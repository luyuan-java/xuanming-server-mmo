# 移动：客户端可见行为参考（基线 mmorpg@9c9c012b7）

> 源码一律按 commit 读：`git -C <mmorpg> show 9c9c012b7:<路径>`，下文路径都相对 mmorpg 根目录。
> 移动相关的契约面（`player_movement.proto`、`player_state_attribute_sync.proto`、移动 handler、`spatial/`、属性同步、
> robot 的 AI 与 handler、MessageLimiter 数据）在 `766cb037c..9c9c012b7` 之间**没有改动**，所以本文与
> `mmorpg-client-contract-scene.md`（基线 766cb037c）不冲突，两份一起读。
> 范围：`SceneMovementClientPlayer` 服务的全部 8 个方法、它们在服务器上的处理、位置怎么广播给别人、离场存盘，
> 以及 Go robot 的 AI「move」动作实际发什么。只写客户端能观察到的部分。

---

## 0. 先看结论（最容易想错的五件事）

1. **基线只真正实现了三条上行**：134 MoveStart / 132 MoveSync / 131 MoveStop。它们的应答类型都是 `Empty`，**永不回包**，被拒也不回。
2. **下行只会发 137 `MoveAckS2C`**，而且只在「服务器裁决位置与上报位置水平差 > 0.5m」时发给**本人**。
   133 `ActorMoveS2C`、135 `ActorMoveListS2C`、130 `TeleportS2C` **全仓没有任何发送方**。136 TeleportRequest 是空桩。
3. **别人看到你在动，靠的是 66 `ActorBaseAttributesS2C`（属性同步通道）**，不是 133。每 2 帧（100ms）按脏位广播一次。
4. **66 在移动时不带 `entity_id`**（基线缺口，PROGRESS.md 的 P1-d 只修了战斗状态那一路）：观察者收到的是一条「不知道是谁」的位置更新。
5. **Go robot 的 AI「move」动作不发任何移动包**：它发的是 43 `SceneInfoC2S`（空请求体），坐标只改机器人本地变量。
   单 zone 下没有任何 robot 模式会发 131/132/134；只有需要两个 zone 的 travel-smoke 会发 134 + 131 各一条。

---

## 1. 坐标、单位与编码约定

- **服务器坐标系 Z-up**：x 前、y 右、z 上；**水平面是 x/y**，z 是高度。单位米、秒。
  与客户端（Unity，Y-up）换轴：`server = (unity.z, unity.x, unity.y)`（`cpp/libs/services/scene/spatial/system/nav_query.h:13-18`、
  `spatial/constants/nav.h:22-37`）。换轴由客户端做，线上传的全是服务器坐标。
- `Location` / `Rotation` / `Scale` / `Velocity` / `Acceleration` / `Vector3` 都是 `{double x=1; double y=2; double z=3;}`
  （`proto/common/component/actor_comp.proto:7-47`、`base_comp.proto:6-11`）。线上是 wire type 1（fixed64，小端 IEEE-754），
  proto3 下值为 0.0 的分量不编码。**四者线上形状完全相同**，只是类型名不同；`Transform.location` 的类型是 `Vector3`，
  移动协议字段用 `Location`，服务器在边界逐分量拷贝（`player_movement_handler.cpp:34-51`）。
- `Rotation`：移动路径上服务器**不解释**它，原样存进 `Transform.rotation` 再原样广播。
  （技能 `LookAtPosition` 按 z-up 写 rotation：yaw 在 `z`、pitch 在 `x`，见 PROGRESS.md P2-a；移动不涉及。）
- `Velocity`：运动学矢量（方向 × 速率，米/秒），**全零 = 停止**。
- 时间：`client_time_ms` 是客户端单调毫秒，服务器**完全不读**；`server_time_ms` 是服务器 **UTC Unix 毫秒**
  （`TimeSystem::NowMilliseconds` = `universal_time`，`cpp/libs/engine/core/time/system/time.cpp:27-29`）。
- proto 全名：`player_movement.proto` 与 `actor_comp.proto` 都**没有 `package`**，全名就是裸名：
  `MoveStartC2S`、`MoveAckS2C`、`Location`、`Transform`……服务名 `SceneMovementClientPlayer`
  （`OptionIsPlayerService=true`、`OptionIsClientProtocolService=true`，由 scene 处理）。Java 生成类在 `com.game.proto`。

---

## 2. 消息清单

消息号来自 `proto/message_id.txt:131-138`；限频来自 `generated/tables/messagelimiter.json`（缺省每秒 3 条）。

