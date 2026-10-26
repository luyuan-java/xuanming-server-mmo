package com.game.scene.team;

import com.game.api.SceneTeamService;
import com.game.api.proto.SceneTeamRefresh;
import com.game.api.proto.SceneTeamRefreshReply;
import com.game.scene.world.SceneWorld;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/** 内部组队提示入口：实例围栏、有界排队，世界状态只在逻辑线程访问；accepted 仅代表成功排队。 */
public final class SceneTeamProvider implements SceneTeamService {

    static final int MAX_QUEUED = 1024;
    private final Supplier<SceneWorld> world;
    private final String instanceId;
    private final Executor logic;
    private final Semaphore queued = new Semaphore(MAX_QUEUED);

    public SceneTeamProvider(Supplier<SceneWorld> world, String instanceId, Executor logic) {
        this.world = Objects.requireNonNull(world, "world");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.logic = Objects.requireNonNull(logic, "logic");
    }

    @Override
    public CompletableFuture<SceneTeamRefreshReply> refresh(SceneTeamRefresh request) {
        if (request == null || request.getPlayerId() == 0 || request.getTargetInstanceId().isEmpty()
                || !instanceId.equals(request.getTargetInstanceId()) || !queued.tryAcquire()) {
            return CompletableFuture.completedFuture(reply(false));
        }
        try {
            logic.execute(() -> {
                queued.release();
                SceneWorld current = world.get();
                if (current != null) current.refreshTeam(request.getPlayerId(), request.getFollow());
            });
        } catch (RejectedExecutionException error) {
            queued.release();
            return CompletableFuture.failedFuture(error);
        }
        return CompletableFuture.completedFuture(reply(true));
    }

    private static SceneTeamRefreshReply reply(boolean accepted) {
        return SceneTeamRefreshReply.newBuilder().setAccepted(accepted).build();
    }
}
