package com.example.sku_sw.domain.character.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CharacterAppearanceType {
    TWO_D("2D"),
    THREE_D("3D");

    private final String value;
}
