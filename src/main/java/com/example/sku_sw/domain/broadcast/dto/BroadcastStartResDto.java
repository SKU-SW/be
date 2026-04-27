package com.example.sku_sw.domain.broadcast.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Schema(description = "방송 시작 응답 DTO")
@Builder
public record BroadcastStartResDto(
        @Schema(description = "해당 방송 고유 ID", example = "aB3dE7fG9hJ2kL5m")
        String broadcastStreamId,
        @Schema(description = "방송 시작 시간", example = "2026-04-26-14:30:00")
        String broadcastStartedAt
) {
}
