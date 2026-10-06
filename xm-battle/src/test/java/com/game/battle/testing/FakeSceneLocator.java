package com.game.battle.testing;

import com.game.api.asset.SceneAssetEndpoint;
import com.game.discovery.location.SceneAssetLocator.Failure;
import com.game.discovery.location.SceneAssetLocator.Found;
import com.game.discovery.location.SceneAssetLocator.NoHolder;
import com.game.discovery.location.SceneAssetLocator.Resolution;
import com.game.discovery.location.SceneAssetLocator.ResolveResult;
import com.game.discovery.proto.PlayerLocation;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 假定位器（生产 = {@code SceneAssetLocator.resolveAsync}）：按玩家编排「此刻的持有者」，缺省没人持有（{@code NoHolder(NOT_ONLINE)}，离线玩家的常态）。
 * 两个被测类的定位端口形状相同，都用方法引用 {@code locator::locate} 接上。完成时机与方式由 {@link #locates} 编排；调用进 {@link CallJournal}
 * （{@code locate:<pid>}）。线程安全。
 */
public final class FakeSceneLocator {

    public final Scripted<Resolution> locates = new Scripted<>();

    private final CallJournal journal;
    private final Map<Long, Resolution> where = new ConcurrentHashMap<>();

    public FakeSceneLocator(CallJournal journal) {
        this.journal = journal;
    }

    /** 一个 scene 节点的直连地址：{@code 10.<zone>.0.<node>:21100}（zone / node 取低 8 位拼地址，只为各节点地址不同）。 */
    public static SceneAssetEndpoint endpoint(int zoneId, int nodeId, String instanceId) {
        return new SceneAssetEndpoint(zoneId, nodeId, instanceId, "10." + (zoneId & 0xFF) + ".0." + (nodeId & 0xFF), 21100);
    }

    /** 玩家此刻由这个节点持有；返回它的直连地址。 */
    public SceneAssetEndpoint online(long playerId, int zoneId, int nodeId, String instanceId) {
        SceneAssetEndpoint endpoint = endpoint(zoneId, nodeId, instanceId);
        where.put(playerId, new Found(PlayerLocation.newBuilder().setPlayerId(playerId).setZoneId(zoneId).setSceneNodeId(nodeId).build(),
                endpoint));
        return endpoint;
    }

    /** 玩家此刻没有持有者（离线 / 重连租约 / 登出墓碑 / 节点不认识）。 */
    public void offline(long playerId, ResolveResult why) {
        where.put(playerId, new NoHolder(why));
    }

    public void offline(long playerId) {
        offline(playerId, ResolveResult.NOT_ONLINE);
    }

    /** 定位故障（位置记录 / 目录读不出来）。 */
    public void broken(long playerId, String reason) {
        where.put(playerId, new Failure(reason));
    }

    /** 直接给一个定位结果（可以是形状不对的，用来测防护）。 */
    public void resolveTo(long playerId, Resolution resolution) {
        where.put(playerId, resolution);
    }

    /** 定位端口本体。 */
    public CompletableFuture<Resolution> locate(long playerId) {
        String label = "locate:" + Long.toUnsignedString(playerId);
        journal.add(label);
        return locates.invoke(label, () -> where.getOrDefault(playerId, new NoHolder(ResolveResult.NOT_ONLINE)));
    }
}
