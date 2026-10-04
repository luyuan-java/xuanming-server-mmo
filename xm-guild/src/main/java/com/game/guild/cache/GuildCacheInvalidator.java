package com.game.guild.cache;

import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import com.game.discovery.RedisKeys;
import com.game.guild.rules.GuildLimits;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 提交后的缓存失效（基线 invalidateAfterCommit，guild_manage_repo.go:509-595；guild-spec §1.11 失效矩阵、§7.4）。
 *
 * <p>语义（同基线）：
 * <ul>
 *   <li>{@code guildId == 0} 表示只失效玩家映射；玩家号里的 0 被忽略；同一个键只失效一次。</li>
 *   <li>先用<b>请求预算</b>同步失效一次（全部键并发发出，在剩余预算内逐个等结果）；失败的键交给后台：按 100 / 400 / 1600 ms 退避重试，
 *       后台总预算 3 s，每轮只重试上一轮仍失败的键（不把已成功的代次再翻一遍）；全部用尽才记 ERROR、计
 *       {@link GuildCacheMetrics#invalidationGaveUp}（一次放弃计一次）。</li>
 *   <li><b>永不抛</b>：MySQL 已经是真相，把缓存失效的失败报成写失败只会让客户端白白进入隔离。调用方在推送<b>之前</b>调它
 *       （收件人收到推送立即拉取时必须读到新值）。</li>
 *   <li>幂等：失效是「代次换成唯一值 + 删数据键」，重复执行无害；同步那次即使只是等超时（命令其实成功了），后台再来一次也无害。</li>
 * </ul>
 *
 * <p>线程：同步部分在调用线程（guild-worker）上阻塞等待，上界是请求预算；后台部分不阻塞任何线程——退避用注入的调度器
 * （{@code schedule}），每轮的结果在 Redis 回调线程上汇总（只做计数与再调度）。
 */
public final class GuildCacheInvalidator {

    private static final Logger log = LoggerFactory.getLogger(GuildCacheInvalidator.class);

    private final GuildCache.CacheRedis redis;
    private final long generationTtlMillis;
    private final ScheduledExecutorService background;
    private final GuildCacheMetrics metrics;
    private final List<Long> retryDelaysMillis;
    private final long backgroundBudgetMillis;

    /**
     * @param ttl        缓存 TTL（代次键带 2 × TTL，与 {@link GuildCache} 用同一个值）
     * @param background 后台重试的调度器（装配方持有、负责关闭）
     */
    public GuildCacheInvalidator(GuildCache.CacheRedis redis, Duration ttl, ScheduledExecutorService background,
                                 GuildCacheMetrics metrics) {
        this(redis, ttl, background, metrics, GuildLimits.INVALIDATE_RETRY_DELAYS_MS,
                GuildLimits.INVALIDATE_BACKGROUND_BUDGET_MS);
    }

    /** 单测用：可把退避间隔与后台预算缩短（基线单测同样把 invalidateRetryDelays 改成毫秒级）。 */
    GuildCacheInvalidator(GuildCache.CacheRedis redis, Duration ttl, ScheduledExecutorService background,
                          GuildCacheMetrics metrics, List<Long> retryDelaysMillis, long backgroundBudgetMillis) {
        if (ttl.toMillis() <= 0) {
            throw new IllegalArgumentException("缓存 TTL 必须 > 0: " + ttl);
        }
        this.redis = redis;
        this.generationTtlMillis = ttl.toMillis() * 2;
        this.background = background;
        this.metrics = metrics;
        this.retryDelaysMillis = List.copyOf(retryDelaysMillis);
        this.backgroundBudgetMillis = backgroundBudgetMillis;
    }

    /** 可变参数版：{@code afterCommit(op, G, deadline, p1, p2…)}。 */
    public void afterCommit(InvalidationOp op, long guildId, Deadline deadline, long... playerIds) {
        List<Long> players = new ArrayList<>(playerIds.length);
        for (long playerId : playerIds) {
            players.add(playerId);
        }
        afterCommit(op, guildId, players, deadline);
    }

    /**
     * 提交之后失效帮会快照（{@code guildId ≠ 0} 时）与这些玩家的映射。永不抛。
     *
     * @param op       写操作（指标标签与日志）
     * @param guildId  要失效快照的帮会；0 = 不失效快照
     * @param playerIds 要失效映射的玩家（0 被忽略）
     * @param deadline 请求预算（同步那一次的等待上界）
     */
    public void afterCommit(InvalidationOp op, long guildId, Collection<Long> playerIds, Deadline deadline) {
        try {
            Set<String> keys = new LinkedHashSet<>();
            if (guildId != 0) {
                keys.add(RedisKeys.guildSnapshot(guildId));
            }
            for (Long playerId : playerIds) {
                if (playerId != null && playerId != 0) {
                    keys.add(RedisKeys.guildOfPlayer(playerId));
                }
            }
            if (keys.isEmpty()) {
                return;
            }
            List<String> targets = List.copyOf(keys);
            List<CompletableFuture<Void>> calls = fire(targets);
            List<String> failed = new ArrayList<>();
            String lastError = null;
            for (int i = 0; i < calls.size(); i++) {
                try {
                    deadline.await(calls.get(i), "失效缓存 " + targets.get(i));
                } catch (DependencyException e) {
                    failed.add(targets.get(i));
                    lastError = GuildCache.rootMessage(e);
                }
            }
            if (failed.isEmpty()) {
                return;
            }
            log.info("[guild] {}: 缓存失效推迟到后台重试 {} 个键: {}", op.label(), failed.size(), lastError);
            scheduleRound(op, failed, 0, Deadline.after(backgroundBudgetMillis), lastError);
        } catch (RuntimeException e) {
            // 兜底：失效绝不让已提交的写失败
            log.error("[guild] {}: 缓存失效出现意外异常（写已提交，相关键最多陈旧一个 TTL）", op.label(), e);
            metrics.invalidationGaveUp(op);
        }
    }

    /** 每个键发一次失效（新代次各不相同）；同步抛出的异常折成失败的 future。 */
    private List<CompletableFuture<Void>> fire(List<String> dataKeys) {
        List<CompletableFuture<Void>> calls = new ArrayList<>(dataKeys.size());
        for (String dataKey : dataKeys) {
            byte[] generation = UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII);
            CompletableFuture<Void> call;
            try {
                call = redis.invalidate(RedisKeys.cacheGeneration(dataKey), dataKey, generation, generationTtlMillis)
                        .toCompletableFuture();
            } catch (RuntimeException e) {
                call = CompletableFuture.failedFuture(e);
            }
            calls.add(call);
        }
        return calls;
    }

    /** 安排第 {@code round} 轮后台重试（round 从 0 起）；轮次用完或后台预算不够等下一轮 → 放弃。 */
    private void scheduleRound(InvalidationOp op, List<String> pending, int round, Deadline budget, String lastError) {
        if (round >= retryDelaysMillis.size()) {
            giveUp(op, pending, lastError);
            return;
        }
        long delay = retryDelaysMillis.get(round);
        if (budget.remainingMillis() <= delay) {
            giveUp(op, pending, lastError); // 基线：退避计时器还没到，后台 ctx 先到期 → 放弃
            return;
        }
        try {
            background.schedule(() -> runRound(op, pending, round, budget, lastError), delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            giveUp(op, pending, lastError + "（后台调度器已关闭）");
        }
    }

    private void runRound(InvalidationOp op, List<String> pending, int round, Deadline budget, String lastError) {
        try {
            if (budget.expired()) {
                giveUp(op, pending, lastError);
                return;
            }
            long timeout = Math.max(1, budget.remainingMillis());
            List<CompletableFuture<Void>> calls = fire(pending);
            List<CompletableFuture<Throwable>> outcomes = new ArrayList<>(calls.size());
            for (CompletableFuture<Void> call : calls) {
                outcomes.add(call.orTimeout(timeout, TimeUnit.MILLISECONDS).handle((ok, error) -> error));
            }
            CompletableFuture.allOf(outcomes.toArray(CompletableFuture[]::new)).whenComplete((done, ignored) -> {
                List<String> failed = new ArrayList<>();
                String error = lastError;
                for (int i = 0; i < outcomes.size(); i++) {
                    Throwable e = outcomes.get(i).join();
                    if (e != null) {
                        failed.add(pending.get(i));
                        error = GuildCache.rootMessage(e);
                    }
                }
                if (!failed.isEmpty()) {
                    scheduleRound(op, failed, round + 1, budget, error);
                }
            });
        } catch (RuntimeException e) {
            giveUp(op, pending, GuildCache.rootMessage(e));
        }
    }

    private void giveUp(InvalidationOp op, List<String> pending, String lastError) {
        log.error("[guild] {}: 缓存失效重试用尽，放弃 {} 个键（写已提交，这些键最多陈旧一个 TTL）{}: {}", op.label(), pending.size(),
                pending, lastError);
        metrics.invalidationGaveUp(op);
    }
}
