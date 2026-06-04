package com.example.sku_sw.domain.character.dto;

import com.example.sku_sw.domain.character.enums.Gender;
import java.util.List;
import lombok.Builder;

@Builder
public record CharacterDetailResDto(
    Long characterId,
    String characterName,
    List<String> triggerWords,
    Gender gender,
    String characterImageUrl,
    CharacterPersonaResDto characterPersona,
    boolean isSelected
) {}
