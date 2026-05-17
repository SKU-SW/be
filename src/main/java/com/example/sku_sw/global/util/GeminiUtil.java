package com.example.sku_sw.global.util;

import com.example.sku_sw.global.util.dto.gemini.common.GeminiGenerateContentReqDto;
import com.example.sku_sw.global.util.dto.gemini.common.GeminiGenerateContentResDto;
import com.example.sku_sw.global.util.dto.gemini.common.GeminiRequestContentDto;
import com.example.sku_sw.global.util.dto.gemini.common.GeminiRequestPartDto;
import com.example.sku_sw.global.util.dto.gemini.common.GeminiResponseCandidateDto;
import com.example.sku_sw.global.util.dto.gemini.common.GeminiResponsePartDto;
import com.example.sku_sw.global.util.dto.gemini.functioncall.GeminiFunctionCallDto;
import com.example.sku_sw.global.util.dto.gemini.functioncall.GeminiFunctionCallingCandidateDto;
import com.example.sku_sw.global.util.dto.gemini.functioncall.GeminiFunctionCallingConfigDto;
import com.example.sku_sw.global.util.dto.gemini.functioncall.GeminiFunctionCallingPartDto;
import com.example.sku_sw.global.util.dto.gemini.functioncall.GeminiFunctionCallingReqDto;
import com.example.sku_sw.global.util.dto.gemini.functioncall.GeminiFunctionCallingResDto;
import com.example.sku_sw.global.util.dto.gemini.functioncall.GeminiFunctionCallingResponseDto;
import com.example.sku_sw.global.util.dto.gemini.functioncall.GeminiFunctionDeclarationDto;
import com.example.sku_sw.global.util.dto.gemini.functioncall.GeminiToolConfigDto;
import com.example.sku_sw.global.util.dto.gemini.functioncall.GeminiToolDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class GeminiUtil {
    private static final String EMPTY_BROADCAST_SUMMARY = "(오늘 방송 요약 없음)";

    private final WebClient webClient;
    private final String apiKey;
    private final String dialogueModel;
    private final String broadcastSummaryModel;

    public GeminiUtil(
            @Value("${gemini.api.key}") String apiKey,
            @Value("${gemini.api.dialogue-model}") String dialogueModel,
            @Value("${gemini.api.broadcast-summary-model}") String broadcastSummaryModel,
            @Value("${gemini.api.base-url}") String baseUrl,
            WebClient.Builder webClientBuilder
    ) {
        this.apiKey = apiKey;
        this.dialogueModel = dialogueModel;
        this.broadcastSummaryModel = broadcastSummaryModel;
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
    }
    /**
     * Gemini Function Calling API를 호출하는 함수
     * - tools/functionDeclarations에 ignore_streamer_sentence 함수를 정의하여 전송한다.
     * - toolConfig.mode=AUTO로 설정하여 모델이 자동으로 함수 호출 여부를 결정한다.
     * @param prompt : 전송할 프롬프트
     * @return : Function Call 결과 DTO (Mono)
     */
    public Mono<GeminiFunctionCallingResponseDto> generateDialogueWithFunctionCalling(String prompt) {
        GeminiFunctionCallingReqDto request = new GeminiFunctionCallingReqDto(
                List.of(new GeminiRequestContentDto(List.of(new GeminiRequestPartDto(prompt)))),
                List.of(new GeminiToolDto(List.of(new GeminiFunctionDeclarationDto(
                        "ignore_streamer_sentence",
                        "스트리머가 방금 한 문장이 문맥상 AI 캐릭터에게 말한 내용이 아니면 이 함수를 호출합니다.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "reason", Map.of(
                                                "type", "string",
                                                "description", "AI 캐릭터에게 말한 것이 아니라고 판단한 이유"
                                        )
                                ),
                                "required", List.of("reason")
                        )
                )))),
                new GeminiToolConfigDto(new GeminiFunctionCallingConfigDto("AUTO"))
        );

        return webClient.post()
                .uri("/models/{model}:generateContent?key={key}", dialogueModel, apiKey) // 1. URI 설정
                .header("Content-Type", "application/json")// 2. 헤더 설정
                .bodyValue(request) // 3. 요청 본문 설정
                .retrieve() // 4. 응답 처리 방식 지정 (아직 요청 안함)
                .onStatus(HttpStatusCode::isError, clientResponse ->
                        clientResponse.bodyToMono(String.class)
                                .map(responseBody -> new IllegalStateException(
                                        "Gemini Function Calling API 호출 실패 - status="
                                                + clientResponse.statusCode().value()
                                                + ", body="
                                                + responseBody
                                ))
                ) // 5. 에러 핸들링 설정
                .bodyToMono(GeminiFunctionCallingResDto.class) // 6. 응답을 Mono로 변환
                .map(this::extractFunctionCallingResult); // 7. Mono 변환 함수 지정 (아직 요청 안함)
    }

    /**
     * 방송 요약용 Gemini API를 호출하는 함수
     * @param prompt : 요약 프롬프트
     * @return : summary text Mono
     */
    public Mono<String> summarizeDialogues(String prompt) {
        GeminiGenerateContentReqDto request = new GeminiGenerateContentReqDto(
                List.of(new GeminiRequestContentDto(List.of(new GeminiRequestPartDto(prompt))))
        );

        return webClient.post()
                .uri("/models/{model}:generateContent?key={key}", broadcastSummaryModel, apiKey)
                .header("Content-Type", "application/json")
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, clientResponse ->
                        clientResponse.bodyToMono(String.class)
                                .map(responseBody -> new IllegalStateException(
                                        "Broadcast Summary AI API 호출 실패 - status="
                                                + clientResponse.statusCode().value()
                                                + ", body="
                                                + responseBody
                                ))
                )
                .bodyToMono(GeminiGenerateContentResDto.class)
                .map(this::extractText);
    }

    /**
     * Gemini WebSocket 세션을 조용히 종료하는 함수
     * @param geminiSession : 종료할 Gemini WebSocket 세션
     */
    public void closeGeminiSessionQuietly(WebSocketSession geminiSession) {
        if (geminiSession == null || !geminiSession.isOpen()) {
            return;
        }

        try {
            geminiSession.close(CloseStatus.SERVER_ERROR.withReason("Gemini setup failed"));
        } catch (IOException e) {
            log.warn("[GeminiUtil] closeGeminiSessionQuietly() - Failed to close Gemini session | error: {}", e.getMessage());
        }
    }

    /**
     * Gemini Function Calling 응답에서 functionCall/text 추출
     * - candidates[0].content.parts[*]를 순회하며 functionCall 존재 여부를 확인한다.
     * - functionCall이 있으면 functionCalled=true 반환
     * - 없으면 모든 text를 합쳐서 반환
     */
    private GeminiFunctionCallingResponseDto extractFunctionCallingResult(GeminiFunctionCallingResDto response) {
        if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
            log.warn("[GeminiUtil] extractFunctionCallingResult() - Empty response");
            return new GeminiFunctionCallingResponseDto(false, "");
        }

        GeminiFunctionCallingCandidateDto firstCandidate = response.candidates().get(0);
        if (firstCandidate.content() == null || firstCandidate.content().parts() == null
                || firstCandidate.content().parts().isEmpty()) {
            log.warn("[GeminiUtil] extractFunctionCallingResult() - Empty candidate content");
            return new GeminiFunctionCallingResponseDto(false, "");
        }

        boolean hasFunctionCall = false;
        StringBuilder textBuilder = new StringBuilder();

        for (GeminiFunctionCallingPartDto part : firstCandidate.content().parts()) {
            if (part.functionCall() != null) {
                hasFunctionCall = true;
                break;
            }
            if (part.text() != null) {
                textBuilder.append(part.text());
            }
        }

        if (hasFunctionCall) {
            log.debug("[GeminiUtil] extractFunctionCallingResult() - Function call detected");
            return new GeminiFunctionCallingResponseDto(true, null);
        }

        String resultText = textBuilder.toString();
        log.debug("[GeminiUtil] extractFunctionCallingResult() - Text response, length: {}", resultText.length());
        return new GeminiFunctionCallingResponseDto(false, resultText);
    }

    // Gemini Response에서 Text를 추출하는 함수
    private String extractText(GeminiGenerateContentResDto response) {
        if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
            log.warn("[GeminiUtil] extractText() - Empty response");
            return EMPTY_BROADCAST_SUMMARY;
        }

        GeminiResponseCandidateDto firstCandidate = response.candidates().get(0);
        if (firstCandidate.content() == null || firstCandidate.content().parts() == null
                || firstCandidate.content().parts().isEmpty()) {
            log.warn("[GeminiUtil] extractText() - Empty candidate content");
            return EMPTY_BROADCAST_SUMMARY;
        }

        StringBuilder textBuilder = new StringBuilder();
        for (GeminiResponsePartDto part : firstCandidate.content().parts()) {
            if (part.text() != null) {
                textBuilder.append(part.text());
            }
        }

        String result = textBuilder.toString().trim();
        return result.isBlank() ? EMPTY_BROADCAST_SUMMARY : result;
    }
}
