package com.example.sku_sw.domain.auth.controller;

import com.example.sku_sw.domain.auth.dto.AuthLoginEmailReqDto;
import com.example.sku_sw.domain.auth.dto.AuthLoginEmailResDto;
import com.example.sku_sw.domain.auth.dto.AuthLogoutReqDto;
import com.example.sku_sw.domain.auth.dto.AuthRefreshTokenReqDto;
import com.example.sku_sw.domain.auth.dto.AuthRefreshTokenResDto;
import com.example.sku_sw.domain.auth.dto.AuthRegisterEmailReqDto;
import com.example.sku_sw.global.response.GlobalResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

@Tag(name = "Auth", description = "인증 관련 API")
@RequestMapping("/api/v1/auth")
public interface AuthControllerDocs {

    @Operation(
            summary = "이메일 회원가입",
            description = """
                    사용자 이름, 이메일, 비밀번호를 입력받아 새로운 이메일 계정을 생성합니다.

                    [Request Body]
                    - name: 사용자 이름
                    - email: 이메일 주소
                    - password: 비밀번호
                    - passwordConfirm: 비밀번호 확인
                    """
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "회원가입 완료", content = @Content)
    })
    @PostMapping("/register/email")
    ResponseEntity<GlobalResponse<Void>> registerEmail(
            @RequestBody AuthRegisterEmailReqDto authRegisterEmailReqDto
    );

    @Operation(
            summary = "이메일 로그인",
            description = """
                    이메일과 비밀번호를 입력받아 Access Token과 Refresh Token을 발급합니다.

                    [Request Body]
                    - email: 이메일 주소
                    - password: 비밀번호
                    """
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "로그인 완료",
                    content = @Content(schema = @Schema(implementation = AuthLoginEmailResDto.class))
            )
    })
    @PostMapping("/login/email")
    ResponseEntity<GlobalResponse<AuthLoginEmailResDto>> loginEmail(
            @RequestBody AuthLoginEmailReqDto authLoginEmailReqDto
    );

    @Operation(
            summary = "로그아웃",
            description = """
                    현재 로그인한 사용자를 로그아웃 처리합니다.

                    [Authorization]
                    - Bearer Access Token 필요

                    [Request Body]
                    - refreshToken: 로그아웃 처리할 Refresh Token
                    """
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "로그아웃 성공", content = @Content)
    })
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/logout")
    ResponseEntity<GlobalResponse<Void>> logout(
            @RequestBody AuthLogoutReqDto authLogoutReqDto
    );

    @Operation(
            summary = "Access Token 재발급",
            description = """
                    기존 Refresh Token을 입력받아 새로운 Access Token과 Refresh Token을 모두 재발급합니다.

                    [Request Body]
                    - refreshToken: 기존 Refresh Token
                    """
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Access Token & Refresh Token 재발급 완료",
                    content = @Content(schema = @Schema(implementation = AuthRefreshTokenResDto.class))
            )
    })
    @PostMapping("/refresh")
    ResponseEntity<GlobalResponse<AuthRefreshTokenResDto>> refreshToken(
            @RequestBody AuthRefreshTokenReqDto authRefreshTokenReqDto
    );
}
