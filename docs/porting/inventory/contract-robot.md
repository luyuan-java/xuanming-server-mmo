# 功能清单：客户端契约覆盖 + robot 验收（mmorpg 26ceb70ca ↔ xuanming-server-mmo）

## 范围摘要

mmorpg 的客户端契约 = `proto/message_id.txt` 的 244 个消息号（0–243）中，所属 service 标了 `OptionIsClientProtocolService` 的那部分：
共 18 个客户端协议服务（login / scene 下 11 个玩家服务 / chat / friend / guild / team / jubaozhai / match / battle），另有 `ScenePlayerSync`
（只下行的属性同步，玩家服务但非客户端协议服务，号 55/65/66/68/75/82 仍下发给客户端）、`SceneRollbackClientPlayer`（GM，非客户端协议）、
`LoginPreGate`（客户端经 HTTP 网关间接使用）。其余号（etcd、DataService、SceneManager、Gate、SceneNodeGrpc、BattleNode、MatchInternal、
ClientRpcRouter、TradeAdmin、LoginAdmin 等）是服务端内部 RPC，Java 版按 Dubbo / 节点链路自行实现，不属于客户端契约。
Java 版 gate 用 `MessageIdRegistry` 从同步来的描述符集构建白名单：玩家服务全部转 scene（scene 的 `ClientRequestHandler` 只实现
77/84/63/43/134/132/131，其余带 `error_message` 的回 1006 kFeatureUnavailable），非玩家服务只有 `ClientPlayerLogin` 接到 login，
其余（chat / friend / guild / team / jubaozhai / match / battle）一律回 23 {kServiceUnavailable}。
Go robot（`robot/`）是验收测试：除默认 stress 模式外有 14 个独立模式 / 场景（login-test、features-smoke、data-stress、currency-crash、
battle-smoke ×2、attribute / pet / chat / guild(+economy) / trade / team / travel / friend smoke）；Java 版 `xm-robot` 只有 smoke（登录→进场→77）
与 movement（移动 / 视野 / 66 / 137）两个场景，Go robot 的 stress 路径已对 Java 版实测通过（PARITY「登录 → 进场景竖切」行）。

下文每个 `svc-*` 条目对应一个客户端可见服务（方法 = 消息号 + 方向），每个 `robot-*` 条目对应一个 Go robot 场景 / 模式。

## A. 客户端可见服务

### svc-client-player-login — 登录服务 ClientPlayerLogin
- mmorpg: `proto/login/login.proto`（service ClientPlayerLogin）；`go/login/internal/logic/clientplayerlogin/{loginlogic,createplayerlogic,entergamelogic,leavegamelogic,disconnectlogic,refreshtokenlogic,session_lifecycle}.go`；gate 直连 / 路由服两种转发（`cpp/nodes/gate/handler/rpc/client_message_processor.cpp` HandleGrpcNodeMessage / HandleRouterForward）
- client messages: 48 Login (C2S)、14 CreatePlayer (C2S)、26 EnterGame (C2S)、17 LeaveGame (C2S, 应答 LoginEmptyResponse)、58 Disconnect (C2S, 应答 LoginEmptyResponse)、127 RefreshToken (C2S)
- tables: RoleNameRule、Class/CharacterAppearance（建角默认职业 / 外观）、Tip
- depends on: client-frame-handshake、svc-login-pregate-http（gate 令牌）、scene-manager 场景分配、player 数据层
- behavior: 细则已在 Java `docs/reference/mmorpg-client-contract-login.md`。要点：auth_type 空串 = 口令；access_token / satoken / 第三方认证；登录会话 TTL；设备数上限 kTooManyDevices；同账号 Login 并发 kLoginInProgress；CreatePlayer 每账号 5 角色（2001）、名字规则；EnterGame 成功即删登录会话（之后 14/26 回 2028）、异步失败推 23{3023}；顶号 ReplaceLogin + 34；RefreshToken 空 / 无效 refresh_token 回 kLoginAccountNotFound，成功回新 access/refresh 对与两个 expire（Unity 客户端 `GameClient.cs` 注册了 127 的 OnNotify）；HomeZone 归属不符时 EnterGame 照常成功并触发 124 跨区重定向（RedirectOnEnterEnabled 开关）
- internal: Redis 登录会话 / 账号锁、player_locator、data_service、scene_manager.EnterScene、Kafka gate 命令（Kick / Redirect）
- java: partial — `xm-login/src/main/java/com/game/login/handler/{Login,CreatePlayer,EnterGame,LeaveGame,Disconnect}Handler.java` 已做；127 RefreshToken 未实现（`ClientMessageDispatcher.unsupported` 回 ClientReply.tip=1006）；`LoginAuthenticator` 只认口令（access_token / satoken 一律失败）；设备数上限、短线重连、HomeZone 重定向缺；LeaveGame 语义有意不同（PARITY 已登记）
- size: L（剩余：token 体系 + 设备上限 + 重连 ≈ M）
- robot: robot-stress-smoke、robot-login-test、robot-e2e-http；Java xm-robot smoke
- hazards: robot 在 access_token 失败后同一连接回落口令登录（login_fail 会多计一次）；Java 与基线在「上次进场在途再发 26」回码不同（2028 vs 2005，PARITY 已登记）；RefreshToken 的错误码复用 kLoginAccountNotFound，客户端无法区分「过期」与「伪造」

### svc-login-pregate-http — 分配 gate / 排队 / 区服列表（HTTP 网关 + LoginPreGate）
- mmorpg: `proto/login/login.proto`（service LoginPreGate：118 AssignGate、138 QueryQueueStatus，网关 → login 的 gRPC）；`java/gateway_node/src/main/java/com/game/gateway/controller/{AssignGate,QueueStatus,ServerList,Login,RefreshToken,Announcement,CdnSign,HotfixCheck,Admin*}Controller.java`；`go/login/internal/logic/loginpregate/{assigngatelogic,querystatuslogic}.go`
- client messages: 无 TCP 消息；HTTP `POST /api/assign-gate`、`POST /api/queue-status`、`GET /api/server-list`、`POST /api/login`、`POST /api/refresh-token`、`GET /api/announcement`、`POST /api/cdn-sign`、`POST /api/hotfix-check`；运维 `/admin/zones|whitelist|announcements`
- tables: none
- depends on: svc-client-player-login（/api/login、/api/refresh-token 走 login）、gate 节点目录
- behavior: 恒 HTTP 200 + body.code：0 准入 / 100 排队（queue_source=ratelimit|login，retry_after_ms）/ 410 排队令牌过期 / 429 限流 / 404 zone_not_found / 503 维护·关闭·未开 / 500；`[]byte` 字段标准 Base64；JSON snake_case；zone_id=0 由客户端先拉 server-list 自动选区；白名单区服、公告
- internal: login 侧排队（Redis 计数 + 令牌）、网关令牌桶限流、zone 状态存储
- java: partial — `xm-gateway/.../assign/AssignGateController.java`（只 code=0 / 404 / 500 路径）与 `serverlist/ServerListController.java`；缺 queue-status、/api/login、/api/refresh-token、announcement、cdn-sign、hotfix-check、admin 接口、排队与限流（PARITY「分配 gate」行）
- size: M
- robot: robot-stress-smoke（assign-gate，排队分支）、robot-e2e-http（/api/login + /api/refresh-token）；Java xm-robot smoke（assign-gate code=0）
- hazards: robot 外层 30 次退避重试，assign-gate 失败不会立刻暴露；`gate.go` 里的 resolveGateAddr 是死代码；Java 对 zone_id=0 回 404 而基线自动选区（客户端先拉 server-list 时不受影响）

### svc-scene-client-player-common — 通用下行：tip / 踢线 / 跨区重定向
- mmorpg: `proto/scene/client_player_common.proto`；发送点 `cpp/nodes/gate/handler/rpc/client_message_processor.cpp` SendTipToClient、`cpp/nodes/gate/handler/event/gate_event_handler.cpp`（KickPlayer / RedirectToGateEventHandler）、`cpp/nodes/gate/handler/event/scene_entry_dispatch.cpp`、`cpp/libs/services/scene/player/system/player_lifecycle.cpp` SendTipAndKickToClient；robot `logic/handler/scene_client_player_common_{send_tip_to_client,kick_player,redirect_to_gate}.go`
- client messages: 23 SendTipToClient (S2C push, TipInfoMessage)、34 KickPlayer (S2C push, GameKickPlayerRequest{reason, operator})、124 RedirectToGate (S2C push, RedirectToGateNotify{target_ip, target_port, token_payload, token_signature, token_deadline})
- tables: Tip（tip 码）
- depends on: client-frame-handshake、svc-client-player-login、跨区 / 合服 HomeZone
- behavior: 23 是传输层 / 异步失败通道（1003 内部错、1006 功能未开放、1008 超频、kServiceUnavailable、3023 进场失败、2017 被顶号）；34 先于关写端发送，reason.id = 被顶号 kLoginBeKickByAnOtherAccount 或进场失败 3023（顺序 tip → 34 → shutdown）；124 收到后客户端断开当前 gate、连 target、首包用 notify 里的 payload/signature 握手、**必须重跑 Login + EnterGame**（票据只认证连接，无跨区会话转移）
- internal: Kafka gate 命令（KickPlayer、RedirectToGate 事件）、scene→gate 下行
- java: partial — 23 已做（`xm-gate/.../session/ClientDispatcher.java` sendTip；顶号 23{2017}、进场失败 23{3023}）；34 有意不发（PARITY「顶号」行、robot 契约第 13 条）；124 缺（无跨区 / HomeZone）
- size: S（34 补发）+ M（124 随跨区一起做）
- robot: robot-stress-smoke（被踢 / 重定向的 handler）、robot-travel-smoke（124 跟随）；Java xm-robot 不覆盖
- hazards: 34 与 23 走同一连接时客户端可能少显示一条 tip（scene→玩家推送不保证顺序）；124 的「换连接之后失败」会话即死，客户端只能外层重连；Unity 客户端 `GameClient.cs` 已注册 23 / 34 / 124 的 OnNotify，Java 不发 34 时客户端只靠断线感知被踢

### svc-scene-scene-client-player — 场景进出与视野通知 SceneSceneClientPlayer
- mmorpg: `proto/scene/player_scene.proto`；`cpp/nodes/scene/handler/rpc/player/player_scene_handler.cpp`；`cpp/libs/services/scene/spatial/system/{aoi,grid,view,interest}.cpp`；TravelToZone → `cpp/libs/services/scene/player/system/player_lifecycle.cpp` RequestZoneTravel
- client messages: 63 EnterScene (C2S)、79 NotifyEnterScene (S2C push)、43 SceneInfoC2S (C2S, Empty 应答，改推 31)、31 NotifySceneInfo (S2C push)、21 NotifyActorCreate (S2C push)、51 NotifyActorDestroy (S2C push)、47 NotifyActorListCreate (S2C push)、64 NotifyActorListDestroy (S2C push)、226 TravelToZone (C2S)
- tables: World / Scene（地图配置）、MainScene / Dungeon（镜像 / 副本）
- depends on: svc-client-player-login（进场）、scene-manager、svc-scene-client-player-common（124 跨区落地）
- behavior: 进场顺序 79 → 21(自己) → 47；63 校验顺序 3001 参数错 / 3004 / 3014 / 3023 / 已在当前场景；镜像场景（mirror_config_id）；跨节点换场景；226 无错应答 = 已受理（到达 = 随后收 124；未成 = 随后收 23{kZoneTravel*}，含 kZoneTravelTargetBusy / TargetZoneNotFound / InBattle / InTeam）；视野半径 10 m
- internal: scene_manager 选场景、跨节点玩家迁移（SceneNodeGrpc / Kafka 场景命令）、跨 zone 交接（冻结→存盘→handoff→RedirectToGate）
- java: partial — `xm-scene/src/main/java/com/game/scene/world/ClientRequestHandler.java`（63 节点内换图、43→31）、`SceneWorld` / `ViewIndex`（21/47/64/51，行为有意不同见 PARITY「视野」行）；缺跨节点换场景、镜像 / 副本（回 3023）、226 TravelToZone（回 1006）
- size: L（跨节点换场景 M + 跨 zone 传送 L 另立）
- robot: robot-stress-smoke（AI 15% 发 63）、robot-travel-smoke（226）；Java xm-robot smoke（79）、movement（21/47/51）
- hazards: TravelToZone 必须追加在 service 末尾（C++ CallMethod 按 method index 分发）；79 可能早于 26 应答到达；基线 21/47 单向通知（旁人看不到新来者）已在 Java 修正

