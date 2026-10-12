package com.game.battle.admin;

import com.game.api.proto.SceneNodeInfo;
import com.game.discovery.location.PlayerLocationDirectory.HolderRead;
import com.game.discovery.location.PlayerLocationDirectory.LocationStatus;
import com.game.discovery.proto.PlayerLocation;
import com.game.discovery.proto.PlayerPresence;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** {@link DevRoutingResolver.Lookups} 的内存假实现：按表回答，{@link #failure} 非 null 时每次读都抛它。线程安全。 */
final class FakeLookups implements DevRoutingResolver.Lookups {

    final Map<Long, PlayerPresence> presences = new ConcurrentHashMap<>();
    final Map<Long, HolderRead> locations = new ConcurrentHashMap<>();
    final Map<String, SceneNodeInfo> scenes = new ConcurrentHashMap<>();
    volatile RuntimeException failure;

    /** 登记一名在线、在场景里的玩家：gate 会话 + 位置记录（o）+ scene 目录条目。 */
    FakeLookups online(long playerId, int zone, int gateNode, String gateInstance, int session, int sceneNode, String sceneInstance) {
        presences.put(playerId, PlayerPresence.newBuilder().setPlayerId(playerId).setZoneId(zone).setGateNodeId(gateNode)
                .setGateInstanceId(gateInstance).setSessionId(session).build());
        locations.put(playerId, new HolderRead(LocationStatus.ONLINE, PlayerLocation.newBuilder().setPlayerId(playerId)
                .setZoneId(zone).setSceneNodeId(sceneNode).setSceneId(99).build(), null));
        scenes.put(zone + "/" + sceneNode, SceneNodeInfo.newBuilder().setZoneId(zone).setNodeId(sceneNode)
                .setInstanceId(sceneInstance).build());
        return this;
    }

    void clear() {
        presences.clear();
        locations.clear();
        scenes.clear();
        failure = null;
    }

    @Override
    public Optional<PlayerPresence> presence(long playerId) {
        if (failure != null) {
            throw failure;
        }
        return Optional.ofNullable(presences.get(playerId));
    }

    @Override
    public HolderRead location(long playerId) {
        if (failure != null) {
            throw failure;
        }
        return locations.getOrDefault(playerId, new HolderRead(LocationStatus.MISSING, null, null));
    }

    @Override
    public Optional<SceneNodeInfo> sceneNode(int zoneId, int nodeId) {
        if (failure != null) {
            throw failure;
        }
        return Optional.ofNullable(scenes.get(zoneId + "/" + nodeId));
    }
}
