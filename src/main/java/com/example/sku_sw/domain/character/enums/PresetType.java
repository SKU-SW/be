package com.example.sku_sw.domain.character.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PresetType {
    FRIENDLY_CHATTER("소통 전문", SpeechStyle.FRIENDLY_INFORMAL, Personality.HUMOROUS),
    HIGH_TENSION("리액션 전문", SpeechStyle.BROADCAST_EXAGGERATED, Personality.ACTIVE),
    PLAYFUL_TEASER("놀림 전문", SpeechStyle.PLAYFUL_INFORMAL, Personality.HUMOROUS),
    PROFESSIONAL_MANAGER("차분한 성격", SpeechStyle.POLITE_FORMAL, Personality.CALM),
    ROLEPLAY_EXPERT("몰입하는 성격", SpeechStyle.POLITE_FORMAL, Personality.SERIOUS),
    CUSTOM("사용자 커스텀 프리셋", null, null);
    
    private final String description;
    private final SpeechStyle speechStyle;
    private final Personality personality;
}
