package com.example.sku_sw.domain.broadcast.dto;

import java.util.List;

/**
 * Gemini 방송 분석 응답 DTO.
 * - summary는 Redis summary를 사용하므로 Gemini 응답에서 제외한다.
 */
public record BroadcastAnalysisGeminiResDto(
        String majorContent,
        String majorMoodWithViewers,
        String totalAnalysis,
        List<CatchPhraseDto> catchPhrases,
        List<TimeLineDto> timeLines
) {
    public record CatchPhraseDto(
            String content,
            String subject,
            String situationAnalysis
    ) {
    }

    public record TimeLineDto(
            String content,
            String startTime,
            String endTime
    ) {
    }
}
