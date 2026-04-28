package com.example.sku_sw.domain.broadcast.service;

import com.example.sku_sw.domain.broadcast.entity.Broadcast;
import com.example.sku_sw.domain.broadcast.enums.BroadcastStatus;
import com.example.sku_sw.domain.broadcast.repository.BroadcastRepository;
import com.example.sku_sw.domain.broadcast.util.BroadcastRedisUtil;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 방송 WebSocket 연결 타임아웃 서비스
 * - 방송 시작 후 일정 시간 내에 WebSocket 연결이 없으면 비정상 종료 처리한다.
 * - 타임아웃은 Redis 저장 성공 시에만 등록된다.
 */
@Slf4j
@Service
public class BroadcastConnectionTimeoutService {

    /**
     * 타임아웃 작업 관리 Map
     * Key: broadcastStreamId, Value: ScheduledFuture
     */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> timeoutTasks = new ConcurrentHashMap<>();

    /**
     * WebSocket 연결 타임아웃 (밀리초) - 30초
     */
    private static final long CONNECTION_TIMEOUT_MS = 30_000;

    private final TaskScheduler taskScheduler;
    private final BroadcastRepository broadcastRepository;
    private final BroadcastRedisUtil broadcastRedisUtil;
    private final BroadcastWebSocketSessionRegistry sessionRegistry;
    private final TransactionTemplate transactionTemplate;

