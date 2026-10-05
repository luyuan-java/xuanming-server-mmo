package com.game.guild.service;

import com.game.api.proto.GuildAssetOpBrief;
import com.game.api.proto.ListAppliedAssetOpsSinceRequest;
import com.game.api.proto.ListAppliedAssetOpsSinceResponse;
import com.game.common.deadline.Deadline;
import com.game.guild.asset.AppliedOpsQuery;
import com.game.guild.asset.GuildAssetStore;
import com.game.guild.asset.GuildAssetStore.AppliedOpBrief;
import com.game.guild.asset.GuildAssetStore.AppliedOpsPage;
import com.game.guild.metrics.GuildMetrics;
import com.game.guild.metrics.GuildMetrics.ListAppliedResult;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 帮会的服务间内部查询 {@code ListAppliedAssetOpsSince}（基线 {@code guild_internal_server.go}；guild-economy-spec §3.6、§7.11、E11 / Q7）：
 * 回档闸（7.2）回档写玩家数据之前先问「这些玩家自快照时刻以来，有没有已终结为已应用的帮会资产指令」，有就拒绝回档。4.5 只交付提供方，没有消费方。
 *
 * <p>判定顺序（全部先于 SQL，guild_internal_server.go:146-226）：入参（{@code INVALID_ARGUMENT}，文案照抄基线，记 ERROR——调用方的编程错误）→
 * 装配（Store 未装配或保留期 ≤ 0 → {@code UNAVAILABLE}）→ 保留期（{@code since_ms < now + 1 h − 终态保留期} → {@code RETENTION_REJECTED}，
 * 带 {@code cutoff_ms}，记 INFO——预期内）→ 查询（失败 → {@code ERROR}；超时与其它失败的 detail 照基线区分，库错误原文不外发）。
 * E11：拒绝走应答里的结果码与 {@code cutoff_ms} 字段，不靠异常与解析 message 前缀；{@code detail} 只进日志。
 *
 * <p>线程：判定在 Dubbo 线程上做（纯计算），SQL 在 guild-worker 上跑（阻塞 JDBC 不占 Dubbo 线程）；工作队列满 → {@code ERROR}（消费方按「问不到」
 * 拒绝回档，方向 fail-closed）。查询没有自己的子预算，靠整请求预算（同基线）。player_id 不进日志与指标，只记规模。线程安全。
 */
public final class GuildInternalQueries {

    private static final Logger log = LoggerFactory.getLogger(GuildInternalQueries.class);

    /** 保留期拒绝的英文原因前缀（同基线 RetentionRejectedMessagePrefix；Java 只进 detail / 日志，消费方读 {@code cutoff_ms} 字段）。 */
    static final String RETENTION_REJECTED_PREFIX = "since_ms older than terminal retention; cutoff_ms=";

    private final GuildAssetStore store;
    private final long terminalRetentionMs;
    private final Executor executor;
    private final GuildMetrics metrics;
    private final LongSupplier clockMs;
    private final long budgetMillis;

    /**
     * @param store             资产账本 Store；null = 未装配（一律 UNAVAILABLE）
     * @param terminalRetention 终态行保留期（{@code xm.guild.asset-op.terminal-retention}，与清理开关无关）；≤ 0 一律 UNAVAILABLE
     * @param executor          跑 SQL 的工作池（guild-worker）
     * @param budgetMillis      一次查询的预算（整请求预算）
     */
    public GuildInternalQueries(GuildAssetStore store, Duration terminalRetention, Executor executor, GuildMetrics metrics,
                                LongSupplier clockMs, long budgetMillis) {
        this.store = store;
        this.terminalRetentionMs = terminalRetention == null ? 0 : terminalRetention.toMillis();
        this.executor = Objects.requireNonNull(executor, "executor");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clockMs = Objects.requireNonNull(clockMs, "clockMs");
        this.budgetMillis = budgetMillis;
    }

