package com.game.scene.battle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.discovery.battle.BattleRedis;
import com.game.player.store.state.BattleLedgerEntry;
import com.game.player.store.state.BattleLedgerState;
import com.game.player.store.state.PlayerState;
import com.google.protobuf.UnknownFieldSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 战斗结算幂等账本的纯规则（scene-battle-spec §7.12、§13.1；逐条移植基线 {@code player_battle_settlement_test.cpp:325-360} 的
 * {@code BattleSettlementLedgerRule} 三条，再补 Java 自有的部分：写出顺序、state 往返、加载校验 D24、「可以销账」的判据）。
 * 条目级未知字段的往返在 {@link BattleLedgerEntryFieldsTest}。
 */
class BattleLedgerTest {

    /** 大于 2^63 的 battle_id（有符号看是负数）：排序、命中都必须按无符号。 */
    private static final long HUGE = 0x8000_0000_0000_0005L;

    private static BattleLedgerEntry entry(long battleId, long appliedAt) {
        return BattleLedgerEntry.newBuilder().setBattleId(battleId).setAppliedAtMs(appliedAt).build();
    }

    private static BattleLedgerState stateOf(BattleLedgerEntry... entries) {
        return BattleLedgerState.newBuilder().addAllApplied(List.of(entries)).build();
    }

    /** 刚好满员：1..64 号，时间戳 1000 + 号。 */
    private static BattleLedger full() {
        BattleLedger ledger = BattleLedger.empty();
        for (long id = 1; id <= BattleLedger.CAPACITY; id++) {
            assertThat(ledger.record(id, 1000 + id)).isZero();
        }
        assertThat(ledger.size()).isEqualTo(BattleLedger.CAPACITY);
        return ledger;
    }

    // ------------------------------------------------------------------ 基线三条（t_settle:325-360）

    /** {@code RecordIsIdempotentAndForgetRemoves}。 */
    @Test
    void 登记幂等_重复登记只刷新时间戳不产生第二条_forget摘掉且只摘一次() {
        BattleLedger ledger = BattleLedger.empty();
        assertThat(ledger.has(9)).isFalse();

        assertThat(ledger.record(9, 100)).as("没有淘汰").isZero();
        assertThat(ledger.record(9, 200)).isZero();

        assertThat(ledger.size()).as("重投重复登记不产生第二条").isEqualTo(1);
        assertThat(ledger.toState().getAppliedList()).containsExactly(entry(9, 200));
        assertThat(ledger.has(9)).isTrue();
        assertThat(ledger.forget(9)).isTrue();
        assertThat(ledger.forget(9)).as("已经摘掉了").isFalse();
        assertThat(ledger.has(9)).isFalse();
        assertThat(ledger.size()).isZero();
        assertThat(ledger.isPristine()).isTrue();
    }

    /** {@code ZeroBattleIdIsNeverRecordedOrMatched}。 */
    @Test
    void 零号永不登记_永不命中_也摘不到() {
        BattleLedger ledger = BattleLedger.empty();

        assertThat(ledger.record(0, 1)).isZero();

        assertThat(ledger.size()).isZero();
        assertThat(ledger.has(0)).isFalse();
        assertThat(ledger.forget(0)).isFalse();
        assertThat(ledger.battleIds()).isEmpty();
        assertThat(ledger.toState()).isEqualTo(BattleLedgerState.getDefaultInstance());
        assertThat(ledger.isPristine()).isTrue();
    }

    /**
     * {@code EvictsOldestByTimestampNotByIndex}：满员时淘汰 applied_at_ms 最小的一条，顺序由时间戳决定而不是下标 / battle_id。
     * 刻意把最旧那条放在中间（4 号），下标顺序、battle_id 顺序都与时间顺序不一致时才测得出来。
     */
    @Test
    void 满员淘汰时间戳最小的一条_不按下标也不按battle_id() {
        BattleLedger ledger = BattleLedger.empty();
        for (int i = 0; i < BattleLedger.CAPACITY; i++) {
            long at = i == 3 ? 1 : 1000 + i;
            assertThat(ledger.record(i + 1, at)).isZero();
        }
        assertThat(ledger.size()).isEqualTo(BattleLedger.CAPACITY);

        long evicted = ledger.record(999, 5000);

        assertThat(evicted).as("下标 3、时间戳最小的那条").isEqualTo(4);
        assertThat(ledger.size()).isEqualTo(BattleLedger.CAPACITY);
        assertThat(ledger.has(4)).isFalse();
        assertThat(ledger.has(1)).isTrue();
        assertThat(ledger.has(999)).isTrue();
        assertThat(ledger.battleIds()).doesNotContain(4L).contains(999L).hasSize(BattleLedger.CAPACITY);
    }

