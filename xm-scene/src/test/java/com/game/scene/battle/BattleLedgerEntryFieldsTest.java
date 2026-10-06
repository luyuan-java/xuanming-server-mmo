package com.game.scene.battle;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.player.store.state.BattleLedgerEntry;
import com.game.player.store.state.BattleLedgerState;
import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import org.junit.jupiter.api.Test;

/**
 * 战斗结算账本往返时保留<b>条目级</b>的未知字段（审计 STL-10；规格 §7.12「未知字段原样保留」不限层级，做法同 {@code AssetOpLedger}）：
 * 以后给 {@code BattleLedgerEntry} 加字段，滚动升级 / 回滚期间旧版本节点写回时不会把它抹掉。账本的其余规则（幂等、淘汰、损坏判定）在
 * §13.1 的 {@code BattleLedgerTest}。
 */
class BattleLedgerEntryFieldsTest {

    /** 一个带未知字段的条目：字段号 15（varint）与 16（bytes）是本版本不认识的。 */
    private static BattleLedgerEntry entryWithUnknown(long battleId, long appliedAt, long marker) {
        UnknownFieldSet unknown = UnknownFieldSet.newBuilder()
                .addField(15, UnknownFieldSet.Field.newBuilder().addVarint(marker).build())
                .addField(16, UnknownFieldSet.Field.newBuilder().addLengthDelimited(ByteString.copyFromUtf8("新版本写的")).build())
                .build();
        return BattleLedgerEntry.newBuilder().setBattleId(battleId).setAppliedAtMs(appliedAt).setUnknownFields(unknown).build();
    }

    private static BattleLedgerEntry plain(long battleId, long appliedAt) {
        return BattleLedgerEntry.newBuilder().setBattleId(battleId).setAppliedAtMs(appliedAt).build();
    }

    @Test
    void 条目里不认识的字段_往返原样带回_逐字节相同() {
        BattleLedgerState stored = BattleLedgerState.newBuilder()
                .addApplied(entryWithUnknown(7, 100, 42))
                .addApplied(plain(9, 200))
                .build();

        BattleLedgerState written = BattleLedger.restore(stored).toState();

        assertThat(written).isEqualTo(stored);
        assertThat(written.toByteString()).isEqualTo(stored.toByteString());
        assertThat(written.getApplied(0).getUnknownFields().getField(15).getVarintList()).containsExactly(42L);
        assertThat(written.getApplied(1).getUnknownFields().asMap()).isEmpty();
    }

    @Test
    void 账本这一层与条目这一层的未知字段都保留() {
        BattleLedgerState stored = BattleLedgerState.newBuilder()
                .addApplied(entryWithUnknown(7, 100, 42))
                .setUnknownFields(UnknownFieldSet.newBuilder()
                        .addField(20, UnknownFieldSet.Field.newBuilder().addFixed64(0xCAFEL).build()).build())
                .build();

        BattleLedger ledger = BattleLedger.restore(stored);

        assertThat(ledger.toState()).isEqualTo(stored);
        assertThat(ledger.isPristine()).isFalse();
    }

    @Test
    void 重复登记只刷新时间戳_条目里不认识的字段留着() {
        BattleLedger ledger = BattleLedger.restore(BattleLedgerState.newBuilder().addApplied(entryWithUnknown(7, 100, 42)).build());

        assertThat(ledger.record(7, 555)).isZero();

        BattleLedgerEntry entry = ledger.toState().getApplied(0);
        assertThat(entry.getAppliedAtMs()).isEqualTo(555);
        assertThat(entry.getUnknownFields().getField(15).getVarintList()).containsExactly(42L);
        assertThat(entry.getUnknownFields().hasField(16)).isTrue();
    }

    @Test
    void 摘掉条目时它的未知字段一起丢_同一个battle_id重新登记是干净的新条目_别的条目不受影响() {
        BattleLedger ledger = BattleLedger.restore(BattleLedgerState.newBuilder()
                .addApplied(entryWithUnknown(7, 100, 42))
                .addApplied(entryWithUnknown(9, 200, 43))
                .build());

        assertThat(ledger.forget(7)).isTrue();
        ledger.record(7, 300);

        BattleLedgerState written = ledger.toState();
        assertThat(written.getAppliedList()).extracting(BattleLedgerEntry::getBattleId).containsExactly(7L, 9L);
        assertThat(written.getApplied(0)).as("重新登记的 7 不带旧条目的未知字段").isEqualTo(plain(7, 300));
        assertThat(written.getApplied(1)).isEqualTo(entryWithUnknown(9, 200, 43));
    }

    @Test
    void 满员淘汰时被淘汰条目的未知字段一起丢_留下的照旧() {
        BattleLedgerState.Builder full = BattleLedgerState.newBuilder();
        // 1 号时间戳最小（最先被淘汰），2 号带未知字段且留下
        full.addApplied(entryWithUnknown(1, 10, 41));
        full.addApplied(entryWithUnknown(2, 1_000, 42));
        for (long id = 3; id <= BattleLedger.CAPACITY; id++) {
            full.addApplied(plain(id, 1_000 + id));
        }
        BattleLedger ledger = BattleLedger.restore(full.build());
        assertThat(ledger.invalidReason()).isNull();

        long evicted = ledger.record(1_000, 5_000);
        assertThat(evicted).isEqualTo(1);
        // 被淘汰的 1 号以后重新登记：干净条目
        assertThat(ledger.forget(1_000)).isTrue();
        ledger.record(1, 6_000);

        BattleLedgerState written = ledger.toState();
        assertThat(written.getApplied(0)).isEqualTo(plain(1, 6_000));
        assertThat(written.getApplied(1)).isEqualTo(entryWithUnknown(2, 1_000, 42));
    }

    @Test
    void 损坏的账本原样带回_连同条目里的未知字段() {
        BattleLedgerState corrupt = BattleLedgerState.newBuilder()
                .addApplied(entryWithUnknown(7, 100, 42))
                .addApplied(entryWithUnknown(7, 200, 43))
                .build();

        BattleLedger ledger = BattleLedger.restore(corrupt);

        assertThat(ledger.invalidReason()).contains("重复");
        assertThat(ledger.toState()).isSameAs(corrupt);
        assertThat(ledger.forget(7)).as("损坏的账本不改").isFalse();
        assertThat(ledger.toState()).isSameAs(corrupt);
    }
}
