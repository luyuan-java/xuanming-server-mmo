package com.game.scenemanager;

import com.game.api.SceneDirectoryService;
import com.game.api.proto.AssignSceneRequest;
import com.game.api.proto.AssignSceneResponse;
import com.game.api.proto.ChannelKind;
import com.game.api.proto.CreateInstanceRequest;
import com.game.api.proto.CreateInstanceResponse;
import com.game.api.proto.RedirectToZoneRequest;
import com.game.api.proto.RedirectToZoneResponse;
import com.game.api.proto.SelectSwitchTargetRequest;
import com.game.api.proto.SelectSwitchTargetResponse;
import com.game.api.proto.SelectTravelTargetRequest;
import com.game.api.proto.SelectTravelTargetResponse;
import com.game.scenemanager.travel.TravelRouting;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link SceneDirectoryService} 的 Dubbo 提供方（Triple，无 group / version）。只做协议适配，规则在 {@link SceneAssigner}（进游戏）、
 * {@link SwitchTargetSelector}（在线换图选跨节点目标，批次 5.2）、{@link InstanceIdIssuer}（镜像 / 副本实例取号，批次 5.3）
 * 与 {@link TravelRouting}（跨 zone 传送选目标、登录期重定向，批次 5.4：选 gate、签票据；这两个方法的计时与指标在它那边）。
 *
 * <p>分配与选目标都只读一次 Redis（外加至多一次软预占），实例取号不碰 Redis；都直接在 Dubbo 业务线程上同步算完再返回已完成的 future
 * （不占 Netty I/O 线程）。业务拒绝走 {@code tip_id}；场景目录读不到（Redis 故障等）或发号租约无效时 future 以异常完成，表示「调用失败」
 * 而不是「没有场景」。异常只带概要，完整堆栈留在本进程日志：内部细节（Redis 地址等）不外发给调用方。
 *
 * <p>指标（architecture.md §11）：每次分配按结果计耗时 {@code xm.scene_manager.assign{result}}，结果只有 {@link AssignResult} 这几种；
 * 每次换图选目标按结果计耗时 {@code xm.scene_manager.switch{result}}（Prometheus 名 {@code xm_scene_manager_switch_seconds}，
 * scene-handoff-spec §7.2），结果 = {@link SwitchTargetSelector.Result} 加 {@code error}；每次实例取号按种类与结果计耗时
 * {@code xm.scene_manager.instance{kind, result}}（{@code xm_scene_manager_instance_seconds}，dungeon-mirror-spec §8.2），
 * {@code kind} = mirror / dungeon / other（请求里的种类不合法），{@code result} = {@link InstanceIdIssuer.Result} 加 {@code error}。
 * 都不带 zone / 玩家 / 节点 / 场景维度。
 */
@DubboService
public class SceneDirectoryProvider implements SceneDirectoryService {

    private static final Logger log = LoggerFactory.getLogger(SceneDirectoryProvider.class);

    static final String ASSIGN_METRIC = "xm.scene_manager.assign";
    static final String SWITCH_METRIC = "xm.scene_manager.switch";
    static final String INSTANCE_METRIC = "xm.scene_manager.instance";

    /** {@code xm.scene_manager.switch} 在调用失败（目录 / 预占存储不可用）时的结果标签。 */
    static final String SWITCH_ERROR = "error";
    /** {@code xm.scene_manager.instance} 在取号抛出意外异常时的结果标签（发号租约无效另计 {@code no_lease}）。 */
    static final String INSTANCE_ERROR = "error";
    /** {@code xm.scene_manager.instance} 的种类标签：请求里的种类不是 MIRROR / DUNGEON（参数错）。 */
    static final String KIND_OTHER = "other";

    /** 延迟直方图的桶边界：一次 Redis 读（加至多一次预占），固定 8 个，覆盖 1ms～1s（Dubbo 调用超时 3s）。 */
    private static final Duration[] LATENCY_BUCKETS = {
            Duration.ofMillis(1), Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofMillis(25),
            Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofSeconds(1)};

    /** 一次分配的结果（{@code xm.scene_manager.assign{result}}）。 */
    enum AssignResult {
        OK,
        /** 没有可分配的场景（{@link SceneAssigner#TIP_NO_SCENE}）。 */
        NO_SCENE,
        /** 请求参数不合法（{@link SceneAssigner#TIP_BAD_REQUEST}）。 */
        BAD_REQUEST,
        /** 其他业务拒绝码（预留，现在的规则不会产生）。 */
        REJECTED,
        /** 场景目录不可读：调用失败。 */
        ERROR
    }

