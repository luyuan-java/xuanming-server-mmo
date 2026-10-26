# 移动广播 133 与同场景瞬移（批次 2.10）移植统一规格：从 mmorpg 基线到 Java 版

> **基线**：2026-10-26 实现前复核 mmorpg `275f9c5c0`（`D:\luyuan\xuanming-server-mmo-cpp`）。原稿按 `73b852f32` / `0aa1027bc` 写成，之后相关源码已有变化，不能再称逐字相同。
> 133、130 的 entity 已取不透明 actor 网络号，退出停步另有角色交易冻结调用；核心广播 / 瞬移 / 3 s 与 3 m 落位规则未变（§12.7）。
> mmorpg 已提交的 `docs/handoff/global-economy-claude-review-20261022.md` 有完整构建与 AOI 执行历史；本次未重跑 C++，不把历史记录当作本批验收。
> **客户端怎么解读仍以 Unity 实际处理器为准**（§2）。未更新的旧行号只作历史定位，应按函数名查当前代码。
>
> **客户端**：`D:\luyuan\mmorpg-client`，2026-10-26 实现前核实 `HEAD = origin/main = 9ccb99c51`，工作区干净。
> 历史规格以 `f537079` 为基线；当前已包含 `40e6fdd43`（远端双端外推、1 秒外推上限、转向补 MoveSync、图元禁碰撞、走路跟随）。
> §2 及验收步骤已按当前源码更新；D1 / D2 设计不变。此次未运行客户端测试或 Unity 联机，复核记录见 §12.6。
>
> **Java 侧**：从最新 `origin/main = 58e0296b` 建独立 worktree `D:\luyuan\wt210-impl`、分支 `batch-2.10-impl`；主工作区检查时干净，仍不得修改它。
> 契约记录现为 mmorpg `f83da2e18`，本批不改同步产物。当前三图导航已接入，§5.3 ⑦ 必须覆盖已有撞墙清速路径。
> 构建一律离线 `mvn -o`、不用 install；只提交本批路径。**不推送、不合并，完成报告后停下**。本任务书覆盖旧 D12 与旧工作区说明。
>
> **路径怎么读**
> - 以 `cpp/`、`robot/`、`proto/` 开头的路径与 `team-system.md`（= `docs/design/team-system.md`）在 mmorpg 克隆下；不带目录的基线文件：
>   `movement.{h,cpp}` 在 `cpp/libs/services/scene/spatial/system/`，`aoi.{h,cpp}` 同目录；`teleport_settle_comp.h` 在 `cpp/libs/services/scene/spatial/comp/`；
>   `mvh.cpp` = `cpp/nodes/scene/handler/rpc/player/player_movement_handler.cpp`（Java 代码注释里已用这个简称）；`player_lifecycle.cpp`、`player_team.{h,cpp}` 在
>   `cpp/libs/services/scene/player/system/`；`aoi_system_test.cpp` 在 `cpp/tests/aoi_test/`。
> - 以 `Assets/` 开头的路径在客户端克隆下；简称：`GameClient.cs` = `Assets/Scripts/Game/GameClient.cs`，`ActorWorld.cs` = `Assets/Scripts/World/ActorWorld.cs`，
>   `TianyongPlayerController.cs` = `Assets/Scripts/World/Tianyong/TianyongPlayerController.cs`，`MessageIds.cs` = `Assets/Scripts/Net/MessageIds.cs`，
>   `WorldCoordinateConverter.cs` = `Assets/Scripts/World/WorldCoordinateConverter.cs`（历史 `HandlerRegistry.cs` 已删除），
>   `QdaoBoySpriteAnimator.cs` = `Assets/Scripts/World/QdaoBoySpriteAnimator.cs`，`AppBootstrap.cs` = `Assets/Scripts/UI/AppBootstrap.cs`。
> - 以 `game-`、`docs/`、`PARITY.md`、`AGENTS.md` 开头的路径在 Java 仓库根下；本次修改仅在 `D:\luyuan\wt210-impl`。Java 类：`SceneWorld` / `ScenePlayer` / `Scene` / `ViewIndex` /
>   `MoveGuard` / `MovementRules` / `MoveInput` / `ClientRequestHandler` / `SceneMessageIds` / `SceneClock` 在 `game-scene/src/main/java/com/game/scene/world/`；
>   `SceneMetrics` 在 `.../scene/metrics/`；`PlayerBattleService` 在 `.../scene/battle/`；`TeamFollowService` 在 `.../scene/team/`；`SceneAdminController` /
>   `SceneAdminAuthFilter` 在 `.../scene/admin/`；`SceneNode` / `SceneNodeConfiguration` 在 `.../scene/`；
>   `MovementScenario` / `MoveAssertions` 在 `game-robot/src/main/java/com/game/robot/scenario/`，robot 的 `MessageIds` / `SceneAdminClient` 在 `.../robot/client/`。
> - 消息号取自 `game-proto/src/main/resources/contract/message_id.txt`（**号 N 在第 N+1 行**：66 `:67`、130 `:131`、133 `:134`、135 `:136`、137 `:138`）；
>   消息形状取自 `game-proto/src/main/proto/**/player_movement.proto`（同步产物，下称 `player_movement.proto`）。
> - 带「推导」字样的结论没有对应的测试或运行覆盖，是把多处代码路径串起来得出的；带「转述」的没有在代码上复核。
>
> 本稿只读代码，除本文件外没有改任何文件。2026-10-13 按七视角复核与主持人定案修订过一轮，修订内容与出处见文末 §12「评审修订记录」；正文只保留定案后的设计。

---

## 0 概览与范围

### 0.1 结论速览

- **基线做了什么**（`9199d217d`）：新增 `MovementSystem::BroadcastMove`，把实体此刻的权威 Transform / Velocity 发成 133 `ActorMoveS2C`，收件人是「互相可见且有会话」的玩家、
  不含自己；在 134 / 132 / 131 被接受之后、战斗或冻结中收到 131、外推撞墙清速、走着退出（`StopMotionForExit`）四处调用。新增服务器主动的**同场景瞬移原语**
  `MovementSystem::TeleportWithinScene`：写位置、清速度、只给本人发 130 `TeleportS2C`（reason 5）、`AoiSystem::ResetEntityVisibility` 把可见性当作「离开旧位置 + 重新进场」重建，
  并挂 `TeleportSettleComp`：3 s（单调时钟）内上报点离目标水平距离超过 3 m 的 134 / 132 / 131 整条丢弃。**66 那一路原样保留**。唯一调用瞬移的是组队归队（4.3b）。
- **客户端实际怎么收**（§2，`9ccb99c51` 上逐个复核）：移动下行处理133/135/130/137（`GameClient.cs:1853-1903`），仍无66的消息常量、proto类与处理器；
  66每条落入日志（`:1725`、`AppBootstrap.cs:81`）。133按场景实体号取实体，仅使用location、rotation、velocity；未知实体静默丢弃。
  远端在0.15s内收敛样本误差，插值两端同时按速度推进；距最后样本超过1s后清速，不能替代服务端立即同步真实停止。
  21/47对已有实体去重、创建时读取rotation；朝向仍只落在根transform，精灵朝向来自位移。130本人硬吸附并重新规划原点地目的地，不可走时恢复并报告131；非本人也支持硬吸附。
- **Java 现状**：只发 66（`entity_id = player_id`，PARITY「属性同步 66」行），从不发 133 / 135 / 130，没有瞬移原语、没有落位等待。按客户端的事实，
  **Java 的远端玩家在真客户端上不动——这是现存的客户端可见缺口**，2.10 修它。
- **Java 设计**（§5）：
  - **133 只在两类地方产生**：(a) `applyMove` 接受了一条客户端输入——只挂一个待发标记，**下一帧开头（外推之前）**兑现成一条 133 发给 `scene.watchers(p)`
    （看得见它的人 = 客户端上有它的人），同一帧内的多条输入合并成一条（每实体每帧至多一条输入触发的 133，20 Hz 上限）；位置就是裁决后的权威值（`MoveGuard` 截断后）。
    (b) 服务器让一个仍留在场景里的玩家由动变停（挂机停推、备战 / 战斗在途的停步、交出冻结；7.5 将来的撞墙清速）——经私有方法 `haltMotion` **当场**发一条速度为零的 133，不等下一帧。
    逐帧外推不发；离场 / 被接管 / 停服 / 同节点换场景 / 瞬移用销毁代替，不发。
  - **新观察者的初始状态**：21 / 47 的 `transform` 带上朝向（`rotation` 非 null 时；基线只填 location，这是对已对齐的 21 / 47 的新有意差异 X3）；
    新看见的目标里**正在动**的，在 47 之后给观察者一条 135（多目标批量）。进场、帧内进视野、瞬移重建三处同一规则。静止目标不需要任何补发。
  - **66 不动**：收件人、频率、`entity_id`、脏位保留、新观察者补发都保持现状，**不因基准结果翻转**（Q1）；登记「两版同步评估停发移动 66」。
  - 同场景瞬移原语 `SceneWorld#teleportWithinScene`：拒冻结 / 战斗在途；**同步**完成可见性重建——旧观察者 64、新观察者 21；本人一侧**只发差集**
    （不再看得见的 64、新看见的 47，瞬移前后都看得见的人原样保留）；给本人 130（reason 透传），清速度，`MoveGuard` 锚点重置到落点，挂落位等待（3 s / 3 m，按基线常量）。
    2.10 没有生产调用方；验证经 dev 管理口（reason 3，Q7）。
- **给 4.3b 留下**（§5.12）：瞬移原语与原因枚举、互相可见的查询、朝向的只读访问、瞬移指标、robot 的 130 / 133 断言工具。4.3b 自己做归队判定、入队通知与 `TeamFollowService` 同场景分支，
  并且要重开「队友是否挤进已满的兴趣表」（team-spec D8）。
- **robot**（§11.6）：Java robot `movement` 以 133 为主断言（66 保留为旁证），重登段验 21 带朝向，新增瞬移段（经 dev 管理口）；Go robot 的 133 / 135 / 130 处理器是空函数，
  不能拿来做跨实现验收；真客户端的验收要两台 Unity 客户端手工跑（§11.8）。**本机没有中间件与 Unity，本批能在本机取得的证据只有单测与 `-Dgame.bench` 基准**（§11.0），
  PARITY 两行落地后的状态因此写「Java 进行中」（§5.13）。

### 0.2 盘点 id 与落点

| 盘点 id | 出处 | 2.10 交付 |
|---|---|---|
| d10-move-broadcast-133 | `docs/porting/inventory/delta-20261010.md:46-70`；PARITY「移动广播 133」行 | 133 的构造、触发点、输入帧开头合并与停步当场发、新观察者的初始状态（§5.2–§5.6）。**勘误**：delta 条目 ④（`:60-61`）写「基线这里走的是 `HandleExitGameNode` → `StopMotionForExit` 发的 133」，对基线的描述有误（交接放行走 `DestroyDeposedPlayer`，从不发 133），以本稿 §1.3 为准；delta 是快照、不改，写 PARITY 与契约文档时不要照抄它 |
| d10-teleport-within-scene | `delta-20261010.md:72-82`；PARITY「同场景瞬移」行 | 瞬移原语、同步可见性重建、130、落位等待、dev 管理口（§5.7–§5.9） |
| 被依赖：d10-team-follow-v11 | `delta-20261010.md:84-96`；PARITY「组队跟随 v1.1」行；路线图 4.3b（`roadmap.md:71`） | 只留接口（§5.12），归队本身随 4.3b |
| 文档过期项 | `delta-20261010.md` §9 第 1 条 | `mmorpg-client-contract-{movement,aoi,scene}.md`、`architecture.md`、`game-robot/README.md`、路线图、PARITY 各行（§5.13） |

### 0.3 范围

**2.10 做**：133 的发送（全部触发点）；21 / 47 带朝向与新观察者的 135 补发；同场景瞬移原语、130、可见性重建、落位等待；dev 管理口瞬移（dev / test 才放行）；指标；
`ViewCrowdBenchmarkTest` 加 133 一段、前后对比只做记录；Java robot `movement` 的 133 / 130 断言；参考文档与 PARITY 改写。

**2.10 不做**：组队归队与入队即跟随（4.3b）；136 `TeleportRequest`（仍回 1006，PARITY「136」行）；导航算法扩展（已有撞墙清速接入本批停步广播，§0.4）；
停发移动 66（Q1，无条件不做）；队友挤进已满兴趣表（4.3b 重开 team-spec D8，§5.12）；客户端改动（本仓库不碰客户端；需要客户端配合的登记为客户端改进，§8.3）。

### 0.4 与其它批次的边界

| 话题 | 归属 | 2.10 的处理 |
|---|---|---|
| 组队归队（`ShouldRegroup`、8 m、队长交接在途不归队）、入队即跟随的通知通道、robot team 的「收到 130 reason 5」 | 4.3b | 只提供原语与查询（§5.12）；`TeamFollowService.java:246-249` 同场景分支现在回 `SAME_SCENE`，2.10 不动 |
| 5.2 跨节点换图 / 5.4 跨 zone 传送的冻结停步（`SceneWorld.java:1745-1751` `haltForFreeze`） | 已做 | 停步处当场发 133（§5.3 ⑥）；冻结中的玩家不能被瞬移（§5.7 前置） |
| 6.3 回合制战斗的停步（`haltForBattle`，`SceneWorld.java:2945-2948`；D5） | 已做 | 停步处当场发 133（§5.3 ③④⑤）；战斗在途不能被瞬移 |
| 7.5 导航查询与当前三图导航 | 已部分接入 | `integrate` 已有 `move.blocked()` 清速，2.10 改经 `haltMotion` 在撞墙当帧发 133；保留导航夹持与 MoveGuard 锚点修正，不改导航算法 |
| 视野（`ViewIndex`，PARITY「视野（AOI）」行） | 已做 | 不改重判规则；新增 `ViewIndex#reenter` 与 `Scene#teleport`（离开 + 重新进入，§5.7）；133 的收件人直接用 `watchers`；21 / 47 的 `transform` 带朝向（X3） |
| 136 TeleportRequest | 不改 | 仍回 1006；之后不发 130（「之后」只指对 136 的应答，服务器主动瞬移另说，PARITY「136」行已注明） |

---

## 1 基线协议

### 1.1 消息（`player_movement.proto`，0.3 已同步，形状与基线逐字相同）

| id | 方向 | 类型 | 字段 |
|---|---|---|---|
| **133** | S→C | `ActorMoveS2C` | `uint64 entity = 1`（proto 注释「actor entity id」）、`Transform transform = 2`、`Velocity velocity = 3`（零 = 停）、`uint64 server_time_ms = 4`（注释「server timestamp; clients use it for interpolation」，`:82`）、`uint32 move_state = 5`（0 停 / 1 走 / 2 跑 / 3 冲刺）。消息注释（`:76-77`；mmorpg 侧 `proto/scene/player_movement.proto:73-74`）：「Sent on start / stop / direction change, NOT every tick」 |
| **135** | S→C | `ActorMoveListS2C` | `repeated ActorMoveS2C moves = 1`；proto 注释：玩家刚进 AOI 格、要一次同步很多 actor 时用 |
| **130** | S→C | `TeleportS2C` | `uint64 entity = 1`、`Transform transform = 2`、`uint32 input_seq = 3`（注释：客户端丢弃 ≤ 它的预测输入）、`uint32 reason = 4`（注释列到 4：0 纠正 / 1 传送门 / 2 技能 / 3 管理员 / 4 复活；基线 5 = 组队归队，`movement.h:13`，注释待 mmorpg 下次重生成时补） |
| 66 | S→C | `ActorBaseAttributesS2C` | 不变（基线移动触发的不带 `entity_id`；Java 每条带 `player_id`） |

`Transform` = `{Vector3 location = 1; Rotation rotation = 2; Scale scale = 3}`，各分量 `double`（fixed64），proto3 下 0.0 不编码（`mmorpg-client-contract-movement.md` §1）。
基线 133 经 `BroadcastMessageToPlayers`、130 经 `SendMessageToClientViaGate` 下发，信封 `MessageContent{message_id, id = 0}`；**135 基线仍然从不发**。
**没有新 proto、新消息号、新 tip**（`9199d217d` 提交说明）。

### 1.2 `BroadcastMove`（`movement.cpp:121-176`，声明与注释 `movement.h:38-49`）

- **内容**：当前 `entity = actor_net_id::Of(entity)`（`movement.cpp:163-169`，缺号记错不发；与 21 / 47 / 51 / 64 同口径，不是 guid 或本地 ECS 句柄）；`transform = *transform`（整个 Transform：location 恒在；
  rotation 写过才在——`ApplyReportedLocation` 每次都 `*transform->mutable_rotation() = rotation`（`mvh.cpp:133`），所以被接受过一条上报之后 rotation 恒在场，请求没带朝向时是空消息 `12 00`）；
  `velocity` 有 Velocity 组件就拷（停止时是「存在但全零」，没有组件时字段缺席）；`server_time_ms = TimeSystem::NowMilliseconds()`（`:172`，墙钟 UTC 毫秒，与 137 同口径；
  B1 / B2 / B3 在 handler 里同步调用，所以它就是**裁决时刻**）；`move_state = 速度非零 ? 1 : 0`（`kMoveStateWalking` / `kMoveStateStopped`，`movement.h:16-17`），从不发 2 / 3。
- **收件人**：遍历**自己的** `AoiListComp`，留下「仍有效、有 `PlayerSessionSnapshotComp`（有网关会话）、对方的 `AoiListComp` 里也有我」的实体（`:134-156`），不含自己；
  一个都没有就不发。**理由**（`movement.h:41-45`）：只按自己的表发会把坐标发给看不见我的人（隐身时对方表里没有我）；严格的「谁看得见我」要扫 7 格全部实体，每条上报都走这条路径，扫不起。
  **已知缺口**：对方看得见我、但我的表因容量满没收下对方时，对方收不到我的移动（与 66 同一口径的既有缺口）。（可见性本身的同格缺口——基线只在跨格时重判——是视野的差异，
  见 PARITY「视野（AOI）」行缺口 3，不属于 133。）
- **序列化一次**，按目标所在 gate 分组，每个 gate 一条内部广播，gate 再逐会话下发（`BroadcastMessageToPlayers`，同 66 的批量方式，`mmorpg-client-contract-movement.md` §6.1）。
- **只读组件、不增删组件**，可以在 view 遍历中调用（`movement.h:48`）。

### 1.3 调用点（HEAD 行号）

| # | 位置 | 时机 | 备注 |
|---|---|---|---|
| B1 | `mvh.cpp:183`（MoveStart）、`:232`（MoveSync） | 战斗 / 冻结闸与落位等待闸都过了、`ApplyReportedLocation` + `ApplyReportedVelocity` 之后 | 每条被接受的上报发一条，**不合批**；137（若有）先于 133 发出 |
| B2 | `mvh.cpp:212`（MoveStop） | 同上，速度清零之后 | |
| B3 | `mvh.cpp:195-201` | 回合制战斗在途（`InBattleComp`）**或**归属交接冻结（`PlayerFrozenComp`）中收到 131：只清速度、**无条件**广播（`:200`） | 注释：进战 / 冻结前最后一次广播还带着行走速度。冻结 / 战斗中的人要等自己的客户端发 131 才停 |
| B4 | `movement.cpp:112-117` | 外推撞墙清速的那一 tick | 当 tick 发、只发一次（速度已清零，之后不再进这段） |
| B5 | `player_lifecycle.cpp:3255-3274` 的 `StopMotionForExit`，广播在 `:3271` | 速度原本非零才清、才广播；当前三个调用点：`FreezeRoleForTrade:3244`、`HandleExitGameNode:3332/3412` | 除退出重存兜底与常规退出外，新增角色交易冻结调用。归属交接冻结仍只挂冻结，不能把它与角色交易冻结混为一谈；Java 寄售排空直接移除实体并发 51，不新增一条无效果的 133 |

基线**不**发 133 的地方：
- AFK 停推（`afk.cpp` 只挂 `AfkComp`、不清速度）；
- 进战（`InBattleComp` 只让外推跳过，不清速度，要等客户端发 131 才走 B3）；
- 归属交接冻结：`StartTravelHandoff` 只挂 `PlayerFrozenComp`、不清速度（`player_lifecycle.cpp:4035`），冻结期间只有客户端自己发 131 才走 B3；66 那一路同样排除冻结实体
  （`actor_state_attribute_sync.cpp` 的 `exclude<PlayerFrozenComp>`），所以冻结期间旁人收不到任何停步信号。交接未成解冻（`AbortTravelHandoff`，`:5501`）也不清速，解冻后按冻结前的速度继续积分；
- **归属交接放行后的源端销毁**：走 `DestroyDeposedPlayer`（`player_lifecycle.cpp:3794-3840`；调用点 `:3791`、`:4446`、`:4486`、`:5022`、`:5073`、`:5181`、`:5462`，
  覆盖交接放行 / 被废黜 / 重入取代），只做 `DetachFromScene` → 摘会话 → 销毁，**不经 `HandleExitGameNode`、不调 `StopMotionForExit`**；交接在途时 scene_manager 的 `ReleasePlayer`
  在 `scene_node_service.cpp:168-176` 就返回了，走不到 `:182` 的 `HandleExitGameNode`。所以冻结中的人在旁人屏幕上一直跑到 51 把它销毁，
  例外只有冻结中客户端自己发了 131（B3）或交接途中断线（退出优先，才走 B5）；
- 新观察者进视野（缺口 9 在基线仍在）；换场景（旧场景广播销毁，新场景重新进场）；逐帧外推（`movement.cpp:108-110` 只置 66 的 Transform 脏位）。

### 1.4 频率与量

- 客户端移动中每 0.25 s 一条 132（`TianyongPlayerController.cs:642-650`，实际是「≥ 0.25 s 后的第一个渲染帧」，与服务器帧不锁相），起步一条 134，停步一条 131；
  转向补 132 已落地：距上次报告 ≥ 0.12 s 且方向点积 < 0.94（约 > 19.95°，`:37-44`、`:642-650`）。移动中的玩家周期约 4 条 / 秒，持续转向最多约 8 条 / 秒。
- gate 按 MessageLimiter 限频，表值 134 → 15、132 → 20、131 → 15（窗口 1 秒；PARITY「gate 按消息号限频」行，两仓 `messagelimiter.pb` 逐字节相同）。
  **基线**限频器按整秒时间戳、`>` 淘汰（`cpp/libs/engine/core/message_limiter/message_limiter.cpp:13`、`:34`），任意相邻两个整秒合计至多 N 条：
  单人被接受的移动输入单秒突发最多 50 条、持续约 25 条 / 秒。**Java** gate 是 1 秒纳秒滑窗（`MessageRateLimiter.java:29`），持续上限 50 条 / 秒。
  B1 / B2 不合批，基线的 133 扇出跟着这个输入速率走，没有自己的上限。
- 收件人受兴趣表容量约束（`kAoiListCapacityDefault = 100`，`aoi_priority.h:97`）。`team-system.md` V.2 第 5 条 / V.5 估算：每个移动中的玩家每秒 4–8 条 × 互相可见人数；
  「要减可以改成只在起步 / 停步 / 转向时发（协议注释的原意），代价是远端外推误差变大」。基线没有压测。
- **66 那一路原样保留**：偶数帧（每实体 ≤ 10 Hz）按脏位发给自己兴趣表里的实体，冻结实体不发（`actor_state_attribute_sync.cpp:56-113`，契约文档 movement §6.1）。

### 1.5 同场景瞬移 `TeleportWithinScene`（`movement.cpp:179-217`，注释 `movement.h:51-58`）

调用方保证落点是该场景导航上的合法点；没有 Transform 直接返回；只能在 scene 节点 loop 线程调。按顺序：

1. 写 `Transform.location = 落点`、`Transform.rotation = 入参`（`:189` `*transform->mutable_rotation() = rotation`，**无条件覆盖**），置 Transform 脏位（`:188-190`）。
2. 有 Velocity 组件就 `Clear()` 并置 Velocity 脏位（`:193-197`）——否则 `Update` 从新位置按旧速度继续积分，人一落地就飘走。
3. `emplace_or_replace<TeleportSettleComp>`：记当时的场景句柄、目标 x / y、`expireAtMs = 单调时钟 + 3000`（`:200-205`）；重复瞬移以最后一次为准。
4. 实体是玩家（有 `Player` 组件）时**只给本人**发 130：`entity` = 场景实体号、`transform = *transform`（新位置 + 新朝向；`rotation` 恒在，可能是空消息）、`reason`；
   **`input_seq` 不设（0）**（`:207-214`）。
