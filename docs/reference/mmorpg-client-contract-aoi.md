# AOI / 视野与属性同步：客户端可见契约（基线 mmorpg@9c9c012b7）

> 来源：mmorpg 已提交内容 `9c9c012b7`（与 `contract/SOURCE.properties` 的 `mmorpg.commit` 一致），读法 `git -C <mmorpg> show 9c9c012b7:<路径>`。下文路径都相对 mmorpg 根目录。
> AOI、视野、移动、属性同步相关源码在 `766cb037c..9c9c012b7` 之间**没有改动**（`git diff --stat` 为空），所以本文与 `mmorpg-client-contract-scene.md` 用的是同一份实现。
> 范围：进视野 / 出视野的规则与下行消息（21 / 47 / 51 / 64），移动输入如何变成下行推送（66 / 137），属性同步（66 与 65/55/82/68/75），以及这些消息之间的先后顺序。
> 没读客户端仓库（`../mmorpg-client`，mmorpg AGENTS.md §9 要求授权才能读）。UE 客户端具体怎么消费 66，要去客户端仓库核对；robot 的处理见 §3.6。
> 本文是 Java 版 xm-scene 实现 AOI、移动和属性同步的规格。§8 是 Java 现状，§9 是设计建议，§10 是必做清单。

---

## 0. 消息号与通道

`proto/scene/*.proto` 都没有 `package`，所以消息全名就是裸名，Java 生成类在 `com.game.proto`。下表的号来自 `proto/message_id.txt`。Java 必须按「服务名 + 方法名」解析这些号，不许写死数字（沿用 `SceneMessageIds` 的做法）。

| id | 服务 / 方法 | 类型 | 方向 | 基线是否发送 |
|---|---|---|---|---|
| 21 | SceneSceneClientPlayer / NotifyActorCreate | `ActorCreateS2C` | S→C | 只在进场时发**自己**（§3.1） |
| 47 | SceneSceneClientPlayer / NotifyActorListCreate | `ActorListCreateS2C` | S→C | AOI 进视野（§2.4） |
| 51 | SceneSceneClientPlayer / NotifyActorDestroy | `ActorDestroyS2C` | S→C | 离开场景时广播（§2.5） |
| 64 | SceneSceneClientPlayer / NotifyActorListDestroy | `ActorListDestroyS2C` | S→C | AOI 出视野（§2.4） |
| 66 | ScenePlayerSync / SyncBaseAttribute | `ActorBaseAttributesS2C` | S→C | 脏字段同步，最多 10 Hz（§5） |
| 65 / 55 / 82 / 68 / 75 | ScenePlayerSync / SyncAttribute{2,5,10,30,60}Frames | `AttributeDelta*FramesS2C` | S→C | **从不发**（§5.3） |
| 134 / 132 / 131 | SceneMovementClientPlayer / MoveStart / MoveSync / MoveStop | `MoveStartC2S` / `MoveSyncC2S` / `MoveStopC2S` | C→S | 应答是 `Empty`，不回包（§4.1） |
| 137 | SceneMovementClientPlayer / NotifyMoveAck | `MoveAckS2C` | S→C | 只在纠偏时发（§4.3） |
| 136 | SceneMovementClientPlayer / TeleportRequest | `TeleportRequestC2S` → `TeleportRequestC2SResponse` | C→S | handler 是空的（§4.4） |
| 133 / 135 / 130 | NotifyActorMove / NotifyActorMoveList / NotifyTeleport | `ActorMoveS2C` / `ActorMoveListS2C` / `TeleportS2C` | S→C | **从不发**（没有任何调用点） |
| 70 / 33 | SceneSkillClientPlayer / NotifySkillUsed / NotifySkillInterrupted | `SkillUsedS2C` / `SkillInterruptedS2C` | S→C | 收件人规则见 §6.4 |
| 79 | SceneSceneClientPlayer / NotifyEnterScene | `EnterSceneS2C` | S→C | 见 scene 契约文档 |

- `ScenePlayerSync` 服务只标了 `OptionIsPlayerService`，**没有**标 `OptionIsClientProtocolService`。所以 66 这一组只能下行，客户端上行会被 gate 当成非法包（Java gate 的 `MessageRoutes` 只放行 `clientService()`，行为一致）。
- **信封**：单发走 `GateSendMessageToPlayer`(72)，广播走 `GateBroadcastToPlayers`(32)（`cpp/libs/engine/core/network/player_message_utils.cpp:60-80, 109-167`）。客户端两种情况收到的都是 `MessageContent{message_id, serialized_message}`，`id=0`，不带 `error_message`。
- **gate 限频**（`generated/tables/messagelimiter.json`，Java gate 用同一份表）：MoveStart(134) 每秒 15 条，MoveSync(132) 每秒 20 条，MoveStop(131) 每秒 15 条。表里没有的消息缺省每秒 3 条。

## 1. 坐标与单位

- 世界是 **z-up**：`x`、`y` 是地面平面，`z` 是高度。服务器坐标与 UE 客户端一致（见 `spatial/system/view.cpp:152-167` 的注释，以及 `spatial/constants/nav.h:21-33` 的换轴说明）。
- 单位是**米**。客户端基础移速 9 m/s，信任上限 `kMaxTrustedClientSpeed = 10.0` m/s（`nav.h:10`）。
- 所有坐标类型（`Vector3` / `Location` / `Rotation` / `Scale` / `Velocity`）的 x/y/z 都是 `double`。`Transform = {Vector3 location=1; Rotation rotation=2; Scale scale=3}`（`proto/common/component/actor_comp.proto:28-33`）。

