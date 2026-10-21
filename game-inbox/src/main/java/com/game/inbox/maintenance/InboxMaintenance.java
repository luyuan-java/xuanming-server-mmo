package com.game.inbox.maintenance;

import com.game.inbox.metrics.InboxMetrics;
import com.game.inbox.metrics.InboxMetrics.ScanGauges;
import com.game.inbox.notify.NotifyResult;
import com.game.inbox.rules.InboxShards;
import com.game.inbox.store.InboxMaintenanceStore;
import com.game.inbox.store.InboxMaintenanceStore.NotifyDue;
import com.game.inbox.store.LedgerReader;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.LongUnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * 后台循环（mmorpg {@code loops.go}；设计 §6.3、§9.1、§9.3、§9.4、§10）：清理、提醒补发、扫描型 gauge、对账，<b>只在领导者上跑</b>
 * （领导锁见 {@link LeaderLease}）：
 * <ul>
 *   <li>清理只删终态行、逐行 autocommit、不持流行锁；领导者切换期间重叠执行无害；</li>
 *   <li>补发的每一步都是 next_seq CAS，重叠执行最多多发一次提醒；</li>
 *   <li>扫描型 gauge 与对账的不一致数只由领导者产出，卸任时归零（避免 max() 聚合里留着卸任副本的旧值）；</li>
 *   <li>当选后第一轮对账只等 1–5 分钟（随机错开），不等满一个间隔（滚动发布每次都会易主）；之后按间隔跑，起始分片按墙钟轮转。</li>
 * </ul>
 * 每一轮的预算是 min(间隔, 2 分钟)；易主 / 关停时立即停下。有效期判法同 {@code WorldLeaderLock}：持有中，且距最近一次成功竞选 / 续期不足 TTL 的 2/3
 * （单调时钟，取命令发出前的读数）。关停顺序：停续期 → 停各循环并有界等待 → 放锁 → gauge 归零，之后才由 Spring 关连接池。
 */
