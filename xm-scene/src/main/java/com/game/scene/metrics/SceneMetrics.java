package com.game.scene.metrics;

import com.game.api.proto.NodeLinkFrame;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

/**
 * scene 的低基数指标（Micrometer，经 actuator 以 Prometheus 格式导出，见 architecture.md §11）。
 * 指标名与标签只在这里定义，业务代码只调语义方法。
 *
 * <p><b>标签基数约束</b>（AGENTS.md §5）：不以 player_id / session_id / 实体号 / 场景实例号 / gate 节点号 / 链路号作标签。
 * 场景维度只用场景配置号（{@code scene_config}）：场景只在启动时按配置表的主世界场景建出来，之后不再新增，所以它有界；
 * 链路帧类型取 {@code NodeLinkFrame} 的 oneof（有界）；其余标签都是本类里的枚举。
 *
 * <p><b>线程</b>：全部计量器线程安全，可从任意线程调——场景逻辑线程（人数、移动、视野、帧与广播耗时、逻辑任务）、
 * 链路 I/O 线程（收到的帧、背压暂停）、存储线程（写回结局）。Gauge 由抓取线程读，绑定的回调必须线程安全、不阻塞，
 * 不得读场景状态（场景状态只归逻辑线程），所以在线人数由逻辑线程推送到这里、而不是由抓取线程去数。
 * 枚举维度的计量器在构造时建好放进只读表，热路径（每帧、每条链路帧）不拼标签。
 */
public final class SceneMetrics {

    static final String PLAYERS = "xm.scene.players";
    static final String LOGIC_PENDING = "xm.scene.logic.pending.tasks";
    static final String LOGIC_TASK_WAIT = "xm.scene.logic.task.wait";
    static final String LOGIC_TASK_RUN = "xm.scene.logic.task.run";
    static final String TICK = "xm.scene.tick";
    static final String BROADCAST = "xm.scene.broadcast";
    static final String MOVES = "xm.scene.moves";
    static final String PERIODIC_SAVES = "xm.scene.periodic.saves";
    static final String AOI_CHANGES = "xm.scene.aoi.changes";
    static final String STORAGE_WRITES = "xm.scene.storage.writes";
    static final String GATE_LINKS = "xm.scene.gate.links";
    static final String LINK_FRAMES = "xm.scene.link.frames";
    static final String LINK_DROPPED = "xm.scene.link.dropped";
    static final String LINK_PAUSES = "xm.scene.link.backpressure.pauses";
    /** 存储线程池的 Micrometer 标准线程池指标（{@code executor_*}）的 {@code name} 标签。 */
    static final String STORAGE_EXECUTOR_NAME = "scene-storage";
    static final String AUDIT_RECORDS = "xm.scene.audit.records";
    static final String AUDIT_EXECUTOR_NAME = "scene-audit";

    /**
     * 逻辑线程内耗时（帧、广播、逻辑任务排队与执行）的桶边界：固定 12 个，覆盖 0.1ms～1s，50ms 是一帧的预算
     * （20 FPS）；超过 1s 落在 +Inf 桶，最大值另见 {@code _max}。不开百分位直方图。
     */
    static final Duration[] LOGIC_BUCKETS = {
            Duration.ofNanos(100_000), Duration.ofNanos(250_000), Duration.ofNanos(500_000), Duration.ofMillis(1),
            Duration.ofNanos(2_500_000), Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofMillis(25),
            Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofSeconds(1)};

