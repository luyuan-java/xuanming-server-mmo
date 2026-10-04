# 功能清单：scene 节点核心 + 玩家生命周期（mmorpg 26ceb70ca → xuanming Java）

## 区域概述

mmorpg 的 C++ scene 节点（`cpp/nodes/scene`，库 `cpp/libs/services/scene/{player,core,world,frame,actor}`）拥有场景实体与玩家实体：
gate 经 muduo RPC 投递 `PlayerEnterGameNode` / `ProcessClientPlayerMessage`，scene 从 zone Redis 异步载入 `PlayerAllData`
（login 经 Kafka→DB 服务预热），在 20 FPS 的 `World::Update` 里跑挂机、AOI、移动、Buff、属性计算与同步；玩家写回走
「Redis owner_epoch CAS + Kafka DBTask」，带脏快照比较、退出收敛、断线释放标记（A1′/A2′）与跨节点 / 跨 zone 归属交接（冻结 + handoff 标记 + 看门狗）。
场景实例由 Go scene_manager 按 World / Dungeon / Mirror 表经 gRPC `CreateScene` 下发，scene 只登记；客户端换图（63）、跨 zone 传送（226）、镜像副本都绕 scene_manager 往返。
Java 版（xm-scene）已有：进场与初始同步、场景内换图、移动 / AOI / 66、owner_epoch 围栏写回（MySQL）、顶号、停服写回；
缺：属性点 / 方案 / 面板（167–175）、等级经验、周期存盘、跨节点换图、副本 / 镜像、断线重连租约、跨 zone 传送、GM 闸等（ActorActionState 已随 2.6 接入）。
状态判定以 `D:\work\xuanming-server-mmo` 的 grep 结果为准（2026-10-02）。

## 功能

### scene-frame-loop — 场景固定步长帧循环（20 FPS）
- mmorpg: cpp/libs/services/scene/world/world.cpp（World::Update）、frame/manager/frame_time.h、core/constants/fps.h、nodes/scene/main.cpp（DependencyGate 等 SceneManager + 号段就绪后才 RunEvery）
- client messages: none（驱动 47/64/66/137 等推送）
- tables: none
- depends on: none
- behavior: 20 FPS；累加器夹 [-1s,1s]，每次回调最多模拟 5 帧、超出的整帧只扣不补；帧内顺序 Afk → AOI → Movement → MovementAcceleration → Buff → 属性计算 → 属性同步(66) → 帧号+1。
- internal: 单 EventLoop 线程拥有全部 ECS 状态；帧号 current_frame 是挂机判定、66 节奏的时钟。
- java: done — xm-scene `SceneTicker` + `SceneWorld.step`（外推 → 视野 → 偶数帧 66）；无 Buff / 属性计算阶段（随战斗 / 属性功能补）。
- size: S
- robot: robot_smoke（stress AI）、xm-robot MovementScenario
- hazards: 基线 main.cpp 在 SceneManager 连上且 GUID 号段首段领到之前不启动帧定时器（Java 无号段依赖）；帧内不得做阻塞 I/O。

### scene-client-dispatch — 客户端消息进 scene 的统一入口
- mmorpg: nodes/scene/handler/rpc/scene_handler.cpp（ProcessClientPlayerMessage / SendMessageToPlayer / InvokePlayerService）、rpc/player/player_service_interface.cpp
- client messages: 所有 OptionIsPlayerService 客户端服务（gate 发 10 SceneProcessClientPlayerMessage 转入）
- tables: none
- depends on: player-enter-load
- behavior: session→player_id 映射查不到 / 消息号未知 / 方法不存在 / 解析失败一律静默丢弃不回包；路由带的 player_id 与 SessionMap 不符（gate 节点号复用造成串号）丢弃；会话不是实体当前会话（重连后的旧会话）丢弃并删映射；加载中收到 ExitGame(9) 取消加载；实体处于退出中（UnregisterPlayer）时除 ExitGame 外一律回 error_message=1006；非 Empty 应答才回包，message_id 同请求、id 回显；刷新 LastActiveFrame（退出中不刷新）。
- internal: 生成的 CallMethod 末尾 TRANSFER_ERROR_MESSAGE 把进程级 TLS TipInfoMessage 整体覆盖进应答的 error_message。
- java: done — `xm-scene/.../world/ClientRequestHandler.java`（会话 + player_id 校验、解析失败丢弃、Empty 不回包、未实现方法回 1006、touch 刷新活跃帧）。「退出中」状态 Java 不存在（离场即移除，后到消息按会话找不到丢弃），不适用。
- size: M
- robot: robot_smoke、MovementScenario
- hazards: 基线 handler 若直接写 response->error_message 会被 TLS 空 tip 覆盖成成功（ReleaseSkill 的 1001 实际下发为 0；player_scene_handler 曾因此 7 处拒绝全部失效）——Java 如实回码属有意差异，2026-10-03 起在 PARITY「放技能 84 的校验链」行登记（此前只写在 Javadoc 里）；GM 分支必须清 TLS tip，否则污染下一条请求。

### request-field-sanity-check — 请求字段规模与负数校验
- mmorpg: scene_handler.cpp（三个入口都调）、cpp/libs/engine/core/utils/proto/proto_field_checker.cpp、network_constants.h（kProtoFieldCheckerThreshold=20）
- client messages: 全部 scene 客户端请求
- tables: none
- depends on: scene-client-dispatch
- behavior: 任一 repeated / map 字段元素数 > 20（递归进非 repeated 子消息）或任一 int32/int64（含 repeated）为负 → ProcessClientPlayerMessage 静默丢弃不回包；InvokePlayerService 路径回 kArraySizeTooLargeInMessage / kNegativeValueInMessage。
- internal: 反射遍历描述符，与具体方法无关。
- java: done（2026-10-02，批次 2.1）— `RequestFieldCheck`，scene 分发入口解析后、GM 闸前执行；单测 `RequestFieldCheckTest`。
- size: S
- robot: none
- hazards: 只检查 int32/int64，不查 sint/uint；不递归进 repeated 子消息；阈值 20 对 AllocateAttributePoints 的 map 足够（维度 4 个），以后若有合法 >20 的列表会被静默吞掉。

### player-enter-load — 玩家进节点：异步载入、建实体、进场
- mmorpg: scene_handler.cpp PlayerEnterGameNode；player/system/player_lifecycle.cpp（HandlePlayerAsyncLoaded / HandlePlayerAsyncLoadFailed / InitPlayerFromAllData / EnterScene）；core/system/redis.cpp（AsyncLoad 回调接线）；player_database_loader.cpp
- client messages: 79 NotifyEnterScene (S2C push)、21 NotifyActorCreate（自己）、23 SendTipToClient{3023} (S2C push，失败时)
- tables: BaseScene（出生点 / 导航）
- depends on: owner-epoch-fencing, player-persistent-data-model, enter-scene-initial-sync
- behavior: 先预登记 session→player 让加载期间的消息命中「未加载」分支；同玩家在途加载被新会话覆盖（旧会话映射删除）；加载期间会话被取消则放弃建实体；Redis 读失败回 23{3023}；NIL（重试 500ms…16s 后）当新号；scene_id 不在本节点时 fail-closed 回 3023 且不置登录态、不触发 PlayerLoginEvent；节点上已有该玩家实体（重连 / 顶号）则复用实体只重绑会话与场景。
- internal: Redis key 由 login 经 Kafka→DB 服务预热；owner_epoch / home_zone 随路由透传，epoch 取 max；A2′ 先核归属再删继承 handoff 标记，未确认不建实体。
- java: done — `SceneWorld.onPlayerEnter/onPlayerLoaded` + `StoragePlayerRepository`（MySQL 存储线程池载入、epoch 校验、加载中取消、失败回 PlayerEnterResult{3023} 由 gate 推 23）。Redis 预热 / NIL 重试 / A2′ 标记不适用（Java 直读 MySQL，CreatePlayer 已建行）。
- size: L
- robot: robot_smoke（login_ok/enter_ok）、login_test DisconnectDuringEnter / RapidReconnect、xm-robot SmokeScenario
- hazards: 基线 EnterScene 找不到 scene 时若照旧置 enter_gs_type，重试后 PlayerLoginEvent 永不补发（已修）；加载回调必须以「待入场表仍是同一条」为准，否则旧会话迟到的 ExitGame 会杀掉新玩家。

