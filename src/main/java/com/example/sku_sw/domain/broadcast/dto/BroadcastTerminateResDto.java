package com.example.sku_sw.domain.broadcast.dto;

import com.example.sku_sw.domain.broadcast.enums.BroadcastStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Schema(description = "방송 종료 응답 DTO")
@Builder
public record BroadcastTerminateResDto(
        @Schema(description = "종료된 방송 고유 ID", example = "aB3dE7fG9hJ2kL5m")
        String terminatedBroadcastStreamId,

        @Schema(description = "종료된 방송 상태", example = "TERMINATED")
        BroadcastStatus broadcastStatus,

        @Schema(description = "방송 종료 시간", example = "2026-04-25-18:00:00")
        String broadcastTerminatedAt
) {
}
