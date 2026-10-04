# 场景侧进场：客户端可见行为参考（基线 mmorpg@766cb037c）

> 源码快照根目录：`C:\Users\ADMINI~1\AppData\Local\Temp\claude\D--luyuan-wuxingqitan-mmorpg\296f1859-4ee0-4d2d-a51b-ea15b1f39a91\scratchpad\baseline`。下文路径都相对这个目录。
> 范围：scene 接纳玩家后，一直到玩家在世界里空闲为止的全部下行消息；进场后 robot_smoke 发往 scene 的请求；离开和断线时的表现。只写客户端能看到的部分，服务端内部流程只在解释时序时带到。

---

## 0. 通用约定（客户端可见）

### 0.1 proto 全名
`proto/scene/*.proto`、`proto/common/base/tip.proto`、`proto/common/component/*.proto` 都**没有 `package` 语句**。整个 `proto/scene` 和 `proto/common/{base,component}` 里只有 `proto/common/base/node.proto` 声明了 `package common.base`。所以本文涉及的消息全名就是裸名，例如 `EnterSceneS2C`、`ActorCreateS2C`、`ListSkillsResponse`、`PlayerSkillListComp`、`TipInfoMessage`、`SceneInfoComp`、`Transform`、`Vector3`。

### 0.2 信封（gate 转发给客户端的 `MessageContent`）
- **服务端主动推送**：scene 组装 `NodeRouteMessageRequest{header.session_id, header.target_player_id, message_content{message_id, serialized_message}}`，经 gate 的 `GateSendMessageToPlayer`(72) 发出（`cpp/libs/engine/core/network/player_message_utils.cpp:62-82`）。这里**不设** `message_content.id`，所以值为 0。
- **广播**：走 `GateBroadcastToPlayers`(32)，同样只带 `message_id` 和 `serialized_message`（同文件 `:112-169`）。
- **请求的应答**：`ProcessClientPlayerMessage` 用 `message_content.message_id = 请求的 message_id`、`message_content.id = 请求 ClientRequest.id`（回显 seq）（`cpp/nodes/scene/handler/rpc/scene_handler.cpp:555-570`）。
- **应答类型是 `Empty` 的方法（所有 Notify*，以及 `SceneInfoC2S`）不回包**（`scene_handler.cpp:555`）。

### 0.3 应答里 `error_message` 的实际线上形态（重要）
生成的 `CallMethod` 在每个 handler 之后执行 `TRANSFER_ERROR_MESSAGE`：`*(_resp->mutable_error_message()) = std::move(tlsTip)`（`cpp/libs/engine/core/macros/return_define.h:67-75`；调用点如 `cpp/nodes/scene/handler/rpc/player/player_skill_handler.h:39,61`）。

后果有三条：
- 凡是带 `TipInfoMessage error_message = 1` 的应答，**线上总是带着这个字段**。成功时它是空子消息（`id=0`），编码为字节 `0a 00`。
- 在 handler 里直接写 `response->mutable_error_message()->set_id(x)` 会被 TLS 里的空 tip 覆盖掉。`ReleaseSkill` 的两处 `kInvalidTableId` 就是这样失效的（`player_skill_handler.cpp:25,33`），见 §7。
- 真正生效的拒绝码必须写进 TLS tip（`SetTip`，见 `player_scene_handler.cpp:29-32`），或者走 `CHECK_PLAYER_REQUEST`。

### 0.4 相关 message_id（`proto/message_id.txt`）

| id | 名称 | 方向 | 本文用途 |
|---|---|---|---|
| 79 | SceneSceneClientPlayerNotifyEnterScene | S→C | 进场通知（**必需**） |
| 21 | SceneSceneClientPlayerNotifyActorCreate | S→C | 自身 actor（**必需**）；AOI 单体创建 |
| 47 | SceneSceneClientPlayerNotifyActorListCreate | S→C | AOI 批量进视野 |
| 51 | SceneSceneClientPlayerNotifyActorDestroy | S→C | 离场广播给他人 |
| 64 | SceneSceneClientPlayerNotifyActorListDestroy | S→C | AOI 批量出视野（进场链不发） |
| 31 | SceneSceneClientPlayerNotifySceneInfo | S→C | 只在收到 43 时回推 |
| 43 | SceneSceneClientPlayerSceneInfoC2S | C→S | 返回 Empty |
| 63 | SceneSceneClientPlayerEnterScene | C→S | 切场景请求 |
| 77 | SceneSkillClientPlayerListSkills | C→S | 技能列表（**必需**） |
| 84 | SceneSkillClientPlayerReleaseSkill | C→S | 放技能 |
| 70 / 33 | SceneSkillClientPlayerNotifySkillUsed / NotifySkillInterrupted | S→C | 技能广播 |
| 66 | ScenePlayerSyncSyncBaseAttribute | S→C | 基础属性脏同步 |
| 65/55/82/68/75 | ScenePlayerSyncSyncAttribute{2,5,10,30,60}Frames | S→C | 基线从不发（无脏位来源） |
| 23 | SceneClientPlayerCommonSendTipToClient | S→C | 进场失败提示 |
| 9 | ScenePlayerExitGame | gate→S（内部） | 断线/退出 |
| 83 | ScenePlayerEnterGameNode | gate→S（内部） | 进场入口 |
| 17 / 58 | ClientPlayerLoginLeaveGame / Disconnect | C→login | robot 收尾 |