### enter-scene-initial-sync — 进场初始同步（79 → 自己 21 → 47 / 旁人 21）
- mmorpg: player/system/player_scene.cpp（HandleEnterScene）、spatial/system/scene_spawn.cpp（EnsureValidEnterLocation）、view.cpp
- client messages: 79 NotifyEnterScene、21 NotifyActorCreate、47 NotifyActorListCreate、51 NotifyActorDestroy（旧场景观察者）(S2C push)
- tables: BaseScene（spawn_x/y/z、nav_bin_file）
- depends on: aoi-view（spatial 区域）
- behavior: 已在目标场景幂等跳过；同图换线保留坐标、换地图落出生点；坐标不在导航网格上落出生点；先发 79 再补发「自己」的 21（AOI 遍历跳过自身，客户端靠 guid==player_id 绑定本地角色）。
- internal: BeforeLeaveScene 事件清 AOI；ScenePlayers 集合维护。
- java: done — `SceneWorld.enterScene` / `resolveEnterPosition`（无导航网格：坐标 (0,0,0)、越界或换图落 BaseScene 出生点，兜底 (180,200,0)）。
- size: M
- robot: robot_smoke、MovementScenario 第 1/3 步
- hazards: 基线新号坐标为 (0,0,0)，不校验会导致首次 MoveAck 把客户端拉回原点（2026-09-05 诊断）。

### player-persistent-data-model — 玩家持久化数据模型（PlayerAllData 全量）
- mmorpg: player/system/player_database_loader.cpp、player_database_1_loader.cpp、player_data_loader.h、bag_marshal.cpp、mission_marshal.cpp；proto/common/database/mysql_database_table.proto
- client messages: none（间接影响所有面板）
- tables: Class（初值）
- depends on: owner-epoch-fencing
- behavior: 一份 player_database 行同时恢复 transform、uint64/uint32 组件（registration_timestamp、class）、技能列表（去重 + 删表里不存在的）、基础属性（BaseAttributes）、等级（压回 85 上限）、属性加点组件、宠物、角色名只读副本、背包、任务、资产账本（校验坏了只关资产通道不阻断登录）、战斗结算幂等账本、货币；player_database_1 另一份。
- internal: 存盘 = 整份 marshal → Redis SET（owner_epoch CAS）+ 两条 Kafka DBTask 落 MySQL；资产 / 账本必须同一次落盘（不变量 I3）。
- java: partial — `xm-player-store` 的 `player` 表只有 class_id / gender / appearance_id / level / scene_config_id / pos_x,y,z（`xm-player-schema.sql`、`PlayerSave` 只写 level + 场景 + 坐标）；朝向、技能、属性、背包、货币等全无存储位置。需要设计可扩展的玩家数据载体（按系统分表或 proto blob 列）并保证同事务落盘。
- size: L
- robot: MovementScenario 重登核对坐标
- hazards: 基线 class_id 未随 PlayerAllData 下发，初值与 ResolveClassRow 一律取 Class 表首行；角色名副本只读不写。Java 若拆多表必须在一个事务里与 owner_epoch 围栏一起写，否则重现「资产落了账本没落」。

### player-exit-save — 离场：停运动、摘场景、写回、收敛后销毁
- mmorpg: player_lifecycle.cpp（HandleExitGameNode / FinishExitAfterPersist / StopMotionForExit / DetachFromScene / DestroyPlayer）、player_exit_intent.h、handler/rpc/player/player_lifecycle_handler.cpp（9 ExitGame）、s2s_player_scene_handler.cpp（69 LeaveScene）
- client messages: 51 NotifyActorDestroy（给看得见的人）(S2C push)；9 ScenePlayerExitGame（gate 断线 / 主动离开代发，内部）
- tables: none
- depends on: owner-epoch-fencing, player-persistent-data-model
- behavior: 挂 UnregisterPlayer + 退出意图（抄会话、原因）→ 清速度 / 加速度 → 摘场景（旁人收 51）→ 登出快照 → 存盘；落地后比对内存，不一致重存（有上限），一致才销毁；退出中只放行 ExitGame；重复退出合并原因；快路径（盘上已是同一份）且无在途存盘时当场收尾。
- internal: 收尾写断线释放标记（A1′）、消费疏散票据、撤回 handoff 标记（退出优先作废交接）。
- java: done — `SceneWorld.onPlayerLeave/removePlayer/onLinkClosed` + `StoragePlayerRepository` 写回并释放（epoch 围栏，离场先清速度再写回含 z）；无「退出中」中间态，A1′/A2′ 标记不适用（Java 用 owner_released 列）。
- size: M
- robot: login_test LoginLogoutCycle / LeaveAndReEnter、MovementScenario 重登
- hazards: 基线 DetachFromScene 曾漏摘 ScenePlayers / Hex（实体 id 复用后排空会踢错人、重连后谁也看不见）；退出期间仍有改动源时重存不收敛，靠上限 + 停机看门狗兜底。

### periodic-dirty-save — 在线周期存盘 + 脏比较快路径
- mmorpg: core/system/redis.cpp（SCENE_PLAYER_SAVE_INTERVAL_SECONDS，默认 300，0 关闭）、player_lifecycle.cpp SavePlayerToRedisImpl、player/comp/last_persisted_snapshot_comp.h、dirty_save_stats.h
- client messages: none
- tables: none
- depends on: player-persistent-data-model, owner-epoch-fencing
- behavior: 每秒一个槽，只存 playerId % interval == 槽号的玩家（每人每周期恰好一次、单次工作量 N/interval）；marshal 后与上次落盘快照逐字节比较，相同则跳过 Redis + Kafka；落盘成功回调才更新快照；首存总是写；停机开始即停周期存盘。30s 一行 [DirtySave] total/skipped/skip_pct。
- internal: 战斗结算后立即压一次存盘（缩短「已应用未落盘」窗口）。
- java: missing — `StoragePlayerRepository` 注释明确「只在离场时写回、没有周期存盘，进程被 kill 时本次在线增量会丢」；architecture.md §10 列为后续。
- size: M
- robot: none（基线 stress_summarize 统计 skip 率）
- hazards: 快照必须在压测探针打点之前比较，否则快路径永远失效（R2）；快照只在实体上，重连重建实体后首存必写；Java 实现时写回需带 epoch 围栏、在存储线程池执行，且不能让周期写与离场最终写乱序（后发先至会用旧数据覆盖）。

### save-failure-durability — 存盘失败的保留与重试
- mmorpg: player_lifecycle.cpp HandlePlayerAsyncSaveFailed / HandlePlayerSaveRejected / DestroyDeposedPlayer；engine MessageAsyncClient（保留最新 payload、封顶退避重试）
- client messages: none（被废黜时实体静默销毁）
- tables: none
- depends on: owner-epoch-fencing
- behavior: 连续失败到告警阈值只记 ERROR，最新 payload 继续封顶退避重试；不更新落盘快照（保证下次必重写）；退出中的实体保留直到存盘成功（停机看门狗兜底）；CAS 拒绝 = 已被废黜，终态销毁实体、不再存盘、不改派。
- internal: 计数 [OwnerEpoch] stale_owner_write_rejected（应恒 0）。
- java: partial — `StoragePlayerRepository` 对瞬时故障 5s 内退避重试最多 3 次，用尽记 ERROR + 计数后放弃（进度丢失）；围栏拒绝计 fenced 不重试、实例已移除。缺「保留最新状态持续重试直到成功」。
- size: S
- robot: currency_crash_window（基线）
- hazards: 基线 Redis 键与 DB 服务回写的分表键是两套命名空间，存盘失败没有下游修复（player-async-save-loss-windows.md）；Java 若改成持续重试需防止与新 epoch 的写者交错（围栏已兜住）。

### owner-epoch-fencing — 玩家数据归属围栏（单写者）
- mmorpg: player/comp/player_ownership_comp.h、player_lifecycle.cpp（EnterScene 第 0.5 步、SavePlayerToRedisImpl 的 CAS、DiscardStaleHandoffEntity / DiscardDeposedEntityOnReentry）、exit_release_mark.h；go/scene_manager owner_epoch.go
- client messages: none
- tables: none
- depends on: none
- behavior: 路由带 owner_epoch（0 = 旧版不覆盖），实体按 max 记；存盘 Redis 写带 Lua CAS、DBTask 带 epoch；进场命中本节点旧实体且 epoch 至少 +2（中间有别的持有者）时不存盘销毁后重载；home_zone 决定落库 topic（未知时 fail-closed 用进程 zone 并计数）。
- internal: [OwnerEpoch] 三个健康计数；A1′/A2′ handoff 标记协议。
- java: done（机制不同，PARITY「玩家数据归属」行登记为 Java 内部）— `PlayerStore` owner_epoch / owner_released / owner_lease_until，夺权 / 续约（`OwnerLeaseRenewer`）/ 释放，写回带围栏；跨 zone 的 home_zone 不适用（Java 不跨 zone）。
- size: L
- robot: login_test AccountDisplacement / RapidReconnect
- hazards: 基线允许 epoch=0 跳过 CAS（滚动升级窗口），等于假防护；Java 任何新写回路径（周期存盘、资产写）都必须走同一围栏。

