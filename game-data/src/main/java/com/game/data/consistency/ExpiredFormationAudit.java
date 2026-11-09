package com.game.data.consistency;

import com.game.common.deadline.Deadline;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 异常到期成局的周期观察。只调用独立 SELECT 端口，不取得业务租约、不恢复或释放票据。
 * 游标跨轮推进以免坏旧行饿死后面的候选；每次发布仅含本轮观察，不累加成全库快照。
 */
public final class ExpiredFormationAudit implements AutoCloseable {
    public enum State { UNKNOWN, OBSERVED, STALE, STOPPED, DISABLED }
    public record Observation(State state, long scanned, long confirmedBad, long uncertain,
            boolean windowFinished, String reason, long startedNanos) {
        /** 未知覆盖、过期、停机或查询失败不能显示健康的零值。非零值也只是本轮下界。 */
        public double badLowerBound() {
            return state == State.OBSERVED || state == State.UNKNOWN && confirmedBad > 0
                    ? confirmedBad : Double.NaN;
        }
    }
    private static final Logger log = LoggerFactory.getLogger(ExpiredFormationAudit.class);
    private static final ConsistencySqlPlans.Plan SHAPE = ConsistencySqlPlans.forCheck("match.expired_unrecoverable").getFirst();
    private final ConsistencySqlReader sql;
    private final ExpiredFormationAuditProperties properties;
    private final ScheduledExecutorService worker;
    private final LongSupplier nanoClock;
    private final ReentrantLock turn = new ReentrantLock();
    private final Object lifecycle = new Object();
    private volatile Observation observation;
    private volatile boolean closed;
    private boolean started;
    // 仅由 turn 持有者访问；没有跨副本写权含义，也不进入日志或指标。
    private ConsistencySqlPlans.Key cursor;
    private ConsistencySqlPlans.Key upper;

