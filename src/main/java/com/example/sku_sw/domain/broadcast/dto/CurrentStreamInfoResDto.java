package com.example.sku_sw.domain.broadcast.dto;

import com.example.sku_sw.global.response.CursorSliceResponse;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Schema(description = "현재 진행 중인 방송 정보 조회 응답 DTO")
@Builder
public record CurrentStreamInfoResDto(
        @Schema(description = "현재 방송 중인 AI 캐릭터 정보")
        BroadcastCharacterInfoResDto broadcastCharacterInfo,

        @JsonUnwrapped
        @Schema(description = "현재 방송 대화 커서 슬라이스 응답")
        CursorSliceResponse<BroadcastDialogueCursorItemResDto> dialogueSlice
) {
}