### reconnect-resume — 断线重连回原节点、复用实体（断线租约）
- mmorpg: player_lifecycle.cpp EnterScene 第 0 步 CancelExitOnReconnect、scene_handler.cpp PlayerEnterGameNode 第 2 步（实体在线直接 EnterScene）；PlayerEnterGameStateComp.enter_gs_type（LOGIN_FIRST / RECONNECT / REPLACE）；go/login 30s 断线租约（玩家位置记录保留到租约到期）
- client messages: 79 / 21 / 47（重新进场的初始同步）
- tables: none
- depends on: player-exit-save, player-enter-load
- behavior: 租约内重登被 login 路由回原节点；实体若还在（退出存盘在途）则取消退出、复用内存态（不回档），旧会话映射删除；PlayerLoginEvent 只在首次进场触发（重连不重复触发业务结算）；换会话且战斗在途时推重连提示（battle）。
- internal: enter_gs_type 由 gate/login 给出；scene 侧 PlayerLoginEvent 处理器对三种类型目前都是 TODO。
- java: partial — 断线即写回并释放，重连从 MySQL 重载，同图坐标保留（MovementScenario 已验证）；没有 enter_gs_type、没有「复用仍在内存的实例」、login 的 30s 断线租约「回原位」未做（PARITY 登录行「短线重连」待做）。
- size: M
- robot: login_test RapidReconnect / RapidDisconnectReconnect / AccessTokenReconnect
- hazards: 复用实体必须确认 epoch 没被别人推进过（否则回档），基线用 Discard* 两道判断；客户端可见差异只在于是否重发登录类业务事件。

### takeover-kick — 顶号（同角色新连接进场，旧连接被踢）
- mmorpg: scene_handler.cpp PlayerEnterGameNode（RemovePlayerSessionSilently + 复用实体重绑会话）；go/login ReplaceLogin；gate 发 34 KickPlayer
- client messages: 34 KickPlayer (S2C push)、23 SendTipToClient{2017}
- tables: none
- depends on: owner-epoch-fencing
- behavior: 新会话覆盖 SessionMap，旧会话的迟到消息因 snapshot.gate_session_id 不符被丢弃；旧客户端收 34 后断开。
- internal: login 侧主导。
- java: done — 归属协议 + `OwnerTakeoverSubscriber` / `SceneWorld.onTakeoverRequested`（旧实例写回释放、旧连接收 23{2017} 后断开）；不发 34（PARITY 已登记差异）。
- size: M
- robot: login_test AccountDisplacement / ConcurrentSameAccount
- hazards: 持有者 3s 内不让出 Java 回 2005（基线不会出现该码）。

### scene-instance-registry — 场景实例的建立 / 销毁（scene 侧登记）
- mmorpg: scene_handler.cpp CreateScene / DestroyScene（muduo 36/121）、handler/grpc/scene_node_service.cpp（SceneNodeGrpc 122/123，两处必须同步）、handler/event/scene_event_handler.cpp（OnSceneCreated/Destroyed → Agones 计数）
- client messages: none（建好后客户端经 79 的 scene_info 看到 scene_id / config / mirror / dungeon id）
- tables: World / Dungeon / Mirror / BaseScene（由 scene_manager 选）
- depends on: world-channels-from-tables
- behavior: config_id / scene_id 任一为 0 拒绝；按 scene_id 幂等（已存在回原 info）；SceneInfoComp 记 scene_config_id、scene_id（Go 分配，全局唯一）、mirror_config_id、dungeon_config_id、creators；Agones 未分配时 fail-closed（muduo 路径非阻塞）；DestroyScene 场景里还有人则先排空改派、实体保留到排空后再销毁；找不到 scene 幂等 OK。
- internal: scene_manager → scene 的 gRPC；Agones GameServer 单元计数。
- java: partial — `SceneNode` 启动时按 World 表每个配置 `createScene` 一个场景（`ConfigSceneTables.worldSceneConfigIds`），scene_id 用本节点雪花；无运行期 Create/Destroy 接口、无 mirror/dungeon 字段填充、无 creators。
- size: M
- robot: none（间接：robot_smoke 进场）
- hazards: 基线 InitSceneManagerReply 不在生成的 register 文件里，曾被 regen 抹掉导致所有 SM 应答静默丢弃（f1b110bcc）；两个 CreateScene 入口字段必须一致。

### world-channels-from-tables — 主世界频道按 World 表建立与扩缩容
- mmorpg: go/scene_manager/internal/logic/world_init.go、world_autoscale.go、world_rebalance.go、node_selection.go（scene 节点类型 kMainSceneNode / kMainSceneCrossNode / 副本节点）
- client messages: 79 的 scene_info（频道 = 同 config 的不同 scene_id）
- tables: World（scene_id → BaseScene）
- depends on: scene-instance-registry
- behavior: 每个 zone 每张 World 地图保持 N 个频道（MinChannelsPerMap 默认 1，按负载扩缩容，带冷却与排空集合），节点重启后对所有频道重发 CreateScene（幂等）；只挑主世界类型节点承载频道。
- internal: Redis 锁串行化补建；scene_manager 领导者执行。属 scene_manager 区域，scene 节点只被动登记。
- java: partial — 每个 scene 节点各自为每张 World 地图建 1 个场景、经 Redis 节点目录上报（`SceneDirectoryPublisher`），`xm-scene-manager` `SceneAssigner` 挑人数最少者（PARITY：不预占名额、不跨 zone）；无频道数配置、无扩缩容 / 排空。
- size: L
- robot: robot.stress-*（基线多频道）
- hazards: 缩容排空依赖 scene 的 BeginSceneDrain；频道上有镜像时不得缩容（channelHasMirrors）。

### scene-switch-same-node — 客户端换场景（63，本节点内）
- mmorpg: handler/rpc/player/player_scene_handler.cpp EnterScene；player_scene.cpp HandleEnterScene
- client messages: 63 EnterScene (C2S)、79 / 21 / 47 / 51 (S2C push)
- tables: World、BaseScene
- depends on: enter-scene-initial-sync
- behavior: 校验顺序：节点类型非主世界 3004 → 战斗在途 3023 → 换图在途 / 交接在途 3014 → 三个 id 全 0 → 3005 → mirror_config_id>0 且 scene_id==0 走镜像分支 → 已在目标 scene 3008 → 无会话快照 3005 → 无 SceneManager 1003；通过后应答无错 = 已受理，实际落位由 scene_manager 路由回来的进场驱动。
- internal: 基线即使目标在本节点也经 scene_manager 往返（RequestSceneChange 带 correlation_id 单槽在途）。
- java: done（PARITY「场景内换图」）— `ClientRequestHandler.enterScene` + `SceneWorld.resolveSwitchTarget/switchScene` 同步完成；3004/3014/战斗在途的 3023 不会出现；镜像与跨节点回 3023。
- size: M
- robot: robot_smoke stress AI scene_switch、login_test SceneSwitch
- hazards: 基线成功应答先于实际换图，客户端在收到 79 前再次发 63 会被 3014 拒；Java 同步完成，客户端看不到 3014。

### scene-switch-cross-node — 跨节点换场景（63 目标在别的 scene 节点）
- mmorpg: player_scene_handler.cpp EnterScene → PlayerLifecycleSystem::RequestSceneChange / DispatchEnterSceneReply / DispatchEnterSceneTransportFailure / HandleSceneChangeEnterSceneReply；rpc_replies/scene_manager_response_handler.cpp；player/comp/player_ownership_comp.h（PlayerSceneChangeInFlightComp）；go/scene_manager enterscenelogic.go / reentry_barrier.go
- client messages: 63 EnterScene (C2S)、23 SendTipToClient{3023 / 1003} (S2C push，失败)、目标节点的 79 / 21 / 47 (S2C push)、源场景旁人的 51
- tables: World、BaseScene
- depends on: scene-switch-same-node, ownership-handoff, world-channels-from-tables
- behavior: 应答「无错」只代表已受理；scene_manager 回 18（ErrHandoffPending）→ 起同 zone 归属交接后重发；其它业务失败补推 23{3023}（否则客户端一直等 79）；gRPC 传输失败（结果未知）对玩家请求推 23{1003}；队伍跟随（服务器代发）被拒只记日志；路由把玩家放进新场景即摘在途记录（不等 gRPC 应答）。
- internal: 每条 EnterScene 带线程内单调 correlation_id，应答按号对回；单槽在途记录 + 发送侧闸 IsSceneChangeBusy（TTL = SM deadline + 1s）。
- java: missing — `ClientRequestHandler.enterScene` 目标不在本节点回 3023（`resolveSwitchTarget` 只查本节点）；需要：scene-manager 选目标节点 → 源节点写回并释放 / 交出归属 → gate 改绑到目标节点链路 → 目标节点进场。
- size: L
- robot: robot_smoke stress scene_switch（多节点时）
- hazards: 基线没有 correlation_id 时应答按 player_id 串号，曾把普通换图的迟到应答当成交接证据（误删标记、误踢线）；Java 若做成「释放 + login 式夺权」需保证 gate 会话不断、客户端只看到 51/79 序列。