---

## 2. AOI 模型（`cpp/libs/services/scene/spatial/**`）

### 2.1 格子：六边形，只看 x/y

- 布局用 `Layout(layout_flat, size=(20,20), origin=(0,0))`（`spatial/system/grid.cpp:12-14`）。这是 redblobgames 的六边形库（`third_party/hexagons_grids`，子模块 `683e59e`）。
  - flat-top：中心到顶点 20 m，相邻格中心相距 `20·√3 ≈ 34.64` m。
- 格子坐标**只取 `location.x / location.y`**，`z` 不参与（`grid.cpp:16-26`）。
- 换算步骤（`pixel_to_hex` + `hex_round`）：
  1. `px = x/20`，`py = y/20`。
  2. `q = 2/3·px`，`r = −1/3·px + √3/3·py`，`s = −q−r`。
  3. 三个分量各自用 C 的 `round` 取整（**四舍五入时 .5 远离 0**）。
  4. 修正误差最大的那个分量：若 `q` 误差严格最大，`q = −r−s`；否则若 `r` 误差大于 `s` 的，`r = −q−s`；否则 `s = −q−r`。
  - 注意：Java 的 `Math.round(-0.5)` 结果是 0，C 的 `round(-0.5)` 结果是 -1。要与基线一致，就用 `signum(v)·floor(|v|+0.5)`。
- 格子 id 是 `(q, r)` 对。C++ 把它打包成 `uint128`：`q<<64 | (uint64)r`。这只是服务端内部表示，客户端看不到。
- 邻格方向依次是 `(+1,0) (+1,−1) (0,−1) (−1,0) (−1,+1) (0,+1)`。**候选集 = 本格 + 6 个邻格，共 7 格**（`grid.cpp:28-38`）。格子按场景实例分开（`SceneGridListComp` 挂在场景实体上）。
- 金标准（便于 Java 单测）：
  - `(0,0)→(0,0)`
  - `(30, 17.3205…)→(1,0)`
  - **出生点 `(180,200)→(6,3)`**
  - `(170,200)`、`(190,200)`、`(180,210)` 都在 `(6,3)`
  - `(180,185)→(6,2)`
  - 恰好落在格边上的点（例如 `y=0` 且 `x` 在 30 附近）取决于浮点误差。Java 用同样的运算顺序算即可；个别边界点和基线分到相邻格也不违反协议，只会改变候选集。
- 几何推论：
  - 7 格候选集至少覆盖实体周围 **20 m**：最坏情况是实体站在本格顶点上，到簇边界的凹角距离是 20。
  - 最远能覆盖约 **73 m**：凸角到本格中心 52.9 m，再加实体到本格中心的 20 m。
  - 所以视野半径 10 m 的圆一定落在候选集内。

### 2.2 视野半径与可见性判定

- 玩家的 `ViewRadius.radius = 10`。这个值在加载存档后写死（`player/system/player_lifecycle.cpp:2409`），**不来自配置表**：`data/schema/*` 里没有视野字段。
- 没有 `ViewRadius` 组件的实体（NPC）用缺省值 `kMaxViewRadius = 10`（`spatial/constants/view.h:3`，`view.cpp:28-38`）。`ViewRadius.radius` 在 proto 里是 `float`（`actor_comp.proto:52-55`）。
- `IsWithinViewRadius(观察者, 目标)` 用 `dtVdist` 算**三维**欧氏距离（包括 z），类型是 double（`dtReal = double`）。**`≤ 半径` 为真**，用的是观察者自己的半径（`view.cpp:40-67`）。
- `CanSee(观察者, 目标)` 先判距离，再判隐身：目标挂着 `StealthedTagComp` 时，只有观察者把目标钉成 `kPinned` 才能看见（`view.cpp:175-198`）。基线生产代码里没人调用 `PinAoiEntity`，所以隐身目标实际上谁都看不见。Java 首批没有 buff，可以不做。

### 2.3 兴趣列表（`AoiListComp`）

- 每个观察者一张表，结构是 `entries: map<目标实体, {priority}>`（`spatial/comp/scene_node_scene_comp.h:18-33`）。
- 兴趣是**单向**的：A 看得见 B 不代表 B 看得见 A（`docs/design/aoi_priority_design.md` 的 Directionality 一节）。设计原则是「**客户端显示的就是兴趣列表**」。但基线在反向与淘汰时没有通知客户端，所以原则没守住，见 §7。
- 容量取 `min(客户端期望, 场景压力上限)`（`spatial/system/interest.cpp:39-63`）。
  - 基线生产代码里没人挂 `AoiClientCapacityComp`、`ScenePressureComp`、`ScenePriorityPolicyComp`，所以**实际容量是 100**（`kAoiListCapacityDefault`），策略是开放世界。
  - 常量见 `spatial/constants/aoi_priority.h:97-99`：默认 100，下限 20，上限 200。
- 加入规则（`AddAoiEntity`，`interest.cpp:80-115`）：
  - 已在表里：只可能升优先级，返回 true。
  - 未满：直接插入。
  - 已满：新条目权重严格大于表里最低权重时，挤掉最低那条（**静默，不给客户端发任何消息**）；否则拒绝，返回 false。
  - 生产代码里实际只出现 `kNormal`(0)，以及组队时的 `kTeammate`(1)（`player_team_aoi.cpp`）。

