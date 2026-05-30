package com.example.sku_sw.domain.auth.controller;

import com.example.sku_sw.domain.auth.dto.AuthChzzkAuthUrlResDto;
import com.example.sku_sw.domain.auth.dto.AuthLoginEmailReqDto;
import com.example.sku_sw.domain.auth.dto.AuthLoginEmailResDto;
import com.example.sku_sw.domain.auth.dto.AuthLogoutReqDto;
import com.example.sku_sw.domain.auth.dto.AuthRefreshTokenReqDto;
import com.example.sku_sw.domain.auth.dto.AuthRefreshTokenResDto;
import com.example.sku_sw.domain.auth.dto.AuthRegisterEmailReqDto;
import com.example.sku_sw.global.response.GlobalResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

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

    @Operation(
            summary = "치지직 인증 URL 조회",
            description = """
                    프론트가 치지직 인증 페이지로 이동할 수 있도록 인증 URL을 반환합니다.

                    [Response Data]
                    - authUrl: 치지직 계정 연동 인증 URL
                    """
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "치지직 인증 URL 조회 성공",
                    content = @Content(schema = @Schema(implementation = AuthChzzkAuthUrlResDto.class))
            )
    })
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/chzzk")
    ResponseEntity<GlobalResponse<AuthChzzkAuthUrlResDto>> getChzzkAuthUrl();

    @Operation(
            summary = "치지직 인증 Callback 수신",
            description = """
                    치지직 인증 완료 후 redirect 되는 callback 요청을 수신합니다.

                    [Query Parameter]
                    - code: 치지직 인증 완료 후 전달되는 authorization code
                    - state: 인증 URL 발급 시 서버가 생성하고 Redis에 저장한 CSRF 방지용 state 값
                    """
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "치지직 인증 callback 수신 성공", content = @Content),
            @ApiResponse(responseCode = "400", description = "유효하지 않은 치지직 인증 요청입니다.", content = @Content)
    })
    @GetMapping("/chzzk/callback")
    ResponseEntity<GlobalResponse<Void>> handleChzzkCallback(
            @Parameter(description = "치지직 인증 authorization code", required = true)
            @RequestParam("code") String code,

            @Parameter(description = "CSRF 방지용 state 값", required = true)
            @RequestParam("state") String state
    );
}
