# 副本与镜像场景、实例空闲回收（批次 5.3）移植统一规格：从 mmorpg 基线到 Java 版

> **基线**：mmorpg `26ceb70ca`，与 `contract/SOURCE.properties` 的 `mmorpg.commit` 相同。客户端仓库 mmorpg-client `a8577c7` 只用来确认客户端读哪些字段。
> **Java**：HEAD `5ef79a6`（批次 4.7；5.1 = `d80cf79`）。工作区里有批次 5.2（跨节点换图 + 归属交接）与 6.1（新模块 `xm-battle-engine`）未提交的改动：
> `scene_directory.proto` / `SceneDirectoryService` 的 `selectSwitchTarget`、未跟踪的 `SwitchTargetSelector.java`、`node_link.proto` 的 `PlayerTransfer`、
> `ChannelSelector.java`（注释）、`SceneNode.java` / `SceneNodeProperties.java`（交出参数）等。**本稿把 5.2 / 6.1 当作即将落地**，钩子按
> `docs/porting/scene-handoff-spec.md`（下称 5.2 规格）、`docs/porting/battle-engine-spec.md` 描述。
> Java 行号：已提交且工作区未改的文件按 HEAD；在途文件（`ChannelSelector.java`、`SwitchTargetSelector.java`、`scene_directory.proto`、`SceneNode.java`）
> 按当前工作区或 HEAD（注明），并同时写出类名 / 方法名，提交后会漂移。
>
> **路径怎么读**
> - 以 `go/`、`cpp/`、`proto/`、`generated/`、`robot/`、`deploy/` 开头的路径在 `D:\work\mmorpg` 下；以 `xm-`、`docs/porting/`、`docs/reference/`、`config-data/`、
>   `contract/`、`tools/`、`PARITY.md`、`AGENTS.md` 开头的路径在 `D:\work\xuanming-server-mmo` 下；`architecture.md` 指 Java 仓库的 `docs/design/architecture.md`。
> - 不带目录的 Go 文件：`createscenelogic.go`、`instance_lifecycle.go`、`destroyscenelogic.go`、`scene_atomic.go`、`scene_node_client.go`、`enterscenelogic.go`、
>   `load_reporter.go`、`world_autoscale.go`、`world_rebalance.go`、`orphan_cleanup.go`，以及同目录的 `logic_test.go`、`world_autoscale_mirror_test.go`、`integration_test.go`，
>   都在 `go/scene_manager/internal/logic/` 下。`config.go` 在 `go/scene_manager/internal/config/`；`errors.go`、`scene_type.go` 在 `go/scene_manager/internal/constants/`；
>   `metrics.go` 在 `go/scene_manager/internal/metrics/`；`sm.yaml` = `go/scene_manager/etc/scene_manager_service.yaml`。
> - 不带目录的 C++ 文件：`player_scene_handler.cpp` 在 `cpp/nodes/scene/handler/rpc/player/`；`player_scene.cpp`、`player_team.cpp`、`player_lifecycle.cpp` 在
>   `cpp/libs/services/scene/player/system/`；`sm_reply.cpp` = `cpp/nodes/scene/rpc_replies/scene_manager_response_handler.cpp`；`scene_node_service.cpp` 在
>   `cpp/nodes/scene/handler/grpc/`；`scene_handler.cpp` 在 `cpp/nodes/scene/handler/rpc/`；`scene_event_handler.cpp` 在 `cpp/nodes/scene/handler/event/`；
>   `scene_spawn.cpp` 在 `cpp/libs/services/scene/spatial/system/`；`message_limiter.h` 在 `cpp/libs/engine/core/message_limiter/`。
> - proto 用 mmorpg 行号：`sm.proto` = `proto/scene_manager/scene_manager_service.proto`；`scene_info.proto`、`scene.proto`、`player_scene.proto` 在 `proto/scene/`。
>   Java 同步副本在 `xm-proto/src/main/proto/proto/scene/`，**行号 = mmorpg 行号 + 3**（多三行 java option）。
> - mmorpg 文档：`sca.md` = `docs/design/scene-creation-architecture.md`；`deadline.md` = `docs/design/grpc-client-deadline-failure-callback.md`。
> - Java 类：`SceneWorld` / `Scene` / `ClientRequestHandler` / `ScenePlayer` / `SceneTables` / `ConfigSceneTables` / `RequestFieldCheck` 在 `xm-scene/src/main/java/com/game/scene/world/`；
>   `SceneNode` 在 `.../scene/`；`SceneDirectoryPublisher` 在 `.../scene/discovery/`；`TeamFollowService` 在 `.../scene/team/`；`MissionTables` 在 `.../scene/mission/`；
>   `SceneAssigner` / `ChannelSelector` / `SwitchTargetSelector` / `SceneDirectoryProvider` 在 `xm-scene-manager/src/main/java/com/game/scenemanager/`；
>   `WorldChannelControlPlane` / `WorldChannelPlanner` / `WorldChannelCoordinator` / `WorldAutoscaler` / `WorldRebalancePlanner` / `DirectoryView` / `MirrorSources` /
>   `PlanInput` / `PlanResult` / `NodeAvailability` 在 `.../scenemanager/world/`；`world_channel.proto`、`node_directory.proto`、`scene_directory.proto` 在 `xm-api/src/main/proto/xm/api/`；
>   `NodeDirectory`、`NodeTypes` 在 `xm-discovery/src/main/java/com/game/discovery/`；`Snowflake` 在 `xm-common/src/main/java/com/game/common/id/`。
> - tip 数值取自 `xm-table/src/main/proto/tip/`：`scene_error_tip.proto`（3000 `:12`、3004 `:20`、3005 `:22`、3006 `:24`、3008 `:28`、3014 `:40`、3022 `:56`、3023 `:58`）、
>   `common_error_tip.proto`（1003 `:18`）。消息号取自 `xm-proto/src/main/resources/contract/message_id.txt`（**号 N 在第 N+1 行**）。
> - 带「推导」字样的结论没有对应的测试或用例覆盖，是把多处代码路径串起来得出的。
>
> **本稿的来历**：由三份分区稿合并——scene-manager 分区稿（实例登记、建实例、选点、回收；提出在 Redis 建一张独立的实例登记表，宿主节点用 Lua 认领回收）、
> scene 节点分区稿（63 镜像分支、客户端契约、节点侧回收与级联）、Java 映射分区稿（方案比较、5.1 代码必须同批改的正确性点、回收宽限与复活）。
> 三份稿子在**实例登记放在哪里**上分歧最大：本稿采纳「节点自有 + 目录登记」（§6.2 写明仲裁理由），scene-manager 分区稿的 Redis 登记表列为备选。
> 分歧点与稿子之间的事实出入都回到代码重新核对过，更正集中在 §9.3。本稿只读代码，除本文件外没有改任何文件。

---

## 0 概览与范围（与 5.1 / 5.2 / 5.4 / 5.5 的边界）

### 0.1 结论速览

**基线**

- **客户端能走到的实例链路只有一条：63 的镜像分支**，条件是 `mirror_config_id ≠ 0 且 scene_id == 0`（`player_scene_handler.cpp:83-105`）。
  - 镜像 = 以玩家**当前所在场景**为模板开一个私有实例。地图（`scene_config_id`）取源场景的（`player_scene.cpp:189`），**全仓没有任何代码读 Mirror 表**，
    `mirror_config_id` 只是一个不透明的标签，非 0 即收（`player_scene_handler.cpp:93`；§1.4）。
  - 同步应答 `{0}` 只表示「已发出 CreateScene」（`player_scene_handler.cpp:90-92`）。SM 在 Redis 上做多键、非事务的登记 → 同步 gRPC 让节点建实体 →
    失败逐键回滚 → 成功回显 `creator_ids`（`createscenelogic.go:182-325`）；源节点据回显，替**仍在本节点、且没有换图在途**的创建者代发
    `EnterScene{scene_id = 镜像}`（`sm_reply.cpp:81-178`），之后走普通换图：旁人 51 → 本人 79 / 21 → 下一拍 47。
  - **缺省配置下镜像必与源场景同节点**：只有源节点判死、跨 zone、或打开了负载上限（缺省 0 = 关）才回落别的节点（`createscenelogic.go:491-527`；`config.go:154-159`）。
  - 两个「什么都不发」的黑洞：SM **业务**失败的应答不回显 `creator_ids`，C++ 只打日志（`sm_reply.cpp:84-90`、`:184-185`）；应答到达时创建者换图在途就静默跳过
    （`sm_reply.cpp:136-146`）。两种情况下客户端在 `{0}` 之后永远等不到 79 或 23。
  - `creators` 只写不读：任何知道 scene_id 的同 zone 玩家都能进别人的镜像；`kCheckEnterSceneCreator(3022)` 只出现在一份没编进 `game.sln` 的过时单测里
    （`cpp/tests/scene_test/scene_test.cpp:925-944`）。
- **副本（Dungeon 表）在基线只是字段**：SM 的 `CreateSceneRequest` 没有 dungeon 字段（`sm.proto:34-55`），SM 发给节点时也不填（`scene_node_client.go:108-113`），
  全仓除两处原样拷贝外没有写者（`scene_node_service.cpp:44`、`scene_handler.cpp:903`）。**79 里的 `dungeon_config_id` 在生产中恒为 0**；scene 侧不刷怪、不计时、
  不校验队伍人数。Dungeon 表的真实消费者是回合制战斗、匹配、帮会活动、任务（§3.2）。
- **回收**：SM 领导者每 30 s 扫一遍 `instances:zone:{z}:active`；镜像空置 ≥ 30 s、其它实例 ≥ 300 s 就用「人数仍为 0 才删」的 Lua 销毁
  （`instance_lifecycle.go:19-156`；`scene_atomic.go:132-148`）。级联（源被销毁 / 缩容收尾 / 迁移）、死节点收尾都**强制**销毁（`instance_lifecycle.go:263-297`；
  `world_autoscale.go:437-457`；`world_rebalance.go:299-330`；`load_reporter.go:413-472`）。显式 `DestroyScene` RPC **没有生产调用方**（`destroyscenelogic.go:30-120`）。
  空闲计时以「上次扫描看到有人」为起点，所以最后一人离开后约 0–30 s 镜像就可能被删（§4.2，推导）。

**Java 现状**

- 63 镜像分支一律回 3023（`ClientRequestHandler.java:217-218`）；`Scene` 已接收完整 `SceneInfoComp`，注释预告「5.3 的副本 / 镜像从这里填」（`Scene.java:26-30`）。
- scene-manager 的 `MirrorSources` 恒为「否」（`WorldChannelControlPlane.java:69-70`）；没有 Mirror / Dungeon 表的消费者；发号租约只在可竞选领导的副本上申领
  （`WorldChannelControlPlane.java:47-68`）。
- **最大的隐患：镜像与源场景的 `scene_config_id` 相同**。5.1 / 5.2 里所有「按地图挑频道」的地方都只看配置号：login 分配与 5.2 按地图选目标（`ChannelSelector.java:87`）、
  节点本地选频道与改派（`SceneWorld.java:431-457`、`:504-519`）；`applyChannelPlan` 还会把「计划外」的本地场景转排空（`SceneWorld.java:325-332`）。
  镜像一接进来，就会被当成主世界频道分人进去，或被当成孤儿排空（§9.2 R1–R3）。

**本稿的 Java 设计（§6，方案 C）**

1. **实例是承载节点自有的临时对象**：不写进 5.1 的频道计划，也不建 scene-manager 侧的 Redis 实例登记表；节点目录 `SceneEntry` 追加 `kind`、`source_scene_id`，
   **目录就是唯一的登记**（与 5.1「节点对实际状态的报告 = 目录」一致）。实例随进程消亡。
2. **镜像恒与源同节点**（发起者所在节点就是源节点）。scene 经 Dubbo `SceneDirectoryService.createInstance` 向 scene-manager 取一个全服 scene_id（scene-manager 无状态，
   放置恒为发起节点），拿到号后在**同一个逻辑任务**里建场景并 `switchScene`。客户端所见与基线相同：`{0}` → 51 / 79 / 21 / 47，同图保留坐标。
3. **回收、级联、排空都在节点逻辑线程**：实例空置满超时（镜像 30 s / 副本 300 s）→ 进入回收宽限（缺省 30 s，不接新进入、在途进场到达即复活）→ 销毁；
   源频道销毁 → 镜像转排空、居民改派到同图主世界频道（坐标保留）→ 空了销毁。人数、在途进场、销毁同在一个线程，不需要基线那套 Lua CAS。
4. **副本**：只做种类、79 字段、300 s 回收与 dev / test 专用的管理口建 / 毁（同基线无客户端入口）；玩法在 6.x 的 battle 节点。
5. **与 5.1 同批改**：选频道、改派、只带地图的 63 只认主世界频道（`kind = WORLD`）；「计划外即排空」只作用于主世界频道；`MirrorSources` 每拍从目录推导；
   发号租约挪出控制面、每个副本都申领。

**客户端所见**：成功路径同基线。与基线的差异（§10）：建镜像在途时再发 63 回 3014（基线会再建一个）；scene-manager 业务拒绝推 23 `{3023}`（基线沉默）；
应答到达时玩家已不在源场景就不建、推 23 `{3023}`（基线可能把他拽进镜像）；未知 `mirror_config_id` 回 3005（基线照收）；回收窗口精确（空置满 30 s 才停止接客）；
级联改派落同图主世界频道（基线落默认大世界）；重连回到还在的原实例（基线一律落默认大世界）。

### 0.2 盘点 id 与落点

| 盘点 id | 基线落点 | 盘点原文（Java 列） | 5.3 交付 |
|---|---|---|---|
| mirror-scene（`docs/porting/inventory/scene-core.md:232-242`） | `player_scene.cpp` RequestEnterMirrorScene；63 镜像分支；`sm_reply.cpp` | missing（`:239`） | 63 镜像分支、节点本地建镜像、自动进场、各失败出口（§6.7、§6.8） |
| dungeon-instance（`scene-core.md:244-254`） | `SceneInfoComp.dungeon_config_id` 只登记 | missing（`:251`） | `DUNGEON` 种类、79 字段、300 s 回收、dev 管理口建 / 毁（§6.13）；无客户端入口、无玩法（同基线） |
| sm-mirror-instance（`docs/porting/inventory/scene-manager-match.md:130-140`） | `createscenelogic.go` createInstance / resolveMirrorSourceNode / rollbackInstanceAllocation | missing（`:137`） | `createInstance`（发号 + 放置，无状态）；`MirrorSources` 接目录；选频道的种类过滤（§6.6） |
| sm-instance-lifecycle（`scene-manager-match.md:142-152`） | `instance_lifecycle.go`、`destroyscenelogic.go`、`scene_atomic.go` | missing（`:149`，「Java 场景随节点启动创建」已过时，5.1 起按计划建） | 节点本地空闲回收、回收宽限与复活、级联、显式销毁（§6.10、§6.11） |

### 0.3 范围

**5.3 做**：上表交付列；`xm-api` 内部契约（§6.5）；对 5.1 已提交代码与 5.2 在途代码的改动（§6.6、§6.9）；指标、配置；文档与 PARITY 登记；单测、真 Redis 集成测试、
本机切片与 robot（§12）。

**5.3 不做**：
- 副本玩法：刷怪、`time_limit` 计时踢出、`max_team_size` 准入。基线 scene 侧同样没有（`scene-core.md:249`、`:254`），PVE 在 6.x 的 battle 节点。
- 节点用途分池（`StrictNodeTypeSeparation`、`scene_node_type` 1 / 3、3004）、Agones、`MirrorSourceNodeLoadCap` 与跨节点回落、`MirrorDedupBySource`、源克隆镜像
  （`mirror_config_id == 0`）、SM 显式 `DestroyScene` RPC（§10.2）。
- 跨 zone 实例（5.4）；死节点判定与整节点疏散（5.5）；战斗冻结（6.3）。

### 0.4 与其它批次的边界与钩子

| 基线段落 | 归属 | 5.3 留下的钩子 / 约束 |
|---|---|---|
| 频道计划、`ChannelKind`（`world_channel.proto:24-28` 预告「5.3 追加副本 / 镜像」）、`MirrorSources`（`MirrorSources.java:3-17`）、`PlanResult.removed` 的级联钩子（`PlanResult.java:21`） | **5.1 已做** | `ChannelKind` 追加 MIRROR / DUNGEON，**只用于目录**，计划里出现一律拒绝（`SceneWorld.java:356-360` 不变）；`MirrorSources` 改为每拍从目录推导（§6.6）；级联在节点本地做，`PlanResult.removed` 钩子不用、只改注释 |
| 5.1 的 per-node 覆盖（`scene-channels-spec.md:1069` D6）与 D15「5.3 引入」用途分池（`:1078`） | 5.1 | 本稿**不**引入用途分池（D15 延续，§10.1 D15）；per-node 下源节点总有同图主世界频道，级联改派留在本节点 |
| 跨节点换图 + 交出（5.2 规格 §5.4–§5.8；钩子 `SceneTransfers.begin(…, Reason.MIRROR / DUNGEON)`，`:102`） | **5.2 在途** | 建镜像**用不到**交出（恒共置）；加入别的节点上的实例 = 5.2 的显式 scene_id Remote 分支（§6.12）；镜像取号期间复用 5.2 的 `RESOLVING` 状态与 4 s 本地兜底；`Reason.MIRROR / DUNGEON` 只留给将来的跨节点放置（Q2） |
| 跨 zone 传送 / 重定向（226 / 124） | 5.4 | 镜像源只能是同 zone、同节点的主世界频道，不存在跨 zone 镜像（基线的 `zone_mismatch` 回落在 Java 不适用，`createscenelogic.go:505-509`）；在实例里发 226 由 5.4 决定 |
| 死节点判定、再入屏障、整节点疏散、`NodeAvailability`（`NodeAvailability.java:12-14`） | 5.5 | 实例随进程消失，scene-manager 没有账要清（D13）；疏散 = 实例居民 `begin(…, Reason.EVACUATE)` 去别的节点的主世界频道、实例空了销毁、作废在途的 `RESOLVING`；被 5.5 标记不接新频道的节点，`createInstance` 应拒绝（钩子，§6.6） |
| 战斗冻结（63 的「战斗在途 → 3023」，`player_scene_handler.cpp:54-61`；`player_scene.cpp:137-143`） | 6.3 | 63 镜像分支的战斗闸由 6.3 的入口闸统一处理（放在 3014 之前）；`PrepareBattle` 拒绝 `switchState ≠ NONE`（含镜像取号的 `RESOLVING`）；级联 / 管理口改派跳过战斗冻结中的玩家 |
| Dungeon 表的战斗用途（回合上限、怪物组，`battle-engine-spec.md:280`、`:324`） | 6.1 / 6.4 | 与本批无交集；本批只读 Dungeon 行的 `scene_id`（副本地图） |
| 组队跟随进队长所在场景（`TeamFollowService.java:167-199`） | 4.3 已做 | 同节点跟进队长的镜像，与基线一致（基线跨节点同样不跟，`player_team.cpp:434-440`）；队长所在实例在回收宽限 / 排空中不跟（沿用 `:179-186`） |

### 0.5 客户端可见效果速览

