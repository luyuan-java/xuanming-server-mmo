package com.game.match.placement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline;
import com.game.discovery.RedisKeys;
import com.game.match.placement.PlacementStore.Read;
import com.game.match.proto.BattlePlacement;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.client.codec.StringCodec;
import org.redisson.codec.CompositeCodec;
import org.redisson.config.Config;

/**
 * 落点记录的 Redis 实现连真 Redis（缺省跳过：{@code -Dxm.it.redis=redis://127.0.0.1:6379}；match-spec §4.3、§15.3）：键与两个字段的形状、
 * <b>单调写</b>（attempt 小的迟到写盖不掉新写）、TTL 360 s 与同值补写刷新、可重放、无条件删除、三态读（损坏的记录是 Failed，不折成不存在）。
 * 用 DB 13、随机 battle_id，只删自己写的键。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class PlacementStoreIntegrationTest {

    private static RedissonClient redis;
    private final List<Long> used = new ArrayList<>();

    private static RedissonClient connect() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(13).setConnectionMinimumIdleSize(1).setConnectionPoolSize(4);
        return Redisson.create(config);
    }

    @BeforeAll
    static void open() {
        redis = connect();
    }

    @AfterAll
    static void close() {
        if (redis != null) {
            redis.shutdown();
        }
    }

    @AfterEach
    void cleanUp() {
        for (long battleId : used) {
            redis.getKeys().delete(RedisKeys.matchBattlePlacement(battleId));
        }
    }

    /** 一个随机的、高位为 1 的 battle_id（顺带验证无符号十进制的键）。 */
    private long newBattleId() {
        long battleId = ThreadLocalRandom.current().nextLong() | Long.MIN_VALUE;
        used.add(battleId);
        return battleId;
    }

    private static BattlePlacement placement(long battleId, int attempt, int nodeId, String instance) {
        return BattlePlacement.newBuilder().setBattleId(battleId).setBattleNodeId(nodeId).setBattleInstanceId(instance).setRpcHost("10.0.0." + nodeId)
                .setRpcPort(21200 + nodeId).setAttempt(attempt).setMode(3).setBattleConfigId(0).addPlayerNames("甲").addPlayerNames("乙")
                .setCreatedAtMs(1_800_000_001_234L).setDeadlineMs(1_800_000_300_000L).build();
    }

    private static Deadline d() {
        return Deadline.after(3_000);
    }

    private static RMap<String, byte[]> raw(long battleId) {
        return redis.getMap(RedisKeys.matchBattlePlacement(battleId), new CompositeCodec(StringCodec.INSTANCE, ByteArrayCodec.INSTANCE));
    }

    private static long ttlMs(long battleId) {
        return redis.getKeys().remainTimeToLive(RedisKeys.matchBattlePlacement(battleId));
    }

    private static BattlePlacement found(Read read) {
        assertThat(read).isInstanceOf(Read.Found.class);
        return ((Read.Found) read).placement();
    }

    // ================================================================ 写

    @Test
    void 首写_键是带tag的无符号十进制_HASH两个字段_读回逐字节相同_TTL是360秒() {
        RedissonPlacementStore store = new RedissonPlacementStore(redis);
        long battleId = newBattleId();
        BattlePlacement placement = placement(battleId, 1, 1, "inst-a");

        assertThat(store.write(placement)).isTrue();

        String key = RedisKeys.matchBattlePlacement(battleId);
        assertThat(key).isEqualTo("xm:{match}:battle:" + Long.toUnsignedString(battleId));
        assertThat(raw(battleId).readAllMap()).containsOnlyKeys("a", "pb");
        assertThat(new String(raw(battleId).get("a"), StandardCharsets.US_ASCII)).isEqualTo("1");
        assertThat(raw(battleId).get("pb")).isEqualTo(placement.toByteArray());
        assertThat(found(store.read(battleId, d()))).isEqualTo(placement);
        assertThat(ttlMs(battleId)).isBetween(350_000L, 360_000L);
    }

    @Test
    void 单调写_attempt小的迟到写盖不掉换节点之后的新写_仍回true() {
        RedissonPlacementStore store = new RedissonPlacementStore(redis);
        long battleId = newBattleId();
        BattlePlacement first = placement(battleId, 1, 1, "inst-a");
        BattlePlacement rewritten = placement(battleId, 2, 2, "inst-b");
        assertThat(store.write(rewritten)).isTrue();

        // 首写在换节点改写之后才落盘（超时后迟到）
        boolean late = store.write(first);

        assertThat(late).as("记录存在且 attempt ≥ 本次的").isTrue();
        BattlePlacement stored = found(store.read(battleId, d()));
        assertThat(stored).isEqualTo(rewritten);
        assertThat(stored.getBattleNodeId()).as("补签仍被导向实际建房的节点").isEqualTo(2);
        assertThat(new String(raw(battleId).get("a"), StandardCharsets.US_ASCII)).isEqualTo("2");
    }

    @Test
    void 被丢弃的迟到写不刷新TTL_不续别人的命() {
        RedissonPlacementStore store = new RedissonPlacementStore(redis);
        long battleId = newBattleId();
        store.write(placement(battleId, 2, 2, "inst-b"));
        raw(battleId).expire(Duration.ofSeconds(20));

        store.write(placement(battleId, 1, 1, "inst-a"));

        assertThat(ttlMs(battleId)).isBetween(10_000L, 20_000L);
    }

    @Test
    void attempt更大的写覆盖旧值_换节点改写() {
        RedissonPlacementStore store = new RedissonPlacementStore(redis);
        long battleId = newBattleId();
        store.write(placement(battleId, 1, 1, "inst-a"));

        assertThat(store.write(placement(battleId, 2, 2, "inst-b"))).isTrue();

        assertThat(found(store.read(battleId, d())).getBattleInstanceId()).isEqualTo("inst-b");
    }

    @Test
    void 同值补写_内容不变_TTL刷回360秒_写两次与写一次结果相同() {
        RedissonPlacementStore store = new RedissonPlacementStore(redis);
        long battleId = newBattleId();
        BattlePlacement placement = placement(battleId, 2, 2, "inst-b");
        store.write(placement);
        raw(battleId).expire(Duration.ofSeconds(20));
        assertThat(ttlMs(battleId)).isLessThanOrEqualTo(20_000L);

        assertThat(store.write(placement)).isTrue();
        assertThat(store.write(placement)).as("Redis 客户端重发同一段脚本也无害").isTrue();

        assertThat(found(store.read(battleId, d()))).isEqualTo(placement);
        assertThat(raw(battleId).readAllMap()).containsOnlyKeys("a", "pb");
        assertThat(ttlMs(battleId)).isBetween(350_000L, 360_000L);
    }

    @Test
    void 已存的attempt不是数字_按没有处理_直接覆盖() {
        RedissonPlacementStore store = new RedissonPlacementStore(redis);
        long battleId = newBattleId();
        raw(battleId).put("a", "乱码".getBytes(StandardCharsets.UTF_8));
        BattlePlacement placement = placement(battleId, 1, 1, "inst-a");

        assertThat(store.write(placement)).isTrue();

        assertThat(found(store.read(battleId, d()))).isEqualTo(placement);
    }

    @Test
    void 入参不合法是调用方的bug_直接抛() {
        RedissonPlacementStore store = new RedissonPlacementStore(redis);

        assertThatThrownBy(() -> store.write(BattlePlacement.newBuilder().setAttempt(1).build())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.write(BattlePlacement.newBuilder().setBattleId(5).build())).isInstanceOf(IllegalArgumentException.class);
    }

    // ================================================================ 读

    @Test
    void 没有记录_Absent() {
        RedissonPlacementStore store = new RedissonPlacementStore(redis);

        assertThat(store.read(newBattleId(), d())).isInstanceOf(Read.Absent.class);
    }

    @Test
    void 损坏的记录是Failed_不折成不存在_缺pb_pb解析不了_与键不符_键类型不对() {
        RedissonPlacementStore store = new RedissonPlacementStore(redis);
        long missingPb = newBattleId();
        raw(missingPb).put("a", "1".getBytes(StandardCharsets.US_ASCII));
        long garbage = newBattleId();
        raw(garbage).put("a", "1".getBytes(StandardCharsets.US_ASCII));
        raw(garbage).put("pb", new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
        long mismatched = newBattleId();
        raw(mismatched).put("a", "1".getBytes(StandardCharsets.US_ASCII));
        raw(mismatched).put("pb", placement(12345, 1, 1, "inst-a").toByteArray());
        long wrongType = newBattleId();
        redis.getBucket(RedisKeys.matchBattlePlacement(wrongType), StringCodec.INSTANCE).set("不是 HASH");

        for (long battleId : new long[] {missingPb, garbage, mismatched, wrongType}) {
            Read read = store.read(battleId, d());

            assertThat(read).as("battle_id %s", Long.toUnsignedString(battleId)).isInstanceOf(Read.Failed.class);
            assertThat(((Read.Failed) read).why()).isNotBlank();
        }
    }

    // ================================================================ 删

    @Test
    void 删除无条件_删完读不到_删不存在的不抛_删了之后迟到的写会复活记录() {
        RedissonPlacementStore store = new RedissonPlacementStore(redis);
        long battleId = newBattleId();
        BattlePlacement placement = placement(battleId, 2, 2, "inst-b");
        store.write(placement);

        store.delete(battleId);
        store.delete(battleId);
        store.delete(newBattleId());

        assertThat(store.read(battleId, d())).isInstanceOf(Read.Absent.class);
        assertThat(raw(battleId).isExists()).isFalse();
        // 已知且无害：超时那次迟到落盘把记录「复活」，补签会直拨到 battle、由 battle 回房间不存在，记录随 TTL 自清
        assertThat(store.write(placement(battleId, 1, 1, "inst-a"))).isTrue();
        assertThat(found(store.read(battleId, d())).getAttempt()).isEqualTo(1);
        assertThat(ttlMs(battleId)).isBetween(350_000L, 360_000L);
    }

    // ================================================================ 故障与线程

    @Test
    void Redis客户端不可用_写回false_读是Failed_删不抛_都不折成成功或不存在() {
        RedissonClient closed = connect();
        RedissonPlacementStore store = new RedissonPlacementStore(closed, 1_500);
        long battleId = newBattleId();
        assertThat(store.write(placement(battleId, 1, 1, "inst-a"))).isTrue();
        closed.shutdown();

        long started = System.nanoTime();
        boolean written = store.write(placement(battleId, 2, 2, "inst-b"));
        Read read = store.read(battleId, Deadline.after(1_500));
        store.delete(battleId);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(written).isFalse();
        assertThat(read).isInstanceOf(Read.Failed.class);
        assertThat(elapsedMs).as("各自在自己的截止附近返回").isLessThan(10_000);
        assertThat(found(new RedissonPlacementStore(redis).read(battleId, d())).getAttempt()).as("失败的写没有落盘").isEqualTo(1);
    }

    @Test
    void 在虚拟线程上读写删_同gather的用法() throws Exception {
        RedissonPlacementStore store = new RedissonPlacementStore(redis);
        long battleId = newBattleId();
        BattlePlacement placement = placement(battleId, 1, 1, "inst-a");
        AtomicReference<Object> outcome = new AtomicReference<>();

        Thread thread = Thread.ofVirtual().name("test-placement").start(() -> {
            try {
                boolean written = store.write(placement);
                Read read = store.read(battleId, d());
                store.delete(battleId);
                Read after = store.read(battleId, d());
                outcome.set(List.of(written, read, after));
            } catch (Throwable t) {
                outcome.set(t);
            }
        });

        assertThat(thread.join(Duration.ofSeconds(30))).isTrue();
        assertThat(outcome.get()).isInstanceOf(List.class);
        List<?> results = (List<?>) outcome.get();
        assertThat(results.get(0)).isEqualTo(true);
        assertThat(results.get(1)).isEqualTo(new Read.Found(placement));
        assertThat(results.get(2)).isInstanceOf(Read.Absent.class);
    }
}
