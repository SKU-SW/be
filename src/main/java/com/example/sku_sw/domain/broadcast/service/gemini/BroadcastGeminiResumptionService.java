package com.example.sku_sw.domain.broadcast.service.gemini;

import com.example.sku_sw.domain.broadcast.enums.WebSocketSessionBundleStatus;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionBundle;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionRegistry;
import com.example.sku_sw.domain.broadcast.websocket.gemini.GeminiLiveWebSocketHandler;
import com.example.sku_sw.global.util.GeminiUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * Gemini session resumption 서비스
 * - refresh와 별도로, 예기치 않은 Gemini 세션 종료 후 resumption handle 기반 재연결을 시도한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BroadcastGeminiResumptionService {

    private final BroadcastWebSocketSessionRegistry sessionRegistry;
    private final BroadcastGeminiLiveService broadcastGeminiLiveService;
    private final BroadcastGeminiRequestService broadcastGeminiRequestService;
    private final GeminiUtil geminiUtil;

    /**
     * 예기치 않은 Gemini 세션 종료 이후 resumption 재연결을 시도한다.
     * - 성공 시 기존 cleanup을 수행하지 않는다.
     * - 조건 불충족 또는 실패 시 fallback cleanup을 실행한다.
     * @param closedSession : 종료된 Gemini 세션
     * @param closeStatus : 종료 상태
     * @param fallbackCleanup : 기존 cleanup 로직
     * @return : resumption 성공 여부
     */
    public boolean tryResumeAfterUnexpectedClose(
            WebSocketSession closedSession,
            CloseStatus closeStatus,
            Runnable fallbackCleanup
    ) {
        log.info("[BroadcastGeminiResumptionService] tryResumeAfterUnexpectedClose() - START | sessionId: {}, closeStatus: {}",
                closedSession != null ? closedSession.getId() : null, closeStatus);

        /*
            1. 현재 종료된 Gemini 세션이 속한 Session Bundle을 조회하고, resumption 가능 상태인지 검증한다.
            - bundle이 없거나 현재 Gemini 세션과 다르면 fallback cleanup을 즉시 수행한다.
            - client session 종료, refresh 진행 중, resumption 중복 수행, active turn 존재 시에도 resumption을 시작하지 않는다.
         */
        BroadcastWebSocketSessionBundle bundle = resolveCurrentBundle(closedSession);
        if (!isResumptionEligible(bundle, closedSession)) {
            fallbackCleanup.run();
            log.info("[BroadcastGeminiResumptionService] tryResumeAfterUnexpectedClose() - END | action: fallback_cleanup");
            return false;
        }

        /*
            2. 동일 bundle에 대해 resumption이 중복 실행되지 않도록 원자적으로 in-progress 플래그를 선점한다.
            - 이미 다른 스레드에서 resumption 중이면 fallback cleanup을 수행하고 종료한다.
         */
        if (!bundle.markResumptionInProgress()) {
            fallbackCleanup.run();
            log.info("[BroadcastGeminiResumptionService] tryResumeAfterUnexpectedClose() - END | action: resumption_already_in_progress");
            return false;
        }

        String broadcastStreamId = resolveBroadcastStreamId(closedSession);
        long generation = bundle.getGeneration();
        String resumptionHandle = bundle.getLatestGeminiResumptionHandle();

        try {
            /*
                3. resumption handle을 사용해 새 Gemini 세션 연결을 시도한다.
                - 연결 중에는 bundle 상태를 GEMINI_CONNECTING으로 전환한다.
                - setupComplete까지 완료된 새 Gemini 세션과 handler를 확보한다.
                - BroadcastGeminiLiveService에서 임시로 갖고 있는 GeminiWebSocketHandler를 remove해서 가져온다.
             */
            bundle.updateStatus(WebSocketSessionBundleStatus.GEMINI_CONNECTING);
            WebSocketSession resumedGeminiSession = broadcastGeminiLiveService
                    .resumeGeminiApiWebSocketAsync(broadcastStreamId, generation, resumptionHandle)
                    .join();
            GeminiLiveWebSocketHandler resumedHandler = broadcastGeminiLiveService.consumePendingHandler(resumedGeminiSession.getId());

            /*
                4. resumption 완료 시점에도 현재 bundle이 여전히 동일한 generation/세션인지 재검증한다.
                - 중간에 bundle이 교체되었다면 새 Gemini 세션을 즉시 닫고 fallback cleanup을 수행한다.
                - 동일 bundle이면 새 Gemini 세션/handler로 교체하고 READY 상태로 복구한다.
             */
            BroadcastWebSocketSessionBundle currentBundle = sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation);
            if (currentBundle == null || currentBundle.getGeminiSession() != closedSession) {
                geminiUtil.closeGeminiSessionQuietly(
                        resumedGeminiSession,
                        resumedHandler,
                        CloseStatus.NORMAL.withReason("Resumed session discarded by race"),
                        com.example.sku_sw.domain.broadcast.enums.GeminiSessionCloseReason.SESSION_REGISTRY_CLEANUP
                );
                bundle.clearResumptionInProgress();
                fallbackCleanup.run();
                log.warn("[BroadcastGeminiResumptionService] tryResumeAfterUnexpectedClose() - Current bundle changed during resumption | streamId: {}, generation: {}",
                        broadcastStreamId, generation);
                return false;
            }
            // 동일 bundle이면 새 Gemini 세션/handler로 교체하고 READY 상태로 복구
            currentBundle.registerGeminiSession(resumedGeminiSession, resumedHandler);
            currentBundle.clearResumptionInProgress();
            currentBundle.updateStatus(WebSocketSessionBundleStatus.READY);

            /*
                5. resumption 성공 후 첫 sessionResumptionUpdate 생성을 유도한다.
                - registerGeminiSession()이 기존 resumption metadata를 초기화하므로,
                  새 세션에 대한 handle을 확보하기 위해 bootstrap control 요청을 전송한다.
                - 실패하더라도 resumption 자체는 성공 처리하고 로깅만 수행한다.
             */
            try {
                broadcastGeminiRequestService.getFirstResumptionEvent(broadcastStreamId, generation);
            } catch (Exception ex) {
                log.warn("[BroadcastGeminiResumptionService] tryResumeAfterUnexpectedClose() - First resumption event request failed | streamId: {}, generation: {}, error: {}",
                        broadcastStreamId, generation, ex.getMessage());
            }

            log.info("[BroadcastGeminiResumptionService] tryResumeAfterUnexpectedClose() - END | action: resumed | streamId: {}, generation: {}, newSessionId: {}",
                    broadcastStreamId, generation, resumedGeminiSession.getId());
            return true;
        } catch (Exception e) {
            /*
                5. resumption 연결 과정에서 예외가 발생하면 in-progress 플래그를 해제하고 fallback cleanup을 수행한다.
                - bundle 상태는 READY로 되돌린 뒤 기존 종료 정리 로직으로 위임한다.
             */
            bundle.clearResumptionInProgress();
            bundle.updateStatus(WebSocketSessionBundleStatus.READY);
            fallbackCleanup.run();
            log.error("[BroadcastGeminiResumptionService] tryResumeAfterUnexpectedClose() - Failed | streamId: {}, generation: {}, handle: {}, error: {}",
                    broadcastStreamId, generation, resumptionHandle, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 종료된 Gemini 세션으로부터 현재 Session Registry에 연결된 Session Bundle을 조회한다.
     * @param closedSession : 종료된 Gemini 세션
     * @return : 현재 Session Bundle (없으면 null)
     */
    private BroadcastWebSocketSessionBundle resolveCurrentBundle(WebSocketSession closedSession) {
        log.info("[BroadcastGeminiResumptionService] resolveCurrentBundle() - START | sessionId: {}",
                closedSession != null ? closedSession.getId() : null);

        /*
            1. 종료된 Gemini 세션 attribute에서 broadcastStreamId를 추출한다.
            - streamId가 없으면 현재 bundle을 찾을 수 없으므로 null을 반환한다.
         */
        String broadcastStreamId = resolveBroadcastStreamId(closedSession);
        if (broadcastStreamId == null) {
            log.info("[BroadcastGeminiResumptionService] resolveCurrentBundle() - END | action: stream_id_not_found");
            return null;
        }

        /*
            2. Session Registry에서 현재 streamId에 연결된 bundle을 조회하여 반환한다.
         */
        BroadcastWebSocketSessionBundle result = sessionRegistry.getSessionBundle(broadcastStreamId);
        log.info("[BroadcastGeminiResumptionService] resolveCurrentBundle() - END | streamId: {}, found: {}",
                broadcastStreamId, result != null);
        return result;
    }

    /**
     * 현재 종료 상황이 Gemini resumption 시도 조건에 맞는지 확인한다.
     * - 현재 bundle 존재 여부, client session open 상태, refresh/resumption 진행 여부를 함께 검증한다.
     * - active request-flight 또는 active accumulator가 남아있으면 안전을 위해 resumption을 시작하지 않는다.
     * @param bundle : 현재 Session Bundle
     * @param closedSession : 종료된 Gemini 세션
     * @return : resumption 시도 가능 여부
     */
    private boolean isResumptionEligible(BroadcastWebSocketSessionBundle bundle, WebSocketSession closedSession) {
        log.info("[BroadcastGeminiResumptionService] isResumptionEligible() - START | sessionId: {}",
                closedSession != null ? closedSession.getId() : null);

        /*
            1. 현재 bundle 및 세션 정합성을 검증한다.
            - bundle이 없거나, 현재 bundle에 연결된 Gemini 세션이 종료된 세션과 다르면 resumption을 수행하지 않는다.
            - client session이 닫혀 있으면 복구 대상이 아니므로 false를 반환한다.
         */
        if (bundle == null || closedSession == null) {
            log.info("[BroadcastGeminiResumptionService] isResumptionEligible() - END | action: bundle_or_session_missing");
            return false;
        }
        if (bundle.getGeminiSession() != closedSession) {
            log.info("[BroadcastGeminiResumptionService] isResumptionEligible() - END | action: gemini_session_mismatch");
            return false;
        }
        if (!bundle.isClientSessionOpen()) {
            log.info("[BroadcastGeminiResumptionService] isResumptionEligible() - END | action: client_session_closed");
            return false;
        }

        /*
            2. refresh/resumption 동시 진행 및 진행 중 turn 상태를 검증한다.
            - refresh 진행 중이거나 이미 resumption 중이면 중복 복구를 방지하기 위해 false를 반환한다.
            - request-flight 또는 active accumulator가 남아 있으면 상태 유실 가능성이 있어 resumption을 보류한다.
         */
        if (bundle.getGeminiSessionRefreshInProgress() || bundle.isGeminiSessionRefreshRequested()) {
            log.info("[BroadcastGeminiResumptionService] isResumptionEligible() - END | action: refresh_in_progress");
            return false;
        }
        if (bundle.getGeminiSessionResumptionInProgress()) {
            log.info("[BroadcastGeminiResumptionService] isResumptionEligible() - END | action: resumption_in_progress");
            return false;
        }
        if (bundle.getRequestFlightCountValue() > 0) {
            log.info("[BroadcastGeminiResumptionService] isResumptionEligible() - END | action: in_flight_remaining, requestFlightCount: {}",
                    bundle.getRequestFlightCountValue());
            return false;
        }
        if (bundle.getGeminiHandler() != null && bundle.getGeminiHandler().hasActiveAccumulator()) {
            log.info("[BroadcastGeminiResumptionService] isResumptionEligible() - END | action: active_accumulator_remaining");
            return false;
        }

        /*
            3. 최신 resumption metadata를 검증한다.
            - resumable=true 이고, 유효한 handle 문자열이 있을 때만 resumption 대상이다.
         */
        boolean result = bundle.isLatestGeminiResumable()
                && bundle.getLatestGeminiResumptionHandle() != null
                && !bundle.getLatestGeminiResumptionHandle().isBlank();
        log.info("[BroadcastGeminiResumptionService] isResumptionEligible() - END | resumable: {}, hasHandle: {}, result: {}",
                bundle.isLatestGeminiResumable(),
                bundle.getLatestGeminiResumptionHandle() != null && !bundle.getLatestGeminiResumptionHandle().isBlank(),
                result);
        return result;
    }

    /**
     * Gemini 세션 attribute에서 broadcastStreamId를 추출한다.
     * @param session : 종료된 Gemini 세션
     * @return : broadcastStreamId (없으면 null)
     */
    private String resolveBroadcastStreamId(WebSocketSession session) {
        log.info("[BroadcastGeminiResumptionService] resolveBroadcastStreamId() - START | sessionId: {}",
                session != null ? session.getId() : null);

        /*
            1. session 또는 attributes가 없으면 streamId를 추출할 수 없으므로 null을 반환한다.
         */
        if (session == null || session.getAttributes() == null) {
            log.info("[BroadcastGeminiResumptionService] resolveBroadcastStreamId() - END | action: session_or_attributes_missing");
            return null;
        }

        /*
            2. broadcastStreamId attribute를 문자열로 변환하여 반환한다.
         */
        Object value = session.getAttributes().get("broadcastStreamId");
        String result = value != null ? value.toString() : null;
        log.info("[BroadcastGeminiResumptionService] resolveBroadcastStreamId() - END | streamId: {}", result);
        return result;
    }
}
