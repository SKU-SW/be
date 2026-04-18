package com.example.sku_sw.domain.character.dto;

import com.example.sku_sw.domain.character.enums.Gender;
import lombok.Builder;

@Builder
public record CharacterImageResDto(
    Long imageId,
    Gender gender,
    String name,
    String imageUrl
) {}
