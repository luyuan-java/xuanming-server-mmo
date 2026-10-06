package com.game.discovery.battle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.discovery.RedisKeys;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

/**
 * {@link BattleLockReader}（match / team / 组队跟随 / 以后的 guild 用的只读工具）的真 Redis 集成测试（scene-battle-spec §2.4、§13.4 末条）：
 * EXISTS、读 b、批量 EXISTS（去重、保持入参顺序），以及「锁由 scene 的脚本写出 / 放掉之后读者看到什么」。
 * 默认跳过；显式开启：{@code -Dxm.it.redis=redis://127.0.0.1:6379}。DB 15、随机大号 id，只删自己写的键。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class BattleLockReaderIntegrationTest {

    private static final long PLAYER_BASE = Long.MIN_VALUE + (1L << 54) + ThreadLocalRandom.current().nextLong(1L << 40);
    private static final long BATTLE = Long.MIN_VALUE + (1L << 55) + ThreadLocalRandom.current().nextLong(1L << 40);
    private static final AtomicLong playerSeq = new AtomicLong();
    private static final List<String> written = new ArrayList<>();

    private static RedissonClient redis;
    private static BattleRedis scripts;
    private static BattleLockReader reader;

    @BeforeAll
    static void connect() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(BattleRedisScriptsIntegrationTest.DB);
        redis = Redisson.create(config);
        scripts = new BattleRedis(redis);
        reader = new BattleLockReader(redis);
    }

    @AfterAll
    static void cleanup() {
        if (!written.isEmpty()) {
            redis.getKeys().delete(written.toArray(String[]::new));
        }
        redis.shutdown();
    }

    private static long newPlayer() {
        long player = PLAYER_BASE + playerSeq.incrementAndGet();
        written.add(RedisKeys.battleLock(player));
        written.add(RedisKeys.battleSettlements(player));
        written.add(RedisKeys.battleSettled(player, BATTLE));
        return player;
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    private static void prepare(long player) throws Exception {
        assertThat(await(scripts.prepareLock(player, BATTLE, 3, 96_000, 30_000, 156))).isEqualTo("0");
    }

    @Test
    void 没有锁_exists为false_battleId为0() throws Exception {
        long player = newPlayer();

        assertThat(await(reader.exists(player))).isFalse();
        assertThat(await(reader.battleId(player))).isZero();
    }

    @Test
    void 备战锁与战斗中的锁都算在途_battleId按无符号读出() throws Exception {
        long player = newPlayer();
        prepare(player);

        assertThat(await(reader.exists(player))).isTrue();                                  // 备战中（P）
        assertThat(await(reader.battleId(player))).isEqualTo(BATTLE).isNegative();

        assertThat(await(scripts.confirm(player, BATTLE, 600_000, 660))).isNotNull();
        assertThat(await(reader.exists(player))).isTrue();                                  // 战斗中（F）
        assertThat(await(reader.battleId(player))).isEqualTo(BATTLE);
    }

    @Test
    void 结算已应用待落盘时锁仍在_销账放锁后读者看到不在() throws Exception {
        long player = newPlayer();
        prepare(player);
        assertThat(await(scripts.confirm(player, BATTLE, 600_000, 660))).isNotNull();
        assertThat(await(scripts.storeSettlement(player, BATTLE, new byte[] {1}, BattleRedis.SETTLEMENT_TTL_SEC))).isEqualTo(1L);
        assertThat(await(scripts.hold(player, BATTLE, BattleRedis.LOCK_HOLD_AFTER_APPLY_SEC))).isEqualTo(1L);
        assertThat(await(reader.exists(player))).isTrue();   // 已结算待落盘：仍算在途（§2.4）

        assertThat(await(scripts.ack(player, BATTLE))).isEqualTo(3L);

        assertThat(await(reader.exists(player))).isFalse();
        assertThat(await(reader.battleId(player))).isZero();
        // 墓碑与待结算记录不是锁：销账后留下的墓碑不会让读者误判在途
        assertThat(redis.getKeys().countExists(RedisKeys.battleSettled(player, BATTLE))).isEqualTo(1);
    }

    @Test
    void 只有待结算记录没有锁_不算在途() throws Exception {
        long player = newPlayer();
        assertThat(await(scripts.storeSettlement(player, BATTLE, new byte[] {1}, BattleRedis.SETTLEMENT_TTL_SEC))).isEqualTo(1L);

        assertThat(await(reader.exists(player))).isFalse();
        assertThat(await(reader.existsAll(List.of(player)))).containsExactly(Map.entry(player, false));
    }

    @Test
    void 取消备战删锁后_读者看到不在() throws Exception {
        long player = newPlayer();
        prepare(player);

        assertThat(await(scripts.deletePreparingIfMatch(player, BATTLE))).isEqualTo(BattleRedis.PREPARING_DELETE_DONE);

        assertThat(await(reader.exists(player))).isFalse();
    }

    @Test
    void 批量_保持入参顺序_重复的只读一次_有锁无锁各自正确() throws Exception {
        long locked = newPlayer();
        long free = newPlayer();
        long fighting = newPlayer();
        prepare(locked);
        prepare(fighting);
        assertThat(await(scripts.confirm(fighting, BATTLE, 600_000, 660))).isNotNull();

        Map<Long, Boolean> result = await(reader.existsAll(List.of(fighting, free, locked, free, fighting)));

        // 入参顺序去重后是 fighting, free, locked（不按数值排序：三个号是递增分配的，fighting 最大却排第一）
        assertThat(new ArrayList<>(result.keySet())).containsExactly(fighting, free, locked);
        assertThat(result).containsExactly(Map.entry(fighting, true), Map.entry(free, false), Map.entry(locked, true));
    }

    @Test
    void 批量_空入参得到空表() throws Exception {
        assertThat(await(reader.existsAll(List.of()))).isEmpty();
    }

    @Test
    void 锁键被写成别的类型_exists仍为true_读b异常完成() throws Exception {
        // EXISTS 不看类型：键在就按在途（咨询性读，宁可多挡）；读 b 走 HGET，类型不对以异常完成，不会被当成「没有锁」
        long player = newPlayer();
        redis.getBucket(RedisKeys.battleLock(player), StringCodec.INSTANCE).set("not-a-hash");

        assertThat(await(reader.exists(player))).isTrue();
        assertThatThrownBy(() -> await(reader.battleId(player))).isInstanceOf(ExecutionException.class);
    }

    @Test
    void Redis不可用_三个方法都异常完成_不同步抛出() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(BattleRedisScriptsIntegrationTest.DB);
        RedissonClient closed = Redisson.create(config);
        closed.shutdown();
        BattleLockReader dead = new BattleLockReader(closed);

        CompletableFuture<Boolean> exists = dead.exists(PLAYER_BASE);
        CompletableFuture<Long> battleId = dead.battleId(PLAYER_BASE);
        CompletableFuture<Map<Long, Boolean>> all = dead.existsAll(List.of(PLAYER_BASE, PLAYER_BASE + 1));

        assertThatThrownBy(() -> exists.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
        assertThatThrownBy(() -> battleId.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
        assertThatThrownBy(() -> all.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
    }
}
