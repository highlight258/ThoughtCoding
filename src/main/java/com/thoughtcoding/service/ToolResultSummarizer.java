package com.thoughtcoding.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 工具输出源头压缩器
 * 在工具结果写入对话历史之前做压缩，避免无用信息占用上下文窗口。
 *
 * 策略：
 * 1. 提取高信号行（异常/错误/关键信息）优先保留
 * 2. 保留头尾各 N 行（头尾信息密度通常最高）
 * 3. 合并连续重复行
 * 4. 截断超长单行
 */
public class ToolResultSummarizer {

    private static final int MAX_OUTPUT_LINES = 50;
    private static final int HEAD_LINES = 10;
    private static final int TAIL_LINES = 10;
    private static final int MAX_LINE_LENGTH = 500;

    /**
     * 高信号关键词 —— 包含这些词的行优先保留
     */
    private static final Set<String> SIGNAL_KEYWORDS = Set.of(
        "exception", "error", "fatal", "severe", "failed", "caused by",
        "trace", "warning", "warn", "cannot", "unable", "refused",
        "timeout", "denied", "not found", "already exists", "conflict",
        "at ", "null", "stack", "killed", "out of", "overflow"
    );

    /**
     * 低信号关键词 —— 包含这些词的纯信息行可以丢弃
     * （仅在超出 MAX_OUTPUT_LINES 时生效）
     */
    private static final Set<String> NOISE_KEYWORDS = Set.of(
        "downloading", "downloaded", "installing", "collecting",
        "resolving", "compiling", "[info]", "[debug]", "[trace]"
    );

    /**
     * 对工具输出做摘要压缩，不改变短输出。
     *
     * @param rawOutput 工具原始输出
     * @return 压缩后文本，原始足够短则原样返回
     */
    public static String summarize(String rawOutput) {
        if (rawOutput == null || rawOutput.isEmpty()) {
            return rawOutput;
        }

        String[] lines = rawOutput.split("\n");
        if (lines.length <= MAX_OUTPUT_LINES) {
            return rawOutput;
        }

        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // ── Pass 1: 提取高信号行 ──
        List<String> signalLines = new ArrayList<>();
        List<String> noiseLines = new ArrayList<>();
        for (String line : lines) {
            String lower = line.toLowerCase();
            boolean isSignal = false;
            boolean isNoise = false;

            for (String kw : SIGNAL_KEYWORDS) {
                if (lower.contains(kw)) {
                    isSignal = true;
                    break;
                }
            }
            if (!isSignal) {
                for (String kw : NOISE_KEYWORDS) {
                    if (lower.contains(kw)) {
                        isNoise = true;
                        break;
                    }
                }
            }

            if (isSignal && seen.add(line)) {
                signalLines.add(truncateLine(line));
            } else if (isNoise) {
                noiseLines.add(line);
            }
        }

        // ── Pass 2: 头尾保留 ──
        List<String> head = new ArrayList<>();
        for (int i = 0; i < Math.min(HEAD_LINES, lines.length); i++) {
            head.add(truncateLine(lines[i]));
        }

        List<String> tail = new ArrayList<>();
        int tailStart = Math.max(HEAD_LINES, lines.length - TAIL_LINES);
        for (int i = tailStart; i < lines.length; i++) {
            tail.add(truncateLine(lines[i]));
        }

        // ── Pass 3: 组装 ──
        result.addAll(head);

        int middleOmitted = lines.length - HEAD_LINES - TAIL_LINES;

        if (!signalLines.isEmpty()) {
            result.add("... [关键信息 " + signalLines.size() + " 条] ...");
            result.addAll(signalLines);
            middleOmitted -= signalLines.size();
        }

        if (!noiseLines.isEmpty() && result.size() < MAX_OUTPUT_LINES) {
            result.add("... [低信息量行 " + noiseLines.size() + " 条已省略] ...");
        }

        if (middleOmitted > 0) {
            result.add("... [省略 " + middleOmitted + " 行] ...");
        }

        result.addAll(tail);
        result.add("[压缩] 原始 " + lines.length + " 行 → " + result.size() + " 行");

        return String.join("\n", result);
    }

    private static String truncateLine(String line) {
        if (line.length() <= MAX_LINE_LENGTH) {
            return line;
        }
        return line.substring(0, MAX_LINE_LENGTH) + "...[截断]";
    }
}