| 情形 | 基线 | Java 现在 | Java 5.3 |
|---|---|---|---|
| 63 `{mirror = M, scene_id = 0}` 正常 | 应答 `{0}` → 旁人 51 → 本人 79（镜像 info）/ 21，下一拍 47；坐标保留 | 应答 `{3023}` | 应答 `{0}` → 旁人 51 → 79 / 21 / 47（47 当场发，已有差异）；坐标保留 |
| 在镜像里再发镜像 | 3005 | 3023 | 3005 |
| 建镜像在途再发 63 | 不受闸，再建一个镜像 | — | 3014 |
| SM 业务拒绝 | 无任何下行（卡住） | — | 23 `{3023}` |
| SM 调用失败 / 超时 | 23 `{1003}` | — | 23 `{1003}` |
| 没有 SM 可用 | 同步 3005 | — | 应答 `{0}` 后 23 `{1003}` |
| 应答到达时玩家已换到别的场景 | 不在途就被拽进镜像；在途则沉默 | — | 不建，23 `{3023}` |
| 未知 `mirror_config_id`（如 999） | 照收 | 3023 | 3005 |
| 按 id 加入别人的镜像 | 与普通显式换图相同，不查 creators | 同节点：成功；跨节点：3023 | 同节点同步；跨节点走 5.2 交出 |
| 镜像里只带地图的 63 | 只在主世界频道里选，不会留在镜像 | 可能「打平留在原地」（缺陷） | 必回主世界频道 |
| 空镜像的可加入窗口 | 最后一人离开后约 0–30 s（扫描相位） | — | 满 30 s；再过 30 s 宽限后销毁 |
| 源频道被销毁时镜像里有人 | 存盘 → 默认大世界 79 / 21 | — | 同节点改派到同图主世界频道，坐标保留，无 tip |
| 在镜像里断线、30 s 内重连 | 落默认大世界 | 回原实例（若存在） | 回原镜像（若未进入回收） |
| 副本 | 无入口，79 的 dungeon 恒 0 | 无 | 无客户端入口；dev 管理口建出的副本 79 带 `dungeon_config_id` |

---

## 1 基线数据模型与配表

### 1.1 客户端契约里的 `SceneInfoComp` 与内部消息

- **`SceneInfoComp`**（`scene_info.proto:4-11`；Java 同步副本 `xm-proto/src/main/proto/proto/scene/scene_info.proto:7-14`）：
  `uint32 scene_config_id = 1`、`uint64 scene_id = 2`、`uint32 mirror_config_id = 3`、`uint32 dungeon_config_id = 4`、`map<uint64, bool> creators = 5`。
  它是 63 请求、79 推送、31 推送的载体（`player_scene.proto:13-31`；Java 副本 `:16-34`）。
- **SM 服务**（`sm.proto:20-32`）：`CreateScene`、`DestroyScene`、`EnterScene`、`LeaveScene`。
  - `CreateSceneRequest`（`sm.proto:34-55`）：`scene_conf_id = 1`、`target_node_id = 2`、`scene_type = 3`、`creator_ids = 4`、`zone_id = 5`、`source_scene_id = 6`、
    `mirror_config_id = 7`。**没有 dungeon 字段**。
  - `CreateSceneResponse`（`sm.proto:57-70`）：`scene_id`、`node_id`、`error_code`、`error_message`、`creator_ids`（回显）。
  - `DestroySceneRequest`（`sm.proto:72-76`）：`scene_id`、`zone_id`。
- **SM → 节点**：`SceneNodeGrpc.CreateScene{config_id, scene_id, mirror_config_id, dungeon_config_id, creator_ids}`（`scene.proto:60-72`）。
  SM 发出时只填 config、scene_id、mirror、creators（`scene_node_client.go:92-119`），`dungeon_config_id` 恒不赋值。

### 1.2 场景类型、节点用途与 SM 私有码

- `SceneType`（`sm.proto:10-17`；`scene_type.go:4-7`）：1 = MAIN_WORLD、2 = INSTANCE、0 = 自动判断（World 表里有这个配置号就当主世界，`createscenelogic.go:122-131`）。
- 节点用途 `scene_node_type`（`scene_type.go:14-19`）：0 主世界、1 副本、2 跨服主世界、3 跨服副本。`StrictNodeTypeSeparation = true`（缺省，`config.go:109-118`）时，
  实例只建到 1 / 3 类节点；**镜像共置绕过这个过滤**（`createscenelogic.go:438-449` 注释）。
- SM 私有码（`errors.go:4-27`）：1 `ErrNoAvailableNode`、8 `ErrRedis`、10 `ErrInvalidSceneType`、12 `ErrNoNodeForPurpose`、13 `ErrSourceSceneGone`。
  这些码只在服务间出现；客户端最终看到什么见 §5.5。

### 1.3 实例相关的 Redis 键（除注明外无 TTL，键名不带 hash tag）

| 键 | 类型 / 值 | 写者 | 读者 | 出处 |
|---|---|---|---|---|
| `instances:zone:{z}:active` | ZSET：成员 scene_id，分 = 建立时刻（秒）；扫描看到有人时刷新为当次扫描时刻 | 建 ZADD；扫描刷新；CAS 放弃销毁时重新播种；销毁 ZREM | 空闲扫描；死节点收尾用 ZSCORE 区分「是不是实例」 | `createscenelogic.go:19`、`:257-262`；`instance_lifecycle.go:113-118`、`:229`；`load_reporter.go:450-454` |
| `instance:{id}:player_count` | STRING int（与世界频道共用） | 建时置 0；进场原子 INCR；离开 `INCRBY -1` 后负数钳 0 | 空闲判定、销毁 Lua | `createscenelogic.go:20`、`:264-265`；`scene_atomic.go:39-44`；`instance_lifecycle.go:365-379` |
| `scene:{id}:node` / `scene:{id}:zone` | STRING | `allocateScene` | 「场景还在不在」的唯一权威 | `createscenelogic.go:546-574` |
| `scene:{id}:mirror` | `"1"` | `mirror_config_id > 0` 或 `source_scene_id > 0` 时写 | 选超时（镜像 30 s） | `createscenelogic.go:267-274` |
| `scene:{id}:source` / `scene:{src}:mirrors` | STRING / SET | 带源的镜像写 | 级联、去重、缩容排除镜像源、销毁时摘出 | `createscenelogic.go:276-288`；`world_autoscale.go:285-293` |
| `node:zone:{z}:{n}:scenes` | SET 反向索引 | `allocateScene` SADD | 死节点收尾 | `createscenelogic.go:567-571` |
| `node:zone:{z}:{n}:scene_count` / `player_count` | STRING | 建 INCR；销毁减（残余人数钳 0） | 负载分、共置上限 | `createscenelogic.go:561-565`；`instance_lifecycle.go:317-329` |
| `scene:{id}:agones_gs` | STRING | 只在 Agones 模式下写 | 名额归还 | `createscenelogic.go:249-255` |

### 1.4 配表（Java 同步副本与基线逐字节相同：`config-data/tables/{mirror,dungeon,basescene,world}.pb`；schema 见 `xm-table/src/main/proto/*_table.proto`）

**Mirror**（`xm-table/src/main/proto/mirror_table.proto:15-24`）：`id`、`scene_id`（FK → BaseScene）、`main_scene_id`（FK → World）。
数据两行 `{1, scene 20, main 1}`、`{2, scene 21, main 2}`（`generated/tables/mirror.json`）。

| 列 | 运行期读者 |
|---|---|
| `id` | **无**。客户端把它当 `mirror_config_id` 发上来，C++ 只判非 0（`player_scene_handler.cpp:93`、`player_scene.cpp:124`） |
| `scene_id`（BaseScene 20 / 21，`mirror_scene.bin`） | **无**。镜像地图取源场景的配置号（`player_scene.cpp:189`） |
| `main_scene_id` | **无**。不校验源场景是不是这张主世界图 |

C++ / Go 非生成代码里 grep `MirrorTable`、`FindMirror` 为空。

**Dungeon**（`dungeon_table.proto:15-26`）：`id`、`scene_id`（FK → BaseScene）、`max_team_size`、`time_limit`（秒）、`monster`（×3，FK → Monster）。数据（`generated/tables/dungeon.json`）：

| id | scene_id | max_team_size | time_limit | monster |
|---|---|---|---|---|
| 1 | 17 | 5 | 1800 | [1, 2] |
| 2 | 18 | 5 | 3600 | [6, 7] |
| 3 | 19 | 10 | 1800 | [11, 12, 16] |

各列的消费者见 §3.2。

**BaseScene**（`generated/tables/basescene.json`）：1–16 是主世界地图；17–19 是 `dungeon_scene.bin`、20–21 是 `mirror_scene.bin`，出生点都是 (180, 200, 0)
（`basescene.json:116-149`）。基线运行期这五行只被外键引用，**没有任何场景用到它们**（镜像用源地图，副本建不出来）。
**World**：16 行，`scene_id` 1..16；第一行是默认主世界。
**MessageLimiter**：表里**没有 63 这一行**（`generated/tables/messagelimiter.json`，有 60、68，无 63），走缺省每秒 3 条（`message_limiter.h:15-16`）；
Java gate 同口径，超频回信封 1008（`ClientDispatcher.java:62-63`）。

### 1.5 Java 侧的表与访问代码

- 访问代码由 `xm-table-codegen` 编译期生成；`ConfigTables.mirror()` / `dungeon()` / `baseScene()` 已可用。Dungeon 已被任务（`MissionTables.java:153-171`，可击杀怪物集合）
  与战斗引擎（`TableBattleData.java:103`、`:125`）读；**没有 Mirror 表的读者**。
- scene 的 `SceneTables` 只暴露世界地图列表、出生点、初始技能（`SceneTables.java:10-31`）；`ConfigSceneTables.spawnPoint` 对任意 BaseScene id 都能用
  （`ConfigSceneTables.java:63-70`），副本地图 17–19 的出生点因此不需要新代码。
- scene-manager 只加载 World 表（`SceneManagerConfiguration`、`WorldSceneConfigs`）。

---

## 2 镜像场景（基线）

### 2.1 63 的同步校验顺序（`player_scene_handler.cpp:37-172`）

拒绝码都写进 TLS 里的 tip（`:25-33`），所以**成功也带一个 id = 0 的 `TipInfoMessage`**（`docs/reference/mmorpg-client-contract-scene.md` §0.3）。

1. 节点类型是副本节点 `kSceneNode(1)` / `kSceneSceneCrossNode(3)` → **3004**（`:45-52`）。
2. 回合制战斗在途 → **3023**（`:54-61`）。
3. `IsSceneChangeBusy`（冻结 / 交接意图 / 撤回待确认 / 在途槽未过期）→ **3014**（`:63-72`）。注释写明「放在镜像分支之前：冻结中的玩家同样不该去创建镜像」。
4. `scene_config_id`、`scene_id`、`mirror_config_id` 三个都为 0 → **3005**（`:74-81`；uint32 的 `<= 0` 即 `== 0`）。请求里只带 `dungeon_config_id` 也落在这一条。
5. **`mirror_config_id > 0 && scene_id == 0` → 镜像分支**（`:93-105`）：`RequestEnterMirrorScene` 返回 true → 应答 `{0}`（`:95-100`）；false → **3005**（`:101-104`）。
   这一分支里请求的 `scene_config_id` 完全被忽略；分支在 3008 与「无 SM 回 1003」之前，所以**无 SM 时镜像分支回的是 3005**。
6. 以下是非镜像分支：`scene_id > 0` 且等于当前场景 → **3008**（`:107-116`）；缺会话快照 → 3005（`:118-124`）；没有 SM → **1003**（`:140-149`）；其余 → `RequestSceneChange`
   （`:151-171`）。`scene_id > 0` 且带 `mirror_config_id` 按普通显式换图处理，SM 不看 mirror 字段（`:83-87` 注释；`sca.md:299-303`）。

### 2.2 `RequestEnterMirrorScene`（`player_scene.cpp:122-203`）

下列任一命中都返回 false，handler 回 3005：

| 条件 | 行 |
|---|---|
| `mirrorConfigId == 0`（从客户端走不到，上一步已保证非 0） | `:124-129` |
| 实体无效 | `:131-135` |
| 战斗在途（与 §2.1 第 2 步重复） | `:137-143` |
| 玩家当前不在任何场景 | `:145-151` |
| 源场景缺 `SceneInfoComp` 或 `scene_id == 0` | `:153-158` |
| **源场景本身是镜像**（`mirror_config_id != 0`，不能镜像镜像） | `:160-169` |
| 缺会话快照 | `:171-177` |
| **没有 SM 节点** | `:179-184` |

全部通过后发出 `CreateSceneRequest{scene_conf_id = 源.scene_config_id, SCENE_TYPE_INSTANCE, zone_id, source_scene_id = 源.scene_id, mirror_config_id, creator_ids = [自己]}`
（`:188-196`），**发后不管**：玩家身上不登记任何在途状态（对比普通换图经 `RequestSceneChange` 登记单槽在途记录，`player_scene_handler.cpp:162-166`）。
源是副本（`dungeon_config_id ≠ 0`、`mirror_config_id == 0`）不被拒绝——基线只看 mirror 字段。

### 2.3 SM `createInstance` 的镜像路径（`createscenelogic.go:182-325`）

1. **分流**：`CreateScene` 按 `scene_type` 走 `createMainWorldScene` 或 `createInstance`，未知类型回 10（`:87-101`）。
2. **源是否存在，只对「源克隆」检查**：`source_scene_id > 0 && mirror_config_id == 0` 时要求 `EXISTS scene:{src}:node`，不存在回 13 并计 `mirror_source_missing_total`；
   EXISTS 出错只打日志、继续（`:183-200`）。**带 `mirror_config_id` 的请求不查源**，源只是共置提示（`:184-186` 注释）。C++ 恒填 `mirror_config_id > 0`，所以生产中永不检查。
3. **去重**（`MirrorDedupBySource`，缺省 false，`config.go:161-169`）：从 `scene:{src}:mirrors` 任取一个合法成员，映射还在就直接回它（**不比较 `mirror_config_id`**）并计
   `dedup{hit}`；映射没了就 SREM 并计 `stale`；空集计 `miss`（`:103-119`、`:202-222`）。
4. **选点**（非 Agones，`pickInstanceNode`，`:454-472`）：
   1. `target_node_id` 非空就直接用，不做任何校验（`:455-457`）；
   2. 有源时尝试共置 `resolveMirrorSourceNode`（`:491-527`）：取不到源的节点映射 → `no_mapping`；源 zone 非 0 且 ≠ 请求 zone → `zone_mismatch`；源节点不活 → `node_dead`；
      `MirrorSourceNodeLoadCap > 0` 且源节点 `scene_count ≥ cap` → `overloaded`；否则共置，计 `mirror_colocate_total{hit|fallback, reason}`；
   3. 回落 `GetBestNodeForPurpose(Instance)`（`:471`）。选不出节点：严格模式回 12，否则 1（`:225-238`）。
   - **结论**：源节点就是发起者所在、活着的节点，缺省 `MirrorSourceNodeLoadCap = 0`（`config.go:159`；`sm.yaml:112`），所以**缺省配置下镜像必与发起者同节点**。
5. **发号与登记**：`allocateScene` 发号（失租即失败，`:546-551`），`SET scene:{id}:node` → `SET :zone` → `INCR scene_count` → `SADD node:…:scenes`（`:554-571`）；
   随后 ZADD active（分 = `time.Now().Unix()`，`:257-262`）→ `SET player_count 0`（`:264-265`）→ 镜像标记（`:267-274`）→ `SADD mirrors` / `SET source`（`:276-288`）。
   **无事务；除 `scene:{id}:node` 外的写失败只打日志。**
6. **建实体**：`RequestNodeCreateSceneWithOptions(conf, scene_id, mirror_config_id, creator_ids)`，用本次 RPC 的请求 ctx（受 SM `Timeout: 8000` 约束，`sm.yaml:14`）
   （`:290-309`）。失败时 `rollbackInstanceAllocation` 逐键删除、减回 `scene_count`、归还 Agones 名额，回 1（`:398-436`）。
7. **成功**：回 `{scene_id, node_id, creator_ids = 请求原样}`（`:317-324`）。**业务失败的应答都不带 `creator_ids`**（`:96-99`、`:195-198`、`:237`、`:246`、`:305-308`）。

### 2.4 节点建场景（`scene_node_service.cpp:19-66`；SM 走这条 gRPC 路径）

- 按 scene_id 幂等：已存在就回原 info，**不比较 config / mirror**（`:23-37`）。gRPC 入口不检查零号；muduo 入口有（`scene_handler.cpp:854-859`），SM 不走 muduo。
- 挂上 `SceneInfoComp`（config、mirror、dungeon、scene_id、`creators{id: true}`，`:39-50`）与空的 `ScenePlayers`（`:52`），触发 `OnSceneCreated`（`:54-56`）。
  这个事件只做 Agones 计数（`scene_event_handler.cpp:30-46`）：**不加载导航、不刷 NPC**。SM 与 yaml 注释里「镜像每次进入重置 NPC」（`createscenelogic.go:267-269`；
  `sm.yaml:100-102`）没有代码对应（§9.3 E9）。

### 2.5 应答驱动的自动进场（`sm_reply.cpp:75-200`）

| 应答情况 | 处理 | 客户端所见 |
|---|---|---|
| `error_code ≠ 0` | 只打 ERROR（`:84-90`） | **什么都没有**（已收到 `{0}`，永远等不到 79） |
| `scene_id == 0` | 当失败（`:92-96`） | 什么都没有 |
| `creator_ids` 为空 | 系统创建，不处理（`:98-102`） | — |
| 没有 SM 可发后续 EnterScene | WARN（`:104-110`） | 什么都没有 |
| 创建者已不在本节点 | 跳过（`:112-125`） | 断线者看不到；空镜像等空闲回收 |
| 缺会话快照 | 跳过（`:127-134`） | 什么都没有 |
| **`IsSceneChangeBusy`** | 放弃自动进场，「镜像由 scene_manager 按空场景回收」（`:136-146`） | **什么都没有** |
| 正常 | `EnterSceneRequest{scene_id = 镜像, session, gate…, zone}` 经 `RequestSceneChange` 发出（`:148-171`），**不带 `scene_conf_id`** | 见 §2.6 |

- 正常一行**不检查玩家是否还在源场景**：玩家在 `{0}` 之后用普通 63 换到了别处（换图已完成、不在途），应答到达时照样替他发 EnterScene，把他拽进镜像（推导，§9.1 B2）。
- **传输失败**（deadline 到期 / 不可达）：按发出请求里的 `creator_ids` 给仍在本节点的创建者推 **23 `{1003}`**（`:186-200`）。注释写明结果未知、镜像若其实已建好由空闲回收清掉；
  也写明业务失败那条路仍然通知不到创建者（`:180-185`；`deadline.md:166`、`:200`）。
- 时限：C++ → SM unary deadline 10 000 ms，SM 服务端超时 8 000 ms（`deadline.md:125`）。

### 2.6 进场与落位（自动进场成功后）

- **同节点**（缺省必然如此）：SM 经 Kafka 推路由给 gate，gate 转 `PlayerEnterGameNode`，scene 执行 `PlayerLifecycleSystem::EnterScene`：按 scene_id 找不到实体就推
  **23 `{3023}`** 并中止（`player_lifecycle.cpp:1659-1666`）；找到则 `HandleEnterScene`（`:1669`）。
- **异节点**（只在负载上限打开或源节点判死时出现）：SM 先回 18，源节点起同 zone 交接（`sm_reply.cpp:168-170`），客户端所见同 5.2 规格 §3.1。
- **`HandleEnterScene`**（`player_scene.cpp:41-120`）：已在目标场景则幂等早退（`:51-58`）；`mapChanged = 旧 conf ≠ 新 conf`（`:60-66`）——**镜像与源同 conf，所以 false**；
  BeforeLeaveScene 让源场景里看得见他的人收到 51（`:68-85`）；`EnsureValidEnterLocation(mapChanged = false)` 保留当前坐标（吸附不上导航网格才落出生点，
  `scene_spawn.cpp:91-149`）；79 携带整份 info（`:104-107`），随后发自己的 21（`:109-116`）；47 在下一拍 AOI 阶段发。

