# 功能清单：社交域（Go friend / chat / trade）— mmorpg 26ceb70ca vs Java 版

范围：`go/friend/**`、`go/chat/**`、`go/trade/**`，协议 `proto/friend/{friend,friend_table}.proto`、`proto/chat/chat.proto`、
`proto/trade/{jubaozhai,trade_admin,trade_table}.proto`；客户端用法核对自 `mmorpg-client/Assets/Scripts/{Game/Social,Game/Team,Game/Jubaozhai,App/DevTeamInvitationDriver.cs}`。

概述：mmorpg 的三个社交服务都是"全局一份、多副本、无状态"的 Go 微服务，客户端经 gate → client_rpc_router（路由服模式）到达，
身份只取 gate 注入的会话 metadata `x-session-detail-bin`，业务结果一律 in-band（`TipInfoMessage`），存储故障统一回 1003。
friend 有完整的好友申请 / 同意 / 拒绝 / 删除 / 列表 / 黑名单 / 推荐 / 在线目录（组队邀请用）与 S2C 推送（Kafka→gate，at-most-once），
权威数据在独占库 `mmorpg_friend`（四表 + 容量守卫行锁），列表经私有 Redis 版本号缓存；chat 只有 v1：世界频道（全服一条）+ 私聊，
数据只在 Redis（7 天 / 200 条尽力窗口），**没有任何实时推送**（客户端发完自己拉历史），TEAM / SYSTEM 频道回 1006，无敏感词、无黑名单联动；
trade 只有聚宝斋 P1（浏览 / 详情 / 收藏 / 货架 + dev 种子），P2 资产托管通道已落码但默认关闭且无客户端入口，上架 / 下单 / 支付 / 拍卖都未做。
mmorpg 不存在"面对面玩家交易"、邮件（mail 只有设计稿）、帮会频道。Java 版（xuanming-server-mmo）三块**全部未开始**：
gate 的 `ClientDispatcher.dispatch` 对非 login / scene 域的消息一律回信封 tip 1003（`xm-gate/.../session/ClientDispatcher.java:254`），
仓内无任何 friend / chat / trade 代码，xm-robot 也无对应场景；gate 层按消息号限频（MessageLimiter 表）已对齐，会自动覆盖这些消息号。

tip 码速查：1003 kServiceUnavailable(fault)、1005 kInvalidParameter、1006 kFeatureUnavailable、1008 kRateLimitExceeded、1010 kMessageSizeExceeded；
好友段 15000 CannotAddSelf / 15001 AlreadyFriends / 15002 ListFull / 15003 RequestAlreadySent / 15004 TargetListFull / 15005 NoPendingRequest /
15006 TooManyPending / 15007 Blocked / 15008 BlockListFull / 15009 TargetInboxFull；聚宝斋段 20000 ListingNotFound / 20001 HomeZoneUnknown /
20002 FavoriteLimitReached / 20003 FeatureDisabled。

## 公共底座（三个服务共同依赖）

