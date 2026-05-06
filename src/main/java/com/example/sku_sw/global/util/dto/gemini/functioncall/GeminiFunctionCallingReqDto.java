package com.example.sku_sw.global.util.dto.gemini.functioncall;

import com.example.sku_sw.global.util.dto.gemini.common.GeminiRequestContentDto;

import java.util.List;

public record GeminiFunctionCallingReqDto(
        List<GeminiRequestContentDto> contents,
        List<GeminiToolDto> tools,
        GeminiToolConfigDto toolConfig
) {
}