---

## 1. 首登玩家落在哪里

1. login 的 EnterGame 发给 scene_manager 的 `EnterSceneRequest` **不带 `SceneConfId`**。FirstLogin 时 `SceneId=0`，`ZoneId` 为本 zone（`go/login/internal/logic/clientplayerlogin/entergamelogic.go:646-700` `resolveEnterSceneRoute` 第 4 条；请求体在 `:736-745`）。
2. scene_manager 收到 `scene_conf_id==0` 时取 `defaultWorldConfID()`，也就是 **World 表第一行的 `scene_id`**（`go/scene_manager/internal/logic/enterscenelogic.go:1228-1256`、`world_init.go:578-588`）。
3. `generated/tables/world.json` 第一行是 `{id:1, scene_id:1}`，所以**首登场景配置 id = 1**。它对应 `basescene.json` 的 id 1：`nav_bin_file:"data/scene_nav_bin/main_scene.bin"`，出生点 `spawn_x:180.0, spawn_y:200.0, spawn_z:0.0`。
4. 具体频道（`scene_id`，雪花号）由 `ReserveBestWorldChannelForEnter` 选负载最低的一条。场景实体上的 `SceneInfoComp` 在 CreateScene 时填写：`scene_config_id=config_id`、`scene_id`、`mirror_config_id`、`dungeon_config_id`、`creators`（`cpp/nodes/scene/handler/grpc/scene_node_service.cpp:42-49`、`scene_handler.cpp:895-902`）。世界频道的 mirror/dungeon 都是 0，`creators` 为空。
5. 断线重连或顶号时 `ZoneId=0`、`SceneId=0`、不带地图：scene_manager 只用 location 决定**去哪个 zone**（`enterscenelogic.go:355-364`），zone 内仍按第 2、4 条落默认主世界人数最少的频道；只有选中的频道恰好是玩家原来所在的那个时才走「同落点重连」捷径（`:492`、`:630-640`）。它注释里写的「去向按 location 决定」在 zone 内并没有生效。主动登出后 location 被清掉，下次登录同样按 FirstLogin 规则落到 World 第一行。（2026-10-04 更正：此前这里写「通常回到原场景」，不对。）

### 出生坐标来源
逻辑在 `cpp/libs/services/scene/spatial/system/scene_spawn.cpp:91-149` `EnsureValidEnterLocation`，由 `player_scene.cpp:102` 调用。

- 新号的 `Transform` 是 proto 默认值 (0,0,0)。
- 首次进场时 `useSceneSpawn=false`：
  - 该配置有导航网格：先对当前坐标做 `SnapToMesh`。成功就只吸附；失败就取出生点，并吸附到网格。
  - 没有导航网格，且坐标为 (0,0,0)：直接取出生点。
- 出生点优先取 BaseScene 表的 `spawn_x/y/z`（要求有限且不全为 0）；否则用常量 `kTianyongSpawn` = (180, 200, 0)（`spatial/constants/nav.h:35-37`；`scene_spawn.cpp:45-63`）。
- 换地图（`mapChanged`：新旧 `scene_config_id` 不同）一律落到目标出生点。同图换线保留坐标。`mapChanged` 只和内存里的旧场景比（会话内换图）；重新登录时没有旧场景、库里也不存地图，不算换图，按下一条保留存档坐标。
- 老玩家的 `Transform` 来自存档 `player_database.transform`（`proto/common/database/mysql_database_table.proto:110`）。只要在网格上就保留。
- **Java 若没有导航网格：首登坐标 = (180.0, 200.0, 0.0)。**

---

## 2. 进场内部链路（只为说明时序）

