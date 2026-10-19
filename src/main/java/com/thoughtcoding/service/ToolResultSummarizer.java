package com.thoughtcoding.service;

import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.util.FileUtils;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;

/**
 * 工具输出压缩器（基于结构化字段，无损可恢复）。
 *
 * 策略：
 * 1. 失败时优先保留 stderr / error（结构化字段，不再靠正则猜「高信号行」）
 * 2. 超长输出落盘保留完整原文，写回头尾预览 + 恢复路径标记，模型可回读
 */
public class ToolResultSummarizer {

    private static final int MAX_OUTPUT_LINES = 50;
    private static final int MAX_OUTPUT_CHARS = 20_000;
    private static final int HEAD_LINES = 10;
    private static final int TAIL_LINES = 10;
    private static final int MAX_LINE_LENGTH = 500;
    private static final int MAX_ARTIFACT_FILES = 100;

    // 落盘目录（默认 sessions/tool-results，测试可覆盖）
    private static volatile String storageDir = "sessions/tool-results";

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    /** 基于结构化 ToolResult 压缩：失败优先 stderr，成功压缩 stdout */
    public static String summarize(ToolResult result, String toolName) {
        if (result.isSuccess()) {
            return summarizeText(result.getOutput(), toolName);
        }
        String stderr = result.getStderr();
        String text = (stderr != null && !stderr.isEmpty()) ? stderr : result.getError();
        String summarized = summarizeText(text, toolName);
        // 失败时固定带出退出码，避免模型只看到错误文本却不知道失败码
        if (text != null && !text.isEmpty()) {
            return "[退出码 " + result.getExitCode() + "]\n" + summarized;
        }
        return summarized;
    }

    /** 纯文本压缩（向后兼容，供测试与直接文本使用） */
    public static String summarize(String rawOutput, String toolName) {
        return summarizeText(rawOutput, toolName);
    }

    private static String summarizeText(String text, String toolName) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }

        boolean exceedsChars = text.length() > MAX_OUTPUT_CHARS;
        String[] lines = text.split("\n");
        if (!exceedsChars && lines.length <= MAX_OUTPUT_LINES) {
            return text;
        }

        // 1. 落盘完整原文（无损可恢复）。必须在截断之前，保证落盘的是完整原文
        String artifactPath = offload(text, toolName);

        // 2. 字符超限时先按字符截断出预览文本，再走头尾保留/行数压缩
        String preview = exceedsChars ? text.substring(0, MAX_OUTPUT_CHARS) : text;
        String[] previewLines = preview.split("\n");

        StringBuilder sb = new StringBuilder();
        if (previewLines.length <= MAX_OUTPUT_LINES) {
            // 行数不多但单行字符量巨大：回传字符截断后的预览（完整原文已落盘）
            sb.append(preview)
              .append("\n[输出超过 ").append(MAX_OUTPUT_CHARS).append(" 字符，已截断]");
        } else {
            // 头尾保留
            for (int i = 0; i < Math.min(HEAD_LINES, previewLines.length); i++) {
                sb.append(truncateLine(previewLines[i])).append('\n');
            }
            int omitted = Math.max(0, previewLines.length - HEAD_LINES - TAIL_LINES);
            sb.append("... [省略 ").append(omitted).append(" 行] ...\n");
            int tailStart = Math.max(HEAD_LINES, previewLines.length - TAIL_LINES);
            for (int i = tailStart; i < previewLines.length; i++) {
                sb.append(truncateLine(previewLines[i])).append('\n');
            }
        }
        sb.append("[完整输出 ");
        if (exceedsChars) {
            sb.append(text.length()).append(" 字符 / ");
        }
        sb.append(lines.length).append(" 行已保存至 ")
          .append(artifactPath).append("，需要时调用文件读取工具读取该文件查看]");
        return sb.toString();
    }

    /** 落盘完整原文，返回文件路径；失败时降级返回占位说明 */
    private static String offload(String rawOutput, String toolName) {
        String safeTool = toolName == null ? "tool" : toolName.replaceAll("[^\\w.-]", "_");
        String name = safeTool + "_" + LocalDateTime.now().format(TIMESTAMP) + ".txt";
        Path path = Paths.get(storageDir, name);
        try {
            FileUtils.writeFile(path, rawOutput);
            cleanupOldArtifacts();
            return path.toString();
        } catch (Exception e) {
            return "<落盘失败: " + e.getMessage() + ">";
        }
    }

    /** 按文件数量上限淘汰最旧的落盘产物，防止目录无限增长。清理失败不影响主流程。 */
    private static void cleanupOldArtifacts() {
        try {
            File dir = new File(storageDir);
            File[] files = dir.listFiles((d, name) -> name.endsWith(".txt"));
            if (files == null || files.length <= MAX_ARTIFACT_FILES) {
                return;
            }
            Arrays.sort(files, Comparator.comparingLong(File::lastModified));
            int toDelete = files.length - MAX_ARTIFACT_FILES;
            for (int i = 0; i < toDelete; i++) {
                files[i].delete();
            }
        } catch (Exception e) {
            // 忽略清理异常
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
