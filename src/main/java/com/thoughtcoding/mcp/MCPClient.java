package com.thoughtcoding.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.mcp.model.*;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * MCP客户端，管理与单个MCP服务器的连接和通信
 */
public class MCPClient {
    private static final Logger log = LoggerFactory.getLogger(MCPClient.class); // 应该是 MCPClient.class
    private final ObjectMapper objectMapper = new ObjectMapper();
    private Process process;
    private BufferedReader reader;
    private BufferedWriter writer;
    private final Map<String, MCPTool> availableTools = new ConcurrentHashMap<>();
    // 串行化 JSON-RPC I/O，防止并发调用时 writer 交叉写入导致协议流损坏
    private final Object ioLock = new Object();

    private Thread errorMonitorThread;
    // 响应队列 + 独立读线程（替换 100ms 轮询）
    // 有界队列：防止服务端异常刷屏时内存无限增长；满时读线程阻塞形成背压
    private static final int RESPONSE_QUEUE_CAPACITY = 1024;
    private final BlockingQueue<String> responseQueue = new LinkedBlockingQueue<>(RESPONSE_QUEUE_CAPACITY);
    private Thread readerThread;

    // 进程启动等待上限（轮询存活状态，秒退时快速失败）
    private static final long STARTUP_WAIT_MS = 2000L;

    private boolean initialized = false;
    private final String serverName;

    // 存储用于重连的配置
    private String command;
    private List<String> args;

    public MCPClient(String serverName) {
        this.serverName = serverName;
    }

    public boolean connect(String fullCommand, List<String> args) {
        try {
            // 分割完整命令为命令和参数，并去除引号
            String[] parts = fullCommand.split("\\s+");
            String command = parts[0].replace("\"", "");  // 去除引号

            List<String> commandList = new ArrayList<>();
            commandList.add(command);

            // 添加命令的其他部分作为参数，并去除引号
            for (int i = 1; i < parts.length; i++) {
                String arg = parts[i].replace("\"", "");  // 去除引号
                commandList.add(arg);
            }

            // 添加额外的参数
            if (args != null && !args.isEmpty()) {
                for (String arg : args) {
                    commandList.add(arg.replace("\"", ""));  // 去除引号
                }
            }

            log.debug("完整命令: {}", String.join(" ", commandList));
            log.debug("工作目录: {}", System.getProperty("user.dir"));

            ProcessBuilder pb = new ProcessBuilder(commandList);
            pb.directory(new File(System.getProperty("user.dir")));
            // 🔥 关键修复：不合并错误流，分开处理
            pb.redirectErrorStream(false);

            process = pb.start();

            // 🔥 启动错误流监控（只监控错误，不影响主输出）
            startErrorMonitoring();

            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));

            // 🔥 启动独立读线程，持续把服务端 stdout 读入响应队列
            startReaderThread();

            // 等待进程启动：轮询存活状态，进程秒退时快速失败（而非固定 sleep 2s）
            long startupDeadline = System.currentTimeMillis() + STARTUP_WAIT_MS;
            while (System.currentTimeMillis() < startupDeadline) {
                if (!process.isAlive()) {
                    int exitCode = process.exitValue();
                    log.error("❌ MCP服务器进程启动失败，退出码: {}", exitCode);
                    return false;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            log.debug("✅ MCP 进程已启动，开始协议初始化...");

            if (initializeProtocol()) {
                listTools();
                initialized = true;
                // 存储配置用于后续重连
                this.command = fullCommand;
                this.args = args != null ? new ArrayList<>(args) : new ArrayList<>();
                log.debug("✅ MCP客户端初始化成功: {} ({} 个工具)", serverName, availableTools.size());
                return true;
            } else {
                log.error("❌ MCP 协议初始化失败");
            }

        } catch (Exception e) {
            log.error("❌ 连接MCP服务器失败: {}", serverName, e);
        }
        return false;
    }