### 2.7 加入、离开、再镜像、跟随、断线、查询

- **按 id 加入**：63 `{scene_id = 镜像}`，带不带 mirror 都行，走普通显式换图：SM `resolveScene` case 1（`enterscenelogic.go:1175-1248`），预占时检查场景还在
  （`:648-670`）。**不检查 creators**，任何同 zone 玩家都能进；同图保留坐标；后加入者收到的 79 里 `creators` 仍只有创建者。
- **离开**：63 只带地图 → SM 只在 `world_channels` 集合里选（`enterscenelogic.go:1267-1286`；镜像不在集合里），**不可能留在镜像**；同图保留坐标，换图落出生点；
  63 `{scene_id = 原频道}` 回到那条频道。
- **再镜像**：在镜像里发镜像回 3005（§2.2）。
- **组队跟随**：同节点队员按队长位置记录里的 scene_id 跟随（`player_team.cpp:442-446`、`:475-491`，`playerRequested = false`），**跨节点不跟**（`:434-440`）；
  结果是队长进镜像时同节点的队员被拉进同一个镜像。
- **断线 / 重连**：SM 人数扣 1，空镜像开始回收计时；基线重连在 zone 内不回原实例、一律落默认主世界（`PARITY.md:91` 有意差异 ⑥），所以重连永远不会回到镜像。
- **查询**：43 → 31 推当前场景的 info（`player_scene_handler.cpp:185-206`），在镜像里查得到镜像的字段。

### 2.8 结局一览（基线，客户端视角）

| # | 情形 | 63 应答 | 之后 |
|---|---|---|---|
| M1 | 正常 | `{0}` | 旁人 51 → 79（镜像 info）→ 21 → 47（下一拍，非空才发），坐标保留 |
| M2 | 副本节点上发 | 3004 | — |
| M3 | 战斗在途 | 3023 | — |
| M4 | 换图 / 交接在途 | 3014 | — |
| M5 | 镜像里再发 / 没有当前场景 / 没有 SM / 缺会话 | 3005 | — |
| M6 | CreateScene 传输失败 | `{0}` | 23 `{1003}` |
| M7 | SM 业务失败（严格分池无副本节点 12、发号失败 8、节点建场景失败 1） | `{0}` | **无** |
| M8 | 应答到达时换图在途 / 已断线 | `{0}` | **无**（断线的人看不到） |
| M9 | 自动进场被 SM 拒绝（非 18） | `{0}` | 23 `{3023}`（`player_lifecycle.cpp:3956-3961`） |
| M10 | 自动进场传输失败 | `{0}` | 23 `{1003}`（`player_lifecycle.cpp:3209-3216`） |
| M11 | 自动进场路由到节点时镜像已没了 | `{0}` | 23 `{3023}`（`player_lifecycle.cpp:1661`） |

---

## 3 副本（基线）

### 3.1 没有生产者

- SM `CreateSceneRequest` 没有 dungeon 字段（`sm.proto:34-55`）；SM → 节点只填四项（`scene_node_client.go:108-113`）。
- 全仓 `dungeon_config_id` 的写者只有两处原样拷贝（`scene_node_service.cpp:44`、`scene_handler.cpp:903`），没有来源。
- SM `CreateScene` 的调用方全仓只有 C++ 镜像这一处（`player_scene.cpp:196`）；Go 侧构造 `CreateSceneRequest` 的只有 SM 自己对节点的调用（`scene_node_client.go:108`）。
  所以「不带 source / mirror 的普通实例」（300 s 回收那一类）**也没有调用方**。
- `scene_test.cpp:912-914` 的 `TEST(SceneNodeTest, CreateDungeon)` 是空函数。
- 客户端 `GameClient.EnterScene` 只填 `SceneConfigId` / `SceneId`（mmorpg-client `Assets/Scripts/Game/GameClient.cs:1167-1185`），79 处理只读 `SceneId` / `SceneConfigId`
  （`:1697-1707`）；客户端代码里没有任何地方读 `MirrorConfigId` / `DungeonConfigId` / `Creators`。基线 Go robot 也不读这三个字段
  （`docs/reference/mmorpg-client-contract-robot.md:326`）。**镜像链今天只有自定义客户端或测试工具会走到。**

### 3.2 Dungeon 各列的消费者

| 列 | 消费者 |
|---|---|
| `id` | 等于 `battle_config_id`：匹配 PVE、组队 PVE、帮会历练 `GuildActivity.dungeon_id`（`go/guild/internal/activity/config.go:75`） |
| `scene_id`（BaseScene 17–19） | **无** |
| `max_team_size` | **无**：人数取 yaml `PveTeamSizeByConfigId` 再按 5 收口（`go/match/internal/config/config.go:92-93`；`go/match/internal/logic/gather.go:30-35`）；表里有 10 的历史行 |
| `time_limit` | 战斗回合上限，0 时 30 回合（`battle-engine-spec.md:280-282`） |
| `monster` | 战斗怪物组（跳过 0，`battle-engine-spec.md:324-326`）；任务「可击杀怪物」（`MissionTables.java:160-171`） |

**scene 侧不读这张表的任何列**（`scene-core.md:249`、`:254` 已明示「勿当成已有行为」）。「副本进度」只存在于回合制战斗里：match / team 凑人 → battle 建房 →
按 Dungeon 的 `time_limit` / `monster` 开战 → 结算，scene 侧只负责备战冻结（6.3）。

### 3.3 对 Java 的含义

两版都没有副本的生产入口。Java 5.3 只交付：`DUNGEON` 种类、按基线 `SceneInfoComp` 填 79（`dungeon_config_id = Dungeon.id`，地图 = `Dungeon.scene_id`）、
300 s 空闲回收、dev / test 专用的管理口（§6.13）。地图取 `Dungeon.scene_id` 是 Java 的选择（基线由调用方传 conf，没有调用方），见 D16。

---

## 4 实例生命周期与空闲回收（基线）

### 4.1 空闲扫描（`instance_lifecycle.go:19-156`）

- 两种超时都 ≤ 0 时不启动（`:23-26`）。扫描间隔 `InstanceCheckIntervalSeconds` 缺省 30，≤ 0 时取 30（`:28-32`；`config.go:143-145`）。
- 只有领导者执行（`:45-49`）；长循环里每条都重查领导权，降级即停（`:96-100`）。
- 超时：镜像（`scene:{id}:mirror == "1"`）用 `MirrorIdleTimeoutSeconds`，≤ 0 时回落 `InstanceIdleTimeoutSeconds`（`:58-63`、`:120-130`）；某种类的超时 ≤ 0 = 永不回收
  （`:132-135`）。缺省 300 / 30（`config.go:130-141`；`sm.yaml:96-104`）。
- 判定：人数 > 0 → 用当前时刻刷新分数（`:113-118`）；否则 `now − 分数 ≥ 超时` 就 `destroyInstance`（`:137-150`）。

### 4.2 实际窗口（推导：以 30 s 扫描粒度代入上面的规则）

| 情形 | 镜像（30 s） | 普通实例（300 s） |
|---|---|---|
| 最后一人在相邻两次扫描之间离开 | 下一次扫描（**约 0–30 s 后**）即删：分数是上次「看到有人」的扫描时刻，不是离开时刻 | 约 270–300 s 后 |
| 建好后从没人进过 | 建立后 30–60 s | 300–330 s |

所以 `sm.yaml:100-104` 注释里「吸收短暂断线 / 加载」的说法得不到保证：最后一人刚离开、想马上重进的玩家可能已经进不去（§9.1 B11）。

### 4.3 进场 INCR 与销毁 CAS（`scene_atomic.go:12-29` 的设计说明）

- 进场：`AtomicIncrPlayerCountIfSceneExists`——映射在才 INCR，否则 −1；−1 → 回 1 并计 `enter_scene_rejected_total{scene_gone}`（`scene_atomic.go:39-44`、`:208-218`；
  `enterscenelogic.go:648-670`）。
- 空闲销毁：`luaAtomicDestroyInstance` 在「人数为 0 或键不存在」时读出节点与 Agones 名额、DEL 六个键 + ZREM；人数 > 0 则放弃（`scene_atomic.go:132-148`），
  放弃时重新播种空闲时钟（`instance_lifecycle.go:226-231`）。两段 Lua 在 Redis 里串行，堵住「边销毁边进场」。
- 强制销毁：`luaAtomicDestroyInstanceForce` 读与删同一段脚本，并发的多次销毁只有一个赢家执行副作用（`scene_atomic.go:157-197`；`instance_lifecycle.go:239-257`）。

### 4.4 销毁路径一览

| 路径 | 触发 | 原子性 | 级联 | 对节点 | 出处 |
|---|---|---|---|---|---|
| idle | 空闲扫描 | 人数 CAS | 确认销毁**之后**强制级联子镜像（注释记录了「先级联」的旧 bug） | 节点活着就发一次 `DestroyScene` | `instance_lifecycle.go:193-345` |
| node_death | 死节点收尾（只销毁 active 集合里的成员，世界频道跳过） | force Lua | 同上 | 节点已死，不发 | `load_reporter.go:413-472` |
| cascade | 源被 idle / 显式销毁 | force Lua | 递归 | 同上 | `instance_lifecycle.go:276-297`；`destroyscenelogic.go:56-73` |
| source_scaled_in | 缩容收尾（「排空窗口里新建的镜像」兜底） | force Lua | — | 同上 | `world_autoscale.go:437-457` |
| source_migrated | 再平衡迁移源频道（**基线迁移不跳过镜像源**，`channelHasMirrors` 只在缩容用，`world_autoscale.go:312`） | force Lua | — | 同上 | `world_rebalance.go:292`、`:299-330` |
| explicit | SM `DestroyScene` RPC，**无生产调用方**（只有 Go 客户端桩） | **非原子**：快照读 + 逐键 DEL；不读、不还 `agones_gs` | 先级联 | 发 RPC | `destroyscenelogic.go:30-120` |
| 孤儿频道清理 | World 表删图 | 逐键 | **不级联**：DEL `scene:{id}:mirrors` 但不销毁子镜像 | DestroyScene | `orphan_cleanup.go:156-199`（`:190`） |

指标 `instance_destroyed_total{zone_id, kind, reason}`（`metrics.go:97-101`）的 Help 漏了实际在用的 `source_scaled_in`。

### 4.5 节点侧 `DestroyScene`（`scene_node_service.cpp:68-125`）

- 找不到场景按幂等成功（`:85-89`）。
- **还有人**：`BeginSceneDrain`（逐个存盘 → 写交接标记 → `EnterScene(0,0)` 落默认大世界），实体留着、直接返回，靠调用方「下一拍重试」（`:91-115`）。
  注释说实例不会走到这一支（CAS 挡住），级联与缩容会走到（`:106-108`）。
- **但强制销毁路径上 SM 已原子删光键，之后不会再发第二次 `DestroyScene`**（`instance_lifecycle.go:299-305` 只发一次）→ 节点上留下一个空实体直到进程重启（推导，§9.1 B6）。
- 被改派的玩家落点成功时 SM 去扣旧实例的人数（`enterscenelogic.go:726-730`）；那个计数键已被删，`INCRBY −1` 把它重建为 −1 再钳成 0 → 留下无 TTL 的孤儿键
  （`instance_lifecycle.go:365-370`；推导，§9.1 B7）。
- 改派去向是默认大世界（World 第一行），即使镜像是别的图；新实体 `mapChanged = false`，坐标只要在目标图导航网格上就保留（推导，§9.1 B14）。

### 4.6 Java 现状

Java 没有按需实例，也没有回收。可复用的部件：`drainStep` 每秒一次，排空场景的居民同节点改派（同图优先 → 默认大世界 → 原地等），空了且没有在途进场才销毁
（`SceneWorld.java:381-397`、`:399-442`；`SceneNode.java:319-325`，HEAD）；`hasPendingEnter` 挡住销毁（`SceneWorld.java:459-466`）；进场遇排空改投兄弟频道
（`SceneWorld.java:591-601`）。

---

## 5 客户端可见行为（消息号、tip、时序）

### 5.1 消息号（`message_id.txt`）

| 号 | 方向 | 消息（`player_scene.proto`） | 本批用途 |
|---|---|---|---|
| 63 | C2S | `EnterSceneC2SRequest{SceneInfoComp scene_info = 1}` → `EnterSceneC2SResponse{TipInfoMessage error_message = 1}` | 建镜像 / 按 id 进实例 / 离开实例 |
| 79 | S2C | `EnterSceneS2C{SceneInfoComp scene_info = 1}` | 进入镜像、副本或离开后的主世界 |
| 21 | S2C | `ActorCreateS2C`（自己 + 给看得见的人） | 同上 |
| 47 | S2C | `ActorListCreateS2C`（进场者看得见的人，非空才发） | 同上 |
| 51 | S2C | `ActorDestroyS2C`（发给旧场景里看得见他的人） | 离开源场景 / 实例 |
| 43 / 31 | C2S / S2C | `SceneInfoC2S`（应答 Empty）→ 推 `SceneInfoS2C{repeated SceneInfoComp}` | 在实例里查询，得到实例的 info |
| 23 | S2C | `SendTipToClient(TipInfoMessage)` | 异步失败 1003 / 3023 |

行号：63 在 `:64`、79 在 `:80`、21 在 `:22`、47 在 `:48`、51 在 `:52`、43 在 `:44`、31 在 `:32`、23 在 `:24`。Java 注册点：63 `ClientRequestHandler.java:105`，43 `:107-108`。
**本批不新增任何客户端消息号、tip 码或字段。**

### 5.2 79 的 `scene_info`（两版必须逐字段一致）

| 种类 | `scene_config_id` | `scene_id` | `mirror_config_id` | `dungeon_config_id` | `creators` |
|---|---|---|---|---|---|
| 主世界频道（5.1） | World 图号 | 频道号 | 0 | 0 | 空 |
| **镜像**（基线 `scene_node_service.cpp:39-50`；Java 同） | **源场景的配置号**（`player_scene.cpp:189`），**不是** Mirror.scene_id（20 / 21） | 新的全服雪花号 | 63 里带的值（Java 先查表，D7） | 0（SM 不转发） | `{创建者: true}`，**只有一项**；后加入的人看到的也只有创建者 |
| 副本（Java，只有 dev 管理口入口） | `Dungeon[id].scene_id`（BaseScene 17–19） | 新号 | 0 | `Dungeon.id` | 空（系统创建，同基线 `sm_reply.cpp:98-102` 的「系统创建无创建者」） |

- 编码（镜像，dungeon 为 0 不上线）：`EnterSceneS2C` = `0A len { 08 conf | 10 scene_id(varint) | 18 mirror | 2A 04 { 08 creator | 10 01 } }`。
  固定输入 `{conf = 1, scene_id = 0x0102030405060708, mirror = 1, creators = {42: true}}` 的字节是
  `0A 14 08 01 10 88 8E 98 A8 C0 E0 80 81 01 18 01 2A 04 08 2A 10 01`（本稿用 perl 按 protobuf 规则算出，作为 §12.2 的字节级断言）。
- map 只有一项，序列化确定。Java 必须 `putCreators(pid, true)`：写成 false 时 map 条目变成 `2A 04 08 2A 10 00`（Java 的 map 条目总是写出键和值），
  语义与字节都和基线 `{id: true}` 不同；漏写则整个 `2A …` 段消失。
- 43 → 31 推的是当前场景这一条（`player_scene_handler.cpp:185-206`；Java `ClientRequestHandler.java:107-108`），在实例里查同样带上述字段。

### 5.3 tip（`scene_error_tip.proto`、`common_error_tip.proto`；文案 `config-data/tables/tip_text.json`）

| 码 | 名 | 本批出现的位置 | 文案 |
|---|---|---|---|
| 3000 | kEnterSceneNotFound | 只在服务间（scene-manager 的拒绝码，`SceneAssigner.java:46`），客户端看到的是 23 `{3023}` | 「想进入的场景未找到」（`:149`） |
| 3004 | kEnterSceneServerType | 基线：63 发在副本类型节点上；**Java 不会出现**（D15） | 「服务器未找到」（`:152`） |
| 3005 | kEnterSceneParamError | 三个号全 0；镜像分支的一切同步失败 | 无 |
| 3008 | kEnterSceneYouInCurrentScene | 指定的 scene_id 就是当前场景（镜像分支不会出） | 无 |
| 3014 | kEnterSceneChangingScene | 换图 / 交接在途；Java 另含「建镜像在途」 | 无 |
| 3022 | kCheckEnterSceneCreator | **两版运行期都不产生**（死码） | 无 |
| 3023 | kEnterSceneFailed | 战斗在途（同步）；异步拒绝（23）；显式进入排空中 / 已回收的实例 | 无 |
| 1003 | kServiceUnavailable | CreateScene / 取号调用失败（23）；基线普通分支没有 SM（同步） | 「服务不可用」（`:6`） |

### 5.4 63 镜像分支的同步应答（基线 vs Java）

| 顺序 | 基线 | Java 5.3（5.2 + 6.3 落地之后） |
|---|---|---|
| 1 | 副本节点 → 3004 | 不适用（D15） |
| 2 | 战斗在途 → 3023 | 同（6.3 接入） |
| 3 | 换图 / 交接在途 → 3014 | `switchState ≠ NONE`（含建镜像在途的 `RESOLVING`）→ 3014 |
| 4 | 三号全 0 → 3005 | 同 |
| 5 | 镜像分支：任一失败 → 3005；派发成功 → `{0}` | 镜像分支：当前场景不是主世界频道（含镜像的镜像）、`mirror_config_id` 不在 Mirror 表、当前频道在排空、本节点停止接客、实例数达上限 → **3005**；否则 `{0}` |
| 6 | （非镜像）scene_id 等于当前 → 3008，等等 | 同 5.1 / 5.2 |

Java 把新增的同步拒绝都归到 3005，**这个分支在客户端看来的码集合与基线相同**（`{0}`、3005，外加分支之前的 3014 / 3023）（Q11）。

### 5.5 异步结局（基线 vs Java）

| 情形 | 基线 | Java 5.3 |
|---|---|---|
| scene-manager 业务拒绝 | 无（M7） | 23 `{3023}`（D5） |
| 调用失败 / 超时 / 发号租约无效 | 23 `{1003}`（M6；租约无效在基线是业务失败 8 → 无） | 23 `{1003}` |
| 没有 SM | 同步 3005（M5） | 应答 `{0}` 后 23 `{1003}`（D9） |
| 应答到达时创建者已断线 | 无 | 无（结果被丢弃，不建，D3） |
| 应答到达时创建者换图在途 | 无（M8） | 不会出现：在途期间 63 回 3014，组队跟随跳过；5.1 排空改派可能把人挪走 → 见下一行 |
| 应答到达时创建者已不在源场景、源在排空、节点停止接客 | 不在途就被拽进镜像（推导，B2） | 不建，23 `{3023}`（D6） |
| 正常 | 旁人 51 → 79 / 21 → 下一拍 47 | 旁人 51 → 79 / 21 → 47 当场发（`SceneWorld.java:54-57` 的既有差异） |

### 5.6 坐标与存盘

