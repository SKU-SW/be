package com.example.sku_sw.domain.broadcast.service;

import com.example.sku_sw.domain.broadcast.dto.BroadcastInfoRedisDto;
import com.example.sku_sw.domain.broadcast.enums.BroadcastDataStatus;
import com.example.sku_sw.domain.broadcast.enums.DialogueSubject;
import com.example.sku_sw.domain.broadcast.service.gemini.BroadcastGeminiRequestService;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BroadcastProactiveChatServiceTest {
    @Mock TaskScheduler taskScheduler;
    @Mock BroadcastRedisUtil broadcastRedisUtil;
    @Mock BroadcastWebSocketSessionRegistry sessionRegistry;
    @Mock BroadcastGeminiRequestService geminiRequestService;

    @Test
    void viewerCandidatesAreSentAndMarkedAsOneBatch() {
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
        given(broadcastRedisUtil.isAiProactiveToChatEnabled("stream-1")).willReturn(true);
        given(broadcastRedisUtil.isStreamerSilent("stream-1")).willReturn(true);
        BroadcastInfoRedisDto info = new BroadcastInfoRedisDto(
                10L, DialogueSubject.VIEWER, "(시청자, tester)재밌다", null,
                LocalDateTime.now(), BroadcastDataStatus.ACTIVE, false);
        given(broadcastRedisUtil.getUnsentDialoguesByCursorIds(eq("stream-1"), any()))
                .willReturn(List.of(info));
        BroadcastWebSocketSessionBundle bundle = mock(BroadcastWebSocketSessionBundle.class);
        given(bundle.canSendToGemini()).willReturn(true);
        given(bundle.getRequestFlightCountValue()).willReturn(0);
        given(bundle.getGeneration()).willReturn(7L);
        given(sessionRegistry.getSessionBundle("stream-1")).willReturn(bundle);
        given(geminiRequestService.sendProactiveChatRequest(eq("stream-1"), eq(7L), anyString()))
                .willReturn(true);

        BroadcastProactiveChatService service = new BroadcastProactiveChatService(
                taskScheduler, broadcastRedisUtil, sessionRegistry, geminiRequestService);
        ReflectionTestUtils.setField(service, "batchWindowMs", 2000L);
        service.enqueue("stream-1", 10L);

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(task.capture(), any(Instant.class));
        task.getValue().run();

        verify(geminiRequestService).sendProactiveChatRequest(
                eq("stream-1"), eq(7L), eq("(시청자, tester)재밌다"));
        verify(broadcastRedisUtil).markDialoguesSentToGemini("stream-1", List.of(10L));
    }
}