5. `AoiSystem::ResetEntityVisibility(entity)`（`:216`）。

**没有**给旁人发 133，**没有**动 `LastActiveFrame`，**没有**改位置记录（Redis location 只记场景）。

### 1.6 `ResetEntityVisibility`（`aoi.cpp:234-299`，注释 `aoi.h:15-26`）

把同场景瞬移当作「从旧位置离开视野、在新位置重新进场」：

1. 自己这一侧：兴趣表里非 `kPinned` 的条目全部摘掉，**通知自己的客户端销毁**（`NotifyEntityVisibilityChanges` → 64 列表，`:241-253`）。
   注释给的理由：不通知的话客户端会把旧位置的 actor 永远留着，**之后同一实体再进视野时客户端按 entity 去重，连位置都不会更新**。
   基线全量摘是因为重建在下一次 `Update`，它当下分不出谁仍然可见。
2. 观察者一侧：旧 Hex 的当前格 + 6 邻格里、兴趣表里有它（非钉住）的实体，摘掉并各收一条 64（`:257-291`）；把它移出格子、摘掉 `Hex`。
3. 下一次 `AoiSystem::Update`：没有 `Hex` 的实体走「首次进场」分支，按新位置对当前格及邻格重新做双向 `CanSee`（先按视野半径 10 判，`view.cpp:175-179`），自己收 47、旁人经 `8843a1465` 的反向通知收 47。
   **所以基线的「新位置可见」晚到下一个模拟步（名义 ≤ 50 ms）**；这一步之前被瞬移者自己的兴趣表是空的，期间它被接受的上报调 `BroadcastMove` 会在 `movement.cpp:124` 直接返回（没人收到它的 133）。
4. 已知边界：钉住它的观察者既不收销毁也不收重建；移动广播只发给互相可见的人，它在那些客户端上会停在瞬移前的位置（`aoi.h:23-25`；目前没有玩法钉住玩家）。

### 1.7 落位等待（`teleport_settle_comp.h`、`movement.cpp:219-258`、`movement.h:19-31`、`:61-77`）

- **为什么**：移动是客户端上报、服务器裁决。瞬移发出时客户端可能还有按旧位置发的 134 / 132 / 131 在路上；照常裁决会把人沿直线拉回旧位置（中间没墙就整段接受），瞬移等于白做。
- **判定**（三个 handler 在战斗 / 冻结闸**之后**、裁决**之前**问 `ShouldDropReportAfterTeleport`，`mvh.cpp:176`、`:204`、`:225`）：
  - 没挂组件 → 照常（每条上报一次 `try_get`）；
  - 瞬移之后换了场景（场景句柄不同）→ 摘组件、照常；
  - 纯函数 `JudgeReportAfterTeleport(水平距离, now, expire)`：**先看距离再看时间**——上报点离目标 ≤ `kTeleportSettleRadius = 3`（含等号）→ `kSettled`（摘组件、照常裁决）；
    否则 `now ≥ expire` → `kExpired`（摘组件、照常裁决、`LOG_WARN`）；否则 `kStale` → **整条丢弃**（位置、速度、137、133 都没有）。
  - 先看距离的理由：客户端落位后可能站着不动，过了超时才发第一条上报，那仍是一次正常落位。
- **没有定时器**：判定与摘除都发生在下一条上报到达时；一直不上报的实体一直带着组件，无害。只在战斗 / 冻结闸之后判，所以战斗中的 131（B3）不受它影响。
  活跃帧在分发处刷新（`scene_handler.cpp:468-473`），被落位等待丢掉的上报照样算活跃。
- 不落盘、不进 proto。单调时钟（`std::chrono::steady_clock` 自纪元起的 uint64 毫秒，直接比大小）。

### 1.8 唯一的调用方（4.3b 的内容，列在这里只为说明原语怎么被用）

`PlayerTeamSystem::RegroupToLeader`（`player_team.cpp:514-574`）：与队长同一场景实例、队长归属交接不在途（`:529` `IsOwnershipInFlight(leader)`，= `PlayerFrozenComp` ∪ `PlayerTravelHandoffComp`，`:78-82`）、
`ShouldRegroup(水平距离, 互相可见)`（`player_team.h:93-96`：互相不可见，或距离 > `kTeamRegroupDistance = 8`）→ `TeleportWithinScene(队员, 队长位置, 队长朝向, 5)`
（朝向取 `leaderTransform->rotation()`，`:569`，队长没写过朝向时是全零默认值）。「互相可见」只在双方都已有 `Hex`（已被 AOI 评估过）时看兴趣表，
否则按可见处理——因为基线的瞬移 / 进场到首次 `Update` 之间有一个模拟步的「还没评估」窗口（`:543-555`）。

**队员一侧的守卫在调用方**：到达 `RegroupToLeader` 之前，`CheckFollowLeader` 已对队员查过回合制战斗在途、归属交接在途（Frozen ∪ TravelHandoff）、换图请求在途（`IsSceneChangeInFlight`）、
会话存活（`player_team.cpp:361-380`），MGET 回调里 `battle:lock` 非 NIL 即跳过并把前四道重查一遍（`:403-429`）；`RegroupToLeader` 另查队长实体在本节点且不是自己（`:518`）、
两实体此刻绑定同一场景（`:523-527`）、双方有 Transform（`:536-541`）。「换图请求在途」只对队员自己查，没有拿它查队长。

**表满怎么办**：基线靠队友优先级挤出。`DetermineAoiPriority` 对同队返回 `AoiPriority::kTeammate`（`aoi.cpp:24-37`），瞬移后首次进场评估双向都带这个优先级（`aoi.cpp:111-125`），
表满时权重更高的挤掉最低的一条（`interest.cpp:105-114`，`aoi_priority.h:42-44`：kNormal 0 / kTeammate 1；**被挤者不发 64，挤出是静默的**）。入队时 `RefreshTeammateAoi`
只升级已有条目（`player_team_aoi.cpp:31-38`），所以人群里原本互不可见的一对，在基线正是靠「互相不可见 → 瞬移 → 首次进场按 kTeammate 挤出」建立可见。

### 1.9 验证状态

| 范围 | 证据 |
|---|---|
| 服务端 C++ | 当前 HEAD 已有历史构建与 AOI 执行记录（文首），本次未复跑。`aoi_system_test.cpp` 的 Reset 四条在 372/397/427/448，另有 Pinned 重入 473；落位纯函数在 1329/1336/1345，归队规则在 1305/1312/1319。仍无 `TeleportWithinScene` / `ShouldDropReportAfterTeleport` / `BroadcastMove` 直接测试 |
| Go robot | `robot/logic/handler/scene_movement_client_player_notify_{actor_move,actor_move_list,teleport}.go` 都是空函数（`message_body_handler.go:91-93` 已注册）；`team_smoke` 没有 133 / 130 断言 |
| 客户端配套 | `9ccb99c51` 已包含 `40e6fdd43` 的配套源码（§2）；历史纯逻辑36/36为转述，本次未运行客户端测试、Unity测试或双号联机 |
| 联机 | 没有（`team-system.md` V.6 第 3 步是待办清单） |

---

## 2 客户端可见行为（Unity `9ccb99c51`，2026-10-26 实现前复核）

### 2.1 处理器清单

| 消息 | 处理器 | 结论 |
|---|---|---|
| 133 | `GameClient.cs:1853-1857` → `ApplyServerMove`（`:2010-2015`）→ `ActorWorld.ApplyMove`（`ActorWorld.cs:341-356`） | 有 |
| 135 | `GameClient.cs:1859-1863`：对 `moves` 逐条 `ApplyServerMove`（空列表无副作用） | 有（与 133 完全同义） |
| 130 | `GameClient.cs:1865-1872` → `ActorWorld.Teleport`（`ActorWorld.cs:363-399`）；本人走 `TianyongPlayerController.WarpFromServer`（`:305-329`） | 有（处理器不区分本人与否） |
| 137 | `GameClient.cs:1874-1903` | 有（2.10 不改它） |
| 21 / 47 | `GameClient.cs:1802-1806`（21）与 `:1808-1813`（47，逐项）→ 同一个 `SpawnActorView`（`:1955-1984`）→ `ActorWorld.SpawnActor`（`ActorWorld.cs:169-223`） | 有；创建时读 location 与 rotation（§2.4） |
| **66** | **无**：`MessageIds.cs` 没有 66；`Assets/` 下没有 `ActorBaseAttributesS2C` / `ScenePlayerSync`（全仓检索无匹配） | 落入 `Log("[unhandled] message_id=66 bytes=…")`（`GameClient.cs:1725`），每条一次 `Debug.Log`（§2.5） |

历史基线 `f537079` 的生成空壳 `HandlerRegistry` 已删除（`GameClient.cs:1766-1770`）；现有推送经 `OnNotify` 或 `Net/Generated/Rpc/` 下的类型化注册。
`OnNotify` 仍为覆盖语义（`:437-438`），重复注册同一消息号仍会覆盖已有处理器，但旧空壳注册表不再是待接入的风险。

### 2.2 133 的字段解读与插值 / 外推

- `entity` 是 `ActorWorld` 的键（`SpawnActorView` 传 `ev.Entity`，`GameClient.cs:1973`）。**找不到实体直接返回**（`ActorWorld.cs:344`）：
  133 早于 21 / 47、或误用 player_id，都会静默丢弃、没有日志。本人实体也忽略（`:348`），本人预测纠偏只走 130 / 137。
- location 缺席视为 (0, 0, 0)，rotation 缺席视为零欧拉角，velocity 缺席视为零（`GameClient.cs:2012-2015`；`WorldCoordinateConverter.cs:67-74`）。
  因而服务端必须总带 location；rotation 缺席并不是「保持朝向」。`server_time_ms`、`move_state`、`transform.scale` 不读。
- **朝向在当前精灵画面上仍不可见**：角色始终朝相机（`QdaoBoySpriteAnimator.cs:758`），八方向来自实际位移（`:699-714`），新建固定朝 S（`:94`、`:141`）。
  rotation 写入实体根 transform（`ActorWorld.cs:198`、`:352`、`:379`、`:389`），精灵、影子、名牌不读；只有退回图元时才看得见。
  要验朝向应在编辑器查看 `Player#<entity>` 的 `localEulerAngles`。
- 插值仍用 0.15 s（`ActorWorld.cs:342`），每条样本重置起点与时刻（`:349-355`）。当前 `Tick` 在插值前把**两端同时按权威速度推进**（`:429-431`），
  只在 0.15 s 内收敛收到样本时的位置误差（`:432-441`），不再像历史 `f537079` 那样先插值到旧样本、再开始外推。
  - 距最后样本**严格超过 1 s**时清速度（`MaxExtrapolationSeconds = 1f`，`:104`、`:427-428`），不会无限跑下去。
    这只是丢停步样本的客户端兜底，不能替代服务端立即同步真实停止；D1 / C4 仍要求所有停步路径及时发零速 133。
  - 历史 `f537079` 的「速度 × 0.15 s」固定滞后已消除。
  - 新建实体仍为零速度且 `HasTarget = false`（`SpawnActor` 不设它们）：移动目标必须在创建之后收到 133 / 135 才会动；收到后下一次 `Tick` 即按速度推进，
    **不再必然原地等 0.15 s**。
  - 客户端仍不按时间戳对齐，样本一律当作「此刻」。恒定延迟表现为整体平移；逐条变化的延迟表现为到达抖动：匀速、连续采样时，到达间隔比发送间隔多 / 少 Δ，
    0.15 s 误差插值窗口的显示速度约为 v ×（1 − Δ / 0.15）（推导）。§5.4 合并的可见代价仍存在。
- 后到的 133 从当前显示位置重新开始误差插值。重复旧位置样本会重置插值并产生回拉误差，**不是幂等操作**；双端仍在推进，不再是历史实现的「推迟开始外推」。
  因而补发只能给新观察者，不能给全部观察者重发（§8.2 R9）。

### 2.3 130 的解读

- 本人实体由 `guid == PlayerId` 的 21 / 47 绑定（`GameClient.cs:1975-1983`），本人也在 `_actors` 中。64 / 51 销毁本人会清掉绑定（`ActorWorld.cs:304-315`）；
  重发本人的 21 / 47 即使 `SpawnActor` 去重，随后 `SetLocalPlayer` 仍会重新初始化控制器。因此本人收到的 64 / 51 / 47 不能含本人，瞬移也不能重发本人的 21（C9、I8）。
- 找不到实体时 `Teleport` 静默返回（`ActorWorld.cs:365`），处理器仍记录 `[move] teleport entity=… reason=… input_seq<=…`（`GameClient.cs:1871`），日志不能证明实体已找到。
  - **本人**：`WarpFromServer` → `WarpTo`，先 `CancelPath(false)`（清路径、`_moving = false`、不发 131，`TianyongPlayerController.cs:279`、`:669-675`），
    再按地图边界内缩 2 m 夹 XZ（`:280`；服务器坐标 x ∈ [2, 298]、y ∈ [2, 398]）。夹后仍可走时不回报（`:334`），位置差异留到下一条移动上报。
    点地寻路中则保存目的地并在落位后重新规划（`:305-329`），之后从落点继续走。落点不可走时尝试最近可走点，失败则用该地图初始化时校验过的 `_fallbackSpawn`
    （`:261-273`、`:337-340`），回报一条 131（`:360`、`:373-383`）；2 s 内连续超过 3 次不再回报（`:48-49`、`:347-356`）。
    本人朝向仍按 130 设置（`ActorWorld.cs:379`），rotation 缺席即归零；137 传本地朝向（`GameClient.cs:1897`）。
  - **非本人**：直接放到落点并写朝向（`ActorWorld.cs:381-395`），速度归零且清插值目标（`:396-399`），完全跳过插值。
- location 缺席同样当作零；reason / input_seq 只进日志（`GameClient.cs:1871`），不丢弃预测输入，没有回滚重演管线（`:1878-1879`）。
- 瞬移后 WASD 仍按着时，下一帧先移动再发 134（`TianyongPlayerController.cs:616`、`:637`）；普通移速 9 m/s、dt 上限 0.05 s，首条起点离落点 ≤ 0.45 m。
  跟随可用 1.1 倍移速，首步上限为 0.495 m（`:63-65`、`:598-601`）；两者都远小于落位半径 3 m。点地寻路会重新规划，站着不动不主动报告。
- 79 仍 `World.Clear()`（`GameClient.cs:1757`），清全部实体；瞬移不能发 79。

### 2.4 视野消息的硬约束（与 133 / 130 直接相关）

- 21 / 47 按 entity 去重（`ActorWorld.cs:172`）：对已有实体重发不会更新位置。
- **21 与 47 共用读取 rotation 的函数**（2026-10-26 在 `origin/main` `9ccb99c51` 复核）：21（`GameClient.cs:1802-1806`）与47（`:1808-1813`）
  都调用 `SpawnActorView`；`:1961` 读 rotation、`:1965` 转换（null → 零欧拉角）、`:1973` 传入 `World.SpawnActor`，后者在去重后 `ActorWorld.cs:198` 写入根 transform。
  本人的 21 也是同一路径。D2 / X3 不需要客户端配合。
- 让旁人看见瞬移可用：① 64 / 51 销毁后 21 / 47 创建；② 133 在 0.15 s 内收敛位置误差；③ 130 硬吸附、清速度与插值（`ActorWorld.cs:363-399`）。
  ③ 缺 rotation 会清朝向，精灵将大位移当作吸附（`QdaoBoySpriteAnimator.cs:63`、`:699`）；基线与本批 Java 只用①，③ 仍是不采纳的变体 N1。
- 销毁后到达的133、未知实体的64/51均静默忽略（`ActorWorld.cs:344`、`:306`）。
- 图元碰撞体已禁用（`ActorWorld.cs:194-195`），远端角色不再作为本地 CharacterController 的物理障碍。

### 2.5 66

客户端仍不处理66。`GameClient.cs:1725` → `Log`（`:2060`）→ `AppBootstrap.cs:81` 的 `Debug.Log`（正式入口无条件挂上，`ProjectSettings.asset` 的 `m_StackTraceTypes` 仍为 ScriptOnly）。
每条66都有字符串格式化与主线程日志开销：每个可见移动者10条/秒，20个约200次/秒、100个约1000次/秒。基线亦保留66，这不是本批引入的问题；D3无条件保持66，后续两版同步评估停发。

### 2.6 对服务端的硬约束（下文设计逐条满足，§11 逐条有测试）

| # | 约束 | 依据 |
|---|---|---|
| C1 | 133 / 135 / 130 的 `entity` 用场景实体号（= 21 / 47 / 51 / 64 的 `entity`），不是 player_id | §2.2、§2.3 |
| C2 | 133 / 135 / 130 的 `transform.location` 必须总在 | §2.2 |
| C3 | 某实体的133必须晚于21/47、早于其64/51（直到重新创建） | §2.2、§2.4 |
| C4 | 服务器让仍可见玩家停下的每条路径都补零速133，越早越好；不能等客户端1秒兜底 | §2.2 |
| C5 | 旁人看到瞬移必须用销毁→创建、133或130，不能只重发21/47 | §2.4 |
| C6 | 130落点应在行走掩码上可走且位于地图内缩2m范围（服务器x ∈ [2,298]、y ∈ [2,398]）；否则可能夹持/恢复。rotation非null必带，缺席使本人朝向归零 | §2.3 |
| C7 | 新建实体速度为零：移动目标在21/47后补133/135；静止目标只需创建消息带对位置与朝向 | §2.2、§2.4 |
| C8 | 推送信封 `id` 必须为0：任何非0 id 都只按应答处理，未匹配请求也作为迟到应答丢弃，不会走推送处理器 | `GameClient.cs:1714-1724` |
| C9 | 本人收到的64/51/47不含本人；除进场那条自己的21外不再给本人发自己的21 | §2.3 |

130只给本人是设计选择（基线同），不是客户端处理能力限制。

### 2.7 已落地的客户端配套（`40e6fdd43`，当前基线已包含）

2026-10-26 核实 `40e6fdd43` 为 `origin/main` 祖先：两端共同外推、1秒上限、转向补132、图元禁碰撞均已落地（上述行号）。
跟随逻辑在 `Assets/Scripts/Game/Team/TeamFollowTrail.cs`、`TeamFollowPlanner.cs`、`TeamFollowDriver.cs`；历史规格称“本克隆不存在/未推送”已过时。
本次仅检查源码，未运行Unity测试或双客户端联机；D1服务器停步当场发、D2创建朝向和移动目标补135均保持原定案。

---
## 3 Java 现状与差距

### 3.1 今天置 66 脏位 / 让人停下的全部地方

game-scene 主代码里 `markDirty(` 的调用点只有 5 处（`SceneWorld.java:1748`、`:2421`、`:2496`、`:2501`、`:2947`），外加 `markFullStateForNewWatcher` 的 3 处（`:1499`、`:1502`、`:2516`）；
改速度的只有 `:2420`（`applyMove`）、`:2495`（挂机停推）与各处 `stopMotion()`。下表与它们一一对上：

| # | 位置 | 做什么 | 66 |
|---|---|---|---|
| J0 | `SceneWorld.java:2500-2501` `integrate` 逐帧外推 | 速度非零的玩家每帧 `location += v × 0.05` | `DIRTY_TRANSFORM`（每个偶数帧一条只带 transform 的 66，66 流量的主体） |
| J1 | `SceneWorld.java:2403-2435` `applyMove`（134 / 132 / 131 通过 `MoveInput.isAcceptable` 与 `MoveGuard.admit` 之后） | 位置取 `MoveGuard` 截断后的值、朝向整体覆盖（`:2419`，请求没带朝向时是默认实例而不是 null）、速度截到 10 m/s（131 为零）；偏差 > 0.5 m 给本人 137 | `DIRTY_TRANSFORM \| DIRTY_VELOCITY`（`:2421`） |
| J2 | `SceneWorld.java:2494-2498` `integrate` 挂机停推（PARITY「移动上行」有意差异 ③） | 600 帧无客户端消息：`setVelocity(ORIGIN)`（不经 `stopMotion`） | `DIRTY_VELOCITY` |
| J3 | `ClientRequestHandler.java:328-336`（战斗在途收到 131，`BattlePolicy.STOP_ONLY`） | **在动才**调 `haltForBattle`（条件在调用方 `:332`） | `DIRTY_VELOCITY` |
| J4 | `PlayerBattleService.java:222`（备战即停步，scene-battle-spec D5） | **无条件**调 `haltForBattle` | `DIRTY_VELOCITY` |
| J5 | `PlayerBattleService.java:1281-1286` `attachFreeze`（迟到确认 / 进场恢复挂冻结） | **在动才**调 `haltForBattle`（条件在调用方 `:1283`） | `DIRTY_VELOCITY` |
| J6 | `SceneWorld.java:1745-1751` `haltForFreeze`（`freeze` `:1733`、`freezeForTravel` `:2271`） | **在动才**停（条件在方法内 `:1746`），返回冻结快照 `toSave()` | `DIRTY_VELOCITY` |
| J7 | `SceneWorld.java:1497-1503`（`enterScene`）、`:2513-2517`（`emitViewChanges`）→ `ScenePlayer.java:256-263` `markFullStateForNewWatcher` | 新观察者：目标上报过朝向就置 transform 脏、在动就置 velocity 脏（修基线缺口 9） | 下一个同步帧的 66 发给**全部**观察者 |
| — | `removePlayer`（`:2839-2858`）、`switchScene`（`:1543-1567`）里的 `stopMotion()` | 只清速度、**不置脏位**（`ScenePlayer.java:241-248` 的 javadoc 写明了理由） | 无；旧场景的旁人收到 51，新场景的人收到 21 |
| — | `shutdown`（`:2881-2908`）里的 `stopMotion()` | 只清速度、写回、`scene.clear()` | **不发任何下行**（javadoc `:2872`「不再给客户端发消息」；停服顺序是先断开全部 gate 链路再写回，`architecture.md:1128`） |

`haltForBattle`（`:2945-2948`）是 **public**、被 battle 包调用（`PlayerBattleService.java:222`、`:1284`）与 `ClientRequestHandler.java:333`，函数体无条件（`stopMotion()` + `markDirty(DIRTY_VELOCITY)`）。
66 由 `syncAttributes`（`SceneWorld.java:2535-2548`）在偶数帧发给 `scene.watchers(p)`（不含自己），没人看得见时**保留脏位**。帧顺序：外推 → 视野刷新（47 / 64）→ 偶数帧 66（`:2449-2469`）。
`enterScene`（`:1490-1512`）的实际下行顺序是 79（`:1506`）→ 本人的 21（`:1507`）→ 47（`:1509`）→ 给 seers 的 21（`:1511`）。

### 3.2 差距

1. **没有 133 / 135 / 130 的发送方**；`SceneMessageIds`（`:12-31`）没有这三个号；`MovementSyncTest.java:621` 的用例名就是「从不发 133 / 135 / 130」。
2. **新观察者的初始状态走 66**（J7）：真客户端收不到，移动中的人进视野后静止到它下一条上报；21 / 47 只带位置（`ScenePlayer.java:277-291`），目标的朝向到不了客户端的 transform
   （当前 `9ccb99c51` 的精灵朝向仍由位移决定，§2.2，所以这半句没有画面后果）。
3. **没有瞬移原语**：`MoveGuard.reset`（`MoveGuard.java:47-51`）只有进场落位与换场景两个调用方；`Scene` 只有 `relocate`（`Scene.java:218-224`，交给帧内按节奏重判，不会「销毁 → 创建」）。
4. **没有落位等待**：服务器瞬移之后，在途的旧位置上报相对落点过 `MoveGuard`（`reset` 把额度回满到 24 m，`MoveGuard.java:47-50`、`:29-31`）：**离落点 ≤ 24 m 的整条原样接受、不回 137
   ——服务器位置回到旧位置，瞬移被静默撤销**；> 24 m 的截断到 24 m 并回 137，客户端被拉向旧位置方向。4.3b 归队的触发距离是 > 8 m，大量落在前一种。
5. **robot**：`MessageIds`（robot）没有 133 / 135 / 130；`MovementScenario` 只断言 66；`SceneAdminClient` 只有建 / 毁副本两个接口。
6. **文档**：`mmorpg-client-contract-movement.md`、`mmorpg-client-contract-aoi.md`、`architecture.md`、`game-robot/README.md` 以及 PARITY「移动上行」「属性同步 66」「136」「视野（AOI）」各行
   都按「66 是唯一通道、不发 133、21 / 47 只带位置」写（清单见 §5.13）。

