package com.game.api.asset;

/**
 * 一个 scene 节点的资产通道直连地址（来自 Redis 节点目录 {@code SceneNodeInfo}：zone / node_id / instance_id / rpc_host / rpc_port）。
 * 客户端缓存（{@link SceneAssetOpClients}）按 (host, port) 复用连接，实例变了（节点号被新进程接手）就重建。
 *
 * @param zoneId     节点所属 zone
 * @param nodeId     节点号（按 zone 租约；实例退出即交还，可能被同号新实例接手）
 * @param instanceId 实例 id（进程启动时随机生成）
 * @param host       Dubbo Triple 通告地址
 * @param port       Dubbo Triple 端口（1..65535）
 */
public record SceneAssetEndpoint(int zoneId, int nodeId, String instanceId, String host, int port) {

    public SceneAssetEndpoint {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("资产通道地址 host 为空");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("资产通道端口超出范围: " + port);
        }
        instanceId = instanceId == null ? "" : instanceId;
    }

    /** {@code host:port}（IPv6 字面量加方括号），用作缓存键与 Dubbo 直连 URL 的地址段。 */
    public String address() {
        return (host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host) + ":" + port;
    }
}
