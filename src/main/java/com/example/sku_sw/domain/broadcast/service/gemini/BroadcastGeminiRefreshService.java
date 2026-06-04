package com.example.sku_sw.domain.broadcast.service.gemini;

import com.example.sku_sw.domain.broadcast.dto.BroadcastCharacterRedisDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastDialogueRefreshSnapshotDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastInfoRedisDto;
import com.example.sku_sw.domain.broadcast.enums.BroadcastErrorCode;
import com.example.sku_sw.domain.broadcast.enums.DialogueSubject;
import com.example.sku_sw.domain.broadcast.enums.WebSocketSessionBundleStatus;
import com.example.sku_sw.domain.broadcast.util.BroadcastPromptBuilder;
import com.example.sku_sw.domain.broadcast.util.BroadcastRedisUtil;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionBundle;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionRegistry;
import com.example.sku_sw.domain.broadcast.websocket.gemini.GeminiLiveWebSocketHandler;
import com.example.sku_sw.domain.character.enums.Gender;
import com.example.sku_sw.global.util.GeminiUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Gemini refresh 오케스트레이션 서비스
 * - summary 갱신 이후 기존 Gemini 세션 drain, snapshot 확보, 신규 Gemini bootstrap, Redis 정리, backlog replay를 담당한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BroadcastGeminiRefreshService {

    @Value("${broadcast.dialogue.redis-max-num}")
    private Integer redisBroadcastDialogueMaxNum;

    @Value("${broadcast.dialogue.redis-max-refresh-retry-count}")
    private Integer redisMaxRefreshRetryCount;

    private final BroadcastWebSocketSessionRegistry sessionRegistry;
    private final BroadcastRedisUtil broadcastRedisUtil;
    private final BroadcastPromptBuilder broadcastPromptBuilder;
    private final GeminiUtil geminiUtil;
    private final BroadcastGeminiBootstrapService broadcastGeminiBootstrapService;
    private final BroadcastGeminiRequestService broadcastGeminiRequestService;
    private final BroadcastGeminiLiveService broadcastGeminiLiveService;
    private final TaskScheduler taskScheduler;

    /**
     * compaction 완료 이후 Gemini refresh를 요청한다.
     * - refresh 상태로 전환하고, request-flight값이 0이면 즉시 refresh를 시작한다.
     * - 만약
     * @param broadcastStreamId : 방송 스트림 ID
     */
    public void requestRefreshAfterCompaction(String broadcastStreamId) {
        log.info("[BroadcastGeminiRefreshService] requestRefreshAfterCompaction() - START | streamId: {}", broadcastStreamId);

        /*
            1. 현재 세션 번들 조회 및 refresh 상태 전환
            - 현재 활성 번들이 없으면 종료한다.
         */
        BroadcastWebSocketSessionBundle bundle = sessionRegistry.getSessionBundle(broadcastStreamId);
        if (bundle == null || !bundle.isClientSessionOpen()) {
            log.info("[BroadcastGeminiRefreshService] requestRefreshAfterCompaction() - END | streamId: {}, action: bundle_not_found", broadcastStreamId);
            return;
        }

        /*
            2. 현재 request-flight 요청 수를 확인한다.
            - Gemini 응답 처리 중이 아니면 즉시 refresh를 시작한다.
            - 응답 처리 중이면 마지막 turn 종료 시점에 refresh가 시작된다.
              (각 Gemini 응답 종료 시점에 재검사가 실행된다. - BroadcastGeminiResponseService.handleCompletedTurnAsync())
            - 안전장치: 응답 처리 중이면 5초 후 자체 재검사를 예약하여,
              콜백 누락 시에도 refresh가 영구히 멈추지 않도록 보장한다.
         */
        if (bundle.getRequestFlightCountValue() == 0) {
            log.info("[BroadcastGeminiRefreshService] requestRefreshAfterCompaction() - Mark Refresh Requested and Set Bundle Status REFRESHING | streamId: {}, inFlightCount: {}", broadcastStreamId, bundle.getRequestFlightCountValue());
            bundle.markRefreshRequested();
            bundle.updateStatus(WebSocketSessionBundleStatus.REFRESHING);
            tryStartRefresh(broadcastStreamId, bundle.getGeneration());
        }

        log.info("[BroadcastGeminiRefreshService] requestRefreshAfterCompaction() - END | streamId: {}, inFlightCount: {}",
                broadcastStreamId, bundle.getRequestFlightCountValue());
    }

    /**
     * 현재 방송의 Gemini refresh 상태가 compaction을 차단해야 하는지 확인한다.
     * @param broadcastStreamId : 방송 스트림 ID
     * @return : compaction 차단 여부
     */
    public boolean isRefreshBlockingCompaction(String broadcastStreamId) {
        BroadcastWebSocketSessionBundle bundle = sessionRegistry.getSessionBundle(broadcastStreamId);
        return bundle != null && (bundle.isRefreshing() || bundle.getGeminiSessionRefreshRequested() || bundle.getGeminiSessionRefreshInProgress());
    }

    /**
     * Gemini request-flight 요청이 모두 종료되었을 때 refresh 시작을 시도한다.
     * @param broadcastStreamId : 방송 스트림 ID
     * @param generation : 현재 세션 generation
     */
    public void tryStartRefresh(String broadcastStreamId, long generation) {
        log.info("[BroadcastGeminiRefreshService] tryStartRefresh() - START | streamId: {}, generation: {}", broadcastStreamId, generation);

        /*
            1. refresh 시작 가능 여부를 검증한다.
            - 현재 generation 번들을 다시 확인한다.
            - refresh 요청이 없거나 이미 refresh 중이면 중복 시작하지 않는다.
         */
        BroadcastWebSocketSessionBundle bundle = sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation);
        if (bundle == null || !bundle.getGeminiSessionRefreshRequested()) {
            log.info("[BroadcastGeminiRefreshService] tryStartRefresh() - END | streamId: {}, action: refresh_not_requested", broadcastStreamId);
            return;
        }
        if (bundle.getRequestFlightCountValue() > 0) {
            log.info("[BroadcastGeminiRefreshService] tryStartRefresh() - END | streamId: {}, action: in_flight_remaining", broadcastStreamId);
            return;
        }
        if (!bundle.markRefreshInProgress()) {
            log.info("[BroadcastGeminiRefreshService] tryStartRefresh() - END | streamId: {}, action: already_refreshing", broadcastStreamId);
            return;
        }

        try {
            /*
                2. refresh snapshot을 확보하고 신규 Gemini Session bootstrap(초기화)을 시작한다.
                - 해당 순간의 Redis snapshotCursor를 저장하여 refresh 이후 삭제 범위를 고정한다.
            */
            BroadcastDialogueRefreshSnapshotDto snapshot = broadcastRedisUtil.getRefreshSnapshot(
                    broadcastStreamId,
                    redisBroadcastDialogueMaxNum
            );
            bundle.updateRefreshSnapshotCursorId(snapshot.snapshotCursorId());

            /*
                3. snapshot 데이터를 기반으로 prompt를 생성해 신규 Gemini Session 연결을 시도한다.
                - setup이 완료된 Gemini Session을 반환받으면, 해당 Session 객체를 사용해 Refresh 성공 시의 후처리 작업을 수행한다.
             */
            BroadcastCharacterRedisDto character = broadcastRedisUtil.getBroadcastCharacterDto(broadcastStreamId);
            String systemPrompt = broadcastPromptBuilder.buildBroadcastDialoguePrompt(
                    character,
                    snapshot.summary(),
                    snapshot.dialogues()
            );
            String voiceName = deriveVoiceName(character);
            WebSocketSession oldGeminiSession = bundle.getGeminiSession();
            broadcastGeminiBootstrapService.bootstrapGeminiForRefreshAsync(broadcastStreamId, generation, systemPrompt, voiceName)
                    .thenAccept(newGeminiSession -> handleRefreshSuccess(
                            broadcastStreamId,
                            generation,
                            oldGeminiSession,
                            newGeminiSession,
                            snapshot.snapshotCursorId()
                    ))
                    .exceptionally(throwable -> {
                        handleRefreshFailure(broadcastStreamId, generation, throwable);
                        return null;
                    });
        } catch (Exception e) {
            handleRefreshFailure(broadcastStreamId, generation, e);
        }

        log.info("[BroadcastGeminiRefreshService] tryStartRefresh() - END | streamId: {}, generation: {}", broadcastStreamId, generation);
    }

    /**
     * refresh 성공 후 후처리를 수행한다.
     * - snapshot 기준 이전 Redis 대화를 삭제하고, snapshot 이후 누적 backlog를 순서대로 replay한다.
     * @param broadcastStreamId : 방송 스트림 ID
     * @param generation : 현재 세션 generation
     * @param oldGeminiSession : 이전 Gemini 세션
     * @param newGeminiSession : 신규 Gemini 세션
     * @param snapshotCursorId : refresh snapshot 마지막 cursor
     */
    public void handleRefreshSuccess(
            String broadcastStreamId,
            long generation,
            WebSocketSession oldGeminiSession,
            WebSocketSession newGeminiSession,
            Long snapshotCursorId
    ) {
        log.info("[BroadcastGeminiRefreshService] handleRefreshSuccess() - START | streamId: {}, generation: {}, snapshotCursorId: {}",
                broadcastStreamId, generation, snapshotCursorId);
        GeminiLiveWebSocketHandler newHandler = broadcastGeminiLiveService.consumePendingHandler(newGeminiSession.getId());

        /*
            1. 현재 generation 번들을 재검증한다.
            - 신규 Gemini 연결이 완료되기 전에 세션이 교체되었으면 신규 세션을 닫고 종료한다.
         */
        BroadcastWebSocketSessionBundle bundle = sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation);
        if (bundle == null) {
            geminiUtil.closeGeminiSessionQuietly(newGeminiSession);
            log.warn("[BroadcastGeminiRefreshService] handleRefreshSuccess() - Bundle not found | streamId: {}, generation: {}",
                    broadcastStreamId, generation);
            return;
        }

        /*
            2. snapshot 이전 Redis 대화를 삭제하고 snapshot 이후 backlog를 조회한다.
            - 삭제는 cursor 기준으로 원자적으로 수행한다.
            - 삭제 이후 남아있는 ACTIVE 대화를 cursor 순서대로 replay 대상으로 사용한다.
         */
        broadcastRedisUtil.atomicDeleteDialoguesUpToCursor(broadcastStreamId, snapshotCursorId);
        List<BroadcastInfoRedisDto> replayCandidates = broadcastRedisUtil.getActiveDialoguesAfterCursor(broadcastStreamId, snapshotCursorId);

        /*
             3. 신규 Gemini 세션과 핸들러를 bundle에 등록하고 READY 상태로 전환한다.
            - 이후 backlog를 순서대로 replay하고, 마지막에 이전 Gemini 세션을 종료한다.
         */
        registerNewGeminiSessionToSessionBundleAndClearRefreshingStatus(bundle, newGeminiSession, newHandler);
        replayDialogues(broadcastStreamId, generation, replayCandidates, "handleRefreshSuccess");

        if (oldGeminiSession != null && oldGeminiSession != newGeminiSession) {
            geminiUtil.closeGeminiSessionQuietly(oldGeminiSession);
        }

        broadcastGeminiBootstrapService.sendStatusMessage(
                bundle.getClientSession(),
                WebSocketSessionBundleStatus.READY.name(),
                "Gemini WebSocket 세션 refresh 완료"
        );
        log.info("[BroadcastGeminiRefreshService] handleRefreshSuccess() - END | streamId: {}, generation: {}",
                broadcastStreamId, generation);
    }

    /**
     * refresh 실패 후 후처리를 수행한다.
     * - old Gemini가 살아있으면 snapshot 이후 누적 backlog를 old Gemini로 replay 후 READY 복귀한다.
     * - old Gemini도 사용할 수 없으면 1회 재시도 후 실패 시 전체 리소스를 정리한다.
     * @param broadcastStreamId : 방송 스트림 ID
     * @param generation : 현재 세션 generation
     * @param throwable : 발생한 예외
     */
    public void handleRefreshFailure(String broadcastStreamId, long generation, Throwable throwable) {
        log.error("[BroadcastGeminiRefreshService] handleRefreshFailure() - START | streamId: {}, generation: {}, error: {}",
                broadcastStreamId, generation, throwable != null ? throwable.getMessage() : "unknown error");

        /*
            1. 현재 번들과 기존 Gemini 세션 상태를 확인한다.
            - old Gemini 세션이 살아있으면 snapshot 이후 backlog를 old 세션으로 replay하고 READY로 복귀한다.
            - old Gemini 세션이 없으면 재시도 또는 전체 종료를 진행한다.
         */
        BroadcastWebSocketSessionBundle bundle = sessionRegistry.getSessionBundleIfCurrent(broadcastStreamId, generation);
        if (bundle == null) {
            log.warn("[BroadcastGeminiRefreshService] handleRefreshFailure() - Bundle not found | streamId: {}, generation: {}",
                    broadcastStreamId, generation);
            return;
        }

        /*
            2. oldGeminiSession이 살아있으면 해당 Session Bundle의 Refreshing Status를 원상복구하고, old gemini session으로 대화 replay를 보낸다.
            - 이후 클라이언트에게 관련 작업 메시지 전송
         */
        WebSocketSession oldGeminiSession = bundle.getGeminiSession();
        if (oldGeminiSession != null && oldGeminiSession.isOpen()) {
            clearRefreshingStatus(bundle);
            replayDialoguesToCurrentGemini(broadcastStreamId, generation, bundle, "handleRefreshFailure");
            broadcastGeminiBootstrapService.sendStatusMessage(
                    bundle.getClientSession(),
                    WebSocketSessionBundleStatus.READY.name(),
                    "Gemini WebSocket 세션 refresh 실패, 기존 세션으로 복귀"
            );
            log.info("[BroadcastGeminiRefreshService] handleRefreshFailure() - END | streamId: {}, action: rollback_ready", broadcastStreamId);
            return;
        }

        /*
            3. 기존 Gemini 세션도 사용할 수 없으면 1회 재시도 후 종료한다.
            - 첫 실패면 retry count를 증가시키고 refresh를 다시 시작한다.
            - 재시도도 실패하면 전체 웹소켓 리소스를 종료하고 클라이언트에 에러를 전달한다.
         */
        int retryCount = bundle.incrementRefreshRetryCount();
        bundle.clearRefreshInProgress();
        if (retryCount <= redisMaxRefreshRetryCount) {
            tryStartRefresh(broadcastStreamId, generation);
            log.info("[BroadcastGeminiRefreshService] handleRefreshFailure() - END | streamId: {}, action: retry, retryCount: {}",
                    broadcastStreamId, retryCount);
            return;
        }
        clearRefreshingStatusByFailed(bundle);
        broadcastGeminiBootstrapService.terminateBundleWithError(
                broadcastStreamId,
                generation,
                bundle.getClientSession(),
                bundle.getGeminiSession(),
                CloseStatus.SERVER_ERROR,
                BroadcastErrorCode.GEMINI_RESPONSE_FAILED.getMessage()
        );
        log.error("[BroadcastGeminiRefreshService] handleRefreshFailure() - END | streamId: {}, action: terminate", broadcastStreamId);
    }

    private void replayDialoguesToCurrentGemini(
            String broadcastStreamId,
            long generation,
            BroadcastWebSocketSessionBundle bundle,
            String caller
    ) {
        Long snapshotCursorId = bundle.getGeminiSessionRefreshSnapshotRedisCursorId();
        List<BroadcastInfoRedisDto> replayCandidates = broadcastRedisUtil.getActiveDialoguesAfterCursor(broadcastStreamId, snapshotCursorId);
        replayDialogues(broadcastStreamId, generation, replayCandidates, caller);
    }

    private void replayDialogues(
            String broadcastStreamId,
            long generation,
            List<BroadcastInfoRedisDto> replayCandidates,
            String caller
    ) {
        if (replayCandidates == null || replayCandidates.isEmpty()) {
            return;
        }

        replayCandidates.stream()
                .sorted(Comparator.comparing(BroadcastInfoRedisDto::cursorId))
                .filter(info -> info.subject() == DialogueSubject.STREAMER)
                .filter(info -> info.content() != null && !info.content().isBlank())
                .forEach(info -> {
                    try {
                        broadcastGeminiRequestService.processReplayMessage(
                                broadcastStreamId,
                                generation,
                                info.content()
                        );
                    } catch (Exception e) {
                        log.warn("[BroadcastGeminiRefreshService] {}() - Replay failed | streamId: {}, generation: {}, cursorId: {}, error: {}",
                                caller, broadcastStreamId, generation, info.cursorId(), e.getMessage());
                        throw e;
                    }
                });
    }

    /**
     * 새로운 제미나이 세션을 SessionBundle에 새로 등록하고 Session Bundle 상태를 Refresh에서 해제한다.
     * @param bundle : 현재 세션 번들
     * @param newGeminiSession : 신규 Gemini 세션
     */
    private void registerNewGeminiSessionToSessionBundleAndClearRefreshingStatus(
            BroadcastWebSocketSessionBundle bundle,
            WebSocketSession newGeminiSession,
            GeminiLiveWebSocketHandler newHandler
    ) {
        bundle.registerGeminiSession(newGeminiSession, newHandler);
        clearRefreshingStatus(bundle);
    }

    private void clearRefreshingStatus(BroadcastWebSocketSessionBundle bundle) {
        bundle.updateStatus(WebSocketSessionBundleStatus.READY);
        bundle.clearRefreshRequested();
        bundle.clearRefreshInProgress();
        bundle.resetRefreshRetryCount();
        bundle.clearRefreshSnapshotCursorId();
    }

    /**
     * BroadcastCharacterRedisDto로부터 voiceName을 추출한다.
     * @param character 캐릭터 Redis DTO
     * @return voice name (null 가능)
     */
    private String deriveVoiceName(BroadcastCharacterRedisDto character) {
        if (character == null || character.getCharacterPresetType() == null) {
            return null;
        }
        return character.getCharacterGender() == Gender.MALE
                ? character.getCharacterPresetType().getMaleVoiceName()
                : character.getCharacterPresetType().getFemaleVoiceName();
    }

    private void clearRefreshingStatusByFailed(BroadcastWebSocketSessionBundle bundle) {
        bundle.updateStatus(WebSocketSessionBundleStatus.FAILED);
        bundle.clearRefreshRequested();
        bundle.clearRefreshInProgress();
        bundle.resetRefreshRetryCount();
        bundle.clearRefreshSnapshotCursorId();
    }
}
