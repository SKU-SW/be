package com.example.sku_sw.domain.broadcast.controller;

import com.example.sku_sw.domain.broadcast.dto.BroadcastDialogueCursorItemResDto;
import com.example.sku_sw.domain.broadcast.dto.CurrentStreamInfoResDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastStartResDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastTerminateResDto;
import com.example.sku_sw.domain.broadcast.service.BroadcastService;
import com.example.sku_sw.global.response.CursorSliceResponse;
import com.example.sku_sw.global.response.GlobalResponse;
import com.example.sku_sw.global.security.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class BroadcastController implements BroadcastControllerDocs {

    private final BroadcastService broadcastService;

    @Override
    public ResponseEntity<GlobalResponse<BroadcastStartResDto>> startBroadcast(Long characterId) {
        Long userId = SecurityUtil.getCurrentUserId();
        BroadcastStartResDto response = broadcastService.startBroadcast(userId, characterId);
        return ResponseEntity.ok(GlobalResponse.success("방송 시작 성공", response));
    }

    @Override
    public ResponseEntity<GlobalResponse<BroadcastTerminateResDto>> terminateCurrentBroadcast() {
        Long userId = SecurityUtil.getCurrentUserId();
        BroadcastTerminateResDto response = broadcastService.terminateCurrentBroadcast(userId);
        return ResponseEntity.ok(GlobalResponse.success("방송이 성공적으로 종료되었습니다.", response));
    }

    @Override
    public ResponseEntity<GlobalResponse<CurrentStreamInfoResDto>> getCurrentStreamInfo(Integer size) {
        Long userId = SecurityUtil.getCurrentUserId();
        CurrentStreamInfoResDto response = broadcastService.getCurrentStreamInfo(userId, size);
        return ResponseEntity.ok(GlobalResponse.success("현재 진행 중인 방송 정보 조회 성공", response));
    }

    @Override
    public ResponseEntity<GlobalResponse<CursorSliceResponse<BroadcastDialogueCursorItemResDto>>> getBroadcastDialoguesByCursor(
            Integer size,
            Long cursorId,
            Boolean aiCharacterDialogue,
            Boolean streamerDialogue,
            Boolean viewerDialogue
    ) {
        Long userId = SecurityUtil.getCurrentUserId();
        CursorSliceResponse<BroadcastDialogueCursorItemResDto> response = broadcastService.getBroadcastDialoguesByCursor(
                userId,
                size,
                cursorId,
                aiCharacterDialogue,
                streamerDialogue,
                viewerDialogue
        );
        return ResponseEntity.ok(GlobalResponse.success("방송 대화 데이터 조회 성공", response));
    }
}
