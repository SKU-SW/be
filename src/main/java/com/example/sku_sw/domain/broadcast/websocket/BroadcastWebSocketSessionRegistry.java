package com.example.sku_sw.domain.broadcast.websocket;

import com.example.sku_sw.domain.broadcast.enums.WebSocketSessionBundleStatus;
import com.example.sku_sw.domain.broadcast.websocket.gemini.GeminiLiveWebSocketHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 방송 WebSocket 세션 관리 Registry
 * - 활성 WebSocket 세션을 broadcastStreamId 기준으로 관리한다.
 * - ConcurrentHashMap을 사용하여 동시성을 보장한다.
 */
@Slf4j
@Component
public class BroadcastWebSocketSessionRegistry {

    /**
     * 활성 WebSocket 세션 관리
     * Key: broadcastStreamId, Value: BroadcastWebSocketSessionBundle
     */
    private final ConcurrentHashMap<String, BroadcastWebSocketSessionBundle> sessions = new ConcurrentHashMap<>();
    /**
     * AtomicLong: 자바 멀티스레드 환경에서 안전하게 Long 값을 원자적으로 다루기 위한 클래스
     * - BroadcastWebSocketSessionBundle을 구분할 수 있게 각 번들별로 붙여주는 시퀀스값
     */
    private final AtomicLong generationSequence = new AtomicLong(0L);

    /**
     * 클라이언트 WebSocket 세션을 등록한다.
     * - 동일 streamId로 기존 세션 번들이 있으면 교체되고, 기존 번들을 반환한다.
     *
     * @param broadcastStreamId : 방송 스트림 ID
     * @param clientSession     : 등록할 클라이언트 WebSocket 세션
     * @return : 이전에 등록된 세션 번들 (없으면 null)
     */
    public BroadcastWebSocketSessionBundle registerClientSession(String broadcastStreamId, WebSocketSession clientSession) {
        long generation = generationSequence.incrementAndGet();
        BroadcastWebSocketSessionBundle bundle = BroadcastWebSocketSessionBundle.builder()
                .clientSession(clientSession)
                .generation(generation)
                .status(WebSocketSessionBundleStatus.GEMINI_CONNECTING)
                .build();
        return sessions.put(broadcastStreamId, bundle);
    }

    /**
     * expectedGeneration과 현재 generation이 일치하는 세션 번들에만 Gemini 세션을 등록한다.
     *
     * @param broadcastStreamId : 방송 스트림 ID
     * @param expectedGeneration : 기대하는 generation
     * @param geminiSession     : 등록할 Gemini 세션
     * @return : 등록 성공 여부
     */
    public boolean registerGeminiSessionIfCurrent(String broadcastStreamId, long expectedGeneration, WebSocketSession geminiSession) {
        return registerGeminiSessionIfCurrent(broadcastStreamId, expectedGeneration, geminiSession, null);
    }

    /**
     * expectedGeneration과 현재 generation이 일치하는 세션 번들에만 Gemini 세션과 핸들러를 함께 등록한다.
     *
     * @param broadcastStreamId : 방송 스트림 ID
     * @param expectedGeneration : 기대하는 generation
     * @param geminiSession     : 등록할 Gemini 세션
     * @param geminiHandler     : Gemini Live WebSocket 핸들러 인스턴스
     * @return : 등록 성공 여부
     */
    public boolean registerGeminiSessionIfCurrent(
            String broadcastStreamId,
            long expectedGeneration,
            WebSocketSession geminiSession,
            GeminiLiveWebSocketHandler geminiHandler
    ) {
        final boolean[] registered = {false};
        sessions.computeIfPresent(broadcastStreamId, (key, bundle) -> {
            if (!bundle.matchesGeneration(expectedGeneration)) {
                return bundle;
            }

            bundle.registerGeminiSession(geminiSession, geminiHandler);
            registered[0] = true;
            return bundle;
        });
        return registered[0];
    }

