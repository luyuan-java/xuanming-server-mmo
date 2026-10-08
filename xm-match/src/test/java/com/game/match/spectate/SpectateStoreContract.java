package com.game.match.spectate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.game.api.match.MatchBudgets;
import com.game.common.deadline.Deadline;
import com.game.match.proto.BattlePlacement;
import com.game.match.spectate.SpectateStore.Acquire;
import com.game.match.spectate.SpectateStore.Entry;
import com.game.match.spectate.SpectateStore.Eviction;
import com.game.match.spectate.SpectateStore.Listed;
import com.game.match.spectate.SpectateStore.Pick;
import com.game.match.spectate.SpectateStore.Record;
import com.game.match.spectate.SpectateStore.Scored;
import com.game.match.spectate.SpectateStore.Snapshot;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link SpectateStore} 的契约测试：接口注释与规格 §4.3 九段脚本的每一条承诺各钉一例（判定顺序、按值删、四种剔除各自的条件、
 * attempt 一致才公开、<b>每个可变方法连调两次存储的最终状态不变</b>）。内存替身（{@code InMemorySpectateStoreTest}）与 Redis 实现
 * （W1 的 {@code RedissonSpectateStoreIntegrationTest}）各继承一份、跑同一套断言——163 / 164 / 开局钩子的组件测试都建在内存替身上，
 * 替身与真实现不许漂移。
 *
 * <p>用例不依赖「拨时钟」（真 Redis 的 {@code TIME} 拨不动）：「过期的成员」靠写入一个旧分数（{@code nowMs() − 360 s − 余量}）摆出来；
 * 时刻断言夹在调用前后各读一次存储时间之间；TTL 断言留 {@link #TTL_SLACK} 的余量。玩家号、战斗号经钩子分配（{@link #pid} / {@link #battle}）：
 * Redis 版每个用例用随机的一段，用完只删自己的键。<b>可观战索引是全局的一把键</b>：这些用例假定每个用例开始时索引是空的、期间没有别人写它
 * （内存版每个用例一个新存储；Redis 版每个用例给存储一把自己的索引键——同槽、以生产键为前缀——不去清、也不受别人影响那把全局的键）。
 * 恰好卡在「存储时间」上的边界（分数恰在过期分界、TTL 恰好 360 s、重放不刷新 TTL）：内存版用手拨时钟钉，Redis 版用钉住时钟的脚本副本与改短 TTL 钉。
 */
public abstract class SpectateStoreContract {

    /** TTL 断言的余量：真 Redis 上从写入到读回会流逝一点时间。 */
    protected static final long TTL_SLACK = 15_000;
    protected static final long MARK_TTL = MatchBudgets.WATCHING_TTL_SECONDS * 1_000L;
    /** 比过期分界再早这么多的分数 = 必然过期；比「现在」早这么多的分数 = 必然没过期。 */
    private static final long CLEARLY = 60_000;

    // ================================================================ 钩子（两种实现各自提供）

    protected abstract SpectateStore store();

    /** 本用例的第 n 号玩家（非 0；同一个 n 返回同一个号）。 */
    protected abstract long pid(int n);

    /** 本用例的第 n 场战斗（非 0；同一个 n 返回同一个号）。 */
    protected abstract long battle(int n);

    /** 存储的时间（Redis {@code TIME} / 手拨时钟），Unix 毫秒。 */
    protected abstract long nowMs();

    /** 让这名玩家的票据键存在（任意状态的一张票）。 */
    protected abstract void givenTicket(long playerId);

    /** 直接放一个观战标记（任意值，含脏值），TTL 360 s。 */
    protected abstract void givenMark(long playerId, String value);

    /** 直接看存储里的标记。 */
    protected abstract Optional<String> markOf(long playerId);

    /** 标记剩余的 TTL（毫秒）；没有为 -1。 */
    protected abstract long markTtlMs(long playerId);

    /** 写一条好的落点记录（attempt 字段与消息一致，同 {@code PlacementStore.write}）。 */
    protected abstract void givenPlacement(BattlePlacement placement);

    /**
     * 摆一条「键在、但不是好记录」的落点，而且<b>没有可比对的 attempt</b>（Redis 版：HASH 里没有 {@code a} 字段、{@code pb} 是解析不了的字节）。
     * 别的损坏形状（{@code a} 在而 {@code pb} 坏、键被占成别的类型……）由 Redis 版自己的用例覆盖。
     */
    protected abstract void givenCorruptPlacement(long battleId);

    /** 落点键此刻在不在（好的坏的都算在）。 */
    protected abstract boolean placementExists(long battleId);

    /** 直接看存储里的落点记录（不在或损坏为空）。 */
    protected abstract Optional<BattlePlacement> placementOf(long battleId);

    /** 直接往可观战索引里放一个成员（任意串）。 */
    protected abstract void givenMember(String member, long score);

    /** 直接看可观战索引：成员 → 分数。 */
    protected abstract Map<String, Long> index();

    // ================================================================ 小工具

    protected static Deadline d() {
        return Deadline.after(5_000);
    }

    protected static BattlePlacement placement(long battleId, int attempt, long createdAtMs) {
        return BattlePlacement.newBuilder().setBattleId(battleId).setBattleNodeId(1).setBattleInstanceId("battle-inst-" + attempt)
                .setRpcHost("127.0.0.1").setRpcPort(21200 + attempt).setAttempt(attempt).setMode(3).setBattleConfigId(7)
                .addPlayerNames("甲").addPlayerNames("乙").setCreatedAtMs(createdAtMs).setDeadlineMs(createdAtMs + 300_000).build();
    }

    protected static String mark(long battleId) {
        return SpectateRules.encodeMark(battleId, SpectateRules.newNonce());
    }

    private static String member(long battleId) {
        return SpectateRules.member(battleId);
    }

    /** 一个必然已过期的分数。 */
    private long staleScore() {
        return nowMs() - MatchBudgets.SPECTATE_STALE_MS - CLEARLY;
    }

    /** 一个必然没过期的分数（比现在早 {@code agoMs}）。 */
    private long freshScore(long agoMs) {
        return nowMs() - agoMs;
    }

    // ================================================================ S_W_ENTRY

    @Test
    void entry_没有票也没有标记() {
        Entry entry = store().entry(pid(1), d());

        assertThat(entry.hasTicket()).isFalse();
        assertThat(entry.mark()).isEmpty();
    }

    @Test
    void entry_一次读出有没有票与标记的原值_只看自己的玩家号_脏标记原样返回() {
        String value = mark(battle(1));
        givenTicket(pid(1));
        givenMark(pid(1), value);
        givenMark(pid(2), "不是合法的标记");

        Entry both = store().entry(pid(1), d());
        Entry garbage = store().entry(pid(2), d());
        Entry nobody = store().entry(pid(3), d());

        assertThat(both.hasTicket()).isTrue();
        assertThat(both.mark()).contains(value);
        assertThat(garbage.hasTicket()).isFalse();
        assertThat(garbage.mark()).as("脏值不在存储里解析：原样交给调用方").contains("不是合法的标记");
        assertThat(nobody.hasTicket()).isFalse();
        assertThat(nobody.mark()).isEmpty();
    }

    // ================================================================ S_W_ACQUIRE

    @Test
    void acquire_没有票也没有标记_写入_TTL是360秒() {
        String value = mark(battle(1));

        assertThat(store().acquire(pid(1), value, d())).isEqualTo(Acquire.OK);

        assertThat(markOf(pid(1))).contains(value);
        assertThat(markTtlMs(pid(1))).isBetween(MARK_TTL - TTL_SLACK, MARK_TTL);
    }

    @Test
    void acquire_有票_回QUEUED_不写标记() {
        givenTicket(pid(1));

        assertThat(store().acquire(pid(1), mark(battle(1)), d())).isEqualTo(Acquire.QUEUED);

        assertThat(markOf(pid(1))).as("原子的「没有票据 ∧ SET NX」：有票就不写").isEmpty();
    }

    @Test
    void acquire_同值重放回OK_别的值回BUSY_原标记都不动() {
        String mine = mark(battle(1));
        String other = mark(battle(1));
        assertThat(store().acquire(pid(1), mine, d())).isEqualTo(Acquire.OK);

        assertThat(store().acquire(pid(1), mine, d())).as("命中自己写下的值：重发不误报 16016").isEqualTo(Acquire.OK);
        assertThat(store().acquire(pid(1), other, d())).as("同一场、不同 nonce 也是别人的").isEqualTo(Acquire.BUSY);
        assertThat(store().acquire(pid(1), mark(battle(2)), d())).isEqualTo(Acquire.BUSY);

        assertThat(markOf(pid(1))).contains(mine);
    }

    @Test
    void acquire_判定顺序_有票先于标记_哪怕标记就是自己的值() {
        String mine = mark(battle(1));
        assertThat(store().acquire(pid(1), mine, d())).isEqualTo(Acquire.OK);
        givenTicket(pid(1));

        assertThat(store().acquire(pid(1), mine, d())).as("重发的第二轮之前建出了票据：回 QUEUED，首轮写下的标记还在，由调用方按值释放")
                .isEqualTo(Acquire.QUEUED);
        assertThat(markOf(pid(1))).contains(mine);
    }

    // ================================================================ S_W_RELEASE

    @Test
    void release_值相等才删_删过再删是false_值不等不动() {
        String mine = mark(battle(1));
        String other = mark(battle(1));
        givenMark(pid(1), mine);

        assertThat(store().release(pid(1), other, d())).as("别的请求的值：不删（W3）").isFalse();
        assertThat(markOf(pid(1))).contains(mine);
        assertThat(store().release(pid(1), mine, d())).isTrue();
        assertThat(markOf(pid(1))).isEmpty();
        assertThat(store().release(pid(1), mine, d())).as("幂等").isFalse();
        assertThat(store().release(pid(2), mine, d())).as("没有标记").isFalse();
    }

    @Test
    void release_脏标记按读到的原串也删得掉() {
        givenMark(pid(1), "garbage:値");

        assertThat(store().release(pid(1), "garbage:値", d())).isTrue();

        assertThat(markOf(pid(1))).isEmpty();
    }

    @Test
    void 空串的脏标记_读得到_挡着别人抢_按原串删得掉() {
        givenMark(pid(1), "");

        Entry entry = store().entry(pid(1), d());
        assertThat(entry.mark()).as("空串也是「有标记」：不能和没有标记混为一谈，否则它会让这名玩家一直抢不到").contains("");
        assertThat(store().marksOf(List.of(pid(1)), d())).containsOnly(Map.entry(pid(1), ""));
        assertThat(store().acquire(pid(1), mark(battle(1)), d())).isEqualTo(Acquire.BUSY);

        assertThat(store().release(pid(1), "", d())).isTrue();

        assertThat(markOf(pid(1))).isEmpty();
        assertThat(store().entry(pid(1), d()).mark()).isEmpty();
        assertThatThrownBy(() -> store().release(pid(1), null, d())).as("null 才是调用方的错").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store().acquire(pid(1), "", d())).as("抢标记的值必须是编出来的整串").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acquire_没写的两种结果再来一遍还是没写_QUEUED与BUSY都不动存储() {
        String held = mark(battle(1));
        givenMark(pid(1), held);
        givenTicket(pid(2));

        for (int round = 0; round < 2; round++) {
            assertThat(store().acquire(pid(1), mark(battle(2)), d())).as("第 %d 遍", round + 1).isEqualTo(Acquire.BUSY);
            assertThat(store().acquire(pid(2), mark(battle(2)), d())).as("第 %d 遍", round + 1).isEqualTo(Acquire.QUEUED);
        }

        assertThat(markOf(pid(1))).contains(held);
        assertThat(markOf(pid(2))).isEmpty();
    }

    @Test
    void releaseAsync_发出即返回_最终按值删掉_值不等的不动() {
        String mine = mark(battle(1));
        String kept = mark(battle(2));
        givenMark(pid(1), mine);
        givenMark(pid(2), kept);

        store().releaseAsync(pid(1), mine);
        store().releaseAsync(pid(2), mark(battle(2)));
        store().releaseAsync(pid(3), mine);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(markOf(pid(1))).isEmpty());
        assertThat(markOf(pid(2))).as("值不等：不删").contains(kept);
    }

    // ================================================================ MGET

    @Test
    void marksOf_只含有标记的玩家_值原样_空名单回空表() {
        String first = mark(battle(1));
        givenMark(pid(1), first);
        givenMark(pid(3), "脏值");

        Map<Long, String> marks = store().marksOf(List.of(pid(1), pid(2), pid(3)), d());

        assertThat(marks).containsOnly(Map.entry(pid(1), first), Map.entry(pid(3), "脏值"));
        assertThat(store().marksOf(List.of(pid(2)), d())).isEmpty();
        assertThat(store().marksOf(List.of(), d())).isEmpty();
    }

    @Test
    void marksOf_重复的玩家号只算一次_结果按名单的次序() {
        String third = mark(battle(3));
        String first = mark(battle(1));
        givenMark(pid(3), third);
        givenMark(pid(1), first);

        Map<Long, String> marks = store().marksOf(List.of(pid(3), pid(2), pid(1), pid(3)), d());

        assertThat(marks).containsExactly(Map.entry(pid(3), third), Map.entry(pid(1), first));
    }

    // ================================================================ S_W_READ

    @Test
    void read_落点在_未公开与已公开_时间取存储的时间() {
        BattlePlacement placement = placement(battle(1), 1, freshScore(1_000));
        givenPlacement(placement);

        long before = nowMs();
        Snapshot unpublished = store().read(battle(1), d());
        long after = nowMs();
        givenMember(member(battle(1)), placement.getCreatedAtMs());
        Snapshot published = store().read(battle(1), d());

        assertThat(unpublished.published()).isFalse();
        assertThat(unpublished.record()).isEqualTo(new Record.Found(placement));
        assertThat(unpublished.redisNowMs()).isBetween(before, after);
        assertThat(published.published()).isTrue();
        assertThat(published.record()).isEqualTo(new Record.Found(placement));
    }

    @Test
    void read_落点不在_成员在不在都如实报_不修任何东西() {
        givenMember(member(battle(2)), freshScore(1_000));

        Snapshot nothing = store().read(battle(1), d());
        Snapshot orphanMember = store().read(battle(2), d());

        assertThat(nothing.published()).isFalse();
        assertThat(nothing.record()).isInstanceOf(Record.Absent.class);
        assertThat(orphanMember.published()).as("成员在、记录不在：照实报，由调用方用 Missing 剔除").isTrue();
        assertThat(orphanMember.record()).isInstanceOf(Record.Absent.class);
        assertThat(index()).containsOnlyKeys(member(battle(2)));
    }

    @Test
    void read_落点损坏_回Corrupt_不抛也不折成不存在() {
        givenCorruptPlacement(battle(1));

        Snapshot snapshot = store().read(battle(1), d());

        assertThat(snapshot.record()).isInstanceOf(Record.Corrupt.class);
        assertThat(placementExists(battle(1))).as("读不修数据").isTrue();
    }

    // ================================================================ S_W_PICK

    @Test
    void pickRandom_索引为空_或只有过期成员_回None() {
        long before = nowMs();
        Pick empty = store().pickRandom(0.5, d());
        givenMember(member(battle(1)), staleScore());
        givenMember(member(battle(2)), staleScore() - 1);
        Pick onlyStale = store().pickRandom(0.0, d());

        assertThat(empty).isInstanceOf(Pick.None.class);
        assertThat(((Pick.None) empty).redisNowMs()).isGreaterThanOrEqualTo(before);
        assertThat(onlyStale).as("过期成员永远不会被挑中（W8）").isInstanceOf(Pick.None.class);
        assertThat(index()).as("只读：不在这里剔除").hasSize(2);
    }

    @Test
    void pickRandom_只在未过期的成员里按r取_r为0取分数最小的_r接近1取最大的() {
        givenMember(member(battle(1)), staleScore());
        givenMember(member(battle(2)), freshScore(30_000));
        givenMember(member(battle(3)), freshScore(20_000));
        givenMember(member(battle(4)), freshScore(10_000));

        Pick first = store().pickRandom(0.0, d());
        Pick middle = store().pickRandom(0.5, d());
        Pick last = store().pickRandom(0.999_999, d());

        assertThat(((Pick.Member) first).member()).as("3 个未过期的，⌊0 × 3⌋ = 0：分数最小的").isEqualTo(member(battle(2)));
        assertThat(((Pick.Member) middle).member()).as("⌊0.5 × 3⌋ = 1").isEqualTo(member(battle(3)));
        assertThat(((Pick.Member) last).member()).as("⌊0.999999 × 3⌋ = 2：分数最大的").isEqualTo(member(battle(4)));
        assertThat(((Pick.Member) last).score()).isEqualTo(index().get(member(battle(4))));
        assertThat(((Pick.Member) store().pickRandom(Math.nextDown(1.0), d())).member()).as("r 取 1 之前最大的 double：仍是最后一个，不越界")
                .isEqualTo(member(battle(4)));
        assertThat(((Pick.Member) store().pickRandom(Double.MIN_VALUE, d())).member()).as("r 取最小的正数（写成科学计数法）：第一个")
                .isEqualTo(member(battle(2)));
        for (double r : new double[] {0.0, 0.2, 0.34, 0.5, 0.67, 0.9, 0.999_999}) {
            assertThat(((Pick.Member) store().pickRandom(r, d())).member()).as("r=%s", r).isNotEqualTo(member(battle(1)));
        }
    }

    @Test
    void pickRandom_同分的成员按成员字符串升序排_次序是确定的() {
        long score = freshScore(5_000);
        givenMember("200", score);
        givenMember("100", score);
        givenMember("300", score);

        assertThat(((Pick.Member) store().pickRandom(0.0, d())).member()).isEqualTo("100");
        assertThat(((Pick.Member) store().pickRandom(0.34, d())).member()).as("⌊0.34 × 3⌋ = 1").isEqualTo("200");
        assertThat(((Pick.Member) store().pickRandom(0.99, d())).member()).isEqualTo("300");
        assertThat(((Pick.Member) store().pickRandom(0.99, d())).score()).isEqualTo(score);
    }

    @Test
    void pickRandom_非法成员原样返回_由调用方剔除() {
        givenMember("not-a-battle", freshScore(1_000));

        Pick pick = store().pickRandom(0.0, d());

        assertThat(((Pick.Member) pick).member()).isEqualTo("not-a-battle");
        assertThat(SpectateRules.parseMember(((Pick.Member) pick).member())).isEmpty();
    }

    // ================================================================ S_W_EVICT

    @Test
    void evict_非法成员_无条件摘掉这个成员_再摘是false() {
        givenMember("not-a-battle", freshScore(1_000));
        givenMember(member(battle(1)), freshScore(1_000));

        assertThat(store().evict(new Eviction.Invalid("not-a-battle"), d())).isTrue();
        assertThat(store().evict(new Eviction.Invalid("not-a-battle"), d())).as("幂等").isFalse();

        assertThat(index()).containsOnlyKeys(member(battle(1)));
    }

    @Test
    void evict_缺记录_落点此刻仍不在才摘成员_落点在就什么都不动_永不删落点() {
        givenMember(member(battle(1)), freshScore(1_000));
        givenMember(member(battle(2)), freshScore(1_000));
        BattlePlacement prewritten = placement(battle(2), 1, freshScore(500));
        givenPlacement(prewritten); // 读到「不在」之后才预写出来的记录

        assertThat(store().evict(new Eviction.Missing(battle(1)), d())).isTrue();
        assertThat(store().evict(new Eviction.Missing(battle(2)), d())).as("落点已经（重新）存在：不摘成员").isFalse();
        assertThat(store().evict(new Eviction.Missing(battle(3)), d())).as("落点不在、成员也不在：没有可摘的").isFalse();

        assertThat(index()).containsOnlyKeys(member(battle(2)));
        assertThat(placementOf(battle(2))).as("这个模式永不删落点").contains(prewritten);
    }

    @Test
    void evict_房间已死_attempt没变才删落点并摘成员_被改写过就都不动() {
        givenPlacement(placement(battle(1), 1, freshScore(40_000)));
        givenMember(member(battle(1)), freshScore(40_000));
        BattlePlacement rewritten = placement(battle(2), 2, freshScore(40_000));
        givenPlacement(rewritten); // 读到 attempt = 1 之后，gather 换节点改写成了 attempt = 2
        givenMember(member(battle(2)), freshScore(40_000));

        assertThat(store().evict(new Eviction.Dead(battle(1), 1), d())).isTrue();
        assertThat(store().evict(new Eviction.Dead(battle(2), 1), d())).as("attempt 守护（W7）：落点已被改写").isFalse();

        assertThat(placementExists(battle(1))).isFalse();
        assertThat(index()).containsOnlyKeys(member(battle(2)));
        assertThat(placementOf(battle(2))).contains(rewritten);
        assertThat(store().evict(new Eviction.Dead(battle(1), 1), d())).as("幂等：都没了").isFalse();
    }

    @Test
    void evict_房间已死_落点已经不在_只剩成员也摘掉_未公开的场只删落点() {
        givenMember(member(battle(1)), freshScore(40_000));
        givenPlacement(placement(battle(2), 1, freshScore(40_000)));

        assertThat(store().evict(new Eviction.Dead(battle(1), 1), d())).as("落点已不在：成员照摘").isTrue();
        assertThat(store().evict(new Eviction.Dead(battle(2), 1), d())).as("没公开过：只有落点可删，也算摘了").isTrue();

        assertThat(index()).isEmpty();
        assertThat(placementExists(battle(2))).isFalse();
    }

    @Test
    void evict_过期_成员的分数仍小于分界才删落点并摘成员_分数不小于分界或成员不在都不动() {
        long cutoff = SpectateRules.staleCutoff(nowMs());
        givenMember(member(battle(1)), cutoff - 1);
        givenPlacement(placement(battle(1), 1, cutoff - 1));
        givenMember(member(battle(2)), cutoff);
        BattlePlacement alive = placement(battle(2), 1, cutoff);
        givenPlacement(alive);
        BattlePlacement unpublished = placement(battle(3), 1, cutoff - 1);
        givenPlacement(unpublished);

        assertThat(store().evict(new Eviction.Stale(battle(1), cutoff), d())).isTrue();
        assertThat(store().evict(new Eviction.Stale(battle(2), cutoff), d())).as("恰在分界上：不算过期").isFalse();
        assertThat(store().evict(new Eviction.Stale(battle(3), cutoff), d())).as("成员不在索引里：不动落点").isFalse();

        assertThat(index()).containsOnlyKeys(member(battle(2)));
        assertThat(placementExists(battle(1))).isFalse();
        assertThat(placementOf(battle(2))).contains(alive);
        assertThat(placementOf(battle(3))).contains(unpublished);
    }

    @Test
    void evict_缺记录与过期_原样再来一遍是false_什么都不再改() {
        long cutoff = SpectateRules.staleCutoff(nowMs());
        givenMember(member(battle(1)), freshScore(1_000));
        givenMember(member(battle(2)), cutoff - 1);
        givenPlacement(placement(battle(2), 1, cutoff - 1));
        givenMember(member(battle(3)), freshScore(1_000));

        assertThat(store().evict(new Eviction.Missing(battle(1)), d())).isTrue();
        assertThat(store().evict(new Eviction.Missing(battle(1)), d())).as("重放：成员已不在").isFalse();
        assertThat(store().evict(new Eviction.Stale(battle(2), cutoff), d())).isTrue();
        assertThat(store().evict(new Eviction.Stale(battle(2), cutoff), d())).as("重放：成员已不在").isFalse();

        assertThat(index()).as("不相干的成员不受影响").containsOnlyKeys(member(battle(3)));
        assertThat(placementExists(battle(2))).isFalse();
    }

    @Test
    void 重放的剔除不误伤两次之间重新预写的落点_缺记录与房间已死都不动新记录() {
        // 第一遍：1 号场的落点不在、2 号场 attempt = 1 的房间已死，都摘掉了；随后同一个 battle_id 又有了新的落点（活动开局的号先于 gather 发出）
        givenMember(member(battle(1)), freshScore(40_000));
        assertThat(store().evict(new Eviction.Missing(battle(1)), d())).isTrue();
        givenPlacement(placement(battle(2), 1, freshScore(40_000)));
        assertThat(store().evict(new Eviction.Dead(battle(2), 1), d())).isTrue();
        BattlePlacement rewrittenFirst = placement(battle(1), 1, freshScore(500));
        BattlePlacement rewrittenSecond = placement(battle(2), 2, freshScore(500));
        givenPlacement(rewrittenFirst);
        givenPlacement(rewrittenSecond);

        assertThat(store().evict(new Eviction.Missing(battle(1)), d())).as("迟到的重发：落点又在了").isFalse();
        assertThat(store().evict(new Eviction.Dead(battle(2), 1), d())).as("迟到的重发：attempt 已经是 2").isFalse();

        assertThat(placementOf(battle(1))).contains(rewrittenFirst);
        assertThat(placementOf(battle(2))).contains(rewrittenSecond);
    }

    @Test
    void 损坏的落点_读成Corrupt_缺记录与房间已死的剔除都不动它_公开不登记_过期照删() {
        long cutoff = SpectateRules.staleCutoff(nowMs());
        givenCorruptPlacement(battle(1));
        givenMember(member(battle(1)), freshScore(1_000));
        givenCorruptPlacement(battle(2));
        givenMember(member(battle(2)), cutoff - 1);
        givenCorruptPlacement(battle(3));

        assertThat(store().read(battle(1), d()).record()).isInstanceOf(Record.Corrupt.class);
        assertThat(store().evict(new Eviction.Missing(battle(1)), d())).as("键在：不是缺记录").isFalse();
        assertThat(store().evict(new Eviction.Dead(battle(1), 1), d())).as("没有可比对的 attempt：不动").isFalse();
        assertThat(store().publish(placement(battle(3), 1, freshScore(1_000)), d())).as("损坏的落点不公开").isFalse();
        assertThat(placementExists(battle(1))).isTrue();
        assertThat(placementExists(battle(3))).isTrue();
        assertThat(index()).containsOnlyKeys(member(battle(1)), member(battle(2)));

        assertThat(store().evict(new Eviction.Stale(battle(2), cutoff), d())).as("过期只看分数，不看记录好坏").isTrue();

        assertThat(placementExists(battle(2))).isFalse();
        assertThat(index()).containsOnlyKeys(member(battle(1)));
    }

    @Test
    void evictAsync_发出即返回_一批里各条互不影响_最终都生效_空批什么都不做() {
        givenMember("junk", freshScore(1_000));
        givenMember(member(battle(1)), freshScore(1_000));
        givenMember(member(battle(2)), freshScore(1_000));
        BattlePlacement kept = placement(battle(2), 1, freshScore(1_000));
        givenPlacement(kept);

        store().evictAsync(List.of());
        store().evictAsync(List.of(new Eviction.Invalid("junk"), new Eviction.Missing(battle(2)), new Eviction.Missing(battle(1))));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(index()).containsOnlyKeys(member(battle(2))));
        assertThat(placementOf(battle(2))).contains(kept);
    }

    // ================================================================ S_W_PUBLISH

    @Test
    void publish_attempt一致才登记_分数是created_at_ms_重放结果不变() {
        long createdAt = freshScore(2_000);
        BattlePlacement placement = placement(battle(1), 2, createdAt);
        givenPlacement(placement);

        assertThat(store().publish(placement, d())).isTrue();
        assertThat(store().publish(placement, d())).as("可重放").isTrue();

        assertThat(index()).containsOnly(Map.entry(member(battle(1)), createdAt));
        assertThat(store().read(battle(1), d()).published()).isTrue();
    }

    @Test
    void publish_落点不在或attempt不一致_不登记() {
        BattlePlacement gone = placement(battle(1), 1, freshScore(2_000));
        BattlePlacement first = placement(battle(2), 1, freshScore(2_000));
        givenPlacement(placement(battle(2), 2, freshScore(2_000))); // 存着的是 attempt = 2

        assertThat(store().publish(gone, d())).as("落点不在").isFalse();
        assertThat(store().publish(first, d())).as("存着的不是这一次开局的 attempt：先有最终落点、再进索引").isFalse();

        assertThat(index()).isEmpty();
    }

    // ================================================================ S_W_LIST / 批读

    @Test
    void list_分数降序_同分按成员字符串降序_按limit截断_过期与非法成员照列() {
        long now = nowMs();
        givenMember("100", now - 5_000);
        givenMember("200", now - 1_000);
        givenMember("300", now - 5_000);
        givenMember("junk", now - 3_000);
        givenMember("400", staleScore());

        long before = nowMs();
        Listed all = store().list(50, d());
        Listed top2 = store().list(2, d());

        assertThat(all.members()).extracting(Scored::member).containsExactly("200", "junk", "300", "100", "400");
        assertThat(all.members().get(0).score()).isEqualTo(now - 1_000);
        assertThat(all.redisNowMs()).isGreaterThanOrEqualTo(before);
        assertThat(top2.members()).extracting(Scored::member).containsExactly("200", "junk");
        assertThat(store().list(1, d()).members()).hasSize(1);
        assertThat(index()).as("只读").hasSize(5);
    }

    @Test
    void list_空索引回空表_limit小于1是调用方的错() {
        assertThat(store().list(20, d()).members()).isEmpty();
        assertThatThrownBy(() -> store().list(0, d())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readPlacements_每个战斗号一条_在_不在_损坏三种_重复的只算一次_空入参回空表() {
        BattlePlacement placement = placement(battle(1), 1, freshScore(1_000));
        givenPlacement(placement);
        givenCorruptPlacement(battle(3));

        Map<Long, Record> records = store().readPlacements(List.of(battle(1), battle(2), battle(3), battle(1)), d());

        assertThat(records).hasSize(3);
        assertThat(records.get(battle(1))).isEqualTo(new Record.Found(placement));
        assertThat(records.get(battle(2))).isInstanceOf(Record.Absent.class);
        assertThat(records.get(battle(3))).isInstanceOf(Record.Corrupt.class);
        assertThat(store().readPlacements(List.of(), d())).isEmpty();
    }

    // ================================================================ S_W_SWEEP / ZCARD

    @Test
    void sweep_只摘分数过期的成员_不动落点_回摘掉的条数_再扫一遍是0() {
        givenMember(member(battle(1)), staleScore());
        givenMember(member(battle(2)), staleScore() - 5);
        givenMember(member(battle(3)), freshScore(1_000));
        BattlePlacement stalePlacement = placement(battle(1), 1, staleScore());
        givenPlacement(stalePlacement);

        assertThat(store().watchableCount(d())).isEqualTo(3);
        assertThat(store().sweep(d())).isEqualTo(2);
        assertThat(store().sweep(d())).as("幂等").isZero();

        assertThat(index()).containsOnlyKeys(member(battle(3)));
        assertThat(store().watchableCount(d())).isEqualTo(1);
        assertThat(placementOf(battle(1))).as("清扫只摘成员；落点靠自己的 TTL").contains(stalePlacement);
    }

    // ================================================================ 截止

    @Test
    void 截止已过_不发命令直接抛依赖异常_什么都没写() {
        Deadline expired = Deadline.after(0);
        String value = mark(battle(1));
        givenMember(member(battle(1)), staleScore());
        BattlePlacement placement = placement(battle(2), 1, freshScore(1_000));
        givenPlacement(placement);
        givenMark(pid(2), value);

        assertThatThrownBy(() -> store().entry(pid(1), expired)).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> store().acquire(pid(1), value, expired)).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> store().release(pid(2), value, expired)).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> store().marksOf(List.of(pid(2)), expired)).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> store().read(battle(2), expired)).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> store().pickRandom(0.1, expired)).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> store().evict(new Eviction.Invalid(member(battle(1))), expired)).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> store().publish(placement, expired)).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> store().list(10, expired)).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> store().readPlacements(List.of(battle(2)), expired)).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> store().sweep(expired)).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> store().watchableCount(expired)).isInstanceOf(Deadline.DependencyException.class);

        assertThat(markOf(pid(1))).as("acquire 没有执行").isEmpty();
        assertThat(markOf(pid(2))).as("release 没有执行").contains(value);
        assertThat(index()).as("evict / publish / sweep 都没有执行").containsOnlyKeys(member(battle(1)));
    }
}
