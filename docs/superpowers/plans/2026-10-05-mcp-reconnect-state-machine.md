# MCP 健康检查与退避重连状态机重构 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 MCP 重连逻辑重构为两态/三态状态机，修复「重连只跑一次就死」与「两时钟打架」两个 bug，统一连接状态为单一数据源。

**Architecture:** `MCPHealthCheckScheduler` 自持 `Map<String, ServerConnectionState>` 状态表作为唯一数据源，遍历自己的状态表（而非 `MCPService.getConnectedServers()`）。`ServerConnectionState` 新增 `State{HEALTHY, RECONNECTING, ABANDONED}` 枚举 + `generation`（过期任务检测）+ `pendingReconnect`（取消）。`MCPService` 删除重复的 `serverConfigs`，在 connect/disconnect 时通过 `onServerConnected`/`onServerDisconnected` 回调通知调度器。

**Tech Stack:** Java 17, JUnit 5, Mockito 5.7.0

**设计文档:** `docs/superpowers/specs/2026-10-05-mcp-reconnect-state-machine-design.md`

---

## 文件结构

- 修改 `src/main/java/com/thoughtcoding/mcp/ServerConnectionState.java` — 状态机字段与退避逻辑
- 修改 `src/main/java/com/thoughtcoding/mcp/MCPService.java` — 单一数据源 + 通知回调 + 工具注册收拢
- 修改 `src/main/java/com/thoughtcoding/mcp/MCPHealthCheckScheduler.java` — 状态机重写
- 修改 `src/main/java/com/thoughtcoding/core/ThoughtCodingContext.java` — 接线重排
- 新建 `src/test/java/com/thoughtcoding/mcp/ServerConnectionStateTest.java`
- 新建 `src/test/java/com/thoughtcoding/mcp/MCPHealthCheckSchedulerTest.java`

---

## Task 1: ServerConnectionState 新增状态机字段

**Files:**
- Modify: `src/main/java/com/thoughtcoding/mcp/ServerConnectionState.java`

- [ ] **Step 1: 用完整新内容替换 ServerConnectionState.java**

将整个文件替换为以下内容：

