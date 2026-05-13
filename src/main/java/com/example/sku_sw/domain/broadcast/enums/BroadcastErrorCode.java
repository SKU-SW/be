package com.example.sku_sw.domain.broadcast.enums;

import com.example.sku_sw.global.exception.model.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum BroadcastErrorCode implements BaseErrorCode {
    TOKEN_AUTHORIZATION_FAILED(HttpStatus.BAD_REQUEST, "토큰 인증에 실패했습니다."),
    BROADCAST_CHARACTER_NOT_SELECTED(HttpStatus.BAD_REQUEST, "선택되지 않은 캐릭터가 방송 시작을 시도했습니다."),
    CHARACTER_ALREADY_BROADCASTING(HttpStatus.BAD_REQUEST, "이미 해당 캐릭터가 방송을 진행 중입니다."),
    NEED_BROADCAST_STREAM_ID(HttpStatus.BAD_REQUEST, "방송 고유 ID가 필요합니다"),
    BROADCAST_USER_UNMATCH(HttpStatus.BAD_REQUEST, "해당 사용자가 진행 중인 방송이 아닙니다."),
    BROADCAST_NOT_ACTIVE(HttpStatus.BAD_REQUEST, "활성 상태의 방송이 아닙니다."),
    STREAM_ID_GENERATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "방송 스트림 ID 생성에 실패했습니다."),
    BROADCAST_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 방송입니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 사용자입니다."),
    ACTIVE_BROADCAST_NOT_FOUND(HttpStatus.NOT_FOUND, "진행 중인 방송이 없습니다."),
    INVALID_DIALOGUE_FILTER(HttpStatus.BAD_REQUEST, "최소 하나 이상의 대화 주체 필터가 필요합니다."),
    WEBSOCKET_CONNECTION_NOT_FOUND(HttpStatus.NOT_FOUND, "WebSocket 연결이 존재하지 않습니다."),
    BROADCAST_CHARACTER_REDIS_NOT_FOUND(HttpStatus.NOT_FOUND, "방송 캐릭터 정보를 Redis에서 찾을 수 없습니다."),
    WEBSOCKET_MESSAGE_PARSE_FAILED(HttpStatus.BAD_REQUEST, "WebSocket 메시지 파싱에 실패했습니다."),
    GEMINI_RESPONSE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "Gemini API 응답 처리에 실패했습니다.");

    private final HttpStatus httpStatus;
    private final String message;

    @Override
    public HttpStatus getStatus() {
        return this.httpStatus;
    }
}
