package com.example.sku_sw.domain.broadcast.dto;

import lombok.Builder;

import java.util.List;

/**
 * Redis 방송 대화 compaction snapshot DTO
 * - 현재 summary와 compaction 대상 대화 목록을 함께 전달한다.
 */
@Builder
public record BroadcastDialogueCompactionSnapshotDto(
        BroadcastInfoRedisDto summary,
        List<BroadcastDialogueSnapshotItemDto> dialogues
) {
}
