package com.example.sku_sw.domain.character.dto;

import lombok.Builder;

@Builder
public record CharacterSelectResDto(
    Long selectedCharacterId,
    Long deselectedCharacterId
) {}
