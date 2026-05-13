package com.example.sku_sw.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.util.concurrent.Executor;

@EnableAsync
@Configuration
public class AppConfig {
    @Bean
    public ObjectMapper objectMapper(Jackson2ObjectMapperBuilder builder) {
        return builder.build();
    }

    /**
     * StandardWebSocketClient의 text Message, Binary Message Buffer Size를 지정한다.
     * @return
     */
    @Bean
    public StandardWebSocketClient standardWebSocketClient() {
        WebSocketContainer webSocketContainer = ContainerProvider.getWebSocketContainer();
        webSocketContainer.setDefaultMaxTextMessageBufferSize(128 * 1024);
        webSocketContainer.setDefaultMaxBinaryMessageBufferSize(128 * 1024);
        return new StandardWebSocketClient(webSocketContainer);
    }

    /**
     * Gemini turn 완료 후처리 전용 비동기 실행기
     * @return : Gemini 완료 응답 처리 Executor
     */
    @Bean(name = "geminiTurnCompletionExecutor")
    public Executor geminiTurnCompletionExecutor() {
        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(4);
        taskExecutor.setMaxPoolSize(8);
        taskExecutor.setQueueCapacity(100);
        taskExecutor.setThreadNamePrefix("gemini-turn-");
        taskExecutor.initialize();
        return taskExecutor;
    }
}
