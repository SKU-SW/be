package com.example.sku_sw.domain.broadcast.service.gemini;

import com.example.sku_sw.domain.broadcast.enums.WebSocketAttributes;
import com.example.sku_sw.domain.broadcast.enums.BroadcastErrorCode;
import com.example.sku_sw.domain.broadcast.websocket.gemini.GeminiLiveWebSocketHandler;
import com.example.sku_sw.domain.broadcast.websocket.gemini.GeminiLiveWebSocketHandlerFactory;
import com.example.sku_sw.global.exception.CustomException;
import com.example.sku_sw.global.util.GeminiUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class BroadcastGeminiLiveService {

    private final StandardWebSocketClient webSocketClient;
    private final GeminiLiveWebSocketHandlerFactory geminiLiveWebSocketHandlerFactory;
    private final GeminiUtil geminiUtil;

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
    public CompletableFuture<WebSocketSession> connectGeminiApiWebSocketAsync(String broadcastStreamId, String systemPrompt) {
        log.info("[BroadcastGeminiLiveService] connectGeminiApiWebSocketAsync() - START | streamId: {}", broadcastStreamId);

        /*
            1. StandardWebSocketClient.execute()로 Gemini Live API로 WebSocket 연동 요청을 보낸다.
            - 해당 함수 호출 직후 executeFuture 객체를 바로 반환받는다.
         */
        URI geminiUri = createGeminiLiveWebSocketUri();
        GeminiLiveWebSocketHandler liveWebSocketHandler = geminiLiveWebSocketHandlerFactory.create(systemPrompt);
        CompletableFuture<WebSocketSession> executeFuture = webSocketClient.execute(
                liveWebSocketHandler,
                new WebSocketHttpHeaders(),
                geminiUri
        ).orTimeout(liveConnectTimeoutMs, TimeUnit.MILLISECONDS);

        /*
            2. CompletableFuture<WebSocketSession> 객체가 돌아오면 thenCompose()로 다음 비동기 작업으로 연결시킨다.
            - Gemini WebSocket Session attributes에 broadcastStreamId를 먼저 주입한다.
            - 생성한 GeminiLiveWebSocketHandler가 보유한 setupCompleteFuture 객체를 가져온다.
            - 해당 setupCompleteFuture의 작업이 완료되면 geminiSession을 반환하도록 설정한다.
            - 만약 해당 과정에서 예외가 발생한다면 GeminiSession을 종료시킨다.
         */
        CompletableFuture<WebSocketSession> readyFuture = executeFuture.thenCompose(geminiSession -> {
            geminiSession.getAttributes().put(WebSocketAttributes.BROADCAST_STREAM_ID.getValue(), broadcastStreamId);
            CompletableFuture<Void> setupCompleteFuture = liveWebSocketHandler.getSetupCompleteFuture();

            return setupCompleteFuture.orTimeout(liveSetupTimeoutMs, TimeUnit.MILLISECONDS)
                    .thenApply(ignored -> geminiSession)
                    .exceptionally(throwable -> {
                        log.error("[BroadcastGeminiLiveService] connectGeminiApiWebSocketAsync() - Setup failed | streamId: {}, diagnostics: {}",
                                broadcastStreamId, liveWebSocketHandler.getSetupFailureDiagnostics(), throwable);
                        geminiUtil.closeGeminiSessionQuietly(geminiSession);
                        throw new CustomException(BroadcastErrorCode.GEMINI_RESPONSE_FAILED);
                    });
        });

        /*
            3. readyFuture 객체에 setup이 완료된 Gemini WebSocket Session이 들어오는 경우, 해당 Gemini Session을 검증한다
            - whenComplete()
                - 성공/실패 시 모두 실행
                - 예외도 확인 가능
                - 결과를 바꾸는 것이 아니라 마지막 점검/후처리에 사용됨
         */
        return readyFuture.whenComplete((geminiSession, throwable) -> {
            if (geminiSession != null) {
                log.info("[BroadcastGeminiLiveService] connectGeminiApiWebSocketAsync() - END | streamId: {}, sessionId: {}",
                        broadcastStreamId, geminiSession.getId());
                return;
            }

            log.error("[BroadcastGeminiLiveService] connectGeminiApiWebSocketAsync() - Failed | streamId: {}, diagnostics: {}",
                    broadcastStreamId, liveWebSocketHandler.getSetupFailureDiagnostics(), throwable);
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
}
