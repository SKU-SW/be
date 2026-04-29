package com.example.sku_sw.domain.broadcast.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum WebSocketAttributes {
    USER_ID("userId"),
    BROADCAST_STREAM_ID("broadcastStreamId"),
    CHARACTER_ID("characterId"),
    LAST_PONG_AT("lastPongAt");

    private final String value;
}
