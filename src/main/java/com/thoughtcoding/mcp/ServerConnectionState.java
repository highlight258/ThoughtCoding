package com.thoughtcoding.mcp;

/**
 * MCP 服务端连接状态，用于指数退避重连追踪。
 * 记录服务端配置、失败计数和退避延迟。
 */
public class ServerConnectionState {
    private final String serverName;
    private volatile String command;
    private volatile java.util.List<String> args;
    private int consecutiveFailures;
    private int backoffSeconds;

    public ServerConnectionState(String serverName, String command, java.util.List<String> args) {
        this.serverName = serverName;
        this.command = command;
        this.args = args != null ? new java.util.ArrayList<>(args) : new java.util.ArrayList<>();
        this.consecutiveFailures = 0;
        this.backoffSeconds = 0;
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

    /**
     * 健康恢复时重置退避状态
     */
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

    public java.util.List<String> getArgs() {
        return args;
    }

    /**
     * 更新命令和参数（重连时可能变更）
     */
    public void updateConnectionInfo(String command, java.util.List<String> args) {
        this.command = command;
        this.args = args != null ? new java.util.ArrayList<>(args) : new java.util.ArrayList<>();
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    /** 仅递增失败计数，不退避。用于标记放弃状态避免重复日志。 */
    public void incrementFailures() {
        this.consecutiveFailures++;
    }

    public int getBackoffSeconds() {
        return backoffSeconds;
    }
}
