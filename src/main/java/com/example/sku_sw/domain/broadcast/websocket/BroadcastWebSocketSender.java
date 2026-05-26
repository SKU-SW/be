package com.example.sku_sw.domain.broadcast.websocket;

import com.example.sku_sw.domain.broadcast.dto.BroadcastVoiceMetadataResDto;
import com.example.sku_sw.domain.broadcast.enums.BroadcastErrorCode;
import com.example.sku_sw.domain.broadcast.enums.BroadcastVoiceEventType;
import com.example.sku_sw.domain.character.enums.Emotion;
import com.example.sku_sw.global.exception.CustomException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.nio.ByteBuffer;

@Slf4j
@Component
@RequiredArgsConstructor
public class BroadcastWebSocketSender {

    private final ObjectMapper objectMapper;
    private final BroadcastWebSocketSessionRegistry sessionRegistry;

    /**
     * 음성 청크와 메타데이터를 클라이언트에게 전송한다.
     * - BinaryMessage로 음성 데이터 전송
     * - TextMessage로 메타데이터(JSON) 전송
     * - synchronized로 전송 순서를 보장한다.
     *
     * @param broadcastStreamId : 대상 방송 스트림 ID
     * @param generation : 현재 세션 generation
     * @param voiceData : 음성 청크 데이터
     * @param characterId : 캐릭터 ID
     * @param turnNumber : Gemini 응답 turn 번호
     * @param voiceText : 음성 텍스트 청크
     * @param emotion : 응답 감정값
     */
    public void sendVoiceChunkWithMetadata(
            String broadcastStreamId,
            Long generation,
            byte[] voiceData,
            Long characterId,
            Long turnNumber,
            String voiceText,
            Emotion emotion
    ) {
        BroadcastVoiceMetadataResDto metadata = BroadcastVoiceMetadataResDto.builder()
                .eventType(BroadcastVoiceEventType.VOICE_CHUNK)
                .turnNumber(turnNumber)
                .characterId(characterId)
                .voiceText(voiceText)
                .emotion(emotion)
                .broadcastDialogueCursorId(null)
                .build();

        sendPayload(broadcastStreamId, generation, voiceData, metadata, "sendVoiceChunkWithMetadata");
    }

    /**
     * Gemini turn 완료 메타데이터를 클라이언트에게 전송한다.
     * - cursorId를 포함한 메타데이터만 전달한다.
     *
     * @param broadcastStreamId : 대상 방송 스트림 ID
     * @param generation : 현재 세션 generation
     * @param characterId : 캐릭터 ID
     * @param turnNumber : Gemini 응답 turn 번호
     * @param voiceText : 누적된 텍스트 데이터
     * @param emotion : 응답 감정값
     * @param broadcastDialogueCursorId : BroadcastInfo Cursor ID
     */
    public void sendTurnCompleteMetadata(
            String broadcastStreamId,
            Long generation,
            Long characterId,
            Long turnNumber,
            String voiceText,
            Emotion emotion,
            Long broadcastDialogueCursorId
    ) {
        BroadcastVoiceMetadataResDto metadata = BroadcastVoiceMetadataResDto.builder()
                .eventType(BroadcastVoiceEventType.VOICE_TURN_COMPLETE)
                .turnNumber(turnNumber)
                .characterId(characterId)
                .voiceText(voiceText)
                .emotion(emotion)
                .broadcastDialogueCursorId(broadcastDialogueCursorId)
                .build();

        sendPayload(broadcastStreamId, generation, null, metadata, "sendTurnCompleteMetadata");
    }

    /**
     * 인터럽트 완료 메타데이터(VOICE_INTERRUPTED)를 클라이언트에게 전송한다.
     * - 기존 BroadcastVoiceMetadataResDto 양식을 그대로 사용하며 eventType만 VOICE_INTERRUPTED로 설정한다.
     *
     * @param broadcastStreamId : 대상 방송 스트림 ID
     * @param generation : 현재 세션 generation
     * @param characterId : 캐릭터 ID
     * @param turnNumber : Gemini 응답 turn 번호
     * @param voiceText : 인터럽트되어 저장된 텍스트 ("[응답 중단됨]" 포함)
     * @param emotion : 응답 감정값
     * @param broadcastDialogueCursorId : BroadcastInfo Cursor ID
     */
    public void sendInterruptedMetadata(
            String broadcastStreamId,
            Long generation,
            Long characterId,
            Long turnNumber,
            String voiceText,
            Emotion emotion,
            Long broadcastDialogueCursorId
    ) {
        BroadcastVoiceMetadataResDto metadata = BroadcastVoiceMetadataResDto.builder()
                .eventType(BroadcastVoiceEventType.VOICE_INTERRUPTED)
                .turnNumber(turnNumber)
                .characterId(characterId)
                .voiceText(voiceText)
                .emotion(emotion)
                .broadcastDialogueCursorId(broadcastDialogueCursorId)
                .build();

        sendPayload(broadcastStreamId, generation, null, metadata, "sendInterruptedMetadata");
    }

