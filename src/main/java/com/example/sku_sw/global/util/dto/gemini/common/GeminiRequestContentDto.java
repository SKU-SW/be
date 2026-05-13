package com.example.sku_sw.global.util.dto.gemini.common;

import java.util.List;

public record GeminiRequestContentDto(
        List<GeminiRequestPartDto> parts
) {
}
