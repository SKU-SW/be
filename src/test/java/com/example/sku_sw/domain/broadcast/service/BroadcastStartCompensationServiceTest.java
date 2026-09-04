package com.example.sku_sw.domain.broadcast.service;

import com.example.sku_sw.domain.broadcast.entity.Broadcast;
import com.example.sku_sw.domain.broadcast.enums.BroadcastStatus;
import com.example.sku_sw.domain.broadcast.repository.BroadcastRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BroadcastStartCompensationServiceTest {

    @InjectMocks
    private BroadcastStartCompensationService broadcastStartCompensationService;

    @Mock
    private BroadcastRepository broadcastRepository;

    @Test
    @DisplayName("방송 시작 보상 성공 - 진행 중인 방송을 비정상 종료")
    void 방송_시작_보상_성공_진행_중인_방송_비정상_종료() {
        // given
        String broadcastStreamId = "stream-broadcasting";
        Broadcast broadcast = Broadcast.startBroadcast(broadcastStreamId, null);

        given(broadcastRepository.findByStreamIdAndStatusForUpdate(
                broadcastStreamId,
                BroadcastStatus.BROADCASTING
        )).willReturn(Optional.of(broadcast));

        // when
        broadcastStartCompensationService.abnormalTerminateBroadcast(broadcastStreamId);

        // then
        assertThat(broadcast.getStatus()).isEqualTo(BroadcastStatus.ABNORMAL_TERMINATED);
        assertThat(broadcast.getTerminatedAt()).isNotNull();
        verify(broadcastRepository, times(1)).save(broadcast);
    }

    @Test
    @DisplayName("방송 시작 보상 제외 - 진행 중인 방송이 없으면 기존 상태 유지")
    void 방송_시작_보상_제외_진행_중인_방송_없음() {
        // given
        String broadcastStreamId = "stream-terminated";
        given(broadcastRepository.findByStreamIdAndStatusForUpdate(
                broadcastStreamId,
                BroadcastStatus.BROADCASTING
        )).willReturn(Optional.empty());

        // when
        broadcastStartCompensationService.abnormalTerminateBroadcast(broadcastStreamId);

        // then
        verify(broadcastRepository, never()).save(org.mockito.ArgumentMatchers.any(Broadcast.class));
    }
}