```java
package com.thoughtcoding.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

/**
 * MCP 服务端连接状态，用于状态机 + 指数退避重连追踪。
 */
public class ServerConnectionState {

    /** 连接状态机：健康 → 重连中 → 放弃 */
    public enum State {
        HEALTHY,       // 已连接，接受健康检查
        RECONNECTING,  // 断开中，退避重连循环自驱动
        ABANDONED      // 连续失败达上限，放弃自动重连，需手动 /mcp 重连
    }

    private final String serverName;
    private volatile String command;
    private volatile List<String> args;
    private volatile State state;
    private volatile long generation;
    private volatile int consecutiveFailures;
    private volatile int backoffSeconds;
    private volatile ScheduledFuture<?> pendingReconnect;

    public ServerConnectionState(String serverName, String command, List<String> args) {
        this.serverName = serverName;
        this.command = command;
        this.args = args != null ? new ArrayList<>(args) : new ArrayList<>();
        this.state = State.HEALTHY;
        this.generation = 0;
        this.consecutiveFailures = 0;
        this.backoffSeconds = 0;
        this.pendingReconnect = null;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public long getGeneration() {
        return generation;
    }

    /** 递增 generation，使已排队的过期重连任务失效 */
    public long incrementGeneration() {
        return ++generation;
    }

    public ScheduledFuture<?> getPendingReconnect() {
        return pendingReconnect;
    }

    public void setPendingReconnect(ScheduledFuture<?> pendingReconnect) {
        this.pendingReconnect = pendingReconnect;
    }

    /**
     * 指数退避：1 → 2 → 4 → 8 → 16 → 32 → 60 → 60 ...
     * @return 当前退避延迟（秒）
     */
    public int getAndIncrementBackoff() {
        consecutiveFailures++;
        if (backoffSeconds == 0) {
            backoffSeconds = 1;
        } else {
            backoffSeconds = Math.min(backoffSeconds * 2, 60);
        }
        return backoffSeconds;
    }

    /** 健康恢复或重连成功时重置退避状态 */
    public void resetBackoff() {
        consecutiveFailures = 0;
        backoffSeconds = 0;
    }

    public String getServerName() {
        return serverName;
    }

    public String getCommand() {
        return command;
    }

    public List<String> getArgs() {
        return args;
    }

    public void updateConnectionInfo(String command, List<String> args) {
        this.command = command;
        this.args = args != null ? new ArrayList<>(args) : new ArrayList<>();
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public int getBackoffSeconds() {
        return backoffSeconds;
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn -q compile`
Expected: BUILD SUCCESS（旧调用点 `incrementFailures()` 已在此文件删除，但调度器尚未重写，仍引用 `incrementFailures` —— 见 Task 4 一并处理。若此处编译失败属预期，继续 Task 2~4 后再编译。）

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/thoughtcoding/mcp/ServerConnectionState.java
git commit -m "refactor: ServerConnectionState 新增状态机字段与退避逻辑"
```

---

## Task 2: ServerConnectionState 单元测试

**Files:**
- Create: `src/test/java/com/thoughtcoding/mcp/ServerConnectionStateTest.java`

- [ ] **Step 1: 写测试**

```java
package com.thoughtcoding.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ServerConnectionStateTest {

    @Test
    void backoffSequenceCapsAtSixtySeconds() {
        ServerConnectionState state = new ServerConnectionState("s", "cmd", List.of());
        int[] expected = {1, 2, 4, 8, 16, 32, 60, 60, 60};
        for (int expectedDelay : expected) {
            assertEquals(expectedDelay, state.getAndIncrementBackoff());
        }
    }

    @Test
    void resetBackoffClearsFailuresAndDelay() {
        ServerConnectionState state = new ServerConnectionState("s", "cmd", List.of());
        state.getAndIncrementBackoff();
        state.getAndIncrementBackoff();
        state.getAndIncrementBackoff();
        state.resetBackoff();
        assertEquals(0, state.getConsecutiveFailures());
        assertEquals(0, state.getBackoffSeconds());
        assertEquals(1, state.getAndIncrementBackoff());
    }

    @Test
    void initialStateIsHealthy() {
        ServerConnectionState state = new ServerConnectionState("s", "cmd", List.of());
        assertEquals(ServerConnectionState.State.HEALTHY, state.getState());
        assertEquals(0, state.getGeneration());
    }

    @Test
    void incrementGenerationIsMonotonic() {
        ServerConnectionState state = new ServerConnectionState("s", "cmd", List.of());
        assertEquals(1, state.incrementGeneration());
        assertEquals(2, state.incrementGeneration());
    }

    @Test
    void updateConnectionInfoReplacesCommandAndArgs() {
        ServerConnectionState state = new ServerConnectionState("s", "old", List.of("a"));
        state.updateConnectionInfo("new", List.of("b", "c"));
        assertEquals("new", state.getCommand());
        assertEquals(List.of("b", "c"), state.getArgs());
    }
}
```

- [ ] **Step 2: 运行测试**

Run: `mvn -q -Dtest=ServerConnectionStateTest test`
Expected: BUILD SUCCESS，5 个测试通过

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/thoughtcoding/mcp/ServerConnectionStateTest.java
git commit -m "test: ServerConnectionState 退避序列与 generation 单测"
```

---

## Task 3: MCPService 单一数据源 + 通知回调 + 工具注册收拢

**Files:**
- Modify: `src/main/java/com/thoughtcoding/mcp/MCPService.java`

- [ ] **Step 1: 删除 `serverConfigs` 字段（第 26 行）**

