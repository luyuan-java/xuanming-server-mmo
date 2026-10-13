# 移动广播 133 与同场景瞬移（批次 2.10）移植统一规格：从 mmorpg 基线到 Java 版

> **基线**：mmorpg `73b852f32`（2026-10-10，`D:\luyuan\xuanming-server-mmo-cpp`），契约已由批次 0.3 同步到它（`ef85727`）。本批的基线行为全部来自
> `9199d217d`（「组队跟随 v1.1：入队即跟随、归队瞬移、移动广播」，10-10，经合并 `8e14aa1bc` 进主干）。它之后到 HEAD 只有 `player_lifecycle.cpp`
> 因 CPP-3 改过（`d49739faa`、`dacec28b1`），`StopMotionForExit` 本身没变、只是行号漂移；`movement.{h,cpp}`、`aoi.{h,cpp}`、`teleport_settle_comp.h`、
> `mvh.cpp`、`player_team.{h,cpp}`、`aoi_system_test.cpp` 与 `9199d217d` 逐字相同（`git diff --stat 9199d217d HEAD` 核对）。
> **这批基线 C++ 自标「未编译，待 Codex 验证」，HEAD 上仍然没有编译与运行证据**（`inventory/delta-20261010.md` §1：主干 scene 被装备生成物挡住，
> `aoi_test` 新增的 10 条没跑）。所以下文「基线怎么做」读的是 HEAD 源码的意图，**客户端怎么解读一律以 Unity 客户端的实际处理器为准**（§2），两者冲突时按客户端。
>
> **客户端**：`D:\luyuan\mmorpg-client`，`origin/main` = `f537079`（2026-10-10；本地 main 与之同号，工作区里另有一批删除的美术资源，与本批无关）。
> 基线提交说明与 `team-system.md` V.4 提到的客户端配套（远端插值改为随权威速度外推、1 秒外推上限、转向立即补 MoveSync、角色不做物理障碍、走路跟随）
> 在客户端作者本机 main `40e6fdd4`，**没有推送，不在本克隆里**；本稿凡涉及它都标「转述」。**Java 的设计按 `f537079` 的行为兜底**——它是今天任何人能拿到的客户端。
>
> **Java 侧**：HEAD `3c4945e`（本地领先 origin，未推送）。写作时工作区里有另一个会话未提交的改动（game-team、game-api 的 `MatchBudgets` / `MatchTeamService`
> 及测试、game-match 的 `MatchStartupChecks` 及测试、`docs/porting/{match,team}-spec.md`、PARITY.md 的「组队」行），都不在本批要动的 game-scene / game-robot 里；
> 唯一重叠的文件是 PARITY.md，提交方式见 §5.14。game-scene 在工作区里没有改动，**本稿的 Java 行号按 HEAD**，引用时同时写类名 / 方法名。
>
> **路径怎么读**
> - 以 `cpp/`、`robot/`、`proto/` 开头的路径与 `team-system.md`（= `docs/design/team-system.md`）在 mmorpg 克隆下；不带目录的基线文件：
>   `movement.{h,cpp}` 在 `cpp/libs/services/scene/spatial/system/`，`aoi.{h,cpp}` 同目录；`teleport_settle_comp.h` 在 `cpp/libs/services/scene/spatial/comp/`；
>   `mvh.cpp` = `cpp/nodes/scene/handler/rpc/player/player_movement_handler.cpp`（Java 代码注释里已用这个简称）；`player_lifecycle.cpp`、`player_team.{h,cpp}` 在
>   `cpp/libs/services/scene/player/system/`；`aoi_system_test.cpp` 在 `cpp/tests/aoi_test/`。
> - 以 `Assets/` 开头的路径在客户端克隆下；简称：`GameClient.cs` = `Assets/Scripts/Game/GameClient.cs`，`ActorWorld.cs` = `Assets/Scripts/World/ActorWorld.cs`，
>   `TianyongPlayerController.cs` = `Assets/Scripts/World/Tianyong/TianyongPlayerController.cs`，`MessageIds.cs` = `Assets/Scripts/Net/MessageIds.cs`，
>   `HandlerRegistry.cs` = `Assets/Scripts/Net/Generated/HandlerRegistry.cs`，`WorldCoordinateConverter.cs` = `Assets/Scripts/World/WorldCoordinateConverter.cs`。
> - 以 `game-`、`docs/`、`PARITY.md`、`AGENTS.md` 开头的路径在 `D:\luyuan\xuanming-server-mmo` 下。Java 类：`SceneWorld` / `ScenePlayer` / `Scene` / `ViewIndex` /
>   `MoveGuard` / `MovementRules` / `MoveInput` / `ClientRequestHandler` / `SceneMessageIds` / `SceneClock` 在 `game-scene/src/main/java/com/game/scene/world/`；
>   `SceneMetrics` 在 `.../scene/metrics/`；`PlayerBattleService` 在 `.../scene/battle/`；`TeamFollowService` 在 `.../scene/team/`；`SceneAdminController` 在 `.../scene/admin/`；
>   `MovementScenario` / `MoveAssertions` 在 `game-robot/src/main/java/com/game/robot/scenario/`，robot 的 `MessageIds` 在 `.../robot/client/`。
> - 消息号取自 `game-proto/src/main/resources/contract/message_id.txt`（**号 N 在第 N+1 行**：66 `:67`、130 `:131`、133 `:134`、135 `:136`、137 `:138`）；
>   消息形状取自 `game-proto/src/main/proto/**/player_movement.proto`（同步产物，下称 `player_movement.proto`）。
> - 带「推导」字样的结论没有对应的测试或运行覆盖，是把多处代码路径串起来得出的；带「转述」的没有在代码上复核。
>
> 本稿只读代码，除本文件外没有改任何文件。

---

## 0 概览与范围

### 0.1 结论速览

- **基线做了什么**（`9199d217d`）：新增 `MovementSystem::BroadcastMove`，把实体此刻的权威 Transform / Velocity 发成 133 `ActorMoveS2C`，收件人是「互相可见且有会话」的玩家、
  不含自己；在 134 / 132 / 131 被接受之后、战斗或冻结中收到 131、外推撞墙清速、走着退出（`StopMotionForExit`）四处调用。新增服务器主动的**同场景瞬移原语**
  `MovementSystem::TeleportWithinScene`：写位置、清速度、只给本人发 130 `TeleportS2C`（reason 5）、`AoiSystem::ResetEntityVisibility` 把可见性当作「离开旧位置 + 重新进场」重建，
  并挂 `TeleportSettleComp`：3 s（单调时钟）内上报点离目标水平距离超过 3 m 的 134 / 132 / 131 整条丢弃。**66 那一路原样保留**。唯一调用瞬移的是组队归队（4.3b）。
- **客户端实际怎么收**（§2，`f537079` 上逐个找到处理器）：移动相关的下行只注册了 133 / 135 / 130 / 137 的处理器（`GameClient.cs:1827-1874`）；**没有 66 的消息号常量、proto 类与处理器**，
  66 落进 `[unhandled]` 日志（`GameClient.cs:1685-1686`）。133 只用 `entity`（= 21 / 47 里的场景实体号）、`transform.location`、`transform.rotation`、`velocity`；
  `server_time_ms`、`move_state` 不读。远端角色先 0.15 s 线性插值到样本点，**之后按样本速度无限外推、没有上限**——收不到「停了」的 133，别人屏幕上的人会一直跑下去。
  未知实体的 133 静默丢弃；已有实体的 21 / 47 被去重（不更新位置）。130 只对本人有意义：硬吸附、取消点地寻路后重新规划、落在不可走处会自己挪并回报一条 131。
- **Java 现状**：只发 66（`entity_id = player_id`，PARITY「属性同步 66」行），从不发 133 / 135 / 130，没有瞬移原语、没有落位等待。按客户端的事实，
  **Java 的远端玩家在真客户端上不动——这是现存的客户端可见缺口**，2.10 修它。
- **Java 设计**（§5）：
  - 「告诉旁人在动 / 停了」的每一处在置 66 速度脏位的同时挂一个 **133 待发标记**；**每帧开头（外推之前）**对有标记的玩家发一条 133 给 `scene.watchers(p)`
    （看得见它的人 = 客户端上有它的人），同一帧内的多次输入合并成一条（每实体至多 20 Hz）。位置就是裁决后的权威值（`MoveGuard` 截断后）。
  - 新观察者**当场补发**：进场 / 帧内进视野 / 瞬移重建之后，看得见的目标里「上报过朝向或在动」的，给观察者一条 135（多目标批量），给看得见进场者的人一条 133。
    替代「交给下一条 133」：静止、只有朝向的目标永远不会再有下一条。
  - 离场 / 被接管 / 停服 / 同节点换场景前的停步**不发** 133（实体随即销毁，旁人收到 51；新场景的人从 21 看到的本来就是静止的它）。
  - **66 不动**：收件人、频率、`entity_id`、新观察者补发都保持现状（Q1；停发留给两版同步决定，并给出重估数据的判据）。
  - 同场景瞬移原语 `SceneWorld#teleportWithinScene`：拒冻结 / 战斗在途；**同步**完成可见性重建（旧观察者 64、本人 64 旧视野、本人 47 新视野、新观察者 21，再补发），
    给本人 130（reason 透传），清速度，`MoveGuard` 锚点重置到落点，挂落位等待（3 s / 3 m，按基线常量）。2.10 没有生产调用方；验证经 dev 管理口（reason 3，Q7）。
- **给 4.3b 留下**（§5.12）：瞬移原语与原因枚举、互相可见的查询、朝向的只读访问、瞬移指标、robot 的 130 / 133 断言工具。4.3b 自己做归队判定、入队通知与 `TeamFollowService` 同场景分支。
- **robot**（§11.6）：Java robot `movement` 以 133 为主断言（66 保留为旁证），重登段验新观察者补发，新增瞬移段（经 dev 管理口）；Go robot 的 133 / 135 / 130 处理器是空函数，
  不能拿来做跨实现验收；真客户端的验收要两台 Unity 客户端手工跑（§11.8）。

### 0.2 盘点 id 与落点

| 盘点 id | 出处 | 2.10 交付 |
|---|---|---|
| d10-move-broadcast-133 | `docs/porting/inventory/delta-20261010.md:46-70`；PARITY「移动广播 133」行（HEAD `:145`） | 133 的构造、触发点、帧开头合并发送、新观察者补发（§5.2–§5.6） |
| d10-teleport-within-scene | `delta-20261010.md:72-82`；PARITY「同场景瞬移」行（HEAD `:146`） | 瞬移原语、同步可见性重建、130、落位等待、dev 管理口（§5.7–§5.9） |
| 被依赖：d10-team-follow-v11 | `delta-20261010.md:84-96`；PARITY「组队跟随 v1.1」行（HEAD `:147`）；路线图 4.3b（`roadmap.md:71`） | 只留接口（§5.12），归队本身随 4.3b |
| 文档过期项 | `delta-20261010.md` §9 第 1 条 | `mmorpg-client-contract-movement.md` / `-aoi.md`、`architecture.md`、PARITY 五行（§5.13） |

### 0.3 范围

**2.10 做**：133 的发送（全部触发点）与新观察者补发（133 / 135）；同场景瞬移原语、130、可见性重建、落位等待；dev 管理口瞬移（dev / test 才放行）；指标；
`ViewCrowdBenchmarkTest` 加 133 一段重估；Java robot `movement` 的 133 / 130 断言；参考文档与 PARITY 改写。

**2.10 不做**：组队归队与入队即跟随（4.3b）；136 `TeleportRequest`（仍回 1006，PARITY「136」行）；撞墙清速（Java 没有导航网格，7.5 落地时补，§0.4）；
停发移动 66（Q1）；客户端改动（本仓库不碰客户端；需要客户端配合的登记为客户端改进，§8.3）。

### 0.4 与其它批次的边界

