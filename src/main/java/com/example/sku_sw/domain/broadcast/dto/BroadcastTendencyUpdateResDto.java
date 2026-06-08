package com.example.sku_sw.domain.broadcast.dto;

import com.example.sku_sw.domain.broadcast.enums.AiCharacterTendency;
import com.example.sku_sw.domain.broadcast.enums.TendencyVersion;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "AI 캐릭터 편승 태도 수정 응답")
public record BroadcastTendencyUpdateResDto(
        @Schema(description = "이전 편승 태도 버전 (AUTO | MANUAL)", example = "AUTO")
        TendencyVersion prevVersion,

        @Schema(description = "이전 편승 태도 값 (POSITIVE | NEUTRAL | NEGATIVE)", example = "POSITIVE")
        AiCharacterTendency prevTendency
) {}
