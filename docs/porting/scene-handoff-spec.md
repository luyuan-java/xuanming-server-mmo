# 跨节点换图与归属交接（批次 5.2）移植统一规格：从 mmorpg 基线到 Java 版

> **基线**：mmorpg `26ceb70ca`，与 `contract/SOURCE.properties:4` 的 `mmorpg.commit` 相同。Java 侧以 `58afbba` 为准（HEAD）。
> 写作时工作区里有批次 5.1（主世界多频道）正在实现的未提交改动，**正好落在本批要动的文件上**：
> `xm-scene` 的 `SceneWorld.java`、`ClientRequestHandler.java`、`SceneNode.java`、`Scene.java`、`TeamFollowService.java`、`SceneMetrics.java`、
> `application.yaml`，以及 `xm-gate/src/main/resources/application.yaml`、`tools/local/start-slice.sh`、`xm-discovery/.../RedisKeys.java`。
> **本稿引用的 Java 行号一律按 HEAD `58afbba`**（用 `git show HEAD:<path>` 核对过），5.1 提交后会漂移，所以引用时同时写出类名 / 方法名。
>
> **路径怎么读**
> - 以 `go/`、`cpp/`、`proto/`、`robot/`、`deploy/`、`PROGRESS.md` 开头的路径，以及 `travel.md`（= `docs/design/cross-zone-scene-travel.md`）在 `D:\work\mmorpg` 下；
>   以 `xm-`、`docs/`、`tools/`、`config-data/`、`contract/`、`PARITY.md`、`AGENTS.md` 开头的路径在 `D:\work\xuanming-server-mmo` 下。
> - 不带目录的 Go 文件：`enterscenelogic.go`、`owner_epoch.go`、`changesceneutil.go`、`leavescenelogic.go`、`scene_node_client.go` 在
>   `go/scene_manager/internal/logic/`；`errors.go` 在 `go/scene_manager/internal/constants/`；`metrics.go` 在 `go/scene_manager/internal/metrics/`；
>   `config.go` 在 `go/scene_manager/internal/config/`；`sm.yaml` = `go/scene_manager/etc/scene_manager_service.yaml`；`ownerepoch.go` 在 `go/shared/ownerepoch/`；
>   `entergamelogic.go` 在 `go/login/internal/logic/clientplayerlogin/`；`leasemonitor.go` 在 `go/player_locator/internal/logic/`。
> - 不带目录的 C++ 文件：`player_lifecycle.{h,cpp}`、`travel_freeze_cap.h`、`handoff_mark_withdraw.h`、`exit_release_mark.h`、`player_exit_intent.h`、
>   `player_scene.cpp`、`asset_op_system.cpp`、`player_team.cpp` 在 `cpp/libs/services/scene/player/system/`；`player_ownership_comp.h`、`player_frozen_comp.h`
>   在 `cpp/libs/services/scene/player/comp/`；`skill.cpp` 在 `cpp/libs/services/scene/combat/skill/system/`；`movement.cpp`、`movement_acceleration.cpp` 在
>   `cpp/libs/services/scene/spatial/system/`；`player_scene_handler.cpp`、`player_movement_handler.cpp` 在 `cpp/nodes/scene/handler/rpc/player/`；
>   `scene_handler.cpp` 在 `cpp/nodes/scene/handler/rpc/`；`scene_node_service.cpp` 在 `cpp/nodes/scene/handler/grpc/`；`mission_event_handler.cpp` 在
>   `cpp/nodes/scene/handler/event/`；`sm_reply.cpp` = `cpp/nodes/scene/rpc_replies/scene_manager_response_handler.cpp`；`gate_event_handler.cpp`、
>   `scene_entry_dispatch.{h,cpp}`、`scene_route_helper.h` 在 `cpp/nodes/gate/handler/event/`；`client_message_processor.cpp` 在 `cpp/nodes/gate/handler/rpc/`。
> - proto（mmorpg 行号）：`sm.proto` = `proto/scene_manager/scene_manager_service.proto`，`storage.proto`、`scene_node_service.proto` 同目录；
>   `gate_event.proto`、`gate_command.proto` 在 `proto/contracts/kafka/`；`scene.proto` 在 `proto/scene/`。
> - Java 类：`SceneWorld` / `ClientRequestHandler` / `ScenePlayer` / `SceneMessageIds` / `ClientSink` 在 `xm-scene/src/main/java/com/game/scene/world/`；
>   `StoragePlayerRepository` 在 `.../scene/storage/`；`OwnerLeaseRenewer` / `OwnerTakeoverSubscriber` 在 `.../scene/ownership/`；`GateLinks` 在 `.../scene/link/`；
>   `RedisPlayerLocations` 在 `.../scene/location/`；`AssetOpService` 在 `.../scene/asset/`；`PlayerStore` / `PlayerMapper` 在
>   `xm-player-store/src/main/java/com/game/player/store/`，表结构 `xm-player-store/src/main/resources/db/xm-player-schema.sql`（下称 `schema.sql`）；
>   `ClientDispatcher` / `SceneEventRouter` / `ClientSession` / `SessionRegistry` 在 `xm-gate/src/main/java/com/game/gate/session/`，`GatePresence` 在 `xm-gate/.../gate/presence/`；
>   `SceneLink` / `SceneLinks` / `SceneLinkManager` 在 `xm-gate/.../gate/link/`；`EnterGameHandler` 在 `xm-login/src/main/java/com/game/login/handler/`；
>   `LoginClientMessageService` 在 `xm-login/.../login/`；`PlayerLocationDirectory` 在 `xm-discovery/src/main/java/com/game/discovery/location/`；
>   `SceneAssigner` 在 `xm-scene-manager/src/main/java/com/game/scenemanager/`；`node_link.proto`、`scene_directory.proto`、`client_call.proto` 在 `xm-api/src/main/proto/xm/api/`。
> - tip 数值取自 `xm-table/src/main/proto/tip/`：`scene_error_tip.proto`（3000 `:12`、3005 `:22`、3008 `:28`、3014 `:40`、3023 `:58`、3027 `:66`）、
>   `common_error_tip.proto`（1003 `:18`、1005 `:22`、1006 `:24`）、`login_error_tip.proto`（2005 `:22`、2017 `:46`）、`asset_error_tip.proto`（27003 `:18`、27006 `:24`）、
>   `cross_server_error_tip.proto`（13000 `:12`）。消息号取自 `xm-proto/src/main/resources/contract/message_id.txt`（**号 N 在第 N+1 行**）。
> - 带「推导」字样的结论没有对应的测试或用例覆盖，是把多处代码路径串起来得出的。
>
> **本稿的来历**：由两份分区稿合并而成——基线分区稿（协议、时序、失败与回滚、客户端所见、竞态、指标）与 Java 分区稿（现状、缺口、设计、测试）。
> 两份稿子之间、以及它们与代码、inventory 不一致的地方都回到代码重新核对过，更正集中在 §8.3。Java 设计在分区稿基础上做了四处收紧
> （交出事务加「剩余租约安全边际 + 租约值作本次标识」、结局不明时用加锁读探测、gate 在会话已释放时也代为放弃、源节点确知帧没发出时自己释放新 epoch），
> 理由写在 §5.2、§5.7、§5.5。本稿只读代码，除本文件外没有改任何文件。

---

## 0 概览与范围（含与 5.1 / 5.3 / 5.4 / 5.5 的边界）

### 0.1 结论速览

- **基线没有专用消息，跨节点换图要往返 scene_manager（下称 SM）两次**。
  - 客户端只发 63。scene 事先不知道目标在不在本节点，一律请 SM 执行 `EnterScene`（`player_scene_handler.cpp:151-166`）。
  - SM 发现要换节点、而源节点还没写「已落盘」标记时，回私有码 **18** `ErrHandoffPending`（`errors.go:45-50`），什么都不改。
  - 源 scene 收到 18 才起被动交接：冻结 → 存盘 → 写标记 `player:{id}:handoff = "{E}:{saved_at_ms}" EX 300` → 重发 `EnterScene`
    （`player_lifecycle.cpp:3921-3955`、`:2818-2955`、`:3335-3452`、`:3481-3571`）。
  - SM 第二次在**一段 Lua** 里复核标记、把 `owner_epoch` INCR 到 E+1、写 location（`owner_epoch.go:102-121`），再经 Kafka 推 `RoutePlayerEvent{…, owner_epoch=E+1}`
    给 gate（`enterscenelogic.go:732-766`、`:1305-1356`）；gate 改绑会话并转 `PlayerEnterGameNode` 给目标节点 B（`scene_entry_dispatch.cpp:110-138`）。
  - B 建实体前先过 A2′：`owner_epoch == E+1` 才删标记、建实体（`exit_release_mark.h:249-268`、`:422-439`）。源节点 A 只凭「原子取证」（先删本族标记、再读 epoch / location）
    裁决去留：epoch 未变（B4）或读到本次回滚回执（B5）才解冻，其余一律**不存盘销毁**（`player_lifecycle.cpp:3757-3918`）。
  - 冻结有界：存盘看门狗 30 s、应答看门狗 30 s、晚发闸 35 s、硬上限 70 s + 1 s 扫描，服务端最迟约 71 s 出结论（`travel_freeze_cap.h:50-98`）。
- **基线这条链在生产配置下没有端到端验收**：dev 配置 `AllowUnsafeCrossNodeHandoff: true`（`sm.yaml:61`）让同 zone 跨节点换图第一跳就放行、不铸 epoch、
  标记链从不触发（`sm.yaml:46-60`）；C++ 单测 2026-09-29 诊断编译后 105/105（`PROGRESS.md:6009-6010`），但 false 配置下的验收清单写明「当前不可执行」
  （`travel.md:1545-1561`）。基线 robot 全绿不能证明这条链可用。
- **Java 现状**：63 的目标不在本节点一律回 3023（`ClientRequestHandler.java:208-235`；`SceneWorld.java:234-248`）。Java 有一套完整的 MySQL 单行归属协议
  （夺权 / 在线存盘 / 写回并释放 / 只释放 / 续约全带 `owner_epoch` 围栏，`PlayerMapper.java:51-115`）、带 (epoch, 写序号) 的位置记录
  （`PlayerLocationDirectory.java:66-87`）、按 (节点, 链路代次, 玩家, epoch) 过滤的 gate 会话绑定（`ClientDispatcher.java:601-669`），但没有「交出」原语、
  scene 不调 scene-manager、scene 不能让 gate 改绑、冻结闸全是恒放行的桩。
- **本稿建议的 Java 设计**（§5）：**一笔 MySQL 交出事务**同时完成「带围栏写回冻结快照」与「`owner_epoch` 加一、保持未释放、给新租约」，它一次顶替基线的
  handoff 标记、18 换手门、SM 铸 epoch、`ReleasePlayer`、回滚回执、A2′。提交后源节点移除实例（旁人 51），经**同一条链路**给 gate 发新帧
  `PlayerTransfer{E→E+1, 目标}`；gate 在会话线程上改绑并发 `PlayerEnter{E+1, transfer=true}`；目标节点照现有进场逻辑加载（库里 epoch == E+1 才进），
  下发 79 / 21 / 47。scene-manager 只负责选目标（复用 5.1 的选频道与软预占），不进提交路径。
- **客户端所见**：成功路径与基线相同（63 应答无错 = 已受理 → 旁人 51 → 本人 79 / 21 / 47）；在途再发 63 回 3014（Java 新出现，与基线一致）；
  交出前失败原地解冻 + 23 `{3023}`（同基线）；交出后失败推 23 `{3023}` 后断开，**不发 34**（D5，同 Java 现有口径 `PARITY.md:37`）；
  冻结窗口从基线的最长 71 s 缩到一笔库事务加一次探测（≤ 约 15 s，D4）。

### 0.2 盘点 id 与落点

| 盘点 id | 基线落点 | 盘点原文（Java 列） | 5.2 交付 |
|---|---|---|---|
| scene-switch-cross-node（`docs/porting/inventory/scene-core.md:196-206`） | `player_scene_handler.cpp`、`player_lifecycle.cpp` RequestSceneChange / DispatchEnterSceneReply / HandleSceneChangeEnterSceneReply、`sm_reply.cpp` | missing | 63 的 Remote 分支：选目标 → 冻结 → 交出 → 改绑 → 目标进场（§5.5–§5.8） |
| ownership-handoff（`scene-core.md:208-218`） | `player_lifecycle.cpp` StartTravelHandoff…EnforceTravelFreezeCaps、`travel_freeze_cap.h`、`handoff_mark_withdraw.h` | missing | `PlayerStore.handOffOwnership` + 冻结状态机 + 入口集中冻结闸（§5.2、§5.5、§5.9） |
| sm-cross-node-scene-switch（`docs/porting/inventory/scene-manager-match.md:82-92`） | `enterscenelogic.go` crossNodeHandoff / dispatchReleasePlayer / routePlayerToGate | missing | `SceneDirectoryService.selectSwitchTarget`（只选，不铸、不写位置，§5.4） |
| sm-player-location（`scene-manager-match.md:58-68`） | `changesceneutil.go`、`leavescenelogic.go`、`storage.proto:16-43` | missing（**已过时**：记录自批次 3.3 起就有，`PARITY.md:91`） | 交出期间的写入规则（§5.14），读者不改 |

相关但不在本批交付的盘点项：sm-owner-epoch-handoff（`scene-manager-match.md:70-80`，Java「done」但注明「源先落盘再放行」尚无对应——本批补上）、
reconnect-resume（`scene-core.md:136-146`，Java 已按自己的方式做完，`PARITY.md:91`）、sm-gate-command-channel（`scene-manager-match.md:94-104`，
「missing」已过时，推送通道见 `docs/design/architecture.md:130-149`）。

### 0.3 范围

**5.2 做**：63 指定 scene_id 不在本节点（以及 5.1 切到 hash 覆盖后「只带地图而本节点没有该图」）时的跨节点换场景；交出原语；冻结闸（客户端入口、资产通道、世界内部）；
gate 改绑；目标节点的「交出进场」；scene → scene-manager 的选目标调用；scene 推 23；指标、文档与 PARITY 登记；本机双 scene 切片与 robot。

**5.2 不做**：跨 zone（226 / 124、等待落点、票据、3027 / 13000，5.4）；镜像 / 副本（5.3）；有人频道跨节点排空改派、整节点疏散、死节点接管、再入屏障（5.5）；
回合制战斗冻结（6.3）；队伍跟随跨节点拉人（基线同样不做：`player_lifecycle.cpp:3925-3937`，team-system DV-6）。

### 0.4 与其它批次的边界与钩子

| 基线段落 | 归属 | 5.2 留下的钩子 / 约束 |
|---|---|---|
| 频道集合、conf 选频道 + 原子预占、显式 scene_id 解析、排空频道不可进（`enterscenelogic.go:1171-1286`） | **5.1** | 5.1 交付 `SwitchTarget{Local / Remote / Reject}`，暂把 Remote 映射成 3023（`docs/porting/scene-channels-spec.md:82`、`:834-842`）；5.2 把 Remote 接到 §5.5。5.1 的 `ChannelSelector.select(zone, conf, excludeSceneId, playerId)`（`scene-channels-spec.md:830`）由 `selectSwitchTarget` 复用；软预占带 TTL，5.2 的失败出口**不需要**成对退还（5.1 D7）。5.1 规格里 Remote 带 `nodeId`，5.2 不用它（节点由 scene-manager 定），可以去掉或恒 0 |
| 覆盖模式 per-node → hash（5.1 D6，`scene-channels-spec.md:1069`） | 5.1 配置、5.2 上线后切 | per-node 下「只带地图」永远在本节点完成，跨节点只由显式 scene_id 触发；切 hash 的时机见 Q4 |
| 换手门 18、ReleasePlayer、epoch 铸造、location CAS、推路由与回滚、源端冻结 / 存盘 / 标记 / 取证、A2′、gate 改绑（`enterscenelogic.go:479-767`；`player_lifecycle.cpp:2818-4373`） | **5.2** | 本稿 §1–§5 |
| 镜像自动进场（CreateScene 应答驱动，落到别的节点时同样走 18 交接，`sm_reply.cpp:136-176`）；副本 | **5.3** | 复用同一原语：`SceneTransfers.begin(player, Target(node, scene), Reason.MIRROR / DUNGEON, playerRequested = true)`；63 的镜像分支（`ClientRequestHandler.java:216-217`）届时改为「请求建场景，再 begin」 |
| 跨 zone 传送（同一个 `StartTravelHandoff`，targetZone ≠ 本 zone，`player_lifecycle.cpp:2745-2816`；SM 第 2 步 `enterscenelogic.go:366-431`、`:1006-1088`） | **5.4** | 交出事务相同（Java 单库 `xm_java`，`player.zone_id` 列已在，`schema.sql:14`）；`PlayerTransfer` 预留字段 7 承载 `redirect{gate 地址, 令牌}`，gate 推 124 而不是改绑；失败码 3027 / 13000 按跨 zone 分支（`player_lifecycle.cpp:2826-2829`、`:2856-2860`）；冻结闸共用 |
| 疏散 / 排空改派（也写标记并发不带等待者的 `EnterScene(0,0)`，`player_lifecycle.cpp:2249-2363`）、死节点接管（`enterscenelogic.go:300-331`、`:840-940`）、再入屏障 | **5.5** | 疏散 = 服务器发起的批量 `begin(…, Reason.EVACUATE, playerRequested = false)`，失败不推 tip，并发受存储线程池约束（`StoragePlayerRepository.java:170-176`）。死节点无法交出，只能等租约过期（30 s）；5.5 若要提前释放必须先确认旧进程已不能写库 |
| A1′ 断线释放标记（`exit_release_mark.h:16-19`；交接在途压制 A1′，`player_lifecycle.cpp:2064`、`:2129-2131`） | 基线横跨 reconnect 与 5.2 | Java 不需要：断线即写回并释放（`SceneWorld.java:637-667`），重连按新 epoch 从库重载（`architecture.md:637`） |
| 回合制战斗在途闸（63 的 3023、资产通道 27002） | **6.3** | 交出与战斗冻结互斥：`begin` 拒绝战斗中的玩家，`PrepareBattle` 拒绝 `switchState ≠ NONE` 的玩家 |

### 0.5 与已有冻结闸桩的关系

基线的冻结闸散在二十余处，按 `PlayerFrozenComp`（冻结）或 `PlayerTravelHandoffComp`（交接意图）各自判（§3.3）。Java 已经留了桩，但全部恒放行：

| Java 桩 | 现状 | 基线口径 | 5.2 |
|---|---|---|---|
| 资产通道第 5 步（`AssetOpService.java:196-202`；类注释 `:46-49`） | 只判 `ownerEpoch != 0`，否则 RETRY 27003 | 冻结**或**交接在途都回 RETRY{27003}，判定在记账之前；刻意不复用 `IsCrossZoneFrozen`，因为它漏掉「已挂交接组件、存盘还没落地」那一段（`asset_op_system.cpp:162-177`、`:750-758`） | `switchState == FREEZING` 回 RETRY 27003（RESOLVING 不冻结，与基线「18 之后才冻结」一致，`player_lifecycle.cpp:3946-3953`） |
| `BagService.writable`（`BagService.java:113-116`，调用点 `:71`、`:102`） | 恒放行 | 1005（`cpp/libs/modules/bag/bag_service.cpp:76-90`、`:158`） | 按 `player.frozen()` 回 1005（纵深防御） |
| `PetService.checkWritable`（`PetService.java:493-498`） | 恒放行 | 1005（`player_pet.cpp:234-237`） | 同上 |
| `AttributeService.checkWritable`（`AttributeService.java:527-533`） | 恒放行；173 自动加点不过写前置 | 1005（`player_attribute.cpp:283-286`）；173 不过写前置（`scene-core.md:333`） | 同上；173 在入口回 1005（D9） |
| `MissionService.writable`（`MissionService.java:226-229`，调用点 `:110`、`:166`、`:218`；`:218` 是事实派发） | 恒放行 | 拒绝接取 / 领奖 1005、丢弃事实（`player_mission.cpp:31`；`mission_event_handler.cpp:43`） | 同上 |
| `CurrencyService.add / deduct`（`CurrencyService.java:43-105`） | **没有闸桩**，只在 `Wallet.java:23` 注释里提到 | 27003（`cpp/libs/modules/currency/system/currency_system.cpp:105-114`、`:228-235`）；GM 封禁 / 解封 1005（`player_currency_handler.cpp:102-108`、`:129-135`） | 加闸桩回 27003 |

**对 Java 的含义**：只要存在「源节点在快照与交出之间还在服务输入」的窗口，就必须有等价的闸，否则冻结快照与内存分叉（`player_frozen_comp.h:11-17`；`scene-core.md:218`）。
Java 的窗口只有一笔库事务加探测，但闸照样要做；本稿把闸收拢到入口、缺省拒绝，并加事后检测（§5.9，D7）。

### 0.6 客户端可见效果速览

| 情形 | 基线（生产配置） | Java 现在 | Java 5.2 |
|---|---|---|---|
| 63 指定别的节点上的 scene_id | 应答 `{0}` → 旁人 51 → 本人 79 / 21，下一拍 47（§3.1） | 应答 `{3023}` | 应答 `{0}` → 旁人 51 → 本人 79 / 21 / 47（47 非空才发） |
| 冻结 / 在途中再发 63 | 3014（`player_scene_handler.cpp:63-72`） | 不会出现（同步完成，`ClientRequestHandler.java:203-207`） | 3014 |
| 目标不存在 / SM 拒绝 | 23 `{3023}`（`player_lifecycle.cpp:3956-3961`） | 应答 `{3023}` | 应答 `{0}` 后 23 `{3023}` |
| SM 不可达 | 没注册任何 SM：同步应答 1003（`player_scene_handler.cpp:139-149`）；传输失败：23 `{1003}`（`player_lifecycle.cpp:3195-3216`） | — | 应答 `{0}` 后 23 `{1003}`（D12） |
| 交出前失败 | 解冻留原地 + 23 `{3023}`（`player_lifecycle.cpp:4311-4373`） | — | 同左 |
| 交出后无法落地 | 23 `{3023}` + 34 `{3023}` + 不存盘销毁，回选服重登（`player_lifecycle.cpp:4218-4309`） | — | 23 `{3023}` 后断开，不发 34（D5） |
| 冻结时长上界 | 约 71 s | — | 约 15 s（D4） |
| 跨节点换**不同**地图的落点 | B 上 `mapChanged` 恒为 false，坐标按导航网格校验，合法就保留旧坐标（推导，§3.4） | — | 一律落出生点（D10） |

---

## 1 基线协议（时序、消息、围栏、超时）

### 1.1 参与方与线程

| 参与方 | 职责 | 线程 / 并发约束 |
|---|---|---|
| 客户端 | 发 63；收 63 应答及之后的 79 / 21 / 47 / 23 / 34 | — |
| gate | 按会话的 `SceneNodeService` 实体号转发客户端消息；消费 `RoutePlayerEvent`，改绑会话、转 `PlayerEnterGameNode`；断线时向当前绑定节点发 ExitGame | 只在 gate 主 EventLoop 上处理（`scene_entry_dispatch.h:21-28`） |
| 源 scene A | 63 准入；登记在途换图；收到 18 后发起交接；取证并裁决去留 | 全在逻辑线程；异步回调只捕获 `playerId` 与代际，回调里按 id 回查实体（`player_lifecycle.cpp:2741-2742`） |
| SM（Go） | 位置与归属的唯一写入方：换手门、铸 epoch、CAS 写 location、发 ReleasePlayer、推 Kafka 路由、失败回滚 | 单次 gRPC 内同步执行；ReleasePlayer 在独立 goroutine 里发（`scene_node_client.go:324-366`） |
| 目标 scene B | 收 `PlayerEnterGameNode`，执行 A2′，从 Redis 载入，建实体，进场 | 逻辑线程 |
| Redis（zone） | `player:{id}:{location,owner_epoch,handoff}` 与玩家数据 blob | 归属判定的关键步骤全是单段 Lua |
| Kafka | `GateCommand` 发往 gate-cmd topic，分区 = gate_node_id % P（`changesceneutil.go:24-38`） | SM 同步写，RequireOne，超时 5 s（`sm.yaml:40-44`） |
| login / player_locator | 重登时用 `ZoneId=0` 让 SM 按 location 定去向（`entergamelogic.go:664-667`）；断线租约 30 s 到期后调 LeaveScene 删 location（`leasemonitor.go:283-315`） | — |

### 1.2 Redis 键（Go 与 C++ 一字不差）

| 键 | 值 | 写入方 | 删除方 / 回收 | 出处 |
|---|---|---|---|---|
| `player:{id}:location` | `PlayerLocation` pb：scene_id, node_id, update_time, zone_id, owner_epoch, pending_scene_conf_id, rollback_receipt | 只有 SM `EnterScene` 的 Lua（铸造 / 不铸造 / 回滚三种） | SM `LeaveScene`「原文未变才 DEL、同段 Lua 扣人数」；回滚分支也可能 DEL | `storage.proto:16-43`；`changesceneutil.go:63-69`、`:128-187`；`leavescenelogic.go:33-49`、`:116-159` |
| `player:{id}:owner_epoch` | 十进制整数，只经 INCR 前进 | SM 铸造 INCR；回滚也 INCR（bump）；同节点补种 SETNX | 永不删除（`changesceneutil.go:198-206`） | `ownerepoch.go:15-22`；`owner_epoch.go:32-46` |
| `player:{id}:handoff` | `"{epoch}:{saved_at_ms}"`，EX 300 | 源 scene `BeginTravelHandoff`；疏散改派（条件写）；A1′；A2′ 放弃时补写；SM 回滚转写为 `"{E+2}:{同一 t}"` | 源 scene 撤回（按原文条件删）；取证脚本（删后缀等于 t 的本族）；A2′（核对 epoch 后删 ≤N 的）。**SM 从不删** | `player_ownership_comp.h:20-41`、`:65-74`；`exit_release_mark.h:30-40`；`ownerepoch.go:23-28`、`:43-46` |

