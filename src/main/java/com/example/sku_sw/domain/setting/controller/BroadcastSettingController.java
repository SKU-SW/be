package com.example.sku_sw.domain.setting.controller;

import com.example.sku_sw.domain.setting.dto.AiProactiveUpdateReqDto;
import com.example.sku_sw.domain.setting.dto.BroadcastSettingResDto;
import com.example.sku_sw.domain.setting.service.BroadcastSettingService;
import com.example.sku_sw.global.response.GlobalResponse;
import com.example.sku_sw.global.security.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class BroadcastSettingController implements BroadcastSettingControllerDocs {

    private final BroadcastSettingService broadcastSettingService;

    @Override
    public ResponseEntity<GlobalResponse<BroadcastSettingResDto>> getBroadcastSetting() {
        Long userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(GlobalResponse.success(broadcastSettingService.getBroadcastSetting(userId)));
    }

    @Override
    public ResponseEntity<GlobalResponse<BroadcastSettingResDto>> updateAiProactiveToChat(
            AiProactiveUpdateReqDto reqDto
    ) {
        Long userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(GlobalResponse.success(broadcastSettingService.updateAiProactiveToChat(userId, reqDto)));
    }

    @Override
    public ResponseEntity<GlobalResponse<BroadcastSettingResDto>> initBroadcastSetting() {
        Long userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(GlobalResponse.success("방송 설정이 초기화되었습니다.",
                broadcastSettingService.initBroadcastSetting(userId)));
    }
}
