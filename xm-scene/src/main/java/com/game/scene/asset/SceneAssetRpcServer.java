package com.game.scene.asset;

import com.game.api.DubboGroups;
import com.game.api.SceneAssetOpService;
import com.game.api.asset.IsolatedDubboModule;
import java.util.List;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 资产通道提供方的 Dubbo 导出（guild-economy-spec §4.6）：协议 Triple、group {@link DubboGroups#SCENE_ASSET}、<b>{@code register = false}</b>
 * （地址只来自 Redis 节点目录 {@code SceneNodeInfo.rpc_host / rpc_port}，与 gate 连 scene 同一个发现源，不造第二份真相）、无注册中心。
 *
 * <p>编程式导出（自己的 Dubbo 模型，{@link IsolatedDubboModule}），不用 {@code @DubboService}：导出 / 反导出必须排进 {@code SceneNode} 的
 * 启停顺序——启动时提供方导出成功之后才把 rpc 地址写进目录；停服时摘目录之后、逻辑线程停之前才反导出。Spring 的 Dubbo 在全部
 * SmartLifecycle 启动之后才导出、在它们停止之前就反导出，排不出这个顺序（与规格「加 dubbo-spring-boot-starter + @DubboService」的写法不同，
 * 效果相同）。
 *
 * <p>端口：{@code xm.scene.asset-rpc-port}（缺省 21100；同机多实例必须各不相同，同 link-port 惯例）；被占用即导出失败、节点拒绝启动。
 * Dubbo 不接受回环绑定地址（architecture.md §4.1），通告地址用 {@code xm.advertise-host}，实际监听全部网卡——生产用 {@code DUBBO_IP_TO_BIND}
 * 绑内网网卡并配防火墙；调用方 MAC（{@code XM_DUBBO_SECRET}）与请求体 HMAC 两层鉴权。
 */
public final class SceneAssetRpcServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SceneAssetRpcServer.class);

    /** Dubbo 应用名（进 URL，便于在调用方日志里认出 scene 的资产通道）。 */
    static final String APPLICATION = "xm-scene-asset";
    static final String PROTOCOL = "tri";

    private final IsolatedDubboModule dubbo;
    private final ServiceConfig<SceneAssetOpService> service;
    private final int port;

    private SceneAssetRpcServer(IsolatedDubboModule dubbo, ServiceConfig<SceneAssetOpService> service, int port) {
        this.dubbo = dubbo;
        this.service = service;
        this.port = port;
    }

    /**
     * 导出（阻塞到端口开好）。失败（端口被占、缺 {@code XM_DUBBO_SECRET} 等）抛 {@link IllegalStateException}，已建的 Dubbo 模型一并销毁。
     *
     * @param host 通告地址（写进 Dubbo URL；与目录里的 rpc_host 相同）
     * @param port 端口（1..65535）
     */
    public static SceneAssetRpcServer export(SceneAssetOpService provider, String host, int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("资产通道端口超出范围: " + port);
        }
        IsolatedDubboModule dubbo = IsolatedDubboModule.create(APPLICATION);
        try {
            ProtocolConfig protocol = new ProtocolConfig(PROTOCOL, port);
            protocol.setHost(host);
            ServiceConfig<SceneAssetOpService> service = new ServiceConfig<>(dubbo.module());
            service.setInterface(SceneAssetOpService.class);
            service.setRef(provider);
            service.setGroup(DubboGroups.SCENE_ASSET);
            service.setRegister(false);
            service.setRegistry(new RegistryConfig(RegistryConfig.NO_AVAILABLE));
            service.setProtocol(protocol);
            service.export();
            log.info("资产通道 Dubbo 提供方已导出 {}://{}:{} group={} register=false", PROTOCOL, host, port,
                    DubboGroups.SCENE_ASSET);
            return new SceneAssetRpcServer(dubbo, service, port);
        } catch (RuntimeException e) {
            dubbo.close();
            throw new IllegalStateException("资产通道 Dubbo 提供方导出失败 " + host + ":" + port, e);
        }
    }

    public int port() {
        return port;
    }

    /** 导出的 URL（测试 / 排障用：确认 register=false、group、协议）。 */
    List<String> exportedUrls() {
        return service.getExportedUrls().stream().map(Object::toString).toList();
    }

    /** 反导出并销毁 Dubbo 模型（端口关闭；在途应答等至多 Dubbo 停服等待的 1/3）。幂等，不抛异常。 */
    @Override
    public void close() {
        try {
            service.unexport();
        } catch (RuntimeException e) {
            log.warn("资产通道 Dubbo 提供方反导出出错（继续销毁）", e);
        }
        dubbo.close();
        log.info("资产通道 Dubbo 提供方已关闭 port={}", port);
    }
}
