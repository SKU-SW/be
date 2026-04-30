package com.example.sku_sw.domain.broadcast.dto;

import com.example.sku_sw.domain.character.enums.AgeGroup;
import com.example.sku_sw.domain.character.enums.Gender;
import com.example.sku_sw.domain.character.enums.Personality;
import com.example.sku_sw.domain.character.enums.SpeechStyle;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BroadcastCharacterRedisDto {
    private Long characterId;
    private String characterName;
    private Gender characterGender;
    private List<String> characterTriggerWords;
    private AgeGroup characterVoiceAgeGroup;
    private String characterVoiceTtsId;
    private String characterImagePreset;
    private List<BroadcastCharacterImageRedisDto> characterImages;
    private SpeechStyle characterSpeechStyle;
    private Personality characterPersonality;
    private Boolean isTalking;
}
