package com.example.sku_sw.domain.broadcast.service;

import com.example.sku_sw.domain.broadcast.dto.BroadcastUserRedisDto;
import com.example.sku_sw.domain.broadcast.enums.BroadcastErrorCode;
import com.example.sku_sw.domain.broadcast.util.BroadcastRedisUtil;
import com.example.sku_sw.domain.chat.dto.FastApiChzzkRedisChannelReqDto;
import com.example.sku_sw.domain.chat.util.ChatRedisUtil;
import com.example.sku_sw.domain.chat.util.FastApiUtil;
import com.example.sku_sw.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class BroadcastRestoreService {

    private final BroadcastRedisUtil broadcastRedisUtil;
    private final ChatRedisUtil chatRedisUtil;
    private final FastApiUtil fastApiUtil;

    /**
     * Redis에 저장된 방송 사용자 정보를 기준으로 채팅 Redis 구독 상태를 복원한다.
     * @param broadcastStreamId : 방송 스트림 ID
     */
    public void restoreChatRedisChannel(String broadcastStreamId) {
        log.info("[BroadcastRestoreService] restoreChatRedisChannel() - 채팅 Redis Channel 구독 상태 복원 시작 | START | broadcastStreamId: {}", broadcastStreamId);
        BroadcastUserRedisDto broadcastUserRedisDto = broadcastRedisUtil.getBroadcastUserDto(broadcastStreamId);
        String channelName = broadcastUserRedisDto.getChannelName();
        if (channelName == null || channelName.isBlank()) {
            throw new CustomException(BroadcastErrorCode.CHZZK_REDIS_CHANNEL_NOT_CREATED);
        }

        boolean listenerRegistered = false;
        try {
            if (!chatRedisUtil.hasChannelListener(channelName)) {
                listenerRegistered = chatRedisUtil.resubscribeChannelPattern(channelName);
            }

            fastApiUtil.connectChzzkRedisChannel(new FastApiChzzkRedisChannelReqDto(
                    broadcastStreamId,
                    broadcastUserRedisDto.getSessionKey(),
                    channelName
            ));
            log.info("[BroadcastRestoreService] restoreChatRedisChannel() - 채팅 Redis Channel 구독 상태 복원 종료 | END | broadcastStreamId: {}", broadcastStreamId);
        } catch (CustomException e) {
            removeRestoredChannelListener(broadcastStreamId, broadcastUserRedisDto, listenerRegistered);
            throw e;
        } catch (Exception e) {
            removeRestoredChannelListener(broadcastStreamId, broadcastUserRedisDto, listenerRegistered);
            log.error("[BroadcastRestoreService] restoreChatRedisChannel() - Unexpected error | streamId: {}, error: {}",
                    broadcastStreamId, e.getMessage(), e);
            throw new CustomException(BroadcastErrorCode.CHZZK_REDIS_CHANNEL_CONNECT_FAILED);
        }
    }

    /**
     * 채팅 Redis 채널 복원 후 FastAPI 연결에 실패한 경우 이번 복원에서 등록한 Listener를 제거한다.
     * @param broadcastStreamId : 방송 스트림 ID
     * @param broadcastUserRedisDto : 방송 사용자 Redis 정보
     * @param listenerRegistered : 이번 복원에서 Listener를 등록했는지 여부
     */
    private void removeRestoredChannelListener(
            String broadcastStreamId,
            BroadcastUserRedisDto broadcastUserRedisDto,
            boolean listenerRegistered
    ) {
        log.debug("[BroadcastRestoreService] removeRestoredChannelListener() - START | streamId: {}, listenerRegistered: {}", broadcastStreamId, listenerRegistered);
        if (!listenerRegistered) {
            log.debug("[BroadcastRestoreService] removeRestoredChannelListener() - END | streamId: {}", broadcastStreamId);
            return;
        }

        try {
            chatRedisUtil.unsubscribeChannelPattern(broadcastUserRedisDto.getChannelId());
        } catch (Exception e) {
            log.error("[BroadcastRestoreService] removeRestoredChannelListener() - Listener rollback failed | streamId: {}, channelId: {}, error: {}",
                    broadcastStreamId, broadcastUserRedisDto.getChannelId(), e.getMessage(), e);
        }
        log.debug("[BroadcastRestoreService] removeRestoredChannelListener() - END | streamId: {}", broadcastStreamId);
    }
}