    // ------------------------------------------------------------------ 容量与淘汰的边界

    @Test
    void 容量是64_与跨进程常量同值() {
        assertThat(BattleLedger.CAPACITY).isEqualTo(64).isEqualTo(BattleRedis.LEDGER_CAPACITY);
    }

    @Test
    void 满员时重复登记已有的局_只刷新时间戳_不淘汰任何一条() {
        BattleLedger ledger = full();

        assertThat(ledger.record(1, 9_999)).isZero();

        assertThat(ledger.size()).isEqualTo(BattleLedger.CAPACITY);
        assertThat(ledger.toState().getApplied(0)).isEqualTo(entry(1, 9_999));
        // 1 号刚被刷新成最新的：下一次淘汰轮到 2 号
        assertThat(ledger.record(500, 10_000)).isEqualTo(2);
        assertThat(ledger.has(1)).isTrue();
    }

    @Test
    void 连续溢出_每次淘汰当时最旧的一条_条数始终是64() {
        BattleLedger ledger = full();

        assertThat(ledger.record(101, 2_000)).isEqualTo(1);
        assertThat(ledger.record(102, 2_001)).isEqualTo(2);
        assertThat(ledger.record(103, 2_002)).isEqualTo(3);

        assertThat(ledger.size()).isEqualTo(BattleLedger.CAPACITY);
        assertThat(ledger.battleIds()).startsWith(4L, 5L).endsWith(101L, 102L, 103L);
    }

    /** applied_at_ms 是 uint64：最高位为 1 的时间戳是「最新」而不是「最旧」（按有符号比会把它当成负数先淘汰）。 */
    @Test
    void 淘汰按无符号时间戳比较() {
        BattleLedger ledger = BattleLedger.empty();
        ledger.record(1, -1L);
        for (long id = 2; id <= BattleLedger.CAPACITY; id++) {
            ledger.record(id, 1000 + id);
        }

        assertThat(ledger.record(999, 5000)).as("最旧的是 2 号（1002），不是时间戳 2^64-1 的 1 号").isEqualTo(2);
        assertThat(ledger.has(1)).isTrue();
    }

    @Test
    void 没满时登记不淘汰_第64条不淘汰_第65条才淘汰() {
        BattleLedger ledger = BattleLedger.empty();
        for (long id = 1; id < BattleLedger.CAPACITY; id++) {
            ledger.record(id, id);
        }

        assertThat(ledger.record(64, 64)).as("第 64 条刚好放满").isZero();
        assertThat(ledger.size()).isEqualTo(64);
        assertThat(ledger.record(65, 65)).as("第 65 条挤掉最旧的 1 号").isEqualTo(1);
        assertThat(ledger.size()).isEqualTo(64);
    }

    // ------------------------------------------------------------------ 写出顺序与往返

    /** 周期存盘按值比对，写出顺序必须只由内容决定：battle_id 无符号升序，与登记先后、时间戳无关。 */
    @Test
    void 写出按battle_id无符号升序_与登记先后无关_含大于2的63次方的号() {
        BattleLedger ledger = BattleLedger.empty();
        ledger.record(9, 400);
        ledger.record(HUGE, 100);
        ledger.record(3, 300);
        ledger.record(-1L, 50);
        ledger.record(7, 200);

        assertThat(ledger.battleIds()).containsExactly(3L, 7L, 9L, HUGE, -1L);
        assertThat(ledger.toState().getAppliedList())
                .containsExactly(entry(3, 300), entry(7, 200), entry(9, 400), entry(HUGE, 100), entry(-1L, 50));
        assertThat(ledger.has(HUGE)).isTrue();
        assertThat(ledger.has(-1L)).isTrue();

        // 换一个登记次序，写出逐字节相同
        BattleLedger other = BattleLedger.empty();
        other.record(-1L, 50);
        other.record(7, 200);
        other.record(3, 300);
        other.record(HUGE, 100);
        other.record(9, 400);
        assertThat(other.toState().toByteString()).isEqualTo(ledger.toState().toByteString());
    }

