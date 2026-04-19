package com.example.sku_sw.global.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * [SecurityContext에서 인증 정보를 추출하는 유틸리티 클래스]
 * - Controller나 Service에서 현재 로그인한 사용자의 ID를 쉽게 가져올 수 있도록 한다.
 */
public class SecurityUtil {

    /**
     * SecurityContext에서 현재 로그인한 사용자의 ID를 추출하는 함수
     * @return : 현재 로그인한 사용자의 ID
     */
    public static Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new IllegalStateException("인증 정보가 존재하지 않습니다.");
        }
        CustomUserDetails customUserDetails = (CustomUserDetails) authentication.getPrincipal();
        return customUserDetails.getUserAuthDto().userId();
    }
}
