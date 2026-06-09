package com.example.sku_sw.domain.broadcast.dto;

import com.example.sku_sw.domain.broadcast.enums.DialogueSubject;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Schema(description = "현재 방송 대화 커서 조회 항목 응답 DTO")
@Builder
public record BroadcastDialogueCursorResDto(
        @Schema(description = "각 방송 대화 기록 데이터별 고유 cursor 전용 ID", example = "7")
        Long cursorId,

        @Schema(description = "발언 주체", example = "VIEWER")
        DialogueSubject subject,

        @Schema(description = "발언 텍스트 데이터", example = "왜 엄한 애한테 그러냐")
        String content,

        @Schema(description = "발언 생성 시각", example = "2026-04-25-18:00:15")
        String createdAt
) {
}