Java 已经有、2.10 直接复用的：
- `watchers` 是精确的反向索引（`ViewIndex.java:17-18`：A ∈ watching(B) ⇔ B ∈ watchedBy(A)），**观察者一侧**的每一次加入 / 删除都当场通知（21 / 47 / 64 / 51，PARITY「视野」行）。
  离开者自己的兴趣表是**静默清空**的（`ViewIndex.java:130-131`、`:144-146`，基线同：离场者收不到 64 / 51），它的客户端靠随后的 79 整体清场（`GameClient.cs:1718` `World.Clear()`），或会话本身已结束。
  所以对仍在场的会话，`watchers(p)` = 「客户端上有 p 的人」。索引的增删点只有 `Scene.add`（`enterScene` `:1493`）、`Scene.remove`（`removePlayer` `:2845`、`switchScene` `:1553`）、帧内重判与
  `clear`（`destroyScene` 只在空场景时、`shutdown`），没有「观察者一侧索引变了没通知」的路径。
- Java 没有隐身、没有 NPC。在场实例通常有会话，**例外是冻结中会话已离开的实例**（`deferLeaveIfFrozen`，`SceneWorld.java:1974-1983`，实例与旁人视野保留到交出结局）——发给它的 133 / 135 与今天的
  66 / 47 / 64 一样，由 gate 丢弃（`SceneEventRouter.java:47-51`）或在 scene 侧计 `LinkDrop.LINK_GONE`（`GateLinks.java:224-229`），无害。
- `broadcast` 已按链路分组、消息体序列化一次（`SceneWorld.java:2968-2978`；信封按链路数各编码一次，`GateLinks.java:122-128`）；`SceneClock` 有单调纳秒与墙钟毫秒（`SceneClock.java:9-12`）。
- gate 对 scene 下行没有消息号白名单（`SceneEventRouter.java:37-53`），133 / 135 / 130 不需要改 gate。

---

## 4 Java 与基线的客户端可见对照（2.10 落地后）

| 场景 | 基线（源于 `9199d217d`，当前按 `275f9c5c0` 复核） | Java 2.10 |
|---|---|---|
| A 走动，B 看得见 A | 每条被接受的输入当场一条 133（4–8 / 秒；受 gate 限频，突发 50 / 秒、持续约 25 / 秒） | 下一帧开头一条 133，同帧多条合并（4–8 / 秒，上限 20 / 秒）；每条样本比基线晚 0–50 ms 且逐条不同（X2） |
| A 停下（131） | 133（速度零，`move_state` 0） | 同（下一帧开头） |
| A 30 s 无任何消息 | 服务器停推但**不发** 133（不清速度），B 上的 A 按旧速度一直跑 | 停推那一帧清速并**当场**发 133（有意差异 ③ 的延续，X4） |
| A 备战 / 进入回合制战斗 | 不清速度；B 上的 A 一直跑，直到 A 的客户端发 131（B3）才有 133 | 备战即停步，在动则当场 133（X5） |
| A 换节点 / 跨 zone 冻结 | 冻结不清速度、66 也不发；B 上的 A 一直跑，直到 A 的客户端发 131（B3）；**交接放行后只有 51，没有 133**（`DestroyDeposedPlayer`） | 冻结时在动则当场 133；交出提交后 51（X5） |
| 战斗在途 / 冻结中 A 的客户端发 131 | 无条件一条 133（B3） | 战斗在途：仍在动才停、才发（备战时已停步，通常不发）；冻结中：131 直接丢（冻结时已停步）（X10） |
| A 走着掉线 / 主动退出 / 停机（经 `HandleExitGameNode`） | 133 + 51 | 掉线 / 退出 / 被接管：只有 51（实体随即销毁，133 没有可见效果，X8）；停服：**没有任何下行**（链路已先断开，客户端随连接断开） |
| A 被更新一代取代 / 被废黜（`DestroyDeposedPlayer`，推导） | 只有 51 | 被接管：只有 51（同） |
| B 刚看见 A（进场 / 走近） | 只有 21 / 47 的位置；A 在动则静止到下一条 133；transform 的朝向为零 | 21 / 47 带位置与朝向；A 在动则 47 之后当场一条 135（X3）。A 进场 / 换场景时速度恒为零，旁人只收 21 |
| 服务器把 A 瞬移 | A ← 130；A ← 64（旧视野全部）；旧观察者 ← 64；下一个模拟步：A ← 47、新观察者 ← 47 | A ← 130 → 64（不再看得见的）→ 47（新看见的）→ 135（新看见且在动的）；旧观察者 ← 64；新观察者 ← 21。全部在同一次调用里；A 瞬移前后都看得见的人在 A 的客户端上原样保留（X6）。两版都按进视野半径 10 m 重建：离落点 10–20 m 的旧关系只销毁、不重建 |
| 瞬移后 3 s 内的旧位置上报 | 丢弃 | 同（计 `moves{result=teleport_stale}`） |
| 66 | 照发（不带 entity_id） | 照发（带 player_id）；客户端都不处理，每条一次 `Debug.Log`（§2.5） |

---

## 5 Java 设计

### 5.1 思路

把 133 当作「状态变化事件」而不是「每条输入的回声」，并按事件的来源分两种发送时机：

- **客户端输入**（一条 134 / 132 / 131 被接受）：只给 p 挂一个待发标记，场景帧在**外推之前**把标记兑现成一条 133，发给此刻的 `watchers(p)`。同帧多条输入合并成最后状态的一条，
  扇出与输入速率解耦。
- **服务器让仍在场的人停下**（挂机停推、备战、战斗在途的 131、交出冻结；将来的撞墙清速）：**当场**发，不挂待发、不等下一帧。当前客户端虽有1s外推上限，仍须立即同步真实停止；
  「停了」越早送到越好；帧内产生的停步若也挂标记，要晚一帧才发出，还会让当帧的新观察者在下一帧再收一条重复的。

两种时机共用**同一个发送函数** `broadcastMove(p)`。新观察者的初始状态由创建消息本身带全（21 / 47 带位置与朝向——状态复制的标准做法，客户端本来就读），
只有「正在动」这一项创建消息带不了，在建立兴趣的同一处用一条 135 补上。瞬移是「离开视野 + 换位 + 重新进入视野」的一次同步操作，复用 `ViewIndex` 的 `leave` / `enter`，
不引入新的可见性规则。66 保持原样，与 133 互不依赖。

### 5.2 21 / 47 / 133 / 135 / 130 的构造（`ScenePlayer`）

```java
/** 21 / 47 里的一项：transform 带 location；rotation 非 null 时一并带上（X3，基线 FillActorCreateMessageInfo 只填 location）；scale 不写。其余字段不变。 */
ActorCreateS2C toActorCreate() {
    Transform.Builder t = Transform.newBuilder().setLocation(position.toProto());
    if (rotation != null) t.setRotation(rotation);
    return ActorCreateS2C.newBuilder().setEntity(entity).setTransform(t) /* actor_type、guid、appearance、class、gender 照旧 */ .build();
}

/** 133：此刻的权威状态。transform.location 恒在（C2），rotation 上报过才在，scale 不写；velocity 恒在（停止时线上为 1a 00）。 */
ActorMoveS2C toActorMove(long serverTimeMs) {
    Transform.Builder t = Transform.newBuilder().setLocation(position.toProto());
    if (rotation != null) t.setRotation(rotation);
    return ActorMoveS2C.newBuilder()
            .setEntity(entity)                                  // 场景实体号（C1），不是 playerId
            .setTransform(t)
            .setVelocity(velocity.toVelocity())
            .setServerTimeMs(serverTimeMs)                      // 发送时刻的 SceneClock.epochMillis()，与 137 同一个时钟（取值时刻见 X2）
            .setMoveState(velocity.isOrigin() ? 0 : 1)          // 只用 0 / 1（基线同）
            .build();
}

/** 130：只发给本人。transform = 瞬移后的 location + rotation（非 null 必带，C6）；input_seq 不设（基线同，客户端只记日志）。 */
TeleportS2C toTeleport(int reasonCode) { … entity、transform{location, rotation 非 null 时}、reason … }

/** 正在动（速度非零）。新观察者只对这样的目标需要 135 补发。 */
boolean isMoving() { return !velocity.isOrigin(); }

/** 输入触发的 133 待发标记：只由 applyMove 置位，由 broadcastMove / switchScene / 瞬移清掉。不进 toSave()、不落盘。 */
void markMoveBroadcast();   boolean moveBroadcastPending();   void clearMoveBroadcast();
```

- 21 / 47 的每一项都带朝向，**包括进场时发给本人的那条 21**（同一个 `toActorCreate()`；客户端 21 / 47 走同一个读取函数，§2.4）。从没上报过朝向、存档里也没有的玩家 `rotation == null`，不带。
  Java 里任何一条被接受的移动上行都会把朝向写成非 null（`SceneWorld.java:2419`，请求没带时是全零的默认实例），并随写回持久化（`ScenePlayer.java:324-326`）、进场恢复（`:119-122`），
  所以除了从没动过的新号，几乎所有玩家的 21 / 47 都带 rotation。
- 现有包内访问器 `ScenePlayer#rotation()`（`:193-195`）**改为 public**（与相邻的 `position()` / `velocity()` 同一命名与可见性），不新增 `facing()`——`Facing` 在本类里是持久化类型，新增同名访问器只会撞名。
- 135 = `ActorMoveListS2C{moves = [各目标的 toActorMove(now)]}`。尺寸（推导，按 proto3 编码）：移动中、朝向只有 yaw、z = 0 的一条 133 约 72 字节，加 `MessageContent` 信封约 77 字节
  （`entity` 10、`transform` 33、`velocity` 20、`server_time_ms` 7、`move_state` 2）；停止的约 57 字节。另加 gate 的客户端帧头。
- 信封 `MessageContent{message_id, id = 0}`（C8），经现有的 `push(…)` 构造。

### 5.3 133 在哪里产生（逐条对 Java 自己的调用点，基线只作参照）

规则：**133 只在两类地方产生——(a) `applyMove` 接受了一条输入（挂待发，帧开头兑现）；(b) `haltMotion` 让仍在场的玩家由动变停（当场发）。
外推不发；离场 / 换场景 / 瞬移用销毁代替，不发。** 待发标记只有 (a) 这一个写入点。逐条：

| # | Java 位置 | 基线对应 | 条件 | 2.10 |
|---|---|---|---|---|
| ① | `applyMove`（J1），裁决成功之后（137 是给本人的、照旧当场发，与挂标记的先后无关） | B1 / B2 | 输入被接受（`INVALID` / 落位等待丢弃 / 冻结 DROP / 战斗 DROP 都不挂） | `markMoveBroadcast()`；下一帧开头一条 133，位置 = `MoveGuard` 截断后的权威值 |
| ② | `integrate` 挂机停推（J2） | 无（基线不清速） | 速度非零且 600 帧无消息（现有条件） | 改调 `haltMotion`：停推那一帧**当场**发 |
| ③ | `ClientRequestHandler` 战斗在途的 131（J3） | B3（基线无条件广播） | 在动才调（现有条件，`:332`） | 经 `haltForBattle` → `haltMotion` 当场发 |
| ④ | `PlayerBattleService` 备战（J4） | 无（Java D5） | 无条件调（现有条件，66 行为不变） | 同上；**在动才发 133**——静止的人只置 66 脏位、不发 133（I1：旁人手里已是零速度，或还有一条输入挂的待发在下一帧开头兑现） |
| ⑤ | `attachFreeze`（J5） | 无 | 在动才调（`:1283`） | 同 ③ |
| ⑥ | `haltForFreeze`（J6，5.2 / 5.4 冻结） | 无（基线冻结不清速、放行销毁也不发，§1.3） | 在动才调（方法内 `:1746`） | `haltMotion` 当场发。冻结中实体仍在场景里、旁人看得见；交出提交后实例被移除、旁人收到 51（C3 成立） |
| ⑦ | `integrate` 的 `move.blocked()` | B4（当 tick 发） | 导航夹持后由动变停 | 已有调用点改经 `haltMotion`，当帧发 133，位置为导航裁决点；66 仍置速度与位置脏位 |
| ⑧ | `removePlayer` / 交出提交后的移除 / `shutdown` | B5（只覆盖经 `HandleExitGameNode` 的退出；交接放行在基线同样不发） | — | **不发**：前两者 51 随即销毁实体；`shutdown` 不发任何下行（链路已断）。直接用 `stopMotion()`，不走 `haltMotion`。`ScenePlayer#stopMotion` 的 javadoc 补一句「也不发 133」 |
| ⑨ | `switchScene` | 无 | — | **不发**；清掉待发标记与落位等待（§5.4、§5.8）。旧场景的人收到 51；新场景的人从 21（带朝向、速度为零）看到它，不需要 133 |
| ⑩ | 新观察者（J7 的两处 + 瞬移重建） | 无（缺口 9） | 目标 `isMoving()` | 21 / 47 带朝向；在动的目标在 47 之后进一条 135 给观察者（§5.5）。不挂待发、不给目标的老观察者重发 |
| ⑪ | 同场景瞬移 | `TeleportWithinScene` | — | 不发 133（旧观察者收 64，新观察者收 21，瞬移后速度为零）；清待发（§5.7） |
| ⑫ | `integrate` 逐帧外推（J0） | `movement.cpp:108-110` 只置脏位；proto 注释「NOT every tick」 | — | **不发、不挂**：位置按已告知的速度连续演进，客户端按同一速度自行外推，没有新信息；挂了就是每个移动中的实体恒为 20 Hz |

**`haltMotion` 与两个现有入口**：

```java
/** 让仍留在场景里的玩家停下：清速度、置 66 速度脏位；原先在动才当场发一条速度为零的 133。本身不判「在动才停」，条件在各调用点。 */
private void haltMotion(ScenePlayer p) {
    boolean wasMoving = p.isMoving();
    p.stopMotion();
    p.markDirty(ScenePlayer.DIRTY_VELOCITY);
    if (wasMoving) {
        broadcastMove(p);
    }
}
```

- `haltForBattle` **保持 public、签名不变、仍无条件**（battle 包在用），函数体改为调 `haltMotion`；`haltForFreeze` 仍只在动时停、仍返回 `toSave()`，`if` 里改调 `haltMotion`；
  `integrate` 挂机停推的 `setVelocity(ORIGIN)` + `markDirty`（`:2495-2496`）改调 `haltMotion`。各调用点「在动才调 / 无条件调」的现有条件原样保留，66 的行为一字不变。
- 待发标记不进 `toSave()`，`onHandedOff` 的冻结后改动检查（`SceneWorld.java:1815` 比较 `toSave()` 与冻结快照）不受影响。
- **约定**（R2）：让仍留在场景里的玩家停下一律走 `haltMotion`；`removePlayer` / `shutdown` / `switchScene` / `teleportWithinScene` 直接用 `stopMotion()` 是有意的（⑧⑨⑪ 用销毁代替 133），不算漏点。
- Java 改位置 / 朝向 / 速度的写入点只有 `SceneWorld` 的 `:1559-1560`、`:1747-1748`、`:2418-2421`、`:2495-2501`、`:2844`、`:2895`、`:2946-2947` 七处（`SkillService` 不动朝向），上表没有漏。

**不变量 I1（速度一致）**：只要 p 没有未兑现的待发标记，对 p 的每个观察者 W，W 的客户端上 p 的速度（最后一条 133 / 135 条目里的值；从没收到过的是创建实体时的零）= 服务器上 p 的速度。
待发标记只存在于「输入被接受」到「下一帧开头」之间（≤ 一帧）。① 保证输入带来的变化被告知，②–⑥ 保证「由动变停」当场被告知，⑩ 保证新观察者从正确的状态开始（创建即零速度，在动才补 135），
⑧⑨⑪ 用销毁代替。「已经静止的人再停一次」不需要 133——这就是 ③⑤⑥「在动才调」与 ④「在动才发」的依据。

### 5.4 发送时机：输入合并到帧开头，停步当场发

**唯一的发送函数**：

```java
/** 发一条 133 给此刻看得见 p 的人。只读 watchers、不增删任何集合，可以在遍历 scene.players() 时调用（基线 movement.h:48 同样允许）。 */
private void broadcastMove(ScenePlayer p) {
    p.clearMoveBroadcast();                                   // 有没有收件人都清
    Collection<ScenePlayer> watchers = p.scene().watchers(p); // 不含 p 自己
    if (watchers.isEmpty()) {
        return;                                               // 没人看得见：不发、不积压
    }
    broadcast(watchers, push(ids.notifyActorMove(), p.toActorMove(clock.epochMillis())));   // 消息体序列化一次
    metrics.actorMoveDelivered(ActorMoveDelivery.BROADCAST, watchers.size());
}
```

待发标记是 `ScenePlayer` 上的一个布尔（不是 66 的脏位：66 没人看得见时保留脏位，133 不保留——没有观察者就丢掉，后来的观察者从 21 / 47 + 135 拿到当时的状态）。

帧顺序（`SceneWorld#step`，改 `:2449-2469`；`step` 的 javadoc `:2444-2447` 同改）：

```
stepping = true                  // 运行期守卫：帧内不许瞬移（§5.7）
for scene:
    flushMoveBroadcasts(scene)   // 新：挂了待发的玩家各调一次 broadcastMove
    integrate(scene)             // 外推（不发 133）；挂机停推经 haltMotion 当场发
    scene.refreshViews(viewChanges)
    emitViewChanges()            // 每个观察者 47 → 135（新看见的目标里在动的）→ 64
if 偶数帧: syncAttributes        // 66，不变
frame++
stepping = false                 // finally
```

- **(a) 输入 → 帧开头**。帧任务与客户端消息同在逻辑线程上串行（`SceneNode.java:421-423`），两帧之间收到的输入在兑现那一刻还没被外推，133 的位置就是裁决值本身
  （与基线「裁决后当场发」的内容一致，robot 能精确比对）；外推后再发会多出一步 `v × 0.05` 的位移。`server_time_ms` 取**发送时刻**（帧开头）的墙钟，不引入「裁决时刻」字段：
  帧间不外推，这一刻服务器的权威位置恰好就是 133 里的位置，是按帧打戳的快照语义；与基线「裁决时刻」的差别登记在 X2。
- **合并**：同一帧内同一玩家的多条输入只发最后状态的一条——「131 后紧跟 134」合并成一条「在走」，旁人看到的是最终状态。每实体每帧至多一条输入触发的 133 = **20 Hz 上限**，
  与输入速率无关（基线不合批，扇出跟着 gate 限频后的输入速率走，§1.4；Java gate 的输入上限是持续 50 条 / 秒）。正常客户端 0.25 s 一条，几乎不会合并。
- **(b) 停步 → 当场**。`haltMotion` 在调用处直接 `broadcastMove`：帧间的（备战、战斗在途的 131、冻结）在事件发生的那个逻辑任务里发出；帧内的（挂机停推，将来的撞墙）在 `integrate`
  遍历到它的那一刻发出。`broadcastMove` 会清掉 p 的待发标记，所以「输入挂了待发、下一帧之前又被服务器停下」只发一条（停步那条，内容是当前状态），不会在帧开头再发一条。
- **代价**（只对 (a)）：每条样本名义晚 0–50 ms（逻辑线程卡顿、或 `SceneTicker` 某次 run 没有整帧时更久，`SceneTicker.java:45`；一次 run 可能 0 到 5 帧）。延迟**逐条不同**
  （客户端上报间隔 = 0.25 s 加至多一个渲染帧，与服务器帧不锁相），等于给样本加了至多 50 ms 的到达抖动。当前客户端不读 `server_time_ms`、不做时间对齐，相位回卷的那条样本使远端角色的误差插值
  在 0.15 s 插值窗口里多收 / 少收至多「速度 × 0.05」（信任上限 10 m/s 时 ≤ 0.5 m，显示速度波动至多约 ±33%，推导见 §2.2），与一般公网抖动同量级，**接受**。基线当场发没有这一项（X2）。
- **顺序**：
  - 帧开头的 133 在本帧的 47 / 64 之前发出。本帧要失去 p 的 W 先收 133 再收 64（C3 允许）。
  - 帧内不存在未兑现的标记（输入不会在帧内到达，帧开头已全部兑现），所以本帧新看见 p 的 W 收到的 47（+ 135）就是全部，**帧内没有重复**。挂机停推先于本帧的视野刷新：
    停推的 133 发给刷新前的观察者，刷新后才看见它的 W 从 47 创建出一个零速度的实体，不需要 135。
  - 进场（`enterScene`）与瞬移在两帧之间同步执行：新观察者当场收到 47 + 135（当前状态）。若某个**在动**的目标此时恰好挂着待发（上一帧之后刚接受了一条输入），
    下一帧开头新观察者会再收一条同状态的 133——这是唯一可能重复的情形，无害（R9），不去重。
- **不过滤**：兑现与当场发都**不看**被发者的 `frozen()` / `inBattle()`（③–⑥ 发送时它恰好处于或即将处于这两种状态；`integrate` 里那句 `if (player.inBattle()) continue` 不要带进兑现循环）；
  收件人也**不按**冻结 / 战斗在途过滤（与 `syncAttributes` 同）——交出没提交会原地解冻、战斗结束会回到场景，那时它手里的状态必须是新的，漏掉的 133 没有任何补发路径。
- **实现**：`flushMoveBroadcasts` 直接遍历 `scene.players()` 看标记（与 `integrate` 同为 O(N) 的布尔检查；也可以并进 `integrate` 的同一个循环——每人先兑现、再外推，等价），
  不另建待发名单，所以移除 / 换场景不需要额外清理名单；`switchScene` 与瞬移只把标记清掉。帧开头这一段的耗时记进 `game.scene.broadcast{kind=actor_move}`（§7）。
- **收件人** = `scene.watchers(p)`（不含 p 自己）。不需要基线的「互相可见」过滤：Java 的反向索引是精确的、观察者一侧的每次增删都通知了（§3.2 末段），所以它就是「客户端上有 p 的人」；
  基线那条「对方表满时收不到」的缺口在 Java 不存在（有意差异 X1）。冻结中会话已离开的实例也会被当成收件人，由 gate / 链路层丢弃，与 66 同（§3.2）。
- **与视野的关系、带宽上界**：133 只读 `watchers`，不触发、也不参与视野重判（位置变化照旧经 `Scene#relocate` → `ViewIndex.moved` 登记，按「静止立即、移动中每累计 1 m」的节奏重判）。
  容量只约束观察者自己的表（`ViewIndex.DEFAULT_CAPACITY = 100`），`watchers(p)` 可以超过 100；所以**单个客户端**收到的 133 只来自它 `watching` 里的实体，
  上界 = 100 × 20 条 / 秒，典型 = 可见的移动者数 × 4–8 条 / 秒（每条约 77 字节，§5.2）；服务器出站总量 = Σ_W |watching(W)| × 各实体的兑现率 ≤ 在线人数 × 100 × 20 条 / 秒。
  进 10 m / 出 20 m 的滞回带里（10–20 m）已建立的关系照样收 133（与 66 同）。单个客户端的下行由兴趣表容量封顶，这是 133 不另设距离 / 频率分级的理由（YAGNI；要做 LOD 是另一批）。

### 5.5 新观察者的初始状态：21 / 47 带朝向，在动的目标补 135

| 发生处 | 观察者一侧 | 目标一侧 |
|---|---|---|
| `enterScene`（`:1490-1512`，进场 / 换场景） | 进场者 ← 47（`:1509`，各项带朝向）之后一条 135：`entered.seen()` 里 `isMoving()` 的目标（没有就不发） | `entered.seers()` ← 21（`:1511`，带进场者的朝向）。**不发 133**：进场 / 换场景时进场者的速度恒为零 |
| `emitViewChanges`（`:2506-2528`，帧内进视野） | 每个观察者在它的 47 之后、64 之前 ← 一条 135：`delta.added()` 里 `isMoving()` 的目标（没有就不发） | —（帧内的变化都按观察者归并；目标在动时它的 135 条目已经在各新观察者自己的 135 里） |
| 瞬移重建（§5.7） | 被瞬移者 ← 47（新看见的）之后一条 135：其中 `isMoving()` 的 | 新观察者 ← 21（带朝向）。**不发 133**：瞬移后速度为零 |

