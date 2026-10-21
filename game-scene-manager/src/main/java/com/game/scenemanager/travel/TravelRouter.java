package com.game.scenemanager.travel;

import com.game.api.ChannelKinds;
import com.game.api.proto.ChannelState;
import com.game.api.proto.GateNodeInfo;
import com.game.api.proto.RedirectToZoneRequest;
import com.game.api.proto.RedirectToZoneResponse;
import com.game.api.proto.SelectTravelTargetRequest;
import com.game.api.proto.SelectTravelTargetResponse;
import com.game.api.proto.WorldChannel;
import com.game.api.proto.ZoneRedirect;
import com.game.common.token.GateTokenIssuer;
import com.game.common.token.GateTokenIssuer.IssuedGateToken;
import com.game.discovery.gate.GatePicker;
import com.game.discovery.gate.GateSource;
import com.game.discovery.world.WorldChannelStore;
import com.game.discovery.zone.ZoneMergeFence;
import com.game.scenemanager.SceneAssigner;
import com.game.scenemanager.SceneNodeSource;
import com.game.scenemanager.WorldSceneConfigs;
import com.game.table.SceneErrorTip;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 跨 zone 的两种选路（批次 5.4，zone-travel-spec §5.4）：在目标 zone 选一台 gate、为这名玩家签一张重定向票据。
 *
 * <p><b>{@link #selectTravelTarget}</b>（源 scene 给 226 调）：
 * <ol>
 *   <li>参数：{@code from_zone_id} / {@code to_zone_id} / {@code player_id} 为 0、或两个 zone 相同 → {@link SceneAssigner#TIP_BAD_REQUEST}
 *       （scene 已先挡过 3024，到这里是调用方编程错误）；</li>
 *   <li>地图预检：{@code conf = want ≠ 0 ? want : 默认主世界}，{@code want} 不是世界地图 → {@link SceneAssigner#TIP_BAD_REQUEST}（纵深防御，scene 已回 3007）。
 *       读目标 zone 的频道计划快照（{@link WorldChannelStore#snapshot}，批次 5.1），有任一 {@code scene_config_id == conf} 的主世界频道记录
 *       （ACTIVE 或 DRAINING 都算）就放行，没有 → {@link SceneAssigner#TIP_NO_SCENE}。只看「这张图在这个 zone 开没开」：不解析实例、不预占；
 *       过渡窗口里频道暂时不可用照常放行，第二条腿由 {@link SceneAssigner} 回落默认主世界。<b>计划读失败 → future 以异常完成</b>
 *       （X20：基线读失败放行；Java 的计划与 gate 目录在同一个 Redis，读不到计划时 gate 目录同样读不到，放行只会把失败推迟到交出之后）；</li>
 *   <li>合服围栏检查点 F4（{@link ZoneMergeFence}；7.3 之前装配的是恒放行的 {@link ZoneMergeFence#OPEN}）：命中或读不到 → {@link #TIP_ZONE_FENCED}；</li>
 *   <li>选 gate：{@link GateSource#listGates}（目录读不到 → 异常完成）→ {@link GatePicker#pick}（剔除连不上的、同一客户端地址只留最新启动的、
 *       剔除排空中的但全部排空时忽略标记、按人数随机二选一，2026-10-18 起）；没有 → {@link #TIP_NO_GATE}；</li>
 *   <li>签票据：{@link GateTokenIssuer#issueRedirect}（{@code gate_node_id}、{@code zone_id} = gate 所在 zone、有效期 300 s、持票者、
 *       {@code target_zone_id} = 目标 zone，不带会话密钥），应答带解析后的地图与 {@link ZoneRedirect}（地址取 gate 的客户端通告地址）。</li>
 * </ol>
 *
 * <p><b>{@link #redirectToZone}</b>（login 的登录期重定向 GO-5 调）：参数校验同上；目标 zone 的节点目录里没有任何可用的 scene 节点
 * （{@link SceneAssigner#isUsable}）→ {@link SceneAssigner#TIP_NO_SCENE}（否则会把人送到一个落不了地的 zone；目录读不到 → 异常完成）；
 * 然后同上选 gate、签票据。<b>不查地图、不查围栏</b>（只送连接，同基线 {@code !leavingZone}）。
 *
 * <p>契约（{@link TravelRouting}）：业务拒绝走 {@code tip_id}（都是 scene-manager 的内部码，只进日志与指标）；基础设施失败以异常完成，
 * 异常只带概要（完整堆栈留在本进程日志，Redis 地址等内部细节不外发）。只读、无副作用（唯一的「副作用」是签名）：不铸 epoch、不写位置记录、
 * 不碰归属、不预占。在 Dubbo 业务线程上同步阻塞读 Redis（同 {@link SceneAssigner}，提供方超时 3 s），返回已完成的 future。无可变状态，线程安全。
 *
 * <p>指标（zone-travel-spec §7.2，Micrometer）：每次调用按结果恰好计一次耗时——{@value #TRAVEL_METRIC}{@code {result}}
 * （Prometheus {@code game_scene_manager_travel_seconds}，结果见 {@link TravelResult}）与 {@value #REDIRECT_METRIC}{@code {result}}
 * （{@code game_scene_manager_redirect_seconds}，结果见 {@link RedirectResult}）。全部序列在构造时预注册；不带 zone / 玩家 / 节点 / 地图维度。
 */
public final class TravelRouter implements TravelRouting {

    private static final Logger log = LoggerFactory.getLogger(TravelRouter.class);

    static final String TRAVEL_METRIC = "game.scene_manager.travel";
    static final String REDIRECT_METRIC = "game.scene_manager.redirect";

    /**
     * 目标 zone 没有可连的 gate（目录里没有条目，或条目都连不上）。scene-manager 的内部码，只进日志与指标：scene 对任何非 0 一律推 23 {3027}，
     * login 回落为在入口 zone 首登。选 {@code kEnterNodeUnavailable(3003)}：scene_error 段里「要进的节点不可用」，与 3000 / 3005 分得开。
     */
    public static final int TIP_NO_GATE = SceneErrorTip.scene_error.kEnterNodeUnavailable_VALUE;

    /**
     * 合服围栏封锁（检查点 F4，命中或读不到；批次 7.3 之前装配恒放行，不会出现）。同样只是内部码；取 {@code kZoneTravelTargetBusy(3027)}——
     * 正是 scene 随后推给客户端的那个码（基线 SM 回私有码 21，源 scene 收敛成 23 {3027}）。
     */
    public static final int TIP_ZONE_FENCED = SceneErrorTip.scene_error.kZoneTravelTargetBusy_VALUE;

    /** 延迟直方图的桶边界：频道计划一段只读 Lua + gate 目录一次读 + 排空标记一次 MGET，固定 8 个，覆盖 1ms～1s（Dubbo 调用超时 3s）。 */
    private static final Duration[] LATENCY_BUCKETS = {
            Duration.ofMillis(1), Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofMillis(25),
            Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofSeconds(1)};

    /** 一次 {@link #selectTravelTarget} 的结局（{@value #TRAVEL_METRIC}{@code {result}}）。 */
    enum TravelResult {
        OK,
        /** 目标 zone 没有可连的 gate（{@link #TIP_NO_GATE}）。 */
        NO_GATE,
        /** 目标 zone 的频道计划里没有这张图（{@link SceneAssigner#TIP_NO_SCENE}）。 */
        NO_MAP,
        /** 合服围栏封锁（{@link #TIP_ZONE_FENCED}；7.3 之前恒为 0，序列照样预注册）。 */
        FENCED,
        /** 参数不合法（{@link SceneAssigner#TIP_BAD_REQUEST}）。 */
        BAD_REQUEST,
        /** 频道计划 / gate 目录读不到，或签名意外失败：调用失败。 */
        ERROR
    }

    /** 一次 {@link #redirectToZone} 的结局（{@value #REDIRECT_METRIC}{@code {result}}）。 */
    enum RedirectResult {
        OK,
        /** 目标 zone 没有可连的 gate（{@link #TIP_NO_GATE}）。 */
        NO_GATE,
        /** 目标 zone 没有可用的 scene 节点（{@link SceneAssigner#TIP_NO_SCENE}）。 */
        NO_SCENE,
        /** 参数不合法（{@link SceneAssigner#TIP_BAD_REQUEST}）。 */
        BAD_REQUEST,
        /** scene 节点目录 / gate 目录读不到，或签名意外失败：调用失败。 */
        ERROR
    }

    private final WorldChannelStore plans;
    private final SceneNodeSource scenes;
    private final GateSource gates;
    private final WorldSceneConfigs worldConfigs;
    private final ZoneMergeFence fence;
    private final com.game.common.player.HomeZones homeZones;
    private final GateTokenIssuer issuer;
    private final MeterRegistry meterRegistry;
    private final Map<TravelResult, Timer> travelTimers = new EnumMap<>(TravelResult.class);
    private final Map<RedirectResult, Timer> redirectTimers = new EnumMap<>(RedirectResult.class);

    /**
     * @param plans        频道计划（只用 {@link WorldChannelStore#snapshot} 读目标 zone）
     * @param scenes       scene 节点目录（只给 {@link #redirectToZone} 判目标 zone 有没有可用节点）
     * @param gates        gate 目录（叠加了排空标记）
     * @param worldConfigs 世界地图集合与默认主世界
     * @param fence        合服围栏的读侧（检查点 F4；7.3 之前是 {@link ZoneMergeFence#OPEN}），在 Dubbo 业务线程上同步调用
     * @param issuer       票据签发（gate 令牌密钥 {@code GAME_GATE_TOKEN_SECRET}，与 gateway / gate 同值）
     */
    public TravelRouter(WorldChannelStore plans, SceneNodeSource scenes, GateSource gates, WorldSceneConfigs worldConfigs,
                        ZoneMergeFence fence, GateTokenIssuer issuer, MeterRegistry meterRegistry) {
        this(plans,scenes,gates,worldConfigs,fence,issuer,meterRegistry,null);
    }
    public TravelRouter(WorldChannelStore plans,SceneNodeSource scenes,GateSource gates,WorldSceneConfigs worldConfigs,ZoneMergeFence fence,GateTokenIssuer issuer,MeterRegistry meterRegistry,com.game.common.player.HomeZones homeZones){
        this.plans = Objects.requireNonNull(plans, "plans");
        this.scenes = Objects.requireNonNull(scenes, "scenes");
        this.gates = Objects.requireNonNull(gates, "gates");
        this.worldConfigs = Objects.requireNonNull(worldConfigs, "worldConfigs");
        this.fence = Objects.requireNonNull(fence, "fence");
        this.homeZones=homeZones;
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        for (TravelResult result : TravelResult.values()) {
            travelTimers.put(result, timer(TRAVEL_METRIC, "跨 zone 传送（226）选目标的结果与耗时（地图预检 + 选 gate + 签票据）",
                    result.name()));
        }
        for (RedirectResult result : RedirectResult.values()) {
            redirectTimers.put(result, timer(REDIRECT_METRIC, "登录期重定向（GO-5）选目标的结果与耗时（scene 节点预检 + 选 gate + 签票据）",
                    result.name()));
        }
    }

    private Timer timer(String name, String description, String result) {
        return Timer.builder(name)
                .description(description)
                .tag("result", result.toLowerCase(Locale.ROOT))
                .serviceLevelObjectives(LATENCY_BUCKETS)
                .register(meterRegistry);
    }

    @Override
    public CompletableFuture<SelectTravelTargetResponse> selectTravelTarget(SelectTravelTargetRequest request) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            TravelOutcome outcome = travel(request);
            sample.stop(travelTimers.get(outcome.result()));
            return CompletableFuture.completedFuture(outcome.response());
        } catch (RuntimeException e) {
            sample.stop(travelTimers.get(TravelResult.ERROR));
            log.error("跨 zone 传送选目标失败：频道计划或 gate 目录不可用 from={} to={} player={} want={}", request.getFromZoneId(),
                    request.getToZoneId(), Long.toUnsignedString(request.getPlayerId()), request.getWantSceneConfigId(), e);
            return CompletableFuture.failedFuture(unavailable(e));
        }
    }

    @Override
    public CompletableFuture<RedirectToZoneResponse> redirectToZone(RedirectToZoneRequest request) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            RedirectOutcome outcome = redirect(request);
            sample.stop(redirectTimers.get(outcome.result()));
            return CompletableFuture.completedFuture(outcome.response());
        } catch (RuntimeException e) {
            sample.stop(redirectTimers.get(RedirectResult.ERROR));
            log.error("登录期重定向选目标失败：scene 节点目录或 gate 目录不可用 from={} to={} player={}", request.getFromZoneId(),
                    request.getToZoneId(), Long.toUnsignedString(request.getPlayerId()), e);
            return CompletableFuture.failedFuture(unavailable(e));
        }
    }

    /** 交给调用方的异常只带概要（同 {@code SceneDirectoryProvider} 的做法）。 */
    private static IllegalStateException unavailable(RuntimeException e) {
        return new IllegalStateException("跨 zone 选路暂不可用: " + e.getClass().getSimpleName());
    }

    private record TravelOutcome(TravelResult result, SelectTravelTargetResponse response) {
    }

    private record RedirectOutcome(RedirectResult result, RedirectToZoneResponse response) {
    }

    /** 226 选目标的规则（基础设施异常原样上抛，由 {@link #selectTravelTarget} 转成调用失败）。 */
    private TravelOutcome travel(SelectTravelTargetRequest request) {
        int from = request.getFromZoneId();
        int to = request.getToZoneId();
        long playerId = request.getPlayerId();
        int want = request.getWantSceneConfigId();
        if (badZones(from, to, playerId)) {
            log.warn("跨 zone 传送选目标请求参数不合法 from={} to={} player={} want={}",
                    from, to, Long.toUnsignedString(playerId), want);
            return travelRejected(TravelResult.BAD_REQUEST, SceneAssigner.TIP_BAD_REQUEST);
        }
        if (want != 0 && !worldConfigs.isWorld(want)) {
            log.warn("跨 zone 传送的目标地图不是世界地图（scene 应已回 3007） from={} to={} player={} want={}",
                    from, to, Long.toUnsignedString(playerId), want);
            return travelRejected(TravelResult.BAD_REQUEST, SceneAssigner.TIP_BAD_REQUEST);
        }
        int conf = want != 0 ? want : worldConfigs.defaultConfigId();
        if (!mapOpen(to, conf)) {
            log.info("跨 zone 传送拒绝：目标 zone 的频道计划里没有这张图 from={} to={} player={} conf={}",
                    from, to, Long.toUnsignedString(playerId), conf);
            return travelRejected(TravelResult.NO_MAP, SceneAssigner.TIP_NO_SCENE);
        }
        if (fenced(request)) {
            return travelRejected(TravelResult.FENCED, TIP_ZONE_FENCED);
        }
        Optional<Ticket> ticket = pickAndSign(to, playerId);
        if (ticket.isEmpty()) {
            log.warn("跨 zone 传送拒绝：目标 zone 没有可连的 gate from={} to={} player={} conf={}",
                    from, to, Long.toUnsignedString(playerId), conf);
            return travelRejected(TravelResult.NO_GATE, TIP_NO_GATE);
        }
        log.info("跨 zone 传送已选目标 from={} to={} player={} conf={} gate={} addr={}:{} deadline={}",
                from, to, Long.toUnsignedString(playerId), conf, ticket.get().gate().getNodeId(),
                ticket.get().gate().getClientHost(), ticket.get().gate().getClientPort(), ticket.get().redirect().getTokenDeadline());
        return new TravelOutcome(TravelResult.OK, SelectTravelTargetResponse.newBuilder()
                .setSceneConfigId(conf)
                .setRedirect(ticket.get().redirect())
                .build());
    }

    /** GO-5 选目标的规则（基础设施异常原样上抛，由 {@link #redirectToZone} 转成调用失败）。 */
    private RedirectOutcome redirect(RedirectToZoneRequest request) {
        int from = request.getFromZoneId();
        int to = request.getToZoneId();
        long playerId = request.getPlayerId();
        if (badZones(from, to, playerId)) {
            log.warn("登录期重定向选目标请求参数不合法 from={} to={} player={}", from, to, Long.toUnsignedString(playerId));
            return redirectRejected(RedirectResult.BAD_REQUEST, SceneAssigner.TIP_BAD_REQUEST);
        }
        if (scenes.list(to).stream().noneMatch(node -> SceneAssigner.isUsable(node, to))) {
            log.warn("登录期重定向拒绝：目标 zone 没有可用的 scene 节点 from={} to={} player={}",
                    from, to, Long.toUnsignedString(playerId));
            return redirectRejected(RedirectResult.NO_SCENE, SceneAssigner.TIP_NO_SCENE);
        }
        Optional<Ticket> ticket = pickAndSign(to, playerId);
        if (ticket.isEmpty()) {
            log.warn("登录期重定向拒绝：目标 zone 没有可连的 gate from={} to={} player={}", from, to, Long.toUnsignedString(playerId));
            return redirectRejected(RedirectResult.NO_GATE, TIP_NO_GATE);
        }
        log.info("登录期重定向已选目标 from={} to={} player={} gate={} addr={}:{} deadline={}",
                from, to, Long.toUnsignedString(playerId), ticket.get().gate().getNodeId(), ticket.get().gate().getClientHost(),
                ticket.get().gate().getClientPort(), ticket.get().redirect().getTokenDeadline());
        return new RedirectOutcome(RedirectResult.OK, RedirectToZoneResponse.newBuilder()
                .setRedirect(ticket.get().redirect())
                .build());
    }

    /** 两种请求共用的参数校验：三个号都不能为 0，目标 zone 不能是来源 zone。 */
    private static boolean badZones(int from, int to, long playerId) {
        return from == 0 || to == 0 || from == to || playerId == 0;
    }

    /** 目标 zone 的频道计划里有没有这张图的主世界频道记录（ACTIVE 或 DRAINING 都算）。读失败原样上抛。 */
    private boolean mapOpen(int zoneId, int conf) {
        for (WorldChannel channel : plans.snapshot(zoneId).channels().values()) {
            if (channel.getSceneConfigId() == conf && ChannelKinds.isWorldChannel(channel.getKind())
                    && (channel.getState() == ChannelState.CHANNEL_ACTIVE || channel.getState() == ChannelState.CHANNEL_DRAINING)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 合服围栏检查点 F4：判归属区，命中或读不到都按封锁（fail-closed，{@link ZoneMergeFence} 的契约）。
     *
     * <p>7.3 之前请求里没有归属区（{@code SelectTravelTargetRequest} 的字段 5 预留给 {@code home_zone_id}），按围栏契约传 0（没有 zone 可判）；
     * 装配的又是恒放行的 {@link ZoneMergeFence#OPEN}，检查点先立着、客户端所见零差别。7.3 加字段后改判 {@code request.getHomeZoneId()}，并把 0
     * 当作「读不到」（zone-merge-spec §2.6：0 只可能来自旧版 scene）。
     */
    private boolean fenced(SelectTravelTargetRequest request) {
        int homeZone = 0;
        try {
            if(homeZones!=null){homeZone=homeZones.homeZoneOf(request.getPlayerId(),com.game.common.deadline.Deadline.after(1500));if(homeZone==0)return true;}
            if (fence.inProgress(homeZone)||homeZones!=null&&(fence.inProgress(request.getFromZoneId())||fence.inProgress(request.getToZoneId()))) {
                log.warn("跨 zone 传送拒绝：归属区正在合服 from={} to={} player={} home={}", request.getFromZoneId(),
                        request.getToZoneId(), Long.toUnsignedString(request.getPlayerId()), homeZone);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.error("跨 zone 传送拒绝：合服围栏读不到，按封锁处理 from={} to={} player={} home={}", request.getFromZoneId(),
                    request.getToZoneId(), Long.toUnsignedString(request.getPlayerId()), homeZone, e);
            if (e instanceof InterruptedException) {
                // Dubbo 业务线程被中断（关停）：照样按封锁拒绝，中断标记留给线程池。
                Thread.currentThread().interrupt();
            }
            return true;
        }
    }

    /** 选中的 gate 与给它签的票据。 */
    private record Ticket(GateNodeInfo gate, ZoneRedirect redirect) {
    }

    /** 在目标 zone 选 gate 并签票据；没有可连的 gate 为空。目录读失败原样上抛。 */
    private Optional<Ticket> pickAndSign(int toZone, long playerId) {
        List<GateNodeInfo> candidates = gates.listGates(toZone);
        Optional<GateNodeInfo> picked = GatePicker.pick(toZone, candidates);
        if (picked.isEmpty()) {
            log.debug("目标 zone 没有可连的 gate zone={} 目录条目数={}", toZone, candidates.size());
            return Optional.empty();
        }
        GateNodeInfo gate = picked.get();
        IssuedGateToken token = issuer.issueRedirect(gate.getNodeId(), gate.getZoneId(), playerId, toZone);
        ZoneRedirect redirect = ZoneRedirect.newBuilder()
                .setTargetZoneId(toZone)
                .setGateNodeId(gate.getNodeId())
                .setGateHost(gate.getClientHost())
                .setGatePort(gate.getClientPort())
                // 签名时的原字节，原样放进应答（下游一律原样拷贝，不得解析后重新序列化）
                .setTokenPayload(token.payload())
                .setTokenSignature(token.signature())
                .setTokenDeadline(token.deadlineEpochSec())
                .build();
        return Optional.of(new Ticket(gate, redirect));
    }

    private static TravelOutcome travelRejected(TravelResult result, int tipId) {
        return new TravelOutcome(result, SelectTravelTargetResponse.newBuilder().setTipId(tipId).build());
    }

    private static RedirectOutcome redirectRejected(RedirectResult result, int tipId) {
        return new RedirectOutcome(result, RedirectToZoneResponse.newBuilder().setTipId(tipId).build());
    }
}
