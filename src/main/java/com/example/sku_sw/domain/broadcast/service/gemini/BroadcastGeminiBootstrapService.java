package com.example.sku_sw.domain.broadcast.service.gemini;

import com.example.sku_sw.domain.broadcast.dto.BroadcastCharacterRedisDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastInfoRedisDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastWebSocketErrorResDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastWebSocketStatusResDto;
import com.example.sku_sw.domain.broadcast.enums.BroadcastErrorCode;
import com.example.sku_sw.domain.broadcast.enums.GeminiSessionCloseReason;
import com.example.sku_sw.domain.broadcast.enums.WebSocketSessionBundleStatus;
import com.example.sku_sw.domain.broadcast.util.BroadcastPromptBuilder;
import com.example.sku_sw.domain.broadcast.util.BroadcastRedisUtil;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionBundle;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionRegistry;
import com.example.sku_sw.domain.broadcast.websocket.gemini.GeminiLiveWebSocketHandler;
import com.example.sku_sw.domain.character.enums.Gender;
import com.example.sku_sw.global.exception.CustomException;
import com.example.sku_sw.global.util.GeminiUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * Gemini WebSocket bootstrap(초기 설정) 비동기 처리 서비스
 * - 클라이언트 WebSocket 연결 이후 Gemini 연결/설정 완료까지를 비동기로 관리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BroadcastGeminiBootstrapService {

    @Value("${broadcast.dialogue.redis-max-num}")
    private Integer redisBroadcastDialogueMaxNum;

    private final ObjectMapper objectMapper;
    private final BroadcastWebSocketSessionRegistry sessionRegistry;
    private final BroadcastGeminiLiveService broadcastGeminiLiveService;
    private final BroadcastGeminiRequestService broadcastGeminiRequestService;
    private final GeminiUtil geminiUtil;
    private final BroadcastRedisUtil broadcastRedisUtil;
    private final BroadcastPromptBuilder broadcastPromptBuilder;

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

        try {
            /*
                1. Gemini Live setup에 주입할 시스템 프롬프트를 생성한다.
                - Redis에서 캐릭터, summary, 최근 활성 대화 내역을 조회한다.
                - 조회한 방송 컨텍스트를 조합해 세션 초기용 시스템 프롬프트를 만든다.
             */
            BroadcastCharacterRedisDto character = broadcastRedisUtil.getBroadcastCharacterDto(broadcastStreamId);
            BroadcastInfoRedisDto summary = broadcastRedisUtil.getSummary(broadcastStreamId);
            java.util.List<BroadcastInfoRedisDto> recentActiveInfos = broadcastRedisUtil.getRecentActiveDialogues(
                    broadcastStreamId,
                    redisBroadcastDialogueMaxNum
            );
            String systemPrompt = broadcastPromptBuilder.buildBroadcastDialoguePrompt(character, summary, recentActiveInfos);
            String voiceName = deriveVoiceName(character);

            /*
                2. 생성한 시스템 프롬프트를 포함해 Gemini Live WebSocket bootstrap을 시작한다.
                - setupComplete까지 성공하면 후속 성공 처리로 연결한다.
                - 실패하면 현재 generation bundle만 정리한다.
             */
            broadcastGeminiLiveService.connectGeminiApiWebSocketAsync(broadcastStreamId, generation, systemPrompt, voiceName)
                    .thenAccept(geminiSession -> handleBootstrapSuccess(broadcastStreamId, clientSession, generation, geminiSession))
                    .exceptionally(throwable -> {
                        handleBootstrapFailure(broadcastStreamId, clientSession, generation, throwable);
                        return null;
                    });
        } catch (Exception e) {
            handleBootstrapFailure(broadcastStreamId, clientSession, generation, e);
        }
    }

    /**
     * refresh용 Gemini WebSocket bootstrap Future를 반환한다.
     * - caller가 준비한 system prompt를 사용해 setupComplete까지 완료된 Gemini 세션을 반환한다.
     * - 실패 시 클라이언트 세션을 종료하지 않고 예외를 Future로 전달한다.
     * @param broadcastStreamId : 방송 스트림 ID
     * @param generation : 현재 세션 generation
     * @param systemPrompt : refresh용 시스템 프롬프트
     * @return : setupComplete까지 완료된 Gemini 세션 Future
     */
    public CompletableFuture<WebSocketSession> bootstrapGeminiForRefreshAsync(
            String broadcastStreamId,
            long generation,
            String systemPrompt,
            String voiceName
    ) {
        log.info("[BroadcastGeminiBootstrapService] bootstrapGeminiForRefreshAsync() - START | streamId: {}, generation: {}",
                broadcastStreamId, generation);

        /*
            1. 현재 generation 번들 존재 여부를 검증한다.
            - 현재 세션 번들이 없으면 refresh를 진행하지 않는다.
         */
        BroadcastWebSocketSessionBundle bundle = sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation);
        if (bundle == null) {
            CompletableFuture<WebSocketSession> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(new CustomException(BroadcastErrorCode.WEBSOCKET_CONNECTION_NOT_FOUND));
            return failedFuture;
        }

        /*
            2. caller가 전달한 프롬프트로 Gemini 연결을 생성한다.
            - setupComplete까지 완료된 신규 Gemini 세션을 Future로 반환한다.
         */
        return broadcastGeminiLiveService.connectGeminiApiWebSocketAsync(broadcastStreamId, generation, systemPrompt, voiceName)
                .whenComplete((geminiSession, throwable) -> {
                    if (geminiSession != null) {
                        log.info("[BroadcastGeminiBootstrapService] bootstrapGeminiForRefreshAsync() - END | streamId: {}, generation: {}, sessionId: {}",
                                broadcastStreamId, generation, geminiSession.getId());
                        return;
                    }

                    log.error("[BroadcastGeminiBootstrapService] bootstrapGeminiForRefreshAsync() - Failed | streamId: {}, generation: {}, error: {}",
                            broadcastStreamId, generation, throwable != null ? throwable.getMessage() : "unknown error");
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
            1. Bootstrap 성공 후 반환된 clientSession이 현재 Session Registry에 저장된 Session Bundle과 일치하는지 확인한다.
            - 만약 현재 Session Registry에 저장된 Session Bundle과 일치하지 않는다면, 새롭게 생성된 Gemini WebSocket Session을 종료시킨다.
            - 현재 Session Registry에 저장된 Session Bundle과 일치하지 않는 경우는, Gemini WebSocket Session이 생성되는 도중에 클라이언트가 새로운 WebSocket 연결을 시도한 것이기 때문에 Gemini Session만 종료시킨다.
         */
        GeminiLiveWebSocketHandler handler = broadcastGeminiLiveService.consumePendingHandler(geminiSession.getId());
        if (!sessionRegistry.isCurrentClientSession(broadcastStreamId, generation, clientSession)) {
            BroadcastWebSocketSessionBundle currentBundle = sessionRegistry.getSessionBundle(broadcastStreamId);
            geminiUtil.closeGeminiSessionQuietly(
                    geminiSession,
                    handler,
                    CloseStatus.POLICY_VIOLATION.withReason(GeminiSessionCloseReason.STALE_CLIENT_SESSION.getDescription()),
                    GeminiSessionCloseReason.STALE_CLIENT_SESSION
            );
            log.warn("[BroadcastGeminiBootstrapService] handleBootstrapSuccess() - Stale client session detected | streamId: {}, staleGeneration: {}, currentGeneration: {}, diagnostics: {}",
                    broadcastStreamId,
                    generation,
                    currentBundle != null ? currentBundle.getGeneration() : null,
                    handler != null ? handler.getTerminationDiagnostics(geminiSession, "STALE_CLIENT_SESSION", null) : "handler_not_found");
            return;
        }

        /*
             2. Gemini Session과 GeminiLiveWebSocketHandler를 Session Bundle에 등록한다.
            - handler는 BroadcastGeminiLiveService의 pendingGeminiHandlers 맵에서 꺼내 전달한다.
            - 만약 정상적으로 Session이 등록되지 않았다면 전체 방송 세션 번들을 삭제한다.
         */
        boolean registered = sessionRegistry.registerGeminiSessionIfCurrent(broadcastStreamId, generation, geminiSession, handler);
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

        /*
            4. 초기 연결 직후 first resumption event를 전송한다.
            - 사용자 입력이 없어도 첫 sessionResumptionUpdate를 확보할 수 있도록 bootstrap control 요청을 전송한다.
            - 실패하더라도 bootstrap 자체는 유지하고 로깅만 수행한다.
         */
        try {
            broadcastGeminiRequestService.getFirstResumptionEvent(broadcastStreamId, generation);
        } catch (Exception e) {
            log.warn("[BroadcastGeminiBootstrapService] handleBootstrapSuccess() - First resumption event request failed | streamId: {}, generation: {}, error: {}",
                    broadcastStreamId, generation, e.getMessage());
        }

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
        log.error("[BroadcastGeminiBootstrapService] handleBootstrapFailure() - END | streamId: {}, generation: {}, error: {}, diagnostics: {}",
                broadcastStreamId,
                generation,
                throwable != null ? throwable.getMessage() : "unknown error",
                bundle.getGeminiHandler() != null
                        ? bundle.getGeminiHandler().getTerminationDiagnostics(bundle.getGeminiSession(), "BOOTSTRAP_FAILURE", throwable)
                        : "handler_not_found");
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
     * 현재 generation 번들의 전체 리소스를 종료하고 에러를 전송한다.
     * @param broadcastStreamId : 방송 스트림 ID
     * @param generation : 현재 세션 generation
     * @param clientSession : 클라이언트 WebSocket 세션
     * @param geminiSession : Gemini WebSocket 세션
     * @param closeStatus : 종료 상태
     * @param errorMessage : 에러 메시지
     */
    public void terminateBundleWithError(
            String broadcastStreamId,
            long generation,
            WebSocketSession clientSession,
            WebSocketSession geminiSession,
            CloseStatus closeStatus,
            String errorMessage
    ) {
        terminateFailedBundle(broadcastStreamId, generation, clientSession, geminiSession, closeStatus, errorMessage);
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
    /**
     * BroadcastCharacterRedisDto로부터 voiceName을 추출한다.
     * @param character 캐릭터 Redis DTO
     * @return voice name (null 가능)
     */
    private String deriveVoiceName(BroadcastCharacterRedisDto character) {
        if (character == null || character.getCharacterPresetType() == null) {
            return null;
        }
        return character.getCharacterGender() == Gender.MALE
                ? character.getCharacterPresetType().getMaleVoiceName()
                : character.getCharacterPresetType().getFemaleVoiceName();
    }

    private void terminateFailedBundle(
            String broadcastStreamId,
            long generation,
            WebSocketSession clientSession,
            WebSocketSession geminiSession,
            CloseStatus closeStatus,
            String errorMessage
    ) {
        sessionRegistry.updateBundleStatusIfCurrent(broadcastStreamId, generation, WebSocketSessionBundleStatus.FAILED);
        sendErrorAndClose(clientSession, closeStatus, errorMessage);
        BroadcastWebSocketSessionBundle currentBundle = sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation);
        GeminiLiveWebSocketHandler handler = currentBundle != null ? currentBundle.getGeminiHandler() : null;
        geminiUtil.closeGeminiSessionQuietly(
                geminiSession,
                handler,
                closeStatus.withReason(errorMessage),
                GeminiSessionCloseReason.FAILED_BUNDLE_CLEANUP
        );
        log.warn("[BroadcastGeminiBootstrapService] terminateFailedBundle() - Bundle cleanup requested | streamId: {}, generation: {}, closeStatus: {}, errorMessage: {}, diagnostics: {}",
                broadcastStreamId,
                generation,
                closeStatus,
                errorMessage,
                handler != null ? handler.getTerminationDiagnostics(geminiSession, "FAILED_BUNDLE_CLEANUP", null) : "handler_not_found");
        sessionRegistry.removeSessionBundleIfCurrent(
                broadcastStreamId,
                generation,
                closeStatus.withReason(errorMessage),
                "terminateFailedBundle"
        );
    }


}
