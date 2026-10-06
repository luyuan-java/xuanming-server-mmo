package com.game.data.rollback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.game.data.ops.OpsException;
import com.game.data.ops.pb.OpsJobPlayerRow;
import com.game.data.ops.pb.OpsJobRow;
import com.game.data.ops.pb.OpsJobStatus;
import com.game.data.snapshot.SnapshotCauses;
import com.game.data.store.PersistedPlayer;
import com.game.data.store.PlayerSnapshotEntry;
import com.game.data.store.TransactionLogEntry;
import com.game.data.store.TransactionLogQuery;
import com.game.player.store.OwnerState;
import com.game.player.store.state.AssetOpLedgerState;
import com.game.player.store.state.AssetOpStreamLedgerState;
import com.game.player.store.state.BagItemState;
import com.game.player.store.state.BagState;
import com.game.player.store.state.CurrencyState;
import com.game.player.store.state.Facing;
import com.game.player.store.state.MissionState;
import com.game.player.store.state.PetEntry;
import com.game.player.store.state.PetState;
import com.game.player.store.state.PlayerState;
import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 回档作业端到端（生产的受理 / 作业框架 / 栅栏 / 写事务，H2 或 {@code -Dxm.it.mysql} 的真 MySQL；让出请求、墓碑、帮会是替身）：
 * T-F1 / T-F2 / T-A1 / T-A2 / T-R1 / T-R2 / T-R3 / T-J1 的主体。
 */
class RollbackJobSqlTest {

    private static final long P = 1001;
    private static final long SNAP = 5001;

    private RollbackHarness h;

    @AfterEach
    void tearDown() throws Exception {
        if (h != null) {
            h.close();
        }
    }

    // ------------------------------------------------------------------ 素材

    /** 快照：金币 1000、钻石 0；物品 uuid=1 ×5；宝宝 1 只；账本空；任务有一条进行中。 */
    static PlayerState snapshotState() {
        return PlayerState.newBuilder()
                .setCurrency(CurrencyState.newBuilder().addBalances(1000).addBalances(0))
                .setBag(BagState.newBuilder().addItems(item(1, 100, 5, 0)))
                .setPets(PetState.newBuilder().addPets(PetEntry.newBuilder().setPetId(9001).setPetTableId(1).setLevel(3)))
                .setMission(MissionState.newBuilder().addCompletedIds(11))
                .setFacing(Facing.newBuilder().setX(1))
                .build();
    }

    /** 现档：金币 1500、钻石 20、封禁金币；物品 uuid=1 ×7、uuid=2 ×1（快照之后新增）；宝宝 2 只；任务多完成一条；没有朝向。 */
    static PlayerState currentState() {
        return PlayerState.newBuilder()
                .setCurrency(CurrencyState.newBuilder().addBalances(1500).addBalances(20).addBlockedTypes(0))
                .setBag(BagState.newBuilder().addItems(item(1, 100, 7, 0)).addItems(item(2, 200, 1, 1)))
                .setPets(PetState.newBuilder().addPets(PetEntry.newBuilder().setPetId(9001).setPetTableId(1).setLevel(5))
                        .addPets(PetEntry.newBuilder().setPetId(9002).setPetTableId(2)))
                .setMission(MissionState.newBuilder().addCompletedIds(11).addCompletedIds(12))
                .build();
    }

    static BagItemState item(long uuid, int config, int stack, int pos) {
        return BagItemState.newBuilder().setItemUuid(uuid).setConfigId(config).setStackSize(stack).setPos(pos).setBagType(0)
                .build();
    }

    /** 一条流的账本：seq 1..n 已见且已应用。 */
    static AssetOpLedgerState ledger(int stream, long epoch, int applied) {
        AssetOpStreamLedgerState.Builder s = AssetOpStreamLedgerState.newBuilder().setStream(stream).setWatermark(0)
                .setStreamEpoch(epoch).setMaxSeq(applied);
        long word = applied == 0 ? 0 : (applied >= 64 ? -1L : (1L << applied) - 1);
        for (int i = 0; i < 16; i++) {
            s.addSeenBits(i == 0 ? word : 0).addAppliedBits(i == 0 ? word : 0);
        }
        return AssetOpLedgerState.newBuilder().addStreams(s).build();
    }

