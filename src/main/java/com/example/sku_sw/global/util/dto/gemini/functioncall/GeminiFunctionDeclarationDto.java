package com.example.sku_sw.global.util.dto.gemini.functioncall;

import java.util.Map;

public record GeminiFunctionDeclarationDto(
        String name,
        String description,
        Map<String, Object> parameters
) {
}
