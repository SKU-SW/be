package com.example.sku_sw.domain.broadcast.service.gemini;

import com.example.sku_sw.domain.broadcast.dto.BroadcastInfoRedisDto;
import com.example.sku_sw.domain.broadcast.enums.BroadcastCompactionTriggerType;
import com.example.sku_sw.domain.broadcast.enums.BroadcastGeminiRefreshTriggerType;
import com.example.sku_sw.domain.broadcast.enums.DialogueSubject;
import com.example.sku_sw.domain.broadcast.enums.WebSocketAttributes;
import com.example.sku_sw.domain.broadcast.event.BroadcastCompactionCheckRequestedEvent;
import com.example.sku_sw.domain.broadcast.event.BroadcastGeminiRefreshRequestedEvent;
import com.example.sku_sw.domain.broadcast.util.BroadcastRedisUtil;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionBundle;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionRegistry;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketVoiceSender;
import com.example.sku_sw.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

/**
 * Gemini 응답 스트리밍 및 완료 후처리 서비스
 * - 청크 수신 시 클라이언트에게 즉시 전달한다.
 * - turn 완료 시 Redis 저장과 완료 메타데이터 전송을 수행한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BroadcastGeminiResponseService {

    private final BroadcastRedisUtil broadcastRedisUtil;
    private final BroadcastWebSocketSessionRegistry sessionRegistry;
    private final BroadcastWebSocketVoiceSender broadcastWebSocketVoiceSender;
    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * Gemini 응답 청크를 현재 클라이언트에게 즉시 전달한다.
     *
     * @param geminiSession : 응답을 전달한 Gemini WebSocket 세션
     * @param broadcastStreamId : 방송 스트림 ID
     * @param generation : 현재 세션 generation
     * @param turnNumber : Gemini 응답 turn 번호
     * @param voiceTextChunk : 이번 payload에서 수신한 텍스트 청크
     * @param voiceDataChunk : 이번 payload에서 수신한 오디오 청크
     */
    public void forwardStreamingChunk(
            WebSocketSession geminiSession,
            String broadcastStreamId,
            Long generation,
            Long turnNumber,
            String voiceTextChunk,
            byte[] voiceDataChunk
    ) {
        log.debug("[BroadcastGeminiResponseService] forwardStreamingChunk() - START | streamId: {}, generation: {}, turnNumber: {}",
                broadcastStreamId, generation, turnNumber);

        if ((voiceTextChunk == null || voiceTextChunk.isBlank()) && (voiceDataChunk == null || voiceDataChunk.length == 0)) {
            log.debug("[BroadcastGeminiResponseService] forwardStreamingChunk() - Empty chunk skipped | streamId: {}, generation: {}, turnNumber: {}",
                    broadcastStreamId, generation, turnNumber);
            return;
        }

        BroadcastWebSocketSessionBundle bundle = getValidatedBundle(geminiSession, broadcastStreamId, generation, "forwardStreamingChunk");
        if (bundle == null) {
            return;
        }

        Long characterId = resolveCharacterId(bundle);

        try {
            broadcastWebSocketVoiceSender.sendVoiceChunkWithMetadata(
                    broadcastStreamId,
                    generation,
                    voiceDataChunk,
                    characterId,
                    turnNumber,
                    voiceTextChunk
            );
            log.debug("[BroadcastGeminiResponseService] forwardStreamingChunk() - END | streamId: {}, generation: {}, turnNumber: {}",
                    broadcastStreamId, generation, turnNumber);
        } catch (CustomException e) {
            log.warn("[BroadcastGeminiResponseService] forwardStreamingChunk() - Chunk send failed | streamId: {}, generation: {}, turnNumber: {}, error: {}",
                    broadcastStreamId, generation, turnNumber, e.getMessage());
        }
    }

    /**
     * Gemini 응답에 turnComplete 필드가 돴을 때, 클라이언트에게 메타데이터를 보내는 완료 응답을 비동기로 처리한다.
     * - 현재 Gemini 세션과 일치하는 세션 번들을 검증한 뒤 Redis 저장과 완료 메타데이터 전송을 수행한다.
     * - 누적 텍스트가 비어 있으면 응답을 무시한다.
     *
     * @param geminiSession : 완료된 응답을 전달한 Gemini WebSocket 세션
     * @param broadcastStreamId : 방송 스트림 ID
     * @param generation : 현재 세션 generation
     * @param turnNumber : Gemini 응답 turn 번호
     * @param voiceText : Gemini 응답 누적 텍스트
     * @param requestOwnerBundle : 요청을 발사했던 세션 번들
     */
    @Async("geminiTurnCompletionExecutor")
    public void handleCompletedTurnAsync(
            WebSocketSession geminiSession,
            String broadcastStreamId,
            Long generation,
            Long turnNumber,
            String voiceText,
            BroadcastWebSocketSessionBundle requestOwnerBundle
    ) {
        log.info("[BroadcastGeminiResponseService] handleCompletedTurnAsync() - START | streamId: {}, generation: {}, turnNumber: {}",
                broadcastStreamId, generation, turnNumber);
        Long savedCursorId = null;
        try {
            BroadcastWebSocketSessionBundle bundle = getValidatedBundle(geminiSession, broadcastStreamId, generation, "handleCompletedTurnAsync");
            if (bundle == null) {
                log.info("[BroadcastGeminiResponseService] handleCompletedTurnAsync() - Stale completion skipped, cleanup continues | streamId: {}, generation: {}, turnNumber: {}",
                        broadcastStreamId, generation, turnNumber);
                return;
            }

            if (voiceText == null || voiceText.isBlank()) {
                log.info("[BroadcastGeminiResponseService] handleCompletedTurnAsync() - Empty Gemini response, skipped | streamId: {}, generation: {}, turnNumber: {}",
                        broadcastStreamId, generation, turnNumber);
                return;
            }
            // 1. Gemini 응답값을 Redis BroadcastInfo List에 저장한다. 이후 Redis의 방송 데이터 요약 점검 이벤트를 발행한다.
            BroadcastInfoRedisDto savedAiInfo = broadcastRedisUtil.pushBroadcastInfo(
                    broadcastStreamId,
                    DialogueSubject.AI_CHARACTER,
                    voiceText
            );
            savedCursorId = savedAiInfo.cursorId();
            applicationEventPublisher.publishEvent(BroadcastCompactionCheckRequestedEvent.builder()
                    .broadcastStreamId(broadcastStreamId)
                    .triggerType(BroadcastCompactionTriggerType.AI_DIALOGUE_STORED)
                    .build());

            // 2. 응답 데이터를 메타데이터와 함께 Client Session으로 전송한다.
            Long characterId = resolveCharacterId(bundle);
            try {
                broadcastWebSocketVoiceSender.sendTurnCompleteMetadata(
                        broadcastStreamId,
                        generation,
                        characterId,
                        turnNumber,
                        voiceText,
                        savedAiInfo.cursorId()
                );
            } catch (CustomException e) {
                log.warn("[BroadcastGeminiResponseService] handleCompletedTurnAsync() - Completion metadata send failed | streamId: {}, generation: {}, turnNumber: {}, error: {}",
                        broadcastStreamId, generation, turnNumber, e.getMessage());
            }
        } finally {
            // 3. Gemini 응답이 오고 난 후의 작업을 수행한다. (request-flight값 감소, Gemini Session refresh 등)
            handleGeminiTurnFinished(broadcastStreamId, generation, requestOwnerBundle);
        }

        log.info("[BroadcastGeminiResponseService] handleCompletedTurnAsync() - END | streamId: {}, generation: {}, turnNumber: {}, cursorId: {}",
                broadcastStreamId, generation, turnNumber, savedCursorId);
    }

    /**
     * Gemini turn 종료 후 request-flight 감소 및 refresh 시작 여부를 확인한다.
     * @param broadcastStreamId : 방송 스트림 ID
     * @param generation : 현재 세션 generation
     * @param bundle : 현재 세션 번들
     */
    public void handleGeminiTurnFinished(String broadcastStreamId, Long generation, BroadcastWebSocketSessionBundle bundle) {
        log.info("[BroadcastGeminiResponseService] handleGeminiTurnFinished() - START | streamId: {}, generation: {}",
                broadcastStreamId, generation);

        if (bundle == null) {
            log.warn("[BroadcastGeminiResponseService] handleGeminiTurnFinished() - Bundle not found | streamId: {}, generation: {}",
                    broadcastStreamId, generation);
            return;
        }

        /*
            1. Gemini request-flight 요청 수를 감소시킨다.
            - 응답 텍스트 유무와 관계없이 turn 종료 시점에는 반드시 감소시킨다.
         */
        int previousInFlight = bundle.getRequestFlightCountValue();
        int remainingInFlight = bundle.decrementRequestFlight();
        log.info("[BroadcastGeminiResponseService] handleGeminiTurnFinished() - Request-flight decremented | streamId: {}, generation: {}, previousInFlight: {}, remainingInFlight: {}, refreshRequested: {}",
                broadcastStreamId, generation, previousInFlight, remainingInFlight, bundle.getGeminiSessionRefreshRequested());

        /*
            2. refresh 요청이 있고 더 이상 request-flight 요청이 없으면 refresh 이벤트를 발행한다.
            - summary 반영 이후 refreshRequested=true 상태인 경우에만 coordinator 재평가 이벤트를 발행한다.
         */
        if (remainingInFlight == 0 && bundle.getGeminiSessionRefreshRequested()) {
            log.info("[BroadcastGeminiResponseService] handleGeminiTurnFinished() - Publishing refresh event | streamId: {}, generation: {}",
                    broadcastStreamId, generation);
            applicationEventPublisher.publishEvent(BroadcastGeminiRefreshRequestedEvent.builder()
                    .broadcastStreamId(broadcastStreamId)
                    .generation(generation)
                    .triggerType(BroadcastGeminiRefreshTriggerType.TURN_FINISHED)
                    .build());
        }

        log.info("[BroadcastGeminiResponseService] handleGeminiTurnFinished() - END | streamId: {}, generation: {}, remainingInFlight: {}",
                broadcastStreamId, generation, remainingInFlight);
    }

    private BroadcastWebSocketSessionBundle getValidatedBundle(
            WebSocketSession geminiSession,
            String broadcastStreamId,
            Long generation,
            String methodName
    ) {
        BroadcastWebSocketSessionBundle bundle = sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation);
        if (bundle == null || !bundle.canProcessGeminiResponse()) {
            log.warn("[BroadcastGeminiResponseService] {}() - Bundle not ready, drop response | streamId: {}, generation: {}",
                    methodName, broadcastStreamId, generation);
            return null;
        }

        if (bundle.getGeminiSession() != geminiSession) {
            log.warn("[BroadcastGeminiResponseService] {}() - Gemini session mismatch, drop response | streamId: {}, generation: {}",
                    methodName, broadcastStreamId, generation);
            return null;
        }

        return bundle;
    }

    private Long resolveCharacterId(BroadcastWebSocketSessionBundle bundle) {
        Object characterIdAttribute = bundle.getClientSession().getAttributes().get(WebSocketAttributes.CHARACTER_ID.getValue());
        if (characterIdAttribute == null) {
            return null;
        }

        if (characterIdAttribute instanceof Number number) {
            return number.longValue();
        }

        return Long.parseLong(characterIdAttribute.toString());
    }
}
