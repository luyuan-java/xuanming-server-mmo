package com.game.team.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline;
import com.game.common.player.PlayerProfiles.Profile;
import com.game.discovery.RedisKeys;
import com.game.discovery.battle.BattleLockReader;
import com.game.discovery.battle.BattleRedis;
import com.game.discovery.presence.PlayerPresenceDirectory;
import com.game.discovery.proto.PlayerPresence;
import com.game.team.view.MemberDisplay;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.config.Config;

/**
 * 队伍视图的 in_battle 在真 Redis 上（scene-battle-spec §2.4 队伍视图行、§7.13 世界内部第 4 条；§13.8 第 12 步的组件级版本）：
 * 锁由 scene 用的那几段脚本（{@link BattleRedis}：备战 / 确认 / 续锁 / 销账）真的写出与放掉，{@link TeamDisplay} 经生产用的
 * {@link BattleLockReader#existsAll} 与 {@link PlayerPresenceDirectory#findAllAsync} 读。锁是 Hash——MGET 对非 String 的键回 nil、不报错
 * （报 WRONGTYPE 的是 GET），照搬基线的 MGET 写法（{@code presence.go:92-97}：值非空即在战斗）会让 in_battle 静默恒为 false；
 * 这里钉住逐键 EXISTS 读得到，MGET 的这条前提也有一条用例实测。
 * 读失败 / 超时 / 同步抛出 / 被中断的各种形态在 {@link TeamDisplayTest} 用替身覆盖；这里只多测一条「Redis 客户端已关」的真失败。
 *
 * <p>默认跳过；显式开启：{@code -Dxm.it.redis=redis://127.0.0.1:6379}（DB 12，随机 ≥ 2^63 的 id，只删自己写的键）。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class TeamDisplayRedisIntegrationTest {

    private static final int DB = 12;

    private static RedissonClient redis;
    private static BattleRedis scripts;

    private final List<String> written = new ArrayList<>();
    private final long base = Long.MIN_VALUE + (1L << 54) + ThreadLocalRandom.current().nextLong(1L << 38) * 64;
    private final long battle = Long.MIN_VALUE + (1L << 55) + ThreadLocalRandom.current().nextLong(1L << 40);

    private static Config config() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(DB);
        return config;
    }

    @BeforeAll
    static void connect() {
        redis = Redisson.create(config());
        scripts = new BattleRedis(redis);
    }

    @AfterAll
    static void shutdown() {
        redis.shutdown();
    }

    @AfterEach
    void cleanup() {
        if (!written.isEmpty()) {
            redis.getKeys().delete(written.toArray(String[]::new));
        }
    }

    /** 第 n 名玩家；它可能被写到的键（锁、待结算记录、本局的已销账墓碑、在线条目）全部登记，测完只删这些。 */
    private long player(int n) {
        long pid = base + n;
        written.add(RedisKeys.battleLock(pid));
        written.add(RedisKeys.battleSettlements(pid));
        written.add(RedisKeys.battleSettled(pid, battle));
        written.add(RedisKeys.presence(pid));
        return pid;
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    private static TeamDisplay display(RedissonClient client, Map<Long, Profile> profiles) {
        return new TeamDisplay((ids, d) -> profiles, new PlayerPresenceDirectory(client)::findAllAsync,
                new BattleLockReader(client)::existsAll);
    }

    @Test
    void 备战写锁后in_battle为true_确认开战与结算待销账期间一直为true_销账放锁后变回false() throws Exception {
        long fighter = player(1);
        long teammate = player(2);
        long applicant = player(3);
        redis.<byte[]>getBucket(RedisKeys.presence(teammate), ByteArrayCodec.INSTANCE)
                .set(PlayerPresence.newBuilder().setPlayerId(teammate).setGateNodeId(1).build().toByteArray());
        TeamDisplay display = display(redis, Map.of(fighter, new Profile(fighter, "甲", 31, 2, 1, "ap", 3)));
        List<Long> roster = List.of(fighter, teammate, applicant);
        MemberDisplay teammateView = new MemberDisplay(true, false, 0, 0, "", "", 0);

        assertThat(display.load(roster, Deadline.after(5000))).as("没有锁").containsExactly(
                Map.entry(fighter, new MemberDisplay(false, false, 31, 2, "甲", "ap", 1)),
                Map.entry(teammate, teammateView),
                Map.entry(applicant, MemberDisplay.NONE));

        // 备战：scene 写锁（s = P）
        long now = System.currentTimeMillis();
        assertThat(await(scripts.prepareLock(fighter, battle, 3, now + 96_000, now + 30_000, 156))).isEqualTo("0");
        assertThat(display.load(roster, Deadline.after(5000))).as("备战中").containsExactly(
                Map.entry(fighter, new MemberDisplay(false, true, 31, 2, "甲", "ap", 1)),
                Map.entry(teammate, teammateView),
                Map.entry(applicant, MemberDisplay.NONE));

        // 确认开战（s = F）
        assertThat(await(scripts.confirm(fighter, battle, now + 600_000, 660))).containsEntry(BattleRedis.FIELD_STATE,
                BattleRedis.STATE_FIGHTING);
        assertThat(display.load(roster, Deadline.after(5000)).get(fighter).inBattle()).as("战斗中").isTrue();

        // 结算落库、scene 应用后续锁：记录在、锁还在
        assertThat(await(scripts.storeSettlement(fighter, battle, new byte[] {1, 2, 3}, BattleRedis.SETTLEMENT_TTL_SEC))).isEqualTo(1L);
        assertThat(await(scripts.hold(fighter, battle, BattleRedis.LOCK_HOLD_AFTER_APPLY_SEC))).isEqualTo(1L);
        assertThat(display.load(roster, Deadline.after(5000)).get(fighter).inBattle()).as("已结算待销账").isTrue();

        // 销账：删记录 + 放锁（位 1 | 位 2）
        assertThat(await(scripts.ack(fighter, battle))).isEqualTo(3L);
        assertThat(display.load(roster, Deadline.after(5000))).as("销账之后").containsExactly(
                Map.entry(fighter, new MemberDisplay(false, false, 31, 2, "甲", "ap", 1)),
                Map.entry(teammate, teammateView),
                Map.entry(applicant, MemberDisplay.NONE));
    }

    @Test
    void 锁是Hash_MGET读它不报错只回空_照搬基线会静默恒为false_逐键EXISTS才读得到() throws Exception {
        long fighter = player(1);
        long idle = player(2);
        long now = System.currentTimeMillis();
        assertThat(await(scripts.prepareLock(fighter, battle, 3, now + 96_000, now + 30_000, 156))).isEqualTo("0");
        String lockKey = RedisKeys.battleLock(fighter);
        assertThat(redis.getKeys().countExists(lockKey)).as("前提：锁确实在").isEqualTo(1L);

        // 基线的读法：一批键 MGET、值非空即在战斗。锁在、但 MGET 一个值也读不到，也不报错
        Map<String, byte[]> viaMget = redis.getBuckets(ByteArrayCodec.INSTANCE).get(lockKey, RedisKeys.battleLock(idle));
        assertThat(viaMget).as("MGET 对 Hash 键回 nil：有锁的人和没锁的人读出来一样").isEmpty();
        // 报错的是 GET；MGET 式的实现不会这样自己暴露
        assertThatThrownBy(() -> redis.<byte[]>getBucket(lockKey, ByteArrayCodec.INSTANCE).get()).hasMessageContaining("WRONGTYPE");

        Map<Long, MemberDisplay> dc = display(redis, Map.of()).load(List.of(fighter, idle), Deadline.after(5000));

        assertThat(dc).containsExactly(
                Map.entry(fighter, new MemberDisplay(false, true, 0, 0, "", "", 0)),
                Map.entry(idle, MemberDisplay.NONE));
    }

    @Test
    void 只有待结算记录或已销账墓碑_没有锁_in_battle为false() throws Exception {
        long recordOnly = player(1);
        long tombstoneOnly = player(2);
        // 锁已过期 / 已判废之后才落库的结算：只有记录
        assertThat(await(scripts.storeSettlement(recordOnly, battle, new byte[] {9}, BattleRedis.SETTLEMENT_TTL_SEC))).isEqualTo(1L);
        // 没有锁也没有记录时销账：只留下墓碑
        assertThat(await(scripts.ack(tombstoneOnly, battle))).isZero();
        assertThat(redis.getKeys().countExists(RedisKeys.battleSettlements(recordOnly), RedisKeys.battleSettled(tombstoneOnly, battle)))
                .as("前提：记录与墓碑确实都在").isEqualTo(2L);

        Map<Long, MemberDisplay> dc = display(redis, Map.of()).load(List.of(recordOnly, tombstoneOnly), Deadline.after(5000));

        assertThat(dc).containsExactly(Map.entry(recordOnly, MemberDisplay.NONE), Map.entry(tombstoneOnly, MemberDisplay.NONE));
    }

    @Test
    void 别的局的锁也算在战斗_in_battle只看锁在不在() throws Exception {
        long busy = player(1);
        long otherBattle = battle + 1;
        long now = System.currentTimeMillis();
        assertThat(await(scripts.prepareLock(busy, otherBattle, 4, now + 96_000, now + 30_000, 156))).isEqualTo("0");

        assertThat(display(redis, Map.of()).load(List.of(busy), Deadline.after(5000)).get(busy).inBattle()).isTrue();

        // 用本局的号销账放不掉别的局的锁（只写下本局的墓碑）：仍在战斗
        assertThat(await(scripts.ack(busy, battle))).isZero();
        assertThat(display(redis, Map.of()).load(List.of(busy), Deadline.after(5000)).get(busy).inBattle()).isTrue();
        assertThat(await(scripts.deleteIfMatch(busy, otherBattle))).as("reaper 判废删锁").isEqualTo(1L);
        assertThat(display(redis, Map.of()).load(List.of(busy), Deadline.after(5000)).get(busy).inBattle()).isFalse();
    }

    @Test
    void Redis客户端已关_视图照常返回_在线与in_battle按false_资料照常() throws Exception {
        long locked = player(1);
        long now = System.currentTimeMillis();
        assertThat(await(scripts.prepareLock(locked, battle, 3, now + 96_000, now + 30_000, 156))).isEqualTo("0");
        RedissonClient closed = Redisson.create(config());
        closed.shutdown();
        TeamDisplay display = display(closed, Map.of(locked, new Profile(locked, "甲", 31, 2, 1, "ap", 3)));

        Map<Long, MemberDisplay> dc = display.load(List.of(locked), Deadline.after(3000));

        assertThat(dc).as("锁其实在，但读不到 → 按 false；不抛出").containsExactly(
                Map.entry(locked, new MemberDisplay(false, false, 31, 2, "甲", "ap", 1)));
        // 同一把锁用活着的客户端读得到：上面的 false 来自读失败，不是锁不在
        assertThat(display(redis, Map.of()).load(List.of(locked), Deadline.after(5000)).get(locked).inBattle()).isTrue();
    }
}