### svc-scene-skill-client-player — 技能 SceneSkillClientPlayer
- mmorpg: `proto/scene/player_skill.proto`；`cpp/nodes/scene/handler/rpc/player/player_skill_handler.cpp`；`cpp/libs/services/scene/combat/skill/system/skill.cpp`、`combat/buff/system/*.cpp`
- client messages: 84 ReleaseSkill (C2S)、70 NotifySkillUsed (S2C push)、33 NotifySkillInterrupted (S2C push)、77 ListSkills (C2S)
- tables: Skill、Buff、Class（初始技能）、SkillPermission
- depends on: svc-scene-scene-client-player（视野广播）、actor 属性 / 战斗状态、svc-scene-player-sync
- behavior: 84 未知技能 / 未拥有 → 1001 kInvalidTableId；校验冷却 kSkillCooldownNotReady、状态 kSkillCannotBeCastInCurrentState、目标 kSkillInvalidTarget(Id)、前置 kSkillPrerequisites、buff 限制；施法点定时器（普通 / 引导技能）后结算伤害（暴击 ×2）、加 buff、扣资源 / 物品、起冷却；被打断广播 33；70 广播给可见玩家（基线不含施法者本人）；77 的 skill_list 必须存在（空列表也要有，robot 靠它发就绪信号）
- internal: 伤害事件、buff 系统、冷却组件、跨 zone 冻结目标丢弃伤害
- java: partial（2026-10-03，批次 2.6）— 77 `ClientRequestHandler.listSkills`（场景核心）；84 `com.game.scene.skill.{SkillTables,SkillRules,SkillService,SkillFeature}`：真实 1001、目标 7001、冷却 7003、施法阶段 7000 / 打断推 33（冷却与后摇按设计意图生效）、状态表；70 / 33 含施法者本人（PARITY 登记 mmorpg 待改）。缺：消耗、命中 / 伤害、buff、7004 / 7002（随 6.3）。robot `skill` 场景覆盖
- size: XL（战斗结算 + buff 应拆成「技能校验与冷却」M、「伤害结算」M、「buff 系统」L）
- robot: robot-stress-smoke（AI 85% 发 84）、robot-features-smoke；Java xm-robot smoke（77 非空）
- hazards: 基线写的 1001 会被 TRANSFER_ERROR_MESSAGE 覆盖成空 tip（Java 如实回码）；施法者收不到自己的 70 时 Unity 客户端看不到自己施法表现（`GameClient.cs` 只由 70 驱动 SkillFx）

### svc-scene-movement-client-player — 移动 SceneMovementClientPlayer
- mmorpg: `proto/scene/player_movement.proto`；`cpp/nodes/scene/handler/rpc/player/player_movement_handler.cpp`；`cpp/libs/services/scene/spatial/system/movement.cpp`、`player/system/afk.cpp`
- client messages: 134 MoveStart (C2S, Empty)、132 MoveSync (C2S, Empty)、131 MoveStop (C2S, Empty)、136 TeleportRequest (C2S)、137 NotifyMoveAck (S2C push)、133 NotifyActorMove (S2C push)、135 NotifyActorMoveList (S2C push)、130 NotifyTeleport (S2C push)
- tables: 无（导航网格数据可选）
- depends on: svc-scene-scene-client-player（视野）、svc-scene-player-sync（66 是移动广播唯一通道）
- behavior: 上行永不回包；137 纠偏（导航射线夹持）；20 FPS 外推；600 帧挂机停推；133 / 135 / 130 基线从不发送；136 基线是空桩（回 id=0 空 tip）
- internal: 场景帧循环、导航网格
- java: done — `xm-scene` `SceneWorld.applyMove`、`MoveGuard`、`MovementRules`（规格 `docs/reference/mmorpg-client-contract-movement.md`；有意差异全部登记在 PARITY「移动」「移动位移校验」「136」行）；136 回 1006
- size: M
- robot: robot-stress-smoke（AI move 动作）；Java xm-robot movement（Java 独有场景，Go robot 无对应断言）
- hazards: 客户端依赖约 250 ms 一条 MoveSync；Java 的令牌桶（12 m/s、封顶 24 m）比基线严，客户端长时间不上报会被截断回 137

### svc-scene-player-sync — 属性同步下行 ScenePlayerSync
- mmorpg: `proto/scene/player_state_attribute_sync.proto`（玩家服务，**未**标客户端协议，号仍下发）；`cpp/libs/services/scene/actor/attribute/system/actor_state_attribute_sync.cpp`；handler `cpp/nodes/scene/handler/rpc/player/player_state_attribute_sync_handler.cpp`（全空桩）
- client messages: 66 SyncBaseAttribute (S2C push, ActorBaseAttributesS2C)、65 SyncAttribute2Frames、55 SyncAttribute5Frames、82 SyncAttribute10Frames、68 SyncAttribute30Frames、75 SyncAttribute60Frames（均 S2C push）
- tables: 无
- depends on: svc-scene-scene-client-player（可见集合）、svc-scene-movement-client-player
- behavior: 66 偶数帧、只带脏字段、发给看得见它的人（不含自己）；距离分三档（level1/2/3 半径系数）按不同频率发 65/55/82/68/75——但这些 AttributeDelta*S2C 目前只有 entity_id 字段（「Additional attributes can be added here」），实际线上不出现有效载荷
- internal: 场景帧、脏标记
- java: done（66）/ not_applicable（65/55/82/68/75 基线也无实际内容）— `xm-scene` 属性同步（PARITY「属性同步 66」行，有意差异：每条带 entity_id、新观察者补朝向 / 速度）
- size: S
- robot: robot-stress-smoke 被动接收；robot-attribute-smoke（间接）；Java xm-robot movement 断言 66
- hazards: 客户端发来 55/65/66 等号（玩家服务但非客户端协议）gate 白名单就拦掉；将来给 AttributeDelta 加字段时两版要同步加频率分档

### svc-scene-currency-client-player — 货币 SceneCurrencyClientPlayer
- mmorpg: `proto/scene/player_currency.proto`；`cpp/nodes/scene/handler/rpc/player/player_currency_handler.cpp`；`cpp/libs/modules/currency/system/currency_system.cpp`（632 行）；GM 闸 `cpp/nodes/gate/gate_gm_client_messages.h` + `cpp/nodes/scene/handler/rpc/player/player_gm_guard.h`
- client messages: 54 GetCurrencyList (C2S)、37 GmAddCurrency (C2S, GM)、49 GmDeductCurrency (C2S, GM)、94 GmBlockCurrency (C2S, GM)、95 GmUnblockCurrency (C2S, GM)
- tables: Currency（类型 / 上限）
- depends on: player 数据层（CurrencyComp 持久化）、交易流水（transaction_log）、跨 zone 冻结
- behavior: 54 回 CurrencyComp{values[], blocked_types[], debts[]}；GM 四条在 prod（GATE_RUN_MODE 未设）由 gate 回 23{1006} 并计非法包，scene 另有 SCENE_RUN_MODE 二道闸；加币被封禁类型 kAssetBlocked、超上限 kCurrencyMax、扣币不足 kAssetCurrencyInsufficient、冻结 kAssetFrozen；有欠款（debts）时加币自动先还债；流水记 TX_GM_GRANT / TX_GM_DEDUCT
- internal: 交易流水 / 异常检测、owner_epoch 写回、补缴债务
- java: missing — scene `ClientRequestHandler.replyUnavailable` 对 54/37/49/94/95 一律回 1006；无货币组件与持久化列
- size: M
- robot: robot-currency-crash（54 / 37）、robot-features-smoke（54）、robot-guild-smoke（economy 段用 GM 加币）
- hazards: GM 号挂在客户端协议服务上是历史口子，Java 实现时必须同样有运行模式闸（gate + scene 两道）；GmBlock 在跨 zone 冻结期间回 kInvalidParameter

### svc-scene-rollback-client-player — GM 回档 / 审计 SceneRollbackClientPlayer
- mmorpg: `proto/scene/player_rollback.proto`（只标 OptionIsPlayerService，**不是**客户端协议）；`cpp/nodes/scene/handler/rpc/player/player_rollback_handler.cpp`（十二条 GM 桩，只有 GM 闸与冻结检查，业务 TODO）；DataService 侧 96–101 / 105 / 108 / 114
- client messages: 102 GmAttachDebt、106 GmWaiveDebt、113 GmAdjustDebt、109 GmFreezeDebt、107 GmQueryDebt、103 GmCreateSnapshot、115 GmListSnapshots、116 GmPreviewRollback、112 GmExecuteRollback、117 GmQueryTransactionLog、104 GmTraceItem、110 GmClawbackItem（gate 白名单不放行，客户端不可达）
- tables: 无
- depends on: data_service 快照 / 回档、交易流水
- behavior: 客户端不可见；节点路由（SceneHandler::InvokePlayerService）可达，handler 首句都是 GM 闸
- internal: GM 工具 → scene 节点路由 → DataService
- java: not_applicable — Java gate 白名单只收 `clientService()` 方法（`MessageRoutes.of`），这些号到不了 scene；GM / 回档体系 Java 尚无（architecture.md §10「GM / 管理接口」首批不做）
- size: L（若做 GM 回档；与 data_service 快照一起另立）
- robot: none
- hazards: mmorpg 侧全是空桩，填实之前别移植；scene 分发入口不看 OptionIsClientProtocolService，Java scene 的 `ClientRequestHandler` 要求 playerService && clientService，已更严

### svc-scene-attribute-client-player — 属性加点 / 方案 SceneAttributeClientPlayer
- mmorpg: `proto/scene/player_attribute.proto`；`cpp/nodes/scene/handler/rpc/player/player_attribute_handler.cpp`；`cpp/libs/services/scene/player/system/player_attribute.cpp`（836 行）、`attribute_allocation_rules.h`、`player_level_rules.h`；设计 `docs/design/player-attribute-allocation.md`
- client messages: 167 GetAttributePanel (C2S)、168 AllocateAttributePoints (C2S)、172 ResetAttributePoints (C2S)、173 AutoAllocateAttributePoints (C2S)、174 CreateAttributeScheme (C2S)、171 SwitchAttributeScheme (C2S)、169 RenameAttributeScheme (C2S)、170 NotifyAttributePanelChanged (S2C push)、175 GmSetPlayerLevel (C2S, GM)
- tables: AttributePool、AttributeDimension、AttributeAllocRatio、AttributeAutoPlan、AttributeRule
- depends on: svc-scene-currency-client-player（重置 / 开方案扣金币）、player 等级、svc-scene-player-sync（派生属性变化）
- behavior: 面板 = 多池（等级换算总点、解锁等级、维度上限）× 维度 × 方案；分配是「目标已分配点」map（缺省维度不变）；错误 kAttributePoolNotFound / kAttributePoolLocked / kAttributeNothingToChange / kAttributeSchemeNotFound / kAttributeSchemeNameInvalid / kAttributeGoldNotEnough / 点数不足；方案切换冷却 switch_cooldown_until（Unix 秒）；变化推 170；175 走 GM 闸（prod 回 1006）
- internal: 玩家持久化（方案 / 已分配点）、派生属性重算
- java: missing — scene 对 167–175 回 1006（`ClientRequestHandler.replyUnavailable`）
- size: L
- robot: robot-attribute-smoke、robot-features-smoke
- hazards: 面板 value 只是点数不能反推收益；GmSetPlayerLevel 是客户端协议上的 GM 口子

