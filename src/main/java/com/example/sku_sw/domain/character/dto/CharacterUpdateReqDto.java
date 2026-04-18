package com.example.sku_sw.domain.character.dto;

import com.example.sku_sw.domain.character.enums.Gender;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record CharacterUpdateReqDto(
    @NotBlank(message = "캐릭터 이름은 필수입니다.")
    String characterName,

    @NotNull(message = "호출어는 필수입니다.")
    List<@NotBlank String> triggerWords,

    @NotNull(message = "성별은 필수입니다.")
    Gender gender,

    @NotNull(message = "목소리 타입은 필수입니다.")
    Long voiceTypeId,

    @NotNull(message = "캐릭터 이미지는 필수입니다.")
    Long characterImageId,

    @Valid @NotNull(message = "페르소나는 필수입니다.")
    CharacterPersonaReqDto characterPersona
) {}
