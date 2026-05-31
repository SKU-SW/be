package com.example.sku_sw.domain.auth.service;

import com.example.sku_sw.domain.auth.dto.AuthChzzkApiResDto;
import com.example.sku_sw.domain.auth.dto.AuthChzzkRefreshTokenReqDto;
import com.example.sku_sw.domain.auth.dto.AuthChzzkTokenReqDto;
import com.example.sku_sw.domain.auth.dto.AuthChzzkTokenRevokeReqDto;
import com.example.sku_sw.domain.auth.dto.AuthChzzkTokenResDto;
import com.example.sku_sw.domain.auth.enums.AuthErrorCode;
import com.example.sku_sw.global.exception.CustomException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Slf4j
@Service
public class AuthChzzkApiService {

    private static final String CHZZK_TOKEN_BASE_URL = "https://openapi.chzzk.naver.com";
    private static final String CHZZK_TOKEN_PATH = "/auth/v1/token";
    private static final String CHZZK_TOKEN_REVOKE_PATH = "/auth/v1/token/revoke";

    private final WebClient webClient;

    public AuthChzzkApiService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder
                .baseUrl(CHZZK_TOKEN_BASE_URL)
                .build();
    }

    /**
     * 치지직 Access Token / Refresh Token 발급을 요청하는 함수
     * - authorization_code, clientId, clientSecret, code, state를 전달하여 토큰 교환을 수행한다.
     * @param request : 치지직 토큰 발급 요청 DTO
     * @return : 치지직 토큰 발급 응답 DTO
     */
    public AuthChzzkTokenResDto requestToken(AuthChzzkTokenReqDto request) {
        log.info("[AuthChzzkApiService] requestToken() - START | state: {}", request.state());

        try {
            AuthChzzkApiResDto<AuthChzzkTokenResDto> response = webClient.post()
                    .uri(CHZZK_TOKEN_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, clientResponse ->
                            clientResponse.bodyToMono(String.class)
                                    .map(errorBody -> {
                                        log.error("[AuthChzzkApiService] requestToken() - CHZZK token API error | status: {}, body: {}",
                                                clientResponse.statusCode(), errorBody);
                                        return new CustomException(AuthErrorCode.CHZZK_AUTH_TOKEN_REQUEST_FAILED);
                                    })
                    )
                    .bodyToMono(new ParameterizedTypeReference<AuthChzzkApiResDto<AuthChzzkTokenResDto>>() {
                    })
                    .block();

            if (response == null || response.content() == null) {
                log.error("[AuthChzzkApiService] requestToken() - invalid CHZZK token response | state: {}, response: {}",
                        request.state(), response);
                throw new CustomException(AuthErrorCode.CHZZK_AUTH_TOKEN_RESPONSE_INVALID);
            }

            log.info("[AuthChzzkApiService] requestToken() - END | state: {}", request.state());
            return response.content();
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            log.error("[AuthChzzkApiService] requestToken() - CHZZK token API call failed | state: {}, error: {}",
                    request.state(), e.getMessage());
            throw new CustomException(AuthErrorCode.CHZZK_AUTH_TOKEN_REQUEST_FAILED);
        }
    }

    /**
     * 치지직 Access Token 재발급을 요청하는 함수
     * - refresh_token grant 방식으로 치지직 Access / Refresh Token 재발급을 요청한다.
     * @param request : 치지직 Refresh Token 재발급 요청 DTO
     * @return : 치지직 토큰 재발급 응답 DTO
     */
    public AuthChzzkTokenResDto requestRefreshToken(AuthChzzkRefreshTokenReqDto request) {
        log.info("[AuthChzzkApiService] requestRefreshToken() - START");

        try {
            AuthChzzkApiResDto<AuthChzzkTokenResDto> response = webClient.post()
                    .uri(CHZZK_TOKEN_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, clientResponse ->
                            clientResponse.bodyToMono(String.class)
                                    .map(errorBody -> {
                                        log.error("[AuthChzzkApiService] requestRefreshToken() - CHZZK token refresh API error | status: {}, body: {}",
                                                clientResponse.statusCode(), errorBody);
                                        return resolveRefreshTokenException(clientResponse.statusCode(), errorBody);
                                    })
                    )
                    .bodyToMono(new ParameterizedTypeReference<AuthChzzkApiResDto<AuthChzzkTokenResDto>>() {
                    })
                    .block();

            if (response == null || response.content() == null) {
                log.error("[AuthChzzkApiService] requestRefreshToken() - invalid CHZZK refresh response | response: {}", response);
                throw new CustomException(AuthErrorCode.CHZZK_AUTH_TOKEN_RESPONSE_INVALID);
            }

            log.info("[AuthChzzkApiService] requestRefreshToken() - END");
            return response.content();
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            log.error("[AuthChzzkApiService] requestRefreshToken() - CHZZK refresh API call failed | error: {}", e.getMessage());
            throw new CustomException(AuthErrorCode.CHZZK_AUTH_TOKEN_REFRESH_FAILED);
        }
    }

    private CustomException resolveRefreshTokenException(HttpStatusCode statusCode, String errorBody) {
        String normalizedErrorBody = errorBody == null ? "" : errorBody.toLowerCase();
        if (statusCode.value() == 401
                || normalizedErrorBody.contains("invalid_token")
                || normalizedErrorBody.contains("invalid token")
                || normalizedErrorBody.contains("expired")) {
            return new CustomException(AuthErrorCode.CHZZK_AUTH_REFRESH_TOKEN_INVALID);
        }

        return new CustomException(AuthErrorCode.CHZZK_AUTH_TOKEN_REFRESH_FAILED);
    }

    /**
     * 치지직 토큰 만료를 요청하는 함수
     * - Refresh Token 기준으로 치지직 인증 토큰 만료를 요청한다.
     * - 이미 만료되었거나 제거된 토큰은 성공으로 간주한다.
     * @param request : 치지직 토큰 만료 요청 DTO
     */
    public void revokeToken(AuthChzzkTokenRevokeReqDto request) {
        log.info("[AuthChzzkApiService] revokeToken() - START | tokenTypeHint: {}", request.tokenTypeHint());

        try {
            AuthChzzkApiResDto<Object> response = webClient.post()
                    .uri(CHZZK_TOKEN_REVOKE_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchangeToMono(clientResponse -> {
                        HttpStatusCode statusCode = clientResponse.statusCode();

                        if (statusCode.is2xxSuccessful()) {
                            return clientResponse.bodyToMono(new ParameterizedTypeReference<AuthChzzkApiResDto<Object>>() {
                            });
                        }

                        return clientResponse.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(errorBody -> {
                                    if (statusCode.value() == 401 && isInvalidTokenError(errorBody)) {
                                        log.warn("[AuthChzzkApiService] revokeToken() - token already invalid | body: {}", errorBody);
                                        return Mono.empty();
                                    }

                                    log.error("[AuthChzzkApiService] revokeToken() - CHZZK token revoke API error | status: {}, body: {}",
                                            statusCode, errorBody);
                                    return Mono.error(new CustomException(AuthErrorCode.CHZZK_AUTH_TOKEN_REVOKE_FAILED));
                                });
                    })
                    .block();

            if (response != null && (response.code() == null || response.code() != 200)) {
                log.error("[AuthChzzkApiService] revokeToken() - invalid CHZZK revoke response | response: {}", response);
                throw new CustomException(AuthErrorCode.CHZZK_AUTH_TOKEN_REVOKE_FAILED);
            }

            log.info("[AuthChzzkApiService] revokeToken() - END | tokenTypeHint: {}", request.tokenTypeHint());
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            log.error("[AuthChzzkApiService] revokeToken() - CHZZK revoke API call failed | error: {}", e.getMessage());
            throw new CustomException(AuthErrorCode.CHZZK_AUTH_TOKEN_REVOKE_FAILED);
        }
    }

    private boolean isInvalidTokenError(String errorBody) {
        String normalizedErrorBody = errorBody == null ? "" : errorBody.toLowerCase();
        return normalizedErrorBody.contains("invalid_token") || normalizedErrorBody.contains("invalid token");
    }
}
