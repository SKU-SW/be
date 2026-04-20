package com.example.sku_sw.domain.character.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record CharacterSelectReqDto(
        @Schema(description = "선택 설정 값", example = "true")
        @NotNull(message = "선택 여부는 필수입니다.")
        Boolean isSelected
) {}