### 1.3 内部消息与字段

| 消息 | 方向 / 通道 | 本协议用到的字段 | 出处 |
|---|---|---|---|
| `scene_manager.EnterSceneRequest` | A → SM，异步 gRPC | `player_id`、`scene_id`（0 = 按 conf 挑频道）、`scene_conf_id`、`session_id`、`gate_id`、`gate_instance_id`（带 GateId 时必填，Kafka 防僵尸）、`zone_id`、`gate_zone_id`、`correlation_id`（线程内单调，SM 原样回显）。scene 发的请求**不带** `request_id` | `sm.proto:78-100`；`player_lifecycle.cpp:3536-3553`；`enterscenelogic.go:138-160` |
| `EnterSceneResponse` | SM → A | `error_code`（SM 私有码）、`redirect`（只跨 zone）、回显 `player_id` / `correlation_id`、`owner_epoch_after_rollback`（只在 7 里出现，**只进日志，不作采纳凭证**） | `sm.proto:102-126`；`enterscenelogic.go:130-135` |
| `scene_node.ReleasePlayerRequest` | SM → A（旧节点），gRPC | `player_id`、`target_scene_id`、`target_node_id`（后两项只进日志）；**不带 epoch** | `scene_node_service.proto:39-45` |
| `GateCommand{RoutePlayerEvent}` | SM → gate，Kafka | `session_id`、`target_node_id`、`scene_id`、`player_id`、`home_zone_id`、`owner_epoch`；信封带 `target_gate_id` 与 `target_instance_id`（gate 进程 UUID） | `gate_event.proto:10-22`；`gate_command.proto:9-20`；`enterscenelogic.go:1316-1337` |
| `PlayerEnterGameNodeRequest` | gate → B，节点链路 | `player_id`、`session_id`、`enter_gs_type`（换图时 0）、`scene_id`、`home_zone_id`、`owner_epoch`（gate 原样透传，0 也照发） | `scene.proto:15-23`；`scene_entry_dispatch.cpp:122-131` |
| ExitGame（`ProcessClientPlayerMessage`） | gate → 会话当前绑定的 scene | 断线时发，带 `player_id` 作身份栅栏 | `client_message_processor.cpp:409-435` |

### 1.4 SM 私有码（与 tip 码数轴重叠，只在服务间用；`errors.go:4-70`）

| 码 | 含义 | 出处 |
|---|---|---|
| 1 `ErrNoAvailableNode` | 解析不出场景 / 频道，或场景在预占前消失 | `enterscenelogic.go:461-468`、`:655-659` |
| 3 `ErrUpdateLocation` | 预占或写 location 失败 | `:653`、`:722-723` |
| 5 `ErrInvalidGateID` | gate_id 非法或缺 `gate_instance_id` | `:142-159` |
| 7 `ErrKafkaRoute` | 推路由失败（已回滚） | `:139-140`、`:736-751` |
| 8 `ErrRedis` | 读 location / epoch / 标记失败，或补种失败 | `:283-292`、`:789-792`、`:597-604` |
| 15 / 16 | request_id 去重的「执行中」/「冲突」（scene 不带 request_id，不会遇到） | `:192-271` |
| 17 `ErrSceneReentryBarrier` | 显式 scene_id 指向刚判死的节点（5.5） | `:462-466`、`:1209-1218` |
| **18** `ErrHandoffPending` | 换手门暂拒（无标记 / 标记不是当前代际 / 落点时标记已撤回）。**可重试，什么都没改** | `:786-817`、`:709-714` |
| **19** `ErrOwnerEpochConflict` | 落点 CAS 失败，或补种时键已存在 | `:715-721`、`:605-614` |
| 20 / 21 | 归属 zone 不可用 / 正在合服（3b） | `:539-553` |
| 14 | 历史码，不再发出 | `errors.go:28-32` |

C++ 侧 18 的唯一定义是 `PlayerLifecycleSystem::kSmErrHandoffPending = 18`（`player_lifecycle.h:918-923`）。

### 1.5 客户端可见码与消息号

消息号（`message_id.txt`）：63 EnterScene（C2S，`:64`）、79 NotifyEnterScene（`:80`）、21 ActorCreate（`:22`）、47 ActorListCreate（`:48`）、51 ActorDestroy（`:52`）、
23 SendTipToClient（`:24`）、34 KickPlayer（`:35`）、124 RedirectToGate（`:125`）。

| 码 | 名 | 本协议里的出处 |
|---|---|---|
| 3004 | kEnterSceneServerType | 63 准入第 1 条（`player_scene_handler.cpp:45-52`） |
| 3023 | kEnterSceneFailed | 战斗在途准入；第一跳非 18；同 zone 交接未成；Conclude 的 tip 与 34 的 reason；gate 转发放弃；B 拒建 / 载入失败 / 场景不在 |
| 3014 | kEnterSceneChangingScene | 63 时在途换图 / 交接 / 待撤回标记；同 zone 交接重入 |
| 3005 | kEnterSceneParamError | 三个 id 全 ≤0；镜像派发失败；缺会话快照 |
| 3008 | kEnterSceneYouInCurrentScene | 63 的 scene_id 就是当前场景 |
| 1003 | kServiceUnavailable | 无 SM（同步应答）；第一跳 gRPC 传输失败（推 23） |
| 27003 / 1005 | kAssetFrozen / kInvalidParameter | 冻结期间的写闸（§3.3） |
| 3027 / 13000 | kZoneTravelTargetBusy / kSceneTransferInProgress | 只在跨 zone（5.4），同 zone 不出现 |

### 1.6 正常时序（生产配置 `AllowUnsafeCrossNodeHandoff=false`）

```
Client   Gate                Scene A(源)                         SM                           Redis(zone)          Kafka→Gate    Scene B(目标)
  |--63-->|------------------>|①准入 + RequestSceneChange(corr=c1)
  |<--63应答(无错=已受理)------|
  |       |                   |--EnterScene#1{conf,sid,corr=c1}-->|②读 location{A,E}、epoch E
  |       |                   |                                   | 解析频道(原子预占)→ 落在 B
  |       |                   |                                   | 跨节点 → GET handoff:没有/不是 E → 退还预占
  |       |                   |<----------{18, corr=c1}-----------|   (什么都没改)
  |       |                   |③StartTravelHandoff:摘 InFlight,挂 TravelHandoff + Frozen,打单调冻结点
  |       |                   |  SavePlayerToRedis(Lua CAS 期望 epoch=E) ------------------------>| 写 blob
  |       |                   |<---------------------------------------------- 落地回调 --------|
  |       |                   |④BeginTravelHandoff:requestedAtMs=t
  |       |                   |  SET player:{id}:handoff "E:t" EX 300 ----------------------------->|
  |       |                   |<-------------------------------------------------------- OK ------|
  |       |                   |⑤RequestTravelEnterScene:corr=c2,挂 30s 应答看门狗
  |       |                   |--EnterScene#2{sid,conf,corr=c2}-->|⑥重新观察 location{A,E}、epoch E
  |       |                   |                                   | 重新解析频道(可能与 #1 不同!)
  |       |                   |                                   | GET handoff=="E:t" → 放行;3b 查 home zone
  |       |                   |<..ReleasePlayer(异步goroutine)....|⑦5b:先于落点发出
  |       |                   | (IsHandoffRequested → 忽略)        |⑧Lua:epoch==E 且 handoff=="E:t"
  |       |                   |                                   |   → INCR→E+1;SET location{B,scene,E+1}
  |       |                   |                                   |⑨6b:扣旧场景人数
  |       |                   |                                   |⑩GateCommand{RoutePlayerEvent{…,E+1}} --同步ACK(≤5s)-->
  |       |                   |<----------{0, corr=c2}------------|
  |       |<==========================================RoutePlayerEvent==========================(Kafka)
  |       |⑪覆写 session.{homeZone,ownerEpoch=E+1};比较/转发/提交
  |       |--PlayerEnterGameNode{enter_gs_type=0,scene,E+1}----------------------------------------------------------->|⑫A2′:epoch==E+1?
  |       | 转发成功后会话改指 B                                                                                             |  删 ≤E+1 的标记 → 放行
  |       |                                                                                                                  | AsyncLoad → 建实体 → EnterScene
  |<-79/21---------------------------------------------------------------------------------------------------------------------|(下一拍 AOI → 47)
  |       |                   |⑬应答 c2:0、无 redirect、同 zone → ResolveTravelOutcome(kSucceeded)
  |       |                   |  EVAL judge:DEL 本族("*:t")+ MGET epoch/location ---------------->|
  |       |                   |  epoch=E+1≠E,location→B → kMovedElsewhere → DestroyDeposedPlayer(不存盘)
 旁人<-51---------------------|(DetachFromScene → BeforeLeaveScene → AOI 广播)
```

⑫ 与 ⑬ 互相独立、先后不定；两者都只删「有权删的标记」，A2′ 还要求 epoch 等于 E+1。

| # | 执行方 | 动作 | 围栏 / 幂等 | 出处 |
|---|---|---|---|---|
| ① | A | 63 按序准入：3004 → 战斗在途 3023 → `IsSceneChangeBusy` 3014 → 三个 id 全 ≤0 3005 → 镜像分支（mirror>0 且 scene_id=0）→ scene_id 等于当前 3008 → 缺 `PlayerSessionSnapshotComp` 3005 → 无 SM 1003。拒绝码写进 TLS `TipInfoMessage`，直接写 response 会被覆盖 | 发送侧闸：交接在途 / 冻结 / 待撤回标记 / 在途换图未过 TTL | `player_scene_handler.cpp:25-33`、`:45-149`；`player_lifecycle.cpp:2984-3015` |
| ①′ | A | `RequestSceneChange`：取号（线程内单调、恒非 0）→ `emplace_or_replace` 单槽在途记录 `{sceneId, sceneConfigId, sentAtMs, playerRequested=true, correlationId}` → 经唯一出口 `SendCorrelatedEnterScene` 发出。63 同步应答无错只代表「已受理」 | correlation_id 只对应答，不进 SM 去重指纹 | `player_lifecycle.cpp:3017-3038`、`:373-397`；`player_ownership_comp.h:176-209`；`enterscenelogic.go:100-117` |
| ② | SM | `EnterScene` #1：带 GateId 时校验 Kafka 可用、gate_id、`gate_instance_id` 非空 → 读 location（含原文）与 owner_epoch（**本请求唯一一次观察**）→ 过滤「整 zone 下线」/「死节点」位置（5.5）→ 解析场景（scene_id=0 时原子预占最少的频道）→ `samePlacement` / `samePhysicalNode` / `crossNodeHandoff` 判定（NodeId 只在 zone 内唯一，zone 必须明确相等，zone=0 按跨节点）→ `requireHandoffCommitted`：无标记 → 18，退还预占，计 `handoff_pending_no_marker` | 拒绝发生在任何 location / epoch 写之前；「先看标记、后看旁路」 | `enterscenelogic.go:138-160`、`:278-331`、`:437-468`、`:484-515`、`:786-817` |
| ③ | A | `DispatchEnterSceneReply` 按 correlation_id 分类为 `kSceneChange` → 按值抄下在途组件后摘掉 → `HandleSceneChangeEnterSceneReply` 看到 18 → `StartTravelHandoff(本 zone, target.sceneId, target.sceneConfigId)` | 应答 player_id=0 时 no-op；号为 0（旧版 SM）退回按 player_id，计 `reply_uncorrelated` | `player_lifecycle.cpp:3040-3101`、`:3921-3955`；`player_lifecycle.h:705-791` |
| ③′ | A | `StartTravelHandoff` 先校验，任一不过**不改状态**：targetZone 为 0；实体有效；未在退出；未在交接 / 冻结（同 zone 回 3014）；不在战斗（3023）；gate 会话活着；有可达 SM。通过后：摘在途组件 → 挂 `PlayerTravelHandoffComp{targetZoneId, sceneId, sceneConfigId, requestedAtMs=0, frozenAtSteady=now}` 与 `PlayerFrozenComp` → `started`+1 → 挂 30 s 汇总定时器与 1 s 上限扫描 → `SavePlayerToRedis`。快路径判「盘上已是同一份」但同 key 还有未落地存盘时强制真写一次（`handoff_fastpath_forced`），保证不变量 I1；存盘在途时挂 30 s 存盘看门狗；没写盘时直接 `BeginTravelHandoff` | 先冻结、后存盘 | `player_lifecycle.cpp:2818-2955`；`player_frozen_comp.h:5-28` |
| ④ | A | 存盘落地 → `HandlePlayerAsyncSaved`：先判「退出优先」（§2.6）；否则 `BeginTravelHandoff`：已发起则返回 → 冻结 ≥35 s 则 Abort → epoch 为 0 则 Abort → zone Redis 未连接则 Abort → **先置 `requestedAtMs=t` 再发** `SET handoff "E:t" EX 300` → 命令没进缓冲则 Abort → 置 `markEpoch=E` | 从这一刻起 `SavePlayerToRedisImpl` 直接跳过写盘 | `player_lifecycle.cpp:1325-1383`、`:3335-3452`、`:2445-2455` |
| ⑤ | A | SET 回调 OK 且代际仍是 t → `RequestTravelEnterScene`：冻结 ≥35 s / 无会话 / 无 SM 走 Conclude（§2.4）；否则构造请求（`scene_id=travel.sceneId`，只按 conf 时为 0；`gate_zone_id = zone_id = 本 zone`；**不设 request_id**）→ 取号写入 `enterSceneCorrelationId` → 发送 → 挂 30 s 应答看门狗（证据 kNoReply） | 每代交接只发一次，看门狗只核实不重发 | `player_lifecycle.cpp:3399-3440`、`:3481-3571` |
| ⑥ | SM | `EnterScene` #2：重新观察 → **重新解析频道**（scene_id=0 时可能挑到与 #1 不同的频道，甚至挑回 A，§1.7 V1）→ 仍跨节点则 `checkHandoffCommitted` 判 `marker.Epoch == observedEpoch`，成立则 `committed`，标记原文记进 `guard.requiredMarker`，`mint=true` → 3b：带 GateId 时查 home zone（失败 20 / 21，退还预占） | 预检与落点锚在同一个 observedEpoch 上 | `enterscenelogic.go:282-292`、`:503-553`、`:620-624`；`owner_epoch.go:555-572` |
| ⑦ | SM → A | 5b：`currentLoc.NodeId != ""` 且不是同物理节点时 `dispatchReleasePlayer`（safego goroutine，`context.Background`）。**在落点之前发出**；A 收到时若 `IsHandoffRequested` 为真直接忽略，也**不得**在这里取证（会撤回即将发生的放行） | 失败只计指标 | `enterscenelogic.go:672-699`；`scene_node_client.go:324-366`；`scene_node_service.cpp:127-184` |
| ⑧ | SM | 6：`placePlayerLocation` 执行 `luaMintEpochAndSetLocation`：`cur==E`（缺键按 "0"）→ requiredMarker 非空且 handoff≠原文返回 −1（翻成 18 withdrawn）→ 否则 INCR 并 SET location（`OwnerEpoch=E+1`）。cur≠E 先做重放识别，不命中回 0（19） | INCR 与 SET 同一原子域 | `owner_epoch.go:54-121`；`changesceneutil.go:106-187` |
| ⑧′ | SM | 重放识别：go-redis（MaxRetries=3）原样重发 EVAL 而首发已执行时，`cur==E+1`、location 原文等于本请求写的、标记仍等于原文 → 返回 −cur，计 `enter_scene_mint_replay_recognized_total`。只对凭标记的铸造做 | — | `owner_epoch.go:68-101`；`changesceneutil.go:163-185` |
| ⑨ | SM | 6b：落点成功之后扣旧场景人数 | — | `enterscenelogic.go:726-730` |
| ⑩ | SM | 7：`routePlayerToGate`，同步 `WriteMessages`（不可取消的 ctx，MaxAttempts=1 + WriteTimeout）。**broker ACK 是事务提交点**；失败回滚并回 7（§2.7） | epoch 随事件走，节点不得自己读 Redis（两次改派挨近时会读到后一次的值，造成双主） | `enterscenelogic.go:732-766`、`:1305-1356` |
| ⑪ | gate | `RoutePlayerEventHandler`：会话不在 → DEBUG 并 return（断线竞态）→ 补 playerId → 无条件覆写 `homeZoneId` / `ownerEpoch` → `OnRouteDecision` 以最近一次路由整体替换欠账 → `AttemptPendingSceneEntry`：玩家栅栏 → 解析 node_id → 比较节点 → `ApplyRoute`（scene_id 或节点变了以 `enter_gs_type=0` 转发）→ **转发成功后**才提交 `sceneId` 与节点指向 | 重复事件不转发；会话指向只在转发成功后改写 | `gate_event_handler.cpp:56-105`；`scene_route_helper.h:47-113`、`:264-317`；`scene_entry_dispatch.cpp:110-138` |
| ⑫ | B | `PlayerEnterGameNode`：`RemovePlayerSessionSilently` → ctx（`ownerEpoch=E+1`）→ 本节点没有该玩家 → 预登记 SessionMap → 写 PendingEnterMap → **A2′** `BeginInheritedMarkClear`（与 GET 同一条 FIFO 连接，EVAL 排在 GET 前）→ `AsyncLoad` → `DecideInheritGate`：确认 → 建实体；在途 → 暂存；不符 / 耗尽 → 拒建（§2.9）→ `InitPlayerFromAllData` → `EnterScene`：0.5 步按 max 缓存 epoch → 绑定会话 → `HandleEnterScene` 发 79 与 21 → 3.1 步摘在途组件 → 下一拍 AOI 发 47 | A2′ 与回滚互斥（§1.9.4） | `scene_handler.cpp:159-264`；`player_lifecycle.cpp:1253-1323`、`:1550-1735`；`player_scene.cpp:41-120` |
| ⑬ | A | c2 应答（`kTravelHandoff`）→ `HandleTravelEnterSceneReply`：无错、无 redirect、同 zone → `ResolveTravelOutcome(kSucceeded)` → 取证 EVAL → `JudgeTravelOutcomeReply`：epoch=E+1≠E、location 不指回本节点 → `kMovedElsewhere` → `DestroyDeposedPlayer(routine)`：作废疏散票据 → 摘 TravelHandoff / Frozen → `DetachFromScene`（BeforeLeaveScene 触发 AOI 51）→ 摘会话 → 销毁实体。**不存盘** | 取证先删本族标记再读数；不读应答回显的 epoch | `player_lifecycle.cpp:3964-4004`、`:3610-3686`、`:3688-3868`、`:2664-2700`、`:1826-1857`；`aoi.cpp:182`、`:244-263` |

### 1.7 变体

- **V1：#2 重新解析后挑回了 A（同物理节点）**。SM：`samePhysicalNode` 成立 → 不过换手门、不发 ReleasePlayer、`mint=false`，走 `luaSetLocationIfEpoch`（epoch 仍 E）→
  推路由到 A（`enterscenelogic.go:492-496`、`:620-621`；`owner_epoch.go:123-139`）。A 收到 `PlayerEnterGameNode`：实体在、路由 epoch 等于缓存值 → `EnterScene` →
  `HandleEnterScene`（实例变了发 79 / 21，同一实例幂等早退、不发 79）→ **3.2 步**：交接已发起、同 zone、路由 epoch 等于缓存值 → `ResolveTravelOutcome(kSucceeded)` →
  B4 → `AbortTravelHandoff(notifyFailure=false)` 静默解冻，计 `resolved_in_place`（`player_lifecycle.cpp:1677-1704`；`scene_handler.cpp:195-216`）。随后到达的 c2 应答按
  `kNoWaiter` 当 no-op（`player_lifecycle.cpp:3137-3146`）。
- **V2：显式 scene_id**（加入已有镜像或副本）：`travel.sceneId` 原样重发，SM 不重挑（`player_lifecycle.cpp:3538-3543`）；3.2 步不适用（`:1685-1687`）。
- **V3：预检与铸造之间源端撤回了标记**：落点 Lua 回 −1，SM 回 18，计 `handoff_pending_withdrawn`（`enterscenelogic.go:709-714`）；源端此时已解冻或已退出，应答走 `kNoWaiter` 或实体不在。

### 1.8 dev 旁路（`AllowUnsafeCrossNodeHandoff=true`，`sm.yaml:46-61`；生产缺省 false，`config.go:16-25`）

- **第一跳就放行**：`requireHandoffCommitted` 没读到标记但旁路开着，返回不铸造的 grant（`enterscenelogic.go:796-800`）→ 5b 先发 ReleasePlayer → 落点用 `luaSetLocationIfEpoch`
  （新旧节点短暂同持 E）→ 推路由 → 回 0。
- **A 走普通退出**：没有交接在途 → `HandleExitGameNode(kReleasedByTransfer)`：存盘、销毁（`scene_node_service.cpp:178-182`）；kReleasedByTransfer 不写 A1′（`exit_release_mark.h:44-47`）；
  c1 应答到时实体已不在，INFO 后 no-op（`player_lifecycle.cpp:3068-3075`）。
- **B 可能读到旧档**（「unsafe」的含义，`enterscenelogic.go:682-693`）；不铸造是因为铸了 A 的释放存盘必被 CAS 拒、每次换图确定性回档（`:780-785`）。
- **推导**：ReleasePlayer 先于落点，所以落点若随后失败（19 或 Kafka 失败），A 已退出而 gate 会话仍绑在 A，客户端变哑。只在 dev。
- C++ 对应开关 `SCENE_DEV_UNSAFE_CROSS_NODE_HANDOFF` 默认关，须与 SM 同步打开，影响 A1′ 的粘性压制（`exit_release_mark.h:241-249`；`player_lifecycle.cpp:717-721`、`:1919-1923`）。

### 1.9 围栏与不变量

#### 1.9.1 epoch 推进规则（SM）

- **唯一推进口**：只在 `EnterScene`、只在「持有者真的换了」时推进：首次落点、过了换手门的跨节点交接、跨 zone 放行、等待落点的第二条腿（`owner_epoch.go:32-46`）。
- 同节点换图、同落点重连、dev 旁路**不铸造**：否则持有节点要等 Kafka 绕一圈才知道新值，窗口内它的存盘被 CAS 拒、合法持有者被当废黜者销毁（`enterscenelogic.go:555-559`）。
  例外：同节点 epoch 与 location 都记为 0 的存量号铸造（`sameNodeZeroMint`）；键被单独淘汰时按 location 的值补种（`:561-621`）。
- **单调回滚**：路由失败的回滚也是 INCR（E+1 → E+2），永不退回旧值（`owner_epoch.go:141-206`）。

#### 1.9.2 节点侧写围栏

- Redis 存盘 CAS：缓存 epoch≠0 时 Lua 原子比较 `owner_epoch == 期望`（`player_lifecycle.cpp:2542-2556`）；被拒值就是当前缓存值 → 判定已被废黜，计 `stale_owner_write_rejected`，
  `DestroyDeposedPlayer`；被拒值≠缓存值 → 旧代际的在途写，用当前 epoch 重存一次（`:2618-2662`）；缓存为 0 走无 guard 的旧 `Save`，计 `owner_epoch_unknown`（`:2557-2561`）。
- DBTask 每张子表带 `owner_epoch`，db 服务落库前比较（`:2589-2591`）。
- 交接发起后禁写：`requestedAtMs≠0` 之后 `SavePlayerToRedisImpl` 直接返回 false（`:2445-2455`）。
- epoch 缓存只取 max，只有两个写入口：路由 ctx（`:1594-1603`）与取证 B5 采纳（`:3799`），后者是「节点不读 Redis 取 epoch」的唯一例外（`player_ownership_comp.h:15-18`；`scene_handler.cpp:180-186`）。

#### 1.9.3 换手门两层

1. 预检：`handoff.epoch == observedEpoch`；缺失、写坏、读失败都不放行（`owner_epoch.go:534-572`）。
2. 落点 Lua 复核：标记必须与预检原文一字不差（`owner_epoch.go:95-101`、`:115-117`）。有这一层，源端「原子删本族标记、再读 epoch」之后不可能再有凭这份标记的放行，
   于是「epoch 没变」严格等价于「没被放行」。

#### 1.9.4 A2′ 与回滚的令牌互斥

两段都是同一 Redis 上的原子 Lua，只能一先一后（`owner_epoch.go:176-181`；`exit_release_mark.h:38-40`）：A2′ 在先 → 标记已删，之后的回滚回 3（marker_gone）、保留落点、归属留在 B；
回滚在先 → epoch 已是 E+2，A2′ 回 −1，B 拒建，发不出 DBTask(E+1)。

#### 1.9.5 源端解冻骨架（I0 / I1 / I3）与第一刀

