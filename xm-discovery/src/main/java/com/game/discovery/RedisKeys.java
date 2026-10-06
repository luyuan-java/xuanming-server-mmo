package com.game.discovery;

import java.util.Optional;

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
     * 玩家位置：{@code xm:location:{player_id}}，Hash（字段 {@code e} = owner_epoch 十进制、{@code q} = 写序号、{@code s} = 状态 o 在线 / l 重连租约 / x 已登出、
     * {@code v} = {@code xm.discovery.PlayerLocation}），带 TTL；持有归属的 scene 节点是唯一写者（进场 / 换场景写、在线续期、
     * 断线写成重连租约、主动离开写成登出墓碑；按 (epoch, 写序号) 只收更新的写），login 进游戏时读。
     */
    public static String playerLocation(long playerId) {
        return PREFIX + "location:" + Long.toUnsignedString(playerId);
    }

    /**
     * gate 排空标记 {@code xm:gate-draining:{zone}:{node}}：值为打标记时刻（Redis 服务器时间，Unix 秒），必须带 TTL——
     * 打标记的人中途挂了，到期后这台 gate 自动重新接客（容量不会永久蒸发）。xm-data 运维接口写，xm-gateway 读。
     */
    public static String gateDraining(int zoneId, int nodeId) {
        return PREFIX + "gate-draining:" + zoneId + ":" + nodeId;
    }

    /**
     * gate 已排空到可以安全下线 {@code xm:gate-drained:{zone}:{node}}：值为判定理由（below_threshold / deadline），
     * TTL 取排空标记的剩余 TTL（不比它活得久）。xm-gateway 的排空判定循环写，运维 / 缩容脚本读。
     */
    public static String gateDrained(int zoneId, int nodeId) {
        return PREFIX + "gate-drained:" + zoneId + ":" + nodeId;
    }

    /**
     * 好友列表缓存 {@code xm:friend:{<player_id>}:list}（xm-friend 读写）：JSON 数组 {@code [{friend_player_id, since_ms}]}，
     * 带 TTL；同一玩家的键共用 hash tag，上 Cluster 时两条 Lua 碰到的键同槽。玩家号按无符号十进制。
     */
    public static String friendList(long playerId) {
        return PREFIX + "friend:{" + Long.toUnsignedString(playerId) + "}:list";
    }

    /** 入站好友申请缓存 {@code xm:friend:{<player_id>}:req}（形状同 {@link #friendList}）。 */
    public static String friendPending(long playerId) {
        return PREFIX + "friend:{" + Long.toUnsignedString(playerId) + "}:req";
    }

    /**
     * 版本化缓存的代次键 {@code <数据键>:gen}（好友、帮会共用这一条规则）：写路径失效时写一个新的唯一值（带 TTL）再删数据键，
     * 回填只在代次未变时写——防「写之前读到旧快照的回填」在写之后落地。与数据键共用 hash tag（数据键里的 {@code {...}} 原样保留），
     * 两段 Lua 在 Cluster 下同槽。
     */
    public static String cacheGeneration(String dataKey) {
        return dataKey + ":gen";
    }

    /** 好友缓存的代次键 {@code <数据键>:gen}（即 {@link #cacheGeneration}；保留原名，键形状不变）。 */
    public static String friendCacheGeneration(String dataKey) {
        return cacheGeneration(dataKey);
    }

    /** 发好友申请的每分钟配额计数 {@code xm:friend:{<player_id>}:quota}（固定窗口，INCR + EXPIRE）。 */
    public static String friendRequestQuota(long playerId) {
        return PREFIX + "friend:{" + Long.toUnsignedString(playerId) + "}:quota";
    }

    /** 在线目录翻页的每分钟配额计数 {@code xm:friend:{<player_id>}:dir-quota}（固定窗口，INCR + EXPIRE）。 */
    public static String friendDirectoryQuota(long playerId) {
        return PREFIX + "friend:{" + Long.toUnsignedString(playerId) + "}:dir-quota";
    }

    /** 在线目录条目的键前缀（{@link #presence} 的前缀；在线目录按它 SCAN）。 */
    public static String presencePrefix() {
        return PREFIX + "presence:";
    }

    /** 世界频道聊天历史 {@code xm:chat:{world}:log}（LIST，新在前，全服一条，xm-chat 读写）。 */
    public static String chatWorldLog() {
        return PREFIX + "chat:{world}:log";
    }

    /** 私聊历史 {@code xm:chat:{p:<小号>:<大号>}:log}：两个玩家号按无符号排序，A→B 与 B→A 写同一把键。 */
    public static String chatPrivateLog(long a, long b) {
        long lo = Long.compareUnsigned(a, b) <= 0 ? a : b;
        long hi = lo == a ? b : a;
        return PREFIX + "chat:{p:" + Long.toUnsignedString(lo) + ":" + Long.toUnsignedString(hi) + "}:log";
    }

    /** 发言的幂等键 {@code xm:chat:{req:<player_id>}:<request_id>}（SET NX EX；request_id 由客户端给、≤ 64 字节）。 */
    public static String chatRequestId(long playerId, String requestId) {
        return PREFIX + "chat:{req:" + Long.toUnsignedString(playerId) + "}:" + requestId;
    }

    /** 发言的每秒限速计数 {@code xm:chat:{rl:<player_id>}}。 */
    public static String chatRateLimit(long playerId) {
        return PREFIX + "chat:{rl:" + Long.toUnsignedString(playerId) + "}";
    }

    /**
     * 组队权威记录 {@code xm:{team}:rec:<team_id>}（Hash：{@code ver} 版本号、{@code pb} TeamRecord；空闲 24 h 过期）。
     * 组队的四类键统一 hash tag {@code {team}}：写脚本一次要原子写记录、投影与多名玩家的索引，将来上 Cluster 仍同槽（team-spec D2）。
     * 只经 xm-team 的 Lua 写。
     */
    public static String teamRecord(long teamId) {
        return PREFIX + "{team}:rec:" + Long.toUnsignedString(teamId);
    }

    /** 组队投影 {@code xm:{team}:info:<team_id>}（String：{@code xm.discovery.TeamInfo}；xm-team 与记录同一段 Lua 写，xm-scene 读）。 */
    public static String teamInfo(long teamId) {
        return PREFIX + "{team}:info:" + Long.toUnsignedString(teamId);
    }

    /** 玩家的组队索引 {@code xm:{team}:player:<player_id>}（Hash：{@code tid} 所在队，无队为 "0"；{@code epoch} 成员关系版本）。 */
    public static String teamPlayer(long playerId) {
        return PREFIX + "{team}:player:" + Long.toUnsignedString(playerId);
    }

    /** 被邀请人的邀请反查 {@code xm:{team}:invite:<player_id>}（ZSET：成员 = team_id，分数 = expire_at_ms；TTL 1 h）。 */
    public static String teamInvite(long playerId) {
        return PREFIX + "{team}:invite:" + Long.toUnsignedString(playerId);
    }

    /**
     * 帮会快照缓存 {@code xm:guild:{g:<guild_id>}:snap}（xm-guild 读写）：值为 xm-guild 自有的 {@code xm.guild.GuildSnapshot} protobuf
     * （字段同基线 GuildData，含 score / funds / 成员帮贡），带 TTL；代次键是 {@link #cacheGeneration}（{@code …:snap:gen}）。
     * 基线 {@code guild:v2:{id}}（guild_repo.go:136-140）。帮会号按无符号十进制。
     */
    public static String guildSnapshot(long guildId) {
        return PREFIX + "guild:{g:" + Long.toUnsignedString(guildId) + "}:snap";
    }

    /**
     * 玩家 → 帮会映射缓存 {@code xm:guild:{p:<player_id>}:gid}：值为帮会号的无符号十进制，<b>未入帮的 0 也缓存</b>，带 TTL；
     * 代次键是 {@link #cacheGeneration}（{@code …:gid:gen}）。基线 {@code player_guild:v2:{id}}（guild_repo.go:142-144、:258）。
     */
    public static String guildOfPlayer(long playerId) {
        return PREFIX + "guild:{p:" + Long.toUnsignedString(playerId) + "}:gid";
    }

    /**
     * 入帮申请推送冷却 {@code xm:guild:{g:<guild_id>}:apply-push:<player_id>}（{@code SET NX PX 60000}，值 "1"）：
     * 同一（帮会, 申请人）60 s 内至多推一次 APPLICATION_RECEIVED。基线 {@code guild:apply_push:{g}:{p}}（guild_manage_repo.go:683-700）。
     */
    public static String guildApplyPush(long guildId, long playerId) {
        return PREFIX + "guild:{g:" + Long.toUnsignedString(guildId) + "}:apply-push:" + Long.toUnsignedString(playerId);
    }

    /**
     * 帮会全服榜 {@code xm:guild:{rank}:all}（ZSET：成员 = 帮会号无符号十进制，分数 = 排行分）。排行的全部键共用 hash tag {@code {rank}}，
     * 多键 Lua 与换榜的 RENAME 在 Cluster 下同槽（guild-spec D16）。基线 {@code guild_rank}（guild_repo.go:154）。
     */
    public static String guildRankAll() {
        return PREFIX + "guild:{rank}:all";
    }

    /** 帮会区榜 {@code xm:guild:{rank}:zone:<zone_id>}（形状同 {@link #guildRankAll}；只放 zone ≠ 0 的帮）。基线 {@code guild_rank:zone:{z}}。 */
    public static String guildRankZone(int zoneId) {
        return PREFIX + "guild:{rank}:zone:" + Integer.toUnsignedString(zoneId);
    }

    /**
     * 帮会区榜索引 {@code xm:guild:{rank}:zones}（SET：成员 = 出现过区榜的 zone_id 无符号十进制），代替基线的 {@code SCAN guild_rank:zone:*}：
     * 入榜脚本 SADD、重建换榜时整体重写，清榜按它找全部区榜键。
     */
    public static String guildRankZones() {
        return PREFIX + "guild:{rank}:zones";
    }

    /** 帮会排行维护锁 {@code xm:guild:{rank}:lock}（值 = 持有者令牌，PX 30 s 并由持有者续期）。基线 {@code guild_rank:maintenance_lock}（5 min）。 */
    public static String guildRankLock() {
        return PREFIX + "guild:{rank}:lock";
    }

    /** 重建排行的全服榜临时键 {@code xm:guild:{rank}:tmp:<token>:all}（带 PEXPIRE，换榜时 RENAME 成正式键）。 */
    public static String guildRankTmpAll(String token) {
        return PREFIX + "guild:{rank}:tmp:" + token + ":all";
    }

    /** 重建排行的区榜临时键 {@code xm:guild:{rank}:tmp:<token>:zone:<zone_id>}。 */
    public static String guildRankTmpZone(String token, int zoneId) {
        return PREFIX + "guild:{rank}:tmp:" + token + ":zone:" + Integer.toUnsignedString(zoneId);
    }

    /**
     * 主世界频道计划 {@code xm:world:{z:<zone>}:ch}（HASH：字段 = scene_id 无符号十进制，值 = {@code xm.api.WorldChannel} protobuf；无 TTL）。
     * 一个 zone 的频道键（ch / desired / cooldown / ver / leader / resv:*）共用 hash tag {@code {z:<zone>}}，多键 Lua 在 Cluster 下同槽
     * （修基线 scene_atomic.go:51-68 自称 Cluster-safe 实则跨槽，scene-channels-spec §7.1 B17）。只经 {@code WorldChannelStore} 的围栏 Lua 写。
     * 基线 {@code world_channels:zone:{z}:{conf}} + {@code scene:{id}:node} 等多键（world_init.go:25、:216-239；D1）。
     */
    public static String worldChannels(int zoneId) {
        return worldZonePrefix(zoneId) + "ch";
    }

    /**
     * 每图期望频道数 {@code xm:world:{z:<zone>}:desired}（HASH：conf 无符号十进制 → 期望数十进制；无 TTL；首次 HSETNX 播种，此后 Redis 为权威）。
     * 基线 {@code world_channels:desired:zone:{z}}（world_autoscale.go:38、:73-117）。
     */
    public static String worldDesired(int zoneId) {
        return worldZonePrefix(zoneId) + "desired";
    }

    /**
     * 扩缩容冷却 {@code xm:world:{z:<zone>}:cooldown}（HASH：conf 无符号十进制 → 冷却到期毫秒，Redis TIME；过期字段由领导者顺手 HDEL）。
     * 基线每图一个 SETEX 键 {@code world_channels:cooldown:zone:{z}:{conf}}（world_autoscale.go:44、:491-504）。
     */
    public static String worldCooldown(int zoneId) {
        return worldZonePrefix(zoneId) + "cooldown";
    }

    /**
     * 频道计划版本号 {@code xm:world:{z:<zone>}:ver}（STRING 整数，只增不减、不过期；不存在当 0）：围栏 Lua 每次写入把它置为批次定好的新值
     * （max(旧值 + 1, Redis TIME 毫秒)，见 WorldPlanBatch——Redis 丢写后也不会让两次不同的写入撞号），
     * 领导者据此做 CAS，scene 节点每秒 GET 一次、变了才整读计划。
     */
    public static String worldPlanVersion(int zoneId) {
        return worldZonePrefix(zoneId) + "ver";
    }

    /**
     * 频道计划的分 zone 领导锁 {@code xm:world:{z:<zone>}:leader}（STRING：持有者令牌 {@code <instanceId>:<uuid>}，PX = 锁 TTL，持有者续期）。
     * 基线全局一把 {@code scene_manager:leader:lock}（scene_manager_service.go:94-101），Java 每 zone 一把（D2）。
     */
    public static String worldLeader(int zoneId) {
        return worldZonePrefix(zoneId) + "leader";
    }

    /**
     * 进场软预占 {@code xm:world:{z:<zone>}:resv:<scene_id>}（ZSET：成员 = player_id 无符号十进制，分数 = 到期毫秒（Redis TIME）；
     * 键 PEXPIRE = 预占 TTL）。取代基线 {@code instance:{id}:player_count} 的 INCR / DECR 硬计数（scene_atomic.go:69-101；D7）。
     */
    public static String worldReservations(int zoneId, long sceneId) {
        return worldZonePrefix(zoneId) + "resv:" + Long.toUnsignedString(sceneId);
    }

    /**
     * 出现过 scene 节点的 zone 集合 {@code xm:world:zones}（SET：zone_id 无符号十进制；无 TTL、只增）：scene 启动时 SADD，
     * scene-manager 据此决定竞选哪些 zone 的领导锁（基线「活跃 zone = etcd 里出现过的 zone」，load_reporter.go:579-595）。
     * 退役 zone 要运维手工 SREM（scene-channels-spec §7.2 第 7 条）。
     */
    public static String worldZones() {
        return PREFIX + "world:zones";
    }

    private static String worldZonePrefix(int zoneId) {
        return PREFIX + "world:{z:" + Integer.toUnsignedString(zoneId) + "}:";
    }

    /**
     * 热关停规则 {@code xm:killswitch}（哈希：字段 = 规则键 {@code pkg.Service/Method}、{@code Service/*}、{@code *}，值 = 规则；
     * 各进程每秒全量读一次，见 {@code RedisKillSwitchSync}）。运维经 xm-data 的 {@code /admin/killswitch} 写。
     */
    public static String killSwitch() {
        return PREFIX + "killswitch";
    }

    /** 登录排队：区的队列 {@code xm:login-queue:{zone}}，ZSET（成员 = 排队号，分数 = 入队毫秒）。xm-gateway 读写。 */
    public static String loginQueue(int zoneId) {
        return PREFIX + "login-queue:" + zoneId;
    }

    /**
     * 登录排队：区里已放行、还没进到 gate 的占位 {@code xm:login-queue-admitted:{zone}}，ZSET（成员 = 排队号或 {@code fast:<uuid>}，
     * 分数 = 占位到期毫秒，过期的计数前清掉；客户端取走放行槽后再留 15 s，等 gate 把人数发布出来）。
     */
    public static String loginQueueAdmitted(int zoneId) {
        return PREFIX + "login-queue-admitted:" + zoneId;
    }

    /**
     * 登录排队：放行时选好的 gate {@code xm:login-queue-admit:{queue_id}}（{@code xm.api.GateNodeInfo}，带 TTL，取走即删）。
     * 放行脚本拿 {@code loginQueueAdmit("")} 当前缀拼键，排队号必须在键尾。
     */
    public static String loginQueueAdmit(String queueId) {
        return PREFIX + "login-queue-admit:" + queueId;
    }

    /**
     * 登录排队：一个排队条目的元数据 {@code xm:login-queue-meta:{queue_id}}，Hash（zone / created / admitted；取走放行槽后加
     * taken_by / slot——同一请求被 Redisson 重发时据此认出），带 TTL。放行脚本拿 {@code loginQueueMeta("")} 当前缀拼键，排队号必须在键尾。
     */
    public static String loginQueueMeta(String queueId) {
        return PREFIX + "login-queue-meta:" + queueId;
    }

    /** 开服限流：区的令牌桶 {@code xm:rl:zone:{zone}}（Hash：t = 余量 ×1000、ms = 上次补充毫秒），闲置 1 h 过期。xm-gateway 读写。 */
    public static String rateLimitZone(int zoneId) {
        return PREFIX + "rl:zone:" + zoneId;
    }

    /** 开服限流：单 IP 的令牌桶 {@code xm:rl:ip:{ip}}（同区桶的形状），闲置 1 h 过期。{@code ip} 是解析好的 IP 字面量（或对端地址）。 */
    public static String rateLimitIp(String ip) {
        return PREFIX + "rl:ip:" + ip;
    }

    /**
     * 开服限流：同一身份同一 IP 的冷却 {@code xm:rl:cd:{scope}:{subject}}（scope = login / assign；subject = 身份的 SHA-256 前缀 + IP），
     * TTL = 冷却时长。
     */
    public static String rateLimitCooldown(String scope, String subject) {
        return PREFIX + "rl:cd:" + scope + ":" + subject;
    }

    /** 登录排队：放行循环的选主锁 {@code xm:login-queue-dispatcher}（全部 xm-gateway 只有一个在放行）。 */
    public static String loginQueueDispatcherLock() {
        return PREFIX + "login-queue-dispatcher";
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

    /**
     * 回合制战斗锁（scene-battle-spec §7.2，D2）：{@code xm:battle:{<player_id>}:lock}，Hash——{@code b} battle_id（无符号十进制）、
     * {@code n} battle 节点号、{@code s} 阶段 {@code P} / {@code F}、{@code d} 战斗期限毫秒、{@code p} 备战期限毫秒；带 TTL。
     * 锁与上下文合成一个键，「同生共死」由结构保证。scene 是唯一写者；match / team / guild 经 {@code BattleLockReader} 只读。
     * 与待结算记录 {@link #battleSettlements} 共用 hash tag，销账 / 取代两段 Lua 在 Cluster 下同槽。
     */
    public static String battleLock(long playerId) {
        return PREFIX + "battle:{" + Long.toUnsignedString(playerId) + "}:lock";
    }

    /**
     * 回合制战斗待结算记录（scene-battle-spec §7.2，D13）：{@code xm:battle:{<player_id>}:settlement}，Hash——字段名 = battle_id（无符号十进制），
     * 值 = 契约 {@code BattleSettlementEvent} 字节；整键 TTL 7 天，每次写刷新。battle 落库，scene 销账时只删字段。
     */
    public static String battleSettlements(long playerId) {
        return PREFIX + "battle:{" + Long.toUnsignedString(playerId) + "}:settlement";
    }

    /**
     * 回合制战斗「已销账」墓碑（Java 独有，按 (玩家, 战斗) 一个键）：{@code xm:battle:{<player_id>}:settled:<battle_id>}，String {@code "1"}，
     * TTL = {@code BattleRedis.SETTLED_TOMBSTONE_TTL_SEC}（10 min）。销账的两段 Lua（scene 的 {@code ACK}、battle 的 {@code ACK_IF_SUPERSEDED}
     * 删记录分支）顺手写，battle 的落库 {@code STORE_SETTLEMENT} 见到它就不写——防迟到 / 被重放的落库落在销账之后把记录重新造出来、
     * 下次进场重复发奖。与锁 {@link #battleLock}、待结算记录 {@link #battleSettlements} 共用 hash tag（三键同槽）；
     * 只许经 {@code KEYS} 传进脚本，不许在 Lua 里用 ARGV 拼。两个号都按无符号十进制。
     */
    public static String battleSettled(long playerId, long battleId) {
        return PREFIX + "battle:{" + Long.toUnsignedString(playerId) + "}:settled:" + Long.toUnsignedString(battleId);
    }

    /**
     * 活动局结果的持久副本（scene-battle-spec §7.17）：{@code xm:battle:activity-result:<battle_id>}，String = 契约 {@code BattleResultEvent} 字节，
     * TTL 7 天；battle 写，消费方（4.6 帮会）消费后删。
     */
    public static String battleActivityResult(long battleId) {
        return PREFIX + "battle:activity-result:" + Long.toUnsignedString(battleId);
    }

    // ------------------------------------------------------------------ 匹配（xm-match，批次 6.4，match-spec §9.4）
    //
    // 全部 match 键共用一个 hash tag {match}：票据、队列、弹组标记、切磋记录、落点记录同槽，入队 / 取消 / 弹组 / 回队首 / 切磋的发起与消费
    // 都能各用一段 Lua 原子完成（先例 {team}，team-spec D2）；基线票据不带 tag、与队列跨 slot，才需要三条自愈路径。只有 xm-match 写这些键。

    /** match 键的公共前缀 {@code xm:{match}:}。 */
    private static final String MATCH_PREFIX = PREFIX + "{match}:";
    private static final String MATCH_QUEUE_PREFIX = MATCH_PREFIX + "queue:";

    /**
     * 一条排队队列的身份（{@link #matchQueue} 的两段）。
     *
     * @param mode   {@code MatchMode} 的数值（≥ 0）
     * @param config {@code battle_config_id}（uint32 的位模式；键里按无符号十进制）
     */
    public record MatchQueueId(int mode, int config) {

        public MatchQueueId {
            if (mode < 0) {
                throw new IllegalArgumentException("匹配模式不能为负: " + mode);
            }
        }
    }

    /** 活跃队列注册集 {@code xm:{match}:index}（SET：成员 = 队列键全文；代替 SCAN。非空队列一定在里面，由入队 / 回队首的同一段 Lua 保证）。 */
    public static String matchQueueIndex() {
        return MATCH_PREFIX + "index";
    }

    /**
     * 排队队列 {@code xm:{match}:queue:<mode>:<config>}（LIST：成员 = player_id 无符号十进制；队尾入队、队首等得最久、回队首用 LPUSH）。
     * mode 是 {@code MatchMode} 的数值，config 是 {@code battle_config_id} 的无符号十进制——config 不校验，每个不同的值一条队列（照搬基线）。
     */
    public static String matchQueue(int mode, int config) {
        return MATCH_QUEUE_PREFIX + queueSuffix(mode, config);
    }

    /** 队列的评分镜像 {@code xm:{match}:rank:<mode>:<config>}（ZSET：成员 = player_id，分数 = 入队时的评分 × 100；成员集合恒等于队列的成员集合）。 */
    public static String matchRank(int mode, int config) {
        return MATCH_PREFIX + "rank:" + queueSuffix(mode, config);
    }

    /** 凑单锁 {@code xm:{match}:lock:<mode>:<config>}（STRING：值 = 实例 id，PX 10 s，按持有者释放；只是效率手段，正确性靠弹组脚本）。 */
    public static String matchQueueLock(int mode, int config) {
        return MATCH_PREFIX + "lock:" + queueSuffix(mode, config);
    }

    /**
     * 解析注册集里的一个队列键（{@link #matchQueue} 的逆）。只认规范形：mode 是不带符号与前导零的十进制 int，config 是不带前导零的 uint32 十进制；
     * 其它一律为空（凑单把它当坏成员告警并跳过）。{@code parseMatchQueue(matchQueue(m, c))} 恒还原出 (m, c)。
     */
    public static Optional<MatchQueueId> parseMatchQueue(String key) {
        if (key == null || !key.startsWith(MATCH_QUEUE_PREFIX)) {
            return Optional.empty();
        }
        String rest = key.substring(MATCH_QUEUE_PREFIX.length());
        int colon = rest.indexOf(':');
        if (colon <= 0 || colon != rest.lastIndexOf(':')) {
            return Optional.empty();
        }
        long mode = canonicalDecimal(rest.substring(0, colon), Integer.MAX_VALUE);
        long config = canonicalDecimal(rest.substring(colon + 1), 0xFFFF_FFFFL);
        if (mode < 0 || config < 0) {
            return Optional.empty();
        }
        return Optional.of(new MatchQueueId((int) mode, (int) config));
    }

    /**
     * 排队票据 {@code xm:{match}:ticket:<player_id>}（HASH：{@code ticket} / {@code mode} / {@code config} / {@code state} / {@code enqueued_at_ms} /
     * {@code zone_id} / {@code queue_key} / {@code rating_centi} / {@code team_id} / {@code battle_id} / {@code not_before_ms}；TTL：queued 6 h、
     * matched 按人数公式、ready 60 s）。每个玩家至多一张；所有写都带 ticket id 做 CAS。
     */
    public static String matchTicket(long playerId) {
        return MATCH_PREFIX + "ticket:" + Long.toUnsignedString(playerId);
    }

    /** 弹组重放标记 {@code xm:{match}:pop:<token>}（STRING，PX 60 s；Java 独有：弹组脚本被 Redisson 重发时凭它认出「已经弹过了」）。 */
    public static String matchPopMarker(String token) {
        return MATCH_PREFIX + "pop:" + token;
    }

    /** 切磋记录 {@code xm:{match}:challenge:<challenge_id>}（HASH：发起者、目标、配置、过期时刻；TTL 60 s）。 */
    public static String matchChallenge(long challengeId) {
        return MATCH_PREFIX + "challenge:" + Long.toUnsignedString(challengeId);
    }

    /** 目标的待应答占坑 {@code xm:{match}:challenge-target:<player_id>}（STRING：值 = challenge_id；TTL 60 s；一个人同时只能有一条待应答的邀请）。 */
    public static String matchChallengeTarget(long playerId) {
        return MATCH_PREFIX + "challenge-target:" + Long.toUnsignedString(playerId);
    }

    /** 切磋消费墓碑 {@code xm:{match}:challenge-done:<challenge_id>}（HASH：被消费记录的字段 + 请求 nonce，PX 60 s；Java 独有：消费脚本重发时原样返回）。 */
    public static String matchChallengeDone(long challengeId) {
        return MATCH_PREFIX + "challenge-done:" + Long.toUnsignedString(challengeId);
    }

    /**
     * 战斗落点记录 {@code xm:{match}:battle:<battle_id>}（HASH：{@code a} = attempt 十进制、{@code pb} = xm-match 的 {@code BattlePlacement} 字节；
     * TTL 360 s）。gather 在建房<b>之前</b>写（只收 attempt ≥ 已存值的写）；179 补签按它直拨 battle；6.5 观战复用同一条记录。
     */
    public static String matchBattlePlacement(long battleId) {
        return MATCH_PREFIX + "battle:" + Long.toUnsignedString(battleId);
    }

    private static String queueSuffix(int mode, int config) {
        if (mode < 0) {
            throw new IllegalArgumentException("匹配模式不能为负: " + mode);
        }
        return mode + ":" + Integer.toUnsignedString(config);
    }

    /** 规范十进制（无符号、无前导零、不超过 {@code max}）的值；不是规范形返回 -1。 */
    private static long canonicalDecimal(String s, long max) {
        if (s.isEmpty() || s.length() > 10 || (s.length() > 1 && s.charAt(0) == '0')) {
            return -1;
        }
        long value = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            value = value * 10 + (c - '0');
        }
        return value <= max ? value : -1;
    }
}
