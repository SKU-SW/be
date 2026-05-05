package com.example.sku_sw.domain.broadcast.enums;

/**
 * Redis 방송 대화 데이터 상태 Enum
 * - ACTIVE: 아직 요약/영속화되지 않은 대화
 * - INACTIVE: DB 저장이 완료되어 삭제 대기 중인 대화
 */
public enum BroadcastDataStatus {
    ACTIVE,
    INACTIVE
}
