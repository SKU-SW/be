package com.example.sku_sw.domain.character.dto;

import com.example.sku_sw.domain.character.enums.Personality;
import com.example.sku_sw.domain.character.enums.PresetType;
import com.example.sku_sw.domain.character.enums.SpeechStyle;
import java.util.List;
import lombok.Builder;

@Builder
public record CharacterSettingsResDto(
    List<VoiceTypeResDto> voiceTypes,
    List<CharacterImageResDto> characterImages,
    List<PresetType> presetTypes,
    List<SpeechStyle> speechStyles,
    List<Personality> personalities
) {}
