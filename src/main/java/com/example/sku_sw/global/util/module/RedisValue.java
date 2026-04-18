package com.example.sku_sw.global.util.module;

import com.example.sku_sw.domain.user.enums.RegisterType;
import com.example.sku_sw.domain.user.enums.UserRole;
import lombok.Builder;

/**
 * Redis에 저장할 Refresh Token Value 구조체
 * @param userId : User PK
 * @param email : AuthAccount PK
 * @param role : 사용자 권한
 * @param registerType : 회원가입 타입
 */
@Builder
public record RedisValue(
        Long userId,
        String email,
        UserRole role,
        RegisterType registerType
) {
}