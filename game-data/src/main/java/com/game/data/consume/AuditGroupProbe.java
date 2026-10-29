package com.game.data.consume;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * 专用后台线程，不与 KafkaConsumer、SQL 落库或 HTTP 请求共用线程。
 * 每个目标全程一个预算；停止打断 Future 等待并立即关闭本探针的 Admin，不动消费者与其他进程。
 */
public final class AuditGroupProbe implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(AuditGroupProbe.class);
    private final List<AuditOffsetSnapshot.Target> targets;
    private final Map<AuditOffsetSnapshot.ConsumerName, AuditProbeState> states;
    private final AuditProbeProperties properties;
    private final Supplier<GroupOffsetReader> readers;
    private final Clock clock;
    private final LongSupplier nanos;
    private final AuditProbeMetrics metrics;
    private volatile boolean running;
    private Thread worker;
    private GroupOffsetReader active;

    public AuditGroupProbe(List<AuditOffsetSnapshot.Target> targets, AuditProbeProperties properties,
                           Supplier<GroupOffsetReader> readers, Clock clock, MeterRegistry registry) {
        this(targets, properties, readers, clock, registry, System::nanoTime);
    }

    AuditGroupProbe(List<AuditOffsetSnapshot.Target> targets, AuditProbeProperties properties,
                    Supplier<GroupOffsetReader> readers, Clock clock, MeterRegistry registry, LongSupplier nanos) {
        this.targets = List.copyOf(targets);
        this.properties = Objects.requireNonNull(properties, "properties");
        this.readers = Objects.requireNonNull(readers, "readers");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.nanos = Objects.requireNonNull(nanos, "nanos");
        Map<AuditOffsetSnapshot.ConsumerName, AuditProbeState> entries = new EnumMap<>(AuditOffsetSnapshot.ConsumerName.class);
        for (var target : targets) {
            if (entries.put(target.consumer(), new AuditProbeState(target, properties.staleAfter(), nanos)) != null) {
                throw new IllegalArgumentException("同一消费目标重复");
            }
        }
        if (entries.size() != AuditOffsetSnapshot.ConsumerName.values().length) {
            throw new IllegalArgumentException("必须完整登记两条审计消费目标");
        }
        states = Map.copyOf(entries);
        metrics = new AuditProbeMetrics(registry, states);
    }

    @Override public synchronized void start() {
        if (running) return;
        if (worker != null && worker.isAlive()) throw new IllegalStateException("旧探针尚未退出，不能重叠启动");
        states.values().forEach(AuditProbeState::started);
        running = true;
        worker = new Thread(this::run, "data-audit-group-probe");
        worker.setDaemon(true);
        worker.start();
    }

    private void run() {
        try {
            while (running) {
                for (var target : targets) {
                    if (!running) return;
                    probe(target);
                }
                Thread.sleep(properties.interval().toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (this) {
                running = false;
                states.values().forEach(AuditProbeState::stopped);
            }
        }
    }

    private void probe(AuditOffsetSnapshot.Target target) {
        AuditProbeBudget budget = new AuditProbeBudget(properties.timeout(), nanos);
        // 创建客户端也计入预算。DNS 若阻塞在驱动构造里，停止不无限等待；旧观测仍会由单调时钟转 STALE。
        GroupOffsetReader reader = null;
        try {
            reader = readers.get();
            synchronized (this) {
                if (!running) return;
                active = reader;
            }
            budget.remainingNanos();
            AuditOffsetSnapshot sample = reader.read(target, budget);
            budget.remainingNanos();
            synchronized (this) {
                if (!running) return;
                boolean accepted = states.get(target.consumer()).succeeded(sample, budget.startedNanos(), clock.millis());
                metrics.attempted(target.consumer(), accepted);
                if (!accepted) {
                    log.warn("审计探针 consumer={} reason=TOPIC_CHANGED", target.consumer().label());
                } else if (sample.retentionGap() > 0) {
                    // 原件只含 broker offset；没有玩家、载荷和秘密。每次采样可能重复，不累计为丢失条数。
                    log.error("审计恢复位点出现保留缺口 consumer={} generation={} topicId={} offsets={}；"
                                    + "需核对在途落库，缺口消失不表示恢复", target.consumer().label(), target.generation(),
                            sample.topicId(), sample.partitions());
                }
            }
        } catch (RuntimeException failure) {
            AuditProbeFailure.Reason reason = failure instanceof AuditProbeFailure known
                    ? known.reason() : AuditProbeFailure.Reason.BROKER;
            synchronized (this) {
                if (running) {
                    states.get(target.consumer()).failed(reason, clock.millis());
                    metrics.attempted(target.consumer(), false);
                    log.warn("审计探针 consumer={} reason={}", target.consumer().label(), reason);
                }
            }
        } finally {
            synchronized (this) { if (active == reader) active = null; }
            closeQuietly(reader);
        }
    }

    /** 只返回内存中的原件，不向 broker 发请求；HTTP 线程不会等待外部服务。 */
    public List<AuditProbeState.View> views() {
        return targets.stream().map(target -> states.get(target.consumer()).view()).toList();
    }

    @Override public void stop() {
        GroupOffsetReader closing;
        Thread joining;
        synchronized (this) {
            running = false;
            states.values().forEach(AuditProbeState::stopped);
            closing = active;
            active = null;
            joining = worker;
        }
        if (joining != null) joining.interrupt();
        closeQuietly(closing);
        if (joining != null && joining != Thread.currentThread()) {
            try { joining.join(2_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (joining.isAlive()) log.warn("审计探针尚在退出；状态保持 STOPPED，旧线程退出前禁止重启");
        }
    }

    private static void closeQuietly(GroupOffsetReader reader) {
        if (reader == null) return;
        try { reader.close(); } catch (RuntimeException e) { log.warn("审计探针关闭客户端失败"); }
    }

    @Override public boolean isRunning() { return running; }
}
