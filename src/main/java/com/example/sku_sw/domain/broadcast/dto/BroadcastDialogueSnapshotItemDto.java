package com.example.sku_sw.domain.broadcast.dto;

import lombok.Builder;

/**
 * Redis 방송 대화 snapshot 단건 DTO
 * - compaction 대상 대화의 Redis index와 대화 정보를 함께 보관한다.
 */
@Builder
public record BroadcastDialogueSnapshotItemDto(
        Integer listIndex,
        BroadcastInfoRedisDto dialogue
) {
}
