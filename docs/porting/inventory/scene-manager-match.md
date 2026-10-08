# 功能清单：Go scene_manager + match + team + battle（mmorpg 26ceb70ca）

> 范围：`go/scene_manager/**`、`go/match/**`（组队实现在 `go/match/internal/team`，`go/team` 与 `go/battle` 只有生成的消息号文件）、
> `proto/{scene_manager,match,battle,team}/**`；顺带涉及 C++ scene 侧的调用方（`player_scene*.cpp`、`player_activity_schedule.cpp`）。
> Java 对照：`xm-scene-manager`、`xm-scene`、`xm-gate`、`xm-login`、`xm-player-store`（commit 7ab4d9b 工作区）。

## 区域概述

Go scene_manager 是**中心化的场景调度器**：它自己用 snowflake 发 scene_id，按 World 表 × 频道数在各 zone 的「主世界」节点上
铺大世界频道（FNV 哈希选节点），用 Redis 维护 scene→node、频道集合、每场景 / 每节点人数与负载 ZSET，并通过 etcd list-watch
感知 C++ scene 节点存亡；进场（EnterScene）时原子预占人数最少的频道、铸造 owner_epoch、CAS 写 `player:{id}:location`，
再经 Kafka `GateCommand`（RoutePlayerEvent / RedirectToGateEvent）通知 gate 改路由或把客户端重定向到别的 zone（msg 124）。
它还负责镜像副本的创建 / 空闲回收、死节点的再入屏障与频道改派、频道再平衡 / 自动扩缩容、Agones 容量预占与选主。
match 进程承载回合制战斗的全部「开局」：排队（PVE 单人 / PVE 组队 / 1v1 / 5v5）、Elo 凑单、切磋、观战、票据补签、帮会活动开战，
统一经 gather 管线（scene.PrepareBattle 冻结快照 → battle.CreateBattle），并同进程承载组队服务（Redis Lua CAS 版本化名册 + 推送）。
Java 版目前只有 scene-manager 的「无状态选场景」：每个 scene 节点启动时为 World 表每行建一个场景、每 5s 把人数写进 Redis 节点目录，
`SceneAssigner` 挑人数最少者；没有中心位置记录、没有跨节点换图 / 跨区重定向 / 副本、没有 Kafka，match / team / battle 的客户端消息
在 Java gate 一律路由到 `unsupported` 并回 `MessageContent.error_message{kServiceUnavailable}`（`MessageRoutes.SERVICE_BACKENDS` 只有 login）。

## 功能

### sm-node-registry-load — 场景节点注册发现与负载评分
- mmorpg: go/scene_manager/internal/logic/load_reporter.go、node_selection.go、scene_node_client.go、noderegistry/registry.go、constants/scene_type.go
- client messages: none
- tables: none
- depends on: 无（基础设施）
- behavior: 客户端不可见。节点角色 scene_node_type：0 主世界 / 1 副本 / 2 跨服主世界 / 3 跨服副本；StrictNodeTypeSeparation=true 时某用途无匹配节点即 ErrNoNodeForPurpose(12)，false 时回落全池。负载分 = 1.0·scene_count + 0.01·player_count，越低越优；GetBestNodeForPurpose 取 ZSET 最低分者。
- internal: etcd 前缀 `SceneNodeService.rpc/` list-watch（全量 + 增量 + watch 断开重同步）镜像到内存 knownNodes；领导者把分数写 `scene_nodes:zone:{z}:load` ZSET、角色写 `node:zone:{z}:{n}:scene_node_type`，每 5s 刷新；计数键 `node:zone:{z}:{n}:{scene_count,player_count}`；IsNodeAlive = ZSCORE 命中（Redis 出错按存活处理）；同 (zone,node_id) 多条注册视为身份歧义一律拒用；到 C++ 节点的 gRPC 连接按 (zone,node) 缓存，节点消失即关闭。Java 需要：节点目录（已有）+ 角色字段 + 可组合的负载分（若采用中心化分配）。
- java: partial — xm-discovery `NodeDirectory`（Redis，5s 写、TTL 15s）+ scene `SceneDirectoryPublisher` 上报 `SceneNodeInfo{scenes[], player_count}`；`SceneAssigner.isUsable` 只挡坏条目。缺：节点角色（主世界 / 副本）、scene_count 维度、身份歧义检测、领导者写分（Java 由各节点自报，不需要）。
- size: M
- robot: robot_smoke（间接）
- hazards: IsNodeAlive 在 Redis 故障时 fail-open（按存活），而死节点判定 fail-closed，两者方向相反是有意的（宁可不改派）；跨 zone 的 node_id 不唯一，凡比较节点必须带 zone；`readNodeSceneType` 读不到时节点被当作「未分类」而进入任意用途池。

### sm-world-channel-provisioning — 大世界频道铺设（多频道 / 分线）
- mmorpg: go/scene_manager/internal/logic/world_init.go、createscenelogic.go（createMainWorldScene）、world_autoscale.go（DesiredWorldChannelCount）、etc/scene_manager_service.yaml（WorldChannelCount / WorldChannelCountByConfId）
- client messages: none 直接；结果体现在 79 NotifyEnterScene 的 scene_id 与 43/31 场景信息
- tables: World（scene_id 列 = BaseScene id，表序第一行为默认大世界）
- depends on: sm-node-registry-load
- behavior: 每个 zone × World 表每张图铺 N 个频道（默认 1，按 conf 覆盖；dev 配置图 1 = 16 个频道），节点 = FNV32a(conf*1000+i) % 排序后的主世界节点；新节点出现 / fullSync / 领导者切换时补齐缺的频道；CreateScene(MAIN_WORLD) 找不到频道时按需铺设。
- internal: scene_id 由 snowflake（etcd worker 租约，失租先 Fence 再退出）发；Redis：`scene:{id}:node`、`scene:{id}:zone`、`world_channels:zone:{z}:{conf}` SET、`instance:{id}:player_count`、`node:zone:{z}:{n}:scenes` SET；每 zone 初始化锁 `world_init:lock:zone:{z}`（SETNX 60s，按 token 释放）；gRPC SceneNodeGrpc.CreateScene(config_id, scene_id) 让 C++ 节点建实体，5s 超时，不可达节点本轮剔除并对已判死的改派重试。
- java: partial — 场景由 scene 节点自建：`SceneNode` 启动时 `tables.worldSceneConfigIds().forEach(sceneWorld::createScene)`（每节点每张世界图恰好 1 个场景，scene_id 由节点本地发号器出），scene-manager 只读目录。缺：每图多频道、频道数配置、中心发号、按需铺设。
- size: M
- robot: robot.stress-*（多频道下 scene_switch）
- hazards: 期望频道数一旦启用自动扩缩容就以 Redis `world_desired_channels` 为准，静态配置只是种子（否则缩容会被立即补回）；锁 TTL 60s 内 CreateScene RPC 卡住会让互斥失效（只打日志）；`CleanupOrphanChannelsOnStartup` 在 World 表为空时必须拒绝运行，否则清空全部频道。

### sm-enter-scene-allocation — 进场选场景与原子预占（负载均衡）
- mmorpg: go/scene_manager/internal/logic/enterscenelogic.go（resolveSceneForEnter / resolveScene / defaultWorldConfID）、scene_atomic.go（luaReserveBestWorldChannel / luaAtomicIncrPlayerCount）、world_init.go（ReserveBestWorldChannelForEnter / GetBestWorldChannel）
- client messages: none 直接（login 26 EnterGame 的下游；失败时客户端收 23 {3023}）
- tables: World
- depends on: sm-world-channel-provisioning、sm-node-registry-load
- behavior: scene_id≠0 → 查 `scene:{id}:node`，场景所属 zone 与请求 zone 不符即拒；scene_id=0 → 用 scene_conf_id，缺省 = World 表第一行；在该图的活频道里**一条 Lua 原子比较 + INCR** 人数最少者（同时 INCR 节点人数），频道全部落在死节点时懒改派到活节点并同步 CreateScene；解析不出 → ErrNoAvailableNode(1)，再入屏障未到 → ErrSceneReentryBarrier(17，可重试)；任何后续拒绝都要成对 DecrInstancePlayerCount。
- internal: 业务码是 scene_manager 私有 1..21（与 tip 码数轴重叠，只在服务间用）；request_id 幂等：`enter_scene:dedup:{player}:{request_id}` 存 pending:{fingerprint}:{uuid} → 成功后 CAS 覆盖为 done:{fingerprint}:{base64 响应}，TTL 60s，指纹排除 correlation_id；应答回显 player_id 与 correlation_id。Java 需要：带预占的分配（防 5s 上报窗口内的突发扎堆）或接受现状。
- java: partial — `SceneAssigner.assign`：preferred_scene_config_id 是世界图且有承载 → 用它，否则默认世界图；挑 player_count 最小（并列取节点号 / 场景号小者）；无场景回 tip 3000、zone_id=0 回参数错。**不预占**（javadoc 已登记风险）、不支持按 scene_id 进指定场景、不跨 zone、无幂等键（Java 分配无副作用，不需要）。
- size: M
- robot: robot_smoke、robot.stress-*（登录进场）
- hazards: 预占发生在解析阶段，之后的每个拒绝出口（换手门 18、归属查询失败、epoch 冲突、Kafka 失败）都必须退还，Go 有多处成对释放，漏一处即人数漂移；同落点重连（samePlacement）先预占再退还；`DecrInstancePlayerCount` 用 INCRBY -1 后负数改 0，非原子，可短暂为负。

