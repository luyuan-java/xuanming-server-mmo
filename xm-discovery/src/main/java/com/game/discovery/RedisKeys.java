package com.game.discovery;

/** Java 版 Redis 键的唯一出处。全部带 {@code xm:} 前缀，与 mmorpg 的键空间隔离。 */
public final class RedisKeys {

    public static final String PREFIX = "xm:";

    private RedisKeys() {
    }

    /** 节点号租约：{@code xm:node-id:{type}:{zone}:{id}}，值为持有实例的 uuid。 */
    public static String nodeId(String nodeType, int zoneId, int id) {
        return PREFIX + "node-id:" + nodeType + ":" + zoneId + ":" + id;
    }

    /**
     * 节点号租约的防护代次计数器：{@code xm:node-id-epoch:{type}:{zone}:{id}}，整数，每占到一次这个号 INCR 一次，
     * 不过期（单调性要跨越租约键的过期）。
     */
    public static String nodeIdEpoch(String nodeType, int zoneId, int id) {
        return PREFIX + "node-id-epoch:" + nodeType + ":" + zoneId + ":" + id;
    }

    /** 节点目录：{@code xm:nodes:{type}:{zone}}，RMapCache，字段为节点号，值为节点信息 protobuf。 */
    public static String nodeDirectory(String nodeType, int zoneId) {
        return PREFIX + "nodes:" + nodeType + ":" + zoneId;
    }

    /**
     * 玩家数据归属接管请求的 pub/sub 频道：{@code xm:owner-takeover}，消息为 {@code xm.api.OwnerTakeover} protobuf。
     * 全服一个频道（login 发、全部 scene 节点收）：只在夺权撞上仍被持有的归属时才发，量很小。
     */
    public static String ownerTakeoverTopic() {
        return PREFIX + "owner-takeover";
    }

    /**
     * 玩家在线目录：{@code xm:presence:{player_id}}，值为 {@code xm.api.PlayerPresence} protobuf，带 TTL；
     * gate 是唯一写者（进场写、离场带条件删、在线续期），任何服务可读。player_id 全服唯一（雪花），不分 zone。
     */
    public static String presence(long playerId) {
        return PREFIX + "presence:" + Long.toUnsignedString(playerId);
    }

    /**
     * 服务端 → gate 的推送频道：{@code xm:gate-push:{zone}:{gate 节点号}}，消息为 {@code xm.api.GatePush} protobuf。
     * 每个 gate 节点订阅自己的频道；节点号按 zone 分配，所以频道名带 zone。
     */
    public static String gatePushTopic(int zoneId, int gateNodeId) {
        return PREFIX + "gate-push:" + zoneId + ":" + gateNodeId;
    }
}
