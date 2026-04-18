package com.example.sku_sw.global.security;

import com.example.sku_sw.global.security.module.UserAuthDto;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * [사용자의 아이디, 비밀번호, 권한 등의 인증 정보들을 포함하는 클래스]
 * - 이는 일반 DTO와 다르며, Spring Security 전용 어댑터 클래스이다. 해당 인터페이스는 Spring Security가 요구하는 메서드들인 **`getAuthorities()`**, **`getPassword()`**, **`getUserName()`** 등을 반드시 가진다.
 * - Spring Security의 Security Context의 Authentication 객체
 * - UserDetails 클래스를 상속받아서 구현한다.
 * - 사용자의 인증 정보를 담고 있는 UserAuthDto Class를 감싼 형태로 사용한다.
 */
@Getter
@RequiredArgsConstructor
public class CustomUserDetails implements UserDetails {
    private final UserAuthDto userAuthDto; // 사용자의 인증 정보를 담고 있는 Dto

    /**
     * [사용자 권한(Role)을 처리하는 함수]
     * Payload의 'role'에 해당한다.
     * DB의 RoleType Enum을 Spring Security의 GrantedAuthority로 변환한다.
     * - GrantedAuthority: Spring Security에서 권한을 표현하는 인터페이스
     * - SimpleGrantedAuthority: 문자열로 된 권한을 담는 구현체
     * @return
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // 1. User의 권한을 담을 List<> 생성
        List<GrantedAuthority> authorities = new ArrayList<>();
        // 2. role이 null일 경우 방어 로직
        if (userAuthDto.role() != null) {
            // 3. 사용자가 갖고 있는 권한을 SimpleGrantedAuthority에 담아 List<GrantedAuthority>에 추가
            authorities.add(new SimpleGrantedAuthority(userAuthDto.role().name()));
        }
        return authorities;
    }

    /**
     * [Password 처리 함수]
     * 인증 과정에서 비밀번호 비교 시 사용됩니다.
     * (이 인증 과정에서는 Password가 존재하지 않기 때문에 null을 반환하도록 설정한다)
     */
    @Override
    public String getPassword() {
        return null;
    }

    /**
     * [Username 처리 -> User PK]
     * JWT의 Subject에 들어갈 값이자, Spring Security 내에서 이 유저를 식별하는 고유 ID입니다.
     */
    @Override
    public String getUsername() {
        return userAuthDto.userId().toString();
    }
}
