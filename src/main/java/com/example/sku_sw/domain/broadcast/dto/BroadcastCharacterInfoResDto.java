package com.example.sku_sw.domain.broadcast.dto;

import com.example.sku_sw.domain.character.enums.Gender;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Schema(description = "현재 방송 중인 AI 캐릭터 정보 응답 DTO")
@Builder
public record BroadcastCharacterInfoResDto(
        @Schema(description = "AI 캐릭터 고유 PK", example = "1")
        Long characterId,

        @Schema(description = "AI 캐릭터 이름", example = "형준")
        String characterName,

        @Schema(description = "AI 캐릭터 호출어 리스트", example = "[\"형준아\", \"야\"]")
        List<String> triggerWords,

        @Schema(description = "AI 캐릭터 성별", example = "MALE")
        Gender gender,

        @Schema(description = "캐릭터 외형 이미지 URL", example = "https://example.com/character.png")
        String characterImageUrl,

        @Schema(description = "AI 캐릭터 페르소나 정보")
        CharacterPersonaInfoResDto characterPersona
) {
}
