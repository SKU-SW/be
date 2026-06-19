package com.example.sku_sw.domain.broadcast.dto;

import com.example.sku_sw.domain.broadcast.enums.DialogueSubject;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 방송 시작 프롬프트에 포함할 이전 방송 이력 컨텍스트 DTO.
 *
 * @param recentBroadcastAnalyses 최근 방송 분석 목록
 * @param historicalCatchPhrases 누적 유행어 목록
 */
public record BroadcastPromptHistoryContext(
        List<RecentBroadcastAnalysis> recentBroadcastAnalyses,
        List<HistoricalCatchPhrase> historicalCatchPhrases
) {

    /**
     * 이전 방송 분석 요약 DTO.
     *
     * @param broadcastId 방송 PK
     * @param streamId 방송 streamId
     * @param startedAt 방송 시작 시각
     * @param majorContent 방송 주요 컨텐츠
     * @param majorMoodWithViewers 시청자와의 주요 분위기
     * @param summary 방송 요약
     * @param totalAnalysis 방송 총평
     */
    public record RecentBroadcastAnalysis(
            Long broadcastId,
            String streamId,
            LocalDateTime startedAt,
            String majorContent,
            String majorMoodWithViewers,
            String summary,
            String totalAnalysis
    ) {
    }

    /**
     * 이전 방송 유행어 DTO.
     *
     * @param broadcastId 유행어가 등장한 방송 PK
     * @param streamId 유행어가 등장한 방송 streamId
     * @param content 유행어 본문
     * @param subject 유행어 주체
     * @param situationAnalysis 유행어 발생 상황 분석
     * @param startedAt 방송 시작 시각
     * @param duplicateBroadcastCount 같은 유행어가 중복 등장한 방송 수
     */
    public record HistoricalCatchPhrase(
            Long broadcastId,
            String streamId,
            String content,
            DialogueSubject subject,
            String situationAnalysis,
            LocalDateTime startedAt,
            long duplicateBroadcastCount
    ) {
    }
}
