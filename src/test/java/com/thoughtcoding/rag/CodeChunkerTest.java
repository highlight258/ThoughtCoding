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
}