| id | 名称 | 方向 | 请求 → 应答类型 | 基线实际行为 | gate 限频 |
|---|---|---|---|---|---|
| **134** | SceneMovementClientPlayerMoveStart | C→S | `MoveStartC2S` → `Empty` | 裁决位置 + 截断速度，不回包 | 15 / 1s |
| **132** | SceneMovementClientPlayerMoveSync | C→S | `MoveSyncC2S` → `Empty` | 同上，不回包 | 20 / 1s |
| **131** | SceneMovementClientPlayerMoveStop | C→S | `MoveStopC2S` → `Empty` | 裁决位置 + 速度清零，不回包 | 15 / 1s |
| 136 | SceneMovementClientPlayerTeleportRequest | C→S | `TeleportRequestC2S` → `TeleportRequestC2SResponse` | **空桩**：回 `error_message` 为空 tip（id=0），什么都不做 | 缺省 3 / 1s |
| **137** | SceneMovementClientPlayerNotifyMoveAck | S→C | `MoveAckS2C` | 仅纠偏时发给本人 | — |
| 133 | SceneMovementClientPlayerNotifyActorMove | S→C | `ActorMoveS2C` | **从不发** | — |
| 135 | SceneMovementClientPlayerNotifyActorMoveList | S→C | `ActorMoveListS2C` | **从不发** | — |
| 130 | SceneMovementClientPlayerNotifyTeleport | S→C | `TeleportS2C` | **从不发** | — |

相关的非本服务消息：

| id | 名称 | 与移动的关系 |
|---|---|---|
| **66** | ScenePlayerSyncSyncBaseAttribute（`ActorBaseAttributesS2C`） | **位置 / 速度广播给视野内其他玩家的唯一通道**（§6.1） |
| 47 / 64 | NotifyActorListCreate / NotifyActorListDestroy | 移动跨 AOI 格时发给**移动者本人**（§6.2） |
| 21 / 51 | NotifyActorCreate / NotifyActorDestroy | 进场 / 离场；`ActorCreateS2C.transform` 只带 location |
| 43 → 31 | SceneInfoC2S → NotifySceneInfo | robot AI「move」动作实际发的就是 43（§8.1） |
| 65/55/82/68/75 | SyncAttribute{2,5,10,30,60}Frames | 全仓没有置它们脏位的代码，**从不发** |

客户端发来的 130/133/135/137（S2C 方向的方法号）也会被 gate 当作客户端协议转给 scene，
scene 调到空 handler、应答是 `Empty`，结果是**静默无动作**（`player_movement_handler.cpp:224-254`）。

---

## 3. 字段逐条（`proto/scene/player_movement.proto`）

### 3.1 `MoveStartC2S`（134）

| # | 字段 | 类型 | 含义 | 基线服务器是否使用 |
|---|---|---|---|---|
| 1 | `start_location` | Location | 按下移动时客户端所在位置 | **用**：当作上报位置裁决（§4.3） |
| 2 | `rotation` | Rotation | 朝向 | **用**：原样覆盖 `Transform.rotation`（缺省即全零） |
| 3 | `velocity` | Velocity | 期望速度矢量，m/s | **用**：按 10 m/s 截断后写入（§4.4） |
| 4 | `target_location` | Location | 点地移动目的地，自由移动时为零 | 不用 |
| 5 | `client_time_ms` | uint64 | 客户端单调毫秒 | 不用 |
| 6 | `input_seq` | uint32 | 单调递增输入序号 | 只在回 137 时原样回显 |

### 3.2 `MoveSyncC2S`（132，proto 注释：移动中约每 250ms 一条）

| # | 字段 | 类型 | 使用 |
|---|---|---|---|
| 1 | `location` | Location | 上报位置，裁决 |
| 2 | `rotation` | Rotation | 覆盖 `Transform.rotation` |
| 3 | `velocity` | Velocity | 截断后写入 |
| 4 | `client_time_ms` | uint64 | 不用 |
| 5 | `input_seq` | uint32 | 137 回显 |

### 3.3 `MoveStopC2S`（131）

| # | 字段 | 类型 | 使用 |
|---|---|---|---|
| 1 | `end_location` | Location | 上报位置，裁决 |
| 2 | `rotation` | Rotation | 覆盖 `Transform.rotation` |
| 3 | `client_time_ms` | uint64 | 不用 |
| 4 | `input_seq` | uint32 | 137 回显 |

### 3.4 `TeleportRequestC2S`（136）/ `TeleportRequestC2SResponse`

