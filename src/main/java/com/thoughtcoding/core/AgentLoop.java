package com.thoughtcoding.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.model.ChatMessage;
import com.thoughtcoding.model.ToolExecution;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.service.PerformanceMonitor;
import com.thoughtcoding.service.ToolResultSummarizer;
import com.thoughtcoding.tools.BaseTool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

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
        // 设置「是否继续」回调（工具调用到上限时询问用户）
        context.getAiService().setContinueCallback(this::askContinue);
    }

    /** 工具调用到达上限时询问用户是否继续 */
    private boolean askContinue(int usedRounds) {
        context.getUi().displayWarning("⚠️ 已达到工具调用上限（" + usedRounds + " 轮），是否继续？(y/n)");
        try {
            String answer = context.getUi().getLineReader().readLine("> ");
            return answer != null && ("y".equalsIgnoreCase(answer.trim()) || "yes".equalsIgnoreCase(answer.trim()));
        } catch (Exception e) {
            return false;
        }
    }

    public void loadHistory(List<ChatMessage> previousHistory) {
        if (previousHistory != null) {
            history.addAll(previousHistory);
            context.getAiService().loadConversation(previousHistory);
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

            // 保存会话（用 LangChainService 的完整对话，含 assistant 回复与工具执行记录）
            context.getSessionService().saveSession(sessionId, context.getAiService().getConversation());

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

        // 有副作用的工具执行前确认（autoApprove 模式跳过）
        if (isDangerousTool(request) && !confirmation.isAutoApproveMode()) {
            if (!confirmToolExecution(request)) {
                return "用户取消了工具执行";
            }
        }

        String toolInput = convertArgumentsToInput(request);
        ToolResult result = tool.execute(toolInput);

        return ToolResultSummarizer.summarize(result, request.name());
    }

    /** 判断工具是否可能有副作用（只读检索工具无需确认） */
    private boolean isDangerousTool(ToolExecutionRequest request) {
        String name = request.name();
        if (name.equals("grep_search") || name.equals("codebase_search")) {
            return false;
        }
        if (name.equals("file_manager")) {
            // read/list/info 只读；write/delete/create 有副作用
            return isMutatingFileCommand(request.arguments());
        }
        return true;  // command_executor、code_executor、MCP 工具都确认
    }

    /** 判断 file_manager 的 command 是否是修改操作 */
    private boolean isMutatingFileCommand(String argumentsJson) {
        try {
            Map<String, Object> args = OBJECT_MAPPER.readValue(argumentsJson, Map.class);
            Object command = args.get("command");
            if (command == null) {
                return true;
            }
            String cmd = command.toString().toLowerCase();
            return cmd.equals("write") || cmd.equals("delete") || cmd.equals("create");
        } catch (Exception e) {
            return true;  // 解析失败，保守确认
        }
    }

    /** 弹出确认框，返回用户是否同意执行 */
    private boolean confirmToolExecution(ToolExecutionRequest request) {
        try {
            Map<String, Object> params = OBJECT_MAPPER.readValue(request.arguments(), Map.class);
            ToolExecution execution = new ToolExecution(
                request.name(),
                "执行 " + request.name() + " 工具",
                params,
                true
            );
            return confirmation.askConfirmation(execution);
        } catch (Exception e) {
            return true;  // 参数解析失败，交给工具自身返回参数错误
        }
    }

    /**
     * 将 function calling 的 JSON arguments 透传给工具。
     * 所有工具均原生支持 JSON 输入。
     */
    private String convertArgumentsToInput(ToolExecutionRequest request) {
        String argsJson = request.arguments();
        if (argsJson == null || argsJson.isBlank()) {
            return "";
        }
        return argsJson;
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
