package com.game.match.matcher;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.discovery.RedisKeys;
import com.game.match.ticket.QueueRef;
import com.game.match.ticket.RedissonTicketStore;
import com.game.match.ticket.TicketStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
 * <p>票据存储用的是 ticket 包的生产实现 {@link RedissonTicketStore}（直接构造，与进程里 {@code QueueConfiguration} 建的是同一个类）。
 * 旁路摆数据用到的键与字段名（票据 HASH 的 {@code enqueued_at_ms}、队列 LIST、镜像 ZSET、注册集 SET、凑单锁 STRING）取自 match-spec §9.4。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class MatcherRedisIntegrationTest extends MatcherPortedScenarios {

    private static final String ENQUEUED_AT_FIELD = "enqueued_at_ms";

    private static RedissonClient redis;
    private static TicketStore store;

    @BeforeAll
    static void connect() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(13).setConnectionMinimumIdleSize(1).setConnectionPoolSize(4);
        redis = Redisson.create(config);
        store = new RedissonTicketStore(redis);
    }

    @AfterAll
    static void close() {
        if (redis != null) {
            redis.shutdown();
        }
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
