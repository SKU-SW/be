package com.example.sku_sw.global.config;

import com.example.sku_sw.domain.broadcast.websocket.BroadcastHandshakeInterceptor;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 설정 클래스
 * - 네이티브 WebSocket 엔드포인트를 등록한다.
 * - 핸드셰이크 인터셉터와 핸들러를 연결한다.
 */
@Configuration
@EnableWebSocket
@EnableScheduling
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final BroadcastWebSocketHandler broadcastWebSocketHandler;
    private final BroadcastHandshakeInterceptor broadcastHandshakeInterceptor;

    /**
     * WebSocket 핸들러 등록
     * - /api/v1/stream/ws 경로로 WebSocket 연결을 허용한다.
     * - 핸드셰이크 인터셉터를 통해 JWT 검증 및 세션 속성 설정을 수행한다.
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(broadcastWebSocketHandler, "/api/v1/stream/ws")
                .addInterceptors(broadcastHandshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
