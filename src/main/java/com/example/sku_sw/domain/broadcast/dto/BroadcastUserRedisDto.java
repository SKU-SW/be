package com.example.sku_sw.domain.broadcast.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 방송 사용자 Redis 상태 DTO.
 * - aiProactiveToChat: 시청자 채팅에 대한 Gemini 선제 반응 허용 여부
 * - isStreamerSilent: 스트리머의 현재 무음 상태 여부
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BroadcastUserRedisDto {
    private String sessionKey;
    private String channelId;
    private String channelName;
    private Boolean aiProactiveToChat;
    private Boolean isStreamerSilent;

    /**
     * AI 선제 채팅 반응 설정의 유효값을 반환한다.
     * - Redis 값이 null이면 기존 기본 정책에 맞춰 true로 해석한다.
     *
     * @return : AI 선제 채팅 반응 활성화 여부
     */
    public boolean isAiProactiveToChatEnabled() {
        return aiProactiveToChat == null || aiProactiveToChat;
    }

    /**
     * 스트리머 무음 상태의 유효값을 반환한다.
     * - Redis 값이 null이면 안전하게 false로 해석한다.
     *
     * @return : 스트리머 무음 여부
     */
    public boolean isStreamerSilentNow() {
        return Boolean.TRUE.equals(isStreamerSilent);
    }
}
