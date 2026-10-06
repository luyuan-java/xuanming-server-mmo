# 功能清单：Go 帮会服务（go/guild）

基线：mmorpg `26ceb70ca`；Java 仓库 xuanming-server-mmo 当前工作区。

## 区域概述

go/guild 是一个独立的 gRPC 微服务（go-zero），客户端经 gate → client_rpc_router 透传会话元数据 `x-session-detail-bin` 访问 `GuildService`（28 个消息号：8、15、19、27、29、35、38、39、52、53、60、76、120、216–223、228、233、239–243），身份一律取会话、不信请求体。它独占 MySQL 库 `mmorpg_guild`（guild / guild_player_state / guild_member / guild_application / guild_player_op_seq / guild_asset_op / guild_daily_counter / guild_activity_progress），Redis 做快照缓存（带代次的 cache-aside）与区服排行 ZSET，经 Kafka gate 命令推送 `GuildChangedS2C`（220），并经通用资产通道（outbox + 重投循环 → scene `AssetDebit/AssetCredit/AssetAbortDebit`）扣货币、发物品。功能分批落地：B1/B2 建帮与成员管理和申请审批，B3b 展示名，B5 经济（捐献 / 升级 / 商店），B6a 活动（元宵灯会 / 中秋团圆）；同道历练（B6b）在 Go 里只是桩，固定回 kGuildActivityNotOpen。**Java 版目前完全没有帮会**：gate 的 `MessageRoutes.SERVICE_BACKENDS` 只有 `ClientPlayerLogin`，所以所有 GuildService 消息号都走 `ClientDispatcher` 的 default 分支，回一条 1003 kServiceUnavailable tip（xm-gate/src/main/java/com/game/gate/session/ClientDispatcher.java:253-257）。仅有的帮会相关 Java 能力是：五张帮会配表的类型化访问（xm-table 生成的 `GuildRuleRows / GuildLevelRows / GuildDonateRows / GuildShopRows / GuildActivityRows`），以及通用的按消息号限频（MessageLimiter 表同样覆盖帮会消息号）。

客户端（mmorpg-client `Assets/Scripts/Game/Guild/GuildClient.cs`）已经用上的：建帮 / 查询 / 申请审批 / 管理 / 公告 / 退帮 / 解散 / 排行（27）/ 捐献 / 升级 / 商店，以及 220 推送；**活动消息 239–243 和 52 GetGuildRankByGuild 目前没有客户端调用**。

## 功能

### guild-service-access — 帮会服务进程与客户端接入
- mmorpg: go/guild/guild.go、go/guild/internal/server/guild_server.go、go/guild/internal/session/session.go、go/client_rpc_router/internal/logic/forwardlogic.go、go/guild/etc/guild.yaml
- client messages: GuildService 全部 28 个消息号（C2S，均带 request id 的应答）；8 UpdateGuildScore 与 220 NotifyGuildChanged 不对客户端开放
- tables: MessageLimiter（帮会消息号行：读 10 次/秒、写 5 次/秒）
- depends on: gate 路由、Dubbo 调用方鉴权、guild-storage-tx、guild-zone-isolation
- behavior: 身份 = gate 会话 player_id，请求体里的 player_id / zone_id 对客户端调用一律忽略（不一致只打日志）。会话元数据解不开 → Unauthenticated；方法不在白名单 `ClientMethods` → PermissionDenied。gRPC 错误在路由服统一变成**带请求 id 的信封** `MessageContent{id, message_id, error_message{1003}}`。业务拒绝走响应体里的 `error_message`（14000–14031 段），故障才走 gRPC 错误（会触发客户端重连隔离）。所以写冲突、合服、名字重复等都必须回 tip，不能回错误。整请求预算 = Timeout − 500ms（3500ms）。无会话的内部调用（GM / 工具）沿用请求体字段；B2 起新增的管理 / 申请 / 经济 / 活动 RPC 对无会话调用直接 PermissionDenied。
- internal: 新进程 xm-guild（Spring Boot + Dubbo）。gate 的 `MessageRoutes.SERVICE_BACKENDS` 加一行 `GuildService → guild`，dispatcher 加 guild 域的 Dubbo 转发（照 login 的 ClientMessageService）。白名单在 guild 侧显式登记、默认拒绝。killswitch（etcd 键热关停）、Prometheus 低基数指标（guild_economy_requests_total 等，label 不放 id）、雪花槽位申领。
- java: missing — ClientDispatcher.java:253-257 对所有非 login / scene 域回 1003；MessageRoutes.java 的 SERVICE_BACKENDS 只有 ClientPlayerLogin；没有 xm-guild 模块。限频已通用实现（TableMessageLimits，见 PARITY「gate 按消息号限频」行）。
- size: M
- robot: robot/guild_smoke_scenario.go 第 8 步（伪造请求体 player_id）、第 9 步（客户端发 UpdateGuildScore → 信封 1003，且分数仍为 0）
- hazards: Java gate 现在的「不支持」回法是一条独立的 tip 推送（`message_id = tipMessageId`，**不带请求 id**，ClientDispatcher.java:681-686），而 mmorpg 路由服回的是带原请求 id / message_id 的信封。接入 guild 后，内部方法拒绝和故障都必须回信封，否则 robot 第 9 步与客户端的请求回调对不上。220 的应答类型是 Empty，Java 的 MessageRoutes.hasResponse 会把它判成「不回包」；mmorpg 里客户端发 220 会收到 1003 信封，Java 要特意保持这一行为。UpdateGuildScore 在 mmorpg 里没有任何服务端调用方（只有 robot 用来测拒绝）。

### guild-storage-tx — 帮会库表与写事务基座
- mmorpg: proto/guild/guild_db.proto、go/guild/internal/data/tables.go、go/guild/internal/data/guild_manage_repo.go（inTx / retryOnDeadlock / 锁序 / 全局插入守卫）、go/guild/internal/data/server_version.go、go/schemamigrate
- client messages: none（间接：kGuildBusyRetry 14021、kGuildIdGenUnavailable 14008）
- tables: none
- depends on: MySQL（Java：xm_java 库 + MyBatis + Druid）
- behavior: 写冲突（死锁重试 3 次耗尽、锁等待超过 1s、子预算到期、COMMIT 结果不明）一律回 tip 14021，让玩家原地重试。帮名全服唯一（按 name_norm），一人至多一个帮（uk player_id）。
- internal: 8 张表（列见 guild_db.proto），全部 READ COMMITTED。锁序固定：guild → guild_player_state → guild_member → guild_application → guild_player_op_seq → guild_asset_op → guild_daily_counter → guild_activity_progress。同表多行按主键升序逐行点锁。锁定读只做完整主键等值（guild_member 用 FORCE INDEX(PRIMARY)）。凡是插入或删除 guild_member 的事务，先锁该玩家的 guild_player_state 行（缺行就在事务外 INSERT IGNORE）。player_id=0 的哨兵行是全局插入守卫：建帮与审批通过都要先锁它。单次尝试的子预算 1.5s（解散 2.5s）；DSN 钉死 innodb_lock_wait_timeout=1；推送与缓存失效只在提交之后做；启动时检查数据库版本（MySQL 8.0.29+ 或 TiDB），缺表 / 缺索引就拒绝启动。
- java: missing — 没有任何帮会表或 DDL；xm-player-store 有 MyBatis 与 Druid 的现成做法可以照搬。
- size: L
- robot: none（并发回归在 Go 单测 guild_lock_order_mysql_test.go 里）
- hazards: 大量篇幅都是为 InnoDB 与 TiDB 的死锁做的锁序论证（唯一二级索引查重的 S next-key、删除标记记录上的 S→X 升级）。Java 实现同样的写集时，必须沿用「先锁状态行、哨兵串行化查重插入、点操作」这套纪律，否则「两个帮同时审批同一人」这种正常玩法会稳定撞出 1213。没有 Kafka 或别的异步写，所有写都是同步事务。