- **I0**：SET 没发出就不可能被放行；**I1**：SET 发出时 Redis ⊇ 冻结那一刻的内存；**I3**：不在退出中的实体 `IsCrossZoneFrozen` 为 false 的任何时刻，本节点一定仍是属主
  （`player_lifecycle.h:880-895`）。能摘冻结又保留实体的出口只有 A1（SET 没发出）、B4（epoch 未变）、B5（本次回执且交叉校验成立），其余摘冻结的路径都同时销毁实体
  （`player_lifecycle.h:886-894`；`travel.md:1534-1535`）。
- 第一刀 `IsHandoffMarkSent(requestedAtMs) = requestedAtMs≠0`，不看 `markEpoch`：漏写只会落到「销毁」一侧（`travel_freeze_cap.h:160-168`）。

### 1.10 幂等与去重

| 层 | 机制 | 出处 |
|---|---|---|
| SM 请求级去重 | `enter_scene:dedup:{player}:{request_id}`，pending → done（TTL 60 s），指纹排除 correlation_id。**scene 一律不带 request_id**，否则 60 s 去重会吞掉玩家短时间内的第二次换图 | `enterscenelogic.go:35-39`、`:162-271`；`player_lifecycle.cpp:3552-3553` |
| SM 应答对号 | correlation_id 原样回显 | `enterscenelogic.go:121-135` |
| SM 铸造 / 回滚的 go-redis 重发 | 铸造：重放识别（⑧′）；bump 回滚返回 2；keep 回滚永不返回 2 | `owner_epoch.go:68-91`、`:198-206` |
| SM 落点 | CAS 锚定 observedEpoch，并发者回 19、不发路由 | `changesceneutil.go:106-124` |
| SM LeaveScene | 比对原文后 DEL、同段扣人数；deleted / already_gone / superseded；读失败回错误让调用方保留认领重试 | `leavescenelogic.go:15-159` |
| A：交接代际 | `requestedAtMs`；SET 回调、看门狗、取证回调都按代际比对 | `player_lifecycle.cpp:3347-3351`、`:3429-3439`、`:3588-3592`、`:3699-3703` |
| A：应答对号 | `enter_scene_reply::Classify`；交接的号为 0（还没发）时任何应答都不算交接证据 | `player_lifecycle.h:740-777` |
| A：收口 | Abort / Conclude / DestroyDeposed 对已收尾的情形幂等 | `player_lifecycle.cpp:4318-4322`、`:4221-4230`、`:2666-2672` |
| A：ReleasePlayer | 本节点没有该玩家幂等 OK；交接已发起忽略 | `scene_node_service.cpp:131-176` |
| gate | `ApplyRoute`：重复事件不转发；欠账以最近一次为准；只有转发成功才提交 | `scene_route_helper.h:68-113`；`scene_entry_dispatch.h:47-49` |
| B | PendingEnterMap 以最新上下文覆盖；A2′ 只认针对本 ctx.ownerEpoch 的那一次 | `scene_handler.cpp:240-256`；`exit_release_mark.h:416-439` |
| 撤回 | 按原文条件删（`kLuaDelIfEqual`），同 (player, mark) 不重复登记，重连时强制重发 | `handoff_mark_withdraw.h:48-55`、`:72-84` |

### 1.11 `player:{id}:location`（sm-player-location）

- **写**：只有 SM `EnterScene`，三种 Lua（铸造 / 不铸造 CAS / 回滚）；换图整条覆写、不删（`changesceneutil.go:63-69`）。`node_id` 为空且 epoch≠0 = 跨 zone 等待落点（5.4，`storage.proto:24-26`）。
- **删**：只有 `LeaveScene`，由 player_locator 在断线租约到期 / 主动登出时调（`leavescenelogic.go:99-159`；`leasemonitor.go:283-315`）；只比 scene_id 再比原文；读失败回错误，不能当「没有位置」。
- **读**（只读）：match 排队判在场、gather 找 PrepareBattle 目标节点（`joinqueuelogic.go:143-158`；`gather.go:402-416`；`playercontract.go:47-50`、`:97-110`）；team 开战预检
  （`team/service.go:440-450`）；login 职业回填（`player_class_backfill.go:94`）；C++ scene 只在取证脚本里读（`player_lifecycle.cpp:3734-3754`）。
- **人数**：#1 被 18 拒时退还预占（`enterscenelogic.go:509-511`）；落点成功后扣旧场景（`:726-730`）；回滚时成对恢复（`:1104-1114`）。

### 1.12 时限与常量（全表）

| 常量 | 值 | 出处 | 作用 |
|---|---|---|---|
| SM zrpc 服务端超时 | 8000 ms | `sm.yaml:3-14` | 一次 EnterScene 的服务端上限（≥ 归属查询 1500 + Kafka 5000 + 1500） |
| C++ → SM gRPC deadline | 10000 ms | `travel_freeze_cap.h:54-59` | 到期调失败处理器 |
| 在途换图 TTL | SM deadline + 1000 ms | `player_lifecycle.cpp:177-192` | `IsSceneChangeBusy` 单槽过期 |
| `KafkaWriteTimeoutSeconds` | 5 s | `sm.yaml:40-44` | 推路由的有界终态；也是「铸造 → 路由 / 回滚」窗口 |
| ReleasePlayer | 首发 1 s deadline；失败后间隔 1 s、3 s 各重试一次（总长约 7 s） | `scene_node_client.go:313-364` | 发给旧节点的异步通知 |
| `kSaveBudget` | 30 s | `travel_freeze_cap.h:50-52` | 存盘阶段看门狗 |
| `kReplyBudget` | 30 s | `:54-64` | 应答看门狗；下限必须远大于 5 s 路由窗口 |
| `kVerifyMargin` | 5 s | `:66-67` | 一次取证往返余量 |
| `kFreezeCap` | 70 s | `:69-71` | 冻结硬上限（单调时钟） |
| `kDispatchWindow` | 70 − 30 − 5 = 35 s | `:73-75` | 晚发闸 |
| `kSweepInterval` | 1 s | `:77-78` | 上限扫描周期 |
| `kEarlyFireTolerance` | 1 s | `:80-84` | muduo 按墙钟提前唤醒的容差 |
| `kClientAcceptedHandoffBudget` | 75 s | `:86-98` | 镜像客户端 `CityTravelRequest.AcceptedHandoffBudgetSeconds` |
| handoff 标记 TTL | 300 s | `player_ownership_comp.h:72-74`；`ownerepoch.go:43-46` | — |
| 撤回表 | 重试间隔 5 s；截止 TTL + 5 s；上限 4096 条 | `handoff_mark_withdraw.h:41-47` | — |
| A2′ | 最多 4 次；从首发起 5000 ms；退避 250 / 500 / 1000 ms | `exit_release_mark.h:441-457` | — |
| gate 进场转发 | 总预算 20 s；找不到节点 3 s；退避 250 ms → 2 s；最多 16 次；扫描 0.25 s；放弃后 1 s 强关 | `scene_route_helper.h:22-41`；`scene_entry_dispatch.h:35-37` | — |
| 断线租约 | 30 s | `entergamelogic.go:625-633`；`player_lifecycle.cpp:1401` | 期间 location 保留 |
| `[TravelHandoff]` 汇总行 | 每 30 s，有变化才打 | `player_lifecycle.cpp:197-198`、`:244-299` | — |

时序不等式由 `static_assert` 守：`kSaveBudget ≤ kDispatchWindow`；`kDispatchWindow + kReplyBudget + kVerifyMargin ≤ kFreezeCap`；`kEarlyFireTolerance ≤ kVerifyMargin`；
`kFreezeCap + kSweepInterval < kClientAcceptedHandoffBudget`（`travel_freeze_cap.h:92-99`）。

---

## 2 基线的失败与回滚

### 2.1 63 的同步拒绝

见 §1.6 ①。全部同步回码、不改状态。

### 2.2 第一跳（#1）的非 18 结果

| A 收到的结果 | 处理 | 客户端 | 出处 |
|---|---|---|---|
| `error_code` 0（同节点换图） | 已摘在途组件，无事可做 | 由路由驱动的 79 / 21 | `player_lifecycle.cpp:3956-3961` |
| 非 0 非 18，玩家发起 | 补推 tip，否则客户端一直等不来的 79 | 23 `{3023}` | `:3956-3961` |
| 队伍跟随（`playerRequested=false`） | 只记日志，留原场景（DV-6） | 无 | `:3925-3937` |
| gRPC 传输失败（结果未知） | 摘在途组件；玩家请求推 1003（不断言失败，路由可能随后到达）；跟随只记日志 | 23 `{1003}` | `:3195-3216`；`sm_reply.cpp:67-73` |
| 18 但 `StartTravelHandoff` 起不来 | 推返回的 tip | 23 `{3014}` 或 `{3023}` | `:3939-3955`、`:2822-2874` |
| 号对不上 / 迟到 | 丢弃；可疑的计 `reply_unmatched` | 无 | `:3115-3135`；`player_lifecycle.h:779-790` |

### 2.3 存盘与 SET 阶段（标记**没发出**，可以安全解冻）

| 触发 | 处置 | 出处 |
|---|---|---|
| 存盘 30 s 没落地 | `saveWatchdogFired` → `AbortTravelHandoff` | `player_lifecycle.cpp:2957-2982` |
| 冻结 ≥35 s 才落地（晚发闸） | `dispatchWindowClosed` → Abort | `:3353-3369` |
| 缓存 epoch 为 0 / zone Redis 未连接 / 命令没进缓冲 | Abort | `:3376-3390`、`:3443-3447` |
| SET 返回 ERROR 且代际相同 | `markEpoch=0`（不登记撤回）→ Abort；代际不符只记日志 | `:3454-3479` |
| SET 回调空 reply（连接断，**结果未知**） | 不就地解冻，走 `ResolveTravelOutcome(kMarkWriteUnknown)` 取证；这一证据**永不踢线** | `:3408-3428`；`player_lifecycle.h:565-568` |
| 冻结 70 s 且标记没发出 | `ExpireTravelFreeze` 判 kUnfreeze → Abort（正常应恒不出现） | `:4173-4203` |

`AbortTravelHandoff`（`player_lifecycle.cpp:4311-4373`）：先抄后摘交接组件 → 计 `aborted`（或 `resolved_in_place`）→ 摘 Frozen → `markEpoch≠0` 时按原文登记撤回
（`WithdrawHandoffMark` 先登记后发条件删；确认前 `IsSceneChangeBusy` 为真，`handoff_mark_withdraw.h:20-35`；`player_lifecycle.cpp:4029-4051`）→ `notifyFailure` 时推 tip：同 zone 3023，跨 zone 3027。

### 2.4 SET 已 OK、但 #2 发不出（只能销毁）

- 触发：enter_scene 阶段冻结已 ≥35 s；没有 gate 会话；没有 SM（`player_lifecycle.cpp:3494-3534`）。
- 统一收口 `ConcludeHandoffAfterMarkSent`（`:4218-4309`）：前置被破坏（标记其实没发出）退回 Abort；还有未落地存盘时**推迟销毁**、每次交接只告警一次（`destroy_deferred_unsettled_save`，
  冻结上限唯一允许的例外）；否则会话活着、实体不在退出中时先发 tip 与 `34{reason=同一 tip}`（同 zone 都是 3023），再 `DestroyDeposedPlayer`；**不发任何 Redis 命令、不撤回标记**
  （销毁之后这份标记与 A1′ 同义）。
- 同 zone 也踢线：此刻 epoch 状态未知，不踢就是哑连接（`player_lifecycle.h:1238-1240`）。

### 2.5 #2 的应答、传输失败与看门狗

| 证据 | 来源 | 处置 |
|---|---|---|
| `error_code≠0` | SM 拒绝 | 不能直接解冻（失败应答证明不了没铸造过），走 `ResolveTravelOutcome(kFailed)`（`player_lifecycle.cpp:3981-3992`） |
| 0、无 redirect、同 zone | 正常放行，或重发后挑回本节点 | `kSucceeded`（`:3993-4004`） |
| 0、无 redirect、跨 zone | 协议异常 | `kAnomalous`（`:4006-4012`） |
| 带 redirect | 跨 zone 放行（5.4） | 不取证，直接销毁（`:4015-4026`） |
| gRPC 传输失败 | 结果未知 | **只记日志**，交给看门狗（`:3185-3193`） |
| 30 s 应答看门狗 | 首次挂载证据 kNoReply，**从不取消**；代际不符 no-op | `ResolveTravelOutcome(kNoReply)`（`:3573-3608`）；组件上记的证据优先（`player_lifecycle.h:548-551`；`player_lifecycle.cpp:3634-3642`、`:3704-3707`） |

`ResolveTravelOutcome`（`player_lifecycle.cpp:3610-3686`）：实体或代际不符 no-op；标记没发出 LOG_ERROR 返回 → 非 kNoReply 证据记到组件 → Redis 不可用 / 命令发不出 / 脚本 ERROR /
应答形状不对：**保持冻结、带原证据重挂看门狗**（`verify_rearmed`），终局交给 70 s 上限 → 否则 `EVAL kLuaJudgeTravelOutcome 3 handoff owner_epoch location t`。

取证判定表（`JudgeTravelOutcomeReply`，`player_lifecycle.cpp:3757-3918`；`player_lifecycle.h:614-682`）：

| 行 | 条件 | 处置 | 客户端 |
|---|---|---|---|
| B4 kUnchanged | redisEpoch == 缓存值 | `markEpoch=0` → Abort；证据 kSucceeded 时静默，否则推 tip | 静默，或 23 `{3023}` |
| B5 kRolledBackToSelf | 回执等于本次原文 `"{markEpoch}:{t}"`、markEpoch==缓存值、redisEpoch==markEpoch+2==location.epoch、location 指回本节点本 zone | 采纳 E+2（取 max）→ `markEpoch=0` → 计 `rolled_back_adopted` → Abort → **强制存盘一次** | 23 `{3023}` |
| B6 kReceiptAnomaly | 回执是本次的，但交叉校验不成立 | Conclude（`travel_receipt_anomaly`） | 23 `{3023}` + 34 |
| B7 kReturnedToSelf | epoch 变了、没有本次回执、location 却指回本节点 | 计 `returned_after_grant`，按「已放行」销毁，**不踢** | 无 |
| B8 / B9 | location 在别处 / 缺失或损坏 | 「已放行」：kSucceeded 时 routine 销毁；其它证据计 `granted_without_reply` 后销毁；踢线只对**跨 zone**且 location 是本次等待落点成立（`ShouldResetClientOnGrant`），同 zone **永不踢** | 同 zone：会话已改绑 B 则正常；路由没送达则哑连接 |

### 2.6 交接途中玩家退出（退出优先）

- **存盘阶段**：`HandlePlayerAsyncSaved` 看到 `UnregisterPlayer` → 摘交接与冻结组件 → 标记已写时按原文撤回 → 置 `exitIntent->travelHandoffMarkIssued`，让 A1′ 不写（`player_lifecycle.cpp:1342-1371`）。
- **标记已写之后**：`HandleExitGameNode` 里 `SavePlayerToRedis` 返回 false → 没有未落地存盘时 `FinishExitAfterPersist` 内联收尾 →「exit wins」→ 撤回 → A1′ 压制 → 摘会话、销毁
  （`:1991-2029`、`:2063-2135`）。撤回删不到 SM 转写出来的 `E+2:t`，无害且正确；**禁止**改成无条件 DEL（`:2101-2105`）。
- SM 若已放行：location 指向 B，c2 应答到时实体已不在，no-op（`:3068-3075`）；后续见 §2.11 R1。

### 2.7 SM 的路由推送失败回滚（7 号码）

- 入口：`rollbackEnterSceneAfterRouteFailure` → `planRouteRollback` → `luaRollbackPlayerPlacement`（`enterscenelogic.go:734-751`、`:1099-1115`；`owner_epoch.go:345-385`、`:208-241`）。
- **bump**（铸造过的落点）：location 退回旧值，克隆写入 `OwnerEpoch=E+2`、`RollbackReceipt="E:t"`、zone 回填；epoch INCR 到 E+2；标记转写为 `"E+2:t"`（后缀保持原文）。**前提是所凭的原标记原样还在**。
- **keep**（没铸造的落点）：只退 location。
- 返回值（`classifyRollbackReply`，`owner_epoch.go:408-427`）：`-after` rolled_back（恢复人数）；1 keep 已回滚；2 already_rolled_back（重发，恢复人数）；3 marker_gone（什么都不动、保留落点）；
  0 superseded；其余 redis_error / plan_error。只有 restored 时退还人数（`enterscenelogic.go:1104-1114`）。
- 应答 `owner_epoch_after_rollback` 只在 bump 确认生效时非 0，**只进日志**（`sm.proto:111-119`；`player_lifecycle.cpp:3983-3990`）。
- 同 zone 下 redis_error 的终态：源端取证读到 E+1、location 指向 B → 销毁不踢 →「玩家挂在哑连接上」，已登记为已知限制（`owner_epoch.go:461-475`）。

### 2.8 gate 侧转发失败

- `kNodeNotFound`、`kNoRpcClient`、`kNotConnected`、`kHandshakePending` 在预算内重试；`kInvalidRoute` 等直接放弃（`scene_route_helper.h:131-145`、`:191-217`）。
- 放弃：推 23 `{3023}` → 推 34 `{3023}` → shutdown → 1 s 后强关（`scene_entry_dispatch.cpp:156-200`）；放弃期间会话指向不变，断线后的 ExitGame 落到旧节点 A（`scene_route_helper.h:259-263`）。

### 2.9 目标节点 B 的失败

| 情形 | 处置 | 客户端 |
|---|---|---|
| A2′ 返回 −1（epoch≠N），或重发耗尽 / 截止 | `RefuseInheritedEntry`：擦掉 pending 与 SessionMap，`AbandonInheritClear`（必要时补写标记，M7） | 23 `{3023}`（经 pending session）；会话仍绑 B，B 上没有实体（`player_lifecycle.cpp:961-984`、`:1279-1299`；`exit_release_mark.h:377-394`） |
| 载入时 Redis 出错 | 同上 | 23 `{3023}`（`:1200-1219`） |
| 载入期间会话被取消 | 跳过建实体，Abandon | — |
| scene_id 不在本节点 | 不置登录态、不触发登录事件 | 23 `{3023}`（`:1641-1666`） |

### 2.10 冻结硬上限

`EnforceTravelFreezeCaps` 每 1 s 扫一次，**含退出中的实体**，先收集后处置（`player_lifecycle.cpp:4144-4171`）；`DecideFreezeCap`：标记没发出 → 解冻（Abort）；已发出 → Conclude
（tip + 34 + 不存盘销毁）（`travel_freeze_cap.h:170-178`）；冻结起点没打点时就地补记并计 `freeze_unstamped`（`player_lifecycle.cpp:630-648`）。

### 2.11 竞态

| # | 竞态 | 基线怎么处理 / 后果 | 出处 |
|---|---|---|---|
| R1 | **交接已放行、路由送达前客户端断线（丢人，推导）** | gate 会话已删，`RoutePlayer` 被丢（`gate_event_handler.cpp:62-74`）→ B 不载入；A 收到 ExitGame，exit wins：撤回标记、A1′ 被压制、销毁；location 停在 {B, E+1}、B 上没有实体。租约内重登 `ZoneId=0, SceneId=0`：SM 挑到 B 时同物理节点能载入；挑到别处是跨节点、又没有 E+1 的标记 → 18 → login 推 23 `{3023}`（`entergamelogic.go:664-667`、`:753-765`）。持续到 30 s 租约到期 LeaveScene 删 location（`leasemonitor.go:283-315`） | 推导；无用例 |
| R2 | 双持有 | 三层挡：标记等于观察 epoch；落点 Lua 复核标记；取证「先删本族、后读数」。源端只有 B4 / B5 能解冻；回滚单调 bump 且与 A2′ 互斥 | §1.9 |
| R3 | ReleasePlayer 先于铸造到达 | 交接已发起则忽略；**不在这里取证** | `scene_node_service.cpp:138-176` |
| R4 | 采纳 E+2 之后才到的迟到 ReleasePlayer | `IsHandoffRequested` 已 false，按 kReleasedByTransfer 退出，location 仍指本节点，会话变哑；根治要让 ReleasePlayer 带预期 epoch（改 proto），已登记为残余 | `scene_node_service.cpp:163-167`；`travel.md:1563-1569` |
| R5 | 路由先于应答到达且挑回本节点 | 3.2 步取证后静默解冻；随后的应答走 kNoWaiter | `player_lifecycle.cpp:1677-1704` |
| R6 | 应答丢失（SM 在路由 ACK 后重启），玩家在别处玩过后又被派回 A | `DiscardStaleHandoffEntity`：路由 epoch 大于缓存值时销毁旧实体并重载；`DiscardDeposedEntityOnReentry`：相差 ≥2 的旧实体同样丢弃 | `player_lifecycle.cpp:3253-3333`；`player_exit_intent.h:212-215`；`scene_handler.cpp:188-210` |
| R7 | 应答丢失、路由已送达 B | A 冻结着留在原场景直到 30 s 看门狗，取证后判已放行，销毁不踢；旁人看到一个不动的分身。**不得**改成「收到 ReleasePlayer 后读一次 epoch 就销毁」（会读到随后被回滚的值） | `scene_node_service.cpp:150-162`；`travel_freeze_cap.h:60-64` |
| R8 | Kafka「报错但其实已投递」 | A2′ 先执行 → 回滚 marker_gone、归属在 B；回滚先执行 → A2′ −1、B 拒建、A 按 B5 采纳 E+2 | §1.9.4；`owner_epoch.go:449-460` |
| R9 | 交接期间被顶号（ReplaceLogin，`ZoneId=0`） | login 的请求凭源端标记 "E:t" 过门、铸 E+1；A 自己的 #2 随后回 18，取证读到 epoch 已变，销毁（同 zone 不踢；旧会话由 login 踢）；新会话恰好落回 A（同物理节点、不铸造）则 3.2 步静默解冻（推导组合） | `entergamelogic.go:664-667`；`player_lifecycle.h:571-574` |
| R10 | 看门狗落进 SM「铸造 → 路由 / 回滚」的 5 s 窗口 | `kReplyBudget` 30 s ≫ 5 s；muduo 提前唤醒按单调时钟重挂；容差 1 s ≤ `kVerifyMargin` | `travel_freeze_cap.h:60-64`、`:80-96`；`player_lifecycle.cpp:307-333` |
| R11 | 撤回标记失败（Redis 不通） | 进撤回表重试，期间 `IsSceneChangeBusy` 为真；但不经本节点的落点（断线后重登到别的节点）拦不住，只靠 300 s TTL，已登记为残余 | `handoff_mark_withdraw.h:24-29` |
| R12 | 收到 18 前刚进入备战 | `StartTravelHandoff` 重查战斗，回 3023 | `player_lifecycle.cpp:2854-2860` |
| R13 | 单槽在途记录被覆盖 | 发送侧闸 `IsSceneChangeBusy` + TTL；过 TTL 才到的应答按号丢弃 | `player_ownership_comp.h:176-193`；`player_lifecycle.cpp:177-192` |
| R14 | 同一 scene_id 的频道被迁到别的节点 | gate 按「节点变化」而不只按 scene_id 判断，仍会转发 | `scene_route_helper.h:76-85` |
| R15 | 旧版 SM 不回显 correlation_id | 退回按 player_id 对应答，计 `reply_uncorrelated`，每线程只 WARN 一次 | `player_lifecycle.cpp:3053-3066` |

### 2.12 基线的验证状态与 robot 覆盖

- **Go**：`owner_epoch_test.go`（`TestEnterScene_CrossNodeWithStaleMarkerIsRejectedAsHandoffPending` :377、`…CurrentMarkerIsAllowedAndMintsNextEpoch` :413、
  `…RouteFailureAfterHandoffBumpsEpochAndLeavesReceipt` :617、`…KeepsPlacementWhenMarkerAlreadyConsumed` :692、`TestMintIsRefusedOnceTheSourceWithdrewItsHandoffMarker` :1245、
  `TestCheckHandoffCommitted` :2380、`TestPlanRouteRollback` :225、`TestClassifyRollbackReply` :348、`TestEnterScene_SameNodeSceneSwitchDoesNotMintEpoch` :1020）；
  `logic_test.go:608`（`TestEnterScene_CrossNodeRejectedWithoutSideEffectsAndRetryStaysRejected`）；跨语言金样 `owner_epoch_crosslang_test.go:124`、`:212` 逐字复制了取证脚本与 A2′ 脚本。
  2026-09-29 Go 全过（`PROGRESS.md:5999-6003`）。
- **C++**：`cpp/tests/cross_zone_test/cross_zone_test.cpp` 用例组 EnterSceneReplyRoute ×9、EnterSceneReplyEcs ×8、EnterSceneTransportFailureEcs ×5、TravelOwnership ×7、
  TravelOutcomeReset ×8、TravelFreezeCap ×8 / Ecs ×9、HandoffMarkWithdrawQueue ×7、ExitRelease* ×24；2026-09-29 诊断编译后 105/105（`PROGRESS.md:6009-6010`）。
