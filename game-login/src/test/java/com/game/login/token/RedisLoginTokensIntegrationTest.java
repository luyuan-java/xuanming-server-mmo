package com.game.login.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.game.common.GameNamespace;
import com.game.discovery.RedisKeys;
import com.game.login.token.RedisLoginTokens.Kind;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RMap;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RType;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

/**
 * 连真 Redis（{@code -Dgame.it.redis=redis://127.0.0.1:6379}，缺省跳过）：令牌家族的签发 / 校验 / 轮换 / 宽限重放 / 重用吊销 /
 * 封顶，以及各类损坏一律拒绝且不写（覆盖面取自 2026-10-23 Codex 版的用例，与它有意不同的取舍在用例注释里写明）。
 * DB 13，账号带随机后缀，只清本用例登记过的键，不 FLUSH、不 SCAN。与 mmorpg {@code token_family_test.go} 同一组语义。
 */
@EnabledIfSystemProperty(named = "game.it.redis", matches = ".+")
class RedisLoginTokensIntegrationTest {

    private static final RedisKeys REDIS_KEYS = new RedisKeys(GameNamespace.of("game"));

    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");
    private static final Duration ACCESS = Duration.ofHours(2);
    private static final Duration REFRESH = Duration.ofHours(720);
    private static final Duration GRACE = Duration.ofSeconds(30);

