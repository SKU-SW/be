package com.example.sku_sw.domain.broadcast.service.gemini;

import com.example.sku_sw.domain.broadcast.enums.BroadcastErrorCode;
import com.example.sku_sw.domain.broadcast.websocket.gemini.GeminiLiveWebSocketHandler;
import com.example.sku_sw.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class GeminiLiveApiService {

    private final StandardWebSocketClient webSocketClient;
    private final GeminiLiveWebSocketHandler setupWebSocketHandler;

    @Value("${gemini.api.key}")
    private String geminiApiKey;

    @Value("${gemini.api.live-websocket-url}")
    private String geminiLiveWebSocketUrl;

    @Value("${gemini.api.live-connect-timeout-ms:5000}")
    private long liveConnectTimeoutMs;

    @Value("${gemini.api.live-setup-timeout-ms:5000}")
    private long liveSetupTimeoutMs;

    /**
     * Gemini Live API WebSocket 비동기 연결을 생성한다.
     * - handshake 완료 후 setupComplete 응답까지 대기한다.
     * - setupComplete 완료 이후 Gemini WebSocket 세션 Future를 반환한다.
     *
     * @return : setupComplete까지 완료된 Gemini WebSocket 세션 Future
     */
    public CompletableFuture<WebSocketSession> connectGeminiApiWebSocketAsync() {
        log.info("[GeminiLiveApiService] connectGeminiApiWebSocketAsync() - START");

        /*
            1. StandardWebSocketClient.execute()로 Gemini Live API로 WebSocket 연동 요청을 보낸다.
            - 해당 함수 호출 직후 executeFuture 객체를 바로 반환받는다.
         */
        URI geminiUri = createGeminiLiveWebSocketUri();
        CompletableFuture<WebSocketSession> executeFuture = webSocketClient.execute(
                setupWebSocketHandler,
                new WebSocketHttpHeaders(),
                geminiUri
        ).orTimeout(liveConnectTimeoutMs, TimeUnit.MILLISECONDS);

        /*
            2. CompletableFuture<WebSocketSession> 객체가 돌아오면 thenCompose()로 다음 비동기 작업으로 연결시킨다.
            - setupWebSocketHandler의 해당 Gemini WebSocketSession의 ID 별로 저장된 setupCompleteFuture 객체를 가져온다
            - 해당 setupCompleteFuture의 작업이 완료되면 geminiSession을 반환하도록 설정한다.
            - 만약 해당 과정에서 예외가 발생한다면 GeminiSession을 종료시킨다.
         */
        CompletableFuture<WebSocketSession> readyFuture = executeFuture.thenCompose(geminiSession -> {
            CompletableFuture<Void> setupCompleteFuture = setupWebSocketHandler.getSetupCompleteFuture(geminiSession);
            if (setupCompleteFuture == null) {
                closeGeminiSessionQuietly(geminiSession);
                return CompletableFuture.failedFuture(new CustomException(BroadcastErrorCode.GEMINI_RESPONSE_FAILED));
            }

            return setupCompleteFuture.orTimeout(liveSetupTimeoutMs, TimeUnit.MILLISECONDS)
                    .thenApply(ignored -> geminiSession)
                    .exceptionally(throwable -> {
                        closeGeminiSessionQuietly(geminiSession);
                        throw new CustomException(BroadcastErrorCode.GEMINI_RESPONSE_FAILED);
                    });
        });

        /*
            3. readyFuture 객체에 setup이 완료된 Gemini WebSocket Session이 들어오는 경우, 해당 Gemini Session을 검증한다
            - Gemini Session값이 null인 경우, setupWebSocketHandler의 특정 setupCompleteFuture 객체를 삭제한다.
            - whenComplete()
                - 성공/실패 시 모두 실행
                - 예외도 확인 가능
                - 결과를 바꾸는 것이 아니라 마지막 점검/후처리에 사용됨
         */
        return readyFuture.whenComplete((geminiSession, throwable) -> {
            if (geminiSession != null) {
                setupWebSocketHandler.removeSetupCompleteFuture(geminiSession.getId());
                log.info("[GeminiLiveApiService] connectGeminiApiWebSocketAsync() - END | sessionId: {}", geminiSession.getId());
                return;
            }

            log.error("[GeminiLiveApiService] connectGeminiApiWebSocketAsync() - Failed | error: {}",
                    throwable != null ? throwable.getMessage() : "unknown error");
        });
    }

    /**
     * Gemini Live API WebSocket URI를 생성한다.
     *
     * @return : API key query parameter가 포함된 WebSocket URI
     */
    private URI createGeminiLiveWebSocketUri() {
        String encodedApiKey = URLEncoder.encode(geminiApiKey, StandardCharsets.UTF_8);
        return URI.create(geminiLiveWebSocketUrl + "?key=" + encodedApiKey);
    }

    /**
     * Gemini WebSocket 세션을 조용히 종료한다.
     *
     * @param geminiSession : 종료할 Gemini 세션
     */
    public void closeGeminiSessionQuietly(WebSocketSession geminiSession) {
        if (geminiSession == null || !geminiSession.isOpen()) {
            return;
        }

        try {
            geminiSession.close(CloseStatus.SERVER_ERROR.withReason("Gemini setup failed"));
        } catch (IOException e) {
            log.warn("[GeminiLiveApiService] closeGeminiSessionQuietly() - Failed to close Gemini session | error: {}", e.getMessage());
        }
    }
}
