package com.game.data.rollback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.api.proto.GuildAssetOpBrief;
import com.game.audit.proto.TransactionReason;
import com.game.data.DataProperties;
import com.game.data.metrics.DataMetrics;
import com.game.data.ops.JobContext;
import com.game.data.ops.OpsJobRunner.JobBody;
import com.game.data.ops.OpsJobRunner.JobResult;
import com.game.data.ops.OpsJobStore;
import com.game.data.ops.fence.AdminOwnership;
import com.game.data.ops.fence.AdminOwnership.Claim;
import com.game.data.ops.pb.OpsJobEventType;
import com.game.data.ops.pb.OpsJobPlayerRow;
import com.game.data.ops.pb.OpsJobStatus;
import com.game.data.query.TransactionLogQueryService;
import com.game.data.rollback.RollbackPlanner.Target;
import com.game.data.snapshot.LedgerDiff;
import com.game.data.store.PersistedPlayer;
import com.game.data.store.PersistedPlayerMapper;
import com.game.data.store.PlayerSnapshotEntry;
import com.game.data.store.PlayerSnapshotMapper;
import com.game.data.store.TransactionLogEntry;
import com.game.data.store.TransactionLogQuery;
import com.game.player.store.state.PlayerState;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 回档作业（data-ops-spec §4.7 单人 / 多人、§4.9 整区 / 多区）。在 {@code data-ops} 线程上执行：
 * <pre>
 * 1 计划（只读）：逐人选快照并钉进 ops_job_player（PLANNED）；没有快照的分类；超上限 → REJECTED plan_too_large
 * 2 夺权：Claimed → 持有；Held + reject → player_online；Held + kick → 让出、等待；超时 → player_busy；NotFound → player_not_found
 *   （单人 / 多人：夺不到的只记结果，其余继续；整区：任何一人夺不到 → 全部释放、零写入，REJECTED zone_not_quiescent）
 * 3 续约（data-ops-fence 每 10 s，AdminOwnership.renew）
 * 4 账本差集（立即）→ 沉降 → 帮会检查 → 回收逆转检查 → 裁决；放行时 ACCEPTED 事件（写不进零写入）+ 逐行 ERROR 日志
 * 5 逐人写事务（RollbackWriter），按 player_id 升序
 * 6 至少一人写成功时：等 recheck-delay → 写后复查（持有归属期间）；发现新 op_id → DIVERGED_AFTER_WRITE（紧急告警，不自动撤销）
 * 7 RESULT（OpsJobRunner）  8 逐人：位置墓碑 → 释放（afterResult）
 * </pre>
 * 不变量：STARTED 先于一切（受理时已提交，I3）；三道检查没有得出「通过」之前不写任何玩家，写后复查在释放之前（I4）；释放前已尝试写墓碑（I5）。
 * 取消只在第一笔写之前有效（各阶段边界检查）。
 */
public final class RollbackJob implements JobBody {

    private static final Logger log = LoggerFactory.getLogger(RollbackJob.class);
    private static final Logger audit = LoggerFactory.getLogger("xm.audit.ops");

    static final String KIND = "rollback";
    /** 回收逆转查的流水原因（批量回收 19、精确回收 17；7.2c 起才有写入方，7.2b 先接好检查）。 */
    static final List<Integer> RECALL_REASONS = List.of(TransactionReason.TX_CLAWBACK_VALUE,
            TransactionReason.TX_BATCH_RECALL_VALUE);
    /** 库级故障：连续这么多人写失败就停（剩下的记 not_executed）。 */
    static final int MAX_CONSECUTIVE_FAILURES = 3;
    /** 夺权循环里查取消标志的间隔（取消标志在库里，逐人查会多一倍读）。 */
    static final Duration CANCEL_POLL = Duration.ofSeconds(1);

    // 结果码（data-ops-spec §7.7）
    static final String OK = "ok";
    static final String PARTIAL = "partial";
    static final String CANCELLED = "cancelled";
    static final String JOB_TIMEOUT = "job_timeout";
    static final String PLAN_TOO_LARGE = "plan_too_large";
    static final String ZONE_EMPTY = "zone_empty";
    static final String ZONE_NOT_QUIESCENT = "zone_not_quiescent";
    static final String GUILD_DIVERGENCE = "rollback_guild_divergence";
    static final String LEDGER_DIVERGENCE = "ledger_divergence";
    static final String RECALL_REVERSAL = "recall_reversal";
    static final String GUILD_CHECK_FAILED = "rollback_guild_check_failed";
    static final String DIVERGED_AFTER_WRITE = "rollback_guild_diverged_after_write";
    static final String POST_WRITE_UNVERIFIED = "post_write_unverified";
    static final String DB_ERROR = "snapshot_db_error";

