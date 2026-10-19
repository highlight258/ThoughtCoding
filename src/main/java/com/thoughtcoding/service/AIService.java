package com.thoughtcoding.service;

import com.thoughtcoding.model.ChatMessage;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.util.List;
import java.util.function.Consumer;

/**
 * AI 服务接口，定义了与 AI 模型交互的方法。
 *
 * function calling 由实现类内部完成循环：模型请求工具 → 通过 ToolExecutor 执行 → 结果回传 → 继续，直到模型不再请求工具。
 */
public interface AIService {

    /**
     * 发起一次对话，内部完成 function calling 循环。
     *
     * @param input        用户输入
     * @param modelName    模型名
     * @param toolExecutor 工具执行器（由调用方提供，负责实际执行工具并返回结果字符串）
     */
    void chat(String input, String modelName, ToolExecutor toolExecutor);

    /** 流式文本回调（逐 token 推送 assistant 文本） */
    void setMessageHandler(Consumer<ChatMessage> handler);

    boolean validateModel(String modelName);

    List<String> getAvailableModels();

    /** 工具执行器：执行一个工具调用请求，返回结果字符串 */
    @FunctionalInterface
    interface ToolExecutor {
        String execute(ToolExecutionRequest request);
    }
}