- **robot**：`login_test SceneSwitch` 只发当前 conf 的 63、等 79 最多 5 s（`robot/login_test_scenarios.go:1170-1201`）；stress AI `switchScene` 同样只发当前 conf
  （`robot/logic/ai/robot_ai.go:195-218`）；dev 下 4 节点、图 1 有 16 个频道，换图大概率跨节点（`sm.yaml:51`），但旁路开着，**18 链从未被触发**；`team_smoke` S6 是同节点跟随
  （`robot/team_smoke_scenario.go:13`、`:421-438`）；`travel_smoke` 属跨 zone。**基线没有任何以 false 配置驱动同 zone 跨节点交接的 robot 用例或故障注入记录**（`travel.md:1545-1561`）。

---

## 3 客户端可见行为

### 3.1 基线正常路径

1. 63 同步应答无错 = 「已受理、不代表已到达」（`docs/reference/mmorpg-client-contract-scene.md:236`）。
2. 冻结期间的操作被各闸拒绝或静默丢弃（§3.3）。
3. B 发 **79 `EnterSceneS2C{scene_info}`** 与 **21 自身 `ActorCreateS2C`**（`player_scene.cpp:105-116`），下一拍 AOI 发 **47**（`aoi.cpp:157-169`）。
4. A 场景旁人收到 **51**（`aoi.cpp:244-263`，经 `DetachFromScene → BeforeLeaveScene`）。
5. 没有 tip。正常冻结时长 ≈ 两次 SM 往返 + 一次存盘 + 一次 SET（无基线实测）。
6. 重选挑回原频道：幂等早退、不发 79（`mmorpg-client-contract-scene.md:248-250`）。

### 3.2 各结局：基线与 Java 5.2 对照

| 结局 | 基线 | 基线耗时上界 | Java 5.2 | Java 耗时上界 |
|---|---|---|---|---|
| 成功 | 63 应答 → 79 / 21 / 47；旁人 51 | — | 63 应答 → 旁人 51 → 79（新 scene_id）/ 21（新实体号）/ 47（非空才发）；目标旁人 21 | 一次 Dubbo + 一笔事务 + 加载 |
| 重选挑回本节点（V1） | 实例变了 79 / 21（+51）；同一实例什么都不发 | — | 结果在本节点：本地 `switchScene`（51 / 79 / 21 / 47）；就是当前场景：什么都不发 | Dubbo 往返 |
| 准入拒绝 | 63 应答 3004 / 3023 / 3014 / 3005 / 3008 / 1003 | 立即 | 63 应答 3014 / 3005 / 3023（镜像）/ 3008；3004、缺会话快照不会出现 | 立即 |
| 第一跳非 18 / 传输失败 | 23 `{3023}` / 23 `{1003}` | ≤ 10 s | 选目标拒绝 23 `{3023}`；调用失败 / 超时 23 `{1003}` | ≤ 4 s |
| 交接起不来 | 23 `{3014}` 或 `{3023}` | 一次往返 | 选目标回来时实例已不在 / 令牌不符：丢弃（无可见） | — |
| 标记没发出就失败 / B4 | 23 `{3023}`，解冻留在 A | ≤ 30–35 s | 交出未提交（LeaseTooShort / 探测 NotCommitted）：23 `{3023}`，原地解冻 | ≤ 约 15 s |
| B5（路由失败、单调回滚） | 23 `{3023}`，留在 A（采纳 E+2） | 约 5 s + 往返 | 不存在（没有回滚） | — |
| Conclude（B6 / 晚发闸 / 无会话 / 无 SM / 70 s 上限） | 23 `{3023}` + 34 `{3023}`，回选服重登 | ≤ 71 s | 交出结局无法确认（Lost）：23 `{3023}` 后断开 | ≤ 约 15 s |
| B7–B9 同 zone 非 kSucceeded | 无消息；会话已改绑 B 则正常，否则哑连接 | — | 不存在（结局确定） | — |
| gate 转发放弃 | 23 `{3023}` + 34 + 断开 | ≤ 20 s | 到目标的链路不可用 / 建链失败：23 `{3023}` 后断开 | 建链 3 s + 握手 5 s |
| B 拒建 / 载入失败 | 23 `{3023}`，之后没有实体（要重登） | ≤ 5 s | 23 `{3023}` 后断开 | 加载 ≤ 10 s |
| 失去归属（被别人夺走） | 被当废黜销毁 | — | 23 `{2017}` 后断开（现有口径，`SceneWorld.java:726-734`） | — |
| 冻结 / 在途中再发 63 | 3014 | 立即 | 3014 | 立即 |
| 改绑后、目标加载完成前的请求 | B 预登记 SessionMap，命中「未加载」守卫 | — | T 按会话找不到玩家静默丢弃（`ClientRequestHandler.java:129-133`），带应答的请求收不到应答 | 加载时长 |

### 3.3 冻结期间的业务闸

**基线**没有中心闸：`ProcessClientPlayerMessage` 只拦退出中的实体（`scene_handler.cpp:373-579`，退出闸在 `:493`）。各闸如下（本表比基线分区稿多出移动外推、组队跟随、任务事件三处，见 §8.3）：

| 闸 | 基线行为 / 码 | 出处 | Java 5.2 |
|---|---|---|---|
| 63、队伍跟随、镜像自动进场 | 3014 / 跳过 | `player_lifecycle.cpp:2990-2993`；`sm_reply.cpp:136-146` | 63 回 3014；跟随跳过（`switchState ≠ NONE`） |
| 队伍跟随（`IsFollowBlocked`：冻结**或**交接意图） | 不跟 | `player_team.cpp:65-75` | 同上 |
| 移动上报 134 / 132 / 131 | 静默丢 | `player_movement_handler.cpp:167-205` | DROP |
| 服务器外推 / 加速 | 冻结实体被 exclude | `movement.cpp:44-52`；`movement_acceleration.cpp:10-13` | 冻结时 `stopMotion()`（`ScenePlayer.java:234-236`），速度为 0 不外推 |
| RoutePlayerStringMsg | 静默丢（IMPORTANT 级打 ERROR） | `scene_handler.cpp:733-754` | Java 无此通道 |
| 放技能 84 | **请求不拒**；到施法点时冻结施法者 no-op（`HandleGeneralSkillSpell` / `HandleChannelSkillSpell`）；冻结目标的伤害丢弃 | `skill.cpp:47-56`、`:264`、`:323`、`:611` | ALLOW（Java 的伤害 / buff 两版都未生效，`PARITY.md:77`、`:80`；施法运行态不持久化） |
| 货币加 / 扣 | `kAssetFrozen` 27003 | `currency_system.cpp:105-114`、`:228-235` | 入口 GATED + 服务闸 27003 |
| GM 封禁 / 解封货币 | 1005 | `player_currency_handler.cpp:102-108`、`:129-135` | REJECT 1005 |
| 背包加 / 删 | 1005 | `bag_service.cpp:76-90`、`:158`、`:239`、`:313`、`:353` | GATED 1005 |
| 资产通道 | 冻结或交接在途 RETRY{27003}；退出中 RETRY{27006} | `asset_op_system.cpp:162-177`、`:750-764` | FREEZING 回 RETRY 27003 |
| 属性加点 / 方案、宠物 | 1005 | `player_attribute.cpp:283-286`；`player_pet.cpp:234-237` | GATED 1005 |
| 173 自动加点 | 不过写前置（冻结中照改，随后被丢） | `scene-core.md:333` | REJECT 1005（D9） |
| 任务接取 / 领奖、任务事件 | 拒绝 / 丢弃事实 | `player_mission.cpp:31`；`mission_event_handler.cpp:43` | GATED 1005；事实丢弃 |
| 货币 GM、回档 | 拒绝 | `player_currency_handler.cpp:102`、`:129`；`player_rollback_handler.cpp:59` | REJECT / 不适用 |
| PrepareBattle | `kFeatureUnavailable` | `player_battle.cpp:1128-1133` | 6.3 |
| 战斗结算、buff、AFK、属性重算 / 同步 | exclude | `player_battle.cpp:346-356`；`buff.cpp:100`、`:566-573`；`afk.cpp:25-50`；`actor_attribute_calculator.cpp:100-111`；`actor_state_attribute_sync.cpp:61-67` | 属性同步 66 无状态改动、照常；其余 Java 无或未生效 |
| 冻结实体留在 AOI | 旁人看到不动的分身；应答丢失时最长 30 s | `scene_node_service.cpp:150-155` | 同样留在 AOI，至多交出事务 + 探测的时长 |

### 3.4 坐标

- **基线（推导，跨节点特有）**：B 上的实体是新载入的，没有旧场景，`mapChanged` 恒为 false；坐标只靠导航网格校验：合法就保留 DB 坐标，否则落出生点
  （`player_scene.cpp:60-66`、`:98-104`）。同节点换地图一定落出生点；**跨节点换不同地图时，旧坐标恰好在目标地图网格上会被保留**——基线怪癖，无测试覆盖。
- **Java**：目标节点 `resolveEnterPosition` 用库里的配置号与坐标（`SceneWorld.java:400-405`）；交出事务写的是源场景的配置，所以同图保留坐标、换图落出生点，
  与同节点 `switchScene` 的规则一致（`SceneWorld.java:468`），也与 Java 登录口径 ⑦ 一致（`PARITY.md:91`）。差异登记为 D10。

---

## 4 Java 现状与差距

### 4.1 归属协议的数据面：`player` 行上的三列

| 列 | 含义 | 出处 |
|---|---|---|
| `owner_epoch` | 围栏，每次夺权加一，所有写都带上 | `schema.sql:25` |
| `owner_released` | 1 = 当前 epoch 的写者已写回并释放（或从未进场），0 = 有持有者 | `:26` |
| `owner_lease_until` | 持有者租约到期时刻（毫秒），过期后允许强制夺权 | `:27` |
| `player_state.saved_epoch` | 写入这份玩法数据的 epoch，只用于排障 | `:40` |

| 操作 | SQL 条件 | Java 入口 | 调用者 |
|---|---|---|---|
| 夺权 | `owner_released = 1 OR owner_lease_until < now` → epoch+1、released=0、租约 now+30 s；同事务再读 epoch | `PlayerMapper.java:51-56`；`PlayerStore.java:213-225` | login `EnterGameHandler` |
| 最终写回并释放 | `owner_epoch = E`（**不**要求 released=0），置 released=1；同事务 upsert `player_state` | `PlayerMapper.java:61-68`；`PlayerStore.java:233-241` | scene 离场、断线、停服、被接管 |
| 在线存盘 | `owner_epoch = E AND owner_released = 0` | `PlayerMapper.java:74-80`；`PlayerStore.java:249-257` | 周期存盘、`requestSave` |
| 只释放 | `owner_epoch = E AND owner_released = 0` | `PlayerMapper.java:95-99`；`PlayerStore.java:282-284` | 进场失败 / 取消（scene）、`abandonEnter`（login，`LoginClientMessageService.java:107-137`） |
| 批量续约 | `owner_released = 0 AND (player_id, owner_epoch) IN (…)` | `PlayerMapper.java:102-115`；`PlayerStore.java:293-316` | `OwnerLeaseRenewer`，每 10 s（`OwnerLeaseRenewer.java:30`） |

- 租约 30 s 只有一个出处（`PlayerStore.java:51`）；用各进程墙钟比较，部署要求 NTP（`architecture.md:622`）。
- 两个只能等租约过期的残余（`architecture.md:620-621`）：gate 收到 login 应答后、发出 PlayerEnter 前崩溃；PlayerEnter 已写出但链路随即断开、scene 没收到。

### 4.2 进游戏夺权与顶号（login）

```
client   gate                     login(EnterGameHandler)                 scene-manager   MySQL        Redis
 26 ───▶ callLogin ──Dubbo──────▶ 在途闸门(2005) :185-188
                                  findPlayer / 归属校验 :200-204
                                  读位置记录 applyLocation :221-241 ───────────────────────────────────▶ xm:location
                                  assign（5 s 兜底）:244-255 ───────▶ SceneAssigner.assign
                                  ◀────────────────────────────────── (node, scene)
                                  claim :295-327 ─────────────────────────────────▶ claimOwnerEpoch
                                    Held(E) → takeovers.request(E) :310 ──────────────────────────────▶ PUBLISH xm:owner-takeover
                                    退避 100→800 ms（:71-72），最长 3 s（application.yaml:95）；等不到回 2005（:313-318）
                                  Claimed(E') → 复查位置记录 rerouteIfStale :334-368
 ◀26 应答 ◀ ClientReply{EnterScene(node, scene, E')} :390-403
 gate: enterScene → PlayerEnter{session, player, scene, E'}（ClientDispatcher.java:513-543）
```

持有 E 的 scene 收到让出请求（`OwnerTakeoverSubscriber.java:51-64` 从 Redisson 线程投递到逻辑线程）后经 `SceneWorld.onTakeoverRequested` 处理（`SceneWorld.java:700-720`）：
已进场实例写回并释放、踢旧会话（2017）；加载中的进场取消、释放、`playerKicked`。只认 epoch 完全相同的实例（`:702`、`:709`）。

### 4.3 scene 侧的归属生命周期（全部在逻辑线程）

- **进场**（`onPlayerEnter`，`SceneWorld.java:252-278`；`onPlayerLoaded` `:280-387`）：停止接客或场景不在本节点直接 `failEnter` 并释放（`:256-267`、`:440-450`）；同会话加载中再次进场，
  被取代的那次释放（`:269-276`）；加载回来核对会话仍是这次进场（`:284-288`）、库里 epoch 等于请求 epoch（`:305-308`）、本节点没有更新 epoch 的实例（`:309-313`）；
  接管本节点失去归属的旧实例时，库里仍是旧实例最近落库的样子才沿用旧内存（`:335-358`，`PARITY.md:38`）。进场成功顺序：79 → 21 → 47 → 给旁人 21（`:411-432`）→
  `enterResult(0)`（`:379`）→ 写位置（`:380`）→ 拍 LOGIN 快照（`:382`）→ 组队跟随（`:386`）。脏比对基准取库里此刻的样子（`:374`）。
- **离场**（`:637-667`）：加载中离场取消并释放，主动离开写登出墓碑（`:639-648`）；已进场 `removePlayer(player, true)`，主动离开写墓碑、断线写 30 s 重连租约（`:659-665`）。
- **链路断开**（`:670-691`）：取消该链路上全部进场并释放；移除其上全部玩家并写回，位置转重连租约。
- **失去归属**（`onOwnershipLost`，`:726-734`）：移除（不写回）并踢 23 `{2017}`。
- **周期存盘**（`:745-772`、`:819-833`）：按槽到期，没变化跳过，同一玩家至多一个在途，积压推到下个周期（`StoragePlayerRepository.java:170-176`）；在线存盘回来 FENCED 且实例仍在 → 移除并踢（`SceneWorld.java:845-851`）。
- **立即存盘**（`requestSave`，`:812-817`）：资产通道记账后调用（`AssetOpService.java:468`）。
- **移除**（`removePlayer`，`:868-882`）：停下 → 出场景（旁人 51）→ 按需写回并拍 LOGOUT 快照。
- **停服**（`shutdown`，`:899-921`）：释放全部在途进场、写回全部在场玩家。节点释放顺序先摘目录、再停接管订阅与续约、再断链、最后写回（`SceneNode.java:436-530`）。
- **装配**（`SceneNode.java:329-336`）：归属快照经 `callOnLogic(sceneWorld::ownedPlayers)` 取（`SceneWorld.java:217-223`）；续约在存储线程池跑（`OwnerLeaseRenewer.java:65-97`）。
- **`ScenePlayer` 上与归属相关的字段**：会话与 epoch 是 `final`（`ScenePlayer.java:39-40`），**同一实例不能换 epoch**，跨节点必然在目标节点新建实例；位置写序号（`:51`、`:203-215`）；
  最近确认落库的快照（`:79`、`:386-392`）；在线存盘在途标记（`:81`、`:394-400`）；`toSave()` 用当前场景配置号与坐标组装写回内容（`:305-307`）；技能不持久化、每次进场按配表发放（`:46`）。

### 4.4 写回、在线存盘与重试

- 阻塞调用在有界存储线程池，结果投递回逻辑线程（`StoragePlayerRepository.java:35-53`、`:121-149`）；写任务 `SAVE`、`RELEASE`、`PROGRESS`（`:260-267`、`:302-308`）。
- 瞬时故障在 5 s 内最多 3 次，首退避 200 ms 翻倍（`:65-74`、`:212-243`）；判定见 `:246-257`；围栏拒绝不重试（`:221-222`）。**单次尝试的时长不受 5 s 截止约束**：
  一次尝试最长受 JDBC `connectTimeout=3000&socketTimeout=10000`（`xm-scene/src/main/resources/application.yaml:27`），截止只在两次尝试之间判。
- 只有 `PROGRESS` 有回调（`:311-325`），`SAVE` / `RELEASE` 没有（`:151-159`）。**交出需要带结局回调的写**，现在没有。
- 指标 `xm_scene_storage_writes_seconds{op, result}`（`architecture.md:806`）。

### 4.5 位置记录与短线重连（sm-player-location 的现状）

- 键 `xm:location:{player_id}`，Hash 字段 `e` / `q` / `s`（o / l / x）/ `v`（`PlayerLocationDirectory.java:24-32`；`RedisKeys.java:54-56`；消息 `xm-discovery/src/main/proto/xm/discovery/location.proto:14-23`）。
- 按 (epoch, 序号) 只收更新的写，epoch 更高的一律覆盖（`PlayerLocationDirectory.java:66-87`）；TTL 在线 60 s、重连租约 30 s、续期 20 s（`:46-49`）。
- 唯一写者是持有归属的 scene（`RedisPlayerLocations.java:34-53`）：进场 / 换场景写在线（`SceneWorld.java:380`、`:473`），断线写租约（`:664`、`:686`），主动离开写墓碑（`:662`、`:645`）；
  被接管 / 失去归属 / 停服不写（`architecture.md:632`）。
- 读者：login 宽松 `find`（`PlayerLocationDirectory.java:360-378`）后请 scene-manager 回原实例（`SceneAssigner.java:64-69`、`:97-112`）；资产通道严格 `findHolderAsync`
  （`PlayerLocationDirectory.java:289-304`），只认 `o`，`l` / `x` / 缺失 → NOT_HERE（`architecture.md:351`）；组队 `statusesAsync`（`PlayerLocationDirectory.java:217-251`）。

### 4.6 gate：会话绑定与链路路由

- 场景绑定四字段 `sceneNodeId`、`sceneLinkGen`、`scenePlayerId`、`sceneOwnerEpoch` 同生同灭（`ClientSession.java:51-61`），外加 `presenceOnline`（`:66`）；`boundTo(node, gen)`（`:146-148`）。
- 建立绑定只有一个入口：login 的 `EnterScene` 指令（`ClientDispatcher.java:477-490`、`:513-543`），它先对旧场景发 `PlayerLeave`（`:523`）；帧写不上（`send == 0`）走 `abandonEnter` + `failEnter`（`:534-539`）。
- 转发：上行按 `s.sceneNodeId`（`:582-597`）；下行只发给仍绑定 (节点, 代次) 的会话（`:601-611`）。
- 进场结果只认当前绑定且 epoch 相同（`:614-633`）；成功时**只在 `!s.presenceOnline` 时**登记在线目录（`:623-627`）；失败 `failEnter` 让会话回「已登录、未进游戏」并推 23 `{tip}`（`:550-556`）。
- 建链失败只在会话没关、仍绑定时才 `abandonEnter` + `failEnter`（`:636-644`）；会话已关闭直接返回，那份归属等租约过期（现有残余）。
- 踢出 `PlayerKicked` 同样按四元组过滤，推 23 `{tip}` 后断开（`:650-669`）；链路断开关闭绑定在其上的会话（`:702-713`）；断线 `onDisconnected` 发 `PlayerLeave` 后
  `finishClose` → `registry.release(s)`（`:279-292`、`:788-792`）。所有解绑经 `unbindScene`，同时撤销在线目录（`:741-750`）。
- **链路事件路由**：`SceneEventRouter` 在链路 I/O 线程上按会话号 `registry.get(...)` 找会话，**找不到就丢弃**（`SceneEventRouter.java:41`、`:50`、`:58`、`:66`）；
  会话一旦 `registry.release` 就从表里删掉（`SessionRegistry.java:35-37`）。同一链路的帧按到达顺序投递到会话 EventLoop。
- 链路层：按节点号按需建链，代次区分新旧（`SceneLinkManager.java:72-96`）；未就绪时帧排队，建链失败时排队中的 `PlayerEnter` 逐条回报（`SceneLink.java:77-120`、`:216-255`；契约 `SceneLinks.java:8-17`）。
- scene 侧：同一 gate 节点号只留一条链路，新顶旧（`GateLinks.java:58-70`）；`write` 在链路已断或不可写时丢帧（`:131-148`，`LINK_GONE` 计数 `:135`）。
- 在线目录 `xm:presence` 由 gate 写，以 epoch 高者为准（`GatePresence.java:51-73`；`architecture.md:134-142`）。

### 4.7 今天的 63（同节点换图）

```
client      gate                          scene S（逻辑线程）
 63 ──────▶ forwardToScene ─ClientForward─▶ ClientRequestHandler.enterScene :208-235
                                           3005 / 3023(镜像) / 3008 → resolveSwitchTarget（只查本节点）SceneWorld :234-248
 ◀── 63{error_message{0}} ◀── ToClient ────┤ call.reply（先回应答）:231
                                           switchScene :459-476：旧旁人 51 → 停下、换图落出生点 / 同图保留坐标 :468-471
 ◀── 79 / 21 / 47 ◀────────────────────────┤ enterScene :411-432 → 新旁人 21；locations.entered（epoch 不变、序号 +1）:473；组队跟随 :475
```

- 目标不在本节点或带镜像配置一律 3023（`ClientRequestHandler.java:216-223`）；javadoc 写明 3004 / 3014 不会出现（`:203-207`）。
- 处理器必须**同步**回应答，否则补回 1006（`:185-189`）。所以异步换图只能「先回已受理、失败再推 23」，与基线语义一致（`mmorpg-client-contract-scene.md:236`）。
- scene 没有推 23 的消息号：`SceneMessageIds` 17 个号不含 `SendTipToClient`（`SceneMessageIds.java:12-29`）；gate 用 `SceneClientPlayerCommon.SendTipToClient`（`GateNode.java:182`）。

### 4.8 现状隐含的不变量

| # | 不变量 | 证据 | 5.2 |
|---|---|---|---|
| J1 | 写者变更只经夺权或租约过期 | `PlayerMapper.java:51-56` | **打破**：新增「持有者原子交出」，任何时刻仍只有一个写者 |
| J2 | 一个 `ScenePlayer` 终生一个会话、一个 epoch | `ScenePlayer.java:39-40` | 保持：跨节点 = 新建实例 |
| J3 | gate 会话的场景绑定只由 login `EnterScene` 指令建立 | `ClientDispatcher.java:484`、`:513-543` | **打破**：新增 scene 发起的 `PlayerTransfer` 改绑 |
| J4 | 进场失败即回大厅 | `:550-556` | 只对登录进场保持；交出进场失败改为断开 |
| J5 | 位置记录只由持有者写，epoch 高者覆盖 | `PlayerLocationDirectory.java:66-87` | 保持（新增「交出未送达时源节点以旧 epoch 补写一次」，§5.14） |
| J6 | 63 同步完成，没有在途态 | `ClientRequestHandler.java:203-207` | **打破**：远端目标有在途态，再发 63 回 3014 |

### 4.9 缺口

| # | 缺口 | 现状出处 | 5.2 处理 |
|---|---|---|---|
| G1 | 63 只在本节点找目标 | `SceneWorld.java:234-248`；`ClientRequestHandler.java:221-223` | `SwitchTarget{Local / Remote / Reject}`（5.1 形状），Remote 接 §5.5 |
| G2 | scene 不调 scene-manager；`assign` 没有「换图」语义 | `SceneDirectoryService.java:13-16`；`scene_directory.proto:9-26`；`SceneAssigner.java:55-94` | `selectSwitchTarget`（§5.4） |
| G3 | 没有「写回 + 交出」原语，没有带结局回调的最终写 | `PlayerStore.java:233-284`；`StoragePlayerRepository.java:151-164` | `handOffOwnership` / `probeOwnership` + `HANDOFF` / `PROBE` 任务（§5.2） |
| G4 | 没有换图在途 / 冻结状态；闸桩恒放行；`CurrencyService` 无闸桩 | §0.5 | `ScenePlayer.switchState` + 入口集中闸（§5.9） |
| G5 | scene 不能让 gate 改绑；`enterScene` 会先发 `PlayerLeave` | `ClientDispatcher.java:523` | 新帧 `PlayerTransfer` + `rebindForTransfer`（§5.7） |
| G6 | 交出后进场失败会回大厅 | `:550-556`、`:630-632` | 交出态失败推 23 `{3023}` 后断开 |
| G7 | 续约失败、在线存盘被围栏拒都会踢人，交出中的玩家会被误踢 | `SceneWorld.java:726-734`、`:845-851` | 交出中跳过，由交出结局裁决 |
| G8 | 目标节点会拍 LOGIN 快照 | `SceneWorld.java:382` | `PlayerEnter.transfer=true` 时不拍（D8） |
| G9 | scene 不能推 23 | `SceneMessageIds.java:12-29` | 加 `sendTipToClient` |
| G10 | 建链失败而会话已关闭 / 已释放时，`PlayerEnter` 夺得的归属没人释放 | `ClientDispatcher.java:637-640`；`SceneEventRouter.java:65-70` | 未送达的 `PlayerEnter` 一律 `abandonEnter`，路由层找不到会话也代为放弃（§5.7） |
| G11 | 交出结局不明时无法判定「是不是我提交的」 | `StoragePlayerRepository.java:212-243`（结局只有 FAILED） | 剩余租约安全边际 + 租约值作本次标识 + 加锁读探测（§5.2） |
| G12 | gate 改绑后 `onPlayerEnterResult` 不会以新 epoch 重登在线目录 | `ClientDispatcher.java:623-627` | 交出进场成功时显式 `presence.online(E+1)` |
| G13 | 没有指标、robot、多节点切片（本机切片只起一个 scene，`tools/local/start-slice.sh:76`） | — | §7、§10 |
| G14 | 盘点与 PARITY 过时 | §8.3 | §5.15 |

