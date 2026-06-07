package com.example.sku_sw.domain.chat.service;

import com.example.sku_sw.domain.broadcast.entity.Broadcast;
import com.example.sku_sw.domain.broadcast.entity.BroadcastKeywords;
import com.example.sku_sw.domain.broadcast.entity.BroadcastStats;
import com.example.sku_sw.domain.broadcast.enums.AiCharacterTendency;
import com.example.sku_sw.domain.broadcast.enums.DialogueSubject;
import com.example.sku_sw.domain.broadcast.repository.BroadcastKeywordsRepository;
import com.example.sku_sw.domain.broadcast.repository.BroadcastRepository;
import com.example.sku_sw.domain.broadcast.repository.BroadcastStatsRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChzzkChatMessageService {

    private static final Map<String, String> USER_ROLE_CODE_TO_KOREAN = Map.of(
            "common_user", "시청자",
            "manager", "매니저",
            "subscription_user", "구독자",
            "top_fan_user", "열혈팬",
            "streaming_chat_notice_admin", "공지 관리자"
    );

    private final ObjectMapper objectMapper;
    private final BroadcastRedisUtil broadcastRedisUtil;
    private final BroadcastRepository broadcastRepository;
    private final BroadcastStatsRepository broadcastStatsRepository;
    private final BroadcastKeywordsRepository broadcastKeywordsRepository;

    /**
     * FastApi로부터 전달받은 Chzzk 채팅 메시지를 파싱하고,
     * Redis 저장만 수행한다.
     * - 스트리머 채팅은 DialogueSubject.STREAMER로 저장한다.
     * - 일반 채팅은 Gemini 문맥 누적용 접두어를 포함한 문자열로 저장한다.
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
                2. Redis BroadcastInfo에 채팅 데이터 저장
                - 스트리머 채팅은 STREAMER로 원문 저장한다.
                - 일반 채팅은 VIEWER로 Gemini 문맥용 접두어가 포함된 문자열을 저장한다.
                - FastAPI 채팅은 아직 Gemini로 보내지지 않았으므로 sentToGemini=false로 저장한다.
              */
            DialogueSubject dialogueSubject = resolveDialogueSubject(message);
            String redisContent = buildRedisContent(message);

            broadcastRedisUtil.pushBroadcastInfo(message.broadcastStreamId(), dialogueSubject, redisContent, null, false);

            log.info("[ChzzkChatMessageService] processChatMessage() - Received | channelId: {}, nickname: {}, content: {}",
                    message.channelId(), message.nickname(), message.content());
        } catch (JsonProcessingException e) {
            log.error("[ChzzkChatMessageService] processChatMessage() - Failed to parse payload | payload: {}", payload, e);
        }

        log.info("[ChzzkChatMessageService] processChatMessage() - END");
    }

    /**
     * FastAPI 채팅 메시지의 Redis 저장 subject를 결정한다.
     * - 스트리머 채팅은 STREAMER로 저장하고, 그 외 채팅은 VIEWER로 저장한다.
     * @param message : Chzzk 채팅 메시지 DTO
     * @return : Redis 저장 subject
     */
    private DialogueSubject resolveDialogueSubject(ChzzkChatMessageDto message) {
        log.info("[ChzzkChatMessageService] resolveDialogueSubject() - START | streamId: {}, userRoleCode: {}",
                message.broadcastStreamId(), message.userRoleCode());

        /*
            1. userRoleCode 기준으로 대화 주체를 결정한다.
            - streamer는 STREAMER로 저장한다.
            - 그 외의 채팅은 VIEWER로 저장한다.
         */
        DialogueSubject result = "streamer".equals(message.userRoleCode())
                ? DialogueSubject.STREAMER
                : DialogueSubject.VIEWER;

        log.info("[ChzzkChatMessageService] resolveDialogueSubject() - END | subject: {}", result);
        return result;
    }

    /**
     * FastAPI 채팅 메시지를 Redis 저장용 문자열로 변환한다.
     * - 스트리머 채팅은 원문 그대로 저장한다.
     * - 일반 채팅은 역할/닉네임 접두어를 포함한 Gemini 문맥 문자열로 저장한다.
     * @param message : Chzzk 채팅 메시지 DTO
     * @return : Redis 저장용 문자열
     */
    private String buildRedisContent(ChzzkChatMessageDto message) {
        log.info("[ChzzkChatMessageService] buildRedisContent() - START | streamId: {}, nickname: {}",
                message.broadcastStreamId(), message.nickname());

        /*
            1. 스트리머 채팅은 원문 그대로 반환한다.
            - 이후 공통 Gemini payload 생성 시점에 (스트리머) 접두어를 붙인다.
         */
        if ("streamer".equals(message.userRoleCode())) {
            log.info("[ChzzkChatMessageService] buildRedisContent() - END | contentType: streamer_raw");
            return message.content();
        }

        /*
            2. 일반 채팅은 역할/닉네임 접두어를 포함한 문자열로 변환한다.
            - BroadcastInfoRedisDto에는 닉네임 필드가 없으므로 저장 시점에 포맷을 완료한다.
         */
        String nickname = message.nickname();
        String userRoleCode = message.userRoleCode();

        String prefix;
        if (userRoleCode == null || userRoleCode.isEmpty()) {
            prefix = "(시청자, " + nickname + ")";
        } else {
            String koreanRole = USER_ROLE_CODE_TO_KOREAN.getOrDefault(userRoleCode, userRoleCode);
            prefix = "(" + koreanRole + ", " + nickname + ")";
        }

        String result = prefix + message.content();
        log.info("[ChzzkChatMessageService] buildRedisContent() - END | contentType: viewer_prefixed");
        return result;
    }

    /**
     * FastApi로부터 전달받은 채팅 통계 메시지를 파싱하고,
     * 새로운 BroadcastStats를 생성하여 저장한 뒤,
     * Redis BroadcastCharacter의 tendency를 업데이트한다.
     * - 항상 새로운 BroadcastStats를 생성한다.
     * - 긍정/중립/부정 비율 중 가장 높은 값을 tendency로 설정한다.
     * - tendencyAutoUpdate가 true인 경우에만 Redis가 원자적으로 업데이트된다.
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

            /*
                5. Redis BroadcastCharacter의 tendency 업데이트
                - 이번 통계 데이터의 긍정/중립/부정 비율을 계산하여 가장 높은 tendency를 결정한다.
                - tendencyAutoUpdate가 true인 경우에만 Lua 스크립트로 원자적 업데이트한다.
             */
            int totalChatCount = positiveChatCount + neutralChatCount + negativeChatCount;
            if (totalChatCount > 0) {
                double positiveRatio = (double) positiveChatCount / totalChatCount;
                double neutralRatio = (double) neutralChatCount / totalChatCount;
                double negativeRatio = (double) negativeChatCount / totalChatCount;

                AiCharacterTendency tendency = AiCharacterTendency.NEUTRAL;
                if (positiveRatio >= neutralRatio && positiveRatio >= negativeRatio) {
                    tendency = AiCharacterTendency.POSITIVE;
                } else if (negativeRatio > neutralRatio) {
                    tendency = AiCharacterTendency.NEGATIVE;
                }

                broadcastRedisUtil.updateBroadcastCharacterTendencyIfAutoUpdateEnabled(broadcastStreamId, tendency);

                log.info("[ChzzkChatMessageService] processChatStatsMessage() - Tendency updated | broadcastStreamId: {}, positiveRatio: {}, neutralRatio: {}, negativeRatio: {}, tendency: {}",
                        broadcastStreamId, positiveRatio, neutralRatio, negativeRatio, tendency);
            }
        } catch (JsonProcessingException e) {
            log.error("[ChzzkChatMessageService] processChatStatsMessage() - Failed to parse payload | payload: {}", payload, e);
        }

        log.info("[ChzzkChatMessageService] processChatStatsMessage() - END");
    }

    /**
     * FastApi로부터 전달받은 키워드 메시지를 파싱하고,
     * 정규화된 BroadcastKeywords를 생성하여 저장한다.
     * - 30초마다 키워드 배열이 전달되며, 각 키워드를 개별 레코드로 저장한다.
     * - 정규화 결과 빈 키워드는 저장하지 않는다.
     * @param payload : FastApi가 전달한 JSON 형식의 키워드 메시지
     */
    @Transactional
    public void processChatKeywordsMessage(String payload) {
        log.info("[ChzzkChatMessageService] processChatKeywordsMessage() - START | payload: {}", payload);

        try {
            /*
                1. JSON 파싱
                - 전달받은 payload에서 broadcastStreamId, keywords를 추출한다.
             */
            JsonNode jsonNode = objectMapper.readTree(payload);
            String broadcastStreamId = jsonNode.get("broadcastStreamId").asText();
            JsonNode keywordsNode = jsonNode.get("keywords");

            /*
                2. Broadcast 조회
                - broadcastStreamId로 방송을 조회하고, 존재하지 않으면 로그만 남기고 종료한다.
             */
            Broadcast broadcast = broadcastRepository.findByStreamId(broadcastStreamId).orElse(null);
            if (broadcast == null) {
                log.warn("[ChzzkChatMessageService] processChatKeywordsMessage() - Broadcast not found | broadcastStreamId: {}", broadcastStreamId);
                log.info("[ChzzkChatMessageService] processChatKeywordsMessage() - END");
                return;
            }

            /*
                3. 키워드 목록 생성
                - keywords 배열을 순회하며 정규화된 BroadcastKeywords를 생성한다.
                - 정규화 결과 null인 키워드는 제외한다.
             */
            List<BroadcastKeywords> keywordEntities = new ArrayList<>();
            if (keywordsNode != null && keywordsNode.isArray()) {
                for (JsonNode keywordNode : keywordsNode) {
                    String rawKeyword = keywordNode.asText();
                    BroadcastKeywords keywordEntity = BroadcastKeywords.create(rawKeyword, broadcast);
                    if (keywordEntity != null) {
                        keywordEntities.add(keywordEntity);
                    }
                }
            }

            /*
                4. BroadcastKeywords 저장
             */
            if (!keywordEntities.isEmpty()) {
                broadcastKeywordsRepository.saveAll(keywordEntities);
            }

            log.info("[ChzzkChatMessageService] processChatKeywordsMessage() - Saved keywords | broadcastStreamId: {}, total: {}, saved: {}",
                    broadcastStreamId, keywordsNode != null ? keywordsNode.size() : 0, keywordEntities.size());
        } catch (JsonProcessingException e) {
            log.error("[ChzzkChatMessageService] processChatKeywordsMessage() - Failed to parse payload | payload: {}", payload, e);
        }

        log.info("[ChzzkChatMessageService] processChatKeywordsMessage() - END");
    }
}
