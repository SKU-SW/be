package com.example.sku_sw.domain.broadcast.websocket;

import com.example.sku_sw.domain.broadcast.enums.WebSocketSessionBundleStatus;
import com.example.sku_sw.domain.broadcast.websocket.gemini.GeminiLiveWebSocketHandler;
import lombok.Builder;
import lombok.Getter;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 방송 스트림 단위의 WebSocket 세션 묶음
 * - 클라이언트 WebSocket 세션과 Gemini Live API WebSocket 세션을 함께 관리한다.
 */
@Getter
@Builder
public class BroadcastWebSocketSessionBundle {

    private final WebSocketSession clientSession;
    private final long generation;
    private volatile WebSocketSession geminiSession; // volatile: 자바에서 변수의 값이 여러 스레드에서 즉시 보이도록 보장하는 키워드.
    private volatile WebSocketSessionBundleStatus status;
    @Builder.Default
    private final AtomicInteger geminiRequestFlightCount = new AtomicInteger(0); // 제미나이 요청이 현재 날아가있는 횟수
    @Builder.Default
    private final AtomicBoolean geminiSessionRefreshRequested = new AtomicBoolean(false); // 제미나이 세션이 Refresh 요청이 되어있는 상태인지 여부
    @Builder.Default
    private final AtomicBoolean geminiSessionRefreshInProgress = new AtomicBoolean(false); // 제미나이 세션 Refresh 과정이 진행되고 있는 상태인지 여부
    @Builder.Default
    private final AtomicInteger geminiSessionRefreshRetryCount = new AtomicInteger(0); // 제미나이 세션 Refresh 재시도 횟수
    @Builder.Default
    private final AtomicBoolean geminiSessionResumptionInProgress = new AtomicBoolean(false); // 제미나이 세션 Resumption 재연결 진행 여부
    private volatile Long geminiSessionRefreshSnapshotRedisCursorId; // 제미나이 세션 refresh 시 snapshot의 마지막 요소 redis cursor ID
    private volatile GeminiLiveWebSocketHandler geminiHandler; // Gemini Live WebSocket 핸들러 (인터럽트 등 접근용)
    private volatile String latestGeminiResumptionHandle; // 가장 최근에 수신한 Gemini session resumption handle
    private volatile boolean latestGeminiResumable; // 가장 최근 세션이 resumable 상태인지 여부
    private volatile Instant latestGeminiResumptionUpdatedAt; // 가장 최근 resumption update 수신 시각

    /**
     * 클라이언트 세션 존재 여부를 확인한다.
     * @return : 클라이언트 세션 존재 여부
     */
    public boolean hasClientSession() {
        return clientSession != null;
    }

    /**
     * Gemini 세션 존재 여부를 확인한다.
     * @return : Gemini 세션 존재 여부
     */
    public boolean hasGeminiSession() {
        return geminiSession != null;
    }

    /**
     * 현재 generation이 전달받은 generation과 동일한지 확인한다.
     * @param generation : 비교할 generation
     * @return : 동일 여부
     */
    public boolean matchesGeneration(long generation) {
        return this.generation == generation;
    }

    /**
     * 클라이언트 세션 open 여부를 확인한다.
     * @return : 클라이언트 세션 open 여부
     */
    public boolean isClientSessionOpen() {
        return hasClientSession() && clientSession.isOpen();
    }

    /**
     * Gemini 세션 open 여부를 확인한다.
     * @return : Gemini 세션 open 여부
     */
    public boolean isGeminiSessionOpen() {
        return hasGeminiSession() && geminiSession.isOpen();
    }

    /**
     * 클라이언트 세션과 Gemini 세션이 모두 open 상태인지 확인한다.
     * @return : 완전 연결 여부
     */
    public boolean isFullyConnected() {
        return isClientSessionOpen() && isGeminiSessionOpen();
    }

