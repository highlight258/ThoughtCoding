package com.thoughtcoding.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

/**
 * MCP 服务端连接状态，用于状态机 + 指数退避重连追踪。
 */
public class ServerConnectionState {

    /** 连接状态机：健康 → 重连中 → 放弃 */
    public enum State {
        HEALTHY,       // 已连接，接受健康检查
        RECONNECTING,  // 断开中，退避重连循环自驱动
        ABANDONED      // 连续失败达上限，放弃自动重连，需手动 /mcp 重连
    }

    private final String serverName;
    private volatile String command;
    private volatile List<String> args;
    private volatile State state;
    private volatile long generation;
    private volatile int consecutiveFailures;
    private volatile int backoffSeconds;
    private volatile ScheduledFuture<?> pendingReconnect;

    public ServerConnectionState(String serverName, String command, List<String> args) {
        this.serverName = serverName;
        this.command = command;
        this.args = args != null ? new ArrayList<>(args) : new ArrayList<>();
        this.state = State.HEALTHY;
        this.generation = 0;
        this.consecutiveFailures = 0;
        this.backoffSeconds = 0;
        this.pendingReconnect = null;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public long getGeneration() {
        return generation;
    }

    /** 递增 generation，使已排队的过期重连任务失效 */
    public long incrementGeneration() {
        return ++generation;
    }

    public ScheduledFuture<?> getPendingReconnect() {
        return pendingReconnect;
    }

    public void setPendingReconnect(ScheduledFuture<?> pendingReconnect) {
        this.pendingReconnect = pendingReconnect;
    }

    /**
     * 指数退避：1 → 2 → 4 → 8 → 16 → 32 → 60 → 60 ...
     * @return 当前退避延迟（秒）
     */
    public int getAndIncrementBackoff() {
        consecutiveFailures++;
        if (backoffSeconds == 0) {
            backoffSeconds = 1;
        } else {
            backoffSeconds = Math.min(backoffSeconds * 2, 60);
        }
        return backoffSeconds;
    }

    /** 健康恢复或重连成功时重置退避状态 */
    public void resetBackoff() {
        consecutiveFailures = 0;
        backoffSeconds = 0;
    }

    public String getServerName() {
        return serverName;
    }

    public String getCommand() {
        return command;
    }

    public List<String> getArgs() {
        return args;
    }

    public void updateConnectionInfo(String command, List<String> args) {
        this.command = command;
        this.args = args != null ? new ArrayList<>(args) : new ArrayList<>();
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public int getBackoffSeconds() {
        return backoffSeconds;
    }
}
