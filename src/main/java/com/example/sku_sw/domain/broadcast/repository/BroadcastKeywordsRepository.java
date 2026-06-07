package com.example.sku_sw.domain.broadcast.repository;

import com.example.sku_sw.domain.broadcast.entity.Broadcast;
import com.example.sku_sw.domain.broadcast.entity.BroadcastKeywords;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BroadcastKeywordsRepository extends JpaRepository<BroadcastKeywords, Long> {

    @Query("""
        SELECT bk.content 
        FROM BroadcastKeywords bk 
        WHERE bk.broadcast = :broadcast 
        GROUP BY bk.content 
        ORDER BY COUNT(bk) DESC 
        LIMIT 10
    """)
    List<String> findTop10KeywordsByBroadcast(@Param("broadcast") Broadcast broadcast);
}
