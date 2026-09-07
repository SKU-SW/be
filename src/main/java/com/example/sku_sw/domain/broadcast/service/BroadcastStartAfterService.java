package com.example.sku_sw.domain.broadcast.service;

import com.example.sku_sw.domain.broadcast.dto.BroadcastCharacterRedisDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastUserRedisDto;
import com.example.sku_sw.domain.broadcast.util.BroadcastRedisUtil;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionRegistry;
import com.example.sku_sw.domain.chat.dto.FastApiChzzkRedisChannelReqDto;
import com.example.sku_sw.domain.chat.dto.FastApiChzzkRedisChannelResDto;
import com.example.sku_sw.domain.chat.util.ChatRedisUtil;
import com.example.sku_sw.domain.chat.util.FastApiUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class BroadcastStartAfterService {

    private final BroadcastConnectionTimeoutService broadcastConnectionTimeoutService;
    private final BroadcastStartCompensationService broadcastStartCompensationService;
    private final BroadcastRedisUtil broadcastRedisUtil;
    private final BroadcastWebSocketSessionRegistry sessionRegistry;
    private final ChatRedisUtil chatRedisUtil;
    private final FastApiUtil fastApiUtil;

    /**
     * 방송 시작 트랜잭션 커밋 후 Redis와 FastAPI 후속 처리를 수행한다.
     * - 방송 Redis 데이터를 초기화하고 치지직 Redis 채널을 연결한다.
     * - 모든 연결이 완료되면 WebSocket 연결 타임아웃을 등록한다.
     * - 후속 처리 중 실패하면 방송 상태와 생성된 외부 자원을 보상한다.
     * @param broadcastStreamId : 방송 스트림 ID
     * @param redisDto : Redis에 저장할 방송 캐릭터 정보
     * @param broadcastUserRedisDto : Redis에 저장할 방송 사용자 정보
     */
    public void processBroadcastStartAfterCommit(
            String broadcastStreamId,
            BroadcastCharacterRedisDto redisDto,
            BroadcastUserRedisDto broadcastUserRedisDto
    ) {
        log.info("[BroadcastStartAfterService] 방송 시작 후속 처리됨 | processBroadcastStartAfterCommit() - START | streamId: {}",
                broadcastStreamId);
        log.info(
                "[BroadcastStartAfterService] processBroadcastStartAfterCommit | threadName: {}, threadId: {}",
                Thread.currentThread().getName(),
                Thread.currentThread().threadId()
        );
        String subscribedChannelId = null;
        boolean fastApiConnected = false;
        try {
            /*
                1. 방송 Redis 데이터 초기화 및 채널 구독
                - BroadcastCharacterValue와 SummarySlot을 초기화한다.
                - 치지직 채널을 구독한 뒤 BroadcastUserValue에 구독 채널명을 저장한다.
             */
            broadcastRedisUtil.setBroadcastCharacterValue(broadcastStreamId, redisDto);
            broadcastRedisUtil.initializeSummarySlot(broadcastStreamId);
            subscribedChannelId = broadcastUserRedisDto.getChannelId();
            String channelName = chatRedisUtil.subscribeChannelPattern(subscribedChannelId);
            broadcastUserRedisDto.setChannelName(channelName);
            broadcastRedisUtil.setBroadcastUserValue(broadcastStreamId, broadcastUserRedisDto);

            /*
                2. FastAPI Redis 채널 연결
                - FastAPI가 치지직 Redis 채널 연결을 완료할 때까지 동기적으로 대기한다.
             */
            FastApiChzzkRedisChannelResDto response = fastApiUtil.connectChzzkRedisChannel(
                    buildFastApiChzzkRedisChannelReqDto(broadcastStreamId, broadcastUserRedisDto)
            );
            fastApiConnected = "연결 성공".equals(response.status());

            /*
                3. WebSocket 연결 타임아웃 등록
                - Redis와 FastAPI 채널 연결이 완료된 방송만 연결 타임아웃을 등록한다.
             */
            broadcastConnectionTimeoutService.registerConnectionTimeout(broadcastStreamId);
        } catch (Exception e) {
            /*
                4. 방송 시작 후속 처리 보상
                - 실패 시 방송을 비정상 종료하고 생성된 외부 자원을 정리한다.
             */
            log.error("[BroadcastStartAfterService] 방송 시작 후속 처리 실패 | streamId: {}, message: {}",
                    broadcastStreamId, e.getMessage(), e);
            rollbackBroadcastStartAfterCommit(
                    broadcastStreamId,
                    broadcastUserRedisDto,
                    subscribedChannelId,
                    fastApiConnected
            );
        }

        log.info("[BroadcastStartAfterService] 방송 시작 후속 처리 완료 | processBroadcastStartAfterCommit() - END | streamId: {}",
                broadcastStreamId);
    }

    /**
     * FastAPI Redis 채널 연결 요청 DTO를 생성한다.
     * @param broadcastStreamId : 방송 스트림 ID
     * @param broadcastUserRedisDto : 방송 사용자 Redis 정보
     * @return : FastAPI Redis 채널 연결 요청 DTO
     */
    private FastApiChzzkRedisChannelReqDto buildFastApiChzzkRedisChannelReqDto(
            String broadcastStreamId,
            BroadcastUserRedisDto broadcastUserRedisDto
    ) {
        log.debug("[BroadcastStartAfterService] FastAPI Redis 채널 요청 DTO 생성됨 | buildFastApiChzzkRedisChannelReqDto() - START | streamId: {}",
                broadcastStreamId);

        FastApiChzzkRedisChannelReqDto result = new FastApiChzzkRedisChannelReqDto(
                broadcastStreamId,
                broadcastUserRedisDto.getSessionKey(),
                broadcastUserRedisDto.getChannelName()
        );

        log.debug("[BroadcastStartAfterService] FastAPI Redis 채널 요청 DTO 생성 완료 | buildFastApiChzzkRedisChannelReqDto() - END | streamId: {}",
                broadcastStreamId);
        return result;
    }

    /**
     * 방송 시작 후속 처리 실패 시 방송 상태와 외부 자원을 보상한다.
     * - 별도 트랜잭션 서비스에서 방송 상태를 비정상 종료로 변경한다.
     * - 연결된 FastAPI 및 Redis 구독을 해제하고 생성된 방송 Redis 값과 WebSocket 번들을 삭제한다.
     * @param broadcastStreamId : 방송 스트림 ID
     * @param broadcastUserRedisDto : 방송 사용자 Redis 정보
     * @param subscribedChannelId : 구독을 해제할 채널 ID
     * @param fastApiConnected : FastAPI Redis 채널 연결 완료 여부
     */
    private void rollbackBroadcastStartAfterCommit(
            String broadcastStreamId,
            BroadcastUserRedisDto broadcastUserRedisDto,
            String subscribedChannelId,
            boolean fastApiConnected
    ) {
        log.debug("[BroadcastStartAfterService] 방송 시작 후속 처리 보상됨 | rollbackBroadcastStartAfterCommit() - START | streamId: {}",
                broadcastStreamId);

        try {
            /*
                1. 방송 DB 상태 비정상 종료 처리
                - 기존 방송 시작 트랜잭션과 분리된 새 트랜잭션에서 상태와 종료 시간을 변경한다.
             */
            broadcastStartCompensationService.abnormalTerminateBroadcast(broadcastStreamId);
        } catch (Exception e) {
            log.error("[BroadcastStartAfterService] rollbackBroadcastStartAfterCommit() - Broadcast status rollback failed | streamId: {}, error: {}",
                    broadcastStreamId, e.getMessage(), e);
        }

        try {
            /*
                2. FastAPI Redis 채널 연결 해제
                - FastAPI 연결이 완료된 경우에만 연결 해제를 요청한다.
             */
            if (fastApiConnected && StringUtils.hasText(broadcastUserRedisDto.getChannelName())) {
                fastApiUtil.disconnectChzzkRedisChannel(
                        buildFastApiChzzkRedisChannelReqDto(broadcastStreamId, broadcastUserRedisDto)
                );
            }
        } catch (Exception e) {
            log.error("[BroadcastStartAfterService] rollbackBroadcastStartAfterCommit() - FastAPI disconnect failed | streamId: {}, error: {}",
                    broadcastStreamId, e.getMessage(), e);
        }

        try {
            /*
                3. Spring Boot Redis 채널 구독 해제
                - 구독이 생성된 경우 치지직 채널 패턴 구독을 해제한다.
             */
            if (StringUtils.hasText(subscribedChannelId)) {
                chatRedisUtil.unsubscribeChannelPattern(subscribedChannelId);
            }
        } catch (Exception e) {
            log.error("[BroadcastStartAfterService] rollbackBroadcastStartAfterCommit() - Chat unsubscribe failed | streamId: {}, error: {}",
                    broadcastStreamId, e.getMessage(), e);
        }

        try {
            /*
                4. 방송 Redis 데이터 삭제
                - BroadcastCharacterValue, BroadcastUserValue, BroadcastInfo를 삭제한다.
             */
            broadcastRedisUtil.deleteBroadcastCharacterValue(broadcastStreamId);
            broadcastRedisUtil.deleteBroadcastUserValue(broadcastStreamId);
            broadcastRedisUtil.deleteBroadcastInfo(broadcastStreamId);
        } catch (Exception e) {
            log.error("[BroadcastStartAfterService] rollbackBroadcastStartAfterCommit() - Redis rollback failed | streamId: {}, error: {}",
                    broadcastStreamId, e.getMessage(), e);
        }

        try {
            /*
                5. WebSocket 세션 번들 제거
                - 방송 시작 과정에서 생성된 Client/Gemini WebSocket을 종료하고 Registry에서 번들을 제거한다.
             */
            sessionRegistry.disconnect(broadcastStreamId);
        } catch (Exception e) {
            log.error("[BroadcastStartAfterService] rollbackBroadcastStartAfterCommit() - WebSocket session disconnect failed | streamId: {}, error: {}",
                    broadcastStreamId, e.getMessage(), e);
        }

        log.debug("[BroadcastStartAfterService] 방송 시작 후속 처리 보상 완료 | rollbackBroadcastStartAfterCommit() - END | streamId: {}",
                broadcastStreamId);
    }
}