    /**
     * 현재 generation이 일치하는 세션 번들의 상태를 변경한다.
     *
     * @param broadcastStreamId : 방송 스트림 ID
     * @param generation        : 기대하는 generation
     * @param status            : 변경할 상태
     * @return : 변경 성공 여부
     */
    public boolean updateBundleStatusIfCurrent(
            String broadcastStreamId,
            long generation,
            WebSocketSessionBundleStatus status
    ) {
        final boolean[] updated = {false};
        sessions.computeIfPresent(broadcastStreamId, (key, bundle) -> {
            if (!bundle.matchesGeneration(generation)) {
                return bundle;
            }

            bundle.updateStatus(status);
            updated[0] = true;
            return bundle;
        });
        return updated[0];
    }

    /**
     * 방송 종료 요청으로 현재 세션 번들을 완전히 종료한다.
     * - Client/Gemini WebSocket을 모두 닫고 Registry에서 제거한다.
     *
     * @param broadcastStreamId : 방송 스트림 ID
     * @return : 제거된 세션 번들 (없으면 null)
     */
    public BroadcastWebSocketSessionBundle disconnect(String broadcastStreamId) {
        return disconnect(broadcastStreamId, CloseStatus.NORMAL.withReason("Broadcast terminated"));
    }

    /**
     * 방송 종료 요청으로 현재 세션 번들을 완전히 종료한다.
     * - Client/Gemini WebSocket을 모두 닫고 Registry에서 제거한다.
     *
     * @param broadcastStreamId : 방송 스트림 ID
     * @param closeStatus       : 종료에 사용할 CloseStatus
     * @return : 제거된 세션 번들 (없으면 null)
     */
    public BroadcastWebSocketSessionBundle disconnect(String broadcastStreamId, CloseStatus closeStatus) {
        return removeSessionBundle(broadcastStreamId, closeStatus, "disconnect");
    }

    /**
     * WebSocket 세션 번들을 제거한다.
     * - 제거 성공 시 Client/Gemini WebSocket을 모두 닫는다.
     *
     * @param broadcastStreamId : 방송 스트림 ID
     * @return : 제거된 세션 번들 (없으면 null)
     */
    public BroadcastWebSocketSessionBundle removeSessionBundle(String broadcastStreamId) {
        return removeSessionBundle(broadcastStreamId, CloseStatus.NORMAL.withReason("Session removed"), "removeSessionBundle");
    }

    /**
     * WebSocket 세션 번들을 제거한다.
     * - 제거 성공 시 Client/Gemini WebSocket을 모두 닫는다.
     *
     * @param broadcastStreamId : 방송 스트림 ID
     * @param closeStatus       : 종료에 사용할 CloseStatus
     * @param caller            : 호출자명
     * @return : 제거된 세션 번들 (없으면 null)
     */
    public BroadcastWebSocketSessionBundle removeSessionBundle(String broadcastStreamId, CloseStatus closeStatus, String caller) {
        BroadcastWebSocketSessionBundle bundle = sessions.get(broadcastStreamId);
        if (bundle == null) {
            return null;
        }

        boolean removed = sessions.remove(broadcastStreamId, bundle);
        if (!removed) {
            return null;
        }

        terminateBundle(bundle, closeStatus, caller, broadcastStreamId);
        return bundle;
    }

    /**
     * 현재 등록된 세션 번들의 클라이언트 세션이 전달받은 클라이언트 세션과 동일한 경우에만 제거한다.
     * - 재연결 상황에서 이전 세션의 close 이벤트가 새 세션을 제거하지 않도록 방지한다.
     *
     * @param broadcastStreamId : 방송 스트림 ID
     * @param clientSession     : 제거 기준이 되는 클라이언트 WebSocket 세션
     * @return : 제거 성공 여부
     */
    public boolean removeSessionBundle(String broadcastStreamId, WebSocketSession clientSession) {
        return removeSessionBundle(
                broadcastStreamId,
                clientSession,
                CloseStatus.NORMAL.withReason("Client session removed"),
                "removeSessionBundle"
        );
    }

