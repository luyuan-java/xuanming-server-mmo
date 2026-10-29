package com.game.discovery;

import com.game.common.GameNamespace;
import java.util.Objects;
import java.util.Optional;

/**
 * Java 版 Redis 键与 pub/sub 频道的唯一出处。全部带 {@code <ns>:} 前缀（{@code <ns>} = 部署命名空间 {@code game.namespace}，缺省 {@code game}，
 * 见 {@link GameNamespace}），与 mmorpg 的键空间、以及共用同一个 Redis 的别的部署隔离。下面各方法注释里的 {@code game:} 均指缺省命名空间。
 *
 * <p>带命名空间的不可变实例（同 Spring Session 的 {@code spring.session.redis.namespace}、Spring Cache 的 key-prefix 的做法）：Spring 进程里由
 * game-discovery 的 {@code GameNamespaceAutoConfiguration} 按校验过的 {@code game.namespace} 注册成 bean（使用方自己定义了同类 bean 时让路），经构造器注入给使用方；
 * 不起 Spring 的命令行工具用 {@code GameNamespaceBinder} 按服务进程同口径绑定命名空间后自己 {@code new}；测试直接构造任意命名空间的实例。
 * main 代码不得静态持有它，也不得在装配类与命令行工具之外 {@code new} 它（game-robot 的 {@code NamespaceLiteralGuardTest} 守着）。
 * 前缀之后的结构（节点类型、hash tag、字段名）不随命名空间变。不用 Redisson 的 NameMapper：它管不到 Lua 里经 ARGV 拼的前缀和 SCAN 模式。
 */
public final class RedisKeys {

    /** 全服产出封禁的类别：币种（字段是币种号）。写者（game-data）与读者（scene）共用这一个出处。编译期常量，不带命名空间。 */
    public static final String GAIN_BLOCK_CURRENCY = "currency";
    /** 全服产出封禁的类别：物品（字段是物品配置号）。 */
    public static final String GAIN_BLOCK_ITEM = "item";

    private final GameNamespace namespace;
    /** 键前缀 {@code <ns>:}（缺省 {@code game:}）。 */
    private final String prefix;
    /** match 键的公共前缀 {@code game:{match}:}（见「匹配」一节）。 */
    private final String matchPrefix;
    private final String matchQueuePrefix;

