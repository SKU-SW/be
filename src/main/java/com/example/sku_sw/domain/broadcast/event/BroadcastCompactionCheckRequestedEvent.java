package com.example.sku_sw.domain.broadcast.event;

import com.example.sku_sw.domain.broadcast.enums.BroadcastCompactionTriggerType;
import lombok.Builder;

@Builder
public record BroadcastCompactionCheckRequestedEvent(
        String broadcastStreamId,
        BroadcastCompactionTriggerType triggerType
) {
}
