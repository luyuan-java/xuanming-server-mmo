package com.game.match.matcher;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.discovery.RedisKeys;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.ticket.QueueRef;
import com.game.match.ticket.TicketStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.protocol.ScoredEntry;
import org.redisson.config.Config;

/**
 * {@link MatcherPortedScenarios}（从基线移植的凑单端到端用例）跑在<b>真 Redis 的票据存储</b>上（缺省跳过：{@code -Dxm.it.redis=redis://127.0.0.1:6379}）：
 * 凑单的每一步——快照、批量读票、摘无效成员、原子弹组、剔除空队列、凑单锁——落在真的 Lua 脚本上，与内存替身上的那一遍
 * （{@link MatcherPortedInMemoryTest}）同一套断言。
 *
 * <p><b>隔离</b>：用 DB 13（本机切片的 xm-match 在 DB 12，它的凑单会把注册集里的测试队列弹走）；每个用例只碰自己随机副本号的队列与随机玩家号，
 * 结束时只删自己写的键；凑单直接对单条队列驱动，不遍历全局注册集。
 *
 * <p><b>票据存储的真实现在 ticket 包</b>（{@value #STORE_CLASS}，与凑单并行开发）。这里按类名找它、用只需要 Redis 客户端（及本模块现成的
 * 指标 / 配置对象）的公开构造器建：类不在类路径上时整类按前提不满足跳过；类在而构造器对不上时<b>失败</b>并提示改成直接构造——
 * 两个包合到一起之后，建议把 {@link #realStore} 换成一行 {@code new}。
 * 旁路摆数据用到的键与字段名（票据 HASH 的 {@code enqueued_at_ms}、队列 LIST、镜像 ZSET、注册集 SET、凑单锁 STRING）取自 match-spec §9.4。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class MatcherRedisIntegrationTest extends MatcherPortedScenarios {

    static final String STORE_CLASS = "com.game.match.ticket.RedissonTicketStore";
    private static final String ENQUEUED_AT_FIELD = "enqueued_at_ms";

    private static RedissonClient redis;
    private static TicketStore store;

    @BeforeAll
    static void connect() {
        Class<?> type;
        try {
            type = Class.forName(STORE_CLASS);
        } catch (ClassNotFoundException e) {
            Assumptions.assumeTrue(false, "票据存储的 Redis 实现 " + STORE_CLASS + " 不在类路径上（ticket 包合入之后才能跑）");
            return;
        }
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(13).setConnectionMinimumIdleSize(1).setConnectionPoolSize(4);
        redis = Redisson.create(config);
        store = realStore(type, redis);
    }

    @AfterAll
    static void close() {
        if (redis != null) {
            redis.shutdown();
        }
    }

    /**
     * 用「参数都能从手头这几样里凑出来」的公开构造器建真实现（参数最少的优先）。手头有：Redis 客户端、一份缺省配置、一套挂在一次性注册表上的指标。
     */
    static TicketStore realStore(Class<?> type, RedissonClient client) {
        MetricLabels labels = new MetricLabels(id -> true);
        List<Object> available = List.of(client, MatcherRig.defaults(), labels, new MatchMetrics(new SimpleMeterRegistry(), labels));
        Constructor<?>[] constructors = type.getConstructors();
        Arrays.sort(constructors, Comparator.comparingInt(Constructor::getParameterCount));
        for (Constructor<?> constructor : constructors) {
            Object[] args = new Object[constructor.getParameterCount()];
            boolean satisfiable = true;
            for (int i = 0; i < args.length && satisfiable; i++) {
                Class<?> wanted = constructor.getParameterTypes()[i];
                args[i] = available.stream().filter(wanted::isInstance).findFirst().orElse(null);
                satisfiable = args[i] != null;
            }
            if (satisfiable) {
                try {
                    return (TicketStore) constructor.newInstance(args);
                } catch (ReflectiveOperationException | ClassCastException e) {
                    throw new AssertionError("建 " + STORE_CLASS + " 失败（构造器 " + constructor + "）：把 realStore 换成直接构造", e);
                }
            }
        }
        throw new AssertionError(STORE_CLASS + " 没有只需要 RedissonClient / MatchProperties / MatchMetrics / MetricLabels 的公开构造器（现有："
                + Arrays.toString(constructors) + "）：把 MatcherRedisIntegrationTest.realStore 换成直接构造");
    }

    /** 只删本用例自己写的键。 */
    @AfterEach
    void deleteOwnKeys() {
        if (redis == null) {
            return;
        }
        List<String> keys = new ArrayList<>();
        for (Long playerId : usedPlayers) {
            keys.add(RedisKeys.matchTicket(playerId));
        }
        for (QueueRef queue : usedQueues) {
            keys.add(queue.queueKey());
            keys.add(queue.rankKey());
            keys.add(queue.lockKey());
            redis.getSet(RedisKeys.matchQueueIndex(), StringCodec.INSTANCE).remove(queue.queueKey());
        }
        if (!keys.isEmpty()) {
            redis.getKeys().delete(keys.toArray(String[]::new));
        }
    }

    // ================================================================ MatcherPortedScenarios 的存储与旁路

    @Override
    protected TicketStore store() {
        return store;
    }

    @Override
    protected void setEnqueuedAgo(long playerId, Duration ago) {
        RMap<String, String> ticket = redis.getMap(RedisKeys.matchTicket(playerId), StringCodec.INSTANCE);
        assertThat(ticket.containsKey(ENQUEUED_AT_FIELD)).as("票据 HASH 里入队时刻的字段名应是 %s（match-spec §9.4）；现有字段 %s", ENQUEUED_AT_FIELD,
                ticket.readAllKeySet()).isTrue();
        ticket.fastPut(ENQUEUED_AT_FIELD, Long.toString(redisNowMs() - ago.toMillis()));
    }

    @Override
    protected List<String> queueMembers(QueueRef queue) {
        return redis.<String>getList(queue.queueKey(), StringCodec.INSTANCE).readAll();
    }

    @Override
    protected Map<String, Long> rankScores(QueueRef queue) {
        Map<String, Long> scores = new LinkedHashMap<>();
        for (ScoredEntry<String> entry : redis.<String>getScoredSortedSet(queue.rankKey(), StringCodec.INSTANCE).entryRange(0, -1)) {
            scores.put(entry.getValue(), Math.round(entry.getScore()));
        }
        return scores;
    }

    @Override
    protected boolean indexed(QueueRef queue) {
        return redis.getSet(RedisKeys.matchQueueIndex(), StringCodec.INSTANCE).contains(queue.queueKey());
    }

    @Override
    protected Optional<String> lockHolder(QueueRef queue) {
        return Optional.ofNullable(redis.<String>getBucket(queue.lockKey(), StringCodec.INSTANCE).get());
    }

    @Override
    protected long ticketTtlMs(long playerId) {
        return redis.getMap(RedisKeys.matchTicket(playerId), StringCodec.INSTANCE).remainTimeToLive();
    }

    @Override
    protected void registerQueue(QueueRef queue) {
        redis.getSet(RedisKeys.matchQueueIndex(), StringCodec.INSTANCE).add(queue.queueKey());
    }

    @Override
    protected void pushRaw(QueueRef queue, String member, long ratingCenti) {
        redis.getSet(RedisKeys.matchQueueIndex(), StringCodec.INSTANCE).add(queue.queueKey());
        redis.<String>getScoredSortedSet(queue.rankKey(), StringCodec.INSTANCE).add(ratingCenti, member);
        redis.<String>getList(queue.queueKey(), StringCodec.INSTANCE).add(member);
    }

    @Override
    protected boolean addRankOnly(QueueRef queue, String member, long ratingCenti) {
        redis.<String>getScoredSortedSet(queue.rankKey(), StringCodec.INSTANCE).add(ratingCenti, member);
        return true;
    }

    /** Redis 的 {@code TIME}（Unix 毫秒）：票据里的时间都是它，改写入队时刻也按它算。 */
    private static long redisNowMs() {
        List<Object> time = redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_ONLY, "return redis.call('TIME')", RScript.ReturnType.MULTI,
                List.of());
        return Long.parseLong(time.get(0).toString()) * 1000 + Long.parseLong(time.get(1).toString()) / 1000;
    }
}