### social-backend-routing — 社交域消息路由到后端服务（带会话身份与方法准入）
- mmorpg: `go/client_rpc_router`（路由表按 OptionIsClientProtocolService 生成）、`go/friend/internal/session/session.go`、`go/trade/internal/session/session.go`、`go/chat/internal/session/session.go`、各服务 `buildUnaryInterceptors`
- client messages: 所有 friend / chat / jubaozhai C2S 消息号（见各功能）；内部 199 TradeAdmin.SeedListing 不得从客户端可达
- tables: MessageLimiter（gate 第一道限频）
- depends on: gate 会话、登录进游戏（player_id 绑定）
- behavior: 请求体不带自己的 player_id（friend 六个请求体 `reserved 1`），"我是谁"只取会话；带会话调白名单外方法 → 拒绝（friend 对 235 NotifyFriendEvent、trade 对 TradeAdmin/*）。客户端看到的形状：friend 发 235 → 信封 tip 1003（路由服把上游 gRPC 错误统一翻成 1003）；发 199 → gate 直接丢弃（不是客户端服务的消息号，表现为超时）。会话缺失（逻辑层取不到 player_id）→ in-band 1005，刻意不用 1012（fault）。chat 的拦截器坏头"放行到逻辑层"，最终也是 1005。所有业务失败都在响应体 `error_message`，信封只表示"没到业务"。服务超时 4000ms < 路由服转发 5000ms，逻辑层预算 = Timeout − 500ms，保证超时也能 in-band 回 1003。
- internal: Java 需要 gate 增加 friend / chat / trade 域（`MessageRoutes.backendOf`），把 `ClientCall{session, message_id, body, request_id}` 经 Dubbo 发给对应 Java 服务（可合成一个 xm-social 进程 + 一个 xm-trade 进程），回包回填到原请求号；准入用"客户端服务且非 S2C 方法"白名单，S2C 方法号（235）上行必须被拒；回调回到连接 EventLoop。
- java: missing — `ClientDispatcher.dispatch` default 分支回 `TIP_SERVICE_UNAVAILABLE`（xm-gate/src/main/java/com/game/gate/session/ClientDispatcher.java:254）；`MessageRoutesTest` 只断言 ClientPlayerFriend 能被识别为客户端服务
- size: M
- robot: mmorpg friend-smoke 第 6 步（235 上行被拒）、trade-smoke 第 2 步（199 经 gate 超时）
- hazards: NotifyFriendEvent 与 10 个 C2S 同处 `ClientPlayerFriend` 服务（生成器只给含 ClientPlayer 的服务出推送 handler），所以 gate 的客户端白名单**会**放它上来——准入必须在方法级做，不能按服务名；消息号由 proto-gen 按 map 迭代顺序复用空号，Java 必须只用 ContractSync 同步来的号，不能按名字推断。

### player-push-channel — 后端服务向在线玩家推送（PushToPlayer）
- mmorpg: `go/friend/internal/logic/push.go`、`go/friend/internal/kafka/gate_command_builder.go`、`go/shared/kafkautil`（topic `gate-cmd_g<N>`，`GateCommand{PushToPlayerEvent|BroadcastToPlayers|BroadcastToScene|BroadcastToAll}`）
- client messages: 235 ClientPlayerFriendNotifyFriendEvent (S2C push)（本域唯一的推送；chat / trade 不推）
- tables: none
- depends on: player-presence-directory（找玩家所在 gate / session）
- behavior: at-most-once：玩家不在线（会话 state≠ONLINE 或无 key）直接不推；Kafka 抖动、玩家正好掉线都会永久丢事件，客户端必须靠拉列表兜底。推送只在 MySQL 提交后、事务外做；失败只打日志 + 计指标，绝不影响 RPC 结果；不推给操作者本人。单次推送超时 1500ms。
- internal: Java 无 Kafka（architecture.md §10 列为后续批次）。需要一条"任意 Java 服务 → 指定玩家所在 gate 连接"的下行通道（例如按 presence 里的 gate 节点号经 Dubbo / Redis pub/sub 投递，gate 在连接 EventLoop 上写帧，帧形状与 scene 下行相同：message_id + body，request_id=0）。
- java: missing — 仓内只有 owner-takeover 的 pub/sub（xm-login/.../RedisOwnerTakeovers.java、xm-scene/.../OwnerTakeoverSubscriber.java），无通用推送；PARITY「gate 接入与路由」行注明"Kafka 命令待做"
- size: M
- robot: mmorpg friend-smoke 第 1、3 步（跨 zone 推送 10s 内到达）
- hazards: 推送事件刻意只带 reason + by_player_id + ts_ms（不带列表，防超 gate 单包上限）；跨 zone 推送（A 在 zone1、B 在 zone2）是验收点；Java 设计时注意不要把推送失败回传成业务失败。

### player-presence-directory — 玩家在线状态目录（谁在线、在哪个 gate、最后活跃时刻）
- mmorpg: 写者 `go/player_locator`（key `player:session:{id}`，值 `player_locator.PlayerSession{player_id, state, session_id, gate_id, gate_instance_id, last_active_ts}`）；读者 `go/friend/internal/data/session_reader.go`、`push.go`、`online_directory.go`
- client messages: 间接影响 12 GetFriendList（is_online / last_active_ms）、119 RecommendFriends（is_online、在线目录）、235 推送路由
- tables: none
- depends on: 登录 / 进场 / 断线 / 顶号生命周期
- behavior: 只有 state=ONLINE 且 key 里 player_id 与查询 id 一致才算在线，否则离线；last_active_ms 只在在线时填。好友列表的在线读是"可靠查询"：Redis / 解码 / 身份不符错误 → 整个 GetFriendList 回 1003（客户端据此禁邀，不能把"未知"当离线）；推荐是"可降级展示"：读失败当离线。
- internal: Java 当前没有跨进程的"玩家在线 + 所在 gate"目录（gate 会话状态只在 gate 进程内；xm-player-store 的 owner_released / owner_lease_until 只表示数据归属，不含 gate / session）。需新增 Redis 键（经 `RedisKeys`，`xm:` 前缀）或 Dubbo 查询；在线目录功能还需要能"枚举在线玩家"。
- java: missing — `RedisKeys` 只有节点号 / 节点目录 / owner-takeover 频道（xm-discovery/src/main/java/com/game/discovery/RedisKeys.java）
- size: M
- robot: mmorpg friend-smoke 第 3 步（is_online=true、last_active_ms≠0）
- hazards: mmorpg 的 key 无 hash tag，MGET 多 key 要求共享库不能 Cluster；friend 读错 Redis 实例不会报错、只会"全员离线"。好友服务曾有自写的 `friend:online` 键但无写者（已删）——Java 不要重蹈"只有读者"的覆辙，写者与读者同批交付。

### player-profile-lookup — 批量查玩家展示资料与归属区（home zone）
- mmorpg: `go/friend/internal/data/friend_profiles.go`（共享 Redis `PlayerAllData:{id}` 缓存 + data_service `BatchGetPlayerName` / `BatchGetPlayerHomeZone`）、`go/trade/internal/logic/home_zone.go`（data_service 查 home_zone，超时 1500ms）
- client messages: 间接：12 GetFriendList（name / level / class_id / gender / appearance_id / zone_id）、119 在线目录、196/197/198 的 zone 范围判定
- tables: none
- depends on: 玩家持久化
- behavior: 好友列表展示资料缺失时只打日志、照常返回（名字可能为空）；home_zone=0 → trade 回 20001，查询失败 → 1003。
- internal: Java 玩家表已有 name / level / class_id / gender / appearance_id / zone_id（xm-player-store/src/main/resources/db/xm-player-schema.sql），缺一个批量查询入口（PlayerStore 只有 findPlayer 单查、listPlayers 按账号）；Java 无"归属区"与"当前区"之分，zone_id 即 home zone（合服时需改写）。
- java: partial — 数据有、接口无（`PlayerStore.findPlayer` 单行；无批量 / 无 Dubbo 暴露）
- size: S
- robot: none
- hazards: mmorpg 的 level 来自 Redis 里的 PlayerAllData 缓存（可能比 MySQL 新），Java 直读 player 表时 level 只在写回时更新——在线玩家的等级可能滞后，属可接受差异但要登记。

### rpc-killswitch — 按方法热关停（运维止血阀）
- mmorpg: `go/shared/killswitch`（etcd 前缀 `/mmorpg/killswitch/<pkg.Service>/<Method|*>`），friend / chat / trade 拦截器链第 ②
- client messages: 任一社交消息号；被关停时客户端看到信封级失败
- tables: none
- depends on: social-backend-routing
- behavior: etcd 下放 `{"deny":true,"reason":..}` 即对该方法（或整个服务 `*`）短路；etcd 不可达一律放行（fail-open）。
- internal: Java 可用 Nacos 配置 / Dubbo 动态路由实现同等"按方法开关"；属于运维面，客户端只看到信封 1003 一类的拒绝。
- java: missing — 无任何按方法关停机制
- size: S
- robot: none
- hazards: 被关停的调用 mmorpg 不计入业务耗时指标；Java 若在 gate 侧实现，注意与 MessageLimiter 计数的先后顺序。

## 好友（go/friend）

### friend-relation-store — 好友关系权威存储与容量守卫（四表 + 锁序 + 版本号缓存）
- mmorpg: `proto/friend/friend_table.proto`、`go/friend/internal/data/friend_repo.go`、`block_repo.go`、`tables.go`
- client messages: none（被 234/238/232/11/12/230/7/236/2 共用）
- tables: none（配置项 Friend.MaxFriends=200（硬顶 300）、MaxPendingRequests=50、MaxIncomingRequests=200、MaxBlocks=200、ListReadHardLimit=1000、CacheTTL=30m）
- depends on: none
- behavior: 好友边双向两行（A→B、B→A 同事务）；`friend_request` 每对 (from,to) 一行，状态 1 pending / 2 accepted / 3 rejected，重复申请走 upsert；`friend_capacity` 显式计数行，写事务内 `SELECT ... FOR UPDATE` 按 player_id 升序锁双方计数行后才做任何锁定读——好友上限在高并发下也不超卖；`friend_block` 单向。列表读带 LIMIT 1000。
- internal: 写路径隔离级别 READ COMMITTED；事务外先"ensure 容量行"（COUNT(*) 权威边数 + INSERT ODKU，缺行绝不猜 0），守卫缺行（被 sweep 回收竞态）整段重试至多 3 次；1213 死锁重试 3 次。私有 Redis 缓存 `friend:{f:<id>}:list:v3` / `:req:v3` + `<key>:generation`：写提交后 Lua `INCR generation + DEL`，回填 Lua 只在 generation 未变时 SET（防旧值覆盖），进程内按 key 互斥防击穿；缓存失效失败只打日志（最多陈旧一个 TTL）。Java 版：xm_java 里建同语义四表（自己的表名 / DDL），缓存键经 RedisKeys；可用 Redisson / Spring Cache 但必须保留"代次防回填"语义。
- java: missing — 无任何好友表或代码
- size: L
- robot: mmorpg friend-smoke 全流程间接覆盖
- hazards: ① 计数行与边数必须同事务同增同减，删边时 `friend_count>0` 防下溢并打错误日志；② 删边会把 created_ms 刷成当前时刻（防 sweep 回收 + 陈旧 INSERT 把计数写大 1，见 proto 注释）；③ AddFriend 会给**任意** target id 建容量行（friend 无玩家名册，不校验 target 存在）——Java 有玩家表，可改为先校验目标存在（客户端可见差异：mmorpg 对不存在的 id 也回成功）；④ 锁序：容量行 → 拉黑行 → 好友边 → 申请行，新增写路径必须遵守。

### friend-request-add — 发起好友申请（含频控与推送）
- mmorpg: `go/friend/internal/logic/friend_logic.go` AddFriend、`rate_quota.go`、`data/friend_repo.go` AddFriendRequest
- client messages: 234 ClientPlayerFriendAddFriend (C2S)；成功后向 target 推 235（reason=REQUEST_RECEIVED, by=我）
- tables: none
- depends on: friend-relation-store, social-backend-routing, player-push-channel, player-presence-directory
- behavior: 校验顺序：无会话 1005 → target=0 1005 → target=自己 15000 → 频控（每人每分钟 10 次，固定窗口，超出 1008）→ 事务内：任一方向拉黑 15007（文案中性，不泄露方向）→ 已是好友 15001 → 我→他已 pending 15003 → 我出站 pending ≥50 15006 → 对方入站 pending ≥200 15009 → 我好友满 15002 → 对方好友满 15004 → upsert pending（覆盖旧的 accepted/rejected 行，request_time_ms 重置）。成功后失效对方的申请缓存并推送。对方已向我发过申请时**不会**自动成为好友，双方各一条 pending。
- internal: 频控 Redis Lua `INCR`+首次 `EXPIRE 60`（+TTL==-1 自愈），key `friend:{rq:<pid>}`；Redis 故障 **fail-open**（放行并计 `friend_rate_quota_total{outcome=error}`），理由是社交功能可用性优先、硬上限都在 MySQL fail-closed。
- java: missing
- size: M
- robot: mmorpg friend-smoke 第 1、2 步（推送到达、重复申请 15003）、第 4 步（双向 15007）
- hazards: 频控必须在任何副作用之前；`count > limit` 而非 `>=`（第 10 次放行）；固定窗口边界最坏放过 2×阈值。

### friend-accept-reject — 同意 / 拒绝好友申请
- mmorpg: `friend_logic.go` AcceptFriend / RejectFriend、`friend_repo.go` AcceptFriend / RejectFriend
- client messages: 238 ClientPlayerFriendAcceptFriend (C2S)、232 ClientPlayerFriendRejectFriend (C2S)；同意成功后向原申请人推 235（reason=REQUEST_ACCEPTED, by=我）；拒绝不推
- tables: none
- depends on: friend-relation-store, player-push-channel
- behavior: Accept：无会话 / from=0 / from=自己 → 1005；无此 pending 15005（事务外先探一次，事务内 FOR UPDATE 再判）；任一方向拉黑 15007；对方（申请人）好友满 15004、我好友满 15002；成功：正向置 accepted、反向 pending 一并置 accepted、双向 INSERT IGNORE 边并各自计数 +1，失效四个缓存键。Reject：CAS `pending→rejected`，0 行 → 15005；只失效我的申请缓存。
- internal: Accept 走容量守卫事务；Reject 单条 UPDATE 不加守卫（只会让数量变少）。
- java: missing
- size: M
- robot: mmorpg friend-smoke 第 3 步（同意 + 推送 + 双方列表互见）
- hazards: 拒绝刻意不推送（避免社交尴尬）；Accept 的 15004/15002 映射方向与 AddFriend 相反（申请人 = sender），Java 别抄错。

### friend-remove — 删除好友
- mmorpg: `friend_logic.go` RemoveFriend、`friend_repo.go` RemoveFriend / deleteFriendEdges
- client messages: 11 ClientPlayerFriendRemoveFriend (C2S)
- tables: none
- depends on: friend-relation-store
- behavior: 无会话 / target=0 → 1005；不是好友、删自己都**回成功**（幂等，无 tip）；双向删边并各自计数 −1，失效双方好友列表缓存；不推送给对方（对方下次拉列表才发现）。
- internal: 事务外先探边是否存在，存在才进容量守卫事务。
- java: missing
- size: S
- robot: mmorpg friend-smoke 第 0 步预清理、第 7 步清理；客户端 DevTeamInvitationDriver 使用
- hazards: 删好友不清旧的 accepted 申请行（由 sweep 清），之后再加好友走 upsert，行为等价。

### friend-list — 好友列表（含在线状态与展示资料）
- mmorpg: `friend_logic.go` GetFriendList、`data/session_reader.go` BatchOnlineStatus、`data/friend_profiles.go` FillFriendProfiles
- client messages: 12 ClientPlayerFriendGetFriendList (C2S)
- tables: none
- depends on: friend-relation-store, player-presence-directory, player-profile-lookup
- behavior: 返回 `FriendEntry{friend_player_id, since_ms, last_active_ms, is_online, name, level, class_id, gender, appearance_id, zone_id}`，最多 1000 条、无排序保证、无分页。在线状态读失败 → 整个请求 1003（客户端组队邀请据 is_online 禁邀离线好友，不能把未知当离线）；展示资料补全失败只打日志、照常返回。客户端 TeamInvitationDirectory 用它做"好友"页签。
- internal: 关系列表走版本号缓存；在线状态按 ListReadHardLimit 分批 MGET；名字缺失走 data_service 批量回源，zone_id 每批都查 home zone。
- java: missing
- size: M
- robot: mmorpg friend-smoke 第 3 步（双方互见、is_online=true、last_active_ms≠0）
- hazards: 缓存里只存 (id, since_ms)，在线状态与资料每次实时补，不能整条缓存；客户端在 `name` 为空时回退本地缓存，Java 尽量填全。

### friend-pending-list — 待处理好友申请列表
- mmorpg: `friend_logic.go` GetPendingRequests、`friend_repo.go` loadPendingRequestsFromMySQL
- client messages: 230 ClientPlayerFriendGetPendingRequests (C2S)
- tables: none
- depends on: friend-relation-store
- behavior: 只返回**入站**（to=我、status=pending）申请，`FriendRequest{from_player_id, to_player_id, request_time_ms, status=1}`，最多 1000 条，无排序；没有"我发出的申请"列表接口。这是推送丢失时的权威补救路径。
- internal: 走版本号缓存（`friend:{f:<id>}:req:v3`），被 Add / Accept / Reject / Block 失效。
- java: missing
- size: S
- robot: mmorpg friend-smoke 第 2 步
- hazards: 被拉黑时两个方向的 pending 会被置 rejected，列表随之消失。

### friend-event-push — 好友事件推送（红点触发）
- mmorpg: `go/friend/internal/logic/push.go`、`proto/friend/friend.proto` FriendEventS2C
- client messages: 235 ClientPlayerFriendNotifyFriendEvent (S2C push)：`FriendEventS2C{reason, by_player_id, ts_ms}`
- tables: none
- depends on: player-push-channel, player-presence-directory, friend-request-add, friend-accept-reject
- behavior: 仅两个触发点：AddFriend 成功 → 推 REQUEST_RECEIVED 给 target；AcceptFriend 成功 → 推 REQUEST_ACCEPTED 给原申请人。Reject / Remove / Block / Unblock 都不推；不推给操作者；对方离线直接丢（无离线补推）；未知 reason 拒推。客户端收到后应去拉 230 / 12。
- internal: 推送失败计 `friend_push_total{reason,outcome}`（ok / offline / error），不影响 RPC 结果。
- java: missing
- size: S
- robot: mmorpg friend-smoke 第 1、3 步（10s 内收到）
- hazards: Unity 客户端生成的 235 handler 桩未接线（friend-handoff §4.3），目前只有 robot 消费；客户端上行发 235 必须被拒（见 social-backend-routing）。

### friend-blacklist — 黑名单（拉黑 / 解除 / 列表）
- mmorpg: `friend_logic.go` Block / Unblock / ListBlocks、`data/block_repo.go`
- client messages: 7 ClientPlayerFriendBlock (C2S)、236 ClientPlayerFriendUnblock (C2S)、2 ClientPlayerFriendListBlocks (C2S)
- tables: none
- depends on: friend-relation-store
- behavior: Block：无会话、target=0、target=自己 → 1005；黑名单满（200）→ 15008（已拉黑的再拉黑不占名额、幂等成功）；成功时同一事务内删除双向好友边（计数 −1）并把两个方向的 pending 置 rejected；不通知被拉黑者。Unblock：同样参数校验，单条 DELETE，幂等成功，**不恢复**好友关系。ListBlocks：`BlockEntry{blocked_player_id, since_ms}`，最多 1000，不缓存。拉黑效果只体现在好友域：任一方向存在拉黑时 Add / Accept 都回 15007，推荐排除双向拉黑。
- internal: Block 事务外先快速判满（不 ensure 容量行），事务内走容量守卫（与 Add / Accept 互斥），再锁定读复核；写黑名单用 ODKU 而非 INSERT IGNORE。
- java: missing
- size: M
- robot: mmorpg friend-smoke 第 4 步（C Block A，双向 AddFriend 均 15007）、第 7 步 Unblock
- hazards: mmorpg 的 chat 私聊**不读**黑名单——被拉黑者仍能私聊对方（见 chat-send）；Java 若要联动需两版同改。Block 之后 Unblock 换目标反复刷会堆容量行（由 sweep 回收）。

### friend-recommend — 好友推荐（共同好友 + 随机兜底）
- mmorpg: `go/friend/internal/logic/recommend.go`、`data/recommend_repo.go`
- client messages: 119 ClientPlayerFriendRecommendFriends (C2S，online_only=false)
- tables: none（配置 RecommendDefaultLimit=10、RecommendMaxLimit=20（硬顶 20）、RecommendMaxExclude=64）
- depends on: friend-relation-store, player-presence-directory
- behavior: exclude_player_ids 超 64 条 → 1005（不截断）；limit=0 取 10，>20 钳到 20（不报错）。策略链：① 二度好友按共同好友数降序（同数随机）；② 随机锚点兜底（在 friend 表 player_id 区间随机取 pivot，取其后 1024 个有好友的玩家）。两级都排除：自己、exclude、已是好友、双向拉黑、双向 pending。返回 `RecommendEntry{candidate_player_id, mutual_friends, is_online, last_active_ms}`——**name / level 等展示字段不填**；在线读失败当离线。SQL 失败 → 1003。response.online_directory=false。
- internal: 纯读、无事务、允许陈旧；每条候选查询扫描量有上界（STRAIGHT_JOIN + 主键点查），MaxFriends 硬顶 300 就是为了二度查询的 MaxFriends² 上界。
- java: missing
- size: M
- robot: mmorpg friend-smoke 第 5 步（limit=50 钳到 ≤20，不含自己 / 好友 / 被拉黑者）
- hazards: 随机兜底只能推"至少有一个好友的玩家"（锚点取自 friend 表），新服冷启动推荐为空；当前 Unity 客户端不用这个模式（只用 online_only），客户端可见行为主要由 robot 约束。

### friend-online-directory — 同区在线玩家目录（组队邀请用，RecommendFriends online_only）
- mmorpg: `go/friend/internal/logic/online_directory.go`、`data/online_directory.go`
- client messages: 119 ClientPlayerFriendRecommendFriends (C2S，online_only=true, cursor, query)
- tables: none
- depends on: player-presence-directory, player-profile-lookup
- behavior: 响应恒带 `online_directory=true`（含出错时；客户端见 false 即判"旧服不支持"）。cursor 格式 `v1:<scan>:<offset>`（≤48 字节、offset≤1024），非法 → 1005；query ≤64 个 Unicode 字符，非法 UTF-8 → 1005；query 去首尾空白、小写后按"昵称包含"或"player_id 十进制包含"匹配。每人每分钟 60 页（`friend:{directory:<pid>}`），超出 1008，限流 Redis 故障 **fail-closed** 回 1003。只返回与调用者 home zone 相同、state=ONLINE、资料完整（有名字且 class≠0）的玩家，**包含已是好友的人**，排除自己与 exclude；条目带 name / level / class_id / gender / appearance_id / zone_id、is_online=true。limit 先经推荐钳制（默认 10、上限 20）。next_cursor 非空表示可继续，本页可为空；不是快照（上下线会让结果漂移）。调用者 home zone 未知 → 1003。
- internal: 在共享 Redis 上 SCAN `player:session:*`（每轮 64、最多 4 轮、单批 ≤1024），MGET 会话 + PlayerAllData 缓存，再批量查 home zone 过滤。Java 需要一个可分页枚举的在线目录（建议按 zone 维护在线集合，如 ZSET / Set，游标由 Java 自定义但保持"不透明字符串 + 空=结束"契约）。
- java: missing
- size: M
- robot: none（mmorpg robot 不覆盖 online_only；Unity `TeamInvitationDirectory.cs` 在线页签使用，PageSize + 加载更多 + 游标不前进检测）
- hazards: 客户端检测 `next_cursor == 请求 cursor` 视为分页卡死报错，Java 的游标必须单调前进；mmorpg 用 SCAN 在线人多时每页成本高、且同一玩家可能跨页重复——Java 换实现时客户端可见形状保持即可。

### friend-sweep — 申请终态行与零好友容量行的定期清理
- mmorpg: `go/friend/internal/logic/sweep.go`、`data/sweep_repo.go`
- client messages: none
- tables: none（配置 Friend.Sweep{Mode=report_only|delete, Interval=5m, RetentionDays=7, BatchLimit=1000}）
- depends on: friend-relation-store
- behavior: 运维可见：默认 report_only 只统计、打 WARN、刷 gauge（`friend_sweep_pending_rows`、`friend_sweep_idle_capacity_rows`），不删；delete 模式删除 accepted/rejected 且 updated_ms 超过保留期的申请行、以及 friend_count=0 且 created_ms 超保留期的容量行。pending 永不删。玩家可见影响：7 天后"被拒记录"消失（目前无接口展示，实际不可见）。
- internal: 多副本各跑各的、启动随机抖动、单轮预算 30s；逐行按主键自动提交删除（不批量 DELETE，防与写路径 ABBA 死锁），DELETE 的 WHERE 复核终态与截止点；updated_ms=0 的终态行只统计不删。
- java: missing
- size: S
- robot: none
- hazards: Java 用 Spring `@Scheduled` 实现时同样不要引入 leader 选举；Java 新库无存量，updated_ms=0 保险可简化但保留"默认只报告"开关。

## 聊天（go/chat v1）

### chat-send — 发言（世界频道 + 私聊；校验、幂等、限速、落历史）
- mmorpg: `go/chat/internal/logic/chat_logic.go` SendChat、`internal/session/session.go`、`etc/chat.yaml`
- client messages: 61 ClientPlayerChatSendChat (C2S)：`SendChatRequest{message: ChatMessage{sender_player_id, target_player_id, channel, content, send_time_ms}, request_id}`
- tables: none（配置 MaxContentBytes=512、RateLimitPerSecond=5、HistoryMaxEntries=200、HistoryTTLSeconds=604800、RequestIdTTLSeconds=60）
- depends on: social-backend-routing
- behavior: 顺序：无会话 1005 → message 缺失 1005 → 频道：WORLD 可发（target 清零）、PRIVATE 需 target≠0 且≠自己（否则 1005）、TEAM / SYSTEM / UNSPECIFIED → 1006、未知枚举 → 1005 → 内容原始字节 >512 → 1010，trim 后为空也是 1010 → request_id >64 字节 1005 → 幂等（request_id 非空时 `SET NX EX 60` 占成 `pending:<token>`；已是 done → 直接回成功、不重复写；仍 pending → 1008 让客户端稍后重试）→ 限速每人每秒 5 条（超出 1008，并释放幂等键）→ 写历史 → 幂等键置 done。sender 由会话覆盖、send_time_ms 由服务端盖、content 存 trim 后的值。成功后**不推送给任何人**（世界 / 私聊对方都要自己拉）。gate 第一道限频（MessageLimiter，缺省 3 次/秒/消息号）实际比 chat 的 5/s 更紧。
- internal: 全部在私有 ChatRedis（Cluster 安全，单 key 操作）：`chat:{world}:log`（全服唯一，跨 zone 共享）、`chat:{p:<小id>:<大id>}:log`（私聊双方同 key）、`chat:{req:<pid>}:<rid>`、`chat:{rl:<pid>}`；写历史 Lua `LPUSH + LTRIM 0..199 + EXPIRE 7d`；释放 / 置 done 用"值等于本次 token 才改"的 Lua，独立 1s 超时。go-zero Stat 日志屏蔽 SendChat 请求体（含私聊正文，不得落日志）。Java：Redisson + 同语义 Lua，键经 RedisKeys。
- java: missing
- size: M
- robot: mmorpg chat-smoke 第 2、4、5（600 字节 → 1010）、6（同 request_id 重发去重）步；robot AI `chat` 动作（`robot/logic/ai/robot_ai.go` sendChat，chatter profile 权重 35）
- hazards: ① 无敏感词过滤、无禁言、私聊**不检查黑名单 / 好友关系 / 目标是否存在**；② 写入 Lua 超时但实际落库时客户端重试会产生重复（设计接受"重复优于丢失"）；③ 空内容回 1010（"消息包太大"）文案不贴切但属契约；④ Unity 客户端 `SocialClient.Send` 只开放 WORLD，本地限 1–120 字，把 1005/1006/1008/1010 视为"确定拒绝"、1003 视为"可能已落库需重连确认"。

### chat-history — 拉取频道历史
- mmorpg: `chat_logic.go` PullChatHistory / resolveLogKey / clampHistoryLimit
- client messages: 28 ClientPlayerChatPullChatHistory (C2S)：`{channel, peer_player_id, limit}` → `{error_message, repeated ChatMessage messages}`
- tables: none（HistoryDefaultLimit=20、HistoryMaxLimit=50）
- depends on: chat-send
- behavior: 频道与 key 解析同 SendChat（PRIVATE 需 peer≠0 且≠自己，TEAM / SYSTEM 1006）；limit=0 取 20、>50 钳到 50；返回最近 N 条、按 send_time_ms 降序（新在前，同毫秒稳定保持写入序），无游标 / 无增量；单条坏数据跳过。私聊只能拉"自己与 peer"的会话（key 由会话身份 + peer 决定）。历史 Redis 重启即丢，7 天无新消息过期——这也是 mmorpg 唯一的"离线消息"形态。
- internal: `LRANGE key 0 limit-1`。
- java: missing
- size: S
- robot: mmorpg chat-smoke 第 3、4、6 步
- hazards: 客户端每次发送成功后立即 Refresh(Pull 50 条)，没有推送时多人聊天全靠轮询；世界频道是全服（非按 zone）的一条 LIST，合服 / 多区无影响但量大时 200 条窗口很快滚完。

### chat-extra-channels — 队伍 / 系统 / 帮会 / 当前频道与实时下发
- mmorpg: `chat_logic.go` resolveLogKey（TEAM / SYSTEM 回 1006）；`proto/chat/chat.proto` 枚举无 GUILD / CURRENT；客户端 `SocialState.ChatChannels = {Current, World, Guild, Team}` 只有 World 接线
- client messages: 61 / 28 带 channel=TEAM(2) / SYSTEM(4) → 1006
- tables: none
- depends on: chat-send, 队伍成员关系（go/team）、帮会成员关系、player-push-channel
- behavior: mmorpg 当前对 TEAM / SYSTEM 一律 1006；无帮会频道、无当前（场景）频道、无系统公告下发、无聊天推送。
- internal: 若将来做，需要成员关系查询与广播推送（BroadcastToPlayers）。
- java: not_applicable — mmorpg 未实现；Java 只需在 chat-send / chat-history 里对这些频道回 1006 保持一致
- size: L
- robot: none
- hazards: 客户端 UI 已有 Current / Guild / Team 页签但发送前本地拦截"此频道服务尚未开放"，两版都不能静默接受这些频道的写入。

### chat-sensitive-word-filter — 聊天敏感词过滤
- mmorpg: `docs/design/chat-sensitive-word-filter.md`（C++ 原语 `sensitive_word_filter.h` 已落，未接任何聊天链路；go/chat 不调用）
- client messages: 61（将来）
- tables: none（词表按部署 / 语种外置，未定）
- depends on: chat-send
- behavior: mmorpg 当前**不过滤**任何内容。设计口径：按 UTF-8 字节子串匹配 + ASCII 大小写折叠，命中拒绝或打码。
- internal: 若做，Java 可在 chat-send 纯校验阶段接入（Aho-Corasick 库需按 §2 选型规则查 star）。
- java: not_applicable — mmorpg 链路未接线
- size: S
- robot: none
- hazards: 若一版先做，拒绝码 / 打码行为是客户端可见差异，必须两版同批。

## 聚宝斋 / 交易（go/trade）

### trade-browse-listings — 聚宝斋浏览（公示 / 寄售列表，筛选、搜索、排序、分页、市场范围）
- mmorpg: `go/trade/internal/logic/jubaozhai_logic.go` BrowseListings、`logic/phase.go`、`logic/home_zone.go`、`data/listing_repo.go`、`proto/trade/trade_table.proto`、`etc/trade.yaml` Market 段
- client messages: 196 ClientPlayerJubaozhaiBrowseListings (C2S)
- tables: none（配置 Market{Scope=zone|global, DefaultPageSize=20, MaxPageSize=20, MaxPage=100}；子类上限写死在 constants.MaxSubcategory：角色 4、宠物 6、武器 5、防具 5、召唤令 2、其余 0）
- depends on: social-backend-routing, player-profile-lookup（home zone）
- behavior: 校验（任一失败 1005）：无会话；search trim 后 ≤64 字符且无控制字符；tab 只能 1 公示 / 2 寄售；section 只能 1 寄售 / 2 拍卖（0 也非法）；category 必填且 subcategory ≤ 该类上限；sort 0–4。section=拍卖 → 20003。zone 范围：分区 = 调用者 home zone（忽略 zone_filter；home zone 未知 20001、查询失败 1003）；global：zone_filter 0=全部。过滤：公示 tab = status LISTED 且 notice_end>now；寄售 tab = status LISTED/LOCKED 且 notice_end≤now<sale_end；search 为纯数字时 `listing_id = n OR title LIKE %s%`（LIKE 用 `!` 转义）；favorites_only 只看自己收藏。排序：0 listing_id DESC；1 价格升（id 升）；2 价格降（id 降）；3 等级降（id 降）；4 剩余时间升（公示 tab 按 notice_end、寄售按 sale_end）。分页：page_size 0→20、>20→20；page 0→1、超末页→末页、再封顶 100；page_count≥1。响应 `{listings, total_count, page, page_size, page_count, market_scope, server_now_ms}`；ListingSummary 带 phase（服务端用同一个 now 推导）、is_favorite、is_mine，**不带卖家 id**。
- internal: 独占库表 trade_listing（三条联合索引：本区浏览 / 全服浏览 / 卖家货架）、trade_favorite；COUNT + LIMIT/OFFSET 分页；状态只存 LISTED，公示 / 寄售 / 结束由时间推导（不需要后台改状态）。Java：xm_java 下同语义表 + MyBatis 动态 SQL（ORDER BY 只能来自固定映射）。
- java: missing
- size: M
- robot: mmorpg trade-smoke 第 3–6 步（zone / global 两种 scope 各跑一次）
- hazards: Market.Scope 是进程级配置，一次只能验一种；`total_count` 超 uint32 钳顶；OFFSET 分页在大表上慢（MaxPage=100 封顶缓解）；客户端类目值 = 服务端枚举值 − 1，子类编码两边任一改动必须同步。

### trade-listing-detail — 商品详情（可见性判定）
- mmorpg: `jubaozhai_logic.go` GetListingDetail / loadVisibleListing、`phase.go` VisibleToBuyer
- client messages: 197 ClientPlayerJubaozhaiGetListingDetail (C2S)
- tables: none
- depends on: trade-browse-listings
- behavior: 无会话 / listing_id=0 → 1005；不存在 → 20000；卖家本人始终可见（任意状态）；其他人只在 status ∈ {LISTED, LOCKED} 且 now < sale_end 且（global，或 zone 范围下 market_zone == 调用者 home zone）时可见，否则一律 20000（不泄露别区商品）；zone 范围下非卖家查 home zone（未知 20001）。响应 `{detail{summary, description}, server_now_ms}`，含 is_favorite。
- internal: 主键单查 + 收藏存在性查询。
- java: missing
- size: S
- robot: mmorpg trade-smoke 第 7 步
- hazards: 公示期商品对买家可见（只是不可买），寄售期满即不可见。

### trade-favorite — 收藏 / 取消收藏
- mmorpg: `jubaozhai_logic.go` SetFavorite、`data/listing_repo.go` Insert/Delete/Count/FavoriteExists
- client messages: 198 ClientPlayerJubaozhaiSetFavorite (C2S)：`{listing_id, favorite}` → `{error_message, listing_id, favorite}`
- tables: none（Market.MaxFavoritesPerPlayer=100）
- depends on: trade-listing-detail（同一可见性规则）
- behavior: 无会话 / listing_id=0 → 1005（错误响应也回填 listing_id）；取消：直接删，幂等，不查商品（已下架的收藏也能删），回 favorite=false；收藏：商品须对调用者可见（否则 20000 / 20001），已收藏直接回 true，否则收藏数 ≥100 → 20002，写入后回 true。
- internal: 软上限（Count 与 Insert 之间无锁，并发可略超）；InsertFavorite 幂等。
- java: missing
- size: S
- robot: mmorpg trade-smoke 第 8 步（收藏 → favorites_only 浏览可见 → 取消）
- hazards: 客户端在 favorites_only 模式下收藏状态变化后会重新浏览；gate MessageLimiter 对 198 配 5 次/秒（196/197/200 为 10 次/秒），Java 同表自动生效。

### trade-my-shelf — 我的货架
- mmorpg: `jubaozhai_logic.go` GetMyShelf、`listing_repo.go` CountSellerListings / QuerySellerListings
- client messages: 200 ClientPlayerJubaozhaiGetMyShelf (C2S)：`{page, page_size}` → `{listings, total_count, page, page_size, page_count, server_now_ms}`
- tables: none
- depends on: trade-browse-listings
- behavior: 返回调用者自己上架的全部商品（任意状态，含已结束），listing_id 降序（新在前），分页规则同浏览，不查 home zone、不受市场范围约束；is_mine 恒 true。无会话 → 1005。
- internal: 按 (seller_player_id, listing_id) 索引。
- java: missing
- size: S
- robot: mmorpg trade-smoke 第 9 步
- hazards: P3 计划在货架里追加"我的订单"，目前没有。

### trade-seed-listing — 内部造商品（dev / test 联调与冒烟）
- mmorpg: `go/trade/internal/logic/admin_logic.go` SeedListing、`proto/trade/trade_admin.proto`、`go/trade/internal/svc`（listing_id 号段 biz_tag=trade_listing）
- client messages: 199 TradeAdminSeedListing（内部服务，非客户端协议；经 gate 发来必须被丢弃 / 拒绝）
- tables: none
- depends on: player-profile-lookup（卖家 home zone）、ID 发号
- behavior: 运维 / robot 可见：仅 Mode=dev|test 可用，否则 gRPC PermissionDenied；参数校验（卖家≠0、类目子类合法、标题非空 ≤64 字、摘要 ≤128、描述 ≤512、无控制字符、icon_key 只含 [a-z0-9_] ≤64、等级 ≤1000、价格 1..1e10 分、寄售时长 1ms..90 天、公示 ≤30 天）失败 in-band 1005；卖家 home zone 未知 20001；写入 status=LISTED、market_zone=seller_zone_at_listing=卖家 home zone、notice_end=now+公示时长、sale_end=notice_end+寄售时长，不移动任何资产。
- internal: Java 可做成仅开发 profile 启用的 Dubbo 管理接口（同 Dubbo 调用方鉴权），发号用 Java 自己的 ID 方案。
- java: missing
- size: S
- robot: mmorpg trade-smoke 第 1 步（gRPC 直连造三条商品）、第 2 步（经 gate 发 199 必须超时）
- hazards: P1 没有下架流程，冒烟种子商品逐轮累积，断言只按本轮 nonce 找。

### trade-asset-escrow-channel — 资产托管通道（trade 侧 outbox / seq / 重投循环，P2）
- mmorpg: `go/trade/internal/reconcile/pipeline.go`、`go/trade/internal/svc/assetchannel.go`、`go/trade/internal/data/asset_op_repo.go`、`proto/trade/trade_table.proto`（trade_player_op_seq、trade_asset_op）、`go/shared/assetop`、`go/shared/scenenode`；scene 侧账本属另一区域
- client messages: none（无客户端入口；`EnqueueEscrowDebit` 全仓无生产调用方）
- tables: none
- depends on: scene 资产账本 RPC（AssetDebit / AssetCredit / AssetAbortDebit + 按 (stream, caller) 的 HMAC 签名）、玩家位置键 `player:{id}:location`、货币 / 背包 / 宠物系统、ID 发号（biz_tag=trade_asset_op）
- behavior: 当前对玩家不可见：配置 `AssetOp.Enabled=false`，设计文档要求在签名扩展与对账闸门完成前任何共享环境不得开启；开启时密钥缺失 / 短于 32 字节拒启动（fail-closed）。语义：业务事务内分配每玩家每流 seq（带纪元，库恢复后整流重置）并写 outbox 行，提交后同步投递一次，后台循环（2s 周期、100 批、8 worker、10s 租约、退避 1s..60s、单次 RPC 800ms）重投直到拿到 scene 已落盘的终局（APPLIED / REJECTED / ABORTED / APPLIED_PARTIAL→人工）；超截止改发 AbortDebit；人工终结留痕（resolved_by / resolve_reason）。v1 只支持游戏币托管。
- internal: Java 版尚无货币 / 背包 / 宠物，也无 scene 资产账本；需整体设计（Java 可用 owner_epoch 围栏下的 scene 内事务 + 发件箱），不照抄 seq 账本结构。
- java: missing — 前置（scene 资产系统）在 Java 全部缺失
- size: XL
- robot: none（mmorpg 也无端到端冒烟；只有 Go 单测与 MySQL 集成测）
- hazards: 设计文档标注 P2 "已落码、未编译"（2026-09-18/19）；正确性证明依赖 scene 侧 1024 seq 窗口与这些常量，改参数前先改证明；资产路径一律 fail-closed。

### trade-p3-orders-payment — 上架 / 下架 / 下单 / 支付 / 交付 / 拍卖 / 角色交易（P3–P6）
- mmorpg: 仅设计 `docs/design/jubaozhai-market.md` §5、§7、§9（规划 CreateListing / CancelListing / CreateOrder / GetOrder、MockConfirmPayment、20 余个 Trade* tip 码），代码未开工；拍卖分区当前回 20003
- client messages: 尚未分配消息号
- tables: 规划 Item / Pet 表加 tradable / market_category / market_subcategory 列（未加）
- depends on: trade-asset-escrow-channel、账号角色持久化（P0-b）、支付渠道
- behavior: 当前客户端可见的只有：拍卖分区 → 20003（客户端显示"拍卖尚未开放"）、货架只有商品没有订单、客户端无上架界面。
- internal: 订单状态机、mock 支付、卖家入账冷静期、到期回退扫描、合服冻结。
- java: not_applicable — mmorpg 未实现（Java 只需保持拍卖分区回 20003）
- size: XL
- robot: none
- hazards: 真钱交易上线闸（GM 客户端消息鉴权 P0-a、账号数据持久化 P0-b）两版都要过。

### trade-merge-zone-rewrite — 合服时改写商品市场分区
- mmorpg: `tools/merge_zone/trade_step.go`（步骤 3b / 撤销 3b' / verify:trade_listing）、`jubaozhai-market.md` §4
- client messages: none（合服后 196 / 197 的 zone 范围结果变化）
- tables: none
- depends on: trade-browse-listings、合服工具（Java 无）
- behavior: 运维可见：按合服清单里的 listing_id 分批 `UPDATE trade_listing SET market_zone=dst WHERE market_zone=src AND listing_id IN (…)`；改写后源区仍有剩余则中止；seller_zone_at_listing 不改；trade 库缺失时合服 fail-closed（显式 `-skip-trade-mysql` 才跳过）；支持撤销。玩家可见：合服后原两区商品互相可见。
- internal: 依赖 home zone 改写（玩家 zone 归属变化）与清单落盘。
- java: missing — Java 版无合服（architecture.md §10 列为后续批次）
- size: S
- robot: none
- hazards: 不能整区 UPDATE（清单落盘后新进源区的商品会被改写却不在清单里，撤销找不回）。

## Open questions

1. **Java 后端拆分**：friend + chat 是否合进一个 `xm-social` 进程、trade 单独 `xm-trade`？两者都要求"全局一份、多副本、无状态"，Java 现有进程都是按 zone 部署的（gate / scene），需在 architecture.md 定全局服务的部署形态与 Dubbo 发现方式。
2. **推送通道选型**：Java 无 Kafka。friend 推送（以及将来的 team / guild / mail 推送）用什么通道到 gate——Redis pub/sub 按 gate 节点分频道、还是 Dubbo 直调 gate？需与在线目录（presence）一起定，且必须跨 zone。
3. **在线目录归属**：mmorpg 的写者是 player_locator（不在本区域）。Java 由 gate、login 还是 scene 写？在线目录分页（online_only）需要"按 zone 枚举在线玩家"，键结构要一并设计。
4. **AddFriend 目标存在性**：mmorpg 不校验 target 是否存在（对不存在的 id 也回成功并建容量行）。Java 有玩家表可校验，但校验失败回什么码（15xxx 里没有"玩家不存在"）？若改要两版同批加 tip。
5. **聊天与黑名单 / 禁言 / 敏感词**：mmorpg 私聊不读 friend 黑名单，也没有禁言与敏感词。是否作为两版共同的待做项登记到 PARITY？
6. **离线消息**：mmorpg 唯一的"离线可见"是 chat 历史（Redis 7 天 / 200 条，重启即丢）与 friend pending 列表；推送 at-most-once 无补发；邮件只有设计稿（`docs/design/mail-system.md`，标注"未落码"）。Java 是否照此（历史放 Redis、可丢），还是借 Java 的 MySQL 做持久化（客户端不可见，属内部差异）？
7. **面对面玩家交易**：mmorpg 不存在 C2C 实时交易（无 proto、无代码），聚宝斋是唯一交易形态，本清单因此未列。
8. **聚宝斋 P2 状态**：设计文档称资产通道"已落码、未编译"，代码在 HEAD 上存在但 `AssetOp.Enabled=false`；Java 何时做取决于货币 / 背包 / 宠物在 Java 版的进度，建议在 PARITY 登记为"mmorpg 进行中"而不是"Java 待做"。
9. **ID 发号**：trade 的 listing_id 来自 data_service 号段（biz_tag=trade_listing）。Java 有雪花 ID；listing_id 客户端可见（详情、数字搜索按编号精确匹配），雪花 ID 位数大但仍是 uint64，是否可接受需确认（客户端按编号搜索时输入长度会变长）。
10. **friend 推荐普通模式不填展示资料**：RecommendEntry 的 name / level 等只在在线目录模式填，普通推荐只给 id。Unity 目前不用普通推荐，Java 照搬还是顺手补齐（客户端可见差异，需两版同批）？
11. **chat 的 gate 限频与服务限速叠加**：gate MessageLimiter 对 61 的档位决定了实际可发速率（缺省 3 次/秒 < chat 的 5 次/秒）；需确认表里 61 / 28 的实际配置后再定 Java 侧 chat 限速是否还有意义（为了对齐仍应保留）。