### ownership-handoff — 归属交接：冻结、落盘、出示标记、裁决
- mmorpg: player_lifecycle.cpp（StartTravelHandoff / BeginTravelHandoff / RequestTravelEnterScene / ResolveTravelOutcome / JudgeTravelOutcomeReply / AbortTravelHandoff / ConcludeHandoffAfterMarkSent / EnforceTravelFreezeCaps / WithdrawHandoffMark）、player_frozen_comp.h、travel_freeze_cap.h、handoff_mark_withdraw.h、player_ownership_comp.h；docs cross-zone-scene-travel.md
- client messages: 23 SendTipToClient（3023 / 3027 / 13000）、34 KickPlayer (S2C push)、124 RedirectToGate（跨 zone）
- tables: none
- depends on: owner-epoch-fencing, periodic-dirty-save（同一存盘通道）
- behavior: 冻结期间所有业务写（AOI / 战斗 / 货币 / 背包 / 移动 / 属性）被丢弃，客户端消息静默丢；存盘落地后 SET player:{id}:handoff "{epoch}:{ms}" EX 300 再请求 EnterScene；结局三选一：到达（目标节点 79 或 124）/ 原地解冻 + 失败 tip / 无法原地恢复则 tip + 34 + 不存盘销毁（客户端回选服重登）；最迟约 71s 出结论（存盘 30s、应答 30s 看门狗、35s 晚发窗口、70s 冻结上限）。
- internal: Redis Lua 原子取证（删本族标记再读 epoch）、回滚回执采纳（B5）、[TravelHandoff] 30s 计数行。
- java: missing — Java 归属在 MySQL（owner_epoch + released + lease），可用「源节点写回并释放 → 目标节点夺权加载」替代整套标记协议，不需要照抄。
- size: XL（Java 版按自身归属协议重做约 L）
- robot: travel_smoke（基线）
- hazards: 冻结中实体仍在场景里，必须由每个业务系统主动查 IsCrossZoneFrozen（二十余处），漏查即分叉；不变量 I3：未冻结即一定仍是属主；退出优先（交接在途时断线作废交接）。

### zone-travel — 跨 zone 传送（226 TravelToZone）与重定向
- mmorpg: player_scene_handler.cpp TravelToZone → PlayerLifecycleSystem::RequestZoneTravel；client_player_common.proto RedirectToGate(124)；go/scene_manager gate_redirect.go、home_zone.go
- client messages: 226 TravelToZone (C2S)、124 RedirectToGate (S2C push)、23 SendTipToClient (S2C push)、34 KickPlayer (S2C push)
- tables: World（sceneConfigId 非 0 必须是 World 表地图）
- depends on: ownership-handoff, owner-epoch-fencing
- behavior: 同步拒绝：目标 zone 为 0 或等于本 zone 3024；地图不在 World 表 3007；战斗 / 备战在途 3025；在队伍中 3026；交接在途 13000、换图在途 3014；受理后结局见 ownership-handoff；访客期间存盘按 home_zone 落库，回家不回档。
- internal: 数据不搬家，目标 zone 直接从 home 盘加载；gate 票据带 zone。
- java: missing — Java 不跨 zone（PARITY 场景分配行「不跨 zone」）；未实现时 Java 对 226 回 `TravelToZoneResponse{error_message=1006}`（ClientRequestHandler.replyUnavailable）。
- size: L
- robot: travel_smoke（T1–T6）
- hazards: 客户端预算须大于服务端最坏结论时长（约 71s）；124 可能先于 226 应答到达；gate-cmd 消费滞后 >30s 时成功的传送也可能被踢回选服。

### mirror-scene — 镜像场景（以当前主世界为模板开私有副本并自动进入）
- mmorpg: player_scene.cpp RequestEnterMirrorScene；player_scene_handler.cpp EnterScene 镜像分支；rpc_replies/scene_manager_response_handler.cpp（CreateScene 应答回显 creator_ids → 代发 EnterScene；传输失败推 1003）；go/scene_manager createscenelogic.go / instance_lifecycle.go（MirrorIdleTimeoutSeconds）
- client messages: 63 EnterScene{mirror_config_id>0, scene_id=0} (C2S)、79 / 21 / 47 (S2C push)、23{1003} (S2C push)
- tables: Mirror（scene_id → BaseScene、main_scene_id → World）
- depends on: scene-instance-registry, scene-switch-cross-node
- behavior: 拒绝镜像的镜像、源场景无 scene_id、战斗在途、无 SceneManager（同步回 3005）；受理只代表「已请求创建」；应答回来若玩家已离开或换图在途则放弃自动进场（空镜像由 SM 按空闲超时回收）；scene_id>0 + mirror_config_id>0 = 加入已有镜像，走普通换图；镜像「尽量」与源同节点，落到别处走 18 交接。
- internal: CreateScene(SCENE_TYPE_INSTANCE, source_scene_id, mirror_config_id, creator_ids) → SM。
- java: missing — `ClientRequestHandler.enterScene`：mirror_config_id≠0 且 scene_id==0 回 3023，无 Mirror 表使用。
- size: M
- robot: none（基线 features_smoke 未覆盖镜像）
- hazards: SM 业务失败应答不回显 creator_ids，创建者永远收不到失败提示（基线已知缺口）；有镜像的频道不得被缩容。

### dungeon-instance — 副本实例场景（Dungeon 表）
- mmorpg: SceneInfoComp.dungeon_config_id（scene_handler.cpp / scene_node_service.cpp 只登记）；go/scene_manager createInstance / instance_lifecycle.go（空闲超时销毁、孤儿回收）；PVE 实际玩法在回合制 battle 节点按 Dungeon.monster 生成（go/match gather.go）
- client messages: 79 的 scene_info.dungeon_config_id
- tables: Dungeon（scene_id、max_team_size、time_limit、monster×3）
- depends on: scene-instance-registry
- behavior: scene 节点对副本场景没有专门玩法（不刷怪、不计时、不校验队伍人数）；SM 记录活跃副本，0 人超过超时即销毁（先排空）。
- internal: SM Redis `instances:zone:%d:active`、`instance:%d:player_count`。
- java: missing — Java 无运行期建场景；Dungeon 表仅由 xm-table 生成访问代码。
- size: M
- robot: battle_smoke（PVE 走战斗节点，非场景副本）
- hazards: Dungeon 表有 max_team_size=10 的历史行（match 侧代码收口）；副本「时间限制」在 scene 侧未实现，移植时勿当成已有行为。

### scene-drain-relocate — 场景排空 / 节点疏散改派到主世界
- mmorpg: player_lifecycle.cpp（BeginSceneDrain / BeginEmergencyRelocateAll / EnqueueRelocateTicket / DispatchEmergencyRelocate / SendEmergencyRelocateEnterScene / IsEmergencyRelocateDrained）；main.cpp SetOnConflictShutdown / SetConflictDrainComplete
- client messages: 新节点的 79 / 21 / 47（被改派玩家）、旧场景旁人的 51
- tables: World
- depends on: player-exit-save, scene-instance-registry
- behavior: 销毁有人的场景（缩容 / 副本回收）或节点身份冲突（etcd 租约丢失）时：抄会话 → 存盘 → 落地后写 handoff 标记并请求 SM EnterScene(0,0) 让它挑存活节点的大世界频道 → 摘会话销毁实体；已断线的只存盘。单场景排空不把节点标成疏散中。
- internal: 有界 drain 看门狗；改派必须等存盘落地（否则新节点读到旧数据回档）。
- java: missing — Java 丢节点号租约时只 `stopAcceptingEnters`、在场玩家照常服务到离开；无运行期销毁场景，也无改派。
- size: M
- robot: none
- hazards: ScenePlayers 残留陈旧实体 id 时排空会把别的场景里的活人踢走（基线已修）。

### graceful-shutdown-save — 停服全量写回与持久化屏障
- mmorpg: nodes/scene/main.cpp（SetBeforeShutdown exitAllPlayers / SetShutdownDrainComplete）、core/system/redis.cpp BeginShutdown
- client messages: none（连接随 gate 断开）
- tables: none
- depends on: player-exit-save, periodic-dirty-save
- behavior: SIGTERM：停号段续段、停 Agones、停帧与周期存盘 → 对每个在线玩家走 HandleExitGameNode(kNodeShutdown)；drain 谓词每轮重扫实体（停机瞬间排队的请求可能晚一拍建玩家），直到无玩家、无在途存盘、无在途释放标记、Kafka producer 冲刷完；受 Node drain 看门狗约束。
- internal: 日志 Shutdown drain progress。
- java: done — `SceneShutdown`（摘目录 → 停监听 → 停接管订阅与续约 → 断链 → 逻辑线程写回全部 → 有预算地等存储池排空，丢弃的写回逐条 ERROR）。
- size: M
- robot: none
- hazards: Java 断链后才写回，期间 gate 侧会话已收到断开；与基线一致都不给客户端发提示。

