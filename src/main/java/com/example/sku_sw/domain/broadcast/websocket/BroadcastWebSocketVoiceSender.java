package com.example.sku_sw.domain.broadcast.websocket;

import com.example.sku_sw.domain.broadcast.dto.BroadcastVoiceMetadataResDto;
import com.example.sku_sw.domain.broadcast.enums.BroadcastErrorCode;
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
import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class BroadcastWebSocketVoiceSender {

    private final ObjectMapper objectMapper;
    private final BroadcastWebSocketSessionRegistry sessionRegistry;

    /**
     * 음성 데이터와 메타데이터를 클라이언트에게 전송
     * - BinaryMessage로 음성 데이터 전송
     * - TextMessage로 메타데이터(JSON) 전송
     * - synchronized로 전송 순서 보장
     *
     * @param broadcastStreamId : 대상 방송 스트림 ID
     * @param voiceData : 음성 데이터 (byte 배열)
     * @param characterId : 캐릭터 ID
     * @param voiceText : 음성 텍스트 데이터
     * @param broadcastDialogueCursorId : BroadcastInfo Cursor ID
     */
    public void sendVoiceWithMetadata(
            String broadcastStreamId,
            byte[] voiceData,
            Long characterId,
            String voiceText,
            Long broadcastDialogueCursorId,
            Long startTime
    ) {
        WebSocketSession session = sessionRegistry.getSession(broadcastStreamId);
        if (session == null || !session.isOpen()) {
            log.warn("[BroadcastWebSocketHandler] sendVoiceWithMetadata() - Session not found or closed | streamId: {}", broadcastStreamId);
            throw new CustomException(BroadcastErrorCode.WEBSOCKET_CONNECTION_NOT_FOUND);
        }

        /*
            1. 메타데이터 JSON 직렬화
         */
        BroadcastVoiceMetadataResDto metadata = BroadcastVoiceMetadataResDto.builder()
                .characterId(characterId)
                .voiceText(voiceText)
                .broadcastDialogueCursorId(broadcastDialogueCursorId)
                .build();

        try {
            String metadataJson = objectMapper.writeValueAsString(metadata);

            /*
                2. 음성 데이터(바이너리) → 메타데이터(JSON) 순서로 전송
                - synchronized로 동시 전송 시 순서 보장
             */
            synchronized (session) {
                session.sendMessage(new BinaryMessage(ByteBuffer.wrap(voiceData)));
                session.sendMessage(new TextMessage(metadataJson));
            }

            log.info("[BroadcastWebSocketHandler] sendVoiceWithMetadata() - Sent | streamId: {}, cursorId: {}, voice response time: {}", broadcastStreamId, broadcastDialogueCursorId, System.currentTimeMillis() - startTime);
        } catch (IOException e) {
            log.error("[BroadcastWebSocketHandler] sendVoiceWithMetadata() - Failed to send | streamId: {}, error: {}", broadcastStreamId, e.getMessage());
            throw new CustomException(BroadcastErrorCode.WEBSOCKET_CONNECTION_NOT_FOUND);
        }
    }

}