| 话题 | 归属 | 2.10 的处理 |
|---|---|---|
| 组队归队（`ShouldRegroup`、8 m、队长交接在途不归队）、入队即跟随的通知通道、robot team 的「收到 130 reason 5」 | 4.3b | 只提供原语与查询（§5.12）；`TeamFollowService.java:246-249` 同场景分支现在回 `SAME_SCENE`，2.10 不动 |
| 5.2 跨节点换图 / 5.4 跨 zone 传送的冻结停步（`SceneWorld.java:1745-1751` `haltForFreeze`） | 已做 | 停步处加 133（§5.3 ⑥）；冻结中的玩家不能被瞬移（§5.7 前置） |
| 6.3 回合制战斗的停步（`haltForBattle`，`SceneWorld.java:2945-2948`；D5） | 已做 | 停步处加 133（§5.3 ③④⑤）；战斗在途不能被瞬移 |
| 7.5 导航网格查询（`contract-navmesh-spec.md`：外推撞墙清零速度，`:458`） | 7.5 | 2.10 没有这个调用点；7.5 给 `integrate` 加撞墙清速时，**按本规格 §5.3 同一条规则挂 133 待发**（基线 `movement.cpp:112-117`），写进 7.5 的待办 |
| 视野（`ViewIndex`，PARITY「视野（AOI）」行） | 已做 | 不改重判规则；新增 `Scene#teleport`（离开 + 换位 + 重新进入，§5.7）；133 的收件人直接用 `watchers` |
| 136 TeleportRequest | 不改 | 仍回 1006；之后不发 130（「之后」只指对 136 的应答，服务器主动瞬移另说，PARITY「136」行已注明） |

---

## 1 基线协议

### 1.1 消息（`player_movement.proto`，0.3 已同步，形状与基线逐字相同）

| id | 方向 | 类型 | 字段 |
|---|---|---|---|
| **133** | S→C | `ActorMoveS2C` | `uint64 entity = 1`（proto 注释「actor entity id」）、`Transform transform = 2`、`Velocity velocity = 3`（零 = 停）、`uint64 server_time_ms = 4`、`uint32 move_state = 5`（0 停 / 1 走 / 2 跑 / 3 冲刺） |
| **135** | S→C | `ActorMoveListS2C` | `repeated ActorMoveS2C moves = 1`；proto 注释：玩家刚进 AOI 格、要一次同步很多 actor 时用 |
| **130** | S→C | `TeleportS2C` | `uint64 entity = 1`、`Transform transform = 2`、`uint32 input_seq = 3`（注释：客户端丢弃 ≤ 它的预测输入）、`uint32 reason = 4`（注释列到 4：0 纠正 / 1 传送门 / 2 技能 / 3 管理员 / 4 复活；基线 5 = 组队归队，`movement.h:13`，注释待 mmorpg 下次重生成时补） |
| 66 | S→C | `ActorBaseAttributesS2C` | 不变（基线移动触发的不带 `entity_id`；Java 每条带 `player_id`） |

`Transform` = `{Vector3 location = 1; Rotation rotation = 2; Scale scale = 3}`，各分量 `double`（fixed64），proto3 下 0.0 不编码（`mmorpg-client-contract-movement.md` §1）。
基线 133 经 `BroadcastMessageToPlayers`、130 经 `SendMessageToClientViaGate` 下发，信封 `MessageContent{message_id, id = 0}`；**135 基线仍然从不发**。
**没有新 proto、新消息号、新 tip**（`9199d217d` 提交说明）。

### 1.2 `BroadcastMove`（`movement.cpp:121-176`，声明与注释 `movement.h:37-49`）

- **内容**：`entity = entt::to_integral(entity)`（场景实体号，与 21 / 47 / 51 / 64 同一套口径，不是 guid）；`transform = *transform`（整个 Transform：location 恒在，
  rotation 写过才在）；`velocity` 有 Velocity 组件就拷（停止时是「存在但全零」，没有组件时字段缺席）；`server_time_ms = TimeSystem::NowMilliseconds()`（墙钟 UTC 毫秒，与 137 同口径）；
  `move_state = 速度非零 ? 1 : 0`（`kMoveStateWalking` / `kMoveStateStopped`，`movement.h:16-17`），从不发 2 / 3。
- **收件人**：遍历**自己的** `AoiListComp`，留下「仍有效、有 `PlayerSessionSnapshotComp`（有网关会话）、对方的 `AoiListComp` 里也有我」的实体（`:134-156`），不含自己；
  一个都没有就不发。**理由**（`movement.h:41-45`）：只按自己的表发会把坐标发给看不见我的人（隐身时对方表里没有我）；严格的「谁看得见我」要扫 7 格全部实体，每条上报都走这条路径，扫不起。
  **已知缺口**：对方看得见我、但我的表因容量满没收下对方时，对方收不到我的移动（与 66 同一口径的既有缺口）。
- **序列化一次**，按目标所在 gate 分组，每个 gate 一条内部广播，gate 再逐会话下发（`BroadcastMessageToPlayers`，同 66 的批量方式，`mmorpg-client-contract-movement.md` §6.1）。
- **只读组件、不增删组件**，可以在 view 遍历中调用（`movement.h:48`）。

### 1.3 调用点（HEAD 行号）

| # | 位置 | 时机 | 备注 |
|---|---|---|---|
| B1 | `mvh.cpp:183`（MoveStart）、`:232`（MoveSync） | 战斗 / 冻结闸与落位等待闸都过了、`ApplyReportedLocation` + `ApplyReportedVelocity` 之后 | 每条被接受的上报发一条，**不合批** |
| B2 | `mvh.cpp:212`（MoveStop） | 同上，速度清零之后 | |
| B3 | `mvh.cpp:195-201` | 回合制战斗在途（`InBattleComp`）或归属交接冻结（`PlayerFrozenComp`）中收到 131：只清速度、**无条件**广播 | 注释：进战 / 冻结前最后一次广播还带着行走速度 |
| B4 | `movement.cpp:112-117` | 外推撞墙清速的那一 tick | 只发一次（速度已清零，之后不再进这段） |
| B5 | `player_lifecycle.cpp:2611`（`StopMotionForExit`，`:2595-2620`） | 速度原本非零才清、才广播；调用方 `HandleExitGameNode`（`:2730`，紧接着 `DetachFromScene` 广播 51）与退出重存兜底（`:2678`） | 「走着掉线」；跨 zone / 跨节点交接也走 `HandleExitGameNode`，所以交接期间冻结的人**直到退出才**发「停了」 |

基线**不**发 133 的地方：AFK 停推（`afk.cpp` 只挂 `AfkComp`、不清速度）；进战（`InBattleComp` 只让外推跳过，不清速度，要等客户端发 131 才走 B3）；
归属交接冻结（`PlayerFrozenComp` 同理）；新观察者进视野（缺口 9 在基线仍在）；换场景（旧场景广播销毁，新场景重新进场）。

### 1.4 频率与量

- 客户端移动中每 0.25 s 一条 132（`TianyongPlayerController.cs:488-493`），起步一条 134，停步一条 131；转向立即补 132（≥ 20°、两条至少隔 0.12 s，**转述**，在未推送的 `40e6fdd4`）。
  所以每个移动中的玩家约 4 条 / 秒（`f537079`），最多约 8 条 / 秒（转述）。gate 按 MessageLimiter 限频：134 → 15、132 → 20、131 → 15 条 / 秒（PARITY「gate 按消息号限频」行），
  **单人上限约 50 条 / 秒全部被接受**——B1/B2 不合批，基线的 133 扇出没有自己的上限。
- 收件人受兴趣表容量约束（`kAoiListCapacityDefault = 100`，`aoi_priority.h:97`）。`team-system.md` V.2 第 5 条 / V.5 估算：每个移动中的玩家每秒 4–8 条 × 互相可见人数；
  「要减可以改成只在起步 / 停步 / 转向时发（协议注释的原意），代价是远端外推误差变大」。基线没有压测。
- **66 那一路原样保留**：偶数帧（每实体 ≤ 10 Hz）按脏位发给自己兴趣表里的实体，冻结实体不发（`actor_state_attribute_sync.cpp:56-113`，契约文档 movement §6.1）。

### 1.5 同场景瞬移 `TeleportWithinScene`（`movement.cpp:179-217`，注释 `movement.h:51-58`）

调用方保证落点是该场景导航上的合法点；没有 Transform 直接返回；只能在 scene 节点 loop 线程调。按顺序：

1. 写 `Transform.location = 落点`、`Transform.rotation = 入参`，置 Transform 脏位（`:188-190`）。
2. 有 Velocity 组件就 `Clear()` 并置 Velocity 脏位（`:193-197`）——否则 `Update` 从新位置按旧速度继续积分，人一落地就飘走。
3. `emplace_or_replace<TeleportSettleComp>`：记当时的场景句柄、目标 x / y、`expireAtMs = 单调时钟 + 3000`（`:200-205`）；重复瞬移以最后一次为准。
4. 实体是玩家（有 `Player` 组件）时**只给本人**发 130：`entity` = 场景实体号、`transform = *transform`（新位置 + 新朝向）、`reason`；**`input_seq` 不设（0）**（`:207-214`）。
5. `AoiSystem::ResetEntityVisibility(entity)`（`:216`）。

**没有**给旁人发 133，**没有**动 `LastActiveFrame`，**没有**改位置记录（Redis location 只记场景）。

### 1.6 `ResetEntityVisibility`（`aoi.cpp:234-299`，注释 `aoi.h:15-26`）

把同场景瞬移当作「从旧位置离开视野、在新位置重新进场」：

1. 自己这一侧：兴趣表里非 `kPinned` 的条目全部摘掉，**通知自己的客户端销毁**（`NotifyEntityVisibilityChanges` → 64 列表，`:241-253`）。
   注释给的理由：不通知的话客户端会把旧位置的 actor 永远留着，**之后同一实体再进视野时客户端按 entity 去重，连位置都不会更新**。
2. 观察者一侧：旧 Hex 的当前格 + 6 邻格里、兴趣表里有它（非钉住）的实体，摘掉并各收一条 64（`:257-291`）；把它移出格子、摘掉 `Hex`。
3. 下一次 `AoiSystem::Update`：没有 `Hex` 的实体走「首次进场」分支，按新位置对当前格及邻格重新做双向 `CanSee`，自己收 47、旁人经 `8843a1465` 的反向通知收 47。
   **所以基线的「新位置可见」最多晚一个 tick（50 ms）**。
4. 已知边界：钉住它的观察者既不收销毁也不收重建；移动广播只发给互相可见的人，它在那些客户端上会停在瞬移前的位置（`aoi.h:23-25`；目前没有玩法钉住玩家）。

### 1.7 落位等待（`teleport_settle_comp.h`、`movement.cpp:219-258`、`movement.h:19-31`、`:60-77`）

- **为什么**：移动是客户端上报、服务器裁决。瞬移发出时客户端可能还有按旧位置发的 134 / 132 / 131 在路上；照常裁决会把人沿直线拉回旧位置（中间没墙就整段接受），瞬移等于白做。
- **判定**（三个 handler 在战斗 / 冻结闸**之后**、裁决**之前**问 `ShouldDropReportAfterTeleport`，`mvh.cpp:176`、`:204`、`:225`）：
  - 没挂组件 → 照常（每条上报一次 `try_get`）；
  - 瞬移之后换了场景（场景句柄不同）→ 摘组件、照常；
  - 纯函数 `JudgeReportAfterTeleport(水平距离, now, expire)`：**先看距离再看时间**——上报点离目标 ≤ `kTeleportSettleRadius = 3`（含等号）→ `kSettled`（摘组件、照常裁决）；
    否则 `now ≥ expire` → `kExpired`（摘组件、照常裁决、`LOG_WARN`）；否则 `kStale` → **整条丢弃**（位置、速度、137、133 都没有）。
  - 先看距离的理由：客户端落位后可能站着不动，过了超时才发第一条上报，那仍是一次正常落位。
- **没有定时器**：判定与摘除都发生在下一条上报到达时；一直不上报的实体一直带着组件，无害。只在战斗 / 冻结闸之后判，所以战斗中的 131（B3）不受它影响。
- 不落盘、不进 proto。单调时钟（`std::chrono::steady_clock`）。

### 1.8 唯一的调用方（4.3b 的内容，列在这里只为说明原语怎么被用）

`PlayerTeamSystem::RegroupToLeader`（`player_team.cpp:514-574`）：与队长同一场景实例、队长归属交接不在途、`ShouldRegroup(水平距离, 互相可见)`（`player_team.h:93-96`：
互相不可见，或距离 > `kTeamRegroupDistance = 8`）→ `TeleportWithinScene(队员, 队长位置, 队长朝向, 5)`。「互相可见」只在双方都已有 `Hex`（已被 AOI 评估过）时看兴趣表，
否则按可见处理——因为基线的瞬移 / 进场到首次 `Update` 之间有一个 tick 的「还没评估」窗口（`:543-555`）。

