package com.example.sku_sw.domain.broadcast.repository;

import com.example.sku_sw.domain.broadcast.entity.Broadcast;
import com.example.sku_sw.domain.broadcast.enums.BroadcastStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;

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

    /**
     * streamId로 방송 단건 조회
     * @param streamId : 조회할 스트림 ID
     * @return : Optional<Broadcast>
     */
    @Query("select b " +
            "from Broadcast b " +
            "join fetch b.character " +
            "where b.streamId=:streamId")
    Optional<Broadcast> findByStreamId(@Param("streamId") String streamId);

    /**
     * streamId와 상태로 방송 단건 조회
     * @param streamId : 조회할 스트림 ID
     * @param status : 확인할 방송 상태
     * @return : Optional<Broadcast>
     */
    @Query("select b " +
            "from Broadcast b " +
            "join fetch b.character " +
            "where b.streamId=:streamId and b.status=:status")
    Optional<Broadcast> findByStreamIdAndStatus(@Param("streamId") String streamId, @Param("status") BroadcastStatus status);

    /**
     * streamId와 상태로 방송 단건 조회 및 쓰기 락 획득
     * - 타임아웃 비정상 종료와 정상 종료가 동시에 상태를 변경하지 않도록 방지한다.
     * @param streamId : 조회할 스트림 ID
     * @param status : 확인할 방송 상태
     * @return : Optional<Broadcast>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b " +
            "from Broadcast b " +
            "join fetch b.character " +
            "where b.streamId=:streamId and b.status=:status")
    Optional<Broadcast> findByStreamIdAndStatusForUpdate(@Param("streamId") String streamId, @Param("status") BroadcastStatus status);

    /**
     * 사용자의 선택 캐릭터에 대한 활성 방송 조회
     * - userId와 characterId로 진행 중인 방송을 조회한다.
     * @param userId : 조회할 사용자 ID
     * @param characterId : 조회할 캐릭터 ID
     * @param status : 확인할 방송 상태
     * @return : Optional<Broadcast>
     */
    @Query("select b " +
            "from Broadcast b " +
            "join fetch b.character c " +
            "where c.user.id = :userId " +
            "and c.id = :characterId " +
            "and b.status = :status")
    Optional<Broadcast> findActiveByUserIdAndCharacterId(
            @Param("userId") Long userId,
            @Param("characterId") Long characterId,
            @Param("status") BroadcastStatus status
    );

    /**
     * 사용자의 진행 중인 방송 단건 조회
     * - userId 기준으로 BROADCASTING 상태 방송을 조회한다.
     * @param userId : 조회할 사용자 ID
     * @param status : 확인할 방송 상태
     * @return : Optional<Broadcast>
     */
    @Query("select b " +
            "from Broadcast b " +
            "join fetch b.character c " +
            "left join fetch c.characterImage ci " +
            "left join fetch c.characterVrm cv " +
            "join fetch c.characterPersona cp " +
            "where c.user.id = :userId " +
            "and b.status = :status")
    Optional<Broadcast> findActiveByUserId(
            @Param("userId") Long userId,
            @Param("status") BroadcastStatus status
    );
}
