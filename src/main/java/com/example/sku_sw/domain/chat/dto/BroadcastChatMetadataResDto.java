package com.example.sku_sw.domain.chat.dto;

import com.example.sku_sw.domain.broadcast.enums.BroadcastVoiceEventType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

/**
 * 채팅 메시지 WebSocket 전송용 Response DTO
 * - 실시간 채팅 메시지를 클라이언트에게 전달할 때 사용한다.
 */
@Builder
public record BroadcastChatMetadataResDto(
        @Schema(description = "이벤트 타입", example = "CHAT_MESSAGE")
        BroadcastVoiceEventType eventType,
        @Schema(description = "채팅 작성자 닉네임", example = "시청자1")
        String nickname,
        @Schema(description = "사용자 역할 코드", example = "common_user")
        String userRoleCode,
        @Schema(description = "원본 채팅 내용", example = "안녕하세요")
        String content,
        @Schema(description = "Redis에 저장된 포맷팅된 내용", example = "(시청자, 시청자1)안녕하세요")
        String redisContent,
        @Schema(description = "메시지 시각 (Unix timestamp)", example = "1718000000")
        Long messageTime
) {
}
