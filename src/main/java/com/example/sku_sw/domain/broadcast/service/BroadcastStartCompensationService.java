package com.example.sku_sw.domain.broadcast.service;

import com.example.sku_sw.domain.broadcast.entity.Broadcast;
import com.example.sku_sw.domain.broadcast.enums.BroadcastStatus;
import com.example.sku_sw.domain.broadcast.repository.BroadcastRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class BroadcastStartCompensationService {

    private final BroadcastRepository broadcastRepository;

    /**
     * 방송 시작 후속 처리에 실패한 방송을 비정상 종료한다.
     * - 기존 방송 시작 트랜잭션의 커밋 이후 실행되므로 별도 트랜잭션에서 상태를 변경한다.
     * - BROADCASTING 상태인 방송만 잠금 조회하여 이미 종료된 상태를 덮어쓰지 않는다.
     * @param broadcastStreamId : 비정상 종료할 방송 스트림 ID
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void abnormalTerminateBroadcast(String broadcastStreamId) {
        log.info("[BroadcastStartCompensationService] 방송 시작 보상 비정상 종료됨 | abnormalTerminateBroadcast() - START | streamId: {}",
                broadcastStreamId);

        /*
            1. 진행 중인 방송 잠금 조회
            - 동시 종료 처리와 상태 충돌이 발생하지 않도록 비관적 쓰기 락을 획득한다.
         */
        Broadcast broadcast = broadcastRepository.findByStreamIdAndStatusForUpdate(
                        broadcastStreamId,
                        BroadcastStatus.BROADCASTING
                )
                .orElse(null);

        if (broadcast == null) {
            log.warn("[BroadcastStartCompensationService] 비정상 종료 대상 방송 없음 | abnormalTerminateBroadcast() - END | streamId: {}",
                    broadcastStreamId);
            return;
        }

        /*
            2. 방송 비정상 종료 상태 저장
            - 방송 상태를 ABNORMAL_TERMINATED로 변경하고 종료 시간을 기록한다.
         */
        broadcast.abnormalTerminate();
        broadcastRepository.save(broadcast);

        log.info("[BroadcastStartCompensationService] 방송 시작 보상 비정상 종료 완료 | abnormalTerminateBroadcast() - END | streamId: {}",
                broadcastStreamId);
    }
}
