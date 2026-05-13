package com.example.sku_sw.domain.broadcast.dto;

import com.example.sku_sw.domain.broadcast.enums.BroadcastVoiceEventType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
public record BroadcastVoiceMetadataResDto(
        @Schema(description = "음성 메타데이터 이벤트 타입", example = "VOICE_CHUNK")
        BroadcastVoiceEventType eventType,
        @Schema(description = "Gemini 응답 turn 번호", example = "1")
        Long turnNumber,
        @Schema(description = "캐릭터 ID", example = "1")
        Long characterId,
        @Schema(description = "음성 텍스트 데이터", example = "안녕하세요")
        String voiceText,
        @Schema(description = "BroadcastInfo Cursor ID", example = "42")
        Long broadcastDialogueCursorId
) {
}
