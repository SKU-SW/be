package com.example.sku_sw.domain.broadcast.event;

import com.example.sku_sw.domain.broadcast.enums.BroadcastGeminiRefreshTriggerType;
import lombok.Builder;

@Builder
public record BroadcastGeminiRefreshRequestedEvent(
        String broadcastStreamId,
        Long generation,
        BroadcastGeminiRefreshTriggerType triggerType
) {
}