- 同节点换入同配置号的镜像：保留坐标、清速度（Java `SceneWorld.java:742-760`，`:751` 同图保留）；基线 `mapChanged = false` 同样保留（§2.6）。
- 从镜像回同图主世界频道：保留坐标（两版一致）。进出副本：地图不同，落 BaseScene 出生点（Java `:751`）。
- 存盘的 `scene_config_id` 是当前场景的配置号（`ScenePlayer.java:307-308`）：在镜像里是源地图，在副本里是 17–19。下次登录按地图选：镜像 → 源地图频道，副本 → 不是世界地图，
  回落默认主世界出生点（`SceneAssigner.java:89-98`；PARITY ⑦）。

### 5.7 回收、级联、节点死亡时客户端所见

| 情形 | 基线 | Java 5.3 |
|---|---|---|
| 空闲回收 | 场景里没人，不可见；之后显式进入该号失败（23 `{3023}`） | 同（同节点 63 同步回 3023；跨节点 `{0}` 后 23 `{3023}`） |
| 源被销毁 / 缩容 / 迁移时镜像里有人 | 存盘 → 旁人 51 → 本人默认大世界的 79 / 21（可能跨节点），无 tip | 只有「源被销毁」会级联（缩容 / 迁移跳过镜像源）：同节点改派到同图主世界频道，坐标保留，无 tip、不存盘（沿用 5.1 D8） |
| 宿主节点死亡 | 断线 → 重连落默认大世界 | 断线 → 重连：实例已不在目录 → 按原地图（镜像 = 源地图）或默认主世界（副本） |
| dev 管理口显式销毁 | 无对应（RPC 无调用方） | 居民改派：镜像 → 同图主世界频道；副本 → 默认主世界出生点；无 tip |

### 5.8 时限一览

| 项 | 基线 | Java 5.3 |
|---|---|---|
| 63 应答 | 同步 | 同步 |
| 建镜像调用 | C++ deadline 10 s / SM 服务端 8 s（`deadline.md:125`） | Dubbo 提供方 3 s（`xm-scene-manager/src/main/resources/application.yaml:94`）+ scene 本地兜底 4 s（5.2 `switch-resolve-timeout`） |
| 应答到 79 | 一次 SM EnterScene 往返 + Kafka 路由 | 应答回到逻辑线程后在同一个任务里完成 |
| 镜像可加入窗口（最后一人离开后） | 约 0–30 s（扫描相位） | 满 30 s（`mirror-idle-timeout`，1 s 粒度） |
| 镜像销毁（最后一人离开后） | 约 0–30 s | 约 60 s（30 s 空置 + 30 s 回收宽限） |
| 副本 | 270–300 s | 300 s + 30 s |
| 死节点上的实例 | 再入屏障 20 s 后强制销毁 | 随进程消失 |
| 级联 | 与源的销毁同步 | 源销毁的同一个逻辑任务里转排空，≤ 1 s 内改派 |

### 5.9 限频

63 走 gate 缺省每秒 3 条（§1.4）。基线一个玩家每秒最多 3 次建镜像请求，每个空镜像活约 0–60 s，可堆出数十个空镜像（推导，B12）。Java 的 `RESOLVING` 让同一玩家同时只有一个在途，
建好的镜像必须真的进去、出来才能再建（镜像里不能建镜像），再加每创建者 / 每节点上限（D19）。

---

## 6 Java 落地映射

### 6.1 现状与缺口

| 位置 | 现状 | 5.3 要做什么 |
|---|---|---|
| `ClientRequestHandler.enterScene`（`:209-238`） | 镜像分支回 3023（`:217-218`）；顺序：全 0 → 3005、镜像、3008（`:219-220`）、`resolveSwitchTarget`；显式号配置不符 → 3005（`:225-226`） | 镜像分支改为异步建镜像（§6.7） |
| `Scene`（`Scene.java:22-44`） | 收完整 `SceneInfoComp`（`:26-30`）；只有 `draining` 一个状态位（`:35`） | 加种类、源、空置时刻、排空原因、排空起点（§6.4） |
| `SceneWorld.applyChannelPlan`（`:278-340`） | 计划外的本地场景一律转排空（`:325-332`）；非 WORLD 种类的计划记录被拒（`:356-360`） | 前者只作用于 WORLD（R2） |
| `leastLoadedActive` / `relocationTarget`（`:431-457`）、`resolveSwitchTarget`（`:504-519`） | 按配置号选，不分种类；只带当前地图时并列留原地（`:515`，5.1 D17） | 只把 WORLD 当频道；身在实例里只带地图必回 WORLD（R3） |
| `sceneEntries`（`:238-249`）、`publishChannels`（`:227-235`） | 目录只带号 / 图 / 人数 / 排空；频道指标数全部场景 | 目录带 `kind`、`source_scene_id`；频道指标只数 WORLD |
| `onPlayerEnter`（`:523-550`）/ `onPlayerLoaded` 排空重定向（`:591-601`） | 场景不在本节点就失败（`:536-539`）；排空中改投兄弟 | 目标是「回收宽限中」的实例 → 复活后照常进入（§6.10） |
| `ChannelSelector.select`（`:84-93`，工作区） | 候选只按配置号、非 0、非排空过滤（`:87`） | 加 `kind ∈ {UNSPECIFIED, WORLD}`（R1） |
| `SceneAssigner` 原实例分支（`:78-88`） | 种类无关：还在且不在排空就直接用 | 保持（重连回实例，D14） |
| `SwitchTargetSelector`（5.2 在途） | 显式号不限种类并写预占（`selectExplicit`，`:89-129`）；只带地图要求世界地图，注释以为这样能挡住镜像（`:25-27`、`:77-80`） | 显式号命中实例照常；只带地图靠 `ChannelSelector` 的种类过滤；改注释 |
| `MirrorSources`（`MirrorSources.java:3-17`） | 控制面固定传 `NONE`（`WorldChannelControlPlane.java:69-70`）；缩容（`WorldAutoscaler.java:139`）、再平衡（`WorldRebalancePlanner.java:81`）已调用 | 每拍从 `PlanInput.directory()` 推导（§6.6） |
| 发号租约 | 在 `WorldChannelControlPlane.start` 里申领，`leader-eligible = false` 时不申领（`:47-68`） | 挪成独立 bean，每个副本都申领（R5） |
| `world_channel.proto` `ChannelKind`（`:24-28`） | 只有 WORLD | 追加 MIRROR = 2、DUNGEON = 3（只用于目录与 `createInstance`） |
| `node_directory.proto` `SceneEntry`（`:44-50`） | 号 / 图 / 人数 / 排空 | 追加 `kind = 5`、`source_scene_id = 6` |
| `SceneTables` | 无 Mirror / Dungeon | 加 `mirrorExists(int)`、`dungeonSceneConfigId(int)` |

### 6.2 方案选择：实例登记放在哪里

| 方案 | 做法 | 问题 | 结论 |
|---|---|---|---|
| A 扩展 5.1 频道计划 | 实例作为 `kind = MIRROR / DUNGEON` 的记录写进 `xm:world:{z:<zone>}:ch` | 计划只由分 zone 领导者经「令牌 + 版本号 CAS」写（`architecture.md:500-505`），数据面每建一个镜像都要抢 `ver` 或等一拍（5 s）；每次变更让全 zone 节点重读整张 HASH；节点重启会按计划把早已无人的临时镜像重建出来；重新打开 5.1 对非 WORLD 记录的 fail-closed 门（`SceneWorld.java:356-360`） | 否决 |
| A′ 独立的 Redis 实例登记表（scene-manager 分区稿） | `xm:world:{z:<zone>}:inst` HASH（值为 `SceneInstance` pb）+ 每节点索引与版本号 + 源到镜像索引；数据面 Lua 建；宿主节点 Lua 认领回收（人数为 0、无在途、无 Redis 预占）；领导者清死节点与级联；节点按版本号拉取 | 能支持跨节点放置与「宿主未建好就有人来」，但要多一套键空间、6 段 Lua、节点与 Redis 两份真相（需要节点局部版本守卫防误删）、三方写者；而**缺省配置下基线镜像本来就恒共置**（§2.3），这套机制只服务一个基线缺省关闭的回落 | 不采纳（留作 Q2 的升级路径） |
| B 照搬基线 SM 注册表 | `instances:zone:active` ZSET + 每场景键 + SM 人数计数 + 领导者 30 s 扫描 + SM → scene 的 Create / Destroy RPC | Java scene-manager 不在进场路径上，在场人数的权威是节点；照搬就得在每条进出路径加 INCR / DECR，并引回 5.1 D3 刻意去掉的命令式 RPC（`scene-channels-spec.md:1066`） | 否决 |
| **C 节点自有 + 目录登记** | 实例活在承载节点内存里、随进程消亡；目录 `SceneEntry` 带种类与源；scene_id 由 scene-manager 发；回收、级联、排空都在节点逻辑线程 | 目录体积随实例数增长（R6，用上限约束）；跨节点放置要另做（Q2） | **采纳** |

方案 C 成立的前提是「镜像恒与源同节点」：发起请求的节点就是源节点，它当然活着；基线的回落只在源节点判死、跨 zone 或打开负载上限时发生（§2.3），Java 节点同构、
不分池（5.1 D15）。人数、在途进场、销毁同在一个逻辑线程，基线要用 Lua 堵的「边销毁边进场」竞态在本节点内不存在；跨节点的在途进场由回收宽限兜住（§6.10）。

### 6.3 总体与职责

```
client ─63{mirror=M, scene=0}─▶ gate ─ClientForward─▶ scene S（逻辑线程）
   ◀── 63{0} ───────────────────────────────────────── 同步校验（§6.7）→ RESOLVING{token, MirrorCreate(src, M)}
                                                      ─Dubbo createInstance─▶ scene-manager（任一副本，无状态）
                                                                               参数校验 → 放置 = S → 全服发号
                                                      ◀──── {tip 0, node = S, scene_id} ──┘
                                                      [逻辑线程] token 校验 → 玩家仍在源、源未排空、节点接客
                                                      → createInstance(SceneInfoComp{源 conf, id, M, 0, {pid: true}}, MIRROR, src)
                                                      → 回 NONE → switchScene(player, mirror)
   ◀── 51（源场景旁人）/ 79 / 21 / 47（同图坐标保留）────────┘
                                                      → requestDirectoryPublish()（SceneEntry{kind = MIRROR, source = src}）
每秒（逻辑线程）：空置满超时 → 回收宽限（目录报 draining）→ 宽限满且仍空、无在途 → 销毁；源频道销毁 → 镜像转排空 → 居民同图改派 → 空了销毁
scene-manager 领导者（每拍）：DirectoryMirrorSources.of(目录) → 缩容 / 再平衡跳过镜像源
```

- **权威**：实例的存在与人数以承载节点内存为准；目录是它的报告（5 s 一次，TTL 15 s，`SceneDirectoryPublisher.java:31-32`；建立 / 进入回收 / 复活 / 级联 / 销毁时立即补发）。
- **scene-manager 对实例零状态**：只发号、定放置（5.3 恒为发起节点）、计指标；规划器从目录推导「谁是镜像源」。

### 6.4 数据模型（xm-scene，只在逻辑线程读写；不用 ECS 命名）

`Scene` 新增字段：

| 字段 | 含义 |
|---|---|
| `SceneKind kind` | WORLD（计划建的频道）/ MIRROR / DUNGEON；与 `ChannelKind` 互转 |
| `long sourceSceneId` | 镜像的源（同节点上的 WORLD 频道），其它种类为 0 |
| `long emptySinceNanos` | 实例最近一次变空（或创建）的单调时刻；有人在场或有指向它的在途进场时为 0 |
| `DrainCause drainCause` | NONE / PLAN（5.1 计划）/ IDLE（回收宽限）/ CASCADE（源已销毁）/ ADMIN（管理口显式销毁） |
| `long drainingSinceNanos` | 进入排空的单调时刻（IDLE 宽限据此计） |

`draining()` 语义不变（「不接新进入、在场者被改派、空了销毁」），`drainCause` 只决定**何时**销毁与能否复活。`info`（`SceneInfoComp`）按 §5.2 逐字段填写，是 79 / 31 的唯一来源。

`SceneWorld` 新增 `createInstance(InstanceSpec)`，**是建实例的唯一入口**；`addScene` 改为只接收已校验的 info，不变量：scene_id 本地唯一。现有 `createScene(int)`
（`:199-201`，只给单测与本地装配用）不变。

### 6.5 内部契约变更（Java 自有 proto / 接口，不是同步产物）

**`world_channel.proto`**（只追加，兑现 `:24` 的注释）：

```proto
enum ChannelKind {
  CHANNEL_KIND_UNSPECIFIED = 0;
  CHANNEL_KIND_WORLD = 1;
  // 批次 5.3：节点自有的实例。只出现在节点目录（SceneEntry.kind）与 createInstance 里；
  // 频道计划里出现这两种一律拒绝（SceneWorld.rejection，fail-closed）。
  CHANNEL_KIND_MIRROR = 2;
  CHANNEL_KIND_DUNGEON = 3;
}
```

**`node_directory.proto` 的 `SceneEntry`**（只追加）：

```proto
  // 场景种类（批次 5.3）：WORLD = 频道计划建的主世界频道；MIRROR / DUNGEON = 节点自有的实例（不在计划里，随进程消亡）。
  // UNSPECIFIED（不认识这个字段的旧版本节点）按 WORLD 读。draining 对实例的含义扩展为「回收宽限中 / 级联排空中 / 显式销毁中」。
  ChannelKind kind = 5;
  // MIRROR 的源（同节点上的主世界频道）；其余为 0。scene-manager 据此回答「这个频道是不是镜像源」。
  uint64 source_scene_id = 6;
```

`mirror_config_id` / `dungeon_config_id` / `creators` **不进目录**：没有服务端读者（YAGNI），目录越小 login 越快（R6）。`SceneNodeInfo.scene_node_type`（`:39-41`）继续恒 0。

**`scene_directory.proto`**（追加）：

```proto
// scene → scene-manager：为一个新的副本 / 镜像实例取全服 scene_id 并定放置（批次 5.3）。
// scene-manager 不记任何状态、不写 Redis（发号租约除外）、不调 scene；节点拿到号后在本地建。
message CreateInstanceRequest {
  uint32 zone_id = 1;
  uint32 requester_scene_node_id = 2;  // 发起节点（5.3 放置恒为它）
  uint64 player_id = 3;                // 发起玩家；dev 管理口建副本时为 0
  ChannelKind kind = 4;                // MIRROR / DUNGEON
  uint64 source_scene_id = 5;          // MIRROR：源（发起玩家当前所在的主世界频道）；DUNGEON：0
  uint32 scene_config_id = 6;          // 实例的地图：MIRROR = 源的配置号；DUNGEON = Dungeon.scene_id
  uint32 mirror_config_id = 7;
  uint32 dungeon_config_id = 8;
}
message CreateInstanceResponse {
  uint32 tip_id = 1;           // 0 = 成功；非 0 只进日志与指标，scene 对客户端推 23 {3023}
  uint32 scene_node_id = 2;    // 实例落在哪个节点（5.3 恒等于 requester_scene_node_id）
  uint64 scene_id = 3;         // 全服雪花（NodeTypes.SCENE_MANAGER 租约）
}
```

**`SceneDirectoryService`** 追加 `CompletableFuture<CreateInstanceResponse> createInstance(CreateInstanceRequest)`：业务拒绝放 `tip_id`，基础设施异常（含发号租约无效）以
future 异常完成，与 `assign` / `selectSwitchTarget` 的契约一致（`SceneDirectoryService.java:15-26`）。scene 侧复用 5.2 建好的同一个编程式引用（`retries = 0`、`check = false`，
5.2 规格 §5.4）。

**`DirectoryView.Scene`**（`DirectoryView.java:56`）加 `kind`、`sourceSceneId`；`of(...)` 照抄（`:63-81`）。

**dev 管理口**（新文件 `xm-api/src/main/proto/xm/api/scene_admin.proto`，只给 robot / 切片用）：
`CreateDungeonInstanceRequest{uint32 dungeon_config_id = 1}` → `CreateDungeonInstanceResponse{uint32 tip_id = 1; uint64 scene_id = 2; uint32 scene_config_id = 3; uint32 scene_node_id = 4}`；
`DestroyInstanceRequest{uint64 scene_id = 1}` → `DestroyInstanceResponse{uint32 tip_id = 1}`（§6.13）。

**`SceneTables`**：加 `boolean mirrorExists(int mirrorConfigId)`（按无符号 id 查，`≥ 2^31` 的值在 Java int 里为负，表里不可能有）、`OptionalInt dungeonSceneConfigId(int)`。

### 6.6 scene-manager

**`createInstance`**（新类 `InstanceIdIssuer`，Dubbo 业务线程，无状态）：

1. 参数校验，不合法回 `TIP_BAD_REQUEST`（3005，`SceneAssigner.java:49`）：`zone_id`、`requester_scene_node_id`、`scene_config_id` 为 0；`kind` 不是 MIRROR / DUNGEON；
   MIRROR 缺 `source_scene_id` / `mirror_config_id` / `player_id`；DUNGEON 缺 `dungeon_config_id`。**不复核表与源**：节点是权威，已在逻辑线程上校验过；
   scene-manager 再读可能过时的目录只会多出误拒（与 5.2 `selectSwitchTarget` 的「只做选择」口径一致）。
2. 放置：5.3 恒为 `requester_scene_node_id`（D2）。5.5 接入后，`NodeAvailability.acceptsNewChannels(zone, requester) == false` 时回 3000（钩子）。
3. 发号：`LeaseGatedSnowflake.tryNext()`；为空（租约无效）→ future 异常完成（scene 推 23 `{1003}`），指标 `result = no_lease`（Q12）。
4. 不写 Redis、不写预占、不调 scene。

**发号租约挪出控制面**：`NodeIdLease` + `LeaseGatedSnowflake` 从 `WorldChannelControlPlane.start`（`:51-68`）挪成独立 bean `SceneIdAllocator`，**每个副本都申领**
（`leader-eligible = false` 的金丝雀也要能发实例号）；控制面与 `createInstance` 共用。`Snowflake.nextId` 是 synchronized 的，规划器线程与 Dubbo 线程可以共用。
worker 号段 1..1023 全服共享（`WorldChannels.java:20-24`），scene-manager 副本数远小于它。

**`MirrorSources` 接目录**：`WorldChannelPlanner` 不再在构造时固定一个 `MirrorSources`（`WorldChannelPlanner.java:50`、`:58-66`），改为每拍
`DirectoryMirrorSources.of(input.directory())`（构造参数换成 `Function<DirectoryView, MirrorSources>`，测试仍可注入）。判定：任一节点上存在
`kind == MIRROR && sourceSceneId == X` 的条目（不论是否在排空），X 就是镜像源，语义同基线 `channelHasMirrors` 的 SCARD > 0（`world_autoscale.go:285-293`）。
调用点不变：缩容跳过（`WorldAutoscaler.java:139`）、再平衡跳过（`WorldRebalancePlanner.java:81`）、查询异常按「是」（`WorldAutoscaler.java:179-187`）。
孤儿（P1）与死节点（P2）**不**受它约束，镜像由节点本地级联处理（§6.11）。目录有 ≤ 5 s 的滞后（新镜像建好后会立即补发，实际滞后是一次发布的往返），
窗口里源被选为缩容牺牲者时由节点级联兜底，同基线「排空窗口里新建的镜像」（`world_autoscale.go:437-445`）。

**`ChannelSelector`（R1）**：候选再加一条 `kind ∈ {UNSPECIFIED, WORLD}`（`:87`）。login 进游戏、5.2 只带地图的选目标、5.1 的预占都依赖它。

**`SceneAssigner`**：原实例分支（`:78-88`）保持种类无关——断线 30 s 内重连回到还在、且不在排空的镜像 / 副本（D14）；原实例在回收宽限 / 级联中 → 按原地图
（`:89-98`：镜像的地图是世界地图，落同图频道；副本不是世界地图，回落默认主世界）。