- **静止目标不需要任何补发**：位置与朝向由 21 / 47 带到，速度在客户端创建实体时就是零（C7）。所以「给进场者的 seers 发 133」这条路径整个不存在。
- 135 的条目是发送时刻的状态（`toActorMove(clock.epochMillis())`）；135 直接构造、**不经 `broadcastMove`**，所以不清目标的待发标记（目标的其他观察者还没收到那条输入的 133），也不计入 `deliveries{kind=broadcast}`。
- 只有一个在动的目标时也发 135（不退化成 133），规则统一、好测；135 是协议为「刚进视野、一次同步很多 actor」准备的（§1.1），客户端处理器与 133 同义（§2.1）。
- 量：补发只含正在动的目标。进人群时一条 135 的条目数 = 可见的移动者数（上限 100 × 约 72 字节）；人人在动的基准里每个 47 条目都配一个 135 条目（§8.2 R4）。
- 耗时：`emitViewChanges` 里的 135 落在 `broadcast{kind=view_changes}` 计时段，`enterScene` 与瞬移里的不在任何帧内计时段（§7.2）。
- 66 的 `markFullStateForNewWatcher` **保留**（66 不动，§5.6）；135 是新增的、独立的一条路径。

### 5.6 66：保持不变

收件人（`watchers`）、频率（偶数帧、每实体 ≤ 10 Hz）、`entity_id = player_id`、只带脏字段、没人看得见时保留脏位、`markFullStateForNewWatcher` 补发，全部不动，**2.10 不因任何基准结果翻转这一点**（Q1）。
2.10 落地后 66 与 133 是两条互不依赖的通道：同一观察者在同一条链路上先后收到（133 在帧开头或停步当场，66 在偶数帧末尾），客户端只处理 133。
PARITY「属性同步 66」行的标题「移动广播的唯一通道」与「从不发 133 / 135 / 130」两句改写，并登记「两版同步评估停发移动 66」（§5.13）。

### 5.7 同场景瞬移原语

```java
/**
 * 逻辑线程，且不在帧内：step() 期间调用抛 IllegalStateException（它会改兴趣集合）。
 * target 必须在世界范围内。facing 为 null 表示保持原朝向——Java 扩展，只给管理口用；基线恒覆盖（movement.cpp:189）。
 */
public TeleportResult teleportWithinScene(ScenePlayer p, Vec3 target, Rotation facing, TeleportReason reason)
```

`TeleportReason { TEAM_REGROUP, ADMIN }` 与 `TeleportResult { TELEPORTED, NOT_HERE, FROZEN, IN_BATTLE, INVALID_TARGET }` 是嵌在 `SceneMetrics` 里的标签枚举（同 `TransferReason` /
`TeamFollowResult` 的先例，业务代码直接使用，小写名即指标标签，§7）；原语的入参 / 返回值就用这两个枚举，不另建一套再映射。130 的 wire 值是协议常量，放 `MovementRules`：
`TELEPORT_REASON_ADMIN = 3`（proto 注释「3=admin」）、`TELEPORT_REASON_TEAM_REGROUP = 5`（基线 `kTeleportReasonTeamRegroup`），`SceneWorld` 里一个私有 `switch` 把 `TeleportReason` 映射过去。

**前置**（按此顺序判，任一不满足：什么都不改、不发任何消息、计 `teleports{reason, result}`、返回对应结果）：

1. `playerById(p.playerId()) == p`（实例仍在本节点，否则 `NOT_HERE`）；
2. `!p.frozen()`（`FROZEN`）：交出冻结中的位置属于冻结快照，挪了它会被 `SceneWorld.java:1815-1817` 判成冻结后改动（`game_scene_transfer_post_freeze_mutations_total`）；
3. `!p.inBattle()`（`IN_BATTLE`）：回合制战斗在途时客户端在战斗画面、外推也跳过它（`integrate` `:2491-2493`），挪位置没有意义；
4. `MovementRules.insideWorld(target)`（隐含有限），且 `facing` 非 null 时三个分量都有限（同 `MoveInput.isAcceptable` 对朝向的要求，`MoveInput.java:41-45`；朝向会落盘并广播）；否则 `INVALID_TARGET`。

基线原语不查 2 / 3（调用方 `CheckFollowLeader` 的守卫已挡掉，§1.8）；Java 放进原语本身（有意差异 X7），4.3b 的守卫照样保留。RESOLVING / PRECHECK（选目标中，未冻结）不挡，与 5.2 的冻结口径一致
（基线调用方连「换图请求在途」也挡，那一层由 4.3b 对队员自己的 `switchInFlight` 守卫承担）。`inBattle()` 不覆盖「进场恢复读在途、Redis 战斗锁仍在」的窗口，原语此时放行，无害（战斗状态不含位置）。
死亡不挡（`applyMove` 也不查，基线同）。

**步骤**（单线程、一次调用内完成，中间不让出）：

1. `ViewIndex.Reentered r = scene.teleport(p, target)`。两个新方法：
   - `ViewIndex#reenter(ScenePlayer p)`（包内）：先把 `watching(p)` 拷成独立列表（`leave` 之后 `watching(p)` 返回空集），再 `leave(p)`（离开者从格子、双方兴趣表、待刷新名单里彻底清掉，
     `ViewIndex.java:133-152`；`grid.remove` 用的是索引里记的旧格，不读玩家当前坐标），再 `enter(p)`（`:99-127`，按 p 的当前位置当场双向判，**只按进视野半径 10 m 建立**，受各自容量限制）。
     返回 record `Reentered(lostWatching, lostWatchers, seen, seers)`——四个都是原始集合，`ViewIndex` 仍是「全部重置」。
   - `Scene#teleport(ScenePlayer p, Vec3 target)`：与 `relocate` 一样**先校验再动**——`target` 非有限抛 `IllegalArgumentException`，`players.get(p.playerId()) != p` 抛 `IllegalStateException`，
     两者都在动任何状态之前；然后 `p.setPosition(target)` → `view.reenter(p)`。`Scene.players` 不动、人数不变。不能复用 `Scene.remove` / `add`（它们会动 `players` 表）。
   这是 `relocate` 之外第二个被允许改位置的入口（`ScenePlayer.java:29-30` 的类注释约定、`:228` `setPosition` 的 javadoc、`Scene.java:19` 的类注释一并改）。
   4.3b 若改 `enter` 的建链规则（队友挤进已满的表，§5.12），`Reentered` 的形状与下面的下行表（可能多出被挤者的 64）随之改。
2. 状态：`facing != null` 时 `setRotation(facing)`；`stopMotion()`；`markDirty(DIRTY_TRANSFORM | DIRTY_VELOCITY)`（66 的积压语义照旧，新观察者下一个同步帧收到 p 的全量 66）；
   `moveGuard().reset(target, clock.nanoTime())`（锚点到落点、额度回满——否则客户端从落点发的第一条上报会被当成瞬移截断、回 137 把人拉回去）；
   挂落位等待 `TeleportSettle(target.x, target.y, now + 3 s)`（覆盖上一次的，被覆盖的计 `settles{voided}`）；`clearMoveBroadcast()`。
   66：`seen ∖ lostWatching`（p 新看见的）里每个目标 `markFullStateForNewWatcher()`（与 `enterScene` 对新看见的目标同）。
3. 下行。差集在 `SceneWorld` 里算（`gone = lostWatching ∖ seen`，`fresh = seen ∖ lostWatching`，各 ≤ 100 项）；同一链路上按此顺序发，顺序是 C3 / C5 / C9 的要求：

| 收件人 | 消息（按序） |
|---|---|
| p 本人 | 130（`toTeleport(reason 的 wire 值)`，`rotation` = 瞬移后的 `p.rotation()`）→ 64 `{gone 的实体号}`（非空才发）→ 47 `{fresh}`（非空才发，各项带朝向）→ 135（`fresh` 里 `isMoving()` 的，非空才发） |
| `lostWatchers` | 64 `{p.entity}`（一份序列化） |
| `seers` | 21（`p.toActorCreate()`：新位置、带朝向） |

   - **本人一侧只发差集**：瞬移前后都看得见的人（`lostWatching ∩ seen`）没有动过，p 的客户端上它们的实体与插值状态本来就是对的，**不销毁、不重建、不发任何东西**。
     这些目标在整个调用前后 p 都在它们的 `watchers` 里（索引里的断开与重连在同一次调用内完成，中间没有任何发送），它们的 133 / 66 照常发给 p，I1 / C3 不受影响。
     基线全量销毁是因为它的重建在下一个模拟步、当下分不出谁仍可见（§1.6）；Java 同步重建，分得出。4.3b 的归队是短距离瞬移（8 m 阈值），交集是常态而不是边角。
   - **旁人一侧**（p 确实换了位置）仍是销毁 → 创建：一个旁人 X 同时在 `lostWatchers` 与 `seers` 里（短距离瞬移）时先收 64 再收 21，客户端先销毁再按新位置创建（C5；只发 21 会被去重、位置不动）。
     瞬移后 p 的速度为零，旁人不需要 133。
   - **重建按进视野半径 10 m 判**：离落点 10–20 m 的旧关系（滞回带内，原先互相看得见）**只销毁、不重建**，等双方再走近到 10 m 内才恢复——基线全部重置同义。
     对 4.3b 的可见后果：归队时站在队员旧位置附近、离队长 10–20 m 的旁人会看到队员消失。
   - p 收到的 64 / 47 取自 `watching(p)` 的差集，`ViewIndex` 的自身排除（`ViewIndex.java:115` `theirs == mine` 跳过）保证其中不含 p 自己；瞬移不重发本人的 21（C9、I8）。
   - **人群里的边界**：落点 10 m 内超过 100 人时，p 重建的表按格子遍历序先到先得（`ViewIndex.java:113-124`），可能与原来的不是同一批人，差集因此可以很大（最多 100 个 64 条目加 100 个 47 条目）；
     旧观察者里离落点超过 10 m 的各腾出一个名额，等任一方下次重判才补人（与离场同）。
   - 朝向保持（`facing = null`）而 p 从没上报过朝向时，130 不带 `rotation`，客户端把本人的朝向归零（§2.3）；只出现在从没动过的新号上，且朝向在当前客户端的精灵画面上仍不可见。
4. 指标与日志：`teleports{reason, result=teleported}`；`aoi.changes` 按真实发出的增删条目计（`leave` += `|lostWatchers| + |gone|`，`enter` += `|seers| + |fresh|`；p 一侧的交集不计）；
   `deliveries{kind=catch_up}` += 135 的条目数；INFO 一行（player、scene、from → to、reason、`lostWatchers` / `gone` / `fresh` / `seers` 四个数）。

**不做**：不改位置记录（只记场景，`PlayerLocations`）；不调 `teamFollow.onEnteredScene`（不是换场景）；不刷新活跃帧；不发 79（客户端会 `World.Clear()`，§2.3）。
**调用约束**：只能在逻辑线程、且**不在帧内**调用——它改兴趣集合，而 `integrate` / `syncAttributes` / `flushMoveBroadcasts` 正在遍历玩家或兴趣集合。除 javadoc 外加运行期守卫：
`step()` 期间置布尔 `stepping`，`teleportWithinScene` 在它为真时抛 `IllegalStateException`（快速失败比只写 javadoc 可靠，成本是一个布尔）。
2.10 的两个调用方（dev 管理口、4.3b 的组队回调）都是投到逻辑线程的独立任务，天然满足。

### 5.8 落位等待

- `ScenePlayer` 加 `TeleportSettle settle`（record：`targetX`、`targetY`、`deadlineNanos`；不落盘、不进 proto），只由 §5.7 写、由 `applyMove` 读和清，`switchScene` 清（唯一的换场景入口；
  实例按进场新建，跨节点 / 跨 zone 天然作废）——等价于基线「换了场景就作废」的场景句柄比较。
- 纯函数 `MovementRules.judgeAfterTeleport(double horizontalDistance, long nowNanos, long deadlineNanos) → SETTLED | EXPIRED | STALE`，规则照基线：先距离（≤ 3.0 m，含等号 → `SETTLED`）
  后时间。**时间判定用差值** `nowNanos - deadlineNanos >= 0` → `EXPIRED`，否则 `STALE`：`SceneClock#nanoTime` 就是 `System.nanoTime()`，起点任意、可为负，只保证差值有意义；
  仓库里现有的到期判断都用差值（`SceneWorld.java:1589` `clock.nanoTime() - sw.deadlineNanos() >= 0`、`TransferTombstone.java:65`）。基线直接比大小是因为它用的是 steady_clock 自纪元起的 uint64 毫秒。
  `deadlineNanos = now + TELEPORT_SETTLE_TIMEOUT.toNanos()`，允许回绕。常量 `TELEPORT_SETTLE_RADIUS = 3.0`、`TELEPORT_SETTLE_TIMEOUT = 3 s`（基线 `movement.h:20-23`），放 `MovementRules`。
- 在 `applyMove` 里的位置：`isAcceptable()` **之后**（非有限 / 越界的输入照旧计 `INVALID`，不让 NaN 距离参与判定；基线对非有限上报是按时间落到 kStale / kExpired，口径不同但都是丢弃）、
  `MoveGuard.admit` **之前**：
  - `settle == null` → 照常；
  - `STALE` → 整条丢弃：位置、朝向、速度、66 脏位、133 待发标记、`MoveGuard`（锚点与额度）都不动，不发 137，计 `moves{result=teleport_stale}`
    （「每条移动上行在 `moves{result}` 里恰好计一次」的口径不变；三条移动上行本来就是 Empty 应答、不回包）；
  - `SETTLED` → 清 `settle`、计 `teleport.settles{result=settled}`、照常裁决；
  - `EXPIRED` → 清 `settle`、计 `expired`、WARN 一行（player、目标、上报点；不进指标标签）、照常裁决。
- 战斗在途的 131（`STOP_ONLY`）与冻结中的 DROP 在分发处就截下了，不经过 `applyMove`，与基线「先战斗 / 冻结闸、后落位闸」同序。`touch`（活跃帧）照旧在分发处刷新
  （`ClientRequestHandler.java:212`，先于处理器），被丢弃的 `STALE` 上报照样算活跃（基线同）。
- **超时之后的语义**：「照常裁决」在 Java 的信任模型里 = 相对锚点（落点）过 `MoveGuard`，而 `STALE` 的上报不消耗额度，超时后第一条远处上报到达时额度仍是满的 24 m：
  - 离落点 **≤ 24 m**：整条原样接受、不回 137，服务器位置回到旧位置——**等于静默撤销这次瞬移**（与基线无导航场景的整段接受一致）；
  - **> 24 m**：截断到距落点 24 m 并回 137，客户端吸附到截断点，此后照常。
  130 没被客户端应用只可能是客户端没处理（同一条链路不会单独丢帧）。**没有落位等待时，上面这两种情况会立刻发生在每一次瞬移之后**（在途的旧位置上报几乎总是存在），
  8–24 m 的归队会被第一条在途上报静默撤销——这才是落位等待存在的理由。见 §8.2 R5 与 N2。
- **已知边界**：瞬移后 3 s 内又同图换线（`switchScene` 保留坐标并重置 `MoveGuard`，`SceneWorld.java:1558-1561`）会作废落位等待，此后在途的旧位置上报按 `MoveGuard` 裁决（24 m 内原样接受）。
  窗口很窄（3 s 内被排空改派 / 跟随换线），与基线「场景句柄不同即作废」同义，不处理。

### 5.9 dev 管理口瞬移（Q7）

唯一用途：在 4.3b 之前让 robot 与本机切片能端到端验证瞬移（§11.6）。没有任何客户端消息号。逐项对着现有的实例管理口（批次 5.3）落：

- **路径**：`POST /admin/scene/player/teleport`（`SceneAdminController.TELEPORT_PATH`）。与实例管理口同一套：只绑本机、运维令牌鉴权（过滤器按 `/admin/*` 注册，`SceneNodeConfiguration.java:204`，
  新路径自动受保护）、过滤器那行审计、运行模式不是 dev / test → 403（`runMode.allowsGmCommands()`，**先于解析请求体**；类注释 `SceneAdminController.java:32-45`）。
- **契约**（Java 自有，`game-api/src/main/proto/game/api/scene_admin.proto` 追加，不是同步产物；文件头注释补「与玩家瞬移」）：
  `TeleportPlayerRequest{uint64 player_id = 1; double x = 2; double y = 3; double z = 4;}` → `TeleportPlayerResponse{uint32 tip_id = 1;}`（命名与 `DestroyInstanceRequest / Response` 同式）。
  请求体约 37 字节，现有 `MAX_BODY_BYTES = 1024` 够用。
- **结果到 tip 的映射（写死，与 `PlayerBattleService` 的 PrepareBattle 同一套码）**：

  | 结果 | tip |
  |---|---|
  | `TELEPORTED` | 0 |
  | `INVALID_TARGET`（坐标不在世界范围内 / 非有限；`player_id = 0`） | 1005 `kInvalidParameter` |
  | `NOT_HERE`（本节点没有这个玩家） | **1004 `kEntityIsNull`**（同 `PlayerBattleService.java:191-193` 的 `NOT_HERE`；不用 1012 `kPlayerNotFoundInSession`——它的语义是「会话上找不到玩家」，game-scene 从不用） |
  | `FROZEN` / `IN_BATTLE` | 1006 `kFeatureUnavailable` |

- **逻辑线程入口** `SceneWorld#adminTeleport(long playerId, Vec3 target) → int tip`（与 `destroyInstance` 返回 tip 同式）：`playerId == 0` → 计 `teleports{reason=admin, result=invalid_target}`、回 1005；
  `playerById(playerId) == null` → 计 `teleports{admin, not_here}`、回 1004；否则 `teleportWithinScene(p, target, null, ADMIN)`（朝向保持）并按上表映射。
  **找人阶段就拒绝的同样计 `teleports`**，所以该指标是「每次瞬移请求恰好计一次」。
- **HTTP 线程只做鉴权与编解码**：新接口 `SceneAdminController.PlayerAdmin { CompletableFuture<TeleportPlayerResponse> teleportPlayer(long playerId, double x, double y, double z); }`
  （`InstanceAdmin` 旁边），控制器两个构造器各加一个参数；`SceneNodeConfiguration` 加一个 bean（照 `sceneInstanceAdmin`，`:174-187`）；`SceneNode#teleportPlayer` 照 `destroyInstance`（`SceneNode.java:576-586`）
  建 future、`!running || w == null` 时异常完成、经 `submitAdmin` 投到逻辑线程。控制器方法照 `destroy`（`SceneAdminController.java:135-160`）：403 → 解析失败 400 → 等结果 → 审计 → 200 + protobuf。
- **等结果超时**（等待上限沿用 `switchResolveTimeout + 2 s`，缺省 6 s）：瞬移有客户端可见的副作用且不幂等，照「建副本」那种有副作用接口的写法**撤掉**——控制器 `pending.cancel(false)`；
  撤成功回 503（文案「场景逻辑线程 N ms 内没有执行完，已撤掉（不会再瞬移）」），撤不掉说明结果恰好在这一刻定了，照实返回（同 `abandon`，`SceneAdminController.java:166-171`）。
  逻辑任务开头 `if (result.isDone()) return;`，执行完用 `result.complete(…)` 交回。「已过了开头的检查、瞬移做完、`complete` 时才发现被撤」这个极窄的竞态下会出现「已瞬移却回了 503」，dev 工具可接受，robot 把 503 判失败。
- **审计**：每次调用一行 `admin scene player-teleport operator={} player_id={} x={} y={} z={} tip={}`（同 `:128`、`:156` 的写法）。
- **管理口指标**：`SceneMetrics.ADMIN_OP_PLAYER_TELEPORT = "player_teleport"`，`SceneAdminAuthFilter.opOf`（`:85-93`）加这条路径——不加的话瞬移口在 `game.scene.admin.requests{op}` 里静默记成 `other`。
- **文案与注释**：`forbidden()` 的文案（`:190`「实例管理口只在…」）改成不带「实例」的通用说法；`MAX_BODY_BYTES` 的注释（`:52`「两个请求都只有一个整数字段」）、控制器类注释的接口清单、
  `SceneAdminAuthFilter.java:18`「批次 5.3 只有 dev 实例管理口」、`SceneMetrics.java:1023-1024` `adminRequest` 的 javadoc、`SceneNodeConfiguration.java:173` 与 `:190-201` 的 bean 注释 / 启动日志「dev 实例管理口」同改。
- **坐标范围**：服务端只查世界范围（`MovementRules.WORLD_LIMIT = 1e7`），**不加客户端地图范围校验**（本批保持定案：瞬移不新增导航夹持；已有导航只作用于后续普通移动，坐标须由操作者选在可走处）；落点是否在客户端地图内（服务器 x ∈ [2, 298]、y ∈ [2, 398]，C6）
  与是否可走由操作者负责。

### 5.10 线程所有权与 I/O

全部在场景逻辑线程：标记、兑现、当场发、补发、瞬移、落位判定。没有新的阻塞 I/O、没有新的 Redis / MySQL 访问。管理口遵守既有模式（HTTP 线程不碰场景状态）。
出站仍经 `ClientSink` → 节点链路：scene → gate 链路出站缓冲越过高水位是「丢帧并断开链路」（`SceneMetrics` `LinkDrop.WRITE_BUFFER_FULL`），gate → 客户端写缓冲越过 2 MB 断开会话
（`GateNode.java:80-81`），**没有「悄悄丢一条 133」的路径**——丢就是断线，断线后旁人收到 51（C4 不会因背压被破坏）。

### 5.11 不变量

| # | 不变量 | 由谁保证 |
|---|---|---|
| I1 | p 没有未兑现的待发标记时，每个观察者的客户端上 p 的速度（最后一条 133 / 135 条目的值；从没收到过的是创建实体时的零）= 服务器上 p 的速度。待发只存在于「输入被接受」到「下一帧开头」之间 | §5.3 的触发点表；客户端 `SpawnActor` 不设 Velocity |
| I2 | 133 / 135 只发给发送时刻的 `watchers` / 新观察者；因此收到某实体的 133 之前一定收到过它的 21 / 47，收到 64 / 51 之后不再收到（C3） | `watchers` 是精确反向索引；观察者一侧的增删都通知；离场一侧靠 79 清场或会话已结束；同一链路 FIFO |
| I3 | 133 / 135 / 130 的 `entity` 恒为场景实体号，`transform.location` 恒在，信封 `id = 0`（C1、C2、C8） | `toActorMove` / `toTeleport` 是唯一构造处；经 `push(…)` |
| I4 | 每实体每帧至多一条**输入触发**的 133：每条被接受的输入在下一帧开头被兑现（同帧合并），当时没有观察者则丢弃、由 21 / 47 + 135 覆盖；服务器停步当场另发，并吃掉未兑现的待发 | 布尔标记 + 帧开头兑现；`broadcastMove` 清标记 |
| I5 | 落位等待中 `STALE` 的输入不改位置 / 朝向 / 速度 / 66 脏位 / 133 待发标记 / `MoveGuard`（锚点、额度），不产生任何下行；活跃帧照旧刷新，`moves{teleport_stale}` 计一次 | §5.8 |
| I6 | 被瞬移的人在调用返回时已与落点 10 m 内的人双向建好可见性（受容量限制），旧观察者已收到销毁 | `ViewIndex#reenter` 同步完成 |
| I7 | p 自己永远收不到自己的 133 / 135 条目；130 只发给 p | `watchers` 不含自己；`toTeleport` 只经 `sendTo(p)` |
| I8 | p 本人收到的 64 / 51 / 47 永不含 `p.entity`；除进场那条自己的 21 外不给本人发自己的 21，瞬移不重发（C9） | `ViewIndex` 的自身排除（`:115`）；§5.7 的收件人表 |

### 5.12 给 4.3b 留下的接口

