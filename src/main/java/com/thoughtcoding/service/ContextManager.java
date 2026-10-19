package com.thoughtcoding.service;

import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.model.ChatMessage;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingResult;
import com.knuddels.jtokkit.api.EncodingType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文管理器
 * 负责管理对话历史的长度，防止 Token 超限
 *
 * 支持两种策略：
 * 1. 滑动窗口：保留最近 N 轮对话
 * 2. Token 控制：根据 Token 数量动态截断
 */
public class ContextManager {
    private static final Logger log = LoggerFactory.getLogger(ContextManager.class);

    private final AppConfig appConfig;

    // 默认配置
    private static final int DEFAULT_MAX_HISTORY_TURNS = 10;  // 保留10轮（20条消息）
    private static final int DEFAULT_MAX_CONTEXT_TOKENS = 3000;  // 为历史预留3000 tokens
    private static final int DEFAULT_RESERVE_TOKENS = 1000;  // 为响应预留1000 tokens

    // BPE 分词器（线程安全）。CL100K_BASE 是 OpenAI 系模型编码，对 DeepSeek/Qwen 为近似，但远优于字符比例启发式。
    private static final Encoding TOKEN_ENCODING = Encodings.newDefaultEncodingRegistry()
            .getEncoding(EncodingType.CL100K_BASE);

    // 策略枚举
    public enum Strategy {
        SLIDING_WINDOW,  // 滑动窗口
        TOKEN_BASED,     // 基于 Token
        HYBRID           // 混合策略
    }

    private Strategy strategy = Strategy.TOKEN_BASED;  // 默认使用 Token 控制
    private int maxHistoryTurns = DEFAULT_MAX_HISTORY_TURNS;
    private int maxContextTokens = DEFAULT_MAX_CONTEXT_TOKENS;

    public ContextManager(AppConfig appConfig) {
        this.appConfig = appConfig;
        loadConfiguration();
    }

    /**
     * 从配置加载参数
     */
    private void loadConfiguration() {
        // TODO: 从 config.yaml 读取配置
        // 目前使用默认值
        // 🔥 移除初始化日志，保持输出简洁
    }

    /**
     * 获取适合发送给 AI 的上下文
     * 应用历史长度限制策略
     *
     * @param fullHistory 完整的对话历史
     * @return 经过处理的历史（不超过限制）
     */
    public List<ChatMessage> getContextForAI(List<ChatMessage> fullHistory) {
        if (fullHistory == null || fullHistory.isEmpty()) {
            return new ArrayList<>();
        }

        List<ChatMessage> result;

        switch (strategy) {
            case SLIDING_WINDOW:
                result = applySlidingWindow(fullHistory);
                break;
            case TOKEN_BASED:
                result = applyTokenLimit(fullHistory);
                break;
            case HYBRID:
                result = applyHybridStrategy(fullHistory);
                break;
            default:
                result = fullHistory;
        }

        // 输出统计信息
        logContextStatistics(fullHistory, result);

        return result;
    }

