# 帮会核心（guild，批次 4.4）移植统一规格：从 mmorpg 基线到 Java 版

> **基线**：mmorpg `26ceb70ca`，与 `contract/SOURCE.properties:4` 的 `mmorpg.commit` 相同。Java 侧以 `8a4726f` 为准。
> 写作时工作区里有一批未提交的改动（组队 xm-team、`Deadline` / `PlayerProfiles` 迁到 xm-common、热关停等），涉及
> `MessageRoutes.java`、`GateConfiguration.java`、`RedisKeys.java`、`NodeTypes.java`、`DubboGroups.java`、`start-slice.sh` 等文件。
> 这些文件的行号按当前工作区给出，提交后可能小幅漂移，所以引用时同时写出方法名。
>
> **路径怎么读**
> - 不带目录的 `guild_repo.go`、`guild_manage_repo.go`、`economy_repo.go`、`activity_repo.go`、`asset_store.go`、`tables.go`、`server_version.go`，
>   以及 `guild_manage_repo_test.go`、`guild_lock_order_mysql_test.go`、`guild_lock_plan_mysql_test.go`、`guild_repo_test.go`、
>   `guild_repo_zone_test.go`、`rank_page_test.go`、`rank_zone_integration_test.go`、`server_version_test.go`：都在 mmorpg `go/guild/internal/data/` 下。
> - `guild_logic.go`、`guild_manage_logic.go`、`economy_logic.go`、`push.go`、`online_status_resolver.go`、`player_name_resolver.go`、`home_zone.go`、
>   `merge_fence.go` 及同目录的 `*_test.go`（`guild_manage_logic_test.go`、`client_zone_test.go`、`merge_fence_test.go`、`guild_logic_names_test.go`、
>   `player_name_resolver_test.go`、`home_zone_test.go`、`guild_id_mint_test.go`）：在 `go/guild/internal/logic/` 下。
> - `session.go` / `session_test.go`：`go/guild/internal/session/`；`constants.go`：`go/guild/internal/constants/`；`config.go` / `config_test.go`：
>   `go/guild/internal/config/`；`guild_id_minter.go` / `servicecontext.go`：`go/guild/internal/svc/`；`guild_server.go`、`inband_observability_test.go`：
>   `go/guild/internal/server/`；`guild.go` / `guild_test.go`：`go/guild/`；`guild.yaml`：`go/guild/etc/`。
> - `guild.proto`、`guild_db.proto`：mmorpg `proto/guild/` 下，行号用 mmorpg 的。Java 同步副本在 `xm-proto/src/main/proto/proto/guild/`，
>   只多第 2–4 行 java option，所以 **Java 行号 = mmorpg 行号 + 3**。
> - `forwardlogic.go`：mmorpg `go/client_rpc_router/internal/logic/forwardlogic.go`。`guild_smoke_scenario.go`：mmorpg `robot/`。
>   `messagelimiter.json`、`guildrule.json`、`guildlevel.json`：mmorpg `generated/tables/`。
> - `GuildClient.cs`：`D:\work\mmorpg-client\Assets\Scripts\Game\Guild\GuildClient.cs`；`GameClient.cs`：`D:\work\mmorpg-client\Assets\Scripts\Game\GameClient.cs`。
> - 以 `xm-`、`docs/`、`config-data/`、`contract/`、`tools/` 开头的路径，以及 `PARITY.md`、`AGENTS.md`：在 `D:\work\xuanming-server-mmo` 下。
>   `guild_error_tip.proto` 指 `xm-table/src/main/proto/tip/guild_error_tip.proto`；`tip_text.json` 指 `config-data/tables/tip_text.json`；
>   `message_id.txt` 指 `xm-proto/src/main/resources/contract/message_id.txt`（**号 N 在第 N+1 行**）。
> - mmorpg 设计稿 `docs/design/guild-phase2/*.md` 只作背景；与代码冲突时以代码为准。
> - Redis 键里的 `xm:` 是前缀；Java 键里的 `{...}` 是 hash tag（§1.10）。
>
> **本稿的来历**：由三份分区规格合并而成，三份分别讲数据层（表、键、事务、缓存、排行、发号）、RPC / logic 层（准入、前置、
> 推送、在线、名字）、客户端契约 / robot / Java 落地映射。
> - 分区稿之间互相矛盾、或与代码不符的地方，都回到代码重新核对过；对分区稿的更正列在 §9.4，对 inventory 的更正列在 §9.5。
> - 本稿只读代码，没有改任何源文件。
>
> **与 pbmysql 的关系**：用户要求阶段 4 起「形状由 proto 消息决定的 MySQL 表」一律接 pbmysql（`docs/porting/roadmap.md:50-53`）。
> 帮会四张核心表正是这类表：Java 版用自有的 `guild_tables.proto` 经 xm-pbmysql 建表，DDL 与 Go proto2mysql 逐字节相同
> （`xm-pbmysql/src/test/java/com/game/pbmysql/CreateTableSqlTest.java:310-341` 已对同步来的 `guild_db.proto` 钉住了 `guild` 与 `guild_member` 两张表），
> 业务 SQL 全部手写（§7.5）。

---

## 0 概览与协议

### 0.1 链路与依赖

**基线链路**
- **请求路径**：客户端 → C++ gate（路由服模式）→ `client_rpc_router` → guild 进程（go-zero zrpc，`Name: guild.rpc`，`guild.yaml:2-3`）。
  guild 是全服单服务，不按 zone 切分（`guild.yaml:9-11`）。
- **路由服**：上游任何 gRPC 错误或超时都翻成**信封** `MessageContent{id, message_id, error_message{1003}}`，不带 parameters
  （`forwardlogic.go:170-183`、`:225-232`）；成功时把应答字节原样装进 `serialized_message`（`:186-191`）。转发超时 `ForwardTimeoutMs = 5000`
  （`config.go:15`、`guild.yaml:4`）。
- **服务端超时**：zrpc `Timeout: 4000`（`guild.yaml:7`）；整请求业务预算 = `Timeout − 500 = 3500 ms`（`config.go:18-19`、`:251-253`），
  由拦截器链最内层套到 handler 的 ctx 上（`guild.go:487`、`:492-498`）。

**基线依赖**

| 依赖 | 用途 | 出处 |
|---|---|---|
| `RedisClient`（全局 Redis，DB 2） | 帮会快照、玩家→帮会映射、排行 ZSET、榜维护锁、申请推送冷却；启动 Ping 失败即 panic | `guild.yaml:13-17`；`servicecontext.go:51-59` |
| `PlayerLocatorRedis`（DB 0；Host 为空时回落到 RedisClient 的 Host、DB 0） | 只读 `player:session:{id}`：在线判定与推送路由 | `guild.yaml:19-23`；`servicecontext.go:61-73`；`online_status_resolver.go:17-24` |
| `MergeMarkerRedis`（可选，data_service 的 mapping Redis，DB 0） | 只做 `EXISTS merge:in_progress:{zone}`（合服闸门）；Ping 失败不拒启 | `guild.yaml:25-38`；`servicecontext.go:146-163`；`merge_fence.go:11-28` |
| MySQL 独占库 `mmorpg_guild` | 权威数据（四张核心表 + B5/B6 的表） | `guild.yaml:40-50`；`tables.go:13`；`config.go:282-285` |
| data_service（gRPC，etcd key `dataservice.rpc`） | `GetPlayerHomeZone`（归属区）、`BatchGetPlayerName`（展示名）、`AllocateIdSegment`（guild_id 号段） | `guild.yaml:80-89`；`home_zone.go:49-61`；`player_name_resolver.go:96-142`；`guild_id_minter.go:35-69` |
| etcd | 节点注册、snowflake 槽位、热关停规则 | `guild.go:144-214` |
| Kafka `gate-cmd` | 推送 220 | `push.go:76-155`；`servicecontext.go:97-122` |

**Java 侧的对应**
- **入口**：gate 经 Dubbo 调 `ClientMessageService.handle(ClientCall) → CompletableFuture<ClientReply>`，group 为 `guild`
  （`docs/design/architecture.md:75-90`；`xm-api/src/main/java/com/game/api/DubboGroups.java:7-22` 的现有写法）。
- **进程**：新模块 **xm-guild**（Spring Boot + Dubbo Triple），结构照 xm-friend（`architecture.md:350-391`；路线图规定「每个服务一个进程」，
  `docs/porting/roadmap.md:48`）。批次 4.4 的范围见 `roadmap.md:61`。
- **存储**：MySQL `xm_java` 库（`AGENTS.md` §3）四张表经 xm-pbmysql 建；Redis（Redisson，DB 12）键经 `com.game.discovery.RedisKeys` 生成。
- **在线态与推送**：`xm:presence` + `PlayerPushes`（`architecture.md:128-147`）；展示名与归属区读 `xm_java.player`（`xm-common/.../player/PlayerProfiles.java:18-26`）。

### 0.2 消息号、请求 / 应答形状、准入、gate 限频

**数据来源**
- 消息号只能取自 `MessageIdRegistry`（`message_id.txt`），代码里不写数字。服务全名 `guildpb.GuildService`，标了 `OptionIsClientProtocolService`、
  没标 `OptionIsPlayerService`（`guild.proto:530-531`），所以 Java gate 会把**全部 28 个号**（含 8、220）路由到同一个后端
  （`xm-gate/.../session/MessageRoutes.java:38-49`、`:57-62`）。
- 限频取自 `messagelimiter.json`（Java 读同一份 `config-data/tables/messagelimiter.pb`）；表里没有的号按缺省每秒 3 条
  （`xm-gate/.../session/MessageLimit.java:13-14`）。超频 Java 回信封 1008 并计非法包（`ClientDispatcher.java:229-235`）。
- 基线客户端白名单是 `session.go:47-78` 的 `ClientMethods`；8 与 `Notify*` **永不登记**（`session.go:43-46`；单测 `session_test.go:101-120`）。

| 号（`message_id.txt` 行） | 方法（`guild.proto` 行） | 请求 → 应答 | 批次 | 客户端可调 | 无会话内部调用（基线） | 限频（`messagelimiter.json`） | 客户端（`GuildClient.cs`） |
|---|---|---|---|---|---|---|---|
| 15（:16） | CreateGuild（:49-58） | `{player_id=1, name=2, zone_id=3}` → `{error_message=1, GuildInfo guild=2}` | 4.4 | ✓ | 照常，信请求体 player_id / zone_id | 5/s | :326-334 |
| 60（:61） | GetGuild（:60-67） | `{guild_id=1}` → `{error_message, guild}` | 4.4 | ✓ | 照常，不按区过滤 | 10/s | 不调用 |
| 35（:36） | GetPlayerGuild（:69-76） | `{player_id=1}` → `{error_message, guild}` | 4.4 | ✓ | 照常（走缓存路径） | 10/s | :241-267 |
| 29（:30） | LeaveGuild（:78-84） | `{player_id=1}` → `{error_message}` | 4.4 | ✓ | 照常 | 5/s | :437-443 |
| 38（:39） | DisbandGuild（:86-92） | `{player_id=1}` → `{error_message}` | 4.4 | ✓ | 照常 | 5/s | :445-451 |
| 39（:40） | SetAnnouncement（:94-103） | `{guild_id=1, player_id=2, announcement=3}` → `{error_message, guild}` | 4.4 | ✓ | 照常 | 5/s | :425-435 |
| 19（:20） | SetGuildMemberRole（:109-116） | `{target_player_id=1, role=2}` → `{error_message, guild}` | 4.4 | ✓ | PermissionDenied | 5/s | :370-377 |
| 217（:218） | KickGuildMember（:118-124） | `{target_player_id=1}` → `{error_message, guild}` | 4.4 | ✓ | PermissionDenied | 5/s | :380-387 |
| 216（:217） | TransferGuildLeader（:126-132） | `{target_player_id=1}` → `{error_message, guild}` | 4.4 | ✓ | PermissionDenied | 5/s | :390-397 |
| 218（:219） | ApplyJoinGuild（:136-141） | `{guild_id=1}` → `{error_message}` | 4.4 | ✓ | PermissionDenied | 5/s | :337-349 |
| 219（:220） | CancelGuildApplication（:143-148） | `{guild_id=1}` → `{error_message}` | 4.4 | ✓ | PermissionDenied | 5/s | :352-367 |
| 222（:223） | ListMyGuildApplications（:163-167） | `{}` → `{error_message, applications=2}` | 4.4 | ✓ | PermissionDenied | 10/s | :285-295 |
| 221（:222） | ListGuildApplications（:178-183） | `{}` → `{error_message, applicants=2}` | 4.4 | ✓ | PermissionDenied | 10/s | :309-320 |
| 223（:224） | ReviewGuildApplication（:185-192） | `{applicant_player_id=1, bool approve=2}` → `{error_message, guild}` | 4.4 | ✓ | PermissionDenied | 5/s | :400-423 |
| 27（:28） | GetGuildRank（:500-512） | `{page=1, page_size=2, zone_id=3}` → `{error_message, entries=2, total_count=3, page=4, page_size=5}` | 4.4 | ✓ | 照常，可查全服榜（zone 0），页长不夹 | 10/s | :269-282 |
| 52（:53） | GetGuildRankByGuild（:514-522） | `{guild_id=1, zone_id=2}` → `{error_message, GuildRankEntry entry=2}` | 4.4 | ✓ | 照常 | 10/s | 不调用 |
| 8（:9） | UpdateGuildScore（:490-498） | `{guild_id=1, int64 score=2, zone_id=3}` → `{error_message}` | 4.4 | **✗**（PermissionDenied → 信封 1003） | 只对内部开放 | 表中无 → 3/s | 不调用；robot 第 9 步 |
| 220（:221） | NotifyGuildChanged（:215-220、:554） | **S2C** `GuildChangedS2C` → `Empty` | 4.4 | **✗**（推送占位） | 恒回 Empty（`guild_server.go:128-133`） | 表中无 → 3/s | `RegisterNotify`（:141） |
| 53 / 76 / 120 / 228 / 233 | Donate / Upgrade / DonateOptions / GetShop / BuyShop（:290-339、:563-567） | — | **4.5** | ✓ | — | 5/5/10/10/5 | :483-617 |
| 239–243 | 活动五个（:434-474、:571-575） | — | **4.6** | ✓ | — | 表中无 → 3/s | 不调用 |

- B2 起的 8 个管理 / 申请 RPC 在协议里**没有** player_id 字段（`guild.proto:105-107`），无会话一律 PermissionDenied
  （`guild_manage_logic.go:222-232`、`:534-538`、`:620-624`、`:689-693`；单测 `guild_manage_logic_test.go:180-191`）。
- Java 的 `ClientMessageService` 只给 gate 用、总带会话（`xm-api/src/main/java/com/game/api/ClientMessageService.java:34-46`），
  没有「无会话内部调用」这条口，见 D12。

### 0.3 消息形状与字段语义

**GuildMember**（`guild.proto:12-25`）
- `player_id=1`；`role=2`：持久化编码 **0 成员 / 1 长老 / 3 帮主，2 是空号**（`constants.go:9-13`），比较权限一律换算成 Rank（§2.1）。
- `join_time_ms=3`；`last_active_ms=4`：只在插入成员行时写 now，全仓库没有 UPDATE（`guild_db.proto:67`；`guild_repo.go:358-361`；
  `guild_manage_repo.go:735-736`），所以恒等于 `join_time_ms`。
- `reserved 5`；`online=6`：只有会话状态 ONLINE 才算在线，读失败按离线（§4.6）；`name=7`：展示名，取不到留空（§4.7）。
- `contribution_total=8` / `contribution_balance=9`：4.4 恒 0（插入成员行都写 `0, 0`），B5 起写入。

**GuildInfo**（`guild.proto:27-45`），由 `toProtoGuild`（`guild_logic.go:735-786`）+ `guildInfoFor`（`guild_manage_logic.go:772-788`）装配，详见 §4.1：
- `guild_id=1, name=2, leader_id=3, level=4, announcement=5, create_time_ms=6, max_members=7, members=8（player_id 升序）, zone_id=9, funds=10,
  max_officers=11, officer_count=12, upgrade_cost_funds=13, leader_name=14, pending_application_count=15`。
- **score 不在 GuildInfo 里**；`funds` 4.4 恒 0；`pending_application_count` 只对本帮长老 / 帮主非 0。

**GuildApplicationView**（`guild.proto:151-161`）：`guild_id, guild_name, level, member_count, max_members, leader_id, leader_name, apply_ms, expire_ms`；
列表按 `apply_ms` 降序、`guild_id` 升序（`:166`）。

**GuildApplicantView**（`guild.proto:170-176`）：`player_id, name, online, apply_ms, expire_ms`；列表按 `apply_ms` 升序、`player_id` 升序，
至多 `GuildRule.max_pending_applications_per_guild` 条（`:181-182`）。

**GuildRankEntry**（`guild.proto:479-488`）：`guild_id, name, leader_id, level, member_count, int64 score, rank（1 起）, leader_name`。

**GuildChangedS2C**（`guild.proto:215-220`）：`kind=1, guild_id=2, actor_player_id=3（系统触发为 0）, target_player_id=4（没有则 0）`。
`GuildChangeKind` 共 **14 个值**：`UNSPECIFIED=0` 与 13 个有意义的值 1–13（`guild.proto:198-213`）：
MEMBER_JOINED 1、MEMBER_LEFT 2、MEMBER_KICKED 3、ROLE_CHANGED 4、LEADER_TRANSFERRED 5、DISBANDED 6、APPLICATION_RECEIVED 7、APPLICATION_REJECTED 8、
FUNDS_CHANGED 9（B5）、LEVEL_UP 10（B5）、ANNOUNCEMENT_CHANGED 11、ACTIVITY_CHANGED 12（B6）、DELIVERY_DONE 13（B5）。

### 0.4 tip 码表

**帮会段**（`guild_error_tip.proto:9-76`；Java 写法 `com.game.table.GuildErrorTip.guild_error.kGuildXxx_VALUE`；文案 `tip_text.json:24-55`，
文案里都没有占位符）。基线每个 tip 的形状都是 `TipInfoMessage{id, parameters:[一句固定英文原因]}`（`guild_logic.go:120-122`）。
客户端只看 `Id`（`GuildClient.cs:728-770`）；Java 照抄原句，便于逐字节对拍。

| 码 | 枚举 | 文案（`tip_text.json` 行） | 4.4 的发生点与英文参数 |
|---|---|---|---|
| 14000 | kGuildAlreadyInGuild | 已加入公会（:24） | 建帮预检 / 事务 `"already in a guild"`（`guild_logic.go:237`、`:273`）；申请预检 `"already in a guild"`（`guild_manage_logic.go:485`）、事务（mapWriteErr :305）；退帮时 MySQL 说在别的帮 `"guild membership changed, retry"`（`guild_logic.go:458`） |
| 14001 | kGuildNotFound | 公会不存在（:25） | GetGuild 不存在或别区 `"guild not found"`（`guild_logic.go:362`）；UpdateGuildScore（`:567`、`:584`）；内部 GetPlayerGuild 重读后仍无帮（`:417`）；事务 ErrGuildGone / ErrGuildZoneMismatch（`guild_manage_logic.go:285-288`）；申请 guild_id=0 `"guild id is zero"`（`:470`） |
| 14002 | kGuildNotInGuild | 尚未加入公会（:26） | GetPlayerGuild / operatorGuild `"not in any guild"`（`guild_logic.go:385`、`:395`、`:410`；`guild_manage_logic.go:249`；`economy_logic.go:272`）；事务 ErrNotGuildMember `"not a member of the guild"`（`guild_manage_logic.go:289-291`）；列待审时查不到本人成员行（`:638`） |
| 14003 | kGuildFull | 公会人数已满（:27） | 申请、审批通过 `"guild is full"`（`:300-301`） |
| 14004 | kGuildLeaderCantLeave | 会长不能退出公会（:28） | 退帮 `"leader cannot leave, disband or transfer instead"`（`:298-299`） |
| 14005 | kGuildNotLeader | 只有会长可以执行该操作（:29） | **只用于解散** `"not guild leader"`（`guild_logic.go:494-498`） |
| 14006 | kGuildNoPermission | 权限不足（:30） | 公告：不在该帮或职位不足 `"no permission"`（`guild_logic.go:544-546`）；任免 role∉{0,1} `"role not assignable"`（`guild_manage_logic.go:384-386`） |
| 14007 | kGuildNotRanked | 暂未上榜（:31） | 52 `"guild not ranked"`（`guild_logic.go:654-656`） |
| 14008 | kGuildIdGenUnavailable | 服务繁忙，请稍后重试（:32） | 建帮发号失败 `"id generator unavailable"`（`guild_logic.go:328-340`）。**唯一的 in-band 故障码**：Tip 表 fault 列标 1，serverbase 计入 `rpc_inband_fault_total`（`constants.go:71-73`、`:208-219`；`go/shared/generated/tip/faults.go:60`；`inband_observability_test.go:107`） |
| 14009 | kGuildNameInvalid | 公会名称无效（1-24 个字，不能包含控制字符）（:33） | `"invalid guild name"`（`guild_logic.go:195-198`） |
| 14010 | kGuildNameTaken | 公会名称已被使用（:34） | 撞 `uk_guild` `"guild name taken"`（`:274-275`） |
| 14011 | kGuildAnnouncementTooLong | 公会公告过长（:35） | `"announcement too long"`（`:524-526`） |
| 14012 | kGuildHomeZoneUnknown | 角色归属区服未确认，暂时无法使用公会（:36） | clientZone 查到 0 `"home zone unknown"`（`:160-164`） |
| 14013 | kGuildZoneMerging | 区服合并维护中，帮会操作暂停，请稍后再试（:37） | 事务外闸门 `"merge fence unreadable"` / `"zone merging"`（`:310-318`）；事务内闸门 `"zone merging"`（`guild_manage_logic.go:312-316`） |
| 14014 | kGuildTargetNotMember | 对方已不在本帮会（:38） | target=0 `"target player id is zero"`（`:336-338`）；事务 `"target is not a member"`（`:292-293`） |
| 14015 | kGuildCannotTargetSelf | 不能对自己执行此操作（:39） | `"cannot target self"`（`:339-341`）；审批自己 `"cannot review own application"`（`:698-700`） |
| 14016 | kGuildRankTooLow | 帮会职位不足，无法执行此操作（:40） | 事务 `"rank too low"`（`:294-295`）；列待审 `"officer rank required"`（`:641-645`） |
| 14017 | kGuildOfficerLimit | 长老人数已达当前帮会等级上限（:41） | `"officer limit reached"`（`:296-297`） |
| 14018 | kGuildApplicationNotFound | 入帮申请不存在或已失效（:42） | 事务 `"application not found or expired"`（`:306-307`）；撤回 guild_id=0 `"guild id is zero"`（`:516-522`）；审批申请人=0 `"applicant player id is zero"`（`:695-697`）；申请人没有归属区 `"applicant home zone unknown"`（`:735-738`） |
| 14019 | kGuildApplicationLimit | 同时进行中的入帮申请已达上限（:43） | `"pending application limit reached"`（`:308-309`） |
| 14020 | kGuildApplicationQueueFull | 该帮会待审申请已满，请稍后再试（:44） | `"guild application queue is full"`（`:310-311`） |
| 14021 | kGuildBusyRetry | 帮会操作繁忙，请稍后重试（:45） | 写冲突 / 超子预算 / 1205 / COMMIT 结果不明 `"guild write conflict"`（`:317-321`；`guild_logic.go:276-279`、`:585-587`） |
| 14022–14031 | 经济段 | （:46-55） | 4.4 不发；码与文案已就位，留给 4.5 |

- **活动段 tip 码在 Java 契约里不存在**：mmorpg 代码引用的 `kGuildActivity*` / `kGuildTrial*`（`constants.go:144-172`）在同步来的
  `guild_error_tip.proto` 里没有（帮会段止于 14031，`:74`），GuildActivity 表也未导出（`contract/SOURCE.properties:11-12`；`PARITY.md:52`）。

**通用段**（`tip_text.json`）：1003 kServiceUnavailable（:6，信封故障）、1006 kFeatureUnavailable（:9，D13 的经济 / 活动占位）、
1008 kRateLimitExceeded（:11，gate 信封）、1010 kMessageSizeExceeded（:13，gate 信封）、1013 kMessageIdNotFound（:16）。

### 0.5 配置与常量

**基线配置**（`guild.yaml`、`config.go`）

| 键 | 现值 / 缺省 | 用途与校验 | 出处 |
|---|---|---|---|
| `Timeout` | 4000 ms | ≤ 路由服 5000 − 1000；整请求预算 = Timeout − 500 | `guild.yaml:4-7`；`config.go:15-19`、`:251-265` |
| `DataServiceRpc.Timeout` | 2000 ms | ∈ [500, 3000]；且 `Timeout ≥ DataServiceRpc.Timeout + 1500 + 500`（建帮最坏串行做归属区查询与同步领号段） | `guild.yaml:86`；`config.go:25-30`、`:266-276`；单测 `config_test.go:123` |
| `MySQL.DataSource` | 库名必须 `mmorpg_guild`；禁止 `clientFoundRows=true` | — | `config.go:277-292`；`config_test.go:173`、`:199` |
| `Schema.AutoMigrate` | 不写或 true = 启动跑 schemamigrate.Up；false = 只 Plan、不干净拒启 | — | `guild.yaml:47-50`；`config.go:243-246`；`guild.go:540-567` |
| `Cache.DefaultTTL` | 30m | 快照与映射缓存的 TTL | `guild.yaml:67-68`；`guild.go:117` |
| `Cache.MaxMembers` | 500 | **代码里没有读者**（死配置） | `config.go:358-361` |
| `Kafka.Brokers` | 空 → 不推送（NoopNotifier） | — | `servicecontext.go:97-122`；`push.go:92-96` |
| `IdSegment` | `Enabled=true, Step=100, MinStep=10, MaxStep=1000, FallbackToSnowflake=false` | guild_id 号段 | `guild.yaml:91-104` |
| `KillSwitchPrefix` | 空 = `/mmorpg/killswitch/` | 热关停 | `guild.yaml:145-150` |
| `Prometheus` | 127.0.0.1:9220 | 指标端点 | `guild.yaml:159-175`；`config_test.go:19` |

**代码常量**

