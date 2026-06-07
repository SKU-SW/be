package com.example.sku_sw.domain.broadcast.websocket.gemini;

import com.example.sku_sw.domain.broadcast.service.gemini.BroadcastGeminiResponseService;
import com.example.sku_sw.domain.broadcast.service.gemini.BroadcastGeminiToolCallService;
import com.example.sku_sw.domain.broadcast.websocket.BroadcastWebSocketSessionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Gemini Live WebSocket Handler 생성 팩토리
 * - Gemini 세션별 system prompt를 포함한 전용 Handler 인스턴스를 생성한다.
 */
@Component
@RequiredArgsConstructor
public class GeminiLiveWebSocketHandlerFactory {

    private final ObjectMapper objectMapper;
    private final BroadcastWebSocketSessionRegistry sessionRegistry;
    private final BroadcastGeminiResponseService broadcastGeminiResponseService;
    private final BroadcastGeminiToolCallService broadcastGeminiToolCallService;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Value("${gemini.api.dialogue-model}")
    private String dialogueModel;

    /**
     * Gemini Live WebSocket 연결마다 독립적인 Handler 인스턴스를 생성한다.
     * @param systemPrompt : Gemini setup 시 주입할 시스템 프롬프트
     * @param voiceName : Gemini setup 시 사용할 voice name (null/blank이면 voice config 생략)
     * @return : 세션 전용 GeminiLiveWebSocketHandler
     */
    public GeminiLiveWebSocketHandler create(String systemPrompt, String voiceName, String resumptionHandle) {
        return new GeminiLiveWebSocketHandler(
                objectMapper,
                sessionRegistry,
                broadcastGeminiResponseService,
                broadcastGeminiToolCallService,
                applicationEventPublisher,
                dialogueModel,
                systemPrompt,
                voiceName,
                resumptionHandle
        );
    }
}
