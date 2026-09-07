package com.example.sku_sw.domain.broadcast.service;

import com.example.sku_sw.domain.broadcast.dto.BroadcastCharacterRedisDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastInfoRedisDto;
import com.example.sku_sw.domain.broadcast.enums.BroadcastCompactionTriggerType;
import com.example.sku_sw.domain.broadcast.enums.BroadcastErrorCode;
import com.example.sku_sw.domain.broadcast.enums.DialogueSubject;
import com.example.sku_sw.domain.broadcast.event.BroadcastCompactionCheckRequestedEvent;
import com.example.sku_sw.domain.broadcast.service.gemini.BroadcastGeminiRequestService;
import com.example.sku_sw.domain.broadcast.util.BroadcastRedisUtil;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionBundle;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionRegistry;
import com.example.sku_sw.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * WebSocket을 통해 수신한 클라이언트 텍스트 메시지를 처리하는 서비스
 * - 메시지 정규화, 트리거 워드 검사, BroadcastInfo 저장 및 Gemini AI 호출을 담당한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BroadcastMessageService {

    private final BroadcastRedisUtil broadcastRedisUtil;
    private final BroadcastGeminiRequestService broadcastGeminiRequestService;
    private final BroadcastWebSocketSessionRegistry sessionRegistry;
    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * 클라이언트 텍스트 메시지를 처리한다.
     * - Redis에서 캐릭터 정보를 조회하고, USER 메시지를 BroadcastInfo에 저장한다.
     * - trigger word 및 isTalking 상태에 따라 Gemini 호출 여부를 결정한다.
     * - resumption/refresh 중에도 입력을 Redis에 적재해 유실을 방지한다.
     *
     * @param broadcastStreamId : 방송 스트림 ID
     * @param generation        : 현재 세션 generation
     * @param message           : 클라이언트가 보낸 텍스트 메시지
     */
    public void handleClientMessage(String broadcastStreamId, Long generation, String message) {
        log.info("[BroadcastMessageService] handleClientMessage() - START | streamId: {}, generation: {}, message: {}",
                broadcastStreamId, generation, message);

        /*
            1. 현재 generation의 Session Bundle이 존재하고 클라이언트 입력 수신 가능한 상태인지 확인한다.
            - bundle이 없거나 입력 수신 불가 상태면 예외를 발생시킨다.
         */
        BroadcastWebSocketSessionBundle bundle = sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation);
        if (bundle == null || !bundle.canAcceptClientMessage()) {
            throw new CustomException(BroadcastErrorCode.WEBSOCKET_SESSION_NOT_READY);
        }

        synchronized (bundle) {
            /*
                2. 동기화 구간에서 현재 bundle이 여전히 유효한지 재검증한다.
                - resumption 완료 후 backlog flush와 일반 입력 저장/전송이 같은 락을 사용하도록 맞춘다.
             */
            BroadcastWebSocketSessionBundle currentBundle = sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation);
            if (currentBundle == null || !currentBundle.canAcceptClientMessage()) {
                throw new CustomException(BroadcastErrorCode.WEBSOCKET_SESSION_NOT_READY);
            }

            /*
                3. 캐릭터 성향(tendency)을 반영한 스트리머 메시지를 만든다.
             */
            BroadcastCharacterRedisDto character = broadcastRedisUtil.getBroadcastCharacterDto(broadcastStreamId);
            String updatedMessage = applyCharacterTendency(message, character);

            /*
                4. 스트리머 입력을 Redis에 우선 저장하고 compaction 검사를 요청한다.
                - resumption 중에도 sentToGemini=false 상태로 적재해 유실을 막는다.
             */
            BroadcastInfoRedisDto savedUserInfo = broadcastRedisUtil.pushBroadcastInfo(
                    broadcastStreamId,
                    DialogueSubject.STREAMER,
                    updatedMessage
            );
            log.info("[BroadcastMessageService] handleClientMessage() - Client message saved | streamId: {}, cursorId: {}, clientMessage: {}",
                    broadcastStreamId, savedUserInfo.cursorId(), updatedMessage);
            applicationEventPublisher.publishEvent(BroadcastCompactionCheckRequestedEvent.builder()
                    .broadcastStreamId(broadcastStreamId)
                    .triggerType(BroadcastCompactionTriggerType.CLIENT_MESSAGE_STORED)
                    .build());

            /*
                5. 현재 입력 기준 trigger word를 확인한다.
                - trigger가 감지되면 talking 상태를 true로 올려 이후 backlog flush에서도 동일 정책을 적용한다.
             */
            boolean hasTriggerWord = hasTriggerWord(message, character);
            boolean isTalking = Boolean.TRUE.equals(character.getIsTalking());

            if (!hasTriggerWord && !isTalking) {
                log.info("[BroadcastMessageService] handleClientMessage() - No trigger word and not talking, skipping | streamId: {}",
                        broadcastStreamId);
                log.info("[BroadcastMessageService] handleClientMessage() - END | streamId: {}, action: skip", broadcastStreamId);
                return;
            }

            if (hasTriggerWord) {
                log.info("[BroadcastMessageService] handleClientMessage() - Trigger word detected, activating AI | streamId: {}",
                        broadcastStreamId);
                broadcastRedisUtil.updateBroadcastCharacterIsTalking(broadcastStreamId, true);
                character.setIsTalking(true);
            }

            /*
                6. 현재 Gemini 전송이 불가능한 상황이면 Redis에만 저장하고 종료한다.
                - first resumption control event 완료 전에는 backlog를 그대로 유지한다.
             */
            if (!canSendPendingDialoguesNow(currentBundle)) {
                log.info("[BroadcastMessageService] handleClientMessage() - Gemini send blocked, saved only | streamId: {}, generation: {}, status: {}, resumptionInProgress: {}",
                        broadcastStreamId, generation, currentBundle.getStatus(), currentBundle.getGeminiSessionResumptionInProgress());
                log.info("[BroadcastMessageService] handleClientMessage() - END | streamId: {}, action: saved_only", broadcastStreamId);
                return;
            }

            /*
                7. 현재 전송 가능한 상태이면 Redis backlog 전체를 Gemini로 전송한다.
                - viewer/chat backlog가 함께 쌓여 있었다면 cursor 순서대로 같이 전달된다.
             */
            sendPendingDialoguesToGemini(broadcastStreamId, generation, character);
        }

        log.info("[BroadcastMessageService] handleClientMessage() - END | streamId: {}, action: gemini_called", broadcastStreamId);
    }

    /**
     * Gemini resumption 완료 후 Redis에 적재된 backlog를 재전송한다.
     * - first resumption control event가 끝난 뒤에만 호출된다.
     * - trigger 정책을 유지해 trigger가 없고 talking 상태도 아니면 backlog를 그대로 둔다.
     *
     * @param broadcastStreamId : 방송 스트림 ID
     * @param generation        : 현재 세션 generation
     * @param reason            : resumption 완료 사유
     */
    public void flushPendingDialoguesAfterResumption(String broadcastStreamId, Long generation, String reason) {
        log.info("[BroadcastMessageService] flushPendingDialoguesAfterResumption() - START | streamId: {}, generation: {}, reason: {}",
                broadcastStreamId, generation, reason);

        BroadcastWebSocketSessionBundle bundle = sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation);
        if (bundle == null) {
            log.info("[BroadcastMessageService] flushPendingDialoguesAfterResumption() - END | streamId: {}, action: bundle_not_found",
                    broadcastStreamId);
            return;
        }

        synchronized (bundle) {
            /*
                1. resumption 완료 이벤트가 stale event인지 확인한다.
             */
            BroadcastWebSocketSessionBundle currentBundle = sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation);
            if (currentBundle == null) {
                log.info("[BroadcastMessageService] flushPendingDialoguesAfterResumption() - END | streamId: {}, action: stale_bundle",
                        broadcastStreamId);
                return;
            }

            BroadcastCharacterRedisDto character = broadcastRedisUtil.getBroadcastCharacterDto(broadcastStreamId);
            List<BroadcastInfoRedisDto> unsentDialogues = broadcastRedisUtil.getUnsentDialogues(broadcastStreamId);

            /*
                2. resumption 상태를 먼저 해제한다.
                - 이후 같은 bundle 락 안에서 backlog flush 여부를 판단해 중복 전송 경쟁을 막는다.
             */
            currentBundle.clearResumptionInProgress();

            if (unsentDialogues.isEmpty()) {
                log.info("[BroadcastMessageService] flushPendingDialoguesAfterResumption() - END | streamId: {}, action: no_unsent_dialogues",
                        broadcastStreamId);
                return;
            }

            /*
                3. backlog 안의 STREAMER 발화 기준으로 trigger word를 다시 확인한다.
                - trigger가 하나라도 있으면 talking을 활성화하고 backlog 전체를 보낸다.
             */
            boolean hasTriggerWord = hasTriggerWordInDialogues(unsentDialogues, character);
            boolean isTalking = Boolean.TRUE.equals(character.getIsTalking());
            if (hasTriggerWord) {
                broadcastRedisUtil.updateBroadcastCharacterIsTalking(broadcastStreamId, true);
                character.setIsTalking(true);
                isTalking = true;
            }

            if (!hasTriggerWord && !isTalking) {
                log.info("[BroadcastMessageService] flushPendingDialoguesAfterResumption() - END | streamId: {}, action: pending_retained_without_trigger",
                        broadcastStreamId);
                return;
            }

            /*
                4. resumption 해제 직후에도 Gemini 전송 가능 상태인지 다시 확인한다.
                - socket close/refresh가 다시 겹친 경우에는 backlog를 Redis에 그대로 남긴다.
             */
            if (!canSendPendingDialoguesNow(currentBundle)) {
                log.info("[BroadcastMessageService] flushPendingDialoguesAfterResumption() - END | streamId: {}, action: gemini_unavailable_after_resumption",
                        broadcastStreamId);
                return;
            }

            sendPendingDialoguesToGemini(broadcastStreamId, generation, character);
        }

        log.info("[BroadcastMessageService] flushPendingDialoguesAfterResumption() - END | streamId: {}, action: flushed",
                broadcastStreamId);
    }

    /**
     * Gemini로 아직 전송하지 않은 방송 대화들을 조회해 하나의 payload로 결합 후 전송한다.
     * - Redis의 미전송 대화들을 조회한다.
     * - 각 대화를 Gemini 입력 형식으로 변환한 뒤 줄바꿈으로 결합한다.
     * - Gemini 전송이 성공한 경우에만 sentToGemini를 true로 마킹한다.
     * @param broadcastStreamId : 방송 스트림 ID
     * @param generation : 현재 세션 generation
     * @param character : 방송 캐릭터 정보
     */
    private void sendPendingDialoguesToGemini(
            String broadcastStreamId,
            Long generation,
            BroadcastCharacterRedisDto character
    ) {
        log.info("[BroadcastMessageService] sendPendingDialoguesToGemini() - START | streamId: {}, generation: {}",
                broadcastStreamId, generation);

        /*
            1. Redis에서 미전송 대화를 조회한다.
         */
        List<BroadcastInfoRedisDto> unsentDialogues = broadcastRedisUtil.getUnsentDialogues(broadcastStreamId);
        if (unsentDialogues.isEmpty()) {
            log.info("[BroadcastMessageService] sendPendingDialoguesToGemini() - END | streamId: {}, action: no_unsent_dialogues",
                    broadcastStreamId);
            return;
        }

        /*
            2. 미전송 대화들을 Gemini 입력 payload로 결합한다.
         */
        String combinedMessage = buildGeminiDialoguePayload(unsentDialogues);
        log.info("[BroadcastMessageService] sendPendingDialoguesToGemini() - Combined dialogues | streamId: {}, unsentCount: {}",
                broadcastStreamId, unsentDialogues.size());

        /*
            3. 결합한 payload를 Gemini로 전송한다.
         */
        broadcastGeminiRequestService.processFormattedDialogueMessage(
                broadcastStreamId,
                generation,
                character,
                combinedMessage
        );

        /*
            4. 전송 성공 후에만 sentToGemini=true로 마킹한다.
         */
        List<Long> unsentCursorIds = unsentDialogues.stream()
                .map(BroadcastInfoRedisDto::cursorId)
                .toList();
        broadcastRedisUtil.markDialoguesSentToGemini(broadcastStreamId, unsentCursorIds);

        log.info("[BroadcastMessageService] sendPendingDialoguesToGemini() - END | streamId: {}, sentCount: {}",
                broadcastStreamId, unsentCursorIds.size());
    }

    /**
     * 미전송 대화 목록을 Gemini realtimeInput.text payload 문자열로 결합한다.
     * @param dialogues : 미전송 대화 목록
     * @return : 줄바꿈으로 결합한 Gemini 입력 문자열
     */
    private String buildGeminiDialoguePayload(List<BroadcastInfoRedisDto> dialogues) {
        log.info("[BroadcastMessageService] buildGeminiDialoguePayload() - START | dialogueCount: {}", dialogues.size());

        /*
            1. 각 대화를 Gemini 입력 형식으로 변환한 뒤 줄바꿈으로 결합한다.
         */
        String result = dialogues.stream()
                .map(this::formatDialogueForGemini)
                .collect(Collectors.joining("\n"));

        log.info("[BroadcastMessageService] buildGeminiDialoguePayload() - END | payloadLength: {}", result.length());
        return result;
    }

    /**
     * Redis 대화를 Gemini 입력용 단일 문자열로 변환한다.
     * - STREAMER는 "(스트리머)" 접두어를 붙인다.
     * - VIEWER 및 기타 대화는 저장된 문자열을 그대로 사용한다.
     * @param dialogue : Redis 대화 DTO
     * @return : Gemini 입력용 단일 문자열
     */
    private String formatDialogueForGemini(BroadcastInfoRedisDto dialogue) {
        log.info("[BroadcastMessageService] formatDialogueForGemini() - START | cursorId: {}, subject: {}",
                dialogue.cursorId(), dialogue.subject());

        /*
            1. subject에 따라 Gemini 입력 문자열을 구성한다.
         */
        String result = dialogue.subject() == DialogueSubject.STREAMER
                ? "(스트리머)" + dialogue.content()
                : dialogue.content();

        log.info("[BroadcastMessageService] formatDialogueForGemini() - END | cursorId: {}", dialogue.cursorId());
        return result;
    }

    /**
     * 메시지 정규화 유틸리티
     * - null-safe: null 입력 시 빈 문자열 반환
     * - trim: 앞뒤 공백 제거
     * - 모든 whitespace(\s+) 제거
     * - 영어 lower-case (Locale.ROOT)
     *
     * @param text : 정규화할 문자열
     * @return : 정규화된 문자열
     */
    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private String applyCharacterTendency(String message, BroadcastCharacterRedisDto character) {
        return switch (character.getTendency()) {
            case POSITIVE -> message + "(긍정적인 방향으로 답변)";
            case NEGATIVE -> message + "(부정적인 방향으로 답변)";
            case NEUTRAL -> message;
        };
    }

    private boolean hasTriggerWord(String message, BroadcastCharacterRedisDto character) {
        String normalizedMessage = normalize(message);
        if (character.getCharacterTriggerWords() == null) {
            return false;
        }
        return character.getCharacterTriggerWords().stream()
                .anyMatch(trigger -> normalizedMessage.contains(normalize(trigger)));
    }

    private boolean hasTriggerWordInDialogues(
            List<BroadcastInfoRedisDto> dialogues,
            BroadcastCharacterRedisDto character
    ) {
        return dialogues.stream()
                .filter(dialogue -> dialogue.subject() == DialogueSubject.STREAMER)
                .map(BroadcastInfoRedisDto::content)
                .anyMatch(content -> hasTriggerWord(content, character));
    }

    private boolean canSendPendingDialoguesNow(BroadcastWebSocketSessionBundle bundle) {
        return bundle.isGeminiSessionOpen()
                && !bundle.isGeminiSessionRefreshRequested()
                && bundle.isWebSocketSessionBundleReady()
                && !bundle.getGeminiSessionResumptionInProgress();
    }
}