### 2.4 进出视野：每 tick 的 `AoiSystem::Update`（`spatial/system/aoi.cpp:40-180`）

每个 tick 遍历所有带 `Transform + SceneEntityComp` 的实体 E（遍历顺序未定义）：

1. **算格子**（`UpdateGridState`，`aoi.cpp:62-97`）
   - E 还没有 `Hex`，也就是首次进入本场景：把 E 放进当前格，`进入格 = 当前 7 格`，`离开格 = ∅`。
   - E 所在的格子没变（`hex_distance == 0`）：**本 tick 对 E 什么都不做**，也**不重新判断距离**。
   - 格子变了：`离开格 = 旧 7 格 − 新 7 格`，`进入格 = 新 7 格 − 旧 7 格`，再把 E 从旧格挪到新格。
2. **进入格里的每个其他实体 O**（`aoi.cpp:104-123`）
   - 正向：`CanSee(E,O)` 为真且 `AddAoiEntity(E,O)` 成功时，O 进入发给 E 的「进视野集合」。
   - **反向**：`CanSee(O,E)` 为真时，`AddAoiEntity(O,E)`。**只改 O 的表，不给 O 发任何消息。**
3. **离开格里的每个其他实体 O**（`aoi.cpp:128-153`）
   - 正向：O 在 E 的表里且不是 `kPinned` 时，把它移除，并放进发给 E 的「出视野集合」。这里**不判距离**。
   - **反向**：E 在 O 的表里且不是 pinned 时，把 E 从 O 的表里移除。**同样不给 O 发消息。**
4. **下发**（`NotifyEntityVisibilityChanges`，`aoi.cpp:158-180`）：先发 **47**，再发 **64**，都只发给 E。
   - E 是 NPC 时没有会话，`SendMessageToClientViaGate` 记一条警告后放弃。
   - 进、出两个集合天然不相交，因为同一个实体只在一个格里。

### 2.5 离开场景（`AoiSystem::BeforeLeaveSceneHandler`，`aoi.cpp:182-263`）

下面这些情况都会触发 `BeforeLeaveScene` 事件（`player_lifecycle.cpp:1826-1860` `DetachFromScene`，`player/system/player_scene.cpp:69-85`）：正常退出、断线、被废黜（顶号、交接）、场景内换场景。处理步骤：

1. **静默清空离场者自己的兴趣列表**（`aoi.cpp:201-203`）。离场者**收不到 64，也收不到任何别的消息**。
2. 把离场者从所在格里摘掉。
3. 离场者当前 7 格里的**所有实体** O：从 O 的表里删掉离场者，并收集成广播名单。
4. 向名单广播 **51 `ActorDestroyS2C{entity = 离场者}`**。这一步**不检查 O 是否曾经看见过离场者**。NPC 没有会话，被跳过。
5. 调用方随后删掉离场者的 `Hex`。下次进场，无论同节点重连还是换场景，都按「首次进入」处理。

离场者还没经过首个 AOI tick（没有 `Hex`）就离开时，这个 handler 直接返回，不发 51。这时本来也没人知道它。

### 2.6 滞回

- **基线没有显式的滞回参数**。设计文档把「lazy-time / hysteresis」列为未来工作（`docs/design/aoi_priority_design.md:154-155`）。
- 实际上进视野和出视野的条件不对称，客观上形成了一个宽滞回带：
  - **进视野**：必须在「E 或 O 换格（包括首次进场）」的那个 tick 上满足距离 ≤ 10 m。
  - **出视野**：只在对方离开自己的 7 格候选集时发生（这时双方相距至少 20 m，最多约 73 m），或者对方离开场景。**不按距离出视野。**
- 由此产生的现象都属于基线怪癖，见 §7：
  - 两人同在 7 格邻域、相距 15 m 时，一方走近到 5 m，只要双方都没换格，就**始终互不可见**。
  - 一旦可见，就要走出 20–73 m 才会消失。

### 2.7 tick 与各系统顺序（`cpp/libs/services/scene/world/world.cpp:41-75`）

- 场景固定 **20 FPS**（`core/constants/fps.h:3`）。`World::Update` 由 `worldTimer.RunEvery(0.05s)` 驱动（`cpp/nodes/scene/main.cpp:324`）。
- 固定步长累加器：累加值夹在 [-1, 1] 秒。每次调用最多模拟 5 步，超出的时间只扣掉、不补模拟。
- 每一步的顺序：**AFK → AOI → 移动积分 → 加速度 → Buff → 属性计算 → 属性同步（66）→ 帧号 +1**。
- 客户端消息（移动输入、放技能）和进场、离场 RPC 都在两次 tick 之间的事件循环里处理，**立即生效**，处理中需要发的消息（79 / 21 / 51 / 137 / 70）也当场发出。

---

## 3. 视野消息字段

### 3.1 21 `ActorCreateS2C`（`proto/scene/player_scene.proto:49-59`）

由 `ViewSystem::FillActorCreateMessageInfo(observer, entrant)` 填写（`spatial/system/view.cpp:88-122`）：

