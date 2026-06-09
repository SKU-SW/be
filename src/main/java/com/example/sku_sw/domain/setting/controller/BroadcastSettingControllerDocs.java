package com.example.sku_sw.domain.setting.controller;

import com.example.sku_sw.domain.setting.dto.AiProactiveUpdateReqDto;
import com.example.sku_sw.domain.setting.dto.BroadcastSettingResDto;
import com.example.sku_sw.global.response.GlobalResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

@Tag(name = "Broadcast Setting", description = "방송 설정 관련 API")
@RequestMapping("/api/v1/broadcast/settings")
public interface BroadcastSettingControllerDocs {

    @Operation(summary = "방송 설정 조회", description = """
            현재 로그인한 사용자의 방송 설정을 조회하는 API입니다.
            
            [Request Header]
            - `Authorization: Bearer <Access Token>` 필요
            
            [Request Body]
            - 없음
            
            [Response Body]
            - `aiProactiveToChat`: AI가 채팅에 선제 반응을 하는지 여부 (true/false)
            
            [예외]
            - 방송 설정이 존재하지 않으면 404 예외가 발생합니다.
            """)
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "방송 설정 조회 성공",
                    content = @Content(schema = @Schema(implementation = BroadcastSettingResDto.class))
            ),
            @ApiResponse(responseCode = "401", description = "인증 실패 / 토큰 만료", content = @Content),
            @ApiResponse(responseCode = "403", description = "접근 권한 없음", content = @Content),
            @ApiResponse(responseCode = "404", description = "방송 설정을 찾을 수 없습니다.", content = @Content),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("")
    ResponseEntity<GlobalResponse<BroadcastSettingResDto>> getBroadcastSetting();

    @Operation(summary = "AI 채팅 선제 반응 설정/해제", description = """
            현재 로그인한 사용자의 AI 채팅 선제 반응 설정을 멱등하게 수정하는 API입니다.
            
            [Request Header]
            - `Authorization: Bearer <Access Token>` 필요
            
            [Request Body]
            - `aiProactiveToChat`: AI가 채팅에 선제 반응을 할지 여부 (true/false)
            
            [Response Body]
            - `aiProactiveToChat`: 수정된 AI 선제 반응 설정 값
            
            [멱등성]
            - 같은 값으로 여러 번 호출해도 결과가 동일합니다.
            
            [예외]
            - 방송 설정이 존재하지 않으면 404 예외가 발생합니다.
            """)
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "AI 채팅 선제 반응 설정 수정 성공",
                    content = @Content(schema = @Schema(implementation = BroadcastSettingResDto.class))
            ),
            @ApiResponse(responseCode = "400", description = "잘못된 요청", content = @Content),
            @ApiResponse(responseCode = "401", description = "인증 실패 / 토큰 만료", content = @Content),
            @ApiResponse(responseCode = "403", description = "접근 권한 없음", content = @Content),
            @ApiResponse(responseCode = "404", description = "방송 설정을 찾을 수 없습니다.", content = @Content),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping("/ai-proactive")
    ResponseEntity<GlobalResponse<BroadcastSettingResDto>> updateAiProactiveToChat(
            @RequestBody @Valid AiProactiveUpdateReqDto reqDto
    );

    @Operation(summary = "방송 설정 초기화", description = """
            현재 로그인한 사용자의 방송 설정을 초기값으로 초기화하는 API입니다.
            
            [Request Header]
            - `Authorization: Bearer <Access Token>` 필요
            
            [Request Body]
            - 없음
            
            [Response Body]
            - `aiProactiveToChat`: 초기화된 AI 선제 반응 설정 값 (true)
            
            [초기값]
            - `aiProactiveToChat`: true
            
            [동작]
            - 방송 설정이 존재하면 초기값으로 되돌리고, 존재하지 않으면 새로 생성합니다.
            """)
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "방송 설정 초기화 성공",
                    content = @Content(schema = @Schema(implementation = BroadcastSettingResDto.class))
            ),
            @ApiResponse(responseCode = "401", description = "인증 실패 / 토큰 만료", content = @Content),
            @ApiResponse(responseCode = "403", description = "접근 권한 없음", content = @Content),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping("/init")
    ResponseEntity<GlobalResponse<BroadcastSettingResDto>> initBroadcastSetting();
}
