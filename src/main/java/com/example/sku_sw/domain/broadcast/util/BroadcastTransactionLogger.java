package com.example.sku_sw.domain.broadcast.util;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.engine.spi.SessionImplementor;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 방송 트랜잭션에 이미 연결된 스레드와 JDBC 커넥션을 관찰한다.
 * 커넥션 획득, SQL 실행, close를 하지 않으며 Hibernate의 연결 상태만 읽는다.
 */
@Slf4j
public final class BroadcastTransactionLogger {

    private BroadcastTransactionLogger() {
    }

    /**
     * 현재 스레드에 바인딩된 JPA 자원을 기록하는 함수
     * @param phase : 로그 발생 위치 (ex: terminate.afterCommit.ENTER)
     * @param reference : 로그 발생 자원 (레퍼런스)
     */
    public static void logCurrent(String phase, Object reference) {
        if (!log.isInfoEnabled()) {
            return;
        }
        /*
            1. 현재 Thread, JPA 자원 조회
            - Thread.currentThread() : 현재 메서드를 실행하는 스레드를 반환
            - TransactionSynchronizationManager.getResourceMap() : 현재 스레드에 Spring이 연결해 둔 자원을 반환
         */
        Thread thread = Thread.currentThread();
        String connection = "NOT_ACQUIRED";
        String sessionId = "NONE";
        try {
            var resources = TransactionSynchronizationManager.getResourceMap();
            for (Object resource : resources.values()) {
                /*
                    2. 현재 Thread의 Jpa 자원 조회
                    - 해당 Thread에 연결된 EntityManagerHolder를 통해 EntityManager를 가져오고, 내부의 Hibernate 구현체에 접근
                 */
                if (resource instanceof EntityManagerHolder holder) {
                    SessionImplementor session = holder.getEntityManager().unwrap(SessionImplementor.class);
                    sessionId = session.getSessionIdentifier().toString();
                    /*
                        3. 이미 확보한 커넥션만 확인
                        - logicalConnection: Hibernate가 JDBC 연결을 관리하는 객체
                        - 먼저 실제 커넥션이 연결되어 있는지 확인하고, 연결되어 있을 때만 해당 커넥션의 문자열 정보를 가져옴
                     */
                    var logicalConnection = session.getJdbcCoordinator().getLogicalConnection();
                    if (logicalConnection.isPhysicallyConnected()) {
                        connection = logicalConnection.getPhysicalConnection().toString();
                    }
                    break;
                }
            }
            /*
                4. 현재 스레드에 바인딩된 Hikari 풀의 전체 사용량 조회
                - active: 대여 중, idle: 즉시 대여 가능한 유휴 연결, total: 생성된 전체 연결
                - creatable: 최대 크기까지 추가 생성 가능한 개수이며 즉시 사용 가능한 연결은 아니다.
                - 각 수치는 개별 시점의 관측값이므로 동시 요청에 의해 서로 일치하지 않을 수 있다.
             */
            String poolState = "UNAVAILABLE (no bound HikariDataSource)";
            for (Object resourceKey : resources.keySet()) {
                if (resourceKey instanceof HikariDataSource dataSource) {
                    var pool = dataSource.getHikariPoolMXBean();
                    if (pool == null) {
                        poolState = dataSource.getPoolName() + " (not initialized)";
                    } else {
                        int active = pool.getActiveConnections();
                        int idle = pool.getIdleConnections();
                        int total = pool.getTotalConnections();
                        int max = dataSource.getMaximumPoolSize();
                        poolState = String.format("name=%s, active=%d, idle=%d, total=%d, max=%d, creatable=%d, waiting=%d",
                                dataSource.getPoolName(), active, idle, total, max,
                                Math.max(0, max - total), pool.getThreadsAwaitingConnection());
                    }
                    break;
                }
            }
            log.info("[BroadcastTransaction] phase: {}, reference: {}, thread: {}#{}, springTxActive: {}, txName: {}, session: {}, connection: {}, pool: [{}]",
                    phase, reference, thread.getName(), thread.threadId(),
                    TransactionSynchronizationManager.isActualTransactionActive(),
                    TransactionSynchronizationManager.getCurrentTransactionName(), sessionId, connection, poolState);
        } catch (RuntimeException e) {
            log.warn("[BroadcastTransaction] 관찰 실패 | phase: {}, reference: {}, thread: {}#{}, error: {}",
                    phase, reference, thread.getName(), thread.threadId(), e.toString());
        }
    }
}
