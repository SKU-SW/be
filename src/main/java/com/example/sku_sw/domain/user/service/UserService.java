package com.example.sku_sw.domain.user.service;

import com.example.sku_sw.domain.user.dto.ChzzkAuthStatusResDto;
import com.example.sku_sw.domain.user.entity.User;
import com.example.sku_sw.domain.user.enums.UserErrorCode;
import com.example.sku_sw.domain.user.mapper.UserMapper;
import com.example.sku_sw.domain.user.repository.UserRepository;
import com.example.sku_sw.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;

    /**
     * 치지직 인증 상태 조회
     * - userId로 사용자를 조회한 후, 치지직 API 인증 여부 및 토큰 만료 상태를 반환한다.
     * - 존재하지 않는 userId인 경우 UserErrorCode.USER_NOT_FOUND 예외 발생
     * @param userId : 조회할 사용자 ID
     * @return : 치지직 인증 상태가 담긴 ChzzkAuthStatusResDto
     */
    public ChzzkAuthStatusResDto getChzzkAuthStatus(Long userId) {
        log.info("[UserService] getChzzkAuthStatus() - START | userId: {}", userId);

        /*
            (1) 사용자 단건 조회
            - userId로 사용자를 조회하고, 존재하지 않으면 USER_NOT_FOUND 예외를 발생시킨다.
         */
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(UserErrorCode.USER_NOT_FOUND));

        /*
            (2) 치지직 인증 상태 평가 및 DTO 매핑
            - User Entity의 헬퍼 메서드로 인증 상태를 평가하고, Mapper로 DTO 변환한다.
         */
        ChzzkAuthStatusResDto result = userMapper.toChzzkAuthStatusResDto(user);

        log.info("[UserService] getChzzkAuthStatus() - END | authorized: {}, accessTokenExpired: {}, refreshTokenExpired: {}",
                result.authorized(), result.accessTokenExpired(), result.refreshTokenExpired());
        return result;
    }
}