    /**
     * 🔥 新增：构建固定的项目上下文消息
     * 这个上下文会在每次 AI 调用时注入，永远不会被截断
     *
     * @return 项目上下文系统消息，如果无法获取则返回 null
     */
    public ChatMessage buildProjectContextMessage() {
        try {
            String cwd = System.getProperty("user.dir");
            if (cwd == null || cwd.isEmpty()) {
                return null;
            }

            StringBuilder context = new StringBuilder();
            context.append("## 重要指令\n\n");
            context.append("- 你必须始终使用中文回答用户的所有问题。\n");
            context.append("- 所有的解释、说明、代码注释都必须使用中文。\n\n");

            context.append("## 你的角色\n\n");
            context.append("你是一位资深的编程助手。需要读取文件、执行命令、操作文件系统或检索代码时，直接调用对应的工具，不要用文字描述工具调用。\n\n");

            context.append("## 当前工作环境\n\n");
            context.append("工作目录: ").append(cwd).append("\n");
            context.append("路径支持相对路径、绝对路径、~ 主目录、../ 上级目录。\n\n");

            context.append("## 使用工具的规则\n\n");
            context.append("1. 需要读取文件内容时，调用文件工具，不要猜测文件内容。\n");
            context.append("2. 需要执行命令时，调用命令执行工具，不要假设命令结果。\n");
            context.append("3. 绝对禁止编造工具执行结果——你不知道结果，必须等工具返回真实结果后再回答。\n");
            context.append("4. 工具返回结果后，基于真实结果继续回答用户。\n");

            return new ChatMessage("system", context.toString());
        } catch (Exception e) {
            log.warn("无法构建项目上下文: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 策略1：滑动窗口
     * 保留最近 N 轮对话
     */
    private List<ChatMessage> applySlidingWindow(List<ChatMessage> fullHistory) {
        int maxMessages = maxHistoryTurns * 2;  // 每轮包含用户+AI消息

        if (fullHistory.size() <= maxMessages) {
            return new ArrayList<>(fullHistory);
        }

        // 保留最近 N 条消息
        int startIndex = fullHistory.size() - maxMessages;
        return new ArrayList<>(fullHistory.subList(startIndex, fullHistory.size()));
    }

    /**
     * 策略2：Token 控制
     * 根据 Token 数量动态截断
     */
    private List<ChatMessage> applyTokenLimit(List<ChatMessage> fullHistory) {
        List<ChatMessage> result = new ArrayList<>();
        int totalTokens = 0;

        // 从最新消息开始倒序添加
        for (int i = fullHistory.size() - 1; i >= 0; i--) {
            ChatMessage msg = fullHistory.get(i);
            int msgTokens = estimateTokens(msg.getContent());

            // 检查是否超过限制
            if (totalTokens + msgTokens > maxContextTokens) {
                // 如果这是第一条消息且超过限制，截断它
                if (result.isEmpty()) {
                    String truncatedContent = truncateToTokenLimit(msg.getContent(), maxContextTokens);
                    ChatMessage truncatedMsg = new ChatMessage(msg.getRole(), truncatedContent);
                    result.add(0, truncatedMsg);
                }
                break;
            }

            result.add(0, msg);  // 添加到开头
            totalTokens += msgTokens;
        }

        return result;
    }

    /**
     * 策略3：混合策略
     * 先应用滑动窗口，再应用 Token 控制
     */
    private List<ChatMessage> applyHybridStrategy(List<ChatMessage> fullHistory) {
        // 1. 先应用滑动窗口
        List<ChatMessage> windowedHistory = applySlidingWindow(fullHistory);

        // 2. 再应用 Token 控制
        return applyTokenLimit(windowedHistory);
    }

    /**
     * 使用 BPE 分词器精确估算 Token 数量（非字符比例）。
     *
     * @param text 待估算的文本
     * @return token 数量
     */
    static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return TOKEN_ENCODING.countTokens(text);
    }

    /**
     * 截断文本到指定 Token 限制（按真实 BPE token 精确截断，非字符近似）
     */
    private String truncateToTokenLimit(String text, int maxTokens) {
        if (estimateTokens(text) <= maxTokens) {
            return text;
        }

        // 编码时限制最大 token 数，再解码还原，保证截断后 token 数恰好不超限
        EncodingResult result = TOKEN_ENCODING.encode(text, maxTokens);
        String truncated = TOKEN_ENCODING.decode(result.getTokens());
        return truncated + "\n\n[内容过长已截断...]";
    }

    /**
     * 输出上下文统计信息
     */
    private void logContextStatistics(List<ChatMessage> fullHistory, List<ChatMessage> managedHistory) {
        int fullTokens = fullHistory.stream()
                .mapToInt(msg -> estimateTokens(msg.getContent()))
                .sum();

        int managedTokens = managedHistory.stream()
                .mapToInt(msg -> estimateTokens(msg.getContent()))
                .sum();

        if (fullHistory.size() != managedHistory.size()) {
            log.debug("📊 上下文管理统计:");
            log.debug("  完整历史: {} 条消息 (~{} tokens)", fullHistory.size(), fullTokens);
            log.debug("  发送历史: {} 条消息 (~{} tokens)", managedHistory.size(), managedTokens);
            log.debug("  节省: {} tokens ({}%)",
                    fullTokens - managedTokens,
                    (fullTokens - managedTokens) * 100 / Math.max(fullTokens, 1));
        }
    }

    /**
     * 获取当前策略
     */
    public Strategy getStrategy() {
        return strategy;
    }

    /**
     * 设置策略
     */
    public void setStrategy(Strategy strategy) {
        this.strategy = strategy;
        log.info("切换上下文策略为: {}", strategy);
    }

    /**
     * 设置最大历史轮数（用于滑动窗口策略）
     */
    public void setMaxHistoryTurns(int maxHistoryTurns) {
        this.maxHistoryTurns = maxHistoryTurns;
        log.info("设置最大历史轮数: {} 轮", maxHistoryTurns);
    }

    /**
     * 设置最大上下文 Token 数
     */
    public void setMaxContextTokens(int maxContextTokens) {
        this.maxContextTokens = maxContextTokens;
        log.info("设置最大上下文 Tokens: {}", maxContextTokens);
    }

    /**
     * 获取配置摘要
     */
    public String getConfigSummary() {
        return String.format("Strategy: %s, MaxTurns: %d, MaxTokens: %d",
                strategy, maxHistoryTurns, maxContextTokens);
    }

    public int getMaxContextTokens() {
        return maxContextTokens;
    }
}

