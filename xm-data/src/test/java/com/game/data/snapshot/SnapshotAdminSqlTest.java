package com.game.data.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.data.metrics.DataMetrics;
import com.game.data.ops.OpsException;
import com.game.data.ops.OpsIds;
import com.game.data.ops.pb.OpsJobKind;
import com.game.data.ops.pb.OpsJobRow;
import com.game.data.ops.pb.OpsJobStatus;
import com.game.data.snapshot.SnapshotAdminService.CreateRequest;
import com.game.data.snapshot.SnapshotAdminService.Created;
import com.game.data.store.PlayerSnapshotEntry;
import com.game.data.testing.DataSqlFixture;
import com.game.data.testing.TestIds;
import com.game.data.testing.TestIds.FakeLease;
import com.game.player.store.state.CurrencyState;
import com.game.player.store.state.PlayerState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

/**
 * 手工快照（T-P1）与快照详情：内容时刻 / 拍摄时刻 / epoch / 归属区的语义；快照 + 作业行同一事务（任一步失败零变更）；幂等键；
 * 号源无效 503 零变更。缺省 H2，{@code -Dxm.it.mysql} 时连真 MySQL（同一套断言）。
 */
class SnapshotAdminSqlTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long PLAYER = 1001;

    private DataSqlFixture db;
    private FakeLease lease;
    private OpsIds ids;
    private SnapshotAdminService service;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() throws Exception {
        db = DataSqlFixture.create();
        lease = new FakeLease(7);
        ids = TestIds.ready(lease);
        meters = new SimpleMeterRegistry();
        service = new SnapshotAdminService(db.players, db.snapshots, db.jobs(), ids, db.tx(), new DataMetrics(meters),
                new ObjectMapper(), Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC), "test-runner");
    }

    @AfterEach
    void tearDown() throws Exception {
        ids.close();
        db.close();
    }

    private static byte[] state(long gold) {
        return PlayerState.newBuilder().setCurrency(CurrencyState.newBuilder().addBalances(gold)).build().toByteArray();
    }

    private CreateRequest req(String note) {
        return new CreateRequest(PLAYER, SnapshotCauses.GM_MANUAL, note, "客诉排查");
    }

    @Test
    void 内容时刻取已落盘写入时刻_拍摄时刻记ingested_at_epoch取saved_epoch_区取归属区_作业行终态() {
        db.insertPlayer(PLAYER, 3, 12, 1001, 9, true, 0, 1_000, 9_000);
        byte[] data = state(500);
        db.putState(PLAYER, data, 7, 5_000);

        Created created = service.create(req("事件:补偿"), "运维甲", "key-1");

        Map<String, Object> body = created.body();
        assertThat(created.replayed()).isFalse();
        assertThat(body).containsEntry("timeMs", 5_000L).containsEntry("ingestedAt", NOW).containsEntry("savedEpoch", "7")
                .containsEntry("zoneId", 3L).containsEntry("online", false).containsEntry("ownerReleased", true)
                .containsEntry("cause", "GM_MANUAL").containsEntry("hasState", true).doesNotContainKey("onlineCaveat");
        long snapshotId = Long.parseUnsignedLong((String) body.get("snapshotId"));
        long jobId = Long.parseUnsignedLong((String) body.get("jobId"));
        assertThat(snapshotId).isNotEqualTo(jobId);
        PlayerSnapshotEntry e = db.snapshots.findById(snapshotId);
        assertThat(e.getTimeMs()).isEqualTo(5_000);
        assertThat(e.getIngestedAt()).isEqualTo(NOW);
        assertThat(e.getOwnerEpoch()).isEqualTo(7);
        assertThat(e.getZoneId()).isEqualTo(3);
        assertThat(e.getLevel()).isEqualTo(12);
        assertThat(e.getCause()).isEqualTo(SnapshotCauses.GM_MANUAL);
        assertThat(e.getOperator()).isEqualTo("运维甲");
        assertThat(e.getNote()).isEqualTo("事件:补偿");
        assertThat(e.getPlayerState()).isEqualTo(data);
        assertThat(e.getPosX()).isEqualTo(1.5);
        OpsJobRow job = db.jobs().findJob(jobId).orElseThrow();
        assertThat(job.getKind()).isEqualTo(OpsJobKind.OPS_JOB_SNAPSHOT);
        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_SUCCEEDED);
        assertThat(job.getIdemKey()).isEqualTo("key-1");
        assertThat(job.getOperator()).isEqualTo("运维甲");
        assertThat(job.getReason()).isEqualTo("客诉排查");
        assertThat(job.getRunner()).isEqualTo("test-runner");
        assertThat(job.getFinishedMs()).isEqualTo(NOW);
        assertThat(meters.get("xm.data.snapshot.admin").tag("cause", "gm_manual").tag("result", "ok").counter().count())
                .isEqualTo(1);

        Map<String, Object> detail = service.detail(snapshotId, true);
        assertThat(detail).containsEntry("operator", "运维甲").containsEntry("causeName", "GM_MANUAL");
        assertThat(detail.get("playerState").toString()).contains("500");
    }

    @Test
    void 没有player_state行_内容时刻取player的updated_at_epoch为0_持有归属时标online() {
        db.insertPlayer(PLAYER, 2, 1, 0, 4, false, NOW + 30_000, 1_000, 8_000);

        Map<String, Object> body = service.create(new CreateRequest(PLAYER, SnapshotCauses.PRE_MAINTENANCE, "", "维护"),
                "ops", "key-2").body();

        assertThat(body).containsEntry("timeMs", 8_000L).containsEntry("savedEpoch", "0").containsEntry("hasState", false)
                .containsEntry("online", true).containsEntry("ownerReleased", false).containsKey("onlineCaveat")
                .containsEntry("stateBytes", 0).containsEntry("cause", "PRE_MAINTENANCE");
    }

    @Test
    void 幂等键_同参数回原结果_不同参数409_都不多写() {
        db.insertPlayer(PLAYER, 1, 1, 0, 1, true, 0, 1_000, 2_000);

        Created first = service.create(req("a"), "ops", "key-3");
        Created again = service.create(req("a"), "另一个运维", "key-3");

        assertThat(again.replayed()).isTrue();
        assertThat(again.body()).containsEntry("snapshotId", first.body().get("snapshotId")).containsEntry("replayed", true);
        assertThatThrownBy(() -> service.create(req("b"), "ops", "key-3"))
                .isInstanceOfSatisfying(OpsException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo(OpsException.IDEMPOTENCY_CONFLICT);
                });
        assertThat(db.count("player_snapshot")).isEqualTo(1);
        assertThat(db.count("ops_job")).isEqualTo(1);
    }

    @Test
    void 玩家不存在404_号源无效503_都零变更() {
        assertThatThrownBy(() -> service.create(req(""), "ops", "key-4"))
                .isInstanceOfSatisfying(OpsException.class, e -> assertThat(e.code()).isEqualTo(OpsException.PLAYER_NOT_FOUND));

        db.insertPlayer(PLAYER, 1, 1, 0, 1, true, 0, 1_000, 2_000);
        lease.valid.set(false);
        assertThatThrownBy(() -> service.create(req(""), "ops", "key-5"))
                .isInstanceOfSatisfying(OpsException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.code()).isEqualTo(OpsException.ID_UNAVAILABLE);
                });
        assertThat(db.count("player_snapshot")).isZero();
        assertThat(db.count("ops_job")).isZero();
        assertThat(meters.get("xm.data.snapshot.admin").tag("result", "id_unavailable").counter().count()).isEqualTo(1);
    }

    @Test
    void 作业行写不进_快照一起回滚_零变更() {
        db.insertPlayer(PLAYER, 1, 1, 0, 1, true, 0, 1_000, 2_000);
        // 让作业行的 INSERT 必然失败（幂等键超长，严格模式拒绝），而按键查询照常：快照已插进同一事务，必须一起回滚
        // （MyBatis 与 pbmysql 用 Spring 事务绑定的同一条连接）
        db.jdbc().execute("ALTER TABLE ops_job MODIFY COLUMN idem_key VARCHAR(2) NOT NULL DEFAULT ''");

        assertThatThrownBy(() -> service.create(req(""), "ops", "key-6"))
                .isInstanceOfSatisfying(OpsException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.code()).isEqualTo(OpsException.SNAPSHOT_DB_ERROR);
                });
        assertThat(db.count("player_snapshot")).isZero();
    }

    @Test
    void 作业表的幂等键唯一_并发同键时第二个插入撞键() {
        OpsJobRow job = OpsJobRow.newBuilder().setJobId(1).setIdemKey("dup").setKind(OpsJobKind.OPS_JOB_SNAPSHOT).build();
        db.tx().executeWithoutResult(s -> db.jobs().insertJob(job));
        assertThatThrownBy(() -> db.tx().executeWithoutResult(s -> db.jobs().insertJob(job.toBuilder().setJobId(2).build())))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(db.jobs().findByIdemKey("dup")).hasValueSatisfying(r -> assertThat(r.getJobId()).isEqualTo(1));
    }

    @Test
    void 详情_不存在404() {
        assertThatThrownBy(() -> service.detail(12345, false))
                .isInstanceOfSatisfying(OpsException.class, e -> assertThat(e.code()).isEqualTo(OpsException.SNAPSHOT_NOT_FOUND));
    }
}
