package com.example.sku_sw.domain.broadcast.websocket;

import com.example.sku_sw.domain.broadcast.dto.BroadcastInfoRedisDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastMessageReqDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastUserRedisDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastWebSocketErrorResDto;
import com.example.sku_sw.domain.broadcast.entity.Broadcast;
import com.example.sku_sw.domain.broadcast.enums.BroadcastErrorCode;
import com.example.sku_sw.domain.broadcast.enums.BroadcastStatus;
import com.example.sku_sw.domain.broadcast.enums.DialogueSubject;
import com.example.sku_sw.domain.broadcast.enums.WebSocketAttributes;
import com.example.sku_sw.domain.broadcast.enums.WebSocketSessionBundleStatus;
import com.example.sku_sw.domain.broadcast.repository.BroadcastRepository;
import com.example.sku_sw.domain.broadcast.service.BroadcastConnectionTimeoutService;
import com.example.sku_sw.domain.broadcast.service.BroadcastDialoguePersistenceService;
import com.example.sku_sw.domain.broadcast.service.gemini.BroadcastGeminiBootstrapService;
import com.example.sku_sw.domain.broadcast.service.BroadcastMessageService;
import com.example.sku_sw.domain.broadcast.service.gemini.BroadcastGeminiRequestService;
import com.example.sku_sw.domain.broadcast.util.BroadcastRedisUtil;
import com.example.sku_sw.domain.chat.dto.FastApiChzzkRedisChannelReqDto;
import com.example.sku_sw.domain.chat.util.ChatRedisUtil;
import com.example.sku_sw.domain.chat.util.FastApiUtil;
import com.example.sku_sw.domain.broadcast.websocket.gemini.GeminiLiveWebSocketHandler;
import com.example.sku_sw.global.exception.CustomException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

