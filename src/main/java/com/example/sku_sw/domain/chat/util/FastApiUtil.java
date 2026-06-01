package com.example.sku_sw.domain.chat.util;

import com.example.sku_sw.domain.chat.dto.FastApiChzzkRedisChannelReqDto;
import com.example.sku_sw.domain.chat.dto.FastApiChzzkRedisChannelResDto;
import com.example.sku_sw.domain.chat.dto.FastApiChzzkSessionCreateReqDto;
import com.example.sku_sw.domain.chat.dto.FastApiChzzkSessionCreateResDto;
import com.example.sku_sw.domain.broadcast.enums.BroadcastErrorCode;
import com.example.sku_sw.global.exception.CustomException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * FastAPI 서버와 통신하는 유틸리티
 * - 방송 시작 시 치지직 세션 연결을 요청한다.
 */
@Slf4j
@Component
public class FastApiUtil {

    private final WebClient webClient;
    private final String chzzkSessionConnectPath;
    private final String chzzkChannelConnectPath;
    private final String chzzkChannelDisconnectPath;

    public FastApiUtil(
            @Value("${fastapi.base-url}") String baseUrl,
            @Value("${fastapi.chzzk-session-connect-path}") String chzzkSessionConnectPath,
            @Value("${fastapi.chzzk-channel-connect-path}") String chzzkChannelConnectPath,
            @Value("${fastapi.chzzk-channel-disconnect-path}") String chzzkChannelDisconnectPath,
            @Value("${fastapi.connect-timeout-ms}") Integer connectTimeoutMs,
            @Value("${fastapi.read-timeout-ms}") Integer readTimeoutMs
    ) {
        this.chzzkSessionConnectPath = chzzkSessionConnectPath;
        this.chzzkChannelConnectPath = chzzkChannelConnectPath;
        this.chzzkChannelDisconnectPath = chzzkChannelDisconnectPath;

        HttpClient httpClient = HttpClient.create()
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .responseTimeout(Duration.ofMillis(readTimeoutMs));

        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    /**
     * FastAPI에 치지직 세션 연결을 요청하는 함수
     * - 방송 streamId, attemptId, accessToken을 전달한다.
     * - FastAPI가 세션 생성/핸드셰이크/채팅 구독을 완료하면 응답을 반환한다.
     * @param request : FastAPI 치지직 세션 연결 요청 DTO
     * @return : FastAPI 치지직 세션 연결 응답 DTO
     */
    public Mono<FastApiChzzkSessionCreateResDto> createChzzkSession(FastApiChzzkSessionCreateReqDto request) {
        log.info("[FastApiUtil] createChzzkSession() - START | streamId: {}, attemptId: {}",
                request.broadcastStreamId(), request.attemptId());

        return webClient.post()
                .uri(chzzkSessionConnectPath)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, clientResponse ->
                        clientResponse.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(errorBody -> {
                                    log.error("[FastApiUtil] createChzzkSession() - FastAPI error | status: {}, body: {}",
                                            clientResponse.statusCode(), errorBody);
                                    return new CustomException(BroadcastErrorCode.CHZZK_SESSION_CONNECT_FAILED);
                                })
                )
                .bodyToMono(FastApiChzzkSessionCreateResDto.class)
                .switchIfEmpty(Mono.error(new CustomException(BroadcastErrorCode.CHZZK_SESSION_RESPONSE_INVALID)))
                .doOnSuccess(response -> {
                    if (response != null) {
                        log.info("[FastApiUtil] createChzzkSession() - END | streamId: {}, sessionKey: {}, channelId: {}",
                                response.broadcastStreamId(), response.sessionKey(), response.channelId());
                    }
                })
                .onErrorMap(CustomException.class, error -> error)
                .onErrorMap(error -> !(error instanceof CustomException), error -> {
                    log.error("[FastApiUtil] createChzzkSession() - Error | streamId: {}, attemptId: {}, error: {}",
                            request.broadcastStreamId(), request.attemptId(), error.getMessage());
                    return new CustomException(BroadcastErrorCode.CHZZK_SESSION_CONNECT_FAILED);
                });
    }

    public FastApiChzzkRedisChannelResDto connectChzzkRedisChannel(FastApiChzzkRedisChannelReqDto reqDto) {
        return requestChzzkRedisChannel(reqDto, chzzkChannelConnectPath, BroadcastErrorCode.CHZZK_REDIS_CHANNEL_CONNECT_FAILED);
    }

    public FastApiChzzkRedisChannelResDto disconnectChzzkRedisChannel(FastApiChzzkRedisChannelReqDto reqDto) {
        return requestChzzkRedisChannel(reqDto, chzzkChannelDisconnectPath, BroadcastErrorCode.CHZZK_REDIS_CHANNEL_DISCONNECT_FAILED);
    }

    private FastApiChzzkRedisChannelResDto requestChzzkRedisChannel(
            FastApiChzzkRedisChannelReqDto reqDto,
            String path,
            BroadcastErrorCode errorCode
    ) {
        try {
            FastApiChzzkRedisChannelResDto response = webClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(reqDto)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, clientResponse ->
                            clientResponse.bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .map(errorBody -> {
                                        log.error("[FastApiUtil] requestChzzkRedisChannel() - FastAPI error | path: {}, status: {}, body: {}",
                                                path, clientResponse.statusCode(), errorBody);
                                        return new CustomException(BroadcastErrorCode.CHZZK_REDIS_CHANNEL_RESPONSE_INVALID);
                                    })
                    )
                    .bodyToMono(FastApiChzzkRedisChannelResDto.class)
                    .block();

            validateRedisChannelResponse(reqDto, response);
            return response;
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            log.error("[FastApiUtil] requestChzzkRedisChannel() - Error | path: {}, streamId: {}, error: {}",
                    path, reqDto.broadcastStreamId(), e.getMessage(), e);
            throw new CustomException(errorCode);
        }
    }

    private void validateRedisChannelResponse(
            FastApiChzzkRedisChannelReqDto reqDto,
            FastApiChzzkRedisChannelResDto response
    ) {
        if (response == null) {
            throw new CustomException(BroadcastErrorCode.CHZZK_REDIS_CHANNEL_RESPONSE_INVALID);
        }
        if (!reqDto.broadcastStreamId().equals(response.broadcastStreamId())
                || !reqDto.sessionKey().equals(response.sessionKey())
                || !reqDto.channelName().equals(response.channelName())) {
            throw new CustomException(BroadcastErrorCode.CHZZK_REDIS_CHANNEL_RESPONSE_INVALID);
        }
        if (response.status() == null || response.status().isBlank()) {
            throw new CustomException(BroadcastErrorCode.CHZZK_REDIS_CHANNEL_RESPONSE_INVALID);
        }
    }
}
