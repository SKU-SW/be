package com.example.sku_sw.domain.broadcast.repository;

import com.example.sku_sw.domain.broadcast.entity.Broadcast;
import com.example.sku_sw.domain.broadcast.enums.BroadcastStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BroadcastRepository extends JpaRepository<Broadcast, Long> {

    /**
     * 특정 캐릭터가 해당 상태로 방송 중인지 확인
     * @param characterId : 확인할 캐릭터 ID
     * @param status : 확인할 방송 상태
     * @return : 방송 중 여부 (true: 방송 중, false: 방송 중 아님)
     */
    boolean existsByCharacterIdAndStatus(Long characterId, BroadcastStatus status);

    /**
     * 생성된 streamId가 이미 존재하는지 확인
     * @param streamId : 중복 확인할 스트림 ID
     * @return : 존재 여부 (true: 이미 존재, false: 사용 가능)
     */
    boolean existsByStreamId(String streamId);
}
