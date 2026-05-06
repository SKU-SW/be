package com.example.sku_sw.global.util.dto.gemini.functioncall;

import java.util.List;

public record GeminiToolDto(
        List<GeminiFunctionDeclarationDto> functionDeclarations
) {
}
