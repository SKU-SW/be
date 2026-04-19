package com.example.sku_sw.domain.character.dto;

import com.example.sku_sw.domain.character.enums.Personality;
import com.example.sku_sw.domain.character.enums.PresetType;
import com.example.sku_sw.domain.character.enums.SpeechStyle;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record CharacterPersonaReqDto(
        @Schema(description = "캐릭터 프리셋 타입 ", example = "FRIENDLY_CHATTER")
        @NotNull(message = "프리셋 타입은 필수입니다.")
        PresetType presetType,

        @Schema(description = "캐릭터 말투", example = "FRIENDLY_INFORMAL")
        @NotNull(message = "말투는 필수입니다.")
        SpeechStyle speechStyle,

        @Schema(description = "캐릭터 성격", example = "ACTIVE")
        @NotNull(message = "성격은 필수입니다.")
        Personality personality
) {}
