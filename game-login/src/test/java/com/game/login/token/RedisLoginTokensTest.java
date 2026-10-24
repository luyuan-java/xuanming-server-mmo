package com.game.login.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/**
 * 不连 Redis 的部分：令牌格式闸与家族号编解码、键的 hash tag、值与轮换记录的解码、宽限窗口与 TTL 的取值范围、
 * 存储异常不带原消息、轮换脚本前的截止检查、access 校验对脚本结果的映射。
 */
class RedisLoginTokensTest {

    private static final RedisKeys REDIS_KEYS = new RedisKeys(GameNamespace.of("game"));
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    private static RedisLoginTokens tokens(RedissonClient redis) {
        return new RedisLoginTokens(redis, REDIS_KEYS, Clock.systemUTC(), Duration.ofHours(2), Duration.ofHours(720),
                Duration.ofSeconds(30), new SecureRandom());
    }

    /** 一枚属于 familyBytes 的规范令牌（家族号 16 字节 ‖ 随机 16 字节）。 */
    private static String tokenOf(byte[] familyBytes) {
        byte[] bytes = Arrays.copyOf(familyBytes, RedisLoginTokens.TOKEN_BYTES);
        byte[] tail = new byte[RedisLoginTokens.TOKEN_BYTES - RedisLoginTokens.FAMILY_BYTES];
        new SecureRandom().nextBytes(tail);
        System.arraycopy(tail, 0, bytes, RedisLoginTokens.FAMILY_BYTES, tail.length);
        return ENCODER.encodeToString(bytes);
    }

    private static byte[] randomFamily() {
        byte[] family = new byte[RedisLoginTokens.FAMILY_BYTES];
        new SecureRandom().nextBytes(family);
        return family;
    }

    /** 同一 32 字节的非规范编码：43 字符里末位只用高 4 位，规范编码低 2 位为 0；把最低位置 1。 */
    private static String nonCanonical(String token) {
        int last = ALPHABET.indexOf(token.charAt(42));
        return token.substring(0, 42) + ALPHABET.charAt(last | 1);
    }

    /** Redis Cluster 算 slot 用的子串：第一个 '{' 之后到其后第一个 '}' 之前，非空才算 tag。 */
    private static String hashTagOf(String key) {
        int open = key.indexOf('{');
        int close = open < 0 ? -1 : key.indexOf('}', open + 1);
        return open < 0 || close <= open + 1 ? key : key.substring(open + 1, close);
    }

    @Test
    void 格式不对或非规范编码的令牌不碰Redis直接无效() {
        RedissonClient redis = mock(RedissonClient.class);
        RedisLoginTokens tokens = tokens(redis);
        String valid = tokenOf(randomFamily());
        for (String bad : new String[] {null, "", "a".repeat(42), "a".repeat(44), "a".repeat(42) + "=",
                "x\nforged log line " + "a".repeat(25), "a".repeat(42) + "/", nonCanonical(valid)}) {
            assertThat(tokens.validateAccess(bad)).isEmpty();
            assertThat(tokens.refresh(bad)).isEmpty();
        }
        verifyNoInteractions(redis);
        assertThat(RedisLoginTokens.wellFormed("Ab-_" + "0".repeat(39))).isTrue();
    }

    @Test
    void 令牌嵌家族号_只认规范编码() {
        byte[] family = randomFamily();
        String familyId = ENCODER.encodeToString(family);
        String token = tokenOf(family);
        assertThat(familyId).hasSize(22).matches("[A-Za-z0-9_-]{22}");
        assertThat(RedisLoginTokens.familyOf(token)).contains(familyId);
        assertThat(RedisLoginTokens.tokenOfFamily(token, familyId)).isTrue();
        assertThat(RedisLoginTokens.tokenOfFamily(token, ENCODER.encodeToString(randomFamily()))).isFalse();
        assertThat(RedisLoginTokens.familyOf(nonCanonical(token))).isEmpty();
    }