### 1.9 验证状态

| 范围 | 证据 |
|---|---|
| 服务端 C++ | 无（未编译；`aoi_system_test.cpp:303/328/358/379` 的 `ResetEntityVisibility` ×4、`:1228/1235/1244` 的 `TeleportReportVerdictTest` ×3、`:1204/1211/1218` 的 `TeamRegroupRuleTest` ×3 都没跑） |
| Go robot | `robot/logic/handler/scene_movement_client_player_notify_{actor_move,actor_move_list,teleport}.go` 都是空函数（`message_body_handler.go:91-93` 已注册）；`team_smoke` 没有 133 / 130 断言 |
| 客户端配套 | 在未推送的 `40e6fdd4`，纯逻辑 36/36（转述）；Unity 内测试与双号联机没做 |
| 联机 | 没有（`team-system.md` V.6 第 3 步是待办清单） |

---

## 2 客户端可见行为（Unity `f537079`，逐个找到处理器）

### 2.1 处理器清单

| 消息 | 处理器 | 结论 |
|---|---|---|
| 133 | `GameClient.cs:1827-1831` → `ApplyServerMove`（`:1984-1990`）→ `ActorWorld.ApplyMove`（`ActorWorld.cs:317-333`） | 有 |
| 135 | `GameClient.cs:1833-1837`：对 `moves` 逐条 `ApplyServerMove` | 有（与 133 完全同义） |
| 130 | `GameClient.cs:1839-1846` → `ActorWorld.Teleport`（`ActorWorld.cs:339-376`）；本人走 `TianyongPlayerController.WarpFromServer`（`:201-226`） | 有 |
| 137 | `GameClient.cs:1848-1874` | 有（2.10 不改它） |
| **66** | **无**：`MessageIds.cs` 没有 66（也没有 `ScenePlayerSync` 服务的任何号）；`Assets/` 下没有 `ActorBaseAttributesS2C` 类（`Assets/Scripts/Proto/Generated/` 没有 `PlayerStateAttributeSync.cs`，全仓 grep 0 处） | 落进分发器的兜底分支 `Log("[unhandled] message_id=66 bytes=…")`（`GameClient.cs:1685-1686`），**每条一行日志** |

注意：`HandlerRegistry.cs:86-93` 里有 133 / 135 / 130 的 protogen 空壳，但 `HandlerRegistry.Register` 从未被调用（`GameClient.cs:1727-1733` 的注释），真正的注册表是 `OnNotify`，
且 `OnNotify` 是**覆盖语义**（`:409-410`）——将来谁把生成层接上，空壳会静默盖掉上面的处理器。这是客户端风险，与服务端无关，登记在 §8.3。

### 2.2 133 的字段解读与插值 / 外推

- `entity`：`ActorWorld` 的键（`SpawnActor` 用 `ActorCreateS2C.entity` 入表，`GameClient.cs:1947`）。**找不到这个实体就直接返回**（`ActorWorld.cs:320`）——
  133 早于 21 / 47 到达、或用错了 id（比如发成 player_id）都是**静默丢弃**，客户端没有任何日志。
- **本人的实体被忽略**（`:321-324`，本地角色由 CharacterController 预测驱动，纠偏只走 130 / 137）。
- `transform.location` → `FromServerVector`；**缺席时当作 (0, 0, 0)**（`WorldCoordinateConverter.cs` `FromServerVector(null) => Vector3.zero`）——服务端必须**总带 location**，
  否则远端角色被拉到世界原点。`transform.rotation` 缺席当作零朝向；`velocity` 缺席当作零。`server_time_ms`、`move_state`、`transform.scale` **不读**。
- 插值：从当前显示位置 / 朝向线性插到样本的位置 / 朝向，用时 0.15 s（`ApplyMove` 的缺省参数，`:318`）；之后**每帧 `TargetPos += Velocity × dt` 无限外推**（`Tick`，`:382-404`），
  朝向停在样本值。所以：
  - **收不到「停了」的 133，远端角色会一直按旧速度跑下去**（`f537079` 没有外推上限；1 秒上限在未推送的 `40e6fdd4`，转述）。服务端每一条让人停下的路径都必须补 133。
  - 插值窗口里不外推，所以行走中的远端角色恒定落后约「速度 × 0.15 s」（`team-system.md` V.4 转述的 1.35 m 就是 9 m/s × 0.15 s，`40e6fdd4` 改了这一点）。
  - 样本的位置是「服务器此刻的权威位置」就够：客户端不做时间戳对齐，晚到几十毫秒只是整体平移。
- 同一实体先后两条 133：后一条从**当前显示位置**重新插值，不会跳回。

### 2.3 130 的解读

- 按 `entity` 找实体；找不到静默返回。**本人**：`WarpFromServer`——`WarpTo` 先 `CancelPath(false)`（清点地路径、`_moving = false`、**不**发 131，`TianyongPlayerController.cs:173-188`、`:513-520`），
  按地图边界夹 XZ（`ClampXZ(…, 2f)`）后放到落点；如果当时在点地寻路，落位后**按原目的地重新规划**（`:206-224`）——于是下一步就是一条从落点出发的 134；
  落点在客户端行走掩码上不可走时挪到最近可走点并**回报一条 131**（`ReportPositionToServer`，`:228-258`、`:269-280`），2 s 内连续超过 3 次就不再回报
  （`RecoveryBurstWindow` / `RecoveryBurstLimit`，`:36-37`）。
  **非本人**：直接摆到落点（远端插值状态清掉）。
- `transform.location` 缺席同样当 (0, 0, 0)；`reason`、`input_seq` **只写进日志**（`GameClient.cs:1845`），**不丢弃任何预测输入**（客户端没有回滚重演管线，`:1852-1853` 的注释）。
- 瞬移后第一条上报：WASD 继续按着 → 下一帧 `MoveInDirection` 发起点 = 落点的 134（`:483-489`）；点地寻路 → 重新规划后的 134；站着不动 → 什么也不发。
  所以落位等待的「第一条落在目标附近的上报」在正常客户端上距离是 0（或掩码挪位的那一小段）。

### 2.4 视野消息的两个硬约束（与 133 / 130 直接相关）

- **21 / 47 按实体去重**（`ActorWorld.cs:157` `if (_actors.ContainsKey(entity)) return;`）：对客户端已有的实体再发 21 / 47 **不会更新位置**。
  要让旁人看见一个实体「跳到别处」，只有两条路：先 64 / 51 销毁再 21 / 47 创建（吸附），或发 133（0.15 s 内滑过去）。
- 64 / 51 之后再收到这个实体的 133：找不到实体、静默丢弃（无害，但说明服务端顺序错了）。

### 2.5 66

客户端不处理（§2.1）。Java 现在每 100 ms 向每个观察者发的移动 66，在真客户端上**只产生一行 `[unhandled]` 日志**。基线发的 66 同样如此（基线保留了 66 那一路）。

### 2.6 对服务端的硬约束（下文设计逐条满足，§11 逐条有测试）

| # | 约束 | 依据 |
|---|---|---|
| C1 | 133 / 135 / 130 的 `entity` 用**场景实体号**（= 21 / 47 / 51 / 64 的 `entity`），不是 player_id | §2.2、§2.3 |
| C2 | 133 / 135 / 130 的 `transform.location` 必须总在 | §2.2 |
| C3 | 收到某实体的 133 之前一定先收到过它的 21 / 47；收到它的 64 / 51 之后不再收到它的 133（直到重新创建） | §2.2、§2.4 |
| C4 | 任何「服务器让人停下、而旁人仍看得见他」的路径都要补一条速度为零的 133 | §2.2（无外推上限） |
| C5 | 让旁人看见同一实体换了位置（瞬移）必须「销毁 → 创建」或 133，不能只发 21 / 47 | §2.4 |
| C6 | 130 只给本人；落点必须在本人行走掩码上可走（否则客户端自己挪并回报 131） | §2.3 |
| C7 | 新观察者要靠服务端补发才能拿到目标的朝向 / 速度：21 / 47 只带位置（`ScenePlayer.java:277-291`），静止、只有朝向的目标之后不会再有 133 | §2.2 |

### 2.7 未推送的客户端改动（转述，`team-system.md` V.4，`40e6fdd4`）

远端插值改为「两端一起按权威速度外推、只把收到样本时的误差在 0.15 s 内收掉」；1 秒没有新样本就停止外推；转向 ≥ 20° 立即补 132；`SpawnActor` 禁用图元碰撞体
（否则被放到队长脚下的队员一开始就卡在队长的盒子里——`f537079` 的 `SpawnActor` 用 `CreatePrimitive`，自带碰撞体，`ActorWorld.cs:168-170`）；走路跟随（TeamFollowTrail / Planner / Driver）。
这些推送之前，Java 的设计必须在 `f537079` 上可用：C4 不能指望 1 秒上限兜底。

---

## 3 Java 现状与差距

### 3.1 今天「告诉旁人在动 / 停了」的地方（全部经 66）

| # | 位置 | 做什么 | 66 |
|---|---|---|---|
| J1 | `SceneWorld.java:2403-2437` `applyMove`（134 / 132 / 131 通过 `MoveInput.isAcceptable` 与 `MoveGuard.admit` 之后） | 位置取 `MoveGuard` 截断后的值、朝向整体覆盖、速度截到 10 m/s（131 为零）；偏差 > 0.5 m 给本人 137 | `DIRTY_TRANSFORM \| DIRTY_VELOCITY`（`:2421`） |
| J2 | `SceneWorld.java:2494-2498` `integrate` 挂机停推（PARITY「移动上行」有意差异 ③） | 600 帧无客户端消息：速度清零 | `DIRTY_VELOCITY` |
| J3 | `ClientRequestHandler.java:328-335`（战斗在途收到 131，`BattlePolicy.STOP_ONLY`） | 在动才 `haltForBattle` | `DIRTY_VELOCITY` |
| J4 | `PlayerBattleService.java:222`（备战即停步，scene-battle-spec D5） | **无条件** `haltForBattle` | `DIRTY_VELOCITY` |
| J5 | `PlayerBattleService.java:1281-1286` `attachFreeze`（迟到确认 / 进场恢复挂冻结） | 在动才 `haltForBattle` | `DIRTY_VELOCITY` |
| J6 | `SceneWorld.java:1745-1751` `haltForFreeze`（`freeze` `:1733`、`freezeForTravel` `:2271`） | 在动才停 | `DIRTY_VELOCITY` |
| J7 | `SceneWorld.java:1497-1503`（`enterScene`）、`:2513-2517`（`emitViewChanges`）→ `ScenePlayer.java:256-263` `markFullStateForNewWatcher` | 新观察者：目标上报过朝向就置 transform 脏、在动就置 velocity 脏（修基线缺口 9） | 下一个同步帧的 66 发给**全部**观察者 |
| — | `removePlayer`（`:2839-2856`）、`shutdown`（`:2881-2908`）、`switchScene`（`:1543-1567`）里的 `stopMotion()` | 只清速度、**不置脏位**（`ScenePlayer.java:241-248` 的 javadoc 写明了理由） | 无；旁人收到 51，新场景的人收到 21 |

66 由 `syncAttributes`（`SceneWorld.java:2535-2549`）在偶数帧发给 `scene.watchers(p)`（不含自己），没人看得见时**保留脏位**。帧顺序：外推 → 视野刷新（47 / 64）→ 偶数帧 66（`:2449-2469`）。

### 3.2 差距

1. **没有 133 / 135 / 130 的发送方**；`SceneMessageIds`（`:12-31`）没有这三个号；`MovementSyncTest.java:621` 的用例名就是「从不发 133 / 135 / 130」。
2. **新观察者补发走 66**（J7）：真客户端收不到，移动中的人进视野后静止到它下一条上报，静止、只有朝向的人朝向永远是零。
3. **没有瞬移原语**：`MoveGuard.reset`（`MoveGuard.java:47-51`）只有进场落位与换场景两个调用方；`Scene` 只有 `relocate`（`Scene.java:218-224`，交给帧内按节奏重判，不会「销毁 → 创建」）。
4. **没有落位等待**：服务器瞬移之后，在途的旧位置上报会被 `MoveGuard` 当成超速位移截断（额度 24 m）并回 137——客户端被拉回旧位置方向（delta 条目的「对已移植代码的影响」）。
5. **robot**：`MessageIds`（robot）没有 133 / 135 / 130；`MovementScenario` 只断言 66。
6. **文档**：`mmorpg-client-contract-movement.md` §0 第 2–3 条、§2 表、§3.6、§4.5、§6.1、§10，`mmorpg-client-contract-aoi.md` §3.1、§4、§5.2、§6，`architecture.md` 的「scene 场景帧」一段，
   以及 PARITY「移动上行」「属性同步 66」「136」三行都按「66 是唯一通道、不发 133」写。