### guild-cache-mapping — 帮会快照缓存与玩家→帮会映射
- mmorpg: go/guild/internal/data/guild_repo.go（GetGuild / GetPlayerGuildID / 代次 Lua 脚本）、guild_manage_repo.go（invalidateAfterCommit / VerifyPlayerGuildID / ResolvePlayerGuild）
- client messages: none（影响 35 / 60 等全部读路径）
- tables: none
- depends on: guild-storage-tx、Redis（Java：Redisson，键经 RedisKeys，前缀 xm:）
- behavior: 刚入帮或刚被踢的玩家，最迟在下一次读时看到正确状态。客户端的 GetPlayerGuild 在两种情况下以 MySQL 复核：缓存说他没入帮，或者快照里没有他。
- internal: 键 `guild:v2:{id}`、`player_guild:v2:{pid}`，各配一个 cache_generation 计数键。读未命中时，只有「读库前看到的代次」仍是当前代次才回填（Lua 比较后再 SET PX）；写在提交之后执行 INCR 代次 + DEL。失效失败就在后台按退避重试（约 2.1s），重试用尽计指标；同一个键的并发读做 singleflight；默认 TTL 30m。授权一律不看缓存，只看锁内的 MySQL 行。
- java: missing
- size: M
- robot: guild_smoke 第 6 步（审批后 B 立刻在 GetPlayerGuild 里看到自己）
- hazards: 映射缓存过期时，退帮和解散只能信事务内的权威行：退帮遇到「不是成员」时要复核，复核结果为 0 才算幂等成功，结果是别的帮就回 14000 让客户端刷新；**绝不能**按缓存里的旧 guild_id 去删别的帮的成员行。

### guild-config-validation — 帮会配表的启动期 fail-closed 校验
- mmorpg: go/guild/internal/logic/guild_manage_logic.go（validateGuildTables）、go/guild/internal/logic/economy_config.go（ValidateEconomyTables / MaxBuyCount / ValidateAssetOpTiming）、go/guild/internal/activity/config.go（ValidateTables）
- client messages: none
- tables: GuildRule、GuildLevel、GuildDonate、GuildShop、GuildActivity、Item（max_stack_size）、Reward、Dungeon
- depends on: Java 配表层 com.game.table.ConfigTables
- behavior: 坏表拒绝启动。GuildRule[1]：application_expire_hours 1–720、每人待审上限 1–10、每帮待审上限 1–500，资产两列 > 0，activity_join_min_hours 0–720，trial_invite_ttl 10–120，cooldown 0–600。GuildLevel：id 从 1 连续；max_members 2–100 且随等级不降；max_officers < max_members 且不降；只有最后一级的 upgrade_cost_funds = 0。GuildDonate：currency_type 只能是 0 或 1；每种货币至多 2 行；contribution_gain 与 funds_gain 至少一个 > 0；daily_limit ≥ 1；min_guild_level 不超过最高级。GuildShop：三个分类各至少 1 行；item_count 在 1..max_stack 之间；cost 在 1..1e9；limit_period 为 0 时 limit_count 必须为 0。GuildActivity：同一类型的启用行档期不得重叠；奖励包非空、≤16 种物品；历练人数满足 2 ≤ min ≤ max ≤ 5；团圆阈值的生效值 = max(行值, reunion_min_online_members)，两者都为 0 拒绝启动。
- internal: 启动时、注册节点之前执行；校验函数要写成纯函数，方便单测造坏样例。
- java: partial — xm-table 已能按 schema 生成并加载五张帮会表（`GuildRuleRows`、`GuildLevelRows`、`GuildDonateRows`、`GuildShopRows`、`GuildActivityRows`；ConfigTablesTest.java:43-46 证明 GuildActivity 缺数据时按空表加载）。帮会专用的业务校验完全没有。
- size: M
- robot: none
- hazards: 当前导出的 guildrule.json 只有第 1–7 列，B6 的三列（activity_join_min_hours、trial_invite_ttl_seconds、trial_invite_cooldown_seconds）在数据里缺失，读出来都是 0。若照 Go 注释的范围校验 trial_invite_ttl（10–120），现在的数据会拒绝启动。GuildActivity 没有导出数据（manifest 里没有它），所以活动在两版都只能显示为空。

### guild-zone-isolation — 按归属区隔离与合服闸门
- mmorpg: go/guild/internal/logic/home_zone.go、merge_fence.go、guild_logic.go（clientZone / visibleIn / mergeFenceTip）、tools/merge_zone/guild_step.go
- client messages: 15、60、29、38、39、27、52、218 以及全部管理写（C2S）
- tables: none
- depends on: 玩家归属区（mmorpg：data_service 的 player:zone:{id}；Java：player.zone_id 列）
- behavior: 建帮落在本人的归属区，请求体里的 zone_id 忽略。客户端只看得见、只能申请本区的帮会，别区的帮会回「不存在」14001；排行只看本区榜；查不到归属区回 14012。合服期间（`merge:in_progress:{zone}` 键存在、或者读不到）写操作回 14013，按 fail-closed 处理。解散、经济、活动在事务里再按锁住的 guild.zone_id 判一次闸门。GetPlayerGuild 不按区过滤（自己的帮永远看得见）。经济和活动的写 RPC 不查归属区：成员一定与帮会同区。
- internal: 归属区查询有 1.5s 超时；查询失败按故障处理，映射缺失回 tip。
- java: missing — xm-player-store 的 PlayerRow 有 zoneId 列（PlayerRow.java:11），可以直接拿来当归属区；Java 没有合服（architecture.md §10 首批不做），合服闸门暂时可以做成恒放行，但接口要留好。
- size: S
- robot: guild_smoke 第 1 步（伪造 zone_id）、第 4 步（别区的 C 看不到帮、查不到名次、无法申请、用同名建帮回 14010）
- hazards: 帮名全服唯一、不分区（合服时不需要改名）。解散清榜只能用事务内读到的 zone（缓存里的 zone 在合服后会过期）。UpdateGuildScore 忽略请求里的 zone_id。

### guild-presence-names — 成员在线状态与展示名
- mmorpg: go/guild/internal/logic/online_status_resolver.go、player_name_resolver.go（data_service BatchGetPlayerName）
- client messages: 填进 35 / 60 / 15 / 221 / 222 / 27 / 52 的 online、name、leader_name 字段
- tables: none
- depends on: 玩家会话目录（mmorpg：player_locator 的 `player:session:{id}`）、玩家名
- behavior: online 与 name 都是展示字段：查不到就留 false 或空串，不让读请求失败（fail-open），名字批量查询上限 800ms。唯一例外是团圆在线人数，用严格版：任何一人读不到就整体按故障返回。
- internal: 一次 MGET 批量取在线状态、一次批量取名字（成员 id 与帮主 id 合并后去重）。排行一页的帮主名也只批量查一次。
- java: missing — Java 没有「玩家 → 在线会话」的 Redis 目录（RedisKeys 只有节点相关键）；玩家名在 xm-player-store 的 player.name 列，可以批量查。在线状态需要新建会话目录，或者问 gate / scene 的归属。
- size: S
- robot: guild_smoke 第 6 步 member-names（快照里每个 name 与 leader_name 都非空）
- hazards: Go 的 MGET 没有独立超时，locator Redis 卡住时会吃光整个请求预算；Java 实现时给在线查询加独立超时。

### guild-create — 建帮
- mmorpg: go/guild/internal/logic/guild_logic.go（CreateGuild / normalizeGuildName / mintGuildID）、go/guild/internal/data/guild_repo.go（CreateGuild / GuildNameNorm）、go/guild/internal/svc/guild_id_minter.go
- client messages: 15 CreateGuild（C2S）
- tables: GuildLevel（第 1 级的 max_members，默认 30）
- depends on: guild-service-access、guild-storage-tx、guild-zone-isolation、guild-rank、guild-query（回包用 GuildInfo）
- behavior: 帮名先 TrimSpace，再要求 1–24 个 rune、不含控制字符、NFKC 规范化后可用，否则回 14009。判重按 name_norm（NFKC → 去首尾空白 → 小写，≤48 rune），重名回 14010（全服唯一）。已在帮中回 14000（先看缓存，缓存说在帮就用 MySQL 复核）。合服回 14013，发号失败回 14008，写冲突回 14021。成功时新帮为 Lv1、资金 0、max_members = GuildLevel[1]，建帮者为帮主（role 3），回包带 GuildInfo；不推送。新帮以 0 分进全服榜和区榜。
- internal: guild_id 来自号段（data_service biz_tag=guild），按配置可回退雪花；发号排在合服闸门与读表之后，避免白烧号。guild 行与帮主成员行同一事务写入，事务先锁全局插入守卫 S(0)、再锁 S(player)。
- java: missing — xm-common 有雪花 ID 可以发 guild_id；xm-player-store 已有同一套名字规范化写法（角色名 name_key，见 PARITY「角色名唯一」行），可以复用。
- size: M
- robot: guild_smoke 第 1、2、4 步，economy 段第 1 步
- hazards: 旧实现曾吞掉成员行写入失败，产出「帮会存在但没有任何成员」的帮；必须一个事务里写完。撞唯一键时要分清是 uk_guild（重名）还是 uk_guild_member（已在帮）。