    public BroadcastConnectionTimeoutService(
            TaskScheduler taskScheduler,
            BroadcastRepository broadcastRepository,
            BroadcastRedisUtil broadcastRedisUtil,
            BroadcastWebSocketSessionRegistry sessionRegistry,
            PlatformTransactionManager transactionManager
    ) {
        this.taskScheduler = taskScheduler;
        this.broadcastRepository = broadcastRepository;
        this.broadcastRedisUtil = broadcastRedisUtil;
        this.sessionRegistry = sessionRegistry;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 방송 시작 후 WebSocket 연결 타임아웃 작업을 등록한다.
     * - 30초 후에 실행되는 타임아웃 태스크를 스케줄링한다.
     * - WebSocket 연결 성공 시 {@link #cancelConnectionTimeout(String)}으로 취소되어야 한다.
     *
     * @param broadcastStreamId : 방송 스트림 ID
     */
    public void registerConnectionTimeout(String broadcastStreamId) {
        // taskScheduler.schedule(): 지정해준 시간에 지정한 함수가 1회 실행되도록 한다.
        ScheduledFuture<?> future = taskScheduler.schedule(
                () -> handleTimeout(broadcastStreamId),
                Instant.now().plusMillis(CONNECTION_TIMEOUT_MS)
        );
        ScheduledFuture<?> oldFuture = timeoutTasks.put(broadcastStreamId, future);
        if (oldFuture != null) {
            oldFuture.cancel(false);
        }
        log.info("[BroadcastConnectionTimeoutService] registerConnectionTimeout() - Timeout registered | streamId: {}, timeoutMs: {}", broadcastStreamId, CONNECTION_TIMEOUT_MS);
    }

    /**
     * WebSocket 연결 성공 시 타임아웃 작업을 취소한다.
     * - 등록된 ScheduledFuture를 취소하고 timeoutTasks에서 제거한다.
     *
     * @param broadcastStreamId : 방송 스트림 ID
     */
    public void cancelConnectionTimeout(String broadcastStreamId) {
        ScheduledFuture<?> future = timeoutTasks.remove(broadcastStreamId);
        if (future != null) {
            future.cancel(false);
            log.info("[BroadcastConnectionTimeoutService] cancelConnectionTimeout() - Timeout cancelled | streamId: {}", broadcastStreamId);
        } else {
            log.debug("[BroadcastConnectionTimeoutService] cancelConnectionTimeout() - No timeout task found | streamId: {}", broadcastStreamId);
        }
    }

    /**
     * 타임아웃 핸들러
     * - 30초 이내에 WebSocket 연결이 없으면 비정상 종료 처리를 수행한다.
     * - 트랜잭션 commit 직전 WebSocket 세션을 한 번 더 확인하여
     *   세션이 존재하면 DB 롤백 및 Redis 데이터를 복구한다.
     *
     * @param broadcastStreamId : 방송 스트림 ID
     */
    private void handleTimeout(String broadcastStreamId) {
        log.info("[BroadcastConnectionTimeoutService] handleTimeout() - START | streamId: {}", broadcastStreamId);

        try {
            /*
                1. WebSocket 세션 존재 여부 확인
                - 이미 WebSocket 연결이 수립되었다면 타임아웃 처리를 중단한다.
                - 밑에 있는 트랜잭션에서 2번 더 WebSocket Session 존재 여부를 확인하지만, Redis 조회 같은 무거운 로직이 실행되기 전에 바로 Timeout을 종료시키도록 하기 위해 해당 조건문 설정하였음
             */
            if (sessionRegistry.hasSession(broadcastStreamId)) {
                log.info("[BroadcastConnectionTimeoutService] handleTimeout() - WebSocket session already exists, skipping timeout | streamId: {}", broadcastStreamId);
                return;
            }

            /*
                2. Redis value 백업 조회
                - 롤백 시 복구를 위해 백업을 저장해둔다.
             */
            String redisBackupJson = broadcastRedisUtil.getBroadcastCharacterValue(broadcastStreamId);

            /*
                3. 트랜잭션 내에서 비정상 종료 처리
                - TransactionTemplate으로 필요한 부분에만 트랜잭션 적용 (execute 함수 사용)
                - DB에 쓰기 락을 획득한 뒤 BROADCASTING 상태일 때만 abnormalTerminate 상태를 기록한다.
                - Redis 삭제 이후 세션을 재확인하여 세션이 생겼으면 Redis 복구 후 rollback 처리한다.
             */
            transactionTemplate.execute(status -> {
                Broadcast broadcast = broadcastRepository.findByStreamIdAndStatusForUpdate(broadcastStreamId, BroadcastStatus.BROADCASTING)
                        .orElse(null);
                if (broadcast == null) {
                    log.warn("[BroadcastConnectionTimeoutService] handleTimeout() - Broadcast not found or not BROADCASTING | streamId: {}", broadcastStreamId);
                    return null;
                }

                if (sessionRegistry.hasSession(broadcastStreamId)) {
                    log.info("[BroadcastConnectionTimeoutService] handleTimeout() - WebSocket session found in transaction, skipping timeout | streamId: {}", broadcastStreamId);
                    return null;
                }

                broadcastRedisUtil.deleteBroadcastCharacterValue(broadcastStreamId);
                log.info("[BroadcastConnectionTimeoutService] handleTimeout() - Redis backup & delete completed | streamId: {}", broadcastStreamId);

                broadcast.abnormalTerminate();
                broadcastRepository.save(broadcast);

                /*
                    3-1. commit 전 WebSocket 세션 재확인
                    - 이 시점에 세션이 존재하면 Redis 데이터를 복구하고 DB 변경은 rollback 처리한다.
                 */
                if (sessionRegistry.hasSession(broadcastStreamId)) {
                    log.warn("[BroadcastConnectionTimeoutService] handleTimeout() - WebSocket session found before commit, rolling back | streamId: {}", broadcastStreamId);
                    if (redisBackupJson != null) {
                        broadcastRedisUtil.setBroadcastCharacterValueRaw(broadcastStreamId, redisBackupJson);
                    }
                    status.setRollbackOnly();
                    return null;
                }

                log.info("[BroadcastConnectionTimeoutService] handleTimeout() - No WebSocket session, committing abnormal termination | streamId: {}", broadcastStreamId);

                return null;
            });

            log.info("[BroadcastConnectionTimeoutService] handleTimeout() - Abnormal termination processed | streamId: {}", broadcastStreamId);
        } catch (Exception e) {
            log.error("[BroadcastConnectionTimeoutService] handleTimeout() - Error during timeout handling | streamId: {}, error: {}", broadcastStreamId, e.getMessage(), e);
        } finally {
            /*
                5. timeoutTasks에서 제거
                - 타임아웃 작업 완료 후 반드시 Map에서 삭제한다.
             */
            timeoutTasks.remove(broadcastStreamId);
            log.info("[BroadcastConnectionTimeoutService] handleTimeout() - END | streamId: {}", broadcastStreamId);
        }
    }
}
