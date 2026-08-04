package com.example.sku_sw.global.config;

import com.example.sku_sw.global.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * classes 옵션으로 필요한 Bean들만 등록
 */
@SpringBootTest(classes = {
        RedisConfig.class,
        EmbeddedRedisConfig.class,
})
class EmbeddedRedisConfigTest extends IntegrationTestSupport {

    @Autowired
    @Qualifier("broadcastStringRedisTemplate")
    private StringRedisTemplate broadcastRedisTemplate;

    @Autowired
    @Qualifier("chatStringRedisTemplate")
    private StringRedisTemplate chatRedisTemplate;

    @Test
    @DisplayName("Broadcast Redis와 Chat Redis를 서로 다른 인스턴스로 연결")
    void 두_Redis_인스턴스_연결_성공() {
        // given
        String key = "embedded-redis:connection-test";

        // when
        broadcastRedisTemplate.opsForValue().set(key, "broadcast");
        chatRedisTemplate.opsForValue().set(key, "chat");

        // then
        assertThat(broadcastRedisTemplate.opsForValue().get(key)).isEqualTo("broadcast");
        assertThat(chatRedisTemplate.opsForValue().get(key)).isEqualTo("chat");
    }
}