### guild-query — 查帮会信息
- mmorpg: go/guild/internal/logic/guild_logic.go（GetGuild / GetPlayerGuild / toProtoGuild）、guild_manage_logic.go（guildInfoFor / levelDisplay）
- client messages: 60 GetGuild（C2S）、35 GetPlayerGuild（C2S）
- tables: GuildLevel（max_officers、upgrade_cost_funds）
- depends on: guild-cache-mapping、guild-presence-names、guild-zone-isolation
- behavior: GuildInfo 包含 guild_id、name、leader_id / leader_name、level、announcement、create_time_ms、max_members、zone_id、funds、max_officers、officer_count（role=1 的人数）、upgrade_cost_funds（0 = 满级）、members（按 player_id 升序，每人带 role、join_time_ms、last_active_ms、online、name、contribution_total 和 contribution_balance）。pending_application_count 只对本帮长老和帮主非 0，其他人一律 0。GetGuild 查别区或不存在的帮回 14001；GetPlayerGuild 未入帮回 14002。GuildLevel 缺行时 max_officers / upgrade_cost_funds 留 0 并记 ERROR，不让整次读失败。
- internal: 客户端走 ResolvePlayerGuild，必要时以 MySQL 复核；内部调用走缓存路径。待审数每次按未过期申请现数，不进缓存。
- java: missing
- size: M
- robot: guild_smoke 第 0、6、7 步；客户端 GuildClient.RefreshGuild
- hazards: role 编码不连续（0 / 1 / 3，2 是空号），比较权限一律先映射成 Rank，不能直接比数值。成员数上限 100 是快照包体与推送的预算前提。

### guild-leave — 退帮
- mmorpg: go/guild/internal/logic/guild_logic.go（LeaveGuild）、go/guild/internal/data/guild_manage_repo.go（LeaveGuild）、economy_repo.go（accelerateDonationDeadlines）
- client messages: 29 LeaveGuild（C2S，回包只有 error_message）；推送 220 kind=MEMBER_LEFT
- tables: none
- depends on: guild-storage-tx、guild-change-push、guild-asset-outbox（提前截止）
- behavior: 帮主不能退帮，回 14004（锁内按 role 与 leader_id 任一判定）。不在帮中视为幂等成功；缓存所指的帮不对、本人实际在另一个帮时回 14000，提示刷新。合服回 14013。成员行和帮贡一起删除（帮贡清零）；同时删掉本人在所有帮会的待审申请；本人在本帮还没结算的捐献，截止时间提前到当下，尽快中止并退回次数。推送 MEMBER_LEFT（actor = target = 退帮者），收件人是剩余全体成员。
- internal: 锁序 guild → state(p) → member → application（按主键升序）→ asset_op。
- java: missing
- size: S
- robot: guild_smoke 第 8 步、M9
- hazards: 已扣款的捐献照样记给原帮会，不退款（用户决策 D2）。活动个人次数挂在玩家身上，换帮后当天不重置。

### guild-disband — 解散帮会
- mmorpg: go/guild/internal/logic/guild_logic.go（DisbandGuild）、go/guild/internal/data/guild_manage_repo.go（DisbandGuild，约 2263 行起）、guild_repo.go（RemoveGuildFromRank）、activity_repo.go（删进度）
- client messages: 38 DisbandGuild（C2S）；推送 220 kind=DISBANDED
- tables: none
- depends on: guild-storage-tx、guild-rank、guild-change-push、guild-asset-outbox、guild-activity-core
- behavior: 只有帮主能解散，否则回 14005（是唯一一处把职位不足映射成「只有会长可以执行」的地方）。帮不存在回 14001，合服回 14013（事务内按 guild.zone_id 再判一次）。删除的内容：本帮全部申请、全体成员在别帮的申请、全体成员行、活动进度、guild 行；全体成员在本帮还没结算的捐献，截止时间提前到当下。帮会从区榜和全服榜删除。推送 DISBANDED（target = 0），收件人是除帮主外的全体成员；只提交过申请的玩家不通知。
- internal: 最重的事务，约 300–400 条主键点语句（满员 100 人），子预算 2.5s。事务外先给全体成员建状态行，事务内锁住 guild 行后重新读一遍成员全集。清榜只用事务内读到的 zone_id。
- java: missing
- size: M
- robot: guild_smoke 第 0 步（清场）、第 10 步（解散后榜上消失，M10 留下的申请被连带删除）
- hazards: 被解散的帮还有 PENDING 的商店或活动指令时，到账照常发给玩家；捐献后来 APPLIED 时帮会已不存在，帮会资金和帮贡记成 orphan（只计指标）。

### guild-announcement — 帮会公告
- mmorpg: go/guild/internal/logic/guild_logic.go（SetAnnouncement）、guild_manage_repo.go（UpdateAnnouncement）、guild_repo.go（canSetAnnouncement）
- client messages: 39 SetAnnouncement（C2S）；推送 220 kind=ANNOUNCEMENT_CHANGED
- tables: none
- depends on: guild-storage-tx、guild-change-push
- behavior: 超过 600 字节（UTF-8）回 14011。之所以是 600 字节：gate 单包上限 1KB，700 字节的公告仍能通过 gate、由 guild 拒绝。只有长老和帮主能改，否则回 14006（不在帮也回 14006）。guild_id 取请求体（事务会核对操作者确实是该帮的长老或帮主）。写入相同文本也算成功。回包是事务内的权威快照。推送给除操作者外的全体成员。合服回 14013。
- internal: 锁 guild 行后锁操作者成员行，判权限，再 UPDATE。
- java: missing
- size: S
- robot: guild_smoke 第 5 步（未入帮 → 14006）、第 7 步（700 字节 → 14011，B 读到新公告）
- hazards: 长度按字节算，不按字数（约 200 个汉字）。

### guild-roles — 任免长老与转让帮主
- mmorpg: go/guild/internal/logic/guild_manage_logic.go（SetGuildMemberRole / TransferGuildLeader）、go/guild/internal/data/guild_manage_repo.go（SetMemberRole / TransferLeader / canAssignRole / demotedLeaderRole）、go/guild/internal/constants/constants.go（Rank）
- client messages: 19 SetGuildMemberRole（C2S）、216 TransferGuildLeader（C2S）；推送 220 kind=ROLE_CHANGED / LEADER_TRANSFERRED
- tables: GuildLevel.max_officers
- depends on: guild-storage-tx、guild-change-push、guild-query
- behavior: 只有帮主能任免和转让，否则回 14016。role 只能设 0 或 1，设别的值回 14006。目标是自己回 14015；目标为 0 或不是本帮成员回 14014。长老数已满（GuildLevel[level].max_officers）回 14017；配表下调后已经超额的长老不强制降级，只拒绝新的任命。重复设成同一角色视为幂等成功：不写库、不推送。转让后原帮主变成长老（如果还有长老名额）或成员；guild.leader_id 必须与唯一一行 role=3 一致，否则回滚（Internal）。成功回包带提交前的事务内快照；推送给除操作者外的全体成员。
- internal: 操作者与目标两行按 player_id 升序点锁。GuildLevel 缺行时 fail-closed。
- java: missing
- size: M
- robot: guild_smoke M5（任命两次，第二次幂等；max_officers=2）、M6（长老任免帮主 → 14016）、M7 / M8（来回转让）
- hazards: 判权限一律先映射 Rank（Go 的 constants.Rank），未知编码映射为 RankNone、拒绝一切操作。转让时「宁可降成成员也不超编」。