| 字段 | 号 | 类型 | 值 |
|---|---|---|---|
| `entity` | 1 | uint64 | 进视野实体在场景内的实体号（C++ 是 `entt::to_integral`，会复用，Java 用非 0 雪花号）。**不是 player_id** |
| `transform` | 2 | Transform | **只拷 `location`**，`rotation` 和 `scale` 不填。没有 Transform 组件时整个字段不写 |
| `actor_type` | 3 | enum | 玩家为 `ACTOR_TYPE_PLAYER=1`，NPC 为 `ACTOR_TYPE_NPC=2` |
| `guid` | 4 | uint64 | `Guid` 组件，也就是玩家的 **player_id**。NPC 没有，为 0 |
| `config_id` | 5 | uint64 | **从不填**（恒为 0，NPC 也一样） |
| `appearance_id` | 6 | string | `PlayerProfileComp.appearance_id`，仅玩家 |
| `class_id` | 7 | uint32 | `PlayerUint32Comp.class`，仅玩家 |
| `gender` | 8 | uint32 | `PlayerProfileComp.gender`，仅玩家 |

- `observer` 参数在函数里没有用到，同一个实体发给谁内容都一样。
- **不带速度和朝向**：正在移动的实体刚进视野时，在客户端看来是静止的，要等它的下一条 66 才更新（§5）。
- 基线**只在一个地方单独发 21**：`PlayerSceneSystem::HandleEnterScene` 给进场者发它自己（`player_scene.cpp:114-116`）。AOI 路径从不单独发 21。

### 3.2 47 `ActorListCreateS2C`（`player_scene.proto:66-69`）

- 结构：`repeated ActorCreateS2C actor_list = 1`，每一项同 §3.1。
- 只发给「这个 tick 里自己换格或首次进场」的实体 E。内容是本 tick 新加进 E 兴趣列表的实体，列表非空才发。
- 项的顺序来自 `unordered_set`，**未定义**。客户端不能依赖顺序。

### 3.3 51 `ActorDestroyS2C`（`player_scene.proto:61-64`）

- 结构：`uint64 entity = 1`，是离场者的场景实体号。
- 只在 §2.5 的离开场景时发，收件人是离场者当前 7 格里的全部玩家（不看可见性）。

### 3.4 64 `ActorListDestroyS2C`（`player_scene.proto:71-74`）

- 结构：`repeated uint64 entity = 1`。
- 只发给「这个 tick 里自己换格」的实体 E，内容是因为 E 换格而从 E 的表里移除的实体。
- **可能包含 E 的客户端从没收到过创建消息的实体**：它们是经 §2.4 第 2 步的反向路径静默加进 E 的表的。

### 3.5 实体号的两套口径（易错）

- 21 / 47 / 51 / 64 / 70 / 33 里的实体号，以及 `ReleaseSkillRequest.target_id`，都是**场景内实体号**。
- 66 的 `entity_id` 是 **guid**，也就是 player_id（§5.1）。
- 基线场景实体号在一次在线期间（包括同节点换场景）保持不变，重登时可能变。robot 把实体号 0 当作「没有目标」，所以 **Java 的实体号必须非 0**（现在用雪花号，满足）。

### 3.6 robot 的处理（`robot/logic/handler/*`，基线 9c9c012b7）

- 21：`guid == 自己` 时记下自己的 entity，然后 `AddEntity`。47：逐项同 21。
- 51：`RemoveEntity`。64：`RemoveEntities`。
- 这几个操作都幂等：`knownEntities` 按值去重，对未知实体删除时什么也不做。
- 收到 79 且 `scene_id` 变化时清空已知实体（`robot/logic/gameobject/player.go:148-158`）。
- robot **不处理 66**，只打一条 Unhandled 日志。**robot 不发移动**（只有 `travel_smoke_scenario.go` 的 `probeMove` 发 134 / 131，并尽力等 137）。
- 137 / 133 / 135 / 130 的 handler 都是空函数。

---

## 4. 移动输入与移动推送

基线没有「移动广播」专用消息：133 `ActorMoveS2C`、135、130 都**没有任何发送点**。**别人的移动完全经由 66 下发。**

### 4.1 上行 134 / 132 / 131（`proto/scene/player_movement.proto`，handler 在 `cpp/nodes/scene/handler/rpc/player/player_movement_handler.cpp`）

| 消息 | 字段 |
|---|---|
| `MoveStartC2S` | `start_location=1 (Location)`，`rotation=2`，`velocity=3`，`target_location=4`（未使用），`client_time_ms=5`（未使用），`input_seq=6` |
| `MoveSyncC2S` | `location=1`，`rotation=2`，`velocity=3`，`client_time_ms=4`（未使用），`input_seq=5`。客户端移动中约每 250 ms 发一次 |
| `MoveStopC2S` | `end_location=1`，`rotation=2`，`client_time_ms=3`（未使用），`input_seq=4` |

处理规则：

- 三者的应答都是 `Empty`，**不回包**，拒绝时只能静默丢弃。
- 玩家在回合制战斗中（`InBattleComp`）或归属交接冻结中（`PlayerFrozenComp`）时丢弃。MoveStop 例外：仍把速度清零。
- **位置**（`ApplyReportedLocation`，`:82-154`）
  - 有导航网格时：从服务器当前位置向上报位置做 raycast，撞墙就截在阻挡点；起点不在网格上时吸附上报点，吸附也失败就落到出生点。
  - **没有导航网格时原样接受（fail-open）**。Java 没有导航网格，属于这种情况。
  - 然后写 `Transform.location`，**用请求里的 `rotation` 整体替换 `Transform.rotation`**（请求没带时替换成空的 Rotation），并置 Transform 脏位。
