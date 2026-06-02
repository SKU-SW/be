package com.example.sku_sw.domain.chat.dto;

public record ChzzkChatMessageDto(
        String channelId,
        String nickname,
        String userRoleCode,
        String content,
        Long messageTime
) {
}
