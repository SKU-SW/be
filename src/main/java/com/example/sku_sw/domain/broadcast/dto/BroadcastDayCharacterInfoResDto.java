package com.example.sku_sw.domain.broadcast.dto;

import com.example.sku_sw.domain.character.enums.Gender;
import com.example.sku_sw.domain.character.enums.PresetType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Schema(description = "방송 통계 - 캐릭터 정보 응답 DTO")
@Builder
public record BroadcastDayCharacterInfoResDto(

        @Schema(description = "AI 캐릭터 이름", example = "형준")
        String name,

        @Schema(description = "AI 캐릭터 성별", example = "MALE")
        Gender gender,

        @Schema(description = "캐릭터 외형 이미지 URL", example = "https://example.com/character.png")
        String imageUrl,

        @Schema(description = "AI 캐릭터 페르소나", example = "FRIENDLY_CHATTER")
        PresetType persona,

        @Schema(description = "AI 캐릭터 호출어 리스트", example = "[\"형준아\", \"야\"]")
        List<String> triggerWords
) {
    public static BroadcastDayCharacterInfoResDto create(
            String name,
            Gender gender,
            String imageUrl,
            PresetType persona,
            List<String> triggerWords
    ){
        return BroadcastDayCharacterInfoResDto.builder()
                .name(name)
                .gender(gender)
                .imageUrl(imageUrl)
                .persona(persona)
                .triggerWords(triggerWords)
                .build();
    }
}