### guild-kick — 踢出成员
- mmorpg: go/guild/internal/logic/guild_manage_logic.go（KickGuildMember）、go/guild/internal/data/guild_manage_repo.go（KickMember / precheckKickTarget / canKick）
- client messages: 217 KickGuildMember（C2S）；推送 220 kind=MEMBER_KICKED
- tables: none
- depends on: guild-storage-tx、guild-change-push、guild-asset-outbox（提前截止）
- behavior: 操作者至少是长老，并且职位严格高于目标：帮主可以踢长老和成员，长老只能踢成员，没人能踢帮主；不满足回 14016。踢自己回 14015；目标不在本帮回 14014（幂等，客户端据此刷新）。被踢者的帮贡随成员行一起清掉，他的全部待审申请删除，他在本帮未结算的捐献提前截止。推送收件人是剩余成员（不含操作者）加上被踢者本人。回包带新快照。
- internal: 事务外先普通读，确认目标是不是本帮成员，不是就直接答复，免得给随机 id 建垃圾状态行。锁序 guild → state(target) → member（两行按 id 升序）→ application → asset_op。
- java: missing
- size: S
- robot: guild_smoke M6（长老踢成员成功；踢帮主 → 14016；踢自己 → 14015；再踢已走的人 → 14014）
- hazards: 客户端在被踢者自己收到 MEMBER_KICKED 推送时显示「你已被请离帮会。」（GuildClient.cs:209）。

### guild-applications — 入帮申请（申请人侧）
- mmorpg: go/guild/internal/logic/guild_manage_logic.go（ApplyJoinGuild / CancelGuildApplication / ListMyGuildApplications / myApplicationViews）、go/guild/internal/data/guild_manage_repo.go（ApplyToGuild / CancelApplication / ListMyApplications / purgeExpired*）、guild_manage_repo.go（TryMarkApplyPush）
- client messages: 218 ApplyJoinGuild、219 CancelGuildApplication、222 ListMyGuildApplications（C2S）；推送 220 kind=APPLICATION_RECEIVED
- tables: GuildRule（application_expire_hours=72、max_pending_applications_per_player=3、max_pending_applications_per_guild=50）
- depends on: guild-storage-tx、guild-zone-isolation、guild-change-push、guild-presence-names
- behavior: 已在帮中回 14000；guild_id 为 0 或不存在、别区回 14001；帮满回 14003（只拒新申请人）；本人待审已达上限回 14019；目标帮的待审队列已满回 14020。重复申请同一个帮 = 刷新有效期并成功，不新增行、不推送。申请的 expire_ms = apply_ms + 72h；过期行在写事务里顺手删掉，不占上限。撤回：guild_id 为 0 或申请不存在回 14018；撤回成功没有推送。ListMyGuildApplications：已在帮时回空列表；最多 10 条，按 apply_ms 降序、guild_id 升序；别区或已解散的帮被过滤掉；每条带帮名、等级、人数 / 上限、帮主 id 与名字、apply_ms、expire_ms。新申请推 APPLICATION_RECEIVED 给该帮长老和帮主；同一（帮, 申请人）60 秒内至多推一次（Redis SetNX 冷却，Redis 出错时不推）。
- internal: 并发申请靠 guild_player_state 行锁串行化，所以每人上限是硬上限；插入写成 IODKU。提交之后再按逐行短事务尽力清理本帮的过期申请。
- java: missing
- size: M
- robot: guild_smoke 第 6 步、M1（F 重复申请后仍只有 1 条）、M10、第 0 步（撤回遗留申请）
- hazards: 申请行只存待审的；通过、拒绝、撤回、过期、解散都直接删行。推送冷却挡的是「申请 → 撤回 → 申请」刷屏审批人。

### guild-application-review — 入帮审批（帮会侧）
- mmorpg: go/guild/internal/logic/guild_manage_logic.go（ListGuildApplications / ReviewGuildApplication / applicantViews）、go/guild/internal/data/guild_manage_repo.go（ReviewApplication / ListApplicants / CountLiveApplications / precheckApprovedApplication）
- client messages: 221 ListGuildApplications、223 ReviewGuildApplication（C2S）；推送 220 kind=MEMBER_JOINED / APPLICATION_REJECTED
- tables: GuildRule.max_pending_applications_per_guild（列表条数上限）
- depends on: guild-applications、guild-zone-isolation、guild-change-push、guild-presence-names
- behavior: 列表只给长老和帮主看，否则回 14016；未入帮回 14002。列表按 apply_ms 升序、player_id 升序，每条带 name、online、apply_ms、expire_ms。审批：申请人为 0 回 14018；审批自己回 14015；申请不存在、已过期、申请人已入别的帮、申请人归属区与帮会不一致，一律删掉这条申请并回 14018（不细分，免得泄露申请人的状态）；查不到申请人归属区也回 14018。通过时帮已满回 14003，申请保留、事务回滚。通过：插入成员行（role 0，join_time = 现在），并删除申请人在其他帮的全部申请。拒绝：只删这一条。推送：通过 → MEMBER_JOINED 给除审批人外的全体成员（含新成员）；拒绝 → APPLICATION_REJECTED 只给申请人。回包带快照。
- internal: 通过时，申请人归属区的查询与开事务并行进行。锁序 guild → S(0) → S(applicant) → application → member（登记在案的例外）。
- java: missing
- size: M
- robot: guild_smoke 第 6 步、M2（未入帮的人列名单 → 14002）、M3、M4（拒绝后再审同一人 → 14018）
- hazards: 申请人刚入了别的帮这一竞态，由插入时撞 uk_guild_member 的 1062 分支兜住，这一分支要转成 14018。

### guild-change-push — 帮会变更推送（GuildChangedS2C）
- mmorpg: go/guild/internal/logic/push.go、go/guild/internal/kafka/gate_command_builder.go、go/shared/kafkautil、economy_logic.go（OnAssetFinalized）
- client messages: 220 NotifyGuildChanged（S2C push，body GuildChangedS2C{kind, guild_id, actor_player_id, target_player_id}）
- tables: none
- depends on: gate 下行推送通道；玩家 → gate 会话目录（guild-presence-names）
- behavior: 推送至多一次，客户端收到后只触发重新拉取；只推在线玩家，离线的计数后丢弃。13 种 kind 与收件人：MEMBER_JOINED（除审批人外全体）、MEMBER_LEFT（剩余全体）、MEMBER_KICKED（剩余成员 + 被踢者）、ROLE_CHANGED / LEADER_TRANSFERRED / ANNOUNCEMENT_CHANGED / LEVEL_UP / ACTIVITY_CHANGED（除操作者外全体）、DISBANDED（除帮主外全体）、APPLICATION_RECEIVED（长老和帮主，60s 冷却）、APPLICATION_REJECTED（申请人）、FUNDS_CHANGED（捐献在后台终结时只推本人，含拒绝 / 中止）、DELIVERY_DONE（商店或活动发奖在后台终结时只推本人）。同步投递里当场终结的不推。建帮不推。客户端处理（GuildClient.cs:186-215）：在帮时任何 kind 都重拉 GetPlayerGuild；Disbanded 弹「帮会已被帮主解散」；未入帮时收到 MemberJoined 或 ApplicationRejected 才刷新。
- internal: 提交之后异步发送，3s 预算，失败只计指标、绝不影响 RPC 结果。mmorpg 用 MGET 读 player:session 拿到 gate 实例，再往 Kafka gate-cmd topic 发 PushToPlayer / BroadcastToPlayers。Java 需要一条从 guild 到 gate 的下行通道（例如 Dubbo 调 gate，或 Redis pub/sub 按 gate 节点分发）以及玩家 → gate 的定位。
- java: missing — Java 的下行推送只有 scene 经节点链路发的 ToClient（xm-api node_link.proto），没有非 scene 服务推给客户端的通道，也没有 Kafka（architecture.md §10）。
- size: M
- robot: guild_smoke M3（MEMBER_JOINED）、M4（APPLICATION_REJECTED）、M5（ROLE_CHANGED）、M6（MEMBER_KICKED）、M7（LEADER_TRANSFERRED）、M9（MEMBER_LEFT）；robot 等推送的预算是 5s，超时判失败
- hazards: 推送的收件人取自事务内的快照，没有成员的帮会就不推。客户端必须能处理 kind 数值未知的情况（proto3 枚举）。100 人帮是一次广播的预算上限。

