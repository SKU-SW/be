package com.example.sku_sw.domain.broadcast.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Schema(description = "방송 통계 조회 응답 DTO")
@Builder
public record BroadcastDayStatsResDto(

        @Schema(description = "캐릭터 정보")
        BroadcastDayCharacterInfoResDto characterInfo,

        @Schema(description = "방송 정보")
        BroadcastDayBroadcastInfoResDto broadcastInfo,

        @Schema(description = "채팅 정보")
        BroadcastDayChatInfoResDto chatAnalysisInfo
) {
}
