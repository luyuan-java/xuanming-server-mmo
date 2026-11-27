package com.game.data.recall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.data.merge.ZoneMergeStore;
import com.game.data.merge.ZoneMergeTables;
import com.game.data.merge.pb.ZoneMergeHistoryRow;
import com.game.data.ops.OpsException;
import com.game.data.ops.OpsIds;
import com.game.data.query.TransactionLogQueryService;
import com.game.data.testing.DataSqlFixture;
import com.game.data.testing.TestIds;
import com.game.data.txlog.TransactionLogRow;
import com.game.data.txlog.TransactionLogSink;
import com.game.pbmysql.TableSchema;
import com.game.player.store.state.BagItemState;
import com.game.player.store.state.BagState;
import com.game.player.store.state.CurrencyState;
import com.game.player.store.state.PlayerState;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 覆盖 L956 合服只增历史审计 + L958 批量回收执行（digest 校验、空计划 37、离线围栏、点名实例销毁、未回收放回、摘孤儿角色）。 */
class ZoneMergeHistoryAndRecallExecutorTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final Duration MAX_WINDOW = Duration.ofDays(7);

    private DataSqlFixture db;
    private OpsIds ids;

    @BeforeEach
    void setUp() throws Exception {
        db = DataSqlFixture.create((c, principal) -> {
            try (Statement st = c.createStatement()) {
                for (TableSchema table : ZoneMergeTables.registry().tables()) {
                    String ddl = table.createTableSql()
                            .replace(" CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin", "")
                            .replaceAll("\\) ENGINE=InnoDB[^\\n]*;\\s*$", ");");
                    st.execute(ddl);
                }
            }
        });
        ids = TestIds.ready(10);
    }

    @AfterEach
    void tearDown() throws Exception {
        ids.close();
        db.close();
    }

    private RecallPlanner planner() {
        return new RecallPlanner(new TransactionLogQueryService(db.txlog, MAX_WINDOW), db.players, db.jobs(), ids,
                db.tx(), new ObjectMapper(), Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC), 100, "test");
    }

    private RecallExecutor executor() {
        return new RecallExecutor(db.dataSource, new TransactionLogQueryService(db.txlog, MAX_WINDOW), db.jobs(), ids,
                db.tx(), new ObjectMapper(), Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC), 100, "test");
    }

    private void txlog(TransactionLogRow... rows) {
        new TransactionLogSink(db.txlog, db.tx(), 50, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC))
                .insert(List.of(rows));
    }

    private static TransactionLogRow gold(long txId, long timeMs, long to, long delta) {
        return new TransactionLogRow(txId, timeMs, 9, 1, 0, to, 0, delta, 0, 0, 0, 0, 0, 0, "", 1);
    }

    private static TransactionLogRow item(long txId, long timeMs, long to, long itemUuid, int config, int qty) {
        return new TransactionLogRow(txId, timeMs, 9, 2, 0, to, 0, 0, 0, 0, itemUuid, config, qty, 0, "", 1);
    }

    @Test
    void 合服只增历史可审计并识别无历史的映射() throws Exception {
        String run1 = UUID.randomUUID().toString();
        String run2 = UUID.randomUUID().toString();
        db.jdbc().update("INSERT INTO zone_merged_into(zone_id,target_zone,run_id,revision) VALUES(1,2,?,1)", run1);
        db.jdbc().update("INSERT INTO zone_merged_into(zone_id,target_zone,run_id,revision) VALUES(3,2,?,1)", run2);
        try (Connection c = db.schemaConnection()) {
            ZoneMergeStore.appendMergeHistory(c, run1, 1, 1, 2, "", NOW);
            List<ZoneMergeHistoryRow> history = ZoneMergeStore.listMergeHistory(c);
            assertThat(history).hasSize(1);
            assertThat(history.getFirst().getRunId()).isEqualTo(run1);
            assertThat(history.getFirst().getAction()).isEqualTo(1);
            assertThat(history.getFirst().getSourceZone()).isEqualTo(1);
            assertThat(history.getFirst().getTargetZone()).isEqualTo(2);
            assertThat(history.getFirst().getOperatorName()).isNotBlank();
            assertThat(ZoneMergeStore.findMappingsWithoutHistory(c)).containsExactly(3);
        }
    }

    @Test
    void 回收执行校验digest_空计划回37_离线扣减去重并放回未回收行() throws Exception {
        db.insertPlayer(7, 1, 1, 0, 10, true, 0, 1, 1);
        db.putState(7, PlayerState.newBuilder().setCurrency(CurrencyState.newBuilder().addBalances(120)).build()
                .toByteArray(), 10, 1);
        txlog(gold(101, 100, 7, 100), gold(102, 110, 7, 50), gold(103, 120, 7, 40));

        RecallPlanner.Plan plan = RecallPlanner.plan(List.of(7L), "currency", 0L, null, 0L, 1_000L, List.of(9),
                "补偿回退", NOW, Duration.ZERO, MAX_WINDOW);
        Map<String, Object> preview = planner().dryRun(plan, "ops");
        String digest = (String) preview.get("planDigest");
        assertThat(digest).hasSize(64);

        // 错误 digest 拒绝
        assertThatThrownBy(() -> executor().execute(plan, "0".repeat(64), "debt", "ops"))
                .isInstanceOfSatisfying(OpsException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.code()).isEqualTo(OpsException.PLAN_DIGEST_MISMATCH);
                });

        // 真正执行：持有 120，tx101 扣 100 + tx102 扣 20（记录 recall_source），tx103 扣 0（放回不记 recall_source）
        Map<String, Object> out = executor().execute(plan, digest, "debt", "ops");
        assertThat(out).containsEntry("dryRun", false).containsEntry("playersAffected", 1)
                .containsEntry("totalRecovered", 120L).containsEntry("totalShortfall", 70L);
        byte[] afterBytes = db.jdbc().queryForObject("SELECT data FROM player_state WHERE player_id = 7", byte[].class);
        assertThat(PlayerState.parseFrom(afterBytes).getCurrency().getBalances(0)).isZero();
        assertThat(db.jdbc().queryForObject("SELECT owner_epoch FROM player WHERE player_id = 7", Long.class))
                .isEqualTo(11L);
        assertThat(db.jobs().recalledAmong(List.of(101L, 102L, 103L))).containsExactlyInAnyOrder(101L, 102L);

        // 空计划返回 422 且 errorCode = 37
        RecallPlanner.Plan emptyPlan = RecallPlanner.plan(List.of(999L), "currency", 0L, null, 0L, 1_000L, List.of(9),
                null, NOW, Duration.ZERO, MAX_WINDOW);
        assertThatThrownBy(() -> executor().execute(emptyPlan, RecallPlanner.planDigest(emptyPlan), "report", "ops"))
                .isInstanceOfSatisfying(OpsException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.code()).isEqualTo(OpsException.EMPTY_RECALL_PLAN);
                    assertThat(e.details()).containsEntry("errorCode", RecallExecutor.EMPTY_PLAN_ERROR_CODE);
                });
    }

    @Test
    void 不可叠加物品只销毁点名实例_在线角色拦截不锁流水_孤儿角色可摘除() throws Exception {
        // 玩家 8 离线，持有两件同 configId=501 的不可叠加装备（uuid=8001、8002），流水点名 8001 与不存在的 8099
        db.insertPlayer(8, 1, 1, 0, 5, true, 0, 1, 1);
        db.putState(8, PlayerState.newBuilder().setBag(BagState.newBuilder()
                .addItems(BagItemState.newBuilder().setItemUuid(8001).setConfigId(501).setStackSize(1))
                .addItems(BagItemState.newBuilder().setItemUuid(8002).setConfigId(501).setStackSize(1))).build()
                .toByteArray(), 5, 1);
        // 玩家 9 在线（owner_released=0），不可扣减
        db.insertPlayer(9, 1, 1, 0, 5, false, NOW + 60_000, 1, 1);
        txlog(item(201, 100, 8, 8001, 501, 1), item(202, 110, 8, 8099, 501, 1), item(203, 120, 9, 9001, 501, 1));

        RecallPlanner.Plan plan = RecallPlanner.plan(List.of(8L, 9L), "item", null, 501L, 0L, 1_000L, List.of(9),
                "回收异常装备", NOW, Duration.ZERO, MAX_WINDOW);
        Map<String, Object> out = executor().execute(plan, RecallPlanner.planDigest(plan), "report", "ops");
        assertThat(out).containsEntry("playersAffected", 1).containsEntry("playersFailed", 1)
                .containsEntry("totalRecovered", 1L);
        byte[] p8Bytes = db.jdbc().queryForObject("SELECT data FROM player_state WHERE player_id = 8", byte[].class);
        assertThat(PlayerState.parseFrom(p8Bytes).getBag().getItemsList())
                .extracting(BagItemState::getItemUuid).containsExactly(8002L);
        // 201 命中入 recall_source；202 未命中、203 在线拦截，均放回
        assertThat(db.jobs().recalledAmong(List.of(201L, 202L, 203L))).containsExactly(201L);

        // 摘孤儿：玩家 8 有存档 → 409 拒绝；玩家 9 在线 → 409 拒绝；删掉 player_state 的离线孤儿 → 成功摘除
        assertThatThrownBy(() -> executor().detachOrphanPlayer(8L, "ops", "test"))
                .isInstanceOfSatisfying(OpsException.class, e -> assertThat(e.code()).isEqualTo(OpsException.PLAYER_NOT_ORPHAN));
        assertThatThrownBy(() -> executor().detachOrphanPlayer(9L, "ops", "test"))
                .isInstanceOfSatisfying(OpsException.class, e -> assertThat(e.code()).isEqualTo(OpsException.PLAYER_NOT_ORPHAN));
        db.jdbc().update("DELETE FROM player_state WHERE player_id = 8");
        Map<String, Object> detached = executor().detachOrphanPlayer(8L, "ops", "orphan-cleanup");
        assertThat(detached).containsEntry("detached", true).containsEntry("deletedPlayerRows", 1);
        assertThat(db.jdbc().queryForObject("SELECT COUNT(*) FROM player WHERE player_id = 8", Integer.class)).isZero();
    }
}
