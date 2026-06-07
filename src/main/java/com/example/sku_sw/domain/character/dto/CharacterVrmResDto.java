package com.example.sku_sw.domain.character.dto;

import com.example.sku_sw.domain.character.enums.Gender;
import lombok.Builder;

@Builder
public record CharacterVrmResDto(
    Long characterVrmId,
    String presetId,
    Gender gender,
    String name,
    String thumbnailUrl,
    String vrmUrl
) {}
