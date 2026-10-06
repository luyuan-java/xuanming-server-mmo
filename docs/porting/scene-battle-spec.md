# scene 侧战斗冻结与结算应用、结算发件箱（批次 6.3）移植统一规格

> **基线**：mmorpg `26ceb70ca`（`D:\work\mmorpg`，稀疏克隆）。本稿用到的目录都已检出。只有一处缺口：`activity.h:5-6` 引用的
> `go/guild/internal/data/trial_result_record.go` 在仓库里不存在，`go/` 下也搜不到任何 `activity_result` 的读者——基线的活动结果持久通道只有写方、没有销账方。
>
> **Java**：HEAD `aa8b5b5`（6.1 已提交）。工作区里还有 5.2 的未提交改动（xm-scene / xm-gate / xm-player-store / xm-scene-manager）和 6.2 的未提交改动
> （`xm-battle`、xm-api `BattleNodeService` / `battle_control.proto`、xm-common 票据、xm-net 限频）。Java 行号**以工作区为准**；5.2、6.2 提交后会漂移，
> 所以同时写出类名与方法名。
>
> **本稿的来历**：由三份分区稿合并——① 基线冻结面（备战冻结、快照、确认 / 取消 / 清理、锁与上下文、重连提示、在途闸、gate 拒绝战斗上行）；
> ② 结算链路（battle 发件箱 → 投递与重投 → scene 幂等应用 → 销账、活动结果通道、故障与崩溃）；③ Java 落地映射。
> 分区稿之间不一致、或与代码不符的地方都回到代码重新核对过，裁决记在 §10.6，对 inventory 的勘误记在 §10.7。本稿只读代码，唯一写入的是本文件。
>
> **实现状态（2026-10-06）**：本稿已落地。主代码在提交 `7dff75c`，之后经审计 → 修复 → 补测试 → 评审，两轮：第一轮修复与测试在 `4f3f345`，
> CI 上一处测试竞态修在 `d6b6b1e`，第二轮修正与文档见其后的提交（git log「批次 6.3 收尾」）。§7–§13 的正文已按实现回写，
> 与撰稿时不同的地方在各小节就地改写；新增的有意差异是 D29–D36（§11）。落地的类、审计确认并修掉的缺陷、测试与证据、遗留与残余风险见末尾
> 「实现记录（scene 侧、发件箱与评审修复）」。本稿里的 Java `文件:行` 引用是撰稿时（6.3 动工前）的位置，6.3 落地后已漂移，**以类名与方法名为准**。
>
> **路径缩写**（mmorpg 侧，相对 `D:\work\mmorpg`）
>
> | 缩写 | 路径 |
> |---|---|
> | `pb.cpp` / `pb.h` | `cpp/libs/services/scene/battle/system/player_battle.{cpp,h}` |
> | `ledger.h` / `cache.h` | 同目录 `battle_settlement_ledger.h` / `battle_settlement_application_cache.h` |
> | `outbox.h` | `cpp/libs/services/battle/settlement/settlement_outbox.h` |
> | `activity.h` | `cpp/libs/services/battle/system/battle_result_activity.h` |
> | `room.cpp` / `room.h` | `cpp/nodes/battle/logic/battle_room_manager.{cpp,h}` |
> | `engine.cpp` | `cpp/libs/services/battle/system/turn_battle_engine.cpp` |
> | `lc.cpp` | `cpp/libs/services/scene/player/system/player_lifecycle.cpp` |
> | `pet.cpp` / `attr.cpp` / `team.cpp` / `asset.cpp` / `snap.cpp` | 同目录 `player_pet.cpp` / `player_attribute.cpp` / `player_team.cpp` / `asset_op_system.cpp` / `player_feature_snapshot.cpp` |
> | `movement.cpp` | `cpp/libs/services/scene/spatial/system/movement.cpp` |
> | `skill.cpp` | `cpp/libs/services/scene/combat/skill/system/skill.cpp` |
> | `psh.cpp` / `mvh.cpp` / `rbh.cpp` | `cpp/nodes/scene/handler/rpc/player/` 下的 `player_scene_handler.cpp` / `player_movement_handler.cpp` / `player_rollback_handler.cpp` |
> | `sns.cpp` / `beh.cpp` | `cpp/nodes/scene/handler/grpc/scene_node_service.cpp` / `cpp/nodes/scene/handler/event/battle_event_handler.cpp` |
> | `gate_cmp.cpp` | `cpp/nodes/gate/handler/rpc/client_message_processor.cpp` |
> | `gap.md` | `docs/design/turn-battle-gap-closure.md`（§9.3 结算幂等基底） |
> | `t_settle` / `t_route` / `t_act` | `cpp/tests/bag_test/player_battle_settlement_test.cpp` / `cpp/tests/routing_identity_test/routing_identity_test.cpp` / `cpp/tests/turn_battle_engine_test/battle_result_activity_test.cpp` |
> | `gather.go` / `pc.go` | `go/match/internal/logic/gather.go` / `go/match/internal/playercontract/playercontract.go` |
>
> **proto 行号**一律用 mmorpg 的；Java 同步副本在 `xm-proto/src/main/proto/proto/...`，Java 行号 = mmorpg 行号 + 3（`battle-engine-spec.md:17-18`）。
>
> **Java 侧缩写**：`node-spec` = `docs/porting/battle-node-spec.md`（6.2）；`engine-spec` = `docs/porting/battle-engine-spec.md`（6.1）；
> `handoff-spec` = `docs/porting/scene-handoff-spec.md`（5.2）；`mirror-spec` = `docs/porting/dungeon-mirror-spec.md`（5.3）；
> `channels-spec` = `docs/porting/scene-channels-spec.md`（5.1）；`team-spec` = `docs/porting/team-spec.md`；`combat.md` = `docs/porting/inventory/combat.md`；
> `arch` = `docs/design/architecture.md`。

---

## 0 概览与范围（与 6.1 / 6.2 / 6.4 / 5.2 / 2.8 / 2.5 的边界）

### 0.1 结论速览

- **冻结的权威**：基线是 scene 实体上的 `InBattleComp{battle_id, battle_node_id, deadline_ms, state, prepare_deadline_ms}`（`battle_comp.proto:16-22`），不落库；
  伴生 Redis 锁 `battle:lock:{pid}`（值 = battle_id）与上下文 `battle:ctx:{pid}`（组件序列化），所有删 / 续都「值等于本局才动」（`pb.cpp:170-191`）。
- **Java 的根本前提不同**：断线即写回、释放、移除实例，重连从库新建实例（`SceneWorld.java:1384-1415`；`PARITY.md:91` ①）。内存冻结活不过任何一次断线，
  所以 **Java 的锁不再只是咨询性的，而是承重的**：备战时先把锁写成功再回 match，写失败就拒绝；锁独占，被别的局占着就拒绝（D3）。
- **锁的形态**：锁与上下文合成一个 Hash `xm:battle:{<pid>}:lock`（字段 `b n s d p`），「锁与 ctx 同生共死」由结构保证（D2）。
- **结算幂等**：账本进 `player_state.battle_ledger`（新字段 9），与货币 / 背包 / 宝宝 / 任务同一次带 `owner_epoch` 围栏的 MySQL 写；
  「别再发一次」看内存账本，「可以销账」看 `ScenePlayer.persistedState()`——与资产通道同一条判据（`AssetOpService.java:515-534`）。不移植进程内应用缓存（D18）。
- **发件箱**：battle 先落 Redis、后投递、未销账就有界重投（10 s × 12），照基线。待结算记录改成**每局一个字段**的 Hash `xm:battle:{<pid>}:settlement`（D13），
  首投与重投都按位置记录 + 节点目录解析（D14），ACK 仍是 scene 在 Redis 上的条件销账（与放锁同一段 Lua）；销账同时给这一局留一个 10 min 的
  「已销账」墓碑，晚到 / 被重放的落库见到它就不写（D29）。
- **传输**：采纳 6.2 的 Q13。scene 在现有资产 RPC 端口（21100）上再导出 `SceneBattleService`（Dubbo Triple，group `scene-battle`，`register=false`，
  按目录直连，`retries=0`），备战 / 取消 / 确认 / 结算四个方法都走它（D1）。
- **在途闸**：基线各系统各回各的码（3023 / 3025 / 25011 / 26008 / 1005 / 27002 / 7004 / 7002，移动静默丢）。Java 码与次序照搬，判定收成一个谓词
  `ScenePlayer.inBattle()`，客户端入口按方法声明 `BattlePolicy`（缺省 REJECT，D11），再加一张参数化「闸门矩阵」测试钉住。
- **与 5.2 互斥**：63 先判战斗在途（3023）再判换图在途（3014）；`PrepareBattle` 拒绝 `switchPhase ≠ NONE`；进入 FREEZING 前复查战斗在途；
  FREEZING 期间到达的结算回 DEFERRED、零副作用。
- **修掉的基线残留（多数只在故障路径上可见）**：进场恢复完成前结算回 DEFERRED（D20）；FIGHTING 判废加 10 s 宽限、判废前先查本局待结算记录（D21）；
  探测出错不再当成已销账（D15）；单槽覆盖丢奖消失（D13）。
- **宝宝参战**：快照与结算回写逐项照基线（死宝宝不参战、技能不过滤、按现算上限夹、阵亡回满、推一次 184）。
- **gate 拒绝战斗上行**：Java 已对齐（23 `{1003}`）；6.3 把拒绝提前到 GM 闸之后、热关停与待处理队列之前，回到基线位置（D12，`MessageRoute.directOnly`，§7.19）。
- **异步次序没有保证**：Java 的每条脚本各自异步，Redis 上谁先执行、回调谁先回到逻辑线程都不定（§1.8）。实现为此加了三道防线：进场恢复的代际号与
  「本实例已销账集合」（D32）、锁复核的单调（D30）、只删备战锁的删除入口（D31）。
- **验收**：6.2 管理口加 `POST /admin/battle/dev/gather`（dev / test 专用）：经 `SceneBattleService` 真实备战、取 scene 出的快照、建房（`origin = DEV_GATHER`），
  这类房间照常确认、照常结算。robot 新增 `battle-settle` 场景（§13.8）。

### 0.2 覆盖的盘点条目

| inventory id | `combat.md` | 本稿 |
|---|---|---|
| scene-battle-freeze | `:293-303` | §1、§7.4–§7.9 |
| in-battle-gates | `:317-327` | §2.1–§2.4、§7.13 |
| gate-battle-uplink-reject | `:329-339` | §2.5、§7.19（已对齐，本批调位置并补测试） |
| battle-settlement-outbox | `:269-279` | §3、§7.15–§7.17 |
| scene-battle-settlement-apply | `:305-315` | §4、§7.10–§7.12 |
| pet-battle-integration | `:341-351` | §5、§7.11 f 步 |

### 0.3 与相邻批次的边界

| 批次 | 6.3 依赖 / 提供 | 对方负责 |
|---|---|---|
| **6.1 引擎**（已提交） | scene 只用 `BattleRules.isTurnBattleCastableSkill`（engine-spec D7，`:1543`）与 `BattleTableFingerprint.compute`（engine-spec §10.1，`:1297`）；xm-scene 新增对 xm-battle-engine 的依赖 | 引擎内的宝宝单位、actor_id、只普攻、结算里带宝宝终值（engine-spec `:250`、`:313-319`、`:617`、`:939`） |
| **6.2 battle 节点**（实现中） | 实现 6.2 留下的三个出站端口 `SceneBattleEvents` / `SettlementSink` / `ActivityResultSink` 的真实传输；`RoomOrigin` 增加 `DEV_GATHER`；管理口增加 dev gather；修订 node-spec §7.13 的预留键名 | 收尾顺序（逐人「推 150 → 调 `SettlementSink`」，node-spec §4.6）、确认补发节奏（17 次，§4.8）、dev 房间不结算 |
| **6.4 match** | 提供 `SceneBattleService.prepareBattle / cancelBattlePrepare`、`BattleLockReader`（EXISTS / 读 battle_id / 批量）、`BattleResultSink` 只留日志实现 | 调备战 / 取消、读锁（JoinQueue 16000、切磋 16009 / 16010 / 16012、整队开战预检 4025）、对局结果的传输与消费、179 补签；对备战失败 / 超时的成员也发取消（Q21）。match-spec 已改用 `RedisKeys.battleLock`（Hash，EXISTS 语义不变）并经 `BattleLockReader` 读；match 的备战 / 取消调用超时保持 3 s、超时按结局不明处理并补发取消（§7.3、§10.4 的裁决） |
| **5.2 跨节点换图**（实现中） | 战斗冻结与交出互斥（`handoff-spec:106`）；`BattlePolicy` 与 `FreezePolicy` 并存；交出进场也跑进场恢复 | `FreezePolicy`、`SwitchPhase`、交出事务、`requestSave` 在 FREEZING 回 IN_FLIGHT |
| **2.8 宝宝**（已完成） | `PetService.buildBattleSnapshot` / `applyBattleSettlement` 新增；写闸 26008 接入 `PetService.checkWritable` | — |
| **2.5 任务**（已完成） | 结算的击杀事实调用现成的 `MissionService.onMonsterKilled`（`MissionService.java:208-215`），打通「任务 12 可领」端到端 | — |
| 2.7 气血 / 复活 | 结算回写气血法力与复活（`PlayerRevive.reviveIfDead`）、0 血拒绝开战（`PARITY.md:79` 的「Java 待做」） | — |
| 5.1 / 5.3 | 排空改派、级联改派跳过战斗中的玩家（`channels-spec:788`、`mirror-spec:121`、`:797`）；63 镜像分支由 63 的入口闸统一覆盖 | — |
| 5.4 | 留钩子：226 在 3024 / 3007 之后、3026 之前回 3025（`lc.cpp:2780-2788`）；跨 zone 起交接复查回 3025 | 实现 |
| 5.5 | 保证「写回后由下一个持有者按锁重建、结算按位置或进场恢复落地」（Q17） | 疏散改派跳过战斗中的玩家、到期写回（`scene-drain-spec.md` Q16） |
| 6.5 / 4.6 / 7.2 | 只提供 `BattleLockReader` | 观战 16015、帮会活动开局拒绝、活动结果的消费与巡检、GM 回档 1005（`rbh.cpp:72`）。7.2b 的 GM 回档已接：xm-data 在夺权之后批量读锁，命中记 `in_battle`、读失败记 `battle_lock_unknown`，无条件拒绝（`BattleLockGate`，见 data-ops-spec） |
| 4.3 组队 | 收掉 team-spec D10：`TeamMemberView.in_battle` 由锁算出；组队跟随读锁（均已落地，§7.13） | 整队开战预检（6.4） |

### 0.4 兼容面

- **客户端可见，两版逐字节相同**：144 / 150 / 184 / 23 的消息与推送时机；各在途闸的码与判定次序；`BattlePlayerSnapshot` 经 battle 进入客户端可见的
  `BattleActorState`（属性、技能、道具、宝宝）；结算落地后的面板数值（167 / 181 / 191 / 54 / 193）。
- **服务端内部，按 Java 方式重做**：传输、线程、Redis 键与脚本形态、账本格式、发件箱存储、指标。
- **不在客户端契约里、但 Java 仍须保住的语义**（其它进程依赖）：备战拒绝即零副作用（match 依赖）；锁存在 = 在途（match / team / guild 依赖）；
  锁 TTL ≥ 期限 + 60 s（6.2 的确认补发窗口依赖，node-spec §10.4）；结算恰好一次。

---

## 1 基线备战冻结

### 1.1 入口、线程与常量

| 入口 | 来源 | 线程 | 出处 |
|---|---|---|---|
| `PrepareBattle` / `CancelBattlePrepare` | match 的 gRPC `SceneNodeGrpc`（gather 管线） | gRPC 同步线程 `runInLoop` 后 `future.get()` 等 loop | `sns.cpp:186-202`、`:308-340` |
| `BattleConfirmedEvent` / `BattleSettlementEvent` | battle 经 Kafka `SceneCommand{DispatchEvent}` | loop 线程，进程内分发 | `beh.cpp:7-34`；`battle_event.proto:13-25` |
| 进场后置钩子 `OnPlayerEnterScene` | 进场流程第 6 步（组队跟随第 3.5 步之后） | loop | `lc.cpp:1706-1709`、`:1730-1734` |
| reaper | 节点启动注册、停止注销 | loop，`runEvery(30 s)` | scene `main.cpp:307`、`:139`；`pb.cpp:2090-2211` |

| 常量 | 值 | 出处 |
|---|---|---|
| reaper 间隔 | 30 s（「分钟级精度」） | `pb.h:101-102` |
| 锁余量 `kLockExtraTtlSec` | 60 s：锁必须比 `InBattleComp` 活得久 | `pb.h:109-111` |
| 锁 TTL | `(deadline − now)/1000`（不足取 0，整数截断）+ 60 | `pb.cpp:229-234` |
| speed 缺配兜底 | 120（2026-09-14 单位 ×12） | `pb.cpp:99-102` |
| 结算后续锁 `kSettlementLockHoldSec` | 180 s = 重投窗口 10 s × 12 + 60 s | `pb.h:112-117` |
| 挂起结算 TTL | 7 天 | `pb.h:118-119`；`room.h:179` |
| 备战期限 | gather 起点 + matched TTL（1 / 2 / 5 / 10 人组为 42 / 48 / 66 / 96 s） | `gather.go:220-226`；node-spec §4.8 |
| 战斗期限 | gather 起点 + `BattleMaxDurationSeconds` | `gather.go:219` |
| 确认补发 | 首发 1 次，之后每 10 s 补发共 17 次，窗口 180 s | node-spec §4.8（`room.cpp:1107-1128`） |

### 1.2 锁与上下文

**键与语义**（`pb.cpp:81-97`）：

- `battle:lock:{pid}` = battle_id 十进制。match 只看在不在（`pc.go:112-121` 出错返回 `(true, err)`；各调用方先看 err，口径不一，见 §2.4），team 用 MGET 读值（`go/match/internal/team/presence.go:92`，
  失败按「不在战斗」）。帮会历练**没有**生产读者：`go/guild/internal/activity/rules.go:391` 只是 `MyTrialBattleID` 字段注释里提到锁，全仓只有单测给它赋值；
  活动开局读锁的是 match（`go/match/internal/logic/activitybattlelogic.go:220-228`）。scene 是唯一写者。
- `battle:ctx:{pid}` = `InBattleComp` 序列化，scene 私有。它服务两条「实体没了、锁还在」的路径：完整下线再登录；备战到期后迟到的确认（`pb.h:19-22`）。
  `battle_node_id` 只存在于 ctx 里，确认事件不带（`pb.cpp:1204-1205`）。

**Lua**（都按「锁值 == ARGV[1]」才动作）：

| 脚本 | 作用 | 出处 |
|---|---|---|
| `kDeleteLockIfMatchScript` | 匹配才删锁与 ctx；返回 1 / 0 | `pb.cpp:174-176` |
| `kConfirmLockIfMatchScript` | 匹配才 SET ctx（EX ttl）并 EXPIRE 锁 | `:179-182` |
| `kGetCtxIfLockMatchScript` | 匹配才返回 {ctx 或 nil, 锁 TTL}，否则 false | `:185-187` |
| `kGetLockAndCtxScript` | 返回 {锁值, ctx, TTL}；无锁返回 false | `:189-191` |
| 销账 / 离线暂存 / 结算后续锁 | 见 §4 | `:193-227` |

- **备战时的 SET 不是 Lua**：锁与 ctx 是两条无条件 `SET … EX`，不回读、不等结果；Redis 未连接时只打 WARN、备战照常成功，理由是「权威仍在 InBattleComp」（`pb.cpp:1187-1222`）。

**由 ctx 重建的取值规则** `BuildInBattleFromCtx`（`pb.cpp:608-637`）：battle_id 以调用方给的（锁值 / 事件）为准，ctx 里的 battle_id 不一致就整体不信；
ctx 不可信时状态取 FIGHTING（保守冻结）；`deadline_ms = 0` 时按锁剩余 TTL 反推（只会偏晚）；PREPARING 且 `prepare_deadline_ms = 0` 时补成 `deadline_ms`。

**解冻的唯一入口** `RemoveInBattleComp`（`pb.cpp:148-168`）：一并摘掉备战会话记录；只有确实摘掉了组件才回调 `PlayerTeamSystem::OnBattleFreezeCleared` 补一次跟随
（`team.cpp:139-142`）。`ClearBattleFreeze` 先发条件删锁、再摘组件（`pb.cpp:639-645`）：跟随链会读锁、读不到就放弃，**依赖 hiredis 同连接先进先出**才能读到删锁之后的状态（`:148-152`）。

### 1.3 `PrepareBattle`（`pb.cpp:1105-1231`）

**判定顺序**（前一步拒绝，后面全不执行，且不留任何冻结痕迹）：

| # | 条件 | 应答 `error_message.id` | 出处 |
|---|---|---|---|
| 1 | player_id、battle_id、deadline_ms 任一为 0 | 1005 `kInvalidParameter` | `:1110-1116` |
| 2 | 实体不在本节点 | 1004 `kEntityIsNull` | `:1118-1126` |
| 3 | `PlayerFrozenComp`（跨 zone / 同 zone 交接冻结） | 1006 `kFeatureUnavailable` | `:1128-1135` |
| 4 | 已有 `InBattleComp`（结算串行化 D4） | 1006 | `:1137-1145` |
| 5 | `BaseAttributesComp.health == 0` | 1006 | `:1147-1156` |
| 6 | 组快照失败：缺 `BaseAttributesComp` / 缺会话快照或 `gate_session_id == 0` | 1004 / 1011 `kSessionNotFound` | `:889-905`、`:857-863` |

- **这些码对客户端不可见**：match 对任何非 0 码一样处理——记 `prepare_failed`，肇事者出局删票，已冻结的人回队首（`gather.go:241-251`、`:428-430`）。客户端只看到 match 的结果（6.4）。
- 基线**不拒绝**「普通换图应答在途」（只查 `PlayerFrozenComp`），见 D4。

**成功的副作用**（顺序固定）：`prepare_deadline_ms` 为 0 时取 `deadline_ms`（`:1167-1170`）→ 挂 `InBattleComp{…, PREPARING}`（`:1172-1178`）→ 记下
`BattlePrepareSessionComp{快照路由里的会话号}`（`:1179-1182`；语义 `:128-139`）→ 应答 `table_fingerprint` 与快照里的同值（`:1184-1185`）→ 锁与 ctx 各一条
`SET … EX ttl`，`ttl = LockTtlSecFor(prepare_deadline)`，不等结果（`:1187-1217`）。**不停止位移**：速度保留，帧积分把 `InBattleComp` 排除在外（`movement.cpp:49-52`）。

**玩家快照** `BuildBattleSnapshot`（`pb.cpp:885-1103`；字段 `battle_data.proto:37-59`）：

| 字段 | 基线取值 | 出处 |
|---|---|---|
| `player_id` | Guid | `:907` |
| `player_name` / `appearance_id` / `gender` | `PlayerProfileComp`；缺组件填空 / 0，不拦开战 | `:908-913` |
| `class_id` | `PlayerUint32Comp.class_` | `:914-916` |
| `level` | `max(level, 1)`（缺组件取 1） | `:918-919` |
| `base_attributes` | 整份 `BaseAttributesComp`；speed 为 0 时取 120 并 WARN | `:921-928` |
| `max_health` / `max_mana` | 派生属性 > 0 就用，否则 `max(当前, 1)` | `:930-941` |
| `physical_attack` / `magic_attack` / `defense` | 派生属性；缺组件为 0 | `:942-947` |
| `pets` | 最多 1 只，见 §5.1 | `:949-953` |
| `skill_table_ids` | `PlayerSkillListComp` 原顺序；跳过 0 与查不到行的；`skill_type` 任一位号是被动 0 / 持续施法 2 / 开关 3 就剔除（位号不是掩码）；引擎再过滤一次 | `:955-991`；engine-spec `:616` |
| `buffs` | 剔除 Stun / Freeze / Silence 与瞬时类；layer 至少 1；施法者只在「是自己」时映射成 player_id；剩余回合按表的**全量**时长换算（无限 = 0） | `:993-1048` |
| `items` | 只取主背包；跳过数量 0 的堆；只取 `ItemTable.battle_usable ≠ 0`（判据与结算共用 `IsBattleUsableItem`，`:683-689`）；按 config_id 合并（`std::map` 升序），数量 uint64 累加 | `:1050-1077` |
| `routing` | session_id、gate_node_id、gate_instance_id（取不到只 WARN、不拒）、scene_node_id / uuid、zone_id | `:1079-1094`；`:848-876` |
| `team_index` | 0（由 match 改写） | `:1096-1097` |
| `table_fingerprint` | 本节点战斗表指纹 | `:1099-1101` |

### 1.4 取消 `CancelBattlePrepare`（`pb.cpp:1233-1310`）

| 情形 | 处理 | 出处 |
|---|---|---|
| 实体不在本节点 | Redis 未连接则跳过（锁随 TTL 过期）；否则「锁值匹配才读 ctx」：锁不在或易主 → 幂等忽略；ctx 是本局且 FIGHTING → 拒绝（`metric=battle_cancel_rejected_fighting`）；其余 → 条件删锁。**两次往返** | `:1239-1280` |
| 在线、没有 `InBattleComp` | 幂等忽略（**不碰锁**） | `:1282-1289` |
| battle_id 不符 | 忽略（迟到的取消，防止解冻下一局） | `:1290-1296` |
| FIGHTING | 拒绝并记 metric：确认已到 = 房间建成过，这时的取消是 CreateBattle 超时后的过期回滚 | `:1297-1306` |
| PREPARING | `ClearBattleFreeze` | `:1308` |

### 1.5 确认与两条重建路径

**`ConfirmBattle`**（`pb.cpp:1312-1438`）：

| 情形 | 处理 |
|---|---|
| player_id 或 battle_id 为 0 | ERROR，丢弃（`:1316-1321`） |
| 实体不在 | `deadline_ms = 0` 或 Redis 未连接就跳过；否则 `GetCtxIfLockMatch` → 回调里 `BuildInBattleFromCtx` → 改 FIGHTING、写正式期限 → `ConfirmBattleLockIfMatch`（**两次往返**，`:1331-1373`） |
| 在线、没有组件 | `RebuildBattleFreezeFromLock(…, "late_confirm")`（`:1377-1386`） |
| battle_id 不符 | 幂等忽略（`:1387-1393`） |
| 已不是 PREPARING | 幂等忽略（重投 / 补发，`:1394-1400`），**不再续期** |
| PREPARING | → FIGHTING；deadline 取事件值（0 时沿用）；条件续期并覆写 ctx（`:1402-1408`） |

- **补推 144**（`:1416-1437`）：备战会话记录缺失、或记录的会话号 ≠ 当前会话号，且当前会话号 ≠ 0 → 推 `BattleReconnectS2C{battle_id}`；推完摘掉会话记录。

**迟到确认重建** `RebuildBattleFreezeFromLock`（`:1440-1525`）：`GetCtxIfLockMatch`，锁不在或易主不重建（`:1459-1465`）→ 回调里实体已换或已有组件就放弃（`:1466-1479`）→
**活账本里已有这一局**就不重建、改推进销账（`:1480-1489`）→ 重建，**一律 FIGHTING**，有 deadline 提示就覆盖（`:1490-1501`）→ `ConfirmRebuiltFreeze` 复核：再发一次条件续期，
没命中就撤销重建（`metric=battle_freeze_rebuild_reverted`，`:1503-1505`；`:540-579`）→ 有会话就无条件推 144（`:1512-1521`）。

**登录重建** `RestoreBattleFreezeOnLogin`（`:1527-1611`）：`GetLockAndCtx`，无锁或 EVAL 出错都按无锁（`:1535-1537`）→ 实体已换或已有组件不动（`:1539-1546`）→
锁值解析失败或为 0 返回（`:1547-1566`）→ 账本里已有这一局则推进销账（`:1567-1576`）→ 按 ctx 重建，**保留 ctx 里的状态**（PREPARING 也照样重建，`:1577-1582`）→
复核，TTL 按有效期限（PREPARING 看 `prepare_deadline`）（`:1584-1591`）→ **只有 FIGHTING 才推 144**（`:1600-1607`）。

### 1.6 进场钩子与 144（`pb.cpp:1999-2088`、`:1977-1997`）

1. 所有登录类型都先 `GET` 挂起结算（`:2011-2063`）：没有 → 无组件时 `RestoreBattleFreezeOnLogin`；解析失败 → 两键无条件 DEL，**不按锁重建**（`:2041-2058`）；
   有 → `ApplyPendingSettlement`，其末尾再按锁重建一次（`:1963-1972`）。次序靠同连接 FIFO（`:2015-2017`）。
2. `LOGIN_RECONNECT` / `LOGIN_REPLACE` 且组件仍在、FIGHTING → 推 144（`:2070-2087`）。REPLACE 也算（跨 gate 重定向后被判为 REPLACE）。**PREPARING 不推**：房间还没建，补签会被判 BattleGone（`:2076-2079`）。
- **144 的下发**：scene 经大厅会话下行，没有组件就什么都不做（`:1977-1997`）。客户端拿 battle_id 走 `MatchService.RequestBattleTicket`（179）补签、重建直连，
  再 `GetBattleState`（140）补拉。基线 robot 对 144 是空处理（`robot/logic/handler/battle_client_player_notify_battle_reconnect.go:8-9`），**没有端到端覆盖**。

### 1.7 reaper 与冻结时长上界（`pb.cpp:2090-2195`）

- 每 30 s 扫一遍组件，先收集再处理（`:2108-2129`）。
- **PREPARING**：有效期限 = `prepare_deadline`（0 时退回 `deadline`）。过期**只摘组件、锁保留到 TTL**，给迟到的确认留重建余地；`metric=battle_prepare_expired`（`:2137-2151`）。
- **FIGHTING**：`deadline < now` 即 `ClearBattleFreeze`，`metric=battle_freeze_expired`（`:2153-2158`）。**没有宽限**（`gap.md:328-329` 自认「恰在 deadline 结算的超时平局可能输给一次 reaper tick」）。
- 第二遍排空账本（`:2161-2190`），见 §4.5。
- 期限比较用墙钟 UTC 毫秒；期限由 match 用墙钟生成，节点间钟差基线不处理。

| 情形 | 冻结上界 |
|---|---|
| 已 FIGHTING 但 DestroyBattle / battle 崩溃 | 期限 + 最多 30 s（node-spec §4.7、Q5） |
| PREPARING 由 match 断链收尾 | 备战期限 + 最多 30 s；锁再多留 60 s |

### 1.8 基线依赖 hiredis 同连接先进先出的地方（Java 必须改写）

1. `ClearBattleFreeze`：删锁先进管道，跟随链随后读锁（`pb.cpp:642-644`、`:148-152`）。
2. 进场钩子：挂起结算应用后的销账 / 续锁排在读锁之前（`:2015-2017`、`:1968`）。
3. 结算后的快速存盘：用 PING 判断落盘（`:385-397`）。
4. 销账回调里补跟随：靠删锁先于这次回复执行（`:304-309`）。
5. 备战的两条 SET 与随后的取消 / 删锁之间没有显式先后保证。

Redisson 不保证这一点（`PARITY.md:91` 记过 NOSCRIPT 重发时命令被重排的实例），Java 一律改成「同一段 Lua」或「future 链：后一个命令在前一个完成之后才发」。

---

## 2 战斗在途闸

### 2.1 判定口径

基线判定都用 `IsInBattle` = 组件存在（`pb.cpp:879-883`），PREPARING 与 FIGHTING 一样对待。闸只放在入口层：`BagService` / `CurrencySystem` **刻意不闸**
（D48 红线：结算自己扣药会被自己拦住，`bag_service.cpp:411-413`；`asset.cpp:750-752`）。

### 2.2 逐系统闸（按客户端消息号）

| 系统 / 入口 | 消息号 | 基线行为 / 码 | 在判定链中的位置 | 出处 |
|---|---|---|---|---|
| 换场景 | 63 | 应答 `{3023 kEnterSceneFailed}` | 服务器类型 3004 之后、换图在途 3014 之前 | `psh.cpp:54-61`；`scene_error_tip.proto:58` |
| 镜像进场（辅助函数） | —（63 已先拦） | 返回 false | — | `player_scene.cpp:137-143` |
| 跨 zone 传送 | 226 | `3025 kZoneTravelInBattle` | 目标 zone 3024 → 非世界地图 3007 → **3025** → 组队 3026 → 忙 13000 / 3014 | `lc.cpp:2780-2788` |
| 起交接时复查 | 由 18 应答触发 | 同 zone 3023 / 跨 zone 3025，以 23 推送 | 冻结前 | `lc.cpp:2853-2860` |
| 移动 | 134 / 132 / 131 | 静默丢（应答 Empty）；131 仍把速度清零 | 入口第一步 | `mvh.cpp:163-212` |
| 帧外推 | — | 战斗在途的实体不积分 | — | `movement.cpp:49-52` |
| 属性写 | 168 / 172 / 174 / 171 / 169 / 175 | `25011 kAttributeInBattle` | `CheckWritable`：实体 → 冻结 1005 → **25011** | `attr.cpp:279-291`；调用点 `:602`、`:640`、`:737`、`:769`、`:794`、`:810` |
| 属性自动加点 | 173 | **不闸**（只给建议） | — | `attr.cpp:675-685` |
| 宝宝写 | 183 / 185 / 186 / 182 / 189 / **187** | `26008 kPetInBattle` | `CheckWritable` 同口径 | `pet.cpp:230-242`；调用点 `:479`、`:504`、`:519`、`:562`、`:653`、`:691` |
| 宝宝自动加点 | 188 | 不闸 | — | `pet.cpp:601-611` |
| 整理背包 | 192 | 1005 | 合法性 1005 → 缺背包 1003 → 层不一致 → **1005** | `snap.cpp:160-172` |
| 资产通道 | Dubbo debit / credit | `RETRY{27002}`，**不记账** | 第 7 步：冻结 27003（第 5 步）与中止占位（第 6 步，战斗中也允许）之后 | `asset.cpp:750-796` |
| 放技能（施法者） | 84 | `7004 kSkillCannotBeCastInCurrentState` | 查表之后、目标之前 | `skill.cpp:219-229` |
| 放技能（目标） | 84 | `7002 kSkillInvalidTarget` | 目标类型之后 | `skill.cpp:410-414` |
| 施法点目标已进战斗 / 实时 buff | — | 丢弃 / 不落 | — | `skill.cpp:703-712`；`buff.cpp:107-115`（两版都不生效：`PARITY.md:77`、`:80`） |
| GM 回档 / 回收 | GmAttachDebt 等 | 1005 | 冻结之后 | `rbh.cpp:57-82` |
| 组队跟随 | 服务器触发 | 跳过；在途**或锁存在**都跳过（同一跳读队长位置 + 自己的锁，读不到按在途） | — | `team.cpp:350-406` |
| 解冻后补跟随 | — | `OnBattleFreezeCleared → RefreshAndFollow` | — | `team.cpp:139-142`；`pb.cpp:166`、`:304-309` |
| 场景排空（SceneDrain） | 服务器触发 | **不看战斗**：照常退出并改派（锁留下，目标节点进场时按锁重建） | — | `lc.cpp:2149-2190`、`:2225` |

### 2.3 基线不闸、Java 也不得加闸的入口

| 入口 | 基线状态 |
|---|---|
| 货币 GM 37 / 49 / 94 / 95 | 不闸（`grep IsInBattle` 无命中；交接冻结另有 27003 / 1005） |
| 任务接取 / 领奖 194 / 195 | 不闸 |
| 只读入口 43 / 77 / 54 / 167 / 181 / 190 / 191 / 193 | 不闸 |
| 聊天 / 好友 / 帮会请求本身 | 不闸；帮会扣资产经资产通道得到 RETRY 27002，由 4.5 按通用 RETRY 处理 |

### 2.4 场景外读锁的地方（6.3 只提供键与读取工具，码由各批实现）

| 读者 | 判定 | 客户端看到 | 出处 | Java 批次 |
|---|---|---|---|---|
| JoinQueue | 锁存在 → 16000；**读失败回 `kMatchInternal`**（不是 16000，`IsBattleLocked` 虽返回 `true` 但调用方先判 err） | `16000 kMatchInBattle` / 内部错误 | `joinqueuelogic.go:95-111`；`pc.go:112-121` | 6.4 |
| 切磋发起 / 应战复查 | 自己有锁 / 目标有锁；读失败一律按「忙」 | 16010 / 16009；16012 / 16010 | `challengelogic.go:69-87`、`:245-265` | 6.4 |
| 观战 | 锁存在；读失败回内部错误 | 16015 | `watchbattlelogic.go:80-89` | 6.5 |
| 整队开战预检 | 锁存在（读失败按在途） | `4025`，参数为 pid | `go/match/internal/team/service.go:429-435` | 6.4（Java 现在恒回 4027） |
| 队伍视图 | 读时由锁算出（MGET 失败为 false） | `TeamMemberView.in_battle` | `team-spec:1221` | **6.3，已做**（收掉 team-spec D10：`TeamDisplay` 经 `BattleLockReader.existsAll` 批量读，任何失败整批按 false，§7.13 世界内部第 4 条） |
| 帮会活动开局（match 的预检） | 锁存在；读失败回 `ACTIVITY_BATTLE_REJECT_INTERNAL` | `ACTIVITY_BATTLE_REJECT_MEMBER_IN_BATTLE` | `go/match/internal/logic/activitybattlelogic.go:220-228` | 6.4（match 侧）/ 4.6（消费） |

### 2.5 gate 拒绝战斗上行（`gate_cmp.cpp:937-950`）

- **前置闸顺序**：会话存在 → 已过令牌校验（`:858-863`，否则断连）→ 消息号在白名单（`:874-886`，计非法包）→ 体积与限频（`:888-889`）→ GM 闸（`:909-933`）→ **战斗拒绝**（`:944-950`）。
- **战斗拒绝**：`targetNodeType == BattleNodeService` 的号一律回 23 `TipInfoMessage{1003 kServiceUnavailable}`；不计非法包、不断连，逐条只打 DEBUG；直连与路由两种模式都拦。
  范围是 `BattleClientPlayer` 服务的全部 12 个号：139 / 140 / 143 / 144 / 149 / 150 / 158 / 161 / 162 / 165 / 166 / 177（`player_battle.proto:185-203`；`message_id.txt`）。
- **时序**：当场发出，不排在其他在途请求之后。
- **`targetNodeType` 的来源**（实现时核对；§7.19 原稿曾把它当成方法的 `OptionFileDefaultNode`，不对）：基线 gate 的节点类型来自 protogen `NodeServiceForCpp` 的三级回落——
  服务名（加 `NodeService` 后命中 `eNodeType`）→ proto package 驼峰化后命中 → proto 所在目录名（`protogen/internal/model.go:167-183`），与 `OptionFileDefaultNode` 无关。
  `BattleClientPlayer` 没有 package，靠目录名 `battle` 落到 `BattleNodeService`；现行契约上圈出的正是这 12 个号。
- **限频在前**：战斗拒绝排在体积与限频之后（`ValidateClientMessage`，`:888-889`）。被拒的战斗号照样占限频额度：同一个号每秒超过表里的额度（缺省 3 条）时，
  超出的回信封 1008 并计非法包，超长回 1010 并计非法包——「不计非法包」只指战斗拒绝这一步本身。Java 同（§7.19）。

---

## 3 结算发件箱与投递（battle 侧）

### 3.1 消息与键

| 消息 / 键 | 定义 | 用途 |
|---|---|---|
| `BattleSettlementData` | `battle_data.proto:185-202` | 每人一份：`battle_id`、`player_id`、`outcome`、队伍、HP / MP 终值、`exp_gain`、`gold_gain`、`items_consumed`、`items_gained`、`is_dead`、`fled`、`total_rounds`、`pets[]`、`defeated_monsters[]` |
| `BattlePetSettlementData` | `battle_data.proto:205-210` | `pet_id`、`health`、`mana`、`is_dead` |
| `BattleSettlementEvent` | `battle_event.proto:13-15` | `{settlement}`；也是 pending 记录的值 |
| 150 `BattleEndS2C{battle_id, outcome, settlement}` | `player_battle.proto:50` | battle 经直连推一份；scene 应用后经大厅再推一份，客户端按 battle_id 幂等（`room.cpp:1162-1165`） |
| `battle:settlement:pending:{pid}` / `…:pending:id:{pid}` | `outbox.h:94-99` | 每人**单槽**，blob 与伴生 id 同 SET / 同 TTL（7 天）/ 同 DEL；伴生 id 是「条件销账」与「ACK 探测」共用的尺子 |
| `battle:activity_result:{battle_id}` | `activity.h:30-41` | 活动局结果，TTL 7 天，battle 写、guild 删 |

### 3.2 `FinishBattle` 的顺序（`room.cpp:1130-1221`）

按参战者逐个（`:1144-1175`）：组 settlement（覆盖 `outcome`、补 `battle_id`）→ 经直连推 150（`:1170`）→ `DispatchSettlementDurably`（`:1174`）。
然后观众收尾、关直连，最后发结果事件：活动局走持久通道，普通局只发一次（`:1199-1211`）。**不结算的路径**：DestroyBattle（`room.cpp:644-670`）、停机 AbortAllRooms（`:1327-1353`）、节点崩溃。
Java 6.2 的调用点已按 player_id 升序逐人「推 150 → 调 `SettlementSink`」（node-spec §4.6，R6）。

### 3.3 `DispatchSettlementDurably`（`room.cpp:1657-1708`）

1. 序列化失败只打 ERROR，**什么都不发**（`:1663-1670`）。
2. Redis 未连接：直接投一次，`metric=battle_settlement_not_durable`，不登记 outbox（`:1675-1684`）。
3. 否则 EVAL 两键同 SET；**只有在回调里**才投递：回复为空或出错 → 仍投一次、打 not_durable、不登记（`:1691-1698`）；成功 → 先投递、再 `EnqueuePendingSettlement`（`:1700-1701`）。
4. **顺序是硬要求**（`room.h:343-347`）：先投后落库时，scene 可能先应用并销账（销账是空操作），迟到的 SET 留下孤儿记录，下次登录**重复发奖**。
5. 首投目标取快照路由的 `(scene_node_id, scene_instance_id)`（`:1672-1673`）；scene 的 Kafka 过滤器实例对不上就丢，实例为空则接受。

### 3.4 重投（`room.cpp:1710-1849`；判定纯函数 `outbox.h:55-74`）

- 条目键 `(battle_id, player_id)`，值是原样 payload、开局时的 scene 节点（只进日志）、attempts（`room.h:331-339`）。节点级 10 s 定时器在第一条入队时启动，outbox 空了就停（`:1710-1735`）。
- 每轮开始 Redis 未连接就整轮跳过，**不消耗次数**（`:1744-1750`）。
- 每条第一跳 `GET pending:id`：只有读到「字符串且等于本局」才算仍是我们的，否则摘除并视为已销账（`:1776-1785`）。**缺陷**：回复为空或出错时同样走「已销账」，重投提前停止（D15）。
- 第二跳 `GET player:{id}:location` 重新解析节点号，**绝不复用**开局时的 uuid（`:1793-1802`）。
- 判定顺序「不再是我们的 → kDone」>「次数用尽 → kExhausted」>「解析不到位置 → kSkipNoTarget」>「kResend」（`outbox.h:55-74`）。判定用的是**本轮之前**的次数，判定**之后**才 `++attempts`，
  skip 轮也计次（`room.cpp:1810-1811`）。所以 attemptsSoFar = 0…11 的 12 轮重投（或跳过），**第 13 轮**才判用尽，入队后约 130 s（单测 `t_route:361-386`：11 → resend，12 → exhausted）。
  第二跳发出前 Redis 未连接同样直接返回、不计次（`:1789-1792`）。
- 重投实例 uuid 留空（「谁持有这个 node_id 就谁执行」，`:1815-1825`）；用尽打 `metric=battle_settlement_undelivered` 并摘除，记录留给登录钩子（`:1831-1839`）。
- **没有确认通道**：ACK 只有一种，就是 scene 侧条件删 pending；投递本身是 Kafka 单向。

### 3.5 单槽与覆盖

pending 每人一槽，battle 无条件 SET。下一局如果在上一局销账之前结束，就会覆盖上一局唯一的持久副本。基线的缓解是「锁留到落盘」：锁在，match 就不放下一场
（`gap.md:299-308`）。基线文档自己承认「单槽覆盖只剩锁自然过期一种来路；彻底修法是 pending 改成按 (player, battle) 一条，要动两端 key 契约，未做」（`gap.md:332-333`）。

### 3.6 活动结果持久通道（`room.cpp:1861-2031`；`activity.h:25-108`）

- 只有 `activity_context.kind ≠ NONE` 的局；**不认识的 kind 也按活动局处理**（`activity.h:43-48`）。
- 序列化失败 → `metric=battle_activity_result_serialize_failed`，不落库不投递；Redis 未连接 → 只发一次 + not_durable；`SET key payload EX 7d` 成功 → 发 Kafka `match-results` 并登记；
  失败或命令没发出 → 发一次、不登记。
- 重发：每 10 s 对每条 `EXISTS`；**回复为空本轮跳过不计次**；只有整数 0 才算已销账，出错或类型不对按「仍在」（`room.cpp:1993-2004`）；判定顺序：已销账 > 用尽（30）> 重发**原字节**
  （`activity.h:93-108`）。与结算探测（出错当已销账）口径不一致。
- 销账方 guild 与巡检器**在基线未实现**（本稿开头）。

### 3.7 battle 侧故障窗口

| # | 窗口 | 结果 | 出处 |
|---|---|---|---|
| B-1 | 战斗中 battle 崩溃 | 没有结算；scene reaper 在 deadline 后判废 | `room.h` 头注释 |
| B-2 | FinishBattle 之后、SET 落地之前崩溃 | **结算整笔丢失**（客户端可能已从直连收到 150） | 窗口 = 一次 Redis 往返 |
| B-3 | SET 已落地、首投之前崩溃 | 在线玩家冻到 deadline 后被 reaper **判废**（锁删），pending 留到下次登录补应用 | `room.cpp:1700-1701`；`pb.cpp:2153-2158` |
| B-4 | 首投丢失 | 10 s 后探测 → 重新解析 → 重投，≤ 12 次 | `room.cpp:1765-1849` |
| B-5 | 次数用尽 | ERROR；记录保留 7 天给登录钩子 | `:1831-1839` |
| B-6 | 探测时 Redis 回复出错 | 误判已销账、提前停止重投（缺陷） | `:1776-1785` |
| B-7 | FinishBattle 时 Redis 不可用 | 只投一次、不持久 | `:1675-1684` |

---

## 4 scene 侧结算应用（基线）

### 4.1 入口分流 `ApplySettlement`（`pb.cpp:1811-1934`）

| 条件 | 动作 | 出处 |
|---|---|---|
| `player_id` 或 `battle_id` 为 0 | ERROR，返回（不销账） | `:1816-1820` |
| 玩家不在本节点，或正在退出（`UnregisterPlayer`） | `StorePendingSettlementIfLockMatch`：锁 == X 就重写 pending（灰度兼容）；锁属于别的局 → 按「已取代」销账；**锁不在什么都不做**，留给登录钩子；EVAL 出错不动 | `:1827-1847`；`:453-502` |
| 在线，组件存在但 id ≠ X | WARN 丢弃 + `AckSettlementPending(X)` | `:1849-1859` |
| 在线，没有组件 | 异步 `GET lock`：出错 → 保留待重投；锁 ≠ X → 丢弃 + Ack；回调时实体没了或在退出 → 放弃；回调时已有别的局的组件 → 丢弃（**不** Ack）；否则按锁应用（`metric=battle_settlement_applied_by_lock`）→ `ReleaseFreezeKeepLock` + Ack + 推 150（已应用过的不推） | `:1860-1917` |
| 在线且 id 匹配 | 应用 → `ReleaseFreezeKeepLock` → Ack → 推 150（已应用过的不推）。先销账后应用会两头落空 | `:1920-1933` |

### 4.2 `ApplySettlementToEntity` 的步骤（顺序就是语义，`pb.cpp:1613-1809`）

1. **归属校验**：实体有效、Guid 等于 `settlement.player_id`、`battle_id ≠ 0`，否则返回 false：不应用、不销账、不毒化后续重投（`:1617-1625`；`t_settle:213`）。
2. **持久账本命中** → 返回 true、`alreadyApplied = true`：调用方照常解冻并销账，**不再推 150**（`:1631-1637`）。
3. **整笔可应用**：跨 zone 冻结、交接在途、退出中都返回 false，等重投（`:1643-1648`；判据必须是各内部闸的超集，`:345-358`）。
4. 应用缓存：账本未命中而缓存说已完成时以账本为准先 Forget（`:1650-1659`），之后在缓存的 `Apply` 里执行副作用（`cache.h`）。
5. 缺 `BaseAttributesComp` → false（`:1664-1670`）。
6. **金币**：`gold_gain > INT64_MAX` 或 `AddCurrency` 失败 → false。这是**唯一会正常失败的入账**，必须排在一切其它副作用之前（`:1672-1690`）；流水原因取缺省 `TX_CURRENCY_ADD`、关联号 0。
7. **HP / MP**：按派生上限夹，`max > 0` 才夹（`:1691-1705`）；`is_dead || health == 0` 时 `ReviveBaseAttributesIfDead`——它内部只在 `health == 0` 时回满（`player_database_loader.cpp:77-82`）
   （`:1706-1720`）；`MarkAttributeForUpdate(kHealth / kEnergy)` 在基线是空操作（`actor_attribute_calculator.cpp:46-52`），**不推任何属性消息**（`:1721-1722`）。
8. **宝宝**：逐个 `PetSystem::ApplyBattleSettlement`，有宝宝条目时推**一次** 184（`:1724-1734`；详见 §5.3）。
9. **经验**：只打日志，基线没有经验系统（`:1736-1742`）。
10. **道具**（`:1744-1753`、`:691-837`），全程不让整笔失败（金币已入账）：
    - **消耗**：跳过 0；非 `battle_usable` 的 id 拒绝并 ERROR（防伪造，`:721-729`）；uint64 → uint32 夹紧（`:730-735`）；`RemoveItemsClamped` 按实际持有夹紧，逐实例记
      `TX_ITEM_DESTROY`，关联号 = battle_id，extra = `{"source":"battle","battle_id":N}`（`:706-707`、`:740-741`）；扣不满 WARN（`:752-761`）；之后 `MergeAndCompact(kMergeOnly)`（`:765`）；
      抽取顺序是 entt 视图的遍历序，没有规定。
    - **掉落**：入包前记下各配置持有量（`:794-798`）；主背包 `AddItems(TX_ITEM_AWARD)`（`:800-801`）；失败时只把「实收不足」的部分改投临时格（`:804-828`）；两处都放不下 ERROR、道具丢失（`:830-834`）。
11. **任务击杀**：每只怪一条 `kConditionKillMonster`，`amount = 1`，0 号跳过（`:1755-1767`）。
12. **登记账本**：在**所有副作用之后**（`:1768-1779`）；淘汰了旧项打 `metric=battle_settlement_ledger_evicted`。
13. 缓存回 Applied 时 `RequestSettlementPersist` 并返回 true；其余返回 false，保留 pending（`:1789-1808`）。

### 4.3 账本规则（`ledger.h:21-88`；单测 `t_settle:325-360`）

- 容量 64 是**异常兜底**（稳态 0~1 条，`ledger.h:23-32`）。
- `HasApplied`：battle_id = 0 永远不命中（`:34-42`）。
- `RecordApplied`：已在账本里只刷新时间戳；满时淘汰 `applied_at_ms` 最小的，**按时间戳不按下标**，返回 evicted（`:44-74`）。
- `ForgetApplied`：**只能在销账 EVAL 成功回调里**调用（`:76-88`）。两条判据必须分开：活账本 =「别再发一次奖」，落盘快照 =「可以销账」（`ledger.h:17-20`）。

### 4.4 销账唯一入口 `AckSettlementPending`（`pb.cpp:400-451`）

1. 本节点有活实体、账本有 X：已落盘（条目在 `PlayerLastPersistedSnapshotComp` 那份字节里，`:334-343`）→ 条件销账 + 放锁；没落盘 → 什么都不删，没有未落地存盘时再压一次（`:441-448`）。
2. 本节点有活实体、账本无 X：判废或重复投递，立即条件销账（`:431-435`）。
3. 本节点没有活实体：只有调用方正面证明「锁已被别的局持有」才销账，否则留给登录钩子（`:418-430`）。

**销账 Lua** `kAckSettlementScript`（`:193-205`）：`pending:id == X` 才删 pending 两键（位 1）；`lock == X` 才删 lock + ctx（位 2）；两件事必须在同一段脚本里。
回调（`:284-310`）：出错保留账本条目；回复为整数 → `ForgetApplied`；锁确实被删且没有组件 → 补一次组队跟随。

### 4.5 快路径、续锁、reaper 第二遍、登录补应用

- **快路径**：`RequestSettlementPersist` 压出一笔存盘后，在**同一条 hiredis 连接**上紧跟一条 `PING`，PING 回来时当场 Ack（`pb.cpp:362-398`）。
- **续锁**：`ReleaseFreezeKeepLock` 只摘组件，同时把锁续到 ≥ 180 s（`:647-679`）。锁是「这一局还没完」的唯一跨进程证据（`gap.md:299-308`）。
- **reaper 第二遍**：每 30 s 对所有带账本条目的玩家各调一次 Ack（`:2161-2190`）。
- **登录补应用** `ApplyPendingSettlement`（`:1936-1975`）：应用 → `HoldBattleLockUntilDurable` + Ack → id 相同时摘组件 → 推 150（已应用的不推）→ **再按锁重建一次冻结**。
  **登录钩子不看锁**：锁 TTL 是 deadline + 60 s，pending 活 7 天，按锁闸会把合法的离线结算判掉（`:1926-1931`）。

### 4.6 scene 侧故障窗口（`gap.md:259-338`）

| # | 窗口 | 结果 |
|---|---|---|
| S-1 | 已应用（内存）、存盘前崩溃 | 资产与账本一起丢；pending 在、锁续到 180 s；重投或登录重新应用（不丢） |
| S-2 | 已落盘、销账前崩溃 | 账本在盘上；登录或重投被账本挡住，只销账（不重复） |
| S-3 | 销账 EVAL 失败 | 账本条目保留；reaper 每 30 s 重试 |
| S-4 | 实体被废黜后重建 | 账本随资产一起没，以账本为准重新应用（`pb.cpp:1650-1659`） |
| S-5 | 冻结或交接期间到达 | 整笔不应用、不销账，等重投 |
| S-6 | 金币被封禁或溢出 | 整笔不应用；常被 reaper 按 deadline **判废**，之后的重投以「锁不在」丢弃（Q-a） |
| S-7 | 崩溃 + 停机超过 180 s，重投恰好**先于**登录钩子打到新实体 | 「锁不在 + 账本无」被判废，**pending 被删、奖励丢失**（`gap.md:330-331`） |
| S-8 | reaper 判废与超时平局的结算赛跑 | 结算输了就被当作作废丢弃（`gap.md:328-329`） |
| S-9 | 单槽被覆盖 | 只剩「锁自然过期」一条来路（`gap.md:332-333`） |
| S-10 | 离线玩家被判废的局 | 登录时补发（`gap.md:326-327`，既有语义） |
| S-11 | 回档 | 账本退回旧值、pending 不参与回档 → 可能重发；与回档语义自洽（`gap.md:335-336`） |

---

## 5 宝宝参战

### 5.1 快照 `PetSystem::BuildBattleSnapshot`（`pet.cpp:867-912`）

- **不带宝宝的情形**：没有出战宝宝、宝宝找不到、缺表行或缺池（WARN）、**宝宝气血为 0**（死宝宝不参战，`:886-889`）。
- **字段**：`pet_id`；`owner_player_id`；名字为空时用表里的名字；表 id；等级；现算的派生属性（`ComputeDerived`）给出 max 气血 / 法力、物伤 / 法伤 / 防御；
  `base_attributes` 只填三项：`health = min(当前, max)`、`mana` 在 max > 0 时取 min 否则取原值、`speed` 取派生速度，其余为 0。
- **技能 = 种类自带 ∪ 已学**（`ResolveSkills`，`pet.cpp:95-109`，先种类后实例、去重），**不做可施放过滤**。

### 5.2 引擎内（6.1 已移植，只列 6.3 需要知道的约束）

- 只有出战宝宝（至多一只）参战，与主人同队，`actor_id = kPetActorIdBase + 局内序号`，真实 pet_id 另存（engine-spec `:250`、`:313-319`）；`pet_id == 0` 或同局重复的宝宝整局拒绝。
- 宝宝恒为挂机、只普攻，技能列表从不被使用，但会出现在客户端可见的 `BattleActorState.skill_table_ids` 里（engine-spec `:617`）——**基线怪癖，照搬**。
- 宝宝不建结算条目，终值随主人结算的 `pets[]` 带回，不论死活（engine-spec `:939`）；宝宝击杀算主人的（engine-spec `:1512-1514`）。

### 5.3 结算回写 `PetSystem::ApplyBattleSettlement`（`pet.cpp:914-941`）

- 找不到 pet_id → WARN 忽略；缺表行或缺池 → 静默返回。
- `health = min(结算值, 现算上限)`；`mana` 上限 > 0 时夹，否则取结算值。
- `is_dead` 或夹后气血为 0 → **回满**：`health = 上限`、`mana = 法力上限`（不回满的话死宝宝会被快照永远挡在门外）。
- 全部宝宝写完后推**一次** 184 `PetListChangedS2C`（`pb.cpp:1727-1734`；`pet.cpp:943-950`）。

---

## 6 客户端可见行为

### 6.1 消息与时机（两版相同）

| 情形 | 客户端看到 |
|---|---|
| 备战成功 / 失败 | 无（match 的结果由 6.4 给出） |
| 备战成功的那一刻 | 旁观者收到一条速度为 0 的 66（**Java 独有**，D5；基线什么都不发，观察者按旧速度继续外推） |
| 确认到达，且冻结是在别的实例上建的 | 大厅推 144 `{battle_id}`；PREPARING 期间永不推 |
| 战斗中重连 / 顶号 / 交出进场，冻结为 FIGHTING | 79 / 21 / 47 之后大厅推 144 |
| 结算应用（本次新应用） | 184（有宝宝条目时，一次）→ 150 `BattleEndS2C{battle_id, outcome, settlement=引擎原值}`，同一条 gate 链路 FIFO；账本命中的不推 |
| 结算回写气血 | 不推 170 / 66（同基线 Q-h）；面板 167 / 181 主动拉取可见 |
| 在途闸 | 63 → 3023（先于 3014）；134 / 132 静默丢；131 只清速度；168 / 172 / 174 / 171 / 169 / 175 → 25011；183 / 185 / 186 / 182 / 189 / 187 → 26008；192 → 1005；84 → 7004，打在途目标 → 7002；资产通道 RETRY 27002（帮会捐献等表现为稍后重试）；173 / 188 / 37 / 49 / 94 / 95 / 194 / 195 与只读入口照常 |
| 大厅连接发战斗号 | 12 个号都回 23 `{1003}`，不断连 |
| 队伍视图 | `TeamMemberView.in_battle` 在锁存在期间为 true（6.3 起；之前恒 false） |
| 冻结解除时刻 | 结算应用即解；作废：PREPARING 在备战期限后 ≤ 30 s，FIGHTING 在期限 + 10 s 后 ≤ 30 s（D21） |

### 6.2 只在竞态 / 故障路径上可见的差异

| 情形 | 基线 | Java |
|---|---|---|
| 换图选目标中（RESOLVING，≤ 4 s）恰好备战 | 开战，随后换图失败（23 `{3023}`） | 这人被 match 判为备战失败，换图照常（D4） |
| 备战时 Redis 故障 | 照常开战（锁缺失） | 开局失败（D3） |
| battle 在落库后、首投前崩溃（B-3），或超时平局输给 reaper（S-8） | 作废，无奖励 | 期限 + 10 s 时按本局记录正常结算并推 150（D21） |
| 下一局覆盖了上一局未销账的记录（S-9） | 上一局丢失 | 两局都在，登录按局序应用（D13） |
| 进场恢复未完成时到达的结算（S-7） | 可能被判废删除 | 延后到恢复之后应用（D20） |
| 消耗按格子扣 | 未规定哪一堆先扣 | 格子号升序（D22） |
| 战斗中的玩家所在频道被排空 | 退出并改派 | 留在原频道直到结算（D26） |
| 备战所在的 scene 进程在确认之前崩溃，玩家已重进别的实例 | 收不到 144；锁按备战 TTL 过期后可被再次匹配，这局结算被丢弃 | 确认改投到玩家现在的实例：升级 FIGHTING、收到 144、这局照常结算（D28） |
| 迟到确认的回复回来时，进场恢复已按更早的快照重建出同一局的 PREPARING 冻结 | 已有组件就放弃这条确认，等下一次补发（可能已是最后一次）才升级、推 144 | 当场按正常确认升级 FIGHTING 并推 144（D33） |
| 备战请求的期限离现在超过 7 天（只可能是坏数据） | 照常冻结 | 1005，零冻结（D36；码只给 match 看） |

---

## 7 Java 设计

### 7.1 模块、包与依赖

| 模块 | 新增 / 改动 |
|---|---|
| `xm-discovery` | `RedisKeys.battleLock(pid)` / `battleSettlements(pid)` / `battleSettled(pid, battleId)`（已销账墓碑，D29）/ `battleActivityResult(battleId)`；包 `com.game.discovery.battle`：`BattleRedis`（全部 Lua 文本、返回码与跨进程常量的唯一出处，scene 与 battle 共用；结果形状 `EnterRead` / `SettlementField`）、`BattleLockReader`（EXISTS / 读 battle_id / 批量，给 match、team、scene 的组队跟随、xm-data 的回档闸与以后的 guild） |
| `xm-api` | `com.game.api.SceneBattleService`；`xm/api/scene_battle.proto`（`SceneBattleCall` / `SceneBattleReply` / `SceneBattleStatus` / `SettlementDisposition`）；`DubboGroups.SCENE_BATTLE = "scene-battle"`（改 `SCENE_ASSET` 注释里「同一端口上不会有别的服务」，`DubboGroups.java:29-33`）；新增通用的按节点直连客户端 `com.game.api.rpc.NodeRpcClients<S>`（node-spec Q9 原本留给 6.4，6.3 先用到；`SceneAssetOpClients` 还没有改成它的薄包装，见实现记录）；`battle_control.proto` 追加 `DevGatherRequest / DevGatherResponse` |
| `xm-player-store` | `player_state.proto` 加 `BattleLedgerState battle_ledger = 9`；不改表结构 |
| `xm-audit` | `transaction_log.proto` 加 `TX_ITEM_AWARD = 20`（同基线值）、`TX_BATTLE_REWARD = 1005`（Java 独有，按 1001+ 规则） |
| `xm-scene` | 新包 `com.game.scene.battle`：`PlayerBattle` / `BattleFreeze`（运行态）、`BattleLedger`（持久化）、`PlayerBattleService`（备战 / 取消 / 确认 / 恢复 / reaper / 解冻入口，**以及结算到达的分流 `deliver`、应用后的收尾 `applyAndFinish` 与销账 `writeOff` / `ack`**）、`BattleSnapshots`（纯组装）、`BattleSettlementService`（**只做**一份结算在一名玩家身上的应用，184 由它直接发）、`BattleLocks`（异步 Redis 端口；生产实现是 `BattleLocks.redis(BattleRedis)` 返回的委托，没有单独的类）、`SceneBattleTables`（启动时建一次的配表视图：指纹、可施放技能、`battleUsable`、职业行）、`SceneBattleProvider`；`com.game.scene.world` 里的 `BattlePolicy` 与 `BattleHooks`（世界回调战斗的三个钩子：进场、落盘、原地解冻）。与原稿的类划分不同：没有 `SettlementWriteOff` / `RedisBattleLocks` / `BattleItems`，分流与销账收在 `PlayerBattleService`——四条应用路径只有一个收口，过期读在那里统一兜住（§7.10 第 9 步）。`SceneAssetRpcServer` 改名 `com.game.scene.rpc.SceneRpcServer`（一个模块导出两个服务；配置键、Dubbo 应用名、回写执行器名 `scene-asset-reply` 不改，避免运维配置与指标序列变动）；新依赖 `xm-battle-engine`；`PlayerData` / `ScenePlayer` 加 `name`（`PlayerRow` 已有 `name`，`PlayerMapper.java:36` 是 `SELECT *`，只需改 `StoragePlayerRepository.toData`，`:659-664`）；`BagService.removeClamped` / `Bag.drainClamped`（Java 背包现在没有任何扣除方法，`Bag.java:138`、`:299`）；`PetService.buildBattleSnapshot` / `applyBattleSettlement`；`AssetAudit.Reason.BATTLE_REWARD` / `ITEM_AWARD`；`SceneMessageIds` 加 144 / 150 |
| `xm-battle`（6.2） | `com.game.battle.outbox`：`SettlementOutbox`（直接实现 `SettlementSink`）、`SettlementRetryRules`（纯函数，移植 `outbox.h:28-90`）、`ActivityResultOutbox`（直接实现 `ActivityResultSink`）、`ActivityRetryRules`（`activity.h:83-108`）、`OutboxMetrics`；`com.game.battle.port.scene`：`SceneTransport`（装配：`battle-outbox` 线程、直连客户端、定位器、两个发件箱与确认传输，停机排空）、`SceneTransportProperties`、`DubboSceneBattleEvents`、`SceneClientSweeper`（直连客户端缓存的清扫）；`BattleResultSink` 加 `Channel{PLAIN, ACTIVITY}`；`admin.DevGather` / `DevGatherController` / `DevGatherConfiguration`；`RoomOrigin.DEV_GATHER`。原稿列的 `OutboxSettlementSink` / `OutboxActivityResultSink` 没有单独成类 |
| `xm-gate` | `MessageRoute` 加 `directOnly`、`MessageRoutes.DIRECT_ONLY_SERVICES`；`GateMetrics.RequestResult.BATTLE_REJECTED`；战斗上行当场拒绝（§7.19） |
| `xm-team` | `TeamDisplay.in_battle` 由 `BattleLockReader.existsAll` 批量算出（`TeamConfiguration` 新增 `BattleLockReader` bean；收掉 team-spec D10） |
| `xm-data`（7.2b） | 回档写之前查战斗锁（`BattleLockGate`，经 `BattleLockReader.existsAll`；见 data-ops-spec，本稿不展开） |
| `xm-robot` | `battle-settle` 场景；`BattleAdminClient`（6.2）加 gather / cancel-prepare；故障变体 `BattleCrashScenario` + `tools/local/battle-crash-window.sh`（§13.8） |

新增第三方依赖：无。不用 ECS：领域对象 + `XxxService`。

### 7.2 Redis 键与 Lua（`BattleRedis`；全部经 `RedisKeys`，前缀 `xm:`）

**键**：

| 键 | 类型 / 字段 | 写者 | 读者 | TTL | 基线对应 |
|---|---|---|---|---|---|
| `xm:battle:{<pid>}:lock` | Hash：`b` battle_id（无符号十进制）、`n` battle_node_id、`s` `P` / `F`、`d` deadline_ms、`p` prepare_deadline_ms | 只有 scene | scene；match（EXISTS，含活动开局预检）；team（批量 EXISTS）；读 `b` 的接口留给 4.6（基线帮会没有生产读者，§1.2） | `max(0, 有效期限 − now)/1000 + 60`；结算应用后至少 180 s | `battle:lock` + `battle:ctx`（`pb.cpp:81-97`） |
| `xm:battle:{<pid>}:settlement` | Hash：字段名 = battle_id（无符号十进制），值 = 契约 `BattleSettlementEvent` 字节 | battle（落库）；scene（只删字段） | battle 探测（HEXISTS）；scene 进场恢复 / rescue | 7 天，每次写刷新整键 | `pending` + `pending:id`（`outbox.h:94-99`），单槽改每局一字段（D13） |
| `xm:battle:{<pid>}:settled:<battle_id>` | String `"1"`：这一局已销账的墓碑（`RedisKeys.battleSettled`，Java 独有，D29） | scene（`ACK`，无条件写）；battle（`ACK_IF_SUPERSEDED` 删记录的分支） | battle 的落库脚本（见到就不写） | 600 s（`SETTLED_TOMBSTONE_TTL_SEC`，≥ `OUTBOX_MAX_AGE`） | 无 |
| `xm:battle:activity-result:<battle_id>` | String：契约 `BattleResultEvent` 字节 | battle | guild（4.6，消费后 DEL）；battle 探测（EXISTS） | 7 天 | `battle:activity_result:{id}`（`activity.h:30-41`） |

- `{<pid>}` hash tag 的写法同 `RedisKeys.friendList`（`RedisKeys.java:74-80`）：锁、待结算记录、墓碑三键同槽，销账、取代、落库三段 Lua 跨键执行，将来上 Cluster 不用改。
  脚本只碰经 `KEYS` 传入的键，**不在 Lua 里用 ARGV 拼键**（`BattleRedisConstantsTest` 钉住）。
- 这组键名**取代** node-spec §7.13 预留的 `xm:battle:settlement:pending:{pid}` / `…:pending-id:{pid}` / `xm:battle:lock:{pid}` / `xm:battle:ctx:{pid}`（没有 hash tag，销账脚本无法同槽）；6.2 不写这些键，改名只影响文档。
- 所有比较用 `Long.toUnsignedString(battle_id)`；脚本用 `ByteArrayCodec`；读者一律经 `BattleLockReader`（锁是 Hash，Go 侧的 MGET 写法不移植：对 Hash 键 `MGET` 回 nil、**不报错**，
  照搬基线 `presence.go:92-97`「值非空即在战斗」的写法会让 `in_battle` 静默恒为 false——报 `WRONGTYPE` 的是 `GET`。真 Redis 用例钉住）。
- **待结算字段名只认规范的无符号十进制**（1–20 位数字、无前导零、不溢出、不是 0，`SettlementField.battleId()`）：`"05"` / `"+5"` 这类写法能被宽松解析成 5，
  但销账按规范串 `"5"` HDEL 删不到它，当合法记录应用就会每次进场都再发一次奖，所以按坏字段处理。`ENTER_READ` 读出的字段名**保留原始字节**（`SettlementField.rawName()`），
  删坏字段按原字节删，不经 String 往返（非法 UTF-8 的名字解码再编码会变样、删不到）。

**Lua**（语义逐条对齐基线）：

| 名字 | 用方 | 键 / 参数 | 语义 | 基线 |
|---|---|---|---|---|
| `PREPARE_LOCK` | scene | lock；X, n, d, p, ttl | **键里没有 `b`**（键不存在，或键在而缺 `b` 的残缺锁——按空键处理，键上其它字段保留）→ `HSET b=X n s=P d p` + `EXPIRE ttl` → 返回 `"0"`；`b == X`、`s == P` → 视为**重放**：重写 n d p + `EXPIRE ttl` → `"0"`；其余（别的局，或同局已 F）→ 返回现 `b`（拒绝，不改字段与 TTL）。回复为空以异常完成 | 无条件两条 SET（`pb.cpp:1187-1217`），D3 |
| `CONFIRM` | scene | lock；X, d, ttl | `b ≠ X` → nil；否则 `HSET s=F`，`d ≠ "0"` 才写 `d`，`ttl ≠ "0"` 才 `EXPIRE` → 返回 `HGETALL`（未命中时 Redisson 给 null，封装对 null 与空表都按未命中） | `kConfirmLockIfMatchScript` + `kGetCtxIfLockMatchScript`（`:179-187`）；离线确认 / 在线升级 / 迟到确认重建共用一段 |
| `CANCEL_OFFLINE` | scene（两个入口共用：离线取消 `cancelOffline`；在线路径的**只删备战锁** `deletePreparingIfMatch`，D31） | lock；X | `b ≠ X` → 0；`s == F` → 2（拒绝，锁的字段与 TTL 不动）；否则 DEL → 1 | 基线读 ctx、C++ 解析、再条件删两次往返（`:1239-1280`） |
| `DELETE_IF_MATCH` | scene（**只给 reaper 判废用**，D31） | lock；X | `b == X` → DEL → 1，否则 0；不看 `s`（F 也删），不写墓碑 | `kDeleteLockIfMatchScript`（`:174-176`） |
| `TOUCH` | scene | lock；X, ttl, s, d, p | `b ≠ X` → 0；锁上 `s == F` 而入参 `s == P` → **什么都不改**（不降级、不改 d / p、不动 TTL）→ 2（D30）；否则 HSET s d p + EXPIRE → 1。入参是 F 时永远不会得到 2 | 重建复核 `ConfirmRebuiltFreeze`（`:540-579`） |
| `HOLD` | scene | lock；X, hold | `b ≠ X` → 0；TTL 在 [0, hold) 就 `EXPIRE hold`（只延不缩；TTL = -1 时不加 TTL）→ 1 | `kHoldLockIfMatchScript`（`:220-227`） |
| `ACK` | scene | lock, settlement, 墓碑；X, 墓碑 TTL | `HDEL settlement X` 删到 → r = 1；`HGET lock b == X` → DEL lock，r += 2；**无论记录与锁在不在都** `SET 墓碑 EX`（not_durable 的落库可能还在路上）；返回 r | `kAckSettlementScript`（`:193-205`）：销账与放锁同一段；墓碑是 Java 独有（D29） |
| `ENTER_READ` | scene | lock, settlement | 返回扁平数组 `{TTL lock, 锁字段数 × 2, 锁的 k v …, 记录的 k v …}`（同一时刻的快照；记录的字段名是原始字节） | 基线 `GET pending` 后再串 `kGetLockAndCtxScript`（`:2018-2063`、`:189-191`） |
| `READ_IF_OURS` | scene（rescue，D21） | settlement；X | `HGET settlement X` | — |
| `READ_LOCK_BATTLE` | scene（结算到达且没有冻结，§7.10 第 7 步）；`BattleLockReader.battleId` | lock | `HGET lock b`（没有锁或 `b` 不是无符号十进制 → 0） | `GET lock`（`pb.cpp:1860-1917`） |
| `DELETE_SETTLEMENT_FIELD` | scene（删坏字段，§7.8 第 2 步） | settlement；字段名原始字节 | `HDEL` → 删到的个数；不写墓碑（坏字段不对应一场可销账的局） | 基线无条件 DEL 两键（`pb.cpp:2047-2053`），D13 |
| `STORE_SETTLEMENT` | battle | settlement, 墓碑；X, bytes, ttl | 墓碑在（这一局已销账）→ **不写、不刷新 TTL**，返回 -1（`STORE_ALREADY_SETTLED`）；否则 `HSET X bytes` + `EXPIRE ttl` → 返回 `HLEN`（≥ 1） | `kSetPendingSettlementScript`（`outbox.h:101-105`）；墓碑判断是 D29 |
| `PROBE_SETTLEMENT` | battle（每轮探测） | settlement；X | `HEXISTS`（0 / 1）；回复为空以异常完成，不映射成「不在」 | `GET pending:id`（`room.cpp:1776-1785`） |
| `ACK_IF_SUPERSEDED` | battle（D17） | lock, settlement, 墓碑；X, 墓碑 TTL | `HEXISTS settlement X == 0` → 3（已销账）；锁不在 → 0；`b == X` → 1（仍是本局）；否则 `HDEL X` + `SET 墓碑 EX` → 2（已被取代，这也是一次销账） | 离线分支的「锁被别的局持有 → 销账」（`pb.cpp:486-494`） |
| `STORE_ACTIVITY_RESULT` / `PROBE_ACTIVITY_RESULT` | battle（§7.17） | activity-result；bytes, ttl / — | `SET EX` → 1 / `EXISTS` | `activity.h:30-41`；`room.cpp:1993-2004` |

- **探测、删坏字段、读锁的 `b`、活动结果的 SET / EXISTS 也包成了 Lua**（原稿写「不需要 Lua」）：为的是全部走 `ByteArrayCodec` 与同一条 `eval` 路径——值与字段名是原样字节，
  「回复为空 → 异常完成」只写一处。
- **回整数的脚本得到空回复（协议异常）一律以 `IllegalStateException` 异常完成**，`PREPARE_LOCK` 的空回复同样（不再映射成会被当成「被占」的空串）：没问到结论不能当成任何一种结论（D15 的口径）。
- **全部脚本以 `RScript.Mode.READ_WRITE` 执行，即读主库**（含只读的 `ENTER_READ` / `READ_IF_OURS` / `READ_LOCK_BATTLE` / 两段探测）：这几段读的结果直接驱动丢弃与判废
  （读到「没有锁」→ 结算 DISCARDED 并销账；读到「没有记录」→ FIGHTING 判废删锁），主从 / 集群部署下 Redisson 缺省把只读脚本发到从库，复制延迟下的「没有」会被当成事实。
  `BattleLockReader.exists / existsAll` 是另一回事：它是**咨询性的读**（挡排队 / 跟随 / 队伍视图 / 回档闸），走 Redisson 的普通读路由，部署约束见 §10.5。

**重放语义（必须逐段成立）**：Java 的 Redisson 配置 `retryAttempts = 1`，超时的 EVAL 会被**原样重发**，第一次可能已经执行（`RedisProperties.java:14`；`match-spec.md:997` 同一前提）。
单条命令最坏耗时 = (1 + 1) × 2000 + 200 = **4.2 s**（`RedisProperties.worstCaseCommandMillis()`），不是 Redisson 自带的 3 s。逐段核对：
`CONFIRM` 幂等（s = F、同一个 d、同一个 TTL）；`TOUCH` 幂等（同一组 s d p 与 TTL，回 1），`TOUCH(P)` 落在 `CONFIRM` 之后回 2、什么都不改；`HOLD` 只延不缩，
重放最多把 TTL 再顶回 hold；`DELETE_IF_MATCH` 与 `DELETE_SETTLEMENT_FIELD` 状态幂等（第一次回 1、重放回 0；删坏字段的调用方不看返回值）；
`CANCEL_OFFLINE` / 只删备战锁重放回 0（离线取消计 `offline_absent`，无害），删除被排到 `CONFIRM` 之后回 2、锁不动；`ACK` 重放回 0（回调照样 `forget`，
只是丢了位 2 的「补一次跟随」，由下一次跟随触发兜住），墓碑被重写、TTL 刷新。三段删锁脚本的返回 1 除了计数（离线取消的 `offline_deleted`）只有一处用途——删锁结局回来时凭它决定要不要给现任实例补跟随（§7.4）：
重放回 0 时同样只是丢这一次补跟随；`ACK_IF_SUPERSEDED` 第一次回 2 时重放回 3（按已销账摘除，正确）；
`STORE_SETTLEMENT` 对自己幂等（同值 HSET、EXPIRE、HLEN 不变）；`STORE_ACTIVITY_RESULT` 幂等，但「落库 → 消费方 DEL → 重放」会把副本重新造出来，
消费方（4.6）必须自己按 battle_id 幂等；`ENTER_READ` / `READ_IF_OURS` / `READ_LOCK_BATTLE` / 两段探测只读。
**`PREPARE_LOCK` 原稿的「同局也拒」在重放下是缺陷**：第一次已写入、回复超时、重发看到 `b == X` 被当成「被占」→ scene 解冻并回 1006，match 认定备战失败、不发取消，
锁却按备战 TTL（最长 96 + 60 s）留着，玩家这段时间排不了队。所以同局且仍是 `P` 的命中按重放成功处理。这不放宽语义：同一实例上的同局重复备战已被内存的「已有冻结 → 1006」挡住，
match 也从不对同一 battle_id 重发备战（`gather.go:236-255` 失败即弃局）；同局已是 `F` 仍拒绝，不把在打的局改回备战期 TTL。

**与销账交错**——某段脚本的重放 / 晚到落在同一局的销账（`ACK`）之后：会在键不存在时把键造出来的只有 `PREPARE_LOCK` 与 `STORE_SETTLEMENT` 两段，
其余一律落空、**不会把锁或记录造回来**：`CONFIRM` 回 nil（调用方按未命中，不重建冻结）；`CANCEL_OFFLINE` / 只删备战锁 / `DELETE_IF_MATCH` / `TOUCH` / `HOLD` 回 0
（`TOUCH` 的调用方据此撤销按旧快照重建的冻结）；`ACK` 回 0；`ACK_IF_SUPERSEDED` 回 3。`STORE_SETTLEMENT` 由墓碑挡下（回 -1，记录不复活；not_durable 的那次落库同样被挡）。
`PREPARE_LOCK` 的重放不可能晚于同一局的销账：销账在结算之后，而一次备战调用最坏 4.2 s、此时房间还没建。销账只动本局的字段与 `b == X` 的锁，
下一局的锁、记录、墓碑都不受影响。墓碑不保护的两种情形见 §10.5（墓碑过期之后才落地的落库；reaper 判废与删坏字段不写墓碑）。

**次序没有保证的不只是重放**：两条各自发出的脚本，Redis 上谁先执行、两个回调谁先回到逻辑线程都没有保证（§1.8）。所以一份读到的快照（恢复读、读锁、rescue 读、
迟到确认的回复）可能早于本实例已经完成的销账——scene 侧由恢复代际与「本实例已销账集合」兜住（§7.4、D32），不靠脚本。

**跨进程常量**（`BattleRedis`，代码常量，不开放配置，同 node-spec §8.3 的做法）：

| 常量 | 值 | 联动 |
|---|---|---|
| `LOCK_EXTRA_TTL_SEC` | 60 | node-spec §10.4：确认补发窗口 180 ≥ 96 + 60；6.2 的约束单测 `ConfirmWindowConstraintTest` 已改为引用本常量（并多钉一条：最后一次实际补发 170 s ≥ 156 s，§10.4） |
| `LOCK_HOLD_AFTER_APPLY_SEC` | 180 | 单测断言 ≥ `SETTLEMENT_RETRY_INTERVAL × SETTLEMENT_RETRY_MAX + 60` |
| `SETTLEMENT_TTL_SEC` | 604800 | 两端同值 |
| `SETTLEMENT_RETRY_INTERVAL` / `_MAX` | 10 s / 12 | `room.h:172-176` |
| `ACTIVITY_RESULT_TTL_SEC` / `ACTIVITY_RETRY_INTERVAL` / `_MAX` | 7 天 / 10 s / 30 | `activity.h:30-36` |
| `REAPER_INTERVAL` | 30 s | `pb.h:101-102`；是缺省值，`xm.scene.battle.reaper-interval`（§8）只许调小（启动校验 ≤ 30 s），供本机切片 |
| `FIGHTING_EXPIRY_GRACE` | 10 s | D21；单测钉住 `GRACE + REAPER_INTERVAL < LOCK_EXTRA_TTL_SEC`（10 + 30 < 60）：第一次 rescue 最晚在期限 + 40 s，此时锁一定还在 |
| `OUTBOX_MAX_AGE` | 10 min | Java 独有：两个发件箱的条目登记超过它仍没有结论（探测 / 定位一直出错、不计次）→ 按用尽摘除。D15 让出错不计次，没有这条上界，持久性的单键错误会让条目永远留在内存里 |
| `SETTLED_TOMBSTONE_TTL_SEC` | 600 | Java 独有（D29）：已销账墓碑的寿命。单测钉住 ≥ `OUTBOX_MAX_AGE`——发件箱还可能为这一局发出落库 / 重发的整个窗口里，销账之后的落库都被挡住 |
| `SETTLEMENT_FIELDS_WARN` | 16 | Java 独有：`STORE_SETTLEMENT` 返回的 `HLEN` 超过就 ERROR 并计 `fields_overflow`（正常为 1） |
| `LEDGER_CAPACITY` | 64 | `ledger.h:32`；scene 的「本实例已销账集合」容量 `PlayerBattle.WRITTEN_OFF_CAPACITY` 取同值（§7.4） |
| `FALLBACK_SPEED` | 120 | `pb.cpp:102`（scene 私有，放 `BattleSnapshots`） |
| `MAX_DEADLINE_AHEAD_MS` | 7 天（= `SETTLEMENT_TTL_SEC × 1000`） | Java 独有（D36；scene 私有，放 `PlayerBattleService`）：备战请求的期限离现在不得超过它，§7.5 第 1.1 步 |

**返回码常量**（`BattleRedis`，调用方不写字面值）：`STORE_ALREADY_SETTLED = -1`；`TOUCH_MISS / TOUCH_HIT / TOUCH_KEPT_FIGHTING = 0 / 1 / 2`；
`PREPARING_DELETE_MISS / _DONE / _FIGHTING = 0 / 1 / 2`（`cancelOffline` 与 `deletePreparingIfMatch` 共用）。锁的字段名与阶段取值也是常量（`FIELD_*`、`STATE_PREPARING / STATE_FIGHTING`）。

### 7.3 `SceneBattleService`（xm-api）与提供方

```java
package com.game.api;
/** scene 节点的回合制战斗入口（node-spec Q13）。每个 scene 节点在 xm.scene.asset-rpc-port 上与 SceneAssetOpService 一并导出，
 *  group DubboGroups.SCENE_BATTLE，register = false；调用方按 Redis 节点目录直连，引用 retries = 0。
 *  future 异常完成 = 传输失败（结局未知）：prepareBattle 时调用方按「可能已冻结」补发一次 cancel。 */
public interface SceneBattleService {
    CompletableFuture<SceneBattleReply> prepareBattle(SceneBattleCall call);       // body = PrepareBattleRequest；reply.body = PrepareBattleResponse
    CompletableFuture<SceneBattleReply> cancelBattlePrepare(SceneBattleCall call); // body = CancelBattlePrepareRequest
    CompletableFuture<SceneBattleReply> confirmBattle(SceneBattleCall call);       // body = BattleConfirmedEvent
    CompletableFuture<SceneBattleReply> applySettlement(SceneBattleCall call);     // body = BattleSettlementEvent（与 Redis 记录逐字节相同）
}
```

```proto
// xm-api/src/main/proto/xm/api/scene_battle.proto（Java 自有；xm-api 的 proto 不 import 依赖里的 proto，xm-api/pom.xml:70，契约消息按字节嵌入）
message SceneBattleCall {
  string target_instance_id = 1;  // 必填：调用方从目录读到的 scene 实例；与本进程不符 → NOT_HERE（等价基线 Kafka target_instance_id 过滤）
  uint64 player_id = 2;           // 冗余，Dubbo 线程上只用于日志
  bytes body = 3;                 // 契约消息字节
  uint32 attempt = 4;             // 结算：0 = 首投；只进日志
}
enum SceneBattleStatus {
  SCENE_BATTLE_STATUS_UNSPECIFIED = 0;  // 读方按传输失败处理（字段缺失不能被读成「已处理」）
  SCENE_BATTLE_HANDLED = 1;             // 已在逻辑线程处理；备战的业务结论在 body 的 error_message 里
  SCENE_BATTLE_NOT_HERE = 2;            // 实例不符，或（结算）玩家不在本节点：调用方下一轮重新解析
  SCENE_BATTLE_DEFERRED = 3;            // （结算）玩家在本节点但此刻不可应用（交出冻结、恢复中、金币被拒、读锁失败、账本损坏），零副作用
  SCENE_BATTLE_OVERLOADED = 4;          // 在途超限：保证没进逻辑线程、零副作用
}
enum SettlementDisposition {
  SETTLEMENT_DISPOSITION_UNSPECIFIED = 0;
  SETTLEMENT_APPLIED = 1;          // 本次应用（未必已落盘）
  SETTLEMENT_ALREADY_APPLIED = 2;  // 账本命中
  SETTLEMENT_DISCARDED = 3;        // 非法 / 不匹配 / 已作废（已按规则销账或不销账）
}
message SceneBattleReply { SceneBattleStatus status = 1; bytes body = 2; SettlementDisposition settlement = 3; }
```

- **提供方** `SceneBattleProvider`（照 `SceneAssetOpProvider.java:87-128`）：Dubbo 线程依次做在途名额（`xm.scene.battle-rpc-max-inflight`，缺省 256，与资产通道各自独立；
  超出回 OVERLOADED）→ 实例核对（不符直接 NOT_HERE，不进逻辑线程）→ 解析 body（失败：备战回 HANDLED + `{1005}`，结算回 HANDLED + DISCARDED，其余 HANDLED，都打 ERROR）→
  `logic.execute`（被拒 = 逻辑线程已停 → future 异常完成）。应答在 `scene-asset-reply` 回写执行器上完成；需要异步 Redis 的分支等结果回来再完成，最坏约 4.2 s
  （一条脚本的 Redisson 最坏耗时，§7.2）。**battle → scene 的确认 / 结算与 dev gather** 的调用方超时必须大于它（battle 侧 `xm.battle.scene-rpc-timeout` 缺省 5 s，
  启动校验；dev gather 单次 scene 调用 5 s），否则 scene 已给出确定结论而调用方先按「结局未知」处理。
  **match 的备战 / 取消调用不在此列（裁决，2026-10-06）**：match 的超时保持 3 s（match-spec 的数值表与跨进程不等式整表不动，42 / 48 / 66 / 96 与基线逐字相同），
  超时按结局不明处理并补发取消；scene 在写锁在途时收到取消会延后到写锁完成再删锁（§7.6，`PrepareBattleTest` 钉住），兜底还有 reaper 与锁 TTL。
  `SceneBattleService` 接口注释里「调用方超时必须大于它」那一句随 6.4 改，不在本批。
  - **应答的时机**：取消等删锁脚本的结局、按锁结算等读锁回来才完成，期间在途名额占着，`rpc{method,result}` 在结局出来时才计。
  - **应答 future 的结局**：备战与结算按锁分支，在逻辑线程回调抛出意外异常、或逻辑线程拒绝投递（停服）时**异常完成**（= 结局未知，调用方按传输失败处理；
    不异常完成的话提供方的在途名额永不归还）。备战回调抛异常时还会摘掉仍挂着的这个冻结，并尽力发一次只删备战锁（`abandonPrepare`）。
    取消（离线 / 在线）与解冻的 future 在同样情形下**正常完成**——效果在应答前已经生效，调用方不按传输失败重试。异常完成时交给 Dubbo 的是原始异常，不带 `CompletionException` 包装。
  - **信封与结算体的玩家不符**：结算按信封 `player_id` 核对，不符回 HANDLED + DISCARDED，计 `settlements{online, discarded_invalid}`，不应用、不销账，不影响随后信封相符的重投。
- **导出**：`SceneRpcServer` 一个 `IsolatedDubboModule`、一个 `ProtocolConfig`、两个 `ServiceConfig`；启停顺序不变（导出成功后才写目录；停服先摘目录、写回、再撤导出，`SceneAssetRpcServer.java:50-73`）。
- **鉴权**：Dubbo 调用方 MAC（`XM_DUBBO_SECRET`）自动生效；结算 body 不另加 HMAC（Q13）。缺 MAC 的 Triple 调用在调用方以 `StatusRpcException`、状态码 `UNAUTHENTICATED` 异常完成，
  到不了提供方，rpc 指标不动（过滤器给的中文原因经 Triple 传到调用方只剩问号，调用方只能按状态码认，见实现记录的遗留）。
- **基线对照**：gRPC 线程 `runInLoop` 后 `future.get()` 同步等（`sns.cpp:308-340`）；Kafka 事件在 loop 上分发（`beh.cpp:18-34`）。Java 全程异步（D1）。

### 7.4 scene 侧状态模型（只在场景逻辑线程读写）

```java
// com.game.scene.battle.PlayerBattle —— 挂在 ScenePlayer 上，不持久化，随实例生灭（基线 InBattleComp 同样不落库，battle_comp.proto:8）
final class PlayerBattle {
    BattleFreeze freeze;        // null = 没有在途战斗
    Recovery recovery;          // PENDING（进场恢复读在途；新实例的初值，fail-closed）→ READY；RETRY = 读失败、处理快照时出错、或有延后的待结算，由 reaper 重试
    boolean applying;           // 结算应用中（不重入断言）
    long recoveryGeneration;    // 最近一次发出的进场恢复读是第几代；回调只认最新一代（D32）
    Set<Long> writtenOff;       // 本实例已发出过销账脚本的 battle_id（插入序，容量 64 先进先出，D32）
}
final class BattleFreeze {      // 可变：只在逻辑线程上改
    final long battleId; final int battleNodeId;
    Phase phase;                // PREPARING / FIGHTING
    long deadlineMs, prepareDeadlineMs;
    boolean preparedHere;       // 「这一局的 144 不必再推」：本实例上 PrepareBattle 建的初值为真；按锁重建的初值为假，第一次推 144 之后置真（顶替 BattlePrepareSessionComp，D7）；
                                //  同 epoch 沿用旧实例时会话没变照抄、会话变了置假（§7.8 第 0 步）
    boolean lockPending;        // 备战写锁在途
    boolean cancelRequested;    // 写锁在途时收到取消，延后到写锁完成后再删锁
    boolean lockExtended;       // FIGHTING 的条件续期已确认成功（D9）
    boolean rescuing;           // reaper 的 rescue 读在途（防重复处理）
}
// com.game.scene.battle.BattleLedger —— 持久化（§7.12）
```

- `ScenePlayer.inBattle()` = `battle.freeze != null`，唯一谓词（基线 `IsInBattle`）。`frozen()` 的语义不改，仍只表示 5.2 的交出冻结（`ScenePlayer.java:414-416`）。
- **改冻结状态的唯一入口** `PlayerBattleService.clearFreeze(player, LockAction)`：摘掉冻结，再按 `LockAction` 处置锁——
  - `DELETE_PREPARING`（**只删备战锁**：`b == X` 且 `s ≠ F` 才删，D31）：在线取消、写锁在途的延后取消用它；删锁的结局回到逻辑线程后补一次组队跟随
    （`TeamFollow.onBattleFreezeCleared`，补给谁见下）。删除回 2（这条删除被 Redisson 排到了确认之后，锁已标 F）时锁保留，只打 INFO；
  - `DELETE_IF_MATCH`（不看 `s`）：**reaper 判废专用**（rescue 没读到 / DEFERRED / 期限 + 60 s 放弃）；删完同样补一次跟随；
  - `KEEP_LOCK`：立即调（此时锁还在，跟随链读到锁会放弃，与基线 `pb.cpp:2149` 相同，锁真正放掉时由销账回调补）。reaper 备战到期、结算应用后的解冻、备战被占 / 写锁出错后的解冻、
    复核落空的撤销用它。

  返回的 future 在删锁结局回来后完成；删锁回调出错、逻辑线程已停也照常完成——解冻在返回前已经生效。
- **删锁结局回来之后补给谁**（前两种 `LockAction`；第二轮修正及其评审修正），按次序三种情形：
  1. 这次只删备战锁顺带摘掉了现任实例上按这把锁重建出来的备战冻结（`dropPreparingRebuiltFromLock`，§7.5 第 4 步末两条）→ 摘除时已经补过，不再补；
  2. 解冻的那个实例还在、且没有战斗冻结 → 补给它，**不看删没删到**（这是它解冻之后的第一次检查；锁还留着的话跟随链读到锁自己放弃）；
  3. 实例已换（离场，或同 epoch 重进换成了新实例）→ **锁确实是这一次删掉的（返回 1）**才补给现任实例，且它没有战斗冻结（`followCurrentIfLockDeleted`）；
     返回 0、返回 2、出错、玩家已不在本节点都不补。来由与销账回调里「位 2 而实例已换」那一支相同（§7.12）：新实例进场那次跟随检查读到的还是这把没删掉的锁、已经放弃，
     它的恢复读又排在删除之后（读不到锁、不重建冻结），删完之后就再没有别的触发点。

  不跟着一次解冻的删锁（备战失败 / 过期后的尽力删锁、离线取消；§7.5 第 4 步、§7.6）没有「解冻的那个实例」可言：先看第 1 种情形，否则锁确实是这一次删掉的（返回 1）
  就补给现任实例——可以就是原来那个实例（例如写锁出错后的尽力删锁：保留锁的解冻当场补的那次检查读到锁而放弃，删到了再补一次）。
  三段删锁脚本都以返回 1 表示「这一次把锁删掉了」（`PlayerBattleService.LOCK_DELETED = BattleRedis.PREPARING_DELETE_DONE`）。
- 所有异步回调先核对「`world.playerById(pid) == player`，且冻结 / 换图对象没变」再动状态（比基线 `GuidForLog` 更强），与 5.2 的 `player.switching() == sw` 同一纪律。
  进场重建的 `TOUCH` 复核回调同样：回来时实例已换、玩家已离场、或挂着的冻结已不是这次重建的那个对象 → 整个丢弃（不推 144、不撤销现在挂着的冻结、不补跟随、`rebuilds{login,*}` 一个取值都不计）。
- **过期读的两道防线**（D32；Redis 脚本各自异步，执行次序与回调次序都没有保证，§1.8、§7.2）：
  - **恢复代际**：每发一次进场恢复读（进场、原地解冻、reaper 重试）就开新一代，回调只认最新一代；更旧的一代不论成功失败一律丢弃——不计 `recovery` 指标、不改 `recovery` 状态。
    同一实例上不会有两份快照各跑一遍第 2–4 步。
  - **本实例已销账集合**：发出 `ACK` 的那一刻就登记（不等结局，脚本失败也不撤），容量 64、先进先出，不持久化、随实例生灭，同 epoch 沿用旧实例时一并沿用。
    销账删记录、放锁之后，更早拍下的快照（恢复读、读锁、rescue 读、迟到确认的回复）里还带着这一局，而账本此时可能已经 forget——只靠账本去重就会再发一次奖。
    凡是在集合里的局：应用路径一律按 ALREADY_APPLIED 收尾、不再应用（§7.10 第 9 步）；按锁重建与迟到确认重建都不重建、只再推一次销账（§7.7、§7.8 第 3 步）。
    推演依据：`forget` 只在 `ACK` 成功回调里做，而登记在发出 `ACK` 时就做了，所以「账本已 forget」必然意味着「本实例已登记」；过期读都绑定实例，不会跨实例。
- **逻辑线程回调不许把状态卡住**：回调里的意外异常、或逻辑线程拒绝投递，都不得让进场恢复停在 PENDING（置 RETRY、计 `recovery{error}`，由 reaper 重跑），
  也不得让应答 future 永不完成（§7.3）。
- **reaper 不建索引**：每 30 s 遍历一遍在场玩家（先收集再处理）。一个节点几千人、30 s 一次，开销可忽略。

### 7.5 备战 `prepareBattle`

1. 判定（码同基线，前一步拒绝后面不执行、零痕迹）：
   1. player_id / battle_id / deadline_ms 为 0 → 1005；`deadline_ms` 或 `prepare_deadline_ms` 比现在晚了超过 7 天（`MAX_DEADLINE_AHEAD_MS`，按无符号比较，
      ≥ 2^63 的垃圾值同样超界）→ 同样 1005（D36）。理由：`PREPARE_LOCK` 先 HSET 后 EXPIRE，期限是天文数字时 TTL 超出 Redis 的上限，EXPIRE 报错、脚本中止，
      已执行的 HSET 不回滚，留下一把**没有 TTL** 的锁。只设上界：已经过去的期限照旧放行（锁 TTL 取下限 60 s，冻结由下一轮 reaper 摘掉）；
   2. 玩家不在本节点（含仍在加载）→ 1004；
   3. 换图在途（`SceneWorld.switchInFlight`）→ 1006（FREEZING 对应基线 `PlayerFrozenComp`；RESOLVING 是 D4）。经世界的「在途」判定而不是直接看 `switchPhase`：
      过了期限的 RESOLVING 槽（选目标兜底超时 4 s + 1 s，结果回调丢了）在这里**当场作废并放行**，迟到的选目标结果按过期丢弃，不会在战斗冻结挂上之后再进交出；
   4. 已有冻结 → 1006；
   5. `recovery ≠ READY`、账本损坏、账本里有**未落盘**条目 → 1006（D4；已落盘未销账的不挡：锁要么还在、NX 自然拒绝，要么已过期、下一局覆盖不了已落盘的结果）；
   6. 气血 0 → 1006；
   7. 组快照（§7.11 的快照部分）：Java 实例加载即有属性、一定绑着会话，基线 1004 / 1011 两个组快照失败码只做防御（链路不在 → 1011、ERROR）。

   四种 1006 在指标上分得开：`prepares{switching / in_battle / not_ready / dead}`；第 5 步的三种原因同计 `not_ready`，账本损坏那一种另打一条带原因、player、battle_id 的 WARN；
   1011 计 `not_here`；期限超界与参数为 0 同计 `invalid`。
2. 挂上 `BattleFreeze{PREPARING, preparedHere = true, lockPending = true}`；此后各闸立刻生效，快照到应答之间状态不会再变。
3. `stopMotion()` 并置速度脏位（D5，同 `SceneWorld.freeze` 的做法，`SceneWorld.java:970-973`）。
4. `PREPARE_LOCK(X, n, d, p, ttl(prepare_deadline))`；结果回到逻辑线程：
   - **实例未换、冻结仍是这个对象、仍 PREPARING**：
     - `"0"` → `lockPending = false`；若 `cancelRequested` → `clearFreeze(DELETE_PREPARING)`、回 1006（计 `prepare{cancelled}`）；否则回快照与指纹（成功的应答**不带** `error_message`，
       dev gather / robot 按「没有 error_message 且带快照」判成功）；
     - 被占（别的局，或同局已 F）→ `clearFreeze(KEEP_LOCK)` → 1006（`lock_held`），锁不动；Redisson 重发造成的「同局 P」已在脚本里按成功处理（§7.2 重放语义）；
     - Redis 出错 / 超时（`xm.redis` 缺省 2 s × 重试 1 次，最坏 4.2 s）→ `clearFreeze(KEEP_LOCK)`，再尽力发一次**只删备战锁**（脚本可能已执行、只是回复丢了；D31）→ 1003（`redis_error`）。
       残余：若 Redis 侧排队的那次 `PREPARE_LOCK` 晚于这条删除才执行，锁会留到备战 TTL（§10.5）。
   - **实例已换或冻结已不是这个**（stale）：占到了、**或结局不明**（出错 / 超时）都尽力只删一次备战锁（match 认为这人没冻结成功，不会对他发取消；
     原稿只在「占到了」时删）；被别的局占着时不删别人的锁；回 1004（`stale`）。
   - **回调中途抛意外异常**：应答异常完成（结局未知，调用方按「可能已冻结」补发取消），并由 `abandonPrepare` 把状态收拾成「没有这个冻结、没有这一局的备战锁」——
     冻结若还挂着就摘掉，再尽力只删一次备战锁。逻辑线程拒绝投递（停服）时应答同样异常完成。
   - **删锁有了结局之后，顺带收拾按这把锁重建出来的备战冻结**（第二轮修正，`dropPreparingRebuiltFromLock`）：删锁与写锁都是异步的，这把锁从写上到删掉之间可能被一次进场恢复读到——
     最常见的是同 epoch 重进（重连）：旧实例的备战写锁 / 取消删锁还在途，新实例的恢复读看到了这把备战锁，据此重建出 PREPARING 冻结（复核也命中），随后那条删除才执行。
     对 match 而言这一局对这名玩家已经作废，不会再有确认、也不会再有取消，留下的就是一把**没有锁**的冻结，要等备战期限由 reaper 摘。
     所以本节点发出的每一次只删备战锁有了结局时（共五个适用点：上面三处尽力删锁——经 `deletePreparingLock`；§7.6 的在线取消与延后取消——`clearFreeze(DELETE_PREPARING)` 的回调；
     §7.6 的离线取消 `CANCEL_OFFLINE` 的回调），按 player_id 找**现任实例**（可以是同 epoch 重进的新实例，也可以是重跑了进场恢复的原实例），它若挂着同一局、仍是 PREPARING、
     且 `preparedHere == false`（不是它自己备战出来的）的冻结，一并摘掉：走与复核落空相同的撤销路径（`clearFreeze(KEEP_LOCK)` + `rebuilds{login, reverted}`，当场补一次组队跟随）。
     删除回 2（锁已被确认标 F、拒删）时不摘——确认已到，冻结等下一次补发确认升级；删到了（1）、结局不明（出错 / 超时）、没有可删的（0：锁已不在或已是别的局，
     含「首发已删、Redisson 重发回 0」）都摘。FIGHTING 的冻结、别的局的冻结、现任实例自己备战的冻结都不动。摘的时机是删锁的结局回到逻辑线程那一刻，不是发出删除时。
   - **没有冻结可摘、而锁确实是这一次删掉的（返回 1）**→ 给现任实例补一次组队跟随（它没有战斗冻结时；§7.4「删锁结局回来之后补给谁」）。
5. 指纹在启动时用 `BattleTableFingerprint.compute(tables)` 算一次（scene 没有表热更），快照和应答两处同值。

### 7.6 取消 `cancelBattlePrepare`

| 情形 | Java | 与基线 |
|---|---|---|
| player_id 或 battle_id 为 0 | 忽略，计 `mismatch` | — |
| 玩家不在本节点 | `CANCEL_OFFLINE`，1 / 2 / 0 分别计 `offline_deleted` / `offline_rejected_fighting` / `offline_absent`，脚本失败计 `offline_error`；应答等脚本结局。脚本在途期间这名玩家若已进场：按下面「删锁结局回来时」的规则收尾 | 同（一次往返，D2） |
| 在线、没有冻结 | 忽略（不碰锁），计 `idempotent` | 同（B2） |
| battle_id 不符 | 忽略，计 `mismatch` | 同 |
| FIGHTING | 拒绝，计 `rejected_fighting` | 同（B1） |
| PREPARING 且 `lockPending` | 记 `cancelRequested`，不立刻删锁，计 `deferred`；写锁完成后再 `clearFreeze(DELETE_PREPARING)` | Java 特有：Redisson 可能把删锁排到 SET 之前（§1.8） |
| PREPARING | `clearFreeze(DELETE_PREPARING)`：当场解冻、**只删备战锁**（D31），计 `cleared`；应答与补跟随都等删锁结局 | 基线的条件删锁不看阶段 |

- **只删备战锁的理由**：这条删除若被 Redisson 排到紧随其后的确认之后，锁已标 F、迟到确认可能已据此重建 FIGHTING 冻结——不能再删。删除回 2 时锁保留，只打 INFO。
- **删锁结局回来时**（在线、延后、离线三条取消路径同一套规则，§7.4「删锁结局回来之后补给谁」）：先看现任实例上有没有按这把锁重建出来的备战冻结，有就摘掉
  （§7.5 第 4 步末两条；摘除时已经补过跟随，不重复补）；没有可摘的 → 解冻的那个实例还在就补给它，实例已换（或离线取消本来就没有实例）时，锁确实是这一次删掉的（返回 1）
  才补给现任实例。同一次删锁可能触发两次跟随检查（复核落空撤销时一次，删锁结局回来再一次），第二次已同场景时什么都不做。
- **应答 future 一律正常完成**：离线分支与在线分支在脚本失败、回调抛异常、逻辑线程已停时都照常完成——取消只管尽力，效果在应答前已经生效，调用方不按传输失败重试。
- **残余**：删锁被 Redisson 重放（首发已删、重发回 0）且实例已换时，给现任实例的这次补跟随会丢，由下一次跟随事件兜住（与销账重放丢位 2 同一类，§7.2、§10.5）。

### 7.7 确认 `confirmBattle` 与迟到重建

| 情形 | Java |
|---|---|
| 参数非法 | ERROR，丢弃 |
| 玩家不在本节点 | `deadline == 0` 跳过（零 Redis，计 `offline_miss`）；否则一段 `CONFIRM`（基线两次往返），命中计 `offline_extended`、锁不是本局计 `offline_miss` |
| 玩家在 FREEZING（交出在途） | 只跑 `CONFIRM`（锁标 F 并续期），**不挂冻结**（D34），计 `frozen_extended`（锁不是本局计 `offline_miss`）。事件 `deadline_ms = 0` 时**不跳过**：发 `CONFIRM(X, 0, 0)`，只把锁标 F、不改 `d`、不续 TTL，TTL 由之后的恢复在复核时续上。交出提交 → 目标节点进场恢复时据锁重建 FIGHTING 并推 144；交出没提交、原地解冻 → **重跑完整的进场恢复**（第 1 步起，§7.8、§10.5），不是只补锁步骤 |
| 有冻结、battle_id 不符 | 忽略 |
| 已 FIGHTING（同局） | `lockExtended` 为假才再发一次 `CONFIRM`（D9）；否则零 Redis 的幂等 |
| PREPARING | → FIGHTING，deadline 取事件值（0 时沿用）；发 `CONFIRM`，成功置 `lockExtended`（失败计 `confirms{error}`，与已计的 `upgraded` 叠加，下一次补发走 `reextended`）；`!preparedHere` 就推 144 并置 `preparedHere = true`（等同基线推完摘会话记录） |
| 在线、没有冻结 | 账本已有 X → `writeOff(X)`，不重建（计 `ledger_hit`）；否则发 `CONFIRM`，回调（迟到确认，下面分情形） |

**迟到确认的回调**（`onLateConfirm`；发出时在线、没有冻结，一段 `CONFIRM` 已核对锁并标 F）：

| 回调时 | 动作 | 计数 |
|---|---|---|
| 脚本失败 | 什么都不做，等下一次补发 | `confirms{error}` + `rebuilds{late_confirm, error}` |
| `CONFIRM` 返回 nil（锁不在 / 易主；这一局已结算、已落盘、已销账之后才到的补发确认也落在这里——每条这样的补发多一次 Redis 往返） | 不重建、不推 144 | `confirms{mismatch}` + `rebuilds{late_confirm, miss}` |
| 实例已换，或已在 FREEZING | 丢弃 | `confirms{idempotent}` + `rebuilds{late_confirm, miss}` |
| 已有**同一局的 PREPARING 冻结**且不是写锁在途（确认在途期间，进场恢复按更早的快照重建出来的） | **按正常确认升级**（D33）：FIGHTING、期限取事件值（0 时取锁的 `d`）、按下一行的规则置 `lockExtended`、`!preparedHere` 就推 144。否则锁已是 F、内存却停在 PREPARING，要等下一次补发才追上（这一次可能已是最后一次） | `confirms{upgraded}`（这一支不计 `rebuilds{late_confirm,*}`） |
| 已有别的局的冻结，或同局已 FIGHTING / 写锁在途（例：两条迟到确认都在途，先回来的已重建） | 不换冻结对象、不改期限、不再推 144 | `confirms{idempotent}` + `rebuilds{late_confirm, miss}` |
| 账本已有 X，或 X 在本实例已销账集合里（这条回复早于那次销账，是过期结果） | 不重建，再推一次 `writeOff(X)` | `confirms{ledger_hit}` + `rebuilds{late_confirm, ledger_hit}` |
| 其余 | 挂 FIGHTING（`n` / `p` 取锁；战斗期限**事件值优先**，事件为 0 才用锁的 `d`；`preparedHere = false`）→ 推 144（`pushReconnectOnce`，推后 `preparedHere = true`） | `confirms{rebuilt}` + `rebuilds{late_confirm, rebuilt}` |

- **事件不带期限的迟到确认**（`deadline_ms == 0`，D33）：那段 `CONFIRM` 只标了 F、没续期（脚本对 0 不写 `d`、不 EXPIRE）。重建或升级之后 `lockExtended` **不得置真**，
  当场再发一条 `CONFIRM(X, 锁的 d, ttl(锁的 d))`，成功才置真；失败计 `confirms{error}`，下一次补发确认走 `reextended`。否则之后的补发全走零 Redis 的幂等分支，锁停在备战 TTL 提前过期。
  事件带期限时那段 `CONFIRM` 已写 `d`、续了 TTL，直接置 `lockExtended = true`。
- **`confirm()` 入口不查已销账集合**：已销账的局收到重复确认时仍会发一条 `CONFIRM`（回 nil，计 `mismatch`），无害，只是多一次往返。
- **账本损坏的玩家**（D24）收到迟到确认仍会按锁重建 FIGHTING（账本判断以「账本完好」为前置）；这名玩家的结算一律 DEFERRED，冻结要等 reaper 判废（§10.5）。
- **144 的规则**：全部推送路径（确认升级、迟到确认、进场重建的复核、沿用旧实例）都经 `pushReconnectOnce`，以 `preparedHere` 作「已推过 / 不必推」标记，
  同一个冻结对象上**恰好一次**；PREPARING 永不推（B5）。
- **迟到确认不做第二次复核**（D10）：核对锁与标 F 在同一段 Lua 里；之后能删这把锁的只有本节点逻辑线程（销账要求账本有这一局、回调里查过）、`CANCEL_OFFLINE`（看到 F 会拒绝）
  与 TTL。基线的 `ConfirmRebuiltFreeze`（`pb.cpp:540-579`）防的「两次往返之间被删」在这里不存在。

### 7.8 进场恢复（基线登录钩子 `pb.cpp:1999-2088`）

**挂接**：`SceneWorld.onPlayerLoaded` 末尾、`teamFollow.onEnteredScene` 之后（`SceneWorld.java:755`；同基线组队 3.5 步在战斗 6 步之前，`lc.cpp:1706-1734`），
**所有进场类型**都跑（登录 / 重连 / 顶号 / 5.2 交出进场）。

0. **沿用旧实例**（`previous.ownerEpoch() == epoch`，与 `continueLocationSeq` 同一处，`SceneWorld.java:701-727`）：把 `previous` 的冻结**复制成一个新的 `BattleFreeze` 对象**挂到新实例，
   **复位运行态标记**（`rescuing = false`、`cancelRequested = false`；`lockExtended` 照抄——为假时新实例上的补发确认再续一次）。`preparedHere`（「这一局的 144 不必再推」）**看会话**
   （`BattleFreeze.carriedCopy(sameSession)`，第二轮修正）：会话没变（同会话的重复进场）→ 照抄旧值，之后的确认升级不会多推一条 144（同基线：按「备战时记下的会话号 ≠ 当前会话号」才推）；
   会话变了（重连 / 顶号）→ 置假。计 `rebuilds{carried, rebuilt}`。
   **沿用时推不推 144 只看副本的 `preparedHere` 与阶段，不再另判会话**（沿用后无条件调 `pushReconnectOnce(…, CARRIED)`）：
   - 会话变了 → 副本为假，FIGHTING 的当场推（对应基线 RECONNECT / REPLACE 那一步，`pb.cpp:2080-2087`）；
   - 会话没变、旧值为真（在这个会话上备战的，或已经推过）→ 不推；
   - 会话没变、旧值为假的 FIGHTING → 当场推一次并置真。这种冻结只有一种来路：旧实例按 F 锁重建之后复核（`TOUCH`）还在途、144 还没推。那次复核的回调认的是旧实例，回来即被丢弃
     （不推第二条，也不计 `rebuilds{login,*}` / `hints{login}`）；新实例已有冻结、恢复的锁步骤不再重建，补发的确认走 `lockExtended` 的幂等分支——这里不推，这个会话就收不到这一局的 144
     （基线重建时不等复核就推）；
   - PREPARING 的任何组合都不在沿用时推（B5），等确认升级时按 `preparedHere` 决定。

   账本随 `persistentState()` 自动沿用；本实例已销账集合也一并沿用（`inheritWrittenOff`：旧实例的账本与「已为哪些局发出过销账」这份认识是一体的）。
   - **没落盘的账本条目随内存沿用**：旧实例已应用、存盘还在途的局，账本条目与金币随内存过来；新实例的落库快照取库里那一行，所以恢复读带回这条记录时按已应用处理、不销账，
     再压一笔自己的存盘，落盘后才销。旧实例那笔存盘之后 SAVED 回来不触发销账。
   - **`lockPending` 为真（备战写锁还在途）的冻结不沿用**：那条 `PREPARE_LOCK` 的回调核对实例会失败，按 §7.5 第 4 步删锁并回 1004；沿用过来的冻结会变成「没有锁、`lockPending` 永远为真」的孤儿，
     取消被永远延后，只能等 reaper 按备战期限摘。
   - 不复制同一个对象、要复位 `rescuing` 的理由：旧实例上在途的回调都按「实例 + 冻结对象」双重核对后丢弃；同一个对象带着 `rescuing = true` 过来，reaper 会永远跳过它，冻结永不过期。
1. `recovery = PENDING`，**开新一代**（`recoveryGeneration + 1`），发 `ENTER_READ`。回调回到逻辑线程先核对实例，再核对代际：不是最新一代（之后又因原地解冻 / reaper 重试发过恢复读）→
   整份丢弃，不计指标、不改状态（D32）。读失败（含发读时同步抛出）→ `recovery = RETRY`、计 `recovery{retry}`，由 reaper 重读（基线 Redis 断开时只是跳过这次，D20）。
   读回来了、但处理快照（第 2–4 步）时抛出意外异常 → 同样置 RETRY，另计 `recovery{error}` 并打 ERROR（正常恒为 0，非 0 就是代码缺陷；已应用的局有账本兜着，重跑是幂等的）。
2. **待结算记录**：按 battle_id 无符号升序逐个处理（雪花号升序即时间顺序；HP 是终值，顺序就是语义）：
   - 字段名不是规范的无符号十进制（§7.2）、字段解析失败、或 blob 里的 `battle_id` ≠ 字段名、或 `player_id` ≠ 自己 → 按字段名的**原始字节**删这个字段（`DELETE_SETTLEMENT_FIELD`，天然条件删），计 `pending_corrupt`，字段名合法时记下这个 id；
   - 否则按 `login` 路径应用（§7.10 第 8 步之后的收尾：不看锁、不要求冻结匹配，同 `pb.cpp:1926-1931`）：
     `ALREADY_APPLIED` → `HOLD(X)` + `writeOff`；`APPLIED` → `HOLD(X)` + `writeOff(X)` + 推 150，冻结 id == X 时 `clearFreeze(KEEP_LOCK)`；
     **`DEFERRED`（金币被拒等）→ `recovery = RETRY`，停止应用后面的字段**（不能越过它应用更新的局），但**第 3 步照常执行**。
     X 在本实例已销账集合里而账本没有它（这份快照早于那次销账）→ 不应用，按 ALREADY_APPLIED 收尾（§7.10 第 9 步）。
   - 「不要求冻结匹配」的另一面：沿用的冻结是**别的局**（玩家已在下一局）时，更早一局的待结算记录照常应用（气血按它的终值改写），那个冻结不摘、`HOLD` 落空、
     落盘后的 `ACK` 只删旧局记录。
   - 恢复读回来时玩家已在 **FREEZING** 且快照里有待结算记录 → 这条记录 `deferred_frozen`、`recovery = RETRY`，不 `HOLD` 不 `ACK`；之后 reaper 每轮重读一次，
     直到交出提交或原地解冻（原地解冻后重跑的恢复当场补应用）。
3. **锁**（没有冻结时；**第 2 步中途停下也照做**）：
   - `b` 在本次 `ENTER_READ` 读到的字段名里 → **不重建**：这一局已经结束，结算记录就是证据，重建只会把玩家冻进一场打完的战斗
     （同基线坏记录 `pb.cpp:2055-2057`、补应用失败不重建 `pb.cpp:1963-1967`）。三种情形分开计（`rebuilds{login, …}`）：那个字段是坏的 → `skipped_corrupt`；
     这一局本轮刚应用或账本命中（离线结算后在期限内登录的**常态**，已在第 2 步销账，不再多调一次 `writeOff`）→ `ledger_hit`；记录被延后、或排在延后的那一局之后还没轮到 → `skipped_pending`；
   - 账本里有 `b`，或 `b` 在本实例已销账集合里（这份快照早于那次销账，锁其实已放，D32）→ 不重建，再推一次 `writeOff(b)`，计 `ledger_hit`；
   - 否则（锁指向一场**仍在进行**、没有结算记录的局）按 §1.2 的取值规则重建（纯函数 `BattleFreeze.fromLock`：`s` 只有等于 `P` 才是备战，缺失或不认识一律按 F；
     `d` 为 0 按 `now + max(TTL, 0) × 1000`；P 缺 `p` 取 `d`；`n` 缺失按 0），`preparedHere = false`、`lockExtended = (s == F)`，**挂上冻结即停步**（不等复核），
     再发 `TOUCH(b, ttl(有效期限), s, d, p)` 复核。是否推 144 **按重建那一刻的阶段**决定，不看回调时刻的阶段（重建出 PREPARING、复核在途时确认到达，升级那一下已经推过，这里不得再推）：
     - 没命中（0）→ `clearFreeze(KEEP_LOCK)`、计 `rebuilds{login, reverted}`；
     - 命中（1），或命中但锁上已是 F 而入参是 P（2，什么都没改，D30）→ 计 `rebuilds{login, rebuilt}`；重建时是 FIGHTING 的推 144。返回 2 时冻结**保持 PREPARING**、
       不升级、不补推 144，等下一次补发确认升级（B17「S1 还在」一支的行为不变，§10.5）；
     - 复核脚本出错 → 保守保留冻结，计 `rebuilds{login, error}`；重建时是 FIGHTING 的**照样推 144**（冻结既然留着，重连提示就该给；同基线不等复核结果就推），
       并把 `lockExtended` 置回 false，让下一次补发确认再续一次锁；重建为 PREPARING 的不推；
     - 回调回来时实例已换、玩家已离场、或挂着的冻结已不是这次重建的对象 → 整个丢弃（§7.4）。
     锁缺 `d` 时的一个后果：冻结期限 = 现在 + 锁的剩余 TTL，`TOUCH` 把这个值写回锁并把 TTL 续成它 + 60 s——每经过一次这样的重建锁的寿命多出 60 s。只在锁字段不全时出现，`PREPARE_LOCK` 总写 `d`。
     原稿在第 2 步延后时连锁步骤一起停掉：金币长期被封时，一场仍在打的局在本实例上没有冻结，各在途闸全部敞开、也不推 144，这一条修掉它。
   - 回调时玩家处于 **FREEZING**（进场后马上发的 63 已进入交出）→ **不挂冻结**（计 `rebuilds{login, miss}`），同 §7.7「FREEZING 时只续期」：交出提交后目标节点的进场恢复会重建；
     交出没提交时，原地解冻之后**重跑完整的进场恢复**（下面一条）。RESOLVING 照常挂上，随后 `onSwitchTargetChosen` 的复查中止换图、推 23 `{3023}`。两种冻结因此始终互斥。
4. 账本里其余条目（第 2、3 步没有处理过的）逐个 `writeOff`（加载自库的天然 durable；基线靠 reaper，这里进场就做）。账本损坏时不销。
5. 没有延后的 → `recovery = READY`（计 `recovery{ready}`）；有延后的 → `RETRY`（计 `recovery{retry}`，同时已计 `settlements{path=login, result=deferred_*}`，据此与读失败区分）。

**原地解冻后重跑完整的进场恢复**（`BattleHooks.onUnfrozenInPlace`，由 `SceneWorld.unfreezeInPlace` 调；D34）：交出没提交、原地解冻之后从第 1 步起重跑，
不是原稿写的「只补锁步骤」。理由：锁步骤本身就需要 `ENTER_READ` 的结果，那次读失败时若不落到 RETRY 就没有任何东西重试；重跑全流程还能把冻结期间被 DEFERRED 的结算当场补上、
把冻结期间没 `forget` 的账本条目销掉（§7.12）。代价：一次往返内 `recovery = PENDING`（备战 1006、结算 DEFERRED），读失败转 RETRY 由 reaper 重读。
与还在途的更早一次恢复读靠代际号区分，只认最新一代。

- 144 一定在 79 / 21 / 47 之后（进场下行是同步发的，恢复是异步的）。PREPARING 永不推（B5）。
- 进场恢复完成之前：结算回 DEFERRED、备战回 1006；**在途闸不受影响**（只看冻结）——否则每次登录后马上发的 63 都会因为一次 Redis 往返被拒。
  这个窗口基线完整重登时同样存在（`RestoreBattleFreezeOnLogin` 是异步的），见 §10.1 第 9 条。

### 7.9 reaper（`logicLoop.scheduleAtFixedRate(30 s)`，与 `SceneTicker` 同一个执行器，启动注册、停服注销）

| 情形 | 动作 | 基线 |
|---|---|---|
| PREPARING 过了 `prepareDeadline`（0 时看 `deadline`） | `clearFreeze(KEEP_LOCK)`，锁留到 TTL；计 `freeze_expired{preparing}` | `pb.cpp:2137-2151` |
| FIGHTING 过了 `deadline + 10 s` 且没在 rescue | 置 `rescuing`，`READ_IF_OURS(X)` → 回调（实例与冻结未换，先清 `rescuing`）：有记录且有效 → 按 `rescue` 路径应用（与 §7.10 同一组前置：`recovery ≠ READY` / FREEZING → DEFERRED；APPLIED / ALREADY → 同 §7.10 收尾，计 `rescues{applied/already}`）；没有记录 / 应用回 DEFERRED → `clearFreeze(DELETE_IF_MATCH)`，计 `freeze_expired{fighting}`（DEFERRED 时记录留在 Redis，由进场恢复按局序补应用）。**读出错 → 本轮不判废**，计 `rescues{error}`，下一轮再读；直到 `now > deadline + LOCK_EXTRA_TTL_SEC` 仍读不到才不读直接判废（此时锁已过期，Redis 也多半整体不可用）。原稿把「出错」并进「没有」：判废删锁后，随后到达的重投会落进「无冻结、锁不在」被 DISCARDED 并销账——一次读错就丢奖，与 D15 的口径相反 | 基线无宽限、直接判废（`:2153-2158`），D21 |
| 账本非空，且 `recovery == READY`、账本完好 | 每条 `writeOff` | `:2161-2190` |
| `recovery == RETRY` | **只**重跑进场恢复（第 1 步起，开新一代）；**本轮不排空账本**，账本条目由恢复读回来后的第 2、4 步销（D32） | Java 独有（D20） |
| `recovery == PENDING` | 不排空账本，也不重发恢复读（在途的那次回来后自己销） | Java 独有 |

- **RETRY 不排空账本的理由**（审计确认的重复发奖竞态，改前实测金币 200 / 期望 100）：销账与恢复读背靠背发出时，两条脚本在 Redis 上的次序、两个回调的次序都没有保证；
  快照早于销账而回调晚于 `forget`，就会把已销账的局再应用一遍。第二层保险是本实例已销账集合（§7.4）。
- **rescue 的结局**：`applied` / `already_applied` 之外，`miss`（没有记录、记录归属不符）、`deferred`（`recovery ≠ READY`、FREEZING、金币被拒、账本损坏——同时计
  `settlements{path=rescue, result=deferred_*}`）都判废删锁（`DELETE_IF_MATCH`）并计 `freeze_expired{fighting}`；`gave_up` 是期限 + 60 s 不读直接判废。
- **判废删锁之后的补跟随**：`clearFreeze(DELETE_IF_MATCH)` 的删锁结局回来后按 §7.4「删锁结局回来之后补给谁」处理——被判废的那个实例还在就补给它；
  删锁在途期间玩家同 epoch 重进了，锁确实是这一次删掉的（返回 1）才补给新实例，返回 0 或玩家已离场都不补。
- **`xm_scene_battle_frozen` 的口径**：reaper 每轮数一遍。本轮因备战到期被摘的 PREPARING 不计入；本轮同步判废（期限 + 60 s 放弃）的 FIGHTING 仍计入本轮的 fighting，下一轮才归零。
- reaper 处理单个玩家抛异常只记 ERROR、继续下一个；遍历的是快照，逐个再核对实例仍在。

### 7.10 结算到达 `applySettlement`（对应 `ApplySettlement`）

1. `target_instance_id` ≠ 本实例 → NOT_HERE（Dubbo 线程上）。
2. 解析；失败、`player_id` / `battle_id` 为 0、信封 `player_id` ≠ settlement 里的 → HANDLED + DISCARDED + ERROR，**不销账**（`pb.cpp:1816-1820`、`:1620-1625`）。
3. `playersById.get(pid) == null` → NOT_HERE。Java 没有「退出中」：离场在逻辑线程上同步移除，写回已带账本。battle 下一轮按位置重新解析（离线时由 `ACK_IF_SUPERSEDED` 判取代，D17）。
4. `recovery ≠ READY` → DEFERRED（D20）。
5. `frozen()`（5.2 FREEZING）→ DEFERRED（同 `IsSettlementApplicable`，`pb.cpp:354-358`）。RESOLVING 不算：之后拍的冻结快照会包含这次应用。
6. 有冻结且 id ≠ X → DISCARDED + `writeOff(X)`（`:1849-1859`）。
7. 没有冻结 → 异步 `HGET lock b`，回到逻辑线程后先复核「实例仍是这个、`recovery == READY`、不在 FREEZING」（不满足 → DEFERRED）：
   读失败 → DEFERRED；`b ≠ X` 或锁不在 → DISCARDED + `writeOff(X)`；回调时已有别的局的冻结 → DISCARDED（**不**销账，同 `:1898-1904`）；否则按 `by_lock` 路径应用（`:1905`）。
   **判定次序就是上面的书写次序**（先看锁，再看回调时刻的冻结，同基线 `pb.cpp:1881-1904`）：锁不是本局时，即使回调时已有别的局的冻结也照样丢弃并销账
   （`ACK` 只删 X 的记录，`b ≠ X` 的锁不动）；只有「锁读到的是本局、回调时却挂着别的局的冻结」才不销账。回调时已有**本局**的冻结（读锁在途期间重建出来的）→ 按 `online` 路径应用。
   这一支的应答等读锁回来才完成；回调抛异常或逻辑线程已停 → 应答异常完成（§7.3）。
8. 冻结 id == X（PREPARING 或 FIGHTING）→ 按 `online` 路径应用。
9. 应用（§7.11）回 APPLIED / ALREADY_APPLIED → `HOLD(X, 180)` → 冻结 id == X 时 `clearFreeze(KEEP_LOCK)`（`ReleaseFreezeKeepLock`，`:666-679`）→ `writeOff(X)` → 本次新应用才推 150
   （`:1920-1933`）。回 DEFERRED → 原样回 DEFERRED（冻结保留）。回 DISCARDED（归属不符）→ 不销账。
   这一步是 `PlayerBattleService.applyAndFinish`，**四条应用路径（online / by_lock / login / rescue）的唯一收口**，过期读在这里统一兜住（D32）：
   X 在本实例已销账集合里、而账本没有它 → **不调应用**，按 ALREADY_APPLIED 收尾（`HOLD`、解同局冻结、再销一次账把可能残留的记录 / 锁清掉，不推 150），
   计 `settlements{path, already_applied}`。带它回来的那份读（恢复读的快照、读锁、rescue 读）早于那次销账，或者这一局是本实例决定丢弃的；账本可能已经 forget，不能再靠账本去重。
   由此带来一处故障路径上的行为变化：本实例对某局做了「丢弃并销账」而那次 `ACK` 失败（记录还在 Redis）时，同一实例随后重跑进场恢复（RETRY / 原地解冻），
   原来会按 `login` 路径把它应用（B13 式补发），现在保持丢弃并再发一次 `ACK`。新实例（重新登录）上的行为不变，仍按 B13 应用。

### 7.11 结算应用（`BattleSettlementService.apply`，逻辑线程，一个任务内同步完成）与快照组装

**应用步骤**（顺序逐条同 `pb.cpp:1613-1809`）：

| 步 | Java | 失败 | 基线 |
|---|---|---|---|
| 0 | `applying` 为真即抛 `IllegalStateException`（不重入断言，取代应用缓存，D18） | — | `cache.h` 的 InFlight |
| a | `settlement.player_id == player.playerId()` 且 battle_id ≠ 0 | DISCARDED（ERROR） | `:1617-1625` |
| b | `ledger.has(X)` → ALREADY_APPLIED（不推 150） | — | `:1631-1637` |
| c | `frozen()` 或账本损坏 → DEFERRED，零副作用 | DEFERRED | `:1643-1648`；D24 |
| d | **金币先行**：`gold_gain` 按无符号读，`> Long.MAX_VALUE` → DEFERRED + ERROR；`> 0` 时 `currency.add(player, 金币, gold, BATTLE_REWARD, correlationId = X)`，失败（GM 封禁 / 全服封禁 / 溢出 / 冻结）→ DEFERRED。此前没有任何副作用（B7） | DEFERRED | `:1672-1690`；`CurrencyService.java:64-97` |
| e | **气血、法力**都只在各自的派生上限 `> 0` 时才夹到上限（同基线 `pb.cpp:1693-1697`；上限为 0 时取结算值，不把残血抹成 0 当成阵亡）；然后 `PlayerRevive.reviveIfDead(夹后气血, 夹后法力, maxH, maxM)`（只在气血 0 时回满；`is_dead` 但气血 > 0 与基线一样不复活）；不推 170 / 66（B12） | 之后各步单独 try / catch，记 ERROR、继续 | `:1691-1722`；`PlayerRevive.java:27-33` |
| f | **宝宝**：逐个 `PetService.applyBattleSettlement`（§7.14），有宝宝条目时经 `PetFeature` 推一次 184 | 同上 | `:1724-1734` |
| g | **经验**：INFO + 计 `exp_ignored`（B8） | — | `:1736-1742` |
| h | **消耗**：跳过 0；非 `battle_usable`（`SceneBattleTables.battleUsable`，与快照共用判据）→ ERROR、计 `consume_rejected`、跳过；单条数量夹到 `0xFFFFFFFF`，同一配置的多条累加后**仍饱和在 `0xFFFFFFFF`**（基线是 uint32 的 `+=`，两条大数会回绕，D35）；`BagService.removeClamped(INVENTORY, counts, X, extra)` 按实际持有夹紧、**格子号升序**抽取（D22），逐实例记 `ITEM_DESTROY`（关联号 X，extra 同基线）；实扣 < 请求时 WARN + `consume_clamped`；之后 `BagService.mergeOnly`（只合并不重排） | 同上 | `:709-767` |
| i | **掉落**：跳过 id 或数量为 0 的；单条数量同样夹到 `0xFFFFFFFF` 后按配置累加（`:776-783`；累加同样饱和，D35）；入包前按配置记 `bag.total(config)`；`addItems(INVENTORY, …, ITEM_AWARD, X, extra)`；失败时用「现有 − 入包前」算没进去的部分改投 TEMPORARY（`drop_overflow`）；仍失败 → ERROR + `drop_lost`，**不让整笔失败**（B10）。全服封禁的物品走同一条失败路径 | 同上 | `:769-836`；`BagService.java:70-93` |
| j | **击杀**：每条 `defeated_monsters` 调 `MissionService.onMonsterKilled(player, config, count)`（每只一条事实，0 号跳过） | 同上 | `:1755-1767`；`MissionService.java:208-215` |
| k | `ledger.record(X, now)`：e–j 包在一个 `try` 里、本步放在它的 `finally`，而这个 `try` **从 d 步成功之后才开始**——a–d 任一步返回（DISCARDED / ALREADY / DEFERRED）都不登记，**金币一旦入账（或 `gold_gain = 0` 走过 d 步）就一定登记**；evicted 时 ERROR + `ledger_evictions` | — | `:1768-1779` |
| l | `world.requestSave(player)`（同一玩家至多一笔在途、脏比对相同就跳过的规则不变） | — | `:1789-1796`；`SceneWorld.java:1587-1609` |

- **服务闸的关系**：d–j 用到的 `CurrencyService.add` / `BagService.writable` / `MissionService.writable` 只在冻结时拒绝，c 步已排除冻结，所以不会出现「闸半拒、账本照记」（基线 `:345-353`
  要求整笔判据是内部闸的超集）。**应用时战斗冻结还没摘**（§7.10 第 9 步在应用之后才 `clearFreeze`），`inBattle()` 为真：d–j 走到的任何代码都不得看 `inBattle()`。
  6.3 会把 25011 / 26008 加进 `AttributeService.checkWritable` / `PetService.checkWritable`（§7.13），所以 f 步的 `PetService.applyBattleSettlement` 与 e 步的气血回写**绝不能**经过这两个
  `checkWritable`。单测钉住：在冻结为 FIGHTING 的玩家身上应用，d–j 每一项都生效（不被冻结闸、也不被战斗闸拒绝）。战斗闸**不下沉**到货币 / 背包 / 任务服务（D48），否则结算会被自己拦住。
- **客户端可见的线上顺序**：184（f）→ 150（§7.10 第 9 步）；之前不推任何别的东西（任务没有推送，背包 / 货币靠客户端主动拉 191 / 54）。

**快照组装** `BattleSnapshots.build`（纯函数，与 §1.3 表逐项对照）：

| 字段 | Java 取值 | 与基线 |
|---|---|---|
| `player_name` | `ScenePlayer.name()`（`player.name`，经 `PlayerData.name`） | 同（基线取 profile 副本） |
| `appearance_id` / `gender` / `class_id` | `ScenePlayer` 现有字段 | 同 |
| `level` | `max(level, 1)` | 同 |
| `base_attributes` | `health` / `mana` 取 `PlayerAttributes` 当前值；`speed` 取派生速度，0 → 120；`armor` / `strength` / `critchance` / `resistance` 取真实职业行的 `init_*`（`class_table.proto:25-28`）；`stamina` 0 | D8（`PARITY.md:79` ①；现表 9 个职业初值相同，没有数值差异） |
| `max_*` / 物伤 / 法伤 / 防御 | 派生属性；max 为 0 时 `max(当前, 1)` | 同 |
| `pets` | `PetService.buildBattleSnapshot`（§7.14） | 同 |
| `skill_table_ids` | `player.skills()` 原顺序；跳过 0 与无表行；`BattleRules.isTurnBattleCastableSkill`（经 `SceneBattleTables.castable`，启动时算好） | 同（engine-spec D7） |
| `buffs` | 恒空（Java 玩家身上没有实时 buff，`PARITY.md:80`） | 两版结果相同 |
| `items` | `INVENTORY`；跳过数量 0；`SceneBattleTables.battleUsable`；`TreeMap(Integer::compareUnsigned)` 合并，数量 uint64 累加 | 同 |
| `routing` | `session_id` = 会话号；`gate_node_id` / `gate_instance_id` = `GateLinks.Link`（`GateLinks.java:42`，恒有值）；scene 节点号 / 实例 / zone = `SceneNode.nodeId()` / `instanceId()` / `zoneId()`（`SceneNode.java:443-455`） | 同；gate 实例 id 恒非空（6.2 的 CreateBattle 对空值回 1005） |
| `team_index` | 0 | 同 |
| `table_fingerprint` | 启动时算好的指纹 | 同 |

### 7.12 账本与销账

**格式**（Java 自有，`xm-player-store/src/main/proto/xm/storage/player_state.proto`，下一个空闲号是 9，`:16-35`）：

```proto
// 回合制战斗结算幂等账本（mmorpg BattleSettlementLedgerComp 的 Java 自有对应）：只登记「已应用、销账未确认」的局，稳态 0~1 条，上限 64 是异常兜底。
// 与资产同一份记录、同一次围栏写——「这一局已应用」与资产同生共死。没有这段 = 没有待销账的局。
BattleLedgerState battle_ledger = 9;
message BattleLedgerState { repeated BattleLedgerEntry applied = 1; }   // 按 battle_id 无符号升序写出（周期存盘按值比对，顺序必须确定）
message BattleLedgerEntry { uint64 battle_id = 1; uint64 applied_at_ms = 2; }  // applied_at_ms 只用于满员淘汰与排障
```

- 内存 `TreeMap<Long, Long>(Long::compareUnsigned)`；`has` / `record`（满 64 淘汰 `applied_at_ms` 最小的，按时间戳不按下标）/ `forget` / `toState` / `restore`，语义逐条同 `ledger.h:34-88`。
- 在 `ScenePlayer.persistentState()` 写出、构造器恢复（`ScenePlayer.java:97-125`、`:313-340`），**各层未知字段都原样保留**：`BattleLedgerState` 这一层，以及每个 `BattleLedgerEntry` 自己的
  （内存里按 battle_id 旁挂一份条目级 `UnknownFieldSet`，条目被 `forget` / 淘汰时一起丢；做法同 `AssetOpLedger`）。滚动升级 / 回滚期间旧版本节点不会把新字段抹掉。
- **加载校验**：battle_id 为 0、重复、条数 > 64 视为损坏；损坏时原样带回、不改写，该玩家的结算一律 DEFERRED、备战回 1006，绝不当空账本用（D24，做法同 `AssetOpLedger`，`arch` §4.12）。
  进场规整时（`PlayerInitializer` 里调 `PlayerBattleService.checkLedgerOnLoad`，与 `AssetOpService.checkLedgerOnLoad` 并列）大声报一次 ERROR；之后备战因损坏被拒时打带原因、player、battle_id 的 WARN
  （与「恢复中 / 未落盘」同计 `prepares{not_ready}`，靠这条日志区分）；结算因损坏延后计 `settlements{deferred_ledger}`。没有新增专门的计数。自建世界的装配要自己加这一步（`BattleFixture` 已加）。
- **两条判据**：「这一局应用过没有」= 内存 `ledger.has(X)`；「可以销账」= `durable(player, X)` = `player.persistedState() != null` 且其中的 `battle_ledger` 有 X
  （`ScenePlayer.java:380-382`；只在在线存盘 SAVED 时更新、FAILED 置 null，`SceneWorld.java:1612-1635`）。与资产通道同一条判据（`AssetOpService.java:515-534`）。
- **围栏**：在线存盘要求 `owner_epoch = E AND owner_released = 0`、最终写回 `owner_epoch = E`、交出走 handoff-spec §5.2。在线存盘回 FENCED 时实例照现有逻辑被移除并踢掉
  （`SceneWorld.java:1621-1633`）：内存里的奖励连同账本条目一起作废，待结算记录仍在，下一个持有者进场时恰好应用一次。基线这条路径不受归属围栏保护（`pb.cpp:349-353`），Java 更严。

**`PlayerBattleService.writeOff(player, X, trigger)`**（销账唯一入口，规则同 `pb.cpp:400-451` 的 1、2 条；原稿设想的独立类 `SettlementWriteOff` 没有建，§7.1）：

1. 账本**没有** X → 立即 `ACK(X)`（判废或重复投递）。
2. 有且 durable → `ACK(X)`。
3. 有但不 durable → `requestSave`：`IN_FLIGHT` → 等那笔存盘回来时 `onPersisted` 再判；`DEFERRED` → 交给 reaper；`UNCHANGED` 而不 durable → ERROR（比对或快照维护有 bug，同 `AssetOpService.java:497-508`）。
   基线「已有未落地存盘就不压」由「同一玩家至多一笔在途」天然保证。FREEZING 时 `requestSave` 同样回 `IN_FLIGHT`（`SceneWorld.java:1594-1598`），但并没有在线存盘会回来：
   条目随冻结快照落库，交出提交后由目标节点进场销账，没提交则原地解冻后由 reaper 再判。
4. 规则 3（本节点没有活实例）在 scene 侧没有对应路径，由 battle 的 `ACK_IF_SUPERSEDED` 承担（D17）。

**发出 `ACK` 的那一刻**就把 X 记进本实例的已销账集合（不等结局、脚本失败也不撤，§7.4、D32）：账本里有的条目照旧由账本挡着并重试销账；账本里没有的是本实例决定丢弃的局，同样不该再应用。
`ACK` 每次都给这一局写已销账墓碑（D29），所以只对已经有结论（应用过 / 丢弃了）的局调用。

**`ACK` 的结局**投递回逻辑线程：出错 → 保留账本条目、计 `acks{trigger, error}`；返回整数 → 先计 `released`（非 0）/ `not_ours`（0），**再**核对实例——
- **实例已换**（离场，或同 epoch 重进换成了新实例）：不动旧实例的账本。但位 2 被置（锁确实是这一次放掉的）时，对**现任实例**（它存在且没有战斗冻结时）补一次组队跟随（第二轮修正）：
  新实例进场时那次跟随检查读到的还是这把锁、已经放弃，它自己随后的销账只会回 0，再没有别的触发点。只有位 1、返回 0、玩家已离场、现任实例战斗在途都不补。
- **实例仍是这个**：**不在 FREEZING** 时 `ledger.forget(X)`（随下次存盘去掉）；位 2 被置且没有战斗冻结 → `TeamFollow.onBattleFreezeCleared`（同 `pb.cpp:304-309`，次序由 future 链保证，
  不依赖连接 FIFO）。补跟随的判据是位 2（放了锁），不是返回值非 0：`ACK` 回 1（只删到记录：锁不在，或锁已是别的局）时不发起跟随检查。

**FREEZING 时不改账本**：冻结中玩家的可变状态必须与冻结快照一致（`ScenePlayer.java:410-416`，handoff-spec §5.9）；条目随冻结快照交给下一个持有者，那边再 `ACK` 一次（返回 0）后摘除，
或原地解冻后由重跑的进场恢复 / reaper 再销一次。

**快路径**（D19）：`SceneWorld.onProgressSaved(SAVED)` 在 `markPersisted` 之后通知 `PlayerBattleService.onPersisted(player)`：账本里 durable 的条目逐条 `ACK`；还有不 durable 的就再
`requestSave` 一次。只在 SAVED 时链式重存；FAILED 交给 reaper，免得库失联时空转。

**最终写回**（离场 / 断线 / 停服）没有结局回调（`PlayerRepository.java:71`），6.3 不加（Q10）：账本随写回落库，下次进场恢复时即 durable、即销账；锁由 `HOLD` 至少保持 180 s，
过期也无妨（待结算记录仍在，进场时按账本判「已应用」后销账）。

### 7.13 在途闸的 Java 接入

**客户端请求**：`SceneFeature.Registrar` 注册时除 `FreezePolicy` 外再声明 `BattlePolicy`（只给冻结策略的旧重载删掉，防止新方法静默得到缺省值；四参数重载两者都缺省 REJECT）。
`ClientRequestHandler.onClientForward` 在 GM 闸与「有没有处理器」之后：`frozen()` 按 `FreezePolicy`（现有，`ClientRequestHandler.java:218`）；否则 `inBattle()` 按 `BattlePolicy`。
两种冻结互斥，判定先后不影响结果（只在指标上可见：`moves{frozen}` 对 `moves{in_battle}`）。**没有处理器的方法排在战斗闸之前**：136（传送）与 226（跨 zone 传送，5.4 未做）
战斗中照旧回 1006、不计闸；226 接入时必须先进下表（基线 3025），届时 `InBattleGateMatrixTest` 的「新方法不在表里即失败」会拦住。
注册期的约束：玩法功能声明 DROP / STOP_ONLY（只给场景核心的移动上行）、或在应答为 `Empty` 的方法上声明它们，都启动即失败。

| `BattlePolicy` | 方法 | 战斗中 | 基线 |
|---|---|---|---|
| DROP | 134 MoveStart、132 MoveSync | 静默丢，计 `moves{result=in_battle}` | `mvh.cpp:170-173`、`:205-208` |
| STOP_ONLY | 131 MoveStop | 只把速度清零，不收位置。速度非 0 时停步并置脏位；速度已为 0（常态：挂冻结时已停步）时是空操作，不广播 66，但照样计 `moves{in_battle}` 与 `gate_rejects{move}` | `mvh.cpp:188-192` |
| GATED | 63 EnterScene | 处理器第一步 3023（先于 `switchInFlight` 的 3014，`ClientRequestHandler.java:296`） | `psh.cpp:54-61` |
| GATED | 84 ReleaseSkill | `SkillService.release`（`:74` 的桩）：1001 之后施法者 7004；`SkillRules.checkTarget`（`:51` 的桩）：目标是本节点玩家但在战斗中 → 7002（与「不是玩家」的 7001 分开）。目标闸只管**指定目标**的技能：范围 / 无目标技能在目标方式处先放行，目标号指向在途玩家也回 0（同基线 `ValidateTarget`，`skill.cpp:386-389`） | `skill.cpp:219-229`、`:410-414` |
| GATED | 168 / 172 / 174 / 171 / 169 / 175 | `AttributeService.checkWritable`：冻结 1005 → 战斗 25011（`AttributeService.java:532-534`） | `attr.cpp:279-291` |
| GATED | 183 / 185 / 186 / 182 / 189 / 187 | `PetService.checkWritable`：冻结 1005 → 战斗 26008（`PetService.java:497-499`） | `pet.cpp:230-242` |
| GATED | 192 SortBag | `BagFeature.sortBag`：类型合法之后、`sortByPlayer` 之前判 → 1005（`BagFeature.java:62-66`）。`BagService` 保持不闸（D48） | `snap.cpp:160-172` |
| ALLOW | 43、77、54、167、181、190、191、193（只读）；173、188（只给建议）；37、49、94、95（GM 货币）；194、195（任务） | 照常 | §2.3 |
| REJECT（缺省） | 以后新加、没声明的方法 | 应答内 `error_message{1005}`（与冻结 REJECT 同形，`ClientRequestHandler.java:251-258`） | Java 独有（D11）；现有方法逐个显式声明，**没有行为变化** |

**资产通道**：`AssetOpService` 第 7 步（`AssetOpService.java:230` 的桩）`inBattle()` → `RETRY 27002`，不记账；第 5 步的 27003 优先，第 6 步中止占位照常放行（`asset.cpp:782-796`）。

**世界内部**：

1. **5.2**：`SceneWorld.onSwitchTargetChosen` 远端分支在 `freeze(...)` 之前（`SceneWorld.java:961-962`）判 `inBattle()` → 回 NONE、推 23 `{3023}`（5.4 跨 zone 为 3025）、计 `switch_resolves{in_battle}`
   （基线 `lc.cpp:2853-2860`）。按 D4 应恒为 0，是纵深防御。本节点分支照旧同步 `switchScene`（同基线同节点路由不查战斗）。
   另在 `SceneWorld.beginRemoteSwitch` 入口拒绝 `inBattle()`（不进 RESOLVING；63 已先回 3023，这里只记 ERROR 并计 `switch_resolves{in_battle}`），
   兑现 handoff-spec `:106`「`begin` 拒绝战斗中的玩家」的契约，以后新增的交出发起方不必各自记得判战斗（5.5 的 `begin(…, EVACUATE)` 落地时同样要接这一条，Java 现在还没有这个入口）。
   **备战这一侧**对换图在途的判定经 `SceneWorld.switchInFlight`（public，有副作用）：过期的 RESOLVING 槽当场作废并放行（§7.5 第 1.3 步）。
2. **5.1 排空改派**（`SceneWorld.relocateResidents`，`:441-480`）与 **5.3 级联 / 管理口改派**：`inBattle()` 跳过、计 `channel_relocations{in_battle}`，下次推进再看（D26）。
3. **组队跟随**（`TeamFollowService`）：新增结果 `IN_BATTLE`（内存冻结）与 `BATTLE_LOCK`（锁存在或读失败，对应 `team.cpp:378-402`）；新增 `onBattleFreezeCleared(world, player)` 入口，
   按「只跟随、不扇出」处理。与原稿、与基线写法的出入：
   - **锁读并进了成员关系那一跳**：读成员关系的同时并行发一条 `EXISTS xm:battle:{pid}:lock`（`BattleLockProbe`，生产 = `BattleLockReader.exists`），两个结果都回来后一起投递回逻辑线程。
     基线是两跳（先读成员关系，第二跳 `MGET` 队长位置 + 自己的锁）；Java 同节点跟随用内存里的队长场景、不读位置（team-spec D9），没有第二跳。
   - **内存冻结只在回调后判一次**，不做原稿写的「读之前与回调后各判一次」：基线在第二跳之前、回调之后各判一次（`player_team.cpp:353`、`:402`），Java 没有「第二跳之前」这个位置；
     也不能把预判加在发起读之前——成员关系那一跳不受战斗闸约束，队长在战斗中进场照样要扇出（基线 `player_team.cpp:327-345` 同）。代价：战斗中的非队长多发一次 EXISTS，结果相同。
   - **读锁失败按在途的范围**：读的 future 异常完成、发读时同步抛出、读回 null、端口返回 null，都计 `battle_lock`、不跟随（fail-closed）。
   - **守卫次序**：换图在途（`switching`）→ 内存冻结（`in_battle`）→ 锁（`battle_lock`）→ 队长不在本节点 → 已同场景 → 队长频道在排空。换图在途的判定同样经 `SceneWorld.switchInFlight`
     （第二轮修正）：过期的 RESOLVING 槽就地作废、不再挡，否则这名队员的跟随会一直被一个死槽挡住，直到他再发 63 或有人对他备战。
   - **`onBattleFreezeCleared` 的触发点**（三类）：解冻且条件删锁完成之后（取消备战、reaper 判废）；保留锁的解冻（备战到期、结算应用后、复核落空撤销）——这次检查会读到锁而放弃，等于空跑；
     销账脚本确实放掉了锁之后（位 2）——真正补上的是这一次。删锁 / 销账的结局回来时发起它的实例已经换掉（同 epoch 重进）的，或者那次删除本来就不跟着一次解冻的
     （备战失败 / 过期后的尽力删锁、离线取消），只要锁确实是这一次删掉 / 放掉的，就补给**现任实例**（它没有战斗冻结时）——它进场那次检查读到的还是这把锁（§7.4、§7.12）。
4. **xm-team 队伍视图**：`TeamDisplay.in_battle` 用 `BattleLockReader.existsAll` 批量 EXISTS 算出，收掉 team-spec D10。口径：锁存在即为 true，只看锁在不在，不看 `s` 也不看 `b`——
   备战中、战斗中、已结算待销账（含续锁的 180 s）都显示在战斗；销账放锁、锁过期或判废后变回 false；只有待结算记录或墓碑、没有锁时为 false。
   读的范围是一次视图构建的全体 rosterIds（成员、申请人、被邀请人、邀请人，最多 26 人；基线 `loadDisplay` 的 MGET 也是对全体 id 做的），每人一条 EXISTS 并发发出。
   任何一条读失败整批按 false（同基线「MGET 失败 → 全 false」，`team-spec:1221`）：同步抛出、异常完成、超时、被中断、结果为 null 都只记一行 WARN、视图照常，不让 RPC 失败。
   没有单独的超时键，与在线读共用请求预算（§8）。咨询性读，部署约束见 §10.5。
5. **5.4 / 7.2 钩子**：226 在 3024 / 3007 之后、3026 之前判 → 3025（5.4 未做）。**GM 回档（7.2b）已接**，但不在 scene 侧、也不是 1005：xm-data 的回档作业在夺权之后、账本差集之前
   批量读战斗锁（`BattleLockGate`），命中记逐玩家结局 `in_battle`，读失败 fail-closed 记 `battle_lock_unknown`，无条件拒绝（不走 acceptDivergence），整区有人在战计入 `zone_not_quiescent`
   （见 data-ops-spec）。7.2c 的回收 / 精确回收 / 欠款写操作落地时也要过同一道闸。
6. **帧外推**（`SceneWorld.integrate`，第二轮修正）：有战斗冻结（备战或战斗中）的玩家整个跳过——不外推、不动速度、不置脏位，也不走挂机停推那一支
   （同基线 `movement.cpp:49-52` 的 `exclude<InBattleComp>`）。Java 挂冻结时已经停步（D5）、在途的移动上行也不收速度，正常流程里在途玩家的速度恒为 0，这里是纵深防御；解冻后照常外推。
   基线同一行还排除了 `PlayerFrozenComp`；Java 的交出冻结（FREEZING）在帧外推里**没有**跳过——冻结那一步已把速度清零、之后的移动上行被冻结闸丢弃，登记为写法上的出入（§10.5）。

**与 5.2 的互斥一览**：

| 当前状态 ＼ 事件 | 63 远端去向 | `prepareBattle` | 结算到达 | 确认到达 |
|---|---|---|---|---|
| 无战斗，NONE | 照 5.2 | 照 §7.5 | 无冻结 → 按锁（§7.10 第 7 步） | 无冻结 → 迟到重建 |
| RESOLVING（不冻结） | 3014 | **1006**（`handoff-spec:106`）；槽已过期（兜底超时 4 s + 1 s）则当场作废、照 §7.5 放行 | 照常（无冻结就按锁） | 可以重建 FIGHTING；随后 `onSwitchTargetChosen` 复查时中止换图、推 23 `{3023}` |
| FREEZING | 3014 | **1006** | DEFERRED，零副作用 | 只跑 `CONFIRM`，不挂冻结；交出提交 → 目标节点进场恢复时重建；交出没提交 → 原地解冻后重跑完整的进场恢复（D34） |
| 战斗 PREPARING / FIGHTING | **3023**，先于 3014 | 1006 | §7.10 | §7.7 |
| 资产通道 | — | — | — | 27003（冻结）优先于 27002（战斗） |

- 交出提交后，源节点上的玩家没有冻结，锁仍在 Redis；目标节点进场恢复对 `transfer = true` 照样执行：冻结快照带着账本落了库，进场即判 durable、即销账。

### 7.14 宝宝参战的 Java 落点（`PetService`）

- `buildBattleSnapshot(player)`：判据与字段同 §5.1；复用 `skills(pet, row)`（`PetService.java:552`，顺序同 `ResolveSkills`）与 `derived(pet, row)`（`:579`）；**不做可施放过滤**（B4）。
  「没有出战宝宝」的判据是 `active_pet_id == 0`，不拿 0 去找宝宝：存档恢复不校验 pet_id，坏档里 `pet_id = 0` 的宝宝不会被带进战斗（引擎对 `pet_id == 0` 整局拒绝）。
- `applyBattleSettlement(player, entries)`：判据与回写同 §5.3（不认识的 pet_id WARN 忽略；缺表行静默跳过；按现算上限夹，法力上限 0 时取结算值；`is_dead` 或夹后为 0 → 气血、法力回到上限）；
  有条目时推一次 184。服务闸 `checkWritable` 不参与（结算在 c 步已排除冻结；战斗闸只在入口）。
- 局内号与宝宝号分离由引擎负责（engine-spec `:250`），scene 只认 `pet_id`。

### 7.15 battle 侧发件箱（xm-battle，`SettlementOutbox`）

- **线程**：独占一条单线程执行器 `battle-outbox`（D25）。`battle-logic` 上的 `SettlementSink.dispatch` 只把任务交给它；名单、计时器、判定都在它上面；Redisson / Dubbo 的回调投递回它。
  好处：不与直连面 I/O 争线程；停机时逻辑线程可以先停、发件箱另行有界排空。
- **dispatch**（调用点见 node-spec §4.6；`origin = DEV` 一律不调，`DEV_GATHER` 照调）：
  0. 入口挡住空的结算（调用方缺陷：不抛出、不占在途计数，只打 ERROR）；发件箱已关闭或线程已停 → ERROR，这份结算没有落库也没有投递。
  1. 组 `BattleSettlementEvent` 字节；序列化失败 ERROR + 计 `serialize_failed`，什么都不发。
  2. `STORE_SETTLEMENT(X, bytes, 7d)`：**成功**（回字段数 ≥ 1）→ 计 `stored` → 登记 `(battleId, playerId) → {payload, originalSceneNode（只进日志）, attempts = 0, probing = false, enqueuedAt}`，
     定时器没开就开 → 用定位器解析目标（下一条）→ 投递；`HLEN > 16` → ERROR + 在 `stored` 之外另计 `fields_overflow`。首投解析不到（NoHolder / 定位出错）→ 这次不投、照常登记，交给下一轮（离线玩家的常态）。
     **回 -1**（这一局已销账，墓碑还在，脚本没有写入，D29）→ 计 `already_settled`（与 `stored` 互斥，也不计 `acked`），不登记、不定位、不投递、不开表。
     **失败、Redis 不可用、或没有结论**（结果为 null、端口返回 null future）→ 计 `not_durable` + ERROR → 解析目标后**只投一次**，不登记（同 B-7，Q16）；这时定位也解析不到就什么都发不出去（同基线 Redis 不可用时的结局）。
     结局不明的那条 EVAL 即使之后才落地，也会被 scene 销账留下的墓碑挡住，不会再造出孤儿记录（§7.21 J17）。
  3. 投递只发生在 SET 结局之后（成功或明确降级）——顺序是硬要求（§3.3 第 4 条）。
- **寻址**（D14）：首投与重投都走 `SceneAssetLocator`（`SceneAssetLocator.java:21-35`）：只认位置状态 `o`；`l` / `x` / 缺失 → NoHolder（本轮无目标）；出错 → 本轮跳过不计次。
  `target_instance_id` 取目录里的实例 id。快照路由里的 scene 字段只进日志。Java 断线即写回并释放，重连租约期间没有持有者，这段时间的结算由进场恢复应用——与基线离线分支同一结局。
- **每 10 s 一轮**（`BattleScheduler`；测试用手动调度器），名单空了**当场**停表（每个摘除点之后都查一次，不等下一轮；探测到已销账的那条路径也一样）。每条：
  - `probing` 为真（上一轮的往返还没回来）→ 跳过、不计次；
  - 登记已超过 `OUTBOX_MAX_AGE`（10 min）→ 按 `EXHAUSTED` 摘除、计 `expired`（ERROR）：只有「探测 / 定位一直出错、从不计次」才会走到这里，记录仍在 Redis；
  - `HEXISTS settlement X`（`PROBE_SETTLEMENT`）：出错**或没有结论**（结果为 null、端口返回 null future）→ 计 `probe_error`，本轮跳过、**不计次**（D15：没问到结论不当已销账）；
    不存在 → 摘除，计 `acked`；存在 → 解析位置（定位出错或结果为 null 计 `locate_error`，同样本轮跳过、不计次）。
    「最后一轮已销账算成功」由这个次序保证（先探测、不在就摘，次数已满也一样），不是由判定函数的 `DONE` 分支保证：`classify` 的 `stillOurs` 在调用点写死为 true
    （基线 `room.cpp:1805` 同样写死），`DONE` 分支不可达，保留它只为逐条移植基线的纯函数与单测；
  - 判定照搬 `ClassifyRetry`（已销账 > 用尽 > 无目标 > 重投），用**本轮之前**的次数判定、判定**之后** `++attempts`（同 `room.cpp:1810-1811`；重投 12 次、第 13 轮用尽，§3.4）：
    `RESEND` → 按 endpoint 投递；`SKIP_NO_TARGET` → 另跑 `ACK_IF_SUPERSEDED(X)`，返回 2 或 3 时摘除（计 `superseded` / `acked`）；
    `EXHAUSTED` → 摘除，记录留给 scene 的进场恢复 / rescue：最后一轮无目标的计 `exhausted_offline`（WARN，玩家离线时的常态），有目标的计 `exhausted`（ERROR，告警）。
- **投递应答只计数**（`delivery{result}`），**不改 outbox 状态**（D16）：scene 应用之后要等落盘才能销账，「已应用」不等于「可以停止重投」。
  `outbox{delivered}` 是**发出次数**（首投与每次重投都计；一次重投同时计 `resend` 与 `delivered`，首投数 = `delivered − resend`）。
- **停机**：最多等 `xm.battle.outbox-drain-timeout`（缺省 3 s；0 = 不等），让在途的 `STORE_SETTLEMENT` 和首投回来，然后丢弃内存名单（记录留在 Redis）。这缩小了 B-2 窗口。
  **挂载点与原稿不同**：不在 `BattleNode` 的停止序列里，而是靠 Spring 销毁 `SceneTransport` bean——发生在 `BattleNode.stop`（关闸 → AbortAllRooms → 停逻辑线程）之后；
  关闭顺序 `drainAndClose` → 停 `battle-outbox` → 停清扫线程 → 销毁直连客户端（`BattleConfigurationTest` / `SceneTransportTest` 钉住「节点先停、传输后关、关之前等到在途结算」）。
  只排空结算发件箱，活动结果通道在途的落库与首发不等。`drainAndClose` 之后才回来的落库成功仍会首投一次，但不登记、不重开表。
- **直连客户端缓存的清扫**（Java 独有，原稿没有）：`NodeRpcClients<SceneBattleService>` 按 (host, port, 实例) 缓存客户端，scene 节点下线 / 换地址 / 换实例后旧条目只增不减。
  `SceneClientSweeper`：每次发调用（确认与结算投递两条路）都登记目标节点；一条单独的守护线程 `battle-scene-sweep` 每 60 s（代码常量）按 zone 现读 scene 目录，
  把已不在目录的节点的客户端逐个销毁；读失败的 zone 本轮跳过（不当成空目录）；清扫期间同地址又发了调用、换了实例的留给下一轮（条件摘除）。目录读是阻塞的，所以不放在 `battle-outbox` 或逻辑线程上。
- **节点崩溃**：内存名单丢失，不恢复（同基线），由 scene 的 rescue（在线）/ 进场恢复（离线）兜底。

### 7.16 确认的传输（`DubboSceneBattleEvents`）

- 按 node-spec §7.9：用快照路由的 `(zone_id, scene_node_id)` 查 scene 目录，目录里的 `instance_id` 等于 `routing.scene_instance_id` 就发往它（正常路径，与基线 Kafka 的实例过滤等价）。
- **实例不符（备战所在的 scene 进程已重启 / 下线）→ 回落到定位器**（D28）：`SceneAssetLocator` 得到 Found 就按它的地址与实例 id 发，计 `rerouted`；NoHolder / 出错 → 不发，计 `skipped`。
  这是原稿与 node-spec §7.9「不等就不发」的修订：不回落时，备战节点崩溃后确认永远送不到，锁停在备战 TTL（最长 96 + 60 s）后过期，玩家在这局还在打时就能被再次匹配，
  这局的结算随后在线上被判「冻结不符」丢弃（B17 的崩溃变体，§10.2）。回落后，玩家已在新实例上（进场恢复按锁重建出 PREPARING）就能正常升级 FIGHTING、续锁、收到 144。
  回落的触发条件比「实例不符」宽（实现）：目录里没有这个节点、目录读失败、条目损坏（`rpc_port` 按无符号 > 65535，打 WARN）、或快照路由缺 zone / 节点 / 实例，都按实例不符回落到定位器。
  条目损坏而玩家还在该节点时，定位器对同一条目回 Failure，计 `skipped`；玩家已换节点则 `rerouted`。
- `confirmBattle(SceneBattleCall{target_instance_id = 上面选定的实例, …, body = BattleConfirmedEvent})` 异步发出、不等结果；补发节奏仍是 6.2 的 17 次。
  回调不碰房间状态（只计数），可以在任意线程上完成。
- **每次确认必有一个计数结局**（`xm_battle_scene_events_total{kind=confirm}`）：发出计 `sent` / `rerouted`；应答是 NOT_HERE 另计 `not_here`，传输失败 / UNSPECIFIED 另计 `error`；
  不发计 `skipped`；回调里的意外（程序缺陷、损坏的输入、端口返回 null future）计 `error` 并打 ERROR——三段回调都挂在无人持有的 future 上，不兜这一层异常会被悄悄吞掉。
  确认的 OVERLOADED / DEFERRED / HANDLED 只有发出时的 `sent`，不另计。
- **dev 房间**（`origin = DEV`）照 node-spec §7.12 继续发确认：scene 侧按锁匹配，dev 房间的 battle_id 不会命中任何锁，零副作用（Q12）。

### 7.17 活动结果通道（`ActivityResultOutbox`，`battle-outbox` 线程）

- 出口 `ActivityResultSink.dispatch(event)` 只在 `kind ≠ NONE` 时被调（判定在 6.2 的 `BattleResultAssembler`，不认识的 kind 也算）。
- 序列化失败计 `serialize_failed`、不发；`SET xm:battle:activity-result:<id> EX 7d`：成功 → 经 `BattleResultSink` 发布**同一份字节**并登记；失败或没有结论（null）→ 发布一次 + `not_durable`。
  登记的是事件对象（不可变），重发时再序列化，结果与落库的字节相同（真 Redis 用例断言了持久副本与发布事件逐字节相同）；是否改存 `byte[]` 等 6.4 定真实传输的签名。
- **发布端口带通道**：`BattleResultSink.publish(event, Channel)`，`Channel{PLAIN, ACTIVITY}`；`publish(event)` 是 default = PLAIN（房间的调用点不变）。
  活动通道的首发与每次重发都传 ACTIVITY，`xm_battle_results_total{channel=activity}` 统计活动局的首发与重发，`plain` 只统计普通局。
  自 6.3 起这个端口有**两个调用方、两条线程**：普通局在 `battle-logic` 上一局调一次；活动局由本通道在 `battle-outbox` 上调，首发 1 次，未销账每 10 s 重发同一个事件对象，
  一局最多 1 + 30 = 31 次，落库失败只发 1 次。6.4 的真实实现必须线程安全、容忍同一 battle_id 多次调用、按 channel 分开计数。
- 每 10 s 对每条 `EXISTS`：0 → 摘除（acked）；1 → 用尽（30）则 ERROR 并摘除，否则 `++attempts` 后重发原字节（只在重发时计次，同 `room.cpp:2014`）；
  **出错或没有结论一律跳过、不计次**（计 `probe_error`；与 D15 统一，基线空回复跳过、出错当仍在）；登记超过 `OUTBOX_MAX_AGE` 仍没有结论 → 按用尽摘除（记录仍在 Redis，留给 4.6 的巡检器）。
- 销账方是 4.6 的帮会同道历练消费方与巡检器，两版现在都不存在；6.4 接上 `BattleResultSink` 的真实传输之前发布端口只记日志——整条通道两版都不可达（Q11）。

### 7.18 dev / test 管理接口（Java 独有，xm-battle 管理端口 18112）

| 接口 | 请求 | 应答 | 说明 |
|---|---|---|---|
| `POST /admin/battle/dev/gather` | `DevGatherRequest{mode = PREPARE_ONLY / CREATE, battle_id, match_mode, battle_config_id, seed, deadline_ms, prepare_deadline_ms, repeated member{player_id, team_index}}` | `DevGatherResponse{prepare_results[], create_result}` | 按 player_id 升序逐人：位置记录（只认 `o`）+ scene 目录 → `SceneBattleService.prepareBattle`；任一人失败 → 按升序对已备战的人发 `cancelBattlePrepare`，回 422 并写原因。`PREPARE_ONLY` 到此为止、回快照。`CREATE`：组 `CreateBattleRequest`（快照的 `team_index` 按请求改写）→ 进程内 `BattleNodeServiceImpl.createBattle(origin = DEV_GATHER)`；`NOT_ALLOCATABLE` 或业务错误 → 全部取消、回 422 |
| `POST /admin/battle/dev/cancel-prepare` | `{player_id, battle_id}` | 204 | 解析位置后调 scene 取消 |

- **`DEV_GATHER` 房间**：快照来自 scene（不是调用方伪造），照常确认、照常结算；对局结果事件仍跳过（没有 match）；不接受 `activity_context`。6.2 的 `dev/create`（`origin = DEV`）保持永不结算。
- **鉴权**同 node-spec §7.12：`X-Xm-Admin-Token`（`XM_ADMIN_TOKEN`，常数时间比较，不设则 503）+ `X-Xm-Operator` 审计；运行模式不是 dev / test 一律 403。
- **线程**：Tomcat 线程上做 Redis 查询与 Dubbo 调用，整体等待上限 10 s；不碰房间（建房走同一条准入与投递路径）。

### 7.19 gate 拒绝战斗上行

- **6.3 之前**：`BattleClientPlayer` 不是 scene 玩家服务，也不在 `SERVICE_BACKENDS` 里，`backendOf` 回 `unsupported`，`ClientDispatcher` 的缺省分支回 `sendTip(1003)`，
  不计非法包、不断连——客户端结果与基线相同，但有两处细节差异：热关停检查排在前面（运维热关停了某个战斗方法时客户端收到的是信封 1003 而不是 23 `{1003}`）；
  请求先进待处理队列（login 调用在途时 tip 要等它完成才发，队列溢出还可能断连）。
- **已落地的改法**（D12）：
  - `MessageRoute`（record）多一个分量 `directOnly`；`MessageRoutes.of` 按**服务裸名**置位：`MessageRoutes.DIRECT_ONLY_SERVICES = {"BattleClientPlayer"}`，圈出 12 个号。
    原稿的另一种判据「方法的 `OptionFileDefaultNode = NODE_BATTLE`」**没有采用**：它不是基线的判据（§2.5），`MessageMethod` 也只暴露服务语义；Java 不照搬基线「按 proto 目录名」的回落，
    因为 `AGENTS.md` §1 不许依赖同步来的 proto 所在目录。等价性由 `MessageRoutesTest` 守：照算基线回落的前两级（服务名、package），第三级以 `OptionFileDefaultNode` 近似，
    合成判据圈出的号必须等于 `directOnly` 的 12 个号；并钉住「前两级不命中且文件没写 option」的客户端服务清单（现在恰为 Login / Friend / Guild 三个）——
    mmorpg 以后新增这类服务时用例先失败，由人去 mmorpg 生成物确认它是不是 `BattleNodeService`。残余盲区：option 与目录自相矛盾的文件测不出，属 mmorpg 自身错配。
  - `ClientDispatcher.onRequest` 的闸序：关闭中 / 未握手 → 白名单 → 体积 → 限频 → GM 闸 → **直连闸（新）** → 热关停 → 后端队列 / 待处理队列，与基线 `gate_cmp.cpp:858-950` 逐段相同。
    直连闸当场推 23 `{1003}`、计 `xm_gate_client_requests_total{result="battle_rejected"}`、return：不进待处理队列与后端队列、不计非法包、不断连、不调热关停的计数，逐条只打 DEBUG。
  - 由闸序得出的行为：被拒的战斗号照样占限频额度（超频回信封 1008、超长回 1010，都计非法包，同基线）；热关停规则（`BattleClientPlayer/<Method>`、`BattleClientPlayer/*`、`*`）对 gate 上的战斗号
    没有可见效果，客户端始终收到 23 `{1003}`；直连闸不看 `domain`，即使有人把战斗服务错接到某个后端，请求也到不了后端——「6.4 接入 `MatchService` 后 `BattleClientPlayer` 仍被拒」由此保证；
    Notify 号（应答类型 Empty）发上来同样回 23 `{1003}`；GM 闸在前，契约里若以后出现 GM 名字的战斗方法，生产模式下它先被 GM 闸按非法包拒（同基线次序）。
  - 指标：战斗上行不再计 `result=unsupported`（看板或告警若按 `unsupported` 统计过战斗上行，需要改口径）；`route` 标签仍是 `unsupported`（战斗路由的域没有改），
    `method = BattleClientPlayer.<方法名>`；这 12 条 `battle_rejected` 序列在装配时预建为 0（`GateMetrics.registerRequest`），是 gate 请求计数器里唯一预建的一组。

### 7.20 线程所有权

| 线程 | 做什么 | 不得做什么 |
|---|---|---|
| scene 逻辑线程 | `PlayerBattle` / `BattleLedger` 全部读写；备战、取消、确认、应用、进场恢复、reaper、闸；组快照；推 144 / 150 / 184；收到异步结局先复核实例 | 阻塞的 Redis / MySQL / Dubbo 调用（脚本一律异步）；`join()` |
| scene Dubbo 提供方线程 | 名额、实例核对、解析、投递 | 碰 `ScenePlayer` |
| `scene-asset-reply` | 完成 Dubbo future | 碰 `ScenePlayer` |
| Redisson 回调线程 | 只把结果 `logic.execute(...)` 投递回去 | 碰 `ScenePlayer` |
| scene 存储线程池 | 在线存盘 / 写回（含账本）；结局投递回逻辑线程（现有） | — |
| battle `battle-logic` | 调端口：只交任务、不阻塞 | 碰发件箱数据 |
| battle `battle-outbox`（新增，单线程） | 两个发件箱的名单与计时器；发起异步调用，回调投递回本线程；活动局的 `BattleResultSink.publish(…, ACTIVITY)` | 碰房间 |
| battle `battle-scene-sweep`（新增，守护线程，每 60 s） | 阻塞读 scene 节点目录、销毁已不在目录的节点的直连客户端（§7.15） | 碰房间与发件箱名单 |
| battle 管理 Tomcat | dev gather | 碰房间 / 发件箱 |
| xm-team 工作线程 / 推送线程 | 在请求预算内限时等战斗锁的批量读（§7.13 第 4 条） | 无限等待 |

### 7.21 Java 的失败与崩溃窗口一览

| # | 窗口 | 归属与数据 | 客户端 | 指标 |
|---|---|---|---|---|
| J1 | 战斗中 battle 崩溃 | 无结算；期限 + 10 s 后 rescue 读不到 → 判废 | 直连断开；之后冻结解除 | `freeze_expired{fighting}` |
| J2 | FinishBattle 之后、SET 落地之前崩溃 | 结算丢失（停机走有界排空时不丢） | 可能收到直连 150 但没有到账 | — |
| J3 | SET 已落地、首投之前崩溃 | 在线：rescue 应用（D21）；离线：进场恢复 | 期限 + 10 s 时到账并收 150 | `rescues{applied}` |
| J4 | 投递失败或超时 | 10 s × 12 重投 | 延迟 | `outbox{resend}` |
| J5 | scene 已应用、未落盘时崩溃 | 资产 + 账本一起丢；记录在；新实例进场恢复先于任何投递 | 重新到账一次 + 150 | `settlements{path=login,result=applied}` |
| J6 | 已落盘、销账前崩溃 | 进场时 `writeOff` → 不重复 | 不推 150 | `acks{trigger=login}` |
| J7 | 销账 Lua 失败 | 账本条目保留；落盘回调 / reaper / 进场重试；重投落到账本命中 | — | `acks{result=error}` |
| J8 | 在线存盘被围栏拒（失去归属） | 实例移除，应用丢失；记录在；新持有者进场恢复 | 2017 踢线，重登到账 | — |
| J9 | 交出在途（FREEZING）时到达 | DEFERRED；之后在目标节点应用或账本命中 | 延迟 | `settlements{deferred_frozen}` |
| J10 | 金币失败 | 在线：DEFERRED、冻结保留，期限时 rescue 仍失败 → 判废（B7）；进场恢复：RETRY、拒绝开下一局（Q18） | 延迟或作废 | `deferred_currency` |
| J11 | 销账之后迟到的重投 | HEXISTS 为假，battle 先摘；真到了 scene：无冻结、锁不在 → DISCARDED（销账是空操作）；读锁的快照早于销账（读到的仍是本局）时由已销账集合兜住，见 J24 | 无 | `discarded_void` |
| J12 | 玩家已在下一局（冻结 Y） | DISCARDED + 销账 X | 无 | `discarded_mismatch` |
| J13 | 账本满 64 | 淘汰最旧项并 ERROR；被淘汰那一局若再被投递会重复发奖 | — | `ledger_evictions` |
| J14 | 待结算记录坏字段 | 只删坏字段；锁指向它时不重建 | 无 | `pending_corrupt` |
| J15 | 读锁失败（没有冻结的分支） | DEFERRED，零副作用 | 延迟 | `deferred_lock_read` |
| J16 | scene 停服 | 写回全部玩家（带账本）；下次进场销账 | 断开 | — |
| J17 | FinishBattle 时 Redis 不可用，或落库结局不明（超时、空回复） | 只投一次，不持久。落库多半败在响应超时、命令其实已经写出：那条 EVAL 若在 scene 销账之后才落地，被墓碑挡下（回 -1），不留孤儿记录（D29）；落在销账之前则是一条正常的待结算记录，随销账删掉 | 投递丢了就丢了 | `outbox{not_durable}`（告警） |
| J18 | 进场恢复读失败 | RETRY；期间结算 DEFERRED、备战 1006 | 延迟 | `recovery{retry}` |
| J19 | rescue 读本局记录出错 | 不判废、下一轮再读；期限 + 60 s 仍出错才判废 | 冻结最多多留约 30 s | `rescues{error}` / `rescues{gave_up}` |
| J20 | `PREPARE_LOCK` 回复超时、Redisson 重发 | 脚本把同局 P 当重放成功，回 0；真超时回 1003 并尽力删锁 | 无（备战结论只给 match） | `prepares{redis_error}` |
| J21 | 发件箱条目一直探测 / 定位出错 | 10 min 后按用尽摘除，记录仍在 Redis | 延迟到 rescue / 进场恢复 | `outbox{expired}` |
| J22 | 备战节点在确认前崩溃 | 确认回落到定位器，改投玩家现在的实例（D28）；玩家离线则锁按备战 TTL 过期（B17） | 在线：收到 144 | `scene_events{rerouted}` |
| J23 | 落库的重放 / 晚到落在销账之后（Redisson 重发、not_durable 的那次、重入的 dispatch） | 墓碑挡下，脚本回 -1、不写；发件箱按已销账处理，不登记、不投递（D29）。**原稿没有墓碑时这是一个重复发奖窗口**（孤儿记录 → 下次进场恢复再应用），现已消除；残余见 §10.5 | 无 | `outbox{already_settled}` |
| J24 | 一份读（恢复读、读锁、rescue 读、迟到确认的回复）的快照早于本实例已完成的销账，回调却晚于 `forget` | 已销账集合命中：不再应用、不按锁重建，只再推一次销账（D32）。**原稿在 reaper 同一轮里「先销账、再重跑恢复」时会把已销账的局再应用一遍**（审计实测金币 200 / 期望 100），现已消除 | 无 | `settlements{already_applied}`、`rebuilds{*, ledger_hit}` |
| J25 | 逻辑线程回调中途抛意外异常，或逻辑线程拒绝投递（停服） | 进场恢复置 RETRY 由 reaper 重跑，不停在 PENDING；备战 / 按锁结算的应答异常完成（结局未知），不泄漏提供方的在途名额；取消 / 解冻的应答正常完成。**原稿下这两种情形会让恢复永久 PENDING、名额永不归还** | 延迟 | `recovery{error}`、`rpc{result=error}` |
| J26 | 进场重建的 `TOUCH(P)` 被排到 `CONFIRM` 之后（重放或 Redisson 重排） | 脚本回 2、什么都不改（不把在打的局降回备战、不把 TTL 缩回备战期，D30）；冻结按命中保留，停在 PREPARING 等下一次补发确认。**原稿的 TOUCH 会把锁改回 P** | 无 | `rebuilds{login, rebuilt}` |
| J27 | 取消 / 备战失败的删锁被排到确认之后 | 只删备战锁回 2，锁不动（D31）。**原稿用不看 `s` 的删除，会留下一个没有锁的 FIGHTING 冻结**（迟到确认已据这把锁重建） | 无 | — |
| J28 | 备战写锁期间玩家离场、写锁结局不明（出错 / 超时） | stale 分支照样尽力只删一次备战锁（脚本可能已执行、只是回复丢了）；**原稿只在「占到了」时删，结局不明时孤儿锁留到备战 TTL** | 无（这人被 match 判为备战失败） | `prepares{stale}` |

---

## 8 配置与常量

**配置**（新增）：

| 键 | 缺省 | 位置 | 说明 |
|---|---|---|---|
| `xm.scene.battle-rpc-max-inflight` | 256 | `SceneNodeProperties`（校验 ≥ 1，同 `asset-op-max-inflight`，`:93-94`、`:126-127`） | `SceneBattleService` 在途上限 |
| `xm.scene.battle.reaper-interval` | 30 s | 同上（嵌套的 `BattleSettings` 记录） | yaml 里取环境变量 `XM_SCENE_BATTLE_REAPER_INTERVAL`（缺省 30 s）；本机切片由 `tools/local/start-slice.sh` 导出 2 s 供 robot 用，生产不改。启动校验 0 < 值 ≤ 30 s（保住 `GRACE + 间隔 < LOCK_EXTRA_TTL_SEC`，§7.2） |
| `xm.battle.scene-rpc-timeout` | 5 s | `SceneTransportProperties`（与 `BattleProperties` 同前缀、分开绑定；不在 `BattleProperties` 里） | 确认 / 结算调用超时；必须大于 scene 侧一条 Redis 脚本的最坏耗时 4.2 s（§7.2、§7.3），启动时与 `RedisProperties.worstCaseCommandMillis()` 比较（`requireAbove`）、不满足拒绝启动；必须为正 |
| `xm.battle.outbox-drain-timeout` | 3 s | 同上 | 停机时等待在途落库与首投；0 合法，表示停机不等；不能为负 |

两个 `xm.battle.*` 键已写进 xm-battle 的 `application.yaml`（值等于代码缺省），文件头的 Redis 读写说明同步更新。xm-team 的战斗锁读**没有**单独的超时键：与在线读共用调用方传入的请求预算
（请求 3500 ms、推送批 3 s）。xm-data 回档闸的 `xm.data.ops.battle-lock-wait`（缺省 5 s）见 data-ops-spec。除此之外 6.3 的评审修复没有新增配置键。

端口不新增：`SceneBattleService` 用 `xm.scene.asset-rpc-port`（缺省 21100，`application.yaml:93-95`）。

**代码常量**：见 §7.2 的 `BattleRedis` 表（注释写清与谁联动）。scene 私有的还有 `PlayerBattle.WRITTEN_OFF_CAPACITY`（64）、`PlayerBattleService.MAX_DEADLINE_AHEAD_MS`（7 天）；
battle 私有的 `SceneClientSweeper.SWEEP_INTERVAL`（60 s）。

**真 Redis 测试用的库号**（约定，供后来的测试避让）：xm-discovery 与 xm-scene 的战斗脚本测试用 DB 15，xm-battle 用 DB 14，xm-team 的 `TeamDisplayRedisIntegrationTest` 沿用该模块的 DB 12
（与生产缺省库同号，只用随机 ≥ 2^63 的 id、只删自己登记的键）；都用随机大号玩家、结束时只删自己写的键。

---

## 9 指标（Micrometer；不以 player_id / battle_id / 会话 / 节点号作标签，`AGENTS.md` §5）

**scene**：

| 指标 | 标签 |
|---|---|
| `xm_scene_battle_prepares_total` | `result` = ok / invalid / not_here / switching / in_battle / not_ready / dead / lock_held / redis_error / stale / cancelled |
| `xm_scene_battle_cancels_total` | `result` = cleared / idempotent / mismatch / rejected_fighting / deferred / offline_deleted / offline_rejected_fighting / offline_absent / offline_error |
| `xm_scene_battle_confirms_total` | `result` = upgraded / idempotent / reextended / mismatch / rebuilt / frozen_extended / offline_extended / offline_miss / ledger_hit / invalid / error |
| `xm_scene_battle_rebuilds_total` | `reason` = login / late_confirm / carried；`result` = rebuilt / reverted / ledger_hit / skipped_corrupt / skipped_pending / miss / error |
| `xm_scene_battle_freeze_expired_total` | `phase` = preparing / fighting |
| `xm_scene_battle_rescues_total` | `result` = applied / already_applied / deferred / miss / error（读出错、下一轮重试）/ gave_up（期限 + 60 s 仍读不到，直接判废） |
| `xm_scene_battle_reconnect_hints_total` | `trigger` = confirm / late_confirm / login / carried |
| `xm_scene_battle_frozen`（gauge） | `state` = preparing / fighting |
| `xm_scene_battle_rpc_total` | `method`（4 个）；`result` = handled / not_here / deferred / overloaded / error |
| `xm_scene_battle_settlements_total` | `path` = online / by_lock / login / rescue；`result` = applied / already_applied / discarded_invalid / discarded_mismatch / discarded_void / deferred_frozen / deferred_currency / deferred_recovering / deferred_lock_read / deferred_ledger / not_here |
| `xm_scene_battle_acks_total` | `trigger` = apply / persisted / reaper / login / discard；`result` = released / not_ours / deferred / error |
| `xm_scene_battle_recovery_total` | `result` = ready / retry / error |
| `xm_scene_battle_items_total` | `kind` = consume_clamped / consume_rejected / drop_overflow / drop_lost |
| `xm_scene_battle_ledger_evictions_total`、`xm_scene_battle_pending_corrupt_total`、`xm_scene_battle_exp_ignored_total` | — |
| `xm_scene_battle_gate_rejects_total` | `gate` = enter_scene / skill / attribute / pet / bag_sort / asset / move / default |

现有指标的结果枚举补值：`xm_scene_moves_total{result=in_battle}`、放技能结果（`xm_scene_skill_releases_total`）补 **`caster_in_battle` / `target_in_battle` 两个取值**
（按 7004 / 7002 两个回码拆开，没有 `in_battle` 这个取值）、`xm_scene_team_follow_total{result=in_battle|battle_lock}`、`xm_scene_switch_resolves_total{result=in_battle}`、`xm_scene_channel_relocations_total{result=in_battle}`。

全部 scene 战斗计数器在 `SceneBattleMetrics` 构造时预建全部标签组合（「从没发生」与「指标不存在」分得开）。**口径**（单测已钉住）：

- `prepares`：`not_here` 含组快照取不到路由的 1011；`invalid` 含期限超界（D36）；`not_ready` 含恢复未就绪、账本未落盘、账本损坏三种（损坏另有 WARN 日志）。
- `cancels`：`mismatch` 含 player_id / battle_id 为 0。
- `confirms`：**不是「每条确认恰好计一次」**。`error` 既计确认脚本失败，也计升级 / 再续之后的续锁失败——后者与同一条确认已计的 `upgraded` / `reextended` 叠加；
  `idempotent` 含迟到确认回来时实例已换 / 已在交出冻结 / 已有别的局或同局 FIGHTING 的冻结；`upgraded` 含迟到确认回来时发现同局备战冻结而升级的那一支（D33）；
  `offline_miss` 含「离线且事件不带期限的跳过」和「交出冻结中锁不是本局」；`ledger_hit` 含本实例已为这一局发出过销账。
- `rebuilds{reason=login}`：`skipped_corrupt` 只表示坏字段；`ledger_hit` = 本轮刚应用 / 账本命中 / 锁指向账本里已有的局 / 本实例已销账的局——**离线结算后登录的常态计的是它**；
  `skipped_pending` = 记录延后或还没轮到；`rebuilt` 含复核返回 2；`reverted` = 复核没命中而撤销，也含「本节点的只删备战锁有了结局后，摘掉现任实例上按它重建的备战冻结」（§7.5 第 4 步）；
  `miss` = 恢复回来时已在交出冻结；`error` = 复核脚本失败（冻结保守保留）。更旧一代的恢复读、已失效的复核回调不计数。`reason=carried` 只有 `rebuilt`（同 epoch 沿用旧冻结）。
  「删锁有了结局后摘掉」的那种 `reverted` 一律记 `reason=login`；它若发生在复核命中之后，会与同一个冻结此前已计的 `rebuilt` 叠加。
- `reconnect_hints`：同一个冻结对象上至多一条。`trigger=carried` = 同 epoch 沿用时当场推的：换了会话的 FIGHTING，以及同会话沿用、还欠着一条 144 的 FIGHTING
  （按 F 锁重建后复核还在途，§7.8 第 0 步）；后一种情形里被丢弃的那次复核不计 `rebuilds{login,*}`，也不计 `hints{login}`。
- `settlements`：`path=by_lock` 的 `deferred_recovering` 含「读锁回来时实例已换」；`already_applied` 含「本实例已销账的局被过期读带回来」；信封与结算体玩家不符计 `{online, discarded_invalid}`。
- `acks`：`released` = 脚本删到了记录或锁，`not_ours` = 都不是本局，`deferred` = 还没落盘（压一次存盘）；`released / not_ours` 在实例核对**之前**计数，
  所以「实例已换」的那次销账照样计数，但不 `forget` 旧实例的账本（放了锁时对现任实例补一次跟随，§7.12）。
- `recovery`：`retry` = 恢复读失败，或有延后的待结算记录（后者同时计 `settlements{path=login, result=deferred_*}`）；`error` = 读回来了、处理快照时出了意外异常（正常恒为 0）。
- `gate_rejects`：每条被闸拒的请求恰好计一次，只计在自己的闸上——63 计 `enter_scene`，84 的施法者与目标都计 `skill`，属性六写计 `attribute`，宝宝六写计 `pet`，192 计 `bag_sort`，
  134 / 132 / 131 计 `move`，缺省 REJECT 计 `default`，资产 debit / credit 计 `asset`。不计闸的：ALLOW 的方法、84 的 1001（技能不存在）、192 的类型非法、资产 abort 与已见 seq 的重放、
  没有处理器的方法、交出冻结先判掉的请求。
- `frozen`（gauge）的口径见 §7.9。

**battle**：

| 指标 | 标签 |
|---|---|
| `xm_battle_settlement_outbox_total` | `event` = stored / not_durable / delivered / resend / skip_no_target / superseded / acked / exhausted / exhausted_offline / expired / probe_error / locate_error / serialize_failed / **already_settled** / **fields_overflow**（共 15 个取值，全部预建） |
| `xm_battle_settlement_outbox_entries`（gauge） | — |
| `xm_battle_settlement_delivery_total` | `result` = applied / already_applied / discarded / deferred / not_here / overloaded / transport_error |
| `xm_battle_scene_events_total`（6.2 已定义） | `kind` = confirm：`result` 增加 sent / rerouted / skipped / not_here / error（§7.16）；`kind` = settlement：`result = sent` 表示房间把这份结算交给了发件箱（落库 / 投递 / 重投的结局看上面两行） |
| `xm_battle_results_total`（6.2 已定义） | `channel` = plain / activity：活动局的首发与每次重发计 `activity`，`plain` 只统计普通局（§7.17） |
| `xm_battle_activity_result_outbox_total` | `event` = stored / not_durable / resend / acked / exhausted / expired / probe_error / serialize_failed |
| `xm_battle_dev_gather_total` | `mode` = prepare_only / create / unknown；`result` = ok / prepare_failed / create_failed / forbidden / rejected（`unknown` 与 `rejected` 见 dev gather 的实现记录） |

**battle 的口径**：`stored` 只在落库脚本真的写入时计；落库时这一局已销账（脚本回 -1）计 `already_settled`，两者互斥，也不计 `acked`；`fields_overflow` 在 `stored` 之外另计；
`delivered` 是发出次数（首投 + 每次重投），首投数 = `delivered − resend`；`probe_error` / `locate_error` 含「没有结论」（null）。

**gate**：`xm_gate_client_requests_total{result=battle_rejected}`（`route = unsupported`、`method = BattleClientPlayer.<方法名>`，12 条序列装配时预建；战斗上行不再计 `result=unsupported`，§7.19）。

**告警**：`not_durable > 0`；`exhausted`（有目标）> 0；`expired > 0`；`fields_overflow > 0`；`ledger_evictions > 0`；`drop_lost > 0`；`pending_corrupt > 0`；`recovery{error} > 0`（代码缺陷）；
`frozen{state=fighting}` 长时间不降；`recovery{retry}` 持续增长；`settlements{result=deferred_currency}` 持续出现（该玩家按 Q18 开不了战，需 GM 处置）；
gate 的 `battle_rejected` 持续增长（旧客户端或误走大厅的战斗上行）。

---

## 10 隐患与边界

### 10.1 实现必须守住的坑

1. **每一次删锁 / 续锁 / 删记录都按 battle_id 条件执行**，否则迟到的消息会碰到下一局（`combat.md:303`）。
2. **先落库、后投递**（§3.3 第 4 条）。
3. **金币入账之后不得让整笔失败**；账本登记在 finally 里；金币之前不得有任何副作用（`combat.md:315`）。
4. **销账只在 durable 之后**；`ledger.forget` 只在 ACK 结局回来之后（`ledger.h:76-88`）。
5. **锁留到落盘**：应用后先 `HOLD` 再摘冻结；ACK 与放锁同一段 Lua。
6. **PREPARING 永不推 144**；144 必须在 79 / 21 之后。
7. **uint64 → uint32 夹紧**（道具数量）；**无符号**比较 battle_id（字段排序、TreeMap、Lua 用十进制字符串比较等值）。
8. **次序不靠连接 FIFO**（§1.8）：future 链或同一段 Lua。
9. **进场恢复窗口**：恢复读回来之前冻结尚未重建，在途闸是开的（基线完整重登同样如此）。期间 63 可能成功换图（本节点内无害；远端换图由 `onSwitchTargetChosen` 复查兜住，
   复查时恢复通常已完成，没完成时目标节点据锁重建）。不把 `recovery ≠ READY` 当成在途闸，否则每次登录后的第一条 63 都要赌一次 Redis 往返。
10. **战斗闸不下沉**到 `BagService` / `CurrencyService` / `MissionService`（D48——这是基线自己的设计编号，§2.1，不是本稿 §11 的差异编号）。以后新增的扣资产入口必须声明 `BattlePolicy`（缺省 REJECT 已经兜住）。
11. **关闭后清掉引用**：发件箱条目、rescue 标记、`lockPending` 回调都要先核对实例与冻结对象。
12. **gate 永远不路由 `BattleClientPlayer`**（node-spec §10.1 第 14 条）。
13. **每段可变脚本都会被 Redisson 重放**（`retryAttempts = 1`）：新增脚本必须写明重放语义并在真 Redis 测试里连跑两次（§7.2、§13.4）。
14. **两种冻结始终互斥**：任何会挂战斗冻结的路径（迟到确认、进场恢复、沿用旧实例）都先看 FREEZING；任何会发起交出的路径（`beginRemoteSwitch`、5.5 `begin`）都先看 `inBattle()`。
15. **两条各自发出的脚本，执行次序与回调次序都没有保证**（不只是重放）：任何「读 → 据此应用 / 重建」的路径都可能拿着早于本实例销账的快照回来。
    新增的应用路径必须经 `applyAndFinish`（唯一收口），新增的重建路径必须查已销账集合；新增的恢复读必须带代际号（D32）。
16. **只该删备战锁的路径不用 `DELETE_IF_MATCH`**：取消、备战失败、stale 分支用只删备战锁的入口（D31）；不看 `s` 的删除只留给 reaper 判废。
17. **驱动丢弃 / 判废的读必须读主库**：`BattleRedis` 的脚本一律 `READ_WRITE`，不许把只读脚本改回 `READ_ONLY`；`BattleLockReader.exists` 只能用在咨询性的地方（§10.5）。
18. **逻辑线程回调不许把状态卡住**：挂着应答 future 的异步调用要给 `onLogic` 传「没跑完」的出口；进场恢复的处理要落到 RETRY 而不是停在 PENDING（§7.4）。
19. **「没问到结论」不当任何一种结论**：空回复、null 结果、null future 一律按出错处理（D15 的口径，scene 的脚本封装与 battle 的两个发件箱都是）。

### 10.2 照搬的基线怪癖（PARITY 注明）

| # | 怪癖 | 出处 |
|---|---|---|
| B1 | 已 FIGHTING 的取消被拒；Destroy 发生在确认之后时玩家冻结到期限 | `pb.cpp:1297-1306`；node-spec Q5 |
| B2 | 在线但没有冻结时收到取消：不删锁，锁最多挂到 TTL | `pb.cpp:1282-1289` |
| B3 | 登录重建出一个已过期的 PREPARING：要等下一轮 reaper（≤ 30 s）才解 | `pb.cpp:1577-1582` |
| B4 | 宝宝技能列表不过滤，进入客户端可见的 `BattleActorState` | `pet.cpp:95-109`；engine-spec `:617` |
| B5 | PREPARING 永不推 144 | `pb.cpp:2076-2079` |
| B6 | reaper 精度 30 s | `pb.h:101-102` |
| B7 | 金币入账失败（封禁 / 溢出）让**整笔**延后；在线时多半最后被判废，HP、宝宝、任务、道具一并作废 | `pb.cpp:1672-1690` |
| B8 | 经验只打日志（两版都没有经验系统） | `:1736-1742` |
| B9 | 150 从直连与大厅各来一份，客户端按 battle_id 幂等；已应用过的不再推 | `room.cpp:1162-1165`；`pb.cpp:1914`、`:1933` |
| B10 | 道具 fail-soft：两处都放不下、或被获取封禁拒绝的掉落直接丢失并 ERROR | `pb.cpp:830-834` |
| B11 | HP / MP 回写是终值；150 带的是引擎原值（未夹） | `pb.cpp:1691-1705`、`:839-846` |
| B12 | 结算回写 HP 不推任何属性消息 | `actor_attribute_calculator.cpp:46-52` |
| B13 | 离线玩家被判废的局会在登录时补发（登录不看锁） | `gap.md:326-327` |
| B14 | 在线且无冻结时，锁不符的迟到结算被丢弃（与离线「登录照样补」口径不一致） | `pb.cpp:1880-1888` |
| B15 | 自动加点 173 / 188 战斗中不闸 | `attr.cpp:675`；`pet.cpp:601` |
| B16 | 快照 buff 按表全量时长换算（Java buff 恒空，碰不到） | `pb.cpp:1035-1046` |
| B17 | 备战期间玩家断线重进、落到了别的节点（Java 断线即移除实例，所以只要备战窗口内重进就可能发生）：确认仍发往快照路由的旧节点 S1。**S1 还在**：它走离线分支只改锁（标 F、续到正式期限），新节点重建的 PREPARING 不会升级、不推 144，到期被 reaper 摘掉，之后在途闸敞开直到结算，结算按锁应用。**S1 已重启 / 下线**（实例不符）：基线确认一条都送不到，锁停在备战 TTL 后过期，玩家在这局还在打时就能被再次匹配，这局结算在线上被「冻结不符」丢弃——Java 由 D28 的定位器回落修掉在线的这一支，离线超过 180 s 的仍照基线 | node-spec §7.9；`pb.cpp:1331-1373`；`room.cpp:1107-1128` |

### 10.3 基线缺陷（Java 修）

| # | 缺陷 | 出处 | Java |
|---|---|---|---|
| F1 | 结算探测时 Redis 出错被当成已销账，重投提前停止 | `room.cpp:1776-1785` | 本轮跳过不计次（D15） |
| F2 | 重投与登录钩子赛跑、锁已过期时合法 pending 被判废删除（S-7） | `gap.md:330-331` | 进场恢复闸（D20） |
| F3 | 坏 pending 无条件 DEL 两键，可能误删读之后新写入的下一局 | `pb.cpp:2047-2053` | 只删坏字段（D13） |
| F4 | 单槽覆盖丢奖（S-9） | `gap.md:332-333` | 每局一个字段（D13） |
| F5 | 超时平局可能输给 reaper（S-8）；battle 落库后首投前崩溃被判废（B-3） | `gap.md:328-329`；`pb.cpp:2153-2158` | 宽限 + rescue（D21，客户端可见，两版同改为可选） |
| F6 | 备战写锁失败照常成功，锁缺失期间可开第二局 | `pb.cpp:1187-1222` | fail-closed（D3） |

### 10.4 跨批次的数值依赖

- 确认补发窗口 180 s ≥ match 最长 matched TTL 96 s + `LOCK_EXTRA_TTL_SEC` 60 s（node-spec §10.4）：6.2 的单测 `ConfirmWindowConstraintTest` 已改为引用 `BattleRedis.LOCK_EXTRA_TTL_SEC`
  （match 的 96 s 仍是字面值，等 6.4 有了 match 的常量再换）。实现时另钉一条更严的：计次实现下 +180 s 那次只停表、不发，**最后一次实际补发在 170 s**，它同样要 ≥ 156 s——
  实际余量是 14 s，不是标称的 24 s（基线同样如此，`room.cpp:1115`）；`LOCK_EXTRA_TTL_SEC` 或 match 最长 matched TTL 合计再涨超过 14 s 时这条用例会失败。
- `LOCK_HOLD_AFTER_APPLY_SEC`（180）≥ `SETTLEMENT_RETRY_INTERVAL × MAX + 60`（单测钉住）。
- `FIGHTING_EXPIRY_GRACE`（10 s）+ reaper 间隔（≤ 30 s）< `LOCK_EXTRA_TTL_SEC`（60 s）：第一次 rescue 最晚发生在期限 + 40 s，锁一定还在（单测钉住；reaper 间隔配置启动校验 ≤ 30 s）。
- battle → scene 的确认 / 结算调用超时（`xm.battle.scene-rpc-timeout`）与 dev gather 的单次 scene 调用 > scene 侧一条脚本最坏耗时 4.2 s（`RedisProperties.java:14`）；改 `xm.redis.*` 时一起核。
  **match 的备战 / 取消调用不受这条约束（裁决，2026-10-06）**：保持 3 s，超时按结局不明处理并补发取消（§7.3）；match-spec 的数值表与跨进程不等式整表不动。
- `SETTLED_TOMBSTONE_TTL_SEC`（600 s）≥ `OUTBOX_MAX_AGE`（10 min）：发件箱里一份结算从登记到摘除的最长寿命内，销账之后的落库都被墓碑挡住（单测钉住）。
- `MAX_DEADLINE_AHEAD_MS`（7 天）= 待结算记录的保留期：对应的锁 TTL 是 604 860 s，EXPIRE 不会报错；正常对局的期限（集合起点 + 300 s）远小于它。
- **D13 的「按 battle_id 升序 = 时间顺序」依赖 battle_id 生成器按时间递增**：基线是 match 节点的 snowflake（`gather.go:83`、`:202-209`；切磋 `challengelogic.go:105` 同源）。
  6.4 的 Java match 必须用时间在高位的雪花号；dev gather 由调用方给 battle_id，robot 必须按时间递增取号（§13.8 的 X1 < X2 < …）。
- 房间期限 = 确认里的 `deadline_ms` = scene 冻结的正式期限 = 票据寿命（node-spec §10.4）。
- `SETTLEMENT_TTL_SEC` 两端同值（同一常量）。

### 10.5 已知取舍与残余风险

- **账本满 64**（J13）：被淘汰的那一局若再被投递会重复发奖；只在 Redis 长期不可用时出现（`ledger.h:23-32`）。
- **Redis 实例丢数据**会丢掉在途结算与锁：部署要求 Redis 开 AOF（everysec），写进 7.6 的部署文档。
- **B17**：罕见，照搬；若要修见 Q22。
- **迟到确认在 FREEZING 时不挂冻结**：交出成功时由目标节点按锁重建；但交出没提交、原地解冻（`SceneWorld.unfreezeInPlace`）时，源节点上的玩家没有内存冻结而锁已是 F，
  只能等下一次确认补发（窗口内）才重建。所以原地解冻之后**重跑一次完整的进场恢复**（第 1 步起，D34；原稿写的是只补「锁」步骤，§7.8 末段说明了为什么做成超集），不依赖补发是否还在窗口内。
  代价是一次往返内 `recovery = PENDING`（备战 1006、结算 DEFERRED）。
- **dev gather** 不是新的发奖口子：dev 本来就有 GM 加币和发宝宝；prod 403。
- **备战写锁晚到**：`PREPARE_LOCK` 超时后 scene 回 1003 并尽力只删一次备战锁，但 Redisson 的重发或服务端排队里的那次执行可能落在删除之后，锁会留到备战 TTL（最长 96 + 60 s），
  这段时间该玩家排不了队。只在 Redis 抖动时出现，可接受；不为它加第二轮清理。「备战 → 取消删锁 → 写锁的重放 / 晚到」同理会把锁重新写出来，按备战 TTL 过期。
- **落库晚到已由墓碑消除，剩两处不保护**（D29）：① 墓碑 10 min 过期之后才落地的落库（远大于 Redisson 的 4.2 s 最坏耗时与发件箱 130 s 的重投窗口，实际不可达）；
  ② reaper 判废（`DELETE_IF_MATCH`）与删坏字段不写墓碑——判废之后晚到的结算照常落库，由进场恢复应用（B13，与原有语义一致）。
- **`BattleLockReader.exists / existsAll` 是咨询性的读，上主从 / 集群前有部署约束**：它走 Redisson 的普通读路由，主从部署下可能读到从库的旧值。队伍视图、组队跟随、6.4 的 match 预检
  读到旧值只是显示或排队判断晚一拍；但 **xm-data 的回档闸是安全门**，读到从库的「没有锁」会放行一次本该拒绝的回档。现在是单机 Redis，没有影响。
  上主从 / 集群之前必须二选一：Redisson 的 `readMode` 设 `MASTER`，或给回档闸一个读主库的批量入口（写进 architecture 的部署约束与 PARITY 残余风险）。
  同类的还有 battle 发件箱每轮定位用的 `PlayerLocationDirectory.findHolderAsync`（`READ_ONLY`）：读到过期位置最坏是多一轮 NOT_HERE 或 skip_no_target，下一轮纠正；全仓其余 `READ_ONLY` 的读届时一并审查。
- **`TOUCH` 返回 2 后冻结停在 PREPARING**（B17「S1 还在」一支）：之后没有补发确认到达本实例的话，冻结到备战期限被 reaper 摘掉，在途闸敞开到结算，结算仍按锁应用。裁决为不改（最小修法）。
- **没有锁的 PREPARING 冻结**（有界）：备战写锁或取消删锁，与同 epoch 重进的恢复读交错时，新实例可能按锁重建出一个随后失去锁的 PREPARING 冻结。删锁有了结局时本节点会把现任实例上
  按这把锁重建出来的备战冻结一并摘掉（§7.5 第 4 步、§7.6）；删除在 Redis 上先于恢复读执行时读不到锁、不重建，排在恢复读与复核之间时由复核落空撤销。剩下的只有一种：删锁出错且脚本确实没执行、
  而新实例的恢复读又晚于删锁回调才回来——新实例仍会按那把留到 TTL 的锁重建备战冻结，到备战期限由 reaper 摘。
- **「结局不明」按摘处理的反面**：只删备战锁其实该回 2（锁已标 F）、但回复两次都丢时，会按结局不明摘掉一个在打的局的 PREPARING 重建冻结；下一次补发确认会按锁重建 FIGHTING 并推 144，有界。
- **删锁 / 销账被 Redisson 重放且实例已换时，补给现任实例的那次组队跟随会丢**：首发已删（已放）、重发回 0，回调只看到 0。由下一次跟随事件兜住；影响只是归队晚一拍（§7.2、§7.6）。
- **沿用一个复核还在途的按锁重建冻结时，新实例不再复核**：同 epoch 重进时旧实例的 `TOUCH` 回调被丢弃，新实例已有冻结、不会再发复核。那次复核本来会落空的话，新实例带着一把没有锁的冻结
  （FIGHTING 的 144 已在沿用时推出）直到期限加宽限由 reaper 判废，PREPARING 的到备战期限。换会话沿用早就是这个行为；要收口得给 `BattleFreeze` 加「复核在途」状态，或对这种冻结不沿用，没有做。
- **同一实例对同一局先后两次备战**：第一次写锁的过期回调（stale 分支）会删掉第二次占到的锁。`preparedHere` 守卫保证不摘第二次的冻结，但那把锁确实被删了，冻结到备战期限由 reaper 摘。
  match 不会对同一局重复备战（`gather.go:236-255`），dev gather 理论上可以；登记，不处理。
- **确认事件的期限没有上界**（D36 只管备战）：天文数字的 `deadline_ms` 会让 `CONFIRM` 的 EXPIRE 报错（锁原有的 TTL 还在），内存里的冻结升级成一个到不了的期限，要等结算或重登。
  battle 的确认期限来自房间期限，现实不可达；登记，不处理。
- **帧外推没有跳过交出冻结（FREEZING）的玩家**：基线 `movement.cpp:49-52` 同一行也排除了 `PlayerFrozenComp`。Java 在冻结那一步已清零速度、之后的移动上行被冻结闸丢弃，结果相同；
  第二轮修正只加了战斗在途的跳过（§7.13 世界内部第 6 条）。
- **rescue 与进场恢复的交错**（§7.9 与 B14 之间的张力，非 6.3 的评审修复引入）：rescue 读到有效记录但 `recovery ≠ READY` 时照规格判废删锁、记录保留。若更早发出的恢复读快照早于落库、
  且 rescue 回调先于它回来，那次恢复读带不回这条记录；之后的重投会被「锁不在」丢弃并销账，这一局的奖励丢失。窗口要求恢复读在途恰好跨过期限 + 10 s，极窄；登记，不处理。
- **账本损坏（D24）的组合**：这名玩家收到迟到确认仍会按锁重建 FIGHTING；到期后 rescue 读到记录、应用得 `deferred_ledger` → 判废删锁，记录留在 Redis；reaper 不销账、不压存盘。等 GM 修复（Q23）。
- **迟到确认在「事件 `deadline_ms = 0` 且锁里没有 `d`」时**会重建出期限为 0 的 FIGHTING 冻结，下一轮 reaper 直接判废。`PREPARE_LOCK` 总写 `d`、battle 的确认总带期限，现实不可达，未处理。
  两条迟到确认都在途且事件期限不同（锁的 `d` 是后执行那条的，冻结期限是先回来那条的）同理：battle 的补发期限恒相同。
- **写锁在途（`lockPending`）时确认到达**：match 要等全员备战成功才建房，正常不可达。代码上此时会升级为 FIGHTING，若随后写锁以出错完成，这个 FIGHTING 冻结会被按写锁失败摘掉（只是读代码的结论，没有用例）。
- **已销账集合满 64**：先进先出淘汰最早的；被淘汰的那一局的销账早已落地，过期读不会迟到这么久。稳态 0~1 个。
- **`lockTtlSec` 用有符号相减**，基线是无符号比较：期限 ≥ 2^63 的垃圾值两版结果不同；Java 的备战入口已按无符号把这类值拒在 1005（D36），正常的毫秒时间戳完全一致。
- **活动结果通道停机不排空**：`SceneTransport.close` 只有界排空结算发件箱；停机瞬间打完的活动局可能既没落库也没发布。整条通道两版现在都不可达（Q11），6.4 / 4.6 接上真实消费方后再裁决。
- **永久性的结算延后**：`gold_gain > Long.MAX_VALUE`（只可能是坏数据）、钱包溢出、长期 GM 封禁会让这条记录一直 DEFERRED。按 Q18，进场恢复停在它上面、该玩家备战一律 1006，
  直到记录 7 天过期（每次新写会刷新整键 TTL，但备战被拒就不会有新写）。告警 `deferred_currency` + `recovery{retry}`，处置入口随 7.4 的 GM 工具（删掉该字段或解封）。基线同样会无限重投这类记录，只是不拦下一局。

### 10.6 分区稿之间的分歧与裁决（都回到代码核对过）

| # | 分歧 | 分区稿说法 | 裁决与依据 |
|---|---|---|---|
| 1 | 锁与 ctx 的形态 | ① 锁为 String、ctx 为 Hash，两键；② 沿用①；③ 合成一个 Hash | **一个 Hash**（D2）：Go 读者只 EXISTS（`pc.go:112-121`）或 MGET（`presence.go:92`，Java 读者统一走 `BattleLockReader`，不移植 MGET 写法）；两键并存正是 `combat.md:303`「ctx 必须与锁同命」要防的 |
| 2 | 待结算记录 | ② 保持单槽（理由：多条要定义应用顺序，HP 是终值）；③ 每局一个字段 | **每局一个字段**（D13）：基线文档自认这是「彻底修法」，只因两端 key 是跨语言契约才没做（`gap.md:332-333`），Java 的键是内部的；顺序问题用「按 battle_id 升序、遇延后即停」解决（雪花号即时间序，同一玩家两局至少隔一整局时长）；再加备战闸保证正常路径上至多一条 |
| 3 | 备战写锁语义 | ① 不存在或等于本局才写，Redis 出错回 1003；③ NX，Redis 出错回 1006 | **NX，但「同局且仍是 P」按重放成功**（评审修订：原裁决「同局也拒」在 Redisson 重发下会把自己写的锁当成被占，§7.2 重放语义；同实例同局重复备战仍由内存冻结判 1006）；同局已 F、别的局 → 拒绝；Redis 出错 / 超时回 **1003**（语义更准；两码对客户端都不可见） |
| 4 | 备战时停步 | ① 停（stopMotion + 脏位）；③ 不停，照基线跳过积分 | **停**（D5）：与 5.2 冻结（`SceneWorld.java:970-973`）、Java 挂机停推（`:1326-1330`）同一做法；基线自己在 131 的注释里承认「战后带着旧速度继续飘」是问题（`mvh.cpp:185-187`） |
| 5 | 已 FIGHTING 时的重复确认 | ① 每次再续期；③ 零 Redis 幂等 | **只在之前的续期没成功时再发**（D9，`lockExtended`）：正常路径零开销，异常路径自愈 |
| 6 | 重建后的复核 | ① 两条重建路径都复核；③ 都不复核 | **迟到确认不复核**（一段 Lua，D10）；**登录重建保留复核**（读与写之间可能有别的节点的离线取消删锁，`pb.cpp:1584-1591` 同理） |
| 7 | RPC 信封 | ① `{target_instance_id, body}` + `{OK, NOT_HERE}`；② `SettlementDelivery` + 7 值结局；③ `SceneBattleCall` + `{HANDLED, NOT_HERE, RETRY}` | 通用信封 + `{HANDLED, NOT_HERE, DEFERRED, OVERLOADED}` + 结算专用 `SettlementDisposition`（§7.3）；方法名取基线名；过载用类型化结局（同 6.2 `NOT_ALLOCATABLE` 的理由：保证零副作用，调用方不必补偿） |
| 8 | 提供方类名 | ① 保留并加一个服务；③ 改名 `SceneRpcServer`、回写池改名 | 类改名，**配置键 / Dubbo 应用名 / 回写执行器名不改**（执行器名是指标标签，`SceneMetrics.java:72`） |
| 9 | 离线结算的「已取代」判定 | ② battle 在无目标那轮跑 `ACK_IF_SUPERSEDED`；③ scene 收到投递时跑 `SUPERSEDE` 再回 NOT_HERE | **battle 侧**（D17）：首投也走定位器，离线玩家的正常信号是 NoHolder，scene 收不到这类投递 |
| 10 | 首投目标 | ② 位置记录；③ 快照路由 + 目录实例比对 | **位置记录**（D14）：一条代码路径；快照路由在 scene 重启或换节点后会过时；多一次 Redis 往返 |
| 11 | 最终写回之后的销账 | ② 存储线程在写回落地后 ACK；③ 不做，下次进场销账 | **6.3 不做**（Q10）：收敛已由进场恢复保证；要改 `PlayerRepository.save` 的契约（5.2 正在改它），收益只是锁与重投早收尾几十秒 |
| 12 | FIGHTING 判废 | ② 判废前 rescue；③ 加 10 s 宽限 | **两个都做**（D21）：宽限挡住「SET 在途 + 钟差」，rescue 挡住「battle 落库后崩溃」 |
| 13 | 发件箱线程 | ② `battle-logic`；③ 独立 `battle-outbox` | **独立线程**（D25）：不与直连 I/O 争线程；停机时可以在逻辑线程停后有界排空 |
| 14 | 停机排空上限 | ② 5 s；③ 3 s | **3 s**：6.2 停机已有 1 s 刷新 + 1.5 s Dubbo 等待，总停机时长要可控 |
| 15 | 恢复钩子的位置 | ② `teamFollow.onEnteredScene` 之后；③ 之前 | **之后**：同基线（组队 3.5 步在战斗 6 步之前，`lc.cpp:1706-1734`）；跟随链自己读锁，不依赖先后 |
| 16 | 在途闸的形态 | ① 逐系统判 + 矩阵测试；③ `BattlePolicy` 缺省 REJECT | **两者都要**（D11）：声明式缺省拒绝兜住 `combat.md:327` 的隐患，矩阵测试钉住现有方法的码 |
| 17 | 「已应用待销账」的表示 | ② 看账本是否 durable；③ 另设 `lockHeldBattleId` | **只看账本**：备战闸用「账本有未落盘条目」；跟随链读锁（同基线 `team.cpp:378-402`）；不另设字段 |
| 18 | dev 接口 | ① `gather`（DEV_GATHER）；② `create-settling`（DEV_SETTLING）；③ `gather` 带 PREPARE_ONLY / CREATE | 采纳 ③ 的形态（§7.18） |
| 19 | `DEV` 房间是否还发确认 | ③ 不发（修订 node-spec §7.12） | **照发，不改 6.2**（Q12）：scene 按锁匹配，dev 房间零副作用；少改一处正在实现的批次 |
| 20 | 5.5 疏散 | ① 允许交出；③ 跳过等结算 | 回到代码：基线 SceneDrain / 疏散**不看战斗**，照常退出并改派（`lc.cpp:2149-2190`、`:2225`）。并行的 5.5 规格稿已选「跳过、到期写回」（`scene-drain-spec.md` Q16），6.3 两种都能接住；推荐接受 5.5 的选择（Q17），差异并入 D26 |
| 21 | reaper 扫描 | ③ 只扫索引 | **遍历全部在场玩家**：开销可忽略，少一个要维护一致性的索引（YAGNI） |
| 22 | 结算复活的入参 | ② `reviveIfDead(0, mana, …)`（`is_dead` 时强制复活） | 传**夹后的真实气血**：基线 `ReviveBaseAttributesIfDead` 只在 `health == 0` 时回满（`player_database_loader.cpp:77-82`） |
| 23 | 行号 / 出处更正 | ③ 引 `ledger.h:183`、`:197-225`；② 引 `battle_event.proto:40-45`；③ 引 `AssetOpService.java:207`；① 引 engine-spec「§9.7 `:1297`」；① 说 `PlayerMapper` 查询要加列 | `ledger.h` 只有 90 行，规则在 `:21-88`；`BattleSettlementEvent` 在 `:13-15`；第 7 步桩在 `:230`；`:1297` 属 §10.1；`PlayerMapper.java:36` 是 `SELECT *`、`PlayerRow` 已有 `name`，只改 `toData` |

### 10.7 对 inventory（`combat.md`）的勘误与补充

1. **`:319`（in-battle-gates）**：宝宝写闸漏了 187 GmGrantPet（`pet.cpp:689-693`）。
2. **`:317-327`**：漏列组队跟随闸（`team.cpp:350-406`，含读锁 fail-closed）、GM 回档闸（`rbh.cpp:72`）与解冻后补跟随；补充「SceneDrain 不看战斗」（`lc.cpp:2225`）。
3. **`:298`**：「frozen for cross-zone travel → 1006」实际判的是 `PlayerFrozenComp`，同 zone 跨节点交接同样挂它。
4. **`:303`**：「Rebuilds must be checked again after the round trip」——Java 的迟到确认用一段 Lua 免掉了复核，登录重建仍复核（D10）。
5. **`:312`**：「Java has no currency, bag, mission or HP systems yet」已过时（2.1 / 2.4 / 2.5 / 2.7 / 2.8 均已完成）。
6. **`:274`**：补充——skip 轮也计次（`room.cpp:1810-1811`）；「up to 12 times」准确地说是 12 次重投、第 13 轮判用尽（次数在判定之后才加）；探测出错被当成已销账（`:1776-1785`）；活动结果探测反过来把出错当「仍在」，且只在重发时计次（`:2014`）。
7. **`:336`**：「done」成立，但撰稿时拒绝位置在热关停与待处理队列之后；6.3 已改到基线位置（D12，§7.19）。
   另：基线 gate 的 `targetNodeType` 来自 protogen 的三级回落（服务名 → package → 目录名），不是方法的 `OptionFileDefaultNode`（§2.5）。
8. **`:346`**：补充——缺表行 / 缺池时结算回写静默跳过；阵亡回满时法力回到上限（上限 0 则为 0）。
9. **`guild.md:350`**：确认 Go 侧没有 `activity_result` 的读者，基线通道只有写方；帮会也没有 `battle:lock` 的生产读者（`rules.go:391` 只是注释），活动开局读锁在 match。

---

## 11 建议的有意差异（PARITY 候选）

| # | 差异 | 基线 | Java | 理由 | 客户端可见？ | 两版同改？ |
|---|---|---|---|---|---|---|
| D1 | battle ↔ scene 传输 | Kafka scene-cmd + gRPC `SceneNodeGrpc` | Dubbo `SceneBattleService`，挂资产 RPC 端口 | Java 节点间命令不走 Kafka（`arch` §4.3）；按节点直连、异步 | 否 | 否 |
| D2 | 锁与上下文形态 | 两个 String（ctx 是 protobuf） | 一个 Hash；离线取消 / 确认 / 迟到确认各一段 Lua；键带 `{pid}` 标签 | 同生共死由结构保证；去掉先读后写窗口；与待结算记录同槽 | 否 | 否 |
| D3 | 备战写锁 | 无条件 SET，失败也照常成功 | 独占（NX，同局仍 P 按 Redisson 重放成功）且写成功才回 0；被占 1006；Redis 出错 / 超时 1003 | 实例活不过断线，锁是跨断线的唯一证据（`PARITY.md:91`）；资产路径 fail-closed | 仅故障路径（开局失败） | 可选 |
| D4 | 备战额外拒绝 | 只判冻结 / 已在战 / 0 血 | 另拒 RESOLVING、进场恢复未完成、账本有未落盘条目、账本损坏 | 与 5.2 互斥（`handoff-spec:106`、`mirror-spec:121`）；本地权威的结算串行化 | 仅竞态（≤ 4 s）：被 match 判为备战失败而不是换图失败 | 否 |
| D5 | 备战即停步 | 保留速度、只停积分 | `stopMotion` + 速度脏位 | 与 5.2 冻结、挂机停推同一做法；战后不漂 | **是**：旁观者收一条速度 0 的 66 | 可选 |
| D6 | 重连 | 实体存活时组件仍在，按 RECONNECT / REPLACE 补推 144 | 每次进场从 Redis 恢复；同 epoch 沿用旧实例时连同冻结沿用 | Java 进场模型（`PARITY.md:91`） | 否（144 时机等价） | 否 |
| D7 | 是否补推 144 | 比会话号（备战时记下的会话号 ≠ 当前会话号才推，推完摘记录） | `preparedHere`（「这一局的 144 不必再推」）：本实例备战的为真；按锁重建的为假、第一次推后置真；同 epoch 沿用旧实例时会话没变照抄、会话变了置假。全部推送经 `pushReconnectOnce`，同一冻结对象上恰好一次 | 一个实例只对应一个会话；同会话重复进场时与基线同样不多推 | 否 | 否 |
| D8 | 快照细节 | 成长属性存档；buff 换算；gate 实例可能为空 | 成长属性取职业行；buff 恒空；gate 实例恒有值；名字取 `player.name` | `PARITY.md:79` ①、`:80` | 否（现表数值相同） | 否 |
| D9 | 已 FIGHTING 的补发确认 | 幂等忽略 | 首次续期未成功时再发一次 | 自愈；否则锁停在备战 TTL、早于战斗结束过期 | 否 | 否 |
| D10 | 重建复核 | 两条路径都两次往返 | 迟到确认一段 Lua；登录重建保留复核（复核脚本单调，见 D30） | 单键原子 | 否 | 否 |
| D11 | 战斗闸的缺省 | 每个系统自己判 | `BattlePolicy` 显式声明，缺省 REJECT 1005 | 新入口默认 fail-closed（`combat.md:327`） | 否（现有方法不变） | 否 |
| D12 | gate 拒绝战斗上行的位置与判据 | GM 闸之后、分派之前，当场回；按 `targetNodeType == BattleNodeService` 判（protogen 的服务名 → package → 目录名回落） | 位置同基线（6.3 已落地：`MessageRoute.directOnly`，GM 闸之后、热关停之前当场推 23 `{1003}`，不进队列；6.3 之前排在热关停与待处理队列之后）；判据按服务裸名 `BattleClientPlayer`（现行契约上与基线圈出同一组 12 个号，单测守等价，§7.19）；计 `battle_rejected`、不再计 `unsupported` | 回到基线时序；Java 不许依赖 proto 目录（`AGENTS.md` §1） | 6.3 之后与基线相同（之前的边角：热关停时回包形状、login 在途时 tip 到达时刻） | 否 |
| D13 | 待结算记录 | 每人单槽、无条件覆盖；坏记录无条件 DEL | 每局一个 Hash 字段；进场按 battle_id 升序应用、遇延后即停；坏字段单独删（按字段名原始字节）；字段名只认规范的无符号十进制，`"05"` 这类写法按坏字段删（§7.2） | 消除覆盖丢奖（`gap.md:332-333`）；销账按规范串删不到非规范字段，当合法记录应用会每次进场重发 | 仅故障路径：以前被覆盖的那一局能到账 | 可选 |
| D14 | 结算寻址 | 首投按快照路由；重投节点号、实例留空 | 首投与重投都按位置记录 + 目录，带实例 id 围栏 | 一条路径；保住实例围栏 | 否 | 否 |
| D15 | 探测出错 / 没问到结论 | 结算：当已销账；活动结果：当仍在 | 一律本轮跳过、不计次。实现把同一口径推到底：回整数的脚本得到空回复一律异常完成（含 `PREPARE_LOCK`）；battle 两个发件箱的探测 / 定位 / 落库结果为 null、端口返回 null future 都按出错（`probe_error` / `locate_error` / `not_durable`） | 基线缺陷 F1，两通道统一；没问到结论不能当成任何一种结论 | 否（只影响延迟） | 可选 |
| D16 | 投递应答 | 无 | 类型化应答，只计数 | 销账仍以落盘为准 | 否 | 否 |
| D17 | 离线结算分支 | scene 锁匹配时重写 pending；锁属于别的局 → 销账 | scene 回 NOT_HERE；「已取代」由 battle 无目标那轮的 Lua 判（删记录时同样留已销账墓碑，D29） | battle 恒先落库，不存在老版本灰度（`pb.cpp:453-455`） | 否 | 否 |
| D18 | 幂等基底 | 进程内应用缓存 + 实体账本 + 落盘快照 | 持久账本 + 不重入断言 | 同步应用没有在途；缓存的跨实体快路径被基线自己判为有害（`pb.cpp:1650-1659`） | 否 | 否 |
| D19 | 快路径 | 同连接 PING | 在线存盘落地回调 | 结论来自真实结局 | 否 | 否 |
| D20 | 进场恢复闸 | 无闸；Redis 断开就跳过这次 | 恢复前结算 DEFERRED、备战 1006；读失败与延后的记录由 reaper 重试 | 修 F2 | 仅故障路径（不丢奖） | 可选 |
| D21 | FIGHTING 判废 | `deadline < now` 即判废 | 期限 + 10 s 宽限；判废前读本局记录、有就应用 | 修 F5（B-3、S-8） | **是**（故障路径）：作废变成正常结算 + 150；battle 崩溃时冻结最多晚 10 s 解除 | 可选（需用户同意改 mmorpg） |
| D22 | 消耗抽取顺序 | entt 视图序（未规定） | 格子号升序 | 确定性 | **是**：191 里哪一堆变少（总量相同） | 否 |
| D23 | 流水原因 | 金币 `TX_CURRENCY_ADD`、关联号 0；掉落 `TX_ITEM_AWARD`；消耗 `TX_ITEM_DESTROY` | 金币 `TX_BATTLE_REWARD(1005)`、关联号 battle_id；其余同 | Java 按原因分开记的惯例（`AssetAudit.java:25-32`） | 否 | 否 |
| D24 | 账本格式与校验 | 无序、不校验 | battle_id 升序写出；加载校验，损坏时该玩家 fail-closed | 脏比对；同资产通道账本 | 仅损坏时 | 否 |
| D25 | battle 发件箱线程与停机 | 与房间同 loop；停机立即退出 | 独立单线程；停机有界等待在途落库与首投（≤ 3 s） | 缩小 B-2 | 否 | 否 |
| D26 | 排空 / 级联 / 疏散改派遇到战斗中的玩家 | SceneDrain / 疏散照常退出并改派 | 跳过，下次推进再看（5.1 / 5.3 规格已写定；5.5 到期写回，Q17） | 交出与战斗互斥的不变量保持简单；写回后由下一个持有者按锁重建 | **是**：战斗中的玩家留在排空频道直到结算 | 否 |
| D27 | dev gather 管理口 | 无 | dev / test 专用；`DEV_GATHER` 房间照常结算 | 6.4 之前验收 6.3 | prod 不可用 | 否 |
| D28 | 确认的投递目标 | 只发快照路由的 `(node, instance)`，实例不符就被丢弃 | 实例相符照旧；不符（备战节点已重启 / 下线）回落到定位器，Found 才发（§7.16） | 修 B17 的崩溃变体：否则锁按备战 TTL 过期，玩家会在这局还在打时被再次匹配、这局结算被丢弃 | **是**（仅故障路径）：备战节点崩溃后已重进的玩家能收到 144、回到这一局 | 可选 |
| D29 | 已销账墓碑 | 没有：销账只删 pending 两键与锁。靠「先落库、后投递」的次序防孤儿记录；Redis 不可用时只投一次、不落库 | 销账（scene 的 `ACK`，以及 battle 的 `ACK_IF_SUPERSEDED` 删记录的分支）在同一段 Lua 里写 `xm:battle:{pid}:settled:<battle_id>`（TTL 600 s ≥ 发件箱条目最长寿命）；落库脚本见到它就不写、回 -1，发件箱按已销账处理（不登记、不投递，计 `already_settled`） | Java 的落库多半败在响应超时、命令其实已写出，且 Redisson 会原样重发：迟到 / 被重放的落库若落在销账之后，会把记录重新造出来，而账本此时已 forget → 下次进场恢复重复发奖（审计 RDS-6 / OBX-7）。基线不重发 EVAL，只剩「回复丢了而命令已执行」那一种来路，窗口更窄 | 否 | 可选 |
| D30 | 重建复核（TOUCH）单调 | `ConfirmRebuiltFreeze` 只比锁值：匹配就覆写 ctx（连同阶段）并按入参续 TTL，不看 ctx 里已有的阶段（`pb.cpp:540-579`、`:179-182`）；同一连接上靠 FIFO 保序 | 锁上已是 F 而入参是 P → 什么都不改、返回 2；scene 按命中保留冻结（停在 PREPARING，等下一次补发确认升级） | Redisson 的重发 / 重排（以及别的节点的离线确认）会让登录重建的 `TOUCH(P)` 落到 `CONFIRM` 之后，把在打的局降回备战、把 TTL 缩回备战期（审计 RDS-7 / FRZ-5） | 否 | 可选（基线跨节点同样可能把 ctx 写回 PREPARING） |
| D31 | 只删备战锁 | 取消 / `ClearBattleFreeze` 的条件删锁只比锁值（`kDeleteLockIfMatchScript`，`pb.cpp:174-176`），不看阶段；同一连接上靠 FIFO 保证删除不会落到确认之后 | 在线取消、延后取消、备战失败 / 回调异常后的尽力删锁、stale 分支用「`b == X` 且 `s ≠ F` 才删」（与离线取消同一段脚本）；reaper 判废仍用不看 `s` 的删除。stale 分支在写锁结局不明时也删 | 删除被排到确认之后时锁已标 F、迟到确认已据此重建 FIGHTING，再删就留下没有锁的在打冻结（审计 FRZ-7 / FRZ-6） | 否 | 否 |
| D32 | 过期读的防线：恢复代际 + 本实例已销账集合 | 没有：销账 / 续锁排在读锁之前靠同连接 FIFO（§1.8 第 2 条）；reaper 第二遍对所有带账本条目的玩家销账 | 进场恢复读带代际号，回调只认最新一代；发出 `ACK` 的局记进本实例的已销账集合（64，FIFO），之后任何一条读把它带回来都按已应用处理、不再应用、不按锁重建；reaper 只对 `recovery == READY` 的玩家排空账本，RETRY 只重启恢复 | 两条脚本的执行次序与回调次序都没有保证：快照早于销账而回调晚于 `forget` 会把已销账的局再应用一遍（审计 FRZ-1，critical） | 仅故障路径：本实例「丢弃并销账」而 ACK 失败的局，同一实例重跑恢复时保持丢弃（新实例上仍按 B13 补发，§7.10 第 9 步） | 否 |
| D33 | 迟到确认遇到同局备战冻结 | 迟到确认回来时已有组件就放弃（`pb.cpp:1466-1479`），等下一次补发 | 已有同一局的 PREPARING 冻结（非写锁在途）→ 按正常确认升级并推 144。附带的 Java 内部处理（不是行为差异）：事件 `deadline_ms = 0` 时那段 `CONFIRM` 不续期，重建 / 升级后不置 `lockExtended`，立即按锁里的期限补续一次——基线由 `ConfirmRebuiltFreeze` 的第二次往返续期，Java 的 D10 省掉了那次往返（审计 RDS-9） | 进场恢复按更早的快照重建出 PREPARING 时，锁已是 F、内存却停在 PREPARING，这一次可能已是最后一次补发（审计 FRZ-5） | 仅竞态：144 早一个补发周期到 | 可选 |
| D34 | 交出冻结期间的确认；原地解冻 | 交接冻结（`PlayerFrozenComp`）不影响确认：迟到确认照样在冻结实体上重建 `InBattleComp` | FREEZING 时确认只标锁、不挂冻结；进场恢复回来时已在 FREEZING 也不挂。交出提交 → 目标节点进场恢复重建；交出没提交 → 原地解冻后重跑**完整**的进场恢复（不是只补锁步骤） | 冻结中玩家的可变状态必须与冻结快照一致（handoff-spec §5.9），两种冻结始终互斥；重跑全流程才有 RETRY 兜底，并顺手补上冻结期间延后的结算与没 forget 的账本条目（审计 FRZ-9 / GAT-13） | 否（144 在解冻或落到目标节点之后到） | 否 |
| D35 | 结算道具数量的累加 | 同一配置的多条数量夹到 uint32 后 `+=`，两条大数会回绕（`pb.cpp:734`、`:782`） | 夹到 `0xFFFFFFFF` 后累加仍饱和在 `0xFFFFFFFF` | 回绕是溢出缺陷；只在坏数据下可见 | 仅坏数据 | 可选 |
| D36 | 备战期限的上界 | 只拒 0 | `deadline_ms` / `prepare_deadline_ms` 比现在晚超过 7 天（无符号比较）→ 1005，零冻结 | Java 的写锁是 HSET + EXPIRE 一段 Lua：期限离谱时 EXPIRE 报错、脚本中止，留下没有 TTL 的锁（审计 RDS 的遗留，第二轮修正）。基线两条 `SET … EX` 各自失败、不留无 TTL 的键 | 否（码只给 match；正常期限 = 集合起点 + 300 s） | 否 |

---

## 12 开放问题（每条附推荐答案）

> **落地结论（2026-10-06）**：属于 6.3 范围的各条都按推荐答案落地（Q9 的 gate 位置已做，Q12 的 dev gather 已做，Q22 只做了回落）；推荐「不做」的（Q10、Q13、Q24）没有做。
> 归别的批次的没有动：Q17 由 5.5 定，Q21 随 6.4，Q18 / Q23 的 GM 处置入口随 7.4。涉及改 mmorpg 的「可选待做」都只登记、未动 mmorpg，需用户同意。

- **Q1 传输采纳 node-spec Q13？** 推荐**采纳**（D1）。备选 Redis pub/sub 同样至多一次，没有类型化应答。
- **Q2 锁与 ctx 合成 Hash、键带 `{pid}`，并修订 node-spec §7.13 的预留键名？** 推荐**采纳**；6.2 规格同步改。
- **Q3 待结算记录每局一个字段还是保持单槽？** 推荐**每局一个字段**（D13，理由见 §10.6 第 2 条）。PARITY 登记「mmorpg 待做（可选）」。
- **Q4 备战 fail-closed + 独占（D3）？** 推荐**采纳**：Redis 不可用时整条结算链本来就不可用，开局只会制造冻结。
- **Q5 备战额外拒绝（D4，含 RESOLVING）？** 推荐**采纳**（5.2 / 5.3 已写定）；PARITY 注明竞态下的可见差异。
- **Q6 备战即停步（D5，客户端可见）？** 推荐**采纳**。不采纳就在 `SceneWorld.integrate` 里跳过 `inBattle()` 的玩家（同基线）。
- **Q7 FIGHTING 判废宽限 + rescue（D21）？** 推荐**采纳**，PARITY 记「有意差异（故障路径，客户端可见）」+「mmorpg 待做（可选）：`pb.cpp:2153-2158` 加宽限、判废前先 GET pending」，改 mmorpg 需用户同意。不采纳时照搬基线立即判废。
- **Q8 `BattlePolicy` 缺省 REJECT（D11）？** 推荐**采纳**。备选「注册时没声明就启动失败」同样有效，但让每个新功能都多写一处。
- **Q9 gate 当场拒绝的位置（D12）？** 推荐**采纳**；6.2 的 gate 钉住测试一起改。
- **Q10 最终写回之后由存储线程销账？** 推荐 **6.3 不做**（§10.6 第 11 条）。若 7.x 需要早放锁，再与 `PlayerRepository` 的结局回调一起设计。
- **Q11 活动结果通道的 battle 侧放 6.3 还是 4.6？** 推荐 **6.3**（与结算发件箱同一套机制、带单测），发布走 6.4 的 `BattleResultSink`。PARITY 记「已对齐（battle 侧）；两版都不可达（消费方与巡检器两版都没有）」。
- **Q12 6.4 之前怎么端到端；`DEV` 房间还发不发确认？** 推荐采纳 dev gather（§7.18）；`DEV` 房间照 6.2 发确认（scene 侧零副作用），不改 6.2。
- **Q13 结算 body 要不要像资产通道那样加签？** 推荐 **6.3 不加**：调用方只有 battle，有 Dubbo 调用方 MAC，内网隔离；待结算记录本身在 Redis，进场时直接应用，只签 RPC 堵不住那条路，要签就两条一起签，属两版的设计决定（登记 mmorpg 待做（可选））。
- **Q14 金币流水原因用 `TX_BATTLE_REWARD(1005)` 还是 `TX_CURRENCY_ADD(14)`？** 推荐 **1005 + 关联号 battle_id**（D23）。
- **Q15 消耗的抽取顺序？** 推荐**格子号升序**（D22），PARITY 标「客户端可见：哪一堆变少」。
- **Q16 FinishBattle 时 Redis 不可用？** 推荐**照搬**「只投一次 + 告警」（J17）。备选「内存里按应答重试」会引入第二种 ACK，不推荐。
- **Q17 5.5 疏散遇到战斗中的玩家？** 由 5.5 定。并行的 5.5 规格稿已推荐「改派跳过战斗冻结中的玩家；冲突 / 停服疏散到期时照常写回，结算落到下一个持有者」
  （`scene-drain-spec.md:105`、`:543`、Q16 `:1092`）。推荐 **6.3 接受**：写回后锁仍在，下一个持有者进场恢复按锁重建冻结、推 144，结算经位置记录投递或进场恢复应用，两条都已覆盖。
  与基线不同（基线疏散不看战斗、立即退出并改派，`lc.cpp:2149-2190`、`:2225`），并入 D26 登记。备选「允许 EVACUATE 交出战斗中的玩家」同样安全，但打破「交出与战斗互斥」这条简单不变量。
- **Q18 进场恢复遇到因金币被拒而延后的记录时，是否拒绝开下一局？** 推荐**拒绝（1006）**：保住「按局序应用」的不变量（否则旧局的气血终值会盖掉新局）；只在 GM 封禁 / 全服封禁 / 钱包溢出 / 坏数据时出现，PARITY 注明。
  代价是永久性的延后会让该玩家最多 7 天开不了战（§10.5），必须有告警与 GM 处置入口（7.4）。注意延后只停「应用后面的记录」，锁步骤照做（§7.8 第 3 步），在打的局照样冻结。
- **Q19 已 FIGHTING 的补发确认是否零 Redis？** 推荐**只在续期未成功时再发**（D9）。
- **Q20 `NodeRpcClients<S>` 泛化放哪批？** 推荐 **6.3**：battle 调 scene 先用到，6.4 的 match 直接复用。
- **Q21 match 对备战 RPC 失败 / 超时的成员也发取消？** 推荐 **6.4 采纳**（取消幂等，battle_id 不符会忽略）；登记 mmorpg 待做（可选）。
- **Q22 确认改按位置记录解析，修 B17？** 推荐**只做回落**（D28，评审修订）：快照路由的实例相符时照旧（离线玩家靠它续锁），只在实例不符时改问定位器——只多一次 Redis 读，
  只在故障路径上发生，修掉 B17 里危险的那一支（备战节点崩溃 → 锁按备战 TTL 过期 → 同时进两局、丢结算）。全面改按位置解析仍不做：会失去离线确认对锁的续期。
  B17 里「原节点还在」那一支（新节点上的 PREPARING 到期被摘、不推 144、在途闸敞开到结算）照搬；要修可在 reaper 摘掉 `preparedHere = false` 的 PREPARING 时重读一次锁、看到 `F` 就升级，留作备选。
- **Q23 账本损坏（D24）怎么修？** 推荐 fail-closed + 告警，GM 修复入口随 7.4。
- **Q24 确认补发收到确定性回执就停（node-spec Q6）？** 推荐**不做**，照基线补满；scene 处理重复确认在正常路径上零 Redis。

---

## 13 测试计划与 robot

构建：`./mvnw -B -pl xm-scene,xm-battle,xm-gate,xm-team,xm-player-store,xm-api,xm-discovery,xm-audit -am test`。改了 proto（`scene_battle.proto`、`player_state.proto`、
`transaction_log.proto`、`battle_control.proto`）必须 `clean install`（`AGENTS.md` §4）。测试方法名用中文。没有运行证据时不得声称通过。

**落地后的测试类分布与原计划的出入**（§13.1–§13.7 的各行保留原计划的「内容」列，实际的类名与落点以这里为准；条数与运行证据见末尾的实现记录）：

| 模块 / 包 | 测试类 | 对应本节 |
|---|---|---|
| xm-discovery `com.game.discovery.battle` | `BattleRedisConstantsTest`、`LockTtlTest`、`BattleRedisReplyTest`（`ENTER_READ` 的回复形状、字段名原始字节与规范判定，新增）；真 Redis：`BattleRedisScriptsIntegrationTest`（每段脚本的真值表，每段可变脚本连跑两次）、`BattleLockReaderIntegrationTest` | §13.1、§13.4 |
| xm-scene `com.game.scene.battle` | 计划内：`PrepareBattleTest`、`CancelBattlePrepareTest`、`ConfirmBattleTest`、`BattleRecoveryTest`、`BattleReaperTest`、`SettlementDeliverTest`、`SettlementApplyTest`、`SettlementApplyValuesTest`、`BattleLedgerTest`、`BattleSnapshotsTest`、`FreezeFromLockTest`、`InBattleGateMatrixTest`、`HandOffBattleExclusionTest`、`TeamFollowBattleTest`、`SceneBattleProviderTest`。**评审修复新增的回归类**：`BattleStaleReadTest`（过期读 / 代际 / 已销账集合 / reaper 不排空）、`BattleCallbackFailureTest`（回调抛异常、逻辑线程已停时各应答的结局）、`BattleRebuildRegressionTest`（TOUCH 的三种结局、144 恰好一次、迟到确认不带期限、rebuild 指标三分）、`BattleLockDeletionRegressionTest`（只删备战锁、按锁回调的判定次序）、`BattleLedgerEntryFieldsTest`（条目级未知字段逐字节往返）、`BattleUnfreezeRecoveryTest`（真的 5.2 交出流程：原地解冻后重跑完整恢复）。**第二轮修正新增**：`BattleFreezeCarryTest`（沿用副本：`preparedHere` 随会话取值、运行态标记复位） | §13.1、§13.2 |
| xm-scene 其它包 | `com.game.scene.pet.PetBattleSnapshotTest`；`com.game.scene.rpc.SceneRpcServerTest`（一个端口两个服务 + §13.7 的四条跨进程断言并在其中）；`com.game.scene.metrics.SceneBattleMetricsTest`（导出的时间序列与 §9 逐项相等、初值 0、标签键有界）与 `SceneMetricsTest`；`com.game.scene.SceneNodePropertiesTest`（`reaper-interval` 边界、`battle-rpc-max-inflight`）；`com.game.scene.storage.StoragePlayerRepositoryTest`（`toData` 带出 name 与账本） | §13.1–§13.3、§13.7 |
| xm-scene 测试件 `com.game.scene.testing` | `FakeBattleLocks`（内存版 Redis，按 `BattleRedis` 真值表执行，可控「Redis 执行」与「回复交回」的次序、重放、先执行再失败）+ `FakeBattleLocksTest`（约 60 步走遍每段脚本每个分支，先对字面期望，`-Dxm.it.redis` 下再与真 Redis 逐步对拍）、`ManualExecutor`、`RecordingTeamFollow`；`com.game.scene.battle.BattleFixture`（与 `SceneNode` 同形的一整套装配，别的包可用） | §13.2 的假件 |
| xm-player-store | `PlayerStoreSqlTest`（H2 与真 MySQL 各一遍） | §13.3 |
| xm-battle `outbox` / `port.scene` / `testing` | `SettlementOutboxTest`、`ActivityResultOutboxTest`、`SettlementRetryRulesTest`、`ActivityRetryRulesTest`、`OutboxMetricsTest`（新增）、`OutboxRedisIntegrationTest`（真发件箱 + 真脚本的整链，新增）；`DubboSceneBattleEventsTest`、`SceneTransportTest`、`SceneTransportPropertiesTest`、`SceneClientSweeperTest`（后三个新增）；假件 `FakeSettlementStore`（+ `FakeSettlementStoreTest`）、`FakeActivityStore`、`FakeSceneLocator`、`RecordingSceneTransport`、`Scripted`、`CallJournal` | §13.1、§13.4、§13.5 |
| xm-battle 其它 | `room.ConfirmWindowConstraintTest`、`room.ResultRoutingTest`、`room.ConfirmResendTest`、`port.LoggingPortsTest`、`BattleConfigurationTest`、`admin.DevGatherTest` / `DevGatherControllerTest` / `DevGatherControllerProdTest` / `DevGatherControllerNoTokenTest`、`e2e.BattleNodeEndToEndTest` | §13.5 |
| xm-gate | `BattleUplinkRejectedTest`（真实契约的 12 个号）、`ClientDispatcherTest`「战斗上行」一节（手写路由的闸序）、`MessageRoutesTest`（`directOnly` 与基线判据的等价） | §13.6 |
| xm-team | `TeamDisplayTest`、`TeamDisplayRedisIntegrationTest`（真 Redis、真脚本）、`TeamConfigurationTest`、`TeamViewsTest` | §13.8 第 12 步的组件级等价验证 |
| xm-api | `rpc.NodeRpcClientsTest`（真 Triple 回环） | §13.7 |
| xm-common | `deadline.DeadlineTest`（第二轮修正补 `Deadline.await` 中断分支的两条：等待中被中断、调用时已带中断标志） | — |
| xm-robot | `BattleSettleChecksTest`、`BattleAdminGatherTest`、`BattleCrashChecksTest`、`BattleCrashScenarioTest`、`CrashWindowOptionsTest`、`SliceScriptsTest`、`UpstreamConstantsTest`、`RobotOptionsTest` | §13.8 |

出入：

- **`BattleRedisConstantsTest` 与 `LockTtlTest` 在 xm-discovery**（常量与纯函数的出处），不在 xm-scene。前者里的确认补发窗口 180 s、备战期限上限 96 s、`scene-rpc-timeout` 缺省 5000 ms 只能写字面值
  （这些常量在 xm-battle / 6.4，xm-discovery 不能反向依赖）；引用常量的版本在 xm-battle：`ConfirmWindowConstraintTest`、`SceneTransportPropertiesTest`。
- **§13.2 的四个类没有放进审计建议的 world / team 包**：`InBattleGateMatrixTest`、`HandOffBattleExclusionTest`、`TeamFollowBattleTest`、`SceneBattleProviderTest` 都在 `com.game.scene.battle`，共用 `BattleFixture`。
- **§13.5 的几个短语落在房间层**：「`DEV` 房间不调端口、`DEV_GATHER` 照调」「`kind = NONE` 不进通道、不认识的 kind 进通道」「`DEV_GATHER` 房间的结算端口被调用、结果事件端口不被调用」在 `ResultRoutingTest`；
  「补发节奏」在 `ConfirmResendTest`；「非 dev → 403」在 `DevGatherControllerProdTest`；「缺令牌 → 503」在 `DevGatherControllerNoTokenTest`。
- **§13.6 分两处**：真实契约的 12 个号在 `BattleUplinkRejectedTest`，手写路由的闸序用例在 `ClientDispatcherTest`。
- **§13.7 并进 `SceneRpcServerTest`**：调用方用 xm-api 的 `NodeRpcClients<SceneBattleService>`，对面是真的 `SceneBattleProvider` 加真的 `PlayerBattleService`；鉴权拒绝按 Triple 状态码 `UNAUTHENTICATED` 断言。
- **`InBattleGateMatrixTest` 多一道守卫**：扫描主代码编译产物里全部 `SceneFeature` 实现类，必须与 `BattleFixture.features` 相等——新功能类不进夹具即失败，进了夹具其方法又必须进 §7.13 的表与探测。
  「钉住不闸」的定义是：对照玩家（另一套相同装配、不在战斗）发同一条请求办得成，在途玩家得到逐项相同的结果。
- **§13.5 `SettlementOutboxTest` 补一项**：跑满 12 轮后、第 13 轮前销账 → `acked`，不是 `exhausted` / `exhausted_offline`（有目标、无目标各一条）——这条性质由探测在前的次序保证（§7.15）。
- **§13.2 表里与第二轮修正不再一致的两处**（表保留原计划的写法，以这里为准）：`BattleRecoveryTest` 的「同 epoch 沿用旧冻结（会话变了推 144，同会话不推）」——同会话沿用、
  还欠着一条 144 的 FIGHTING 冻结会在沿用时当场推一次（§7.8 第 0 步）；`CancelBattlePrepareTest` 的「删锁完成后才触发跟随复查」与 `TeamFollowBattleTest` 的「解冻且删锁完成后补跟随」——实例已换时按
  §7.4「删锁结局回来之后补给谁」补给现任实例。第二轮另在 `PrepareBattleTest`（期限上界、删锁结局后摘重建冻结与补跟随）、`CancelBattlePrepareTest`、`BattleReaperTest`（判废删锁后的补跟随）、
  `ConfirmBattleTest`（同会话沿用后的确认升级推不推）、`SettlementApplyTest`（销账放锁而实例已换）、`InBattleGateMatrixTest`（帧外推跳过在途玩家）里加了用例。
- **判别力核对**：各组测试补完后都对主代码做过变异核对（临时改错一处、确认对应用例失败、还原并 `cmp`），做法与结果记在实现记录里；按设计存活的等价变异也记在那里。

### 13.1 纯函数（逐条移植基线）

| Java 测试 | 内容 | 来源 |
|---|---|---|
| `BattleLedgerTest` | 登记幂等与 forget；0 号永不登记、永不命中；满 64 按时间戳不按下标淘汰；state 往返；写出顺序确定；未知字段保留；损坏判定（0 号、重复、超 64） | `t_settle:325-360` |
| `SettlementRetryRulesTest` | 停止于已销账；按重新解析的目标重投；无位置时等待；用尽响亮但不丢；最后一轮已销账算成功；**次数在判定之后才加**：attemptsSoFar = 11 → resend、12 → exhausted（共 12 次重投，第 13 轮用尽） | `t_route:353-397` |
| `ActivityRetryRulesTest` | 判定顺序；键名；只在重发时计次（30 次重发后第 31 轮用尽） | `t_act:116-135`；`room.cpp:2014` |
| `BattleRedisConstantsTest` | `LOCK_HOLD ≥ INTERVAL × MAX + 60`；`GRACE + REAPER_INTERVAL < LOCK_EXTRA`；确认补发窗口不等式（给 6.2 引用）；`xm.battle.scene-rpc-timeout` 缺省值 > `RedisProperties` 缺省最坏耗时 4.2 s | `pb.h:112-117` |
| `LockTtlTest` | 期限已过 → 60；`(d − now)/1000` 截断 | `pb.cpp:229-234` |
| `FreezeFromLockTest` | §1.2 的四条取值规则（字段缺失、`s` 缺失按 F、`d` 按 TTL 反推、P 缺 `p` 取 `d`） | `pb.cpp:608-637` |
| `BattleSnapshotsTest` | 各字段来源；speed 0 → 120；level 至少 1；max 兜底；技能剔除被动 / 开关 / 持续施法、空 `skill_type` 保留、0 与无表行跳过、保持原顺序；道具只取主背包、临时格不算、数量 0 跳过、`battle_usable = 0` 跳过、config 无符号升序合并（含 > 2^31 的 id）、uint64 累加；routing；`team_index = 0`；指纹等于 `TableBattleData.fingerprint()`；名字来自 `PlayerData` | `pb.cpp:885-1103` |
| `PetBattleSnapshotTest` | 没有出战 / 宝宝不存在 / 缺表行 / 气血 0 → 不带；技能先种类后已学、去重、**不过滤**；气血按上限夹；法力上限 0 时取原值；速度取派生值 | `pet.cpp:867-912` |

### 13.2 scene 组件测试（`SceneWorld` + 假 `BattleLocks`（可控完成次序与失败）+ 假仓库 + `RecordingSink` + 手动时钟，逻辑线程同步驱动）

| 类 | 用例 |
|---|---|
| `PrepareBattleTest` | 逐条拒绝码及次序（1005 → 1004 → 1006 FREEZING → 1006 RESOLVING → 1006 在途 → 1006 恢复中 / 账本未落盘 / 账本损坏 → 1006 0 血）；拒绝时零冻结、零 Redis 调用。成功：挂冻结后立刻挡住 168（25011）；锁写完才回应答；TTL 与锁字段正确；停步并置速度脏位（旁观者收到速度 0 的 66）。锁被占 → 1006 且解冻；**脚本重放**（假 `BattleLocks` 先执行再报超时、随后重发）→ 仍回 0、冻结与锁都在；Redis 失败 → 1003、解冻、尽力删锁；写锁期间玩家离场 → 1004 且删锁；写锁期间收到取消 → 写锁完成后才删、回 1006；写锁期间同 epoch 重进 → 新实例**不带**冻结、旧回调删锁回 1004 |
| `CancelBattlePrepareTest` | 离线三种返回；在线无冻结忽略；battle_id 不符忽略；FIGHTING 拒绝；PREPARING 解冻并条件删锁；删锁完成后才触发跟随复查 |
| `ConfirmBattleTest` | 升级与续期；`deadline = 0` 沿用；不符 / 重投幂等；续期失败后重投再续一次、成功后零 Redis；离线一段 Lua；FREEZING 只续期不挂冻结；在线无冻结 → 迟到重建（FIGHTING、推 144）；账本命中 → 销账不重建；`preparedHere` 时不推、重建 / 沿用时推且只推一次；PREPARING 状态下任何路径都不推 144 |
| `BattleRecoveryTest` | 重建 PREPARING 不推；重建 FIGHTING 推 144 且在 79 / 21 之后；无锁不重建；复核没命中就撤销；锁指向账本里已有的局 → 销账不重建；坏字段只删该字段、锁指向它时不重建；多条记录按 battle_id 升序应用、遇延后即停；**延后之后锁步骤照做**：锁指向没有记录的在途局 → 重建 FIGHTING 并推 144，锁指向延后 / 未轮到的那一局 → 不重建；读失败 → RETRY、reaper 重读后 READY；同 epoch 沿用旧冻结（会话变了推 144，同会话不推；沿用的是新对象、`rescuing` 已复位）；恢复读回来时已在 FREEZING → 不挂冻结，RESOLVING → 挂上且随后选中远端时中止换图；交出进场也跑 |
| `BattleReaperTest` | PREPARING 过期只解冻、锁保留；FIGHTING 在期限后 10 s 内不动；过了宽限：有本局记录 → 应用 + 150 + 销账，无记录 → 解冻并条件删锁；**读记录出错 → 不判废、下一轮再读，过了期限 + 60 s 才直接判废**；`recovery ≠ READY` 时 rescue 得 DEFERRED → 判废但记录保留；rescue 在途不重复；`prepare_deadline = 0` 退回 `deadline`；账本排空；遍历时不改集合 |
| `SettlementDeliverTest` | 冻结匹配 → 应用 + 摘冻结 + HOLD + 150；冻结不匹配 → DISCARDED + 销账；无冻结锁 == X → 应用；无冻结锁不在 → DISCARDED + 销账；读锁失败 → DEFERRED 零副作用；异步读回来时实例已换 → DEFERRED；回调时已有别的局冻结 → DISCARDED 不销账；实例不符 → NOT_HERE；恢复中 / FREEZING → DEFERRED |
| `SettlementApplyTest`（逐条对照 `t_settle`） | `:120` 重复回调与重入只付一次、只推一次进度（重入抛断言）；`:141` 金币失败时 HP / 宝宝 / 任务都不动，解封后应用一次；`:157` 重登读到旧记录不再发奖、不碰新一局的冻结与锁；`:185` 的 Java 对应：实例 A 应用后存盘失败被移除 → 实例 B 恰好应用一次；`:203` 不适用（`ScenePlayer` 总有属性对象，测试里写明）；`:213` 错玩家不应用、不毒化合法重投；`:233` 账本进入 `toSave()`；`:245` 带账本加载 → ALREADY_APPLIED、无 150；`:262` / `:282` FREEZING → DEFERRED、无金币 / 道具；`:295` 未落盘不 ACK；`:308` `onProgressSaved(SAVED)` 后 ACK、成功后 forget；道具 `:406-455`（扣与发、按持有夹紧、没有不报错、非战斗道具拒扣不销毁、重复应用不重扣不重发） |
| `SettlementApplyValuesTest` | HP 夹到上限；MP 上限 0 不夹；`is_dead` 且 0 血回满，`is_dead` 但气血 > 0 不复活；宝宝夹、阵亡回满、未知宝宝忽略、184 只推一次且先于 150；N 个击杀 N 条事实、0 号跳过；数量夹到 `0xFFFFFFFF`；`gold > Long.MAX` → DEFERRED；主包满 → 临时格只补没进去的部分；两处都满 → `drop_lost` 但账本照记、150 照推；抽取按格子号升序；流水（金币 `BATTLE_REWARD` 关联号 X、`ITEM_AWARD` 带 extra、每个被抽实例一条 `ITEM_DESTROY`）；第 65 条 → evicted；某一步抛异常时其余照做、账本照记；a–d 返回时账本不登记；闸超集（c 步放行时 d–j 不被冻结闸拒）；**在冻结为 FIGHTING 的玩家身上应用**，宝宝回写与气血回写都生效（没有经过带 26008 / 25011 的 `checkWritable`）；掉落数量同样夹到 `0xFFFFFFFF` |
| `InBattleGateMatrixTest`（参数化，遍历注册表） | 断言每个已注册方法的 `BattlePolicy` 与本稿 §7.13 表一致（新方法不在表里即失败）。在途玩家：63 → 3023（同时换图在途仍回 3023）；134 / 132 位置不变；131 速度为 0；168 / 172 / 174 / 171 / 169 / 175 → 25011，173 照常；183 / 185 / 186 / 182 / 189 / 187 → 26008，188 照常；192 → 1005，191 照常；84 → 7004，打在途目标 → 7002；资产 debit / credit → RETRY 27002 且账本不变、abort 照常；**钉住不闸**：37 / 49 / 94 / 95 / 194 / 195 / 54 / 167 / 181 / 193 / 190 / 43 / 77。解冻后各项恢复 |
| `HandOffBattleExclusionTest`（扩展 `RemoteSwitchTest` / `HandOffTransferTest`） | 战斗中 63 远端 → 3023；`beginRemoteSwitch` 直接调用时对战斗中的玩家拒绝（不进 RESOLVING）；FREEZING / RESOLVING 时备战 → 1006；RESOLVING 期间迟到确认重建，随后选中远端 → 回 NONE、推 23 `{3023}`；选中本节点 → 照常换场景；FREEZING 时资产码为 27003；FREEZING 时 ACK 回来不改账本、冻结快照与内存一致；交出提交后目标节点进场恢复为已应用的局销账；排空改派跳过战斗中的玩家 |
| `TeamFollowBattleTest` | 在途跳过；锁存在跳过；读锁失败按在途；解冻且删锁完成后补跟随；保留锁的解冻不补；ACK 放锁后补跟随 |
| `SceneBattleProviderTest` | 在途上限 → OVERLOADED 且未进逻辑线程；实例不符 → NOT_HERE 且未进逻辑线程；解析失败的各方法回包；在回写线程上完成；逻辑线程已停 → 异常完成 |
| `SceneRpcServerTest` | 一个端口导出两个服务的真 Triple 回环，`register = false`，两个 group |

### 13.3 存储（`PlayerStoreSqlTest`，缺省 H2，`-Dxm.it.mysql` 连真库）

`battle_ledger` 随 `player_state` 往返；被围栏拒绝的写不落账本；交出事务把账本带过去（handoff-spec §5.2）；`PlayerData.name` 读出。

### 13.4 真 Redis（`-Dxm.it.redis`，缺省跳过）

- `BattleRedis` 每段脚本的真值表：`PREPARE_LOCK`（空 → 写入；同局 P → 重放成功并刷新 TTL；同局 F → 拒绝且不改 TTL；别的局 → 拒绝）；**每段可变脚本连跑两次**，第二次的返回与副作用符合 §7.2 的重放语义；`CONFIRM` 命中与不命中；`CANCEL_OFFLINE` 的 P / F / 不符；`DELETE_IF_MATCH` 不误删别的局；
  `TOUCH`；`HOLD` 只延不缩；`ACK` 位掩码（记录匹配 / 不匹配 × 锁匹配 / 不匹配 / 不在），删掉最后一个字段后键自然消失；`ENTER_READ` 在无锁、有锁无记录、两者都有时的返回；
  `READ_IF_OURS`；`STORE_SETTLEMENT` 刷新 TTL 并返回 HLEN；`ACK_IF_SUPERSEDED` 四种返回。
- 键形状：同一玩家的锁与记录 `CLUSTER KEYSLOT` 相等（或断言 hash tag 相同）。
- 发件箱：落库 → scene 销账 → 探测得到已销账。`BattleLockReader` 的 EXISTS / 读值 / 批量。
- 实现时补的格子（`BattleRedisScriptsIntegrationTest` / `OutboxRedisIntegrationTest` / `FakeBattleLocksTest` 的对拍）：墓碑——「落库 → 销账 → 再落库」回 -1、记录不复活，not_durable 变体
  （销账先到、落库晚到）同样被挡，`ACK_IF_SUPERSEDED` 回 2 时写墓碑；`TOUCH` 的单调（直接情形与「备战 → TOUCH(P) → 确认 → TOUCH(P) 连跑两次」）；只删备战锁的 1 / 2 / 0；
  残缺的锁（键在而缺 `b`）上其余脚本全部落空、`PREPARE_LOCK` 按空键写入；回 nil 的整数脚本异常完成；两个按 UTF-8 解码会撞成同一个 String 的非法字段名都读得出、都能按原字节单独删掉；
  `CONFIRM` 未命中得到 null（Redisson 3.50 + `ByteArrayCodec` 下实测）；对 Hash 键 `MGET` 回 nil、`GET` 报 `WRONGTYPE`（`TeamDisplayRedisIntegrationTest`）。

### 13.5 battle 侧（手动调度器 + 假 Redis 端口 + 记录型传输，带全局序号）

- `SettlementOutboxTest`：投递一定排在 `STORE_SETTLEMENT` 结局之后；SET 失败或 Redis 不可用 → 只投一次、不登记、计 `not_durable`；序列化失败什么都不发；
  首投解析不到 → 不投但登记；探测：不存在 → 摘除；出错 → 跳过且不计次；定位出错 → 跳过且不计次；Found → 按 endpoint 与实例 id 重投；NoHolder → skip 并调 `ACK_IF_SUPERSEDED`，返回 2 / 3 时摘除；
  第 12 轮仍重投、**第 13 轮** → exhausted / exhausted_offline 并摘除；一直出错超过 `OUTBOX_MAX_AGE` → `expired` 并摘除；
  `probing` 防重叠；定时器空时停、第一条入队时开；`DEV` 房间不调端口、`DEV_GATHER` 照调；停机时等在途落库与首投（≤ 3 s）；投递应答不改名单。
- `ActivityResultOutboxTest`：SET 之后发布同一份字节；SET 失败只发一次；EXISTS 0 → 摘除；1 → 重发原字节；30 次重发后 → exhausted；出错 → 跳过不计次；超过 `OUTBOX_MAX_AGE` → expired；`kind = NONE` 不进通道、不认识的 kind 进通道。
- `DubboSceneBattleEventsTest`：目录实例相符 → 发往快照实例；不符 → 问定位器，Found 发往定位到的实例（`rerouted`），NoHolder / 出错 → `skipped`；正常发出、不等结果；补发节奏仍由 6.2 的房间测试覆盖。
- `DevGatherControllerTest`：非 dev → 403；缺令牌 → 503；第 k 人备战失败 → 按升序取消前 k − 1 人、回 422；建房 `NOT_ALLOCATABLE` 或业务错误 → 全部取消；`PREPARE_ONLY` 只备战不建房；
  `DEV_GATHER` 房间的结算端口被调用、结果事件端口不被调用。

### 13.6 gate

`ClientDispatcherTest`：12 个战斗号都回 23 `{1003}`；不计非法包、不断连；login 在途时当场回（不排队）；热关停了某个战斗方法时仍回 23 `{1003}`；6.4 把 `MatchService` 接入后 `BattleClientPlayer` 仍被拒。
（6.2 已计划一条钉住用例，本批补齐。）

### 13.7 跨进程

进程内真 Triple 回环：battle 的 `NodeRpcClients<SceneBattleService>` → scene 提供方。断言实例不符 → NOT_HERE；缺 Dubbo MAC → 鉴权拒绝；在途超限 → OVERLOADED；应答在 `scene-asset-reply` 上完成。

### 13.8 robot：`battle-settle` 场景（xm-robot）

- **前置**：本机切片带 xm-battle 与 xm-scene（`SceneBattleService`），dev 模式，`XM_ADMIN_TOKEN` 已设，scene 的 `reaper-interval = 2s`。复用 6.2 的 `BattleDirectConnection`、
  `BattleAdminClient`、大厅 177 记录。账号 A（新号）、B（同场景观察者）。基线对照：`robot/features_battle_smoke.go`（任务 12、PVE1、`verify_relogin`，胜利判据 `:82-95`）。

1. **准备**：A、B 登录进同一场景。A 用 GM 187 领宝宝并 183 出战；194 接任务 12；记下 54 金币、191 背包、167 气血、181 宝宝。
   本场景用到的 battle_id 由 robot 按「毫秒时间戳 × 1000 + 序号」现取，保证 X1 < X2 < … 且跨次运行不重复（D13 的局序依赖 battle_id 随时间递增，§10.4）。
2. **gate 拒绝**：A 在大厅连接上发 149 / 140 / 162 / 165 → 每条都收到 23 `{1003}`，连接不断。
3. **只备战**：`gather{PREPARE_ONLY, X1, deadline = now + 300 s, prepare_deadline = now + 60 s, [A]}` → 200。快照核对：player_id、level ≥ 1、speed > 0、max_health > 0；
   routing 的 scene 节点与实例、gate 实例非空；指纹 `^[0-9a-f]{32}$`；items 都是 `battle_usable`；pets 只有一只、`owner = A`；技能都可施放。
4. **在途闸**（PREPARING）：63（同节点另一场景）→ 3023；168 → 25011；185 → 26008；187 → 26008；192 → 1005；84 → 7004；**B 对 A 放 84 → 7002**；173 照常回建议；194 接任务 13 成功；
   A 发 134 带新坐标 → 1 s 内 B 收不到 A 的位置变化，且 B 在备战那一刻收到 A 速度为 0 的 66；再发 `gather{PREPARE_ONLY, X2}` → 备战结果 1006。
5. **取消**：`cancel-prepare{A, X1}` → 闸立即解除（168 成功）；再取消一次幂等；`gather{PREPARE_ONLY, X3}` 成功后再取消。
   **备战到期**（只在 `--slow` 跑，约 70 s）：`gather{PREPARE_ONLY, X3b, prepare_deadline = now + 3 s}` 不取消 → 3 + 2 s 内 63 / 168 恢复（reaper 只摘冻结）；
   随即 `gather{PREPARE_ONLY, X3c}` → 1006（锁按 B2 / §1.7 保留到备战 TTL）；`cancel-prepare{A, X3b}` 也不删它（在线、没有冻结 → 忽略，B2）；约 60 s 后 `X3c` 再试成功并取消。
6. **完整一局**：`gather{CREATE, X4, battle_config_id = 1, 固定 seed, [A]}` → ADMITTED；大厅先收 177、后收 143；直连握手、补拉 140。
   **重连提示**：关掉 A 的大厅连接并重新登录 → 进场后大厅收到 **144** `{X4}`；63 仍回 3023。经 `dev/issue-ticket` 补签 → 重新直连 → 162 开自动 → 直连收到 150（`SIDE_A_WIN`）。
   大厅随后收到 **184**（宝宝有回写时）和 **150**：battle_id、settlement 与直连那份逐字段相同，**184 在 150 之前**。
7. **结算效果**：54 金币增量 = `gold_gain`；掉落进背包（主包或临时格），消耗按持有夹紧；167 气血 = `min(health, 上限)`，阵亡时回满；181 宝宝气血 = 结算值，阵亡时回满；
   每 250 ms 轮询 193，5 s 内任务 12 可领 → 195 成功、重复领取被拒；闸已解除（63 成功、168 成功）；**锁已放**：2 s 内 `gather{PREPARE_ONLY, X5}` 成功，随后取消。
8. **重登不重发**：A 登出再登入 → 任务、背包、金币与第 7 步相同；10 s 内大厅**没有**第二条 150。
9. **离线结算**：`gather{CREATE, X6}` → 直连开自动 → 立即关掉大厅与直连 → 等结算完成并越过重投窗口（`--slow` 跑满 130 s：12 次重投、第 13 轮用尽，§3.4；快跑 15 s）→ A 登录 → 大厅收到 150 `{X6}`，金币只增一次；再等 10 s 无重复。
10. **确认后销毁**（B1）：`gather{CREATE, X7, deadline = now + 20 s}` → 收到 143 后等 1 s（确认已到，冻结为 FIGHTING）→ `cancel-prepare{A, X7}` → 63 仍回 3023（FIGHTING 拒绝取消）→
    `dev/destroy` → A 收不到 150；63 一直回 3023；期限 + 10 s 宽限 + 2 s 内 63 恢复（rescue 读不到本局记录 → 判废）。
11. **与 5.2 互斥**（只在双 scene 切片上跑，复用 `CrossNodeScenario` 的前置）：PREPARING 时 63 指向另一节点的场景 → 3023（不是 3014）；取消后同一条 63 跨节点成功。
12. **队伍视图**（`team` 场景扩展）：成员备战后 `TeamMemberView.in_battle` 为 true；结算销账后变回 false。
13. **指标**：scene `xm_scene_battle_settlements_total{result="applied"} ≥ 2`、`xm_scene_battle_acks_total{result="released"} ≥ 2`、`xm_scene_battle_gate_rejects_total` 各 gate 有增长；
    battle（18112）`xm_battle_settlement_outbox_total{event="acked"} ≥ 1`、`exhausted` 不变。
14. **输出**：`BATTLE_SETTLE_OK battle_id=… gold=… mission=12 relogin=ok offline=ok`；失败 `BATTLE_SETTLE_FAIL step=…`。

- **故障变体**（只在本机切片跑，形态参照基线 `robot/currency_crash_window_scenario.go`，由 `tools/local/` 脚本 kill -9）：大厅收到 150 之后立即 kill scene、重启、重登 → 金币恰好增一次、150 至多一份；
  大厅 150 之前、SET 之后 kill battle → 期限 + 10 s 时 rescue 到账（D21）。
- **prod 模式**：`gather` 回 403，场景只跑第 2 步。
- **留给 6.4**：第 3–10 步的建房换成真排队（基线 `battle_smoke_scenario.go` / `features_battle_smoke.go`），dev gather 退出主流程。
- **基线对照**：基线 robot 对 144 是空处理（§1.6），Java 第 6 步是新增覆盖，建议同步给 mmorpg robot（PARITY「mmorpg 待做（可选）」）。

**落地后的现状**（与上面步骤的出入；哪些步骤有切片证据见实现记录）：

- **步骤次序**：1、2、3–4、5、（5-slow）、12、11、6–7、8、9、10、13。第 12、11 步放在第 5 步之后——那时 A 没有冻结也没有锁、还在登录时的场景里。
  汇总行 `BATTLE_SETTLE_FAIL step=<逗号分隔的步骤名>`，步骤名取值 `2 / prod-403 / 1 / 3-4 / 5 / 5-slow / 12 / 11 / 6-7 / 8 / 9 / 10 / 13 / 流程`。
- **第 12 步放在 `battle-settle` 里，不在 team 场景**（team 场景没有 battle 管理口客户端）：A 建单人队读自己这名成员的 `in_battle`，共五次采样
  （备战前 false / 备战后 true / 取消后 false / 确认后 true / 销账放锁后 false），每次最多等 5 s；后两次嵌在第 6–7 步里采样，失败记在第 12 步名下。
- **第 11 步**：A、B 登录时同图不同频道就直接跑（目标 = B 原来的频道）；否则只有 `--scene-metrics-url` 给了两个及以上地址（= 声明双 scene 切片）时才用探针账号落位，
  落不到判失败；只有一个地址时跳过并写观察记录。判据依赖「per-node 覆盖、每图每节点一个频道」（与 `cross-node` 场景同一前提）。双 scene 切片上这一步之后 A 留在另一个节点。
- **第 13 步**：在途闸逐个断言 `gate = enter_scene / attribute / pet / bag_sort / skill / move` 各 ≥ 1（取值由单测读 xm-scene 的 `SceneMetrics.BattleGate` 钉住）；`asset` 与 `default` 本场景打不到，只记观察。
  双 scene 切片必须把两个节点的管理端口都传给 `--scene-metrics-url`；robot 见过第二个节点、地址不足两个、增量又不达标时，只记一条写明原因的失败（仍是 `step=13`，不降成观察记录）。
- **故障变体**：`battle-settle --crash-window scene-after-150|battle-after-store --crash-phase arm|verify --crash-state <文件>`（`BattleCrashScenario`），由 `tools/local/battle-crash-window.sh` 编排
  （等断点 → `kill -9` → 等 arm 退出 → 按 `start-slice.sh` 的方式重启 → verify）；robot 只是客户端、不杀进程，断点信号是状态文件的出现。
  - `scene-after-150`：大厅 150 一到就写断点。verify 按重登后本局 150 的条数分类：0 = durable（kill 落在落盘之后）、1 = recovered（进场恢复重放）、≥ 2 = 失败；都要求金币恰好增一次。
  - `battle-after-store`：没有去赌「SET 之后、首投之前」的毫秒级窗口，而是用不改服务端的办法把窗口撑开——A 先用 GM 94 封禁自己的金币获取，首投到 scene 时金币先行入账被拒，
    整笔 DEFERRED、冻结保持 FIGHTING、记录留在 Redis；杀掉 battle 后用 GM 95 解封，等期限 + 10 s + reaper 间隔，大厅 150 的到达时刻不早于期限 + 10 s − 1 s 才算 rescued。
    与「纯」窗口的差别：scene 在 battle 被杀之前见过一次被延后的投递。
  - 判定版本：`BattleCrashChecks.STATE_VERSION = 2`（也是状态文件的 `version`）：打赢且 `gold_gain > 0` 才写断点、verify 拒绝 `gold_gain = 0`、`battle-after-store` 的 verify 只认 rescued。
    robot 帮助里报出 `[crash-window-rev=2]`，脚本的 `ROBOT_CRASH_REVISION` 必须相等；改判定要两边一起加一。
  - 脚本前置：只支持 `start-slice.sh` 起的切片，要在启动切片的同一个 shell 里执行；只允许 dev / test；robot 包要认 `--crash-window`、判定版本相符、且不比 `xm-robot/src/main` 与 `pom.xml` 里任何文件旧
    （改了 xm-robot 源码必须重新打包）；`scene-after-150` 在第二个 scene 节点活着时拒绝。脚本里重启用的函数、端口表、命令行是从 `start-slice.sh` 逐字复制的，由 `SliceScriptsTest` 在构建期钉住两份不走样。

### 13.9 交付清单（随 6.3 提交）

- **PARITY.md**：新增「scene 侧战斗冻结与在途闸」「回合制战斗结算链路（battle 发件箱 / scene 应用与账本 / 销账）」「战斗活动结果持久通道（已对齐 battle 侧；两版都不可达）」三行，
  附 D1–D36、B1–B17；更新第 74 行（`features` 的战斗段随 6.4）、第 75 行（7004 / 7002 已接）、第 79 行（结算回写与复活、0 血拒绝开战已做）、第 81 行（宝宝写闸 26008 与战斗接缝已做）、
  组队行（team-spec D10 收口）、gate 拒绝战斗上行行（D12）。交付说明写明 mmorpg 侧：D3 / D13 / D15 / D20 / D21 / D28 / D29 / D30 / D33 / D35 / Q13 / Q21 / robot 144 为可选待做，需用户同意。
  残余风险里登记 `BattleLockReader` 咨询性读的部署约束（§10.5）。
- **`docs/design/architecture.md`**：§4.12 端口上的第二个服务；新增「回合制战斗（scene 侧）」小节（状态、互斥、键与脚本、账本与 durable 判据、进场恢复闸、发件箱）；§7 账本字段；§11 新指标；
  键清单加已销账墓碑；线程表加 `battle-outbox` / `battle-scene-sweep`；部署约束加「上主从 / 集群前 Redisson `readMode` 设 MASTER，或给回档闸一个读主库的批量入口」。
- **`docs/design/db-migrations.md`**：`player_state` 是 blob 新字段，不改表结构，只登记字段号 9。
- **`docs/porting/inventory/combat.md`**：`:276`、`:300`、`:312`、`:324`、`:336`、`:348` 的 java 列；§10.7 的勘误。
- **`docs/porting/battle-node-spec.md`**：§7.9 / §7.13 的键名与结算寻址改为指向本稿；§7.9「实例不符就不发」改为 D28 的定位器回落；§7.6 / §7.12 加 `DEV_GATHER`；§10.4 的单测改为引用 `BattleRedis` 常量
  （并写明实际余量 14 s）；`BattleResultSink` / `SettlementSink` 的契约；§3.7 / §13.7 的 gate 行按 D12 更新。
- **`docs/porting/scene-handoff-spec.md`**：`beginRemoteSwitch` 拒绝战斗中的玩家（§7.13 世界内部第 1 条）；`unfreezeInPlace` 之后**重跑完整的进场恢复**（§7.8、§10.5；原稿写的是补跑锁步骤）。
- **`docs/porting/team-spec.md`**：D10 标为已收口。
- **`docs/porting/match-spec.md`**（6.4，并行稿）：锁键改为 `RedisKeys.battleLock` 并经 `BattleLockReader` 读（咨询性读，权威在 scene）；§9.7.2 对 `SceneBattleService` 的最低要求改为指向本稿 §7.3–§7.6；
  **备战 / 取消调用超时保持 3 s 的裁决**（§7.3、§10.4；原稿写的「备战调用超时 ≥ 5 s」作废）；battle_id 用时间在高位的雪花号（§10.4）。
- **`docs/porting/roadmap.md:83`**：完成后打勾，写提交号。

---

## 评审修订记录

完整性评审（2026-10-05，基线 `26ceb70ca`、Java 工作区含 5.2 / 6.2 未提交改动）。核对了 40 多处出处：`pb.cpp` 的备战 / 取消 / 确认 / 两条重建 / 应用 / 分流 / 进场钩子 / reaper、
`pb.h` 常量、`ledger.h`、`outbox.h`、`activity.h`、`room.cpp` 的 FinishBattle / 落库 / 重投 / 活动通道 / Destroy / Abort、`room.h` 常量、psh / mvh / attr / pet / snap / asset / skill / rbh / team / lc 各闸、
`gate_cmp.cpp` 闸序、`battle_comp.proto` / `battle_event.proto` / `player_battle.proto`、`message_id.txt` 的 12 个号、`gap.md` 残留条目、`t_settle` / `t_route` 用例名，以及 Java 侧
`SceneWorld` / `ScenePlayer` / `ClientRequestHandler` / `AssetOpService` / `BagFeature` / `AttributeService` / `PetService` / `MissionService` / `CurrencyService` / `PlayerRevive` / `MessageRoutes` /
`ClientDispatcher` / `SceneAssetLocator` / `RedisKeys` / `RedisProperties` 与 5.1 / 5.2 / 5.3 / 5.5 / 6.1 / 6.2 / 6.4 / team 各稿的引用行。下列为改动：

**基线事实更正**
1. §3.4 / §7.15 / §13.1 / §13.5：重投次数是**判定之后**才加（`room.cpp:1810-1811`），原稿写成判定之前。实际是 12 次重投、第 13 轮判用尽（约 130 s），robot 第 9 步的等待改为 130 s。
2. §1.2 / §2.4 / §7.2 / §10.7：帮会没有 `battle:lock` 的生产读者（`rules.go:391` 只是字段注释，只有单测赋值）；活动开局的读锁在 match（`activitybattlelogic.go:220-228`，读失败回 `REJECT_INTERNAL`）。
   JoinQueue 读锁失败回 `kMatchInternal`，不是 16000；观战读失败回内部错误；切磋读失败按「忙」。
3. §10.2 B17：原稿只写了「原节点还在」一支。补上「原节点已重启」那一支：确认送不到，锁按备战 TTL 过期，玩家会在这局还在打时被再次匹配、这局结算被丢弃。

**不安全的 Java 设计（已改）**
4. §7.2 / §7.5 / §10.6 第 3 条：Java 的 Redisson 是 `retryAttempts = 1`，超时的 EVAL 会被重发（`RedisProperties.java:14`）。原稿 `PREPARE_LOCK`「同局也拒」在重放下会把自己刚写的锁当成被占，
   scene 回 1006、match 不发取消，锁却留到备战 TTL。改为「同局且仍是 P → 重放成功」，并给每段脚本写明重放语义；Redisson 最坏耗时更正为 4.2 s（原稿写 3 s）。
5. §7.3 / §8 / §10.4：`xm.battle.scene-rpc-timeout` 从 3 s 改为 5 s，并要求所有 `SceneBattleService` 的调用方超时都大于 scene 侧最坏 4.2 s，启动时校验。
6. §7.8 第 0 步：沿用旧实例时，原稿「原样搬」同一个冻结对象。`lockPending` 的冻结会变成没有锁的孤儿；带着 `rescuing = true` 的冻结会让 reaper 永远跳过它，冻结永不过期。
   改为复制成新对象、复位运行态标记，写锁在途的冻结不沿用。
7. §7.8 第 2 / 3 步：原稿遇到延后的记录就整步停下，连锁步骤一起停。金币长期被封时，一场仍在打的局在本实例上没有冻结，在途闸全部敞开。改为锁步骤照做，并补上判据：
   锁指向一场已有结算记录的局就不重建。另补上进场恢复回调时已在 FREEZING → 不挂冻结，保住两种冻结互斥。
8. §7.9 rescue：原稿把读出错并进「没有记录」直接判废。判废会删锁，随后到达的重投被 DISCARDED 并销账，一次读错就丢了奖励。改为本轮不判废、下一轮再读，
   过了期限 + 60 s 才放弃；rescue 走与 §7.10 相同的 `recovery` / FREEZING 前置判断。
9. §7.11：k 步的 `finally` 明确只包住 d 步之后的部分，a–d 返回时都不登记账本。应用时战斗冻结还没摘，而 6.3 会把 25011 / 26008 加进 `AttributeService` / `PetService` 的 `checkWritable`，
   所以写明 e / f 步绝不能经过这两个闸，并加了单测。另补上掉落数量同样夹到 uint32。
10. §7.12：ACK 结果回来时如果玩家在 FREEZING，就不 `forget` 账本，保住冻结快照与内存的一致（handoff-spec §5.9）。补充说明 FREEZING 时 `requestSave` 回 `IN_FLIGHT`，但不会有存盘回调。
11. §7.13：`beginRemoteSwitch` / 5.5 `begin` 入口拒绝战斗中的玩家，兑现 handoff-spec `:106` 的契约。
12. §7.2 / §7.15 / §7.17：D15 让出错不计次，而定位器会把损坏的位置记录报成 Failure，条目可能永远不用尽。新增 `OUTBOX_MAX_AGE` = 10 min 作为上界，两个发件箱都用它
    （取代原稿活动通道的「超过 7 天丢弃」）。另写明首投解析不到时不投、照常登记。
13. §7.16 / D28 / Q22：确认在快照实例不符时回落到定位器，修掉 B17 的崩溃变体（只在故障路径上客户端可见：能收到 144）。node-spec §7.9 列入交付修订。
14. §7.2 / §8 / §10.4：`GRACE < LOCK_EXTRA` 改为 `GRACE + REAPER_INTERVAL < LOCK_EXTRA`。reaper 间隔的配置只许调小（≤ 30 s），与常量表不再矛盾。

**补漏**
15. §10.4：D13 的局序依赖 battle_id 随时间递增（基线是 match 的 snowflake）。写成对 6.4 与 robot 取号的约束。
16. §10.5 / Q18 / §9：永久性的结算延后（坏数据、钱包溢出、长期封禁）会让玩家最多 7 天开不了战。补了告警与处置入口，也补了备战写锁晚到的残余风险。
17. §6.2 / §7.21：补 D28 的可见差异与 J19–J22 四个故障窗口；§9 补 `rescues{error,gave_up}`、`outbox{expired,locate_error}`、`scene_events{rerouted}`；§10.1 补第 13、14 条。
18. 测试与 robot：脚本连跑两次的重放真值表；备战重放 / 写锁期间同 epoch 重进；延后之后锁步骤照做；恢复撞上 FREEZING；rescue 读错；FIGHTING 冻结下应用不被闸拒；第 13 轮用尽 / 超龄；确认回落。
    robot 补：B 对在途 A 放技能 → 7002；FIGHTING 拒绝取消（B1）；`--slow` 下备战到期后锁保留到 TTL；battle_id 按时间递增取号。
19. §13.9：交付清单补 5.2 规格（`beginRemoteSwitch` 闸、`unfreezeInPlace` 补跑锁步骤）、node-spec §7.9 回落、match-spec 的超时与 battle_id 约束；PARITY 改附 D1–D28。

未改动、已核对属实的：§1–§5 其余基线描述与出处；客户端可见帧（144 / 150 / 184 / 23 / 66）及其时机；各在途闸的码与次序；gate 拒绝的位置与 12 个号；宝宝快照与回写口径。

**落地后对上面各条的订正（2026-10-06）**：第 5 条「所有 `SceneBattleService` 的调用方超时都大于 4.2 s」——match 的备战 / 取消不在此列，保持 3 s（§7.3、§10.4 的裁决）；
第 8、19 条与 §10.5 的「`unfreezeInPlace` 补跑锁步骤」——实现是重跑完整的进场恢复（D34）；第 12 条的 `OUTBOX_MAX_AGE` 之外又加了已销账墓碑（D29）；第 19 条「PARITY 改附 D1–D28」——现为 D1–D36。
落地阶段的审计、修复与评审记在末尾的实现记录里，不在本节。

---

## 实现记录（robot 与 dev gather，2026-10-05）

范围：§7.18 dev gather、§13.8 robot `battle-settle`，以及这两项依赖、契约那一步没有落地的最小共享件。scene 侧（`SceneBattleProvider`、冻结 / 闸 / 应用）与 battle 侧发件箱不在这一部分。

**共享件**（形状按 §7.3 与 match-spec 的 `NodeRpcClients<S>` 条目；后到的批次只引用、不重建）

- xm-api：`SceneBattleService`；`xm/api/scene_battle.proto`（同 §7.3）；`DubboGroups.SCENE_BATTLE`，`SCENE_ASSET` 的注释已同步；
  `battle_control.proto` 追加 `DevGatherMode / DevGatherMember / DevGatherRequest / DevGatherPrepareResult / DevGatherResponse`，并 import `scene_battle.proto` 以引用 `SceneBattleStatus`。
- xm-api：`com.game.api.rpc.NodeRpcClients<S>`（Q20）。按 (host, port) 缓存编程式引用，实例变了就重建；`retries = 0`；引用在自己的守护线程上建；重连间隔与心跳都是 1 s（同资产通道）。
  `SceneAssetOpClients` 还**没有**改成它的薄包装，留到与 guild 资产通道同批合入时再改，这次不动 guild 的调用面。测试：`NodeRpcClientsTest`（5 条，真 Triple 回环）。
- xm-battle：`RoomOrigin.DEV_GATHER`，加 `settles()` / `publishesResult()`。`BattleRoomServiceImpl` 的结算与结果出站改按这两个方法判断：
  DEV 仍不结算；DEV_GATHER 照常结算、不发结果事件；MATCH 不变。`ResultRoutingTest` 补 2 条。
- xm-battle 指标：`xm_battle_dev_gather_total{mode, result}`。与 §9 相比多两个取值：`mode=unknown` 用于解析请求体之前就被拒的请求（403、坏请求体）；
  `result=rejected` 用于 400 和节点没在运行的 503。

**dev gather**（`com.game.battle.admin`：`DevGather` 负责编排，`DevGatherController` 是 HTTP 入口，`DevGatherConfiguration` 是独立装配）

装配：定位用 `SceneAssetLocator`（位置记录只认 `o`，再查 scene 目录），调 scene 用这套装配自己的 `NodeRpcClients<SceneBattleService>`。Dubbo 模型惰性创建，prod 下请求在 403 处就停了，不会建连接。

对 §7.18 表格的细化：

1. **422 的应答体**：422 也带 protobuf `DevGatherResponse`。`failure` 写原因；`prepare_results` 给出每人的 scene 状态和 `PrepareBattleResponse` 字节；`cancelled_player_ids` 列出补发了取消的人。
   robot 靠它读出 1006 这类业务码。400 / 403 / 503 仍回 text/plain，与 dev/create 相同。
2. **哪些人要补发取消**：备战成功的人，加上结局不明的人。结局不明指传输失败、`UNSPECIFIED`、应答体解析失败、`HANDLED` 但没有快照或 player_id 不符。
   以下三种零副作用，不取消：`HANDLED` 带业务拒绝码、`NOT_HERE`、`OVERLOADED`。取消按 player_id 无符号升序，发往当时备战的那个节点。
3. **建房失败的处理**：结局不明时（超时、异常、admission `UNSPECIFIED`、应答体坏）先发一次幂等的 destroy，再逐人取消。`NOT_ALLOCATABLE` 和业务错误零副作用，直接取消。
4. **指纹**：全员指纹不一致时不建房，全部取消（同 match）。`CreateBattleRequest.table_fingerprint` 取全员一致的那个值，`created_at_ms` 取 battle 本机时钟，快照按 player_id 升序排。
5. **时限**：主路径（定位 + 备战 + 建房）整体不超过 10 s；单次 scene 调用 5 s，大于 scene 侧最坏的 4.2 s；建房结果最多等 5 s。
   补偿取消另有 6 s 预算，免得主路径超时后冻结一直留到备战期限。
6. **请求形状校验（400）**：mode 合法；battle_id ≠ 0；deadline_ms ≠ 0；成员 1–10 人；player_id 非 0 且不重复；team_index 只能是 0 或 1。
   `PrepareBattleRequest.battle_node_id` 取本节点的租约号；节点没在运行时回 503。
7. **cancel-prepare**：定位到持有者就发取消，scene `HANDLED` 回 204。玩家此刻没有持有者节点时回 422：Java 断线即写回，离线玩家没有可以代跑 `CANCEL_OFFLINE` 的节点，dev 接口不代办。
   定位故障、传输失败、非 `HANDLED` 都回 503。

测试：`DevGatherTest`（18 条，纯编排）、`DevGatherControllerTest`（6 条，真 Tomcat + 真控制面 + 桩房间 + 指标）、`DevGatherControllerProdTest`（1 条）、`BattleMetricsTest` 补 1 条。

**robot `battle-settle`**（xm-robot）

新增 `BattleSettleScenario`；纯函数放在 `BattleSettleChecks`，由 `BattleSettleChecksTest` 覆盖（9 条）。`BattleAdminClient` 加了 `gather / gatherRaw / cancelPrepare`，
请求与应答都按字段号编解码，`BattleAdminGatherTest`（6 条）用 xm-api 的生成类逐字节钉住字段号。子命令是 `battle-settle`，`--slow` 加跑慢用例，`--expect-dev deny` 只核对 403。

与 §13.8 的出入：

- **没做的步骤**：第 11 步（与 5.2 互斥，要双 scene 切片）和第 12 步（队伍视图，属 team 场景）不在本场景里。
  （这是 2026-10-05 当时的状态；两步之后都补进了本场景，第 13 步也改成逐闸断言，见下一段实现记录与 §13.8「落地后的现状」。）
- **第 4 步 173**：只断言不回 25011。1 级角色没有剩余点，会回 25014，这同样算「照常」。
- **A、B 不在同一频道**：B 先发 63 换到 A 的 scene_id。
- **63 会真的换图**：第 7 步「闸已解除」就是一次真换图；第 10 步的判废时刻取 63 第一次成功的时刻。
- **reaper 等待**：按缺省 30 s 间隔取上界（再加 2 s）等待，实际时延写进检查细节；切片把 `xm.scene.battle.reaper-interval` 调到 2 s 时，实际值远小于上界。
- **第 9 步的等待**：不用固定的 15 s，而是轮询 dev/issue-ticket，直到房间消失（回 1005），再等 3 s（`--slow` 等 130 s）才登录。
- **任务 12**：只在 `settlement.defeated_monsters` 里有怪物 1 时才检查；没有就记一条失败并写明 outcome。胜负按基线判 SIDE_A_WIN。
- **第 13 步指标**：指标名照 §9 写死；scene 指标抓不到时只记观察记录，不判失败。
- **结尾汇总**：在观察记录里写一行 `BATTLE_SETTLE_OK …` 或 `BATTLE_SETTLE_FAIL step=…`。

**运行证据**（本机单 scene 切片，2026-10-05 13:05，scene 侧这时还没有导出 `SceneBattleService`）

- `battle-settle`：第 1、2 步通过。从第 3 步起 gather 一律 422，原因是 `UNIMPLEMENTED : Invoker for gRPC not found`。
  这说明 dev gather 已经在真进程里装配起来，链路走通了：经位置记录与目录定位 → `NodeRpcClients` 带调用方 MAC 连到 scene 的 21100 → 按结局不明补发取消 → 422 的 protobuf 体被 robot 解出。
  第 3–10 步要等 scene 侧落地后再跑。
- 6.2 的 `battle` 场景 60 / 60 通过，`RoomOrigin` / 出站判定的改动没有回归。
- xm-battle.log 里有 8 行 ERROR，都是 Dubbo 调用方的 `RpcExceptionFilter` 在记上面那个 UNIMPLEMENTED，scene 导出 `SceneBattleService` 之后就会消失。

---

## 实现记录（scene 侧、发件箱与评审修复，2026-10-05 / 06）

范围：scene 侧（冻结 / 在途闸 / 结算应用 / 账本与销账 / 提供方）、xm-discovery 的键与脚本、battle 侧两个发件箱与确认传输、gate 的 D12、xm-team 的 `in_battle`，
以及 robot `battle-settle` 的补全与故障变体。过程：主代码在提交 `7dff75c` 入库 → 分六个区做审计并逐条复核（另有第七个区是 7.2b，见 data-ops-spec）→ 第一轮修复与补测试 →
逐模块评审与评审修正（到这里为止的代码与测试在提交 `4f3f345`；CI 上一处测试竞态修在 `d6b6b1e`）→ 第二轮修正（7 项）及其评审修正（2 项），与文档一起见其后的提交
（git log「批次 6.3 收尾」）。正文 §7–§13 已按本节的事实回写；本节只记正文里放不下的东西：类的落点、缺陷清单、证据与遗留。

### 落地的类与职责

**xm-discovery**（`com.game.discovery`、`com.game.discovery.battle`）

- `RedisKeys`：`battleLock` / `battleSettlements` / `battleSettled`（已销账墓碑）/ `battleActivityResult`；前三个共用 `{pid}` hash tag。
- `BattleRedis`：全部 Lua 文本（`PREPARE_LOCK`、`CONFIRM`、`CANCEL_OFFLINE`、`DELETE_IF_MATCH`、`TOUCH`、`HOLD`、`ACK`、`ENTER_READ`、`READ_IF_OURS`、`READ_LOCK_BATTLE`、
  `DELETE_SETTLEMENT_FIELD`、`STORE_SETTLEMENT`、`PROBE_SETTLEMENT`、`ACK_IF_SUPERSEDED`、`STORE_ACTIVITY_RESULT`、`PROBE_ACTIVITY_RESULT`）、返回码与跨进程常量、
  纯函数 `lockTtlSec` / `parseUnsigned`，结果形状 `EnterRead` / `SettlementField`。类注释逐段写明重放语义与「与销账交错」的结论。全部方法异步，future 在 Redisson 回调线程上完成。
- `BattleLockReader`：`exists`（普通读，咨询性）/ `battleId`（经脚本，读主库）/ `existsAll`（按入参顺序去重，任一读失败整体异常完成）。

**xm-scene**

- `com.game.scene.battle.PlayerBattle`：挂在 `ScenePlayer` 上的运行态——冻结、恢复状态、不重入标记、恢复代际、本实例已销账集合。
- `BattleFreeze`：一名玩家身上的冻结；纯函数 `fromLock`（§1.2 的取值规则）、`carriedCopy(sameSession)`。
- `BattleLedger`：持久账本（`player_state.battle_ledger = 9`），加载校验、条目级未知字段、`persistedHas`。
- `PlayerBattleService`（实现 `BattleHooks`）：备战 `prepare`、取消 `cancel`、确认 `confirm` / `onLateConfirm`、进场恢复 `onEntered` / `startRecovery` / `recoverFrom` / `rebuildFromLock`、
  原地解冻 `onUnfrozenInPlace`、reaper `reap` / `reapFighting` / `onRescueRead`、结算到达 `deliver`、收口 `applyAndFinish`、销账 `writeOff` / `ack` / `onPersisted`、解冻 `clearFreeze`、
  删锁结局的收尾 `deletePreparingLock` / `dropPreparingRebuiltFromLock` / `followCurrentIfLockDeleted`（第二轮修正）、备战回调出错的收拾 `abandonPrepare`、
  `pushReconnectOnce`、加载期检查 `checkLedgerOnLoad`、回调投递 `onLogic`（带「没跑完」的出口）。
- `BattleSettlementService`：一份结算在一名玩家身上的应用（a–l 步，§7.11），有宝宝条目时直接推 184。
- `BattleSnapshots`：玩家快照的纯组装；`SceneBattleTables`：启动时建一次的配表视图（指纹、技能存在 / 可施放、`battleUsable`、职业行）。
- `BattleLocks`：scene 侧的异步 Redis 端口（12 个方法），生产实现是 `BattleLocks.redis(BattleRedis)` 返回的委托。
- `SceneBattleProvider`：`SceneBattleService` 的 Dubbo 提供方（名额 → 实例核对 → 解析 → 投递逻辑线程 → 回写线程完成）。
- `com.game.scene.world`：`BattlePolicy`、`BattleHooks`（`onEntered` / `onPersisted` / `onUnfrozenInPlace`，`NONE` = 不接战斗）；`SceneWorld` 的 `haltForBattle`、public 的 `switchInFlight`、
  `beginRemoteSwitch` / `onSwitchTargetChosen` / 排空改派 / 帧外推里的战斗判断；`ClientRequestHandler` 按 `BattlePolicy` 的入口闸。
- `com.game.scene.rpc.SceneRpcServer`：一个 Dubbo 模块、一个端口、两个服务。`com.game.scene.team.TeamFollowService`：并行读锁、`onBattleFreezeCleared`。
- `com.game.scene.metrics.SceneBattleMetrics`（本批全部战斗计数，构造时预建）；`SceneMetrics` 的 `BattleGate` 与各 `in_battle` 取值。
- 各服务闸：`AttributeService.checkWritable`（25011）、`PetService.checkWritable`（26008）、`BagFeature.sortBag`（1005）、`SkillService` / `SkillRules`（7004 / 7002）、`AssetOpService`（27002）；
  `PetService.buildBattleSnapshot` / `applyBattleSettlement`；`BagService.removeClamped` / `mergeOnly`、`Bag.drainClamped`。
- `SceneNode`：装配（`BattleLockReader` → 跟随的锁探针；`PlayerInitializer` 里的两个账本检查；reaper 按 `xm.scene.battle.reaper-interval` 注册在逻辑线程上）。

**与 §7.1 原稿类划分的出入**（审计 STL-12；裁决为不改代码、记在这里）：没有 `SettlementWriteOff`——销账并在 `PlayerBattleService.writeOff / ack / onPersisted`；没有 `BattleItems.usable`——
判据在 `SceneBattleTables.battleUsable`；没有 `RedisBattleLocks` 类；结算到达的分流在 `PlayerBattleService.deliver` 而不是 `BattleSettlementService`；184 由 `BattleSettlementService` 直接发，
不经 `PetFeature`。行为等价；分流与销账放在一个类里，四条应用路径才只有一个收口（`applyAndFinish`）。

**xm-battle**

- `com.game.battle.outbox`：`SettlementOutbox`（实现 `SettlementSink`；端口 `Store` / `Locator` / `Transport`）、`SettlementRetryRules`、`ActivityResultOutbox`（实现 `ActivityResultSink`）、
  `ActivityRetryRules`、`OutboxMetrics`。
- `com.game.battle.port.scene`：`SceneTransport`（一条 `battle-outbox` 线程、一个 `NodeRpcClients<SceneBattleService>`、定位器与 scene 目录；交出三个真实端口；`close` 有界排空）、
  `SceneTransportProperties`、`DubboSceneBattleEvents`（端口 `Directory` / `Locator` / `Transport`）、`SceneClientSweeper`（包内可见）。
- `com.game.battle.port`：`BattleResultSink` 的抽象方法改为 `publish(event, Channel)`（6.4 接真实传输时按新形状实现）；`SettlementSink` / `SceneBattleEvents` / `ActivityResultSink` 的契约注释重写。
  `LoggingBattleResultSink` 按通道计数。
- 配置：`application.yaml` 补两个键与文件头的 Redis 读写说明。

**xm-gate**：`MessageRoute.directOnly`、`MessageRoutes.DIRECT_ONLY_SERVICES` / `directOnly(method)` / `all()`、`GateMetrics.RequestResult.BATTLE_REJECTED` 与 `registerRequest`、`ClientDispatcher.onRequest` 的直连闸。

**xm-team**：`TeamDisplay` 多一个依赖 `battleLocks`（构造器三参数）；`TeamConfiguration` 新增 `BattleLockReader` bean，`teamDisplay` 注入 `battleLocks::existsAll`（6.4 的整队开战预检可以直接复用这个 bean）。

**xm-robot 与脚本**：`BattleSettleScenario`（补第 11、12 步，第 13 步逐闸）、`BattleSettleChecks`、`BattleCrashScenario` / `BattleCrashChecks` / `CrashWindowOptions`、`tools/local/battle-crash-window.sh`。

**lead 的三处小改动**：根 `pom.xml` 钉 maven-surefire-plugin 3.2.5 并加 `forkedProcessTimeoutInSeconds = 1800`（某条用例无限等待时构建在 30 分钟后失败，而不是把测试进程永远挂住）；
`SettlementOutbox.dispatch` 入口挡住空的结算（+ 用例），`xm-battle/pom.xml` 的 description 补 6.3 起的 Redis 使用面；xm-data 的 `PersistedPlayerMapper` 区号按无符号绑定（属 7.2b，见 data-ops-spec）。

### 审计确认并修掉的缺陷

编号是审计底稿里的（RDS = 键与脚本，FRZ = 冻结生命周期，STL = 结算与账本，GAT = 在途闸与提供方，OBX = 发件箱，OPS = 指标 / 配置 / robot）。同一缺陷被两个区各报一次的并成一条。

| 审计编号 | 缺陷（修之前） | 修法 |
|---|---|---|
| FRZ-1（critical） | reaper 同一轮里对同一名 RETRY 玩家先发销账、紧接着发恢复读；快照早于销账而回调晚于 `forget` 时，已销账的局被再应用一遍——重复发奖 | reaper 对 RETRY / PENDING 不排空账本；恢复代际 + 本实例已销账集合，收口在 `applyAndFinish`（D32） |
| RDS-6 / OBX-7 | 落库只对自己幂等：迟到 / 被重放的落库落在销账之后会把记录重新造出来，下次进场恢复重复发奖 | 已销账墓碑（D29）；发件箱把 -1 当已销账 |
| FRZ-2 / STL-8 / OPS-11 | 逻辑线程回调抛异常被吞：恢复永久停在 PENDING；备战 / 按锁结算的应答 future 永不完成，提供方的在途名额泄漏 | 恢复 → RETRY + `recovery{error}`；`onLogic` 加「没跑完」出口，应答异常完成；`abandonPrepare` |
| RDS-7 / FRZ-5 | `TOUCH` 无条件覆写，能把已确认的锁降回 P、把 TTL 缩回备战期；迟到确认回来时已有同局 PREPARING 冻结就丢弃 | `TOUCH` 单调（D30）；迟到确认升级同局 PREPARING（D33） |
| FRZ-7 | 在线取消、备战失败、stale 的删锁不看 `s`，被排到确认之后时留下没有锁的 FIGHTING 冻结 | 只删备战锁（D31） |
| FRZ-6 / RDS-10 | stale 分支只在「无错且回 0」时删锁，写锁结局不明时孤儿锁留到备战 TTL | 结局不明也尽力只删一次备战锁 |
| FRZ-3 | 按锁重建 FIGHTING 后复核脚本出错时不推 144，之后的补发确认走零 Redis 分支也不会再推 | 出错分支照样推（重建时是 FIGHTING 的），并把 `lockExtended` 置回 false |
| FRZ-4 / RDS-8 | `TOUCH` 回调按回调时刻的阶段决定推不推，重建 PREPARING、复核在途时确认到达会推两条 144 | 按重建时捕获的阶段判断；全部推送经 `pushReconnectOnce` |
| RDS-9 | 事件不带期限的迟到确认只标了 F 没续期，却置 `lockExtended`，锁停在备战 TTL 提前过期 | 不置 `lockExtended`，立即按锁里的期限补续一次 |
| FRZ-9 / GAT-13 | 原地解冻后重跑的是完整恢复而规格与注释写「只补锁步骤」；与在途的更早一次恢复读可能各跑一遍 | 保留完整恢复、加代际号，规格与注释改成与实现一致（D34） |
| FRZ-10 | §1.2 的取值规则内联在私有方法里，没有可测的接缝 | 抽成纯函数 `BattleFreeze.fromLock` |
| GAT-14 | 备战闸直接看 `switchPhase`，过期的 RESOLVING 槽（回调丢了）一直把备战挡成 1006 | 改经 `SceneWorld.switchInFlight`（过期即清槽） |
| STL-9 | 按锁回调先判「回调时已有别的局的冻结」再判锁，与规格和基线的次序相反（该销账的没销） | 对调为先锁后冻结 |
| STL-10 | 账本往返丢条目级未知字段（滚动升级 / 回滚时被旧版本抹掉） | 旁挂条目级 `UnknownFieldSet` |
| STL-11 | 账本损坏时进场不报，备战被拒不打日志 | `checkLedgerOnLoad` 报一次 ERROR；被拒时打带原因的 WARN |
| RDS-11 | 待结算字段名经 String 往返，含非法 UTF-8 的坏字段删不掉 | 字段名保留原始字节；只认规范的无符号十进制 |
| RDS-13 | 三个驱动丢弃 / 判废的读以 `READ_ONLY` 执行，主从部署下会读到从库的旧值 | 全部脚本 `READ_WRITE` |
| RDS-12 | 探测等包成了 Lua 而规格写「不需要 Lua」，新脚本没有重放语义的说明与用例 | 类注释补齐 16 段脚本的重放语义，全部纳入连跑两次的用例；规格回写（§7.2） |
| OBX-9 | 探测的空结果被当成「记录不在」→ 当已销账摘除 | 归 `probe_error`；落库 / 定位的 null 与 null future 同口径 |
| OBX-8 | 探测到已销账摘除后晚一轮才停表 | 每个摘除点之后当场查 |
| OBX-10 / RDS-14 | `HLEN` 超阈值只打 ERROR 不计数 | 新取值 `fields_overflow` |
| OBX-11 | 直连客户端缓存只增不减 | `SceneClientSweeper` |
| OBX-12 | 活动局的结果计在 `plain` 通道 | 发布端口带 `Channel`，活动局计 `activity` |
| OBX-13 | 目录条目端口越界时异常被无人持有的 future 吞掉，这次确认没有任何计数结局 | 越界按实例不符回落；回调体的异常计 `error` 并打 ERROR |
| GAT-2 / GAT-8（gate） | D12 没有落地：战斗上行仍走 unsupported 域 → 待处理队列 → 缺省分支，三处细节差异都在 | `directOnly` 直连闸 + 指标预建 + 用例（§7.19） |
| GAT-1 / RDS-5 / OPS-12 | `TeamMemberView.in_battle` 仍恒为 false，`BattleLockReader.existsAll` 没有调用方 | xm-team 接上，team-spec D10 收口 |
| OPS-13 / OPS-14 / OPS-22 | rebuild 指标把「坏字段」「常态的账本命中」「延后」都计成 `skipped_corrupt`；放技能取值与规格不一致；几处口径没写 | 新取值 `skipped_pending` 并三分；放技能保留两个取值；口径写进注释与 §9 |
| OPS-15 / OBX-16 / OPS-21 | 两个 `xm.battle.*` 键不在 yaml、文件头过时；`delivered` 的口径没写 | yaml 补齐；注释与 §9 写明 |
| GAT-15 / RDS-16 | `TeamFollowService` 类注释仍写「不读 battle:lock」，与实现矛盾 | 注释重写；规格 §7.13 第 3 条按实现改 |
| RDS-4 | 6.2 的约束单测仍私有写死 60_000 | 引用 `BattleRedis.LOCK_EXTRA_TTL_SEC` |
| OPS-18 / OPS-19 / OPS-20（robot） | 没有故障变体；第 13 步不逐闸；普通检查失败不登记步骤号 | `BattleCrashScenario` 与脚本；逐闸断言；`step()` 登记失败步骤，汇总行抽成纯函数 |
| RDS-1–3、FRZ-11–16、STL-1–7、GAT-3–7、OBX-1–6、OPS-16 / 17 | §13 计划里的测试类大多不存在 | 全部补齐（§13 开头的分布表） |

复核判为不成立、没有改的：FRZ-8（同一行为由 GAT-14 的修法覆盖）、GAT-12（镜像取号回来后不复查战斗）、OPS-23（B7 + B14 的基线怪癖）。area7 的两条与战斗有关（回档前查战斗锁），修法在 xm-data（`BattleLockGate`），记在 data-ops-spec。

**评审阶段的发现**（都是测试质量或文档问题，主代码没有再发现缺陷）：gate 2 条（`MessageRoutesTest` 的守卫用错了基线判据、既有用例取到了 `directOnly` 的号）；team 3 条
（类级 `@Timeout` 缺省是 SAME_THREAD、兜不住 `join()` 式的无限等待；缺中断形态的用例；注释把 MGET 的行为写错）；battle 两轮共 7 条（停机排空的接线没有判别力、OBX-7 的回归用例只断言了假件、
线程所有权只钉了一跳、清扫的条件摘除没有用例、「最后一轮已销账」在发件箱层没有用例、恒等式断言、结算投递那条路没有登记断言）；scene 测试 4 条（复核回调的实例 / 冻结守卫没有用例、
补跟随的位 2 判据分不出、假锁的 `PREPARE_LOCK` 与 Lua 不一致、a–d 步用例的断言恒真）；robot 两轮共 6 条（`gold_gain = 0` 时故障变体空过、verify 不看 arm 记下的结局、脚本放行过期的 robot 包、
双 scene 只给一个指标地址时第 13 步误报、超时分支没有结束 robot、时间常量只与字面值比）。全部修掉或补了用例，各自做过变异核对。
team 的那条有一个值得记的通用结论：本仓库 JUnit 5.12.2 的 `@Timeout` 缺省 SAME_THREAD，到时只发一次中断，对 `CompletableFuture.join()` 无效；要靠它兜「无限等待」回归必须显式写 `SEPARATE_THREAD`
（`TeamDisplayTest` 已改；别的模块没有逐个排查）。

**CI 上的一处测试竞态**（提交 `d6b6b1e`；不是功能缺陷）：`4f3f345` 在 GitHub 上「构建与单测」失败在 `SceneBattleProviderTest` 的
`正常路径_四个方法进逻辑线程_应答在回写线程上完成_备战等写锁结局回来才完成` 一条——用例先对应答调 `get()`、后等观察者，被唤醒的测试线程会帮着跑 future 的后续（`CompletableFuture` 的 postComplete），
观察者偶尔记到 `main`。改成先等观察者、再取应答；同样写法的旧用例 `SceneAssetOpProviderTest`（批次 4.5 起就有）一并改了。

### 第二轮修正（2026-10-06）

第一轮的遗留里，下面 7 项由 lead 裁决再修一轮（a–g），随后的评审又给出 2 条低级别的既有缺口（h、i），一并修掉。逐项回到代码核对过，都已在工作区的主代码里；
这一轮没有新增配置键、Redis 键、指标取值、第三方依赖。

| # | 内容 | 落点 |
|---|---|---|
| a | 本节点发出的只删备战锁有了结局时，现任实例（多半是同 epoch 重进的新实例）挂着同一局、非本实例备战的 PREPARING 冻结就一并摘掉——否则是一把没有锁的冻结留到备战期限。返回 2 不摘，返回 1 / 0 / 出错都摘；五个适用点；计 `rebuilds{login, reverted}` | `PlayerBattleService.dropPreparingRebuiltFromLock`，由 `deletePreparingLock`（备战的三处尽力删锁）、`clearFreeze(DELETE_PREPARING)` 的回调、离线取消 `CANCEL_OFFLINE` 的回调调用；§7.5 第 4 步、§7.6 |
| b | 同会话重复进场沿用冻结时 `preparedHere` 照抄旧值（不再多推一条 144，同基线）；会话变了才置假 | `BattleFreeze.carriedCopy(sameSession)`、`PlayerBattleService.onEntered`；§7.8 第 0 步、D7 |
| c | 备战对期限加上界（7 天）：过大的期限会让 `PREPARE_LOCK` 的 EXPIRE 报错、留下无 TTL 的锁 | `PlayerBattleService.MAX_DEADLINE_AHEAD_MS`、`prepare`；§7.5 第 1.1 步、D36 |
| d | 组队跟随判换图在途时同样让过期的 RESOLVING 槽失效 | `TeamFollowService.followLeader` 改经 `SceneWorld.switchInFlight`（现在有三个调用方）；§7.13 第 3 条 |
| e | 帧外推跳过战斗在途的玩家（同基线 `movement.cpp:49-52`；纵深防御） | `SceneWorld.integrate`；§7.13 第 6 条 |
| f | 销账放了锁（位 2）而回调时实例已换：对现任实例（没有冻结时）补一次组队跟随 | `PlayerBattleService.ack` 的回调；§7.12 |
| g | xm-common 的 `Deadline.await` 中断分支补直接用例（主代码没有改） | `DeadlineTest`（等待中被中断、调用时已带中断标志两条） |
| h | 评审修正：要删锁的解冻（取消、reaper 判废）与不跟着解冻的删锁（备战失败 / 过期后的尽力删锁、离线取消），删锁结局回来时实例已换、现任实例上又没有可摘的冻结——原来不给现任实例补跟随（与 f 同一种丢失）。改为锁确实是这一次删掉的（返回 1）就补给现任实例 | `PlayerBattleService.followCurrentIfLockDeleted`（`clearFreeze` 的回调、`deletePreparingLock`、离线取消的回调）；§7.4「删锁结局回来之后补给谁」 |
| i | 评审修正：按 F 锁重建的 FIGHTING 冻结复核还在途（144 还没推）时同会话重复进场，沿用后这个会话永远收不到这一局的 144。改为沿用时推不推只看副本的 `preparedHere` 与阶段，不再另判会话 | `PlayerBattleService.onEntered`（沿用后无条件调 `pushReconnectOnce`）；§7.8 第 0 步、§9 的 `reconnect_hints` 口径 |

第二轮新增 / 改动的测试：`BattleFreezeCarryTest`（新增）、`DeadlineTest`，以及 `PrepareBattleTest`、`CancelBattlePrepareTest`、`ConfirmBattleTest`、`BattleRecoveryTest`、`BattleReaperTest`、
`SettlementApplyTest`、`TeamFollowBattleTest`、`InBattleGateMatrixTest` 里的相应用例。模块级的运行证据在下一小节（xm-scene、xm-common 两行）。

### 测试与模块级证据

都是**模块级**的运行（单个模块、`-o` 离线、经模块锁），日期 2026-10-05 / 06，命令前缀一律是
`source /d/work/.tools/env.sh && /d/work/.tools/mvn-locked.sh <模块> -s /d/work/.tools/settings.xml -B -o -pl <模块> test`，日志在 `D:/work/.tools/logs/`。带真依赖的全量构建与切片 robot 不在这里，见最后的「最终验证」。

| 模块 | 命令的其余部分（日志） | 汇总行 | 时点 |
|---|---|---|---|
| xm-discovery | `-Dxm.it.redis=redis://127.0.0.1:6379`（`sceneRv-disc-1.log`） | `Tests run: 196, Failures: 0, Errors: 0, Skipped: 0` | 10-06，scene 评审修正之后 |
| xm-discovery | 不带 Redis（`disc-4.log`） | `Tests run: 195, Failures: 0, Errors: 0, Skipped: 109` | 10-05，实现之后（之后又加了 1 条集成用例） |
| xm-scene | 整模块，不带 Redis（`r2rev2-full-scene-2.log`） | `Tests run: 1417, Failures: 0, Errors: 0, Skipped: 4` | 10-06 07:59，**第二轮修正及其评审修正之后**（最终状态的主代码） |
| xm-scene | 战斗相关类 + 真 Redis：`-Dtest='com.game.scene.battle.*Test,FakeBattleLocksTest,PetBattleSnapshotTest,TeamFollowServiceTest,SceneBattleMetricsTest,MovementSyncTest' -Dsurefire.failIfNoSpecifiedTests=false -Dxm.it.redis=…`（`r2-redis-scene.log`） | `Tests run: 597, Failures: 0, Errors: 0, Skipped: 0` | 10-06 06:36，第二轮修正（a–g）之后、它的评审修正（h、i）之前；h、i 不涉及 Redis 脚本，之后没有带真 Redis 重跑 |
| xm-scene | 整模块，不带 Redis（`sceneRv-full-2.log`） | `Tests run: 1382, Failures: 0, Errors: 0, Skipped: 4` | 10-06 05:39，第一轮评审修正之后、第二轮修正之前（留作对照） |
| xm-common | 整模块（`r2-full-common.log`） | `Tests run: 116, Failures: 0, Errors: 0, Skipped: 0` | 10-06 06:36，第二轮修正之后 |
| xm-player-store | 整模块 H2（`sceneC2-store-h2.log`） | `Tests run: 59, Failures: 0, Errors: 0, Skipped: 4` | 10-06 |
| xm-player-store | `-Dtest='PlayerStoreSqlTest' -Dxm.it.mysql=jdbc:mysql://127.0.0.1:3306`（`sceneC2-store-mysql.log`） | `Tests run: 30, Failures: 0, Errors: 0, Skipped: 0` | 10-06 |
| xm-battle | 整模块，不带 Redis（`r63c-full-2.log`） | `Tests run: 472, Failures: 0, Errors: 0, Skipped: 15` | 10-06，第二轮评审修正之后 |
| xm-battle | 整模块 + 真 Redis（`battle63-full-it.log`） | `Tests run: 460, Failures: 0, Errors: 0, Skipped: 0` | 10-05，实现之后（之后两轮评审修正各加了用例，没有带真 Redis 重跑整模块；`OutboxRedisIntegrationTest` 单跑过，`r63b-it.log`） |
| xm-gate | 整模块（`gate-review-3-full.log`） | `Tests run: 187, Failures: 0, Errors: 0, Skipped: 0` | 10-06 |
| xm-team | 整模块，不带 Redis（`teamfix-5-full.log`） | `Tests run: 248, Failures: 0, Errors: 0, Skipped: 53` | 10-05 23:35 |
| xm-team | `-Dtest='TeamDisplayTest,TeamDisplayRedisIntegrationTest' -Dxm.it.redis=…`（`teamfix-6.log`） | `Tests run: 22, Failures: 0, Errors: 0, Skipped: 0` | 10-05 23:36 |
| xm-robot | `package`（全部单测 + 重新打可执行 jar；`robotfix-full.log`） | `Tests run: 305, Failures: 0, Errors: 0, Skipped: 0` | 10-06 03:22 |

xm-scene 跳过的 4 条是缺省跳过的：`ScenePlanPullIntegrationTest`、`RedisGainBlockSourceIntegrationTest`、`FakeBattleLocksTest` 的真 Redis 对拍、`ViewCrowdBenchmarkTest`。xm-data 的战斗锁闸用例见 data-ops-spec。

各测试类的条数（取自上表各自那份日志里的逐类汇总行；只供对照规模，**以最终验证为准**）：

- xm-discovery：`BattleRedisScriptsIntegrationTest` 46、`BattleLockReaderIntegrationTest` 9、`BattleRedisConstantsTest` 15、`BattleRedisReplyTest` 14、`LockTtlTest` 6。
- xm-scene（`r2rev2-full-scene-2.log`，第二轮之后）：`PrepareBattleTest` 44、`CancelBattlePrepareTest` 39、`ConfirmBattleTest` 35、`BattleRecoveryTest` 34、`BattleReaperTest` 25、`SettlementDeliverTest` 26、
  `SettlementApplyTest` 24、`SettlementApplyValuesTest` 36、`BattleLedgerTest` 20、`BattleSnapshotsTest` 20、`FreezeFromLockTest` 11、`InBattleGateMatrixTest` 118、`HandOffBattleExclusionTest` 13、
  `TeamFollowBattleTest` 17、`SceneBattleProviderTest` 12、`BattleStaleReadTest` 13、`BattleCallbackFailureTest` 9、`BattleRebuildRegressionTest` 18、`BattleLockDeletionRegressionTest` 6、
  `BattleLedgerEntryFieldsTest` 6、`BattleUnfreezeRecoveryTest` 3、`BattleFreezeCarryTest` 2、`FakeBattleLocksTest` 11、`PetBattleSnapshotTest` 15、`SceneRpcServerTest` 8、`SceneBattleMetricsTest` 5；
  xm-player-store 的 `PlayerStoreSqlTest` 30；xm-common 的 `DeadlineTest` 7。
- xm-battle：`SettlementOutboxTest` 56、`ActivityResultOutboxTest` 19、`SettlementRetryRulesTest` 10、`ActivityRetryRulesTest` 6、`OutboxMetricsTest` 6、
  `OutboxRedisIntegrationTest` 4、`DubboSceneBattleEventsTest` 19、`SceneTransportTest` 6、`SceneTransportPropertiesTest` 8、`SceneClientSweeperTest` 9、`ConfirmWindowConstraintTest` 3。
- xm-gate：`BattleUplinkRejectedTest` 9、`ClientDispatcherTest` 95、`MessageRoutesTest` 17。
- xm-team：`TeamDisplayTest` 17、`TeamDisplayRedisIntegrationTest` 5、`TeamConfigurationTest` 2、`TeamViewsTest` 10。
- xm-robot：`BattleSettleChecksTest` 28、`BattleCrashChecksTest` 19、`BattleCrashScenarioTest` 11、`SliceScriptsTest` 13、`UpstreamConstantsTest` 6、`CrashWindowOptionsTest` 7、`RobotOptionsTest` 19。

**判别力核对（变异）**——每一处都是「临时改错、跑对应用例确认失败、还原并 `cmp`」：

- xm-discovery：去掉 TOUCH 单调、两处墓碑写入、落库的墓碑判断 → 三个相关类 `Tests run: 67, Failures: 11`（`disc-mutation.log`）。
- xm-scene 第一轮修复：把 `PlayerBattleService` 换回修复前的版本，当时的 85 条战斗用例 `Failures: 37, Errors: 3`（`scene1-mut.log`），失败原因与各缺陷对应
  （FRZ-1：金币 expected 100 but was 200；FRZ-4：144 为 [7, 7]、期望 [7]；FRZ-2：recovery expected RETRY but was PENDING；STL-9：销账 X 的 ACK 次数 0、期望 1）。
- xm-scene 三组测试：A 组 140 条单点变异，补 2 条用例后全部被杀（1 条为等价变异、用伪造回复区分）；B 组 47 个全部被杀；C 组 115 个，114 个判红、1 个等价。评审修正另做了 7 处变异（`sceneRv-mut-*.log`），全部判红。
- xm-scene 第二轮修正（a–f）：30 条单点变异（去掉某个删锁调用点的摘冻结、四条守卫各去一条、`preparedHere` 的三种错法、期限上界的七种错法、销账补跟随的五种错法、换回 `switchPhase`、
  去掉帧外推的跳过等），在相关的 10 个测试类（未变异时 349 条）上逐条判红（`r2-mut-verdicts-scene.txt`）。xm-common（g）：3 条变异只在草稿区的模块副本里做，各让两条新用例失败
  （`r2-mut-verdicts-common.txt`）。评审修正（h、i）：8 条变异在相关的 224 条用例上逐条判红（`r2rev2-mut-summary-1.log` / `-2.log`）。
  一条经验：还原主代码时若保留原修改时间（`cp -p`），还原后的源文件比变异版编译出的 class 旧，Maven 增量编译不会重编，`target/classes` 里会留下变异版的 class——
  还原后要 `touch` 源文件再编一遍（这一轮在最后一个变异后发现并当场重编，上表 07:59 的整模块运行在重编之后）。
- xm-battle：8 处同时变异 → `Tests run: 95, Failures: 19`（`battle63-mutation.log`）；两轮评审修正各自的变异见 `r63b-*` / `r63c-mut-all3.log`。
- xm-gate：整体关掉直连闸 → `Tests run: 120, Failures: 11, Errors: 3`（`gate63-mutant.log`）。xm-team：限时等待改成 `join()` → 4 条超时失败、构建没有卡住；吞中断标志 → 2 条失败。
- 按设计存活的等价变异（没有用例能区分的防御性代码）：`applyGained` 里「只补没进主包的部分」（Java 的批量入包整批原子，主包失败时一件都没进）；e–j 外层的 `finally` 登记账本
  （各步自己已捕获 `RuntimeException`，`finally` 只在 `Error` 时有区别）；reaper 里「账本损坏就不排空」（损坏的账本 `battleIds()` 本来就是空的）；
  `ClientRequestHandler.register` 里「有应答的方法不能 DROP」的检查（玩法功能的 DROP 在注册入口已先被拒）；`abandonPrepare` 里「冻结还挂着就摘掉」那一支（没找到能让它成立的抛出点）。

### robot 与故障变体脚本的现状

- **`battle-settle`**：第 1、2 步有 2026-10-05 13:05 的切片证据（上一段实现记录，当时 scene 侧还没有导出 `SceneBattleService`）。**第 3–10 步在 scene 侧落地之后、以及后来补的第 11、12 步与改过的第 13 步，
  都还没有在活的切片上跑过**（写本节时的状态：实现、评审与修正阶段都不起本机切片，证据只有编译与纯函数单测 `BattleSettleChecksTest` 等；切片上的结论见最后的「最终验证」）。第 12 步依赖 xm-team 的 `in_battle`，切片里没部署 xm-team 时会如实失败（`step=12`）。
- **故障变体**（`scene-after-150` / `battle-after-store`）：`BattleCrashScenario` 的全部在线流程、脚本对真进程的 kill 与重启都没有在活的切片上跑过。证据只有：纯函数与状态文件编解码的单测；
  `bash -n`；第一轮在草稿目录里用假仓库树 + 假 java + 假节点做的编排模拟（36 项，覆盖两个变体的全流程）；评审修正之后只在沙箱里重跑了前置检查与 arm 段（重启与 verify 两段没有重跑）；
  真仓库 `--dry-run`（切片没在跑时如实报四条「没在运行」）。
- 已知的局限：`scene-after-150` 基本打不中「已应用、还没落盘」那一段（本机实测断点到 kill 有 6–186 ms，而落盘加销账只有几毫秒），结果几乎总是 `durable`，`recovered` 那条路径靠这个变体覆盖不到，
  要稳定打中需要服务端配合（例如 dev 下可延迟存盘的开关），没有做；`battle-after-store` 的前提（带灵狐的 1 级新号挂机能打赢 Dungeon 1，且在 105 s 内打完）没有实测，单测只钉住了 Dungeon 1 的怪 `gold_reward` 之和 > 0；
  故障变体不支持双 scene 切片（`scene-after-150` 被脚本拒绝，`battle-after-store` 由脚本自动带上两个指标地址）；重启用的是脚本所在 shell 的环境与 target 下当前的 jar（切片起来之后有人重新打过包，重启出来的就不是被杀的那一版，脚本不检查）。
- gate 的 `battle_rejected` 导出名没有在 `/actuator/prometheus` 上实抓（单测断言的是 Micrometer 名与标签）；下次起切片时可顺手确认 12 条序列初值为 0。

### 遗留与残余风险

设计层面的残余风险已写进 §10.5，这里列实现与测试层面的遗留（各份汇报里仍然成立的，去重归并）。

**scene**

- `confirm()` 入口不查已销账集合：已销账的局收到重复确认多一次 Redis 往返（回 nil，计 `mismatch`），无害。
- 第二轮之后仍在的窄窗口都登记在 §10.5：删锁 / 销账被重放且实例已换时补跟随会丢；沿用一个复核还在途的重建冻结时新实例不再复核；同一实例对同一局先后两次备战时
  第一次的过期回调会删掉第二次的锁；确认事件的期限没有上界；帧外推没有跳过交出冻结。
- 没有用例的组合：写锁在途（`lockPending`）时确认到达；迟到确认在「事件期限为 0 且锁里没有 `d`」时重建出期限为 0 的冻结；`TOUCH` 返回 2 之后的后续（冻结停在 PREPARING 到备战期限）。都在 §10.5 登记。
- `SceneNode` 的装配没有单测：`battleProvider` 用的实例 id 与目录相同、与资产通道共用回写池、`battle-rpc-max-inflight` 传入、两个服务一并导出、启停顺序——这些只有 robot / 本机切片覆盖
  （`SceneRpcServerTest` 的回写池是测试按生产线程名自建的）。「battle 导出失败时已导出的 asset 被撤」也没有专门用例（两个服务共用一个 `ProtocolConfig`，没有不改主代码就能让第二个失败的办法）。
- §7.13 提到而 Java 还不存在的入口没有用例：5.4 的 226（3025）、5.5 的 `begin(…, EVACUATE)`、7.2c 的回收。
- 两处次序没有用例钉：分发层 `frozen()` 与 `inBattle()` 的先后（规格写明互斥、先后不影响结果，只在指标上可见）；GM 闸与战斗闸的先后（现有方法上不可观察）。
- `a–d 步返回时零副作用` 的用例只对 a 步与 d 步做了整份状态比对；b 步（账本命中）只断言了账本、金币与气血。
- `PlayerRevive.reviveIfDead` 在气血上限为 0 且气血为 0 时回「满」到 0；基线注释说取不到上限时退回职业初值（那个函数的实现没有读，差异未证实）。只在派生上限缺配时出现，不属于 6.3。
- 几个测试写死了正式配表的现值（技能 13 行及其类型、物品 10 / 11 可用、职业初值、灵狐的数值等）：契约表同步后变了，这些断言会失败并指出要重看。
- `DubboAuthProviderFilter`（xm-api）给调用方的中文原因经 Triple 传过去只剩问号，调用方日志里只有状态码 `UNAUTHENTICATED`；改成 ASCII 文案即可，不属于本批文件。
- `FakeBattleLocksTest` 的真 Redis 对拍与 xm-discovery 的脚本用例缺省跳过，日常回归只跑字面期望。

**xm-discovery / xm-battle**

- `BattleRedisConstantsTest` 里的 180 s / 96 s / 5000 ms 是字面值（不能反向依赖 xm-battle / 6.4）；`ConfirmWindowConstraintTest` 里 match 的 96 s 也是字面值，等 6.4 有了常量再换。
- `DevGatherConfiguration` 自己那份 `NodeRpcClients<SceneBattleService>` 没有接清扫（只在 dev / test 下建连接）。
- `SceneTransport.close` 不排空活动结果通道（§10.5）；`drainAndClose` 之后才回来的落库成功仍会首投一次、不登记。
- `OutboxRedisIntegrationTest` 没有覆盖 `ACK_IF_SUPERSEDED` 回 0 / 1 的「保留」分支（真线程上难以确定地等到往返结束），这两格由假 Store 的用例和脚本真值表覆盖；
  「已取代判定的结局」这一跳的线程所有权没有真线程证据；「先落库后投递」的次序只有正向断言、没有做变异。
- `SceneTransportTest` 慢（整类约 17–22 s：对刚关掉的本机端口建 Dubbo 引用要等连接被拒）且带墙钟断言，机器极端繁忙时理论上可能误报；要压下来得让客户端缓存可注入，属装配改动，没做。
- `SettlementRetryRules.classify` 的 `stillOurs` 形参与走不到的 `DONE` 分支有意保留（§7.15）。
- `SceneAssetOpClients` 还没有改成 `NodeRpcClients<S>` 的薄包装（上一段实现记录）。

**xm-gate / xm-team**

- `MessageRoutesTest` 里的 package 驼峰化是简化版（按 `_ . -` 分词、各词首字母大写），不是 `strcase.ToCamel` 的逐字节复刻；对现行契约的 package 名结果相同。
  6.4 把 `MatchService` 接进 `SERVICE_BACKENDS` 后，用例「Java版未接入的客户端服务路由到unsupported_且不是directOnly」会带说明失败，届时直接删掉它（缺省分支由另一条用例继续钉住）。
- 整队开战预检的 4025 仍恒回 4027，属 6.4。
- `TeamDisplay` 原有的在线读 WARN 不带原始 cause；资料读或在线读若返回 null 会 NPE（生产实现都不会给 null，潜在问题，没有顺手改）。`TeamConfigurationTest` 的 Redis 是 Mockito 替身。
- `TeamDisplayRedisIntegrationTest` 用 DB 12（生产缺省库），与其它新测试的 DB 15 / 14 不一致，是否对齐未定（§8）。

**部署**

- 上主从 / 集群之前：Redisson `readMode` 设 `MASTER`，或给回档闸一个读主库的批量入口；届时全仓其余 `READ_ONLY` 的读（含发件箱定位用的位置读）一并审查（§10.5）。

### 最终验证（2026-10-06）

在含第二轮修正、评审修正与注释订正的最终代码上执行（本机 Windows 11，JDK 21.0.12；Redis 8.10.2、MySQL 8.4.11、Kafka 4.3.1 单机，与 CI 的依赖镜像同版本）。

**全量构建**：`./mvnw -B clean install -Dxm.it.redis=redis://127.0.0.1:6379 -Dxm.it.mysql=jdbc:mysql://127.0.0.1:3306 -Dxm.it.kafka=127.0.0.1:9092`
→ 26 个模块 BUILD SUCCESS，7096 条用例、0 失败、0 错误，跳过 1 条（`ViewCrowdBenchmarkTest`，缺省关闭的基准）。各模块条数：
xm-proto 7、xm-table-codegen 18、xm-table 22、xm-net 21、xm-common 116、xm-battle-engine 2236、xm-pbmysql 127、xm-api 43、xm-discovery 196、xm-player-store 59、
xm-gateway-store 10、xm-audit 8、xm-scene-manager 232、xm-login 170、xm-friend 84、xm-chat 10、xm-team 248、xm-guild 531、xm-trade 175、xm-scene 1417、xm-battle 472、
xm-gate 194、xm-gateway 90、xm-data 305、xm-robot 305。

**GitHub Actions**：`4f3f345`（第一轮修复与测试）的 Integration 通过，CI「构建与单测」失败在 `SceneBattleProviderTest` 的测试竞态（不是功能缺陷，见上面「评审阶段的发现」）；
`d6b6b1e`（只改那两条测试的等待次序）的 CI 与 Integration 都通过。本次收尾提交的结果以它自己的 run 为准。

**本机单 scene 切片**（`tools/local/start-slice.sh`，12 个服务进程，dev 运行模式）：

- robot 29 个场景全部通过（括号里是检查项数）：smoke(3)、movement(20)、currency(7)、attribute(11)、bag(11)、features(20)、skill(15)、pet(23)、token(12)、reconnect(9)、
  friend(26)、chat(8)、team(38)、guild(75)、guild-economy(78)、trade(79)、audit(3)、guard(5)、rollback(19)、mirror(47)、dungeon(31)、battle(60)、battle-edge(20)、
  **battle-settle(72)**、zones(15)、killswitch(6)、queue(7)、drain(7)、ratelimit(5)。
- `battle-settle`：第 1–10、12、13 步通过（第 11 步要双 scene 切片），汇总行 `BATTLE_SETTLE_OK … gold=22 mission=12 relogin=ok offline=ok`；
  `--slow`（备战到期、离线结算等满 130 s 重投窗口）77 项通过；`battle-edge --slow` 21 项通过。
- 故障变体（`tools/local/battle-crash-window.sh`）：`scene-after-150` 通过（arm 6 项、verify 8 项，`outcome=durable`：kill 落在落盘之后，重登不重发、金币恰好增一次）；
  `battle-after-store` 通过（arm 15 项、verify 8 项，`outcome=rescued`：battle 被杀后没有人重投，期限 + 10 s 之后 scene 的 reaper 读到记录并应用，金币恰好增一次）。
  `recovered`（kill 落在「已应用、未落盘」）这一支本机没有打中，原因见上面「robot 与故障变体脚本的现状」。
- 服务端日志里的 ERROR 只有：故障变体杀掉 scene 期间 xm-battle / xm-guild 连它的 21100 失败的 Dubbo 重连日志，以及 guild、killswitch 两个场景的负面用例各触发的一条。

**本机双 scene 切片**（`XM_SCENE_NODES=2`）：smoke(3)、cross-node(41)、**battle-settle(77，给两个节点的指标地址；含第 11 步：PREPARING 时 63 指向另一节点 → 3023，
取消后同一条 63 跨节点成功)**、reconnect(9)、mirror(40)、dungeon(31)、battle(60)、guild-economy(78)、rollback(19)、friend(26)、chat(8)、guild(75) 全部通过；
`battle-after-store` 通过（`outcome=rescued`）。`movement` 要求两个号落在同一场景实例，双 scene 切片上按它自己的前提不适用，没有计入。

**切片上暴露并处理的两件事**（都不在 6.3 的服务端代码里）：

1. robot `team` 的 S6（队长换图、队员跟随）在双 scene 切片上失败：两人登录时被分到不同节点，而跟随不跨节点（team-spec §6.10 X2，同基线）。
   改 robot（`TeamScenario`）：S6 之前两人不在同一实例时，先让队员用 63 换到队长的实例。
2. robot `team`「邀请已登出的账号 → 4017」偶发回 0（双 scene 切片上 5 次里 2 次）：gate 在线目录的写入与撤销是两条各自异步的 Redis 命令，
   进场后立刻断线时撤销可能先执行，留下一条要等 TTL（60 s）才消失的条目。这是批次 1.2 的既有缺陷，修在 `GatePresence`
   （同一玩家的目录写按发出的次序逐条发；续期在途时下线的条目，续期回来后再撤销一次），单独提交，登记在 PARITY「玩家在线目录」行。
   修后 `team` 在单、双 scene 切片上各连跑 5 遍，10 遍全部通过（各 38 项）。