### sm-player-location — 玩家位置权威记录与离场
- mmorpg: go/scene_manager/internal/logic/changesceneutil.go（placePlayerLocation）、leavescenelogic.go、proto/scene_manager/storage.proto（PlayerLocation）；读者：go/match/internal/playercontract、go/login、C++ scene
- client messages: none
- tables: none
- depends on: sm-enter-scene-allocation、sm-owner-epoch-handoff
- behavior: 客户端不可见，但组队开战 / 排队（ErrNotInScene）/ 切磋 / 组队推送都以「有没有 location」判断玩家是否在场景。LeaveScene(player, scene)：location 不在该 scene → 忽略；否则 Lua「原文未变才 DEL 并扣场景 / 节点人数」，结果 deleted / already_gone / superseded；读 Redis 失败返回 gRPC 错误让调用方保留并重试。
- internal: Redis `player:{id}:location` = PlayerLocation pb{scene_id,node_id,update_time,zone_id,owner_epoch,pending_scene_conf_id,rollback_receipt}；写只经 EnterScene 的 epoch CAS Lua；node_id 为空且 epoch≠0 = 「跨 zone 等待落点」。Java 需要：一个「玩家当前在哪个 scene 节点 / 场景」的可查询目录，供 match / team / 跨节点换图使用。
- java: missing — Java 没有中心位置记录：gate 会话里绑定 (scene 节点, 场景)，scene 内存持有玩家，归属在 MySQL（owner_epoch / owner_released / owner_lease_until，`PlayerStore`）。别的服务无法查「玩家在哪个节点」。
- size: M
- robot: none 直接（team_smoke / battle_smoke 间接依赖）
- hazards: LeaveScene 只比 scene_id 不比 epoch，靠「原文逐字节相同」防误删新落点；读失败必须报错而非当作「没有位置」，否则调用方会释放自己的认领；location 被合服 / 下线 zone 留下时由 EnterScene 的 playerLocationOwnerGone 视为不存在。

### sm-owner-epoch-handoff — 归属 epoch 铸造、换手门与回滚
- mmorpg: go/scene_manager/internal/logic/owner_epoch.go、changesceneutil.go、enterscenelogic.go（requireHandoffCommitted / sameNodeZeroMint）、go/shared/ownerepoch
- client messages: none
- tables: none
- depends on: sm-player-location
- behavior: 只在「持有者真的换了」时铸造（首次落点、过了换手门的跨节点交接、跨 zone 放行、等待落点第二条腿；同节点 epoch=0 的存量号例外）；跨节点 / 跨 zone 离开必须先有源 scene 写的 `player:{id}:handoff` 标记 "{epoch}:{saved_at_ms}"，没有 → ErrHandoffPending(18，可重试，源 scene 收到后冻结 → 存盘 → 写标记 → 重发)；并发推进 → ErrOwnerEpochConflict(19)。dev 旁路 AllowUnsafeCrossNodeHandoff=true 无标记放行且不铸造。
- internal: Lua luaMintEpochAndSetLocation（CAS + INCR + 写 location + 标记复核 + go-redis 重发识别返回 -cur）、luaSetLocationIfEpoch、luaRollbackPlayerPlacement（推路由 / 重定向失败时 location 退回、epoch 只进不退 N+1→N+2 并写回滚回执、标记转写）。新 epoch 随 RoutePlayerEvent.owner_epoch 下发，C++ 存盘用 Lua 比对拒绝旧 epoch。
- java: done — Java 内部重做（PARITY「玩家数据归属」行）：`xm-player-store` 的 owner_epoch + owner_released + 30s 租约，login 夺权、scene 续约 / 写回带 epoch 围栏、顶号经 Redis pub/sub 请持有者让出。覆盖首次进场 / 重进 / 顶号；**跨节点换图与跨 zone 交接的「源先落盘再放行」尚无对应**（见 sm-cross-node-scene-switch）。
- size: L
- robot: currency_crash_window、travel_smoke（基线）；Java robot 无
- hazards: 同节点换图若铸造，持有节点要等 Kafka 绕一圈才知道新值，窗口内的周期存盘被拒 → 合法持有者被当废黜踢人回档（所以同节点不铸造）；epoch 键被 allkeys-lfu 单独淘汰而 location 仍记 N 时必须按 N 补种不铸造；回滚凭回显的 owner_epoch_after_rollback 采纳会造成双持有（只能凭 location 里的回滚回执）。Java 若引入跨节点换图，必须给出同等的「旧节点已落盘」凭据，不能只靠租约。

### sm-cross-node-scene-switch — 跨节点换图编排
- mmorpg: go/scene_manager/internal/logic/enterscenelogic.go（crossNodeHandoff / dispatchReleasePlayer / routePlayerToGate）、scene_node_client.go（RequestNodeReleasePlayer）；C++ cpp/nodes/scene/handler/rpc/player/player_scene_handler.cpp（EnterSceneC2S）、player_lifecycle.cpp（RequestSceneChange / StartTravelHandoff）
- client messages: 63 SceneSceneClientPlayerEnterScene (C2S)；79 NotifyEnterScene (S2C push，目标节点进场后)；21/47/51 视野（新旧场景）；23 失败提示
- tables: World、BaseScene
- depends on: sm-enter-scene-allocation、sm-owner-epoch-handoff、sm-player-location、sm-gate-command-channel
- behavior: 63 校验顺序：换图在途 → kEnterSceneChangingScene；三个 id 全 0 → 3001 ParamError；scene_id == 当前 → kEnterSceneYouInCurrentScene；无 scene_manager → kServiceUnavailable；之后 scene 发 EnterScene(gate_id、gate_instance_id、correlation_id)，目标在别的节点时生产配置先回 18 → 源 scene 冻结玩家、存盘、写 handoff 标记后重发 → scene_manager 铸 epoch、向旧节点发 ReleasePlayer（存盘、销毁实体、放会话映射）、CAS 写 location、经 Kafka 推 RoutePlayerEvent 给 gate，gate 把会话切到新节点，新节点从 Redis 载入并下发 79。同节点换图只做 epoch 不变的 CAS、不发 ReleasePlayer。
- internal: SceneNodeGrpc.ReleasePlayer(player, target_scene, target_node)（异步，失败只记日志）；RoutePlayerEvent{session_id,target_node_id,scene_id,player_id,home_zone_id,owner_epoch} 包在 GateCommand(target_instance_id = gate UUID)；推送失败走单调回滚并回 ErrKafkaRoute(7)。Java 需要：scene→scene-manager 的换图请求、旧节点写回释放凭据、gate 换绑场景节点的指令通道（Java 可用 Dubbo / 节点链路代替 Kafka）。
- java: missing — `ClientRequestHandler.enterScene` 只在本节点内换场景（`SceneWorld.resolveSwitchTarget` 在本节点同图选人数最少的场景），目标不在本节点 / mirror 请求一律回 3023 kEnterSceneFailed（javadoc：「镜像场景与跨节点换场景首批不做」）。
- size: L
- robot: robot.stress-*（AI scene_switch，robot_ai.go:207 发 63）、team_smoke S6；Java robot 无
- hazards: AllowUnsafeCrossNodeHandoff 在 dev 配置为 true，此时同 zone 跨节点换图拿不到 18、C++ 标记链路从未被触发（配置注释承认该链路「尚未编译、未跑过任何测试」）——基线的 robot 全绿不能证明安全换手；NodeId 只在 zone 内唯一，同节点判定必须两边 zone 明确相等（zone=0 按跨节点处理）；C++ 侧在途记录只有一个槽，靠 correlation_id 对号。

### sm-gate-command-channel — 服务端到 gate 的命令 / 推送通道
- mmorpg: go/scene_manager/internal/logic/changesceneutil.go（GateCommandMessageFor）、go/shared/kafkacmd、go/shared/kafkautil（PushToPlayer）、go/match/internal/playercontract/playercontract.go、go/match/internal/kafka/gate_command_builder.go
- client messages: 承载 124 RedirectToGate、团队 213/215/203、切磋 156/154、战斗大厅公告 177/143 等所有「非 scene 服务主动下发」的 S2C push
- tables: none
- depends on: 会话目录（player_locator 写的 `player:session:{id}`：gate_id、gate_instance_id、session_id、state）
- behavior: 至多一次投递：目标没有 ONLINE 会话（DISCONNECTING 宽限期也算不在线）就不推，客户端靠拉取接口自愈；命令必须带 target_instance_id（gate 进程 UUID），空值 fail-closed 拒发，防止复用 node_id 的僵尸 gate 消费。
- internal: Kafka topic gate-cmd_g<N>，分区 = gate_node_id % P，key = player_id；同步写 RequireOne、超时 KafkaWriteTimeoutSeconds=5s；scene 方向另有 scene-cmd（SceneCommand，团队刷新用）。Java 需要：任意后端服务按 player_id 找到所在 gate 并下发 S2C / 指令（可用 Redis 会话目录 + Dubbo 调 gate，或节点链路），并保留「按 gate 实例号防僵尸」语义。
- java: missing — Java 只有两条下行：login 的 `ClientReply`（随请求应答 + 会话指令）与 scene 经节点链路 `ToClient`。没有会话目录可按 player_id 查 gate，也没有第三方服务向 gate 推消息的接口；无 Kafka（architecture.md §10 首批不做）。
- size: M
- robot: team_smoke、battle_smoke、travel_smoke（基线）
- hazards: 推送与 RPC 结果解耦，失败只记指标；团队推送调用者本人不收（以回包为准）；按 gate_node_id 分区保证同一 gate 内有序，但不同来源（scene 链路 vs Kafka）之间无序——基线客户端用 version / membership_epoch 排序吸收。

