package com.game.friend.sweep;

import com.game.friend.store.SweepStore;
import com.game.friend.store.SweepStore.Result;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 后台清理（mmorpg go/friend internal/logic/sweep.go，friend-spec.md §6）：每轮先清终态好友申请、再回收零好友容量行，
 * 前一段失败不影响后一段。缺省 {@code report_only}（只数不删、积压 &gt; 0 打 WARN 级 ERROR 日志），{@code delete} 才删。
 *
 * <p>节拍：首轮延迟 = 随机 [0, interval) + interval（多副本同时重启不在同一秒一起删），之后固定延迟 interval，单线程、
 * 不叠轮；每轮 catch Throwable（未捕获的异常会让调度静默停掉后续执行）；单轮预算 min(interval, 30 s)，逐行删之前检查。
 * 多副本各跑各的、不选主（逐行按主键删 + 提交点复核，后到的一方删到 0 行无害）。不需要失效任何缓存。
 *
 * <p>Gauge 刷新纪律（同基线）：只在合法模式、只在成功时刷，每轮都刷（含 0——长期不更新是「清理没在跑」的唯一信号）；失败分支不刷
 * （候选读失败时 idle = 0，刷了会把失败伪装成无积压）。
 */
public final class FriendSweep implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FriendSweep.class);

    static final Duration ROUND_BUDGET = Duration.ofSeconds(30);

    /** 两个 Gauge（按模式）。 */
    public interface Gauges {
        void pendingRows(String mode, long value);

        void idleCapacityRows(String mode, long value);
    }

    private final SweepStore store;
    private final String mode;
    private final Duration interval;
    private final int retentionDays;
    private final int batchLimit;
    private final LongSupplier clockMs;
    private final Gauges gauges;
    private ScheduledExecutorService scheduler;

    public FriendSweep(SweepStore store, String mode, Duration interval, int retentionDays, int batchLimit,
                       LongSupplier clockMs, Gauges gauges) {
        this.store = store;
        this.mode = mode;
        this.interval = interval;
        this.retentionDays = retentionDays;
        this.batchLimit = batchLimit;
        this.clockMs = clockMs;
        this.gauges = gauges;
    }

    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        long intervalMs = interval.toMillis();
        long firstDelay = ThreadLocalRandom.current().nextLong(intervalMs) + intervalMs;
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("friend-sweep").daemon(true).factory());
        scheduler.scheduleWithFixedDelay(this::runRoundSafely, firstDelay, intervalMs, TimeUnit.MILLISECONDS);
        log.info("[friend] sweep 启动 mode={} interval={} retention_days={} batch={}（清理对象：friend_request 终态行 +"
                + " friend_capacity 零好友行）", mode, interval, retentionDays, batchLimit);
    }

    private void runRoundSafely() {
        try {
            runRound();
        } catch (Throwable t) {
            log.error("[friend] sweep 本轮异常（只丢这一轮）", t);
        }
    }

    /** 跑一轮（调度线程上；测试与运维手动触发也可直调）。 */
    public void runRound() {
        long budgetMs = Math.min(interval.toMillis(), ROUND_BUDGET.toMillis());
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        boolean known = SweepStore.MODE_DELETE.equals(mode) || SweepStore.MODE_REPORT_ONLY.equals(mode);
        if (!known) {
            log.error("[friend] sweep 模式 {} 不是 {} / {}，本轮未清理任何行", mode, SweepStore.MODE_REPORT_ONLY, SweepStore.MODE_DELETE);
        }
        long now = clockMs.getAsLong();
        Result terminal = store.sweepTerminalRequests(mode, retentionDays, batchLimit, now,
                () -> System.nanoTime() - deadline < 0);
        reportTerminal(terminal, known);
        Result idle = store.sweepIdleCapacityRows(mode, retentionDays, batchLimit, now,
                () -> System.nanoTime() - deadline < 0);
        reportIdle(idle, known);
    }

    private void reportTerminal(Result r, boolean known) {
        if (r.error() != null) {
            log.error("[friend] sweep 清理终态申请失败 mode={}（中止前看到 {} 行、已删 {} 行）: {}", mode, r.seen(), r.deleted(),
                    r.error().toString());
            return;
        }
        if (!known) {
            return;
        }
        gauges.pendingRows(mode, r.seen());
        if (SweepStore.MODE_DELETE.equals(mode)) {
            if (r.deleted() > 0) {
                log.info("[friend] sweep 删除 {} 行终态好友申请（retention_days={}，batch={}）", r.deleted(), retentionDays, batchLimit);
            } else if (r.seen() > 0) {
                log.error("[friend] WARN sweep(delete) 有 {} 行待清理却一行未删：检查是否触发了 updated_ms=0 的保险，"
                        + "或只是被另一个副本抢先删了", r.seen());
            }
        } else if (r.seen() > 0) {
            log.error("[friend] WARN sweep(report_only) 发现 {} 行终态好友申请已过保留期（retention_days={}，统计上限 {}）；"
                    + "切 delete 前先确认这个数字合理", r.seen(), retentionDays, batchLimit);
        }
    }

    private void reportIdle(Result r, boolean known) {
        if (r.error() != null) {
            log.error("[friend] sweep 回收零好友容量行失败 mode={}（中止前看到 {} 行、已删 {} 行）: {}", mode, r.seen(), r.deleted(),
                    r.error().toString());
            return;
        }
        if (!known) {
            return;
        }
        gauges.idleCapacityRows(mode, r.seen());
        if (SweepStore.MODE_DELETE.equals(mode)) {
            if (r.deleted() > 0) {
                log.info("[friend] sweep 回收 {} 行零好友容量行（本轮看到 {} 行，retention_days={}，batch={}）", r.deleted(), r.seen(),
                        retentionDays, batchLimit);
            }
        } else if (r.seen() > 0) {
            log.error("[friend] WARN sweep(report_only) 发现 {} 行零好友容量行已过保留期（retention_days={}，统计上限 {}）；"
                    + "持续上涨说明有人在对大量不同目标发申请 / 反复拉黑换目标", r.seen(), retentionDays, batchLimit);
        }
    }

    @Override
    public synchronized void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }
}
