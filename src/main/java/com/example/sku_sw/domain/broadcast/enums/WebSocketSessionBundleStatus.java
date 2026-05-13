package com.example.sku_sw.domain.broadcast.enums;

/**
 * 방송 WebSocket 세션 번들 상태
 * - 클라이언트 세션과 Gemini 세션의 준비 상태를 표현한다.
 */
public enum WebSocketSessionBundleStatus {
    GEMINI_CONNECTING,
    READY,
    FAILED,
    CLOSING;

    /**
     * 현재 상태가 잠금 상태인지 확인한다.
     *
     * @return : 잠금 상태 여부
     */
    public boolean isLocked() {
        return this != READY;
    }
}
