package com.example.sku_sw.domain.broadcast.service;

import com.example.sku_sw.domain.broadcast.dto.BroadcastInfoRedisDto;
import com.example.sku_sw.domain.broadcast.entity.Broadcast;
import com.example.sku_sw.domain.broadcast.entity.BroadcastDialogue;
import com.example.sku_sw.domain.broadcast.enums.BroadcastErrorCode;
import com.example.sku_sw.domain.broadcast.enums.BroadcastStatus;
import com.example.sku_sw.domain.broadcast.repository.BroadcastDialogueBulkRepository;
import com.example.sku_sw.domain.broadcast.repository.BroadcastDialogueRepository;
import com.example.sku_sw.domain.broadcast.repository.BroadcastRepository;
import com.example.sku_sw.domain.broadcast.util.BroadcastRedisUtil;
import com.example.sku_sw.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class BroadcastDialoguePersistenceService {

    private final BroadcastRepository broadcastRepository;
    private final BroadcastDialogueRepository broadcastDialogueRepository;
    private final BroadcastDialogueBulkRepository broadcastDialogueBulkRepository;
    private final BroadcastRedisUtil broadcastRedisUtil;

    /**
     * 방송 종료 직전 Redis에 남아있는 방송 대화를 DB에 저장하는 함수
     * - Redis summary를 제외한 ACTIVE/INACTIVE 대화를 모두 조회한다.
     * - 실제 저장은 saveDialogues()를 재사용하여 cursorId 기준 중복 저장을 방지한다.
     * @param broadcastStreamId : 방송 스트림 ID
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveRemainingRedisDialogues(String broadcastStreamId) {
        log.info("[BroadcastDialoguePersistenceService] saveRemainingRedisDialogues() - START | streamId: {}", broadcastStreamId);

        /*
            1. Redis 잔여 대화 조회
            - 방송 종료 정리에서 Redis 데이터를 삭제하기 전에 남아있는 대화 데이터를 모두 조회한다.
         */
        List<BroadcastInfoRedisDto> remainingDialogues = broadcastRedisUtil.getRemainingDialogues(broadcastStreamId);

        /*
            2. 잔여 대화 DB 저장
            - saveDialogues() 내부 중복 제거 로직을 통해 이미 저장된 cursorId는 제외한다.
         */
        saveDialogues(broadcastStreamId, remainingDialogues);

        log.info("[BroadcastDialoguePersistenceService] saveRemainingRedisDialogues() - END | streamId: {}, remainingSize: {}",
                broadcastStreamId, remainingDialogues.size());
    }

    /**
     * Redis 방송 대화 batch를 DB에 저장하는 함수
     * - 이미 저장된 cursorId는 제외하고 신규 대화만 저장한다.
     * @param broadcastStreamId : 방송 스트림 ID
     * @param dialogues : 저장할 방송 대화 목록
     */
    @Transactional
    public void saveDialogues(String broadcastStreamId, List<BroadcastInfoRedisDto> dialogues) {
        log.info("[BroadcastDialoguePersistenceService] saveDialogues() - START | streamId: {}, dialogueSize: {}",
                broadcastStreamId, dialogues.size());
        LocalDateTime startTime = LocalDateTime.now();

        if (dialogues.isEmpty()) {
            log.info("[BroadcastDialoguePersistenceService] saveDialogues() - END | streamId: {}, action: skip", broadcastStreamId);
            return;
        }

        /*
            1. 활성 방송 조회
            - streamId 기준으로 현재 방송 엔티티를 조회한다.
         */
        Broadcast broadcast = broadcastRepository.findByStreamIdAndStatus(broadcastStreamId, BroadcastStatus.BROADCASTING)
                .or(() -> broadcastRepository.findByStreamId(broadcastStreamId))
                .orElseThrow(() -> new CustomException(BroadcastErrorCode.BROADCAST_NOT_FOUND));

        /*
            2. 이미 저장된 cursorId 조회
            - 중복 저장을 막기 위해 기존 cursorId를 먼저 조회한다.
         */
        List<Long> cursorIds = dialogues.stream()
                .map(BroadcastInfoRedisDto::cursorId)
                .toList();
        Set<Long> existingCursorIds = new HashSet<>(broadcastDialogueRepository.findByBroadcastIdAndCursorIdIn(broadcast.getId(), cursorIds)
                .stream()
                .map(BroadcastDialogue::getCursorId)
                .toList());

        /*
            3. 신규 대화만 Entity 변환 후 저장
            - 아직 저장되지 않은 cursorId만 batch insert 한다.
         */
        List<BroadcastDialogue> newDialogues = dialogues.stream()
                .filter(dialogue -> !existingCursorIds.contains(dialogue.cursorId()))
                .map(dialogue -> BroadcastDialogue.builder()
                        .cursorId(dialogue.cursorId())
                        .subject(dialogue.subject())
                        .content(dialogue.content())
                        .createdAt(dialogue.createdAt())
                        .broadcast(broadcast)
                        .build())
                .toList();

        if (!newDialogues.isEmpty()) {
            broadcastDialogueBulkRepository.saveAll(newDialogues);
        }

        log.info("[BroadcastDialoguePersistenceService] saveDialogues() - END | streamId: {}, savedSize: {}",
                broadcastStreamId, newDialogues.size());
        log.debug("[BroadcastDialoguePersistenceService] saveDialogues() - Execute Time: {}", Duration.between(startTime, LocalDateTime.now()).toMillis());
    }
}
