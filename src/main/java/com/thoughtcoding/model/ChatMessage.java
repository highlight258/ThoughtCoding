package com.thoughtcoding.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 *  聊天消息模型，包含消息ID、角色、内容和时间戳
 */
@Data
public class ChatMessage {
    private final String id;
    private final String role; // "user", "assistant", "system", "tool"
    private final String content;
    private final String timestamp;
    private final String sessionId;
    @JsonIgnore  // 忽略这些字段的序列化
    private boolean userMessage;

    @JsonIgnore
    private boolean systemMessage;

    @JsonIgnore
    private boolean assistantMessage;

    // tool 消息专用：工具调用 ID 与工具名（用于 function calling 结果回传关联）
    @JsonIgnore
    private final String toolCallId;
    @JsonIgnore
    private final String toolName;

    // 无参构造函数 - 需要提供默认值
    public ChatMessage() {
        this.role = "user";
        this.content = "";
        this.sessionId = null;
        this.id = UUID.randomUUID().toString();
        this.timestamp = Instant.now().toString();
        this.toolCallId = null;
        this.toolName = null;
    }


    public ChatMessage(String role, String content) {
        this.id = UUID.randomUUID().toString();
        this.role = role;
        this.content = content;
        this.timestamp = String.valueOf(LocalDateTime.now());
        this.sessionId = null;
        this.toolCallId = null;
        this.toolName = null;
    }

    public ChatMessage(String role, String content, String sessionId) {
        this.id = UUID.randomUUID().toString();
        this.role = role;
        this.content = content;
        this.timestamp = String.valueOf(LocalDateTime.now());
        this.sessionId = sessionId;
        this.toolCallId = null;
        this.toolName = null;
    }

    /** tool 消息构造函数：携带工具调用 ID 与工具名，供 function calling 结果回传关联 */
    public ChatMessage(String role, String content, String toolCallId, String toolName) {
        this.id = UUID.randomUUID().toString();
        this.role = role;
        this.content = content;
        this.timestamp = String.valueOf(LocalDateTime.now());
        this.sessionId = null;
        this.toolCallId = toolCallId;
        this.toolName = toolName;
    }

    // 添加静态工厂方法
    public static ChatMessage from(String content) {
        return new ChatMessage("assistant", content); // 默认角色为 "assistant"
    }

    /** 构造一条 tool 结果消息 */
    public static ChatMessage toolResult(String toolCallId, String toolName, String content) {
        return new ChatMessage("tool", content, toolCallId, toolName);
    }

    // Getters
    public String getId() { return id; }
    public String getRole() { return role; }
    public String getContent() { return content; }
    public String getTimestamp() { return timestamp; }
    public String getSessionId() { return sessionId; }
    public String getToolCallId() { return toolCallId; }
    public String getToolName() { return toolName; }

    @Override
    public String toString() {
        return String.format("[%s] %s: %s", timestamp, role, content);
    }

    public boolean isUserMessage() {
        return "user".equals(role);
    }

    public boolean isAssistantMessage() {
        return "assistant".equals(role);
    }

    public boolean isSystemMessage() {
        return "system".equals(role);
    }

    public boolean isToolMessage() {
        return "tool".equals(role);
    }

    /** 从已持久化的 session 恢复消息时使用，保留原始 ID 和时间戳 */
    public ChatMessage(String id, String role, String content, String timestamp, String sessionId) {
        this.id = id;
        this.role = role;
        this.content = content;
        this.timestamp = timestamp;
        this.sessionId = sessionId;
        this.toolCallId = null;
        this.toolName = null;
    }
}
