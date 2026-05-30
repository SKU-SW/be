package com.example.sku_sw.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "치지직 인증 URL 응답 DTO")
public record AuthChzzkAuthUrlResDto(
        @Schema(description = "치지직 인증 URL", example = "https://chzzk.naver.com/account-interlock?clientId=client-id&redirectUri=https://dev.sku-sw.cloud/api/v1/auth/chzzk/callback&state=550e8400-e29b-41d4-a716-446655440000")
        String authUrl
) {
}
