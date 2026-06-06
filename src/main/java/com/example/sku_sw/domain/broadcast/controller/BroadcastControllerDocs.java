package com.example.sku_sw.domain.broadcast.controller;

import com.example.sku_sw.domain.broadcast.dto.BroadcastChatStatsResDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastDialogueCursorItemResDto;
import com.example.sku_sw.domain.broadcast.dto.CurrentStreamInfoResDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastStartResDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastTerminateResDto;
import com.example.sku_sw.global.response.CursorSliceResponse;
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
import org.springframework.web.bind.annotation.GetMapping;
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

    @Operation(summary = "현재 진행 중인 방송 정보 조회", description = """
            현재 로그인한 스트리머의 진행 중인 방송 정보를 조회하는 API입니다.

            [Request Header]
            - `Authorization: Bearer <Access Token>` 필요

            [Query String]
            - `size`: 조회할 최신 방송 대화 데이터 개수

            [Request Body]
            - 없음

            [Response Body]
            - `broadcastCharacterInfo`: 현재 방송 중인 AI 캐릭터 정보
            - `content`: 최신 방송 대화 데이터 리스트
            - `size`: 요청한 조회 크기
            - `hasNext`: 다음 데이터 존재 여부
            - `nextCursor`: 이후 대화 조회 API에서 사용할 다음 cursor

            [조회 방식]
            - 최신 대화는 Redis에서 우선 조회합니다.
            - Redis 데이터가 부족하면 DB에서 부족한 개수만큼 추가 조회합니다.
            """)
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "현재 진행 중인 방송 정보 조회 성공",
                    content = @Content(schema = @Schema(implementation = CurrentStreamInfoResDto.class))
            ),
            @ApiResponse(responseCode = "401", description = "인증 실패 / 토큰 만료", content = @Content),
            @ApiResponse(responseCode = "403", description = "접근 권한 없음", content = @Content),
            @ApiResponse(responseCode = "404", description = "진행 중인 방송 없음", content = @Content),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/info")
    ResponseEntity<GlobalResponse<CurrentStreamInfoResDto>> getCurrentStreamInfo(
            @Parameter(description = "조회할 최신 방송 대화 데이터 개수", required = true)
            @RequestParam(defaultValue = "10") Integer size
    );

    @Operation(summary = "방송 채팅 통계 조회", description = """
            현재 로그인한 스트리머의 진행 중인 방송 채팅 통계를 조회하는 API입니다.

            [Request Header]
            - `Authorization: Bearer <Access Token>` 필요

            [Request Body]
            - 없음

            [Response Body]
            - `publicOpinion`: 여론 현황 (긍정/중립/부정 채팅 수 및 비율)
            - `aiPartnerTendency`: AI 파트너 응답 성향 (POSITIVE/NEUTRAL/NEGATIVE)

            [조회 방식]
            - 최근 10분 동안의 BroadcastStats 데이터를 기반으로 통계를 계산합니다.
            - AI 파트너 성향은 가장 높은 비율의 여론으로 판별됩니다.

            [예외]
            - 진행 중인 방송이 없으면 404 예외가 발생합니다.
            """)
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "방송 채팅 통계 조회 성공",
                    content = @Content(schema = @Schema(implementation = BroadcastChatStatsResDto.class))
            ),
            @ApiResponse(responseCode = "401", description = "인증 실패 / 토큰 만료", content = @Content),
            @ApiResponse(responseCode = "403", description = "접근 권한 없음", content = @Content),
            @ApiResponse(responseCode = "404", description = "진행 중인 방송 없음", content = @Content),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/chat/stats")
    ResponseEntity<GlobalResponse<BroadcastChatStatsResDto>> getBroadcastChatStats();

    @Operation(summary = "현재 방송 대화 cursor 조회", description = """
            현재 로그인한 스트리머의 진행 중인 방송이 있다면, cursorId를 기반으로 과거 방송 대화 데이터를 조회하는 API입니다.

            [Request Header]
            - `Authorization: Bearer <Access Token>` 필요

            [Query String]
            - `size`: 조회할 방송 대화 데이터 개수
            - `cursorId`: 조회를 시작할 기준 cursor 데이터
            - `aiCharacterDialogue`: AI 캐릭터 대화 조회 여부
            - `streamerDialogue`: 스트리머 대화 조회 여부
            - `viewerDialogue`: 시청자 채팅 조회 여부

            [Request Body]
            - 없음

            [Response Body]
            - `content`: 조회된 대화 데이터 리스트
            - `size`: 요청한 조회 크기
            - `hasNext`: 다음 데이터 존재 여부
            - `nextCursor`: 다음 조회용 cursor

            [조회 방식]
            - `cursorId` 이하의 대화부터 조회합니다.
            - Redis에서 우선 조회하고 부족하면 DB에서 추가 조회합니다.
            - `hasNext`, `nextCursor` 계산을 위해 요청 개수보다 1개 더 조회합니다.
            """)
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "방송 대화 데이터 조회 성공",
                    content = @Content(schema = @Schema(implementation = CursorSliceResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 파라미터 또는 잘못된 대화 필터", content = @Content),
            @ApiResponse(responseCode = "401", description = "인증 실패 / 토큰 만료", content = @Content),
            @ApiResponse(responseCode = "403", description = "접근 권한 없음", content = @Content),
            @ApiResponse(responseCode = "404", description = "진행 중인 방송 없음", content = @Content),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/info/dialogues")
    ResponseEntity<GlobalResponse<CursorSliceResponse<BroadcastDialogueCursorItemResDto>>> getBroadcastDialoguesByCursor(
            @Parameter(description = "조회할 방송 대화 데이터 개수", required = true)
            @RequestParam Integer size,

            @Parameter(description = "조회 시작 기준 cursorId", required = true)
            @RequestParam Long cursorId,

            @Parameter(description = "AI 캐릭터 대화 조회 여부", required = true)
            @RequestParam Boolean aiCharacterDialogue,

            @Parameter(description = "스트리머 대화 조회 여부", required = true)
            @RequestParam Boolean streamerDialogue,

            @Parameter(description = "시청자 채팅 조회 여부", required = true)
            @RequestParam Boolean viewerDialogue
    );

}
