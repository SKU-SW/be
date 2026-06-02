package com.example.sku_sw.domain.chat.service;

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

    /**
     * FastApi로부터 전달받은 Chzzk 채팅 메시지를 파싱하고 로깅한다.
     * @param payload : FastApi가 전달한 JSON 형식의 채팅 메시지
     */
    public void processChatMessage(String payload) {
        log.info("[ChzzkChatMessageService] processChatMessage() - START | payload: {}", payload);

        /*
            1. JSON 파싱
            - 전달받은 payload를 ChzzkChatMessageDto로 역직렬화한다.
         */
        try {
            ChzzkChatMessageDto message = objectMapper.readValue(payload, ChzzkChatMessageDto.class);
            log.info("[ChzzkChatMessageService] processChatMessage() - Received | channelId: {}, nickname: {}, content: {}",
                    message.channelId(), message.nickname(), message.content());
        } catch (JsonProcessingException e) {
            log.error("[ChzzkChatMessageService] processChatMessage() - Failed to parse payload | payload: {}", payload, e);
        }

        log.info("[ChzzkChatMessageService] processChatMessage() - END");
    }
}