- 请求：`uint32 teleport_table_id = 1; Location target_location = 2;`——基线全不读（handler 空，`player_movement_handler.cpp:216-222`）。
- 应答：`TipInfoMessage error_message = 1;`。生成的 `CallMethod` 对这一个方法执行 `TRANSFER_ERROR_MESSAGE`
  （`player_movement_handler.h:67-74`，宏在 `cpp/libs/engine/core/macros/return_define.h:67-76`），
  所以**应答里 `error_message` 总是存在且 `id=0`**，线上 body 为 `0a 00`；信封 `message_id=136`、`id=请求 seq`。
  之后**不会**有 130。

### 3.5 `MoveAckS2C`（137）

| # | 字段 | 类型 | 基线填法（`player_movement_handler.cpp:135-145`） |
|---|---|---|---|
| 1 | `input_seq` | uint32 | 触发纠偏的那条输入的 `input_seq` |
| 2 | `server_location` | Location | 服务器裁决后的位置（有导航时恒在网格上） |
| 3 | `server_velocity` | Velocity | 实体上**此刻**的 Velocity 组件——注意在 MoveStart/MoveSync 里位置先于速度处理，所以这是**本条输入之前**的速度；实体从没有过 Velocity 组件时字段缺席 |
| 4 | `server_time_ms` | uint64 | UTC Unix 毫秒 |

信封：`SendMessageToClientViaGate` 单播，`message_id=137`、`id=0`（`cpp/libs/engine/core/network/player_message_utils.cpp:60-80`）。

### 3.6 `ActorMoveS2C` / `ActorMoveListS2C` / `TeleportS2C`（基线从不发，仅列形状）

- `ActorMoveS2C{uint64 entity=1; Transform transform=2; Velocity velocity=3; uint64 server_time_ms=4; uint32 move_state=5;}`，
  `move_state`：0 停 / 1 走 / 2 跑 / 3 冲刺。
- `ActorMoveListS2C{repeated ActorMoveS2C moves=1;}`
- `TeleportS2C{uint64 entity=1; Transform transform=2; uint32 input_seq=3; uint32 reason=4;}`，
  `reason`：0 服务器纠正 / 1 传送门 / 2 技能 / 3 管理员 / 4 复活；proto 注释要求客户端硬 snap 并丢弃 `<= input_seq` 的预测输入。

### 3.7 `ActorBaseAttributesS2C`（66，`proto/scene/player_state_attribute_sync.proto:15-23`）

| # | 字段 | 类型 | 移动时的内容 |
|---|---|---|---|
| 1 | `entity_id` | uint64 | **移动触发的 66 不带**（§6.1）；带的时候取的是玩家 Guid（= player_id），不是 ECS 号 |
| 2 | `transform` | Transform | 整个 Transform：`location`（Vector3）+ `rotation`（移动输入写过就存在，哪怕全零）；`scale` 从不设置 |
| 3 | `velocity` | Velocity | 仅在速度脏时带；**停止时是「存在但全零」的空子消息 `1a 00`** |
| 4 | `combat_state_flags` | CombatStateFlagsComp | 与移动无关（战斗状态重算时才带，那时会连带 `entity_id`） |

---

## 4. 服务器如何处理上行移动

### 4.1 进 scene 之前（gate）

- **按消息号限频**（`cpp/nodes/gate/handler/rpc/client_message_processor.cpp:300-339`，`message_limiter.cpp`）：
  134→15、132→20、131→15 条 / 1s 窗口；136、43 不在表里，缺省 3 条 / 1s。
  超频：gate 直接回 `MessageContent{message_id=原 id, id=请求 seq, error_message{id=1008 kRateLimitExceeded}}`，不转发，
  并计一次非法包（阈值 `GATE_ILLEGAL_PACKET_THRESHOLD`，缺省 50，到阈值 `forceClose`）。
  **表里的 `tip_message` 列（值 1000 = kSuccess）不被读取**，拒绝码恒为 1008。Java gate 已按同一份表对齐（PARITY「gate 按消息号限频」行）。

### 4.2 进 scene 之后的通用闸（`cpp/nodes/scene/handler/rpc/scene_handler.cpp:373-575`）

- 未知消息号 / 会话不符 / 实体加载中 / 会话已被取代 / 请求体解析失败：**静默丢弃**（同 scene 参考文档 §4.5）。
- 任何一条客户端消息（退出中的除外）都会刷新 `LastActiveFrameComp`（`:455-461`），这是 AFK 判定的唯一输入（§5）。
- 字段校验只查超长与**有符号整数为负**（`proto_field_checker.cpp:46-94`）。移动消息里没有有符号整数，
  **负坐标合法**；**NaN / ±Inf 不做任何检查**（基线缺口，§9）。
