package com.thoughtcoding.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ContextManagerTest {

    @Test
    void emptyTextIsZeroTokens() {
        assertEquals(0, ContextManager.estimateTokens(""));
        assertEquals(0, ContextManager.estimateTokens(null));
    }

    @Test
    void englishTokensAreFewerThanCharacters() {
        String text = "The quick brown fox jumps over the lazy dog";
        int tokens = ContextManager.estimateTokens(text);
        assertTrue(tokens > 0, "token 数应大于 0");
        assertTrue(tokens < text.length(), "BPE token 数应远小于字符数");
    }
}