### svc-scene-pet-client-player — 宝宝 ScenePetClientPlayer
- mmorpg: `proto/scene/player_pet.proto`；`cpp/nodes/scene/handler/rpc/player/player_pet_handler.cpp`；`cpp/libs/services/scene/player/system/player_pet.cpp`（950 行）、`pet_rules.h`；设计 `docs/design/player-pet.md`
- client messages: 181 GetPetList (C2S)、183 SummonPet (C2S)、185 RecallPet (C2S)、186 AllocatePetPoints (C2S)、182 ResetPetPoints (C2S)、188 AutoAllocatePetPoints (C2S)、189 RenamePet (C2S)、184 NotifyPetListChanged (S2C push)、187 GmGrantPet (C2S, GM)
- tables: Pet、PetRule（max_pets、改名 / 洗点金币）、属性池相关表
- depends on: svc-scene-attribute-client-player（属性点池机制复用，pool owner = pet）、svc-scene-currency-client-player
- behavior: PetListInfo{pets[], active_pet_id, max_pets, rename_cost_gold}；错误 kPetNotFound / kPetSlotFull / kPetOwnerLevelNotEnough / kPetGoldNotEnough / kPetAlreadyActive / kPetPointsNotEnough / kPetNothingToChange / kPetTableRowMissing；同时只一只出战；变化推 184；187 走 GM 闸
- internal: 宝宝实例持久化、宝宝属性派生、战斗中宝宝参战（player_battle.cpp 读宝宝）
- java: done（2026-10-03，批次 2.8）— `com.game.scene.pet.{PetTables,PetRules,PetService,PetFeature}`，见 PARITY「宝宝」行
- size: L
- robot: robot-pet-smoke、robot-features-smoke
- hazards: 成长率万分比由服务器算；宝宝 id 是雪花 uint64

### svc-scene-activity-client-player — 活动列表 SceneActivityClientPlayer
- mmorpg: `proto/scene/player_activity.proto`；`cpp/nodes/scene/handler/rpc/player/player_activity_handler.cpp`；`cpp/libs/services/scene/player/system/{player_activity_schedule,player_feature_snapshot}.cpp`（PlayerActivityReadSystem::BuildList）
- client messages: 190 GetActivityList (C2S)
- tables: ActivitySchedule、Mission（活动绑定任务）
- depends on: svc-scene-mission-client-player（can_participate 用任务接取检查）
- behavior: 每项 status UNSCHEDULED/UPCOMING/OPEN/ENDED、starts/ends_at_ms、can_participate（OPEN 且可接任务）、unavailable_reason 中文原因（已完成 / 同类型任务未完成）；回 server_time_ms
- internal: 只读，按服务器 UTC 毫秒计算日程
- java: missing — scene 回 1006；`xm-table` 已能生成 ActivitySchedule 访问代码
- size: S
- robot: robot-features-smoke
- hazards: unavailable_reason 是服务器下发的中文串（客户端直接显示），两版文案需一致

### svc-scene-bag-client-player — 背包 SceneBagClientPlayer
- mmorpg: `proto/scene/player_bag.proto`；`cpp/nodes/scene/handler/rpc/player/player_bag_handler.cpp`；`cpp/libs/services/scene/player/system/{player_feature_snapshot,bag_marshal}.cpp`（PlayerBagSystem）；`cpp/libs/modules/bag/**`（约 2900 行，BagService::SortByPlayerRequest、格子布局）
- client messages: 191 GetBag (C2S)、192 SortBag (C2S)
- tables: Item（max_stack、equip_kind、展示字段）
- depends on: 物品 / 背包模块、svc-scene-currency-client-player（BagInfo 附带 CurrencyComp）
- behavior: bag_type 0 人物背包 / 1 仓库 / 2 装备栏 / 3 临时格；items 与 layout(slots{slot,item_id,width,height}) 分离、顺序无语义；只有 0/1 可整理（其余 kInvalidParameter）；布局不一致 kInvalidTableData；背包组件缺失 kServiceUnavailable；失败时清空 bag 字段只回 error_message；SortBag 回 changed
- internal: 物品实例持久化（bag_marshal）、物品流水、多格物品布局
- java: missing — scene 回 1006；Java 无物品 / 背包模型
- size: L（背包模块本体 L；本服务两条只读 / 整理 S）
- robot: robot-features-smoke
- hazards: 物品表尚无展示字段，name/description 可能为空；item_id 雪花 uint64

### svc-scene-mission-client-player — 任务 SceneMissionClientPlayer
- mmorpg: `proto/scene/player_mission.proto`；`cpp/nodes/scene/handler/rpc/player/player_mission_handler.cpp`；`cpp/libs/services/scene/player/system/{player_mission,mission_marshal,player_feature_snapshot}.cpp`；`cpp/libs/modules/mission/**`、`modules/condition`、`modules/reward`
- client messages: 193 GetMissionList (C2S)、194 AcceptMission (C2S, MissionActionRequest{scope, mission_id}, 应答 GetMissionListResponse)、195 ClaimMissionReward (C2S, 同上)
- tables: Mission、Condition、Reward
- depends on: 条件 / 事件系统（进度推进）、奖励发放（货币 / 物品）、svc-scene-activity-client-player
- behavior: 状态 NOT_ACCEPTED/ACTIVE/COMPLETED/CLAIMABLE/FAILED；目标进度 objectives[]；can_accept / can_claim / unavailable_reason（「请先完成同类型任务」「任务所需玩法暂未开放」等中文串）；错误 kMissionIdRepeated / kMissionAlreadyCompleted / kMissionTypeAlreadyExists；Accept / Claim 成功后回完整列表；state_persistent 标记随完整玩家快照保存
- internal: 任务组件持久化、事件驱动进度、奖励入账走资产流水
- java: missing — scene 回 1006
- size: L
- robot: robot-features-smoke
- hazards: 两个动作复用 GetMissionListResponse；configured=false 区分存量任务缺表

### svc-client-player-chat — 聊天 ClientPlayerChat
- mmorpg: `proto/chat/chat.proto`；`go/chat/internal/logic/chat_logic.go`（397 行）、`go/chat/internal/constants/constants.go`、`go/chat/internal/session/session.go`
- client messages: 61 SendChat (C2S)、28 PullChatHistory (C2S)（无下行推送：客户端靠拉取）
- tables: 无（错误码用 CommonError）
- depends on: gate → client_rpc_router / 直连转发、会话身份（x-session-detail-bin）
- behavior: v1 只开 WORLD 与 PRIVATE，TEAM / SYSTEM / UNSPECIFIED 回 kFeatureUnavailable；发言人与时间戳由服务端覆盖；WORLD 清零 target；内容按字节限长、trim 后空 → kMessageSizeExceeded；每秒限速 kRateLimitExceeded；request_id 幂等（pending 期重发回 kRateLimitExceeded，done 后回成功）；无会话 kInvalidParameter；存储故障 kServiceUnavailable；历史 7 天 / 200 条尽力而为，limit 夹在默认 / 上限之间
- internal: ChatRedis（world log LIST、私聊 log、幂等键、限速计数），错误一律 in-band
- java: missing — gate `MessageRoutes.SERVICE_BACKENDS` 无 ClientPlayerChat → 23{kServiceUnavailable}
- size: M
- robot: robot-chat-smoke、robot-stress-smoke（AI chat 动作）
- hazards: 私聊 key 按 (小 id, 大 id) 排序共享；Redis 重启历史清空（契约允许）；幂等键 pending 卡住时同 request_id 重发要等 TTL

### svc-client-player-friend — 好友 ClientPlayerFriend
- mmorpg: `proto/friend/friend.proto`、`proto/friend/friend_table.proto`；`go/friend/internal/logic/{friend_logic,push,recommend,rate_quota,sweep,online_directory}.go`（约 1400 行非测试）、`go/friend/internal/data/*`、`go/friend/internal/kafka/gate_command_builder.go`；设计 `docs/design/friend-port-20260918.md`
- client messages: 234 AddFriend、238 AcceptFriend、232 RejectFriend、11 RemoveFriend、12 GetFriendList、230 GetPendingRequests、7 Block、236 Unblock、2 ListBlocks、119 RecommendFriends（均 C2S）、235 NotifyFriendEvent (S2C push, FriendEventS2C{reason, by_player_id, ts_ms})
- tables: FriendError tip 段（kFriendCannotAddSelf / AlreadyFriends / ListFull / RequestAlreadySent / TargetListFull / NoPendingRequest / TooManyPending / Blocked / BlockListFull / TargetInboxFull）
- depends on: 在线目录（好友在线状态）、玩家名批量解析（DataService.BatchGetPlayerName）、Kafka gate 命令下行
- behavior: 推送只在 AddFriend 成功（REQUEST_RECEIVED 给对方）与 AcceptFriend 成功（REQUEST_ACCEPTED 给申请人）时发，at-most-once、事件体不带列表（客户端收到后须重拉 230 / 12），不推给操作者本人，Reject / Remove / Block / Unblock 不推；拉黑后对方加好友 kFriendBlocked；推荐策略 mutual → random，limit 0 = 默认、超上限钳制，exclude 条数越界 kInvalidParameter；每玩家写操作配额 kRateLimitExceeded；存储故障 kServiceUnavailable；过期申请定时清扫
- internal: MySQL 权威（好友 / 申请 / 黑名单表，容量守卫锁顺序）+ 缓存；推送在事务提交后经 Kafka gate-cmd
- java: missing — gate 无 ClientPlayerFriend 路由 → 23{kServiceUnavailable}
- size: L
- robot: robot-friend-smoke
- hazards: 服务名必须带 ClientPlayer 前缀生成器才出推送 handler（曾因此改名）；friend.proto 刻意没写 OptionFileDefaultNode（节点类型由生成器回落派生）；好友码曾与 common 段撞号（constants_test 防回归）

### svc-guild-service-membership — 帮会成员与申请 GuildService（核心段）
- mmorpg: `proto/guild/guild.proto`、`guild_db.proto`；`go/guild/internal/logic/{guild_logic,guild_manage_logic,push,home_zone,merge_fence,online_status_resolver,player_name_resolver}.go`；准入白名单 `go/guild/internal/session/session.go` ClientMethods；设计 `docs/design/guild-zone-client-access.md`
- client messages: 15 CreateGuild、60 GetGuild、35 GetPlayerGuild、29 LeaveGuild、38 DisbandGuild、39 SetAnnouncement、19 SetGuildMemberRole、217 KickGuildMember、216 TransferGuildLeader、218 ApplyJoinGuild、219 CancelGuildApplication、222 ListMyGuildApplications、221 ListGuildApplications、223 ReviewGuildApplication（均 C2S）、220 NotifyGuildChanged (S2C push, GuildChangedS2C{kind, guild_id, actor_player_id, target_player_id})
- tables: GuildRule、GuildLevel
- depends on: 帮会 id 发号、HomeZone（帮会按归属 zone 隔离）、玩家名 / 在线状态解析、Kafka 推送
- behavior: 请求不带 player_id / guild_id，操作者与所在帮会一律取 gate 会话；错误 kGuildNameTaken / NameInvalid / AlreadyInGuild / NotInGuild / NotFound / NoPermission / NotLeader / LeaderCantLeave / Full / OfficerLimit / CannotTargetSelf / TargetNotMember / AnnouncementTooLong / ApplicationLimit / ApplicationQueueFull / ApplicationNotFound / ZoneMerging / HomeZoneUnknown / BusyRetry / IdGenUnavailable；推送 kind 1–8、11（入帮 / 退 / 踢 / 职位 / 转让 / 解散 / 收到申请 / 被拒 / 公告）
- internal: MySQL 帮会库 + Redis 缓存；合服围栏（merge_fence）；Notify* 不在 ClientMethods（客户端发推送号被拒）
- java: missing — gate 无 GuildService 路由 → 23{kServiceUnavailable}
- size: L
- robot: robot-guild-smoke
- hazards: GuildService 无 ClientPlayer 前缀却是客户端协议服务；UpdateGuildScore(8) 标了客户端协议但服务端白名单 fail-closed 拒绝——Java 白名单要同样排除；guild.proto 也没写 OptionFileDefaultNode

