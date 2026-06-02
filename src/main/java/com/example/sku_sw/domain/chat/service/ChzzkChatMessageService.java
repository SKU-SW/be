package com.example.sku_sw.domain.chat.service;

import com.example.sku_sw.domain.broadcast.enums.DialogueSubject;
import com.example.sku_sw.domain.broadcast.service.gemini.BroadcastGeminiRequestService;
import com.example.sku_sw.domain.broadcast.util.BroadcastRedisUtil;
import com.example.sku_sw.domain.chat.dto.ChzzkChatMessageDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChzzkChatMessageService {

    private final ObjectMapper objectMapper;
    private final BroadcastGeminiRequestService broadcastGeminiRequestService;
    private final BroadcastRedisUtil broadcastRedisUtil;

    /**
     * FastApi로부터 전달받은 Chzzk 채팅 메시지를 파싱하고,
     * Gemini 전송 및 Redis 저장을 수행한다.
     * - Gemini 전송: BroadcastGeminiRequestService.sendChatRequest()에 위임
     * - Redis 저장: BroadcastRedisUtil.pushBroadcastInfo()로 DialogueSubject.VIEWER 저장
     * @param payload : FastApi가 전달한 JSON 형식의 채팅 메시지
     */
    public void processChatMessage(String payload) {
        log.info("[ChzzkChatMessageService] processChatMessage() - START | payload: {}", payload);

        try {
            /*
                1. JSON 파싱
                - 전달받은 payload를 ChzzkChatMessageDto로 역직렬화한다.
            */
            ChzzkChatMessageDto message = objectMapper.readValue(payload, ChzzkChatMessageDto.class);

            /*
                2. Gemini에게 채팅 메시지 전송
                - BroadcastGeminiRequestService.sendChatRequest()에 위임한다.
                - userRoleCode 기반 "(시청자, 닉네임)" / "(스트리머)" 접두어 포맷팅 포함.
             */
            broadcastGeminiRequestService.sendChatRequest(message);

            /*
                3. Redis BroadcastInfo에 채팅 데이터 저장
                - DialogueSubject.VIEWER로 BroadcastInfo Redis List에 저장한다.
             */
            broadcastRedisUtil.pushBroadcastInfo(message.broadcastStreamId(), DialogueSubject.VIEWER, message.content());

            log.info("[ChzzkChatMessageService] processChatMessage() - Received | channelId: {}, nickname: {}, content: {}",
                    message.channelId(), message.nickname(), message.content());
        } catch (JsonProcessingException e) {
            log.error("[ChzzkChatMessageService] processChatMessage() - Failed to parse payload | payload: {}", payload, e);
        }

        log.info("[ChzzkChatMessageService] processChatMessage() - END");
    }
}
