package com.example.sku_sw.global.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;
import redis.embedded.RedisServer;
import redis.embedded.RedisServerBuilder;

/**
 * test 프로필에서 Broadcast Redis와 Chat Redis를 실행하는 설정 클래스.
 */
@Slf4j
@Profile("test")
@Configuration
public class EmbeddedRedisConfig {

    private static final String LOCAL_BIND_ADDRESS = "127.0.0.1";

    private final AppRedisProperties appRedisProperties;

    private RedisServer broadcastRedisServer;
    private RedisServer chatRedisServer;

    /**
     * 테스트 환경에서 사용할 Embedded Redis 설정을 생성한다.
     * - {@link AppRedisProperties}에 바인딩된 Broadcast, Chat Redis 연결 정보를 주입받는다.
     *
     * @param appRedisProperties Broadcast, Chat Redis 연결 설정
     */
    public EmbeddedRedisConfig(AppRedisProperties appRedisProperties) {
        log.info("[EmbeddedRedisConfig] 설정 초기화 | EmbeddedRedisConfig() - START");
        this.appRedisProperties = appRedisProperties;
        log.info("[EmbeddedRedisConfig] 설정 초기화 | EmbeddedRedisConfig() - END");
    }

    /**
     * Broadcast Redis와 Chat Redis 서버를 시작한다.
     * - 설정에 바인딩된 두 Redis의 포트를 검증한 후 각각 별도 프로세스로 실행한다.
     * - 일부 서버의 시작에 실패하면 이미 시작된 서버를 종료하고 예외를 다시 전달한다.
     *
     * @PostConstruct: Spring이 해당 Config 객체를 생성하고 의존성 주입을 완료한 직후, 해당 메서드를 자동 호출하게 하는 어노테이션
     */
    @PostConstruct
    public void startRedisServers() {
        log.info("[EmbeddedRedisConfig] Embedded Redis 서버 시작 | startRedisServers() - START");

        /*
            1. Redis 연결 설정 조회
            - 테스트 설정에 바인딩된 Broadcast, Chat Redis의 host, port, password 정보를 조회한다.
         */
        AppRedisProperties.Node broadcast = appRedisProperties.getBroadcast();
        AppRedisProperties.Node chat = appRedisProperties.getChat();

        /*
            2. Redis 포트 검증
            - 두 Redis 서버가 동일한 포트를 사용해 충돌하지 않도록 검증한다.
         */
        validateDifferentPorts(broadcast, chat);

        /*
            3. Embedded Redis 서버 시작
            - Broadcast Redis를 먼저 시작하고 Chat Redis를 이어서 시작한다.
            - 시작 중 예외가 발생하면 이미 실행된 Redis 서버를 모두 정리한다.
         */
        try {
            broadcastRedisServer = startRedisServer("broadcast", broadcast);
            chatRedisServer = startRedisServer("chat", chat);
        } catch (RuntimeException exception) {
            log.error("[EmbeddedRedisConfig] Embedded Redis 서버 시작 실패 | startRedisServers() - ERROR", exception);
            stopRedisServers();
            throw exception;
        }

        log.info(
                "[EmbeddedRedisConfig] Embedded Redis 서버 시작 | startRedisServers() - END | broadcastPort: {}, chatPort: {}",
                broadcast.getPort(),
                chat.getPort()
        );
    }

    /**
     * 실행 중인 Chat Redis와 Broadcast Redis 서버를 종료한다.
     * - 의존 관계 역순으로 Chat Redis를 먼저 종료하고 Broadcast Redis를 종료한다.
     * - 종료된 서버 참조를 제거해 중복 종료를 방지한다.
     */
    @PreDestroy
    public void stopRedisServers() {
        log.info("[EmbeddedRedisConfig] Embedded Redis 서버 종료 | stopRedisServers() - START");

        /*
            1. Chat Redis 서버 종료
            - Chat Redis를 종료한 후 서버 참조를 제거한다.
         */
        stopRedisServer("chat", chatRedisServer);
        chatRedisServer = null;

        /*
            2. Broadcast Redis 서버 종료
            - Broadcast Redis를 종료한 후 서버 참조를 제거한다.
         */
        stopRedisServer("broadcast", broadcastRedisServer);
        broadcastRedisServer = null;

        log.info("[EmbeddedRedisConfig] Embedded Redis 서버 종료 | stopRedisServers() - END");
    }