    /**
     * 현재 등록된 세션 번들의 클라이언트 세션이 전달받은 클라이언트 세션과 동일한 경우에만 제거한다.
     * - 재연결 상황에서 이전 세션의 close 이벤트가 새 세션을 제거하지 않도록 방지한다.
     * - 제거 성공 시 Client/Gemini WebSocket을 모두 닫는다.
     *
     * @param broadcastStreamId : 방송 스트림 ID
     * @param clientSession     : 제거 기준이 되는 클라이언트 WebSocket 세션
     * @param closeStatus       : 종료에 사용할 CloseStatus
     * @param caller            : 호출자명
     * @return : 제거 성공 여부
     */
    public boolean removeSessionBundle(
            String broadcastStreamId,
            WebSocketSession clientSession,
            CloseStatus closeStatus,
            String caller
    ) {
        BroadcastWebSocketSessionBundle bundle = sessions.get(broadcastStreamId);
        if (bundle == null || !bundle.matchesClientSession(clientSession)) {
            return false;
        }

        boolean removed = sessions.remove(broadcastStreamId, bundle);
        if (!removed) {
            return false;
        }

        terminateBundle(bundle, closeStatus, caller, broadcastStreamId);
        return true;
    }

    /**
     * 현재 generation이 일치하는 경우에만 세션 번들을 제거한다.
     *
     * @param broadcastStreamId : 방송 스트림 ID
     * @param generation        : 기대하는 generation
     * @return : 제거된 세션 번들 (없으면 null)
     */
    public BroadcastWebSocketSessionBundle removeSessionBundleIfCurrent(String broadcastStreamId, long generation) {
        return removeSessionBundleIfCurrent(
                broadcastStreamId,
                generation,
                CloseStatus.NORMAL.withReason("Current session removed"),
                "removeSessionBundleIfCurrent"
        );
    }

    /**
     * 현재 generation이 일치하는 경우에만 세션 번들을 제거한다.
     * - 제거 성공 시 Client/Gemini WebSocket을 모두 닫는다.
     *
     * @param broadcastStreamId : 방송 스트림 ID
     * @param generation        : 기대하는 generation
     * @param closeStatus       : 종료에 사용할 CloseStatus
     * @param caller            : 호출자명
     * @return : 제거된 세션 번들 (없으면 null)
     */
    public BroadcastWebSocketSessionBundle removeSessionBundleIfCurrent(
            String broadcastStreamId,
            long generation,
            CloseStatus closeStatus,
            String caller
    ) {
        BroadcastWebSocketSessionBundle bundle = sessions.get(broadcastStreamId);
        if (bundle == null || !bundle.matchesGeneration(generation)) {
            return null;
        }

        boolean removed = sessions.remove(broadcastStreamId, bundle);
        if (!removed) {
            return null;
        }

        terminateBundle(bundle, closeStatus, caller, broadcastStreamId);
        return bundle;
    }

    /**
     * BroadcastStreamId로 세션 번들을 제거한다.
     *
     * @param broadcastStreamId : 방송 스트림 ID
     * @return : 제거된 세션 번들 (없으면 null)
     */
    public BroadcastWebSocketSessionBundle removeSessionBundleIfCurrent(String broadcastStreamId) {
        return removeSessionBundleIfCurrent(
                broadcastStreamId,
                CloseStatus.NORMAL.withReason("Current session removed"),
                "removeSessionBundleIfCurrent"
        );
    }

