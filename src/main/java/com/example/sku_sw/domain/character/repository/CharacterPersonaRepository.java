package com.example.sku_sw.domain.character.repository;

import com.example.sku_sw.domain.character.entity.CharacterPersona;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CharacterPersonaRepository extends JpaRepository<CharacterPersona, Long> {
    Optional<CharacterPersona> findByCharacterId(Long characterId);
}
