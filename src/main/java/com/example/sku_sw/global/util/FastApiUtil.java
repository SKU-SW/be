package com.example.sku_sw.global.util;

import com.example.sku_sw.domain.broadcast.dto.FastApiTtsReqDto;
import com.example.sku_sw.domain.broadcast.dto.FastApiTtsResDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

/**
 * FastAPI TTS 서버와 통신하는 유틸리티
 * - multipart/form-data 요청을 전송하고 multipart/mixed 응답을 파싱한다.
 * - 응답 형식: 첫 번째 Part = JSON 메타데이터, 두 번째 Part = 바이너리 음성 데이터
 */
@Slf4j
@Component
public class FastApiUtil {

    private final WebClient webClient;
    private final String ttsPath;
    private final ObjectMapper objectMapper;

    public FastApiUtil(
            @Value("${fastapi.base-url}") String baseUrl,
            @Value("${fastapi.tts-path}") String ttsPath,
            @Value("${fastapi.connect-timeout-ms}") Integer connectTimeoutMs,
            @Value("${fastapi.read-timeout-ms}") Integer readTimeoutMs,
            @Value("${fastapi.max-in-memory-size-bytes:1048576}") Integer maxInMemorySizeBytes,
            ObjectMapper objectMapper
    ) {
        this.ttsPath = ttsPath;
        this.objectMapper = objectMapper;

        HttpClient httpClient = HttpClient.create()
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .responseTimeout(Duration.ofMillis(readTimeoutMs));

        ExchangeStrategies exchangeStrategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(maxInMemorySizeBytes))
                .build();

        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(exchangeStrategies)
                .build();
    }

    /**
     * FastAPI TTS 엔드포인트를 호출하여 음성 데이터를 생성한다.
     * - 요청: application/json (broadcastStreamId, characterId, ttsId, voiceText, broadcastDialogueId)
     * - 응답: multipart/form-data (JSON 메타데이터 Part + 음성 바이너리 Part)
     *
     * @param request : TTS 요청 DTO (broadcastStreamId, characterId, ttsId, voiceText, broadcastDialogueId)
     * @return : TTS 응답 DTO (characterId, voiceText, broadcastDialogueId, voiceData byte[])
     */
    public Mono<FastApiTtsResDto> generateTts(FastApiTtsReqDto request) {
        log.info("[FastApiUtil] generateTts() - START | streamId: {}, characterId: {}, textLength: {}",
                request.broadcastStreamId(), request.characterId(),
                request.voiceText() != null ? request.voiceText().length() : 0);

        /*
            1. WebClient로 POST 요청 전송 및 멀티파트 응답 파싱
            - exchangeToMono를 사용하여 응답 상태 코드 및 본문을 직접 처리한다.
            - 요청 본문은 JSON, 응답 Content-Type은 multipart/form-data를 가정한다.
         */
        return webClient.post()
                .uri(ttsPath)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(request)
                .exchangeToMono(response -> {
                    if (response.statusCode().isError()) {
                        return response.bodyToMono(String.class)
                                .flatMap(errorBody -> {
                                    log.error("[FastApiUtil] generateTts() - FastAPI error | status: {}, body: {}",
                                            response.statusCode(), errorBody);
                                    return Mono.error(new RuntimeException(
                                            "FastAPI TTS 호출 실패 - status=" + response.statusCode().value()
                                                    + ", body=" + errorBody));
                                });
                    }
                    MediaType contentType = response.headers().contentType()
                            .orElse(MediaType.APPLICATION_OCTET_STREAM);

                    return response.bodyToMono(byte[].class)
                            .flatMap(body -> parseMultipartResponse(contentType, body));
                })
                .doOnError(error -> log.error("[FastApiUtil] generateTts() - Error | streamId: {}, error: {}",
                        request.broadcastStreamId(), error.getMessage()))
                .doOnSuccess(res -> {
                    if (res != null) {
                        log.info("[FastApiUtil] generateTts() - END | streamId: {}, voiceDataLength: {}",
                                request.broadcastStreamId(), res.voiceData() != null ? res.voiceData().length : 0);
                    }
                });
    }

    /**
     * multipart/form-data 응답 바이트를 파싱하여 FastApiTtsResDto로 변환한다.
     * - metadata Part: JSON 메타데이터 (characterId, voiceText, broadcastDialogueId)
     * - audio Part: 바이너리 음성 데이터 (voiceData)
     *
     * @param contentType : 응답 Content-Type
     * @param body        : 응답 원본 바이트 배열
     * @return : 파싱된 FastApiTtsResDto
     */
    private Mono<FastApiTtsResDto> parseMultipartResponse(MediaType contentType, byte[] body) {
        try {
            String boundary = contentType.getParameter("boundary");
            if (boundary == null || boundary.isBlank()) {
                return Mono.error(new IllegalStateException("FastAPI 응답 boundary가 없습니다."));
            }

            byte[] metadataBytes = extractPartBody(body, boundary, "metadata");
            byte[] audioBytes = extractPartBody(body, boundary, "audio");

            if (metadataBytes == null || audioBytes == null) {
                return Mono.error(new IllegalStateException("FastAPI 응답에 metadata/audio part가 없습니다."));
            }

            String metadataJson = new String(metadataBytes, StandardCharsets.UTF_8);
            JsonNode node = objectMapper.readTree(metadataJson);

            FastApiTtsResDto result = FastApiTtsResDto.builder()
                    .characterId(node.has("characterId") && !node.get("characterId").isNull() ? node.get("characterId").asLong() : null)
                    .voiceText(node.has("voiceText") && !node.get("voiceText").isNull() ? node.get("voiceText").asText() : null)
                    .broadcastDialogueId(node.has("broadcastDialogueId") && !node.get("broadcastDialogueId").isNull() ? node.get("broadcastDialogueId").asLong() : null)
                    .voiceData(audioBytes)
                    .build();

            return Mono.just(result);
        } catch (Exception e) {
            return Mono.error(new RuntimeException("FastAPI multipart 응답 파싱 실패", e));
        }
    }

    /**
     * multipart 응답 바이트에서 지정한 name의 Part body를 추출한다.
     * @param body     : multipart 전체 바이트 배열
     * @param boundary : multipart boundary
     * @param partName : 찾을 Part name
     * @return : Part body 바이트 배열, 없으면 null
     */
    private byte[] extractPartBody(byte[] body, String boundary, String partName) {
        String bodyText = new String(body, StandardCharsets.ISO_8859_1);
        String marker = "--" + boundary;
        String[] rawParts = bodyText.split(marker);

        for (String rawPart : rawParts) {
            if (!rawPart.contains("name=\"" + partName + "\"")) {
                continue;
            }

            int headerEndIndex = rawPart.indexOf("\r\n\r\n");
            if (headerEndIndex < 0) {
                continue;
            }

            String headersAndPrefix = rawPart.substring(0, headerEndIndex + 4);
            byte[] prefixBytes = headersAndPrefix.getBytes(StandardCharsets.ISO_8859_1);
            byte[] partBytes = rawPart.getBytes(StandardCharsets.ISO_8859_1);
            byte[] contentBytes = Arrays.copyOfRange(partBytes, prefixBytes.length, partBytes.length);

            if (contentBytes.length >= 2
                    && contentBytes[contentBytes.length - 2] == '\r'
                    && contentBytes[contentBytes.length - 1] == '\n') {
                contentBytes = Arrays.copyOf(contentBytes, contentBytes.length - 2);
            }

            if (contentBytes.length >= 2
                    && contentBytes[0] == '\r'
                    && contentBytes[1] == '\n') {
                contentBytes = Arrays.copyOfRange(contentBytes, 2, contentBytes.length);
            }

            return contentBytes;
        }

        return null;
    }

    /**
     * FastAPI 응답에서 JSON 메타데이터 Part를 파싱하기 위한 private record
     */
    private record MetadataJson(
            Long characterId,
            String voiceText,
            Long broadcastDialogueId
    ) {}
}
