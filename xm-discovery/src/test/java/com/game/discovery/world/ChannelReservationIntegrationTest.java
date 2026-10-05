package com.game.discovery.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.discovery.RedisKeys;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

/**
 * 进场软预占连真 Redis（scene-channels-spec §4.11、§9.5 ChannelReservationIT）。
 * 默认跳过；显式开启：{@code -Dxm.it.redis=redis://127.0.0.1:6379}。用 DB 9，每个用例一个随机 zone，结束时只删自己的键。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class ChannelReservationIntegrationTest {

    private static final Duration TTL = Duration.ofSeconds(10);

    private static RedissonClient redis;
    private static WorldChannelStore store;
    private static final List<Integer> ZONES = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    static void connect() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(9);
        redis = Redisson.create(config);
        store = new RedissonWorldChannelStore(redis);
    }

    @AfterAll
    static void cleanup() {
        for (int zone : ZONES) {
            redis.getKeys().deleteByPattern("xm:world:{z:" + Integer.toUnsignedString(zone) + "}:*");
        }
        redis.shutdown();
    }

    private static int newZone() {
        int zone = ThreadLocalRandom.current().nextInt(100_000_000, 2_000_000_000);
        ZONES.add(zone);
        return zone;
    }

    private static List<ReservationCandidate> candidates(int... players) {
        List<ReservationCandidate> out = new ArrayList<>();
        for (int i = 0; i < players.length; i++) {
            out.add(new ReservationCandidate(1_000 + i, players[i]));
        }
        return out;
    }

    private static List<Long> counts(int zone, int n) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(1_000L + i);
        }
        return store.countReservations(zone, ids);
    }

    @Test
    void 两百个并发分配摊到四个频道_差不超过一() throws Exception {
        int zone = newZone();
        List<ReservationCandidate> four = candidates(0, 0, 0, 0);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<ReservationPick>> picks = new ArrayList<>();
            for (int p = 0; p < 200; p++) {
                long playerId = 10_000 + p;
                picks.add(pool.submit(() -> {
                    go.await();
                    return store.reserve(zone, four, playerId, TTL);
                }));
            }
            go.countDown();
            int[] perScene = new int[4];
            for (Future<ReservationPick> f : picks) {
                perScene[f.get(10, TimeUnit.SECONDS).index()]++;
            }
            int min = Integer.MAX_VALUE;
            int max = 0;
            for (int n : perScene) {
                min = Math.min(min, n);
                max = Math.max(max, n);
            }
            assertThat(max - min).isLessThanOrEqualTo(1);
            assertThat(counts(zone, 4)).containsExactly(50L, 50L, 50L, 50L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 负载是目录人数加别人的预占_并列取靠前的() {
        int zone = newZone();
        // 目录人数 (3, 1, 1)：第二个最空，并列取靠前
        assertThat(store.reserve(zone, candidates(3, 1, 1), 1, TTL)).isEqualTo(new ReservationPick(1, 1));
        // 第二个现在 1 + 1 预占 = 2，第三个 1 → 选第三个
        assertThat(store.reserve(zone, candidates(3, 1, 1), 2, TTL)).isEqualTo(new ReservationPick(2, 1));
        // (2, 2, 2) 并列 → 第二个（负载 2 = 目录 1 + 预占 1，与第一个的 3 相比更小）
        assertThat(store.reserve(zone, candidates(3, 1, 1), 3, TTL)).isEqualTo(new ReservationPick(1, 2));
        // 目录人数是 uint32：最高位为 1 也按无符号大数处理（不会被当成负数选中）
        assertThat(store.reserve(zone, List.of(new ReservationCandidate(1_000, -1), new ReservationCandidate(1_001, 7)),
                4, TTL).index()).isEqualTo(1);
    }

    @Test
    void 同一玩家重复分配不重复计数_也不因自己的旧预占改变选择() {
        int zone = newZone();
        for (int i = 0; i < 5; i++) {
            assertThat(store.reserve(zone, candidates(0, 0), 77, TTL)).isEqualTo(new ReservationPick(0, 0));
        }
        assertThat(counts(zone, 2)).containsExactly(1L, 0L);
        // 目录变了，另一个更空：预占搬过去，旧的撤掉
        assertThat(store.reserve(zone, candidates(5, 0), 77, TTL)).isEqualTo(new ReservationPick(1, 0));
        assertThat(counts(zone, 2)).containsExactly(0L, 1L);
    }

    @Test
    void 预占按_Redis_TIME_到期_键带_TTL() throws Exception {
        int zone = newZone();
        Duration shortTtl = Duration.ofMillis(400);
        assertThat(store.reserve(zone, candidates(0, 0), 1, shortTtl).index()).isZero();
        assertThat(store.reserve(zone, candidates(0, 0), 2, shortTtl).index()).isEqualTo(1);
        long pttl = redis.getScoredSortedSet(RedisKeys.worldReservations(zone, 1_000), StringCodec.INSTANCE).remainTimeToLive();
        assertThat(pttl).isPositive().isLessThanOrEqualTo(400);
        assertThat(counts(zone, 2)).containsExactly(1L, 1L);
        Thread.sleep(250);
        // 晚到的预占把键的 TTL 推后，但早到的成员仍按自己的到期时刻失效
        store.reserveScene(zone, 1_000, 3, Duration.ofSeconds(5));
        Thread.sleep(300);
        assertThat(counts(zone, 2)).containsExactly(1L, 0L);
        // 到期的不再计入负载
        assertThat(store.reserve(zone, candidates(0, 0), 4, TTL)).isEqualTo(new ReservationPick(1, 0));
    }

    @Test
    void 指定场景预占_撤销_计数() {
        int zone = newZone();
        store.reserveScene(zone, 1_000, 5, TTL);
        store.reserveScene(zone, 1_000, 5, TTL);       // 同一玩家只刷新到期时间
        store.reserveScene(zone, 1_000, 6, TTL);
        assertThat(counts(zone, 1)).containsExactly(2L);
        assertThat(store.releaseReservation(zone, 1_000, 5)).isTrue();
        assertThat(store.releaseReservation(zone, 1_000, 5)).isFalse();
        assertThat(counts(zone, 1)).containsExactly(1L);
        assertThat(store.countReservations(zone, List.of())).isEmpty();
        // 计数里有并发分配看得见的那条：(0, 0) 时选第二个
        assertThat(store.reserve(zone, candidates(0, 0), 7, TTL).index()).isEqualTo(1);
    }

    @Test
    void 参数校验() {
        int zone = newZone();
        assertThatThrownBy(() -> store.reserve(zone, List.of(), 1, TTL)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.reserve(zone, candidates(0), 1, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReservationCandidate(0, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void SCRIPT_FLUSH_之后_NOSCRIPT_能重载() {
        int zone = newZone();
        assertThat(store.reserve(zone, candidates(0, 0), 1, TTL).index()).isZero();
        redis.getScript().scriptFlush();                // SCRIPT FLUSH：服务端忘掉全部脚本
        assertThat(store.reserve(zone, candidates(0, 0), 2, TTL).index()).isEqualTo(1);
        assertThat(counts(zone, 2)).containsExactly(1L, 1L);
        assertThat(store.snapshot(zone).version()).isZero();
    }
}
