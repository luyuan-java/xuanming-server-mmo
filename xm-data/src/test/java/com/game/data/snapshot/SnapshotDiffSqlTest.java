package com.game.data.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.data.ops.OpsException;
import com.game.data.query.TransactionLogQueryService;
import com.game.data.testing.DataSqlFixture;
import com.game.data.txlog.TransactionLogRow;
import com.game.data.txlog.TransactionLogSink;
import com.game.player.store.state.BagItemState;
import com.game.player.store.state.BagState;
import com.game.player.store.state.CurrencyState;
import com.game.player.store.state.Facing;
import com.game.player.store.state.PetEntry;
import com.game.player.store.state.PetState;
import com.game.player.store.state.PlayerState;
import com.game.player.store.state.Vitals;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 结构化差异（T-D1 的端到端部分，§3.6）：选快照（按号属于别人 404、按时刻白名单）、行级 / 货币 / 物品 / 宝宝 / 其余段、
 * 转移证据（被转移 / 被销毁 / 合并 / 无记录）与 restorable、快照早于流水保留期时证据不完整（restorable 未知）、账本差集；坏字节 422。
 * 缺省 H2，{@code -Dxm.it.mysql} 时连真 MySQL。
 */
class SnapshotDiffSqlTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long PLAYER = 1001;
    private static final long OTHER = 2002;

    private DataSqlFixture db;
    private SnapshotDiffService service;

    @BeforeEach
    void setUp() throws Exception {
        db = DataSqlFixture.create();
        service = service(Duration.ZERO);
    }

    /** 资产流水保留期为 {@code retention}（0 = 永久）的差异服务，时钟固定在 {@link #NOW}。 */
    private SnapshotDiffService service(Duration retention) {
        return new SnapshotDiffService(db.players, db.snapshots, new TransactionLogQueryService(db.txlog,
                Duration.ofDays(7)), Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC), retention);
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    private static BagItemState item(long uuid, int config, int stack, int pos) {
        return BagItemState.newBuilder().setItemUuid(uuid).setConfigId(config).setStackSize(stack).setPos(pos).build();
    }

    private void snapshot(long id, long player, long timeMs, int cause, PlayerState state) {
        db.snapshots.insertDirect(new PlayerSnapshotRow(id, player, timeMs, cause, 1, 3, 10, 1001, 1.5, 2.5, -3.5,
                state.toByteArray()), timeMs, "", "");
    }

    private void txlog(TransactionLogRow... rows) {
        new TransactionLogSink(db.txlog, db.tx(), 50, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC))
                .insert(List.of(rows));
    }

    private static TransactionLogRow itemRow(long txId, long timeMs, long from, long to, long uuid, int qty, int reason) {
        return new TransactionLogRow(txId, timeMs, reason, 2, from, to, 0, 0, 0, 0, uuid, 501, qty, 0, "", 1);
    }

    @Test
    void 各段差异_转移证据_账本差集() {
        PlayerState before = PlayerState.newBuilder()
                .setCurrency(CurrencyState.newBuilder().addBalances(1000).addBalances(50))
                .setBag(BagState.newBuilder().addItems(item(11, 501, 5, 0)).addItems(item(12, 501, 1, 1))
                        .addItems(item(13, 502, 1, 2)).addItems(item(14, 503, 1, 3)).addItems(item(15, 504, 2, 4)))
                .setPets(PetState.newBuilder().addPets(PetEntry.newBuilder().setPetId(70).setPetTableId(9).setLevel(3)))
                .setVitals(Vitals.newBuilder().setHealth(100))
                .build();
        PlayerState after = PlayerState.newBuilder()
                .setCurrency(CurrencyState.newBuilder().addBalances(400).addBalances(50).addBlockedTypes(1))
                .setBag(BagState.newBuilder().addItems(item(11, 501, 2, 0)).addItems(item(15, 504, 2, 4))
                        .addItems(item(16, 505, 1, 5)))
                .setPets(PetState.newBuilder().addPets(PetEntry.newBuilder().setPetId(71).setPetTableId(9).setLevel(1)))
                .setVitals(Vitals.newBuilder().setHealth(80))
                .setFacing(Facing.newBuilder().setY(1))
                .build();
        db.insertPlayer(PLAYER, 1, 12, 1002, 4, true, 0, 100, 9_000);
        db.putState(PLAYER, after.toByteArray(), 4, 8_000);
        snapshot(500, PLAYER, 1_000, SnapshotCauses.LOGOUT, before);
        txlog(
                // 12 被交易给了别人（转移）
                itemRow(1, 2_000, PLAYER, OTHER, 12, 1, 1),
                // 13 被销毁
                itemRow(2, 2_100, PLAYER, 0, 13, 1, 13),
                // 快照之前的流水不算证据
                itemRow(3, 500, PLAYER, OTHER, 14, 1, 1),
                // 金币：快照之后经交易转出
                new TransactionLogRow(4, 3_000, 1, 1, PLAYER, OTHER, 0, -600, 1000, 400, 0, 0, 0, 0, "", 1));

        Map<String, Object> diff = service.diff(PLAYER, 500L, null);

        assertThat(map(diff, "snapshot")).containsEntry("snapshotId", "500").containsEntry("causeName", "LOGOUT");
        assertThat(map(diff, "current")).containsEntry("persistedAtMs", 8_000L).containsEntry("online", false);
        assertThat(diff).as("流水永久保留：证据完整").containsEntry("evidenceComplete", true)
                .containsEntry("evidenceFloorMs", 0L);
        Map<String, Object> row = map(diff, "row");
        assertThat(map(row, "level")).containsEntry("snapshot", 10L).containsEntry("current", 12L)
                .containsEntry("changed", true);
        assertThat(map(row, "position")).containsEntry("changed", false);

        Map<String, Object> currency = map(diff, "currency");
        List<Map<String, Object>> balances = list(currency, "balances");
        assertThat(balances.get(0)).containsEntry("delta", "-600").containsEntry("transferAfterSnapshot", true)
                .containsEntry("restorable", false).containsEntry("transferTxIds", List.of("4"));
        assertThat(balances.get(1)).containsEntry("delta", "0").doesNotContainKey("restorable");
        assertThat(map(currency, "blockedTypes")).containsEntry("changed", true);

        Map<String, Object> items = map(diff, "items");
        assertThat(list(items, "byConfig")).extracting(m -> m.get("configId")).containsExactly(501L, 502L, 503L, 505L);
        assertThat(list(items, "onlyInSnapshot")).extracting(m -> m.get("itemUuid"), m -> m.get("evidence"),
                        m -> m.get("restorable"))
                .containsExactly(org.assertj.core.groups.Tuple.tuple("12", "TRANSFERRED", false),
                        org.assertj.core.groups.Tuple.tuple("13", "DESTROYED", true),
                        org.assertj.core.groups.Tuple.tuple("14", "NO_RECORD", true));
        assertThat(list(items, "stackChanged")).singleElement()
                .satisfies(m -> assertThat(m).containsEntry("itemUuid", "11").containsEntry("delta", -3L));
        assertThat(list(items, "onlyInCurrent")).extracting(m -> m.get("itemUuid")).containsExactly("16");

        Map<String, Object> pets = map(diff, "pets");
        assertThat(list(pets, "onlyInSnapshot")).extracting(m -> m.get("petId")).containsExactly("70");
        assertThat(list(pets, "onlyInCurrent")).extracting(m -> m.get("petId")).containsExactly("71");

        Map<String, Object> sections = map(diff, "sections");
        assertThat(map(sections, "vitals")).containsEntry("same", false);
        assertThat(list(map(sections, "vitals"), "changes")).singleElement()
                .satisfies(m -> assertThat(m).containsEntry("path", "vitals.health"));
        assertThat(map(sections, "facing")).containsEntry("same", false).containsKey("presence");
        assertThat(map(sections, "mission")).containsEntry("same", true);
        assertThat(map(diff, "ledger")).containsEntry("clean", true);
    }

    @Test
    void 快照早于流水保留期_证据不完整_只认正面证据_其余restorable未知() {
        long day = Duration.ofDays(1).toMillis();
        SnapshotDiffService purged = service(Duration.ofDays(90));
        long floor = NOW - 90 * day;
        PlayerState before = PlayerState.newBuilder()
                .setCurrency(CurrencyState.newBuilder().addBalances(1000).addBalances(50))
                .setBag(BagState.newBuilder().addItems(item(12, 501, 1, 1)).addItems(item(13, 502, 1, 2))
                        .addItems(item(14, 503, 1, 3)))
                .build();
        PlayerState after = PlayerState.newBuilder()
                .setCurrency(CurrencyState.newBuilder().addBalances(400).addBalances(30))
                .build();
        db.insertPlayer(PLAYER, 1, 12, 1002, 4, true, 0, 100, NOW - day);
        db.putState(PLAYER, after.toByteArray(), 4, NOW - day);
        // GM 快照缺省永久保留，比流水活得久；快照之后、下界之前的流水（例如 -100 天卖掉的 600 金币）已被清理
        snapshot(600, PLAYER, NOW - 120 * day, SnapshotCauses.GM_MANUAL, before);
        // 恰好在下界上的快照：之后的流水一条都没被清
        snapshot(601, PLAYER, floor, SnapshotCauses.LOGOUT, before);
        txlog(
                // 下界之后仍看得到的转移：正面证据照常生效
                itemRow(1, NOW - 5 * day, PLAYER, OTHER, 12, 1, 1),
                // 下界之后的销毁：之前可能还有已清掉的转移，不能据此判可恢复
                itemRow(2, NOW - 5 * day, PLAYER, 0, 13, 1, 13),
                new TransactionLogRow(3, NOW - 5 * day, 1, 1, PLAYER, OTHER, 1, -20, 50, 30, 0, 0, 0, 0, "", 1));

        Map<String, Object> diff = purged.diff(PLAYER, null, NOW - 100 * day);

        assertThat(map(diff, "snapshot")).as("按时刻能选到 GM 快照").containsEntry("snapshotId", "600");
        assertThat(diff).containsEntry("evidenceComplete", false).containsEntry("evidenceFloorMs", floor);
        assertThat((String) diff.get("caveat")).contains("evidenceFloorMs=" + floor).contains("INCOMPLETE");
        List<Map<String, Object>> balances = list(map(diff, "currency"), "balances");
        assertThat(balances.get(0)).as("流水已清：不是「没转移、可恢复」，是未知")
                .containsEntry("delta", "-600").containsEntry("transferAfterSnapshot", null)
                .containsEntry("restorable", null).containsEntry("transferTxIds", List.of());
        assertThat(balances.get(1)).containsEntry("delta", "-20").containsEntry("transferAfterSnapshot", true)
                .containsEntry("restorable", false).containsEntry("transferTxIds", List.of("3"));
        assertThat(list(map(diff, "items"), "onlyInSnapshot")).extracting(m -> m.get("itemUuid"),
                        m -> m.get("evidence"), m -> m.get("restorable"), m -> m.get("evidenceTxIds"))
                .containsExactly(org.assertj.core.groups.Tuple.tuple("12", "TRANSFERRED", false, List.of("1")),
                        org.assertj.core.groups.Tuple.tuple("13", "INCOMPLETE", null, List.of("2")),
                        org.assertj.core.groups.Tuple.tuple("14", "INCOMPLETE", null, List.of()));

        Map<String, Object> atFloor = purged.diff(PLAYER, 601L, null);
        assertThat(atFloor).as("快照不早于下界：证据完整").containsEntry("evidenceComplete", true);
        assertThat((String) atFloor.get("caveat")).doesNotContain("INCOMPLETE");
        assertThat(list(map(atFloor, "currency"), "balances").get(0)).containsEntry("transferAfterSnapshot", false)
                .containsEntry("restorable", true);
        assertThat(list(map(atFloor, "items"), "onlyInSnapshot")).extracting(m -> m.get("evidence"),
                        m -> m.get("restorable"))
                .containsExactly(org.assertj.core.groups.Tuple.tuple("TRANSFERRED", false),
                        org.assertj.core.groups.Tuple.tuple("DESTROYED", true),
                        org.assertj.core.groups.Tuple.tuple("NO_RECORD", true));
    }

    @Test
    void 选快照_按号属于别人404_按时刻用白名单_没有可用快照404_坏字节422() {
        db.insertPlayer(PLAYER, 1, 1, 0, 1, true, 0, 100, 200);
        db.insertPlayer(OTHER, 1, 1, 0, 1, true, 0, 100, 200);
        snapshot(1, OTHER, 100, SnapshotCauses.LOGIN, PlayerState.getDefaultInstance());
        snapshot(2, PLAYER, 100, SnapshotCauses.LOGIN, PlayerState.getDefaultInstance());
        snapshot(3, PLAYER, 150, SnapshotCauses.PRE_ROLLBACK, PlayerState.getDefaultInstance());

        assertThatThrownBy(() -> service.diff(PLAYER, 1L, null)).isInstanceOfSatisfying(OpsException.class,
                e -> assertThat(e.code()).isEqualTo(OpsException.SNAPSHOT_NOT_FOUND));
        assertThat(map(service.diff(PLAYER, null, 200L), "snapshot"))
                .as("安全快照 3 不当时间点源").containsEntry("snapshotId", "2");
        assertThat(map(service.diff(PLAYER, 3L, null), "snapshot")).as("安全快照可以按号显式选")
                .containsEntry("snapshotId", "3");
        assertThatThrownBy(() -> service.diff(PLAYER, null, 99L)).isInstanceOfSatisfying(OpsException.class,
                e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.diff(PLAYER, 2L, 200L)).isInstanceOfSatisfying(OpsException.class,
                e -> assertThat(e.code()).isEqualTo(OpsException.INVALID_REQUEST));

        db.putState(PLAYER, new byte[] {0x0a, 0x7f}, 1, 300);
        assertThatThrownBy(() -> service.diff(PLAYER, 2L, null)).isInstanceOfSatisfying(OpsException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(e.details()).containsEntry("side", "current");
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> m, String key) {
        return (Map<String, Object>) m.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> m, String key) {
        return (List<Map<String, Object>>) m.get(key);
    }
}
