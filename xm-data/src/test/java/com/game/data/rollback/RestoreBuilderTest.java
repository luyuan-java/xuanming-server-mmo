package com.game.data.rollback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.data.rollback.RestoreBuilder.CurrencyChange;
import com.game.data.rollback.RestoreBuilder.ItemChange;
import com.game.data.rollback.RestoreBuilder.Restored;
import com.game.data.store.PersistedPlayer;
import com.game.data.store.PlayerSnapshotEntry;
import com.game.player.store.state.BattleLedgerEntry;
import com.game.player.store.state.BattleLedgerState;
import com.game.player.store.state.CurrencyState;
import com.game.player.store.state.PlayerState;
import com.game.player.store.state.Vitals;
import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 恢复内容（T-R2，data-ops-spec §4.5）：FULL 整份替换、封禁保留、SECTIONS 以现档为底、未知字段、资产变化。 */
class RestoreBuilderTest {

    private static PlayerSnapshotEntry snapshot(PlayerState state) {
        PlayerSnapshotEntry e = new PlayerSnapshotEntry();
        e.setSnapshotId(5);
        e.setPlayerId(1);
        e.setTimeMs(1000);
        e.setLevel(5);
        e.setSceneConfigId(2002);
        e.setPosX(1);
        e.setPosY(2);
        e.setPosZ(3);
        e.setPlayerState(state.toByteArray());
        return e;
    }

    private static PersistedPlayer current(byte[] state) {
        PersistedPlayer p = new PersistedPlayer();
        p.setPlayerId(1);
        p.setLevel(9);
        p.setSceneConfigId(1001);
        p.setPosX(7);
        p.setStateData(state);
        p.setStateUpdatedAt(2000L);
        return p;
    }

    /** 带一个本版本不认识的字段（字段号 99）的玩家状态。 */
    private static PlayerState withUnknown(PlayerState state, String marker) {
        return state.toBuilder().setUnknownFields(UnknownFieldSet.newBuilder()
                .addField(99, UnknownFieldSet.Field.newBuilder().addLengthDelimited(ByteString.copyFromUtf8(marker)).build())
                .build()).build();
    }

    @Test
    void FULL_整份换成快照含未知字段_快照之后新增的段清掉_封禁保留现档() {
        PlayerState snap = withUnknown(RollbackJobSqlTest.snapshotState(), "snap");
        PlayerState cur = withUnknown(RollbackJobSqlTest.currentState().toBuilder()
                .setVitals(Vitals.newBuilder().setHealth(3)).build(), "cur");

        Restored r = RestoreBuilder.build(snapshot(snap), current(cur.toByteArray()), EnumSet.noneOf(RollbackSection.class));

        assertThat(r.state().getUnknownFields().getField(99).getLengthDelimitedList())
                .containsExactly(ByteString.copyFromUtf8("snap"));
        assertThat(r.state().hasVitals()).isFalse();
        assertThat(r.state().getCurrency().getBalancesList()).containsExactly(1000L, 0L);
        assertThat(r.state().getCurrency().getBlockedTypesList()).containsExactly(0);
        assertThat(r.level()).isEqualTo(5);
        assertThat(r.sceneConfigId()).isEqualTo(2002);
        assertThat(r.posX()).isEqualTo(1);
        assertThat(r.currency()).containsExactly(new CurrencyChange(0, 1500, 1000), new CurrencyChange(1, 20, 0));
        assertThat(r.items()).containsExactly(new ItemChange(1, 100, 7, 5), new ItemChange(2, 200, 1, 0));
        assertThat(r.assetsRestored()).isTrue();
    }

    @Test
    void FULL_快照与现档都没有货币段也没有封禁_不凭空造货币段() {
        PlayerState empty = PlayerState.getDefaultInstance();
        Restored r = RestoreBuilder.build(snapshot(empty), current(empty.toByteArray()), Set.of());
        assertThat(r.state()).isEqualTo(empty);
        assertThat(r.currency()).isEmpty();
    }

    @Test
    void FULL_现档损坏_允许_封禁取快照值_不写流水() {
        PlayerState snap = RollbackJobSqlTest.snapshotState().toBuilder()
                .setCurrency(CurrencyState.newBuilder().addBalances(5).addBlockedTypes(2)).build();
        Restored r = RestoreBuilder.build(snapshot(snap), current(new byte[] {(byte) 0xFF, 0x01}), Set.of());
        assertThat(r.currentValid()).isFalse();
        assertThat(r.state()).isEqualTo(snap);
        assertThat(r.currency()).isEmpty();
        assertThat(r.detail()).containsKey("currentStateInvalid");
    }