1. **gate → scene** 发 `PlayerEnterGameNode(83)`，请求体 `PlayerEnterGameNodeRequest{player_id=1, session_id=2, enter_gs_type=4, scene_id=5, home_zone_id=6, owner_epoch=7}`（`proto/scene/scene.proto:15-23`）。处理在 `scene_handler.cpp:159-261`：
   - 实体已在本节点：直接 `EnterScene`（`:209-213`）。
   - 实体不在：先预登记 `SessionMap[session]=player`（`:227-230`），再从 Redis 异步加载 `PlayerAllData`（`:259`）。
     - 取到 NIL 时按 500ms、1s、2s、4s、8s、16s 退避重试 6 次，累计约 31.5s（`cpp/libs/engine/infra/storage/redis_client/redis_client.h:389-403,825-840`）。
     - 重试用尽按全新玩家处理（`player_lifecycle.cpp:1175-1182`）。
     - 正常情况下 login 已预热该 key，客户端几乎感觉不到延迟。
2. **加载完成**：`HandlePlayerAsyncLoaded` → `InitPlayerFromAllData`（`cpp/libs/services/scene/player/system/player_lifecycle.cpp:1237-1307, 2322-2385`）：
   - 若 `registration_timestamp <= 0`（首次注册）：写入时间戳、`LevelComp.level=1`，并触发 `RegisterPlayerEvent` → `PlayerSkillSystem::RegisterPlayer` 发放初始技能（`:2356-2364`；`cpp/nodes/scene/handler/event/player_event_handler.cpp:29-42`）。
   - 设置 `ViewRadius.radius = 10`（`:2366`）。
   - 然后调用 `EnterScene(player, ctx)`（`:2379`）。
3. **`PlayerLifecycleSystem::EnterScene`**（`player_lifecycle.cpp:1533-1716`）：
   - 绑定 session（`:1591-1607`）。
   - 按 `scene_id` 找场景实体（`:1610-1629`）。**找不到就推 tip 3023 并中止**（`:1642-1649`）。
   - 调用 `PlayerSceneSystem::HandleEnterScene`（`:1652`）。
   - 组队刷新、`PlayerLoginEvent`、战斗后置钩子：对无队伍、无战斗的新号都**不产生下行消息**。`PlayerLoginEventHandler` 的各分支全是 TODO（`player_event_handler.cpp:80-111`）。

`InitializeActorCompsEvent` 的 handler 为空（`actor_event_handler.cpp`）。`InitializePlayerCompsEvent` 只做任务初始化（`player_event_handler.cpp:66-79`）。**进场链上没有主动推技能、属性面板、背包或货币。**

---

## 3. 进场下行消息：发送顺序与字段

scene 主循环 20 FPS，`kTargetFPS=20`（`cpp/libs/services/scene/core/constants/fps.h:3`）。`World::Update` 每个 tick 依次执行 AFK → AOI → 移动 → Buff → 属性计算 → 属性同步（`cpp/libs/services/scene/world/world.cpp:41-75`）。

| 序 | 时机 | message_id | 类型 | 条件 | robot_smoke |
|---|---|---|---|---|---|
| ① | `HandleEnterScene` 内同步发出 | **79** | `EnterSceneS2C` | 每次真正进入新场景 | **必需** |
| ② | 紧接 ①，同步发出 | **21** | `ActorCreateS2C`（自己） | 同上 | **必需**（robot 靠它拿到自身 entity，用作技能目标） |
| ③ | 下一个 tick 的 AOI 阶段 | 47 | `ActorListCreateS2C` | 当前格加相邻 hex 格里有 `CanSee` 为真的其他实体 | 可选（多机器人时基线会发） |
| ④ | 之后首个偶数帧的属性同步阶段 | 66 | `ActorBaseAttributesS2C` | 自身 AOI 列表非空且 Transform 脏位未清 | 可选（robot 不处理） |
| — | 从不发 | 65/55/82/68/75 | `AttributeDelta*FramesS2C` | 没有任何代码置它们的脏位 | 不需要 |
| — | 进场不发 | 31 | `SceneInfoS2C` | 只在收到 43 时回推 | 不需要 |
| — | 进场不发 | 64 | `ActorListDestroyS2C` | 只在 AOI 换格时发 | 不需要 |
| ✗ | 失败路径 | 23 | `TipInfoMessage{id=3023}` | 场景找不到或 Redis 加载报错 | 失败路径 |

①② 走同一条 gate RpcSession 依次 `SendRequest`，TCP 保序，**79 一定先于 21**（`player_scene.cpp:105-116`）。

### 3.1 ① NotifyEnterScene — id 79，`EnterSceneS2C`
- proto：`proto/scene/player_scene.proto:23-26`：`message EnterSceneS2C { SceneInfoComp scene_info = 1; }`
- 填写方式：`message.mutable_scene_info()->CopyFrom(*sceneInfo)`，把场景实体上的 `SceneInfoComp` 整体拷过来（`cpp/libs/services/scene/player/system/player_scene.cpp:105-107`）。
- `SceneInfoComp` 字段（`proto/scene/scene_info.proto:4-11`）：

