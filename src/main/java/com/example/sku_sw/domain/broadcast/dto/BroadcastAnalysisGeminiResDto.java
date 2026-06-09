package com.example.sku_sw.domain.broadcast.dto;

import java.util.List;

public record BroadcastAnalysisGeminiResDto(
        String majorContent,
        String majorMoodWithViewers,
        String summary,
        String totalAnalysis,
        List<String> catchPhrases,
        List<TimeLineDto> timeLines
) {
    public record TimeLineDto(
            String content,
            String startTime,
            String endTime
    ) {
    }
}
