package com.example.sku_sw.domain.broadcast.dto;

import lombok.Builder;
import java.util.List;

@Builder
public record BroadcastMonthResDto(
        List<BroadcastMonthInfoResDto> broadcastMonthInfoList,
        int broadcastYear,
        int broadcastMonth
) {
    public static BroadcastMonthResDto create(
            List<BroadcastMonthInfoResDto> broadcastMonthInfoList,
            int broadcastYear,
            int broadcastMonth){
        return BroadcastMonthResDto.builder()
                .broadcastMonthInfoList(broadcastMonthInfoList)
                .broadcastYear(broadcastYear)
                .broadcastMonth(broadcastMonth)
                .build();
    }
}
