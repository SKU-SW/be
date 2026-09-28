package com.example.sku_sw.domain.broadcast.service;

import com.example.sku_sw.domain.broadcast.dto.BroadcastUserRedisDto;
import com.example.sku_sw.domain.broadcast.util.BroadcastRedisUtil;
import com.example.sku_sw.domain.broadcast.util.BroadcastTransactionLogger;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionRegistry;
import com.example.sku_sw.domain.chat.dto.FastApiChzzkRedisChannelReqDto;
import com.example.sku_sw.domain.chat.util.ChatRedisUtil;
import com.example.sku_sw.domain.chat.util.FastApiUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.CloseStatus;

/**
 * 방송 종료 상태 커밋 이후 대화 저장, 분석 및 외부 자원 정리를 수행한다.
 * 기존 후속 처리의 순서와 예외 처리 정책을 유지한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BroadcastTerminateAfterService {

    private final BroadcastWebSocketSessionRegistry sessionRegistry;
    private final BroadcastDialoguePersistenceService broadcastDialoguePersistenceService;
    private final BroadcastAnalysisService broadcastAnalysisService;
    private final FastApiUtil fastApiUtil;
    private final ChatRedisUtil chatRedisUtil;
    private final BroadcastRedisUtil broadcastRedisUtil;
    private final BroadcastStreamerSilenceService streamerSilenceService;
    private final BroadcastProactiveChatService proactiveChatService;

    /**
     * 커밋이 확정된 방송의 종료 후속 처리를 동기적으로 수행한다.
     * - 잔여 대화 저장 성공 시 분석하고, Redis 및 WebSocket 자원을 정리한다.
     * @param broadcastStreamId : 종료할 방송 스트림 ID
     */
    public void processBroadcastTerminateAfterCommit(String broadcastStreamId) {
        log.info("[BroadcastTerminateAfterService] processBroadcastTerminateAfterCommit() - START | streamId: {}", broadcastStreamId);
        streamerSilenceService.cancel(broadcastStreamId);
        proactiveChatService.cancel(broadcastStreamId);
        BroadcastUserRedisDto broadcastUserRedisDto = null;
        boolean remainingDialoguesSaved = false;
        try {
            // 1. Redis에 남아있는 대화 데이터를 DB에 저장
            BroadcastTransactionLogger.logCurrent("terminate.afterCommit.BEFORE_DIALOGUES", broadcastStreamId);
            broadcastDialoguePersistenceService.saveRemainingRedisDialogues(broadcastStreamId);
            BroadcastTransactionLogger.logCurrent("terminate.afterCommit.AFTER_DIALOGUES", broadcastStreamId);
            remainingDialoguesSaved = true;
        } catch (Exception e) {
            log.error("[BroadcastTerminateAfterService] 방송 종료 잔여 대화 저장 중 오류 발생 | streamId: {}, message: {}", broadcastStreamId, e.getMessage(), e);
        }

        if (remainingDialoguesSaved) {
            try {
                // 2. 동기적으로 방송 데이터 분석
                BroadcastTransactionLogger.logCurrent("terminate.afterCommit.BEFORE_DIALOGUES", broadcastStreamId);
                broadcastAnalysisService.analysisBroadcastDialogues(broadcastStreamId);
                BroadcastTransactionLogger.logCurrent("terminate.afterCommit.AFTER_ANALYSIS", broadcastStreamId);
            } catch (Exception e) {
                log.error("[BroadcastTerminateAfterService] 방송 분석 중 오류 발생 | streamId: {}, message: {}", broadcastStreamId, e.getMessage(), e);
            }
        } else {
            log.warn("[BroadcastTerminateAfterService] 잔여 대화 저장 실패로 방송 분석을 건너뜁니다. | streamId: {}", broadcastStreamId);
        }

        try {
            // 3. Redis에서 방송 User 정보 조회
            broadcastUserRedisDto = broadcastRedisUtil.getBroadcastUserDto(broadcastStreamId);
            // 4. 치지직 Redis 채널 연결 해제 로직
            if (StringUtils.hasText(broadcastUserRedisDto.getChannelName())) {
                fastApiUtil.disconnectChzzkRedisChannel(
                        buildFastApiChzzkRedisChannelReqDto(broadcastStreamId, broadcastUserRedisDto)
                );
            }
            if (StringUtils.hasText(broadcastUserRedisDto.getChannelId())) {
                chatRedisUtil.unsubscribeChannelPattern(broadcastUserRedisDto.getChannelId());
            }
        } catch (Exception e) {
            log.error("[BroadcastTerminateAfterService] 방송 종료 정리 중 오류 발생 | streamId: {}, message: {}", broadcastStreamId, e.getMessage(), e);
        } finally {
            try { broadcastRedisUtil.deleteBroadcastCharacterValue(broadcastStreamId); }
            catch (Exception e) { log.error("[BroadcastTerminateAfterService] 방송 캐릭터 정보 Redis 삭제 실패 | streamId: {}, message: {}", broadcastStreamId, e.getMessage(), e); }
            try { broadcastRedisUtil.deleteBroadcastUserValue(broadcastStreamId); }
            catch (Exception e) { log.error("[BroadcastTerminateAfterService] 방송 유저 정보 Redis 삭제 실패 | streamId: {}, message: {}", broadcastStreamId, e.getMessage(), e); }
            try { broadcastRedisUtil.deleteBroadcastInfo(broadcastStreamId); }
            catch (Exception e) { log.error("[BroadcastTerminateAfterService] 방송 정보 Redis 삭제 실패 | streamId: {}, message: {}", broadcastStreamId, e.getMessage(), e); }
        }

        sessionRegistry.disconnect(
                broadcastStreamId,
                CloseStatus.NORMAL.withReason("Broadcast terminated")
        );
        log.info("[BroadcastTerminateAfterService] processBroadcastTerminateAfterCommit() - END | streamId: {}", broadcastStreamId);
    }

    /**
     * FastAPI 치지직 Redis 채널 연결 해제 요청 DTO를 생성한다.
     * @param broadcastStreamId : 종료할 방송 스트림 ID
     * @param broadcastUserRedisDto : Redis에 저장된 방송 사용자 정보
     * @return : FastAPI 치지직 Redis 채널 요청 DTO
     */
    private FastApiChzzkRedisChannelReqDto buildFastApiChzzkRedisChannelReqDto(
            String broadcastStreamId,
            BroadcastUserRedisDto broadcastUserRedisDto
    ) {
        log.debug("[BroadcastTerminateAfterService] buildFastApiChzzkRedisChannelReqDto() - START | streamId: {}", broadcastStreamId);
        FastApiChzzkRedisChannelReqDto result = new FastApiChzzkRedisChannelReqDto(
                broadcastStreamId,
                broadcastUserRedisDto.getSessionKey(),
                broadcastUserRedisDto.getChannelName()
        );
        log.debug("[BroadcastTerminateAfterService] buildFastApiChzzkRedisChannelReqDto() - END | streamId: {}", broadcastStreamId);
        return result;
    }
}