### sm-cross-zone-redirect — 跨区重定向与跨 zone 传送（msg 124）
- mmorpg: go/scene_manager/internal/logic/enterscenelogic.go（crossZoneRedirect / handleCrossZoneRedirect / rejectTravelToUnopenedMap / awaitingPlacement）、gate_redirect.go、go/shared/clientendpoint；login entergamelogic.go（RedirectOnEnterEnabled）；C++ TravelToZone
- client messages: 124 SceneClientPlayerCommonRedirectToGate (S2C push, RedirectToGateEvent)；226 SceneSceneClientPlayerTravelToZone (C2S)；之后客户端在新 gate 上重新握手 / 48 Login / 26 EnterGame
- tables: World（目标图必须在目标 zone 有频道）
- depends on: sm-gate-command-channel、sm-owner-epoch-handoff、sm-home-zone-routing、gate 令牌签名（xm-common 已有）
- behavior: gate zone ≠ 目标 zone 时不解析场景、只送连接：在目标 zone 的 gate 里按 player_count 最小挑一台（同地址去重取最新 launch_time；RequireClientEndpoint=true 时没有 client_endpoint 的 gate 跳过），签 GateTokenPayload{gate_node_id, zone_id, expire=now+300s, player_id, target_zone_id}，HMAC-SHA256 hex；只在玩家真的离开所在 zone 时动归属（过换手门、写「等待落点」location{node_id="", zone=目标, pending_scene_conf_id}）；目标图在目标 zone 没频道 / 默认大世界未配置 → ErrNoAvailableNode，在不可回头点之前拒绝；等待落点第二条腿解析失败回落默认大世界；票据过期的等待落点不牵引去向。重连 zone_id=0 时跟随 location 所在 zone（GO-5）。
- internal: 应答 RedirectToGateInfo 同时回给调用方；推送失败单调回滚；GateCommand 带 gate UUID。Java 需要：多 zone 部署、跨区 gate 选择、票据绑定 player_id + target_zone_id、登录侧认票不弹回（CZ-8）。
- java: missing — PARITY：gate「重定向待做」；architecture.md §10「跨 zone 首批不做」；xm-gateway `zone_id=0` 回 404；`SceneAssigner` 只在请求 zone 内选。
- size: L
- robot: travel_smoke（T1–T6）、battle_smoke_cross_zone（基线）；Java robot 无
- hazards: 必须先判跨区再解析场景，否则目标 zone 过渡期（频道没建好）会让合服后源区玩家登录永远卡死；124 一到客户端就关旧连接，从旧 gate 发出的 TravelToZone 回包可能丢；不认票据 target_zone_id 的目标区会再发 124 把人送回去（robot 必须断言每跳只出现一次 124）。

### sm-home-zone-routing — 归属 zone 查询与合服围栏
- mmorpg: go/scene_manager/internal/logic/home_zone.go；go/match/internal/team/homezone.go；配置 DataServiceRpc / HomeZoneLookupTimeoutMs=1500 / AllowGateZoneAsHomeZone
- client messages: none 直接（失败时上游给客户端 23 {3023} 或团队 4019 kTeamHomeZoneUnknown）
- tables: none
- depends on: data_service（player:zone 映射、home_zone_merging 标志）
- behavior: 进场带 GateId 时查一次 GetPlayerHomeZone：有映射用它；正在合服 → ErrHomeZoneMerging(21) 拒；无映射时首次落点 / 同 zone 换图按 gate zone，跨 zone 传送任一条腿拒；超时 / 不可用 → ErrHomeZoneUnavailable(20) 可重试；未配置 data_service 且未开 AllowGateZoneAsHomeZone → 拒。结果随 RoutePlayerEvent.home_zone_id 下发，scene 据此选存盘 topic。组队建队 / 申请 / 邀请也按 home zone 做跨区校验。
- internal: gRPC data_service（etcd 发现、熔断、NonBlock）；指标 home_zone_lookup_total{outcome}。
- java: missing — Java 单 zone、单库 `xm_java`，玩家没有 home zone 概念；跨 zone / 合服首批不做（architecture.md §10）。
- size: M
- robot: travel_smoke（基线）
- hazards: 「拒绝先于任何写」只对带 GateId 的请求成立，不带 GateId 的调用方（将来的疏散 / GM）会绕过合服围栏（代码注释登记的已知缺口）；归属未知时绝不能静默落进程 zone 库。

### sm-mirror-instance — 镜像副本创建与进入
- mmorpg: go/scene_manager/internal/logic/createscenelogic.go（createInstance / resolveMirrorSourceNode / rollbackInstanceAllocation）；C++ player_scene.cpp（RequestEnterMirrorScene）、player_scene_handler.cpp（63 mirror 分支）、rpc_replies/scene_manager_response_handler.cpp（CreateScene 回包后按 creator_ids 发 EnterScene）
- client messages: 63 EnterScene (C2S，scene_info.mirror_config_id>0 且 scene_id=0)；79 NotifyEnterScene (S2C push，镜像建好后)
- tables: Mirror（id、scene_id→BaseScene、main_scene_id→World）、BaseScene
- depends on: sm-node-registry-load、sm-cross-node-scene-switch、sm-enter-scene-allocation
- behavior: 63 带 mirror_config_id、scene_id=0 = 「以我当前场景为源新建一个镜像并进去」，应答只表示已受理（成功无 tip），之后异步收到 79；源本身是镜像 → 拒（不允许镜像的镜像，回 ParamError）；scene_id>0 且带 mirror_config_id = 按 id 进已有镜像走普通路径。scene_manager 端：源场景已不存在 → ErrSourceSceneGone(13)；默认与源场景同节点共置（复用已加载地图 / AI / 刷怪），源节点死 / 跨 zone / 超过 MirrorSourceNodeLoadCap 时回落最佳副本节点；MirrorDedupBySource=true 时复用同源已有镜像；CreateScene RPC 失败整体回滚不留幽灵场景；应答回显 creator_ids。
- internal: Redis `scene:{id}:mirror`=1、`scene:{id}:source`、`scene:{src}:mirrors` SET、`instances:zone:{z}:active` ZSET(score=最后活跃秒)、`instance:{id}:player_count`；SceneNodeGrpc.CreateScene(config, scene_id, mirror_config_id, creator_ids)；可选 Agones 房间预占。
- java: missing — `ClientRequestHandler.enterScene`：mirror_config_id≠0 且 scene_id=0 → 3023 kEnterSceneFailed；Java 无副本节点角色、无 Mirror 表消费者。
- size: L
- robot: none（基线 robot 未覆盖镜像）
- hazards: 镜像 CreateScene 是 C++ 发起的**异步**调用，后续 EnterScene 由回包驱动，回包丢失时玩家停在源场景且客户端已收到「成功」应答；DestroyScene / 迁移源频道都要级联销毁镜像，否则 `scene:{src}:mirrors` 永久泄漏。

### sm-instance-lifecycle — 副本空闲回收与销毁
- mmorpg: go/scene_manager/internal/logic/instance_lifecycle.go、destroyscenelogic.go、scene_atomic.go（luaAtomicDestroyInstance / Force）
- client messages: none 直接（C++ 侧 DestroyScene 有人时把人改派回大世界，客户端看到换图）
- tables: none
- depends on: sm-mirror-instance
- behavior: 领导者每 30s 扫各 zone 活跃副本：人数>0 刷新活跃时刻；0 人超过 InstanceIdleTimeoutSeconds(300s) / 镜像 MirrorIdleTimeoutSeconds(30s) 即销毁；显式 DestroyScene RPC 强制销毁；均级联销毁子镜像、对活节点发 DestroyScene、扣节点 scene_count 与残余人数、归还 Agones 房间。
- internal: 空闲销毁用 Lua「人数仍为 0 才一次性删全部键」（与进入的原子 INCR 竞争安全）；强制销毁同样一条 Lua 删键并返回残余人数；进入指定 scene_id 用 AtomicIncrPlayerCountIfSceneExists 防 destroy-while-entering（返回 <0 → scene_gone）。
- java: missing — Java 场景随节点启动创建、随节点停服消失，没有按需副本与回收。
- size: M
- robot: none
- hazards: DestroyScene 的非原子版本（destroyscenelogic.go）逐键 DEL，与并发进入不互斥；扣节点人数用 INCRBY 负数后夹 0，非原子；领导者丢失时扫描中途停止，交给新领导者。

### sm-dead-node-recovery — 死节点判定、再入屏障与接管
- mmorpg: go/scene_manager/internal/logic/reentry_barrier.go、constants/reentry_barrier.go、load_reporter.go（removeNodeFromRedis / reconcileDeadNodeScenes / sweepStaleLoadSetMembers）、enterscenelogic.go（playerLocationOwnerDead / playerLocationOwnerGone）、world_init.go（懒改派）
- client messages: none 直接（死节点上的玩家断线后重登，表现为重新进场）
- tables: World
- depends on: sm-node-registry-load、sm-player-location、sm-owner-epoch-handoff
- behavior: etcd DELETE → 写 `node:zone:{z}:{n}:death_at`(ms) → 摘负载集；此后改派 / 销毁 / 接管必须等再入屏障 = C++ drain 15s + 时钟余量 5s = 20s（配置只能调大，低于下限钳回）；屏障内进场回 ErrSceneReentryBarrier(17) 可重试；屏障过后：世界频道懒改派到活节点并 CreateScene，死节点上的副本强制销毁（世界频道不销毁），位置指向死节点的玩家按「无持有者」接管（不需要 handoff 标记），整个 zone 已无活节点的陈旧 location 视为不存在。death_at 写不进 / 摘除失败 → 推迟摘除队列重试；节点重新注册清除死亡标记。
- internal: 判定 fail-closed（拿不准不动归属）；进程内还记「本副本观察到 etcd 消失」时刻，跟随者不能单凭 Redis death_at 放行。
- java: partial — Java 不改派场景：节点 TTL 15s 后从目录消失，`SceneAssigner` 不再选它；玩家归属靠 30s 租约到期后被新会话强制夺权（`PlayerStore`，代价是上次写回以来的增量丢失）。缺：节点死亡后的「旧节点可能仍在写」屏障——Java 依赖租约 + epoch 围栏（旧节点写回被拒），语义上已覆盖回档风险，但没有频道改派（死节点承载的世界图在其他节点上本来就有同 config 场景，所以可用性不受影响）。
- size: L
- robot: none（基线有 node_detach_deferred / integration 单测）
- hazards: CppNodeDrainBudget 是对 C++ kDrainBudget 的手工镜像，C++ 调大而 Go 没跟会静默打开双写窗口；IsNodeAlive 在 Redis 故障时按存活处理（不误改派）；death_at 由 Go 观察 DELETE 时打点，墙钟偏差是唯一不安全项。

### sm-world-channel-rebalance — 频道再平衡与孤儿频道清理
- mmorpg: go/scene_manager/internal/logic/world_rebalance.go、world_rebalance_debug.go、orphan_cleanup.go
- client messages: none
- tables: World
- depends on: sm-world-channel-provisioning、sm-dead-node-recovery
- behavior: 主世界节点加入 / 离开 / 角色变化，以及每 300s，按哈希重算期望 (conf, scene)→node，只迁移**空频道**（有人的频道在旧进程仍注册时绝不迁），每拍最多 10 个（0 关闭），迁移时级联处理镜像；fullSync 时删除 World 表里已不存在的 conf 的频道集合（表为空时拒绝运行）。调试 HTTP 端点输出迁移计划。
- internal: 迁移改写 `scene:{id}:node` 无 CAS，领导者降级即停（逐条重查领导权）；指标 rebalance 积压 gauge。
- java: not_applicable — Java 场景归节点所有、由节点自建，不存在「中心分配后需要回迁」的频道；若 Java 改为中心化频道分配（sm-world-channel-provisioning），本项随之变为 missing。
- size: M
- robot: none
- hazards: 新旧领导者并发迁移同一频道 = 计数双减 + 名额错挂（Go 注释承认的最危险写路径）。

