package com.example.sku_sw.domain.broadcast.dto;

public record FastApiTtsReqDto(
        String broadcastStreamId,
        Long characterId,
        String ttsId,
        String voiceText,
        Long broadcastDialogueId
) {
}
