package com.example.sku_sw.domain.broadcast.dto;

import com.example.sku_sw.domain.character.enums.AgeGroup;
import com.example.sku_sw.domain.character.enums.Gender;
import com.example.sku_sw.domain.character.enums.Personality;
import com.example.sku_sw.domain.character.enums.SpeechStyle;
import java.util.List;
import lombok.Builder;

@Builder
public record BroadcastCharacterRedisDto(
        Long characterId,
        String characterName,
        Gender characterGender,
        AgeGroup characterVoiceAgeGroup,
        String characterVoiceTtsId,
        String characterImagePreset,
        List<BroadcastCharacterImageRedisDto> characterImages,
        SpeechStyle characterSpeechStyle,
        Personality characterPersonality
) {
}
