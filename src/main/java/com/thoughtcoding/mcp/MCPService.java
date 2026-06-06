package com.thoughtcoding.mcp;

import com.thoughtcoding.mcp.model.MCPTool;
import com.thoughtcoding.tools.BaseTool; // 使用你的 BaseTool 基类
import com.thoughtcoding.tools.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP服务，管理与多个MCP服务器的连接和工具调用
 */
public class MCPService {
    private static final Logger log = LoggerFactory.getLogger(MCPService.class);
    private final Map<String, MCPClient> connectedServers = new ConcurrentHashMap<>();
    private final Map<String, BaseTool> mcpTools = new ConcurrentHashMap<>(); // 改为 BaseTool
    private final ToolRegistry toolRegistry;

    public MCPService(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }
    // 添加 clients 映射
    private final Map<String, MCPClient> clients = new ConcurrentHashMap<>();


    // 🔥 新增3参数方法
    public List<BaseTool> connectToServer(String serverName, String command, List<String> args) {
        try {
            log.debug("启动MCP服务器: {} - {}", serverName, command);
            log.debug("参数: {}", args);

            // 清理旧连接
            if (clients.containsKey(serverName)) {
                MCPClient existingClient = clients.get(serverName);
                if (existingClient != null && existingClient.isConnected()) {
                    existingClient.disconnect();
                }
                clients.remove(serverName);
                connectedServers.remove(serverName); // 🔥 同时清理 connectedServers
            }

            MCPClient client = new MCPClient(serverName);
            boolean connected = client.connect(command, args);

            if (connected) {
                // 🔥 保存到两个映射中
                clients.put(serverName, client);
                connectedServers.put(serverName, client);

                List<MCPTool> mcpToolList = client.getAvailableTools();
                List<BaseTool> baseTools = convertToBaseTools(mcpToolList, serverName);

                // 🔥 保存工具到 mcpTools 映射
                for (int i = 0; i < mcpToolList.size(); i++) {
                    String toolKey = mcpToolList.get(i).getName(); // 使用工具名称作为key
                    mcpTools.put(toolKey, baseTools.get(i));
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
            MCPClient client = clients.get(serverName);
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
        MCPClient client = connectedServers.remove(serverName);
        if (client != null) {
            // 移除相关工具
            mcpTools.entrySet().removeIf(entry -> {
                boolean shouldRemove = entry.getKey().startsWith("mcp:" + serverName + "/");
                if (shouldRemove) {
                    // 根据你的 ToolRegistry 实现，可能需要不同的取消注册方法
                    // 如果没有 unregister 方法，可能需要其他方式处理
                    log.debug("移除MCP工具: {}", entry.getKey());
                }
                return shouldRemove;
            });

            client.disconnect();
            log.debug("已断开MCP服务器: {}", serverName);
        }
    }

    public List<String> getConnectedServers() {
        return new ArrayList<>(connectedServers.keySet());
    }

    public List<BaseTool> getServerTools(String serverName) {
        List<BaseTool> tools = new ArrayList<>();
        mcpTools.forEach((name, tool) -> {
            if (name.startsWith("mcp:" + serverName + "/")) {
                tools.add(tool);
            }
        });
        return tools;
    }

    public Map<String, BaseTool> getMCPTools() {
        return new HashMap<>(mcpTools);
    }

    public List<String> getAvailableToolNames() {
        return new ArrayList<>(mcpTools.keySet());
    }

    public void shutdown() {
        log.info("关闭所有MCP连接...");
        new ArrayList<>(connectedServers.keySet()).forEach(this::disconnectServer);
    }
}