    /** 判定 + 查询；返回的 future 不异常完成（拒绝与失败都是应答里的结果码）。 */
    public CompletableFuture<ListAppliedAssetOpsSinceResponse> listAppliedAssetOpsSince(ListAppliedAssetOpsSinceRequest request) {
        String invalid = request == null ? "request is required"
                : AppliedOpsQuery.requestError(request.getPlayerIdsList(), request.getSinceMs(), request.getLimit());
        if (invalid != null) {
            metrics.listApplied(ListAppliedResult.INVALID, 0);
            // 调用方的编程错误，不是运行期常态：ERROR 级，便于第一时间发现契约漂移
            log.error("[GuildInternal] ListAppliedAssetOpsSince 入参非法: {}", invalid);
            return done(reject(com.game.api.proto.ListAppliedResult.LIST_APPLIED_RESULT_INVALID_ARGUMENT, invalid, 0));
        }
        if (store == null || terminalRetentionMs <= 0) {
            metrics.listApplied(ListAppliedResult.UNAVAILABLE, 0);
            log.error("[GuildInternal] ListAppliedAssetOpsSince 不可用：资产 Store 已装配={} 终态保留期={}ms", store != null,
                    terminalRetentionMs);
            return done(reject(com.game.api.proto.ListAppliedResult.LIST_APPLIED_RESULT_UNAVAILABLE,
                    "guild asset op store is not available", 0));
        }
        long cutoff = AppliedOpsQuery.retentionCutoffMs(clockMs.getAsLong(), terminalRetentionMs);
        if (Long.compareUnsigned(request.getSinceMs(), cutoff) < 0) {
            // 预期内的答复：快照早于终态流水保留期，这段已无法证明。消费方据 cutoff_ms 钳位重查，不打 ERROR
            metrics.listApplied(ListAppliedResult.RETENTION, 0);
            log.info("[GuildInternal] ListAppliedAssetOpsSince since_ms={} 早于保留期下界 cutoff_ms={}",
                    Long.toUnsignedString(request.getSinceMs()), Long.toUnsignedString(cutoff));
            return done(reject(com.game.api.proto.ListAppliedResult.LIST_APPLIED_RESULT_RETENTION_REJECTED,
                    RETENTION_REJECTED_PREFIX + Long.toUnsignedString(cutoff), cutoff));
        }
        AppliedOpsQuery query = new AppliedOpsQuery(request.getZoneId(), request.getPlayerIdsList(), request.getSinceMs(),
                request.getAfterOpId(), AppliedOpsQuery.effectiveLimit(request.getLimit()));
        Deadline deadline = Deadline.after(budgetMillis);
        CompletableFuture<ListAppliedAssetOpsSinceResponse> out = new CompletableFuture<>();
        try {
            executor.execute(() -> out.complete(query(query, deadline)));
        } catch (RejectedExecutionException e) {
            metrics.listApplied(ListAppliedResult.ERROR, 0);
            log.error("[GuildInternal] ListAppliedAssetOpsSince 工作队列已满 players={} zone_id={}", query.playerIds().size(),
                    Integer.toUnsignedString(query.zoneId()));
            out.complete(reject(com.game.api.proto.ListAppliedResult.LIST_APPLIED_RESULT_ERROR, "guild service overloaded", 0));
        }
        return out;
    }

    private ListAppliedAssetOpsSinceResponse query(AppliedOpsQuery query, Deadline deadline) {
        AppliedOpsPage page;
        try {
            page = store.listAppliedSince(query, deadline);
        } catch (RuntimeException e) {
            metrics.listApplied(ListAppliedResult.ERROR, 0);
            // 详情（含库错误）只进日志，不回给调用方；player_id 不进日志，只记规模
            log.error("[GuildInternal] ListAppliedAssetOpsSince 查询失败 players={} zone_id={}: {}", query.playerIds().size(),
                    Integer.toUnsignedString(query.zoneId()), e.toString());
            return reject(com.game.api.proto.ListAppliedResult.LIST_APPLIED_RESULT_ERROR,
                    timedOut(e, deadline) ? "list applied asset ops timed out" : "list applied asset ops failed", 0);
        }
        ListAppliedAssetOpsSinceResponse.Builder resp = ListAppliedAssetOpsSinceResponse.newBuilder()
                .setResult(com.game.api.proto.ListAppliedResult.LIST_APPLIED_RESULT_OK)
                .setNextAfterOpId(page.nextAfterOpId());
        for (AppliedOpBrief op : page.ops()) {
            resp.addOps(GuildAssetOpBrief.newBuilder()
                    .setOpId(op.opId()).setPlayerId(op.playerId()).setGuildId(op.guildId()).setStream(op.stream())
                    .setKind(op.kind()).setStatus(op.status()).setFundsDelta(op.fundsDelta())
                    .setContributionDelta(op.contributionDelta()).setUpdatedMs(op.updatedMs()));
        }
        metrics.listApplied(page.ops().isEmpty() ? ListAppliedResult.OK_EMPTY : ListAppliedResult.OK_ROWS, page.ops().size());
        return resp.build();
    }

    private static boolean timedOut(Throwable e, Deadline deadline) {
        if (deadline.expired()) {
            return true;
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof TimeoutException || t instanceof java.sql.SQLTimeoutException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static ListAppliedAssetOpsSinceResponse reject(com.game.api.proto.ListAppliedResult result, String detail,
                                                           long cutoffMs) {
        return ListAppliedAssetOpsSinceResponse.newBuilder().setResult(result).setDetail(detail).setCutoffMs(cutoffMs).build();
    }

    private static CompletableFuture<ListAppliedAssetOpsSinceResponse> done(ListAppliedAssetOpsSinceResponse response) {
        return CompletableFuture.completedFuture(response);
    }
}
