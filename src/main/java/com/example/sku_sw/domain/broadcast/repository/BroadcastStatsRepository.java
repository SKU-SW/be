package com.example.sku_sw.domain.broadcast.repository;

import com.example.sku_sw.domain.broadcast.entity.Broadcast;
import com.example.sku_sw.domain.broadcast.entity.BroadcastStats;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface BroadcastStatsRepository extends JpaRepository<BroadcastStats, Long> {

    /**
     * 특정 방송의 가장 최근 통계 조회
     * @param broadcast : 조회할 방송 Entity
     * @return : 가장 최근 통계 (없으면 empty)
     */
    Optional<BroadcastStats> findTopByBroadcastOrderByRecordedAtDesc(Broadcast broadcast);

    /**
     * 특정 방송의 특정 시점 이후 통계 조회
     * @param broadcast : 조회할 방송 Entity
     * @param since : 조회 시작 시각 (inclusive)
     * @return : 통계 목록 (시간순 정렬)
     */
    @Query("SELECT bs FROM BroadcastStats bs " +
           "WHERE bs.broadcast = :broadcast " +
           "AND bs.recordedAt >= :since " +
           "ORDER BY bs.recordedAt ASC")
    List<BroadcastStats> findByBroadcastAndRecordedAtAfter(
        @Param("broadcast") Broadcast broadcast,
        @Param("since") LocalDateTime since
    );
}