---

## 5 Java 设计

### 5.1 思路：一笔带围栏的「交出」事务替代基线的标记链

基线必须靠标记链，是因为它的存盘链是「Redis CAS + Kafka DBTask」，源节点「已落盘」与 SM 铸 epoch 是两个系统的两次写，中间要用标记、换手门、取证、回执、A2′、看门狗缝起来
（§1.6、§1.9、§2）。Java 的归属与数据在**同一行 MySQL** 上，两件事可以合成一个原子操作：

> `UPDATE player SET <冻结快照>, owner_epoch = E+1, owner_released = 0, owner_lease_until = L WHERE player_id = ? AND owner_epoch = E AND owner_released = 0 AND owner_lease_until >= now + M`，
> 同一事务 upsert `player_state`（`saved_epoch = E`）。

- 提交成功**同时**证明：源节点的最终状态已落库；从此只有 E+1 的持有者能写。旧 epoch 的任何迟到写（在线存盘、写回、续约、只释放）都被现有围栏拒掉
  （`PlayerMapper.java:66`、`:78`、`:97`、`:104`）。
- 不需要标记、换手门、回滚回执，也不需要 SM 参与提交；scene-manager 只做选择。
- 改绑不需要 Kafka：源节点经**同一条链路**发 `PlayerTransfer`，与它发给该会话的其余下行保持先后（`GateLinks.java:146-147` 同链路按提交顺序写；`SceneEventRouter` 在同一个链路 I/O 线程上按序投递）。
- **否决**「源先写回并释放，目标 / login 再夺权」（inventory `scene-core.md:215` 的提法）：释放与夺权之间另一台设备的 EnterGame 会直接夺走（released=1），交出必然失败，
  而且多一跳 login 的 Dubbo 往返。原子交出没有这个窗口。

### 5.2 存储原语（xm-player-store）

```java
// PlayerStore
/** 交出结局。owner 是交出事务里读到的那一行（未提交时用于区分原因）。 */
sealed interface HandOffResult {
    record HandedOff(long newEpoch) implements HandOffResult {}
    record LeaseTooShort(OwnerState owner) implements HandOffResult {}   // 仍是 (E, 0)，但剩余租约不足安全边际：没改任何东西
    record Fenced(OwnerState owner) implements HandOffResult {}          // epoch ≠ E 或已释放：不是自己的了（或是自己更早一次已提交，见下）
}
record OwnerState(long ownerEpoch, boolean released, long leaseUntil) {}

@Transactional
public HandOffResult handOffOwnership(PlayerRow frozen /* ownerEpoch = E */, PlayerState state, long leaseUntil, long requireLeaseAtLeast) {
    long now = clockMs.getAsLong();
    if (mapper.updateStateAndHandOff(frozen, now, leaseUntil, requireLeaseAtLeast) == 1) {
        mapper.upsertState(frozen.getPlayerId(), state.toByteArray(), frozen.getOwnerEpoch() /* 写者 E */, now);
        long next = mapper.selectOwnerEpoch(frozen.getPlayerId());      // 行锁保证 = E+1
        if (next != frozen.getOwnerEpoch() + 1) throw new IllegalStateException(...);   // 不变量
        return new HandOffResult.HandedOff(next);
    }
    OwnerState owner = mapper.selectOwnerForUpdate(frozen.getPlayerId());              // 同事务加锁读：结论确定
    if (owner == null) return new HandOffResult.Fenced(null);
    return owner.ownerEpoch() == frozen.getOwnerEpoch() && !owner.released()
            ? new HandOffResult.LeaseTooShort(owner) : new HandOffResult.Fenced(owner);
}

/** 结局不明之后的探测：加锁读（等任何仍持有行锁的在途事务结束），语句超时有界。 */
public Optional<OwnerState> probeOwnership(long playerId, Duration statementTimeout);
```

```sql
-- PlayerMapper.updateStateAndHandOff（与 updateStateHeld 同形，多改三列、多一个租约前置）
UPDATE player
   SET level = #{level}, scene_config_id = #{sceneConfigId}, pos_x = #{posX}, pos_y = #{posY}, pos_z = #{posZ},
       owner_epoch = owner_epoch + 1, owner_released = 0, owner_lease_until = #{leaseUntil}, updated_at = #{now}
 WHERE player_id = #{playerId} AND owner_epoch = #{ownerEpoch} AND owner_released = 0
   AND owner_lease_until >= #{requireLeaseAtLeast}
-- PlayerMapper.selectOwnerForUpdate / probe
SELECT owner_epoch, owner_released, owner_lease_until FROM player WHERE player_id = #{playerId} FOR UPDATE
```

**为什么要「剩余租约安全边际 M」与「租约值作本次标识」**（对 Java 分区稿 §3.11 的收紧）：

- 存储层的重试（`StoragePlayerRepository.java:212-243`）会把「第一次已提交、应答丢了」的交出再执行一遍，第二次影响 0 行。要把它认成「自己的提交」，得排除
  「期间别人夺了权、恰好也是 E+1」。别人能夺权只有一种可能：E 的租约过期（`PlayerMapper.java:51-56`）。分区稿假设「结局不明的窗口只有几秒，远小于 30 s」，
  但剩余租约可以是 0–30 s 之间任何值——恰恰是库抖动、续约也在失败的时候，结局不明最常见。
- 所以交出 SQL 要求 `owner_lease_until ≥ now + M`（M = `xm.scene.transfer-lease-margin`，缺省 15 s）：每次尝试开始时 E 至少还有 M 的租约，S 冻结期间照常续约
  （冻结中的实例仍在 `ownedPlayers()` 里，`SceneWorld.java:217-223`），于是从第一次尝试起 M 之内**不可能**有人夺到 E 的下一代。
  再把每次尝试写入的 `L_i = now_i + 30 s` 记下来：结局不明之后读到 `(E+1, released=0, lease ∈ {L_i})` 才认作自己的提交。目标节点在拿到 `PlayerTransfer` 之前不会续 E+1，
  所以这段时间里 `owner_lease_until` 不会被别人改写。前提：各进程墙钟偏差远小于 M 与窗口之差（与现有「部署要求 NTP」同一前提，`architecture.md:622`）。
- 租约不足（LeaseTooShort）说明续约近期在失败，交出不安全：不改任何东西、原地中止、推 23 `{3023}`，玩家稍后可再试。

**存储层接线**（`StoragePlayerRepository`）：

- 新写任务 `HANDOFF`：带结局回调 `HandOffOutcome{HandedOff(E+1) | LeaseTooShort | Fenced | Failed}`，复用现有重试（瞬时故障、5 s 截止）与「结局只回调一次」
  （`StoragePlayerRepository.java:311-325` 的纪律）。每次尝试现取 `now_i`，`leaseUntil = now_i + 30 s`、`requireLeaseAtLeast = now_i + M`，记下 `L_i`；
  某次得到 `Fenced(owner)` 而 `owner = (E+1, released=0, lease ∈ 之前的 L_j)`，就改判 `HandedOff(E+1)`。
- 新只读任务 `PROBE`：`probeOwnership` 加锁读，语句超时 `xm.scene.transfer-probe-statement-timeout`（缺省 3 s，MyBatis `@Options(timeout=…)`，MySQL 与 H2 都支持）；
  按 `firstAttemptAt + M − 2 s` 截止，读失败 / 锁等待超时在截止内退避重试。结论：`(E, 0)` → `NotCommitted`；`(E+1, 0, lease ∈ {L_i})` → `HandedOff(E+1)`；其余或到截止 → `Lost`。
  加锁读会等任何仍持有行锁的在途交出事务结束，所以读到 `(E, 0)` 时那笔事务一定已回滚、不会再提交——分区稿的残余 R2「探测读到 E 而服务端随后才提交」因此消失，
  换成「锁一直等不到 → Lost（fail-closed）」。
- **结局投递被拒**（逻辑线程已停，同 `StoragePlayerRepository.java:143-149`、`:320-324` 的情形）：若结局是 `HandedOff(E+1)`，存储线程直接 `releaseOwnership(E+1)`——`PlayerTransfer` 只由逻辑线程发出，
  逻辑线程收不到结局就一定没发，E+1 没有别的知情者，释放安全。缩小停服时的 E+1 悬空窗口（§8.2 R-J1）。
- 两类任务的指标都记在 `StorageOp` 上（`SceneMetrics.java:159-176` 的枚举加 `HANDOFF` / `PROBE`）。
- **不改表结构**：只用现有三列；SQL 在 H2 上同样能跑（`PlayerStoreSqlTest` 缺省 H2，`AGENTS.md` §4）。`saved_epoch` 写 E（写者），语义与现有一致（`schema.sql:40`）。
- **新 epoch 的租约** 30 s，与登录夺权一致。它要覆盖「提交 → 目标节点首次续约」：gate 建链至多 3 s + 握手 5 s（`xm-gate/src/main/resources/application.yaml:68-69`）、
  加载受 `socketTimeout=10000`（`xm-scene/src/main/resources/application.yaml:27`）、续约周期 10 s（`OwnerLeaseRenewer.java:30`），最坏约 28 s，紧贴 30 s（登录进场今天也是这个余量）。
  目标节点交出进场成功后**立即单独续约一次**（`OwnerLeaseRenewer.renewSoon(OwnedPlayer)`），把余量拉回 20 s 以上。

### 5.3 内部契约变更（Java 自有 proto，不是同步产物）

| 契约 | 变更 | 说明 |
|---|---|---|
| `node_link.proto` | `NodeLinkFrame` oneof 加 `PlayerTransfer player_transfer = 9;`（现有到 8，`node_link.proto:10-21`） | `PlayerTransfer{uint32 session_id = 1; uint64 player_id = 2; uint64 from_epoch = 3; uint64 to_epoch = 4; uint32 target_scene_node_id = 5; uint64 target_scene_id = 6; reserved 7; /* 5.4 redirect */}`，方向 scene → gate |
| 同上 | `PlayerEnter` 加 `bool transfer = 5;`（现有到 4，`:48-53`） | 目标节点据此不拍 LOGIN 快照、计交出指标、进场后立即续约；gate 据此知道失败要断开 |
| `scene_directory.proto` | 新增 `SelectSwitchTargetRequest{zone_id, player_id, from_scene_node_id, from_scene_id, want_scene_id, want_scene_config_id}` 与 `SelectSwitchTargetResponse{tip_id, scene_node_id, scene_id, scene_config_id}` | `SceneDirectoryService.selectSwitchTarget`；`assign` 不变（`SceneDirectoryService.java:13-16`） |
| `ClientSink`（`ClientSink.java`） | 加 `boolean playerTransfer(linkId, sessionId, playerId, fromEpoch, toEpoch, node, sceneId, Runnable onWriteFailed)` | `GateLinks` 实现，同 `playerKicked`（`GateLinks.java:121-129`）；返回 false = 链路已断 / 不可写、帧确定没写出；`onWriteFailed` 挂在 `writeAndFlush` 的 future 上，投递回逻辑线程 |
| `SceneLinkListener`（gate） | 加 `onPlayerTransfer(sceneNodeId, linkGen, PlayerTransfer)` | `SceneEventRouter` 实现（§5.7） |
| `SceneMessageIds` | 加 `sendTipToClient` | `SceneClientPlayerCommon.SendTipToClient`，与 gate 同号（`GateNode.java:182`） |
| `PlayerRepository` | 加 `handOff(PlayerSave, Consumer<HandOffOutcome>)`、`probe(playerId, …, Consumer<ProbeOutcome>)` | §5.2 |
| `PlayerSnapshots.Cause` | 不新增 | 交出不拍快照（D8），`PlayerSnapshots.java:12-17` 保持 |

### 5.4 scene-manager：`selectSwitchTarget`

- 数据面与 5.1 的选频道共用（`scene-channels-spec.md:830`；5.1 实现在途，落点可能在 `xm-discovery` 的 `com.game.discovery.world` 包）。
- `want_scene_id ≠ 0`：在可用节点目录里找这个场景号（5.1 之后必须没在排空）。找不到或在排空中 → `TIP_NO_SCENE`（`SceneAssigner.java:42`）；指定了配置而与该场景不符 → 3005（`TIP_BAD_REQUEST`，`:45`）。
  不回落到「按地图挑」，与基线显式 scene_id 的解析一致（`scene-channels-spec.md:81`）。
- `want_scene_id = 0`（只带地图）：`ChannelSelector.select(zone, conf, excludeSceneId = from_scene_id, playerId)`，写软预占（5.1 D7）。
- 不铸 epoch、不写位置记录、不碰归属；基础设施异常原样上抛，与 `SceneAssigner.java:25-28` 的契约一致。
- 客户端只看到 3023 / 1003：SM 的 tip 只进日志与指标，scene 一律推 23 `{3023}`（基线同样把任何非 0 非 18 映射成 3023，`player_lifecycle.cpp:3956-3961`）。
- Dubbo：提供方超时 3000 ms（`xm-scene-manager/src/main/resources/application.yaml:68`）。scene 侧编程式引用（照 `xm-api/.../asset/IsolatedDubboModule.java:23-74` + `ReferenceConfig`），
  `retries=0`、`check=false`；local profile 直连 `xm.scene.scene-manager-url`（缺省 `tri://127.0.0.1:20882`，与 login 同，`xm-login/src/main/resources/application.yaml:79`）；
  调用方鉴权由 SPI 过滤器自动带上（`architecture.md:93-100`）；完成后投递回逻辑线程，再加本地兜底超时 4 s。

### 5.5 scene：换图状态机（只在逻辑线程读写）

`ScenePlayer` 新增不持久化的 `SwitchState`：`NONE` → `RESOLVING{token, want, deadline}` → `FREEZING{token, target(node, scene, conf), snapshot, frozenAtNanos, firstAttemptAt, pendingAbort}`。
交出成功后实例移除，世界里留 `TransferTombstone{session, player, fromEpoch, toEpoch, linkId, expireAt = now + 30 s}`（`SceneWorld.transfers`）。

**63**（替换 `ClientRequestHandler.java:208-235` 的 Remote 分支）：

1. 校验顺序同基线（`player_scene_handler.cpp:37-171`）：战斗在途 → 3023（6.3 接入）；`switchState ≠ NONE` → **3014**；三个号全 0 → 3005；镜像 → 3023（5.3 接入）；`scene_id` 等于当前 → 3008。
2. `resolveSwitchTarget` 返回 `Local(scene)`：照旧同步 `switchScene`；`Reject(tip)`：回 tip。
3. `Remote`（显式 scene_id 不在本节点；或只带地图而本节点没有该图的 ACTIVE 频道——5.1 切 hash 后才出现）：先回 `{0}`，置 `RESOLVING`，异步 `selectSwitchTarget`。
4. 结果回到逻辑线程先校验 `playersById.get(id) == player && state == RESOLVING(token)`，不符丢弃、计 `stale`：

| 结果 | 动作 | 客户端 |
|---|---|---|
| 调用失败 / 超时 | 回 NONE | 23 `{1003}`（基线 `player_lifecycle.cpp:3215`） |
| tip ≠ 0 | 回 NONE | 23 `{3023}`（基线 `:3960`） |
| 目标在本节点（目录过时，或选中本节点另一个频道） | 回 NONE，同步 `switchScene` | 51 / 79 / 21 / 47 |
| 目标就是当前场景 | 回 NONE | 什么都不发（`mmorpg-client-contract-scene.md:248-250`） |
| 目标在别的节点 | **冻结**：`stopMotion()`（`ScenePlayer.java:234-236`）→ `snapshot = toSave()`（`:305-307`）→ `FREEZING` → 提交 `HANDOFF(snapshot)` | 无 |

**RESOLVING 不冻结**：离场、断链、接管、失去归属照现有逻辑处理并清状态；迟到的选择结果因实例已不在被丢弃。组队跟随在 `switchState ≠ NONE` 时跳过；5.1 的排空改派只跳过 FREEZING。

**FREEZING 期间各事件**：

| 事件 | 处理 |
|---|---|
| `HandedOff(E+1)`，`pendingAbort = NONE` | 比对 `toSave()` 与快照，不同计 `post_freeze_mutation` 并记 ERROR（应恒 0）→ `removePlayer(player, false)`（旁人 51，不写回、不拍 LOGOUT）→ 记墓碑 → `sink.playerTransfer(…)`：返回 false（链路已断 / 不可写）→ `repository.release(E+1)` + 位置以 (E, 序号+1) 写重连租约 + 计 `link_gone`；`onWriteFailed` 回调同样处理；成功计 `handed_off` |
| `HandedOff(E+1)`，`pendingAbort = LEAVE(voluntary)` | `removePlayer(false)`；`release(E+1)`（安全：`PlayerTransfer` 没发，没人会拿 E+1 进场）；位置以 (E, 序号+1) 按 voluntary 写墓碑或重连租约；计 `left` |
| `HandedOff(E+1)`，`pendingAbort = TAKEOVER` | `removePlayer(false)`；`release(E+1)`；`kick(2017)`（旧会话仍绑 E，`ClientDispatcher.java:650-653` 对得上）；计 `taken_over` |
| `LeaseTooShort` | 未提交：回 NONE（原地解冻），推 23 `{3023}`；有 pendingAbort 时按 leave / takeover 走现有流程（写回并释放）；计 `lease_too_short` |
| `Fenced` | 已失去归属（租约过期被夺）：`removePlayer(false)` + `kick(2017)`，与 `onOwnershipLost` 同语义（`SceneWorld.java:726-734`）；计 `fenced` |
| `Failed`（重试用尽、结局不明） | 提交 `PROBE`（§5.2）：`NotCommitted` → 原地解冻、23 `{3023}`、pendingAbort 照常处理，计 `aborted_in_place`；`HandedOff(E+1)` → 同上面三行；`Lost` → `removePlayer(false)` + `kick(3023)`（gate 推 23 `{3023}` 后断开），计 `lost_unknown` |
| `PlayerLeave`（断线 / LeaveGame）、所在链路断开 | 只记 `pendingAbort = LEAVE(voluntary)`，不提交第二笔写（同一玩家只有一个写在途，结局确定）。实例与旁人视野保持到结局回来 |
| `onTakeoverRequested(E)` | 记 `pendingAbort = TAKEOVER` |
| `onTakeoverRequested(E+1)` | 源节点不持有 E+1，忽略；目标节点处理（`SceneWorld.java:700-720`），login 退避期内每次重试都重发（`EnterGameHandler.java:309-326`） |
| 续约报失去 E | 交出中**忽略**（提交之后 E 当然续不上），由交出结局裁决 |
| 在线存盘到期 / `requestSave` | 不提交（`IN_FLIGHT`）；冻结前就在途的在线存盘回来 FENCED 时不踢人（修改 `SceneWorld.java:845-851`） |
| 客户端请求 / 资产通道 | §5.9 |
| 组队跟随、5.1 排空改派 | 跳过（新结果 `switching`） |
| 停服 `shutdown` | 照常提交 `save(E)`：与在途交出谁先谁后都安全（save 先 → 交出 Fenced；交出先 → save 被拒，结局投递被拒时存储线程代为释放 E+1，§5.2） |

**墓碑**只服务「`PlayerLeave` 与 `PlayerTransfer` 在链路上交叉」：gate 在收到 `PlayerTransfer` 前已发出 `PlayerLeave`，这条 leave 落到源节点时实例已不在（`SceneWorld.java:649-652`）。
源节点查到墓碑，就按 voluntary 用 (E, 序号+1) 写墓碑或重连租约（gate 随后把这次 transfer 当过期并 `abandonEnter(E+1)`，§5.7）。墓碑**绝不**用来释放 E+1：源节点无法知道 gate 是否已改绑。

### 5.6 正常时序

```
client     gate G                     源 scene S                     scene-manager         MySQL                       目标 scene T
 63 ─────▶ forwardToScene(S) ──CF───▶ enterScene: Remote
 ◀─63{0}── ToClient ◀───────────────── reply{0}; RESOLVING
                                       selectSwitchTarget ─Dubbo──▶ 选 (T, t) + 软预占
                                       ◀────────────────────────── {node = T, scene = t}
                                       [逻辑线程] token 校验 → stopMotion → snapshot → FREEZING
                                       HANDOFF(snapshot, E) ─────────────────────────────▶ UPDATE … epoch = E+1, released = 0, lease = L
                                                                                            WHERE epoch = E AND released = 0 AND lease ≥ now+M;
                                                                                            upsert player_state(saved_epoch = E)
                                       ◀──────────────────────────────────────────────── HandedOff(E+1)
                                       removePlayer(不写回) → S 旁人 51；墓碑
          ◀─ PlayerTransfer{E→E+1, T, t} ─┤（同一条链路，排在此前的 ToClient 之后）
 G：校验 (S, gen, player, E) → 改绑 (T, E+1)，transferEntering = true；presence 保持
          ── PlayerEnter{t, E+1, transfer = true} ──────────────────────────────────────────────────────────────▶ load（存储线程池）
                                                                                            ◀── SELECT ───────────────┤
                                                                                                                    ├ epoch == E+1 → 新实例
 ◀──────────── 79(scene_id = t) / 21(新实体号) / 47 ◀──────────────────────────────────────────────────────────────── ┤ T 旁人 21
          ◀── PlayerEnterResult{0, E+1} ────────────────────────────────────────────────────────────────────────────┤
 G：transferEntering = false；presence.online(E+1)                                                                     locations.entered(E+1, q = 1)
                                                                                                                       renewSoon；组队跟随
```

### 5.7 gate 改绑（`ClientDispatcher`，会话 EventLoop 上）

- **路由层**（`SceneEventRouter.onPlayerTransfer`，链路 I/O 线程）：`registry.get(session_id)` 找到会话就照 `onPlayerKicked` 的方式投递到会话 EventLoop（`SceneEventRouter.java:57-62`）；
  **找不到（会话已 `registry.release`）就直接 `abandonEnter(player, to_epoch)`**——分区稿只在会话线程上处理「会话已关闭」，但会话关闭后很快就从表里删掉（`ClientDispatcher.java:788-792`；
  `SessionRegistry.java:35-37`），路由层会把帧整条丢掉（§8.3）。`abandonEnter` 的 `SessionContext` 只用于日志（`LoginClientMessageService.java:107-137`），用帧里的 session_id 与本 gate 节点号补齐即可。
  `onEnterUndeliverable` 同样补这条路径（G10）。
- **会话线程 `onPlayerTransfer(s, node, gen, t)`**：
  1. `s.closed`、`s.closing`，或绑定对不上（`!s.boundTo(node, gen)`、`scenePlayerId ≠ t.player_id`、`sceneOwnerEpoch ≠ t.from_epoch`）：孤儿 → `abandonEnter(player, t.to_epoch)`
     （`ClientDispatcher.java:756-786`）→ 计 `stale`。安全：E+1 只为这一帧铸出，`PlayerEnter{E+1}` 只会由这个会话发。会话号被复用给新会话时，新会话的绑定对不上，同样走这条。
  2. 否则 `rebindForTransfer`：**不发** `PlayerLeave`（源节点已移除实例）、**不撤** presence；`sceneNodeId = T`、`scenePlayerId` 不变、`sceneOwnerEpoch = E+1`、`transferEntering = true`，
     再 `gen' = links.send(T, PlayerEnter{session, player, t, E+1, transfer = true})`。
  3. `gen' == 0`：`abandonEnter(E+1)` → 推 23 `{3023}` 后断开（新断开原因 `transfer_failed`）。
- **`onPlayerEnterResult`**（`:614-633`）：成功 → `transferEntering = false`，**显式** `presence.online(player, session, E+1)`（现有代码只在 `!s.presenceOnline` 时登记，`:623-627`；
  `GatePresence` 以 epoch 高者覆盖，`GatePresence.java:63-64`）；失败且 `transferEntering` → 不走 `failEnter` 回大厅，推 23 `{tip}` 后断开（目标节点的 `failEnter` 已释放 E+1，`SceneWorld.java:440-450`）。