- **速度**（`ApplyReportedVelocity`，`:56-70`）
  - MoveStart / MoveSync：用上报的速度，模长超过 10 m/s 时等比缩放到 10，方向不变，并**无条件**置 Velocity 脏位。
  - MoveStop：速度清零，同样置脏位。
  - 顺序是先处理位置、再处理速度，所以 137 里带的是**旧**速度。
- **基线不校验 NaN / Inf**，也不校验位移是否合理：没有导航网格时，上报任意坐标都会被接受。
- 每条客户端消息都会刷新 `LastActiveFrameComp`（`cpp/nodes/scene/handler/rpc/scene_handler.cpp:459`）。

### 4.2 服务器积分（`spatial/system/movement.cpp:35-88`）

- 每个 tick，对速度非零的实体执行 `location += velocity × 0.05s`（三个分量都积分），然后置 Transform 脏位。
- 有导航网格且撞墙时截停，速度清零，并置 Velocity 脏位。
- 以下实体不积分：带 `Acceleration`（由加速度系统接管）、`AfkComp`、`PlayerFrozenComp`、`InBattleComp` 的实体。
- **AFK**：连续 30 s（600 帧）没有任何客户端消息就挂 `AfkComp`（`player/system/afk.cpp:14`），之后停止积分。
  - 源码注释说 AOI 和属性同步也会排除 AFK，**实际上不排除**，只有移动积分排除。
- 退出时 `StopMotionForExit` 把非零速度清零，并置 Velocity 脏位（`player_lifecycle.cpp:1862-1883`）。

### 4.3 137 `MoveAckS2C`（纠偏）

- 只在「裁决位置与上报位置的**水平**（x/y）距离 > `kMoveCorrectionEpsilon = 0.5` m」时发（`nav.h:19`，handler `:135-153`）。发送时机在处理那条上行消息的当下，只发给本人。
- 字段：

| 字段 | 值 |
|---|---|
| `input_seq` | 回显请求的 `input_seq` |
| `server_location` | 裁决后的位置 |
| `server_velocity` | 当前 Velocity 组件；没有该组件时不写 |
| `server_time_ms` | UTC Unix 毫秒 |

- **没有导航网格时永远不会发 137**，因为原样接受，距离为 0。

### 4.4 136 TeleportRequest

handler 是空的。生成的 `CallMethod` 会把空 TLS tip 写进 `error_message`，所以基线回的是 `TeleportRequestC2SResponse{error_message{id=0}}`（线上字节 `0a 00`），也就是「受理了，但什么也不做」。之后不会有 130。

---

## 5. 属性同步（`proto/scene/player_state_attribute_sync.proto`）

### 5.1 66 `ActorBaseAttributesS2C`

| 字段 | 号 | 类型 | 写入来源（C++ 生成序列化器 `cpp/libs/services/scene/generated/attribute/actorbaseattributess2c_attribute_sync.cpp:13-74`） |
|---|---|---|---|
| `entity_id` | 1 | uint64 | 用 `try_get<uint64_t>` 取组件，也就是 **`Guid` 组件 = player_id**（`using Guid = uint64_t`）。**不是**场景实体号 |
| `transform` | 2 | Transform | **整份**拷贝 Transform 组件：`location` 总在；`rotation` 在上报过移动或存档里有值时才在；`scale` 基线从不写 |
| `velocity` | 3 | Velocity | 整份拷贝 Velocity 组件（已夹到 ≤ 10 m/s） |
| `combat_state_flags` | 4 | `CombatStateFlagsComp{map<uint32,bool> state_flags=1}` | 键是当前激活的 `eCombatState`（目前只有 `kSilence=0`），值恒为 true；状态清空时为空 map |

**只序列化脏字段**，发出后清对应的脏位。没有任何脏字段时不发。脏位的来源：

| 脏位 | 何时置位 |
|---|---|
| transform | 进场落位 `EnsureValidEnterLocation`（`spatial/system/scene_spawn.cpp:138`，每次进场都置）；每条 MoveStart / MoveSync / MoveStop；速度非零时每个 tick 积分 |
| velocity | 每条 MoveStart / MoveSync（无条件）/ MoveStop；积分时撞墙清速；退出时清速 |
| combat_state_flags | 战斗状态增减之后的属性计算（`actor/attribute/system/actor_attribute_calculator.cpp:63-84`） |
| entity_id | **只和 combat_state_flags 一起置**（同一函数 `:83`）。移动带来的 66 **不带 entity_id** |

- **子消息的存在性**：C++ 用 `mutable_x()->CopyFrom(...)` 写入，所以字段只要是脏的，就一定出现在线上，**哪怕值全是 0**：
  - 停步时的 velocity 是 `1a 00`；
  - 状态清空时的 combat_state_flags 是 `22 00`。
  - Java 必须用 `setVelocity(...)` 这类方式写，保留存在性。「速度字段在、值为 0」的意思是「停了」。
