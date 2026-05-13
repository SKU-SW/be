package com.example.sku_sw.global.util.dto.gemini.functioncall;

import lombok.Builder;

/**
 * Gemini Function Calling API 호출 결과 DTO
 * - functionCalled: Function Call이 발생했으면 true
 * - text: Function Call이 없을 경우 모델이 생성한 텍스트 응답
 */
@Builder
public record GeminiFunctionCallingResponseDto(
        boolean functionCalled,
        String text
) {
}