### guild-rank — 帮会排行
- mmorpg: go/guild/internal/logic/guild_logic.go（UpdateGuildScore / GetGuildRank / GetGuildRankByGuild / enrichRankEntries）、go/guild/internal/data/guild_repo.go（UpdateGuildScore / RebuildRanks / RemoveGuildFromRank / GetGuildRankPage / acquireRankLock）
- client messages: 27 GetGuildRank（C2S）、52 GetGuildRankByGuild（C2S）、8 UpdateGuildScore（内部；客户端调用回 1003 信封）
- tables: none
- depends on: guild-storage-tx、guild-zone-isolation、guild-presence-names
- behavior: 客户端强制看本区榜（zone_id 用归属区覆盖，全服榜 zone_id=0 只开放给内部调用）。page 默认 1；page_size 默认 20，客户端最多 50。条目按分数降序，rank 从 1 开始，带 name、leader_id、leader_name、level、member_count，回包带 total_count。GetGuildRankByGuild 不在榜（包括别区的帮）回 14007。UpdateGuildScore：帮不存在回 14001，写冲突回 14021；分区一律用帮会自己的 zone，忽略请求里的 zone_id。
- internal: 权威分数存在 guild.score。Redis ZSET `guild_rank` 与 `guild_rank:zone:{z}` 只是读加速：先写 MySQL 再写 ZSET。每次启动从 MySQL 全量重建（先写临时键，再用 MULTI 原子 RENAME 替换）。所有维护操作在一把 SetNX 维护锁下串行（TTL 5 分钟，等锁 5 秒）。
- java: missing
- size: M
- robot: guild_smoke 第 3 步（同区查到名次）、第 4 步、第 9 步、第 10 步（解散后从榜上消失）；客户端「寻找帮会」页（GuildWindow.cs:250）用 27 列出帮会
- hazards: mmorpg 里没有任何服务端调用 UpdateGuildScore，所以全部分数都是 0，同分顺序由 ZSET 的 member 字典序决定（ZREVRANGE 对同分按 member 降序）。Java 若想与 mmorpg 一致，同分要按 guild_id 的**字符串**降序排列。GetGuildRankPage 逐条 GetGuild 补齐信息，有 N+1 读。

### guild-asset-outbox — 帮会资产指令账本与终结记账
- mmorpg: proto/guild/guild_db.proto（GuildPlayerOpSeqRecord / GuildAssetOpRecord / GuildDailyCounterRecord）、go/guild/internal/data/asset_store.go（Finalize / terminate / applyCounterparty / refundCounter）、economy_repo.go（ReserveDonation / ReserveShopOrder / accelerateDonationDeadlines / upsertCounterWithLimit）、go/shared/assetop/seq.go
- client messages: none（体现在 53 / 233 / 239 / 240 视图的 status 与 reason_tip_id，以及 220 FUNDS_CHANGED / DELIVERY_DONE）
- tables: GuildRule.asset_op_deadline_seconds=600
- depends on: guild-storage-tx、guild-asset-delivery、op_id 发号
- behavior: 每个（玩家, 流）的 seq 单调递增，带 stream_epoch（建行时刻）。本人每条流最多 16 条未决指令，超出回 14026 且不写任何行。指令状态：PENDING → APPLIED / REJECTED / ABORTED / APPLIED_PARTIAL（数值与客户端的 GuildAssetOrderStatus 逐项相同）。对侧账在终结事务里一起完成：捐献 APPLIED → 帮会资金 += funds_delta、帮贡 total 与 balance 都 += contribution_delta；捐献 REJECTED / ABORTED → 退回今日次数；商店 REJECTED / ABORTED → 退回帮贡与限购次数；活动发奖只做状态 CAS；APPLIED_PARTIAL 只终结、转人工补偿。帮会或成员已不存在时计 orphan，不记账。今日次数用「带上限的 upsert」占用。
- internal: op_id 来自号段（biz_tag guild_asset_op），**不允许回退雪花**。账本行 28 列（含租约令牌、last_reason、payload 是 AssetBundle 的序列化字节）。终结 = 读不可变列 → 锁对侧（guild / member / seq）→ 点锁 op 行 → `status=PENDING` 的 CAS → 记对侧账 → 退计数；提交后失效缓存并回调 OnFinalized 推送。
- java: missing — Java 没有货币、背包或资产通道的任何代码（在 xm-scene 和 xm-player-store 里 grep 不到 asset / currency）。
- size: L
- robot: guild_economy_smoke full 模式第 5–13 步（资金、帮贡、次数精确到数值）
- hazards: 捐献在 scene 实际扣款成功之后才入账，扣款前取消不需要退款。商店先扣帮贡、后发货。退出、被踢、解散时把捐献截止时间提前，好让还没扣款的指令尽快中止。

### guild-asset-delivery — 资产指令投递与重投循环（guild → scene）
- mmorpg: go/guild/internal/svc/asset_op.go、go/shared/assetop/{caller,reconcile,decide,auth,classify,ledger_dataservice}.go、economy_logic.go（deliverNow / syncBudgetFor）、go/guild/internal/data/asset_store.go（ListDue / Claim / Reschedule / markPoison）、proto/common/asset/asset_op.proto、proto/scene_manager/scene_node_service.proto（AssetDebit / AssetCredit / AssetAbortDebit）
- client messages: none（决定 53 / 233 / 239 / 240 的回包是当场 APPLIED 还是 PENDING）
- tables: GuildRule.asset_op_retry_base_ms=1000
- depends on: guild-asset-outbox；**scene 侧的资产账本**（C++ cpp/libs/services/scene/player/system/asset_op_{system,ledger,auth}.cpp：按 seq 窗口幂等、验签、改货币或背包、与玩家数据一起落盘）——属于 scene 区域，Java 也没有
- behavior: 提交后同步投递一次，预算 = min(2500ms, 请求剩余 − 1000ms)，剩余不足 300ms 就不投、交给后台循环。玩家离线（没有位置）时本地合成 NOT_HERE，按退避重投（基数 1s，封顶 60s）。scene 回 RETRY（战斗中、背包满、冻结）同样重排，并把原因写进 last_reason，视图里的 my_pending_reason_tip_id 展示它。捐献超过截止（600s）就改发 AssetAbortDebit 并终结为 ABORTED（退回次数）。商店与活动奖励没有截止，背包满就一直挂着，腾出空间后自动到账。UNKNOWN 结局只告警、不终结（需要人工处理，见 guild-asset-ops-tools）。离线超过 3 次的行，可以从 data_service 读已落盘的账本提前终结。
- internal: 后台循环（间隔 2s，每批 100 行，8 个 worker）按 next_attempt_ms 取到期行，用租约领取（10s 加令牌），失败退避，毒行延后 1 小时。按 player:{id}:location 和 etcd 里的 scene 节点镜像定位玩家所在的 scene，用 HMAC 签名（密钥来自环境变量 MMORPG_ASSET_OP_SECRET_GUILD，至少 32 字节；时钟偏差容忍 300s；canonical 串覆盖货币与物品）。scene 按（stream, caller）白名单校验。通道开关 AssetOp.Enabled=false 时：捐献、兑换、带物品的活动都回 14026，升级和读接口照常可用。插行租约与截止时间之间有交叉校验，配错拒绝启动。
- java: missing — Java scene 没有货币、背包或资产账本；Java 版也没有 player:{id}:location，只有 owner_epoch 归属。需要先由 scene 区域做出资产指令入口（Java 可以走节点链路或 Dubbo），guild 才能接上。
- size: XL（guild 侧约 L；scene 侧账本不计入本区）
- robot: guild_economy_smoke（前置要求 AssetOp.Enabled=true，且 scene 能收资产指令）；可选手动项：强杀 guild 后由重投循环完成捐献、资金只加一次
- hazards: 正确性靠 scene 的 seq 账本与玩家数据**同一次落盘**；Java 的写回又带 owner_epoch 围栏，账本必须和玩家数据走同一个围栏，否则顶号时会重复扣款或重复发放。同步投递在请求线程里阻塞，Java 不能放在 Netty I/O 线程上做。

