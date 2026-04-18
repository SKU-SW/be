package com.example.sku_sw.domain.character.dto;

import com.example.sku_sw.domain.character.enums.AgeGroup;
import com.example.sku_sw.domain.character.enums.Gender;
import lombok.Builder;

@Builder
public record VoiceTypeResDto(
    Long voiceTypeId,
    String label,
    Gender gender,
    AgeGroup ageGroup,
    String testUrl
) {}
