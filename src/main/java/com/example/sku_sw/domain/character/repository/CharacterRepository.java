package com.example.sku_sw.domain.character.repository;

import com.example.sku_sw.domain.character.entity.Character;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CharacterRepository extends JpaRepository<Character, Long> {
    /**
     * 선택된 캐릭터가 있다면 page가 1인 경우에만 최상단에 선택된 캐릭터 정보를 위치시켜 조회하는 함수
     * @param userId : 조회할 사용자 ID
     * @param selectedCharacterId : 최상단에 노출할 선택된 캐릭터 ID
     * @param pageable : 조회할 페이지 정보
     * @return : 캐릭터 목록 Slice
     */
    @Query("SELECT c FROM Character c " +
            "JOIN FETCH c.characterImage " +
            "JOIN FETCH c.characterPersona " +
            "WHERE c.user.id = :userId " +
            "ORDER BY CASE " +
            "WHEN c.id = :selectedCharacterId " +
            "THEN 1 ELSE 2 END ASC, " +
            "c.createdAt DESC")
    Slice<Character> findCharactersWithSelectedFirst(@Param("userId") Long userId,
                                                     @Param("selectedCharacterId") Long selectedCharacterId,
                                                     Pageable pageable);


    // 캐릭터 목록 API의 선택된 캐릭터 조회에 사용한다.
    @Query("select c " +
            "from Character c " +
            "join fetch c.characterPersona " +
            "join fetch c.characterImage " +
            "where c.id=:characterId and c.user.id=:userId")
    Optional<Character> findByIdAndUserId(@Param("characterId") Long characterId, @Param("userId") Long userId);

    /**
     * 방송 시작 시 Redis 저장에 필요한 캐릭터 연관 정보를 함께 조회하는 함수
     * @param characterId : 조회할 캐릭터 ID
     * @param userId : 캐릭터 소유 사용자 ID
     * @return : 캐릭터 정보 Optional
     */
    @Query("select distinct c " +
            "from Character c " +
            "join fetch c.characterPersona " +
            "join fetch c.characterImage ci " +
            "left join fetch c.triggerWords tw " +
            "where c.id=:characterId and c.user.id=:userId")
    Optional<Character> findBroadcastRedisCharacterByIdAndUserId(@Param("characterId") Long characterId, @Param("userId") Long userId);

    boolean existsByIdAndUserId(Long id, Long userId);
}
