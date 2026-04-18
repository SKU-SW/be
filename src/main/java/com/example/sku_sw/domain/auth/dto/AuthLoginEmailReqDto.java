package com.example.sku_sw.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "이메일 로그인 요청 DTO")
public record AuthLoginEmailReqDto(
        @Schema(description = "이메일", example = "name@example.com")
        @NotBlank
        @Email
        String email,

        @Schema(description = "비밀번호", example = "password123!")
        @NotBlank
        @Size(min = 8, max = 12, message = "비밀번호는 8 ~ 12글자 사이여야 합니다.")
        String password
) {
}