| 常量 | 值 | 出处 |
|---|---|---|
| `MaxGuildNameRunes` | 24（码点，trim 后） | `constants.go:199` |
| `MaxGuildNameNormRunes` | 48 | `guild_repo.go:522` |
| `MaxAnnouncementBytes` | 600（UTF-8 字节，不 trim；gate 单包 1 KB 的约束） | `constants.go:200-203` |
| `MaxRankPageSize` | 50（缺省页长 20，缺省页 1） | `constants.go:205`；`guild_logic.go:598-605` |
| `MaxGuildMembersCap` | 100（GuildLevel.max_members 的校验上限，也是推送 / 快照预算前提） | `constants.go:189-194` |
| `DefaultInitLevel` | 1 | `constants.go:186` |
| `myApplicationsLimit` | 10（= 每人待审上限的校验上界，列表不会被截断） | `guild_manage_logic.go:47-50` |
| `ApplyPushCooldown` | 60 s | `guild_manage_repo.go:683` |
| `purgeExpiredApplicationsPerApply` | 10 | `guild_manage_repo.go:1857` |
| 事务子预算 | 1500 ms；解散 2500 ms | `guild_manage_repo.go:115`、`:123` |
| `maxTxAttempts` / 退避 | 3 次 / 10–50 ms 随机 | `guild_manage_repo.go:103`、`:257` |
| `LockWaitTimeoutSeconds` | 1 | `guild_manage_repo.go:315` |
| 失效后台重试 | 100 / 400 / 1600 ms，后台预算 3 s | `guild_manage_repo.go:513`、`:553` |
| 榜维护锁 | TTL 5 min，最多等 5 s，20 ms 轮询 | `guild_repo.go:156-160`、`:790` |
| 归属区查询 / 取名 / 推送 | 1500 ms / 800 ms / 3 s | `home_zone.go:28`；`player_name_resolver.go:45`；`push.go:41` |
| 取名单批上限 | 500 | `player_name_resolver.go:54` |
| IN 列表分块 | 100 | `guild_manage_repo.go:955`、`:1206` |

**配表**（只存 id、用时现查，查不到 fail-closed，`guild_manage_logic.go:37-42`）
- `GuildRule[1]`（单行，`guild_manage_logic.go:45`）：当前数据 `application_expire_hours=72, max_pending_applications_per_player=3,
  max_pending_applications_per_guild=50`（`guildrule.json`）。4.4 读第 2–4 列；第 5–6 列归 4.5，第 7 列及以后归 4.6
  （`xm-table/src/main/proto/guildrule_table.proto:13-20`）；第 8–10 列在 Java 是 `reserved`（数据未导出，`:53-59`）。
- `GuildLevel`：10 级，`max_members` 30→100、`max_officers` 2→6、第 10 级 `upgrade_cost_funds=0`（`guildlevel.json`；
  schema `xm-table/src/main/proto/guildlevel_table.proto:22-38`）。

### 0.6 全局规则（所有 RPC 适用）

1. **两种失败形态**（`guild_manage_logic.go:13-15`）：
   - **业务拒绝 → 应答体 `error_message`（in-band tip）**，handler 返回 nil error。
   - **真故障 → gRPC error**（配表缺行、双存储矛盾、依赖不可用、无会话、未知错误），经路由服变成**信封 1003**。
   - 客户端收到任何信封错误（或传输层失败）就把帮会模块置成 `RequiresReconnect`，之后的帮会请求一律拒绝，直到真正断线
     （`GuildClient.cs:785-808`；`GameClient.cs:1560-1563`）。所以写冲突、合服、职位不足、申请失效都**必须**回 in-band tip。
2. **身份只认会话**：客户端来源的身份 = 会话 `player_id`；请求体里的 player_id / zone_id 一律忽略（不一致只打 ERROR，`guild_logic.go:139-148`）。
3. **写 RPC 的前置顺序固定**：会话 → 归属区 → 合服闸门 → 业务前置 → 事务（`guild_manage_logic.go:10-12`、`:222-232`）。
4. **授权只认锁内 MySQL**：所有权限判定在事务里对 `FOR UPDATE` 锁住的行做；缓存只用来定位操作者属于哪个帮、以及展示（`guild_manage_repo.go:11-12`）。
5. **提交之后才失效缓存、才推送**；失效失败不把已提交的写报成失败，推送失败绝不影响 RPC 结果（`guild_manage_repo.go:56-57`；`push.go:5-15`）。
6. **写冲突一律 14021**：死锁重试用尽、1205、超子预算、COMMIT 结果不明都归一到 `ErrWriteConflict` → 14021（`guild_manage_repo.go:82-86`）。
7. **时钟**：`nowMs()` 是本服务唯一的「现在」（Unix 毫秒，`guild_manage_logic.go:200-202`），用于建帮时刻、入帮时刻、申请 / 过期时刻、
   提前截止捐献、待审计数与列表的过期过滤。
8. **id 一律 uint64**：guild_id、player_id、funds、contribution 在 Java 里必须按无符号处理（§7.5）。

---

## 1 数据模型（MySQL 表、Redis 键、缓存）

### 1.1 库、建表路径与启动期检查

- **事实源**：`guild_db.proto` 是唯一事实源；建表、加列只经 schemamigrate，业务 SQL 一律手写（`guild_db.proto:8-9`；`tables.go:1-2`）。
  改表纪律：字段只追加、`OptionIndex` 组只能追加到末尾（`guild_db.proto:11-12`）；追加区 G 从 12、S 从 3、M 从 8、A 从 5 起（`:39`、`:51`、`:70`、`:85`）。
- **`Tables()` 的顺序就是全库表间锁序**：G guild < S guild_player_state < M guild_member < A guild_application < Q guild_player_op_seq
  < O guild_asset_op < C guild_daily_counter < P guild_activity_progress；B6b 再接 guild_trial_battle(9)、guild_trial_reward_owed(10)
  （`tables.go:15-21`、`:26-74`；`guild_db.proto:15-16`）。新增表要同时追加两份删表清单，测试按 `len(Tables())+1` 守住（`tables.go:23-25`）。
- **基线启动顺序**（`guild.go`）：
  1. 配表校验，失败拒启（`:95-109`；4.4 只涉及 `ValidateGuildTables`）；
  2. `ensureSchema`：Up / Plan；Up 报 Manual 项拒启；**缺 proto 声明的普通索引一律拒启**（schemamigrate 不对已存在的表补建索引，`:540-589`）；
  3. `NewGuildRepo(rdb, db, Cache.DefaultTTL)`（`:117`）；
  4. `CheckServerVersion`，5 s 预算（`:124-130`）；
  5. `EnsureGlobalInsertGuard`，10 s 预算（`:137-142`）；
  6. 注册节点、申领 snowflake 槽、启动热关停（`:144-219`）；
  7. `RebuildRanks`，失败直接 **panic**（`:223-225`）；
  8. 组装发号器并预热号段（`:232-233`）；之后装配 logic、起 gRPC（`:348-375`）。
- **数据库版本下限**（`server_version.go`）：推演只对「TiDB 悲观 + RC」与「MySQL 8.0.29+ InnoDB + RC」成立——审批通过重插删除标记的成员行
  要做 S → X 升级，依赖 InnoDB Bug #11745929 的修复（`:3-14`）。判定（`:44-65`）：`SELECT VERSION()`（`:25`）；含 `tidb`（不分大小写）放行；
  含 `mariadb` 拒绝；否则取开头连续的「数字与点」，至少三段，低于 8.0.29 或解析不出都拒绝（`:69-87`）。单测 `server_version_test.go:13`、`:51`、`:67`。

### 1.2 四张核心表（4.4 必建）

