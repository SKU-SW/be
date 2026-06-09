package com.example.sku_sw.domain.setting.dto;

import lombok.Builder;

@Builder
public record BroadcastSettingResDto(
        boolean aiProactiveToChat
) {}