    public ExpiredFormationAudit(ConsistencySqlReader sql, ExpiredFormationAuditProperties properties, MeterRegistry metrics) {
        this(sql, properties, metrics, Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("expired-formation-audit").daemon(true).factory()), System::nanoTime);
    }
    ExpiredFormationAudit(ConsistencySqlReader sql, ExpiredFormationAuditProperties properties, MeterRegistry metrics,
            ScheduledExecutorService worker, LongSupplier nanoClock) {
        this.sql = sql;
        this.properties = Objects.requireNonNull(properties);
        this.worker = Objects.requireNonNull(worker);
        this.nanoClock = Objects.requireNonNull(nanoClock);
        observation = empty(properties.enabled() ? State.UNKNOWN : State.DISABLED, "not_started");
        Objects.requireNonNull(metrics);
        Gauge.builder("game.data.match.expired.bad.lowerbound", this, audit -> audit.observation().badLowerBound())
                .description("本副本本轮确认的坏形状下界；未知零/过期/停止为NaN，不可求和为全库总数").register(metrics);
        Gauge.builder("game.data.match.expired.scanned", this, audit -> audit.observedCount(false))
                .description("本副本最近一轮检查行数；失效为NaN").register(metrics);
        Gauge.builder("game.data.match.expired.uncertain", this, audit -> audit.observedCount(true))
                .description("本轮缺少深读或发生变化的行数；失效为NaN").register(metrics);
        for (State state : State.values()) {
            Gauge.builder("game.data.match.expired.probe.state", this, audit -> audit.observation().state() == state ? 1 : 0)
                    .tag("state", state.name().toLowerCase(java.util.Locale.ROOT)).register(metrics);
        }
        Gauge.builder("game.data.match.expired.window.finished", this,
                audit -> audit.observation().state() == State.OBSERVED ? (audit.observation().windowFinished() ? 1 : 0) : Double.NaN)
                .description("仅表示游标走完先前捕获上界；多轮/多页不是数据库同一快照").register(metrics);
        Gauge.builder("game.data.match.expired.worker.terminated", this,
                audit -> audit.closed && audit.worker.isTerminated() ? 1 : 0)
                .description("1仅在停止请求后专属工作线程已退出；STOPPED只表示禁止发布新观察").register(metrics);
    }

    public void start() {
        synchronized (lifecycle) {
            if (closed || started || !properties.enabled()) return;
            started = true;
            worker.scheduleWithFixedDelay(this::round, 0, properties.interval().toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    public Observation observation() {
        Observation current = observation;
        if ((current.state() == State.OBSERVED || current.state() == State.UNKNOWN && current.startedNanos() != Long.MIN_VALUE)
                && nanoClock.getAsLong() - current.startedNanos() > properties.staleAfter().toNanos()) {
            return new Observation(State.STALE, current.scanned(), current.confirmedBad(), current.uncertain(),
                    false, "observation_stale", current.startedNanos());
        }
        return current;
    }

    /** 包内可见供确定性测试；生产只由专属单线程调度，无排队堆积或并发轮次。 */
    void round() {
        if (closed || !properties.enabled() || !turn.tryLock()) return;
        long began = nanoClock.getAsLong();
        long scanned = 0, bad = 0, uncertain = 0;
        boolean finished = false;
        String reason = "bounded_observation";
        State state = State.OBSERVED;
        try {
            if (sql == null) throw new ConsistencyBudget.Failure("sql_not_configured");
            var budget = new ConsistencyBudget(Deadline.after(properties.budget().toMillis()), properties.budget().toMillis());
            // 空表也核对 completion 的列与权限，不把无候选等同依赖可用。
            query(SHAPE.schema(), 1, budget);
            if (upper == null) {
                var rows = query(ConsistencySqlPlans.expiredUpper(), 1, budget);
                if (!rows.isEmpty()) upper = ConsistencySqlPlans.key(rows.getFirst());
                else finished = true;
            }
            while (!finished && scanned < properties.maxRows()) {
                int size = (int) Math.min(properties.pageSize(), properties.maxRows() - scanned);
                var page = query(ConsistencySqlPlans.expiredPage(cursor, upper, size), size, budget);
                if (page.isEmpty()) { finished = true; break; }
                for (var row : page) {
                    var next = ConsistencySqlPlans.key(row);
                    if (cursor != null && next.compareTo(cursor) <= 0 || next.compareTo(upper) > 0)
                        throw new ConsistencyBudget.Failure("invalid_cursor");
                    // 无论该行的复核如何，下一轮都从后续行开始，避免一条坏原件长期饿死整页。
                    cursor = next;
                    scanned++;
                    var finding = ConsistencySqlPlans.finding(SHAPE, row);
                    if (finding == null) continue;
                    if (finding.status() == ConsistencyReport.Status.UNKNOWN) { uncertain++; continue; }
                    var confirmation = query(SHAPE.point(next), 1, budget);
                    if (confirmation.isEmpty()) { uncertain++; continue; }
                    var after = confirmation.getFirst();
                    var confirmed = ConsistencySqlPlans.finding(SHAPE, after);
                    if (!ConsistencyService.stable(row, after) || confirmed == null
                            || confirmed.status() == ConsistencyReport.Status.UNKNOWN) uncertain++;
                    else bad++;
                }
                finished = cursor.compareTo(upper) >= 0 || page.size() < size;
            }
            if (finished) { cursor = null; upper = null; }
            if (!finished || uncertain > 0) { state = State.UNKNOWN; reason = uncertain > 0 ? "unproved_rows" : "partial_window"; }
        } catch (ConsistencyBudget.Failure failure) {
            state = State.UNKNOWN;
            reason = fixedReason(failure.getMessage());
        } catch (RuntimeException failure) {
            state = State.UNKNOWN;
            reason = "read_failed";
        } finally {
            synchronized (lifecycle) {
                if (!closed) observation = new Observation(state, scanned, bad, uncertain, finished, reason, began);
            }
            turn.unlock();
        }
        if (bad > 0 && !closed) log.warn("到期成局发现无法恢复的形状，本轮确认下界={}，覆盖未知={}；仅观察不释放票据", bad, state == State.UNKNOWN);
    }

    private List<Map<String, Object>> query(ConsistencySqlPlans.Query query, int size, ConsistencyBudget budget) {
        if (closed) throw new ConsistencyBudget.Failure("stopped");
        budget.check();
        var result = sql.query(query.sql(), query.parameters(), size, budget);
        budget.check();
        if (closed) throw new ConsistencyBudget.Failure("stopped");
        if (result == null || result.size() > size) throw new ConsistencyBudget.Failure("response_limit");
        return result;
    }
    private static String fixedReason(String reason) {
        return switch (reason == null ? "" : reason) {
            case "sql_not_configured", "budget_exhausted", "query_timeout", "query_failed", "schema_unavailable",
                    "namespace_mismatch", "strong_read_unproved", "network_budget_unproved", "invalid_cursor",
                    "row_decode_failed", "response_limit", "stopped" -> reason;
            default -> "read_failed";
        };
    }
    private Observation empty(State state, String reason) { return new Observation(state, 0, 0, 0, false, reason, Long.MIN_VALUE); }
    private double observedCount(boolean uncertain) {
        var current = observation();
        return (current.state() == State.OBSERVED || current.state() == State.UNKNOWN)
                && current.startedNanos() != Long.MIN_VALUE
                ? (uncertain ? current.uncertain() : current.scanned()) : Double.NaN;
    }
    @Override public void close() {
        synchronized (lifecycle) {
            if (closed) return;
            closed = true;
            observation = empty(State.STOPPED, "stopped");
        }
        worker.shutdownNow();
        try {
            if (!worker.awaitTermination(properties.budget().toMillis() + 100, TimeUnit.MILLISECONDS))
                log.warn("到期成局巡检已禁用发布，但工作线程尚未退出；不能据STOPPED认定在途只读已结束");
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
