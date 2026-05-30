package com.example.sku_sw.domain.auth.service;

import com.example.sku_sw.domain.auth.dto.AuthChzzkApiResDto;
import com.example.sku_sw.domain.auth.dto.AuthChzzkTokenReqDto;
import com.example.sku_sw.domain.auth.dto.AuthChzzkTokenResDto;
import com.example.sku_sw.domain.auth.enums.AuthErrorCode;
import com.example.sku_sw.global.exception.CustomException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Service
public class AuthChzzkApiService {

    private static final String CHZZK_TOKEN_BASE_URL = "https://openapi.chzzk.naver.com";
    private static final String CHZZK_TOKEN_PATH = "/auth/v1/token";

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
}
