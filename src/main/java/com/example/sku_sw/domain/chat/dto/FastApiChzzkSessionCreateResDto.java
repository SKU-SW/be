package com.example.sku_sw.domain.chat.dto;

import lombok.Builder;

@Builder
public record FastApiChzzkSessionCreateResDto(
        String broadcastStreamId,
        String attemptId,
        String sessionKey,
        String channelId
) {
}
