package com.game.scenemanager;

import com.game.api.SceneDirectoryService;
import com.game.api.proto.AssignSceneRequest;
import com.game.api.proto.AssignSceneResponse;
import com.game.api.proto.SelectSwitchTargetRequest;
import com.game.api.proto.SelectSwitchTargetResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link SceneDirectoryService} 的 Dubbo 提供方（Triple，无 group / version）。只做协议适配，规则在 {@link SceneAssigner}（进游戏）
 * 与 {@link SwitchTargetSelector}（在线换图选跨节点目标，批次 5.2）。
 *
 * <p>两种调用都只读一次 Redis（外加至多一次软预占），直接在 Dubbo 业务线程上同步算完再返回已完成的 future（不占 Netty I/O 线程）。
 * 业务拒绝走 {@code tip_id}；场景目录读不到（Redis 故障等）时 future 以异常完成，表示「调用失败」而不是「没有场景」。
 * 异常只带概要，完整堆栈留在本进程日志：内部细节（Redis 地址等）不外发给调用方。
 *
 * <p>指标（architecture.md §11）：每次分配按结果计耗时 {@code xm.scene_manager.assign{result}}，结果只有 {@link AssignResult} 这几种；
 * 每次换图选目标按结果计耗时 {@code xm.scene_manager.switch{result}}（Prometheus 名 {@code xm_scene_manager_switch_seconds}，
 * scene-handoff-spec §7.2），结果 = {@link SwitchTargetSelector.Result} 加 {@code error}。都不带 zone / 玩家 / 节点 / 场景维度。
 */
@DubboService
public class SceneDirectoryProvider implements SceneDirectoryService {

    private static final Logger log = LoggerFactory.getLogger(SceneDirectoryProvider.class);

    static final String ASSIGN_METRIC = "xm.scene_manager.assign";
    static final String SWITCH_METRIC = "xm.scene_manager.switch";

    /** {@code xm.scene_manager.switch} 在调用失败（目录 / 预占存储不可用）时的结果标签。 */
    static final String SWITCH_ERROR = "error";

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
    private final MeterRegistry meterRegistry;
    private final Map<AssignResult, Timer> timers = new EnumMap<>(AssignResult.class);
    private final Map<SwitchTargetSelector.Result, Timer> switchTimers = new EnumMap<>(SwitchTargetSelector.Result.class);
    private final Timer switchErrorTimer;

    public SceneDirectoryProvider(SceneAssigner assigner, SwitchTargetSelector switchSelector, MeterRegistry meterRegistry) {
        this.assigner = assigner;
        this.switchSelector = switchSelector;
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
