package com.example.sku_sw.domain.broadcast.service;

import com.example.sku_sw.domain.broadcast.util.BroadcastRedisUtil;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionBundle;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 스트리머 발화 종료 시점부터 무음 타이머를 관리하는 서비스.
 * - 마지막 발화 완료 후 지정 시간 동안 추가 발화가 없으면 isStreamerSilent=true로 전환한다.
 * - 새 발화가 시작되면 타이머를 취소하고 무음 상태를 false로 되돌린다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BroadcastStreamerSilenceService {
    private final TaskScheduler taskScheduler;
    private final BroadcastRedisUtil broadcastRedisUtil;
    private final BroadcastWebSocketSessionRegistry sessionRegistry;
    private final ConcurrentHashMap<String, SilenceState> states = new ConcurrentHashMap<>();

    @Value("${broadcast.streamer-silence-threshold-ms:4000}")
    private long silenceThresholdMs;

    /**
     * 방송 시작 직후 최초 무음 타이머를 등록한다.
     *
     * @param streamId : 방송 스트림 ID
     * @param generation : 현재 세션 generation
     */
    public void startInitialTimer(String streamId, long generation) {
        log.info("[BroadcastStreamerSilenceService] startInitialTimer() - START | streamId: {}, generation: {}",
                streamId, generation);
        markUtteranceCompleted(streamId, generation);
        log.info("[BroadcastStreamerSilenceService] startInitialTimer() - END | streamId: {}, generation: {}",
                streamId, generation);
    }

    /**
     * 스트리머 발화 시작을 반영한다.
     * - 기존 타이머를 무효화하고 무음 상태를 false로 갱신한다.
     *
     * @param streamId : 방송 스트림 ID
     * @param generation : 현재 세션 generation
     */
    public void markSpeechStarted(String streamId, long generation) {
        log.info("[BroadcastStreamerSilenceService] markSpeechStarted() - START | streamId: {}, generation: {}",
                streamId, generation);

        SilenceState state = states.computeIfAbsent(streamId, ignored -> new SilenceState());
        synchronized (state) {
            state.token.incrementAndGet();
            cancelFuture(state);
            setSilentSafely(streamId, false);
        }

        log.info("[BroadcastStreamerSilenceService] markSpeechStarted() - END | streamId: {}, generation: {}, silent: false",
                streamId, generation);
    }

    /**
     * 스트리머 발화 완료를 반영하고 무음 타이머를 다시 등록한다.
     *
     * @param streamId : 방송 스트림 ID
     * @param generation : 현재 세션 generation
     */
    public void markUtteranceCompleted(String streamId, long generation) {
        log.info("[BroadcastStreamerSilenceService] markUtteranceCompleted() - START | streamId: {}, generation: {}",
                streamId, generation);

        SilenceState state = states.computeIfAbsent(streamId, ignored -> new SilenceState());
        synchronized (state) {
            long token = state.token.incrementAndGet();
            cancelFuture(state);
            setSilentSafely(streamId, false);
            state.future = taskScheduler.schedule(
                    () -> markSilentIfCurrent(streamId, generation, token, state),
                    Instant.now().plusMillis(silenceThresholdMs));
        }

        log.info("[BroadcastStreamerSilenceService] markUtteranceCompleted() - END | streamId: {}, generation: {}, thresholdMs: {}",
                streamId, generation, silenceThresholdMs);
    }

    /**
     * 방송 종료 또는 세션 교체 시 무음 타이머 상태를 정리한다.
     *
     * @param streamId : 방송 스트림 ID
     */
    public void cancel(String streamId) {
        log.info("[BroadcastStreamerSilenceService] cancel() - START | streamId: {}", streamId);

        SilenceState state = states.remove(streamId);
        if (state != null) {
            synchronized (state) {
                state.token.incrementAndGet();
                cancelFuture(state);
            }
        }

        log.info("[BroadcastStreamerSilenceService] cancel() - END | streamId: {}, action: {}",
                streamId, state == null ? "skip" : "cancelled");
    }

    private void markSilentIfCurrent(String streamId, long generation, long token, SilenceState state) {
        synchronized (state) {
            BroadcastWebSocketSessionBundle bundle = sessionRegistry.getSessionBundleIfCurrent(streamId, generation);
            if (states.get(streamId) != state || state.token.get() != token || bundle == null) {
                return;
            }
            setSilentSafely(streamId, true);
            state.future = null;
        }
    }

    private void setSilentSafely(String streamId, boolean silent) {
        try {
            broadcastRedisUtil.updateBroadcastUserStreamerSilent(streamId, silent);
        } catch (RuntimeException e) {
            log.warn("[BroadcastStreamerSilenceService] Redis state update skipped | streamId: {}, silent: {}, error: {}",
                    streamId, silent, e.getMessage());
        }
    }

    private void cancelFuture(SilenceState state) {
        if (state.future != null) {
            state.future.cancel(false);
            state.future = null;
        }
    }

    private static final class SilenceState {
        private final AtomicLong token = new AtomicLong();
        private ScheduledFuture<?> future;
    }
}