| 字段 | 编号 | 类型 | 首登世界场景的值 |
|---|---|---|---|
| `scene_config_id` | 1 | uint32 | **1**（World 表第一行） |
| `scene_id` | 2 | uint64 | 频道雪花号，非 0 |
| `mirror_config_id` | 3 | uint32 | 0 |
| `dungeon_config_id` | 4 | uint32 | 0 |
| `creators` | 5 | map<uint64,bool> | 空 |

- robot 处理（`robot/logic/handler/scene_scene_client_player_notify_enter_scene.go:10-21`）：
  - **`scene_info` 为 nil 时直接 return，不会发出就绪信号。**
  - 否则 `SetSceneInfo(scene_id, scene_config_id)` 并 `SignalSceneReady()`（只生效一次）。
  - robot 之后切场景用的就是这里拿到的 `scene_config_id`。
- **幂等早退（基线怪癖）**：玩家已在目标场景实体里时，`HandleEnterScene` 直接 return，**不发 79 和 21**（`player_scene.cpp:51-58`）。顶号或重连复用实体、且没有先退出场景时，新客户端收不到 79，见 §7。

### 3.2 ② NotifyActorCreate（自身）— id 21，`ActorCreateS2C`
- proto：`player_scene.proto:49-59`

```proto
message ActorCreateS2C {
  uint64 entity = 1;  Transform transform = 2;  ActorType actor_type = 3;
  uint64 guid = 4;    uint64 config_id = 5;     string appearance_id = 6;
  uint32 class_id = 7; uint32 gender = 8;
}
enum ActorType { ACTOR_TYPE_NONE = 0; ACTOR_TYPE_PLAYER = 1; ACTOR_TYPE_NPC = 2; }   // :42-47
```

- 填写逻辑：`ViewSystem::FillActorCreateMessageInfo(observer=player, entrant=player)`（`cpp/libs/services/scene/spatial/system/view.cpp:88-122`；调用点 `player_scene.cpp:114-116`）。

| 字段 | 值来源 |
|---|---|
| `entity`(1) | `entt::to_integral(entity)`，即 scene 内的 ECS 实体号（**不是** player_id） |
| `transform`(2) | **只填 `transform.location`**（Vector3 x/y/z），取 §1 算出的落位；`rotation`、`scale` 不填 |
| `actor_type`(3) | 实体有 `Player` 组件时为 `ACTOR_TYPE_PLAYER`=1 |
| `guid`(4) | `Guid` 组件，也就是 **player_id** |
| `config_id`(5) | 玩家不填（0） |
| `appearance_id`(6) | `PlayerProfileComp.appearance_id`（组件存在时才填） |
| `gender`(8) | `PlayerProfileComp.gender`（组件存在时才填） |
| `class_id`(7) | `PlayerUint32Comp.class`（proto 字段名 `class`=1，见 `proto/common/component/player_comp.proto:44-47`） |

- `class_id`、`appearance_id`、`gender`、`name` 由 login 在首次入场时补进存档（`go/login/internal/logic/clientplayerlogin/player_class_backfill.go:25-107`），值是 CreatePlayer 时的职业、外观和性别。
- `Transform` 结构：`proto/common/component/actor_comp.proto:28-33` 为 `{Vector3 location=1; Rotation rotation=2; Scale scale=3;}`；`Vector3{double x=1,y=2,z=3}` 定义在 `proto/common/component/base_comp.proto:6-11`。
- 客户端靠 `guid == 自己的 player_id` 识别本地角色（注释见 `player_scene.cpp:109-113`）。robot 在 `guid == player.ID` 时 `SetEntityID(entity)`，并把 entity 加入已知列表（`robot/logic/handler/scene_scene_client_player_notify_actor_create.go`）。

### 3.3 ③ NotifyActorListCreate — id 47，`ActorListCreateS2C`
- proto：`player_scene.proto:66-69`：`repeated ActorCreateS2C actor_list = 1;`
- 触发：`AoiSystem::Update` 遍历 `view<Transform, SceneEntityComp>`（`cpp/libs/services/scene/spatial/system/aoi.cpp:40-60`）。实体没有 `Hex` 组件时视为首次进场，把自己插进当前格，并把当前格加相邻格作为 `gridsToEnter`（`:62-69`）。
- 对这些格里的每个其他实体 O：
  - 进场者 E 能看见 O（`CanSee(E,O)`，距离 ≤ E 的 `ViewRadius`=10，隐身另判）且 `AddAoiEntity` 成功时，O 进入发给 E 的列表（`:108-116`；`view.cpp:175-198`）。
  - 反向，O 能看见 E 时，只把 E 加进 O 的兴趣列表，**不给 O 发任何消息**（`aoi.cpp:118-121`，基线缺口，见 §7）。
