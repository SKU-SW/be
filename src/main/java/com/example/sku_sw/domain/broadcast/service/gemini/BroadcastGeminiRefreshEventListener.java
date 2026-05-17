package com.example.sku_sw.domain.broadcast.service.gemini;

import com.example.sku_sw.domain.broadcast.event.BroadcastGeminiRefreshRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Gemini refresh 요청 이벤트 리스너
 * - 응답 완료/세션 종료 등에서 발행된 refresh 요청 이벤트를 수신해 실제 refresh 시작 여부를 판단한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BroadcastGeminiRefreshEventListener {

    private final BroadcastGeminiRefreshService broadcastGeminiRefreshService;

    /**
     * Gemini refresh 요청 이벤트를 수신해 refresh 시작을 시도한다.
     * @param event : Gemini refresh 요청 이벤트
     */
    @EventListener
    public void onBroadcastGeminiRefreshRequested(BroadcastGeminiRefreshRequestedEvent event) {
        log.info("[BroadcastGeminiRefreshEventListener] onBroadcastGeminiRefreshRequested() - START | streamId: {}, generation: {}, triggerType: {}",
                event.broadcastStreamId(), event.generation(), event.triggerType());

        /*
            1. 이벤트에 담긴 방송 스트림 ID와 generation으로 refresh 시작을 시도한다.
            - 실제 refresh 진행 여부는 BroadcastGeminiRefreshService 내부 조건 검증 결과에 따라 결정된다.
         */
        broadcastGeminiRefreshService.tryStartRefresh(event.broadcastStreamId(), event.generation());

        log.info("[BroadcastGeminiRefreshEventListener] onBroadcastGeminiRefreshRequested() - END | streamId: {}, generation: {}, triggerType: {}",
                event.broadcastStreamId(), event.generation(), event.triggerType());
    }
}
