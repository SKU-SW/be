package com.example.sku_sw.global.util;

import com.example.sku_sw.global.util.module.RedisValue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.HexFormat;

/**
 * Redis 관련 유틸 로직을 수행하는 Util 함수
 */
@Slf4j
@Component
public class RedisUtil {
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final JwtUtil jwtUtil;

    public RedisUtil(
            @Qualifier("broadcastStringRedisTemplate") StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            JwtUtil jwtUtil
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.jwtUtil = jwtUtil;
    }

    // ==========================================================
    // [ Refresh Token ]
    // ==========================================================

    /**
     * Refresh Token 문자열을 Redis Key 용 해시 문자열로 변환하는 함수
     * - 원본 Refresh Token을 Redis Key에 그대로 저장하지 않기 위해 사용한다.
     * - SHA-256 결과를 16진수 문자열로 변환하여 "RT:{hashedRefreshToken}" 구조에서 사용한다.
     * @param refreshToken : 원본 Refresh Token
     * @return : SHA-256 기반 해시 문자열
     */
    public String hashRefreshToken(String refreshToken) {
        try {
            /*
             * 1. SHA-256 MessageDigest 인스턴스를 준비한다.
             * 2. Refresh Token 문자열을 UTF-8 바이트로 변환한 뒤 다이제스트를 계산한다.
             * 3. 계산된 바이트 배열을 Redis Key로 쓰기 쉬운 16진수 문자열로 변환한다.
             */
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] hashedBytes = messageDigest.digest(refreshToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashedBytes);
        } catch (NoSuchAlgorithmException e) {
            log.error("[Redis] Refresh Token 해시 생성 중 오류가 발생했습니다. {}", e.getMessage());
            throw new RuntimeException("Refresh Token Hash Error", e);
        }
    }

    /**
     * [Refresh Token 정보 저장 함수 (Set)]
     * - Key: "RT:{hashedRefreshToken}"
     * - Value: 사용자 식별 정보 JSON
     * - TTL: Refresh Token 만료 시각까지 남은 시간
     */
    public void setHashedRefreshTokenValue(String hashedRefreshToken, RedisValue value, long durationInMillis) {
        String key = "RT:" + hashedRefreshToken;
        try {
            String jsonValue = objectMapper.writeValueAsString(value);
            ValueOperations<String, String> values = redisTemplate.opsForValue();
            values.set(key, jsonValue, Duration.ofMillis(durationInMillis));
        } catch (JsonProcessingException e) {
            log.error("Redis Value JSON 변환 오류: {}", e.getMessage());
            throw new RuntimeException("Redis Save Error", e);
        }
    }

    /**
     * [Refresh Token 정보 조회 함수 (Get)]
     * @param hashedRefreshToken : 조회할 해시 Refresh Token
     * @return : Redis에 저장된 Value, 없으면 null
     */
    public RedisValue getHashedRefreshTokenValue(String hashedRefreshToken) {
        String key = "RT:" + hashedRefreshToken;
        ValueOperations<String, String> values = redisTemplate.opsForValue();
        String jsonValue = values.get(key);

        if (jsonValue == null) {
            return null;
        }

        try {
            return objectMapper.readValue(jsonValue, RedisValue.class);
        } catch (JsonProcessingException e) {
            log.error("Redis Value JSON 파싱 오류: {}", e.getMessage());
            return null;
        }
    }

    /**
     * [Refresh Token 삭제 함수 (Delete)]
     * - 로그아웃, 재발급 시 사용
     */
    public void deleteHashedRefreshToken(String hashedRefreshToken) {
        String key = "RT:" + hashedRefreshToken;
        if (!redisTemplate.hasKey(key)) {
            return;
        }
        redisTemplate.delete(key);
        log.debug("[Redis] HashedRefreshToken이 Redis에서 삭제되었습니다. {}", hashedRefreshToken);
    }

    /**
     * Refresh Token을 원자적으로 회전시키는 함수
     * - 기존 Refresh Token 키가 존재할 때만 기존 키를 삭제하고 새 Refresh Token 키를 저장한다.
     * - Redis Lua Script를 사용하여 조회, 삭제, 저장 과정을 원자적으로 처리한다.
     * @param oldHashedToken : 기존 해시 Refresh Token
     * @param newHashedToken : 새 해시 Refresh Token
     * @param newValue : 새 Refresh Token에 저장할 Redis 값
     * @param newTtlMillis : 새 Refresh Token 만료 시간(밀리초)
     * @return : 회전 성공 시 true, 기존 토큰이 이미 삭제된 경우 false
     */
    public boolean rotateRefreshToken(String oldHashedToken, String newHashedToken, RedisValue newValue, long newTtlMillis) {
        log.info("[Redis] Refresh Token 회전 처리 START - oldHashedToken: {}, newHashedToken: {}", oldHashedToken, newHashedToken);

        try {
            String oldKey = "RT:" + oldHashedToken;
            String newKey = "RT:" + newHashedToken;
            String jsonValue = objectMapper.writeValueAsString(newValue);

            String script = "if redis.call('EXISTS', KEYS[1]) == 1 then " +
                    "redis.call('DEL', KEYS[1]) " +
                    "redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[2]) " +
                    "return 1 end return 0";

            DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
            redisScript.setScriptText(script);
            redisScript.setResultType(Long.class);

            Long result = redisTemplate.execute(
                    redisScript,
                    List.of(oldKey, newKey),
                    jsonValue,
                    String.valueOf(newTtlMillis)
            );

            boolean rotated = result != null && result == 1L;
            log.debug("[Redis] Refresh Token 회전 처리 END - result: {}", rotated);
            return rotated;
        } catch (JsonProcessingException e) {
            log.error("Redis Value JSON 변환 오류: {}", e.getMessage());
            throw new RuntimeException("Redis Save Error", e);
        }
    }

    /**
     * [해당 Refresh Token 존재 여부를 확인하는 함수]
     */
    public boolean hasHashedRefreshToken(String hashedRefreshToken) {
        return redisTemplate.hasKey("RT:" + hashedRefreshToken);
    }

    // ==========================================================
    // [ BlackList ]
    // ==========================================================

    /**
     * [해당 토큰의 JTI를 Redis 블랙리스트에 등록하는 함수]
     * - Key: BL:{jti}
     * - Value: TokenType
     * - TTL: 해당 토큰 만료까지 남은 시간
     */
    public void setTokenInBlacklist(String token) {
        try {
            String key = "BL:" + jwtUtil.getJtiFromToken(token);
            String value = jwtUtil.getTokenType(token).name();
            long ttl = jwtUtil.getExpirationInMs(token) - System.currentTimeMillis();

            if (ttl <= 0) {
                log.debug("[Redis] 이미 만료된 Token의 블랙리스트 등록 요청 - jti: {}", key);
                return;
            }

            if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
                return;
            }

            redisTemplate.opsForValue().set(key, value, Duration.ofMillis(ttl));
            log.info("[Redis] Token Redis BlackList 등록 완료: {}, TTL - {}ms", key, ttl);
        } catch (MalformedJwtException | UnsupportedJwtException | IllegalArgumentException e) {
            log.warn("[Redis] 잘못된 형식의 토큰 블랙리스트 등록 시도: {}", e.getMessage());
        } catch (Exception e) {
            log.error("[Redis] 블랙리스트 등록 중 시스템 오류 발생: {}", e.getMessage());
        }
    }

    /**
     * [해당 Token이 Redis 블랙리스트에 존재하는지 확인하는 함수]
     */
    public Boolean hasTokenInBlacklist(String token) {
        return redisTemplate.hasKey("BL:" + jwtUtil.getJtiFromToken(token));
    }

    // ==========================================================
    // [ CHZZK Auth State ]
    // ==========================================================

    /**
     * 치지직 Auth state를 Redis에 저장하는 함수
     * - Key: CHZZK:STATE:{state}
     * - Value: VALID
     * - TTL: callback 검증 가능 시간
     * @param state : 저장할 state 값
     * @param durationInMillis : TTL(ms)
     */
    public void setChzzkAuthState(String state, long durationInMillis) {
        String key = "CHZZK:STATE:" + state;
        redisTemplate.opsForValue().set(key, "VALID", Duration.ofMillis(durationInMillis));
    }

    /**
     * 치지직 Auth state 존재 여부를 확인하는 함수
     * @param state : 조회할 state 값
     * @return : 존재 여부
     */
    public boolean hasChzzkAuthState(String state) {
        return Boolean.TRUE.equals(redisTemplate.hasKey("CHZZK:STATE:" + state));
    }
}
