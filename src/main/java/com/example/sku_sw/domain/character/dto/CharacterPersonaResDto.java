package com.example.sku_sw.domain.character.dto;

import com.example.sku_sw.domain.character.enums.PresetType;
import lombok.Builder;

@Builder
public record CharacterPersonaResDto(
    PresetType presetType
) {}
