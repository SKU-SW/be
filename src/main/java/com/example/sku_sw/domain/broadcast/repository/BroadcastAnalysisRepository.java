package com.example.sku_sw.domain.broadcast.repository;

import com.example.sku_sw.domain.broadcast.entity.Broadcast;
import com.example.sku_sw.domain.broadcast.entity.BroadcastAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * BroadcastAnalysis Repository
 */
public interface BroadcastAnalysisRepository extends JpaRepository<BroadcastAnalysis, Long> {

    /**
     * 방송 기준 BroadcastAnalysis 존재 여부 조회
     *
     * @param broadcast 조회할 방송
     * @return BroadcastAnalysis 존재 여부 (true: 존재, false: 미존재)
     */
    boolean existsByBroadcast(Broadcast broadcast);

    /**
     * 방송 streamId로 BroadcastAnalysis 조회
     *
     * @param streamId 방송 streamId
     * @return Optional<BroadcastAnalysis>
     */
    Optional<BroadcastAnalysis> findByBroadcast_StreamId(String streamId);

    /**
     * 방송 ID로 BroadcastAnalysis 조회
     * @param broadcastId : 조회할 방송 ID
     * @return : Optional<BroadcastAnalysis>
     */
    Optional<BroadcastAnalysis> findByBroadcast_Id(Long broadcastId);
}
