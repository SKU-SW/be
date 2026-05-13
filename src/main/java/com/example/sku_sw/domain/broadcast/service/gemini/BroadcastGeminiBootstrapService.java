package com.example.sku_sw.domain.broadcast.service.gemini;

import com.example.sku_sw.domain.broadcast.dto.BroadcastWebSocketErrorResDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastWebSocketStatusResDto;
import com.example.sku_sw.domain.broadcast.enums.BroadcastErrorCode;
import com.example.sku_sw.domain.broadcast.enums.WebSocketSessionBundleStatus;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionBundle;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;

/**
 * Gemini WebSocket bootstrap(초기 설정) 비동기 처리 서비스
 * - 클라이언트 WebSocket 연결 이후 Gemini 연결/설정 완료까지를 비동기로 관리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BroadcastGeminiBootstrapService {

    private final ObjectMapper objectMapper;
    private final BroadcastWebSocketSessionRegistry sessionRegistry;
    private final GeminiLiveApiService geminiLiveApiService;

    /**
     * Gemini WebSocket bootstrap(초기 세팅) 비동기 작업을 시작한다.
     * - Gemini Live API의 setupComplete까지 성공하면 bundle 상태를 READY로 갱신한다.
     * - 실패하면 bundle을 제거하고 client/gemini 세션을 정리한다.
     * - thenAccept(): CompletableFuture에서 결과를 받으면 그 값을 소비만 하고, 새로운 값을 반환하지 않는 함수
     *
     * @param broadcastStreamId : 방송 스트림 ID
     * @param clientSession     : 클라이언트 WebSocket 세션
     * @param generation        : 현재 세션 generation
     */
    public void bootstrapGeminiAsync(String broadcastStreamId, WebSocketSession clientSession, long generation) {
        log.info("[BroadcastGeminiBootstrapService] bootstrapGeminiAsync() - START | streamId: {}, generation: {}",
                broadcastStreamId, generation);

        geminiLiveApiService.connectGeminiApiWebSocketAsync()
                .thenAccept(geminiSession -> handleBootstrapSuccess(broadcastStreamId, clientSession, generation, geminiSession))
                .exceptionally(throwable -> {
                    handleBootstrapFailure(broadcastStreamId, clientSession, generation, throwable);
                    return null;
                });
    }

    /**
     * Gemini bootstrap 성공 시 후처리를 수행한다.
     *
     * @param broadcastStreamId : 방송 스트림 ID
     * @param clientSession     : 클라이언트 WebSocket 세션
     * @param generation        : 현재 세션 generation
     * @param geminiSession     : 준비 완료된 Gemini WebSocket 세션
     */
    private void handleBootstrapSuccess(
            String broadcastStreamId,
            WebSocketSession clientSession,
            long generation,
            WebSocketSession geminiSession
    ) {
        log.info("[BroadcastGeminiBootstrapService] handleBootstrapSuccess() - START | streamId: {}, generation: {}",
                broadcastStreamId, generation);

        /*
            1. Bootstrap 성공 후 반환된 clienSession이 현재 Session Registry에 저장된 Session Bundle과 일치하는지 확인한다.
            - 만약 현재 Session Registry에 저장된 Session Bundle과 일치하지 않는다면, 새롭게 생성된 Gemini WebSocket Session을 종료시킨다.
            - 현재 Session Registry에 저장된 Session Bundle과 일치하지 않는 경우는, Gemini WebSocket Session이 생성되는 도중에 클라이언트가 새로운 WebSocket 연결을 시도한 것이기 때문에 Gemini Session만 종료시킨다.
         */
        if (!sessionRegistry.isCurrentClientSession(broadcastStreamId, generation, clientSession)) {
            geminiLiveApiService.closeGeminiSessionQuietly(geminiSession);
            log.warn("[BroadcastGeminiBootstrapService] handleBootstrapSuccess() - Stale client session detected | streamId: {}, generation: {}",
                    broadcastStreamId, generation);
            return;
        }

        /*
            2. Gemini Session을 Session Bundle에 등록한다.
            - 만약 정상적으로 Session이 등록되지 않았다면 전체 방송 세션 번들을 삭제한다.
         */
        boolean registered = sessionRegistry.registerGeminiSessionIfCurrent(broadcastStreamId, generation, geminiSession);
        if (!registered) {
            terminateFailedBundle(broadcastStreamId, generation, clientSession, geminiSession, CloseStatus.SERVER_ERROR, BroadcastErrorCode.GEMINI_RESPONSE_FAILED.getMessage());
            log.warn("[BroadcastGeminiBootstrapService] handleBootstrapSuccess() - Gemini session registration failed | streamId: {}, generation: {}",
                    broadcastStreamId, generation);
            return;
        }

        /*
            3. Gemini Session까지 현재 Session Bundle에 성공적으로 등록했다면, 해당 Session Bundle의 상태를 READY로 변경한다.
            - 이후 ClientSession에게 "WebSocket 연결 성공" 메시지 전송
         */
        sessionRegistry.updateBundleStatusIfCurrent(broadcastStreamId, generation, WebSocketSessionBundleStatus.READY);
        sendStatusMessage(clientSession, WebSocketSessionBundleStatus.READY.name(), "WebSocket 연결 성공");

        log.info("[BroadcastGeminiBootstrapService] handleBootstrapSuccess() - END | streamId: {}, generation: {}",
                broadcastStreamId, generation);
    }

    /**
     * Gemini bootstrap 실패 시 후처리를 수행한다.
     *
     * @param broadcastStreamId : 방송 스트림 ID
     * @param clientSession     : 클라이언트 WebSocket 세션
     * @param generation        : 현재 세션 generation
     * @param throwable         : 발생한 예외
     */
    private void handleBootstrapFailure(
            String broadcastStreamId,
            WebSocketSession clientSession,
            long generation,
            Throwable throwable
    ) {
        log.error("[BroadcastGeminiBootstrapService] handleBootstrapFailure() - START | streamId: {}, generation: {}, error: {}",
                broadcastStreamId, generation, throwable != null ? throwable.getMessage() : "unknown error");

        // 1. broadcastStreamId와 generation 값을 이용해 Session Bundle을 찾는다.
        BroadcastWebSocketSessionBundle bundle = sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation);
        if (bundle == null || !bundle.matchesClientSession(clientSession)) {
            log.warn("[BroadcastGeminiBootstrapService] handleBootstrapFailure() - Bundle already replaced | streamId: {}, generation: {}",
                    broadcastStreamId, generation);
            return;
        }

        /*
            2. Gemini WebSocket 초기세팅(Bootstrap)이 실패했을 경우의 작업 실행
            - 해당 Session Bundle의 status를 FAILED로 설정
            - 해당 번들의 Gemini Session을 종료
            - 해당 Session Bundle 삭제 및 클라이언트에게 에러 메시지(GEMINI_RESPONSE_FAILED) 전송 후 Client Session 종료
         */
        terminateFailedBundle(broadcastStreamId, generation, clientSession, bundle.getGeminiSession(), CloseStatus.SERVER_ERROR, BroadcastErrorCode.GEMINI_RESPONSE_FAILED.getMessage());
        log.error("[BroadcastGeminiBootstrapService] handleBootstrapFailure() - END | streamId: {}, generation: {}, error: {}",
                broadcastStreamId, generation, throwable != null ? throwable.getMessage() : "unknown error");
    }

    /**
     * 상태 메시지를 클라이언트에게 전송한다.
     *
     * @param clientSession : 클라이언트 WebSocket 세션
     * @param status        : 상태값
     * @param message       : 상태 메시지
     */
    public void sendStatusMessage(WebSocketSession clientSession, String status, String message) {
        try {
            if (clientSession == null || !clientSession.isOpen()) {
                return;
            }

            BroadcastWebSocketStatusResDto resDto = BroadcastWebSocketStatusResDto.builder()
                    .status(status)
                    .message(message)
                    .build();

            clientSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(resDto)));
        } catch (IOException e) {
            log.warn("[BroadcastGeminiBootstrapService] sendStatusMessage() - Failed | error: {}", e.getMessage());
        }
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

    /**
     * 에러 메시지를 전송하고 세션을 종료한다.
     *
     * @param session : WebSocket 세션
     * @param status : 종료 상태
     * @param errorMessage  : 에러 메시지
     */
    public void sendErrorAndClose(WebSocketSession session, CloseStatus status, String errorMessage) {
        try {
            if (session != null && session.isOpen()) {
                sendError(session,  errorMessage);
                session.close(status.withReason(errorMessage));
            }
        } catch (IOException e) {
            log.warn("[BroadcastGeminiBootstrapService] sendErrorAndClose() - Failed | error: {}", e.getMessage());
        }
    }

    /**
     * 실패한 Bundle의 리소스들을 정리 및 삭제함으로써 완전히 종료시킨다.
     * 클라이언트에게 Error 메시지를 전송한다.
     * @param broadcastStreamId
     * @param generation
     * @param clientSession
     * @param geminiSession
     * @param closeStatus
     * @param errorMessage
     */
    private void terminateFailedBundle(
            String broadcastStreamId,
            long generation,
            WebSocketSession clientSession,
            WebSocketSession geminiSession,
            CloseStatus closeStatus,
            String errorMessage
    ) {
        sessionRegistry.updateBundleStatusIfCurrent(broadcastStreamId, generation, WebSocketSessionBundleStatus.FAILED);
        geminiLiveApiService.closeGeminiSessionQuietly(geminiSession);
        sessionRegistry.removeSessionBundleIfCurrent(broadcastStreamId, generation);
        sendErrorAndClose(clientSession, closeStatus, errorMessage);
    }


}
