package com.game.match.spectate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.game.api.match.MatchBudgets;
import com.game.common.deadline.Deadline;
import com.game.discovery.RedisKeys;
import com.game.match.proto.BattlePlacement;
import com.game.match.spectate.SpectateStore.Acquire;
import com.game.match.spectate.SpectateStore.Entry;
import com.game.match.spectate.SpectateStore.Eviction;
import com.game.match.spectate.SpectateStore.Listed;
import com.game.match.spectate.SpectateStore.Pick;
import com.game.match.spectate.SpectateStore.Record;
import com.game.match.spectate.SpectateStore.Scored;
import com.game.match.spectate.SpectateStore.Snapshot;
import com.game.match.ticket.QueueRef;
import com.game.match.ticket.RedissonTicketStore;
import com.game.match.ticket.TicketRedisFixture;
import com.game.match.ticket.TicketRef;
import com.game.proto.match.BattleWatchSummary;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.connection.CRC16;

/**
 * 观战存储的 Redis 实现连真 Redis（缺省跳过：{@code -Dxm.it.redis=redis://127.0.0.1:6379}；DB 13，随机的玩家号 / 战斗号、自己的索引键，
 * 只删自己的键；spectate-spec §10.4）。
 *
 * <ul>
 *   <li>先过 {@link SpectateStoreContract} 的全部用例——与内存替身 {@code InMemorySpectateStoreTest} 同一套断言（九段脚本的真值表、
 *       每个可变方法连调两次最终状态不变）。玩家号、战斗号全部 ≥ 2^63（键名、成员、标记值按无符号十进制）。</li>
 *   <li>再加只有真 Redis 才看得见的东西：键的形状与类型、TTL 的毫秒数、与 6.4 的真票据 / 真落点写者的配合、<b>每段可变脚本原样重发一遍后存储逐字节不变</b>
 *       （{@code DUMP} 比较）、恰在过期分界上的判定（钉住时钟的脚本副本）、随机选场的分布、落点的各种损坏形状、被占成别的类型的键、
 *       不是合法 UTF-8 的脏成员、客户端不可用、虚拟线程、真并发下「每人至多一个标记」。</li>
 *   <li>移植基线 {@code go/match/internal/logic/spectate_test.go} 的 {@code TestSpectateRegisterLoadRemoveRoundTrip}、
 *       {@code TestPickRandomBattleNeverReturnsStaleOrGarbage}、{@code …EvictsAllBadMembersAndReportsNone}、{@code …GivesUpAfterThreeAttempts}
 *       （后三条在 Java 的行为不同：过期成员根本挑不到、也不由选场顺手剔除，见各用例）。</li>
 * </ul>
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class RedissonSpectateStoreIntegrationTest extends SpectateStoreContract {

    private static final long STALE_MS = MatchBudgets.SPECTATE_STALE_MS;
    private static final byte[] GARBAGE_PB = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF};

    private static RedissonClient redis;
    private SpectateRedisFixture fx;

    @BeforeAll
    static void connect() {
        redis = TicketRedisFixture.connect();
    }

    @AfterAll
    static void shutdown() {
        if (redis != null) {
            redis.shutdown();
        }
    }

    @BeforeEach
    void setUp() {
        fx = new SpectateRedisFixture(redis);
    }

    @AfterEach
    void cleanup() {
        fx.cleanup();
    }

    // ================================================================ 契约测试的钩子

    @Override
    protected SpectateStore store() {
        return fx.store;
    }

    @Override
    protected long pid(int n) {
        return fx.pid(n);
    }

    @Override
    protected long battle(int n) {
        return fx.battle(n);
    }

    @Override
    protected long nowMs() {
        return fx.nowMs();
    }

    @Override
    protected void givenTicket(long playerId) {
        fx.putTicketKey(playerId);
    }

    @Override
    protected void givenMark(long playerId, String value) {
        fx.putMark(playerId, value);
    }

    @Override
    protected Optional<String> markOf(long playerId) {
        return fx.markOf(playerId);
    }

    @Override
    protected long markTtlMs(long playerId) {
        return fx.markTtlMs(playerId);
    }

    @Override
    protected void givenPlacement(BattlePlacement placement) {
        fx.putPlacement(placement);
    }

    @Override
    protected void givenCorruptPlacement(long battleId) {
        fx.putRawPlacement(battleId, null, GARBAGE_PB);
    }

    @Override
    protected boolean placementExists(long battleId) {
        return fx.placementExists(battleId);
    }

    @Override
    protected Optional<BattlePlacement> placementOf(long battleId) {
        return fx.placementOf(battleId);
    }

    @Override
    protected void givenMember(String member, long score) {
        fx.putMember(member, score);
    }

    @Override
    protected Map<String, Long> index() {
        return fx.index();
    }

    // ================================================================ 小工具

    private static String member(long battleId) {
        return SpectateRules.member(battleId);
    }

    /** 做一遍、记下存储的逐字节快照、原样再做一遍：两遍的返回值各是什么，第二遍之后存储必须与第一遍之后逐字节相同。 */
    private void replayed(String what, Supplier<Object> operation, Object first, Object second) {
        assertThat(operation.get()).as("%s：第一遍", what).isEqualTo(first);
        Map<String, String> afterFirst = fx.dump();
        assertThat(operation.get()).as("%s：原样重发", what).isEqualTo(second);
        assertThat(fx.dump()).as("%s：重发之后存储逐字节不变", what).isEqualTo(afterFirst);
    }

    private static String text(Object replyItem) {
        return new String((byte[]) replyItem, StandardCharsets.ISO_8859_1);
    }

    /** Redis Cluster 的槽：hash tag（第一个 { 与其后第一个 } 之间的非空内容）的 CRC16 模 16384。 */
    private static int slotOf(String key) {
        int open = key.indexOf('{');
        int close = open < 0 ? -1 : key.indexOf('}', open + 1);
        String tagged = open >= 0 && close > open + 1 ? key.substring(open + 1, close) : key;
        return CRC16.crc16(tagged.getBytes(StandardCharsets.UTF_8)) % 16384;
    }

    // ================================================================ 键的形状

    @Test
    void 抢到的标记_键是带tag的无符号十进制_类型是STRING_值原样_TTL是360秒() {
        long playerId = fx.pid(1);
        String value = mark(fx.battle(1));

        assertThat(fx.store.acquire(playerId, value, d())).isEqualTo(Acquire.OK);

        String key = "xm:{match}:watching:" + Long.toUnsignedString(playerId);
        assertThat(playerId).as("夹具的玩家号 ≥ 2^63").isNegative();
        assertThat(SpectateRedisFixture.markKey(playerId)).isEqualTo(key);
        assertThat(fx.type(key)).isEqualTo("string");
        assertThat(redis.<String>getBucket(key, StringCodec.INSTANCE).get()).isEqualTo(value);
        assertThat(value).startsWith(Long.toUnsignedString(fx.battle(1)) + ":");
        assertThat(fx.pttl(key)).as("PX 360000：刚写完，剩余不超过它、也不会少掉几秒").isBetween(355_000L, 360_000L);
    }

    @Test
    void 公开_索引是没有TTL的ZSET_成员是无符号十进制_分数是created_at_ms_落点记录原封不动() {
        long battleId = fx.battle(1);
        long createdAt = fx.nowMs() - 2_000;
        BattlePlacement placement = placement(battleId, 1, createdAt);
        fx.putPlacement(placement);
        String placementDump = fx.dump().get(SpectateRedisFixture.placementKey(battleId));

        assertThat(fx.store.publish(placement, d())).isTrue();

        assertThat(battleId).as("夹具的战斗号 ≥ 2^63").isNegative();
        assertThat(fx.type(fx.indexKey)).isEqualTo("zset");
        assertThat(fx.pttl(fx.indexKey)).as("索引没有 TTL").isEqualTo(-1);
        assertThat(fx.index()).containsOnly(Map.entry(Long.toUnsignedString(battleId), createdAt));
        assertThat(fx.dump().get(SpectateRedisFixture.placementKey(battleId))).as("公开只读落点").isEqualTo(placementDump);
    }

    @Test
    void 观战脚本碰的键与票据_落点同槽() {
        long playerId = fx.pid(1);
        long battleId = fx.battle(1);
        int expected = slotOf(TicketRedisFixture.ticketKey(playerId));

        assertThat(expected).isEqualTo(CRC16.crc16("match".getBytes(StandardCharsets.UTF_8)) % 16384);
        for (String key : List.of(SpectateRedisFixture.markKey(playerId), SpectateRedisFixture.placementKey(battleId), RedisKeys.matchWatchable(),
                fx.indexKey)) {
            assertThat(key).startsWith("xm:{match}:");
            assertThat(slotOf(key)).as(key).isEqualTo(expected);
        }
        // 多键脚本真的各跑一遍（单机 Redis 不校验槽，这里只是确认它们确实是「多键、同 tag」的那几段）
        assertThat(fx.store.entry(playerId, d()).hasTicket()).isFalse();
        assertThat(fx.store.read(battleId, d()).record()).isInstanceOf(Record.Absent.class);
        assertThat(fx.store.evict(new Eviction.Missing(battleId), d())).isFalse();
    }

    // ================================================================ 与 6.4 的真票据、真落点写者

    @Test
    void 有没有票只看票据键在不在_真票据存储建出的queued_matched_ready票都算_票没了就能抢() {
        RedissonTicketStore tickets = fx.tickets.store;
        long queued = fx.pid(1);
        long matched = fx.pid(2);
        long ready = fx.pid(3);
        QueueRef queue = fx.tickets.queue(3, 1);
        tickets.enqueue(queued, "t-queued", queue, 1, 150_000, 21_600_000, d());
        tickets.createMatched(matched, "t-matched", 5, 1, 1, 150_000, 42_000, d());
        tickets.createMatched(ready, "t-ready", 5, 1, 1, 150_000, 42_000, d());
        assertThat(tickets.markReady(new TicketRef(ready, "t-ready"), fx.battle(9), 60_000, d())).isTrue();

        for (long playerId : new long[] {queued, matched, ready}) {
            assertThat(fx.store.entry(playerId, d()).hasTicket()).as("player %s", Long.toUnsignedString(playerId)).isTrue();
            assertThat(fx.store.acquire(playerId, mark(fx.battle(1)), d())).as("持票任意状态都不能抢（BW1：ready 残留也算）").isEqualTo(Acquire.QUEUED);
            assertThat(fx.markOf(playerId)).isEmpty();
        }

        assertThat(tickets.cancel(queued, "t-queued", queue, d())).isTrue();
        assertThat(tickets.delete(new TicketRef(matched, "t-matched"), d())).isTrue();

        assertThat(fx.store.entry(queued, d()).hasTicket()).isFalse();
        assertThat(fx.store.acquire(queued, mark(fx.battle(1)), d())).isEqualTo(Acquire.OK);
        assertThat(fx.store.acquire(matched, mark(fx.battle(1)), d())).isEqualTo(Acquire.OK);
        assertThat(fx.store.acquire(ready, mark(fx.battle(1)), d())).as("ready 票还在").isEqualTo(Acquire.QUEUED);
    }

    @Test
    void 损坏的票据HASH与被占成别的类型的票据键也算有票() {
        long corrupt = fx.pid(1);
        long wrongType = fx.pid(2);
        redis.<String, String>getMap(TicketRedisFixture.ticketKey(corrupt), StringCodec.INSTANCE).put("没有票号的字段", "1");
        fx.pexpire(TicketRedisFixture.ticketKey(corrupt), 600_000);
        fx.setString(TicketRedisFixture.ticketKey(wrongType), "不是 HASH");

        for (long playerId : new long[] {corrupt, wrongType}) {
            assertThat(fx.store.entry(playerId, d()).hasTicket()).as("只看键在不在，不解析").isTrue();
            assertThat(fx.store.acquire(playerId, mark(fx.battle(1)), d())).isEqualTo(Acquire.QUEUED);
            assertThat(fx.markOf(playerId)).isEmpty();
        }
    }

    /** 移植 {@code TestSpectateRegisterLoadRemoveRoundTrip}（spectate_test.go:127）：登记 → 字段完整、索引含成员且分数 = created_at、TTL = 时限 + 60 s；剔除后两处皆空。 */
    @Test
    void 登记_读回_剔除往返_落点的字段完整_索引的分数是created_at_落点的TTL是360秒() {
        long battleId = fx.battle(1);
        long createdAt = fx.nowMs() - 1_500;
        BattlePlacement placement = BattlePlacement.newBuilder().setBattleId(battleId).setBattleNodeId(7).setBattleInstanceId("battle-inst-7")
                .setRpcHost("10.0.0.7").setRpcPort(21207).setAttempt(1).setMode(5).setBattleConfigId(3).addPlayerNames("甲").addPlayerNames("乙")
                .setCreatedAtMs(createdAt).setDeadlineMs(createdAt + 300_000).build();
        fx.putPlacement(placement);
        assertThat(fx.store.read(battleId, d()).published()).as("落点先于公开：建房期间读到的是「未公开」").isFalse();

        assertThat(fx.store.publish(placement, d())).isTrue();

        Snapshot snapshot = fx.store.read(battleId, d());
        assertThat(snapshot.published()).isTrue();
        assertThat(snapshot.record()).isEqualTo(new Record.Found(placement));
        BattleWatchSummary summary = SpectateRules.summaryOf(((Record.Found) snapshot.record()).placement());
        assertThat(summary.getBattleId()).isEqualTo(battleId);
        assertThat(summary.getModeValue()).isEqualTo(5);
        assertThat(summary.getBattleConfigId()).isEqualTo(3);
        assertThat(summary.getPlayerNamesList()).containsExactly("甲", "乙");
        assertThat(summary.getCreatedAtMs()).isEqualTo(createdAt);
        assertThat(fx.index()).containsOnly(Map.entry(member(battleId), createdAt));
        assertThat(fx.store.list(20, d()).members()).containsExactly(new Scored(member(battleId), createdAt));
        assertThat(fx.store.readPlacements(List.of(battleId), d())).containsOnly(Map.entry(battleId, new Record.Found(placement)));
        assertThat(fx.pttl(SpectateRedisFixture.placementKey(battleId))).as("落点的 TTL = 战斗时限 300 s + 60 s 余量").isBetween(350_000L, 360_000L);

        assertThat(fx.store.evict(new Eviction.Dead(battleId, 1), d())).isTrue();

        Snapshot gone = fx.store.read(battleId, d());
        assertThat(gone.published()).isFalse();
        assertThat(gone.record()).isInstanceOf(Record.Absent.class);
        assertThat(fx.store.watchableCount(d())).isZero();
        assertThat(fx.placementExists(battleId)).isFalse();
    }

    // ================================================================ 重放：每段可变脚本原样再发一遍

    @Test
    void 重放_抢标记_第二遍命中自己的值_不重写也不续期_存储逐字节不变() {
        long playerId = fx.pid(1);
        String value = mark(fx.battle(1));
        String key = SpectateRedisFixture.markKey(playerId);
        assertThat(fx.store.acquire(playerId, value, d())).isEqualTo(Acquire.OK);
        fx.pexpire(key, 100_000); // 当作首轮写下之后已经过去了 260 s
        Map<String, String> afterFirst = fx.dump();

        assertThat(fx.store.acquire(playerId, value, d())).as("重发：认得出自己写的值，不误报 BUSY").isEqualTo(Acquire.OK);

        assertThat(fx.dump()).isEqualTo(afterFirst);
        assertThat(fx.pttl(key)).as("重放不是续期：TTL 没有被刷回 360 s").isBetween(1L, 100_000L);
    }

    @Test
    void 重放_抢标记的两种拒绝_有票与被别人占着_两遍都什么都不写() {
        long queued = fx.pid(1);
        long busy = fx.pid(2);
        fx.putTicketKey(queued);
        fx.putMark(busy, mark(fx.battle(1)));
        fx.pexpire(SpectateRedisFixture.markKey(busy), 100_000);
        Map<String, String> before = fx.dump();
        String mine = mark(fx.battle(2));

        for (int round = 1; round <= 2; round++) {
            assertThat(fx.store.acquire(queued, mine, d())).as("第 %d 遍", round).isEqualTo(Acquire.QUEUED);
            assertThat(fx.store.acquire(busy, mine, d())).as("第 %d 遍", round).isEqualTo(Acquire.BUSY);
            assertThat(fx.dump()).as("第 %d 遍之后", round).isEqualTo(before);
        }
        assertThat(fx.pttl(SpectateRedisFixture.markKey(busy))).as("别人的标记没有被续期").isBetween(1L, 100_000L);
    }

    @Test
    void 重放_抢标记的两遍之间建出了票据_第二遍回QUEUED_首轮写下的标记还在_按本次的值删得掉() {
        long playerId = fx.pid(1);
        String value = mark(fx.battle(1));
        assertThat(fx.store.acquire(playerId, value, d())).isEqualTo(Acquire.OK);
        fx.tickets.store.createMatched(playerId, "late-ticket", 5, 1, 1, 150_000, 42_000, d());

        assertThat(fx.store.acquire(playerId, value, d())).isEqualTo(Acquire.QUEUED);

        assertThat(fx.markOf(playerId)).as("这就是为什么 QUEUED 之后调用方仍要按本次的值释放一次").contains(value);
        assertThat(fx.store.release(playerId, value, d())).isTrue();
        assertThat(fx.markOf(playerId)).isEmpty();
    }

    @Test
    void 重放_按值删标记_公开_清扫_第二遍存储逐字节不变() {
        long playerId = fx.pid(1);
        String value = mark(fx.battle(1));
        fx.putMark(playerId, value);
        long now = fx.nowMs();
        BattlePlacement placement = placement(fx.battle(2), 2, now - 3_000);
        fx.putPlacement(placement);
        fx.putMember(member(fx.battle(3)), now - STALE_MS - 60_000);
        fx.putMember(member(fx.battle(4)), now - STALE_MS - 90_000);
        fx.putMember(member(fx.battle(5)), now - 1_000);

        replayed("release", () -> fx.store.release(playerId, value, d()), true, false);
        replayed("publish", () -> fx.store.publish(placement, d()), true, true);
        replayed("sweep", () -> fx.store.sweep(d()), 2L, 0L);

        assertThat(fx.markOf(playerId)).isEmpty();
        assertThat(fx.index()).containsOnlyKeys(member(fx.battle(2)), member(fx.battle(5)));
    }

    @Test
    void 重放_四种剔除_第二遍是false_存储逐字节不变_条件不成立的两遍都不动() {
        long now = fx.nowMs();
        long cutoff = now - STALE_MS;
        fx.putMember("not-a-battle", now - 1_000);
        fx.putMember(member(fx.battle(1)), now - 1_000); // 缺记录
        fx.putPlacement(placement(fx.battle(2), 1, now - 40_000)); // 房间已死
        fx.putMember(member(fx.battle(2)), now - 40_000);
        fx.putPlacement(placement(fx.battle(3), 1, cutoff - 5)); // 过期
        fx.putMember(member(fx.battle(3)), cutoff - 5);
        fx.putPlacement(placement(fx.battle(4), 2, now - 2_000)); // 活着的一场：下面每一条不成立的剔除都冲着它来
        fx.putMember(member(fx.battle(4)), now - 2_000);

        replayed("invalid", () -> fx.store.evict(new Eviction.Invalid("not-a-battle"), d()), true, false);
        replayed("missing", () -> fx.store.evict(new Eviction.Missing(fx.battle(1)), d()), true, false);
        replayed("dead", () -> fx.store.evict(new Eviction.Dead(fx.battle(2), 1), d()), true, false);
        replayed("stale", () -> fx.store.evict(new Eviction.Stale(fx.battle(3), cutoff), d()), true, false);
        replayed("missing（落点在）", () -> fx.store.evict(new Eviction.Missing(fx.battle(4)), d()), false, false);
        replayed("dead（attempt 不符）", () -> fx.store.evict(new Eviction.Dead(fx.battle(4), 1), d()), false, false);
        replayed("stale（没过期）", () -> fx.store.evict(new Eviction.Stale(fx.battle(4), cutoff), d()), false, false);

        assertThat(fx.index()).containsOnly(Map.entry(member(fx.battle(4)), now - 2_000));
        assertThat(fx.placementOf(fx.battle(4))).contains(placement(fx.battle(4), 2, now - 2_000));
        for (int n = 1; n <= 3; n++) {
            assertThat(fx.placementExists(fx.battle(n))).as("battle(%d)", n).isFalse();
        }
    }

    @Test
    void 剔除脚本收到不认识的模式_是脚本错误_什么都不改() {
        long now = fx.nowMs();
        long battleId = fx.battle(1);
        fx.putPlacement(placement(battleId, 1, now - 1_000));
        fx.putMember(member(battleId), now - 1_000);
        Map<String, String> before = fx.dump();

        assertThatThrownBy(() -> redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, SpectateScripts.EVICT, RScript.ReturnType.INTEGER,
                List.<Object>of(fx.indexKey, SpectateRedisFixture.placementKey(battleId)), "purge", member(battleId), "1"))
                .hasMessageContaining("unknown mode");

        assertThat(fx.dump()).isEqualTo(before);
    }

    // ================================================================ 恰在分界上：钉住时钟的脚本副本

    @Test
    void 选场的过期分界_钉住时钟_恰在分界上的成员还活着_早1毫秒的挑不到() {
        long seconds = 1_900_000_000L;
        long now = seconds * 1_000 + 123;
        long cutoff = now - STALE_MS;
        fx.putMember("111", cutoff);
        fx.putMember("222", cutoff - 1);
        fx.putMember("333", cutoff + 5);
        List<Object> keys = List.of(fx.indexKey);
        String stale = Long.toString(STALE_MS);

        List<Object> first = fx.evalAt(SpectateScripts.PICK, seconds, 123_999, RScript.ReturnType.MULTI, keys, "0.0", stale);
        List<Object> last = fx.evalAt(SpectateScripts.PICK, seconds, 123_999, RScript.ReturnType.MULTI, keys, "0.999999", stale);
        List<Object> middle = fx.evalAt(SpectateScripts.PICK, seconds, 123_999, RScript.ReturnType.MULTI, keys, "0.5", stale);

        assertThat(first).hasSize(3);
        assertThat(first.get(0)).as("now = 秒 × 1000 + ⌊微秒 / 1000⌋").isEqualTo(now);
        assertThat(text(first.get(1))).as("分数恰等于分界：还算活着（严格小于才过期）").isEqualTo("111");
        assertThat(text(first.get(2))).isEqualTo(Long.toString(cutoff));
        assertThat(text(last.get(1))).isEqualTo("333");
        assertThat(text(middle.get(1))).as("活着的只有 2 个：⌊0.5 × 2⌋ = 1").isEqualTo("333");

        // 时钟再走 1 ms：111 也过期了；再走 6 ms：一个都不剩
        List<Object> later = fx.evalAt(SpectateScripts.PICK, seconds, 124_000, RScript.ReturnType.MULTI, keys, "0.0", stale);
        List<Object> none = fx.evalAt(SpectateScripts.PICK, seconds, 129_000, RScript.ReturnType.MULTI, keys, "0.0", stale);
        assertThat(text(later.get(1))).isEqualTo("333");
        assertThat(none).as("全部过期：只回 now").containsExactly(now + 6);
        assertThat(fx.index()).as("选场只读").hasSize(3);
    }

    @Test
    void 清扫的过期分界_钉住时钟_分数严格小于分界才摘() {
        long seconds = 1_900_000_000L;
        long now = seconds * 1_000 + 7;
        long cutoff = now - STALE_MS;
        fx.putMember("111", cutoff - 1);
        fx.putMember("222", cutoff);
        fx.putMember("333", cutoff + 1);
        fx.putMember("444", cutoff - 100_000);

        Long removed = fx.evalAt(SpectateScripts.SWEEP, seconds, 7_000, RScript.ReturnType.INTEGER, List.of(fx.indexKey), Long.toString(STALE_MS));

        assertThat(removed).isEqualTo(2);
        assertThat(fx.index()).containsOnlyKeys("222", "333");
    }

    @Test
    void 读快照与列表里的now_钉住时钟_秒乘1000加微秒除1000向下取整_回复的形状逐项钉住() {
        long seconds = 1_900_000_000L;
        long battleId = fx.battle(1);
        BattlePlacement placement = placement(battleId, 3, 1_899_999_990_000L);
        fx.putPlacement(placement);
        fx.putMember(member(battleId), 1_899_999_990_000L);
        fx.putMember("zzz", 1_899_999_980_000L);

        List<Object> read = fx.evalAt(SpectateScripts.READ, seconds, 999_999, RScript.ReturnType.MULTI,
                List.of(fx.indexKey, SpectateRedisFixture.placementKey(battleId)), member(battleId));
        List<Object> absent = fx.evalAt(SpectateScripts.READ, seconds, 0, RScript.ReturnType.MULTI,
                List.of(fx.indexKey, SpectateRedisFixture.placementKey(fx.battle(2))), member(fx.battle(2)));
        List<Object> list = fx.evalAt(SpectateScripts.LIST, seconds, 1_999, RScript.ReturnType.MULTI, List.of(fx.indexKey), "10");

        assertThat(read).hasSize(5);
        assertThat(read.get(0)).isEqualTo(seconds * 1_000 + 999);
        assertThat(read.get(1)).as("已公开").isEqualTo(1L);
        assertThat(read.get(2)).as("落点在").isEqualTo(SpectateScripts.RECORD_PRESENT);
        assertThat(text(read.get(3))).as("a 字段 = attempt 的十进制").isEqualTo("3");
        assertThat((byte[]) read.get(4)).isEqualTo(placement.toByteArray());
        assertThat(absent.get(0)).isEqualTo(seconds * 1_000);
        assertThat(absent.subList(1, 3)).as("未公开、落点不在").containsExactly(0L, SpectateScripts.RECORD_ABSENT);
        assertThat((byte[]) absent.get(3)).isEmpty();
        assertThat((byte[]) absent.get(4)).isEmpty();
        assertThat(list).hasSize(5);
        assertThat(list.get(0)).isEqualTo(seconds * 1_000 + 1);
        assertThat(List.of(text(list.get(1)), text(list.get(2)), text(list.get(3)), text(list.get(4))))
                .containsExactly(member(battleId), "1899999990000", "zzz", "1899999980000");
    }

    // ================================================================ 随机选场：分布与基线用例

    @Test
    void 一万次随机选场_过期成员一次都挑不到_未过期的各占六分之一上下() {
        long now = fx.nowMs();
        List<String> alive = new ArrayList<>();
        for (int n = 1; n <= 5; n++) {
            fx.putMember(member(fx.battle(n)), now - n * 1_000L);
            alive.add(member(fx.battle(n)));
        }
        fx.putMember("not-a-battle", now - 500); // 非法成员没过期：照样会被挑中（由调用方剔除），这里算第 6 个
        alive.add("not-a-battle");
        for (int n = 6; n <= 8; n++) {
            fx.putMember(member(fx.battle(n)), now - STALE_MS - 60_000 - n);
        }
        Random random = new Random(20261008);
        Map<String, Integer> hits = new LinkedHashMap<>();

        for (int i = 0; i < 10_000; i++) {
            Pick pick = fx.store.pickRandom(random.nextDouble(), Deadline.after(10_000));
            assertThat(pick).as("第 %d 次", i).isInstanceOf(Pick.Member.class);
            hits.merge(((Pick.Member) pick).member(), 1, Integer::sum);
        }

        assertThat(hits.keySet()).as("过期的 3 个一次都没有被挑中（W8）").containsExactlyInAnyOrderElementsOf(alive);
        for (String member : alive) {
            assertThat(hits.get(member)).as("成员 %s 的命中数（期望 1667；固定种子，结果是确定的）", member).isBetween(1_450, 1_900);
        }
        assertThat(fx.index()).as("选场只读，不剔除任何成员").hasSize(9);
    }

    /** 163 随机选场的用法：至多挑 {@code tries} 次，挑到非法成员就按原串剔除后重挑。 */
    private OptionalLong pickLikeWatchBattle(int tries) {
        for (int i = 0; i < tries; i++) {
            Pick pick = fx.store.pickRandom(0.0, d());
            if (!(pick instanceof Pick.Member picked)) {
                return OptionalLong.empty();
            }
            OptionalLong battleId = SpectateRules.parseMember(picked.member());
            if (battleId.isPresent()) {
                return battleId;
            }
            assertThat(fx.store.evict(new Eviction.Invalid(picked.member()), d())).isTrue();
        }
        return OptionalLong.empty();
    }

    /** 移植 {@code TestPickRandomBattleNeverReturnsStaleOrGarbage}（spectate_test.go:161）：活跃战斗与坏成员混在一起时，只可能返回活跃战斗。 */
    @Test
    void 活跃战斗与过期成员_非法成员混在一起_选出来的只可能是活跃战斗() {
        long now = fx.nowMs();
        long fresh = fx.battle(1);
        long stale = fx.battle(2);
        fx.putMember(member(fresh), now - 1_000);
        fx.putMember(member(stale), now - STALE_MS - 60_000);
        fx.putMember("not-a-battle-id", now - 2_000); // 分数比 fresh 小：r = 0 时先挑中它

        for (int i = 0; i < 10; i++) {
            assertThat(pickLikeWatchBattle(MatchBudgets.RANDOM_PICK_TRIES)).as("第 %d 次：坏成员只能被剔除，不能被返回", i).hasValue(fresh);
        }

        assertThat(fx.index()).as("非法成员已被剔除；过期成员不归选场管（挑不到它），留给清扫").containsOnlyKeys(member(fresh), member(stale));
    }

    /**
     * 移植 {@code TestPickRandomBattleEvictsAllBadMembersAndReportsNone}（spectate_test.go:177）。Java 的差别：过期成员挑不到，所以也不由选场剔除——
     * 它的索引项等清扫器摘，落点记录靠自己的 TTL（基线是选场时现场 DEL 记录 + ZREM）。
     */
    @Test
    void 只剩过期与非法成员_选场回没有可看的_非法成员被剔除_过期的留给清扫_清扫不动落点() {
        long now = fx.nowMs();
        long stale = fx.battle(1);
        BattlePlacement stalePlacement = placement(stale, 1, now - STALE_MS - 60_000);
        fx.putPlacement(stalePlacement);
        fx.putMember(member(stale), now - STALE_MS - 60_000);
        fx.putMember("garbage", now - 1_000);

        assertThat(pickLikeWatchBattle(MatchBudgets.RANDOM_PICK_TRIES)).isEmpty();

        assertThat(fx.index()).containsOnlyKeys(member(stale));
        assertThat(fx.store.sweep(d())).isEqualTo(1);
        assertThat(fx.store.watchableCount(d())).isZero();
        assertThat(fx.placementOf(stale)).as("清扫只摘成员").contains(stalePlacement);
    }

    /**
     * 移植 {@code TestPickRandomBattleGivesUpAfterThreeAttempts}（spectate_test.go:839）。基线：5 个过期成员，每挑一次剔一个，3 次后放弃、剩 2 个。
     * Java：过期成员不在候选里，第一次就回「没有」，一个都不剔（「至多挑 3 次」只对非法成员有意义，由 163 的用例钉）。
     */
    @Test
    void 五个过期成员_第一次选场就回没有_索引原封不动_清扫一次摘完() {
        long now = fx.nowMs();
        for (int n = 1; n <= 5; n++) {
            fx.putMember(member(fx.battle(n)), now - STALE_MS - 60_000 - n);
        }

        Pick pick = fx.store.pickRandom(0.7, d());

        assertThat(pick).isInstanceOf(Pick.None.class);
        assertThat(((Pick.None) pick).redisNowMs()).isBetween(now, now + 60_000);
        assertThat(fx.index()).hasSize(5);
        assertThat(fx.store.sweep(d())).isEqualTo(5);
    }

    // ================================================================ 落点的损坏形状、被占成别的类型的键

    @Test
    void 落点的各种损坏形状都读成Corrupt_不抛也不折成不存在_单读与批读一致_同一批里的好记录不受连累() {
        long now = fx.nowMs();
        long good = fx.battle(1);
        long absent = fx.battle(2);
        long noPb = fx.battle(3);
        long noAttempt = fx.battle(4);
        long attemptMismatch = fx.battle(5);
        long garbagePb = fx.battle(6);
        long otherBattle = fx.battle(7);
        long wrongType = fx.battle(8);
        long noFields = fx.battle(9);
        BattlePlacement placement = placement(good, 1, now - 1_000);
        fx.putPlacement(placement);
        fx.putRawPlacement(noPb, "1", null);
        fx.putRawPlacement(noAttempt, null, placement(noAttempt, 1, now).toByteArray());
        fx.putRawPlacement(attemptMismatch, "2", placement(attemptMismatch, 1, now).toByteArray());
        fx.putRawPlacement(garbagePb, "1", GARBAGE_PB);
        fx.putRawPlacement(otherBattle, "1", placement(fx.battle(99), 1, now).toByteArray());
        fx.setString(SpectateRedisFixture.placementKey(wrongType), "不是 HASH");
        fx.putRawPlacement(noFields, null, null);
        List<Long> corrupt = List.of(noPb, noAttempt, attemptMismatch, garbagePb, otherBattle, wrongType, noFields);
        List<Long> all = new ArrayList<>(List.of(good, absent));
        all.addAll(corrupt);

        Map<Long, Record> batch = fx.store.readPlacements(all, d());

        assertThat(batch).hasSize(9);
        assertThat(batch.get(good)).isEqualTo(new Record.Found(placement));
        assertThat(batch.get(absent)).isInstanceOf(Record.Absent.class);
        for (long battleId : corrupt) {
            String name = "battle(" + (battleId - fx.battle(0)) + ")";
            assertThat(batch.get(battleId)).as(name).isInstanceOf(Record.Corrupt.class);
            assertThat(((Record.Corrupt) batch.get(battleId)).why()).as(name).isNotBlank();
            assertThat(fx.store.read(battleId, d()).record()).as("单读 %s", name).isInstanceOf(Record.Corrupt.class);
            assertThat(fx.placementExists(battleId)).as("读不修数据 %s", name).isTrue();
        }
        assertThat(fx.store.read(good, d()).record()).isEqualTo(new Record.Found(placement));
        // 两个口径的差别只在 a 字段：179 补签（PlacementStore.read）只看 pb，这两条它读得出来；观战要拿 attempt 做守护，所以算损坏
        assertThat(fx.placementOf(noAttempt)).isPresent();
        assertThat(fx.placementOf(attemptMismatch)).isPresent();
    }

    /** 与内存替身的已知差别（见 {@code InMemorySpectateStore} 的类注释）：脚本只比 a 字段，认不出 pb 坏没坏。163 / 开局钩子只对读到的好记录调，走不到。 */
    @Test
    void a字段还对得上而pb坏了的落点_房间已死的剔除照删_公开照登记_a对不上的都不动() {
        long now = fx.nowMs();
        long matching = fx.battle(1);
        long published = fx.battle(2);
        long mismatched = fx.battle(3);
        fx.putRawPlacement(matching, "1", GARBAGE_PB);
        fx.putMember(member(matching), now - 1_000);
        fx.putRawPlacement(published, "2", GARBAGE_PB);
        fx.putRawPlacement(mismatched, "3", GARBAGE_PB);

        assertThat(fx.store.evict(new Eviction.Dead(matching, 1), d())).isTrue();
        assertThat(fx.store.publish(placement(published, 2, now - 2_000), d())).isTrue();
        assertThat(fx.store.evict(new Eviction.Dead(mismatched, 1), d())).isFalse();
        assertThat(fx.store.publish(placement(mismatched, 1, now - 2_000), d())).isFalse();

        assertThat(fx.placementExists(matching)).isFalse();
        assertThat(fx.placementExists(mismatched)).isTrue();
        assertThat(fx.index()).containsOnly(Map.entry(member(published), now - 2_000));
    }

    @Test
    void 索引键被占成别的类型_碰索引的读写都抛依赖异常_不折成空列表_也不会先把落点删掉() {
        long now = fx.nowMs();
        long battleId = fx.battle(1);
        BattlePlacement placement = placement(battleId, 1, now - STALE_MS - 60_000);
        fx.putPlacement(placement);
        fx.setString(fx.indexKey, "不是 ZSET");
        Class<Deadline.DependencyException> failure = Deadline.DependencyException.class;

        assertThatThrownBy(() -> fx.store.pickRandom(0.5, d())).isInstanceOf(failure);
        assertThatThrownBy(() -> fx.store.list(20, d())).as("读索引失败不能冒充「当前没有可观战的战斗」").isInstanceOf(failure);
        assertThatThrownBy(() -> fx.store.read(battleId, d())).isInstanceOf(failure);
        assertThatThrownBy(() -> fx.store.publish(placement, d())).isInstanceOf(failure);
        assertThatThrownBy(() -> fx.store.evict(new Eviction.Invalid("junk"), d())).isInstanceOf(failure);
        assertThatThrownBy(() -> fx.store.evict(new Eviction.Missing(fx.battle(2)), d())).isInstanceOf(failure);
        assertThatThrownBy(() -> fx.store.evict(new Eviction.Dead(battleId, 1), d())).isInstanceOf(failure);
        assertThatThrownBy(() -> fx.store.evict(new Eviction.Stale(battleId, now), d())).isInstanceOf(failure);
        assertThatThrownBy(() -> fx.store.sweep(d())).isInstanceOf(failure);
        assertThatThrownBy(() -> fx.store.watchableCount(d())).isInstanceOf(failure);

        assertThat(fx.placementOf(battleId)).as("每段脚本读完再写：索引读不了就在第一条写之前失败，落点还在").contains(placement);
        assertThat(redis.<String>getBucket(fx.indexKey, StringCodec.INSTANCE).get()).isEqualTo("不是 ZSET");
        assertThat(fx.store.readPlacements(List.of(battleId), d())).as("不碰索引的批读照常").containsOnly(Map.entry(battleId, new Record.Found(placement)));
    }

    @Test
    void 标记键被占成别的类型_入口读_抢_删_批读都抛依赖异常_键原封不动() {
        long playerId = fx.pid(1);
        String key = SpectateRedisFixture.markKey(playerId);
        redis.<String, String>getMap(key, StringCodec.INSTANCE).put("f", "v");
        fx.pexpire(key, 600_000);
        Map<String, String> before = fx.dump();
        Class<Deadline.DependencyException> failure = Deadline.DependencyException.class;

        assertThatThrownBy(() -> fx.store.entry(playerId, d())).isInstanceOf(failure);
        assertThatThrownBy(() -> fx.store.acquire(playerId, mark(fx.battle(1)), d())).isInstanceOf(failure);
        assertThatThrownBy(() -> fx.store.release(playerId, mark(fx.battle(1)), d())).isInstanceOf(failure);
        assertThatThrownBy(() -> fx.store.marksOf(List.of(fx.pid(2), playerId), d())).as("不返回半份结果").isInstanceOf(failure);

        assertThat(fx.type(key)).isEqualTo("hash");
        assertThat(fx.dump()).isEqualTo(before);
    }

    // ================================================================ 字节原样

    @Test
    void 不是合法UTF8的脏成员_原样读出_按读到的原串摘得掉_文本的脏成员两种给法都摘得掉() {
        long now = fx.nowMs();
        byte[] binary = {(byte) 0xFF, (byte) 0xFE, 'x', (byte) 0x80};
        fx.putMemberBytes(binary, now - 3_000);
        fx.putMemberBytes("垃圾成员".getBytes(StandardCharsets.UTF_8), now - 2_000);
        fx.putMemberBytes("另一个".getBytes(StandardCharsets.UTF_8), now - 1_000);

        Pick.Member picked = (Pick.Member) fx.store.pickRandom(0.0, d());
        List<Scored> listed = fx.store.list(10, d()).members();

        assertThat(picked.member().getBytes(StandardCharsets.ISO_8859_1)).as("成员按字节一一对应：读出来不失真").isEqualTo(binary);
        assertThat(SpectateRules.parseMember(picked.member())).isEmpty();
        assertThat(listed).hasSize(3);
        assertThat(listed.get(2).member()).isEqualTo(picked.member());
        assertThat(fx.store.evict(new Eviction.Invalid(picked.member()), d())).as("读到什么就交回什么：摘得掉").isTrue();
        assertThat(fx.store.evict(new Eviction.Invalid(listed.get(1).member()), d())).as("UTF-8 文本的脏成员，交回读到的串").isTrue();
        assertThat(fx.store.evict(new Eviction.Invalid("另一个"), d())).as("调用方自己给的文本（带 > 0xFF 的字符）按 UTF-8 发出").isTrue();
        assertThat(fx.index()).isEmpty();
    }

    /** 已知限制（见 {@code RedissonSpectateStore} 的类注释）：标记按 UTF-8 文本读写，不是合法 UTF-8 的字节串读出来失真，按值比不上——只能等 TTL。 */
    @Test
    void 不是合法UTF8的脏标记_如实报有标记_不抛_按读到的值删不掉_它带着TTL自己会过期() {
        long playerId = fx.pid(1);
        fx.putMarkBytes(playerId, new byte[] {(byte) 0xFF, (byte) 0xFE, ':', 'x'});

        Entry entry = fx.store.entry(playerId, d());

        assertThat(entry.mark()).isPresent();
        assertThat(SpectateRules.decodeMark(entry.mark().get())).as("解析不了：调用方当脏值处理").isEmpty();
        assertThat(fx.store.acquire(playerId, mark(fx.battle(1)), d())).isEqualTo(Acquire.BUSY);
        assertThat(fx.store.release(playerId, entry.mark().get(), d())).as("解码失真，按值比不上：没删，也没有删错别的").isFalse();
        assertThat(fx.markTtlMs(playerId)).isBetween(1L, 360_000L);
    }

    @Test
    void 脏分数_小数向下取整_正负无穷收到long的两端() {
        for (String[] entry : new String[][] {{"+inf", "top"}, {"1900000000000.75", "fraction"}, {"-1.5", "negative"}, {"-inf", "bottom"}}) {
            redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, "return redis.call('ZADD', KEYS[1], ARGV[1], ARGV[2])",
                    RScript.ReturnType.INTEGER, List.<Object>of(fx.indexKey), entry[0], entry[1]);
        }

        Listed listed = fx.store.list(10, d());
        Pick pick = fx.store.pickRandom(0.0, d());

        assertThat(listed.members()).containsExactly(new Scored("top", Long.MAX_VALUE), new Scored("fraction", 1_900_000_000_000L),
                new Scored("negative", -2), new Scored("bottom", Long.MIN_VALUE));
        assertThat(pick).as("未过期的两个里分数最小的").isEqualTo(new Pick.Member("fraction", 1_900_000_000_000L, ((Pick.Member) pick).redisNowMs()));
        assertThat(fx.store.sweep(d())).as("负分数与负无穷都早于分界").isEqualTo(2);
    }

    // ================================================================ 批量

    @Test
    void 一页50场_列表_批读落点_批读标记各一次调用读全() {
        long now = fx.nowMs();
        List<Long> battles = new ArrayList<>();
        List<Long> players = new ArrayList<>();
        Map<Long, String> marks = new LinkedHashMap<>();
        for (int n = 1; n <= MatchBudgets.WATCHABLE_LIST_MAX; n++) {
            BattlePlacement placement = placement(fx.battle(n), 1, now - n * 100L);
            fx.putPlacement(placement);
            assertThat(fx.store.publish(placement, d())).isTrue();
            battles.add(fx.battle(n));
            players.add(fx.pid(n));
            if (n % 2 == 0) {
                marks.put(fx.pid(n), mark(fx.battle(n)));
                fx.putMark(fx.pid(n), marks.get(fx.pid(n)));
            }
        }

        Listed listed = fx.store.list(MatchBudgets.WATCHABLE_LIST_MAX, d());
        Map<Long, Record> records = fx.store.readPlacements(battles, d());

        assertThat(listed.members()).extracting(Scored::member).as("开局最晚的在前").containsExactlyElementsOf(battles.stream().map(SpectateRules::member).toList());
        assertThat(records).hasSize(50);
        assertThat(records.keySet()).containsExactlyElementsOf(battles);
        assertThat(records.values()).allSatisfy(record -> assertThat(record).isInstanceOf(Record.Found.class));
        assertThat(((Record.Found) records.get(fx.battle(50))).placement().getCreatedAtMs()).isEqualTo(now - 5_000);
        assertThat(fx.store.marksOf(players, d())).containsExactlyEntriesOf(marks);
    }

    @Test
    void 异步剔除_整批走一个管道_某一条在Redis上报错不连累其余() {
        long now = fx.nowMs();
        long orphan = fx.battle(1);
        long wrongType = fx.battle(2);
        fx.putMember("junk", now - 1_000);
        fx.putMember(member(orphan), now - 1_000);
        fx.putMember(member(wrongType), now - 1_000);
        fx.setString(SpectateRedisFixture.placementKey(wrongType), "不是 HASH"); // Dead 模式读它的 a 字段会报错

        assertThatCode(() -> fx.store.evictAsync(List.of(new Eviction.Invalid("junk"), new Eviction.Dead(wrongType, 1), new Eviction.Missing(orphan))))
                .doesNotThrowAnyException();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(fx.index()).containsOnlyKeys(member(wrongType)));
        assertThat(fx.type(SpectateRedisFixture.placementKey(wrongType))).as("报错的那一条什么都没改").isEqualTo("string");
    }

    // ================================================================ 生产索引键

    @Test
    void 缺省构造用的索引键就是RedisKeys的那一把_与夹具自己的索引互不相干() {
        RedissonSpectateStore production = new RedissonSpectateStore(redis);
        long battleId = fx.battle(1);
        BattlePlacement placement = placement(battleId, 1, fx.nowMs() - 1_000);
        fx.putPlacement(placement);
        String member = fx.trackProductionMember(battleId);

        assertThat(production.publish(placement, d())).isTrue();

        assertThat(RedisKeys.matchWatchable()).isEqualTo("xm:{match}:watchable");
        assertThat(fx.indexOf(RedisKeys.matchWatchable())).containsEntry(member, placement.getCreatedAtMs());
        assertThat(fx.index()).as("夹具的索引键没有被碰").isEmpty();
        assertThat(production.read(battleId, d()).published()).isTrue();
        assertThat(fx.store.read(battleId, d()).published()).isFalse();
        assertThat(production.watchableCount(d())).isPositive();

        assertThat(production.evict(new Eviction.Dead(battleId, 1), d())).isTrue();
        assertThat(fx.indexOf(RedisKeys.matchWatchable())).doesNotContainKey(member);
    }

    // ================================================================ 故障、线程、并发

    @Test
    void Redis客户端不可用_每个同步方法都抛依赖异常_各自在截止附近返回_两个尽力方法不抛() {
        RedissonClient closed = TicketRedisFixture.connect();
        RedissonSpectateStore store = new RedissonSpectateStore(closed, fx.indexKey);
        long playerId = fx.pid(1);
        long battleId = fx.battle(1);
        String value = mark(battleId);
        BattlePlacement placement = placement(battleId, 1, fx.nowMs());
        fx.putPlacement(placement);
        fx.putMark(playerId, value);
        assertThat(store.entry(playerId, d()).mark()).as("关之前是通的").contains(value);
        closed.shutdown();
        Class<Deadline.DependencyException> failure = Deadline.DependencyException.class;

        long started = System.nanoTime();
        assertThatThrownBy(() -> store.entry(playerId, Deadline.after(1_500))).isInstanceOf(failure);
        assertThatThrownBy(() -> store.acquire(fx.pid(2), value, Deadline.after(1_500))).isInstanceOf(failure);
        assertThatThrownBy(() -> store.release(playerId, value, Deadline.after(1_500))).isInstanceOf(failure);
        assertThatThrownBy(() -> store.marksOf(List.of(playerId), Deadline.after(1_500))).isInstanceOf(failure);
        assertThatThrownBy(() -> store.read(battleId, Deadline.after(1_500))).isInstanceOf(failure);
        assertThatThrownBy(() -> store.pickRandom(0.5, Deadline.after(1_500))).isInstanceOf(failure);
        assertThatThrownBy(() -> store.evict(new Eviction.Dead(battleId, 1), Deadline.after(1_500))).isInstanceOf(failure);
        assertThatThrownBy(() -> store.publish(placement, Deadline.after(1_500))).isInstanceOf(failure);
        assertThatThrownBy(() -> store.list(20, Deadline.after(1_500))).as("不折成空列表").isInstanceOf(failure);
        assertThatThrownBy(() -> store.readPlacements(List.of(battleId), Deadline.after(1_500))).as("不折成「不存在」").isInstanceOf(failure);
        assertThatThrownBy(() -> store.sweep(Deadline.after(1_500))).isInstanceOf(failure);
        assertThatThrownBy(() -> store.watchableCount(Deadline.after(1_500))).isInstanceOf(failure);
        assertThatCode(() -> store.releaseAsync(playerId, value)).doesNotThrowAnyException();
        assertThatCode(() -> store.evictAsync(List.of(new Eviction.Dead(battleId, 1), new Eviction.Invalid("junk")))).doesNotThrowAnyException();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(elapsedMs).as("12 次调用各自不晚于自己的 1.5 s 截止").isLessThan(30_000);
        assertThat(fx.markOf(playerId)).as("失败的调用什么都没改").contains(value);
        assertThat(fx.placementOf(battleId)).contains(placement);
    }

    @Test
    void 在虚拟线程上读写_同163与开局钩子的用法() throws Exception {
        long playerId = fx.pid(1);
        long battleId = fx.battle(1);
        String value = mark(battleId);
        BattlePlacement placement = placement(battleId, 1, fx.nowMs() - 1_000);
        fx.putPlacement(placement);
        AtomicReference<Object> outcome = new AtomicReference<>();

        Thread thread = Thread.ofVirtual().name("test-spectate").start(() -> {
            try {
                Entry entry = fx.store.entry(playerId, d());
                boolean published = fx.store.publish(placement, d());
                Pick pick = fx.store.pickRandom(0.3, d());
                Snapshot snapshot = fx.store.read(battleId, d());
                Acquire acquired = fx.store.acquire(playerId, value, d());
                Map<Long, String> marks = fx.store.marksOf(List.of(playerId), d());
                boolean released = fx.store.release(playerId, value, d());
                outcome.set(List.of(entry, published, ((Pick.Member) pick).member(), snapshot.record(), acquired, marks, released));
            } catch (Throwable t) {
                outcome.set(t);
            }
        });

        assertThat(thread.join(Duration.ofSeconds(30))).isTrue();
        assertThat(outcome.get()).isEqualTo(List.of(new Entry(false, Optional.empty()), true, member(battleId), new Record.Found(placement),
                Acquire.OK, Map.of(playerId, value), true));
    }

    @Test
    void 并发抢同一个玩家的标记_恰好一个OK_其余都是BUSY_留下的是赢家的值() throws Exception {
        long playerId = fx.pid(1);
        int contenders = 16;
        List<String> values = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            values.add(mark(fx.battle(1 + i % 3)));
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            List<Future<Acquire>> futures = new ArrayList<>();
            for (String value : values) {
                Callable<Acquire> task = () -> {
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("起跑信号没有到");
                    }
                    return fx.store.acquire(playerId, value, Deadline.after(10_000));
                };
                futures.add(pool.submit(task));
            }
            start.countDown();

            List<String> winners = new ArrayList<>();
            int busy = 0;
            for (int i = 0; i < contenders; i++) {
                Acquire result = futures.get(i).get(30, TimeUnit.SECONDS);
                if (result == Acquire.OK) {
                    winners.add(values.get(i));
                } else {
                    assertThat(result).isEqualTo(Acquire.BUSY);
                    busy++;
                }
            }

            assertThat(winners).as("「没有标记才写」是一段脚本里的原子判定：16 条并发 163 只有一条抢到").hasSize(1);
            assertThat(busy).isEqualTo(contenders - 1);
            assertThat(fx.markOf(playerId)).contains(winners.get(0));
        } finally {
            pool.shutdownNow();
        }
    }
}
