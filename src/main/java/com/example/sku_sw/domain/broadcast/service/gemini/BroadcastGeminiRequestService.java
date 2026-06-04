package com.example.sku_sw.domain.broadcast.service.gemini;

import com.example.sku_sw.domain.broadcast.dto.BroadcastCharacterRedisDto;
import com.example.sku_sw.domain.broadcast.enums.BroadcastErrorCode;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionBundle;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionRegistry;
import com.example.sku_sw.domain.chat.dto.ChzzkChatMessageDto;
import com.example.sku_sw.global.exception.CustomException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import com.fasterxml.jackson.databind.node.ArrayNode;
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

    private static final Map<String, String> USER_ROLE_CODE_TO_KOREAN = Map.of(
            "common_chat_user", "시청자",
            "manager", "매니저",
            "subscription_user", "구독자",
            "top_fan_user", "열혈팬",
            "streaming_chat_notice_admin", "공지 관리자"
    );

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
        if (bundle == null || !bundle.canSendToGemini()) {
            throw new CustomException(BroadcastErrorCode.WEBSOCKET_SESSION_NOT_READY);
        }

        try {
            bundle.incrementRequestFlight();
            ObjectNode requestNode = objectMapper.createObjectNode();
            ObjectNode realtimeInputNode = requestNode.putObject("realtimeInput");
            realtimeInputNode.put("text", getClientMessage(clientMessage));

            WebSocketSession geminiSession = bundle.getGeminiSession();
            geminiSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(requestNode)));

            log.info("[BroadcastGeminiService] processClientMessage() - END | streamId: {}, generation: {}, characterId: {}",
                    broadcastStreamId,
                    generation,
                    character.getCharacterId());
        } catch (Exception e) {
            bundle.decrementRequestFlight();
            log.error("[BroadcastGeminiService] processClientMessage() - Failed | streamId: {}, generation: {}, error: {}",
                    broadcastStreamId, generation, e.getMessage());
            throw new CustomException(BroadcastErrorCode.GEMINI_RESPONSE_FAILED);
        }
    }

    /**
     * refresh 이후 replay할 클라이언트 메시지를 Gemini Live WebSocket 세션으로 전송한다.
     * @param broadcastStreamId : 방송 스트림 ID
     * @param generation : 현재 세션 generation
     * @param clientMessage : replay할 메시지
     */
    public void processReplayMessage(String broadcastStreamId, long generation, String clientMessage) {
        log.info("[BroadcastGeminiService] processReplayMessage() - START | streamId: {}, generation: {}",
                broadcastStreamId, generation);

        /*
            1. 현재 generation 번들과 Gemini 전송 가능 여부를 확인한다.
            - replay도 일반 Gemini 전송과 동일한 세션 준비 상태를 요구한다.
         */
        BroadcastWebSocketSessionBundle bundle = sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation);
        if (bundle == null || !bundle.canSendToGemini()) {
            throw new CustomException(BroadcastErrorCode.WEBSOCKET_SESSION_NOT_READY);
        }

        /*
            2. 일반 클라이언트 메시지와 동일한 방식으로 replay 메시지를 전송한다.
            - refresh 이후 최신 trigger 1건만 전송하기 위한 공용 send 로직을 재사용한다.
         */
        try {
            bundle.incrementRequestFlight();
            ObjectNode requestNode = objectMapper.createObjectNode();
            ObjectNode realtimeInputNode = requestNode.putObject("realtimeInput");
            realtimeInputNode.put("text", getClientMessage(clientMessage));

            WebSocketSession geminiSession = bundle.getGeminiSession();
            geminiSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(requestNode)));
            log.info("[BroadcastGeminiService] processReplayMessage() - END | streamId: {}, generation: {}",
                    broadcastStreamId, generation);
        } catch (Exception e) {
            bundle.decrementRequestFlight();
            log.error("[BroadcastGeminiService] processReplayMessage() - Failed | streamId: {}, generation: {}, error: {}",
                    broadcastStreamId, generation, e.getMessage());
            throw new CustomException(BroadcastErrorCode.GEMINI_RESPONSE_FAILED);
        }
    }

    /**
     * 현재 Gemini WebSocket 세션으로 인터럽트 요청을 전송한다.
     * - Gemini Live API의 clientContent turnComplete 메시지를 전송하여 현재 응답 생성을 중단하도록 요청한다.
     * - request-flight는 인터럽트 요청 시 증가시키지 않는다. (Gemini가 interrupted:true 응답 시 감소)
     *
     * @param geminiSession    : 인터럽트를 요청할 Gemini WebSocket 세션
     * @param broadcastStreamId : 방송 스트림 ID
     * @param generation        : 현재 세션 generation
     */
    public void sendInterruptRequest(WebSocketSession geminiSession, String broadcastStreamId, long generation) {
        log.info("[BroadcastGeminiService] sendInterruptRequest() - START | streamId: {}, generation: {}",
                broadcastStreamId, generation);

        try {
            /*
                1. clientContent 메시지를 생성하여 Gemini에게 현재 응답 생성을 중단하도록 요청한다.
                - turnComplete: true를 포함하여 현재 turn의 완료를 Gemini에게 알린다.
             */
            ObjectNode requestNode = objectMapper.createObjectNode();
            ObjectNode clientContentNode = requestNode.putObject("clientContent");
            ArrayNode turnsNode = clientContentNode.putArray("turns");
            ObjectNode turnNode = turnsNode.addObject();
            turnNode.put("role", "user");
            ArrayNode partsNode = turnNode.putArray("parts");
            ObjectNode partNode = partsNode.addObject();
            partNode.put("text", """
                    [SYSTEM_CONTROL:INTERRUPT_CURRENT_RESPONSE]
                    This is a backend control signal, not a streamer utterance.
                    Do not answer this message.
                    Do not call any tools for this message.
                    Stop the current response only and wait for the next real streamer utterance.
                    """);
            clientContentNode.put("turnComplete", true);

            geminiSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(requestNode)));

            log.info("[BroadcastGeminiService] sendInterruptRequest() - END | streamId: {}, generation: {}",
                    broadcastStreamId, generation);
        } catch (Exception e) {
            log.error("[BroadcastGeminiService] sendInterruptRequest() - Failed | streamId: {}, generation: {}, error: {}",
                    broadcastStreamId, generation, e.getMessage());
            // 인터럽트 요청 실패는 세션을 종료하지 않고 로깅만 수행한다.
        }
    }


    /**
     * 시청자 채팅 메시지를 Gemini Live WebSocket 세션으로 전송한다. (비-생성, 컨텍스트 전용)
     * - userRoleCode에 따라 접두어를 붙여 포맷팅한다. (Streamer: "(스트리머)", 일반: "(한글역할, 닉네임)", null: "(시청자, 닉네임)")
     * - 세션 번들이 없거나 Gemini 전송 불가 상태면 로깅 후 종료한다. (비동기 채팅이므로 예외 미발생)
     * - clientContent / turnComplete:false 메시지 구조를 사용하여 모델 turn을 요청하지 않는다.
     * - request-flight를 증가시키지 않는다. (시청자 채팅은 Gemini 응답을 트리거하지 않음)
     *
     * @param message : Chzzk 채팅 메시지 DTO
     */
    public void sendViewerChatRequest(ChzzkChatMessageDto message) {
        log.info("[BroadcastGeminiService] sendViewerChatRequest() - START | streamId: {}, nickname: {}",
                message.broadcastStreamId(), message.nickname());

        /*
            1. 세션 번들 조회
            - generation 검증 없이 broadcastStreamId로 현재 세션 번들을 조회한다.
            - 번들이 없거나 Gemini 전송 불가 상태면 로깅 후 종료한다.
         */
        BroadcastWebSocketSessionBundle bundle = sessionRegistry.getSessionBundle(message.broadcastStreamId());
        if (bundle == null || !bundle.canSendToGemini()) {
            log.info("[BroadcastGeminiService] sendViewerChatRequest() - No ready Gemini session, skipping | streamId: {}",
                    message.broadcastStreamId());
            log.info("[BroadcastGeminiService] sendViewerChatRequest() - END | streamId: {}, action: skip",
                    message.broadcastStreamId());
            return;
        }

        /*
            2. 채팅 메시지 포맷팅 후 Gemini 전송 (clientContent / turnComplete:false)
            - userRoleCode에 따라 "(시청자, 닉네임)" 또는 "(스트리머)" 접두어를 붙인다.
            - realtimeInput 대신 clientContent + turnComplete:false를 사용하여 모델 turn을 요청하지 않는다.
            - request-flight를 증가시키지 않는다.
         */
        try {
            ObjectNode requestNode = objectMapper.createObjectNode();
            ObjectNode clientContentNode = requestNode.putObject("clientContent");
            ArrayNode turnsNode = clientContentNode.putArray("turns");
            ObjectNode turnNode = turnsNode.addObject();
            turnNode.put("role", "user");
            ArrayNode partsNode = turnNode.putArray("parts");
            ObjectNode partNode = partsNode.addObject();
            partNode.put("text", getChatMessage(message));
            clientContentNode.put("turnComplete", false);

            WebSocketSession geminiSession = bundle.getGeminiSession();
            geminiSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(requestNode)));

            log.info("[BroadcastGeminiService] sendViewerChatRequest() - END | streamId: {}, action: sent",
                    message.broadcastStreamId());
        } catch (Exception e) {
            log.error("[BroadcastGeminiService] sendViewerChatRequest() - Failed | streamId: {}, error: {}",
                    message.broadcastStreamId(), e.getMessage());
        }
    }

    /**
     * 시청자 채팅 메시지를 Gemini Live WebSocket 세션으로 전송한다. (기존 sendViewerChatRequest 이전 버전, 유지보수용)
     * - userRoleCode에 따라 접두어를 붙여 포맷팅한다. (Streamer: "(스트리머)", 일반: "(한글역할, 닉네임)", null: "(시청자, 닉네임)")
     * - 세션 번들이 없거나 Gemini 전송 불가 상태면 로깅 후 종료한다. (비동기 채팅이므로 예외 미발생)
     * - realtimeInput.text 메시지 구조로 전송하며, request-flight를 증가시킨다.
     *
     * @param message : Chzzk 채팅 메시지 DTO
     * @deprecated 시청자 채팅은 {@link #sendViewerChatRequest(ChzzkChatMessageDto)} 사용을 권장.
     *             이 메서드는 request-flight 누수 위험이 있어 더 이상 호출되지 않는다.
     */
    @Deprecated
    public void sendChatRequest(ChzzkChatMessageDto message) {
        log.info("[BroadcastGeminiService] sendChatRequest() - START | streamId: {}, nickname: {}",
                message.broadcastStreamId(), message.nickname());

        /*
            1. 세션 번들 조회
            - generation 검증 없이 broadcastStreamId로 현재 세션 번들을 조회한다.
            - 번들이 없거나 Gemini 전송 불가 상태면 로깅 후 종료한다.
         */
        BroadcastWebSocketSessionBundle bundle = sessionRegistry.getSessionBundle(message.broadcastStreamId());
        if (bundle == null || !bundle.canSendToGemini()) {
            log.info("[BroadcastGeminiService] sendChatRequest() - No ready Gemini session, skipping | streamId: {}",
                    message.broadcastStreamId());
            log.info("[BroadcastGeminiService] sendChatRequest() - END | streamId: {}, action: skip",
                    message.broadcastStreamId());
            return;
        }

        /*
            2. 채팅 메시지 포맷팅 후 Gemini 전송
            - userRoleCode에 따라 "(시청자, 닉네임)" 또는 "(스트리머)" 접두어를 붙인다.
            - processClientMessage()와 동일한 realtimeInput.text 메시지 구조로 전송한다.
         */
        try {
            bundle.incrementRequestFlight();
            ObjectNode requestNode = objectMapper.createObjectNode();
            ObjectNode realtimeInputNode = requestNode.putObject("realtimeInput");
            realtimeInputNode.put("text", getChatMessage(message));

            WebSocketSession geminiSession = bundle.getGeminiSession();
            geminiSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(requestNode)));

            log.info("[BroadcastGeminiService] sendChatRequest() - END | streamId: {}, action: sent",
                    message.broadcastStreamId());
        } catch (Exception e) {
            bundle.decrementRequestFlight();
            log.error("[BroadcastGeminiService] sendChatRequest() - Failed | streamId: {}, error: {}",
                    message.broadcastStreamId(), e.getMessage());
        }
    }
    
    private String getClientMessage(String clientMessage){
        return "(스트리머)" + clientMessage;
    }

    /**
     * 채팅 메시지 포맷팅
     * - userRoleCode에 따라 접두어를 붙여 Gemini에게 전달할 메시지를 생성한다.
     *   - "streamer" → "(스트리머)" + content
     *   - null/empty → "(시청자, {nickname})" + content
     *   - 그 외 → "({한글 역할명}, {nickname})" + content
     * @param message : Chzzk 채팅 메시지 DTO
     * @return : Gemini에게 전달할 포맷팅된 메시지
     */
    private String getChatMessage(ChzzkChatMessageDto message) {
        String userRoleCode = message.userRoleCode();
        String nickname = message.nickname();
        String content = message.content();

        String prefix;
        if ("streamer".equals(userRoleCode)) {
            prefix = "(스트리머)";
        } else if (userRoleCode == null || userRoleCode.isEmpty()) {
            prefix = "(시청자, " + nickname + ")";
        } else {
            String koreanRole = USER_ROLE_CODE_TO_KOREAN.getOrDefault(userRoleCode, userRoleCode);
            prefix = "(" + koreanRole + ", " + nickname + ")";
        }

        return prefix + content;
    }
}
