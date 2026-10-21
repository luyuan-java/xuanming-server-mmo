package com.game.data.ops.fence;

import com.game.data.metrics.DataMetrics;
import com.game.player.store.OwnerLease;
import com.game.player.store.PlayerStore;
import com.game.player.store.PlayerStore.ClaimResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 运维的离线栅栏 = 归属夺权（data-ops-spec §4.2，D1）：game-data 只是归属协议里的又一个写者（architecture.md §7），不发明新协议。
 *
 * <ul>
 *   <li><b>取得</b>：{@link PlayerStore#claimOwnership}（{@code owner_released = 1 OR owner_lease_until < now} 才 epoch 加一并持有）。
 *       夺权本身是原子判定，没有「先查在线、再动手」的 TOCTOU：在线、5.2 交出在途（E+1 被持有）都夺不到；
 *       与 5.2 的 {@code handOffOwnership}（要求 E 持有、未释放、租约够长）天然互斥。每次用剩余预算调 PlayerStore 自己的带时限事务，
 *       不再套外层事务，否则加入外层事务会丢失 SQL 时限。<b>夺到不等于不在战斗</b>（批次 6.3）：Java 断线即写回并释放、回合制战斗在 game-battle
 *       继续，离线玩家的战斗锁可以还在；{@code kick} 走的顶号通路也不看是否在战斗。基线的战斗闸由回档作业在夺权之后另查战斗锁
 *       （{@code com.game.data.rollback.BattleLockGate}，data-ops-spec §13.3），不在这里。</li>
 *   <li><b>在线玩家</b>：缺省拒绝（{@link Claim.Online}）；{@code kick} 时向 {@code game:owner-takeover} 发让出请求（持有 E 的 scene 带围栏写回、
 *       释放、推 23 {2017} 后断开），退避重试夺权（50 ms 起翻倍、封顶 800 ms，每次重发让出请求），{@code wait} 内夺到就持有，否则
 *       {@link Claim.Busy}。期间玩家登录撞上 Held → login 发的让出请求没人处理（game-data 不订阅）→ 3 s 后回 2005。</li>
 *   <li><b>持有</b>：{@link #renew} 由 {@code data-ops-fence} 线程每 10 s 调一次（与 scene 续约同口径）；续不上的玩家记为 {@link #lost}，
 *       之后不再对他写（写事务开头还会加锁核对 epoch，迟到的一笔只会被拒）。</li>
 *   <li><b>释放</b>：先写登出墓碑（位置记录按 (epoch, 序号) 只收更新的写，我们的 epoch 更大，一定生效；被接管的 scene 不改位置记录，
 *       不写会留下指向旧实例的「在线」记录）→ 再带围栏释放。墓碑失败只告警（TTL 兜底）；释放失败就等租约过期（≤ 30 s）。</li>
 * </ul>
 * 线程：{@link #claim} / {@link #release} 在作业线程上，{@link #renew} 在栅栏线程上；持有表是并发表。
 */
public final class AdminOwnership {

    private static final Logger log = LoggerFactory.getLogger(AdminOwnership.class);

    /** 夺权重试退避的起点与上限（同 login 的 claim 退避口径）。 */
    static final Duration BACKOFF_START = Duration.ofMillis(50);
    static final Duration BACKOFF_MAX = Duration.ofMillis(800);
    /** 写墓碑最多等多久（异步 Redis 脚本；超时只告警）。 */
    static final Duration TOMBSTONE_WAIT = Duration.ofSeconds(2);
    /** 墓碑的写序号：这个 epoch 只有我们写位置记录，从 1 起就够。 */
    public static final long TOMBSTONE_SEQ = 1;

    /** 一次夺权的结局。 */
    public sealed interface Claim {

        /** 夺到并持有 {@code epoch}；{@code kicked} = 期间发过让出请求（玩家原本在线）。 */
        record Claimed(long epoch, boolean kicked) implements Claim {
        }

        /** 在线（被 {@code heldEpoch} 持有），没有踢。 */
        record Online(long heldEpoch) implements Claim {
        }

        /** 踢了但等不到释放（有活着的写者在续约，或交出在途）。 */
        record Busy(long heldEpoch) implements Claim {
        }

        /** 玩家不存在。 */
        record NotFound() implements Claim {
        }

        /** 访问库出错。 */
        record Failed(String error) implements Claim {
        }
    }

    /** 让出请求的发布方（{@code game:owner-takeover}；尽力而为、不阻塞、不抛异常）。 */
    @FunctionalInterface
    public interface TakeoverRequests {
        void request(long playerId, long heldEpoch);
    }

    /** 位置墓碑（{@code PlayerLocationDirectory.removeAsync}）。返回是否生效（false = 已有更新的写）。 */
    @FunctionalInterface
    public interface LocationTombstones {
        java.util.concurrent.CompletionStage<Boolean> tombstone(long playerId, long epoch);
    }

    /** 退避等待（作业里传 {@code JobContext::pause}，心跳丢失时抛异常中止）。 */
    @FunctionalInterface
    public interface Pauser {
        void pause(Duration duration);
    }

    private final PlayerStore store;
    private final TakeoverRequests takeovers;
    private final LocationTombstones tombstones;
    private final DataMetrics metrics;
    /** 持有中的 player → epoch。 */
    private final Map<Long, Long> held = new ConcurrentHashMap<>();
    /** 续约发现已失去的玩家（不再对他写；释放时跳过）。 */
    private final Set<Long> lost = ConcurrentHashMap.newKeySet();

    public AdminOwnership(PlayerStore store, TransactionTemplate tx, TakeoverRequests takeovers,
                          LocationTombstones tombstones, DataMetrics metrics) {
        this.store = store;
        // 保留既有装配签名；带时限夺权必须使用 PlayerStore 自有事务，不能再由此模板包裹。
        java.util.Objects.requireNonNull(tx.getTransactionManager(), "运维事务管理器");
        this.takeovers = takeovers;
        this.tombstones = tombstones;
        this.metrics = metrics;
    }

    /**
     * 夺取一名玩家的归属。
     *
     * @param kick   在线时是否走顶号通路踢下线
     * @param wait   kick 时最多等多久（{@code game.data.ops.claim-wait}）
     * @param pauser 退避等待
     */
    public Claim claim(long playerId, boolean kick, Duration wait, Pauser pauser) {
        if (wait == null || wait.isZero() || wait.isNegative()) throw new IllegalArgumentException("运维夺权预算必须为正");
        long deadline = System.nanoTime() + wait.toNanos();
        Duration backoff = BACKOFF_START;
        boolean kicked = false;
        Long observedHeld = null;
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return timedOut(playerId, observedHeld);
            ClaimResult result;
            try {
                result = store.claimOwnership(playerId, Duration.ofNanos(remaining));
            } catch (RuntimeException e) {
                metrics.opsClaim("error");
                log.warn("运维夺权访问库失败 player={}：{}", Long.toUnsignedString(playerId), e.toString());
                return new Claim.Failed(e.toString());
            }
            if (result instanceof ClaimResult.TimedOut) return timedOut(playerId, observedHeld);
            if (result instanceof ClaimResult.Claimed claimed) {
                return rememberClaim(playerId, claimed.ownerEpoch(), kicked);
            }
            if (result instanceof ClaimResult.NotFound || result == null) {
                metrics.opsClaim("not_found");
                return new Claim.NotFound();
            }
            long heldEpoch = ((ClaimResult.Held) result).ownerEpoch();
            observedHeld = heldEpoch;
            if (!kick) {
                metrics.opsClaim("online");
                return new Claim.Online(heldEpoch);
            }
            takeovers.request(playerId, heldEpoch);
            kicked = true;
            remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return timedOut(playerId, observedHeld);
            }
            Duration delay = backoff.toNanos() < remaining ? backoff : Duration.ofNanos(remaining);
            pauser.pause(delay);
            backoff = backoff.multipliedBy(2).compareTo(BACKOFF_MAX) < 0 ? backoff.multipliedBy(2) : BACKOFF_MAX;
        }
    }

    /**
     * 历史 SQL 回退后的显式维护入口；不得以普通 claim 替代。审核上界必须独立于恢复库、覆盖所有已发纪元，
     * 调用期间保持玩家入口、旧写者及旧发号者停用，直到全部新围栏安装完毕。缺少上界直接拒绝，绝不从回退 SQL 猜测。
     * 只推进归属元数据，资产原字节及 saved_epoch 不变；返回后使用本门面的续约/释放生命周期。
     *
     * <p>阻塞 SQL，不能在外层事务、I/O 或场景线程调用。SQL 异常可能是提交应答丢失，Failed 不保证零改动；
     * 重投见 Online 不得自行认领该纪元。本方法不自动踢人或重试发号，也不承诺恢复幂等。
     */
    public Claim recoverAfterRestore(long playerId, OptionalLong auditedOldEpochUpperBound, Duration timeout) {
        metrics.opsClaim("error");return new Claim.Failed("历史恢复必须同时提供独立审核的账号归属修订上界；仅E入口已封闭");
    }
    public Claim recoverAfterRestore(long playerId,OptionalLong auditedOldEpochUpperBound,OptionalLong auditedOwnerRevisionUpperBound,Duration timeout){
        if (auditedOldEpochUpperBound == null || auditedOldEpochUpperBound.isEmpty()) {
            metrics.opsClaim("error");
            return new Claim.Failed("缺少独立审核旧纪元上界");
        }
        if(auditedOwnerRevisionUpperBound==null||auditedOwnerRevisionUpperBound.isEmpty()){
            metrics.opsClaim("error");return new Claim.Failed("缺少独立审核的账号归属修订上界");
        }
        ClaimResult result;
        try {
            result = store.recoverOwnershipAfterRestore(playerId, auditedOldEpochUpperBound.getAsLong(),auditedOwnerRevisionUpperBound.getAsLong(), timeout);
        } catch (RuntimeException e) {
            metrics.opsClaim("error");
            // SQL/连接异常可能夹带连接串或驱动配置；维护入口只记异常类型，不透出异常消息。
            log.warn("历史归属恢复失败 player={} type={}", Long.toUnsignedString(playerId), e.getClass().getSimpleName());
            return new Claim.Failed("历史归属恢复失败");
        }
        if (result instanceof ClaimResult.Claimed claimed) return rememberClaim(playerId, claimed.ownerEpoch(), false);
        if (result instanceof ClaimResult.Held online) {
            metrics.opsClaim("online");
            return new Claim.Online(online.ownerEpoch());
        }
        if (result instanceof ClaimResult.TimedOut) return timedOut(playerId, null);
        metrics.opsClaim("not_found");
        return new Claim.NotFound();
    }

    private Claim rememberClaim(long playerId, long epoch, boolean kicked) {
        held.put(playerId, epoch);
        lost.remove(playerId);
        metrics.fenceHeld(held.size());
        metrics.opsClaim(kicked ? "kicked" : "claimed");
        return new Claim.Claimed(epoch, kicked);
    }

    private Claim timedOut(long playerId, Long heldEpoch) {
        metrics.opsClaim("timeout");
        log.warn("运维夺权预算已用尽 player={} held_epoch={}", Long.toUnsignedString(playerId),
                heldEpoch == null ? "unknown" : Long.toUnsignedString(heldEpoch));
        return heldEpoch == null ? new Claim.Failed("运维夺权超时") : new Claim.Busy(heldEpoch);
    }

    /** 仍由我们持有（没被续约判失去）的 epoch；不持有为空。 */
    public OptionalLong epochOf(long playerId) {
        Long epoch = held.get(playerId);
        return epoch == null || lost.contains(playerId) ? OptionalLong.empty() : OptionalLong.of(epoch);
    }

    /** 续约发现已失去。 */
    public boolean lost(long playerId) {
        return lost.contains(playerId);
    }

    public int heldCount() {
        return held.size();
    }

    /** 批量续约（栅栏线程每 10 s）。续不上的记为失去、移出持有表。 */
    public void renew() {
        if (held.isEmpty()) {
            return;
        }
        List<OwnerLease> leases = new ArrayList<>();
        held.forEach((player, epoch) -> leases.add(new OwnerLease(player, epoch)));
        List<OwnerLease> gone;
        try {
            gone = store.renewOwnerLeases(leases);
        } catch (RuntimeException e) {
            log.warn("运维栅栏续约失败（{} 人；租约 30 s 内再试）：{}", leases.size(), e.toString());
            return;
        }
        long count = 0;
        for (OwnerLease lease : gone) {
            if (held.remove(lease.playerId(), lease.ownerEpoch())) {
                lost.add(lease.playerId());
                count++;
                log.error("运维栅栏续约失败：玩家 {} 的归属（epoch {}）已不在我们手里，之后不再对他写",
                        Long.toUnsignedString(lease.playerId()), Long.toUnsignedString(lease.ownerEpoch()));
            }
        }
        metrics.fenceLost(count);
        metrics.fenceHeld(held.size());
    }

    /** 释放一名玩家：位置墓碑 → 带围栏释放。没持有（或已失去）什么也不做。 */
    public void release(long playerId) {
        Long epoch = held.remove(playerId);
        metrics.fenceHeld(held.size());
        if (epoch == null) {
            lost.remove(playerId);
            return;
        }
        if (lost.remove(playerId)) {
            return;
        }
        tombstone(playerId, epoch);
        try {
            if (!store.releaseOwnership(playerId, epoch)) {
                log.warn("运维释放归属没改到行（epoch 已变或已释放） player={} epoch={}", Long.toUnsignedString(playerId),
                        Long.toUnsignedString(epoch));
            }
        } catch (RuntimeException e) {
            log.warn("运维释放归属失败（等租约 30 s 过期） player={} epoch={}：{}", Long.toUnsignedString(playerId),
                    Long.toUnsignedString(epoch), e.toString());
        }
    }

    /**
     * 只发一次让出请求（不夺权）：整区 / 多人 kick 时先给全部在线目标一起发，再逐个 {@link #claim}——scene 并行写回释放，
     * 总等待约为一次写回而不是人数 × 写回。尽力而为、不阻塞。
     */
    public void requestTakeover(long playerId, long heldEpoch) {
        takeovers.request(playerId, heldEpoch);
    }

    /**
     * 释放全部（作业收尾）：先<b>并发</b>发出全部位置墓碑、一起等至多 {@link #TOMBSTONE_WAIT}（Redis 不可用时不至于人数 × 超时），
     * 再逐个带围栏释放。墓碑失败只告警（TTL 60 s 兜底）。
     */
    public void releaseAll() {
        releaseMany(List.copyOf(held.keySet()));
        lost.clear();
    }

    /**
     * 释放一批（写之前一起出局的玩家，例如回档前查到战斗锁仍在 / 读不到）：做法同 {@link #releaseAll}——墓碑并发发出、一起等至多
     * {@link #TOMBSTONE_WAIT}，再逐个带围栏释放——只限给出的玩家，其余持有不动。没持有（或续约已判失去）的跳过。
     * 逐个调 {@link #release} 在 Redis 不可用时是人数 × 超时，而读不到战斗锁恰恰多半是 Redis 不可用。
     */
    public void releaseMany(Collection<Long> playerIds) {
        Map<Long, Long> toRelease = new LinkedHashMap<>();
        for (Long playerId : playerIds) {
            Long epoch = held.remove(playerId);
            if (!lost.remove(playerId) && epoch != null) {
                toRelease.put(playerId, epoch);
            }
        }
        metrics.fenceHeld(held.size());
        if (toRelease.isEmpty()) {
            return;
        }
        Map<Long, CompletableFuture<Boolean>> pending = new LinkedHashMap<>();
        toRelease.forEach((playerId, epoch) -> {
            CompletableFuture<Boolean> f;
            try {
                f = tombstones.tombstone(playerId, epoch).toCompletableFuture();
            } catch (RuntimeException e) {
                f = CompletableFuture.failedFuture(e);
            }
            pending.put(playerId, f);
        });
        long deadline = System.nanoTime() + TOMBSTONE_WAIT.toNanos();
        int errors = 0;
        for (var e : pending.entrySet()) {
            long left = Math.max(0, deadline - System.nanoTime());
            try {
                Boolean applied = e.getValue().get(left, TimeUnit.NANOSECONDS);
                metrics.locationTombstone(Boolean.TRUE.equals(applied) ? "ok" : "stale");
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                metrics.locationTombstone("error");
                errors++;
            } catch (Exception ex) {
                metrics.locationTombstone("error");
                errors++;
            }
        }
        if (errors > 0) {
            log.warn("运维收尾写位置墓碑有 {} / {} 人失败或超时（TTL 60 s 兜底）", errors, pending.size());
        }
        toRelease.forEach((playerId, epoch) -> {
            try {
                if (!store.releaseOwnership(playerId, epoch)) {
                    log.warn("运维释放归属没改到行（epoch 已变或已释放） player={} epoch={}", Long.toUnsignedString(playerId),
                            Long.toUnsignedString(epoch));
                }
            } catch (RuntimeException ex) {
                log.warn("运维释放归属失败（等租约 30 s 过期） player={} epoch={}：{}", Long.toUnsignedString(playerId),
                        Long.toUnsignedString(epoch), ex.toString());
            }
        });
    }

    private void tombstone(long playerId, long epoch) {
        try {
            Boolean applied = tombstones.tombstone(playerId, epoch).toCompletableFuture()
                    .get(TOMBSTONE_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            metrics.locationTombstone(Boolean.TRUE.equals(applied) ? "ok" : "stale");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            metrics.locationTombstone("error");
        } catch (Exception e) {
            metrics.locationTombstone("error");
            log.warn("写位置墓碑失败（TTL 60 s 兜底） player={} epoch={}：{}", Long.toUnsignedString(playerId),
                    Long.toUnsignedString(epoch), e.toString());
        }
    }
}