Java 已经有、2.10 直接复用的：`watchers` 是精确的反向索引（`ViewIndex.java:17-18`：A ∈ watching(B) ⇔ B ∈ watchedBy(A)），且**每一次加入 / 删除都通知了观察者**
（21 / 47 / 64 / 51，PARITY「视野」行），所以 `watchers(p)` 恰好等于「客户端上有 p 的人」；Java 没有隐身、没有 NPC、在场实例恒有会话（断线即移出）；
`broadcast` 已按链路分组、序列化一次（`SceneWorld.java:2968-2978`）；`SceneClock` 有单调纳秒与墙钟毫秒（`SceneClock.java:9-12`）。

---

## 4 Java 与基线的客户端可见对照（2.10 落地后）

| 场景 | 基线（`9199d217d`，未编译） | Java 2.10 |
|---|---|---|
| A 走动，B 看得见 A | 每条被接受的输入当场一条 133（4–8 / 秒，上限约 50 / 秒） | 下一帧开头一条 133，同帧多条合并（4–8 / 秒，上限 20 / 秒）；比基线晚 0–50 ms |
| A 停下（131） | 133（速度零，`move_state` 0） | 同 |
| A 30 s 无任何消息 | 服务器停推但**不发** 133（不清速度），B 上的 A 按旧速度一直跑 | 停推、清速、133（有意差异 ③ 的延续） |
| A 备战 / 进入回合制战斗 | 不清速度；B 上的 A 一直跑，直到 A 的客户端发 131 才有 133 | 备战即停步、133 |
| A 换节点 / 跨 zone 冻结 | 冻结期间 B 上的 A 一直跑；交出后退出时 133 + 51 | 冻结时 133；交出提交后 51 |
| A 走着掉线 / 被顶号 / 停服 | 133 + 51 | 51（实体随即销毁，133 没有可见效果） |
| B 刚看见 A（进场 / 走近） | 只有 21 / 47 的位置；A 在动则静止到下一条 133，A 静止则朝向为零 | 21 / 47 之后当场补发（B ← 135；A 进场时 B ← 133） |
| 服务器把 A 瞬移 | A ← 130；A ← 64（旧视野）；旧观察者 ← 64；下一 tick：A ← 47、新观察者 ← 47 | A ← 130 → 64 → 47 → 135；旧观察者 ← 64；新观察者 ← 21 → 133。全部在同一次调用里 |
| 瞬移后 3 s 内的旧位置上报 | 丢弃 | 同（计 `moves{result=teleport_stale}`） |
| 66 | 照发（不带 entity_id） | 照发（带 player_id）；客户端都不处理 |

---

## 5 Java 设计

### 5.1 思路

把 133 当作「状态变化事件」而不是「每条输入的回声」：凡是会改变旁人对 p 的认知（位置跳变、速度变化、朝向变化）的服务器动作，都给 p 挂一个待发标记；
场景帧在**外推之前**把标记兑现成一条 133，发给此刻的 `watchers(p)`。新观察者的初始状态不靠「下一条 133」，而是在建立兴趣的同一处当场补发。
瞬移是「离开视野 + 换位 + 重新进入视野」的一次同步操作，复用 `ViewIndex` 的 `leave` / `enter`，不引入新的可见性规则。66 保持原样，与 133 互不依赖。

### 5.2 133 / 135 / 130 的构造（`ScenePlayer`）

```java
/** 133：此刻的权威状态。transform.location 恒在（C2），rotation 上报过才在，scale 不写；velocity 恒在（停止时线上为 1a 00）。 */
ActorMoveS2C toActorMove(long serverTimeMs) {
    Transform.Builder t = Transform.newBuilder().setLocation(position.toProto());
    if (rotation != null) t.setRotation(rotation);
    return ActorMoveS2C.newBuilder()
            .setEntity(entity)                                  // 场景实体号（C1），不是 playerId
            .setTransform(t)
            .setVelocity(velocity.toVelocity())
            .setServerTimeMs(serverTimeMs)                      // SceneClock.epochMillis()，与 137 同口径
            .setMoveState(velocity.isOrigin() ? 0 : 1)          // 只用 0 / 1（基线同）
            .build();
}

/** 130：只发给本人。input_seq 不设（基线同，客户端只记日志）。 */
TeleportS2C toTeleport(int reasonCode) { … entity、transform{location, rotation 若有}、reason … }

/** 新观察者是否需要补发：上报过朝向，或正在动（21 / 47 只带位置）。 */
boolean hasMotionState() { return rotation != null || !velocity.isOrigin(); }
```

135 = `ActorMoveListS2C{moves = [各目标的 toActorMove(now)]}`。尺寸（推导，按 proto3 编码）：移动中、朝向只有 yaw、z = 0 的一条 133 约 72 字节，加 `MessageContent` 信封约 77 字节
（`entity` 10、`transform` 33、`velocity` 20、`server_time_ms` 7、`move_state` 2）；停止的约 57 字节。另加 gate 的客户端帧头。

### 5.3 触发点（逐条对 Java 自己的调用点，基线只作参照）

规则一句话：**凡是服务器为「告诉旁人在动 / 停了」而置 66 速度 / 位置脏位的地方，同时挂 133 待发**（`ScenePlayer#markMoveBroadcast()`）。逐条：

| # | Java 位置 | 基线对应 | 条件 | 2.10 |
|---|---|---|---|---|
| ① | `applyMove`（J1），裁决成功之后、137 之前或之后都可（137 是给本人的、当场发） | B1 / B2 | 输入被接受（`INVALID` / 落位等待丢弃 / 冻结 DROP / 战斗 DROP 都不挂） | 挂待发；133 的位置 = `MoveGuard` 截断后的权威值 |
| ② | `integrate` 挂机停推（J2） | 无（基线不清速） | 速度由非零变零 | 挂待发（下一帧开头发出） |
| ③ | `ClientRequestHandler` 战斗在途的 131（J3） | B3（基线无条件） | 在动（现有条件） | 经 `haltForBattle` 挂待发 |
| ④ | `PlayerBattleService` 备战（J4） | 无（Java D5） | 无条件（现有条件，与 66 一致；静止时多发一条无害、且是罕见事件） | 同上 |
| ⑤ | `attachFreeze`（J5） | 无 | 在动 | 同上 |
| ⑥ | `haltForFreeze`（J6，5.2 / 5.4 冻结） | B5（基线要等交出后的退出） | 在动 | 挂待发。冻结中实体仍在场景里、旁人看得见，下一帧开头发出；若交出在那之前就提交，实例被移除、旁人收到 51，待发随实例消失（C3 仍成立） |
| ⑦ | 撞墙清速 | B4 | — | Java 没有导航，**无此调用点**；7.5 落地时按本表规则补（§0.4） |
| ⑧ | `removePlayer` / `shutdown` / 交出提交后的移除 | B5 | — | **不发**：51 随即销毁实体（停服时全员一起下线）。`ScenePlayer#stopMotion` 的 javadoc 补一句「也不发 133」 |
| ⑨ | `switchScene` | 无 | — | **不发**；清掉待发与落位等待（§5.4、§5.8）；新场景的人从 21 + 补发看到它 |
| ⑩ | 新观察者（J7 的两处 + 瞬移重建） | 无（缺口 9） | 目标 `hasMotionState()` | 当场补发（§5.5），不挂待发 |
| ⑪ | 同场景瞬移 | `TeleportWithinScene` | — | 不发 133 给旧观察者（他们收 64）；新观察者 21 + 补发；清待发（§5.7） |

`haltForBattle` 与 `haltForFreeze` 收拢成一个私有方法 `haltMotion(p)`：`stopMotion()` + `markDirty(DIRTY_VELOCITY)` + `markMoveBroadcast()`，两处各自保留现有的「在动才停」条件。

**不变量 I1（停步可见）**：帧开头兑现之后，对 p 的每个观察者 W，W 手里 p 的最后一条 133（或补发条目）的速度 = 服务器上 p 的速度。②–⑥ 保证「变零」被告知，
① 保证「变非零」被告知，⑩ 保证新观察者从正确的状态开始，⑧⑨⑪ 用销毁代替。I1 成立时，「已经静止的人再停一次」不需要 133——③⑤⑥ 的「在动才停」条件就是靠它。

### 5.4 合批与发送时机：帧开头兑现

`ScenePlayer` 加一个布尔 `moveBroadcastPending`（不是 66 的脏位：66 没人看得见时保留脏位，133 不保留——没有观察者就丢掉，后来的观察者由补发拿到当时的状态）。

帧顺序（`SceneWorld#step`，改 `:2449-2469`）：

```
for scene:
    flushMoveBroadcasts(scene)   // 新：待发的玩家各一条 133 → scene.watchers(p)，清标记
    integrate(scene)             // 外推、挂机停推（停推挂的待发下一帧兑现）
    scene.refreshViews(viewChanges)
    emitViewChanges()            // 每个观察者 47 → 135 补发 → 64
if 偶数帧: syncAttributes        // 66，不变
frame++
```

- **为什么在外推之前**：两帧之间收到的输入此刻还没被外推，133 的位置就是裁决值本身（与基线「裁决后当场发」的内容一致，robot 能精确比对）；
  外推后再发会多出一步 `v × 0.05` 的位移。`server_time_ms` 取兑现时刻的墙钟；服务器状态在兑现时刻与裁决时刻相同（外推是离散的），所以时间戳与位置自洽。
- **合并**：同一帧内同一玩家的多条输入只发最后状态的一条——「131 后紧跟 134」合并成一条「在走」，旁人看到的是最终状态。每实体每帧至多一条 = **20 Hz 上限**，
  与输入速率无关（基线不合批，单人按 gate 限频上限每秒约 50 条被接受，§1.4）。正常客户端 0.25 s 一条，几乎不会合并。
- **代价**：比基线晚 0–50 ms（平均约 25 ms）。客户端用的是「此刻位置 + 速度」外推（§2.2），整体晚到只是平移，不放大误差（推导）。
- **顺序**：133 在本帧的 47 / 64 之前发出。本帧要失去 p 的 W 先收 133 再收 64（C3 允许）；本帧新看见 p 的 W 先收 47、再收补发（§5.5，此时标记已清，补发用的就是当前状态），
  不会重复。进场（`enterScene`）与瞬移在两帧之间同步执行，它们的补发在同一次调用里紧跟 21 / 47（§5.5），之后本帧开头若 p 仍有待发，W 会再收一条同内容的 133——无害，不去重。
- **实现**：`flushMoveBroadcasts` 直接遍历 `scene.players()` 看标记（与 `integrate` 同为 O(N) 的布尔检查），不另建待发名单，所以移除 / 换场景不需要额外清理名单；
  `switchScene` 与瞬移只把标记清掉。整段计时记进 `game.scene.broadcast{kind=actor_move}`（§7）。
- **收件人** = `scene.watchers(p)`（不含 p 自己）。不需要基线的「互相可见」过滤：Java 的反向索引是精确的、每次增删都通知了观察者（§3.2 末段），所以它就是「客户端上有 p 的人」；
  基线那条「对方表满时收不到」的缺口在 Java 不存在（有意差异 X1）。
- **与视野的关系、带宽上界**：133 只读 `watchers`，不触发、也不参与视野重判（位置变化照旧经 `Scene#relocate` → `ViewIndex.moved` 登记，按「静止立即、移动中每累计 1 m」的节奏重判）。
  容量只约束观察者自己的表（`ViewIndex.DEFAULT_CAPACITY = 100`），`watchers(p)` 可以超过 100；所以**单个客户端**收到的 133 只来自它 `watching` 里的实体，
  上界 = 100 × 20 条 / 秒，典型 = 可见的移动者数 × 4–8 条 / 秒（每条约 77 字节，§5.2）；服务器出站总量 = Σ_W |watching(W)| × 各实体的兑现率 ≤ 在线人数 × 100 × 20 条 / 秒。
  进 10 m / 出 20 m 的滞回带里（10–20 m）已建立的关系照样收 133（与 66 同）。单个客户端的下行由兴趣表容量封顶，这是 133 不另设距离 / 频率分级的理由（YAGNI；要做 LOD 是另一批）。

