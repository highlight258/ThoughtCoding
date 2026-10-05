package com.thoughtcoding.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ServerConnectionStateTest {

    @Test
    void backoffSequenceCapsAtSixtySeconds() {
        ServerConnectionState state = new ServerConnectionState("s", "cmd", List.of());
        int[] expected = {1, 2, 4, 8, 16, 32, 60, 60, 60};
        for (int expectedDelay : expected) {
            assertEquals(expectedDelay, state.getAndIncrementBackoff());
        }
    }

    @Test
    void resetBackoffClearsFailuresAndDelay() {
        ServerConnectionState state = new ServerConnectionState("s", "cmd", List.of());
        state.getAndIncrementBackoff();
        state.getAndIncrementBackoff();
        state.getAndIncrementBackoff();
        state.resetBackoff();
        assertEquals(0, state.getConsecutiveFailures());
        assertEquals(0, state.getBackoffSeconds());
        assertEquals(1, state.getAndIncrementBackoff());
    }

    @Test
    void initialStateIsHealthy() {
        ServerConnectionState state = new ServerConnectionState("s", "cmd", List.of());
        assertEquals(ServerConnectionState.State.HEALTHY, state.getState());
        assertEquals(0, state.getGeneration());
    }

    @Test
    void incrementGenerationIsMonotonic() {
        ServerConnectionState state = new ServerConnectionState("s", "cmd", List.of());
        assertEquals(1, state.incrementGeneration());
        assertEquals(2, state.incrementGeneration());
    }

    @Test
    void updateConnectionInfoReplacesCommandAndArgs() {
        ServerConnectionState state = new ServerConnectionState("s", "old", List.of("a"));
        state.updateConnectionInfo("new", List.of("b", "c"));
        assertEquals("new", state.getCommand());
        assertEquals(List.of("b", "c"), state.getArgs());
    }
}
