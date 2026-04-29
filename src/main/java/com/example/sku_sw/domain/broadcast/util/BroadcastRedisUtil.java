package com.example.sku_sw.domain.broadcast.util;

import com.example.sku_sw.domain.broadcast.dto.BroadcastCharacterRedisDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Component;

/**
 * 방송 관련 Redis 저장/삭제를 수행하는 Util 함수
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BroadcastRedisUtil {
    private static final String BROADCAST_KEY_PREFIX = "BroadcastCharacter:";

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
        String key = BROADCAST_KEY_PREFIX + broadcastStreamId;
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
        String key = BROADCAST_KEY_PREFIX + broadcastStreamId;
        redisTemplate.delete(key);
        log.debug("[BroadcastRedisUtil] 방송 캐릭터 정보가 Redis에서 삭제되었습니다. streamId: {}", broadcastStreamId);
    }

    /**
     * Redis에 방송 캐릭터 정보가 존재하는지 확인하는 함수
     * @param broadcastStreamId : 확인할 방송 스트림 ID
     * @return : 키 존재 여부
     */
    public boolean hasBroadcastCharacterValue(String broadcastStreamId) {
        String key = BROADCAST_KEY_PREFIX + broadcastStreamId;
        Boolean hasKey = redisTemplate.hasKey(key);
        return Boolean.TRUE.equals(hasKey);
    }

    /**
     * Redis에서 방송 캐릭터 정보 JSON 문자열을 조회하는 함수
     * @param broadcastStreamId : 조회할 방송 스트림 ID
     * @return : 방송 캐릭터 정보 JSON 문자열 (없으면 null)
     */
    public String getBroadcastCharacterValue(String broadcastStreamId) {
        String key = BROADCAST_KEY_PREFIX + broadcastStreamId;
        ValueOperations<String, String> values = redisTemplate.opsForValue();
        return values.get(key);
    }

    /**
     * Redis에 방송 캐릭터 정보 JSON 문자열을 직접 저장하는 함수 (백업 복구용)
     * @param broadcastStreamId : 저장할 방송 스트림 ID
     * @param jsonValue : 저장할 JSON 문자열
     */
    public void setBroadcastCharacterValueRaw(String broadcastStreamId, String jsonValue) {
        String key = BROADCAST_KEY_PREFIX + broadcastStreamId;
        ValueOperations<String, String> values = redisTemplate.opsForValue();
        values.set(key, jsonValue);
    }
}