### svc-guild-service-economy-rank — 帮会排行与经济（捐献 / 升级 / 商店）
- mmorpg: `go/guild/internal/logic/{economy_logic,economy_config}.go`（约 1570 行）、`guild_logic.go` GetGuildRank / GetGuildRankByGuild / UpdateGuildScore、`go/guild/internal/svc/asset_op.go`；scene 侧 `SceneNodeGrpc.AssetDebit/AssetCredit/AssetAbortDebit`（224/225/227）
- client messages: 27 GetGuildRank、52 GetGuildRankByGuild、120 GetGuildDonateOptions、53 DonateToGuild、76 UpgradeGuild、228 GetGuildShop、233 BuyGuildShopGoods（均 C2S）；8 UpdateGuildScore（客户端协议号但服务端拒绝）；变化走 220（kind 9 FUNDS_CHANGED / 10 LEVEL_UP / 13 DELIVERY_DONE）
- tables: GuildDonate、GuildLevel、GuildShop、GuildRule
- depends on: svc-guild-service-membership、svc-scene-currency-client-player（扣玩家货币）、跨服务资产操作协议（scene AssetDebit / Credit 两阶段 + 账本）
- behavior: 排行只看本 HomeZone、分页；捐献 / 购买返回资产订单 status（结算中 / 待发放**不进** error_message，避免客户端把正常单当失败）；错误 kGuildDonateLimit / CurrencyInsufficient / FundsInsufficient / MaxLevel / ContributionInsufficient / ShopLimit / ShopLevelTooLow / ShopGoodsNotFound / RankTooLow / NotRanked / AssetRejected / AssetPending
- internal: 资产操作 id 发号、scene 侧幂等账本（asset_op_ledger）、异步完成回调 OnAssetFinalized、合服经济围栏
- java: missing
- size: L
- robot: robot-guild-smoke（guild_economy_smoke.go 段）
- hazards: 资产两阶段（debit → credit / abort）跨 guild 与 scene，Java 必须先有 scene 资产账本；GM 加币在 prod 被闸，robot 经济段依赖 dev 模式

### svc-guild-service-activity — 帮会活动（灯会 / 团圆 / 历练）
- mmorpg: `go/guild/internal/logic/{activity_logic,activity_metrics}.go`（约 970 行）、`go/guild/internal/activity/{config,rules}.go`（约 820 行）；历练依赖 `proto/match/match_internal.proto`（MatchInternal，B6b）
- client messages: 241 GetGuildActivities、239 LightGuildLantern、240 ClaimGuildReunion、242 StartGuildTrial、243 RespondGuildTrialInvite（均 C2S）；变化走 220（kind 12 ACTIVITY_CHANGED）
- tables: GuildActivity（schema 已有、mmorpg 导表器尚未产出数据，PARITY「GuildActivity 配置表」行）、GuildRule
- depends on: svc-guild-service-membership、svc-guild-service-economy-rank（奖励走资产操作）、在线状态（团圆按在线人数 / 时长）、svc-match-service（历练）
- behavior: 前置阻塞优先级：未开放 > 帮会等级 > 入帮时长 > 今日次数（kGuildActivityNotOpen / LevelTooLow / JoinTooRecent / AlreadyClaimed / ThresholdNotReached）；只接受该类型当前选中且 Open 的行，伪造其他档期 id 一律按未开放；历练两条现为 B6a 桩：公共前置照走，然后恒回未开放，视图强制 DISABLED（kGuildTrial* 码已占号）
- internal: 活动进度存储、奖励资产订单、每日次数（按服务器时区）
- java: missing — gate 回 23{kServiceUnavailable}（PARITY 基线行明确 239–243 Java 回「服务不可用」）
- size: M（历练去桩后另计 M）
- robot: robot-guild-smoke（活动段，视配置）
- hazards: mmorpg 侧仍在开发（B6b 去桩、配表未出数据），Java 应等契约稳定再做

### svc-client-player-team — 组队 ClientPlayerTeam
- mmorpg: `proto/team/team.proto`；`go/match/internal/team/{service,rules,store,notify,view,presence,homezone,scripts}.go`（约 4000 行非测试，挂在 match 进程）；scene 侧 `cpp/libs/services/scene/player/system/{player_team,player_team_aoi}.cpp`；设计 `docs/design/team-system.md`
- client messages: 214 CreateTeam、207 GetMyTeam、206 ApplyJoinTeam、208 HandleApplication、201 InviteToTeam、204 RespondInvite、205 ListMyInvites、210 LeaveTeam、202 KickMember、212 TransferLeader、209 DisbandTeam、211 StartTeamMatch（均 C2S，多数应答 TeamResponse）；213 NotifyTeamSnapshot、215 NotifyTeamInvite、203 NotifyTeamEvent（S2C push）
- tables: TeamError tip 段（base 4000）、副本 / 匹配配置
- depends on: HomeZone、在线 presence、svc-match-service（StartTeamMatch 组队排队 / 开战）、scene 队伍 AOI（队友可见）
- behavior: 错误 kTeamMembersFull / MemberInTeam / MemberNotInTeam / KickSelf / KickNotLeader / AppointSelf / NotInApplicantList / HasNotTeamId / DismissNotLeader / PlayerNotFound / NotLeader / CrossZoneDenied / InviteNotFound / InviteLimit / InMatch / MemberOffline / MemberInBattle / MemberNotReady（parameters[0]=player_id）/ DungeonNotOpen / SizeExceeded / StateChanged / Internal；推送至多一次、不在线不推，客户端靠 207 / 205 自愈；调用者本人不收快照（回包已含同次提交视图）；203 只给没有视图可推的人（申请被拒、邀请失效）弹 toast；视图带 epoch
- internal: SharedRedis 权威（Lua 脚本 CAS、版本号、TIME 口径毫秒）、scene 刷新信号（去重读 SharedRedis）、Kafka 推送
- java: missing — gate 回 23{kServiceUnavailable}
- size: XL（建议拆：队伍 CRUD + 邀请 / 申请 L、开战匹配 M、scene 队伍 AOI S）
- robot: robot-team-smoke
- hazards: 队伍时间统一取 Redis TIME（不用实例墙钟）；跨区 TravelToZone 对在队玩家回 kZoneTravelInTeam

### svc-client-player-jubaozhai — 聚宝斋（寄售市场）只读面 ClientPlayerJubaozhai
- mmorpg: `proto/trade/jubaozhai.proto`、`trade_table.proto`；`go/trade/internal/logic/{jubaozhai_logic,phase,home_zone}.go`、`go/trade/internal/data/listing_repo.go`；内部 `TradeAdmin.SeedListing`(199，非 dev/test 回 PermissionDenied)；设计 `docs/design/jubaozhai-market.md`
- client messages: 196 BrowseListings、197 GetListingDetail、198 SetFavorite、200 GetMyShelf（均 C2S）
- tables: TradeError tip 段（kTradeListingNotFound / FavoriteLimitReached / FeatureDisabled / HomeZoneUnknown）
- depends on: HomeZone（scope=ZONE 时按归属区过滤）、listing id 段发号（DataService.AllocateIdSegment）
- behavior: 阶段由 now 推导不落库：LISTED 且 now<notice_end → PUBLIC_NOTICE；now<sale_end → ON_SALE；LOCKED → LOCKED；其余 ENDED；非卖家可见 = status∈{LISTED,LOCKED} 且未过寄售期且（GLOBAL 或同 home_zone），home_zone 查不到一律不可见（fail-closed）；响应带 server_now_ms；身份只取会话；P1 不移动任何资产
- internal: MySQL listing / favorite 表、资产通道与对账管线（P3 用，assetchannel / reconcile）
- java: missing — gate 回 23{kServiceUnavailable}
- size: M（只读 P1；下单 / 寄售 P3 另计 L）
- robot: robot-trade-smoke
- hazards: GM 货币口子与人民币寄售叠加即印钞（gate GM 闸的由来）；LIKE 搜索需转义

### svc-match-service-queue — 匹配排队与切磋 MatchService（队列段）
- mmorpg: `proto/match/match_service.proto`、`match_internal.proto`；`go/match/internal/logic/{joinqueuelogic,cancelqueuelogic,getqueuestatuslogic,challengelogic,queue,matcher,gather,rating,team_battle,activitybattlelogic,push,location,keys}.go`（约 4500 行非测试）；设计 `docs/design/turn-based-battle-server.md`
- client messages: 157 JoinQueue (C2S)、148 CancelQueue (C2S, Empty 应答)、153 GetQueueStatus (C2S)、152 ChallengePlayer (C2S)、151 RespondChallenge (C2S)、156 NotifyChallengeInvite (S2C push)、154 NotifyChallengeResult (S2C push)；成局后 battle 推 177 NotifyBattleAssigned
- tables: Dungeon（max_team_size）、匹配模式配置、MatchError tip 段
- depends on: svc-battle-client-player（BattleNode.CreateBattle、票据）、scene ScenePrepareBattle / SceneNodeGrpc.PrepareBattle / CancelBattlePrepare（141/142/145/155，把玩家从场景冻结进战斗）、player_locator 会话、svc-client-player-team（组队开战）
- behavior: 模式 1V1 / 5V5 / PVE_SOLO（即时开战）/ PVE_TEAM / PVP_CHALLENGE（点名成局不入队），3V3 未开放 kMatchModeNotOpen；错误 kMatchAlreadyQueued / InBattle / NotInScene / CancelTooLate / TeamSizeNotConfigured / TicketMismatch / ChallengeSelf / ChallengePending / ChallengeTargetOffline / ChallengeTargetBusy / ChallengeSelfBusy / ChallengeNotTarget / ChallengeExpired / Internal；挑战窗只推给 ONLINE 会话（断线等重连期间不弹）；推送经 Kafka gate-cmd 分区、预算固定不继承请求 ctx
- internal: Redis 队列 + gather 开局管线（指纹、CAS 票据、可分配校验）、ELO rating、Kafka match 事件
- java: missing — gate 无 MatchService 路由 → 23{kServiceUnavailable}
- size: XL（建议拆：排队与成局 L、切磋 M、rating S）
- robot: robot-battle-smoke、robot-battle-smoke-cross-zone、robot-features-smoke（battle 段）
- hazards: CancelQueue 应答是 Empty（成功不回包，Java gate 的 hasResponse 规则已覆盖）；match 同进程还托管 team 服务