public final class InboxMaintenance implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(InboxMaintenance.class);

    /** 每轮最多删 500 行。 */
    static final int SWEEP_BUDGET_PER_ROUND = 500;
    /** 补发每个分片每轮最多 100 条。 */
    static final int NOTIFY_PER_SHARD_LIMIT = 100;
    /** 补发只扫 notify_due_ms ≤ now − 5s 的行，不与请求内那次提醒抢。 */
    static final Duration NOTIFY_GRACE = Duration.ofSeconds(5);
    /** 提醒积压超过 5 分钟计入 notify.backlog。 */
    static final Duration NOTIFY_OVERDUE = Duration.ofMinutes(5);
    /** 最后一次投递早于 now − 24h 的已投递未确认行算陈旧，只进 delivered.unacked.stale（评审 M4）。 */
    static final Duration DELIVERED_STALE_AFTER = Duration.ofHours(24);
    /** 单轮的整体上限。 */
    static final Duration ROUND_BUDGET_CAP = Duration.ofMinutes(2);
    /** 当选后第一轮对账在 [1, 5) 分钟内随机起跑（不超过一个间隔）。 */
    static final Duration RECONCILE_FIRST_DELAY_MIN = Duration.ofMinutes(1);
    static final Duration RECONCILE_FIRST_DELAY_SPREAD = Duration.ofMinutes(4);

    /**
     * 循环参数（来自 {@code game.inbox.*}，已由配置校验）。
     *
     * @param reconcileInterval 0 = 不跑对账
     */
    public record Settings(Duration terminalRetention, Duration sweepInterval, Duration notifyInterval, Duration gaugeInterval,
                           Duration reconcileInterval, Duration reconcileWindow) {
    }

    private final LeaderLease lease;
    private final InboxMaintenanceStore store;
    private final LedgerReader ledgers;
    private final InboxMetrics metrics;
    private final Settings settings;
    private final LongSupplier wallClockMs;
    private final LongSupplier nanoClock;
    /** [0, n) 内的随机毫秒（错开当选后第一轮对账）；单测注入确定值。 */
    private final LongUnaryOperator jitterMs;

    private final Object lock = new Object();
    private boolean running;
    private ScheduledExecutorService leaseThread;
    private ScheduledExecutorService rounds;
    private ExecutorService ledgerReaders;
    private final List<ScheduledFuture<?>> loops = new ArrayList<>();
    private volatile boolean held;
    private volatile long lastRenewedNanos;
    /** 本任期的对账分片读数（只由对账那一个循环读写）。 */
    private ReconcileRound.Tally tally;

    public InboxMaintenance(LeaderLease lease, InboxMaintenanceStore store, LedgerReader ledgers, InboxMetrics metrics, Settings settings,
                            LongSupplier wallClockMs, LongSupplier nanoClock, LongUnaryOperator jitterMs) {
        this.lease = Objects.requireNonNull(lease, "lease");
        this.store = Objects.requireNonNull(store, "store");
        this.ledgers = Objects.requireNonNull(ledgers, "ledgers");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.wallClockMs = Objects.requireNonNull(wallClockMs, "wallClockMs");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.jitterMs = Objects.requireNonNull(jitterMs, "jitterMs");
    }

    @Override
    public void start() {
        synchronized (lock) {
            if (running) {
                return;
            }
            running = true;
            leaseThread = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("inbox-leader").daemon(true).factory());
            rounds = Executors.newScheduledThreadPool(4, Thread.ofPlatform().name("inbox-maintenance-", 0).daemon(true).factory());
            ledgerReaders = Executors.newFixedThreadPool(ReconcileRound.Limits.DEFAULT.readConcurrency(),
                    Thread.ofPlatform().name("inbox-ledger-read-", 0).daemon(true).factory());
            long renewMs = Math.max(1, lease.ttl().toMillis() / 3);
            leaseThread.scheduleWithFixedDelay(this::leaseTick, 0, renewMs, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void stop() {
        ScheduledExecutorService leaseExec;
        ScheduledExecutorService roundExec;
        ExecutorService readers;
        synchronized (lock) {
            if (!running) {
                return;
            }
            running = false;
            leaseExec = leaseThread;
            roundExec = rounds;
            readers = ledgerReaders;
        }
        shutdown(leaseExec, "领导锁续期");
        deposed("关停");
        shutdown(roundExec, "后台循环");
        shutdown(readers, "对账读账本");
        try {
            lease.release();
        } catch (RuntimeException e) {
            log.warn("[PlayerInbox] 放领导锁失败（TTL 到期后自然释放）: {}", e.toString());
        }
    }

    @Override
    public boolean isRunning() {
        synchronized (lock) {
            return running;
        }
    }

    /** 关停排在 Dubbo 与 Web 之后、连接池之前（数据源 bean 在 Lifecycle 之后才销毁）。 */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1000;
    }

    /** 本副本此刻是不是有效的领导者。 */
    public boolean leading() {
        return held && nanoClock.getAsLong() - lastRenewedNanos < lease.ttl().toNanos() / 3 * 2;
    }

    private static void shutdown(ExecutorService executor, String what) {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("[PlayerInbox] {} 10 s 内没有停下", what);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ================================================================ 领导权

    void leaseTick() {
        long sentAt = nanoClock.getAsLong();
        boolean ok;
        try {
            ok = lease.acquireOrRenew();
        } catch (RuntimeException e) {
            log.warn("[PlayerInbox] 领导锁竞选 / 续期出错: {}", e.toString());
            if (held && !leading()) {
                deposed("续期连续出错，已超过有效期");
            }
            return;
        }
        if (ok) {
            lastRenewedNanos = sentAt;
            if (!held) {
                held = true;
                elected();
            }
        } else if (held) {
            deposed("领导锁已被别的副本持有");
        }
    }

    private void elected() {
        synchronized (lock) {
            if (!running) {
                return;
            }
            log.info("[PlayerInbox] 当选后台维护领导者：清理 / 提醒补发 / 扫描型 gauge / 对账在本副本运行");
            tally = new ReconcileRound.Tally();
            // 终态item是永久幂等收据，不再调度物理删除。旧配置/运维方法只作兼容，不恢复删除行为。
            loops.add(every(settings.notifyInterval(), settings.notifyInterval(), this::notifyRound));
            loops.add(every(settings.gaugeInterval(), settings.gaugeInterval(), this::gaugeRound));
            if (!settings.reconcileInterval().isZero()) {
                Duration first = reconcileFirstDelay(settings.reconcileInterval(), jitterMs);
                log.info("[PlayerInbox] 第一轮对账在 {} 秒后，之后每 {}", first.toSeconds(), settings.reconcileInterval());
                loops.add(every(first, settings.reconcileInterval(), this::reconcileRound));
            }
        }
    }

    private void deposed(String why) {
        boolean was;
        synchronized (lock) {
            was = held;
            held = false;
            loops.forEach(f -> f.cancel(false));
            loops.clear();
        }
        metrics.resetLeaderGauges();
        if (was) {
            log.info("[PlayerInbox] 卸任后台维护领导者（{}）；循环已停", why);
        }
    }

    /** 当选后第一轮对账前的等待：1 分钟 + jitter([0, 4 分钟))，不超过一个间隔；jitter 越界也钳回去。 */
    static Duration reconcileFirstDelay(Duration interval, LongUnaryOperator jitterMs) {
        long spread = RECONCILE_FIRST_DELAY_SPREAD.toMillis();
        long jitter = Math.min(Math.max(jitterMs.applyAsLong(spread), 0), spread);
        Duration first = RECONCILE_FIRST_DELAY_MIN.plusMillis(jitter);
        return first.compareTo(interval) > 0 ? interval : first;
    }

    /** 本轮的控制：领导权丢失 / 关停（不计数）与本轮预算用完（计 timeout）分开。 */
    private final class Round {
        private final long deadlineNanos;

        Round(Duration interval) {
            Duration budget = interval.compareTo(ROUND_BUDGET_CAP) < 0 ? interval : ROUND_BUDGET_CAP;
            deadlineNanos = nanoClock.getAsLong() + budget.toNanos();
        }

        boolean expired() {
            return nanoClock.getAsLong() - deadlineNanos >= 0;
        }

        boolean stop() {
            return !leading() || expired() || Thread.currentThread().isInterrupted();
        }
    }

    private interface RoundBody {
        void run(Round round) throws Exception;
    }

    private ScheduledFuture<?> every(Duration first, Duration interval, RoundBody body) {
        return rounds.scheduleAtFixedRate(() -> {
            if (!leading()) {
                return;
            }
            try {
                body.run(new Round(interval));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                log.error("[PlayerInbox] 后台循环这一轮出错，下一轮照常", t);
            }
        }, first.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    // ================================================================ 各轮

    void sweepRound(Round round) throws SQLException {
        long now = wallClockMs.getAsLong();
        long retention = settings.terminalRetention().toMillis();
        if (now <= retention) {
            return;
        }
        int deleted = store.sweepTerminal(now - retention, SWEEP_BUDGET_PER_ROUND, round::stop);
        if (deleted > 0) {
            log.info("[PlayerInbox] 清理删除了 {} 条保留期（{}）外的终态行", deleted, settings.terminalRetention());
        }
    }

    void notifyRound(Round round) {
        long now = wallClockMs.getAsLong();
        long grace = NOTIFY_GRACE.toMillis();
        if (now <= grace) {
            return;
        }
        for (int shard = 0; shard < InboxShards.SHARD_COUNT; shard++) {
            if (round.stop()) {
                return;
            }
            List<NotifyDue> due;
            try {
                due = store.dueNotifications(shard, now - grace, NOTIFY_PER_SHARD_LIMIT);
            } catch (SQLException e) {
                log.error("[PlayerInbox] 提醒补发扫描失败 shard={}: {}", shard, e.toString());
                continue;
            }
            for (NotifyDue d : due) {
                if (round.stop()) {
                    return;
                }
                NotifyResult result = store.resendNotify(d);
                log.debug("[PlayerInbox] 补发提醒 player={} result={}", Long.toUnsignedString(d.playerId()), result.label());
            }
        }
    }

    void gaugeRound(Round round) throws SQLException {
        long now = wallClockMs.getAsLong();
        long overdue = NOTIFY_OVERDUE.toMillis();
        if (now <= overdue) {
            return;
        }
        long stale = DELIVERED_STALE_AFTER.toMillis();
        ScanGauges gauges = store.scanGauges(now, now - overdue, now > stale ? now - stale : 0, round::stop);
        if (leading()) {
            metrics.setScanGauges(gauges);
        }
    }

    void reconcileRound(Round round) {
        long now = wallClockMs.getAsLong();
        long window = settings.reconcileWindow().toMillis();
        long since = now > window ? now - window : 0;
        int start = ReconcileRound.startShard(now, settings.reconcileInterval().toMillis());
        ReconcileRound.Result result = null;
        String outcome;
        Exception failure = null;
        try {
            result = ReconcileRound.run(start, ReconcileRound.Limits.DEFAULT,
                    (shard, before, limit) -> store.recentlyFinalized(shard, since, before, limit), ledgers, ledgerReaders, round::stop,
                    metrics);
            outcome = !leading() ? null : round.expired() ? "timeout" : "ok";
        } catch (SQLException | RuntimeException e) {
            failure = e;
            outcome = !leading() ? null : round.expired() ? "timeout" : "error";
        }
        if (outcome == null) {
            return; // 领导权丢失 / 关停：不计数、不刷新 gauge（卸任时已归零）
        }
        metrics.reconcileRound(outcome);
        if (result != null) {
            metrics.setReconcileInconsistent(tally.apply(result.shards()));
            if (result.truncated() && settings.reconcileWindow().compareTo(
                    settings.reconcileInterval().multipliedBy(ReconcileRound.FULL_CYCLE_ROUNDS)) < 0) {
                log.error("[PlayerInbox] 对账窗口 {} 不足 {} 个间隔（{}）：轮次持续截断时，有的分片可能整个窗口都轮不到", settings.reconcileWindow(),
                        ReconcileRound.FULL_CYCLE_ROUNDS, settings.reconcileInterval());
            }
            if (result.inconsistent() > 0) {
                log.error("[PlayerInbox] 本轮对账发现 {} 处不一致（见前面的 ERROR 行）", result.inconsistent());
            }
        }
        switch (outcome) {
            case "timeout" -> log.error("[PlayerInbox] 对账轮次用完了预算（start_shard={}，核对了 {}/{} 名玩家）；没核对的留给之后的轮次", start,
                    result == null ? 0 : result.playersChecked(), result == null ? 0 : result.playersPlanned());
            case "error" -> log.error("[PlayerInbox] 对账轮次失败（start_shard={}）", start, failure);
            default -> {
            }
        }
    }
}
