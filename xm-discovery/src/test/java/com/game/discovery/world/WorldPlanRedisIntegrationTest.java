package com.game.discovery.world;

import static com.game.discovery.world.WorldPlanBatchTest.channel;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.ChannelState;
import com.game.api.proto.DrainReason;
import com.game.api.proto.WorldChannel;
import com.game.discovery.RedisKeys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

/**
 * 频道计划与领导锁连真 Redis（scene-channels-spec §9.5 WorldPlanRedisIT / WorldLeaderFailoverIT 的存储层部分）。
 * 默认跳过；显式开启：{@code -Dxm.it.redis=redis://127.0.0.1:6379}。用 DB 9，每个用例一个随机 zone，结束时只删自己的键。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class WorldPlanRedisIntegrationTest {

    private static RedissonClient redis;
    private static WorldChannelStore store;
    private static final List<Integer> ZONES = new ArrayList<>();

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
            redis.getSet(RedisKeys.worldZones(), StringCodec.INSTANCE).remove(Integer.toUnsignedString(zone));
        }
        redis.shutdown();
    }

    private static synchronized int newZone() {
        int zone = ThreadLocalRandom.current().nextInt(100_000_000, 2_000_000_000);
        ZONES.add(zone);
        return zone;
    }

    private static String leader(int zone, String token) {
        assertThat(store.tryAcquireLeader(zone, token, Duration.ofSeconds(30))).isTrue();
        return token;
    }

    @Test
    void 空计划_版本_0_快照为空_TIME_接近墙钟() {
        int zone = newZone();
        assertThat(store.planVersion(zone)).isZero();
        WorldPlan plan = store.readPlan(zone);
        assertThat(plan.version()).isZero();
        assertThat(plan.channels()).isEmpty();
        WorldPlanSnapshot snap = store.snapshot(zone);
        assertThat(snap.version()).isZero();
        assertThat(snap.channels()).isEmpty();
        assertThat(snap.desired()).isEmpty();
        assertThat(snap.cooldownUntilMs()).isEmpty();
        assertThat(Math.abs(snap.nowMs() - System.currentTimeMillis())).isLessThan(5_000);
    }

    @Test
    void 全部_op_按顺序执行_版本严格递增_pb_字节往返无损() {
        int zone = newZone();
        String token = leader(zone, "sm-a:" + zone);
        // 选一个编码里含 0x00 与高位字节的记录：scene_id 最高位为 1、时刻为负数
        WorldChannel odd = channel(-256L, -1, 0x7f00_0001, 0).toBuilder()
                .setCreatedMs(-1L).setStateSinceMs(0x0100_0000_0000L)
                .setState(ChannelState.CHANNEL_DRAINING).setDrainReason(DrainReason.DRAIN_ORPHAN).build();
        WorldChannel plain = channel(42, 1, 3, 2);

        WorldPlanWriteResult first = store.write(zone, token, new WorldPlanBatch(0)
                .seedDesired(1, 16)
                .seedDesired(2, 1)
                .putChannel(odd)
                .putChannel(plain)
                .setCooldown(1, 123)
                .setCooldown(2, 456));
        assertThat(first).isEqualTo(WorldPlanWriteResult.written(1));

        WorldPlanSnapshot snap = store.snapshot(zone);
        assertThat(snap.version()).isEqualTo(1);
        assertThat(snap.channels()).containsExactly(
                Map.entry(42L, plain.toBuilder().setPlanVersion(1).build()),
                Map.entry(-256L, odd.toBuilder().setPlanVersion(1).build()));
        assertThat(snap.desired()).containsExactly(Map.entry(1, 16), Map.entry(2, 1));
        assertThat(snap.cooldownUntilMs()).containsExactly(Map.entry(1, 123L), Map.entry(2, 456L));
        assertThat(store.readPlan(zone).channels()).containsExactlyElementsOf(snap.channels().values());

        // HSETNX 不覆盖已有值；Q 覆盖；X / Y / D 删除
        WorldPlanWriteResult second = store.write(zone, token, new WorldPlanBatch(1)
                .seedDesired(1, 3)
                .setDesired(2, 5)
                .removeDesired(9)
                .removeCooldown(1)
                .removeChannel(42)
                .removeChannel(7));
        assertThat(second).isEqualTo(WorldPlanWriteResult.written(2));
        snap = store.snapshot(zone);
        assertThat(snap.version()).isEqualTo(2);
        assertThat(snap.channels()).containsOnlyKeys(-256L);
        assertThat(snap.desired()).containsExactly(Map.entry(1, 16), Map.entry(2, 5));
        assertThat(snap.cooldownUntilMs()).containsExactly(Map.entry(2, 456L));

        // 空批次也推进 ver；版本号对 scene 的 GET 可见
        assertThat(store.write(zone, token, new WorldPlanBatch(2))).isEqualTo(WorldPlanWriteResult.written(3));
        assertThat(store.planVersion(zone)).isEqualTo(3);
        assertThat(store.readPlan(zone).version()).isEqualTo(3);
    }

    @Test
    void 领导者批次的版本号以_Redis_TIME_托底_丢写回退后重写不与丢失的那一版撞号() throws InterruptedException {
        int zone = newZone();
        String token = leader(zone, "sm-a:" + zone);
        WorldPlanSnapshot before = store.snapshot(zone);
        WorldPlanBatch lostBatch = WorldPlanBatch.forSnapshot(before).putChannel(channel(77, 1, 1, 0));
        assertThat(store.write(zone, token, lostBatch)).isEqualTo(WorldPlanWriteResult.written(lostBatch.writtenVersion()));
        assertThat(lostBatch.writtenVersion()).isGreaterThanOrEqualTo(before.nowMs());
        assertThat(store.planVersion(zone)).isEqualTo(lostBatch.writtenVersion());

        // 模拟主从切换丢了这次写：版本号与记录都退回写之前
        redis.getBucket(RedisKeys.worldPlanVersion(zone), StringCodec.INSTANCE)
                .set(Long.toString(before.version()));
        redis.getMap(RedisKeys.worldChannels(zone), StringCodec.INSTANCE).fastRemove("77");
        Thread.sleep(5);

        WorldPlanSnapshot after = store.snapshot(zone);
        assertThat(after.version()).isEqualTo(before.version());
        WorldPlanBatch rewrite = WorldPlanBatch.forSnapshot(after).putChannel(channel(78, 1, 1, 0));
        assertThat(store.write(zone, token, rewrite).isWritten()).isTrue();
        // 同一期望版本、不同内容：新版本号严格大于丢失的那一版，节点按「版本号变了」重读
        assertThat(rewrite.writtenVersion()).isGreaterThan(lostBatch.writtenVersion());
        assertThat(store.readPlan(zone).version()).isEqualTo(rewrite.writtenVersion());
        assertThat(store.readPlan(zone).channels()).extracting(WorldChannel::getSceneId).containsExactly(78L);
    }

    @Test
    void 令牌被夺后旧令牌写入回_负一_数据不变() {
        int zone = newZone();
        String old = leader(zone, "sm-old:" + zone);
        assertThat(store.write(zone, old, new WorldPlanBatch(0).putChannel(channel(1, 1, 1, 0))).isWritten()).isTrue();

        // 锁过期后被别人拿走（这里直接改值模拟被夺）
        redis.getBucket(RedisKeys.worldLeader(zone), StringCodec.INSTANCE).set("sm-new:" + zone);
        assertThat(store.write(zone, old, new WorldPlanBatch(1).removeChannel(1).seedDesired(1, 9)))
                .isEqualTo(WorldPlanWriteResult.FENCED);
        assertThat(store.renewLeader(zone, old, Duration.ofSeconds(30))).isFalse();
        assertThat(store.releaseLeader(zone, old)).isFalse();
        WorldPlanSnapshot snap = store.snapshot(zone);
        assertThat(snap.version()).isEqualTo(1);
        assertThat(snap.channels()).containsOnlyKeys(1L);
        assertThat(snap.desired()).isEmpty();

        // 锁不存在也算不是领导者
        redis.getBucket(RedisKeys.worldLeader(zone), StringCodec.INSTANCE).delete();
        assertThat(store.write(zone, old, new WorldPlanBatch(1).removeChannel(1))).isEqualTo(WorldPlanWriteResult.FENCED);
    }

    @Test
    void 版本号_CAS_冲突回_负二_数据不变() {
        int zone = newZone();
        String token = leader(zone, "sm:" + zone);
        assertThat(store.write(zone, token, new WorldPlanBatch(0).putChannel(channel(5, 1, 1, 0))).isWritten()).isTrue();
        // 用旧快照（ver 0）再写：冲突
        assertThat(store.write(zone, token, new WorldPlanBatch(0).removeChannel(5)))
                .isEqualTo(WorldPlanWriteResult.CONFLICT);
        // 期望比当前大也冲突
        assertThat(store.write(zone, token, new WorldPlanBatch(7).removeChannel(5)))
                .isEqualTo(WorldPlanWriteResult.CONFLICT);
        assertThat(store.snapshot(zone).channels()).containsOnlyKeys(5L);
        assertThat(store.planVersion(zone)).isEqualTo(1);
    }

    @Test
    void 两个写者同时按同一版本写_只有一个生效() throws Exception {
        int zone = newZone();
        String token = leader(zone, "sm:" + zone);   // 双领导窗口里令牌恰好相同也过不了 CAS
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 20; round++) {
                long expected = store.planVersion(zone);
                CountDownLatch go = new CountDownLatch(1);
                int r = round;
                Future<WorldPlanWriteResult> a = pool.submit(() -> {
                    go.await();
                    return store.write(zone, token, new WorldPlanBatch(expected).seedDesired(1000 + r, 3));
                });
                Future<WorldPlanWriteResult> b = pool.submit(() -> {
                    go.await();
                    return store.write(zone, token, new WorldPlanBatch(expected).seedDesired(1000 + r, 5));
                });
                go.countDown();
                List<WorldPlanWriteResult> results = List.of(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS));
                assertThat(results).filteredOn(WorldPlanWriteResult::isWritten).hasSize(1);
                assertThat(results).contains(WorldPlanWriteResult.CONFLICT);
                assertThat(store.planVersion(zone)).isEqualTo(expected + 1);
            }
        } finally {
            pool.shutdownNow();
        }
        // 后来者用新快照播种也不覆盖（HSETNX）
        long ver = store.planVersion(zone);
        int before = store.snapshot(zone).desired().get(1000);
        assertThat(store.write(zone, token, new WorldPlanBatch(ver).seedDesired(1000, 99)).isWritten()).isTrue();
        assertThat(store.snapshot(zone).desired().get(1000)).isEqualTo(before);
    }

    @Test
    void 只读快照与并发写一致() throws Exception {
        int zone = newZone();
        String token = leader(zone, "sm:" + zone);
        // 不变量：第 v 次写入后，频道数 = v、期望数 conf 1 = v、冷却 conf 1 = v（同一段 Lua 写，快照要么全看到要么全看不到）
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger checked = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = pool.submit(() -> {
                for (int v = 0; v < 150; v++) {
                    WorldPlanWriteResult r = store.write(zone, token, new WorldPlanBatch(v)
                            .putChannel(channel(v + 1, 1, 1, Math.min(v, WorldChannels.MAX_SLOT)))
                            .setDesired(1, v + 1)
                            .setCooldown(1, v + 1));
                    assertThat(r).isEqualTo(WorldPlanWriteResult.written(v + 1));
                }
                stop.set(true);
                return null;
            });
            Future<?> reader = pool.submit(() -> {
                while (!stop.get()) {
                    WorldPlanSnapshot snap = store.snapshot(zone);
                    long v = snap.version();
                    assertThat(snap.channels()).hasSize((int) v);
                    if (v > 0) {
                        assertThat(snap.desired().get(1)).isEqualTo((int) v);
                        assertThat(snap.cooldownUntilMs().get(1)).isEqualTo(v);
                        assertThat(snap.channels().values()).allSatisfy(c -> assertThat(c.getPlanVersion()).isLessThanOrEqualTo(v));
                    }
                    WorldPlan plan = store.readPlan(zone);
                    assertThat(plan.channels()).hasSize((int) plan.version());
                    checked.incrementAndGet();
                }
                return null;
            });
            writer.get(30, TimeUnit.SECONDS);
            reader.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(checked.get()).isPositive();
    }

    @Test
    void 非法_op_整批拒绝_一条也不写() {
        int zone = newZone();
        String token = leader(zone, "sm:" + zone);
        List<Object> keys = List.of(RedisKeys.worldLeader(zone), RedisKeys.worldChannels(zone), RedisKeys.worldDesired(zone),
                RedisKeys.worldCooldown(zone), RedisKeys.worldPlanVersion(zone));
        RScript script = redis.getScript(ByteArrayCodec.INSTANCE);
        assertThatThrownBy(() -> script.eval(RScript.Mode.READ_WRITE, RedissonWorldChannelStore.WRITE,
                RScript.ReturnType.INTEGER, keys, ascii(token), ascii("0"), ascii("1"),
                ascii("Q"), ascii("1"), ascii("4"),
                ascii("Z"), ascii("1"), ascii("")))
                .hasMessageContaining("unknown op");
        assertThatThrownBy(() -> script.eval(RScript.Mode.READ_WRITE, RedissonWorldChannelStore.WRITE,
                RScript.ReturnType.INTEGER, keys, ascii(token), ascii("0"), ascii("1"), ascii("Q"), ascii("1")))
                .hasMessageContaining("malformed");
        WorldPlanSnapshot snap = store.snapshot(zone);
        assertThat(snap.version()).isZero();
        assertThat(snap.desired()).isEmpty();
    }

    @Test
    void 坏记录让拉取与快照整体失败() {
        int zone = newZone();
        redis.<byte[], byte[]>getMap(RedisKeys.worldChannels(zone), ByteArrayCodec.INSTANCE)
                .fastPut(ascii("7"), channel(8, 1, 1, 0).toByteArray());
        assertThatThrownBy(() -> store.readPlan(zone)).isInstanceOf(IllegalStateException.class).hasMessageContaining("field=7");
        assertThatThrownBy(() -> store.snapshot(zone)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 领导锁_互斥_续期_放锁_到期后被接管_旧领导写入被拒() throws Exception {
        int zone = newZone();
        Duration ttl = Duration.ofMillis(800);
        WorldLeaderLock a = new WorldLeaderLock(store, zone, WorldLeaderLock.newToken("sm-a"), ttl);
        WorldLeaderLock b = new WorldLeaderLock(store, zone, WorldLeaderLock.newToken("sm-b"), ttl);
        assertThat(a.tryAcquire()).isTrue();
        assertThat(a.tryAcquire()).isTrue();                 // 已是本令牌：幂等续期
        assertThat(b.tryAcquire()).isFalse();
        assertThat(a.renew()).isEqualTo(WorldLeaderLock.RenewOutcome.RENEWED);
        long pttl = redis.getBucket(RedisKeys.worldLeader(zone), StringCodec.INSTANCE).remainTimeToLive();
        assertThat(pttl).isPositive().isLessThanOrEqualTo(800);
        assertThat(store.write(zone, a.token(), new WorldPlanBatch(0).seedDesired(1, 2)).isWritten()).isTrue();

        // a 停止续期：TTL 内 b 接管
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!b.tryAcquire()) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(50);
        }
        assertThat(b.isValid()).isTrue();
        assertThat(a.isValid()).isFalse();                    // 2/3 TTL 早已过去
        assertThat(store.write(zone, a.token(), new WorldPlanBatch(1).seedDesired(2, 2)))
                .isEqualTo(WorldPlanWriteResult.FENCED);
        assertThat(a.renew()).isEqualTo(WorldLeaderLock.RenewOutcome.LOST);
        assertThat(store.write(zone, b.token(), new WorldPlanBatch(1).seedDesired(2, 2)).isWritten()).isTrue();

        // 放锁只删自己的；b 放了之后 a 能重新当选
        a.release();
        assertThat(redis.getBucket(RedisKeys.worldLeader(zone), StringCodec.INSTANCE).get()).isEqualTo(b.token());
        b.release();
        assertThat(redis.getBucket(RedisKeys.worldLeader(zone), StringCodec.INSTANCE).isExists()).isFalse();
        assertThat(a.tryAcquire()).isTrue();
        a.release();
    }

    @Test
    void zone_集合_登记与读回() {
        int z1 = newZone();
        int z2 = newZone();
        store.registerZone(z2);
        store.registerZone(z1);
        store.registerZone(z1);
        List<Integer> zones = store.zones();
        assertThat(zones).contains(z1, z2);
        assertThat(zones).isSortedAccordingTo(Integer::compareUnsigned);
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}
