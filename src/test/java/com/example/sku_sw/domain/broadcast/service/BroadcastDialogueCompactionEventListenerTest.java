package com.example.sku_sw.domain.broadcast.service;

import com.example.sku_sw.domain.broadcast.enums.BroadcastCompactionTriggerType;
import com.example.sku_sw.domain.broadcast.event.BroadcastCompactionCheckRequestedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BroadcastDialogueCompactionEventListenerTest {

    @InjectMocks
    private BroadcastDialogueCompactionEventListener listener;

    @Mock
    private BroadcastDialogueCompactionService broadcastDialogueCompactionService;

    @Test
    @DisplayName("compaction 점검 이벤트 수신 성공 - streamId로 compaction 시작을 시도한다")
    void onBroadcastCompactionCheckRequested_성공() {
        // given
        String broadcastStreamId = "stream-1";
        BroadcastCompactionCheckRequestedEvent event = BroadcastCompactionCheckRequestedEvent.builder()
                .broadcastStreamId(broadcastStreamId)
                .triggerType(BroadcastCompactionTriggerType.CLIENT_MESSAGE_STORED)
                .build();

        // when
        listener.onBroadcastCompactionCheckRequested(event);

        // then
        verify(broadcastDialogueCompactionService, times(1)).tryStartCompaction(broadcastStreamId);
    }
}