    // 逐玩家结局（ops_job_player.outcome）
    static final String PLAYER_ONLINE = "player_online";
    static final String PLAYER_BUSY = "player_busy";
    static final String FAILED = "failed";
    static final String NOT_EXECUTED = "not_executed";
    static final String REJECTED = "rejected";

    /** 依赖（装配一次，多个作业共用）。 */
    public record Deps(RollbackPlanner planner, RollbackWriter writer, AdminOwnership ownership, GuildDivergenceGate guild,
                       PersistedPlayerMapper players, PlayerSnapshotMapper snapshots, TransactionLogQueryService txlog,
                       OpsJobStore jobs, DataProperties props, DataMetrics metrics, ObjectMapper json) {
    }

    private final Deps d;
    private final RollbackRequest req;

    /** 已夺到的玩家 → epoch（升序）。 */
    private final TreeMap<Long, Long> claimed = new TreeMap<>(Long::compareUnsigned);
    /** 计划里的目标（按玩家号）。 */
    private final Map<Long, Target> byPlayer = new LinkedHashMap<>();
    /** 已经定了结局的玩家（明细不再是 PLANNED）。 */
    private final Map<Long, String> outcomes = new LinkedHashMap<>();
    private int restored;
    private int writeFailures;
    /** 写阶段因作业时限停下（剩下的记 not_executed）。 */
    private boolean writeTimedOut;
    /** 夺权循环里下一次读库查取消标志的时刻（节流：每 {@link #CANCEL_POLL} 至多一次）。 */
    private long nextCancelPollNanos = System.nanoTime();

    public RollbackJob(Deps deps, RollbackRequest req) {
        this.d = deps;
        this.req = req;
    }

