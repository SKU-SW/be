package com.example.sku_sw.domain.broadcast.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
public record BroadcastVoiceMetadataResDto(
        @Schema(description = "캐릭터 ID", example = "1")
        Long characterId,
        @Schema(description = "음성 텍스트 데이터", example = "안녕하세요")
        String voiceText,
        @Schema(description = "BroadcastDialogue PK (Cursor)", example = "42")
        Long broadcastDialogueId
) {
}
