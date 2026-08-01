package com.example.sku_sw.domain.broadcast.service;

import com.example.sku_sw.domain.broadcast.dto.BroadcastCharacterRedisDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastInfoRedisDto;
import com.example.sku_sw.domain.broadcast.enums.AiCharacterTendency;
import com.example.sku_sw.domain.broadcast.enums.BroadcastDataStatus;
import com.example.sku_sw.domain.broadcast.enums.DialogueSubject;
import com.example.sku_sw.domain.broadcast.enums.WebSocketSessionBundleStatus;
import com.example.sku_sw.domain.broadcast.service.gemini.BroadcastGeminiRequestService;
import com.example.sku_sw.domain.broadcast.util.BroadcastRedisUtil;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionBundle;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.socket.WebSocketSession;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BroadcastMessageServiceTest {

    @InjectMocks
    private BroadcastMessageService broadcastMessageService;

    @Mock
    private BroadcastRedisUtil broadcastRedisUtil;

    @Mock
    private BroadcastGeminiRequestService broadcastGeminiRequestService;

    @Mock
    private BroadcastWebSocketSessionRegistry sessionRegistry;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Test
    @DisplayName("resumption 중 trigger 입력 수신 성공 - Redis에 저장하고 Gemini 전송은 보류한다")
    void handleClientMessage_resumption중_trigger입력_저장성공() {
        // given
        String broadcastStreamId = "stream-1";
        Long generation = 1L;
        String message = "하조야 지금 반응해줘";

        WebSocketSession clientSession = org.mockito.Mockito.mock(WebSocketSession.class);
        WebSocketSession geminiSession = org.mockito.Mockito.mock(WebSocketSession.class);
        given(geminiSession.isOpen()).willReturn(true);

        BroadcastWebSocketSessionBundle bundle = BroadcastWebSocketSessionBundle.builder()
                .clientSession(clientSession)
                .generation(generation)
                .status(WebSocketSessionBundleStatus.GEMINI_CONNECTING)
                .build();
        bundle.registerGeminiSession(geminiSession);
        bundle.markResumptionInProgress();

        BroadcastCharacterRedisDto character = BroadcastCharacterRedisDto.builder()
                .characterId(1L)
                .characterName("하조")
                .characterTriggerWords(List.of("하조"))
                .isTalking(false)
                .tendency(AiCharacterTendency.NEUTRAL)
                .build();
        BroadcastInfoRedisDto savedInfo = BroadcastInfoRedisDto.builder()
                .cursorId(11L)
                .subject(DialogueSubject.STREAMER)
                .content(message)
                .createdAt(LocalDateTime.now())
                .dataStatus(BroadcastDataStatus.ACTIVE)
                .sentToGemini(false)
                .build();

        given(sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation)).willReturn(bundle);
        given(broadcastRedisUtil.getBroadcastCharacterDto(broadcastStreamId)).willReturn(character);
        given(broadcastRedisUtil.pushBroadcastInfo(broadcastStreamId, DialogueSubject.STREAMER, message)).willReturn(savedInfo);

        // when
        broadcastMessageService.handleClientMessage(broadcastStreamId, generation, message);

        // then
        verify(broadcastRedisUtil, times(1)).pushBroadcastInfo(broadcastStreamId, DialogueSubject.STREAMER, message);
        verify(broadcastRedisUtil, times(1)).updateBroadcastCharacterIsTalking(broadcastStreamId, true);
        verify(broadcastGeminiRequestService, never()).processFormattedDialogueMessage(any(), anyLong(), any(), any());
        verify(broadcastRedisUtil, never()).markDialoguesSentToGemini(any(), any());
    }

    @Test
    @DisplayName("resumption 완료 후 backlog에 trigger가 있으면 미전송 대화를 Gemini로 전달한다")
    void flushPendingDialoguesAfterResumption_trigger존재_전송성공() {
        // given
        String broadcastStreamId = "stream-1";
        Long generation = 1L;

        WebSocketSession clientSession = org.mockito.Mockito.mock(WebSocketSession.class);
        WebSocketSession geminiSession = org.mockito.Mockito.mock(WebSocketSession.class);
        given(geminiSession.isOpen()).willReturn(true);

        BroadcastWebSocketSessionBundle bundle = BroadcastWebSocketSessionBundle.builder()
                .clientSession(clientSession)
                .generation(generation)
                .status(WebSocketSessionBundleStatus.READY)
                .build();
        bundle.registerGeminiSession(geminiSession);
        bundle.markResumptionInProgress();

        BroadcastCharacterRedisDto character = BroadcastCharacterRedisDto.builder()
                .characterId(1L)
                .characterName("하조")
                .characterTriggerWords(List.of("하조"))
                .isTalking(false)
                .tendency(AiCharacterTendency.NEUTRAL)
                .build();
        List<BroadcastInfoRedisDto> unsentDialogues = List.of(
                BroadcastInfoRedisDto.builder()
                        .cursorId(1L)
                        .subject(DialogueSubject.VIEWER)
                        .content("(시청자 테스트)채팅")
                        .createdAt(LocalDateTime.now())
                        .dataStatus(BroadcastDataStatus.ACTIVE)
                        .sentToGemini(false)
                        .build(),
                BroadcastInfoRedisDto.builder()
                        .cursorId(2L)
                        .subject(DialogueSubject.STREAMER)
                        .content("하조야 반응해줘")
                        .createdAt(LocalDateTime.now())
                        .dataStatus(BroadcastDataStatus.ACTIVE)
                        .sentToGemini(false)
                        .build()
        );

        given(sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation)).willReturn(bundle);
        given(broadcastRedisUtil.getBroadcastCharacterDto(broadcastStreamId)).willReturn(character);
        given(broadcastRedisUtil.getUnsentDialogues(broadcastStreamId)).willReturn(unsentDialogues);

        // when
        broadcastMessageService.flushPendingDialoguesAfterResumption(
                broadcastStreamId,
                generation,
                "SUPPRESSED_TURN_COMPLETE"
        );

        // then
        verify(broadcastRedisUtil, times(1)).updateBroadcastCharacterIsTalking(broadcastStreamId, true);
        verify(broadcastGeminiRequestService, times(1)).processFormattedDialogueMessage(
                eq(broadcastStreamId),
                eq(generation),
                eq(character),
                eq("(시청자 테스트)채팅\n(스트리머)하조야 반응해줘")
        );
        verify(broadcastRedisUtil, times(1)).markDialoguesSentToGemini(broadcastStreamId, List.of(1L, 2L));
    }

    @Test
    @DisplayName("resumption 완료 후 trigger도 talking 상태도 없으면 backlog를 그대로 유지한다")
    void flushPendingDialoguesAfterResumption_trigger없음_보류성공() {
        // given
        String broadcastStreamId = "stream-1";
        Long generation = 1L;

        WebSocketSession clientSession = org.mockito.Mockito.mock(WebSocketSession.class);
        WebSocketSession geminiSession = org.mockito.Mockito.mock(WebSocketSession.class);

        BroadcastWebSocketSessionBundle bundle = BroadcastWebSocketSessionBundle.builder()
                .clientSession(clientSession)
                .generation(generation)
                .status(WebSocketSessionBundleStatus.READY)
                .build();
        bundle.registerGeminiSession(geminiSession);
        bundle.markResumptionInProgress();

        BroadcastCharacterRedisDto character = BroadcastCharacterRedisDto.builder()
                .characterId(1L)
                .characterName("하조")
                .characterTriggerWords(List.of("하조"))
                .isTalking(false)
                .tendency(AiCharacterTendency.NEUTRAL)
                .build();
        List<BroadcastInfoRedisDto> unsentDialogues = List.of(
                BroadcastInfoRedisDto.builder()
                        .cursorId(3L)
                        .subject(DialogueSubject.STREAMER)
                        .content("이번 판은 천천히 하자")
                        .createdAt(LocalDateTime.now())
                        .dataStatus(BroadcastDataStatus.ACTIVE)
                        .sentToGemini(false)
                        .build()
        );

        given(sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation)).willReturn(bundle);
        given(broadcastRedisUtil.getBroadcastCharacterDto(broadcastStreamId)).willReturn(character);
        given(broadcastRedisUtil.getUnsentDialogues(broadcastStreamId)).willReturn(unsentDialogues);

        // when
        broadcastMessageService.flushPendingDialoguesAfterResumption(
                broadcastStreamId,
                generation,
                "FIRST_RESUMPTION_EVENT_TIMEOUT"
        );

        // then
        verify(broadcastGeminiRequestService, never()).processFormattedDialogueMessage(any(), anyLong(), any(), any());
        verify(broadcastRedisUtil, never()).markDialoguesSentToGemini(any(), any());
    }
}
