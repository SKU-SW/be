package com.example.sku_sw.domain.broadcast.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
     * Key: broadcastStreamId, Value: WebSocketSession
     */
    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    /**
     * WebSocket 세션을 등록한다.
     * - 동일 streamId로 기존 세션이 있으면 교체되고, 기존 세션을 반환한다.
     *
     * @param broadcastStreamId : 방송 스트림 ID
     * @param session           : 등록할 WebSocket 세션
     * @return : 이전에 등록된 세션 (없으면 null)
     */
    public WebSocketSession registerSession(String broadcastStreamId, WebSocketSession session) {
        return sessions.put(broadcastStreamId, session);
    }

    /**
     * WebSocket 세션을 제거한다.
     *
     * @param broadcastStreamId : 방송 스트림 ID
     * @return : 제거된 세션 (없으면 null)
     */
    public WebSocketSession removeSession(String broadcastStreamId) {
        return sessions.remove(broadcastStreamId);
    }

    /**
     * 현재 등록된 세션이 전달받은 세션과 동일한 경우에만 제거한다.
     * - 재연결 상황에서 이전 세션의 close 이벤트가 새 세션을 제거하지 않도록 방지한다.
     *
     * @param broadcastStreamId : 방송 스트림 ID
     * @param session           : 제거 기준이 되는 WebSocket 세션
     * @return : 제거 성공 여부
     */
    public boolean removeSession(String broadcastStreamId, WebSocketSession session) {
        return sessions.remove(broadcastStreamId, session);
    }

    /**
     * 해당 broadcastStreamId의 WebSocket 세션이 존재하는지 확인한다.
     *
     * @param broadcastStreamId : 방송 스트림 ID
     * @return : 세션 존재 여부
     */
    public boolean hasSession(String broadcastStreamId) {
        WebSocketSession session = sessions.get(broadcastStreamId);
        if (session == null) {
            return false;
        }
        if (session.isOpen()) {
            return true;
        }
        sessions.remove(broadcastStreamId, session);
        return false;
    }

    /**
     * 해당 broadcastStreamId의 WebSocket 세션을 조회한다.
     *
     * @param broadcastStreamId : 방송 스트림 ID
     * @return : WebSocket 세션 (없으면 null)
     */
    public WebSocketSession getSession(String broadcastStreamId) {
        return sessions.get(broadcastStreamId);
    }

    /**
     * 활성 세션 수를 반환한다.
     *
     * @return 활성 WebSocket 세션 수
     */
    public int getActiveSessionCount() {
        return sessions.size();
    }

    /**
     * 전체 활성 세션의 스냅샷을 반환한다.
     * - 순회 중 동시 수정이 발생해도 안전하도록 복사본을 반환한다.
     *
     * @return 활성 세션 엔트리 Set (변경 불가)
     */
    public Set<Map.Entry<String, WebSocketSession>> getActiveSessionsSnapshot() {
        return Collections.unmodifiableSet(new HashSet<>(sessions.entrySet()));
    }
}
