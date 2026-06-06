package com.example.sku_sw.domain.broadcast.repository;

import com.example.sku_sw.domain.broadcast.entity.Broadcast;
import com.example.sku_sw.domain.broadcast.entity.BroadcastStats;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BroadcastStatsRepository extends JpaRepository<BroadcastStats, Long> {

    /**
     * 특정 방송의 가장 최근 통계 조회
     * @param broadcast : 조회할 방송 Entity
     * @return : 가장 최근 통계 (없으면 empty)
     */
    Optional<BroadcastStats> findTopByBroadcastOrderByRecordedAtDesc(Broadcast broadcast);
}