    @Test
    void 存档往返_内容不变_乱序的存档读进来后按升序写出() {
        BattleLedgerState sorted = stateOf(entry(3, 300), entry(7, 200), entry(HUGE, 100));
        BattleLedger restored = BattleLedger.restore(sorted);

        assertThat(restored.invalidReason()).isNull();
        assertThat(restored.size()).isEqualTo(3);
        assertThat(restored.has(3)).isTrue();
        assertThat(restored.has(7)).isTrue();
        assertThat(restored.has(HUGE)).isTrue();
        assertThat(restored.has(4)).isFalse();
        assertThat(restored.toState()).isEqualTo(sorted);
        assertThat(restored.isPristine()).isFalse();

        BattleLedgerState shuffled = stateOf(entry(HUGE, 100), entry(3, 300), entry(7, 200));
        assertThat(BattleLedger.restore(shuffled).toState()).isEqualTo(sorted);
    }

    @Test
    void 读回来的账本照常登记与摘除_时间戳沿用存档里的() {
        BattleLedger ledger = BattleLedger.restore(stateOf(entry(3, 300), entry(7, 200)));

        assertThat(ledger.forget(3)).isTrue();
        assertThat(ledger.record(5, 900)).isZero();

        assertThat(ledger.toState()).isEqualTo(stateOf(entry(5, 900), entry(7, 200)));
    }

    @Test
    void 空账本与空存档_写出就是缺省实例_持久化时整段省略() {
        assertThat(BattleLedger.empty().toState()).isEqualTo(BattleLedgerState.getDefaultInstance());
        assertThat(BattleLedger.empty().isPristine()).isTrue();
        assertThat(BattleLedger.empty().invalidReason()).isNull();

        BattleLedger restored = BattleLedger.restore(BattleLedgerState.getDefaultInstance());

        assertThat(restored.invalidReason()).isNull();
        assertThat(restored.isPristine()).isTrue();
        assertThat(restored.size()).isZero();
    }

    /** 账本这一层本版本不认识的字段原样带回，登记 / 摘除都不影响它；有它在就不算「没有内容」。条目级的见 BattleLedgerEntryFieldsTest。 */
    @Test
    void 未知字段保留_登记与摘除之后仍在_有未知字段的空账本不省略() {
        UnknownFieldSet unknown = UnknownFieldSet.newBuilder()
                .addField(20, UnknownFieldSet.Field.newBuilder().addVarint(77).build()).build();
        BattleLedgerState stored = BattleLedgerState.newBuilder().addApplied(entry(7, 200)).setUnknownFields(unknown).build();

        BattleLedger ledger = BattleLedger.restore(stored);
        assertThat(ledger.toState()).isEqualTo(stored);
        ledger.record(9, 300);
        assertThat(ledger.forget(7)).isTrue();
        assertThat(ledger.forget(9)).isTrue();

        BattleLedgerState written = ledger.toState();
        assertThat(written.getAppliedCount()).isZero();
        assertThat(written.getUnknownFields()).isEqualTo(unknown);
        assertThat(written.getUnknownFields().getField(20).getVarintList()).containsExactly(77L);
        assertThat(ledger.isPristine()).as("还带着别的版本写的字段，不能整段省略").isFalse();
    }

    // ------------------------------------------------------------------ 加载校验（D24）

    @Test
    void 损坏判定_含0号条目() {
        BattleLedgerState corrupt = stateOf(entry(3, 300), entry(0, 1));

        assertThat(BattleLedger.validate(corrupt)).isEqualTo("含 battle_id = 0 的条目");
        assertCorruptAndFrozen(corrupt, "含 battle_id = 0 的条目");
    }

    @Test
    void 损坏判定_battle_id重复_原因里带那个号的无符号写法() {
        BattleLedgerState corrupt = stateOf(entry(HUGE, 300), entry(7, 1), entry(HUGE, 301));

        assertThat(BattleLedger.validate(corrupt)).isEqualTo("battle_id 重复 9223372036854775813");
        assertCorruptAndFrozen(corrupt, "battle_id 重复 9223372036854775813");
    }

    @Test
    void 损坏判定_条数超过64_刚好64条合法() {
        BattleLedgerState.Builder exact = BattleLedgerState.newBuilder();
        for (long id = 1; id <= BattleLedger.CAPACITY; id++) {
            exact.addApplied(entry(id, id));
        }
        assertThat(BattleLedger.validate(exact.build())).as("刚好 64 条合法").isNull();
        BattleLedger atCapacity = BattleLedger.restore(exact.build());
        assertThat(atCapacity.invalidReason()).isNull();
        assertThat(atCapacity.size()).isEqualTo(64);
        assertThat(atCapacity.toState()).isEqualTo(exact.build());

        BattleLedgerState over = exact.addApplied(entry(65, 65)).build();

        assertThat(BattleLedger.validate(over)).isEqualTo("条数 65 超过容量 64");
        assertCorruptAndFrozen(over, "条数 65 超过容量 64");
    }

