package com.example.sku_sw.domain.chat.util;

import com.example.sku_sw.domain.chat.service.ChzzkChatMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chat Redis의 구독을 관리하는 클래스
 */
@Slf4j
@Component
public class ChatRedisSubscriber {

    private final RedisMessageListenerContainer chatRedisMessageListenerContainer;
    private final ChzzkChatMessageService chzzkChatMessageService;
    private final ConcurrentHashMap<String, MessageListener> channelListeners = new ConcurrentHashMap<>();

    public ChatRedisSubscriber(RedisMessageListenerContainer chatRedisMessageListenerContainer,
                               ChzzkChatMessageService chzzkChatMessageService) {
        this.chatRedisMessageListenerContainer = chatRedisMessageListenerContainer;
        this.chzzkChatMessageService = chzzkChatMessageService;
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
            log.info("[ChatRedisSubscriber] subscribeChannelPattern() - Registered | channelId: {}, pattern: {}, channelName: {}",
                    channelId, pattern, channelName);
            return listener;
        });

        return channelName;
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
            채팅 메시지가 왔을 때의 동작 수행
         */
        chzzkChatMessageService.processChatMessage(payload);
    }
}
