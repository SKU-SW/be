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

/**
 * Broadcast / Chat Redis 연결 Bean을 수동으로 등록하는 설정 클래스
 *
 * 이 클래스의 역할은 "실제 Redis 연결 관련 Bean을 만드는 것"이다.
 * {@link AppRedisProperties}가 단순히 설정값을 보관하는 객체라면, 이 클래스는 그 설정값을 읽어서 실제로 사용할 Redis 연결 객체를 생성한다.
 *
 * 왜 수동 설정이 필요한가?
 * - Spring Boot 기본 Redis 자동설정은 주로 단일 Redis 연결을 전제로 한다.
 * - 하지만 현재 프로젝트는 Broadcast 저장 Redis와 Chat Pub/Sub Redis를 분리해서 사용한다.
 * - 따라서 "어느 설정으로 어느 Redis 연결을 만들지"를 코드에서 명시해야 한다.
 *
 * 이 클래스가 만드는 Bean
 * 1. {@code broadcastRedisConnectionFactory}
 * 2. {@code broadcastStringRedisTemplate}
 * 3. {@code chatRedisConnectionFactory}
 * 4. {@code chatStringRedisTemplate}
 *
 * ConnectionFactory와 StringRedisTemplate의 차이
 * - {@code ConnectionFactory}는 "어느 Redis에 연결할지"를 정의하는 연결 정보 객체다.
 * - {@code StringRedisTemplate}는 그 연결 정보를 사용해서 실제 Redis 명령을 수행하는 도구다.
 * - 즉, Redis가 2개면 연결 정보도 2세트, 실제 사용 객체도 2세트 필요하다.
 *
 * @Qualifier가 왜 중요한가?
 * - 이 클래스는 같은 타입의 Bean을 여러 개 만든다.
 * - 예: {@code StringRedisTemplate}가 Broadcast용 1개, Chat용 1개 생성된다.
 * - 따라서 다른 클래스에서는 반드시 {@code @Qualifier("broadcastStringRedisTemplate")}처럼 어떤 Bean을 쓸지 명시해줘야 한다.
 *
 * @Primary는 왜 쓰는가?
 * - {@code @Primary}는 동일 타입 Bean이 여러 개일 때 "기본 후보"를 지정하는 장치다.
 * - 현재는 기존 Redis 사용이 대부분 Broadcast 쪽이므로 Broadcast를 기본값으로 둔다.
 * - 다만 실무적으로는 기본값에 의존하기보다 {@code @Qualifier}로 명시적으로 주입하는 편이 안전하다.
 */
@Configuration
@EnableConfigurationProperties(AppRedisProperties.class)
public class RedisConfig {
    /**
     * Broadcast 저장 Redis용 ConnectionFactory Bean
     *
     * 이 메서드는 {@code app.redis.broadcast.*} 설정값을 읽어 Broadcast Redis 연결 정보를 담은 {@link LettuceConnectionFactory}를 만든다.
     * 중요한 점은, 이 객체가 비즈니스 코드에서 직접 Redis 명령을 수행하는 객체는 아니라는 것이다.
     * 이 객체는 "Broadcast Redis에 어떻게 연결할지"를 알고 있는 팩토리 역할을 한다.
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
     *
     * 실제 Redis 명령은 이 Template를 통해 수행된다.
     * 예를 들면 {@code opsForValue().set(...)} / {@code get(...)} / {@code delete(...)} / {@code execute(...)} 같은 작업이 이 객체를 통해 이뤄진다.
     * 여기서는 Broadcast 전용 ConnectionFactory를 주입받아, 이 Template가 Broadcast Redis만 바라보도록 만든다.
     *
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
     *
     * 이 메서드는 {@code app.redis.chat.*} 설정값을 사용해 Chat Redis 연결 정보를 담은 {@link LettuceConnectionFactory}를 만든다.
     * 향후 Chat 메시지 publish / subscribe 등은 이 연결 계열을 통해 처리된다.
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
     *
     * 이 Template는 Chat Redis에 대해 문자열 기반 publish / 저장 / 조회 작업을 수행하는 객체다.
     * 현재는 주로 {@code ChatRedisUtil}에서 {@code convertAndSend(...)} 형태로 사용할 수 있도록 준비해 둔 구조다.
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

    /**
     * 공통 Redis ConnectionFactory 생성 함수
     *
     * Broadcast Redis와 Chat Redis는 역할은 다르지만, 연결을 만드는 방식 자체는 동일하다.
     * 둘 다 host / port / password 조합으로 단일 Redis 서버에 연결한다는 점이 같기 때문이다.
     * 그래서 ConnectionFactory 생성 로직을 한 군데로 모아 중복을 줄였다.
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
        // 즉 로컬 개발처럼 비밀번호 없이 구동하는 Redis도 대응 가능하다.
        String password = redisProperties.getPassword();
        if (password != null && !password.isBlank()) {
            configuration.setPassword(RedisPassword.of(password));
        }

        // 위 설정을 바탕으로 Lettuce 기반 ConnectionFactory를 생성한다.
        // 이후 StringRedisTemplate는 이 factory를 사용해 실제 연결을 획득한다.
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
