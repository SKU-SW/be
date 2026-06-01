package com.example.sku_sw.domain.chat.dto;

public record FastApiChzzkRedisChannelReqDto(
        String broadcastStreamId,
        String sessionKey,
        String channelName
) {
}
