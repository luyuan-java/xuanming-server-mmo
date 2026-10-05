package com.game.scenemanager.world;

import com.game.api.proto.ChannelState;
import com.game.api.proto.WorldChannel;
import com.game.discovery.world.WorldChannelStore;
import com.game.discovery.world.WorldPlan;
import com.game.scenemanager.SceneNodeSource;
import com.game.scenemanager.WorldSceneConfigs;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;

/**
 * 健康组件 {@code worldChannels}（scene-channels-spec §5.2）：对 {@code xm:world:zones} 里每个<b>有活节点</b>的 zone，每张 World 图都至少有 1 个
 * 「计划里 ACTIVE、节点在场、目录里已建出且没在排空」的频道才 UP——即 login 现在来分配一定分得到。没有活节点的 zone 不算
 * （scene 还没起来时 scene-manager 照样 UP，本机脚本先起 scene-manager 再起 scene）。不进 liveness：频道没铺好不该让进程被重启。
 *
 * <p>每次探测读一次 zone 集合、每个 zone 一次目录与一次计划（只读，在管理端口的请求线程上）；Redis 出错由基类报 DOWN。
 */
public final class WorldChannelsHealthIndicator extends AbstractHealthIndicator {

    private final WorldChannelStore store;
    private final SceneNodeSource directory;
    private final WorldSceneConfigs worldConfigs;

    public WorldChannelsHealthIndicator(WorldChannelStore store, SceneNodeSource directory, WorldSceneConfigs worldConfigs) {
        super("主世界频道健康检查失败");
        this.store = store;
        this.directory = directory;
        this.worldConfigs = worldConfigs;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        Map<String, Object> missingByZone = new LinkedHashMap<>();
        int zonesWithNodes = 0;
        for (int zoneId : store.zones()) {
            DirectoryView view = DirectoryView.of(zoneId, directory.list(zoneId));
            if (view.nodes().isEmpty()) {
                continue;
            }
            zonesWithNodes++;
            List<Integer> missing = missingConfs(store.readPlan(zoneId), view, worldConfigs);
            if (!missing.isEmpty()) {
                missingByZone.put(Integer.toUnsignedString(zoneId), missing);
            }
        }
        builder.withDetail("zonesWithNodes", zonesWithNodes);
        if (missingByZone.isEmpty()) {
            builder.up();
        } else {
            builder.down().withDetail("mapsWithoutChannel", missingByZone);
        }
    }

    /** 没有任何可分配频道的 World 图（表序）。 */
    static List<Integer> missingConfs(WorldPlan plan, DirectoryView view, WorldSceneConfigs worldConfigs) {
        Set<Integer> ready = new TreeSet<>(Integer::compareUnsigned);
        for (WorldChannel channel : plan.channels()) {
            if (channel.getState() != ChannelState.CHANNEL_ACTIVE) {
                continue;
            }
            DirectoryView.Node node = view.node(channel.getNodeId());
            if (node != null && node.hostsActive(channel.getSceneId())) {
                ready.add(channel.getSceneConfigId());
            }
        }
        List<Integer> missing = new ArrayList<>();
        for (int conf : worldConfigs.orderedConfigIds()) {
            if (!ready.contains(conf)) {
                missing.add(conf);
            }
        }
        return missing;
    }
}
