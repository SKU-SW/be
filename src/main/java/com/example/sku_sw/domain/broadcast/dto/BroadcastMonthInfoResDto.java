package com.example.sku_sw.domain.broadcast.dto;

import com.example.sku_sw.domain.broadcast.enums.BroadcastStatus;
import lombok.Builder;

@Builder
public record BroadcastMonthInfoResDto(
        int day,
        Long broadcastId,
        Long characterId,
        String characterName,
        BroadcastStatus broadcastStatus,
        String broadcastTime
) {}
