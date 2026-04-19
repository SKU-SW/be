package com.example.sku_sw.domain.character.repository;

import com.example.sku_sw.domain.character.entity.CharacterImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface CharacterImageRepository extends JpaRepository<CharacterImage, Long> {

    @Query("""
        select ci from CharacterImage ci
        join fetch ci.imageDetails
    """)
    List<CharacterImage> findAllWithImageDetails();
}
