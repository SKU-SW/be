package com.example.sku_sw.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "토큰 재발급 응답 DTO")
public record AuthRefreshTokenResDto(
        @Schema(description = "Access Token", example = "ABCD...")
        String accessToken,

        @Schema(description = "Refresh Token", example = "ABCD...")
        String refreshToken
) {
}