- **频率**：每个 tick 都检查，但只在**偶数帧**（`current_frame % 2 == 0`）发送，所以每个实体最多 **10 Hz**（`actor/attribute/system/actor_state_attribute_sync.cpp:84-86`）。移动中 transform 每 tick 都脏，所以别人收到的是约 10 Hz 的 66 流。
- **收件人**：**发送者自己的兴趣列表**，也就是它看得见的那些实体（`:66-86`）。
  - 这些实体经 `BroadcastMessageToPlayers` 发出，NPC 没有会话会被跳过。
  - **不发给自己**：自己不在自己的表里。自己的移动靠客户端预测加 137 纠偏。
  - 兴趣列表为空时**不清脏位**。脏位一直留着，等表里第一次有人时，把积压的字段一次发出去。
  - 归属交接冻结中（`PlayerFrozenComp`）的实体不同步。

### 5.2 各消息的时序关系

在同一 tick 内，AOI 阶段在属性同步阶段之前，所以：

- 本 tick 新进视野的实体，**先**在 47 里给出，**然后**才可能收到它的 66。
- 本 tick 出视野的实体，64 发出后就不会再收到它的 66，因为同步阶段用的是更新后的兴趣列表。

### 5.3 65 / 55 / 82 / 68 / 75 `AttributeDelta{2,5,10,30,60}FramesS2C`

- 这几条消息都只有 `entity_id=1` 一个字段。
- 同步系统按距离给兴趣列表里的实体分档（`actor_state_attribute_sync.cpp:88-111`）：
  - 距离 ≤ 1/3 半径：每 2/5/10/30/60 帧各发一次；
  - ≤ 2/3 半径：每 2/5/10 帧；
  - ≤ 半径：每 2 帧。
- 但**全仓没有任何代码给它们置脏位**，所以**从不发送**。Java 不要发。

### 5.4 不在本文范围

`AttributePanelChangedS2C`（`proto/scene/player_attribute.proto:144`）等属性面板消息只发给本人，不属于视野同步。

---

## 6. 顺序汇总（每个客户端的收包顺序 = scene 的发送顺序）

同一玩家的全部下行都经过同一个 gate 会话（C++ 走同一条 RpcSession，Java 走同一条节点链路），所以发送顺序就是收包顺序。

| 事件 | 当场（事件循环里）发出 | 下一个 tick 发出 |
|---|---|---|
| 进场（首登 / 重连 / 换场景） | 给进场者发 79，然后发 21（自己）。换场景时另向旧场景 7 格里的玩家广播 51 | AOI 阶段给进场者发 47（视野内的其他实体）。旁人**什么都收不到**（缺口 1）。同 tick 若是偶数帧，旁人会收到进场者的 66（只有 transform，没有 entity_id） |
| 走动但没换格 | 可能给本人发 137 | 偶数帧给「行走者兴趣列表」里的实体发 66（transform，以及上报带来的 velocity） |
| 走动并换格 | 同上 | AOI 阶段给行走者发 47，再发 64。新靠近的人和新远离的人**都收不到任何消息**（缺口 1、2）。然后偶数帧发 66 |
| 离场（退出 / 断线 / 顶号） | 给离场者 7 格里的全部玩家广播 51，离场者什么也收不到 | —— |
| 放技能 / 被打断 | 70 / 33，见 §6.4 | —— |

### 6.4 70 / 33 的收件人

`BroadcastSkillUsedMessage` / `SendSkillInterruptedMessage`（`combat/skill/system/skill.cpp:476-487, 506-516`）调用 `BroadcastMessageToVisiblePlayers`，内部用 `GridSystem::GetEntitiesInViewAndNearby`（`grid.cpp:82-129`）挑收件人：施法者 7 格内、「**以对方自己的半径**看得见施法者」的实体，并且**跳过施法者本人**（`grid.cpp:114-117`）。

- `view.h:21` 的注释写着「including self」，但实现里**不含本人**。
- 收件人按当下的距离算，**不看兴趣列表**。所以可能发给从没收到过施法者 21 / 47 的客户端。

---

## 7. 基线缺口与怪癖（Java 可以修，但必须确认不破坏客户端）

1. **反向进视野静默**（`aoi.cpp:118-121`）：E 进场或走近时，只有 E 收到 47。场内原有的人 O 只是在服务端的表里加上 E，**客户端永远收不到 E 的创建消息**。直到 O 自己换格——而那时 E 已经在 O 的表里了，也不会再出现在 47 里。结果是**老观察者看不见新来者和走近者**。
2. **反向出视野静默**（`aoi.cpp:144-151`）：E 走远离开 O 的邻域时，O 的表里删掉了 E，但 O 的客户端收不到 64 或 51。E 成了 O 屏幕上的**残影**，直到 E 离开场景（且 O 恰好在 E 的 7 格里）或 O 收到新的 79。
3. **只在换格时判断距离**（`aoi.cpp:72-74`）：同一邻域内从 15 m 走到 5 m，只要双方都不换格就不会进视野。
4. **容量满时的挤出是静默的**（`interest.cpp:112-113`）：被挤掉的实体不发 64 / 51。只有组队或者 100 人以上才会触发。
5. **移动带来的 66 不带 entity_id**，接收方无法知道这条更新属于谁。而且 66 发给的是「发送者看得见的人」，其中包括经缺口 1 静默加入、从没收到过发送者创建消息的客户端。
6. **66 的 `entity_id` 是 guid**，其余视野消息都用场景实体号。两套口径并存，客户端要维护 guid → 实体的映射（21 里同时带着 entity 和 guid）。
7. **51 不看可见性**：发给离场者 7 格里的所有玩家，包括从没见过离场者的人。
8. **70 / 33 不发给施法者本人**，与 `view.h` 的注释相反。Java 现在会发给本人（§8）。
9. **创建消息只带位置**：没有朝向和速度。正在移动的实体进视野时显示为静止，要等到它的速度下次变化才会收到 velocity。
10. **隐身生效时不会把目标从现有的兴趣列表里移除**，只影响之后的进视野判定。
11. **移动输入不校验 NaN / Inf**：NaN 会进入 Transform，再进入 `hex_round`，而 C++ 把 NaN 转 int 是未定义行为。
12. 136 回「受理」却什么也不做。133 / 135 / 130 在 proto 里有定义，但从不发送。