    /**
     * 현재 세션 번들이 READY 상태인지 확인한다.
     * @return : READY 상태 여부
     */
    public boolean isReady() {
        return status == WebSocketSessionBundleStatus.READY;
    }

    /**
     * 현재 세션 번들이 REFRESHING 상태인지 확인한다.
     * @return : REFRESHING 상태 여부
     */
    public boolean isRefreshing() {
        return status == WebSocketSessionBundleStatus.REFRESHING;
    }

    /**
     * 현재 세션 번들이 클라이언트 메시지를 수신 가능한 상태인지 확인한다.
     * @return : 수신 가능 여부
     */
    public boolean canAcceptClientMessage() {
        return status == WebSocketSessionBundleStatus.READY || status == WebSocketSessionBundleStatus.REFRESHING;
    }

    /**
     * 현재 세션 번들이 Gemini 응답을 처리할 수 있는 상태인지 확인한다.
     * @return : Gemini 응답 처리 가능 여부
     */
    public boolean canProcessGeminiResponse() {
        return (status == WebSocketSessionBundleStatus.READY || status == WebSocketSessionBundleStatus.REFRESHING)
                && isGeminiSessionOpen();
    }

    /**
     * 현재 세션 번들이 신규 Gemini 요청 전송 가능한 상태인지 확인한다.
     * @return : 전송 가능 여부
     */
    public boolean canSendToGemini() {
        return status == WebSocketSessionBundleStatus.READY && isGeminiSessionOpen() && !isGeminiSessionRefreshRequested();
    }

    public boolean isWebSocketSessionBundleReady() {
        return status == WebSocketSessionBundleStatus.READY;
    }

    /**
     * 현재 세션 번들이 잠금 상태인지 확인한다.
     * @return : 잠금 상태 여부
     */
    public boolean isLocked() {
        return status.isLocked();
    }

    /**
     * refresh 요청 여부를 반환한다.
     * @return : refresh 요청 여부
     */
    public boolean isGeminiSessionRefreshRequested() {
        return geminiSessionRefreshRequested.get();
    }

    /**
     * refresh 진행 중 여부를 반환한다.
     * @return : refresh 진행 중 여부
     */
    public boolean getGeminiSessionRefreshInProgress() {
        return geminiSessionRefreshInProgress.get();
    }

    /**
     * refresh 요청 플래그를 설정한다.
     * @return : 최초 설정 여부
     */
    public boolean markRefreshRequested() {
        return geminiSessionRefreshRequested.compareAndSet(false, true);
    }

    /**
     * refresh 요청 플래그를 해제한다.
     */
    public void clearRefreshRequested() {
        geminiSessionRefreshRequested.set(false);
    }

    /**
     * refresh 진행 플래그를 설정한다.
     * @return : 최초 설정 여부
     */
    public boolean markRefreshInProgress() {
        return geminiSessionRefreshInProgress.compareAndSet(false, true);
    }

    /**
     * refresh 진행 플래그를 해제한다.
     */
    public void clearRefreshInProgress() {
        geminiSessionRefreshInProgress.set(false);
    }

    /**
     * resumption 진행 중 여부를 반환한다.
     * @return : resumption 진행 중 여부
     */
    public boolean getGeminiSessionResumptionInProgress() {
        return geminiSessionResumptionInProgress.get();
    }

    /**
     * resumption 진행 플래그를 설정한다.
     * @return : 최초 설정 여부
     */
    public boolean markResumptionInProgress() {
        return geminiSessionResumptionInProgress.compareAndSet(false, true);
    }

    /**
     * resumption 진행 플래그를 해제한다.
     */
    public void clearResumptionInProgress() {
        geminiSessionResumptionInProgress.set(false);
    }

    /**
     * 현재 request-flight 요청 수를 반환한다.
     * @return : request-flight 요청 수
     */
    public int getRequestFlightCountValue() {
        return geminiRequestFlightCount.get();
    }

