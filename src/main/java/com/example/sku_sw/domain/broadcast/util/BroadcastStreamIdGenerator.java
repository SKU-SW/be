package com.example.sku_sw.domain.broadcast.util;

import com.example.sku_sw.domain.broadcast.enums.BroadcastErrorCode;
import com.example.sku_sw.domain.broadcast.repository.BroadcastRepository;
import com.example.sku_sw.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * 방송 스트림 ID 생성기
 * - 16자리 영문 대소문자 + 숫자로 구성된 고유한 streamId를 생성
 * - 중복 시 재생성 (최대 10회 시도)
 */
@Component
@RequiredArgsConstructor
public class BroadcastStreamIdGenerator {

    private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int STREAM_ID_LENGTH = 16;
    private static final int MAX_RETRY = 10;

    private final BroadcastRepository broadcastRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 고유한 16자리 streamId를 생성
     * - 중복이 확인되면 최대 10회까지 재생성 시도
     * - 모든 시도에서 중복이 발생하면 STREAM_ID_GENERATION_FAILED 예외 발생
     *
     * @return : 고유한 16자리 영숫자 streamId
     */
    public String generate() {
        for (int i = 0; i < MAX_RETRY; i++) {
            String streamId = generateRandomStreamId();
            if (!broadcastRepository.existsByStreamId(streamId)) {
                return streamId;
            }
        }
        throw new CustomException(BroadcastErrorCode.STREAM_ID_GENERATION_FAILED);
    }

    /**
     * 16자리 랜덤 영숫자 문자열 생성
     *
     * @return : 16자리 랜덤 문자열
     */
    private String generateRandomStreamId() {
        StringBuilder sb = new StringBuilder(STREAM_ID_LENGTH);
        for (int i = 0; i < STREAM_ID_LENGTH; i++) {
            sb.append(CHARACTERS.charAt(secureRandom.nextInt(CHARACTERS.length())));
        }
        return sb.toString();
    }
}
