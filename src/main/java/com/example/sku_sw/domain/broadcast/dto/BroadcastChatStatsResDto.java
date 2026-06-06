package com.example.sku_sw.domain.broadcast.dto;

import com.example.sku_sw.domain.broadcast.enums.AiCharacterTendency;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Schema(description = "방송 채팅 통계 응답 DTO")
@Builder
public record BroadcastChatStatsResDto(
    @Schema(description = "여론 현황")
    PublicOpinionResDto publicOpinion,

    @Schema(description = "AI 파트너 응답 성향", example = "POSITIVE")
    AiCharacterTendency aiPartnerTendency
) {
    @Schema(description = "여론 현황 상세")
    @Builder
    public record PublicOpinionResDto(
        @Schema(description = "긍정 채팅 수")
        Integer positiveChatCount,
        @Schema(description = "중립 채팅 수")
        Integer neutralChatCount,
        @Schema(description = "부정 채팅 수")
        Integer negativeChatCount,
        @Schema(description = "전체 채팅 수")
        Integer totalChatCount,
        @Schema(description = "긍정 비율 (%)")
        Double positiveRatio,
        @Schema(description = "중립 비율 (%)")
        Double neutralRatio,
        @Schema(description = "부정 비율 (%)")
        Double negativeRatio
    ) {}
}