/**
 * WebSocket 핸들러
 * - 클라이언트와의 WebSocket 연결을 관리한다.
 * - 음성 데이터(바이너리)와 메타데이터(JSON)를 송수신한다.
 * - 주기적인 Ping/Pong으로 클라이언트 생존 여부를 확인한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BroadcastWebSocketHandler extends AbstractWebSocketHandler {

    private static final long PONG_TIMEOUT_MS = 90_000;

    /** AI 응답 인터럽트 요청 prefix — 프론트→서버 JSON message 필드에서 이 prefix로 시작하면 인터럽트 요청으로 간주한다. */
    private static final String AI_RESPONSE_INTERRUPT_PREFIX = "__AI_RESPONSE_INTERRUPT_REQUEST__:";

    private final ObjectMapper objectMapper;
    private final BroadcastRedisUtil broadcastRedisUtil;
    private final BroadcastConnectionTimeoutService broadcastConnectionTimeoutService;
    private final BroadcastWebSocketSessionRegistry sessionRegistry;
    private final BroadcastMessageService broadcastMessageService;
    private final BroadcastGeminiBootstrapService broadcastGeminiBootstrapService;
    private final BroadcastDialoguePersistenceService broadcastDialoguePersistenceService;
    private final BroadcastRepository broadcastRepository;
    private final TransactionTemplate transactionTemplate;
    private final BroadcastGeminiRequestService broadcastGeminiRequestService;
    private final ChatRedisUtil chatRedisUtil;
    private final FastApiUtil fastApiUtil;

    @Override
    public void afterConnectionEstablished(WebSocketSession clientSession) {
        String broadcastStreamId = (String) clientSession.getAttributes().get(WebSocketAttributes.BROADCAST_STREAM_ID.getValue());
        Long userId = (Long) clientSession.getAttributes().get(WebSocketAttributes.USER_ID.getValue());

        // 1. Redis에 방송 캐릭터 정보 없으면 Client WebSocket Session 닫고 sessionBundle SessionRegistry에서 삭제
        if (!broadcastRedisUtil.hasBroadcastCharacterValue(broadcastStreamId)) {
            log.warn("[BroadcastWebSocketHandler] afterConnectionEstablished() - Redis key not found, closing session | userId: {}, streamId: {}",
                    userId, broadcastStreamId);
            closeSessionQuietly(clientSession, CloseStatus.POLICY_VIOLATION.withReason("Broadcast not available"), "afterConnectionEstablished", "Client");
            sessionRegistry.removeSessionBundle(
                    broadcastStreamId,
                    clientSession,
                    CloseStatus.POLICY_VIOLATION.withReason("Broadcast not available"),
                    "afterConnectionEstablished"
            );
            return;
        }

        /*
            2. 이전 Session Bundle과 새로 생성한 Session Bundle을 가져와, Session Registry에 Session Bundle이 정상적으로 등록되었는지 확인한다.
            - 만약 Session Registry에 Session Bundle이 정상적으로 등록되지 않았다면 클라이언트에게 에러 메시지를 전송하고 Client WebSocket Session을 닫는다.
         */
        BroadcastWebSocketSessionBundle oldBundle = sessionRegistry.registerClientSession(broadcastStreamId, clientSession);
        BroadcastWebSocketSessionBundle currentBundle = sessionRegistry.getSessionBundle(broadcastStreamId);
        if (currentBundle == null) {
            sendErrorAndClose(clientSession, BroadcastErrorCode.WEBSOCKET_CONNECTION_NOT_FOUND.getMessage());
            return;
        }

        // 3. Client WebSocket Session에 SESSION_GENERATION, LAST_PONG_AT 속성을 설정하고, ConnectionTimeout 스케줄러를 삭제한다.
        long generation = currentBundle.getGeneration();
        clientSession.getAttributes().put(WebSocketAttributes.SESSION_GENERATION.getValue(), generation);
        clientSession.getAttributes().put(WebSocketAttributes.LAST_PONG_AT.getValue(), Instant.now());
        broadcastConnectionTimeoutService.cancelConnectionTimeout(broadcastStreamId);

        // 4. 오래된 session Bundle을 종료하고 Client에게 GEMINI_CONNECTING 메시지를 보낸다. 이후 Gemini WebSocket 초기 세팅 비동기 작업을 시작한다.
        closeOldBundle(oldBundle, broadcastStreamId);
        sendStatusMessage(clientSession, WebSocketSessionBundleStatus.GEMINI_CONNECTING.name(), "WebSocket 연결 대기중");
        broadcastGeminiBootstrapService.bootstrapGeminiAsync(broadcastStreamId, clientSession, generation);

        log.info("[BroadcastWebSocketHandler] afterConnectionEstablished() - Session registered | userId: {}, streamId: {}, generation: {}",
                userId, broadcastStreamId, generation);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String broadcastStreamId = (String) session.getAttributes().get(WebSocketAttributes.BROADCAST_STREAM_ID.getValue());
        Long userId = (Long) session.getAttributes().get(WebSocketAttributes.USER_ID.getValue());
        Long generation = (Long) session.getAttributes().get(WebSocketAttributes.SESSION_GENERATION.getValue());

        log.info("[BroadcastWebSocketHandler] handleTextMessage() - Received | userId: {}, streamId: {}, generation: {}, payload: {}",
                userId, broadcastStreamId, generation, message.getPayload());

        // 1. Session Registry에서 해당 Client WebSocket이 포함된 Session Bundle을 가져온다.
        BroadcastWebSocketSessionBundle bundle = sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation != null ? generation : -1L);
        if (bundle == null || !bundle.matchesClientSession(session) || !bundle.canAcceptClientMessage()) {
            sendStatusMessage(session, WebSocketSessionBundleStatus.GEMINI_CONNECTING.name(), BroadcastErrorCode.WEBSOCKET_SESSION_NOT_READY.getMessage());
            return;
        }

        BroadcastMessageReqDto reqDto;
        try {
            reqDto = objectMapper.readValue(message.getPayload(), BroadcastMessageReqDto.class);
        } catch (Exception e) {
            log.error("[BroadcastWebSocketHandler] handleTextMessage() - JSON parse failed | userId: {}, streamId: {}, error: {}",
                    userId, broadcastStreamId, e.getMessage());
            abnormalTerminateBroadcast(broadcastStreamId);
            sendErrorAndClose(session, "Invalid JSON format");
            return;
        }

        if (reqDto.message() == null || reqDto.message().isBlank()) {
            log.info("[BroadcastWebSocketHandler] handleTextMessage() - Empty message, ignored | userId: {}, streamId: {}",
                    userId, broadcastStreamId);
            return;
        }

        // AI 응답 인터럽트 요청 prefix 감지 — 일반 메시지 흐름으로 처리하지 않고 즉시 분기한다.
        if (reqDto.message().startsWith(AI_RESPONSE_INTERRUPT_PREFIX)) {
            handleInterruptRequest(session, broadcastStreamId, userId, generation, reqDto.message());
            return;
        }

        try {
            // AI 응답 인터럽트 요청이 아닌 경우, 일반 Client Message
            broadcastMessageService.handleClientMessage(broadcastStreamId, generation, reqDto.message());
        } catch (CustomException e) {
            log.error("[BroadcastWebSocketHandler] handleTextMessage() - CustomException | userId: {}, streamId: {}, errorCode: {}",
                    userId, broadcastStreamId, e.getErrorCode().getMessage());
            if (e.getErrorCode() == BroadcastErrorCode.WEBSOCKET_SESSION_NOT_READY) {
                sendStatusMessage(session, WebSocketSessionBundleStatus.GEMINI_CONNECTING.name(), e.getErrorCode().getMessage());
                return;
            }

            abnormalTerminateBroadcast(broadcastStreamId);
            sendErrorAndClose(session, e.getErrorCode().getMessage());
            sessionRegistry.removeSessionBundleIfCurrent(
                    broadcastStreamId,
                    generation != null ? generation : -1L,
                    CloseStatus.POLICY_VIOLATION.withReason(e.getErrorCode().getMessage()),
                    "handleTextMessage"
            );
        } catch (Exception e) {
            log.error("[BroadcastWebSocketHandler] handleTextMessage() - Unexpected error | userId: {}, streamId: {}, error: {}",
                    userId, broadcastStreamId, e.getMessage());
            abnormalTerminateBroadcast(broadcastStreamId);
            sendErrorAndClose(session, "Internal server error");
            sessionRegistry.removeSessionBundleIfCurrent(
                    broadcastStreamId,
                    generation != null ? generation : -1L,
                    CloseStatus.SERVER_ERROR.withReason("Internal server error"),
                    "handleTextMessage"
            );
        }
    }

    /**
     * AI 응답 인터럽트 요청을 처리한다.
     * - 요청 prefix: __AI_RESPONSE_INTERRUPT_REQUEST__:{JSON}
     * - JSON payload: {"turnNumber": 12, "reason": "STREAMER_SPEECH_START"}
     *
     * 처리 순서:
     * 1. turnNumber 검증 및 중복/상태 검사
     * 2. accumulator.markInterrupting()
     * 3. 인터럽트 텍스트 결정 → Redis 저장 (BroadcastRedisUtil.pushBroadcastInfo)
     * 4. compaction check 이벤트 발행
     * 5. accumulator에 cursorId와 interruptedText 저장
     * 6. accumulator.markInterrupted()
     * 7. Gemini로 인터럽트 요청 전송
     * 8. return (일반 메시지 흐름으로 가지 않음)
     */
    private void handleInterruptRequest(
            WebSocketSession clientSession,
            String broadcastStreamId,
            Long userId,
            Long generation,
            String message
    ) {
        log.info("[BroadcastWebSocketHandler] handleInterruptRequest() - START | streamId: {}, userId: {}", broadcastStreamId, userId);

        // 1. Interrupt payload 파싱
        String jsonPayload = message.substring(AI_RESPONSE_INTERRUPT_PREFIX.length());
        JsonNode interruptNode;
        long requestedTurnNumber;
        String requestedInterruptReason;
        try {
            interruptNode = objectMapper.readTree(jsonPayload);
            requestedTurnNumber = interruptNode.get("turnNumber").asLong();
            requestedInterruptReason = interruptNode.get("reason").asText();
        } catch (Exception e) {
            log.warn("[BroadcastWebSocketHandler] handleInterruptRequest() - Failed to parse interrupt payload | streamId: {}, payload: {}",
                    broadcastStreamId, jsonPayload);
            return;
        }

        // 2. Session Bundle 검증
        BroadcastWebSocketSessionBundle bundle = sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation != null ? generation : -1L);
        if (bundle == null || !bundle.matchesClientSession(clientSession) || !bundle.canAcceptClientMessage()) {
            log.warn("[BroadcastWebSocketHandler] handleInterruptRequest() - Bundle not ready | streamId: {}, generation: {}",
                    broadcastStreamId, generation);
            return;
        }

        // 3. Gemini Handler 조회 (BroadcastWebSocketSessionBundle에 직접 저장된 handler 사용)
        WebSocketSession geminiSession = bundle.getGeminiSession();
        if (geminiSession == null || !geminiSession.isOpen()) {
            log.warn("[BroadcastWebSocketHandler] handleInterruptRequest() - Gemini session not available | streamId: {}", broadcastStreamId);
            return;
        }
        GeminiLiveWebSocketHandler geminiHandler = bundle.getGeminiHandler();
        if (geminiHandler == null) {
            log.warn("[BroadcastWebSocketHandler] handleInterruptRequest() - Gemini handler not found | streamId: {}", broadcastStreamId);
            return;
        }

        // 4. Accumulator 조회
        GeminiLiveWebSocketHandler.GeminiTurnAccumulator accumulator = geminiHandler.getTurnAccumulator();
        if (accumulator == null) {
            log.warn("[BroadcastWebSocketHandler] handleInterruptRequest() - No active turn accumulator | streamId: {}, requestedTurn: {}",
                    broadcastStreamId, requestedTurnNumber);
            return;
        }

        // 5. turnNumber 검증
        if (!accumulator.getTurnNumber().equals(requestedTurnNumber)) {
            log.warn("[BroadcastWebSocketHandler] handleInterruptRequest() - Turn number mismatch | streamId: {}, requested: {}, current: {}",
                    broadcastStreamId, requestedTurnNumber, accumulator.getTurnNumber());
            return;
        }

        // 6. 중복/완료 상태 검증
        if (accumulator.isInterruptingOrInterrupted()) {
            log.warn("[BroadcastWebSocketHandler] handleInterruptRequest() - Already interrupting/interrupted | streamId: {}, turn: {}, status: {}",
                    broadcastStreamId, requestedTurnNumber, accumulator.getStatus());
            return;
        }
        if (accumulator.getStatus() == GeminiLiveWebSocketHandler.GeminiTurnAccumulator.GeminiTurnStatus.COMPLETED) {
            log.warn("[BroadcastWebSocketHandler] handleInterruptRequest() - Turn already completed | streamId: {}, turn: {}",
                    broadcastStreamId, requestedTurnNumber);
            return;
        }

        // 7. 인터럽트 처리 수행
        accumulator.markInterrupting();

        String interruptedText;
        if (accumulator.getAccumulatedText() == null || accumulator.getAccumulatedText().isBlank()) {
            interruptedText = "[응답 중단됨]";
        } else {
            interruptedText = accumulator.getAccumulatedText() + " [응답 중단됨]";
        }

        // 8. Redis 저장
        BroadcastInfoRedisDto savedInfo;
        try {
            savedInfo = broadcastRedisUtil.pushBroadcastInfo(
                    broadcastStreamId,
                    DialogueSubject.AI_CHARACTER,
                    interruptedText,
                    accumulator.getEmotion(),
                    true
            );
        } catch (Exception e) {
            log.error("[BroadcastWebSocketHandler] handleInterruptRequest() - Redis save failed | streamId: {}, error: {}",
                    broadcastStreamId, e.getMessage());
            return;
        }

        // 9. Accumulator에 인터럽트 정보 저장 (Gemini가 interrupted:true 응답 시 사용)
        accumulator.setInterruptedCursorId(savedInfo.cursorId());
        accumulator.setInterruptedText(interruptedText);

        // 10. 상태를 INTERRUPTED로 전환
        accumulator.markInterrupted();

        // 11. Gemini로 인터럽트 요청 전송
        try {
            broadcastGeminiRequestService.sendInterruptRequest(
                    geminiSession,
                    broadcastStreamId,
                    generation != null ? generation : -1L
            );
        } catch (Exception e) {
            log.warn("[BroadcastWebSocketHandler] handleInterruptRequest() - Gemini interrupt send failed | streamId: {}, error: {}",
                    broadcastStreamId, e.getMessage());
        }

        log.info("[BroadcastWebSocketHandler] handleInterruptRequest() - END | streamId: {}, turn: {}, cursorId: {}",
                broadcastStreamId, requestedTurnNumber, savedInfo.cursorId());
    }

    /**
     * 비정상 방송 종료 시 방송 관련 설정들을 초기화 및 종료하는 함수
     * @param broadcastStreamId
     */
    private void abnormalTerminateBroadcast(String broadcastStreamId) {
        log.info("[BroadcastWebSocketHandler] abnormalTerminateBroadcast() - START | streamId: {}", broadcastStreamId);

        try {
            transactionTemplate.executeWithoutResult(status -> {
                Broadcast broadcast = broadcastRepository.findByStreamIdAndStatusForUpdate(
                                broadcastStreamId,
                                BroadcastStatus.BROADCASTING
                        )
                        .orElse(null);

                if (broadcast == null) {
                    log.warn("[BroadcastWebSocketHandler] abnormalTerminateBroadcast() - Active broadcast not found | streamId: {}", broadcastStreamId);
                    return;
                }

                broadcast.abnormalTerminate();
            });

            try {
                broadcastDialoguePersistenceService.saveRemainingRedisDialogues(broadcastStreamId);
            } catch (Exception e) {
                log.error("[BroadcastWebSocketHandler] abnormalTerminateBroadcast() - Remaining dialogue save failed | streamId: {}, error: {}",
                        broadcastStreamId, e.getMessage(), e);
            }

            BroadcastUserRedisDto broadcastUserRedisDto = broadcastRedisUtil.getBroadcastUserDto(broadcastStreamId);

            if (broadcastUserRedisDto != null) {
                if (broadcastUserRedisDto.getChannelName() != null && !broadcastUserRedisDto.getChannelName().isBlank()) {
                    try {
                        fastApiUtil.disconnectChzzkRedisChannel(new FastApiChzzkRedisChannelReqDto(
                                broadcastStreamId,
                                broadcastUserRedisDto.getSessionKey(),
                                broadcastUserRedisDto.getChannelName()
                        ));
                    } catch (Exception e) {
                        log.error("[BroadcastWebSocketHandler] abnormalTerminateBroadcast() - FastAPI disconnect failed | streamId: {}, error: {}",
                                broadcastStreamId, e.getMessage(), e);
                    }
                }

                if (broadcastUserRedisDto.getChannelId() != null && !broadcastUserRedisDto.getChannelId().isBlank()) {
                    chatRedisUtil.unsubscribeChannelPattern(broadcastUserRedisDto.getChannelId());
                }
            }

            broadcastRedisUtil.deleteBroadcastCharacterValue(broadcastStreamId);
            broadcastRedisUtil.deleteBroadcastUserValue(broadcastStreamId);
            broadcastRedisUtil.deleteBroadcastInfo(broadcastStreamId);
        } catch (Exception e) {
            log.error("[BroadcastWebSocketHandler] abnormalTerminateBroadcast() - Failed | streamId: {}, error: {}", broadcastStreamId, e.getMessage());
        }

        log.info("[BroadcastWebSocketHandler] abnormalTerminateBroadcast() - END | streamId: {}", broadcastStreamId);
    }

    private void sendError(WebSocketSession session, String errorMessage) {
        try {
            BroadcastWebSocketErrorResDto errorRes = BroadcastWebSocketErrorResDto.builder()
                    .error("ERROR")
                    .message(errorMessage)
                    .build();
            String errorJson = objectMapper.writeValueAsString(errorRes);
            session.sendMessage(new TextMessage(errorJson));
        } catch (Exception e) {
            log.warn("[BroadcastWebSocketHandler] sendError() - Failed to send error | error: {}", e.getMessage());
        }
    }

    private void sendErrorAndClose(WebSocketSession session, String errorMessage) {
        try {
            if (session.isOpen()) {
                sendError(session, errorMessage);
                session.close(CloseStatus.POLICY_VIOLATION.withReason(errorMessage));
            }
        } catch (IOException e) {
            log.warn("[BroadcastWebSocketHandler] sendErrorAndClose() - Failed to send error and close session | error: {}", e.getMessage());
        }
    }

    private void sendStatusMessage(WebSocketSession session, String status, String message) {
        broadcastGeminiBootstrapService.sendStatusMessage(session, status, message);
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        String broadcastStreamId = (String) session.getAttributes().get(WebSocketAttributes.BROADCAST_STREAM_ID.getValue());
        session.getAttributes().put(WebSocketAttributes.LAST_PONG_AT.getValue(), Instant.now());
        log.debug("[BroadcastWebSocketHandler] handlePongMessage() - Pong received | streamId: {}", broadcastStreamId);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        String broadcastStreamId = (String) session.getAttributes().get(WebSocketAttributes.BROADCAST_STREAM_ID.getValue());
        Long generation = (Long) session.getAttributes().get(WebSocketAttributes.SESSION_GENERATION.getValue());

        log.error("[BroadcastWebSocketHandler] handleTransportError() - Transport error | streamId: {}, generation: {}, error: {}",
                broadcastStreamId, generation, exception.getMessage());

        sessionRegistry.removeSessionBundleIfCurrent(
                broadcastStreamId,
                generation != null ? generation : -1L,
                CloseStatus.SERVER_ERROR.withReason("Client transport error"),
                "handleTransportError"
        );
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String broadcastStreamId = (String) session.getAttributes().get(WebSocketAttributes.BROADCAST_STREAM_ID.getValue());
        Long userId = (Long) session.getAttributes().get(WebSocketAttributes.USER_ID.getValue());
        Long generation = (Long) session.getAttributes().get(WebSocketAttributes.SESSION_GENERATION.getValue());

        sessionRegistry.removeSessionBundleIfCurrent(
                broadcastStreamId,
                generation != null ? generation : -1L,
                CloseStatus.NORMAL.withReason("Client connection closed"),
                "afterConnectionClosed"
        );

        log.info("[BroadcastWebSocketHandler] afterConnectionClosed() - Session removed | userId: {}, streamId: {}, generation: {}, status: {}",
                userId, broadcastStreamId, generation, status);
    }

    public int getActiveSessionCount() {
        return sessionRegistry.getActiveSessionCountFromBundle();
    }

    @Scheduled(fixedDelayString = "${websocket.ping.interval-ms:30000}")
    public void pingActiveSessions() {
        Instant now = Instant.now();
        int closedCount = 0;

        for (var entry : sessionRegistry.getActiveSessionBundlesSnapshot()) {
            String broadcastStreamId = entry.getKey();
            BroadcastWebSocketSessionBundle bundle = entry.getValue();
            WebSocketSession clientSession = bundle.getClientSession();

            if (clientSession == null || !clientSession.isOpen()) {
                sessionRegistry.removeSessionBundleIfCurrent(
                        broadcastStreamId,
                        bundle.getGeneration(),
                        CloseStatus.POLICY_VIOLATION.withReason("Client session closed"),
                        "pingActiveSessions"
                );
                closedCount++;
                continue;
            }

            Instant lastPongAt = (Instant) clientSession.getAttributes().get(WebSocketAttributes.LAST_PONG_AT.getValue());
            if (lastPongAt != null && Duration.between(lastPongAt, now).toMillis() > PONG_TIMEOUT_MS) {
                log.warn("[BroadcastWebSocketHandler] pingActiveSessions() - Pong timeout, closing session | streamId: {}", broadcastStreamId);
                sessionRegistry.removeSessionBundleIfCurrent(
                        broadcastStreamId,
                        bundle.getGeneration(),
                        CloseStatus.POLICY_VIOLATION.withReason("Pong timeout"),
                        "pingActiveSessions"
                );
                closedCount++;
                continue;
            }

            try {
                clientSession.sendMessage(new PingMessage());
                log.debug("[BroadcastWebSocketHandler] pingActiveSessions() - Ping sent | streamId: {}", broadcastStreamId);
            } catch (IOException e) {
                log.error("[BroadcastWebSocketHandler] pingActiveSessions() - Failed to send ping | streamId: {}", broadcastStreamId);
            }
        }

        if (closedCount > 0) {
            log.info("[BroadcastWebSocketHandler] pingActiveSessions() - Closed {} timed-out sessions", closedCount);
        }
    }

    private void closeOldBundle(BroadcastWebSocketSessionBundle oldBundle, String broadcastStreamId) {
        if (oldBundle == null) {
            return;
        }

        sessionRegistry.closeDetachedSessionBundle(
                oldBundle,
                CloseStatus.POLICY_VIOLATION.withReason("Replaced by new connection"),
                "afterConnectionEstablished",
                broadcastStreamId
        );
    }

    private void closeSessionQuietly(
            WebSocketSession session,
            CloseStatus closeStatus,
            String caller,
            String sessionLabel
    ) {
        if (session == null || !session.isOpen()) {
            return;
        }

        try {
            session.close(closeStatus);
        } catch (IOException e) {
            log.warn("[BroadcastWebSocketHandler] {}() - Failed to close {} session", caller, sessionLabel);
        }
    }
}
