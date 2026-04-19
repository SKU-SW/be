package com.example.sku_sw.domain.character.dto;

import com.example.sku_sw.domain.character.enums.Gender;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record CharacterCreateReqDto(
        @Schema(description = "설정할 AI 캐릭터의 이름", example = "형준")
        @NotBlank(message = "캐릭터 이름은 필수입니다.")
        String characterName,

        @Schema(description = "캐릭터 호출어 리스트", example = "형준아")
        @NotNull(message = "호출어는 필수입니다.")
        List<@NotBlank String> triggerWords,

        @Schema(description = "캐릭터 성별", example = "MALE")
        @NotNull(message = "성별은 필수입니다.")
        Gender gender,

        @Schema(description = "캐릭터 목소리 PK", example = "1")
        @NotNull(message = "캐릭터 목소리 PK는 필수입니다.")
        Long voiceTypeId,

        @Schema(description = "캐릭터 이미지 PK", example = "1")
        @NotNull(message = "캐릭터 이미지 PK는 필수입니다.")
        Long characterImageId,

        @Schema(description = "캐릭터 페르소나")
        @Valid @NotNull(message = "페르소나는 필수입니다.")
        CharacterPersonaReqDto characterPersona
) {}
