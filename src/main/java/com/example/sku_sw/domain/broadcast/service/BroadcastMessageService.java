package com.example.sku_sw.domain.broadcast.service;

import com.example.sku_sw.domain.broadcast.dto.BroadcastCharacterRedisDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastInfoRedisDto;
import com.example.sku_sw.domain.broadcast.enums.BroadcastErrorCode;
import com.example.sku_sw.domain.broadcast.enums.DialogueSubject;
import com.example.sku_sw.domain.broadcast.service.gemini.BroadcastGeminiService;
import com.example.sku_sw.domain.broadcast.util.BroadcastRedisUtil;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionBundle;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionRegistry;
import com.example.sku_sw.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

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
    private final BroadcastGeminiService broadcastGeminiService;
    private final BroadcastWebSocketSessionRegistry sessionRegistry;
    private final BroadcastDialogueCompactionService broadcastDialogueCompactionService;

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

        BroadcastWebSocketSessionBundle bundle = sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation);
        if (bundle == null || !bundle.isReady()) {
            throw new CustomException(BroadcastErrorCode.WEBSOCKET_SESSION_NOT_READY);
        }

        BroadcastCharacterRedisDto character = broadcastRedisUtil.getBroadcastCharacterDto(broadcastStreamId);
        BroadcastInfoRedisDto savedUserInfo = broadcastRedisUtil.pushBroadcastInfo(broadcastStreamId, DialogueSubject.STREAMER, message);
        log.info("[BroadcastMessageService] handleClientMessage() - Client message saved | streamId: {}, cursorId: {}, clientMessage: {}",
                broadcastStreamId, savedUserInfo.cursorId(), message);
        broadcastDialogueCompactionService.tryStartCompaction(broadcastStreamId);

        String normalizedMessage = normalize(message);
        boolean hasTriggerWord = false;
        if (character.getCharacterTriggerWords() != null) {
            hasTriggerWord = character.getCharacterTriggerWords().stream()
                    .anyMatch(trigger -> normalizedMessage.contains(normalize(trigger)));
        }

        Boolean isTalking = character.getIsTalking();

        if (!hasTriggerWord && (isTalking == null || !isTalking)) {
            log.info("[BroadcastMessageService] handleClientMessage() - No trigger word and not talking, skipping | streamId: {}", broadcastStreamId);
            log.info("[BroadcastMessageService] handleClientMessage() - END | streamId: {}, action: skip", broadcastStreamId);
            return;
        }

        if (hasTriggerWord) {
            log.info("[BroadcastMessageService] handleClientMessage() - Trigger word detected, activating AI | streamId: {}", broadcastStreamId);
            broadcastRedisUtil.updateBroadcastCharacterIsTalking(broadcastStreamId, true);
        }

        BroadcastInfoRedisDto summary = broadcastRedisUtil.getSummary(broadcastStreamId);
        List<BroadcastInfoRedisDto> recentInfos = broadcastRedisUtil.getRecentActiveDialogues(broadcastStreamId, RECENT_BROADCAST_INFO_LIMIT);

        broadcastGeminiService.processClientMessage(
                broadcastStreamId,
                generation,
                character,
                summary,
                recentInfos,
                message
        );

        log.info("[BroadcastMessageService] handleClientMessage() - END | streamId: {}, action: gemini_called", broadcastStreamId);
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
