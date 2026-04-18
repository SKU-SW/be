package com.example.sku_sw.domain.character.repository;

import com.example.sku_sw.domain.character.entity.Character;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CharacterRepository extends JpaRepository<Character, Long> {
    Slice<Character> findAllByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Optional<Character> findByIdAndUserId(Long id, Long userId);

    boolean existsByIdAndUserId(Long id, Long userId);
}
