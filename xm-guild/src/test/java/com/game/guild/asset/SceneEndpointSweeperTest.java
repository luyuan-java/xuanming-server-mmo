package com.game.guild.asset;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.asset.SceneAssetEndpoint;
import com.game.api.asset.SceneAssetOpClients;
import com.game.api.proto.SceneNodeInfo;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 资产通道客户端缓存清扫：目录里消失 / 换实例 / 不再提供资产通道的节点被销毁；读目录失败的 zone 本轮不动。 */
class SceneEndpointSweeperTest {

    private final SceneAssetOpClients clients = new SceneAssetOpClients("xm-guild-sweeper-test");
    private final Map<Integer, List<SceneNodeInfo>> directory = new ConcurrentHashMap<>();

    @AfterEach
    void close() {
        clients.close();
    }

    private static SceneNodeInfo info(int zone, int node, String instance, int port) {
        return SceneNodeInfo.newBuilder().setZoneId(zone).setNodeId(node).setInstanceId(instance).setRpcHost("10.0.0." + node)
                .setRpcPort(port).build();
    }

    private static SceneAssetEndpoint endpoint(int zone, int node, String instance) {
        return new SceneAssetEndpoint(zone, node, instance, "10.0.0." + node, 21100);
    }

    @Test
    void 不在目录_换实例_不再提供资产通道的节点被清掉_读失败的zone不动() {
        SceneEndpointSweeper sweeper = new SceneEndpointSweeper(zone -> {
            if (zone == 3) {
                throw new IllegalStateException("redis down");
            }
            return directory.getOrDefault(zone, List.of());
        }, clients);
        sweeper.track(endpoint(1, 1, "a"));
        sweeper.track(endpoint(1, 2, "b"));
        sweeper.track(endpoint(1, 3, "c"));
        sweeper.track(endpoint(1, 4, "d"));
        sweeper.track(endpoint(3, 5, "e"));
        directory.put(1, List.of(info(1, 1, "a", 21100), info(1, 2, "b2", 21100), info(1, 3, "c", 0)));

        assertThat(sweeper.sweep()).isEqualTo(3);
        assertThat(sweeper.trackedCount()).isEqualTo(2);
        assertThat(sweeper.sweep()).isZero();
    }
}
