package com.game.scenemanager;

import com.game.api.SceneDirectoryService;
import com.game.api.proto.AssignSceneRequest;
import com.game.api.proto.AssignSceneResponse;
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
 */
@DubboService
public class SceneDirectoryProvider implements SceneDirectoryService {

    private static final Logger log = LoggerFactory.getLogger(SceneDirectoryProvider.class);

    private final SceneAssigner assigner;

    public SceneDirectoryProvider(SceneAssigner assigner) {
        this.assigner = assigner;
    }

    @Override
    public CompletableFuture<AssignSceneResponse> assign(AssignSceneRequest request) {
        try {
            return CompletableFuture.completedFuture(assigner.assign(request));
        } catch (RuntimeException e) {
            log.error("场景分配失败：场景目录不可读 zone={} player={}", request.getZoneId(), request.getPlayerId(), e);
            return CompletableFuture.failedFuture(
                    new IllegalStateException("场景目录暂不可用: " + e.getClass().getSimpleName()));
        }
    }
}
