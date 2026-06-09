package com.example.sku_sw.domain.broadcast.controller;

import com.example.sku_sw.domain.broadcast.dto.BroadcastDayStatsResDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastMonthResDto;
import com.example.sku_sw.domain.broadcast.service.BroadcastService;
import com.example.sku_sw.global.response.GlobalResponse;
import com.example.sku_sw.global.security.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class BroadcastStatsController implements BroadcastStatsControllerDocs {

    private final BroadcastService broadcastService;

    @Override
    public ResponseEntity<GlobalResponse<BroadcastDayStatsResDto>> getBroadcastDayStats(Long broadcastId) {
        Long userId = SecurityUtil.getCurrentUserId();
        BroadcastDayStatsResDto response = broadcastService.getBroadcastDayStats(userId, broadcastId);
        return ResponseEntity.ok(GlobalResponse.success("요청이 성공적으로 처리되었습니다.", response));
    }

    @Override
    public ResponseEntity<GlobalResponse<BroadcastMonthResDto>> getBroadcastMonth(Integer year, Integer month) {
        Long userId = SecurityUtil.getCurrentUserId();
        BroadcastMonthResDto response = broadcastService.getBroadcastMonth(userId, year, month);
        return ResponseEntity.ok(GlobalResponse.success("달별 방송 기록 조회 성공", response));
    }
}
