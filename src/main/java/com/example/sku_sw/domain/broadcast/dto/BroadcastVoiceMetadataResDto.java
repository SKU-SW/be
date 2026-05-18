package com.example.sku_sw.domain.broadcast.dto;

import com.example.sku_sw.domain.broadcast.enums.BroadcastVoiceEventType;
import com.example.sku_sw.domain.character.enums.Emotion;
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
        @Schema(description = "현재 응답 감정", example = "TALKING")
        Emotion emotion,
        @Schema(description = "BroadcastInfo Cursor ID", example = "42")
        Long broadcastDialogueCursorId
) {
    public static BroadcastVoiceMetadataResDto buildEmotionMetadataResDto(Long turnNumber, Long characterId, Emotion emotion) {
        return BroadcastVoiceMetadataResDto.builder()
                .eventType(BroadcastVoiceEventType.VOICE_EMOTION)
                .turnNumber(turnNumber)
                .characterId(characterId)
                .voiceText(null)
                .emotion(emotion)
                .broadcastDialogueCursorId(null)
                .build();
    }
}
