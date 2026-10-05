# MCP 健康检查与退避重连状态机重构

- 日期：2026-10-05
- 状态：已确认，待实现
- 涉及模块：`mcp/`、`core/ThoughtCodingContext.java`

## 背景与问题

当前 MCP 重连由 `MCPHealthCheckScheduler` 每 30s 触发一次健康检查，发现 ping 失败后调度退避重连。存在三个问题：

1. **重连循环只跑一次就死**（严重 bug）。`performReconnect` 第一步调用 `mcpService.disconnectServer(serverName)`，把服务器从 `connectedServers` 移除；而 `healthCheckCycle` 遍历的是 `mcpService.getConnectedServers()`。重连失败后服务器从 `connectedServers` 消失，下一次健康检查不再看到它，`consecutiveFailures` 永远停留在 1，指数退避 `1→2→4→…→60` 与 `MAX_CONSECUTIVE_FAILURES=10` 的放弃逻辑在真实崩溃场景下全部不可达。

2. **两个时钟打架**。退避延迟（1s~60s）与健康检查周期（30s）是两个独立时钟：前几轮短退避被 30s 周期吞掉，退避超过 32s 后又造成重连任务积压（单线程调度器串行执行）。

3. **状态重复**。`ServerConnectionState` 同时存在于 `MCPService.serverConfigs`（存 command/args）和 `MCPHealthCheckScheduler.connectionStates`（存退避计数），两份会漂移；`disconnectServer` 清理 `serverConfigs` 时不会同步清理调度器那份。

## 设计目标

将重连逻辑重构为一个**两态/三态状态机**，由调度器自持状态、自驱动重连，彻底消除「重连只跑一次」与「两时钟打架」两个问题，并统一状态为单一数据源。

## 1. 状态机

`ServerConnectionState` 新增状态枚举：

```java
enum State { HEALTHY, RECONNECTING, ABANDONED }
```

转移规则：

```
HEALTHY ──ping失败──► RECONNECTING ──重连成功──► HEALTHY
                        │   ▲
                        │   └──重连失败且<10次：退避递增，自调度下一次
                        │
                        └──重连失败达10次──► ABANDONED（放弃，等手动 /mcp）
```

- HEALTHY：健康检查照常 ping。
- RECONNECTING：健康检查跳过，由退避任务自身调度下一次重连。
- ABANDONED：跳过，需手动 `/mcp` 重连恢复。

## 2. 单一数据源

- 调度器拥有唯一的状态表 `connectionStates`（含 command/args + state + 退避计数 + generation + pendingReconnect）。
- 删除 `MCPService.serverConfigs`（`MCPService.java:26`）重复副本；`MCPService.getServerConfig`/`getStoredCommand`/`getStoredArgs` 相应移除（注意：这里指 `MCPService` 的三个方法，与 `MCPConfig.getServerConfig` 无关；后者返回 `MCPServerConfig`，保留不动）。`getStoredCommand`/`getStoredArgs` 经查全项目无调用点，属死代码。
- 调度器遍历自己的状态表，不再遍历 `getConnectedServers()`。

## 3. 组件改动

### `ServerConnectionState`

- 新增 `volatile State state`
- 新增 `long generation`（防过期任务）
- 新增 `volatile ScheduledFuture<?> pendingReconnect`（用于取消）

### `MCPHealthCheckScheduler`

- `healthCheckCycle`：遍历 `connectionStates`，仅对 HEALTHY 态 ping；失败 → 转 RECONNECTING + 调度首次重连。
- `performReconnect`：
  1. 校验 generation（过期则中止）
  2. 用内部路径关闭旧连接（不通知调度器，避免自取消）
  3. 建新 client 连接
  4. 成功 → `registerReconnectedServer` + 转 HEALTHY + `resetBackoff`
  5. 失败 → `<10` 自调度下一次（状态保持 RECONNECTING），否则转 ABANDONED
- 新增回调 `onServerConnected(name, command, args)` / `onServerDisconnected(name)`
- 删除死代码：`serverAdapters` 表、`registerServerAdapters` 方法（从未被调用）

### `MCPService`

- 新增 `setHealthCheckScheduler(scheduler)`（构造后注入，避免构造期循环依赖）
- `connectToServer` 成功 → 调 `scheduler.onServerConnected(...)`
- `disconnectServer` → 调 `scheduler.onServerDisconnected(...)`
- 新增内部方法 `closeConnectionForReconnect(name)`（只关连接+清工具，**不**通知调度器），供 `performReconnect` 调用；用户态 `disconnectServer` 保持通知语义
- `registerReconnectedServer` 补上 `toolRegistry.register(adapter)`，收拢工具注册职责，调度器不再直接碰 ToolRegistry

### 接线 `ThoughtCodingContext.java`

- 在 `scheduler.start()` 前后注入 `mcpService.setHealthCheckScheduler(scheduler)`（约 `ThoughtCodingContext.java:159`）

## 4. 取消机制

- `onServerDisconnected`：`pendingReconnect.cancel(true)` + `generation++` + 移除状态
- `onServerConnected`：存在 pending 重连则先取消，重置 state=HEALTHY、退避清零
- generation 作为兜底：即使取消有竞态，过期任务执行时发现 generation 不匹配直接 no-op

## 5. 边界情况

| 场景 | 行为 |
|------|------|
| RECONNECTING 中用户 `/mcp disconnect` | 取消 pending 任务 + 移除状态，不会「复活」 |
| ABANDONED 后用户 `/mcp connect` | `onServerConnected` 重置为 HEALTHY |
| 重连成功但发现 0 个工具 | 视为失败，继续退避（与现状一致） |
| 应用 shutdown | `scheduler.stop()` 取消所有 pending 任务 |

## 6. 测试

- 单元测试 `ServerConnectionState`：退避序列 `1→2→4→…→60` 封顶、`resetBackoff` 清零、generation 校验。
- Mockito 测 `MCPHealthCheckScheduler`（mock `MCPService`）：
  - ping 失败 → 转 RECONNECTING
  - 重连失败自调度
  - 达 10 次转 ABANDONED
  - 手动断开取消 pending
- 回归 `mvn test` 确认现有测试（`MCPJsonTest` 等）无破坏。
