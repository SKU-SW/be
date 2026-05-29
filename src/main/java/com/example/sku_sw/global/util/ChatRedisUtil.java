package com.example.sku_sw.global.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Chat Pub/Sub Redis 관련 유틸 클래스
 */
@Slf4j
@Component
public class ChatRedisUtil {
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public ChatRedisUtil(
            @Qualifier("chatStringRedisTemplate") StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 문자열 메시지를 Chat Redis 채널로 발행하는 함수
     * @param channel : 발행할 채널명
     * @param message : 발행할 메시지
     */
    public void publish(String channel, String message) {
        redisTemplate.convertAndSend(channel, message);
        log.debug("[ChatRedisUtil] 메시지를 발행했습니다. channel: {}", channel);
    }

    /**
     * 객체 메시지를 JSON으로 직렬화해 Chat Redis 채널로 발행하는 함수
     * @param channel : 발행할 채널명
     * @param payload : 발행할 객체
     */
    public void publish(String channel, Object payload) {
        try {
            publish(channel, objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            log.error("[ChatRedisUtil] 메시지 JSON 직렬화 중 오류가 발생했습니다. channel: {}, error: {}", channel, e.getMessage());
            throw new RuntimeException("Chat Redis Publish Error", e);
        }
    }
}
