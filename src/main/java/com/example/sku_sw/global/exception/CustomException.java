package com.example.sku_sw.global.exception;

import com.example.sku_sw.global.exception.model.BaseErrorCode;
import lombok.Getter;

@Getter
public class CustomException extends RuntimeException{
    private final BaseErrorCode errorCode;
    public CustomException(BaseErrorCode errorCode){
        // RuntimeException의 내부 message 필드를 채워주기 위해 super 함수 호출
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