---

## 8. Java 现状（`xm-scene/src/main/java/com/game/scene/world/*`）

| 方面 | 现状 | 与基线的关系 |
|---|---|---|
| 视野计算 | `SceneWorld.viewersOf`：同一场景内逐人比较三维距离 ≤ `VIEW_RADIUS = 10`，只在进场、离场、换场景时算。没有格子、没有 tick、没有兴趣列表状态 | 首批没有移动，位置不会变，所以结果等价于「双向、对称」的视野 |
| 进场 | 发 79 → 21（自己）→ 47（视野内他人，非空才发）→ 给视野内他人补发 21（新进场者），全部**当场同步**发出 | 修了缺口 1 的进场部分。47 比基线早一个 tick |
| 离场 / 换场景 | 向离开前视野内的人广播 51，离场者什么也收不到 | 收件人是「视野内」，基线是 7 格内全部玩家。Java 版更准（缺口 7） |
| 64 | 不发，`SceneMessageIds` 里也没有 | 缺 |
| 移动 131 / 132 / 134 | 应答是 `Empty`，`ClientRequestHandler` 只记一条 debug 日志后忽略，坐标不变 | 缺 |
| 136 | 回 `error_message{1006}` | 基线回 `{0}`（受理但不处理）。两者都不会有后续 |
| 137 / 66 / tick | 都没有 | 缺 |
| 70 | `broadcastToSelfAndViewers`，**包括施法者本人**。注释称「基线收件人包括施法者自己」，与实现不符 | **偏离**（缺口 8） |
| 实体号 | `ScenePlayer.entity`：雪花号，非 0，一次进场内稳定，换场景也不变 | 符合 |
| `ActorCreateS2C` | `toActorCreate()` 只填 location、`actor_type=1`、guid、appearance、class、gender，`config_id=0` | 符合 §3.1 |

---

## 9. Java 实现建议（按 Java 惯用方式组织，不照抄 ECS）

- **`HexGrid`**（纯函数，无状态）：`cellOf(x, y) → HexCell(q, r)`，`neighborhood(cell) → 7 格`。实现 §2.1 的运算和 C 的舍入语义，用 §2.1 的金标准做单测。
- **`AoiIndex`**（每个 `Scene` 一个，只在场景逻辑线程上用）：
  - `Map<HexCell, Set<ScenePlayer>>`；
  - 每个玩家两张表：`watching`（我看得见谁，也就是兴趣列表，容量 100）和 `watchedBy`（谁看得见我，反向索引）。
  - 用 `LinkedHashSet` 或 `LinkedHashMap`，让 47 / 64 的顺序确定、单测可复现。
- **`SceneTicker`**：在场景逻辑线程（`DefaultEventLoop`）上 `scheduleAtFixedRate` 每 50 ms 跑一次。
  - 用固定步长累加器，每次最多 5 步，帧号单调递增。
  - 每步顺序：AOI → 移动积分 → 属性同步（偶数帧）。与 §2.7 一致。
- **进出视野的规则**（建议口径，需要登记 PARITY，并请 mmorpg 同步修改）：
  - **候选集**：与基线相同的 7 格。
  - **进视野**：双方同场景、在候选集内、距离 ≤ 10 m（三维，含等号）、表未满。**判定时机**：任一方位置变化的那个 tick 都重新检查候选集里还没进表的实体（修缺口 3），不再只在换格时检查。
  - **出视野**：对方离开 7 格候选集时移除（保留基线的隐式滞回：进 ≤ 10 m，出 ≥ 20 m），或者对方离开场景。
  - **对称通知**（修缺口 1、2、4）：
    - 任何「W 的表加入 T」都给 W 发 T 的创建消息。同一 tick 同一 W 的多个加入合成一条 47；进场路径沿用现有的「47 给进场者，21 给旁人」。
    - 任何「W 的表移除 T」都给 W 发销毁消息。tick 内合成 64；离场时发 51。
    - 效果：**任何客户端收到的 66 / 70 / 33，所涉实体都一定先收到过它的创建消息**；收到销毁消息后，不会再收到该实体的任何推送。
  - **51 的收件人**：`watchedBy(离场者)`。修了缺口 7，客户端本来也必须容忍多余的销毁消息，改了没有兼容风险。
- **移动**：
  - 处理 134 / 132 / 131：先校验 finite，非有限值直接丢弃（fail-closed，应答本来就是 Empty）；速度按模长夹到 10 m/s；位置原样接受（没有导航网格时与基线的 fail-open 一致）；rotation 整体替换；置脏位。
  - 每个 tick 积分。
  - 137 按 §4.3 实现。没有导航网格时它永远不会触发，但代码路径要留好。
  - 可选的反作弊（例如单位时间最大位移、越界拉回并发 137）会偏离基线，要先定口径。