| 接口 | 形状 | 4.3b 怎么用 / 注意 |
|---|---|---|
| 瞬移原语 | `SceneWorld#teleportWithinScene(p, target, facing, reason)` → `SceneMetrics.TeleportResult`（public，逻辑线程，不在帧内） | 归队：`(队员, leader.position(), 朝向, TEAM_REGROUP)`。原语拒冻结 / 战斗，4.3b 的守卫（对队员自己的 `switchInFlight`、`inBattle`、战斗锁）照旧在前 |
| 原因 | `SceneMetrics.TeleportReason.TEAM_REGROUP`（指标 `team_regroup`；130 的 wire 值 `MovementRules.TELEPORT_REASON_TEAM_REGROUP = 5`） | robot team 断言「B 收到 130 reason 5」用它 |
| 互相可见 | `SceneWorld#mutuallyVisible(a, b)`（public，逻辑线程；同场景且 `a ∈ watching(b) ∧ b ∈ watching(a)`） | `ShouldRegroup(距离, 互相可见)` 直接用。**进场与瞬移同步评估**，基线 `visibilityEvaluated`（`player_team.cpp:543-555`）防的两种情况——进场链、瞬移后紧跟的第二次检查——在 Java 不存在，4.3b 不需要那个分支；普通移动仍按帧内节奏重判（见下面 ①） |
| 朝向 | `ScenePlayer#rotation()`（改为 public，`Rotation` 不可变，可为 null） | 传 `leader.rotation()`，**为 null 时传 `Rotation.getDefaultInstance()`**（与基线 `player_team.cpp:569` 同值：队长没写过朝向时队员被写成零朝向）。不要把 null 直接传进去——null 在原语里是「保持原朝向」，只给管理口用 |
| 落位等待 | 原语自带；重复瞬移覆盖目标与截止 | 4.3b 不需要自己挡过期上报 |
| 指标 | `game.scene.teleports{reason=team_regroup, result}` 2.10 就注册 | 4.3b 只加 `TeamFollowResult` 的新取值（如 `REGROUPED` / `REGROUP_SKIPPED`） |
| robot | robot `MessageIds` 的 `notifyActorMove` / `notifyActorMoveList` / `notifyTeleport`；`MoveAssertions` 的 133 / 135 / 130 解析与按实体号匹配、瞬移判定纯函数 | `TeamScenario` 直接复用 |

**4.3b 仍要自己做**：`TeamFollowService` 同场景分支（`:246-249` 现在回 `SAME_SCENE`）改成归队判定；`kTeamRegroupDistance = 8` 与 `ShouldRegroup`；
**队长归属交接在途不归队用 `leader.frozen()`**（`ScenePlayer.java:456-458`，对应基线 `IsOwnershipInFlight(leader)`；**不用 `switchInFlight(leader)`**——它对应基线 `IsSceneChangeBusy`，
把不冻结的 PRECHECK / RESOLVING 也算在途，基线没有拿它查队长，照它写队长还在选目标时队员就不归队，而归队只由事件触发、错过不补）；
game-team 入队提交后通知被加入者所在 scene 节点的通道（改「组队」行有意差异 ⑥）；同图换线跟随之后的归队（Java 换线经 `switchScene` → `onEnteredScene` → 异步读 → 回到逻辑线程时已同场景 → 归队）。

**4.3b 要考虑的 2.10 事实**：

- ① **「互相不可见」的三种来源**：(i) 兴趣表满；(ii) 判定距离是水平的而视野是三维的（基线 `player_team.cpp:557-559` 只算 dx / dy，Java `ViewIndex` 按三维 ≤ 10 m，高度原样取上报值），
  水平 ≤ 8 m 而高差大时互不可见；(iii) 重判节奏窗口——移动输入 `relocate` 之后到下一帧 `refreshViews` 之前还没重判（单条上报可跳 ≤ 24 m）。(ii)(iii) 瞬移都能解决，照常瞬移至多多一次，无害。
  只有 (i) 是 Java 的瞬移解决不了的：`ViewIndex` 不挤人（`link` 表满直接 false，`ViewIndex.java:273-276`；`enter` 按格子遍历序先到先得），瞬移后队长不一定进得了队员新建的表，队长自己的表满也收不下队员。
- ② **基线靠 kTeammate 挤出解决表满**（§1.8），归队瞬移后队长 ↔ 队员必互见；Java 的 team-spec D8（「不做队友 AOI 优先级……优先级没有可见效果」，`team-spec.md:1913`）的前提在归队落地后不再成立，
  **4.3b 必须重开 D8**。推荐与基线客户端可见结果一致的做法：同队目标建立兴趣不受容量阻挡——挤掉一个非队友并给观察者补 64（不照搬基线的静默挤出，`mmorpg-client-contract-aoi.md:328`），
  或更简单：同队条目不计入容量（每人至多多 4 条，不产生被挤者）。这样判定里「互相不可见一律瞬移，同基线」即可，不需要排除表满。
  次选：登记有意差异「人群中归队后可能互不可见、走路跟随不生效」，并在判定里只排除表满（否则每个跟随时机都会瞬移一次）；所需的「表是否满」查询由 4.3b 自己加。
  **2.10 不预置 `viewFull` 之类的查询**（本批没有消费者，选了挤出方案根本用不到）。「瞬移返回后紧跟的第二次检查看到互相可见、距离 0，不会重复瞬移」只在双方表未满（或 D8 重开后）成立。
- ③ 当前客户端已禁用远端图元碰撞体（`ActorWorld.cs:194-195`），历史 `f537079` 中落到队长脚下被图元挡住的问题已解决（§2.7）。
- ④ 当前客户端点地寻路中被130吸附后仍按原目的地重新规划（`TianyongPlayerController.cs:305-329`），被归队的队员会从队长脚下走回原目的地；跟随是事件触发的（`onEnteredScene` / `onBattleFreezeCleared`），
  不会因此循环瞬移，**不需要冷却**。4.3b单独验瞬移原语时让被归队者静止或用WASD；走路跟随已落地，完整跟随验收需按当前TeamFollowDriver的控制状态核对。
- ⑤ 瞬移重建只按 10 m 判（§5.7）：归队时站在队员旧位置附近、离队长 10–20 m 的旁人会看到队员消失，直到再走近。

### 5.13 需要同步修改的文档与登记

- **PARITY.md**（按行标题找；本分支上功能表行号比 main 大 1）。状态取值只用表头列的枚举（`已对齐` / `Java 待做` / `mmorpg 待做` / `Java 进行中` / `不适用`），没跑过的验证不写成已对齐：
  - 「移动广播 133」行 → 状态 **`Java 进行中`**（与 5.4 同口径）。备注逐项写：单测与 `-Dgame.bench` 基准已跑；robot `movement`、双 Unity 客户端验收**未运行**（本机没有中间件与 Unity）；
    有意差异 X1–X5、X8、X10 摘要。robot 与真客户端验收跑完后再改「已对齐（行为有意不同）」。
  - 「同场景瞬移」行 → 状态 **`Java 进行中`**，同样逐项写证据；X6、X7、X9；注明 2.10 没有生产调用方、4.3b 接上。
  - 「移动上行」行 → 有意差异 ③ 改为「…停推那一帧当场发一条速度为零的 133（66 照旧）」；补「瞬移后 3 s 落位等待」一句；「mmorpg `73b852f3` 有修正，Java 待跟」一段改成已跟。
  - 「属性同步 66」行 → 标题去掉「移动广播的唯一通道」；「从不发 133 / 135 / 130」改为「移动另经 133 广播（见『移动广播 133』行），66 原样保留，客户端（`9ccb99c51`）仍没有66处理器」；
    状态格里的「mmorpg `73b852f3` 有修正，Java 待跟」、行尾「2.10 落地时改写」一段、「不另发单播」一句同改；备注登记**「两版同步评估停发移动 66」**并写上事实：真客户端上每条 66 是一次主线程 `Debug.Log`（带堆栈），
    人群里每秒数百到上千次（§2.5）。
  - 「136」行 → 补「服务器主动瞬移发 130 见『同场景瞬移』行」。
  - 「视野（AOI）」行 → 补「21 / 47 的 `transform` 带朝向（有意差异，基线只填 location；mmorpg 可选）；新看见的在动目标经 135 补发；瞬移按『销毁 → 创建』同步重建，本人一侧只发差集」。
  - 「组队跟随 v1.1」行 → 只注明「依赖的 2.10 已交付（Java 进行中）；4.3b 要重开 team-spec D8」，状态不变。
  - 「gate 按消息号限频」行 → 行文更正（不改行为）：「语义一致、边界略宽松」改为「Java 的持续受理上限是基线的 2 倍（基线按整秒时间戳、任意相邻两个整秒合计 N 条；Java 是 1 秒纳秒滑窗、N 条 / 秒）」。
    要不要收紧到与基线一致不在 2.10 范围，另行决定。
- **`docs/reference/mmorpg-client-contract-movement.md`**：头部基线改为「`9c9c012b7`，移动广播与瞬移部分按 `73b852f3`」；§0 第 2–3 条（133 有发送方、别人看到你在动靠 133、
  并注明客户端没有 66 的处理器）；§2 消息清单（133 / 135 / 130 的「基线实际行为」与 66 行「唯一通道」）；§3.6 标题与 130 的 reason 取值（补 5，注 Java 管理口用 3）；
  §4.5 两行（「别人收到 66」→「别人收到 133（与 66）」）；§6 加 6.0「133」、把 6.1 的「唯一的位置广播」改掉、§6.3（`:281`「观察者只能从后续的 66 拿朝向」→ Java 由 21 / 47 带朝向）；
  §10 清单（`:374`「不发 133 / 135 / 130」）改为本规格的要求；新增一节「瞬移与落位等待」。
- **`docs/reference/mmorpg-client-contract-aoi.md`**：§0 表（`:26`「133 / 135 / 130 …从不发」）；§3.1（`:147` transform 行「只拷 location」注明 Java 另带 rotation、`:156`「不带速度和朝向…要等它的下一条 66」）；
  §3.6（robot 的 133 / 135 / 130 处理器是空函数）；§4（`:195`）；§5.2（133 的时序）；§6 汇总表；§7 第 9 条（`:333`「创建消息只带位置」→ Java 带朝向、在动的补 135）与第 12 条（`:336`「…从不发送」）；
  §8（`:353` `toActorCreate()` 一行）；§10 清单：`:402`（47 先于 66 那条）补「133 / 135 同样在 47 之后、64 之后不再有」，`:407` 的「不发」清单去掉 133 / 135 / 130，
  `:424`「可选：给新观察者补发一条全量 66」改为已做并指向 21 / 47 带朝向 + 135。
- **`docs/reference/mmorpg-client-contract-scene.md`**：`:148`（21 的 `transform`「只填 location」）与 `:318`（清单里 21 的 `transform.location`）注明 Java 的 21 / 47 另带 `rotation`（X3）。
- **`docs/design/architecture.md`**：① 「scene 场景帧」一段（`:1113-1127`）：帧顺序加「帧开头兑现 133」与「停步当场发」，基准数字按 §11.5 记录的结果写（含 `:1127` 引用的 PARITY 行名）；
  ② §11 指标表：改 `game_scene_broadcast_seconds`（`:1978`，`kind` 加 `actor_move`，`view_changes` 注明含 135）、`game_scene_moves_total`（`:1979`，`result` 加 `teleport_stale`）、
  `game_scene_aoi_changes_total`（`:1982`，补瞬移的口径）、`game_scene_admin_requests_total`（`:1994`，`op` 加 `player_teleport`）四行，新增 `game_scene_actor_move_deliveries_total`、
  `game_scene_teleports_total`、`game_scene_teleport_settles_total` 三行；③ §4.21 的管理口清单（`:767-768`）与端点表（`:1857`）加 `POST /admin/scene/player/teleport`；
  ④ `:879`（备战停步）与 `:1690`（冻结停步）的「旁人收到速度为 0 的 66」改为「133（与 66）」。
- **`game-robot/README.md`**：movement 一节的步骤与选项说明（`:40-41`、`:65`、`:83-94`，现在全按 66 写；补瞬移段、`--scene-admin-url` / `--expect-dev`、运维令牌与单节点切片的前提）。
- **路线图 `docs/porting/roadmap.md`**：2.10 行（`:44`）改状态；「建议顺序」（`:120` 起）去掉第 1 条并顺延，`:126-127` 对 2.10 的引用同改；7.5 行（`:112`）加待办「撞墙清速经 `haltMotion` 当帧发 133」，
  `contract-navmesh-spec.md` `:458`（「66（速度变 0）」）与 `:724`（`result` 取值「不变」）同步。
- **代码注释**：
  - `SceneWorld`：类注释的「视野」「移动」「帧」三段（含 `:94`「21 / 47 只带位置」）；`:1497`、`:1730`、`:1744`、`:2397`、`:2942` 各处写「66」的注释；`step` 的 javadoc（`:2444-2447`「外推 → 视野刷新 → 偶数帧属性同步」）；
    `integrate` 的 javadoc（`:2478` 一带，含 `:2481` 引用的旧基线行号）。
  - `ScenePlayer`：类注释（`entity` 也是 133 / 135 / 130 的口径；`:29-30` 改位置的约定）、`setPosition`（`:228`）、`stopMotion`（`:241-248`）、`markFullStateForNewWatcher`（`:250-255`「21 / 47 只带位置」）、
    `toActorCreate`（`:277-280`「transform 只填 location」）的 javadoc。
  - `Scene.java:19`（「位置变化只能经本类（add / remove / relocate）」）；`ViewIndex.java:130-131`（「离开者自己的兴趣列表静默清空」——`reenter` 的调用方要自己通知）。
  - `PlayerBattleService.java:221`；`SceneMetrics` 的 `MoveResult`（`:126-138`）与 `BroadcastKind`（`:177-182`）成员注释、`adminRequest` 的 javadoc（`:1023-1024`）。
  - §5.9 列的管理口注释与文案；`game-api` 的 `scene_admin.proto` 文件头；robot `SceneAdminClient` 类注释（`:19-26`）与 `MovementScenario` 类 javadoc。
  - **引用基线行号的注释**（停在 `9199d217d` 之前的行号，2.10 正好改这几处代码）：`ClientRequestHandler.java:322`（「基线 mvh.cpp:170-173、:205-208」）、`:329`（「基线 mvh.cpp:188-192」），`BattlePolicy.java:21`、`:24`，
    `SceneWorld.java:2481` 与 `InBattleGateMatrixTest.java:646`（「movement.cpp:49-52 的 exclude<InBattleComp>」）。改成 `73b852f3` 的行号并**同时写函数名**
    （「mvh.cpp MoveStart / MoveSync / MoveStop 的战斗·冻结闸」= `:171-174` / `:221-224` / `:195-202`；`movement.cpp:71-74`），下次基线漂移不必再改；STOP_ONLY 的注释补上基线在这里无条件 `BroadcastMove`（`mvh.cpp:200`）。
  - `MovementSyncTest` 的用例名（§11.2 T12）与 `HandOffTransferTest` / `ZoneTravelHandOffTest` 的用例名。
- `inventory/delta-20261010.md` 是快照，不改（条目 ④ 的勘误见 §0.2）。

### 5.14 代码改动清单与提交注意

| 模块 | 文件 | 改动 |
|---|---|---|
| game-scene | `SceneMessageIds` | 加 `notifyActorMove` / `notifyActorMoveList` / `notifyTeleport`（按「服务裸名 + 方法名」解析，缺了启动失败） |
| | `ScenePlayer` | `toActorCreate` 带朝向；`toActorMove` / `toTeleport` / `isMoving`；`rotation()` 改 public；待发标记（`markMoveBroadcast` / `moveBroadcastPending` / `clearMoveBroadcast`）；`TeleportSettle` |
| | `SceneWorld` | `applyMove` 挂标记与落位判定；`broadcastMove`；`flushMoveBroadcasts`；`step` 顺序与 `stepping` 守卫；`haltMotion`（`haltForBattle` / `haltForFreeze` / `integrate` 挂机停推改调它）；`enterScene` / `emitViewChanges` 的 135；`teleportWithinScene`；`adminTeleport`；`mutuallyVisible`；`switchScene` 清标记与落位等待 |
| | `ViewIndex`、`Scene` | `ViewIndex#reenter` 与 record `Reentered`；`Scene#teleport`（先校验再动；复用 `leave` / `enter`，不新增可见性规则） |
| | `MovementRules` | 落位常量与 `judgeAfterTeleport`；`TELEPORT_REASON_ADMIN = 3` / `TELEPORT_REASON_TEAM_REGROUP = 5` |
| | `SceneMetrics` | 嵌套枚举 `TeleportReason` / `TeleportResult` / `TeleportSettle` / `ActorMoveDelivery`；`MoveResult.TELEPORT_STALE`；`BroadcastKind.ACTOR_MOVE`；`ADMIN_OP_PLAYER_TELEPORT`；§7 的新计数 |
| | `SceneAdminController`、`SceneAdminAuthFilter`、`SceneNode`、`SceneNodeConfiguration` | §5.9：`TELEPORT_PATH` 与处理方法、`PlayerAdmin` 接口与两个构造器、`opOf`、`SceneNode#teleportPlayer`（经 `submitAdmin`）、`PlayerAdmin` bean、文案与注释 |
| | 测试支撑 | `WorldTestAccess` 加待发标记与落位等待的访问器；`MovementSyncTest` 的 `enterNew / enterSaved / steps / moveStart / moveSync / moveStop / forward` 抽成包内共用夹具 `MovementFixture`（同 `TransferFixture` 的做法），新测试类复用 |
| game-api | `scene_admin.proto` | §5.9 的两条消息（Java 自有） |
| game-robot | `MessageIds`、`MoveAssertions`、`MovementScenario` | §11.6 |
| | `SceneAdminClient`（+ `SceneAdminClientTest`） | 瞬移的手工编解码：`TELEPORT` 路径、`teleport(playerId, x, y, z)` / `teleportRaw`、应答解码；double 按原始位判零（`Double.doubleToRawLongBits(v) != 0` 才写），与生成类逐字节一致；`statusHint` 的 404 文案补 2.10 |
| | `RobotOptions`、`InstanceOptions`、`RobotMain` | `--scene-admin-url` 的解析提到 `RobotOptions`（与 `battleAdminUrl` / `matchAdminUrl` 同列），`InstanceOptions` 改为复用——**不在 movement 分支调 `InstanceOptions.parse`**（它会连带校验 `--mirror-config-id` 等无关选项，`TravelOptionsTest.java:94-99` 钉着「movement 不解析别的子命令的专属选项」）；movement 分支构造 `SceneAdminClient`（令牌 `AdminClient.resolveToken(env.get("GAME_ADMIN_TOKEN"))`）并传 `expectDevAllowed`；`--observe-timeout-ms`（`:193`「B 收到 66」）、`--scene-admin-url`（`:215`）、`--expect-dev`（`:224`）的帮助文本与 usage 的 movement 一行 |
| docs / PARITY | §5.13 | |

不改：同步产物（proto、消息号、tip、配表）、gate（对 scene 下行没有消息号白名单）、login、scene-manager、game-team、`tools/local`（缺省的 18104 就是切片第一个 scene 节点的管理端口，令牌由 `start-slice.sh` 生成）。

**提交注意**：代码在 worktree `D:\luyuan\wt210`（分支 `batch-2.10`）里做，提交只 `git add` 本批路径、不用 `-A`；PARITY.md 在 worktree 里正常提交，不需要临时拼索引（先前「另一个会话未提交的组队改动」已随 `d942f74` 提交并推送）。
分支上已有 `a761096`。接回时在 worktree 里 `git rebase main`，再到主工作区 `git merge --ff-only batch-2.10`；主工作区若有别的会话未提交且与本批重叠的改动，ff 会拒绝（fail-closed），先与对方协调、
等对方提交后再 rebase——修订收尾时就有这样一批（战斗缺省回合上限，重叠 `PARITY.md` 与 `RobotOptions.java`，见文首），**不要替对方提交，也不要绕开它**。

---

## 6 配置与常量

### 6.1 基线

全是编译期常量：`kTeleportSettleTimeoutMs = 3000`、`kTeleportSettleRadius = 3.0`、`kTeleportReasonTeamRegroup = 5`、`kMoveStateStopped / Walking = 0 / 1`（`movement.h:13-23`）；
`kTeamRegroupDistance = 8`（4.3b）。没有配置项。

### 6.2 Java

**不新增配置项，也不加只为基准服务的开关**。新增常量（都在 `MovementRules`）：`TELEPORT_SETTLE_RADIUS = 3.0`、`TELEPORT_SETTLE_TIMEOUT = Duration.ofSeconds(3)`、
`TELEPORT_REASON_TEAM_REGROUP = 5`、`TELEPORT_REASON_ADMIN = 3`（取 proto 注释里「3=admin」）；`move_state` 0 / 1。理由：落位半径 / 超时与客户端「落位后第一条上报从落点出发」的行为耦合（§2.3），
基线也是常量；做成配置只会多一种和客户端对不上的方式。合批粒度就是帧（20 FPS，现有 `MovementRules.STEP_SECONDS`），不单独配置。

---

## 7 指标

### 7.1 基线

只有日志：`metric=team_regroup` / `team_regroup_skipped`（`player_team.cpp`，4.3b）与落位超时的 `LOG_WARN`（`movement.cpp:250-253`）。133 没有任何计数。

### 7.2 Java（Micrometer；不以 player / session / 场景号 / 节点号作标签，`architecture.md:1871`、`AGENTS.md` §5）

标签枚举照仓库先例（`TransferReason`、`TeamFollowResult`）**嵌在 `SceneMetrics` 里并由业务代码直接使用**：`SceneMetrics.TeleportReason { TEAM_REGROUP, ADMIN }`、
`SceneMetrics.TeleportResult { TELEPORTED, NOT_HERE, FROZEN, IN_BATTLE, INVALID_TARGET }`、`SceneMetrics.TeleportSettle { SETTLED, EXPIRED, VOIDED }`、`SceneMetrics.ActorMoveDelivery { BROADCAST, CATCH_UP }`。
`SceneMetrics` 不反向依赖 world 包（它今天只 import game-api 与 micrometer）；取值靠现有的 `counters(Class, name, tag, desc)` 全部预注册、`tagValue` 取小写名。

| 指标 | 类型 | 标签（取值） | 含义 |
|---|---|---|---|
| `game.scene.broadcast{kind=actor_move}` | timer | `kind` 新增 `actor_move`（`BroadcastKind.ACTOR_MOVE`） | **每帧记一次**：全部场景帧开头兑现（`flushMoveBroadcasts`）的耗时，与 `view_changes` / `attribute_sync` 并列。停步当场发的 133 不计入（帧内的落在整帧耗时里，帧间的不在任何帧内计时段）；是罕见事件 |
| `game.scene.broadcast{kind=view_changes}` | timer | 不变 | **2.10 起含 `emitViewChanges` 里 135 的构造与下发**（基准对比这一段的前后数字时要算上它）；`enterScene` 与瞬移里的 135 不在帧内，不计 |
| `game.scene.actor.move.deliveries` | counter | `kind` ∈ {`broadcast`, `catch_up`} | 133 / 135 条目送出的「(收件人, 实体)」对数：`broadcastMove`（帧开头兑现与停步当场发）计 `broadcast`，新观察者的 135 条目计 `catch_up`。含冻结中会话已离开的无会话收件人（§3.2）。带宽评估用（× 约 77 字节） |
| `game.scene.moves{result}` | counter | 新增 `teleport_stale`（`MoveResult.TELEPORT_STALE`） | 落位等待中丢弃的上行（每条恰好计一次的口径不变） |
| `game.scene.teleports` | counter | `reason` ∈ {`team_regroup`, `admin`}；`result` ∈ {`teleported`, `not_here`, `frozen`, `in_battle`, `invalid_target`} | **每次瞬移请求恰好计一次**：原语调用，或管理口在找人阶段就拒绝的（`player_id = 0`、找不到人，§5.9） |
| `game.scene.teleport.settles` | counter | `result` ∈ {`settled`, `expired`, `voided`} | 落位等待的结局：`voided` = 换场景或被下一次瞬移覆盖时还没落位 |
| `game.scene.aoi.changes{change}` | counter | 不变 | 瞬移按真实发出的条目计：`leave` = 旧观察者的 64 + 本人 64 的条目数（差集），`enter` = 新观察者的 21 + 本人 47 的条目数（差集） |
| `game.scene.admin.requests{op}` | counter | `op` 新增 `player_teleport` | 管理口调用（§5.9） |

### 7.3 勾稽

- `moves{accepted + clamped + corrected}` ≥ 帧开头实际发出的 133 条数（合并、没有观察者、被停步吃掉都会让后者更少）；`deliveries{broadcast}` = Σ 每条 133 的收件人数。
- `teleports{result=teleported}` ≥ `settles{settled + expired + voided}`；差值是仍在等待的人（等待没有定时器，长期不上报的人一直挂着）与等待中离场的人（实例随之丢弃、不计）。
- `settles{expired}` 持续非零 = 客户端没有应用 130（或掩码与服务端落点长期不一致）：查 WARN 日志里的目标与上报点。

---

## 8 隐患与边界

### 8.1 基线自身的隐患（Java 对应处置）