    @Test
    void SECTIONS_现档损坏_state_invalid_快照损坏一律state_invalid() {
        assertThatThrownBy(() -> RestoreBuilder.build(snapshot(RollbackJobSqlTest.snapshotState()),
                current(new byte[] {(byte) 0xFF, 0x01}), EnumSet.of(RollbackSection.LEVEL)))
                .isInstanceOfSatisfying(RestoreBuilder.StateInvalidException.class,
                        e -> assertThat(e.side()).isEqualTo("current"));
        PlayerSnapshotEntry broken = snapshot(PlayerState.getDefaultInstance());
        broken.setPlayerState(new byte[] {(byte) 0xFF, 0x01});
        assertThatThrownBy(() -> RestoreBuilder.build(broken, current(new byte[0]), Set.of()))
                .isInstanceOfSatisfying(RestoreBuilder.StateInvalidException.class,
                        e -> assertThat(e.side()).isEqualTo("snapshot"));
    }

    @Test
    void SECTIONS_以现档为底_只换选中段_两边相同的未知字段照留_资产组四段一起_封禁保留() {
        PlayerState snap = withUnknown(RollbackJobSqlTest.snapshotState(), "same");
        PlayerState cur = withUnknown(RollbackJobSqlTest.currentState(), "same");

        Restored r = RestoreBuilder.build(snapshot(snap), current(cur.toByteArray()),
                EnumSet.of(RollbackSection.ASSETS, RollbackSection.MISSION, RollbackSection.FACING));

        assertThat(r.state().getUnknownFields().getField(99).getLengthDelimitedList())
                .containsExactly(ByteString.copyFromUtf8("same"));
        assertThat(r.state().getCurrency().getBalancesList()).containsExactly(1000L, 0L);
        assertThat(r.state().getCurrency().getBlockedTypesList()).containsExactly(0);
        assertThat(r.state().getBag()).isEqualTo(snap.getBag());
        assertThat(r.state().getPets()).isEqualTo(snap.getPets());
        assertThat(r.state().getMission()).isEqualTo(snap.getMission());
        assertThat(r.state().getFacing()).isEqualTo(snap.getFacing());
        // level / position 没选：行列取现档
        assertThat(r.level()).isEqualTo(9);
        assertThat(r.sceneConfigId()).isEqualTo(1001);
        assertThat(r.posX()).isEqualTo(7);
    }

    @Test
    void SECTIONS_选了assets而两边的未知顶层字段不同_拒绝_unknown_sections_列出字段号() {
        // 较新的 scene 写下了本版本不认识的段（可能是新账本）：资产回到快照而它留在现档 → 账本与资产对不上，fail-closed
        PlayerState snap = withUnknown(RollbackJobSqlTest.snapshotState(), "snap");
        PlayerState cur = withUnknown(RollbackJobSqlTest.currentState(), "cur");
        assertThatThrownBy(() -> RestoreBuilder.build(snapshot(snap), current(cur.toByteArray()),
                EnumSet.of(RollbackSection.ASSETS)))
                .isInstanceOfSatisfying(RestoreBuilder.UnknownSectionsException.class,
                        e -> assertThat(e.fields()).containsExactly(99))
                .isInstanceOf(RestoreBuilder.StateInvalidException.class);
        // 只有一边有也算不同
        assertThatThrownBy(() -> RestoreBuilder.build(snapshot(RollbackJobSqlTest.snapshotState()),
                current(cur.toByteArray()), EnumSet.of(RollbackSection.ASSETS, RollbackSection.LEVEL)))
                .isInstanceOf(RestoreBuilder.UnknownSectionsException.class);
    }

    @Test
    void SECTIONS_不动资产组时_现档的未知字段保留_不拒绝() {
        PlayerState snap = withUnknown(RollbackJobSqlTest.snapshotState(), "snap");
        PlayerState cur = withUnknown(RollbackJobSqlTest.currentState(), "cur");

        Restored r = RestoreBuilder.build(snapshot(snap), current(cur.toByteArray()),
                EnumSet.of(RollbackSection.FACING, RollbackSection.LEVEL));

        assertThat(r.state().getUnknownFields().getField(99).getLengthDelimitedList())
                .containsExactly(ByteString.copyFromUtf8("cur"));
        assertThat(r.state().getFacing()).isEqualTo(snap.getFacing());
        assertThat(r.state().getCurrency()).isEqualTo(cur.getCurrency());
        assertThat(r.level()).isEqualTo(5);
    }

    @Test
    void SECTIONS_快照没有的段_清掉_不动资产时不出资产变化() {
        PlayerState snap = PlayerState.newBuilder().setCurrency(CurrencyState.newBuilder().addBalances(1)).build();
        PlayerState cur = RollbackJobSqlTest.currentState().toBuilder().setVitals(Vitals.newBuilder().setHealth(3)).build();
        Restored r = RestoreBuilder.build(snapshot(snap), current(cur.toByteArray()),
                EnumSet.of(RollbackSection.VITALS, RollbackSection.POSITION));
        assertThat(r.state().hasVitals()).isFalse();
        assertThat(r.state().getCurrency()).isEqualTo(cur.getCurrency());
        assertThat(r.assetsRestored()).isFalse();
        assertThat(r.currency()).isEmpty();
        assertThat(r.items()).isEmpty();
        assertThat(r.sceneConfigId()).isEqualTo(2002);
        assertThat(r.level()).isEqualTo(9);
    }

