package com.example.sku_sw.domain.broadcast.util;

import com.example.sku_sw.domain.broadcast.dto.BroadcastCharacterRedisDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastInfoRedisDto;
import com.example.sku_sw.domain.broadcast.enums.BroadcastErrorCode;
import com.example.sku_sw.global.exception.CustomException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 방송 관련 Redis 저장/삭제를 수행하는 Util 함수
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BroadcastRedisUtil {
    private static final String BROADCAST_CHARACTER_KEY_PREFIX = "BroadcastCharacter:";
    private static final String BROADCAST_INFO_KEY_PREFIX = "BroadcastInfo:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 방송 캐릭터 정보를 Redis에 저장하는 함수
     * - Key: BroadcastCharacter:{broadcastStreamId}
     * - Value: 방송 캐릭터 정보 JSON
     * @param broadcastStreamId : 방송 스트림 ID
     * @param value : 저장할 방송 캐릭터 정보
     */
    public void setBroadcastCharacterValue(String broadcastStreamId, BroadcastCharacterRedisDto value) {
        String key = BROADCAST_CHARACTER_KEY_PREFIX + broadcastStreamId;
        try {
            String jsonValue = objectMapper.writeValueAsString(value);
            ValueOperations<String, String> values = redisTemplate.opsForValue();
            values.set(key, jsonValue);
        } catch (JsonProcessingException e) {
            log.error("Broadcast Redis Value JSON 변환 오류: {}", e.getMessage());
            throw new RuntimeException("Broadcast Redis Save Error", e);
        }
    }

    /**
     * 방송 캐릭터 정보를 Redis에서 삭제하는 함수
     * @param broadcastStreamId : 삭제할 방송 스트림 ID
     */
    public void deleteBroadcastCharacterValue(String broadcastStreamId) {
        String key = BROADCAST_CHARACTER_KEY_PREFIX + broadcastStreamId;
        redisTemplate.delete(key);
        log.debug("[BroadcastRedisUtil] 방송 캐릭터 정보가 Redis에서 삭제되었습니다. streamId: {}", broadcastStreamId);
    }

    /**
     * Redis에 방송 캐릭터 정보가 존재하는지 확인하는 함수
     * @param broadcastStreamId : 확인할 방송 스트림 ID
     * @return : 키 존재 여부
     */
    public boolean hasBroadcastCharacterValue(String broadcastStreamId) {
        String key = BROADCAST_CHARACTER_KEY_PREFIX + broadcastStreamId;
        Boolean hasKey = redisTemplate.hasKey(key);
        return Boolean.TRUE.equals(hasKey);
    }

    /**
     * Redis에서 방송 캐릭터 정보 JSON 문자열을 조회하는 함수
     * @param broadcastStreamId : 조회할 방송 스트림 ID
     * @return : 방송 캐릭터 정보 JSON 문자열 (없으면 null)
     */
    public String getBroadcastCharacterValue(String broadcastStreamId) {
        String key = BROADCAST_CHARACTER_KEY_PREFIX + broadcastStreamId;
        ValueOperations<String, String> values = redisTemplate.opsForValue();
        return values.get(key);
    }

    /**
     * Redis에 방송 캐릭터 정보 JSON 문자열을 직접 저장하는 함수 (백업 복구용)
     * @param broadcastStreamId : 저장할 방송 스트림 ID
     * @param jsonValue : 저장할 JSON 문자열
     */
    public void setBroadcastCharacterValueRaw(String broadcastStreamId, String jsonValue) {
        String key = BROADCAST_CHARACTER_KEY_PREFIX + broadcastStreamId;
        ValueOperations<String, String> values = redisTemplate.opsForValue();
        values.set(key, jsonValue);
    }

    /**
     * Redis에서 방송 캐릭터 정보 DTO를 조회하는 함수
     * - BroadcastCharacter:{broadcastStreamId} key의 JSON을 파싱하여 DTO로 반환한다.
     * @param broadcastStreamId : 조회할 방송 스트림 ID
     * @return : 방송 캐릭터 정보 DTO
     * @throws CustomException : Redis에 데이터가 없는 경우 BROADCAST_CHARACTER_REDIS_NOT_FOUND
     */
    public BroadcastCharacterRedisDto getBroadcastCharacterDto(String broadcastStreamId) {
        String json = getBroadcastCharacterValue(broadcastStreamId);
        if (json == null) {
            log.warn("[BroadcastRedisUtil] Broadcast character not found in Redis | streamId: {}", broadcastStreamId);
            throw new CustomException(BroadcastErrorCode.BROADCAST_CHARACTER_REDIS_NOT_FOUND);
        }
        try {
            return objectMapper.readValue(json, BroadcastCharacterRedisDto.class);
        } catch (JsonProcessingException e) {
            log.error("[BroadcastRedisUtil] Broadcast character JSON parsing error | streamId: {}, error: {}", broadcastStreamId, e.getMessage());
            throw new RuntimeException("Broadcast Redis Parse Error", e);
        }
    }

    /**
     * Redis에 저장된 방송 캐릭터의 isTalking 필드를 갱신하는 함수
     * - 기존 DTO를 조회하여 isTalking 값만 변경한 뒤 Redis에 다시 저장한다.
     * @param broadcastStreamId : 갱신할 방송 스트림 ID
     * @param isTalking : 설정할 isTalking 값
     */
    public void updateBroadcastCharacterIsTalking(String broadcastStreamId, boolean isTalking) {
        BroadcastCharacterRedisDto existing = getBroadcastCharacterDto(broadcastStreamId);
        existing.setIsTalking(isTalking);
        setBroadcastCharacterValue(broadcastStreamId, existing);
        log.debug("[BroadcastRedisUtil] updateBroadcastCharacterIsTalking() - Updated | streamId: {}, isTalking: {}", broadcastStreamId, isTalking);
    }

    /**
     * 방송 대화 내역을 Redis List에 추가하는 함수
     * - Key: BroadcastInfo:{broadcastStreamId}
     * - Value: BroadcastInfoRedisDto JSON (Right Push)
     * @param broadcastStreamId : 방송 스트림 ID
     * @param value : 저장할 방송 대화 정보
     */
    public void pushBroadcastInfo(String broadcastStreamId, BroadcastInfoRedisDto value) {
        String key = BROADCAST_INFO_KEY_PREFIX + broadcastStreamId;
        try {
            String jsonValue = objectMapper.writeValueAsString(value);
            redisTemplate.opsForList().rightPush(key, jsonValue);
            log.debug("[BroadcastRedisUtil] pushBroadcastInfo() - Pushed | streamId: {}, role: {}", broadcastStreamId, value.role());
        } catch (JsonProcessingException e) {
            log.error("[BroadcastRedisUtil] Broadcast Info JSON 변환 오류 | streamId: {}, error: {}", broadcastStreamId, e.getMessage());
            throw new RuntimeException("Broadcast Redis Save Error", e);
        }
    }

    /**
     * Redis List에서 최근 방송 대화 내역을 조회하는 함수
     * - Key: BroadcastInfo:{broadcastStreamId}
     * - 가장 오래된 순서(0번 인덱스)부터 최신 순으로 조회한다.
     * @param broadcastStreamId : 방송 스트림 ID
     * @param limit : 조회할 최대 개수
     * @return : 방송 대화 정보 DTO 목록
     */
    public List<BroadcastInfoRedisDto> getRecentBroadcastInfos(String broadcastStreamId, int limit) {
        String key = BROADCAST_INFO_KEY_PREFIX + broadcastStreamId;
        List<String> jsonList = redisTemplate.opsForList().range(key, -limit, -1);
        if (jsonList == null || jsonList.isEmpty()) {
            return List.of();
        }
        return jsonList.stream().map(json -> {
            try {
                return objectMapper.readValue(json, BroadcastInfoRedisDto.class);
            } catch (JsonProcessingException e) {
                log.error("[BroadcastRedisUtil] Broadcast Info JSON 파싱 오류 | streamId: {}, error: {}", broadcastStreamId, e.getMessage());
                throw new RuntimeException("Broadcast Redis Parse Error", e);
            }
        }).toList();
    }

    /**
     * 방송 대화 내역 Redis List를 삭제하는 함수
     * @param broadcastStreamId : 삭제할 방송 스트림 ID
     */
    public void deleteBroadcastInfo(String broadcastStreamId) {
        String key = BROADCAST_INFO_KEY_PREFIX + broadcastStreamId;
        redisTemplate.delete(key);
        log.debug("[BroadcastRedisUtil] deleteBroadcastInfo() - Deleted | streamId: {}", broadcastStreamId);
    }
}
