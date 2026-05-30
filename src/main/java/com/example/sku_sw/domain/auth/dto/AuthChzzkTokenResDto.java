package com.example.sku_sw.domain.auth.dto;

public record AuthChzzkTokenResDto(
        String accessToken,
        String refreshToken,
        String tokenType,
        String expiresIn
) {
}
