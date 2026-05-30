package com.example.sku_sw.domain.auth.dto;

public record AuthChzzkTokenReqDto(
        String grantType,
        String clientId,
        String clientSecret,
        String code,
        String state
) {
}