    /**
     * 현재 turn의 감정 정보만 클라이언트에게 전송한다.
     *
     * @param broadcastStreamId : 대상 방송 스트림 ID
     * @param generation : 현재 세션 generation
     * @param characterId : 캐릭터 ID
     * @param turnNumber : Gemini 응답 turn 번호
     * @param emotion : 응답 감정값
     */
    public void sendEmotionMetadata(
            String broadcastStreamId,
            Long generation,
            Long characterId,
            Long turnNumber,
            Emotion emotion
    ) {
        // voiceText, CursorId 값 없이 감정 정보만 담긴 Dto를 생성하고 클라이언트에게 전송한다.
        BroadcastVoiceMetadataResDto metadata = BroadcastVoiceMetadataResDto.buildEmotionMetadataResDto(turnNumber, characterId, emotion);
        sendPayload(broadcastStreamId, generation, null, metadata, "sendEmotionMetadata");
    }

    private void sendPayload(
            String broadcastStreamId,
            Long generation,
            byte[] voiceData,
            BroadcastVoiceMetadataResDto metadata,
            String methodName
    ) {
        BroadcastWebSocketSessionBundle bundle = getReadyBundle(broadcastStreamId, generation, methodName);
        WebSocketSession clientSession = bundle.getClientSession();
        int voiceDataLength = voiceData == null ? 0 : voiceData.length;
        boolean binarySent = voiceDataLength > 0;

        try {
            String metadataJson = objectMapper.writeValueAsString(metadata);

            synchronized (clientSession) {
                if (binarySent) {
                    clientSession.sendMessage(new BinaryMessage(ByteBuffer.wrap(voiceData)));
                }
                clientSession.sendMessage(new TextMessage(metadataJson));
            }

            log.info("[BroadcastWebSocketSender] {}() - Sent | streamId: {}, generation: {}, eventType: {}, turnNumber: {}, cursorId: {}, emotion: {}, binarySent: {}, voiceDataLength: {}",
                    methodName,
                    broadcastStreamId,
                    generation,
                    metadata.eventType(),
                    metadata.turnNumber(),
                    metadata.broadcastDialogueCursorId(),
                    metadata.emotion(),
                    binarySent,
                    voiceDataLength);

            if (metadata.eventType() == BroadcastVoiceEventType.VOICE_CHUNK && !binarySent) {
                log.warn("[BroadcastWebSocketSender] {}() - VOICE_CHUNK metadata sent without audio binary | streamId: {}, generation: {}, turnNumber: {}, textLength: {}",
                        methodName,
                        broadcastStreamId,
                        generation,
                        metadata.turnNumber(),
                        metadata.voiceText() == null ? 0 : metadata.voiceText().length());
            }
        } catch (IOException e) {
            log.error("[BroadcastWebSocketSender] {}() - Failed to send | streamId: {}, error: {}",
                    methodName, broadcastStreamId, e.getMessage());
            throw new CustomException(BroadcastErrorCode.WEBSOCKET_CONNECTION_NOT_FOUND);
        }
    }

    private BroadcastWebSocketSessionBundle getReadyBundle(String broadcastStreamId, Long generation, String methodName) {
        BroadcastWebSocketSessionBundle bundle = sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation);
        if (bundle == null || !bundle.isReady()) {
            log.warn("[BroadcastWebSocketSender] {}() - Bundle not ready | streamId: {}, generation: {}",
                    methodName, broadcastStreamId, generation);
            throw new CustomException(BroadcastErrorCode.WEBSOCKET_CONNECTION_NOT_FOUND);
        }

        WebSocketSession clientSession = bundle.getClientSession();
        if (clientSession == null || !clientSession.isOpen()) {
            log.warn("[BroadcastWebSocketSender] {}() - Session not found or closed | streamId: {}",
                    methodName, broadcastStreamId);
            throw new CustomException(BroadcastErrorCode.WEBSOCKET_CONNECTION_NOT_FOUND);
        }

        return bundle;
    }
}
