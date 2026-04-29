package com.example.sku_sw.global.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

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
     * Gemini API에 텍스트 프롬프트를 전송하여 응답을 받아오는 함수
     * @param prompt 사용자 입력 프롬프트
     * @return Gemini 모델이 생성한 텍스트 응답
     */
    public String generateContent(String prompt) {
        log.info("[START] GeminiUtil.generateContent - prompt length: {}", prompt != null ? prompt.length() : 0);

        GeminiRequest request = new GeminiRequest(
                List.of(new RequestContent(List.of(new RequestPart(prompt))))
        );

        String response = webClient.post()
                .uri("/models/{model}:generateContent?key={key}", model, apiKey)
                .header("Content-Type", "application/json")
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, clientResponse ->
                        clientResponse.bodyToMono(String.class)
                                .map(responseBody -> new IllegalStateException(
                                        "Gemini API 호출 실패 - status="
                                                + clientResponse.statusCode().value()
                                                + ", body="
                                                + responseBody
                                ))
                )
                .bodyToMono(GeminiResponse.class)
                .map(this::extractText)
                .block();

        log.info("[END] GeminiUtil.generateContent - response length: {}",
                response != null ? response.length() : 0);

        return response != null ? response : "";
    }

    private String extractText(GeminiResponse response) {
        if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
            return "";
        }

        Candidate firstCandidate = response.candidates().get(0);
        if (firstCandidate.content() == null
                || firstCandidate.content().parts() == null
                || firstCandidate.content().parts().isEmpty()) {
            return "";
        }

        ResponsePart firstPart = firstCandidate.content().parts().get(0);
        return firstPart.text() != null ? firstPart.text() : "";
    }

    private record GeminiRequest(List<RequestContent> contents) {}

    private record RequestContent(List<RequestPart> parts) {}

    private record RequestPart(String text) {}

    private record GeminiResponse(List<Candidate> candidates) {}

    private record Candidate(Content content) {}

    private record Content(List<ResponsePart> parts) {}

    private record ResponsePart(String text) {}
}
