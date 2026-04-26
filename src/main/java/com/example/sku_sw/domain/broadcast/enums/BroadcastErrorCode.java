package com.example.sku_sw.domain.broadcast.enums;

import com.example.sku_sw.global.exception.model.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum BroadcastErrorCode implements BaseErrorCode {

    BROADCAST_CHARACTER_NOT_SELECTED(HttpStatus.BAD_REQUEST, "선택되지 않은 캐릭터가 방송 시작을 시도했습니다."),
    CHARACTER_ALREADY_BROADCASTING(HttpStatus.BAD_REQUEST, "이미 해당 캐릭터가 방송을 진행 중입니다."),
    STREAM_ID_GENERATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "방송 스트림 ID 생성에 실패했습니다.");

    private final HttpStatus httpStatus;
    private final String message;

    @Override
    public HttpStatus getStatus() {
        return this.httpStatus;
    }
}
