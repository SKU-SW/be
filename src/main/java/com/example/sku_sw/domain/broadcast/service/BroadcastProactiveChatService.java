package com.example.sku_sw.domain.broadcast.service;

import com.example.sku_sw.domain.broadcast.dto.BroadcastInfoRedisDto;
import com.example.sku_sw.domain.broadcast.enums.DialogueSubject;
import com.example.sku_sw.domain.broadcast.service.gemini.BroadcastGeminiRequestService;
import com.example.sku_sw.domain.broadcast.util.BroadcastRedisUtil;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionBundle;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Collectors;

/**
 * 방송별 선제 채팅 후보를 묶어서 Gemini로 전송하는 서비스.
 * - 동일 방송에서 짧은 시간에 들어온 VIEWER 채팅을 하나의 후보 블록으로 모은다.
 * - 선제 반응이 비활성화되었거나 스트리머가 말하는 중이면 전송하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BroadcastProactiveChatService {
    private final TaskScheduler taskScheduler;
    private final BroadcastRedisUtil broadcastRedisUtil;
    private final BroadcastWebSocketSessionRegistry sessionRegistry;
    private final BroadcastGeminiRequestService geminiRequestService;
    private final ConcurrentHashMap<String, BatchState> batches = new ConcurrentHashMap<>();

    @Value("${broadcast.proactive-chat-batch-window-ms:750}")
    private long batchWindowMs;

    /**
     * 선제 채팅 후보를 방송별 배치에 등록한다.
     * - 후보가 유효하고 방송이 선제 반응 가능 상태일 때만 예약한다.
     *
     * @param streamId : 방송 스트림 ID
     * @param cursorId : Redis 대화 cursor ID
     */
    public void enqueue(String streamId, Long cursorId) {
        log.info("[BroadcastProactiveChatService] enqueue() - START | streamId: {}, cursorId: {}",
                streamId, cursorId);

        if (cursorId == null || !isEligible(streamId)) {
            log.info("[BroadcastProactiveChatService] enqueue() - END | streamId: {}, cursorId: {}, action: skip",
                    streamId, cursorId);
            return;
        }
        BatchState state = batches.computeIfAbsent(streamId, ignored -> new BatchState());
        synchronized (state) {
            state.cursorIds.add(cursorId);
            if (state.future == null) {
                state.future = schedule(streamId, state, batchWindowMs);
            }
        }

        log.info("[BroadcastProactiveChatService] enqueue() - END | streamId: {}, cursorId: {}, action: queued",
                streamId, cursorId);
    }

    /**
     * 방송의 선제 채팅 배치를 취소한다.
     *
     * @param streamId : 방송 스트림 ID
     */
    public void cancel(String streamId) {
        log.info("[BroadcastProactiveChatService] cancel() - START | streamId: {}", streamId);

        BatchState state = batches.remove(streamId);
        if (state != null) {
            synchronized (state) {
                if (state.future != null) {
                    state.future.cancel(false);
                }
                state.cursorIds.clear();
                state.future = null;
            }
        }

        log.info("[BroadcastProactiveChatService] cancel() - END | streamId: {}, action: {}",
                streamId, state == null ? "skip" : "cancelled");
    }

    private ScheduledFuture<?> schedule(String streamId, BatchState state, long delayMs) {
        return taskScheduler.schedule(() -> flush(streamId, state), Instant.now().plusMillis(delayMs));
    }

    private void flush(String streamId, BatchState state) {
        log.info("[BroadcastProactiveChatService] flush() - START | streamId: {}", streamId);

        synchronized (state) {
            state.future = null;
            if (batches.get(streamId) != state || !isEligible(streamId)) {
                batches.remove(streamId, state);
                state.cursorIds.clear();
                log.info("[BroadcastProactiveChatService] flush() - END | streamId: {}, action: stale_or_ineligible",
                        streamId);
                return;
            }

            BroadcastWebSocketSessionBundle bundle = sessionRegistry.getSessionBundle(streamId);
            if (bundle == null || !bundle.canSendToGemini() || bundle.getRequestFlightCountValue() > 0) {
                state.future = schedule(streamId, state, batchWindowMs);
                log.info("[BroadcastProactiveChatService] flush() - END | streamId: {}, action: rescheduled, reason: {}, batchCursorCount: {}, batchCursorIds: {}",
                        streamId,
                        buildRescheduledReason(bundle),
                        state.cursorIds.size(),
                        state.cursorIds);
                return;
            }

            List<BroadcastInfoRedisDto> candidates = broadcastRedisUtil
                    .getUnsentDialoguesByCursorIds(streamId, state.cursorIds).stream()
                    .filter(info -> info.subject() == DialogueSubject.VIEWER)
                    .toList();
            if (candidates.isEmpty()) {
                batches.remove(streamId, state);
                state.cursorIds.clear();
                log.info("[BroadcastProactiveChatService] flush() - END | streamId: {}, action: empty_candidates",
                        streamId);
                return;
            }

            String candidateBlock = candidates.stream()
                    .map(BroadcastInfoRedisDto::content)
                    .collect(Collectors.joining("\n"));
            boolean sent = geminiRequestService.sendProactiveChatRequest(
                    streamId, bundle.getGeneration(), candidateBlock);
            if (!sent) {
                state.future = schedule(streamId, state, batchWindowMs);
                log.info("[BroadcastProactiveChatService] flush() - END | streamId: {}, action: retry_scheduled",
                        streamId);
                return;
            }

            broadcastRedisUtil.markDialoguesSentToGemini(
                    streamId, candidates.stream().map(BroadcastInfoRedisDto::cursorId).toList());
            batches.remove(streamId, state);
            state.cursorIds.clear();
            log.info("[BroadcastProactiveChatService] flush() - END | streamId: {}, action: sent, candidateCount: {}",
                    streamId, candidates.size());
        }
    }

    /**
     * proactive chat flush 재예약 사유를 문자열로 정리하는 함수.
     *
     * @param bundle 현재 방송 세션 번들
     * @return 재예약 사유 문자열
     */
    private String buildRescheduledReason(BroadcastWebSocketSessionBundle bundle) {
        /*
            1. flush 재예약에 영향을 주는 세션 상태를 한 줄로 직렬화한다.
            - request-flight 누수, refresh 잔류, Gemini 세션 종료 여부를 로그만 보고 바로 식별할 수 있도록 한다.
         */
        if (bundle == null) {
            return "bundle_missing";
        }

        return String.format(
                "bundleStatus=%s, canSendToGemini=%s, geminiSessionOpen=%s, refreshRequested=%s, refreshInProgress=%s, requestFlightCount=%d",
                bundle.getStatus(),
                bundle.canSendToGemini(),
                bundle.isGeminiSessionOpen(),
                bundle.isGeminiSessionRefreshRequested(),
                bundle.getGeminiSessionRefreshInProgress(),
                bundle.getRequestFlightCountValue()
        );
    }

    private boolean isEligible(String streamId) {
        try {
            return broadcastRedisUtil.isAiProactiveToChatEnabled(streamId)
                    && broadcastRedisUtil.isStreamerSilent(streamId);
        } catch (RuntimeException e) {
            log.debug("[BroadcastProactiveChatService] Eligibility check failed | streamId: {}, error: {}",
                    streamId, e.getMessage());
            return false;
        }
    }

    private static final class BatchState {
        private final LinkedHashSet<Long> cursorIds = new LinkedHashSet<>();
        private ScheduledFuture<?> future;
    }
}
