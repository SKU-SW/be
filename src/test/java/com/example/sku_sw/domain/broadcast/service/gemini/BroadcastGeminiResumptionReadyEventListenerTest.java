package com.example.sku_sw.domain.broadcast.service.gemini;

import com.example.sku_sw.domain.broadcast.event.BroadcastGeminiResumptionReadyEvent;
import com.example.sku_sw.domain.broadcast.service.BroadcastMessageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BroadcastGeminiResumptionReadyEventListenerTest {

    @InjectMocks
    private BroadcastGeminiResumptionReadyEventListener listener;

    @Mock
    private BroadcastMessageService broadcastMessageService;

    @Test
    @DisplayName("Gemini resumption ready 이벤트 수신 성공 - backlog flush를 시도한다")
    void onBroadcastGeminiResumptionReady_성공() {
        // given
        BroadcastGeminiResumptionReadyEvent event = BroadcastGeminiResumptionReadyEvent.builder()
                .broadcastStreamId("stream-1")
                .generation(1L)
                .reason("SUPPRESSED_TURN_COMPLETE")
                .build();

        // when
        listener.onBroadcastGeminiResumptionReady(event);

        // then
        verify(broadcastMessageService, times(1))
                .flushPendingDialoguesAfterResumption("stream-1", 1L, "SUPPRESSED_TURN_COMPLETE");
    }
}
