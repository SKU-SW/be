package com.example.sku_sw.domain.chat.util;

import com.example.sku_sw.domain.chat.service.ChzzkChatMessageService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Chat Redis의 구독을 관리하는 클래스
 */
@Slf4j
@Component
public class ChatRedisSubscriber {

    private static final String CHANNEL_PREFIX = "Chat:";
    private static final String MESSAGE_CHANNEL_SUFFIX = ".message";

    private final RedisMessageListenerContainer chatRedisMessageListenerContainer;
    private final ChzzkChatMessageService chzzkChatMessageService;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, MessageListener> channelListeners = new ConcurrentHashMap<>();

    public ChatRedisSubscriber(RedisMessageListenerContainer chatRedisMessageListenerContainer,
                               ChzzkChatMessageService chzzkChatMessageService,
                               ObjectMapper objectMapper) {
        this.chatRedisMessageListenerContainer = chatRedisMessageListenerContainer;
        this.chzzkChatMessageService = chzzkChatMessageService;
        this.objectMapper = objectMapper;
    }

    /**
     * 각 스트리머 채널 고유 ID로 Pub/Sub Redis를 구독한다.
     * @param channelId : 각 스트리머 채널 고유 ID
     * @return Chat:{channelId}.message
     */
    public String subscribeChannelPattern(String channelId) {
        /*
            1. 구독하려는 채널의 패턴과 실제 채널 이름 생성
         */
        String pattern = "Chat:" + channelId + ".*";
        String channelName = "Chat:" + channelId + ".message";

        /*
            2. 해당 채널을 아직 구독하고 있지 않은 경우에만 해당 채널용 MessageListener를 등록해놓는다.
            - RedisMessageListenerContainer에 해당 Pattern Channel에 대응되는 MessageListener를 등록해놓음으로써, Channel에 메시지가 Publish 되었을 때 해당 채팅을 받아 처리할 수 있도록 한다.
         */
        channelListeners.computeIfAbsent(channelId, key -> {
            /*
                3. RedisMessageListenerContainer에 (MessageListener, PatternTopic)을 추가한다.
             */
            MessageListener listener = this::handleMessage;
            chatRedisMessageListenerContainer.addMessageListener(listener, PatternTopic.of(pattern));
            log.info("[ChatRedisSubscriber] subscribeChannelPattern() - Chat Pub/Sub Redis 채널 구독 완료 | channelId: {}, pattern: {}, channelName: {}",
                    channelId, pattern, channelName);
            return listener;
        });

        return channelName;
    }

    /**
     * Redis 채널 이름에 대응되는 MessageListener 등록 여부를 확인한다.
     * @param channelName : Chat:{channelId}.message 형식의 Redis 채널 이름
     * @return : MessageListener 등록 여부
     */
    public boolean hasChannelListener(String channelName) {
        String channelId = extractChannelId(channelName);
        return channelListeners.containsKey(channelId);
    }

    /**
     * Redis 채널 이름을 기준으로 Pattern Channel을 다시 구독한다.
     * @param channelName : Chat:{channelId}.message 형식의 Redis 채널 이름
     * @return : 이번 호출에서 MessageListener를 새로 등록했는지 여부
     */
    public boolean resubscribeChannelPattern(String channelName) {
        String channelId = extractChannelId(channelName);
        String pattern = CHANNEL_PREFIX + channelId + ".*";
        AtomicBoolean registered = new AtomicBoolean(false);

        channelListeners.computeIfAbsent(channelId, key -> {
            MessageListener listener = this::handleMessage;
            chatRedisMessageListenerContainer.addMessageListener(listener, PatternTopic.of(pattern));
            registered.set(true);
            log.info("[ChatRedisSubscriber] resubscribeChannelPattern() - Chat Pub/Sub Redis 채널 재구독 완료 | channelId: {}, pattern: {}, channelName: {}",
                    channelId, pattern, channelName);
            return listener;
        });

        return registered.get();
    }

    /**
     * 각 스트리머 채널 고유 ID로 Pub/Sub Redis를 구독한 것을 해제한다.
     * @param channelId : 각 스트리머 채널 고유 ID
     */
    public void unsubscribeChannelPattern(String channelId) {
        MessageListener listener = channelListeners.remove(channelId);
        if (listener != null) {
            chatRedisMessageListenerContainer.removeMessageListener(listener, PatternTopic.of("Chat:" + channelId + ".*"));
            log.info("[ChatRedisSubscriber] unsubscribeChannelPattern() - Unregistered | channelId: {}", channelId);
        }
    }

    /**
     * Redis 채널 이름에서 스트리머 채널 고유 ID를 추출한다.
     * @param channelName : Chat:{channelId}.message 형식의 Redis 채널 이름
     * @return : 스트리머 채널 고유 ID
     */
    private String extractChannelId(String channelName) {
        if (channelName == null
                || !channelName.startsWith(CHANNEL_PREFIX)
                || !channelName.endsWith(MESSAGE_CHANNEL_SUFFIX)) {
            throw new IllegalArgumentException("Invalid chat Redis channel name: " + channelName);
        }

        String channelId = channelName.substring(
                CHANNEL_PREFIX.length(),
                channelName.length() - MESSAGE_CHANNEL_SUFFIX.length()
        );
        if (channelId.isBlank()) {
            throw new IllegalArgumentException("Invalid chat Redis channel name: " + channelName);
        }
        return channelId;
    }

    /**
     * Pub/Sub Redis에 Publish된 메시지를 처리하는 MessageListener
     * @param message : 수신받은 메시지 데이터
     * @param pattern : 채널 패턴
     */
    private void handleMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        String subscribedPattern = pattern == null ? "" : new String(pattern, StandardCharsets.UTF_8);
        log.info("[ChatRedisSubscriber] handleMessage() - Received | pattern: {}, channel: {}, payload: {}",
                subscribedPattern, channel, payload);

        /*
            1. JSON 파싱하여 메시지 타입 구분
            - positiveChatCount 필드 존재 여부로 통계 데이터인지 채팅 데이터인지 판단
         */
        try {
            JsonNode jsonNode = objectMapper.readTree(payload);

            if (jsonNode.has("keywords")) {
                /*
                    2-1. 키워드 데이터인 경우
                    - 키워드 처리 메서드 호출
                 */
                chzzkChatMessageService.processChatKeywordsMessage(payload);
            } else if (jsonNode.has("positiveChatCount")) {
                /*
                    2-2. 통계 데이터인 경우
                    - 채팅 통계 처리 메서드 호출
                 */
                chzzkChatMessageService.processChatStatsMessage(payload);
            } else {
                /*
                    2-3. 채팅 데이터인 경우
                    - 기존 채팅 메시지 처리 메서드 호출
                 */
                chzzkChatMessageService.processChatMessage(payload);
            }
        } catch (JsonProcessingException e) {
            log.error("[ChatRedisSubscriber] handleMessage() - Failed to parse payload | payload: {}", payload, e);
        }
    }
}
