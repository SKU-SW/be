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
 * - 메시지 정규화, 트리거 워드 검사, BroadcastInfo 저장, Gemini AI 호출을 담당한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BroadcastMessageService {

    private static final int RECENT_BROADCAST_INFO_LIMIT = 50;

    private final BroadcastRedisUtil broadcastRedisUtil;
    private final BroadcastGeminiRequestService broadcastGeminiRequestService;
    private final BroadcastWebSocketSessionRegistry sessionRegistry;
    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * 클라이언트 텍스트 메시지를 처리한다.
     * - Redis에서 캐릭터 정보를 조회하고, USER 메시지를 BroadcastInfo에 저장한다.
     * - 트리거 워드 포함 여부와 isTalking 상태에 따라 Gemini 호출 또는 종료를 결정한다.
     * - Gemini Live WebSocket 세션으로 메시지를 전송한다.
     *
     * @param broadcastStreamId : 방송 스트림 ID
     * @param generation        : 현재 세션 generation
     * @param message           : 클라이언트가 보낸 텍스트 메시지
     */
    public void handleClientMessage(String broadcastStreamId, Long generation, String message) {
        log.info("[BroadcastMessageService] handleClientMessage() - START | streamId: {}, generation: {}, message: {}",
                broadcastStreamId, generation, message);
        /*
            1. Session Registry에서 broadcastStreamId와 generation 값으로 WebSocket Session Bundle값이 있는지 확인한다.
            - Bundle이 없다면 예외를 발생시킨다.
            - Bundle이 클라이언트 메시지를 받을 수 있는 상황인지 확인(status가 Ready이거나 Refreshing인 경우)
         */
        BroadcastWebSocketSessionBundle bundle = sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation);
        if (bundle == null || !bundle.canAcceptClientMessage()) {
            throw new CustomException(BroadcastErrorCode.WEBSOCKET_SESSION_NOT_READY);
        }

        /*
            2. Session Registry에 해당 방송의 Bundle이 존재한다면, 아래 작업을 수행한다.
                1) Redis에 저장되어있는 현재 방송을 진행 중인 AI 캐릭터의 정보를 가져온다.
                2) 클라이언트로부터 받은 텍스트 데이터를 Redis의 현재 방송 진행 정보 List에 저장한다.
                3) Redis에 있는 현재 방송 진행 정보의 Compact(요약)을 시도한다.
         */
        BroadcastCharacterRedisDto character = broadcastRedisUtil.getBroadcastCharacterDto(broadcastStreamId);
        BroadcastInfoRedisDto savedUserInfo = broadcastRedisUtil.pushBroadcastInfo(broadcastStreamId, DialogueSubject.STREAMER, message);
        log.info("[BroadcastMessageService] handleClientMessage() - Client message saved | streamId: {}, cursorId: {}, clientMessage: {}",
                broadcastStreamId, savedUserInfo.cursorId(), message);
        applicationEventPublisher.publishEvent(BroadcastCompactionCheckRequestedEvent.builder()
                .broadcastStreamId(broadcastStreamId)
                .triggerType(BroadcastCompactionTriggerType.CLIENT_MESSAGE_STORED)
                .build());

        /*
            3. 클라이언트 메시지를 정규화한 뒤, 메시지에 AI 캐릭터의 트리거 메시지가 있는지 확인한다.
            - 메시지에 트리거 메시지가 없는 경우, 아무런 동작 하지 않고 종료
            - 메시지에 트리거 메시지가 있는 경우, 해당 캐릭터가 말하고 있다고 설정값 변경. 이후 Gemini WebSocket을 통해 요청을 보낸다.
         */
        String normalizedMessage = normalize(message);
        boolean hasTriggerWord = false;
        if (character.getCharacterTriggerWords() != null) {
            hasTriggerWord = character.getCharacterTriggerWords().stream()
                    .anyMatch(trigger -> normalizedMessage.contains(normalize(trigger)));
        }
        Boolean isTalking = character.getIsTalking();

        /*
            4. Gemini Session으로 메시지를 보내기 위한 검증
            - 입력된 데이터에 Trigger Word가 없는데 isTalking 상태가 아닌 경우
            - Gemini Seesion이 열려있지 않은 경우
            - Gemini Session이 Refresh 요청 중인 상태인 경우
            - WebSocket Session Bundle이 준비되어있지 않은 경우
            를 제외한다.
         */
        if (!hasTriggerWord && (isTalking == null || !isTalking)) {
            log.info("[BroadcastMessageService] handleClientMessage() - No trigger word and not talking, skipping | streamId: {}", broadcastStreamId);
            log.info("[BroadcastMessageService] handleClientMessage() - END | streamId: {}, action: skip", broadcastStreamId);
            return;
        }

        if (!bundle.isGeminiSessionOpen()) {
            log.info("[BroadcastMessageService] handleClientMessage() - Gemini Session is No Open | streamId: {}, generation: {}",
                    broadcastStreamId, generation);
            log.info("[BroadcastMessageService] handleClientMessage() - END | streamId: {}, action: saved_only", broadcastStreamId);
            return;
        }

        if (bundle.isGeminiSessionRefreshRequested()) {
            log.info("[BroadcastMessageService] handleClientMessage() - Gemini send blocked during refresh | streamId: {}, generation: {}",
                    broadcastStreamId, generation);
            log.info("[BroadcastMessageService] handleClientMessage() - END | streamId: {}, action: saved_only", broadcastStreamId);
            return;
        }

        if (!bundle.isWebSocketSessionBundleReady()) {
            log.info("[BroadcastMessageService] handleClientMessage() - Gemini Session Bundle is Not Ready | streamId: {}, generation: {}",
                    broadcastStreamId, generation);
            log.info("[BroadcastMessageService] handleClientMessage() - END | streamId: {}, action: saved_only", broadcastStreamId);
            return;
        }

        /*
            5. Trigger Word가 감지된 경우, Redis에 해당 AI 캐릭터가 talking 중인 것으로 설정 및 데이터 Gemini로 전송  
         */
        if (hasTriggerWord) {
            log.info("[BroadcastMessageService] handleClientMessage() - Trigger word detected, activating AI | streamId: {}", broadcastStreamId);
            broadcastRedisUtil.updateBroadcastCharacterIsTalking(broadcastStreamId, true);
        }
        sendPendingDialoguesToGemini(broadcastStreamId, generation, character);

        log.info("[BroadcastMessageService] handleClientMessage() - END | streamId: {}, action: gemini_called", broadcastStreamId);
    }

    /**
     * Gemini로 아직 전송되지 않은 방송 대화들을 조회해 하나의 payload로 결합 전송한다.
     * - Redis의 미전송 대화들을 조회한다.
     * - 각 대화를 Gemini 입력 포맷으로 변환한 뒤 줄바꿈으로 결합한다.
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
            - 현재 방금 저장된 스트리머 메시지와 이전 viewer/chat 누적분을 함께 조회한다.
         */
        List<BroadcastInfoRedisDto> unsentDialogues = broadcastRedisUtil.getUnsentDialogues(broadcastStreamId);
        if (unsentDialogues.isEmpty()) {
            log.info("[BroadcastMessageService] sendPendingDialoguesToGemini() - END | streamId: {}, action: no_unsent_dialogues",
                    broadcastStreamId);
            return;
        }

        /*
            2. 미전송 대화들을 Gemini 입력용 payload로 결합한다.
            - STREAMER는 (스트리머) 접두어를 붙인다.
            - VIEWER는 저장된 문자열을 그대로 사용한다.
         */
        String combinedMessage = buildGeminiDialoguePayload(unsentDialogues);
        log.info("[BroadcastMessageService] sendPendingDialoguesToGemini() - Combined dialogues | streamId: {}, unsentCount: {}",
                broadcastStreamId, unsentDialogues.size());

        /*
            3. 결합한 payload를 Gemini로 전송한다.
            - 이미 포맷된 여러 줄 대화 블록이므로 추가 접두어 없이 그대로 전송한다.
         */
        broadcastGeminiRequestService.processFormattedDialogueMessage(
                broadcastStreamId,
                generation,
                character,
                combinedMessage
        );

        /*
            4. 전송 성공 시 해당 대화들을 sentToGemini=true로 마킹한다.
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
     * @return : 줄바꿈으로 결합된 Gemini 입력 문자열
     */
    private String buildGeminiDialoguePayload(List<BroadcastInfoRedisDto> dialogues) {
        log.info("[BroadcastMessageService] buildGeminiDialoguePayload() - START | dialogueCount: {}", dialogues.size());

        /*
            1. 각 대화를 Gemini 입력 포맷으로 변환한 뒤 줄바꿈으로 결합한다.
         */
        String result = dialogues.stream()
                .map(this::formatDialogueForGemini)
                .collect(Collectors.joining("\n"));

        log.info("[BroadcastMessageService] buildGeminiDialoguePayload() - END | payloadLength: {}", result.length());
        return result;
    }

    /**
     * Redis 대화를 Gemini 입력용 단일 문자열로 변환한다.
     * - STREAMER는 (스트리머) 접두어를 붙인다.
     * - VIEWER 및 그 외 대화는 저장된 문자열을 그대로 사용한다.
     * @param dialogue : Redis 대화 DTO
     * @return : Gemini 입력용 단일 문자열
     */
    private String formatDialogueForGemini(BroadcastInfoRedisDto dialogue) {
        log.info("[BroadcastMessageService] formatDialogueForGemini() - START | cursorId: {}, subject: {}",
                dialogue.cursorId(), dialogue.subject());

        /*
            1. subject에 따라 Gemini 입력 문자열을 구성한다.
            - STREAMER는 원문에 (스트리머) 접두어를 부여한다.
            - VIEWER는 저장 시점에 prefix가 포함되어 있으므로 그대로 사용한다.
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
     * - 모든 whitespace(\s+) 제거 (한글/영어 공통)
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
}
