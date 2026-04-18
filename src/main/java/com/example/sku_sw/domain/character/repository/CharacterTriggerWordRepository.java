package com.example.sku_sw.domain.character.repository;

import com.example.sku_sw.domain.character.entity.CharacterTriggerWord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CharacterTriggerWordRepository extends JpaRepository<CharacterTriggerWord, Long> {
    List<CharacterTriggerWord> findAllByCharacterIdOrderBySortOrderAsc(Long characterId);

    void deleteByCharacterId(Long characterId);
}
