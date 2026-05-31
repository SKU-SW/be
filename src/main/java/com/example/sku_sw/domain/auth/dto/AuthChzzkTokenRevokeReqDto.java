package com.example.sku_sw.domain.auth.dto;

/**
 * 치지직 토큰 만료 요청 DTO
 * @param clientId : 치지직 Client ID
 * @param clientSecret : 치지직 Client Secret
 * @param token : 만료 처리할 치지직 토큰
 * @param tokenTypeHint : 만료 처리할 토큰 타입 힌트
 */
public record AuthChzzkTokenRevokeReqDto(
        String clientId,
        String clientSecret,
        String token,
        String tokenTypeHint
) {
}
