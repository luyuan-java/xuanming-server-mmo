package com.game.battle.admin;

import com.game.api.DubboGroups;
import com.game.api.SceneBattleService;
import com.game.api.asset.SceneAssetEndpoint;
import com.game.api.proto.SceneBattleCall;
import com.game.api.proto.SceneBattleReply;
import com.game.api.proto.SceneNodeInfo;
import com.game.api.rpc.NodeRpcClients;
import com.game.battle.BattleNode;
import com.game.discovery.NodeDirectory;
import com.game.discovery.NodeTypes;
import com.game.discovery.location.PlayerLocationDirectory;
import com.game.discovery.location.SceneAssetLocator;
import com.game.discovery.location.SceneAssetLocator.Resolution;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * dev gather 接口（{@link DevGatherController}）的装配（scene-battle-spec §7.18）：定位用 {@link SceneAssetLocator}（位置记录只认 {@code o} + scene 目录），
 * 调 scene 用自己的 {@link NodeRpcClients}{@code <SceneBattleService>}（Dubbo 模型惰性创建：prod 下接口回 403，从不建引用、不开连接）。
 * 与 {@code BattleConfiguration} 分开放，免得 dev 专用的部件混进节点的主装配。
 */
@Configuration(proxyBeanMethods = false)
public class DevGatherConfiguration {

    /** Dubbo 应用名（进 URL，便于在 scene 日志里认出 dev gather）。 */
    static final String APPLICATION = "xm-battle-dev-gather";

    /** dev gather 调 scene 的直连客户端缓存（停机时销毁）。 */
    @Bean(destroyMethod = "close")
    public NodeRpcClients<SceneBattleService> devGatherSceneClients() {
        return new NodeRpcClients<>(APPLICATION, SceneBattleService.class, DubboGroups.SCENE_BATTLE, DevGather.SCENE_CALL_TIMEOUT,
                "dev-gather-connect");
    }

    @Bean
    public DevGather devGather(RedissonClient redis, NodeRpcClients<SceneBattleService> devGatherSceneClients) {
        SceneAssetLocator locator = new SceneAssetLocator(new PlayerLocationDirectory(redis),
                new NodeDirectory<>(redis, NodeTypes.SCENE, SceneNodeInfo.parser()), null);
        return new DevGather(new DevGather.Scenes() {
            @Override
            public CompletableFuture<Resolution> locate(long playerId) {
                return locator.resolveAsync(playerId);
            }

            @Override
            public CompletableFuture<SceneBattleReply> prepare(SceneAssetEndpoint endpoint, SceneBattleCall call, Duration timeout) {
                return devGatherSceneClients.call(target(endpoint), timeout, service -> service.prepareBattle(call));
            }

            @Override
            public CompletableFuture<SceneBattleReply> cancel(SceneAssetEndpoint endpoint, SceneBattleCall call, Duration timeout) {
                return devGatherSceneClients.call(target(endpoint), timeout, service -> service.cancelBattlePrepare(call));
            }
        }, System::currentTimeMillis);
    }

    /** 节点在运行时给出控制面与本节点号。 */
    @Bean
    public DevGatherController.Node devGatherNode(BattleNode battleNode) {
        return () -> battleNode.controlPlane().flatMap(plane -> battleNode.identity()
                .map(identity -> new DevGatherController.Running(plane, identity.nodeId())));
    }

    static NodeRpcClients.Target target(SceneAssetEndpoint endpoint) {
        return new NodeRpcClients.Target(endpoint.host(), endpoint.port(), endpoint.instanceId());
    }
}
