package com.game.battle.rpc;

import com.game.api.BattleNodeService;
import com.game.api.DubboGroups;
import com.game.api.asset.IsolatedDubboModule;
import java.util.List;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * battle 控制面 {@link BattleNodeService} 的 Dubbo 导出（battle-node-spec §7.8；先例 xm-scene 的 {@code SceneAssetRpcServer}）：协议 Triple、
 * group {@link DubboGroups#BATTLE_NODE}、<b>{@code register = false}</b>（地址只来自 Redis 节点目录 {@code BattleNodeInfo.rpc_host / rpc_port}，
 * 不造第二份真相）、无注册中心。
 *
 * <p>编程式导出（自己的 Dubbo 模型，{@link IsolatedDubboModule}），不用 {@code @DubboService}：导出 / 反导出必须排进 {@code BattleNode} 的启停顺序——
 * 启动时导出成功之后才绑直连端口、开准入闸、把 rpc 地址写进目录；停机时作废全部房间、排空直连之后才反导出（等在途调用至多
 * {@link IsolatedDubboModule#DEFAULT_SHUTDOWN_WAIT} 的 1/3），之后才停逻辑线程（§7.11）。
 *
 * <p>鉴权：调用方 MAC（{@code XM_DUBBO_SECRET}，xm-api 的 SPI 提供方过滤器，缺密钥导出即失败）与热关停过滤器（{@code xm.killswitch.enabled}）自动生效
 * （architecture.md §4.1、§4.15）。Dubbo 不接受回环绑定地址，通告地址用 {@code xm.advertise-host}，实际监听全部网卡——生产用
 * {@code DUBBO_IP_TO_BIND} 绑内网网卡并配防火墙。端口 {@code xm.battle.rpc-port}（缺省 21200，同机多实例各不相同），被占用即导出失败、拒绝启动。
 */
public final class BattleRpcServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BattleRpcServer.class);

    /** Dubbo 应用名（进 URL，便于在调用方日志里认出 battle 控制面）。 */
    static final String APPLICATION = "xm-battle";
    static final String PROTOCOL = "tri";

    private final IsolatedDubboModule dubbo;
    private final ServiceConfig<BattleNodeService> service;
    private final int port;

    private BattleRpcServer(IsolatedDubboModule dubbo, ServiceConfig<BattleNodeService> service, int port) {
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
    public static BattleRpcServer export(BattleNodeService provider, String host, int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("battle 控制面端口超出范围: " + port);
        }
        IsolatedDubboModule dubbo = IsolatedDubboModule.create(APPLICATION);
        try {
            ProtocolConfig protocol = new ProtocolConfig(PROTOCOL, port);
            protocol.setHost(host);
            ServiceConfig<BattleNodeService> service = new ServiceConfig<>(dubbo.module());
            service.setInterface(BattleNodeService.class);
            service.setRef(provider);
            service.setGroup(DubboGroups.BATTLE_NODE);
            service.setRegister(false);
            service.setRegistry(new RegistryConfig(RegistryConfig.NO_AVAILABLE));
            service.setProtocol(protocol);
            service.export();
            log.info("battle 控制面 Dubbo 提供方已导出 {}://{}:{} group={} register=false", PROTOCOL, host, port,
                    DubboGroups.BATTLE_NODE);
            return new BattleRpcServer(dubbo, service, port);
        } catch (RuntimeException e) {
            dubbo.close();
            throw new IllegalStateException("battle 控制面 Dubbo 提供方导出失败 " + host + ":" + port, e);
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
            log.warn("battle 控制面 Dubbo 提供方反导出出错（继续销毁）", e);
        }
        dubbo.close();
        log.info("battle 控制面 Dubbo 提供方已关闭 port={}", port);
    }
}
