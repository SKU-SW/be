package com.example.sku_sw.domain.broadcast.exception;

import com.example.sku_sw.domain.auth.dto.AuthChzzkAuthUrlResDto;
import com.example.sku_sw.domain.broadcast.enums.BroadcastErrorCode;
import com.example.sku_sw.global.exception.CustomException;
import lombok.Getter;

@Getter
public class ChzzkReauthRequiredException extends CustomException {

    private final AuthChzzkAuthUrlResDto authUrlResDto;

    public ChzzkReauthRequiredException(AuthChzzkAuthUrlResDto authUrlResDto) {
        super(BroadcastErrorCode.CHZZK_AUTH_REAUTH_REQUIRED);
        this.authUrlResDto = authUrlResDto;
    }
}
