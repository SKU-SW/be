package com.example.sku_sw.domain.broadcast.event;

/**
 * 방송 종료 상태 커밋 이후 후속 정리를 요청하는 이벤트.
 * @param broadcastStreamId : 종료할 방송 스트림 ID
 */
public record BroadcastTerminateRequestedEvent(String broadcastStreamId) {
}
