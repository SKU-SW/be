package com.example.sku_sw.domain.broadcast.service.gemini;

import com.example.sku_sw.domain.broadcast.enums.BroadcastGeminiRefreshTriggerType;
import com.example.sku_sw.domain.broadcast.event.BroadcastGeminiRefreshRequestedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BroadcastGeminiRefreshEventListenerTest {

    @InjectMocks
    private BroadcastGeminiRefreshEventListener listener;

    @Mock
    private BroadcastGeminiRefreshService broadcastGeminiRefreshService;

    @Test
    @DisplayName("Gemini refresh 요청 이벤트 수신 성공 - streamId와 generation으로 refresh 시작을 시도한다")
    void onBroadcastGeminiRefreshRequested_성공() {
        // given
        String broadcastStreamId = "stream-1";
        long generation = 1L;
        BroadcastGeminiRefreshRequestedEvent event = BroadcastGeminiRefreshRequestedEvent.builder()
                .broadcastStreamId(broadcastStreamId)
                .generation(generation)
                .triggerType(BroadcastGeminiRefreshTriggerType.TURN_FINISHED)
                .build();

        // when
        listener.onBroadcastGeminiRefreshRequested(event);

        // then
        verify(broadcastGeminiRefreshService, times(1)).tryStartRefresh(broadcastStreamId, generation);
    }
}