### svc-match-service-spectate-ticket — 观战匹配与战斗票据补签 MatchService（观战段）
- mmorpg: `go/match/internal/logic/{watchbattlelogic,listwatchablebattleslogic,spectate,requestbattleticketlogic}.go`；battle 侧 `BattleNode.AddObserver/RemoveObserver/IssueBattleTicket`（160/159/178）
- client messages: 163 WatchBattle (C2S)、164 ListWatchableBattles (C2S)、179 RequestBattleTicket (C2S；请求 / 应答复用 player_battle.proto 定义)
- tables: none
- depends on: svc-match-service-queue、svc-battle-client-player（观战首帧 161 经 Kafka 推）
- behavior: battle_id=0 随机观战；收到 161 NotifySpectateState 首帧才算进入观战；错误 kMatchSpectateWhileQueued / SpectateWhileInBattle / SpectateOffline / NoWatchableBattle / BattleNotWatchable / AlreadyWatching；补签只给参战者 / 观众（battle 本地核对名单并自签），客户端丢票或冷启动时用
- internal: spectate:battle:{battle_id} 索引定位 battle 节点
- java: missing
- size: M
- robot: robot-battle-smoke（B 观战跟看）
- hazards: RequestBattleTicket 曾在 BattleClientPlayer 下，gate 收缩后搬到 match；号 179 归 MatchService

### svc-battle-client-player — 回合制战斗（直连 battle 节点）BattleClientPlayer
- mmorpg: `proto/battle/player_battle.proto`、`battle_data.proto`；`cpp/nodes/battle/client/battle_client_edge.cpp`（直连面）、`cpp/nodes/battle/logic/battle_room_manager.cpp`（2081 行）、`cpp/libs/services/battle/system/turn_battle_engine.cpp`（1818 行）、`settlement/settlement_outbox.h`；gate 拒绝 `client_message_processor.cpp`（BattleNodeService 号回 kServiceUnavailable）
- client messages: 149 SubmitBattleAction (C2S)、140 GetBattleState (C2S, 应答 BattleStateS2C)、165 StopWatchBattle (C2S)、162 SetAutoBattle (C2S)；143 NotifyBattleStart、139 NotifyTurnResult、150 NotifyBattleEnd、144 NotifyBattleReconnect、161 NotifySpectateState、158 NotifySpectateTurnResult、166 NotifySpectateEnd、177 NotifyBattleAssigned（S2C push）；握手帧 BattleTokenVerifyRequest / Response（非 ClientRequest 信封）
- tables: Dungeon、Monster / 战斗数值表（表指纹 battle_table_fingerprint 校验两端一致）、Skill
- depends on: svc-match-service-queue、svc-match-service-spectate-ticket、scene 战斗准备（冻结 / 结算回写：battle_settlement_ledger、player_battle.cpp 读宝宝 / 属性）、svc-scene-pet-client-player
- behavior: 客户端第二条 TCP 直连 battle 节点（177 带 host / port / token_payload / token_signature / expire_at_ms），帧格式同 gate，首包 BattleTokenVerifyRequest（signature 为 hex HMAC，dev 空密钥也必须发握手）；握手超时关闭、连接上限、只放行四条上行、MessageLimiter 限速、非法包阈值 50；经 gate 发战斗号一律 23{kServiceUnavailable}；回合时长 / 自动战斗间隔、断线重连推 144、观众上限、结算经 outbox 重试回写 scene
- internal: 战斗房间管理、回合引擎、结算 outbox（Redis pending settlement + Kafka match_results）、Agones 端口映射
- java: missing — Java gate 对 BattleClientPlayer 号回 23{kServiceUnavailable}（与基线 gate 同形），但没有 battle 节点
- size: XL（建议拆：直连面 + 票据 M、回合引擎 L、观战 M、结算回写 M）
- robot: robot-battle-smoke、robot-battle-smoke-cross-zone、robot-features-smoke（features_battle_smoke.go）
- hazards: 结算是跨进程资产写入，需 owner_epoch 等价围栏；表指纹不一致必须拒开局；140 应答类型不是带 error_message 的响应（BattleStateS2C）

## B. 跨服务契约能力

### contract-gate-client-gates — gate 客户端消息闸（白名单 / 体积 / 限频 / 非法包 / GM 闸 / 战斗号拒绝）
- mmorpg: `cpp/nodes/gate/handler/rpc/client_message_processor.cpp` DispatchClientRpcMessage、`gate_security.h`、`gate_gm_client_messages.h`、`gate_router_mode.h`；scene 二道 GM 闸 `cpp/nodes/scene/handler/rpc/player/player_gm_guard.h`、`scene_handler.cpp` ProcessClientPlayerMessage
- client messages: 全部上行；拒绝时下发 23（1008 超频、1010 超长、1006 GM 被拒、kServiceUnavailable 战斗号 / 无后端）
- tables: MessageLimiter
- depends on: client-frame-handshake、MessageIdRegistry
- behavior: 未验证令牌即发包 → 断开；消息号不存在 / 非客户端协议 → 不回包、计非法包，达阈值（默认 50）断开；体积超限回 1010 计非法包；按消息号限频回 1008 计非法包；GM 六条（37/49/94/95/175/187）在 GATE_RUN_MODE 非 dev/test 时回 23{1006} 并计非法包；BattleClientPlayer 号回 23{kServiceUnavailable} 不计非法包不断连；scene 对退出中的玩家只放行 ExitGame，其余回 1006
- internal: 运行模式环境变量（GATE_RUN_MODE / SCENE_RUN_MODE / BATTLE_RUN_MODE）
- java: partial — 白名单 / 体积 / 限频 / 非法包阈值已做（`xm-gate/.../session/MessageRoutes.java`、`TableMessageLimits.java`、`GateProperties.illegalPacketThreshold`、`GateMetrics`）；战斗号与未接入服务回 kServiceUnavailable（`ClientDispatcher.dispatch` default 分支）；GM 号目前因 scene 未实现而回 1006（等效拒绝），**一旦实现任何 Gm* 处理器必须先补运行模式闸**
- size: S
- robot: robot-login-test（MessageBeforeLogin）、robot-stress-smoke；Java xm-robot 不覆盖
- hazards: GM 号挂在客户端协议服务上；GM 拒绝必须排在限频之后（否则被拒包不占额度可刷日志）

### contract-service-backend-routing — 非 scene 客户端服务的后端路由（路由服 / 直连）
- mmorpg: gate 直连模式 typed sender（`HandleGrpcNodeMessage`）与路由服模式（`GATE_CLIENT_RPC_ROUTER=1` → `go/client_rpc_router/internal/logic`，消息 176 ClientRpcRouterForward）；会话元数据 `x-session-detail-bin`；各 Go 服务 `internal/session` 校验
- client messages: 所有非玩家服务的客户端号（chat / friend / guild / team / jubaozhai / match / login）
- tables: 无
- depends on: contract-gate-client-gates
- behavior: 应答桥接只认成功响应，业务错误一律 in-band（error_message），gRPC 错误到客户端只剩超时 / 23；无可用后端回 23{kServiceUnavailable}；Login(48) 绑定后端实例（会话粘滞）
- internal: 路由服按服务名挑实例、Login 粘滞；Java 用 `MessageRoutes.SERVICE_BACKENDS`（服务裸名 → Dubbo group）+ `ClientMessageService.handle(ClientCall)`
- java: partial — 机制已在（`MessageRoutes`、`ClientDispatcher.callLogin`、`xm-api` ClientMessageService），表里只有 `ClientPlayerLogin`；每接一个新后端（chat / friend / guild / team / trade / match）加一行并实现对应 Dubbo provider
- size: S（每接一个服务的路由增量）
- robot: 各 *-smoke 场景
- hazards: Java 的 ClientReply 失败分层（body 内 error_message vs tip_id vs 23{1003}）要对所有新后端保持一致；guild 的 ClientMethods 式服务端白名单在 Java 侧也要有（UpdateGuildScore、Notify* 不可调用）

### contract-server-push-channel — 非 scene 服务向在线玩家推送（S2C）
- mmorpg: `go/shared/.../playercontract.PushToPlayer`、`kafkautil.PushToPlayer`、各服务 `push.go`（friend / guild / match / team）；gate 消费 `contracts/kafka/gate_command.proto`（`cpp/nodes/gate/handler/event/gate_kafka_command_router.cpp`）；会话定位 player_locator `player:session:{id}`
- client messages: 235 NotifyFriendEvent、220 NotifyGuildChanged、213/215/203 Team 推送、156/154 Challenge 推送、177 / 161 Battle 推送、34 KickPlayer、124 RedirectToGate
- tables: 无
- depends on: 玩家在线定位（player_locator 等价物）、gate 下行
- behavior: 至多一次投递、不在线不推、不补推，推送只是「去拉」的信号；只在权威写提交之后推；推送失败不影响 RPC 结果；不推给操作者本人
- internal: Kafka gate-cmd_g<N> 按 gate_node_id 分区；fail-closed 拒绝空 gate_instance_id
- java: missing — Java 现在只有 scene 经节点链路下行与 login 的会话指令；没有「任意服务 → 某玩家所在 gate」的推送通道与玩家在线定位（顶号用的是 Redis pub/sub 请 scene 让出，不是通用推送）。实现 friend / guild / team / match 前必须先做（Java 方式：Redis pub/sub 或 Kafka 到 gate，按 gate 节点号路由）
- size: M
- robot: robot-friend-smoke、robot-guild-smoke、robot-team-smoke、robot-battle-smoke
- hazards: 推送顺序与权威状态的竞态（客户端以拉取为准）；gate 进程重启后旧 gate_instance_id 的推送必须丢弃

## C. robot 验收场景

### robot-stress-smoke — 默认 stress 模式（robot_smoke / robot / behavioral / stress-* 配置）
- mmorpg: `robot/main.go` runRobot / runRobotOnce、`robot/login.go`、`robot/http_assign_gate.go`、`robot/logic/ai/{action,robot_ai}.go`、`robot/logic/handler/*`（约 100 个消息处理器）、`robot/etc/{robot_smoke,robot,robot.behavioral,robot.stress-*,robot.smoke-5k-z1}.yaml`
- client messages: assign-gate HTTP → 握手 → 48 → (14) → 26 → 79 → 77 → AI 循环（cast_skill=84、switch_scene=63、move=**43 SceneInfoC2S**（不是 134/132/131）、chat=61、idle）→ 17 → 58；被动处理 23 / 34 / 124 / 21 / 47 / 51 / 64 / 66 / 70 / 31
- tables: robot 本地加载 Skill / Class 等表（`table_dir`，缺表即 Fatal）
- depends on: svc-client-player-login、svc-login-pregate-http、svc-scene-scene-client-player、svc-scene-skill-client-player、svc-client-player-chat（behavioral / chatter profile）
- behavior: profile 权重：stress = 84:85% / 63:15%；behavioral = skill45 move25 switch20 idle5 chat5；fighter / explorer / chatter；断线后外层重连（带缓存 access_token 先试 access_token，失败回落口令）；统计 login_ok / enter_ok / login_fail / recon_fb；**退出码不反映成败**（只看统计行）；可接 LLM 顾问（默认关）
- internal: 无
- java: partial — Go robot 的 robot_smoke.yaml（3 账号）已对 Java 版实测 login_ok=3 enter_ok=3、stress AI 75s 稳定（PARITY「登录 → 进场景竖切」行）；Java xm-robot `scenario/SmokeScenario.java` 复刻其登录 → 进场 → 77 段并以退出码断言；chat 动作对 Java 回 23{kServiceUnavailable}（behavioral / chatter profile 会计失败）；大规模 stress（5k / 45k）未在 Java 版跑过
- size: S（Java 侧补：behavioral profile 实测、千级并发压测脚本）
- robot: 本身
- hazards: Go robot 的 move 动作只发 43，从未覆盖移动上行；`use_http_login: true` 的 stress-* 配置会先打 /api/login（Java 缺 → 回落 TCP 登录并计 login_fail）