    @Test
    void 一个家族的键同在一个Cluster_slot_账号索引不带tag() {
        String familyId = ENCODER.encodeToString(randomFamily());
        String token = tokenOf(Base64.getUrlDecoder().decode(familyId));
        for (String key : List.of(REDIS_KEYS.loginAccessToken(familyId, token), REDIS_KEYS.loginRefreshToken(familyId, token),
                REDIS_KEYS.loginRefreshFamily(familyId), REDIS_KEYS.loginRefreshRotated(familyId, token))) {
            assertThat(hashTagOf(key)).as(key).isEqualTo(familyId);
        }
        assertThat(REDIS_KEYS.loginAccountRefresh("acc")).doesNotContain("{");
    }

    @Test
    void 日志标签不暴露家族号() {
        String familyId = ENCODER.encodeToString(randomFamily());
        String tag = RedisLoginTokens.familyLogTag(familyId);
        assertThat(tag).hasSize(8).matches("[0-9a-f]{8}").isEqualTo(RedisLoginTokens.familyLogTag(familyId));
        assertThat(familyId).doesNotContain(tag);
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
                RedisLoginTokens.MAX_REFRESH_REUSE_GRACE, false, new SecureRandom());
        verifyNoInteractions(redis);
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
    void 轮换记录_缺字段_家族不符_令牌不属于家族_解不开都按无效() {
        byte[] family = randomFamily();
        String familyId = ENCODER.encodeToString(family);
        String access = tokenOf(family);
        String refresh = tokenOf(family);
        RedisLoginTokens.RotationRecord rec = new RedisLoginTokens.RotationRecord("robot_1", familyId, access, refresh, 10, 20, 5);
        String json = RedisLoginTokens.encode(rec);
        assertThat(json).contains("\"family_id\":\"" + familyId + "\"").contains("\"access_token\":\"" + access + "\"")
                .contains("\"refresh_token\":\"" + refresh + "\"").contains("\"access_expire\":10")
                .contains("\"refresh_expire\":20").contains("\"rotated_at\":5");
        assertThat(RedisLoginTokens.decodeRotation(json, familyId)).contains(rec);
        assertThat(RedisLoginTokens.decodeRotation(json, ENCODER.encodeToString(randomFamily()))).isEmpty();
        String foreign = tokenOf(randomFamily());
        assertThat(RedisLoginTokens.decodeRotation(RedisLoginTokens.encode(
                new RedisLoginTokens.RotationRecord("robot_1", familyId, access, foreign, 10, 20, 5)), familyId)).isEmpty();
        assertThat(RedisLoginTokens.decodeRotation(RedisLoginTokens.encode(
                new RedisLoginTokens.RotationRecord("", familyId, access, refresh, 10, 20, 5)), familyId)).isEmpty();
        assertThat(RedisLoginTokens.decodeRotation(null, familyId)).isEmpty();
        assertThat(RedisLoginTokens.decodeRotation("null", familyId)).isEmpty();
        assertThat(RedisLoginTokens.decodeRotation("{bad", familyId)).isEmpty();
    }

    @Test
    void 解码_身份字段不全_家族号不符_空或null_坏JSON都按无效() {
        String familyId = ENCODER.encodeToString(randomFamily());
        TokenData data = new TokenData("robot_1", "password", "", 7, familyId);
        assertThat(RedisLoginTokens.decode(RedisLoginTokens.encode(data), familyId)).contains(data);
        assertThat(RedisLoginTokens.decode(null, familyId)).isEmpty();
        assertThat(RedisLoginTokens.decode("null", familyId)).isEmpty();
        assertThat(RedisLoginTokens.decode("{bad", familyId)).isEmpty();
        assertThat(RedisLoginTokens.decode(RedisLoginTokens.encode(data), ENCODER.encodeToString(randomFamily()))).isEmpty();
        for (String family : List.of("null", "\"\"", "7", "\"bad\"")) {
            assertThat(RedisLoginTokens.decode("{\"account\":\"robot_1\",\"auth_type\":\"password\",\"created_at\":7,\"family_id\":"
                    + family + "}", familyId)).as("family_id=%s", family).isEmpty();
        }
        assertThat(RedisLoginTokens.decode("{\"account\":\"robot_1\",\"auth_type\":\"password\",\"created_at\":7}", familyId))
                .as("没有家族号的旧数据").isEmpty();
        assertThat(RedisLoginTokens.decode(RedisLoginTokens.encode(new TokenData("", "password", "", 7, familyId)), familyId)).isEmpty();
        assertThat(RedisLoginTokens.decode(RedisLoginTokens.encode(new TokenData("robot_1", "", "", 7, familyId)), familyId)).isEmpty();
        assertThat(RedisLoginTokens.decode(RedisLoginTokens.encode(new TokenData("robot_1", "password", "", 0, familyId)), familyId)).isEmpty();
    }