- **`onEnterUndeliverable`**（`:636-644`）：帧里的 `PlayerEnter` 从未写上链路就**一律** `abandonEnter(enter.player, enter.epoch)`，不再要求会话仍绑定（修 G10）；会话仍绑定且 `transferEntering` 时额外推 23 `{3023}` 后断开。
- **迟到帧**：改绑后源节点迟到的 `ToClient` / `PlayerKicked{E}` 因 `boundTo(T, gen')` 不成立被丢弃（`:605-608`、`:651-653`）；改绑前已转给 S 的请求由 S 应答，按链路 FIFO 先于 `PlayerTransfer` 到达。
- **改绑后**：上行转发到 T（`:595`），T 在加载完成前静默丢弃（`ClientRequestHandler.java:129-133`）；断线 / LeaveGame 的 `PlayerLeave` 发往 T（`:727-738`），T 的加载中分支释放 E+1 并写墓碑
  （`SceneWorld.java:639-648`）；T 链路断开沿用现有逻辑关闭会话（`ClientDispatcher.java:702-713`），T 的 `onLinkClosed` 释放这次进场的 E+1（`SceneWorld.java:671-677`）。
- 在线目录在 gap 期间保持 `presenceOnline = true`：服务端推送直接经 gate 下发，不经 scene，交出期间照常送达（`architecture.md:143-148`）。

### 5.8 目标节点

- 沿用 `onPlayerEnter` / `onPlayerLoaded`（`SceneWorld.java:252-387`）；epoch 校验天然覆盖「交出之后又被夺权」：库里不是 E+1 就 3023（`:305-308`）。
- 落位：`resolveEnterPosition` 用库里的配置号与坐标（`:400-405`）：同图保留、换图落出生点（D10）。
- `transfer = true` 时：不拍 LOGIN 快照（D8）；计 `xm_scene_transfer_enters_total{result}`；进场后 `renewSoon`；照常写位置（新实例写序号从 1 起，`ScenePlayer.java:203-205`；E+1 高于 E 直接覆盖，
  `PlayerLocationDirectory.java:66-87`）；照常触发组队跟随（`SceneWorld.java:386`）。
- 运行态随新实例清空（施法阶段、冷却、获取异常窗口、位移校验锚点、资产账本补存限频时刻；实体号换新，`:360`），与「接管 / 重新进场即清空」同口径（`architecture.md:294`）。
  基线 B 侧同样新建实体（`player_lifecycle.cpp:1253-1323`），冷却与技能上下文在基线本就不挂到玩家身上（`PARITY.md:76`），所以这一条**不是**差异（分区稿 D9 撤销，§8.3）。

### 5.9 冻结：入口集中闸 + 逐系统基线码 + 事后检测

基线要每个系统自己查冻结，漏一处即分叉（`scene-core.md:218`）。Java 把可变更的入口收拢到三处，**缺省拒绝**：

1. **客户端请求**（`ClientRequestHandler.onClientForward`，`:127-190`）：注册时给每个方法声明 `FreezePolicy`，缺省 REJECT。FREEZING 中：

| 策略 | 方法（现有注册点） | 冻结中 |
|---|---|---|
| — | 63（`ClientRequestHandler.java:105`） | 3014 |
| DROP | 134 / 132 / 131（`:101-103`） | 静默丢（应答 `Empty`），计 `move{result=frozen}` |
| READ_ONLY | 43、77（`:104`、`:107`）；54（`CurrencyFeature.java:37`）；167（`AttributeFeature.java:58`）；191（`BagFeature.java:48`）；193（`MissionFeature.java:47`）；190（`ActivityFeature.java:36`）；181（`PetFeature.java:47`） | 照常（只读冻结内存） |
| GATED | 168 / 172 / 174 / 171 / 169 / 175（`AttributeFeature.java:60-98`）；192（`BagFeature.java:49`）；194 / 195（`MissionFeature.java:49-53`）；182–189（`PetFeature.java:49-92`）；37 / 49（`CurrencyFeature.java:39-40`） | 进处理器，由服务闸回基线码：1005，货币加 / 扣 27003 |
| REJECT（缺省） | 173（`AttributeFeature.java:70`）；94 / 95（`CurrencyFeature.java:41-44`）；以后新加且没声明的方法 | 回应答内 `error_message{1005}`（与 `replyUnavailable` 同形，`ClientRequestHandler.java:241-258`） |
| ALLOW | 84（`SkillFeature.java:28`） | 照常：基线 84 请求不拒、只在施法点 no-op（`skill.cpp:264`、`:323`）；Java 的施法运行态不持久化，伤害 / buff 两版都未生效 |

2. **资产通道**（`AssetOpService.java:196-202`）：FREEZING 回 RETRY 27003；RESOLVING 放行。已见的 seq 照常只读答复（durable 看 `persistedState`，交出提交后由目标节点据实回报）。
3. **世界内部**：在线存盘、`requestSave`、组队跟随、5.1 改派、续约失去、围栏拒踢人，按 §5.5 跳过。

另外服务闸桩（§0.5）按 `player.frozen()` 回基线码作纵深防御；§5.5 的快照比对兜底检测漏掉的闸（`xm_scene_transfer_post_freeze_mutations_total` 应恒 0）。

### 5.10 失败与回滚一览

| # | 失败点 | 归属与数据 | 客户端 | 指标 |
|---|---|---|---|---|
| F1 | `selectSwitchTarget` 业务拒绝 | 不变 | 23 `{3023}` | `switch_resolves{rejected}` |
| F2 | `selectSwitchTarget` 调用失败或超时 | 不变；软预占按 TTL 过期 | 23 `{1003}` | `switch_resolves{error}` |
| F3 | 交出 LeaseTooShort | 仍是 E，未改 | 23 `{3023}`，留原地 | `transfers{lease_too_short}` |
| F4 | 交出被围栏拒（Fenced） | 别人持有，不写回（反正会被拒） | 23 `{2017}` 后断开 | `transfers{fenced}` |
| F5 | 结局不明，探测 NotCommitted | 仍是 E；原地解冻；冻结期间没有新改动 | 23 `{3023}`，留原地 | `transfers{aborted_in_place}` |
| F6 | 结局不明，探测 Lost | 已提交（E+1 无人持有，等租约过期）或已被夺；冻结快照已落库，或丢失自上次落库以来的增量（等同库失联时宕机） | 23 `{3023}` 后断开（基线 tip + 34 + 不存盘销毁，`player_lifecycle.cpp:4301-4302`） | `transfers{lost_unknown}` |
| F7 | 源节点写 `PlayerTransfer` 时链路已断 / 写失败 | 源节点 `release(E+1)`，位置写重连租约 | gate 同时收到链路断开、关会话 | `transfers{link_gone}` |
| F8 | gate 收到过期的 `PlayerTransfer` 或会话已释放 | `abandonEnter(E+1)`，login 带围栏释放 | 会话已离开 / 已关，无可见 | `gate_scene_transfers{stale}` |
| F9 | gate 到 T 的链路不可用（`send == 0`）或建链失败 | `abandonEnter(E+1)` | 23 `{3023}` 后断开 | `{link_unavailable}` / `{undeliverable}` |
| F10 | T 拒绝进场（场景没了、停止接客、epoch 不符、加载失败） | T 的 `failEnter` 释放 E+1（epoch 不符时空操作） | 23 `{3023}` 后断开 | `{enter_failed}` |
| F11 | T 进场后、续约前宕机 | 30 s 后租约过期 | 链路断开即关会话；30 s 内重连回 2005（同现有「scene 宕机」口径，`PARITY.md:37`） | 现有 |

断开后重连：login 读位置记录（T 还没写时是 S 的 `o`(E) 或 S 补写的 `l`(E)；T 写过就是 `o`(E+1)）→ 请 scene-manager 回原实例 → 夺权。E+1 已释放时立即夺到；
E+1 无人持有时撞上 Held、让出请求没人应，3 s 后回 2005（`EnterGameHandler.java:313-318`），客户端重试，至多 30 s。数据一定是冻结快照或更新的状态。

### 5.11 竞态（Java）

- **双重持有（不可能）**：任何时刻库里只有一个 epoch；源节点在提交时失去写权，旧 epoch 的写全被围栏拒；目标节点只有加载读到 E+1 后才成为写者；gap 期间没有写者。
  gap 超过 30 s、被登录夺走 E+2 时，目标节点的加载或续约被拒（F10 / 续约失去）。基线要单独防的「回滚回执采纳导致双持」（`scene-manager-match.md:76`）在 Java 不存在：没有回滚，epoch 只进不退。
  E+1 的释放方只有三个且互斥：源节点（只在 `PlayerTransfer` 确定没发出时）、gate 的 `abandonEnter`（只在 `PlayerEnter{E+1}` 确定没发出时）、T 的 `failEnter` / 离场。
- **玩家丢失（无法落地）**：每条失败路径都以「23 + 断开」或「原地解冻 + 23」收尾；交出写有重试上限，探测有截止，提交后实例已移除、不再冻结；gate 的 `transferEntering` 由
  `PlayerEnterResult` / undeliverable / 链路断开 / 断线四条路径之一终结。残余只剩 §8.2 的 R-J1。
- **交接中重连 / 顶号**：提交前 login 读到 Held(E) → 让出请求 → S 记 TAKEOVER → 结局后释放 E+1 并踢旧会话 2017 → login 重试夺到 E+2。提交后、T 进场前 login 读到 Held(E+1) →
  让出请求落空，login 3 s 内每次重试都重发（`EnterGameHandler.java:309-326`）→ T 收到 `PlayerEnter` 后加载中分支取消、释放并踢（`SceneWorld.java:707-717`），或已进场分支写回、释放并踢（`:702-705`）；
  等不到回 2005，客户端稍后重试。login 的落点按位置记录（多半仍是 S 的 `o`(E)），夺到 E+2 后进 S 的原场景，结果一致。
- **LeaveGame 与交出交叉**：§5.5 墓碑与 §5.7 第 1 条；旧会话在提交后离开，location 不残留错误的 `o`。
- **两次 63 连发**：第二次 3014；RESOLVING 有本地超时 4 s，超时后迟到结果因 token 不符被丢。
- **T 上已有旧实例**（A→B→A 极快往返）：正常流程不会出现（S 在提交时已移除）；出现时 `onPlayerLoaded` 的 previous 分支按「epoch 更低的旧实例」处理（`SceneWorld.java:335-358`），只有内容与库完全相同才沿用。
- **目录过时**：选中的场景在 T 上已销毁或在排空 → T 按 5.1 §4.10.4 重定向到兄弟频道，或 3023 → F10。
- **S 节点号租约丢失**（`SceneNode.java:590-604`）：只停接客，对外交出不受影响，正好把人移走。

### 5.12 幂等

- 交出事务按 (player_id, E, released = 0, 租约 ≥ now+M) 只能成功一次；重试中的「其实已提交」按 §5.2 的 L_i 改判 HandedOff，不当 Fenced 踢人。
- `PlayerTransfer` 按 (节点, 代次, 玩家, from_epoch) 只生效一次，重复帧因绑定已变按过期处理；`abandonEnter` 带围栏、幂等（`ClientMessageService.java:41-46`）。
- `abandonEnter`、`release`、`PlayerLeave` 都带 epoch 围栏，重复无副作用（`PlayerMapper.java:95-99`）。
- 位置记录按 (epoch, 序号) 去乱序（`PlayerLocationDirectory.java:34-38`）。

### 5.13 超时预算

| 段 | 上限 | 出处 / 理由 |
|---|---|---|
| 63 应答 | 同步 | `ClientRequestHandler.java:185-189` |
| 选目标（scene → SM） | Dubbo 3 s，本地兜底 4 s；RESOLVING 槽同 TTL | 提供方 3000 ms（`xm-scene-manager/.../application.yaml:68`）；基线槽 TTL = SM deadline + 1 s（`player_lifecycle.cpp:186-191`） |
| 交出写 | 两次尝试之间 5 s 截止；单次尝试受 socketTimeout 10 s | `StoragePlayerRepository.java:67`；`xm-scene/.../application.yaml:27` |
| 结局不明窗口（首次尝试起到探测结论） | < M − 2 s（M 缺省 15 s） | §5.2；保证窗口内没人能夺到 E 的下一代 |
| **冻结总长** | ≈ 交出写 + 探测 ≤ M（约 15 s）+ 排队 | 基线 70 s + 1 s 扫描（`travel_freeze_cap.h:71`、`:78`） |
| 新 epoch 租约 | 30 s | `PlayerStore.java:51` |
| gate 建链 / 握手 | 3 s / 5 s | `xm-gate/.../application.yaml:68-69` |
| 目标加载 | ≤ socketTimeout 10 s | `xm-scene/.../application.yaml:27` |
| 客户端「已受理交接」预算（基线，跨 zone 客户端） | 75 s | `travel_freeze_cap.h:86-98` |

Java 从受理到出结论的最坏情形 ≈ 4（选目标）+ 15（交出 + 探测）+ 8（建链 + 握手）+ 10（加载）≈ 37 s，小于 75 s。

### 5.14 位置记录（sm-player-location 的 5.2 部分）

- 写入点：源节点 E 的 `o`（进场或换图写入），目标节点 E+1 的 `o`（交出进场写入）。gap 期间记录仍指向 S；资产通道定位到 S 得 NOT_HERE，调用方重投（`architecture.md:351`），T 写入后自愈。
- 交出提交后源节点**只在两种情形**补写一次（用 E、序号+1）：`pendingAbort = LEAVE`（墓碑或租约）、`PlayerTransfer` 确定没发出（重连租约）。这仍是「以自己写过的 epoch 写」，
  E+1 的写一定更新（J5 保持）。
- 不增加 `t`（交出中）状态：读者只有 login / team / asset 三类，`o` / `l` / `x` 足够。不做「S 在发 `PlayerTransfer` 时写一条指向 T 的 `o`」（Q3，见 §9.3）。
- 读者不用改：`find` 宽松、`findHolderAsync` 严格的约定不变（`PlayerLocationDirectory.java:253-304`、`:359-378`）。

### 5.15 需要同步修改的文档与登记

- **`architecture.md`**：§4.2 新帧 `PlayerTransfer` 与 `PlayerEnter.transfer`（`:106-128`）；§7 归属协议加第 6 步「交出」（含安全边际与探测）、残余加 R-J1–R-J3（`:605-622`）；
  §7 位置记录说明 gap 期间的写入（`:630-637`）；§8 加「11. 跨节点换图」（`:639-676`）；§10 去掉跨节点换图（`:691-697`）；§11 新指标（`:699-818`）。
- **代码注释**：`ClientRequestHandler.java:203-207`（3014 会出现）、`AssetOpService.java:46-49`、`Wallet.java:23`、`BagService.java:21`、`:113`、`PetService.java:493-494`、
  `AttributeService.java:527-529`、`MissionService.java:226`、`node_link.proto` 的 `PlayerEnterResult` 注释（交出进场失败改为断开）。
- **PARITY**：`:29`「跨节点换图待做」改为已做；新增「跨节点换图与归属交接」一行，写明 D1–D12；`:36`（交出进场失败断开而不是回大厅）；`:38` 加交出原语；`:91` 加 gap 期间位置的行为；
  「mmorpg 待做（可选）」登记 §8.1 的 B-1–B-4。
- **盘点**：`scene-manager-match.md:65`、`:77`、`:87`、`:89`、`:91`、`:101`；`scene-core.md:9`、`:201`、`:203`、`:213`、`:215` 的 java 列与勘误（§8.3）。
- **路线图**：`docs/porting/roadmap.md:71` 状态列。

---

## 6 配置与常量

### 6.1 基线（本批相关）

见 §1.12。对 Java 有意义的只有：在途换图 TTL = SM deadline + 1 s（Java 对应 RESOLVING 本地超时）；断线租约 30 s（Java 已有，`PlayerLocationDirectory.java:46-49`）；
客户端已受理交接预算 75 s（Java 最坏 37 s，§5.13）。其余（看门狗、晚发闸、冻结上限、撤回表、A2′、gate 转发补发）都随标记链一起不移植（D1、D4）。

### 6.2 Java 新增 / 复用的配置

| 配置 | 缺省 | 进程 | 说明 |
|---|---|---|---|
| `xm.scene.scene-manager-url` | `tri://127.0.0.1:20882` | xm-scene | local profile 直连 scene-manager；nacos profile 走注册中心 |
| `xm.scene.switch-resolve-timeout` | 4 s | xm-scene | 选目标本地兜底超时，也是 RESOLVING 槽 TTL；须 > Dubbo 提供方超时 3 s |
| `xm.scene.transfer-lease-margin`（M） | 15 s | xm-scene | 交出要求的剩余租约下限；须 < 租约 30 s − 续约周期 10 s = 20 s（否则健康续约下也会 LeaseTooShort），且 > 一次交出尝试的最长时长 |
| `xm.scene.transfer-probe-statement-timeout` | 3 s | xm-scene | 探测加锁读的语句超时 |
| `xm.scene.transfer-tombstone-ttl` | 30 s | xm-scene | 交叉 `PlayerLeave` 的墓碑存活时长 |
| `PlayerStore.OWNER_LEASE` | 30 s（常量） | xm-player-store | 新 epoch 租约，复用（`PlayerStore.java:51`） |
| `OwnerLeaseRenewer.PERIOD` | 10 s（常量） | xm-scene | 复用（`OwnerLeaseRenewer.java:30`）；加 `renewSoon` |
| 存储重试 `RetryPolicy.DEFAULT` | 3 次 / 200 ms / 5 s | xm-scene | 复用（`StoragePlayerRepository.java:65-67`） |
| `xm.login.owner-claim-wait` | 3 s | xm-login | 不改（`xm-login/src/main/resources/application.yaml:95`） |
| 5.1 预占 TTL | 10 s | xm-scene-manager | 复用（5.1 `WorldChannels.DEFAULT_RESERVATION_TTL`） |

启动期校验（不满足即拒启）：`switch-resolve-timeout > Dubbo 超时`；`renewPeriod < transfer-lease-margin < OWNER_LEASE − renewPeriod`；`probe-statement-timeout < transfer-lease-margin`。

---

## 7 指标

### 7.1 基线

**SM（Prometheus，subsystem `scene_manager`，`metrics.go:28`）**

| 指标 | 标签 / 取值 | 出处 |
|---|---|---|
| `enter_scene_rejected_total` | `{zone_id, reason}`；本批相关 `handoff_pending_no_marker`（第一跳常态，**不告警**）、`handoff_pending_stale_marker`、`handoff_pending_withdrawn`、`epoch_conflict`、`scene_gone`、`home_zone_*` | `metrics.go:110-125`；`enterscenelogic.go:41-70` |
| `enter_scene_rollback_total` | `{outcome}` = rolled_back / already_rolled_back / marker_gone / superseded / redis_error / plan_error | `metrics.go:265-275` |
| `enter_scene_mint_replay_recognized_total` | 应恒近 0 | `:277-284` |
| `release_player_total` | `{zone_id, outcome}` = ok / retry_ok / timeout / error | `:180-189` |
| `enter_scene_stage_seconds` | `{zone_id, stage}` = dedup / scene_resolve / reserve / update_loc / route_gate / release_dispatch / cross_zone | `:191-204`、`:338-349` |
| `enter_scene_owner_dead_takeover_total`、`reentry_barrier_blocked_total` | 归 5.5 | `:290-311` |

**告警**（`deploy/k8s/scene-manager-alerts.yaml`）：`SceneManagerEnterSceneHandoffAnomaly`（stale_marker | withdrawn | epoch_conflict 速率 > 0.05/s，`:249-279`；no_marker 不告警的说明 `:205-247`）；
`SceneManagerEnterSceneRollbackRedisError`（`:383-405`）。

**C++ scene `[TravelHandoff]` 汇总行**（30 s 一次，有变化才打；`player_lifecycle.cpp:244-299`，语义 `player_lifecycle.h:136-329`）：终态 `started`、`started_cross_zone`、`granted`、
`granted_without_reply`、`resolved_in_place`、`aborted`、`exit_wins`、`mark_sent_destroyed`、`mark_sent_client_reset`；看门狗 `save_watchdog_fired`、`reply_watchdog_fired`、`verify_rearmed`、
`watchdog_early_fire`；冻结 `frozen_ms_total`、`frozen_ms_max`、`freeze_cap_reached`、`dispatch_window_closed`、`destroy_deferred_unsettled_save`、`freeze_unstamped`；撤回 `withdraw_deferred`、
`withdraw_expired`；应答 `reply_uncorrelated`、`reply_unmatched`；存盘 `handoff_fastpath_forced`；GO-2 `rolled_back_adopted`、`returned_after_grant`、`rollback_receipt_anomaly`。
勾稽：`started − (granted + resolved_in_place + aborted + exit_wins + mark_sent_destroyed)` = 仍在途 + 被 CAS 拒而销毁（`player_lifecycle.h:203-205`）。
其它：`[OwnerEpoch] stale_owner_write_rejected`（压测期应恒 0，是双主的直接证据）、`home_zone_unknown`、`owner_epoch_unknown`（`player_lifecycle.h:94-134`；`player_lifecycle.cpp:2652-2659`）；
`[ExitRelease] inherit_*`、`inherit_refused`（`exit_release_mark.h:306-317`；`player_lifecycle.cpp:973`）；gate `[SceneEntry] gaveUp`（`scene_entry_dispatch.cpp:160-176`）。
预期成功日志序列：SM `[Handoff] … 暂拒` → scene `needs a cross-node handoff` → `[ZoneTravel] handoff started … (same-zone cross-node)` → `requested EnterScene` → `same-zone handoff granted` →
`[scene_handoff_granted] … destroyed without persisting`，全程 `stale_owner_write_rejected` 为 0（`travel.md:195`）。

### 7.2 Java（Micrometer；不以 player / session / zone / 场景实例号 / 节点号作标签，`architecture.md:725-728`；`AGENTS.md` §5）

| 进程 | 指标 | 类型 | 标签 / 说明 |
|---|---|---|---|
| scene | `xm_scene_switch_resolves_total` | Counter | `result` = local / remote / same / rejected / error / stale |
| scene | `xm_scene_transfers_total` | Counter | `result` = handed_off / lease_too_short / fenced / aborted_in_place / lost_unknown / left / taken_over / link_gone；每次开始的交出恰好终结一次 |
| scene | `xm_scene_transfer_freeze_seconds` | Timer | 从冻结到结局处理完；桶沿用逻辑线程那组 |
| scene | `xm_scene_transfers_in_flight` | Gauge | 逻辑线程在变化后推绝对值 |
| scene | `xm_scene_transfer_enters_total` | Counter | `result` = ok / failed（目标节点） |
| scene | `xm_scene_frozen_rejections_total` | Counter | `kind` = request / asset_op / move |
| scene | `xm_scene_transfer_post_freeze_mutations_total` | Counter | **应恒 0**，非 0 告警（漏掉的闸） |
| scene | `xm_scene_storage_writes_seconds` | Timer | `op` 加 handoff / probe（`SceneMetrics.java:159-176`）；`result` 加 lease_too_short / not_committed / lost |
| gate | `xm_gate_scene_transfers_total` | Counter | `result` = rebound / stale / orphan（路由层找不到会话）/ link_unavailable / undeliverable / entered / enter_failed |
| gate | `xm_gate_disconnects_total` | Counter | `reason` 加 transfer_failed（`GateMetrics.java:109`） |
| gate | `xm_gate_link_frames_total` | Counter | `type` 自动出现 player_transfer |
| scene-manager | `xm_scene_manager_switch_seconds` | Timer | `result` = ok / not_found / draining / bad_request / error |
| login | `xm_login_abandoned_enters_total` | Counter | 复用（`architecture.md:755`）；交出孤儿走 `released` / `stale` |

勾稽（对标基线 `[TravelHandoff]`）：`switch_resolves{remote}` ≈ Σ`transfers{*}` + `in_flight`；`transfers{handed_off}` ≈ `gate_scene_transfers{rebound}` + `{stale}` + `{orphan}`；
`gate_scene_transfers{rebound}` = `{entered}` + `{enter_failed}` + `{link_unavailable}` + `{undeliverable}` + 断线中断的。

---

## 8 隐患与边界

### 8.1 基线自身的隐患（移植时照搬或显式偏离；建议进 PARITY「mmorpg 待做（可选）」）

| # | 隐患 | 出处 | Java |
|---|---|---|---|
| B-1 | 生产配置下同 zone 跨节点交接没有端到端验收；dev 旁路让 18 链从不触发，robot 全绿不能证明可用 | `sm.yaml:46-61`；`travel.md:1545-1561` | 不适用（无旁路）；本批要求切片 + robot 实测 |
| B-2 | dev 旁路下落点失败时客户端变哑（ReleasePlayer 先于落点） | `enterscenelogic.go:672-705`（推导） | 不适用 |
| B-3 | R1：放行后、路由送达前断线 → location 指向没有实体的 B，租约内重登被 18 拒，最长约 30 s | `gate_event_handler.cpp:62-74`；`entergamelogic.go:753-765` | Java 对应 R-J1，最长 30 s 但重登路径不卡 18（只等租约） |
| B-4 | R4 采纳 E+2 后迟到的 ReleasePlayer 让实体退出、会话变哑；根治需 ReleasePlayer 带 epoch | `scene_node_service.cpp:163-167` | 不适用（无 ReleasePlayer） |
| B-5 | 同 zone redis_error 回滚终态 / B9 路由未送达：玩家挂在哑连接上 | `owner_epoch.go:461-475`；`player_lifecycle.cpp:3757-3918` | 不适用（结局确定，失败一律断开） |
| B-6 | R7 应答丢失时冻结分身在 AOI 里留 30 s | `scene_node_service.cpp:150-155` | 分身至多留交出事务 + 探测的时长 |
| B-7 | 跨节点换不同地图可能保留旧坐标（`mapChanged` 恒 false） | `player_scene.cpp:60-66`、`:98-104`（推导） | D10 落出生点 |
| B-8 | 173 自动加点不过写前置，冻结中照改然后被丢 | `scene-core.md:333` | D9 回 1005 |
| B-9 | 冻结目标的伤害只让伤害留 0，`DealDamage` 照跑，下次普攻加成仍可能落血 | `skill.cpp:703-705` 注释 | 不适用（伤害两版都未生效） |
| B-10 | R11 撤回失败时不经本节点的落点拦不住，只靠 300 s TTL | `handoff_mark_withdraw.h:24-29` | 不适用 |