### gm-graceful-shutdown-rpc — GM 远程优雅停服（Scene.GmGracefulShutdown）
- mmorpg: nodes/scene/handler/rpc/scene_admin_handler.cpp（126 SceneSceneGmGracefulShutdown）、cpp/nodes/gate/gate_security.h（VerifyGmRequestFromEnv）
- client messages: none（运维面）
- tables: none
- depends on: graceful-shutdown-save
- behavior: 信封 HMAC 校验（密钥 GATE_GM_ADMIN_SECRET，未配置一律拒绝；canonical 串绑 method 名 "Scene.GmGracefulShutdown" 与本节点 node_id；时间窗 + nonce 去重）；拒绝打 ERROR 并截断 operator/reason 到 128 字节；通过则回 affected_count = 在线人数，并 queueInLoop 在应答发出后 RequestShutdown。
- internal: 旧代码 done->Run() 空指针必崩（已修）。
- java: missing — Java scene 只有 actuator health/prometheus，无 GM 停服接口（停服靠 SIGTERM / Spring 关闭）。
- size: S
- robot: none
- hazards: 停机必须延后到应答写出之后；签名必须绑节点号，否则一次抓包可重放停掉全区 scene。

### client-gm-gate — 客户端 GM 指令统一闸门
- mmorpg: scene_handler.cpp ProcessClientPlayerMessage GM 分支、handler/rpc/player/player_gm_guard.h（SCENE_RUN_MODE，默认 prod = 拒绝）；gate 第一道锁 gate_gm_client_messages.h
- client messages: 175 GmSetPlayerLevel、37/49/94/95 Gm*Currency、187 GmGrantPet、102–117 SceneRollbackClientPlayer Gm*（全部 C2S）
- tables: none
- depends on: scene-client-dispatch
- behavior: 方法名形如 `Gm`+大写字母即 GM 指令；非 dev/test 模式回应答体 error_message=1006（没有该字段则只丢弃），并清掉 TLS tip 防止污染下一条请求；每次拒绝 WARN 留痕；各 Gm* handler 内再判一次（防节点路由绕过）。
- internal: 前缀判据 + 描述符扫描单测守住不叫 Gm* 的后门。
- java: missing（目前无风险）— Java 未实现任何 Gm* 方法，统一回 1006；gate 亦无 GM 白名单（grep 无结果）。实现第一个 GM 指令前必须先做本闸（运行模式环境变量，默认拒绝）。
- size: S
- robot: travel_smoke / attribute_smoke 依赖 dev 模式下 GmAddCurrency / GmSetPlayerLevel 可用
- hazards: 基线 rollback 服务没标 OptionIsClientProtocolService，gate 清单挡得住、scene 入口不看该 option，曾可直连打入。

### derived-attribute-recalc — 二级属性重算（职业初值 + 自然成长 + 加点公式）
- mmorpg: player/system/player_attribute.cpp（Recalculate / ConvergeOverAllocation / StandardBaseAtLevelCap / AddAllocatedByFormula）、attribute_allocation_rules.h（TotalPoints / AllocatedIncrement / RescaleCurrent / FullInvestmentPoints）
- client messages: none（结果经面板 derived 字段与战斗快照可见）
- tables: AttributeRule（alloc_efficiency_bonus）、AttributePool（owner_type 0=角色 1=宝宝）、AttributeDimension（base_per_level、六项系数、pool_id）、AttributeAllocRatio（职业专属行优先、class_id=0 兜底）、Class
- depends on: player-class, player-level, player-persistent-data-model
- behavior: 六项（气血上限 / 法力上限 / 物攻 / 法攻 / 速度 / 防御）= 职业初值 + Σ(自然成长×等级 + 外部加成)×每点系数 + 已分配点按百分比公式（标准基础 = 职业初值 + 自然成长×85 级；增量 = 标准×比例×E(n)÷d）；max_health 至少 1；速度直写 BaseAttributes.speed、护甲每次按 Class.init_armor 直写；当前 HP/MP：升级按绝对增量补、降级只夹，其余（加载 / 加点 / 切方案 / 洗点）按比例保持（活着至少留 1，防「改属性当治疗」）；加载与等级变化时先收敛「已分配 > 总量」（整池清零返还）。
- internal: 宝宝共用同一套纯规则（PetSystem）；DerivedAttributesComp 不落库，每次登录重算。
- java: done（2026-10-03，批次 2.2）— `AttributeService.recalculate` / 纯规则 `AttributeRules`，同序累加逐值一致（真实配表单测）；当前气血 / 法力不持久化、进场回满（2.7 再持久化）。
- size: M
- robot: attribute_smoke（基线）
- hazards: 登录时 oldMax=0，补增量分支进不去，需靠 TopUpToDerivedMax 回满阵亡玩家；浮点 floor 取整，Java 用 double 同序累加才能逐值一致。

### attribute-panel — 属性面板查询与主动推送（167 / 170）
- mmorpg: handler/rpc/player/player_attribute_handler.cpp GetAttributePanel / NotifyAttributePanelChanged；player_attribute.cpp BuildPanel / PushPanel；player_event_handler.cpp PlayerUpgradeEventHandler
- client messages: 167 GetAttributePanel (C2S)、170 NotifyAttributePanelChanged (S2C push)
- tables: AttributePool、AttributeDimension、AttributeRule
- depends on: derived-attribute-recalc
- behavior: 全量面板（客户端零配表、整体覆盖）：只列角色池 / 角色维度；池 total / remaining / dimension_cap / unlocked / unlock_level / reset_cost_gold（低于 reset_free_below_level 免费）；维度 allocated / value（自然成长×等级+已分配+加成，只是点数）/ cap / sort；方案列表、active_scheme_id、max_schemes（表 0 → 1）、create_scheme_cost_gold（已有方案数 < free_scheme_count 免费）、switch_cooldown_until（last_switch + 冷却）；derived 六项 + 当前 health/mana；level。升级 / GM 改等级后主动推 170。
- internal: 写操作成功后应答都带全量面板（客户端不做增量合并）。
- java: done（2026-10-03，批次 2.2）— `AttributeFeature` 167 / 175 后推 170（只在等级变化后推，同基线）。
- size: M
- robot: attribute_smoke
- hazards: 首次访问用 EnsureComp 补默认方案（id 1、名「方案一」）——读接口也会写组件，Java 移植时注意这是「加载即补」而非读时副作用。

### attribute-allocate-reset-auto — 加点 / 洗点 / 自动加点建议（168 / 172 / 173）
- mmorpg: player_attribute_handler.cpp；player_attribute.cpp Allocate / Reset / AutoAllocate / CheckWritable；attribute_allocation_rules.h ValidateAllocation / DistributePoints
- client messages: 168 AllocateAttributePoints (C2S)、172 ResetAttributePoints (C2S)、173 AutoAllocateAttributePoints (C2S)
- tables: AttributePool、AttributeDimension、AttributeAutoPlan（class_id 专属优先、0 兜底；dimension/weight）、AttributeRule
- depends on: derived-attribute-recalc, attribute-panel, currency（扣金币）
- behavior: 写前置（168 / 172；173 自动加点不过写前置，只校验实体）：冻结中 1005、战斗在途 25011；168：pool_id=0 或 map 空 → 1005；池不存在 / 宝宝池 25000、锁定 25001、维度不属该池 25002、只增不减 25004、超单项上限 25005、超剩余 25003、无变化 25014；提交的是「目标已分配」全量幂等，缺省维度不变。172：本池已用 0 → 25014；先扣金币（不足 25012，扣费失败原样回货币码）再清点。173：只算不落；锁定 25001、无方案 25013、剩余 0 → 25014；有上限池按优先序灌满、无上限按权重比例；应答带 pool_id + suggested。成功应答（168/172）带全量面板。
- internal: 写后 Recalculate(kAllocate / kReset)。
- java: done（2026-10-03，批次 2.2）— 拒绝码与判定顺序同基线；冻结 / 战斗两道写前置待交接冻结（5.2 / 5.4）与回合制战斗（6.3）接入。
- size: M
- robot: attribute_smoke
- hazards: 拒绝码必须经 TLS tip 写入（直接写 response 会被覆盖成 0）；客户端按响应体 error_message 判拒绝。

