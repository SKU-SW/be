package com.example.sku_sw.domain.broadcast.websocket;

import com.example.sku_sw.domain.broadcast.dto.BroadcastVoiceMetadataResDto;
import com.example.sku_sw.domain.broadcast.enums.BroadcastErrorCode;
import com.example.sku_sw.domain.broadcast.enums.WebSocketAttributes;
import com.example.sku_sw.global.exception.CustomException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * 활성 WebSocket 세션 관리
     * Key: broadcastStreamId, Value: WebSocketSession
     */
    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    /**
     * Pong 응답 타임아웃 (밀리초) - 기본 90초
     */
    private static final long PONG_TIMEOUT_MS = 90_000;

    /**
     * WebSocket 연결 수립 후 실행
     * - HandshakeInterceptor에서 검증이 끝나면, 해당 단계에서 지정해준 Attribute가 담긴 WebSocketSession 객체가 생성되어 전달된다.
     * - 세션을 ConcurrentHashMap에 등록한다.
     * - lastPongAt 속성을 현재 시간으로 초기화한다.
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String broadcastStreamId = (String) session.getAttributes().get(WebSocketAttributes.BROADCAST_STREAM_ID.getValue());
        Long userId = (Long) session.getAttributes().get(WebSocketAttributes.USER_ID.getValue());

        /*
            1. 기존 세션이 있으면 닫고 새 세션으로 교체
            - 동일 streamId로 재연결 시 이전 연결을 정리한다.
         */
        WebSocketSession oldSession = sessions.put(broadcastStreamId, session); // 기존에 존재하던 value 값을 반환한다.
        if (oldSession != null && oldSession.isOpen()) {
            try {
                oldSession.close(CloseStatus.POLICY_VIOLATION.withReason("Replaced by new connection"));
            } catch (IOException e) {
                log.warn("[BroadcastWebSocketHandler] afterConnectionEstablished() - Failed to close old session | streamId: {}", broadcastStreamId);
            }
        }

        /*
            2. Pong 응답 타임스탬프 초기화
         */
        session.getAttributes().put(WebSocketAttributes.LAST_PONG_AT.getValue(), Instant.now());

        log.info("[BroadcastWebSocketHandler] afterConnectionEstablished() - Session registered | userId: {}, streamId: {}", userId, broadcastStreamId);
    }

    /**
     * 클라이언트로부터 텍스트 메시지(JSON) 수신
     * - 클라이언트가 전송한 JSON 형식의 메시지를 처리한다.
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String broadcastStreamId = (String) session.getAttributes().get(WebSocketAttributes.BROADCAST_STREAM_ID.getValue());
        Long userId = (Long) session.getAttributes().get(WebSocketAttributes.USER_ID.getValue());

        log.info("[BroadcastWebSocketHandler] handleTextMessage() - Received | userId: {}, streamId: {}, payload: {}", userId, broadcastStreamId, message.getPayload());

        /*
            TODO: 클라이언트 메시지 파싱 및 비즈니스 로직 처리
            - 메시지 타입에 따른 분기 처리
            - 예: 사용자 텍스트 입력, 방송 제어 명령 등
         */
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
        sessions.remove(broadcastStreamId);
    }

    /**
     * WebSocket 연결 종료 후 실행
     * - 세션을 ConcurrentHashMap에서 제거한다.
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String broadcastStreamId = (String) session.getAttributes().get(WebSocketAttributes.BROADCAST_STREAM_ID.getValue());
        Long userId = (Long) session.getAttributes().get(WebSocketAttributes.USER_ID.getValue());

        sessions.remove(broadcastStreamId);
        log.info("[BroadcastWebSocketHandler] afterConnectionClosed() - Session removed | userId: {}, streamId: {}, status: {}", userId, broadcastStreamId, status);
    }

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
     * @param broadcastDialogueId : BroadcastDialogue PK (Cursor)
     */
    public void sendVoiceWithMetadata(
            String broadcastStreamId,
            byte[] voiceData,
            Long characterId,
            String voiceText,
            Long broadcastDialogueId
    ) {
        WebSocketSession session = sessions.get(broadcastStreamId);
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
                .broadcastDialogueId(broadcastDialogueId)
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

            log.debug("[BroadcastWebSocketHandler] sendVoiceWithMetadata() - Sent | streamId: {}, dialogueId: {}", broadcastStreamId, broadcastDialogueId);
        } catch (IOException e) {
            log.error("[BroadcastWebSocketHandler] sendVoiceWithMetadata() - Failed to send | streamId: {}, error: {}", broadcastStreamId, e.getMessage());
            throw new CustomException(BroadcastErrorCode.WEBSOCKET_CONNECTION_NOT_FOUND);
        }
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
        WebSocketSession session = sessions.get(broadcastStreamId);
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
        return sessions.size();
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

        for (var entry : sessions.entrySet()) {
            String broadcastStreamId = entry.getKey();
            WebSocketSession session = entry.getValue();

            if (!session.isOpen()) {
                sessions.remove(broadcastStreamId);
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
                sessions.remove(broadcastStreamId);
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
