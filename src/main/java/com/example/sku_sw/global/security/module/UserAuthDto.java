package com.example.sku_sw.global.security.module;

import com.example.sku_sw.domain.user.enums.RegisterType;
import com.example.sku_sw.domain.user.enums.UserRole;
import lombok.Builder;

/**
    [JWT 토큰을 생성할 때 필요한 사용자 인증 정보들을 담고 있는 DTO]
    - Entity들을 직접 CustomUserDetails에 넣으면, 트랜잭션 범위 밖에서의 Lazy Loading이 발생할 수 있다.
    - 따라서, 이 DTO를 사용하여 해당 문제를 방지한다.
 */
@Builder
public record UserAuthDto(
        Long userId,
        String email,
        UserRole role,
        RegisterType registerType
) {
}
