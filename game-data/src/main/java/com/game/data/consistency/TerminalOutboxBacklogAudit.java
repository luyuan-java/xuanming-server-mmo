package com.game.data.consistency;

import com.game.common.deadline.Deadline;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一条强读 SELECT 观察三类终局积压，读完整且预算仍有效才一次发布。
 * 只在该 statement 的读快照内一致；不授予投递、释放或维护排空的业务权利。
 */
public final class TerminalOutboxBacklogAudit implements AutoCloseable {
    public enum Kind { RESULT, FIRST, WAITING }
    public enum State { UNKNOWN, OBSERVED, STALE, STOPPED, DISABLED }
    public record Reading(long rows, double oldestAgeSeconds) { }
    public record Observation(State state, Map<Kind, Reading> readings, String reason, long startedNanos) {
        public Observation { readings = Map.copyOf(readings); }
        public double rows(Kind kind) { return state == State.OBSERVED ? readings.get(kind).rows() : Double.NaN; }
        public double oldestAgeSeconds(Kind kind) {
            return state == State.OBSERVED ? readings.get(kind).oldestAgeSeconds() : Double.NaN;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(TerminalOutboxBacklogAudit.class);
    private static final Set<String> COLUMNS = Set.of("kind", "pending_rows", "oldest_ms", "db_now");
    private final ConsistencySqlReader sql;
    private final TerminalOutboxBacklogProperties properties;
    private final ScheduledExecutorService worker;
    private final LongSupplier nanoClock;
    private final ReentrantLock turn = new ReentrantLock();
    private final Object lifecycle = new Object();
    private volatile Observation observation;
    private volatile boolean closed;
    private boolean started;

    public TerminalOutboxBacklogAudit(ConsistencySqlReader sql, TerminalOutboxBacklogProperties properties,
            MeterRegistry metrics) {
        this(sql, properties, metrics, Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("terminal-outbox-backlog-audit").daemon(true).factory()), System::nanoTime);
    }

    TerminalOutboxBacklogAudit(ConsistencySqlReader sql, TerminalOutboxBacklogProperties properties,
            MeterRegistry metrics, ScheduledExecutorService worker, LongSupplier nanoClock) {
        this.sql = sql;
        this.properties = Objects.requireNonNull(properties);
        this.worker = Objects.requireNonNull(worker);
        this.nanoClock = Objects.requireNonNull(nanoClock);
        this.observation = empty(properties.enabled() ? State.UNKNOWN : State.DISABLED, "not_started", Long.MIN_VALUE);
        Objects.requireNonNull(metrics);
        for (Kind kind : Kind.values()) {
            String label = kind.name().toLowerCase(Locale.ROOT);
            Gauge.builder("game.data.match.outbox.backlog.rows", this, audit -> audit.observation().rows(kind))
                    .tag("kind", label).description("同一强读快照的待投行数；未知/过期/停止为NaN，副本间用max聚合")
                    .register(metrics);
            Gauge.builder("game.data.match.outbox.oldest.pending.age.seconds", this,
                            audit -> audit.observation().oldestAgeSeconds(kind))
                    .tag("kind", label).description("最老待投行自SQL归档时间起的秒数；空类为0，失效为NaN")
                    .register(metrics);
        }
        for (State state : State.values()) {
            Gauge.builder("game.data.match.outbox.probe.state", this, audit -> audit.observation().state() == state ? 1 : 0)
                    .tag("state", state.name().toLowerCase(Locale.ROOT)).register(metrics);
        }
        Gauge.builder("game.data.match.outbox.worker.terminated", this,
                        audit -> audit.closed && audit.worker.isTerminated() ? 1 : 0)
                .description("停止后专属线程确已退出；STOPPED本身只保证不再发布新读数").register(metrics);
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
        if ((current.state() == State.OBSERVED || current.state() == State.UNKNOWN)
                && current.startedNanos() != Long.MIN_VALUE
                && nanoClock.getAsLong() - current.startedNanos() > properties.staleAfter().toNanos()) {
            return empty(State.STALE, "observation_stale", current.startedNanos());
        }
        return current;
    }

