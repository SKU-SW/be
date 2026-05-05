package com.example.sku_sw.domain.broadcast.service;

import com.example.sku_sw.domain.broadcast.dto.BroadcastInfoRedisDto;
import com.example.sku_sw.domain.broadcast.entity.Broadcast;
import com.example.sku_sw.domain.broadcast.entity.BroadcastDialogue;
import com.example.sku_sw.domain.broadcast.enums.BroadcastErrorCode;
import com.example.sku_sw.domain.broadcast.enums.BroadcastStatus;
import com.example.sku_sw.domain.broadcast.repository.BroadcastDialogueRepository;
import com.example.sku_sw.domain.broadcast.repository.BroadcastRepository;
import com.example.sku_sw.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class BroadcastDialoguePersistenceService {

    private final BroadcastRepository broadcastRepository;
    private final BroadcastDialogueRepository broadcastDialogueRepository;

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
            broadcastDialogueRepository.saveAll(newDialogues);
        }

        log.info("[BroadcastDialoguePersistenceService] saveDialogues() - END | streamId: {}, savedSize: {}",
                broadcastStreamId, newDialogues.size());
    }
}
