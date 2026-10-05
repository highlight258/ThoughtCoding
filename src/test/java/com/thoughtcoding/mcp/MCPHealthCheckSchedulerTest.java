package com.thoughtcoding.mcp;

import com.thoughtcoding.mcp.model.MCPTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class MCPHealthCheckSchedulerTest {

    private MCPService mcpService;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> mockFuture;

    @BeforeEach
    void setUp() {
        mcpService = mock(MCPService.class);
        executor = mock(ScheduledExecutorService.class);
        mockFuture = mock(ScheduledFuture.class);
        doReturn(mockFuture)
                .when(executor).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    private MCPHealthCheckScheduler newScheduler(Function<String, MCPClient> clientFactory) {
        return new MCPHealthCheckScheduler(mcpService, clientFactory, executor);
    }

    @Test
    void pingFailureTransitionsToReconnecting() {
        MCPClient client = mock(MCPClient.class);
        when(client.ping(anyInt())).thenReturn(false);
        when(mcpService.getClient("filesystem")).thenReturn(client);

        MCPHealthCheckScheduler scheduler = newScheduler(name -> mock(MCPClient.class));
        scheduler.onServerConnected("filesystem", "cmd", List.of());

        scheduler.healthCheckCycle();

        ServerConnectionState state = scheduler.getConnectionState("filesystem");
        assertEquals(ServerConnectionState.State.RECONNECTING, state.getState());
        verify(executor).schedule(any(Runnable.class), eq(1L), eq(TimeUnit.SECONDS));
    }

    @Test
    void reconnectSuccessReturnsToHealthy() {
        MCPClient client = mock(MCPClient.class);
        when(client.connect(anyString(), any())).thenReturn(true);
        MCPTool tool = mock(MCPTool.class);
        when(tool.getName()).thenReturn("test_tool");
        when(tool.getDescription()).thenReturn("desc");
        when(client.getAvailableTools()).thenReturn(List.of(tool));

        MCPHealthCheckScheduler scheduler = newScheduler(name -> client);
        scheduler.onServerConnected("filesystem", "cmd", List.of());
        ServerConnectionState state = scheduler.getConnectionState("filesystem");
        state.setState(ServerConnectionState.State.RECONNECTING);

        scheduler.performReconnect("filesystem", state, state.getGeneration());

        assertEquals(ServerConnectionState.State.HEALTHY, state.getState());
        verify(mcpService).registerReconnectedServer(eq("filesystem"), eq(client), anyList());
    }

    @Test
    void reconnectGivesUpAfterTenFailures() {
        MCPClient pingClient = mock(MCPClient.class);
        when(pingClient.ping(anyInt())).thenReturn(false);
        when(mcpService.getClient("filesystem")).thenReturn(pingClient);

        MCPClient connectClient = mock(MCPClient.class);
        when(connectClient.connect(anyString(), any())).thenReturn(false);

        MCPHealthCheckScheduler scheduler = newScheduler(name -> connectClient);
        scheduler.onServerConnected("filesystem", "cmd", List.of());

        // 触发健康检查失败 → 进入 RECONNECTING 并调度首次重连（consecutiveFailures=1）
        scheduler.healthCheckCycle();
        ServerConnectionState state = scheduler.getConnectionState("filesystem");
        assertEquals(ServerConnectionState.State.RECONNECTING, state.getState());

        // 之后连续失败，累计达 10 次后放弃
        for (int i = 0; i < 10; i++) {
            scheduler.performReconnect("filesystem", state, state.getGeneration());
        }

        assertEquals(ServerConnectionState.State.ABANDONED, state.getState());
    }

    @Test
    void manualDisconnectCancelsPendingReconnect() {
        MCPClient client = mock(MCPClient.class);
        when(client.ping(anyInt())).thenReturn(false);
        when(mcpService.getClient("filesystem")).thenReturn(client);

        AtomicReference<Runnable> captured = new AtomicReference<>();
        doAnswer(inv -> {
                    captured.set(inv.getArgument(0));
                    return mockFuture;
                })
                .when(executor).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));

        MCPHealthCheckScheduler scheduler = newScheduler(name -> mock(MCPClient.class));
        scheduler.onServerConnected("filesystem", "cmd", List.of());
        scheduler.healthCheckCycle();

        scheduler.onServerDisconnected("filesystem");

        verify(mockFuture).cancel(false);
        assertNull(scheduler.getConnectionState("filesystem"));

        Runnable stale = captured.get();
        assertNotNull(stale);
        stale.run();
        verify(mcpService, never()).closeConnectionForReconnect(anyString());
    }
}