| # | 基线隐患 | Java |
|---|---|---|
| B-1 | 133 不合批：扇出跟着输入速率走（gate 限频后单人突发 50 条 / 秒、持续约 25 条 / 秒，§1.4），没有自己的上限 | 输入触发的 133 帧开头合并，每实体 ≤ 20 Hz（X2），与输入速率无关 |
| B-2 | AFK停推、进战、交接冻结不清速/不发133，客户端未立即获知真实停止；历史 `f537079` 无限外推，当前 `9ccb99c51` 超过1s才自行清速 | 全部清速 + 当场133（X4、X5），不能依赖客户端兜底 |
| B-3 | 「互相可见」过滤：对方表满时收不到移动（`movement.h:44-45`） | 反向索引精确，无此缺口（X1） |
| B-4 | 新观察者没有初始状态（缺口 9）：移动中的人进视野静止到下一条 133；创建消息不带朝向 | 21 / 47 带朝向，在动的目标当场补 135（X3） |
| B-5 | 瞬移后新位置的可见性晚到下一个模拟步，其间被瞬移者的 133 没人收到；自己一侧全量销毁再重建（仍看得见的人也闪一下）；钉住它的观察者收不到销毁 / 重建 | 同步重建，本人一侧只发差集；Java 没有钉住（X6） |
| B-6 | 4.3b 的「还没评估」窗口要靠 `visibilityEvaluated` 绕开，否则进场链与紧跟的第二次检查都会重复瞬移；表满靠 kTeammate 静默挤出（§1.8） | Java 进场 / 瞬移同步评估，没有这个窗口；表满是 4.3b 要重开的 team-spec D8（§5.12 ②） |
| B-7 | 130 的 `input_seq` 不设，proto 注释说客户端据此丢弃预测——客户端实际不用（§2.3） | 照搬（Q8） |
| B-8 | 本次未重跑 C++，历史证据不代替跨实现验收 | Java 以自己的单测为准；robot 与真客户端的证据待在有中间件 / Unity 的机器上跑（§11.0）；跨实现只对客户端可见形状（§11.7） |

### 8.2 Java 移植时会踩的 / 残余风险

| # | 风险 | 处置 |
|---|---|---|
| R1 | 用错 id：133 / 130 的 `entity` 写成 player_id（66 用的是 player_id），客户端静默丢弃、没有任何日志 | `toActorMove` / `toTeleport` 唯一构造处；单测断言等于 21 里的 `entity`（§11.2 T1） |
| R2 | 漏停步点：当前客户端最多继续外推约1s，错误位置仍保留到下一条同步；历史 `f537079` 更会一直跑 | §5.3逐条测试（T6）；仍在场景的停步统一走 `haltMotion`，离场/换场景/瞬移以销毁代替 |
| R3 | 瞬移时旁人一侧只发 21 不先销毁：客户端去重、位置不动（C5）；或本人一侧把自己的实体号发进 64（C9） | 下行顺序固定（§5.7 第 3 步）；单测断言旁人一侧 64 → 21、本人一侧交集零下行、本人收到的 64 / 47 不含自己（V1、V2） |
| R4 | 带宽 / 帧耗时：66 不动再加 133，人群里每个客户端下行约翻倍；2000 人挤在 15 m 的极端基准 p99 已贴着 50 ms（`architecture.md:1124-1125`：2026-09-30 本机 p99 48.9 ms，其中偶数帧 66 扇出约 8 ms，是旧日期的数） | 推导：帧开头兑现每帧约 400 人 × 约 100 观察者 ≈ 66 偶数帧扇出的 1/5，即每帧约 +1.6 ms；另有 135——基准里人人在动，每个 47 条目配一个 135 条目，`view_changes` 段的构造与序列化量约翻倍（真实场景只含正在动的目标）。**按推导已贴线甚至越线**。处置：§11.5 同机前后对比、只做记录，写回 `architecture.md`；超过 50 ms 只在那段注明（那段本来就写着极端密度要分散出生点），**不触发任何设计变更**（Q1、Q12） |
| R5 | 130 没被客户端应用：3 s 后第一条远处上报相对落点过 `MoveGuard`——离落点 ≤ 24 m 的整条接受、不回 137，瞬移被静默撤销；> 24 m 的截断到 24 m 并回 137（§5.8） | 可接受（只在客户端没处理 130 时；与基线无导航场景一致）；`settles{expired}` 与 WARN 可见；不做额外的「超时撤销」逻辑（N2） |
| R6 | 客户端掩码 / 地图边界与落点不一致（当前部分地图已有导航；瞬移仍按定案原样使用队长 / 管理口给的落点）：落点不可走时客户端挪到最近可走点回报 131，挪动 > 3 m 时 3 s 内被当成过期丢弃，且客户端不重报，不一致持续到下一次移动；被地图边界夹过但仍可走时客户端根本不回报（§2.3） | 基线同（`team-system.md` V.5）；4.3b 的落点是队长刚上报的位置，同一份掩码上可走、客户端自己也夹过；真正受影响的只有 dev 管理口，坐标由操作者负责（§5.9） |
| R7 | 在帧内遍历中调用瞬移会改兴趣集合 | 运行期守卫：`step()` 期间 `teleportWithinScene` 抛 `IllegalStateException`（§5.7 末）；两个调用方都是独立任务 |
| R8 | 输入触发的 133 比输入晚 0–50 ms，同帧的两条输入只有一条 133；robot 若按「每条输入一条、X ms 内」断言会偶发失败 | robot 观察时限沿用 `observeTimeout`（远大于 50 ms）；133 与输入的配对复用 `MoveAssertions.matchInputs` 的合并容忍规则（§11.6） |
| R9 | 新观察者刚收到135，而在动目标还挂着待发标记，下一帧开头又收到同状态133 | 允许这一帧内的重复，不去重；当前客户端重置误差插值、两端仍推进，旧位置样本可产生至多一帧位移量的回拉误差。客户端不是幂等的（§2.2），不得把补发做成向全部观察者重发。帧内不会重复（§5.4）；测试断言至少一条、内容及顺序正确 |
| R10 | `PlayerBattleService` 备战无条件调停步（J4）：静止的人没有 133，只置 66 脏位 | 依赖 I1：旁人手里已是零速度，或还有一条输入挂的待发在下一帧开头兑现；T6 的备战项静止 / 在动各测一条 |
| R11 | 4.3b：人群中归队后队长与队员可能互不可见（Java 表满不挤人，基线靠 kTeammate 挤出） | 2.10 不处理、不预置查询；写进 §5.12 ②，由 4.3b 重开 team-spec D8 |
| R12 | 静默断网没有FIN/RST，Java gate无空闲/心跳检测，只在写失败或写缓冲超过2MB时断开，时间不确定 | 当前客户端距最后133超过1s即停外推；服务端仍积分，出视野时64才使其消失。挂机停推仍需第30s的零速133纠正位置；不能凭画面提前停下认定服务端已停（§11.8第4步） |
| R13 | 跨节点交出后、目标79到达前，离开者客户端上旧场景实体不再有133 | 当前客户端按最后速度最多推进约1s，再停等79清场；窗口由交接时长决定，验收不要误判 |
| R14 | 历史 `f537079` 中远端图元碰撞体会挡住本地玩家，上报意图速度与实际位移不一致 | 当前基线已由 `40e6fdd43` 禁用图元碰撞体（`ActorWorld.cs:194-195`）；不再是当前风险，保留编号供历史评审追溯 |
| R15 | 21 / 47 带朝向改了两条原本与基线逐字相同的视野消息 | `Transform.rotation` 是契约里已有的字段，当前 `9ccb99c51` 仍读取（§2.4）；Java 仓里对 21 / 47 断言「不带 rotation」的只有 `SceneWorldTest.java:112`（新号，仍成立）；登记为有意差异 X3 |

### 8.3 需要客户端配合 / 登记为客户端改进（本仓库不改客户端）

- **66**：每条一次带堆栈的主线程 `Debug.Log`（§2.5），人群里每秒数百到上千次；基线同样触发。建议客户端给 66 注册一个静默处理器，或两版同步停发移动 66（Q1，已登记「两版同步评估」）。
  §11.8 第 7 步的人群观察只作为这项评估的输入。
- 旧 `HandlerRegistry` 已删除，覆盖风险已收窄为任何代码重复注册同一 `OnNotify` 消息号（§2.1）；不再要求清理旧空壳。
- `40e6fdd43` 的外推上限、碰撞体修正、转向补发与跟随已在当前基线落地（§2.7）；C4仍由服务器保证及时同步真实停止。
- 用 133 的 `server_time_ms` 做时间对齐：能消掉帧开头合并带来的到达抖动（X2）。注意 Java 的时间戳是发送时刻（帧开头），与位置成对。

---

## 9 有意差异

### 9.1 采纳

| 编号 | 差异 | 理由 | 客户端可见？ | 两版同改？ |
|---|---|---|---|---|
| X1 | 133 收件人 = `watchers(p)`（精确反向索引），不做「对方表里也有我」的过滤 | Java 的 `watchers` 就是「客户端上有 p 的人」；基线的过滤是因为它不知道谁看得见自己（`movement.h:41-45`）；没有隐身、没有 NPC | 是：基线表满缺口下收不到的人，Java 收得到 | 否（基线做法受它的 AOI 结构限制） |
| X2 | **输入触发**的 133 在下一帧开头兑现、同帧合并，每实体 ≤ 20 Hz；`server_time_ms` 取发送时刻（帧开头）的墙钟。基线每条输入当场发、时间戳是裁决时刻 | 扇出与输入速率解耦（B-1）；位置仍是裁决值，帧开头那一刻服务器的权威位置就是它 | 是：每条样本晚 0–50 ms 且逐条不同，当前 `9ccb99c51` 上表现为误差插值的那条样本在 0.15 s 内多收 / 少收至多「速度 × 0.05」（≤ 0.5 m）；同帧的「停 + 走」只看到最终状态；`server_time_ms` 比基线晚 0–50 ms（当前客户端仍不读） | mmorpg 可选 |
| X3 | 新观察者的初始状态：**21 / 47 的 `transform` 带朝向**（基线 `FillActorCreateMessageInfo` 只填 location），新看见的**在动**目标在 47 之后补一条 135；基线不补 | 创建消息带完整的初始 transform 是状态复制的标准做法，客户端本来就读（§2.4）；修缺口 9 在真客户端上的版本（Java 原先的 66 补发客户端收不到）；静止目标因此不需要任何补发 | 是：进视野即在动；朝向只落在实体的 transform 上——当前 `9ccb99c51` 的精灵仍始终朝相机，朝向在画面上不可见（§2.2） | mmorpg 可选（缺口 9 已登记「mmorpg 待做」） |
| X4 | 挂机停推清速并当场发 133；基线既不清速也不发 | 「移动上行」有意差异 ③ 的延续（原先发的是客户端收不到的 66） | 是 | mmorpg 可选（③ 已登记） |
| X5 | 备战 / 进场恢复挂冻结 / 交出冻结时停步并当场发 133；基线要等客户端自己发 131（B3），交接放行经 `DestroyDeposedPlayer` 销毁、不发 133 | Java 早已在这些时刻停步（D5、5.2 §5.5），只是改用客户端能收的通道 | 是：旁人看到的人在冻结 / 备战那一刻停下 | mmorpg 可选 |
| X6 | 瞬移的可见性重建在同一次调用里同步完成（旧观察者 64、新观察者 21）；**本人一侧只发差集**，仍看得见的人不销毁不重建。基线销毁当下、重建在下一个模拟步（新观察者收 47），自己一侧全量销毁 | Java 的视野索引可以当场判，分得出谁仍可见；没有「还没评估」窗口，4.3b 不需要绕开 | 是：新位置的人早到一个模拟步（名义 ≤ 50 ms）看见；新观察者收 21（单条）而不是 47（列表），客户端同义；被瞬移者屏幕上仍看得见的人不闪 | 否 |
| X7 | 瞬移原语自己拒冻结 / 回合制战斗在途；基线原语不查（调用方查） | 冻结快照一致性与战斗语义属于原语的前置条件，放在原语里一处守住 | 否 | 否 |
| X8 | 掉线 / 主动退出 / 被接管前不发「停了」的 133；基线经 `HandleExitGameNode` 的退出（断线 / 主动退出 / 停机 / 排空疏散 / 非交接的 ReleasePlayer）由 `StopMotionForExit` 发 133 后紧跟 51 | 51 随即销毁实体，133 没有可见效果。Java「交出提交后的移除」与基线的 `DestroyDeposedPlayer` 一样不发，不算差异；Java 停服不发任何下行（链路已先断开） | 是：少一条无效果的 133 | 否 |
| X9 | dev 管理口瞬移（reason 3） | 4.3b 之前端到端验证原语；Java 自有工具，不是客户端协议 | 否（生产不可达） | 否 |
| X10 | 战斗在途 / 冻结中收到 131：基线无条件发一条 133（B3）；Java 战斗在途只在仍在动时停、发，冻结中的 131 直接丢（`FreezePolicy.DROP`） | Java 在备战 / 冻结的那一刻已经停步并发过 133（X5），再发没有新信息 | 是：少一条无效果的 133 | 否 |

### 9.2 列出但不采纳

- **N1**：瞬移时旁人一侧也「只改变化的部分」——`lostWatchers ∩ seers`（瞬移前后都看得见 p 的人）不销毁重建，改发 **130**（客户端对非本人实体的 130 直接吸附、清插值状态，§2.4 第三条路）。
  好处：旁人屏幕上 p 不闪、不丢精灵状态。不采纳：这是 Java 独有的下行（基线从不给非本人发 130），当前客户端 `9ccb99c51` 与 Go robot 对非本人130仍未做联机验收（客户端源码能力已核实，§2.3），robot 断言还要多一种形状。
  （改发 133 的变体更差：对方会在 0.15 s 内滑过整段距离。**本人一侧**的「只改变化的部分」已采纳，见 X6。）
- **N2**：落位超时后把 `MoveGuard` 锚点改回瞬移前的位置（显式「撤销瞬移」）：旧位置离落点 ≤ 24 m 时现状已经等于撤销（§5.8）；> 24 m 时改回锚点等于送客户端一次免费的远距离瞬移。
  只在客户端没处理 130 时才触发，不值得多存一个点。
- **N3**：（原「用 21 / 47 带上朝向」，**已采纳**，见 X3。）
- **N4**：输入触发的 133 也当场发（不合批）：与基线时机逐字一致，但扇出跟着输入速率走（B-1）。若局域网验收认为 X2 的到达抖动不可接受，再考虑的改法是「本帧首条输入当场发、同帧后续合并到下一帧开头」
  （位置仍是裁决值），而不是下面的 N7。
- **N5**：补发逐条用 133（不用 135）：进人群时一个观察者要收上百条单独的 133；135 是协议为此准备的、客户端已处理。
- **N6**：2.10 就停发移动 66：见 Q1。
- **N7**：133 的位置做延迟补偿（裁决位置 + 速度 × 兑现延迟）：位置不再等于服务器的权威位置（服务器自己的外推也是按帧离散的），还破坏 T1 / robot 的逐位比对。
- **N8**：待发标记记下裁决时刻，让 `server_time_ms` = 裁决时刻（同基线）：没有任何客户端读这个字段；改了之后按时间戳对齐的客户端算出的位置会比服务器帧开头的权威位置超前「速度 × 延迟」，
  只是换了一种不一致；多一个每玩家状态与一条合并规则。只登记差异（X2）。
- **N9**：静止目标也进 135、或给进场者的观察者补一条 133（只为朝向）：21 / 47 带朝向之后没有必要，而且人群里是「每次进场 × 全部观察者」的扇出。

### 9.3 照搬基线、不算差异

133 的字段与取值（`entity` = 场景实体号、transform 整体、velocity 恒在、`server_time_ms` = 墙钟毫秒（时钟口径相同，取值时刻见 X2）、`move_state` 只有 0 / 1）；
每条被接受的输入都会产生一次（合并前）；逐帧外推不发；不发给自己；130 只发给本人、`input_seq` 不设、reason 透传、`transform.rotation` = 瞬移后的朝向；
瞬移 = 写位置、写朝向（`facing` 非 null 时；null「保持」是只给管理口用的 Java 扩展）、清速度、`MoveGuard` / 落位等待、旧观察者销毁、新位置按进视野半径重新进场；
落位等待的 3 s / 3 m、先距离后时间、换场景作废、没有定时器、在战斗 / 冻结闸之后判、被丢弃的上报照样刷新活跃帧；交接放行后的源端移除不发 133；66 原样保留。

---

## 10 决策记录（原「开放问题」，2026-10-13 定案，全部关闭）

| # | 问题 | 结论 |
|---|---|---|
| Q1 | 66 还发不发？ | **2.10 无条件保留 66**（收件人、频率、`entity_id`、脏位保留、新观察者补发全不变），**不因基准结果翻转**。理由：基线保留了 66（两版这一点一致）；本批的目标是修客户端可见缺口，停发是另一个有回归面的改动（`MovementSyncTest`、`PrepareBattleTest`、`InBattleGateMatrixTest`、robot 的 `SceneMoves` / `CrossNode` / `Mirror` / `BattleSettle` 都拿 66 作断言），而且属于两版同步的决定——不该由一台开发机上 200 帧里的第 2 大样本触发；66 留着在本批里还能当 133 的旁证。代价：人群里每个客户端的下行约翻倍（推导：100 个移动中的可见者，133 约 31–62 KB/s，66 约 50 KB/s）；真客户端每条 66 一次主线程 `Debug.Log`（§2.5）。**登记「两版同步评估停发移动 66」**（PARITY「属性同步 66」行；基线的 66 还承载 `combat_state_flags`，要不要拆是 mmorpg 的事） |
| Q2 | 133当场发还是帧内合并？ | 输入触发帧开头兑现、同帧合并（X2）；服务器停步当场发（D1不变）。前者把扇出与输入速率解耦且位置仍为裁决值；后者立即同步停止，不能等客户端1s兜底，帧内只挂标记还会晚一帧并造成重复 |
| Q3 | 新观察者的初始状态怎么给？ | **21 / 47 带朝向；在动的目标当场补 135**（X3）。静止目标不需要任何补发；移动中的目标若「交给下一条 133」最多晚 250 ms 才动起来 |
| Q4 | 补发用 133 还是 135？ | **只用 135，只发给观察者**（N5）；只有一个在动的目标也发 135。不存在「目标一侧的 133」：进场 / 换场景 / 瞬移后的人速度恒为零 |
| Q5 | 瞬移的可见性：全部重置（基线）还是只改变化的部分？ | **索引里全部重置、同步完成；下行上本人一侧只发差集，旁人一侧销毁 → 创建**（X6）。旁人一侧改发 130 的变体不采纳（N1） |
| Q6 | 瞬移原语要不要自己拒冻结 / 战斗在途？ | **要**（X7）；结果用枚举返回，调用方按需计数 |
| Q7 | 2.10 怎么端到端验证瞬移（生产里还没有调用方）？ | **加 dev 管理口**（§5.9，X9），robot `movement` 加瞬移段；本机跑不了 robot，管理口这条路径在本批的证据来自 §11.4b 的单测 |
| Q8 | 130 的 `input_seq` 填什么？ | **不设（0）**，同基线。客户端只记日志（§2.3）；要填「最近一条被接受的输入序号」得在 `ScenePlayer` 上多记一个字段，等客户端真做回滚再说 |
| Q9 | 已经静止的人被「再停一次」要不要发 133？ | **不发**。`haltMotion` 只在由动变停时发；③⑤⑥ 在动才调，④ 无条件调但静止时只置 66 脏位（66 的行为不变）。依据 I1 |
| Q10 | 落位等待的半径 / 超时做成配置吗？ | **不做**，常量同基线（§6.2） |
| Q11 | 离场 / 被接管 / 停服前要不要像基线一样先发一条「停了」？ | **不发**（X8）；51 随即销毁，停服则链路已断 |
| Q12 | `ViewCrowdBenchmarkTest` 的口径怎么改？ | **只做记录**：A 段加一行 `133 flush`，人群行为不变（每人每 250 ms 一条 132 → 每帧约 1/5 的人兑现）；同一台机器上在不含本批的提交与本批各跑 ≥ 3 次（§11.5）。不在生产代码里加只为基准服务的开关；「66 关闭」的对照用基准里现成的「整帧 − 66 段」估算。结果写回 `architecture.md`；超过 50 ms 只在那段注明，不触发任何设计变更 |
| Q13 | 2.10 要不要同时改基线（两版纪律）？ | **不需要**：基线已经有 133 / 瞬移（本次未重跑）；Java 的差异 X2–X5 登记「mmorpg 可选」。建议 mmorpg 另批补两处客户端可见的修正：AFK / 进战 / 交接冻结时清速并发 133（B-2）、新观察者的初始状态（B-4） |

---

## 11 测试计划与 robot

### 11.0 证据分级

| 级别 | 内容 | 本机能否取得 |
|---|---|---|
| 单测 | §11.2–§11.4b（game-scene、game-robot 的纯函数与假链路用例） | 能 |
| 基准 | §11.5（`-Dgame.bench=true`） | 能 |
| robot 端到端 | §11.6（`movement` 的行走 / 重登 / 瞬移 / 跳跃四段，要连本机切片） | **不能**：本机没有 Redis / MySQL / Kafka / Nacos，切片起不来 |
| 真客户端 | §11.8（两台 Unity 客户端） | **不能**：本机没有 Unity |

后两级标「待在有中间件 / Unity 的机器上跑」。PARITY 与提交说明如实写「robot `movement`、双 Unity 客户端验收未运行」，两行状态写「Java 进行中」（§5.13）。
因此 robot 的判定逻辑尽量落成 `MoveAssertions` 里的**纯函数**并由 `MoveAssertionsTest` 覆盖（135 展开、133 与输入配对、瞬移判定），场景类只负责收发——这是本机唯一能跑的 robot 证据，
与仓库里「场景中不连服务端就能钉住的部分做成纯函数单测」的惯例一致（`CrossNodeScenarioTest` / `TeamScenarioTest`）。

### 11.1 基线测试对照

| 基线用例 | 性质 | Java 对应 |
|---|---|---|
| `aoi_system_test.cpp:303` `ResetEntityVisibilityRebuildsViewAfterSameHexTeleport` | 同格、**原先互不可见**（相距 14，`:313-314`）→ 瞬移到脚下后双向建立（`:322-323`） | §11.3 V1 里 C 的那一半（只创建、没有 64）；Java 没有「同格不重判」，不需要单独的同格用例 |
| `:328` `ResetEntityVisibilityDropsOldNeighbours` | 远距离瞬移摘掉旧邻居 | §11.3 V1 |
| `:358` `ResetEntityVisibilityKeepsPinnedEntriesOnBothSides` | 钉住的条目不动 | 不适用（Java 没有钉住） |
| `:379` `ResetEntityVisibilityBeforeFirstUpdateIsHarmless` | 首次 Update 之前调用无害 | 不适用（Java 进场同步评估）；改为 §11.3 V6「刚进场就瞬移」 |
| `:1228` / `:1235` / `:1244` `TeleportReportVerdictTest` | 判定纯函数：远且未到期 → `kStale`（`:1232`）；恰好 3.0 m → `kSettled`（`:1238`，到期前后都是）；远且恰好到期 → `kExpired`（`:1246`） | §11.4 S1–S3 逐条照写（三个边界基线已含）；另加 Java 自己的差值比较用例 |
| `:1204` / `:1211` / `:1218` `TeamRegroupRuleTest` | 归队判定 | 4.3b |
| 无 | 133 的内容、收件人、调用点；`TeleportWithinScene` 五步；`ShouldDropReportAfterTeleport` 的换场景作废分支；瞬移的任何下行（基线四条 Reset 用例只断言兴趣表与格子，不断言 64 / 47） | §11.2；§11.3 V1 / V2（交集）；§11.4 S7 |

**本次未复跑以上基线用例**（§1.9），对照只用来保证规则覆盖，不当作基线行为的证据。

### 11.2 133 / 135 与 21 / 47（新类 `MovementBroadcastTest`；`MovementSyncTest` 风格：假时钟、假链路、`steps(n)` 驱动帧，夹具用抽出来的 `MovementFixture`）

夹具事实：`ManualClock` 在 `world.step()` 期间不走；`WorldTestAccess.startFreezing` 只挂冻结状态、不经 `haltForFreeze`，验证冻结停步要用 `TransferFixture` / `TravelFixture` 的 `freeze(player)`；
battle 包读旁观者下行用 `BattleFixture.pushes(watcher, IDS.notifyActorMove())`；带朝向进场用 `repo.put(new PlayerData(…, PlayerState.newBuilder().setFacing(…).build()))`（`PeriodicSaveTest` 里现成的写法）。

