package com.example.sku_sw.domain.broadcast.dto;

import com.example.sku_sw.domain.character.enums.PresetType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Schema(description = "현재 방송 중인 AI 캐릭터 페르소나 정보 응답 DTO")
@Builder
public record CharacterPersonaInfoResDto(
        @Schema(description = "AI 캐릭터 프리셋 타입", example = "FRIENDLY_CHATTER")
        PresetType presetType
) {
}