### 8.2 Java 移植时会踩的 / 残余风险

- **R-J1**：E+1 已提交且 `PlayerTransfer` 已写进 socket，但 gate 进程随即崩溃（或停服时结局在逻辑线程停止后才回、而投递又已被接受未执行）。E+1 无人持有，最多 30 s 后才能再进；
  数据完整（冻结快照）。与现有残余「PlayerEnter 写出后链路断开」同级（`architecture.md:620-621`）。链路已断 / 写失败（§5.5）、会话已释放（§5.7）、逻辑线程已停（§5.2）三种可确知的情形已收掉。
- **R-J2**：探测锁等待超时（库端事务因网络分区迟迟不结束）→ Lost → 断开，可能误判一笔其实会回滚的交出；fail-closed，代价是玩家重登、至多等 30 s。
- **R-J3**：gap 期间 location 仍指向 S，资产通道多一次 NOT_HERE 重投。
- **R-J4**：改绑后、T 加载完成前客户端发来的带应答请求收不到应答（T 静默丢）。与基线 B 加载期同类。
- **R-J5**：墙钟偏差接近 M 时安全边际失效。与现有「租约要求 NTP」同一前提，`architecture.md:622`；启动期校验只能约束配置，不能约束时钟。
- **R-J6**：5.1 改动与本批改同一组文件（`SceneWorld` / `ClientRequestHandler` / `SceneNode` / `TeamFollowService` / `SceneMetrics`），本批须在 5.1 提交之后开工、重新取行号。
- **R-J7**：`onlineSinceMs` 在交出进场成功时被 `presence.online(E+1)` 重置（`GatePresence.java:58`）。读者只把它当展示，影响可忽略；如需保留，改为沿用旧条目的值。

### 8.3 勘误

**对基线分区稿**

1. §6.3 冻结闸表漏了三处：服务器外推 / 加速对冻结实体 exclude（`movement.cpp:44-52`；`movement_acceleration.cpp:10-13`）、组队跟随闸 `IsFollowBlocked`（`player_team.cpp:65-75`）、
   任务事件处理器（`mission_event_handler.cpp:43`）。本稿 §3.3 已补。
2. 「技能施法 / 目标：拒绝」不准确：84 请求本身不拒，冻结施法者在施法点 no-op（`skill.cpp:264`、`:323`），冻结目标的伤害被丢（`:611`）。

**对 Java 分区稿**

1. §3.8 表「ALLOW 84：基线的放技能也不查冻结（基线只有九个文件检查 `IsCrossZoneFrozen`，skill 不在其中）」的理由错误：`skill.cpp` 查的是 `PlayerFrozenComp`（`:51-58`、`:264`、`:323`、`:611`）。
   结论（ALLOW）仍成立：基线在请求层不拒，Java 的施法运行态不持久化、伤害未生效。
2. §3.6 第 1 条「`s.closed` → `abandonEnter`」在会话已从 `SessionRegistry` 删除时走不到：`SceneEventRouter` 找不到会话就整帧丢弃（`SceneEventRouter.java:41`、`:50`、`:58`、`:66`），
   而断线后 `finishClose` 很快 `registry.release`（`ClientDispatcher.java:788-792`）。这正是 R1 最常见的形态。改为路由层代为放弃（§5.7），G10 的修法同样要落在路由层。
3. §3.11「E 未释放时别人无法夺权，除非租约过期；结局不明的窗口只有几秒，远小于 30 s」不成立：剩余租约可以是 0–30 s 任何值，库抖动时续约同样在失败。改为租约安全边际 + 租约值标识 + 加锁读探测（§5.2）。
   分区稿 §3.19 R2（探测读到 E 而服务端随后提交）随之消失。
4. §3.6「成功 → `presence.online(…, E+1)`」需要改代码才成立：现有 `onPlayerEnterResult` 只在 `!s.presenceOnline` 时登记（`ClientDispatcher.java:623-627`），列为 G12。
5. §3.9 F10「`PlayerTransfer` 写出时链路已断 → E+1 无人持有」可以收掉：`GateLinks.write` 同步知道链路已断 / 不可写（`GateLinks.java:131-145`），异步写失败也有 future，源节点据此自己释放 E+1（§5.5）。
6. §3.16 D9「跨节点后运行态清空，需由基线分区稿核对」：已核对，基线 B 同样新建实体，冷却 / 技能上下文在基线本就不挂到玩家身上（`PARITY.md:76`），不是差异，撤销。
7. §3.2 引 `xm-scene/src/main/resources/application.yaml:28` 的 socketTimeout，实际在 `:27`。
8. §1.4「单次写」漏了一点：重试截止只在两次尝试之间判，单次尝试最长受 socketTimeout 10 s，交出写的上界是「约 10 s 加一次退避」，不是 5 s（§4.4、§5.13 已按此计算）。

**对 inventory**

1. `scene-manager-match.md:87`（sm-cross-node-scene-switch）：「三个 id 全 0 → 3001」应为 **3005** `kEnterSceneParamError`（`player_scene_handler.cpp:75-80`）；完整顺序 3004 → 战斗 3023 → 3014 →
   3005 → 镜像分支 → 3008 → 缺会话快照 3005 → 无 SM 1003（`:45-149`）。
2. 同条「向旧节点发 ReleasePlayer（存盘、销毁实体、放会话映射）」只在 dev 旁路下成立；生产路径源节点**忽略** ReleasePlayer，由取证裁决后**不存盘**销毁（`scene_node_service.cpp:138-176`），
   且 ReleasePlayer 在**落点之前**发出（`enterscenelogic.go:694-699`）。
3. `scene-core.md:213`（ownership-handoff）「冻结期间……客户端消息静默丢」不准确：没有中心丢弃，各闸有回码的（27003 / 1005 / 3014 / kFeatureUnavailable）也有静默的（移动、RoutePlayerStringMsg）。
4. `scene-core.md:201`（scene-switch-cross-node）「gRPC 传输失败推 23{1003}」只适用第一跳；交接 #2 的传输失败只记日志、交给看门狗（`player_lifecycle.cpp:3185-3193`）。
5. inventory 没写：目标节点建实体前的 A2′ 闸（§1.6 ⑫、§2.9）；#2 会**重新解析频道**、可能挑回源节点（§1.7 V1）；跨节点换地图在 B 上 `mapChanged` 恒 false（§3.4）。
6. `scene-manager-match.md:91`「C++ 标记链路尚未编译」已过时：2026-09-29 已诊断编译、105/105；但 false 配置下的端到端验收仍未做。
7. `scene-manager-match.md:65`（sm-player-location「java: missing」）、`:101`（sm-gate-command-channel「missing」）已过时：位置记录自批次 3.3 起就有（`PARITY.md:91`），推送通道见 `architecture.md:143-149`。
8. `scene-core.md:203`、`:215` 的 Java 建议「源节点写回并释放 → 目标节点夺权加载」被本稿否决（§5.1：释放与夺权之间的窗口会被另一台设备夺走）。
9. `scene-core.md:9`「缺：……断线重连租约」已过时（`PARITY.md:91`）。

---

## 9 建议的有意差异

### 9.1 建议采纳

| 编号 | 差异 | 理由 | 客户端可见？ | 两版同改？ |
|---|---|---|---|---|
| D1 | 一笔 MySQL 交出事务（写回 + epoch+1 + 新租约）替代 handoff 标记、18、`ReleasePlayer`、回滚回执、A2′ | 归属与数据同行，原子化之后基线那一串看门狗都不需要 | 否 | 否 |
| D2 | scene-manager 只选目标，不铸 epoch、不写 location | 保持无状态，与 `SceneAssigner.java:29-30` 的约定一致；5.1 的软预占除外 | 否 | 否 |
| D3 | 改绑经源节点链路帧 `PlayerTransfer`，不经 Kafka `RoutePlayerEvent` | 与该会话其它下行同序；没有消费滞后；Java 无 Kafka 命令通道（`architecture.md:130-149`） | 否 | 否 |
| D4 | 冻结只覆盖一笔库事务 + 一次探测（≤ 约 15 s），不设 70 s 上限、看门狗、晚发闸 | 原子提交，结局可探测、可判定 | 是：失败更快出结论 | 否 |
| D5 | 交出之后才失败：推 23 `{3023}` 后断开，不发 34；交出之前失败原地解冻 + 23 `{3023}`（同基线） | 与现有「不发 34」口径一致（`PARITY.md:37`、`architecture.md:124`） | 是 | 否 |
| D6 | 只带地图的 63 先在本节点选，本节点没有该图才跨节点 | 少一次库写和客户端重新加载；与 5.1 D17 衔接 | 是：频道分布略不同 | 否 |
| D7 | 冻结闸集中在入口、缺省拒绝，加事后检测 | 避免基线「逐系统查冻结、漏查即分叉」 | 否 | 否 |
| D8 | 交出不拍 LOGIN / LOGOUT 快照 | 不是登录或登出；冻结快照已在库里 | 否 | 否 |
| D9 | 冻结中 173 自动加点回 1005 | 基线 173 不过写前置，冻结中的改动会被丢掉（`scene-core.md:333`） | 是（极窄窗口） | mmorpg 可选（B-8） |
| D10 | 跨节点换不同地图一律落出生点 | 与同节点换图、Java 登录口径 ⑦ 一致（`PARITY.md:91`）；基线保留旧坐标是怪癖 | 是（换图落点） | mmorpg 可选（B-7） |
| D11 | 目标节点拒绝 / 到目标链路不可用：断开（不回大厅、不发 34） | 基线 B 拒建后会话挂在没有实体的 B 上，要重登；gate 放弃时 23 + 34 + 断开 | 是（少一条 34） | 否 |
| D12 | SM 不可达一律「应答 `{0}` 后 23 `{1003}`」 | 基线「没注册任何 SM」同步回 1003（`player_scene_handler.cpp:139-149`）；Java 直连 URL 无法同步得知 | 是（码相同、时机不同） | 否 |

### 9.2 列出但不建议首批采纳

- **N1 gap 期间的位置提示**（S 发 `PlayerTransfer` 时写一条指向 T 的 `o`）：gap 期间的重连落回 S 的原场景本就合理（库里快照就是 S 的地图与坐标），资产通道无论定位到 S 还是 T 都是一次 NOT_HERE 重投，收益为零。
- **N2 `owner_handoff_id` 列**：能把 Lost 中「其实已提交」的部分救回，但需要存量库迁移（`docs/design/db-migrations.md`）；§5.2 的租约值标识与加锁读已把 Lost 收窄到锁等待超时一种情形。
- **N3 交出后失败「弹回」源场景**：要再夺一次权（gate 调 login，或源节点保留实例），复杂度高、与 D5 冲突。
- **N4 给旧节点的 ReleasePlayer 等价通知**：Java 源节点自己提交交出，没有「谁来通知旧节点」的问题。
- **N5 scene 直读目录 + 共享选频道库**（不经 scene-manager）：少一跳 Dubbo，但分配逻辑会散在两个进程（见 Q7）。

### 9.3 待用户拍板 / 开放问题

1. **Q1 冻结中带应答的请求**：§5.9 的逐方法策略 + 缺省拒绝 1005（推荐），还是一律回 3014？
2. **Q2 交出之后的失败**：推 23 `{3023}` 后断开（推荐，D5 / D11），还是弹回源场景（N3）？
3. **Q3 gap 期间的位置提示**：不做（推荐，N1）还是做？
4. **Q4 覆盖模式**：5.2 上线时就把缺省切到 hash，还是先保留 per-node、只放开显式 `scene_id` 的跨节点，切片与 robot 验证通过后再切（推荐后者）？
5. **Q5 新 epoch 的租约**：保持 30 s + 目标节点 `renewSoon`（推荐），还是交出时给更长的租约（会延长 R-J1 的锁定时间）？
6. **Q6 安全边际 M**：缺省 15 s（推荐；健康续约下剩余租约 20–30 s，交出一次尝试最长约 10 s + 探测 3 s）是否合适？是否要随 `OWNER_LEASE` 联动成比例配置？
7. **Q7 选目标的通道**：scene → scene-manager Dubbo（推荐：分配逻辑与指标只在一处，与基线「scene 请 SM」同构）还是 scene 直读目录（N5）？
8. **Q8 PARITY「mmorpg 待做（可选）」**：是否登记 B-1–B-10 全部，还是只登记客户端可见的 B-3、B-7、B-8？
9. **Q9 5.1 的 `SwitchTarget.Remote` 形状**：去掉 `nodeId`（推荐，节点由 scene-manager 定），还是保留为提示？需要与 5.1 实现者对齐。

---

## 10 测试计划

### 10.1 基线测试对照（移植时逐条对照）

| 基线用例 | 验证的性质 | Java 对应 |
|---|---|---|
| `TestEnterScene_CrossNodeWithStaleMarkerIsRejectedAsHandoffPending`（`owner_epoch_test.go:377`）、`TestEnterScene_CrossNodeRejectedWithoutSideEffectsAndRetryStaysRejected`（`logic_test.go:608`） | 没准备好就不放行、拒绝无副作用 | 交出 LeaseTooShort / Fenced 不改任何列（§10.2） |
| `…CurrentMarkerIsAllowedAndMintsNextEpoch`（`:413`） | 凭据就绪时恰好前进一代 | 交出成功 epoch = E+1（§10.2） |
| `…RouteFailureAfterHandoffBumpsEpochAndLeavesReceipt`（`:617`）、`TestPlanRouteRollback`（`:225`）、`TestClassifyRollbackReply`（`:348`） | 路由失败的单调回滚 | 不适用（D1）；对应「交出后改绑失败一律释放 E+1 / 断开」（§10.4） |
| `TestMintIsRefusedOnceTheSourceWithdrewItsHandoffMarker`（`:1245`） | 源端撤回后不再放行 | 结局不明时加锁读探测的确定性（§10.2） |
| `TestEnterScene_SameNodeSceneSwitchDoesNotMintEpoch`（`:1020`） | 同节点换图不推进 epoch | 本地 `switchScene` 不调交出、epoch 不变（§10.3） |
| C++ TravelOwnership / TravelOutcomeReset / ExitRelease* | 退出优先、去留裁决 | FREEZING 中 leave / takeover / 链路断开的结局表（§10.3） |
| C++ TravelFreezeCap ×17 | 冻结有界 | 交出写 + 探测截止（§10.3） |

### 10.2 存储（`PlayerStoreSqlTest` 风格，缺省 H2，`-Dxm.it.mysql` 连真库，`AGENTS.md` §4）

- 交出成功：epoch = E+1、released = 0、租约 = 传入的 L、`player` 行与 `player_state` 都是快照、`saved_epoch = E`。
- 交出未提交的三种情况各回对应结局且不改任何列：epoch ≠ E（Fenced）；released = 1（Fenced）；剩余租约 < M（LeaseTooShort）；玩家不存在（Fenced(null)）。
- 交出之后：旧 epoch 的 `saveStateHeld`、`saveStateAndRelease`、`renewOwnerLeases`、`releaseOwnership` 全被拒；E+1 上的 `releaseOwnership` 成功；`claimOwnership` 回 Held(E+1)，租约过期后夺到 E+2。
- 交出与 `saveStateAndRelease(E)` 并发（两个线程、真 MySQL）：恰好一个成功，库里都是同一份快照。
- 交出与在线存盘并发：在线存盘先提交则交出覆盖它；交出先提交则在线存盘被拒（现有用例 `:162`、`:173` 的形状）。
- **重试改判**：第一次尝试提交后人为让应答失败、第二次尝试得到 Fenced(E+1, 0, L₁) → 仓库层改判 HandedOff；读到 (E+1, 0, 别的租约值) → 不改判。
- **加锁读探测**：另一连接持有未提交的交出事务时，探测阻塞，事务提交后读到 (E+1, 0, L)、回滚后读到 (E, 0)；持锁超过语句超时 → 探测失败（Lost 路径）。

### 10.3 scene 单测（`SceneWorldTest` / `ClientRequestHandlerTest` 风格，假仓库可控回调、假 sink 记录帧）

- **63 分支**：Local 照旧（51 → 79 → 21 → 47）；Remote 先回 `{0}`；RESOLVING / FREEZING 期间再发 63 回 3014；选目标拒绝推 23 `{3023}`、失败推 23 `{1003}`；结果在本节点就本地换；
  结果就是当前场景什么都不发；token 过期丢弃；RESOLVING 期间离场 → 结果被丢弃。
- **交出成功**：冻结前停下；快照与写入内容一致；旁人收到 51；本人在源节点收不到 79；`playerTransfer` 帧带 (E, E+1, T, t)；不拍 LOGOUT、不写位置；`ownedPlayers` 不再含该玩家；留下墓碑。
- **交出失败**：LeaseTooShort → 解冻、23 `{3023}`、之后可写；Fenced → 踢 2017；Failed → 探测 NotCommitted 解冻 / HandedOff 照成功处理 / Lost 踢 3023；探测截止前读失败重试。
- **帧没写出**：`playerTransfer` 返回 false → `release(E+1)`、位置写重连租约、计 `link_gone`；异步写失败回调同样处理。
- **冻结中的事件**：leave（主动 / 断线）、链路断开 → HandedOff 时 `release(E+1)` + 位置墓碑或租约；未提交时照常写回释放；takeover(E) → HandedOff 时 `release(E+1)` + 2017；takeover(E+1) 忽略；
  续约失去忽略；在线存盘到期跳过；`requestSave` 回 IN_FLIGHT；冻结前在途的在线存盘回来 FENCED 不踢；组队跟随跳过；停服仍提交 `save(E)`，结局投递被拒时存储线程释放 E+1。
- **墓碑**：交叉到达的 leave 按 voluntary 写位置；E+1 永不由墓碑释放；过期后不再命中。
- **冻结闸**：按 §5.9 的表逐个方法断言（READ_ONLY 照常、GATED 回基线码、REJECT 回 1005、DROP 无应答、84 照常）；资产通道 FREEZING 回 RETRY 27003、RESOLVING 放行；
  人为绕过闸时 `post_freeze_mutation` 计数。
- **目标节点**：`PlayerEnter{transfer = true}` 不拍 LOGIN；同图保留坐标、换图落出生点；epoch 不符回 3023 并释放；位置 E+1、序号 1 覆盖 E 的记录（假目录）；进场后 `renewSoon`。
- **服务闸桩**：`BagServiceTest`、`PetServiceTest`、`AttributeFeatureTest`、`MissionServiceTest`、`CurrencyServiceTest` 冻结时回 1005 / 27003，零写入、零流水；任务事实冻结时丢弃。

### 10.4 gate 单测（`ClientDispatcherTest` 风格，`FakeLinks` / `FakeLogin` / `RecordingPresence`）

- 正常改绑：不发 `PlayerLeave` 给 S；向 T 发 `PlayerEnter{E+1, transfer}`；presence 保持；成功后 `online(E+1)` 被调用（即使 `presenceOnline` 已为 true）。
- 过期的 transfer：绑定已变 / 会话关闭中 / epoch 不符 → `abandonEnter(E+1)`、不改绑；**路由层找不到会话** → 照样 `abandonEnter(E+1)`（`SceneEventRouter` 单测）。
- `send == 0`：`abandonEnter` + 23 `{3023}` + 断开（`transfer_failed`）。T 拒绝：断开，**不**回大厅（对照现有用例「进场被 scene 拒绝时解绑场景并推 tip」）。
- undeliverable：会话已关闭 / 已释放也 `abandonEnter`（修 G10）。
- 迟到帧：改绑后 S 的迟到 `ToClient` / `PlayerKicked{E}` 被丢弃；S 在 `PlayerTransfer` 之前发出的 `ToClient`（冻结中请求的应答）照常下发。
- 改绑后：断线发 `PlayerLeave` 给 T；LeaveGame 发 `PlayerLeave{voluntary}` 给 T；T 链路断开关闭会话。

### 10.5 scene-manager 单测（`SceneAssignerTest` 风格）

`selectSwitchTarget`：显式场景号在别的节点 → 回该节点；不存在或在排空 → `TIP_NO_SCENE`；配置不符 → 3005；只带地图 → 排除源场景、按人数加预占挑；zone = 0 → 参数错；目录读失败原样上抛；指标计数。

### 10.6 真 Redis 集成测试（`-Dxm.it.redis`）

- 位置记录：T 先写 E+1 后，S 的 (E, q+1) 迟到写被拒；S 补写的 (E, q+1) 租约随后被 T 的 E+1 覆盖。
- 接管频道：交出 gap 期间的 takeover(E+1) 由 T 处理。

### 10.7 本机多节点切片

`start-slice.sh` 加 `XM_SCENE_NODES=2`，两个 scene 各用不同的 `link-port`（21000 / 21001）、`asset-rpc-port`（21100 / 21101）、`SERVER_PORT`（18104 / 18114）（与 5.1 §9.6 共用改动，
`scene-channels-spec.md:1169`），并加 `xm.scene.scene-manager-url`。

| 编号 | 用例 | 期望 |
|---|---|---|
| M1 | A 在节点 1，63 `{scene_id = 节点 2 上的场景}` | §3.2 成功行；库里 epoch 加一；`xm:location` 指向节点 2、E+1；`xm_scene_transfers_total{handed_off}` +1 |
| M2 | 再 63 回到节点 1 | 同上，epoch 再加一 |
| M3 | 在节点 2 上断开后立即重连 | 回到节点 2 原实例、原坐标 |
| M4 | 冻结窗口内用另一个 mysql 会话 `SELECT … FOR UPDATE` 持行锁 8 s | 交出尝试超时 → 探测在锁释放后读到 (E, 0) → 23 `{3023}`，玩家留在原地、可以继续玩；`stale_owner` 类拒绝为 0 |
| M5 | 交出提交后、T 进场前 `kill -9` 节点 2 | gate 推 23 `{3023}` 后断开；30 s 内重连回 2005，之后成功；数据是冻结快照 |
| M6 | 停掉 scene-manager 后跨节点 63 | 23 `{1003}`；同节点 63 不受影响 |
| M7 | 跨节点 63 的同时用另一条连接顶号 | 旧连接 23 `{2017}`；新连接进场成功（或 2005 后重试成功）；库里只有一个持有者 |
| M8 | 连发 63 | 第二条回 3014 |
| M9 | 跨节点之后帮会捐献 / 兑换（资产通道） | 最终在节点 2 落账，durable 为真，没有 UNKNOWN |
| M10 | 停掉节点 1（GM 签名停机）时它正在交出 | 停服写回与交出任一方被拒；玩家数据完整；至多 30 s 锁定 |
| M11 | 停掉续约 25 s（人为让节点 1 的续约失败）后跨节点 63 | LeaseTooShort → 23 `{3023}`，留在原地 |

### 10.8 robot

- **新增 Java robot `CrossNodeScenario`**（`RobotOptions.Scenario` 加 `CROSS_NODE`），前提是两个 scene 节点、per-node 覆盖、每图每节点一个频道：
  1. A、B 登录，用 79 的 `scene_id` 判断是否同场景；同场景就让 B 等 6 s（目录 5 s 刷新，`SceneDirectoryPublisher.java:26`）后重登，有次数上限，直到两人在不同节点。
  2. A 发 63 `{scene_id = S_B}`：断言应答 `{0}`，收到 79（`scene_id = S_B`）、21（自己，新实体号）、47 含 B；B 收到 A 的 21；可选观察者 C 留在 S_A，收到 A 旧实体的 51。
  3. A 移动，B 收到 66；A 发 77，节点 2 应答。
  4. A 断开重连，回到 S_B 原位。
  5. A 发 63 回 S_A。
  6. A 发 63 `{scene_id = 不存在}`：应答 `{0}` 后收到 23 `{3023}`。
  7. 连发 63，第二条 3014。
  8. 顶号：旧连接 23 `{2017}`。
- **改造**：`ReconnectScenario` / `TeamScenario` 依赖「63 按地图永远在本节点」（I1，`scene-channels-spec.md:526`）；D6 保持了这一点；切 hash 覆盖后按 5.1 §9.7 改造。
- **兼容**：mmorpg Go robot 的 login_test `SceneSwitch` 与 stress AI 的 `switch_scene` 只带当前地图，在 Java 本节点完成，不受 5.2 影响；基线 `travel_smoke` 属于 5.4。

---

## 11 实现记录（2026-10-05，批次 5.2）

> §0–§10 是动工前的设计稿，正文保留不改；实现中发现的偏差、补全与订正记在本节，**两处不一致时以本节与代码为准**。
> 编号 K1–K40 只在本节内引用。落地后的架构说明见 `docs/design/architecture.md` §4.2、§7 第 6 步、§8.1、§11，对账见 `PARITY.md`「跨节点换图与归属交接」行。

### 11.1 拍板结果

