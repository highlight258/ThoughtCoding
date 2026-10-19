package com.thoughtcoding.core;

import com.thoughtcoding.model.ChatMessage;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.mcp.MCPToolAdapter;
import com.thoughtcoding.service.PerformanceMonitor;
import com.thoughtcoding.service.ToolResultSummarizer;
import com.thoughtcoding.tools.BaseTool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 管理 AI 交互的核心循环。
 *
 * 用户输入 → 发起对话（function calling 循环在 LangChainService 内部完成）
 * → 工具执行（本类提供回调）→ 会话持久化。
 */
public class AgentLoop {
    private final ThoughtCodingContext context;
    private final List<ChatMessage> history;
    private final String sessionId;
    private final String modelName;
    private final ToolExecutionConfirmation confirmation;
    private final OptionManager optionManager;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public AgentLoop(ThoughtCodingContext context, String sessionId, String modelName) {
        this.context = context;
        this.sessionId = sessionId;
        this.modelName = modelName;
        this.history = new ArrayList<>();

        this.confirmation = new ToolExecutionConfirmation(
            context.getUi(),
            context.getUi().getLineReader()
        );

        this.optionManager = new OptionManager();

        // 设置消息处理器（流式文本回调）
        context.getAiService().setMessageHandler(this::handleMessage);
    }

    public void loadHistory(List<ChatMessage> previousHistory) {
        if (previousHistory != null) {
            history.addAll(previousHistory);
        }
    }

    public void processInput(String input) {
        PerformanceMonitor monitor = context.getPerformanceMonitor();
        monitor.start();

        try {
            if (optionManager.isOptionInput(input)) {
                handleOptionSelection(input);
                return;
            }

            // 添加用户消息到历史（用于持久化）
            ChatMessage userMessage = new ChatMessage("user", input);
            history.add(userMessage);

            // 发起对话，function calling 循环由 LangChainService 内部完成
            context.getAiService().chat(input, modelName, this::executeTool);

            // 保存会话
            context.getSessionService().saveSession(sessionId, history);

        } catch (Exception e) {
            context.getUi().displayError("Error processing input: " + e.getMessage());
        } finally {
            monitor.stop();
        }
    }

    /** 处理用户的选项选择 */
    private void handleOptionSelection(String input) {
        String command = optionManager.processOptionSelection(input);

        if (command == null) {
            context.getUi().displayError("❌ 无效的选项，请输入 1-" + optionManager.getCurrentOptions().size());
            return;
        }

        context.getUi().displayInfo("\n✅ 你选择了：" + command);
        context.getUi().displayInfo("正在执行...\n");

        processInput(command);
    }

    private void handleMessage(ChatMessage message) {
        // 显示 AI 消息（用于流式输出的实时显示）
        context.getUi().displayAIMessage(message);

        // 尝试从 AI 响应中提取选项
        if (message.isAssistantMessage()) {
            boolean hasOptions = optionManager.extractOptionsFromResponse(message.getContent());

            if (hasOptions) {
                context.getUi().displayInfo("\n💡 请输入选项编号（1-" +
                    optionManager.getCurrentOptions().size() + "）来选择你想要的操作");
            }
        }
    }

    /**
     * 执行一个工具调用请求（供 LangChainService 的 function calling 循环回调）。
     * 返回工具执行结果字符串，供回传给模型。
     */
    private String executeTool(ToolExecutionRequest request) {
        BaseTool tool = context.getToolRegistry().getTool(request.name());
        if (tool == null) {
            return "工具不存在: " + request.name();
        }

        String toolInput = convertArgumentsToInput(request);
        ToolResult result = tool.execute(toolInput);

        return ToolResultSummarizer.summarize(result, request.name());
    }

    /**
     * 将 function calling 的 JSON arguments 转换为工具可接受的输入字符串。
     * 支持 JSON 的工具直接透传；仅支持空格分隔的工具按类型组装。
     */
    private String convertArgumentsToInput(ToolExecutionRequest request) {
        String argsJson = request.arguments();
        if (argsJson == null || argsJson.isBlank()) {
            return "";
        }

        // 支持 JSON 输入的工具直接透传 JSON
        if (request.name().equals("file_manager")
                || request.name().equals("command_executor")
                || context.getToolRegistry().getTool(request.name()) instanceof MCPToolAdapter) {
            return argsJson;
        }

        // 其余工具：解析 JSON 组装成空格分隔字符串
        try {
            Map<String, Object> args = OBJECT_MAPPER.readValue(argsJson, Map.class);
            return switch (request.name()) {
                case "code_executor" -> args.get("language") + " " + args.get("code");
                case "grep_search" -> args.get("pattern") + " " + args.get("path");
                case "codebase_search" -> String.valueOf(args.get("query"));
                default -> argsJson;
            };
        } catch (Exception e) {
            return argsJson;
        }
    }

    /** 设置自动批准模式（用于批量操作） */
    public void setAutoApprove(boolean enabled) {
        confirmation.setAutoApproveMode(enabled);
    }

    /** 检查是否处于自动批准模式 */
    public boolean isAutoApproveMode() {
        return confirmation.isAutoApproveMode();
    }

    public List<ChatMessage> getHistory() {
        return new ArrayList<>(history);
    }

    public String getSessionId() {
        return sessionId;
    }
}
