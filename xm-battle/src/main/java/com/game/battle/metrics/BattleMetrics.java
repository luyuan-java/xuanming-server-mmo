package com.game.battle.metrics;

import com.game.battle.admission.AdmissionPhase;
import com.game.battle.protocol.BattleMessageIds.Notify;
import com.game.battle.protocol.BattleMessageIds.Upstream;
import com.game.battle.push.PushCategory;
import com.game.battle.push.PushRoute;
import com.game.battle.room.FingerprintMode;
import com.game.common.token.BattleTickets;
import com.game.discovery.presence.PlayerPushes;
import com.game.net.client.ClientFrameException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * battle 节点的低基数指标（Micrometer，经 actuator 以 Prometheus 格式导出；battle-node-spec §9）。指标名与标签只在这里定义，
 * 业务代码只调语义方法；全部标签取值都是本类或协议里的有界枚举。
 *
 * <p><b>标签基数约束</b>（AGENTS.md §5）：不以 player_id / battle_id / 会话号 / IP / 节点号作标签。
 *
 * <p><b>预建</b>：构造时把全部预期的标签组合建成 0（不预建的话「从没发生」与「指标不存在」分不开，{@code rate(...) > 0} 的告警既不报警也不报错）。
 * Gauge 也在构造时注册，读数经 {@code bind*} 绑定的回调（绑定前读 0）；回调由抓取线程调用，必须线程安全、不阻塞、不读逻辑线程独占的状态
 * （房间数 / 直连数由属主维护在原子量里，抓取线程只读原子量）。
 *
 * <p><b>线程</b>：全部方法线程安全，可从任意线程调（逻辑线程、Dubbo 线程、回复执行器、Redisson 回调线程、管理 Tomcat 线程），不抛异常。
 *
 * <p><b>谁计什么</b>（每个事件恰好由一处计）：
 * <ul>
 *   <li>房间（{@code com.game.battle.room} / {@code push} / {@code ticket}）：{@link #roomCreate}（除 NOT_ALLOCATABLE）、{@link #roomEnd}、
 *       {@link #fingerprintMismatch}、{@link #round}、{@link #roundResolve}、{@link #push}、{@link #ticket}；大厅回落的结局由
 *       {@code PresenceLobbyAnnouncer} 计 {@link #lobbyPushOutcome}；</li>
 *   <li>直连面（{@code com.game.battle.edge}）：{@link #handshake}、{@link #clientRequest}、{@link #invalidFrame}、{@link #disconnect}
 *       （含房间经 {@code DirectLink.closeGracefully / closeNow} 传入的原因，由直连面在真正关闭时计一次）；</li>
 *   <li>出站端口实现：{@link #sceneEvent}、{@link #result}；对局结果的 Kafka 传输（{@code port.kafka.KafkaBattleResultSink}）另计
 *       {@link #resultEvent}；</li>
 *   <li>控制面（{@code rpc} / {@code BattleNode}）：{@link #rpc}、{@code roomCreate(NOT_ALLOCATABLE)}、{@link #leaseLost}；</li>
 *   <li>dev 管理接口（{@code admin.DevGatherController}）：{@link #devGather}。</li>
 * </ul>
 * Gauge 由装配方绑定：{@code bindRooms(rooms::roomCount)}、{@code bindDirectConnections(edge::connectionCount)}、
 * {@code bindLogicPendingTasks(scheduler::pendingTasks)}、{@code bindAdmissionPhase(admission::phase)}。
 */
public final class BattleMetrics {

    static final String ROOMS = "xm.battle.rooms";
    static final String ROOM_CREATES = "xm.battle.room.creates";
    static final String ROOM_ENDS = "xm.battle.room.ends";
    static final String FINGERPRINT_MISMATCH = "xm.battle.fingerprint.mismatch";
    static final String ROUNDS = "xm.battle.rounds";
    static final String ROUND_RESOLVE = "xm.battle.round.resolve";
    static final String DIRECT_CONNECTIONS = "xm.battle.direct.connections";
    static final String HANDSHAKES = "xm.battle.handshakes";
    static final String CLIENT_REQUESTS = "xm.battle.client.requests";
    static final String INVALID_FRAMES = "xm.battle.invalid.frames";
    static final String DISCONNECTS = "xm.battle.disconnects";
    static final String PUSHES = "xm.battle.pushes";
    static final String LOBBY_PUSH_OUTCOMES = "xm.battle.lobby.push.outcomes";
    static final String TICKETS = "xm.battle.tickets";
    static final String SCENE_EVENTS = "xm.battle.scene.events";
    static final String RESULTS = "xm.battle.results";
    static final String RESULT_EVENTS = "xm.battle.result.events";
    static final String RPC = "xm.battle.rpc";
    static final String LOGIC_PENDING = "xm.battle.logic.pending.tasks";
    static final String ADMISSION_PHASE = "xm.battle.admission.phase";
    static final String LEASE_LOST = "xm.battle.lease.lost";
    static final String DEV_GATHER = "xm.battle.dev.gather";

    /** 白名单外的上行号（{@code xm_battle_client_requests_total{method}}）。 */
    public static final String METHOD_OTHER = "other";

    /** 逻辑线程上「结算 + 广播」耗时的桶：0.1 ms～1 s 共 12 个（同 scene 的逻辑线程桶）。 */
    static final Duration[] ROUND_BUCKETS = {
            Duration.ofNanos(100_000), Duration.ofNanos(250_000), Duration.ofNanos(500_000), Duration.ofMillis(1),
            Duration.ofNanos(2_500_000), Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofMillis(25),
            Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofSeconds(1)};

    /** 控制面提供方耗时（含逻辑线程排队）的桶：1 ms～1 s 共 10 个。 */
    static final Duration[] RPC_BUCKETS = {
            Duration.ofMillis(1), Duration.ofNanos(2_500_000), Duration.ofMillis(5), Duration.ofMillis(10),
            Duration.ofMillis(25), Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(250),
            Duration.ofMillis(500), Duration.ofSeconds(1)};

    // ---------------------------------------------------------------- 标签枚举

    /** 建房结局（{@code xm_battle_room_creates_total{result}}），每次 createBattle 恰好计一次。 */
    public enum CreateResult {
        /** 新建成功。 */
        OK,
        /** 幂等命中（房间已存在，不比较内容，零副作用）。 */
        IDEMPOTENT,
        /** 1005：battle_id 为 0 / 没有玩家 / 路由缺 gate 或 scene 实例。 */
        INVALID,
        /** 1006：enforce 模式下指纹不一致。 */
        FINGERPRINT_REJECT,
        /** 1002：引擎拒绝开局。 */
        ENGINE_REJECT,
        /** 1003：预签票据失败（零副作用）。 */
        TICKET_FAILED,
        /** 节点级拒绝（准入闸 / 在途超限），由控制面计。 */
        NOT_ALLOCATABLE
    }

    /** 房间结局（{@code xm_battle_room_ends_total{reason}}），每间房 erase 时恰好计一次。 */
    public enum RoomEnd {
        /** 分出胜负。 */
        FINISHED,
        /** 整场期限到，强制平局。 */
        DEADLINE,
        /** DestroyBattle（match 补偿 / dev 接口）。 */
        DESTROYED,
        /** 停机作废（abortAll）。 */
        ABORTED
    }

    /** 回合结算的触发方式（{@code xm_battle_rounds_total{trigger}}），每次结算恰好计一次。 */
    public enum RoundTrigger {
        /** 回合窗口到期。 */
        TIMER,
        /** 149 提交使全员就绪（含全自动房间里的合法提交，B2）。 */
        ALL_READY,
        /** 162 开自动造成「未就绪 → 就绪」翻转。 */
        AUTO_FLIP
    }

    /** 握手结局（{@code xm_battle_handshakes_total{result}}），每个握手包恰好计一次；取值即基线采样日志的 reason。 */
    public enum HandshakeResult {
        OK,
        /** 已验证的连接再握手（不看新票，回旧 battle_id，B1）。 */
        REPEAT,
        TICKET_HMAC_MISMATCH,
        TICKET_PAYLOAD_PARSE_FAILED,
        EMPTY_IDENTITY,
        NODE_MISMATCH,
        INSTANCE_MISMATCH,
        EXPIRED,
        ROLE_INVALID,
        TICKET_NOT_IN_ROSTER;

        /** 字段判定失败对应的结局。 */
        public static HandshakeResult of(BattleTickets.Verdict verdict) {
            return switch (verdict) {
                case OK -> OK;
                case EMPTY_IDENTITY -> EMPTY_IDENTITY;
                case NODE_MISMATCH -> NODE_MISMATCH;
                case INSTANCE_MISMATCH -> INSTANCE_MISMATCH;
                case EXPIRED -> EXPIRED;
                case ROLE_INVALID -> ROLE_INVALID;
            };
        }
    }

    /** 已验证连接上一条 {@code ClientRequest} 的结局（{@code xm_battle_client_requests_total{result}}），每条恰好计一次。 */
    public enum RequestResult {
        /** 应答体没有错误码。 */
        OK,
        /** 应答体带业务 tip（引擎拒绝、1005 房间不存在等）。 */
        BUSINESS_ERROR,
        /** 信封 1010：整条超过 1024 B。 */
        OVERSIZED,
        /** 信封 1008：按消息号限频。 */
        RATE_LIMITED,
        /** 信封 1005：消息号不在白名单。 */
        NOT_ALLOWED,
        /** 信封 1005：请求体解析失败。 */
        BAD_BODY
    }

    /** 服务端主动断开直连的原因（{@code xm_battle_disconnects_total{reason}}），每条连接至多计一次。 */
    public enum Disconnect {
        /** 握手期限内没完成握手。 */
        HANDSHAKE_TIMEOUT,
        /** 握手被拒（应答 → FIN → 0.1 s 强关）。 */
        HANDSHAKE_REJECTED,
        /** 握手前发 ClientRequest。 */
        REQUEST_BEFORE_VERIFY,
        /** 非法包达阈值。 */
        ILLEGAL_PACKETS,
        /** 输出缓冲越过高水位（2 MiB）。 */
        WRITE_BUFFER_FULL,
        /** 解码失败 / 不收的类型名。 */
        INVALID_FRAME,
        /** 并发连接数已满。 */
        AT_CAPACITY,
        /** 同一玩家的新直连挂接成功，旧连接被顶替（立即强关，不发帧）。 */
        REPLACED,
        /** 房间收尾、作废、退出观战、观众被清退（优雅关闭）。 */
        BATTLE_CLOSED,
        /** 停机。 */
        SHUTDOWN
    }

    /** 大厅公告经 gate 回落的结局（{@code xm_battle_lobby_push_outcomes_total{outcome}}）：{@code PlayerPushes} 的结局 + 异常。 */
    public enum LobbyOutcome {
        SENT,
        OFFLINE,
        GATE_UNREACHABLE,
        /** Redis 故障、在线目录条目损坏等（stage 异常完成）。 */
        ERROR;

        public static LobbyOutcome of(PlayerPushes.Outcome outcome) {
            return switch (outcome) {
                case SENT -> SENT;
                case OFFLINE -> OFFLINE;
                case GATE_UNREACHABLE -> GATE_UNREACHABLE;
            };
        }
    }

    /** 签票的路径（{@code xm_battle_tickets_total{path}}）。 */
    public enum TicketPath {
        /** CreateBattle 给参战者预签。 */
        CREATE,
        /** AddObserver 给观众签（新观众与幂等重签）。 */
        OBSERVER,
        /** IssueBattleTicket 补签。 */
        REISSUE
    }

    /** battle → scene 的事件种类（{@code xm_battle_scene_events_total{kind}}）。 */
    public enum SceneEventKind {
        /** BattleConfirmedEvent（首发 + 补发）。 */
        CONFIRM,
        /** 结算（每名参战者一条）。 */
        SETTLEMENT
    }

    /** 出站端口一次调用的结局（{@code xm_battle_scene_events_total{result}}）。6.2 的日志缺省实现只有 {@link #LOGGED}。 */
    public enum SceneEventResult {
        LOGGED,
        SENT,
        /** 确认：快照路由的实例已不在（备战节点重启 / 下线），回落到定位器、发往玩家现在的实例（D28）。 */
        REROUTED,
        /** 确认：scene 回 NOT_HERE（实例不符 / 已换实例）。 */
        NOT_HERE,
        /** 不发。确认：快照路由的实例已不在，回落到定位器也没找到持有者（NoHolder）或定位出错；结算：dev 房间不结算。 */
        SKIPPED,
        ERROR
    }

    /** 对局结果事件的通道（{@code xm_battle_results_total{channel}}）：普通局 / 带活动上下文的局。 */
    public enum ResultChannel {
        PLAIN,
        ACTIVITY
    }

    /**
     * 对局结果事件一次发布的结局（{@code xm_battle_results_total{result}}），每次 {@code publish} 恰好计一次。只记日志的实现
     * （{@code LoggingBattleResultSink}）只有 {@link #LOGGED}；Kafka 实现（6.4）在结局确定时计 {@link #SENT} 或 {@link #ERROR}。
     */
    public enum ResultOutcome {
        LOGGED,
        /** Kafka 已确认。 */
        SENT,
        /** 没能由 Kafka 确认（已完整写进兜底日志 {@code xm.battle.result.fallback}；细分见 {@link ResultEvent}）。 */
        ERROR
    }

    /**
     * 对局结果事件在 Kafka 传输上的结局（{@code xm_battle_result_events_total{result}}；match-spec §5.4、§11），每次 {@code publish} 恰好计一次，
     * 不分通道（分通道的计数见 {@link ResultOutcome}：{@code sent} 相等，{@code error} = {@code fallback} + {@code not_verified}）。
     */
    public enum ResultEvent {
        /** Kafka 已确认。 */
        SENT,
        /** 队列满 / 发送失败 / 投递失败 / 停服没发完：完整字节写进兜底日志。 */
        FALLBACK,
        /** topic 还没核对通过（Kafka 不可达、分区契约不符、生产者待重建）：同样写兜底日志，单列出来便于告警区分「一直没接上」。 */
        NOT_VERIFIED
    }

    /** 控制面方法（{@code xm_battle_rpc_seconds{method}}）。 */
    public enum RpcMethod {
        CREATE_BATTLE("createBattle"),
        DESTROY_BATTLE("destroyBattle"),
        ISSUE_BATTLE_TICKET("issueBattleTicket"),
        ADD_OBSERVER("addObserver"),
        REMOVE_OBSERVER("removeObserver");

        private final String label;

        RpcMethod(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 控制面一次调用的结局（{@code xm_battle_rpc_seconds{result}}）。 */
    public enum RpcResult {
        /** 应答没有错误码（含幂等命中）。 */
        OK,
        /** 应答带业务 tip。 */
        BUSINESS_ERROR,
        /** createBattle 的 NOT_ALLOCATABLE。 */
        NOT_ALLOCATABLE,
        /** future 异常完成（投递被拒、在途超限、处理中抛异常）。 */
        ERROR
    }

    /**
     * dev gather 的模式（{@code xm_battle_dev_gather_total{mode}}，scene-battle-spec §9）。{@link #UNKNOWN} = 解析请求体之前就拒绝的
     * （运行模式 403、请求体非法），那时还不知道模式。
     */
    public enum DevGatherMode {
        PREPARE_ONLY,
        CREATE,
        UNKNOWN
    }

    /** dev gather 的结局（{@code xm_battle_dev_gather_total{result}}）。 */
    public enum DevGatherResult {
        OK,
        /** 有人定位 / 备战失败（含指纹不一致），没走到建房。 */
        PREPARE_FAILED,
        /** 全员备战成功，建房不可分配 / 业务错误 / 结局不明。 */
        CREATE_FAILED,
        /** 运行模式不是 dev / test（403）。 */
        FORBIDDEN,
        /** 请求体非法（400）或节点没在运行（503）。 */
        REJECTED
    }

    // ---------------------------------------------------------------- 计量器

    private final MeterRegistry registry;
    private final Map<CreateResult, Counter> roomCreates;
    private final Map<RoomEnd, Counter> roomEnds;
    private final Map<FingerprintMode, Counter> fingerprintMismatches;
    private final Map<RoundTrigger, Counter> rounds;
    private final Timer roundResolve;
    private final Map<HandshakeResult, Counter> handshakes;
    private final Map<String, Map<RequestResult, Counter>> clientRequests;
    private final Map<ClientFrameException.Reason, Counter> invalidFrames;
    private final Map<Disconnect, Counter> disconnects;
    private final ConcurrentHashMap<String, Counter> pushes = new ConcurrentHashMap<>();
    private final Map<LobbyOutcome, Counter> lobbyPushOutcomes;
    private final Map<TicketPath, Map<Boolean, Counter>> tickets;
    private final Map<SceneEventKind, Map<SceneEventResult, Counter>> sceneEvents;
    private final Map<ResultChannel, Map<ResultOutcome, Counter>> results;
    private final Map<ResultEvent, Counter> resultEvents;
    private final Map<RpcMethod, Map<RpcResult, Timer>> rpcs;
    private final Counter leaseLost;
    private final Map<DevGatherMode, Map<DevGatherResult, Counter>> devGathers;

    private volatile IntSupplier roomCount = () -> 0;
    private volatile IntSupplier directConnectionCount = () -> 0;
    private volatile IntSupplier logicPendingTasks = () -> 0;
    private volatile Supplier<AdmissionPhase> admissionPhase = () -> AdmissionPhase.NOT_STARTED;

    public BattleMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");

        Gauge.builder(ROOMS, () -> roomCount.getAsInt()).description("本节点的房间数").register(registry);
        this.roomCreates = counters(CreateResult.class, ROOM_CREATES, "建房结局（每次 createBattle 恰好计一次）", "result", BattleMetrics::lower);
        this.roomEnds = counters(RoomEnd.class, ROOM_ENDS, "房间结局（每间房移除时恰好计一次）", "reason", BattleMetrics::lower);
        Map<FingerprintMode, Counter> mismatches = new EnumMap<>(FingerprintMode.class);
        for (FingerprintMode mode : new FingerprintMode[] {FingerprintMode.WARN, FingerprintMode.ENFORCE}) {
            mismatches.put(mode, Counter.builder(FINGERPRINT_MISMATCH).description("战斗配表指纹不一致（warn 放行 / enforce 拒绝）")
                    .tag("mode", mode.wireName()).register(registry));
        }
        this.fingerprintMismatches = mismatches;
        this.rounds = counters(RoundTrigger.class, ROUNDS, "回合结算次数（按触发方式）", "trigger", BattleMetrics::lower);
        this.roundResolve = Timer.builder(ROUND_RESOLVE).description("逻辑线程上一次「结算 + 广播」的耗时")
                .serviceLevelObjectives(ROUND_BUCKETS).register(registry);

        Gauge.builder(DIRECT_CONNECTIONS, () -> directConnectionCount.getAsInt()).description("直连数（含未握手）").register(registry);
        this.handshakes = counters(HandshakeResult.class, HANDSHAKES, "握手结局（每个握手包恰好计一次）", "result", BattleMetrics::lower);
        Map<String, Map<RequestResult, Counter>> requests = new HashMap<>();
        for (Upstream upstream : Upstream.values()) {
            requests.put(upstream.method(), requestCounters(upstream.method()));
        }
        requests.put(METHOD_OTHER, requestCounters(METHOD_OTHER));
        this.clientRequests = Map.copyOf(requests);
        this.invalidFrames = counters(ClientFrameException.Reason.class, INVALID_FRAMES, "非法帧（解码失败即断开，不回包）", "reason",
                BattleMetrics::lower);
        this.disconnects = counters(Disconnect.class, DISCONNECTS, "服务端主动断开直连", "reason", BattleMetrics::lower);

        for (Notify notify : Notify.values()) {
            pushCounter(notify.category(), PushRoute.DIRECT, notify);
            pushCounter(notify.category(),
                    notify.category() == PushCategory.LOBBY_ANNOUNCEMENT ? PushRoute.VIA_GATE : PushRoute.DROP, notify);
        }
        this.lobbyPushOutcomes = counters(LobbyOutcome.class, LOBBY_PUSH_OUTCOMES, "大厅公告经 gate 回落的结局", "outcome",
                BattleMetrics::lower);
        Map<TicketPath, Map<Boolean, Counter>> ticketCounters = new EnumMap<>(TicketPath.class);
        for (TicketPath path : TicketPath.values()) {
            ticketCounters.put(path, Map.of(
                    true, Counter.builder(TICKETS).description("签票").tag("path", lower(path)).tag("result", "ok").register(registry),
                    false, Counter.builder(TICKETS).description("签票").tag("path", lower(path)).tag("result", "failed").register(registry)));
        }
        this.tickets = ticketCounters;
        Map<SceneEventKind, Map<SceneEventResult, Counter>> scene = new EnumMap<>(SceneEventKind.class);
        for (SceneEventKind kind : SceneEventKind.values()) {
            Map<SceneEventResult, Counter> byResult = new EnumMap<>(SceneEventResult.class);
            for (SceneEventResult result : SceneEventResult.values()) {
                byResult.put(result, Counter.builder(SCENE_EVENTS).description("battle → scene 的事件（确认 / 结算）")
                        .tag("kind", lower(kind)).tag("result", lower(result)).register(registry));
            }
            scene.put(kind, byResult);
        }
        this.sceneEvents = scene;
        Map<ResultChannel, Map<ResultOutcome, Counter>> resultCounters = new EnumMap<>(ResultChannel.class);
        for (ResultChannel channel : ResultChannel.values()) {
            Map<ResultOutcome, Counter> byResult = new EnumMap<>(ResultOutcome.class);
            for (ResultOutcome result : ResultOutcome.values()) {
                byResult.put(result, Counter.builder(RESULTS).description("对局结果事件（只在真打完的局发）")
                        .tag("channel", lower(channel)).tag("result", lower(result)).register(registry));
            }
            resultCounters.put(channel, byResult);
        }
        this.results = resultCounters;
        this.resultEvents = counters(ResultEvent.class, RESULT_EVENTS, "对局结果事件在 Kafka 传输上的结局（每次发布恰好计一次）", "result",
                BattleMetrics::lower);
        Map<RpcMethod, Map<RpcResult, Timer>> rpcTimers = new EnumMap<>(RpcMethod.class);
        for (RpcMethod method : RpcMethod.values()) {
            Map<RpcResult, Timer> byResult = new EnumMap<>(RpcResult.class);
            for (RpcResult result : RpcResult.values()) {
                byResult.put(result, Timer.builder(RPC).description("控制面提供方耗时（含逻辑线程排队）")
                        .tag("method", method.label()).tag("result", lower(result))
                        .serviceLevelObjectives(RPC_BUCKETS).register(registry));
            }
            rpcTimers.put(method, byResult);
        }
        this.rpcs = rpcTimers;
        Gauge.builder(LOGIC_PENDING, () -> logicPendingTasks.getAsInt()).description("逻辑线程任务队列长度").register(registry);
        Gauge.builder(ADMISSION_PHASE, () -> admissionPhase.get().gaugeValue())
                .description("建房准入闸阶段：0 not_started / 1 open / 2 closed").register(registry);
        this.leaseLost = Counter.builder(LEASE_LOST).description("节点号租约丢失（关闸、停发布、不作废房间）").register(registry);
        Map<DevGatherMode, Map<DevGatherResult, Counter>> gathers = new EnumMap<>(DevGatherMode.class);
        for (DevGatherMode mode : DevGatherMode.values()) {
            Map<DevGatherResult, Counter> byResult = new EnumMap<>(DevGatherResult.class);
            for (DevGatherResult result : DevGatherResult.values()) {
                byResult.put(result, Counter.builder(DEV_GATHER).description("dev / test 管理接口 gather 的结局（scene-battle-spec §7.18）")
                        .tag("mode", lower(mode)).tag("result", lower(result)).register(registry));
            }
            gathers.put(mode, byResult);
        }
        this.devGathers = gathers;
    }

    // ---------------------------------------------------------------- Gauge 绑定（回调必须线程安全、不阻塞）

    /** 房间数（房间表 hooks 维护的原子量）。 */
    public void bindRooms(IntSupplier count) {
        this.roomCount = Objects.requireNonNull(count);
    }

    /** 直连数（含未握手；直连面维护的原子量）。 */
    public void bindDirectConnections(IntSupplier count) {
        this.directConnectionCount = Objects.requireNonNull(count);
    }

    /** 逻辑线程任务队列长度（{@code BattleScheduler#pendingTasks()}）。 */
    public void bindLogicPendingTasks(IntSupplier pending) {
        this.logicPendingTasks = Objects.requireNonNull(pending);
    }

    /** 准入闸阶段（{@code AdmissionGate::phase}）。 */
    public void bindAdmissionPhase(Supplier<AdmissionPhase> phase) {
        this.admissionPhase = Objects.requireNonNull(phase);
    }

    // ---------------------------------------------------------------- 房间

    public void roomCreate(CreateResult result) {
        roomCreates.get(result).increment();
    }

    public void roomEnd(RoomEnd reason) {
        roomEnds.get(reason).increment();
    }

    /** 指纹不一致（只计 warn / enforce；off 不比，不会调到这里，调了也忽略）。 */
    public void fingerprintMismatch(FingerprintMode mode) {
        Counter counter = fingerprintMismatches.get(mode);
        if (counter != null) {
            counter.increment();
        }
    }

    public void round(RoundTrigger trigger) {
        rounds.get(trigger).increment();
    }

    /** 一次「结算 + 广播」的耗时（纳秒，单调时钟）。 */
    public void roundResolve(long nanos) {
        roundResolve.record(Math.max(0, nanos), TimeUnit.NANOSECONDS);
    }

    // ---------------------------------------------------------------- 直连面

    public void handshake(HandshakeResult result) {
        handshakes.get(result).increment();
    }

    /** @param method 白名单内的上行；null = 白名单外的号（标签 {@value #METHOD_OTHER}） */
    public void clientRequest(Upstream method, RequestResult result) {
        clientRequests.get(method == null ? METHOD_OTHER : method.method()).get(result).increment();
    }

    public void invalidFrame(ClientFrameException.Reason reason) {
        invalidFrames.get(reason).increment();
    }

    public void disconnect(Disconnect reason) {
        disconnects.get(reason).increment();
    }

    // ---------------------------------------------------------------- 推送与签票

    /** 一次推送的出口（每个收件人每条消息恰好计一次）。 */
    public void push(PushCategory category, PushRoute route, Notify message) {
        pushCounter(category, route, message).increment();
    }

    public void lobbyPushOutcome(LobbyOutcome outcome) {
        lobbyPushOutcomes.get(outcome).increment();
    }

    public void ticket(TicketPath path, boolean ok) {
        tickets.get(path).get(ok).increment();
    }

    // ---------------------------------------------------------------- 出站端口

    public void sceneEvent(SceneEventKind kind, SceneEventResult result) {
        sceneEvents.get(kind).get(result).increment();
    }

    public void result(ResultChannel channel, ResultOutcome result) {
        results.get(channel).get(result).increment();
    }

    /** 对局结果事件在 Kafka 传输上的结局（Kafka 实现在结局确定时调：发布线程、{@code battle-result-out} 或 Kafka 的发送线程）。 */
    public void resultEvent(ResultEvent result) {
        resultEvents.get(result).increment();
    }

    // ---------------------------------------------------------------- 控制面与节点

    /** 一次控制面调用（提供方收到 → future 完成的耗时，纳秒）。 */
    public void rpc(RpcMethod method, RpcResult result, long nanos) {
        rpcs.get(method).get(result).record(Math.max(0, nanos), TimeUnit.NANOSECONDS);
    }

    public void leaseLost() {
        leaseLost.increment();
    }

    /** dev gather 的一次调用（管理 Tomcat 线程）。 */
    public void devGather(DevGatherMode mode, DevGatherResult result) {
        devGathers.get(mode).get(result).increment();
    }

    // ---------------------------------------------------------------- 内部

    private Counter pushCounter(PushCategory category, PushRoute route, Notify message) {
        String key = category.label() + '|' + route.label() + '|' + message.method();
        return pushes.computeIfAbsent(key, k -> Counter.builder(PUSHES).description("房间推送的出口（直连 / 经 gate / 丢弃）")
                .tag("category", category.label()).tag("route", route.label()).tag("message", message.method())
                .register(registry));
    }

    private Map<RequestResult, Counter> requestCounters(String method) {
        Map<RequestResult, Counter> byResult = new EnumMap<>(RequestResult.class);
        for (RequestResult result : RequestResult.values()) {
            byResult.put(result, Counter.builder(CLIENT_REQUESTS).description("已验证直连上的客户端请求（每条恰好计一次）")
                    .tag("method", method).tag("result", lower(result)).register(registry));
        }
        return byResult;
    }

    private <E extends Enum<E>> Map<E, Counter> counters(Class<E> type, String name, String description, String tag,
                                                         Function<E, String> label) {
        Map<E, Counter> map = new EnumMap<>(type);
        for (E value : type.getEnumConstants()) {
            map.put(value, Counter.builder(name).description(description).tag(tag, label.apply(value)).register(registry));
        }
        return map;
    }

    static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
