package com.example.sku_sw.domain.auth.dto;

/**
 * 치지직 Refresh Token 재발급 요청 DTO
 * @param grantType : 치지직 토큰 재발급 grant type
 * @param refreshToken : 치지직 Refresh Token
 * @param clientId : 치지직 Client ID
 * @param clientSecret : 치지직 Client Secret
 */
public record AuthChzzkRefreshTokenReqDto(
        String grantType,
        String refreshToken,
        String clientId,
        String clientSecret
) {
}
