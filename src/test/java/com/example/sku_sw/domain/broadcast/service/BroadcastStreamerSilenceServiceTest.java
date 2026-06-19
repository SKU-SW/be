package com.example.sku_sw.domain.broadcast.service;

import com.example.sku_sw.domain.broadcast.util.BroadcastRedisUtil;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionBundle;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BroadcastStreamerSilenceServiceTest {
    @Mock TaskScheduler taskScheduler;
    @Mock BroadcastRedisUtil broadcastRedisUtil;
    @Mock BroadcastWebSocketSessionRegistry sessionRegistry;

    @Test
    void completedUtteranceMarksSilentOnlyForCurrentGeneration() {
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
        BroadcastStreamerSilenceService service = new BroadcastStreamerSilenceService(
                taskScheduler, broadcastRedisUtil, sessionRegistry);
        ReflectionTestUtils.setField(service, "silenceThresholdMs", 5000L);
        BroadcastWebSocketSessionBundle bundle = mock(BroadcastWebSocketSessionBundle.class);
        given(sessionRegistry.getSessionBundleIfCurrent("stream-1", 3L)).willReturn(bundle);

        service.markUtteranceCompleted("stream-1", 3L);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(task.capture(), any(Instant.class));
        task.getValue().run();

        verify(broadcastRedisUtil).updateBroadcastUserStreamerSilent("stream-1", false);
        verify(broadcastRedisUtil).updateBroadcastUserStreamerSilent("stream-1", true);
    }
}
