package com.game.inbox.metrics;

import com.game.inbox.notify.NotifyResult;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * game-inbox 的低基数指标（Micrometer，经 actuator 以 Prometheus 格式导出，名字里的点变成下划线、计数器带 {@code _total}）。
 * 指标名与 label 取值对齐 mmorpg {@code go/player_inbox/internal/metrics}（前缀 {@code player_inbox_} 换成本仓库的 {@code game_inbox_}；
 * mmorpg 设计 §9.4）。label 只用有限枚举，<b>不用 player_id</b>。线程安全。
 *
 * <ul>
 *   <li>计数器（每个副本各报一份，告警一律 {@code sum()}）：enqueue{type,result}、fetch{domain,fence,path}、ack{domain,result}、
 *       void{domain,reason,path}、notify{result}、cursor.ahead、fence.regressed、epoch.ahead、reconcile.truncated、reconcile.rounds{result}、
 *       reconcile.ledger.reads{result}、sweep.deleted；Java 增项 consumer.gate{result}（写前门禁的结论）。启动时按封闭集合预置零值的只有
 *       notify、reconcile.rounds、reconcile.ledger.reads、consumer.gate（同 mmorpg）。</li>
 *   <li>扫描型 gauge（只由领导者产出，卸任时归零；告警与看板一律 {@code max()}）：open.items、oldest.open.age.seconds、delivered.unacked、
 *       delivered.unacked.oldest.seconds、delivered.unacked.stale、max.delivery.count、notify.backlog、reconcile.inconsistent.rows。</li>
 *   <li>分布：fetch.scanned.rows（有限查询实际读回的候选数，最多max_items+1；不代表存储引擎物理扫描数）。</li>
 * </ul>
 */
public final class InboxMetrics {

    static final String ENQUEUE = "game.inbox.enqueue";
    static final String FETCH = "game.inbox.fetch";
    static final String ACK = "game.inbox.ack";
    static final String VOID = "game.inbox.void";
    static final String NOTIFY = "game.inbox.notify";
    static final String CURSOR_AHEAD = "game.inbox.cursor.ahead";
    static final String FENCE_REGRESSED = "game.inbox.fence.regressed";
    static final String EPOCH_AHEAD = "game.inbox.epoch.ahead";
    static final String RECONCILE_TRUNCATED = "game.inbox.reconcile.truncated";
    static final String RECONCILE_ROUNDS = "game.inbox.reconcile.rounds";
    static final String RECONCILE_LEDGER_READS = "game.inbox.reconcile.ledger.reads";
    static final String SWEEP_DELETED = "game.inbox.sweep.deleted";
    static final String CONSUMER_GATE = "game.inbox.consumer.gate";
    static final String FETCH_SCANNED_ROWS = "game.inbox.fetch.scanned.rows";

    /** 写前门禁的结论（{@code game.inbox.consumer.gate{result}}）。 */
    public static final List<String> CONSUMER_GATE_RESULTS = List.of("open", "closed", "error");
    /** 对账轮次结果。 */
    public static final List<String> RECONCILE_ROUND_RESULTS = List.of("ok", "error", "timeout");
    /** 对账读已落盘账本的结果。 */
    public static final List<String> LEDGER_READ_RESULTS = List.of("found", "absent", "error", "invalid");

    /** 一轮扫描型 gauge 的读数（{@link #setScanGauges}）。 */
    public record ScanGauges(long openItems, double oldestOpenAgeSeconds, long deliveredUnacked, double deliveredUnackedOldestSeconds,
                             long deliveredUnackedStale, long maxDeliveryCount, long notifyBacklog) {

        public static final ScanGauges ZERO = new ScanGauges(0, 0, 0, 0, 0, 0, 0);
    }

    private final MeterRegistry registry;
    private final AtomicReference<ScanGauges> scan = new AtomicReference<>(ScanGauges.ZERO);
    private final AtomicLong reconcileInconsistent = new AtomicLong();
    private final DistributionSummary scannedRows;

