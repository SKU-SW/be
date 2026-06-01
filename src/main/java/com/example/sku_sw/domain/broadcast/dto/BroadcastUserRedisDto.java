package com.example.sku_sw.domain.broadcast.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BroadcastUserRedisDto {
    private String sessionKey;
    private String channelId;
    private String channelName;
}