    private long stateUpdatedAt;

    /** 离线玩家 P（epoch 3，已释放）+ 一份 LOGOUT 快照。 */
    private void offlinePlayer(PlayerState current) {
        stateUpdatedAt = System.currentTimeMillis() - 5000;
        h.player(P, 1, current.toByteArray(), stateUpdatedAt, 3, false);
        h.logoutSnapshot(SNAP, P, System.currentTimeMillis() - 60_000, snapshotState().toByteArray());
    }

    private PlayerState persisted(long playerId) throws Exception {
        PersistedPlayer p = h.db.players.find(playerId);
        return PlayerState.parseFrom(p.stateBytes());
    }

    private OwnerState owner(long playerId) {
        return h.db.tx().execute(s -> h.db.playerMapper.selectOwnerForUpdate(playerId));
    }

    private List<TransactionLogEntry> rollbackRows(long playerId) {
        List<TransactionLogEntry> out = new ArrayList<>();
        for (TransactionLogEntry e : h.db.txlog.query(TransactionLogQuery.builder().reasons(List.of(16))
                .window(0, Long.MAX_VALUE).fetch(100).build())) {
            if (e.getFromPlayer() == playerId || e.getToPlayer() == playerId) {
                out.add(e);
            }
        }
        return out;
    }

    private int preSnapshots() {
        return h.count("SELECT COUNT(*) FROM player_snapshot WHERE cause = ?", SnapshotCauses.PRE_ROLLBACK);
    }

    private void assertUntouched(PlayerState expected) throws Exception {
        assertThat(persisted(P)).isEqualTo(expected);
        assertThat(preSnapshots()).isZero();
        assertThat(rollbackRows(P)).isEmpty();
    }

    // ------------------------------------------------------------------ 用例

