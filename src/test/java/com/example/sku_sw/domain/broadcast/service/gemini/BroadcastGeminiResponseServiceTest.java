package com.example.sku_sw.domain.broadcast.service.gemini;

import com.example.sku_sw.domain.broadcast.dto.BroadcastInfoRedisDto;
import com.example.sku_sw.domain.broadcast.enums.DialogueSubject;
import com.example.sku_sw.domain.broadcast.enums.WebSocketAttributes;
import com.example.sku_sw.domain.broadcast.enums.WebSocketSessionBundleStatus;
import com.example.sku_sw.domain.broadcast.service.BroadcastDialogueCompactionService;
import com.example.sku_sw.domain.broadcast.util.BroadcastRedisUtil;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionBundle;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionRegistry;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketVoiceSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
    private BroadcastWebSocketVoiceSender broadcastWebSocketVoiceSender;

    @Mock
    private BroadcastDialogueCompactionService broadcastDialogueCompactionService;

    @Test
    @DisplayName("handleCompletedTurnAsync 성공 - 유효한 번들과 세션 일치 시 Redis 저장, Compaction, 완료 메타데이터 전송")
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

        given(sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation)).willReturn(bundle);

        BroadcastInfoRedisDto savedInfo = BroadcastInfoRedisDto.builder()
                .cursorId(100L)
                .subject(DialogueSubject.AI_CHARACTER)
                .content(voiceText)
                .build();
        given(broadcastRedisUtil.pushBroadcastInfo(broadcastStreamId, DialogueSubject.AI_CHARACTER, voiceText))
                .willReturn(savedInfo);

        // when
        service.handleCompletedTurnAsync(geminiSession, broadcastStreamId, generation, turnNumber, voiceText);

        // then
        verify(broadcastRedisUtil, times(1))
                .pushBroadcastInfo(broadcastStreamId, DialogueSubject.AI_CHARACTER, voiceText);
        verify(broadcastDialogueCompactionService, times(1))
                .tryStartCompaction(broadcastStreamId);
        verify(broadcastWebSocketVoiceSender, times(1))
                .sendTurnCompleteMetadata(
                        eq(broadcastStreamId),
                        eq(generation),
                        eq(42L),
                        eq(turnNumber),
                        eq(voiceText),
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

        // when
        service.handleCompletedTurnAsync(geminiSession, broadcastStreamId, generation, turnNumber, null);

        // then
        verify(broadcastRedisUtil, never()).pushBroadcastInfo(anyString(), any(), any());
        verify(broadcastDialogueCompactionService, never()).tryStartCompaction(anyString());
        verify(broadcastWebSocketVoiceSender, never())
                .sendTurnCompleteMetadata(anyString(), anyLong(), any(), anyLong(), anyString(), anyLong());
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

        // when
        service.handleCompletedTurnAsync(geminiSession, broadcastStreamId, generation, turnNumber, voiceText);

        // then
        verify(broadcastRedisUtil, never()).pushBroadcastInfo(anyString(), any(), any());
        verify(broadcastDialogueCompactionService, never()).tryStartCompaction(anyString());
        verify(broadcastWebSocketVoiceSender, never())
                .sendTurnCompleteMetadata(anyString(), anyLong(), any(), anyLong(), anyString(), anyLong());
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

        given(sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation)).willReturn(null);

        // when
        service.handleCompletedTurnAsync(geminiSession, broadcastStreamId, generation, turnNumber, voiceText);

        // then
        verify(broadcastRedisUtil, never()).pushBroadcastInfo(anyString(), any(), any());
        verify(broadcastDialogueCompactionService, never()).tryStartCompaction(anyString());
        verify(broadcastWebSocketVoiceSender, never())
                .sendTurnCompleteMetadata(anyString(), anyLong(), any(), anyLong(), anyString(), anyLong());
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
        service.handleCompletedTurnAsync(geminiSession, broadcastStreamId, generation, turnNumber, voiceText);

        // then
        verify(broadcastRedisUtil, never()).pushBroadcastInfo(anyString(), any(), any());
        verify(broadcastDialogueCompactionService, never()).tryStartCompaction(anyString());
        verify(broadcastWebSocketVoiceSender, never())
                .sendTurnCompleteMetadata(anyString(), anyLong(), any(), anyLong(), anyString(), anyLong());
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
        service.handleCompletedTurnAsync(geminiSession, broadcastStreamId, generation, turnNumber, voiceText);

        // then
        verify(broadcastRedisUtil, never()).pushBroadcastInfo(anyString(), any(), any());
        verify(broadcastDialogueCompactionService, never()).tryStartCompaction(anyString());
        verify(broadcastWebSocketVoiceSender, never())
                .sendTurnCompleteMetadata(anyString(), anyLong(), any(), anyLong(), anyString(), anyLong());
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
        service.forwardStreamingChunk(geminiSession, broadcastStreamId, generation, turnNumber, voiceTextChunk, voiceDataChunk);

        // then
        verify(broadcastWebSocketVoiceSender, times(1))
                .sendVoiceChunkWithMetadata(
                        eq(broadcastStreamId),
                        eq(generation),
                        eq(voiceDataChunk),
                        eq(7L),
                        eq(turnNumber),
                        eq(voiceTextChunk)
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
        service.forwardStreamingChunk(geminiSession, broadcastStreamId, generation, turnNumber, null, null);

        // then
        verify(broadcastWebSocketVoiceSender, never())
                .sendVoiceChunkWithMetadata(anyString(), anyLong(), any(), any(), anyLong(), anyString());
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
        service.forwardStreamingChunk(geminiSession, broadcastStreamId, generation, turnNumber, voiceTextChunk, voiceDataChunk);

        // then
        verify(broadcastWebSocketVoiceSender, never())
                .sendVoiceChunkWithMetadata(anyString(), anyLong(), any(), any(), anyLong(), anyString());
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
        service.forwardStreamingChunk(geminiSession, broadcastStreamId, generation, turnNumber, voiceTextChunk, voiceDataChunk);

        // then
        verify(broadcastWebSocketVoiceSender, never())
                .sendVoiceChunkWithMetadata(anyString(), anyLong(), any(), any(), anyLong(), anyString());
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
        service.forwardStreamingChunk(geminiSession, broadcastStreamId, generation, turnNumber, voiceTextChunk, voiceDataChunk);

        // then
        verify(broadcastWebSocketVoiceSender, never())
                .sendVoiceChunkWithMetadata(anyString(), anyLong(), any(), any(), anyLong(), anyString());
    }
}
