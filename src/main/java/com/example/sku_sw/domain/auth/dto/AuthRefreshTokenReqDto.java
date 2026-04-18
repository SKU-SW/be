package com.example.sku_sw.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "토큰 재발급 요청 DTO")
public record AuthRefreshTokenReqDto(
        @Schema(description = "Refresh Token", example = "ABCDE...")
        String refreshToken
) {
}
