package com.example.sku_sw.domain.broadcast.repository;

import com.example.sku_sw.domain.broadcast.entity.Broadcast;
import com.example.sku_sw.domain.broadcast.enums.BroadcastStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
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

    /**
     * broadcastId와 userId로 방송 단건 조회 (Fetch Join)
     * - Broadcast -> Character, CharacterPersona, CharacterImage, CharacterVrm을 Fetch Join한다.
     * - 컬렉션 Fetch Join은 수행하지 않는다.
     * @param broadcastId : 조회할 방송 ID
     * @param userId : 조회할 사용자 ID (Character 소유자)
     * @return : Optional<Broadcast>
     */
    @Query("select b " +
            "from Broadcast b " +
            "join fetch b.character c " +
            "left join fetch c.characterPersona " +
            "left join fetch c.characterImage " +
            "left join fetch c.characterVrm " +
            "where b.id = :broadcastId and c.user.id = :userId")
    Optional<Broadcast> findByIdAndUserId(@Param("broadcastId") Long broadcastId, @Param("userId") Long userId);

    /**
     * 사용자의 해당 월 방송 목록 조회
     * - userId와 month로 특정 월의 방송 목록을 조회한다.
     * - startedAt 오름차순으로 정렬한다.
     * @param userId : 조회할 사용자 ID
     * @param year : 조회할 연도
     * @param month : 조회할 월
     * @return : List<Broadcast>
     */
    @Query("select b " +
            "from Broadcast b " +
            "join fetch b.character " +
            "where b.character.user.id = :userId " +
            "and year(b.startedAt) = :year " +
            "and month(b.startedAt) = :month " +
            "order by b.startedAt asc")
    List<Broadcast> findAllByUserIdAndMonth(
            @Param("userId") Long userId,
            @Param("year") int year,
            @Param("month") int month
    );
}
