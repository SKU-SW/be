package com.example.sku_sw.domain.broadcast.dto;

import com.example.sku_sw.domain.broadcast.enums.DialogueSubject;
import com.example.sku_sw.domain.broadcast.enums.BroadcastDataStatus;
import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record BroadcastInfoRedisDto(
        Long cursorId,
        DialogueSubject subject,
        String content,
        LocalDateTime createdAt,
        BroadcastDataStatus dataStatus
) {
}
