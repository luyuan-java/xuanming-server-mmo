package com.game.gateway.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.GateNodeInfo;
import com.game.discovery.RedisKeys;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

/**
 * 登录排队的 Lua 脚本在真 Redis 上：入队名次、占位按到期分数清理、快速通道预算、按空位原子放行、轮询的三种结局、取走被重发。
 * 默认跳过；显式开启：{@code -Dxm.it.redis=redis://127.0.0.1:6379}。用 DB 13 与随机大号区隔离。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class LoginQueueIntegrationTest {

    private static RedissonClient redis;
    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    private int zone;
    private LoginQueue queue;

    private static final GateNodeInfo GATE = GateNodeInfo.newBuilder().setZoneId(1).setNodeId(7).setClientHost("10.0.0.7")
            .setClientPort(11007).build();

    @BeforeAll
    static void connect() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(13);
        redis = Redisson.create(config);
    }

    @AfterAll
    static void disconnect() {
        redis.shutdown();
    }

    @BeforeEach
    void setUp() {
        zone = 800_000 + ThreadLocalRandom.current().nextInt(100_000);
        queue = new LoginQueue(redis, now::get, Duration.ofMinutes(10), Duration.ofMillis(300));
    }

    @AfterEach
    void cleanup() {
        redis.getKeys().delete(RedisKeys.loginQueue(zone), RedisKeys.loginQueueAdmitted(zone));
    }

    @Test
    void 入队名次从0起_队长() {
        LoginQueue.Enqueued a = queue.enqueue(zone);
        now.incrementAndGet();
        LoginQueue.Enqueued b = queue.enqueue(zone);
        assertThat(a.rank()).isZero();
        assertThat(b.rank()).isEqualTo(1);
        assertThat(b.total()).isEqualTo(2);
        assertThat(queue.queueLength(zone)).isEqualTo(2);
        assertThat(queue.lookup(zone, b.queueId())).isEqualTo(new LoginQueue.Waiting(1, 2));
        redis.getKeys().delete(RedisKeys.loginQueueMeta(a.queueId()), RedisKeys.loginQueueMeta(b.queueId()));
    }

    @Test
    void 快速通道按预算占位_占位到期后清掉() {
        assertThat(queue.tryReserveFastPath(zone, 2)).isTrue();
        assertThat(queue.tryReserveFastPath(zone, 2)).isTrue();
        assertThat(queue.tryReserveFastPath(zone, 2)).as("预算用完").isFalse();
        assertThat(queue.tryReserveFastPath(zone, 0)).isFalse();
        assertThat(queue.admittedCount(zone)).isEqualTo(2);
        now.addAndGet(301);
        assertThat(queue.admittedCount(zone)).as("占位按到期分数清掉，不靠整体 TTL").isZero();
        assertThat(queue.tryReserveFastPath(zone, 2)).isTrue();
    }

    @Test
    void 放行按预算减占位_取走一次_占位再留一会_再轮询过期_放行槽超时没取也过期() throws Exception {
        LoginQueue.Enqueued a = queue.enqueue(zone);
        now.incrementAndGet();
        LoginQueue.Enqueued b = queue.enqueue(zone);
        now.incrementAndGet();
        LoginQueue.Enqueued c = queue.enqueue(zone);
        assertThat(queue.tryReserveFastPath(zone, 10)).isTrue();

        assertThat(queue.dispatch(zone, 2, 50, GATE)).as("预算 2 − 占位 1 = 空位 1").isEqualTo(new LoginQueue.Dispatched(1, 0));
        assertThat(queue.admittedCount(zone)).isEqualTo(2);
        assertThat(queue.lookup(zone, b.queueId())).isEqualTo(new LoginQueue.Waiting(0, 2));
        assertThat(queue.dispatch(zone, 2, 50, GATE)).as("空位用完").isEqualTo(new LoginQueue.Dispatched(0, 0));

        LoginQueue.Lookup first = queue.lookup(zone, a.queueId());
        assertThat(first).isInstanceOf(LoginQueue.Admitted.class);
        assertThat(((LoginQueue.Admitted) first).gate()).isEqualTo(GATE);
        assertThat(((LoginQueue.Admitted) first).enqueuedAtMs()).isEqualTo(1_800_000_000_000L);
        assertThat(queue.admittedCount(zone)).as("取走后占位还在（等 gate 发布人数）").isEqualTo(2);
        assertThat(redis.getScoredSortedSet(RedisKeys.loginQueueAdmitted(zone), StringCodec.INSTANCE)
                .getScore(a.queueId())).isEqualTo((double) (now.get() + LoginQueue.TAKEN_GRACE.toMillis()));
        assertThat(queue.lookup(zone, a.queueId())).as("只取一次").isInstanceOf(LoginQueue.Expired.class);

        assertThat(queue.dispatch(zone, 10, 1, GATE)).as("每次至多 max 个").isEqualTo(new LoginQueue.Dispatched(1, 0));
        Thread.sleep(400);
        assertThat(queue.lookup(zone, b.queueId())).as("放行槽过期没取").isInstanceOf(LoginQueue.Expired.class);
        assertThat(queue.lookup(zone, QueueTokens.newQueueId())).as("不认识的排队号").isInstanceOf(LoginQueue.Expired.class);
        assertThat(queue.lookup(zone, c.queueId())).isEqualTo(new LoginQueue.Waiting(0, 1));
        redis.getKeys().delete(RedisKeys.loginQueueMeta(a.queueId()), RedisKeys.loginQueueMeta(b.queueId()),
                RedisKeys.loginQueueMeta(c.queueId()));
    }

    @Test
    void 同一次取走被重发认得出_别的请求还是过期() {
        LoginQueue.Enqueued a = queue.enqueue(zone);
        assertThat(queue.dispatch(zone, 10, 50, GATE).admitted()).isEqualTo(1);
        LoginQueue.Lookup first = queue.lookup(zone, a.queueId(), "r1");
        assertThat(first).isInstanceOf(LoginQueue.Admitted.class);
        assertThat(queue.lookup(zone, a.queueId(), "r1")).as("Redisson 重发同一段 EVAL").isEqualTo(first);
        assertThat(queue.lookup(zone, a.queueId(), "r2")).as("新请求").isInstanceOf(LoginQueue.Expired.class);
        assertThat(queue.lookup(zone, a.queueId())).isInstanceOf(LoginQueue.Expired.class);
        redis.getKeys().delete(RedisKeys.loginQueueMeta(a.queueId()));
    }

    @Test
    void 元数据已过期的弹出即丢不放行() {
        LoginQueue.Enqueued a = queue.enqueue(zone);
        now.incrementAndGet();
        LoginQueue.Enqueued b = queue.enqueue(zone);
        redis.getKeys().delete(RedisKeys.loginQueueMeta(a.queueId()));
        assertThat(queue.dispatch(zone, 10, 50, GATE)).isEqualTo(new LoginQueue.Dispatched(1, 1));
        assertThat(queue.admittedCount(zone)).isEqualTo(1);
        assertThat(redis.getBucket(RedisKeys.loginQueueAdmit(a.queueId())).isExists()).isFalse();
        assertThat(queue.lookup(zone, b.queueId())).isInstanceOf(LoginQueue.Admitted.class);
        redis.getKeys().delete(RedisKeys.loginQueueMeta(b.queueId()));
    }
}