### 5.5 新观察者补发（替代「交给下一条 133」）

| 发生处 | 观察者一侧 | 目标一侧 |
|---|---|---|
| `enterScene`（`:1490-1509`，进场 / 换场景，现有 21 / 47 之后） | 进场者 ← 一条 135：`entered.seen()` 里 `hasMotionState()` 的目标（没有就不发） | `entered.seers()` ← 一条 133（进场者自己的状态），进场者 `hasMotionState()` 才发；进场时速度恒为零，所以实际只在朝向已恢复（`player_state.facing`）时发 |
| `emitViewChanges`（`:2506-2528`，帧内进视野） | 每个观察者在它的 47 之后 ← 一条 135：`delta.added()` 里 `hasMotionState()` 的目标 | —（帧内的变化都按观察者归并，目标一侧已经包含在别的观察者的 135 里） |
| 瞬移重建（§5.7） | 被瞬移者 ← 135（新视野里需要补的目标） | 新观察者 ← 133（被瞬移者的状态，`hasMotionState()` 才发） |

- 观察者一侧用 135（多目标一帧，proto 注释的原意；客户端处理器与 133 同义，§2.1），目标一侧用 133（一份序列化扇给多人）。只有一个目标时也发 135，规则统一、好测。
- 66 的 `markFullStateForNewWatcher` **保留**（66 不动，§5.6）；补发是新增的、独立的一条路径。
- 补发的内容是兑现时刻的状态（`clock.epochMillis()`）；补发不清目标的待发标记（目标一侧的其他观察者还没收到）。

### 5.6 66：保持不变

收件人（`watchers`）、频率（偶数帧、每实体 ≤ 10 Hz）、`entity_id = player_id`、只带脏字段、没人看得见时保留脏位、`markFullStateForNewWatcher` 补发，全部不动。
理由与代价见 Q1。2.10 落地后 66 与 133 是两条互不依赖的通道：同一观察者在同一条链路上先后收到（133 在帧开头，66 在偶数帧末尾），客户端只处理 133。
PARITY「属性同步 66」行的标题「移动广播的唯一通道」与「从不发 133 / 135 / 130」两句改写（§5.13）。

### 5.7 同场景瞬移原语

```java
/** 逻辑线程。target 必须在世界范围内；facing 为 null 表示保持原朝向。 */
public TeleportResult teleportWithinScene(ScenePlayer p, Vec3 target, Rotation facing, TeleportReason reason)

public enum TeleportReason { TEAM_REGROUP(5), ADMIN(3); final int code; }   // wire 值 = 130.reason；小写名作指标标签
public enum TeleportResult { TELEPORTED, NOT_HERE, FROZEN, IN_BATTLE, INVALID_TARGET }
```

**前置**（任一不满足：什么都不改、计指标、返回对应结果）：

1. `playerById(p.playerId()) == p`（实例仍在本节点，否则 `NOT_HERE`）；
2. `!p.frozen()`（`FROZEN`）：交出冻结中的位置属于冻结快照，挪了它会被 `game_scene_transfer_post_freeze_mutations_total` 判成冻结后改动；
3. `!p.inBattle()`（`IN_BATTLE`）：回合制战斗在途时客户端在战斗画面、外推也跳过它（`integrate` `:2491-2493`），挪位置没有意义；
4. `MovementRules.insideWorld(target)`（`INVALID_TARGET`，隐含有限）。

基线原语不查 2 / 3（调用方 `CheckFollowLeader` 的守卫已挡掉，§1.8）；Java 放进原语本身（有意差异 X7），4.3b 的守卫照样保留。RESOLVING / PRECHECK（选目标中，未冻结）不挡，与 5.2 的冻结口径一致。

**步骤**（单线程、一次调用内完成，中间不让出）：

1. `Scene.Teleported t = scene.teleport(p, target)`——新方法：先拷 `watching(p)` 为 `lostWatching`，`view.leave(p)` 得 `lostWatchers`（离开者从格子、双方兴趣表、待刷新名单里彻底清掉，
   `ViewIndex.java:133-152`），`p.setPosition(target)`，`view.enter(p)` 得 `Entered{seen, seers}`（`:99-127`，按新位置当场双向判，受容量限制）。`Scene.players` 不动、人数不变。
   这是 `relocate` 之外第二个被允许改位置的入口（`ScenePlayer.java:29-30` 的约定一并改）。
2. 状态：`facing != null` 时 `setRotation(facing)`；`stopMotion()`；`markDirty(DIRTY_TRANSFORM | DIRTY_VELOCITY)`（66 的积压语义照旧）；
   `moveGuard().reset(target, clock.nanoTime())`（锚点到落点、额度回满——否则客户端从落点发的第一条上报会被当成瞬移截断、回 137 把人拉回去）；
   `setTeleportSettle(target.x, target.y, now + 3 s)`（覆盖上一次的）；清 `moveBroadcastPending`。
   66：`seen` 里每个目标 `markFullStateForNewWatcher()`，`seers` 非空时 p 自己也 `markFullStateForNewWatcher()`（与 `enterScene` 同）。
3. 下行（同一链路上按此顺序；顺序是 C3 / C5 的要求）：

| 收件人 | 消息（按序） |
|---|---|
| p 本人 | 130（`toTeleport(reason.code)`）→ 64 `{lostWatching 的实体号}`（非空才发）→ 47 `{seen}`（非空才发）→ 135（`seen` 里 `hasMotionState()` 的，非空才发） |
| `lostWatchers` | 64 `{p.entity}`（一份序列化） |
| `seers` | 21（`p.toActorCreate()`，新位置）→ 133（p 的状态，`hasMotionState()` 才发） |

   一个旁人 X 同时在 `lostWatchers` 与 `seers` 里（短距离瞬移）：先收 64 再收 21，客户端先销毁再按新位置创建（C5；只发 21 会被去重、位置不动）。p 本人同理：
   `lostWatching` 与 `seen` 的交集先 64 后 47。这是基线 `ResetEntityVisibility` 的「全部重置」语义，只是 Java 同步做完、不等下一帧。
4. 指标与日志：`teleports{reason, result}`；`aoi.changes` 按现有口径计（`lostWatchers + lostWatching` 计 leave，`seen + seers` 计 enter）；INFO 一行（player、scene、from → to、reason、四个集合的大小）。

**不做**：不改位置记录（只记场景，`PlayerLocations`）；不调 `teamFollow.onEnteredScene`（不是换场景）；不刷新活跃帧；不发 79。
**调用约束**：只能在逻辑线程、且**不在帧内遍历**（`integrate` / `syncAttributes` / `flushMoveBroadcasts` 正在遍历玩家或兴趣集合时）调用——它改兴趣集合。
2.10 的两个调用方（dev 管理口、4.3b 的组队回调）都是投到逻辑线程的独立任务，天然满足。

### 5.8 落位等待

- `ScenePlayer` 加 `TeleportSettle settle`（record：`targetX`、`targetY`、`deadlineNanos`；不落盘、不进 proto），只由 §5.7 写、由 `applyMove` 读和清，`switchScene` 清（唯一的换场景入口；
  实例按进场新建，跨节点 / 跨 zone 天然作废）——等价于基线「换了场景就作废」的场景句柄比较。
- 纯函数 `MovementRules.judgeAfterTeleport(double horizontalDistance, long nowNanos, long deadlineNanos) → SETTLED | EXPIRED | STALE`，规则逐字照基线：先距离（≤ 3.0 m，含等号）后时间（`now ≥ deadline`）。
  常量 `TELEPORT_SETTLE_RADIUS = 3.0`、`TELEPORT_SETTLE_TIMEOUT = 3 s`（基线 `movement.h:20-23`），放 `MovementRules`。
- 在 `applyMove` 里的位置：`isAcceptable()` **之后**（非有限 / 越界的输入照旧计 `INVALID`，不让 NaN 距离参与判定）、`MoveGuard.admit` **之前**：
  - `settle == null` → 照常；
  - `STALE` → 整条丢弃：位置、朝向、速度都不应用，不挂 133，不发 137，计 `moves{result=teleport_stale}`（「每条移动上行在 `moves{result}` 里恰好计一次」的口径不变）；
  - `SETTLED` → 清 `settle`、计 `teleport.settles{result=settled}`、照常裁决；
  - `EXPIRED` → 清 `settle`、计 `expired`、WARN 一行（player、目标、上报点；不进指标标签）、照常裁决。
- 战斗在途的 131（`STOP_ONLY`）与冻结中的 DROP 在分发处就截下了，不经过 `applyMove`，与基线「先战斗 / 冻结闸、后落位闸」同序。`touch`（活跃帧）照旧在分发处刷新。
- **超时之后的语义**：「照常裁决」在 Java 的信任模型里 = 相对锚点（落点）过 `MoveGuard`。130 没被客户端应用（只可能是客户端没处理，同一条链路不会单独丢帧）时，
  超时后第一条远处上报按位移校验截断到 24 m、回 137，客户端吸附到截断点，此后照常——不会卡死，但不是「整段撤销瞬移」（基线无导航场景是整段接受）。见 §8.2 R5 与 N2。

### 5.9 dev 管理口瞬移（Q7 推荐采纳）

- `POST /admin/scene/player/teleport`（挂 `SceneAdminController`，与 5.3 的实例管理口同一套：只绑本机、运维令牌鉴权、每次调用一行审计、运行模式不是 dev / test → 403，`SceneAdminController.java:31-45`）。
- 契约（Java 自有，`game-api/src/main/proto/game/api/scene_admin.proto` 追加，不是同步产物）：
  `TeleportPlayerRequest{uint64 player_id = 1; double x = 2; double y = 3; double z = 4;}` → `TeleportPlayerResponse{uint32 tip_id = 1;}`；
  0 = 已瞬移；1005 = 坐标不在世界范围内或 player_id 为 0；1012 = 本节点没有这个玩家；1006 = 冻结中或回合制战斗在途。
- HTTP 线程只做鉴权与编解码，业务投到逻辑线程（`InstanceAdmin` 旁边加一个 `PlayerAdmin` 入口），限时等结果；`reason = ADMIN(3)`，朝向保持。没有任何客户端消息号。
- 唯一用途：在 4.3b 之前让 robot 与本机切片能端到端验证瞬移（§11.6）。

### 5.10 线程所有权与 I/O

全部在场景逻辑线程：标记、兑现、补发、瞬移、落位判定。没有新的阻塞 I/O、没有新的 Redis / MySQL 访问。管理口遵守既有模式（HTTP 线程不碰场景状态）。
出站仍经 `ClientSink` → 节点链路：scene → gate 链路出站缓冲越过高水位是「丢帧并断开链路」（`SceneMetrics` `LinkDrop.WRITE_BUFFER_FULL`），gate → 客户端写缓冲越过 2 MB 断开会话
（`GateNode.java:80-81`），**没有「悄悄丢一条 133」的路径**——丢就是断线，断线后旁人收到 51（C4 不会因背压被破坏）。

### 5.11 不变量

| # | 不变量 | 由谁保证 |
|---|---|---|
| I1 | 帧开头之后，每个观察者手里 p 的最后一条 133 / 补发的速度 = 服务器上 p 的速度 | §5.3 的触发点表 |
| I2 | 133 / 135 只发给发送时刻的 `watchers` / 新观察者；因此收到某实体的 133 之前一定收到过它的 21 / 47，收到 64 / 51 之后不再收到（C3） | `watchers` 是精确反向索引；所有增删都通知；同一链路 FIFO |
| I3 | 133 / 135 / 130 的 `entity` 恒为场景实体号，`transform.location` 恒在（C1、C2） | `toActorMove` / `toTeleport` 是唯一构造处 |
| I4 | 每实体每帧至多一条帧内 133；每条被接受的输入在下一帧开头之前被兑现（合并后） | 布尔标记 + 帧开头兑现 |
| I5 | 落位等待中 `STALE` 的输入不改任何状态、不产生任何下行 | §5.8 |
| I6 | 被瞬移的人在调用返回时已与新位置的人双向建好可见性（受容量限制），旧位置的人已收到销毁 | `Scene#teleport` 同步完成 |
| I7 | p 自己永远收不到自己的 133 / 135 条目；130 只发给 p | `watchers` 不含自己；`toTeleport` 只经 `sendTo(p)` |

