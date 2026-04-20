package com.example.sku_sw.domain.character.repository;

import com.example.sku_sw.domain.character.entity.CharacterImageDetail;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CharacterImageDetailRepository extends JpaRepository<CharacterImageDetail, Long> {
    List<CharacterImageDetail> findAllByCharacterImageIdOrderByEmotionAsc(Long characterImageId);
}
