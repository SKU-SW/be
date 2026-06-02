package com.example.sku_sw.domain.user.controller;

import com.example.sku_sw.domain.user.dto.ChzzkAuthStatusResDto;
import com.example.sku_sw.domain.user.service.UserService;
import com.example.sku_sw.global.response.GlobalResponse;
import com.example.sku_sw.global.security.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class UserController implements UserControllerDocs {

    private final UserService userService;

    @Override
    public ResponseEntity<GlobalResponse<ChzzkAuthStatusResDto>> getChzzkAuthStatus() {
        Long userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(GlobalResponse.success(userService.getChzzkAuthStatus(userId)));
    }
}
