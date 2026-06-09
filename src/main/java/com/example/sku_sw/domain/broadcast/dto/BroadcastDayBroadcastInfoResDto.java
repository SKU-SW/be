package com.example.sku_sw.domain.broadcast.dto;

import com.example.sku_sw.domain.broadcast.enums.BroadcastStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Schema(description = "방송 통계 - 방송 정보 응답 DTO")
@Builder
public record BroadcastDayBroadcastInfoResDto(

        @Schema(description = "방송 고유 stream ID", example = "abc123def456ghi7")
        String streamId,

        @Schema(description = "방송 상태", example = "TERMINATED")
        BroadcastStatus status,

        @Schema(description = "방송 시작 시각", example = "2026-04-25-18:00:00")
        String startedAt,

        @Schema(description = "방송 종료 시각", example = "2026-04-25-19:30:00")
        String terminatedAt,

        @Schema(description = "최근 5개 방송 대화 목록")
        List<BroadcastDialogueCursorResDto> lastFiveBroadcastDialogues,

        @Schema(description = "방송 분석 결과")
        Object analysisResult
) {
    public static BroadcastDayBroadcastInfoResDto create(
            String streamId,
            BroadcastStatus status,
            String startedAt,
            String terminatedAt,
            List<BroadcastDialogueCursorResDto> lastFiveBroadcastDialogues,
            Object analysisResult
    ){
        return BroadcastDayBroadcastInfoResDto.builder()
                .streamId(streamId)
                .status(status)
                .startedAt(startedAt)
                .terminatedAt(terminatedAt)
                .lastFiveBroadcastDialogues(lastFiveBroadcastDialogues)
                .analysisResult(analysisResult)
                .build();
    }
}
