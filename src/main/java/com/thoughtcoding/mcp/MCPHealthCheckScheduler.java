package com.thoughtcoding.mcp;

import com.thoughtcoding.mcp.model.MCPTool;
import com.thoughtcoding.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * MCP 健康检查调度器。
 * 每 30s 检查所有已连接 MCP 服务器的健康状态，
 * 发现断连后使用指数退避策略自动重连，
 * 重连成功后同步 ToolRegistry 中的适配器引用。
 */
public class MCPHealthCheckScheduler {
    private static final Logger log = LoggerFactory.getLogger(MCPHealthCheckScheduler.class);

    private static final long INITIAL_INTERVAL_MS = 30_000L;
    private static final long PING_TIMEOUT_MS = 2_000L;
    private static final int MAX_BACKOFF_SECONDS = 60;
    private static final int INITIAL_BACKOFF_SECONDS = 1;
    private static final int MAX_CONSECUTIVE_FAILURES = 10;

    private final MCPService mcpService;
    private final ToolRegistry toolRegistry;
    private final ScheduledExecutorService scheduler;

    // 每个服务端的退避状态
    private final Map<String, ServerConnectionState> connectionStates;

    // 每个服务端当前注册的工具适配器引用（用于重连后替换）
    private final Map<String, List<MCPToolAdapter>> serverAdapters;

    public MCPHealthCheckScheduler(MCPService mcpService, ToolRegistry toolRegistry) {
        this.mcpService = mcpService;
        this.toolRegistry = toolRegistry;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MCP-HealthCheck");
            t.setDaemon(true);
            return t;
        });
        this.connectionStates = new ConcurrentHashMap<>();
        this.serverAdapters = new ConcurrentHashMap<>();
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(
                this::healthCheckCycle,
                5_000L,  // 初始延迟 5s，让应用先启动
                INITIAL_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
        log.info("MCP 健康检查调度器已启动（间隔 30s）");
    }

    public void stop() {
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("MCP 健康检查调度器已停止");
    }

    /**
     * 注册服务端的工具适配器引用（首次连接时调用）
     */
    public void registerServerAdapters(String serverName, List<MCPToolAdapter> adapters) {
        serverAdapters.put(serverName, new ArrayList<>(adapters));
    }

    /**
     * 每 30s 执行一次的健康检查周期
     */
    private void healthCheckCycle() {
        List<String> serverNames = mcpService.getConnectedServers();
        for (String serverName : serverNames) {
            try {
                checkAndReconnectIfNeeded(serverName);
            } catch (Exception e) {
                log.error("健康检查异常: {}", serverName, e);
            }
        }
    }

    /**
     * 检查单个服务端，如果不健康则触发退避重连
     */
    private void checkAndReconnectIfNeeded(String serverName) {
        MCPClient client = mcpService.getClient(serverName);
        boolean healthy = (client != null) && client.ping((int) PING_TIMEOUT_MS);

        if (healthy) {
            // 健康：检查状态上有无之前累积的失败计数，如有则重置
            ServerConnectionState state = connectionStates.get(serverName);
            if (state != null && state.getConsecutiveFailures() > 0) {
                state.resetBackoff();
                log.info("MCP 服务端健康恢复: {}", serverName);
            }
            return;
        }

        // 不健康 → 启动指数退避重连
        handleReconnection(serverName);
    }

    /**
     * 指数退避重连处理
     */
    private void handleReconnection(String serverName) {
        ServerConnectionState state = connectionStates.computeIfAbsent(
                serverName,
                name -> {
                    ServerConnectionState config = mcpService.getServerConfig(name);
                    if (config != null) {
                        return new ServerConnectionState(name, config.getCommand(), config.getArgs());
                    }
                    return new ServerConnectionState(name, "", new ArrayList<>());
                }
        );

        // 超过最大重试次数，放弃自动重连（持续尝试中每次只记一次日志）
        if (state.getConsecutiveFailures() >= MAX_CONSECUTIVE_FAILURES) {
            if (state.getConsecutiveFailures() == MAX_CONSECUTIVE_FAILURES) {
                log.error("MCP 服务端 {} 连续重连失败 {} 次，放弃自动重连。请检查服务端状态后手动重连。",
                        serverName, MAX_CONSECUTIVE_FAILURES);
                state.incrementFailures(); // 越过 == MAX 值，后续只 return 不重复记日志
            }
            return;
        }

        int delaySeconds = state.getAndIncrementBackoff();
        log.warn("MCP 服务端 {} 断开连接，{}s 后尝试第 {} 次重连",
                serverName, delaySeconds, state.getConsecutiveFailures());

        // 调度延迟重连任务
        scheduler.schedule(
                () -> performReconnect(serverName, state),
                delaySeconds,
                TimeUnit.SECONDS
        );
    }

    /**
     * 执行实际重连：创建新进程 → 发现工具 → 替换适配器 → 同步 ToolRegistry
     */
    private void performReconnect(String serverName, ServerConnectionState state) {
        try {
            log.info("正在重连 MCP 服务端: {} ...", serverName);

            // 1. 断开旧连接（幂等）
            mcpService.disconnectServer(serverName);

            // 2. 创建新 MCPClient 并连接
            MCPClient newClient = new MCPClient(serverName);
            boolean connected = newClient.connect(state.getCommand(), state.getArgs());

            if (!connected) {
                log.warn("重连 {} 失败：连接超时或协议初始化失败", serverName);
                return;
            }

            // 3. 获取新工具列表
            List<MCPTool> newMCPTools = newClient.getAvailableTools();
            if (newMCPTools.isEmpty()) {
                log.warn("重连 {} 后未发现任何工具", serverName);
                newClient.disconnect();
                return;
            }

            // 4. 创建新的 MCPToolAdapter 实例（绑定新的 MCPClient）
            List<MCPToolAdapter> newAdapters = new ArrayList<>();
            for (MCPTool mcpTool : newMCPTools) {
                newAdapters.add(new MCPToolAdapter(mcpTool, newClient));
            }

            // 5. 从 ToolRegistry 中移除旧适配器
            List<MCPToolAdapter> oldAdapters = serverAdapters.get(serverName);
            if (oldAdapters != null) {
                for (MCPToolAdapter oldAdapter : oldAdapters) {
                    toolRegistry.unregister(oldAdapter.getName());
                }
            }

            // 6. 注册新适配器到 ToolRegistry
            for (MCPToolAdapter adapter : newAdapters) {
                toolRegistry.register(adapter);
            }

            // 7. 更新 serverAdapters 缓存
            serverAdapters.put(serverName, newAdapters);

            // 8. 在 MCPService 中注册重连后的客户端
            mcpService.registerReconnectedServer(serverName, newClient, newAdapters);

            // 9. 重置退避状态
            state.resetBackoff();
            log.info("重连 MCP 服务端 {} 成功 ({} 个工具)", serverName, newAdapters.size());

        } catch (Exception e) {
            log.error("重连 MCP 服务端 {} 异常: {}", serverName, e.getMessage());
        }
    }
}
