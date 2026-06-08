package com.example.sku_sw.domain.broadcast.dto;

import com.example.sku_sw.domain.broadcast.enums.AiCharacterTendency;
import com.example.sku_sw.domain.broadcast.enums.TendencyVersion;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "AI 캐릭터 편승 태도 수정 요청")
public record BroadcastTendencyUpdateReqDto(
        @NotNull(message = "version은 필수입니다.")
        @Schema(description = "편승 태도 버전 (AUTO | MANUAL)", example = "MANUAL")
        TendencyVersion version,

        @Schema(description = "편승 태도 값 (POSITIVE | NEUTRAL | NEGATIVE). MANUAL 모드에서만 적용되며, AUTO 모드에서는 무시됩니다.", example = "POSITIVE")
        AiCharacterTendency tendency
) {}
