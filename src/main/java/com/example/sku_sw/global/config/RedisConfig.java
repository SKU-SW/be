package com.example.sku_sw.global.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Broadcast / Chat Redis 연결 Bean을 수동으로 등록하는 설정 클래스
 * - Spring Boot 기본 Redis 자동설정은 주로 단일 Redis 연결을 전제로 하지만, 현재 프로젝트는 Broadcast 저장 Redis와 Chat Pub/Sub Redis를 분리해서 사용한다.
 * - 따라서 AppRedisProperties를 사용해서 각 설정별 StringRedisTemplate을 생성해야한다.
 *  - ConnectionFactory: StringRedisTemplate을 어느 Redis 설정에 연결할지를 정의하는 "연결 정보 객체"
 *  - StringRedisTemplate: 연결 정보(ConnectionFactory)를 사용해서 실제 Redis 명령을 수행하는 도구
 */
@Configuration
@EnableConfigurationProperties(AppRedisProperties.class) // AppRedisProperties를 Bean으로 등록하라고 지시하는 어노테이션
public class RedisConfig {
    /**
     * Broadcast 저장 Redis용 ConnectionFactory Bean
     * - app.redis.broadcast.* 설정값을 읽어 Broadcast Redis 연결 정보를 담은 LettuceConnectionFactory를 생성
     * - @Primary: 동일 타입 Bean이 여러 개일 때 "기본 후보"를 지정하는 장치
     *
     * @param appRedisProperties 설정 파일 값이 바인딩된 AppRedisProperties Bean
     * @return Broadcast Redis 연결 정보를 가진 ConnectionFactory
     */
    @Bean
    @Primary
    public LettuceConnectionFactory broadcastRedisConnectionFactory(AppRedisProperties appRedisProperties) {
        return createConnectionFactory(appRedisProperties.getBroadcast());
    }

    /**
     * Broadcast 저장 Redis용 StringRedisTemplate Bean
     * - 실제 Redis 명령은 이 Template를 통해 수행된다.
     * - Broadcast Redis 전용 ConnectionFactory를 주입받아, 이 Template이 Broadcast Redis를 바라보도록 한다.
     * - @Qualifier: 같은 타입의 Bean을 여러 개 만든다.
     * @param connectionFactory Broadcast Redis용 ConnectionFactory
     * @return Broadcast Redis 전용 StringRedisTemplate
     */
    @Bean
    @Primary
    public StringRedisTemplate broadcastStringRedisTemplate(
            @Qualifier("broadcastRedisConnectionFactory") LettuceConnectionFactory connectionFactory
    ) {
        return createStringRedisTemplate(connectionFactory);
    }

    /**
     * Chat Pub/Sub Redis용 ConnectionFactory Bean
     * - app.redis.broadcast.* 설정값을 읽어 Broadcast Redis 연결 정보를 담은 LettuceConnectionFactory를 생성
     *
     * @param appRedisProperties 설정 파일 값이 바인딩된 AppRedisProperties Bean
     * @return Chat Redis 연결 정보를 가진 ConnectionFactory
     */
    @Bean
    public LettuceConnectionFactory chatRedisConnectionFactory(AppRedisProperties appRedisProperties) {
        return createConnectionFactory(appRedisProperties.getChat());
    }

    /**
     * Chat Pub/Sub Redis용 StringRedisTemplate Bean
     * - 실제 Redis 명령은 이 Template를 통해 수행된다.
     * - Chat Redis 전용 ConnectionFactory를 주입받아, 이 Template이 Broadcast Redis를 바라보도록 한다.
     *
     * @param connectionFactory Chat Redis용 ConnectionFactory
     * @return Chat Redis 전용 StringRedisTemplate
     */
    @Bean
    public StringRedisTemplate chatStringRedisTemplate(
            @Qualifier("chatRedisConnectionFactory") LettuceConnectionFactory connectionFactory
    ) {
        return createStringRedisTemplate(connectionFactory);
    }

    @Bean
    public RedisMessageListenerContainer chatRedisMessageListenerContainer(
            @Qualifier("chatRedisConnectionFactory") LettuceConnectionFactory connectionFactory
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }

    /**
     * 공통 Redis ConnectionFactory 생성 함수
     * {@link RedisStandaloneConfiguration}을 사용하는 이유는 현재 구성이 Sentinel이나 Cluster가 아니라 host + port 기반의 단일 Redis 접속이기 때문이다.
     *
     * @param redisProperties 개별 Redis 연결 정보
     * @return 해당 설정을 기반으로 생성된 ConnectionFactory
     */
    private LettuceConnectionFactory createConnectionFactory(AppRedisProperties.Node redisProperties) {
        // 단일 Redis 서버 접속 정보를 담는 Spring Data Redis 설정 객체를 만든다.
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration();

        // 설정 파일에서 바인딩된 host / port 값을 실제 Redis 연결 정보에 반영한다.
        configuration.setHostName(redisProperties.getHost());
        configuration.setPort(redisProperties.getPort());

        // 비밀번호는 비어 있을 수 있으므로, 실제 값이 있을 때만 적용한다.
        String password = redisProperties.getPassword();
        if (password != null && !password.isBlank()) {
            configuration.setPassword(RedisPassword.of(password));
        }

        // 위 설정을 바탕으로 Lettuce 기반 ConnectionFactory를 생성한다. 이후 StringRedisTemplate는 이 factory를 사용해 실제 연결을 획득한다.
        return new LettuceConnectionFactory(configuration);
    }

    /**
     * 공통 StringRedisTemplate 생성 함수
     *
     * {@link StringRedisTemplate}는 문자열 key / value 중심 Redis 작업에 적합한 Spring 추상화 객체다.
     * 현재 프로젝트는 JSON 문자열 저장, Refresh Token / Blacklist 문자열 저장, Pub/Sub 문자열 메시지 발행 같은 패턴이 많다.
     * 그래서 범용 {@code RedisTemplate<Object, Object>}보다 {@code StringRedisTemplate}가 더 자연스럽다.
     * 이 메서드에 어떤 ConnectionFactory를 넘기느냐에 따라, 결과 Template가 Broadcast Redis용이 될 수도 있고 Chat Redis용이 될 수도 있다.
     *
     * @param connectionFactory 특정 Redis 연결 정보를 가진 ConnectionFactory
     * @return 해당 Redis를 사용하는 StringRedisTemplate
     */
    private StringRedisTemplate createStringRedisTemplate(LettuceConnectionFactory connectionFactory) {
        // 실제 Redis 명령 실행 객체를 생성한다.
        StringRedisTemplate stringRedisTemplate = new StringRedisTemplate();

        // 이 Template가 어느 Redis에 붙을지를 지정한다.
        // broadcast factory를 넣으면 broadcast 전용 template,
        // chat factory를 넣으면 chat 전용 template가 된다.
        stringRedisTemplate.setConnectionFactory(connectionFactory);

        return stringRedisTemplate;
    }
}