**`SwitchTargetSelector`（5.2）**：`selectExplicit` 不改（命中实例照常返回并写 5.1 的软预占）；`selectByMap` 因 R1 自动排除镜像；改 `:25-27` 的注释
（「镜像的配置号就是世界地图，靠 `ChannelSelector` 的种类过滤挡住」）。

**指标**：见 §8.2。

### 6.7 scene：63 镜像分支（逻辑线程，替换 `ClientRequestHandler.java:217-218`）

**准入顺序**（与 `player_scene_handler.cpp:45-116` 对齐；括号里是接入的批次）：

1. 战斗在途 → 3023（6.3）；
2. `switchState ≠ NONE` → 3014（5.2）；
3. 三个号全 0 → 3005（现有 `:215-216`）；
4. `mirror_config_id ≠ 0 && scene_id == 0` → **镜像分支**（Java 里 uint32 只能判 `≠ 0`，不能用 `> 0`）；
5. `scene_id ==` 当前场景 → 3008；
6. `resolveSwitchTarget`（§6.9）。

3004 不适用（D15）。请求里的 `scene_config_id`、`dungeon_config_id`、`creators` 在镜像分支里都忽略（同基线）。

**镜像分支同步校验**（任一不满足回应答 `{3005}`，计 `xm.scene.mirror.requests{result}`）：

| 检查 | 基线 | Java | result 标签 |
|---|---|---|---|
| 当前场景不是 WORLD（镜像的镜像；副本作源） | 镜像的镜像 3005；副本作源**允许** | 3005（Q7） | `bad_source` |
| `mirror_config_id` 不在 Mirror 表 | 照收 | 3005（D7，Q1） | `bad_mirror_config` |
| 当前频道在排空（5.1 计划排空） | 照建，随后被级联 | 3005（D8） | `source_draining` |
| 本节点已停止接客（`acceptingEnters = false`：节点号租约丢失 / 停服） | — | 3005（D8） | `not_accepting` |
| 本节点实例数 ≥ `max-per-node` | 无此上限 | 3005（D19） | `node_cap` |
| 本节点上 `creators` 含本人的实例数 ≥ `max-per-creator` | 无此上限 | 3005（D19） | `creator_cap` |

**受理**：回 `{0}`；`switchState = RESOLVING{token, MirrorCreate(sourceSceneId, M), deadline = now + switch-resolve-timeout}`（复用 5.2 的状态与 4 s 本地兜底，
5.2 规格 §5.5、§6.2）；经 5.2 的 scene → scene-manager 客户端发 `createInstance{zone, 本节点, pid, MIRROR, source, 源 conf, M, 0}`，完成后投递回逻辑线程。计 `accepted`。

**结果回到逻辑线程**：先校验 `playersById.get(id) == player && switchState == RESOLVING(token)`，不符就丢弃（scene-manager 发出的号作废，**任何地方都不留幽灵**），计
`xm.scene.mirror.resolves{stale}`。然后：

| 结果 | 动作 | 客户端 | resolves 标签 |
|---|---|---|---|
| 调用失败 / 超时（含租约无效） | 回 NONE | 23 `{1003}`（同 `sm_reply.cpp:186-200`） | `error` |
| `tip ≠ 0` | 回 NONE | 23 `{3023}`（D5） | `rejected` |
| `scene_node_id ≠ 本节点` | 5.3 不会出现：回 NONE、ERROR 日志 | 23 `{3023}`（Q2 的钩子） | `wrong_node` |
| 玩家已不在源场景（被 5.1 排空改派走）、源已不在 / 在排空、节点停止接客、实例数达上限 | 回 NONE，不建 | 23 `{3023}`（D6） | `source_moved` |
| scene_id 本地已存在 | 回 NONE，不建，ERROR（发号器失效的迹象） | 23 `{3023}` | `create_rejected` |
| 成功 | `createInstance(spec)` → 回 NONE → `switchScene(player, mirror)` → 补发目录 | 源场景旁人 51；本人 79（§5.2）/ 21；有旁人时 47 | `created` |

- `switchScene` 内部已经写位置记录 `(node, 镜像 id, 源 conf)`（`SceneWorld.java:756`；`RedisPlayerLocations.java:80`），并触发组队跟随扇出（`:759`），同节点队友跟进镜像，
  与基线一致。
- `RESOLVING` 期间不冻结（同 5.2）：离场、断链、接管、失去归属照现有逻辑处理，迟到的结果被丢弃；组队跟随在 `switchState ≠ NONE` 时跳过；5.1 的排空改派**不跳过**
  `RESOLVING`（5.2 规格 §5.5），所以「玩家已被改派」要在结果回来时复查（上表第四行）。
- 应答在前、79 在后，与基线相同，客户端不能把 `{0}` 当作已到达（`player_scene_handler.cpp:90-92`）。

### 6.8 scene：建立实例 `SceneWorld.createInstance(spec)`（逻辑线程）

- 拒绝（ERROR 日志，计 `xm.scene.instance.lifecycle{event = rejected}`，不建）：scene_id 或 conf 为 0；种类不是 MIRROR / DUNGEON；本地已有同号（不论内容，号是全服唯一的，
  同号就是发号器坏了）；MIRROR：源不在本地、源不是 WORLD、源在排空；DUNGEON：conf 与 `Dungeon[id].scene_id` 不一致；达到 `max-per-node`；停止接客。
- 建好后：`emptySinceNanos = now`（创建者在同一任务里换入后立刻清零），`publishPopulation`，实例 gauge 加一，`requestDirectoryPublish()`，计 `created`。
- `SceneWorld` 需要一个「目录有变」的出口：构造参数加 `Runnable directoryChanged`（缺省空操作），由 `SceneNode` 接到 `requestDirectoryPublish`（`SceneNode.java:401-406`，HEAD）。
  `SceneDirectoryPublisher.requestPublishNow` 只把发布投到调度线程（`SceneDirectoryPublisher.java:78-90`），在逻辑线程上调用不会死锁。

### 6.9 scene：5.1 逻辑的改动

| 方法 | 改动 |
|---|---|
| `applyChannelPlan`（`:325-332`） | 「本地有、计划里没有 → 排空」只作用于 WORLD（R2） |
| `rejection`（`:343-368`） | 不变；另加「本地同号是实例」一条（理论上不会出现） |
| `leastLoadedActive`（`:445-457`） | 只看 WORLD（R3） |
| `relocationTarget`（`:431-442`） | 不变（依赖上一条）：镜像居民改派到同图 WORLD 频道，副本居民改派到默认主世界 |
| `resolveSwitchTarget`（`:504-519`） | 显式号：本地任意种类、未排空 → Local；排空中 → 3023；不在本地 → Remote（5.2）。只带地图且**当前场景是实例**：只在该图的 WORLD 频道里选人数最少的，**不允许留在实例里**；本地没有 → Remote（5.2，hash 覆盖下）或 3023。当前是 WORLD 时保持 D17 |
| `onPlayerLoaded` 排空重定向（`:591-601`） | 目标是 `drainCause = IDLE` 的实例 → 复活后照常进入（§6.10）；CASCADE / ADMIN / PLAN → 现有重定向 |
| `sceneEntries`（`:238-249`） | 带上 `kind`、`source_scene_id` |
| `publishChannels`（`:227-235`） | 只计 WORLD；实例另计 gauge |
| `destroyScene`（`:468-474`） | 销毁 WORLD 时触发级联（§6.11）；销毁实例时计 `destroyed_*` |
| `drainStep`（`:381-397`） | 扩为 `maintainScenes()`：回收判定 → 级联兜底 → 排空推进 → 销毁（§6.10） |

`publishPopulation`（`:216-224`）不改：镜像居民按源地图计入 `xm_scene_players{scene_config}`（同图各场景合计，口径不变）；副本居民计到 17–19 这几个标签值（表内有界）。

### 6.10 空闲回收（sm-instance-lifecycle 的 Java 版）

挂在现有的每秒逻辑任务上（`SceneNode.java:319-325` 的排空推进，HEAD），对每个非 WORLD 场景：

1. **计时**：`playerCount == 0 && !hasPendingEnter(id)` 时，`emptySinceNanos == 0` 就置为 now；否则清 0（玩家离开时也立即置位，精度 1 s 以内）。
2. **进入回收宽限**：`!draining && emptySince ≠ 0 && timeout(kind) > 0 && now − emptySince ≥ timeout(kind)` → `draining = true`、`drainCause = IDLE`、`drainingSince = now`，
   立即补发目录（之后 scene-manager 不再把新玩家导向它：login 按地图选、5.2 显式号回 DRAINING），计 `reclaim_started`。
3. **复活**：宽限中有在途进场到达（`onPlayerLoaded` 的目标就是它；登录重连、5.2 显式加入）→ `draining = false`、`drainCause = NONE`、补发目录，照常进入，计 `revived`。
   宽限中的**本地**新进入一律不放行：63 显式进入回 3023（排空中，现有规则），组队跟随跳过（`TeamFollowService.java:179-186`）。
4. **销毁**：`drainCause == IDLE && playerCount == 0 && !hasPendingEnter && now − drainingSince ≥ reclaim-grace` → `destroyScene`，计 `destroyed_idle`。
5. **超时取值**：`timeout(MIRROR) = mirror-idle-timeout > 0 ? mirror-idle-timeout : idle-timeout`（同 `instance_lifecycle.go:58-63`）；`timeout(DUNGEON) = idle-timeout`；
   结果为 0 = 该类不自动回收（同 `:132-135`）。
6. **为什么不需要基线的 Lua CAS**：人数、在途进场（`pendingEnters`，`SceneWorld.java:136`）、销毁都在同一个逻辑线程（`architecture.md` §5）。跨节点的在途进场在到达之前
   本节点看不见，由宽限兜住：从 scene-manager 选中目标到 `PlayerEnter` 落到目标节点，5.2 最坏约 23 s（交出 + 探测 ≤ M = 15 s，建链 + 握手 3 + 5 s，5.2 规格
   `:982-996`），登录路径更短（软预占 TTL 10 s 不短于 login 的归属等待，`xm-scene-manager/src/main/resources/application.yaml:56`）。缺省宽限 30 s 覆盖两者；
   `PlayerEnter` 一到，`pendingEnters` 接手保护（`hasPendingEnter` 挡住销毁）。超过宽限才到的进场按现有路径 `failEnter`（登录可以重试；5.2 交出进场是 F10「23 `{3023}` 后断开」），
   属 fail-safe（R8）。
7. 与基线的差别：基线按 30 s 扫描、起点是「上次扫描看到有人」（§4.2）；Java 按精确的变空时刻、1 s 粒度，再加宽限（D10）。

### 6.11 级联与显式销毁

- **级联**（对应基线 `instance_lifecycle.go:276-297`、`world_autoscale.go:437-457`）：WORLD 频道在 `destroyScene` 里被销毁时，本节点 `sourceSceneId == 它` 且未排空的镜像
  转 `draining = true`、`drainCause = CASCADE`、补发目录，计 `cascade_started`；同一次 `maintainScenes` 里居民经 `relocateResidents` 改派到同图 WORLD 频道（5.1 D8 同图优先；
  基线落默认大世界，`scene_node_service.cpp:91-115`），空了即销毁，计 `destroyed_cascade`。
  - 触发面：5.1 的缩容排空收尾、孤儿图收尾（基线孤儿清理不级联，B8，Java 统一级联）、5.5 的整节点疏散。5.1 缩容排空超时回滚（D10）的源频道没有被销毁，不级联。
  - 不级联「源开始排空」：同节点改派在一次推进里就把源清空并销毁，效果相同；源开始排空那一刻起，新的镜像请求已被 §6.7 拒掉。
  - 兜底：`maintainScenes` 每秒检查一次「镜像的源不在本地或不是 WORLD」，命中也转 CASCADE（防漏）。
  - 战斗冻结中的玩家（6.3）跳过、下一秒再试，同 5.1 的改派约定（`SceneWorld.java:377`）。
- **显式销毁**（基线 SM `DestroyScene` RPC 无生产调用方；Java 只经 dev 管理口，§6.13）：`destroyInstance(sceneId)` —— 不在本地 → 3000；是 WORLD → 3005（基线会删世界频道，B10）；
  已在排空 → 0（幂等）；否则 `drainCause = ADMIN`，居民按 `relocationTarget` 改派（镜像 → 同图 WORLD，副本 → 默认主世界出生点），空了销毁，计 `destroyed_admin`。
- **停服**：与 WORLD 一样写回并释放（`SceneWorld.java:1183-1205`），实例随进程消失；写回的 conf 是源地图 / 副本地图，重连按 §6.14。

### 6.12 跨节点进入与 5.2 交出

| 情形 | Java 5.3 | 用到的 5.2 原语 |
|---|---|---|
| 63 `{scene_id = 本节点上的实例}` | `Local` → 同步 `switchScene` | 无 |
| 63 `{scene_id = 别的节点上的实例, mirror_config_id 任意}` | 5.2 `Remote`：`selectSwitchTarget`（显式号，命中实例 + 写软预占）→ 冻结 → 交出 → `PlayerTransfer` → 目标节点进场 | 5.2 全套；交出原因按普通换图记（`PLAYER_SWITCH`） |
| 目标实例在交出途中进入回收宽限 | 宽限内到达 → 复活进入；更晚 → 目标节点 `failEnter` → F10「23 `{3023}` 后断开」，重连按位置记录落回源地图 | 5.2 F10（规格 `:953`） |
| 组队跟随进镜像 | 同节点跟进（与基线一致）；队长所在实例在宽限 / 排空中不跟 | 无（跨节点跟随两版都不做） |
| scene-manager 把新实例放到别的节点（基线的回落） | **5.3 不做**（D2）。钩子：scene-manager 应答前同步调目标节点的 `ensureInstance(spec)`（4.5 的隔离 Dubbo 模块，目录 `rpc_host / rpc_port` 直连，按 scene_id 幂等；建成却没人进入由本地回收自愈，不需要回滚表）；发起节点拿到 `{node ≠ 自己}` 后 `SceneTransfers.begin(player, Target(node, id), Reason.MIRROR / DUNGEON, playerRequested = true)`；失败口径同 5.2 F1–F11 | 5.2 全套（Q2） |

5.2 的 `SelectSwitchTargetResponse` 可以选择性加 `ChannelKind scene_kind = 5`，只用于给交出指标打 MIRROR / DUNGEON 标签（YAGNI，本批不加）。

### 6.13 副本（DUNGEON）与 dev 管理口

- 交付：种类与字段；§5.2 的 79 内容；300 s 回收；目录登记；`createInstance` 接受 DUNGEON；级联不适用（副本不能当镜像源，Q7）。
- **不实现**：时间限制、刷怪、队伍人数校验（基线 scene 侧同样没有）。
- **dev / test 管理口**（Q3，照 4.7 播种先例 `architecture.md:517-536`）：scene 管理端口（缺省 18104）上
  - `POST /admin/scene/instance/create`（`CreateDungeonInstanceRequest`）：HTTP 线程校验 Dungeon 行存在 → scene-manager `createInstance(kind = DUNGEON, requester = 本节点,
    player = 0, conf = Dungeon.scene_id, dungeon_config_id)` → `callOnLogic(createInstance(spec))` → 回 `{0, scene_id, conf, node}`；表里没有回 3005，取号失败回 1003，
    本地拒建回 3023。
  - `POST /admin/scene/instance/destroy`（`DestroyInstanceRequest`）：`callOnLogic(destroyInstance)`（§6.11）。
  - 请求 / 应答是 protobuf 二进制；鉴权同 xm-trade 播种：头 `X-Xm-Admin-Token` 与环境变量 `XM_ADMIN_TOKEN` 常数时间比对（不设则 503）、`X-Xm-Operator` 必填、每次写审计行；
    运行模式不是 dev / test 一律 403。没有任何客户端消息号新增。

### 6.14 重连、位置记录与存盘

- 位置记录：`switchScene` / 进场写 `(zone, node, scene_id, scene_config_id, epoch)`，实例里写实例号与实例的 conf（镜像 = 源地图）。
- **重连回到还在的原实例（D14）**：login 带 `preferred_scene_node_id / preferred_scene_id` 请 scene-manager 分配（`PARITY.md:91`），`SceneAssigner` 的原实例分支种类无关
  （`:78-88`）。实例空置满超时进入宽限（目录报 draining）后，重连改按原地图选——这与 30 s 断线租约、30 s 镜像超时天然对齐：断线 30 s 内重连回镜像，之后回源地图频道。
  宽限保证「分配时还在 → 到达时还在」（§6.10 第 6 条）。
- 节点死亡 / 重启：实例消失，记录里的实例号在目录里找不到 → 按原地图（镜像）或默认主世界（副本，conf 不是世界地图）。频道计划不会重建实例（D13）。
- 存盘：实例里 `PlayerSave.sceneConfigId` = 实例的 conf（`ScenePlayer.java:307-308`），坐标是当时位置；重登规则见 §5.6。**不新增任何持久化字段**，`owner_epoch` 围栏不受影响
  （同节点换入实例不涉及归属）。

### 6.15 线程所有权

| 线程 | 做什么 |
|---|---|
| scene 逻辑线程 | `Scene` 全部状态；63 校验与 `RESOLVING`；`createInstance`；`switchScene`；回收、级联、排空；结果的 token 校验 |
| Dubbo 客户端回调线程（5.2 的 scene → scene-manager 客户端） | 只把结果 `logicLoop.execute(...)` 投递回逻辑线程；本地 4 s 兜底超时在逻辑线程上判 |
| `scene-sched` | 不新增任务；目录发布照旧经 `callOnLogic` 取快照，实例变化时 `requestPublishNow` |
| scene 管理端口 HTTP 线程（仅 dev 管理口） | 只做鉴权与编解码，业务经 scene-manager future 与 `callOnLogic` |
| scene-manager Dubbo 业务线程 | `createInstance` 无状态；共享发号器（synchronized） |
| scene-manager 控制面 tick 线程 | `DirectoryMirrorSources.of(view)` 纯函数，与规划器同线程 |

scene 侧不新增 Redis 或 MySQL I/O；Netty I/O 线程与逻辑线程上没有阻塞调用（`AGENTS.md` §3）。

### 6.16 失败处理与竞态

| 情形 | 行为 |
|---|---|
| scene-manager 不可达 / 超时 / 租约无效 | 23 `{1003}`；没有建任何东西 |
| scene-manager 业务拒绝 | 23 `{3023}` |
| 应答迟到（token 已换 / 玩家已离开） | 丢弃；号作废（基线这里会留一个空镜像等 0–60 s 回收，`sm_reply.cpp:112-146`） |
| 建镜像途中源频道被计划转排空、玩家被改派 | 结果回来时源在排空或玩家不在源 → 不建、23 `{3023}` |
| 同一玩家连发两次 63 镜像 | 第二次 3014（D4） |
| 玩家在镜像里断线 | 镜像变空、开始计时；30 s 内重连回镜像；之后按地图回到源地图频道 |
| 回收宽限中到达的在途进场 | 复活进入 |
| 宽限之后才到达的进场 | 登录：`failEnter` → 客户端重试；5.2 交出：F10（R8） |
| 节点停服 / 崩溃 | 实例随进程消失；写回的 conf 是源地图 / 副本地图；重连按地图选 |
| 目录发布延迟 | 新镜像立即补发；别的节点在窗口里按号加入回 3023（可重试）；规划器在窗口里把源选为缩容牺牲者 → 节点级联（同基线窗口） |
| 级联改派找不到目标（本节点同图与默认大世界都没有 WORLD 频道） | 原地不动、计 `blocked`、每秒重试（5.1 现有逻辑；per-node 覆盖下不会出现） |
| 滚动升级：新 scene + 旧 scene-manager | `createInstance` 不存在 → 调用失败 → 23 `{1003}`（fail-closed）；**旧 scene-manager 的 `ChannelSelector` 没有种类过滤，会把登录分进新节点上的镜像** → 必须先升级 scene-manager（R7） |
| 滚动升级：旧 scene + 新 scene-manager | 旧节点不建实例，目录 `kind` 为 UNSPECIFIED → 按 WORLD 读，行为不变 |