### guild-asset-ops-tools — 资产指令的运维面：清理、人工终结、回档前检查
- mmorpg: go/guild/guild.go（startAssetOpCleanup）、go/guild/internal/data/asset_store.go（RunCleanup / CleanupOnce / ResolveManually）、go/guild/cmd/assetopfix/main.go、go/guild/internal/server/guild_internal_server.go、go/guild/internal/data/asset_op_divergence_repo.go、proto/guild/guild_internal.proto
- client messages: none（GuildInternal.ListAppliedAssetOpsSince 只供内部调用，带会话调用回 PermissionDenied）
- tables: none
- depends on: guild-asset-outbox；mmorpg data_service 的回档闸
- behavior: 运维可见。终态指令保留 30 天，计数行保留 30 天，每 10 分钟清理一次（多副本随机错开起点）。assetopfix CLI 只能把卡死的 PENDING 行人工终结为 applied 或 aborted，必须带 `-txlog-checked`，并写 resolved_by 与 resolve_reason；行已经不是 PENDING 时退出码为 3。ListAppliedAssetOpsSince：参数 player_ids 1..100 个、since_ms > 0、按 op_id 游标翻页、每页 limit ≤500；since_ms 早于保留期时回 FailedPrecondition，message 前缀「since_ms older than terminal retention; cutoff_ms=」是跨服务契约。
- internal: 清理按主键点删、分批进行；人工终结与自动终结共用同一个对侧记账函数；只读查询不开事务。
- java: missing — Java 没有回档功能，内部 RPC 的消费方暂不存在；清理与人工终结随 guild-asset-outbox 一起做。
- size: M
- robot: none
- hazards: 回档检查依赖「终态行的 next_attempt_ms = 终结时刻」这个不变量，Java 要保持这一写法。

### game-day-periods — 游戏日 / 游戏周切点
- mmorpg: go/shared/gameday/gameday.go
- client messages: 120 / 228 / 241 视图里的 next_daily_reset_ms、next_weekly_reset_ms、next_reset_ms、period_key
- tables: GuildShop.limit_period（0 不限 / 1 每日 / 2 每周）
- depends on: none
- behavior: 固定 UTC+8 时区，每天 05:00 切日，周一 05:00 切周（ISO 周）。DayKey 为 YYYYMMDD（8 位），WeekKey 为 ISO 周年 + 周号（6 位，例如 2027-01-01 属于 202653），两个数值域不相交、可以混放在一个 IN 里。NextDailyReset / NextWeeklyReset 严格晚于当前时刻（恰好在切点上时返回下一个切点）。
- internal: 一次请求只取一次 now，周期键、截止时刻、视图时间戳都由它推出。不依赖 tzdata、没有夏令时。
- java: missing — Java 仓库里没有 gameday 或 DayKey（grep 无结果）。用 java.time 加固定 ZoneOffset.ofHours(8) 即可实现。
- size: S
- robot: guild_economy_smoke（按今日用量判定 full / degraded 模式）
- hazards: WeekKey 必须用 ISO 周所属的年份，不能用自然年，否则键不再单调递增，清理任务会误删。

### guild-donate — 帮会捐献
- mmorpg: go/guild/internal/logic/economy_logic.go（GetGuildDonateOptions / DonateToGuild / economyCaller / donationRejectTip / settledView）、go/guild/internal/data/economy_repo.go（ReserveDonation / DonateUsage / PendingOps / RecentOps / MemberContribution）
- client messages: 120 GetGuildDonateOptions（C2S）、53 DonateToGuild（C2S）；推送 220 FUNDS_CHANGED（后台终结时）
- tables: GuildDonate（1 银两小捐：花 10000、得帮贡 10、资金 1000、每日 5 次；2 银两大捐：花 100000、得 120 与 12000、每日 2 次；3 灵石捐献：花 100、得 200 与 20000、每日 1 次）、GuildRule.asset_op_deadline_seconds
- depends on: guild-asset-outbox、guild-asset-delivery、game-day-periods、guild-query；scene 侧货币扣除（currency_type 0 = kCurrencyGold，1 = kCurrencyDiamond）
- behavior: 选项页：所有配表行按 donate_id 升序，每行带 used_today（含结算中的占用）、unlocked（帮会等级 ≥ min_guild_level）；另有 contribution_total / balance（从 MySQL 直读）、next_daily_reset_ms、pending_donations（本人在本帮的 PENDING，≤16）、recent_results（10 分钟内终结的，≤5 条，新的在前）。捐献：donate_id 不存在回 14027；帮会等级不足回 14029；今日次数用完回 14024；未决过多或通道关闭回 14026（不写行、回包不带视图）；合服回 14013；未入帮回 14002。scene 永久拒绝时：货币不足回 14025，其他原因回 14027，视图状态为 REJECTED、次数退回。PENDING 和 APPLIED **不进** error_message，只体现在 donation.status 上。只要写了指令行，donation 视图就必填；请求者还在帮里时 guild 必填（重新读取，APPLIED 时已能看到新资金）。
- internal: 预留事务（锁成员行 → 按需建 seq 行 → 分配 seq → 写 PENDING 行 → 占用今日次数）→ 同步投递 → 回读状态。走 GUILD_DEBIT 流，TxType TX_GUILD_DONATE，截止 = 现在 + 600s。
- java: missing
- size: L
- robot: guild_economy_smoke full 第 5–8 步（大捐两次后第三次回 14024；灵石捐献；B 灵石不足回 14025、视图 REJECTED、用量仍为 0）、degraded 第 4' 步
- hazards: 客户端遇到任何非 0 tip 都会中断后续刷新，所以「结算中」绝不能塞进 error_message。合服闸门在事务里判。帮会资金与帮贡只在 scene 确认扣款之后才增加。

### guild-upgrade — 帮会升级
- mmorpg: go/guild/internal/logic/economy_logic.go（UpgradeGuild / upgradeLevelLookup）、go/guild/internal/data/economy_repo.go（UpgradeGuild）
- client messages: 76 UpgradeGuild（C2S）；推送 220 LEVEL_UP
- tables: GuildLevel（upgrade_cost_funds：1→2 级 20000、2→3 级 50000 … 10 级满级；max_members 从 30 到 100）
- depends on: guild-storage-tx、guild-query、guild-change-push
- behavior: 长老或帮主才能升级，否则回 14016（回包仍带 guild）。expected_level 非 0 且与库里不同，说明已被别人升过：回成功和最新 GuildInfo，不扣钱（重复点击不会连升两级）。已满级回 14023；资金不足回 14022（回包带 guild，客户端借此刷新资金显示）。扣的是**当前等级行**的 upgrade_cost_funds，新的 max_members 取下一级的行。不经资产通道，通道关闭时照常可用。推送 LEVEL_UP 给除操作者外的全体。合服回 14013。
- internal: 锁 guild 行 → 锁本人成员行 → 判职位 → 合服闸门 → 比对 expected → 带条件的 UPDATE（level 与资金一起作为条件）。expected 不符的分支也要失效缓存。
- java: missing
- size: S
- robot: guild_economy_smoke full 第 9 步（B 升级回 14016；A 升到 Lv.2，资金 24000，上限 35；A 带旧等级再升一次 → 受理、仍是 Lv.2）、degraded 第 6' 步（资金不足回 14022）
- hazards: 资金只能靠捐献和活动增加；没有降级，也没有资金消耗以外的其他用途。

### guild-shop — 帮会商店
- mmorpg: go/guild/internal/logic/economy_logic.go（GetGuildShop / BuyGuildShopGoods / contributionPrecheck / refundedBalance / shopUsedCount）、economy_config.go（MaxBuyCount）、go/guild/internal/data/economy_repo.go（ReserveShopOrder / ShopUsage）
- client messages: 228 GetGuildShop（C2S）、233 BuyGuildShopGoods（C2S）；推送 220 DELIVERY_DONE（后台终结时）
- tables: GuildShop（11 件商品，分 3 个分类）、Item.max_stack_size
- depends on: guild-asset-outbox、guild-asset-delivery、game-day-periods；scene 侧发物品进背包
- behavior: 商店页：商品按 category、goods_id 升序，每件带 unlocked、used_count（本周期已兑份数，含待发放）、max_buy_count（堆叠上限为 1 时恒为 1，否则 min(20, max_stack / item_count)）、limit_period / limit_count；另有可用帮贡 balance、pending_orders（≤16）、recent_orders（10 分钟内，≤5）、next_daily_reset_ms、next_weekly_reset_ms。兑换：count 为 0 按 1 份处理。goods 不存在回 14028；份数超过 max_buy_count 或本周期限购已满回 14030；帮会等级不足回 14029；可用帮贡不足回 14031（预判时快照说不够，会先直读 MySQL 复核）。未决过多或通道关闭回 14026。帮贡在预留事务里**先扣**；scene 永久拒绝时回 14027，并在回包里给出退回后的余额。兑换没有截止，背包满就一直挂着等待。回包带 order 视图与提交后的 contribution_balance。
- internal: 走 GUILD_CREDIT 流，TxType TX_GUILD_SHOP，限购计数按 period_key（日键或周键），不限购的商品 period_key = 0、不占计数行。
- java: missing
- size: L
- robot: guild_economy_smoke full 第 10–13 步（103 已解锁、104 未解锁；兑换 101 后帮贡 410、背包 +5；104 → 14029；B → 14031；202 两份 → 14030；301 连兑 5 次后第 6 次 → 14030）、degraded 第 7' 步
- hazards: 离帮、被踢后，已扣的帮贡随成员行一起消失；之后若被拒，退款无处可退（计 orphan）。单价 1e9 × 20 份不会溢出 uint64，这是配表校验的前提。

