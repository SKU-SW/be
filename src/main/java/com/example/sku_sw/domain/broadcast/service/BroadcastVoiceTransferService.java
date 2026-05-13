package com.example.sku_sw.domain.broadcast.service;

import com.example.sku_sw.domain.broadcast.dto.BroadcastCharacterRedisDto;
import com.example.sku_sw.domain.broadcast.dto.FastApiTtsReqDto;
import com.example.sku_sw.domain.broadcast.util.BroadcastRedisUtil;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketVoiceSender;
import com.example.sku_sw.global.util.FastApiUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * FastAPI TTS 음성 변환 및 WebSocket 전송을 처리하는 서비스
 * - Gemini AI 응답 텍스트를 FastAPI TTS 서버로 전달하여 음성 데이터를 생성한다.
 * - 생성된 음성 데이터를 WebSocket을 통해 클라이언트로 전송한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BroadcastVoiceTransferService {

    private final FastApiUtil fastApiUtil;
    private final BroadcastRedisUtil broadcastRedisUtil;
    private final BroadcastWebSocketVoiceSender broadcastWebSocketVoiceSender;

    /**
     * AI 응답 텍스트를 FastAPI TTS로 음성 변환한 후 WebSocket으로 전송한다.
     * - Redis에서 캐릭터 정보를 조회하여 ttsId를 가져온다.
     * - FastAPI TTS API를 호출하여 음성 데이터를 생성한다.
     * - 생성된 음성 데이터와 메타데이터를 WebSocket으로 클라이언트에 전송한다.
     *
     * @param broadcastStreamId : 방송 스트림 ID
     * @param voiceText         : 음성으로 변환할 텍스트
     * @param broadcastDialogueCursorId : Redis BroadcastInfo cursor ID
     * @param startTime         : handleTextMessage 시작 시각(ms)
     * @return : 음성 전송 결과 (Mono&lt;Void&gt;)
     */
    public Mono<Void> processVoiceTransfer(String broadcastStreamId, String voiceText, Long broadcastDialogueCursorId, long startTime) {
        log.info("[BroadcastVoiceTransferService] processVoiceTransfer() - START | streamId: {}, textLength: {}, cursorId: {}",
                broadcastStreamId, voiceText != null ? voiceText.length() : 0, broadcastDialogueCursorId);

        /*
            1. Redis에서 캐릭터 정보 조회
            - TTS ID(characterVoiceTtsId)를 가져오기 위해 캐릭터 정보를 조회한다.
         */
        BroadcastCharacterRedisDto character = broadcastRedisUtil.getBroadcastCharacterDto(broadcastStreamId);

        /*
            2. FastAPI TTS 요청 DTO 생성
         */
        FastApiTtsReqDto request = new FastApiTtsReqDto(
                broadcastStreamId,
                character.getCharacterId(),
                character.getCharacterVoiceTtsId(),
                voiceText,
                broadcastDialogueCursorId
        );

        /*
            3. FastAPI TTS 호출 및 WebSocket 전송
            - FastAPI 응답을 받으면 WebSocketHandler를 통해 클라이언트로 전송한다.
         */
        return fastApiUtil.generateTts(request)
                .doOnNext(response -> {
                    log.info("[BroadcastVoiceTransferService] processVoiceTransfer() - TTS response received | streamId: {}, voiceDataLength: {}",
                            broadcastStreamId, response.voiceData() != null ? response.voiceData().length : 0);

                    /*
                        (1) FastAPI metadata cursor 검증
                        - FastAPI 응답 cursor가 없거나 요청 cursor와 다르면, Spring이 보낸 cursor를 기준으로 메타데이터를 유지한다.
                     */
                    Long metadataCursorId = response.broadcastDialogueCursorId();
                    if (metadataCursorId == null || !metadataCursorId.equals(broadcastDialogueCursorId)) {
                        log.warn("[BroadcastVoiceTransferService] processVoiceTransfer() - Cursor mismatch detected | streamId: {}, requestCursorId: {}, responseCursorId: {}",
                                broadcastStreamId, broadcastDialogueCursorId, metadataCursorId);
                        metadataCursorId = broadcastDialogueCursorId;
                    }

                    /*
                        (2) WebSocket으로 음성 데이터 + 메타데이터 전송
                        - sendVoiceWithMetadata()는 동기 호출이며, 내부에서 예외 발생 시 CustomException을 던진다.
                     */
                    broadcastWebSocketVoiceSender.sendVoiceWithMetadata(
                            broadcastStreamId,
                            response.voiceData(),
                            response.characterId(),
                            response.voiceText(),
                            metadataCursorId,
                            startTime
                    );
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(error ->
                        log.error("[BroadcastVoiceTransferService] processVoiceTransfer() - Failed | streamId: {}, error: {}",
                                broadcastStreamId, error.getMessage())
                )
                .doOnSuccess(res ->
                        log.info("[BroadcastVoiceTransferService] processVoiceTransfer() - END | streamId: {}", broadcastStreamId)
                )
                .then();
    }
}
