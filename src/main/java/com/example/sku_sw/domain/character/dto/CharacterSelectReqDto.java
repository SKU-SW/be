package com.example.sku_sw.domain.character.dto;

import jakarta.validation.constraints.NotNull;

public record CharacterSelectReqDto(
    @NotNull(message = "선택 여부는 필수입니다.")
    Boolean isSelected
) {}
