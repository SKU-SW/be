package com.example.sku_sw.domain.chat.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class ChatRedisSubscriber {

    private final RedisMessageListenerContainer chatRedisMessageListenerContainer;
    private final ConcurrentHashMap<String, MessageListener> channelListeners = new ConcurrentHashMap<>();

    public ChatRedisSubscriber(RedisMessageListenerContainer chatRedisMessageListenerContainer) {
        this.chatRedisMessageListenerContainer = chatRedisMessageListenerContainer;
    }

    public String subscribeChannelPattern(String channelId) {
        String pattern = "Chat:" + channelId + ".*";
        String channelName = "Chat:" + channelId + ".message";

        channelListeners.computeIfAbsent(channelId, key -> {
            MessageListener listener = this::handleMessage;
            chatRedisMessageListenerContainer.addMessageListener(listener, PatternTopic.of(pattern));
            log.info("[ChatRedisSubscriber] subscribeChannelPattern() - Registered | channelId: {}, pattern: {}, channelName: {}",
                    channelId, pattern, channelName);
            return listener;
        });

        return channelName;
    }

    public void unsubscribeChannelPattern(String channelId) {
        MessageListener listener = channelListeners.remove(channelId);
        if (listener != null) {
            chatRedisMessageListenerContainer.removeMessageListener(listener, PatternTopic.of("Chat:" + channelId + ".*"));
            log.info("[ChatRedisSubscriber] unsubscribeChannelPattern() - Unregistered | channelId: {}", channelId);
        }
    }

    private void handleMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        String subscribedPattern = pattern == null ? "" : new String(pattern, StandardCharsets.UTF_8);
        log.info("[ChatRedisSubscriber] handleMessage() - Received | pattern: {}, channel: {}, payload: {}",
                subscribedPattern, channel, payload);
    }
}
