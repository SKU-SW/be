package com.example.sku_sw.domain.auth.service;

import com.example.sku_sw.domain.auth.dto.AuthChzzkAuthUrlResDto;
import com.example.sku_sw.domain.auth.dto.AuthChzzkRefreshTokenReqDto;
import com.example.sku_sw.domain.auth.dto.AuthChzzkTokenReqDto;
import com.example.sku_sw.domain.auth.dto.AuthChzzkTokenRevokeReqDto;
import com.example.sku_sw.domain.auth.dto.AuthChzzkTokenResDto;
import com.example.sku_sw.domain.auth.dto.AuthLoginEmailReqDto;
import com.example.sku_sw.domain.auth.dto.AuthLoginEmailResDto;
import com.example.sku_sw.domain.auth.dto.AuthLogoutReqDto;
import com.example.sku_sw.domain.auth.dto.AuthRefreshTokenReqDto;
import com.example.sku_sw.domain.auth.dto.AuthRefreshTokenResDto;
import com.example.sku_sw.domain.auth.dto.AuthRegisterEmailReqDto;
import com.example.sku_sw.domain.auth.enums.AuthErrorCode;
import com.example.sku_sw.domain.user.entity.User;
import com.example.sku_sw.domain.user.enums.RegisterType;
import com.example.sku_sw.domain.user.repository.UserRepository;
import com.example.sku_sw.global.exception.CustomException;
import com.example.sku_sw.global.security.CustomUserDetails;
import com.example.sku_sw.global.security.SecurityUtil;
import com.example.sku_sw.global.security.module.JwtTokenType;
import com.example.sku_sw.global.security.module.UserAuthDto;
import com.example.sku_sw.global.util.JwtUtil;
import com.example.sku_sw.global.util.RedisUtil;
import com.example.sku_sw.global.util.module.RedisValue;
import com.example.sku_sw.global.util.module.TokenInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String CHZZK_AUTH_BASE_URL = "https://chzzk.naver.com/account-interlock";
    private static final long CHZZK_AUTH_STATE_TTL_MILLIS = 10 * 60 * 1000L;
    private static final long CHZZK_REFRESH_TOKEN_EXPIRES_DAYS = 30L;
    private static final String CHZZK_AUTHORIZATION_GRANT_TYPE = "authorization_code";
    private static final String CHZZK_REFRESH_TOKEN_GRANT_TYPE = "refresh_token";
    private static final String CHZZK_REFRESH_TOKEN_TYPE_HINT = "refresh_token";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final RedisUtil redisUtil;
    private final AuthChzzkApiService authChzzkApiService;

    @Value("${chzzk.client-id}")
    private String chzzkClientId;

    @Value("${chzzk.client-secret}")
    private String chzzkClientSecret;

    @Value("${chzzk.redirect-uri}")
    private String chzzkRedirectUri;

    /**
     * 이메일 회원가입을 처리하는 함수
     * - 이메일 중복 여부를 확인한다.
     * - 비밀번호와 비밀번호 확인 값의 일치 여부를 검증한다.
     * - 비밀번호를 단방향 해싱한 뒤 User 엔티티를 생성 및 저장한다.
     * @param reqDto : 이메일 회원가입 요청 DTO
     */
    @Transactional
    public void registerEmail(AuthRegisterEmailReqDto reqDto) {
        log.info("[AuthService] registerEmail() - START | email: {}", reqDto.email());

        // 1. 동일한 이메일이 이미 가입되어 있는지 먼저 검사한다.
        if (userRepository.existsByEmail(reqDto.email())) {
            throw new CustomException(AuthErrorCode.ALREADY_EXIST_EMAIL);
        }

        // 2. 사용자가 입력한 비밀번호와 비밀번호 확인 값이 같은지 검증한다.
        validatePassword(reqDto.password(), reqDto.passwordConfirm());

        // 3. DB에는 평문 비밀번호를 저장하지 않고, PasswordEncoder를 이용해 단방향 해싱 결과만 저장한다.
        String encodedPassword = passwordEncoder.encode(reqDto.password());

        // 4. 회원가입 타입을 EMAIL로 고정하여 User 엔티티를 생성한다.
        User user = User.createUser(
                reqDto.name(),
                reqDto.email(),
                encodedPassword,
                RegisterType.EMAIL
        );

        // 5. 생성한 사용자를 저장하여 회원가입을 완료한다.
        User savedUser = userRepository.save(user);

        log.info("[AuthService] registerEmail() - END | userId: {}, email: {}", savedUser.getId(), savedUser.getEmail());
    }

    /**
     * 이메일 로그인을 처리하는 함수
     * - 이메일로 사용자를 조회한다.
     * - PasswordEncoder를 통해 비밀번호를 검증한다.
     * - JwtUtil을 이용해 Access Token / Refresh Token을 발급한다.
     * - Refresh Token은 해싱한 뒤 Redis에 저장한다.
     * @param reqDto : 이메일 로그인 요청 DTO
     * @return : 이메일 로그인 응답 DTO
     */
    @Transactional(readOnly = true)
    public AuthLoginEmailResDto loginEmail(AuthLoginEmailReqDto reqDto) {
        log.info("[AuthService] loginEmail() - START | email: {}", reqDto.email());

        // ============================================================
        // 1. 사용자 조회 및 로그인 자격 검증
        // ============================================================
        // 1-1. 이메일 기준으로 사용자를 조회한다.
        User user = findUserByEmail(reqDto.email());

        // 1-2. 요청으로 들어온 평문 비밀번호와 DB에 저장된 해시 비밀번호를 비교한다.
        validateLoginPassword(reqDto.password(), user.getHashedPassword());

        // ============================================================
        // 2. JWT 발급을 위한 인증 객체 구성
        // ============================================================
        // 2-1. JWT Payload 구성에 필요한 최소 사용자 정보를 UserAuthDto로 묶는다.
        UserAuthDto userAuthDto = UserAuthDto.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .role(user.getRole())
                .registerType(user.getRegisterType())
                .build();

        // 2-2. JwtUtil이 그대로 사용할 수 있도록 CustomUserDetails로 감싼다.
        CustomUserDetails customUserDetails = new CustomUserDetails(userAuthDto);

        // ============================================================
        // 3. Access Token / Refresh Token 발급
        // ============================================================
        // 3-1. Access Token을 발급한다.
        TokenInfo accessTokenInfo = jwtUtil.createAccessToken(customUserDetails);

        // 3-2. Refresh Token을 발급한다.
        TokenInfo refreshTokenInfo = jwtUtil.createRefreshToken(customUserDetails);

        // ============================================================
        // 4. Refresh Token Redis 저장
        // ============================================================
        // 4-1. Redis Key에 사용할 수 있도록 Refresh Token을 해시 문자열로 변환한다.
        String hashedRefreshToken = redisUtil.hashRefreshToken(refreshTokenInfo.token());

        // 4-2. Redis Value에는 재검증에 필요한 사용자 식별 정보를 저장한다.
        RedisValue redisValue = RedisValue.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .role(user.getRole())
                .registerType(user.getRegisterType())
                .build();

        // 4-3. Redis TTL은 방금 발급한 Refresh Token의 실제 만료 시각에 맞춘다.
        long refreshTokenTtl = calculateRefreshTokenTtl(refreshTokenInfo.token());

        // 4-4. RedisUtil의 기존 저장 메서드를 사용해 "RT:{hashedRefreshToken}" 키 구조로 저장한다.
        redisUtil.setHashedRefreshTokenValue(hashedRefreshToken, redisValue, refreshTokenTtl);

        // ============================================================
        // 5. API 응답 DTO 생성
        // ============================================================
        // 5-1. 로그인 성공 응답에는 사용자 기본 정보와 발급된 Access/Refresh Token을 모두 담아 반환한다.
        AuthLoginEmailResDto result = new AuthLoginEmailResDto(
                user.getId(),
                user.getEmail(),
                user.getName(),
                accessTokenInfo.token(),
                refreshTokenInfo.token()
        );

        log.info("[AuthService] loginEmail() - END | userId: {}, email: {}", user.getId(), user.getEmail());
        return result;
    }

    /**
     * 로그아웃을 처리하는 함수
     * @param reqDto : 로그아웃 요청 DTO
     */
    @Transactional
    public void logout(AuthLogoutReqDto reqDto) {
        log.info("[AuthService] logout() - START");

        // ============================================================
        // 1. Access Token 블랙리스트 등록
        // ============================================================
        // 1-1. Access Token을 Redis 블랙리스트에 등록한다.
        redisUtil.setTokenInBlacklist(reqDto.accessToken());

        // ============================================================
        // 2. Refresh Token 삭제
        // ============================================================
        // 2-1. Refresh Token을 Redis Key 형식에 맞게 해싱한다.
        String hashedRefreshToken = redisUtil.hashRefreshToken(reqDto.refreshToken());

        // 2-2. 해싱된 Refresh Token을 Redis에서 삭제한다.
        redisUtil.deleteHashedRefreshToken(hashedRefreshToken);

        log.info("[AuthService] logout() - END");
    }

    /**
     * 토큰 재발급을 처리하는 함수
     * - 기존 Refresh Token을 검증한다.
     * - Redis에서 해당 Refresh Token이 유효한지 확인한다.
     * - 새로운 Access Token과 Refresh Token을 발급한다.
     * - Redis Lua Script 기반 원자적 회전을 통해 기존 Refresh Token을 교체하고 재사용 여부를 감지한다.
     * @param reqDto : 토큰 재발급 요청 DTO
     * @return : 토큰 재발급 응답 DTO
     */
    @Transactional(readOnly = true)
    public AuthRefreshTokenResDto refreshToken(AuthRefreshTokenReqDto reqDto) {
        log.info("[AuthService] refreshToken() - START");

        String oldRefreshToken = reqDto.refreshToken();

        // ============================================================
        // 1. Refresh Token 검증
        // ============================================================
        // 1-1. Refresh Token의 유효성을 검증한다.
        if (!jwtUtil.validateToken(oldRefreshToken)) {
            log.warn("[AuthService] refreshToken() - 유효하지 않은 Refresh Token");
            throw new CustomException(AuthErrorCode.INVALID_REFRESH_TOKEN);
        }

        // 1-2. 해당 토큰이 Refresh Token 타입인지 확인한다.
        if (jwtUtil.getTokenType(oldRefreshToken) != JwtTokenType.REFRESH) {
            log.warn("[AuthService] refreshToken() - Access Token으로 재발급 시도");
            throw new CustomException(AuthErrorCode.INVALID_REFRESH_TOKEN);
        }

        // ============================================================
        // 2. Redis에서 Refresh Token 존재 여부 확인
        // ============================================================
        // 2-1. Refresh Token을 해시하여 Redis Key로 변환한다.
        String hashedRefreshToken = redisUtil.hashRefreshToken(oldRefreshToken);

        // 2-2. Redis에서 해당 Refresh Token이 존재하는지 확인한다.
        RedisValue redisValue = redisUtil.getHashedRefreshTokenValue(hashedRefreshToken);
        if (redisValue == null) {
            log.warn("[AuthService] refreshToken() - Redis에 존재하지 않는 Refresh Token");
            throw new CustomException(AuthErrorCode.INVALID_REFRESH_TOKEN);
        }

        // ============================================================
        // 3. Redis 사용자 정보로 인증 객체 구성
        // ============================================================
        // 3-1. Redis에 저장된 사용자 정보를 그대로 사용해 JWT Payload용 정보를 묶는다.
        UserAuthDto userAuthDto = UserAuthDto.builder()
                .userId(redisValue.userId())
                .email(redisValue.email())
                .role(redisValue.role())
                .registerType(redisValue.registerType())
                .build();

        // 3-2. JwtUtil이 그대로 사용할 수 있도록 CustomUserDetails로 감싼다.
        CustomUserDetails customUserDetails = new CustomUserDetails(userAuthDto);

        // ============================================================
        // 4. 새로운 Access Token / Refresh Token 발급
        // ============================================================
        // 4-1. 새로운 Access Token을 발급한다.
        TokenInfo accessTokenInfo = jwtUtil.createAccessToken(customUserDetails);

        // 4-2. 새로운 Refresh Token을 발급한다.
        TokenInfo refreshTokenInfo = jwtUtil.createRefreshToken(customUserDetails);

        // ============================================================
        // 5. Refresh Token 원자적 회전 처리
        // ============================================================
        // 5-1. 새로운 Refresh Token을 해시하여 Redis Key로 변환한다.
        String newHashedRefreshToken = redisUtil.hashRefreshToken(refreshTokenInfo.token());

        // 5-2. Redis Value에는 재검증에 필요한 사용자 식별 정보를 저장한다.
        RedisValue newRedisValue = RedisValue.builder()
                .userId(redisValue.userId())
                .email(redisValue.email())
                .role(redisValue.role())
                .registerType(redisValue.registerType())
                .build();

        // 5-3. Redis TTL은 방금 발급한 Refresh Token의 실제 만료 시각에 맞춘다.
        long newRefreshTokenTtl = calculateRefreshTokenTtl(refreshTokenInfo.token());

        // 5-4. Redis Lua Script를 사용해 기존 Refresh Token 삭제와 새 Refresh Token 저장을 원자적으로 처리한다.
        //      기존 토큰이 이미 삭제된 경우(재사용 감지) false를 반환한다.
        boolean rotated = redisUtil.rotateRefreshToken(hashedRefreshToken, newHashedRefreshToken, newRedisValue, newRefreshTokenTtl);
        if (!rotated) {
            log.warn("[AuthService] refreshToken() - Refresh Token 재사용 감지 | userId: {}", redisValue.userId());
            throw new CustomException(AuthErrorCode.REFRESH_TOKEN_REUSE);
        }
        log.debug("[AuthService] refreshToken() - Refresh Token 원자적 회전 완료");

        // ============================================================
        // 6. API 응답 DTO 생성
        // ============================================================
        AuthRefreshTokenResDto result = new AuthRefreshTokenResDto(
                accessTokenInfo.token(),
                refreshTokenInfo.token()
        );

        log.info("[AuthService] refreshToken() - END | userId: {}, email: {}", redisValue.userId(), redisValue.email());
        return result;
    }

    /**
     * 치지직 인증 URL을 생성하는 함수
     * - clientId, redirectUri, state를 포함한 치지직 계정 연동 URL을 생성한다.
     * - 생성한 state는 callback 검증을 위해 Redis에 저장한다.
     * @return : 치지직 인증 URL 응답 DTO
     */
    @Transactional
    public AuthChzzkAuthUrlResDto getChzzkAuthUrl() {
        log.info("[AuthService] getChzzkAuthUrl() - START");

        /*
            1. 현재 로그인 사용자 ID 조회
            - callback 이후 치지직 토큰을 저장할 대상을 식별하기 위해 현재 사용자 ID를 가져온다.
         */
        Long userId = SecurityUtil.getCurrentUserId();

        /*
            2. 사용자 ID 기반 치지직 인증 URL 생성
            - 실제 치지직 인증 URL 생성 로직은 공통 메서드에 위임한다.
         */
        AuthChzzkAuthUrlResDto result = createChzzkAuthUrl(userId);

        log.info("[AuthService] getChzzkAuthUrl() - END | userId: {}", userId);
        return result;
    }

    /**
     * 사용자 ID 기준 치지직 인증 URL을 생성하는 함수
     * - clientId, redirectUri, state를 포함한 치지직 계정 연동 URL을 생성한다.
     * - 생성한 state는 callback 검증을 위해 Redis에 저장한다.
     * @param userId : 치지직 인증을 수행할 사용자 ID
     * @return : 치지직 인증 URL 응답 DTO
     */
    @Transactional
    public AuthChzzkAuthUrlResDto createChzzkAuthUrl(Long userId) {
        log.info("[AuthService] createChzzkAuthUrl() - START | userId: {}", userId);

        /*
            1. 치지직 인증 state 생성
            - CSRF 방지를 위해 임시 UUID 기반 state 값을 생성한다.
         */
        String state = UUID.randomUUID().toString();

        /*
            2. state Redis 저장
            - callback 요청 검증 및 사용자 식별을 위해 생성 직후 Redis에 state와 userId를 저장한다.
         */
        redisUtil.setChzzkAuthState(state, userId, CHZZK_AUTH_STATE_TTL_MILLIS);

        /*
            3. 치지직 인증 URL 생성
            - clientId, redirectUri, state를 query parameter로 조합한다.
         */
        String authUrl = UriComponentsBuilder.fromHttpUrl(CHZZK_AUTH_BASE_URL)
                .queryParam("clientId", chzzkClientId)
                .queryParam("redirectUri", chzzkRedirectUri)
                .queryParam("state", state)
                .build()
                .toUriString();

        /*
            4. ResponseDto 생성
            - 프론트가 바로 redirect에 사용할 수 있도록 치지직 인증 URL을 반환한다.
         */
        AuthChzzkAuthUrlResDto result = AuthChzzkAuthUrlResDto.builder()
                .authUrl(authUrl)
                .build();

        log.info("[AuthService] createChzzkAuthUrl() - END | userId: {}, state: {}", userId, state);
        return result;
    }

    /**
     * 치지직 Access Token 재발급을 처리하는 함수
     * - 저장된 Refresh Token으로 치지직 Access / Refresh Token 재발급을 시도한다.
     * - 재발급 성공 시 사용자 엔티티의 치지직 인증 토큰 정보를 최신 값으로 갱신한다.
     * @param user : 치지직 토큰을 재발급할 사용자 엔티티
     */
    @Transactional
    public void refreshChzzkAccessToken(User user) {
        log.info("[AuthService] refreshChzzkAccessToken() - START | userId: {}", user.getId());

        /*
            1. 치지직 Refresh Token 재발급 요청 DTO 생성
            - refresh_token grant 방식으로 치지직 Access / Refresh Token 재발급 요청을 준비한다.
         */
        AuthChzzkRefreshTokenReqDto refreshTokenRequest = new AuthChzzkRefreshTokenReqDto(
                CHZZK_REFRESH_TOKEN_GRANT_TYPE,
                user.getChzzkAuthRefreshToken(),
                chzzkClientId,
                chzzkClientSecret
        );

        try {
            /*
                2. 치지직 Access Token 재발급 요청
                - 치지직 Open API를 호출해 Access / Refresh Token 재발급을 수행한다.
             */
            AuthChzzkTokenResDto tokenResponse = authChzzkApiService.requestRefreshToken(refreshTokenRequest);
            validateChzzkTokenResponse(tokenResponse);

            /*
                3. 사용자 치지직 인증 정보 갱신
                - 새로 발급된 Access / Refresh Token과 만료 시각을 사용자 엔티티에 반영한다.
             */
            applyChzzkAuthTokens(user, tokenResponse);
        } catch (CustomException e) {
            if (e.getErrorCode() == AuthErrorCode.CHZZK_AUTH_REFRESH_TOKEN_INVALID) {
                throw e;
            }

            throw new CustomException(AuthErrorCode.CHZZK_AUTH_TOKEN_REFRESH_FAILED);
        }

        log.info("[AuthService] refreshChzzkAccessToken() - END | userId: {}", user.getId());
    }

    /**
     * 저장된 치지직 토큰 만료 및 로컬 인증 정보 정리를 처리하는 함수
     * - 저장된 Refresh Token이 있으면 치지직 revoke API를 호출해 동일 인증 과정의 토큰 만료를 요청한다.
     * - 치지직 revoke 실패 여부와 관계없이 사용자 엔티티의 로컬 치지직 인증 토큰은 정리한다.
     * @param user : 치지직 토큰을 정리할 사용자 엔티티
     * @return : 치지직 revoke 원격 호출 성공 여부
     */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = CustomException.class)
    public boolean revokeChzzkTokens(User user) {
        log.info("[AuthService] revokeChzzkTokens() - START | userId: {}", user.getId());

        /*
            1. 치지직 토큰 존재 여부 확인
            - 저장된 치지직 토큰이 없으면 외부 API 호출 없이 종료한다.
         */
        if (isBlank(user.getChzzkAuthAccessToken()) && isBlank(user.getChzzkAuthRefreshToken())) {
            user.clearChzzkAuthTokens();
            log.info("[AuthService] revokeChzzkTokens() - END | userId: {}, revokeSucceeded: true, reason: no_tokens", user.getId());
            return true;
        }

        /*
            2. 치지직 토큰 만료 요청 DTO 생성
            - Refresh Token 기준으로 치지직 revoke API 요청을 준비한다.
         */
        AuthChzzkTokenRevokeReqDto revokeRequest = new AuthChzzkTokenRevokeReqDto(
                chzzkClientId,
                chzzkClientSecret,
                user.getChzzkAuthRefreshToken(),
                CHZZK_REFRESH_TOKEN_TYPE_HINT
        );

        boolean revokeSucceeded = true;

        try {
            /*
                3. 치지직 토큰 만료 요청
                - 동일 인증 과정으로 발급된 치지직 토큰 전체 제거를 요청한다.
             */
            authChzzkApiService.revokeToken(revokeRequest);
        } catch (CustomException e) {
            revokeSucceeded = false;
            log.error("[AuthService] revokeChzzkTokens() - CHZZK token revoke failed | userId: {}, errorCode: {}",
                    user.getId(), e.getErrorCode());
        } finally {
            /*
                4. 로컬 치지직 인증 정보 정리
                - 회원탈퇴 또는 계정 정리 시 로컬 DB에 저장된 치지직 인증 정보는 항상 제거한다.
             */
            user.clearChzzkAuthTokens();
        }

        log.info("[AuthService] revokeChzzkTokens() - END | userId: {}, revokeSucceeded: {}", user.getId(), revokeSucceeded);
        return revokeSucceeded;
    }

    /**
     * 치지직 인증 callback 요청을 검증하는 함수
     * - Redis에 저장된 state 존재 여부를 확인해 유효한 인증 요청인지 검증한다.
     * - 치지직 토큰 발급 API를 호출한 뒤 Access / Refresh Token과 만료 시각을 사용자 정보에 저장한다.
     * @param code : 치지직 인증 authorization code
     * @param state : callback으로 전달된 state 값
     */
    @Transactional
    public void handleChzzkCallback(String code, String state) {
        log.info("[AuthService] handleChzzkCallback() - START | state: {}", state);

        /*
            1. 치지직 인증 state 검증
            - Redis에 저장된 state 값이 없으면 유효하지 않은 인증 요청으로 판단한다.
         */
        if (!redisUtil.hasChzzkAuthState(state)) {
            throw new CustomException(AuthErrorCode.CHZZK_AUTH_STATE_INVALID);
        }

        /*
            2. state에 매핑된 사용자 ID 조회
            - Redis value에 저장한 userId를 읽어 치지직 토큰 저장 대상을 식별한다.
         */
        Long userId = redisUtil.getChzzkAuthStateUserId(state);
        if (userId == null) {
            throw new CustomException(AuthErrorCode.CHZZK_AUTH_STATE_INVALID);
        }

        /*
            3. 치지직 토큰 발급 요청 DTO 생성
            - authorization_code grant 방식으로 치지직 Access / Refresh Token 발급 요청을 준비한다.
         */
        AuthChzzkTokenReqDto tokenRequest = new AuthChzzkTokenReqDto(
                CHZZK_AUTHORIZATION_GRANT_TYPE,
                chzzkClientId,
                chzzkClientSecret,
                code,
                state
        );

        /*
            4. 치지직 토큰 발급 요청
            - 치지직 Open API를 호출해 Access / Refresh Token을 교환한다.
         */
        AuthChzzkTokenResDto tokenResponse = authChzzkApiService.requestToken(tokenRequest);
        validateChzzkTokenResponse(tokenResponse);

        /*
            5. 사용자 조회
            - Redis state에 매핑된 userId로 사용자를 조회한다.
         */
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(AuthErrorCode.CHZZK_AUTH_STATE_INVALID));

        /*
            6. 사용자 치지직 인증 정보 저장
            - Access / Refresh Token, 각 만료 시각, 인증 완료 상태를 사용자 엔티티에 반영한다.
         */
        applyChzzkAuthTokens(user, tokenResponse);


        log.info("[AuthService] handleChzzkCallback() - END | state: {}", state);
    }

    /**
     * 이메일로 사용자를 조회하는 함수
     * - 보안상 이메일 존재 여부와 비밀번호 오류를 구분하지 않고 동일한 로그인 실패 예외로 처리한다.
     * @param email : 로그인 시도 이메일
     * @return : 조회된 사용자 엔티티
     */
    private User findUserByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(AuthErrorCode.INVALID_LOGIN_CREDENTIALS));
    }

    /**
     * 로그인 비밀번호를 검증하는 함수
     * - PasswordEncoder.matches()를 사용해 평문 비밀번호와 저장된 해시 비밀번호를 비교한다.
     * @param rawPassword : 사용자가 입력한 평문 비밀번호
     * @param encodedPassword : DB에 저장된 해시 비밀번호
     */
    private void validateLoginPassword(String rawPassword, String encodedPassword) {
        if (!passwordEncoder.matches(rawPassword, encodedPassword)) {
            throw new CustomException(AuthErrorCode.INVALID_LOGIN_CREDENTIALS);
        }
    }

    /**
     * Refresh Token Redis TTL을 계산하는 함수
     * - JwtUtil이 반환하는 만료 시각을 기준으로 현재 시각과의 차이를 계산한다.
     * @param refreshToken : 방금 발급한 Refresh Token
     * @return : Redis TTL(ms)
     */
    private long calculateRefreshTokenTtl(String refreshToken) {
        return jwtUtil.getExpirationInMs(refreshToken) - System.currentTimeMillis();
    }

    /**
     * 비밀번호와 비밀번호 확인 값의 일치 여부를 검증하는 함수
     * @param password : 비밀번호
     * @param passwordConfirm : 비밀번호 확인
     */
    private void validatePassword(String password, String passwordConfirm) {
        if (!password.equals(passwordConfirm)) {
            throw new CustomException(AuthErrorCode.PASSWORD_CONFIRM_MISMATCH);
        }
    }

    /**
     * 치지직 토큰 응답 유효성을 검증하는 함수
     * @param tokenResponse : 치지직 토큰 응답 DTO
     */
    private void validateChzzkTokenResponse(AuthChzzkTokenResDto tokenResponse) {
        if (tokenResponse == null
                || isBlank(tokenResponse.accessToken())
                || isBlank(tokenResponse.refreshToken())
                || isBlank(tokenResponse.tokenType())
                || tokenResponse.expiresIn() == null
                || tokenResponse.expiresIn() <= 0) {
            throw new CustomException(AuthErrorCode.CHZZK_AUTH_TOKEN_RESPONSE_INVALID);
        }
    }

    /**
     * 사용자 엔티티에 치지직 인증 토큰을 반영하는 함수
     * - Access Token 만료 시각은 expiresIn 기준으로 계산한다.
     * - Refresh Token 만료 시각은 현재 시각 기준 30일로 계산한다.
     * @param user : 치지직 토큰을 저장할 사용자 엔티티
     * @param tokenResponse : 치지직 토큰 응답 DTO
     */
    private void applyChzzkAuthTokens(User user, AuthChzzkTokenResDto tokenResponse) {
        LocalDateTime currentDateTime = LocalDateTime.now();
        LocalDateTime accessTokenExpiresAt = currentDateTime.plusSeconds(tokenResponse.expiresIn());
        LocalDateTime refreshTokenExpiresAt = currentDateTime.plus(CHZZK_REFRESH_TOKEN_EXPIRES_DAYS, ChronoUnit.DAYS);

        user.updateChzzkAuthTokens(
                tokenResponse.accessToken(),
                tokenResponse.refreshToken(),
                accessTokenExpiresAt,
                refreshTokenExpiresAt
        );
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
