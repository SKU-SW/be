package com.example.sku_sw.domain.broadcast.dto;

import lombok.Builder;

import java.util.List;

@Builder
public record BroadcastDialogueRefreshSnapshotDto(
        BroadcastInfoRedisDto summary,
        List<BroadcastInfoRedisDto> dialogues,
        Long snapshotCursorId
) {
}