### guild-activity-core — 帮会活动公共底座（前置、预检、进度、计数、奖励）
- mmorpg: go/guild/internal/activity/{rules,config}.go、go/guild/internal/logic/activity_logic.go（activityPrelude / precheckActivity / prepareActivityReward / activityTxTip / afterActivityCommit）、go/guild/internal/data/activity_repo.go（participate / deleteGuildActivityProgress / ActivityUsage / GuildProgress / RewardStatuses）
- client messages: 239–243 共用（C2S）；推送 220 ACTIVITY_CHANGED、DELIVERY_DONE
- tables: GuildActivity、GuildRule（activity_join_min_hours、reunion_min_online_members）、Reward（奖励包）
- depends on: guild-asset-outbox、guild-asset-delivery（只有带物品的活动需要）、game-day-periods、guild-zone-isolation、guild-config-validation
- behavior: 身份只认会话，无会话回 PermissionDenied。只有读接口（241）查归属区，帮会不在本区时回 14002；未入帮回 14002。写 RPC 只接受「该类型当前选中、并且处于 Open」的那一行，客户端伪造同类型其他行的 id 一律回 kGuildActivityNotOpen。阻塞项按固定优先级判定：未开放 > 帮会等级不足 [min_level] > 入帮未满 N 小时 [N] > 今日次数已满 > 团圆人数不足 [在线人数, 阈值]。每次成功参与：同一事务里给本人加 personal_contribution 帮贡（total 与 balance）；占用一次今日次数（guild_daily_counter kind=ACTIVITY，按游戏日计，换帮不重置）；更新帮会进度行（guild_activity_progress，键 = 帮会 × 活动 × 档期键）；本档期首次达到阈值时锁存，帮会资金在一个档期内只发一次（funds_granted 标记）；有物品时插入一条 ACTIVITY_REWARD 指令（GUILD_CREDIT 流，永不中止）。带物品的活动在通道关闭时回 14026，什么都不写。首次达阈值时推 ACTIVITY_CHANGED 给除操作者外的全体。写冲突回 14021。解散时逐行删除进度。
- internal: 第一把锁是 G 行；之后依次是 M → Q（即使没有物品也要锁 seq 行）→ O → C → P。帮会档期键：灯会和团圆取档期 start_at_ms 那一天的游戏日；常开行（start 与 end 都为 0）取当天游戏日；历练取开战当天的游戏日。
- java: missing — 只有 GuildActivityRows 表访问（ConfigTablesTest.java:43-46），而且没有数据。
- size: L
- robot: none（mmorpg 也没有活动的 robot 场景）
- hazards: **活动相关的 tip 码（kGuildActivityNotOpen / AlreadyClaimed / ThresholdNotReached / LevelTooLow / JoinTooRecent，以及 5 个 kGuildTrial*）在 26ceb70ca 的 tip_text.json 里不存在**（帮会段只到 14031）；go/shared/generated 里也没有 GuildActivity 表和 GuildRule 第 8–10 列，所以 B6a 的 Go 代码在这个基线上依赖尚未重新生成的产物。Java 必须等 mmorpg 导出码值之后才能字节兼容。

### guild-activity-view — 帮会活动列表
- mmorpg: go/guild/internal/logic/activity_logic.go（GetGuildActivities / activityViews / reunionOnlineForView / buildActivityView）、go/guild/internal/activity/rules.go（SelectVisible / StateOf / BuildView / RewardItems）
- client messages: 241 GetGuildActivities（C2S）
- tables: GuildActivity、GuildRule、Reward
- depends on: guild-activity-core、guild-presence-names（团圆在线人数）
- behavior: 每种类型至多一条，按 type 升序。代表行的选法：Open 中 id 最小的；没有就选 Upcoming 中 start 最早的；再没有就选 id 最小的。状态：1 DISABLED（enabled=false）、2 UPCOMING、3 OPEN、4 ENDED，档期为左闭右开 [start, end)。视图 30 个字段：配表展示数据、period_key 与 guild_period_key、next_reset_ms、server_time_ms（客户端倒计时以它为准）、my_used_count、progress（灯会是点灯人次；团圆在锁存前是当前合格在线人数，锁存后为 0；历练是今日计资金的胜场）、threshold_reached、funds_granted、blocked_tip_id、my_pending_reward_count / my_pending_reason_tip_id / my_last_reward_reject_tip_id（24 小时内），奖励物品按 item_id 合并后升序。B6a 期间历练行强制 DISABLED，blocked 填 kGuildActivityNotOpen。某一行视图构建失败时只隐藏该行。
- internal: 读取今日用量、帮会进度、本人待发状态各一次。团圆在线人数用严格版并带独立超时，失败时显示 0。
- java: missing
- size: M
- robot: none；客户端也没有调用 241
- hazards: 配表为空（当前现状）时回空列表、不报错。

### guild-lantern — 元宵灯会点灯
- mmorpg: go/guild/internal/logic/activity_logic.go（LightGuildLantern）、go/guild/internal/data/activity_repo.go（LightLanternTx）、go/guild/internal/activity/rules.go（LanternOutcome）
- client messages: 239 LightGuildLantern（C2S，请求带 activity_id；回包是本活动提交后的视图）
- tables: GuildActivity（type=1；guild_threshold = 本档期点灯人次阈值，≥1）
- depends on: guild-activity-core
- behavior: 前置检查 → 预检 → 物品发号（如有）→ 事务：progress_count + 1 后在事务内锁定读回；人次达到阈值即锁存；资金只看 funds_granted（即使达标时资金配置为 0，档期内策划改成正数后，下一个点灯者仍会补发一次）。提交后同步投递物品、推送、回视图。今日次数用完回 kGuildActivityAlreadyClaimed。
- internal: 进度行 upsert 之后锁定读回，保证两个并发点灯者不会都以为自己是「第 N 个」。
- java: missing
- size: S
- robot: none
- hazards: 提交之后的同步投递、推送、视图回读都是 best-effort，失败不能报成 RPC 失败（否则玩家会再点一次、得到「今日已领」）。

### guild-reunion — 中秋团圆领奖
- mmorpg: go/guild/internal/logic/activity_logic.go（ClaimGuildReunion / countReunionOnline / reunionEligible）、go/guild/internal/data/activity_repo.go（ClaimReunionTx）、go/guild/internal/activity/rules.go（ReunionThreshold / ReunionObserved / ReunionOutcome）
- client messages: 240 ClaimGuildReunion（C2S）
- tables: GuildActivity（type=2）、GuildRule.reunion_min_online_members=3、activity_join_min_hours
- depends on: guild-activity-core、guild-presence-names（严格在线判定）
- behavior: 生效阈值 = max(行的 guild_threshold, GuildRule.reunion_min_online_members)。本档期还没锁存时，先在事务外数一次「入帮满 N 小时且在线」的成员人数；不够回 kGuildActivityThresholdNotReached [online, threshold]；够了就在事务里锁存。锁存之后不再数人数，每人每个游戏日照常可领。任何一人的在线状态读不到，整次按故障返回（gRPC 错误），不能少算也不能多算。资金在一个档期内只发一次（默认配置资金为 0，只发个人奖）。
- internal: 人数只在事务外数一次，事务内不重数（数完到提交之间有人下线，仍以数人数那一刻为准）。
- java: missing
- size: S
- robot: none
- hazards: 锁存不可撤销，这正是在线判定必须 fail-closed 的原因。