    @Test
    void 离线玩家整份回档_安全快照_覆盖写_流水_明细一个事务写齐_封禁保留_释放归属并写墓碑() throws Exception {
        h = new RollbackHarness(Map.of());
        offlinePlayer(currentState());
        long jobId = h.submit(RollbackHarness.players(List.of("1001"), "5001", null, null, null, false), "k-full");
        OpsJobRow job = h.await(jobId);

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_SUCCEEDED);
        assertThat(job.getResultCode()).isEqualTo("ok");
        assertThat(job.getPlayersAffected()).isEqualTo(1);
        // 覆盖写：整份换成快照内容（快照之后的物品 / 宝宝 / 任务被清掉），封禁名单保留现档值（D6）
        PlayerState expected = snapshotState().toBuilder()
                .setCurrency(snapshotState().getCurrency().toBuilder().addBlockedTypes(0)).build();
        assertThat(persisted(P)).isEqualTo(expected);
        PersistedPlayer row = h.db.players.find(P);
        assertThat(row.getLevel()).isEqualTo(5);
        assertThat(row.getSceneConfigId()).isEqualTo(2002);
        assertThat(row.getPosX()).isEqualTo(10.5);
        assertThat(row.getStateSavedEpoch()).isEqualTo(4L);
        // 安全快照：被覆盖之前的现档，内容时刻 = 现档的 updated_at，带操作人与作业号
        List<PlayerSnapshotEntry> pre = h.db.snapshots.listByPlayer(P, 0, Long.MAX_VALUE,
                List.of(SnapshotCauses.PRE_ROLLBACK), false, 10);
        assertThat(pre).hasSize(1);
        assertThat(pre.get(0).getTimeMs()).isEqualTo(stateUpdatedAt);
        assertThat(pre.get(0).getOperator()).isEqualTo("ops");
        assertThat(pre.get(0).getNote()).isEqualTo("job:" + Long.toUnsignedString(jobId));
        assertThat(h.db.snapshots.findById(pre.get(0).getSnapshotId()).getPlayerState())
                .isEqualTo(currentState().toByteArray());
        // 回档流水：金币 1500→1000、钻石 20→0（扣减方）；物品 uuid1 7→5、uuid2 1→0（扣减方）；correlation_id = 作业号
        List<TransactionLogEntry> rows = rollbackRows(P);
        assertThat(rows).hasSize(4);
        assertThat(rows).allSatisfy(e -> {
            assertThat(e.getCorrelationId()).isEqualTo(jobId);
            assertThat(e.getFromPlayer()).isEqualTo(P);
            assertThat(e.getToPlayer()).isZero();
        });
        assertThat(rows.stream().filter(e -> e.getKind() == 1 && e.getCurrencyType() == 0).findFirst().orElseThrow())
                .satisfies(e -> {
                    assertThat(e.getBalanceBefore()).isEqualTo(1500);
                    assertThat(e.getBalanceAfter()).isEqualTo(1000);
                    assertThat(e.getCurrencyDelta()).isEqualTo(-500);
                });
        assertThat(rows.stream().filter(e -> e.getKind() == 2 && e.getItemUuid() == 1).findFirst().orElseThrow()
                .getItemQuantity()).isEqualTo(2);
        // 明细
        OpsJobPlayerRow detail = h.jobs.players(jobId, 0, 10).get(0);
        assertThat(detail.getOutcome()).isEqualTo("RESTORED");
        assertThat(detail.getPreSnapshotId()).isEqualTo(pre.get(0).getSnapshotId());
        assertThat(detail.getPlannedSnapshotId()).isEqualTo(SNAP);
        assertThat(detail.getClaimedEpoch()).isEqualTo(4L);
        // 归属：epoch 加一（夺权）后已释放；释放前写了墓碑
        OwnerState owner = owner(P);
        assertThat(owner.ownerEpoch()).isEqualTo(4L);
        assertThat(owner.released()).isTrue();
        assertThat(h.tombstones).singleElement().satisfies(t -> assertThat(t).containsExactly(P, 4L));
        assertThat(h.ownership.heldCount()).isZero();
        assertThat(h.eventTypes(jobId)).containsExactly("STARTED", "PLANNED", "CLAIMED", "CHECK", "WRITE", "RECHECK",
                "RESULT");
        // 帮会检查的起点 = 快照内容时刻 − 300 s（不取整到秒）
        assertThat(h.guildCalls).hasSize(2);
        PlayerSnapshotEntry snap = h.db.snapshots.findById(SNAP);
        assertThat(h.guildCalls.get(0).getSinceMs()).isEqualTo(snap.getTimeMs() - 300_000);
        assertThat(h.guildCalls.get(0).getPlayerIdsList()).containsExactly(P);
    }

    @Test
    void 在线且缺省reject_作业REJECTED_player_online_零写入_不踢人() throws Exception {
        h = new RollbackHarness(Map.of());
        h.player(P, 1, currentState().toByteArray(), System.currentTimeMillis(), 3, true);
        h.logoutSnapshot(SNAP, P, System.currentTimeMillis() - 60_000, snapshotState().toByteArray());

        OpsJobRow job = h.await(h.submit(RollbackHarness.players(List.of("1001"), "5001", null, null, null, false), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_REJECTED);
        assertThat(job.getResultCode()).isEqualTo("player_online");
        assertUntouched(currentState());
        assertThat(h.takeovers).isEmpty();
        assertThat(owner(P).ownerEpoch()).isEqualTo(3L);
        assertThat(owner(P).released()).isFalse();
        assertThat(h.jobs.players(job.getJobId(), 0, 10).get(0).getOutcome()).isEqualTo("player_online");
        // 先 STARTED 后夺权：被拒的尝试也留痕（D3）
        assertThat(h.eventTypes(job.getJobId())).startsWith("STARTED");
    }

    @Test
    void 在线且kick_发让出请求_scene释放后夺到_回档成功() throws Exception {
        h = new RollbackHarness(Map.of());
        h.player(P, 1, currentState().toByteArray(), System.currentTimeMillis(), 3, true);
        h.logoutSnapshot(SNAP, P, System.currentTimeMillis() - 60_000, snapshotState().toByteArray());

        OpsJobRow job = h.await(h.submit(RollbackHarness.players(List.of("1001"), "5001", null, null, "kick", false), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_SUCCEEDED);
        assertThat(h.takeovers).isNotEmpty();
        assertThat(h.takeovers.get(0)).containsExactly(P, 3L);
        assertThat(owner(P).ownerEpoch()).isEqualTo(4L);
        assertThat(owner(P).released()).isTrue();
        assertThat(persisted(P).getCurrency().getBalances(0)).isEqualTo(1000);
    }

    @Test
    void kick但持有者不放_claimWait内夺不到_player_busy_零写入() throws Exception {
        h = new RollbackHarness(Map.of("xm.data.ops.claim-wait", "300ms"));
        h.sceneReleasesOnTakeover = false;
        h.player(P, 1, currentState().toByteArray(), System.currentTimeMillis(), 3, true);
        h.logoutSnapshot(SNAP, P, System.currentTimeMillis() - 60_000, snapshotState().toByteArray());

        OpsJobRow job = h.await(h.submit(RollbackHarness.players(List.of("1001"), "5001", null, null, "kick", false), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_REJECTED);
        assertThat(job.getResultCode()).isEqualTo("player_busy");
        assertThat(h.takeovers.size()).isGreaterThan(1); // 每次重试都重发让出请求
        assertUntouched(currentState());
    }

    @Test
    void kick多名在线且持有者都不放_第二轮共用一个claimWait_每人player_busy_不是人数乘等待() throws Exception {
        h = new RollbackHarness(Map.of("xm.data.ops.claim-wait", "1s"));
        h.sceneReleasesOnTakeover = false;
        long t = System.currentTimeMillis() - 120_000;
        List<String> ids = new ArrayList<>();
        for (long p = 2001; p <= 2005; p++) {
            h.player(p, 1, currentState().toByteArray(), System.currentTimeMillis() - 5000, 3, true);
            h.snapshot(7000 + p, p, t, SnapshotCauses.LOGOUT, 1, 5, 2002, snapshotState().toByteArray());
            ids.add(Long.toString(p));
        }

        OpsJobRow job = h.await(h.submit(RollbackHarness.players(ids, null, t + 1, null, "kick", false), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_REJECTED);
        assertThat(job.getResultCode()).isEqualTo("player_busy");
        assertThat(h.jobs.players(job.getJobId(), 0, 10)).extracting(OpsJobPlayerRow::getOutcome)
                .containsOnly("player_busy").hasSize(5);
        // 5 人 × 1 s 的旧行为至少 5 s；共用截止时刻约 1 s
        assertThat(job.getFinishedMs() - job.getStartedMs()).isLessThan(3000);
        // 截止之后每人仍再试一次、照样重发让出请求
        assertThat(h.takeovers.stream().map(x -> x[0]).distinct()).hasSize(5);
        for (long p = 2001; p <= 2005; p++) {
            assertThat(persisted(p)).isEqualTo(currentState());
        }
    }

    @Test
    void 沉降之后已超时_FAILED_job_timeout_零写入_不问帮会_不写ACCEPTED_释放() throws Exception {
        h = new RollbackHarness(Map.of("xm.data.ops.job-timeout", "500ms", "xm.data.rollback.guild.settle", "1s"));
        offlinePlayer(currentState());
        long after = System.currentTimeMillis();
        h.guildAnswers.add(r -> RollbackHarness.ok(RollbackHarness.op(70, P, after)));

        OpsJobRow job = h.await(h.submit(RollbackHarness.players(List.of("1001"), "5001", null, null, null, true), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_FAILED);
        assertThat(job.getResultCode()).isEqualTo("job_timeout");
        assertUntouched(currentState());
        assertThat(h.guildCalls).isEmpty();
        assertThat(h.eventTypes(job.getJobId())).doesNotContain("ACCEPTED", "WRITE");
        assertThat(h.jobs.players(job.getJobId(), 0, 10).get(0).getOutcome()).isEqualTo("rejected");
        assertThat(owner(P).released()).isTrue();
        assertThat(h.ownership.heldCount()).isZero();
    }

    @Test
    void 部分回档选了assets_快照与现档有不同的未知顶层字段_unknown_sections_不写_释放_dry_run同样标出() throws Exception {
        h = new RollbackHarness(Map.of());
        PlayerState current = withUnknown(currentState(), 99, "cur");
        stateUpdatedAt = System.currentTimeMillis() - 5000;
        h.player(P, 1, current.toByteArray(), stateUpdatedAt, 3, false);
        h.logoutSnapshot(SNAP, P, System.currentTimeMillis() - 60_000, withUnknown(snapshotState(), 99, "snap").toByteArray());

        OpsJobRow job = h.await(h.submit(RollbackHarness.players(List.of("1001"), "5001", null, List.of("assets"), null,
                false), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_REJECTED);
        assertThat(job.getResultCode()).isEqualTo("unknown_sections");
        assertThat(h.jobs.players(job.getJobId(), 0, 10).get(0).getOutcome()).isEqualTo("unknown_sections");
        assertThat(persisted(P)).isEqualTo(current);
        assertThat(preSnapshots()).isZero();
        assertThat(owner(P).released()).isTrue();
        assertThat(h.ownership.heldCount()).isZero();

        RollbackRequest.Body dry = new RollbackRequest.Body("players", List.of("1001"), null, null, "5001", null,
                List.of("assets"), null, false, false, "预演", true);
        JsonNode view = h.json.valueToTree(h.rollbacks.handle(dry, "ops", null));
        assertThat(view.at("/players/0/outcome").asText()).isEqualTo("unknown_sections");
        assertThat(view.at("/players/0/unknownFields/0").asInt()).isEqualTo(99);
        // 整份回档不受影响（未知字段随快照整份替换）
        OpsJobRow full = h.await(h.submit(RollbackHarness.players(List.of("1001"), "5001", null, null, null, false), "k2"));
        assertThat(full.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_SUCCEEDED);
        assertThat(persisted(P).getUnknownFields().getField(99).getLengthDelimitedList())
                .containsExactly(ByteString.copyFromUtf8("snap"));
    }

    static PlayerState withUnknown(PlayerState state, int field, String marker) {
        return state.toBuilder().setUnknownFields(UnknownFieldSet.newBuilder()
                .addField(field, UnknownFieldSet.Field.newBuilder().addLengthDelimited(ByteString.copyFromUtf8(marker)).build())
                .build()).build();
    }

    @Test
    void 帮会检查没装配_FAILED_check_failed_零写入_已夺的归属释放() throws Exception {
        h = new RollbackHarness(Map.of(), false);
        offlinePlayer(currentState());

        OpsJobRow job = h.await(h.submit(RollbackHarness.players(List.of("1001"), "5001", null, null, null, true), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_FAILED);
        assertThat(job.getResultCode()).isEqualTo("rollback_guild_check_failed");
        // 放行（acceptDivergence）对问不到无效
        assertUntouched(currentState());
        assertThat(owner(P).released()).isTrue();
        assertThat(h.ownership.heldCount()).isZero();
    }

    @Test
    void 帮会有分歧_缺省拒绝零写入_带原因放行后先写ACCEPTED再写() throws Exception {
        h = new RollbackHarness(Map.of());
        offlinePlayer(currentState());
        long after = System.currentTimeMillis();
        h.guildAnswers.add(r -> RollbackHarness.ok(RollbackHarness.op(70, P, after)));

        OpsJobRow rejected = h.await(h.submit(RollbackHarness.players(List.of("1001"), "5001", null, null, null, false),
                "k1"));
        assertThat(rejected.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_REJECTED);
        assertThat(rejected.getResultCode()).isEqualTo("rollback_guild_divergence");
        assertThat(rejected.getDivergenceRows()).isEqualTo(1);
        assertUntouched(currentState());

        OpsJobRow accepted = h.await(h.submit(RollbackHarness.players(List.of("1001"), "5001", null, null, null, true),
                "k2"));
        assertThat(accepted.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_SUCCEEDED);
        assertThat(accepted.getAcceptedDivergence()).isTrue();
        List<String> events = h.eventTypes(accepted.getJobId());
        assertThat(events.indexOf("ACCEPTED")).isGreaterThan(events.indexOf("CHECK")).isLessThan(events.indexOf("WRITE"));
        assertThat(persisted(P).getCurrency().getBalances(0)).isEqualTo(1000);
    }

    @Test
    void 帮会结果码非OK_一律check_failed() throws Exception {
        h = new RollbackHarness(Map.of());
        offlinePlayer(currentState());
        h.guildAnswers.add(r -> com.game.api.proto.ListAppliedAssetOpsSinceResponse.getDefaultInstance());

        OpsJobRow job = h.await(h.submit(RollbackHarness.players(List.of("1001"), "5001", null, null, null, true), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_FAILED);
        assertThat(job.getResultCode()).isEqualTo("rollback_guild_check_failed");
        assertUntouched(currentState());
    }

    @Test
    void 写后复查发现新op_DIVERGED_AFTER_WRITE_数据不撤销() throws Exception {
        h = new RollbackHarness(Map.of());
        offlinePlayer(currentState());
        long after = System.currentTimeMillis();
        h.guildAnswers.add(r -> RollbackHarness.ok());
        h.guildAnswers.add(r -> RollbackHarness.ok(RollbackHarness.op(71, P, after)));

        OpsJobRow job = h.await(h.submit(RollbackHarness.players(List.of("1001"), "5001", null, null, null, false), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_DIVERGED_AFTER_WRITE);
        assertThat(job.getResultCode()).isEqualTo("rollback_guild_diverged_after_write");
        assertThat(persisted(P).getCurrency().getBalances(0)).isEqualTo(1000);
        // 复查在释放之前（I4）：复查时归属仍由我们持有，之后才释放
        assertThat(owner(P).released()).isTrue();
    }

    @Test
    void 账本差集_快照之后已应用的seq_缺省拒绝_ledger_divergence() throws Exception {
        h = new RollbackHarness(Map.of());
        PlayerState current = currentState().toBuilder().setAssetLedger(ledger(1, 100, 2)).build();
        stateUpdatedAt = System.currentTimeMillis() - 5000;
        h.player(P, 1, current.toByteArray(), stateUpdatedAt, 3, false);
        h.logoutSnapshot(SNAP, P, System.currentTimeMillis() - 60_000,
                snapshotState().toBuilder().setAssetLedger(ledger(1, 100, 0)).build().toByteArray());

        OpsJobRow job = h.await(h.submit(RollbackHarness.players(List.of("1001"), "5001", null, null, null, false), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_REJECTED);
        assertThat(job.getResultCode()).isEqualTo("ledger_divergence");
        assertThat(job.getDivergenceRows()).isEqualTo(2);
        assertThat(persisted(P)).isEqualTo(current);
    }

    @Test
    void 部分回档_只换选中段_资产组四段一起_其余段与未选的行列保留现档() throws Exception {
        h = new RollbackHarness(Map.of());
        offlinePlayer(currentState());

        OpsJobRow job = h.await(h.submit(RollbackHarness.players(List.of("1001"), "5001", null,
                List.of("level", "assets"), null, false), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_SUCCEEDED);
        PlayerState now = persisted(P);
        assertThat(now.getCurrency().getBalancesList()).containsExactly(1000L, 0L);
        assertThat(now.getCurrency().getBlockedTypesList()).containsExactly(0);
        assertThat(now.getBag()).isEqualTo(snapshotState().getBag());
        assertThat(now.getPets()).isEqualTo(snapshotState().getPets());
        assertThat(now.getMission()).isEqualTo(currentState().getMission());
        assertThat(now.hasFacing()).isFalse();
        PersistedPlayer row = h.db.players.find(P);
        assertThat(row.getLevel()).isEqualTo(5);
        assertThat(row.getSceneConfigId()).isEqualTo(1001); // position 没选：保留现档
    }

    @Test
    void 按时刻选源_不选安全快照_同毫秒取号大的() throws Exception {
        h = new RollbackHarness(Map.of());
        long t = System.currentTimeMillis() - 120_000;
        h.player(P, 1, currentState().toByteArray(), System.currentTimeMillis() - 5000, 3, false);
        h.snapshot(6001, P, t, SnapshotCauses.LOGOUT, 1, 5, 2002, snapshotState().toByteArray());
        h.snapshot(6002, P, t, SnapshotCauses.LOGIN, 1, 6, 2002,
                snapshotState().toBuilder().setCurrency(CurrencyState.newBuilder().addBalances(777)).build().toByteArray());
        h.snapshot(6003, P, t + 10, SnapshotCauses.PRE_ROLLBACK, 1, 7, 2002, currentState().toByteArray());

        OpsJobRow job = h.await(h.submit(RollbackHarness.players(List.of("1001"), null, t + 20, null, null, false), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_SUCCEEDED);
        assertThat(h.jobs.players(job.getJobId(), 0, 10).get(0).getPlannedSnapshotId()).isEqualTo(6002);
        assertThat(persisted(P).getCurrency().getBalances(0)).isEqualTo(777);
    }

    @Test
    void 快照属于别的玩家_404_snapshot_not_found_不受理() throws Exception {
        h = new RollbackHarness(Map.of());
        offlinePlayer(currentState());
        h.player(2002, 1, currentState().toByteArray(), System.currentTimeMillis(), 1, false);

        assertThatThrownBy(() -> h.submit(RollbackHarness.players(List.of("2002"), "5001", null, null, null, false), "k1"))
                .isInstanceOfSatisfying(OpsException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.code()).isEqualTo("snapshot_not_found");
                });
        assertThat(h.db.count("ops_job")).isZero();
    }

    @Test
    void 多人回档_夺不到的只记结局_其余照写_PARTIAL() throws Exception {
        h = new RollbackHarness(Map.of());
        long t = System.currentTimeMillis() - 120_000;
        h.player(P, 1, currentState().toByteArray(), System.currentTimeMillis() - 5000, 3, false);
        h.player(1002, 1, currentState().toByteArray(), System.currentTimeMillis() - 5000, 3, true);
        h.player(1003, 1, currentState().toByteArray(), System.currentTimeMillis() - 5000, 3, false);
        h.snapshot(6001, P, t, SnapshotCauses.LOGOUT, 1, 5, 2002, snapshotState().toByteArray());
        h.snapshot(6002, 1002, t, SnapshotCauses.LOGOUT, 1, 5, 2002, snapshotState().toByteArray());

        OpsJobRow job = h.await(h.submit(RollbackHarness.players(List.of("1003", "1002", "1001", "1001"), null, t + 1,
                null, null, false), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_PARTIAL);
        assertThat(job.getPlayersPlanned()).isEqualTo(3);
        assertThat(job.getPlayersAffected()).isEqualTo(1);
        List<String> outcomes = h.jobs.players(job.getJobId(), 0, 10).stream().map(OpsJobPlayerRow::getOutcome).toList();
        assertThat(outcomes).containsExactly("RESTORED", "player_online", "no_snapshot");
        assertThat(persisted(1002)).isEqualTo(currentState());
    }

    @Test
    void 幂等键_同参回原作业_异参409_写开关关闭503_dry_run不受影响() throws Exception {
        h = new RollbackHarness(Map.of());
        offlinePlayer(currentState());
        RollbackRequest.Body body = RollbackHarness.players(List.of("1001"), "5001", null, null, null, false);
        long jobId = h.submit(body, "same");
        h.await(jobId);
        Map<String, Object> replay = h.rollbacks.handle(body, "ops", "same");
        assertThat(replay.get("jobId")).isEqualTo(Long.toUnsignedString(jobId));
        assertThat(replay.get("replayed")).isEqualTo(true);
        assertThatThrownBy(() -> h.rollbacks.handle(RollbackHarness.players(List.of("1001"), "5001", null,
                List.of("level"), null, false), "ops", "same"))
                .isInstanceOfSatisfying(OpsException.class, e -> assertThat(e.code()).isEqualTo("idempotency_conflict"));
        assertThatThrownBy(() -> h.rollbacks.handle(body, "ops", null))
                .isInstanceOfSatisfying(OpsException.class, e -> assertThat(e.code()).isEqualTo("invalid_request"));
        h.close();

        h = new RollbackHarness(Map.of("xm.data.ops.enabled", "false"));
        offlinePlayer(currentState());
        assertThatThrownBy(() -> h.rollbacks.handle(body, "ops", "k"))
                .isInstanceOfSatisfying(OpsException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.code()).isEqualTo("ops_disabled");
                });
        RollbackRequest.Body dry = new RollbackRequest.Body("players", List.of("1001"), null, null, "5001", null, null,
                null, false, false, "预演", true);
        Map<String, Object> plan = h.rollbacks.handle(dry, "ops", null);
        assertThat(plan.get("dryRun")).isEqualTo(true);
        JsonNode view = h.json.valueToTree(plan);
        assertThat(view.at("/players/0/snapshot/snapshotId").asText()).isEqualTo("5001");
        assertThat(view.at("/players/0/restore/mode").asText()).isEqualTo("FULL");
        assertThat(h.db.count("ops_job")).isZero();
        assertUntouched(currentState());
    }

    @Test
    void 已有作业在跑_第二个409_ops_busy_带在跑的作业号() throws Exception {
        h = new RollbackHarness(Map.of("xm.data.rollback.guild.settle", "2s"));
        offlinePlayer(currentState());
        long first = h.submit(RollbackHarness.players(List.of("1001"), "5001", null, null, null, false), "k1");
        assertThatThrownBy(() -> h.submit(RollbackHarness.players(List.of("1001"), "5001", null, List.of("level"), null,
                false), "k2"))
                .isInstanceOfSatisfying(OpsException.class, e -> {
                    assertThat(e.code()).isEqualTo("ops_busy");
                    assertThat(e.details().get("runningJobId")).isEqualTo(Long.toUnsignedString(first));
                });
        assertThat(h.await(first).getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_SUCCEEDED);
    }

    @Test
    void 取消只在第一笔写之前有效_沉降期间取消_CANCELLED_零写入_释放() throws Exception {
        h = new RollbackHarness(Map.of("xm.data.rollback.guild.settle", "1500ms"));
        offlinePlayer(currentState());
        long jobId = h.submit(RollbackHarness.players(List.of("1001"), "5001", null, null, null, false), "k1");
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!h.eventTypes(jobId).contains("CLAIMED") && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(h.service.cancel(jobId).get("cancelRequested")).isEqualTo(true);
        // 重复取消（标志已是 1）仍报已请求，不误报「作业已终结」
        assertThat(h.service.cancel(jobId).get("cancelRequested")).isEqualTo(true);

        OpsJobRow job = h.await(jobId);
        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_CANCELLED);
        assertThat(job.getCancelRequested()).isTrue();
        assertUntouched(currentState());
        assertThat(owner(P).released()).isTrue();
        assertThat(h.service.cancel(jobId).get("cancelRequested")).isEqualTo(false);
    }

    @Test
    void 安全快照可撤销上一次回档_按preSnapshotId显式选用() throws Exception {
        h = new RollbackHarness(Map.of());
        offlinePlayer(currentState());
        long first = h.submit(RollbackHarness.players(List.of("1001"), "5001", null, null, null, false), "k1");
        h.await(first);
        long pre = h.jobs.players(first, 0, 1).get(0).getPreSnapshotId();

        OpsJobRow undo = h.await(h.submit(RollbackHarness.players(List.of("1001"), Long.toUnsignedString(pre), null, null,
                null, false), "k2"));

        assertThat(undo.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_SUCCEEDED);
        assertThat(persisted(P).getCurrency().getBalancesList()).containsExactly(1500L, 20L);
        assertThat(persisted(P).getBag()).isEqualTo(currentState().getBag());
        assertThat(Collections.frequency(h.eventTypes(undo.getJobId()), "RESULT")).isEqualTo(1);
    }
}
