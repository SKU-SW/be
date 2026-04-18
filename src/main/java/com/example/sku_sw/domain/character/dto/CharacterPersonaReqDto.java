package com.example.sku_sw.domain.character.dto;

import com.example.sku_sw.domain.character.enums.Personality;
import com.example.sku_sw.domain.character.enums.PresetType;
import com.example.sku_sw.domain.character.enums.SpeechStyle;
import jakarta.validation.constraints.NotNull;

public record CharacterPersonaReqDto(
    @NotNull(message = "프리셋 타입은 필수입니다.")
    PresetType presetType,

    @NotNull(message = "말투는 필수입니다.")
    SpeechStyle speechStyle,

    @NotNull(message = "성격은 필수입니다.")
    Personality personality
) {}
