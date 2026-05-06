package com.example.sku_sw.global.util.dto.gemini.functioncall;

import java.util.Map;

public record GeminiFunctionCallDto(
        String name,
        Map<String, Object> args
) {
}
