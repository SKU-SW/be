package com.example.sku_sw.domain.broadcast.dto;

import com.example.sku_sw.domain.broadcast.enums.AiCharacterTendency;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.util.List;

@Schema(description = "방송 통계 - 채팅 정보 응답 DTO")
@Builder
public record BroadcastDayChatInfoResDto(
        @Schema(description = "여론 현황")
        BroadcastChatStatsResDto.PublicOpinionResDto publicOpinion,

        @Schema(description = "AI 파트너 응답 성향", example = "POSITIVE")
        AiCharacterTendency aiPartnerTendency,

        @Schema(description = "감정 흐름 통계")
        List<BroadcastChatStatsResDto.SentimentFlowItemResDto> sentimentFlow,

        @Schema(description = "상위 키워드 (1~10위)")
        List<String> topKeywords
) {}
