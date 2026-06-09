package com.example.sku_sw.domain.setting.mapper;

import com.example.sku_sw.domain.setting.dto.BroadcastSettingResDto;
import com.example.sku_sw.domain.setting.entity.BroadcastSetting;
import org.springframework.stereotype.Component;

@Component
public class BroadcastSettingMapper {

    /**
     * BroadcastSetting Entity -> BroadcastSettingResDto 변환
     * @param broadcastSetting : 변환할 BroadcastSetting Entity
     * @return : 변환된 BroadcastSettingResDto
     */
    public BroadcastSettingResDto toBroadcastSettingResDto(BroadcastSetting broadcastSetting) {
        return BroadcastSettingResDto.builder()
                .aiProactiveToChat(broadcastSetting.isAiProactiveToChat())
                .build();
    }
}