### 6.17 幂等

- 建实例**不幂等**（同基线，每次请求一个新镜像）；同一玩家同时只有一个 `RESOLVING`，Dubbo `retries = 0`。
- `createInstance(spec)` 按 scene_id 拒绝重号（号全服唯一，重号即故障）；回收、级联、显式销毁都只改本地状态，重复调用无副作用（已排空的再排空是空操作）。
- 管理口销毁对同一号重复调用回 0 或 3000。

### 6.18 需要同步修改的文档与登记

- `architecture.md`：§4.19 末尾或新增 §4.21「副本与镜像实例（批次 5.3）」：节点自有、目录登记、scene-manager 只发号、回收宽限、级联；§9 的 scene_id 一条加上
  「实例号同样由 scene-manager 全服租约发，发号租约每个副本都申领」；§10 去掉「副本」相关的首批不做；§11 指标表加 §8.2 各项，并改写 `:774` 的
  「场景只在启动时按配置表建出」（实例按需建，但 `scene_config` 标签值仍受 BaseScene 表约束）。
- 代码注释：`ClientRequestHandler.java:203-208`；`world_channel.proto:24`；`node_directory.proto:39-50`；`PlanResult.java:21`（级联在节点本地）；`MirrorSources.java:3-17`
  （不再恒「否」）；`Scene.java:11-21` 类注释；`SwitchTargetSelector.java:25-27`；`SceneWorld.java:95-99` 类注释。
- PARITY：新增一行「副本 / 镜像场景与实例空闲回收」，写明 D1–D19 与 mmorpg 待做 B 项；更新「场景实例与主世界频道」行（`:107`，`MirrorSources` 接上、实例不进计划）；
  更新 `:29`（「跨节点换图、副本待做」）；更新 `:91`（重连回到还在的原实例，含镜像 / 副本）；更新 `:27`（选频道只认主世界频道）。
- 盘点：`scene-core.md:235`、`:237`、`:239`、`:249`、`:251`；`scene-manager-match.md:133`、`:135-137`、`:149` 的 java 列与勘误（§9.3）。
- 路线图 `docs/porting/roadmap.md:73` 的状态列。

---

## 7 配置与常量

### 7.1 基线（`config.go`；开发配置 `sm.yaml`）

| 键 | 缺省 | 开发 | 出处 |
|---|---|---|---|
| `InstanceIdleTimeoutSeconds` | 300 | 300 | `config.go:130-132`；`sm.yaml:96-98` |
| `MirrorIdleTimeoutSeconds` | 30（≤ 0 回落上一项） | 30 | `config.go:134-141`；`sm.yaml:100-104` |
| `InstanceCheckIntervalSeconds` | 30 | 30 | `config.go:143-145`；`sm.yaml:123-124` |
| `MirrorSourceNodeLoadCap` | 0（关） | 0 | `config.go:154-159`；`sm.yaml:106-112` |
| `MirrorDedupBySource` | false | false | `config.go:161-169`；`sm.yaml:114-121` |
| `StrictNodeTypeSeparation` | true | true | `config.go:109-118`；`sm.yaml:149-159` |
| `NodeLoadWeightSceneCount` / `PlayerCount` | 1.0 / 0.01 | — | `config.go:120-128` |
| SM `Timeout` | 8000 ms | 8000 | `sm.yaml:14` |
| C++ → SM deadline | 10 000 ms | — | `deadline.md:125` |
| 换图在途槽 TTL | deadline + 1000 ms | — | `player_lifecycle.cpp:186-192` |
| MessageLimiter 缺省 | 每秒 3 条 | — | `message_limiter.h:15-16` |

### 7.2 Java

| 配置 | 缺省 | 进程 | 校验（不满足拒启） | 基线对应 |
|---|---|---|---|---|
| `xm.scene.instance.mirror-idle-timeout` | 30 s | xm-scene | ≥ 0；0 = 用 `idle-timeout` | `MirrorIdleTimeoutSeconds` |
| `xm.scene.instance.idle-timeout` | 300 s | xm-scene | ≥ 0；0 = 副本（及回落到它的镜像）不自动回收 | `InstanceIdleTimeoutSeconds` |
| `xm.scene.instance.reclaim-grace` | 30 s | xm-scene | ≥ 10 s（不短于 5.1 软预占 TTL / login 归属等待的口径） | 无（Java） |
| `xm.scene.instance.max-per-node` | 200 | xm-scene | 1..10000 | 无（基线只有 Agones 房间数） |
| `xm.scene.instance.max-per-creator` | 3 | xm-scene | 1..100 | 无 |
| `xm.scene.switch-resolve-timeout` | 4 s | xm-scene | 复用 5.2（> Dubbo 3 s） | 在途槽 TTL 11 s |
| `xm.scene.scene-manager-url` | `tri://127.0.0.1:20882` | xm-scene | 复用 5.2 | — |
| `XM_ADMIN_TOKEN`（环境变量） | 无（不设则管理口 503） | xm-scene | — | — |
| `xm.run-mode` | prod | xm-scene | 管理口只在 dev / test 放行 | — |
| Dubbo 提供方超时 | 3000 ms | xm-scene-manager | 复用（`application.yaml:94`） | SM `Timeout` 8000 |
| 发号租约 | `NodeTypes.SCENE_MANAGER`，作用域 0，worker 1..1023，TTL 15 s | xm-scene-manager | 复用（`WorldChannels.java:20-24`）；改为每个副本申领 | `SceneIDGen` |
| 回收检查周期 | 1 s（复用排空推进任务，不可配） | xm-scene | — | `InstanceCheckIntervalSeconds` 30 s |

**不移植**：`InstanceCheckIntervalSeconds`、`MirrorSourceNodeLoadCap`、`MirrorDedupBySource`、`StrictNodeTypeSeparation`、负载权重、Agones（§10.2）。

**常量**：`ChannelKind.CHANNEL_KIND_MIRROR = 2`、`CHANNEL_KIND_DUNGEON = 3`；`SceneEntry.kind = 5`、`source_scene_id = 6`；scene 侧 `DrainCause{NONE, PLAN, IDLE, CASCADE, ADMIN}`。

### 7.3 本机切片

`tools/local/start-slice.sh` 为 robot 提速可用环境变量把 `mirror-idle-timeout` 调到 5 s、`reclaim-grace` 调到 10 s（宽限下限），并设 `XM_RUN_MODE=dev`、`XM_ADMIN_TOKEN`。
双节点（5.2 的 `XM_SCENE_NODES=2`）用于跨节点加入实例。

---

## 8 指标

所有指标都不带 zone / scene_id / 节点号 / player_id 标签（`AGENTS.md` §5；`architecture.md:771-775`）。

### 8.1 基线（`metrics.go`，subsystem `scene_manager`，**带 `zone_id` 标签**）

| 指标 | 标签 | 出处 |
|---|---|---|
| `mirror_colocate_total` | `zone_id, outcome = hit/fallback, reason` | `:86-90` |
| `instance_destroyed_total` | `zone_id, kind = instance/mirror, reason = idle/explicit/cascade/node_death/source_migrated`（实际还有 `source_scaled_in`） | `:97-101` |
| `enter_scene_rejected_total` | `zone_id, reason`（含 `scene_gone`） | `:122-127` |
| `scene_orphans_reconciled_total` | `zone_id` | `:147-151` |
| `mirror_source_missing_total` | `zone_id` | `:158-162` |
| `mirror_dedup_total` | `zone_id, outcome = hit/miss/stale` | `:170-174` |

没有「建了多少实例」的计数器，也没有「当前活跃实例数」的 gauge。

### 8.2 Java（Micrometer）

| 进程 | 指标（Prometheus 名） | 类型 | 标签 | 基线对应 |
|---|---|---|---|---|
| scene | `xm_scene_instances` | Gauge（逻辑线程在变化后推绝对值） | `kind` = mirror / dungeon；`state` = active / reclaiming / draining | 无 |
| scene | `xm_scene_instance_lifecycle_total` | Counter | `kind`；`event` = created / rejected / reclaim_started / revived / cascade_started / destroyed_idle / destroyed_cascade / destroyed_admin | `instance_destroyed_total`（Java 没有 explicit RPC / node_death / source_migrated / source_scaled_in） |
| scene | `xm_scene_mirror_requests_total` | Counter | `result` = accepted / bad_source / bad_mirror_config / source_draining / not_accepting / node_cap / creator_cap | 无（同步结局） |
| scene | `xm_scene_mirror_resolves_total` | Counter | `result` = created / rejected / error / stale / wrong_node / source_moved / create_rejected | 无（异步结局） |
| scene | `xm_scene_channels` | Gauge（5.1） | 改为只计 WORLD | — |
| scene | `xm_scene_players` | Gauge（现有） | 镜像居民计入源地图；副本居民计入 17–19 | — |
| scene-manager | `xm_scene_manager_instance_seconds` | Timer | `kind` = mirror / dungeon；`result` = ok / bad_request / no_lease / error | `mirror_colocate_total`（Java 恒共置，不另设） |
| scene-manager | `xm_scene_manager_switch_seconds` | Timer（5.2） | 不变；显式号命中已回收的实例计 `not_found` | `enter_scene_rejected_total{scene_gone}` |

勾稽：`mirror_requests{accepted}` = Σ `mirror_resolves{*}` + 在途数；`mirror_resolves{created}` = `instance_lifecycle{kind = mirror, event = created}`；
每个 `created` 最终恰好对应一个 `destroyed_*`（或停服）。

---

## 9 隐患与边界

### 9.1 基线自身的隐患（建议登记进 PARITY「mmorpg 待做（可选）」）

| # | 隐患 | 出处 |
|---|---|---|
| B1 | SM 业务失败的应答不回显 `creator_ids`，C++ 只打日志；客户端在 `{0}` 之后永远等不到结果 | `createscenelogic.go:195-198`、`:237`、`:246`、`:305-308`；`sm_reply.cpp:84-90`、`:184-185`；`deadline.md:200` |
| B2 | 镜像创建期间不登记在途：连发会建出多个镜像；应答晚到时若玩家已换图完成（不在途），会被**拽进**这个镜像（推导） | `player_scene.cpp:196`（发后不管）对照 `sm_reply.cpp:136-146`（只判在途）、`:159-171` |
| B3 | 应答到达时创建者换图在途就静默跳过，客户端卡住 | `sm_reply.cpp:136-146` |
| B4 | Mirror 表运行期不读：未知 `mirror_config_id` 照收；`scene_id` / `main_scene_id` 两列无效 | §1.4 |
| B5 | `creators` 写而不读，3022 是死码；`CheckEnterSceneWithCreators` 引用了已不存在的 `SceneCommon`，所在工程 `game_server_load_balance.vcxproj` 不在 `game.sln` 里 | `scene_test.cpp:925-944`；`cpp/tests/scene_test/game_server_load_balance.vcxproj:22` |
| B6 | 强制销毁（级联 / 缩容 / 迁移）有人的镜像：SM 已原子删光键、只发一次 `DestroyScene`；节点只排空、保留实体 → 空实体泄漏到进程重启（推导） | `instance_lifecycle.go:239-257`、`:299-305`；`scene_node_service.cpp:91-115` |
| B7 | 被改派玩家落点后扣旧实例计数，把已删的键重建成无 TTL 的孤儿 `instance:{id}:player_count`（推导） | `enterscenelogic.go:726-730`；`instance_lifecycle.go:365-370` |
| B8 | 孤儿频道清理 DEL `scene:{id}:mirrors` 但不级联销毁镜像，与另外几条销毁路径不一致 | `orphan_cleanup.go:190` |
| B9 | 去重不比较 `mirror_config_id`，且任取一个 | `createscenelogic.go:107-118`、`:202-213` |
| B10 | 显式 `DestroyScene` RPC 非原子、不还 Agones 名额、能删世界频道（之后懒改派会在别的节点把它重建出来，推导） | `destroyscenelogic.go:40-120` |
| B11 | 回收窗口取决于扫描相位：最后一人离开后约 0–30 s 即可能回收，配置注释「吸收短暂断线」得不到保证 | §4.2；`sm.yaml:100-104` |
| B12 | 镜像创建不限频（63 不在 MessageLimiter 表），一个玩家可持续堆出数十个空镜像（推导） | §5.9 |
| B13 | 镜像分支没有 SM 回 3005，普通分支回 1003，口径不一 | `player_scene.cpp:179-184` 对照 `player_scene_handler.cpp:140-149` |
| B14 | 共置失败回落到副本类型节点后，那里的 63 一律 3004，玩家没法用 63 离开镜像；级联改派落默认大世界、且新实体 `mapChanged = false` 可能在别的图上沿用旧坐标（推导） | `createscenelogic.go:471`；`player_scene_handler.cpp:45-52`；`scene_node_service.cpp:94-104` |
| B15 | 镜像链没有 C++ 单测，基线 robot 也不覆盖 | `cpp/tests` grep 为空；`scene-core.md:241`；`scene-manager-match.md:139` |
| B16 | gRPC `CreateScene` 入口不检查零号，按 id 幂等时不比较字段（5.1 已登记为其 B12） | `scene_node_service.cpp:23-37` 对照 `scene_handler.cpp:854-859` |

### 9.2 Java 移植时会踩的

| # | 风险 | 对策 |
|---|---|---|
| R1 | 镜像与源同配置号：`ChannelSelector` 会把登录、5.2 只带地图的换图、5.1 预占分进镜像 | 候选加 `kind ∈ {UNSPECIFIED, WORLD}`（§6.6） |
| R2 | `applyChannelPlan` 把计划外的本地场景转排空，实例一建出来就被当孤儿排空 | 只对 WORLD（§6.9） |
| R3 | `leastLoadedActive` / `relocationTarget` / `resolveSwitchTarget` 会选中别人的镜像；身在镜像里发「只带当前地图」的 63 会因并列留在镜像（D17） | 只把 WORLD 当频道；实例里只带地图必回 WORLD（§6.9） |
| R4 | `xm.scene.channels` 把实例数成频道 | 只计 WORLD，实例另起 gauge |
| R5 | 发号租约只在可竞选的副本上申领，`leader-eligible = false` 的副本发不出实例号 | 租约挪成独立 bean，每个副本都申领（§6.6） |
| R6 | 目录 `scenes` 随实例数增长，login 每次分配都整读目录（`SceneAssigner.java:73`；`NodeDirectory.list` 是 `readAllValues`） | 每节点 / 每创建者上限（D19）；条目只加两个字段（每条约 30 B，200 条约 6 KB / 节点）；超出时把实例拆进单独的目录键（Q10） |
| R7 | 滚动升级：旧 scene-manager 没有种类过滤 | 先升级 scene-manager，再升级 scene；PARITY 与发布说明写明顺序 |
| R8 | 跨节点在途进场在宽限之后才到（5.2 慢路径 > 30 s） | 宽限缺省 30 s ≥ 5.2 最坏约 23 s；落空走 F10，数据安全由 `owner_epoch` 保证 |
| R9 | `mirror_config_id ≥ 2^31` 在 Java int 里为负 | 判 `≠ 0`，查表按无符号；`RequestFieldCheck` 只查有符号字段（`RequestFieldCheck.java:11-12`），不会误丢 |
| R10 | 63 请求里客户端带了超过 20 个 creators：整条请求被 `RequestFieldCheck` 静默丢弃、不回包（基线 `CheckFieldSizes` 同样丢） | 两版一致，不改；creators 在请求里本就被忽略 |
| R11 | 将来打开跨节点放置时，`scene_node_id ≠ 自己` 不能被悄悄当成本地实例 | 5.3 显式 ERROR + 23 `{3023}`，接上 Q2 时改为 `begin(Reason.MIRROR)` |

### 9.3 勘误（盘点、设计稿与分区稿）

| # | 原文 | 更正 |
|---|---|---|
| E1 | `scene-manager-match.md:135`「源场景已不存在 → ErrSourceSceneGone(13)」 | 只在 `mirror_config_id == 0` 时成立（`createscenelogic.go:183-200`），C++ 从不这样发（`player_scene.cpp:124-129`），生产不可达 |
| E2 | `scene-manager-match.md:136`「`instances:zone:{z}:active` ZSET(score = 最后活跃秒)」 | 建立时是建立时刻，只有扫描看到有人时才刷新为扫描时刻（`createscenelogic.go:257-262`；`instance_lifecycle.go:113-118`） |
| E3 | `scene-core.md:249`「SM 记录活跃副本」 | SM 的 `CreateSceneRequest` 没有 dungeon 字段（`sm.proto:34-55`），只能记「实例」；生产代码建不出 `dungeon_config_id ≠ 0` 的场景 |
| E4 | `scene-core.md:235` / `scene-manager-match.md:133` 的 tables 列「Mirror（scene_id → BaseScene…）」 | 运行期不读 Mirror 表，镜像地图是源地图（`player_scene.cpp:189`） |
| E5 | `scene-core.md:237`「镜像『尽量』与源同节点，落到别处走 18 交接」 | 缺省配置下必然共置（§2.3）；回落只在源节点判死、跨 zone 或打开 `MirrorSourceNodeLoadCap` 时发生 |
| E6 | `sca.md:343-357` Destroy Flow：先级联、RPC 在原子删除之前 | 代码是先原子删除、确认后再级联、最后发 RPC（`instance_lifecycle.go:217-305`） |
| E7 | `sca.md:338`「C++ 按 config_id 去重」 | 按 scene_id（`scene_node_service.cpp:23-37`） |
| E8 | `metrics.go:100` Help 列的 reason | 漏了实际在用的 `source_scaled_in`（`world_autoscale.go:455`） |
| E9 | `config.go:131`「0 = never auto-destroy (default)」；`createscenelogic.go:267-269`、`sm.yaml:100-102`「镜像每次进入重置 NPC」 | 缺省是 300；没有重置 NPC 的代码，`OnSceneCreated` 只做 Agones 计数（`scene_event_handler.cpp:30-46`） |
| E10 | scene-manager 分区稿「基线组队跟随可以跨节点（`player_team.cpp:475-484`）」 | 跨节点**不跟**（`player_team.cpp:434-440`）；Java 4.3 同样只在本节点 |
| E11 | Java 映射分区稿「最后一人离开后约 1 s 就被删」 | 窗口取决于扫描相位，约 0–30 s（§4.2）；「约 1 s」只是其中一端 |
| E12 | 5.2 规格 §5.4 / §5.13「提供方超时 `application.yaml:68`」 | 现在是 `xm-scene-manager/src/main/resources/application.yaml:94`（值仍 3000） |
| E13 | `SwitchTargetSelector.java:25-27`「地图必须是世界地图（副本 / 镜像是私有实例…）」 | 镜像的配置号**就是**世界地图，这条挡不住镜像，要靠 `ChannelSelector` 的种类过滤（R1） |
| E14 | `scene-manager-match.md:149`「Java 场景随节点启动创建、随节点停服消失」 | 5.1 起按频道计划建（`SceneWorld.java:278-340`） |
| E15 | `scene-channels-spec.md:1078` D15「5.3 引入」用途分池 | 5.3 不引入（§10.1 D15） |