- 退出中（`UnregisterPlayer`）的实体：拒绝码 1006 只能写进带 `error_message` 的应答，`Empty` 应答不回包
  → 131/132/134 **静默丢弃**，136 回 `error_message{1006}`（`:488-507`、`:559-575`）。

### 4.3 三条输入的顺序与分支（`player_movement_handler.cpp:159-214`）

| 方法 | 回合制战斗在途（`InBattleComp`）或归属交接冻结（`PlayerFrozenComp`） | 正常 |
|---|---|---|
| MoveStart | 静默丢弃，不打日志 | `ApplyReportedLocation(start_location, rotation, input_seq)` → `ApplyReportedVelocity(velocity)` |
| MoveSync | 静默丢弃 | `ApplyReportedLocation(location, …)` → `ApplyReportedVelocity(velocity)` |
| MoveStop | **只把速度清零**（不收位置） | `ApplyReportedLocation(end_location, …)` → 速度清零 |

**位置裁决 `ApplyReportedLocation`**（`:82-154`，导航接口见 `spatial/system/nav_query.h:19-42`）：

1. 当前位置 = `Transform.location`，上报位置 = 请求里的 Location。
2. 场景**没有导航网格**（`GetNavForPlayer` 为空）：**fail-open，原样接受**上报位置（x、y、z 全收）。
3. 有导航网格：
   - `ValidateMove(当前→上报)` 全程可走：接受吸附到网格后的终点（高度被吸附）。
   - 中途撞墙且当前点在网格上：接受**阻挡点**（沿途最后一个合法点，回退 0.05m）——`verdict=blocked`。
   - 当前点不在网格上：上报点能吸附就用吸附点（`guided`）；两头都不在网格上就落到本场景出生点（`respawned`，`scene_spawn.cpp:151-164`）。
   - 吸附搜索范围：水平 ±2m、垂直 ±4m。
4. 写入 `Transform.location = 裁决位置`、`Transform.rotation = 请求 rotation`（**无条件覆盖**，请求没带就是全零），置 Transform 脏位。
5. 若 `水平距离(裁决位置, 上报位置) > kMoveCorrectionEpsilon = 0.5m`（只算 x/y，`nav.h:19`），给本人回 **137**。
   没有导航网格时两者恒相等，**永远不回 137**。

**速度处理 `ApplyReportedVelocity`**（`:56-70`）：

- 三维模长 `|v| = sqrt(x²+y²+z²)`；`|v| > kMaxTrustedClientSpeed = 10.0 m/s`（`nav.h:10`）时**等比缩放到 10**，方向不变；否则原样。
- 写入 Velocity 组件并置 Velocity 脏位（即使值没变也置）。
- 10 m/s 的来历：客户端基础移速 9（`TianyongMapConfig.moveSpeed`）+ 约 10% 抖动余量。

### 4.4 基线**没有**做的校验（「反作弊」的实际边界）

- **没有位移 / 时间校验**：上报位置离当前多远都行，只要导航射线不撞墙（无导航时连这个都没有）。瞬移外挂在无导航场景完全放行。
- **没有表驱动的移速上限**：Buff 表的 `movement_speed_boost` / `movement_speed_reduction` 会被算进 `MoveSpeedComp`
  （`actor_attribute_calculator.cpp:29-44`），但移动校验不读它；上限就是常量 10。
- 不看 `client_time_ms` 单调性，不看 `input_seq` 单调性 / 重复，不看 `target_location`，没有违规计数、没有踢人。
  （`docs/design/scene-navmesh-pipeline.md` §5 原话：反作弊只有速度截断 + 阻挡夹持。）
- 移动永远不产生 tip 码：上行应答都是 `Empty`。
- 注：PROGRESS.md 2026-08-26 条目写的纠偏阈值是 0.25m，**代码是 0.5m**（`nav.h:12-19` 注释说明了改动理由），以代码为准。
  同一设计文档记录客户端现状：移动中 <1.5m 的 137 不应用，>1.5m 硬 snap。

### 4.5 拒绝与后果一览

