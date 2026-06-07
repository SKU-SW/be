package com.example.sku_sw.domain.broadcast.repository;

import com.example.sku_sw.domain.broadcast.entity.BroadcastKeywords;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BroadcastKeywordsRepository extends JpaRepository<BroadcastKeywords, Long> {
}
