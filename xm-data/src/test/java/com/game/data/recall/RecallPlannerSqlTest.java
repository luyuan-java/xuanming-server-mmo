package com.game.data.recall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.data.ops.OpsException;
import com.game.data.ops.OpsIds;
import com.game.data.ops.pb.OpsJobEventRow;
import com.game.data.ops.pb.OpsJobEventType;
import com.game.data.ops.pb.OpsJobKind;
import com.game.data.ops.pb.OpsJobRow;
import com.game.data.ops.pb.OpsJobStatus;
import com.game.data.ops.pb.RecallSourceRow;
import com.game.data.query.TransactionLogQueryService;
import com.game.data.testing.DataSqlFixture;
import com.game.data.testing.TestIds;
import com.game.data.txlog.TransactionLogRow;
import com.game.data.txlog.TransactionLogSink;
import com.game.player.store.state.BagItemState;
import com.game.player.store.state.BagState;
import com.game.player.store.state.CurrencyState;
import com.game.player.store.state.PlayerState;
import java.sql.Connection;
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
 * 回收 dry-run（T-C1 的 7.2a 部分，§5.2–§5.4）：金币可作目标、扣减行不算候选、同一 tx 只计一次、已回收的源流水标出且不计入、
 * 按已落盘状态估算可回收量与缺口、截断 422 零变更且照写审计作业；参数校验（保留期、全服窗口、players 上限）。
 */
class RecallPlannerSqlTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final Duration MAX_WINDOW = Duration.ofDays(7);

    private DataSqlFixture db;
    private OpsIds ids;

    @BeforeEach
    void setUp() throws Exception {
        db = DataSqlFixture.create();
        ids = TestIds.ready(3);
    }

    @AfterEach
    void tearDown() throws Exception {
        ids.close();
        db.close();
    }

    private RecallPlanner planner(int maxRows) {
        return new RecallPlanner(new TransactionLogQueryService(db.txlog, MAX_WINDOW), db.players, db.jobs(), ids, db.tx(),
                new ObjectMapper(), Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC), maxRows, "test-runner");
    }

    private void txlog(TransactionLogRow... rows) {
        new TransactionLogSink(db.txlog, db.tx(), 50, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC))
                .insert(List.of(rows));
    }

    private static TransactionLogRow gold(long txId, long timeMs, long from, long to, long delta, int reason) {
        return new TransactionLogRow(txId, timeMs, reason, 1, from, to, 0, delta, 0, 0, 0, 0, 0, 0, "", 1);
    }

    private static TransactionLogRow item(long txId, long timeMs, long to, int config, int qty) {
        return new TransactionLogRow(txId, timeMs, 9, 2, 0, to, 0, 0, 0, 0, 900 + txId, config, qty, 0, "", 1);
    }

    private static RecallPlanner.Plan currencyPlan(List<Long> players, long since, long until) {
        return RecallPlanner.plan(players, "currency", 0L, null, since, until, List.of(9), null, NOW, Duration.ZERO,
                MAX_WINDOW);
    }

    @Test
    void 金币可回收_扣减行不算候选_已回收的标出不计入_按落盘状态估缺口() throws Exception {
        db.insertPlayer(7, 1, 1, 0, 1, true, 0, 1, 1);
        db.putState(7, PlayerState.newBuilder().setCurrency(CurrencyState.newBuilder().addBalances(250)).build()
                .toByteArray(), 1, 1);
        txlog(gold(1, 100, 0, 7, 300, 9), gold(2, 110, 7, 0, -50, 10), gold(3, 120, 0, 7, 100, 9),
                gold(4, 130, 0, 8, 40, 9), gold(5, 140, 0, 7, 20, 25));
        try (Connection c = db.dataSource.getConnection()) {
            db.ops.insert(c, RecallSourceRow.newBuilder().setTxId(3).setJobId(1).setPlayerId(7).build());
        }

        Map<String, Object> out = planner(100).dryRun(currencyPlan(List.of(7L, 7L), 0, 1_000), "ops");

        assertThat(list(out, "rows")).extracting(m -> m.get("txId"), m -> m.get("alreadyRecalled"))
                .as("只认获得行；原因过滤 9；同一玩家重复给只查一次").containsExactly(
                        org.assertj.core.groups.Tuple.tuple("1", false), org.assertj.core.groups.Tuple.tuple("3", true));
        assertThat(list(out, "players")).singleElement().satisfies(p -> assertThat(p)
                .containsEntry("player", "7").containsEntry("owed", "300").containsEntry("held", "250")
                .containsEntry("recoverable", "250").containsEntry("shortfall", "50"));
        assertThat(map(out, "totals")).containsEntry("alreadyRecalled", 1).containsEntry("owed", "300");
        assertThat(out).containsEntry("truncated", false).containsEntry("ingestCheck", "not_checked");
        assertThat(db.count("ops_job")).as("dry-run 不截断时不写作业").isZero();
    }

    @Test
    void 全服按物品_走配置号_截断时422零变更并写审计作业() {
        txlog(item(1, 100, 7, 501, 2), item(2, 110, 8, 501, 1), item(3, 120, 9, 501, 4), item(4, 130, 9, 502, 4));
        RecallPlanner.Plan plan = RecallPlanner.plan(List.of(), "item", null, 501L, 0L, 1_000L, List.of(), "排查",
                NOW, Duration.ZERO, MAX_WINDOW);

        Map<String, Object> out = planner(3).dryRun(plan, "ops");
        assertThat(list(out, "rows")).extracting(m -> m.get("txId")).containsExactly("1", "2", "3");
        assertThat(list(out, "players")).extracting(m -> m.get("heldNote")).as("玩家不存在按 0 估")
                .containsOnly("player_not_found");

        assertThatThrownBy(() -> planner(2).dryRun(plan, "ops")).isInstanceOfSatisfying(OpsException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(e.code()).isEqualTo(OpsException.RESULT_TRUNCATED);
            assertThat(e.details()).containsEntry("truncated", true).containsEntry("audited", true).containsKey("jobId");
        });
        OpsJobRow job = db.jobs().findByIdemKey(
                "auto:" + db.jdbc().queryForObject("SELECT job_id FROM ops_job", Long.class)).orElseThrow();
        assertThat(job.getKind()).isEqualTo(OpsJobKind.OPS_JOB_RECALL);
        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_REJECTED);
        assertThat(job.getResultCode()).isEqualTo(OpsException.RESULT_TRUNCATED);
        assertThat(job.getReason()).isEqualTo("排查");
        assertThat(db.jobs().events(job.getJobId())).extracting(OpsJobEventRow::getType)
                .containsExactly(OpsJobEventType.OPS_JOB_EVENT_RESULT);
    }

    @Test
    void 指定玩家时按玩家走获得方索引_物品数量按堆叠估() {
        db.insertPlayer(9, 1, 1, 0, 1, true, 0, 1, 1);
        db.putState(9, PlayerState.newBuilder().setBag(BagState.newBuilder()
                .addItems(BagItemState.newBuilder().setItemUuid(1).setConfigId(501).setStackSize(3).setBagType(0))
                .addItems(BagItemState.newBuilder().setItemUuid(2).setConfigId(501).setStackSize(2).setBagType(1))
                .addItems(BagItemState.newBuilder().setItemUuid(3).setConfigId(777).setStackSize(9))).build()
                .toByteArray(), 1, 1);
        txlog(item(1, 100, 7, 501, 2), item(3, 120, 9, 501, 4), item(5, 140, 9, 501, 4));
        RecallPlanner.Plan plan = RecallPlanner.plan(List.of(9L), "item", null, 501L, 0L, 1_000L, null, null, NOW,
                Duration.ZERO, MAX_WINDOW);

        Map<String, Object> out = planner(10).dryRun(plan, "ops");

        assertThat(list(out, "rows")).extracting(m -> m.get("txId")).containsExactly("3", "5");
        assertThat(list(out, "players")).singleElement().satisfies(p -> assertThat(p).containsEntry("owed", "8")
                .containsEntry("held", "5").containsEntry("shortfall", "3"));
    }

    @Test
    void 币种是uint32_大于等于2的31次方时估算不越界() {
        int hugeType = (int) 3_000_000_000L;
        db.insertPlayer(7, 1, 1, 0, 1, true, 0, 1, 1);
        db.putState(7, PlayerState.newBuilder().setCurrency(CurrencyState.newBuilder().addBalances(250)).build()
                .toByteArray(), 1, 1);
        txlog(new TransactionLogRow(1, 100, 9, 1, 0, 7, hugeType, 5, 0, 5, 0, 0, 0, 0, "", 1));
        RecallPlanner.Plan plan = RecallPlanner.plan(List.of(7L), "currency", 3_000_000_000L, null, 0L, 1_000L, null,
                null, NOW, Duration.ZERO, MAX_WINDOW);

        Map<String, Object> out = planner(10).dryRun(plan, "ops");

        assertThat(list(out, "rows")).extracting(m -> m.get("currencyType")).containsExactly(3_000_000_000L);
        assertThat(list(out, "players")).singleElement().satisfies(p -> assertThat(p).containsEntry("owed", "5")
                .containsEntry("held", "0").containsEntry("shortfall", "5"));
    }

    @Test
    void 参数校验() {
        assertThatThrownBy(() -> RecallPlanner.plan(List.of(), "currency", null, null, 0L, 1L, null, null, NOW,
                Duration.ZERO, MAX_WINDOW)).as("货币必须给币种").isInstanceOf(OpsException.class);
        assertThatThrownBy(() -> RecallPlanner.plan(List.of(), "item", null, 0L, 0L, 1L, null, null, NOW, Duration.ZERO,
                MAX_WINDOW)).as("物品配置号不能为 0").isInstanceOf(OpsException.class);
        assertThatThrownBy(() -> RecallPlanner.plan(List.of(), "currency", 0L, null, 0L, MAX_WINDOW.toMillis() + 1, null,
                null, NOW, Duration.ZERO, MAX_WINDOW)).as("全服窗口超上限").isInstanceOf(OpsException.class);
        assertThatThrownBy(() -> RecallPlanner.plan(List.of(), "currency", 0L, null, Long.MIN_VALUE, Long.MAX_VALUE, null,
                null, NOW, Duration.ZERO, MAX_WINDOW)).as("全服窗口宽度溢出也算超上限").isInstanceOf(OpsException.class);
        assertThatThrownBy(() -> RecallPlanner.plan(List.of(1L), "currency", 0L, null, NOW - Duration.ofDays(31).toMillis(),
                NOW, null, null, NOW, Duration.ofDays(30), MAX_WINDOW)).as("早于流水保留期").isInstanceOf(OpsException.class);
        assertThatThrownBy(() -> RecallPlanner.plan(List.of(1L), "currency", 0L, null, 5L, 5L, null, null, NOW,
                Duration.ZERO, MAX_WINDOW)).as("空窗口").isInstanceOf(OpsException.class);
        assertThat(RecallPlanner.plan(List.of(1L), "currency", 0L, null, 0L, Long.MAX_VALUE, null, null, NOW,
                Duration.ZERO, MAX_WINDOW).players()).as("指定玩家时不限窗口").containsExactly(1L);
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
