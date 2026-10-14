package com.thoughtcoding.service;

import com.thoughtcoding.util.FileUtils;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 工具输出源头压缩器
 * 在工具结果写入对话历史之前做压缩，避免无用信息占用上下文窗口。
 *
 * 策略（无损可恢复）：
 * 1. 超长输出先落盘保留完整原文（不永久丢信息）
 * 2. 结构化提取高信号行（按日志严重级别 / 异常堆栈 / 退出码等形态，替代裸关键词白名单）
 * 3. 头尾保留 + 恢复路径标记，模型需要时可回读完整结果
 */
public class ToolResultSummarizer {

    private static final int MAX_OUTPUT_LINES = 50;
    private static final int HEAD_LINES = 10;
    private static final int TAIL_LINES = 10;
    private static final int MAX_LINE_LENGTH = 500;

    // 落盘目录（默认 sessions/tool-results，测试可覆盖）
    private static volatile String storageDir = "sessions/tool-results";

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    // ── 结构化高信号模式（方案B）──
    // 严重级别：大写 ERROR/FATAL/SEVERE/CRITICAL，避免匹配小写 prose 里的 "error"
    private static final Pattern SEVERITY = Pattern.compile("\\b(ERROR|FATAL|SEVERE|CRITICAL)\\b");
    // 异常类名：XxxException / XxxError
    private static final Pattern EXCEPTION = Pattern.compile("\\b([\\w.]*(Exception|Error))\\b");
    // 堆栈行 / 追溯
    private static final Pattern STACK_TRACE = Pattern.compile(
            "(?i)(^\\s*at\\s+[\\w.$<>]+\\([^)]*\\)|^Caused by:|^\\s*Traceback|^\\s*File\\s+\".+\",\\s+line\\s+\\d+)");
    // 退出码 / 构建失败
    private static final Pattern EXIT_FAIL = Pattern.compile(
            "(?i)(exit\\s+code\\s*[1-9]|exit\\s+status\\s*[1-9]|build\\s+failed)");

    /**
     * 对工具输出做无损压缩。短输出原样返回；超长输出落盘全文，返回头尾预览 + 高信号行 + 恢复路径。
     *
     * @param rawOutput 工具原始输出
     * @param toolName  工具名（用于落盘文件命名）
     * @return 压缩后文本
     */
    public static String summarize(String rawOutput, String toolName) {
        if (rawOutput == null || rawOutput.isEmpty()) {
            return rawOutput;
        }

        String[] lines = rawOutput.split("\n");
        if (lines.length <= MAX_OUTPUT_LINES) {
            return rawOutput;
        }

        // 1. 落盘完整原文（方案A）
        String artifactPath = offload(rawOutput, toolName);

        // 2. 结构化提取高信号行（方案B）
        List<String> signalLines = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String line : lines) {
            if (isHighSignal(line) && seen.add(line)) {
                signalLines.add(truncateLine(line));
            }
        }

        // 3. 头尾保留
        List<String> head = new ArrayList<>();
        for (int i = 0; i < Math.min(HEAD_LINES, lines.length); i++) {
            head.add(truncateLine(lines[i]));
        }
        List<String> tail = new ArrayList<>();
        int tailStart = Math.max(HEAD_LINES, lines.length - TAIL_LINES);
        for (int i = tailStart; i < lines.length; i++) {
            tail.add(truncateLine(lines[i]));
        }

        // 4. 组装
        StringBuilder sb = new StringBuilder();
        for (String h : head) {
            sb.append(h).append('\n');
        }
        if (!signalLines.isEmpty()) {
            sb.append("... [关键信息 ").append(signalLines.size()).append(" 条] ...\n");
            for (String s : signalLines) {
                sb.append(s).append('\n');
            }
        }
        int omitted = Math.max(0, lines.length - HEAD_LINES - TAIL_LINES);
        sb.append("... [省略 ").append(omitted).append(" 行] ...\n");
        for (String t : tail) {
            sb.append(t).append('\n');
        }
        sb.append("[完整输出 ").append(lines.length).append(" 行已保存至 ")
          .append(artifactPath).append("，需要时用 ⏺ Read(").append(artifactPath).append(") 查看]");
        return sb.toString();
    }

    /** 判断单行是否为高信号（结构化模式） */
    private static boolean isHighSignal(String line) {
        return SEVERITY.matcher(line).find()
                || EXCEPTION.matcher(line).find()
                || STACK_TRACE.matcher(line).find()
                || EXIT_FAIL.matcher(line).find();
    }

    /** 落盘完整原文，返回文件路径；失败时降级返回占位说明 */
    private static String offload(String rawOutput, String toolName) {
        String safeTool = toolName == null ? "tool" : toolName.replaceAll("[^\\w.-]", "_");
        String name = safeTool + "_" + LocalDateTime.now().format(TIMESTAMP) + ".txt";
        Path path = Paths.get(storageDir, name);
        try {
            FileUtils.writeFile(path, rawOutput);
            return path.toString();
        } catch (Exception e) {
            return "<落盘失败: " + e.getMessage() + ">";
        }
    }

    private static String truncateLine(String line) {
        if (line.length() <= MAX_LINE_LENGTH) {
            return line;
        }
        return line.substring(0, MAX_LINE_LENGTH) + "...[截断]";
    }

    /** 测试用：覆盖落盘目录 */
    static void setStorageDir(String dir) {
        storageDir = dir;
    }
}