    /** 存储写（MySQL，含瞬时故障重试，预算 5s）的桶边界：与 gate / login 同一套，5ms～10s 共 11 个。 */
    static final Duration[] STORAGE_BUCKETS = {
            Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofMillis(25), Duration.ofMillis(50),
            Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1),
            Duration.ofMillis(2500), Duration.ofSeconds(5), Duration.ofSeconds(10)};

    /** 一条移动上行（134 / 132 / 131）的裁决结果（{@code xm.scene.moves{result}}），每条恰好计一次。 */
    public enum MoveResult {
        /** 上报位置原样接受。 */
        ACCEPTED,
        /** 位移超出校验额度被截断，但与上报位置的水平偏差不超过 0.5 m，不回 137。 */
        CLAMPED,
        /** 被截断且水平偏差超过 0.5 m，给本人回了 137 纠偏。 */
        CORRECTED,
        /** 位置 / 朝向 / 速度含非有限值，或位置超出世界范围（±1e7 m），整条丢弃。 */
        INVALID
    }

    /** 周期存盘对一个到期玩家的处理（{@code xm.scene.periodic.saves{result}}），每人每次到期恰好计一次。 */
    public enum PeriodicSave {
        /** 与上次落库的快照不同，提交了在线存盘。 */
        WRITTEN,
        /** 与上次落库的快照相同，跳过（脏比对快路径）。 */
        UNCHANGED,
        /** 上一次在线存盘还没回来，本次跳过（下个周期再比）。 */
        IN_FLIGHT,
        /** 存储线程池积压（续约 / 最终写回优先），推到下个周期。 */
        DEFERRED
    }

    /** 帧内的广播阶段（{@code xm.scene.broadcast{kind}}）。 */
    public enum BroadcastKind {
        /** 视野变化：每个观察者一条 47 / 64（每帧一次，全部场景合计）。 */
        VIEW_CHANGES,
        /** 属性同步：66（偶数帧一次，全部场景合计）。 */
        ATTRIBUTE_SYNC
    }

    /** 存储写的种类（{@code xm.scene.storage.writes{op}}）。 */
    public enum StorageOp {
        /** 最终写回并释放归属（离场、断线、被接管、停服）。 */
        SAVE,
        /** 只释放归属（没进成的进场）。 */
        RELEASE,
        /** 在线存盘（周期存盘，不释放归属）。 */
        PROGRESS
    }

    /** 存储写的结局（{@code xm.scene.storage.writes{result}}），每个写任务恰好计一次。 */
    public enum WriteResult {
        /** 已落库并释放归属。 */
        RELEASED,
        /** 已落库（在线存盘，不释放归属）。 */
        SAVED,
        /** 被 owner_epoch 围栏拒绝（归属已被新的进场取代、已释放或玩家已不存在）：不是故障，什么也没写。 */
        FENCED,
        /** 重试用尽、非瞬时故障或重试等待被中断：写丢失，已记 ERROR 待人工修复。 */
        FAILED,
        /** 存储线程池拒绝（积压满或已关闭）：写丢失，已记 ERROR；没有执行，耗时记 0。 */
        REJECTED
    }

    /** 审计记录的种类（{@code xm.scene.audit.records{kind}}）。 */
    public enum AuditKind {
        /** 资产流水。 */
        TRANSACTION,
        /** 玩家快照。 */
        SNAPSHOT
    }

    /**
     * 审计记录的结局（{@code xm.scene.audit.records{result}}），每条恰好计一次。除 ACKED 外都已把记录完整写进
     * 兜底日志 {@code xm.audit.fallback}（快照只记元数据）。
     */
    public enum AuditResult {
        /** Kafka 已确认。 */
        ACKED,
        /**
         * 投递失败：Kafka 回调报错——包括 send 时等元数据 / 缓冲超过 max.block.ms（KafkaProducer 对这类错误不抛、直接回调）、
         * 投递超时、broker 拒绝、停服时没发完。Kafka 不可达时主要计在这里。
         */
        DELIVERY_FAILED,
        /** send 调用本身抛异常：生产者进入致命状态 / 已关闭、线程被中断等（致命状态会丢弃生产者，下次核对重建）。 */
        SEND_ERROR,
        /** 审计线程队列满。 */
        QUEUE_FULL,
        /** 发不出全局唯一号（号段租约失效或时钟回拨）。 */
        NO_ID,
        /** topic 还没核对通过（broker 不可达或分区契约不符）：不发，免得自动建出错的分区数。 */
        UNVERIFIED,
        /** 超过大小上限（快照）。 */
        OVERSIZE,
        /** 停服时还排在审计线程队列里、来不及发。 */
        SHUTDOWN_DROPPED
    }

    /** 没发出去的 scene → gate 链路帧（{@code xm.scene.link.dropped{reason}}）。 */
    public enum LinkDrop {
        /** 链路已注销或已断开（其上会话随链路一起失效）。 */
        LINK_GONE,
        /** 出站缓冲越过高水位（gate 读不动）：丢掉这一帧并断开链路。 */
        WRITE_BUFFER_FULL
    }

    private final MeterRegistry registry;
    private final Clock clock;
    private final Timer logicTaskWait;
    private final Timer logicTaskRun;
    private final Timer tick;
    private final Map<BroadcastKind, Timer> broadcasts;
    private final Map<MoveResult, Counter> moves;
    private final Map<PeriodicSave, Counter> periodicSaves;
    private final Counter aoiEntered;
    private final Counter aoiLeft;
    private final Map<StorageOp, Map<WriteResult, Timer>> storageWrites;
    private final Map<NodeLinkFrame.BodyCase, Counter> framesIn;
    private final Map<NodeLinkFrame.BodyCase, Counter> framesOut;
    private final Map<LinkDrop, Counter> linkDrops;
    private final Counter linkPauses;
    private final Map<AuditKind, Map<AuditResult, Counter>> auditRecords;
    /** 场景配置号 → 在线人数（逻辑线程写，抓取线程读）。首次出现时注册 Gauge。 */
    private final ConcurrentHashMap<Integer, AtomicInteger> scenePlayers = new ConcurrentHashMap<>();

    public SceneMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.clock = registry.config().clock();
        this.logicTaskWait = logicTimer(LOGIC_TASK_WAIT, "场景逻辑线程任务的排队等待（从投递到开始执行）");
        this.logicTaskRun = logicTimer(LOGIC_TASK_RUN, "场景逻辑线程任务的执行耗时");
        this.tick = logicTimer(TICK, "一帧的耗时（外推 + 视野刷新 + 广播；预算 50ms）");
        this.broadcasts = new EnumMap<>(BroadcastKind.class);
        for (BroadcastKind kind : BroadcastKind.values()) {
            broadcasts.put(kind, Timer.builder(BROADCAST)
                    .description("帧内广播阶段的耗时（组包、序列化、交给链路）")
                    .tag("kind", tagValue(kind))
                    .serviceLevelObjectives(LOGIC_BUCKETS)
                    .register(registry));
        }
        this.moves = counters(MoveResult.class, MOVES, "result", "移动上行的裁决结果");
        this.periodicSaves = counters(PeriodicSave.class, PERIODIC_SAVES, "result", "周期存盘对到期玩家的处理（写 / 未变跳过 / 在途跳过 / 存储积压推迟）");
        this.aoiEntered = aoiCounter("enter");
        this.aoiLeft = aoiCounter("leave");
        this.storageWrites = new EnumMap<>(StorageOp.class);
        for (StorageOp op : StorageOp.values()) {
            EnumMap<WriteResult, Timer> byResult = new EnumMap<>(WriteResult.class);
            for (WriteResult result : WriteResult.values()) {
                byResult.put(result, Timer.builder(STORAGE_WRITES)
                        .description("玩家数据写（写回并释放 / 只释放 / 在线存盘）的结局与耗时（含瞬时故障重试）")
                        .tag("op", tagValue(op))
                        .tag("result", tagValue(result))
                        .serviceLevelObjectives(STORAGE_BUCKETS)
                        .register(registry));
            }
            storageWrites.put(op, byResult);
        }
        this.framesIn = frameCounters("in");
        this.framesOut = frameCounters("out");
        this.linkDrops = counters(LinkDrop.class, LINK_DROPPED, "reason", "没发出去的 scene → gate 链路帧");
        this.linkPauses = Counter.builder(LINK_PAUSES)
                .description("逻辑线程积压到上限、暂停读取 gate 链路的次数（背压）")
                .register(registry);
        this.auditRecords = new EnumMap<>(AuditKind.class);
        for (AuditKind kind : AuditKind.values()) {
            EnumMap<AuditResult, Counter> byResult = new EnumMap<>(AuditResult.class);
            for (AuditResult result : AuditResult.values()) {
                byResult.put(result, Counter.builder(AUDIT_RECORDS)
                        .description("审计记录（资产流水 / 玩家快照）发往 Kafka 的结局；非 acked 的已写兜底日志")
                        .tag("kind", tagValue(kind))
                        .tag("result", tagValue(result))
                        .register(registry));
            }
            auditRecords.put(kind, byResult);
        }
    }

    /** 不导出任何指标的实例（测试 / 不关心指标的装配用）：没有子注册表的 {@link CompositeMeterRegistry} 上计量器都是空操作。 */
    public static SceneMetrics noop() {
        return new SceneMetrics(new CompositeMeterRegistry());
    }

    // ================================================================ 状态量（启动装配时绑定一次）

    /** 逻辑线程待执行的任务数（{@code xm.scene.logic.pending.tasks}）。{@code pending} 在抓取线程上调用，必须线程安全、不阻塞。 */
    public void bindLogicQueue(IntSupplier pending) {
        Gauge.builder(LOGIC_PENDING, () -> pending.getAsInt())
                .description("场景逻辑线程待执行的任务数（链路帧、存储回调、归属事件；不含定时的帧任务）")
                .register(registry);
    }

    /** 接入本节点的 gate 链路连接数（含握手中，{@code xm.scene.gate.links}）。同上，必须线程安全、不阻塞。 */
    public void bindGateLinkCount(IntSupplier links) {
        Gauge.builder(GATE_LINKS, () -> links.getAsInt())
                .description("接入本节点的 gate 链路连接数（含握手中）")
                .register(registry);
    }

    /**
     * 存储线程池的 Micrometer 标准线程池指标（{@code executor_queued_tasks} / {@code executor_active_threads} /
     * {@code executor_queue_remaining_tasks} / {@code executor_completed_tasks_total} …，标签 {@code name=scene-storage}）。
     * 只绑定只读状态，不包装执行器（拒绝语义不变）。
     */
    public void bindStorageExecutor(ExecutorService executor) {
        new ExecutorServiceMetrics(executor, STORAGE_EXECUTOR_NAME, Tags.empty()).bindTo(registry);
    }

    /** 审计线程池（{@code executor.*{name="scene-audit"}}）：排队长度即发往 Kafka 的积压。 */
    public void bindAuditExecutor(ExecutorService executor) {
        new ExecutorServiceMetrics(executor, AUDIT_EXECUTOR_NAME, Tags.empty()).bindTo(registry);
    }

    /**
     * 某场景配置下的在线人数（{@code xm.scene.players{scene_config}}，同配置的各条频道合计）。由逻辑线程在人数变化后
     * 推送<b>绝对值</b>（不是增量，漏推一次不会累积误差）；某配置第一次推送时注册它的 Gauge。
     */
    public void scenePlayers(int sceneConfigId, int players) {
        AtomicInteger value = scenePlayers.get(sceneConfigId);
        if (value == null) {
            value = scenePlayers.computeIfAbsent(sceneConfigId, id -> {
                AtomicInteger holder = new AtomicInteger();
                Gauge.builder(PLAYERS, holder, AtomicInteger::get)
                        .description("场景配置下的在线玩家数（同配置各频道合计）")
                        .tag("scene_config", Integer.toString(id))
                        .register(registry);
                return holder;
            });
        }
        value.set(players);
    }

    // ================================================================ 逻辑线程

    /**
     * 给投递到逻辑线程的任务计时：投递时刻（调用本方法时）起算排队等待（{@code xm.scene.logic.task.wait}），
     * 开始执行起算执行耗时（{@code xm.scene.logic.task.run}，任务抛异常也记）。用注册表的单调时钟。
     */
    public Runnable timeLogicTask(Runnable task) {
        long queued = clock.monotonicTime();
        return () -> {
            long started = clock.monotonicTime();
            logicTaskWait.record(started - queued, TimeUnit.NANOSECONDS);
            try {
                task.run();
            } finally {
                logicTaskRun.record(clock.monotonicTime() - started, TimeUnit.NANOSECONDS);
            }
        };
    }

    /** 一帧跑完（{@code xm.scene.tick}）。 */
    public void tick(long elapsedNanos) {
        tick.record(Math.max(0, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    /** 一个帧内广播阶段跑完（{@code xm.scene.broadcast{kind}}），每次执行该阶段记一次。 */
    public void broadcast(BroadcastKind kind, long elapsedNanos) {
        broadcasts.get(kind).record(Math.max(0, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    public void move(MoveResult result) {
        moves.get(result).increment();
    }

    /**
     * 观察者被告知某实体进入视野的次数（{@code xm.scene.aoi.changes{change=enter}}）：进场时给进场者的 47 条目、
     * 给看得见进场者的人的 21，以及帧内 47 的条目，一对（观察者, 目标）计一次。
     */
    public void aoiEntered(int pairs) {
        if (pairs > 0) {
            aoiEntered.increment(pairs);
        }
    }

    /**
     * 观察者被告知某实体离开视野的次数（{@code xm.scene.aoi.changes{change=leave}}）：离场 / 换场景时的 51、帧内 64 的条目，
     * 一对计一次。离场者自己的兴趣列表是静默清空的（它收不到 64 / 51），不计。
     */
    public void aoiLeft(int pairs) {
        if (pairs > 0) {
            aoiLeft.increment(pairs);
        }
    }

    // ================================================================ 存储

    /** 一个存储写任务结束（{@code xm.scene.storage.writes{op, result}}）；耗时从存储线程开始执行起算，含重试等待。 */
    public void periodicSave(PeriodicSave result) {
        periodicSaves.get(result).increment();
    }

    public void storageWrite(StorageOp op, WriteResult result, long elapsedNanos) {
        storageWrites.get(op).get(result).record(Math.max(0, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    // ================================================================ gate 链路

    /** 从链路收到一帧（{@code xm.scene.link.frames{direction=in}}，含握手帧与握手被拒后丢弃的帧）。 */
    public void linkFrameIn(NodeLinkFrame.BodyCase type) {
        framesIn.get(type).increment();
    }

    /** 一帧已交给链路写出（{@code xm.scene.link.frames{direction=out}}）。 */
    public void linkFrameOut(NodeLinkFrame.BodyCase type) {
        framesOut.get(type).increment();
    }

    public void linkFrameDropped(LinkDrop reason) {
        linkDrops.get(reason).increment();
    }

    /** 一条审计记录的结局（任意线程，含 Kafka 生产者的回调线程）。 */
    public void auditRecord(AuditKind kind, AuditResult result) {
        auditRecords.get(kind).get(result).increment();
    }

    /** 一条链路因逻辑线程积压而暂停读取（{@code xm.scene.link.backpressure.pauses}）。 */
    public void linkReadPaused() {
        linkPauses.increment();
    }

    // ================================================================ 内部

    private Timer logicTimer(String name, String description) {
        return Timer.builder(name).description(description).serviceLevelObjectives(LOGIC_BUCKETS).register(registry);
    }

    private Counter aoiCounter(String change) {
        return Counter.builder(AOI_CHANGES)
                .description("视野变化通知（观察者被告知某实体进入 / 离开视野），一对（观察者, 目标）计一次")
                .tag("change", change)
                .register(registry);
    }

    private <E extends Enum<E>> Map<E, Counter> counters(Class<E> type, String name, String tag, String description) {
        EnumMap<E, Counter> map = new EnumMap<>(type);
        for (E value : type.getEnumConstants()) {
            map.put(value, Counter.builder(name).description(description).tag(tag, tagValue(value)).register(registry));
        }
        return map;
    }

    private Map<NodeLinkFrame.BodyCase, Counter> frameCounters(String direction) {
        EnumMap<NodeLinkFrame.BodyCase, Counter> map = new EnumMap<>(NodeLinkFrame.BodyCase.class);
        for (NodeLinkFrame.BodyCase type : NodeLinkFrame.BodyCase.values()) {
            map.put(type, Counter.builder(LINK_FRAMES)
                    .description("gate ↔ scene 链路帧（in = 从链路收到，out = 已交给链路写出）")
                    .tag("direction", direction)
                    .tag("type", tagValue(type))
                    .register(registry));
        }
        return map;
    }

    static String tagValue(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
