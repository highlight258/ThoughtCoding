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

            // 4. 注册前再校验一次版本：连接过程中用户可能手动断开，避免旧重连结果被注册
            if (state.getGeneration() != generation) {
                log.warn("重连 {} 期间连接状态已变化，丢弃重连结果", serverName);
                newClient.disconnect();
                return;
            }

            // 5. 创建适配器并注册（MCPService 负责工具注册与清理）
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
