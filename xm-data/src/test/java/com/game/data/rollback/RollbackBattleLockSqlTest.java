package com.game.data.rollback;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.game.api.proto.GuildAssetOpBrief;
import com.game.audit.proto.TransactionReason;
import com.game.data.ops.pb.OpsJobEventRow;
import com.game.data.ops.pb.OpsJobEventType;
import com.game.data.ops.pb.OpsJobPlayerRow;
import com.game.data.ops.pb.OpsJobRow;
import com.game.data.ops.pb.OpsJobStatus;
import com.game.data.snapshot.SnapshotCauses;
import com.game.gateway.store.ZoneManualStatus;
import com.game.player.store.OwnerState;
import com.game.player.store.state.PlayerState;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 回档前查战斗锁（批次 6.3 追加，data-ops-spec §4.2、§4.12、§13.3；审计 OPS-10）：生产的受理 / 作业框架 / 栅栏 / 写事务（H2 或
 * {@code -Dxm.it.mysql} 的真 MySQL），战斗锁读取是替身（{@link RollbackHarness#battleLocked} / {@link RollbackHarness#battleLockReader}）。
 *
 * <p>钉住的行为：两轮夺权之后、账本差集之前对<b>已夺到</b>的玩家批量查一次；锁在 → {@code in_battle}，读不到 → {@code battle_lock_unknown}
 * （fail-closed），都零写入并释放归属；不在战的照常回档；整区有一人被挡 → {@code zone_not_quiescent}，战斗中的人数单列；dry-run 标出。
 *
 * <p>闸是<b>无条件</b>的（§13.3 设计第 5 条：不是资产分歧检查，不走 {@code acceptDivergence}）：{@code acceptDivergence} /
 * {@code acceptRecallReversal} 的任何组合、回哪些段（含只回非资产段）都不放行，也不进分歧裁决；同样的请求没有锁时确实由这两个开关裁决
 * （对照用例），所以拒绝只能来自锁。
 */
class RollbackBattleLockSqlTest {

    private static final long P = 1001;
    private static final long SNAP = 5001;

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

    // ------------------------------------------------------------------ 素材

    private static final PlayerState CURRENT = RollbackJobSqlTest.currentState();
    private static final PlayerState SNAPSHOT = RollbackJobSqlTest.snapshotState();

    /** 一名玩家（epoch 3）+ 一份一分钟前的 LOGOUT 快照；{@code online} = 被 scene 持有（租约 1 小时）。 */
    private void player(long playerId, long snapshotId, boolean online) {
        h.player(playerId, 1, CURRENT.toByteArray(), System.currentTimeMillis() - 5000, 3, online);
        h.logoutSnapshot(snapshotId, playerId, System.currentTimeMillis() - 60_000, SNAPSHOT.toByteArray());
    }

    private PlayerState persisted(long playerId) throws Exception {
        return PlayerState.parseFrom(h.db.players.find(playerId).stateBytes());
    }

    private OwnerState owner(long playerId) {
        return h.db.tx().execute(s -> h.db.playerMapper.selectOwnerForUpdate(playerId));
    }

    /** 零写入：现档原样、没有安全快照、没有回档流水、没有任何明细是 RESTORED。 */
    private void assertNothingWritten(long jobId, long... playerIds) throws Exception {
        assertNothingWrittenBesides(0, jobId, playerIds);
    }

    /**
     * 零写入，流水表里只有用例自己预置的 {@code seededTxlog} 行（{@link #seedRecall} 的回收扣减），一行不多。
     * 等级 9 / 场景 1001 是 player 行的现值，快照里是 5 / 2002：只回 level / position 的回档写成了也会在这里露出来。
     */
    private void assertNothingWrittenBesides(int seededTxlog, long jobId, long... playerIds) throws Exception {
        for (long playerId : playerIds) {
            assertThat(persisted(playerId)).as("玩家 %d 的现档", playerId).isEqualTo(CURRENT);
            assertThat(h.db.players.find(playerId).getLevel()).as("玩家 %d 的等级", playerId).isEqualTo(9);
            assertThat(h.db.players.find(playerId).getSceneConfigId()).as("玩家 %d 的场景", playerId).isEqualTo(1001);
            assertThat(h.db.players.find(playerId).getStateSavedEpoch()).as("玩家 %d 的现档写入 epoch", playerId).isEqualTo(3L);
        }
        assertThat(h.count("SELECT COUNT(*) FROM player_snapshot WHERE cause = ?", SnapshotCauses.PRE_ROLLBACK)).isZero();
        assertThat(h.count("SELECT COUNT(*) FROM transaction_log")).isEqualTo(seededTxlog);
        assertThat(h.count("SELECT COUNT(*) FROM transaction_log WHERE reason = ?",
                TransactionReason.TX_ROLLBACK_RESTORE_VALUE)).isZero();
        assertThat(h.jobs.players(jobId, 0, 100)).extracting(OpsJobPlayerRow::getOutcome).doesNotContain("RESTORED");
    }

    /** 归属已释放：epoch 被夺权加一（3 → 4）后带围栏释放，释放前写过墓碑，本实例不再持有任何人。 */
    private void assertReleased(long... playerIds) {
        for (long playerId : playerIds) {
            OwnerState o = owner(playerId);
            assertThat(o.ownerEpoch()).as("玩家 %d 的 epoch", playerId).isEqualTo(4L);
            assertThat(o.released()).as("玩家 %d 已释放", playerId).isTrue();
            assertThat(h.tombstones).as("玩家 %d 的墓碑", playerId).anySatisfy(t -> assertThat(t).containsExactly(playerId, 4L));
        }
        assertThat(h.ownership.heldCount()).isZero();
    }

    private Map<String, String> outcomes(long jobId) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (OpsJobPlayerRow row : h.jobs.players(jobId, 0, 100)) {
            out.put(Long.toUnsignedString(row.getPlayerId()), row.getOutcome());
        }
        return out;
    }

    private JsonNode event(long jobId, OpsJobEventType type) throws Exception {
        for (OpsJobEventRow e : h.jobs.events(jobId)) {
            if (e.getType() == type) {
                return h.json.readTree(e.getPayloadJson());
            }
        }
        throw new AssertionError("作业 " + jobId + " 没有 " + type + " 事件：" + h.eventTypes(jobId));
    }

    private static RollbackRequest.Body zones(long target) {
        return new RollbackRequest.Body("zones", null, List.of(1L), false, null, target, null, null, false, false,
                "合服前回滚", false);
    }

    private static RollbackRequest.Body dryRun(List<String> players, String snapshotId, Long target) {
        return new RollbackRequest.Body("players", players, null, null, snapshotId, target, null, null, false, false, "预演",
                true);
    }

    // ------------------------------------------------------------------ 单人 / 多人

    @Test
    void reject夺到的离线在战玩家_in_battle_零写入_归属已释放_查锁在夺权之后帮会检查之前() throws Exception {
        h = new RollbackHarness(Map.of());
        player(P, SNAP, false);
        h.battleLocked.add(P);
        // 读锁那一刻的现场：归属已经在我们手里（3 → 4、未释放），帮会还没被问过
        List<String> atRead = new CopyOnWriteArrayList<>();
        h.battleLockReader = ids -> {
            OwnerState o = owner(P);
            atRead.add("epoch=" + o.ownerEpoch() + " released=" + o.released() + " guildCalls=" + h.guildCalls.size());
            return h.battleLockAnswer(ids);
        };

        OpsJobRow job = h.await(h.submit(RollbackHarness.players(List.of("1001"), "5001", null, null, null, false), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_REJECTED);
        assertThat(job.getResultCode()).isEqualTo("in_battle");
        assertThat(job.getPlayersPlanned()).isEqualTo(1);
        assertThat(job.getPlayersAffected()).isZero();
        assertThat(job.getPlayersFailed()).isEqualTo(1);
        // 明细：in_battle，带夺到的 epoch，没有安全快照、没有写入时刻
        OpsJobPlayerRow detail = h.jobs.players(job.getJobId(), 0, 10).get(0);
        assertThat(detail.getOutcome()).isEqualTo("in_battle");
        assertThat(detail.getClaimedEpoch()).isEqualTo(4L);
        assertThat(detail.getPreSnapshotId()).isZero();
        assertThat(detail.getWrittenMs()).isZero();
        assertNothingWritten(job.getJobId(), P);
        assertReleased(P);
        // 只读了一次锁，问的就是夺到的这一个人；那一刻已夺到、还没问帮会；被挡之后也不再问帮会
        assertThat(h.battleLockCalls).containsExactly(List.of(P));
        assertThat(atRead).containsExactly("epoch=4 released=false guildCalls=0");
        assertThat(h.guildCalls).isEmpty();
        assertThat(h.takeovers).as("离线玩家：不踢人").isEmpty();
        // 事件：查锁的结论随 CLAIMED；没有 CHECK / WRITE
        assertThat(h.eventTypes(job.getJobId())).containsExactly("STARTED", "PLANNED", "CLAIMED", "RESULT");
        JsonNode claimed = event(job.getJobId(), OpsJobEventType.OPS_JOB_EVENT_CLAIMED);
        assertThat(claimed.at("/claimed").asInt()).isEqualTo(1);
        assertThat(claimed.at("/battleLock/inBattleCount").asInt()).isEqualTo(1);
        // 摘要
        JsonNode summary = h.summary(job);
        assertThat(summary.at("/battleLock/checked").asInt()).isEqualTo(1);
        assertThat(summary.at("/battleLock/inBattleCount").asInt()).isEqualTo(1);
        assertThat(summary.at("/battleLock/inBattlePlayers/0").asText()).isEqualTo("1001");
        assertThat(summary.at("/battleLock/unknownCount").asInt()).isZero();
        assertThat(summary.at("/battleLock").has("error")).isFalse();
        assertThat(summary.at("/outcomes/in_battle").asInt()).isEqualTo(1);
        // 指标：in_battle 计一次；读不到与写成都没发生
        assertThat(h.playersCounted("in_battle")).isEqualTo(1);
        assertThat(h.playersCounted("battle_lock_unknown")).isZero();
        assertThat(h.playersCounted("restored")).isZero();
    }

    @Test
    void kick踢下来的在战玩家_同样in_battle_零写入_归属已释放() throws Exception {
        h = new RollbackHarness(Map.of());
        player(P, SNAP, true);
        h.battleLocked.add(P);
        List<String> atRead = new CopyOnWriteArrayList<>();
        h.battleLockReader = ids -> {
            OwnerState o = owner(P);
            atRead.add("takeovers=" + h.takeovers.size() + " epoch=" + o.ownerEpoch() + " released=" + o.released());
            return h.battleLockAnswer(ids);
        };

        OpsJobRow job = h.await(h.submit(RollbackHarness.players(List.of("1001"), "5001", null, null, "kick", false), "k1"));

        // 顶号通路不看是否在战斗：让出请求照发、scene 写回释放、我们夺到——然后才查锁
        assertThat(h.takeovers).isNotEmpty();
        assertThat(h.takeovers.get(0)).containsExactly(P, 3L);
        assertThat(h.battleLockCalls).containsExactly(List.of(P));
        assertThat(atRead).hasSize(1);
        assertThat(atRead.get(0)).endsWith("epoch=4 released=false").doesNotStartWith("takeovers=0 ");
        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_REJECTED);
        assertThat(job.getResultCode()).isEqualTo("in_battle");
        assertThat(outcomes(job.getJobId())).containsExactly(Map.entry("1001", "in_battle"));
        assertNothingWritten(job.getJobId(), P);
        assertReleased(P);
        assertThat(h.guildCalls).isEmpty();
        assertThat(event(job.getJobId(), OpsJobEventType.OPS_JOB_EVENT_CLAIMED).at("/kick").asBoolean()).isTrue();
        assertThat(h.playersCounted("in_battle")).isEqualTo(1);
    }

    @Test
    void 不在战的照常回档_锁恰好读一次_摘要记下查过() throws Exception {
        h = new RollbackHarness(Map.of());
        player(P, SNAP, false);

        OpsJobRow job = h.await(h.submit(RollbackHarness.players(List.of("1001"), "5001", null, null, null, false), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_SUCCEEDED);
        assertThat(job.getResultCode()).isEqualTo("ok");
        assertThat(persisted(P).getCurrency().getBalances(0)).isEqualTo(1000);
        assertThat(h.jobs.players(job.getJobId(), 0, 10).get(0).getOutcome()).isEqualTo("RESTORED");
        assertThat(h.battleLockCalls).containsExactly(List.of(P));
        JsonNode summary = h.summary(job);
        assertThat(summary.at("/battleLock/checked").asInt()).isEqualTo(1);
        assertThat(summary.at("/battleLock/inBattleCount").asInt()).isZero();
        assertThat(summary.at("/battleLock/unknownCount").asInt()).isZero();
        assertThat(summary.at("/battleLock/inBattlePlayers").size()).isZero();
        assertThat(h.eventTypes(job.getJobId())).containsExactly("STARTED", "PLANNED", "CLAIMED", "CHECK", "WRITE", "RECHECK",
                "RESULT");
        assertThat(h.playersCounted("restored")).isEqualTo(1);
        assertThat(h.playersCounted("in_battle")).isZero();
        assertThat(h.playersCounted("battle_lock_unknown")).isZero();
    }

    /** 读不到锁的四种来路共用的断言：fail-closed、与「确实在战」分开记。 */
    private void assertFailClosed(OpsJobRow job, String errorPart) throws Exception {
        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_REJECTED);
        assertThat(job.getResultCode()).isEqualTo("battle_lock_unknown");
        OpsJobPlayerRow detail = h.jobs.players(job.getJobId(), 0, 10).get(0);
        assertThat(detail.getOutcome()).isEqualTo("battle_lock_unknown");
        assertThat(detail.getClaimedEpoch()).isEqualTo(4L);
        assertNothingWritten(job.getJobId(), P);
        assertReleased(P);
        assertThat(h.battleLockCalls).containsExactly(List.of(P));
        assertThat(h.guildCalls).isEmpty();
        assertThat(h.eventTypes(job.getJobId())).containsExactly("STARTED", "PLANNED", "CLAIMED", "RESULT");
        JsonNode summary = h.summary(job);
        assertThat(summary.at("/battleLock/checked").asInt()).isEqualTo(1);
        assertThat(summary.at("/battleLock/unknownCount").asInt()).isEqualTo(1);
        assertThat(summary.at("/battleLock/inBattleCount").asInt()).isZero();
        assertThat(summary.at("/battleLock/error").asText()).contains(errorPart);
        assertThat(h.playersCounted("battle_lock_unknown")).isEqualTo(1);
        assertThat(h.playersCounted("in_battle")).isZero();
        assertThat(h.playersCounted("restored")).isZero();
    }

    @Test
    void 读锁失败_future异常完成_fail_closed_battle_lock_unknown_零写入_释放() throws Exception {
        h = new RollbackHarness(Map.of());
        player(P, SNAP, false);
        h.battleLockReader = ids -> CompletableFuture.failedFuture(new IllegalStateException("Redis 连接断了"));

        OpsJobRow job = h.await(h.submit(RollbackHarness.players(List.of("1001"), "5001", null, null, null, false), "k1"));

        assertFailClosed(job, "Redis 连接断了");
    }

    @Test
    void 读锁失败_取不到Redis客户端同步抛异常_fail_closed_作业不崩() throws Exception {
        h = new RollbackHarness(Map.of());
        player(P, SNAP, false);
        h.battleLockReader = ids -> {
            throw new IllegalStateException("redissonClient 建不出来");
        };

        OpsJobRow job = h.await(h.submit(RollbackHarness.players(List.of("1001"), "5001", null, null, null, false), "k1"));

        // 不是作业体异常（那会是 FAILED snapshot_db_error）
        assertFailClosed(job, "redissonClient 建不出来");
    }

    @Test
    void 读锁超时_不应答_到点fail_closed_不无限等() throws Exception {
        h = new RollbackHarness(Map.of("xm.data.ops.battle-lock-wait", "300ms"));
        player(P, SNAP, false);
        CompletableFuture<Map<Long, Boolean>> never = new CompletableFuture<>();
        h.battleLockReader = ids -> never;

        OpsJobRow job = h.await(h.submit(RollbackHarness.players(List.of("1001"), "5001", null, null, null, false), "k1"));

        assertFailClosed(job, "超时");
        // 等的是这里覆盖的 300 ms，不是夹具缺省的 2 s（RollbackHarness）：错误文本带闸实际用的毫秒数，时长也到不了 2 s
        assertThat(h.summary(job).at("/battleLock/error").asText()).containsPattern("(?<!\\d)300 ms");
        assertThat(job.getFinishedMs() - job.getStartedMs()).as("只等 battle-lock-wait（300 ms），不是夹具缺省的 2 s，更不是无限等")
                .isBetween(250L, 1900L);
        assertThat(never).as("到点之后不再等这次读").isCancelled();
    }

    @Test
    void 读锁的应答里没有这名玩家_不当成不在战_fail_closed() throws Exception {
        h = new RollbackHarness(Map.of());
        player(P, SNAP, false);
        h.battleLockReader = ids -> CompletableFuture.completedFuture(Map.of(4242L, false));

        OpsJobRow job = h.await(h.submit(RollbackHarness.players(List.of("1001"), "5001", null, null, null, false), "k1"));

        assertFailClosed(job, "缺 1 名玩家");
    }

    @Test
    void 多人_在战的不写并当场释放_其余照常回档_PARTIAL_帮会只问要写的人() throws Exception {
        h = new RollbackHarness(Map.of("xm.data.rollback.guild.settle", "1500ms"));
        long t = System.currentTimeMillis() - 120_000;
        for (long p = 1001; p <= 1003; p++) {
            h.player(p, 1, CURRENT.toByteArray(), System.currentTimeMillis() - 5000, 3, false);
            h.snapshot(6000 + p, p, t, SnapshotCauses.LOGOUT, 1, 5, 2002, SNAPSHOT.toByteArray());
        }
        h.battleLocked.add(1002L);

        long jobId = h.submit(RollbackHarness.players(List.of("1001", "1002", "1003"), null, t + 1, null, null, false), "k1");
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!h.eventTypes(jobId).contains("CLAIMED") && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        // 沉降期间（作业还在跑）：在战的 1002 已经释放，不陪着等；要写的 1001 仍由我们持有
        assertThat(h.jobs.findJob(jobId).orElseThrow().getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_RUNNING);
        assertThat(owner(1002).released()).as("在战的当场释放").isTrue();
        assertThat(owner(1002).ownerEpoch()).isEqualTo(4L);
        assertThat(owner(1001).released()).as("要写的继续持有").isFalse();
        assertThat(h.tombstones).as("此刻只给被挡的人写过墓碑").singleElement()
                .satisfies(x -> assertThat(x).containsExactly(1002L, 4L));

        OpsJobRow job = h.await(jobId);

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_PARTIAL);
        assertThat(job.getResultCode()).isEqualTo("partial");
        assertThat(job.getPlayersAffected()).isEqualTo(2);
        assertThat(job.getPlayersFailed()).isEqualTo(1);
        assertThat(outcomes(jobId)).containsExactly(Map.entry("1001", "RESTORED"), Map.entry("1002", "in_battle"),
                Map.entry("1003", "RESTORED"));
        assertThat(persisted(1001).getCurrency().getBalances(0)).isEqualTo(1000);
        assertThat(persisted(1003).getCurrency().getBalances(0)).isEqualTo(1000);
        assertThat(persisted(1002)).isEqualTo(CURRENT);
        assertThat(h.count("SELECT COUNT(*) FROM player_snapshot WHERE cause = ? AND player_id = ?",
                SnapshotCauses.PRE_ROLLBACK, 1002L)).isZero();
        assertThat(h.count("SELECT COUNT(*) FROM player_snapshot WHERE cause = ?", SnapshotCauses.PRE_ROLLBACK)).isEqualTo(2);
        assertThat(h.count("SELECT COUNT(*) FROM transaction_log WHERE from_player = ? OR to_player = ?", 1002L, 1002L))
                .isZero();
        // 一次批量读问全三人；帮会检查与写后复查都只带要写的两人
        assertThat(h.battleLockCalls).containsExactly(List.of(1001L, 1002L, 1003L));
        assertThat(h.guildCalls).hasSize(2).allSatisfy(c -> assertThat(c.getPlayerIdsList()).containsExactly(1001L, 1003L));
        JsonNode summary = h.summary(job);
        assertThat(summary.at("/battleLock/checked").asInt()).isEqualTo(3);
        assertThat(summary.at("/battleLock/inBattleCount").asInt()).isEqualTo(1);
        assertThat(summary.at("/battleLock/inBattlePlayers/0").asText()).isEqualTo("1002");
        assertThat(summary.at("/restored").asInt()).isEqualTo(2);
        assertThat(event(jobId, OpsJobEventType.OPS_JOB_EVENT_CLAIMED).at("/claimed").asInt()).as("夺到的人数含被挡的").isEqualTo(3);
        for (long p = 1001; p <= 1003; p++) {
            assertThat(owner(p).released()).isTrue();
        }
        assertThat(h.ownership.heldCount()).isZero();
        assertThat(h.playersCounted("in_battle")).isEqualTo(1);
        assertThat(h.playersCounted("restored")).isEqualTo(2);
    }

    @Test
    void 只查夺到的人_在线reject的不查_全没写成时结果码取夺权阶段的结局() throws Exception {
        h = new RollbackHarness(Map.of());
        long t = System.currentTimeMillis() - 120_000;
        h.player(1001, 1, CURRENT.toByteArray(), System.currentTimeMillis() - 5000, 3, true);   // 在线、reject：夺不到
        h.player(1002, 1, CURRENT.toByteArray(), System.currentTimeMillis() - 5000, 3, false);  // 离线、在战
        h.player(1003, 1, CURRENT.toByteArray(), System.currentTimeMillis() - 5000, 3, false);  // 没有快照
        h.snapshot(6001, 1001, t, SnapshotCauses.LOGOUT, 1, 5, 2002, SNAPSHOT.toByteArray());
        h.snapshot(6002, 1002, t, SnapshotCauses.LOGOUT, 1, 5, 2002, SNAPSHOT.toByteArray());
        h.battleLocked.addAll(List.of(1001L, 1002L, 1003L));

        OpsJobRow job = h.await(h.submit(RollbackHarness.players(List.of("1001", "1002", "1003"), null, t + 1, null, null,
                false), "k1"));

        assertThat(h.battleLockCalls).as("没夺到的、没有快照的都不查").containsExactly(List.of(1002L));
        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_REJECTED);
        assertThat(job.getResultCode()).isEqualTo("player_online");
        assertThat(outcomes(job.getJobId())).containsExactly(Map.entry("1001", "player_online"),
                Map.entry("1002", "in_battle"), Map.entry("1003", "no_snapshot"));
        assertNothingWritten(job.getJobId(), 1001, 1002, 1003);
        assertThat(owner(1001).ownerEpoch()).as("在线的没被夺").isEqualTo(3L);
        assertThat(owner(1001).released()).isFalse();
        assertThat(owner(1002).ownerEpoch()).isEqualTo(4L);
        assertThat(owner(1002).released()).isTrue();
        assertThat(h.guildCalls).isEmpty();
        JsonNode summary = h.summary(job);
        assertThat(summary.at("/unclaimed/player_online").asInt()).isEqualTo(1);
        assertThat(summary.at("/battleLock/checked").asInt()).isEqualTo(1);
        assertThat(summary.at("/battleLock/inBattleCount").asInt()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 无条件：不走 acceptDivergence、不看回哪些段

    /** 两个放行开关（acceptDivergence、acceptRecallReversal）的四种组合。 */
    private static final boolean[][] ACCEPTS = {{false, false}, {true, false}, {false, true}, {true, true}};

    /** 回哪些段：整份（FULL）、只回一个非资产段、全部非资产段、资产组。 */
    private static final List<List<String>> SECTION_CHOICES = Arrays.asList(null, List.of("level"),
            List.of("position", "facing", "attribute", "vitals"), List.of("assets"));

    /**
     * 帮会那边每名被问到的玩家都有一条快照之后已应用的指令：走到分歧裁决时缺省 {@code rollback_guild_divergence}，
     * 要 {@code acceptDivergence} 才放行。
     */
    private void seedGuildDivergence() {
        long after = System.currentTimeMillis() - 1000;
        h.guildAnswers.add(r -> RollbackHarness.ok(r.getPlayerIdsList().stream()
                .map(p -> RollbackHarness.op(70_000 + p, p, after)).toArray(GuildAssetOpBrief[]::new)));
    }

    /** 流水里这名玩家快照之后有一笔精确回收扣减：走到回收逆转检查时缺省 {@code recall_reversal}，要 {@code acceptRecallReversal} 才放行。 */
    private void seedRecall(long playerId) {
        long after = System.currentTimeMillis() - 1000;
        h.db.jdbc().update("INSERT INTO transaction_log (tx_id, time_ms, reason, kind, from_player, currency_type, "
                        + "currency_delta, extra, zone_id, ingested_at) VALUES (?, ?, ?, 1, ?, 0, -100, '', 1, ?)",
                90_000 + playerId, after, TransactionReason.TX_CLAWBACK_VALUE, playerId, after);
    }

    /** 单人按号回档的请求体，两个放行开关与 sections 都自己给（{@link RollbackHarness#players} 把 acceptRecallReversal 写死 false）。 */
    private static RollbackRequest.Body single(long playerId, long snapshotId, List<String> sections, boolean acceptDivergence,
                                               boolean acceptRecallReversal) {
        return new RollbackRequest.Body("players", List.of(Long.toString(playerId)), null, null, Long.toString(snapshotId),
                null, sections, null, acceptDivergence, acceptRecallReversal, "客诉补偿", false);
    }

    @Test
    void 战斗锁闸无条件拒绝_两个放行开关与回哪些段的任何组合都是in_battle零写入_不进分歧裁决() throws Exception {
        h = new RollbackHarness(Map.of());
        seedGuildDivergence();
        long p = 2000;
        int jobs = 0;
        for (List<String> sections : SECTION_CHOICES) {
            for (boolean[] accept : ACCEPTS) {
                p++;
                jobs++;
                String what = "sections=" + sections + " acceptDivergence=" + accept[0] + " acceptRecallReversal=" + accept[1];
                player(p, 5000 + p, false);
                seedRecall(p);
                h.battleLocked.add(p);

                OpsJobRow job = h.await(h.submit(single(p, 5000 + p, sections, accept[0], accept[1]), "k" + p));

                // 作业受理的确实是这组开关与段（没有在受理时被改写成缺省）
                JsonNode request = h.json.readTree(job.getRequestJson());
                assertThat(request.at("/acceptDivergence").asBoolean()).as(what).isEqualTo(accept[0]);
                assertThat(request.at("/acceptRecallReversal").asBoolean()).as(what).isEqualTo(accept[1]);
                assertThat(h.json.convertValue(request.at("/sections"), Object.class)).as(what)
                        .isEqualTo(sections == null ? "FULL" : sections);
                assertThat(job.getStatus()).as(what).isEqualTo(OpsJobStatus.OPS_JOB_REJECTED);
                assertThat(job.getResultCode()).as(what).isEqualTo("in_battle");
                assertThat(outcomes(job.getJobId())).as(what).containsExactly(Map.entry(Long.toString(p), "in_battle"));
                // 没有进分歧裁决：帮会没被问过、没有 CHECK / ACCEPTED / WRITE，作业行上两个「已放行」标记都没立、分歧行数没算过
                assertThat(h.eventTypes(job.getJobId())).as(what).containsExactly("STARTED", "PLANNED", "CLAIMED", "RESULT");
                assertThat(h.guildCalls).as(what).isEmpty();
                assertThat(job.getAcceptedDivergence()).as(what).isFalse();
                assertThat(job.getAcceptedRecallReversal()).as(what).isFalse();
                assertThat(job.getDivergenceRows()).as(what).isZero();
                // 每个作业恰好读一次锁，问的就是夺到的这个人——只回非资产段也照查
                assertThat(h.battleLockCalls).as(what).hasSize(jobs);
                assertThat(h.battleLockCalls.get(jobs - 1)).as(what).containsExactly(p);
                assertThat(h.summary(job).at("/battleLock/inBattlePlayers/0").asText()).as(what).isEqualTo(Long.toString(p));
                assertNothingWrittenBesides(jobs, job.getJobId(), p);
                assertReleased(p);
            }
        }
        assertThat(jobs).isEqualTo(16);
        assertThat(h.playersCounted("in_battle")).isEqualTo(16);
        assertThat(h.playersCounted("restored")).isZero();
    }

    @Test
    void 对照_没有战斗锁时同样的请求由两个放行开关裁决_都开才写入_所以有锁时的拒绝只能来自锁() throws Exception {
        h = new RollbackHarness(Map.of());
        seedGuildDivergence();
        long p = 3000;
        // 整份回档 × 四种开关：帮会分歧先裁（缺省拒绝），放行之后才轮到回收逆转，两个都放行才写
        String[] expected = {"rollback_guild_divergence", "recall_reversal", "rollback_guild_divergence", "ok"};
        for (int i = 0; i < ACCEPTS.length; i++) {
            p++;
            String what = "acceptDivergence=" + ACCEPTS[i][0] + " acceptRecallReversal=" + ACCEPTS[i][1];
            player(p, 5000 + p, false);
            seedRecall(p);

            OpsJobRow job = h.await(h.submit(single(p, 5000 + p, null, ACCEPTS[i][0], ACCEPTS[i][1]), "k" + p));

            assertThat(job.getResultCode()).as(what).isEqualTo(expected[i]);
            assertThat(job.getStatus()).as(what).isEqualTo("ok".equals(expected[i]) ? OpsJobStatus.OPS_JOB_SUCCEEDED
                    : OpsJobStatus.OPS_JOB_REJECTED);
            assertThat(h.eventTypes(job.getJobId())).as(what).contains("CHECK");
            // 没有锁也照样读了一次（开关开着也读）
            assertThat(h.battleLockCalls).as(what).hasSize(i + 1);
            assertThat(h.battleLockCalls.get(i)).as(what).containsExactly(p);
            if (!"ok".equals(expected[i])) {
                assertThat(job.getDivergenceRows()).as(what).isEqualTo(1);
                assertThat(h.eventTypes(job.getJobId())).as(what).doesNotContain("ACCEPTED", "WRITE");
                assertThat(persisted(p)).as(what).isEqualTo(CURRENT);
            }
        }
        // 两个开关都开 × 每一种段：与上一例逐一对应的请求，只差没有锁——走到裁决、记下放行、写入
        for (List<String> sections : SECTION_CHOICES) {
            p++;
            String what = "sections=" + sections;
            player(p, 5000 + p, false);
            seedRecall(p);

            OpsJobRow job = h.await(h.submit(single(p, 5000 + p, sections, true, true), "k" + p));

            assertThat(job.getStatus()).as(what).isEqualTo(OpsJobStatus.OPS_JOB_SUCCEEDED);
            assertThat(job.getResultCode()).as(what).isEqualTo("ok");
            assertThat(job.getAcceptedDivergence()).as(what).isTrue();
            assertThat(job.getAcceptedRecallReversal()).as(what).isTrue();
            assertThat(h.eventTypes(job.getJobId())).as(what).containsSubsequence("CLAIMED", "CHECK", "ACCEPTED", "WRITE");
            assertThat(outcomes(job.getJobId())).as(what).containsExactly(Map.entry(Long.toString(p), "RESTORED"));
            assertThat(h.summary(job).at("/battleLock/checked").asInt()).as(what).isEqualTo(1);
            assertThat(h.summary(job).at("/battleLock/inBattleCount").asInt()).as(what).isZero();
        }
        // 写入确实落了地：只回 level 的那一人等级 9 → 5、存档不动；整份回档的那一人金币回到快照的 1000
        assertThat(h.db.players.find(3006).getLevel()).isEqualTo(5);
        assertThat(persisted(3006)).isEqualTo(CURRENT);
        assertThat(persisted(3005).getCurrency().getBalances(0)).isEqualTo(1000);
        assertThat(h.playersCounted("restored")).isEqualTo(5);
        assertThat(h.playersCounted("in_battle")).isZero();
    }

    // ------------------------------------------------------------------ 整区

    @Test
    void 整区有人在战_zone_not_quiescent_全部释放零写入_战斗中的人数单列() throws Exception {
        h = new RollbackHarness(Map.of());
        h.zone(1, ZoneManualStatus.MAINTENANCE.code());
        long now = System.currentTimeMillis();
        long target = now - 60_000;
        h.player(11, 1, CURRENT.toByteArray(), now - 5000, 3, false);   // 离线、不在战
        h.player(12, 1, CURRENT.toByteArray(), now - 5000, 3, false);   // 离线、在战（reject 也夺得到的那种）
        h.player(13, 1, CURRENT.toByteArray(), now - 5000, 3, true);    // 在线、在战：整区一律 kick，踢下来之后锁还在
        h.player(14, 1, CURRENT.toByteArray(), now - 5000, 3, false);   // 没有快照：不夺权、不查锁
        for (long p = 11; p <= 13; p++) {
            h.snapshot(7000 + p, p, target - 10, SnapshotCauses.LOGOUT, 1, 5, 2002, SNAPSHOT.toByteArray());
        }
        h.battleLocked.addAll(List.of(12L, 13L, 14L));

        OpsJobRow job = h.await(h.submit(zones(target), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_REJECTED);
        assertThat(job.getResultCode()).isEqualTo("zone_not_quiescent");
        assertThat(job.getPlayersAffected()).isZero();
        assertThat(h.takeovers.stream().map(x -> x[0]).distinct()).containsExactly(13L);
        assertThat(h.battleLockCalls).containsExactly(List.of(11L, 12L, 13L));
        // 摘要：战斗中的人数单列，不并入「夺不到」
        JsonNode summary = h.summary(job);
        assertThat(summary.at("/battleLock/checked").asInt()).isEqualTo(3);
        assertThat(summary.at("/battleLock/inBattleCount").asInt()).isEqualTo(2);
        assertThat(summary.at("/battleLock/inBattlePlayers/0").asText()).isEqualTo("12");
        assertThat(summary.at("/battleLock/inBattlePlayers/1").asText()).isEqualTo("13");
        assertThat(summary.at("/battleLock/inBattlePlayers").size()).isEqualTo(2);
        assertThat(summary.at("/battleLock/unknownCount").asInt()).isZero();
        assertThat(summary.at("/unclaimedCount").asInt()).isZero();
        assertThat(summary.at("/unclaimedPlayers").size()).isZero();
        assertThat(summary.at("/claimed").asInt()).isEqualTo(3);
        assertThat(summary.at("/planned").asInt()).isEqualTo(3);
        assertThat(summary.at("/outcomes/in_battle").asInt()).isEqualTo(2);
        // 明细：在战的两人 in_battle；不在战的 11 因整区全有或全无记 rejected
        assertThat(outcomes(job.getJobId())).containsExactly(Map.entry("11", "rejected"), Map.entry("12", "in_battle"),
                Map.entry("13", "in_battle"), Map.entry("14", "no_snapshot"));
        assertNothingWritten(job.getJobId(), 11, 12, 13, 14);
        assertReleased(11, 12, 13);
        assertThat(owner(14).ownerEpoch()).isEqualTo(3L);
        assertThat(h.guildCalls).isEmpty();
        assertThat(h.eventTypes(job.getJobId())).doesNotContain("CHECK", "ACCEPTED", "WRITE");
        assertThat(h.playersCounted("in_battle")).isEqualTo(2);
        assertThat(h.playersCounted("rejected")).isEqualTo(1);
    }

    @Test
    void 整区读锁失败_fail_closed_zone_not_quiescent_全部释放零写入() throws Exception {
        h = new RollbackHarness(Map.of());
        h.zone(1, ZoneManualStatus.CLOSED.code());
        long now = System.currentTimeMillis();
        long target = now - 60_000;
        for (long p = 11; p <= 12; p++) {
            h.player(p, 1, CURRENT.toByteArray(), now - 5000, 3, false);
            h.snapshot(7000 + p, p, target - 10, SnapshotCauses.LOGOUT, 1, 5, 2002, SNAPSHOT.toByteArray());
        }
        h.battleLockReader = ids -> CompletableFuture.failedFuture(new IllegalStateException("READONLY 主库切换中"));

        OpsJobRow job = h.await(h.submit(zones(target), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_REJECTED);
        assertThat(job.getResultCode()).isEqualTo("zone_not_quiescent");
        JsonNode summary = h.summary(job);
        assertThat(summary.at("/battleLock/unknownCount").asInt()).isEqualTo(2);
        assertThat(summary.at("/battleLock/inBattleCount").asInt()).isZero();
        assertThat(summary.at("/battleLock/error").asText()).contains("READONLY 主库切换中");
        assertThat(outcomes(job.getJobId())).containsExactly(Map.entry("11", "battle_lock_unknown"),
                Map.entry("12", "battle_lock_unknown"));
        assertNothingWritten(job.getJobId(), 11, 12);
        assertReleased(11, 12);
        assertThat(h.guildCalls).isEmpty();
        assertThat(h.playersCounted("battle_lock_unknown")).isEqualTo(2);
        assertThat(h.playersCounted("in_battle")).isZero();
    }

    @Test
    void 整区既有夺不到的又有在战的_一次列全_两类分开计() throws Exception {
        h = new RollbackHarness(Map.of("xm.data.ops.claim-wait", "300ms"));
        h.sceneReleasesOnTakeover = false;
        h.zone(1, ZoneManualStatus.MAINTENANCE.code());
        long now = System.currentTimeMillis();
        long target = now - 60_000;
        h.player(11, 1, CURRENT.toByteArray(), now - 5000, 3, false);  // 离线、在战
        h.player(12, 1, CURRENT.toByteArray(), now - 5000, 3, true);   // 在线、持有者不放：夺不到
        h.player(13, 1, CURRENT.toByteArray(), now - 5000, 3, false);  // 离线、不在战
        for (long p = 11; p <= 13; p++) {
            h.snapshot(7000 + p, p, target - 10, SnapshotCauses.LOGOUT, 1, 5, 2002, SNAPSHOT.toByteArray());
        }
        h.battleLocked.addAll(List.of(11L, 12L));

        OpsJobRow job = h.await(h.submit(zones(target), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_REJECTED);
        assertThat(job.getResultCode()).isEqualTo("zone_not_quiescent");
        assertThat(h.battleLockCalls).as("只查夺到的 11、13").containsExactly(List.of(11L, 13L));
        JsonNode summary = h.summary(job);
        assertThat(summary.at("/unclaimedCount").asInt()).isEqualTo(1);
        assertThat(summary.at("/unclaimedPlayers/0").asText()).isEqualTo("12");
        assertThat(summary.at("/unclaimedByOutcome/player_busy").asInt()).isEqualTo(1);
        assertThat(summary.at("/battleLock/inBattleCount").asInt()).isEqualTo(1);
        assertThat(summary.at("/battleLock/inBattlePlayers/0").asText()).isEqualTo("11");
        assertThat(outcomes(job.getJobId())).containsExactly(Map.entry("11", "in_battle"), Map.entry("12", "player_busy"),
                Map.entry("13", "rejected"));
        assertNothingWritten(job.getJobId(), 11, 12, 13);
        assertReleased(11, 13);
        assertThat(owner(12).ownerEpoch()).isEqualTo(3L);
    }

    @Test
    void 整区_两个放行开关都开且只回非资产段_有人在战照样zone_not_quiescent零写入_锁没了同一请求才被放行写入() throws Exception {
        h = new RollbackHarness(Map.of());
        h.zone(1, ZoneManualStatus.MAINTENANCE.code());
        long now = System.currentTimeMillis();
        long target = now - 60_000;
        for (long p = 11; p <= 12; p++) {
            h.player(p, 1, CURRENT.toByteArray(), now - 5000, 3, false);
            h.snapshot(7000 + p, p, target - 10, SnapshotCauses.LOGOUT, 1, 5, 2002, SNAPSHOT.toByteArray());
            seedRecall(p);
        }
        seedGuildDivergence();
        h.battleLocked.add(12L);
        RollbackRequest.Body body = new RollbackRequest.Body("zones", null, List.of(1L), false, null, target, List.of("level"),
                null, true, true, "合服前回滚", false);

        OpsJobRow job = h.await(h.submit(body, "k1"));

        JsonNode request = h.json.readTree(job.getRequestJson());
        assertThat(request.at("/acceptDivergence").asBoolean()).isTrue();
        assertThat(request.at("/acceptRecallReversal").asBoolean()).isTrue();
        assertThat(request.at("/sections/0").asText()).isEqualTo("level");
        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_REJECTED);
        assertThat(job.getResultCode()).isEqualTo("zone_not_quiescent");
        assertThat(outcomes(job.getJobId())).containsExactly(Map.entry("11", "rejected"), Map.entry("12", "in_battle"));
        assertThat(h.summary(job).at("/battleLock/inBattleCount").asInt()).isEqualTo(1);
        assertThat(h.summary(job).at("/battleLock/inBattlePlayers/0").asText()).isEqualTo("12");
        // 没有进分歧裁决
        assertThat(h.eventTypes(job.getJobId())).doesNotContain("CHECK", "ACCEPTED", "WRITE");
        assertThat(h.guildCalls).isEmpty();
        assertThat(job.getAcceptedDivergence()).isFalse();
        assertThat(job.getAcceptedRecallReversal()).isFalse();
        assertThat(h.battleLockCalls).containsExactly(List.of(11L, 12L));
        assertNothingWrittenBesides(2, job.getJobId(), 11, 12);
        assertReleased(11, 12);

        // 对照：同一个请求体、同样的帮会分歧与回收扣减，只是锁没了 → 走到裁决、两个开关放行、两人都写
        h.battleLocked.clear();
        OpsJobRow written = h.await(h.submit(body, "k2"));

        assertThat(written.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_SUCCEEDED);
        assertThat(written.getResultCode()).isEqualTo("ok");
        assertThat(written.getAcceptedDivergence()).isTrue();
        assertThat(written.getAcceptedRecallReversal()).isTrue();
        assertThat(written.getPlayersAffected()).isEqualTo(2);
        assertThat(h.eventTypes(written.getJobId())).containsSubsequence("CHECK", "ACCEPTED", "WRITE");
        assertThat(outcomes(written.getJobId())).containsExactly(Map.entry("11", "RESTORED"), Map.entry("12", "RESTORED"));
        for (long p = 11; p <= 12; p++) {
            assertThat(h.db.players.find(p).getLevel()).as("玩家 %d 的等级回到快照", p).isEqualTo(5);
            assertThat(persisted(p)).as("只回 level：存档不动").isEqualTo(CURRENT);
        }
    }

    // ------------------------------------------------------------------ dry-run

    @Test
    void dry_run标出在战玩家_不夺权_不建作业_恢复预演照给() throws Exception {
        h = new RollbackHarness(Map.of("xm.data.ops.enabled", "false"));
        long t = System.currentTimeMillis() - 120_000;
        for (long p = 1001; p <= 1003; p++) {
            h.player(p, 1, CURRENT.toByteArray(), System.currentTimeMillis() - 5000, 3, false);
        }
        h.snapshot(6001, 1001, t, SnapshotCauses.LOGOUT, 1, 5, 2002, SNAPSHOT.toByteArray());
        h.snapshot(6002, 1002, t, SnapshotCauses.LOGOUT, 1, 5, 2002, SNAPSHOT.toByteArray());
        h.battleLocked.addAll(List.of(1001L, 1003L));

        JsonNode view = h.json.valueToTree(h.rollbacks.handle(dryRun(List.of("1001", "1002", "1003"), null, t + 1), "ops",
                null));

        assertThat(view.at("/dryRun").asBoolean()).isTrue();
        // 只查有快照可回的两人（1003 没有快照，执行时也不会夺权）
        assertThat(h.battleLockCalls).containsExactly(List.of(1001L, 1002L));
        assertThat(view.at("/battleLock/checked").asInt()).isEqualTo(2);
        assertThat(view.at("/battleLock/inBattleCount").asInt()).isEqualTo(1);
        assertThat(view.at("/battleLock/inBattlePlayers/0").asText()).isEqualTo("1001");
        assertThat(view.at("/battleLock/unknownCount").asInt()).isZero();
        assertThat(view.at("/players/0/playerId").asText()).isEqualTo("1001");
        assertThat(view.at("/players/0/inBattle").isBoolean()).isTrue();
        assertThat(view.at("/players/0/inBattle").asBoolean()).isTrue();
        assertThat(view.at("/players/0/restore/mode").asText()).as("在战的照样给预演").isEqualTo("FULL");
        assertThat(view.at("/players/1/inBattle").isBoolean()).isTrue();
        assertThat(view.at("/players/1/inBattle").asBoolean()).isFalse();
        assertThat(view.at("/players/2/outcome").asText()).isEqualTo("no_snapshot");
        assertThat(view.at("/players/2").has("inBattle")).isFalse();
        assertThat(view.at("/caveat").asText()).contains("战斗锁");
        // 只读：不夺权、不建作业、不动存档
        assertThat(h.db.count("ops_job")).isZero();
        for (long p = 1001; p <= 1003; p++) {
            assertThat(owner(p).ownerEpoch()).isEqualTo(3L);
            assertThat(persisted(p)).isEqualTo(CURRENT);
        }
        assertThat(h.tombstones).isEmpty();
        assertThat(h.playersCounted("in_battle")).as("dry-run 不计逐玩家结局").isZero();
    }

    @Test
    void dry_run读锁失败_不让预演失败_逐人标成未知_顶层给出原因() throws Exception {
        h = new RollbackHarness(Map.of());
        player(P, SNAP, false);
        h.battleLockReader = ids -> CompletableFuture.failedFuture(new IllegalStateException("Redis 不可达"));

        JsonNode view = h.json.valueToTree(h.rollbacks.handle(dryRun(List.of("1001"), "5001", null), "ops", null));

        assertThat(view.at("/battleLock/checked").asInt()).isEqualTo(1);
        assertThat(view.at("/battleLock/unknownCount").asInt()).isEqualTo(1);
        assertThat(view.at("/battleLock/inBattleCount").asInt()).isZero();
        assertThat(view.at("/battleLock/error").asText()).contains("Redis 不可达");
        assertThat(view.at("/players/0").has("inBattle")).isTrue();
        assertThat(view.at("/players/0/inBattle").isNull()).as("读不到既不是 true 也不是 false").isTrue();
        assertThat(view.at("/players/0/snapshot/snapshotId").asText()).isEqualTo("5001");
        assertThat(view.at("/players/0/restore/mode").asText()).isEqualTo("FULL");
    }

    @Test
    void 整区dry_run_逐人标出在战_没有可回的目标就不读锁() throws Exception {
        h = new RollbackHarness(Map.of());
        h.zone(1, ZoneManualStatus.OPEN.code());
        long now = System.currentTimeMillis();
        long target = now - 60_000;
        h.player(11, 1, CURRENT.toByteArray(), now - 5000, 3, false);
        h.player(12, 1, CURRENT.toByteArray(), now - 5000, 3, true);
        h.player(13, 1, CURRENT.toByteArray(), now - 5000, 3, false);
        h.snapshot(7011, 11, target - 10, SnapshotCauses.LOGOUT, 1, 5, 2002, SNAPSHOT.toByteArray());
        h.snapshot(7012, 12, target - 10, SnapshotCauses.LOGOUT, 1, 5, 2002, SNAPSHOT.toByteArray());
        h.battleLocked.add(12L);
        RollbackRequest.Body dry = new RollbackRequest.Body("zones", null, List.of(1L), false, null, target, null, null, false,
                false, "预演", true);

        JsonNode view = h.json.valueToTree(h.rollbacks.handle(dry, "ops", null));

        assertThat(h.battleLockCalls).containsExactly(List.of(11L, 12L));
        assertThat(view.at("/battleLock/inBattleCount").asInt()).isEqualTo(1);
        assertThat(view.at("/battleLock/inBattlePlayers/0").asText()).isEqualTo("12");
        assertThat(view.at("/players/0/inBattle").asBoolean()).isFalse();
        assertThat(view.at("/players/1/inBattle").asBoolean()).isTrue();
        assertThat(view.at("/players/2").has("inBattle")).isFalse();
        assertThat(owner(12).ownerEpoch()).as("dry-run 不踢人").isEqualTo(3L);
        assertThat(h.takeovers).isEmpty();

        // 目标里没有一个有快照可回：不读锁
        h.close();
        h = new RollbackHarness(Map.of());
        h.zone(1, ZoneManualStatus.OPEN.code());
        h.player(11, 1, CURRENT.toByteArray(), now - 5000, 3, false);
        JsonNode empty = h.json.valueToTree(h.rollbacks.handle(dry, "ops", null));
        assertThat(h.battleLockCalls).isEmpty();
        assertThat(empty.at("/battleLock/checked").asInt()).isZero();
    }

    @Test
    void dry_run_两个放行开关都开且只回非资产段_照样读锁并标出在战() throws Exception {
        h = new RollbackHarness(Map.of());
        player(P, SNAP, false);
        h.battleLocked.add(P);
        RollbackRequest.Body dry = new RollbackRequest.Body("players", List.of("1001"), null, null, "5001", null,
                List.of("level"), null, true, true, "预演", true);

        JsonNode view = h.json.valueToTree(h.rollbacks.handle(dry, "ops", null));

        assertThat(view.at("/request/acceptDivergence").asBoolean()).isTrue();
        assertThat(view.at("/request/acceptRecallReversal").asBoolean()).isTrue();
        assertThat(view.at("/players/0/restore/mode").asText()).isEqualTo("SECTIONS");
        assertThat(view.at("/players/0/restore/sections/0").asText()).isEqualTo("level");
        assertThat(h.battleLockCalls).containsExactly(List.of(P));
        assertThat(view.at("/battleLock/checked").asInt()).isEqualTo(1);
        assertThat(view.at("/battleLock/inBattleCount").asInt()).isEqualTo(1);
        assertThat(view.at("/battleLock/inBattlePlayers/0").asText()).isEqualTo("1001");
        assertThat(view.at("/players/0/inBattle").isBoolean()).isTrue();
        assertThat(view.at("/players/0/inBattle").asBoolean()).isTrue();
    }

    // ------------------------------------------------------------------ 指标

    @Test
    void 逐玩家结局指标_固定集合装配时全部预建为0_含两个新值() throws Exception {
        h = new RollbackHarness(Map.of());

        assertThat(RollbackJob.PLAYER_OUTCOMES).doesNotHaveDuplicates().contains("in_battle", "battle_lock_unknown",
                "restored", "player_online", "player_busy", "rejected", "cancelled");
        assertThat(RollbackJob.PLAYER_OUTCOMES).as("指标里 RESTORED 记作小写").doesNotContain("RESTORED");
        // 还没有任何作业：每个取值都已经有一条值为 0 的序列，且没有集合之外的序列
        for (String outcome : RollbackJob.PLAYER_OUTCOMES) {
            assertThat(h.playersCounted(outcome)).as(outcome).isZero();
        }
        assertThat(h.meters.find("xm.data.ops.players").counters()).extracting(c -> c.getId().getTag("outcome"))
                .containsExactlyInAnyOrderElementsOf(new HashSet<>(RollbackJob.PLAYER_OUTCOMES));
    }
}
