package com.example.sku_sw.domain.broadcast.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 클라이언트 WebSocket으로 전달하는 음성 메타데이터 이벤트 타입
 */
@Getter
@RequiredArgsConstructor
public enum BroadcastVoiceEventType {
    VOICE_CHUNK("VOICE_CHUNK"),
    VOICE_TURN_COMPLETE("VOICE_TURN_COMPLETE");

    private final String value;
}
