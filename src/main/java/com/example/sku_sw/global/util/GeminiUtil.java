package com.example.sku_sw.global.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class GeminiUtil {
    private final WebClient webClient;
    private final String apiKey;
    private final String model;

    public GeminiUtil(
            @Value("${gemini.api.key}") String apiKey,
            @Value("${gemini.api.model}") String model,
            @Value("${gemini.api.base-url}") String baseUrl,
            WebClient.Builder webClientBuilder
    ) {
        this.apiKey = apiKey;
        this.model = model;
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
    }
    /**
     * Gemini Function Calling API를 호출하는 함수
     * - tools/functionDeclarations에 ignore_streamer_sentence 함수를 정의하여 전송한다.
     * - toolConfig.mode=AUTO로 설정하여 모델이 자동으로 함수 호출 여부를 결정한다.
     * @param prompt : 전송할 프롬프트
     * @return : Function Call 결과 DTO (Mono)
     */
    public Mono<GeminiFunctionCallingResponseDto> generateContentWithFunctionCalling(String prompt) {
        GeminiFunctionCallingRequest request = new GeminiFunctionCallingRequest(
                List.of(new RequestContent(List.of(new RequestPart(prompt)))),
                List.of(new Tool(List.of(new FunctionDeclaration(
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
                new ToolConfig(new FunctionCallingConfig("AUTO"))
        );

        return webClient.post()
                .uri("/models/{model}:generateContent?key={key}", model, apiKey) // 1. URI 설정
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
                .bodyToMono(GeminiFunctionCallingResponse.class) // 6. 응답을 Mono로 변환
                .map(this::extractFunctionCallingResult); // 7. Mono 변환 함수 지정 (아직 요청 안함)
    }

    /**
     * Gemini Function Calling 응답에서 functionCall/text 추출
     * - candidates[0].content.parts[*]를 순회하며 functionCall 존재 여부를 확인한다.
     * - functionCall이 있으면 functionCalled=true 반환
     * - 없으면 모든 text를 합쳐서 반환
     */
    private GeminiFunctionCallingResponseDto extractFunctionCallingResult(GeminiFunctionCallingResponse response) {
        if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
            log.warn("[GeminiUtil] extractFunctionCallingResult() - Empty response");
            return new GeminiFunctionCallingResponseDto(false, "");
        }

        FunctionCallCandidate firstCandidate = response.candidates().get(0);
        if (firstCandidate.content() == null || firstCandidate.content().parts() == null
                || firstCandidate.content().parts().isEmpty()) {
            log.warn("[GeminiUtil] extractFunctionCallingResult() - Empty candidate content");
            return new GeminiFunctionCallingResponseDto(false, "");
        }

        boolean hasFunctionCall = false;
        StringBuilder textBuilder = new StringBuilder();

        for (FunctionCallResponsePart part : firstCandidate.content().parts()) {
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

    // --- 기존 private records ---

    private record GeminiRequest(List<RequestContent> contents) {}

    // --- Function Calling 전용 request records ---

    private record GeminiFunctionCallingRequest(
            List<RequestContent> contents,
            List<Tool> tools,
            ToolConfig toolConfig
    ) {}

    private record Tool(List<FunctionDeclaration> functionDeclarations) {}

    private record FunctionDeclaration(String name, String description, Map<String, Object> parameters) {}

    private record ToolConfig(FunctionCallingConfig functionCallingConfig) {}

    private record FunctionCallingConfig(String mode) {}

    // --- Function Calling 전용 response records ---

    private record GeminiFunctionCallingResponse(List<FunctionCallCandidate> candidates) {}

    private record FunctionCallCandidate(FunctionCallContent content) {}

    private record FunctionCallContent(List<FunctionCallResponsePart> parts) {}

    private record FunctionCallResponsePart(String text, FunctionCall functionCall) {}

    private record FunctionCall(String name, Map<String, Object> args) {}

    // --- 기존 private records ---

    private record RequestContent(List<RequestPart> parts) {}

    private record RequestPart(String text) {}

    private record GeminiResponse(List<Candidate> candidates) {}

    private record Candidate(Content content) {}

    private record Content(List<ResponsePart> parts) {}

    private record ResponsePart(String text) {}
}
