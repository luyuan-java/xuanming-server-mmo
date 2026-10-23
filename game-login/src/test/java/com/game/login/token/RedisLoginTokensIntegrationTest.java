package com.game.login.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.GameNamespace;
import com.game.discovery.RedisKeys;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.redisson.api.RScoredSortedSet;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.AdditionalAnswers.delegatesTo;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

/**
 * 连真 Redis（{@code -Dgame.it.redis=redis://127.0.0.1:6379}，缺省跳过）：签发 / 校验 / 轮换（含重放宽限窗口）/ 封顶，DB 13，
 * 账号带随机后缀、前后清理。
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
        tokens = withGrace(GRACE);
    }

    private RedisLoginTokens withGrace(Duration grace) {
        return new RedisLoginTokens(redis, REDIS_KEYS, Clock.fixed(NOW, ZoneOffset.UTC), ACCESS, REFRESH, grace, new SecureRandom());
    }

    /** 轮换并登记要清理的键（新 access、旧 refresh 的轮换记录；新 refresh 在账号集合里，由 tearDown 清）。 */
    private Optional<TokenPair> refresh(String refreshToken) {
        Optional<TokenPair> pair = tokens.refresh(refreshToken);
        touched.add(REDIS_KEYS.loginRefreshRotated(refreshToken));
        pair.ifPresent(this::track);
        return pair;
    }

    @AfterEach
    void tearDown() {
        // 每个成功返回的pair在产生时登记：即使本例故意把账号ZSET损坏为另一类型，也只清自己的键。
        touched.add(REDIS_KEYS.loginAccountRefresh(account));
        try {
            redis.getKeys().delete(touched.toArray(String[]::new));
        } finally {
            redis.shutdown();
        }
    }

    private TokenPair issue() {
        TokenPair pair = tokens.issue(account, "password", "");
        track(pair);
        return pair;
    }

    private void track(TokenPair pair) {
        touched.add(REDIS_KEYS.loginAccessToken(pair.accessToken()));
        touched.add(REDIS_KEYS.loginRefreshToken(pair.refreshToken()));
        var data = RedisLoginTokens.decode(redis.<String>getBucket(REDIS_KEYS.loginRefreshToken(pair.refreshToken()),
                StringCodec.INSTANCE).get());
        data.filter(d -> !d.familyId().isEmpty()).ifPresent(d -> touched.add(REDIS_KEYS.loginRefreshFamily(d.familyId())));
    }

    private String familyId(TokenPair pair) {
        return RedisLoginTokens.decode(redis.<String>getBucket(REDIS_KEYS.loginAccessToken(pair.accessToken()),
                StringCodec.INSTANCE).get()).orElseThrow().familyId();
    }

    private org.redisson.api.RMap<String, String> family(TokenPair pair) {
        return redis.getMap(REDIS_KEYS.loginRefreshFamily(familyId(pair)), StringCodec.INSTANCE);
    }

    @Test
    void 签发_43字符base64url_TTL与过期秒_值是蛇形键的JSON_账号集合记分数() {
        TokenPair pair = issue();

        assertThat(pair.accessToken()).hasSize(43).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(pair.refreshToken());
        assertThat(pair.refreshToken()).hasSize(43).matches("[A-Za-z0-9_-]{43}");
        assertThat(pair.accessExpireSeconds()).isEqualTo(NOW.getEpochSecond() + 7200);
        assertThat(pair.refreshExpireSeconds()).isEqualTo(NOW.getEpochSecond() + 720 * 3600);
        var access = redis.<String>getBucket(REDIS_KEYS.loginAccessToken(pair.accessToken()), StringCodec.INSTANCE);
        assertThat(access.remainTimeToLive()).isBetween(ACCESS.toMillis() - 60_000, ACCESS.toMillis());
        assertThat(access.get()).contains("\"account\":\"" + account + "\"").contains("\"auth_type\":\"password\"")
                .contains("\"created_at\":" + NOW.getEpochSecond());
        assertThat(redis.getBucket(REDIS_KEYS.loginRefreshToken(pair.refreshToken())).remainTimeToLive())
                .isBetween(REFRESH.toMillis() - 60_000, REFRESH.toMillis());
        RScoredSortedSet<String> set = redis.getScoredSortedSet(REDIS_KEYS.loginAccountRefresh(account), StringCodec.INSTANCE);
        assertThat(set.getScore(pair.refreshToken())).isEqualTo((double) pair.refreshExpireSeconds());
        assertThat(set.remainTimeToLive()).isPositive();
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
    void 宽限为零_轮换_旧refresh一次性作废_新的一对可用_access不随之作废() {
        tokens = withGrace(Duration.ZERO);
        TokenPair first = issue();
        TokenPair second = refresh(first.refreshToken()).orElseThrow();

        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(refresh(first.refreshToken())).as("用过的 refresh").isEmpty();
        assertThat(redis.getBucket(REDIS_KEYS.loginRefreshRotated(first.refreshToken())).isExists())
                .as("窗口为 0 不写轮换记录").isFalse();
        assertThat(tokens.validateAccess(first.accessToken())).isPresent();
        assertThat(tokens.validateAccess(second.accessToken()).orElseThrow().authType()).isEqualTo("password");
        RScoredSortedSet<String> set = redis.getScoredSortedSet(REDIS_KEYS.loginAccountRefresh(account), StringCodec.INSTANCE);
        assertThat(set.readAll()).containsExactly(second.refreshToken());
        assertThat(tokens.refresh("")).isEmpty();
    }

    @Test
    void 截止已过_不发轮换脚本_旧refresh仍可用_之后照常轮换() {
        TokenPair first = issue();

        assertThatThrownBy(() -> tokens.refresh(first.refreshToken(), () -> true))
                .isInstanceOf(LoginTokens.RotationAbandoned.class);

        assertThat(redis.getBucket(REDIS_KEYS.loginRefreshToken(first.refreshToken())).isExists()).as("旧 refresh 没被作废")
                .isTrue();
        assertThat(redis.getBucket(REDIS_KEYS.loginRefreshRotated(first.refreshToken())).isExists()).as("没有轮换记录")
                .isFalse();
        RScoredSortedSet<String> set = redis.getScoredSortedSet(REDIS_KEYS.loginAccountRefresh(account), StringCodec.INSTANCE);
        assertThat(set.readAll()).as("账号集合里没有新 refresh").containsExactly(first.refreshToken());

        // 客户端拿原 token 重试（截止之内）：照常轮换。
        assertThat(refresh(first.refreshToken())).isPresent();
    }

    @Test
    void 宽限为零_并发轮换同一个refresh_恰好一个成功() throws Exception {
        tokens = withGrace(Duration.ZERO);
        TokenPair pair = issue();
        List<Optional<TokenPair>> results = race(pair.refreshToken(), 8);
        assertThat(results.stream().filter(Optional::isPresent).count()).isEqualTo(1);
    }

    @Test
    void 宽限窗口内重放旧refresh_返回同一对_不再签发_记录TTL即窗口() {
        TokenPair first = issue();
        TokenPair second = refresh(first.refreshToken()).orElseThrow();

        var record = redis.getBucket(REDIS_KEYS.loginRefreshRotated(first.refreshToken()), StringCodec.INSTANCE);
        assertThat(record.remainTimeToLive()).isBetween(GRACE.toMillis() - 5_000, GRACE.toMillis());
        assertThat(refresh(first.refreshToken())).as("回包丢失后拿原 token 重试").contains(second);
        assertThat(refresh(first.refreshToken())).as("再重试一次也一样").contains(second);

        RScoredSortedSet<String> set = redis.getScoredSortedSet(REDIS_KEYS.loginAccountRefresh(account), StringCodec.INSTANCE);
        assertThat(set.readAll()).as("没有再签发，旧 refresh 已摘掉").containsExactly(second.refreshToken());
        assertThat(redis.getBucket(REDIS_KEYS.loginRefreshToken(first.refreshToken())).isExists()).isFalse();
        assertThat(tokens.validateAccess(second.accessToken())).isPresent();
        assertThat(refresh(second.refreshToken())).as("重放拿到的 refresh 照常能轮换").isPresent();
    }

    @Test
    void 宽限窗口到期后重放旧refresh无效_新refresh不受影响() throws Exception {
        Duration grace = Duration.ofMillis(300);
        tokens = withGrace(grace);
        TokenPair first = issue();
        TokenPair second = refresh(first.refreshToken()).orElseThrow();

        // 窗口靠记录的 PX 过期实现：等它真的过期（有上限的等待），不靠本地时钟判断
        var record = redis.getBucket(REDIS_KEYS.loginRefreshRotated(first.refreshToken()), StringCodec.INSTANCE);
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (record.isExists() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(record.isExists()).as("轮换记录按窗口过期").isFalse();
        assertThat(refresh(first.refreshToken())).isEmpty();
        assertThat(refresh(second.refreshToken())).as("被拒的重放不影响新 refresh").isPresent();
    }

    @Test
    void 新refresh用过之后_窗口内重放旧refresh也无效_新refresh自己的窗口照常() {
        TokenPair first = issue();
        TokenPair second = refresh(first.refreshToken()).orElseThrow();
        TokenPair third = refresh(second.refreshToken()).orElseThrow();

        assertThat(redis.getBucket(REDIS_KEYS.loginRefreshRotated(first.refreshToken())).isExists()).as("仍在窗口内").isTrue();
        assertThat(refresh(first.refreshToken())).as("合法客户端用上新 refresh 后不会再拿旧的重试").isEmpty();
        assertThat(refresh(second.refreshToken())).contains(third);
    }

    @Test
    void 宽限窗口内并发轮换同一个refresh_全部拿到同一对_只签发一次() throws Exception {
        TokenPair pair = issue();
        List<Optional<TokenPair>> results = race(pair.refreshToken(), 8);

        assertThat(results).allSatisfy(r -> assertThat(r).isPresent());
        TokenPair won = results.get(0).orElseThrow();
        assertThat(results).allSatisfy(r -> assertThat(r).contains(won));
        RScoredSortedSet<String> set = redis.getScoredSortedSet(REDIS_KEYS.loginAccountRefresh(account), StringCodec.INSTANCE);
        assertThat(set.readAll()).containsExactly(won.refreshToken());
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
        touched.add(REDIS_KEYS.loginRefreshRotated(refreshToken));
        return results;
    }

    @Test
    void 每账号活跃refresh封顶32_淘汰最旧的连同令牌键_过期成员按分数清掉() {
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
        RScoredSortedSet<String> set = redis.getScoredSortedSet(REDIS_KEYS.loginAccountRefresh(account), StringCodec.INSTANCE);
        set.add(NOW.getEpochSecond() - 1, "dead-member");
        List<TokenPair> pairs = new ArrayList<>();
        pairs.add(issue());
        assertThat(set.contains("dead-member")).as("第一次签发就按分数清掉过期成员（不靠封顶）").isFalse();
        for (int i = 1; i < 34; i++) {
            now.set(now.get().plusSeconds(1));
            pairs.add(issue());
        }
        assertThat(set.size()).isEqualTo(RedisLoginTokens.MAX_REFRESH_PER_ACCOUNT);
        for (int i = 0; i < pairs.size(); i++) {
            boolean exists = redis.getBucket(REDIS_KEYS.loginRefreshToken(pairs.get(i).refreshToken())).isExists();
            assertThat(exists).as("第 %d 个 refresh", i).isEqualTo(i >= 2);
            assertThat(set.contains(pairs.get(i).refreshToken())).isEqualTo(i >= 2);
            assertThat(tokens.validateAccess(pairs.get(i).accessToken()).isPresent()).as("淘汰家族同时使旧access失效").isEqualTo(i >= 2);
        }
    }

    @Test
    void 值解不开或没有账号按无效处理() {
        String badJson = "b".repeat(43);
        String noAccount = "n".repeat(43);
        redis.getBucket(REDIS_KEYS.loginAccessToken(badJson), StringCodec.INSTANCE).set("{not json");
        redis.getBucket(REDIS_KEYS.loginAccessToken(noAccount), StringCodec.INSTANCE)
                .set("{\"auth_type\":\"password\"}");
        touched.add(REDIS_KEYS.loginAccessToken(badJson));
        touched.add(REDIS_KEYS.loginAccessToken(noAccount));
        assertThat(tokens.validateAccess(badJson)).isEmpty();
        assertThat(tokens.validateAccess(noAccount)).isEmpty();
    }
    @Test
    void 新认证独立家族_账号head与TTL同refresh_正常轮换保家族() {
        TokenPair first = issue();
        String id = familyId(first);
        assertThat(id).hasSize(22).matches("[A-Za-z0-9_-]{22}");
        assertThat(Base64.getUrlDecoder().decode(id)).hasSize(16);
        Map<String, String> before = family(first).readAllMap();
        assertThat(before).containsEntry("account", account).containsEntry("head", first.refreshToken())
                .containsEntry("created_at", String.valueOf(NOW.getEpochSecond())).containsEntry("prev", "");
        assertThat(family(first).remainTimeToLive()).isBetween(REFRESH.toMillis() - 5_000, REFRESH.toMillis());
        TokenPair second = refresh(first.refreshToken()).orElseThrow();
        assertThat(familyId(second)).isEqualTo(id);
        assertThat(family(second).readAllMap()).containsEntry("created_at", before.get("created_at"))
                .containsEntry("head", second.refreshToken()).containsEntry("prev", first.refreshToken());
        assertThat(tokens.validateAccess(first.accessToken())).as("正常轮换不撤销仍有效的旧access").isPresent();
        assertThat(tokens.validateAccess(second.accessToken())).isPresent();
    }

    @Test
    void 撤销家族后access与refresh及宽限重放均拒绝_不重建家族() {
        TokenPair first = issue();
        TokenPair second = refresh(first.refreshToken()).orElseThrow();
        String key = REDIS_KEYS.loginRefreshFamily(familyId(second));
        redis.getKeys().delete(key);
        assertThat(tokens.validateAccess(first.accessToken())).isEmpty();
        assertThat(tokens.validateAccess(second.accessToken())).isEmpty();
        assertThat(refresh(second.refreshToken())).isEmpty();
        assertThat(refresh(first.refreshToken())).as("宽限记录不是恢复被撤销家族的凭据").isEmpty();
        assertThat(redis.getKeys().countExists(key)).isZero();
    }

    @Test
    void 真实旧JSON缺字段_在一次性提交点收编_显式空家族拒绝() {
        String old = "l".repeat(43), oldAccess = "a".repeat(43);
        String value = "{\"account\":\"" + account + "\",\"auth_type\":\"password\",\"created_at\":7}";
        String refreshKey = REDIS_KEYS.loginRefreshToken(old), accessKey = REDIS_KEYS.loginAccessToken(oldAccess);
        touched.add(refreshKey); touched.add(accessKey); touched.add(REDIS_KEYS.loginRefreshRotated(old));
        redis.getBucket(refreshKey, StringCodec.INSTANCE).set(value, REFRESH);
        redis.getBucket(accessKey, StringCodec.INSTANCE).set(value, ACCESS);
        RScoredSortedSet<String> index = redis.getScoredSortedSet(REDIS_KEYS.loginAccountRefresh(account), StringCodec.INSTANCE);
        index.add(NOW.getEpochSecond() + REFRESH.toSeconds(), old);
        assertThat(tokens.validateAccess(oldAccess).orElseThrow().familyId()).isEmpty();
        assertThatThrownBy(() -> tokens.refresh(old, () -> true)).isInstanceOf(LoginTokens.RotationAbandoned.class);
        assertThat(redis.getBucket(refreshKey).isExists()).isTrue();
        TokenPair adopted = refresh(old).orElseThrow();
        assertThat(familyId(adopted)).hasSize(22);
        assertThat(family(adopted).readAllMap()).containsEntry("account", account).containsEntry("head", adopted.refreshToken());
        assertThat(refresh(old)).contains(adopted);
        assertThat(index.readAll()).containsExactly(adopted.refreshToken());
        redis.getBucket(accessKey, StringCodec.INSTANCE).set(value.substring(0, value.length() - 1) + ",\"family_id\":\"\"}");
        assertThat(tokens.validateAccess(oldAccess)).as("显式空值不是旧数据").isEmpty();
    }

    @Test
    void 家族账号或head损坏_拒绝轮换且所有原件不变() {
        TokenPair first = issue();
        String key = REDIS_KEYS.loginRefreshToken(first.refreshToken());
        Object original = redis.getBucket(key, StringCodec.INSTANCE).get();
        family(first).put("account", account + "_other");
        assertThat(refresh(first.refreshToken())).isEmpty();
        assertThat(tokens.validateAccess(first.accessToken())).isEmpty();
        assertThat(redis.getBucket(key, StringCodec.INSTANCE).get()).isEqualTo(original);
        assertThat(redis.getBucket(REDIS_KEYS.loginRefreshRotated(first.refreshToken())).isExists()).isFalse();
        family(first).put("account", account); family(first).put("head", "x".repeat(43));
        assertThat(refresh(first.refreshToken())).isEmpty();
        assertThat(tokens.validateAccess(first.accessToken())).isEmpty();
        assertThat(redis.getBucket(key, StringCodec.INSTANCE).get()).isEqualTo(original);
    }

    @Test
    @SuppressWarnings("unchecked")
    void GET后原件已变化_轮换CAS拒绝不覆盖新值() {
        TokenPair first = issue();
        String key = REDIS_KEYS.loginRefreshToken(first.refreshToken());
        var originalBucket = redis.<String>getBucket(key, StringCodec.INSTANCE);
        String replacement = RedisLoginTokens.encode(new TokenData(account, "changed", "", NOW.getEpochSecond(), familyId(first)));
        Map<String, String> beforeFamily = family(first).readAllMap();
        RedissonClient forwarding = mock(RedissonClient.class, delegatesTo(redis));
        org.redisson.api.RBucket<String> interleaved = mock(org.redisson.api.RBucket.class, delegatesTo(originalBucket));
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
        assertThat(redis.<String>getScoredSortedSet(REDIS_KEYS.loginAccountRefresh(account), StringCodec.INSTANCE).readAll())
                .containsExactly(first.refreshToken());
        assertThat(redis.getBucket(REDIS_KEYS.loginRefreshRotated(first.refreshToken())).isExists()).isFalse();
    }

    @Test
    void 轮换前ZSET或家族或重放键类型错误_没有半写() {
        TokenPair first = issue();
        String tokenKey = REDIS_KEYS.loginRefreshToken(first.refreshToken());
        String indexKey = REDIS_KEYS.loginAccountRefresh(account), familyKey = REDIS_KEYS.loginRefreshFamily(familyId(first));
        String replayKey = REDIS_KEYS.loginRefreshRotated(first.refreshToken());
        touched.add(replayKey);
        Object raw = redis.getBucket(tokenKey, StringCodec.INSTANCE).get();
        Map<String, String> originalFamily = family(first).readAllMap();
        // 故障夹具恢复原件时也必须恢复原剩余寿命；用单调deadline扣掉故障耗时，不能恢复成PERSIST或延长期限。
        long familyDeadline = System.nanoTime() + Duration.ofMillis(family(first).remainTimeToLive()).toNanos();
        long indexDeadline = System.nanoTime() + Duration.ofMillis(redis.getScoredSortedSet(indexKey, StringCodec.INSTANCE)
                .remainTimeToLive()).toNanos();
        for (String corrupted : List.of(indexKey, familyKey, replayKey)) {
            redis.getKeys().delete(corrupted);
            if (corrupted.equals(replayKey)) redis.<String, String>getMap(corrupted, StringCodec.INSTANCE).put("wrong", "type");
            else redis.getBucket(corrupted, StringCodec.INSTANCE).set("wrong-type");
            assertThatThrownBy(() -> tokens.refresh(first.refreshToken())).isInstanceOf(TokenStoreException.class);
            assertThat(redis.getBucket(tokenKey, StringCodec.INSTANCE).get()).as("旧refresh未删").isEqualTo(raw);
            if (!corrupted.equals(indexKey)) {
                assertThat(redis.<String>getScoredSortedSet(indexKey, StringCodec.INSTANCE).readAll()).containsExactly(first.refreshToken());
            }
            if (!corrupted.equals(familyKey)) assertThat(family(first).readAllMap()).isEqualTo(originalFamily);
            redis.getKeys().delete(corrupted);
            if (corrupted.equals(indexKey)) {
                var index = redis.<String>getScoredSortedSet(indexKey, StringCodec.INSTANCE);
                index.add(first.refreshExpireSeconds(), first.refreshToken());
                assertThat(index.expire(Duration.ofNanos(indexDeadline - System.nanoTime()))).isTrue();
            }
            if (corrupted.equals(familyKey)) {
                var restoredFamily = redis.<String, String>getMap(familyKey, StringCodec.INSTANCE);
                restoredFamily.putAll(originalFamily);
                assertThat(restoredFamily.expire(Duration.ofNanos(familyDeadline - System.nanoTime()))).isTrue();
            }
        }
        assertThat(refresh(first.refreshToken())).as("恢复自己的夹具后原refresh仍能正常提交").isPresent();
    }

    @Test
    void 签发前账号索引类型错误_不产生任何新令牌或家族() {
        String indexKey = REDIS_KEYS.loginAccountRefresh(account);
        redis.getBucket(indexKey, StringCodec.INSTANCE).set("wrong-type");
        List<String> generated = new ArrayList<>();
        SecureRandom deterministic = new SecureRandom() {
            private int next;
            @Override public void nextBytes(byte[] bytes) {
                Arrays.fill(bytes, (byte) ++next);
                generated.add(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
            }
        };
        tokens = new RedisLoginTokens(redis, REDIS_KEYS, Clock.fixed(NOW, ZoneOffset.UTC), ACCESS, REFRESH, GRACE, deterministic);
        assertThatThrownBy(() -> tokens.issue(account, "password", "")).isInstanceOf(TokenStoreException.class);
        assertThat(generated).hasSize(3);
        List<String> keys = List.of(REDIS_KEYS.loginRefreshFamily(generated.get(0)),
                REDIS_KEYS.loginAccessToken(generated.get(1)), REDIS_KEYS.loginRefreshToken(generated.get(2)));
        touched.addAll(keys);
        assertThat(redis.getKeys().countExists(keys.toArray(String[]::new))).isZero();
        assertThat(redis.getBucket(indexKey, StringCodec.INSTANCE).get()).isEqualTo("wrong-type");
    }

    @Test
    void 家族真实TTL到期_access还有值也拒绝() throws Exception {
        Duration shortRefresh = Duration.ofMillis(600);
        tokens = new RedisLoginTokens(redis, REDIS_KEYS, Clock.fixed(NOW, ZoneOffset.UTC), ACCESS, shortRefresh, GRACE, new SecureRandom());
        TokenPair first = issue();
        String familyKey = REDIS_KEYS.loginRefreshFamily(familyId(first));
        long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
        while (redis.getKeys().countExists(familyKey) != 0 && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(redis.getKeys().countExists(familyKey)).isZero();
        assertThat(redis.getBucket(REDIS_KEYS.loginAccessToken(first.accessToken())).isExists()).isTrue();
        assertThat(tokens.validateAccess(first.accessToken())).isEmpty();
    }

    @Test
    void 淘汰集合被跨账号污染_整批预检失败不删除前面的合法候选() {
        TokenPair first = issue();
        for (int i = 1; i < 32; i++) issue();
        String otherAccount = account + "_other";
        TokenPair foreign = tokens.issue(otherAccount, "password", ""); track(foreign);
        touched.add(REDIS_KEYS.loginAccountRefresh(otherAccount));
        RScoredSortedSet<String> index = redis.getScoredSortedSet(REDIS_KEYS.loginAccountRefresh(account), StringCodec.INSTANCE);
        index.add(first.refreshExpireSeconds() - 100, first.refreshToken());
        index.add(first.refreshExpireSeconds() - 99, foreign.refreshToken());
        issue();
        assertThat(index.size()).as("损坏集合不做部分淘汰；原封顶失败合同保持尽力而为").isEqualTo(34);
        assertThat(redis.getBucket(REDIS_KEYS.loginRefreshToken(first.refreshToken())).isExists()).isTrue();
        assertThat(tokens.validateAccess(first.accessToken())).isPresent();
        assertThat(tokens.validateAccess(foreign.accessToken())).isPresent();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 淘汰读到旧候选后并发轮换_不撤销新head或旧access() {
        TokenPair first = issue();
        for (int i = 1; i < 32; i++) issue();
        String indexKey = REDIS_KEYS.loginAccountRefresh(account);
        RScoredSortedSet<String> original = redis.getScoredSortedSet(indexKey, StringCodec.INSTANCE);
        original.add(first.refreshExpireSeconds() - 100, first.refreshToken());
        RedissonClient forwarding = mock(RedissonClient.class, delegatesTo(redis));
        RScoredSortedSet<String> interleaved = mock(RScoredSortedSet.class, delegatesTo(original));
        doReturn(interleaved).when(forwarding).getScoredSortedSet(indexKey, StringCodec.INSTANCE);
        AtomicBoolean once = new AtomicBoolean();
        AtomicReference<TokenPair> rotated = new AtomicReference<>();
        doAnswer(invocation -> {
            var snapshot = original.valueRange(invocation.<Integer>getArgument(0), invocation.<Integer>getArgument(1));
            if (once.compareAndSet(false, true)) rotated.set(refresh(first.refreshToken()).orElseThrow());
            return snapshot;
        }).when(interleaved).valueRange(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        tokens = new RedisLoginTokens(forwarding, REDIS_KEYS, Clock.fixed(NOW, ZoneOffset.UTC), ACCESS, REFRESH, GRACE, new SecureRandom());
        issue();
        assertThat(once).isTrue();
        TokenPair current = rotated.get();
        assertThat(family(first).readAllMap()).containsEntry("head", current.refreshToken());
        assertThat(tokens.validateAccess(first.accessToken())).isPresent();
        assertThat(tokens.validateAccess(current.accessToken())).isPresent();
        assertThat(original.contains(current.refreshToken())).isTrue();
        assertThat(original.size()).isEqualTo(32);
    }
    @Test
    void PERSIST损坏access或家族或refresh或重放TTL_拒绝且不产生部分轮换() {
        TokenPair first = issue();
        String familyKey = REDIS_KEYS.loginRefreshFamily(familyId(first));
        String refreshKey = REDIS_KEYS.loginRefreshToken(first.refreshToken());
        Object raw = redis.getBucket(refreshKey, StringCodec.INSTANCE).get();
        Map<String, String> beforeFamily = family(first).readAllMap();
        var access = redis.getBucket(REDIS_KEYS.loginAccessToken(first.accessToken()), StringCodec.INSTANCE);
        long accessTtl = access.remainTimeToLive();
        Object accessRaw = access.get();
        assertThat(access.clearExpire()).isTrue();
        assertThat(tokens.validateAccess(first.accessToken())).as("PERSIST不能把2h access延长到家族期限").isEmpty();
        assertThat(access.get()).isEqualTo(accessRaw);
        assertThat(redis.getBucket(refreshKey, StringCodec.INSTANCE).get()).isEqualTo(raw);
        assertThat(family(first).readAllMap()).isEqualTo(beforeFamily);
        assertThat(redis.getBucket(REDIS_KEYS.loginRefreshRotated(first.refreshToken())).isExists()).isFalse();
        assertThat(access.expire(Duration.ofMillis(accessTtl))).isTrue();
        for (String key : List.of(familyKey, refreshKey)) {
            var expirable = redis.getBucket(key, StringCodec.INSTANCE);
            long ttl = expirable.remainTimeToLive();
            assertThat(ttl).isPositive();
            assertThat(expirable.clearExpire()).isTrue();
            assertThat(tokens.validateAccess(first.accessToken())).as("无期限原件不是有效家族").isEmpty();
            assertThat(refresh(first.refreshToken())).isEmpty();
            assertThat(redis.getBucket(refreshKey, StringCodec.INSTANCE).get()).isEqualTo(raw);
            assertThat(family(first).readAllMap()).isEqualTo(beforeFamily);
            assertThat(redis.getBucket(REDIS_KEYS.loginRefreshRotated(first.refreshToken())).isExists()).isFalse();
            assertThat(expirable.expire(Duration.ofMillis(ttl))).isTrue();
        }
        TokenPair second = refresh(first.refreshToken()).orElseThrow();
        var replay = redis.getBucket(REDIS_KEYS.loginRefreshRotated(first.refreshToken()), StringCodec.INSTANCE);
        assertThat(replay.clearExpire()).isTrue();
        assertThat(refresh(first.refreshToken())).as("无限重放记录不能延长原宽限窗口").isEmpty();
        assertThat(tokens.validateAccess(second.accessToken())).isPresent();
    }
    @Test
    @SuppressWarnings("unchecked")
    void 校验access遇正常轮换推进head_同家族旧access不能误拒() {
        TokenPair first = issue();
        String key = REDIS_KEYS.loginRefreshFamily(familyId(first));
        var actualFamily = redis.<String, String>getMap(key, StringCodec.INSTANCE);
        RedissonClient forwarding = mock(RedissonClient.class, delegatesTo(redis));
        org.redisson.api.RMap<String, String> interleaved = mock(org.redisson.api.RMap.class, delegatesTo(actualFamily));
        AtomicBoolean once = new AtomicBoolean();
        AtomicReference<TokenPair> rotated = new AtomicReference<>();
        Runnable normalRotation = () -> {
            if (once.compareAndSet(false, true)) rotated.set(refresh(first.refreshToken()).orElseThrow());
        };
        // 原多次读路径：在它已取得family快照后，让另一路正常轮换提交。
        doAnswer(invocation -> {
            var snapshot = actualFamily.getAll(java.util.Set.of("account", "head"));
            normalRotation.run();
            return snapshot;
        }).when(interleaved).getAll(java.util.Set.of("account", "head"));
        doReturn(interleaved).when(forwarding).getMap(key, StringCodec.INSTANCE);
        // 原子读路径：同一次正常轮换在只读脚本执行前提交；脚本必须看到完整的新head。
        var actualScript = redis.getScript(StringCodec.INSTANCE);
        var script = (org.redisson.api.RScript) java.lang.reflect.Proxy.newProxyInstance(
                org.redisson.api.RScript.class.getClassLoader(), new Class<?>[] {org.redisson.api.RScript.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("eval") && args != null && args.length > 0
                            && args[0] == org.redisson.api.RScript.Mode.READ_ONLY) normalRotation.run();
                    try { return method.invoke(actualScript, args); }
                    catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                });
        doReturn(script).when(forwarding).getScript(StringCodec.INSTANCE);
        tokens = new RedisLoginTokens(forwarding, REDIS_KEYS, Clock.fixed(NOW, ZoneOffset.UTC), ACCESS, REFRESH, GRACE, new SecureRandom());
        assertThat(tokens.validateAccess(first.accessToken())).as("正常轮换期间同family access从未失效").isPresent();
        assertThat(once).as("确实发生一次正式Lua轮换，不能未触发就称并发验证").isTrue();
        assertThat(rotated.get()).isNotNull();
        assertThat(actualFamily.readAllMap()).containsEntry("head", rotated.get().refreshToken());
        assertThat(tokens.validateAccess(rotated.get().accessToken())).isPresent();
    }
}