删除：
```java
    // 服务端连接配置存储（用于重连时重建连接）
    private final Map<String, ServerConnectionState> serverConfigs = new ConcurrentHashMap<>();
```
替换为调度器引用字段：
```java
    // 健康检查调度器（由 ThoughtCodingContext 注入；连接/断开时通知）
    private volatile MCPHealthCheckScheduler scheduler;
```

- [ ] **Step 2: 在 `connectToServer` 中移除 `serverConfigs.put` 并通知调度器**

将（原第 51-57 行附近）：
```java
            if (connected) {
                connectedServers.put(serverName, client);

                // 存储连接配置用于后续重连
                serverConfigs.put(serverName, new ServerConnectionState(serverName, command, args));

                List<MCPTool> mcpToolList = client.getAvailableTools();
```
替换为：
```java
            if (connected) {
                connectedServers.put(serverName, client);

                // 通知调度器：连接成功，重置为 HEALTHY 态
                if (scheduler != null) {
                    scheduler.onServerConnected(serverName, command, args);
                }

                List<MCPTool> mcpToolList = client.getAvailableTools();
```

- [ ] **Step 3: 拆分 `disconnectServer`，新增 `closeConnectionForReconnect`**

将（原第 105-117 行）：
```java
    public void disconnectServer(String serverName) {
        MCPClient client = connectedServers.remove(serverName);
        if (client != null) {
            // 清理 mcpTools 和 toolToServer 中的相关工具条目
            cleanupServerTools(serverName);

            // 清理服务端配置
            serverConfigs.remove(serverName);

            client.disconnect();
            log.debug("已断开MCP服务器: {}", serverName);
        }
    }
```
替换为：
```java
    public void disconnectServer(String serverName) {
        closeConnectionForReconnect(serverName);
        if (scheduler != null) {
            scheduler.onServerDisconnected(serverName);
        }
        log.debug("已断开MCP服务器: {}", serverName);
    }

    /**
     * 仅供调度器重连时使用：关闭旧连接并清理工具，但不通知调度器（避免重连任务自取消）。
     */
    public void closeConnectionForReconnect(String serverName) {
        MCPClient client = connectedServers.remove(serverName);
        if (client != null) {
            cleanupServerTools(serverName);
            client.disconnect();
        }
    }
```

- [ ] **Step 4: 删除 `getServerConfig`/`getStoredCommand`/`getStoredArgs` 三个方法**

删除原第 165-186 行的三个方法：
```java
    /**
     * 获取指定服务端的连接配置（用于重连）
     */
    public ServerConnectionState getServerConfig(String serverName) {
        return serverConfigs.get(serverName);
    }

    /**
     * 获取存储的命令
     */
    public String getStoredCommand(String serverName) {
        ServerConnectionState config = serverConfigs.get(serverName);
        return config != null ? config.getCommand() : null;
    }

    /**
     * 获取存储的参数
     */
    public List<String> getStoredArgs(String serverName) {
        ServerConnectionState config = serverConfigs.get(serverName);
        return config != null ? config.getArgs() : new ArrayList<>();
    }
```

- [ ] **Step 5: `registerReconnectedServer` 中补上 toolRegistry 注册**

将：
```java
        // 注册新工具
        for (MCPToolAdapter adapter : newAdapters) {
            mcpTools.put(adapter.getName(), adapter);
            toolToServer.put(adapter.getName(), serverName);
        }
```
替换为：
```java
        // 注册新工具（收拢到 MCPService，同步 ToolRegistry）
        for (MCPToolAdapter adapter : newAdapters) {
            mcpTools.put(adapter.getName(), adapter);
            toolToServer.put(adapter.getName(), serverName);
            if (toolRegistry != null) {
                toolRegistry.register(adapter);
            }
        }
```

- [ ] **Step 6: 新增 `setHealthCheckScheduler` 方法**

