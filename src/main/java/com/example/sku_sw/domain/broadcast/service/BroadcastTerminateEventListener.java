package com.example.sku_sw.domain.broadcast.service;

import com.example.sku_sw.domain.broadcast.event.BroadcastTerminateRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 커밋 후 발행된 방송 종료 이벤트를 동기적으로 수신한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BroadcastTerminateEventListener {

    private final BroadcastTerminateAfterService broadcastTerminateAfterService;

    /**
     * 방송 종료 이벤트에 담긴 스트림 ID로 후속 정리를 수행한다.
     * @param event : 방송 종료 후속 처리 요청 이벤트
     */
    @Async("broadcastTerminationExecutor")
    @EventListener
    public void onBroadcastTerminateRequested(BroadcastTerminateRequestedEvent event) {
        log.info("[BroadcastTerminateEventListener] onBroadcastTerminateRequested() - START | streamId: {}",
                event.broadcastStreamId());

        /*
            1. 방송 종료 후속 처리 서비스 호출
            - 이벤트를 발행한 스레드에서 후속 처리가 완료될 때까지 동기적으로 실행한다.
         */
        broadcastTerminateAfterService.processBroadcastTerminateAfterCommit(event.broadcastStreamId());

        log.info("[BroadcastTerminateEventListener] onBroadcastTerminateRequested() - END | streamId: {}",
                event.broadcastStreamId());
    }
}