### guild-trial — 同道历练（邀请房间 → 开战 → 战后结算）
- mmorpg: go/guild/internal/logic/activity_logic.go（StartGuildTrial / RespondGuildTrialInvite / trialNotOpenYet，**目前是桩**）；B6b 已有的周边：proto/match/match_internal.proto、proto/battle/battle_node.proto（活动上下文）、proto/contracts/kafka/match_event.proto（guild-trial 消费组）、cpp/libs/services/battle/system/battle_result_activity.h；go/guild/internal/data/trial_result_record.go 在头注释里被引用，但仓库里不存在。**勘误（scene-battle-spec §10.7 第 9 条，按基线 26ceb70ca 核对）**：go/ 下没有 battle:activity_result:{battle_id} 的读者，这条活动结果通道在基线只有写方（battle 节点，cpp/nodes/battle/logic/battle_room_manager.cpp），销账方与巡检器都没有实现；帮会也没有 battle:lock 的生产读者——go/guild/internal/activity/rules.go:391 只是 MyTrialBattleID 字段的注释，全仓只有单测给这个字段赋值；活动开局的读锁在 match（go/match/internal/logic/activitybattlelogic.go:220-228）
- client messages: 242 StartGuildTrial、243 RespondGuildTrialInvite（C2S）
- tables: GuildActivity（type=3：dungeon_id、team_size_min / max 在 2..5 之间、guild_threshold = 每日计资金胜场上限）、GuildRule.trial_invite_ttl_seconds / trial_invite_cooldown_seconds、Dungeon
- depends on: guild-activity-core；B6b 还需要 match 内部服务、battle 活动对局、Kafka 战报
- behavior: **当前 Go 行为**：公共前置照常执行（无会话回 PermissionDenied，未入帮回 14002），然后固定回 kGuildActivityNotOpen；视图里历练行恒为 DISABLED。B6b 设计（未落地）：StartGuildTrial 只建邀请房间（名单含发起人，人数必须在 [min, max] 内且全员在线、不在战斗、同帮），被邀请人亲自同意后才开战；accept=false 时拒绝或取消，房间解散；房间超时解散；建房有冷却；房间状态为 PENDING / LAUNCHING / LAUNCHED / ENDED，ENDED 带 end_tip_id；胜利按名单（扣掉逃跑者）发个人奖，每日计资金的胜场有上限。
- internal: B6b 计划：guild_trial_battle 与 guild_trial_reward_owed 两张表（锁序位置 9 和 10）、调 match 内部 RPC、消费 Kafka 战报、battle:activity_result:{battle_id} 结算后销账，以及巡检器。
- java: missing — Java 版连桩都没有（gate 回 1003）。Java 要先做到与 Go 当前行为一致的桩（size S）；完整版要等 mmorpg B6b 定稿，并依赖 Java 的匹配和战斗。
- size: XL（完整）/ S（桩）
- robot: none
- hazards: 历练相关的 tip 码在当前 tip 数据里同样不存在。match_internal.proto 现在只有 NetworkPolicy 收口，没有调用方鉴权。

### guild-robot-scenarios — 帮会 robot 冒烟场景移植
- mmorpg: robot/guild_smoke_scenario.go、robot/guild_economy_smoke.go
- client messages: 前面各项涉及的消息号全部
- tables: none
- depends on: 本清单除活动以外的全部功能；GM 加钱 / 扣钱（economy 段）
- behavior: 管理段：0–10 步加 M1–M10，打印 `GUILD_MGMT_OK` / `GUILD_SMOKE_OK` / `GUILD_SMOKE_FAIL step=…`；需要 5–6 个同区账号（robot_92xx）加 1 个别区账号，相邻请求间隔 300ms，同一消息号连发时再隔 1 秒（被限频的表现是不回包）。经济段：full 和 degraded 两种模式，打印 `GUILD_ECONOMY_SMOKE_OK mode=…`。
- internal: Java 版 robot 是 xm-robot（Java 实现），需要新增帮会场景，或者确认 Go robot 能直接连 Java 版跑。
- java: missing — xm-robot 里没有任何帮会内容（grep 无结果）。
- size: M
- robot: 本项本身
- hazards: 经济段依赖 GM 客户端消息给账号发货币，并读背包断言；Java 现在没有 GM 闸，也没有货币和背包。

### guild-zone-merge — 合服时的帮会迁移
- mmorpg: tools/merge_zone/guild_step.go（以及 audit_checks.go、fence.go）、go/guild/internal/logic/merge_fence.go
- client messages: none（合服期间帮会写操作回 14013）
- tables: none
- depends on: guild-zone-isolation、guild-rank（启动重建或调用 RebuildRanks）
- behavior: 运维流程：设置 `merge:in_progress:{zone}` → 把源区帮会行的 zone_id 改成目标区（按 idx_guild_0 整区改写），同时迁移玩家归属映射 → 重建区榜 → 删除闸门键。帮名全服唯一，所以合服不用改名。
- internal: 离线工具，不在 guild 进程里。
- java: not_applicable — Java 版首批不做合服（architecture.md §10「合服与 TiDB 数据层」）。等 Java 做合服时再补，guild 侧只需保留闸门接口。
- size: M
- robot: none
- hazards: 合服时把区内帮会整区改写，与解散删除 guild 行是并发路径，所以解散要在事务里判闸门。

## Open questions

1. **活动 tip 码与配表数据缺失**：在 26ceb70ca 上，tip_text.json 的帮会段只到 14031；kGuildActivity*（5 个）和 kGuildTrial*（5 个）都没有码值；go/shared/generated 里也没有 GuildActivity 表和 GuildRule 第 8–10 列。B6a 的 Go 代码是否以 mmorpg 工作区里未提交的导表产物为准？Java 做活动之前，需要 mmorpg 先导出码值和 GuildActivity 数据，并重新跑 ContractSync。
2. **Java 网关的「不支持 / 故障」回法**：mmorpg 路由服回的是带请求 id 的信封（`MessageContent{id, message_id, error_message}`），Java 现在回的是独立的 tip 推送。接入 guild 时是否统一改成信封？会影响 robot guild_smoke 第 9 步，以及客户端按请求 id 的回调。
3. **非 scene 服务如何推送**：Java 没有 Kafka，也没有 gate 命令通道。guild → 玩家的 220 推送选哪条路（Dubbo 调 gate、Redis pub/sub 按 gate 节点分发、还是借 scene 链路）？又如何定位玩家所在的 gate（Java 没有 player:session 目录）？在线标记（GuildMember.online、团圆人数）用同一个来源。
4. **资产通道的 Java 形态**：帮会经济与活动物品的前提是 scene 侧的货币 / 背包与 seq 账本。这属于 scene 或背包区域，需要确认归属与排期。Java 的 owner_epoch 写回围栏如何与资产账本合在同一次落盘？
5. **归属区的真源**：Java 可以直接用 player.zone_id（建角时写入）当归属区，前提是 Java 不做合服。将来做合服时，这一列和帮会的 zone_id 要一起迁移。
6. **排行同分顺序**：mmorpg 里没有任何服务端调用 UpdateGuildScore，全部帮会 0 分，排序完全由 Redis ZSET 对同分按 member 字典序降序决定。Java 是否需要复刻「按 guild_id 字符串降序」，还是登记为有意差异？积分来源（谁、何时给帮会加分）在 mmorpg 里也还没有定义。
7. **guild_id 发号**：mmorpg 用 data_service 号段（可选回退雪花）。Java 用 xm-common 的雪花是否可以接受？客户端只当 uint64 用，看起来没有兼容问题。
8. **GuildRule 第 9 列 trial_invite_ttl_seconds 的数据是 0**：如果照 Go 注释的范围（10–120）在启动时校验，现在的数据会拒绝启动。Go 是否只在 B6b 时才校验这一列？
9. **mmorpg 侧可能的缺陷，供对齐时判断**：(a) GetGuildRankPage 逐条 GetGuild，有 N+1 读；(b) 在线状态 MGET 没有独立超时，可能吃光整个请求预算；(c) 申请推送冷却在 Redis 出错时选择不推（偏向少推）；(d) 客户端发 220 由 guild 回 PermissionDenied、路由服再变成 1003，而 Java 的 MessageRoutes 会把 220 判成「不回包」。
