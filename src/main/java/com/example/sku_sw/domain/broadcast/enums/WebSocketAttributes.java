package com.example.sku_sw.domain.broadcast.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * WebSocket Handshake Interceptor에서 WebSocket Handler로 넘길 WebSocket 속성값을 정의해둔 Enum
 */
@Getter
@RequiredArgsConstructor
public enum WebSocketAttributes {
    USER_ID("userId"),  // User PK
    BROADCAST_STREAM_ID("broadcastStreamId"), // 방송 Streaming 고유 ID
    CHARACTER_ID("characterId"),    // 방송 진행 AI 캐릭터 PK
    LAST_PONG_AT("lastPongAt"),     // 가장 마지막에 Pong이 된 시각
    SESSION_GENERATION("sessionGeneration");    // BroadcastWebSocketSessionBundle에 저장되어있는, 번들끼리의 데이터 정합성을 지키기 위한 generation 값

    private final String value;
}
