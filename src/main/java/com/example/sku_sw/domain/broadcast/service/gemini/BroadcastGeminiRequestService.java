package com.example.sku_sw.domain.broadcast.service.gemini;

import com.example.sku_sw.domain.broadcast.dto.BroadcastCharacterRedisDto;
import com.example.sku_sw.domain.broadcast.enums.BroadcastErrorCode;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionBundle;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionRegistry;
import com.example.sku_sw.domain.chat.dto.ChzzkChatMessageDto;
import com.example.sku_sw.global.exception.CustomException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;

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
    private final TaskScheduler taskScheduler;

    @Value("${gemini.api.live-first-resumption-timeout-ms:3000}")
    private Long liveFirstResumptionTimeoutMs;

    private static final String FIRST_RESUMPTION_EVENT_TEXT = """
            [SYSTEM_CONTROL:FIRST_RESUMPTION_EVENT]
            This is backend-only initial history for session resumption activation.
            Do not answer this message.
            Do not call any tools for this message.
            Treat this as hidden bootstrap control data only.
            """;

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
        log.info("[BroadcastGeminiRequestService] processClientMessage() - START | streamId: {}, generation: {}",
                broadcastStreamId, generation);

        sendRealtimeText(broadcastStreamId, generation, character, formatStreamerMessage(clientMessage));

        log.info("[BroadcastGeminiRequestService] processClientMessage() - END | streamId: {}, generation: {}, characterId: {}",
                broadcastStreamId,
                generation,
                character.getCharacterId());
    }

    /**
     * 이미 Gemini 입력 형식으로 포맷된 대화 블록을 Gemini Live WebSocket 세션으로 전송한다.
     * - 여러 줄 대화 블록을 추가 접두어 없이 그대로 realtimeInput.text로 전달한다.
     * @param broadcastStreamId : 방송 스트림 ID
     * @param generation : 현재 세션 generation
     * @param character : 방송 캐릭터 정보
     * @param formattedDialogueMessage : 이미 포맷된 전체 대화 블록
     */
    public void processFormattedDialogueMessage(
            String broadcastStreamId,
            long generation,
            BroadcastCharacterRedisDto character,
            String formattedDialogueMessage
    ) {
        log.info("[BroadcastGeminiRequestService] processFormattedDialogueMessage() - START | streamId: {}, generation: {}",
                broadcastStreamId, generation);

        sendRealtimeText(broadcastStreamId, generation, character, formattedDialogueMessage);

        log.info("[BroadcastGeminiRequestService] processFormattedDialogueMessage() - END | streamId: {}, generation: {}, characterId: {}",
                broadcastStreamId,
                generation,
                character.getCharacterId());
    }

    /**
     * refresh 이후 replay할 클라이언트 메시지를 Gemini Live WebSocket 세션으로 전송한다.
     * @param broadcastStreamId : 방송 스트림 ID
     * @param generation : 현재 세션 generation
     * @param clientMessage : replay할 메시지
     */
    public void processReplayMessage(String broadcastStreamId, long generation, String clientMessage) {
        log.info("[BroadcastGeminiRequestService] processReplayMessage() - START | streamId: {}, generation: {}",
                broadcastStreamId, generation);

        sendRealtimeText(broadcastStreamId, generation, null, formatStreamerMessage(clientMessage));

        log.info("[BroadcastGeminiRequestService] processReplayMessage() - END | streamId: {}, generation: {}",
                broadcastStreamId, generation);
    }

    /**
     * 방송 시작 직후 Gemini 세션의 첫 resumption update 생성을 유도하는 bootstrap 요청을 전송한다.
     * - 일반 사용자 메시지 흐름을 거치지 않고 raw clientContent payload를 직접 전송한다.
     * - Gemini handler에 first resumption event 진행 상태를 기록한다.
     * - 일정 시간 내 sessionResumptionUpdate가 오지 않으면 timeout 정리를 수행한다.
     * @param broadcastStreamId : 방송 스트림 ID
     * @param generation : 현재 세션 generation
     */
    public void getFirstResumptionEvent(String broadcastStreamId, long generation) {
        log.info("[BroadcastGeminiRequestService] getFirstResumptionEvent() - START | streamId: {}, generation: {}",
                broadcastStreamId, generation);

        /*
            1. 현재 generation의 Session Bundle과 Gemini Handler를 검증한다.
            - bundle이 없거나 Gemini 전송 가능 상태가 아니면 예외를 발생시킨다.
            - Gemini Handler가 없으면 first resumption state를 기록할 수 없으므로 예외를 발생시킨다.
         */
        BroadcastWebSocketSessionBundle bundle = sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation);
        if (bundle == null || !bundle.canSendToGemini()) {
            throw new CustomException(BroadcastErrorCode.WEBSOCKET_SESSION_NOT_READY);
        }
        if (bundle.getGeminiHandler() == null || bundle.getGeminiSession() == null) {
            throw new CustomException(BroadcastErrorCode.WEBSOCKET_SESSION_NOT_READY);
        }

        try {
            /*
                2. first resumption event 진행 상태를 기록하고 request-flight를 증가시킨다.
                - 이후 raw clientContent payload를 직접 Gemini 세션에 전송한다.
            */
            bundle.getGeminiHandler().markFirstResumptionEventStarted();
            int requestFlightCount = bundle.incrementRequestFlight();
            log.info("[BroadcastGeminiRequestService] getFirstResumptionEvent() - Request-flight incremented | streamId: {}, generation: {}, source: first_resumption_event, requestFlightCount: {}",
                    broadcastStreamId, generation, requestFlightCount);

            ObjectNode requestNode = objectMapper.createObjectNode();
            ObjectNode clientContentNode = requestNode.putObject("clientContent");
            ArrayNode turnsNode = clientContentNode.putArray("turns");
            ObjectNode turnNode = turnsNode.addObject();
            turnNode.put("role", "user");
            ArrayNode partsNode = turnNode.putArray("parts");
            partsNode.addObject().put("text", FIRST_RESUMPTION_EVENT_TEXT);
            clientContentNode.put("turnComplete", true);

            String requestPayload = objectMapper.writeValueAsString(requestNode);
            bundle.getGeminiHandler().recordOutboundMessage("FIRST_RESUMPTION_EVENT", requestPayload);
            bundle.getGeminiSession().sendMessage(new TextMessage(requestPayload));

            /*
                3. sessionResumptionUpdate 미수신 상황에 대비해 timeout 정리를 예약한다.
                - timeout 시 아직 first resumption event가 진행 중이면 상태와 request-flight를 정리한다.
             */
            taskScheduler.schedule(
                    () -> handleFirstResumptionEventTimeout(broadcastStreamId, generation, bundle),
                    Instant.now().plusMillis(liveFirstResumptionTimeoutMs)
            );
        } catch (Exception e) {
            clearFirstResumptionEventState(bundle, true);
            log.error("[BroadcastGeminiRequestService] getFirstResumptionEvent() - Failed | streamId: {}, generation: {}, error: {}",
                    broadcastStreamId, generation, e.getMessage(), e);
            throw new CustomException(BroadcastErrorCode.GEMINI_RESPONSE_FAILED);
        }

        log.info("[BroadcastGeminiRequestService] getFirstResumptionEvent() - END | streamId: {}, generation: {}",
                broadcastStreamId, generation);
    }

    /**
     * 시청자 채팅 메시지를 Gemini 세션의 초기 컨텍스트로 전달한다.
     * - 모델 응답을 생성하지 않도록 clientContent/turnComplete:false 형식으로 전송한다.
     * - request-flight는 증가시키지 않는다.
     * @param message : CHZZK 채팅 메시지 DTO
     */
    public void sendViewerChatRequest(ChzzkChatMessageDto message) {
        log.info("[BroadcastGeminiRequestService] sendViewerChatRequest() - START | streamId: {}",
                message != null ? message.broadcastStreamId() : null);

        /*
            1. 방송 스트림에 연결된 현재 Session Bundle을 확인한다.
            - Gemini 전송이 불가능한 상태면 로깅 후 종료한다.
         */
        if (message == null || message.broadcastStreamId() == null) {
            log.info("[BroadcastGeminiRequestService] sendViewerChatRequest() - END | action: message_invalid");
            return;
        }

        BroadcastWebSocketSessionBundle bundle = sessionRegistry.getSessionBundle(message.broadcastStreamId());
        if (bundle == null || !bundle.canSendToGemini() || bundle.getGeminiSession() == null) {
            log.info("[BroadcastGeminiRequestService] sendViewerChatRequest() - END | streamId: {}, action: skip",
                    message.broadcastStreamId());
            return;
        }

        try {
            /*
                2. 시청자/스트리머 메시지를 clientContent payload로 구성해 Gemini에 전송한다.
                - turnComplete:false로 유지하여 모델 응답 생성 없이 컨텍스트만 적재한다.
             */
            ObjectNode requestNode = objectMapper.createObjectNode();
            ObjectNode clientContentNode = requestNode.putObject("clientContent");
            ArrayNode turnsNode = clientContentNode.putArray("turns");
            ObjectNode turnNode = turnsNode.addObject();
            turnNode.put("role", "user");
            ArrayNode partsNode = turnNode.putArray("parts");
            partsNode.addObject().put("text", buildViewerChatText(message));
            clientContentNode.put("turnComplete", false);

            String requestPayload = objectMapper.writeValueAsString(requestNode);
            if (bundle.getGeminiHandler() != null) {
                bundle.getGeminiHandler().recordOutboundMessage("VIEWER_CHAT_REQUEST", requestPayload);
            }
            bundle.getGeminiSession().sendMessage(new TextMessage(requestPayload));
        } catch (Exception e) {
            log.error("[BroadcastGeminiRequestService] sendViewerChatRequest() - Failed | streamId: {}, error: {}",
                    message.broadcastStreamId(), e.getMessage(), e);
        }

        log.info("[BroadcastGeminiRequestService] sendViewerChatRequest() - END | streamId: {}",
                message.broadcastStreamId());
    }

    /**
     * 선제 채팅 후보 블록을 Gemini Live의 clientContent 턴으로 전송한다.
     * - 후보 블록이 비어 있거나 현재 세션이 전송 불가 상태면 전송하지 않는다.
     * - Gemini가 판단 턴을 완료할 수 있도록 turnComplete=true로 전달한다.
     *
     * @param broadcastStreamId : 방송 스트림 ID
     * @param generation : 현재 세션 generation
     * @param candidateBlock : 선제 반응 후보 채팅 블록
     * @return : 전송 성공 여부
     */
    public boolean sendProactiveChatRequest(String broadcastStreamId, long generation, String candidateBlock) {
        log.info("[BroadcastGeminiRequestService] sendProactiveChatRequest() - START | streamId: {}, generation: {}, candidateLength: {}",
                broadcastStreamId, generation, candidateBlock != null ? candidateBlock.length() : null);

        BroadcastWebSocketSessionBundle bundle = sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation);
        if (bundle == null || candidateBlock == null || candidateBlock.isBlank()) {
            log.info("[BroadcastGeminiRequestService] sendProactiveChatRequest() - END | streamId: {}, generation: {}, action: skip_invalid",
                    broadcastStreamId, generation);
            return false;
        }

        synchronized (bundle) {
            if (!bundle.canSendToGemini() || bundle.getRequestFlightCountValue() > 0 || bundle.getGeminiSession() == null) {
                log.info("[BroadcastGeminiRequestService] sendProactiveChatRequest() - END | streamId: {}, generation: {}, action: skip_unavailable",
                        broadcastStreamId, generation);
                return false;
            }
            int requestFlightCount = bundle.incrementRequestFlight();
            log.info("[BroadcastGeminiRequestService] sendProactiveChatRequest() - Request-flight incremented | streamId: {}, generation: {}, source: proactive_chat_request, requestFlightCount: {}",
                    broadcastStreamId, generation, requestFlightCount);
            try {
                ObjectNode requestNode = objectMapper.createObjectNode();
                ObjectNode clientContentNode = requestNode.putObject("clientContent");
                ArrayNode turnsNode = clientContentNode.putArray("turns");
                ObjectNode turnNode = turnsNode.addObject();
                turnNode.put("role", "user");
                turnNode.putArray("parts").addObject().put("text", """
                        [SYSTEM_CONTROL:PROACTIVE_CHAT_CANDIDATES]
                        The following messages are viewer chat candidates, not streamer speech.
                        Respond briefly only if at least one message is genuinely funny, surprising, contextually relevant, or worth answering on stream.
                        For greetings, repetition, spam, simple reactions, or contextless messages, call skip_proactive_chat_response and produce no text or audio.

                        """ + candidateBlock);
                clientContentNode.put("turnComplete", true);

                String payload = objectMapper.writeValueAsString(requestNode);
                if (bundle.getGeminiHandler() != null) {
                    bundle.getGeminiHandler().recordOutboundMessage("PROACTIVE_CHAT_REQUEST", payload);
                }
                bundle.getGeminiSession().sendMessage(new TextMessage(payload));
                log.info("[BroadcastGeminiRequestService] sendProactiveChatRequest() - END | streamId: {}, generation: {}, action: sent",
                        broadcastStreamId, generation);
                return true;
            } catch (Exception e) {
                int remainingRequestFlightCount = bundle.decrementRequestFlight();
                log.info("[BroadcastGeminiRequestService] sendProactiveChatRequest() - Request-flight decremented | streamId: {}, generation: {}, source: proactive_chat_request_send_failed, requestFlightCount: {}",
                        broadcastStreamId, generation, remainingRequestFlightCount);
                log.error("[BroadcastGeminiRequestService] sendProactiveChatRequest() - Failed | streamId: {}, generation: {}, error: {}",
                        broadcastStreamId, generation, e.getMessage(), e);
                log.info("[BroadcastGeminiRequestService] sendProactiveChatRequest() - END | streamId: {}, generation: {}, action: failed",
                        broadcastStreamId, generation);
                return false;
            }
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
        log.info("[BroadcastGeminiRequestService] sendInterruptRequest() - START | streamId: {}, generation: {}",
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
            String requestPayload = objectMapper.writeValueAsString(requestNode);
            BroadcastWebSocketSessionBundle bundle = sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation);
            if (bundle != null && bundle.getGeminiHandler() != null) {
                bundle.getGeminiHandler().recordOutboundMessage("INTERRUPT_REQUEST", requestPayload);
            }

            geminiSession.sendMessage(new TextMessage(requestPayload));

            log.info("[BroadcastGeminiRequestService] sendInterruptRequest() - END | streamId: {}, generation: {}, action: sent",
                    broadcastStreamId, generation);
        } catch (Exception e) {
            log.error("[BroadcastGeminiRequestService] sendInterruptRequest() - Failed | streamId: {}, generation: {}, error: {}",
                    broadcastStreamId, generation, e.getMessage());
            log.info("[BroadcastGeminiRequestService] sendInterruptRequest() - END | streamId: {}, generation: {}, action: failed",
                    broadcastStreamId, generation);
            // 인터럽트 요청 실패는 세션을 종료하지 않고 로깅만 수행한다.
        }
    }


    /**
     * 단일 스트리머 발화를 Gemini 입력 문자열로 포맷한다.
     * @param clientMessage : 스트리머 발화 원문
     * @return : (스트리머) 접두어가 포함된 문자열
     */
    private String formatStreamerMessage(String clientMessage) {
        return "(스트리머)" + clientMessage;
    }

    private String buildViewerChatText(ChzzkChatMessageDto message) {
        if (message.userRoleCode() != null && message.userRoleCode().equalsIgnoreCase("streamer")) {
            return formatStreamerMessage(message.content());
        }
        return String.format("(시청자, %s)%s", message.nickname(), message.content());
    }

    private void handleFirstResumptionEventTimeout(
            String broadcastStreamId,
            long generation,
            BroadcastWebSocketSessionBundle requestOwnerBundle
    ) {
        log.info("[BroadcastGeminiRequestService] handleFirstResumptionEventTimeout() - START | streamId: {}, generation: {}",
                broadcastStreamId, generation);

        /*
            1. timeout 시점에도 동일 generation의 bundle/handler가 유지되는지 확인한다.
            - 현재 bundle이 아니거나 first resumption event가 이미 종료된 경우 정리 없이 종료한다.
         */
        BroadcastWebSocketSessionBundle currentBundle = sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation);
        if (currentBundle == null
                || currentBundle != requestOwnerBundle
                || currentBundle.getGeminiHandler() == null
                || currentBundle.getGeminiHandler().isGeminiSessionFirstResumptionEnd()) {
            log.info("[BroadcastGeminiRequestService] handleFirstResumptionEventTimeout() - END | streamId: {}, action: skip",
                    broadcastStreamId);
            return;
        }

        /*
            2. 아직 first resumption event가 진행 중이면 상태와 request-flight를 정리한다.
            - 이후 일반 메시지 처리는 정상적으로 진행될 수 있도록 in-progress 플래그를 해제한다.
         */
        clearFirstResumptionEventState(currentBundle, true);
        log.warn("[BroadcastGeminiRequestService] handleFirstResumptionEventTimeout() - Timeout cleanup applied | streamId: {}, generation: {}",
                broadcastStreamId, generation);
        log.info("[BroadcastGeminiRequestService] handleFirstResumptionEventTimeout() - END | streamId: {}, generation: {}",
                broadcastStreamId, generation);
    }

    private void clearFirstResumptionEventState(BroadcastWebSocketSessionBundle bundle, boolean decrementRequestFlight) {
        if (bundle == null || bundle.getGeminiHandler() == null) {
            return;
        }

        bundle.getGeminiHandler().clearFirstResumptionEventInProgress();
        bundle.getGeminiHandler().clearAccumulator();
        if (decrementRequestFlight && bundle.getRequestFlightCountValue() > 0) {
            int remainingRequestFlightCount = bundle.decrementRequestFlight();
            log.info("[BroadcastGeminiRequestService] clearFirstResumptionEventState() - Request-flight decremented | source: first_resumption_cleanup, requestFlightCount: {}",
                    remainingRequestFlightCount);
        }
    }

    /**
     * Gemini Live WebSocket 세션으로 realtimeInput.text를 전송한다.
     * - 현재 generation과 READY 상태를 확인한 후 request-flight를 증가시키고 전송한다.
     * - 전송 실패 시 request-flight를 복구한다.
     * @param broadcastStreamId : 방송 스트림 ID
     * @param generation : 현재 세션 generation
     * @param character : 방송 캐릭터 정보
     * @param text : Gemini로 전송할 realtimeInput.text 문자열
     */
    private void sendRealtimeText(
            String broadcastStreamId,
            long generation,
            BroadcastCharacterRedisDto character,
            String text
    ) {
        BroadcastWebSocketSessionBundle bundle = sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation);
        if (bundle == null || !bundle.canSendToGemini()) {
            throw new CustomException(BroadcastErrorCode.WEBSOCKET_SESSION_NOT_READY);
        }

        try {
            int requestFlightCount = bundle.incrementRequestFlight();
            log.info("[BroadcastGeminiRequestService] sendRealtimeText() - Request-flight incremented | streamId: {}, generation: {}, source: realtime_input, requestFlightCount: {}",
                    broadcastStreamId, generation, requestFlightCount);
            ObjectNode requestNode = objectMapper.createObjectNode();
            ObjectNode realtimeInputNode = requestNode.putObject("realtimeInput");
            realtimeInputNode.put("text", text);
            String requestPayload = objectMapper.writeValueAsString(requestNode);

            WebSocketSession geminiSession = bundle.getGeminiSession();
            if (bundle.getGeminiHandler() != null) {
                bundle.getGeminiHandler().recordOutboundMessage("REALTIME_INPUT", requestPayload);
            }
            geminiSession.sendMessage(new TextMessage(requestPayload));
        } catch (Exception e) {
            int remainingRequestFlightCount = bundle.decrementRequestFlight();
            log.info("[BroadcastGeminiRequestService] sendRealtimeText() - Request-flight decremented | streamId: {}, generation: {}, source: realtime_input_send_failed, requestFlightCount: {}",
                    broadcastStreamId, generation, remainingRequestFlightCount);
            log.error("[BroadcastGeminiRequestService] sendRealtimeText() - Failed | streamId: {}, generation: {}, characterId: {}, error: {}",
                    broadcastStreamId,
                    generation,
                    character != null ? character.getCharacterId() : null,
                    e.getMessage());
            throw new CustomException(BroadcastErrorCode.GEMINI_RESPONSE_FAILED);
        }
    }
}
