package com.example.sku_sw.domain.chat.dto;

import lombok.Builder;

@Builder
public record FastApiChzzkRedisChannelResDto(
        String broadcastStreamId,
        String sessionKey,
        String channelName,
        String status
) {
}