    @Test
    void 资产组_描述符里除朝向属性任务气血以外的全部段_新加的段缺省归资产组() {
        assertThat(RollbackSection.ASSET_FIELDS).contains(PlayerState.CURRENCY_FIELD_NUMBER, PlayerState.BAG_FIELD_NUMBER,
                PlayerState.PETS_FIELD_NUMBER, PlayerState.ASSET_LEDGER_FIELD_NUMBER);
        assertThat(RollbackSection.ASSET_FIELDS).doesNotContainAnyElementsOf(RollbackSection.NON_ASSET_FIELDS);
        assertThat(RollbackSection.ASSET_FIELDS.size() + RollbackSection.NON_ASSET_FIELDS.size())
                .isEqualTo(PlayerState.getDescriptor().getFields().size());
    }

    private static BattleLedgerState battleLedger(long... battleIds) {
        BattleLedgerState.Builder b = BattleLedgerState.newBuilder();
        for (long id : battleIds) {
            b.addApplied(BattleLedgerEntry.newBuilder().setBattleId(id).setAppliedAtMs(1_700_000_000_000L + id));
        }
        return b.build();
    }

    /**
     * 审计 OPS-9 的回归：战斗结算账本（battle_ledger = 9）没有单独的分歧检查器（lead 裁决不建 AssetDivergenceChecker），靠的是它随资产组
     * <b>整段</b>回退——未销账的局回档后由待结算记录重投、相对快照恰好一次；已销账的局奖励随回档消失（scene-battle-spec S-11）。
     * 资产回到快照而账本留在现档（或反过来）就会重发 / 吞掉一局的奖励，所以两个方向、FULL 与 assets 都钉住。
     */
    @Test
    void 战斗结算账本随资产组整段回退_现档有快照没有则清掉_反向则带回_FULL与assets同理() {
        assertThat(RollbackSection.ASSET_FIELDS).contains(PlayerState.BATTLE_LEDGER_FIELD_NUMBER);
        PlayerState without = RollbackJobSqlTest.snapshotState();
        PlayerState with = RollbackJobSqlTest.currentState().toBuilder().setBattleLedger(battleLedger(71, 72)).build();

        for (Set<RollbackSection> sections : List.of(EnumSet.noneOf(RollbackSection.class),
                EnumSet.of(RollbackSection.ASSETS))) {
            // 现档有「已应用未销账」的局、快照没有：回档后账本为空（资产也回到那两局之前），待结算记录会重投
            Restored cleared = RestoreBuilder.build(snapshot(without), current(with.toByteArray()), sections);
            assertThat(cleared.state().hasBattleLedger()).as("sections=%s", sections).isFalse();
            assertThat(cleared.state().getCurrency().getBalancesList()).containsExactly(1000L, 0L);

            // 反向：快照里有、现档已销账清空：账本随资产一起带回（那一局的奖励已在快照的资产里，不能再发一次）
            PlayerState snapWith = without.toBuilder().setBattleLedger(battleLedger(71)).build();
            Restored restoredBack = RestoreBuilder.build(snapshot(snapWith),
                    current(RollbackJobSqlTest.currentState().toByteArray()), sections);
            assertThat(restoredBack.state().getBattleLedger()).as("sections=%s", sections).isEqualTo(battleLedger(71));

            // 两边都有、内容不同：整段取快照的，不合并
            Restored replaced = RestoreBuilder.build(snapshot(snapWith), current(with.toByteArray()), sections);
            assertThat(replaced.state().getBattleLedger()).as("sections=%s", sections).isEqualTo(battleLedger(71));
        }
    }

    @Test
    void 不动资产组的部分回档_战斗结算账本留现档() {
        PlayerState snap = RollbackJobSqlTest.snapshotState().toBuilder().setBattleLedger(battleLedger(71)).build();
        PlayerState cur = RollbackJobSqlTest.currentState().toBuilder().setBattleLedger(battleLedger(72, 73)).build();

        Restored r = RestoreBuilder.build(snapshot(snap), current(cur.toByteArray()),
                EnumSet.of(RollbackSection.LEVEL, RollbackSection.FACING));

        assertThat(r.state().getBattleLedger()).isEqualTo(battleLedger(72, 73));
        assertThat(r.state().getCurrency()).isEqualTo(cur.getCurrency());
    }

    @Test
    void 余额差夹到int64() {
        assertThat(RollbackWriter.signedDelta(0, -1L)).isEqualTo(Long.MAX_VALUE);
        assertThat(RollbackWriter.signedDelta(-1L, 0)).isEqualTo(Long.MIN_VALUE);
        assertThat(RollbackWriter.signedDelta(1500, 1000)).isEqualTo(-500);
        assertThat(List.of(RollbackWriter.signedDelta(5, 7))).containsExactly(2L);
    }
}