### 5.12 给 4.3b 留下的接口

| 接口 | 形状 | 4.3b 怎么用 / 注意 |
|---|---|---|
| 瞬移原语 | `SceneWorld#teleportWithinScene(p, target, facing, reason)` → `TeleportResult`（public，逻辑线程） | 归队：`(队员, 队长.position(), 队长.facing(), TEAM_REGROUP)`。原语拒冻结 / 战斗，4.3b 的守卫（`switchInFlight`、`inBattle`、战斗锁）照旧在前 |
| 原因 | `TeleportReason.TEAM_REGROUP`（wire 5，指标 `team_regroup`） | robot team 断言「B 收到 130 reason 5」用它 |
| 互相可见 | `SceneWorld#mutuallyVisible(a, b)`（public，逻辑线程；同场景且 `a ∈ watching(b) ∧ b ∈ watching(a)`） | `ShouldRegroup(距离, 互相可见)` 直接用。**Java 的可见性在进场 / 瞬移时同步评估**，没有基线「还没有 Hex = 还没评估」的窗口，4.3b 不需要基线 `visibilityEvaluated` 那个分支（`player_team.cpp:543-555`）；瞬移返回后紧跟的第二次检查看到的就是「互相可见、距离 0」，不会重复瞬移 |
| 朝向 | `ScenePlayer#facing()`（public 只读，`Rotation` 不可变，可为 null） | 落在队长脚下、朝向同队长（基线同） |
| 落位等待 | 原语自带；重复瞬移覆盖目标与截止 | 4.3b 不需要自己挡过期上报 |
| 指标 | `game.scene.teleports{reason=team_regroup, result}` 2.10 就注册 | 4.3b 只加 `TeamFollowResult` 的新取值（如 `REGROUPED` / `REGROUP_SKIPPED`） |
| robot | robot `MessageIds` 的 `notifyActorMove` / `notifyActorMoveList` / `notifyTeleport`；`MoveAssertions` 的 133 / 135 / 130 解析与按实体号匹配 | `TeamScenario` 直接复用 |

**4.3b 仍要自己做**：`TeamFollowService` 同场景分支（`:246-249` 现在回 `SAME_SCENE`）改成归队判定；`kTeamRegroupDistance = 8` 与 `ShouldRegroup`；队长归属交接在途不归队
（`world.switchInFlight(leader)`）；game-team 入队提交后通知被加入者所在 scene 节点的通道（改「组队」行有意差异 ⑥）；同图换线跟随之后的归队（Java 换线经 `switchScene` →
`onEnteredScene` → 异步读 → 回到逻辑线程时已同场景 → 归队）。**4.3b 要考虑的两个 2.10 事实**：① Java「互相不可见」只剩兴趣表满一种（没有隐身），表满时瞬移也解决不了，
需要在判定里排除，否则每个跟随时机都会瞬移一次；② `f537079` 的客户端里远端角色带碰撞体（§2.7），被放到队长脚下的队员在本机可能卡在队长的盒子里，要等客户端 `40e6fdd4` 推送。

### 5.13 需要同步修改的文档与登记

- **PARITY.md**：
  - 「移动广播 133」行（HEAD `:145`）→ 已对齐（行为有意不同），证据写本批提交与测试；有意差异 X1–X5、X8 摘要。
  - 「同场景瞬移」行（`:146`）→ 已对齐（行为有意不同），X6、X7、X9；注明 2.10 没有生产调用方、4.3b 接上。
  - 「移动上行」行（`:47`）→ 有意差异 ③ 改为「…发一条速度为零的 66 与 133」；补「瞬移后 3 s 落位等待」一句；「mmorpg `73b852f3` 有修正，Java 待跟」一段改成已跟。
  - 「属性同步 66」行（`:50`）→ 标题去掉「移动广播的唯一通道」；「从不发 133 / 135 / 130」改为「移动另经 133 广播（见『移动广播 133』行），66 原样保留，客户端（`f537079`）没有 66 的处理器」。
  - 「136」行（`:51`）→ 补「服务器主动瞬移发 130 见『同场景瞬移』行」。「视野（AOI）」行（`:49`）→ 补「新观察者经 133 / 135 补发朝向与速度；瞬移按『销毁 → 创建』同步重建」。
  - 「组队跟随 v1.1」行（`:147`）→ 只注明「依赖的 2.10 已交付」，状态不变。
- **`docs/reference/mmorpg-client-contract-movement.md`**：头部基线改为「`9c9c012b7`，移动广播与瞬移部分按 `73b852f3`」；§0 第 2–3 条（133 有发送方、别人看到你在动靠 133、
  并注明客户端没有 66 的处理器）；§2 消息清单（133 / 135 / 130 的「基线实际行为」与 66 行「唯一通道」）；§3.6 标题与 130 的 reason 取值（补 5，注 Java 管理口用 3）；
  §4.5 两行（「别人收到 66」→「别人收到 133（与 66）」）；§6 加 6.0「133」并把 6.1 的「唯一的位置广播」改掉；§10 清单「不发 133 / 135 / 130」改为本规格的要求；新增一节「瞬移与落位等待」。
- **`docs/reference/mmorpg-client-contract-aoi.md`**：§3.1（`:156`「要等它的下一条 66」）、§3.6（robot 的 133 / 135 / 130 处理器是空函数）、§4（`:195`）、§5.2（133 的时序）、§6 汇总表。
- **`docs/design/architecture.md`**「scene 场景帧」一段（`:1113-1127`）：帧顺序加「帧开头兑现 133」，基准数字按 §11.5 重测的结果改。
- **代码注释**：`SceneWorld` 类注释的「视野」「移动」「帧」三段；`ScenePlayer` 类注释（`entity` 也是 133 / 135 / 130 的口径）与 `stopMotion` / `markFullStateForNewWatcher` 的 javadoc；
  `MovementSyncTest` 的用例名。
- **路线图** 2.10 行改状态；`inventory/delta-20261010.md` 是快照，不改。

### 5.14 代码改动清单与提交注意

| 模块 | 文件 | 改动 |
|---|---|---|
| game-scene | `SceneMessageIds` | 加 `notifyActorMove` / `notifyActorMoveList` / `notifyTeleport`（按「服务裸名 + 方法名」解析，缺了启动失败） |
| | `ScenePlayer` | `toActorMove` / `toTeleport` / `hasMotionState` / `facing()`；`moveBroadcastPending`；`TeleportSettle` |
| | `SceneWorld` | §5.3 的挂标记；`flushMoveBroadcasts`；`step` 顺序；`enterScene` / `emitViewChanges` 补发；`haltMotion`；`teleportWithinScene`；`mutuallyVisible`；`applyMove` 落位判定；`switchScene` 清理 |
| | `Scene`、`ViewIndex` | `Scene#teleport`（复用 `leave` / `enter`，不新增可见性规则） |
| | `MovementRules` | 落位常量与 `judgeAfterTeleport`；`TeleportReason` / `TeleportResult`（同包新文件） |
| | `SceneMetrics` | §7 的新取值与新计数 |
| | `SceneAdminController`（+ `SceneNode` 的投递） | §5.9 |
| game-api | `scene_admin.proto` | §5.9 的两条消息（Java 自有） |
| game-robot | `MessageIds`、`MoveAssertions`、`MovementScenario`（+ 选项） | §11.6 |
| docs / PARITY | §5.13 | |

不改：同步产物（proto、消息号、tip、配表）、gate、login、scene-manager、game-team。
**提交注意**：PARITY.md 在工作区里有另一个会话未提交的「组队」行改动，2.10 提交时只带本批改的那几行（临时索引：`read-tree HEAD`，把「HEAD 版 + 本批修改」`hash-object -w` 后
`update-index --cacheinfo`，再 `write-tree` / `commit-tree` / `update-ref`），然后把本批改动同样落到工作树的 PARITY.md、保留对方那一行；其余文件按路径 `git add`，不用 `-A`。

---

## 6 配置与常量

### 6.1 基线

全是编译期常量：`kTeleportSettleTimeoutMs = 3000`、`kTeleportSettleRadius = 3.0`、`kTeleportReasonTeamRegroup = 5`、`kMoveStateStopped / Walking = 0 / 1`（`movement.h:13-23`）；
`kTeamRegroupDistance = 8`（4.3b）。没有配置项。

### 6.2 Java

**不新增配置项**。新增常量：`MovementRules.TELEPORT_SETTLE_RADIUS = 3.0`、`TELEPORT_SETTLE_TIMEOUT = Duration.ofSeconds(3)`；`TeleportReason.TEAM_REGROUP = 5`、`ADMIN = 3`
（取 proto 注释里「3=admin」）；`move_state` 0 / 1。理由：落位半径 / 超时与客户端「落位后第一条上报从落点出发」的行为耦合（§2.3），基线也是常量；做成配置只会多一种和客户端对不上的方式。
合批粒度就是帧（20 FPS，现有 `MovementRules.STEP_SECONDS`），不单独配置。

---

## 7 指标

### 7.1 基线

只有日志：`metric=team_regroup` / `team_regroup_skipped`（`player_team.cpp`，4.3b）与落位超时的 `LOG_WARN`（`movement.cpp:250-253`）。133 没有任何计数。

### 7.2 Java（Micrometer；不以 player / session / 场景号 / 节点号作标签，`architecture.md:1871`、`AGENTS.md` §5）

| 指标 | 类型 | 标签（取值） | 含义 |
|---|---|---|---|
| `game.scene.broadcast{kind=actor_move}` | timer | `kind` 新增 `actor_move` | 每帧一次：全部场景的 133 兑现耗时（与 `view_changes` / `attribute_sync` 并列，基准与看板直接对比三段） |
| `game.scene.actor.move.deliveries` | counter | `kind` ∈ {`broadcast`, `catch_up`} | 133 / 135 条目送达的「(收件人, 实体)」对数：帧内兑现计 `broadcast`，新观察者补发计 `catch_up`。带宽评估用（× 约 77 字节） |
| `game.scene.moves{result}` | counter | 新增 `teleport_stale` | 落位等待中丢弃的上行（每条恰好计一次的口径不变） |
| `game.scene.teleports` | counter | `reason` ∈ {`team_regroup`, `admin`}；`result` ∈ {`teleported`, `not_here`, `frozen`, `in_battle`, `invalid_target`} | 每次调用原语恰好计一次 |
| `game.scene.teleport.settles` | counter | `result` ∈ {`settled`, `expired`, `voided`} | 落位等待的结局：`voided` = 换场景或被下一次瞬移覆盖时还没落位 |

### 7.3 勾稽

- `moves{accepted + clamped + corrected}` ≥ 帧内兑现里「由输入挂的」那部分（合并会让后者更少）；`deliveries{broadcast}` ≤ Σ 兑现条数 × 平均观察者数。
- `teleports{result=teleported}` ≥ `settles{settled + expired + voided}`；差值是仍在等待的人（等待没有定时器，长期不上报的人一直挂着）与等待中离场的人（实例随之丢弃、不计）。
- `settles{expired}` 持续非零 = 客户端没有应用 130（或掩码与服务端落点长期不一致）：查 WARN 日志里的目标与上报点。

---

## 8 隐患与边界

### 8.1 基线自身的隐患（Java 对应处置）

| # | 基线隐患 | Java |
|---|---|---|
| B-1 | 133 不合批：单人按 gate 限频上限每秒约 50 条被接受，扇出没有上限 | 帧开头合并，每实体 ≤ 20 Hz（X2） |
| B-2 | AFK 停推、进战、交接冻结都不清速度、不发 133：在 `f537079` 客户端上，旁人看到的人一直跑下去（交接冻结要到退出才停） | 全部清速 + 133（X4、X5） |
| B-3 | 「互相可见」过滤：对方表满时收不到移动（`movement.h:44-45`） | 反向索引精确，无此缺口（X1） |
| B-4 | 新观察者没有补发（缺口 9）：移动中的人进视野静止到下一条 133，静止的人朝向为零 | 当场补发（X3） |
| B-5 | 瞬移后新位置的可见性晚一个 tick；钉住它的观察者收不到销毁 / 重建 | 同步重建；Java 没有钉住（X6） |
| B-6 | 4.3b 的「还没评估」窗口要靠 `visibilityEvaluated` 绕开，否则进场链与紧跟的第二次检查都会重复瞬移 | Java 没有这个窗口（§5.12） |
| B-7 | 130 的 `input_seq` 不设，proto 注释说客户端据此丢弃预测——客户端实际不用（§2.3） | 照搬（Q8） |
| B-8 | 整批未编译 | Java 以单测 + robot 证据为准；跨实现只对客户端可见形状（§11.7） |