### attribute-schemes — 加点方案：新建 / 切换 / 改名（174 / 171 / 169）
- mmorpg: player_attribute_handler.cpp；player_attribute.cpp CreateScheme / SwitchScheme / RenameScheme / IsSchemeNameValid / EnsureComp / SanitizeSchemes
- client messages: 174 CreateAttributeScheme (C2S)、171 SwitchAttributeScheme (C2S)、169 RenameAttributeScheme (C2S)
- tables: AttributeRule（max_schemes、free_scheme_count、create_scheme_cost_gold、switch_cooldown_seconds、scheme_name_max_len 默认 12）
- depends on: attribute-panel, derived-attribute-recalc, currency
- behavior: 写前置同加点（冻结 1005、战斗 25011，改名也拦）；174：达上限 25007；名字空 → 「方案N」；名非法 25009（UTF-8 码点数 ≤ 上限、无控制字符、截断序列非法、至少一个可见码点——全空格 / 零宽 / U+3000 等不算）；超出免费数扣金币（不足 25012）；scheme_id 取 next_scheme_id 自增；应答带 scheme_id + 面板。171：不存在 25006、已是当前 25010、冷却中 25008（Unix 秒、按墙钟）；切换后按 kSchemeSwitch 重算。169：名非法 25009、不存在 25006。
- internal: 加载时删掉表里已不存在的维度分配（改表自愈返还点数）。
- java: done（2026-10-03，批次 2.2）— 同上。
- size: M
- robot: attribute_smoke（含 switchSchemeWaitingCooldown）
- hazards: 冷却用墙钟 NowSecondsUTC，回拨时钟可提前切换；方案名校验与客户端 IsNullOrWhiteSpace 对齐，Java 用 codePoint 遍历时注意代理对与非法 UTF-8 已在 proto 解析层被拒的差异。

### gm-set-player-level — GM 设等级（175）
- mmorpg: player_attribute_handler.cpp GmSetPlayerLevel；player_attribute.cpp GmSetLevel；player_gm_guard.h
- client messages: 175 GmSetPlayerLevel (C2S)、170 NotifyAttributePanelChanged (S2C push)、宠物列表推送（184 NotifyPetListChanged）
- tables: AttributePool、AttributeDimension
- depends on: client-gm-gate, player-level, attribute-panel
- behavior: 非 dev/test 回 1006；冻结 / 战斗前置同加点；等级不在 1..85 → 1005；写 LevelComp 后触发 PlayerUpgradeEvent（重算 + 推 170 + 宠物重算推列表 + 任务条件 LevelUp）；应答带全量面板。
- internal: 直接写等级，不经经验。
- java: done（2026-10-03，批次 2.2）— 175 → 重算 → 推 170 → 应答；不推 184（宝宝 2.8）、不发任务等级事件（2.5）。
- size: S
- robot: attribute_smoke（dev 模式）
- hazards: 降级同样触发 PlayerUpgradeEvent（名字叫升级），HP 只夹不补；GM 设等级后若玩家未下线即崩，等级只在周期 / 离场存盘时落盘。

### player-level — 角色等级（上限 85、升级事件；无经验系统）
- mmorpg: player/system/player_level_rules.h（kMaxLevel=85、IsValidLevel、ClampToMaxLevel）、player_database_loader.cpp（加载压回上限）、nodes/scene/handler/event/player_event_handler.cpp PlayerUpgradeEventHandler；battle/system/player_battle.cpp（结算只打印 exp_gain，未入账）
- client messages: 170 NotifyAttributePanelChanged（等级变化后推面板，panel.level）
- tables: none（上限写死在代码；无经验表）
- depends on: derived-attribute-recalc
- behavior: 新号等级 1；读档等级 0 视为 1；>85 压回 85（上限曾从 200 下调）并在随后收敛中返还多余属性点；升级事件：重算属性 + 推面板 + 宠物等级跟随主人重算推列表 + 任务 ConditionEvent(kConditionLevelUp, new_level)。全仓没有经验值组件 / 升级结算，只有 GM 能改等级。
- internal: PlayerUpgradeEvent 是其它系统挂钩点。
- java: done（2026-10-03，批次 2.2）— `PlayerLevels`：读存档超上限压回 85（SceneWorld 进场），GM 设等级 1..85；经验系统两版都没有。
- size: S
- robot: attribute_smoke
- hazards: 基线 exp_gain 被静默丢弃（战斗奖励里的经验不入账），移植时不要当成已有行为。

### player-class — 职业（class_id 与 Class 表初值）
- mmorpg: player_attribute.cpp PlayerClassId / ResolveClassRow；player_database_loader.cpp ApplyClassInitialAttributesOrReviveFromTable；spatial/system/view.cpp（ActorCreateS2C.class_id / gender / appearance_id）；go/login createplayerlogic（建角选职业）
- client messages: 21 / 47 ActorCreateS2C.class_id（S2C push）；14 CreatePlayer.class_id（login）
- tables: Class（skill×3、init_health / mana / strength / armor / resistance / critchance / speed）、AttributeAllocRatio、AttributeAutoPlan
- depends on: none
- behavior: class_id 存在 PlayerUint32Comp.class；属性重算、自动加点按职业取行，取不到退回 Class 首行；新号初始基础属性与阵亡回满目前一律用 Class 首行（class_id 未随 PlayerAllData 打通）；不能转职。
- internal: none
- java: partial — 建角选职业与校验在 xm-login（`TableCharacterRules.classExists`，0 取首行），`player.class_id` 落库，`ScenePlayer.classId` 进 21 / 47；无任何职业属性效果（属性系统未做）。
- size: S
- robot: robot_smoke（建角）
- hazards: 基线「按首行」是临时口径，Java 若直接按真实 class_id 取行会与基线数值不同（Class 表各行初值目前相同，暂无差异）。

### new-player-init-and-revive — 首登初始化与登录复活回满
- mmorpg: player_lifecycle.cpp InitPlayerFromAllData（registration_timestamp ≤ 0 → 记注册时间、LevelComp=1、RegisterPlayerEvent → PlayerSkillSystem::RegisterPlayer）；player_skill.cpp（RegisterPlayer / SanitizeSkillList）；player_revive.h；player_database_loader.cpp（TopUpToDerivedMax）；InitializePlayerCompsEvent → PlayerMissionSystem::Initialize；SnapshotSystem::CaptureAndSend(SNAPSHOT_LOGIN)
- client messages: 77 ListSkills 的 skill_list、面板 health / mana
- tables: Class、Skill
- depends on: player-class, derived-attribute-recalc, player-persistent-data-model
- behavior: 首登授予 Class 表**全部职业**技能的并集（去重、跳过不存在的技能，持久化进技能列表）；每次加载删掉技能列表里表中已不存在 / 重复的技能；基础属性全 0（health/strength/speed 皆 0）= 新号按 Class 初值初始化，health==0 而成长属性在 = 阵亡，只回满 HP/MP 到二级属性上限；残血一律不动（残血带出战斗）；登录时抓一份登录快照供回档。
- internal: ViewRadius=10 在建实体时挂。
- java: partial — 技能 = `ConfigSceneTables.initialSkills()`（同样是全部职业技能并集，存在于 Skill 表），但不持久化、每次进场现算；无注册时间、无基础属性 / 复活、无登录快照、无任务初始化。
- size: S
- robot: robot_smoke（ListSkills 非空）
- hazards: 判新号只看三项为 0，若某职业 init_strength / init_speed 配成 0 会把阵亡玩家当新号重置成长属性。

### actor-action-state — 行为 / 状态互斥表（ActorActionState）
- mmorpg: actor/action_state/system/actor_action_state.cpp、constants/actor_state.h；combat/skill/system/skill.cpp（ReleaseSkill 调 TryPerformAction(kActorActionUseSkill, kActorStateCombat)）；handler/event/actor_event_handler.cpp（InterruptCurrentStateEvent）
- client messages: 84 ReleaseSkill 的拒绝 tip（表配的 state_tip）（打断事件的处理器为空，与 33 无关；33 是施法阶段的打断）
- tables: ActorActionState（按行为：每个状态的 state_mode 0=互斥 / 1=允许 / 2=打断 + state_tip）、ActorActionCombatState
- depends on: none（被技能 / 组队跟随 / 坐骑使用）
- behavior: 状态集合 Combat / TeamFollow / Mounted；执行行为前遍历当前状态：任一互斥 → 回该格的 state_tip；可打断的先发打断事件并移除状态；成功后加上成功状态。目前只有放技能接入（进入 Combat 状态）。
- internal: 先快照状态键再遍历（打断会删 map 元素，原写法 UB）。
- java: done（2026-10-03，批次 2.6）— `SkillRules.tryPerformAction / validateCombatStates / checkSkillPermission` 在 `SkillService.release` 里（表提示 1000 放行、0 按配置错回 1002；「战斗」状态永久加上、不持久化）；当前表数据下无客户端可见效果，见 PARITY「行为互斥表」行。
- size: S
- robot: robot_smoke（skill）
- hazards: Combat 状态加上后全仓没有移除路径（脱战逻辑在 combat_state 区域），移植前先确认基线是否会永久处于 Combat。