    @Test
    void 合法的存档_校验为null() {
        assertThat(BattleLedger.validate(BattleLedgerState.getDefaultInstance())).isNull();
        assertThat(BattleLedger.validate(stateOf(entry(1, 0), entry(HUGE, 0), entry(-1L, 0)))).as("时间戳为 0、相同都不算损坏").isNull();
    }

    /**
     * 损坏的账本：原样带回（同一个对象，不排序、不去重、不截断）、拒绝改动（record 抛异常、forget 不改）、不当空账本用
     * （{@code isPristine} 为假，写回时不会被省略成「没有账本」；条数报存档里的条数）。
     */
    private static void assertCorruptAndFrozen(BattleLedgerState corrupt, String reason) {
        BattleLedger ledger = BattleLedger.restore(corrupt);

        assertThat(ledger.invalidReason()).isEqualTo(reason);
        assertThat(ledger.toState()).as("原样带回").isSameAs(corrupt);
        assertThat(ledger.isPristine()).as("绝不当空账本").isFalse();
        assertThat(ledger.size()).isEqualTo(corrupt.getAppliedCount());
        assertThatThrownBy(() -> ledger.record(42, 1)).isInstanceOf(IllegalStateException.class).hasMessageContaining(reason);
        assertThatThrownBy(() -> ledger.record(0, 1)).as("损坏的账本连 0 号也先报损坏").isInstanceOf(IllegalStateException.class);
        for (BattleLedgerEntry entry : corrupt.getAppliedList()) {
            assertThat(ledger.forget(entry.getBattleId())).as("损坏的账本不改").isFalse();
        }
        assertThat(ledger.toState()).isSameAs(corrupt);
        assertThat(ledger.size()).isEqualTo(corrupt.getAppliedCount());
    }

    // ------------------------------------------------------------------ 「可以销账」的判据

    /** 「可以销账」= 最近一次确认落库的快照里有这一局；快照为 null（上次在线存盘失败，库里什么样不确定）按没有。 */
    @Test
    void 落库快照里有没有这一局_null与没有账本段都按没有_命中才算() {
        assertThat(BattleLedger.persistedHas(null, 7)).as("快照不确定").isFalse();
        assertThat(BattleLedger.persistedHas(PlayerState.getDefaultInstance(), 7)).as("没有账本段").isFalse();

        PlayerState persisted = PlayerState.newBuilder().setBattleLedger(stateOf(entry(7, 200), entry(HUGE, 100))).build();

        assertThat(BattleLedger.persistedHas(persisted, 7)).isTrue();
        assertThat(BattleLedger.persistedHas(persisted, HUGE)).isTrue();
        assertThat(BattleLedger.persistedHas(persisted, 8)).isFalse();
        assertThat(BattleLedger.persistedHas(PlayerState.newBuilder().setBattleLedger(BattleLedgerState.getDefaultInstance()).build(), 7))
                .as("有账本段但是空的").isFalse();
    }

    @Test
    void 落库快照的判据_0号永不命中_哪怕损坏的快照里真有0号条目() {
        PlayerState corruptPersisted = PlayerState.newBuilder().setBattleLedger(stateOf(entry(0, 1), entry(7, 2))).build();

        assertThat(BattleLedger.persistedHas(corruptPersisted, 0)).isFalse();
        assertThat(BattleLedger.persistedHas(corruptPersisted, 7)).isTrue();
    }

    /** 两条判据分开：内存里登记了、落库快照里还没有 = 最危险的那一刻（此时销账，崩溃即永久丢奖）。 */
    @Test
    void 活账本里有而落库快照里没有_两条判据给出不同答案() {
        BattleLedger live = BattleLedger.restore(stateOf(entry(7, 200)));
        PlayerState persistedBefore = PlayerState.newBuilder().setBattleLedger(live.toState()).build();
        live.record(9, 300);

        assertThat(live.has(9)).as("应用过了").isTrue();
        assertThat(BattleLedger.persistedHas(persistedBefore, 9)).as("还没落盘，不能销账").isFalse();
        assertThat(BattleLedger.persistedHas(PlayerState.newBuilder().setBattleLedger(live.toState()).build(), 9)).isTrue();
    }
}
