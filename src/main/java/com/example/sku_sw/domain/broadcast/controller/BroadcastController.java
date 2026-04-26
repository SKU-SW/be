package com.example.sku_sw.domain.broadcast.controller;

import com.example.sku_sw.domain.broadcast.dto.BroadcastStartResDto;
import com.example.sku_sw.domain.broadcast.service.BroadcastService;
import com.example.sku_sw.global.response.GlobalResponse;
import com.example.sku_sw.global.security.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class BroadcastController implements BroadcastControllerDocs {

    private final BroadcastService broadcastService;

    @Override
    public ResponseEntity<GlobalResponse<BroadcastStartResDto>> startBroadcast(Long characterId) {
        Long userId = SecurityUtil.getCurrentUserId();
        BroadcastStartResDto response = broadcastService.startBroadcast(userId, characterId);
        return ResponseEntity.ok(GlobalResponse.success("방송 시작 성공", response));
    }
}