| 情形 | 客户端看到什么 |
|---|---|
| 超频（gate） | `MessageContent{message_id, id, error_message{1008}}`；累计 50 次断开 |
| 退出中 | 131/132/134 无；136 回 `error_message{1006}` |
| 战斗在途 / 交接冻结 | 无（MoveStop 仍把速度清零，随后别人会收到一条速度为零的 66） |
| 撞墙 / 离网格（有导航） | 137，`server_location` 为阻挡点 / 吸附点 / 出生点 |
| 速度超 10 m/s | 无回包；服务器按截断后的速度外推，别人收到的 66 里 velocity 是截断值 |
| 136 任意请求 | `TeleportRequestC2SResponse{error_message{}}`（id=0），此后什么都不发生 |

---

## 5. tick 与服务器侧位置推进（`cpp/libs/services/scene/world/world.cpp`）

- 固定步长 **20 FPS**，`delta = 0.05s`（`core/constants/fps.h`）。每次 `World::Update` 按累加器补帧，**最多 5 步**，
  累加器夹在 ±1s，超出的模拟时间直接丢弃。
- 每步顺序：`AfkSystem → AoiSystem → MovementSystem → MovementAccelerationSystem → BuffSystem →
  ActorAttributeCalculatorSystem → ActorStateAttributeSyncSystem`，然后 `current_frame + 1`。
  AOI 在移动**之前**跑：本步积分出的新位置下一步才进格子判定。
- **服务器外推（dead reckoning）**（`spatial/system/movement.cpp:35-88`）：对有 Transform + 非零 Velocity 的实体，
  每步 `location += velocity × 0.05`；有导航时 `ValidateMove` 夹持，撞墙就停在阻挡点并**把速度清零**（置 Velocity 脏位）。
  每步都置 Transform 脏位。下一条 MoveSync 到来时直接用上报位置覆盖外推结果。
- 排除：`Acceleration`（另一系统处理，玩家平时没有）、`AfkComp`、`PlayerFrozenComp`、`InBattleComp`。
- **AFK**（`player/system/afk.cpp:14-58`）：连续 600 帧（30s）没有任何客户端消息 → 挂 `AfkComp`，**停止外推**；
  收到任一客户端消息后的下一步摘掉。AOI 与属性同步**不**排除 AFK。
  后果：客户端发了 MoveStart 之后 30s 不发任何包，服务器在 30s 处停住它。
- 客户端上报的处理发生在两个 tick 之间的网络回调里，与 tick 同一线程（Java：场景逻辑线程）。

---

## 6. 位置如何广播给其他玩家

### 6.1 66 `ActorBaseAttributesS2C`（唯一的位置广播）

- **谁在发**：`ActorStateAttributeSyncSystem::Update`（`actor/attribute/system/actor_state_attribute_sync.cpp:56-113`）
  对每个有 Transform、未冻结、**兴趣列表（`AoiListComp`）非空**的实体，在 `current_frame % 2 == 0` 时调一次
  `ActorBaseAttributesS2CSyncAttributes`（`:84-86`）。
- **发什么**（生成的序列化器 `generated/attribute/actorbaseattributess2c_attribute_sync.cpp:13-74`）：
  只写脏位对应的字段，写完即清脏位；一个字段都不脏就不发。
  移动相关的脏位来源只有四处：移动 handler（Transform、Velocity）、`MovementSystem`（每步 Transform；撞墙时 Velocity）、
  进场落位 `EnsureValidEnterLocation`（Transform）、退出 `StopMotionForExit`（Velocity）。
- **`entity_id` 缺席**：`entity_id` 脏位全仓只在 `ResetCombatStateFlags` 里置（`actor_attribute_calculator.cpp:78-83`），
  移动从不置。所以纯移动产生的 66 只有 `transform`（和 `velocity`），**观察者无法据此判断是谁在动**。
  带 `entity_id` 时它取自 `Guid` 组件（`using Guid = uint64_t`，值 = player_id），客户端按 `ActorCreateS2C.guid` 归属。
- **发给谁**：该实体兴趣列表里的全部实体（不按距离再筛），**不含移动者自己**；NPC 没有会话，被跳过（打一条 ERROR 日志）。
- **批量方式**（`player_message_utils.cpp:109-177`）：消息只序列化一次，按目标所在 gate 分组，
  每个 gate 一条 `GateBroadcastToPlayers`（内部 id 32），gate 再逐会话下发。客户端收到的信封是
  `MessageContent{message_id=66, id=0, serialized_message}`。
