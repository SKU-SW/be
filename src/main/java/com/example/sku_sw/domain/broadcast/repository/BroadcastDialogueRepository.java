package com.example.sku_sw.domain.broadcast.repository;

import com.example.sku_sw.domain.broadcast.entity.BroadcastDialogue;
import com.example.sku_sw.domain.broadcast.enums.DialogueSubject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface BroadcastDialogueRepository extends JpaRepository<BroadcastDialogue, Long> {

    /**
     * 특정 방송에 이미 저장된 cursorId 목록을 조회하는 함수
     * @param broadcastId : 방송 ID
     * @param cursorIds : 조회할 cursorId 목록
     * @return : 이미 저장된 cursorId 목록
     */
    List<BroadcastDialogue> findByBroadcastIdAndCursorIdIn(Long broadcastId, Collection<Long> cursorIds);

    /**
     * 특정 방송의 최신 대화 목록을 cursorId 내림차순으로 조회하는 함수
     * @param broadcastId : 방송 ID
     * @param pageable : 조회 개수 Pageable
     * @return : 최신 대화 목록
     */
    List<BroadcastDialogue> findByBroadcastIdOrderByCursorIdDesc(Long broadcastId, Pageable pageable);

    /**
     * 특정 방송에서 기준 cursorId보다 작은 최신 대화 목록을 cursorId 내림차순으로 조회하는 함수
     * @param broadcastId : 방송 ID
     * @param cursorId : 기준 cursorId
     * @param pageable : 조회 개수 Pageable
     * @return : 대화 목록
     */
    List<BroadcastDialogue> findByBroadcastIdAndCursorIdLessThanOrderByCursorIdDesc(Long broadcastId, Long cursorId, Pageable pageable);

    /**
     * 특정 방송에서 기준 cursorId 이하이면서 대화 주체 필터에 해당하는 최신 대화 목록을 cursorId 내림차순으로 조회하는 함수
     * @param broadcastId : 방송 ID
     * @param cursorId : 기준 cursorId
     * @param subjects : 조회할 대화 주체 목록
     * @param pageable : 조회 개수 Pageable
     * @return : 대화 목록
     */
    List<BroadcastDialogue> findByBroadcastIdAndCursorIdLessThanEqualAndSubjectInOrderByCursorIdDesc(
            Long broadcastId,
            Long cursorId,
            Collection<DialogueSubject> subjects,
            Pageable pageable
    );

    /**
     * 특정 방송에서 기준 cursorId 미만이면서 대화 주체 필터에 해당하는 최신 대화 목록을 cursorId 내림차순으로 조회하는 함수
     * @param broadcastId : 방송 ID
     * @param cursorId : 기준 cursorId
     * @param subjects : 조회할 대화 주체 목록
     * @param pageable : 조회 개수 Pageable
     * @return : 대화 목록
     */
    List<BroadcastDialogue> findByBroadcastIdAndCursorIdLessThanAndSubjectInOrderByCursorIdDesc(
            Long broadcastId,
            Long cursorId,
            Collection<DialogueSubject> subjects,
            Pageable pageable
    );

    /**
     * 특정 방송에서 기준 cursorId보다 작은 대화 존재 여부를 확인하는 함수
     * @param broadcastId : 방송 ID
     * @param cursorId : 기준 cursorId
     * @return : 존재 여부
     */
    boolean existsByBroadcastIdAndCursorIdLessThan(Long broadcastId, Long cursorId);
}
