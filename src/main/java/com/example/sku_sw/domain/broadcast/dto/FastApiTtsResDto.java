package com.example.sku_sw.domain.broadcast.dto;

import lombok.Builder;

@Builder
public record FastApiTtsResDto(
        Long characterId,
        String voiceText,
        Long broadcastDialogueId,
        byte[] voiceData
) {
}