    @Override
    public JobResult run(JobContext ctx) {
        DataProperties.Guild guild = d.props().rollback().guild();
        // ---------------------------------------------------------------- 1 计划
        List<Target> targets = d.planner().plan(req);
        Map<String, Object> planSummary = RollbackPlanner.summary(targets);
        if (targets.size() > d.props().ops().maxPlayersPerJob()) {
            return result(OpsJobStatus.OPS_JOB_REJECTED, PLAN_TOO_LARGE, targets.size(), Map.of("plan", planSummary));
        }
        if (req.scope() == RollbackRequest.Scope.ZONES && targets.isEmpty()) {
            return result(OpsJobStatus.OPS_JOB_SUCCEEDED, ZONE_EMPTY, 0, Map.of("plan", planSummary));
        }
        for (Target t : targets) {
            byPlayer.put(t.playerId(), t);
            OpsJobPlayerRow.Builder row = OpsJobPlayerRow.newBuilder().setJobId(ctx.jobId()).setPlayerId(t.playerId())
                    .setOutcome(t.planned() ? OpsJobStore.PLANNED : t.outcome());
            if (t.planned()) {
                row.setPlannedSnapshotId(t.snapshot().getSnapshotId()).setPlannedSnapshotMs(t.snapshot().getTimeMs());
            } else {
                outcomes.put(t.playerId(), t.outcome());
                d.metrics().opsPlayer(KIND, t.outcome());
            }
            d.jobs().insertPlayer(row.build());
        }
        List<Target> planned = targets.stream().filter(Target::planned).toList();
        Map<String, Object> plannedEvent = new LinkedHashMap<>(planSummary);
        plannedEvent.put("sample", planned.stream().limit(100).map(RollbackJob::targetView).toList());
        ctx.eventQuietly(OpsJobEventType.OPS_JOB_EVENT_PLANNED, plannedEvent);
        if (planned.isEmpty()) {
            if (req.scope() == RollbackRequest.Scope.ZONES) {
                return finish(ctx, Map.of("plan", planSummary), null);
            }
            return result(OpsJobStatus.OPS_JOB_REJECTED, targets.get(0).outcome(), targets.size(), Map.of("plan", planSummary));
        }
        if (ctx.cancelRequested()) {
            return stopBeforeWrite(ctx, OpsJobStatus.OPS_JOB_CANCELLED, CANCELLED, Map.of("phase", "planned"));
        }

        // ---------------------------------------------------------------- 2 夺权
        boolean kick = req.ifOnline() == RollbackRequest.IfOnline.KICK;
        // 第一轮不踢：离线的直接夺到；kick 时把在线的一起发让出请求（scene 并行写回释放），第二轮再逐个带等待夺——
        // 总等待约为一次写回，而不是人数 × 写回
        Map<String, Integer> unclaimed = new LinkedHashMap<>();
        List<Target> online = new ArrayList<>();
        Map<Long, Long> heldEpochs = new LinkedHashMap<>();
        for (Target t : planned) {
            ctx.checkpoint();
            if (ctx.timedOut()) {
                return stopBeforeWrite(ctx, OpsJobStatus.OPS_JOB_FAILED, JOB_TIMEOUT, Map.of("phase", "claim"));
            }
            if (cancelPolled(ctx)) {
                return stopBeforeWrite(ctx, OpsJobStatus.OPS_JOB_CANCELLED, CANCELLED, Map.of("phase", "claim"));
            }
            Claim claim = d.ownership().claim(t.playerId(), false, d.props().ops().claimWait(), ctx::pause);
            if (kick && claim instanceof Claim.Online o) {
                online.add(t);
                heldEpochs.put(t.playerId(), o.heldEpoch());
                continue;
            }
            JobResult stop = afterClaim(ctx, t, claim, unclaimed, planned.size());
            if (stop != null) {
                return stop;
            }
        }
        if (!online.isEmpty()) {
            heldEpochs.forEach((player, epoch) -> d.ownership().requestTakeover(player, epoch));
            // 第二轮共用一个截止时刻（从让出请求发出时算起）：每人仍有完整的 claim-wait 等 scene 写回，而整轮至多一个 claim-wait——
            // 持有者不响应让出（发布失败、订阅断了、逻辑线程卡住但续约照常）时不会变成「在线人数 × claim-wait」，
            // 第一轮已夺到的离线玩家也不会被长时间扣着（期间登录回 2005）。截止之后每人只再试一次（照样重发让出请求）。
            long round2Deadline = System.nanoTime() + d.props().ops().claimWait().toNanos();
            for (Target t : online) {
                ctx.checkpoint();
                if (ctx.timedOut()) {
                    return stopBeforeWrite(ctx, OpsJobStatus.OPS_JOB_FAILED, JOB_TIMEOUT, Map.of("phase", "claim"));
                }
                if (cancelPolled(ctx)) {
                    return stopBeforeWrite(ctx, OpsJobStatus.OPS_JOB_CANCELLED, CANCELLED, Map.of("phase", "claim"));
                }
                Duration left = Duration.ofNanos(Math.max(0, round2Deadline - System.nanoTime()));
                Claim claim = d.ownership().claim(t.playerId(), true, left, ctx::pause);
                JobResult stop = afterClaim(ctx, t, claim, unclaimed, planned.size());
                if (stop != null) {
                    return stop;
                }
            }
        }
        if (!zoneUnclaimed.isEmpty()) {
            return zoneNotQuiescent(ctx, unclaimed, planned.size());
        }
        Map<String, Object> claimedEvent = new LinkedHashMap<>();
        claimedEvent.put("claimed", claimed.size());
        claimedEvent.put("unclaimed", unclaimed);
        claimedEvent.put("kick", kick);
        ctx.eventQuietly(OpsJobEventType.OPS_JOB_EVENT_CLAIMED, claimedEvent);
        if (claimed.isEmpty()) {
            String code = unclaimed.keySet().iterator().next();
            return finishRejected(ctx, code, Map.of("unclaimed", unclaimed));
        }
        if (ctx.cancelRequested()) {
            return stopBeforeWrite(ctx, OpsJobStatus.OPS_JOB_CANCELLED, CANCELLED, Map.of("phase", "claimed"));
        }

        // ---------------------------------------------------------------- 4 资产分歧检查
        // 4.1 账本差集（立即可得）
        int ledgerRows = 0;
        Set<Long> ledgerUnprovable = new HashSet<>();
        List<Map<String, Object>> ledgerSample = new ArrayList<>();
        Map<Long, Long> sinceByPlayer = new LinkedHashMap<>();
        for (Long playerId : List.copyOf(claimed.keySet())) {
            Target t = byPlayer.get(playerId);
            PlayerSnapshotEntry snapshot = d.snapshots().findById(t.snapshot().getSnapshotId());
            if (snapshot == null || snapshot.getPlayerId() != playerId) {
                dropClaimed(ctx, playerId, RollbackWriter.SNAPSHOT_GONE);
                continue;
            }
            PersistedPlayer current = d.players().find(playerId);
            PlayerState s;
            try {
                s = RestoreBuilder.parse(snapshot.getPlayerState(), "snapshot");
            } catch (RestoreBuilder.StateInvalidException e) {
                dropClaimed(ctx, playerId, RollbackWriter.STATE_INVALID);
                continue;
            }
            PlayerState c;
            try {
                c = RestoreBuilder.parse(current == null ? null : current.stateBytes(), "current");
            } catch (RestoreBuilder.StateInvalidException e) {
                if (!req.full()) {
                    dropClaimed(ctx, playerId, RollbackWriter.STATE_INVALID);
                    continue;
                }
                // FULL 正是修复手段：允许，但账本差集不可证明（要 acceptDivergence）
                ledgerUnprovable.add(playerId);
                sinceByPlayer.put(playerId, GuildDivergenceGate.since(snapshot.getTimeMs(), guild.clockSkewMargin()));
                continue;
            }
            try {
                RestoreBuilder.requireSameUnknownFields(s, c, req.sections());
            } catch (RestoreBuilder.UnknownSectionsException e) {
                log.warn("回档作业 job={} 玩家 {}：{}", ctx.jobIdText(), Long.toUnsignedString(playerId), e.getMessage());
                dropClaimed(ctx, playerId, RollbackWriter.UNKNOWN_SECTIONS);
                continue;
            }
            sinceByPlayer.put(playerId, GuildDivergenceGate.since(snapshot.getTimeMs(), guild.clockSkewMargin()));
            if (!RollbackSection.restoresAssets(req.sections())) {
                continue; // 不动资产组：账本不回退，不会有重复记账 / 扣款消失
            }
            LedgerDiff.Result diff = LedgerDiff.compare(s, c);
            if (!diff.unprovable().isEmpty()) {
                ledgerUnprovable.add(playerId);
            }
            ledgerRows += diff.rows().size();
            for (LedgerDiff.Row row : diff.rows()) {
                if (ledgerSample.size() < 20) {
                    ledgerSample.add(Map.of("playerId", Long.toUnsignedString(playerId), "stream", row.stream(),
                            "epoch", Long.toUnsignedString(row.epoch()), "seq", Long.toUnsignedString(row.seq())));
                }
                audit.info("[Rollback][Ledger] job={} player={} stream={} epoch={} seq={}", ctx.jobIdText(),
                        Long.toUnsignedString(playerId), row.stream(), Long.toUnsignedString(row.epoch()),
                        Long.toUnsignedString(row.seq()));
            }
        }
        d.metrics().divergenceCheck("ledger", ledgerUnprovable.isEmpty() ? ledgerRows == 0 ? "clean" : "divergence"
                : "unprovable");
        if (claimed.isEmpty()) {
            return finishRejected(ctx, outcomes.values().iterator().next(), Map.of());
        }
        // 4.2 沉降 → 帮会检查
        ctx.pause(guild.settle());
        if (ctx.cancelRequested()) {
            return stopBeforeWrite(ctx, OpsJobStatus.OPS_JOB_CANCELLED, CANCELLED, Map.of("phase", "settle"));
        }
        if (ctx.timedOut()) {
            // 写阶段之前超时：全部释放、FAILED job_timeout（§7.2），不再去问帮会
            return stopBeforeWrite(ctx, OpsJobStatus.OPS_JOB_FAILED, JOB_TIMEOUT, Map.of("phase", "settle"));
        }
        long checkStart = System.nanoTime();
        GuildDivergenceGate.Result guildResult = d.guild().check(sinceByPlayer, guild.checkBudget());
        d.metrics().guildCheckTime(System.nanoTime() - checkStart);
        d.metrics().divergenceCheck("guild", guildResult.checkFailed() ? "check_failed"
                : guildResult.clean() ? "clean" : guildResult.rows().isEmpty() ? "unprovable" : "divergence");
        // 4.3 回收逆转（快照之后的批量 / 精确回收扣减）
        List<Map<String, Object>> reversals = new ArrayList<>();
        for (Long playerId : claimed.keySet()) {
            Target t = byPlayer.get(playerId);
            List<TransactionLogEntry> rows = d.txlog().byPlayer(TransactionLogQuery.builder()
                    .reasons(RECALL_REASONS).window(t.snapshot().getTimeMs(), Long.MAX_VALUE).fetch(20).build(), playerId);
            for (TransactionLogEntry e : rows) {
                if (e.getFromPlayer() == playerId) {
                    reversals.add(Map.of("playerId", Long.toUnsignedString(playerId), "txId",
                            Long.toUnsignedString(e.getTxId()), "reason", e.getReason()));
                }
            }
        }
        d.metrics().divergenceCheck("recall", reversals.isEmpty() ? "clean" : "divergence");

        Map<String, Object> check = new LinkedHashMap<>();
        check.put("guild", guildResult.view());
        Map<String, Object> ledgerView = new LinkedHashMap<>();
        ledgerView.put("rows", ledgerRows);
        ledgerView.put("unprovablePlayers", ledgerUnprovable.stream().map(Long::toUnsignedString).limit(20).toList());
        ledgerView.put("unprovableCount", ledgerUnprovable.size());
        ledgerView.put("sample", ledgerSample);
        check.put("ledger", ledgerView);
        check.put("recallReversal", reversals.size() > 100 ? reversals.subList(0, 100) : reversals);
        check.put("recallReversalCount", reversals.size());
        ctx.eventQuietly(OpsJobEventType.OPS_JOB_EVENT_CHECK, check);

        int divergenceRows = ledgerRows + guildResult.rows().size();
        Set<Long> unprovable = new HashSet<>(ledgerUnprovable);
        unprovable.addAll(guildResult.unprovable());
        if (guildResult.checkFailed()) {
            // 问不到：放行无效（基线 28）
            Map<String, Object> s = new LinkedHashMap<>(check);
            return stopBeforeWrite(ctx, OpsJobStatus.OPS_JOB_FAILED, GUILD_CHECK_FAILED, s, divergenceRows,
                    unprovable.size());
        }
        boolean divergence = divergenceRows > 0 || !unprovable.isEmpty();
        if (divergence && !req.acceptDivergence()) {
            String code = !guildResult.rows().isEmpty() || !guildResult.unprovable().isEmpty() ? GUILD_DIVERGENCE
                    : LEDGER_DIVERGENCE;
            return stopBeforeWrite(ctx, OpsJobStatus.OPS_JOB_REJECTED, code, check, divergenceRows, unprovable.size());
        }
        if (!reversals.isEmpty() && !req.acceptRecallReversal()) {
            return stopBeforeWrite(ctx, OpsJobStatus.OPS_JOB_REJECTED, RECALL_REVERSAL, check, divergenceRows,
                    unprovable.size());
        }
        if (ctx.timedOut()) {
            // 帮会检查 / 回收逆转查询之后已超时：不写 ACCEPTED、零写入（写阶段之前超时 → FAILED job_timeout，§7.2）
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("phase", "checked");
            s.put("check", check);
            return stopBeforeWrite(ctx, OpsJobStatus.OPS_JOB_FAILED, JOB_TIMEOUT, s, divergenceRows, unprovable.size());
        }
        boolean acceptedDivergence = divergence;
        boolean acceptedReversal = !reversals.isEmpty();
        if (acceptedDivergence || acceptedReversal) {
            Map<String, Object> accepted = new LinkedHashMap<>(check);
            accepted.put("operator", ctx.operator());
            accepted.put("reason", ctx.reason());
            try {
                ctx.event(OpsJobEventType.OPS_JOB_EVENT_ACCEPTED, accepted);
            } catch (RuntimeException e) {
                log.error("回档作业 job={} 的 ACCEPTED 事件写不进：零写入", ctx.jobIdText(), e);
                return stopBeforeWrite(ctx, OpsJobStatus.OPS_JOB_FAILED, DB_ERROR, Map.of("phase", "accepted"),
                        divergenceRows, unprovable.size());
            }
            // 放行逐行 ERROR（字段同基线 rollback_logic.go:440-451），不设上限
            for (GuildAssetOpBrief op : guildResult.rows()) {
                log.error("[Rollback][Divergence] accepted job={} player={} source=guild op_id={} guild={} stream={} kind={} "
                                + "status={} updated_ms={} operator={} reason={}", ctx.jobIdText(),
                        Long.toUnsignedString(op.getPlayerId()), Long.toUnsignedString(op.getOpId()),
                        Long.toUnsignedString(op.getGuildId()), op.getStream(), op.getKind(), op.getStatus(),
                        Long.toUnsignedString(op.getUpdatedMs()), printable(ctx.operator()), printable(ctx.reason()));
            }
            for (Long p : unprovable) {
                log.error("[Rollback][Divergence] accepted job={} player={} source=unprovable operator={} reason={}",
                        ctx.jobIdText(), Long.toUnsignedString(p), printable(ctx.operator()), printable(ctx.reason()));
            }
            for (Map<String, Object> r : reversals) {
                log.error("[Rollback][RecallReversal] accepted job={} player={} tx_id={} operator={} reason={}",
                        ctx.jobIdText(), r.get("playerId"), r.get("txId"), printable(ctx.operator()), printable(ctx.reason()));
            }
            d.metrics().divergenceRows("guild", true, guildResult.rows().size());
            d.metrics().divergenceRows("ledger", true, ledgerRows);
        }
        if (ctx.cancelRequested()) {
            return stopBeforeWrite(ctx, OpsJobStatus.OPS_JOB_CANCELLED, CANCELLED, Map.of("phase", "checked"),
                    divergenceRows, unprovable.size());
        }

        // ---------------------------------------------------------------- 5 写
        List<Long> written = new ArrayList<>();
        int consecutiveFailures = 0;
        boolean stopped = false;
        for (Long playerId : List.copyOf(claimed.keySet())) {
            ctx.checkpoint();
            if (!stopped && ctx.timedOut()) {
                // 写阶段超时：写完当前玩家后停，剩下的 not_executed；结果码记 job_timeout（一个没写成时作业 FAILED，见 finish）
                stopped = true;
                writeTimedOut = true;
            }
            if (stopped) {
                setOutcome(ctx, playerId, NOT_EXECUTED);
                continue;
            }
            if (d.ownership().epochOf(playerId).isEmpty()) {
                setOutcome(ctx, playerId, RollbackWriter.FENCE_LOST);
                continue;
            }
            Target t = byPlayer.get(playerId);
            String outcome;
            try {
                outcome = d.writer().write(ctx.jobId(), ctx.operator(), playerId, t.snapshot().getSnapshotId(),
                        claimed.get(playerId), req.sections());
            } catch (RuntimeException e) {
                log.error("回档作业 job={} 写玩家 {} 失败（事务已回滚）", ctx.jobIdText(), Long.toUnsignedString(playerId), e);
                outcome = FAILED;
            }
            if (RollbackWriter.RESTORED.equals(outcome)) {
                restored++;
                consecutiveFailures = 0;
                written.add(playerId);
                outcomes.put(playerId, outcome);
                d.metrics().opsPlayer(KIND, "restored");
                audit.info("[Rollback] WRITE job={} player={} snapshot={} epoch={}", ctx.jobIdText(),
                        Long.toUnsignedString(playerId), Long.toUnsignedString(t.snapshot().getSnapshotId()),
                        Long.toUnsignedString(claimed.get(playerId)));
                continue;
            }
            setOutcome(ctx, playerId, outcome);
            if (FAILED.equals(outcome) || RollbackWriter.ID_UNAVAILABLE.equals(outcome)) {
                writeFailures++;
                consecutiveFailures++;
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES || RollbackWriter.ID_UNAVAILABLE.equals(outcome)) {
                    stopped = true;
                }
            }
        }
        Map<String, Object> writeEvent = new LinkedHashMap<>();
        writeEvent.put("restored", restored);
        writeEvent.put("failed", writeFailures);
        writeEvent.put("stopped", stopped);
        writeEvent.put("timedOut", writeTimedOut);
        ctx.eventQuietly(OpsJobEventType.OPS_JOB_EVENT_WRITE, writeEvent);

