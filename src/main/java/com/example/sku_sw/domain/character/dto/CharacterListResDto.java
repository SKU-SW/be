package com.example.sku_sw.domain.character.dto;

import com.example.sku_sw.domain.character.enums.Gender;
import com.example.sku_sw.domain.character.enums.PresetType;
import java.util.List;
import lombok.Builder;

@Builder
public record CharacterListResDto(
    Long characterId,
    String characterName,
    Gender gender,
    String characterImageUrl,
    List<String> triggerWords,
    PresetType presetType,
    boolean isSelected
) {}
