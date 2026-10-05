package com.thoughtcoding.mcp;

import com.thoughtcoding.mcp.model.MCPTool;
import com.thoughtcoding.tools.BaseTool;
import com.thoughtcoding.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP服务，管理与多个MCP服务器的连接和工具调用。
 * 支持健康检查、指数退避重连和重连后 ToolRegistry 同步。
 */
public class MCPService {
    private static final Logger log = LoggerFactory.getLogger(MCPService.class);
    private final Map<String, MCPClient> connectedServers = new ConcurrentHashMap<>();
    private final Map<String, BaseTool> mcpTools = new ConcurrentHashMap<>();
    private final ToolRegistry toolRegistry;

    // 工具名 → 服务端名映射（用于正确清理和查找）
    private final Map<String, String> toolToServer = new ConcurrentHashMap<>();

    // 健康检查调度器（由 ThoughtCodingContext 注入；连接/断开时通知）
    private volatile MCPHealthCheckScheduler scheduler;

    public MCPService(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    public List<BaseTool> connectToServer(String serverName, String command, List<String> args) {
        try {
            log.debug("启动MCP服务器: {} - {}", serverName, command);
            log.debug("参数: {}", args);

            // 清理旧连接
            if (connectedServers.containsKey(serverName)) {
                MCPClient existingClient = connectedServers.get(serverName);
                if (existingClient != null && existingClient.isConnected()) {
                    existingClient.disconnect();
                }
                // 清理旧工具映射
                cleanupServerTools(serverName);
                connectedServers.remove(serverName);
            }

            MCPClient client = new MCPClient(serverName);
            boolean connected = client.connect(command, args);

            if (connected) {
                connectedServers.put(serverName, client);

                // 通知调度器：连接成功，重置为 HEALTHY 态
                if (scheduler != null) {
                    scheduler.onServerConnected(serverName, command, args);
                }

                List<MCPTool> mcpToolList = client.getAvailableTools();
                List<BaseTool> baseTools = convertToBaseTools(mcpToolList, serverName);

                // 保存工具到 mcpTools 映射，同时记录工具→服务端关系
                for (int i = 0; i < mcpToolList.size(); i++) {
                    String toolKey = mcpToolList.get(i).getName();
                    mcpTools.put(toolKey, baseTools.get(i));
                    toolToServer.put(toolKey, serverName);
                }

                log.debug("✅ 成功连接MCP服务器: {} ({} 个工具)", serverName, baseTools.size());
                return baseTools;
            } else {
                log.debug("⚠️ 连接MCP服务器失败: {}", serverName);
                return Collections.emptyList();
            }
        } catch (Exception e) {
            log.error("❌ 连接MCP服务器异常: {}", serverName, e);
            return Collections.emptyList();
        }
    }

    private List<BaseTool> convertToBaseTools(List<MCPTool> mcpTools, String serverName) {
        MCPClient client = connectedServers.get(serverName);
        if (client == null) {
            log.warn("无法获取 MCP 客户端实例: {}", serverName);
            return List.of();
        }
        List<BaseTool> baseTools = new ArrayList<>();
        for (MCPTool mcpTool : mcpTools) {
            baseTools.add(new MCPToolAdapter(mcpTool, client));
        }
        return baseTools;
    }

    public Object callTool(String serverName, String toolName, Map<String, Object> arguments) {
        try {
            MCPClient client = connectedServers.get(serverName);
            if (client == null) {
                throw new IllegalStateException("MCP服务器未连接: " + serverName);
            }
            return client.callTool(toolName, arguments);
        } catch (Exception e) {
            log.error("调用工具失败: {}.{}", serverName, toolName, e);
            throw new RuntimeException("工具调用失败: " + e.getMessage(), e);
        }
    }

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

    /**
     * 清理指定服务端在 mcpTools 和 toolToServer 中的所有工具
     */
    private void cleanupServerTools(String serverName) {
        Iterator<Map.Entry<String, String>> iter = toolToServer.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<String, String> entry = iter.next();
            if (serverName.equals(entry.getValue())) {
                String toolName = entry.getKey();
                mcpTools.remove(toolName);
                // 也从 ToolRegistry 中移除
                if (toolRegistry != null) {
                    toolRegistry.unregister(toolName);
                }
                iter.remove();
            }
        }
    }

    public List<String> getConnectedServers() {
        return new ArrayList<>(connectedServers.keySet());
    }

    public List<BaseTool> getServerTools(String serverName) {
        List<BaseTool> tools = new ArrayList<>();
        for (Map.Entry<String, String> entry : toolToServer.entrySet()) {
            if (serverName.equals(entry.getValue())) {
                BaseTool tool = mcpTools.get(entry.getKey());
                if (tool != null) {
                    tools.add(tool);
                }
            }
        }
        return tools;
    }

    public Map<String, BaseTool> getMCPTools() {
        return new HashMap<>(mcpTools);
    }

    public List<String> getAvailableToolNames() {
        return new ArrayList<>(mcpTools.keySet());
    }

    // ────────── 重连 API ──────────

    /**
     * 获取客户端引用（用于健康检查）
     */
    public MCPClient getClient(String serverName) {
        return connectedServers.get(serverName);
    }

    /**
     * 获取所有客户端快照（用于健康检查遍历）
     */
    public Collection<MCPClient> getAllClients() {
        return new ArrayList<>(connectedServers.values());
    }

    /**
     * 重连成功后注册新的客户端和工具适配器。
     * 先清理旧条目，再注册新的。
     */
    public void registerReconnectedServer(
            String serverName,
            MCPClient newClient,
            List<MCPToolAdapter> newAdapters) {

        // 清理旧的工具映射
        cleanupServerTools(serverName);

        // 注册新客户端
        connectedServers.put(serverName, newClient);

        // 注册新工具（收拢到 MCPService，同步 ToolRegistry）
        for (MCPToolAdapter adapter : newAdapters) {
            mcpTools.put(adapter.getName(), adapter);
            toolToServer.put(adapter.getName(), serverName);
            if (toolRegistry != null) {
                toolRegistry.register(adapter);
            }
        }

        log.info("重连后注册完成: {} ({} 个工具)", serverName, newAdapters.size());
    }

    /**
     * 注入健康检查调度器（构造后调用，避免构造期循环依赖）。
     */
    public void setHealthCheckScheduler(MCPHealthCheckScheduler scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * 检查指定服务端是否健康
     */
    public boolean isServerHealthy(String serverName) {
        MCPClient client = connectedServers.get(serverName);
        return client != null && client.isConnected();
    }

    public void shutdown() {
        log.info("关闭所有MCP连接...");
        new ArrayList<>(connectedServers.keySet()).forEach(this::disconnectServer);
    }
}
