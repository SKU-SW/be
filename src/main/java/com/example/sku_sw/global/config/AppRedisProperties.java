package com.example.sku_sw.global.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 다중 Redis 연결 정보를 바인딩하는 프로퍼티 클래스
 *
 * 이 클래스의 역할은 "Redis 연결 정보를 보관하는 것"이지, 실제 Redis 연결을 만들거나 Redis 명령을 수행하는 것이 아니다.
 * 즉, 이 클래스는 설정 파일의 값을 Java 객체로 묶어 주는 "설정 바인딩 전용 객체"라고 보면 된다.
 * Redis가 1개일 때는 {@code spring.data.redis.*} 자동설정만으로 충분했지만 지금은 Broadcast 저장 Redis와 Chat Pub/Sub Redis로 역할이 분리되었다.
 * 따라서 Redis 설정도 2세트가 필요하고, 이 값을 구조적으로 관리할 객체가 필요하다.
 *
 * Spring은 이 클래스 자체를 Bean 1개로 등록한다.
 * broadcast, chat은 별도 Bean이 아니라, 이 Bean 내부에 포함된 하위 값 객체이다.
 * 즉, "AppRedisProperties가 2개 생기는 것"이 아니라, "AppRedisProperties 1개 안에 broadcast와 chat 설정이 들어가는 것"이다.
 *
 * 값 바인딩 흐름
 * 1. Spring이 AppRedisProperties Bean을 생성한다.
 * 2. {@code app.redis.broadcast.*}, {@code app.redis.chat.*} 값을 읽는다.
 * 3. 읽은 값을 내부 객체 Node에 채운다.
 * 4. 그 다음 {@code RedisConfig}가 이 값을 사용해 실제 ConnectionFactory / Template Bean을 만든다.
 *
 * 왜 @Value를 여러 개 쓰지 않는가?
 * - {@code @Value("${...}")}를 여러 군데 흩뿌리면 설정이 분산된다.
 * - 반면 이 클래스는 Redis 관련 설정을 한 곳에서 모아서 관리할 수 있다.
 * - 또한 검증 어노테이션을 붙여 잘못된 설정을 더 빨리 발견할 수 있다.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.redis")
public class AppRedisProperties {
    /**
     * Broadcast 저장 Redis 설정
     *
     * 방송 대화, 방송 캐릭터 상태, Refresh Token/Blacklist 등 현재 프로젝트의 "저장 중심" Redis가 사용할 연결 정보 묶음이다.
     * 이 객체 자체는 별도 Bean이 아니라, {@code AppRedisProperties} 내부에 포함된 필드다.
     */
    @Valid
    private Node broadcast = new Node();

    /**
     * Chat Pub/Sub Redis 설정
     *
     * 향후 FastAPI ↔ Redis ↔ Spring Boot 메시지 흐름에서 사용할 Chat Pub/Sub 전용 Redis 연결 정보 묶음이다.
     */
    @Valid
    private Node chat = new Node();

    /**
     * 개별 Redis 연결 정보 구조체
     *
     * Broadcast / Chat Redis가 공통으로 필요로 하는 최소 연결 정보만 정의한다.
     * 현재는 host, port, password만 있으면 외부 Redis 연결이 가능하다.
     * 참고로 현재 구조는 Redis 인스턴스 자체를 포트로 분리했기 때문에, 논리 DB 번호(database)까지는 아직 필요하지 않다.
     * 나중에 같은 Redis 인스턴스 내부에서 논리 DB를 나눌 필요가 생기면 이 클래스에 필드를 확장하면 된다.
     */
    @Getter
    @Setter
    public static class Node {
        /**
         * Redis 서버 host 또는 IP
         *
         * 비어 있으면 어느 Redis에 연결해야 하는지 자체를 알 수 없으므로 필수값이다.
         */
        @NotBlank
        private String host;

        /**
         * Redis 서버 포트
         *
         * 0 이하 값은 유효한 TCP 포트가 아니므로 1 이상으로 검증한다.
         */
        @Min(1)
        private int port;

        /**
         * Redis 인증 비밀번호
         *
         * 운영 환경에서는 보통 사용하지만, 로컬 개발 환경에서는 비밀번호 없이 구동할 수도 있어 강제 검증은 하지 않는다.
         * 실제 적용은 {@code RedisConfig}에서 값이 비어 있지 않을 때만 password를 설정한다.
         */
        private String password;
    }
}