    /**
     * BroadcastStreamId로 세션 번들을 제거한다.
     * - 제거 성공 시 Client/Gemini WebSocket을 모두 닫는다.
     *
     * @param broadcastStreamId : 방송 스트림 ID
     * @param closeStatus       : 종료에 사용할 CloseStatus
     * @param caller            : 호출자명
     * @return : 제거된 세션 번들 (없으면 null)
     */
    public BroadcastWebSocketSessionBundle removeSessionBundleIfCurrent(
            String broadcastStreamId,
            CloseStatus closeStatus,
            String caller
    ) {
        BroadcastWebSocketSessionBundle bundle = sessions.get(broadcastStreamId);
        if (bundle == null) {
            return null;
        }

        boolean removed = sessions.remove(broadcastStreamId, bundle);
        if (!removed) {
            return null;
        }

        terminateBundle(bundle, closeStatus, caller, broadcastStreamId);
        return bundle;
    }

    /**
     * 해당 broadcastStreamId의 WebSocket 세션 번들이 존재하는지 확인한다.
     *
     * @param broadcastStreamId : 방송 스트림 ID
     * @return : 세션 번들 존재 여부
     */
    public boolean hasSessionBundle(String broadcastStreamId) {
        BroadcastWebSocketSessionBundle bundle = sessions.get(broadcastStreamId);
        if (bundle == null) {
            return false;
        }

        if (bundle.isClientSessionOpen()) {
            return true;
        }

        removeSessionBundleIfCurrent(
                broadcastStreamId,
                bundle.getGeneration(),
                CloseStatus.NORMAL.withReason("Client session already closed"),
                "hasSessionBundle"
        );
        return false;
    }

    /**
     * 해당 broadcastStreamId의 WebSocket 세션 번들을 조회한다.
     *
     * @param broadcastStreamId : 방송 스트림 ID
     * @return : WebSocket 세션 번들 (없으면 null)
     */
    public BroadcastWebSocketSessionBundle getSessionBundle(String broadcastStreamId) {
        return sessions.get(broadcastStreamId);
    }

    /**
     * 현재 generation이 일치하는 세션 번들을 조회한다.
     *
     * @param broadcastStreamId : 방송 스트림 ID
     * @param generation        : 기대하는 generation
     * @return : 현재 generation의 세션 번들 (없으면 null)
     */
    public BroadcastWebSocketSessionBundle getSessionBundleIfCurrent(String broadcastStreamId, long generation) {
        BroadcastWebSocketSessionBundle bundle = sessions.get(broadcastStreamId);
        if (bundle == null || !bundle.matchesGeneration(generation)) {
            return null;
        }
        return bundle;
    }

    /**
     * 현재 generation의 클라이언트 세션인지 확인한다.
     *
     * @param broadcastStreamId : 방송 스트림 ID
     * @param generation        : 기대하는 generation
     * @param clientSession     : 비교할 클라이언트 세션
     * @return : 현재 세션 여부
     */
    public boolean isCurrentClientSession(String broadcastStreamId, long generation, WebSocketSession clientSession) {
        BroadcastWebSocketSessionBundle bundle = sessions.get(broadcastStreamId);
        return bundle != null
                && bundle.matchesGeneration(generation)
                && bundle.matchesClientSession(clientSession);
    }

    /**
     * Gemini 세션과 매칭되는 방송 스트림 ID를 조회한다.
     *
     * @param geminiSession : 조회할 Gemini 세션
     * @return : 방송 스트림 ID (없으면 null)
     */
    public String findBroadcastStreamIdByGeminiSession(WebSocketSession geminiSession) {
        for (Map.Entry<String, BroadcastWebSocketSessionBundle> entry : sessions.entrySet()) {
            BroadcastWebSocketSessionBundle bundle = entry.getValue();
            if (bundle != null && Objects.equals(bundle.getGeminiSession(), geminiSession)) {
                return entry.getKey();
            }
        }

        return null;
    }

    /**
     * 활성 세션 수를 반환한다.
     *
     * @return 활성 WebSocket 세션 수
     */
    public int getActiveSessionCountFromBundle() {
        return sessions.size();
    }

