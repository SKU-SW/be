package com.example.sku_sw.domain.broadcast.controller;

import com.example.sku_sw.domain.broadcast.dto.BroadcastStartResDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastTerminateResDto;
import com.example.sku_sw.global.response.GlobalResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Tag(name = "Broadcast", description = "방송 관련 API")
@RequestMapping("/api/v1/stream")
public interface BroadcastControllerDocs {

    @Operation(summary = "방송 시작", description = """
            선택한 AI 캐릭터로 방송을 시작하는 API입니다.

            [Request Header]
            - `Authorization: Bearer <Access Token>` 필요

            [Query String]
            - `characterId`: 방송 시작하는 AI 캐릭터 PK

            [Request Body]
            - 없음

            [Response Body]
            - `broadcastStreamId`: 해당 방송 고유 ID
            - `broadcastStartedAt`: 방송 시작 시간

            [예외]
            - 선택하지 않은 AI 캐릭터로 방송 시작 시 예외가 발생합니다.
            - 이미 방송을 진행 중인 AI 캐릭터가 있으면 400 예외가 발생합니다.

            [후속 절차]
            - 실제 방송을 위한 WebSocket 연결은 반환받은 `broadcastStreamId`로 `/api/v1/stream/ws`에 요청해야 합니다.
            """)
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "방송 시작 성공",
                    content = @Content(schema = @Schema(implementation = BroadcastStartResDto.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "잘못된 요청 파라미터 또는 이미 방송을 진행 중인 AI 캐릭터가 있습니다.",
                    content = @Content
            ),
            @ApiResponse(responseCode = "401", description = "인증 실패 / 토큰 만료", content = @Content),
            @ApiResponse(responseCode = "403", description = "접근 권한 없음", content = @Content),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음", content = @Content),
            @ApiResponse(responseCode = "409", description = "리소스 충돌 (중복 등)", content = @Content),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/start")
    ResponseEntity<GlobalResponse<BroadcastStartResDto>> startBroadcast(
            @Parameter(description = "방송 시작하는 AI 캐릭터의 PK", required = true)
            @RequestParam Long characterId
    );

    @Operation(summary = "현재 방송 종료", description = """
            현재 로그인한 사용자의 진행 중인 방송을 종료하는 API입니다.

            [Request Header]
            - `Authorization: Bearer <Access Token>` 필요

            [Request Body]
            - 없음

            [Response Body]
            - `terminatedBroadcastStreamId`: 종료된 방송 고유 ID
            - `broadcastTerminatedAt`: 방송 종료 시간

            [예외]
            - 진행 중인 방송이 없으면 404 예외가 발생합니다.
            """)
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "방송 종료 성공",
                    content = @Content(schema = @Schema(implementation = BroadcastTerminateResDto.class))
            ),
            @ApiResponse(responseCode = "401", description = "인증 실패 / 토큰 만료", content = @Content),
            @ApiResponse(responseCode = "403", description = "접근 권한 없음", content = @Content),
            @ApiResponse(responseCode = "404", description = "진행 중인 방송 없음", content = @Content),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/terminate")
    ResponseEntity<GlobalResponse<BroadcastTerminateResDto>> terminateCurrentBroadcast();

}