- 列表非空时，每个 O 用 `FillActorCreateMessageInfo(E, O, ...)` 填一项，字段同 §3.2，O 为 NPC 时 `actor_type=2`。然后把 47 发给 E（`aoi.cpp:158-169`）。
- 兴趣列表容量：客户端期望值与场景压力上限取较小者（`interest.cpp:39-63`）。满员时按优先级淘汰。
- robot：遍历 `actor_list`，`guid==自己` 时设 entityID，每项都 `AddEntity`（`scene_scene_client_player_notify_actor_list_create.go`）。
- robot_smoke 的 3 个机器人落在同一出生点，距离 0，彼此都在视野内。所以**后进场的机器人**会收到包含先到者的 47；先到者**收不到**后到者的 21 或 47（基线行为）。

### 3.4 ④ SyncBaseAttribute — id 66，`ActorBaseAttributesS2C`
- proto：`proto/scene/player_state_attribute_sync.proto:15-23`：`{uint64 entity_id=1; Transform transform=2; Velocity velocity=3; CombatStateFlagsComp combat_state_flags=4;}`
- 进场时 `EnsureValidEnterLocation` 置 Transform 脏位（`scene_spawn.cpp:138`）。
- `ActorStateAttributeSyncSystem::Update` 只处理兴趣列表非空的实体，每 2 帧一次，把**该实体自己的**脏字段广播给它兴趣列表里的实体（`cpp/libs/services/scene/actor/attribute/system/actor_state_attribute_sync.cpp:66-86`；序列化在 `cpp/libs/services/scene/generated/attribute/actorbaseattributess2c_attribute_sync.cpp:13-74`）。
- 进场这一条**只带 `transform`**（Transform 整体：location、rotation、scale），**不带 `entity_id`**，因为 entity_id 脏位只在战斗状态重算时才置（`actor_attribute_calculator.cpp:78-83`）。接收方无法据此归属（基线怪癖）。
- 兴趣列表为空时脏位不清，一直留到列表变为非空。
- robot 不处理 66，只打 `Unhandled message` 日志（`robot/logic/handler/message_body_handler.go:146-150`）。

### 3.5 进场失败：id 23，`TipInfoMessage`
- `TipInfoMessage{uint32 id=1; repeated string parameters=2;}`（`proto/common/base/tip.proto:4-8`）。
- `SendTipToPendingSession(session, player_id, kEnterSceneFailed=3023)`（`player_lifecycle.cpp:118-149`），触发条件：
  - `scene_id` 在本节点找不到（`:1642-1649`）；
  - Redis 加载报错（非 NIL）（`:1184-1203`）。
- login 在 scene_manager 拒绝时也会推同一个 3023（`entergamelogic.go:431-461`），注释要求客户端"已进场则忽略进场失败 tip"。
- robot 对 23 空处理（`scene_client_player_common_send_tip_to_client.go`），最终表现为 60s 等待超时。

### 3.6 单机器人（独自在场景里）时实际收到的内容
只有 **79 → 21(self)**。然后空闲，等 ListSkills 的应答。

---

## 4. 进场后 robot 发往 scene 的请求（robot_smoke = stress 模式）

`robot/etc/robot_smoke.yaml` 的配置是：`mode: stress`、`profile: stress`、`robot_count: 3`、`action_interval: 3`、`skill_ids: []`。主流程在 `robot/main.go:405-470`：

1. 先启动 RecvLoop。握手阶段提前到达的推送会先按 FIFO 补投递（`robot/pkg/client.go:174-181, 271-273`），所以 79 早于 EnterGame 应答到达也没关系。
2. **等 NotifyEnterScene 最多 60s**（`main.go:429-434`）。超时就 return，本轮算失败，外层退避后重新走登录（`main.go:271-313`）。
3. 发 **ListSkills(77)**，`ListSkillsRequest{}`（`main.go:437`）。
4. 等 ListSkills 应答最多 **5s**，超时**只告警**（`main.go:440-444`）。
5. 启动 AI 循环：先随机抖动 0–2s，之后每 3s 执行一次动作（`robot/logic/ai/robot_ai.go:50-66`）。stress 权重是 `CastSkill 85 / SwitchScene 15`（`robot/logic/ai/action.go:111-117`），**不会发 43 或聊天**。
6. 收到 SIGINT 或连接断开时，发 LeaveGame(17)（`main.go:470`），然后 defer 发 Disconnect(58) 并关闭连接（`main.go:354-355`）。