    public RedisKeys(GameNamespace namespace) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.prefix = namespace.value() + ":";
        this.matchPrefix = prefix + "{match}:";
        this.matchQueuePrefix = matchPrefix + "queue:";
    }

    /** 本实例的部署命名空间。 */
    public GameNamespace namespace() {
        return namespace;
    }

    /** 键前缀 {@code <ns>:}（缺省 {@code game:}）：日志里的 {@code key_prefix}、按前缀 SCAN / 截串的使用方用它。 */
    public String prefix() {
        return prefix;
    }

    @Override
    public String toString() {
        return "RedisKeys[" + namespace + "]";
    }

    /** 节点号租约：{@code game:node-id:{type}:{zone}:{id}}，值为持有实例的 uuid。 */
    public String nodeId(String nodeType, int zoneId, int id) {
        return prefix + "node-id:" + nodeType + ":" + zoneId + ":" + id;
    }

    /**
     * 节点号租约的防护代次计数器：{@code game:node-id-epoch:{type}:{zone}:{id}}，整数，每占到一次这个号 INCR 一次，
     * 不过期（单调性要跨越租约键的过期）。
     */
    public String nodeIdEpoch(String nodeType, int zoneId, int id) {
        return prefix + "node-id-epoch:" + nodeType + ":" + zoneId + ":" + id;
    }

    /** 节点目录：{@code game:nodes:{type}:{zone}}，RMapCache，字段为节点号，值为节点信息 protobuf。 */
    public String nodeDirectory(String nodeType, int zoneId) {
        return prefix + "nodes:" + nodeType + ":" + zoneId;
    }

    /**
     * 玩家数据归属接管请求的 pub/sub 频道：{@code game:owner-takeover}，消息为 {@code game.api.OwnerTakeover} protobuf。
     * 全服一个频道（login 发、全部 scene 节点收）：只在夺权撞上仍被持有的归属时才发，量很小。
     */
    public String ownerTakeoverTopic() {
        return prefix + "owner-takeover";
    }

    /**
     * 玩家在线目录：{@code game:presence:{player_id}}，值为 {@code game.api.PlayerPresence} protobuf，带 TTL；
     * gate 是唯一写者（进场写、离场带条件删、在线续期），任何服务可读。player_id 全服唯一（雪花），不分 zone。
     */
    public String presence(long playerId) {
        return prefix + "presence:" + Long.toUnsignedString(playerId);
    }

    /**
     * 玩家位置：{@code game:location:{player_id}}，Hash（字段 {@code e} = owner_epoch 十进制、{@code q} = 写序号、{@code s} = 状态 o 在线 / l 重连租约 / x 已登出、
     * {@code v} = {@code game.discovery.PlayerLocation}），带 TTL；持有归属的 scene 节点是唯一写者（进场 / 换场景写、在线续期、
     * 断线写成重连租约、主动离开写成登出墓碑；按 (epoch, 写序号) 只收更新的写），login 进游戏时读。
     * 跨 zone 传送的待落点（批次 5.4）复用这把键、不另起键：源节点交出并释放之后写 {@code s = l}，值里 zone = 目标 zone、节点号与场景号为 0、
     * 配置号 = 要落的地图，TTL 跟着重定向票据走（至多 300 s）；「{@code l} + 节点号 0」就是待落点，见 {@code PlayerLocationDirectory}。
     */
    public String playerLocation(long playerId) {
        return prefix + "location:" + Long.toUnsignedString(playerId);
    }

    /**
     * gate 排空标记 {@code game:gate-draining:{zone}:{node}}：值为打标记时刻（Redis 服务器时间，Unix 秒），必须带 TTL——
     * 打标记的人中途挂了，到期后这台 gate 自动重新接客（容量不会永久蒸发）。game-data 运维接口写，game-gateway 读。
     */
    public String gateDraining(int zoneId, int nodeId) {
        return prefix + "gate-draining:" + zoneId + ":" + nodeId;
    }

    /**
     * gate 已排空到可以安全下线 {@code game:gate-drained:{zone}:{node}}：值为判定理由（below_threshold / deadline），
     * TTL 取排空标记的剩余 TTL（不比它活得久）。game-gateway 的排空判定循环写，运维 / 缩容脚本读。
     */
    public String gateDrained(int zoneId, int nodeId) {
        return prefix + "gate-drained:" + zoneId + ":" + nodeId;
    }

    /** 场景节点运维疏散标记：Redis TIME 秒:实例，必须带 TTL，game-data 写、scene 读。 */
    public String sceneEvacuating(int zoneId, int nodeId) {
        return prefix + "scene-evacuating:" + zoneId + ":" + nodeId;
    }

    /**
     * 好友列表缓存 {@code game:friend:{<player_id>}:list}（game-friend 读写）：JSON 数组 {@code [{friend_player_id, since_ms}]}，
     * 带 TTL；同一玩家的键共用 hash tag，上 Cluster 时两条 Lua 碰到的键同槽。玩家号按无符号十进制。
     */
    public String friendList(long playerId) {
        return prefix + "friend:{" + Long.toUnsignedString(playerId) + "}:list";
    }

    /** 入站好友申请缓存 {@code game:friend:{<player_id>}:req}（形状同 {@link #friendList}）。 */
    public String friendPending(long playerId) {
        return prefix + "friend:{" + Long.toUnsignedString(playerId) + "}:req";
    }

    /**
     * 版本化缓存的代次键 {@code <数据键>:gen}（好友、帮会共用这一条规则）：写路径失效时写一个新的唯一值（带 TTL）再删数据键，
     * 回填只在代次未变时写——防「写之前读到旧快照的回填」在写之后落地。与数据键共用 hash tag（数据键里的 {@code {...}} 原样保留），
     * 两段 Lua 在 Cluster 下同槽。
     */
    public String cacheGeneration(String dataKey) {
        return dataKey + ":gen";
    }

    /** 好友缓存的代次键 {@code <数据键>:gen}（即 {@link #cacheGeneration}；保留原名，键形状不变）。 */
    public String friendCacheGeneration(String dataKey) {
        return cacheGeneration(dataKey);
    }

    /** 发好友申请的每分钟配额计数 {@code game:friend:{<player_id>}:quota}（固定窗口，INCR + EXPIRE）。 */
    public String friendRequestQuota(long playerId) {
        return prefix + "friend:{" + Long.toUnsignedString(playerId) + "}:quota";
    }

    /** 在线目录翻页的每分钟配额计数 {@code game:friend:{<player_id>}:dir-quota}（固定窗口，INCR + EXPIRE）。 */
    public String friendDirectoryQuota(long playerId) {
        return prefix + "friend:{" + Long.toUnsignedString(playerId) + "}:dir-quota";
    }

    /** 在线目录条目的键前缀（{@link #presence} 的前缀；在线目录按它 SCAN）。 */
    public String presencePrefix() {
        return prefix + "presence:";
    }

    /** 世界频道聊天历史 {@code game:chat:{world}:log}（LIST，新在前，全服一条，game-chat 读写）。 */
    public String chatWorldLog() {
        return prefix + "chat:{world}:log";
    }

    /** 私聊历史 {@code game:chat:{p:<小号>:<大号>}:log}：两个玩家号按无符号排序，A→B 与 B→A 写同一把键。 */
    public String chatPrivateLog(long a, long b) {
        long lo = Long.compareUnsigned(a, b) <= 0 ? a : b;
        long hi = lo == a ? b : a;
        return prefix + "chat:{p:" + Long.toUnsignedString(lo) + ":" + Long.toUnsignedString(hi) + "}:log";
    }

    /** 发言的幂等键 {@code game:chat:{req:<player_id>}:<request_id>}（SET NX EX；request_id 由客户端给、≤ 64 字节）。 */
    public String chatRequestId(long playerId, String requestId) {
        return prefix + "chat:{req:" + Long.toUnsignedString(playerId) + "}:" + requestId;
    }

    /** 发言的每秒限速计数 {@code game:chat:{rl:<player_id>}}。 */
    public String chatRateLimit(long playerId) {
        return prefix + "chat:{rl:" + Long.toUnsignedString(playerId) + "}";
    }

    /**
     * 组队权威记录 {@code game:{team}:rec:<team_id>}（Hash：{@code ver} 版本号、{@code pb} TeamRecord；空闲 24 h 过期）。
     * 组队的四类键统一 hash tag {@code {team}}：写脚本一次要原子写记录、投影与多名玩家的索引，将来上 Cluster 仍同槽（team-spec D2）。
     * 只经 game-team 的 Lua 写。
     */
    public String teamRecord(long teamId) {
        return prefix + "{team}:rec:" + Long.toUnsignedString(teamId);
    }

    /** 组队投影 {@code game:{team}:info:<team_id>}（String：{@code game.discovery.TeamInfo}；game-team 与记录同一段 Lua 写，game-scene 读）。 */
    public String teamInfo(long teamId) {
        return prefix + "{team}:info:" + Long.toUnsignedString(teamId);
    }

    /** 玩家的组队索引 {@code game:{team}:player:<player_id>}（Hash：{@code tid} 所在队，无队为 "0"；{@code epoch} 成员关系版本）。 */
    public String teamPlayer(long playerId) {
        return prefix + "{team}:player:" + Long.toUnsignedString(playerId);
    }

    /** 被邀请人的邀请反查 {@code game:{team}:invite:<player_id>}（ZSET：成员 = team_id，分数 = expire_at_ms；TTL 1 h）。 */
    public String teamInvite(long playerId) {
        return prefix + "{team}:invite:" + Long.toUnsignedString(playerId);
    }

    /**
     * 帮会快照缓存 {@code game:guild:{g:<guild_id>}:snap}（game-guild 读写）：值为 game-guild 自有的 {@code game.guild.GuildSnapshot} protobuf
     * （字段同基线 GuildData，含 score / funds / 成员帮贡），带 TTL；代次键是 {@link #cacheGeneration}（{@code …:snap:gen}）。
     * 基线 {@code guild:v2:{id}}（guild_repo.go:136-140）。帮会号按无符号十进制。
     */
    public String guildSnapshot(long guildId) {
        return prefix + "guild:{g:" + Long.toUnsignedString(guildId) + "}:snap";
    }

    /**
     * 玩家 → 帮会映射缓存 {@code game:guild:{p:<player_id>}:gid}：值为帮会号的无符号十进制，<b>未入帮的 0 也缓存</b>，带 TTL；
     * 代次键是 {@link #cacheGeneration}（{@code …:gid:gen}）。基线 {@code player_guild:v2:{id}}（guild_repo.go:142-144、:258）。
     */
    public String guildOfPlayer(long playerId) {
        return prefix + "guild:{p:" + Long.toUnsignedString(playerId) + "}:gid";
    }

    /**
     * 入帮申请推送冷却 {@code game:guild:{g:<guild_id>}:apply-push:<player_id>}（{@code SET NX PX 60000}，值 "1"）：
     * 同一（帮会, 申请人）60 s 内至多推一次 APPLICATION_RECEIVED。基线 {@code guild:apply_push:{g}:{p}}（guild_manage_repo.go:683-700）。
     */
    public String guildApplyPush(long guildId, long playerId) {
        return prefix + "guild:{g:" + Long.toUnsignedString(guildId) + "}:apply-push:" + Long.toUnsignedString(playerId);
    }

    /** 入帮申请尝试配额，按玩家跨所有目标帮共享；单键 Lua，固定 60 秒窗口。 */
    public String guildApplyQuota(long playerId) {
        return prefix + "guild:{apply:" + Long.toUnsignedString(playerId) + "}";
    }

    /** 终态资产指令已清理的最大截止毫秒（STRING，无 TTL、只增）；回档检查用它限制可证明窗口。 */
    public String guildTerminalCleanupWatermark() {
        return prefix + "guild:asset-op:terminal-cleanup-watermark-ms";
    }

    /**
     * 帮会全服榜 {@code game:guild:{rank}:all}（ZSET：成员 = 帮会号无符号十进制，分数 = 排行分）。排行的全部键共用 hash tag {@code {rank}}，
     * 多键 Lua 与换榜的 RENAME 在 Cluster 下同槽（guild-spec D16）。基线 {@code guild_rank}（guild_repo.go:154）。
     */
    public String guildRankAll() {
        return prefix + "guild:{rank}:all";
    }

    /** 帮会区榜 {@code game:guild:{rank}:zone:<zone_id>}（形状同 {@link #guildRankAll}；只放 zone ≠ 0 的帮）。基线 {@code guild_rank:zone:{z}}。 */
    public String guildRankZone(int zoneId) {
        return prefix + "guild:{rank}:zone:" + Integer.toUnsignedString(zoneId);
    }

    /**
     * 帮会区榜索引 {@code game:guild:{rank}:zones}（SET：成员 = 出现过区榜的 zone_id 无符号十进制），代替基线的 {@code SCAN guild_rank:zone:*}：
     * 入榜脚本 SADD、重建换榜时整体重写，清榜按它找全部区榜键。
     */
    public String guildRankZones() {
        return prefix + "guild:{rank}:zones";
    }

    /** 帮会排行维护锁 {@code game:guild:{rank}:lock}（值 = 持有者令牌，PX 30 s 并由持有者续期）。基线 {@code guild_rank:maintenance_lock}（5 min）。 */
    public String guildRankLock() {
        return prefix + "guild:{rank}:lock";
    }

    /** 重建排行的全服榜临时键 {@code game:guild:{rank}:tmp:<token>:all}（带 PEXPIRE，换榜时 RENAME 成正式键）。 */
    public String guildRankTmpAll(String token) {
        return prefix + "guild:{rank}:tmp:" + token + ":all";
    }

    /** 重建排行的区榜临时键 {@code game:guild:{rank}:tmp:<token>:zone:<zone_id>}。 */
    public String guildRankTmpZone(String token, int zoneId) {
        return prefix + "guild:{rank}:tmp:" + token + ":zone:" + Integer.toUnsignedString(zoneId);
    }

    /**
     * 主世界频道计划 {@code game:world:{z:<zone>}:ch}（HASH：字段 = scene_id 无符号十进制，值 = {@code game.api.WorldChannel} protobuf；无 TTL）。
     * 一个 zone 的频道键（ch / desired / cooldown / ver / leader / resv:*）共用 hash tag {@code {z:<zone>}}，多键 Lua 在 Cluster 下同槽
     * （修基线 scene_atomic.go:51-68 自称 Cluster-safe 实则跨槽，scene-channels-spec §7.1 B17）。只经 {@code WorldChannelStore} 的围栏 Lua 写。
     * 基线 {@code world_channels:zone:{z}:{conf}} + {@code scene:{id}:node} 等多键（world_init.go:25、:216-239；D1）。
     */
    public String worldChannels(int zoneId) {
        return worldZonePrefix(zoneId) + "ch";
    }

    /** 玩家可见分线目录（protobuf STRING，短 TTL）；与该 zone 领导锁/计划同槽。 */
    public String worldChannelDirectory(int zoneId, int configId) {
        return worldZonePrefix(zoneId) + "directory:" + Integer.toUnsignedString(configId);
    }

    /** 玩家主动切线冷却（STRING，短 TTL）；全局 player 维度，跨节点/zone不重置。 */
    public String channelSwitchCooldown(long playerId) {
        return prefix + "player:" + Long.toUnsignedString(playerId) + ":channel-switch-cooldown";
    }

    /**
     * 每图期望频道数 {@code game:world:{z:<zone>}:desired}（HASH：conf 无符号十进制 → 期望数十进制；无 TTL；首次 HSETNX 播种，此后 Redis 为权威）。
     * 基线 {@code world_channels:desired:zone:{z}}（world_autoscale.go:38、:73-117）。
     */
    public String worldDesired(int zoneId) {
        return worldZonePrefix(zoneId) + "desired";
    }

    /**
     * 扩缩容冷却 {@code game:world:{z:<zone>}:cooldown}（HASH：conf 无符号十进制 → 冷却到期毫秒，Redis TIME；过期字段由领导者顺手 HDEL）。
     * 基线每图一个 SETEX 键 {@code world_channels:cooldown:zone:{z}:{conf}}（world_autoscale.go:44、:491-504）。
     */
    public String worldCooldown(int zoneId) {
        return worldZonePrefix(zoneId) + "cooldown";
    }

    /**
     * 频道计划版本号 {@code game:world:{z:<zone>}:ver}（STRING 整数，只增不减、不过期；不存在当 0）：围栏 Lua 每次写入把它置为批次定好的新值
     * （max(旧值 + 1, Redis TIME 毫秒)，见 WorldPlanBatch——Redis 丢写后也不会让两次不同的写入撞号），
     * 领导者据此做 CAS，scene 节点每秒 GET 一次、变了才整读计划。
     */
    public String worldPlanVersion(int zoneId) {
        return worldZonePrefix(zoneId) + "ver";
    }

    /** 永久退役原件，与本区计划、目录和预占同槽；不设TTL、不作为可删除热数据。 */
    public String worldRetirement(int zoneId) { return worldZonePrefix(zoneId) + "retired"; }

    /** 已支持永久退役协议的目录版本；用于维护工具的部署前置核验。 */
    public String zoneDirectoryFormat(int zoneId) { return worldZonePrefix(zoneId) + "directory-format"; }

    public String zoneNodeDirectory(String nodeType, int zoneId) { return worldZonePrefix(zoneId) + "nodes:" + nodeType; }

    public String zoneNodeExpiry(String nodeType, int zoneId) { return worldZonePrefix(zoneId) + "node-expiry:" + nodeType; }

    /** 已迁入v2的节点号水位；条目过期也不恢复旧目录原件，不清理。 */
    public String zoneNodeSeen(String nodeType, int zoneId) { return worldZonePrefix(zoneId) + "node-seen:" + nodeType; }

    /** 所有曾使用v2目录的类型，退休脚本核对完整集合后同拍检查各类型活节点。 */
    public String zoneNodeTypes(int zoneId) { return worldZonePrefix(zoneId) + "node-types"; }

    public String worldZoneKeysPrefix(int zoneId) { return worldZonePrefix(zoneId); }

    public String nodeIdsInZone(int zoneId) { return prefix + "node-id:*:" + zoneId + ":*"; }

    /**
     * 频道计划的分 zone 领导锁 {@code game:world:{z:<zone>}:leader}（STRING：持有者令牌 {@code <instanceId>:<uuid>}，PX = 锁 TTL，持有者续期）。
     * 基线全局一把 {@code scene_manager:leader:lock}（scene_manager_service.go:94-101），Java 每 zone 一把（D2）。
     */
    public String worldLeader(int zoneId) {
        return worldZonePrefix(zoneId) + "leader";
    }

    /**
     * 进场软预占 {@code game:world:{z:<zone>}:resv:<scene_id>}（ZSET：成员 = player_id 无符号十进制，分数 = 到期毫秒（Redis TIME）；
     * 键 PEXPIRE = 预占 TTL）。取代基线 {@code instance:{id}:player_count} 的 INCR / DECR 硬计数（scene_atomic.go:69-101；D7）。
     */
    public String worldReservations(int zoneId, long sceneId) {
        return worldZonePrefix(zoneId) + "resv:" + Long.toUnsignedString(sceneId);
    }

    /**
     * 出现过 scene 节点的 zone 集合 {@code game:world:zones}（SET：zone_id 无符号十进制；无 TTL、只增）：scene 启动时 SADD，
     * scene-manager 据此决定竞选哪些 zone 的领导锁（基线「活跃 zone = etcd 里出现过的 zone」，load_reporter.go:579-595）。
     * 退役 zone 要运维手工 SREM（scene-channels-spec §7.2 第 7 条）。
     */
    public String worldZones() {
        return prefix + "world:zones";
    }

    /**
     * 玩家持久收件箱后台维护的领导锁 {@code game:inbox:leader}（STRING：持有者令牌 {@code <instanceId>:<uuid>}，PX = 锁 TTL，持有者续期）。
     * 清理、提醒补发、扫描型 gauge、对账只在持有者上跑（mmorpg {@code player_inbox:maintenance:leader}，设计 §10）。全服一把，game-inbox 写。
     */
    public String inboxMaintenanceLeader() {
        return prefix + "inbox:leader";
    }

    private String worldZonePrefix(int zoneId) {
        return prefix + "world:{z:" + Integer.toUnsignedString(zoneId) + "}:";
    }

    /**
     * 热关停规则 {@code game:killswitch}（哈希：字段 = 规则键 {@code pkg.Service/Method}、{@code Service/*}、{@code *}，值 = 规则；
     * 各进程每秒全量读一次，见 {@code RedisKillSwitchSync}）。运维经 game-data 的 {@code /admin/killswitch} 写。
     */
    public String killSwitch() {
        return prefix + "killswitch";
    }

    /** 登录排队：区的队列 {@code game:login-queue:{zone}}，ZSET（成员 = 排队号，分数 = 入队毫秒）。game-gateway 读写。 */
    public String loginQueue(int zoneId) {
        return prefix + "login-queue:" + zoneId;
    }

    /**
     * 登录排队：区里已放行、还没进到 gate 的占位 {@code game:login-queue-admitted:{zone}}，ZSET（成员 = 排队号或 {@code fast:<uuid>}，
     * 分数 = 占位到期毫秒，过期的计数前清掉；客户端取走放行槽后再留 15 s，等 gate 把人数发布出来）。
     */
    public String loginQueueAdmitted(int zoneId) {
        return prefix + "login-queue-admitted:" + zoneId;
    }

    /**
     * 登录排队：放行时选好的 gate {@code game:login-queue-admit:{queue_id}}（{@code game.api.GateNodeInfo}，带 TTL，取走即删）。
     * 放行脚本拿 {@code loginQueueAdmit("")} 当前缀拼键，排队号必须在键尾。
     */
    public String loginQueueAdmit(String queueId) {
        return prefix + "login-queue-admit:" + queueId;
    }

    /**
     * 登录排队：一个排队条目的元数据 {@code game:login-queue-meta:{queue_id}}，Hash（zone / created / admitted；取走放行槽后加
     * taken_by / slot——同一请求被 Redisson 重发时据此认出），带 TTL。放行脚本拿 {@code loginQueueMeta("")} 当前缀拼键，排队号必须在键尾。
     */
    public String loginQueueMeta(String queueId) {
        return prefix + "login-queue-meta:" + queueId;
    }

    /** 开服限流：区的令牌桶 {@code game:rl:zone:{zone}}（Hash：t = 余量 ×1000、ms = 上次补充毫秒），闲置 1 h 过期。game-gateway 读写。 */
    public String rateLimitZone(int zoneId) {
        return prefix + "rl:zone:" + zoneId;
    }

    /** 开服限流：单 IP 的令牌桶 {@code game:rl:ip:{ip}}（同区桶的形状），闲置 1 h 过期。{@code ip} 是解析好的 IP 字面量（或对端地址）。 */
    public String rateLimitIp(String ip) {
        return prefix + "rl:ip:" + ip;
    }

    /**
     * 开服限流：同一身份同一 IP 的冷却 {@code game:rl:cd:{scope}:{subject}}（scope = login / assign；subject = 身份的 SHA-256 前缀 + IP），
     * TTL = 冷却时长。
     */
    public String rateLimitCooldown(String scope, String subject) {
        return prefix + "rl:cd:" + scope + ":" + subject;
    }

    /** 登录排队：放行循环的选主锁 {@code game:login-queue-dispatcher}（全部 game-gateway 只有一个在放行）。 */
    public String loginQueueDispatcherLock() {
        return prefix + "login-queue-dispatcher";
    }

    /**
     * 服务端 → gate 的推送频道：{@code game:gate-push:{zone}:{gate 节点号}}，消息为 {@code game.api.GatePush} protobuf。
     * 每个 gate 节点订阅自己的频道；节点号按 zone 分配，所以频道名带 zone。
     */
    public String gatePushTopic(int zoneId, int gateNodeId) {
        return prefix + "gate-push:" + zoneId + ":" + gateNodeId;
    }

    /**
     * 全服产出封禁名单：{@code game:gain-block:{category}}（category 是 {@link #GAIN_BLOCK_CURRENCY} 或
     * {@link #GAIN_BLOCK_ITEM}），Hash，字段为被封的 id（十进制），值为封禁元数据 JSON（操作人、时刻、原因，只给运维看）。全服一份、不分 zone；game-data 运维接口是唯一写者，
     * 全部 scene 节点读。
     */
    public String gainBlocks(String category) {
        return prefix + "gain-block:" + category;
    }

    /** 全服产出封禁名单变更通知的 pub/sub 频道：{@code game:gain-block-changed}，消息体是 category（收到就重读名单）。 */
    public String gainBlockChangedTopic() {
        return prefix + "gain-block-changed";
    }

    /**
     * 登录 access token：{@code game:login:access:{<family>}:<token>}，值为令牌数据 JSON（账号、认证方式、设备号、签发秒、家族号），
     * TTL = access 有效期。令牌是 43 字符的 base64url（不含 {@code :}），前 16 字节即家族号；家族号（22 字符 base64url，不含花括号）
     * 作 hash tag，一个令牌家族的全部键同在一个 Cluster slot（基线 {@code access_token:{<fid>}:<token>}）。不分 zone。
     */
    public String loginAccessToken(String familyId, String token) {
        return prefix + "login:access:{" + familyId + "}:" + token;
    }

    /** 口令失败配额 v1：域为 known / unknown，身份仅传原值 UTF-8 的完整 SHA-256，单键单槽。 */
    public String loginPasswordFailure(String domain, String digest) {
        return prefix + "login:password-failure-v1:{" + domain + ":" + digest + "}";
    }

    /**
     * 登录 refresh token：{@code game:login:refresh:{<family>}:<token>}，值同 access，TTL = refresh 有效期；一次性（轮换时原子作废）。
     * 与同家族的其余键同 slot（见 {@link #loginAccessToken}）。
     */
    public String loginRefreshToken(String familyId, String token) {
        return prefix + "login:refresh:{" + familyId + "}:" + token;
    }

    /**
     * refresh 轮换记录：{@code game:login:refresh-rotated:{<family>}:<旧 token>}，值为那次轮换换出的一对（JSON），TTL = 重放宽限窗口
     * （≤ 60s）。窗口内同一旧 token 再来、且新 refresh 仍未用过、家族仍在时据此返回同一对（基线 {@code refresh_rotated:{<fid>}:<token>}）。
     */
    public String loginRefreshRotated(String familyId, String token) {
        return prefix + "login:refresh-rotated:{" + familyId + "}:" + token;
    }

    /**
     * refresh 令牌家族记录：{@code game:login:refresh-family:{<family>}}，HASH {account, head, prev, created_at, rotated_at}，
     * TTL = 家族头的 refresh 有效期（与头同一步写入、同一刻过期）。记录不在 = 家族已吊销或已结束：该家族的 refresh 不能轮换、
     * access 不能登录（基线 {@code refresh_family:{<fid>}}）。
     */
    public String loginRefreshFamily(String familyId) {
        return prefix + "login:refresh-family:{" + familyId + "}";
    }

    /**
     * 账号的令牌家族索引：{@code game:login:account-refresh:<account>}，ZSET，成员是家族号、分数是家族的过期 Unix 秒。
     * 只做单键操作（不进任何多键脚本），用于每账号家族数封顶与吊销全部；家族本身的有效性只看家族记录。
     */
    public String loginAccountRefresh(String account) {
        return prefix + "login:account-refresh:" + account;
    }

    /**
     * 账号处于「已登录、未进游戏」窗口的连接：{@code game:login:devices:{account}}，ZSET，成员是会话键（gate 实例 / 会话号）、
     * 分数是该成员的过期 Unix 毫秒（设备数上限按它自愈）。
     */
    public String loginDevices(String account) {
        return prefix + "login:devices:" + account;
    }

    /**
     * 会话当前计在哪个账号的设备名单里：{@code game:login:device-session:{会话键}}，值为账号，TTL 同设备登记。
     * 断线时 gate 记的账号可能是空的（登录应答没送到 gate），按它找回名单注销。会话结束后值为空串（关闭墓碑，TTL 60 s，
     * 挡住迟到的登记，见 game-login {@code LoginDevices#close}）。
     */
    public String loginDeviceSession(String sessionKey) {
        return prefix + "login:device-session:" + sessionKey;
    }

    /**
     * 回合制战斗锁（scene-battle-spec §7.2，D2）：{@code game:battle:{<player_id>}:lock}，Hash——{@code b} battle_id（无符号十进制）、
     * {@code n} battle 节点号、{@code s} 阶段 {@code P} / {@code F}、{@code d} 战斗期限毫秒、{@code p} 备战期限毫秒；带 TTL。
     * 锁与上下文合成一个键，「同生共死」由结构保证。scene 是唯一写者；match / team / guild 经 {@code BattleLockReader} 只读。
     * 与待结算记录 {@link #battleSettlements} 共用 hash tag，销账 / 取代两段 Lua 在 Cluster 下同槽。
     */
    public String battleLock(long playerId) {
        return prefix + "battle:{" + Long.toUnsignedString(playerId) + "}:lock";
    }

    /**
     * 回合制战斗待结算记录（scene-battle-spec §7.2，D13）：{@code game:battle:{<player_id>}:settlement}，Hash——字段名 = battle_id（无符号十进制），
     * 值 = 契约 {@code BattleSettlementEvent} 字节；整键 TTL 7 天，每次写刷新。battle 落库，scene 销账时只删字段。
     */
    public String battleSettlements(long playerId) {
        return prefix + "battle:{" + Long.toUnsignedString(playerId) + "}:settlement";
    }

    /**
     * 回合制战斗「已销账」墓碑（Java 独有，按 (玩家, 战斗) 一个键）：{@code game:battle:{<player_id>}:settled:<battle_id>}，String {@code "1"}，
     * TTL = {@code BattleRedis.SETTLED_TOMBSTONE_TTL_SEC}（10 min）。销账的两段 Lua（scene 的 {@code ACK}、battle 的 {@code ACK_IF_SUPERSEDED}
     * 删记录分支）顺手写，battle 的落库 {@code STORE_SETTLEMENT} 见到它就不写——防迟到 / 被重放的落库落在销账之后把记录重新造出来、
     * 下次进场重复发奖。与锁 {@link #battleLock}、待结算记录 {@link #battleSettlements} 共用 hash tag（三键同槽）；
     * 只许经 {@code KEYS} 传进脚本，不许在 Lua 里用 ARGV 拼。两个号都按无符号十进制。
     */
    public String battleSettled(long playerId, long battleId) {
        return prefix + "battle:{" + Long.toUnsignedString(playerId) + "}:settled:" + Long.toUnsignedString(battleId);
    }

    /**
     * 活动局结果的持久副本（scene-battle-spec §7.17）：{@code game:battle:activity-result:<battle_id>}，String = 契约 {@code BattleResultEvent} 字节，
     * TTL 7 天；battle 写，消费方（4.6 帮会）消费后删。
     */
    public String battleActivityResult(long battleId) {
        return prefix + "battle:activity-result:" + Long.toUnsignedString(battleId);
    }

    // ------------------------------------------------------------------ 匹配（game-match，批次 6.4，match-spec §9.4）
    //
    // 全部 match 键共用一个 hash tag {match}：票据、队列、弹组标记、切磋记录、落点记录同槽，入队 / 取消 / 弹组 / 回队首 / 切磋的发起与消费
    // 都能各用一段 Lua 原子完成（先例 {team}，team-spec D2）；基线票据不带 tag、与队列跨 slot，才需要三条自愈路径。只有 game-match 写这些键。

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

    /** 活跃队列注册集 {@code game:{match}:index}（SET：成员 = 队列键全文；代替 SCAN。非空队列一定在里面，由入队 / 回队首的同一段 Lua 保证）。 */
    public String matchQueueIndex() {
        return matchPrefix + "index";
    }

    /**
     * 排队队列 {@code game:{match}:queue:<mode>:<config>}（LIST：成员 = player_id 无符号十进制；队尾入队、队首等得最久、回队首用 LPUSH）。
     * mode 是 {@code MatchMode} 的数值，config 是 {@code battle_config_id} 的无符号十进制——config 不校验，每个不同的值一条队列（照搬基线）。
     */
    public String matchQueue(int mode, int config) {
        return matchQueuePrefix + queueSuffix(mode, config);
    }

    /** 队列的评分镜像 {@code game:{match}:rank:<mode>:<config>}（ZSET：成员 = player_id，分数 = 入队时的评分 × 100；成员集合恒等于队列的成员集合）。 */
    public String matchRank(int mode, int config) {
        return matchPrefix + "rank:" + queueSuffix(mode, config);
    }

    /** 凑单锁 {@code game:{match}:lock:<mode>:<config>}（STRING：值 = 实例 id，PX 10 s，按持有者释放；只是效率手段，正确性靠弹组脚本）。 */
    public String matchQueueLock(int mode, int config) {
        return matchPrefix + "lock:" + queueSuffix(mode, config);
    }

    /**
     * 解析注册集里的一个队列键（{@link #matchQueue} 的逆）。只认规范形：mode 是不带符号与前导零的十进制 int，config 是不带前导零的 uint32 十进制；
     * 其它一律为空（凑单把它当坏成员告警并跳过）。{@code parseMatchQueue(matchQueue(m, c))} 恒还原出 (m, c)。
     */
    public Optional<MatchQueueId> parseMatchQueue(String key) {
        if (key == null || !key.startsWith(matchQueuePrefix)) {
            return Optional.empty();
        }
        String rest = key.substring(matchQueuePrefix.length());
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
     * 排队票据 {@code game:{match}:ticket:<player_id>}（HASH：{@code ticket} / {@code mode} / {@code config} / {@code state} / {@code enqueued_at_ms} /
     * {@code zone_id} / {@code queue_key} / {@code rating_centi} / {@code team_id} / {@code battle_id} / {@code not_before_ms}；TTL：queued 6 h、
     * matched 按人数公式、ready 60 s）。每个玩家至多一张；所有写都带 ticket id 做 CAS。
     */
    public String matchTicket(long playerId) {
        return matchPrefix + "ticket:" + Long.toUnsignedString(playerId);
    }

    /** 弹组重放标记 {@code game:{match}:pop:<token>}（STRING，PX 60 s；Java 独有：弹组脚本被 Redisson 重发时凭它认出「已经弹过了」）。 */
    public String matchPopMarker(String token) {
        return matchPrefix + "pop:" + token;
    }

    /**
     * 回队首重放标记 {@code game:{match}:requeue:<token>}（STRING：值 = 那一次放回队首的人数，PX 60 s；Java 独有：回队首脚本被 Redisson 重发时
     * 凭它认出「这一次已经回过了」——只看票据状态认不出来，回了队首的人可能已被再弹成 matched）。
     */
    public String matchRequeueMarker(String token) {
        return matchPrefix + "requeue:" + token;
    }

    /** 切磋记录 {@code game:{match}:challenge:<challenge_id>}（HASH：发起者、目标、配置、过期时刻；TTL 60 s）。 */
    public String matchChallenge(long challengeId) {
        return matchPrefix + "challenge:" + Long.toUnsignedString(challengeId);
    }

    /** 目标的待应答占坑 {@code game:{match}:challenge-target:<player_id>}（STRING：值 = challenge_id；TTL 60 s；一个人同时只能有一条待应答的邀请）。 */
    public String matchChallengeTarget(long playerId) {
        return matchPrefix + "challenge-target:" + Long.toUnsignedString(playerId);
    }

    /** 切磋消费墓碑 {@code game:{match}:challenge-done:<challenge_id>}（HASH：被消费记录的字段 + 请求 nonce，PX 60 s；Java 独有：消费脚本重发时原样返回）。 */
    public String matchChallengeDone(long challengeId) {
        return matchPrefix + "challenge-done:" + Long.toUnsignedString(challengeId);
    }

    /**
     * 战斗落点记录 {@code game:{match}:battle:<battle_id>}（HASH：{@code a} = attempt 十进制、{@code pb} = game-match 的 {@code BattlePlacement} 字节；
     * TTL 360 s）。gather 在建房<b>之前</b>写（只收 attempt ≥ 已存值的写）；179 补签按它直拨 battle；6.5 观战复用同一条记录。
     */
    public String matchBattlePlacement(long battleId) {
        return matchPrefix + "battle:" + Long.toUnsignedString(battleId);
    }

    /**
     * 观战标记 {@code game:{match}:watching:<player_id>}（批次 6.5，spectate-spec §4.2；STRING：值 = {@code "<battle_id 无符号十进制>:<nonce>"}，
     * nonce 是每次 163 随机生成的 16 位十六进制；PX 360 s）。一个玩家至多一条：「他可能正在看哪一场」。与票据 {@link #matchTicket} 同槽，
     * 所以「没有票据 ∧ SET NX」能在一段 Lua 里原子完成；删除一律按值（带 nonce 的整串）比较，不无条件 DEL。
     */
    public String matchWatching(long playerId) {
        return matchPrefix + "watching:" + Long.toUnsignedString(playerId);
    }

    /**
     * 可观战索引 {@code game:{match}:watchable}（批次 6.5，spectate-spec §4.2；ZSET，无 TTL：成员 = battle_id 无符号十进制，
     * 分数 = 落点记录的 {@code created_at_ms}——Unix 毫秒 &lt; 2^53，double 精确）。gather 开局成功、落点按同一个 attempt 补写之后才 ZADD；
     * 与落点记录 {@link #matchBattlePlacement} 同槽，「读落点 + 查是否已公开」「按 attempt 守护的剔除」都各是一段 Lua。
     */
    public String matchWatchable() {
        return matchPrefix + "watchable";
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
