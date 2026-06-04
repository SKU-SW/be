package com.example.sku_sw.domain.character.enums;

import com.example.sku_sw.global.exception.model.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum CharacterErrorCode implements BaseErrorCode {
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    CHARACTER_NOT_FOUND(HttpStatus.NOT_FOUND, "캐릭터를 찾을 수 없습니다."),
    CHARACTER_IMAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "캐릭터 이미지를 찾을 수 없습니다."),
    CHARACTER_IMAGE_TYPE_GENDER_MISMATCH(HttpStatus.BAD_REQUEST, "캐릭터 성별과 캐릭터 이미지의 성별이 일치하지 않습니다."),
    TRIGGER_WORD_DUPLICATE(HttpStatus.BAD_REQUEST, "호출어에 중복이 있습니다."),
    TRIGGER_WORD_EXCEED_MAX(HttpStatus.BAD_REQUEST, "호출어는 최대 3개까지 설정 가능합니다."),
    TRIGGER_WORD_EMPTY(HttpStatus.BAD_REQUEST, "호출어는 최소 1개 이상 설정해야 합니다."),
    CHARACTER_NAME_EMPTY(HttpStatus.BAD_REQUEST, "캐릭터 이름은 필수입니다."),
    CHARACTER_NOT_OWNER(HttpStatus.FORBIDDEN, "해당 캐릭터의 소유자가 아닙니다."),
    CONCURRENT_SELECTION_CONFLICT(HttpStatus.CONFLICT, "동시에 선택 요청이 발생했습니다. 다시 시도해주세요."),
    NO_CHARACTERS_FOR_DESELECT(HttpStatus.BAD_REQUEST, "선택을 취소할 캐릭터가 선택되어있지 않습니다."),
    CHARACTER_NOT_SELECTED(HttpStatus.BAD_REQUEST, "해당 캐릭터는 선택되지 않은 상태입니다.");

    private final HttpStatus httpStatus;
    private final String message;

    @Override
    public HttpStatus getStatus() {
        return this.httpStatus;
    }
}