### 4.1 ListSkills — id 77（**必需**）
- 请求：`ListSkillsRequest {}`（`proto/scene/player_skill.proto:44-45`）。
- 应答：`ListSkillsResponse { TipInfoMessage error_message = 1; PlayerSkillListComp skill_list = 2; }`（`:48-51`）。
  - `PlayerSkillListComp { repeated PlayerSkillComp skill_list = 1; }`，其中 `PlayerSkillComp { uint64 id = 1; uint32 skill_table_id = 2; }`（`proto/common/component/player_skill_comp.proto:6-13`）。
- handler（`cpp/nodes/scene/handler/rpc/player/player_skill_handler.cpp:58-78`）：
  - 组件存在时，把 `PlayerSkillListComp` 整体拷进应答。
  - 组件不存在时 `mutable_skill_list()`，**强制字段存在（空列表）**。注释明确要求不能删，robot 依赖它发出就绪信号。
  - `error_message` 按 §0.3 总是存在且为空。
- **新号的技能来源**：`PlayerSkillSystem::RegisterPlayer`（`cpp/libs/services/scene/player/system/player_skill.cpp:38-59`）遍历 **Class 表所有行**（不只本职业）的 `skill` 数组。跳过 0、Skill 表中不存在的 id 和重复 id，按顺序追加 `{skill_table_id}`，`id` 字段不填（0）。
  - 基线数据中 `class.json` 第 1 行是 `[1,2,13]`，第 2–9 行都是 `[1,1,1]`；`skill.json` 含 1–13。
  - **结果：`skill_list = [{skill_table_id:1},{skill_table_id:2},{skill_table_id:13}]`，与职业无关。**
- 老玩家的技能取自存档 `player_database.skill_list`（字段 4），进场时不重新发放。
- 应答信封：`message_id=77`，`id` 回显请求 seq。
- robot 处理（`scene_skill_client_player_list_skills.go`）：`skill_list` 为 nil 时 `SetOwnedSkillIDs(nil)`；否则取每项的 `skill_table_id`。两种情况都会发出 skillsReady 信号。
- 拒绝和丢弃情形：
  - 实体还在加载中：**静默丢弃，不回包**（`scene_handler.cpp:414-432`）。
  - 玩家退出中：回 `error_message.id = kFeatureUnavailable(1006)`，且不带 skill_list（`:488-502`）。
  - 其余丢弃分支同 §4.5。

### 4.2 ReleaseSkill — id 84（robot 每 3s 约 85% 概率发一次）
- 请求（`player_skill.proto:14-19`）：`ReleaseSkillRequest{uint32 skill_table_id=1; uint64 target_id=2; Vector3 position=3; Rotation rotation=4;}`
  - robot 填写：`skill_table_id` 从 ListSkills 结果中随机取；`target_id` 从已知 entity 里随机取，已知列表为空时用自身 entityID；`position` 为 `{x±10, 0, z±10}`；`rotation` 不填（`robot_ai.go:131-174`）。
  - **`target_id==0` 或没有技能时 robot 不发**，所以 entity 号为 0 的实体永远不会被当作目标。
- 应答：`ReleaseSkillResponse{TipInfoMessage error_message=1;}`（`:21-23`）。
  - 表中不存在或未拥有该技能：handler 写 `kInvalidTableId(1001)`，但会被 §0.3 的机制覆盖成空 tip（`player_skill_handler.cpp:20-35`）。
  - 其余失败走 `CHECK_PLAYER_REQUEST(SkillSystem::ReleaseSkill)`，TLS tip 生效（`:37`；`return_define.h:93-101`）。
- 成功时还会向视野内玩家广播 **70 `SkillUsedS2C`**（`cpp/libs/services/scene/combat/skill/system/skill.cpp:146-163, 476-487`）：`{entity=施法者 ECS 号, target_entity=[target_id], skill_table_id, position=请求 position}`，`time_stamp` 不填。
  - `SkillUsedS2C` 字段（`player_skill.proto:34-41`）：`entity=1, repeated target_entity=2, skill_table_id=3, position=4, time_stamp=5`。
- robot 处理：应答里 `error_message != nil` 就记一条 "rejected" 告警（基线下 error_message 恒存在，所以总是告警）；收到 70 时计数（`scene_skill_client_player_release_skill.go`、`..._notify_skill_used.go`）。
- **robot 不等待这个应答**，对冒烟判定来说是可选项。

