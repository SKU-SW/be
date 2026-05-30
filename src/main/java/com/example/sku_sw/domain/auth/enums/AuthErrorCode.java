package com.example.sku_sw.domain.auth.enums;

import com.example.sku_sw.global.exception.model.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements BaseErrorCode {
    // 400 BAD_REQUEST
    PASSWORD_CONFIRM_MISMATCH(HttpStatus.BAD_REQUEST, "비밀번호와 비밀번호 확인이 일치하지 않습니다."),
    CHZZK_AUTH_STATE_INVALID(HttpStatus.BAD_REQUEST, "유효하지 않은 치지직 인증 요청입니다."),
    CHZZK_AUTH_TOKEN_RESPONSE_INVALID(HttpStatus.BAD_REQUEST, "치지직 토큰 응답이 올바르지 않습니다."),

    // 401 UNAUTHORIZED
    INVALID_LOGIN_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 Refresh Token입니다."),
    REFRESH_TOKEN_REUSE(HttpStatus.UNAUTHORIZED, "Refresh Token이 이미 사용되었습니다."),
    CHZZK_AUTH_TOKEN_REQUEST_FAILED(HttpStatus.UNAUTHORIZED, "치지직 토큰 발급에 실패했습니다."),

    // 409 CONFLICT
    ALREADY_EXIST_EMAIL(HttpStatus.CONFLICT, "이미 가입한 이메일입니다.");

    private final HttpStatus status;
    private final String message;
}