    /**
     * 🔥 只监控错误流，避免和主输入流冲突
     */
    private void startErrorMonitoring() {
        Thread errorThread = new Thread(() -> {
            try (BufferedReader errorReader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = errorReader.readLine()) != null) {
                    // 过滤 npm 无关的错误
                    if (line.contains("npm ERR!") || line.contains("npm WARN") ||
                            line.contains("node_cache") || line.contains("_cacache") ||
                            line.contains("EPERM") || line.contains("operation not permitted")) {
                        continue;
                    }

                    // 🔥 移除 MCP stderr 日志，保持输出简洁
                    // 不再输出 MCP 服务器的标准错误流信息
                }
            } catch (Exception e) {
                log.debug("MCP 错误流监控线程正常退出: {}", serverName);
            }
        });
        errorThread.setDaemon(true);
        errorThread.setName("MCP-Error-" + serverName);
        errorThread.start();
        this.errorMonitorThread = errorThread;
    }

    /**
     * 🔥 启动独立读线程，持续把服务端 stdout 读入响应队列（替换 100ms 轮询）。
     * 进程退出时 readLine 返回 null，读线程自然退出。
     */
    private void startReaderThread() {
        readerThread = new Thread(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    responseQueue.put(line);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.debug("MCP 读线程被中断: {}", serverName);
            } catch (Exception e) {
                log.debug("MCP 读线程退出: {}", serverName);
            }
        });
        readerThread.setDaemon(true);
        readerThread.setName("MCP-Reader-" + serverName);
        readerThread.start();
    }

    private void startOutputMonitoring() {
        // 🔥 已废弃：不再使用此方法，避免和 reader 冲突
    }

    private boolean initializeProtocol() throws IOException {
        try {
            // 🔥 使用 MCPRequest 类来确保 JSON 序列化正确
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("protocolVersion", "2024-11-05");

            Map<String, Object> capabilities = new LinkedHashMap<>();
            Map<String, Object> roots = new LinkedHashMap<>();
            roots.put("listChanged", true);
            capabilities.put("roots", roots);
            capabilities.put("sampling", new LinkedHashMap<>());
            params.put("capabilities", capabilities);

            Map<String, Object> clientInfo = new LinkedHashMap<>();
            clientInfo.put("name", "ThoughtCoding");
            clientInfo.put("version", "1.0.0");
            params.put("clientInfo", clientInfo);

            log.debug("发送初始化请求...");

            // 使用 MCPRequest 确保正确序列化
            MCPRequest request = new MCPRequest("initialize", params);
            request.setId("1");  // 使用字符串 ID

            String json = objectMapper.writeValueAsString(request);
            log.debug("发送的JSON: {}", json);
            writer.write(json);
            writer.newLine();
            writer.flush();

            // 读取并记录所有输出，用于调试
            log.debug("等待MCP服务器响应...");
            MCPResponse response = readResponse(15000);

            if (response != null) {
                log.debug("收到初始化响应: {}", response);
                if (response.getError() == null) {
                    // MCP 握手最后一步：发送 notifications/initialized 通知（无 id，服务端不回复）
                    try {
                        sendNotification("notifications/initialized", null);
                        log.debug("✅ 已发送 notifications/initialized 通知");
                    } catch (Exception e) {
                        // 通知发送失败不影响主流程（严格服务端可能仍会拒绝后续请求，但此处不中断）
                        log.warn("⚠️ 发送 initialized 通知失败（已忽略）: {}", e.getMessage());
                    }
                    log.debug("✅ MCP协议初始化成功");
                    return true;
                } else {
                    log.error("❌ MCP协议初始化错误: {}", response.getError().getMessage());
                }
            }

            return false;

        } catch (Exception e) {
            log.error("❌ 协议初始化异常: {}", e.getMessage(), e);
            return false;
        }
    }

    private void listTools() throws IOException {
        MCPRequest request = new MCPRequest("tools/list", Map.of());
        sendRequest(request);

        MCPResponse response = readResponse(3000);
        if (response != null && response.getResult() instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) response.getResult();
            Object toolsObj = result.get("tools");
            List<Map<String, Object>> toolsList;
            if (toolsObj instanceof List) {
                toolsList = (List<Map<String, Object>>) toolsObj;
            } else {
                toolsList = new ArrayList<>();
            }

            if (toolsList != null) {
                for (Map<String, Object> toolData : toolsList) {
                    try {
                        MCPTool tool = objectMapper.convertValue(toolData, MCPTool.class);
                        availableTools.put(tool.getName(), tool);
                        log.debug("发现工具: {} - {}", tool.getName(), tool.getDescription());
                    } catch (Exception e) {
                        log.warn("解析工具失败: {}", toolData, e);
                    }
                }
            }
        }
    }

    public Object callTool(String toolName, Map<String, Object> arguments) throws IOException {
        if (!initialized) {
            throw new IllegalStateException("MCP客户端未初始化");
        }

        synchronized (ioLock) {
            MCPRequest request = new MCPRequest(
                    "tools/call",
                    Map.of("name", toolName, "arguments", arguments)
            );

            sendRequest(request);
            MCPResponse response = readResponse(30000);

            if (response != null) {
                if (response.getError() != null) {
                    throw new IOException("工具调用失败: " + response.getError().getMessage());
                }
                if (response.getResult() instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> result = (Map<String, Object>) response.getResult();
                    return result.get("content");
                }
            }
        }

        throw new IOException("工具调用无响应");
    }

    private void sendRequest(MCPRequest request) throws IOException {
        String json = objectMapper.writeValueAsString(request);
        writer.write(json);
        writer.newLine();
        writer.flush();
        log.debug("发送MCP请求[{}]: {}", serverName, json);
    }

    /**
     * 发送 JSON-RPC 通知（notification）。
     * 通知不带 id 字段，服务端不返回响应；因此不能复用 MCPRequest（其 id 字段会序列化为 null）。
     */
    private void sendNotification(String method, Object params) throws IOException {
        ObjectNode notification = objectMapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        if (params != null) {
            notification.set("params", objectMapper.valueToTree(params));
        }
        String json = objectMapper.writeValueAsString(notification);
        writer.write(json);
        writer.newLine();
        writer.flush();
        log.debug("发送MCP通知[{}]: {}", serverName, json);
    }

    private MCPResponse readResponse(long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        log.debug("⏳ 开始等待响应，超时时间: {}ms", timeoutMs);

        while (true) {
            // 检查进程状态
            if (process != null && !process.isAlive()) {
                int exitCode = process.exitValue();
                log.error("❌ MCP进程已退出，退出码: {}", exitCode);
                throw new IOException("MCP进程异常退出");
            }

            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                log.error("❌ 读取响应超时 (等待了 {}ms)", timeoutMs);
                throw new IOException("读取响应超时 (等待了 " + timeoutMs + "ms)");
            }

            String line;
            try {
                line = responseQueue.poll(remaining, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("❌ 读取响应被中断");
                throw new IOException("读取响应被中断");
            }

            if (line == null) {
                // poll 超时返回 null，回到循环顶部重新判断进程状态与剩余时间
                continue;
            }

            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }

            log.debug("📨 收到数据: {}", line);
            try {
                MCPResponse response = objectMapper.readValue(line, MCPResponse.class);
                log.debug("✅ 成功解析MCP响应");
                return response;
            } catch (Exception e) {
                log.warn("⚠️ 响应不是有效JSON，继续等待: {}", line);
            }
        }
    }

    /**
     * 发送 JSON-RPC ping 进行健康检查
     * @param timeoutMs 超时时间（毫秒）
     * @return true 表示服务端正常响应
     */
    public boolean ping(int timeoutMs) {
        if (!isConnected()) {
            return false;
        }
        synchronized (ioLock) {
            try {
                MCPRequest req = new MCPRequest("ping", null);
                sendRequest(req);
                MCPResponse resp = readResponse(timeoutMs);
                return resp != null && resp.getError() == null;
            } catch (Exception e) {
                log.debug("Ping 失败: {}", serverName, e.getMessage());
                return false;
            }
        }
    }

    public String getCommand() {
        return command;
    }

    public List<String> getArgs() {
        return args != null ? new ArrayList<>(args) : new ArrayList<>();
    }

    public void disconnect() {
        try {
            if (writer != null) {
                MCPRequest request = new MCPRequest("shutdown", null);
                sendRequest(request);
                writer.close();
            }
            if (reader != null) {
                reader.close();
            }
            // 中断并清理错误监控线程
            if (errorMonitorThread != null && errorMonitorThread.isAlive()) {
                errorMonitorThread.interrupt();
                errorMonitorThread = null;
            }
            if (process != null) {
                process.destroy();
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
            initialized = false;
            availableTools.clear();
            log.info("MCP客户端已断开: {}", serverName);
        } catch (Exception e) {
            log.error("断开MCP连接时出错: {}", serverName, e);
        }
    }

    public List<MCPTool> getAvailableTools() {
        return new ArrayList<>(availableTools.values());
    }

    public boolean isConnected() {
        return process != null && process.isAlive() && initialized;
    }

    public String getServerName() {
        return serverName;
    }
}