package com.example.sku_sw.domain.broadcast.repository;

import com.example.sku_sw.domain.broadcast.entity.CatchPhrase;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * CatchPhrase Repository
 */
public interface CatchPhraseRepository extends JpaRepository<CatchPhrase, Long> {
}
