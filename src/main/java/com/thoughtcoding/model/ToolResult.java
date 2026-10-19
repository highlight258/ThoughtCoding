package com.thoughtcoding.model;

/**
 * 封装工具执行结果（结构化：含退出码与标准错误分离）
 */
public class ToolResult {
    private final boolean success;
    private final String output;
    private final String error;
    private final int exitCode;
    private final String stderr;
    private final long executionTime;

    public ToolResult(boolean success, String output, String error, long executionTime) {
        this(success, output, error, success ? 0 : 1, null, executionTime);
    }

    public ToolResult(boolean success, String output, String error, int exitCode, String stderr, long executionTime) {
        this.success = success;
        this.output = output;
        this.error = error;
        this.exitCode = exitCode;
        this.stderr = stderr;
        this.executionTime = executionTime;
    }

    public static ToolResult success(String output, long executionTime) {
        return new ToolResult(true, output, null, 0, null, executionTime);
    }

    public static ToolResult error(String error, long executionTime) {
        return new ToolResult(false, null, error, 1, null, executionTime);
    }

    /** 命令执行结果：根据退出码判断成败，分离 stdout/stderr */
    public static ToolResult command(String stdout, String stderr, int exitCode, long executionTime) {
        if (exitCode == 0) {
            return new ToolResult(true, stdout, null, 0, stderr, executionTime);
        }
        String errMsg = "Command failed with exit code " + exitCode;
        if (stderr != null && !stderr.isEmpty()) {
            errMsg += ":\n" + stderr;
        } else if (stdout != null && !stdout.isEmpty()) {
            errMsg += ":\n" + stdout;
        }
        return new ToolResult(false, stdout, errMsg, exitCode, stderr, executionTime);
    }

    // Getters
    public boolean isSuccess() { return success; }
    public String getOutput() { return output; }
    public String getError() { return error; }
    public int getExitCode() { return exitCode; }
    public String getStderr() { return stderr; }
    public long getExecutionTime() { return executionTime; }

    @Override
    public String toString() {
        if (success) {
            return String.format("Success: %s (%dms)", output, executionTime);
        } else {
            return String.format("Error: %s (%dms)", error, executionTime);
        }
    }
}
