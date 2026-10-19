package com.thoughtcoding.service;

import com.thoughtcoding.model.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class ToolResultSummarizerTest {

    @TempDir
    Path tempDir;

    @Test
    void shortOutputIsUnchanged() {
        String out = "line1\nline2\nline3";
        assertEquals(out, ToolResultSummarizer.summarize(out, "test_tool"));
    }

    @Test
    void longOutputIsOffloadedAndMarked() throws Exception {
        ToolResultSummarizer.setStorageDir(tempDir.toString());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("line ").append(i).append("\n");
        }

        String result = ToolResultSummarizer.summarize(sb.toString(), "test_tool");

        assertTrue(result.contains("已保存至"), "应包含恢复路径标记");
        assertEquals(1, Files.list(tempDir).count(), "应落盘 1 个文件");
    }

    @Test
    void failedResultPrioritizesStderr() {
        ToolResult result = ToolResult.command("正常输出 stdout", "错误详情 stderr", 1, 10);
        String summary = ToolResultSummarizer.summarize(result, "test_tool");

        assertTrue(summary.contains("错误详情 stderr"), "失败结果应优先保留 stderr");
    }
}
