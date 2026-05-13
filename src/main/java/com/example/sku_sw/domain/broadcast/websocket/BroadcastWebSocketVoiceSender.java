package com.example.sku_sw.domain.broadcast.websocket;

import com.example.sku_sw.domain.broadcast.dto.BroadcastVoiceMetadataResDto;
import com.example.sku_sw.domain.broadcast.enums.BroadcastErrorCode;
import com.example.sku_sw.domain.broadcast.enums.BroadcastVoiceEventType;
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
public class BroadcastWebSocketVoiceSender {

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
     */
    public void sendVoiceChunkWithMetadata(
            String broadcastStreamId,
            Long generation,
            byte[] voiceData,
            Long characterId,
            Long turnNumber,
            String voiceText
    ) {
        BroadcastVoiceMetadataResDto metadata = BroadcastVoiceMetadataResDto.builder()
                .eventType(BroadcastVoiceEventType.VOICE_CHUNK)
                .turnNumber(turnNumber)
                .characterId(characterId)
                .voiceText(voiceText)
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
     * @param broadcastDialogueCursorId : BroadcastInfo Cursor ID
     */
    public void sendTurnCompleteMetadata(
            String broadcastStreamId,
            Long generation,
            Long characterId,
            Long turnNumber,
            String voiceText,
            Long broadcastDialogueCursorId
    ) {
        BroadcastVoiceMetadataResDto metadata = BroadcastVoiceMetadataResDto.builder()
                .eventType(BroadcastVoiceEventType.VOICE_TURN_COMPLETE)
                .turnNumber(turnNumber)
                .characterId(characterId)
                .voiceText(voiceText)
                .broadcastDialogueCursorId(broadcastDialogueCursorId)
                .build();

        sendPayload(broadcastStreamId, generation, null, metadata, "sendTurnCompleteMetadata");
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

        try {
            String metadataJson = objectMapper.writeValueAsString(metadata);

            synchronized (clientSession) {
                if (voiceData != null && voiceData.length > 0) {
                    clientSession.sendMessage(new BinaryMessage(ByteBuffer.wrap(voiceData)));
                }
                clientSession.sendMessage(new TextMessage(metadataJson));
            }

            log.info("[BroadcastWebSocketVoiceSender] {}() - Sent | streamId: {}, generation: {}, eventType: {}, turnNumber: {}, cursorId: {}",
                    methodName, broadcastStreamId, generation, metadata.eventType(), metadata.turnNumber(), metadata.broadcastDialogueCursorId());
        } catch (IOException e) {
            log.error("[BroadcastWebSocketVoiceSender] {}() - Failed to send | streamId: {}, error: {}",
                    methodName, broadcastStreamId, e.getMessage());
            throw new CustomException(BroadcastErrorCode.WEBSOCKET_CONNECTION_NOT_FOUND);
        }
    }

    private BroadcastWebSocketSessionBundle getReadyBundle(String broadcastStreamId, Long generation, String methodName) {
        BroadcastWebSocketSessionBundle bundle = sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation);
        if (bundle == null || !bundle.isReady()) {
            log.warn("[BroadcastWebSocketVoiceSender] {}() - Bundle not ready | streamId: {}, generation: {}",
                    methodName, broadcastStreamId, generation);
            throw new CustomException(BroadcastErrorCode.WEBSOCKET_CONNECTION_NOT_FOUND);
        }

        WebSocketSession clientSession = bundle.getClientSession();
        if (clientSession == null || !clientSession.isOpen()) {
            log.warn("[BroadcastWebSocketVoiceSender] {}() - Session not found or closed | streamId: {}",
                    methodName, broadcastStreamId);
            throw new CustomException(BroadcastErrorCode.WEBSOCKET_CONNECTION_NOT_FOUND);
        }

        return bundle;
    }
}