    @Test
    void 截止已过_读到旧refresh之后不发轮换脚本() {
        // 客户端路径的提交点是那段「作废 + 签发」的原子脚本：截止之后 gate 随时会放行同一会话的下一条请求，
        // 这时发出去，旧 refresh 会晚于下一条请求作废。只允许发生那一次 GET，脚本、重放判定、封顶一律不碰。
        RedissonClient redis = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBucket<String> oldBucket = mock(RBucket.class);
        byte[] family = randomFamily();
        String familyId = ENCODER.encodeToString(family);
        String old = tokenOf(family);
        when(redis.<String>getBucket(REDIS_KEYS.loginRefreshToken(familyId, old), StringCodec.INSTANCE)).thenReturn(oldBucket);
        when(oldBucket.get()).thenReturn(RedisLoginTokens.encode(new TokenData("robot_1", "password", "", 7, familyId)));
        RedisLoginTokens tokens = tokens(redis);
        List<String> checks = new ArrayList<>();

        assertThatThrownBy(() -> tokens.refresh(old, () -> {
            checks.add("deadline");
            return true;
        })).isInstanceOf(LoginTokens.RotationAbandoned.class).hasMessageNotContaining(old).hasMessageNotContaining(familyId);

        assertThat(checks).as("读到旧值之后看一次").containsExactly("deadline");
        verify(redis).getBucket(REDIS_KEYS.loginRefreshToken(familyId, old), StringCodec.INSTANCE);
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
    void 签发前查索引失败_不签令牌_异常不带原始参数() {
        RedissonClient redis = mock(RedissonClient.class);
        RKeys keys = mock(RKeys.class);
        when(redis.getKeys()).thenReturn(keys);
        when(keys.getType(anyString())).thenThrow(new IllegalStateException("SECRET TOKEN"));
        assertThatThrownBy(() -> tokens(redis).issue("robot_1", "password", "")).isInstanceOf(TokenStoreException.class)
                .hasMessageNotContaining("SECRET").hasMessageNotContaining("TOKEN").hasNoCause();
        assertThatThrownBy(() -> tokens(redis).issue("", "password", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tokens(redis).issue("robot_1", "", "")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void access校验只认明确成功_家族已吊销或损坏或属于别的账号都无效_存储异常不泄漏() {
        RedissonClient redis = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        byte[] family = randomFamily();
        String familyId = ENCODER.encodeToString(family);
        String access = tokenOf(family);
        String payload = RedisLoginTokens.encode(new TokenData("robot_1", "password", "", 7, familyId));
        when(redis.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(script.<List<Object>>eval(eq(RScript.Mode.READ_ONLY), anyString(), eq(RScript.ReturnType.MULTI), anyList()))
                .thenReturn(List.<Object>of("0"))
                .thenReturn(List.<Object>of("-1"))
                .thenReturn(List.<Object>of("2", payload))
                .thenReturn(List.<Object>of("1", payload, "other-account"))
                .thenReturn(List.<Object>of("1", "{bad", "robot_1"))
                .thenReturn(List.<Object>of("1", payload, "robot_1"))
                .thenThrow(new IllegalStateException("SECRET"));
        RedisLoginTokens tokens = tokens(redis);
        assertThat(tokens.validateAccess(access)).as("access 不在").isEmpty();
        assertThat(tokens.validateAccess(access)).as("损坏").isEmpty();
        assertThat(tokens.validateAccess(access)).as("家族已吊销").isEmpty();
        assertThat(tokens.validateAccess(access)).as("家族属于别的账号").isEmpty();
        assertThat(tokens.validateAccess(access)).as("值解不开").isEmpty();
        assertThat(tokens.validateAccess(access)).contains(new TokenData("robot_1", "password", "", 7, familyId));
        assertThatThrownBy(() -> tokens.validateAccess(access)).isInstanceOf(TokenStoreException.class)
                .hasMessageNotContaining("SECRET").hasNoCause();
    }
}
