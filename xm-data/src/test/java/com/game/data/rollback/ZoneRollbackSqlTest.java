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
import java.util.ArrayList;
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
            try {
                h.assertPlayerOutcomeLabelsWithinFixedSet();
            } finally {
                h.close();
            }
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

    // ------------------------------------------------------------------ 审计 OPS-13：整区下的帮会检查（T-R3 的 D11 / D12）

    /** 区 1 里 {@code count} 名离线玩家（号从 {@code first} 起连续），各有一份目标时刻之前的 LOGOUT 快照。一个事务里造完。 */
    private List<Long> zonePlayers(long first, int count, long target) {
        long now = System.currentTimeMillis();
        List<Long> ids = new ArrayList<>();
        h.db.tx().executeWithoutResult(status -> {
            for (long p = first; p < first + count; p++) {
                h.player(p, 1, RollbackJobSqlTest.currentState().toByteArray(), now - 1000, 2, false);
                h.snapshot(100_000 + p, p, target - 10, SnapshotCauses.LOGOUT, 1, 5, 2002,
                        RollbackJobSqlTest.snapshotState().toByteArray());
                ids.add(p);
            }
        });
        return ids;
    }

    private static RollbackRequest.Body zonesAccepting(long target) {
        return new RollbackRequest.Body("zones", null, List.of(1L), false, null, target, null, null, true, false,
                "合服前回滚（已核对帮会侧）", false);
    }

    /** 这些玩家都被夺到过（epoch 2 → 3）并已释放，什么也没被写。 */
    private void assertZoneUntouchedAndReleased(List<Long> ids) throws Exception {
        for (long p : ids) {
            assertThat(persisted(p)).as("玩家 %d 的现档", p).isEqualTo(RollbackJobSqlTest.currentState());
            var owner = h.db.tx().execute(s -> h.db.playerMapper.selectOwnerForUpdate(p));
            assertThat(owner.ownerEpoch()).as("玩家 %d 被夺到过", p).isEqualTo(3);
            assertThat(owner.released()).as("玩家 %d 已释放", p).isTrue();
        }
        assertThat(h.count("SELECT COUNT(*) FROM player_snapshot WHERE cause = ?", SnapshotCauses.PRE_ROLLBACK)).isZero();
        assertThat(h.count("SELECT COUNT(*) FROM transaction_log WHERE reason = 16")).isZero();
        assertThat(h.ownership.heldCount()).isZero();
    }

    @Test
    void 整区_一人有帮会分歧_缺省REJECTED_全区谁也不写全部释放_带原因放行后全区写成() throws Exception {
        h = new RollbackHarness(Map.of());
        h.zone(1, ZoneManualStatus.MAINTENANCE.code());
        long target = System.currentTimeMillis() - 60_000;
        List<Long> ids = zonePlayers(11, 3, target);
        long after = System.currentTimeMillis();
        // 只有 12 在快照之后有一条已应用的帮会指令
        h.guildAnswers.add(r -> RollbackHarness.ok(RollbackHarness.op(70, 12, after)));

        OpsJobRow rejected = h.await(h.submit(zones(List.of(1L), false, target), "k1"));

        assertThat(rejected.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_REJECTED);
        assertThat(rejected.getResultCode()).isEqualTo("rollback_guild_divergence");
        assertThat(rejected.getDivergenceRows()).isEqualTo(1);
        assertThat(h.summary(rejected).at("/guild/sample/0/playerId").asText()).isEqualTo("12");
        assertThat(h.summary(rejected).at("/guild/sample/0/opId").asText()).isEqualTo("70");
        // 整区全有或全无：没有分歧的 11、13 也不写
        assertThat(h.jobs.players(rejected.getJobId(), 0, 10)).extracting(OpsJobPlayerRow::getOutcome)
                .containsExactly("rejected", "rejected", "rejected");
        assertThat(h.eventTypes(rejected.getJobId())).doesNotContain("ACCEPTED", "WRITE");
        assertThat(h.guildCalls).hasSize(1);
        assertThat(h.guildCalls.get(0).getPlayerIdsList()).containsExactly(11L, 12L, 13L);
        assertZoneUntouchedAndReleased(ids);

        OpsJobRow accepted = h.await(h.submit(zonesAccepting(target), "k2"));

        assertThat(accepted.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_SUCCEEDED);
        assertThat(accepted.getAcceptedDivergence()).isTrue();
        assertThat(accepted.getPlayersAffected()).isEqualTo(3);
        assertThat(h.eventTypes(accepted.getJobId())).containsSubsequence("CHECK", "ACCEPTED", "WRITE");
        for (long p : ids) {
            assertThat(persisted(p).getCurrency().getBalances(0)).as("玩家 %d", p).isEqualTo(1000);
        }
    }

    @Test
    void 整区_帮会问不到_FAILED_check_failed_带了放行也无效_全区零写入全部释放() throws Exception {
        h = new RollbackHarness(Map.of(), false); // 帮会检查没装配
        h.zone(1, ZoneManualStatus.MAINTENANCE.code());
        long target = System.currentTimeMillis() - 60_000;
        List<Long> ids = zonePlayers(11, 3, target);

        OpsJobRow job = h.await(h.submit(zonesAccepting(target), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_FAILED);
        assertThat(job.getResultCode()).isEqualTo("rollback_guild_check_failed");
        assertThat(job.getAcceptedDivergence()).isFalse();
        assertThat(h.summary(job).at("/guild/checkFailed").asBoolean()).isTrue();
        assertThat(h.eventTypes(job.getJobId())).doesNotContain("ACCEPTED", "WRITE");
        assertThat(h.jobs.players(job.getJobId(), 0, 10)).extracting(OpsJobPlayerRow::getOutcome)
                .containsExactly("rejected", "rejected", "rejected");
        assertZoneUntouchedAndReleased(ids);
    }

    @Test
    void 整区超过一块_第二块帮会检查失败_第一块已通过的人也不写_谁也不写() throws Exception {
        h = new RollbackHarness(Map.of());
        h.zone(1, ZoneManualStatus.CLOSED.code());
        long target = System.currentTimeMillis() - 60_000;
        // 101 人：帮会检查每块 100 人，第 101 人落在第二块
        List<Long> ids = zonePlayers(1000, GuildDivergenceGate.CHUNK + 1, target);
        h.guildAnswers.add(r -> RollbackHarness.ok());
        h.guildAnswers.add(r -> com.game.api.proto.ListAppliedAssetOpsSinceResponse.newBuilder()
                .setResult(com.game.api.proto.ListAppliedResult.LIST_APPLIED_RESULT_ERROR).setDetail("查询超时").build());

        OpsJobRow job = h.await(h.submit(zonesAccepting(target), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_FAILED);
        assertThat(job.getResultCode()).isEqualTo("rollback_guild_check_failed");
        assertThat(job.getPlayersPlanned()).isEqualTo(101);
        assertThat(job.getPlayersAffected()).isZero();
        // 两块各问了一次：第一块 100 人通过、第二块 1 人失败
        assertThat(h.guildCalls).hasSize(2);
        assertThat(h.guildCalls.get(0).getPlayerIdsCount()).isEqualTo(100);
        assertThat(h.guildCalls.get(1).getPlayerIdsList()).containsExactly(1100L);
        assertThat(h.summary(job).at("/guild/failure").asText()).contains("LIST_APPLIED_RESULT_ERROR");
        assertThat(h.jobs.players(job.getJobId(), 0, 200)).hasSize(101).extracting(OpsJobPlayerRow::getOutcome)
                .containsOnly("rejected");
        assertZoneUntouchedAndReleased(ids);
    }
}
