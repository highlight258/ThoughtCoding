package com.thoughtcoding.service;

import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.model.ChatMessage;
import com.thoughtcoding.model.ToolCall;
import com.thoughtcoding.tools.ToolRegistry;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 集成LangChain4j和DeepSeek API的AI服务实现
 */
public class LangChainService implements AIService {
    private final AppConfig appConfig;
    private final ContextManager contextManager;
    private Consumer<ChatMessage> messageHandler;
    private Consumer<ToolCall> toolCallHandler;
    private StreamingChatLanguageModel streamingChatModel;

    // 用于跟踪生成状态
    private volatile boolean isGenerating = false;
    private volatile boolean shouldStop = false;
    private volatile boolean hasTriggeredToolCall = false;

    // 预编译正则 —— 避免每次 AI 响应时重复编译
    private static final Pattern WRITE_FILE_NAME_PATTERN = Pattern.compile("write_file\\s+\"([^\"]+)\"");
    private static final Pattern WRITE_FILE_CONTENT_PATTERN = Pattern.compile(
        "write_file\\s+\"[^\"]+\"\\s+\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern JAVA_FILE_PATTERN = Pattern.compile("([\\w/]+\\.java)");
    private static final Pattern FILE_NAME_KEYWORD_PATTERN = Pattern.compile(
        "(?:文件名|filename|file name)\\s*[:：]?\\s*([\\w/]+\\.\\w+)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern ANY_CODE_FILE_PATTERN = Pattern.compile("([\\w/]+\\.(?:java|py|js|ts|cpp|c|h))");
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("```(?:java)?\\s*\\n([\\s\\S]*?)\\n```");
    private static final Pattern CLASS_BODY_PATTERN = Pattern.compile(
        "(?:public\\s+)?class\\s+\\w+\\s*\\{[\\s\\S]*?\\n\\}");
    private static final Pattern WRITE_FILE_CLEANUP_PATTERN = Pattern.compile("\\s*write_file\\s+\"[^\"]+\"[\\s\\S]*");
    private static final Pattern CODE_LANG_TOKEN_PATTERN = Pattern.compile(
        "^(java|python|javascript|cpp|c|python3|js|ts)\\s*$", Pattern.MULTILINE);
    private static final Pattern CODE_LANG_PREFIX_PATTERN = Pattern.compile(
        "^\\s*(java|python|javascript|cpp|c|python3|js|ts)\\s*\n");

    public LangChainService(AppConfig appConfig, ToolRegistry toolRegistry, ContextManager contextManager) {
        this.appConfig = appConfig;
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
    public List<ChatMessage> chat(String input, List<ChatMessage> history, String modelName) {
        return streamingChat(input, history, modelName);
    }

    @Override
    public List<ChatMessage> streamingChat(String input, List<ChatMessage> history, String modelName) {
        if (messageHandler == null) {
            throw new IllegalStateException("Message handler not set");
        }

        if (streamingChatModel == null) {
            throw new IllegalStateException("DeepSeek model not initialized. Please check your configuration.");
        }

        isGenerating = true;
        shouldStop = false;
        hasTriggeredToolCall = false;

        final StringBuilder fullResponse = new StringBuilder();
        final CompletableFuture<Void> completionFuture = new CompletableFuture<>();

        try {
            List<dev.langchain4j.data.message.ChatMessage> messages = prepareMessages(input, history);

            // 移除提示信息，保持输出简洁
            // System.out.println("🚀 Sending request to DeepSeek API...");

            streamingChatModel.generate(messages, new StreamingResponseHandler<AiMessage>() {
                private final StringBuilder codeBuffer = new StringBuilder();
                private boolean confirmationDisplayed = false;
                private boolean inCodeBlock = false;
                private String detectedFileName = null;
                private int codeBlockCount = 0;

                @Override
                public void onNext(String token) {
                    if (shouldStop || hasTriggeredToolCall) {
                        return;
                    }

                    fullResponse.append(token);
                    String currentText = fullResponse.toString();

                    // 🔥 持续检测文件名
                    if (detectedFileName == null) {
                        detectedFileName = extractFileNameFromText(currentText);
                    }

                    // 🔥 检测代码块开始（```java 或 ```python 等）
                    if (!inCodeBlock && token.contains("```")) {
                        inCodeBlock = true;
                        codeBlockCount++;

                        // 检测文件名但不显示任何提示
                        if (!confirmationDisplayed) {
                            if (detectedFileName == null) {
                                detectedFileName = extractFileNameFromText(currentText);
                            }
                            if (detectedFileName == null) {
                                detectedFileName = "NewFile.java";
                            }
                            confirmationDisplayed = true;
                        }

                        // ❌ 不输出代码块开始标记（```java）
                        return;
                    }

                    // 🔥 检测代码块结束（```）
                    if (inCodeBlock && token.contains("```")) {
                        inCodeBlock = false;
                        codeBlockCount++;

                        // ❌ 不输出代码块结束标记（```）

                        // 触发工具调用
                        if (confirmationDisplayed && codeBlockCount >= 2) {
                            String cleanCode = codeBuffer.toString();
                            // 移除语言标记（如 "java"、"python"）
                            cleanCode = CODE_LANG_PREFIX_PATTERN.matcher(cleanCode).replaceFirst("");

                            triggerToolCallWithCode(detectedFileName, cleanCode.trim());
                            hasTriggeredToolCall = true;
                        }

                        return;
                    }

                    // 🔥 在代码块内，输出纯代码内容（跳过语言标记）
                    if (inCodeBlock) {
                        // 跳过第一个 token 如果它是语言标记（java、python 等）
                        if (codeBuffer.length() == 0 && CODE_LANG_TOKEN_PATTERN.matcher(token.trim()).matches()) {
                            return; // 跳过语言标记，不输出
                        }

                        codeBuffer.append(token);
                        // ✅ 输出纯代码内容
                        messageHandler.accept(new ChatMessage("assistant", token));
                        return;
                    }

                    // 正常输出 AI 的描述文本
                    messageHandler.accept(new ChatMessage("assistant", token));
                }


                private void triggerToolCallWithCode(String fileName, String code) {
                    // 🔥 创建工具调用并触发
                    java.util.Map<String, Object> params = new java.util.HashMap<>();
                    params.put("path", fileName);
                    params.put("content", code.trim());

                    // 🔥 关键：使用 6 参数构造函数，最后一个参数标记为 true 表示流式触发
                    ToolCall toolCall = new ToolCall("write_file", params, null, false, 0, true);
                    //                                                            参数顺序：
                    //                                                            toolName, params, result, success, executionTime, streamingTriggered

                    // 立即触发工具调用处理器
                    if (toolCallHandler != null) {
                        toolCallHandler.accept(toolCall);
                    }
                }

                @Override
                public void onComplete(Response<dev.langchain4j.data.message.AiMessage> response) {
                    try {
                        detectAndTriggerToolCall(fullResponse.toString());

                        if (shouldStop && !fullResponse.isEmpty()) {
                            String cleanContent = removeToolCommandText(fullResponse.toString());
                            ChatMessage truncatedMessage = new ChatMessage("assistant",
                                cleanContent + "\n\n💡 [生成已被用户停止]");
                            history.add(truncatedMessage);
                            return;
                        }

                        if (!fullResponse.isEmpty()) {
                            // 🔥 保存到历史记录前，清理掉工具调用命令文本
                            String cleanContent = removeToolCommandText(fullResponse.toString());
                            if (!cleanContent.isEmpty()) {
                                ChatMessage completeMessage = new ChatMessage("assistant", cleanContent);
                                history.add(completeMessage);
                            }
                        }

                        System.out.println();
                    } finally {
                        isGenerating = false;
                        shouldStop = false;
                        completionFuture.complete(null); // 🔥 通知主线程：流式响应已完成
                    }
                }

                @Override
                public void onError(Throwable error) {
                    try {
                        System.err.println("❌ DeepSeek API error: " + error.getMessage());

                        ChatMessage errorMessage = new ChatMessage("assistant",
                                "抱歉，我在处理您的请求时遇到了问题： " + error.getMessage());
                        messageHandler.accept(errorMessage);
                        history.add(errorMessage);
                    } finally {
                        isGenerating = false;
                        shouldStop = false;
                        completionFuture.completeExceptionally(error); // 🔥 通知主线程：发生错误
                    }
                }
            });

            // 🔥 等待流式响应完成（最多等待 5 分钟）
            try {
                completionFuture.get(5, TimeUnit.MINUTES);
            } catch (java.util.concurrent.TimeoutException e) {
                System.err.println("⚠️  流式响应超时");
                completionFuture.cancel(true);
            } catch (Exception e) {
                System.err.println("⚠️  等待流式响应时发生错误: " + e.getMessage());
            }

        } catch (Exception e) {
            isGenerating = false;
            shouldStop = false;

            System.err.println("❌ Service error: " + e.getMessage());

            ChatMessage errorMessage = new ChatMessage("assistant",
                    "服务暂时不可用，请稍后重试。错误信息: " + e.getMessage());
            messageHandler.accept(errorMessage);
            history.add(errorMessage);
        }

        return history;
    }

    private List<dev.langchain4j.data.message.ChatMessage> prepareMessages(
            String input, List<ChatMessage> history) {
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();

        if (contextManager != null) {
            ChatMessage projectContext = contextManager.buildProjectContextMessage();
            if (projectContext != null) {
                messages.add(dev.langchain4j.data.message.SystemMessage.from(projectContext.getContent()));
            }
        }

        List<ChatMessage> managedHistory = history;
        if (contextManager != null && history != null && !history.isEmpty()) {
            managedHistory = contextManager.getContextForAI(history);
        }

        if (managedHistory != null && !managedHistory.isEmpty()) {
            messages.addAll(convertToLangChainHistory(managedHistory));
        }

        messages.add(dev.langchain4j.data.message.UserMessage.from(input));

        return messages;
    }

    private List<dev.langchain4j.data.message.ChatMessage> convertToLangChainHistory(
            List<ChatMessage> history) {
        return history.stream()
                .map(msg -> {
                    if ("user".equals(msg.getRole())) {
                        return dev.langchain4j.data.message.UserMessage.from(msg.getContent());
                    } else if ("assistant".equals(msg.getRole())) {
                        return dev.langchain4j.data.message.AiMessage.from(msg.getContent());
                    } else {
                        return dev.langchain4j.data.message.SystemMessage.from(msg.getContent());
                    }
                })
                .collect(Collectors.toList());
    }

    @Override
    public void setMessageHandler(Consumer<ChatMessage> handler) {
        this.messageHandler = handler;
    }

    @Override
    public void setToolCallHandler(Consumer<ToolCall> handler) {
        this.toolCallHandler = handler;
    }

    @Override
    public boolean validateModel(String modelName) {
        return appConfig.getModels().containsKey(modelName);
    }

    @Override
    public List<String> getAvailableModels() {
        return new ArrayList<>(appConfig.getModels().keySet());
    }

    private boolean detectAndTriggerToolCall(String aiResponse) {
        if (toolCallHandler == null || aiResponse == null || aiResponse.isEmpty()) {
            return false;
        }

        if (hasTriggeredToolCall) {
            return true;
        }

        String lowerResponse = aiResponse.toLowerCase();

        // 🔥 优先检测简化格式（⏺ Read/Write/Bash/List）- 这些格式必须严格执行
        // ⏺ Read(文件名)
        if (aiResponse.contains("⏺ Read(") || lowerResponse.matches(".*⏺\\s*read\\s*\\(.*")) {
            String filePath = extractFromSimplifiedFormat(aiResponse, "read");
            if (filePath != null) {
                triggerFileManagerRead(filePath);
                hasTriggeredToolCall = true;
                return true;
            }
        }

        // ⏺ List(目录)
        if (aiResponse.contains("⏺ List(") || lowerResponse.matches(".*⏺\\s*list\\s*\\(.*")) {
            String dirPath = extractFromSimplifiedFormat(aiResponse, "list");
            if (dirPath != null) {
                triggerFileManagerList(dirPath);
                hasTriggeredToolCall = true;
                return true;
            }
        }

        // ⏺ Bash(命令)
        if (aiResponse.contains("⏺ Bash(") || lowerResponse.matches(".*⏺\\s*bash\\s*\\(.*")) {
            String command = extractFromSimplifiedFormat(aiResponse, "bash");
            if (command != null) {
                triggerCommandExecutor(command);
                hasTriggeredToolCall = true;
                return true;
            }
        }

        // ⏺ Write(文件名) - 注意：这个只是标记，实际内容在代码块中
        if (aiResponse.contains("⏺ Write(") || lowerResponse.matches(".*⏺\\s*write\\s*\\(.*")) {
            String fileName = extractFromSimplifiedFormat(aiResponse, "write");
            String content = extractFileContent(aiResponse);
            if (fileName != null && content != null) {
                triggerWriteFile(fileName, content);
                hasTriggeredToolCall = true;
                return true;
            }
        }

        // 🔥 检测完整格式
        // 🔥 新增：检测 file_manager read 命令
        if (lowerResponse.contains("file_manager read")) {
            String filePath = extractQuotedPath(aiResponse, "file_manager read");
            if (filePath != null) {
                triggerFileManagerRead(filePath);
                hasTriggeredToolCall = true;
                return true;
            }
        }

        // 🔥 新增：检测 file_manager list 命令
        if (lowerResponse.contains("file_manager list")) {
            String dirPath = extractQuotedPath(aiResponse, "file_manager list");
            if (dirPath != null) {
                triggerFileManagerList(dirPath);
                hasTriggeredToolCall = true;
                return true;
            }
        }

        // 🔥 新增：检测 command_executor 命令
        if (lowerResponse.contains("command_executor")) {
            String command = extractQuotedPath(aiResponse, "command_executor");
            if (command != null) {
                triggerCommandExecutor(command);
                hasTriggeredToolCall = true;
                return true;
            }
        }

        // 🔥 提前检测：看到 write_f 就知道可能是 write_file，提前标记（但不触发）
        if (lowerResponse.contains("write_f") && !hasTriggeredToolCall) {
            // 继续积累，等待完整命令
        }

        if (lowerResponse.contains("write_file")) {
            String fileName = extractFileNameFromCommand(aiResponse);
            String content = extractContentFromCommand(aiResponse);

            if (fileName != null && content != null) {
                triggerWriteFile(fileName, content);
                hasTriggeredToolCall = true;
                return true;
            }
        }

        // 🔥 新增：检测代码块格式（优先，避免命令格式）
        if (lowerResponse.contains("```java") || lowerResponse.contains("```python") ||
            lowerResponse.contains("```javascript")) {

            // 检查是否包含文件名提示
            if (lowerResponse.contains("文件名") || lowerResponse.contains("filename") ||
                lowerResponse.matches(".*\\w+\\.\\w+.*")) {

                String fileName = extractFileNameFromText(aiResponse);
                String content = extractFileContent(aiResponse);

                if (fileName != null && content != null) {
                    triggerWriteFile(fileName, content);
                    hasTriggeredToolCall = true;
                    return true;
                }
            }
        }

        if ((lowerResponse.contains("创建") || lowerResponse.contains("create")) &&
            (lowerResponse.contains("文件") || lowerResponse.contains("file")) &&
            lowerResponse.contains(".java")) {

            String fileName = extractFileName(aiResponse);
            String content = extractFileContent(aiResponse);

            if (fileName != null && content != null) {
                triggerWriteFile(fileName, content);
                hasTriggeredToolCall = true;
                return true;
            }
        }

        if ((lowerResponse.contains("已创建") || lowerResponse.contains("创建成功") ||
             lowerResponse.contains("已成功创建")) &&
            lowerResponse.contains(".java")) {

            String fileName = extractFileName(aiResponse);
            String content = extractCodeFromText(aiResponse);

            if (fileName != null && content != null) {
                triggerWriteFile(fileName, content);
                hasTriggeredToolCall = true;
                return true;
            }
        }

        return false;
    }

    private void triggerWriteFile(String fileName, String content) {
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("path", fileName);
        params.put("content", content);

        // 不再显示中间状态信息，直接触发工具调用确认
        ToolCall toolCall = new ToolCall("write_file", params, null, false, 0);
        toolCallHandler.accept(toolCall);
    }

    private String extractFileNameFromCommand(String response) {
        java.util.regex.Matcher matcher = WRITE_FILE_NAME_PATTERN.matcher(response);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private String extractContentFromCommand(String response) {
        // 使用贪婪匹配，匹配到最后一个引号
        // 支持转义的引号 \"
        java.util.regex.Matcher matcher = WRITE_FILE_CONTENT_PATTERN.matcher(response);
        if (matcher.find()) {
            String content = matcher.group(1);
            // 处理转义字符
            return content.replace("\\n", "\n")
                         .replace("\\\"", "\"")
                         .replace("\\\\", "\\")
                         .replace("\\t", "    ");
        }
        return null;
    }

    private String extractFileName(String response) {
        java.util.regex.Matcher matcher = JAVA_FILE_PATTERN.matcher(response);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * 从文本中提取文件名（支持多种格式）
     * 例如："文件名：HelloWorld.java" 或 "创建 HelloWorld.java"
     */
    private String extractFileNameFromText(String response) {
        // 优先匹配 "文件名：XXX" 或 "filename: XXX" 格式
        java.util.regex.Matcher matcher1 = FILE_NAME_KEYWORD_PATTERN.matcher(response);
        if (matcher1.find()) {
            return matcher1.group(1);
        }

        // 其次匹配任何文件名格式
        java.util.regex.Matcher matcher2 = ANY_CODE_FILE_PATTERN.matcher(response);
        if (matcher2.find()) {
            return matcher2.group(1);
        }

        return null;
    }

    private String extractFileContent(String response) {
        java.util.regex.Matcher matcher = CODE_BLOCK_PATTERN.matcher(response);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    private String extractCodeFromText(String response) {
        String codeBlock = extractFileContent(response);
        if (codeBlock != null) {
            return codeBlock;
        }

        java.util.regex.Matcher matcher = CLASS_BODY_PATTERN.matcher(response);
        if (matcher.find()) {
            return matcher.group(0).trim();
        }

        return null;
    }

    /**
     * 🔥 从简化格式中提取参数
     * 例如：⏺ Read(HelloWorld.java) -> "HelloWorld.java"
     *       ⏺ Bash(ls -la) -> "ls -la"
     */
    private String extractFromSimplifiedFormat(String response, String command) {
        // 匹配格式：⏺ Command(参数)
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
            "⏺\\s*" + command + "\\s*\\(([^)]+)\\)",
            java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher matcher = pattern.matcher(response);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    /**
     * 🔥 从完整格式中提取带引号的路径
     * 例如：file_manager read "test.java" -> "test.java"
     */
    private String extractQuotedPath(String response, String command) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
            command + "\\s+\"([^\"]+)\"",
            java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher matcher = pattern.matcher(response);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * 🔥 触发文件读取工具调用
     */
    private void triggerFileManagerRead(String filePath) {
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("command", "read");
        params.put("path", filePath);

        ToolCall toolCall = new ToolCall("file_manager", params, null, false, 0);
        toolCallHandler.accept(toolCall);
    }

    /**
     * 🔥 触发目录列出工具调用
     */
    private void triggerFileManagerList(String dirPath) {
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("command", "list");
        params.put("path", dirPath);

        ToolCall toolCall = new ToolCall("file_manager", params, null, false, 0);
        toolCallHandler.accept(toolCall);
    }

    /**
     * 🔥 触发命令执行工具调用
     */
    private void triggerCommandExecutor(String command) {
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("command", command);

        ToolCall toolCall = new ToolCall("command_executor", params, null, false, 0);
        toolCallHandler.accept(toolCall);
    }

    /**
     * 移除文本中的工具调用命令部分
     * 例如: "好的，我来帮你创建。\n\nwrite_file \"test.java\" \"...\"\n"
     * 返回: "好的，我来帮你创建。"
     */
    private String removeToolCommandText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        // 移除 write_file 命令及其后续内容
        String result = WRITE_FILE_CLEANUP_PATTERN.matcher(text).replaceFirst("");

        // 移除尾部的多余空白
        return result.trim();
    }

    public boolean isGenerating() {
        return isGenerating;
    }

    public void stopCurrentGeneration() {
        if (isGenerating) {
            shouldStop = true;
            System.out.println("⏸️  正在停止生成...");
        }
    }
}

