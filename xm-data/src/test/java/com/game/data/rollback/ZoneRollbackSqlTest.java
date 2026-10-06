package com.game.data.rollback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.data.ops.OpsException;
import com.game.data.ops.pb.OpsJobPlayerRow;
import com.game.data.ops.pb.OpsJobRow;
import com.game.data.ops.pb.OpsJobStatus;
import com.game.data.snapshot.SnapshotCauses;
import com.game.gateway.store.ZoneManualStatus;
import com.game.player.store.state.PlayerState;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 整区 / 多区回档（T-Z1，data-ops-spec §4.9）。 */
class ZoneRollbackSqlTest {

    private RollbackHarness h;

    @AfterEach
    void tearDown() throws Exception {
        if (h != null) {
            h.close();
        }
    }

    private static RollbackRequest.Body zones(List<Long> zones, boolean all, long target) {
        return new RollbackRequest.Body("zones", null, zones, all, null, target, null, null, false, false, "合服前回滚",
                false);
    }

    private PlayerState persisted(long player) throws Exception {
        return PlayerState.parseFrom(h.db.players.find(player).stateBytes());
    }

    @Test
    void 区服开放_409_zone_open_不受理() throws Exception {
        h = new RollbackHarness(Map.of());
        h.zone(1, ZoneManualStatus.OPEN.code());
        long target = System.currentTimeMillis() - 1000;
        assertThatThrownBy(() -> h.submit(zones(List.of(1L), false, target), "k1"))
                .isInstanceOfSatisfying(OpsException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("zone_open");
                });
        assertThat(h.db.count("ops_job")).isZero();
    }

    @Test
    void 整区_一律kick_没有快照的只报告分两类_其余全写() throws Exception {
        h = new RollbackHarness(Map.of());
        h.zone(1, ZoneManualStatus.MAINTENANCE.code());
        h.zone(2, ZoneManualStatus.OPEN.code());
        long now = System.currentTimeMillis();
        long target = now - 60_000;
        PlayerState current = RollbackJobSqlTest.currentState();
        PlayerState snapshot = RollbackJobSqlTest.snapshotState();
        h.player(11, 1, current.toByteArray(), now - 1000, 2, true);   // 在线：kick
        h.player(12, 1, current.toByteArray(), now - 1000, 2, false);
        h.player(13, 1, current.toByteArray(), now - 1000, 2, false);  // 建于 T 之前却没有快照
        h.player(21, 2, current.toByteArray(), now - 1000, 2, false);  // 别的区：不在目标里
        h.snapshot(7011, 11, target - 10, SnapshotCauses.LOGOUT, 1, 5, 2002, snapshot.toByteArray());
        h.snapshot(7012, 12, target - 10, SnapshotCauses.LOGOUT, 3, 5, 2002, snapshot.toByteArray()); // 快照所在区与归属区不同
        h.snapshot(7021, 21, target - 10, SnapshotCauses.LOGOUT, 2, 5, 2002, snapshot.toByteArray());
        // 建于 T 之后的玩家
        h.db.insertPlayer(14, 1, 1, 1001, 0, true, 0, now, now);

        OpsJobRow job = h.await(h.submit(zones(List.of(1L), false, target), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_SUCCEEDED);
        assertThat(job.getPlayersAffected()).isEqualTo(2);
        List<String> outcomes = h.jobs.players(job.getJobId(), 0, 10).stream().map(OpsJobPlayerRow::getOutcome).toList();
        assertThat(outcomes).containsExactly("RESTORED", "RESTORED", "no_snapshot", "created_after_target");
        assertThat(h.takeovers).isNotEmpty();
        assertThat(persisted(11).getCurrency().getBalances(0)).isEqualTo(1000);
        assertThat(persisted(13)).isEqualTo(current);
        assertThat(persisted(21)).isEqualTo(current);
        assertThat(h.summary(job).at("/plan/snapshotZoneMismatchCount").asInt()).isEqualTo(1);
    }

    @Test
    void 整区里一人夺不到_全部释放_零写入_REJECTED_zone_not_quiescent() throws Exception {
        h = new RollbackHarness(Map.of("xm.data.ops.claim-wait", "300ms"));
        h.sceneReleasesOnTakeover = false;
        h.zone(1, ZoneManualStatus.CLOSED.code());
        long now = System.currentTimeMillis();
        long target = now - 60_000;
        PlayerState current = RollbackJobSqlTest.currentState();
        h.player(11, 1, current.toByteArray(), now - 1000, 2, false);
        h.player(12, 1, current.toByteArray(), now - 1000, 2, true);
        h.snapshot(7011, 11, target - 10, SnapshotCauses.LOGOUT, 1, 5, 2002,
                RollbackJobSqlTest.snapshotState().toByteArray());
        h.snapshot(7012, 12, target - 10, SnapshotCauses.LOGOUT, 1, 5, 2002,
                RollbackJobSqlTest.snapshotState().toByteArray());

        OpsJobRow job = h.await(h.submit(zones(List.of(1L), false, target), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_REJECTED);
        assertThat(job.getResultCode()).isEqualTo("zone_not_quiescent");
        assertThat(h.summary(job).at("/unclaimedCount").asInt()).isEqualTo(1);
        assertThat(h.summary(job).at("/unclaimedPlayers/0").asText()).isEqualTo("12");
        assertThat(persisted(11)).isEqualTo(current);
        assertThat(persisted(12)).isEqualTo(current);
        assertThat(h.count("SELECT COUNT(*) FROM player_snapshot WHERE cause = ?", SnapshotCauses.PRE_ROLLBACK)).isZero();
        // 已夺到的 11 已释放（epoch 加一后释放）
        assertThat(h.db.tx().execute(s -> h.db.playerMapper.selectOwnerForUpdate(11)).released()).isTrue();
        assertThat(h.ownership.heldCount()).isZero();
    }

    @Test
    void 整区多名在线都夺不到_第二轮共用一个claimWait_zone_not_quiescent带全部玩家号() throws Exception {
        h = new RollbackHarness(Map.of("xm.data.ops.claim-wait", "1s"));
        h.sceneReleasesOnTakeover = false;
        h.zone(1, ZoneManualStatus.MAINTENANCE.code());
        long now = System.currentTimeMillis();
        long target = now - 60_000;
        PlayerState current = RollbackJobSqlTest.currentState();
        h.player(10, 1, current.toByteArray(), now - 1000, 2, false);
        h.snapshot(7010, 10, target - 10, SnapshotCauses.LOGOUT, 1, 5, 2002,
                RollbackJobSqlTest.snapshotState().toByteArray());
        for (long p = 11; p <= 14; p++) {
            h.player(p, 1, current.toByteArray(), now - 1000, 2, true);
            h.snapshot(7000 + p, p, target - 10, SnapshotCauses.LOGOUT, 1, 5, 2002,
                    RollbackJobSqlTest.snapshotState().toByteArray());
        }

        OpsJobRow job = h.await(h.submit(zones(List.of(1L), false, target), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_REJECTED);
        assertThat(job.getResultCode()).isEqualTo("zone_not_quiescent");
        // 4 人 × 1 s 的旧行为至少 4 s；共用截止时刻约 1 s（第一轮夺到的离线玩家 10 也不会被扣那么久）
        assertThat(job.getFinishedMs() - job.getStartedMs()).isLessThan(3000);
        assertThat(h.summary(job).at("/unclaimedCount").asInt()).isEqualTo(4);
        assertThat(h.summary(job).at("/unclaimedPlayers").size()).isEqualTo(4);
        assertThat(persisted(10)).isEqualTo(current);
        assertThat(h.db.tx().execute(s -> h.db.playerMapper.selectOwnerForUpdate(10)).released()).isTrue();
        assertThat(h.count("SELECT COUNT(*) FROM player_snapshot WHERE cause = ?", SnapshotCauses.PRE_ROLLBACK)).isZero();
    }

    @Test
    void 整区dry_run规模超上限_422_plan_too_large_不逐人计划() throws Exception {
        h = new RollbackHarness(Map.of("xm.data.ops.max-players-per-job", "1"));
        h.zone(1, ZoneManualStatus.OPEN.code());
        long now = System.currentTimeMillis();
        h.player(11, 1, null, 0, 0, false);
        h.player(12, 1, null, 0, 0, false);
        RollbackRequest.Body dry = new RollbackRequest.Body("zones", null, null, true, null, now - 1000, null, null, false,
                false, "预演", true);
        assertThatThrownBy(() -> h.rollbacks.handle(dry, "ops", null))
                .isInstanceOfSatisfying(OpsException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.code()).isEqualTo("plan_too_large");
                    assertThat(e.details()).containsEntry("players", 2L).containsEntry("max", 1);
                });
        // 上限之内照常给计划
        h.close();
        h = new RollbackHarness(Map.of("xm.data.ops.max-players-per-job", "2"));
        h.zone(1, ZoneManualStatus.OPEN.code());
        h.player(11, 1, null, 0, 0, false);
        h.player(12, 1, null, 0, 0, false);
        Map<String, Object> plan = h.rollbacks.handle(dry, "ops", null);
        assertThat(plan.get("dryRun")).isEqualTo(true);
        assertThat(plan.get("tooLarge")).isEqualTo(false);
    }

    @Test
    void 空区_zone_empty_全服按目录展开() throws Exception {
        h = new RollbackHarness(Map.of());
        h.zone(5, ZoneManualStatus.MAINTENANCE.code());
        h.zone(6, ZoneManualStatus.CLOSED.code());
        OpsJobRow job = h.await(h.submit(zones(null, true, System.currentTimeMillis() - 1000), "k1"));
        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_SUCCEEDED);
        assertThat(job.getResultCode()).isEqualTo("zone_empty");
        assertThat(job.getRequestJson()).contains("\"expandedZones\":[5,6]");
    }

    @Test
    void 规模超上限_422_plan_too_large() throws Exception {
        h = new RollbackHarness(Map.of("xm.data.ops.max-players-per-job", "1"));
        h.zone(1, ZoneManualStatus.MAINTENANCE.code());
        long now = System.currentTimeMillis();
        h.player(11, 1, null, 0, 0, false);
        h.player(12, 1, null, 0, 0, false);
        assertThatThrownBy(() -> h.submit(zones(List.of(1L), false, now - 1000), "k1"))
                .isInstanceOfSatisfying(OpsException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.code()).isEqualTo("plan_too_large");
                });
    }
}
