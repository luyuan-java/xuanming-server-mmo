package com.game.scene.metrics;

import com.game.api.asset.AssetRpc;
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
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;

/**
 * scene 的低基数指标（Micrometer，经 actuator 以 Prometheus 格式导出，见 architecture.md §11）。
 * 指标名与标签只在这里定义，业务代码只调语义方法。
 *
 * <p><b>标签基数约束</b>（AGENTS.md §5）：不以 player_id / session_id / 实体号 / 场景实例号 / gate 节点号 / 链路号 / zone 作标签。
 * 场景维度只用场景配置号（{@code scene_config}）：主世界频道按 scene-manager 的频道计划在运行期建 / 销毁（批次 5.1），
 * 但只建 World 表里的图（节点拒绝计划里的非世界图），所以它仍受 World 表约束、有界；
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
    static final String GAIN_BLOCK_ENTRIES = "xm.scene.gain.block.entries";
    static final String GAIN_BLOCK_SYNC_AGE = "xm.scene.gain.block.sync.age";
    static final String GAIN_BLOCK_SYNC_FAILURES = "xm.scene.gain.block.sync.failures";
    static final String GAIN_BLOCKED = "xm.scene.gain.blocked";
    static final String GAIN_ANOMALIES = "xm.scene.gain.anomalies";
    static final String SKILL_RELEASES = "xm.scene.skill.releases";
    static final String SKILL_INTERRUPTS = "xm.scene.skill.interrupts";
    static final String TEAM_FOLLOW = "xm.scene.team.follow";
    static final String ASSET_OPS = "xm.scene.asset.ops";
    static final String ASSET_OPS_INFLIGHT = "xm.scene.asset.ops.inflight";
    /** 资产通道回写线程池（把 Dubbo 应答切出逻辑线程）的 {@code executor_*} 指标 {@code name} 标签。 */
    static final String ASSET_REPLY_EXECUTOR_NAME = "scene-asset-reply";
    static final String CHANNELS = "xm.scene.channels";
    static final String CHANNEL_PLAN_APPLIES = "xm.scene.channel.plan.applies";
    static final String CHANNEL_PLAN_POLL_FAILURES = "xm.scene.channel.plan.poll.failures";
    static final String CHANNEL_RELOCATIONS = "xm.scene.channel.relocations";
    static final String SWITCH_RESOLVES = "xm.scene.switch.resolves";
    static final String TRANSFERS = "xm.scene.transfers";
    static final String TRANSFER_FREEZE = "xm.scene.transfer.freeze";
    static final String TRANSFERS_IN_FLIGHT = "xm.scene.transfers.in.flight";
    static final String TRANSFER_ENTERS = "xm.scene.transfer.enters";
    static final String TRANSFER_POST_FREEZE_MUTATIONS = "xm.scene.transfer.post.freeze.mutations";
    static final String FROZEN_REJECTIONS = "xm.scene.frozen.rejections";

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

    /**
     * 跨节点换图冻结时长（从冻结到交出结局处理完：一笔交出事务，结局不明时再加一次探测，上界约 M = 15 s）的桶边界：
     * 存储写那组加上 15 s / 20 s（逻辑线程那组止于 1 s，装不下带重试的库事务）。
     */
    static final Duration[] TRANSFER_FREEZE_BUCKETS = {
            Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofMillis(25), Duration.ofMillis(50),
            Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1),
            Duration.ofMillis(2500), Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(15),
            Duration.ofSeconds(20)};

    /** 一条移动上行（134 / 132 / 131）的裁决结果（{@code xm.scene.moves{result}}），每条恰好计一次。 */
    public enum MoveResult {
        /** 上报位置原样接受。 */
        ACCEPTED,
        /** 位移超出校验额度被截断，但与上报位置的水平偏差不超过 0.5 m，不回 137。 */
        CLAMPED,
        /** 被截断且水平偏差超过 0.5 m，给本人回了 137 纠偏。 */
        CORRECTED,
        /** 位置 / 朝向 / 速度含非有限值，或位置超出世界范围（±1e7 m），整条丢弃。 */
        INVALID,
        /** 玩家在冻结中（跨节点换图交出在途，scene-handoff-spec §5.9 DROP），整条静默丢弃。 */
        FROZEN
    }

    /** 一次放技能（84）的裁决（{@code xm.scene.skill.releases{result}}），每条恰好计一次。 */
    public enum SkillResult {
        /** 成功（广播了 70）。 */
        OK,
        /** 技能不存在或未拥有（1001）。 */
        UNKNOWN_SKILL,
        /** 目标不合法（7001）。 */
        INVALID_TARGET,
        /** 冷却中（7003）。 */
        COOLDOWN,
        /** 施法 / 引导 / 后摇中且新技能不能打断（7000）。 */
        UNINTERRUPTIBLE,
        /** 行为互斥表 / 战斗状态 / 技能许可表拒绝（表里的提示码）。 */
        STATE_REJECTED
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

    /**
     * 一次组队跟随检查（进场 / 换场景后读成员关系，team-spec §6.10）的结局（{@code xm.scene.team.follow{result}}），
     * 每次读回来恰好计一次（队长进场扇出时，每个被扇出的成员各自再读一次、各计一次）。
     */
    public enum TeamFollowResult {
        /** 换到了本节点上队长所在的场景实例。 */
        FOLLOWED,
        /** 已与队长同场景，什么都不做。 */
        SAME_SCENE,
        /** 不在队（索引 tid 为 0 或索引键缺失）。 */
        NOT_IN_TEAM,
        /** 在队但投影缺失，或投影与索引对不上（team_id 不符、leader_id 为 0）。 */
        PROJECTION_MISSING,
        /** 投影的成员里没有自己（索引刚过时）。 */
        NOT_MEMBER,
        /** 队长不在本节点（跨节点、跨 zone、离线）。 */
        LEADER_NOT_ON_NODE,
        /** 队长所在的频道在排空中（批次 5.1 §4.13）：不跟进去，等队长被改派后由它的进场扇出把队伍收拢。 */
        LEADER_SCENE_DRAINING,
        /** 自己有在途的跨节点换图（选目标中或冻结中，scene-handoff-spec §5.5）：不跟随，换图的结局优先。 */
        SWITCHING,
        /** 自己就是队长：自己进场时扇出给本节点的其他成员，被跟随 / 被扇出触发时什么都不做。 */
        IS_LEADER,
        /** 读回来时玩家已离开或已重新进场（不是发起读时的那个实例），丢弃。 */
        STALE,
        /** 读失败（Redis 故障 / 超时）、索引 / 投影损坏，或检查本身出错：不跟随。 */
        READ_ERROR
    }

    /** 存储写的种类（{@code xm.scene.storage.writes{op}}）。每种只登记它可能出现的结局（不建恒为 0 的组合）。 */
    public enum StorageOp {
        /** 最终写回并释放归属（离场、断线、被接管、停服）。 */
        SAVE(WriteResult.RELEASED, WriteResult.FENCED, WriteResult.FAILED, WriteResult.REJECTED),
        /** 只释放归属（没进成的进场）。 */
        RELEASE(WriteResult.RELEASED, WriteResult.FENCED, WriteResult.FAILED, WriteResult.REJECTED),
        /** 在线存盘（周期存盘，不释放归属）。 */
        PROGRESS(WriteResult.SAVED, WriteResult.FENCED, WriteResult.FAILED, WriteResult.REJECTED),
        /** 交出归属（跨节点换图：写回冻结快照 + epoch 加一，一笔事务）。FAILED / REJECTED = 结局不明，交给探测。 */
        HANDOFF(WriteResult.HANDED_OFF, WriteResult.LEASE_TOO_SHORT, WriteResult.FENCED, WriteResult.FAILED,
                WriteResult.REJECTED),
        /** 交出结局不明之后的加锁读探测（只读）。 */
        PROBE(WriteResult.HANDED_OFF, WriteResult.NOT_COMMITTED, WriteResult.LOST, WriteResult.REJECTED);

        private final Set<WriteResult> results;

        StorageOp(WriteResult first, WriteResult... rest) {
            this.results = Collections.unmodifiableSet(EnumSet.of(first, rest));
        }

        /** 这种写可能出现的结局。 */
        public Set<WriteResult> results() {
            return results;
        }
    }

    /** 存储写的结局（{@code xm.scene.storage.writes{result}}），每个写任务恰好计一次。 */
    public enum WriteResult {
        /** 已落库并释放归属。 */
        RELEASED,
        /** 已落库（在线存盘，不释放归属）。 */
        SAVED,
        /** 被 owner_epoch 围栏拒绝（归属已被新的进场取代、已释放或玩家已不存在）：不是故障，什么也没写。 */
        FENCED,
        /** 重试用尽、非瞬时故障或重试等待被中断：写丢失，已记 ERROR 待人工修复（交出是「结局不明」，交给探测，不算丢失）。 */
        FAILED,
        /** 存储线程池拒绝（积压满或已关闭）：写丢失，已记 ERROR；没有执行，耗时记 0。交出被拒按结局不明交给探测，探测被拒按 LOST。 */
        REJECTED,
        /** 已交出（交出提交，或探测认出自己的提交）：epoch 加一、未释放、冻结快照已落库。 */
        HANDED_OFF,
        /** 交出没提交：剩余租约不足安全边际，什么也没改。 */
        LEASE_TOO_SHORT,
        /** 探测：交出没提交（仍由交出方持有、未释放），也不会再提交。 */
        NOT_COMMITTED,
        /** 探测：截止前判定不了，或读到别人的归属 / 已释放 / 玩家不存在，按失去归属处理（fail-closed）。 */
        LOST
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

    /**
     * 一次跨进程资产调用（帮会 / 交易经 Dubbo 调进来的 debit / abort_debit / credit）的结局（{@code xm.scene.asset.ops{rpc, outcome}}，
     * guild-economy-spec E13；基线 scene 侧只有日志），每次调用恰好计一次。前五个是 scene 的答复（{@code AssetOutcome}），后两个是没进逻辑线程或
     * 没拿到答复的传输失败（调用方一律按 Retry 重投）。player_id / seq 只进日志，不进标签。
     */
    public enum AssetOpResult {
        APPLIED,
        REJECTED,
        RETRY,
        NOT_HERE,
        UNKNOWN,
        /** 超过在途上限（{@code xm.scene.asset-op-max-inflight}），直接回失败（「过载」），没进逻辑线程。 */
        OVERLOADED,
        /** 资产通道未就绪、逻辑线程已停或处理中抛异常。 */
        ERROR
    }

    /**
     * 频道计划的应用（{@code xm.scene.channel.plan.applies{result}}，§4.10）：每应用一个新版本计一次 {@code applied}；
     * 其中每条被拒的记录（零号、非世界图、同号异图、未知状态 / 种类）另计一次 {@code rejected}；节点号租约无效而跳过的一次拉取计一次 {@code skipped_lease}。
     */
    public enum ChannelPlanApply {
        APPLIED,
        REJECTED,
        SKIPPED_LEASE
    }

    /**
     * 排空频道里的玩家改派 / 进场重定向（{@code xm.scene.channel.relocations{result}}，§4.10.3、§4.10.4），每人每次计一次。
     */
    public enum ChannelRelocation {
        /** 改派到本节点同图的另一个 ACTIVE 频道（同图保留坐标）。 */
        SAME_MAP,
        /** 本节点没有同图 ACTIVE 频道，改派到本节点默认大世界（World 第一行，落出生点）。 */
        DEFAULT_WORLD,
        /** 本节点两者都没有：原地不动，下一次推进再试（per-node 覆盖模式下不会出现）。 */
        BLOCKED,
        /** 进场加载完成时目标频道已在排空，改进本节点的兄弟频道。 */
        ENTER_REDIRECT,
        /** 玩家在跨节点换图的冻结中（交出事务在途，scene-handoff-spec §5.5）：这次不改派，交出结局出来后实例即离开或解冻再改派。 */
        SWITCHING
    }

    /**
     * 63 远端去向的选目标结果（{@code xm.scene.switch.resolves{result}}，scene-handoff-spec §7.2），每次远端选目标恰好计一次。
     * 只计经 scene-manager 的那部分；本节点直接解析掉的 63 不计。
     */
    public enum SwitchResolve {
        /** 结果在本节点（目录过时或选中本节点另一个频道）：同步换场景。 */
        LOCAL,
        /** 结果在别的节点：冻结、提交交出。 */
        REMOTE,
        /** 结果就是当前场景：什么都不发。 */
        SAME,
        /** scene-manager 业务拒绝，或结果指向本节点却不在本节点 / 在排空：推 23 {3023}。 */
        REJECTED,
        /** 调用失败、本地兜底超时或应答残缺：推 23 {1003}。 */
        ERROR,
        /** 结果回来时实例已离开 / 已重新进场 / 换图已作废：丢弃。 */
        STALE
    }

    /** 一次交出的结局（{@code xm.scene.transfers{result}}，scene-handoff-spec §7.2），每次冻结恰好终结一次。 */
    public enum TransferResult {
        /** 已交出并把 PlayerTransfer 交给了链路。 */
        HANDED_OFF,
        /** 没提交：剩余租约不足安全边际，原地解冻、推 23 {3023}。 */
        LEASE_TOO_SHORT,
        /** 没提交：归属已不是本实例的，移除并踢 2017。 */
        FENCED,
        /** 结局不明，探测确认没提交：原地解冻、推 23 {3023}。 */
        ABORTED_IN_PLACE,
        /** 结局不明且探测判定不了（或读到已失去）：移除并踢 3023（fail-closed）。 */
        LOST_UNKNOWN,
        /** 已交出，但冻结中会话已离开（或实例已被停服 / 换角色移出）：源节点释放新 epoch。 */
        LEFT,
        /** 已交出，但冻结中被请求让出（顶号）：源节点释放新 epoch、踢旧会话 2017。 */
        TAKEN_OVER,
        /** 已交出，但写 PlayerTransfer 时链路已断 / 不可写：源节点释放新 epoch、写重连租约。 */
        LINK_GONE
    }

    /**
     * 冻结闸（跨节点换图交出在途，scene-handoff-spec §5.9）在入口挡掉的一次操作（{@code xm.scene.frozen.rejections{kind}}）。
     * 只计入口集中闸；GATED 方法由服务闸回的基线码不计（它们照常进处理器）。
     */
    public enum FrozenRejection {
        /** 客户端请求按缺省 / 声明的 REJECT 回了应答内 1005。 */
        REQUEST,
        /** 资产通道的未见 seq 回 RETRY 27003（未记账，调用方稍后重投）。 */
        ASSET_OP,
        /** 移动上行（134 / 132 / 131）静默丢弃（DROP）。 */
        MOVE
    }

    /** 目标节点上交出进场（{@code PlayerEnter.transfer = true}）的结果（{@code xm.scene.transfer.enters{result}}）。 */
    public enum TransferEnter {
        OK,
        /** 回了失败的 PlayerEnterResult（场景不在、停止接客、epoch 不符、加载失败、初始化失败）。取消（离开 / 断链 / 接管 / 停服）不计。 */
        FAILED
    }

    /** 没发出去的 scene → gate 链路帧（{@code xm.scene.link.dropped{reason}}）。 */
    public enum LinkDrop {
        /** 链路已注销或已断开（其上会话随链路一起失效）。 */
        LINK_GONE,
        /** 出站缓冲越过高水位（gate 读不动）：丢掉这一帧并断开链路。 */
        WRITE_BUFFER_FULL,
        /**
         * 帧已交给链路、随后异步写失败（链路在冲刷前关闭）。目前只对 PlayerTransfer 挂了写结果监听（源节点据此释放新 epoch，
         * scene-handoff-spec §5.5），其余帧的异步失败随链路断开处理、不单独计。
         */
        WRITE_FAILED
    }

    private final MeterRegistry registry;
    private final Clock clock;
    private final Timer logicTaskWait;
    private final Timer logicTaskRun;
    private final Timer tick;
    private final Map<BroadcastKind, Timer> broadcasts;
    private final Map<MoveResult, Counter> moves;
    private final Map<SkillResult, Counter> skillReleases;
    private final Counter skillInterrupts;
    private final Map<PeriodicSave, Counter> periodicSaves;
    private final Map<TeamFollowResult, Counter> teamFollows;
    private final Counter aoiEntered;
    private final Counter aoiLeft;
    private final Map<StorageOp, Map<WriteResult, Timer>> storageWrites;
    private final Map<NodeLinkFrame.BodyCase, Counter> framesIn;
    private final Map<NodeLinkFrame.BodyCase, Counter> framesOut;
    private final Map<LinkDrop, Counter> linkDrops;
    private final Counter linkPauses;
    private final Map<AuditKind, Map<AuditResult, Counter>> auditRecords;
    private final Map<AssetRpc, Map<AssetOpResult, Counter>> assetOps;
    private final Map<ChannelPlanApply, Counter> channelPlanApplies;
    private final Counter channelPlanPollFailures;
    private final Map<ChannelRelocation, Counter> channelRelocations;
    private final Map<SwitchResolve, Counter> switchResolves;
    private final Map<TransferResult, Counter> transfers;
    private final Timer transferFreeze;
    private final Map<TransferEnter, Counter> transferEnters;
    private final Counter postFreezeMutations;
    private final Map<FrozenRejection, Counter> frozenRejections;
    /** 冻结中（交出在途）的玩家数（逻辑线程推绝对值，抓取线程读）。 */
    private final AtomicInteger transfersInFlight = new AtomicInteger();
    /** 本节点承载中 / 排空中的频道数（逻辑线程推绝对值，抓取线程读）。 */
    private final AtomicInteger activeChannels = new AtomicInteger();
    private final AtomicInteger drainingChannels = new AtomicInteger();
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
        this.skillReleases = counters(SkillResult.class, SKILL_RELEASES, "result", "放技能（84）的裁决结果");
        this.skillInterrupts = Counter.builder(SKILL_INTERRUPTS).description("放技能打断了进行中的施法（推了 33），每次打断计一次")
                .register(registry);
        this.periodicSaves = counters(PeriodicSave.class, PERIODIC_SAVES, "result", "周期存盘对到期玩家的处理（写 / 未变跳过 / 在途跳过 / 存储积压推迟）");
        this.teamFollows = counters(TeamFollowResult.class, TEAM_FOLLOW, "result", "组队跟随检查（进场 / 换场景后读成员关系）的结局");
        this.aoiEntered = aoiCounter("enter");
        this.aoiLeft = aoiCounter("leave");
        this.storageWrites = new EnumMap<>(StorageOp.class);
        for (StorageOp op : StorageOp.values()) {
            EnumMap<WriteResult, Timer> byResult = new EnumMap<>(WriteResult.class);
            for (WriteResult result : op.results()) {
                byResult.put(result, Timer.builder(STORAGE_WRITES)
                        .description("玩家数据写（写回并释放 / 只释放 / 在线存盘 / 交出 / 交出探测）的结局与耗时（含瞬时故障重试）")
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
        // 资产通道：rpc × outcome 全部预注册（3 × 7 条，有界），抓取时没有调用过的组合也是 0
        this.assetOps = new EnumMap<>(AssetRpc.class);
        for (AssetRpc rpc : AssetRpc.values()) {
            EnumMap<AssetOpResult, Counter> byResult = new EnumMap<>(AssetOpResult.class);
            for (AssetOpResult result : AssetOpResult.values()) {
                byResult.put(result, Counter.builder(ASSET_OPS)
                        .description("跨进程资产调用（debit / abort_debit / credit）的结局；overloaded / error 是没拿到 scene 答复的传输失败")
                        .tag("rpc", rpc.wireName())
                        .tag("outcome", tagValue(result))
                        .register(registry));
            }
            assetOps.put(rpc, byResult);
        }
        // 主世界频道（批次 5.1，scene-channels-spec §6.2）：全部预注册，不带 zone / scene_id / 节点号
        Gauge.builder(CHANNELS, activeChannels, AtomicInteger::get)
                .description("本节点的主世界频道数（active = 承载中，draining = 排空中）")
                .tag("state", "active")
                .register(registry);
        Gauge.builder(CHANNELS, drainingChannels, AtomicInteger::get)
                .description("本节点的主世界频道数（active = 承载中，draining = 排空中）")
                .tag("state", "draining")
                .register(registry);
        this.channelPlanApplies = counters(ChannelPlanApply.class, CHANNEL_PLAN_APPLIES, "result",
                "频道计划的应用：applied = 应用了一个新版本；rejected = 其中一条记录被拒；skipped_lease = 节点号租约无效跳过一次拉取");
        this.channelPlanPollFailures = Counter.builder(CHANNEL_PLAN_POLL_FAILURES)
                .description("频道计划拉取（读版本号 / 整读 / 投递逻辑线程应用）失败的次数；失败时什么也不应用，下一秒重试")
                .register(registry);
        this.channelRelocations = counters(ChannelRelocation.class, CHANNEL_RELOCATIONS, "result",
                "排空频道里的玩家改派 / 进场重定向（每人每次计一次）");
        // 跨节点换图（批次 5.2，scene-handoff-spec §7.2）：全部预注册，不带 player / zone / 场景实例号 / 节点号
        this.switchResolves = counters(SwitchResolve.class, SWITCH_RESOLVES, "result",
                "63 远端去向的选目标结果（经 scene-manager 的每次恰好计一次）");
        this.transfers = counters(TransferResult.class, TRANSFERS, "result", "跨节点换图交出的结局（每次冻结恰好终结一次）");
        this.transferFreeze = Timer.builder(TRANSFER_FREEZE)
                .description("跨节点换图的冻结时长（从冻结到交出结局处理完）")
                .serviceLevelObjectives(TRANSFER_FREEZE_BUCKETS)
                .register(registry);
        Gauge.builder(TRANSFERS_IN_FLIGHT, transfersInFlight, AtomicInteger::get)
                .description("冻结中（交出在途）的玩家数")
                .register(registry);
        this.transferEnters = counters(TransferEnter.class, TRANSFER_ENTERS, "result",
                "目标节点上交出进场（PlayerEnter.transfer）的结果");
        this.postFreezeMutations = Counter.builder(TRANSFER_POST_FREEZE_MUTATIONS)
                .description("交出提交后发现冻结期间状态被改过（漏掉的冻结闸），应恒为 0")
                .register(registry);
        this.frozenRejections = counters(FrozenRejection.class, FROZEN_REJECTIONS, "kind",
                "冻结闸（跨节点换图交出在途）在入口挡掉的操作：request = 回 1005，asset_op = 资产通道 RETRY 27003，move = 移动上行静默丢");
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
     * 全服产出封禁名单的同步状态：名单条目数（{@code xm.scene.gain.block.entries}）与距上次成功同步的秒数
     * （{@code xm.scene.gain.block.sync.age}，同步一直失败时持续增长——名单可能已过时，要告警）。回调在抓取线程上调用，
     * 必须线程安全、不阻塞。
     */
    public void bindGainBlocks(IntSupplier entries, DoubleSupplier syncAgeSeconds) {
        Gauge.builder(GAIN_BLOCK_ENTRIES, () -> entries.getAsInt())
                .description("本节点当前生效的全服产出封禁条目数")
                .register(registry);
        Gauge.builder(GAIN_BLOCK_SYNC_AGE, () -> syncAgeSeconds.getAsDouble())
                .description("距上次成功从 Redis 同步全服产出封禁名单的秒数")
                .baseUnit("seconds")
                .register(registry);
    }

    /** 全服产出封禁名单同步失败一次（{@code xm.scene.gain.block.sync.failures}；沿用上次的名单）。 */
    public void gainBlockSyncFailed() {
        Counter.builder(GAIN_BLOCK_SYNC_FAILURES).description("全服产出封禁名单同步失败（沿用上次的名单）")
                .register(registry).increment();
    }

    /** 一次获取被全服产出封禁拒绝（{@code xm.scene.gain.blocked{category}}，category 取固定集合）。 */
    public void gainBlocked(String category) {
        Counter.builder(GAIN_BLOCKED).description("被全服产出封禁拒绝的获取").tag("category", category)
                .register(registry).increment();
    }

    /** 物品类告警的 currency_type 标签值（同一指标的每条序列标签键要一致）。 */
    public static final String NO_CURRENCY_TYPE = "none";

    /**
     * 一次获取异常告警（{@code xm.scene.gain.anomalies{category, currency_type}}）。currency_type 只取已知币种
     * （个位数），物品类告警填 {@value #NO_CURRENCY_TYPE}、不带 config id（高基数，见 AGENTS §5），玩家号与配置号只进日志。
     */
    public void gainAnomaly(String category, String currencyType) {
        Counter.builder(GAIN_ANOMALIES).description("获取异常告警（滑动窗口内次数或累计量超阈值，每次越线计一次）")
                .tag("category", category).tag("currency_type", currencyType)
                .register(registry).increment();
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

    // ================================================================ 主世界频道（批次 5.1）

    /** 本节点承载中 / 排空中的频道数（{@code xm.scene.channels{state}}）。逻辑线程在频道增删、状态变化后推绝对值。 */
    public void channels(int active, int draining) {
        activeChannels.set(active);
        drainingChannels.set(draining);
    }

    /** 频道计划应用的一次结局（任意线程：逻辑线程记 applied / rejected，拉取线程记 skipped_lease）。 */
    public void channelPlanApply(ChannelPlanApply result) {
        channelPlanApplies.get(result).increment();
    }

    /** 一次频道计划拉取失败（拉取线程）。 */
    public void channelPlanPollFailed() {
        channelPlanPollFailures.increment();
    }

    /** 一次改派 / 进场重定向（逻辑线程）。 */
    public void channelRelocation(ChannelRelocation result) {
        channelRelocations.get(result).increment();
    }

    // ================================================================ 跨节点换图（批次 5.2）

    /** 一次远端选目标的结果（逻辑线程）。 */
    public void switchResolve(SwitchResolve result) {
        switchResolves.get(result).increment();
    }

    /** 一次交出终结（逻辑线程）：计结局，并记从冻结到此刻的冻结时长。 */
    public void transfer(TransferResult result, long frozenNanos) {
        transfers.get(result).increment();
        transferFreeze.record(Math.max(0, frozenNanos), TimeUnit.NANOSECONDS);
    }

    /** 冻结中的玩家数（逻辑线程在变化后推绝对值）。 */
    public void transfersInFlight(int count) {
        transfersInFlight.set(count);
    }

    /** 目标节点上一次交出进场的结果（逻辑线程）。 */
    public void transferEnter(TransferEnter result) {
        transferEnters.get(result).increment();
    }

    /** 交出提交后发现冻结期间状态被改过（逻辑线程；应恒为 0，非 0 说明有入口漏了冻结闸）。 */
    public void postFreezeMutation() {
        postFreezeMutations.increment();
    }

    /** 冻结闸在入口挡掉一次操作（逻辑线程：请求分发、资产通道）。 */
    public void frozenRejection(FrozenRejection kind) {
        frozenRejections.get(kind).increment();
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

    public void skillRelease(SkillResult result) {
        skillReleases.get(result).increment();
    }

    public void skillInterrupted() {
        skillInterrupts.increment();
    }

    /** 一次组队跟随检查的结局（逻辑线程）。 */
    public void teamFollow(TeamFollowResult result) {
        teamFollows.get(result).increment();
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

    public void periodicSave(PeriodicSave result) {
        periodicSaves.get(result).increment();
    }

    /**
     * 一个存储写任务结束（{@code xm.scene.storage.writes{op, result}}）；耗时从存储线程开始执行起算，含重试等待。
     * 不在 {@link StorageOp#results()} 里的组合不记（调用方的编程错误；不抛，存储线程还要接着投递结局）。
     */
    public void storageWrite(StorageOp op, WriteResult result, long elapsedNanos) {
        Timer timer = storageWrites.get(op).get(result);
        if (timer != null) {
            timer.record(Math.max(0, elapsedNanos), TimeUnit.NANOSECONDS);
        }
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

    // ================================================================ 资产通道（跨进程）

    /** 一次跨进程资产调用的结局（任意线程：回写线程或 Dubbo 业务线程）。 */
    public void assetOp(AssetRpc rpc, AssetOpResult result) {
        assetOps.get(rpc).get(result).increment();
    }

    /**
     * 资产通道在途调用数（{@code xm.scene.asset.ops.inflight}；已占在途名额、还没回写的）。{@code inFlight} 在抓取线程上调用，
     * 必须线程安全、不阻塞。
     */
    public void bindAssetOpsInFlight(IntSupplier inFlight) {
        Gauge.builder(ASSET_OPS_INFLIGHT, () -> inFlight.getAsInt())
                .description("资产通道在途调用数（上限 xm.scene.asset-op-max-inflight，超出直接回过载）")
                .register(registry);
    }

    /** 资产通道回写线程池（{@code executor.*{name="scene-asset-reply"}}）。 */
    public void bindAssetReplyExecutor(ExecutorService executor) {
        new ExecutorServiceMetrics(executor, ASSET_REPLY_EXECUTOR_NAME, Tags.empty()).bindTo(registry);
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
