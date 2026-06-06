package com.example.sku_sw.domain.chat.service;

import com.example.sku_sw.domain.broadcast.entity.Broadcast;
import com.example.sku_sw.domain.broadcast.entity.BroadcastStats;
import com.example.sku_sw.domain.broadcast.enums.DialogueSubject;
import com.example.sku_sw.domain.broadcast.repository.BroadcastRepository;
import com.example.sku_sw.domain.broadcast.repository.BroadcastStatsRepository;
import com.example.sku_sw.domain.broadcast.service.gemini.BroadcastGeminiRequestService;
import com.example.sku_sw.domain.broadcast.util.BroadcastRedisUtil;
import com.example.sku_sw.domain.chat.dto.ChzzkChatMessageDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChzzkChatMessageService {

    private final ObjectMapper objectMapper;
    private final BroadcastGeminiRequestService broadcastGeminiRequestService;
    private final BroadcastRedisUtil broadcastRedisUtil;
    private final BroadcastRepository broadcastRepository;
    private final BroadcastStatsRepository broadcastStatsRepository;

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
                2. Gemini에게 채팅 메시지 전송 (비-생성 컨텍스트 전용)
                - BroadcastGeminiRequestService.sendViewerChatRequest()에 위임한다.
                - clientContent / turnComplete:false 메시지 구조로 전송되어 모델 turn을 요청하지 않는다.
                - userRoleCode 기반 "(시청자, 닉네임)" / "(스트리머)" 접두어 포맷팅 포함.
             */
            broadcastGeminiRequestService.sendViewerChatRequest(message);

            /*
                3. Redis BroadcastInfo에 채팅 데이터 저장
                - DialogueSubject.VIEWER로 BroadcastInfo Redis List에 저장한다.
             */
            broadcastRedisUtil.pushBroadcastInfo(message.broadcastStreamId(), DialogueSubject.VIEWER, message.content(), null, true);

            log.info("[ChzzkChatMessageService] processChatMessage() - Received | channelId: {}, nickname: {}, content: {}",
                    message.channelId(), message.nickname(), message.content());
        } catch (JsonProcessingException e) {
            log.error("[ChzzkChatMessageService] processChatMessage() - Failed to parse payload | payload: {}", payload, e);
        }

        log.info("[ChzzkChatMessageService] processChatMessage() - END");
    }

    /**
     * FastApi로부터 전달받은 채팅 통계 메시지를 파싱하고,
     * 새로운 BroadcastStats를 생성하여 저장한다.
     * - 항상 새로운 BroadcastStats를 생성한다.
     * @param payload : FastApi가 전달한 JSON 형식의 채팅 통계 메시지
     */
    @Transactional
    public void processChatStatsMessage(String payload) {
        log.info("[ChzzkChatMessageService] processChatStatsMessage() - START | payload: {}", payload);

        try {
            /*
                1. JSON 파싱
                - 전달받은 payload에서 broadcastStreamId, positiveChatCount, neutralChatCount, negativeChatCount를 추출한다.
             */
            JsonNode jsonNode = objectMapper.readTree(payload);
            String broadcastStreamId = jsonNode.get("broadcastStreamId").asText();
            int positiveChatCount = jsonNode.get("positiveChatCount").asInt();
            int neutralChatCount = jsonNode.get("neutralChatCount").asInt();
            int negativeChatCount = jsonNode.get("negativeChatCount").asInt();

            /*
                2. Broadcast 조회
                - broadcastStreamId로 방송을 조회하고, 존재하지 않으면 로그만 남기고 종료한다.
             */
            Broadcast broadcast = broadcastRepository.findByStreamId(broadcastStreamId).orElse(null);
            if (broadcast == null) {
                log.warn("[ChzzkChatMessageService] processChatStatsMessage() - Broadcast not found | broadcastStreamId: {}", broadcastStreamId);
                log.info("[ChzzkChatMessageService] processChatStatsMessage() - END");
                return;
            }

            /*
                3. 신규 BroadcastStats 생성
                - 매번 새로운 BroadcastStats를 생성한다.
             */
            BroadcastStats broadcastStats = BroadcastStats.create(
                    0, positiveChatCount, neutralChatCount, negativeChatCount, broadcast
            );

            /*
                4. BroadcastStats 저장
             */
            broadcastStatsRepository.save(broadcastStats);

            log.info("[ChzzkChatMessageService] processChatStatsMessage() - Created new stats | broadcastStreamId: {}, positive: {}, neutral: {}, negative: {}",
                    broadcastStreamId, positiveChatCount, neutralChatCount, negativeChatCount);
        } catch (JsonProcessingException e) {
            log.error("[ChzzkChatMessageService] processChatStatsMessage() - Failed to parse payload | payload: {}", payload, e);
        }

        log.info("[ChzzkChatMessageService] processChatStatsMessage() - END");
    }
}