---

## 10 建议的有意差异

### 10.1 建议采纳（D）

| 编号 | 差异 | 理由 | 客户端可见？ | 两版同改？ |
|---|---|---|---|---|
| D1 | 实例由承载节点自有，节点目录是唯一登记；不建 `instances:zone:{z}:active`、`instance:{id}:player_count`、`scene:{id}:mirror / source / mirrors`、`node:…:scenes` | §6.2；在场人数的权威是节点；与 5.1 D3 一致 | 否 | 否 |
| D2 | 镜像恒与源同节点，没有回落（不移植 `MirrorSourceNodeLoadCap`、跨节点放置） | 发起节点就是源节点；基线回落只在缺省关闭的条件下发生（`createscenelogic.go:491-527`） | 缺省配置下否 | 否 |
| D3 | scene-manager `createInstance` 只发号与定放置，无状态；没有 SM → scene 的 CreateScene、没有回滚；迟到应答不留幽灵镜像 | 5.1 D3 / 5.2 D2 的延续 | 否 | 否 |
| D4 | 建镜像在途时再发 63 → 3014 | 复用 5.2 `RESOLVING`；基线会再建一个镜像（B2） | 是 | 可选（B2） |
| D5 | scene-manager 业务拒绝推 23 `{3023}` | 修 B1 | 是 | 可选（B1） |
| D6 | 应答到达时玩家已不在源场景 / 源在排空 / 节点停止接客 → 不建，23 `{3023}` | 修 B2 的「拽进」与 B3 的沉默 | 是 | 可选（B2、B3） |
| D7 | `mirror_config_id` 必须在 Mirror 表里，否则 3005；镜像地图仍取源地图；不校验 `main_scene_id` | 安全 > 严格对齐（PARITY 先例 `:45`）；地图不改，79 不变 | 只在非法 id 时 | 可选（B4） |
| D8 | 源不是主世界频道 / 源在排空 / 节点停止接客 → 3005 | fail-closed；基线源排空中仍会建、随后被级联 | 是（极窄） | 否 |
| D9 | 没有 scene-manager 可用：应答 `{0}` 后 23 `{1003}`（基线同步 3005） | Dubbo 引用恒在，只能异步发现；与 5.2 D12 一致；顺带消除 B13 的口径不一 | 是 | 否 |
| D10 | 空闲回收由宿主节点按精确变空时刻、1 s 粒度判定，再加回收宽限；宽限内到达的在途进场让实例复活 | 基线以扫描相位为准（B11）；宽限兜住跨节点在途进场 | 是（可加入窗口确定为 30 s，销毁晚约 30 s） | 否 |
| D11 | 级联在节点本地：源频道销毁后镜像转排空，居民改派到同图主世界频道（坐标保留），不存盘 | 5.1 D8 口径；修 B6、B14 | 是（落点） | 否 |
| D12 | 再平衡跳过镜像源（基线迁移源频道并强制级联有人的镜像） | 5.1 已实现（`WorldRebalancePlanner.java:81`），5.3 接上真实的 `MirrorSources` 后生效 | 是（只在 hash 覆盖下） | 否 |
| D13 | 节点死亡 / 重启时实例随进程消失；没有死节点收尾、不重建 | 基线由死节点收尾强制销毁（`load_reporter.go:413-472`）；结果相同 | 否 | 否 |
| D14 | 重连回到还在、且未进入回收的原实例（镜像 / 副本） | 延续 PARITY 有意差异 ⑥（`PARITY.md:91`） | 是（基线一律落默认大世界） | 否（mmorpg 可选：⑥） |
| D15 | 不按节点用途分池，不产生 3004；实例里可以照常发 63 离开 | Java 节点同构（5.1 D15）；避开 B14 的困局 | 理论上是（基线无生产副本） | 否 |
| D16 | 副本地图取 `Dungeon.scene_id`；只有 dev / test 管理口能建 / 毁；79 带 `dungeon_config_id` | 基线没有生产入口与地图来源 | 否（生产不可达） | 否 |
| D17 | 显式销毁只经 dev 管理口，「排空后销毁」，拒绝世界频道 | 修 B10；基线 RPC 无调用方 | 否 | 否 |
| D18 | 63 加入已有实例时带了不符的 `scene_config_id` 回 3005 | 沿用 5.1 / 5.2 现有行为（`ClientRequestHandler.java:225-226`；`SwitchTargetSelector` BAD_REQUEST）；基线不比对；补登记 | 是（既有差异） | 否 |
| D19 | 每节点（200）/ 每创建者（3）实例上限，超限 3005 | 约束目录体积（R6）与刷镜像（B12） | 只对异常客户端 | 可选（B12） |

**对齐（不是差异，列出提醒同批实现）**：镜像地图 = 源地图；镜像的 `dungeon_config_id = 0`、`creators = {创建者: true}`；`creators` 不做准入，任何人可按号加入；
镜像的镜像 3005；从实例里只带地图的 63 必回主世界频道（基线只在世界频道里选）；组队跟随同节点跟进镜像；43 → 31 返回实例 info。
**既有差异照旧**：47 当场发；老观察者能收到新进场者的 21（`SceneWorld.java:54-57`）。

### 10.2 列出但不建议本批采纳（N）

- **N1 `MirrorDedupBySource`**：缺省关，没有使用方；共置后可在节点本地实现（按 (源, `mirror_config_id`) 取号最小者，修 B9），等有「共享镜像」玩法再做。
- **N2 `MirrorSourceNodeLoadCap` 与跨节点放置**：见 Q2；Java 用每节点上限 + 拒绝代替。若将来需要，升级路径是 §6.12 的 `ensureInstance` 钩子，或 A′ 的 Redis 登记表（§6.2）。
- **N3 节点用途分池**（`StrictNodeTypeSeparation`、副本节点、3004）：5.1 D15 延续，等 6.x 或运营需要再评估。
- **N4 SM 显式 `DestroyScene` RPC 与源克隆镜像**（`mirror_config_id == 0`）：基线都没有生产调用方。
- **N5 Agones**：同 5.1 D14。
- **N6 校验 `Mirror.main_scene_id == 源 conf`**：比 D7 更严；现有数据下镜像 1 只能在图 1、镜像 2 只能在图 2 用（Q1）。
- **N7 `creators` 准入（3022）**：基线不做（B5），需两版同改。
- **N8 副本玩法**（`time_limit` 计时踢出、`max_team_size` 准入、刷怪）：基线 scene 侧没有，PVE 在 battle 节点。
- **N9 镜像改用 `Mirror.scene_id` 作地图**（BaseScene 20 / 21）：客户端所见会变（79 的配置号），需两版同改。
- **N10 scene 节点用自己的全服租约（`NodeTypes.SCENE_GUID`）给实例发号**：省一次往返，但与 `SCENE_MANAGER` 的 worker 号段独立申领，同毫秒同 worker 同序号会撞号
  （`Snowflake.java:7-9`「一种 ID 只在一种节点类型里产生」）；要做得先切开号段（Q8）。

### 10.3 mmorpg 待做（可选，登记进 PARITY）

B1（错误应答回显 `creator_ids`）、B2 / B3（镜像创建登记在途、应答时校验仍在源场景）、B4（Mirror 表存在性校验）、B5（删过时单测或接上 creators 准入）、B6 / B7（强制销毁有人的镜像后
节点实体泄漏与孤儿计数键）、B8（孤儿清理级联）、B10（显式销毁原子化、拒绝世界频道）、B11（按离开时刻计时）、B13（无 SM 的口径统一为 1003）。

---

## 11 开放问题（每条附推荐答案）

| # | 问题 | 选项 | 推荐 |
|---|---|---|---|
| Q1 | `mirror_config_id` 校验 | ① 只校验行存在；② 再加 `main_scene_id == 源 conf`（N6）；③ 与基线一样不校验 | **①**（D7）：挡住任意值，又不改变现有数据下的可用范围以外的语义；robot 用 `expect-mirror-validation` 选项同时支持 ③ |
| Q2 | 跨节点放置 | ① 5.3 只做共置；② 同批做 `ensureInstance` + `SceneTransfers.begin(Reason.MIRROR / DUNGEON)`；③ 改用 A′ Redis 登记表 | **①**：基线缺省配置下不发生；② / ③ 留作有「节点满载」需求时的升级路径 |
| Q3 | 副本入口 | ① 完全没有；② dev / test 管理口；③ GM 客户端指令（需新消息号，先改 mmorpg） | **②**：只为 robot 与切片验证，生产不可达，不动客户端契约 |
| Q4 | scene-manager 业务拒绝时给创建者提示 | ① 推 23 `{3023}`；② 与基线一样沉默 | **①**（D5）：基线的沉默是已知缺口（`deadline.md:200`） |
| Q5 | 回收方式 | ① 宽限 + 复活（缺省 30 s）；② 超时即销毁 | **①**：保证「分配时还在 → 到达时还在」，代价只是空实例多活约 30 s |
| Q6 | 实例上限 | ① 每节点 200、每创建者 3，超限 3005；② 只要每节点上限；③ 不设（同基线） | **①**：约束目录体积与刷镜像；码用 3005（与镜像分支其它同步失败一致），不用 3006 kEnterSceneSceneFull |
| Q7 | 副本能否当镜像源 | ① 只允许主世界频道；② 与基线一样允许非镜像的任意场景 | **①**：副本无生产入口，允许它当源要多处理「源是副本」的级联与回收；fail-closed |
| Q8 | 实例号由谁发 | ① scene-manager Dubbo（全服租约）；② scene 节点自发（N10） | **①**：保住「scene_id 只由一种发号器发」 |
| Q9 | 断线重连能否回到实例 | ① 回到还在、未进入回收的原实例；② 一律按地图选 | **①**（D14）：与 Java 现行「回原实例」口径一致；宽限兜住竞态。scene 分区稿曾主张 ②，理由是回收竞态，已由宽限消解 |
| Q10 | 实例条目放在目录哪里 | ① 放进 `SceneNodeInfo.scenes`（带 `kind`）+ 上限；② 另起一个只放实例的目录键（login 只在找原实例时单点读） | **①**：本批简单；若线上单节点实例常态超过约 100，再拆成 ② |
| Q11 | 镜像分支的新增同步拒绝用什么码 | ① 一律 3005；② 源排空 / 停止接客用 3023 | **①**：这个分支在客户端看来的码集合与基线相同 |
| Q12 | 发号租约无效时的口径 | ① 调用失败 → 23 `{1003}`；② 业务拒绝 → 23 `{3023}` | **①**：租约无效是基础设施状态（暂时不可用），与 Redis 故障同类 |
| Q13 | PARITY「mmorpg 待做（可选）」登记哪些 | — | §10.3 列出的全部 |

---

## 12 测试计划与 robot

### 12.1 基线测试对照（`logic_test.go`、`world_autoscale_mirror_test.go`、`integration_test.go`）

| 基线用例 | Java 对应 |
|---|---|
| `TestCreateScene_Mirror_SingleNode_Trivial`（`:1819`）、`_SameZoneSourceColocates`（`:3062`） | scene-manager：`createInstance` 返回的节点 = 发起节点 |
| `_NoSourceMapping_FallsBackToBestNode`（`:1842`）、`_SourceUnderLoadCap_StillColocates`（`:1864`）、`_CrossZoneSourceFallsBack`（`:3033`）、`TestCreateScene_Instance_NonMirror_UsesGetBestNode`（`:1889`）、`_StrictMode_*`（`:2159`、`:2203`）、`_Mirror_BypassesPurposeFilter`（`:2178`） | 不适用（D2、N2、N3） |
| `TestCreateScene_Mirror_SetsMirrorFlag`（`:1918`）、`_NonMirror_NoMirrorFlag`（`:1941`）、`TestCreateMirror_PopulatesMirrorSourceIndex`（`:2688`）、`TestCreateInstance_PopulatesNodeScenesIndex`（`:2669`） | scene：`sceneEntries` 带 `kind` / `source_scene_id` |
| `TestCleanupIdleInstances_MirrorDestroyedFaster`（`:1960`）、`_MirrorWithPlayers_NotDestroyed`（`:2008`）、`TestResolveMirrorTimeout_FallsBackToInstance`（`:2069`） | scene：按种类的超时、有人不回收、镜像 0 回落实例超时 |
| `TestDestroyInstance_CAS_AbortsWhenPlayersPresent`（`:2333`）、`TestAtomicDestroyIfIdle_*`（`:2595`、`:2616`）、`TestAtomicIncrPlayerCount_*`（`:2561`、`:2578`）、`TestEnterScene_AtomicIncr_NoOrphanPlayerCount`（`:2649`）、`TestStress_EnterDestroyRace_NoOrphanPlayerCount`（`:3225`） | scene：在途进场挡住回收；宽限内进场复活；宽限后进场 `failEnter`；随机交错的属性测试（§12.2 第 9 条） |
| `TestDestroyScene_ClearsMirrorFlag`（`:2038`）、`TestDestroyMirror_UnlinksFromSourceSet`（`:2719`） | scene：销毁后目录里没有它；`DirectoryMirrorSources` 不再认这个源 |
| `TestDestroyScene_CascadesToMirrors`（`:2751`）、`TestAutoscale_DrainCascadesMirrorsBornDuringTheDrainWindow`（`world_autoscale_mirror_test.go:78`） | scene：销毁源频道 → 镜像级联排空 → 同图改派 → 销毁 |
| `TestAutoscale_SkipsMirrorSourceAndDrainsAnotherChannel`（`:21`）、`_NoScaleInWhenEveryIdleChannelIsAMirrorSource`（`:41`）、`_MirrorQueryFailureBlocksScaleIn`（`:62`） | scene-manager：`WorldAutoscalerTest` 改用 `DirectoryMirrorSources` 重跑 |
| `TestIntegration_Rebalance_CascadesDependentMirrors`（`integration_test.go:612`） | scene-manager：`WorldRebalancePlannerTest`——镜像源不迁（D12） |
| `TestReconcileDeadNode_*`（`:2808`、`:2847`、`:2875`、`:3167`）、`TestDestroyInstance_DrainsResidualFromNodeAggregate`（`:2297`）、`TestCreateMirror_Dedup*`（`:2941`、`:2973`、`:2996`）、Agones 用例 | 不适用（D1、D13、N1、N5） |
| `TestCreateMirror_RejectsWhenSourceGone`（`:2899`）、`_AllowsWhenSourceExists`（`:2918`） | scene：源在排空 → 同步 3005；结果回来时源已不在 → 23 `{3023}`、不建 |
| C++：无镜像用例（B15） | 下列 scene 单测全部新增 |

### 12.2 scene 单测（`SceneWorldTest` / `ClientRequestHandlerTest` 风格：假 scene-manager 端口可控完成、假时钟、假 sink 记录帧）

新增 `MirrorSceneTest`、`InstanceLifecycleTest`，并补 `ClientRequestHandlerTest`、`SceneWorldTest`、`TeamFollowServiceTest`：

1. **正常路径**：应答 `{0}` 先于 79；79 的 `SceneInfoComp` 逐字段等于 `{源 conf, 新号, M, 0, {pid: true}}`；21 是自己；源场景旁人收到 51；坐标不变、速度为 0；位置记录写镜像号与源 conf；
   组队跟随钩子被调用；目录立即补发且条目 `kind = MIRROR, source = 源`。
2. **准入顺序**：`RESOLVING` / `FREEZING` → 3014；全 0 → 3005；只带 `dungeon_config_id` → 3005；镜像分支在 3008 之前（`{mirror, scene_id = 0}` 不回 3008，`{mirror, scene_id = 当前}`
   回 3008）；镜像的镜像、源是副本、M 不在表、源在排空、停止接客、节点上限、创建者上限各回 3005；**应答 `{3005}` 时不发 79**；镜像分支忽略请求的 `scene_config_id`；
   `mirror_config_id = 0x80000000` 视为非 0 且查表落空 → 3005。
3. **异步结局**：异常 / 超时 → 23 `{1003}`；`tip ≠ 0` → 23 `{3023}`；`node ≠ 自己` → 23 `{3023}` + ERROR；三者都不建场景、不发 79、回 NONE，之后的 63 能被受理。
4. **陈旧结果**：玩家离开 → 丢弃、不建；token 不符 → 丢弃；玩家被 5.1 排空改派走 → 23 `{3023}`、不建；号本地已存在 → 23 `{3023}` + ERROR。
5. **按 id 加入本地镜像**：79 的 creators 仍只有创建者；镜像里原有的人收到 21；加入者收到含他们的 47；指定的 conf 不符 → 3005；进回收宽限 / 排空中的镜像 → 3023。
6. **离开**：镜像里只带同图 → 进 WORLD 频道（人数并列也不留在镜像）、坐标保留；只带别的图 → 出生点；`scene_id = 源` → 回源频道。
7. **5.1 回归（R1–R4）**：应用计划不排空实例；`leastLoadedActive` / 排空改派 / 进场重定向不选实例；计划里的 MIRROR / DUNGEON 记录被拒；`xm.scene.channels` 只计 WORLD；
   `xm.scene.players` 把镜像居民计入源地图。
8. **空闲回收**：29.999 s 保持 ACTIVE、30 s 进入宽限并补发目录；宽限 29.999 s 保留、30 s 销毁；计时从最后一人离开起；有人进入清零；在途进场挡住计时与销毁；
   宽限内 `PlayerEnter` → 复活并进入（计 `revived`、目录补发 ACTIVE）；宽限内本地 63 显式进入 → 3023，组队跟随跳过；镜像超时 0 回落实例超时；两者都 0 永不回收；
   DUNGEON 用 300 s。
9. **随机交错**：假时钟下随机交错「进入 / 离开 / 在途进场开始与取消 / 时间推进」2000 轮，不变量：有人或有在途进场的实例从不被销毁；实例被销毁后目录里没有它；
   `xm_scene_instances` 与本地实例数一致。
10. **级联**：源频道（计划排空 / 孤儿图）排空完毕被销毁 → 镜像转 CASCADE → 居民同图改派、坐标保留 → 空了销毁；孤儿图 → 默认大世界出生点；源被缩容回滚（ACTIVE）→ 镜像不动；
    兜底检查（源不在本地）同样级联。
11. **DUNGEON**：`createInstance` 的 conf 必须等于 `Dungeon.scene_id`；79 带 `dungeon_config_id`、creators 为空；进入落 BaseScene 出生点；离开回世界地图出生点；
    存盘的 conf 为 17 → 下次登录落默认主世界（假仓库）；管理口销毁有人的副本 → 默认主世界出生点。
12. **组队**：队长进镜像 → 本节点队员跟进（与基线一致）；处于 `RESOLVING` 的队员跳过；队长所在实例在宽限中 → 不跟。
13. **43**：在镜像里请求 → 31 里是镜像的 info。
14. **字节级**：固定输入 `{conf = 1, scene_id = 0x0102030405060708, mirror = 1, creators = {42: true}}`，断言 `EnterSceneS2C` 序列化为
    `0A 14 08 01 10 88 8E 98 A8 C0 E0 80 81 01 18 01 2A 04 08 2A 10 01`（防止漏写 creators、写成 false 或 dungeon 非 0）。
15. **管理口**（`SceneAdminControllerTest`）：prod → 403；缺令牌 / 令牌错 → 401 或 503；缺 `X-Xm-Operator` → 400；Dungeon 不存在 → 3005；取号失败 → 1003；成功 → 号与 conf；
    销毁 WORLD → 3005、不存在 → 3000、重复 → 0。
16. **配置**（`SceneNodePropertiesTest`）：缺省值；`reclaim-grace < 10 s` 拒启；上限越界拒启。

