package com.example.sku_sw.domain.broadcast.dto;

import com.example.sku_sw.domain.character.enums.Emotion;
import lombok.Builder;

@Builder
public record BroadcastCharacterImageRedisDto(
        Emotion emotion,
        String imageUrl
) {
}
