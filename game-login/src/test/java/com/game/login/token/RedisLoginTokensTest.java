package com.game.login.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.game.common.GameNamespace;
import com.game.discovery.RedisKeys;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RScript;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/** 不连 Redis 的部分：令牌格式闸、值与轮换记录的解码、宽限窗口的取值范围、存储异常不带原消息、轮换脚本前的截止检查。 */
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
                .contains(new TokenData("robot_1", "", "", 0, ""));
        TokenData data = new TokenData("robot_1", "password", "", 7, "f".repeat(21) + "A");
        assertThat(RedisLoginTokens.decode(RedisLoginTokens.encode(data))).contains(data);
    }

    @Test
    void 截止已过_读到旧refresh之后不发轮换脚本() {
        // 客户端路径的提交点是那段「作废 + 签发」的原子脚本：截止之后 gate 随时会放行同一会话的下一条请求，
        // 这时发出去，旧 refresh 会晚于下一条请求作废。只允许发生那一次 GET，脚本、重放判定、封顶一律不碰。
        RedissonClient redis = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBucket<String> oldBucket = mock(RBucket.class);
        String old = "o".repeat(43);
        when(redis.<String>getBucket(REDIS_KEYS.loginRefreshToken(old), StringCodec.INSTANCE)).thenReturn(oldBucket);
        when(oldBucket.get()).thenReturn("{\"account\":\"robot_1\",\"auth_type\":\"password\",\"created_at\":7}");
        RedisLoginTokens tokens = new RedisLoginTokens(redis, REDIS_KEYS, Clock.systemUTC(), Duration.ofHours(2),
                Duration.ofHours(720), Duration.ofSeconds(30), new SecureRandom());
        List<String> checks = new ArrayList<>();

        assertThatThrownBy(() -> tokens.refresh(old, () -> {
            checks.add("deadline");
            return true;
        })).isInstanceOf(LoginTokens.RotationAbandoned.class).hasMessageNotContaining(old);

        assertThat(checks).as("读到旧值之后看一次").containsExactly("deadline");
        verify(redis).getBucket(REDIS_KEYS.loginRefreshToken(old), StringCodec.INSTANCE);
        verify(oldBucket).get();
        verifyNoMoreInteractions(redis);
    }

    @Test
    void 存储异常只带操作名与类名_不带原消息() {
        TokenStoreException e = new TokenStoreException("校验",
                new IllegalStateException("Command: (GET), params: [game:login:access:SECRETTOKEN]"));
        assertThat(e.getMessage()).isEqualTo("令牌存储校验失败: IllegalStateException").doesNotContain("SECRET");
        assertThat(e.getCause()).isNull();
    }

    @Test
    void 家族记录已失效_access仍有令牌值也必须拒绝() {
        RedissonClient redis = mock(RedissonClient.class);
        @SuppressWarnings("unchecked") RBucket<String> bucket = mock(RBucket.class);
        RScript script = mock(RScript.class);
        String access = "a".repeat(43), familyId = "f".repeat(21) + "A";
        when(redis.<String>getBucket(REDIS_KEYS.loginAccessToken(access), StringCodec.INSTANCE)).thenReturn(bucket);
        when(bucket.get()).thenReturn("{\"account\":\"robot_1\",\"family_id\":\"" + familyId + "\"}");
        when(redis.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(script.<Long>eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any(Object[].class)))
                .thenReturn(0L);
        RedisLoginTokens tokens = new RedisLoginTokens(redis, REDIS_KEYS, Clock.systemUTC(), Duration.ofHours(2),
                Duration.ofHours(720), Duration.ofSeconds(30), new SecureRandom());
        assertThat(tokens.validateAccess(access)).as("家族缺失不能仅凭旧access记录放行").isEmpty();
    }

    @Test
    void 显式损坏家族不能降级为旧JSON_只有真正缺字段兼容() {
        for (String value : List.of("null", "\"\"", "7", "\"bad\"", "\"" + "f".repeat(22) + "\"")) {
            assertThat(RedisLoginTokens.decode("{\"account\":\"robot_1\",\"family_id\":" + value + "}"))
                    .as("显式损坏的family字段").isEmpty();
        }
        assertThat(RedisLoginTokens.decode("{\"account\":\"robot_1\"}").orElseThrow().familyId()).isEmpty();
    }

    @Test
    void 原子校验只接受明确成功_存储异常仍不泄漏() {
        // 真实account/head/TTL与并发原件由同类真Redis回归验证；此处只验证原子读边界的拒绝/异常映射。
        RedissonClient redis = mock(RedissonClient.class);
        @SuppressWarnings("unchecked") RBucket<String> bucket = mock(RBucket.class);
        RScript script = mock(RScript.class);
        String access = "a".repeat(43), familyId = "f".repeat(21) + "A";
        when(redis.<String>getBucket(REDIS_KEYS.loginAccessToken(access), StringCodec.INSTANCE)).thenReturn(bucket);
        when(bucket.get()).thenReturn(RedisLoginTokens.encode(new TokenData("robot_1", "password", "", 7, familyId)));
        when(redis.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(script.<Long>eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any(Object[].class)))
                .thenReturn(0L).thenReturn(null).thenReturn(-1L).thenReturn(1L)
                .thenThrow(new IllegalStateException("SECRET"));
        RedisLoginTokens tokens = new RedisLoginTokens(redis, REDIS_KEYS, Clock.systemUTC(), Duration.ofHours(2),
                Duration.ofHours(720), Duration.ofSeconds(30), new SecureRandom());
        assertThat(tokens.validateAccess(access)).isEmpty();
        assertThat(tokens.validateAccess(access)).isEmpty();
        assertThat(tokens.validateAccess(access)).isEmpty();
        assertThat(tokens.validateAccess(access)).isPresent();
        assertThatThrownBy(() -> tokens.validateAccess(access)).isInstanceOf(TokenStoreException.class)
                .hasMessageNotContaining("SECRET").hasNoCause();
    }

    @Test
    void 零或负TTL在接触Redis之前拒绝() {
        RedissonClient redis = mock(RedissonClient.class);
        for (Duration bad : List.of(Duration.ZERO, Duration.ofMillis(-1))) {
            assertThatThrownBy(() -> new RedisLoginTokens(redis, REDIS_KEYS, Clock.systemUTC(), bad,
                    Duration.ofHours(1), Duration.ZERO, new SecureRandom())).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new RedisLoginTokens(redis, REDIS_KEYS, Clock.systemUTC(), Duration.ofHours(1),
                    bad, Duration.ZERO, new SecureRandom())).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(redis);
    }

    @Test
    void 签发脚本存储失败不返回令牌_异常不带原始参数() {
        RedissonClient redis = mock(RedissonClient.class);
        when(redis.getScript(StringCodec.INSTANCE)).thenThrow(new IllegalStateException("SECRET TOKEN"));
        RedisLoginTokens tokens = new RedisLoginTokens(redis, REDIS_KEYS, Clock.systemUTC(), Duration.ofHours(2),
                Duration.ofHours(720), Duration.ZERO, new SecureRandom());
        assertThatThrownBy(() -> tokens.issue("robot_1", "password", "")).isInstanceOf(TokenStoreException.class)
                .hasMessageNotContaining("SECRET").hasMessageNotContaining("TOKEN").hasNoCause();
    }
}
