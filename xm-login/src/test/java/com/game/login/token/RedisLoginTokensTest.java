package com.game.login.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

/** 不连 Redis 的部分：令牌格式闸、值的解码、存储异常不带原消息。 */
class RedisLoginTokensTest {

    @Test
    void 格式不对的令牌不碰Redis直接无效() {
        RedissonClient redis = mock(RedissonClient.class);
        RedisLoginTokens tokens = new RedisLoginTokens(redis, Clock.systemUTC(), Duration.ofHours(2), Duration.ofHours(720),
                new SecureRandom());
        assertThat(tokens.validateAccess(null)).isEmpty();
        assertThat(tokens.validateAccess("")).isEmpty();
        assertThat(tokens.validateAccess("a".repeat(42))).isEmpty();
        assertThat(tokens.validateAccess("a".repeat(44))).isEmpty();
        assertThat(tokens.validateAccess("a".repeat(42) + "=")).isEmpty();
        assertThat(tokens.validateAccess("x\nforged log line " + "a".repeat(30))).isEmpty();
        assertThat(tokens.refresh("a".repeat(42) + "/")).isEmpty();
        verifyNoInteractions(redis);
        assertThat(RedisLoginTokens.wellFormed("Ab-_" + "0".repeat(39))).isTrue();
    }

    @Test
    void 解码_JSON_null_坏JSON_没有账号都按无效() {
        assertThat(RedisLoginTokens.decode(null)).isEmpty();
        assertThat(RedisLoginTokens.decode("null")).isEmpty();
        assertThat(RedisLoginTokens.decode("{bad")).isEmpty();
        assertThat(RedisLoginTokens.decode("{\"auth_type\":\"password\"}")).isEmpty();
        assertThat(RedisLoginTokens.decode("{\"account\":\"robot_1\",\"extra\":1}"))
                .contains(new TokenData("robot_1", "", "", 0));
        TokenData data = new TokenData("robot_1", "password", "", 7);
        assertThat(RedisLoginTokens.decode(RedisLoginTokens.encode(data))).contains(data);
    }

    @Test
    void 存储异常只带操作名与类名_不带原消息() {
        TokenStoreException e = new TokenStoreException("校验",
                new IllegalStateException("Command: (GET), params: [xm:login:access:SECRETTOKEN]"));
        assertThat(e.getMessage()).isEqualTo("令牌存储校验失败: IllegalStateException").doesNotContain("SECRET");
        assertThat(e.getCause()).isNull();
    }
}
