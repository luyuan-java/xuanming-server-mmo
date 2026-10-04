package com.game.scene.location;

import com.game.discovery.location.PlayerLocationDirectory;
import com.game.discovery.location.PlayerLocationDirectory.Refresh;
import com.game.discovery.proto.PlayerLocation;
import com.game.scene.world.PlayerLocations;
import com.game.scene.world.ScenePlayer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletionStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 玩家位置写进 Redis（{@link PlayerLocationDirectory}）。方法在场景逻辑线程上调用：在逻辑线程上读完玩家状态、取好写序号、
 * 组好消息，再异步发出，不阻塞；失败只记告警（位置只是 login 选场景的提示，写不上时重连 / 顶号按首登落默认主世界）。
 */
public final class RedisPlayerLocations implements PlayerLocations {

    private static final Logger log = LoggerFactory.getLogger(RedisPlayerLocations.class);

    private final PlayerLocationDirectory directory;
    private final int zoneId;
    private final int sceneNodeId;

    public RedisPlayerLocations(PlayerLocationDirectory directory, int zoneId, int sceneNodeId) {
        this.directory = directory;
        this.zoneId = zoneId;
        this.sceneNodeId = sceneNodeId;
    }

    @Override
    public void entered(ScenePlayer player) {
        watch(directory.putAsync(locationOf(player), player.nextLocationSeq()), "写入", player.playerId());
    }

    @Override
    public void disconnected(ScenePlayer player) {
        watch(directory.leaseAsync(locationOf(player), player.nextLocationSeq()), "转成重连租约", player.playerId());
    }

    @Override
    public void loggedOut(ScenePlayer player) {
        watch(directory.removeAsync(player.playerId(), player.ownerEpoch(), player.nextLocationSeq()), "登出",
                player.playerId());
    }

    @Override
    public void loggedOutWhileLoading(long playerId, long ownerEpoch) {
        // 这次进场还没写过记录，序号从 1 起即可：它的 epoch 比任何更早的记录都新
        watch(directory.removeAsync(playerId, ownerEpoch, 1), "登出（加载中）", playerId);
    }

    @Override
    public void refresh(Collection<ScenePlayer> players) {
        if (players.isEmpty()) {
            return;
        }
        List<Refresh> refreshes = new ArrayList<>(players.size());
        for (ScenePlayer player : players) {
            refreshes.add(new Refresh(locationOf(player), player.locationSeq()));
        }
        directory.refreshAsync(refreshes).whenComplete((restored, failure) -> {
            if (failure != null) {
                log.warn("玩家位置续期失败 人数={}: {}", refreshes.size(), failure.toString());
            } else if (restored > 0) {
                log.warn("玩家位置续期时补回了丢失的记录 条数={}", restored);
            }
        });
    }

    /** 玩家此刻（离场后为最后所在）的场景实例。 */
    private PlayerLocation locationOf(ScenePlayer player) {
        return PlayerLocation.newBuilder()
                .setPlayerId(player.playerId())
                .setZoneId(zoneId)
                .setSceneNodeId(sceneNodeId)
                .setSceneId(player.scene().sceneId())
                .setSceneConfigId(player.scene().configId())
                .setOwnerEpoch(player.ownerEpoch())
                .build();
    }

    private static void watch(CompletionStage<Boolean> stage, String what, long playerId) {
        stage.whenComplete((done, failure) -> {
            if (failure != null) {
                log.warn("玩家位置{}失败 player={}: {}", what, Long.toUnsignedString(playerId), failure.toString());
            } else if (!done) {
                log.debug("玩家位置{}没生效（已有更新的写） player={}", what, Long.toUnsignedString(playerId));
            }
        });
    }
}