### 4.3 EnterScene（C2S）— id 63（robot 约 15% 概率发）
- 请求：`EnterSceneC2SRequest{SceneInfoComp scene_info=1;}`（`player_scene.proto:13-16`）。robot 只填 `scene_info.scene_config_id = 当前配置`，也就是 1（`robot_ai.go:195-218`）。
- 应答：`EnterSceneC2SResponse{TipInfoMessage error_message=1;}`（`:18-21`）。无错表示已受理，**不代表已到达**。
- 按顺序的校验（`cpp/nodes/scene/handler/rpc/player/player_scene_handler.cpp:36-171`）：

| 检查 | 失败码 |
|---|---|
| 节点类型是副本（kSceneNode=1）或跨服副本（3） | kEnterSceneServerType=3004（主世界节点 kMainSceneNode=0 放行） |
| 战斗在途 | kEnterSceneFailed=3023 |
| 换场景已在途 | kEnterSceneChangingScene=3014 |
| config、scene_id、mirror 三者都 ≤0 | kEnterSceneParamError=3005 |
| mirror>0 且 scene_id==0 | 进入镜像分支 |
| `scene_id` 等于当前场景（>0 时） | kEnterSceneYouInCurrentScene=3008 |

  - 通过后向 scene_manager 发 EnterScene（带 `scene_conf_id`，`scene_id=0`）。scene_manager 挑同配置负载最低的频道：
    - 挑回原场景：重新路由到本节点，`HandleEnterScene` 幂等早退，**不再发 79**。
    - 挑到别的频道：先 `BeforeLeaveScene`，他人收到 51；本人收到新的 79 和 21，坐标保留（同图）。

    这一分支没有逐步核实。
- robot 只记日志、不等待，属于可选项。建议 Java 至少回一个 `EnterSceneC2SResponse`。

### 4.4 SceneInfoC2S — id 43（stress 模式不发，仅供参考）
- 请求 `SceneInfoRequest{}`，应答类型为 `Empty`，**不回 43**。
- 改为推送 **31 `SceneInfoS2C{repeated SceneInfoComp scene_info=1}`**，内容是一项当前场景的 `SceneInfoComp`（`player_scene_handler.cpp:182-205`；proto `player_scene.proto:28-31`）。
- robot 收到 31 时空处理。

### 4.5 通用：scene 对客户端消息的静默丢弃和拒绝规则
逻辑在 `scene_handler.cpp:368-573`。

**静默丢弃（不回包）的情形：**
- `message_id` 越界，或不是 scene 的 player service（`:379-399`）；
- session 未知（`:401-406`）；
- `player_id` 与 session 映射不符（`:408-412`）；
- 实体还在加载（`:414-432`）；
- session 已被新会话取代（`:438-447`）；
- 请求解析失败、字段超长或含负数（`:458-480`）。

**回包但带拒绝码的情形：**
- 退出中的实体：除 ExitGame 外，回 `error_message.id=1006`（`:488-502`）；
- 客户端 GM 方法被闸门拒绝：同样回 1006（`:503-548`）。

---

## 5. 离开和断线

- robot 收尾依次发 **LeaveGame(17)** 和 **Disconnect(58)**，两者都发给 login，然后关闭 TCP。scene 这一侧看到的是 gate 代发的 **`ScenePlayerExitGame`(9)**，请求体为空 `GameNodeExitGameRequest{}`，应答 `google.protobuf.Empty`（`proto/scene/player_lifecycle.proto:15-24`；handler 在 `cpp/nodes/scene/handler/rpc/player/player_lifecycle_handler.cpp:24-42`）。加载中收到 ExitGame 时直接取消这次加载（`scene_handler.cpp:420-425`）。
- `HandleExitGameNode`（`player_lifecycle.cpp:1881-2034`）依次做四件事：挂 `UnregisterPlayer` 标记、停止运动、`DetachFromScene`（`:1966`）、存盘。收尾时 `FinishExitAfterPersist` 删除 session 并销毁实体（`:2036-2112`）。
- **客户端可见的只有一件事**：`DetachFromScene` 触发 `BeforeLeaveScene`，进而调用 `AoiSystem::BeforeLeaveSceneHandler` 和 `BroadcastEntityLeave`（`player_lifecycle.cpp:1807-1841`；`aoi.cpp:182-263`）。
  - 先把离场者从格子里摘掉（`RemoveEntityFromGrid`），再向**当前格和相邻格里的所有实体**广播 **51 `ActorDestroyS2C{uint64 entity = 离场者 ECS 号}`**（proto `player_scene.proto:61-64`）。
  - 这次广播**不检查**对方是否曾经看见过离场者；NPC 没有 session，会被跳过。
  - 离场者自己的兴趣列表被静默清空，**不给离场者发任何东西**，也不发 64。
- robot 收到 51 时执行 `RemoveEntity(entity)`，对未知 entity 是 no-op。

---

## 6. robot_smoke 在 scene 侧的判定点汇总

