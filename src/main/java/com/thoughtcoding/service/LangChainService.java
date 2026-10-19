package com.thoughtcoding.service;

import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.model.ChatMessage;
import com.thoughtcoding.tools.ToolRegistry;
import com.thoughtcoding.tools.ToolSpecificationBuilder;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 基于 LangChain4j 原生 Function Calling 的 AI 服务实现。
 *
 * 内部维护完整的对话消息（含工具调用与结果回传），并自动完成
 * 「模型请求工具 → 执行 → 结果回传 → 继续」的循环，直到模型不再请求工具。
 */
public class LangChainService implements AIService {
    private static final int MAX_TOOL_ITERATIONS = 10;

    private final AppConfig appConfig;
    private final ToolRegistry toolRegistry;
    private final ContextManager contextManager;
    private final List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();

    private Consumer<ChatMessage> messageHandler;
    private StreamingChatLanguageModel streamingChatModel;

    private volatile boolean isGenerating = false;
    private volatile boolean shouldStop = false;

    public LangChainService(AppConfig appConfig, ToolRegistry toolRegistry, ContextManager contextManager) {
        this.appConfig = appConfig;
        this.toolRegistry = toolRegistry;
        this.contextManager = contextManager;
        initializeChatModel();
    }

    private void initializeChatModel() {
        try {
            AppConfig.ModelConfig modelConfig = appConfig.getModelConfig(appConfig.getDefaultModel());
            if (modelConfig != null) {
                this.streamingChatModel = createDeepSeekModel(modelConfig);
            }
        } catch (Exception e) {
            System.err.println("初始化模型失败: " + e.getMessage());
        }
    }

