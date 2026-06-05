package com.example.sku_sw.domain.broadcast.service.gemini;

import com.example.sku_sw.domain.broadcast.dto.BroadcastInfoRedisDto;
import com.example.sku_sw.domain.broadcast.enums.BroadcastCompactionTriggerType;
import com.example.sku_sw.domain.broadcast.enums.BroadcastGeminiRefreshTriggerType;
import com.example.sku_sw.domain.broadcast.enums.DialogueSubject;
import com.example.sku_sw.domain.broadcast.enums.WebSocketAttributes;
import com.example.sku_sw.domain.broadcast.enums.WebSocketSessionBundleStatus;
import com.example.sku_sw.domain.broadcast.event.BroadcastCompactionCheckRequestedEvent;
import com.example.sku_sw.domain.broadcast.event.BroadcastGeminiRefreshRequestedEvent;
import com.example.sku_sw.domain.broadcast.util.BroadcastRedisUtil;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionBundle;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionRegistry;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSender;
import com.example.sku_sw.domain.character.enums.Emotion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BroadcastGeminiResponseServiceTest {

    @InjectMocks
    private BroadcastGeminiResponseService service;

    @Mock
    private BroadcastRedisUtil broadcastRedisUtil;

    @Mock
    private BroadcastWebSocketSessionRegistry sessionRegistry;

    @Mock
    private BroadcastWebSocketSender broadcastWebSocketSender;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Test
    @DisplayName("handleCompletedTurnAsync 성공 - 유효한 번들과 세션 일치 시 Redis 저장, compaction 이벤트 발행, 완료 메타데이터 전송")
    void handleCompletedTurnAsync_성공() {
        // given
        WebSocketSession geminiSession = org.mockito.Mockito.mock(WebSocketSession.class);
        given(geminiSession.isOpen()).willReturn(true);

        WebSocketSession clientSession = org.mockito.Mockito.mock(WebSocketSession.class);
        Map<String, Object> clientAttributes = new HashMap<>();
        clientAttributes.put(WebSocketAttributes.CHARACTER_ID.getValue(), 42L);
        given(clientSession.getAttributes()).willReturn(clientAttributes);

        BroadcastWebSocketSessionBundle bundle = BroadcastWebSocketSessionBundle.builder()
                .clientSession(clientSession)
                .generation(1L)
                .status(WebSocketSessionBundleStatus.READY)
                .build();
        bundle.registerGeminiSession(geminiSession);

        String broadcastStreamId = "stream-1";
        long generation = 1L;
        long turnNumber = 1L;
        String voiceText = "안녕하세요";
        Emotion emotion = Emotion.HAPPY;

        given(sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation)).willReturn(bundle);

        BroadcastInfoRedisDto savedInfo = BroadcastInfoRedisDto.builder()
                .cursorId(100L)
                .subject(DialogueSubject.AI_CHARACTER)
                .content(voiceText)
                .emotion(emotion)
                .sentToGemini(true)
                .build();
        given(broadcastRedisUtil.pushBroadcastInfo(broadcastStreamId, DialogueSubject.AI_CHARACTER, voiceText, emotion, true))
                .willReturn(savedInfo);

        // when
        service.handleCompletedTurnAsync(geminiSession, broadcastStreamId, generation, turnNumber, voiceText, emotion, bundle);

        // then
        verify(broadcastRedisUtil, times(1))
                .pushBroadcastInfo(broadcastStreamId, DialogueSubject.AI_CHARACTER, voiceText, emotion, true);
        verify(applicationEventPublisher, times(1))
                .publishEvent(org.mockito.ArgumentMatchers.argThat((Object event) -> {
                    if (!(event instanceof BroadcastCompactionCheckRequestedEvent)) {
                        return false;
                    }
                    BroadcastCompactionCheckRequestedEvent compactionEvent = (BroadcastCompactionCheckRequestedEvent) event;
                    return broadcastStreamId.equals(compactionEvent.broadcastStreamId())
                            && compactionEvent.triggerType() == BroadcastCompactionTriggerType.AI_DIALOGUE_STORED;
                }));
        verify(broadcastWebSocketSender, times(1))
                .sendTurnCompleteMetadata(
                        eq(broadcastStreamId),
                        eq(generation),
                        eq(42L),
                        eq(turnNumber),
                        eq(voiceText),
                        eq(emotion),
                        eq(100L)
                );
    }

    @Test
    @DisplayName("handleCompletedTurnAsync 실패 - voiceText가 null이면 처리를 건너뛴다")
    void handleCompletedTurnAsync_실패_voiceText_null() {
        // given
        WebSocketSession geminiSession = org.mockito.Mockito.mock(WebSocketSession.class);
        String broadcastStreamId = "stream-1";
        long generation = 1L;
        long turnNumber = 1L;
        BroadcastWebSocketSessionBundle requestOwnerBundle = BroadcastWebSocketSessionBundle.builder()
                .clientSession(org.mockito.Mockito.mock(WebSocketSession.class))
                .generation(generation)
                .status(WebSocketSessionBundleStatus.READY)
                .build();
        requestOwnerBundle.incrementRequestFlight();

        // when
        service.handleCompletedTurnAsync(geminiSession, broadcastStreamId, generation, turnNumber, null, Emotion.TALKING, requestOwnerBundle);

        // then
        verify(broadcastRedisUtil, never()).pushBroadcastInfo(anyString(), any(), anyString(), any(), anyBoolean());
        verify(applicationEventPublisher, never()).publishEvent(any());
        verify(broadcastWebSocketSender, never())
                .sendTurnCompleteMetadata(anyString(), anyLong(), any(), anyLong(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("handleCompletedTurnAsync 실패 - voiceText가 blank이면 처리를 건너뛴다")
    void handleCompletedTurnAsync_실패_voiceText_blank() {
        // given
        WebSocketSession geminiSession = org.mockito.Mockito.mock(WebSocketSession.class);
        String broadcastStreamId = "stream-1";
        long generation = 1L;
        long turnNumber = 1L;
        String voiceText = "   ";
        BroadcastWebSocketSessionBundle requestOwnerBundle = BroadcastWebSocketSessionBundle.builder()
                .clientSession(org.mockito.Mockito.mock(WebSocketSession.class))
                .generation(generation)
                .status(WebSocketSessionBundleStatus.READY)
                .build();
        requestOwnerBundle.incrementRequestFlight();

        // when
        service.handleCompletedTurnAsync(geminiSession, broadcastStreamId, generation, turnNumber, voiceText, Emotion.TALKING, requestOwnerBundle);

        // then
        verify(broadcastRedisUtil, never()).pushBroadcastInfo(anyString(), any(), anyString(), any(), anyBoolean());
        verify(applicationEventPublisher, never()).publishEvent(any());
        verify(broadcastWebSocketSender, never())
                .sendTurnCompleteMetadata(anyString(), anyLong(), any(), anyLong(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("handleCompletedTurnAsync 실패 - getSessionBundleIfCurrent가 null을 반환하면 처리를 건너뛴다")
    void handleCompletedTurnAsync_실패_bundle_null() {
        // given
        WebSocketSession geminiSession = org.mockito.Mockito.mock(WebSocketSession.class);
        String broadcastStreamId = "stream-1";
        long generation = 1L;
        long turnNumber = 1L;
        String voiceText = "안녕하세요";
        BroadcastWebSocketSessionBundle requestOwnerBundle = BroadcastWebSocketSessionBundle.builder()
                .clientSession(org.mockito.Mockito.mock(WebSocketSession.class))
                .generation(generation)
                .status(WebSocketSessionBundleStatus.REFRESHING)
                .build();
        requestOwnerBundle.markRefreshRequested();
        requestOwnerBundle.incrementRequestFlight();

        given(sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation)).willReturn(null);

        // when
        service.handleCompletedTurnAsync(geminiSession, broadcastStreamId, generation, turnNumber, voiceText, Emotion.TALKING, requestOwnerBundle);

        // then
        verify(broadcastRedisUtil, never()).pushBroadcastInfo(anyString(), any(), anyString(), any(), anyBoolean());
        verify(broadcastWebSocketSender, never())
                .sendTurnCompleteMetadata(anyString(), anyLong(), any(), anyLong(), anyString(), any(), anyLong());
        verify(applicationEventPublisher, times(1))
                .publishEvent(org.mockito.ArgumentMatchers.argThat((Object event) -> event instanceof BroadcastGeminiRefreshRequestedEvent));
    }

    @Test
    @DisplayName("handleCompletedTurnAsync 실패 - 번들이 READY 상태가 아니면 처리를 건너뛴다")
    void handleCompletedTurnAsync_실패_bundle_not_ready() {
        // given
        WebSocketSession geminiSession = org.mockito.Mockito.mock(WebSocketSession.class);
        BroadcastWebSocketSessionBundle bundle = BroadcastWebSocketSessionBundle.builder()
                .clientSession(org.mockito.Mockito.mock(WebSocketSession.class))
                .generation(1L)
                .status(WebSocketSessionBundleStatus.GEMINI_CONNECTING)
                .build();
        bundle.registerGeminiSession(geminiSession);

        String broadcastStreamId = "stream-1";
        long generation = 1L;
        long turnNumber = 1L;
        String voiceText = "안녕하세요";

        given(sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation)).willReturn(bundle);

        // when
        service.handleCompletedTurnAsync(geminiSession, broadcastStreamId, generation, turnNumber, voiceText, Emotion.TALKING, bundle);

        // then
        verify(broadcastRedisUtil, never()).pushBroadcastInfo(anyString(), any(), anyString(), any(), anyBoolean());
        verify(applicationEventPublisher, never()).publishEvent(any());
        verify(broadcastWebSocketSender, never())
                .sendTurnCompleteMetadata(anyString(), anyLong(), any(), anyLong(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("handleCompletedTurnAsync 실패 - Gemini 세션이 일치하지 않으면 처리를 건너뛴다")
    void handleCompletedTurnAsync_실패_gemini_session_mismatch() {
        // given
        WebSocketSession geminiSession = org.mockito.Mockito.mock(WebSocketSession.class);
        WebSocketSession otherGeminiSession = org.mockito.Mockito.mock(WebSocketSession.class);
        given(otherGeminiSession.isOpen()).willReturn(true);

        BroadcastWebSocketSessionBundle bundle = BroadcastWebSocketSessionBundle.builder()
                .clientSession(org.mockito.Mockito.mock(WebSocketSession.class))
                .generation(1L)
                .status(WebSocketSessionBundleStatus.READY)
                .build();
        bundle.registerGeminiSession(otherGeminiSession);

        String broadcastStreamId = "stream-1";
        long generation = 1L;
        long turnNumber = 1L;
        String voiceText = "안녕하세요";

        given(sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation)).willReturn(bundle);

        // when
        service.handleCompletedTurnAsync(geminiSession, broadcastStreamId, generation, turnNumber, voiceText, Emotion.TALKING, bundle);

        // then
        verify(broadcastRedisUtil, never()).pushBroadcastInfo(anyString(), any(), anyString(), any(), anyBoolean());
        verify(applicationEventPublisher, never()).publishEvent(any());
        verify(broadcastWebSocketSender, never())
                .sendTurnCompleteMetadata(anyString(), anyLong(), any(), anyLong(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("forwardStreamingChunk 성공 - 유효한 번들과 세션 일치 시 음성 청크 메타데이터를 전송한다")
    void forwardStreamingChunk_성공() {
        // given
        WebSocketSession geminiSession = org.mockito.Mockito.mock(WebSocketSession.class);
        given(geminiSession.isOpen()).willReturn(true);

        WebSocketSession clientSession = org.mockito.Mockito.mock(WebSocketSession.class);
        Map<String, Object> clientAttributes = new HashMap<>();
        clientAttributes.put(WebSocketAttributes.CHARACTER_ID.getValue(), 7L);
        given(clientSession.getAttributes()).willReturn(clientAttributes);

        BroadcastWebSocketSessionBundle bundle = BroadcastWebSocketSessionBundle.builder()
                .clientSession(clientSession)
                .generation(1L)
                .status(WebSocketSessionBundleStatus.READY)
                .build();
        bundle.registerGeminiSession(geminiSession);

        String broadcastStreamId = "stream-1";
        long generation = 1L;
        long turnNumber = 1L;
        String voiceTextChunk = "안녕";
        byte[] voiceDataChunk = new byte[]{1, 2, 3};

        given(sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation)).willReturn(bundle);

        // when
        service.forwardStreamingChunk(geminiSession, broadcastStreamId, generation, turnNumber, voiceTextChunk, voiceDataChunk, Emotion.TALKING);

        // then
        verify(broadcastWebSocketSender, times(1))
                .sendVoiceChunkWithMetadata(
                        eq(broadcastStreamId),
                        eq(generation),
                        eq(voiceDataChunk),
                        eq(7L),
                        eq(turnNumber),
                        eq(voiceTextChunk),
                        eq(Emotion.TALKING)
                );
    }

    @Test
    @DisplayName("forwardStreamingChunk 실패 - 텍스트와 오디오가 모두 비어 있으면 처리를 건너뛴다")
    void forwardStreamingChunk_실패_empty_chunk() {
        // given
        WebSocketSession geminiSession = org.mockito.Mockito.mock(WebSocketSession.class);
        String broadcastStreamId = "stream-1";
        long generation = 1L;
        long turnNumber = 1L;

        // when
        service.forwardStreamingChunk(geminiSession, broadcastStreamId, generation, turnNumber, null, null, Emotion.TALKING);

        // then
        verify(broadcastWebSocketSender, never())
                .sendVoiceChunkWithMetadata(anyString(), anyLong(), any(), any(), anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("forwardStreamingChunk 실패 - getSessionBundleIfCurrent가 null을 반환하면 처리를 건너뛴다")
    void forwardStreamingChunk_실패_bundle_null() {
        // given
        WebSocketSession geminiSession = org.mockito.Mockito.mock(WebSocketSession.class);
        String broadcastStreamId = "stream-1";
        long generation = 1L;
        long turnNumber = 1L;
        String voiceTextChunk = "안녕";
        byte[] voiceDataChunk = new byte[]{1, 2, 3};

        given(sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation)).willReturn(null);

        // when
        service.forwardStreamingChunk(geminiSession, broadcastStreamId, generation, turnNumber, voiceTextChunk, voiceDataChunk, Emotion.TALKING);

        // then
        verify(broadcastWebSocketSender, never())
                .sendVoiceChunkWithMetadata(anyString(), anyLong(), any(), any(), anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("forwardStreamingChunk 실패 - 번들이 READY 상태가 아니면 처리를 건너뛴다")
    void forwardStreamingChunk_실패_bundle_not_ready() {
        // given
        WebSocketSession geminiSession = org.mockito.Mockito.mock(WebSocketSession.class);
        BroadcastWebSocketSessionBundle bundle = BroadcastWebSocketSessionBundle.builder()
                .clientSession(org.mockito.Mockito.mock(WebSocketSession.class))
                .generation(1L)
                .status(WebSocketSessionBundleStatus.GEMINI_CONNECTING)
                .build();
        bundle.registerGeminiSession(geminiSession);

        String broadcastStreamId = "stream-1";
        long generation = 1L;
        long turnNumber = 1L;
        String voiceTextChunk = "안녕";
        byte[] voiceDataChunk = new byte[]{1, 2, 3};

        given(sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation)).willReturn(bundle);

        // when
        service.forwardStreamingChunk(geminiSession, broadcastStreamId, generation, turnNumber, voiceTextChunk, voiceDataChunk, Emotion.TALKING);

        // then
        verify(broadcastWebSocketSender, never())
                .sendVoiceChunkWithMetadata(anyString(), anyLong(), any(), any(), anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("forwardStreamingChunk 실패 - Gemini 세션이 일치하지 않으면 처리를 건너뛴다")
    void forwardStreamingChunk_실패_gemini_session_mismatch() {
        // given
        WebSocketSession geminiSession = org.mockito.Mockito.mock(WebSocketSession.class);
        WebSocketSession otherGeminiSession = org.mockito.Mockito.mock(WebSocketSession.class);
        given(otherGeminiSession.isOpen()).willReturn(true);

        BroadcastWebSocketSessionBundle bundle = BroadcastWebSocketSessionBundle.builder()
                .clientSession(org.mockito.Mockito.mock(WebSocketSession.class))
                .generation(1L)
                .status(WebSocketSessionBundleStatus.READY)
                .build();
        bundle.registerGeminiSession(otherGeminiSession);

        String broadcastStreamId = "stream-1";
        long generation = 1L;
        long turnNumber = 1L;
        String voiceTextChunk = "안녕";
        byte[] voiceDataChunk = new byte[]{1, 2, 3};

        given(sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation)).willReturn(bundle);

        // when
        service.forwardStreamingChunk(geminiSession, broadcastStreamId, generation, turnNumber, voiceTextChunk, voiceDataChunk, Emotion.TALKING);

        // then
        verify(broadcastWebSocketSender, never())
                .sendVoiceChunkWithMetadata(anyString(), anyLong(), any(), any(), anyLong(), anyString(), any());

    }

    @Test
    @DisplayName("forwardEmotionUpdate 성공 - 유효한 번들과 세션 일치 시 감정 메타데이터를 전송한다")
    void forwardEmotionUpdate_성공() {
        // given
        WebSocketSession geminiSession = org.mockito.Mockito.mock(WebSocketSession.class);
        given(geminiSession.isOpen()).willReturn(true);

        WebSocketSession clientSession = org.mockito.Mockito.mock(WebSocketSession.class);
        Map<String, Object> clientAttributes = new HashMap<>();
        clientAttributes.put(WebSocketAttributes.CHARACTER_ID.getValue(), 9L);
        given(clientSession.getAttributes()).willReturn(clientAttributes);

        BroadcastWebSocketSessionBundle bundle = BroadcastWebSocketSessionBundle.builder()
                .clientSession(clientSession)
                .generation(1L)
                .status(WebSocketSessionBundleStatus.READY)
                .build();
        bundle.registerGeminiSession(geminiSession);

        given(sessionRegistry.getSessionBundleIfCurrent("stream-1", 1L)).willReturn(bundle);

        // when
        service.forwardEmotionUpdate(geminiSession, "stream-1", 1L, 3L, Emotion.HAPPY);

        // then
        verify(broadcastWebSocketSender, times(1))
                .sendEmotionMetadata("stream-1", 1L, 9L, 3L, Emotion.HAPPY);
    }

    @Test
    @DisplayName("handleGeminiTurnFinished 성공 - refresh 요청 상태이며 in-flight가 0이면 refresh 이벤트를 발행한다")
    void handleGeminiTurnFinished_성공_refresh_event_publish() {
        // given
        String broadcastStreamId = "stream-1";
        long generation = 1L;
        BroadcastWebSocketSessionBundle bundle = BroadcastWebSocketSessionBundle.builder()
                .clientSession(org.mockito.Mockito.mock(WebSocketSession.class))
                .generation(generation)
                .status(WebSocketSessionBundleStatus.REFRESHING)
                .build();
        bundle.markRefreshRequested();
        bundle.incrementRequestFlight();

        // when
        service.handleGeminiTurnFinished(broadcastStreamId, generation, bundle);

        // then
        verify(applicationEventPublisher, times(1))
                .publishEvent(org.mockito.ArgumentMatchers.argThat((Object event) -> {
                    if (!(event instanceof BroadcastGeminiRefreshRequestedEvent)) {
                        return false;
                    }
                    BroadcastGeminiRefreshRequestedEvent refreshEvent = (BroadcastGeminiRefreshRequestedEvent) event;
                    return broadcastStreamId.equals(refreshEvent.broadcastStreamId())
                            && generation == refreshEvent.generation()
                            && refreshEvent.triggerType() == BroadcastGeminiRefreshTriggerType.TURN_FINISHED;
                }));
    }

    @Test
    @DisplayName("handleGeminiTurnFinished 성공 - in-flight가 남아 있으면 refresh 이벤트를 발행하지 않는다")
    void handleGeminiTurnFinished_성공_no_event_when_inflight_remaining() {
        // given
        String broadcastStreamId = "stream-1";
        long generation = 1L;
        BroadcastWebSocketSessionBundle bundle = BroadcastWebSocketSessionBundle.builder()
                .clientSession(org.mockito.Mockito.mock(WebSocketSession.class))
                .generation(generation)
                .status(WebSocketSessionBundleStatus.REFRESHING)
                .build();
        bundle.markRefreshRequested();
        bundle.incrementRequestFlight();
        bundle.incrementRequestFlight();

        // when
        service.handleGeminiTurnFinished(broadcastStreamId, generation, bundle);

        // then
        verify(applicationEventPublisher, never())
                .publishEvent(org.mockito.ArgumentMatchers.argThat((Object event) -> event instanceof BroadcastGeminiRefreshRequestedEvent));
    }

    @Test
    @DisplayName("handleCompletedTurnAsync 성공 - stale completion이어도 request-flight를 감소시키고 refresh 이벤트를 발행한다")
    void handleCompletedTurnAsync_성공_stale_completion_cleanup() {
        // given
        WebSocketSession requestOwnerGeminiSession = org.mockito.Mockito.mock(WebSocketSession.class);
        given(requestOwnerGeminiSession.isOpen()).willReturn(true);

        WebSocketSession staleGeminiSession = org.mockito.Mockito.mock(WebSocketSession.class);
        BroadcastWebSocketSessionBundle requestOwnerBundle = BroadcastWebSocketSessionBundle.builder()
                .clientSession(org.mockito.Mockito.mock(WebSocketSession.class))
                .generation(1L)
                .status(WebSocketSessionBundleStatus.REFRESHING)
                .build();
        requestOwnerBundle.registerGeminiSession(requestOwnerGeminiSession);
        requestOwnerBundle.markRefreshRequested();
        requestOwnerBundle.incrementRequestFlight();

        BroadcastWebSocketSessionBundle currentBundle = BroadcastWebSocketSessionBundle.builder()
                .clientSession(org.mockito.Mockito.mock(WebSocketSession.class))
                .generation(1L)
                .status(WebSocketSessionBundleStatus.REFRESHING)
                .build();
        currentBundle.registerGeminiSession(requestOwnerGeminiSession);

        String broadcastStreamId = "stream-1";
        long generation = 1L;
        long turnNumber = 1L;
        String voiceText = "안녕하세요";

        given(sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation)).willReturn(currentBundle);

        // when
        service.handleCompletedTurnAsync(staleGeminiSession, broadcastStreamId, generation, turnNumber, voiceText, Emotion.TALKING, requestOwnerBundle);

        // then
        verify(broadcastRedisUtil, never()).pushBroadcastInfo(anyString(), any(), anyString(), any(), anyBoolean());
        verify(broadcastWebSocketSender, never())
                .sendTurnCompleteMetadata(anyString(), anyLong(), any(), anyLong(), anyString(), any(), anyLong());
        verify(applicationEventPublisher, times(1))
                .publishEvent(org.mockito.ArgumentMatchers.argThat((Object event) -> {
                    if (!(event instanceof BroadcastGeminiRefreshRequestedEvent)) {
                        return false;
                    }
                    BroadcastGeminiRefreshRequestedEvent refreshEvent = (BroadcastGeminiRefreshRequestedEvent) event;
                    return broadcastStreamId.equals(refreshEvent.broadcastStreamId())
                            && generation == refreshEvent.generation()
                            && refreshEvent.triggerType() == BroadcastGeminiRefreshTriggerType.TURN_FINISHED;
                }));
    }
}