### sm-world-channel-autoscale — 大世界频道自动扩缩容
- mmorpg: go/scene_manager/internal/logic/world_autoscale.go、config.WorldAutoscaleConfig
- client messages: none 直接（缩容排空时 C++ 把玩家改派到同图其他频道，客户端看到换图 79）
- tables: World
- depends on: sm-world-channel-provisioning、sm-instance-lifecycle（销毁路径）
- behavior: 默认关闭。该图**所有**频道人数 ≥ 2000 才加一个频道（上限 16）；某频道 < 100 且其余频道有余量时排空（摘路由 → DestroyScene，C++ 有人就改派、没人才销毁，幂等每拍调用）→ 收尾；每图至少 1 个频道；每次伸缩后 (zone, map) 冷却 120s；有镜像以它为源的频道不缩。
- internal: 期望频道数落 Redis（配置只是种子）；排空标记带 TTL，进程中途死掉由下一轮接手；排空索引集合。
- java: missing — Java 每节点每图固定 1 个场景，无动态频道。
- size: M
- robot: none
- hazards: 期望值必须同步减 1，否则补频道逻辑几秒内把刚缩掉的频道建回来；排空期间新建的镜像要级联销毁。

### sm-cluster-ops — 调度器多副本运维面（选主 / 热关停 / 发号围栏 / Agones）
- mmorpg: go/scene_manager/scene_manager_service.go、internal/logic/leader_gate.go、agones_binding.go、agones_reconcile.go、internal/agones/*.go、go/shared/{leader,killswitch,snowflakealloc}
- client messages: none
- tables: none
- depends on: sm-node-registry-load
- behavior: 运维可见。变更类后台循环（补频道 / rebalance / 孤儿清理 / 空闲副本销毁 / 扩缩容 / Agones 对账）只在 Redis 选出的领导者上跑（锁 TTL 30s，SIGTERM 时主动放锁，换主即触发一次 fullSync）；LeaderEligible=false 为金丝雀只服务数据面；etcd `/mmorpg/killswitch/<服务>/<方法>` 秒级短路某个 RPC；snowflake worker 失租先 Fence 发号器、flush Kafka 再 os.Exit；in-band 错误码按 fault 集合计入故障指标；Agones 开启时建场景前在 GameServer 上预占 rooms 计数、销毁 / 失败时按 `scene:{id}:agones_gs` 精确归还，周期对账只告警。
- internal: 见上；启动时校验再入屏障常数自洽，劈叉即 panic。
- java: not_applicable — Java scene-manager 无后台变更循环、无中心发号（scene_id 由各 scene 节点本地发号器出，按 (node, scene) 二元组寻址），多实例天然对等；无 K8s / Agones 部署形态。若 Java 引入中心化频道 / 副本分配，选主与发号围栏需要补上（Redisson RLock 可承担）。
- size: M
- robot: none
- hazards: killswitch 只挡 gRPC 入口，挡不住后台 ticker 建场景；Agones 初始化失败必须 panic 而不是静默退回 Redis 调度。

### match-queue — 匹配排队（JoinQueue / CancelQueue / GetQueueStatus）
- mmorpg: go/match/internal/logic/joinqueuelogic.go、cancelqueuelogic.go、getqueuestatuslogic.go、queue.go、keys.go、constants/errors.go、config/config.go
- client messages: 157 MatchServiceJoinQueue (C2S)、148 MatchServiceCancelQueue (C2S，应答 Empty)、153 MatchServiceGetQueueStatus (C2S)
- tables: Tip（match_error 段 16000 起）；DungeonTable（battle_config_id，PVE 人数的名义权威——实际读 yaml PveTeamSizeByConfigId）
- depends on: sm-player-location（not_in_scene 判定）、battle:lock（C++ scene 写）、match-gather、会话身份（gate 注入 session metadata）
- behavior: 身份取 session metadata（请求体 player_id 只给内部调用）；模式：PVE_SOLO 需 1 人且**不入队**直接建 matched 票并异步 gather；PVE_TEAM 人数 = PveTeamSizeByConfigId[config]（0 → kMatchTeamSizeNotConfigured，>5 截到 5）；1V1=2；5V5=10；其余 / 切磋 → kMatchModeNotOpen；battle:lock 存在 → kMatchInBattle（Redis 错误 fail-closed 回 kMatchInternal）；已有票 → kMatchAlreadyQueued 并回原 ticket（ready 残留票与「queued 但不在队列」的孤儿票先自愈）；无 location → kMatchNotInScene；party_member_ids 已废弃、忽略。Cancel：无票 / 票号不符 / 已 matched 以后一律静默成功（太迟不可撤）；queued 态先 CAS 删票再出队。Status 五态：无票 NOT_QUEUED、queued QUEUED、matched MATCHED、ready READY（ENTERING 保留不用），estimated_wait_seconds 估算。
- internal: MatchRedis（集群，hash tag `{mq}`）：`match:{mq}:queue:{mode}:{config}` LIST + `match:{mq}:rank:…` ZSET（评分镜像）+ `match:{mq}:index` SET；票据 `match:ticket:{player}`（state/mode/config/queue_key/rating/zone/enqueued_at，queued TTL 6h，matched TTL = max(30s, 人数×6s+22.2s+3s+10s)，ready 60s）；所有状态迁移都是带 ticket id 的 Lua CAS；旧格式无 tag 的队列 key 迁移兜底。Java 需要：新后端（建议 xm-match 进程，Dubbo 路由组）+ gate `SERVICE_BACKENDS` 加 MatchService。
- java: done（2026-10-08，批次 6.4，规格 `docs/porting/match-spec.md` §2.1–§2.4、§8.1、§9.4）— 新进程 xm-match（Dubbo 20888、管理 18113、group `match`），gate 的 `MessageRoutes.SERVICE_BACKENDS` 把 `MatchService` 的 10 个号转给它。`com.game.match.queue.{QueueService,QueueHandlers}` + `com.game.match.ticket.{TicketStore,RedissonTicketStore,TicketScripts,DefaultTicketHealing}`：157 的 10 行判定顺序与 `parameters[0]` 逐字节照搬（自愈先于读位置）；148 六种结局一律静默成功、不回包；153 五态映射、`estimated_wait_seconds` 恒为 0。有意差异：身份只取会话（M3）；全部键一个 hash tag `{match}`、票据与队列的每次迁移一段 Lua 且可重放（M5 / M6）；时间取 Redis TIME（M7）；148 成功不回包（M4）；工作池过载回 in-band 16004 / 信封 1003（M29）；发号租约丢失时 157 回 16004（M28）。**勘误**（规格 §12.4 第 1、2、10 条）：`estimated_wait_seconds` 不是「估算」而是恒为 0；Cancel / Status 在 Redis 故障时客户端看到信封 1003；match 的 10 个号都不在 MessageLimiter 表里，按缺省每秒 3 条。robot `battle-smoke` 第 1–3 步。
- size: L
- robot: battle_smoke（JoinQueue PVE_SOLO）、robot AI / DevAutoPilot；Java robot 无
- hazards: 历史上 match 错误码从 1 手写，与 common / login 段撞号（2026-09-02 修为 Tip 表 match_error 段），Java 必须直接引用同步来的 tip 枚举；PVE_TEAM 人数读 yaml 而非 DungeonTable.max_team_size（表里有 10 的历史行），两版取值须一致；Cancel 在 matched 之后返回空成功，客户端只能靠 GetQueueStatus 轮询（客户端每 3s 一次）/ NotifyBattleStart 收敛。

### match-matcher — 凑单器（按队列弹组、评分容差、分队）
- mmorpg: go/match/internal/logic/matcher.go、rating.go（ratingToleranceFor / anchorToleranceFor / assignBalancedTeams）、gather.go（teamAssignment / teamIndexFor）
- client messages: none 直接（结果体现为 GetQueueStatus 变 MATCHED/READY、随后 177 NotifyBattleAssigned / 143 NotifyBattleStart）
- tables: none（人数见 match-queue）
- depends on: match-queue、match-gather、match-rating（评分读取）
- behavior: 每 500ms 遍历 `match:{mq}:index` 里的每个 (mode, config) 队列，抢到该队列的 SETNX 锁（10s，按持有者释放）才处理；从队列前 256 人里以锚点（队首起，最多尝试若干个）凑满 required 人：PVP（1V1 / 5V5）按评分容差 = 100 起、每 5s +100、上限 1000，锚点等满 90s 后容差 ∞；PVE 组队不看评分（FIFO）；弹出者逐个校验票据仍为本队列 queued 并 CAS 推进 matched，校验不过的残留项丢弃；成组后异步 RunGather（失败时幸存者按原序回队首、肇事者删票）。5V5 分队：评分排序后蛇形分 0/1；1V1 0/1；PVE 全员 0 队。空队列懒剔除；锚点饥饿限频告警。每轮顺带清理过期观战索引。
- internal: 弹组是 Lua（快照 + 原子移除 list 与 rank 镜像成员）；queue_depth / wait_seconds / starved_anchor_wait_seconds 指标只由持锁实例上报；battle 节点池为空时暂停凑单并限频告警。Java 需要：定时任务 + Redisson 锁 + Lua（或单 leader）实现同语义。
- java: done（2026-10-08，批次 6.4，规格 `docs/porting/match-spec.md` §2.5–§2.9、§9.5）— `com.game.match.matcher.{MatcherRunner,QueueMatcher,GroupPicker,Tolerance}`：单线程 `match-matcher` 每 500 ms 一轮、按队列加锁、256 前缀、32 个有效锚点、候选扫描全部成员（不是「锚点之后」，规格 §12.4 第 8 条）、容差曲线与成员顺序照搬；分队在 `com.game.match.gather.TeamAssignment`（5V5 重读评分后蛇形）。有意差异：弹组 `S_POP` 原子、全有全无（M5）；暂停条件另加「发号租约无效」「gather 许可已满」（M10）；无肇事者失败后 2 s 退避（M11）；凑单校验多一项位置检查，重连租约跳过、登出删票（M12）；凑不满的队列每 30 s 清一次没有有效票据的残项（M31，基线只剔空队列）；抢不到锁的实例把自己的 gauge 置 0（M8）。不移植旧格式队列搬迁与缺分补写（M9）。
- size: L
- robot: battle_smoke（PVE_SOLO 不经凑单）、battle_smoke_cross_zone（1V1 跨区凑单）
- hazards: 锚点等待时间按各自入队时刻算，队首凑不到时不阻塞后面的人；5V5 required=10 时 matched 票 TTL 96s，公式与 gather 各跳超时同源，单独改任一超时都要重算；rating 读失败按默认 1500 继续（不阻塞凑单）。

### match-rating — 对局结果回流与 Elo 评分
- mmorpg: go/match/internal/kafka/result_consumer.go、go/match/internal/logic/rating.go（ApplyBattleResult / ratingApplyScript）、match_service.go（RatingEnabled 降级重试）
- client messages: none（评分不下发客户端）
- tables: none
- depends on: battle 节点发 BattleResultEvent（Kafka `match-results`，key=battle_id）、match-matcher
- behavior: 只对 PVP 队列模式（1V1 / 5V5）计分：Elo K=32，队伍取平均分，平局 0.5，回合打满（RatingDrawRoundCap=30）按平局；PVE、切磋不计分；新号默认 1500，评分下限 0、保留两位小数。
- internal: 消费组 match-rating；两层幂等：每局 `match:rating:applied:{battle_id}`（applying|ΔA → done，TTL 7d）+ 每人 hash `match:rating:{player}` 的 recent_battles（最近 8 局）——逐人**增量** Lua，写到一半失败由重投续写不重复；handler 失败有限次重试后跳过；Kafka 不可达时降级为 30s 后台重试，不阻塞排队。Java 需要：battle 结果事件来源（Java 尚无 battle）+ 消费方。
- java: done（2026-10-08，批次 6.4，规格 `docs/porting/match-spec.md` §5）— 规则照搬（只计 1V1 / 5V5、K = 32、队伍平均、平局 0.5、回合打满按平局、下限 0、两位小数、新号 1500）：`com.game.match.rating.{EloRules,RatingStore,RatingSqlErrors,JdbcRatingReader,RatingCleanup,BattleResultConsumer,BattleResultIngest}`。有意差异：存储从 Redis 两层幂等 Lua 改成 MySQL 两张表（`match_rating` / `match_rating_applied`，pbmysql 自建）、一局一笔事务，没有「部分入账」状态（M18）；结果走 Java 自有的 Kafka topic `xm-battle-result-g<代次>`（xm-battle 的 `KafkaBattleResultSink` 生产、xm-match 消费，组 `xm-match-rating`），可恢复的库故障暂停重试、不跳过（M19）；事件里重复的 player_id 去重入账。回合打满的阈值照搬基线的 30 + 覆盖表（缺省空），「按引擎回合上限推导」登记为 mmorpg 待做。dev 读评分口 `GET /admin/match/dev/rating/{pid}`（M32）。robot `battle-smoke` 第 8 步、`match-5v5`。
- size: M
- robot: none（基线有 rating_* 单测）
- hazards: 评分写「读旧值 + 写绝对值」会在同一玩家两局被不同实例并发入账时丢一局，必须增量；平局 / 打满回合的判定要与 battle 侧 outcome 枚举一致。

### match-gather — 开局管线与战斗房间分配（gather）
- mmorpg: go/match/internal/logic/gather.go、spectate.go（writeSpectateRecord / stopWatchingIfAny）、go/match/internal/discovery/node_watcher.go；proto/scene_manager/scene_node_service.proto（PrepareBattle / CancelBattlePrepare）、proto/battle/battle_node.proto（CreateBattle / DestroyBattle）
- client messages: 结果：177 BattleClientPlayerNotifyBattleAssigned (S2C push，大厅公告：host/port/票据)、143 NotifyBattleStart (S2C push)；失败时客户端只看到票据回到 queued / 消失
- tables: DungeonTable（battle_config_id，由 battle 节点读怪物组 / 时限）；战斗配表指纹
- depends on: sm-player-location（定位每人所在 scene 节点）、C++ scene PrepareBattle（冻结 + 快照 + battle:lock）、battle 节点（C++）、match-spectate（观战记录先写）
- behavior: battle_id = snowflake（与 challenge_id / team_id 同源）；随机挑一个 battle 节点；逐人先清退其观战，再读 location → 该 scene 节点 PrepareBattle(deadline=现在+300s, prepare_deadline=matched 票 TTL)；全员快照的配表指纹比对（warn / reject 模式）；crypto 随机种子；**先写观战记录**（有界重试写不进即不建房）再 CreateBattle；节点级准入拒绝（Unavailable + battle_not_allocatable）→ 不发 DestroyBattle，换一个没试过的节点重试一次；其它失败 → DestroyBattle（失败则放弃解冻、交期限收尾）→ 逐人 CancelBattlePrepare → 队列场景幸存者回队首 / 其它删票；成功 → 票据置 ready、发布到观战活跃索引。
- internal: scene / battle 节点经 etcd list-watch 镜像（只认 grpcEndpoint，身份歧义拒用），gRPC 连接按 endpoint 缓存；各跳超时：PrepareBattle / RemoveObserver / CreateBattle 5s 级、记录写 ~6.1s；battle 建房成功后自己经 Kafka 推 177/143。Java 需要：scene 侧 PrepareBattle / CancelBattlePrepare（冻结、快照、战斗锁）、battle 进程、节点发现。
- java: done（2026-10-08，批次 6.4，规格 `docs/porting/match-spec.md` §3、§9.6、§9.7；scene 侧的备战 / 取消随 6.3、battle 节点随 6.2）— `com.game.match.gather.{GatherPipeline,Compensation,VirtualThreadGatherLauncher,ScenePreparer,RedisBattleNodes,FingerprintCheck,TeamAssignment}`：五个入口汇入一条管线，步骤、补偿矩阵、各跳超时（3 / 5 / 3 / 3 s，落点写入 6.1 s）与 matched TTL（42 / 48 / 54 / 60 / 66 / 96 s，`MatchBudgets`）与基线逐值相同；建房之前先写落点记录；「不可分配」只看类型化的准入枚举、换节点只重试一次。有意差异：每次 gather 一个虚拟线程 + 在途上限 256（M13）；备战结局不明的人也发取消（M14）；battle 明确拒绝建房时不发 destroy（M15）；建房请求确定没有送达时按没建房补偿（M30）；选节点读 Redis 目录、只选 `accepting = true`（M26）；备战 / 取消经 Dubbo `SceneBattleService`、不占 141 / 142 / 145 / 155 这些消息号（M2）。开局前的观战清退是空钩子（`GatherHooks`，随 6.5）。**勘误**（规格 §12.4 第 3、4、9 条）：各跳超时是 3 / 3 / 5 s 不是「5 s 级」；基线对明确拒绝也发 Destroy、Destroy 失败就不解冻；指纹模式是 off / warn / enforce。robot `battle-smoke`、`match-5v5`、`team` 的 S7。
- size: L
- robot: battle_smoke、team_smoke S7/S8、battle_smoke_cross_zone
- hazards: 补偿矩阵复杂：CreateBattle 超时 ≠ 失败（房间可能已建），所以必须先 DestroyBattle 成功才解冻；scene 侧 PREPARING 作废期限在 PrepareBattle 时就下发、事后改不了，预算必须一次算够（含 D82 换节点重试）；v1 battle 节点是随机挑选，无负载感知。

### match-challenge — 场景发起切磋（点名 PK）
- mmorpg: go/match/internal/logic/challengelogic.go、gather.go（RunChallengeGather）、keys.go
- client messages: 152 MatchServiceChallengePlayer (C2S)、151 MatchServiceRespondChallenge (C2S)、156 MatchServiceNotifyChallengeInvite (S2C push)、154 MatchServiceNotifyChallengeResult (S2C push)
- tables: Tip（match_error：kMatchChallengeSelf / TargetOffline / TargetBusy / SelfBusy / Pending / Expired / NotTarget）
- depends on: sm-gate-command-channel（推弹窗）、match-gather（mode=PVP_CHALLENGE，不带票据）、会话目录（目标在线判定）
- behavior: 发起：挑战自己 → kMatchChallengeSelf；双方 battle:lock 咨询性检查（发起者在战 → SelfBusy，目标在战 → TargetBusy）；目标不 ONLINE → TargetOffline；同一目标同时只挂一个待应答挑战（`challenge:target:{pid}` SETNX）→ Pending；challenge_id = snowflake，记录 TTL 60s；推 156{challenge_id, challenger_id, challenger_name, battle_config_id, expires_at_ms} 给目标。应答：记录不存在 / 过期 → Expired；应答者不是目标 → NotTarget；记录一次性消费；拒绝 → 推 154{accepted=false} 给发起者；接受 → 双方 battle:lock 权威复查（发起者已在战 → Expired「发起者已进入其它战斗」，应答者在战 → SelfBusy）→ 推双方 154{accepted=true} → 异步 gather；gather 失败再推一次 accepted=false 兜底。
- internal: MatchRedis `challenge:{id}` hash + `challenge:target:{pid}`（分两条单 key DEL，集群不同 slot）；切磋不入队不计分。
- java: done（2026-10-08，批次 6.4，规格 `docs/porting/match-spec.md` §6）— `com.game.match.challenge.{ChallengeService,ChallengeHandlers,RedissonChallengeStore,ChallengeScripts,MatchPushExecutor}`：152 的 11 行与 151 的 10 行判定顺序、tip 码与 `parameters[0]` 逐字照搬；156 / 154 经 `PlayerPushes` 推（M24），`challenger_name` 取会话里的账号名（同基线）；接受后以 `mode = PVP_CHALLENGE`、不带票据进开局管线，gather 失败再给双方各推一次 154 false。有意差异：记录 / 占坑 / 消费墓碑在一个 hash tag 下，发起与消费各一段原子 Lua，并发两条接受只有一条开局（M17，修基线「双击接受开两次 gather」）；过期时刻与过期判定取 Redis TIME（M7）；发号租约丢失时 152 回 16004（M28）。156 / 154 作为上行是空操作、gate 不回包。robot `battle-smoke` 第 9 步（基线没有切磋的 robot 覆盖）。
- size: M
- robot: none（基线 robot 未覆盖切磋）
- hazards: 发起时只做咨询性检查、不冻结任何人；记录与目标占坑删除非原子，残留只影响 60s 内对同一目标再次发起；156/154 是「借 service 声明拿消息号」的占位 RPC，客户端若真发上来服务端是空操作。

### match-spectate — 观战（随机 / 指定、观战列表、互斥清退）
- mmorpg: go/match/internal/logic/watchbattlelogic.go、listwatchablebattleslogic.go、spectate.go；proto/battle/battle_node.proto（AddObserver / RemoveObserver）
- client messages: 163 MatchServiceWatchBattle (C2S)、164 MatchServiceListWatchableBattles (C2S)；之后 177 NotifyBattleAssigned(role=OBSERVER) (S2C push 大厅)、161 NotifySpectateState / 158 NotifySpectateTurnResult / 166 NotifySpectateEnd（直连）、165 StopWatchBattle（直连上行）
- tables: Tip（kMatchSpectateWhileQueued / SpectateWhileInBattle / AlreadyWatching / NoWatchableBattle / BattleNotWatchable / SpectateOffline）
- depends on: match-gather（开局时写观战记录）、battle 节点、sm-gate-command-channel、会话目录
- behavior: 排队 / 战斗中 / 观战三态互斥：持票 → SpectateWhileQueued，battle:lock → SpectateWhileInBattle；已在观战不拒绝，服务端先清退旧场再接新场；观众不在线 → SpectateOffline；battle_id=0 从活跃索引随机挑，房间不存在时懒剔除并换一场重试一次，挑不到 → NoWatchableBattle；指定场不存在 / 已结束 / battle 拒绝（观众满、观众是参战者、签不出票）→ BattleNotWatchable；成功回实际 battle_id，落点与首帧由 battle 推。列表按 created_at 取最近若干场摘要{battle_id, mode, battle_config_id, player_names, created_at_ms}。进入 gather 的玩家会被自动清退观战（SpectateEnd reason=REMOVED）。
- internal: `spectate:battle:{id}`（SpectateBattleRecord pb，TTL=战斗最长期限+60s）、`spectate:battles:active` ZSET、`spectate:watching:{pid}`（SETNX 抢占后才 AddObserver，失败回滚；成功后 double-check 票据 / 战斗锁防 TOCTOU）；记录在建房前预写，建房窗口内「未公开」只回不存在、不剔除。
- java: missing（批次 6.5，规格 `docs/porting/spectate-spec.md`）— 房间侧的观战随 6.2 已有（见 combat.md 的 battle-spectate）。批次 6.4（2026-10-08）只备好了 6.5 要用的东西：163 / 164 已路由到 xm-match，暂回 in-band 1006 / 空列表（`com.game.match.dispatch.InlineHandlers`，match-spec M22）；落点记录 `xm:{match}:battle:<id>`（`BattlePlacement`，带实例号、rpc 地址、角色名、Redis 时间的 `created_at_ms`）同时充当观战记录；开局管线的钩子 `GatherHooks.beforePrepare / onStarted`（现为空实现）；按落点直拨并判死的 `placement.PlacementDialer`；票据只读口 `ticket.TicketReader`。观战标记、可观战索引、163 / 164 的真语义、开局前清退观众都没有做。
- size: L
- robot: battle_smoke（B 随机观战并断言回合数 ≥ 1）
- hazards: 战斗收尾时 battle 不回写 Redis，观战标记只能靠 TTL / 懒清退，一律拒绝「已在观战」会把玩家卡死整个 TTL；重看同一场不能发 RemoveObserver（会给仍活着的旧会话推假 SpectateEnd）；ZSET 成员无 TTL，靠三条路清理。

### match-battle-ticket — 战斗票据补签
- mmorpg: go/match/internal/logic/requestbattleticketlogic.go；proto/battle/player_battle.proto（BattleTicketPayload / BattleAssignedS2C）
- client messages: 179 MatchServiceRequestBattleTicket (C2S)，应答带 BattleAssignedS2C；触发方：144 NotifyBattleReconnect（scene 大厅推送）
- tables: Tip（common：kInvalidParameter「该战斗不存在或已结束」、kServiceUnavailable「战斗服务暂不可用」）
- depends on: match-spectate（`spectate:battle:{id}` 是定位 battle 节点的唯一来源）、battle 节点 IssueBattleTicket
- behavior: 客户端丢票（冷启动 / 换设备 / 重连）时凭 battle_id 补签：记录不存在 → kInvalidParameter；battle 节点不可定位 / RPC 失败 → kServiceUnavailable；battle 本地核对名单（参战者或观众）后自签返回 assignment{host, port, token_payload, token_signature, expire_at_ms, role}。
- internal: 票据 = hex(HMAC-SHA256(battle_token_secret, BattleTicketPayload))，与 gate 令牌密钥分域，寿命 = 房间作废期限。
- java: done（2026-10-08，批次 6.4，规格 `docs/porting/match-spec.md` §4；battle 侧的签发随 6.2）— `com.game.match.reissue.{BattleTicketReissue,ReissueHandler}` + `com.game.match.placement.{PlacementStore,RedissonPlacementStore,PlacementDialer,DirectPlacementDialer,PlacementClients,RpcFailures,ConnectProbe}`：179 的判定顺序与 `parameters[0]` 逐字照搬，只认会话身份，battle 的裁决原样透传。有意差异（M16，客户端可见）：落点记录是 Java 自有的 `BattlePlacement`（多存 battle 的实例号与 rpc 地址，单调写），补签按记录里的地址**直拨**（battle 丢了租约仍活着时签得出票，基线回 1003）；直拨的请求确定没有送达 + 目录里同号节点已换实例 + 原地址的建连探测明确连不上，三条都成立才回 1005，超时一律 1003。**勘误**（规格 §12.4 第 5 条）：基线 robot 只注册了 179 的应答 handler，没有任何场景发送 179，「battle_smoke（直连重建路径）」不成立；Java robot `battle-smoke` 第 5–7 步覆盖（补签的票与 177 逐字节相同、非成员 1005、不存在的局 1005、旧局 1005）。
- size: S
- robot: battle_smoke（直连重建路径）
- hazards: 观战记录写失败时 gather 拒绝建房，正是为了保证补签总能定位到房间。

### match-activity-battle — 帮会活动开战（MatchInternal.StartActivityBattle）
- mmorpg: go/match/internal/logic/activitybattlelogic.go、gather.go（RunActivityGather，presetBattleID）、proto/match/match_internal.proto、match_service.go（sessionInterceptor 拒绝带会话的调用）
- client messages: none（内部 RPC，只给 go/guild 调；客户端看到的是 guild 侧消息与 177/143 开战推送）
- tables: DungeonTable（battle_config_id = GuildActivity.dungeon_id）、GuildActivity（guild 侧）
- depends on: match-gather、帮会同道历练（guild 区域）
- behavior: 校验：battle_config_id≠0、1..5 人、非 0 不重复、activity_context 字段齐全且 initiator == members[0]，否则 INVALID_ARGUMENT；逐人只读预检：无在线会话 → MEMBER_OFFLINE、battle:lock → MEMBER_IN_BATTLE、无 location / 在途票据未自愈 / 建票失败 → MEMBER_NOT_READY（offender_player_id 为第一个不满足者）；Redis / 发号故障 → INTERNAL。成功同步发 battle_id、为全员建 matched 票后异步 gather（activity_context 深拷贝进 CreateBattleRequest）；gather 失败全员删票、不回队列，该 battle_id 不产生结果事件，由 guild 巡检判 EXPIRED。
- internal: 业务拒绝走响应 reject 枚举，gRPC 错误只用于 PermissionDenied 与传输故障；隔离靠「不标客户端协议 + 拦截器拒绝带会话 metadata + 网络策略」。
- java: partial（2026-10-08，批次 6.4 做了 match 侧，规格 `docs/porting/match-spec.md` §7.1–§7.2）— 提供方 `com.game.api.MatchInternalService`（xm-api，group `match`）由 xm-match 的 `com.game.match.activity.{MatchInternalServiceImpl,ActivityBattleService,ActivityRequestValidator}` 实现：参数校验、逐成员预检、预发 battle_id、原子建全员 matched 票（`team.GroupTickets`）、截止检查、异步 gather、同步回 `battle_id`，业务拒绝都在 `reject` 里。有意差异：内部接口类型化、gate 够不到，靠 Dubbo 调用方 MAC 隔离，不需要 PermissionDenied 分支（M2）；调用方截止经附件 `xm-budget-ms` 传相对预算；建票一段 Lua 原子完成（M25）；「有位置」要求位置在线且节点号 ≠ 0（M27）。另有 dev / test 专用的管理口 `POST /admin/match/dev/activity-battle`（M32，robot `match-activity` 用，建的是正常房间）。**仍未做**：真正的调用方 xm-guild 与结果消费 / 销账随 4.6（被 GuildActivity 表卡住）——所以现在活动局的结果事件发进 Kafka 之后没有人销账，battle 重发到上限后摘除。4.6 的两条义务：消费后必须销账（含不认识的 (guild, activity)）；`startActivityBattle` 传输失败时「战斗可能已开始」的补登记。
- size: M
- robot: guild_smoke（基线，帮会试炼部分）
- hazards: battle_id 在 gather 之前就交给 guild 登记，gather 失败时没有任何结果事件，guild 必须有过期兜底；内部接口靠部署层隔离，v1 未做调用方签名（契约偏差 13）。

### team-roster — 组队名册：建队 / 查询 / 离队 / 踢人 / 转让 / 解散
- mmorpg: go/match/internal/team/service.go、rules.go、store.go、scripts.go、view.go、keys.go、presence.go、homezone.go、server.go、errors.go；proto/team/team.proto
- client messages: 214 ClientPlayerTeamCreateTeam、207 GetMyTeam、210 LeaveTeam、202 KickMember、212 TransferLeader、209 DisbandTeam（均 C2S，应答 TeamResponse{error_message, team}）
- tables: Tip（team_error 段 base=4000：kTeamPlayerId / MembersFull / MemberInTeam / MemberNotInTeam / KickSelf / KickNotLeader / AppointSelf / AppointLeaderNotLeader / HasNotTeamId / DismissNotLeader / NotLeader / HomeZoneUnknown / InMatch / MemberOffline / StateChanged / Internal 等）
- depends on: 会话目录（在线态）、玩家资料（名字 / 等级 / 职业 / 性别 / appearance_id，读玩家存档 blob）、sm-home-zone-routing（队伍 zone）、team-notify
- behavior: 容量恒 5；建队：已在队 → MemberInTeam，查不到 home zone → HomeZoneUnknown，team_id 由 snowflake 发；GetMyTeam：过期申请 / 邀请清理 + 队长离线（会话键不存在）惰性转给在线且 join_seq 最小者，notify_online=true 时把视图推给其他在线队员；所有写请求带 expected_team_id，不符 / 已解散 → kTeamHasNotTeamId 并附调用者当前视图；离队：最后一人离开即解散，队长离开转给在线且 join_seq 最小者（无人在线取 join_seq 最小）；踢人：非队长 KickNotLeader、踢自己 KickSelf、目标不在队 MemberNotInTeam；转让：目标 0 → PlayerId、给自己 AppointSelf、目标已是队长幂等成功、非队长 AppointLeaderNotLeader、目标离线 MemberOffline；开战锁有效期间离队 / 踢人 / 转让 / 解散 → InMatch；失败回包仍可能带调用者视图。视图：members 按 join_seq 升序、is_online / in_battle 读时计算、applications 与 pending_invites 只给队长、version 每次提交 +1、membership_epoch 为接收者本人的成员关系版本、server_time_ms 取 Redis TIME。
- internal: SharedRedis（单实例）四类键：`team:rec:{tid}` hash{ver, pb=TeamRecord}、`team:{tid}`（TeamInfo 投影，C++ scene 读）、`team:player:{pid}` hash{tid, epoch}、`team:invite:{pid}` ZSET；规则是纯函数（不读墙钟），store 读 → 规则 → 版本 CAS 的 Lua 提交、冲突重读重算、索引与记录矛盾自愈（HEALED）；每请求预算 3500ms；同进程 zrpc、独立注册 TeamNodeService，gate 经路由服转发。
- java: missing — 207/214/… 在 Java gate 回 kServiceUnavailable；无队伍存储、无会话在线目录。
- size: L
- robot: team_smoke（S0、S4、S9）
- hazards: transferLeader 把「转给自己」放在「目标已是队长」之前（否则 4007 不可达）；会话未知（读失败）既不算在线也不触发离线转让（fail-closed）；`team:{tid}` / `team:player:{pid}` 键格式是 C++ scene 与合服工具的跨语言契约，Java 自己的键空间可以不同但 scene 侧读法要一并设计；推送与回包同源构建，排序键 (membership_epoch, version)。

### team-apply-invite — 组队申请 / 审批 / 邀请 / 应邀 / 邀请列表
- mmorpg: go/match/internal/team/rules.go（apply / handleApplication / invite / respondInvite / evictOldest*）、service.go（ApplyJoinTeam / HandleApplication / InviteToTeam / RespondInvite / ListMyInvites / pruneOwnInvite）、store.go（ListInvites / PruneInvite）
- client messages: 206 ApplyJoinTeam、208 HandleApplication、201 InviteToTeam、204 RespondInvite、205 ListMyInvites（均 C2S；205 应答 ListMyInvitesResponse{invites[], server_time_ms}）
- tables: Tip（team_error：kTeamPlayerId / MemberInTeam / MembersFull / NotInApplicantList / NotLeader / CrossZoneDenied / HomeZoneUnknown / InviteNotFound / InviteLimit / PlayerNotFound / InMatch / HasNotTeamId）
- depends on: team-roster、team-notify、会话目录、sm-home-zone-routing
- behavior: 申请：目标 0 / 自己 → PlayerId；自己已在队 → MemberInTeam；目标无队 → HasNotTeamId；跨区（AllowCrossZone=false 且 home zone ≠ 队伍 zone）→ CrossZoneDenied；满员 → MembersFull；重复申请刷新过期（TTL 120s），每队最多 10 条，满了淘汰最早一条不报错。审批：非队长 → NotLeader；拒绝不存在的申请 / 同意已在队成员 = 幂等成功；申请不存在或过期 → NotInApplicantList；开战锁中 → InMatch；按申请记录里的 zone 复核跨区；被拒申请人收 TeamEventS2C{APPLICATION_REJECTED}。邀请：非队长 → NotLeader；目标不在线 → PlayerNotFound（kTeamPlayerNotFound）；目标已在任何队 → MemberInTeam(param=target)；邀请 TTL 60s、每队 10 条、每个被邀请人最多 10 条待处理（Lua 原子判定 → InviteLimit）；被邀请人收 TeamInviteS2C。应邀：team_id 即绑定队伍；拒绝不存在的邀请幂等成功；邀请不存在 / 过期 → InviteNotFound；已在别的队 → MemberInTeam；满员 / 锁中 / 跨区同上。列表：只返回未过期邀请，反查索引里失效项按 score CAS 删除。
- internal: 邀请反查 `team:invite:{pid}` ZSET（member=team_id, score=expire_at_ms）与记录在同一 S_COMMIT 里原子维护；解散 / 满员时被邀请人收 INVITE_REVOKED。
- java: missing。
- size: L
- robot: team_smoke（S1–S3、S5、X1、X2）
- hazards: 客户端对 InviteToTeam / CreateTeam 有本地写冷却（TeamClient.WriteCoolingDown），服务端没有额外限频，只有 gate 按消息号限频（Java 已有 MessageLimiter）；AllowCrossZone 多实例取值不一致时（滚动切换）结果依实例而定。

### team-notify — 组队推送与场景刷新信号
- mmorpg: go/match/internal/team/notify.go、view.go、presence.go；C++ cpp/libs/services/scene/player/system/player_team.cpp（消费 PlayerTeamRefreshEvent）
- client messages: 213 ClientPlayerTeamNotifyTeamSnapshot (S2C push，TeamSnapshotS2C{team, reason, actor_id, tip})、215 NotifyTeamInvite (S2C push)、203 NotifyTeamEvent (S2C push)
- tables: none
- depends on: sm-gate-command-channel、team-roster、会话目录
- behavior: 每次落盘提交后异步推送：受影响成员收新视图（被踢 / 解散者收 team_id=0 视图），reason 取 TeamChangeReason（CREATED / MEMBER_JOINED / LEFT / KICKED / LEADER_TRANSFERRED / LEADER_OFFLINE_TRANSFERRED / DISBANDED / APPLICATION_CHANGED / INVITE_CHANGED / MEMBER_ONLINE / MATCH_STARTED / MATCH_ENDED / MATCH_FAILED / HEALED）；RPC 调用者本人不收（以回包为准）；至多一次，不在线不推；MEMBER_ONLINE 时 version 不变。同时给成员所在 scene 节点发 PlayerTeamRefreshEvent（只是「去重读一下」的信号）。
- internal: 整批推送一个独立预算、不继承请求 ctx；scene 信号走 Kafka scene-cmd（分区 = scene_node_id % P，带节点 UUID，UUID 为空不发）；指标 team_push_total{kind,outcome}。
- java: missing。
- size: M
- robot: team_smoke（每一步都按到达顺序断言推送）
- hazards: 推送可能乱序 / 丢失，客户端必须按 (membership_epoch, version) 丢弃旧视图并用 GetMyTeam / ListMyInvites 自愈；Java 若用不同通道（链路 vs Dubbo）下发，不能破坏这一排序契约。

### team-match — 整队开战（StartTeamMatch）
- mmorpg: go/match/internal/team/service.go（StartTeamMatch / preflightMatch / launchMatch / finishMatch）、rules.go（CheckMatchStart / LockMatch / ReleaseMatchLock）、go/match/internal/logic/team_battle.go（TeamBattleStarter）
- client messages: 211 ClientPlayerTeamStartTeamMatch (C2S，回包视图 match_state=STARTING)；213 推送 MATCH_STARTED / MATCH_ENDED / MATCH_FAILED；随后 177 / 143 战斗推送
- tables: Tip（kTeamNotLeader / InMatch / DungeonNotOpen / SizeExceeded / MemberOffline / MemberInBattle / MemberNotReady / StateChanged，失败时 parameters[0] = 出问题成员 pid）；DungeonTable（battle_config_id，模式固定 PVE_TEAM）
- depends on: team-roster、match-gather、match-queue（票据域）、sm-player-location
- behavior: 只有队长可发；副本未配组队人数 → DungeonNotOpen；人数超上限 → SizeExceeded；roster = 队长在前 + join_seq 序；逐成员预检：会话不在线 → MemberOffline、battle:lock → MemberInBattle、无 location / 有在途票据 → MemberNotReady；通过后提交开战锁（token + 过期时刻 + 名单，版本钉死；冲突整轮重来，有轮数与预算上限），锁期间名单冻结；逐人建 matched 票（带 team_id，失败全部回滚并推 MATCH_FAILED）；后台 gather，结束后释放锁并推 MATCH_ENDED（成功，通常早于战斗结束）或 MATCH_FAILED（带 tip）。v1 不补位、不排队，即时开战。
- internal: 锁时长覆盖 matched 票 TTL + 补偿窗口（5 人约 101s）；锁提交「报错 / 未提交」都可能已落锁（回复丢失、go-redis 重发），后台按 token 清锁 / 同步确认。
- java: done（2026-10-08，批次 6.4，规格 `docs/porting/match-spec.md` §7.3–§7.6、`docs/porting/team-spec.md` §5.5）— xm-team 继续持有开战锁与编排（`com.game.team.service.TeamService.startTeamMatch` / `finishMatch`、`TeamStore.commitMatchLock` / `endMatch`），票据域经 xm-match 的类型化接口 `com.game.api.MatchTeamService`（四个方法：`checkTeamMatch` / `createTeamTickets` / `releaseTeamTickets` / `runTeamGather`；xm-match 侧 `com.game.match.team.MatchTeamServiceImpl` + `precheck.DefaultMemberPrecheck`，xm-team 侧 `com.game.team.match.{TeamBattlePort,MatchTeamBattle}`）。判定顺序、tip（4018 / 4023–4030）与 `parameters[0]`、MATCH_STARTED / MATCH_ENDED / MATCH_FAILED 的收件人与内容照搬。有意差异（M1 / M20）：match 与 team 分进程；预检合成一次调用且整个在 xm-match 做（顺序不变）；票号由 xm-team 每人生成；gather 结果靠长挂的异步 RPC 回来；只在 xm-match 崩溃 / 分区 / 调不通 / 过载 / 发号租约无效时，211 回 4030（基线没有这个故障面），可能多一条 MATCH_FAILED。**勘误**（规格 §12.4 第 6、7 条）：MATCH_FAILED 带 tip 只对建票失败成立，gather 失败不带；预检里「位置节点号为空 → 4026」与「建票时 Redis 出错也回 4026[该成员]」inventory 漏写。robot `team` 场景的开战段（拒绝码、S7、S8；X2 的跨区部分只在配了第二个 zone 时跑）。
- size: M
- robot: team_smoke（S7、S8、X2）
- hazards: 进程退出时不等异步 EndMatch，开战锁靠自然过期；发起人不收 MATCH_STARTED 推送（以回包为准）。

### team-scene-follow — 场景内组队投影与跟随队长换图
- mmorpg: cpp/libs/services/scene/player/system/player_team.{h,cpp}（PlayerTeamSystem：RefreshMembership / CheckFollowLeader）；Go 侧只写投影（go/match/internal/team/store.go projectionOf）
- client messages: 跟随时队员收 79 NotifyEnterScene（与队长同 scene_id）；队友 AOI 表现
- tables: none
- depends on: team-roster、team-notify（刷新信号）、sm-player-location（读队长位置）、sm-cross-node-scene-switch（跟随即请求 scene_manager 换场）
- behavior: v1 只在同 zone、同 scene 节点内跟随：非队长进场 → 查队长位置并请求切过去；队长进场 → 对本节点其他成员逐个刷新并跟随（不再扇出，防循环）；组队变更只刷新 TeamId，入队不立即拉人；战斗中（InBattle 或 battle:lock）不跟随，冻结解除后补一次；跨节点 / 跨 zone 只记日志指标。
- internal: scene 读 `team:player:{pid}`（tid, epoch）、`team:{tid}`（TeamInfo 投影）、`team:rec:{tid}`（只 EXISTS），全部在 loop 线程异步回调、先核对实体仍有效。
- java: missing — Java scene 无队伍组件。归属上属于 scene 区域，这里列出是因为它是组队的客户端可见效果。
- size: M
- robot: team_smoke S6、X2（跨 zone 不跟随）
- hazards: epoch 只在 tid 变化时 +1，键缺失重建时用 Redis TIME 毫秒起种（保证大于旧值）；跟随依赖 scene_manager 进场路径，跨节点跟随在 v1 被明确排除。

### battle-node-runtime — 回合制战斗节点（引用，C++ 区域）
- mmorpg: cpp/nodes/battle/**、cpp/libs/services/battle/**（turn_battle_engine、battle_room_manager、table_battle_data_provider、battle_table_fingerprint）；go/battle 只有生成的消息号 / 事件号文件；proto/battle/{battle_node,player_battle,battle_data}.proto
- client messages: 直连面：BattleTokenVerifyRequest/Response 握手、149 SubmitBattleAction、140 GetBattleState、162 SetAutoBattle、165 StopWatchBattle (C2S，只经直连)；139 NotifyTurnResult、150 NotifyBattleEnd、161/158/166 观战帧 (S2C 直连)；177 NotifyBattleAssigned、143 NotifyBattleStart（大厅公告，无直连时经 gate）、144 NotifyBattleReconnect（scene 大厅推送）
- tables: DungeonTable（scene_id / max_team_size / time_limit / monster×3）、Monster、技能 / 道具相关战斗表
- depends on: match-gather、match-spectate、match-battle-ticket、C++ scene PrepareBattle / 结算（BattleSettlementEvent 等 Kafka 事件 42–47）
- behavior: 本区域只关心「房间分配」接口：CreateBattle（准入闸未开 / 拿不到许可回 Unavailable + battle_not_allocatable，保证无副作用）、DestroyBattle、AddObserver（房间不存在 = kEntityIsNull）、RemoveObserver、IssueBattleTicket；房间在 deadline（默认 300s）强制平局收尾；打完向 `match-results` 发 BattleResultEvent。
- internal: 战斗票据 HMAC 密钥与 gate 令牌分域；节点在 etcd 注册 client_endpoint（客户端可达地址）。
- java: missing — Java 无 battle 进程；PARITY 记「战斗待做」，`NodeInfo.client_endpoint` 行注明 battle 分配的通告地址随 battle 功能一起做。
- size: XL（应由 C++ battle 区域清单细拆，这里不计入本区域工作量）
- robot: battle_smoke、team_smoke
- hazards: gate 一律拒绝战斗上行（战斗只走直连），Java gate 现在把 BattleClientPlayer 的上行也路由到 unsupported，行为一致。

### activity-schedule-list — 活动排期列表（GetActivityList）
- mmorpg: cpp/libs/services/scene/player/system/player_activity_schedule.{h,cpp}、player_mission.cpp、proto/scene/player_activity.proto；data/schema/activityschedule_table.proto
- client messages: 190 SceneActivityClientPlayerGetActivityList (C2S，玩家服务，由 scene 处理)
- tables: ActivitySchedule（id→Mission，enabled，baseline_start_at_ms / baseline_end_at_ms，UTC 毫秒）、Mission（mission_type=2 才进活动目录）
- depends on: 任务系统（scene 区域）
- behavior: 目录只读 MissionTable.mission_type=2；每项状态 UNSCHEDULED（无排期 / 未启用）/ UPCOMING / OPEN / ENDED，结束时刻不可接取；can_participate 由上层结合任务能力设置，不可参与时带 unavailable_reason；应答带 server_time_ms；排期判定只检查时间窗（开放回 kSuccess、合法未开放回 kFeatureUnavailable、配置错误回对应 tip），时间由调用者显式传入。
- internal: 纯计算入口，无存储；只在 scene 线程执行。
- java: missing — Java scene 对 190 走 `ClientRequestHandler.replyUnavailable`，回 GetActivityListResponse{error_message{kFeatureUnavailable}}；Java 已能加载 ActivitySchedule 表（xm-table 生成访问代码），但无任务系统。（Go match / team / scene_manager 都不消费 ActivitySchedule，归属上属于 scene 任务区域。）
- size: S
- robot: none
- hazards: 时间一律 UTC 毫秒；表数据当前可能全部未启用，Java 实现时要和基线一样「未配置 = 未排期」而不是报错。

## Open questions

1. **Java 场景调度要不要中心化？** 基线由 scene_manager 发 scene_id、铺频道、管副本；Java 现在是「节点自建场景 + scene-manager 只读目录」。多频道、按需副本 / 镜像、跨节点换图、自动扩缩容都要先定这一点。若保持节点自治，副本创建可改为「scene-manager 选副本节点 → Dubbo 调该节点建场景」，不需要 Go 那套 Redis 键族与再平衡。
2. **玩家位置目录放哪里？** match / team / 跨节点换图都依赖「玩家在哪个 scene 节点」（基线 `player:{id}:location`）。Java 可以由 login 夺权后写、scene 换图时 CAS 更新，并与 MySQL 的 owner_epoch 同步——需要定唯一写者。
3. **非 scene 后端如何给玩家推 S2C？**（sm-gate-command-channel）Java 没有 Kafka，也没有按 player_id 查 gate 的会话目录。候选：Redis 会话目录 + 每个 gate 暴露 Dubbo「推给会话」接口（带 gate 实例号防僵尸），或 Kafka（tech-stack 里 Kafka 标为 planned）。
4. **跨节点换图的「旧节点已落盘」凭据**：Java 的归属协议靠租约 + epoch 围栏，旧节点的迟到写回会被拒，但这意味着跨节点换图时若不先写回再放行，玩家在新节点会读到旧档（丢进度而不是双写）。需要一个与 handoff 标记等价的步骤（例如旧节点写回并释放 → 新节点夺权），以及与之配套的 gate 换绑指令。
5. 基线 `AllowUnsafeCrossNodeHandoff: true`（dev）下同 zone 跨节点换图从不经过标记链路，C++ 侧标记链路注释说「尚未编译、未跑过任何测试」。Java 若照「生产语义」实现，robot 行为会与基线 dev 配置不同（基线 dev 下不会出现 18 重试）。
6. **PVE 组队人数来源**：Go 用 yaml `PveTeamSizeByConfigId`（仅 config 1 = 5），注释称权威应是 DungeonTable.max_team_size（表里有 10 的历史行，代码截到 5）。Java 读表还是读配置？两版必须一致，否则同一 battle_config_id 在两版可开 / 不可开。
7. scene_manager 私有错误码 1..21 与 tip 码数轴重叠，只在服务间使用；Java 若对外暴露（例如 login 把它转给客户端）必须先映射成 tip 码（基线 login 统一推 3023）。
8. match 的消息号在 Java gate 现在回 `MessageContent.error_message{kServiceUnavailable}`；客户端 BattleClient / TeamClient 对这一形态的处理是否友好（会不会卡在「排队中」相位）未验证。
9. 组队与 match 同进程（Go 是为了共享 snowflake 与票据域），Java 是否也合并成一个 `xm-match` 进程？team_id / battle_id / challenge_id 共用发号器这一点影响模块边界。
10. battle 节点（C++ 回合制引擎 + 直连 + 结算事件）不在本区域，规模 XL，需要单独的清单；本清单中 match-gather / spectate / ticket / rating 全部以它为前置。
11. GetActivityList 归属上是 scene 任务区域，本区域只是被点名「activity schedule」才列出；ActivitySchedule 不被任何 Go 服务消费。
