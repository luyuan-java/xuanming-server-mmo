package com.game.login.token;

import static org.assertj.core.api.Assertions.assertThat;

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
        pair.ifPresent(p -> touched.add(REDIS_KEYS.loginAccessToken(p.accessToken())));
        return pair;
    }

    @AfterEach
    void tearDown() {
        RScoredSortedSet<String> set = redis.getScoredSortedSet(REDIS_KEYS.loginAccountRefresh(account), StringCodec.INSTANCE);
        for (String token : set.readAll()) {
            touched.add(REDIS_KEYS.loginRefreshToken(token));
        }
        touched.add(REDIS_KEYS.loginAccountRefresh(account));
        redis.getKeys().delete(touched.toArray(String[]::new));
        redis.shutdown();
    }

    private TokenPair issue() {
        TokenPair pair = tokens.issue(account, "password", "");
        touched.add(REDIS_KEYS.loginAccessToken(pair.accessToken()));
        touched.add(REDIS_KEYS.loginRefreshToken(pair.refreshToken()));
        return pair;
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
                .contains(new TokenData(account, "password", "", NOW.getEpochSecond()));
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
            result.ifPresent(p -> touched.add(REDIS_KEYS.loginAccessToken(p.accessToken())));
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
}
