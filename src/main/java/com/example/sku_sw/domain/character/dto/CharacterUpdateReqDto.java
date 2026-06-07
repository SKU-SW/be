package com.example.sku_sw.domain.character.dto;

import com.example.sku_sw.domain.character.enums.CharacterAppearanceType;
import com.example.sku_sw.domain.character.enums.Gender;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record CharacterUpdateReqDto(
    @Schema(description = "캐릭터 외형 타입 (TWO_D | THREE_D)", example = "TWO_D")
    @NotNull(message = "캐릭터 외형 타입은 필수입니다.")
    CharacterAppearanceType characterAppearanceType,

    @Schema(description = "설정할 AI 캐릭터의 이름", example = "형준")
    @NotBlank(message = "캐릭터 이름은 필수입니다.")
    String characterName,

    @Schema(description = "캐릭터 호출어 리스트", example = "형준아")
    @NotNull(message = "호출어는 필수입니다.")
    List<@NotBlank String> triggerWords,

    @Schema(description = "캐릭터 성별", example = "MALE")
    @NotNull(message = "성별은 필수입니다.")
    Gender gender,

    @Schema(description = "캐릭터 이미지/VRM PK (2D인 경우 CharacterImage ID, 3D인 경우 CharacterVrm ID)", example = "1")
    @NotNull(message = "타겟 ID는 필수입니다.")
    Long targetId,

    @Valid @NotNull(message = "페르소나는 필수입니다.")
    CharacterPersonaReqDto characterPersona
) {}