在类中合适位置（例如 `registerReconnectedServer` 之后）新增：
```java
    /**
     * 注入健康检查调度器（构造后调用，避免构造期循环依赖）。
     */
    public void setHealthCheckScheduler(MCPHealthCheckScheduler scheduler) {
        this.scheduler = scheduler;
    }
```

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/thoughtcoding/mcp/MCPService.java
git commit -m "refactor: MCPService 统一连接状态数据源并收拢工具注册"
```

---

## Task 4: MCPHealthCheckScheduler 状态机重写

**Files:**
- Modify: `src/main/java/com/thoughtcoding/mcp/MCPHealthCheckScheduler.java`

- [ ] **Step 1: 用完整新内容替换整个文件**

```java
package com.thoughtcoding.mcp;

import com.thoughtcoding.mcp.model.MCPTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * MCP 健康检查与重连状态机。
 *
 * 每个已连接的 MCP 服务器处于三种状态之一（见 ServerConnectionState.State）：
 * - HEALTHY：每 30s ping 一次
 * - RECONNECTING：健康检查暂停，由退避任务自身调度下一次重连
 * - ABANDONED：连续失败达上限，放弃自动重连，需手动 /mcp 重连
 *
 * 状态由本调度器持有（单一数据源），MCPService 在 connect/disconnect 时通过
 * onServerConnected / onServerDisconnected 回调通知本调度器。
 */
public class MCPHealthCheckScheduler {
    private static final Logger log = LoggerFactory.getLogger(MCPHealthCheckScheduler.class);

    private static final long INITIAL_INTERVAL_MS = 30_000L;
    private static final long PING_TIMEOUT_MS = 2_000L;
    private static final int MAX_CONSECUTIVE_FAILURES = 10;

    private final MCPService mcpService;
    private final ScheduledExecutorService scheduler;
    private final Function<String, MCPClient> clientFactory;

    // 单一数据源：serverName → 连接状态
    private final Map<String, ServerConnectionState> connectionStates = new ConcurrentHashMap<>();

    public MCPHealthCheckScheduler(MCPService mcpService) {
        this(mcpService, MCPClient::new, defaultScheduler());
    }

    // 包内可见，供测试注入 fake client 工厂与调度器
    MCPHealthCheckScheduler(MCPService mcpService,
                            Function<String, MCPClient> clientFactory,
                            ScheduledExecutorService scheduler) {
        this.mcpService = mcpService;
        this.clientFactory = clientFactory;
        this.scheduler = scheduler;
    }

