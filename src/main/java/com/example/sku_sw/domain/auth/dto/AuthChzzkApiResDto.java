package com.example.sku_sw.domain.auth.dto;

/**
 * 치지직 Open API 공통 응답 DTO
 * @param code : 치지직 응답 코드
 * @param message : 치지직 응답 메시지
 * @param content : 실제 응답 데이터
 * @param <T> : 실제 응답 데이터 타입
 */
public record AuthChzzkApiResDto<T>(
        Integer code,
        String message,
        T content
) {
}
