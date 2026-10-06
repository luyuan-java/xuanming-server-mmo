package com.game.scene.rpc;

import com.game.api.DubboGroups;
import com.game.api.SceneAssetOpService;
import com.game.api.SceneBattleService;
import com.game.api.asset.IsolatedDubboModule;
import java.util.ArrayList;
import java.util.List;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * scene 节点的 Dubbo 提供方导出（原 {@code SceneAssetRpcServer}，6.3 起一个端口导出两个服务；guild-economy-spec §4.6、scene-battle-spec §7.3）：
 * 协议 Triple、一个 {@link IsolatedDubboModule}、一个 {@link ProtocolConfig}，两个 {@link ServiceConfig}——资产通道
 * {@link SceneAssetOpService}（group {@link DubboGroups#SCENE_ASSET}）与回合制战斗入口 {@link SceneBattleService}
 * （group {@link DubboGroups#SCENE_BATTLE}），都是 <b>{@code register = false}</b>（地址只来自 Redis 节点目录 {@code SceneNodeInfo.rpc_host / rpc_port}）、
 * 无注册中心。配置键（{@code xm.scene.asset-rpc-port}）与 Dubbo 应用名（{@value #APPLICATION}）不改，避免运维配置与指标序列变动。
 *
 * <p>编程式导出（自己的 Dubbo 模型），不用 {@code @DubboService}：导出 / 反导出必须排进 {@code SceneNode} 的启停顺序——启动时导出成功之后才把
 * rpc 地址写进目录；停服时摘目录之后、逻辑线程停之前才反导出。
 *
 * <p>端口：同机多实例必须各不相同（同 link-port 惯例）；被占用即导出失败、节点拒绝启动。Dubbo 不接受回环绑定地址（architecture.md §4.1），
 * 通告地址用 {@code xm.advertise-host}，实际监听全部网卡——生产用 {@code DUBBO_IP_TO_BIND} 绑内网网卡并配防火墙；调用方 MAC（{@code XM_DUBBO_SECRET}）鉴权。
 */
public final class SceneRpcServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SceneRpcServer.class);

    /** Dubbo 应用名（进 URL，便于在调用方日志里认出 scene；沿用资产通道的名字）。 */
    static final String APPLICATION = "xm-scene-asset";
    static final String PROTOCOL = "tri";

    private final IsolatedDubboModule dubbo;
    private final List<ServiceConfig<?>> services;
    private final int port;

    private SceneRpcServer(IsolatedDubboModule dubbo, List<ServiceConfig<?>> services, int port) {
        this.dubbo = dubbo;
        this.services = services;
        this.port = port;
    }

    /**
     * 导出（阻塞到端口开好）。失败（端口被占、缺 {@code XM_DUBBO_SECRET} 等）抛 {@link IllegalStateException}，已建的 Dubbo 模型一并销毁。
     *
     * @param battle 回合制战斗入口；null = 只导出资产通道（测试）
     * @param host   通告地址（写进 Dubbo URL；与目录里的 rpc_host 相同）
     * @param port   端口（1..65535）
     */
    public static SceneRpcServer export(SceneAssetOpService asset, SceneBattleService battle, String host, int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("scene RPC 端口超出范围: " + port);
        }
        IsolatedDubboModule dubbo = IsolatedDubboModule.create(APPLICATION);
        List<ServiceConfig<?>> exported = new ArrayList<>();
        try {
            ProtocolConfig protocol = new ProtocolConfig(PROTOCOL, port);
            protocol.setHost(host);
            exported.add(exportOne(dubbo, protocol, SceneAssetOpService.class, asset, DubboGroups.SCENE_ASSET));
            if (battle != null) {
                exported.add(exportOne(dubbo, protocol, SceneBattleService.class, battle, DubboGroups.SCENE_BATTLE));
            }
            log.info("scene Dubbo 提供方已导出 {}://{}:{} groups={} register=false", PROTOCOL, host, port,
                    battle != null ? DubboGroups.SCENE_ASSET + "," + DubboGroups.SCENE_BATTLE : DubboGroups.SCENE_ASSET);
            return new SceneRpcServer(dubbo, List.copyOf(exported), port);
        } catch (RuntimeException e) {
            for (ServiceConfig<?> service : exported) {
                try {
                    service.unexport();
                } catch (RuntimeException ignored) {
                    // 继续销毁
                }
            }
            dubbo.close();
            throw new IllegalStateException("scene Dubbo 提供方导出失败 " + host + ":" + port, e);
        }
    }

    private static <S> ServiceConfig<S> exportOne(IsolatedDubboModule dubbo, ProtocolConfig protocol, Class<S> type, S ref,
                                                  String group) {
        ServiceConfig<S> service = new ServiceConfig<>(dubbo.module());
        service.setInterface(type);
        service.setRef(ref);
        service.setGroup(group);
        service.setRegister(false);
        service.setRegistry(new RegistryConfig(RegistryConfig.NO_AVAILABLE));
        service.setProtocol(protocol);
        service.export();
        return service;
    }

    public int port() {
        return port;
    }

    /** 导出的 URL（测试 / 排障用：确认 register=false、group、协议）。 */
    public List<String> exportedUrls() {
        List<String> urls = new ArrayList<>();
        for (ServiceConfig<?> service : services) {
            service.getExportedUrls().forEach(url -> urls.add(url.toString()));
        }
        return urls;
    }

    /** 反导出并销毁 Dubbo 模型（端口关闭；在途应答等至多 Dubbo 停服等待的 1/3）。幂等，不抛异常。 */
    @Override
    public void close() {
        for (ServiceConfig<?> service : services) {
            try {
                service.unexport();
            } catch (RuntimeException e) {
                log.warn("scene Dubbo 提供方反导出出错（继续销毁）", e);
            }
        }
        dubbo.close();
        log.info("scene Dubbo 提供方已关闭 port={}", port);
    }
}
