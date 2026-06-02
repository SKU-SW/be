package com.example.sku_sw.domain.user.dto;

import lombok.Builder;

@Builder
public record ChzzkAuthStatusResDto(
        boolean authorized,
        boolean accessTokenExpired,
        boolean refreshTokenExpired
) {}
