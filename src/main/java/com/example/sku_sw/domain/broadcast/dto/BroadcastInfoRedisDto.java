package com.example.sku_sw.domain.broadcast.dto;

import com.example.sku_sw.domain.broadcast.enums.BroadcastInfoRole;
import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record BroadcastInfoRedisDto(
        BroadcastInfoRole role,
        String message,
        LocalDateTime createdAt
) {
}
