package com.example.sku_sw.domain.setting.repository;

import com.example.sku_sw.domain.setting.entity.BroadcastSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BroadcastSettingRepository extends JpaRepository<BroadcastSetting, Long> {

    /**
     * 사용자 ID로 방송 설정 단건 조회
     * @param userId : 조회할 사용자 ID
     * @return : Optional<BroadcastSetting>
     */
    Optional<BroadcastSetting> findByUserId(Long userId);
}