        // ---------------------------------------------------------------- 6 写后复查
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("plan", planSummary);
        summary.put("check", check);
        summary.put("restored", restored);
        summary.put("outcomes", countOutcomes());
        if (writeTimedOut) {
            summary.put("timedOut", true);
        }
        String code = null;
        if (!written.isEmpty()) {
            ctx.pause(guild.recheckDelay());
            Map<Long, Long> recheckSince = new LinkedHashMap<>();
            for (Long p : written) {
                recheckSince.put(p, sinceByPlayer.get(p));
            }
            GuildDivergenceGate.Result recheck = d.guild().check(recheckSince, guild.recheckBudget());
            Set<Long> seen = new HashSet<>();
            guildResult.rows().forEach(op -> seen.add(op.getOpId()));
            List<GuildAssetOpBrief> fresh = recheck.rows().stream().filter(op -> !seen.contains(op.getOpId())).toList();
            Map<String, Object> recheckView = new LinkedHashMap<>(recheck.view());
            recheckView.put("newRows", fresh.size());
            recheckView.put("newSample", fresh.stream().limit(GuildDivergenceGate.SAMPLE).map(GuildDivergenceGate::opView)
                    .toList());
            ctx.eventQuietly(OpsJobEventType.OPS_JOB_EVENT_RECHECK, recheckView);
            summary.put("recheck", recheckView);
            if (recheck.checkFailed()) {
                d.metrics().divergenceCheck("guild", "post_write_failed");
                log.error("回档作业 job={} 写后复查问不到帮会（{}）：已写的 {} 人转人工核对", ctx.jobIdText(), recheck.failure(),
                        written.size());
                code = POST_WRITE_UNVERIFIED;
            } else if (!fresh.isEmpty()) {
                d.metrics().divergenceCheck("guild", "post_write_diverged");
                for (GuildAssetOpBrief op : fresh) {
                    log.error("[Rollback][DivergedAfterWrite] job={} player={} op_id={} guild={} updated_ms={}（紧急：不自动撤销，"
                                    + "用 PRE_ROLLBACK 撤销或人工补偿）", ctx.jobIdText(), Long.toUnsignedString(op.getPlayerId()),
                            Long.toUnsignedString(op.getOpId()), Long.toUnsignedString(op.getGuildId()),
                            Long.toUnsignedString(op.getUpdatedMs()));
                }
                return new JobResult(OpsJobStatus.OPS_JOB_DIVERGED_AFTER_WRITE, DIVERGED_AFTER_WRITE, targets.size(),
                        restored, targets.size() - restored - reportOnly(), divergenceRows, unprovable.size(),
                        acceptedDivergence, acceptedReversal, finalizeLeftovers(ctx, summary, NOT_EXECUTED));
            } else {
                d.metrics().divergenceCheck("guild", "post_write_clean");
            }
        }
        return finish(ctx, summary, code, divergenceRows, unprovable.size(), acceptedDivergence, acceptedReversal,
                targets.size());
    }

    /** 整区里夺不到的玩家（升序；摘要给前 100 个与总数）。 */
    private final List<Long> zoneUnclaimed = new ArrayList<>();

    /**
     * 一次夺权的结局：夺到记进持有表；夺不到记明细（单人 / 多人照常继续；整区记进 {@link #zoneUnclaimed}，两轮夺完后整单拒绝）。
     * 只在访问库失败（{@code Failed}）且是整区时立即停（返回非 null）。
     */
    private JobResult afterClaim(JobContext ctx, Target t, Claim claim, Map<String, Integer> unclaimed, int planned) {
        if (claim instanceof Claim.Claimed c) {
            claimed.put(t.playerId(), c.epoch());
            return null;
        }
        String outcome = switch (claim) {
            case Claim.Online o -> PLAYER_ONLINE;
            case Claim.Busy b -> PLAYER_BUSY;
            case Claim.NotFound n -> RollbackPlanner.PLAYER_NOT_FOUND;
            default -> FAILED;
        };
        unclaimed.merge(outcome, 1, Integer::sum);
        setOutcome(ctx, t.playerId(), outcome);
        if (req.scope() != RollbackRequest.Scope.ZONES) {
            return null;
        }
        zoneUnclaimed.add(t.playerId());
        return claim instanceof Claim.Failed ? zoneNotQuiescent(ctx, unclaimed, planned) : null;
    }

    /**
     * 整区全有或全无（R4：部分玩家被排除在整区回档之外会让玩家之间的交互错位）：任何一人夺不到 → 全部释放（afterResult）、零写入，
     * REJECTED {@code zone_not_quiescent}，带前 100 个玩家号与总数。
     */
    private JobResult zoneNotQuiescent(JobContext ctx, Map<String, Integer> unclaimed, int planned) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("unclaimedPlayers", zoneUnclaimed.stream().limit(100).map(Long::toUnsignedString).toList());
        s.put("unclaimedCount", zoneUnclaimed.size());
        s.put("unclaimedByOutcome", unclaimed);
        s.put("claimed", claimed.size());
        s.put("planned", planned);
        return stopBeforeWrite(ctx, OpsJobStatus.OPS_JOB_REJECTED, ZONE_NOT_QUIESCENT, s);
    }

    @Override
    public void afterResult(JobContext ctx) {
        // 逐人：位置墓碑 → 释放（RESULT 之后，栅栏持到 RESULT 之后）
        d.ownership().releaseAll();
        claimed.clear();
    }

    // ------------------------------------------------------------------ 结局

    private JobResult finish(JobContext ctx, Map<String, Object> summary, String code) {
        return finish(ctx, summary, code, 0, 0, false, false, byPlayer.size());
    }

    /** 按写入结果定终态：全写成 SUCCEEDED；部分 PARTIAL；一个没写 REJECTED / FAILED。 */
    private JobResult finish(JobContext ctx, Map<String, Object> summary, String code, int divergenceRows, int unprovable,
                             boolean acceptedDivergence, boolean acceptedReversal, int targets) {
        Map<String, Object> s = finalizeLeftovers(ctx, new LinkedHashMap<>(summary), NOT_EXECUTED);
        int failed = targets - restored - reportOnly();
        OpsJobStatus status;
        String resultCode;
        if (restored > 0 && failed == 0) {
            status = OpsJobStatus.OPS_JOB_SUCCEEDED;
            resultCode = code != null ? code : OK;
        } else if (restored > 0) {
            status = OpsJobStatus.OPS_JOB_PARTIAL;
            resultCode = code != null ? code : writeTimedOut ? JOB_TIMEOUT : PARTIAL;
        } else if (failed == 0) {
            // 整区里全是没有快照的玩家：无事可做（只报告）
            status = OpsJobStatus.OPS_JOB_SUCCEEDED;
            resultCode = OK;
        } else if (writeFailures > 0) {
            status = OpsJobStatus.OPS_JOB_FAILED;
            resultCode = DB_ERROR;
        } else if (writeTimedOut) {
            // 写阶段超时、一个也没写成：按超时报 FAILED，而不是拿第一个没写成的明细结局当成规则拒绝
            status = OpsJobStatus.OPS_JOB_FAILED;
            resultCode = JOB_TIMEOUT;
        } else {
            status = OpsJobStatus.OPS_JOB_REJECTED;
            resultCode = firstFailureOutcome();
        }
        return new JobResult(status, resultCode, targets, restored, failed, divergenceRows, unprovable, acceptedDivergence,
                acceptedReversal, s);
    }

    private JobResult finishRejected(JobContext ctx, String code, Map<String, Object> summary) {
        Map<String, Object> s = finalizeLeftovers(ctx, new LinkedHashMap<>(summary), REJECTED);
        s.put("outcomes", countOutcomes());
        return new JobResult(OpsJobStatus.OPS_JOB_REJECTED, code, byPlayer.size(), 0, byPlayer.size() - reportOnly(), 0, 0,
                false, false, s);
    }

    private JobResult stopBeforeWrite(JobContext ctx, OpsJobStatus status, String code, Map<String, Object> summary) {
        return stopBeforeWrite(ctx, status, code, summary, 0, 0);
    }

    /** 第一笔写之前停下：已夺权的全部释放（afterResult），剩下的明细记为 rejected / cancelled，零写入。 */
    private JobResult stopBeforeWrite(JobContext ctx, OpsJobStatus status, String code, Map<String, Object> summary,
                                      int divergenceRows, int unprovable) {
        String leftover = status == OpsJobStatus.OPS_JOB_CANCELLED ? CANCELLED : REJECTED;
        Map<String, Object> s = finalizeLeftovers(ctx, new LinkedHashMap<>(summary), leftover);
        s.put("outcomes", countOutcomes());
        return new JobResult(status, code, byPlayer.size(), 0, byPlayer.size() - reportOnly(), divergenceRows, unprovable,
                false, false, s);
    }

    private JobResult result(OpsJobStatus status, String code, int planned, Map<String, Object> summary) {
        return new JobResult(status, code, planned, 0, 0, 0, 0, false, false, summary);
    }

    /** 还是 PLANNED 的明细统一记为 {@code outcome}（不写数据）。 */
    private Map<String, Object> finalizeLeftovers(JobContext ctx, Map<String, Object> summary, String outcome) {
        for (Map.Entry<Long, Target> e : byPlayer.entrySet()) {
            if (!outcomes.containsKey(e.getKey())) {
                setOutcome(ctx, e.getKey(), outcome);
            }
        }
        summary.put("outcomes", countOutcomes());
        return summary;
    }

    /** 整区里没有快照的玩家只报告（不算失败）。 */
    private int reportOnly() {
        if (req.scope() != RollbackRequest.Scope.ZONES) {
            return 0;
        }
        int n = 0;
        for (String o : outcomes.values()) {
            if (RollbackPlanner.NO_SNAPSHOT.equals(o) || RollbackPlanner.CREATED_AFTER_TARGET.equals(o)) {
                n++;
            }
        }
        return n;
    }

    private String firstFailureOutcome() {
        for (String o : outcomes.values()) {
            if (!RollbackWriter.RESTORED.equals(o) && !(req.scope() == RollbackRequest.Scope.ZONES
                    && (RollbackPlanner.NO_SNAPSHOT.equals(o) || RollbackPlanner.CREATED_AFTER_TARGET.equals(o)))) {
                return o;
            }
        }
        return REJECTED;
    }

    private Map<String, Integer> countOutcomes() {
        Map<String, Integer> m = new TreeMap<>();
        for (String o : outcomes.values()) {
            m.merge(o, 1, Integer::sum);
        }
        return m;
    }

    /** 夺权循环里的取消检查（节流读库）：kick 第二轮可能要等一个 claim-wait，期间运维要能叫停。 */
    private boolean cancelPolled(JobContext ctx) {
        long now = System.nanoTime();
        if (now - nextCancelPollNanos < 0) {
            return false;
        }
        nextCancelPollNanos = now + CANCEL_POLL.toNanos();
        return ctx.cancelRequested();
    }

    /** 一名已夺权的玩家在写之前出局：明细记结局，立即释放。 */
    private void dropClaimed(JobContext ctx, long playerId, String outcome) {
        setOutcome(ctx, playerId, outcome);
        claimed.remove(playerId);
        d.ownership().release(playerId);
    }

    /** 明细 PLANNED → 某个没写的结局（尽力：写不进只记日志，作业照常）。 */
    private void setOutcome(JobContext ctx, long playerId, String outcome) {
        if (outcomes.containsKey(playerId)) {
            return;
        }
        outcomes.put(playerId, outcome);
        d.metrics().opsPlayer(KIND, outcome);
        try {
            Long epoch = claimed.get(playerId);
            d.jobs().finishPlayer(ctx.jobId(), playerId, outcome, epoch == null ? 0 : epoch, 0, "{}", 0);
        } catch (RuntimeException e) {
            log.warn("回档作业 job={} 记明细 player={} outcome={} 失败：{}", ctx.jobIdText(), Long.toUnsignedString(playerId),
                    outcome, e.toString());
        }
    }

    private static Map<String, Object> targetView(Target t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("playerId", Long.toUnsignedString(t.playerId()));
        m.put("zoneId", Integer.toUnsignedLong(t.zoneId()));
        m.put("snapshotId", Long.toUnsignedString(t.snapshot().getSnapshotId()));
        m.put("snapshotTimeMs", t.snapshot().getTimeMs());
        m.put("snapshotIngestedAt", t.snapshot().getIngestedAt());
        m.put("snapshotCause", com.game.data.snapshot.SnapshotCauses.name(t.snapshot().getCause()));
        return m;
    }

    static String printable(String value) {
        return value == null ? null : value.replaceAll("\\p{Cntrl}", "?");
    }
}
