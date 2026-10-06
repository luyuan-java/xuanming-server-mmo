package com.game.match.testing;

import com.game.api.proto.SceneNodeInfo;
import com.game.api.rpc.NodeRpcClients;
import com.game.discovery.location.SceneAssetLocator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * scene 节点目录（{@code xm:nodes:scene:<zone>}）的测试替身：{@link SceneAssetLocator.SceneNodeLookup} 的内存实现。与 {@link FakePlayerStatus#holderAsync}
 * 一起喂给 {@code SceneAssetLocator}，就能在不连 Redis 的情况下测「位置记录 → 按 (zone, 节点号) 找 scene → 直连地址」：
 *
 * <pre>
 * FakeSceneNodes sceneNodes = new FakeSceneNodes();
 * sceneNodes.add(1, 7, "scene-inst-a", 21100);              // zone 1 的 7 号节点
 * sceneNodes.add(2, 7, "scene-inst-b", 21101);              // zone 2 的同号节点：按 (zone, 节点号) 找，不得串
 * sceneNodes.set(SceneNodeInfo…setRpcPort(0)…);              // 不提供战斗入口的旧节点
 * sceneNodes.failZone(1);                                    // 读 zone 1 的目录失败
 * SceneAssetLocator locator = new SceneAssetLocator(players::holderAsync, sceneNodes, null);
 * assertThat(sceneNodes.lookups).containsExactly("1:7");
 * </pre>
 */
public final class FakeSceneNodes implements SceneAssetLocator.SceneNodeLookup {

    /** 每次查目录的 {@code "<zone>:<节点号>"}，按调用顺序。 */
    public final List<String> lookups = new CopyOnWriteArrayList<>();
    private final Map<String, SceneNodeInfo> entries = new ConcurrentHashMap<>();
    private final Map<Integer, Boolean> failingZones = new ConcurrentHashMap<>();

    /** 登记一个提供战斗入口的 scene 节点（直连地址 {@code 127.0.0.1:rpcPort}）。 */
    public FakeSceneNodes add(int zoneId, int nodeId, String instanceId, int rpcPort) {
        return set(SceneNodeInfo.newBuilder().setZoneId(zoneId).setNodeId(nodeId).setInstanceId(instanceId).setRpcHost("127.0.0.1")
                .setRpcPort(rpcPort).build());
    }

    /** 登记 / 覆盖一条任意形状的条目。 */
    public FakeSceneNodes set(SceneNodeInfo info) {
        entries.put(info.getZoneId() + ":" + info.getNodeId(), info);
        return this;
    }

    public FakeSceneNodes remove(int zoneId, int nodeId) {
        entries.remove(zoneId + ":" + nodeId);
        return this;
    }

    public FakeSceneNodes failZone(int zoneId) {
        failingZones.put(zoneId, true);
        return this;
    }

    /** 这个节点的直连目标（与 {@code SceneAssetLocator} 解析出来的地址对应；配 {@link FakeNodeCalls#register} 用）。 */
    public NodeRpcClients.Target targetOf(int zoneId, int nodeId) {
        SceneNodeInfo info = entries.get(zoneId + ":" + nodeId);
        if (info == null) {
            throw new IllegalArgumentException("没有登记的 scene 节点: " + zoneId + ":" + nodeId);
        }
        return new NodeRpcClients.Target(info.getRpcHost(), info.getRpcPort(), info.getInstanceId());
    }

    @Override
    public CompletableFuture<Optional<SceneNodeInfo>> findAsync(int zoneId, int nodeId) {
        lookups.add(zoneId + ":" + nodeId);
        if (failingZones.containsKey(zoneId)) {
            return CompletableFuture.failedFuture(new IllegalStateException("注入的故障: 读 scene 节点目录 zone=" + zoneId));
        }
        return CompletableFuture.completedFuture(Optional.ofNullable(entries.get(zoneId + ":" + nodeId)));
    }
}