    private StreamingChatLanguageModel createDeepSeekModel(AppConfig.ModelConfig config) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(config.getBaseURL())
                .apiKey(config.getApiKey())
                .modelName(config.getName())
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens())
                .logRequests(true)
                .logResponses(true)
                .build();
    }

    @Override
    public void chat(String input, String modelName, ToolExecutor toolExecutor) {
        if (streamingChatModel == null) {
            throw new IllegalStateException("DeepSeek model not initialized. Please check your configuration.");
        }

        isGenerating = true;
        shouldStop = false;
        try {
            messages.add(UserMessage.from(input));

            for (int i = 0; i < MAX_TOOL_ITERATIONS && !shouldStop; i++) {
                List<dev.langchain4j.data.message.ChatMessage> request = buildRequestMessages();
                List<ToolSpecification> toolSpecs = ToolSpecificationBuilder.build(toolRegistry);

                AiMessage aiMessage = generateSync(request, toolSpecs);

                if (!aiMessage.hasToolExecutionRequests()) {
                    messages.add(aiMessage);
                    return;
                }

                // 模型请求工具：逐个执行，结果回传
                for (ToolExecutionRequest request2 : aiMessage.toolExecutionRequests()) {
                    String result = toolExecutor.execute(request2);
                    messages.add(AiMessage.from(request2));
                    messages.add(ToolExecutionResultMessage.from(request2, result));
                }
            }

            // 达到轮次上限（非用户主动停止）：强制模型做一次无工具的总结收尾
            if (!shouldStop) {
                messages.add(SystemMessage.from(
                        "你已达到工具调用轮数上限，请基于已有结果总结：已完成什么、卡在哪里、下一步建议什么。"));
                AiMessage summary = generateSync(buildRequestMessages(), List.of());
                messages.add(summary);
            }
        } finally {
            isGenerating = false;
        }
    }

    public boolean isGenerating() {
        return isGenerating;
    }

    public void stopCurrentGeneration() {
        this.shouldStop = true;
    }

    /** 构建请求消息：固定系统指令 + 按 token 预算裁剪后的对话历史 */
    private List<dev.langchain4j.data.message.ChatMessage> buildRequestMessages() {
        List<dev.langchain4j.data.message.ChatMessage> request = new ArrayList<>();

        if (contextManager != null) {
            ChatMessage projectContext = contextManager.buildProjectContextMessage();
            if (projectContext != null) {
                request.add(SystemMessage.from(projectContext.getContent()));
            }
        }

        request.addAll(trimHistory(messages));
        return request;
    }

    /** 按 token 预算倒序裁剪历史（保留最近相关消息，系统提示词不参与裁剪） */
    private List<dev.langchain4j.data.message.ChatMessage> trimHistory(
            List<dev.langchain4j.data.message.ChatMessage> history) {
        int maxTokens = contextManager != null ? contextManager.getMaxContextTokens() : 3000;
        List<dev.langchain4j.data.message.ChatMessage> result = new ArrayList<>();
        int totalTokens = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            dev.langchain4j.data.message.ChatMessage msg = history.get(i);
            int tokens = ContextManager.estimateTokens(msg.text());
            if (totalTokens + tokens > maxTokens && !result.isEmpty()) {
                break;
            }
            result.add(0, msg);
            totalTokens += tokens;

            // 工具结果消息：把紧邻的 assistant tool_calls 消息一起保留，避免拆散调用对
            if (msg instanceof ToolExecutionResultMessage && i - 1 >= 0) {
                dev.langchain4j.data.message.ChatMessage prev = history.get(i - 1);
                if (prev instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                    i--;
                    result.add(0, prev);
                    totalTokens += ContextManager.estimateTokens(prev.text());
                }
            }
        }
        return result;
    }

    /** 阻塞执行一次流式 generate，返回完整 AiMessage（含可能的工具调用请求） */
    private AiMessage generateSync(List<dev.langchain4j.data.message.ChatMessage> request,
                                   List<ToolSpecification> toolSpecs) {
        CompletableFuture<AiMessage> future = new CompletableFuture<>();

        streamingChatModel.generate(request, toolSpecs, new StreamingResponseHandler<AiMessage>() {
            @Override
            public void onNext(String token) {
                if (shouldStop) {
                    return;
                }
                if (messageHandler != null) {
                    messageHandler.accept(new ChatMessage("assistant", token));
                }
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                future.complete(response.content());
            }

            @Override
            public void onError(Throwable error) {
                future.completeExceptionally(error);
            }
        });

        try {
            return future.get(5, TimeUnit.MINUTES);
        } catch (Exception e) {
            throw new RuntimeException("等待模型响应失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void setMessageHandler(Consumer<ChatMessage> handler) {
        this.messageHandler = handler;
    }

    @Override
    public boolean validateModel(String modelName) {
        return appConfig.getModels().containsKey(modelName);
    }

    @Override
    public List<String> getAvailableModels() {
        return new ArrayList<>(appConfig.getModels().keySet());
    }

    @Override
    public List<ChatMessage> getConversation() {
        List<ChatMessage> result = new ArrayList<>();
        for (dev.langchain4j.data.message.ChatMessage msg : messages) {
            if (msg instanceof UserMessage) {
                result.add(new ChatMessage("user", msg.text()));
            } else if (msg instanceof AiMessage ai) {
                if (ai.hasToolExecutionRequests()) {
                    String names = ai.toolExecutionRequests().stream()
                            .map(ToolExecutionRequest::name)
                            .collect(Collectors.joining(", "));
                    result.add(new ChatMessage("assistant", "[调用工具] " + names));
                } else {
                    result.add(new ChatMessage("assistant", ai.text()));
                }
            } else if (msg instanceof ToolExecutionResultMessage trm) {
                result.add(ChatMessage.toolResult(trm.id(), trm.toolName(), trm.text()));
            }
            // SystemMessage 每轮动态构建，不持久化
        }
        return result;
    }

    @Override
    public void loadConversation(List<ChatMessage> history) {
        messages.clear();
        if (history == null) {
            return;
        }
        for (ChatMessage msg : history) {
            switch (msg.getRole()) {
                case "user" -> messages.add(UserMessage.from(msg.getContent()));
                case "assistant" -> messages.add(AiMessage.from(msg.getContent()));
                case "tool" -> messages.add(ToolExecutionResultMessage.from(
                        msg.getToolCallId() != null ? msg.getToolCallId() : UUID.randomUUID().toString(),
                        msg.getToolName() != null ? msg.getToolName() : "tool",
                        msg.getContent()));
                default -> { /* system 等历史消息忽略 */ }
            }
        }
    }
}
