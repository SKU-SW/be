package com.example.sku_sw.domain.chat.util;

import com.example.sku_sw.domain.chat.service.ChzzkChatMessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ChatRedisSubscriberTest {

    @Mock
    private RedisMessageListenerContainer chatRedisMessageListenerContainer;

    @Mock
    private ChzzkChatMessageService chzzkChatMessageService;

    private ChatRedisSubscriber chatRedisSubscriber;

    @BeforeEach
    void setUp() {
        chatRedisSubscriber = new ChatRedisSubscriber(
                chatRedisMessageListenerContainer,
                chzzkChatMessageService,
                new ObjectMapper()
        );
    }

    @Test
    @DisplayName("Redis 채널 재구독 성공 - Listener가 없으면 Pattern Listener를 등록한다")
    void Redis_채널_재구독_성공_Listener_신규_등록() {
        // given
        String channelName = "Chat:channel-1.message";

        // when
        boolean registered = chatRedisSubscriber.resubscribeChannelPattern(channelName);

        // then
        assertThat(registered).isTrue();
        assertThat(chatRedisSubscriber.hasChannelListener(channelName)).isTrue();
        verify(chatRedisMessageListenerContainer, times(1)).addMessageListener(
                any(MessageListener.class),
                eq(PatternTopic.of("Chat:channel-1.*"))
        );
    }

    @Test
    @DisplayName("Redis 채널 재구독 성공 - 이미 Listener가 있으면 중복 등록하지 않는다")
    void Redis_채널_재구독_성공_Listener_중복_등록_방지() {
        // given
        String channelName = "Chat:channel-1.message";
        chatRedisSubscriber.resubscribeChannelPattern(channelName);

        // when
        boolean registered = chatRedisSubscriber.resubscribeChannelPattern(channelName);

        // then
        assertThat(registered).isFalse();
        verify(chatRedisMessageListenerContainer, times(1)).addMessageListener(
                any(MessageListener.class),
                eq(PatternTopic.of("Chat:channel-1.*"))
        );
    }

    @Test
    @DisplayName("Redis 채널 구독 해제 성공 - 재구독한 Listener를 Map과 Container에서 제거한다")
    void Redis_채널_구독_해제_성공_재구독한_Listener_제거() {
        // given
        String channelId = "channel-1";
        String channelName = "Chat:channel-1.message";
        chatRedisSubscriber.resubscribeChannelPattern(channelName);
        assertThat(chatRedisSubscriber.hasChannelListener(channelName)).isTrue();

        // when
        chatRedisSubscriber.unsubscribeChannelPattern(channelId);

        // then
        // 구독 해제 후 channelListeners에서 해당 channelId의 Listener가 제거되어야 한다.
        assertThat(chatRedisSubscriber.hasChannelListener(channelName)).isFalse();
    }

    @Test
    @DisplayName("Redis 채널 재구독 성공 - 여러 스레드가 동시에 요청해도 Listener를 한 번만 등록한다")
    void Redis_채널_재구독_성공_여러_스레드의_Listener_중복_등록_방지() throws Exception {
        // given
        // 모든 스레드가 동일한 Redis 채널을 동시에 재구독하도록 동일한 channelName을 사용한다.
        String channelName = "Chat:channel-1.message";

        /*
            작업 수와 스레드 풀 크기를 동일하게 설정한다.
            작업 스레드는 startLatch가 열릴 때까지 대기하므로 스레드 수가 작업 수보다 적으면,
            실행되지 못한 작업이 readyLatch를 감소시키지 못해 테스트가 교착 상태에 빠질 수 있다.
         */
        int threadCount = 10;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);

        /*
            readyLatch: 모든 작업 스레드가 실행되어 동시 시작 직전까지 도달했는지 확인한다.
            startLatch: 준비된 작업 스레드를 한 번에 출발시키는 시작 신호로 사용한다.
         */
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        // 각 재구독 호출의 반환값을 수집해 실제 Listener 신규 등록 성공 횟수를 계산한다.
        List<Future<Boolean>> futures = new ArrayList<>();

        try {
            // 각 작업은 준비 완료를 알린 뒤 startLatch가 열릴 때까지 동일한 위치에서 대기한다.
            for (int index = 0; index < threadCount; index++) {
                futures.add(executorService.submit(() -> {
                    readyLatch.countDown();
                    startLatch.await();
                    return chatRedisSubscriber.resubscribeChannelPattern(channelName);
                }));
            }

            /*
                모든 작업이 startLatch 앞에서 대기 중인지 확인한다.
                제한 시간을 두어 스레드 생성 또는 스케줄링 문제가 발생했을 때 테스트가 무한 대기하지 않게 한다.
             */
            assertThat(readyLatch.await(3, TimeUnit.SECONDS)).isTrue();

            // when
            // 하나의 시작 신호로 대기 중인 모든 스레드를 해제해 computeIfAbsent에 동시에 진입하도록 유도한다.
            startLatch.countDown();

            // resubscribeChannelPattern()이 true를 반환한 작업, 즉 실제 신규 Listener 등록 작업의 수를 집계한다.
            long registeredCount = 0;
            for (Future<Boolean> future : futures) {
                // 개별 작업에도 제한 시간을 적용해 동시성 오류로 인한 테스트 무한 대기를 방지한다.
                if (future.get(3, TimeUnit.SECONDS)) {
                    registeredCount++;
                }
            }

            // then
            // computeIfAbsent 경쟁에서 정확히 하나의 스레드만 Listener 신규 등록에 성공해야 한다.
            assertThat(registeredCount).isEqualTo(1);

            // 모든 동시 호출이 종료된 뒤 해당 채널의 Listener가 최종적으로 등록되어 있어야 한다.
            assertThat(chatRedisSubscriber.hasChannelListener(channelName)).isTrue();

            // 인메모리 Map뿐 아니라 실제 RedisMessageListenerContainer 등록 부수 효과도 한 번만 발생해야 한다.
            verify(chatRedisMessageListenerContainer, times(1)).addMessageListener(
                    any(MessageListener.class),
                    eq(PatternTopic.of("Chat:channel-1.*"))
            );
        } finally {
            /*
                준비 검증 또는 작업 결과 조회 중 예외가 발생하더라도 대기 중인 스레드를 먼저 해제한다.
                이후 Executor를 종료해 테스트 종료 후 작업 스레드가 남지 않도록 정리한다.
             */
            startLatch.countDown();
            executorService.shutdownNow();
        }
    }

    @Test
    @DisplayName("Redis 채널 재구독 실패 - 채널 이름 형식이 올바르지 않다")
    void Redis_채널_재구독_실패_잘못된_채널_이름() {
        // given
        String channelName = "invalid-channel";

        // when & then
        assertThatThrownBy(() -> chatRedisSubscriber.resubscribeChannelPattern(channelName))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