### actor-attribute-calculator — 运行时属性重算位（移速 / 战斗状态标志）
- mmorpg: actor/attribute/system/actor_attribute_calculator.cpp、constants/actor_state_attribute_calculator_constants.h、comp/actor_attribute_comp.h
- client messages: 66 ActorBaseAttributesS2C.combat_state_flags + entity_id (S2C push)
- tables: Buff（movement_speed_boost / movement_speed_reduction）
- depends on: attribute-sync-66, buff / combat_state（combat 区域）
- behavior: 每帧在属性同步之前处理脏位：kMoveSpeed = Σ buff 加速 − 减速、下限 0，写 MoveSpeedComp（不碰运动矢量 Velocity；移速属性目前无 S2C 通道）；kCombatState = 把 CombatStateCollectionComp 的键投影成 CombatStateFlagsComp 并置 66 脏位（含 entity_id）；kHealth / kEnergy 为 TODO 空实现；冻结实体跳过。
- internal: 位图脏标记组件。
- java: missing（移到路线图 2.7；当前数据下无客户端可见效果，见 PARITY「运行时属性重算位」行）— Java 66 只同步 transform / rotation / velocity（PARITY 属性同步行），无 buff / 战斗状态。
- size: S
- robot: none
- hazards: 旧实现把移速 buff 灌进 Velocity 导致角色沿 (1,1,1) 漂移并落库（已修）；旧实现战斗状态三层皆错（死组件、不置脏位、值写 false）。

### scene-kafka-command-ingress — Kafka 场景命令入口（SceneCommand → 进程内事件）
- mmorpg: nodes/scene/handler/event/scene_kafka_command_router.cpp、event_handler.cpp、main.cpp（SceneNodeHooks::KafkaCommandType = contracts::kafka::SceneCommand）；proto/event_id.txt；producers go/shared/kafkacmd、go/match/internal/team/notify.go 等
- client messages: none（间接触发各系统推送）
- tables: none
- depends on: none
- behavior: 消费本节点 topic 的 SceneCommand{event_id, payload}，缺 event_id / 空 payload / 解码失败只 WARN；按 event_id 反序列化成对应事件并在逻辑线程 dispatcher.trigger。当前用途：BattleSettlementEvent / BattleConfirmedEvent（battle → scene 结算与确认）、PlayerTeamRefreshEvent（match/team）等。
- internal: Kafka consumer group 每节点一个；事件号表 49 项与 C++ 事件类一一对应。
- java: missing — Java 未接 Kafka（architecture.md §10「Kafka 事件」后续），xm-scene 无外部异步命令入口。
- size: M
- robot: battle_smoke、team_smoke（基线）
- hazards: 进程内事件与外部命令共用 event_id 空间，Java 只需为真正跨进程的事件建入口，勿把纯进程内事件（BeforeLeaveScene 等）暴露成外部命令。

### node-player-route — 节点间对玩家服务的调用与多跳路由
- mmorpg: scene_handler.cpp SendMessageToPlayer(1) / InvokePlayerService(40) / RoutePlayerStringMsg(81) / RouteNodeStringMsg(78，空桩)、UpdateSessionDetail(24)
- client messages: 被调用的玩家服务方法的应答（经 gate 转给客户端）
- tables: none
- depends on: scene-client-dispatch
- behavior: 其它节点按 session_id 调玩家服务方法（同样的身份栅栏、旧会话丢弃、字段校验；InvokePlayerService 失败回 kSessionNotFound / kPlayerNotFoundInSession / kMessageIdNotFound / kRequestMessageParseError 等并把 TLS tip 放进 MessageContent.error_message）；RoutePlayerStringMsg 按 node_list 逐跳转发到下一 scene / gate，玩家在本地且无后续跳则本地命中（冻结中丢弃，IMPORTANT 优先级打 ERROR）；节点号按 (zone, node_id) 查，不能当 entt 实体整数。
- internal: muduo 节点 RPC；消息优先级 OptionMessagePriority。
- java: missing — Java 只有 gate→scene 链路的 ClientForward，没有「别的服务调玩家服务」通道（将来帮会 / 组队 / 交易需要时用 Dubbo 接口按 player_id 投递到所属 scene 的逻辑线程）。
- size: M
- robot: none
- hazards: 基线曾用 `entt::entity{node_id}` 直接当实体，可能路由到无关节点（R03 已修）；SendMessageToPlayer 不检查 GM 闸（节点路由不对客户端开放）。

### movement-sync-afk — 移动上行、纠偏、外推与挂机停推（已移植）
- mmorpg: handler/rpc/player/player_movement_handler.cpp、spatial/system/movement.cpp、player/system/afk.cpp、player/comp/afk_comp.h
- client messages: 134 MoveStart / 132 MoveSync / 131 MoveStop (C2S)、137 NotifyMoveAck (S2C push)、136 TeleportRequest (C2S)
- tables: none
- depends on: scene-frame-loop
- behavior: 详见 PARITY「移动」行与 docs/reference/mmorpg-client-contract-movement.md；600 帧无客户端消息停推。
- internal: none
- java: done — `SceneWorld.applyMove`、`MoveGuard`、`MovementRules`（有意差异已登记：非有限值丢弃、位移令牌桶、挂机停推清速度等）；136 回 1006（基线空桩回 id=0，已登记不适用）。
- size: L
- robot: xm-robot MovementScenario、robot_smoke
- hazards: 见 PARITY；朝向不持久化（Java 待补列）。

### aoi-view — 视野进出通知（已移植）
- mmorpg: spatial/system/{aoi,grid,view,interest}.cpp、scene_event_handler.cpp BeforeLeaveScene
- client messages: 21 NotifyActorCreate、47 NotifyActorListCreate、64 NotifyActorListDestroy、51 NotifyActorDestroy (S2C push)
- tables: none
- depends on: scene-frame-loop
- behavior: 详见 PARITY「视野」行与 mmorpg-client-contract-aoi.md（半径 10 m 三维含等号，进场顺序 79→21→47）。
- internal: none
- java: done — `ViewIndex` / `GridIndex`（双向通知、按距离重判、出视野 >20 m，均为已登记的有意差异）。
- size: L
- robot: MovementScenario 第 1/3 步
- hazards: 基线只在换格时判进出、51 发给 7 格内全部（Java 已修，mmorpg 待做）。

### attribute-sync-66 — 属性同步 66（已移植部分）
- mmorpg: actor/attribute/system/actor_state_attribute_sync.cpp、handler/rpc/player/player_state_attribute_sync_handler.cpp（空，S2C）
- client messages: 66 SyncBaseAttribute (S2C push)；65 / 55 / 82 / 68 / 75 SyncAttributeNFrames（基线定义但不发）
- tables: none
- depends on: aoi-view
- behavior: 偶数帧、只带脏字段、发给看得见的人（不含自己）。
- internal: 生成的脏位序列化器。
- java: done（transform / rotation / velocity 部分）— `SceneWorld.syncAttributes`；combat_state_flags 等字段随 actor-attribute-calculator 补。
- size: M
- robot: MovementScenario
- hazards: 基线移动触发的 66 不带 entity_id（Java 补上，已登记）。

### scene-info-query — 查询当前场景信息（43 → 31）
- mmorpg: player_scene_handler.cpp SceneInfoC2S / NotifySceneInfo
- client messages: 43 SceneInfoC2S (C2S)、31 NotifySceneInfo (S2C push)
- tables: none
- depends on: enter-scene-initial-sync
- behavior: 应答类型 Empty 不回包，改推 31（repeated scene_info 只放当前场景一条）；不在任何场景时什么都不发。
- internal: none
- java: done — `ClientRequestHandler`（sceneInfoC2S 分支推 31）。
- size: S
- robot: none（Unity 客户端有生成处理器）
- hazards: none

