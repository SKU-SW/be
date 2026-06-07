package com.example.sku_sw.domain.broadcast.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Gemini WebSocket 세션을 로컬에서 종료할 때 사용하는 종료 사유 Enum
 */
@Getter
@RequiredArgsConstructor
public enum GeminiSessionCloseReason {
    SETUP_FAILED("Gemini setup failed"),
    STALE_CLIENT_SESSION("Stale client session detected"),
    REFRESH_NEW_SESSION_DISCARDED("Refresh target bundle missing"),
    REFRESH_REPLACED_OLD_SESSION("Gemini refresh replaced old session"),
    FAILED_BUNDLE_CLEANUP("Failed bundle cleanup"),
    REPLACED_BY_NEW_CONNECTION("Replaced by new client connection"),
    CLIENT_SESSION_CLOSED("Client session closed"),
    SESSION_REGISTRY_DISCONNECT("Session registry disconnect"),
    SESSION_REGISTRY_CLEANUP("Session registry cleanup");

    private final String description;
}
