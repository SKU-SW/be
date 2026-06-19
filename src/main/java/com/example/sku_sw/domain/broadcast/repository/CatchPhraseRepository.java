package com.example.sku_sw.domain.broadcast.repository;

import com.example.sku_sw.domain.broadcast.entity.CatchPhrase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * CatchPhrase Repository
 */
public interface CatchPhraseRepository extends JpaRepository<CatchPhrase, Long> {

    /**
     * 같은 사용자의 이전 방송 유행어 목록을 최신 방송 순으로 조회한다.
     *
     * @param userId 조회할 사용자 ID
     * @param broadcastId 제외할 현재 방송 ID
     * @return 이전 방송 유행어 목록
     */
    @Query("select cp " +
            "from CatchPhrase cp " +
            "join fetch cp.broadcastAnalysis ba " +
            "join fetch ba.broadcast b " +
            "join fetch b.character c " +
            "where c.user.id = :userId " +
            "and b.id <> :broadcastId " +
            "order by b.startedAt desc, cp.id desc")
    List<CatchPhrase> findAllByUserIdAndBroadcastIdNotOrderByBroadcastStartedAtDesc(
            @Param("userId") Long userId,
            @Param("broadcastId") Long broadcastId
    );
}
