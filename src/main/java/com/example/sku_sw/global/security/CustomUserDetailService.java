package com.example.sku_sw.global.security;

import com.example.sku_sw.domain.user.entity.User;
import com.example.sku_sw.domain.user.repository.UserRepository;
import com.example.sku_sw.global.exception.CustomException;
import com.example.sku_sw.global.exception.GlobalErrorCode;
import com.example.sku_sw.global.security.module.UserAuthDto;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [사용자 정보(UserDetails)를 로드하는 클래스]
 * DB에서 사용자를 조회 -> CustomUserDetails 객체 생성 -> Security로 반환
 * JwtAuthFilter는 UserDetailService를 사용하여 사용자 정보를 가져온다.
 * -> UserDetailService는 단순히 사용자 정보를 가져와서 UserDetails로 변환해주는 인터페이스이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomUserDetailService implements UserDetailsService {
    private final UserRepository userRepository;

    /**
     * [userId를 사용해 DB를 직접 검색하여, User 인증 정보가 담긴 CusomUserDetails 객체를 반환하는 함수]
     * JwtAuthFilter가 해당 메서드를 호출해, DB 사용자를 로딩하여 UserDetails를 반환받는다.
     * @param userId : 원래는 username이지만, 이 서비스에서는 userId를 사용한다
     * @return userId로 검색한 사용자 정보가 담긴 CustomUserDetails 객체
     */
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(@NonNull String userId) throws UsernameNotFoundException {
        /*
            1. 전달받은 userId를 이용해서 사용자 정보를 조회한다.
            - 사용자 정보가 DB에 없다면 예외를 반환한다.
         */
        Long parsedUserId = Long.parseLong(userId);
        User user = userRepository.findById(parsedUserId).orElseThrow(() -> new CustomException(GlobalErrorCode.RESOURCE_NOT_FOUND));

        /*
            2. CustomUserDetails 객체 생성
            - UserAuthDto 생성
         */
        CustomUserDetails userDetails = new CustomUserDetails(UserAuthDto.builder()
                .userId(parsedUserId)
                .email(user.getEmail())
                .role(user.getRole())
                .registerType(user.getRegisterType())
                .build());

        // 3. 로깅
        log.debug("UserDetails 생성 완료 - userId: {}", userId);

        return userDetails;
    }


}