### 8.2 Java 移植时会踩的 / 残余风险

| # | 风险 | 处置 |
|---|---|---|
| R1 | 用错 id：133 / 130 的 `entity` 写成 player_id（66 用的是 player_id），客户端静默丢弃、没有任何日志 | `toActorMove` / `toTeleport` 唯一构造处；单测断言等于 21 里的 `entity`（§11.2 T1） |
| R2 | 漏一个停步点：`f537079` 客户端没有外推上限，远端一直跑 | §5.3 表逐条有测试；新增「让人停下」的代码必须走 `haltMotion` |
| R3 | 瞬移只发 21 / 47 不先销毁：客户端去重、位置不动（C5） | `Scene#teleport` 返回 `lostWatchers` 与 `lostWatching`，下行顺序固定（§5.7 第 3 步）；单测覆盖短距离瞬移的交集 |
| R4 | 带宽 / 帧耗时：66 不动再加 133，人群里每个客户端下行约翻倍；2000 人挤在 15 m 的极端基准 p99 已贴着 50 ms（`architecture.md:1123-1125`，其中偶数帧 66 扇出约 8 ms） | 推导：133 每帧约 400 人兑现 × 约 100 观察者 ≈ 66 偶数帧扇出的 1/5，即每帧约 +1.6 ms；以 §11.5 重测为准，超预算按 Q1 的判据处理 |
| R5 | 130 没被客户端应用：3 s 后第一条远处上报被位移校验截断到 24 m 并回 137，客户端被拉向落点一段 | 可接受（只在客户端没处理 130 时）；`settles{expired}` 与 WARN 可见；不做「超时撤销瞬移」（N2） |
| R6 | 客户端掩码与落点不一致（Java 没有导航网格，落点就是队长 / 管理口给的点）：客户端挪到最近可走点回报 131，挪动 > 3 m 时 3 s 内被当成过期丢弃 | 基线同（`team-system.md` V.5）；4.3b 的落点是队长刚上报的位置，同一份掩码上可走 |
| R7 | 在帧内遍历中调用瞬移会改兴趣集合 | 调用约束写进 javadoc；两个调用方都是独立任务（§5.7 末） |
| R8 | 合批让 133 比输入晚 0–50 ms；robot 若按「输入发出后 X ms 内」断言要留余量 | robot 观察时限沿用 `observeTimeout`（远大于 50 ms） |
| R9 | 帧开头兑现与 `enterScene` / 瞬移的补发可能让同一观察者在同一帧收到两条同内容的 133 | 无害（客户端幂等），不去重；测试只断言「至少一条、内容正确、顺序正确」 |
| R10 | `PlayerBattleService` 备战无条件停步（J4）：静止的人备战也发一条 133 | 罕见、幂等；保持与 66 同一条件，不另设分支 |
| R11 | 4.3b：兴趣表满导致的「互相不可见」会让每个跟随时机都瞬移 | 写进 §5.12，由 4.3b 判定时排除 |

### 8.3 需要客户端配合 / 登记为客户端改进（本仓库不改客户端）

- 66 每条一行 `[unhandled]` 日志（§2.1）：基线同样触发。建议客户端给 66 注册一个静默处理器，或两版同时停发移动 66（Q1）。
- `HandlerRegistry.Register` 一旦被接上会覆盖 133 / 135 / 130 的真实处理器（§2.1）。
- `40e6fdd4` 的外推上限、碰撞体修正与转向补发尽快推送（§2.7）；推送前 C4 只能靠服务端保证。

---

## 9 建议的有意差异

### 9.1 建议采纳

| 编号 | 差异 | 理由 | 客户端可见？ | 两版同改？ |
|---|---|---|---|---|
| X1 | 133 收件人 = `watchers(p)`（精确反向索引），不做「对方表里也有我」的过滤 | Java 的 `watchers` 就是「客户端上有 p 的人」；基线的过滤是因为它不知道谁看得见自己（`movement.h:41-45`）；没有隐身、没有 NPC | 是：基线表满缺口下收不到的人，Java 收得到 | 否（基线做法受它的 AOI 结构限制） |
| X2 | 帧开头兑现、同帧合并，每实体 ≤ 20 Hz；基线每条输入当场发 | 扇出与输入速率解耦（B-1）；一个兑现点，停步类事件统一；位置仍是裁决值 | 是：晚 0–50 ms；同帧的「停 + 走」只看到最终状态 | mmorpg 可选 |
| X3 | 新观察者当场补发（观察者一侧 135、目标一侧 133）；基线不补 | 修缺口 9 在真客户端上的版本（Java 原先的 66 补发客户端收不到）；静止、只有朝向的人不会有下一条 133 | 是：进视野即显示正确的朝向与运动 | mmorpg 可选（缺口 9 已登记「mmorpg 待做」） |
| X4 | 挂机停推清速并发 133；基线既不清速也不发 | 「移动上行」有意差异 ③ 的延续（原先发的是客户端收不到的 66） | 是 | mmorpg 可选（③ 已登记） |
| X5 | 备战 / 进场恢复挂冻结 / 交出冻结时停步并发 133；基线要等客户端发 131 或交出后退出 | Java 早已在这些时刻停步（D5、5.2 §5.5），只是改用客户端能收的通道 | 是：旁人看到的人在冻结 / 备战那一刻停下 | mmorpg 可选 |
| X6 | 瞬移的可见性重建在同一次调用里同步完成（旧观察者 64、新观察者 21）；基线销毁当下、重建在下一 tick（新观察者收 47） | Java 的视野索引可以当场判；没有「还没评估」窗口，4.3b 不需要绕开 | 是：新位置的人早 ≤ 50 ms 看见；新观察者收 21（单条）而不是 47（列表），客户端同义 | 否 |
| X7 | 瞬移原语自己拒冻结 / 回合制战斗在途；基线原语不查（调用方查） | 冻结快照一致性与战斗语义属于原语的前置条件，放在原语里一处守住 | 否 | 否 |
| X8 | 离场 / 被接管 / 停服前不发「停了」的 133；基线 `StopMotionForExit` 发 133 后紧跟 51 | 51 随即销毁实体，133 没有可见效果 | 是：少一条无效果的 133 | 否 |
| X9 | dev 管理口瞬移（reason 3） | 4.3b 之前端到端验证原语；Java 自有工具，不是客户端协议 | 否（生产不可达） | 否 |

### 9.2 列出但不建议采纳

- **N1**：瞬移只对「可见关系真正变了」的人发销毁 / 创建，仍可见的人改发 133：少一对 64 / 21，但 133 会让对方在 0.15 s 内「滑」过整段距离（最远约 30 m），观感不如吸附；
  还要多写一套求交集的代码。保持「全部重置」。
- **N2**：落位超时后把 `MoveGuard` 锚点改回瞬移前的位置（「撤销瞬移」，基线无导航场景的等价行为）：要多存一个点；而且「超时后接受远处位置」若不加限制就是一次免费的客户端瞬移。
  只在客户端没处理 130 时才触发，不值得。
- **N3**：用 21 / 47 带上朝向来修新观察者的朝向：改了两条与基线逐字相同的视野消息，且速度仍然要靠 133，补发一条也省不掉。
- **N4**：133 当场发（不合批）：与基线时机逐字一致，但扇出跟着输入速率走（B-1），停步类事件仍要另找发送点。
- **N5**：补发只用 133（不用 135）：进人群时一个观察者要收上百条单独的 133；135 是协议为此准备的、客户端已处理。
- **N6**：2.10 就停发移动 66：见 Q1。

### 9.3 照搬基线、不算差异

133 的字段与取值（`entity` = 场景实体号、transform 整体、velocity 恒在、`server_time_ms` = 墙钟毫秒、`move_state` 只有 0 / 1）；每条被接受的输入都会产生一次（合并前）；
不发给自己；130 只发给本人、`input_seq` 不设、reason 透传；瞬移 = 写位置、写朝向、清速度、`MoveGuard` / 落位等待、旧观察者销毁、自己旧视野销毁、新位置重新进场；
落位等待的 3 s / 3 m、先距离后时间、换场景作废、没有定时器、在战斗 / 冻结闸之后判；66 原样保留。

---

## 10 开放问题（各带推荐答案）

| # | 问题 | 推荐答案 |
|---|---|---|
| Q1 | 66 还发不发？ | **2.10 不动 66**（收件人、频率、`entity_id`、补发全保持）。理由：基线保留了 66（两版这一点一致）；本批的目标是修客户端可见缺口，停发是另一个有回归面的改动（`MovementSyncTest`、`PrepareBattleTest`、`InBattleGateMatrixTest` 与 robot `CrossNode` / `Mirror` / `BattleSettle` 都拿 66 作断言）；66 留着在本批里还能当 133 的旁证。代价：人群里每个客户端的下行约翻倍（推导：100 个移动中的可见者，133 约 31–62 KB/s，66 约 50 KB/s），真客户端每条 66 一行 `[unhandled]` 日志。**判据**：§11.5 重测里 2000 人基准的 p99 若因 133 超过 50 ms 帧预算，2.10 内改为停发移动 66（Java 的 66 只承载移动，停发 = 不再调 `syncAttributes`，测试改用 133 断言）；否则登记「两版同步评估停发移动 66」，与 mmorpg 一起决定（基线的 66 还承载 `combat_state_flags`，要不要拆是 mmorpg 的事） |
| Q2 | 133 当场发还是帧内合并？ | **帧开头兑现、同帧合并**（X2）。位置与基线相同、扇出有上限、只有一个兑现点 |
| Q3 | 新观察者：当场补发，还是「交给下一条 133」？ | **当场补发**（X3）。静止、只有朝向的目标不会有下一条 133；移动中的目标最多晚 250 ms 才动起来 |
| Q4 | 补发用 133 还是 135？ | **观察者一侧 135、目标一侧 133**（N5）。只有一个目标也发 135，规则统一 |
| Q5 | 瞬移的可见性：全部重置（基线）还是只改变化的部分（N1）？ | **全部重置**，但同步完成（X6） |
| Q6 | 瞬移原语要不要自己拒冻结 / 战斗在途？ | **要**（X7）；结果用枚举返回，调用方按需计数 |
| Q7 | 2.10 怎么端到端验证瞬移（生产里还没有调用方）？ | **加 dev 管理口**（§5.9，X9），robot `movement` 加瞬移段；不愿加接口的备选是只做单测、端到端留给 4.3b 的 robot team |
| Q8 | 130 的 `input_seq` 填什么？ | **不设（0）**，同基线。客户端只记日志（§2.3）；要填「最近一条被接受的输入序号」得在 `ScenePlayer` 上多记一个字段，等客户端真做回滚再说 |
| Q9 | 已经静止的人被「再停一次」要不要发 133？ | **与 66 同条件**：③⑤⑥ 在动才发（靠 I1），④ 无条件（现状）。不另设分支 |
| Q10 | 落位等待的半径 / 超时做成配置吗？ | **不做**，常量同基线（§6.2） |
| Q11 | 离场 / 被接管 / 停服前要不要像基线一样先发一条「停了」？ | **不发**（X8）；51 随即销毁 |
| Q12 | `ViewCrowdBenchmarkTest` 的口径怎么改？ | **加一段「133 兑现」**（与「66 扇出」「47 / 64 下发」并列打印），人群行为不变（每人每 250 ms 一条 132 → 每帧约 1/5 的人兑现）；另跑一次「66 关闭」作对照，给 Q1 的判据用。结论写回 `architecture.md` |
| Q13 | 2.10 要不要同时改基线（两版纪律）？ | **不需要**：基线已经有 133 / 瞬移（未编译）；Java 的差异 X2–X5 登记「mmorpg 可选」。基线编译通过后建议 mmorpg 补两处客户端可见的修正：AFK / 进战 / 交接冻结时清速并发 133（B-2）、新观察者补发（B-4） |