### robot-login-test — 登录用例集（mode: login-test，22 个场景）
- mmorpg: `robot/login_test_scenarios.go`（1373 行）、`robot/login_test.go`
- client messages: 48 / 14 / 26 / 17 / 58、34（顶号判定）、84 / 70、63 / 79 / 31、54 / 37（货币）
- tables: Skill
- depends on: svc-client-player-login、svc-scene-skill-client-player、svc-scene-scene-client-player、svc-scene-currency-client-player
- behavior: 场景：NormalLogin、LoginLogoutCycle、WrongPassword、DuplicateEnterGame（拒绝或幂等受理都算过）、DuplicateLoginRequest、AccountDisplacement（A 在 5s 内收到 34 **或连接被关**即过）、ConcurrentSameAccount（至少一个成功、至多一个在线）、RapidReconnect、DisconnectDuringLogin、DisconnectDuringEnter、RapidDisconnectReconnect、AccessTokenReconnect（首登必须回 access_token；access_token 重登必须成功且**不轮换** token）、DifferentAccountSequential、LeaveAndReEnter、LeaveAndReLogin、RapidLoginSpam、MessageBeforeLogin（26 先于 48 后仍能正常登录）、LoginStuckDetection（限时完成）、BatchConcurrentLogin、SkillCast（收到 84 应答或 70）、SceneSwitch（63 后收到新的 79）、MultiRobotBehavior（≥70% 机器人进场且有行为记录）、CurrencyCrashWindow；结果写 behavior_test_results.csv / jsonl
- internal: 无
- java: partial — 未见在 Java 版跑过的记录；按代码推断 AccessTokenReconnect 与 CurrencyCrashWindow 必失败（无 access_token、54/37 回 1006），其余登录 / 技能 / 换图用例 Java 已具备能力；xm-robot 无对应用例
- size: M（Java 侧：跑通并把可移植的用例并入 xm-robot，如顶号、并发同账号、断线重连）
- robot: 本身
- hazards: 用例间只睡 500ms，Java 的归属租约（释放前夺权等 3s / 租约 30s）可能让紧邻用例撞 2005；AccountDisplacement 以连接关闭为通过条件，所以 Java 不发 34 也能过

### robot-data-stress — 数据一致性压测（mode: data-stress）
- mmorpg: `robot/data_stress.go`、`robot/etc/robot.data_stress.yaml`；校验端 `go/db/cmd/verifier`、`go/db/internal/stresstest/`；C++ 盖章 `cpp/libs/services/scene/player/system/stress_test_probe.cpp`（PlayerStressTestProbe）
- client messages: 48 / 26 / 17 循环
- tables: 无
- depends on: svc-client-player-login、玩家存盘链（scene → Kafka → db → MySQL）
- behavior: 每账号多轮 登录 → 进场 → 玩 → 登出；每次干净登出把 verify:expected:player_database:<player_id> 自增写 Redis；verifier 比对 MySQL 行数 / 内容是否收敛
- internal: Redis verify:enrolled:* / verify:expected:*，依赖 mmorpg 的 db 服务与 PlayerStressTestProbe 字段
- java: not_applicable — Java 不走 Kafka 存盘链、无 verifier；等价保障是 owner_epoch 围栏与写回结局指标（architecture.md §7、§11）。如要对等压测需 Java 自己的校验器
- size: M（若做 Java 版校验器）
- robot: 本身
- hazards: seq 语义是「轮数」而非写入版本；Java 的写回只在离场 / 断线（无周期存盘），压测口径不同

### robot-currency-crash — 货币崩溃窗口快照（mode: currency-crash-snapshot）
- mmorpg: `robot/currency_crash_window_scenario.go`、`robot/etc/robot.currency-crash.yaml`；编排脚本 `tools/scripts/currency_crash_window.ps1`；说明 `docs/notes/currency-crash-window-verification.md`
- client messages: 48 / 26、54 GetCurrencyList、37 GmAddCurrency
- tables: 无（gold = currency slot 0 写死）
- depends on: svc-scene-currency-client-player、周期存盘
- behavior: 单次运行：登录 → 读 gold → GM 加币 → 再读确认落到 scene 内存 → 写快照 JSON 后**不 LeaveGame** 直接退出；外部脚本在两次运行之间 kill -9 scene 再重启，比对余额量化丢失窗口；addAmount=0 时只读
- internal: 依赖 GATE_RUN_MODE / SCENE_RUN_MODE = dev
- java: missing — 54 / 37 在 Java 回 1006；且 Java 无周期存盘（architecture.md §7：被 kill 时在线增量全丢），场景前提不同
- size: S（货币服务就位后移植为 xm-robot 场景 + 周期存盘一起评估）
- robot: 本身
- hazards: 货币类型写死 slot 0；Java 要先定周期存盘策略，否则此场景必然「全丢」

### robot-features-smoke — 背包 / 任务 / 活动 / PVE 功能冒烟（mode: features-smoke）
- mmorpg: `robot/features_smoke_scenario.go`（549 行）、`robot/features_battle_smoke.go`（205 行）、`robot/logic/handler/player_features_response.go`、`player_feature_battle_response.go`
- client messages: 48 / 26（只选已有角色，不建角）、190 GetActivityList、191 GetBag、192 SortBag、193 GetMissionList、194 AcceptMission、195 ClaimMissionReward、124（跟随重定向）；battle 段：157 JoinQueue(PVE1) → 177 / 143 → 直连 140 / 162 / 139 / 150
- tables: ActivitySchedule、Item、Mission、Dungeon
- depends on: svc-scene-activity-client-player、svc-scene-bag-client-player、svc-scene-mission-client-player、svc-battle-client-player、svc-match-service-queue
- behavior: 默认只读，整理背包 / 接任务 / 领奖需显式开关；断言：活动列表无空项、UNSCHEDULED 不带日期与参与、非 OPEN 不得 can_participate；整理不改物品总数与货币、二次整理幂等；接任务后状态为已接；领奖后标记完成、重复领奖被服务端显式拒绝且不改背包 / 货币；state_persistent 必须为真；重登后任务进度与背包不变；battle 段只开放 PVE1 + 任务 12 链，断言 SIDE_A_WIN、有真实回合、BattleEnd 与开局一致；报告只含 id / 计数，不含凭据
- internal: 失败返回 FEATURES_SMOKE_FAIL 退出码 1
- java: missing — 190–195 在 Java 回 1006；无 match / battle
- size: S（服务就位后移植）
- robot: 本身
- hazards: 需要预先存在、配置好物品与任务的账号（不自动建角）

### robot-battle-smoke — 回合制战斗 + 观战（mode: battle-smoke）
- mmorpg: `robot/battle_smoke_scenario.go`（389 行）、`robot/battle_direct_conn.go`（直连客户端）、`robot/logic/handler/battle_client_player_*.go`、`match_service_responses.go`、`robot/etc/battle_smoke.yaml`
- client messages: A：157 JoinQueue(PVE_SOLO) → 143 NotifyBattleStart → 直连握手 BattleTokenVerify → 140 GetBattleState 补拉 → 162 SetAutoBattle → 139 → 150；B：163 WatchBattle(battle_id=0) → 177 NotifyBattleAssigned(role=OBSERVER) → 直连 → 161 首帧（必须从直连到达）→ 158 → 166
- tables: Dungeon / 战斗表
- depends on: svc-match-service-queue、svc-match-service-spectate-ticket、svc-battle-client-player
- behavior: 账号 robot_9001 / robot_9002；A 收到开战后先建直连再等「观战就绪屏障」才开自动（PVE 秒杀约 100ms，否则 B 扑空回 tip 5）；断言 B 的 battle_id = A 的、观战回合 ≥ 1、直连计数 ≥ 1、A 回合 ≥ 1；输出 BATTLE_SMOKE_OK / BATTLE_SMOKE_FAIL step=…，退出码 0 / 1；开战 30s、终局 120s 超时
- internal: 无
- java: missing
- size: S（随战斗功能移植）
- robot: 本身
- hazards: 直连拨号失败即 FAIL，无经 gate 回落

### robot-battle-smoke-cross-zone — 跨区 1V1 匹配（battle-smoke 的 cross_zone 子模式）
- mmorpg: `robot/battle_smoke_cross_zone_scenario.go`（311 行）、`robot/etc/battle_smoke_cross_zone.yaml`；设计 `docs/design/cross-zone-matchmaking.md`
- client messages: A 登 zone_a、B 登 zone_b → 各自 157 JoinQueue(1V1) → 143 → 直连 140 / 162 → 139 → 150
- tables: 战斗表
- depends on: svc-match-service-queue（跨 zone 匹配）、svc-battle-client-player、多 zone 部署
- behavior: 断言 A / B 的 gate 地址不同（不同 zone 证据）、battle_id 相同、双方回合 ≥ 1、直连计数 ≥ 1；输出 CROSS_ZONE_MATCH_OK / FAIL；账号与 battle-smoke 错开防互顶
- internal: 无
- java: missing — Java 也无跨 zone
- size: S
- robot: 本身
- hazards: 需要两个 zone 的 gate 同时在线

### robot-attribute-smoke — 属性加点冒烟（mode: attribute-smoke）
- mmorpg: `robot/attribute_smoke_scenario.go`（580 行）、`robot/etc/attribute_smoke.yaml`、`robot/logic/handler/scene_attribute_client_player_*.go`
- client messages: 175 GmSetPlayerLevel、37 GmAddCurrency、167 GetAttributePanel、173 AutoAllocateAttributePoints、168 AllocateAttributePoints、174 CreateAttributeScheme、171 SwitchAttributeScheme、172 ResetAttributePoints、170（被动）、48 / 26 / 58（重登）
- tables: AttributePool（行 1：每级 5 点）、AttributeDimension、AttributeRule
- depends on: svc-scene-attribute-client-player、svc-scene-currency-client-player、玩家持久化（attribute_component）
- behavior: 账号 robot_9101；预备：等级归 1、洗点（1 级免费）、切回首方案、GM 发金币；断言：面板全量非空；1→30 级总点恰好 +145；Auto 只算不落（增量和 = 剩余点）；按建议提交后剩余归零且二级属性变大；重发同一目标值 kAttributeNothingToChange；减少已分配被拒 kAttributePointsCannotDecrease；开方案按 create_scheme_cost_gold 精确扣金、新方案干净、冷却后切回原方案加点原样；重登后等级 / 方案 / 分配 / 二级属性一致；30 级洗点按 reset_cost_gold 精确扣金并全额返点；输出 ATTRIBUTE_SMOKE_OK / FAIL
- internal: 需 dev 运行模式（GM 号）
- java: missing
- size: S（服务就位后移植进 xm-robot）
- robot: 本身
- hazards: 依赖 GM 号放行，Java 实现 GM 闸后跑冒烟要开 dev 模式；相性 / 仙魔池 2026-09-14 已删，旧文档别照搬

### robot-pet-smoke — 宝宝系统冒烟（mode: pet-smoke）
- mmorpg: `robot/pet_smoke_scenario.go`（601 行）、`robot/etc/pet_smoke.yaml`、`robot/logic/handler/scene_pet_client_player_*.go`
- client messages: 175 / 37（预备）、187 GmGrantPet、181 GetPetList、167（池隔离检查）、182 ResetPetPoints、188 AutoAllocatePetPoints、186 AllocatePetPoints、183 SummonPet、185 RecallPet、184（被动）、重登 48 / 26
- tables: Pet（1 = 灵狐，1 级可携带）、PetRule、AttributePool
- depends on: svc-scene-pet-client-player、svc-scene-attribute-client-player、svc-scene-currency-client-player
- behavior: 账号 robot_9102；断言：列表全量非空；角色面板不得出现宝宝池、宝宝维度必须全属宝宝池（owner_type 分流）；主人 1→30 级时宝宝等级跟涨、点数 +145；洗点后 remaining == total；Auto 只算不落；提交后剩余归零二级属性变大；幂等 kPetNothingToChange；减少被拒 kPetPointsCannotDecrease；不存在 pet_id kPetNotFound；召唤后 active_pet_id 指向它、重复召唤 kPetAlreadyActive；重登一致；收回后 active 归零、再收回 kPetNotActive；输出 PET_SMOKE_OK / FAIL
- internal: 需 dev 运行模式
- java: done（2026-10-03，批次 2.8）— xm-robot `pet` 场景（新号，另加改名 / 洗点按 54 余额核对扣费）
- size: S
- robot: 本身
- hazards: 宝宝等级派生自主人（不独立升级）

