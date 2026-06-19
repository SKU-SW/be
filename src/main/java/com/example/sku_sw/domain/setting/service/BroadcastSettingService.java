package com.example.sku_sw.domain.setting.service;

import com.example.sku_sw.domain.setting.dto.AiProactiveUpdateReqDto;
import com.example.sku_sw.domain.setting.dto.BroadcastSettingResDto;
import com.example.sku_sw.domain.setting.entity.BroadcastSetting;
import com.example.sku_sw.domain.setting.enums.SettingErrorCode;
import com.example.sku_sw.domain.setting.mapper.BroadcastSettingMapper;
import com.example.sku_sw.domain.setting.repository.BroadcastSettingRepository;
import com.example.sku_sw.domain.broadcast.enums.BroadcastStatus;
import com.example.sku_sw.domain.broadcast.repository.BroadcastRepository;
import com.example.sku_sw.domain.broadcast.service.BroadcastProactiveChatService;
import com.example.sku_sw.domain.broadcast.util.BroadcastRedisUtil;
import com.example.sku_sw.domain.user.entity.User;
import com.example.sku_sw.domain.user.repository.UserRepository;
import com.example.sku_sw.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
@RequiredArgsConstructor
public class BroadcastSettingService {

    private final BroadcastSettingRepository broadcastSettingRepository;
    private final UserRepository userRepository;
    private final BroadcastSettingMapper broadcastSettingMapper;
    private final BroadcastRepository broadcastRepository;
    private final BroadcastRedisUtil broadcastRedisUtil;
    private final BroadcastProactiveChatService proactiveChatService;

    /**
     * 사용자의 방송 설정을 조회한다.
     * - userId에 해당하는 BroadcastSetting이 존재하지 않으면 예외를 발생시킨다.
     *
     * @param userId : 조회할 사용자 ID
     * @return : BroadcastSettingResDto
     */
    @Transactional(readOnly = true)
    public BroadcastSettingResDto getBroadcastSetting(Long userId) {
        log.info("[BroadcastSettingService] getBroadcastSetting() - START | userId: {}", userId);

        /*
            1. 방송 설정 단건 조회
            - userId로 방송 설정을 조회하고, 존재하지 않으면 BROADCAST_SETTING_NOT_FOUND 예외를 발생시킨다.
        */
        BroadcastSetting broadcastSetting = broadcastSettingRepository.findByUserId(userId)
                .orElseThrow(() -> new CustomException(SettingErrorCode.BROADCAST_SETTING_NOT_FOUND));

        /*
            2. ResponseDto Mapping
            - 조회한 Entity를 Mapper를 사용해서 Dto로 매핑한다.
        */
        BroadcastSettingResDto result = broadcastSettingMapper.toBroadcastSettingResDto(broadcastSetting);

        log.info("[BroadcastSettingService] getBroadcastSetting() - END | userId: {}, aiProactiveToChat: {}", userId, result.aiProactiveToChat());
        return result;
    }

    /**
     * 사용자의 AI 채팅 선제 반응 설정을 멱등하게 수정한다.
     * - 요청받은 값으로 aiProactiveToChat을 설정한다 (같은 값 반복 호출해도 동일 결과).
     * - userId에 해당하는 BroadcastSetting이 존재하지 않으면 예외를 발생시킨다.
     *
     * @param userId : 설정을 수정할 사용자 ID
     * @param reqDto : AI 선제 반응 설정 요청 DTO
     * @return : BroadcastSettingResDto
     */
    @Transactional
    public BroadcastSettingResDto updateAiProactiveToChat(Long userId, AiProactiveUpdateReqDto reqDto) {
        log.info("[BroadcastSettingService] updateAiProactiveToChat() - START | userId: {}, aiProactiveToChat: {}", userId, reqDto.aiProactiveToChat());

        /*
            1. 방송 설정 단건 조회
            - userId로 방송 설정을 조회하고, 존재하지 않으면 BROADCAST_SETTING_NOT_FOUND 예외를 발생시킨다.
        */
        BroadcastSetting broadcastSetting = broadcastSettingRepository.findByUserId(userId)
                .orElseThrow(() -> new CustomException(SettingErrorCode.BROADCAST_SETTING_NOT_FOUND));

        /*
            2. AI 선제 반응 설정 수정
            - 요청받은 값으로 aiProactiveToChat을 멱등하게 수정한다.
        */
        broadcastSetting.updateAiProactiveToChat(reqDto.aiProactiveToChat());
        registerActiveBroadcastSettingSyncAfterCommit(userId, reqDto.aiProactiveToChat());

        /*
            3. ResponseDto Mapping
            - 수정된 Entity를 Mapper를 사용해서 Dto로 매핑한다.
        */
        BroadcastSettingResDto result = broadcastSettingMapper.toBroadcastSettingResDto(broadcastSetting);

        log.info("[BroadcastSettingService] updateAiProactiveToChat() - END | userId: {}, aiProactiveToChat: {}", userId, result.aiProactiveToChat());
        return result;
    }

    private void registerActiveBroadcastSettingSyncAfterCommit(Long userId, boolean enabled) {
        Runnable sync = () -> broadcastRepository.findActiveByUserId(userId, BroadcastStatus.BROADCASTING)
                .ifPresent(broadcast -> {
                    try {
                        broadcastRedisUtil.updateBroadcastUserAiProactiveToChat(broadcast.getStreamId(), enabled);
                        if (!enabled) {
                            proactiveChatService.cancel(broadcast.getStreamId());
                        }
                    } catch (RuntimeException e) {
                        log.warn("[BroadcastSettingService] Active broadcast Redis sync failed | userId: {}, streamId: {}, error: {}",
                                userId, broadcast.getStreamId(), e.getMessage());
                    }
                });

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            sync.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sync.run();
            }
        });
    }

    /**
     * 사용자의 방송 설정을 초기값으로 초기화한다.
     * - BroadcastSetting이 존재하면 초기화하고, 존재하지 않으면 새로 생성한다.
     * - 초기값: aiProactiveToChat = true
     *
     * @param userId : 설정을 초기화할 사용자 ID
     * @return : BroadcastSettingResDto
     */
    @Transactional
    public BroadcastSettingResDto initBroadcastSetting(Long userId) {
        log.info("[BroadcastSettingService] initBroadcastSetting() - START | userId: {}", userId);

        /*
            1. 방송 설정 조회 또는 신규 생성
            - userId로 방송 설정을 조회한다.
            - 존재하면 init()으로 초기화하고, 존재하지 않으면 새로 생성하여 저장한다.
        */
        BroadcastSetting broadcastSetting = broadcastSettingRepository.findByUserId(userId)
                .map(existing -> {
                    existing.init();
                    return existing;
                })
                .orElseGet(() -> {
                    User user = userRepository.findById(userId)
                            .orElseThrow(() -> new CustomException(SettingErrorCode.BROADCAST_SETTING_NOT_FOUND));
                    BroadcastSetting newSetting = BroadcastSetting.builder()
                            .user(user)
                            .build();
                    return broadcastSettingRepository.save(newSetting);
                });
        registerActiveBroadcastSettingSyncAfterCommit(userId, true);

        /*
            2. ResponseDto Mapping
            - Entity를 Mapper를 사용해서 Dto로 매핑한다.
        */
        BroadcastSettingResDto result = broadcastSettingMapper.toBroadcastSettingResDto(broadcastSetting);

        log.info("[BroadcastSettingService] initBroadcastSetting() - END | userId: {}, aiProactiveToChat: {}", userId, result.aiProactiveToChat());
        return result;
    }
}
