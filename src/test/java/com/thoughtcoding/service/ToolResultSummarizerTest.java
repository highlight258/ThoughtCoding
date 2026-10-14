package com.thoughtcoding.service;

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
    void severityLineIsExtracted() {
        ToolResultSummarizer.setStorageDir(tempDir.toString());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append("INFO normal ").append(i).append("\n");
        }
        sb.append("ERROR something broke\n");
        for (int i = 0; i < 40; i++) {
            sb.append("DEBUG trace ").append(i).append("\n");
        }

        String result = ToolResultSummarizer.summarize(sb.toString(), "test_tool");

        assertTrue(result.contains("ERROR something broke"), "ERROR 行应被提取为高信号");
    }
}
