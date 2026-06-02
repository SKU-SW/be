package com.example.sku_sw.domain.user.mapper;

import com.example.sku_sw.domain.user.dto.ChzzkAuthStatusResDto;
import com.example.sku_sw.domain.user.entity.User;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {

    /**
     * User Entity -> ChzzkAuthStatusResDto 변환
     * - User Entity의 치지직 인증 헬퍼 메서드 평가 결과를 DTO로 매핑한다.
     * @param user : 변환할 User Entity
     * @return : 변환된 ChzzkAuthStatusResDto
     */
    public ChzzkAuthStatusResDto toChzzkAuthStatusResDto(User user) {
        return ChzzkAuthStatusResDto.builder()
                .authorized(user.hasChzzkAuthTokens())
                .accessTokenExpired(user.isChzzkAuthAccessTokenExpired())
                .refreshTokenExpired(user.isChzzkAuthRefreshTokenExpired())
                .build();
    }
}