- **频率**：
  - 移动中（速度非零）：Transform 每步都脏 → **每 100ms 一条**（10 Hz）。
  - 每条 MoveStart/MoveSync 都置 Velocity 脏位 → 其后的第一条 66 带 `velocity`；其余只带 `transform`。
  - MoveStop 后：再发**一条**带 `transform` + 全零 `velocity` 的 66，然后静默，直到下一次输入。
  - 静止且无输入：不发。
- **示例字节**（观察者收到的静止通知，位置 (180,200,0)，移动输入写过全零 rotation）：
  ```
  12 16                               transform, 22 字节
     0a 12                            location(Vector3), 18 字节
        09 00 00 00 00 00 80 66 40    x = 180.0
        11 00 00 00 00 00 00 69 40    y = 200.0      （z = 0 不编码）
     12 00                            rotation = {}（存在但全零）
  1a 00                               velocity = {}（停止）
  ```

### 6.2 移动引起的视野变化（`spatial/system/aoi.cpp:40-180`、`grid.cpp:12-38`、`view.cpp:28-86,175-198`）

- 格子：**平顶六边形，size=20（米）**，原点 (0,0)，用 **x/y** 计算所在格；视野 = 当前格 + 6 个相邻格。
- 可见判定 `CanSee`：**三维**距离 ≤ 观察者 `ViewRadius`（玩家进场时设为 10，缺省常量 `kMaxViewRadius=10`），隐身另判。
- 移动者跨格时（`hex_distance ≠ 0`）：
  - 新进入的格子里、移动者 `CanSee` 的实体 → 加入移动者兴趣列表，**给移动者推 47 `ActorListCreateS2C`**
    （每项同 21 的填法，`transform` 只带 location）。
  - 离开的格子里、在移动者兴趣列表中（非 pinned）的实体 → 移除，**给移动者推 64 `ActorListDestroyS2C{repeated uint64 entity}`**（ECS 号）。
  - 反方向（对方看不看得见移动者）只改对方的兴趣列表，**不给对方发任何消息**（没有 21/47/64/51）。
- 可见性**只在「进入格子」那一刻**判一次距离：同一组格子内彼此走近，不会重新判定、不会补发 47；
  66 却会发给兴趣列表里所有人，不管当前距离。
- 格子内移动（没跨格）不产生任何 AOI 消息。

### 6.3 其它

- 基线没有「给本人确认位置」的常规下行；本人只在纠偏时收到 137。
- 21/47 的 `ActorCreateS2C.transform` 只填 `location`，不填 rotation；观察者只能从后续的 66 拿朝向。
- 同图换线（63）保留坐标，换图落到目标出生点；新位置随新的 79/21 下发，**不发 130**（见 scene 参考文档 §4.3）。

---

## 7. 离场时的位置持久化与再进场

- **退出顺序**（`player/system/player_lifecycle.cpp:1979-1991`）：`StopMotionForExit`（速度、加速度清零，置 Velocity 脏位，`:1862-1884`）
  → `DetachFromScene`（向当前格 + 相邻格广播 51）→ 登出快照 → `SavePlayerToRedis`。速度必须先清，否则外推会让落盘值追不上内存。
- **存什么**：`player_database.transform`（字段 2，`proto/common/database/mysql_database_table.proto:110`）= 整个 Transform
  （location 含 z、rotation；scale 无）（`player/system/player_database_loader.cpp:141`；加载见 `:85`）。**速度不存**。
- **周期存盘**：缺省每 300s 一轮（`SCENE_PLAYER_SAVE_INTERVAL_SECONDS`，0 = 关），按 `playerId % 300` 分槽每秒存一部分
  （`core/system/redis.cpp:99-160`）。scene 崩溃最多丢 300s 的位置。
- **再进场**（`spatial/system/scene_spawn.cpp:91-149`）：同图、坐标在网格上就保留（高度吸附）；不在网格上或为 (0,0,0) → 出生点；
  无导航网格的场景只修 (0,0,0)。换图一律出生点。出生点取 BaseScene 表 `spawn_x/y/z`，缺省 (180,200,0)。
- Java 现状：只在离场时写回，没有周期存盘（`docs/design/architecture.md` §7）；这对客户端不可见，只影响崩溃时的丢失窗口。

---

## 8. Go robot

### 8.1 AI「move」动作（`robot/logic/ai/robot_ai.go:176-193`）

```go
dx := rand.Float64()*30 - 15; dz := rand.Float64()*30 - 15
ai.posX += dx; ai.posZ += dz                       // 只改本地变量，从不上报
ai.client.SendRequest(game.SceneSceneClientPlayerSceneInfoC2SMessageId, &scene.SceneInfoRequest{})
```

