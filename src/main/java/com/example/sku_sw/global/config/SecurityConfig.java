package com.example.sku_sw.global.config;

import com.example.sku_sw.global.security.JwtAuthFilter;
import com.example.sku_sw.global.security.handler.JwtAccessDeniedHandler;
import com.example.sku_sw.global.security.handler.UserAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {
    private final CorsConfigurationSource corsConfigurationSource;
    private final UserAuthenticationEntryPoint userAuthenticationEntryPoint;
    private final JwtAccessDeniedHandler jwtAccessDeniedHandler;
    private final JwtAuthFilter jwtAuthFilter;

    private static final String[] AUTH_WHITELIST = {
            "/api/v1/auth/register/email",
            "/api/v1/auth/login/email",
            "/api/v1/auth/refresh",
            "/v3/api-docs/**",  // Swagger JSON 데이터
            "/swagger-ui/**",   // Swagger UI CSS, JS, 이미지
            "/swagger-ui-custom.html",  // Swagger UI 메인 페이지
            "/manage/health",  // AWS의 ALB 헬스 체크 경로
            "/manage/prometheus", // Prometheus Metrics 수집 경로
            "/api/v1/stream/ws", // WebSocket 핸드셰이크 경로 (HandshakeInterceptor에서 JWT 검증)
    };

    /**
     * Spring Security의 FilterChain 설정 - 각 Request마다 해당 filterChain에 등록된 필터들이 순서대로 실행된다.
     * @param http : HttpSecurity는 SecurityFilterChain을 조립하는 Builder(DSL)이다.
     * @return SecurityFilterChain
     * @throws Exception
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        /*
         * 1. CSRF 방어 기능 비활성화
         * JWT 기반 Stateless API에서는 폼 로그인-세션 기반 인증이 아니므로 CSRF 방어 기능이 필요 없음
         */
        http.csrf(AbstractHttpConfigurer::disable);

        /*
         * 2. Cors 설정
         * http의 .cors() 안에 configurationSource를 명시적으로 지정한다.
         */
        http.cors(corsConfigurer -> corsConfigurer.configurationSource(corsConfigurationSource));

        /*
         * 3. 세션 관리
         * 세션을 사용하지 않도록 설정한다.
         */
        http.sessionManagement(sessionManageMent -> sessionManageMent.sessionCreationPolicy(
                SessionCreationPolicy.STATELESS
        ));

        /*
         * 4. 폼 로그인 & HTTP Basic 끄기
         * 폼 로그인(세션)과 Basic 인증을 꺼서 JWT만 사용하도록 설정함
         */
        http.formLogin(AbstractHttpConfigurer::disable);
        http.httpBasic(AbstractHttpConfigurer::disable);

        /*
         * 5. JWT 필터 등록
         * UsernamePasswordAuthenticationFilter 앞에 직접 만든 JwtAuthFilter를 넣는다.
         * [ http.addFilterBefore("추가할 필터", 기존 필터) ]
         * 해당 필터로 JWT를 통한 인증 처리를 수행하게 한다.
         */
        http.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        /*
         * 6. 예외 처리 핸들러 설정 - 인증 실패(401) 및 접근 거부(403) 예외를 처리하는 핸들러 설정
         */
        http.exceptionHandling(e -> e
                .authenticationEntryPoint(userAuthenticationEntryPoint) // 401 에러 핸들러
                .accessDeniedHandler(jwtAccessDeniedHandler)            // 403 에러 핸들러
        );

        /*
         * 6. 권한 규칙 작성
         * - 화이트 리스트에 있는 경로는 누구나 접근할 수 있도록 허용한다.
         * - "/admin/**"로 요청이 왔을 때, 해당 사용자가 "ADMIN" Role을 갖고 있을 때만 접속을 허용한다.
         */
        http.authorizeHttpRequests(requests -> requests
                .requestMatchers(AUTH_WHITELIST).permitAll()
                .requestMatchers("/admin/**").hasAuthority("ROLE_ADMIN")
                .anyRequest().authenticated()
        );

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
