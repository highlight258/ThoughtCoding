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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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

    /** 构建请求消息：固定系统指令 + 对话历史 */
    private List<dev.langchain4j.data.message.ChatMessage> buildRequestMessages() {
        List<dev.langchain4j.data.message.ChatMessage> request = new ArrayList<>();

        if (contextManager != null) {
            ChatMessage projectContext = contextManager.buildProjectContextMessage();
            if (projectContext != null) {
                request.add(SystemMessage.from(projectContext.getContent()));
            }
        }

        request.addAll(messages);
        return request;
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
}
