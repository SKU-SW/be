package com.example.sku_sw.domain.character.dto;

import com.example.sku_sw.domain.character.enums.PresetType;
import java.util.List;
import lombok.Builder;

@Builder
public record CharacterSettingsResDto(
    List<CharacterImageResDto> characterImages,
    List<CharacterVrmResDto> vrmPresets,
    List<PresetType> personaPresetTypes
) {}