    public InboxMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        for (NotifyResult result : NotifyResult.values()) {
            registry.counter(NOTIFY, "result", result.label());
        }
        RECONCILE_ROUND_RESULTS.forEach(r -> registry.counter(RECONCILE_ROUNDS, "result", r));
        LEDGER_READ_RESULTS.forEach(r -> registry.counter(RECONCILE_LEDGER_READS, "result", r));
        CONSUMER_GATE_RESULTS.forEach(r -> registry.counter(CONSUMER_GATE, "result", r));
        registry.counter(CURSOR_AHEAD);
        registry.counter(FENCE_REGRESSED);
        registry.counter(EPOCH_AHEAD);
        registry.counter(RECONCILE_TRUNCATED);
        registry.counter(SWEEP_DELETED);
        gauge("game.inbox.open.items", () -> scan.get().openItems());
        gauge("game.inbox.oldest.open.age.seconds", () -> scan.get().oldestOpenAgeSeconds());
        gauge("game.inbox.delivered.unacked", () -> scan.get().deliveredUnacked());
        gauge("game.inbox.delivered.unacked.oldest.seconds", () -> scan.get().deliveredUnackedOldestSeconds());
        gauge("game.inbox.delivered.unacked.stale", () -> scan.get().deliveredUnackedStale());
        gauge("game.inbox.max.delivery.count", () -> scan.get().maxDeliveryCount());
        gauge("game.inbox.notify.backlog", () -> scan.get().notifyBacklog());
        gauge("game.inbox.reconcile.inconsistent.rows", reconcileInconsistent::get);
        scannedRows = DistributionSummary.builder(FETCH_SCANNED_ROWS).serviceLevelObjectives(1, 4, 16, 64, 256, 1024, 4096)
                .register(registry);
    }

    /** 不出指标（单测）。 */
    public static InboxMetrics noop() {
        return new InboxMetrics(new SimpleMeterRegistry());
    }

    private void gauge(String name, java.util.function.Supplier<Number> value) {
        Gauge.builder(name, () -> value.get().doubleValue()).register(registry);
    }

    public void enqueue(String type, String result) {
        registry.counter(ENQUEUE, "type", type, "result", result).increment();
    }

    public void fetch(String domain, String fence, String path) {
        registry.counter(FETCH, "domain", domain, "fence", fence, "path", path).increment();
    }

    public void ack(String domain, String result) {
        registry.counter(ACK, "domain", domain, "result", result).increment();
    }

    public void voided(String domain, String reason, String path) {
        registry.counter(VOID, "domain", domain, "reason", reason, "path", path).increment();
    }

    public void notifyResult(NotifyResult result) {
        registry.counter(NOTIFY, "result", result.label()).increment();
    }

    public void cursorAhead() {
        registry.counter(CURSOR_AHEAD).increment();
    }

    public void fenceRegressed() {
        registry.counter(FENCE_REGRESSED).increment();
    }

    public void epochAhead() {
        registry.counter(EPOCH_AHEAD).increment();
    }

    public void reconcileTruncated() {
        registry.counter(RECONCILE_TRUNCATED).increment();
    }

    public void reconcileRound(String result) {
        registry.counter(RECONCILE_ROUNDS, "result", result).increment();
    }

    public void reconcileLedgerRead(String result) {
        registry.counter(RECONCILE_LEDGER_READS, "result", result).increment();
    }

    public void sweepDeleted(int rows) {
        if (rows > 0) {
            registry.counter(SWEEP_DELETED).increment(rows);
        }
    }

    public void consumerGate(String result) {
        registry.counter(CONSUMER_GATE, "result", result).increment();
    }

    public void fetchScannedRows(long rows) {
        scannedRows.record(rows);
    }

    /** 领导者刷新扫描型 gauge。 */
    public void setScanGauges(ScanGauges gauges) {
        scan.set(Objects.requireNonNull(gauges, "gauges"));
    }

    /** 对账的当前不一致数（本任期各分片最近读数之和）。 */
    public void setReconcileInconsistent(long rows) {
        reconcileInconsistent.set(rows);
    }

    /** 卸任时归零：避免 max() 聚合里留着卸任副本的旧值。 */
    public void resetLeaderGauges() {
        scan.set(ScanGauges.ZERO);
        reconcileInconsistent.set(0);
    }

    /** 当前扫描型 gauge 读数（测试用）。 */
    public ScanGauges scanGauges() {
        return scan.get();
    }

    public long reconcileInconsistent() {
        return reconcileInconsistent.get();
    }

    public MeterRegistry registry() {
        return registry;
    }
}
