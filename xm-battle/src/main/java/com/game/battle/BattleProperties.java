package com.game.battle;

import com.game.battle.room.FingerprintMode;
import com.game.common.RunMode;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * xm-battle 业务配置（{@code xm.battle.*}；battle-node-spec §8.1）。缺省值照基线（dev 配置 / 代码常量），范围不合法即拒启。
 *
 * <p>不在这里的：控制面（Dubbo / 目录 {@code rpc_host}）的通告地址用已有的 {@code xm.advertise-host}；运行模式 {@code xm.run-mode}；
 * 配置表目录 {@code xm.table-dir}；秘密（{@code XM_BATTLE_TOKEN_SECRET}、{@code XM_DUBBO_SECRET}、{@code XM_ADMIN_TOKEN}）只从环境变量读，不进配置。
 * 与运行模式有关的门禁（prod 下 {@code max-connections = 0} 拒启）见 {@link #requireAllowedIn(RunMode)}。
 *
 * @param clientPort             直连监听端口（缺省 {@value #DEFAULT_CLIENT_PORT}，同机多实例各不相同）
 * @param clientBindHost         直连面绑定地址（缺省 {@code 0.0.0.0}：直连面对客户端开放）
 * @param advertisePort          票据里给客户端的端口（缺省 0 = {@code clientPort}；端口映射时填映射后的值）
 * @param rpcPort                {@code BattleNodeService} 的 Dubbo Triple 端口（缺省 {@value #DEFAULT_RPC_PORT}）
 * @param maxConnections         并发直连上限，含未握手（缺省 4096，同基线 dev 配置 {@code deploy.yaml:136}）；0 = 硬上限
 *                               {@value #HARD_MAX_CONNECTIONS}，只许 dev / test
 * @param handshakeTimeout       握手期限（缺省 10 s，<b>客户端可见</b>，不建议改；只为测试可注入；范围 (0, 60 s]）
 * @param illegalPacketThreshold 已验证连接上的非法包阈值（缺省 50；0 = 只计数不断开，同 gate 的 {@code xm.gate.illegal-packet-threshold}）
 * @param tableFingerprintMode   战斗配表指纹闸（缺省 warn）
 * @param rpcMaxInflight         控制面在途调用上限（缺省 256，Java 独有）：超限时 createBattle 回 NOT_ALLOCATABLE(overloaded)，
 *                               其余方法 future 异常完成（§7.8、§11 N20）
 * @param shutdownFlushTimeout   停机时等正在优雅关闭的直连排空的上限（缺省 1 s，与基线强关延迟同值，§7.11）
 * @param clientAdvertiseHost    票据（177 / 补签）与目录 {@code client_host} 里给<b>客户端</b>的直连主机；不配（null / 空白）= 取
 *                               {@code xm.advertise-host}。与控制面的通告地址分开（deploy-ci-spec Q9）：端口映射 / NodePort 部署时，客户端要的是
 *                               宿主可达的地址，{@code rpc_host} 要的是容器网络内的服务名，二者必然不同（同 gate 的 advertise-port 与 client-port 之分）
 */
@ConfigurationProperties("xm.battle")
public record BattleProperties(
        Integer clientPort,
        String clientBindHost,
        Integer advertisePort,
        Integer rpcPort,
        Integer maxConnections,
        Duration handshakeTimeout,
        Integer illegalPacketThreshold,
        FingerprintMode tableFingerprintMode,
        Integer rpcMaxInflight,
        Duration shutdownFlushTimeout,
        String clientAdvertiseHost) {

    public static final int DEFAULT_CLIENT_PORT = 12000;
    public static final int DEFAULT_RPC_PORT = 21200;
    public static final int DEFAULT_MAX_CONNECTIONS = 4096;
    /** {@code max-connections = 0} 时的硬上限（基线 {@code kHardMaxConnections}，防 fd 被吃光）。 */
    public static final int HARD_MAX_CONNECTIONS = 65535;
    /** 基线 {@code kHandshakeTimeoutSec}。 */
    public static final Duration DEFAULT_HANDSHAKE_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration MAX_HANDSHAKE_TIMEOUT = Duration.ofSeconds(60);
    /** 基线 {@code illegal_packet_counter.h:61}。 */
    public static final int DEFAULT_ILLEGAL_PACKET_THRESHOLD = 50;
    public static final int DEFAULT_RPC_MAX_INFLIGHT = 256;
    public static final Duration DEFAULT_SHUTDOWN_FLUSH_TIMEOUT = Duration.ofSeconds(1);

    public BattleProperties {
        clientPort = port(clientPort, DEFAULT_CLIENT_PORT, "client-port", false);
        clientBindHost = clientBindHost == null || clientBindHost.isBlank() ? "0.0.0.0" : clientBindHost.strip();
        advertisePort = port(advertisePort, 0, "advertise-port", true);
        rpcPort = port(rpcPort, DEFAULT_RPC_PORT, "rpc-port", false);
        if (maxConnections == null) {
            maxConnections = DEFAULT_MAX_CONNECTIONS;
        } else if (maxConnections < 0 || maxConnections > HARD_MAX_CONNECTIONS) {
            throw new IllegalArgumentException("xm.battle.max-connections 必须在 [0, " + HARD_MAX_CONNECTIONS + "] 内（0 = 硬上限，只许 dev / test）: "
                    + maxConnections);
        }
        if (handshakeTimeout == null) {
            handshakeTimeout = DEFAULT_HANDSHAKE_TIMEOUT;
        } else if (handshakeTimeout.isNegative() || handshakeTimeout.isZero() || handshakeTimeout.compareTo(MAX_HANDSHAKE_TIMEOUT) > 0) {
            throw new IllegalArgumentException("xm.battle.handshake-timeout 必须在 (0, 60s] 内（客户端可见，缺省 10s）: " + handshakeTimeout);
        }
        if (illegalPacketThreshold == null) {
            illegalPacketThreshold = DEFAULT_ILLEGAL_PACKET_THRESHOLD;
        } else if (illegalPacketThreshold < 0) {
            throw new IllegalArgumentException("xm.battle.illegal-packet-threshold 不能为负（0 = 只计数不断开）: " + illegalPacketThreshold);
        }
        if (tableFingerprintMode == null) {
            tableFingerprintMode = FingerprintMode.WARN;
        }
        if (rpcMaxInflight == null) {
            rpcMaxInflight = DEFAULT_RPC_MAX_INFLIGHT;
        } else if (rpcMaxInflight <= 0) {
            throw new IllegalArgumentException("xm.battle.rpc-max-inflight 必须为正: " + rpcMaxInflight);
        }
        if (shutdownFlushTimeout == null) {
            shutdownFlushTimeout = DEFAULT_SHUTDOWN_FLUSH_TIMEOUT;
        } else if (shutdownFlushTimeout.isNegative() || shutdownFlushTimeout.isZero()) {
            throw new IllegalArgumentException("xm.battle.shutdown-flush-timeout 必须为正: " + shutdownFlushTimeout);
        }
        // 空白视同不配（环境变量占位符缺省展开成空串）
        clientAdvertiseHost = clientAdvertiseHost == null || clientAdvertiseHost.isBlank() ? null : clientAdvertiseHost.strip();
    }

    /** 全部取缺省值（测试用）。 */
    public static BattleProperties defaults() {
        return new BattleProperties(null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * 票据与目录 {@code client_host} 里给客户端的直连主机：配了 {@code client-advertise-host} 用它，否则取控制面的通告地址
     * （{@code xm.advertise-host}）。只影响客户端看到的地址；Dubbo 导出与目录 {@code rpc_host} 始终用 {@code advertiseHost}。
     *
     * @param advertiseHost {@code xm.advertise-host}
     */
    public String effectiveClientAdvertiseHost(String advertiseHost) {
        return clientAdvertiseHost != null ? clientAdvertiseHost : advertiseHost;
    }

    /** 实际生效的并发直连上限：配置 0 时取 {@value #HARD_MAX_CONNECTIONS}。 */
    public int effectiveMaxConnections() {
        return maxConnections == 0 ? HARD_MAX_CONNECTIONS : maxConnections;
    }

    /** 票据里给客户端的端口：{@code advertise-port} 为 0 时取 {@code client-port}。 */
    public int effectiveAdvertisePort() {
        return advertisePort == 0 ? clientPort : advertisePort;
    }

    /**
     * 与运行模式有关的门禁（§6.3、§7.11）：prod 下 {@code max-connections = 0} 拒启（基线代码里没有缺省值，缺键就是 0，prod 拒绝启动）。
     *
     * @throws IllegalStateException 不允许
     */
    public void requireAllowedIn(RunMode mode) {
        if (maxConnections == 0 && mode == RunMode.PROD) {
            throw new IllegalStateException("xm.battle.max-connections = 0（硬上限 " + HARD_MAX_CONNECTIONS + "）只许 dev / test，prod 必须配实际上限");
        }
    }

    private static int port(Integer value, int fallback, String name, boolean zeroAllowed) {
        if (value == null) {
            return fallback;
        }
        int min = zeroAllowed ? 0 : 1;
        if (value < min || value > 65535) {
            throw new IllegalArgumentException("xm.battle." + name + " 必须在 [" + min + ", 65535] 内: " + value);
        }
        return value;
    }
}
