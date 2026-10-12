package com.game.guild.asset;

import com.game.api.asset.SceneAssetEndpoint;
import com.game.api.asset.SceneAssetOpClients;
import com.game.api.proto.SceneNodeInfo;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 资产通道客户端缓存的清扫（基线 {@code svc/asset_op.go:166-170}：scene 节点从 etcd 镜像消失时关掉它的连接，{@code scenenode/conn.go:69-79}）。
 * Java 没有目录 watcher：定位每找到一个节点就登记它（{@link #track}），清扫时按这些节点所在的 zone 现读 Redis 节点目录，目录里已经没有、换了实例、
 * 或不再提供资产通道（rpc_port = 0）的，就从 {@link SceneAssetOpClients} 里销毁——留着只会让 Dubbo 对一个已经没人听的地址反复重连。
 * 同地址换实例时客户端缓存自己会重建，这里只管「消失」。正确性不依赖它（找不到节点 = 本地 NOT_HERE；连不上 = 传输失败重投）。
 *
 * <p>{@link #sweep} 阻塞读 Redis（每个 zone 一次 HGETALL），只在资产通道的后台调度线程上调；{@link #track} 任意线程可调、不阻塞。线程安全。
 */
public final class SceneEndpointSweeper {

    private static final Logger log = LoggerFactory.getLogger(SceneEndpointSweeper.class);

    private final IntFunction<List<SceneNodeInfo>> directory;
    private final SceneAssetOpClients clients;
    /** address → 最近一次定位到的节点。 */
    private final Map<String, SceneAssetEndpoint> tracked = new ConcurrentHashMap<>();

    /**
     * @param directory 按 zone 列出 scene 节点目录（生产 {@code NodeDirectory<SceneNodeInfo>::list}）
     */
    public SceneEndpointSweeper(IntFunction<List<SceneNodeInfo>> directory, SceneAssetOpClients clients) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.clients = Objects.requireNonNull(clients, "clients");
    }

    /** 定位找到一个节点（调用方在每次 Found 时调）。 */
    public void track(SceneAssetEndpoint endpoint) {
        tracked.put(endpoint.address(), endpoint);
    }

    /** 当前登记的节点数（测试 / 排障用）。 */
    public int trackedCount() {
        return tracked.size();
    }

    /** 清扫一轮；读目录失败的 zone 本轮跳过（不误删）。返回销毁的条数。 */
    public int sweep() {
        Map<Integer, Set<String>> liveByZone = new HashMap<>();
        int evicted = 0;
        for (SceneAssetEndpoint endpoint : List.copyOf(tracked.values())) {
            Set<String> live = liveByZone.get(endpoint.zoneId());
            if (live == null && !liveByZone.containsKey(endpoint.zoneId())) {
                live = readZone(endpoint.zoneId());
                liveByZone.put(endpoint.zoneId(), live);
            }
            if (live == null) {
                continue;
            }
            if (!live.contains(endpoint.address() + "#" + endpoint.instanceId())) {
                tracked.remove(endpoint.address(), endpoint);
                clients.evict(endpoint);
                evicted++;
                log.info("[AssetOp] scene 节点已不在目录（或换了实例 / 不再提供资产通道），销毁客户端 zone={} node={} address={}",
                        endpoint.zoneId(), endpoint.nodeId(), endpoint.address());
            }
        }
        return evicted;
    }

    /** 某 zone 里提供资产通道的节点（address#instance）；读失败为 null。 */
    private Set<String> readZone(int zoneId) {
        try {
            Set<String> live = new HashSet<>();
            for (SceneNodeInfo info : directory.apply(zoneId)) {
                if (info.getRpcPort() == 0 || info.getRpcHost().isBlank() || Integer.compareUnsigned(info.getRpcPort(), 65535) > 0) {
                    continue;
                }
                SceneAssetEndpoint e = new SceneAssetEndpoint(info.getZoneId(), info.getNodeId(), info.getInstanceId(),
                        info.getRpcHost(), info.getRpcPort());
                live.add(e.address() + "#" + e.instanceId());
            }
            return live;
        } catch (RuntimeException e) {
            log.warn("[AssetOp] 读 scene 节点目录失败，本轮不清扫 zone={}: {}", Integer.toUnsignedString(zoneId), e.toString());
            return null;
        }
    }
}
