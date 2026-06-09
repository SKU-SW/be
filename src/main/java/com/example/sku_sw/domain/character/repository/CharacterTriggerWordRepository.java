package com.example.sku_sw.domain.character.repository;

import com.example.sku_sw.domain.character.entity.CharacterTriggerWord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CharacterTriggerWordRepository extends JpaRepository<CharacterTriggerWord, Long> {

    /**
     * characterId로 캐릭터 호출어 목록을 정렬 순서 오름차순으로 조회하는 함수
     * @param characterId : 캐릭터 ID
     * @return : 캐릭터 호출어 목록
     */
    List<CharacterTriggerWord> findAllByCharacterIdOrderBySortOrderAsc(Long characterId);

    /**
     * characterId로 캐릭터 호출어 목록을 삭제하는 함수
     * @param characterId : 캐릭터 ID
     */
    void deleteByCharacterId(Long characterId);
}