### robot-chat-smoke — 全局聊天冒烟（mode: chat-smoke）
- mmorpg: `robot/chat_smoke_scenario.go`（526 行）、`robot/etc/chat_smoke.yaml`、`robot/logic/handler/client_player_chat_*.go`
- client messages: 61 SendChat、28 PullChatHistory
- tables: 无
- depends on: svc-client-player-chat、contract-service-backend-routing、（cross_zone=true 时）多 zone
- behavior: 账号 robot_9005 / 9006；A 发 WORLD 带 request_id → tip 0；B 拉最近 20 条恰好看到一次且 sender == A（服务端覆盖 sender）；A 私聊 B → B 拉 PRIVATE(peer=A) 看到；600 字节 WORLD → kMessageSizeExceeded（chat 侧 512 字节闸，先于 gate 1KB 闸）；同 request_id 重发受理但历史仍只一条；cross_zone 时断言两人 gate 地址不同；输出 CHAT_SMOKE_OK / FAIL
- internal: 无
- java: missing
- size: S
- robot: 本身
- hazards: 等待预算 10s 要大于 chat zrpc 超时，才能看到 kServiceUnavailable 而不是本地超时；Java 的 Dubbo 超时与 gate 23{1003} 路径要保证同样可区分

### robot-guild-smoke — 帮会分区 / 管理 / 推送冒烟（mode: guild-smoke）
- mmorpg: `robot/guild_smoke_scenario.go`（1084 行）、`robot/etc/guild_smoke.yaml`；设计 `docs/design/guild-zone-client-access.md`、`docs/design/guild-phase2/02-management.md` §24
- client messages: 15 / 60 / 35 / 29 / 38 / 39 / 19 / 217 / 216 / 218 / 219 / 222 / 221 / 223 / 27 / 52 / 8（反向）、220（推送断言）
- tables: GuildRule、GuildLevel
- depends on: svc-guild-service-membership、svc-guild-service-economy-rank（榜单）、contract-server-push-channel、多 zone（cross_zone 时）
- behavior: 账号 robot_9201–9203 + D/E/F；预备：撤申请、退 / 解散；A 建帮（请求体 zone_id 填错仍落归属区）、重复建帮 kGuildAlreadyInGuild；同区 B 查榜上榜；别区 C：GetGuild 不存在、伪造 zone 查榜未上榜、同名建帮 kGuildNameTaken（帮名全局唯一）；B 未入帮改公告 kGuildNoPermission；申请 → 审批 → 双方可见、成员名与 leader_name 非空；700 字节公告 kGuildAnnouncementTooLong；身份伪造（请求体 player_id 填 A）按会话让 B 退出；UpdateGuildScore 得 kServiceUnavailable 信封且积分仍 0；管理段 M1–M10：重复申请刷新不新增、未入帮列待审 kGuildNotInGuild、审批推 MEMBER_JOINED、拒绝推 APPLICATION_REJECTED 后再审 kGuildApplicationNotFound、任命长老幂等（officer_count / max_officers=2）推 ROLE_CHANGED、长老踢人推 MEMBER_KICKED 与 kGuildRankTooLow / CannotTargetSelf / TargetNotMember、转让帮主往返推 LEADER_TRANSFERRED、退帮推 MEMBER_LEFT；解散后从榜消失并清待审；输出 GUILD_MGMT_OK / GUILD_SMOKE_OK / GUILD_SMOKE_FAIL step=…
- internal: 无
- java: missing
- size: M（移植时可按段拆）
- robot: 本身
- hazards: APPLICATION_RECEIVED 有 60s 推送冷却（不断言）；同账号并行跑会互相顶号

### robot-guild-economy-smoke — 帮会经济段（guild_smoke.economy=true）
- mmorpg: `robot/guild_economy_smoke.go`（626 行）；设计 `docs/design/guild-phase2/05-economy.md` §5.40
- client messages: 120 / 53 / 76 / 228 / 233、191 GetBag（核对银两 / 灵石 / 物品）、37 / 49（GM 加扣币）、成员段同上
- tables: GuildDonate、GuildLevel、GuildShop、Item
- depends on: svc-guild-service-economy-rank、svc-scene-bag-client-player、svc-scene-currency-client-player（GM，需 dev）
- behavior: 账号 robot_9214 / 9215；计数按玩家按游戏日（UTC+8 05:00 切日），同日只能 full 一次，重跑走 degraded（只跑拒绝类断言）；full：大捐 ×2 资金 12k→24k、第三次 kGuildDonateLimit；灵石捐资金 44k 帮贡 440 并核对 scene 银两 / 灵石精确增减；余额不足 kGuildCurrencyInsufficient 且视图 REJECTED；成员升级 kGuildRankTooLow；帮主升级 Lv.2 上限 35、旧等级重发幂等；商店解锁按等级、兑换扣帮贡加物品、kGuildShopLevelTooLow / ContributionInsufficient / ShopLimit；结算中时轮询捐献页 ≤10s；输出 GUILD_ECONOMY_SMOKE_OK mode=full|degraded
- internal: 无
- java: missing
- size: S
- robot: 本身
- hazards: 切日边界（05:00 UTC+8）两版必须一致；资金 / 帮贡数值与配表强耦合

### robot-trade-smoke — 聚宝斋 P1 冒烟（mode: trade-smoke）
- mmorpg: `robot/trade_smoke_scenario.go`（876 行）、`robot/etc/trade_smoke.yaml`；设计 `docs/design/jubaozhai-market.md` §9
- client messages: 196 / 197 / 198 / 200；199 TradeAdmin.SeedListing 经 gRPC 直连 trade 造数据；反向：经 gate 发 199 必须超时
- tables: TradeTable 相关
- depends on: svc-client-player-jubaozhai、HomeZone、contract-gate-client-gates
- behavior: 账号 robot_9401–9403（首次在各自 zone 建角）；种子 market_zone = 卖家归属区；scope=zone：同区可见、别区不可见、zone_filter 伪造无效；scope=global：都可见、zone_filter 生效；每次浏览 market_scope 一致、server_now_ms≠0、页数自洽；公示商品只在公示列表；page_size=50 钳到 20、page=9999 钳到末页；拍卖分区 kTradeFeatureDisabled；详情 is_mine、不存在 / 别区 kTradeListingNotFound；收藏开关与 favorites_only；货架只含自己的；输出 TRADE_SMOKE_OK / FAIL
- internal: 一次运行只验一种 scope（进程级配置）
- java: missing — 唯一已与 Java 一致的断言是「经 gate 发 199 无回包」（Java gate 白名单丢弃非客户端号）
- size: S
- robot: 本身
- hazards: robot 直接 import grpc 调内部 SeedListing，Java 版需要等价的造数据入口（Dubbo 或 HTTP 管理接口）

### robot-team-smoke — 组队冒烟（mode: team-smoke）
- mmorpg: `robot/team_smoke_scenario.go`（1278 行）、`robot/etc/team_smoke.yaml`、`robot/logic/handler/client_player_team_*.go`；设计 `docs/design/team-system.md` §I.5
- client messages: 207 / 214 / 206 / 208 / 201 / 205 / 204 / 202 / 212 / 209 / 210 / 211、推送 213 / 215 / 203；63（队长换图）、79（跟随）；157 单人 PVE（S8）；177 / 143 / 直连 162 / 150；X1/X2 跨区
- tables: TeamError tip 段、Dungeon
- depends on: svc-client-player-team、contract-server-push-channel、svc-scene-scene-client-player（同节点跟随换图）、svc-match-service-queue、svc-battle-client-player
- behavior: 账号 robot_9301–9304；S1 建队（team zone == zone_a）→ 申请 → 同意，B 收 MEMBER_JOINED 且 version 递增；S2 重放同意幂等（join_seq 不变）；S3 邀请 → 拒绝（队长收 INVITE_CHANGED）→ 再邀 → 接受；S4 踢人（被踢者收 team_id=0 视图、membership_epoch 变大）、转让往返；S5 申请被拒收 203 APPLICATION_REJECTED；S6 队长换图后队员 10s 内收到 scene_id 相同的 79（**队伍跟随**）；S7 StartTeamMatch 回 STARTING、队员收 MATCH_STARTED（发起人不收）、同一 battle_id 开战并打完、双方收 MATCH_ENDED；S8 队员单人战斗持锁时开战 → kTeamMemberInBattle 且 parameters[0]=B；X1 跨区拒绝 kTeamCrossZoneDenied / X2 允许时跨区组队、别区成员不跟随换图；S9 解散推 DISBANDED；输出 TEAM_SMOKE_OK / FAIL
- internal: 推送按到达顺序留底、mark 后扫描
- java: missing
- size: M
- robot: 本身
- hazards: 队伍跟随换图是 scene 侧行为（player_team.cpp），不在 team 服务里；Java 的同节点换图已具备，但无队伍

### robot-travel-smoke — 跨 zone 场景传送往返（mode: travel-smoke）
- mmorpg: `robot/travel_smoke_scenario.go`（956 行）、`robot/travel_smoke_wire.go`、`robot/etc/travel_smoke.yaml`、`robot/logic/handler/scene_client_player_common_redirect_to_gate.go`、`robot/pkg/redirect.go`（FollowRedirect）；设计 `docs/design/cross-zone-scene-travel.md`
- client messages: 54 / 37（金币标记）、226 TravelToZone、124 RedirectToGate、新 gate 握手 + 48 / 26 严格重登、79、移动探针（尽力而为）
- tables: World（目标地图校验）
- depends on: svc-scene-scene-client-player（226）、svc-scene-client-player-common（124）、svc-client-player-login（HomeZone、票据 target_zone_id）、svc-scene-currency-client-player
- behavior: 账号 96xx（首次在 home_zone 建角）；T1 登 home 并 GM +11 金币得出发值（登录中就收到 124 = 上轮残留，直接失败）；T2 target_zone_id=0 必须同步拒（tip≠0）且不重定向、金币不变；T2b 不存在的 scene_config_id 同步拒；T3 去程等 124 → 先连新后关旧、票据原样验签、按 player_id 严格重登 → 等排在 124 之后的 79，断言 player_id 不变、票据 zone == visit、gate 地址变化；T4 访客区金币 == 出发值、+7 可写、停留熬过 home 区 30s 断线租约不被踢 / 不二次重定向；T5 回程同链反向；T6 回家金币 == 出发值 + 7、全程恰好 2 次 124；一次窗口内出现第二个 124 判失败；226 回包可选（124 到达后旧连接关闭，回包可能丢）
- internal: 无
- java: missing — Java 无跨 zone、无 226 / 124
- size: S（随跨 zone 传送功能移植）
- robot: 本身
- hazards: 通用重登在角色列表为空时会自动建角（假绿 + 脏数据），必须严格按 player_id 选角；「到了」不能用一次性 WaitSceneReady 信号判断

