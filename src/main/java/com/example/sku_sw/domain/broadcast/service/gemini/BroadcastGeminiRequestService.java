package com.example.sku_sw.domain.broadcast.service.gemini;

import com.example.sku_sw.domain.broadcast.dto.BroadcastCharacterRedisDto;
import com.example.sku_sw.domain.broadcast.enums.BroadcastErrorCode;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionBundle;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionRegistry;
import com.example.sku_sw.global.exception.CustomException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Gemini Live WebSocket 메시지 전송 서비스
 * - 현재 READY 상태의 Gemini WebSocket 세션으로 클라이언트 메시지를 전달한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BroadcastGeminiRequestService {

    private final ObjectMapper objectMapper;
    private final BroadcastWebSocketSessionRegistry sessionRegistry;

    /**
     * 클라이언트 메시지를 Gemini Live WebSocket 세션으로 전송한다.
     * - 현재 generation과 READY 상태를 확인한 후 realtimeInput.text 메시지를 전송한다.
     *
     * @param broadcastStreamId : 방송 스트림 ID
     * @param generation        : 현재 세션 generation
     * @param character         : 방송 캐릭터 정보
     * @param clientMessage     : 클라이언트 메시지
     */
    public void processClientMessage(
            String broadcastStreamId,
            long generation,
            BroadcastCharacterRedisDto character,
            String clientMessage
    ) {
        log.info("[BroadcastGeminiService] processClientMessage() - START | streamId: {}, generation: {}",
                broadcastStreamId, generation);

        BroadcastWebSocketSessionBundle bundle = sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation);
        if (bundle == null || !bundle.isReady() || !bundle.isGeminiSessionOpen()) {
            throw new CustomException(BroadcastErrorCode.WEBSOCKET_SESSION_NOT_READY);
        }

        try {
            ObjectNode requestNode = objectMapper.createObjectNode();
            ObjectNode realtimeInputNode = requestNode.putObject("realtimeInput");
            realtimeInputNode.put("text", clientMessage);

            WebSocketSession geminiSession = bundle.getGeminiSession();
            geminiSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(requestNode)));

            log.info("[BroadcastGeminiService] processClientMessage() - END | streamId: {}, generation: {}, characterId: {}",
                    broadcastStreamId,
                    generation,
                    character.getCharacterId());
        } catch (Exception e) {
            log.error("[BroadcastGeminiService] processClientMessage() - Failed | streamId: {}, generation: {}, error: {}",
                    broadcastStreamId, generation, e.getMessage());
            throw new CustomException(BroadcastErrorCode.GEMINI_RESPONSE_FAILED);
        }
    }
}
