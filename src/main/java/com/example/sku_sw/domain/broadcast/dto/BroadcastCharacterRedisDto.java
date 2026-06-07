package com.example.sku_sw.domain.broadcast.dto;

import com.example.sku_sw.domain.broadcast.enums.AiCharacterTendency;
import com.example.sku_sw.domain.character.enums.Gender;
import com.example.sku_sw.domain.character.enums.PresetType;
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
    private String characterImagePreset;
    private List<BroadcastCharacterImageRedisDto> characterImages;
    private PresetType characterPresetType;
    private Boolean isTalking;
    private AiCharacterTendency tendency;
    private Boolean tendencyAutoUpdate;
}
