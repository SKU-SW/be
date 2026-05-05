package com.example.sku_sw.domain.broadcast.repository;

import com.example.sku_sw.domain.broadcast.entity.BroadcastDialogue;
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
}
