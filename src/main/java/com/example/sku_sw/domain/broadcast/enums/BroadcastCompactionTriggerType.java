package com.example.sku_sw.domain.broadcast.enums;

/**
 * 방송 대화 compaction 점검 트리거 타입
 */
public enum BroadcastCompactionTriggerType {
    CLIENT_MESSAGE_STORED,
    AI_DIALOGUE_STORED,
    POST_COMPACTION_RECHECK
}