| 步骤 | robot 期望 | 超时和后果 |
|---|---|---|
| 进场 | 收到 79，且 `scene_info != nil` | 60s，超时算本轮失败，重新走登录（最多 5 次） |
| 自身 actor | 21，其中 `guid == player_id`、`entity ≠ 0` | 没有就无技能目标，ReleaseSkill 不发（不致命） |
| 技能 | 77 的应答（message_id=77） | 5s，超时只告警；之后没有技能可放 |
| 空闲 | 每 3s 发 84 或 63 | 不等应答 |
| 结束 | SIGINT 后发 17、58，关闭连接 | 他人收到 51 |

robot 不处理的推送 id 只会产生 `Unhandled message` 日志，包括 66、65/55/82/68/75 等，不影响流程。

---

## 7. 基线怪癖和缺口（Java 可以修，但要确认不破坏客户端）

1. **幂等早退不发 79**（`player_scene.cpp:51-58`）：顶号或重连复用实体、且已在同一场景时，新客户端收不到 79 和 21，robot 会在 60s 后超时重试。**建议 Java 每次进场成功都发 79 和 21**；robot 的 `SignalSceneReady` 只生效一次，重复发送无害。
2. **老观察者收不到新进场者**（`aoi.cpp:118-121`）：只有进场者收到 47，场内已有的人不收到对应的 21 或 47。建议 Java 给这些 O 补发 21；robot 能处理（AddEntity）。
3. **离场 51 不看可见性**：发给当前格和相邻格里的所有人。客户端必须容忍未知 entity 的销毁消息。
4. **进场时的 66 不带 `entity_id`**，接收方无法归属。这条可以不发。
5. **`error_message` 总是存在**（§0.3）：`ReleaseSkill` 的 1001 被覆盖成空 tip。建议 Java 保持"字段总在、成功时 id=0"的线上形态。是否修复 1001：robot 对任何非 nil 的 error_message 都记告警，修与不修都不影响它。
6. **`ActorCreateS2C.entity` 是 scene 内 ECS 号**，与 `guid`（player_id）不是一回事。entt 首个实体号可能是 0，而 robot 把 0 当成"无目标"。**Java 应保证 entity 号非 0**，并在一次会话内保持稳定。
7. **首登 Redis 数据为 NIL 时最多拖约 31.5s** 才按新号处理。Java 应做到即时。

---

## Java 必须做到（清单）

- [ ] **进场成功立即推 79**：`EnterSceneS2C.scene_info` 必须非空，`scene_config_id=1`（World 表第一行）、`scene_id` 非 0、`mirror_config_id=0`、`dungeon_config_id=0`、`creators` 为空。信封 `message_id=79`、`id=0`。
- [ ] **紧跟着推 21（自身）**，保持 79 在前：`entity` 非 0 且稳定；`guid=player_id`；`actor_type=1`；`transform.location` = 落位（新号无导航网格时为 (180,200,0)）；`class_id`、`appearance_id`、`gender` 取角色数据；`config_id=0`；rotation 和 scale 不填。
- [ ] **重连、顶号、换线等每次成功进场都推 79 和 21**，不照搬基线的幂等早退。
- [ ] **ListSkills(77)**：应答 `message_id=77`、`id` 回显请求 seq；`ListSkillsResponse.skill_list` 必须存在，没有技能时也要是空列表；新号内容为 `[{skill_table_id:1},{skill_table_id:2},{skill_table_id:13}]`（Class 表全表去重并集，`id=0`）；`error_message` 存在且 `id=0`。
- [ ] `Empty` 返回型方法（Notify*、43）不回包；43 改为推送 31 `SceneInfoS2C`。
- [ ] 进场失败时推 23 `TipInfoMessage{id=3023}`。
- [ ] 多人场景：给进场者推 47，内容为视野内（距离 ≤10）的其他 actor，字段同 21。建议同时给场内已有观察者补发 21。
- [ ] 离场或断线：向视野内其他玩家广播 51 `ActorDestroyS2C{entity}`；不给离场者发消息。退出过程中收到的客户端请求回 `error_message.id=1006`。
- [ ] 对 84 回 `ReleaseSkillResponse`（error_message 字段总在）；可选地广播 70 `SkillUsedS2C`。对 63 回 `EnterSceneC2SResponse`，拒绝码用 3004/3005/3008/3014/3023。robot 不等这两个应答，但也不能因此断连或崩溃。
- [ ] proto 全名用裸名（没有 package），字段编号严格按本文和 `proto/` 原文件。
- [ ] 时延：79 要在 EnterGame 之后 60s 内到达（最好是亚秒级）；77 的应答要在 5s 内。