- 线上只有一条 **43 `SceneInfoRequest{}`**（空请求体）。注释写的是「如果服务器支持，就用 SceneInfoC2S 报告新位置」，
  但请求里根本没有位置字段。
- 服务器：43 应答是 `Empty`，不回包；改推 **31 `SceneInfoS2C{repeated SceneInfoComp scene_info=1}`**（一项，当前场景）。
  robot 的 31 handler 为空（`robot/logic/handler/scene_scene_client_player_notify_scene_info.go`）。
- **检查什么：什么都不检查**。发送成功就记一条 `action=move, success=true, detail="pos=(x,z)"` 的行为记录
  （退出时导出 `behavior_test_results.csv` / `.jsonl`，`robot/main.go:96-97,246-247`），并 `msg_sent+1`。不等任何应答。
- robot 的 8 个移动 handler 全部为空函数（`robot/logic/handler/scene_movement_client_player_*.go`），
  137/133/135/130 收到也只是解析后丢弃；66 不在 handler 表里（`message_body_handler.go:86-93` 没有它），收到打 `Unhandled message` 日志。
- `posX/posZ` 是 Y-up 习惯的命名，另被 ReleaseSkill 的 `position={posX±10, 0, posZ±10}` 使用（posX/posZ 初值 0，与服务器坐标无关）。

### 8.2 哪些 profile 会选到 move（`robot/logic/ai/action.go:69-118`）

| profile | move 权重 |
|---|---|
| stress（缺省、robot_smoke 用的） | **0**（只有 cast_skill 85 / switch_scene 15） |
| fighter / explorer / chatter / behavioral | 20 / 40 / 20 / 25 |
| `custom_weights` | 优先级最高（`robot/config/config.go:477-494`），动作名 `idle / move / cast_skill / switch_scene / chat`，未知名字被忽略 |

### 8.3 唯一真正发移动包的地方：travel-smoke 的移动探针

- `robot/travel_smoke_scenario.go:919-957` `probeMove`：在访客 zone 里发
  **134 `MoveStartC2S{start_location={x:100000, y:100000}, client_time_ms=now, input_seq=n}`**，紧接着
  **131 `MoveStopC2S{end_location=同上, client_time_ms, input_seq=n+1}`**（不带 rotation / velocity），然后 3s 内等 137。
- 坐标故意远在地图外：有导航网格必然纠偏回 137；无导航网格 fail-open 原样接受并存盘。
- `require_move_ack` 缺省 false（`robot/etc/travel_smoke.yaml`），没收到 137 也不算失败。
- 这个模式需要两个 zone、共享 Redis、GM 放行，**Java 版目前跑不了**。

**结论**：单 zone 下现有 robot 无法驱动 131/132/134。Java 的移动实现要靠单元测试（handler 分支、截断、外推、66 内容与节奏、
跨格 47/64）验证；若要 robot 端到端覆盖，需要先在 mmorpg 侧给 robot 加一个真正发 MoveStart/MoveSync/MoveStop 的动作（两版同批做）。

---

## 9. 基线怪癖与缺口（Java 可以修，但要确认不破坏客户端）

1. **66 不带 `entity_id`**：建议 Java 每条 66 都填 `entity_id = 被同步者的 player_id`（与 P1-d 的修法同一规则，
   客户端按 `ActorCreateS2C.guid` 归属；字段是加出来的，旧客户端忽略也不出错）。在 PARITY 登记，并提醒 mmorpg 侧同步修。
2. **别人看不到你走进 / 走出他的视野**（只有移动者收 47/64）：建议 Java 给对方补发 21（或 47）与 64/51。robot 能处理这些消息。
3. **可见性只在进格时判一次**：同格内走近不补发、走远不销毁。Java 若做「按距离实时增删」是更正确的行为，客户端收到的仍是 47/64。
4. **137 的 `server_velocity` 是本条输入之前的速度**：Java 建议填裁决后的速度（客户端据此重演预测才对）；不确定时照抄基线也无害。
5. **不检查 NaN / Inf**：基线会把 NaN 写进 Transform 并广播出去。Java 应对非有限值**整条静默丢弃**（正常客户端不会发）。
6. **没有位移 / 时间校验**：Java 可选择加（例如：本次与上次接受位置的水平距离 ≤ 10 m/s × 服务器单调时钟间隔 × 1.2 + 1m，
   超出就按上限截断并回 137）。这比基线严格，必须写进 PARITY 并确认客户端对 137 的处理（>1.5m 硬 snap）。