### 12.3 scene-manager 单测（`SceneAssignerTest` / `SwitchTargetSelectorTest` / 规划器风格）

- `InstanceIdIssuerTest`：参数校验各分支回 3005；租约无效 → future 异常、`result = no_lease`；节点 = 发起节点；号非 0 且两次不同；不碰 Redis（假存储零调用）。
- `SceneDirectoryProviderTest`：`createInstance` 各结果的 Timer 标签；异常只带概要（同 `selectSwitchTarget`）。
- `ChannelSelectorTest`：conf 相同的 MIRROR / DUNGEON 条目不入选；UNSPECIFIED 当 WORLD；只有镜像时返回空。
- `SceneAssignerTest`：原实例是镜像且在场 → 返回它并写预占；在回收宽限 / 排空中 → 按地图选（不进镜像）；原实例是副本已消失 → 默认主世界。
- `SwitchTargetSelectorTest`（5.2 测试的补充）：显式号命中别的节点上的镜像 → 返回并写预占；只带地图 → 不选镜像；显式号命中宽限中的镜像 → DRAINING。
- `DirectoryMirrorSourcesTest`：多节点；排空中的镜像也算源；UNSPECIFIED / WORLD 条目不算。
- 规划器：`WorldAutoscalerTest`、`WorldRebalancePlannerTest` 用真实目录跑 §12.1 对应行；`WorldChannelControlPlaneTest`：`leader-eligible = false` 时仍申领发号租约。

### 12.4 真 Redis 集成测试（`-Dxm.it.redis=redis://127.0.0.1:6379`）

- `NodeDirectory` 往返新字段（`kind`、`source_scene_id`），旧条目（无字段）按 UNSPECIFIED 读。
- scene 发布、scene-manager 读取后，`DirectoryMirrorSources` 能认出镜像源；领导者一拍内缩容跳过它（沿用 `WorldChannelRedisIntegrationTest` 的装配）。
- 两个 scene-manager 副本（一个 `leader-eligible = false`）都能 `createInstance`，号不重复（各自 worker）。

### 12.5 本机切片

**单节点**：
1. 镜像全流程：63 镜像 → 79（坐标保留）→ 43 / 31 带镜像字段 → 离开回频道 → `mirror-idle-timeout + reclaim-grace` 后目录里该号消失、`destroyed_idle` +1。
2. 断线回镜像：在镜像里断开 → 立即重连 → 79 的 scene_id 是镜像号；断开超过镜像超时再重连 → 回源地图频道。
3. 关闭 scene-manager 再发 63 镜像：应答 `{0}` → 4 s 内 23 `{1003}`。
4. 管理口建副本 → robot 按号进入 → 管理口销毁 → 默认主世界出生点。

**双节点**（5.2 的 `XM_SCENE_NODES=2`）：
5. 节点 2 上的玩家按号加入节点 1 上的镜像（5.2 交出）→ 79 镜像信息、库里 epoch 加一。
6. 宽限复活：把镜像超时调短、宽限调长，在镜像进入宽限后从节点 2 按号加入（5.2 交出在途）→ 复活成功；反过来宽限调到下限、人为拖慢交出 → F10。
7. 管理口在节点 1 建副本，节点 2 的玩家按号加入。

### 12.6 robot

基线 robot 不覆盖镜像（`scene-core.md:241`），Go robot 也不读 mirror / dungeon / creators（`docs/reference/mmorpg-client-contract-robot.md:326`），所以 Go robot 不受 5.3 影响。
Java robot 新增两个场景（`RobotOptions.Scenario` 加 `MIRROR`、`DUNGEON`，现有 24 个），复用 5.2 `CrossNodeScenario` 的 `await` / `inbox` / `mark` 工具。新选项：
`mirror-config-id`（缺省 1，Mirror 表第一行，`main_scene_id = 1` 即默认主世界）、`instance-wait-ms`（等回收的上限，配合切片缩短的超时）、
`expect-mirror-validation = strict | lenient`（缺省 strict，与 Q1 绑定）、`scene-admin-url`（缺省 `http://127.0.0.1:18104`，令牌同 `audit` / `trade`）。

**`mirror`**（单节点即可；A、B 两个新账号，同处默认主世界的同一频道）：
1. A 发 63 `{mirror_config_id = 1, scene_id = 0}`：应答 `{0}` 先于 79；79 的 `scene_config_id` = 原地图、`scene_id ≠ 原频道`、`mirror_config_id = 1`、`dungeon_config_id = 0`、
   `creators = {A: true}`；随后自己的 21，坐标与原位置水平偏差 ≤ 0.5 m；B（看得见 A 时）收到 A 的 51；A 全程没收到 23。
2. A 发 43 → 31 的唯一一条等于第 1 步的 `scene_info`。
3. A 在镜像里再发 63 `{mirror_config_id = 1, scene_id = 0}` → `{3005}`，没有 79。
4. B 发 63 `{scene_id = 镜像, mirror_config_id = 1}` → `{0}`；B 收到 79（creators 仍是 `{A: true}`）、自己的 21、含 A 的 47；A 收到 B 的 21；A 移动后 B 收到 66。
5. A 断开后立即重连 → 79 的 `scene_id` 等于镜像号（回原实例，Q9）。
6. A、B 各发 63 `{scene_config_id = 原地图}` → 79 的 `mirror_config_id = 0`、`scene_id ≠ 镜像号`、坐标保留；后走的一方收到先走一方的 51。
7. A 连发两次 63 镜像 → 第一次 `{0}`、第二次 `{3014}`，最后恰好一条 79；A 随后回主世界。
8. 等 `mirror-idle-timeout + reclaim-grace + 2 s`（受 `instance-wait-ms` 约束）后 B 发 63 `{scene_id = 第 1 步的镜像号}` → 没有 79；同节点同步 `{3023}`，或（5.2 落地后、号已不在本地）
   `{0}` 再 23 `{3023}`，两者都接受。
9. 发 63 `{mirror_config_id = 999, scene_id = 0}`：strict → `{3005}`；lenient → `{0}` + 79。
10. 抓指标：scene `xm_scene_instance_lifecycle_total{kind="mirror",event="created"}` 增加、`{event="destroyed_idle"}` 增加、`xm_scene_instances{kind="mirror",state="active"}`
    回到 0；scene-manager `xm_scene_manager_instance_seconds_count{kind="mirror",result="ok"}` 增加。
11. 可选（需要 xm-team）：队长进镜像，同节点队员收到镜像的 79（并入 `team` 场景亦可）。

**`dungeon`**（需要 `XM_RUN_MODE=dev` 与 `XM_ADMIN_TOKEN`）：
1. 管理口建 DUNGEON 1 → `{tip 0, scene_id, scene_config_id = 17}`。
2. robot 发 63 `{scene_id}` → `{0}` → 79 的 `scene_config_id = 17`、`dungeon_config_id = 1`、`mirror_config_id = 0`、creators 为空；自己的 21 坐标是 BaseScene 17 的出生点 (180, 200, 0)。
3. robot 发 63 `{scene_config_id = 17}` → 不会留在副本：5.2 之前同步 `{3023}`，之后 `{0}` + 23 `{3023}`（17 不是世界地图）。
4. 第二个 robot 也进副本；管理口销毁 → 两人都收到默认主世界的 79 / 21（出生点），无 23；随后 63 `{scene_id = 副本号}` → 3023。
5. 管理口 prod 模式 403（切片另起 prod 进程时检查，可选）。

**`cross-node` 补充**（随 5.2 的 `CrossNodeScenario` 一起跑）：C 在节点 2，A 的镜像在节点 1；C 发 63 `{scene_id = A 的镜像}` → `{0}` → 79（镜像信息）、21、含 A 的 47；库里 epoch 加一（5.2 M1 的断言）。

**回归**：现有 24 个场景全部通过；`smoke` / `reconnect` 与 `mirror` 并发跑时，登录永远不会落进镜像（R1 的端到端证据）；`drain` 场景里有镜像存在时缩容不选它的源（hash 覆盖下）。

### 12.7 构建

改了 `xm-api` 的 proto 后按 `AGENTS.md` §4 全量 `./mvnw -B -DskipTests install`，再跑 `./mvnw -B -pl xm-scene,xm-scene-manager -am test`；真 Redis 集成测试加
`-Dxm.it.redis=redis://127.0.0.1:6379`。没有运行证据时不得声称「编译通过」「测试通过」。

---

## 13 实现记录

### 13.1 基础部分（契约、scene-manager、5.1 必改项 R1–R5、R7）

开放问题 Q1–Q13 全部按 §11 的推荐答案落地，D1–D19 全部采纳。

**契约（xm-api）**
- `world_channel.proto`：`ChannelKind` 追加 `CHANNEL_KIND_MIRROR = 2`、`CHANNEL_KIND_DUNGEON = 3`。
- `node_directory.proto` `SceneEntry`：追加 `ChannelKind kind = 5`、`uint64 source_scene_id = 6`（同一文件里的 `BattleNodeInfo` 属于批次 6.2）。
- `scene_directory.proto`：`CreateInstanceRequest{zone_id 1, requester_scene_node_id 2, player_id 3, kind 4, source_scene_id 5, scene_config_id 6,
  mirror_config_id 7, dungeon_config_id 8}` → `CreateInstanceResponse{tip_id 1, scene_node_id 2, scene_id 3}`；`SceneDirectoryService.createInstance`。
- `scene_admin.proto`（新）：dev 管理口的 `CreateDungeonInstanceRequest / Response`、`DestroyInstanceRequest / Response`（§6.13）。
- `com.game.api.ChannelKinds.isWorldChannel`：UNSPECIFIED 当 WORLD、MIRROR / DUNGEON 与不认识的取值都不当 WORLD，scene-manager 与 scene 只用这一处判法。
- 测试 `SceneInstanceContractTest`：取值、字段号、新旧互读。

**scene-manager**
- `InstanceIdIssuer`（无状态）：参数错回 3005；`NodeAvailability` 判发起节点不接新实例时回 3000（5.5 钩子，5.3 装配 `ALL`）；放置恒为发起节点；
  发号租约无效回 `NO_LEASE`，提供方以异常完成 future（Q12）。不读也不写 Redis。
- `SceneIdAllocator`（R5）：发号租约挪成独立 bean，**每个副本都申领**，主世界频道控制面与实例取号共用；租约确认丢失时回调监听（控制面借此让出领导锁）。
- `SceneDirectoryProvider.createInstance`：指标 `xm.scene_manager.instance{kind = mirror|dungeon|other, result = ok|bad_request|node_unavailable|no_lease|error}`
  （Prometheus `xm_scene_manager_instance_seconds`），异常只带概要。
- `ChannelSelector` 候选只认主世界频道（R1）；`DirectoryView.Scene` 带 `kind / sourceSceneId`；`WorldChannelPlanner` 每拍用 `DirectoryMirrorSources.of(目录)` 推导镜像源。
- R7：滚动升级顺序写在 `SceneDirectoryService` 类注释、`node_directory.proto`、`ChannelSelector` 类注释、两个进程的 `application.yaml`。

**xm-scene（只做与 5.1 逻辑的隔离，建实例本身见 13.2）**
- `SceneKind{WORLD, MIRROR, DUNGEON}`（与 `ChannelKind` 互转）；`Scene` 带 `kind / sourceSceneId / isWorldChannel()`。
- 低层登记 `SceneWorld.addScene(SceneInfoComp, SceneKind, long source)`（包内可见，不做业务校验；本地重号抛异常），供 §6.8 的 `createInstance(spec)` 校验后调用。
- `sceneEntries` 带种类与源；`xm.scene.channels` 只计 WORLD（R4）；`applyChannelPlan` 只把计划外的 **WORLD** 转排空（R2），计划里与本地实例同号的记录一律拒绝；
  `leastLoadedActive` 只看 WORLD（排空改派、进场重定向、只带地图的 63），身在实例里只带当前地图必去主世界频道、不留在实例（R3）。

**测试证据**：`-pl xm-api,xm-discovery,xm-scene-manager,xm-scene install`，带 `-Dxm.it.redis` 与 `-Dxm.it.mysql` 全部通过（xm-scene 752 个用例、0 失败）。
新增 / 补充：`InstanceIdIssuerTest`、`SceneIdAllocatorTest`、`SceneIdAllocatorRedisIntegrationTest`（两个副本、一个 `leader-eligible = false`，并发取号 8000 个不重号；
租约被夺后转 `NO_LEASE`）、`DirectoryMirrorSourcesTest`、`SceneDirectoryProviderTest`、`ChannelSelectorTest`、`SceneAssignerTest`、`SwitchTargetSelectorTest`、
`WorldAutoscalerTest`、`WorldRebalancePlannerTest`、`WorldChannelConfigurationTest`、`WorldChannelRedisIntegrationTest`（节点经 Redis 目录上报镜像 → 种类不丢、
login 不分进镜像、领导者一拍内缩容跳过镜像源）、xm-scene `InstanceChannelIsolationTest`。双节点切片 `smoke` / `reconnect` / `cross-node` 通过，各进程 ERROR 行为 0。

### 13.2 scene 节点部分

**xm-scene**（全部状态只在场景逻辑线程读写，没有新增 Redis / MySQL I/O）
- `Scene`：`DrainCause{NONE, PLAN, IDLE, CASCADE, ADMIN}`、`drainingSinceNanos`、`emptySinceNanos`（哨兵 `Long.MIN_VALUE`，单调时钟可取任意值）；
  5.1 的 `setDraining` 记 `PLAN`。`InstanceSpec`（`toInfo()` 即 79 / 31 的 `scene_info`，镜像 `putCreators(创建者, true)`）。
- `SceneTables.mirrorExists`（按 uint32 位型查，`≥ 2^31` 恒无）/ `dungeonSceneConfigId`；`ConfigSceneTables` 读 Mirror / Dungeon 表。
- 63 镜像分支（§6.7）：`ClientRequestHandler` 在全 0 之后、3008 之前进 `SceneWorld.checkMirrorRequest`（`bad_source → bad_mirror_config →
  source_draining → not_accepting → node_cap → creator_cap`，一律 3005）；受理后回 `{0}`，`beginMirrorCreate` 用 `PlayerSwitch.mirrorCreate`
  （`Purpose.MIRROR_CREATE`，复用 5.2 的 RESOLVING 槽，永不冻结；期间 63 回 3014、组队跟随跳过）经 `InstanceIds` 取号。`InstanceIds` 由
  `SceneManagerSwitchTargets` 实现（同一个 Dubbo 引用、`retries = 0`、同一个本地兜底超时，结果投回逻辑线程）。结果按引用核对后：Failed → 23 {1003}、
  Refused → 23 {3023}、节点不是本节点 → 23 {3023} + ERROR、复核不过（玩家不在源 / 源排空 / 停止接客 / 上限）→ 23 {3023}、本地拒建 → 23 {3023}；
  成功 → `createInstance` → `switchScene`。没装配取号（`SceneInstances.DISABLED`）= 应答 `{0}` 后 23 {1003}（D9）。
- `createInstance(spec)` 是建实例的唯一入口（§6.8），建好 / 回收 / 复活 / 级联 / 销毁都经 `SceneInstances.directoryChanged`（`SceneNode` 接
  `requestDirectoryPublish`）立即补发目录。
- 回收与级联（§6.10、§6.11）：每秒任务由 `drainStep` 改为 `maintainScenes()` = 级联兜底（源不在本地或不是 WORLD）→ 空闲判定（满超时进 IDLE 宽限，
  宽限中发现有人按复活处理）→ `drainStep`（IDLE 只在宽限满、空、无在途进场时销毁；其余改派）。`onPlayerLoaded` 目标在 IDLE 宽限中 → 复活进入；
  `destroyScene` 销毁 WORLD 时把以它为源、未在 CASCADE / ADMIN 中的镜像（含 IDLE 宽限中的）转 CASCADE 并在同一次推进里改派、销毁。
  `destroyInstance` 只经 dev 管理口（WORLD / 0 → 3005，不在 → 3000，已在 CASCADE / ADMIN → 0）。
- dev 管理口（§6.13）：`SceneAdminController`（`/admin/scene/instance/{create,destroy}`，protobuf 二进制；非 dev / test 403，先于解析请求体）+
  `SceneAdminAuthFilter`（`XM_ADMIN_TOKEN` 常数时间比对、未配 503、错 401、缺 / 非法操作人 400、审计行、`xm.scene.admin.requests{op, status}`）；
  建副本等结果超时时撤掉结果（逻辑线程据此不取号 / 不建 / 建好交不回去就当场销毁，不留没人知道号的副本）。
- 配置 `xm.scene.instance.*`（`mirror-idle-timeout` 30s / `idle-timeout` 300s / `reclaim-grace` 30s ≥ 10s / `max-per-node` 200 / `max-per-creator` 3，
  绑定时校验、不满足拒启；前三项可用 `XM_SCENE_MIRROR_IDLE_TIMEOUT` / `XM_SCENE_INSTANCE_IDLE_TIMEOUT` / `XM_SCENE_INSTANCE_RECLAIM_GRACE` 覆盖）。
- 指标（§8.2）：`xm_scene_instances{kind, state}`、`xm_scene_instance_lifecycle_total{kind, event}`、`xm_scene_mirror_requests_total{result}`、
  `xm_scene_mirror_resolves_total{result}` 启动即注册；`xm_scene_channels` 只计 WORLD，镜像居民计入源地图的 `xm_scene_players`。
- `SceneDirectoryPublisher`：立即补发改为单飞（标脏 + 至多一个发布任务、不加锁不碰 Redis），逻辑线程上可调。

**robot**：`mirror`、`dungeon` 子命令（`InstanceOptions`：`--mirror-config-id`、`--instance-wait-ms`、`--expect-mirror-validation`、`--scene-admin-url`、
`--scene-manager-metrics-url`）；`cross-node` 末尾追加「跨节点按号加入镜像」。目录发布窗口（§6.16）内的跨节点按号加入回 23 {3023} 属预期，`cross-node`
对这一结局最多重试 3 次（双节点切片实测：每一轮第一次按号加入都回 23 {3023}——镜像建好约 25 ms 以上之后才进目录，重试是每次通过的一部分，不是偶发兜底；第二次即成功）。`tools/local/start-slice.sh` 缺省把镜像空置超时调到 5s、
回收宽限调到 10s（§7.3）。

**测试证据**：`-pl xm-api,xm-scene-manager,xm-scene,xm-robot install`，带 `-Dxm.it.redis` 与 `-Dxm.it.mysql` 全部通过：xm-api 38、xm-scene-manager 232、
xm-scene 834（1 个基准测试跳过）、xm-robot 201，0 失败。新增 / 补充：`MirrorSceneTest`（§12.2 第 1–6、13、14 条）、`InstanceLifecycleTest`（第 8–11 条，
含 2000 轮随机交错）、`SceneAdminControllerTest` / `SceneAdminAuthFilterTest`（第 15 条）、`SceneNodePropertiesTest`（第 16 条）、`TeamFollowServiceTest`
（第 12 条）、`SceneMetricsTest`、`SceneDirectoryPublisherTest`、`SceneManagerSwitchTargetsTest`、`ClientRequestHandlerTest`；robot `InstanceOptionsTest`、
`InstanceChecksTest`、`InstanceMetricsTest`、`SendPacerTest`、`SceneAdminClientTest`（字段号用 xm-api 生成类钉住）。本机切片：单节点 `mirror`（47 项）、
`dungeon`（31 项）通过；双节点 `cross-node`（41 项，含跨节点加入镜像）、`mirror`、`dungeon`、`smoke` 通过；各进程 ERROR 行为 0。
