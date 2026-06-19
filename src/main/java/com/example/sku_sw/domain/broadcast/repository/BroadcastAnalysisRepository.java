package com.example.sku_sw.domain.broadcast.repository;

import com.example.sku_sw.domain.broadcast.entity.Broadcast;
import com.example.sku_sw.domain.broadcast.entity.BroadcastAnalysis;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
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

    /**
     * 같은 사용자의 이전 방송 분석 목록을 최신 방송 순으로 조회한다.
     *
     * @param userId 조회할 사용자 ID
     * @param broadcastId 제외할 현재 방송 ID
     * @param pageable 조회 개수 Pageable
     * @return 이전 방송 분석 목록
     */
    @Query("select ba " +
            "from BroadcastAnalysis ba " +
            "join fetch ba.broadcast b " +
            "join fetch b.character c " +
            "where c.user.id = :userId " +
            "and b.id <> :broadcastId " +
            "order by b.startedAt desc")
    List<BroadcastAnalysis> findRecentByUserIdAndBroadcastIdNot(
            @Param("userId") Long userId,
            @Param("broadcastId") Long broadcastId,
            Pageable pageable
    );
}