§9.1 的 D1–D12 全部采纳。§9.3 按推荐项定：Q1 逐方法 `FreezePolicy`、没声明的缺省 REJECT（1005）；Q2 交出之后的失败推 23 后断开；Q3 不做 gap 期间的位置提示；
Q4 保持 per-node 覆盖（跨节点只由显式 scene_id 触发）；Q5 新 epoch 租约 30 s + 目标节点 `renewSoon`；Q6 M = 15 s（不随 `OWNER_LEASE` 联动）；
Q7 scene → scene-manager Dubbo `selectSwitchTarget`；Q8 PARITY「mmorpg 待做（可选）」只登记 B-1、B-3、B-7、B-8；Q9 `SwitchTarget.Remote` 不带节点号。

### 11.2 落点

| 规格 | 代码 |
|---|---|
| §5.2 存储原语 | xm-player-store：`PlayerStore.handOffOwnership(frozen, state, leaseUntil, requireLeaseAtLeast, timeout)` / `probeOwnership(playerId, timeout)`、`HandOffResult{HandedOff, LeaseTooShort, Fenced}`、`OwnerState`（新文件）、`PlayerMapper.updateStateAndHandOff` / `selectOwnerForUpdate`；xm-scene：`StoragePlayerRepository` 的 `HANDOFF` / `PROBE` 任务、`HandOffOutcome`、`HandOffAttempts`（各次尝试写入的 L_i）、`ProbeOutcome`、`HandOffSettings`、`awaitTransfersSettled`；`OwnerLeaseRenewer.renewSoon` |
| §5.3 内部契约 | `node_link.proto`：`PlayerTransfer`（oneof 9，字段 7 预留）、`PlayerEnter.transfer = 5`、`PlayerEnterResult` 注释；`scene_directory.proto`：`SelectSwitchTarget{Request, Response}`；`SceneDirectoryService.selectSwitchTarget`；`ClientSink.playerTransfer`（`GateLinks` 实现）；`SceneLinkListener.onPlayerTransfer`；`SceneMessageIds.sendTipToClient` |
| §5.4 scene-manager | `SwitchTargetSelector`（规则）+ `SceneDirectoryProvider.selectSwitchTarget`（适配与指标） |
| §5.5 状态机 | `SwitchPhase{NONE, RESOLVING, FREEZING}` + 挂在 `ScenePlayer` 上的 `PlayerSwitch`（异步回调捕获对象本身、回来按引用比对，代替 §5.5 的 token）；`SceneWorld.resolveSwitchTarget` → `SwitchTarget.Remote`；`RemoteSwitchTargets` / `CrossNodeSwitch`；`TransferTombstone`；`com.game.scene.transfer.SceneManagerSwitchTargets`（Dubbo 调用方） |
| §5.7 gate | `SceneLink.onFrame`、`SceneEventRouter`（`on*WithoutSession`）、`ClientDispatcher.onPlayerTransfer` / `rebindForTransfer` / `transferEntering`、`DisconnectReason.TRANSFER_FAILED`、`GateMetrics.SceneTransferResult` |
| §5.9 冻结闸 | `FreezePolicy`、`SceneFeature.Registrar.on` 五参数形式、`ClientRequestHandler.onClientForward`；`AssetOpService` 第 5 步；`BagService` / `PetService` / `AttributeService` / `MissionService` / `CurrencyService` / `Wallet` 的闸 |
| §5.5 停服行 | `SceneShutdown.TransferSettlement`（见 K40） |
| §10.7 / §10.8 | `tools/local/{start,stop}-slice.sh` 的 `XM_SCENE_NODES`；xm-robot `CrossNodeScenario`（子命令 `cross-node`，`RobotOptions.Scenario.CROSS_NODE`） |
| §5.15 代码注释 | 已按清单改：`ClientRequestHandler`（3014 会出现）、`AssetOpService` 类注释、`Wallet`、`BagService`、`PetService`、`AttributeService`、`MissionService`、`node_link.proto` 的 `PlayerEnterResult` |

### 11.3 偏差与补全

**存储（xm-player-store、`StoragePlayerRepository`）**

| # | 规格 | 实现 | 原因 |
|---|---|---|---|
| K1 | §5.2 探测语句超时用 MyBatis `@Options(timeout=…)` | 每次调用开一个带超时的 Spring 事务（`TransactionTemplate`），交出事务（不含 COMMIT）与探测读都受 `transfer-probe-statement-timeout` 约束；`PlayerStore` 新构造器带 `PlatformTransactionManager`（自动装配注入），旧的两参构造器保留，用它时交出 / 探测抛 `IllegalStateException` | `@Options` 是编译期常量，接不了配置；M4（行锁占 8 s）要求交出尝试本身超时，不只是探测 |
| K2 | §5.2 复用现有瞬时故障判定 | `TransactionTimedOutException` 也算瞬时故障、退避重试 | 抛出时事务已回滚，重试安全 |
| K3 | §5.2 读到 (E+1, 0, 租约 ∈ 之前的 L_j) 改判 HandedOff | 只认**更早**某次尝试的 L_j，不认本次的 L_i | 本次影响 0 行说明本次没写，同值只可能是巧合 |
| K4 | §5.2 `Failed` 交给探测 | `Failed` 不计 `writeFailures`（结局不明不是写丢失）；交出任务被池拒时尝试列表为空，探测不读库、直接 `NotCommitted` | 任务没执行就一定没提交 |
| K5 | §5.2 探测「到截止 → Lost」 | 读失败退避重试（封顶 1 s）到「第一次尝试 + M − 2 s」（`HandOffSettings.PROBE_DEADLINE_SLACK`），**截止已过也至少读一次** | 评审确认不削弱安全：截止之后能夺到下一代的只有登录夺权，其租约 now + 30 s 一定晚于任何 L_i，晚到的「已交出」不会把别人的 E+1 认成自己的（前提仍是 NTP，R-J5） |
| K6 | §7.2 `result` 加 lease_too_short / not_committed / lost | 另加 `handed_off`；`xm_scene_storage_writes_seconds` 只注册各 op 可能出现的组合（21 个，不是 5 种 op × 9 种结局的 45 个全组合），不可能的组合静默不记、不抛 | 存储线程还要接着投递结局 |
| K7 | §6.2 启动期校验 | `续约周期 10 s < M < 30 s − 10 s`（不含端点）、`M > 2 s`、`1 s ≤ 语句时限 < M` | JDBC 查询超时粒度是秒 |
| K8 | §10.2 「持锁超过语句超时 → 探测失败」 | H2 的查询超时打断不了行锁等待，两条持锁超时用例只在 `-Dxm.it.mysql` 真库上跑（约 1.2–1.6 s 超时） | H2 限制 |

**scene-manager**

| # | 规格 | 实现 | 原因 |
|---|---|---|---|
| K9 | §5.4 显式 scene_id 在目录里找 | 同一 scene_id 出现在多个节点上 → `TIP_NO_SCENE`（`not_found`）+ WARN，不挑其一 | 节点身份有歧义时拒绝，同基线口径 |
| K10 | §5.4 只带地图 → `ChannelSelector.select` | 地图不是主世界地图 → `not_found`，不读目录 | 不把人送进私有副本 / 镜像实例 |
| K11 | §5.4 只有按地图挑的写软预占 | 显式 scene_id 解析成功也写（`ChannelSelector.reserveInstance`，同 login 指定实例的做法）；预占存储故障因此会让这类调用失败 → scene 推 23 {1003} | 并发分配能看到正在赶来的玩家；要去掉是 `selectExplicit` 里一行 |
| K12 | §5.4 基础设施异常原样上抛 | 提供方以异常完成 future，消息只带概要（「场景目录暂不可用: <类名>」），完整堆栈留本进程日志，计 `error` | 内部细节不外发，同 `assign` |
| K13 | — | 5.1 的 `ChannelSelector` / `WorldChannelProperties` / `WorldChannelPlanner` / `application.yaml` 覆盖注释改为「5.2 之后仍缺省 per-node」，缺省值不变 | Q4 |

**scene（源节点 / 目标节点）**

| # | 规格 | 实现 | 原因 |
|---|---|---|---|
| K14 | §5.5 第 3 条 Remote | 只带地图、本节点没有、且**不是主世界地图** → 直接 3023，不问 scene-manager；`CrossNodeSwitch.DISABLED`（旧构造器与旧单测）下远端去向一律仍回 3023 | 与 K10 同理；旧装配行为不变 |
| K15 | §5.5 结果表 | 补两行：结果节点是本节点但场景不在本节点 → 23 {3023}（绝不交给自己）；节点号或场景号为 0 → 23 {1003}。RESOLVING 槽在兜底超时 + 1 s 时清掉 | 应答残缺按调用失败；回调丢失的兜底 |
| K16 | §5.4 / §6.2 local 直连、nacos 走注册中心 | `SceneManagerSwitchTargets`：独立 Dubbo 模块 + `ReferenceConfig`（`retries=0`、`check=false`、3 s），自己的守护线程建引用并调用，逻辑线程不碰 Dubbo；**只支持直连**（xm-scene 没有 Nacos 注册中心依赖）。校验：`switch-resolve-timeout` 大于 3 s 且不超过 30 s，`scene-manager-url` 不能为空，`transfer-tombstone-ttl` 1 s～5 min | 接 Nacos 要给 xm-scene 加依赖，留到部署批次 |
| K17 | §5.5「`onWriteFailed` 回调同样处理」并计 `link_gone` | 异步写失败照样释放 E+1、写重连租约，但计 `xm_scene_link_dropped_total{reason="write_failed"}`（新 `LinkDrop.WRITE_FAILED`），**不**再计 `transfers{link_gone}` | `handed_off` 已在交给链路时计过，§7.2「每次交出恰好终结一次」优先；勾稽改为 `transfers{handed_off}` − `link_dropped{write_failed}` ≈ gate `rebound + stale + orphan + invalid` |
| K18 | §7.2 冻结时长桶「沿用逻辑线程那组」 | 5 ms～20 s 共 13 个 | 逻辑线程那组止于 1 s，装不下带重试的库事务 |
| K19 | §7.2 `transfer_enters{ok/failed}` | 只计明确的 ok / failed，被离开 / 断链 / 接管 / 停服取消的不计 | 取消不是目标节点的失败 |
| K20 | §5.5 组队跟随与 5.1 改派跳过 | 跟随在 `switchPhase ≠ NONE` 时跳过（`team_follow{switching}`）；排空改派只跳过 FREEZING（`channel_relocations{switching}`）；`switchScene` 拒绝冻结中的玩家并记 ERROR（兜底） | 同规格，补上指标值与兜底 |
| K21 | §5.5 墓碑 | 按会话记，每秒清过期，所在链路关闭时整批丢弃 | 链路没了就不会再有交叉的 leave |

**冻结闸**

| # | 规格 | 实现 | 原因 |
|---|---|---|---|
| K22 | §5.9 表 63「—」 | 63 注册为 ALLOW（`enterScene` 自己回 3014），不另设策略值 | 少一个只有一处用的值 |
| K23 | §5.9 缺省 REJECT | `Registrar.on` 四参数形式缺省 REJECT；功能模块声明 DROP、有应答的方法声明 DROP、缺策略都启动即失败；REJECT 落在 `Empty` 应答的方法上静默丢；REJECT 逐条打 INFO、DROP 打 DEBUG；`freezePolicy(messageId)` 供测试读 | DROP 只给核心移动用，防止有应答的方法被吞掉 |
| K24 | §7.2 `frozen_rejections{kind}` | 只计入口闸（request / asset_op / move），GATED 方法由服务闸回的 1005 / 27003 不计 | 服务闸照常进处理器，属业务应答 |
| K25 | §5.9 GATED 含 182–189 | 188 宝宝自动加点按表是 GATED，但它的服务没有写闸（只计算，同基线），冻结中照常应答 | 不改状态 |
| K26 | §5.9 资产通道 | FREEZING 时未见 seq（含中止占位）回 RETRY 27003、什么都不记；已见 seq 照常只读答复，但冻结中**不触发补存**（补存限频不被占用，原地解冻后第一次重查立即补存）；另加两条目前不可达的防御分支（`checkAdd` 回 FROZEN、背包拒绝冻结玩家）也回 RETRY 27003，不记成永久拒绝 | 冻结中写不了库；防御分支不能把可重试的情形变成终态 |
| K27 | §0.5 `CurrencyService` 加闸 | 加 / 扣 / `checkAdd` 在参数校验之后、封禁检查之前回 27003（基线顺序）；新增 `block` / `unblock` 冻结时回 1005，94 / 95 经它们；`Wallet` 加 `FROZEN` 与静态参数校验 | 同基线 |
| K28 | §5.9 84 ALLOW | `SkillService.settle` 里「随 5.2」的 TODO 改写为：Java 刻意不照搬基线对冻结施法者的早退 | 施法运行态不持久化，伤害两版都未生效 |

**gate**

| # | 规格 | 实现 | 原因 |
|---|---|---|---|
| K29 | §7.2 `gate_scene_transfers{result}` | 多一个 `invalid`：绑定对得上但目标节点为 0 或 `to_epoch ≤ from_epoch` → 推 23 {3023} 后断开，保留绑定让正常断线流程给源节点发 `PlayerLeave`；`to_epoch > from_epoch` 时同时放弃它 | 防御畸形帧 |
| K30 | §5.12「重复帧按过期处理」（会放弃） | 会话已绑在同一玩家的 `to_epoch` 上时只计 `stale`、**不**放弃 | 放弃会释放目标节点正在加载的 E+1 |
| K31 | §7.2 勾稽「rebound = … + 断线中断的」 | `undeliverable` 也计会话已断开之后才报未送达的交出进场（路由层路径）；「每次 rebound 至多一个终结结果」仍成立（评审核过），只是「断线中断的」一部分落在 undeliverable | 那次进场确实没送到 |
| K32 | §5.7 第 1 条路由层代为放弃 | `ClientSession.execute` 改为返回 boolean，会话 EventLoop 拒收任务时同样走无会话路径（`onPlayerTransferWithoutSession` / `onEnterUndeliverableWithoutSession`） | 拒收与找不到会话等价 |
| K33 | §5.5 Lost → `kick(3023)` | 经 `PlayerKicked{3023}` 到 gate，计 `xm_gate_disconnects_total{reason="kicked"}`；`transfer_failed` 只计 gate 改绑之后自己看到的失败（`send` 为 0、未送达、目标拒绝、invalid） | Lost 不一定已提交，也可能是归属被夺；scene 侧 `transfers{lost_unknown}` 是唯一计数 |
| K34 | §5.7 目标链路断开 | 交出进场途中目标链路断开沿用现有行为：`scene_link_down` 关会话、不推 tip | 同规格 |
| K35 | §10.4 G10 | 现有用例改名为「更早一次进场的迟到建链失败回报_只请login放弃那一次的epoch_不解绑新的进场」：G10 之后对旧 epoch 也调 `abandonEnter`（带围栏、无害），绑定不变 | — |

**robot 与切片**

| # | 规格 | 实现 | 原因 |
|---|---|---|---|
| K36 | §10.7 `XM_SCENE_NODES=2` | 缺省 1（行为不变），只接受 1 / 2；第二个实例 `xm-scene-2` 链路 21001、资产 21101、管理 18114，经命令行参数传；每个实例都带 `--xm.scene.scene-manager-url=$XM_SCENE_MANAGER_URL`；两个实例依次起，各等链路端口、资产端口与自己管理端口上的 ACTIVE 频道；日志 / pid 为 `xm-scene-2.*`，`stop-slice.sh` 先停 `xm-scene-2` | 节点号由 Redis 租约自动分 |
| K37 | §10.8 robot 步骤 | 用三个新账号（`<prefix>xn<tag>_1/_2/_3`）：前两个同场景就让 2 号 LeaveGame、等 6 s 重登（至多 4 次），3 号决定谁是 A、自己当留在 S_A 的观察者 C；63 在 MessageLimiter 表里没有行（缺省每秒 3 条），连发步骤前等 1.2 s，单测对着同步来的配表核对 | 不让限频在 scene 之前挡掉第二条 63 |
| K38 | §10.7 M1–M11 | robot 覆盖 M1–M3、M8 与目标不存在、顶号；M4–M7、M9–M11（持行锁、kill -9、停 scene-manager、资产通道、停服交错、停续约）没有自动化（停服交错由 `SceneShutdownHandOffTest` 单测覆盖） | 需要人工注入故障 |
| K39 | §10.8「改造」 | ReconnectScenario / TeamScenario 不用改（per-node 保持）；但 movement robot 假定 A、B 在同一场景实例，双节点切片上登录按人数把两人分到不同节点，起步条件不成立（「A、B 没有分到同一场景实例」），team robot 在双节点切片上只是碰巧同节点才过——两者照旧在单节点切片上跑；要在双节点上跑需让 B 先 63 到 A 的场景（未做） | 登录分配按人数挑频道 |

### 11.4 评审发现与修复

四路评审共 10 条发现，经复核 5 条成立（3 个不同的缺陷），全部修复并补测试（每条新测先在旧代码上确认失败）：

- **K40 停服与在途交出交错（3 条发现同一缺陷，低～中）**：`SceneNode.release` 的顺序是写回 → 关存储池 → 最后才停逻辑线程。冻结中的玩家被写回移出后，
  在途的交出若在 `save(E)` 之前提交，结局照常投递到**还活着**的逻辑线程（§5.2「结局投递被拒时存储线程代为释放」根本触发不到），
  其「释放 E+1」被已关的池拒掉：E+1 悬空到租约过期，还报一条假的「写丢失（需人工修复）」；交出结局不明时探测同样被拒，可能把已提交的交出判成 Lost。
  修法：`StoragePlayerRepository` 记「已提交、结局还没在逻辑线程上处理完」的交出 / 探测数（提交时加；逻辑线程回调执行完、或存储线程兜底释放之后减；
  由 Failed 转探测时先记探测再减交出，计数不会短暂归 0），`awaitTransfersSettled(timeoutNanos)` 等它归 0；`SceneShutdown.writeBackThenDrainStorage`
  多一个 `TransferSettlement` 参数，写回执行完之后、`storage.shutdown()` 之前在同一停服预算里等，预算用完记 ERROR 照常关池（写回根本没执行时不等）；
  `SceneNode.release` 传 `repo::awaitTransfersSettled`。被拒的 RELEASE 改记「租约过期后自动释放（至多 30 s）」，仍计 `writeFailures`。
  新测 `SceneShutdownHandOffTest`（真逻辑线程 + 真存储池 + 真仓库 + 真 `SceneWorld`，按 `SceneNode` 停服顺序）：交出在写回之后提交 → 释放 E+1、`writeFailures` 为 0；
  交出结局不明 → 探测照常执行、认出提交并释放；写回先提交 → 交出被围栏拒、什么都不释放。
- **冻结中主动离开被随后的链路断开降级（低）**：`PlayerSwitch.requestLeave` 后来者为准、`onLinkClosed` 恒传「非主动」，结局时写成 30 s 重连租约而不是登出墓碑。
  修法：已记下的离开只能再加上「主动」（`leaveVoluntary |= voluntary`）。`HandOffTransferTest` +2（HandedOff 与 LeaseTooShort 两种结局都只写 `loggedOut`）。
- **gate 链路判死后只投递改绑、丢掉之前的下行（低）**：别的线程因高水位判死链路、通道还没关时，I/O 线程读到的 `ToClient`（如冻结中请求的 1005 应答）因链路不是 READY 被丢，
  紧随其后的 `PlayerTransfer` 却照常投递，会话改绑后继续跑、那条应答永远收不到。修法：`SceneLink.onFrame` 让 `ToClient` / `PlayerEnterResult` / `PlayerKicked` 与
  `PlayerTransfer` 统一「握手成功过就投递」，过期帧由会话线程按绑定过滤（没有采用「给改绑帧带链路已死标记」的方案）。`SceneLinkTest` +1，并把从未握手的用例扩到四种帧。

复核判为不成立、作为已知现象记录：

- 上行请求与 `PlayerTransfer` 在链路上交叉（gate 改绑前把请求转给 S，S 已移除实例）被 S 静默丢。S 也无法应答——应答会排在 `PlayerTransfer` 之后、被 gate 当迟到帧丢掉；
  与 R-J4 同类、只是早一跳（约一个 RTT），基线 `ProcessClientPlayerMessage` 同样丢。§5.7「改绑前已转给 S 的请求由 S 应答」只对 S 处理时实例还在的请求成立。
- 冻结上界在病态分区下可以超过约 15 s：取连接的 Druid maxWait（3 s）不在事务超时内，COMMIT 只受 socketTimeout（10 s）约束，探测截止之后还至少读一次（K5）。
  安全性不受影响（K5），D4 / §0.6 的「≈ 15 s」按正常的库故障计。
- `undeliverable` 含断线之后才报的（K31）；Lost 在 gate 计 `kicked`（K33）；异步写失败不计 `link_gone`（K17）。

### 11.5 设计正文的订正

- **§5.2「结局投递被拒」段、§5.5 表「停服」行、§8.2 R-J1**：正常停服路径在关存储池之前等在途交出 / 探测的结局处理完（K40），存储线程代为释放只兜「逻辑线程先停」的情形。
  R-J1 剩下：E+1 已提交、`PlayerTransfer` 已写进 socket 而 gate 进程随即崩溃；停服预算内结局没处理完（记 ERROR）；`PlayerTransfer` 的异步写失败回调在存储池关闭之后才到
  （Netty 在链路关闭后紧接着触发，极少见）。都只等 E+1 的租约过期（至多 30 s），库里是冻结快照。
- **§5.2**：语句超时的实现方式见 K1，探测截止见 K5。**§5.4**：显式 scene_id 也写软预占（K11）。**§5.12**：重复 `PlayerTransfer` 不放弃（K30）。
- **§5.5 第 4 条 token**：实现为按引用比对在途对象 `PlayerSwitch`（§11.2）。
- **§5.15**：PARITY「mmorpg 待做（可选）」按 Q8 只登记 B-1、B-3、B-7、B-8（不是 B-1–B-4）。清单漏了 PARITY 各功能行里「冻结闸随 5.2」的待做项：
  货币、属性加点、背包、任务、放技能、宝宝、通用资产通道七行一并改为已接入（放技能记为有意不照搬基线对冻结施法者的早退，K28）。盘点（`docs/porting/inventory/`）的 java 列与勘误没有改：
  盘点是 2026-10-02 的参考快照（roadmap 头注「以代码为准」），勘误留在 §8.3。architecture.md 的 §10 原本就没有列跨节点换图，改为在已补上的括注里登记。
- **§6.2**：nacos profile 下经注册中心找 scene-manager 没做（K16）。
- **§7.2**：冻结时长桶见 K18；gate 多 `invalid`（K29）；存储写多 `handed_off`（K6）；勾稽按 K17、K31 调整；另有 `xm_scene_link_dropped_total{reason="write_failed"}`、
  `xm_scene_moves_total{result="frozen"}`、`xm_scene_team_follow_total{result="switching"}`、`xm_scene_channel_relocations_total{result="switching"}`、
  `xm_gate_link_frames_total{type="player_transfer"}` / `xm_scene_link_frames_total{type="player_transfer"}`（按帧类型自动出现）。指标全集以 architecture.md §11 为准。
- **§10.8「改造」**：见 K39。

### 11.6 验证

- 全量 `clean install`（真 Redis / MySQL / Kafka 集成测试打开；xm-battle-engine 与并行批次在建的 xm-battle 不在内）共 3053 个测试，唯一的错误是 4.5 起就有的 xm-api
  `SceneAssetOpClientsTest` 首次建连计时用例（在 HEAD 导出的代码上同样失败、单跑通过），已另行放宽「第一次调用」的预算修好（之后 xm-api 31 个全绿）。
  评审修复后 `-pl xm-scene,xm-gate install`（真 Redis / MySQL）全绿：xm-scene 738 个（1 个默认跳过的基准）、xm-gate 161 个，各含工作区里其他批次未提交的 3 个用例，
  本批代码对应 xm-scene 735 个、xm-gate 158 个；xm-player-store 55 个、xm-scene-manager 191 个、xm-robot 129 个。
- 双节点切片（`XM_RUN_MODE=dev XM_SCENE_NODES=2`）：robot `cross-node` 34 / 34 共 4 轮（第一轮单独一套切片，另三轮连跑在第二套上），修复后的新包上再跑一轮 34 / 34、smoke 3 / 3，
  两个 scene 与 gate 的日志没有 ERROR。连跑三轮的指标：9 次交出，scene `transfers{handed_off}` 9 = gate `scene_transfers{rebound}` 9 = `{entered}` 9 = 目标节点
  `transfer_enters{ok}` 9；scene-manager `switch{ok}` 9、`{not_found}` 3 = scene `switch_resolves{rejected}` 3（不存在的 scene_id 那一步）；冻结后改动、冻结闸拒绝、
  链路丢帧都是 0；最长冻结 243 ms。smoke / reconnect / team（碰巧同节点）/ guild-economy 在双节点切片上也过，movement 见 K39。
  唯一的 ERROR 在 xm-guild 停服时（scene 节点先停，Dubbo 重连资产端口失败），与 5.2 无关。
- 单节点切片：smoke 3 / 3、movement 20 / 20、skill 15 / 15、team 38 / 38、reconnect 9 / 9、guild-economy 81 / 81。
