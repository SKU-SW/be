package com.example.sku_sw.domain.setting.dto;

import jakarta.validation.constraints.NotNull;

public record AiProactiveUpdateReqDto(
        @NotNull Boolean aiProactiveToChat
) {}
