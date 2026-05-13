package com.example.sku_sw.domain.broadcast.websocket;

import com.example.sku_sw.domain.broadcast.enums.WebSocketSessionBundleStatus;
import lombok.Builder;
import lombok.Getter;
import org.springframework.web.socket.WebSocketSession;

import java.util.Objects;

/**
 * 방송 스트림 단위의 WebSocket 세션 묶음
 * - 클라이언트 WebSocket 세션과 Gemini Live API WebSocket 세션을 함께 관리한다.
 */
@Getter
@Builder
public class BroadcastWebSocketSessionBundle {

    private final WebSocketSession clientSession;
    private final long generation;
    private WebSocketSession geminiSession;
    private WebSocketSessionBundleStatus status;

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
     * 현재 세션 번들이 잠금 상태인지 확인한다.
     * @return : 잠금 상태 여부
     */
    public boolean isLocked() {
        return status.isLocked();
    }

    /**
     * Gemini 세션을 등록한다.
     * @param geminiSession : 등록할 Gemini 세션
     * @return : 기존 Gemini 세션
     */
    public WebSocketSession registerGeminiSession(WebSocketSession geminiSession) {
        WebSocketSession oldGeminiSession = this.geminiSession;
        this.geminiSession = geminiSession;
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
