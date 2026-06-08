package com.example.sku_sw.domain.broadcast.service.gemini;

import com.example.sku_sw.domain.broadcast.enums.WebSocketAttributes;
import com.example.sku_sw.domain.broadcast.enums.WebSocketSessionBundleStatus;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionBundle;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionRegistry;
import com.example.sku_sw.domain.broadcast.websocket.gemini.GeminiLiveWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BroadcastGeminiResumptionServiceTest {

    private BroadcastWebSocketSessionRegistry sessionRegistry;
    private BroadcastGeminiResumptionService broadcastGeminiResumptionService;

    @Mock
    private BroadcastGeminiLiveService broadcastGeminiLiveService;

    @Mock
    private BroadcastGeminiRequestService broadcastGeminiRequestService;

    @Mock
    private com.example.sku_sw.global.util.GeminiUtil geminiUtil;

    @Mock
    private GeminiLiveWebSocketHandler resumedHandler;

    @BeforeEach
    void setUp() {
        sessionRegistry = new BroadcastWebSocketSessionRegistry();
        broadcastGeminiResumptionService = new BroadcastGeminiResumptionService(sessionRegistry, broadcastGeminiLiveService, broadcastGeminiRequestService, geminiUtil);
    }

    @Test
    @DisplayName("resumption 성공 - current bundle에 새 Gemini 세션을 교체하고 fallback cleanup을 실행하지 않는다")
    void resumption_성공() {
        // given
        WebSocketSession clientSession = mock(WebSocketSession.class);
        given(clientSession.isOpen()).willReturn(true);
        WebSocketSession oldGeminiSession = mock(WebSocketSession.class);
        Map<String, Object> oldAttributes = new HashMap<>();
        oldAttributes.put(WebSocketAttributes.BROADCAST_STREAM_ID.getValue(), "stream-1");
        given(oldGeminiSession.getAttributes()).willReturn(oldAttributes);
        given(oldGeminiSession.getId()).willReturn("old-session");

        sessionRegistry.registerClientSession("stream-1", clientSession);
        BroadcastWebSocketSessionBundle bundle = sessionRegistry.getSessionBundle("stream-1");
        long generation = bundle.getGeneration();
        sessionRegistry.registerGeminiSessionIfCurrent("stream-1", generation, oldGeminiSession, null);
        sessionRegistry.updateBundleStatusIfCurrent("stream-1", generation, WebSocketSessionBundleStatus.READY);
        bundle.updateGeminiSessionResumptionMetadata("resume-handle", true, Instant.now());

        WebSocketSession resumedGeminiSession = mock(WebSocketSession.class);
        given(resumedGeminiSession.getId()).willReturn("new-session");
        given(broadcastGeminiLiveService.resumeGeminiApiWebSocketAsync("stream-1", generation, "resume-handle"))
                .willReturn(CompletableFuture.completedFuture(resumedGeminiSession));
        given(broadcastGeminiLiveService.consumePendingHandler("new-session")).willReturn(resumedHandler);

        AtomicBoolean fallbackCalled = new AtomicBoolean(false);

        // when
        boolean resumed = broadcastGeminiResumptionService.tryResumeAfterUnexpectedClose(
                oldGeminiSession,
                CloseStatus.SERVER_ERROR,
                () -> fallbackCalled.set(true)
        );

        // then
        assertThat(resumed).isTrue();
        assertThat(fallbackCalled).isFalse();
        assertThat(bundle.getGeminiSession()).isEqualTo(resumedGeminiSession);
        assertThat(bundle.getGeminiHandler()).isEqualTo(resumedHandler);
        assertThat(bundle.getGeminiSessionResumptionInProgress()).isFalse();
        verify(broadcastGeminiLiveService, times(1)).resumeGeminiApiWebSocketAsync("stream-1", generation, "resume-handle");
        verify(broadcastGeminiRequestService, times(1)).getFirstResumptionEvent("stream-1", generation);
    }

    @Test
    @DisplayName("resumption 조건 불충족 - fallback cleanup을 실행한다")
    void resumption_조건불충족() {
        // given
        WebSocketSession closedSession = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(WebSocketAttributes.BROADCAST_STREAM_ID.getValue(), "stream-1");
        given(closedSession.getAttributes()).willReturn(attributes);

        AtomicBoolean fallbackCalled = new AtomicBoolean(false);

        // when
        boolean resumed = broadcastGeminiResumptionService.tryResumeAfterUnexpectedClose(
                closedSession,
                CloseStatus.SERVER_ERROR,
                () -> fallbackCalled.set(true)
        );

        // then
        assertThat(resumed).isFalse();
        assertThat(fallbackCalled).isTrue();
        verify(broadcastGeminiLiveService, never()).resumeGeminiApiWebSocketAsync(eq("stream-1"), anyLong(), eq("resume-handle"));
    }
}
