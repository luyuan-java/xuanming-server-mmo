package com.game.login.token;

import static org.assertj.core.api.Assertions.assertThat;

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

/** 连真 Redis（{@code -Dxm.it.redis=redis://127.0.0.1:6379}，缺省跳过）：签发 / 校验 / 轮换 / 封顶，DB 13，账号带随机后缀、前后清理。 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class RedisLoginTokensIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");
    private static final Duration ACCESS = Duration.ofHours(2);
    private static final Duration REFRESH = Duration.ofHours(720);

    private RedissonClient redis;
    private RedisLoginTokens tokens;
    private final String account = "robot_it_" + UUID.randomUUID().toString().substring(0, 8);
    private final List<String> touched = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(13);
        redis = Redisson.create(config);
        tokens = new RedisLoginTokens(redis, Clock.fixed(NOW, ZoneOffset.UTC), ACCESS, REFRESH, new SecureRandom());
    }

    @AfterEach
    void tearDown() {
        RScoredSortedSet<String> set = redis.getScoredSortedSet(RedisKeys.loginAccountRefresh(account), StringCodec.INSTANCE);
        for (String token : set.readAll()) {
            touched.add(RedisKeys.loginRefreshToken(token));
        }
        touched.add(RedisKeys.loginAccountRefresh(account));
        redis.getKeys().delete(touched.toArray(String[]::new));
        redis.shutdown();
    }

    private TokenPair issue() {
        TokenPair pair = tokens.issue(account, "password", "");
        touched.add(RedisKeys.loginAccessToken(pair.accessToken()));
        touched.add(RedisKeys.loginRefreshToken(pair.refreshToken()));
        return pair;
    }

    @Test
    void 签发_43字符base64url_TTL与过期秒_值是蛇形键的JSON_账号集合记分数() {
        TokenPair pair = issue();

        assertThat(pair.accessToken()).hasSize(43).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(pair.refreshToken());
        assertThat(pair.refreshToken()).hasSize(43).matches("[A-Za-z0-9_-]{43}");
        assertThat(pair.accessExpireSeconds()).isEqualTo(NOW.getEpochSecond() + 7200);
        assertThat(pair.refreshExpireSeconds()).isEqualTo(NOW.getEpochSecond() + 720 * 3600);
        var access = redis.<String>getBucket(RedisKeys.loginAccessToken(pair.accessToken()), StringCodec.INSTANCE);
        assertThat(access.remainTimeToLive()).isBetween(ACCESS.toMillis() - 60_000, ACCESS.toMillis());
        assertThat(access.get()).contains("\"account\":\"" + account + "\"").contains("\"auth_type\":\"password\"")
                .contains("\"created_at\":" + NOW.getEpochSecond());
        assertThat(redis.getBucket(RedisKeys.loginRefreshToken(pair.refreshToken())).remainTimeToLive())
                .isBetween(REFRESH.toMillis() - 60_000, REFRESH.toMillis());
        RScoredSortedSet<String> set = redis.getScoredSortedSet(RedisKeys.loginAccountRefresh(account), StringCodec.INSTANCE);
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
    void 轮换_旧refresh一次性作废_新的一对可用_access不随之作废() {
        TokenPair first = issue();
        TokenPair second = tokens.refresh(first.refreshToken()).orElseThrow();
        touched.add(RedisKeys.loginAccessToken(second.accessToken()));

        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(tokens.refresh(first.refreshToken())).as("用过的 refresh").isEmpty();
        assertThat(tokens.validateAccess(first.accessToken())).isPresent();
        assertThat(tokens.validateAccess(second.accessToken()).orElseThrow().authType()).isEqualTo("password");
        RScoredSortedSet<String> set = redis.getScoredSortedSet(RedisKeys.loginAccountRefresh(account), StringCodec.INSTANCE);
        assertThat(set.readAll()).containsExactly(second.refreshToken());
        assertThat(tokens.refresh("")).isEmpty();
    }

    @Test
    void 并发轮换同一个refresh_恰好一个成功() throws Exception {
        TokenPair pair = issue();
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Optional<TokenPair>>> racers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            racers.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return tokens.refresh(pair.refreshToken());
            }));
        }
        start.countDown();
        long winners = 0;
        for (CompletableFuture<Optional<TokenPair>> racer : racers) {
            Optional<TokenPair> won = racer.get();
            if (won.isPresent()) {
                winners++;
                touched.add(RedisKeys.loginAccessToken(won.get().accessToken()));
            }
        }
        assertThat(winners).isEqualTo(1);
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
        tokens = new RedisLoginTokens(redis, moving, ACCESS, REFRESH, new SecureRandom());
        RScoredSortedSet<String> set = redis.getScoredSortedSet(RedisKeys.loginAccountRefresh(account), StringCodec.INSTANCE);
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
            boolean exists = redis.getBucket(RedisKeys.loginRefreshToken(pairs.get(i).refreshToken())).isExists();
            assertThat(exists).as("第 %d 个 refresh", i).isEqualTo(i >= 2);
            assertThat(set.contains(pairs.get(i).refreshToken())).isEqualTo(i >= 2);
        }
    }

    @Test
    void 值解不开或没有账号按无效处理() {
        String badJson = "b".repeat(43);
        String noAccount = "n".repeat(43);
        redis.getBucket(RedisKeys.loginAccessToken(badJson), StringCodec.INSTANCE).set("{not json");
        redis.getBucket(RedisKeys.loginAccessToken(noAccount), StringCodec.INSTANCE)
                .set("{\"auth_type\":\"password\"}");
        touched.add(RedisKeys.loginAccessToken(badJson));
        touched.add(RedisKeys.loginAccessToken(noAccount));
        assertThat(tokens.validateAccess(badJson)).isEmpty();
        assertThat(tokens.validateAccess(noAccount)).isEmpty();
    }
}