    private final SceneAssigner assigner;
    private final SwitchTargetSelector switchSelector;
    private final InstanceIdIssuer instanceIssuer;
    private final TravelRouting travelRouting;
    private final MeterRegistry meterRegistry;
    private final Map<AssignResult, Timer> timers = new EnumMap<>(AssignResult.class);
    private final Map<SwitchTargetSelector.Result, Timer> switchTimers = new EnumMap<>(SwitchTargetSelector.Result.class);
    private final Timer switchErrorTimer;
    /** 种类标签 → 结果标签 → 计时器（3 × 5 个，启动时全部注册，序列稳定）。 */
    private final Map<String, Map<String, Timer>> instanceTimers = new HashMap<>();

    /**
     * @param travelRouting 跨 zone 的两种选路（批次 5.4：226 传送选目标、登录期重定向），两个新方法原样委托给它
     */
    public SceneDirectoryProvider(SceneAssigner assigner, SwitchTargetSelector switchSelector,
                                  InstanceIdIssuer instanceIssuer, TravelRouting travelRouting,
                                  MeterRegistry meterRegistry) {
        this.assigner = assigner;
        this.switchSelector = switchSelector;
        this.instanceIssuer = instanceIssuer;
        this.travelRouting = Objects.requireNonNull(travelRouting, "travelRouting");
        this.meterRegistry = meterRegistry;
        for (AssignResult result : AssignResult.values()) {
            timers.put(result, Timer.builder(ASSIGN_METRIC)
                    .description("场景分配的结果与耗时（含一次场景目录读取）")
                    .tag("result", result.name().toLowerCase(Locale.ROOT))
                    .serviceLevelObjectives(LATENCY_BUCKETS)
                    .register(meterRegistry));
        }
        for (SwitchTargetSelector.Result result : SwitchTargetSelector.Result.values()) {
            switchTimers.put(result, switchTimer(result.name().toLowerCase(Locale.ROOT)));
        }
        switchErrorTimer = switchTimer(SWITCH_ERROR);
        for (String kind : List.of(kindTag(ChannelKind.CHANNEL_KIND_MIRROR), kindTag(ChannelKind.CHANNEL_KIND_DUNGEON), KIND_OTHER)) {
            Map<String, Timer> byResult = new HashMap<>();
            for (InstanceIdIssuer.Result result : InstanceIdIssuer.Result.values()) {
                String tag = result.name().toLowerCase(Locale.ROOT);
                byResult.put(tag, instanceTimer(kind, tag));
            }
            byResult.put(INSTANCE_ERROR, instanceTimer(kind, INSTANCE_ERROR));
            instanceTimers.put(kind, Map.copyOf(byResult));
        }
    }

    private Timer instanceTimer(String kind, String result) {
        return Timer.builder(INSTANCE_METRIC)
                .description("镜像 / 副本实例取号的结果与耗时（无 Redis 读写，只发号）")
                .tag("kind", kind)
                .tag("result", result)
                .serviceLevelObjectives(LATENCY_BUCKETS)
                .register(meterRegistry);
    }

    /** 指标的种类标签：mirror / dungeon，其余（参数错）归 {@link #KIND_OTHER}。 */
    static String kindTag(ChannelKind kind) {
        return switch (kind) {
            case CHANNEL_KIND_MIRROR -> "mirror";
            case CHANNEL_KIND_DUNGEON -> "dungeon";
            default -> KIND_OTHER;
        };
    }

    private Timer switchTimer(String result) {
        return Timer.builder(SWITCH_METRIC)
                .description("在线换图选跨节点目标的结果与耗时（含一次场景目录读取与软预占）")
                .tag("result", result)
                .serviceLevelObjectives(LATENCY_BUCKETS)
                .register(meterRegistry);
    }

