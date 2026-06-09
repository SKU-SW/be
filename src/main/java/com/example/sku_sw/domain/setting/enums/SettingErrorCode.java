package com.example.sku_sw.domain.setting.enums;

import com.example.sku_sw.global.exception.model.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum SettingErrorCode implements BaseErrorCode {

    BROADCAST_SETTING_NOT_FOUND(HttpStatus.NOT_FOUND, "방송 설정을 찾을 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String message;

    @Override
    public HttpStatus getStatus() {
        return this.httpStatus;
    }
}
