package com.example.sku_sw.domain.auth.controller;

import com.example.sku_sw.domain.auth.dto.AuthLoginEmailReqDto;
import com.example.sku_sw.domain.auth.dto.AuthLoginEmailResDto;
import com.example.sku_sw.domain.auth.dto.AuthLogoutReqDto;
import com.example.sku_sw.domain.auth.dto.AuthRefreshTokenReqDto;
import com.example.sku_sw.domain.auth.dto.AuthRefreshTokenResDto;
import com.example.sku_sw.domain.auth.dto.AuthRegisterEmailReqDto;
import com.example.sku_sw.domain.auth.service.AuthService;
import com.example.sku_sw.global.response.GlobalResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AuthController implements AuthControllerDocs {

    private final AuthService authService;

    @Override
    public ResponseEntity<GlobalResponse<Void>> registerEmail(
            @Valid AuthRegisterEmailReqDto authRegisterEmailReqDto
    ) {
        authService.registerEmail(authRegisterEmailReqDto);
        return ResponseEntity.ok(GlobalResponse.success("회원가입 완료", null));
    }

    @Override
    public ResponseEntity<GlobalResponse<AuthLoginEmailResDto>> loginEmail(
            @Valid AuthLoginEmailReqDto authLoginEmailReqDto
    ) {
        AuthLoginEmailResDto response = authService.loginEmail(authLoginEmailReqDto);
        return ResponseEntity.ok(GlobalResponse.success("로그인 완료", response));
    }

    @Override
    public ResponseEntity<GlobalResponse<Void>> logout(
            @Valid AuthLogoutReqDto authLogoutReqDto
    ) {
        authService.logout(authLogoutReqDto);
        return ResponseEntity.ok(GlobalResponse.success("로그아웃 성공", null));
    }

    @Override
    public ResponseEntity<GlobalResponse<AuthRefreshTokenResDto>> refreshToken(
            @Valid AuthRefreshTokenReqDto authRefreshTokenReqDto
    ) {
        AuthRefreshTokenResDto response = authService.refreshToken(authRefreshTokenReqDto);
        return ResponseEntity.ok(GlobalResponse.success("Access Token & Refresh Token 재발급 완료", response));
    }
}
