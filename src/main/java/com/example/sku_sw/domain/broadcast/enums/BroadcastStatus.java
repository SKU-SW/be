package com.example.sku_sw.domain.broadcast.enums;

/**
 * 방송 상태 Enum
 * - BROADCASTING: 방송중
 * - TERMINATED: 방송종료됨
 * - ABNORMAL_TERMINATED: 비정상종료됨
 */
public enum BroadcastStatus {
    BROADCASTING,
    TERMINATED,
    ABNORMAL_TERMINATED
}
