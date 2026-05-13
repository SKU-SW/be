package com.example.sku_sw.domain.character.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Emotion {
    DEFAULT("Default"),
    TALKING("Talking"),
    HAPPY("Happy"),
    ANGRY("Angry"),
    TIRED("Tired"),
    SAD("Sad"),
    FEAR("Fear");

    private final String value;
}