### server-push-tip-kick-redirect — 服务端主动推送：提示 / 踢线 / 重定向（23 / 34 / 124）
- mmorpg: handler/rpc/player/client_player_common_handler.cpp（三个方法体为空，只作消息号与类型契约）；player/system/player_tip.cpp（PlayerTipSystem::SendToPlayer）；player_lifecycle.cpp SendTipToPendingSession；gate 直发 34；scene_manager 经 Kafka gate-cmd 发 124
- client messages: 23 SendTipToClient (S2C push，TipInfoMessage{id, parameters})、34 KickPlayer (S2C push)、124 RedirectToGate (S2C push)
- tables: Tip（客户端文案）
- depends on: none
- behavior: 23 用于异步失败（3023 进场失败 / 换图失败、1003 换图传输失败、交接失败 3027 / 13000 等）与顶号（2017）；对加载中的会话也能推（按 session 找 gate）；34 用于交接后无法原地恢复、顶号（基线）；124 跨 zone 到达。
- internal: SendMessageToClientViaGate（按 session 高位 node_id 找 gate，按 (zone,node_id) 解析，不能当实体整数）。
- java: partial — 23 由 gate 推（进场失败 3023、顶号 2017），scene 内部 `SceneWorld.kick` 经 `PlayerKicked` 让 gate 推 23；无 34、无 124；scene 侧尚无「给任意在线玩家推带参数 tip」的通用工具（ClientSink 只发 MessageContent，可直接构造 23）。
- size: S
- robot: travel_smoke（124）、login_test AccountDisplacement（34，基线）
- hazards: 基线 SendTipToPendingSession 曾用 entt::entity{node_id}，单 gate 部署时 100% 发不出去（已修）。

### activity-list — 活动列表（190 GetActivityList）
- mmorpg: handler/rpc/player/player_activity_handler.cpp、player/system/player_activity_schedule.cpp、player_feature_snapshot.cpp（PlayerActivityReadSystem::BuildList）
- client messages: 190 GetActivityList (C2S)
- tables: ActivitySchedule、Mission
- depends on: mission 系统（mission 区域）
- behavior: 应答带 server_time_ms 与每个活动的 status（未排期 / 即将开始 / 进行中 / 已结束）、起止毫秒、can_participate + unavailable_reason、reward_id；配置错误回 1001 / kInvalidTableData；合法未排期不暴露停用的旧时间。
- internal: 只读。
- java: missing — 回 1006（Unity `Game/PlayerFeatures/PlayerFeaturesClient.cs` 在用）。
- size: S
- robot: features_smoke（基线）
- hazards: 活动开关 CheckOpen 也被任务接取复用，属于 mission / activity 区域的共享规则。

### guid-segment-alloc — 永久 GUID 号段与临时 id（item / tx / snapshot / buff / skill）
- mmorpg: nodes/scene/id_segment_bootstrap.cpp（ConfigureGuidSegmentClients）、modules/id_segment/guid_segment_registry.h；core/system/id_generator.h（buff / skill 临时 id = 节点号段 + 32 位序号）；world.cpp OnRoutingNodeIdAllocated
- client messages: none（id 出现在物品 / buff 等下行里）
- tables: none（BaseDeployConfig.id_segment 部署配置）
- depends on: none
- behavior: 每种永久 GUID 一个号段客户端，经 DataService.AllocateIdSegment 领 [lo,hi)（双 buffer、500ms→5s 退避）；首段没领到之前不放玩家进来（帧定时器也不启动），没段时铸 id 一律 fail-closed（kInvalidGuid）；临时 id 在路由 node_id 确定之后才种 node 段。
- internal: 全局库 id_segment 表 CAS；响应归属靠单飞 + FIFO。
- java: partial — Java scene 的场景号 / 实体号用本节点雪花（`SceneWorld.nextId`，节点号来自 Redis 租约），player_id 由 xm-login 雪花；无永久物品 / 交易流水 / 快照 GUID 来源（背包、交易、回档落地前需要，可直接复用雪花或做号段，二选一写进 architecture.md §9）。
- size: S
- robot: none
- hazards: 基线曾在 node_id 恒 0 时种临时 id 段（已修）；号段对节点数不敏感是选它而非 snowflake 槽位协议的理由。

### other-player-service-handlers — 其它玩家服务的客户端入口（交叉引用）
- mmorpg: handler/rpc/player/{player_bag_handler,player_currency_handler,player_mission_handler,player_pet_handler,player_rollback_handler,player_skill_handler}.cpp
- client messages: 191 GetBag / 192 SortBag；54 GetCurrencyList + GM 37 / 49 / 94 / 95；193 GetMissionList / 194 AcceptMission / 195 ClaimMissionReward；181 GetPetList / 182 ResetPetPoints / 183 SummonPet / 185 RecallPet / 186 AllocatePetPoints / 188 AutoAllocatePetPoints / 189 RenamePet / 187 GmGrantPet / 184 NotifyPetListChanged (S2C)；102–117 Rollback Gm*；77 ListSkills / 84 ReleaseSkill / 70 / 33（全部 C2S 除注明）
- tables: 各系统自有
- depends on: scene-client-dispatch, client-gm-gate, player-persistent-data-model
- behavior: 业务规则由背包 / 货币 / 任务 / 宠物 / 回档 / 技能战斗各区域清单细化，本区域只负责它们共用的分发、冻结与战斗前置、GM 闸、TLS tip 回填与持久化载体。
- internal: 宠物与角色共用属性加点纯规则（AttributePool.owner_type=1）。
- java: partial — 77 / 84 已做（ListSkills、ReleaseSkill 无结算，PARITY 已登记）；其余全部回 1006。
- size: XL（应在各自区域拆分）
- robot: pet_smoke、features_smoke、currency_crash_window、battle_smoke（基线）
- hazards: 102–117 回档 GM 在基线是空桩且曾可绕过 gate 直连打入；宠物等级跟随主人（PlayerUpgradeEvent）。

### legacy-internal-scene-rpcs — 遗留 / 内部 scene RPC（不移植）
- mmorpg: player_lifecycle_handler.cpp GateLoginNotify(50，已弃用只打 WARN)；s2s_player_scene_handler.cpp EnterScene(30) / LeaveScene(69) / EnterSceneS2C(67)；scene_handler.cpp UpdateSessionDetail(24)、RouteNodeStringMsg(78 空桩)、NodeHandshake(62)；scene_admin_handler.cpp Test(18 空)；scene_node_service.cpp ReleasePlayer(128，SM 要求节点放人 → HandleExitGameNode(kReleasedByTransfer)，交接已发起时忽略)；Agones GameServer 生命周期（main.cpp、scene_event_handler.cpp）
- client messages: none
- tables: none
- depends on: none
- behavior: 都是 muduo / gRPC / etcd / Agones 内部面，客户端不可见。
- internal: Java 用节点链路（`NodeLinkHandler` 握手、`PlayerEnter` / `PlayerLeave` / `ClientForward`）+ Dubbo + Redis 节点目录替代。
- java: not_applicable — ReleasePlayer 的语义（他人要求放人）在 Java 由归属让出（`OwnerTakeoverSubscriber`）覆盖；Agones 两版选型不同，未规划。
- size: S
- robot: none
- hazards: 基线 ReleasePlayer 对「交接已发起」的实体必须忽略，否则退出流程会抢走交接的去留裁决。

## Open questions

1. 玩家持久化载体怎么扩：`player` 表加列、按系统分表，还是加一个 proto blob 列（类似基线 PlayerAllData）？这决定 periodic-dirty-save、属性、背包、货币等所有后续功能的写回形状，且必须与 owner_epoch 围栏同事务。
2. 跨节点换图 / 跨 zone 传送在 Java 是否沿用「源节点写回并释放 → 目标节点夺权加载」（复用现有 MySQL 归属协议），而不移植 Redis handoff 标记 + 冻结 + 看门狗？若是，客户端可见的失败码（3023 / 3027 / 13000 / 34 踢线）与「最迟约 71s 出结论」需要另定口径。
3. Java 是否要引入「世界频道数 / 扩缩容 / 场景排空」：目前每个 scene 节点各建一份全部 World 地图，scene-manager 只挑人数最少者；多节点时同一 World 地图的频道数 = 节点数，与基线（SM 统一编排）不同。
4. 基线 PlayerLoginEvent 对 LOGIN_FIRST / RECONNECT / REPLACE 全是 TODO：Java 做短线重连时 enter_gs_type 是否需要下发 / 使用，还是继续只靠「写回 + 重载」？
5. 基线有意的「错误码被 TLS tip 覆盖成 0」（ReleaseSkill 1001）在属性系统里不存在（属性 handler 都走 SetTip），Java 统一如实回码即可；其它区域的 handler 需逐个核对是否属于被覆盖的那一类。
6. ~~ActorActionState 的 Combat 状态在基线放技能后只有打断能移除——是否意味着基线玩家放过一次技能后永久处于 Combat？~~ 已答（2026-10-03）：是，表里没有打断格，Combat 加上后直到实体销毁才消失（不落库，重登即清）；但没有任何读者，无可观察效果。见 PARITY「行为互斥表」行。
7. 登录 / 登出快照（SnapshotSystem SNAPSHOT_LOGIN / LOGOUT）与资产账本属于回档 / 资产区域，本清单未单列；Detour crowd（SceneCrowdSystem）与导航网格落位属于 spatial 区域。
8. 周期存盘落地时 Java 的写回顺序保证：周期写与离场最终写都在存储线程池，需按玩家串行（或带单调版本号）避免旧快照后写覆盖新快照。
