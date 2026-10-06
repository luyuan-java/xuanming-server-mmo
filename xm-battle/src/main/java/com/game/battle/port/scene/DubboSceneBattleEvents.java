package com.game.battle.port.scene;

import com.game.api.asset.SceneAssetEndpoint;
import com.game.api.proto.SceneBattleCall;
import com.game.api.proto.SceneBattleReply;
import com.game.api.proto.SceneBattleStatus;
import com.game.api.proto.SceneNodeInfo;
import com.game.battle.metrics.BattleMetrics;
import com.game.battle.metrics.BattleMetrics.SceneEventKind;
import com.game.battle.metrics.BattleMetrics.SceneEventResult;
import com.game.battle.port.SceneBattleEvents;
import com.game.discovery.location.SceneAssetLocator.Found;
import com.game.discovery.location.SceneAssetLocator.Resolution;
import com.game.proto.BattleConfirmedEvent;
import com.game.proto.BattleRouting;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 确认事件的真实传输（scene-battle-spec §7.16；battle-node-spec §7.9 修订）：按快照路由的 {@code (zone_id, scene_node_id)} 查 scene 目录，
 * 目录里的实例等于 {@code routing.scene_instance_id} 就发往它（正常路径，等价基线 Kafka 的实例过滤）；<b>实例不符</b>（备战所在的 scene 进程已重启 / 下线）
 * → 回落到定位器（D28）：Found 就按它的地址与实例 id 发（{@code rerouted}），NoHolder / 出错 → 不发（{@code skipped}）。
 *
 * <p>{@code SceneBattleService.confirmBattle} 异步发出、不等结果，只计数；补发节奏仍是 6.2 房间的 17 次。回调不碰房间状态，可以在任意线程上完成。
 * dev 房间（{@code origin = DEV}）照常发确认：scene 侧按锁匹配，dev 房间的 battle_id 不会命中任何锁，零副作用（Q12）。线程安全、不阻塞、不抛异常。
 */
public final class DubboSceneBattleEvents implements SceneBattleEvents {

    private static final Logger log = LoggerFactory.getLogger(DubboSceneBattleEvents.class);

    /** scene 节点目录的单条读（生产 = {@code NodeDirectory.findAsync}）。 */
    @FunctionalInterface
    public interface Directory {
        CompletableFuture<Optional<SceneNodeInfo>> find(int zoneId, int nodeId);
    }

    /** 玩家此刻的持有者（生产 = {@code SceneAssetLocator.resolveAsync}）。 */
    @FunctionalInterface
    public interface Locator {
        CompletableFuture<Resolution> locate(long playerId);
    }

    /** 发一次确认（生产 = {@code NodeRpcClients<SceneBattleService>}）；future 异常完成 = 传输失败。 */
    @FunctionalInterface
    public interface Transport {
        CompletableFuture<SceneBattleReply> confirm(SceneAssetEndpoint endpoint, SceneBattleCall call);
    }

    private final Directory directory;
    private final Locator locator;
    private final Transport transport;
    private final BattleMetrics metrics;

    public DubboSceneBattleEvents(Directory directory, Locator locator, Transport transport, BattleMetrics metrics) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.locator = Objects.requireNonNull(locator, "locator");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public void confirm(BattleRouting routing, long playerId, long battleId, long deadlineMs) {
        byte[] body = BattleConfirmedEvent.newBuilder()
                .setBattleId(battleId)
                .setPlayerId(playerId)
                .setDeadlineMs(deadlineMs)
                .build()
                .toByteArray();
        CompletableFuture<Optional<SceneNodeInfo>> entry;
        if (routing.getSceneNodeId() == 0 || routing.getZoneId() == 0 || routing.getSceneInstanceId().isEmpty()) {
            entry = CompletableFuture.completedFuture(Optional.empty());
        } else {
            try {
                entry = directory.find(routing.getZoneId(), routing.getSceneNodeId());
            } catch (RuntimeException e) {
                entry = CompletableFuture.failedFuture(e);
            }
        }
        entry.whenComplete((found, error) -> {
            SceneNodeInfo info = error == null && found != null ? found.orElse(null) : null;
            if (info != null && info.getInstanceId().equals(routing.getSceneInstanceId()) && info.getRpcPort() != 0
                    && !info.getRpcHost().isBlank()) {
                send(new SceneAssetEndpoint(info.getZoneId(), info.getNodeId(), info.getInstanceId(), info.getRpcHost(),
                        info.getRpcPort()), playerId, battleId, body, SceneEventResult.SENT);
                return;
            }
            reroute(playerId, battleId, body);
        });
    }

    /** D28：快照路由的实例已不在，问定位器（只认在线位置记录 + 目录）。 */
    private void reroute(long playerId, long battleId, byte[] body) {
        CompletableFuture<Resolution> located;
        try {
            located = locator.locate(playerId);
        } catch (RuntimeException e) {
            located = CompletableFuture.failedFuture(e);
        }
        located.whenComplete((resolution, error) -> {
            if (error == null && resolution instanceof Found found) {
                send(found.endpoint(), playerId, battleId, body, SceneEventResult.REROUTED);
                return;
            }
            metrics.sceneEvent(SceneEventKind.CONFIRM, SceneEventResult.SKIPPED);
            if (log.isDebugEnabled()) {
                log.debug("确认事件不发：快照路由的实例已不在、定位器也找不到持有者 battle_id={} player={} 定位={}", Long.toUnsignedString(battleId),
                        Long.toUnsignedString(playerId), error != null ? error.toString() : resolution);
            }
        });
    }

    private void send(SceneAssetEndpoint endpoint, long playerId, long battleId, byte[] body, SceneEventResult how) {
        SceneBattleCall call = SceneBattleCall.newBuilder()
                .setTargetInstanceId(endpoint.instanceId())
                .setPlayerId(playerId)
                .setBody(com.google.protobuf.ByteString.copyFrom(body))
                .build();
        metrics.sceneEvent(SceneEventKind.CONFIRM, how);
        CompletableFuture<SceneBattleReply> sent;
        try {
            sent = transport.confirm(endpoint, call);
        } catch (RuntimeException e) {
            sent = CompletableFuture.failedFuture(e);
        }
        sent.whenComplete((reply, error) -> {
            if (error != null || reply == null || reply.getStatus() == SceneBattleStatus.SCENE_BATTLE_STATUS_UNSPECIFIED) {
                metrics.sceneEvent(SceneEventKind.CONFIRM, SceneEventResult.ERROR);
                if (log.isDebugEnabled()) {
                    log.debug("确认事件传输失败（由下一次补发覆盖） battle_id={} player={} node={}: {}", Long.toUnsignedString(battleId),
                            Long.toUnsignedString(playerId), endpoint.nodeId(), error);
                }
            } else if (reply.getStatus() == SceneBattleStatus.SCENE_BATTLE_NOT_HERE) {
                metrics.sceneEvent(SceneEventKind.CONFIRM, SceneEventResult.NOT_HERE);
            }
        });
    }
}