### robot-friend-smoke — 好友冒烟（mode: friend-smoke）
- mmorpg: `robot/friend_smoke_scenario.go`（755 行）、`robot/etc/friend_smoke.yaml`、`robot/logic/handler/client_player_friend_*.go`；设计 `docs/design/friend-port-20260918.md`
- client messages: 234 / 230 / 238 / 12 / 7 / 2 / 119 / 11 / 236、推送 235；反向：客户端发 235
- tables: FriendError tip 段
- depends on: svc-client-player-friend、contract-server-push-channel（跨区推送）、在线目录
- behavior: 账号 97xx；A AddFriend(B) → B 10s 内收 235{REQUEST_RECEIVED, by=A}（跨区推送三步）；B 拉 230 见 PENDING；A 重发 kFriendRequestAlreadySent；B Accept → A 收 235{REQUEST_ACCEPTED, by=B}，双方 12 互见，A 看到 B is_online=true、last_active_ms≠0；C Block(A)，双向 AddFriend 都 kFriendBlocked；RecommendFriends(limit=50) 钳到 ≤20 且不含自己 / 好友 / 拉黑者；A 发 235 号 → 拿不到业务回包、信封 tip = kServiceUnavailable；清理 Remove / Unblock；输出 FRIEND_SMOKE_OK / FAIL
- internal: 无
- java: missing
- size: S
- robot: 本身
- hazards: 客户端发 Notify* 推送号时基线回信封 kServiceUnavailable（friend 拦截器 PermissionDenied → 路由服翻译）；Java 实现 friend 后 gate 的 hasResponse 规则会让 Empty 应答「不回包」，与基线信封回 tip 不同——需在后端对 Notify* 返回 tip_id 而不是空应答

### robot-e2e-http — HTTP 登录 + token 刷新链路（use_http_login: true 的 stress 配置）
- mmorpg: `robot/etc/robot.e2e-http.yaml`、`robot.stress-*.yaml`、`robot.smoke-5k-z1.yaml`；`robot/http_login.go`、`robot/login.go` runTokenRefresher；网关 `java/gateway_node/.../{Login,RefreshToken}Controller.java`；login `go/login/internal/logic/clientplayerlogin/{deprecation,legacy_gate_killswitch,refreshtokenlogic}.go`
- client messages: HTTP `POST /api/login`（拿 access / refresh token）→ TCP 48 Login{auth_type="access_token"} → 26；周期 `POST /api/refresh-token`（无网关地址时回落 TCP 127 RefreshToken）
- tables: 无
- depends on: svc-login-pregate-http、svc-client-player-login（access_token 认证、token 不轮换规则）
- behavior: /api/login 响应 {code, message, retry_after_ms, queue_pos, access_token, refresh_token, access_token_expire, refresh_token_expire}，snake_case；access_token 临近过期时主动刷新（TokenRefreshOK / Fail 计数）；access_token 重登不得轮换 token；mmorpg 计划用 kill switch 关闭「TCP 口令登录」旧路径（legacy_gate_killswitch，ARCH §12 T+2），TCP 127 处于弃用窗口
- internal: login Redis token 存储
- java: missing — Java 无 /api/login、/api/refresh-token、access_token 认证；robot 在这些配置下会 login_fail 后回落 TCP 口令登录
- size: M
- robot: 本身
- hazards: 若 mmorpg 真的关掉 TCP 口令登录，Java 版将无法被新客户端登录——这是 Java 当前最大的客户端契约缺口之一

### robot-java-movement — Java 独有：移动 / 视野 / 66 / 137 探针（xm-robot movement）
- mmorpg: 无对应 Go robot 场景（Go robot 的 move 动作只发 43）；依据 `docs/reference/mmorpg-client-contract-{movement,aoi}.md`
- client messages: 134 / 132 / 131、137、21 / 47 / 51、66、48 / 14 / 26 / 79
- tables: 无
- depends on: svc-scene-movement-client-player、svc-scene-player-sync、svc-scene-scene-client-player
- behavior: 两新账号同场景 ≤10 m；B 收到 A 的 21/47 且实体号 / 位置一致；A 每 250ms Start → Sync×3（末条 30 m/s）→ Stop，B 每条输入后收到 A 的 66（超速截到 10 m/s、Stop 速度全零、位置 = 停止点、rotation = 上报值），停后 1s 静默，A 对上行无回包、不收 137、不收自己的 66；A 断开 B 收 51，重登位置 = 停止点；前跳 200 m：收到 137 校验 input_seq / 偏差 > 0.5 m / server_time_ms / 信封 id=0，否则按 fail-open；重登落盘位置 = 裁决位置；`--expect-jump correct|accept|auto`
- internal: 无
- java: done — `xm-robot/src/main/java/com/game/robot/scenario/{MovementScenario,MoveAssertions}.java`（单测 `MoveAssertionsTest`、`MovementPlanTest`）
- size: S
- robot: 本身（mmorpg 侧无，建议回移到 Go robot）
- hazards: 对基线（无导航网格）只能用 `--expect-jump accept|auto`，对 Java（MoveGuard）用 `correct`

### robot-reference-handlers — robot 作为参考客户端的消息处理器全集
- mmorpg: `robot/logic/handler/*.go`（约 100 个，按「服务+方法」一文件）、`handlers.go`、`message_body_handler.go`；`robot/pkg/redirect.go`；Unity 客户端对照 `../mmorpg-client/Assets/Scripts/Game/GameClient.cs`（OnNotify 注册表）、`Net/MessageIds.cs`（由 message_id.txt 生成，115 个常量）
- client messages: 全部客户端协议服务的应答与推送
- tables: 无
- depends on: 所有 svc-* 条目
- behavior: robot 对每个 S2C / 应答号都有处理器（含 Notify*），是判断「客户端到底读哪些字段」的最快入口；Unity 客户端只经 GameClient.OnNotify 注册（生成的 HandlerRegistry 从未被调用），已注册 124 / 177 / 127 / 21 / 47 / 51 / 64 / 70 / 33 / 133 / 135 / 130 / 137 / 23 / 34 等
- internal: 无
- java: partial — xm-robot 只实现登录 / 进场 / 移动所需（`xm-robot/src/main/java/com/game/robot/client/{RobotClient,Inbox,MessageIds}.java`），消息号经 `MessageIdRegistry.requireId` 解析
- size: S（随各服务移植时逐个补）
- robot: 全部场景
- hazards: Unity 客户端注册了 133 / 135 / 130，但两版服务端都从不发送；客户端不区分本地玩家的 70，Java 发给施法者本人是正确的

### contract-client-frame-handshake — 客户端帧格式与 gate 令牌握手
- mmorpg: `cpp/libs/engine/core/network/codec/codec.{h,cpp}`（ProtobufCodec）、`cpp/nodes/gate/handler/rpc/client_message_processor.cpp` DispatchTokenVerify、`cpp/nodes/gate/gate_codec.h`；robot `robot/pkg`（GameClient）
- client messages: 握手 ClientTokenVerifyRequest / ClientTokenVerifyResponse（裸帧，不进 ClientRequest 信封）；上行信封 ClientRequest{id, message_id, body}；下行信封 MessageContent{id, message_id, serialized_message, error_message}
- tables: 无
- depends on: svc-login-pregate-http（GateTokenPayload 签发）
- behavior: `[int32 len][int32 nameLen][typeName+终止符][body][int32 adler32]` 大端；len ∈ [10, 65536]；typeName 末字节剥掉（Go 写空格、C++ 写 \0）；非法帧丢弃并关闭；签名为 HMAC-SHA256 的 64 字节小写 hex；令牌校验 gate_node_id 与过期；推送 MessageContent.id=0，应答回显请求 id
- internal: gate_token_secret（环境变量注入）
- java: done — `xm-net/src/main/java/com/game/net/client/{ClientFrameDecoder,ClientFrameEncoder,ClientFrames}.java`（golden bytes 单测 `ClientFramesTest`）、`xm-gate/.../session/{ClientChannelHandler,ClientPipeline}.java`、`xm-common` 令牌签名（PARITY「客户端协议」行）
- size: S
- robot: 所有场景；Java xm-robot 复用 xm-net 编解码
- hazards: battle 直连复用同一帧格式但握手消息是 BattleTokenVerifyRequest（Java 尚无 battle）；proto 注释写的「32 字节签名原值」与实际 hex 文本不符，以实现为准

### contract-message-id-sync — 消息号 / 客户端 proto / tip / 配表同步
- mmorpg: `proto/message_id.txt`（0–243，生成器发号并复用空洞）、`proto/event_id.txt`、`data/tip/Tip.xlsx` → 生成的 tip 枚举、`generated/tables`；Unity `Net/MessageIds.cs` 由 `tools/gen_messageids.ps1` 生成
- client messages: 全部
- tables: 全部配表 + Tip
- depends on: 无
- behavior: 号是客户端契约但会随 proto 变化漂移，代码里不得写死数字；只能在 service 末尾追加方法（C++ 按 method index 分发）
- internal: Java `tools/ContractSync.java`（记录源 commit 于 `contract/SOURCE.properties`）、`xm-proto/.../contract/MessageIdRegistry.java`（按「服务裸名+方法名」解析，etcd 号预期不可解析）、`xm-table` + `xm-table-codegen`
- java: done — 当前对齐 mmorpg `26ceb70ca`；`MessageIdRegistryTest`、`SceneMessageIds.resolve`（缺号即启动失败）
- size: S
- robot: Java xm-robot `MessageIds` 经 `requireId` 解析
- hazards: 同步后必须 `clean install`（protoc 增量生成会残留旧类，AGENTS.md §4）；帮会 239–243 新号同步后 Java gate 回 kServiceUnavailable

## Open questions

1. **TCP 口令登录的去留**：mmorpg login 有 `legacy_gate_killswitch`（ARCH §12 T+2 关闭「客户端 → gate → Login 口令」旧路径），Unity 客户端与 stress-* 配置都已走 `/api/login` + access_token。Java 只有 TCP 口令登录、没有 access_token / refresh_token 体系——是否把「HTTP 登录 + access_token + /api/refresh-token」列为 Java 下一优先级？
2. **GM 客户端号**：37/49/94/95/175/187 在 Java 现在回 1006（未实现）。实现货币 / 属性 / 宝宝时，Java 是照搬「客户端协议 + 运行模式闸」，还是把 GM 能力改走独立管理接口（mmorpg 注释自己也说更好的做法是不标 OptionIsClientProtocolService）？两版需定一个口径，否则 robot attribute / pet / currency / guild-economy 冒烟无法在 Java 上跑。
3. **非 scene 服务的推送通道**（contract-server-push-channel）：Java 用 Redis pub/sub 还是引入 Kafka（tech-stack 写「Kafka planned」）？这决定 friend / guild / team / match 的落地顺序。
4. **客户端发 Notify* 推送号**：基线经路由服回信封 tip kServiceUnavailable（friend-smoke 第 6 步断言），Java gate 对 Empty 应答默认不回包。friend 等服务接入时要不要在 Java 后端对 Notify* 统一回 tip_id？
5. **login-test 套件是否要在 Java 上跑并入验收**：AccessTokenReconnect、CurrencyCrashWindow 按代码推断必失败；其余用例未见运行记录。
6. **周期存盘**：robot-currency-crash 与 travel-smoke（T4 熬过 30s 租约后金币仍在）都隐含周期存盘 / 迁移存盘；Java 首批无周期存盘（architecture.md §7），需要先定 Java 的存盘策略再移植这类场景。
7. **Go robot 的 move 动作只发 43**：Go robot 从未覆盖 134/132/131；是否把 xm-robot 的 movement 场景回移到 Go robot，作为两版共同的移动验收？
8. **ScenePlayerSync 的 65/55/82/68/75**：AttributeDelta*S2C 目前只有 entity_id，基线按距离三档调度但无有效载荷；mmorpg 是否计划填字段（届时 Java 要同步分档频率）？
9. **SceneRollbackClientPlayer** 十二条 GM 回档在 mmorpg 仍是空桩，Java 是否跳过直到 mmorpg 填实？
10. **robot 账号号段**：Go 冒烟用 robot_9001–97xx 固定号段且依赖「首次在某 zone 建角」登记 HomeZone；Java 无 HomeZone 概念，移植 trade / team / travel 场景前要先定 Java 的 HomeZone / 多 zone 设计。