    private RedissonClient redis;
    private RedisLoginTokens tokens;
    private final String account = "robot_it_" + UUID.randomUUID().toString().substring(0, 8);
    private final List<String> touched = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("game.it.redis")).setDatabase(13);
        redis = Redisson.create(config);
        tokens = with(GRACE, true);
    }

    @AfterEach
    void tearDown() {
        touched.add(REDIS_KEYS.loginAccountRefresh(account));
        try {
            redis.getKeys().delete(touched.toArray(String[]::new));
        } finally {
            redis.shutdown();
        }
    }

    private RedisLoginTokens with(Duration grace, boolean revoke) {
        return new RedisLoginTokens(redis, REDIS_KEYS, Clock.fixed(NOW, ZoneOffset.UTC), ACCESS, REFRESH, grace, revoke,
                new SecureRandom());
    }

    private static String familyId(TokenPair pair) {
        return RedisLoginTokens.familyOf(pair.refreshToken()).orElseThrow();
    }

    private String accessKey(TokenPair pair) {
        return REDIS_KEYS.loginAccessToken(familyId(pair), pair.accessToken());
    }

    private String refreshKey(TokenPair pair) {
        return REDIS_KEYS.loginRefreshToken(familyId(pair), pair.refreshToken());
    }

    private String familyKey(TokenPair pair) {
        return REDIS_KEYS.loginRefreshFamily(familyId(pair));
    }

    private String rotatedKey(TokenPair pair) {
        return REDIS_KEYS.loginRefreshRotated(familyId(pair), pair.refreshToken());
    }

    private RMap<String, String> family(TokenPair pair) {
        return redis.getMap(familyKey(pair), StringCodec.INSTANCE);
    }

    private RScoredSortedSet<String> index(String owner) {
        return redis.getScoredSortedSet(REDIS_KEYS.loginAccountRefresh(owner), StringCodec.INSTANCE);
    }

    private void track(TokenPair pair) {
        touched.add(accessKey(pair));
        touched.add(refreshKey(pair));
        touched.add(familyKey(pair));
    }

    private TokenPair issue() {
        return issue(account);
    }

    private TokenPair issue(String owner) {
        TokenPair pair = tokens.issue(owner, "password", "");
        track(pair);
        if (!owner.equals(account)) {
            touched.add(REDIS_KEYS.loginAccountRefresh(owner));
        }
        return pair;
    }

    /** 轮换并登记要清理的键（新的一对、旧 refresh 的轮换记录）。 */
    private Optional<TokenPair> refresh(String refreshToken) {
        RedisLoginTokens.familyOf(refreshToken)
                .ifPresent(f -> touched.add(REDIS_KEYS.loginRefreshRotated(f, refreshToken)));
        Optional<TokenPair> pair = tokens.refresh(refreshToken);
        pair.ifPresent(this::track);
        return pair;
    }

    private boolean exists(String key) {
        return redis.getKeys().countExists(key) == 1;
    }

    /** 一组键的精确快照：值（DUMP）与是否带 TTL。拒绝路径前后比对它，证明一个字节都没写。 */
    private Map<String, String> snapshot(String... keys) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : keys) {
            var object = redis.getBucket(key);
            byte[] dump = object.dump();
            out.put(key, (dump == null ? "missing" : HexFormat.of().formatHex(dump)) + " ttl>0=" + (object.remainTimeToLive() > 0));
        }
        return out;
    }

    private Map<String, String> familySnapshot(TokenPair pair) {
        return snapshot(accessKey(pair), refreshKey(pair), familyKey(pair), REDIS_KEYS.loginAccountRefresh(account),
                rotatedKey(pair));
    }

    /** 等真实 PX 过期（有上限的等待），不靠本地时钟判断。 */
    private void awaitGone(String key, Duration limit) throws InterruptedException {
        long deadline = System.nanoTime() + limit.toNanos();
        while (exists(key) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(exists(key)).as("键按 TTL 过期").isFalse();
    }

    @Test
    void 签发_43字符_两枚令牌同家族_类型位分开_家族记录与头同TTL_索引记家族() {
        TokenPair pair = issue();
        String id = familyId(pair);

        assertThat(pair.accessToken()).hasSize(43).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(pair.refreshToken());
        assertThat(RedisLoginTokens.parse(pair.accessToken())).contains(new RedisLoginTokens.Parsed(id, Kind.ACCESS));
        assertThat(RedisLoginTokens.parse(pair.refreshToken())).contains(new RedisLoginTokens.Parsed(id, Kind.REFRESH));
        assertThat(Base64.getUrlDecoder().decode(id)).hasSize(16);
        assertThat(pair.accessExpireSeconds()).isEqualTo(NOW.getEpochSecond() + 7200);
        assertThat(pair.refreshExpireSeconds()).isEqualTo(NOW.getEpochSecond() + 720 * 3600);
        RBucket<String> access = redis.getBucket(accessKey(pair), StringCodec.INSTANCE);
        assertThat(access.remainTimeToLive()).isBetween(ACCESS.toMillis() - 60_000, ACCESS.toMillis());
        assertThat(access.get()).contains("\"account\":\"" + account + "\"").contains("\"auth_type\":\"password\"")
                .contains("\"created_at\":" + NOW.getEpochSecond()).contains("\"family_id\":\"" + id + "\"");
        assertThat(redis.getBucket(refreshKey(pair)).remainTimeToLive()).isBetween(REFRESH.toMillis() - 60_000, REFRESH.toMillis());
        assertThat(family(pair).readAllMap()).containsEntry("account", account).containsEntry("head", pair.refreshToken())
                .containsEntry("prev", "").containsEntry("created_at", String.valueOf(NOW.getEpochSecond()));
        assertThat(family(pair).remainTimeToLive()).isBetween(REFRESH.toMillis() - 60_000, REFRESH.toMillis());
        assertThat(index(account).getScore(id)).isEqualTo((double) pair.refreshExpireSeconds());
        assertThat(index(account).remainTimeToLive()).isPositive();
        assertThat(familyId(issue())).as("同账号再登录是另一个家族").isNotEqualTo(id);
    }

    @Test
    void 校验access_refresh不能当access用_access不能当refresh用() {
        TokenPair pair = issue();
        assertThat(tokens.validateAccess(pair.accessToken()))
                .contains(new TokenData(account, "password", "", NOW.getEpochSecond(), familyId(pair)));
        assertThat(tokens.validateAccess(pair.refreshToken())).isEmpty();
        assertThat(tokens.validateAccess("")).isEmpty();
        assertThat(tokens.validateAccess("nope")).isEmpty();

        Map<String, String> before = familySnapshot(pair);
        assertThat(refresh(pair.accessToken())).as("access 当 refresh 发来：普通无效，不当成重用").isEmpty();
        assertThat(familySnapshot(pair)).isEqualTo(before);
        assertThat(refresh(pair.refreshToken())).isPresent();
    }

    @Test
    void 轮换沿用家族_推进head_续索引_旧access仍有效() {
        TokenPair first = issue();
        TokenPair second = refresh(first.refreshToken()).orElseThrow();

        assertThat(familyId(second)).isEqualTo(familyId(first));
        assertThat(exists(refreshKey(first))).as("旧 refresh 作废").isFalse();
        assertThat(family(second).readAllMap()).containsEntry("head", second.refreshToken())
                .containsEntry("prev", first.refreshToken()).containsEntry("created_at", String.valueOf(NOW.getEpochSecond()));
        assertThat(index(account).readAll()).containsExactly(familyId(first));
        assertThat(tokens.validateAccess(first.accessToken())).as("正常轮换不撤销同家族旧 access").isPresent();
        assertThat(tokens.validateAccess(second.accessToken()).orElseThrow().authType()).isEqualTo("password");
    }

    @Test
    void 截止已过_不发轮换脚本_旧refresh仍可用_之后照常轮换() {
        TokenPair first = issue();
        Map<String, String> before = familySnapshot(first);

        assertThatThrownBy(() -> tokens.refresh(first.refreshToken(), () -> true))
                .isInstanceOf(LoginTokens.RotationAbandoned.class);

        assertThat(familySnapshot(first)).as("一个字节都没写").isEqualTo(before);
        assertThat(refresh(first.refreshToken())).as("客户端拿原 token 在截止之内重试，照常轮换").isPresent();
    }

    @Test
    void 宽限窗口内重放旧refresh_返回同一对_不再签发_记录TTL即窗口() {
        TokenPair first = issue();
        TokenPair second = refresh(first.refreshToken()).orElseThrow();

        var record = redis.getBucket(rotatedKey(first), StringCodec.INSTANCE);
        assertThat(record.remainTimeToLive()).isBetween(GRACE.toMillis() - 5_000, GRACE.toMillis());
        Map<String, String> before = familySnapshot(second);
        assertThat(refresh(first.refreshToken())).as("回包丢失后拿原 token 重试").contains(second);
        assertThat(refresh(first.refreshToken())).as("再重试一次也一样").contains(second);
        assertThat(familySnapshot(second)).as("重放不写").isEqualTo(before);
        assertThat(refresh(second.refreshToken())).as("重放拿到的 refresh 照常能轮换").isPresent();
    }

    /**
     * 窗口内重放、而它的后继已经被轮换走、家族还活着：不是丢了回包的重试（拿到后继的一方已经用过它），说明有两方在用同一条链，
     * 按重用吊销。堵住「攻击者在窗口内连续轮换两次，合法方的重放就只得到普通无效」的绕过。
     */
    @Test
    void 新refresh被轮换走之后_窗口内重放旧refresh即重用_吊销家族() {
        TokenPair first = issue();
        TokenPair second = refresh(first.refreshToken()).orElseThrow();
        TokenPair third = refresh(second.refreshToken()).orElseThrow();

        assertThat(exists(rotatedKey(first))).as("仍在窗口内").isTrue();
        assertThat(refresh(first.refreshToken())).isEmpty();
        assertThat(exists(familyKey(first))).as("家族已吊销").isFalse();
        assertThat(exists(refreshKey(third))).as("家族头一并删除").isFalse();
        assertThat(tokens.validateAccess(third.accessToken())).isEmpty();
        assertThat(refresh(second.refreshToken())).as("窗口内重放第二枚：家族已不在").isEmpty();
        assertThat(refresh(third.refreshToken())).as("持有家族头的一方随之出局").isEmpty();
    }

    @Test
    void 宽限窗口内重放_新refresh的键过期或被删而家族头仍指着它_普通无效_不写() {
        TokenPair first = issue();
        TokenPair second = refresh(first.refreshToken()).orElseThrow();
        redis.getKeys().delete(refreshKey(second));
        Map<String, String> before = familySnapshot(second);
        Map<String, String> record = snapshot(rotatedKey(first));

        assertThat(refresh(first.refreshToken())).isEmpty();
        assertThat(familySnapshot(second)).as("不是两方在用，不吊销、不写").isEqualTo(before);
        assertThat(snapshot(rotatedKey(first))).isEqualTo(record);
        assertThat(tokens.validateAccess(second.accessToken())).isPresent();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 轮换记录在GET之后刚好过期_按没有记录处理_交给重用检测() {
        TokenPair first = issue();
        TokenPair second = refresh(first.refreshToken()).orElseThrow();
        String key = rotatedKey(first);
        RBucket<String> realRecord = redis.getBucket(key, StringCodec.INSTANCE);
        RedissonClient forwarding = mock(RedissonClient.class, delegatesTo(redis));
        RBucket<String> expiring = mock(RBucket.class, delegatesTo(realRecord));
        doReturn(expiring).when(forwarding).getBucket(key, StringCodec.INSTANCE);
        doAnswer(invocation -> {
            String value = realRecord.get();
            realRecord.delete();
            return value;
        }).when(expiring).get();
        tokens = new RedisLoginTokens(forwarding, REDIS_KEYS, Clock.fixed(NOW, ZoneOffset.UTC), ACCESS, REFRESH, GRACE,
                new SecureRandom());

        assertThat(refresh(first.refreshToken())).isEmpty();
        assertThat(exists(familyKey(second))).as("后继还活着、头不是旧 token：重用，吊销").isFalse();
        assertThat(tokens.validateAccess(second.accessToken())).isEmpty();
    }

    @Test
    void 宽限窗口内并发轮换同一个refresh_全部拿到同一对_只签发一次() throws Exception {
        TokenPair pair = issue();
        List<Optional<TokenPair>> results = race(pair.refreshToken(), 8);

        assertThat(results).allSatisfy(r -> assertThat(r).isPresent());
        TokenPair won = results.get(0).orElseThrow();
        assertThat(results).allSatisfy(r -> assertThat(r).contains(won));
        assertThat(family(won).readAllMap()).containsEntry("head", won.refreshToken()).containsEntry("prev", pair.refreshToken());
    }

    @Test
    void 窗口外重放前一枚_吊销整个家族_access随之失效_同账号其他登录不受影响() throws Exception {
        tokens = with(Duration.ofMillis(300), true);
        TokenPair first = issue();
        TokenPair second = refresh(first.refreshToken()).orElseThrow();
        TokenPair other = issue();

        // 窗口靠记录的 PX 过期实现
        awaitGone(rotatedKey(first), Duration.ofSeconds(3));

        assertThat(refresh(first.refreshToken())).isEmpty();
        assertThat(exists(familyKey(first))).as("家族已吊销").isFalse();
        assertThat(exists(refreshKey(second))).as("家族头一并删除").isFalse();
        assertThat(index(account).contains(familyId(first))).as("已从账号索引摘掉").isFalse();
        assertThat(tokens.validateAccess(first.accessToken())).isEmpty();
        assertThat(tokens.validateAccess(second.accessToken())).isEmpty();
        assertThat(refresh(second.refreshToken())).as("持有家族头的一方随之出局").isEmpty();

        assertThat(tokens.validateAccess(other.accessToken())).as("另一次登录不受影响").isPresent();
        assertThat(refresh(other.refreshToken())).isPresent();
    }

    @Test
    void 宽限为零_同一请求第二份即重用_刚签出的一对随之作废() {
        tokens = with(Duration.ZERO, true);
        TokenPair first = issue();
        TokenPair second = refresh(first.refreshToken()).orElseThrow();
        assertThat(exists(rotatedKey(first))).as("窗口为 0 不写轮换记录").isFalse();

        assertThat(refresh(first.refreshToken())).isEmpty();
        assertThat(tokens.validateAccess(second.accessToken())).isEmpty();
        assertThat(refresh(second.refreshToken())).isEmpty();
    }

    /**
     * 至多一份签出新的一对，另一份就是重用，家族随之吊销。赢家要么在吊销之前已经返回（它拿到的那一对随即失效），要么在续索引时
     * 看到家族已被吊销而改回无效；无论哪种，都不会留下可用的令牌。后一种交错下没交出去的那一对 access 留到 TTL（DB 13，2h）。
     */
    @Test
    void 宽限为零_并发轮换同一个refresh_至多一个成功_家族随后被吊销() throws Exception {
        tokens = with(Duration.ZERO, true);
        TokenPair pair = issue();
        List<Optional<TokenPair>> results = race(pair.refreshToken(), 8);
        List<TokenPair> won = results.stream().flatMap(Optional::stream).toList();
        assertThat(won).hasSizeLessThanOrEqualTo(1);
        assertThat(exists(familyKey(pair))).isFalse();
        for (TokenPair winner : won) {
            assertThat(tokens.validateAccess(winner.accessToken())).isEmpty();
            assertThat(refresh(winner.refreshToken())).isEmpty();
        }
    }

    @Test
    void 只观测_检测到重用不吊销_一个字节都不写() {
        tokens = with(Duration.ZERO, false);
        TokenPair first = issue();
        TokenPair second = refresh(first.refreshToken()).orElseThrow();
        Map<String, String> before = familySnapshot(second);

        assertThat(refresh(first.refreshToken())).isEmpty();
        assertThat(familySnapshot(second)).isEqualTo(before);
        assertThat(tokens.validateAccess(second.accessToken())).isPresent();
        assertThat(refresh(second.refreshToken())).isPresent();
    }

    @Test
    void 判定为重用但吊销失败_抛存储异常_不带令牌_家族仍在() {
        TokenPair first = issue();
        TokenPair second = refresh(first.refreshToken()).orElseThrow();
        // 让吊销脚本明确拒绝（家族记录缺账号字段 = 归属对不上），并去掉轮换记录，直接走重用检测
        family(second).remove("account");
        redis.getKeys().delete(rotatedKey(first));

        assertThatThrownBy(() -> tokens.refresh(first.refreshToken())).isInstanceOf(TokenStoreException.class)
                .hasMessageNotContaining(first.refreshToken()).hasMessageNotContaining(familyId(first)).hasNoCause();
        assertThat(exists(familyKey(first))).as("没能吊销：家族还活着，等运维处理").isTrue();
    }

    @Test
    void 伪造家族号加任意字节_等同重放旧成员_吊销该家族() {
        TokenPair pair = issue();
        byte[] forged = new byte[32];
        new SecureRandom().nextBytes(forged);
        System.arraycopy(Base64.getUrlDecoder().decode(familyId(pair)), 0, forged, 0, 16);
        forged[RedisLoginTokens.FAMILY_BYTES] &= (byte) ~RedisLoginTokens.ACCESS_KIND_BIT;
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(forged);

        assertThat(refresh(token)).isEmpty();
        assertThat(exists(familyKey(pair))).isFalse();
        assertThat(tokens.validateAccess(pair.accessToken())).isEmpty();
    }

    @Test
    void 未知家族_或头的键已消失_都只是普通无效_不写() {
        TokenPair pair = issue();
        byte[] stranger = new byte[32];
        new SecureRandom().nextBytes(stranger);
        assertThat(refresh(Base64.getUrlEncoder().withoutPadding().encodeToString(stranger))).isEmpty();

        redis.getKeys().delete(refreshKey(pair));
        Map<String, String> before = familySnapshot(pair);
        assertThat(refresh(pair.refreshToken())).as("头的键没了而家族还指着它：不是重用").isEmpty();
        assertThat(familySnapshot(pair)).isEqualTo(before);
    }

    @Test
    void 家族已吊销_access与refresh及宽限重放均拒绝_不写_不重建家族() {
        TokenPair first = issue();
        TokenPair second = refresh(first.refreshToken()).orElseThrow();
        redis.getKeys().delete(familyKey(second));
        Map<String, String> before = familySnapshot(second);

        assertThat(tokens.validateAccess(first.accessToken())).isEmpty();
        assertThat(tokens.validateAccess(second.accessToken())).isEmpty();
        assertThat(refresh(second.refreshToken())).isEmpty();
        assertThat(refresh(first.refreshToken())).as("宽限记录不是恢复被吊销家族的凭据").isEmpty();
        assertThat(familySnapshot(second)).isEqualTo(before);
    }

    /**
     * 损坏一律拒绝且一个字节都不写。与 Codex 版有意不同：头被改写、或头的键消失时 access 仍可用——access 只看它自己与家族记录，
     * 读头要在脚本里拼未声明的键，上不了 Cluster。
     */
    @Test
    void 损坏一律拒绝且不写() {
        record Case(String kind, boolean accessOk, boolean refreshOk) {
        }
        for (Case c : List.of(new Case("family-other-account", false, false), new Case("family-wrong-type", false, false),
                new Case("family-persistent", false, false), new Case("stale-head", true, false),
                new Case("refresh-persistent", true, false), new Case("access-persistent", false, true),
                new Case("payload-foreign-family", false, false), new Case("payload-empty-family", false, false),
                new Case("payload-null-family", false, false), new Case("payload-missing-account", false, false))) {
            TokenPair pair = issue();
            String id = familyId(pair);
            switch (c.kind()) {
                case "family-other-account" -> family(pair).put("account", account + "_other");
                case "family-wrong-type" -> {
                    redis.getKeys().delete(familyKey(pair));
                    redis.getBucket(familyKey(pair), StringCodec.INSTANCE).set("broken", REFRESH);
                }
                case "family-persistent" -> assertThat(family(pair).clearExpire()).isTrue();
                case "stale-head" -> family(pair).put("head", "x".repeat(43));
                case "refresh-persistent" -> assertThat(redis.getBucket(refreshKey(pair)).clearExpire()).isTrue();
                case "access-persistent" -> assertThat(redis.getBucket(accessKey(pair)).clearExpire()).isTrue();
                default -> {
                    String payload = switch (c.kind()) {
                        case "payload-foreign-family" -> RedisLoginTokens.encode(new TokenData(account, "password", "",
                                NOW.getEpochSecond(), RedisLoginTokens.familyOf(issue().refreshToken()).orElseThrow()));
                        case "payload-empty-family" -> RedisLoginTokens.encode(new TokenData(account, "password", "",
                                NOW.getEpochSecond(), ""));
                        case "payload-null-family" -> "{\"account\":\"" + account
                                + "\",\"auth_type\":\"password\",\"created_at\":1,\"family_id\":null}";
                        default -> RedisLoginTokens.encode(new TokenData("", "password", "", NOW.getEpochSecond(), id));
                    };
                    redis.getBucket(accessKey(pair), StringCodec.INSTANCE).set(payload, ACCESS);
                    redis.getBucket(refreshKey(pair), StringCodec.INSTANCE).set(payload, REFRESH);
                }
            }
            Map<String, String> before = familySnapshot(pair);
            assertThat(tokens.validateAccess(pair.accessToken()).isPresent()).as(c.kind() + " access").isEqualTo(c.accessOk());
            if (c.refreshOk()) {
                assertThat(refresh(pair.refreshToken())).as(c.kind() + " refresh").isPresent();
                continue;
            }
            Optional<TokenPair> rotated;
            try {
                rotated = refresh(pair.refreshToken());
            } catch (TokenStoreException damaged) {
                rotated = Optional.empty();
            }
            assertThat(rotated).as(c.kind() + " refresh").isEmpty();
            assertThat(familySnapshot(pair)).as(c.kind() + " 不写").isEqualTo(before);
        }
    }

    /**
     * 轮换记录的剩余 TTL：被 PERSIST、或超过 60s 硬上限，按损坏无效（不能借它延长窗口）；落在（当前窗口, 硬上限] 之间
     * （窗口刚调小后的过渡期）按窗口外普通无效。三种都不写、不吊销。
     */
    @Test
    void 轮换记录被PERSIST_续过硬上限_或比当前窗口长_都不能延长宽限窗口() {
        TokenPair first = issue();
        TokenPair second = refresh(first.refreshToken()).orElseThrow();
        var record = redis.getBucket(rotatedKey(first), StringCodec.INSTANCE);
        for (Runnable mutate : List.<Runnable>of(() -> assertThat(record.clearExpire()).isTrue(),
                () -> assertThat(record.expire(RedisLoginTokens.MAX_REFRESH_REUSE_GRACE.plusSeconds(30))).isTrue(),
                () -> assertThat(record.expire(GRACE.plusSeconds(15))).isTrue())) {
            mutate.run();
            Map<String, String> before = familySnapshot(second);
            assertThat(refresh(first.refreshToken())).isEmpty();
            assertThat(familySnapshot(second)).as("不写").isEqualTo(before);
            assertThat(tokens.validateAccess(second.accessToken())).as("拒绝不吊销").isPresent();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void GET后原件已变化_轮换按值比对失败_不覆盖新值() {
        TokenPair first = issue();
        String key = refreshKey(first);
        RBucket<String> originalBucket = redis.getBucket(key, StringCodec.INSTANCE);
        String replacement = RedisLoginTokens.encode(new TokenData(account, "changed", "", NOW.getEpochSecond(), familyId(first)));
        Map<String, String> beforeFamily = family(first).readAllMap();
        RedissonClient forwarding = mock(RedissonClient.class, delegatesTo(redis));
        RBucket<String> interleaved = mock(RBucket.class, delegatesTo(originalBucket));
        doReturn(interleaved).when(forwarding).getBucket(key, StringCodec.INSTANCE);
        doAnswer(invocation -> {
            String snapshot = originalBucket.get();
            originalBucket.set(replacement, REFRESH);
            return snapshot;
        }).when(interleaved).get();
        tokens = new RedisLoginTokens(forwarding, REDIS_KEYS, Clock.fixed(NOW, ZoneOffset.UTC), ACCESS, REFRESH, GRACE, new SecureRandom());

        assertThat(refresh(first.refreshToken())).isEmpty();
        assertThat(originalBucket.get()).isEqualTo(replacement);
        assertThat(family(first).readAllMap()).isEqualTo(beforeFamily);
        assertThat(exists(rotatedKey(first))).isFalse();
    }

    @Test
    void 账号索引类型损坏_签发在写任何键之前拒绝_轮换不依赖索引也不给坏键续期() {
        TokenPair pair = issue();
        String indexKey = REDIS_KEYS.loginAccountRefresh(account);
        redis.getKeys().delete(indexKey);
        redis.getBucket(indexKey, StringCodec.INSTANCE).set("wrong-type");
        List<String> generated = new ArrayList<>();
        SecureRandom recording = new SecureRandom() {
            @Override
            public void nextBytes(byte[] bytes) {
                super.nextBytes(bytes);
                generated.add("used");
            }
        };
        tokens = new RedisLoginTokens(redis, REDIS_KEYS, Clock.fixed(NOW, ZoneOffset.UTC), ACCESS, REFRESH, GRACE, recording);

        assertThatThrownBy(() -> tokens.issue(account, "password", "")).isInstanceOf(TokenStoreException.class);
        assertThat(generated).as("拒绝发生在造任何令牌之前").isEmpty();
        assertThat(redis.getBucket(indexKey, StringCodec.INSTANCE).get()).isEqualTo("wrong-type");
        assertThat(refresh(pair.refreshToken())).as("索引只是二级索引，令牌照常轮换").isPresent();
        assertThat(redis.getBucket(indexKey, StringCodec.INSTANCE).get()).as("续 score 的脚本先核类型，不碰坏键").isEqualTo("wrong-type");
        assertThat(redis.getBucket(indexKey).remainTimeToLive()).as("也不给坏键续期").isEqualTo(-1L);
    }

    @Test
    void 账号索引丢失_令牌照常可用_轮换把家族补登记回去() {
        TokenPair pair = issue();
        redis.getKeys().delete(REDIS_KEYS.loginAccountRefresh(account));
        assertThat(tokens.validateAccess(pair.accessToken())).isPresent();
        TokenPair next = refresh(pair.refreshToken()).orElseThrow();
        assertThat(index(account).getScore(familyId(pair))).isEqualTo((double) next.refreshExpireSeconds());
    }

    /**
     * 同一组参数的签发再来一次（等同驱动对超时的重发：固定时钟 + 固定随机数让两次的内容逐字节相同）按已写入返回同一对；
     * 内容不同而键撞上，报撞键，不覆盖、不撤回已有的键。
     */
    @Test
    void 签发的重发按已写入返回_撞上别人的键报错且不动已有键() {
        byte[] pattern = new byte[16];
        new SecureRandom().nextBytes(pattern);
        SecureRandom repeating = new SecureRandom() {
            @Override
            public void nextBytes(byte[] bytes) {
                for (int i = 0; i < bytes.length; i++) {
                    bytes[i] = pattern[i % pattern.length];
                }
            }
        };
        tokens = new RedisLoginTokens(redis, REDIS_KEYS, Clock.fixed(NOW, ZoneOffset.UTC), ACCESS, REFRESH, GRACE, repeating);
        TokenPair first = issue();
        assertThat(issue()).as("同一次签发的重发").isEqualTo(first);
        assertThat(index(account).readAll()).containsExactly(familyId(first));

        Map<String, String> before = familySnapshot(first);
        assertThatThrownBy(() -> tokens.issue(account, "satoken", "")).as("内容不同：撞键").isInstanceOf(TokenStoreException.class)
                .hasNoCause();
        assertThat(familySnapshot(first)).as("撞键不覆盖、不撤回已有键").isEqualTo(before);
        assertThat(tokens.validateAccess(first.accessToken())).isPresent();
    }

    /** 家族写入之后、登记索引之前索引坏了：签发失败，并按值撤回刚写的三个键（它们没有交给任何人）。 */
    @Test
    void 登记索引失败_签发失败并撤回刚写的家族() {
        TokenPair existing = issue();
        String indexKey = REDIS_KEYS.loginAccountRefresh(account);
        redis.getKeys().delete(indexKey);
        redis.getBucket(indexKey, StringCodec.INSTANCE).set("wrong-type");
        List<byte[]> random = new ArrayList<>();
        SecureRandom recording = new SecureRandom() {
            @Override
            public void nextBytes(byte[] bytes) {
                super.nextBytes(bytes);
                random.add(bytes.clone());
            }
        };
        // 让签发前的类型预检看到 zset，脚本写完家族之后登记索引才失败
        RedissonClient forwarding = mock(RedissonClient.class, delegatesTo(redis));
        RKeys realKeys = redis.getKeys();
        RKeys keys = mock(RKeys.class, delegatesTo(realKeys));
        doReturn(RType.ZSET).when(keys).getType(indexKey);
        doReturn(keys).when(forwarding).getKeys();
        tokens = new RedisLoginTokens(forwarding, REDIS_KEYS, Clock.fixed(NOW, ZoneOffset.UTC), ACCESS, REFRESH, GRACE, recording);

        assertThatThrownBy(() -> tokens.issue(account, "password", "")).isInstanceOf(TokenStoreException.class);
        assertThat(random).as("家族号、access 随机半段、refresh 随机半段").hasSize(3);
        String familyId = Base64.getUrlEncoder().withoutPadding().encodeToString(random.get(0));
        String access = tokenFrom(random.get(0), random.get(1), Kind.ACCESS);
        String refresh = tokenFrom(random.get(0), random.get(2), Kind.REFRESH);
        List<String> written = List.of(REDIS_KEYS.loginAccessToken(familyId, access), REDIS_KEYS.loginRefreshToken(familyId, refresh),
                REDIS_KEYS.loginRefreshFamily(familyId));
        touched.addAll(written);
        for (String key : written) {
            assertThat(exists(key)).as("没交出去的家族已撤回").isFalse();
        }
        assertThat(tokens.validateAccess(existing.accessToken())).as("已有家族不受影响").isPresent();
    }

    /** 同 {@code RedisLoginTokens#newToken} 的格式：家族号 16 字节 ‖ 随机 16 字节，随机半段首字节最高位是类型位。 */
    private static String tokenFrom(byte[] family, byte[] tail, Kind kind) {
        byte[] bytes = Arrays.copyOf(family, RedisLoginTokens.TOKEN_BYTES);
        System.arraycopy(tail, 0, bytes, RedisLoginTokens.FAMILY_BYTES, tail.length);
        if (kind == Kind.ACCESS) {
            bytes[RedisLoginTokens.FAMILY_BYTES] |= (byte) RedisLoginTokens.ACCESS_KIND_BIT;
        } else {
            bytes[RedisLoginTokens.FAMILY_BYTES] &= (byte) ~RedisLoginTokens.ACCESS_KIND_BIT;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 轮换提交之后、续索引核对之前家族被并发吊销：这对令牌已经无效，不交出去，索引里也不留死成员。 */
    @Test
    void 轮换提交后家族被并发吊销_这一对不交出去_索引不留死成员() {
        TokenPair first = issue();
        String famKey = familyKey(first);
        RedissonClient forwarding = mock(RedissonClient.class, delegatesTo(redis));
        RKeys realKeys = redis.getKeys();
        RKeys keys = mock(RKeys.class, delegatesTo(realKeys));
        doAnswer(invocation -> {
            realKeys.delete(famKey);
            return realKeys.countExists(famKey);
        }).when(keys).countExists(famKey);
        doReturn(keys).when(forwarding).getKeys();
        tokens = new RedisLoginTokens(forwarding, REDIS_KEYS, Clock.fixed(NOW, ZoneOffset.UTC), ACCESS, REFRESH, GRACE,
                new SecureRandom());

        assertThat(refresh(first.refreshToken())).isEmpty();
        assertThat(index(account).contains(familyId(first))).isFalse();
        // 没交出去的那一对写在轮换记录里：登记它的键，留给 tearDown 清理
        RedisLoginTokens.decodeRotation(redis.<String>getBucket(rotatedKey(first), StringCodec.INSTANCE).get(), familyId(first))
                .ifPresent(rec -> {
                    touched.add(REDIS_KEYS.loginAccessToken(familyId(first), rec.accessToken()));
                    touched.add(REDIS_KEYS.loginRefreshToken(familyId(first), rec.refreshToken()));
                });
    }

    @Test
    void 家族真实TTL到期_access还有值也拒绝() throws Exception {
        tokens = new RedisLoginTokens(redis, REDIS_KEYS, Clock.fixed(NOW, ZoneOffset.UTC), ACCESS, Duration.ofMillis(600), GRACE,
                new SecureRandom());
        TokenPair first = issue();
        awaitGone(familyKey(first), Duration.ofSeconds(4));
        assertThat(exists(accessKey(first))).isTrue();
        assertThat(tokens.validateAccess(first.accessToken())).isEmpty();
    }

    /** 时钟可前进：分数各不相同，「最旧」就是最早签的那几个。 */
    private RedisLoginTokens movingClock(AtomicReference<Instant> now) {
        Clock moving = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        return new RedisLoginTokens(redis, REDIS_KEYS, moving, ACCESS, REFRESH, GRACE, new SecureRandom());
    }

    @Test
    void 每账号活家族封顶32_吊销最久没轮换的连同其头与access_过期成员按分数清掉() {
        AtomicReference<Instant> now = new AtomicReference<>(NOW);
        tokens = movingClock(now);
        index(account).add(NOW.getEpochSecond() - 1, "dead-member");
        List<TokenPair> pairs = new ArrayList<>();
        pairs.add(issue());
        assertThat(index(account).contains("dead-member")).as("第一次签发就按分数清掉过期成员（不靠封顶）").isFalse();
        for (int i = 1; i < 34; i++) {
            now.set(now.get().plusSeconds(1));
            pairs.add(issue());
        }
        assertThat(index(account).size()).isEqualTo(RedisLoginTokens.MAX_FAMILIES_PER_ACCOUNT);
        for (int i = 0; i < pairs.size(); i++) {
            TokenPair pair = pairs.get(i);
            boolean kept = i >= 2;
            assertThat(exists(familyKey(pair))).as("第 %d 个家族", i).isEqualTo(kept);
            assertThat(exists(refreshKey(pair))).isEqualTo(kept);
            assertThat(tokens.validateAccess(pair.accessToken()).isPresent()).as("淘汰家族同时使它的 access 失效").isEqualTo(kept);
        }
    }

    @Test
    void 封顶先摘掉家族记录已不在的成员_不计入活家族() {
        AtomicReference<Instant> now = new AtomicReference<>(NOW);
        tokens = movingClock(now);
        List<TokenPair> pairs = new ArrayList<>();
        for (int i = 0; i < RedisLoginTokens.MAX_FAMILIES_PER_ACCOUNT; i++) {
            now.set(now.get().plusSeconds(1));
            pairs.add(issue());
        }
        // 家族记录不在了（外部删除 / 吊销后没摘掉索引）而分数还没过期
        redis.getKeys().delete(familyKey(pairs.get(5)));
        now.set(now.get().plusSeconds(1));
        TokenPair fresh = issue();

        assertThat(index(account).contains(familyId(pairs.get(5)))).as("死成员被摘掉").isFalse();
        assertThat(index(account).size()).isEqualTo(RedisLoginTokens.MAX_FAMILIES_PER_ACCOUNT);
        for (int i = 0; i < pairs.size(); i++) {
            if (i != 5) {
                assertThat(exists(familyKey(pairs.get(i)))).as("活家族没超额，第 %d 个不被吊销", i).isTrue();
            }
        }
        assertThat(tokens.validateAccess(fresh.accessToken())).isPresent();
    }

    @Test
    void 封顶永不吊销本次签发的家族() {
        List<TokenPair> pairs = new ArrayList<>();
        for (int i = 0; i < RedisLoginTokens.MAX_FAMILIES_PER_ACCOUNT; i++) {
            pairs.add(issue());
        }
        // 已有家族的分数都比新家族大：新家族是分数最小的那个
        for (int i = 0; i < pairs.size(); i++) {
            index(account).add(NOW.getEpochSecond() + REFRESH.toSeconds() + 100 + i, familyId(pairs.get(i)));
        }
        TokenPair fresh = issue();

        assertThat(tokens.validateAccess(fresh.accessToken())).as("本次签发的家族不在候选里").isPresent();
        assertThat(exists(familyKey(pairs.get(0)))).as("吊销的是其余家族里分数最小的").isFalse();
        for (int i = 1; i < pairs.size(); i++) {
            assertThat(exists(familyKey(pairs.get(i)))).as("第 %d 个家族", i).isTrue();
        }
    }

    /**
     * 被损坏的账号索引里混进别人的家族：封顶吊销它会被脚本拒绝（归属对不上），别人的家族原样保留；
     * 本账号照样封顶（坏候选占着一个名额，宁可多吊销一个本账号的家族）。分数取 NOW：不会被签发时的过期清理先摘掉。
     */
    @Test
    void 账号索引混进别人的家族_封顶不动别人的家族_本账号照样封顶() {
        TokenPair foreign = issue(account + "_other");
        List<TokenPair> own = new ArrayList<>();
        for (int i = 0; i < RedisLoginTokens.MAX_FAMILIES_PER_ACCOUNT; i++) {
            own.add(issue());
        }
        index(account).add(NOW.getEpochSecond(), familyId(foreign));
        TokenPair last = issue();
        own.add(last);

        assertThat(exists(familyKey(foreign))).isTrue();
        assertThat(tokens.validateAccess(foreign.accessToken())).isPresent();
        assertThat(own.stream().filter(pair -> exists(familyKey(pair))).count())
                .as("33 个本账号家族里吊销两个").isEqualTo(RedisLoginTokens.MAX_FAMILIES_PER_ACCOUNT - 1L);
        assertThat(tokens.validateAccess(last.accessToken())).isPresent();
    }

    /** n 路同时拿同一个 refresh 轮换；先登记轮换记录、再等全部跑完，断言失败也不漏清理。 */
    private List<Optional<TokenPair>> race(String refreshToken, int n) {
        RedisLoginTokens.familyOf(refreshToken).ifPresent(f -> touched.add(REDIS_KEYS.loginRefreshRotated(f, refreshToken)));
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Optional<TokenPair>>> racers = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            racers.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return tokens.refresh(refreshToken);
            }));
        }
        start.countDown();
        CompletableFuture.allOf(racers.toArray(CompletableFuture[]::new)).exceptionally(e -> null).join();
        List<Optional<TokenPair>> results = new ArrayList<>();
        for (CompletableFuture<Optional<TokenPair>> racer : racers) {
            if (!racer.isCompletedExceptionally()) {
                Optional<TokenPair> result = racer.join();
                result.ifPresent(this::track);
                results.add(result);
            }
        }
        assertThat(racers).as("并发轮换不抛存储异常").noneMatch(CompletableFuture::isCompletedExceptionally);
        return results;
    }
}