    /**
     * 지정된 Redis 설정으로 Embedded Redis 서버를 생성하고 시작한다.
     * - 서버는 로컬 주소에 바인딩하며, 비밀번호가 설정된 경우 requirepass 옵션을 적용한다.
     * RedisServer: Redis 서버 프로세스를 실행하고 종료하는 객체
     *
     * @param name 로그에서 Redis 용도를 구분할 이름
     * @param node Redis 서버의 host, port, password 설정
     * @return 시작된 Embedded Redis 서버
     */
    private RedisServer startRedisServer(String name, AppRedisProperties.Node node) {
        log.debug(
                "[EmbeddedRedisConfig] 개별 Embedded Redis 서버 시작 | startRedisServer() - START | name: {}, host: {}, port: {}",
                name,
                node.getHost(),
                node.getPort()
        );

        /*
            1. Embedded Redis 서버 설정 생성
            - 설정된 포트와 로컬 바인딩 주소로 RedisServerBuilder를 구성한다.
         */
        RedisServerBuilder builder = RedisServer.builder()
                .port(node.getPort())
                .bind(LOCAL_BIND_ADDRESS);

        /*
            2. Redis 비밀번호 적용
            - 비밀번호가 존재하는 경우에만 requirepass 옵션을 추가한다.
         */
        if (StringUtils.hasText(node.getPassword())) {
            builder.setting("requirepass " + node.getPassword());
        }

        /*
            3. Embedded Redis 서버 실행
            - 설정이 반영된 Redis 서버를 생성하고 프로세스를 시작한다.
         */
        RedisServer redisServer = builder.build();
        redisServer.start();

        log.debug(
                "[EmbeddedRedisConfig] 개별 Embedded Redis 서버 시작 | startRedisServer() - END | name: {}, host: {}, port: {}",
                name,
                node.getHost(),
                node.getPort()
        );
        return redisServer;
    }

    /**
     * 지정된 Embedded Redis 서버를 종료한다.
     * - 서버가 생성되지 않았으면 종료 작업을 생략한다.
     * - 종료 실패는 다른 Redis 서버의 정리를 막지 않도록 경고 로그만 남긴다.
     *
     * @param name 로그에서 Redis 용도를 구분할 이름
     * @param redisServer 종료할 Embedded Redis 서버
     */
    private void stopRedisServer(String name, RedisServer redisServer) {
        log.debug(
                "[EmbeddedRedisConfig] 개별 Embedded Redis 서버 종료 | stopRedisServer() - START | name: {}",
                name
        );

        /*
            1. Redis 서버 생성 여부 확인
            - 서버가 생성되지 않았으면 별도의 종료 작업 없이 반환한다.
         */
        if (redisServer == null) {
            log.debug(
                    "[EmbeddedRedisConfig] 개별 Embedded Redis 서버 종료 | stopRedisServer() - END | name: {}, result: skipped",
                    name
            );
            return;
        }

        /*
            2. Embedded Redis 서버 종료
            - 종료 실패 시 경고 로그를 남기고 나머지 서버의 종료 작업을 계속한다.
         */
        try {
            redisServer.stop();
        } catch (RuntimeException exception) {
            log.warn(
                    "[EmbeddedRedisConfig] 개별 Embedded Redis 서버 종료 실패 | stopRedisServer() - WARN | name: {}",
                    name,
                    exception
            );
        }

        log.debug(
                "[EmbeddedRedisConfig] 개별 Embedded Redis 서버 종료 | stopRedisServer() - END | name: {}",
                name
        );
    }

    /**
     * Broadcast Redis와 Chat Redis가 서로 다른 포트를 사용하는지 검증한다.
     * - 같은 포트를 사용하면 두 서버를 동시에 실행할 수 없으므로 예외를 발생시킨다.
     *
     * @param broadcast Broadcast Redis 연결 설정
     * @param chat Chat Redis 연결 설정
     */
    private void validateDifferentPorts(
            AppRedisProperties.Node broadcast,
            AppRedisProperties.Node chat
    ) {
        log.debug(
                "[EmbeddedRedisConfig] Redis 포트 중복 검증 | validateDifferentPorts() - START | broadcastPort: {}, chatPort: {}",
                broadcast.getPort(),
                chat.getPort()
        );

        /*
            1. Redis 포트 중복 검증
            - Broadcast Redis와 Chat Redis의 포트가 같으면 서버 시작 전에 예외를 발생시킨다.
         */
        if (broadcast.getPort() == chat.getPort()) {
            throw new IllegalStateException("Broadcast Redis와 Chat Redis는 서로 다른 포트를 사용해야 합니다.");
        }

        log.debug(
                "[EmbeddedRedisConfig] Redis 포트 중복 검증 | validateDifferentPorts() - END | result: valid"
        );
    }
}
