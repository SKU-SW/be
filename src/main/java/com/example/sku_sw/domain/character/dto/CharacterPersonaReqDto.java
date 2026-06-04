package com.example.sku_sw.domain.character.dto;

import com.example.sku_sw.domain.character.enums.PresetType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record CharacterPersonaReqDto(
        @Schema(description = "캐릭터 프리셋 타입", example = "FRIENDLY_CHATTER")
        @NotNull(message = "프리셋 타입은 필수입니다.")
        PresetType presetType
) {}