- **T1 内容**：A 发 134 后、`step()` 之前，B 没有收到任何东西（输入不当场发）；把假时钟推进 30 ms 再 `step()`，B 收到恰好一条 133：信封 `message_id = 133, id = 0`；
  `entity` = A 的 21 里的 `entity`（≠ player_id）；`transform.location` = 裁决位置（逐位相等，外推之前）；`rotation` = 上报值；`velocity` = 截断后的值（30 m/s → 10）；`move_state = 1`；
  `server_time_ms` = 推进后的墙钟（发送时刻，X2）。131 → 速度存在且全零（字节 `1a 00`）、`move_state` 缺席。A 自己收不到任何 133。
- **T2 截断**：超出位移额度的上报：A 收 137（当场），B 收到的 133 位置 = 截断点。
- **T3 合并**：同一帧内 134 + 132 + 131 → 下一帧开头只有一条 133，内容是 131 之后的状态；跨帧的三条 → 三条。
- **T4 外推不发**：A 发 134（速度非零）后不再发输入，连跑 10 帧：B 恰好收到 1 条 133；期间 66 的 transform 照发；`deliveries{kind=broadcast}` 只 +1。
  （T3 的「跨帧三条 → 三条」在「`integrate` 误挂标记」的错误实现下仍然通过，不能当作不多发的证据；这一条才是。）
- **T5 不产生 133 的输入**：非有限 / 越界（`INVALID`）、冻结中（DROP）、战斗在途的 134 / 132（DROP）；战斗在途、**静止**时的 131（STOP_ONLY 不停步）。
- **T6 停步点逐条**（每条断言「停步的那个调用返回时 / 那一帧的 `step()` 内旁人已收到速度为零的 133，下一帧开头不再重复，之后静默」）：
  - 战斗在途、在动时的 131（`InBattleGateMatrixTest` `:593` 的现有用例：`WorldTestAccess.setVelocity` 之后发 131 → 旁观者恰好一条 133；第二条 131 时速度已为零 → 没有 133）；
  - 备战（`PrepareBattleTest` `:858` 的现有用例）：**在动 → 当场一条；静止 → 没有 133、66 速度脏位照置**（两条各测）；顺带断言**备战中的旁观者 W 照收别人的 133**（不过滤，§5.4）；
  - `attachFreeze`（迟到确认 `ConfirmBattleTest` `:530`、进场恢复 `BattleRecoveryTest` `:406`）；
  - `haltForFreeze`（`HandOffTransferTest`、`ZoneTravelHandOffTest`）：`f.freeze(a)` 返回时 b 已收到 133；之后交出提交 → 51；
  - 挂机 600 帧：停推那一帧的 `step()` 内收到 133，之后不再有。**观察者要在 600 帧外推之后仍在 20 m 内**（用低速，如 `MovementSyncTest` `:299` 的 0.1 m/s、30 s 走 3 m），否则观察者早已收到 64、断言不到这条 133；
  - 输入挂了待发、下一帧之前被停下：134 被接受（未 `step`）→ `freeze`：旁人恰好收到一条 133（速度零、位置 = 裁决位置），下一帧开头不再有；
  - 静止且挂着待发（131 刚被接受）→ 备战：当场没有 133，下一帧开头一条（速度零）。
- **T7 不发**：离场、被接管、交出提交——旁人只收 51，之后没有该实体的 133；**夹具要求「有待发时离场」**（输入被接受之后、下一帧之前离场 / 被接管），否则「之后没有 133」是空断言。
  停服：**没有任何下行**（没有 133，也没有 51）。同节点换场景：旧场景的人只收 51；新场景的观察者只收 21（位置 = 新位置、带朝向），**没有 133**；
  换场景前挂着的待发被清掉（下一帧开头新旧场景都没有它的 133）。
- **T8 顺序（C3）**：B 本帧走出 A 的视野：B 收 A 的 133（若有）在 64 之前，64 之后不再有 A 的 133；C 本帧走进在动的 A：C 先收 47 再收 135，135 在本帧的 64 之前；进场：旁人只收 21。
- **T9 没人看得见**：A 在没人的地方走，帧开头标记被清掉（`WorldTestAccess` 读标记）；之后 B 走近，B 只收到 47（A 在动则再加 135，内容是当前状态），不收到积压的旧 133。
- **T10 新观察者的初始状态**：
  - 21 / 47 带朝向：上报过朝向的静止目标 → 观察者的 47 里该项 `transform.rotation` = 上报值，**没有 135**；存档里有朝向的玩家进场 → 本人的 21 与旁人收到的 21 都带；
    从没上报过朝向的新号 → 不带（`SceneWorldTest.java:112` 的现有断言就是这半句）。
  - 135：进场者看见在动的目标 → 47 后一条 135，**只含在动的目标**，条目内容 = 当前状态；看见的全是静止目标 → 没有 135；帧内走进视野同；只有一个在动的目标也是 135。
  - 进场者的观察者不收 133（进场者速度为零）。
- **T11 补发不清待发**：A 在动且挂着待发 → C 进场收 47 + 135{A} → 下一帧开头老观察者 B 仍收到 A 的 133（C 也再收一条同状态的，R9）。
- **T12 改名**：`MovementSyncTest` `:621` 改名为「从不发 65 / 55 / 82 / 68 / 75」，`never` 集合去掉 133 / 135 / 130。
- **T13 现有用例的期望序列**（下表；按本稿的设计对着现有断言逐条推出来的，没有运行证据）。
- **T14 指标**：`deliveries{broadcast}` / `{catch_up}` 的增量（T1、T10 里顺带）；`broadcast{kind=actor_move}` 三帧计 3 次（加在 `SceneWorldTest`「指标_每帧记一次帧耗时…」用例里，`:579`）；
  `SceneMetricsTest` 加一条「移动广播与瞬移指标_启动即注册_导出名与标签」：`game_scene_broadcast_seconds_count{kind="actor_move"}`、`game_scene_moves_total{result="teleport_stale"}`、
  `game_scene_actor_move_deliveries_total{kind=…}`、`game_scene_teleports_total{reason=…,result=…}`（2 × 5 全部为 0）、`game_scene_teleport_settles_total{result=…}`、`game_scene_admin_requests_total` 的 `op="player_teleport"`。

**T13：现有断言 → 2.10 之后的期望**（`MovementSyncTest.java`，行号是断言所在行；link 11 = 玩家 1001，link 12 = 1002）：

| 用例（起始行） | 现在的断言 | 2.10 之后 | 推演 |
|---|---|---|---|
| `:82`（`:96`）、`:140`（`:148`）、`:152`（`:161`、`:167`）、`:183`（`:199`）、`:203`（`:216`） | `sink.events()` 为空 / 只有一条 137 | **不变** | 输入与断言之间没有 `step()`，输入触发的 133 只在帧开头兑现——这些用例本身就是「输入不当场发」的回归证据，实现若改成当场发它们会红 |
| `:299` 挂机 | `lastSyncTo(12)`（按 66 过滤） | **不变**；在本用例或 T6 里加「第 600 帧的 `step()` 内 1002 收到速度为零的 133」 | — |
| `:339` | `syncsTo(12)`（按 66 过滤） | **不变** | — |
| `:376` 停步的 66 字节 | `:386` `singleElement()` 且是 66；`:390` `toB.getFirst()` 的字节 | `containsExactly(133, 66)`；字节断言取 `toB.get(1)` | `moveStop` 被接受挂待发 → 第 0 帧开头 133（速度零；rotation 是默认实例，存在且为空）→ 同帧末尾 66 |
| `:397` 积压 | `:407` `containsExactly(79, 21, 47, 66)`；`:408` `toB.get(3)` | **不变** | 1001 停着：第 0 帧兑现时没人看得见，标记丢掉；1002 进场时 1001 静止 → 47 之后没有 135。内容上 47 里 1001 那一项多了 `rotation = FACING`（加一条断言） |
| `:416` 新观察者（进场路径） | `:427` `containsExactly(79, 21, 47, 66)`；`:428` `toB.get(3)` | `containsExactly(79, 21, 47, 135, 66)`；66 取 `toB.get(4)` | 1001 在动 → 47 之后 135{1001}（1003 静止，不进 135）；加断言：135 的条目 `rotation = FACING`、速度 (1, 0, 0) |
| 同上（帧内路径） | `:435` `containsExactly(79, 21)`；`:439` `containsExactly(79, 21, 47, 66)`；`:440` `toD.get(2)`；`:442` `toD.get(3)` | `:435` 不变；`:439` → `(79, 21, 47, 135, 66)`；47 仍取 `get(2)`，66 取 `toD.get(4)` | 1004 的 `moveStop` 挂的待发在第 5 帧开头没人看得见、丢掉；同帧刷新视野：47 → 135{1001} |
| `:451` 走近走远 | `:459` link 11 `containsExactly(47, 66)`；`:460` link 12 `containsExactly(47)` | **不变** | 1002 走近后双方都静止，没有 135；1002 的待发在第 0 帧开头没人看得见 |
| 同上（走远） | `:469` link 11 `containsExactly(47, 66, 64)`；`:471` `get(2)` 当 64 解析；`:470`、`:473` link 12 | link 11 → `(47, 66, 133, 64)`，64 取 `get(3)`；link 12 不变 | 第二条 `moveSync` 挂待发，第 1 帧开头兑现时 1001 仍在 1002 的观察者里（视野还没刷新）→ 先 133 后 64（正是 T8 的顺序） |
| 同上（离场后） | `:477` `containsExactly(47, 66, 64)` | `(47, 66, 133, 64)` | 同上 |
| `:481` 移动中走近 | `:489`、`:490` 为空；`:495` `startsWith(47)`；`:496` `containsExactly(47)` | **不变** | 1002 的待发兑现时没人看得见；停下那一帧双方都静止，没有 135 |
| `:500` 滞回带 | `:511` link 11 `containsOnly(66)`；`:512` link 12 为空 | `containsOnly(133, 66)`；`:512` 不变 | 每轮 `moveStop` 之后的下一帧开头 1001 收一条 133 |
| `:518` 离场 | `:537`、`:545` link 12 `containsExactly(51)` | **不变** | 离场不发 133；1001 的最后一条 133 在 `sink.clear()` 之前 |
| `:551`、`:582`、`:599` | 没有涉及旁人下行序列的断言 | **不变** | — |
| `:621` | 用例名与 `never` 集合 | 见 T12 | — |

另外两处（`HandOffTransferTest.java:51-66`、`ZoneTravelHandOffTest.java:56-70`，同构）：`:64` / `:70` 的 `containsExactly(66)` → `containsExactly(133, 66)`；
其中 133 在 `f.freeze(a)` 返回时就已经发出（在两次 `step()` 之前加一条断言），66 在随后的同步帧；用例名里的「旁人下一同步帧收到停了的 66」改为「旁人当场收到停了的 133、下一同步帧收到 66」。

不受影响（核对过夹具）：`SceneWorldTest`（`:134` / `:249` / `:254` 的目标没有朝向也不在动；`:575` 断言的是移动者本人的链路）、`ClientRequestHandlerTest` / `SkillFeatureTest` / `ChannelDrainTest` /
`RemoteSwitchTest` / `MirrorSceneTest`（没有人在动）、`FreezeGateTest` / `PlayerCallTest`（只看本人链路）、battle 包（旁观者下行都经 `f.pushes(watcher, 66)` 按消息号过滤）。
**因 21 / 47 带朝向而失败的现有断言：没有**——全仓对 21 / 47 的 rotation 做断言的只有 `SceneWorldTest.java:112`（新号 `hasRotation()` 为 false，仍成立）。
21 / 47 内容的回归面（都只读 `entity` / `guid` / `location`，grep `ActorCreateS2C|ActorListCreateS2C` 得到）：game-scene 的 `SceneWorldTest`（`:103-113`、`:135-141`、`:145`、`:157`、`:173`、`:256` 与 `assertLocation` `:748`）、
`ChannelDrainTest`（`:119-129`、`:171`）、`ClientRequestHandlerTest`（`:129`、`:155`）、`HandOffTransferTest`（`:534-537`）、`MirrorSceneTest`（`:86`、`:114-116`）、`MovementSyncTest`（`:440`、`:461-464`）；
game-robot 的 `PlayerFlow.awaitSelfActor`（`:295-313`）、`CrossNodeScenario.findActor`（`:654`）与各场景对它的调用（`SceneMoves`、`MovementScenario`、`ReconnectScenario`、`TravelScenario`、`BattleSettleScenario`、
`DungeonScenario`、`MirrorScenario`、`SkillScenario`），测试替身 `CrossNodeScenarioTest`（`:46-47`）与 `FakeTravelWorld`（`:463-465`，自己构造 21）。新增的朝向断言放 T10 与 §11.6 重登段。
以上「不变 / 不受影响」都是推演，**实现时以运行结果为准**：落笔前在驱动过移动的测试类（grep `applyMove(|moveStart(|moveSync(|moveStop(|setVelocity(`）里查 `messageIdsTo(` / `sink.to(` 后接
`containsExactly|containsOnly|singleElement|hasSize` 的断言，逐条对一遍，改动的断言逐个列在提交说明里。

### 11.3 瞬移（新类 `TeleportWithinSceneTest`；`ViewIndexTest` 加 `reenter` 的用例）

- **V1 远距离**：A、B 相邻且互相可见，C 在 50 m 外（静止、有朝向）；把 A 瞬移到 C 身边（≤ 10 m，`facing = null`）：A 依次收 130（信封 `id = 0`、`entity` = A 的实体号、`location` = 落点、
  `rotation` = A 原朝向、`reason` = 3、`input_seq` 缺席）→ 64{B} → 47{C}（C 的项带朝向），**没有 135**（C 静止）；B 收 64{A}；C 收 21{A}（位置 = 落点、带 A 的朝向），**没有 133**。
  A 的速度为零、`MoveGuard` 锚点 = 落点、额度满、待发标记已清。**A 收到的 64 / 47 不含 A 自己的实体号，A 不收到自己的 21**（I8）。
  `teleports{result=teleported}` +1；`aoi.changes`：`leave` +2、`enter` +2。C 在动时：A 的 47 之后一条 135{C}，`deliveries{catch_up}` +1。
- **V2 短距离（交集）**：B 离落点 ≤ 10 m、瞬移前后都与 A 互相可见：B 先 64{A} 后 21{A}；**A 只收 130，不收到任何含 B 的 64 / 47 / 135**；`aoi.changes`：`leave` +1、`enter` +1。
  **V2b 滞回带**：B 离落点 15 m 且原先互相可见 → B 只收 64{A}，A 收 130 → 64{B}，都没有 21 / 47。
- **V3 拒绝**：冻结中、战斗在途、落点越界 / 非有限、`facing` 含 NaN、实例已不在 → 不改任何状态、不发任何消息，`teleports{result}` 各计一次。
  帧内调用（用一个在 `step()` 期间回调的假 sink 触发）→ 抛 `IllegalStateException`，状态不变。
- **V4 66**：瞬移后新观察者在下一个同步帧收到 A 的 66（transform + 速度零）；A 新看见的目标照 `markFullStateForNewWatcher` 的现有语义。
- **V5 表满**：放在 `ViewIndexTest`（见下），不在 SceneWorld 层堆 101 人。
- **V6 刚进场就瞬移**、**连续两次瞬移**（第二次以最后一次为准，落位目标被覆盖，`settles{voided}` 计一次）。
- **V7**：RESOLVING / PRECHECK 中（`WorldTestAccess.startResolving`）照常瞬移。
- **V8 朝向三种情形**：`facing = null` 且有朝向 → 130 的 `rotation` = 原朝向、朝向不变；`facing` 非 null → 覆盖，130 与新观察者的 21 都带新朝向；`facing = null` 且从没上报过朝向 → 130 的 `transform` 不带 `rotation`。
- **V9 `mutuallyVisible`**：同场景双向可见 → true；单向（表满）→ false；不同场景 → false。`ScenePlayer#rotation()` 从别的包可见。
- **V10 清待发**：A 的输入被接受（挂待发）→ 瞬移 → 下一帧开头没有 A 的 133。**V11**：瞬移后 A 新看见在动且挂着待发的 T → A 收 135{T}，下一帧开头再收一条 T 的 133（R9）。
- **`ViewIndexTest`（`reenter`，与 `ScenePlayer` 同包，直接测生产代码路径）**：
  - 先 `view.moved(a)` 让 a 进待刷新名单，`a.setPosition(target)` 后 `view.reenter(a)`：`pendingMoves()` 为 0；返回的四个集合正确；`watching` / `watchers` 两向一致；
    在落点旁新 `enter` 的第三人看得见 a、在旧位置旁新 `enter` 的看不见（格子正确；现有「离开_返回看得见它的人_双方的表与格子都清干净」`:248` 就是这个写法）；
  - `new ViewIndex(1)`：容量为 1 时 `seen` / `seers` 只含能建立的那部分，两张表仍互为镜像（V5）；
  - 离落点 10–20 m 的旧关系 `reenter` 之后不重建。
- **`Scene#teleport` 的校验**（放 `TeleportWithinSceneTest`，`new Scene(…)` 直接建场景）：非有限坐标抛 `IllegalArgumentException`、不属于本场景的玩家抛 `IllegalStateException`，位置、两张表、格子都没变。

### 11.4 落位等待（**新类** `MovementRulesTest` 纯函数 + `TeleportWithinSceneTest`）

- **S1–S3**（纯函数，照基线三条）：远（> 3 m）且未到期 → `STALE`；近（恰好 3.0 m）→ `SETTLED`（到期前后都是）；远且 `now == deadline` → `EXPIRED`。另加：`now = deadline − 1` → `STALE`；
  差值比较——`deadline` 回绕成负数（`now` 接近 `Long.MAX_VALUE`）时到期前仍判 `STALE`、到期后判 `EXPIRED`。
- **S4**：瞬移后 3 s 内从旧位置来的 132（旧位置距落点 10 m 与 50 m 各一条）：按 I5 的清单断言——位置 / 朝向 / 速度 / 66 脏位 / 待发标记 / `MoveGuard` 锚点与额度都不变，
  B（新位置的观察者）不收 133，A 不收 137，`moves{teleport_stale}` +1，活跃帧照旧刷新；131 同样丢弃。用例注释写明：没有落位等待时这两条上报的下场就是 S6a / S6b，这是落位等待存在的理由。
- **S5**：落点 2 m 处的 134 → 照常裁决、等待解除、`settles{settled}` +1；之后远处的上报按位移校验处理（锚点已在落点）。
- **S6a**：3 s 后、旧位置距落点 **10 m** 的上报 → 等待解除、**整条接受、没有 137**（位置回到旧位置 = 瞬移被静默撤销）、WARN 一行、`settles{expired}` +1。
  **S6b**：3 s 后、距落点 **50 m** → 截断到距落点 24 m 并回 137、`moves{corrected}` +1、`settles{expired}` +1。
- **S7**：瞬移后换场景 → 等待作废（`settles{voided}` +1），新场景里的上报照常；非有限输入仍计 `INVALID`（不经落位判定）。

### 11.4b 管理口

本机跑不了 robot，管理口这条路径在本批的证据全部来自这里：

- **`SceneAdminControllerTest`**（现有类，`FakeAdmin` 旁加 `PlayerAdmin` 的假实现）：prod → 403 且**先于**解析请求体；请求体超长 / 解不开 → 400；正常 → 200 + `TeleportPlayerResponse`（tip 原样透传）；
  逻辑线程超时 → future 被 `cancel`、回 503；撤不掉（结果恰好已定）→ 照实返回；future 异常完成 → 503；审计行内容（operator、player_id、坐标、tip）。
- **`SceneAdminAuthFilterTest`**：`opOf(TELEPORT_PATH)` = `player_teleport`；401 / 503 走过滤器。
- **`SceneWorld#adminTeleport`**（放 `TeleportWithinSceneTest`）：结果 → tip 的映射逐条——`TELEPORTED` 0、`player_id = 0` 与越界各 → 1005、找不到人 → 1004、`FROZEN` 与 `IN_BATTLE` 各 → 1006；
  `reason = ADMIN`（130 的 `reason` = 3）；朝向保持；找人阶段的拒绝也计 `teleports{reason=admin, …}`。
- **放弃检查**：结果已被 `cancel` 时逻辑任务不瞬移、不计 `teleports`（`SceneNode#teleportPlayer` 的任务体；测试落点由实现定，能在不起节点的情况下调到即可）。
- **`SceneAdminClientTest`**（robot）：`TeleportPlayerRequest / Response` 的手工编解码对照 game-api 生成类逐字节相等，含 0（不写出）、−0.0（原始位非零，要写出）与负坐标。
- **`SceneMetricsTest`**：见 T14。

### 11.5 基准（`ViewCrowdBenchmarkTest`，`-Dgame.bench=true`；**只做记录**）

- 人群不变（1000 / 2000 人在出生点 15 m 内、1–9 m/s、每人每 5 帧一条 132，错开到不同帧 → 每帧约 1/5 的人兑现）。
- 输出：A 段加一行 `133 flush`（取 `game.scene.broadcast{kind=actor_move}` 计时器的逐帧增量，与 `47/64 emit`、`66 fan-out` 并列；标签保持 ASCII，类注释的约定），
  `integrate+refresh (step - broadcasts)` 的算式（`:131`）同时减去它；另打印 `deliveries` 两个取值的每秒对数，以及「66 关闭」的估算行 = 逐帧 `frameNanos − syncNanos`（基准里现成的量）。
- 做法：同一台机器上，在**不含本批的提交（main）**与**本批**各跑 ≥ 3 次，记录整帧均值 / p90 / p99、`view_changes` / `attribute_sync` / `actor_move` 三段的均值、`deliveries` 每秒对数。
  p99 取的是 200 帧里的第 2 大样本（`MEASURE_FRAMES = 200`），一次 GC 就能改变它，所以只和同机同次的对照比，不和 `architecture.md` 里 2026-09-30 的旧数比。
- 读数注意：`emitViewChanges` 里的 135 落在 `view_changes` 段（基准里人人在动，这一段的构造与序列化量约翻倍），不在 `actor_move` 段。
- **不在生产代码里加只为基准服务的开关**（没有「133 开 / 关」「66 开 / 关」的真实对照）。
- 结果写回 `architecture.md` 的「scene 场景帧」一段。**超过 50 ms 只在那段注明**（那段本来就写着极端密度要分散出生点），不触发任何设计变更，也不改变 66 的去留（Q1）。

### 11.6 Java robot（端到端，待在有中间件的机器上跑；判定逻辑的纯函数部分本机有单测）

**前提**（写进 `game-robot/README.md` 与选项帮助）：`movement` 只在**单节点切片**上跑（`architecture.md:1738`；缺省 `--scene-admin-url` 的 18104 就是切片第一个 scene 节点的管理端口）；
瞬移段需要**运维令牌**（`GAME_ADMIN_TOKEN`，或从仓库根目录运行时读 `run/game-admin-token`）——`movement` 是 robot 的缺省场景，今天不需要令牌，2.10 之后需要；`--expect-dev allow | deny` 对 movement 生效。
`SceneAdminClient` 的构造不校验令牌（调用时才报「没有运维令牌」），所以 `RobotMainTest.java:99` 那条在没有 `GAME_ADMIN_TOKEN` 的环境里跑 `movement`（连不上网关即失败）的用例照旧成立。

- **`MessageIds`** 加 `notifyActorMove` / `notifyActorMoveList` / `notifyTeleport`（`MessageIdsTest` 同步）；**`MoveAssertions`** 加 133 / 135 / 130 的解析、按实体号归属、
  「135 展开成若干条 133」的统一视图、133 与输入的配对、瞬移帧序列的判定——都是纯函数，`MoveAssertionsTest` 覆盖。