    @Override
    public CompletableFuture<AssignSceneResponse> assign(AssignSceneRequest request) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            AssignSceneResponse response = assigner.assign(request);
            sample.stop(timers.get(resultOf(response)));
            return CompletableFuture.completedFuture(response);
        } catch (RuntimeException e) {
            sample.stop(timers.get(AssignResult.ERROR));
            log.error("场景分配失败：场景目录不可读 zone={} player={}", request.getZoneId(), request.getPlayerId(), e);
            return CompletableFuture.failedFuture(
                    new IllegalStateException("场景目录暂不可用: " + e.getClass().getSimpleName()));
        }
    }

    /**
     * 在线换图选跨节点目标（scene-handoff-spec §5.4）。业务拒绝（无场景 / 排空中 / 参数错）是正常应答；
     * 目录或预占存储不可用时 future 以异常完成（scene 推 23 {1003}），异常同样只带概要。
     */
    @Override
    public CompletableFuture<SelectSwitchTargetResponse> selectSwitchTarget(SelectSwitchTargetRequest request) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            SwitchTargetSelector.Selection selection = switchSelector.select(request);
            sample.stop(switchTimers.get(selection.result()));
            return CompletableFuture.completedFuture(selection.response());
        } catch (RuntimeException e) {
            sample.stop(switchErrorTimer);
            log.error("换图选目标失败：场景目录或预占存储不可用 zone={} player={}", request.getZoneId(), request.getPlayerId(), e);
            return CompletableFuture.failedFuture(
                    new IllegalStateException("场景目录暂不可用: " + e.getClass().getSimpleName()));
        }
    }

    /**
     * 镜像 / 副本实例取号（dungeon-mirror-spec §6.6）。参数错与发起节点不接新实例是正常应答（{@code tip_id}）；发号租约无效（Q12）
     * 与意外异常以异常完成 future（scene 推 23 {1003}），异常只带概要。
     */
    @Override
    public CompletableFuture<CreateInstanceResponse> createInstance(CreateInstanceRequest request) {
        Timer.Sample sample = Timer.start(meterRegistry);
        Map<String, Timer> byResult = instanceTimers.get(kindTag(request.getKind()));
        try {
            InstanceIdIssuer.Issue issue = instanceIssuer.issue(request);
            sample.stop(byResult.get(issue.result().name().toLowerCase(Locale.ROOT)));
            if (issue.result() == InstanceIdIssuer.Result.NO_LEASE) {
                return CompletableFuture.failedFuture(new IllegalStateException("scene_id 发号租约暂不可用"));
            }
            return CompletableFuture.completedFuture(issue.response());
        } catch (RuntimeException e) {
            sample.stop(byResult.get(INSTANCE_ERROR));
            log.error("实例取号失败 zone={} node={} player={} kind={}", request.getZoneId(), request.getRequesterSceneNodeId(),
                    request.getPlayerId(), request.getKindValue(), e);
            return CompletableFuture.failedFuture(new IllegalStateException("实例取号暂不可用: " + e.getClass().getSimpleName()));
        }
    }

    /**
     * 跨 zone 传送选目标（批次 5.4）：原样委托给 {@link TravelRouting}（规则、计时、指标、把读失败转成异常完成都在它那边）。
     * 这里只兜一件事：实现违反契约同步抛出或返回 null 时，仍以异常完成的 future 交回——Dubbo 调用方看到的总是「调用失败」，
     * 不会是一次没有应答的调用。
     */
    @Override
    public CompletableFuture<SelectTravelTargetResponse> selectTravelTarget(SelectTravelTargetRequest request) {
        return guarded("selectTravelTarget", () -> travelRouting.selectTravelTarget(request));
    }

    /** 登录期重定向选目标（批次 5.4 的 GO-5）：同 {@link #selectTravelTarget}，原样委托。 */
    @Override
    public CompletableFuture<RedirectToZoneResponse> redirectToZone(RedirectToZoneRequest request) {
        return guarded("redirectToZone", () -> travelRouting.redirectToZone(request));
    }

    private static <R> CompletableFuture<R> guarded(String method, Supplier<CompletableFuture<R>> call) {
        try {
            CompletableFuture<R> future = call.get();
            if (future == null) {
                log.error("{} 的实现返回了 null（违反 TravelRouting 的契约），按调用失败交回", method);
                return CompletableFuture.failedFuture(new IllegalStateException("跨 zone 选路暂不可用"));
            }
            return future;
        } catch (RuntimeException e) {
            // 完整堆栈留在本进程日志，交给调用方的异常只带概要（同 assign / selectSwitchTarget 的做法）
            log.error("{} 的实现同步抛出（违反 TravelRouting 的契约），按调用失败交回", method, e);
            return CompletableFuture.failedFuture(new IllegalStateException("跨 zone 选路暂不可用"));
        }
    }

    static AssignResult resultOf(AssignSceneResponse response) {
        int tip = response.getTipId();
        if (tip == 0) {
            return AssignResult.OK;
        }
        if (tip == SceneAssigner.TIP_NO_SCENE) {
            return AssignResult.NO_SCENE;
        }
        if (tip == SceneAssigner.TIP_BAD_REQUEST) {
            return AssignResult.BAD_REQUEST;
        }
        return AssignResult.REJECTED;
    }
}
