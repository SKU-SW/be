package com.example.sku_sw.domain.character.repository;

import com.example.sku_sw.domain.character.entity.CharacterImage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CharacterImageRepository extends JpaRepository<CharacterImage, Long> {
}