- **属性同步**：
  - 每个玩家带一个脏位集合，首批只需要 `TRANSFORM` 和 `VELOCITY`。
  - 偶数帧构造 66，**每条都带 `entity_id = player_id`**（修缺口 5，保持缺口 6 的 guid 口径，**不要**填场景实体号）。
  - 收件人是 `watchedBy(发送者)`，不含发送者本人。
  - 表为空时保留脏位。子消息存在性按 §5.1 处理。
  - 可选：给新观察者发完创建消息后，紧跟一条该实体的全量 66（transform + velocity），修缺口 9。
- **70 / 33**：收件人改为 `watchedBy(施法者)`，**不含施法者本人**，与基线实现一致（缺口 8）。
  - UE 客户端对自己放的技能很可能在本地播放；再发 70 给本人，有重复播放的风险。改之前要到客户端仓库核对。
  - robot 只统计 70 的数量，单机器人跑时这个数会变成 0，但不影响冒烟判定。
- `SceneMessageIds` 需要补上：`NotifyActorListDestroy`(64)、`ScenePlayerSync/SyncBaseAttribute`(66)、`SceneMovementClientPlayer/{MoveStart, MoveSync, MoveStop, NotifyMoveAck, TeleportRequest}`、`NotifySkillInterrupted`(33)。一律按名字解析。
- **存储**：离场写回已经带坐标。有了移动之后，进程被 kill 时丢失的坐标增量会变多，周期存盘仍在 architecture.md §10 的「首批不做」之列，这里不改。

---

## 10. Java 必须做到（清单）

**线上兼容（必须）**

- [ ] 21 / 47 的每一项严格按 §3.1 填写：`entity` 非 0 且稳定；`transform` 只有 location；`actor_type=1`；`guid=player_id`；`config_id=0`；appearance / class / gender 取角色数据。
- [ ] 47 是 `repeated ActorCreateS2C actor_list=1`；64 是 `repeated uint64 entity=1`；51 是 `uint64 entity=1`。实体号用场景实体号。
- [ ] 进场顺序：79 → 21（自己）→ 47（他人）。
- [ ] 不在客户端收到某实体的创建消息之前推送它的 66 / 70 / 33。同一 tick 内先处理 AOI 再做同步，保证 47 在 66 之前、64 之后不再有该实体的 66。
- [ ] 离场者收不到 51 / 64。离场、断线、换场景时向旁人发 51，离场者的视野状态静默清空。
- [ ] 视野半径 10 m，三维欧氏距离，`≤` 含等号，不从配置表读。坐标 z-up，格子只看 x/y，单位是米，类型是 double。
- [ ] 66 的字段号和类型：`entity_id=1 (uint64，= player_id)`、`transform=2`、`velocity=3`、`combat_state_flags=4`。只带脏字段，脏子消息即使全 0 也要写上（停步时 `velocity` 出现且为空）。
- [ ] 66 每个实体最多 10 Hz（20 FPS 的偶数帧），不发给发送者本人。
- [ ] 不发 65 / 55 / 82 / 68 / 75 / 133 / 135 / 130。
- [ ] 134 / 132 / 131 不回包。速度夹到 10 m/s。137 只在水平纠偏 > 0.5 m 时发，字段按 §4.3，`input_seq` 回显。
- [ ] 所有下行都经 `MessageContent{message_id, serialized_message}`，`id=0`。消息号按「服务名 + 方法名」解析。
- [ ] 同一客户端的下行只在场景逻辑线程上按顺序写到同一条链路（保序）。

**正确性（Java 必须，基线没做）**

- [ ] 移动输入里的非有限值（NaN / Inf）一律丢弃，不进 Transform，也不进格子计算。
- [ ] 兴趣状态只在场景逻辑线程上读写。玩家离开时要同时清三处：格子、`watching`、`watchedBy` 反向索引。不能留下悬空引用。

**建议修（与基线行为不同，落地时登记 PARITY「不适用 / 行为有意不同」行，并请 mmorpg 同步）**

- [ ] 双向通知：旁人也收到新来者、走近者的创建消息，走远者、被挤出者的销毁消息（缺口 1、2、4）。
- [ ] 任一方位置变化都重新判断能否进视野（缺口 3）。出视野沿用「离开 7 格邻域」。
- [ ] 每条 66 都带 `entity_id`（缺口 5）。
- [ ] 51 只发给 `watchedBy`（缺口 7）。
- [ ] 70 / 33 不发给施法者（缺口 8）。这一条修正的是 Java 现有的偏离，先到客户端仓库核对。
- [ ] 可选：给新观察者补发一条全量 66（缺口 9）。

**验证（写单测，按 AGENTS.md §4 用 `clean install` 跑）**

- [ ] `HexGrid` 的金标准（§2.1），以及负坐标的舍入。
- [ ] 两人走近、走远时双方的收包序列：47 / 21 / 64 / 51 各一次，没有残影；换场景和断线的收包序列。
- [ ] 66 的字节形态：停步时 `velocity` 存在且为空；带 `entity_id`；10 Hz 限频；兴趣列表为空时保留脏位。
- [ ] robot_smoke 冒烟回归：3 个账号，login_ok 和 enter_ok 都为 3，没有失败（robot 不发移动、不处理 66，收到的 21 / 47 / 51 / 64 都按幂等处理）。
