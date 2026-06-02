package com.example.sku_sw.domain.user.controller;

import com.example.sku_sw.domain.user.dto.ChzzkAuthStatusResDto;
import com.example.sku_sw.global.response.GlobalResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Tag(name = "User", description = "사용자 관련 API")
@RequestMapping("/api/v1/user")
public interface UserControllerDocs {

    @Operation(summary = "치지직 인증 여부 조회", description = "현재 로그인한 사용자의 치지직 API 인증 상태를 조회합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = ChzzkAuthStatusResDto.class))),
            @ApiResponse(responseCode = "401", description = "인증이 필요합니다.",
                    content = @Content),
            @ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없습니다.",
                    content = @Content)
    })
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/chzzk")
    ResponseEntity<GlobalResponse<ChzzkAuthStatusResDto>> getChzzkAuthStatus();
}
