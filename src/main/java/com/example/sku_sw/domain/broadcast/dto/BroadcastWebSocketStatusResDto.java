package com.example.sku_sw.domain.broadcast.dto;

import lombok.Builder;

@Builder
public record BroadcastWebSocketStatusResDto(
        String status,
        String message
) {
}
