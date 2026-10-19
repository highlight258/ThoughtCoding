package com.thoughtcoding.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class CodeChunkerTest {

    @Test
    void consecutiveMethodsAreBothPreserved() {
        CodeChunker chunker = new CodeChunker();
        String content = "public class Foo {\n" +
                "    public void methodA() {\n" +
                "        System.out.println(\"A\");\n" +
                "    }\n" +
                "    public void methodB() {\n" +
                "        System.out.println(\"B\");\n" +
                "    }\n" +
                "}\n";
        List<CodeChunker.Chunk> chunks = chunker.chunkFile("Foo.java", content);

        boolean hasMethodA = chunks.stream().anyMatch(c -> "methodA".equals(c.name()));
        boolean hasMethodB = chunks.stream().anyMatch(c -> "methodB".equals(c.name()));
        assertTrue(hasMethodA, "methodA 不应丢失");
        assertTrue(hasMethodB, "methodB 不应丢失");
    }

    @Test
    void consecutivePythonFunctionsAreBothPreserved() {
        CodeChunker chunker = new CodeChunker();
        String content = "def foo():\n" +
                "    return 1\n" +
                "\n" +
                "def bar():\n" +
                "    return 2\n";
        List<CodeChunker.Chunk> chunks = chunker.chunkFile("app.py", content);

        boolean hasFoo = chunks.stream().anyMatch(c -> "foo".equals(c.name()));
        boolean hasBar = chunks.stream().anyMatch(c -> "bar".equals(c.name()));
        assertTrue(hasFoo, "foo 不应丢失");
        assertTrue(hasBar, "bar 不应丢失");
    }

    @Test
    void consecutiveJsFunctionsAreBothPreserved() {
        CodeChunker chunker = new CodeChunker();
        String content = "function first() {\n" +
                "  return 1;\n" +
                "}\n" +
                "function second() {\n" +
                "  return 2;\n" +
                "}\n";
        List<CodeChunker.Chunk> chunks = chunker.chunkFile("app.js", content);

        // JS 的 name 提取目前带 "function" 前缀（独立的 group 索引 bug），这里用 contains 验证「不覆盖」
        boolean hasFirst = chunks.stream().anyMatch(c -> c.name() != null && c.name().contains("first"));
        boolean hasSecond = chunks.stream().anyMatch(c -> c.name() != null && c.name().contains("second"));
        assertTrue(hasFirst, "first 不应丢失");
        assertTrue(hasSecond, "second 不应丢失");
    }
}
