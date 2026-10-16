package com.game.login.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.game.common.GameNamespace;
import com.game.discovery.RedisKeys;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

/** 不连 Redis 的部分：令牌格式闸、值与轮换记录的解码、宽限窗口的取值范围、存储异常不带原消息。 */
class RedisLoginTokensTest {

    private static final RedisKeys REDIS_KEYS = new RedisKeys(GameNamespace.of("game"));

    @Test
    void 格式不对的令牌不碰Redis直接无效() {
        RedissonClient redis = mock(RedissonClient.class);
        RedisLoginTokens tokens = new RedisLoginTokens(redis, REDIS_KEYS, Clock.systemUTC(), Duration.ofHours(2), Duration.ofHours(720),
                Duration.ofSeconds(30), new SecureRandom());
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
    void 重放宽限窗口越界拒绝构造() {
        RedissonClient redis = mock(RedissonClient.class);
        for (Duration grace : new Duration[] {Duration.ofMillis(-1), RedisLoginTokens.MAX_REFRESH_REUSE_GRACE.plusMillis(1)}) {
            assertThatThrownBy(() -> new RedisLoginTokens(redis, REDIS_KEYS, Clock.systemUTC(), Duration.ofHours(2),
                    Duration.ofHours(720), grace, new SecureRandom()))
                    .as("grace=%s", grace).isInstanceOf(IllegalArgumentException.class);
        }
        new RedisLoginTokens(redis, REDIS_KEYS, Clock.systemUTC(), Duration.ofHours(2), Duration.ofHours(720), Duration.ZERO,
                new SecureRandom());
        new RedisLoginTokens(redis, REDIS_KEYS, Clock.systemUTC(), Duration.ofHours(2), Duration.ofHours(720),
                RedisLoginTokens.MAX_REFRESH_REUSE_GRACE, new SecureRandom());
        verifyNoInteractions(redis);
    }

    @Test
    void 轮换记录_缺新令牌或解不开按无效() {
        String access = "a".repeat(43);
        String refresh = "r".repeat(43);
        RedisLoginTokens.RotationRecord rec = new RedisLoginTokens.RotationRecord("robot_1", access, refresh, 10, 20, 5);
        String json = RedisLoginTokens.encode(rec);
        assertThat(json).contains("\"access_token\":\"" + access + "\"").contains("\"refresh_token\":\"" + refresh + "\"")
                .contains("\"access_expire\":10").contains("\"refresh_expire\":20").contains("\"rotated_at\":5");
        assertThat(RedisLoginTokens.decodeRotation(json)).contains(rec);
        assertThat(RedisLoginTokens.decodeRotation(null)).isEmpty();
        assertThat(RedisLoginTokens.decodeRotation("null")).isEmpty();
        assertThat(RedisLoginTokens.decodeRotation("{bad")).isEmpty();
        assertThat(RedisLoginTokens.decodeRotation("{\"account\":\"robot_1\",\"access_token\":\"" + access + "\"}")).isEmpty();
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
                new IllegalStateException("Command: (GET), params: [game:login:access:SECRETTOKEN]"));
        assertThat(e.getMessage()).isEqualTo("令牌存储校验失败: IllegalStateException").doesNotContain("SECRET");
        assertThat(e.getCause()).isNull();
    }
}