- **`movement` 模式**（`MovementScenario`），段落顺序：行走 → 重登 → 瞬移 → 跳跃。以 133 为主断言，66 原有断言保留为旁证：
  1. **行走**：B 在观察时限内收到 A 的 133——`entity` = A 的 21 里的实体号，位置 = 上报位置（robot 的输入在位移额度内，裁决 = 上报；只对配上的那条输入逐位比较），`rotation` = FACING，
     `velocity` = 期望值（超速那条截到 10），`move_state` 1；Stop 那条速度全零、`move_state` 0。**配对复用 `matchInputs` 的合并容忍规则**：一条输入若被之后（在该 133 到达前已发出）的输入合并，
     算由那条 133 满足（两条输入因 TCP 攒包 / GC 落进同一个 50 ms 帧时前一条没有自己的 133）。**行走段内 B 收到的 A 的 133 条数 ≤ A 已发的输入条数**（外推若误发会是约 5 倍，`≤` 足以抓住）。
     `server_time_ms` 沿用 `MoveAssertions.SERVER_TIME_TOLERANCE_MS`（与 137 同一口径，只防单位 / 时钟源错误，不做时钟同步校验）。A 收不到自己的 133。停下后 1 s 静默窗口内没有 A 的 133。
  2. **重登（新观察者的初始状态）**：A 断开重登，B 收到 A 新实体的 21，**`transform.rotation` = FACING**（`player_state.facing` 恢复，X3）；A 自己的 21 同样带 FACING；
     1 s 内 B 没有收到该实体的 133 / 135（A 静止）。
  3. **瞬移段**（经管理口）：
     - `--expect-dev allow`（缺省）跑完整段；`deny`（prod 进程）**只核对管理口回 403 并跳过瞬移段余下的步骤**（同 `DungeonScenario`），行走 / 重登 / 跳跃照跑。报告里写明这一段跑没跑。
     - 两种模式都需要运维令牌；没有令牌 → 该段判失败并给出与 `DungeonScenario` 相同的提示（`SceneAdminClient` 的「没有运维令牌：设环境变量 GAME_ADMIN_TOKEN…」），余下步骤跳过。
       连不上 / 503 → 该段判失败。管理口回 **1004** → 判失败并提示「`--scene-admin-url` 没指向 A 所在的节点；movement 请在单 scene 节点切片上跑」。
     - 步骤：A、B 相邻 → 管理口把 A 挪到当前位置沿行走方向 +50 m：A 收 130（`entity` = A 的实体号、`location` = 目标、`rotation` = FACING、`reason` = 3）、A 收含 B 的 64、B 收含 A 的 64。
       **robot 收到本人的 130 后把本地位置改成 130 的 `location`**（同真客户端），之后的上报从该点出发。
       **旧位置的 132 在收到 130 后立即发出**（模拟在途的旧上报；A / B 两条 64 的断言用发送前记下的 mark 回看，`connection.await(mark, …)`）→ A 不收 137——这是落位等待的端到端证据
       （没有落位等待时这条 50 m 外的上报会被截断到距落点 24 m 并当场回 137）。再从目标 +1 m 发 131 → A 不收 137（只验「落位后照常裁决」；它在没有落位等待时会不会被纠偏取决于两条上报的间隔，不作为证据）。
     - 再把 A 挪回 B 身边：A 收 130 与含 B 的 47，B 收 A 的 21（位置 = 落点、`rotation` = FACING），**没有随后的 133**；之后 A **从第二次 130 的 `location` 出发**走一步
       （134 起点 = 该 location，250 ms 后一条 131；都带 FACING）：B 收到这两条输入对应的 133（配对规则同行走段）。
  4. **跳跃**：保留，排在瞬移段之后。起跳点：瞬移段跑完时 = 上一步那条 131 的位置；瞬移段被跳过 / 失败时 = 重登时自己的 21 位置（现状）。
- **可选的挂机探针 `--afk-probe`**（缺省关，开了整轮多 30 多秒）：A 以 0.3 m/s 发一条 134 后保持连接、不再发任何消息；B 只在起步时收到一条 133，约 30 s（600 帧）时收到速度为零的 133，
  期间 A 没有离开 B 的视野（30 s 走 9 m）。它同时是 §11.8 第 4 步「低速 robot 充当 A」的工具。
- **其它场景**：`CrossNode`、`Mirror`、`BattleSettle`、`SceneMoves` 的 66 断言不动（66 不变）。`BattleSettle` 第 3 步若要加 133 旁证，必须先让 A 在动（静止的人备战没有 133）。
- 4.3b 在 `TeamScenario` 加「入队后 B 出现在 A 的场景并收到 130 reason 5」，复用本批的工具。

### 11.7 跨实现（Go robot / 基线）

Go robot 的 133 / 135 / 130 处理器是空函数（§1.9），**不能做任何断言**；拿它连 Java 只能证明「多出来的 133 / 135 / 130 与 21 / 47 里多出来的 rotation 不让它出错」（处理器已注册、解析后丢弃）。
本次未运行 C++ 或跨实现对拍；历史构建记录不作为本批证据。跨实现的一致性只按客户端可见形状核对：§2.6 的 C1–C9 两版都满足（基线 C4 在 AFK / 进战 / 交接冻结三处不满足，B-2；C7 基线不补，B-4）。

### 11.8 真客户端验收（两台 Unity 客户端 `9ccb99c51` 连 Java 切片，需要在有 Unity 的机器上手工跑）

1. A点地走、转向、停下：B屏幕上A跟着走并停在停止点（此前远端A不动）。轻微速度起伏是X2的到达抖动，不算缺陷；图元碰撞体已禁用（R14）。
2. A静止，B随后登录：A位置正确。朝向只在编辑器检查根transform的 `localEulerAngles`，不以精灵八方向作判据。
   A从远处走进B视野：21/47创建后收到135，下一次Tick即开始按速度推进，不要求先停0.15s；位置误差仍用0.15s收敛。
3. A走着发起跨节点换图：B看到A在冻结时及时停下、随后消失；A自己的旧场景实体在79前最多按最后速度推进约1s，再停等清场（R13）。
4. 挂机停推与断线分开验：
   - **4a 挂机停推**：Java robot作为A运行 `movement --afk-probe`（§11.6），0.3m/s起步后静默。B约1s会先触发客户端外推上限；
     **第30s服务器的零速133必须用robot断言/收包记录验证**，不能把B提前停下当成服务端停推成功。该133也会把B显示的位置收敛到服务器最终位置。
     不用真客户端断网验证第30s停推：9m/s的服务器积分约2s即超过20m出视野半径，观察者会先收到64。
   - **4b 断线**：关闭A进程（FIN）→ B的A经51消失；静默断网（无FIN）→ B的A先在最后样本1s后停外推，随后服务器积分出视野的64让它消失。
     何时判定离线不作要求（gate无空闲检测，R12）；这一步不覆盖挂机停推。
5. 管理口瞬移A：落点选客户端地图内的可走点，与其他角色隔1m便于观察；双节点切片的管理口必须指向A所在节点。
   A吸附后继续WASD不被拉回；点地寻路则从落点继续朝原目的地走，预期日志无137的 `[move] reconcile`，不以“没往回走”为判据。
   旧位置B上的A消失、新位置C上的A出现；A屏幕上前后都看得见的人不闪。
6. A日志有 `[move] teleport entity=<A> reason=3`，无 `[unhandled] message_id=133 / 135 / 130`；66的unhandled仍存在（Q1）。
7. 用robot造20个以上移动者围着真客户端，观察帧率与66日志增长；只作为两版同步评估停发移动66的输入，不改变2.10设计。
### 11.9 回归面与构建

- 在 worktree 里 `mvn -o -B -fae clean verify`（**不用 `install`**：多个会话共用 `~/.m2`，`install` 会互相覆盖 SNAPSHOT）；接回 main 之后在 main 上再跑一次全量。
  当前基线：main `d942f74` 上 9892 条用例 0 失败（其中 game-match 的 `DevActivityBattleControllerTest` 在机器高负载时会因 10 s HTTP 超时报错，单独重跑通过，与本批无关）。
- 重点模块 game-scene、game-robot、game-api。
- 现有断言：**66 的解析内容与时机断言一条不改**（这是「66 不变」的证据）；要改的只有 T13 列出的期望序列（多出 133 / 135）、两个 HandOff 用例与 T12 的改名；视野 / 冻结 / 战斗用例的其余断言不动。
  没有运行证据时不声称「测试通过」。

---

## 12 评审修订记录（2026-10-13）

**复核方式**：七个视角（基线 133、基线瞬移、客户端、Java 现状、帧内设计、瞬移设计、测试与文档）各自独立对着三个代码库核对初稿；每个视角的意见再由另一个 agent 逐条反驳核验（重新读代码，
给出成立与否与更好的改法）。共 84 条意见，**83 条成立**；另有各视角的补充发现与给实现者的代码事实。主持人在此基础上给出定案 D1–D17，与单条意见冲突时以定案为准。
复核产物在本机 `D:\luyuan\tools\review210\`（不进仓库）。下文意见编号写「视角:编号」（两个视角都用了 `TP-` 前缀，必须带视角名）。

### 12.1 与初稿不同的三处设计（定案 D1–D3）

| 定案 | 初稿 | 现在 | 主要来源 |
|---|---|---|---|
| **D1 停步当场发** | 所有触发点都挂待发标记，一律下一帧开头兑现 | 输入仍然帧开头合并；服务器停步经 `haltMotion` 当场 `broadcastMove`；只有一个发送函数、待发标记只有 `applyMove` 一个写入点；不引入「裁决时刻」字段 | design-frame:DSN-5、DSN-10、DSN-4；java-current:JAVA-10 |
| **D2 21 / 47 带朝向，补发只给在动的目标** | 21 / 47 不动；`hasMotionState()`（有朝向或在动）的目标进 135，进场者再给观察者发一条 133 | `toActorCreate()` 带 rotation；谓词收窄为 `isMoving()`；「目标一侧的 133」整条路径取消；原 N3 从「不建议采纳」转为已采纳（X3） | client:CLI-8、CLI-1；design-frame:DSN-12；tests-docs:TP-5 |
| **D3 无条件保留 66** | Q1 带判据：基准 p99 超 50 ms 就在 2.10 内停发移动 66 | 无条件保留，登记「两版同步评估停发移动 66」；基准只做记录（D4） | design-frame:DSN-2；tests-docs:TP-3；client:CLI-7 |

### 12.2 修订清单（按主题，注明来源）

1. **发送时机与触发点**（D1）：§5.1、§5.3（规则句改成正面枚举、补 ⑫ 逐帧外推不发、`haltMotion` 的准确形状与各调用点条件）、§5.4（帧顺序、不过滤、帧内无重复）、§3.1 补 J0、I1 / I4、R2 / R9 / R10、Q2 / Q9、T4 / T6。
   来源：design-frame:DSN-5、DSN-7、DSN-8、DSN-10；java-current:JAVA-1、JAVA-4、JAVA-10；cpp-133:CPP133-4。
2. **新观察者的初始状态**（D2）：§2.2（历史基线 `f537079` 的朝向不可见）、§2.4（21 / 47 同一读取函数的核对结论与行号）、§2.6 C7、§3.2、§5.2、§5.5、X3、N3 / N9、Q3 / Q4、T7 / T10、§11.6 重登段、§11.8 第 2 步。
   来源：client:CLI-1、CLI-8；design-frame:DSN-12；tests-docs:TP-5。
3. **66 与基准**（D3、D4）：§2.5、§5.6、§8.2 R4、§8.3、Q1 / Q12、§7.2（`view_changes` 含 135）、§11.5。来源：design-frame:DSN-2；tests-docs:TP-3、TP-15；client:CLI-7。
4. **瞬移原语与落位等待**（D5）：`ViewIndex#reenter` / `Scene#teleport` 先校验再动、本人一侧只发差集、10–20 m 只销毁不重建、`facing` 校验与 null 语义、`rotation()` 改 public、`stepping` 守卫、
   差值比较、超时后的真实语义（≤ 24 m 静默撤销）、I5 / I8、C5 / C6 / C9、N1 / N2。落在 §2.3、§2.4、§3.2 第 4 条、§4、§5.7、§5.8、§5.11、§9、V1–V11、S1–S7。
   来源：client:CLI-2、CLI-3、CLI-4；cpp-teleport:CPPTP-2、CPPTP-4、CPPTP-9；design-teleport:TP-5、TP-6、TP-7、TP-8、TP-9、TP-11、TP-14；java-current:JAVA-11。
5. **dev 管理口与指标的落法**（D6、D7）：§5.9 全文（tip 1004、落地清单、超时撤掉、找人阶段也计数、审计与 op 标签）、§7.2（枚举嵌在 `SceneMetrics`）、§5.14、§11.4b、T14。
   来源：design-teleport:TP-1、TP-2、TP-3、TP-12；java-current:JAVA-5、JAVA-6；tests-docs:TP-2、TP-6、TP-10。
6. **测试计划与证据**（D8、D9、D12）：§11.0 证据分级、PARITY 状态写「Java 进行中」、T13（现有断言的新期望，按定案后的设计重推）、§11.1 对照表更正、`reenter` 的测试落点、新类 `MovementRulesTest`、
   补齐的用例（T11、V7–V11、指标）、删掉反事实的 S8。来源：tests-docs:TP-1、TP-4、TP-9、TP-13、TP-16；java-current:JAVA-2；design-frame:DSN-1；cpp-teleport:CPPTP-3。
7. **robot**（D10）：§11.6（前提、`--expect-dev`、1004 提示、收到 130 后更新本地位置、时间戳容差与合并容忍、跳跃段的位置）、§5.14 的 robot 文件清单。
   来源：design-teleport:TP-4；tests-docs:TP-11、TP-12；cpp-teleport:CPPTP-6。
8. **给 4.3b 的说明**（D11）：§1.8（队员一侧守卫、kTeammate 挤出）、§5.12（重开 team-spec D8、三种不可见来源、`leader.frozen()`、点地寻路重新规划）、R11、B-6。
   来源：cpp-teleport:CPPTP-1、CPPTP-5、CPPTP-7；design-teleport:TP-10；client:CLI-9。
9. **基线事实更正**（D13）：交接放行走 `DestroyDeposedPlayer`、从不发 133（§1.3、§4、§5.3 ⑥⑧、B-2、X5 / X8，§0.2 对 delta 条目 ④ 的勘误）；限频器口径（§1.4、§4、B-1）；
   B3 无条件 / Java 在动才发（X10）；Java 停服不发任何下行（§3.1、§4、T7）；「晚到下一个模拟步」。来源：cpp-133:CPP133-1、CPP133-2；design-frame:DSN-6、DSN-9；cpp-teleport:CPPTP-10。
10. **文字与不变量**（D14）：到达抖动的准确表述（§2.2、§5.4、X2、§8.3）、`server_time_ms` 的取值时刻（X2、§9.3）、I1 / I4 / I5 的措辞、`watchers` 的准确表述与无会话收件人（§3.2、I2、§7.2）、
    C6 的地图边界、§2.3 的两处措辞、信封 `id = 0`（C8）。来源：client:CLI-5、CLI-6、CLI-10；design-frame:DSN-3、DSN-4、DSN-11；java-current:JAVA-3。
11. **真客户端验收**（D15）：§11.8 第 2、4、5、7 步，R12。来源：java-current:JAVA-8；client 视角补充发现第 1 条。
12. **文档与注释清单**（D16）：§5.13（aoi.md 的 §0 / §7 / §10、scene.md、README、路线图「建议顺序」与 7.5 待办、`architecture.md` 的指标表与管理口、引用基线行号的注释、`setPosition` 的 javadoc 等）。
    来源：tests-docs:TP-7、TP-8；design-teleport:TP-13；cpp-teleport:CPPTP-8。
13. **工作区、提交与行号**：文首、§5.14 提交注意、§11.9；行号更正——`movement.h:38-49` / `:61-77`，`GameClient.cs:1848-1877` / `:1827-1877`，`TianyongPlayerController.cs:512-519`，
    `SceneWorld.java` 的 `applyMove` `:2403-2435`、`enterScene` `:1490-1512`、`removePlayer` `:2839-2858`、`syncAttributes` `:2535-2548`，`ClientRequestHandler.java:328-336`，`SceneAdminController.java:32-45`；PARITY 改为按行标题引用。
    来源：cpp-133:CPP133-5、CPP133-6；java-current:JAVA-7、JAVA-9；tests-docs:TP-14；client:CLI-11、CLI-12；design-frame:DSN-13；design-teleport:TP-15、TP-16。

### 12.3 定案 D1–D17 摘要

D1 输入帧开头合并、停步当场发、单一发送函数、不过滤、外推不发。D2 21 / 47 带朝向，135 只给在动的目标，取消目标一侧的 133。D3 无条件保留 66。D4 基准只做记录、不加基准开关。
D5 `ViewIndex#reenter` + `Scene#teleport`，本人一侧只发差集，旁人一侧销毁 → 创建，前置检查五项，`rotation()` 改 public，`stepping` 守卫，落位判定用差值。D6 管理口落地清单与 tip 映射（`NOT_HERE` → 1004）。
D7 标签枚举嵌在 `SceneMetrics`，130 的 wire 值放 `MovementRules`。D8 PARITY 两行写「Java 进行中」，§11 加证据分级。D9 现有测试的期望序列按定案后的设计重推。D10 robot 的选项、瞬移段与 130 后的本地位置。
D11 给 4.3b 的说明（重开 D8、`leader.frozen()`、不预置表满查询）。D12 worktree 里 `clean verify`、基线 9892 条、提交方式。D13 基线事实更正。D14 文字与不变量。D15 真客户端验收步骤。D16 文档清单。D17 本记录。

### 12.4 未采纳的意见与理由

- **cpp-133:CPP133-3**（唯一不成立的一条：要求把「同格不重判」列为 133 的又一种收不到的缺口）：引用的事实属实，但那是**可见性**缺口而不是 133 收件人过滤的缺口——同格相距 > 10 的两人谁的兴趣表里都没有对方，
  客户端上根本没有这个实体，「收不到 133」是空话；它在 Java 侧的差异已登记在 PARITY「视野（AOI）」行缺口 3。只在 §1.2 末尾加了半句交叉引用。
- 成立的意见里**没有采纳其原始改法**的几处（都按反驳者的更正或定案处理）：
  - design-frame:DSN-4 建议把待发标记改成记裁决时刻的 `long`：不加字段，只登记差异（N8、X2）。
  - client:CLI-6 的缓解方案（133 位置做延迟补偿）：不采纳（N7），接受并写准确。
  - cpp-teleport:CPPTP-1 建议 2.10 预置 `viewFull` / 三态查询：不预置，交给 4.3b（D11）。
  - design-frame:DSN-2 的「133 开 / 关 × 66 开 / 关」四组基准：不做，不在生产代码里加基准开关（D4）。
  - client:CLI-3 的「旁人一侧改发 130」：写成已知可行但不采纳的变体（N1）。
  - tests-docs:TP-13 的更正建议「不加 `ViewIndex#reenter`」：与定案 D5 冲突，按 D5 加，并直接在 `ViewIndexTest` 里测它。
  - 所有按初稿设计推出来的测试期望序列（java-current:JAVA-2、design-frame:DSN-1、tests-docs:TP-1 给的新序列）：D1 / D2 改了设计，T13 重新推过——静止目标不再产生 135（`:397`、`:451`、`:481` 不变），
    停步类 133 是当场发而不是下一帧开头（两个 HandOff 用例）。

### 12.5 修订者自己定的小处（定案没有覆盖，按「最标准、最简单、与仓库现有惯例一致」定）

1. 方法名：`ScenePlayer#isMoving()`、待发标记的 `markMoveBroadcast` / `moveBroadcastPending` / `clearMoveBroadcast`；130 的 wire 常量 `MovementRules.TELEPORT_REASON_ADMIN` / `TELEPORT_REASON_TEAM_REGROUP`，
   `TeleportReason` → wire 值用 `SceneWorld` 里的一个私有 `switch`。
2. 计时口径：`broadcast{kind=actor_move}` 只计帧开头兑现，每帧记一次；停步当场发的 133 不单独计时；`emitViewChanges` 里的 135 计入 `view_changes`，`enterScene` 与瞬移里的不在帧内计时段。
3. 瞬移时 66 的处理：p 自己 `markDirty(DIRTY_TRANSFORM | DIRTY_VELOCITY)`（已覆盖全量状态），`markFullStateForNewWatcher` 只标 p 新看见的目标（`seen ∖ lostWatching`）；`aoi.changes` 按真实发出的条目计。
4. `switchScene` 清掉待发标记（沿用初稿；不清也只是在新场景多发一条内容正确的 133）。
5. 管理口的逻辑线程入口取 `SceneWorld#adminTeleport(playerId, target) → int tip`，tip 映射放在 `SceneWorld`（与 `destroyInstance` 返回 tip 同式）；等超时撤不掉时照实返回结果（同 `abandon`）。
6. S8 删除、S6 拆成 S6a（10 m，静默撤销）/ S6b（50 m，截断 + 137）：把 tests-docs:TP-16（删 S8）与 design-teleport:TP-8（按距离拆、写明夹具）两条更正合在一起。
7. robot：段落顺序「行走 → 重登 → 瞬移 → 跳跃」与跳跃段的起点；没有令牌 / 瞬移段失败时跳跃段照跑；新增可选的 `--afk-probe`（缺省关），作为 D15「用低速 robot 充当 A」的落地方式。
8. 测试支撑：把 `MovementSyncTest` 的私有夹具抽成包内 `MovementFixture`；`WorldTestAccess` 加待发标记与落位等待的访问器。
9. 编号：新增 C8 / C9、I8、X10、R12–R15、N7–N9；N3 留位并标注已采纳，保持评审意见里引用的编号可查；§10 改名「决策记录」。
10. §5.13 顺带列了 PARITY「gate 按消息号限频」行的行文更正（cpp-133 视角补充发现第 3 条：Java 的持续受理上限是基线的 2 倍，不是「边界略宽松」）；只改措辞，是否收紧不在本批。

### 12.6 客户端基线复核（2026-10-26）

实现前逐条检查客户端 `HEAD = origin/main = 9ccb99c51`（工作区干净），并比较 `f537079..origin/main` 的GameClient、ActorWorld、TianyongPlayerController、MessageIds（+452/−154）。
`40e6fdd43` 已进入主干：双端外推、超过1s无样本后清速、转向补132、图元禁碰撞与组队跟随均已落地；更新文首、§0.1、§1.4/§1.9、§2、风险表与§11.8。

66仍没有处理器；133未知实体仍静默丢弃；21/47仍共用读取rotation的创建函数；130本人恢复/重规划与旁人硬吸附语义不变。
推送信封id=0的约束更严格：任意非0id只走应答处理，未匹配请求也不会回落推送；旧HandlerRegistry已删除。
当前外推上限使历史“低速robot静默、肉眼看到约30s后才停”的验收失效，改用robot/收包记录验证第30s零速133。

**无须改变D1/D2实现设计**：服务端仍停步当场发、输入帧开头合并；21/47带朝向，135只补移动目标。上限只能兜底，不替代权威停止。
本次仅有源码复核证据，未运行客户端测试、Unity测试、robot端到端或双Unity联机验收；历史 `f537079` 仅保留为变更对照。

### 12.7 C++ 与 Java 实现前基线复核（2026-10-26）

- C++ `275f9c5c0`：133 的 B1/B2/B3 仍在移动 handler 183/232、212、200，B4 仍在 movement 116；B5 新增角色交易冻结调用（表已修正）。
  BroadcastMove 现为 movement 121–182，TeleportWithinScene 185–228，ShouldDropReportAfterTeleport 230–269；130 与133均取 `actor_net_id::Of`，不是 entt 句柄。
  actor 网络号与玩家 GUID 分离，与 Java 雪花实体号的现有口径一致；消息形状未变，proto 仅补网络号说明与 OneWay/Push 元数据。
- ResetEntityVisibility 现为 aoi 233–298，非钉住关系销毁、下一个模拟步重建的规则未变；interest 129–138 仍为队友高优先级静默挤出。
  RegroupToLeader 现为 player_team 419–479；队长在途守卫434、双Hex454–460、距离464–465、朝向474、瞬移478。
  3 s / 3 m、先距离后时间、换场景作废规则未变；settle 的 scene 改强类型 SceneEntity 属内部实现。
- Java `58e0296b`：契约记录已为 `f83da2e18`（不是任务书较早的190d9f2ea），本批保持受管契约不动。`SceneWorld.applyMove` 现含导航 trace 与 correctAnchor；`integrate` 已有撞墙清速。
  将现存撞墙分支接入 `haltMotion`，属于 D1 / §5.3 ⑦ 的直接适配，不改变导航算法、66脏位或既有裁决。新增导航停步单测。
  `beginFinalExit` 清速紧接退出/停服，`drainForTrade` 经 removePlayer 发51，均按离场不发133处理。旧“只有七处写入”清单以本节复核为准。
- 需要在报告明确的定案边界：瞬移仍仅校验世界范围与有限朝向，保留 D5/D6 不夹持导航；当前已有导航地图上的后续移动会正常走导航，S6a/S6b的24m结论针对无导航夹具。
  dev操作者须选择可走落点。本批不擅自新增瞬移导航规则，4.3b传队长已处于合法地图的位置。
- C++历史构建与AOI记录见文首，本次仅做源码复核。工作方式以任务书为准：独立分支、离线verify、不推送不合并、报告后停。