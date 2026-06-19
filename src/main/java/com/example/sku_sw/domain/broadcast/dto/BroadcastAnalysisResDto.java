package com.example.sku_sw.domain.broadcast.dto;

import com.example.sku_sw.domain.broadcast.enums.DialogueSubject;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.util.List;

@Schema(description = "방송 분석 결과 응답 DTO")
@Builder
public record BroadcastAnalysisResDto(

        @Schema(description = "방송의 주요 콘텐츠 요약", example = "게임 진행이 중심이었고, 시청자와의 즉흥 반응이 많았다.")
        String majorContent,

        @Schema(description = "시청자와의 주요 분위기", example = "농담이 많이 오가며 가볍고 활발한 분위기였다.")
        String majorMoodWithViewers,

        @Schema(description = "방송 전체 요약", example = "초반에는 준비 이야기를 하고 중반부터 게임 진행과 채팅 반응이 활발하게 이어졌다.")
        String summary,

        @Schema(description = "방송 최종 분석", example = "방송 전반적으로 텐션이 높았고, 시청자 반응을 빠르게 받아치는 흐름이 인상적이었다.")
        String totalAnalysis,

        @Schema(description = "반복적으로 사용된 유행어 목록")
        List<CatchPhraseResDto> catchPhrases,

        @Schema(description = "방송 주요 타임라인 목록")
        List<TimeLineResDto> timeLines
) {
    @Schema(description = "방송 유행어 분석 응답 DTO")
    @Builder
    public record CatchPhraseResDto(

            @Schema(description = "유행어 또는 키워드", example = "레전드")
            String content,

            @Schema(description = "유행어 주체", example = "VIEWER", allowableValues = {"STREAMER", "VIEWER"})
            DialogueSubject subject,

            @Schema(description = "유행어가 발생한 상황 설명", example = "시청자가 난이도 높은 플레이를 보고 즉시 감탄하면서 반복한 표현이다.")
            String situationAnalysis
    ) {
    }

    @Schema(description = "방송 타임라인 응답 DTO")
    @Builder
    public record TimeLineResDto(

            @Schema(description = "해당 구간 방송 내용", example = "스트리머가 첫 번째 보스를 공략했다.")
            String content,

            @Schema(description = "구간 시작 시간", example = "2026-06-28 09:12:33")
            String startTime,

            @Schema(description = "구간 종료 시간", example = "2026-06-28 09:30:33")
            String endTime
    ) {
    }
}
