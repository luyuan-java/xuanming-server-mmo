package com.game.team.push;

import com.game.api.proto.SceneTeamRefresh;
import com.game.common.deadline.Deadline;
import com.game.discovery.location.SceneAssetLocator;
import java.time.Duration;

/** 重用 scene 持有者定位，直连同实例 RPC；提醒至多一次，重进场/显式归队会重新强读。 */
public final class DubboSceneTeamSignals implements SceneTeamSignals {
    private final SceneAssetLocator locator;
    private final SceneTeamEndpointSweeper clients;

    public DubboSceneTeamSignals(SceneAssetLocator locator, SceneTeamEndpointSweeper clients) {
        this.locator = locator;
        this.clients = clients;
    }

    @Override
    public void refresh(long playerId, boolean follow, Deadline deadline) {
        if (deadline.expired()) return;
        var resolved = deadline.await(locator.resolveAsync(playerId), "组队场景定位");
        if (resolved instanceof SceneAssetLocator.NoHolder) return;
        if (resolved instanceof SceneAssetLocator.Failure failure) {
            throw new Deadline.DependencyException(failure.reason());
        }
        var endpoint = ((SceneAssetLocator.Found) resolved).endpoint();
        if (deadline.expired()) return;
        var request = SceneTeamRefresh.newBuilder().setPlayerId(playerId)
                .setTargetInstanceId(endpoint.instanceId()).setFollow(follow).build();
        var reply = deadline.await(clients.call(endpoint,
                Duration.ofMillis(deadline.remainingMillis()), service -> service.refresh(request)), "组队场景刷新");
        if (!reply.getAccepted()) throw new Deadline.DependencyException("目标实例未接受组队刷新提示");
    }
}
