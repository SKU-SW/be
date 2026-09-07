package com.example.sku_sw.domain.broadcast.event;

import lombok.Builder;

@Builder
public record BroadcastGeminiResumptionReadyEvent(
        String broadcastStreamId,
        Long generation,
        String reason
) {
}
