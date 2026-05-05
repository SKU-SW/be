package com.example.sku_sw.global.util.dto.gemini.functioncall;

public record GeminiFunctionCallingPartDto(
        String text,
        GeminiFunctionCallDto functionCall
) {
}