    /** 包内入口便于确定性测试；正式调度一次一轮，不堆积并发查询。 */
    void round() {
        if (closed || !properties.enabled() || !turn.tryLock()) return;
        long began = nanoClock.getAsLong();
        Observation next = empty(State.UNKNOWN, "read_failed", began);
        try {
            if (closed) throw new ConsistencyBudget.Failure("stopped");
            if (sql == null) throw new ConsistencyBudget.Failure("sql_not_configured");
            var budget = new ConsistencyBudget(Deadline.after(properties.budget().toMillis()), properties.budget().toMillis());
            budget.check();
            var rows = sql.query(TerminalOutboxBacklogQueries.snapshot(), List.of(), 3, budget);
            budget.check();
            var readings = decode(rows);
            budget.check();
            next = new Observation(State.OBSERVED, readings, "snapshot_observed", began);
        } catch (ConsistencyBudget.Failure failure) {
            next = empty(State.UNKNOWN, fixedReason(failure.getMessage()), began);
        } catch (RuntimeException failure) {
            next = empty(State.UNKNOWN, "read_failed", began);
        } finally {
            synchronized (lifecycle) {
                // close不等待turn：即使驱动忽略中断并晚返，也不能把STOPPED变回OBSERVED。
                if (!closed) observation = next;
            }
            turn.unlock();
        }
    }

    private static Map<Kind, Reading> decode(List<Map<String, Object>> rows) {
        if (rows == null || rows.size() != Kind.values().length) throw invalid();
        var readings = new EnumMap<Kind, Reading>(Kind.class);
        Long snapshotMillis = null;
        for (var row : rows) {
            if (row == null || !row.keySet().equals(COLUMNS)) throw invalid();
            Kind kind = switch (row.get("kind") instanceof String name ? name : "") {
                case "result" -> Kind.RESULT;
                case "first" -> Kind.FIRST;
                case "waiting" -> Kind.WAITING;
                default -> throw invalid();
            };
            long count = number(row.get("pending_rows"));
            long now = number(row.get("db_now"));
            if (now == 0 || snapshotMillis != null && now != snapshotMillis) throw invalid();
            snapshotMillis = now;
            double age = 0;
            if (count == 0) {
                if (row.get("oldest_ms") != null) throw invalid();
            } else {
                long oldest = number(row.get("oldest_ms"));
                // 新协议持久来源的归档时间必须有效；不能把缺时间解释成健康的零年龄。
                if (oldest == 0) throw invalid();
                if (now > oldest) age = (now - oldest) / 1_000.0;
            }
            if (readings.putIfAbsent(kind, new Reading(count, age)) != null) throw invalid();
        }
        if (readings.size() != Kind.values().length) throw invalid();
        return readings;
    }

    private static long number(Object value) {
        if (!(value instanceof Number) && !(value instanceof String)) throw invalid();
        try {
            long number = new BigDecimal(value.toString()).longValueExact();
            if (number < 0) throw invalid();
            return number;
        } catch (NumberFormatException | ArithmeticException failure) { throw invalid(); }
    }

    private static ConsistencyBudget.Failure invalid() { return new ConsistencyBudget.Failure("row_decode_failed"); }
    private static String fixedReason(String reason) {
        return switch (reason == null ? "" : reason) {
            case "sql_not_configured", "budget_exhausted", "query_timeout", "query_failed", "schema_unavailable",
                    "namespace_mismatch", "strong_read_unproved", "network_budget_unproved", "row_decode_failed",
                    "response_limit", "stopped" -> reason;
            default -> "read_failed";
        };
    }
    private static Observation empty(State state, String reason, long began) {
        return new Observation(state, Map.of(), reason, began);
    }

    @Override public void close() {
        synchronized (lifecycle) {
            if (closed) return;
            closed = true;
            observation = empty(State.STOPPED, "stopped", Long.MIN_VALUE);
        }
        worker.shutdownNow();
        try {
            if (!worker.awaitTermination(properties.budget().toMillis() + 100, TimeUnit.MILLISECONDS))
                log.warn("终局积压采样已停止发布，但专属线程尚未退出，不能认定在途读取已结束");
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
