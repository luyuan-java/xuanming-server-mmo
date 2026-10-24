package com.game.login.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.game.common.GameNamespace;
import com.game.discovery.RedisKeys;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import org.redisson.api.RMap;
import org.redisson.api.RScoredSortedSet;
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
                REDIS_KEYS.loginRefreshRotated(familyId(pair), pair.refreshToken()));
    }

    @Test
    void 签发_43字符_两枚令牌同家族_家族记录与头同TTL_索引记家族() {
        TokenPair pair = issue();
        String id = familyId(pair);

        assertThat(pair.accessToken()).hasSize(43).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(pair.refreshToken());
        assertThat(RedisLoginTokens.familyOf(pair.accessToken())).contains(id);
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
    void 校验access_refresh不能当access用() {
        TokenPair pair = issue();
        assertThat(tokens.validateAccess(pair.accessToken()))
                .contains(new TokenData(account, "password", "", NOW.getEpochSecond(), familyId(pair)));
        assertThat(tokens.validateAccess(pair.refreshToken())).isEmpty();
        assertThat(tokens.validateAccess("")).isEmpty();
        assertThat(tokens.validateAccess("nope")).isEmpty();
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

        var record = redis.getBucket(REDIS_KEYS.loginRefreshRotated(familyId(first), first.refreshToken()), StringCodec.INSTANCE);
        assertThat(record.remainTimeToLive()).isBetween(GRACE.toMillis() - 5_000, GRACE.toMillis());
        Map<String, String> before = familySnapshot(second);
        assertThat(refresh(first.refreshToken())).as("回包丢失后拿原 token 重试").contains(second);
        assertThat(refresh(first.refreshToken())).as("再重试一次也一样").contains(second);
        assertThat(familySnapshot(second)).as("重放不写").isEqualTo(before);
        assertThat(refresh(second.refreshToken())).as("重放拿到的 refresh 照常能轮换").isPresent();
    }

    @Test
    void 新refresh用过之后_窗口内重放旧refresh只拒绝不吊销() {
        TokenPair first = issue();
        TokenPair second = refresh(first.refreshToken()).orElseThrow();
        TokenPair third = refresh(second.refreshToken()).orElseThrow();

        assertThat(exists(REDIS_KEYS.loginRefreshRotated(familyId(first), first.refreshToken()))).as("仍在窗口内").isTrue();
        assertThat(refresh(first.refreshToken())).as("合法客户端用上新 refresh 后不会再拿旧的重试").isEmpty();
        assertThat(exists(familyKey(first))).as("窗口内永不吊销").isTrue();
        assertThat(tokens.validateAccess(third.accessToken())).isPresent();
        assertThat(refresh(second.refreshToken())).contains(third);
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

        // 窗口靠记录的 PX 过期实现：等它真的过期（有上限的等待），不靠本地时钟判断
        var record = redis.getBucket(REDIS_KEYS.loginRefreshRotated(familyId(first), first.refreshToken()));
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (record.isExists() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(record.isExists()).as("轮换记录按窗口过期").isFalse();

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
        assertThat(exists(REDIS_KEYS.loginRefreshRotated(familyId(first), first.refreshToken()))).as("窗口为 0 不写轮换记录").isFalse();

        assertThat(refresh(first.refreshToken())).isEmpty();
        assertThat(tokens.validateAccess(second.accessToken())).isEmpty();
        assertThat(refresh(second.refreshToken())).isEmpty();
    }

    @Test
    void 宽限为零_并发轮换同一个refresh_恰好一个成功_随后被当作重用吊销() throws Exception {
        tokens = with(Duration.ZERO, true);
        TokenPair pair = issue();
        List<Optional<TokenPair>> results = race(pair.refreshToken(), 8);
        List<TokenPair> won = results.stream().flatMap(Optional::stream).toList();
        assertThat(won).hasSize(1);
        assertThat(exists(familyKey(pair))).isFalse();
        assertThat(tokens.validateAccess(won.get(0).accessToken())).isEmpty();
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
    void 伪造家族号加任意字节_等同重放旧成员_吊销该家族() {
        TokenPair pair = issue();
        byte[] forged = new byte[32];
        new SecureRandom().nextBytes(forged);
        System.arraycopy(Base64.getUrlDecoder().decode(familyId(pair)), 0, forged, 0, 16);
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

    @Test
    void 轮换记录被PERSIST或续长_不能延长宽限窗口() {
        TokenPair first = issue();
        TokenPair second = refresh(first.refreshToken()).orElseThrow();
        var record = redis.getBucket(REDIS_KEYS.loginRefreshRotated(familyId(first), first.refreshToken()), StringCodec.INSTANCE);
        assertThat(record.clearExpire()).isTrue();
        assertThat(refresh(first.refreshToken())).isEmpty();
        assertThat(record.expire(GRACE.plusMinutes(1))).isTrue();
        assertThat(refresh(first.refreshToken())).isEmpty();
        assertThat(tokens.validateAccess(second.accessToken())).as("拒绝不吊销").isPresent();
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
        assertThat(exists(REDIS_KEYS.loginRefreshRotated(familyId(first), first.refreshToken()))).isFalse();
    }

    @Test
    void 账号索引类型损坏_签发在写任何键之前拒绝_轮换不依赖索引() {
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
    }

    @Test
    void 账号索引丢失_令牌照常可用_轮换把家族补登记回去() {
        TokenPair pair = issue();
        redis.getKeys().delete(REDIS_KEYS.loginAccountRefresh(account));
        assertThat(tokens.validateAccess(pair.accessToken())).isPresent();
        TokenPair next = refresh(pair.refreshToken()).orElseThrow();
        assertThat(index(account).getScore(familyId(pair))).isEqualTo((double) next.refreshExpireSeconds());
    }

    @Test
    void 家族真实TTL到期_access还有值也拒绝() throws Exception {
        tokens = new RedisLoginTokens(redis, REDIS_KEYS, Clock.fixed(NOW, ZoneOffset.UTC), ACCESS, Duration.ofMillis(600), GRACE,
                new SecureRandom());
        TokenPair first = issue();
        long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
        while (exists(familyKey(first)) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(exists(familyKey(first))).isFalse();
        assertThat(exists(accessKey(first))).isTrue();
        assertThat(tokens.validateAccess(first.accessToken())).isEmpty();
    }

    @Test
    void 每账号活家族封顶32_吊销最久没轮换的连同其头与access_过期成员按分数清掉() {
        // 时钟每次签发前进 1 秒：分数各不相同，「最旧」就是最早签的那几个
        AtomicReference<Instant> now = new AtomicReference<>(NOW);
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
        tokens = new RedisLoginTokens(redis, REDIS_KEYS, moving, ACCESS, REFRESH, GRACE, new SecureRandom());
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
    void 账号索引混进别人的家族_封顶不动别人的家族() {
        TokenPair foreign = issue(account + "_other");
        for (int i = 0; i < RedisLoginTokens.MAX_FAMILIES_PER_ACCOUNT; i++) {
            issue();
        }
        index(account).add(0, familyId(foreign));
        issue();
        assertThat(exists(familyKey(foreign))).isTrue();
        assertThat(tokens.validateAccess(foreign.accessToken())).isPresent();
    }

    /** n 路同时拿同一个 refresh 轮换。 */
    private List<Optional<TokenPair>> race(String refreshToken, int n) throws Exception {
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
        List<Optional<TokenPair>> results = new ArrayList<>();
        for (CompletableFuture<Optional<TokenPair>> racer : racers) {
            Optional<TokenPair> result = racer.get();
            result.ifPresent(this::track);
            results.add(result);
        }
        RedisLoginTokens.familyOf(refreshToken).ifPresent(f -> touched.add(REDIS_KEYS.loginRefreshRotated(f, refreshToken)));
        return results;
    }
}
