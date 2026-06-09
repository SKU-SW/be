package com.example.sku_sw.domain.broadcast.repository;

import com.example.sku_sw.domain.broadcast.entity.TimeLine;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * TimeLine Repository
 */
public interface TimeLineRepository extends JpaRepository<TimeLine, Long> {
}