    private static ScheduledExecutorService defaultScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MCP-HealthCheck");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(
                this::healthCheckCycle,
                5_000L,
                INITIAL_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
        log.info("MCP 健康检查调度器已启动（间隔 30s）");
    }

    public void stop() {
        for (ServerConnectionState state : connectionStates.values()) {
            cancelPending(state);
        }
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("MCP 健康检查调度器已停止");
    }

    // ────────── 回调（由 MCPService 调用） ──────────

    /** 服务端连接成功（首次连接或手动重连），重置为 HEALTHY 态 */
    public void onServerConnected(String serverName, String command, List<String> args) {
        ServerConnectionState state = connectionStates.computeIfAbsent(
                serverName, n -> new ServerConnectionState(n, command, args));
        state.incrementGeneration();
        cancelPending(state);
        state.updateConnectionInfo(command, args);
        state.resetBackoff();
        state.setState(ServerConnectionState.State.HEALTHY);
    }

    /** 服务端被手动断开，取消重连并移除状态 */
    public void onServerDisconnected(String serverName) {
        ServerConnectionState state = connectionStates.remove(serverName);
        if (state != null) {
            state.incrementGeneration();
            cancelPending(state);
        }
    }

    // ────────── 健康检查 ──────────

    // 包内可见，供测试触发单轮健康检查
    void healthCheckCycle() {
        for (ServerConnectionState state : connectionStates.values()) {
            if (state.getState() != ServerConnectionState.State.HEALTHY) {
                continue;  // RECONNECTING/ABANDONED 由重连任务/手动重连负责
            }
            try {
                checkHealthyServer(state);
            } catch (Exception e) {
                log.error("健康检查异常: {}", state.getServerName(), e);
            }
        }
    }

    private void checkHealthyServer(ServerConnectionState state) {
        String serverName = state.getServerName();
        MCPClient client = mcpService.getClient(serverName);
        boolean healthy = client != null && client.ping((int) PING_TIMEOUT_MS);

        if (healthy) {
            return;
        }

        log.warn("MCP 服务端 {} 健康检查失败，进入重连状态", serverName);
        state.resetBackoff();
        state.setState(ServerConnectionState.State.RECONNECTING);
        scheduleReconnect(state);
    }

    // ────────── 重连 ──────────

    private void scheduleReconnect(ServerConnectionState state) {
        long generation = state.getGeneration();
        int delaySeconds = state.getAndIncrementBackoff();
        log.warn("MCP 服务端 {} 断开连接，{}s 后尝试第 {} 次重连",
                state.getServerName(), delaySeconds, state.getConsecutiveFailures());

        ScheduledFuture<?> future = scheduler.schedule(
                () -> performReconnect(state.getServerName(), state, generation),
                delaySeconds,
                TimeUnit.SECONDS
        );
        state.setPendingReconnect(future);
    }

    // 包内可见，供测试直接调用
    void performReconnect(String serverName, ServerConnectionState state, long generation) {
        // 过期任务兜底：排队期间被手动断开/重连，则直接中止
        if (state.getGeneration() != generation) {
            return;
        }

        try {
            log.info("正在重连 MCP 服务端: {} ...", serverName);

            // 1. 关闭旧连接（内部路径，不通知调度器，避免自取消）
            mcpService.closeConnectionForReconnect(serverName);

            // 2. 创建新客户端并连接
            MCPClient newClient = clientFactory.apply(serverName);
            boolean connected = newClient.connect(state.getCommand(), state.getArgs());

            if (!connected) {
                onReconnectFailed(state);
                return;
            }

            // 3. 发现工具
            List<MCPTool> newMCPTools = newClient.getAvailableTools();
            if (newMCPTools.isEmpty()) {
                log.warn("重连 {} 后未发现任何工具", serverName);
                newClient.disconnect();
                onReconnectFailed(state);
                return;
            }

            // 4. 创建适配器并注册（MCPService 负责工具注册与清理）
            List<MCPToolAdapter> newAdapters = new ArrayList<>();
            for (MCPTool mcpTool : newMCPTools) {
                newAdapters.add(new MCPToolAdapter(mcpTool, newClient));
            }
            mcpService.registerReconnectedServer(serverName, newClient, newAdapters);

            // 5. 成功 → 转 HEALTHY
            state.resetBackoff();
            state.setState(ServerConnectionState.State.HEALTHY);
            log.info("重连 MCP 服务端 {} 成功 ({} 个工具)", serverName, newAdapters.size());

        } catch (Exception e) {
            log.error("重连 MCP 服务端 {} 异常: {}", serverName, e.getMessage());
            onReconnectFailed(state);
        }
    }

    private void onReconnectFailed(ServerConnectionState state) {
        if (state.getConsecutiveFailures() >= MAX_CONSECUTIVE_FAILURES) {
            state.setState(ServerConnectionState.State.ABANDONED);
            log.error("MCP 服务端 {} 连续重连失败 {} 次，放弃自动重连。请检查服务端状态后手动重连。",
                    state.getServerName(), state.getConsecutiveFailures());
            return;
        }
        scheduleReconnect(state);
    }

    private void cancelPending(ServerConnectionState state) {
        ScheduledFuture<?> future = state.getPendingReconnect();
        if (future != null) {
            future.cancel(false);
            state.setPendingReconnect(null);
        }
    }

    // 包内可见，供测试获取状态
    ServerConnectionState getConnectionState(String serverName) {
        return connectionStates.get(serverName);
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/thoughtcoding/mcp/MCPHealthCheckScheduler.java
git commit -m "refactor: MCPHealthCheckScheduler 重写为状态机驱动重连"
```

---

## Task 5: MCPHealthCheckScheduler 单元测试

**Files:**
- Create: `src/test/java/com/thoughtcoding/mcp/MCPHealthCheckSchedulerTest.java`

- [ ] **Step 1: 写测试**

```java
package com.thoughtcoding.mcp;

import com.thoughtcoding.mcp.model.MCPTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class MCPHealthCheckSchedulerTest {

    private MCPService mcpService;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> mockFuture;

    @BeforeEach
    void setUp() {
        mcpService = mock(MCPService.class);
        executor = mock(ScheduledExecutorService.class);
        mockFuture = mock(ScheduledFuture.class);
        when(executor.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenReturn(mockFuture);
    }

    private MCPHealthCheckScheduler newScheduler(Function<String, MCPClient> clientFactory) {
        return new MCPHealthCheckScheduler(mcpService, clientFactory, executor);
    }

    @Test
    void pingFailureTransitionsToReconnecting() {
        MCPClient client = mock(MCPClient.class);
        when(client.ping(anyInt())).thenReturn(false);
        when(mcpService.getClient("filesystem")).thenReturn(client);

        MCPHealthCheckScheduler scheduler = newScheduler(name -> mock(MCPClient.class));
        scheduler.onServerConnected("filesystem", "cmd", List.of());

        scheduler.healthCheckCycle();

        ServerConnectionState state = scheduler.getConnectionState("filesystem");
        assertEquals(ServerConnectionState.State.RECONNECTING, state.getState());
        verify(executor).schedule(any(Runnable.class), eq(1L), eq(TimeUnit.SECONDS));
    }

    @Test
    void reconnectSuccessReturnsToHealthy() {
        MCPClient client = mock(MCPClient.class);
        when(client.connect(anyString(), any())).thenReturn(true);
        MCPTool tool = mock(MCPTool.class);
        when(tool.getName()).thenReturn("test_tool");
        when(tool.getDescription()).thenReturn("desc");
        when(client.getAvailableTools()).thenReturn(List.of(tool));

        MCPHealthCheckScheduler scheduler = newScheduler(name -> client);
        scheduler.onServerConnected("filesystem", "cmd", List.of());
        ServerConnectionState state = scheduler.getConnectionState("filesystem");
        state.setState(ServerConnectionState.State.RECONNECTING);

        scheduler.performReconnect("filesystem", state, state.getGeneration());

        assertEquals(ServerConnectionState.State.HEALTHY, state.getState());
        verify(mcpService).registerReconnectedServer(eq("filesystem"), eq(client), anyList());
    }

    @Test
    void reconnectGivesUpAfterTenFailures() {
        MCPClient client = mock(MCPClient.class);
        when(client.connect(anyString(), any())).thenReturn(false);

        MCPHealthCheckScheduler scheduler = newScheduler(name -> client);
        scheduler.onServerConnected("filesystem", "cmd", List.of());
        ServerConnectionState state = scheduler.getConnectionState("filesystem");
        state.setState(ServerConnectionState.State.RECONNECTING);

        for (int i = 0; i < 10; i++) {
            scheduler.performReconnect("filesystem", state, state.getGeneration());
        }

        assertEquals(ServerConnectionState.State.ABANDONED, state.getState());
    }

    @Test
    void manualDisconnectCancelsPendingReconnect() {
        MCPClient client = mock(MCPClient.class);
        when(client.ping(anyInt())).thenReturn(false);
        when(mcpService.getClient("filesystem")).thenReturn(client);

        AtomicReference<Runnable> captured = new AtomicReference<>();
        when(executor.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenAnswer(inv -> {
                    captured.set(inv.getArgument(0));
                    return mockFuture;
                });

        MCPHealthCheckScheduler scheduler = newScheduler(name -> mock(MCPClient.class));
        scheduler.onServerConnected("filesystem", "cmd", List.of());
        scheduler.healthCheckCycle();

        scheduler.onServerDisconnected("filesystem");

        verify(mockFuture).cancel(false);
        assertNull(scheduler.getConnectionState("filesystem"));

        Runnable stale = captured.get();
        assertNotNull(stale);
        stale.run();
        verify(mcpService, never()).closeConnectionForReconnect(anyString());
    }
}
```

- [ ] **Step 2: 运行测试**

Run: `mvn -q -Dtest=MCPHealthCheckSchedulerTest test`
Expected: BUILD SUCCESS，4 个测试通过

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/thoughtcoding/mcp/MCPHealthCheckSchedulerTest.java
git commit -m "test: MCPHealthCheckScheduler 状态转移单测"
```

---

## Task 6: ThoughtCodingContext 接线重排

**Files:**
- Modify: `src/main/java/com/thoughtcoding/core/ThoughtCodingContext.java`

- [ ] **Step 1: 在 MCP 服务创建后提前创建调度器并注入**

在 `MCPToolManager mcpToolManager = new MCPToolManager(mcpService, mcpConfig);` 之后（原第 91 行后）新增：

```java
        // 🔥 提前创建 MCP 健康检查调度器并注入 MCPService，
        //    使初始化连接（initializeMCPTools）时能通过 onServerConnected 建立状态
        MCPHealthCheckScheduler scheduler = null;
        if (mcpConfig != null && mcpConfig.isEnabled()) {
            scheduler = new MCPHealthCheckScheduler(mcpService);
            mcpService.setHealthCheckScheduler(scheduler);
        }
```

- [ ] **Step 2: 替换末尾的调度器启动块**

将原第 157-168 行：
```java
        // 🔥 启动 MCP 健康检查调度器
        if (mcpConfig != null && mcpConfig.isEnabled()) {
            MCPHealthCheckScheduler scheduler = new MCPHealthCheckScheduler(mcpService, toolRegistry);
            scheduler.start();
            ctx.mcpHealthCheckScheduler = scheduler;

            // 注册 JVM 关闭钩子：确保退出时清理 MCP 子进程，防止僵尸进程
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                scheduler.stop();
                mcpService.shutdown();
            }, "MCP-ShutdownHook"));
        }
```
替换为：
```java
        // 🔥 启动 MCP 健康检查调度器
        if (scheduler != null) {
            scheduler.start();
            ctx.mcpHealthCheckScheduler = scheduler;

            // 注册 JVM 关闭钩子：确保退出时清理 MCP 子进程，防止僵尸进程
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                scheduler.stop();
                mcpService.shutdown();
            }, "MCP-ShutdownHook"));
        }
```

- [ ] **Step 3: 编译验证**

Run: `mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/thoughtcoding/core/ThoughtCodingContext.java
git commit -m "fix: 重排调度器接线顺序，初始化连接前注入状态机"
```

---

## Task 7: 全量回归与推送

**Files:**
- 无新增，验证 + 推送

- [ ] **Step 1: 全量测试**

Run: `mvn test`
Expected: BUILD SUCCESS，所有既有测试（DirectCommandExecutorTest、AppTest 等）与新增测试全部通过

- [ ] **Step 2: 推送 GitHub**

```bash
git push origin main
```

---

## Self-Review 记录

- **Spec 覆盖**：状态机（Task 1/4）、单一数据源（Task 3）、健康检查遍历状态表（Task 4 `healthCheckCycle`）、取消机制（Task 4 `onServerDisconnected`/`cancelPending`）、工具注册收拢（Task 3 Step 5）、接线（Task 6）、测试（Task 2/5）。全部覆盖。
- **占位符**：无 TBD/TODO。
- **类型一致性**：`performReconnect(String, ServerConnectionState, long)` 在 Task 4 定义与 Task 5 测试调用一致；`getConnectionState`/`healthCheckCycle`/`performReconnect` 均为包内可见，测试同包访问一致。
