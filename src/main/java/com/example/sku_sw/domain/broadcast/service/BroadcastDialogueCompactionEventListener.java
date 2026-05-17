package com.example.sku_sw.domain.broadcast.service;

import com.example.sku_sw.domain.broadcast.event.BroadcastCompactionCheckRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 방송 대화 compaction 점검 이벤트 리스너
 * - 외부 서비스에서 발행한 compaction 점검 요청 이벤트를 수신해 실제 compaction 시작 여부를 판단한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BroadcastDialogueCompactionEventListener {

    private final BroadcastDialogueCompactionService broadcastDialogueCompactionService;

    /**
     * compaction 점검 요청 이벤트를 수신해 compaction 시작을 시도한다.
     * @param event : compaction 점검 요청 이벤트
     */
    @EventListener
    public void onBroadcastCompactionCheckRequested(BroadcastCompactionCheckRequestedEvent event) {
        log.info("[BroadcastDialogueCompactionEventListener] onBroadcastCompactionCheckRequested() - START | streamId: {}, triggerType: {}",
                event.broadcastStreamId(), event.triggerType());

        /*
            1. 이벤트에 담긴 방송 스트림 ID로 compaction 시작을 시도한다.
            - 실제 실행 여부는 BroadcastDialogueCompactionService 내부 threshold/refresh 상태 검증 결과에 따라 결정된다.
         */
        broadcastDialogueCompactionService.tryStartCompaction(event.broadcastStreamId());

        log.info("[BroadcastDialogueCompactionEventListener] onBroadcastCompactionCheckRequested() - END | streamId: {}, triggerType: {}",
                event.broadcastStreamId(), event.triggerType());
    }
}
