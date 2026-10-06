package com.game.match.challenge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline;
import com.game.discovery.RedisKeys;
import com.game.match.challenge.ChallengeStore.ChallengeRecord;
import com.game.match.challenge.ChallengeStore.ConsumeResult;
import com.game.match.challenge.ChallengeStore.InviteResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

/**
 * 切磋存储连真 Redis（缺省跳过：{@code -Dxm.it.redis=redis://127.0.0.1:6379}；match-spec §15.3）：三段 Lua 跑 {@link ChallengeStoreContract}
 * 整套（含「并发的两条应答只有一条拿到记录」「同一个 nonce 重放返回同一结果」），另钉 Redis 上才看得见的东西——键的形状与类型、三类键的 TTL、
 * 过期时刻取的是 Redis 的 {@code TIME}、损坏的记录怎么读、Redis 不可用时在预算内以依赖异常失败。
 *
 * <p>用 DB 13、随机的 challenge_id / 玩家号，只删自己写的键（多人共用一台 Redis）。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class ChallengeStoreIntegrationTest extends ChallengeStoreContract {

    private static RedissonClient redis;

    private final List<Long> challengeIds = new ArrayList<>();
    private final List<Long> playerIds = new ArrayList<>();
    private RedissonChallengeStore store;

    @BeforeAll
    static void connect() {
        redis = Redisson.create(config());
    }

    @AfterAll
    static void close() {
        if (redis != null) {
            redis.shutdown();
        }
    }

    private static Config config() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(13).setConnectionMinimumIdleSize(1).setConnectionPoolSize(4);
        return config;
    }

    /** 只删本用例发过的号对应的键（含契约测试里把最高位置 1、再减 1 的那几个变体）。 */
    @AfterEach
    void cleanup() {
        List<String> keys = new ArrayList<>();
        for (long id : challengeIds) {
            for (long variant : new long[] {id, id | Long.MIN_VALUE, (id | Long.MIN_VALUE) - 1}) {
                keys.add(RedisKeys.matchChallenge(variant));
                keys.add(RedisKeys.matchChallengeDone(variant));
            }
        }
        for (long id : playerIds) {
            keys.add(RedisKeys.matchChallengeTarget(id));
            keys.add(RedisKeys.matchChallengeTarget(id | Long.MIN_VALUE));
        }
        if (!keys.isEmpty()) {
            redis.getKeys().delete(keys.toArray(String[]::new));
        }
    }

    @Override
    ChallengeStore store() {
        if (store == null) {
            store = new RedissonChallengeStore(redis);
        }
        return store;
    }

    @Override
    long newChallengeId() {
        long id = ThreadLocalRandom.current().nextLong(1L << 40, 1L << 62);
        challengeIds.add(id);
        return id;
    }

    @Override
    long newPlayerId() {
        long id = ThreadLocalRandom.current().nextLong(1L << 40, 1L << 62);
        playerIds.add(id);
        return id;
    }

    @Override
    long storeNowMs() {
        List<Object> time = redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_ONLY, "return redis.call('TIME')", RScript.ReturnType.MULTI,
                List.of());
        return Long.parseLong(time.get(0).toString()) * 1000 + Long.parseLong(time.get(1).toString()) / 1000;
    }

    @Override
    void elapse(long ms) throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(ms);
    }

    private static Deadline d() {
        return Deadline.after(5_000);
    }

    private static long pttl(String key) {
        return redis.getKeys().remainTimeToLive(key);
    }

    @Test
    void 键的形状_记录是四个字段的HASH_占坑是值为邀请号的STRING_两者同一个TTL() {
        long id = newChallengeId();
        long challenger = newPlayerId();
        long target = newPlayerId();

        InviteResult.Created created = (InviteResult.Created) store().invite(id, challenger, target, 12, 60_000, d());

        Map<String, String> record = redis.<String, String>getMap(RedisKeys.matchChallenge(id), StringCodec.INSTANCE).readAllMap();
        assertThat(record).containsOnly(
                Map.entry("challenger", Long.toUnsignedString(challenger)),
                Map.entry("target", Long.toUnsignedString(target)),
                Map.entry("config", "12"),
                Map.entry("expires_at_ms", Long.toString(created.expiresAtMs())));
        assertThat(redis.<String>getBucket(RedisKeys.matchChallengeTarget(target), StringCodec.INSTANCE).get())
                .isEqualTo(Long.toUnsignedString(id));
        assertThat(pttl(RedisKeys.matchChallenge(id))).isBetween(55_000L, 60_000L);
        assertThat(pttl(RedisKeys.matchChallengeTarget(target))).isBetween(55_000L, 60_000L);
        assertThat(RedisKeys.matchChallenge(id)).startsWith("xm:{match}:challenge:");
        assertThat(RedisKeys.matchChallengeTarget(target)).startsWith("xm:{match}:challenge-target:");
    }

    @Test
    void 消费后_记录与占坑都删了_留下六十秒的墓碑_墓碑带nonce与消费时刻() {
        long id = newChallengeId();
        long challenger = newPlayerId();
        long target = newPlayerId();
        long expiresAtMs = ((InviteResult.Created) store().invite(id, challenger, target, 4, 60_000, d())).expiresAtMs();

        ConsumeResult.Consumed consumed = (ConsumeResult.Consumed) store().consume(id, target, "nonce-77", d());

        assertThat(redis.getKeys().countExists(RedisKeys.matchChallenge(id), RedisKeys.matchChallengeTarget(target))).isZero();
        Map<String, String> tombstone = redis.<String, String>getMap(RedisKeys.matchChallengeDone(id), StringCodec.INSTANCE).readAllMap();
        assertThat(tombstone).containsOnly(
                Map.entry("nonce", "nonce-77"),
                Map.entry("challenger", Long.toUnsignedString(challenger)),
                Map.entry("target", Long.toUnsignedString(target)),
                Map.entry("config", "4"),
                Map.entry("expires_at_ms", Long.toString(expiresAtMs)),
                Map.entry("now_ms", Long.toString(consumed.redisNowMs())));
        assertThat(pttl(RedisKeys.matchChallengeDone(id))).isBetween(55_000L, 60_000L);
        assertThat(RedisKeys.matchChallengeDone(id)).startsWith("xm:{match}:challenge-done:");
    }

    @Test
    void 重放的发起不续期_记录与占坑的TTL照第一次的走() throws Exception {
        long id = newChallengeId();
        long target = newPlayerId();
        store().invite(id, newPlayerId(), target, 0, 2_000, d());
        TimeUnit.MILLISECONDS.sleep(600);

        store().invite(id, newPlayerId(), target, 0, 2_000, d());

        assertThat(pttl(RedisKeys.matchChallenge(id))).as("没有被重置成 2000").isLessThan(1_500L).isPositive();
        assertThat(pttl(RedisKeys.matchChallengeTarget(target))).isLessThan(1_500L).isPositive();
    }

    @Test
    void 占坑是本邀请但记录已不在_再发起一次按全新的写_记录与占坑重新对齐() {
        long id = newChallengeId();
        long challenger = newPlayerId();
        long target = newPlayerId();
        long first = ((InviteResult.Created) store().invite(id, challenger, target, 0, 2_000, d())).expiresAtMs();
        redis.getKeys().delete(RedisKeys.matchChallenge(id));

        long second = ((InviteResult.Created) store().invite(id, challenger, target, 0, 60_000, d())).expiresAtMs();

        assertThat(second).as("不是把一条读不到的记录当成重放").isGreaterThan(first + 50_000);
        assertThat(store().read(id, d())).hasValue(new ChallengeRecord(challenger, target, 0, second));
        assertThat(pttl(RedisKeys.matchChallenge(id))).isBetween(55_000L, 60_000L);
        assertThat(pttl(RedisKeys.matchChallengeTarget(target))).as("占坑的寿命跟着重写").isBetween(55_000L, 60_000L);
    }

    @Test
    void 损坏的记录_字段按0读_目标对不上不消费() {
        long id = newChallengeId();
        long responder = newPlayerId();
        redis.<String, String>getMap(RedisKeys.matchChallenge(id), StringCodec.INSTANCE)
                .putAll(Map.of("challenger", "abc", "config", "-3", "expires_at_ms", "1e12"));
        redis.getKeys().expire(RedisKeys.matchChallenge(id), 60, TimeUnit.SECONDS);

        assertThat(store().read(id, d())).as("同基线：解析不了的字段按 0").hasValue(new ChallengeRecord(0, 0, 0, 0));
        assertThat(store().consume(id, responder, "nonce", d())).as("没有 target 字段 = 不是发给任何人的").isEqualTo(new ConsumeResult.NotTarget());
        assertThat(redis.getKeys().countExists(RedisKeys.matchChallenge(id))).as("没有被消费").isEqualTo(1);
    }

    @Test
    void 客户端已不可用_各方法都在预算内以依赖异常失败() {
        RedissonClient closed = Redisson.create(config());
        RedissonChallengeStore broken = new RedissonChallengeStore(closed);
        long id = newChallengeId();
        long target = newPlayerId();
        assertThat(broken.read(id, d())).isEmpty();
        closed.shutdown();

        long started = System.nanoTime();
        assertThatThrownBy(() -> broken.invite(id, newPlayerId(), target, 0, 60_000, Deadline.after(1_500)))
                .isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> broken.read(id, Deadline.after(1_500))).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> broken.consume(id, target, "nonce", Deadline.after(1_500))).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> broken.delete(id, target, Deadline.after(1_500))).isInstanceOf(Deadline.DependencyException.class);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).as("四次调用都在各自的预算附近返回").isLessThan(10_000);
        assertThat(redis.getKeys().countExists(RedisKeys.matchChallenge(id), RedisKeys.matchChallengeTarget(target))).as("什么都没写").isZero();
    }
}