---

## 11 测试计划与 robot

### 11.1 基线测试对照

| 基线用例 | 性质 | Java 对应 |
|---|---|---|
| `aoi_system_test.cpp:303` `ResetEntityVisibilityRebuildsViewAfterSameHexTeleport` | 同格瞬移后重建 | §11.3 V2（短距离瞬移：交集先销毁后创建） |
| `:328` `ResetEntityVisibilityDropsOldNeighbours` | 远距离瞬移摘掉旧邻居 | §11.3 V1 |
| `:358` `ResetEntityVisibilityKeepsPinnedEntriesOnBothSides` | 钉住的条目不动 | 不适用（Java 没有钉住） |
| `:379` `ResetEntityVisibilityBeforeFirstUpdateIsHarmless` | 首次 Update 之前调用无害 | 不适用（Java 进场同步评估）；改为 §11.3 V6「刚进场就瞬移」 |
| `:1228` / `:1235` / `:1244` `TeleportReportVerdictTest` | 判定纯函数：远且未到期 → 过期；近 → 落位（到期前后都是）；远且到期 → 超时 | §11.4 S1–S3 逐条照写，加边界（恰好 3.0 m、恰好到期） |
| `:1204` / `:1211` / `:1218` `TeamRegroupRuleTest` | 归队判定 | 4.3b |
| 无 | 133 的内容、收件人、调用点 | §11.2（基线没有任何 133 用例） |

**以上基线用例都没跑过**（§1.9），对照只用来保证规则覆盖，不当作基线行为的证据。

### 11.2 133 / 135（`MovementSyncTest` 风格：假时钟、假链路、`steps(n)` 驱动帧；新类 `MovementBroadcastTest`）

- **T1 内容**：A 发 134 后，下一帧开头 B 收到一条 133：信封 `message_id = 133, id = 0`；`entity` = A 的 21 里的 `entity`（≠ player_id）；`transform.location` = 裁决位置（逐位相等，外推之前）；
  `rotation` = 上报值；`velocity` = 截断后的值（30 m/s → 10）；`move_state = 1`；`server_time_ms` = 假时钟的墙钟。131 → 速度存在且全零（字节 `1a 00`）、`move_state` 缺席。
  A 自己收不到任何 133。
- **T2 截断**：超出位移额度的上报：A 收 137（当场），B 收到的 133 位置 = 截断点。
- **T3 合并**：同一帧内 134 + 132 + 131 → 下一帧开头只有一条 133，内容是 131 之后的状态；跨帧的三条 → 三条。
- **T4 不产生 133**：非有限 / 越界（`INVALID`）、冻结中（DROP）、战斗在途的 134 / 132（DROP）；战斗在途、**静止**时的 131（STOP_ONLY 不停步）。
- **T5 停步点逐条**（每条断言「下一帧开头收到速度零的 133」与「之后静默」）：战斗在途、在动时的 131；`PrepareBattle`（`PrepareBattleTest`）；`attachFreeze`（迟到确认、进场恢复）；
  `haltForFreeze`（`HandOffTransferTest`、`ZoneTravelHandOffTest`：冻结后过一帧再提交 → 先 133 后 51；冻结后同一帧内就提交 → 只有 51）；挂机 600 帧（下一帧开头 133，之后不再有）。
- **T6 不发**：离场、被接管、停服、同节点换场景、交出提交——旁人只收 51（或新场景的 21），没有 133；换场景时挂着的待发被清掉（新场景不收到旧状态的 133）。
- **T7 顺序（C3）**：B 本帧走出 A 的视野：B 收 A 的 133（若有）在 64 之前，64 之后不再有 A 的 133；C 本帧走进：C 先收 47 再收 135；进场：B 先收 21 再收 133。
- **T8 没人看得见**：A 在没人的地方走，标记被清掉；之后 B 走近，B 只收到补发（当前状态），不收到积压的旧 133。
- **T9 补发**：进场者看见一个上报过朝向的静止目标 → 47 后一条 135（含该目标）；看见一个从没上报过朝向的静止目标 → 不补；进场者自己恢复了朝向且有人看得见它 → 他们收 21 后一条 133；
  帧内走进视野：观察者 47 后一条 135，只含 `hasMotionState()` 的目标。
- **T10 66 不变**：现有 66 用例（`:299` 挂机、`:339` 节奏、`:376` 字节、`:397` 积压、`:416` 新观察者、`:451` 走近走远）原样通过；`:621` 改名为「从不发 65 / 55 / 82 / 68 / 75」并去掉 133 / 135 / 130。

### 11.3 瞬移（新类 `TeleportWithinSceneTest`；`ViewIndexTest` 加两条）

- **V1 远距离**：A、B 相邻，C 在 50 m 外；把 A 瞬移到 C 旁：A 依次收 130（`entity`、`location` = 落点、`rotation`、`reason`、`input_seq` 缺席）→ 64{B} → 47{C} →（C 有朝向时）135{C}；
  B 收 64{A}；C 收 21{A}（位置 = 落点）→（A 有朝向时）133{A}。A 的速度为零、`MoveGuard` 锚点 = 落点、额度满、待发标记已清。
- **V2 短距离（交集）**：B 在瞬移前后都看得见 A：B 先 64{A} 后 21{A}；A 先 64{B} 后 47{B}。
- **V3 拒绝**：冻结中、战斗在途、落点越界 / 非有限、实例已不在 → 不改任何状态、不发任何消息，`teleports{result}` 各计一次。
- **V4 66**：瞬移后新观察者在下一个同步帧收到 A 的 66（`markFullStateForNewWatcher` 语义不变）。
- **V5 表满**：落点附近的人兴趣表都满：A 的 47 与 C 的 21 只包含能建立的那部分，镜像不变量（`ViewIndexTest`）成立。
- **V6 刚进场就瞬移**、**连续两次瞬移**（第二次以最后一次为准，落位目标被覆盖，`settles{voided}` 计一次）。
- **ViewIndexTest**：`Scene#teleport` 之后两张表互为镜像、格子位置正确、待刷新名单里没有残留。

### 11.4 落位等待（`MovementRulesTest` 纯函数 + `TeleportWithinSceneTest`）

- **S1–S3**（纯函数）：远（> 3 m）且未到期 → STALE；近（≤ 3 m，含恰好 3.0）→ SETTLED（到期前后都是）；远且 `now == deadline` / 之后 → EXPIRED。
- **S4**：瞬移后 3 s 内从旧位置来的 132：不改位置 / 速度 / 朝向，B（新位置的观察者）不收 133，A 不收 137，`moves{teleport_stale}` +1；131 同样丢弃。
- **S5**：落点 2 m 处的 134 → 照常裁决、等待解除；之后远处的上报按位移校验处理（锚点已在落点）。
- **S6**：3 s 后的远处上报 → 等待解除、照常裁决（被截断到额度并回 137）、WARN 一行、`settles{expired}` +1。
- **S7**：瞬移后换场景 → 等待作废，新场景里的上报照常；非有限输入仍计 `INVALID`（不经落位判定）。
- **S8 回归**：去掉落位等待时 S4 的那条上报会被截断并回 137 拉回——用例注释写明这条是落位等待存在的理由（对应 delta 条目的「否则会回 137 把人拉回去」）。

### 11.5 基准（`ViewCrowdBenchmarkTest`，`-Dgame.bench=true`）

人群不变（1000 / 2000 人在出生点 15 m 内、1–9 m/s、每人每 250 ms 一条 132）。输出加「D. 133 兑现」段（`broadcast{kind=actor_move}` 同口径），并跑一次 66 关闭的对照。
记录：整帧均值 / p99、三段各自耗时、`deliveries` 每秒对数。**判据**：2000 人 p99 ≤ 50 ms（现状 48.9 ms）；超出时按 Q1 处理。结果写回 `architecture.md` 与 PARITY「移动广播 133」行。

### 11.6 Java robot

- **`MessageIds`** 加 `notifyActorMove` / `notifyActorMoveList` / `notifyTeleport`（`MessageIdsTest` 同步）；**`MoveAssertions`** 加 133 / 135 / 130 的解析、按实体号归属、
  「135 展开成若干条 133」的统一视图（单测：`MoveAssertionsTest`）。
- **`movement` 模式**（`MovementScenario`），以 133 为主断言，66 原有断言保留为旁证：
  1. **行走**：每条输入后 B 在观察时限内收到 A 的 133——`entity` = A 的 21 里的实体号，位置 = 上报位置（robot 的输入在位移额度内，裁决 = 上报），`rotation` = FACING，
     `velocity` = 期望值（超速那条截到 10），`move_state` 1；Stop 那条速度全零、`move_state` 0；`server_time_ms` 与 robot 的 UTC 时钟相差在观察时限内。A 收不到自己的 133。
     停下后 1 s 静默窗口内没有 A 的 133。
  2. **重登（新观察者补发）**：A 断开重登，B 先收到 A 新实体的 21，之后收到同一实体的 133：`rotation` = FACING（`player_state.facing` 恢复）、速度零、`move_state` 0。
  3. **瞬移段**（经 `--scene-admin-url`，运维令牌同 audit；管理口 403 / 连不上时该段判失败并提示）：A、B 相邻 → 管理口把 A 挪到出生点 +50 m：
     A 收 130（`entity` = A 的实体号、`location` = 目标、`reason` = 3）、A 收含 B 的 64、B 收含 A 的 64；A 在 3 s 内从**旧位置**发一条 132（模拟在途的旧上报）→ A 不收 137；
     再从目标 +1 m 发 131 → A 不收 137（没有落位等待时，旧上报会被截断到离落点 24 m 处并当场回 137，锚点随之移走，这条 131 也会被截断回 137——
     两处「不收 137」就是落位等待的端到端证据）；
     再把 A 挪回 B 身边：A 收 130 与含 B 的 47，B 收 A 的 21 与随后的 133（朝向 = FACING）；之后 A 走一步，B 收到 133。
- **其它场景**：`CrossNode`、`Mirror`、`BattleSettle` 的 66 断言不动（66 不变）。`BattleSettle` 第 3 步若要加 133 旁证，必须先让 A 在动（静止的人备战也会发 133，但「在动 → 停」才说明问题）。
- 4.3b 在 `TeamScenario` 加「入队后 B 出现在 A 的场景并收到 130 reason 5」，复用本批的工具。

### 11.7 跨实现（Go robot / 基线）

Go robot 的 133 / 135 / 130 处理器是空函数（§1.9），**不能做任何断言**；拿它连 Java 只能证明「多出来的 133 / 135 / 130 不让它出错」（处理器已注册、解析后丢弃）。
基线 C++ 未编译，没有可以对拍的运行。跨实现的一致性只按客户端可见形状核对：§2.6 的 C1–C7 两版都满足（基线 C4 在 AFK / 进战 / 交接冻结三处不满足，B-2）。

### 11.8 真客户端验收（两台 Unity 客户端 `f537079` 连 Java 本机切片，需要用户在有 Unity 的机器上手工跑）

1. A 点地走、拐弯、停下：B 屏幕上 A 跟着走、停在停止点（此前 A 不动）。
2. A 静止并朝向某处，B 之后登录：B 看到的 A 朝向正确（补发）；A 在远处走、走进 B 的视野：进视野即在动（补发 135）。
3. A 走着时 A 发起跨节点换图（双 scene 节点切片）：B 上的 A 在冻结那一刻停下，随后消失。
4. A 走着时断网（不是正常退出）：B 上的 A 继续按服务器外推走，30 s 后停下（挂机停推的 133）；gate 判定断线后消失。
5. 管理口瞬移 A：A 吸附到落点、继续点地 / WASD 不被拉回；B（旧位置）上的 A 消失；C（新位置）上的 A 出现且朝向正确。
6. 两端日志：没有 `[unhandled] message_id=133 / 135 / 130`；`[unhandled] message_id=66` 仍在（Q1）。

### 11.9 回归面

`mvn -o -B -fae clean install` 全量（当前 9891 条 0 失败）；重点模块 game-scene、game-robot、game-api。改动只增不删现有断言（除 `MovementSyncTest.java:621` 的用例改名与去掉 133 / 135 / 130 那半句），
其余 66 / 视野 / 冻结 / 战斗用例原样通过是「66 不变、视野规则不变」的证据。
