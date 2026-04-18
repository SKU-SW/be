package com.example.sku_sw.domain.character.dto;

import com.example.sku_sw.domain.character.enums.Personality;
import com.example.sku_sw.domain.character.enums.PresetType;
import com.example.sku_sw.domain.character.enums.SpeechStyle;
import lombok.Builder;

@Builder
public record CharacterPersonaResDto(
    PresetType presetType,
    SpeechStyle speechStyle,
    Personality personality
) {}
