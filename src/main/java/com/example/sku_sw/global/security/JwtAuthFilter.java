package com.example.sku_sw.global.security;


import com.example.sku_sw.domain.user.entity.User;
import com.example.sku_sw.domain.user.enums.RegisterType;
import com.example.sku_sw.domain.user.enums.UserRole;
import com.example.sku_sw.domain.user.repository.UserRepository;
import com.example.sku_sw.global.exception.CustomException;
import com.example.sku_sw.global.exception.GlobalErrorCode;
import com.example.sku_sw.global.security.module.JwtTokenType;
import com.example.sku_sw.global.security.module.UserAuthDto;
import com.example.sku_sw.global.util.JwtUtil;
import com.example.sku_sw.global.util.RedisUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * [JWT 인증 필터]
 * 클라이언트 요청 시 헤더에 담긴 JWT Access Token을 검증하고, 해당 토큰이 유효한 경우
 * 사용자 인증 객체(Authentication)를 생성하여 SecurityContext에 저장한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {
    private final JwtUtil jwtUtil;
    private final RedisUtil redisUtil;
    private final CustomUserDetailService customUserDetailService;
    private final AuthenticationEntryPoint authenticationEntryPoint; // 필터링 레벨에서의 예외처리를 위한 클래스
    private static final String AUTHORIZATION_HEADER = "Authorization"; // HTTP 헤더 Key
    private static final String BEARER_PREFIX = "Bearer "; // 토큰 접두사
    private final UserRepository userRepository;

    /**
     * [JWT Access Token 검증 필터 함수]
     * JwtAuthFilter 자체적으로 사용자를 검증한다.
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {
        /*
            1. Request Header에서 토큰 추출
            - Authorization Header에서 토큰만 추출한다.
            - 토큰이 없다면 다음 필터로 넘긴다 (미인증 요청은 에러가 아니므로 예외를 반환하지 않는다)
        */
        String token = getTokenFromRequestHeader(request);
        if(token == null){
            log.debug("Authorization 헤더가 존재하지 않습니다. URI: {}", request.getRequestURI());
            filterChain.doFilter(request, response);
            return;
        }
        try {
            /*
                 2. JwtTokenType을 가져와 Access Token인지 확인한다.
                 - Refresh Token으로는 API 접근이 불가하도록 설정한다.
                 - Access Token이 아닌 다른 토큰(Refresh)으로 요청이 왔다면 에러 로그 & 예외를 발생시킨다.
             */
            JwtTokenType type = jwtUtil.getTokenType(token);
            if (!JwtTokenType.ACCESS.equals(type)) {
                log.warn("Access Token이 아닌 토큰으로 접근 시도. Type: {}", type);
                throw new CustomException(GlobalErrorCode.ACCESS_WITH_NON_ACCESS_TYPE_TOKEN);
            }

            /*
                3. 해당 Access Token이 Redis의 블랙리스트에 존재하는지 확인한다.
             */
            if(redisUtil.hasTokenInBlacklist(token)){
                log.warn("[Redis] BlackList에 등록된 Access Token으로 접근이 시도되었습니다. Token: {}", token);
                throw new CustomException(GlobalErrorCode.BLACKLISTED_TOKEN);
            }

            // 4. DB 조회 없이 토큰 정보만으로 인증 객체 생성
            Authentication authentication = createAuthentication(token);

            // 5. SecurityContext에 저장
            SecurityContextHolder.getContext().setAuthentication(authentication);
            log.debug("Security Context에 인증 정보를 저장했습니다: {}", authentication.getName());
        } catch (CustomException e) {
            log.error("인증 처리 중 비즈니스 예외 발생: {}", e.getMessage());
            request.setAttribute("exception", e); // EntryPoint에서 처리하도록 속성 저장
            authenticationEntryPoint.commence(request, response, new AuthenticationException(e.getMessage(), e) {});
            return; // 여기서 끝내야지, 아래의 doFilter가 실행되지 않음 (Double Response 방지)
        } catch (Exception e) {
            log.error("인증 필터 내부 시스템 오류: {}", e.getMessage());
            request.setAttribute("exception", e);
            authenticationEntryPoint.commence(request, response, new AuthenticationException(e.getMessage(), e) {});
            return; // 여기서 끝내야지, 아래의 doFilter가 실행되지 않음 (Double Response 방지)
        }

        // 6. 다음 필터로 요청 전달
        filterChain.doFilter(request, response);
    }


    // =========================================
    // [Private]
    // =========================================
    /**
     * [Http Request Header에서 토큰 정보를 꺼내오는 메서드]
     * - Authorization 헤더 값을 추출한다.
     * - 클라이언트가 요청할 때, Authorization 헤더는 아래처럼 온다
     *      -> "Authorization: Bearer eyJhbGci..."
     * - "Bearer " 부분은 인증 스키마이기 때문에, 해당 부분을 자르고 순수한 JWT 문자열을 구하기 위해 substring을 하여 토큰 부분만 추출한다.
     */
    private String getTokenFromRequestHeader(HttpServletRequest request) {
        String bearerToken = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(BEARER_PREFIX)) {
            return bearerToken.substring(7); // "Bearer " 이후의 문자열만 반환
        }
        return null;
    }

    /**
     * [Authentication 객체 생성 메서드]
     * JWT Claims에서 정보를 추출하여 DB로부터 데이터를 가져온 뒤 Principal을 구성한다.
     * @param accessToken : 클라이언트로부터 받은 Access Token
     * @return Authentication 객체
     */
    private Authentication createAuthentication(String accessToken) {
        // 1. Access Token에서 데이터 추출
        Long userId = jwtUtil.getUserIdFromToken(accessToken);

        // 2. UserAuthDto & CustomUserDetails 생성
        CustomUserDetails customUserDetails = (CustomUserDetails)customUserDetailService.loadUserByUsername(Long.toString(userId));

        // 3. Spring Security 인증 객체 생성 및 반환
        return new UsernamePasswordAuthenticationToken(customUserDetails, null, customUserDetails.getAuthorities());
    }

}
