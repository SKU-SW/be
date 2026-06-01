package com.example.sku_sw.domain.chat.dto;

public record FastApiChzzkSessionCreateReqDto(
        String broadcastStreamId,
        String attemptId,
        String accessToken
) {
}
