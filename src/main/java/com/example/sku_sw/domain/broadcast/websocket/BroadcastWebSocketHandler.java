package com.example.sku_sw.domain.broadcast.websocket;

import com.example.sku_sw.domain.broadcast.dto.BroadcastMessageReqDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastVoiceMetadataResDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastWebSocketErrorResDto;
import com.example.sku_sw.domain.broadcast.entity.Broadcast;
import com.example.sku_sw.domain.broadcast.enums.BroadcastErrorCode;
import com.example.sku_sw.domain.broadcast.enums.BroadcastStatus;
import com.example.sku_sw.domain.broadcast.enums.WebSocketAttributes;
import com.example.sku_sw.domain.broadcast.repository.BroadcastRepository;
import com.example.sku_sw.domain.broadcast.service.BroadcastConnectionTimeoutService;
import com.example.sku_sw.domain.broadcast.service.BroadcastMessageService;
import com.example.sku_sw.domain.broadcast.util.BroadcastRedisUtil;
import com.example.sku_sw.global.exception.CustomException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
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

    private final ObjectMapper objectMapper;
    private final BroadcastRedisUtil broadcastRedisUtil;
    private final BroadcastConnectionTimeoutService broadcastConnectionTimeoutService;
    private final BroadcastWebSocketSessionRegistry sessionRegistry;
    private final BroadcastMessageService broadcastMessageService;
    private final BroadcastRepository broadcastRepository;
    private final TransactionTemplate transactionTemplate;

    /**
     * Pong 응답 타임아웃 (밀리초) - 기본 90초
     */
    private static final long PONG_TIMEOUT_MS = 90_000;

    /**
     * WebSocket 연결 수립 후 실행
     * - HandshakeInterceptor에서 검증이 끝나면, 해당 단계에서 지정해준 Attribute가 담긴 WebSocketSession 객체가 생성되어 전달된다.
     * - Redis에 해당 broadcastStreamId key가 존재하는지 확인 후, 존재하지 않으면 WebSocket 연결 실패로 간주한다.
     * - Redis key가 존재하면 세션을 등록하고 타임아웃을 취소한다.
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String broadcastStreamId = (String) session.getAttributes().get(WebSocketAttributes.BROADCAST_STREAM_ID.getValue());
        Long userId = (Long) session.getAttributes().get(WebSocketAttributes.USER_ID.getValue());

        /*
            1. Redis에 해당 broadcastStreamId key 존재 여부 확인
            - Redis key가 없다면 WebSocket 연결 실패로 간주하고 세션을 종료한다.
         */
        if (!broadcastRedisUtil.hasBroadcastCharacterValue(broadcastStreamId)) {
            log.warn("[BroadcastWebSocketHandler] afterConnectionEstablished() - Redis key not found, closing session | userId: {}, streamId: {}", userId, broadcastStreamId);
            try {
                session.close(CloseStatus.POLICY_VIOLATION.withReason("Broadcast not available"));
            } catch (IOException e) {
                log.warn("[BroadcastWebSocketHandler] afterConnectionEstablished() - Failed to close session | streamId: {}", broadcastStreamId);
            }
            sessionRegistry.removeSession(broadcastStreamId, session);
            return;
        }

        /*
            2. 기존 세션이 있으면 닫고 새 세션으로 교체
            - 동일 streamId로 재연결 시 이전 연결을 정리한다.
         */
        WebSocketSession oldSession = sessionRegistry.registerSession(broadcastStreamId, session);
        if (oldSession != null && oldSession.isOpen()) {
            try {
                oldSession.close(CloseStatus.POLICY_VIOLATION.withReason("Replaced by new connection"));
            } catch (IOException e) {
                log.warn("[BroadcastWebSocketHandler] afterConnectionEstablished() - Failed to close old session | streamId: {}", broadcastStreamId);
            }
        }

        /*
            3. Pong 응답 타임스탬프 초기화
         */
        session.getAttributes().put(WebSocketAttributes.LAST_PONG_AT.getValue(), Instant.now());

        /*
            4. 타임아웃 취소
            - WebSocket 연결 성공 시 등록된 타임아웃 작업을 취소한다.
         */
        broadcastConnectionTimeoutService.cancelConnectionTimeout(broadcastStreamId);

        log.info("[BroadcastWebSocketHandler] afterConnectionEstablished() - Session registered | userId: {}, streamId: {}", userId, broadcastStreamId);
    }

    /**
     * 클라이언트로부터 텍스트 메시지(JSON) 수신
     * - JSON 형식의 payload만 허용하며, plain text는 허용하지 않는다.
     * - BroadcastMessageReqDto로 파싱하여 BroadcastMessageService로 전달한다.
     * - JSON 파싱 실패 또는 Redis 데이터 없음 시 WebSocket 에러 응답 후 세션을 종료한다.
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        long startTime = System.currentTimeMillis();
        String broadcastStreamId = (String) session.getAttributes().get(WebSocketAttributes.BROADCAST_STREAM_ID.getValue());
        Long userId = (Long) session.getAttributes().get(WebSocketAttributes.USER_ID.getValue());

        log.info("[BroadcastWebSocketHandler] handleTextMessage() - Received | userId: {}, streamId: {}, payload: {}", userId, broadcastStreamId, message.getPayload());

        /*
            1. JSON 파싱
            - payload를 BroadcastMessageReqDto로 파싱한다.
            - JSON 형식이 아니거나 message 필드가 없으면 WebSocket 에러 응답 후 세션을 종료한다.
         */
        BroadcastMessageReqDto reqDto;
        try {
            reqDto = objectMapper.readValue(message.getPayload(), BroadcastMessageReqDto.class);
        } catch (Exception e) {
            log.error("[BroadcastWebSocketHandler] handleTextMessage() - JSON parse failed | userId: {}, streamId: {}, error: {}", userId, broadcastStreamId, e.getMessage());
            abnormalTerminateBroadcast(broadcastStreamId);
            sendErrorAndClose(session, "Invalid JSON format");
            return;
        }

        /*
            2. message 필드 검증
            - message가 null 또는 blank이면 Redis 저장 없이 그냥 return한다.
         */
        if (reqDto.message() == null || reqDto.message().isBlank()) {
            log.info("[BroadcastWebSocketHandler] handleTextMessage() - Empty message, ignored | userId: {}, streamId: {}", userId, broadcastStreamId);
            return;
        }

        /*
            3. BroadcastMessageService 호출
            - 파싱된 메시지를 BroadcastMessageService로 전달하여 비즈니스 로직을 처리한다.
            - Redis 캐릭터 정보가 없으면 CustomException이 발생하며, 에러 응답 후 방송 종료 처리한다.
         */
        try {
            broadcastMessageService.handleClientMessage(broadcastStreamId, reqDto.message(), startTime);
        } catch (CustomException e) {
            log.error("[BroadcastWebSocketHandler] handleTextMessage() - CustomException | userId: {}, streamId: {}, errorCode: {}", userId, broadcastStreamId, e.getErrorCode().getMessage());
            abnormalTerminateBroadcast(broadcastStreamId);
            sendErrorAndClose(session, e.getErrorCode().getMessage());
            sessionRegistry.removeSession(broadcastStreamId, session);
        } catch (Exception e) {
            log.error("[BroadcastWebSocketHandler] handleTextMessage() - Unexpected error | userId: {}, streamId: {}, error: {}", userId, broadcastStreamId, e.getMessage());
            abnormalTerminateBroadcast(broadcastStreamId);
            sendErrorAndClose(session, "Internal server error");
            sessionRegistry.removeSession(broadcastStreamId, session);
        }
    }

    /**
     * WebSocket 메시지 처리 중 예외가 발생한 방송을 비정상 종료 처리한다.
     * - TransactionTemplate으로 별도 트랜잭션을 열어 방송 상태를 ABNORMAL_TERMINATED로 변경한다.
     * - 이미 방송 중 상태가 아니거나 방송이 없으면 상태 변경 없이 로그만 남긴다.
     *
     * @param broadcastStreamId : 비정상 종료 처리할 방송 스트림 ID
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
        } catch (Exception e) {
            log.error("[BroadcastWebSocketHandler] abnormalTerminateBroadcast() - Failed | streamId: {}, error: {}", broadcastStreamId, e.getMessage());
        }

        log.info("[BroadcastWebSocketHandler] abnormalTerminateBroadcast() - END | streamId: {}", broadcastStreamId);
    }

    /**
     * WebSocket 세션에 에러 응답을 전송하고 세션을 종료한다.
     *
     * @param session      : WebSocket 세션
     * @param errorMessage : 에러 메시지
     */
    private void sendErrorAndClose(WebSocketSession session, String errorMessage) {
        try {
            BroadcastWebSocketErrorResDto errorRes = BroadcastWebSocketErrorResDto.builder()
                    .error("ERROR")
                    .message(errorMessage)
                    .build();
            String errorJson = objectMapper.writeValueAsString(errorRes);
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(errorJson));
                session.close(CloseStatus.POLICY_VIOLATION.withReason(errorMessage));
            }
        } catch (IOException e) {
            log.warn("[BroadcastWebSocketHandler] sendErrorAndClose() - Failed to send error and close session | error: {}", e.getMessage());
        }
    }

    /**
     * 클라이언트의 Pong 응답 처리
     * - lastPongAt 속성을 현재 시간으로 업데이트한다.
     */
    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        String broadcastStreamId = (String) session.getAttributes().get(WebSocketAttributes.BROADCAST_STREAM_ID.getValue());

        session.getAttributes().put(WebSocketAttributes.LAST_PONG_AT.getValue(), Instant.now());
        log.debug("[BroadcastWebSocketHandler] handlePongMessage() - Pong received | streamId: {}", broadcastStreamId);
    }

    /**
     * 전송 오류 처리
     * - 메시지 전송 중 오류가 발생하면 세션을 정리한다.
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        String broadcastStreamId = (String) session.getAttributes().get(WebSocketAttributes.BROADCAST_STREAM_ID.getValue());

        log.error("[BroadcastWebSocketHandler] handleTransportError() - Transport error | streamId: {}, error: {}", broadcastStreamId, exception.getMessage());
        sessionRegistry.removeSession(broadcastStreamId, session);
    }

    /**
     * WebSocket 연결 종료 후 실행
     * - 세션을 ConcurrentHashMap에서 제거한다.
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String broadcastStreamId = (String) session.getAttributes().get(WebSocketAttributes.BROADCAST_STREAM_ID.getValue());
        Long userId = (Long) session.getAttributes().get(WebSocketAttributes.USER_ID.getValue());

        sessionRegistry.removeSession(broadcastStreamId, session);
        log.info("[BroadcastWebSocketHandler] afterConnectionClosed() - Session removed | userId: {}, streamId: {}, status: {}", userId, broadcastStreamId, status);
    }

    /**
     * WebSocket 세션 종료 (REST API에서 호출)
     * - 지정된 streamId의 세션을 정상 종료한다.
     *
     * @param broadcastStreamId : 종료할 방송 스트림 ID
     */
    public void disconnect(String broadcastStreamId) {
        disconnect(broadcastStreamId, CloseStatus.NORMAL.withReason("Broadcast terminated"));
    }

    /**
     * WebSocket 세션 종료 (커스텀 CloseStatus)
     * - 지정된 streamId의 세션을 지정된 상태로 종료한다.
     *
     * @param broadcastStreamId : 종료할 방송 스트림 ID
     * @param closeStatus : 종료 상태
     */
    public void disconnect(String broadcastStreamId, CloseStatus closeStatus) {
        WebSocketSession session = sessionRegistry.getSession(broadcastStreamId);
        if (session != null && session.isOpen()) {
            try {
                session.close(closeStatus);
                log.info("[BroadcastWebSocketHandler] disconnect() - Session closed | streamId: {}, status: {}", broadcastStreamId, closeStatus);
            } catch (IOException e) {
                log.error("[BroadcastWebSocketHandler] disconnect() - Failed to close session | streamId: {}, error: {}", broadcastStreamId, e.getMessage());
            }
        } else {
            log.warn("[BroadcastWebSocketHandler] disconnect() - Session not found or already closed | streamId: {}", broadcastStreamId);
        }
    }

    /**
     * 활성 세션 수 반환 (모니터링용)
     *
     * @return 활성 WebSocket 세션 수
     */
    public int getActiveSessionCount() {
        return sessionRegistry.getActiveSessionCount();
    }

    /**
     * 주기적으로 활성 세션에 Ping 메시지 전송
     * - 30초마다 실행
     * - Pong 응답이 없으면 세션 종료
     */
    @Scheduled(fixedDelayString = "${websocket.ping.interval-ms:30000}")
    public void pingActiveSessions() {
        Instant now = Instant.now();
        int closedCount = 0;

        for (var entry : sessionRegistry.getActiveSessionsSnapshot()) {
            String broadcastStreamId = entry.getKey();
            WebSocketSession session = entry.getValue();

            if (!session.isOpen()) {
                sessionRegistry.removeSession(broadcastStreamId);
                closedCount++;
                continue;
            }

            /*
                1. 마지막 Pong 응답 시간 확인
                - 타임아웃 초과 시 세션 강제 종료
             */
            Instant lastPongAt = (Instant) session.getAttributes().get(WebSocketAttributes.LAST_PONG_AT.getValue());
            if (lastPongAt != null && Duration.between(lastPongAt, now).toMillis() > PONG_TIMEOUT_MS) {
                log.warn("[BroadcastWebSocketHandler] pingActiveSessions() - Pong timeout, closing session | streamId: {}", broadcastStreamId);
                try {
                    session.close(CloseStatus.POLICY_VIOLATION.withReason("Pong timeout"));
                } catch (IOException e) {
                    log.error("[BroadcastWebSocketHandler] pingActiveSessions() - Failed to close timed-out session | streamId: {}", broadcastStreamId);
                }
                sessionRegistry.removeSession(broadcastStreamId);
                closedCount++;
                continue;
            }

            /*
                2. Ping 메시지 전송
             */
            try {
                session.sendMessage(new PingMessage());
                log.debug("[BroadcastWebSocketHandler] pingActiveSessions() - Ping sent | streamId: {}", broadcastStreamId);
            } catch (IOException e) {
                log.error("[BroadcastWebSocketHandler] pingActiveSessions() - Failed to send ping | streamId: {}", broadcastStreamId);
            }
        }

        if (closedCount > 0) {
            log.info("[BroadcastWebSocketHandler] pingActiveSessions() - Closed {} timed-out sessions", closedCount);
        }
    }
}
