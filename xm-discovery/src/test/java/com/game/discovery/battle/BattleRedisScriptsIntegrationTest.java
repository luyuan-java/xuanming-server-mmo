package com.game.discovery.battle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.discovery.RedisKeys;
import com.game.discovery.battle.BattleRedis.EnterRead;
import com.game.discovery.battle.BattleRedis.SettlementField;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.client.codec.StringCodec;
import org.redisson.codec.CompositeCodec;
import org.redisson.config.Config;

/**
 * {@link BattleRedis} 每段脚本的真 Redis 真值表与重放语义（scene-battle-spec §7.2、§10.1 第 13 条、§13.4；审计 RDS-1）。
 * <b>每段可变脚本都连跑两次</b>：Redisson {@code retryAttempts = 1} 会把超时的 EVAL 原样重发，第二次的返回与副作用必须符合类注释里的重放结论。
 * 另外钉住三项补充：已销账墓碑（销账写、落库读，RDS-6 / OBX-7）、{@code TOUCH} 单调（RDS-7 / FRZ-5）、只删备战锁（FRZ-7）。
 *
 * <p>默认跳过；显式开启：{@code -Dxm.it.redis=redis://127.0.0.1:6379}。用 DB 15 与随机大号 id（玩家号、战斗号都 ≥ 2^63），每个用例一个新玩家号，
 * 只删自己写的键。类名避开 xm-battle 的 {@code BattleRedisIntegrationTest}（那是 6.2 的节点租约 / 目录测试）。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class BattleRedisScriptsIntegrationTest {

    static final int DB = 15;

    /** 玩家号与战斗号都取高位为 1（≥ 2^63）的值：键名、Lua 里的等值比较、解析都必须按无符号十进制。 */
    private static final long PLAYER_BASE = Long.MIN_VALUE + (1L << 52) + ThreadLocalRandom.current().nextLong(1L << 40);
    private static final long BATTLE_BASE = Long.MIN_VALUE + (1L << 53) + ThreadLocalRandom.current().nextLong(1L << 40);
    /** 本局 X 与别的局 Y（Y 更新）。 */
    private static final long X = BATTLE_BASE + 1;
    private static final long Y = BATTLE_BASE + 2;
    private static final String XS = Long.toUnsignedString(X);
    private static final String YS = Long.toUnsignedString(Y);

    private static final AtomicLong playerSeq = new AtomicLong();
    private static final Set<String> written = ConcurrentHashMap.newKeySet();

    private static RedissonClient redis;
    private static BattleRedis scripts;

    @BeforeAll
    static void connect() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(DB);
        redis = Redisson.create(config);
        scripts = new BattleRedis(redis);
    }

    @AfterAll
    static void cleanup() {
        if (!written.isEmpty()) {
            redis.getKeys().delete(written.toArray(String[]::new));
        }
        redis.shutdown();
    }

    // ------------------------------------------------------------------ 夹具

    /** 一个新玩家号；它的锁、记录、X / Y 两局的墓碑都登记进清理名单。 */
    private static long newPlayer() {
        long player = PLAYER_BASE + playerSeq.incrementAndGet();
        written.add(RedisKeys.battleLock(player));
        written.add(RedisKeys.battleSettlements(player));
        written.add(RedisKeys.battleSettled(player, X));
        written.add(RedisKeys.battleSettled(player, Y));
        return player;
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    private static RMap<String, String> lockMap(long player) {
        return redis.getMap(RedisKeys.battleLock(player), StringCodec.INSTANCE);
    }

    private static Map<String, String> lock(long player) {
        return lockMap(player).readAllMap();
    }

    /** 直接写一把锁（不经被测脚本）：b n s d p + TTL（{@code ttlSec ≤ 0} = 不设过期）。 */
    private static void seedLock(long player, long battle, String state, long deadline, long prepareDeadline, long ttlSec) {
        RMap<String, String> map = lockMap(player);
        map.delete();
        map.putAll(lockFields(battle, "7", state, deadline, prepareDeadline));
        if (ttlSec > 0) {
            map.expire(Duration.ofSeconds(ttlSec));
        }
    }

    private static Map<String, String> lockFields(long battle, String node, String state, long deadline, long prepareDeadline) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("b", Long.toUnsignedString(battle));
        fields.put("n", node);
        fields.put("s", state);
        fields.put("d", Long.toUnsignedString(deadline));
        fields.put("p", Long.toUnsignedString(prepareDeadline));
        return fields;
    }

    private static RMap<String, byte[]> settlementMap(long player) {
        return redis.getMap(RedisKeys.battleSettlements(player), new CompositeCodec(StringCodec.INSTANCE, ByteArrayCodec.INSTANCE));
    }

    /** 直接写一条待结算记录（不经被测脚本）。 */
    private static void seedSettlement(long player, long battle, byte[] payload) {
        settlementMap(player).fastPut(Long.toUnsignedString(battle), payload);
    }

    /** 直接按原始字节写一个字段（坏字段名用）。 */
    private static void seedRawField(long player, byte[] rawName, byte[] value) {
        redis.getScript(ByteArrayCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, "return redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])",
                RScript.ReturnType.INTEGER, List.<Object>of(RedisKeys.battleSettlements(player)), rawName, value);
    }

    private static long ttlMs(String key) {
        return redis.getKeys().remainTimeToLive(key);
    }

    /** TTL 刚被设成 {@code expectedSec}（允许用例自身耗掉几秒）。 */
    private static void assertTtlSec(String key, long expectedSec) {
        assertThat(ttlMs(key)).as("%s 的 TTL（毫秒）应约为 %d s", key, expectedSec)
                .isBetween((expectedSec - 10) * 1000, expectedSec * 1000);
    }

    private static boolean exists(String key) {
        return redis.getKeys().countExists(key) == 1;
    }

    private static String tomb(long player, long battle) {
        return RedisKeys.battleSettled(player, battle);
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    /** 同一次调用连发两遍（模拟 Redisson 的原样重发），返回两次的结果。 */
    private static <T> List<T> twice(Supplier<CompletableFuture<T>> call) throws Exception {
        T first = await(call.get());
        T second = await(call.get());
        return java.util.Arrays.asList(first, second);
    }

    // ================================================================== PREPARE_LOCK

    @Test
    void 备战写锁_空键_写入五个字段与TTL_重放仍回0且不变() throws Exception {
        long player = newPlayer();
        int node = 0x8000_0001;   // 节点号按无符号写

        List<String> results = twice(() -> scripts.prepareLock(player, X, node, 1_760_000_096_000L, 1_760_000_030_000L, 156));

        assertThat(results).containsExactly("0", "0");
        assertThat(lock(player)).isEqualTo(lockFields(X, "2147483649", "P", 1_760_000_096_000L, 1_760_000_030_000L));
        assertTtlSec(RedisKeys.battleLock(player), 156);
    }

    @Test
    void 备战写锁_同局仍是P_按重放成功_重写n_d_p并续TTL() throws Exception {
        long player = newPlayer();
        seedLock(player, X, "P", 1000, 900, 50);

        String held = await(scripts.prepareLock(player, X, 8, 2000, 1900, 500));

        assertThat(held).isEqualTo("0");
        assertThat(lock(player)).isEqualTo(lockFields(X, "8", "P", 2000, 1900));
        assertTtlSec(RedisKeys.battleLock(player), 500);
    }

    @Test
    void 备战写锁_同局已F_拒绝回现b_字段与TTL都不变() throws Exception {
        long player = newPlayer();
        seedLock(player, X, "F", 1000, 900, 1000);

        List<String> results = twice(() -> scripts.prepareLock(player, X, 8, 2000, 1900, 50));

        assertThat(results).containsExactly(XS, XS);
        assertThat(lock(player)).isEqualTo(lockFields(X, "7", "F", 1000, 900));
        assertTtlSec(RedisKeys.battleLock(player), 1000);   // 没被改成 50：在打的局不会被改回备战期 TTL
    }

    @Test
    void 备战写锁_别的局占着_拒绝回现b_不动别人的锁() throws Exception {
        long player = newPlayer();
        seedLock(player, Y, "P", 1000, 900, 1000);

        List<String> results = twice(() -> scripts.prepareLock(player, X, 8, 2000, 1900, 50));

        assertThat(results).containsExactly(YS, YS);
        assertThat(lock(player)).isEqualTo(lockFields(Y, "7", "P", 1000, 900));
        assertTtlSec(RedisKeys.battleLock(player), 1000);
    }

    /**
     * 脚本判的是「键里没有 b」（{@code HGET} 回 nil），不是「键不存在」：键在而缺 b（残缺的锁）同样按空键写入——五个字段都写
     * （s 一律写成 P）、键上别的字段保留、TTL 换成新的，回 {@code "0"}。scene 测试用的假实现（{@code FakeBattleLocks}）照这一条执行。
     */
    @Test
    void 备战写锁_键在但没有b_按空键写入_s写成P_别的字段保留_TTL换成新的() throws Exception {
        long player = newPlayer();
        RMap<String, String> residual = lockMap(player);
        residual.putAll(Map.of("n", "5", "s", "F", "zz", "keep"));
        residual.expire(Duration.ofSeconds(1000));

        List<String> results = twice(() -> scripts.prepareLock(player, X, 8, 2000, 1900, 50));

        assertThat(results).as("第一遍写入；第二遍是同局仍 P 的重放").containsExactly("0", "0");
        Map<String, String> expected = lockFields(X, "8", "P", 2000, 1900);
        expected.put("zz", "keep");
        assertThat(lock(player)).isEqualTo(expected);
        assertTtlSec(RedisKeys.battleLock(player), 50);
    }

    // ================================================================== CONFIRM

    @Test
    void 确认_命中_标F写d续期_回全部字段_重放结果相同() throws Exception {
        long player = newPlayer();
        seedLock(player, X, "P", 1000, 900, 50);

        List<Map<String, String>> results = twice(() -> scripts.confirm(player, X, 2000, 500));

        Map<String, String> expected = lockFields(X, "7", "F", 2000, 900);
        assertThat(results.get(0)).isEqualTo(expected);
        assertThat(results.get(1)).isEqualTo(expected);
        assertThat(lock(player)).isEqualTo(expected);
        assertTtlSec(RedisKeys.battleLock(player), 500);
    }

    @Test
    void 确认_d与ttl为0_只标F_不改d不动TTL() throws Exception {
        long player = newPlayer();
        seedLock(player, X, "P", 1000, 900, 1000);

        Map<String, String> fields = await(scripts.confirm(player, X, 0, 0));

        assertThat(fields).isEqualTo(lockFields(X, "7", "F", 1000, 900));
        assertThat(lock(player)).isEqualTo(lockFields(X, "7", "F", 1000, 900));
        assertTtlSec(RedisKeys.battleLock(player), 1000);   // 仍带原来的 TTL：既没被续成别的值，也没被抹成永不过期
    }

    @Test
    void 确认_d与ttl各自独立生效() throws Exception {
        long onlyTtl = newPlayer();
        seedLock(onlyTtl, X, "P", 1000, 900, 1000);
        assertThat(await(scripts.confirm(onlyTtl, X, 0, 300))).isEqualTo(lockFields(X, "7", "F", 1000, 900));
        assertTtlSec(RedisKeys.battleLock(onlyTtl), 300);

        long onlyDeadline = newPlayer();
        seedLock(onlyDeadline, X, "P", 1000, 900, 1000);
        assertThat(await(scripts.confirm(onlyDeadline, X, 2000, 0))).isEqualTo(lockFields(X, "7", "F", 2000, 900));
        assertTtlSec(RedisKeys.battleLock(onlyDeadline), 1000);
    }

    @Test
    void 确认_未命中得到null而不是空表_不创建键也不动别的局() throws Exception {
        // 疑点（RDS-1）：未命中时脚本回 nil；若封装给出空表，scene 的迟到确认会把不存在的锁当成命中而挂 FIGHTING 冻结
        long absent = newPlayer();
        List<Map<String, String>> none = twice(() -> scripts.confirm(absent, X, 2000, 500));
        assertThat(none.get(0)).isNull();
        assertThat(none.get(1)).isNull();
        assertThat(exists(RedisKeys.battleLock(absent))).isFalse();

        long other = newPlayer();
        seedLock(other, Y, "P", 1000, 900, 1000);
        assertThat(await(scripts.confirm(other, X, 2000, 50))).isNull();
        assertThat(lock(other)).isEqualTo(lockFields(Y, "7", "P", 1000, 900));
        assertTtlSec(RedisKeys.battleLock(other), 1000);
    }

    // ================================================================== CANCEL_OFFLINE / 只删备战锁

    /** 离线取消与「只删备战锁」是同一段脚本的两个入口，真值表相同。 */
    private interface PreparingDelete {
        CompletableFuture<Long> call(long player, long battle);
    }

    private static void 只删备战锁的真值表(PreparingDelete delete) throws Exception {
        // 是本局、仍是 P → 删、回 1；重放回 0
        long preparing = newPlayer();
        seedLock(preparing, X, "P", 1000, 900, 1000);
        assertThat(twice(() -> delete.call(preparing, X)))
                .containsExactly(BattleRedis.PREPARING_DELETE_DONE, BattleRedis.PREPARING_DELETE_MISS);
        assertThat(exists(RedisKeys.battleLock(preparing))).isFalse();

        // 是本局、已 F → 拒绝回 2，锁的字段与 TTL 都不变；重放仍回 2
        long fighting = newPlayer();
        seedLock(fighting, X, "F", 2000, 900, 1000);
        assertThat(twice(() -> delete.call(fighting, X)))
                .containsExactly(BattleRedis.PREPARING_DELETE_FIGHTING, BattleRedis.PREPARING_DELETE_FIGHTING);
        assertThat(lock(fighting)).isEqualTo(lockFields(X, "7", "F", 2000, 900));
        assertTtlSec(RedisKeys.battleLock(fighting), 1000);

        // 别的局 → 0，不误删
        long other = newPlayer();
        seedLock(other, Y, "P", 1000, 900, 1000);
        assertThat(twice(() -> delete.call(other, X))).containsExactly(BattleRedis.PREPARING_DELETE_MISS, BattleRedis.PREPARING_DELETE_MISS);
        assertThat(lock(other)).isEqualTo(lockFields(Y, "7", "P", 1000, 900));

        // 没有锁 → 0，不创建键
        long absent = newPlayer();
        assertThat(await(delete.call(absent, X))).isEqualTo(BattleRedis.PREPARING_DELETE_MISS);
        assertThat(exists(RedisKeys.battleLock(absent))).isFalse();
    }

    @Test
    void 离线取消_P删回1_F拒绝回2_不符回0_重放回0() throws Exception {
        只删备战锁的真值表(scripts::cancelOffline);
    }

    @Test
    void 只删备战锁_P删回1_F不删回2_不符回0_重放回0() throws Exception {
        只删备战锁的真值表(scripts::deletePreparingIfMatch);
    }

    @Test
    void 只删备战锁_排到确认之后_这一局已标F_删不掉() throws Exception {
        // FRZ-7 的时序：取消的删锁被 Redisson 重排 / 重发到 CONFIRM 之后。锁必须留着，与迟到确认重建出的 FIGHTING 冻结一致
        long player = newPlayer();
        assertThat(await(scripts.prepareLock(player, X, 7, 1000, 900, 100))).isEqualTo("0");
        assertThat(await(scripts.confirm(player, X, 2000, 500))).containsEntry("s", "F");

        assertThat(await(scripts.deletePreparingIfMatch(player, X))).isEqualTo(BattleRedis.PREPARING_DELETE_FIGHTING);

        assertThat(lock(player)).isEqualTo(lockFields(X, "7", "F", 2000, 900));
        assertTtlSec(RedisKeys.battleLock(player), 500);
    }

    // ================================================================== DELETE_IF_MATCH

    @Test
    void 条件删锁_不误删别的局_本局不看阶段都删_重放回0() throws Exception {
        long other = newPlayer();
        seedLock(other, Y, "P", 1000, 900, 1000);
        assertThat(twice(() -> scripts.deleteIfMatch(other, X))).containsExactly(0L, 0L);
        assertThat(lock(other)).isEqualTo(lockFields(Y, "7", "P", 1000, 900));
        assertTtlSec(RedisKeys.battleLock(other), 1000);

        long preparing = newPlayer();
        seedLock(preparing, X, "P", 1000, 900, 1000);
        assertThat(twice(() -> scripts.deleteIfMatch(preparing, X))).containsExactly(1L, 0L);
        assertThat(exists(RedisKeys.battleLock(preparing))).isFalse();

        // 与「只删备战锁」的区别：F 也删（reaper 判废用）
        long fighting = newPlayer();
        seedLock(fighting, X, "F", 1000, 900, 1000);
        assertThat(twice(() -> scripts.deleteIfMatch(fighting, X))).containsExactly(1L, 0L);
        assertThat(exists(RedisKeys.battleLock(fighting))).isFalse();

        long absent = newPlayer();
        assertThat(await(scripts.deleteIfMatch(absent, X))).isZero();
        // 删锁不写墓碑：判废不是销账，之后这一局的结算照样能落库
        assertThat(exists(tomb(preparing, X))).isFalse();
        assertThat(exists(tomb(fighting, X))).isFalse();
    }

    // ================================================================== TOUCH

    @Test
    void 复核_不命中回0_不创建键也不动别的局() throws Exception {
        long absent = newPlayer();
        assertThat(twice(() -> scripts.touch(absent, X, 500, "F", 2000, 900))).containsExactly(BattleRedis.TOUCH_MISS, BattleRedis.TOUCH_MISS);
        assertThat(exists(RedisKeys.battleLock(absent))).isFalse();

        long other = newPlayer();
        seedLock(other, Y, "P", 1000, 900, 1000);
        assertThat(await(scripts.touch(other, X, 50, "F", 2000, 1900))).isEqualTo(BattleRedis.TOUCH_MISS);
        assertThat(lock(other)).isEqualTo(lockFields(Y, "7", "P", 1000, 900));
        assertTtlSec(RedisKeys.battleLock(other), 1000);
    }

    @Test
    void 复核_命中_写s_d_p并设TTL_b与n不动_重放幂等() throws Exception {
        long preparing = newPlayer();
        seedLock(preparing, X, "P", 1000, 900, 50);
        assertThat(twice(() -> scripts.touch(preparing, X, 500, "P", 2000, 1900))).containsExactly(BattleRedis.TOUCH_HIT, BattleRedis.TOUCH_HIT);
        assertThat(lock(preparing)).isEqualTo(lockFields(X, "7", "P", 2000, 1900));
        assertTtlSec(RedisKeys.battleLock(preparing), 500);

        // P 锁上 TOUCH(F)：照写（升级方向不受限）
        long upgraded = newPlayer();
        seedLock(upgraded, X, "P", 1000, 900, 50);
        assertThat(await(scripts.touch(upgraded, X, 500, "F", 2000, 900))).isEqualTo(BattleRedis.TOUCH_HIT);
        assertThat(lock(upgraded)).isEqualTo(lockFields(X, "7", "F", 2000, 900));
        assertTtlSec(RedisKeys.battleLock(upgraded), 500);

        // F 锁上 TOUCH(F)：照写
        long fighting = newPlayer();
        seedLock(fighting, X, "F", 1000, 900, 1000);
        assertThat(twice(() -> scripts.touch(fighting, X, 300, "F", 3000, 900))).containsExactly(BattleRedis.TOUCH_HIT, BattleRedis.TOUCH_HIT);
        assertThat(lock(fighting)).isEqualTo(lockFields(X, "7", "F", 3000, 900));
        assertTtlSec(RedisKeys.battleLock(fighting), 300);
    }

    @Test
    void 复核_锁上已F而入参是P_回2_s_d_p与TTL都不变_重放仍回2() throws Exception {
        long player = newPlayer();
        seedLock(player, X, "F", 2000, 900, 1000);

        List<Long> results = twice(() -> scripts.touch(player, X, 50, "P", 1000, 800));

        assertThat(results).containsExactly(BattleRedis.TOUCH_KEPT_FIGHTING, BattleRedis.TOUCH_KEPT_FIGHTING);
        assertThat(lock(player)).isEqualTo(lockFields(X, "7", "F", 2000, 900));   // 没降回 P，d / p 没被备战期的值覆盖
        assertTtlSec(RedisKeys.battleLock(player), 1000);                          // TTL 没被缩回 50
    }

    @Test
    void 复核_TOUCH_P落在确认之后_不降级不缩TTL() throws Exception {
        // RDS-7 / FRZ-5 的时序：进场按 s=P 的旧快照发 TOUCH(P)，确认先一步把锁标 F 并续到正式期限
        long player = newPlayer();
        assertThat(await(scripts.prepareLock(player, X, 7, 96_000, 30_000, 90))).isEqualTo("0");
        assertThat(await(scripts.touch(player, X, 90, "P", 96_000, 30_000))).isEqualTo(BattleRedis.TOUCH_HIT);   // 确认之前：正常命中
        assertThat(await(scripts.confirm(player, X, 600_000, 660))).containsEntry("s", "F").containsEntry("d", "600000");

        List<Long> late = twice(() -> scripts.touch(player, X, 90, "P", 96_000, 30_000));

        assertThat(late).containsExactly(BattleRedis.TOUCH_KEPT_FIGHTING, BattleRedis.TOUCH_KEPT_FIGHTING);
        assertThat(lock(player)).isEqualTo(lockFields(X, "7", "F", 600_000, 30_000));
        assertTtlSec(RedisKeys.battleLock(player), 660);
    }

    // ================================================================== HOLD

    @Test
    void 续锁_只延不缩_三种TTL() throws Exception {
        // TTL 小于 hold → 顶到 hold；重放仍回 1、TTL 仍是 hold
        long shorter = newPlayer();
        seedLock(shorter, X, "F", 1000, 900, 50);
        assertThat(twice(() -> scripts.hold(shorter, X, 180))).containsExactly(1L, 1L);
        assertTtlSec(RedisKeys.battleLock(shorter), 180);

        // TTL 大于 hold → 不缩
        long longer = newPlayer();
        seedLock(longer, X, "F", 1000, 900, 1000);
        assertThat(twice(() -> scripts.hold(longer, X, 180))).containsExactly(1L, 1L);
        assertTtlSec(RedisKeys.battleLock(longer), 1000);

        // 永不过期（TTL = -1）→ 不加 TTL
        long persistent = newPlayer();
        seedLock(persistent, X, "F", 1000, 900, 0);
        assertThat(ttlMs(RedisKeys.battleLock(persistent))).isEqualTo(-1);
        assertThat(await(scripts.hold(persistent, X, 180))).isEqualTo(1L);
        assertThat(ttlMs(RedisKeys.battleLock(persistent))).isEqualTo(-1);

        // 字段不动
        assertThat(lock(shorter)).isEqualTo(lockFields(X, "7", "F", 1000, 900));
    }

    @Test
    void 续锁_不是本局回0_不动别的局的TTL() throws Exception {
        long other = newPlayer();
        seedLock(other, Y, "F", 1000, 900, 50);
        assertThat(twice(() -> scripts.hold(other, X, 180))).containsExactly(0L, 0L);
        assertThat(ttlMs(RedisKeys.battleLock(other))).isBetween(1L, 50_000L);

        long absent = newPlayer();
        assertThat(await(scripts.hold(absent, X, 180))).isZero();
        assertThat(exists(RedisKeys.battleLock(absent))).isFalse();
    }

    // ================================================================== ACK

    private enum LockState { OURS, OTHER, ABSENT }

    /** 一格真值表：摆好记录与锁 → ACK(X) → 核对位掩码、锁、记录、墓碑。 */
    private static void ack一格(boolean recordPresent, LockState lockState, long expectedBits) throws Exception {
        String label = "记录" + (recordPresent ? "在" : "不在") + " × 锁" + lockState;
        long player = newPlayer();
        if (recordPresent) {
            seedSettlement(player, X, bytes(1, 2, 3));
        }
        switch (lockState) {
            case OURS -> seedLock(player, X, "F", 1000, 900, 1000);
            case OTHER -> seedLock(player, Y, "P", 1000, 900, 1000);
            case ABSENT -> { }
        }

        long bits = await(scripts.ack(player, X));

        assertThat(bits).as(label).isEqualTo(expectedBits);
        assertThat(settlementMap(player).containsKey(XS)).as("%s：记录已删", label).isFalse();
        switch (lockState) {
            case OURS, ABSENT -> assertThat(exists(RedisKeys.battleLock(player))).as("%s：锁不在", label).isFalse();
            case OTHER -> {
                assertThat(lock(player)).as("%s：别的局的锁不动", label).isEqualTo(lockFields(Y, "7", "P", 1000, 900));
                assertTtlSec(RedisKeys.battleLock(player), 1000);
            }
        }
        // 无论记录在不在、锁是谁的，都写墓碑
        assertThat(redis.<String>getBucket(tomb(player, X), StringCodec.INSTANCE).get()).as("%s：墓碑", label).isEqualTo("1");
        assertTtlSec(tomb(player, X), BattleRedis.SETTLED_TOMBSTONE_TTL_SEC);
        assertThat(exists(tomb(player, Y))).as("%s：别的局没有墓碑", label).isFalse();

        // 重放：回 0（位 1 / 位 2 都丢），状态不变，墓碑仍在
        assertThat(await(scripts.ack(player, X))).as("%s：重放", label).isZero();
        assertThat(exists(tomb(player, X))).isTrue();
        if (lockState == LockState.OTHER) {
            assertThat(lock(player)).isEqualTo(lockFields(Y, "7", "P", 1000, 900));
        }
    }

    @Test
    void 销账_位掩码六格_记录有无乘锁匹配不匹配不在_每格都写墓碑_重放回0() throws Exception {
        ack一格(true, LockState.OURS, 3);
        ack一格(true, LockState.OTHER, 1);
        ack一格(true, LockState.ABSENT, 1);
        ack一格(false, LockState.OURS, 2);
        ack一格(false, LockState.OTHER, 0);
        ack一格(false, LockState.ABSENT, 0);
    }

    @Test
    void 销账_只删本局字段_删掉最后一个字段后键自然消失() throws Exception {
        long player = newPlayer();
        seedSettlement(player, X, bytes(1));
        seedSettlement(player, Y, bytes(2));
        settlementMap(player).expire(Duration.ofSeconds(1000));

        assertThat(await(scripts.ack(player, X))).isEqualTo(1L);

        assertThat(settlementMap(player).readAllMap().keySet()).containsExactly(YS);
        assertThat(settlementMap(player).get(YS)).isEqualTo(bytes(2));
        assertTtlSec(RedisKeys.battleSettlements(player), 1000);   // 销账不动整键 TTL

        assertThat(await(scripts.ack(player, Y))).isEqualTo(1L);

        assertThat(exists(RedisKeys.battleSettlements(player))).isFalse();
        assertThat(exists(tomb(player, X))).isTrue();
        assertThat(exists(tomb(player, Y))).isTrue();
    }

    // ================================================================== ENTER_READ / READ_IF_OURS / READ_LOCK_BATTLE

    @Test
    void 进场读_无锁无记录_TTL负2() throws Exception {
        long player = newPlayer();

        EnterRead read = await(scripts.enterRead(player));

        assertThat(read.lockTtlSec()).isEqualTo(-2);
        assertThat(read.lock()).isEmpty();
        assertThat(read.settlements()).isEmpty();
        assertThat(read.lockBattleId()).isZero();
    }

    @Test
    void 进场读_有锁无记录_字段与TTL_大号b按无符号() throws Exception {
        long player = newPlayer();
        seedLock(player, X, "P", 1000, 900, 156);

        EnterRead read = await(scripts.enterRead(player));

        assertThat(read.lock()).isEqualTo(lockFields(X, "7", "P", 1000, 900));
        assertThat(read.lockTtlSec()).isBetween(146L, 156L);
        assertThat(read.lockBattleId()).isEqualTo(X).isNegative();
        assertThat(read.settlements()).isEmpty();

        // 永不过期的锁：TTL = -1
        long persistent = newPlayer();
        seedLock(persistent, X, "F", 1000, 900, 0);
        assertThat(await(scripts.enterRead(persistent)).lockTtlSec()).isEqualTo(-1);
    }

    @Test
    void 进场读_锁与记录都有_值含0x00与0xFF原样往返_只读不改任何键() throws Exception {
        long player = newPlayer();
        byte[] first = bytes(0x00, 0xFF, 0x0A, 0x0D, 0x00, 0x80);
        byte[] second = bytes(0xFF);
        seedLock(player, Y, "F", 2000, 900, 1000);
        seedSettlement(player, X, first);
        seedSettlement(player, Y, second);
        settlementMap(player).expire(Duration.ofSeconds(2000));

        EnterRead read = await(scripts.enterRead(player));

        assertThat(read.lock()).isEqualTo(lockFields(Y, "7", "F", 2000, 900));
        assertThat(read.lockBattleId()).isEqualTo(Y);
        assertThat(read.settlements()).containsExactlyInAnyOrder(SettlementField.of(X, first), SettlementField.of(Y, second));
        // 只读：锁、记录、TTL 都没变，没有墓碑
        assertThat(lock(player)).isEqualTo(lockFields(Y, "7", "F", 2000, 900));
        assertTtlSec(RedisKeys.battleLock(player), 1000);
        assertTtlSec(RedisKeys.battleSettlements(player), 2000);
        assertThat(settlementMap(player).size()).isEqualTo(2);
        assertThat(exists(tomb(player, X))).isFalse();
    }

    @Test
    void 进场读_无锁有记录() throws Exception {
        long player = newPlayer();
        seedSettlement(player, X, bytes(9));

        EnterRead read = await(scripts.enterRead(player));

        assertThat(read.lockTtlSec()).isEqualTo(-2);
        assertThat(read.lock()).isEmpty();
        assertThat(read.settlements()).containsExactly(SettlementField.of(X, bytes(9)));
        assertThat(read.settlements().get(0).battleId()).isEqualTo(X);
    }

    @Test
    void 进场读_坏字段名保留原始字节_按原字节删得掉_不误删别的字段() throws Exception {
        // RDS-11：这两个名字按 UTF-8 解码是同一个 String（非法字节都变 U+FFFD），再编回去是 EF BF BD…，HDEL 一个也删不到
        long player = newPlayer();
        byte[] badA = bytes(0xFF, 0xFE, '1');
        byte[] badB = bytes(0xC0, 0xFE, '1');
        byte[] nonCanonical = "05".getBytes();
        seedRawField(player, badA, bytes(1));
        seedRawField(player, badB, bytes(2));
        seedRawField(player, nonCanonical, bytes(3));
        seedSettlement(player, X, bytes(4));

        EnterRead read = await(scripts.enterRead(player));

        assertThat(read.settlements()).containsExactlyInAnyOrder(new SettlementField(badA, bytes(1)), new SettlementField(badB, bytes(2)),
                new SettlementField(nonCanonical, bytes(3)), SettlementField.of(X, bytes(4)));
        List<SettlementField> bad = read.settlements().stream().filter(f -> f.battleId() == 0).toList();
        assertThat(bad).hasSize(3);   // 两个非法 UTF-8 的 + 一个非规范写法的 "05"

        // 删第一个坏字段：回 1；重放回 0；另外三个字段都还在
        SettlementField target = bad.stream().filter(f -> java.util.Arrays.equals(f.rawName(), badA)).findFirst().orElseThrow();
        assertThat(twice(() -> scripts.deleteSettlementField(player, target.rawName()))).containsExactly(1L, 0L);
        assertThat(await(scripts.enterRead(player)).settlements()).containsExactlyInAnyOrder(new SettlementField(badB, bytes(2)),
                new SettlementField(nonCanonical, bytes(3)), SettlementField.of(X, bytes(4)));

        // 其余坏字段逐个按原字节删掉，合法记录不受影响；删坏字段不写墓碑
        assertThat(await(scripts.deleteSettlementField(player, badB))).isEqualTo(1L);
        assertThat(await(scripts.deleteSettlementField(player, nonCanonical))).isEqualTo(1L);
        assertThat(await(scripts.enterRead(player)).settlements()).containsExactly(SettlementField.of(X, bytes(4)));
        assertThat(exists(tomb(player, X))).isFalse();
    }

    @Test
    void 删字段_键不存在回0() throws Exception {
        long player = newPlayer();
        assertThat(await(scripts.deleteSettlementField(player, bytes('7')))).isZero();
        assertThat(exists(RedisKeys.battleSettlements(player))).isFalse();
    }

    @Test
    void 读本局记录_有则原样字节_没有为null() throws Exception {
        long player = newPlayer();
        byte[] payload = bytes(0x00, 0xFF, 0x7F);
        assertThat(await(scripts.readSettlement(player, X))).isNull();   // 键不存在

        seedSettlement(player, X, payload);

        assertThat(await(scripts.readSettlement(player, X))).isEqualTo(payload);
        assertThat(await(scripts.readSettlement(player, Y))).isNull();   // 只读本局的字段
        assertThat(settlementMap(player).size()).isEqualTo(1);           // 只读
    }

    @Test
    void 读锁的b_有锁回battle_id_没有或不是数字回0() throws Exception {
        long player = newPlayer();
        assertThat(await(scripts.readLockBattleId(player))).isZero();

        seedLock(player, X, "P", 1000, 900, 1000);
        assertThat(await(scripts.readLockBattleId(player))).isEqualTo(X).isNegative();

        lockMap(player).fastPut("b", "not-a-number");
        assertThat(await(scripts.readLockBattleId(player))).isZero();
        assertTtlSec(RedisKeys.battleLock(player), 1000);   // 只读
    }

    // ================================================================== STORE_SETTLEMENT 与墓碑

    @Test
    void 落库_写字段_刷新整键TTL_回HLEN_重放HLEN不变() throws Exception {
        long player = newPlayer();
        byte[] payload = bytes(0x00, 0xFF, 0x01);

        List<Long> results = twice(() -> scripts.storeSettlement(player, X, payload, BattleRedis.SETTLEMENT_TTL_SEC));

        assertThat(results).containsExactly(1L, 1L);
        assertThat(settlementMap(player).readAllMap().keySet()).containsExactly(XS);
        assertThat(settlementMap(player).get(XS)).isEqualTo(payload);
        assertTtlSec(RedisKeys.battleSettlements(player), BattleRedis.SETTLEMENT_TTL_SEC);
        assertThat(exists(tomb(player, X))).isFalse();   // 落库不写墓碑
    }

    @Test
    void 落库_每局一个字段_第二局回2_整键TTL被刷新() throws Exception {
        long player = newPlayer();
        assertThat(await(scripts.storeSettlement(player, X, bytes(1), 100))).isEqualTo(1L);
        assertTtlSec(RedisKeys.battleSettlements(player), 100);

        assertThat(await(scripts.storeSettlement(player, Y, bytes(2), BattleRedis.SETTLEMENT_TTL_SEC))).isEqualTo(2L);

        assertTtlSec(RedisKeys.battleSettlements(player), BattleRedis.SETTLEMENT_TTL_SEC);
        assertThat(settlementMap(player).get(XS)).isEqualTo(bytes(1));
        assertThat(settlementMap(player).get(YS)).isEqualTo(bytes(2));
        // 同一局再落一次（内容变了也只是覆盖）：字段数不变
        assertThat(await(scripts.storeSettlement(player, X, bytes(9), BattleRedis.SETTLEMENT_TTL_SEC))).isEqualTo(2L);
        assertThat(settlementMap(player).get(XS)).isEqualTo(bytes(9));
    }

    @Test
    void 落库_销账_再落库_回负1_记录不复活() throws Exception {
        // RDS-6：落库的重发 / 晚到落在销账之后。没有墓碑时这里会把记录重新造出来，下次进场恢复重复发奖
        long player = newPlayer();
        byte[] payload = bytes(7, 7, 7);
        assertThat(await(scripts.storeSettlement(player, X, payload, BattleRedis.SETTLEMENT_TTL_SEC))).isEqualTo(1L);
        assertThat(await(scripts.settlementExists(player, X))).isTrue();
        assertThat(await(scripts.ack(player, X))).isEqualTo(1L);
        assertThat(await(scripts.settlementExists(player, X))).isFalse();   // 发件箱探测：已销账

        List<Long> late = twice(() -> scripts.storeSettlement(player, X, payload, BattleRedis.SETTLEMENT_TTL_SEC));

        assertThat(late).containsExactly(BattleRedis.STORE_ALREADY_SETTLED, BattleRedis.STORE_ALREADY_SETTLED);
        assertThat(exists(RedisKeys.battleSettlements(player))).isFalse();
        assertThat(await(scripts.settlementExists(player, X))).isFalse();
        assertThat(await(scripts.readSettlement(player, X))).isNull();
        assertThat(await(scripts.enterRead(player)).settlements()).isEmpty();   // 下次进场读不到孤儿记录
    }

    @Test
    void 落库_销账先到_not_durable的晚到落库_同样被挡() throws Exception {
        // OBX-7：落库 future 超时走 not_durable、只投一次；scene 应用、落盘、销账（HDEL 是空操作）之后那条 EVAL 才在 Redis 上执行
        long player = newPlayer();
        seedLock(player, X, "F", 1000, 900, 1000);
        assertThat(await(scripts.ack(player, X))).isEqualTo(2L);   // 记录不在、放了锁，照样留墓碑

        assertThat(await(scripts.storeSettlement(player, X, bytes(1), BattleRedis.SETTLEMENT_TTL_SEC))).isEqualTo(BattleRedis.STORE_ALREADY_SETTLED);

        assertThat(exists(RedisKeys.battleSettlements(player))).isFalse();
    }

    @Test
    void 落库_墓碑只挡这一局_别的局照常落库_被挡时不刷新整键TTL() throws Exception {
        long player = newPlayer();
        assertThat(await(scripts.storeSettlement(player, X, bytes(1), 100))).isEqualTo(1L);
        assertThat(await(scripts.storeSettlement(player, Y, bytes(2), 100))).isEqualTo(2L);
        assertThat(await(scripts.ack(player, X))).isEqualTo(1L);

        // X 已销账：被挡，键上只剩 Y，TTL 仍是 100（没被这次落库刷成 7 天）
        assertThat(await(scripts.storeSettlement(player, X, bytes(1), BattleRedis.SETTLEMENT_TTL_SEC))).isEqualTo(BattleRedis.STORE_ALREADY_SETTLED);
        assertThat(settlementMap(player).readAllMap().keySet()).containsExactly(YS);
        assertTtlSec(RedisKeys.battleSettlements(player), 100);

        // Y 没销账：照常落库（重放幂等），回字段数 1
        assertThat(await(scripts.storeSettlement(player, Y, bytes(2), BattleRedis.SETTLEMENT_TTL_SEC))).isEqualTo(1L);
        assertTtlSec(RedisKeys.battleSettlements(player), BattleRedis.SETTLEMENT_TTL_SEC);

        // 另一名玩家的同一局不受影响（墓碑按 (玩家, 战斗)）
        long another = newPlayer();
        assertThat(await(scripts.storeSettlement(another, X, bytes(3), BattleRedis.SETTLEMENT_TTL_SEC))).isEqualTo(1L);
    }

    @Test
    void 落库_墓碑过期后_不再被挡() throws Exception {
        // 墓碑是短寿命的：过期后同一局的落库照常写入（保护窗口就是墓碑 TTL）。这里手工把墓碑删掉模拟过期
        long player = newPlayer();
        assertThat(await(scripts.ack(player, X))).isZero();
        assertThat(await(scripts.storeSettlement(player, X, bytes(1), 100))).isEqualTo(BattleRedis.STORE_ALREADY_SETTLED);

        redis.getKeys().delete(tomb(player, X));

        assertThat(await(scripts.storeSettlement(player, X, bytes(1), 100))).isEqualTo(1L);
    }

    @Test
    void 探测_记录在回true_不在回false_只看本局字段() throws Exception {
        long player = newPlayer();
        assertThat(await(scripts.settlementExists(player, X))).isFalse();   // 键不存在

        seedSettlement(player, Y, bytes(1));
        assertThat(await(scripts.settlementExists(player, X))).isFalse();   // 键在、字段不在
        assertThat(await(scripts.settlementExists(player, Y))).isTrue();
    }

    // ================================================================== ACK_IF_SUPERSEDED

    @Test
    void 已取代判定_记录不在回3_不看锁_不写墓碑() throws Exception {
        long noLock = newPlayer();
        assertThat(twice(() -> scripts.ackIfSuperseded(noLock, X))).containsExactly(3L, 3L);

        long otherLock = newPlayer();
        seedLock(otherLock, Y, "P", 1000, 900, 1000);
        seedSettlement(otherLock, Y, bytes(1));   // 别的局的记录不算
        assertThat(await(scripts.ackIfSuperseded(otherLock, X))).isEqualTo(3L);

        assertThat(exists(tomb(noLock, X))).isFalse();
        assertThat(exists(tomb(otherLock, X))).isFalse();
        assertThat(lock(otherLock)).isEqualTo(lockFields(Y, "7", "P", 1000, 900));
        assertThat(settlementMap(otherLock).containsKey(YS)).isTrue();
    }

    @Test
    void 已取代判定_记录在_锁不在回0_仍是本局回1_都没有副作用() throws Exception {
        long noLock = newPlayer();
        seedSettlement(noLock, X, bytes(1));
        assertThat(twice(() -> scripts.ackIfSuperseded(noLock, X))).containsExactly(0L, 0L);
        assertThat(settlementMap(noLock).get(XS)).isEqualTo(bytes(1));
        assertThat(exists(tomb(noLock, X))).isFalse();

        long ours = newPlayer();
        seedSettlement(ours, X, bytes(1));
        seedLock(ours, X, "F", 1000, 900, 1000);
        assertThat(twice(() -> scripts.ackIfSuperseded(ours, X))).containsExactly(1L, 1L);
        assertThat(settlementMap(ours).get(XS)).isEqualTo(bytes(1));
        assertThat(lock(ours)).isEqualTo(lockFields(X, "7", "F", 1000, 900));
        assertTtlSec(RedisKeys.battleLock(ours), 1000);
        assertThat(exists(tomb(ours, X))).isFalse();
    }

    @Test
    void 已取代判定_锁被别的局持有_删记录写墓碑回2_重放回3_之后的落库被挡() throws Exception {
        long player = newPlayer();
        seedSettlement(player, X, bytes(1));
        seedSettlement(player, Y, bytes(2));
        seedLock(player, Y, "P", 1000, 900, 1000);

        List<Long> results = twice(() -> scripts.ackIfSuperseded(player, X));

        assertThat(results).containsExactly(2L, 3L);
        assertThat(settlementMap(player).readAllMap().keySet()).containsExactly(YS);   // 只删本局字段
        assertThat(lock(player)).isEqualTo(lockFields(Y, "7", "P", 1000, 900));         // 下一局的锁不动
        assertTtlSec(RedisKeys.battleLock(player), 1000);
        assertThat(redis.<String>getBucket(tomb(player, X), StringCodec.INSTANCE).get()).isEqualTo("1");
        assertTtlSec(tomb(player, X), BattleRedis.SETTLED_TOMBSTONE_TTL_SEC);
        assertThat(exists(tomb(player, Y))).isFalse();

        // 取代也是一次销账：这一局迟到 / 重放的落库不许把记录造回来
        assertThat(await(scripts.storeSettlement(player, X, bytes(1), BattleRedis.SETTLEMENT_TTL_SEC))).isEqualTo(BattleRedis.STORE_ALREADY_SETTLED);
        assertThat(settlementMap(player).readAllMap().keySet()).containsExactly(YS);
    }

    // ================================================================== 活动结果

    @Test
    void 活动结果_SET_EX与EXISTS_值原样_重放幂等() throws Exception {
        long battle = BATTLE_BASE + 100 + playerSeq.incrementAndGet();
        String key = RedisKeys.battleActivityResult(battle);
        written.add(key);
        byte[] payload = bytes(0x00, 0xFF, 0x42);
        assertThat(await(scripts.activityResultExists(battle))).isFalse();

        List<Long> results = twice(() -> scripts.storeActivityResult(battle, payload, BattleRedis.ACTIVITY_RESULT_TTL_SEC));

        assertThat(results).containsExactly(1L, 1L);
        assertThat(await(scripts.activityResultExists(battle))).isTrue();
        assertThat(redis.<byte[]>getBucket(key, ByteArrayCodec.INSTANCE).get()).isEqualTo(payload);
        assertTtlSec(key, BattleRedis.ACTIVITY_RESULT_TTL_SEC);

        // 消费方销账（DEL）之后探测得到「不在」
        redis.getKeys().delete(key);
        assertThat(await(scripts.activityResultExists(battle))).isFalse();
    }

    // ================================================================== 端到端：落库 → 应用后续锁 → 销账 → 探测

    @Test
    void 一局的完整链路_备战_确认_落库_续锁_销账_探测得到已销账() throws Exception {
        long player = newPlayer();
        byte[] payload = bytes(1, 2, 3, 4);
        assertThat(await(scripts.prepareLock(player, X, 3, 96_000, 30_000, 90))).isEqualTo("0");
        assertThat(await(scripts.confirm(player, X, 600_000, 660))).containsEntry("s", "F");
        assertThat(await(scripts.storeSettlement(player, X, payload, BattleRedis.SETTLEMENT_TTL_SEC))).isEqualTo(1L);
        assertThat(await(scripts.readLockBattleId(player))).isEqualTo(X);
        assertThat(await(scripts.readSettlement(player, X))).isEqualTo(payload);
        assertThat(await(scripts.ackIfSuperseded(player, X))).isEqualTo(1L);   // 离线分支看：仍是本局
        assertThat(await(scripts.hold(player, X, BattleRedis.LOCK_HOLD_AFTER_APPLY_SEC))).isEqualTo(1L);
        assertTtlSec(RedisKeys.battleLock(player), 660);                        // 660 > 180：只延不缩

        assertThat(await(scripts.ack(player, X))).isEqualTo(3L);

        assertThat(await(scripts.settlementExists(player, X))).isFalse();
        assertThat(await(scripts.ackIfSuperseded(player, X))).isEqualTo(3L);
        assertThat(await(scripts.readLockBattleId(player))).isZero();
        EnterRead after = await(scripts.enterRead(player));
        assertThat(after.lockTtlSec()).isEqualTo(-2);
        assertThat(after.settlements()).isEmpty();
        // 下一局可以马上备战（锁已放）
        assertThat(await(scripts.prepareLock(player, Y, 3, 96_000, 30_000, 90))).isEqualTo("0");
    }

    @Test
    void 销账之后_各段脚本迟到的重放都落空_不把锁或记录造回来() throws Exception {
        // 类注释「与销账交错」：销账（记录删、锁放、墓碑写）之后，同一局任何一段可变脚本的重发 / 晚到都不能复活这一局
        long player = newPlayer();
        seedLock(player, X, "F", 600_000, 30_000, 660);
        seedSettlement(player, X, bytes(1));
        assertThat(await(scripts.ack(player, X))).isEqualTo(3L);

        assertThat(await(scripts.confirm(player, X, 600_000, 660))).isNull();
        assertThat(await(scripts.cancelOffline(player, X))).isEqualTo(BattleRedis.PREPARING_DELETE_MISS);
        assertThat(await(scripts.deletePreparingIfMatch(player, X))).isEqualTo(BattleRedis.PREPARING_DELETE_MISS);
        assertThat(await(scripts.deleteIfMatch(player, X))).isZero();
        assertThat(await(scripts.touch(player, X, 660, "F", 600_000, 30_000))).isEqualTo(BattleRedis.TOUCH_MISS);
        assertThat(await(scripts.touch(player, X, 90, "P", 96_000, 30_000))).isEqualTo(BattleRedis.TOUCH_MISS);
        assertThat(await(scripts.hold(player, X, BattleRedis.LOCK_HOLD_AFTER_APPLY_SEC))).isZero();
        assertThat(await(scripts.ack(player, X))).isZero();
        assertThat(await(scripts.ackIfSuperseded(player, X))).isEqualTo(3L);
        assertThat(await(scripts.storeSettlement(player, X, bytes(1), BattleRedis.SETTLEMENT_TTL_SEC))).isEqualTo(BattleRedis.STORE_ALREADY_SETTLED);

        assertThat(exists(RedisKeys.battleLock(player))).as("锁没有被造回来").isFalse();
        assertThat(exists(RedisKeys.battleSettlements(player))).as("记录没有被造回来").isFalse();
        EnterRead after = await(scripts.enterRead(player));
        assertThat(after.lockTtlSec()).isEqualTo(-2);
        assertThat(after.settlements()).isEmpty();
    }

    @Test
    void 销账只动本局_下一局的锁_记录_落库都不受影响() throws Exception {
        // 迟到的销账（上一局 X）落在下一局 Y 已经备战、甚至已经结算之后
        long player = newPlayer();
        seedSettlement(player, X, bytes(1));
        assertThat(await(scripts.prepareLock(player, Y, 7, 96_000, 30_000, 156))).isEqualTo("0");
        assertThat(await(scripts.storeSettlement(player, Y, bytes(2), BattleRedis.SETTLEMENT_TTL_SEC))).isEqualTo(2L);

        assertThat(twice(() -> scripts.ack(player, X))).containsExactly(1L, 0L);

        assertThat(lock(player)).isEqualTo(lockFields(Y, "7", "P", 96_000, 30_000));
        assertTtlSec(RedisKeys.battleLock(player), 156);
        assertThat(settlementMap(player).readAllMap().keySet()).containsExactly(YS);
        assertThat(exists(tomb(player, Y))).isFalse();
        // Y 的落库重放照常幂等（X 的墓碑不挡 Y），Y 的确认照常命中
        assertThat(await(scripts.storeSettlement(player, Y, bytes(2), BattleRedis.SETTLEMENT_TTL_SEC))).isEqualTo(1L);
        assertThat(await(scripts.confirm(player, Y, 600_000, 660))).containsEntry("s", "F").containsEntry("b", YS);
    }

    // ================================================================== 协议异常与连接失败

    @Test
    void 回整数的脚本得到空回复_异常完成_不映射成任何结论() {
        // OBX-9 / D15：没问到结论不能当成「不在」。这里用一段回 nil 的脚本走同一条 integer 路径
        long player = newPlayer();
        CompletableFuture<Long> future = scripts.integer("NIL_PROBE", "return redis.call('HGET', KEYS[1], 'missing')",
                List.of(RedisKeys.battleSettlements(player)));

        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class).hasStackTraceContaining("NIL_PROBE 回复为空");
    }

    @Test
    void 脚本报错_异常完成() {
        // 对 String 键做 HGET → WRONGTYPE：future 异常完成（调用方走各自的「出错」分支，不当成没有锁）
        long player = newPlayer();
        redis.getBucket(RedisKeys.battleLock(player), StringCodec.INSTANCE).set("not-a-hash");

        assertThatThrownBy(() -> await(scripts.readLockBattleId(player))).isInstanceOf(ExecutionException.class);
        assertThatThrownBy(() -> await(scripts.enterRead(player))).isInstanceOf(ExecutionException.class);
        assertThatThrownBy(() -> await(scripts.ack(player, X))).isInstanceOf(ExecutionException.class);
        assertThatThrownBy(() -> await(scripts.touch(player, X, 60, "P", 1, 1))).isInstanceOf(ExecutionException.class);
    }

    @Test
    void Redis不可用_每个方法都异常完成_不同步抛出() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(DB);
        RedissonClient closed = Redisson.create(config);
        closed.shutdown();
        BattleRedis dead = new BattleRedis(closed);
        long player = PLAYER_BASE;

        List<Supplier<CompletableFuture<?>>> calls = List.of(
                () -> dead.prepareLock(player, X, 1, 1000, 900, 60),
                () -> dead.confirm(player, X, 1000, 60),
                () -> dead.cancelOffline(player, X),
                () -> dead.deletePreparingIfMatch(player, X),
                () -> dead.deleteIfMatch(player, X),
                () -> dead.touch(player, X, 60, "P", 1000, 900),
                () -> dead.hold(player, X, 180),
                () -> dead.ack(player, X),
                () -> dead.enterRead(player),
                () -> dead.readSettlement(player, X),
                () -> dead.readLockBattleId(player),
                () -> dead.deleteSettlementField(player, bytes('1')),
                () -> dead.storeSettlement(player, X, bytes(1), 60),
                () -> dead.settlementExists(player, X),
                () -> dead.ackIfSuperseded(player, X),
                () -> dead.storeActivityResult(X, bytes(1), 60),
                () -> dead.activityResultExists(X));
        for (int i = 0; i < calls.size(); i++) {
            CompletableFuture<?> future = calls.get(i).get();   // 这一步不许抛
            assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS)).as("第 %d 个方法", i).isInstanceOf(ExecutionException.class);
        }
    }
}
