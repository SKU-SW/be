package com.example.sku_sw.domain.broadcast.service;

import com.example.sku_sw.domain.broadcast.dto.BroadcastDialogueCompactionSnapshotDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastDialogueSnapshotItemDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastInfoRedisDto;
import com.example.sku_sw.domain.broadcast.enums.DialogueSubject;
import com.example.sku_sw.domain.broadcast.util.BroadcastRedisUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BroadcastDialogueCompactionService {

    @Value("${broadcast.dialogue.redis-max-num}")
    private int redisBroadcastDialogueMaxNum;

    private final BroadcastRedisUtil broadcastRedisUtil;
    private final BroadcastDialoguePersistenceService broadcastDialoguePersistenceService;
    private final BroadcastDialogueSummaryService broadcastDialogueSummaryService;

    /**
     * compaction 시작 가능 여부를 확인하고 비동기 실행하는 함수
     * @param broadcastStreamId : 방송 스트림 ID
     */
    public void tryStartCompaction(String broadcastStreamId) {
        log.info("[BroadcastDialogueCompactionService] tryStartCompaction() - START | streamId: {}", broadcastStreamId);

        /*
            1. summary 진행 여부 및 threshold 확인
            - 이미 요약 중이면 중복 실행하지 않는다.
            - INACTIVE가 있거나 ACTIVE 개수가 기준 이상인 경우에만 진행한다.
         */
        if (broadcastRedisUtil.isSummaryInProgress(broadcastStreamId)) {
            log.info("[BroadcastDialogueCompactionService] tryStartCompaction() - END | streamId: {}, action: in_progress", broadcastStreamId);
            return;
        }

        boolean hasInactive = broadcastRedisUtil.hasInactiveDialogues(broadcastStreamId);
        int activeCount = broadcastRedisUtil.countActiveDialogues(broadcastStreamId);
        if (!hasInactive && activeCount < redisBroadcastDialogueMaxNum) {
            log.info("[BroadcastDialogueCompactionService] tryStartCompaction() - END | streamId: {}, action: below_threshold", broadcastStreamId);
            return;
        }

        if (!broadcastRedisUtil.markSummaryInProgress(broadcastStreamId)) {
            log.info("[BroadcastDialogueCompactionService] tryStartCompaction() - END | streamId: {}, action: mark_failed", broadcastStreamId);
            return;
        }

        /*
            2. 비동기 compaction 실행
            - 실시간 메시지 처리 흐름을 block하지 않도록 boundedElastic에서 수행한다.
            - 실제 방송 데이터 요약 API 요청은 비동기로 수행되나, 그 이전에 prepareCompaction에서도 요약을 준비하기 위한 작업이 수행된다.
              해당 작업도 별도의 스레드풀에서 수행하도록 함으로써 기존 메시징 처리 흐름이 끊기지 않도록 한다.
         */
        Schedulers.boundedElastic().schedule(() -> compactInternalAsync(broadcastStreamId));
        log.info("[BroadcastDialogueCompactionService] tryStartCompaction() - END | streamId: {}, action: scheduled", broadcastStreamId);
    }

    /**
     * 방송 종료 시 남은 대화를 즉시 compaction하는 함수
     * @param broadcastStreamId : 방송 스트림 ID
     */
    public boolean compactRemainingDialogues(String broadcastStreamId) {
        log.info("[BroadcastDialogueCompactionService] compactRemainingDialogues() - START | streamId: {}", broadcastStreamId);

        if (broadcastRedisUtil.isSummaryInProgress(broadcastStreamId)) {
            log.info("[BroadcastDialogueCompactionService] compactRemainingDialogues() - END | streamId: {}, action: in_progress", broadcastStreamId);
            return false;
        }

        if (!broadcastRedisUtil.hasInactiveDialogues(broadcastStreamId) && broadcastRedisUtil.countActiveDialogues(broadcastStreamId) == 0) {
            log.info("[BroadcastDialogueCompactionService] compactRemainingDialogues() - END | streamId: {}, action: no_dialogue", broadcastStreamId);
            return true;
        }

        if (!broadcastRedisUtil.markSummaryInProgress(broadcastStreamId)) {
            log.info("[BroadcastDialogueCompactionService] compactRemainingDialogues() - END | streamId: {}, action: mark_failed", broadcastStreamId);
            return false;
        }

        boolean result = compactInternalSync(broadcastStreamId);
        log.info("[BroadcastDialogueCompactionService] compactRemainingDialogues() - END | streamId: {}", broadcastStreamId);
        return result;
    }

    private void compactInternalAsync(String broadcastStreamId) {
        log.info("[BroadcastDialogueCompactionService] compactInternalAsync() - START | streamId: {}", broadcastStreamId);

        try {
            CompactionPreparedData preparedData = prepareCompaction(broadcastStreamId);
            if (preparedData == null) {
                log.info("[BroadcastDialogueCompactionService] compactInternalAsync() - END | streamId: {}, action: empty_snapshot", broadcastStreamId);
                return;
            }

            /*
                2. Summary AI 호출 후 summary 반영 + INACTIVE 삭제
                - summary 응답이 도착하면 Redis 0번 summary를 갱신하고 INACTIVE를 제거한다.
             */
            broadcastDialogueSummaryService.summarize(preparedData.summary(), preparedData.dialogues())
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(
                            summaryText -> {
                                BroadcastInfoRedisDto newSummary = BroadcastInfoRedisDto.builder()
                                        .cursorId(0L)
                                        .subject(DialogueSubject.SYSTEM_SUMMARY)
                                        .content(summaryText)
                                        .createdAt(LocalDateTime.now())
                                        .dataStatus(preparedData.summary().dataStatus())
                                        .build();
                                broadcastRedisUtil.atomicReplaceSummaryAndDeleteInactive(broadcastStreamId, newSummary);
                                broadcastRedisUtil.clearSummaryInProgress(broadcastStreamId);
                                if (broadcastRedisUtil.hasInactiveDialogues(broadcastStreamId)
                                        || broadcastRedisUtil.countActiveDialogues(broadcastStreamId) >= redisBroadcastDialogueMaxNum) {
                                    tryStartCompaction(broadcastStreamId);
                                }
                                log.info("[BroadcastDialogueCompactionService] compactInternalAsync() - Summary applied | streamId: {}, dialogueSize: {}",
                                        broadcastStreamId, preparedData.dialogues().size());
                            },
                            error -> {
                                log.error("[BroadcastDialogueCompactionService] compactInternalAsync() - Summary failed | streamId: {}, error: {}",
                                        broadcastStreamId, error.getMessage());
                                broadcastRedisUtil.clearSummaryInProgress(broadcastStreamId);
                            }
                    );
        } catch (Exception e) {
            log.error("[BroadcastDialogueCompactionService] compactInternalAsync() - Failed | streamId: {}, error: {}", broadcastStreamId, e.getMessage(), e);
            broadcastRedisUtil.clearSummaryInProgress(broadcastStreamId);
        }

        log.info("[BroadcastDialogueCompactionService] compactInternalAsync() - END | streamId: {}", broadcastStreamId);
    }

    private boolean compactInternalSync(String broadcastStreamId) {
        log.info("[BroadcastDialogueCompactionService] compactInternalSync() - START | streamId: {}", broadcastStreamId);

        try {
            CompactionPreparedData preparedData = prepareCompaction(broadcastStreamId);
            if (preparedData == null) {
                log.info("[BroadcastDialogueCompactionService] compactInternalSync() - END | streamId: {}, action: empty_snapshot", broadcastStreamId);
                return true;
            }

            String summaryText = broadcastDialogueSummaryService.summarize(preparedData.summary(), preparedData.dialogues()).block();
            BroadcastInfoRedisDto newSummary = BroadcastInfoRedisDto.builder()
                    .cursorId(0L)
                    .subject(DialogueSubject.SYSTEM_SUMMARY)
                    .content(summaryText)
                    .createdAt(LocalDateTime.now())
                    .dataStatus(preparedData.summary().dataStatus())
                    .build();

            broadcastRedisUtil.atomicReplaceSummaryAndDeleteInactive(broadcastStreamId, newSummary);
            broadcastRedisUtil.clearSummaryInProgress(broadcastStreamId);

            if (broadcastRedisUtil.hasInactiveDialogues(broadcastStreamId)
                    || broadcastRedisUtil.countActiveDialogues(broadcastStreamId) >= redisBroadcastDialogueMaxNum) {
                return compactRemainingDialogues(broadcastStreamId);
            }
        } catch (Exception e) {
            log.error("[BroadcastDialogueCompactionService] compactInternalSync() - Failed | streamId: {}, error: {}", broadcastStreamId, e.getMessage(), e);
            broadcastRedisUtil.clearSummaryInProgress(broadcastStreamId);
            return false;
        }

        log.info("[BroadcastDialogueCompactionService] compactInternalSync() - END | streamId: {}", broadcastStreamId);
        return true;
    }

    private CompactionPreparedData prepareCompaction(String broadcastStreamId) {
        /*
            1. compaction snapshot 확보
            - INACTIVE가 남아 있으면 복구 우선, 없으면 ACTIVE batch를 가져온다.
         */
        BroadcastDialogueCompactionSnapshotDto snapshot = broadcastRedisUtil.getCompactionSnapshot(broadcastStreamId);
        if (snapshot.dialogues().isEmpty()) {
            broadcastRedisUtil.clearSummaryInProgress(broadcastStreamId);
            return null;
        }

        List<BroadcastInfoRedisDto> dialogues = snapshot.dialogues().stream()
                .map(BroadcastDialogueSnapshotItemDto::dialogue)
                .toList();

        /*
            2. DB batch 저장
            - 현재 방송 대화 내용을 요약하려는 순간의 스냅샷의 대화 기록을 체크한다.
            - 대화 기록 리스트가 전부 ACTIVE 상태인 대화라면 먼저 DB에 저장하고 INACTIVE로 변경한다.
         */
        boolean allActive = snapshot.dialogues().stream()
                .allMatch(item -> item.dialogue().dataStatus() != null && item.dialogue().dataStatus() == com.example.sku_sw.domain.broadcast.enums.BroadcastDataStatus.ACTIVE);
        if (allActive) {
            broadcastDialoguePersistenceService.saveDialogues(broadcastStreamId, dialogues);

            List<Integer> indices = snapshot.dialogues().stream()
                    .map(BroadcastDialogueSnapshotItemDto::listIndex)
                    .toList();
            broadcastRedisUtil.markDialoguesInactive(broadcastStreamId, indices);

            snapshot = broadcastRedisUtil.getCompactionSnapshot(broadcastStreamId);
            dialogues = snapshot.dialogues().stream()
                    .map(BroadcastDialogueSnapshotItemDto::dialogue)
                    .toList();
        }

        return new CompactionPreparedData(snapshot.summary(), dialogues);
    }

    private record CompactionPreparedData(
            BroadcastInfoRedisDto summary,
            List<BroadcastInfoRedisDto> dialogues
    ) {
    }
}
