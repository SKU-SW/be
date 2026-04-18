package com.example.sku_sw.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "이메일 회원가입 요청 DTO")
public record AuthRegisterEmailReqDto(
        @Schema(description = "사용자 이름", example = "나형준")
        @NotBlank
        @Size(max = 10, message = "이름은 최대 10글자까지 입력 가능합니다.")
        String name,

        @Schema(description = "이메일", example = "name@example.com")
        @NotBlank
        @Email
        String email,

        @Schema(description = "비밀번호", example = "password123!")
        @NotBlank
        @Size(min = 8, max = 12, message = "비밀번호는 8 ~ 12글자 사이여야 합니다.")
        String password,

        @Schema(description = "비밀번호 확인", example = "password123!")
        @NotBlank
        @Size(min = 8, max = 12, message = "비밀번호는 8 ~ 12글자 사이여야 합니다.")
        String passwordConfirm
) {
}
