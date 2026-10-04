package com.game.discovery;

/** Java 版 Redis 键的唯一出处。全部带 {@code xm:} 前缀，与 mmorpg 的键空间隔离。 */
public final class RedisKeys {

    public static final String PREFIX = "xm:";
    /** 全服产出封禁的类别：币种（字段是币种号）。写者（xm-data）与读者（scene）共用这一个出处。 */
    public static final String GAIN_BLOCK_CURRENCY = "currency";
    /** 全服产出封禁的类别：物品（字段是物品配置号）。 */
    public static final String GAIN_BLOCK_ITEM = "item";

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

    /**
     * 全服产出封禁名单：{@code xm:gain-block:{category}}（category 是 {@link #GAIN_BLOCK_CURRENCY} 或
     * {@link #GAIN_BLOCK_ITEM}），Hash，字段为被封的 id（十进制），值为封禁元数据 JSON（操作人、时刻、原因，只给运维看）。全服一份、不分 zone；xm-data 运维接口是唯一写者，
     * 全部 scene 节点读。
     */
    public static String gainBlocks(String category) {
        return PREFIX + "gain-block:" + category;
    }

    /** 全服产出封禁名单变更通知的 pub/sub 频道：{@code xm:gain-block-changed}，消息体是 category（收到就重读名单）。 */
    public static String gainBlockChangedTopic() {
        return PREFIX + "gain-block-changed";
    }

    /**
     * 登录 access token：{@code xm:login:access:{token}}，值为令牌数据 JSON（账号、认证方式、设备号、签发秒），TTL = access 有效期。
     * 令牌是 43 字符的 base64url（不含 {@code :}）；不分 zone（同一账号在哪个区都能用）。
     */
    public static String loginAccessToken(String token) {
        return PREFIX + "login:access:" + token;
    }

    /** 登录 refresh token：{@code xm:login:refresh:{token}}，值同 access，TTL = refresh 有效期；一次性（轮换时原子取走）。 */
    public static String loginRefreshToken(String token) {
        return PREFIX + "login:refresh:" + token;
    }

    /** 账号的活跃 refresh token 集合：{@code xm:login:account-refresh:{account}}，ZSET，成员是 token、分数是它的过期 Unix 秒。 */
    public static String loginAccountRefresh(String account) {
        return PREFIX + "login:account-refresh:" + account;
    }

    /**
     * 账号处于「已登录、未进游戏」窗口的连接：{@code xm:login:devices:{account}}，ZSET，成员是会话键（gate 实例 / 会话号）、
     * 分数是该成员的过期 Unix 毫秒（设备数上限按它自愈）。
     */
    public static String loginDevices(String account) {
        return PREFIX + "login:devices:" + account;
    }

    /**
     * 会话当前计在哪个账号的设备名单里：{@code xm:login:device-session:{会话键}}，值为账号，TTL 同设备登记。
     * 断线时 gate 记的账号可能是空的（登录应答没送到 gate），按它找回名单注销。
     */
    public static String loginDeviceSession(String sessionKey) {
        return PREFIX + "login:device-session:" + sessionKey;
    }
}
