package com.example.sku_sw.domain.broadcast.service.gemini;

import com.example.sku_sw.domain.broadcast.event.BroadcastGeminiResumptionRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Gemini resumption 요청 이벤트 리스너
 * - WebSocket close 시 발행된 resumption 요청 이벤트를 수신해 실제 resumption을 시도한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BroadcastGeminiResumptionEventListener {

    private final BroadcastGeminiResumptionService broadcastGeminiResumptionService;

    /**
     * Gemini resumption 요청 이벤트를 수신해 resumption을 시도한다.
     * @param event : Gemini resumption 요청 이벤트
     */
    @EventListener
    public void onBroadcastGeminiResumptionRequested(BroadcastGeminiResumptionRequestedEvent event) {
        log.info("[BroadcastGeminiResumptionEventListener] onBroadcastGeminiResumptionRequested() - START | sessionId: {}, closeStatus: {}",
                event.getClosedSession() != null ? event.getClosedSession().getId() : null,
                event.getCloseStatus());

        boolean resumed = broadcastGeminiResumptionService.tryResumeAfterUnexpectedClose(
                event.getClosedSession(),
                event.getCloseStatus(),
                () -> {
                    event.getFallbackExecuted().set(true);
                    event.getFallbackCleanup().run();
                }
        );
        event.getResumed().set(resumed);

        log.info("[BroadcastGeminiResumptionEventListener] onBroadcastGeminiResumptionRequested() - END | sessionId: {}, resumed: {}",
                event.getClosedSession() != null ? event.getClosedSession().getId() : null,
                resumed);
    }
}
