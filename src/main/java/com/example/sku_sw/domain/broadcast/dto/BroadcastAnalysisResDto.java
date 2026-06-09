package com.example.sku_sw.domain.broadcast.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.util.List;

@Schema(description = "방송 분석 결과 응답 DTO")
@Builder
public record BroadcastAnalysisResDto(

        @Schema(description = "방송의 주 컨텐츠에 대한 설명", example = "게임을 주로 하며 중간중간 시청자와 일상 대화를 나누는 방송")
        String majorContent,

        @Schema(description = "시청자와의 주 분위기를 분석한 결과", example = "장난과 티키타카가 많고, 게임 상황에 따라 함께 놀리거나 응원하는 분위기")
        String majorMoodWithViewers,

        @Schema(description = "당일 방송 요약", example = "초반에는 근황 토크를 진행하고, 중반부터 게임 플레이를 이어가며 시청자와 반응을 주고받았다.")
        String summary,

        @Schema(description = "방송 최종 분석", example = "전체적으로 게임 진행과 시청자 소통이 균형 있게 이어졌으며 반복적인 밈이 형성되었다.")
        String totalAnalysis,

        @Schema(description = "방송 중 반복적으로 사용된 유행어 목록")
        List<String> catchPhrases,

        @Schema(description = "방송 주요 타임라인 목록")
        List<TimeLineResDto> timeLines
) {
    @Schema(description = "방송 분석 타임라인 응답 DTO")
    @Builder
    public record TimeLineResDto(

            @Schema(description = "해당 구간 방송 내용", example = "스트리머가 시청자와 근황 이야기를 나누며 방송 분위기를 풀었다.")
            String content,

            @Schema(description = "구간 시작 시간", example = "2026-06-28 09:12:33")
            String startTime,

            @Schema(description = "구간 종료 시간", example = "2026-06-28 09:30:33")
            String endTime
    ) {
    }
}