列类型按 proto2mysql 规则生成：uint64 → `bigint unsigned NOT NULL DEFAULT 0`、uint32 → `int unsigned NOT NULL DEFAULT 0`、
int64 → `bigint NOT NULL DEFAULT 0`、string → `MEDIUMTEXT`（可空）、**主键或唯一键里的 string** → `VARCHAR(191) CHARACTER SET utf8mb4
COLLATE utf8mb4_0900_bin NOT NULL DEFAULT ''`（`xm-pbmysql/.../TableSchema.java:63-75`、`:220-236`）。每张表：主键带
`/*T![clustered_index] NONCLUSTERED */`，表尾 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci /*T! SHARD_ROW_ID_BITS=4 PRE_SPLIT_REGIONS=4 */
COMMENT='<表名>'`，列注释 `pb:N`；普通索引名 `idx_<表>_<序号>`、唯一键名 `uk_<表>`（`TableSchema.java:307-314`；`CreateTableSqlTest.java:51`）。

**guild**（`GuildRecord`，`guild_db.proto:19-40`）。DDL 被 `CreateTableSqlTest.java:310-328` 逐字钉住：

```sql
CREATE TABLE IF NOT EXISTS `guild` (
  `guild_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:1',
  `name` MEDIUMTEXT COMMENT 'pb:2',
  `leader_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:3',
  `level` int unsigned NOT NULL DEFAULT 0 COMMENT 'pb:4',
  `announcement` MEDIUMTEXT COMMENT 'pb:5',
  `create_time_ms` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:6',
  `max_members` int unsigned NOT NULL DEFAULT 0 COMMENT 'pb:7',
  `zone_id` int unsigned NOT NULL DEFAULT 0 COMMENT 'pb:8',
  `score` bigint NOT NULL DEFAULT 0 COMMENT 'pb:9',
  `funds` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:10',
  `name_norm` VARCHAR(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT '' COMMENT 'pb:11',
  PRIMARY KEY (`guild_id`) /*T![clustered_index] NONCLUSTERED */,
  INDEX `idx_guild_0` (`zone_id`),
  INDEX `idx_guild_1` (`leader_id`),
  UNIQUE KEY `uk_guild` (`name_norm`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci /*T! SHARD_ROW_ID_BITS=4 PRE_SPLIT_REGIONS=4 */ COMMENT='guild';
```

| 列 | 语义与写者 | 出处 |
|---|---|---|
| guild_id | 发号见 §1.12；建帮时写入 | `guild_db.proto:28`；`guild_repo.go:374-378` |
| name | TrimSpace 后的展示名，**不做 NFKC**，≤ 24 码点，不进索引 | `guild_db.proto:29`；`guild_logic.go:175-191` |
| leader_id | 与 `guild_member.role=3` 同事务维护；只有转让会改 | `guild_db.proto:30`；`guild_manage_repo.go:1559-1560` |
| level | 建帮写 1；4.5 升级改写 | `guild_db.proto:31`；`guild_logic.go:251` |
| announcement | ≤ 600 字节（logic 校验）；读时 `COALESCE(announcement,'')` | `guild_db.proto:32`；`guild_repo.go:464` |
| create_time_ms | 建帮时刻（服务进程时钟） | `guild_logic.go:241`、`:252` |
| max_members | 建帮写 `GuildLevel[1].max_members`；4.5 升级改写；**申请 / 审批判满读的是这一列**，不是现查配表 | `guild_db.proto:34`；`guild_manage_repo.go:1747`、`:2183` |
| zone_id | 归属区；合服工具改写；**清榜与事务内闸门的权威来源** | `guild_db.proto:35`；`guild_repo.go:754-770` |
| score | 排行分的权威副本，ZSET 由它重建；写法是绝对值写入 | `guild_db.proto:36`；`guild_repo.go:586-587` |
| funds | 帮会资金；建帮写 0；4.5 写入 | `guild_db.proto:37`；`guild_repo.go:376` |
| name_norm | `NFKC → TrimSpace → 小写`，≤ 48 码点；**唯一性只看它**（二进制比较，表的 `utf8mb4_unicode_ci` 不参与） | `guild_db.proto:38`；`guild_repo.go:519-534` |

索引的使用者：
- `uk_guild(name_norm)`：建帮判重。识别撞的是哪个唯一键靠错误消息以 `'uk_guild'` 或 `.uk_guild'` **结尾**（帮名本身出现在消息中段，
  按结尾匹配防误判，`guild_repo.go:536-549`；单测 `guild_repo_zone_test.go:21-40`）。
- `idx_guild_0(zone_id)`：服务内没有 SQL 用它，只有 `tools/merge_zone` 用。`idx_guild_1(leader_id)`：服务内没有任何查询用，
  转让改 `leader_id` 时要维护它的二级项（`guild_manage_repo.go:1557-1558`）。

**guild_player_state**（`GuildPlayerStateRecord`，`guild_db.proto:42-53`），按同一套规则推导：

```sql
CREATE TABLE IF NOT EXISTS `guild_player_state` (
  `player_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:1',
  `updated_ms` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:2',
  PRIMARY KEY (`player_id`) /*T![clustered_index] NONCLUSTERED */
) ENGINE=InnoDB ... COMMENT='guild_player_state';
```

- 每玩家一行，**只作串行化锁行**；`updated_ms` 只供诊断（`guild_db.proto:49-50`）。行由 `INSERT IGNORE` 在业务事务外建出，**永不删除**
  （`guild_manage_repo.go:976-1029`）。
- **player_id = 0 是哨兵行（全局插入守卫）**，启动期建好、永不删除（`guild_manage_repo.go:27-45`、`:831-834`）。
- 刻意不存「待审申请数」：别的路径删申请时不锁申请人行，计数会漂（`guild_db.proto:51-52`）。

**guild_member**（`GuildMemberRecord`，`guild_db.proto:55-71`）。DDL 被 `CreateTableSqlTest.java:329-340` 钉住：

```sql
CREATE TABLE IF NOT EXISTS `guild_member` (
  `guild_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:1',
  `player_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:2',
  `role` int unsigned NOT NULL DEFAULT 0 COMMENT 'pb:3',
  `join_time_ms` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:4',
  `last_active_ms` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:5',
  `contribution_total` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:6',
  `contribution_balance` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:7',
  PRIMARY KEY (`guild_id`,`player_id`) /*T![clustered_index] NONCLUSTERED */,
  UNIQUE KEY `uk_guild_member` (`player_id`)
) ENGINE=InnoDB ... COMMENT='guild_member';
```

- `uk_guild_member(player_id)` 保证一人至多一个帮（`guild_db.proto:58`）。用途：插入时撞 1062 → 已入帮；`SELECT guild_id FROM guild_member WHERE player_id=?`
  点查；I1 过滤里的 `LEFT JOIN … ON m.player_id = a.player_id`。
- 帮贡两列 4.4 写 0、4.5 起写入（`guild_db.proto:68-69`）；`last_active_ms` 只在入帮时写（`:67`）。

**guild_application**（`GuildApplicationRecord`，`guild_db.proto:73-86`），按同一套规则推导：

```sql
CREATE TABLE IF NOT EXISTS `guild_application` (
  `guild_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:1',
  `player_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:2',
  `apply_ms` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:3',
  `expire_ms` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:4',
  PRIMARY KEY (`guild_id`,`player_id`) /*T![clustered_index] NONCLUSTERED */,
  INDEX `idx_guild_application_0` (`player_id`),
  INDEX `idx_guild_application_1` (`expire_ms`)
) ENGINE=InnoDB ... COMMENT='guild_application';
```

- 表里**只存待审申请**：通过、拒绝、撤回、过期、解散都删行（`guild_db.proto:85`）。
- `expire_ms = apply_ms + application_expire_hours × 3 600 000`（`guild_db.proto:84`；`guild_manage_logic.go:59-63`）；同帮重复申请刷新两列。

### 1.3 不变量（及其强制点）

| 编号 | 不变量 | 强制点 |
|---|---|---|
| I1 | **有效申请** = `expire_ms > now` **且**申请人没有任何 guild_member 行；只有有效申请计入帮会队列上限、出现在待审列表与待审数里 | `guild_manage_repo.go:774-785`（A13 / A15） |
| I2 | 成员行一出现，就清掉该玩家在所有帮的全部申请 | 建帮 `guild_repo.go:367-372`；审批通过 `guild_manage_repo.go:2201-2206` |
| I3 | 成员行一消失，就清掉该玩家的全部申请；解散另外清掉本帮的全部申请 | 踢人 `:1425-1430`；退帮 `:1636-1638`；解散 `:2303-2314` |
| I4 | 一人至多一个帮 | `uk_guild_member` |
| I5 | 帮名全服唯一（按 name_norm，不分区） | `uk_guild`；`constants.go:76-77` |
| I6 | `guild.leader_id` 与唯一一行 `role=3` 指向同一人 | 转让事务末尾硬断言 `role=3` 人数为 1（`:1570-1576`）；不一致时转让 fail-closed（`:1536-1540`） |
| I7 | 长老数 ≤ `GuildLevel[level].max_officers`（只在任命与转让时判；配表下调不强制降级） | `:1338-1350`、`:1542-1554`；`constants.go:94-96` |
| I8 | 成员数 ≤ `guild.max_members`（申请新行与审批通过时判；刷新申请不判） | `:1743-1749`、`:2179-2186` |
| I9 | 每人有效（未过期）待审申请 ≤ MaxPerPlayer；在 S(p) 锁下计数，并发申请被串行化 | `:1750-1761` |
| I10 | 帮会有效申请（I1）≤ MaxPerGuild | `:1762-1768` |
| I11 | 帮会行锁下成员集合稳定：给本帮插 / 删成员行的事务都先锁 guild 行 | `:2243-2245`；`lockAllMembers` 点不到行即内部错误（`:2374-2381`） |

### 1.4 4.5 / 4.6 的表（4.4 不建；只登记形状与锁序位置）

| 表 | 锁序 | 主键 / 唯一键 / 索引 | 出处 |
|---|---|---|---|
| guild_player_op_seq（Q） | 5 | PK (player_id, stream) | `guild_db.proto:123-136` |
| guild_asset_op（O） | 6 | PK op_id；UK (player_id, stream, stream_epoch, seq)；idx_0 (status, next_attempt_ms)、idx_1 (guild_id, op_id)、idx_2 (player_id, stream, stream_epoch, status, seq) | `:138-180` |
| guild_daily_counter（C） | 7 | PK (player_id, counter_kind, ref_id, period_key)；idx_0 (period_key) | `:182-197` |
| guild_activity_progress（P） | 8 | PK (guild_id, activity_id, period_key)，没有二级索引 | `:211-228` |

另有三个枚举 `GuildAssetOpStatus`、`GuildAssetOpKind`、`GuildDailyCounterKind`（`guild_db.proto:98-121`）。4.4 用不上，但 4.4 的退帮、踢人、
解散事务**必须留出调用它们的位置**，见 §6.3。

### 1.5 锁序与 SQL 纪律（`guild_manage_repo.go:9-57`；`tables.go:15-21`）

1. **表间全序** G → S → M → A →（Q → O → C → P）：不许在持有靠后表的行锁之后回头锁靠前的表。登记过两个例外，各自带不成环论证：
   - **建帮的新 G 行放在最后一条写**（`guild_repo.go:308-327`）：新 guild_id 提交前别人无从得知，能等它的只有同名插入者，而同名插入者也排在 S(0) 之后。
   - **审批通过在锁住 A(G,p) 之后才插 M(G,p)**（`guild_manage_repo.go:2065-2081`）。
2. **(a)** 同表多行按主键升序**逐行**取锁。
3. **(b)** 锁定读、UPDATE、DELETE 只做**完整主键等值**的点操作。按二级条件找行时，先普通读（RC 语句级快照，不加锁）取候选主键，排序后逐行点锁 / 点删；
   WHERE 带原条件作提交点复核，影响 0 行即跳过。guild_member 的锁定 SELECT / UPDATE 一律 `FORCE INDEX (PRIMARY)`（完整主键同时钉死了 uk，
   不强制时优化器可能走 uk，变成「先二级后主键」，`:18-23`、`:723-738`）；单表 DELETE 不收索引提示，靠 EXPLAIN 回归钉住它走 PRIMARY（`:739-745`）。
4. **(c)** 凡是**插入或删除 guild_member 行**的事务（建帮、审批通过、踢人、退帮、解散）都先持有该玩家的 S 行锁（`:24-26`）。
5. **(d) 全局插入守卫 S(0)**（`:27-45`、`:829-863`）：持有者只有三类——建帮、审批通过、补建状态行的短事务，都在事务内点锁、持到提交。
   位置：建帮 S(0)→S(p)；审批通过 G(G)→S(0)→S(p)；补建状态行 S(0)→插 S(p)。作用：串行化 `uk_guild` 与 `uk_guild_member` 的「查重插入者」，
   消掉两个查重插入者各持 S next-key、插入意向互相挡住的 1213；首插者回滚时不再有两个排队者继承间隙锁互等。哨兵行缺失 fail-closed，回内部错误，
   不在请求路径上补建（`:836-839`、`:857-863`）。其余事务（申请、撤回、拒绝、踢人、退帮、解散、任免、转让、经济）一概不取它。
6. **(e)** TiDB 规则（V2）：凡是带复核谓词的点删，之前先做一次完整主键 `FOR UPDATE` 点锁（撤回、申请事务内删本人过期行、申请后的惰性清理三处，
   `:46-55`）。MySQL 下锁集不变；Java 首批不上 TiDB，但建议照抄（语句同一套，回归测试可直接复用）。

### 1.6 事务基座（所有核心写共用）

- **唯一入口** `inTx(ctx, op, fn)` = `retryOnDeadlock(runTxOnce)`（`guild_manage_repo.go:142-144`）；B5 / B6 的仓储复用它（`:133-134`）。
- **隔离级别**：每次 `BeginTx` 显式 `LevelReadCommitted`（`:183-188`）；不用 RR 的理由是间隙锁在 TiDB 上不存在（`:136-137`）。
- **每次尝试的子预算**：`txBudgetFor(op)` 固定映射，1500 ms，解散 2500 ms，不许在调用点传数字（`:105-131`）；每次尝试各给一份完整子预算
  （`:147-151`）；fn 收到的是子预算 ctx（`:138-141`）。
- **子预算到期**：判据是「txCtx DeadlineExceeded 且父 ctx 的 deadline 还没到」→ 计 `guild_tx_budget_exceeded_total{op}`、回 `ErrWriteConflict`、
  **不做内部重试**；父 ctx 自己到期或被取消 → 原样返回 ctx 错误（`:153-181`）。
- **可重试**：1213、9007（TiDB 写冲突）、`errRetryTx`，按 `errors.As(*MySQLError)` 穿透包装取错误号（`:276-307`）。至多 3 次尝试，
  每次之间 10–50 ms 随机退避（`:240-266`）；用尽 → 记 ERROR、回 `ErrWriteConflict`（`:251-254`）；每判一次调钩子 `txDeadlockObserved`，
  生产实现计 `guild_tx_deadlock_total{op}`（`:268-274`）；退避期间 ctx 结束 → `ctx.Err()`（`:259-263`）。
- **1205 不重试**：计 `guild_tx_lock_wait_timeout_total{op}`，回 `ErrWriteConflict`（`:244-246`）。
- **COMMIT 结果分类** `classifyCommitErr`（`:203-234`）：① 可重试或 1205 → 原样返回（TiDB 的 9007 正是在 COMMIT 报出的）；② `ErrTxDone` /
  ctx 取消或超时 → 原样返回；③ 其余 → **结果不明**，记 ERROR，`fmt.Errorf("commit outcome unknown (%v): %w", err, ErrWriteConflict)`。
- **`errCommitThen{inner}`**：先 COMMIT 已做的写，再把 inner 当业务结果返回（实现了 `Unwrap`）；这次 COMMIT 失败就返回 commit 的错误
  （`:88-95`、`:190-198`；单测 `guild_manage_repo_test.go:361`）。用途：过期 / 跨区 / 1062 分支「删掉这条申请并提交，回 NotFound」。
- **结果变量约定**：fn 里累积的结果在闭包内声明，成功返回前才赋给外层，重跑不带出残留（`:236-239`；单测 `guild_manage_repo_test.go:433`）。
- **连接会话（DSN 改写）** `WithLockWaitTimeout`（`:309-352`；`servicecontext.go:75-87`）：强制 `innodb_lock_wait_timeout=1`、
  `transaction_isolation='READ-COMMITTED'`（值带单引号）、删 `tx_isolation`、`ClientFoundRows=false`。理由：RowsAffected 必须是「实际改动行数」，
  `execExactlyOneRow`、申请 IODKU 自检、B5 / B6 的带上限 upsert 都依赖它（`:335-336`）；`config.Validate` 另外拒绝显式写了 `clientFoundRows=true`
  的配置（`config.go:286-292`）。解析失败的错误文案不含 DSN 原文（`:338`）。单测 `guild_manage_repo_test.go:453`。

### 1.7 状态行与全局插入守卫

**`ensurePlayerStateRows(ctx, op, now, ids...)`**（`guild_manage_repo.go:976-1029`）。调用时**本 goroutine 不得持有任何事务**：
1. id 排序去重；含 0 → 内部错误且不碰库（`:1000-1005`；单测 `guild_manage_repo_test.go:597`）。
2. 普通读 S3 挑出已存在的行（IN 按 100 分块，`:951-974`）；全在就返回（常态）。不对全部 id 直接 `INSERT IGNORE`：撞上已存在主键会取 S 锁，
   而解散会把全体成员的状态行锁到提交，这期间他们的请求会白等（`:982-984`）。
3. 有缺的：一个 RC 短事务（`inTx`，op 用调用方的）：`lockGlobalInsertGuard` → **守卫下再普通读一次** → 对仍缺的按升序 `INSERT IGNORE` → 提交（`:1014-1028`）。
- 错误语义：哨兵缺失 → 内部错误；1213 / 9007 用尽、1205、超子预算 → `ErrWriteConflict`（`:997-998`）。

**`EnsureGlobalInsertGuard(ctx, now)`**（`:865-949`），启动期调用：
1. 普通读，行已在就返回（不取任何锁）。
2. 缺行：拿一条专用连接执行 `SELECT GET_LOCK('mmorpg_guild_insert_guard_init:mmorpg_guild', 5)`（`:845`、`:849`、`:905-908`）；
   结果不是 1 → 复读一次，行已在算成功，否则 `errGlobalInsertGuardMissing` 拒启（`:909-915`）。
3. 同一连接上复读，仍缺就自动提交 `INSERT IGNORE (0, now)`，外包 `retryOnDeadlock(op=insert_guard)`；插完再读一次确认（`:918-936`）。
4. defer `SELECT RELEASE_LOCK(?)`，独立 2 s 超时，失败只记日志（`:852`、`:939-949`）。
- 单测：`guild_lock_order_mysql_test.go:1076`（幂等）、`:1119`（首插者回滚）、`:1232`（缺失时 fail-closed）、`:1293`（只有查重插入者取守卫）。

### 1.8 通用助手

- `lockGuildRow`：G1，无行 → `ErrGuildGone`（`:798-809`）。返回 `{level, leader_id, zone_id, max_members}`。
- `lockPlayerState`：S2，无行 → 内部错误（包 `errPlayerStateRowMissing`，`:811-827`）。
- `lockMemberRole`：M1，返回 `(role, found)`；RC 下没命中不加锁（`:1063-1074`）。
- `lockMemberPair(G, actor, target)`：按 player_id 升序两次 M1，**两行都锁完才判存在性**：操作者不在 → `ErrNotGuildMember`，其次目标不在 →
  `ErrTargetNotMember`；actor == target 时只锁一次，在 → `ErrTargetNotMember`、不在 → `ErrNotGuildMember`（`:1076-1121`）。
- `countMembers` / `countMembersByRole`：持 G 行锁时的普通计数（`:1123-1139`）。
- `txSnapshot`：最后一次写之后、提交之前用 tx 跑 `loadGuild`，读得到本事务自己的写；G 行不存在 → `ErrGuildGone`（`:1141-1152`）。
  这份快照同时用作响应体与推送收件人来源（`:461-467`）。
- `execExactlyOneRow`：RowsAffected ≠ 1 → 内部错误（`:1154-1170`）。
- 申请删除：`applicationKeysOfPlayers`（A8，按 100 分块，`:1200-1219`）、`applicationKeysOfGuild`（A9，`:1243-1255`）、
  `deleteApplicationRows`（按 (guild_id, player_id) 排序去重后逐行 A4，0 行跳过；解散把本帮申请与成员在别帮的申请合成一个列表整体排序，
  `:1257-1272`）、`deleteApplicationsOfPlayer(p, alsoHeld...)`（前置：已持 S(p)，`:1274-1284`）。

### 1.9 SQL 全目录（4.4 涉及的全部语句）

| # | 语句 | 锁 / 断言 | 用于 | 出处 |
|---|---|---|---|---|
| G1 | `SELECT level, leader_id, zone_id, max_members FROM guild WHERE guild_id = ? FOR UPDATE` | 事务内 X，const | 任免 / 踢人 / 转让 / 退帮 / 申请 / 审批 / 解散 / 公告 | `guild_manage_repo.go:706` |
| G2 | `DELETE FROM guild WHERE guild_id = ?` | 0 行 → ErrGuildGone；>1 → 内部错误 | 解散最后一条写 | `:709`、`:2330-2343` |
| G3 | `INSERT INTO guild (guild_id, name, name_norm, leader_id, level, announcement, create_time_ms, max_members, zone_id, score, funds) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0)` | 事务内最后一条写 | 建帮 | `guild_repo.go:374-378` |
| G4 | `UPDATE guild SET leader_id = ? WHERE guild_id = ?` | 恰好 1 行 | 转让 | `guild_manage_repo.go:1559-1560` |
| G5 | `UPDATE guild SET announcement = ? WHERE guild_id = ?` | 不检查 RowsAffected | 公告 | `:787`、`:2413` |
| G6 | `SELECT zone_id FROM guild WHERE guild_id = ? FOR UPDATE` | 事务内 | 改分 | `guild_repo.go:579-580` |
| G7 | `UPDATE guild SET score = ? WHERE guild_id = ?` | 读 RowsAffected 但不断言 | 改分 | `guild_repo.go:586-593` |
| G8 | `SELECT zone_id FROM guild WHERE guild_id = ?` | 自动提交普通读 | `authoritativeZoneID` / `readOperatorRole` | `guild_repo.go:760-770` |
| G9 | `SELECT guild_id, name, leader_id, level, COALESCE(announcement, ''), create_time_ms, max_members, zone_id, score, funds FROM guild WHERE guild_id = ?` | 普通读（db 或 tx） | `loadGuild` | `guild_repo.go:463-465` |
| G10 | `SELECT guild_id, zone_id, score FROM guild` | 全表普通读 | `RebuildRanks` | `guild_repo.go:630` |
| S1 | `INSERT IGNORE INTO guild_player_state (player_id, updated_ms) VALUES (?, ?)` | 守卫短事务内 / 启动期自动提交 | 补建状态行 / 哨兵行 | `guild_manage_repo.go:717` |
| S2 | `SELECT player_id FROM guild_player_state WHERE player_id = ? FOR UPDATE` | 事务内 | 玩家守卫、全局守卫（id=0） | `:718` |
| S3 | `SELECT player_id FROM guild_player_state WHERE player_id IN (…)` | 普通读，每块 100 | 查缺行 | `:720-721` |
| S4 | `SELECT GET_LOCK(?, ?)` / `SELECT RELEASE_LOCK(?)` | 会话级命名锁 | 启动期建哨兵行 | `:905`、`:946` |
| M1 | `SELECT role FROM guild_member FORCE INDEX (PRIMARY) WHERE guild_id = ? AND player_id = ? FOR UPDATE` | 事务内 const | 成员行唯一的锁定读 | `:727` |
| M2 | `SELECT COUNT(*) FROM guild_member WHERE guild_id = ?` | 事务内普通读 | 判满 | `:729` |
| M3 | `SELECT COUNT(*) FROM guild_member WHERE guild_id = ? AND role = ?` | 事务内普通读 | 长老上限 / 转让后断言帮主数 | `:730` |
| M4 | `SELECT role FROM guild_member WHERE guild_id = ? AND player_id = ?` | 自动提交普通读 | `MemberRole`（只读 RPC 授权、两个 G4 预读） | `:731`、`:2023-2032` |
| M5 | `SELECT guild_id FROM guild_member WHERE player_id = ?` | 普通读（经 uk） | 申请事务内判已入帮；`loadPlayerGuildFromMySQL` | `:732`；`guild_repo.go:500-512` |
| M6 | `SELECT player_id FROM guild_member WHERE guild_id = ? AND role IN (?, ?) ORDER BY player_id`（参数 1, 3） | 事务内普通读 | 审批人名单（推送收件人） | `:733`、`:1904-1923` |
| M7 | `INSERT INTO guild_member (guild_id, player_id, role, join_time_ms, last_active_ms, contribution_total, contribution_balance) VALUES (?, ?, ?, ?, ?, 0, 0)` | 事务内 | 建帮（role 3）/ 审批通过（role 0） | `:735-736`；`guild_repo.go:358-361` |
| M8 | `UPDATE guild_member FORCE INDEX (PRIMARY) SET role = ? WHERE guild_id = ? AND player_id = ?` | 任免恰好 1 行；转让两条不断言 | 任免 / 转让 | `:738` |
| M9 | `DELETE FROM guild_member WHERE guild_id = ? AND player_id = ?` | 恰好 1 行 | 踢人 / 退帮 / 解散 | `:745` |
| M10 | `SELECT player_id FROM guild_member WHERE guild_id = ? ORDER BY player_id` | 普通读 | 解散的事务外预读与事务内成员全集 | `economy_repo.go:111` |
| M11 | `SELECT player_id, role, join_time_ms, last_active_ms, contribution_total, contribution_balance FROM guild_member WHERE guild_id = ? ORDER BY player_id` | 普通读 | `loadGuild` | `guild_repo.go:478-480` |
| A1 | `SELECT expire_ms FROM guild_application WHERE guild_id = ? AND player_id = ? FOR UPDATE` | 事务内 const，不带复核条件 | 锁申请行；带复核点删之前的点锁 | `guild_manage_repo.go:751` |
| A2 | `INSERT INTO guild_application (guild_id, player_id, apply_ms, expire_ms) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE apply_ms = apply_ms` | RowsAffected 必须为 1 | 新申请 | `:752`、`:1770-1789` |
| A3 | `UPDATE guild_application SET apply_ms = ?, expire_ms = ? WHERE guild_id = ? AND player_id = ?` | **不断言**（同毫秒双击为 0 行） | 同帮重复申请刷新 | `:753`、`:1728-1741` |
| A4 | `DELETE FROM guild_application WHERE guild_id = ? AND player_id = ?` | 0 行跳过 | 拒绝 / 过期 / 跨区 / 1062 / I2 / I3 | `:754` |
| A5 | `DELETE … WHERE guild_id = ? AND player_id = ? AND expire_ms <= ?` | 前面先做 A1 | 删过期行（带复核） | `:756` |
| A6 | `DELETE … WHERE guild_id = ? AND player_id = ? AND expire_ms > ?` | 前面先做 A1 | 撤回 | `:758` |
| A7 | `SELECT 1 FROM guild_application WHERE guild_id = ? AND player_id = ?` | 自动提交普通读 | 审批通过前的预读（G4） | `:761` |
| A8 | `SELECT guild_id, player_id FROM guild_application WHERE player_id IN (…)` | 普通读，每块 100 | I2 / I3 候选 | `:764-765` |
| A9 | `SELECT player_id FROM guild_application WHERE guild_id = ? ORDER BY player_id` | 普通读 | 解散时本帮申请候选 | `:766` |
| A10 | `SELECT guild_id FROM guild_application WHERE player_id = ? AND expire_ms <= ? ORDER BY guild_id` | 普通读 | 申请事务内本人过期行候选 | `:767` |
| A11 | `SELECT player_id FROM guild_application WHERE guild_id = ? AND expire_ms <= ? ORDER BY player_id LIMIT ?`（10） | 自动提交普通读 | 提交后清本帮过期行候选 | `:769`、`:1857` |
| A12 | `SELECT COUNT(*) FROM guild_application WHERE player_id = ? AND expire_ms > ?` | 事务内普通读 | 每人待审上限 | `:772` |
| A13 | `SELECT COUNT(*) FROM guild_application a LEFT JOIN guild_member m ON m.player_id = a.player_id WHERE a.guild_id = ? AND a.expire_ms > ? AND m.player_id IS NULL` | 普通读 | 帮会队列上限 / `pending_application_count` | `:776-778` |
| A14 | `SELECT guild_id, apply_ms, expire_ms FROM guild_application WHERE player_id = ? AND expire_ms > ? ORDER BY apply_ms DESC, guild_id ASC LIMIT ?` | 自动提交 | `ListMyApplications`（limit 10） | `:780-781` |
| A15 | `SELECT a.player_id, a.apply_ms, a.expire_ms FROM guild_application a LEFT JOIN guild_member m ON m.player_id = a.player_id WHERE a.guild_id = ? AND a.expire_ms > ? AND m.player_id IS NULL ORDER BY a.apply_ms ASC, a.player_id ASC LIMIT ?` | 自动提交 | `ListApplicants`（limit = MaxPerGuild） | `:782-785` |
| V1 | `SELECT VERSION()` | — | 启动期版本检查 | `server_version.go:25` |
| O1–O3 | 提前截止的候选普通读、`SELECT op_id … FOR UPDATE` 点锁、`UPDATE guild_asset_op SET deadline_ms=?, next_attempt_ms=LEAST(next_attempt_ms,?), updated_ms=? WHERE op_id=? AND status=? AND deadline_ms > ?` | 事务内 | **4.5 钩子**：退帮 / 踢人 / 解散 | `economy_repo.go:129-142`、`:402-426` |
| P1–P2 | `SELECT activity_id, period_key FROM guild_activity_progress WHERE guild_id = ? ORDER BY …`；按完整主键 DELETE | 事务内 | **4.6 钩子**：解散 | `activity_repo.go:148-153`、`:550-575` |

**执行计划回归（Java 必须照搬）**
- `guild_lock_plan_mysql_test.go:55`：对 G1、G5、G2、S2（含 id=0）、M1、M8、M9、A1、A3、A4、A5、A6 跑 EXPLAIN，断言 `key=PRIMARY`、
  `key_len` 等于主键列字节和（G 8、S 8、M 16、A 16），SELECT 的 type 为 `const`、写语句为 `range` 或 `const`。
- `guild_lock_plan_mysql_test.go:137`：静态形状检查——M1 / M8 含 `guild_member FORCE INDEX (PRIMARY)`；点写含 `WHERE guild_id = ? AND player_id = ?`；
  A2 含 IODKU；A11 以 `LIMIT ?` 结尾；候选读与计数读里不许出现 `FOR UPDATE`、`FOR SHARE`、`LOCK IN SHARE MODE`。

### 1.10 Redis 键

基线用全局 Redis DB 2，不分 zone（`guild.yaml:13-17`）。

| 基线键 | 类型 / 值 | TTL | 写者 → 读者 | 出处 |
|---|---|---|---|---|
| `guild:v2:{guild_id}` | string：`GuildData` 的 JSON | `PX Cache.DefaultTTL`（30 min） | 回填 Lua → `GetGuild` | `guild_repo.go:136-140`、`:213-226` |
| `guild:v2:cache_generation:{guild_id}` | string 计数（INCR） | **无**（永久） | 失效 Lua → 回源前读、回填 Lua 比较 | `:146-148`、`:162-166`、`:287-296` |
| `player_guild:v2:{player_id}` | string：十进制 guild_id，**0 也缓存** | `PX Cache.DefaultTTL` | 回填 Lua → `GetPlayerGuildID` | `:142-144`、`:258-269` |
| `player_guild:v2:cache_generation:{player_id}` | string 计数 | **无** | 同上 | `:150-152` |
| `guild_rank` | ZSET：member = 十进制 guild_id，score = float64(score) | 无 | 改分 / 重建 / 清榜 → 排行读 | `:154`、`:608-616` |
| `guild_rank:zone:{zone_id}` | ZSET，同上（只放 zone > 0 的帮） | 无 | 同上 | `:184-186` |
| `guild_rank:maintenance_lock` | string：uuid 令牌 | 5 min（SETNX） | `acquireRankLock` → 比较后删除 | `:156-160`、`:177-182`、`:786-816` |
| `guild_rank:rebuild:{uuid}:global`、`…:zone:{z}` | ZSET 临时键 | 无（defer DEL） | `RebuildRanks` → RENAME 成正式键 | `:636-665` |
| `guild:apply_push:{guild_id}:{player_id}` | string "1" | 60 s（SETNX） | `TryMarkApplyPush` | `guild_manage_repo.go:683-700` |
| `player:session:{id}`（外部，只读） | `PlayerSession` protobuf | player_locator 维护 | 在线判定 / 推送路由 | `online_status_resolver.go:17-24` |
| `merge:in_progress:{zone}`（外部，只读） | JSON（**从不解析**） | merge_zone 设定 | 闸门只做 EXISTS | `merge_fence.go:11-28`、`:62-74` |

**Lua 脚本**（`guild_repo.go:162-182`）：

```lua
-- 失效 invalidateVersionedCacheScript：KEYS[1]=代次键 KEYS[2]=数据键
redis.call("INCR", KEYS[1]); redis.call("DEL", KEYS[2]); return 1
-- 回填 fillVersionedCacheScript：ARGV[1]=读库前看到的代次 ARGV[2]=payload ARGV[3]=ttl 毫秒
local current = redis.call("GET", KEYS[1]); if not current then current = "0" end
if current ~= ARGV[1] then return 0 end
redis.call("SET", KEYS[2], ARGV[2], "PX", ARGV[3]); return 1
-- 释放榜维护锁 releaseRankLockScript
if redis.call("GET", KEYS[1]) == ARGV[1] then return redis.call("DEL", KEYS[1]) end; return 0
```

读代次时键缺失补成 `"0"`（`guild_repo.go:287-296`）。Java 的键空间与脚本见 §7.7、§7.8、§5.9。

### 1.11 缓存一致性

**内存 / 缓存形态 `GuildData` / `MemberData`**（`guild_repo.go:86-114`）：JSON 字段 `guild_id, name, leader_id, level, announcement, create_time_ms,
max_members, zone_id, score, funds, members[{player_id, role, join_time_ms, last_active_ms, contribution_total, contribution_balance}]`；
`Online` 是 `json:"-"`，不入缓存。由 `loadGuild` 装配：先 G9 再 M11，成员按 player_id 升序；G 行不存在返回 `(nil, nil)`（`:460-498`）。

**读算法 `GetGuild(id)`**（`guild_repo.go:190-228`）：
1. GET 数据键：Redis 报错 → 错误；JSON 解不开 → 错误；命中返回（`:272-285`）。
2. 未命中：取 **`r.cacheLoadMu` 这把进程级全局互斥锁**（`:123`、`:198-200`）——注释说的「singleflight」实际只是这一把锁，本进程内**所有** guild 与
   player 的 miss 全部串行，持锁期间还要做 MySQL 与 Lua。
3. 持锁后再 GET 一次；GET 代次键（缺失补 `"0"`，报错 → 错误）；`loadGuildFromMySQL`；帮会不存在 → `(nil, nil)`，**不做负缓存**。
4. JSON 序列化后执行回填 Lua；**EVAL 报错 → 返回错误**（`:217-226`），与 friend 只记日志不同。

**`GetPlayerGuildID(pid)`**（`:230-270`）：同上，值是十进制；回源用 M5，没有成员行 → 0，**0 也缓存一个 TTL**（`:258`）。

**`RefreshPlayerGuildID`**：直接失效映射（失败返回错误）再走 `GetPlayerGuildID`（`:431-438`），只在内部 GetPlayerGuild 的坏路径上用（`guild_logic.go:402-419`）。

**`VerifyPlayerGuildID(pid, cached)`**（`guild_manage_repo.go:597-615`）：M5 直读 MySQL；与 cached 相同就不写 Redis（不制造无谓的代次翻转）；
不同 → `invalidateAfterCommit(verify_mapping, 0, pid)`，返回 MySQL 值。Redis 失败不影响返回值。

**`ResolvePlayerGuild(pid)`**（`:617-668`），客户端 GetPlayerGuild 与 4.5 / 4.6 前置专用：
1. 缓存映射为 0 → Verify 复核；仍为 0 → `(nil, nil)` 未入帮。
2. `GetGuild`；快照里有本人 → 返回。
3. 帮会不在或快照里没有本人 → 失效 guild(G)（op=verify_mapping）→ `Verify(pid, G)`：为 0 → 未入帮；否则**绕过缓存**直读 `loadGuildFromMySQL`：
   读回不存在 → 未入帮；快照里仍没有本人 → **内部错误**（fail-closed，不当成未入帮，`:664-666`）。
- 单测 `guild_manage_repo_test.go:1568`、`:1604`。

**正确性论证与失效时机**：读者先读代次 g、再读库；写者在**提交之后**原子执行 INCR + DEL。INCR 早于回填 → 回填被拒；晚于回填 → DEL 删掉旧回填
（`guild_repo.go:190-192`）。

**`invalidateAfterCommit(ctx, op, G, players...)`**（`guild_manage_repo.go:524-574`）：G 为 0 时只失效映射，players 里的 0 被忽略；
先用请求 ctx 同步失效一次，失败的键交给 `safego.Go("guild.cache_invalidate")` 后台协程：`context.Background` + 3 s 预算，按 100 / 400 / 1600 ms
退避重试，每轮只重试上一轮仍失败的键；全部用尽 → 记 ERROR、计 `guild_cache_invalidate_failed_total{op}`（`invalidateGaveUp` 钩子）。**永不返回错误**。
单测 `guild_manage_repo_test.go:508`。

**失效矩阵**

| 写 | 失效的键 | 出处 |
|---|---|---|
| create | guild(G)、mapping(leader) | `guild_repo.go:388` |
| set_role（Changed 时） | guild(G) | `guild_manage_repo.go:1368-1371` |
| kick | guild(G)、mapping(target) | `:1448` |
| transfer | guild(G) | `:1589` |
| leave | guild(G)、mapping(p) | `:1654` |
| apply / cancel / review 拒绝 / review 的 errCommitThen 分支 | **无**（`pending_application_count` 不进缓存） | `:1804`、`:2220` |
| review 通过 | guild(G)、mapping(applicant) | `:2221-2223` |
| disband | guild(G)、全体成员 mapping | `:2351` |
| announcement | guild(G)（无条件，写同样的文本也失效） | `:2427` |
| score | guild(G)：**直接调 `invalidateGuildCache`，失败会返回错误**（不走 invalidateAfterCommit） | `guild_repo.go:604-606` |
| verify_mapping | mapping(p)（与 cached 不同时）；`ResolvePlayerGuild` 坏路径还失效 guild(G) | `guild_manage_repo.go:613`、`:648` |

**logic 的映射自愈**：`ErrGuildGone` / `ErrGuildZoneMismatch` / `ErrNotGuildMember` → `verifyMapping(actor, cachedGuildID)`；`ErrPlayerAlreadyInGuild`
→ `verifyMapping(actor, 0)`；失败只记日志（`guild_manage_logic.go:255-305`）。

**申请推送冷却 `TryMarkApplyPush(G, p)`**（`guild_manage_repo.go:679-700`）：`SET guild:apply_push:{G}:{p} 1 NX EX 60`；拿到键 → 推；
键已存在或 **Redis 报错 → 不推**（打 Info）。只对新插入的申请调用（`guild_manage_logic.go:501`），装配见 `guild.go:350`。单测 `guild_manage_repo_test.go:570`。

### 1.12 guild_id 发号

- **策略**（`guild_id_minter.go:71-101`）：号段（data_service `AllocateIdSegment`，`biz_tag="guild"`，`:15-16`）优先，可选回退 snowflake
  （`guild.go:232`）：`Enabled=false` 或 segment 为 nil → 纯 snowflake；`Enabled=true` 且 `FallbackToSnowflake=false`（默认）→ 号段失败即整体失败；
  `FallbackToSnowflake=true` → 记 ERROR 后回退。失败时**绝不返回 0 或自造 id**（`guild_logic.go:324-327`）。
- 开了号段却没配 DataServiceRpc → 拒启（`guild_id_minter.go:42-47`）；启动期预热第一段，5 s，失败只告警（`:18-20`、`:103-118`）；
  号段值域 [1, 2^55) 与存量 snowflake 号（约 6.7e16 起）不相交（`guild.yaml:96-97`）。
- **logic**：发号排在名字校验、归属区、闸门、读表、缓存预检**之后**，事务**之前**，避免白烧号（`guild_logic.go:211-245`）；
  minter 为 nil 或返回错误 → **14008**（`:322-340`）；撞名时这次发出的号作废（`:268`）。
- 单测 `guild_id_minter_test.go`、`guild_id_mint_test.go:24`。Java 落地见 §7.9（D8）。

### 1.13 归属区与合服闸门（数据源）

**归属区**（`home_zone.go`）
- 接口 `HomeZoneLookup.HomeZone(ctx, pid) (uint32, error)`：0 = 没有映射（数据状态）；err = 故障（`:15-24`）。
- 实现 data_service `GetPlayerHomeZone`，单次 1500 ms（`:26-61`）；`NotFound`，或旧版 `Unknown` 且消息含 `"no home zone mapping"` → 0（`:30-33`、`:63-76`）。
- `homeZones` 为 nil（没配 DataServiceRpc）时客户端请求一律 Unavailable（`guild.go:330-337`；`guild_logic.go:151-155`）。单测 `home_zone_test.go:30`。

**合服闸门**（`merge_fence.go`）
- 契约：只读 data_service mapping Redis（DB 0）的 `merge:in_progress:{zone}`，只做 `EXISTS`，**键存在即封锁**，不解析值、不看 TTL（`:11-28`、`:61-74`）。
- 没配 `MergeMarkerRedis` → 构造结果为 nil；`guild.go` 显式转成 **nil 接口**（`guild.go:316-323`；`merge_fence.go:52-59`）。连不上不拒启，
  运行期读失败按 fail-closed 处理（`servicecontext.go:140-163`）。
- `MergeInProgress`：fence 为 nil 或 zone 为 0 → false；Redis 报错 → error（`merge_fence.go:62-74`）。
- 两处使用：事务外 `mergeFenceTip`（§3.1）与事务内 `economyFence`（解散，§3.6）。

---

## 2 规则与权限

### 2.1 role 编码与 Rank

- 持久化编码：`RoleMember=0`、`RoleOfficer=1`、`RoleLeader=3`，2 是刻意跳过的空号（`constants.go:9-13`）。
- 档位：`Rank(0)=RankMember(1)`、`Rank(1)=RankOfficer(2)`、`Rank(3)=RankLeader(3)`，**其余（含 2）= RankNone(0)**（`constants.go:15-41`）。
  权限一律比 Rank、不比 role 原值：`role >= RoleOfficer` 会把未知编码一起放进来（`guild_manage_repo.go:430-431`）。
- `AssignableRole(role)`：只有 0 或 1（`constants.go:43-45`）。帮主只能经转让产生。

### 2.2 权限矩阵（判定一律在事务内，对锁住的 MySQL 行做）

| 操作 | 帮主 | 长老 | 成员 | 未知编码 | 判定出处 |
|---|---|---|---|---|---|
| 任免（只能设 0 / 1） | ✓（目标不能是帮主） | 14016 | 14016 | 14016 | `canAssignRole`：Rank == Leader（`guild_manage_repo.go:433`）；目标是帮主 → ErrRankTooLow（`:1323-1326`） |
| 转让 | ✓（还要求 `guild.leader_id == 自己`，否则 Internal） | 14016 | 14016 | 14016 | `canTransferLeader`（`:435-437`）；`:1531-1540` |
| 踢人 | 可踢长老与成员 | 只能踢成员 | 14016 | 14016；未知编码的目标也不可被踢 | `canKick`：`a ≥ Officer && t ≠ None && a > t`（`:443-448`） |
| 改公告 | ✓ | ✓ | 14006 | 14006 | `canSetAnnouncement`：Rank ≥ Officer（`guild_repo.go:402-407`）；不在该帮也回 14006（`guild_manage_repo.go:2401-2411`） |
| 列待审 / 审批 | ✓ | ✓ | 14016 | 14016 | `canReviewApplications`：Rank ≥ Officer（`:439-441`）；列待审用非锁定读 `MemberRole`（`guild_manage_logic.go:630-645`） |
| 解散 | 只看 `guild.leader_id == actor`（不看 role） | 14005 | 14005 | 14005 | `guild_manage_repo.go:2282-2284` → 调用点映射成 14005（`guild_logic.go:494-498`） |
| 退帮 | **14004**（role 为帮主，**或** leader_id == 自己，任一即拒） | ✓ | ✓ | ✓ | `guild_manage_repo.go:1627-1630` |
| 看到 `pending_application_count` | ✓ | ✓ | 0 | 0 | `guildInfoFor` 读快照里的 role（`guild_manage_logic.go:772-788`）——只用于展示，不用于授权（`:790-791`） |

- 客户端本地有同一套 Rank / CanKick 判定，只用来收起按钮（`GuildClient.cs:13-19`、`:120-123`）；服务端仍必须自己校验。
- 两个不对称值得注意：转让先判 `canTransferLeader(role)` 再判 `leader_id`，所以「role 不是 3、但 leader_id 是我」回 14016，「role 是 3、但 leader_id 不是我」回 Internal；
  解散只看 leader_id，所以「role 是 3、但 leader_id 不是我」回 14005。两者都是双存储被破坏后的 fail-closed，不必特意统一。
- 未知编码（如脏数据 2）的成员：不能踢人，也不能被踢；可以退帮；帮主可以通过任免把他改成 0 / 1（`SetMemberRole` 只拒「目标是帮主」，`:1324`）。

### 2.3 纯函数（Java 写成 `rules/GuildRoles`）

| 函数 | 定义 | 出处 / 单测 |
|---|---|---|
| `canAssignRole(a)` | `Rank(a) == Leader` | `guild_manage_repo.go:433`；`guild_manage_repo_test.go:273` |
| `canTransferLeader(a)` | `Rank(a) == Leader` | `:435-437` |
| `canReviewApplications(a)` | `Rank(a) >= Officer` | `:439-441` |
| `canKick(a, t)` | `Rank(a) >= Officer && Rank(t) != None && Rank(a) > Rank(t)` | `:445-448`；`guild_manage_repo_test.go:251` |
| `demotedLeaderRole(officersAfterTarget, maxOfficers)` | `officersAfterTarget < maxOfficers ? 1 : 0`（宁可降成成员也不超编） | `:450-457`；`guild_manage_repo_test.go:286` |
| `canSetAnnouncement(r)` | `Rank(r) >= Officer` | `guild_repo.go:405-407`；`guild_repo_test.go:22` |
| `targetTip(actor, target)` | target=0 → 14014 `"target player id is zero"`；target=actor → 14015 `"cannot target self"` | `guild_manage_logic.go:335-343`；`guild_manage_logic_test.go:251` |

### 2.4 帮名规范化

**展示名 `normalizeGuildName(raw)`**（`guild_logic.go:175-191`），不通过一律 **14009**：
1. `strings.TrimSpace`（Go `unicode.IsSpace` 口径）去首尾空白；
2. 结果为空，或码点数 > 24 → 非法；
3. 任一码点 `unicode.IsControl` → 非法（Go 的 IsControl 只认 Latin-1 区的 C0 / DEL / C1：U+0000–U+001F、U+007F–U+009F）；
4. `GuildNameNorm(name)` 必须成功（`guild_repo.go:524-534`）：`ToLower(TrimSpace(NFKC(name)))` 非空且 ≤ 48 码点；
5. **存库的展示名是第 1 步之后的值，不做 NFKC**（`guild_logic.go:190`）。

**判重键 `name_norm`** = `strings.ToLower(strings.TrimSpace(norm.NFKC.String(display)))`（`guild_repo.go:529`）。`strings.ToLower` 是逐码点
`unicode.ToLower`（简单大小写映射，不做上下文规则）。`CreateGuild` 仓储里防御性重算一次（`guild_repo.go:337-341`）。

单测与用例：`"  青云门 "` → `"青云门"`；恰好 24 个汉字合法（`client_zone_test.go:144-151`）；`"  ABC "`、`"ＡＢＣ"`、`"Ab　"` 规范成同一个小写键；
空串与全空白拒绝；13 个 `㍿` 经 NFKC 展开成 52 码点而超长（`guild_repo_test.go:171-192`）；跨 zone 同名、NFKC / 大小写 / 尾空格都算重名
（`guild_repo_zone_test.go:110-151`）。

**Java 实现要点**（D14）：
- 去空白用 `com.game.common.text.GoSpaces.trim`（与 Go `unicode.IsSpace` 同义，`xm-common/.../text/GoSpaces.java:3-23`）；
- 控制字符用 `Character.isISOControl(int)`（集合恰为 U+0000–U+001F、U+007F–U+009F，与 Go `unicode.IsControl` 相同）；
- 计数按码点（`codePointCount`）；NFKC 用 `java.text.Normalizer.Form.NFKC`；
- 小写**逐码点** `Character.toLowerCase(int)`（简单映射，对齐 Go `unicode.ToLower`），**不能**用 `String.toLowerCase(Locale.ROOT)`：后者对 `İ`（U+0130）
  产出两个码点、对词尾 `Σ` 产出 `ς`，与 Go 不同；
- **不能复用** `PlayerStore.nameKey`：它是 `Normalizer.NFKC → strip() → toLowerCase(Locale.ROOT)`（`xm-player-store/.../PlayerStore.java:75-80`），
  空白集合与大小写规则都与 Go 不同。

### 2.5 公告

- 长度按 **UTF-8 字节数**：`len(announcement) > 600` → **14011**（`guild_logic.go:524-526`；`constants.go:200-203`）。
  这一步排在身份与归属区之前（`client_zone_test.go:153-161`）。
- **不 trim、不检查字符集与控制字符**；空串合法（等于清空公告）；proto3 string 已保证是合法 UTF-8。
- 客户端本地更严（trim 后 ≤ 200 字且 ≤ 600 字节，`GuildClient.cs:59-62`、`:425-430`），服务端按**未 trim 的原始字节**判。

### 2.6 配表读取与启动校验

**运行期现查**（`guild_manage_logic.go:37-96`），查不到一律 fail-closed：

| 助手 | 读什么 | 查不到时 |
|---|---|---|
| `applicationRulesFromTable()`（:54-64） | GuildRule[1]：`TTLMs = application_expire_hours × 3 600 000`、MaxPerPlayer、MaxPerGuild | 申请 / 列待审回 gRPC Internal `"GuildRule row 1 missing"`（:489-492、:647-650） |
| `officerCapFromTable(level)`（:69-75） | `GuildLevel[level].max_officers` | `ok=false` → 仓储回 `ErrGuildLevelConfigMissing` → Internal |
| `initialMaxMembers()`（:79-85） | `GuildLevel[1].max_members` | 建帮回 Internal `"GuildLevel row 1 missing"`（`guild_logic.go:217-223`） |
| `levelDisplay(level)`（:90-96） | 展示用 `max_officers`、`upgrade_cost_funds` | 留 0 并记 ERROR，不让读失败（`guild_logic.go:777-784`） |

**启动校验 `validateGuildTables(rule, levels)`**（`guild_manage_logic.go:117-196`），纯函数，失败拒启（`guild.go:95-97`）：
- GuildRule[1] 必须存在；`application_expire_hours ∈ [1, 720]`；每人上限 `∈ [1, 10]`；每帮上限 `∈ [1, 500]`（`:102-110`、`:128-142`）。
- GuildLevel 非空；按 id 排序后 id 从 1 连续（重复 id 同样被抓到）；`max_members ∈ [2, 100]`；`max_officers < max_members`；
  成员上限与长老上限都不随等级递减；**只有最后一行** `upgrade_cost_funds == 0`（`:144-195`）。
- B5 的 `ValidateEconomyTables` 先调它再校验第 5–6 列（`:125-126`）；4.4 的 Java 版**不得**校验第 7–10 列（第 8–10 列在 Java 契约里是 `reserved`，
  校验会让当前数据拒启）。单测 `guild_manage_logic_test.go:677`。

### 2.7 仓储哨兵 → 客户端答复（`mapWriteErr`，`guild_manage_logic.go:269-329`；整表单测 `guild_manage_logic_test.go:397`）

两个帮会参数的含义（`:275-280`）：`logGuildID` 只进日志；`cachedGuildID` 喂给映射自愈去比对——申请 / 撤回传 0（申请人按定义不在目标帮），
其余传操作者所在帮（公告传请求体 guild_id）。

| 哨兵（定义 `guild_repo.go:25-84`、`guild_manage_repo.go:86`） | 答复 | 原因串 | 附带动作 |
|---|---|---|---|
| nil | (nil, nil)，走成功分支 | — | — |
| ErrGuildGone、ErrGuildZoneMismatch | 14001 | `"guild not found"` | `verifyMapping(actor, cached)` |
| ErrNotGuildMember | 14002 | `"not a member of the guild"` | `verifyMapping(actor, cached)` |
| ErrTargetNotMember | 14014 | `"target is not a member"` | — |
| ErrRankTooLow | 14016（**解散在调用点先拦截成 14005**） | `"rank too low"` | — |
| ErrOfficerLimit | 14017 | `"officer limit reached"` | — |
| ErrLeaderCantLeave | 14004 | `"leader cannot leave, disband or transfer instead"` | — |
| ErrGuildFull | 14003 | `"guild is full"` | — |
| ErrPlayerAlreadyInGuild | 14000 | `"already in a guild"` | `verifyMapping(actor, 0)` |
| ErrApplicationNotFound | 14018 | `"application not found or expired"` | — |
| ErrApplicationLimit | 14019 | `"pending application limit reached"` | — |
| ErrApplicationQueueFull | 14020 | `"guild application queue is full"` | — |
| ErrZoneMerging | 14013 | `"zone merging"` | — |
| ErrWriteConflict | 14021 | `"guild write conflict"` | 记 **Info**（不是 Error） |
| ErrLeaderMismatch、ErrGuildLevelConfigMissing | **gRPC Internal** `"guild data or configuration is inconsistent"` | — | 记 ERROR |
| 其他（哨兵行缺失、状态行缺失、写入自检失败、SQL 故障、Redis 故障） | 原错误照回（故障） | — | — |

两处在调用点单独映射：建帮的 `ErrGuildNameTaken` → **14010**（`guild_logic.go:274-275`）；公告的 `ErrAnnouncementForbidden` → **14006**（`:544-546`）。

---

## 3 每个 RPC 的处理流程

所有方法在 `guild_server.go` 里都是零分支的薄委托（`:11-13`）。下文「前置」按代码的实际执行顺序列出，顺序即错误优先级；
「事务」是仓储方法内的执行顺序（`→` 表示严格先后，「pre」指事务外）。

### 3.0 入口外壳

**拦截器链**（执行顺序即切片顺序，`guild.go:454-489`）：
1. grpcstats（最外层，被热关停的请求也计入，`:458`）；
2. killswitch（etcd 规则命中即短路，账记 `killswitch_blocked_total{method}`，`:460-466`）；
3. session（会话解码 + 方法白名单；在 killswitch 之后、serverbase 之前，`:468-472`）；
4. serverbase in-band 定性（读响应体 tip，故障 / 拒绝 / 未知码分别计数，不改响应，`:474-482`）；
5. 整请求预算（`WithTimeout(ctx, 3500ms)`，最内层，`:484-498`）。
单测钉住链上必须有 killswitch 与 session 以及预算效果（`guild_test.go:62`、`:144`、`:174`、`:196`）。

**会话**（`session.go`）：键 `x-session-detail-bin`，值是 base64 的 `base.SessionDetails`（`:36`、`:119-131`）。
- 不带该键 = 内部调用，直接放行（`:100-104`）；路由服拒绝缺会话的客户端消息，客户端没法靠「不带会话」绕过（`:14-16`）。
- 带键但解不开（base64 / protobuf 失败、**`player_id == 0`**）→ `codes.Unauthenticated`，绝不降级成内部调用（`:7-9`、`:106-110`、`:128-130`；
  单测 `session_test.go:84`）。
- 方法不在白名单 → `codes.PermissionDenied`（`:111-114`）。
- `ClientPlayerID(ctx)` 只有会话非空且 `player_id ≠ 0` 才返回 ok（`:87-93`）。

### 3.1 公共前置（logic 层）

- **`callerOf(ctx, bodyPlayerID)`**（`guild_logic.go:134-148`）：有会话 → 身份 = 会话 player_id，`fromClient=true`（请求体非 0 且不同时打 ERROR）；
  无会话 → `caller{playerID: bodyPlayerID}`。
- **`viewerOf(who)`**：只有客户端来源才有「请求者视角」，内部调用视角为 0，拿不到待审数（`guild_manage_logic.go:204-212`）。
- **`clientZone(ctx, p)`**（`guild_logic.go:150-166`）依次：`homeZones == nil` → `codes.Unavailable "guild home zone lookup is not configured"`；
  查询出错 → 原错误照回（故障）；结果为 0 → **14012**（打 Info 提示运维补映射）；否则返回 zone。单测 `client_zone_test.go:76-121`。
- **`visibleIn(guild, zone)`**：zone 为 0 不过滤（内部调用），否则 `guild.zone_id == zone`；**别区的帮会与不存在的帮会同一答复**（`guild_logic.go:168-171`、`:360-363`）。
- **`mergeFenceTip(ctx, zone)`**（`guild_logic.go:293-320`）：fence 为 nil 或 zone 为 0 → 放行；读失败 → **14013** `"merge fence unreadable"`（ERROR，fail-closed）；
  键存在 → 14013 `"zone merging"`（Info）。单测 `merge_fence_test.go:34-90`。
- **`clientWrite(ctx)`**（`guild_manage_logic.go:214-232`）：无会话 → PermissionDenied `"guild management requires a client session"`；
  `clientZone` 出 tip 或错误 → 照回；返回 `(player, zone, mergeFenceTip(zone), nil)`。顺序钉死：归属区未知时**不发闸门查询**
  （`guild_manage_logic_test.go:231-244`）；闸门查的是**归属区**（`:197-210`）；闸门读失败也回 14013（`:215-227`）。
- **`operatorGuild(p)`**（`guild_manage_logic.go:234-253`）：缓存映射 `GetPlayerGuildID`（Redis 错误 → 故障）；读到 0 时**用 MySQL 复核**
  （`VerifyPlayerGuildID(p, 0)`），仍为 0 → **14002** `"not in any guild"`。复核的理由：映射连 0 也缓存 30 分钟，审批通过后失效失败会留下陈旧的 0。
- **`verifyMapping(p, cached)`**（`:255-267`）：调 `VerifyPlayerGuildID`，失败只记日志。
- **`leftGuildWhileResolving(p, cached, err)`**（`economy_logic.go:240-273`）：`ResolvePlayerGuild` 报错后以 M5 再复核一次：此刻不在任何帮 → 14002；
  仍在某帮，或复核本身失败 → nil（调用方原错误照回、按故障）。用途：复核与直读快照之间恰好被踢 / 退帮时，错误与「双存储矛盾」长得一样（`:245-250`）。
  核心 GetPlayerGuild 用它，4.5 / 4.6 的前置也用它。

### 3.2 CreateGuild（15）（`guild_logic.go:193-291`；仓储 `guild_repo.go:305-390`）

**前置**
1. `callerOf(ctx, req.PlayerId)`。
2. 帮名校验（§2.4）→ 14009。**先于任何外部查询**（`client_zone_test.go:123-142`）。
3. 确定 zone：客户端 → `clientZone`（Unavailable / 故障 / 14012），**覆盖请求体 zone_id**；内部 → `req.ZoneId`（`:199-209`；`client_zone_test.go:47-74`）。
4. 合服闸门 `mergeFenceTip(zone)` → 14013，放在发号之前（号段只进不退，`:211-215`；`merge_fence_test.go:34-66`）。
5. `GuildLevel[1].max_members`，缺行 → gRPC Internal（`:217-223`）。
6. 已在帮预检：缓存映射 > 0 → `VerifyPlayerGuildID(p, cached)`，复核仍 > 0 → **14000**；缓存或复核出错 → 故障（`:225-239`）。缓存说 0 时不复核，交给事务判。
7. `now = nowMs()`；发号 `mintGuildID` → 失败回 **14008**（`:241-245`、`:322-340`）。
8. 内存构造 GuildData：`level=1`、`create_time_ms=now`、`max_members`=第 5 步、zone=第 3 步、唯一成员 `{p, role=3, join=last_active=now}`，
   announcement 空串（`:247-263`）。

**仓储 `CreateGuild(guild)`**
- 断言恰好 1 名成员；`name_norm` 防御性重算，失败 → 内部错误（`guild_repo.go:333-341`）。
- pre：`ensurePlayerStateRows(create, CreateTimeMs, leader)`（`:342-347`）。
- 事务（op=create，1500 ms）：S(0) → S(leader) → M7 插 M(G, leader, 3, now, now)，撞**任何** 1062 → `ErrPlayerAlreadyInGuild` →
  I2：A8 + 逐行 A4 → **最后**插 G3：只有撞 `uk_guild` → `ErrGuildNameTaken`，撞主键等其它 1062 → 内部错误 → COMMIT（`:348-387`）。
- 提交后：`invalidateAfterCommit(create, G, leader)`（`:388`）。

**结果映射**（`guild_logic.go:269-282`）

| 事务结果 | 回答 |
|---|---|
| ErrPlayerAlreadyInGuild | `verifyMapping(p, 0)` 后回 14000 |
| ErrGuildNameTaken | **14010**（帮名全服唯一、不分区；这次发的号作废） |
| ErrWriteConflict | 14021（Info） |
| 其他 | gRPC error（故障） |

**提交后**
- `UpdateGuildScore(guildID, zone, 0)` 把新帮放进全服榜与区榜；**失败只打 ERROR**（`:283-287`）。它在请求路径上取榜维护锁（最长等 5 s，受请求 ctx 约束，§5.2）。
- **不推送**：此刻帮里只有建帮者，他手上的响应就是最新状态（`:289`）。

**回包**：`{guild: guildInfoFor(内存构造的 guild, viewerOf(who))}`（`:290`）：不重读库；在线与名字实时查；帮主视角下待审数现算（正常为 0）。

单测：`client_zone_test.go:47`、`:63`、`:123`；`merge_fence_test.go:34`、`:53`；`guild_id_mint_test.go:24`；`guild_manage_repo_test.go:1347`（删掉建帮者自己的申请）；
`guild_repo_zone_test.go:110-151`（跨 zone 同名、规范化撞名、失败时成员行不残留）；锁序 `guild_lock_order_mysql_test.go:407`、`:851`、`:902`、`:955`。
robot：guild_smoke 第 1、2、4 步。

### 3.3 GetGuild（60）（`guild_logic.go:342-365`）

1. `callerOf(ctx, 0)`；客户端 → `clientZone`（Unavailable / 故障 / 14012），得到可见区（`:343-354`）。
2. `repo.GetGuild(guild_id)`（缓存 + 代次，§1.11），出错 → 故障（`:356-359`）。
3. 帮会为 nil，或不在可见区 → **14001** `"guild not found"`（`:360-363`）。guild_id=0 同样落到这里。
4. 回包 `{guild: guildInfoFor(g, viewerOf(who))}`；查别人的帮时 viewer 不是成员，待审数为 0。
- 无闸门、无推送。单测 `client_zone_test.go:208-222`；`guild_logic_names_test.go:75`、`:98`、`:257`。客户端不调用 60（只有 robot 第 4 步）。

### 3.4 GetPlayerGuild（35）（`guild_logic.go:367-421`）

**客户端路径不查归属区、不按区过滤**（自己的帮永远看得见，`:367`）：
1. `repo.ResolvePlayerGuild(p)`（§1.11）。
2. 报错 → `leftGuildWhileResolving(p, 0, err)`：此刻不在帮 → 14002；否则原错误照回（故障，`:375-383`）。
3. 结果为 nil → **14002** `"not in any guild"`（`:384-386`）。
4. 成功 → `{guild: guildInfoFor(g, p)}`。

**内部路径**（`:390-420`，Java 没有）：缓存映射为 0 → 14002；`GetGuild` 为 nil → `RefreshPlayerGuildID`（失效映射后重读，绝不删成员行）；
重读为 0 → 14002；再读帮会仍为 nil → 14001；成功 → `guildInfoFor(g, 0)`。

### 3.5 LeaveGuild（29）（`guild_logic.go:423-466`；仓储 `guild_manage_repo.go:1593-1656`）

请求体 player_id 对客户端无效：robot 第 8 步伪造帮主 id 退帮，服务端必须按会话身份受理（`guild_smoke_scenario.go:440-463`）。

**前置**：客户端 → `clientZone` → `mergeFenceTip` → 14012 / 14013（`:427-438`；内部调用两者都不查）；`operatorGuild` → 14002 / 故障（`:440-443`）。

**仓储 `LeaveGuild(G, p, now)`**
- pre：`ensurePlayerStateRows(leave, now, p)`。
- 事务（op=leave）：G1（ErrGuildGone）→ S(p) → M1(G,p)（无行 → ErrNotGuildMember）→ `Rank(role)==Leader || leader_id==p` → ErrLeaderCantLeave →
  M9 恰好 1 行 → I3：清 p 的全部申请 → **[4.5 钩子]** `accelerateDonationDeadlines(G, [p], now)` → 快照（已不含 p）。
- 提交后：失效 guild(G)、mapping(p)（`:1654`）。

**结果映射**
- `ErrNotGuildMember` 或 `ErrGuildGone`（`guild_logic.go:446-459`）→ `VerifyPlayerGuildID(p, G)`：复核出错 → 故障；复核为 0 → **幂等成功**（空回包）；
  复核为别的帮 → **14000** `"guild membership changed, retry"`（ERROR）。**绝不**按缓存里的旧 guild_id 去删别帮的成员行（`:447-448`）。
- 其余走 `mapWriteErr(p, G, G)`：14004、14021 等（`:460-462`）。

**推送**：MEMBER_LEFT，`actor=target=p`，收件人 = 快照剩余全体（`:463-464`）。**回包** `{}`。
单测 `guild_manage_repo_test.go:863`、`:1492`；锁序 `guild_lock_order_mysql_test.go:353`、`:475`。robot 第 8 步、M9。

### 3.6 DisbandGuild（38）（`guild_logic.go:468-520`；仓储 `guild_manage_repo.go:2227-2384`）

**前置**：同退帮——客户端查归属区与事务外闸门，然后 `operatorGuild`（`:471-488`）。

**仓储 `DisbandGuild(G, actor, now, fence=l.economyFence)`**
- pre：M10 普通读成员 → `ensurePlayerStateRows(disband, now, 这些成员...)`（帮会不存在时读到空集，照常进事务拿 ErrGuildGone，`:2264-2272`）。
- 事务（op=disband，**2500 ms**）：
  1. G1（ErrGuildGone）；
  2. **`leader_id ≠ actor` → ErrRankTooLow**（不看 role）；
  3. **事务内闸门** `checkFence(fence, guild.zone_id)`：闸门返回的非哨兵错误也包成 `ErrZoneMerging`（`economy_repo.go:323-335`；`economyFence` 见
     `economy_logic.go:288-311`）；
  4. `res.ZoneID = guild.zone_id`——**清榜唯一可信的输入**（`:493-498`）；
  5. `lockAllMembers`：事务内 M10 → 逐个 S(m) 升序 → 逐个 M1(G,m) 升序；点不到行即内部错误（`:2355-2384`）；
  6. 逐个 M9，每个恰好 1 行。**删成员行排在删申请之前**（否则持着申请行去等 uk 上的 S next-key 会成环，`:2236-2242`）；
  7. A9（本帮申请）与 A8（成员名下，每块 100）合并、排序、去重后逐行 A4（I3）；
  8. **[4.5 钩子]** `accelerateDonationDeadlines(G, members, now)`；
  9. **[4.6 钩子]** `deleteGuildActivityProgress(G)`；
  10. G2：0 行 → ErrGuildGone；>1 → 内部错误。
- 提交后：失效 guild(G) 与全体成员 mapping（`:2351`）。返回 `DisbandResult{ZoneID, MemberIDs（升序）}`。

**结果映射**：**ErrRankTooLow → 14005** `"not guild leader"`（唯一不回 14016 的地方，`guild_logic.go:494-498`）；其余走 `mapWriteErr`：14001（并自愈映射）、14013、14021。

**提交后**
1. `RemoveGuildFromRank(G, res.ZoneID)`（§5.5），失败只打 ERROR，靠下次启动的 RebuildRanks 自愈（`:503-508`）。
2. 推送 DISBANDED：`target=0`，收件人 = 解散前全体成员除帮主；只提交过申请的人不通知（`:510-518`）。

**回包** `{}`；重放 → 14001。单测 `guild_manage_repo_test.go:1384`、`:1422`（闸门在事务内）；锁序 `guild_lock_order_mysql_test.go:475`、`:1164`；
`rank_zone_integration_test.go:147`。robot 第 0、10 步、M10b。

### 3.7 SetAnnouncement（39）（`guild_logic.go:522-556`；仓储 `guild_manage_repo.go:2386-2429`）

**guild_id 取自请求体**：事务要求操作者是该帮的长老或帮主，所以改不了别人的帮（`:540-543`）。

**前置**：`callerOf`；**最先**判原始字节长度 > 600 → 14011（§2.5）；客户端 → `clientZone` → 闸门（`:527-538`）。

**仓储 `UpdateAnnouncement(G, p, text)`**（op=announcement）：G1（ErrGuildGone）→ M1(G,p)（**无行 → ErrAnnouncementForbidden**）→
`Rank < Officer` → ErrAnnouncementForbidden → G5 → 快照。提交后**无条件**失效 guild(G)（写同样的文本也算一次成功，`:2391`）。

**结果映射**：ErrAnnouncementForbidden → **14006** `"no permission"`；其余 `mapWriteErr(p, G, G)`：帮会不存在 → 14001（并以请求体 guild_id 自愈），写冲突 → 14021。

**推送**：ANNOUNCEMENT_CHANGED，`target=0`，收件人 = 快照除操作者（`:553-554`）。
**回包**：`{guild: guildInfoFor(事务内快照, viewer)}`，提交后不回读缓存（`:551-552`）。
单测 `client_zone_test.go:153`；`guild_repo_test.go:38`（缓存里陈旧的长老身份不能授权）；`guild_manage_repo_test.go:1463`。robot 第 5、7 步。

### 3.8 SetGuildMemberRole（19）（`guild_manage_logic.go:369-401`；仓储 `guild_manage_repo.go:1288-1373`）

**前置**：`clientWrite` → `targetTip`（14014 / 14015）→ `AssignableRole(role)` 否则 **14006** `"role not assignable"`（设 3 与 2 都拒，`:382-386`）→ `operatorGuild`。

**仓储 `SetMemberRole(G, actor, target, role, officerCapFromTable)`**
- 入参断言 actor ≠ target、role 可分配、cap 函数非空，否则内部错误（`:1298-1306`）。
- 事务（op=set_role）：G1 → `lockMemberPair` → 操作者不是帮主 → ErrRankTooLow → 目标是帮主 → ErrRankTooLow →
  **目标已是该角色 → 快照，`Changed=false`，不写库**（`:1328-1336`）→ 设为长老时：等级行缺失 → ErrGuildLevelConfigMissing（Internal），
  M3(role=1) ≥ cap → ErrOfficerLimit（14017）；降为成员不查上限 → M8 恰好 1 行 → 快照，`Changed=true`。
- 提交后：只有 Changed 时失效 guild(G)（`:1368-1371`）。

**推送**：仅 `Changed` 时推 ROLE_CHANGED，`actor`=帮主、`target`=目标，收件人 = 快照除帮主（`:396-399`）。
**回包** `{guild: guildInfoFor(快照, actor)}`，幂等分支同样回快照。
单测 `guild_manage_repo_test.go:618`、`:656`、`:683`、`:713`、`:1735`。robot M5、M6。

### 3.9 KickGuildMember（217）（`guild_manage_logic.go:403-431`；仓储 `guild_manage_repo.go:1375-1464`）

**前置**：`clientWrite` → `targetTip` → `operatorGuild`（`:405-420`）。

**仓储 `KickMember(G, actor, target, now)`**
- pre-1（G4 `precheckKickTarget`，`:1452-1464`）：M4 普通读目标是不是本帮成员。是 → 继续；不是 → 按锁内同一优先级答复：G8 帮会不在 → ErrGuildGone；
  M4 操作者不在 → ErrNotGuildMember；否则 → ErrTargetNotMember。**不看操作者职位、不建状态行、不开事务**（防随机 id 刷出垃圾状态行，`:1391-1394`）。
- pre-2：`ensurePlayerStateRows(kick, now, target)`。
- 事务（op=kick）：G1 → S(target) → `lockMemberPair` → `!canKick` → ErrRankTooLow → M9 恰好 1 行（帮贡随行消失）→ I3：清目标的全部申请 →
  **[4.5 钩子]** `accelerateDonationDeadlines(G, [target], now)` → 快照。
- 提交后：失效 guild(G)、mapping(target)（`:1448`）。

**推送**：MEMBER_KICKED，`actor`=操作者、`target`=被踢者；收件人 = 快照（已不含被踢者）除操作者，**再加被踢者本人**（`:426-429`）。
**回包** `{guild: guildInfoFor(快照, actor)}`；重踢已离开的人 → 14014。
单测 `guild_manage_repo_test.go:735`、`:1492`；锁序 `guild_lock_order_mysql_test.go:475`、`:548`、`:734`。robot M6。

### 3.10 TransferGuildLeader（216）（`guild_manage_logic.go:433-457`；仓储 `guild_manage_repo.go:1504-1591`）

**前置**：`clientWrite` → `targetTip` → `operatorGuild`。

**仓储 `TransferLeader(G, actor, target, officerCapFromTable)`**（op=transfer，**不碰 S 表**）：
G1 → `lockMemberPair` → 操作者不是帮主 → ErrRankTooLow → `guild.leader_id ≠ actor` → ErrLeaderMismatch（Internal，ERROR）→
等级行缺失 → ErrGuildLevelConfigMissing → M3(role=1) 数长老，**目标原本是长老且 officers > 0 时减 1** → 原帮主新角色 `demotedLeaderRole(officers, max)` →
G4 恰好 1 行 → M8(target, 3)、M8(actor, oldRole)（两条不断言）→ **M3(role=3) 必须等于 1**，否则内部错误回滚 → 快照。提交后失效 guild(G)（`:1589`）。

**推送**：LEADER_TRANSFERRED，`actor`=原帮主、`target`=新帮主，收件人 = 快照除原帮主（`:455`）。
**回包** `{guild: guildInfoFor(快照, actor)}`（actor 此时是长老或成员，是长老才看得到待审数）。重放 → 14016。
单测 `guild_manage_repo_test.go:772`、`:798`、`:820`、`:839`。robot M7、M8。

### 3.11 ApplyJoinGuild（218）（`guild_manage_logic.go:459-505`；仓储 `guild_manage_repo.go:1658-1901`）

**前置**
1. `clientWrite`：拿到 actor 与归属区 zone。
2. `guild_id == 0` → **14001** `"guild id is zero"`（`:468-471`）。
3. 已在帮预检：缓存 > 0 → MySQL 复核，复核 > 0 → 14000（`:473-487`）。
4. `applicationRulesFromTable` 缺行 → Internal（`:489-492`）。

**仓储 `ApplyToGuild(G, p, requiredZone=zone, now, rules)`**
- rules 三字段任一为 0 → 内部错误（`:1679-1681`）；pre：`ensurePlayerStateRows(apply, now, p)`。
- 事务（op=apply），顺序即错误优先级：
  1. G1 → ErrGuildGone；
  2. `requiredZone ≠ 0 && guild.zone_id ≠ requiredZone` → **ErrGuildZoneMismatch**（→ 14001，不泄露「在别区」）；
  3. S(p)；
  4. M5 普通读，有行 → ErrPlayerAlreadyInGuild（非锁定读，竞态由 I1 与审批 1062 分支兜住，`:1702-1712`）；
  5. 清本人过期行：A10 候选 → 逐个 A1 点锁（不在跳过）→ A5（`:1808-1837`）；
  6. A1(G,p)；**已存在 → A3 刷新 `(now, now+TTL)`，不断言行数，`Inserted=false`，直接提交**——刷新排在判满与两个上限**之前**（`:1728-1741`）；
  7. M2 ≥ `guild.max_members` → ErrGuildFull；
  8. A12 ≥ MaxPerPlayer → ErrApplicationLimit（普通读计数，在 S(p) 下不会漏数，只可能偏大，`:1750-1761`）；
  9. A13 ≥ MaxPerGuild → ErrApplicationQueueFull；
  10. A2 IODKU，RowsAffected ≠ 1 → 记 ERROR、回 ErrWriteConflict（撞上活记录说明有人绕过了串行化，`:1770-1789`）；
  11. M6 取审批人名单 → `Inserted=true, ReviewerIDs`。
- 提交后：`purgeExpiredApplicationsOfGuild(G, now)` 尽力清本帮过期行：A11 至多 10 个候选，每个一个 RC 短事务（op=apply）：A1 → A5；任一失败记 Info 并停止本轮
  （`:1852-1901`）。**不失效任何缓存**（`:1804`）。

**结果映射**：`mapWriteErr(actor, G, 0)`（cached 传 0，`:494-497`）→ 14001 / 14000 / 14003 / 14019 / 14020 / 14021。

**推送**：**只有新建行**，且 `applyPushAllowed(G, actor)`（冷却键 SETNX 成功）时，推 APPLICATION_RECEIVED，`actor=target=申请人`，收件人 = `ReviewerIDs`
（`:500-503`）。同帮刷新、冷却中、撤回都不推。
**回包** `{}`。
单测 `guild_manage_repo_test.go:889`、`:924`、`:946`、`:981`、`:1010`、`:1040`、`:1077`、`:1102`、`:1697`；`guild_repo_zone_test.go:82-108`；
锁序 `guild_lock_order_mysql_test.go:228`、`:305`、`:629`、`:679`。robot 第 6 步、M1、M10a。

### 3.12 CancelGuildApplication（219）（`guild_manage_logic.go:507-530`；仓储 `guild_manage_repo.go:1925-1967`）

**前置**：`clientWrite`；`guild_id == 0` → **14018** `"guild id is zero"`（`:516-522`）。

**仓储 `CancelApplication(G, p, now)`**：一个 RC 短事务（op=cancel），不取任何守卫：A1（无行 → ErrApplicationNotFound，回滚）→ A6：
删到 1 行 → 成功提交；删到 0 行（行在但已过期）→ A5 → `errCommitThen{ErrApplicationNotFound}`。不失效缓存。

**结果映射**：`mapWriteErr(actor, G, 0)` → 14018 / 14021。**从不推送**。重复撤回 → 14018。单测 `guild_manage_repo_test.go:1124`。

### 3.13 ListMyGuildApplications（222）（`guild_manage_logic.go:532-614`）

读 RPC 要会话，**查归属区但不查闸门**（合服窗口里看一眼自己的申请是安全的，`:532-533`）：
1. 无会话 → PermissionDenied（`:535-538`）。
2. `clientZone` → Unavailable / 故障 / 14012（`:539-545`）。
3. 已在帮：缓存 > 0 → MySQL 复核，复核 > 0 → **成功并回空列表**（不带 tip，`:547-561`）。
4. `ListMyApplications(p, now, 10)`（A14）：未过期、`apply_ms DESC, guild_id ASC`、`LIMIT 10`（`guild_manage_repo.go:1969-1993`）。
5. `myApplicationViews`（`:580-614`）：每行一次 `GetGuild`，**任一行出错 → 整体故障**；帮会为 nil 或不在可见区 → 跳过；字段见 §0.3；
   `member_count = len(members)`；`leader_name` 循环后**一次**批量查。
单测 `guild_logic_names_test.go:176`。robot 第 0、6 步、M1、M4、M10b。

### 3.14 ListGuildApplications（221）（`guild_manage_logic.go:616-682`）

**不查归属区、不查闸门**：查申请人的区是每人一次 data_service 往返，跨区残留行留给审批时在事务内复核（`:616-619`）。
1. 无会话 → PermissionDenied（`:621-624`）。
2. `operatorGuild` → 14002 / 故障（`:625-628`）。
3. `MemberRole(G, p)`（M4 非锁定读，`guild_manage_repo.go:2021-2032`）：不是成员 → `verifyMapping(p, G)` 后回 **14002** `"not a member of the guild"`；
   Rank < 长老 → **14016** `"officer rank required"`（`:630-645`）。
4. GuildRule 缺行 → Internal（`:647-650`）。
5. `ListApplicants(G, now, MaxPerGuild)`（A15，I1 过滤，`apply_ms ASC, player_id ASC`）。
6. `applicantViews`（`:663-682`）：一次在线批量读 + 一次批量取名，共用同一份 id 列表。
单测 `guild_logic_names_test.go:154`；`guild_manage_repo_test.go:1150`。robot 第 6 步、M2。

### 3.15 ReviewGuildApplication（223）（`guild_manage_logic.go:684-762`；仓储 `guild_manage_repo.go:2044-2225`）

**前置（顺序要紧）**
1. 无会话 → PermissionDenied（`:690-693`）。
2. `applicant == 0` → **14018** `"applicant player id is zero"`（`:695-697`）。
3. `applicant == 自己` → **14015** `"cannot review own application"`（`:698-700`）。
4. **只在通过、且 homeZones 已装配时**：`safego` 协程（点位 `guild.review.applicant_zone`）用**请求 ctx** 查申请人归属区，结果写进容量 1 的 channel
   （主流程提前返回时协程不阻塞，`:702-713`）。拒绝分支不查。
5. `clientWrite`：审批人的会话、归属区、闸门（`:715-721`）。
6. 通过时（`:723-744`）：channel 为 nil → Unavailable（第二道闸）；查询出错 → **原错误照回（故障）**；zone 为 0 → Info + **14018** `"applicant home zone unknown"`；
   ctx 先结束 → `status.FromContextError`。
7. `operatorGuild` → 14002 / 故障（`:746-749`）。

**仓储 `ReviewApplication(G, actor, applicant, approve, applicantZone, now)`**
- 入参断言：actor ≠ applicant；通过时 applicantZone > 0（`:2092-2097`）。
- pre（**只在通过时**）：`precheckApprovedApplication`（G4，`:1466-1486`）：A7 普通读申请行；不在 → 按优先级 G8 帮会不在 ErrGuildGone → M4 审批人不在
  ErrNotGuildMember → Rank < 长老 ErrRankTooLow → ErrApplicationNotFound（不建状态行、不取 S(0)）；在 → `ensurePlayerStateRows(review, now, applicant)`。
- 事务（op=review）：
  1. G1；
  2. **只在通过时**：S(0) → S(applicant)；
  3. M1(G, actor)：无行 → ErrNotGuildMember；Rank < 长老 → ErrRankTooLow；
  4. A1(G, applicant)：无行 → ErrApplicationNotFound；
  5. `expire_ms ≤ now` → A4 + `errCommitThen{NotFound}`；
  6. **拒绝**：A4 → 快照 → `Approved=false`；
  7. **通过**：`guild.zone_id ≠ applicantZone` → A4 + `errCommitThen{NotFound}`；M2 ≥ max_members → **ErrGuildFull（回滚，申请保留）**；
     M7 `(G, p, 0, now, now)` 撞 1062（申请人已入别帮）→ A4 + `errCommitThen{NotFound}`（InnoDB 只回滚那一条语句）；
     I2：A8 + 已持有的 (G,p) 一起按升序 A4 → 快照 → `Approved=true`。
- 通过分支里的过期 / 跨区 / 1062 子分支也已先取 S(0)、S(p)：基线有意保持简单，不为罕见分支多做一轮预判重跑（`:2059-2063`）。
- 提交后：**只有 Approved** 时失效 guild(G)、mapping(applicant)（`:2220-2223`）；errCommitThen 分支与拒绝不失效。

**结果映射**：`mapWriteErr(actor, G, G)` → 14001 / 14002 / 14016 / 14018 / 14003 / 14021。「已失效」的几种情况一律 14018，不细分（`constants.go:97-100`）。

**推送**：通过 → MEMBER_JOINED，`actor`=审批人、`target`=申请人，收件人 = 快照（含新成员）除审批人（`:754-756`）；拒绝 → APPLICATION_REJECTED，**只推申请人**（`:757-760`）。
**回包** `{guild: guildInfoFor(快照, actor)}`，通过与拒绝都带（`:761`）。
单测 `guild_manage_logic_test.go:330-375`（申请人没有区映射 → 14018 且只查 1 次；查询出错 → 故障；拒绝时不查申请人的区但仍查审批人的区）；
`guild_manage_repo_test.go:1187`、`:1225`、`:1245`、`:1266`、`:1283`、`:1301`、`:1326`、`:1649`；锁序 `guild_lock_order_mysql_test.go:166`、`:1164`。
robot 第 6 步、M3、M4。

### 3.16 排行三个 RPC（8 / 27 / 52）

见 §5.3、§5.6、§5.7。

### 3.17 NotifyGuildChanged（220）

推送消息号的占位方法，服务端恒返回 `Empty`、不进 logic（`guild_server.go:128-133`）。客户端上行在会话层就被 PermissionDenied（`session.go:111-114`），
路由服回信封 1003。

### 3.18 重放与幂等速查

| 操作 | 重放时的结果 |
|---|---|
| 建帮 | 已入帮 → 14000（预检或事务） |
| 退帮 | 事务说不在 / 帮没了 **且** MySQL 复核为 0 → 成功；缓存与 MySQL 都说不在帮 → `operatorGuild` 回 14002（不是成功） |
| 解散 | 14001 |
| 公告 | 同样的文本照写、照推、照失效 |
| 任免 | 目标已是该角色 → 成功、`Changed=false`、不推送、不失效 |
| 踢人 | 14014 |
| 转让 | 14016（操作者已不是帮主） |
| 申请 | 同帮 → 刷新有效期并成功，不推送；同一毫秒双击 A3 影响 0 行也算成功 |
| 撤回 | 14018 |
| 审批 | 14018（申请行已被删） |

---

## 4 推送、在线与名字

### 4.1 GuildInfo 装配（`guildInfoFor` = `toProtoGuild` + 待审数）

**`toProtoGuild(g)`**（`guild_logic.go:715-786`）
1. 收集成员 id（容量多留 1）。
2. **先**查在线 `onlineResolver.BatchResolve(memberIDs)`，**再**取名 `resolveNames(memberIDs + leaderID)`：两步串行（`:724-734`、`:744-745`）。
   帮主 id 单独追加，是为了在坏数据下（帮主不在成员表里）也能显示帮主名（`:719-723`）。
3. 帮会字段 `guild_id, name, leader_id, leader_name, level, announcement, create_time_ms, max_members, zone_id, funds`（`:747-758`）；score 不进 GuildInfo。
4. 成员按快照顺序（player_id 升序），每人 `player_id, name, role, join_time_ms, last_active_ms, contribution_total, contribution_balance, online`（`:760-774`）。
5. `officer_count` = role 为 1 的人数（`:759-775`）。
6. `max_officers`、`upgrade_cost_funds` 取 `GuildLevel[level]`，缺行留 0 并打 ERROR（`:777-784`）。

**`guildInfoFor(g, viewer)`**（`guild_manage_logic.go:766-788`）：viewer 为 0（内部调用），或 viewer 在快照里不存在 / Rank < 长老 → 待审数 0；
否则 `CountLiveApplications(G, now)`（A13，直读 MySQL，**不进缓存**，`guild_manage_repo.go:2034-2042`），出错 → 留 0、打 ERROR。

**写回包必须含操作者本人**：客户端 `Apply(GuildInfo)` 发现快照里没有本人就判无效（`GuildClient.cs:710-727`）。所有写回包都用事务内快照或含本人的构造值，满足这一点。

### 4.2 推送 220 的契约与语义（`push.go:5-15`、`:58-68`）

- 载荷只有 `GuildChangedS2C` 四个字段，**不下发快照**：客户端收到后自己拉 `GetPlayerGuild` / 申请列表（推送与响应的先后不可控，带状态的晚到推送会把客户端刷回旧数据）。
- 只在 MySQL 提交之后调用、至多一次；离线不推、gate 找不到会话即丢、Kafka 写失败不重试。
- **失败绝不影响 RPC 结果**；整批在 `safego` 协程里用独立 3 s 预算，**不继承请求 ctx**（`:35-41`、`:121-123`）。
- 收件人去重、丢弃 0（`uniqueNonZero`，`:209-230`）；**操作者一律不在收件人里**（`:274-277`；退帮时退帮者本就不在快照里）。
- 下发的消息号固定为 220（`push.go:101`）；`MessageContent.id = 0`，客户端按 notify 处理器处理（`GameClient.cs:1652-1672`）。

### 4.3 推送矩阵（代码出处；与设计稿 `docs/design/guild-phase2/02-management.md` 一致）

| 触发 | kind | actor | target | 收件人 | 出处 |
|---|---|---|---|---|---|
| 审批通过 | MEMBER_JOINED(1) | 审批人 | 申请人 | 快照全体（含新成员）除审批人 | `guild_manage_logic.go:754-756` |
| 退帮 | MEMBER_LEFT(2) | 退帮者 | 退帮者 | 快照剩余全体 | `guild_logic.go:463-464` |
| 踢人 | MEMBER_KICKED(3) | 操作者 | 被踢者 | 快照除操作者，**加被踢者** | `guild_manage_logic.go:426-429` |
| 任免（确有变化） | ROLE_CHANGED(4) | 帮主 | 目标 | 快照除帮主 | `:396-399` |
| 转让 | LEADER_TRANSFERRED(5) | 原帮主 | 新帮主 | 快照除原帮主 | `:455` |
| 解散 | DISBANDED(6) | 帮主 | **0** | 解散前全体成员除帮主（只提交过申请的人不通知） | `guild_logic.go:510-518` |
| 申请（新建行且冷却键拿到） | APPLICATION_RECEIVED(7) | 申请人 | 申请人 | 事务内读到的长老与帮主（player_id 升序） | `guild_manage_logic.go:500-503` |
| 审批拒绝 | APPLICATION_REJECTED(8) | 审批人 | 申请人 | 只有申请人 | `:757-760` |
| 公告 | ANNOUNCEMENT_CHANGED(11) | 操作者 | 0 | 快照除操作者 | `guild_logic.go:553-554` |
| 建帮 / 同帮刷新申请 / 冷却中 / 撤回 / 幂等任命 | — | | | 不推 | `guild_logic.go:289`；`guild_manage_logic.go:397`、`:500-501`、`:507` |
| 9 / 10 / 12 / 13 | 4.5 / 4.6 | — | — | — | `guild.proto:208-212` |

- `membersExcept(g, excluded...)`：只做集合减法，保持快照顺序，不去重、不丢 0（`push.go:294-315`）；去重丢 0 统一在 Notify 里做。
- `notify(kind, guildID, actor, target, recipients)`：guild_id 显式传参（解散后已经没有快照可取）；notifier 为 nil 只打 ERROR、不 panic（`push.go:273-292`）。

### 4.4 时序

提交 → 仓储内**同步**做第一次缓存失效（失败交给后台重试）→ 返回 logic → notify（`guild_manage_repo.go:532-574`；例如 `:1448`、`:1654`、`:2222`、`:2351`）。
- 申请没有失效步骤，但 notify 之前先跑「本帮过期申请清理」与「冷却 SETNX」（`guild_manage_repo.go:1803`；`guild_manage_logic.go:501`）。
- 解散在 notify 之前先清榜（`guild_logic.go:506`）。
- 与 friend 的「失效完成之后才推送」一致（`architecture.md:375`）：对方收到推送立即拉取时读到新值。

### 4.5 基线实现（`KafkaGuildNotifier`）

- **构造**：Kafka writer、gate 命令构造器、会话 Redis 任一为 nil → `NoopNotifier`（`push.go:87-103`；装配 `guild.go:349`；单测 `guild_manage_logic_test.go:486`）。
- **`Notify`**（`push.go:105-155`）：
  1. `uniqueNonZero` 去重，空就返回；
  2. 序列化放在起协程之前，失败计 `error`；
  3. `safego.Go("guild.push")` 协程里开 3 s ctx；
  4. `loadGateInfos`：MGET `player:session:{id}`。MGET 出错 → 整批计 `session_error`、不推；返回长度不符 → 整批失败（fail-closed，防张冠李戴）；
     会话状态不是 ONLINE、解不开、键不存在 → 计 `offline`；`gate_instance_id` 为空 → 打 ERROR、按离线计（`:157-207`）；
  5. 只有 1 人在线 → `PushToPlayer`，多人 → `BroadcastToPlayers`；出错 → 在线人数全计 `error`（偏保守），成功 → 计 `ok`。
- **Kafka writer**：`MaxAttempts=1`（不重试）、`WriteTimeout=3s`、`BatchTimeout=10ms`、`RequiredAcks=RequireOne`（`servicecontext.go:97-122`）。

**Java 映射**（D7）：`PlayerPushes.pushToPlayers(收件人, MessageContent{message_id = registry.requireId("GuildService","NotifyGuildChanged"),
serialized_message = GuildChangedS2C 字节, id = 0})`（`xm-discovery/.../presence/PlayerPushes.java:63-87`）：按 gate 分组发布到
`xm:gate-push:{zone}:{gate}`，gate 侧做玩家栅栏（`architecture.md:141-147`）。每个收件人的结局：`SENT` → ok、`OFFLINE` → offline、
`GATE_UNREACHABLE` → error；整个 stage 异常（在线目录读失败）→ 全体计 `session_error`；超时（`orTimeout(3 s)`）→ error。
写法照 friend 的 fire-and-forget（`xm-friend/.../service/FriendService.java`）。推送组件做成可复用的 `GuildPushes`：4.5 的 FUNDS_CHANGED /
DELIVERY_DONE 只推本人，要一个单收件人入口（`PlayerPushes.pushToPlayer`，`:56-61`）。

### 4.6 在线状态（`online_status_resolver.go`）

**`BatchResolve(ids)`**（`:62-99`），展示路径，fail-open：
- resolver 或 rdb 为 nil，或列表为空 → 空 map（全员离线）。
- 先去重，再 MGET `player:session:{id}`（只读 player_locator 维护的契约键，`:17-24`）；MGET 出错 → ERROR、空 map；返回长度与键数不符 → ERROR、空 map（`:80-90`）。
- **只有** `PlayerSession.state == SESSION_STATE_ONLINE` 才算在线；断线重连等待期、值解不开、键不存在都不算（`:26-48`）。
- **没有独立超时**：locator 的 Redis 客户端没开 ContextTimeoutEnabled，套接字读不看 ctx，只受默认 3 s ReadTimeout 约束；Redis 卡住时可能吃光整请求预算
  （`guild_logic.go:724-734`）。
- 使用点：`toProtoGuild` 的成员列表（`guild_logic.go:744`）、`applicantViews`（`guild_manage_logic.go:668`）。单测 `guild_manage_logic_test.go:553`、`:571`
  （推送与成员列表用同一判据）。
- `BatchResolveStrict` 与 800 ms 上限（`:101-233`）**只给活动用**（团圆人数），属于 4.6。

**Java 映射**（D6）：`PlayerPresenceDirectory.findAllAsync(ids)`——一次往返；条目损坏或与键不符按离线（`PlayerPresenceDirectory.java:165-178`、`:323-339`）。
等待加独立上限（建议 800 ms，`min(800, 剩余预算)`），超时或失败按全体离线并计指标。Java 的条目表示「此刻在游戏里」、断线即删，语义等同基线的 ONLINE
（`architecture.md:128-140`）。4.6 团圆要的严格逐人读已经现成：`findEachStrictAsync`（`:280`）。

### 4.7 展示名（`player_name_resolver.go`）

**纪律**：名字是展示数据，读取 fail-open；每个填充点一次请求只发一次批量查询（`:3-13`）。
- `PlayerNameResolver` 契约：永不返回 error；查不到的 id 不出现在结果里；实现自己处理 0 和重复 id（`:25-34`）。
- `DataServicePlayerNames.BatchResolve`（`:96-142`）：接收者或 client 为 nil → 空 map；`uniqueNonZero`；超过 500 个 id → ERROR、只查前 500；
  **请求 ctx 已结束 → Info、空 map、不计失败指标**；`WithTimeout(ctx, 800ms)` 调 `BatchGetPlayerName`；RPC 出错 → ERROR（只记条数）、
  计 `guild_player_name_lookup_failed_total`、空 map；结果里丢弃空名。
- `resolveNames`：未注入 resolver（没配 DataServiceRpc，`guild.go:330-337`）或入参为空 → nil map，取值得空串（`:150-155`）。
- 填充点：成员 + 帮主（一次）、榜单一页的帮主（一次，`guild_logic.go:705-709`）、单帮名次的帮主（一次，`:673-674`）、待审名单（一次）、本人申请列表的帮主（一次）。
- 单测 `player_name_resolver_test.go:74-222`；`guild_logic_names_test.go:75-283`。

**Java 映射**（D5）：名字取 `xm_java.player.name`，`PlayerProfiles.load(ids, deadline)`：每批 64 人，某批失败或预算用完 → ERROR、停在这一批、返回已读到的部分，
永不抛（`xm-common/.../player/PlayerProfiles.java:47-60`）。player 表 name 恒非空（`:18-22`）。帮主 id 合并进同一次批量；各填充点各自一次。

### 4.8 客户端处理（`GuildClient.cs`，Java 的推送与回包必须满足）

- 推送属于本帮（`guild_id == Info.GuildId`）：APPLICATION_RECEIVED 排队重拉待审列表，其余 kind 排队 `Refresh`（GetPlayerGuild）；DISBANDED 显示「帮会已被帮主解散。」，
  本人被踢显示「你已被请离帮会。」（`:186-218`）。
- 本人尚未入帮：只认 target 是自己的 MEMBER_JOINED（刷新）与 APPLICATION_REJECTED（重拉本人申请）。
- 由 `DrainQueued` 每帧最多发一个请求（`:221-237`）；单请求在途，Busy 期间不发新请求（`:785-809`）。
- 自动重拉：`AcceptWrite` 遇 14014 / 14002 排队 Refresh（`:776-782`）；审批遇 14018 / 14003 重拉申请列表（`:410-412`）；撤回遇 14018 重拉本人申请（`:361`）。
- 不认识的 in-band tip（如 1006）显示 `"帮会服务暂未完成请求（<id>），请稍后重试。"`，**不进隔离**（`:767`）；任何信封错误 → `RequiresReconnect`（`:800-807`）。
- 回包对应：先按请求 id 精确匹配，id 为 0 时按 message_id 先进先出，都没匹配上才交给 notify 处理器（`GameClient.cs:1652-1672`）。

---

## 5 排行

### 5.1 数据

- **权威分**：`guild.score`（int64），写法是绝对值写入（`guild_repo.go:586-587`）。基线**没有任何服务调用 UpdateGuildScore**，所有帮都是 0 分；
  建帮以 0 分入榜（`guild_logic.go:285-287`）。
- **读加速**：全服榜 `guild_rank`（ZSET，member = 十进制 guild_id，score = float64(score)），区榜 `guild_rank:zone:{z}`（只放 zone > 0 的帮，
  `guild_repo.go:154`、`:184-186`、`:608-616`）。
- **先写 MySQL、后写 ZSET**：ZSET 丢了可由 RebuildRanks 重建，反过来「只进 ZSET」的分数在 Redis 故障后就永久消失（`:560-565`）。

### 5.2 榜维护锁 `acquireRankLock`（`guild_repo.go:786-816`）

- `SET guild_rank:maintenance_lock <uuid> NX PX 5min`；每 20 ms 轮询一次，最多等 5 s，到时回错误；ctx 结束 → `ctx.Err()`；Redis 报错 → 错误。
- 释放：令牌相符才 DEL（Lua，`:176-182`），用 `context.Background()`，失败只记日志。
- **`UpdateGuildScore`、`RebuildRanks`、`RemoveGuildFromRank` 三者都在这把锁下进行，读不加锁**。锁的作用是让重建与增量更新互斥：
  重建读完 MySQL 之后、换榜之前若有增量 ZADD / ZREM，换榜会把它覆盖掉（丢更新或复活已解散的帮），直到下次重建。

### 5.3 UpdateGuildScore（8，只对内部开放）

**logic**（`guild_logic.go:560-592`）：客户端来源在会话层 PermissionDenied（信封 1003）。`GetGuild`（读缓存）出错 → 故障；为 nil → 14001；
`req.ZoneId > 0` 且与帮会自己的 zone 不同 → ERROR 后**忽略**，榜单一律用帮会自己的 zone（`:569-576`）。
**仓储 `UpdateGuildScore(G, zoneHint, score)`**（`guild_repo.go:566-618`）：
1. 取锁；
2. `inTx(op=score)`：G6（无行 → ErrGuildGone）→ G7（绝对值）；
3. 调用方 zone 与权威 zone 不一致 → ERROR，以权威值为准；
4. **失效 guild 缓存，失败返回错误**（`:604-606`）；
5. pipeline `ZADD guild_rank` 与 `ZADD guild_rank:zone:{权威 zone}`（zone > 0 才写），失败返回错误；
6. 释放锁。

**结果映射**（`guild_logic.go:581-591`）：ErrGuildGone → 14001；ErrWriteConflict → 14021；**其他（抢锁超时、缓存失效失败、ZADD 失败）→ gRPC 错误——此时 MySQL 已提交**。
建帮时直接调的是仓储方法，失败只记日志（`:283-287`）。

### 5.4 RebuildRanks（`guild_repo.go:620-698`）

1. 取锁；2. G10 全表扫；3. 新 uuid 作 token，ZADD 到 `guild_rank:rebuild:{token}:global` 与每个 zone 的 `guild_rank:rebuild:{token}:zone:{z}`（pipeline）；
4. defer `DEL` 全部临时键（`context.Background`）；5. `SCAN guild_rank:zone:* COUNT 1000` 找出旧区榜键；
6. 一个 MULTI/EXEC：`DEL guild_rank`、`DEL 所有旧区榜键`、有数据时 `RENAME 临时 global → guild_rank`、`RENAME 每个临时 zone → guild_rank:zone:{z}`。
- 能修复单项缺失、旧值、区榜丢失、解散后的幽灵条目；**每次启动都跑，失败 panic**（`guild.go:221-225`）。

### 5.5 RemoveGuildFromRank（`guild_repo.go:700-752`）

1. 取锁；2. G8 非锁定读权威 zone：读到就用它（与 hint 不一致记 ERROR）；`ErrGuildGone` 时用 hint（解散后 hint 必须是 `DisbandResult.ZoneID`，
   不能是缓存里可能陈旧 30 分钟的 zone）；其它错误返回；3. SCAN 出全部 `guild_rank:zone:*`，再加上实际 zone 的键；
4. pipeline：`ZREM guild_rank G` + 每个区榜键 `ZREM`。——扫全部区榜是给存量幽灵条目的一刀（`:710-714`）。
- 单测 `rank_zone_integration_test.go:120`、`:147`、`:182`。

### 5.6 GetGuildRank（27）（`guild_logic.go:594-634`；仓储 `guild_repo.go:818-858`）

1. `page_size == 0` → 20；`page == 0` → 1（`:597-605`）。
2. 客户端 → `clientZone`（14012 / 故障），然后 **zone 以归属区覆盖**，`page_size = min(page_size, 50)`；内部调用保留请求的 zone（0 = 全服榜）与原页长
   （`:606-616`；单测 `client_zone_test.go:186-206`）。
3. `GetGuildRankPage(zone, page, size)`：键 zone > 0 用区榜、否则全服榜；ZCARD 得 total；total、page、size 任一为 0 → `(nil, total)`；
   `start = (page−1)×size` 按 **uint64** 算，`start ≥ total` → 空页；`stop = min(start+size−1, total−1)`；`ZREVRANGE WITHSCORES`；
   `rank = start + i + 1`；member 用 `ParseUint` 解析（错误被忽略成 0）；分数 float64 → int64。出错 → 故障（`:618-621`）。单测 `rank_page_test.go:18-66`（uint32 / int64 溢出边界）。
4. `enrichRankEntries`（`:680-711`）：逐条 `GetGuild`；**出错只打 ERROR**，该条只带 `guild_id / score / rank`；帮会为 nil 同样处理（幽灵条目）；
   帮主名整页**一次**批量查。
5. 回包 `{entries, total_count = ZCARD, page, page_size}`，page 与 page_size 是**实际生效值**（`:628-633`）。客户端翻页器只用回包里的这三项
   （`GuildClient.cs:273` 固定页长 5；`D:\work\mmorpg-client\Assets\Scripts\UI\Ugui\Guild\GuildWindow.cs:466-468`），所以 Java 必须回显夹过之后的值。

### 5.7 GetGuildRankByGuild（52）（`guild_logic.go:636-678`；仓储 `guild_repo.go:860-888`）

1. 客户端 → `clientZone`，zone 以归属区覆盖（`:639-648`）。
2. `GetGuildRank(G, zone)`：`ZREVRANK`，nil → 名次 0；再 `ZSCORE`；名次 = 下标 + 1。出错 → 故障。
3. 名次为 0 → **14007** `"guild not ranked"`（`:654-656`）；别区的帮在本区榜上不存在、guild_id=0，都落到这里（`client_zone_test.go:224-236`）。
4. `GetGuild(entry.GuildID)`：出错 → 故障；为 nil → 条目只带 `id / score / rank`；否则补 `name / leader_id / level / member_count` 与**单个**帮主名（fail-open，`:658-675`）。
- 客户端不调用 52；robot 第 3、4、9、10 步用它（`guild_smoke_scenario.go:306-316`、`:477-484`）。

### 5.8 同分顺序与精度

- Redis `ZREVRANGE` 对同分成员按**成员字符串字典序降序**排；成员是十进制 guild_id，所以 `"9" > "10"`。基线全部 0 分，排序完全由这条规则决定
  （`docs/porting/inventory/guild.md:392`）。Java 的 member 必须是**无符号十进制串**，否则同分顺序与查找都对不上。
- 分数以 float64 存，超过 2^53 丢精度（`guild_repo.go:608`、`:853`、`:885`）。

### 5.9 Java 落地（D16）

- **键**（同一 hash tag `{rank}`，Cluster 下 RENAME / 多键 Lua 同槽）：`xm:guild:{rank}:all`、`xm:guild:{rank}:zone:<z>`、区索引 `xm:guild:{rank}:zones`
  （SET，代替 `SCAN guild_rank:zone:*`）、维护锁 `xm:guild:{rank}:lock`、临时键 `xm:guild:{rank}:tmp:<token>:all|zone:<z>`。
- **维护锁保留**（基线语义，§5.2）：`SET NX PX`，令牌比较后删除。差异两处：① 请求路径（建帮入榜、解散清榜）等锁的上限取 `min(剩余预算 − 预留, 5 s)`，
  拿不到就记日志放弃（与基线「失败只记日志」同结局），不让它吃掉回包装配的预算；② TTL 改短（建议 30 s）并由重建过程按 10 s 续期（比较后 PEXPIRE），
  持锁实例崩溃后最多卡 30 s，而不是 5 min（§9.1 第 6 条）。
  > 分区稿曾建议「维护锁只给重建用，单帮 ZADD / ZREM 不取锁」。核对后不采纳：重建读完 MySQL 后、换榜前发生的增量写会被换榜覆盖（丢更新、复活已解散帮），
  > 基线正是靠这把锁避免的。若将来要去掉增量路径的锁，必须同时引入「重建期间增量写记脏、换榜后按 MySQL 复读脏集」的协议。
- **单帮入榜 / 改分**：一段 Lua 同时 `ZADD all`、`ZADD zone:<z>`（z > 0）、`SADD zones <z>`。
- **清榜**：持锁时先 `SMEMBERS zones`，再一段 Lua（KEYS = all + 全部区榜键）逐个 `ZREM`。持锁期间不会有新区榜键出现，两步之间无竞态。
- **重建**（启动期，拒启语义同基线）：取锁 → G10 → 临时键批量 ZADD（**每个临时键带 PEXPIRE 10 min**，进程中途死亡不泄漏，修 §9.1 第 7 条）→
  `SMEMBERS zones` 得旧区 → 一段 Lua 原子换榜：`DEL all`、`DEL 旧区榜`、`RENAME tmp → 正式`、`DEL zones`、`SADD zones 新区`、对正式键 `PERSIST`。
- **读**：27 照基线 `ZCARD` + `ZREVRANGE WITHSCORES`（可合成一段只读 Lua，结果相同）；52 建议一段 Lua 同时取 `ZREVRANK` 与 `ZSCORE`
  （基线两步之间条目被删会让 ZSCORE 回 nil 被当成故障，§9.1 第 8 条；合并后该竞态答 14007）。
- 全服榜 `all` 照样维护（与基线同形），但 Java 4.4 没有读它的入口（D12）。

---

## 6 与 4.5 / 4.6 的边界（4.4 要留的钩子）

### 6.1 4.4 现在就建、但属于 4.5 / 4.6 的列与形状

- 表列：`guild.funds`、`guild.score`、`guild.level`、`guild.max_members`、`guild_member.contribution_total` / `contribution_balance` 在 4.4 一并建出，写 0 或初值；
  字段号与 mmorpg 相同，追加区不动（§1.2）。`guild.max_members` 由建帮写入、4.5 升级改写（`economy_repo.go:108-110` 的 `sqlUpgradeGuild` 同时改 level、funds、max_members），
  申请与审批读的是这一列（`guild_manage_repo.go:1747`、`:2183`）。
- 缓存快照：Java 的 `GuildSnapshot` 现在就要带 `score`、`funds`、成员两列帮贡，4.5 不改缓存格式；GuildInfo 装配已填 `funds` / 帮贡 / `max_officers` / `upgrade_cost_funds`
  （`guild_logic.go:747-784`），4.5 不改视图代码。
- 4.5 的升级、捐献终结改了 G 或 M 的这些列，必须失效 guild(G)；终结后还要失效成员映射并推 FUNDS_CHANGED / DELIVERY_DONE（`guild.go:355-357` 的 `OnAssetFinalized`）。

### 6.2 锁序位置

保留 G < S < M < A < Q(5) < O(6) < C(7) < P(8)，以及 B6b 的 9、10（`tables.go:15-74`）。4.5 / 4.6 在 `guild_tables.proto` 里**追加** message，
字段号照抄 `guild_db.proto:96-228`（含三个枚举、`uk_guild_asset_op` 与三组索引）。4.5 的经济事务规则：捐献 / 兑换**只普通读 guild 行**，
升级才 `FOR UPDATE`（`tables.go:66`；`economy_repo.go:108`）；成员行锁复用 M1（`guild_manage_repo.go:726`）。

### 6.3 事务钩子（4.4 实现为空操作）

| 钩子 | 位置 | 调用方 | 基线 |
|---|---|---|---|
| `MemberExitHooks.accelerateDonationDeadlines(tx, guildId, playerIds, now)`（O 位置） | 删成员行与删申请**之后**、快照或删 G **之前**；成员行 X 锁仍持有到提交，捐献预留被挡在外面 | 踢人（`[target]`）、退帮（`[p]`）、解散（全体成员） | `guild_manage_repo.go:1433-1435`、`:1639-1641`、`:2315-2320`；实现 `economy_repo.go:370-426`（候选普通读 → 逐行 `FOR UPDATE` 点锁 → 带复核点改；`now` 必须 > 0） |
| `deleteGuildActivityProgress(tx, guildId)`（P 位置） | 解散里提前截止**之后**、G2 **之前**（排到前面就是持着 P 回头取 A / O） | 只有解散 | `guild_manage_repo.go:2321-2326`；`activity_repo.go:532-561`；`tables.go:58-60` |

- 三个调用方都必须把 `now` 传进仓储（`guild_logic.go:444-445`、`:489-493`；`guild_manage_logic.go:421-422`）。
- 已登记风险：4.6 之后解散事务里的行数随活动进度增长（每帮每天至多 3 行，不清旧期），2500 ms 预算「远在预算之内」没有依据，上线前要测
  「满员帮 + N 天进度行」的解散 p99（`activity_repo.go:543-549`）。

### 6.4 4.4 落地、4.5 / 4.6 复用的共用件

- 事务基座 `inTx` / 重试分类（含 `errRetryTx`，`guild_manage_repo.go:276-295`）/ `errCommitThen` / `invalidateAfterCommit`：4.5 / 4.6 的仓储持有 `*GuildRepo` 复用（`:133-134`；
  `economy_repo.go:168-170`）。Java 做成 xm-guild 内的 `GuildTx` 与 `GuildCacheInvalidator` 公共组件。
- `FenceFunc` 与 `checkFence`（非哨兵错误包成 `ErrZoneMerging`，`economy_repo.go:159-160`、`:323-335`）、`economyFence`（`economy_logic.go:288-311`）：
  核心解散在用，4.5 的捐献 / 升级 / 商店与 4.6 的活动复用同一个回调。
- 成员关系解析：`operatorGuild`、`ResolvePlayerGuild`、`VerifyPlayerGuildID`、`leftGuildWhileResolving`、`memberOf`（`economy_logic.go:189-286`）——
  4.5 的 `economyCaller`（**不查归属区**，`:189-192`）与 4.6 的 `activityPrelude` 都用它们。Java 做成公开方法。
- `validateGuildTables(rule, levels)` 纯函数：4.5 的 `ValidateEconomyTables` 先调它（`guild_manage_logic.go:125-126`）。
- 完整的 op 集合（`guild_manage_repo.go:394-426`）与推送 kind 集合（`push.go:232-269`）一次定全。

### 6.5 派发器占位（4.4 期间的 10 个号）

- 基线：经济依赖未装配 → 五个经济 RPC 回 `codes.Unavailable`（信封 1003，`economy_logic.go:144-167`）；活动未装配 → 回 `kGuildActivityNotOpen` in-band
  （`guild_logic.go:55-58`）。但 `26ceb70ca` 两者都已装配（`guild.go:282-353`），客户端看到的是真实结果。
- **Java 4.4**：53 / 76 / 120 / 228 / 233 / 239–243 在派发器里登记路由，处理器统一回 **in-band 1006**（写进各自应答的 `error_message`，D13）。
  客户端进捐献 / 商店页会自动拉 120 / 228，in-band 1006 只显示 `"帮会服务暂未完成请求（1006），请稍后重试。"`（`GuildClient.cs:767`），
  不会让整个帮会模块进隔离；信封 1003 则会。活动号 Java 没有可回的活动 tip 码，客户端也不调用。4.5 / 4.6 只换处理器，不动白名单与启动校验。

### 6.6 4.5 的内部服务与发号

- `GuildInternal.ListAppliedAssetOpsSince`（`guild_internal.proto:49-51`；`guild_internal_server.go:1-17`）：data_service 回档闸的只读内部 RPC，不占消息号、
  不进客户端白名单，属于 4.5（Java 将来是类型化 Dubbo 接口）。
- op_id：基线只由号段 `biz_tag=guild_asset_op` 发、禁止回退 snowflake（`config.go:294-307`）；Java 是否复用 `NodeTypes.GUILD` 的雪花在 4.5 定。
- 4.5 发号失败沿用 14008（`economy_logic.go:385-398`），所以 in-band 故障码集合在 4.5 仍只有 14008。

### 6.7 4.6 前置

- mmorpg 先导出 GuildActivity 表与活动 tip 码，再跑 ContractSync（`PARITY.md:52`；`contract/SOURCE.properties:11-12`）；GuildRule 第 8–10 列在 Java 是 `reserved`
  （`guildrule_table.proto:53-59`）。
- 严格在线读 `PlayerPresenceDirectory.findEachStrictAsync`（`:280`）已现成；连接池必须 `useAffectedRows=true`（带上限 upsert 靠 RowsAffected 判达上限，`config.go:286-292`）；
  时钟要可注入（4.5 / 4.6 的游戏日 UTC+8 05:00 从它派生）。

---

## 7 Java 落地映射

### 7.1 进程与模块

| 组件 | Java | 依据 / 先例 |
|---|---|---|
| 进程 | `xm-guild`（Spring Boot + Dubbo Triple）。Dubbo 端口 **20886**，管理端口 **18110**。已占用：20881 login、20882 scene-manager、20883 friend、20884 chat、20885 team；18101–18109 | `tools/local/start-slice.sh:49-59`；`docs/porting/team-spec.md:1458` |
| Dubbo group | `DubboGroups.GUILD = "guild"` | `DubboGroups.java:7-22` |
| 提供方 | `GuildClientMessageService implements ClientMessageService`，`@DubboService(group=GUILD)`；`sessionClosed` / `abandonEnter` 直接回 Ack（无会话状态） | `xm-friend/.../FriendClientMessageService.java` |
| 鉴权 | 必须要求 `XM_DUBBO_SECRET`，缺失即拒绝启动 | `architecture.md:91-102` |
| 派发 | `dispatch/GuildDispatcher` + `GuildWorkerPool`（固定线程 + 有界队列 + AbortPolicy） | `FriendDispatcher.java:53-224` |
| 预算 | `com.game.common.deadline.Deadline`，受理时刻 + 3500 ms | `xm-common/.../deadline/Deadline.java:12-58` |
| 规则（纯函数） | `rules/GuildRoles`（§2.3）、`rules/GuildNames`（§2.4）、`rules/GuildTableRules`（§2.6） | — |
| 服务 | `service/GuildService`（建 / 查 / 退 / 解散 / 公告）、`service/GuildManageService`（任免 / 踢人 / 转让 / 申请 / 审批）、`service/GuildViews`（GuildInfo 装配）；领域对象 + `XxxService`，不用 ECS 风格 | 同 friend / team 的分层 |
| 存储 | `store/GuildStore` 接口 + `JdbcGuildStore`；`store/GuildTx`（事务基座）；表由 `xm/guild/guild_tables.proto` 经 xm-pbmysql 建 | §7.5、§7.6 |
| 缓存 | `cache/GuildCache`（快照 + 映射，版本化 cache-aside、按键单飞）、`cache/GuildCacheInvalidator`（提交后失效 + 后台重试） | `xm-friend/.../cache/FriendCache.java:25-140` |
| 排行 | `rank/GuildRanks`（Redisson + Lua，§5.9） | — |
| 在线 / 名字 / 归属区 | `PlayerPresenceDirectory.findAllAsync`；`PlayerProfiles.load` / `loadStrict` | §4.6、§4.7、§7.10 |
| 推送 | `push/GuildPushes`：包一层 `PlayerPushes` | §4.5 |
| 发号 | `id/GuildIds`：`Snowflake` + `NodeIdLease`，新增 `NodeTypes.GUILD = "guild"`，作用域 0 | §7.9 |
| 合服闸门 | `zone/MergeFence` 接口，4.4 只有 `NONE` 实现（恒放行） | `architecture.md:617-620` |
| 指标 | `metrics/GuildMetrics` | §8.2 |

### 7.2 gate 接入（照 friend / chat / team）

- `MessageRoutes.SERVICE_BACKENDS` 加 `"GuildService" → DubboGroups.GUILD`（`MessageRoutes.java:28-32`）。键是**服务裸名 `GuildService`**（`message_id.txt` 的前缀，
  如 `:9` 的 `8=GuildServiceUpdateGuildScore`）。8 与 220 也会路由过来；220 的应答类型是 Empty，`hasResponse=false`（`:51-55`）。
- `GateConfiguration` 加 `@DubboReference(group=GUILD, check=false, url="${xm.dubbo.guild-url:}", methods=@Method(name="handle", retries=0))`
  （照抄 team 的写法 `GateConfiguration.java:103-111`；写路径不幂等，**`handle` 必须不重试**），并放进 backends Map（`:138-140`）。
  `xm-gate/src/main/resources/application.yaml` 加 `guild-url: tri://127.0.0.1:20886`；`application-nacos.yaml` 置空。
- `start-slice.sh` / `stop-slice.sh` 的服务清单加 `"xm-guild 20886"`（`start-slice.sh:49-59`；`stop-slice.sh:6`），注释里的 `XM_DUBBO_SECRET` 读者名单补上 xm-guild（`start-slice.sh:6`）。
- 按会话、按域的在途队列是现成机制（`ClientDispatcher.java:325-350`）：同一会话的帮会请求串行；队列满 64 断开；身份取**入队时**的会话快照（`:352-364`）。
- 现状：gate 对 GuildService 的号走「未接入的后端域」分支，推 23 {1003}（`ClientDispatcher.java:313-319`）；接入后消失。
- 热关停：gate 按 `route.rpcPath()` = `/guildpb.GuildService/<Method>` 查规则，命中回信封 1003（`ClientDispatcher.java:248-256`），规则键与基线 etcd 键同名
  （`guild.yaml:147-148`；`architecture.md:406-424`）。

### 7.3 准入与错误映射（`GuildDispatcher`）

| 情形 | 基线客户端所见 | Java 返回 | 说明 |
|---|---|---|---|
| 16 个 4.4 C2S（15/19/27/29/35/38/39/52/60/216–219/221–223） | 应答体 | `ClientReply{body}`，`tip_id=0` | 业务拒绝在 body 的 `error_message` 里 |
| 上行 8、220 | PermissionDenied → 信封 1003 | `tip_id=1003`，计 `forbidden` | robot 第 9 步断言信封 1003 且分数不变（`guild_smoke_scenario.go:465-484`）；220 应答是 Empty，但 `tip≠0` 时 gate 照样回包（`ClientDispatcher.java:456-459`） |
| 会话 `player_id == 0` | Unauthenticated → 信封 1003 | `tip_id=1003`，计 `unauthenticated` | 先例 `FriendDispatcher.java:151-156`；**与 team 不同**（team 回 in-band 4001） |
| 请求体解析失败（含 proto3 非法 UTF-8） | gRPC 解码错误 → 信封 1003 | `tip_id=1003`，计 `bad_request`；**解析先于身份检查** | 先例 `FriendDispatcher.java:172-178`（同基线；不采纳 login 的 1014 惯例，见 N7） |
| 依赖故障：MySQL / Redis 抛 `DependencyException`、配表缺行、双存储矛盾、`ErrLeaderMismatch` / `ErrGuildLevelConfigMissing`、处理器异常 | gRPC error → 信封 1003 | **信封** `tip_id=1003`，记 ERROR，计 `internal_error` | **与 friend 不同**：friend 回 in-band 1003（`FriendDispatcher.java:184-191`）。guild 照基线回信封，客户端随之进隔离 |
| 工作队列满 / 排队已超预算 | 无直接对应（go-zero 侧会变成超时 → 信封 1003） | 信封 `tip_id=1003`，计 `overloaded` | D11 |
| 4.5 / 4.6 的 10 个号 | 真实结果 | in-band 1006（各自应答的 `error_message`），计 `unsupported` | D13 |
| 不认识的号 | — | 契约里没有 → 信封 1013；契约里有但不归 guild → 信封 1006 | `FriendDispatcher.java:206-214`；gate 只路由 GuildService 的号，正常走不到 |

- **14008** 是 in-band 回的，但指标计 `fault`（§8.2），对应基线 serverbase 的 in-band 故障定性。
- **启动校验**（照 `FriendDispatcher.java:114-126`）：28 个方法名都要在 `message_id.txt` 里；请求原型与处理器类型一致；每个 C2S 应答都有 `error_message` 字段；缺号即启动失败。
- tip 一律带基线的英文原因串（§0.4），`TipInfoMessage{id, parameters:[原因]}`；信封不带 parameters（与路由服 `rejected` 一致，`forwardlogic.go:225-232`）。

### 7.4 线程、预算、超时

- **线程**：Dubbo 线程只投递，处理在 `guild-worker` 有界池上（缺省 16 线程 / 1024 队列，同 friend，`xm-friend/src/main/resources/application.yaml:73-74`）；
  JDBC 与等待 Redis 都只在工作线程上做（`AGENTS.md` §3）。
- **整请求预算** 3500 ms（= 基线 `Timeout − 500`），启动时校验 ∈ [500, 3500] ms（照 `FriendProperties`），保证先于 gate 的 5000 ms Dubbo 超时结束
  （`xm-gate/src/main/resources/application.yaml:81`）。
- **事务子预算**：每次尝试 `min(请求剩余, 1500 ms)`，解散 2500 ms；代码常量，不开放配置。JDBC `setQueryTimeout` 只到秒级，子预算靠三样合起来实现：
  每条语句前检查子预算 Deadline（过了即中止、回滚，按 14021）、`setQueryTimeout(ceil(剩余秒))`、`innodb_lock_wait_timeout=1`。
- **归属区**：一次 player 表点查，查询超时取 `min(1500 ms, 剩余预算)`（同 team）。审批通过时审批人与申请人的归属区可以合成一条 `IN` 查询（§7.10）。
- **展示名**：`PlayerProfiles.load`，失败只记日志、返回已读到的部分。
- **在线读**：`findAllAsync` 加独立上限 800 ms（D6），超时按全体离线并计指标。
- **推送**：提交与同步失效之后发；`PlayerPushes` 异步，套 `orTimeout(3 s)`，不继承 Deadline（`push.go:35-41`）。
- **缓存失效**：先用请求 Deadline 同步失效一次；失败的键交给后台执行器按 100 / 400 / 1600 ms 重试，总预算 3 s，全部用尽才计指标（`guild_manage_repo.go:524-574`）。
- **排行锁**：请求路径等锁上限 `min(剩余预算 − 300 ms, 5 s)`，拿不到只记日志（§5.9）。

### 7.5 MySQL：自有表 proto + xm-pbmysql + 连接池

- **库**：`xm_java`（`AGENTS.md` §3），不建独占库（D2）。
- **连接池**：xm-guild 自己的 Druid 池，URL 照 friend（`xm-friend/src/main/resources/application.yaml:15-32`）但锁等待改成 1：
  `...&connectTimeout=3000&socketTimeout=4000&useAffectedRows=true&sessionVariables=transaction_isolation='READ-COMMITTED',sql_mode='STRICT_TRANS_TABLES',innodb_lock_wait_timeout=1`，
  Druid `default-transaction-isolation: 2`。
  - `useAffectedRows=true` 等价于 Go 的 `ClientFoundRows=false`（Connector/J 缺省设 CLIENT_FOUND_ROWS）：不带它，A2 的 IODKU 自检会把「撞上活记录」误判成插入成功，
    4.5 / 4.6 的带上限 upsert 也会多发。
  - `innodb_lock_wait_timeout=1`：基线值；friend 是 3。guild 的 1205 不重试、直接 14021，等锁更久只会吃掉预算。
- **表定义**：`xm-guild/src/main/proto/xm/guild/guild_tables.proto`，`package xm.guild`，`java_package com.game.guild.store.pb`，`option (proto2mysql.db) = true`；
  **不依赖**同步来的 `guild_db.proto`（契约产物，不许手改；先例 `xm-friend/src/main/proto/xm/friend/friend_tables.proto:1-5`）。四个 message：

  | message（锁序） | 表选项 | 字段（字段号同 `guild_db.proto`） |
  |---|---|---|
  | `GuildRow`（G） | `table_name="guild"`、`primary_key="guild_id"`、`unique_key="name_norm"`（→ `uk_guild`）、`index="zone_id;leader_id"`（→ `idx_guild_0`、`idx_guild_1`） | `uint64 guild_id=1; string name=2; uint64 leader_id=3; uint32 level=4; string announcement=5; uint64 create_time_ms=6; uint32 max_members=7; uint32 zone_id=8; int64 score=9; uint64 funds=10; string name_norm=11;` |
  | `GuildPlayerStateRow`（S） | `table_name="guild_player_state"`、`primary_key="player_id"` | `uint64 player_id=1; uint64 updated_ms=2;` |
  | `GuildMemberRow`（M） | `table_name="guild_member"`、`primary_key="guild_id,player_id"`、`unique_key="player_id"`（→ `uk_guild_member`） | `uint64 guild_id=1; uint64 player_id=2; uint32 role=3; uint64 join_time_ms=4; uint64 last_active_ms=5; uint64 contribution_total=6; uint64 contribution_balance=7;` |
  | `GuildApplicationRow`（A） | `table_name="guild_application"`、`primary_key="guild_id,player_id"`、`index="player_id;expire_ms"` | `uint64 guild_id=1; uint64 player_id=2; uint64 apply_ms=3; uint64 expire_ms=4;` |

  每张表都带 `tidb_nonclustered_pk=true`、`tidb_shard_row_id_bits=4`、`tidb_pre_split_regions=4`（选项名见 `xm-pbmysql/src/main/proto/proto2mysql/proto2mysql_option.proto:41-70`）。
  `name_norm` 不声明 `max_length`，保持缺省 191（与 Go DDL 逐字相同）。
- **建表**：`PbMysql.register(...)` 后在自动提交连接上 `syncAll`（只扩不缩，整轮持咨询锁，结构漂移启动失败，`xm-friend/.../FriendConfiguration.java:64-85`；`architecture.md` §7）。
  与基线不同：pbmysql 会**补建**缺失的普通索引，Go 会拒启（`guild.go:569-589`），登记为 D2 的一部分。
- **业务 SQL 全部手写**（§1.9 的语句逐字照搬），像 `JdbcFriendStore` 那样用原始 JDBC 连接控制事务。pbmysql 的 `findOneByPkForUpdate` 不带 `FORCE INDEX (PRIMARY)`，
  **不能用于 guild_member**。
- **无符号**：所有 id 绑定用 `PbMysql.uint64(..)` 或 `Long.toUnsignedString` / `BigInteger`；读回按无符号；排序（锁序的「按 player_id 升序」）用 `Long.compareUnsigned`，
  否则大 id 的取锁顺序与 MySQL 主键顺序相反，锁序推演失效。

### 7.6 事务骨架（`GuildTx`）

- 原始 JDBC 连接事务（`setAutoCommit(false)` / `commit` / `rollback`，同 `JdbcFriendStore.java:325-340`），**不用** `@Transactional`；业务拒绝用结果枚举返回并回滚，
  不用异常（避免框架把事务标成 rollback-only，也保证 Review 的 1062 子分支能在 InnoDB 只回滚那一条语句后继续删申请并提交）。
- 结果类型：`TxOutcome<T> = Ok(T) | Reject(GuildReject) | CommitThenReject(GuildReject)`；`GuildReject` 枚举对应 §2.7 的哨兵：
  `GUILD_GONE, ZONE_MISMATCH, NOT_MEMBER, TARGET_NOT_MEMBER, RANK_TOO_LOW, OFFICER_LIMIT, LEADER_CANT_LEAVE, GUILD_FULL, ALREADY_IN_GUILD, NAME_TAKEN,
  ANNOUNCEMENT_FORBIDDEN, APPLICATION_NOT_FOUND, APPLICATION_LIMIT, QUEUE_FULL, ZONE_MERGING, WRITE_CONFLICT`；`LEADER_MISMATCH`、`LEVEL_CONFIG_MISSING`
  映射为故障（信封 1003）。
- 重试与分类（照 §1.6；错误号沿 cause 链按 `SQLException.getErrorCode()` 取，不做文本匹配，同 `JdbcFriendStore.java:566-575`）：
  - 1213（及将来 TiDB 的 9007）→ 整体重跑，至多 3 次，每次之间随机 10–50 ms（剩余预算不够就不再重试）；用尽 → `WRITE_CONFLICT`；计 deadlock 指标。
  - 1205 → `WRITE_CONFLICT`，计 lock_wait 指标，不重试。
  - 子预算到期而请求还活着（语句前检查命中，或 `MySQLTimeoutException`）→ `WRITE_CONFLICT`，计 budget_exceeded；请求预算已用完 → `DependencyException`（信封 1003）。
  - `commit()` 抛错且不是上述可分类错误（典型 `CommunicationsException`）→ **结果不明**，记 ERROR，`WRITE_CONFLICT`。
  - 其余 SQL 错误 → `DependencyException`。
- 结果在闭包内累积，成功返回前才交出（每次重跑从零开始）。
- 提交后：先失效缓存（`GuildCacheInvalidator`，永不抛），再交回 service 发推送。

### 7.7 Redis 键（全部经 `RedisKeys` 生成，`xm:` 前缀）

| 用途 | 基线键 / 值 / TTL | Java 键（建议的 `RedisKeys` 方法） | 值 / TTL |
|---|---|---|---|
| 帮会快照 | `guild:v2:{id}`，JSON，30 min | `xm:guild:{g:<gid>}:snap`（`guildSnapshot(gid)`） | Java 自有 proto `xm.guild.GuildSnapshot`（字段同 GuildData，含 score / funds / 成员帮贡），ByteArrayCodec；30 min |
| 快照代次 | `guild:v2:cache_generation:{id}`，INCR，永久 | `xm:guild:{g:<gid>}:snap:gen`（`<数据键>:gen`，与 `friendCacheGeneration` 同规则，建议抽成通用 `cacheGeneration(dataKey)`） | 「UUID + Redis 服务器 TIME」，PX 2×TTL |
| 玩家 → 帮会映射 | `player_guild:v2:{pid}`，十进制，**0 也缓存**，30 min | `xm:guild:{p:<pid>}:gid`（`guildOfPlayer(pid)`） | 无符号十进制（0 也缓存）；30 min |
| 映射代次 | `player_guild:v2:cache_generation:{pid}` | `xm:guild:{p:<pid>}:gid:gen` | 同快照代次 |
| 申请推送冷却 | `guild:apply_push:{g}:{p}`，SETNX，60 s | `xm:guild:{g:<gid>}:apply-push:<pid>`（`guildApplyPush(gid, pid)`） | `SET NX PX 60000`；Redis 出错不推 |
| 排行 | `guild_rank`、`guild_rank:zone:{z}` | `xm:guild:{rank}:all`、`xm:guild:{rank}:zone:<z>`、区索引 `xm:guild:{rank}:zones` | §5.9 |
| 排行维护锁 / 临时键 | `guild_rank:maintenance_lock`、`guild_rank:rebuild:<token>:…` | `xm:guild:{rank}:lock`、`xm:guild:{rank}:tmp:<token>:all` 与 `xm:guild:{rank}:tmp:<token>:zone:<z>` | 锁 PX 30 s + 续期；临时键 PEXPIRE 10 min |
| 合服闸门 | `merge:in_progress:{zone}`（外部） | 4.4 不建（`MergeFence.NONE`） | — |

- 命名沿用 friend / chat 的 `xm:<服务>:{tag}:…` 形式（`RedisKeys.java:74-130`）；同一实体的数据键与代次键共用 hash tag，两段 Lua 在 Cluster 下同槽。
- id 一律 `Long.toUnsignedString`。

### 7.8 缓存（`GuildCache`）

照 friend 的版本化缓存（`FriendCache.java:25-140`；`architecture.md:371-375`），三处与基线不同（D10）：
1. **代次** = 失效时写「调用方 UUID + Redis 服务器 TIME」（带 2×TTL），读者遇到代次缺失自己 `SET NX` 一个，回填把「代次缺失」当成不符——没有永久计数键，
   不怕淘汰或 Redisson 重发造成 ABA（修 §9.1 第 3 条）。
2. **按键单飞**：同一键同一时刻只有一个回源者，等待者在自己的剩余预算内限时等、等到后重新读（修 §9.1 第 1 条的全局互斥）。
3. **回填失败只记日志与指标**，不让读请求失败（基线回错误，§9.1 第 4 条）；数据键或代次键**读**失败、值坏 → `DependencyException`（信封 1003），与基线同结局。

保持与基线相同的部分：0 也缓存（映射）；不存在的帮会不做负缓存；`pending_application_count` 不进缓存；授权一律不看缓存；`VerifyPlayerGuildID` 与 cached 相同就不写 Redis；
`ResolvePlayerGuild` 的坏路径绕过缓存直读 MySQL、仍不含本人时 fail-closed（故障）。

- 快照值需要 `byte[]` 版的 fill / invalidate 脚本（friend 的 `RedissonFriendCacheRedis` 用 StringCodec，JSON 字符串）：用 ByteArrayCodec，参数全部 `byte[]`，
  数字写 ASCII 十进制（同 `PlayerPresenceDirectory` 的脚本先例）。
- 排行一页的装配改为一次多键 GET 快照（D15），缺失的再逐个经单飞回源；MGET 失败按基线语义处理（条目只带 id / score / rank，记 ERROR）。

### 7.9 guild_id 发号（D8）

- `NodeIdLease.acquire(redis, scheduler, NodeTypes.GUILD, 0, 1, Snowflake.MAX_WORKER, …)`（`xm-discovery/.../NodeIdLease.java:81`），作用域 0（全服；理由同
  `NodeTypes.SCENE_GUID`，`NodeTypes.java:16-22`）。新增 `NodeTypes.GUILD = "guild"`。
- 每次发号前检查 `isValid()`（`NodeIdLease.java:142`）；`Snowflake` 时钟回拨会抛异常（`xm-common/.../id/Snowflake.java`）；租约无效、抛异常或返回 0 → **14008**（in-band，指标计 fault）。
  先例 `PlayerIdGenerator`、team-spec §6.9。
- 发号位置照基线：名字校验 → 归属区 → 闸门 → 读 `GuildLevel[1]` → 已在帮预检 → **发号** → 事务（`guild_logic.go:193-245`）。

### 7.10 归属区、名字、在线、推送

- **归属区**（D3）：`player.zone_id`（`xm-player-store/src/main/resources/db/xm-player-schema.sql`），由建角的 login 按自己的 `xm.zone-id` 写入。
  用 `PlayerProfiles.loadStrict(List.of(pid), deadline)`（`PlayerProfiles.java:62-73`）：查不到行或 `zone_id == 0` → 14012；SQL 出错或预算用完 → `DependencyException`（信封 1003）。
  审批通过：`loadStrict([actor, applicant])` 一条 `IN` 查询；先按审批人结果判 14012、再判闸门、再按申请人结果判 14018（申请人缺行或 zone 0）。
  与基线的唯一差别：基线两次查询独立，审批人查询成功而申请人查询失败时先回闸门结果；合并后 SQL 失败直接是故障（闸门在 Java 4.4 恒放行，无可见差异）。
- **名字**（D5）、**在线**（D6）、**推送**（D7）：见 §4.5–§4.7。

### 7.11 启动顺序（照 `guild.go:85-375`）

1. 配表校验（`validateGuildTables`），失败拒启；
2. pbmysql `syncAll` 建表 / 核对；
3. 数据库版本检查（`SELECT VERSION()`，规则同 §1.1），失败拒启；
4. 在命名锁 `GET_LOCK('xm_guild_insert_guard_init:xm_java', 5)` 下确保哨兵行 `guild_player_state(0)`（§1.7），失败拒启；
5. 申领发号租约；
6. 从 MySQL 全量重建排行（§5.9），失败拒启；
7. 最后才暴露 Dubbo。

### 7.12 配置项（`xm.guild.*`）

| 项 | 缺省 | 校验 / 出处 |
|---|---|---|
| `request-budget` | 3500 ms | ∈ [500, 3500] ms（`config.go:16-19`） |
| `cache-ttl` | 30m | > 0（`guild.yaml:67-68`） |
| `push-timeout` | 3s | `push.go:41`（friend 是 1500 ms，guild 取基线值） |
| `online-lookup-timeout` | 800 ms | Java 自有（D6） |
| `query-timeout` | 3s | 同 friend（语句超时上限） |
| `worker-threads` / `worker-queue-capacity` | 16 / 1024 | 同 friend |
| 代码常量（不开放配置） | 事务子预算 1500 / 2500 ms；重试 3 次；冷却 60 s；榜页长上限 50；清理 10 行；名字 24 / 48；公告 600 字节 | §0.5 |

- Redis database 12（与所有进程一致，`xm-friend/src/main/resources/application.yaml:58-60`）；`xm.killswitch.enabled: true`（`:55-56`）。
- 业务上限（申请有效期、每人 / 每帮待审数、成员与长老上限）**只读配表**，不做成配置项（`guild_manage_logic.go:37-96`）。

---

## 8 指标

### 8.1 基线

所有指标用 go-zero `core/metric`，只在 Prometheus agent 启用后产生样本（`player_name_resolver.go:62-70`；`guild.yaml:159-175`；`config_test.go:19`）。
label **绝不**放 player_id / guild_id（`guild_manage_repo.go:356-357`；`push.go:49-50`）。

| 名称 | 类型与 label | 含义 | 出处 |
|---|---|---|---|
| `guild_tx_deadlock_total{op}` | counter | 1213 / 9007 / errRetryTx 整体重跑 | `guild_manage_repo.go:361-367` |
| `guild_tx_budget_exceeded_total{op}` | counter | 跑满子预算 | `:369-375` |
| `guild_tx_lock_wait_timeout_total{op}` | counter | 1205 | `:377-383` |
| `guild_cache_invalidate_failed_total{op}` | counter | 后台重试用尽 | `:385-391` |
| op 固定集合 | `create, set_role, kick, transfer, leave, apply, cancel, review, disband, announcement, verify_mapping, score`；B5 / B6 预留 `upgrade, asset_finalize, activity, trial_settle, donate, shop`；外加 `insert_guard` | | `:394-426` |
| `guild_push_total{kind, outcome}` | counter，按**收件人**计；outcome = ok / offline / error / session_error；kind 是 13 个固定小写串，未知归 `other` | | `push.go:47-56`、`:232-269`；单测 `guild_manage_logic_test.go:594` |
| `guild_player_name_lookup_failed_total` | counter，无 label，按批计；请求 ctx 已结束的不计 | | `player_name_resolver.go:56-75` |
| serverbase | `rpc_inband_fault_total{method,source,code}`、`rpc_inband_reject_total{method,source}`、`rpc_inband_unknown_code_total{method,source}`、`rpc_duration_seconds{method,status}` | 帮会段只有 14008 计 fault | `guild.yaml:166-171`；`inband_observability_test.go:61`、`:107` |
| 其他 | `killswitch_blocked_total{method}`；`safego_panic_total{point}`（核心点位 `guild.push`、`guild.review.applicant_zone`、`guild.cache_invalidate`） | | `guild.go:463-465`；`guild.yaml:171` |

### 8.2 Java 建议（`GuildMetrics`，低基数，`AGENTS.md` §5）

| 指标（Prometheus 名） | 类型 | 标签 | 对应基线 |
|---|---|---|---|
| `xm_guild_requests_seconds` | Timer | `method`（GuildService 的 28 个方法名 + `unrouted`），`result` = ok / business_error / **fault**（in-band 14008）/ internal_error（信封 1003）/ overloaded / bad_request / unauthenticated / forbidden / unsupported | serverbase 的 `rpc_inband_*` 与 `rpc_duration_seconds` |
| `xm_guild_pushes_total` | Counter | `kind`（13 个固定小写串 + other），`outcome` = ok / offline / error / session_error；14 × 4 个组合启动即注册 | `guild_push_total` |
| `xm_guild_tx_deadlocks_total`、`xm_guild_tx_budget_exceeded_total`、`xm_guild_tx_lock_wait_timeouts_total` | Counter | `op`（固定集合，同基线，含 4.5 / 4.6 预留值与 insert_guard） | `guild_tx_*` |
| `xm_guild_cache_invalidation_failures_total` | Counter | `op` | `guild_cache_invalidate_failed_total` |
| `xm_guild_cache_total` | Counter | `cache` = snapshot / mapping，`result` = hit / miss / fill_skipped / fill_failed / error | Java 增项（同 friend `xm_friend_cache_total`） |
| `xm_guild_profile_lookup_failures_total` | Counter | — | `guild_player_name_lookup_failed_total` |
| `xm_guild_online_lookups_total` | Counter | `outcome` = ok / timeout / error | Java 增项（基线只有日志） |
| `xm_guild_rank_ops_total` | Counter | `op` = add / remove / rebuild，`outcome` = ok / lock_timeout / error | Java 增项（基线只有日志） |
| `executor_*{name="guild-worker"}` | Micrometer 标准线程池指标 | — | 同 friend |

- 选 `fault` 而不是把 14008 并进 `internal_error`：基线对它单独定性（Tip 表 fault 列），合并后就看不出「发号器坏了」与「依赖故障」的区别。
- `architecture.md` §11 的指标总表要补一组 guild 行，管理端口 18110。

---

## 9 隐患与边界

### 9.1 基线自身的隐患（移植时要么照搬，要么显式偏离并登记 PARITY）

1. **「singleflight」其实是进程级全局互斥**：所有 guild 与 player 的缓存 miss 串行，持锁期间做 MySQL 与 Lua；榜单一页逐条 `GetGuild` 的 miss 互相排队
   （`guild_repo.go:117`、`:123`、`:198-200`、`:240-241`）。Java 改按键单飞（D10）。
2. **不存在的 guild_id 没有负缓存**：每次都打 MySQL，还要排在那把全局锁后面（`guild_repo.go:209-212`）。Java 照搬（不做负缓存），但按键单飞后不再互相排队。
3. **代次键永久存在、没有 TTL**：数量随「被碰过的帮会与玩家」无限增长；Redis 若启用淘汰会出现 ABA，把旧快照回填进去（`guild_repo.go:146-152`、`:162-166`）。Java D10 修掉。
4. **回填 EVAL 报错时读请求整体失败**（`guild_repo.go:224-226`、`:266-268`），与 friend 只记日志不一致。Java D10 改为只记日志。
5. **`UpdateGuildScore` 提交后的缓存失效或 ZADD 失败返回 gRPC 错误**，MySQL 已提交（`guild_repo.go:604-617`；`guild_logic.go:588-589`），也没走 `invalidateAfterCommit`。
   基线没有调用方；Java 不暴露该 RPC（D12），内部入榜路径失败只记日志。
6. **持有榜维护锁的实例崩溃后，锁最长卡 5 分钟**：期间改分、建帮入榜、清榜都失败；新实例启动时 RebuildRanks 只等 5 s 就 panic（`guild_repo.go:157-159`；`guild.go:223-225`）。
   等锁上限 5 s 也超过请求预算 3.5 s：建帮成功后入榜若遇锁被占，会把剩余预算等光，随后的 `guildInfoFor` 在已到期的 ctx 上执行——在线读失败（全员离线）、
   取名被跳过（名字为空）、待审数留 0，回包退化但仍是成功（`guild_logic.go:283-290`）。Java D16 改短 TTL + 续期，请求路径等锁受 Deadline 约束。
7. **RebuildRanks 中途进程死亡，临时键没有 TTL，永久泄漏**（`guild_repo.go:636-665`）。Java D16 给临时键加 TTL。
8. **`GetGuildRank`（单帮）两步不原子**：ZREVRANK 与 ZSCORE 之间条目被删，ZSCORE 回 nil 被当成错误抛出（`guild_repo.go:870-881`）。Java D16 合成一段 Lua。
9. **分数精度**：float64 存进 ZSET，超过 2^53 丢精度；`ParseUint` 的错误被吞，得到 GuildID 0（`guild_repo.go:608`、`:850`、`:853`、`:885`）。照搬（无分数来源）。
10. **入榜失败只能等重启自愈**：建帮后的初始 ZADD 失败、解散后清榜失败都只记日志；mmorpg 没有任何推分来源，只有下次启动的 RebuildRanks 能修（`guild_logic.go:283-287`、`:503-508`）。照搬。
11. **在线 MGET 没有独立超时**，locator Redis 卡住时吃光整请求预算（`guild_logic.go:724-734`）。Java D6 加 800 ms 上限。
12. **`last_active_ms` 从不更新**，恒等于 `join_time_ms`（§0.3）。照搬，不另造更新路径。
13. **`Cache.MaxMembers` 是死配置**（`config.go:358-361`；只有 `DefaultTTL` 被 `guild.go:117` 读）。Java 不移植。
14. **`idx_guild_1(leader_id)` 在服务内没有任何查询使用，`idx_guild_0` 只有合服工具用**：只增加写放大。照搬（DDL 两版一致）。
15. **SetAnnouncement 可被当作跨区存在性探针**：guild_id 来自请求体；别区存在的帮回 14006（不是成员），不存在的帮回 14001（`guild_logic.go:543-549`；
    `guild_manage_repo.go:2398-2411`），与 GetGuild / Apply 把别区帮伪装成「不存在」的口径不一致。修它需要两版同改（N5）。
16. **申请刷新不断言行数**（同一毫秒双击为 0 行，有意，`guild_manage_repo.go:1728-1734`）；A2 撞上活记录不吞，回 14021（`:1770-1789`）。照搬。
17. **审批通过的过期 / 跨区 / 1062 子分支也先取 S(0) 与 S(p)**（有意，`guild_manage_repo.go:2059-2063`）；只有「申请行根本不存在」由预读挡掉。照搬。
18. **未知 role 编码的成员不能被踢**（`canKick` 要求 `t ≠ None`），只能自己退帮或被帮主任免改成 0 / 1。照搬。
19. **解散事务的行数随 4.6 的活动进度增长**，2500 ms 预算没有测量依据（`activity_repo.go:543-549`）。4.6 时测。
20. **注释与代码不一致的地方（以代码为准）**：
    - `guild_manage_repo.go:117-121`（`txBudgetDisband` 注释）写「逐行点删申请 → 提前截止捐献 → 逐行删成员」，实际顺序是**先删成员、再删申请、再提前截止**（`:2236-2242`、`:2295-2320`）；
    - `guild_db.proto:51-52` 说「上限判定在持有本行锁后对 guild_application 做加锁计数」，实际每人上限是**普通读计数**（`guild_manage_repo.go:1750-1754`）；
    - `guild_db.proto:10` 说「string 列建成 MEDIUMTEXT、进索引只按 191 前缀」，实际唯一键里的 `name_norm` 生成 `VARCHAR(191) … utf8mb4_0900_bin` 整列（`CreateTableSqlTest.java:323`）；
    - `merge_fence.go:25-28` 与 `guild.yaml:25-27` 只说闸门拦建帮，实际所有写 RPC 都过闸门（`guild_manage_logic_test.go:137-173`）；
    - `guild_repo.go:116-118`（`GuildRepo` 类型注释）写「Write path: Redis + MySQL (sync)」，实际写路径是「只写 MySQL、提交后失效缓存」（`:298-303`）；
    - robot 注释说 gate 超频时「直接丢、不回包」（`guild_smoke_scenario.go:107-111`），Java gate 回信封 1008（`ClientDispatcher.java:229-235`）。

### 9.2 Java 移植时会踩的

1. **失败形态必须一一对应**：基线 gRPC error → Java **信封** 1003（客户端进隔离）；基线 tip → Java in-band。绝不能把 14021、14013、14018 之类改成信封
   （正常玩法会让客户端进隔离）；反过来，依赖故障若照 friend 回 in-band 1003，客户端会显示通用文案而不进隔离，比基线宽松（§7.3）。
2. **uint64**：id 绑定、读回、比较都按无符号；锁序里的「按 player_id 升序」必须用 `Long.compareUnsigned`，否则大 id 的取锁顺序与主键顺序相反，锁序推演失效；
   ZSET member 必须是无符号十进制串。
3. **`useAffectedRows=true` 必须带**，否则 A2 自检与 4.5 / 4.6 的带上限 upsert 静默错误（§7.5）。
4. **guild_member 的锁定读 / UPDATE 必须带 `FORCE INDEX (PRIMARY)`**，pbmysql 的 `findOneByPkForUpdate` 不能用（§7.5）；EXPLAIN 回归要照搬（§1.9）。
5. **1062 要分辨撞的是哪个唯一键**：建帮成员行的 1062 → 14000，guild 行只有撞 `uk_guild`（消息以 `'uk_guild'` 或 `.uk_guild'` 结尾）才是 14010，主键冲突是内部错误。
   Java 判 `SQLException.getErrorCode() == 1062` 再看消息结尾。
6. **审批通过的 1062 子分支**：InnoDB 只回滚那一条 INSERT，事务要接着删申请行并提交；Java 只捕获那一条语句的 1062，不得让事务整体失败。
7. **帮名规范化必须按 Go 语义**（§2.4）；Go `x/text` 与 JDK `Normalizer` 的 Unicode 版本若不同，极少数字符的 NFKC 结果可能不同——两版共享同一个 uk 判重语义，
   上线前用基线单测的样例加一组随机样例对拍。
8. **公告长度按未 trim 的 UTF-8 字节数**；帮名长度按 trim 后的码点数。
9. **检查归属区与闸门的范围不对称，必须照抄**：GetPlayerGuild 不查归属区；ListGuildApplications 两者都不查；ListMyGuildApplications 查归属区不查闸门；
   审批拒绝也查审批人的区与闸门；Leave / Disband / SetAnnouncement 的检查只对客户端来源做（Java 恒为客户端）。
10. **退帮的幂等**：只有「事务说不在帮或帮已没了，**且** MySQL 复核为 0」才回成功；缓存与 MySQL 都说不在帮时 `operatorGuild` 先回 14002。
11. **SetAnnouncement 的 guild_id 取自请求体**，授权完全靠事务内锁行复核。
12. **写回包的快照必须含操作者本人**（`GuildClient.cs:710-727`）；转让 / 任免 / 踢人 / 审批 / 公告都用事务内快照。
13. **GetGuildRank 必须回显夹过之后的 page / page_size**，`total_count = ZCARD`。
14. **解散清榜只用事务内读到的 zone**（缓存里的 zone 在合服后会陈旧一个 TTL）。
15. **归属区只由建角 login 的 `xm.zone-id` 决定**（一个 login 进程只服务一个 zone）：多区部署要确保每个 zone 的 login 都配对；将来做合服时 `player.zone_id`
    与 `guild.zone_id` 要一起迁移（inventory 开放问题 5）。
16. **tip 参数**：照抄基线英文原因串（客户端不读，但逐字节对拍需要）。
17. **CreateGuild 的回包用内存构造的帮会**（不是事务内快照），字段 `level=1, funds=0, max_members=GuildLevel[1]`，与库一致。
18. **gate 限频**：Java gate 超频回信封 1008（基线 robot 注释说丢包）；Java robot 照样保留 300 ms 间隔与 1100 ms 同号退火。

### 9.3 边界速查

| 输入 | 结果 |
|---|---|
| 帮名 trim 后 24 个码点 / 25 个码点 / 空 / 全空白 / 含 U+0000–U+001F、U+007F–U+009F | 合法 / 14009 / 14009 / 14009 / 14009 |
| 帮名 NFKC 展开后 > 48 码点（如 13 个 `㍿`） | 14009 |
| `"ＡＢＣ"` 与已有 `"abc"` | 14010 |
| 公告 600 字节 / 601 字节 / 空串 | 合法 / 14011 / 合法（清空） |
| GetGuild / Apply 的 guild_id = 0 | 14001 / 14001 |
| Cancel 的 guild_id = 0；Review 的 applicant = 0 | 14018 / 14018 |
| SetAnnouncement 的 guild_id = 0 | 事务 G1 无行 → 14001 |
| GetGuildRankByGuild 的 guild_id = 0 或别区的帮 | 14007 |
| 任免 / 踢人 / 转让 target = 0 / = 自己 | 14014 / 14015 |
| 任免 role = 2 或 3 | 14006 |
| 成员数 ≥ `guild.max_members` | 新申请 14003；审批通过 14003（保留申请）；同帮刷新照常成功 |
| 本人有效申请 ≥ 3 | 新申请 14019；同帮刷新照常成功 |
| 帮会有效申请（I1）≥ 50 | 新申请 14020 |
| 长老数 ≥ `max_officers` | 任命 14017；降为成员不查 |
| `expire_ms ≤ now` | 视为过期（审批 / 撤回删行并回 14018；列表与计数不计） |
| page_size 0 / 60（客户端）；page 0 | 20 / 50；1 |
| 申请推送 60 s 内第二次新建行 | 不推（冷却） |
| 每次申请提交后清本帮过期申请 | ≤ 10 行 |
| GuildLevel.max_members 配 101 | 启动拒绝 |

### 9.4 对三份分区规格的勘误

1. 分区三写「`GuildChangeKind` 有 13 个值，从 0 到 13」：实际 14 个值（`UNSPECIFIED=0` 加 13 个有意义的值，`guild.proto:198-213`）。
2. 分区三引用「插入成员行都写 0,0，见 `guild_repo.go:366-368`」「funds 恒 0（`guild_repo.go:366-368`）」：成员行在 `:358-361`，guild 行的 `0, 0` 在 `:374-378`。
3. 分区三 D19 建议「维护锁只给重建用、单帮 ZADD / ZREM 不取锁」：会让重建与增量写互相覆盖，不采纳（§5.9、N8）。
4. 分区三 D13 说 in-band 1006 时客户端显示「该功能当前不可用」：客户端对不认识的码显示 `"帮会服务暂未完成请求（1006），请稍后重试。"`（`GuildClient.cs:767`）；
   「不进隔离」的结论成立。
5. 分区二 §13 建议 4.4 期间经济 / 活动号回**信封** 1003，分区三建议 **in-band 1006**：本稿采用 in-band 1006（D13），列为待拍板。
6. 分区三 §3.5 建议「编程式 `TransactionTemplate`」：本稿改为与 `JdbcFriendStore` 相同的原始 JDBC 连接事务（§7.6），避免框架把事务标成 rollback-only。
7. **键名统一**：分区一写 `xm:guild:{g:<gid>}:info`、`xm:guild:{rank}:all`，分区三写 `xm:guild:{g:<gid>}:snap`、`xm:{guild-rank}:all`；统一为 §7.7 的形式。
8. **指标统一**：14008 在分区三里计 `internal_error`，在分区二里计 `fault`；统一为 `fault`（§8.2）。配置名 `push-budget` / `push-timeout` 统一为 `push-timeout`（同 friend）。
9. 分区一 D16 把「回填失败是否只记日志」留作待定：本稿定为只记日志（D10）。
10. 分区三写审批通过时申请人归属区「Java 可以串行做」、分区二写「并行或合成一条 IN 查询」：本稿采用一条 IN 查询（§7.10）。
11. 分区稿的行号有的从函数注释起、有的从函数签名起（如 `SetMemberRole` 写 1286 或 1297）：两种都对；本稿多数引用到函数签名所在行。
12. **补充三份分区稿都没写的**：建帮成功后入榜等锁会把预算耗尽、导致回包装配退化（§9.1 第 6 条）；SetAnnouncement 的跨区存在性探针（第 15 条）；
    四处注释与代码不一致（第 20 条）；4.5 的内部服务 `GuildInternal`（§6.6）；锁序比较必须用无符号（§9.2 第 2 条）。

### 9.5 对 `docs/porting/inventory/guild.md` 的勘误

- **guild-leave**：「不在帮中视为幂等成功」不准确。映射为 0 且 MySQL 复核为 0 → `operatorGuild` 回 **14002**（`guild_manage_logic.go:239-252`）；只有缓存指向某帮、
  事务回答「不是成员 / 帮已没了」、复核为 0 时才是幂等成功（`guild_logic.go:446-455`）。
- **guild-announcement**：「不在帮也回 14006」只在帮会存在时成立；guild_id 不存在回 14001（`guild_logic.go:547-549`）。
- **guild-application-review**：「查不到申请人归属区也回 14018」只对映射缺失（zone 为 0）成立；查询出错是故障，回信封 1003（`guild_manage_logic.go:732-734`）。
- **guild-zone-isolation**：ListGuildApplications 不查归属区（`guild_manage_logic.go:616-619`）；ListMyGuildApplications 查归属区但不查闸门（`:532-545`）。
- **guild-rank**：UpdateGuildScore 在 MySQL 提交后若缓存失效或 ZADD 失败，回的是 gRPC 错误而不是 tip（`guild_repo.go:604-616`；`guild_logic.go:588-589`）；建帮时这一步失败只打日志。
- **`GuildActivityRows` 不存在**（inventory :56、:308）：xm-table 只有四张帮会表的 Rows 类（`xm-table/target/generated-sources/annotations/com/game/table/` 下
  `GuildDonateRows` / `GuildLevelRows` / `GuildRuleRows` / `GuildShopRows`）；GuildActivity 待同步（`PARITY.md:52`；`contract/SOURCE.properties:11-12`）。
- **「220 会被 Java 判成不回包」不成立**（inventory :23、:395(d)）：`tip≠0` 时 gate 照样回信封（`ClientDispatcher.java:456-459`）；friend 对上行 235 已经这样做（`FriendDispatcher.java:141-145`）。
- **在线目录与推送通道已经有了**（inventory :80、:200 的「Java 没有」已过时）：`xm:presence` / `PlayerPresenceDirectory` 与 `PlayerPushes`（`architecture.md:128-147`）。
- **热关停**（inventory :19）：Java 用 Redis `xm:killswitch`、在 gate 检查，不是 etcd（`architecture.md:406-424`）。
- **「未接入的域推 23」的行号**（inventory :20、:23）：现在是 `ClientDispatcher.java:313-319`。
- **「名字规范化可复用 xm-player-store」**（inventory :92）：不能原样复用（§2.4）。
- **MessageLimiter**（inventory :16）：8、220、239–243 不在表里，按缺省每秒 3 条。
- **存储**（inventory :29「MySQL（Java：xm_java 库 + MyBatis + Druid）」）：按用户要求改为 xm-pbmysql 建表 + 原始 JDBC（`roadmap.md:50-53`）。
- **`Cache.MaxMembers: 500` 没有读者**（`config.go:358-361`）。

---

## 10 建议的有意差异

### 10.1 建议采纳

| 编号 | 差异 | 理由 | 客户端可见？ | 两版同改？ |
|---|---|---|---|---|
| D1 | 独立进程 xm-guild（Dubbo group `guild`，端口 20886，管理端口 18110） | 路线图要求每个服务一个进程（`roadmap.md:48`）；端口顺延 team | 否 | 否 |
| D2 | 表在 `xm_java`，不建独占库；表定义是 Java 自有 `guild_tables.proto` + xm-pbmysql，没有 schemamigrate / `-migrate` / `Schema.AutoMigrate`；pbmysql **补建**缺失的普通索引（基线拒启） | `AGENTS.md` §3；`roadmap.md:50-53`；friend 先例（`PARITY.md:97`）。DDL 与 `guild_db.proto` 逐字相同 | 否 | 否 |
| D3 | 归属区取 `player.zone_id`，代替 data_service 的 `player:zone` 映射；查不到行或为 0 → 14012，SQL 故障 → 信封 1003 | Java 没有 data_service；同 team D5 | 否 | 否 |
| D4 | 合服闸门只留接口，4.4 用恒放行的 `MergeFence.NONE`；事务内闸门检查点照样保留（解散） | Java 首批不做合服（`architecture.md:617-620`）；14013 在 Java 暂不出现 | 否 | 否 |
| D5 | 展示名读 `xm_java.player`（name 恒非空），代替 `BatchGetPlayerName`；每批 64；失败留空 | 同 friend D4、team D4 | 是：名字恒非空 | 否 |
| D6 | 在线态取 `xm:presence`（gate 写，TTL 60 s，断线即删），代替 `player:session` 的 ONLINE；批量读带独立 800 ms 上限，超时按离线 | `architecture.md:128-140`；基线 MGET 无独立超时（§9.1 第 11 条） | 否（只在 Redis 卡住时） | 否 |
| D7 | 推送走 `PlayerPushes`（Redis pub/sub + 玩家栅栏），代替 Kafka gate-cmd；GATE_UNREACHABLE 记 error | `architecture.md:141-147`；同 friend | 否 | 否 |
| D8 | guild_id 来自 xm-guild 自己的全服雪花租约（`NodeTypes.GUILD`，作用域 0），代替 data_service 号段；失败回 14008 | Java 没有号段服务；客户端只把它当 uint64 | 否 | 否 |
| D9 | 后端调用失败或超时时 gate 推 23{1003}，不带请求 id；客户端要等 15 s 超时（`GameClient.cs:917`） | gate 现有行为（`ClientDispatcher.java:382-396`），同 team D14；结果仍是「本连接停用帮会」 | 是（只在故障时） | 否 |
| D10 | 缓存：代次 = 「UUID + 服务器 TIME」（2×TTL）、按键单飞、快照值用 Java 自有 proto、回填失败只记日志；键带 hash tag | friend D6 / D7 先例（`FriendCache.java:31-38`）；修 §9.1 第 1、3、4 条 | 否 | 否 |
| D11 | 工作队列满或排队超出预算 → **信封** 1003 | 基线没有应用层队列；guild 的故障一律走信封（in-band 1003 会被当成业务 tip 显示） | 是（只在过载时） | 否 |
| D12 | 没有「无会话内部调用」口：请求体 player_id / zone_id 一律忽略；上行 8 / 220 回信封 1003；UpdateGuildScore 与全服榜（zone 0）暂不提供类型化 Dubbo 接口 | `ClientMessageService` 只给 gate 用；mmorpg 里也没有任何服务端调用 8 | 否 | 否 |
| D13 | 4.4 期间经济 5 个号、活动 5 个号回 **in-band 1006**（写进各自应答的 `error_message`） | 先例 scene `ClientRequestHandler.java:237-255`；客户端进捐献 / 商店页会自动拉 120 / 228，in-band 只显示通用文案、不隔离；活动 tip 码 Java 没有。4.5 / 4.6 只换处理器 | 是（临时，按批次登记 PARITY） | 否 |
| D14 | 帮名规范化按 Go 语义实现（`GoSpaces.trim`、码点计数、`isISOControl`、NFKC、逐码点 `Character.toLowerCase`），不复用 `PlayerStore.nameKey` | 不这样做，两版对「重名」的判定不一致（§2.4） | 否（实现正确即无差异） | 否 |
| D15 | 排行一页一次多键 GET 快照，不再逐条 `GetGuild` | 基线 N+1（inventory 开放问题 9(a)）；条目内容不变 | 否 | 否 |
| D16 | 排行：键统一 hash tag `{rank}`、区索引 SET 代替 SCAN；单帮写 / 清榜 / 换榜各一段 Lua；维护锁保留但 TTL 30 s + 续期、请求路径等锁受 Deadline 约束；重建临时键带 TTL；52 的 ZREVRANK + ZSCORE 合成一段 Lua | 修 §9.1 第 6、7、8 条；Cluster 下 RENAME 要同槽。同分排序语义不变 | 极少：52 在条目恰被删的竞态里答 14007 而非故障 | 否 |
| D17 | 热关停在 gate 按方法检查（Redis `xm:killswitch`），客户端看到的仍是信封 1003；规则键与基线 etcd 键同名 | `architecture.md:406-424` | 否 | 否 |
| D18 | robot 用 run-tag 新账号，不用固定的 9201–9213；跨区第 4 步只在配了第二个 zone 时跑 | Java robot 惯例（`xm-robot/.../RobotOptions.java:71`）；本机切片单 zone；同 team D16 | 否 | 否 |

### 10.2 列出但不建议首批采纳（或需要两版同改）

- **N1：ListGuildApplications 也查闸门或归属区**。基线刻意不查（`guild_manage_logic.go:616-619`），不改。
- **N2：同分排行改为按 guild_id 数值降序**。两版目前都是 ZSET 成员字符串序；改它需要两版同改，收益极小。
- **N3：申请推送冷却在 Redis 出错时照推**。基线有意少推（`guild_manage_repo.go:689-692`），照搬。
- **N4：解散时也通知只提交过申请的玩家**。需要两版同改；客户端靠拉取自愈。
- **N5：SetAnnouncement 对别区 / 不是成员的帮也回 14001**（堵住 §9.1 第 15 条的存在性探针）。客户端可见，需要先改 mmorpg。
- **N6：更新 `last_active_ms`**。两版都不更新，保持。
- **N7：请求体解析失败回 1014（login 惯例）**。不采纳：基线客户端所见就是信封 1003，同 friend D11。
- **N8：增量排行写不取维护锁**。需要「重建期间增量写记脏、换榜后按 MySQL 复读脏集」的协议，首批不做（§5.9）。
- **N9：对不存在的 guild_id 做负缓存**。基线没有；负缓存要在建帮时额外失效，收益只在恶意刷不存在的 id 时出现，首批不做。

### 10.3 待用户拍板 / 开放问题

1. **D13：4.4 期间 10 个占位号回 in-band 1006 还是信封 1003**。推荐 1006：信封会让玩家点一下捐献页就得重登才能再用帮会。
2. **D11：过载回信封 1003 还是 in-band 14021**。推荐信封 1003（与「故障走信封」一致，也等价于基线路由服超时）；若更看重不隔离，可改 in-band 14021「帮会操作繁忙，请稍后重试」。
3. **跨区 robot 步骤**：是否在本机切片起第二个 zone（login + gate 都设 `xm.zone-id=2`）？否则跨区可见性只能由服务单测与 MySQL 测试覆盖。
4. **4.4 是否一并声明 B5 / B6 的表**（op_seq / asset_op / daily_counter / activity_progress）。推荐按批次追加（pbmysql 只扩不缩），4.4 先留钩子（§6.3）。
5. **`name_norm` 列宽**：保持缺省 VARCHAR(191)，与 Go DDL 相同；不声明 `max_length=48`。
6. **UpdateGuildScore 的积分来源**：两版都没有定义；先不暴露接口，需要时两版一起设计。
7. **缓存值格式**：Java 自有 proto（推荐，类型安全、uint64 无歧义）还是照 friend 用 JSON。
8. **榜维护锁 TTL 30 s + 续期**（推荐）还是保持基线 5 min。
9. **共用件抽取**：friend 与 guild 的版本化缓存是否抽到公共模块（`RedisKeys.friendCacheGeneration` 改名为通用的 `cacheGeneration`）；推荐在 xm-guild 落地时一并做。
10. **全服榜 `all`**：Java 4.4 没有读者，是否照样维护（推荐维护：成本低，日后 GM 工具或内部接口直接可用）。

---

## 11 测试计划

### 11.1 纯函数单测

- **`GuildRolesTest`**：`Rank`、`canKick` 矩阵（含未知编码 2）、`canAssign` / `canTransfer` / `canReview`、`demotedLeaderRole`、`AssignableRole`、`targetTip`
  （照 `guild_manage_repo_test.go:251-307`）。
- **`GuildNamesTest`**：基线用例（`client_zone_test.go:144-151`；`guild_repo_test.go:171-192`）+ Java 增项：控制字符边界（U+001F、U+007F、U+009F、U+00A0）、
  `İ`、词尾 `Σ`、U+0085 / U+3000 / U+00A0 首尾空白、代理对字符的码点计数。
- **`GuildTableRulesTest`**：逐条造坏样例（`guild_manage_logic_test.go:677`）；当前真实配表能通过，且**不因**第 8–10 列缺失而拒启。
- **错误分类**：1213 / 1205 / 查询超时 / commit 通讯异常 / 其它 SQL 错误的分类，沿 cause 链取错误号（照 `guild_manage_repo_test.go:308-365`）；重试结果不累积（`:433`）。
- **版本解析**（`server_version_test.go:13-51`）、**1062 唯一键识别**（`guild_repo_zone_test.go:21-40`）、**分页边界**（`rank_page_test.go:18-66`）。

### 11.2 服务与派发单测（假 Store / 缓存 / 归属区 / 名字 / 在线 / 推送，假时钟）

- **`GuildDispatcherTest`**：28 个方法的路由；8 与 220 回信封 1003；会话 `player_id=0` 回信封 1003；请求体非法回信封 1003 且解析先于身份检查；依赖故障与处理器异常回信封 1003；
  队列满回信封 1003；经济 / 活动号回 in-band 1006；不认识的号 1013 / 1006；启动校验缺号即失败（照 `session_test.go:59-147` 的准入用例）。
- **`GuildServiceTest`**，覆盖 §3 每个 RPC 的完整顺序：
  - 14012 先于闸门、归属区未知时不查闸门（`guild_manage_logic_test.go:231`）；闸门查的是归属区（`:197`）；目标校验在碰库之前（`:251`）；
  - 审批通过先查申请人归属区，缺映射 → 14018，查询失败 → 故障，拒绝不查申请人（`:330`）；
  - 公告超长在碰存储之前拒绝（`client_zone_test.go:153`）；请求体 zone 被忽略（`:47`）；别区的帮看起来不存在（`:208`）；排行只看本区（`:186`、`:224`）；
  - 写冲突回 14021（`guild_manage_logic_test.go:382`）；`mapWriteErr` 整表（`:397`）；解散的 ErrRankTooLow → 14005；
  - 退帮幂等的三种复核结局；ListMy 已入帮回空表；ListGuild 不是成员 → 14002 并自愈映射；
  - 名字整批只查一次、查失败不影响读（`guild_logic_names_test.go:75-283`）；帮主不在成员表时仍取名（`:98`）；
  - 回包快照含操作者；GetGuildRank 回显夹过的 page / page_size。
- **`GuildPushesTest`**：每个 kind 的收件人与 actor / target（§4.3）；去重去 0；GATE_UNREACHABLE 记 error；在线目录失败记 session_error；推送失败不影响回包；
  幂等任命与同帮刷新申请不推送；推送发生在缓存失效之后；冷却拿不到键不推。
- **`GuildPropertiesTest`**：预算区间校验。

### 11.3 真 MySQL（`-Dxm.it.mysql=jdbc:mysql://127.0.0.1:3306`，缺省跳过）

- **建表**：pbmysql 生成的四张表 DDL 与 §1.2 逐字相同（`uk_guild`、`uk_guild_member`、`idx_guild_0/1`、`idx_guild_application_0/1`）；哨兵行幂等、首插者回滚、缺失时 fail-closed、
  只有查重插入者取守卫（`guild_lock_order_mysql_test.go:1076-1293`）；版本下限（`server_version_test.go:67`）。
- **语句形状**：EXPLAIN 回归与静态形状检查（`guild_lock_plan_mysql_test.go:55-190`）。
- **业务**：移植 `guild_manage_repo_test.go:618-1829`——任命到上限、长老不能任免、缓存里的旧 role 不生效、等级配置缺失 fail-closed；踢人矩阵；转让三种降级与
  leader 不一致 fail-closed；退帮；申请同帮刷新（含同一毫秒、帮满时刷新）、每人上限只数未过期、帮会队列满、每次至多清 10 条、审批人名单；撤回；I1 过滤；
  审批通过删光申请人所有申请、跨区删行、拒绝、过期也提交删除、帮满保留申请、申请人已入别帮、成员不能审批；建帮删自己的申请；解散按 MySQL 授权并删申请、
  解散事务内判闸门；公告回快照；陈旧的 0 映射自愈；被踢后的陈旧快照；`guild_repo_zone_test.go:82-151`（跨区申请、跨区同名、规范化撞名）。
- **并发**（判据：死锁钩子零记录、不变量成立）：两帮同时通过同一人、并发申请守住每人上限、并发任命守住上限、并发建帮与申请（`guild_manage_repo_test.go:1649-1829`）；
  锁序回归 `guild_lock_order_mysql_test.go:166-1164` 全部场景（审批通过 ‖ 别帮拒绝 ‖ 撤回；申请 ‖ 撤回 ‖ 解散 ‖ 审批过期；退帮 ‖ 别帮通过；同名三方建帮；
  解散 ‖ 踢人 / 退帮；删成员先持玩家守卫；补建状态行不等已锁行；唯一键查重插入者三组；补建状态行首插者回滚；解散 ‖ 通过）。
- **Java 增项**：大于 2^63 的无符号 id 全流程（含锁序比较）；子预算用完不写库、回 14021；1205 → 14021；COMMIT 结果不明 → 14021。

### 11.4 真 Redis（`-Dxm.it.redis=redis://127.0.0.1:6379`）

- 版本化缓存：写者提交并失效之后，持旧快照的回填被拒；代次缺失时回填落空；按键单飞（同键只回源一次、不同键互不阻塞）；回填失败不让读失败；映射 0 也缓存。
- 排行：建帮以 0 分进全服榜、区榜与区索引；分页边界；解散后从所有区榜移除（`rank_zone_integration_test.go:120-182`）；重建原子替换并清掉幽灵条目；
  临时键带 TTL；维护锁续期与过期接管；52 的单段 Lua。
- 申请推送冷却：60 s 内只放行一次（`guild_manage_repo_test.go:570`）。
- 在线读：读失败按离线、独立超时按离线。

### 11.5 gate

- `MessageRoutesTest`：GuildService 的 28 个号都路由到 `guild`；220 `hasResponse=false`、8 `hasResponse=true`。
- `ClientDispatcherTest`：Empty 应答的方法在 `tip≠0` 时回信封并带请求 id；guild 域的队列与 login / scene / friend 互不阻塞；热关停 `/guildpb.GuildService/*` 生效。

### 11.6 robot `guild`（xm-robot 新增 `GuildScenario`，`RobotOptions.Scenario` 加 `GUILD`，`RobotOptions.java:61-63`）

- **结构**：账号 `prefix+"gd"+runTag+"_a|b|d|e|f"`；配了第二个 zone 时再加 `_c`（D18）；相邻请求间隔 300 ms，同号连发 4 次时插 1100 ms 退火
  （`guild_smoke_scenario.go:96-111`）；等推送 5 s，超时即失败（`:103-105`）；输出用 `CheckReport`，引用 PARITY「帮会核心」行。
- **步骤**：基线第 0–10 步与 M1–M10 全部照跑（`guild_smoke_scenario.go:264-704`）：建帮落归属区、二次建帮 14000、本区榜可见且带名字、别区不可见 / 不可申请 / 同名 14010、
  未入帮改公告 14006、申请 → 待审名单 → 通过并收 MEMBER_JOINED、成员与帮主名非空、改公告与 700 字节公告 14011、伪造 player_id 退帮按会话身份受理、
  上行 8 → 信封 1003 且分数不变、管理段（重复申请只一条、未入帮列待审 14002、批准 / 拒绝推送、拒绝后再审 14018、任命长老 `officer_count==1 && max_officers==2`、
  幂等任命、长老踢成员 / 踢帮主 14016 / 踢自己 14015 / 任免帮主 14016 / 重踢 14014、两次转让与降级、退帮推送、解散后 14002 / 14007、解散删掉别人的申请）。
  第 9 步照 `FriendScenario.java:251-260` 的写法：`send` + `await`，断言信封 1003 且没有业务回包（`call` 遇信封会抛异常）。
- **Java 增项**（钉住契约细节）：帮名为空 / 25 个汉字 / 含控制字符 → 14009；`"ＡＢＣ…"` 与已有 `"abc…"` 撞名 → 14010；任免 role=2 → 14006；Apply guild_id=0 → 14001；
  Cancel guild_id=0 → 14018；Review 申请人=0 → 14018、审批自己 → 14015；Kick target=0 → 14014；普通成员列待审 → 14016；帮主退帮 → 14004；长老解散 → 14005；
  有待审时帮主的 GetPlayerGuild 里 `pending_application_count > 0`、普通成员看到 0；GetGuildRank page_size=60 回显 50、page_size=0 回显 20；上行 220 → 信封 1003、
  没有业务回包；DonateToGuild 回 in-band 1006（只在 4.4 期间，D13）。




