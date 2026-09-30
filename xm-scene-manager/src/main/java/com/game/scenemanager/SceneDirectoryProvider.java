package com.game.scenemanager;

import com.game.api.SceneDirectoryService;
import com.game.api.proto.AssignSceneRequest;
import com.game.api.proto.AssignSceneResponse;
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
 * {@link SceneDirectoryService} 的 Dubbo 提供方（Triple，无 group / version）。只做协议适配，规则在 {@link SceneAssigner}。
 *
 * <p>分配只读一次 Redis，直接在 Dubbo 业务线程上同步算完再返回已完成的 future（不占 Netty I/O 线程）。
 * 业务拒绝走 {@code tip_id}；场景目录读不到（Redis 故障等）时 future 以异常完成，表示「调用失败」而不是「没有场景」。
 * 异常只带概要，完整堆栈留在本进程日志：内部细节（Redis 地址等）不外发给调用方。
 *
 * <p>指标（architecture.md §11）：每次分配按结果计耗时 {@code xm.scene_manager.assign{result}}，
 * 结果只有 {@link AssignResult} 这几种，不带 zone / 玩家 / 节点维度。
 */
@DubboService
public class SceneDirectoryProvider implements SceneDirectoryService {

    private static final Logger log = LoggerFactory.getLogger(SceneDirectoryProvider.class);

    static final String ASSIGN_METRIC = "xm.scene_manager.assign";

    /** 延迟直方图的桶边界：一次 Redis 读，固定 8 个，覆盖 1ms～1s（Dubbo 调用超时 3s）。 */
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
    private final MeterRegistry meterRegistry;
    private final Map<AssignResult, Timer> timers = new EnumMap<>(AssignResult.class);

    public SceneDirectoryProvider(SceneAssigner assigner, MeterRegistry meterRegistry) {
        this.assigner = assigner;
        this.meterRegistry = meterRegistry;
        for (AssignResult result : AssignResult.values()) {
            timers.put(result, Timer.builder(ASSIGN_METRIC)
                    .description("场景分配的结果与耗时（含一次场景目录读取）")
                    .tag("result", result.name().toLowerCase(Locale.ROOT))
                    .serviceLevelObjectives(LATENCY_BUCKETS)
                    .register(meterRegistry));
        }
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
