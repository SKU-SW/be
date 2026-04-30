package com.example.sku_sw.domain.broadcast.service;

import com.example.sku_sw.domain.broadcast.dto.BroadcastCharacterRedisDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastInfoRedisDto;
import com.example.sku_sw.domain.broadcast.enums.BroadcastInfoRole;
import com.example.sku_sw.domain.broadcast.util.BroadcastRedisUtil;
import com.example.sku_sw.global.util.GeminiFunctionCallingResponseDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
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
    private final BroadcastVoiceTransferService broadcastVoiceTransferService;

    /**
     * 클라이언트 텍스트 메시지를 처리한다.
     * - Redis에서 캐릭터 정보를 조회하고, USER 메시지를 BroadcastInfo에 저장한다.
     * - 트리거 워드 포함 여부와 isTalking 상태에 따라 Gemini 호출 또는 종료를 결정한다.
     * - Gemini 호출은 비동기(Mono subscribe)로 수행되며, 결과에 따라 isTalking 갱신 및 AI 응답을 저장한다.
     *
     * @param broadcastStreamId : 방송 스트림 ID
     * @param message           : 클라이언트가 보낸 텍스트 메시지
     * @param startTime         : handleTextMessage 시작 시각(ms)
     */
    public void handleClientMessage(String broadcastStreamId, String message, long startTime) {
        log.info("[BroadcastMessageService] handleClientMessage() - START | streamId: {}, message: {}", broadcastStreamId, message);

        /*
            1. 방송 캐릭터 정보 조회
            - Redis에 BroadcastCharacter:{broadcastStreamId} 데이터가 없으면 예외가 발생한다.
         */
        BroadcastCharacterRedisDto character = broadcastRedisUtil.getBroadcastCharacterDto(broadcastStreamId);

        /*
            2. USER BroadcastInfo 저장
            - 클라이언트 메시지를 USER 역할로 BroadcastInfo Redis List에 저장한다.
         */
        BroadcastInfoRedisDto userInfo = BroadcastInfoRedisDto.builder()
                .role(BroadcastInfoRole.USER)
                .message(message)
                .createdAt(LocalDateTime.now())
                .build();
        broadcastRedisUtil.pushBroadcastInfo(broadcastStreamId, userInfo);
        log.info("[BroadcastMessageService] handleClientMessage() - Client message saved | streamId: {}, clientMessage: {}", broadcastStreamId, message);

        /*
            3. 메시지 정규화 및 트리거 워드 확인
            - null-safe, trim, 모든 whitespace 제거, 영어 lower-case(Locale.ROOT)
         */
        String normalizedMessage = normalize(message);
        boolean hasTriggerWord = false;
        if (character.getCharacterTriggerWords() != null) {
            hasTriggerWord = character.getCharacterTriggerWords().stream()
                    .anyMatch(trigger -> normalizedMessage.contains(normalize(trigger)));
        }

        Boolean isTalking = character.getIsTalking();

        /*
            4. 호출어/트리거 워드 및 isTalking 상태에 따른 분기 처리
         */
        if (!hasTriggerWord && (isTalking == null || !isTalking)) {
            /*
                호출어 없음 + isTalking false/null -> 종료
                - Redis 저장 없이 그냥 return
             */
            log.info("[BroadcastMessageService] handleClientMessage() - No trigger word and not talking, skipping | streamId: {}", broadcastStreamId);
            log.info("[BroadcastMessageService] handleClientMessage() - END | streamId: {}, action: skip", broadcastStreamId);
            return;
        }

        if (hasTriggerWord) {
            /*
                호출어 있음 -> isTalking true 갱신 후 Gemini 비동기 호출
             */
            log.info("[BroadcastMessageService] handleClientMessage() - Trigger word detected, activating AI | streamId: {}", broadcastStreamId);
            broadcastRedisUtil.updateBroadcastCharacterIsTalking(broadcastStreamId, true);
        }

        /*
            5. Gemini 비동기 호출
            - 최근 방송 대화 내역을 조회하여 프롬프트에 포함시킨다.
            - block하지 않고 Mono subscribe 방식으로 처리한다.
         */
        List<BroadcastInfoRedisDto> recentInfos = broadcastRedisUtil.getRecentBroadcastInfos(broadcastStreamId, RECENT_BROADCAST_INFO_LIMIT);

        broadcastGeminiService.processClientMessage(character, recentInfos, message)
                .subscribeOn(Schedulers.boundedElastic())
                // flatMap: Gemini 응답을 처리하고 VoiceTransfer까지 연결하는 단일 reactive 체인
                // map은 Mono 안에 들어있는 데이터를 처리하고, 다시 Mono 객체로 감싸준다. 이때, 해당 숨사의 반환값이 일반 DTO 값이 아니라 Mono인 경우 Mono<Mono<>>가 되어버린다
                // flatMap을 쓰면 이 현상이 일어나지 않게 하나의 Mono로만 감싸버린다.
                .flatMap(response -> handleGeminiResponseReactively(broadcastStreamId, response, startTime))
                .subscribe(
                        null, // onNext - handled in flatMap
                        error -> log.error("[BroadcastMessageService] handleClientMessage() - Gemini/Voice chain failed | streamId: {}, error: {}", broadcastStreamId, error.getMessage())
                );

        log.info("[BroadcastMessageService] handleClientMessage() - END | streamId: {}, action: gemini_called", broadcastStreamId);
    }

    /**
     * Gemini API 응답을 reactive 체인으로 처리한다.
     * - functionCalled true -> isTalking false 갱신, AI 응답 저장 안 함
     * - text 존재 -> AI BroadcastInfo 저장 후 BroadcastVoiceTransferService로 TTS 처리 및 WebSocket 전송
     *
     * @param broadcastStreamId : 방송 스트림 ID
     * @param response          : Gemini Function Calling 응답 DTO
     * @param startTime         : handleTextMessage 시작 시각(ms)
     * @return : 처리 완료 Mono (Void)
     */
    private Mono<Void> handleGeminiResponseReactively(String broadcastStreamId, GeminiFunctionCallingResponseDto response, long startTime) {
        log.info("[BroadcastMessageService] handleGeminiResponseReactively() - START | streamId: {}, functionCalled: {}", broadcastStreamId, response.functionCalled());

        if (response.functionCalled()) {
            /*
                Function Call 발생 -> isTalking false 갱신, AI 응답 저장 안 함
             */
            broadcastRedisUtil.updateBroadcastCharacterIsTalking(broadcastStreamId, false);
            log.info("[BroadcastMessageService] handleGeminiResponseReactively() - Function call, isTalking set to false | streamId: {}, aiResponse: false(functionCall)", broadcastStreamId);
            log.info("[BroadcastMessageService] handleGeminiResponseReactively() - END | streamId: {}", broadcastStreamId);
            return Mono.empty();
        }

        if (response.text() != null && !response.text().isBlank()) {
            /*
                (1) 텍스트 응답 존재 -> AI BroadcastInfo 저장
             */
            BroadcastInfoRedisDto aiInfo = BroadcastInfoRedisDto.builder()
                    .role(BroadcastInfoRole.AI)
                    .message(response.text())
                    .createdAt(LocalDateTime.now())
                    .build();
            broadcastRedisUtil.pushBroadcastInfo(broadcastStreamId, aiInfo);
            log.info("[BroadcastMessageService] handleGeminiResponseReactively() - AI response saved | streamId: {}, aiResponse: {}, responseLength: {}, elapsedMs: {}",
                    broadcastStreamId, response.text(), response.text().length(), System.currentTimeMillis() - startTime);

            /*
                (2) BroadcastVoiceTransferService로 TTS 처리 및 WebSocket 전송 (reactive chain)
                - Redis 저장 후 동일한 reactive 체인에서 voiceTransfer를 호출하여 nested subscribe를 방지한다.
             */
            log.info("[BroadcastMessageService] handleGeminiResponseReactively() - END | streamId: {}", broadcastStreamId);
            return broadcastVoiceTransferService.processVoiceTransfer(broadcastStreamId, response.text());
        }

        log.info("[BroadcastMessageService] handleGeminiResponseReactively() - END | streamId: {}", broadcastStreamId);
        return Mono.empty();
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