    /**
     * 전체 활성 세션 번들의 스냅샷을 반환한다.
     * - 순회 중 동시 수정이 발생해도 안전하도록 복사본을 반환한다.
     *
     * @return 활성 세션 엔트리 Set (변경 불가)
     */
    public Set<Map.Entry<String, BroadcastWebSocketSessionBundle>> getActiveSessionBundlesSnapshot() {
        return Collections.unmodifiableSet(new HashSet<>(sessions.entrySet()));
    }

    /**
     * Registry에 더 이상 연결되어 있지 않은 세션 번들의 리소스를 정리한다.
     * - 재연결로 교체된 이전 번들 정리에 사용한다.
     *
     * @param bundle            : 정리할 세션 번들
     * @param closeStatus       : 종료에 사용할 CloseStatus
     * @param caller            : 호출자명
     * @param broadcastStreamId : 방송 스트림 ID
     */
    public void closeDetachedSessionBundle(
            BroadcastWebSocketSessionBundle bundle,
            CloseStatus closeStatus,
            String caller,
            String broadcastStreamId
    ) {
        if (bundle == null) {
            return;
        }

        closeBundleSessions(bundle, closeStatus, caller, broadcastStreamId);
        log.info("[BroadcastWebSocketSessionRegistry] {}() - Detached session bundle closed | streamId: {}, generation: {}",
                caller, broadcastStreamId, bundle.getGeneration());
    }

    /**
     * Registry에서 제거된 세션 번들의 리소스를 정리한다.
     * - Client/Gemini WebSocket을 모두 닫고 상태를 CLOSING으로 갱신한다.
     *
     * @param bundle            : 정리할 세션 번들
     * @param closeStatus       : 종료에 사용할 CloseStatus
     * @param caller            : 호출자명
     * @param broadcastStreamId : 방송 스트림 ID
     */
    private void terminateBundle(
            BroadcastWebSocketSessionBundle bundle,
            CloseStatus closeStatus,
            String caller,
            String broadcastStreamId
    ) {
        if (bundle == null) {
            return;
        }

        closeBundleSessions(bundle, closeStatus, caller, broadcastStreamId);

        log.info("[BroadcastWebSocketSessionRegistry] {}() - Session bundle removed | streamId: {}, generation: {}",
                caller, broadcastStreamId, bundle.getGeneration());
    }

    /**
     * 세션 번들의 Client/Gemini WebSocket을 모두 닫는다.
     *
     * @param bundle            : 정리할 세션 번들
     * @param closeStatus       : 종료에 사용할 CloseStatus
     * @param caller            : 호출자명
     * @param broadcastStreamId : 방송 스트림 ID
     */
    private void closeBundleSessions(
            BroadcastWebSocketSessionBundle bundle,
            CloseStatus closeStatus,
            String caller,
            String broadcastStreamId
    ) {
        bundle.updateStatus(WebSocketSessionBundleStatus.CLOSING);
        closeSessionQuietly(bundle.getClientSession(), closeStatus, caller, "Client", broadcastStreamId);
        closeSessionQuietly(bundle.getGeminiSession(), closeStatus, caller, "Gemini", broadcastStreamId);
    }

    /**
     * WebSocket 세션을 조용히 종료한다.
     *
     * @param session           : 종료할 세션
     * @param closeStatus       : 종료에 사용할 CloseStatus
     * @param caller            : 호출자명
     * @param sessionLabel      : 세션 구분 라벨
     * @param broadcastStreamId : 방송 스트림 ID
     */
    private void closeSessionQuietly(
            WebSocketSession session,
            CloseStatus closeStatus,
            String caller,
            String sessionLabel,
            String broadcastStreamId
    ) {
        if (session == null || !session.isOpen()) {
            return;
        }

        try {
            session.close(closeStatus);
        } catch (IOException e) {
            log.warn("[BroadcastWebSocketSessionRegistry] {}() - Failed to close {} session | streamId: {}",
                    caller, sessionLabel, broadcastStreamId);
        }
    }
}