7. **136 返回「成功」但什么都不做**：Java 当前对未实现方法回 `error_message{1006}`（`ClientRequestHandler.replyUnavailable`），
   比基线诚实；robot 从不发 136。建议保留 1006 并在 PARITY 登记为有意差异。
8. **MoveStop 在战斗 / 冻结中仍清速度**：保留，这是收敛动作。
9. **rotation 无条件覆盖**：请求不带 rotation 时朝向被清零。建议照抄（客户端每条输入都带朝向）。
10. **限频表的 `tip_message` 列无效**：拒绝码恒为 1008。Java 已对齐。

---

## 10. Java 必须做到（清单）

- [ ] 134 / 132 / 131 在 scene 逻辑线程处理，**永不回包**（包括被拒）；客户端发来的 130/133/135/137 静默忽略。
- [ ] 先处理位置、后处理速度；MoveStop 把速度清零；朝向用请求里的 `rotation` 覆盖。
- [ ] 速度按三维模长截断到 **10 m/s**（等比缩放，方向不变）。
- [ ] 没有导航网格时 **fail-open 原样接受**上报位置（x/y/z）——这也是基线在无导航场景的行为；此时不发 137。
- [ ] 任何让裁决位置偏离上报位置的逻辑（越界、非有限值之外的修正、可选的位移校验），只要**水平距离 > 0.5m** 就给本人发 **137**：
      `MoveAckS2C{input_seq=回显, server_location=裁决位置, server_velocity, server_time_ms=UTC 毫秒}`，信封 `message_id=137, id=0`。
- [ ] 非有限坐标 / 速度：整条丢弃（建议）。
- [ ] 服务器外推：20 FPS 固定步长，速度非零时每 50ms `location += v × 0.05`；30s 无任何客户端消息停止外推。
- [ ] 广播 **66**：每 100ms 至多一条，只发脏字段，发给视野内其他玩家（不含自己），移动中带 `transform`，
      每次输入后带一次 `velocity`，停止时带一次全零 `velocity`（空子消息）；建议同时带 `entity_id = player_id`。
- [ ] 跨 AOI 格时给移动者推 47 / 64（建议同时给对方补发，见 §9 第 2 条）。
- [ ] **不发** 133 / 135 / 130（基线从不发；要启用须先在 mmorpg 侧落地并确认客户端处理）。
- [ ] 136 回 `TeleportRequestC2SResponse`，`error_message` 字段必须存在（基线 id=0；Java 若保留 1006 须登记 PARITY）。
- [ ] gate 限频按 MessageLimiter 表：134→15、132→20、131→15、136/43→缺省 3（每秒）；超频回 1008 并计非法包。
- [ ] 离场（主动 / 断线 / 被接管 / 停服）：先清速度再写回；写回 location（含 z）与 rotation；再进场按 scene 参考文档 §1 的落位规则。
- [ ] 离场写回 / 归属交接 / 退出中收到的移动输入：丢弃，不改 Transform。
- [ ] robot 的「move」= 43：继续推 31（Java 已实现）；43 不回包。

---

## 11. 让 robot 跑「move」动作的配置（Java 版，本地临时文件，不入库）

```yaml
# 连 Java 版：xm-gateway 缺省端口 18081；连 mmorpg 用 8081
gateway_addr: "http://127.0.0.1:18081"
zone_id: 1
robot_count: 3
account_fmt: "robot_%04d"
password: "<与 xm-login 的 XM_LOGIN_DEV_PASSWORD 相同>"   # 不要把真实值写进任何仓库
auth_type: "password"
skill_ids: []
table_dir: "../generated/tables"   # 相对 mmorpg/robot 目录运行
action_interval: 1                 # 每秒一个动作；43 的缺省限频是每秒 3 条，不会被拒
report_interval: 5
mode: "stress"
custom_weights:                    # 优先级高于 profile
  move: 70                         # 实际发 43 SceneInfoC2S → 服务器推 31
  cast_skill: 20                   # 84 ReleaseSkill（3 个机器人同点出生，互为目标）
  idle: 10
```

运行（mmorpg 的 `robot/` 目录）：`robot.exe -c <上面的文件>`。通过标准：
`[stats]` 行 `login_ok=3 enter_ok=3`、无断线；`behavior_test_results.csv` 里 `action=move` 的行全部 `success=true`；
Java gate 日志没有 43 的限频拒绝。**这只验证 43 → 31 的路径和 AI 流量的稳定性，不覆盖 131/132/134**（见 §8.3 结论）。
