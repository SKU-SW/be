package com.example.sku_sw.domain.broadcast.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Schema(description = "방송 통계 - 채팅 정보 응답 DTO")
@Builder
public record BroadcastDayChatInfoResDto(
        @Schema(description = "채팅 분석 결과")
        Object analysisResult
) {}
