package com.example.sku_sw.domain.broadcast.controller;

import com.example.sku_sw.domain.broadcast.dto.BroadcastDayStatsResDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastMonthResDto;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Tag(name = "Broadcast Stats", description = "방송 통계 관련 API")
@RequestMapping("/api/v1/broadcast/stats")
public interface BroadcastStatsControllerDocs {

    @Operation(summary = "하루 방송 통계 조회", description = """
            하루의 방송 통계 정보를 조회하는 API입니다.
            
            [Request Header]
            - `Authorization: Bearer <Access Token>` 필요
            
            [Query String]
            - `broadcastId`: 조회할 방송 PK
            
            [Request Body]
            - 없음
            
            [Response Body]
            - `characterInfo`: 캐릭터 정보 (name, gender, imageUrl, persona, triggerWords)
            - `broadcastInfo`: 방송 정보 (streamId, status, startedAt, terminatedAt, lastFiveBroadcastDialogues, analysisResult)
              - `analysisResult`: 방송 분석 결과. 분석 데이터가 아직 없으면 null입니다.
                - `majorContent`: 방송의 주 컨텐츠에 대한 설명
                - `majorMoodWithViewers`: 시청자와의 주 분위기를 분석한 결과
                - `summary`: 당일 방송 요약
                - `totalAnalysis`: 방송 최종 분석
                - `catchPhrases`: 반복적으로 사용된 유행어 객체 목록
                  - `content`: 유행어 또는 키워드
                  - `subject`: 유행어 주체 (`STREAMER` | `VIEWER`)
                  - `situationAnalysis`: 유행어가 발생한 상황 설명
                - `timeLines`: 주요 컨텐츠 내용이 확연히 바뀐 방송 구간 목록
                  - `content`: 해당 구간 방송 내용
                  - `startTime`: 구간 시작 시간 (`yyyy-MM-dd HH:mm:ss`)
                  - `endTime`: 구간 종료 시간 (`yyyy-MM-dd HH:mm:ss`)
            - `chatAnalysisInfo`: 채팅 분석 정보
              - `publicOpinion`: 해당 방송 전체 채팅 데이터 기준 여론 현황
                - `positiveChatCount`: 긍정 채팅 수
                - `neutralChatCount`: 중립 채팅 수
                - `negativeChatCount`: 부정 채팅 수
                - `totalChatCount`: 전체 채팅 수
                - `positiveRatio`: 긍정 비율 (%)
                - `neutralRatio`: 중립 비율 (%)
                - `negativeRatio`: 부정 비율 (%)
              - `aiPartnerTendency`: 최종 채팅 여론 분석 결과와 동일한 AI 파트너 응답 성향
              - `sentimentFlow`: 해당 방송 전체 통계를 10분 간격으로 고정 그룹핑한 감정 흐름
                - `timeLabel`: 구간 시작 시각 (`HH:mm`)
                - `positiveRatio`: 구간 긍정 비율 (%)
                - `neutralRatio`: 구간 중립 비율 (%)
                - `negativeRatio`: 구간 부정 비율 (%)
              - `topKeywords`: 해당 방송 전체 데이터 기준 상위 10개 키워드
             
            [예외]
            - 본인 방송이 아니거나 존재하지 않는 방송이면 404 예외가 발생합니다.
            """)
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "하루 방송 통계 조회 성공",
                    content = @Content(schema = @Schema(implementation = BroadcastDayStatsResDto.class))
            ),
            @ApiResponse(responseCode = "401", description = "인증 실패 / 토큰 만료", content = @Content),
            @ApiResponse(responseCode = "403", description = "접근 권한 없음", content = @Content),
            @ApiResponse(responseCode = "404", description = "방송을 찾을 수 없음", content = @Content),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/day")
    ResponseEntity<GlobalResponse<BroadcastDayStatsResDto>> getBroadcastDayStats(
            @Parameter(description = "조회할 방송 PK", required = true)
            @RequestParam Long broadcastId
    );

    @Operation(summary = "달별 방송 기록 조회", description = """
            한 달 동안의 방송 기록을 리스트로 조회하는 API입니다.
            
            [Request Header]
            - `Authorization: Bearer <Access Token>` 필요
            
            [Query String]
            - `year`: 조회할 연도 (예: 2026)
            - `month`: 조회할 월 (1 ~ 12)
            
            [Request Body]
            - 없음
            
            [Response Body]
            - `broadcastMonthInfoList`: 한 달 방송 정보 리스트
              - `day`: 방송 날짜 (일)
              - `broadcastId`: 방송 PK
              - `characterId`: 캐릭터 PK
              - `characterName`: 캐릭터 이름
              - `broadcastStatus`: 방송 상태 (TERMINATED | ABNORMAL_TERMINATED | BROADCASTING)
              - `broadcastTime`: 방송 시간 (HH:MM:SS)
            - `broadcastYear`: 조회한 연도
            - `broadcastMonth`: 조회한 월
            
            [조회 방식]
            - 지정한 연도와 월의 방송 목록을 조회한다.
            - 하루에 여러 번 방송한 경우, 각 방송이 별도의 항목으로 반환된다.
            - 방송 시간은 startedAt ~ terminatedAt(종료되지 않은 방송은 현재 시각)의 차이로 계산된다.
            - 방송 시간의 ms는 반올림되어 HH:MM:SS 형식으로 반환된다.
            """)
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "달별 방송 기록 조회 성공",
                    content = @Content(schema = @Schema(implementation = BroadcastMonthResDto.class))
            ),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 파라미터", content = @Content),
            @ApiResponse(responseCode = "401", description = "인증 실패 / 토큰 만료", content = @Content),
            @ApiResponse(responseCode = "403", description = "접근 권한 없음", content = @Content),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/month")
    ResponseEntity<GlobalResponse<BroadcastMonthResDto>> getBroadcastMonth(
            @Parameter(description = "조회할 연도 (예: 2026)", required = true)
            @RequestParam Integer year,

            @Parameter(description = "조회할 월 (1 ~ 12)", required = true)
            @RequestParam Integer month
    );
}
