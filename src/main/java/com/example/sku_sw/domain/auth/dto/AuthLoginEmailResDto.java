package com.example.sku_sw.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "이메일 로그인 응답 DTO")
public record AuthLoginEmailResDto(
        @Schema(description = "사용자 ID", example = "1")
        Long userId,

        @Schema(description = "이메일", example = "name@example.com")
        String email,

        @Schema(description = "사용자 이름", example = "홍길동")
        String name,

        @Schema(description = "Access Token", example = "ABCD...")
        String accessToken,

        @Schema(description = "Refresh Token", example = "ABCD...")
        String refreshToken
) {
}
