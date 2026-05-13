package com.example.sku_sw.domain.broadcast.service;

import com.example.sku_sw.domain.broadcast.dto.BroadcastCharacterRedisDto;
import com.example.sku_sw.domain.broadcast.dto.BroadcastInfoRedisDto;
import com.example.sku_sw.domain.broadcast.util.BroadcastPromptBuilder;
import com.example.sku_sw.global.util.GeminiUtil;
import com.example.sku_sw.global.util.dto.gemini.functioncall.GeminiFunctionCallingResponseDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 방송 Gemini AI 응답 처리 서비스
 * - BroadcastPromptBuilder로 프롬프트를 생성하고 GeminiUtil의 Function Calling API를 호출한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BroadcastGeminiService {

    private final GeminiUtil geminiUtil;
    private final BroadcastPromptBuilder broadcastPromptBuilder;

    /**
     * 클라이언트 메시지에 대한 Gemini AI 응답을 비동기로 요청한다.
     * - 캐릭터 정보, 최근 방송 내역, 클라이언트 메시지를 조합하여 프롬프트를 생성한다.
     * - Gemini Function Calling API를 호출하여 응답을 받아온다.
     *
     * @param character     : 방송 캐릭터 정보 DTO
     * @param summary       : 방송 요약 데이터
     * @param recentActiveInfos   : 최근 방송 대화 내역 목록
     * @param clientMessage : 클라이언트가 보낸 메시지
     * @return : Gemini Function Calling 결과 DTO (Mono)
     */
    public Mono<GeminiFunctionCallingResponseDto> processClientMessage(
            BroadcastCharacterRedisDto character,
            BroadcastInfoRedisDto summary,
            List<BroadcastInfoRedisDto> recentActiveInfos,
            String clientMessage
    ) {
        log.info("[BroadcastGeminiService] processClientMessage() - START | characterId: {}", character.getCharacterId());

        /*
            1. 프롬프트 생성
            - BroadcastPromptBuilder로 캐릭터 정보, 방송 내역, 클라이언트 메시지를 조합한다.
         */
        String prompt = broadcastPromptBuilder.buildPrompt(character, summary, recentActiveInfos, clientMessage);

        /*
            2. Gemini Function Calling API 호출
            - 생성된 프롬프트로 Gemini API를 비동기 호출한다.
         */
        Mono<GeminiFunctionCallingResponseDto> result = geminiUtil.generateDialogueWithFunctionCalling(prompt)
                .doOnNext(response -> {
                    if (response.functionCalled()) {
                        log.info("[BroadcastGeminiService] processClientMessage() - Function call detected");
                    } else {
                        log.info("[BroadcastGeminiService] processClientMessage() - Text response received, length: {}",
                                response.text() != null ? response.text().length() : 0);
                    }
                })
                .doOnError(error ->
                        log.error("[BroadcastGeminiService] processClientMessage() - Gemini API error: {}", error.getMessage())
                );

        log.info("[BroadcastGeminiService] processClientMessage() - END");
        return result;
    }
}
