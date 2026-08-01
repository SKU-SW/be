package com.example.sku_sw.domain.broadcast.service.gemini;

import com.example.sku_sw.domain.broadcast.event.BroadcastGeminiResumptionReadyEvent;
import com.example.sku_sw.domain.broadcast.service.BroadcastMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Gemini resumption 완료 이벤트 리스너
 * - first resumption control event 종료 후 Redis backlog flush를 시도한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BroadcastGeminiResumptionReadyEventListener {

    private final BroadcastMessageService broadcastMessageService;

    /**
     * Gemini resumption 완료 이벤트를 수신하고 backlog flush를 수행한다.
     * @param event : Gemini resumption 완료 이벤트
     */
    @EventListener
    public void onBroadcastGeminiResumptionReady(BroadcastGeminiResumptionReadyEvent event) {
        log.info("[BroadcastGeminiResumptionReadyEventListener] onBroadcastGeminiResumptionReady() - START | streamId: {}, generation: {}, reason: {}",
                event.broadcastStreamId(), event.generation(), event.reason());

        broadcastMessageService.flushPendingDialoguesAfterResumption(
                event.broadcastStreamId(),
                event.generation(),
                event.reason()
        );

        log.info("[BroadcastGeminiResumptionReadyEventListener] onBroadcastGeminiResumptionReady() - END | streamId: {}, generation: {}",
                event.broadcastStreamId(), event.generation());
    }
}