    /**
     * Gemini request-flight 요청 수를 증가시킨다.
     * @return : 증가 후 request-flight 요청 수
     */
    public int incrementRequestFlight() {
        return geminiRequestFlightCount.incrementAndGet();
    }

    /**
     * Gemini request-flight 요청 수를 감소시킨다.
     * @return : 감소 후 request-flight 요청 수
     */
    public int decrementRequestFlight() {
        return geminiRequestFlightCount.updateAndGet(current -> Math.max(0, current - 1));
    }

    /**
     * Gemini request-flight 요청 수를 0으로 초기화한다.
     */
    public void resetRequestFlight() {
        geminiRequestFlightCount.set(0);
    }

    /**
     * refresh retry count를 증가시킨다.
     * @return : 증가 후 retry count
     */
    public int incrementRefreshRetryCount() {
        return geminiSessionRefreshRetryCount.incrementAndGet();
    }

    /**
     * refresh retry count를 초기화한다.
     */
    public void resetRefreshRetryCount() {
        geminiSessionRefreshRetryCount.set(0);
    }

    /**
     * refresh retry count를 반환한다.
     * @return : retry count
     */
    public int getRefreshRetryCountValue() {
        return geminiSessionRefreshRetryCount.get();
    }

    /**
     * refresh snapshot cursor를 갱신한다.
     * @param refreshSnapshotCursorId : refresh snapshot 마지막 cursor
     */
    public void updateRefreshSnapshotCursorId(Long refreshSnapshotCursorId) {
        this.geminiSessionRefreshSnapshotRedisCursorId = refreshSnapshotCursorId;
    }

    /**
     * refresh snapshot cursor를 제거한다.
     */
    public void clearRefreshSnapshotCursorId() {
        this.geminiSessionRefreshSnapshotRedisCursorId = null;
    }

    /**
     * Gemini session resumption 메타데이터를 갱신한다.
     * @param resumptionHandle : 새 resumption handle
     * @param resumable : resumable 여부
     * @param updatedAt : 갱신 시각
     */
    public void updateGeminiSessionResumptionMetadata(String resumptionHandle, boolean resumable, Instant updatedAt) {
        this.latestGeminiResumptionHandle = (resumptionHandle == null || resumptionHandle.isBlank()) ? null : resumptionHandle;
        this.latestGeminiResumable = resumable;
        this.latestGeminiResumptionUpdatedAt = updatedAt;
    }

    /**
     * Gemini 세션을 등록한다.
     * @param geminiSession : 등록할 Gemini 세션
     * @return : 기존 Gemini 세션
     */
    public WebSocketSession registerGeminiSession(WebSocketSession geminiSession) {
        return registerGeminiSession(geminiSession, null);
    }

    /**
     * Gemini 세션과 핸들러를 함께 등록한다.
     * @param geminiSession : 등록할 Gemini 세션
     * @param geminiHandler : Gemini Live WebSocket 핸들러 인스턴스
     * @return : 기존 Gemini 세션
     */
    public WebSocketSession registerGeminiSession(WebSocketSession geminiSession, GeminiLiveWebSocketHandler geminiHandler) {
        WebSocketSession oldGeminiSession = this.geminiSession;
        this.geminiSession = geminiSession;
        this.geminiHandler = geminiHandler;
        this.latestGeminiResumptionHandle = null;
        this.latestGeminiResumable = false;
        this.latestGeminiResumptionUpdatedAt = null;
        return oldGeminiSession;
    }

    /**
     * 세션 번들의 상태를 갱신한다.
     * @param status : 변경할 상태
     */
    public void updateStatus(WebSocketSessionBundleStatus status) {
        this.status = status;
    }

    /**
     * 현재 클라이언트 세션이 전달받은 세션과 동일한지 확인한다.
     * @param session : 비교할 클라이언트 세션
     * @return : 동일 여부
     */
    public boolean matchesClientSession(WebSocketSession session) {
        return Objects.equals(this.clientSession, session);
    }
}
