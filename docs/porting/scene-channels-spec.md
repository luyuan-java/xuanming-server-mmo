# 主世界多频道（批次 5.1）移植统一规格：场景实例登记、频道铺设、进场选频道、扩缩容 / 再平衡 / 孤儿清理

> **基线**：mmorpg `26ceb70ca`，与 `contract/SOURCE.properties:4` 的 `mmorpg.commit` 相同。Java 侧以 `571b194` 为准。
> 写作时工作区里有批次 4.5（帮会经济）未提交的改动，其中与本批相关的是：`xm-api/src/main/proto/xm/api/node_directory.proto`
> （`SceneNodeInfo` 已追加 `rpc_host = 7; rpc_port = 8`）、`xm-discovery/.../NodeDirectory.java`（新增 `findAsync`）、
> `xm-api/.../DubboGroups.java`（新增 `SCENE_ASSET`）、`xm-api/.../asset/{IsolatedDubboModule,SceneAssetOpClients}.java`（未跟踪）、
> `tools/local/start-slice.sh`。这些文件的行号按当前工作区给出，提交后可能漂移，所以引用时同时写出类名 / 方法名。
>
> **路径怎么读**
> - 不带目录的 `world_init.go`、`world_autoscale.go`、`world_rebalance.go`、`world_rebalance_debug.go`、`orphan_cleanup.go`、`node_selection.go`、
>   `load_reporter.go`、`leader_gate.go`、`enterscenelogic.go`、`createscenelogic.go`、`destroyscenelogic.go`、`scene_atomic.go`、`instance_lifecycle.go`、
>   `scene_node_client.go`、`reentry_barrier.go`、`agones_binding.go`，以及同目录的 `*_test.go`：都在 mmorpg `go/scene_manager/internal/logic/` 下。
> - `config.go`：`go/scene_manager/internal/config/`；`scene_type.go`、`errors.go`、`constants/reentry_barrier.go`：`go/scene_manager/internal/constants/`；
>   `metrics.go`：`go/scene_manager/internal/metrics/`；`scene_manager_service.go`：`go/scene_manager/`；`scene_manager_service.yaml`（下称 yaml）：`go/scene_manager/etc/`；
>   `safego.go`：`go/shared/safego/`。
> - C++：`scene_node_service.cpp` 在 `cpp/nodes/scene/handler/grpc/`；`scene_handler.cpp` 在 `cpp/nodes/scene/handler/rpc/`；`scene_event_handler.cpp` 在
>   `cpp/nodes/scene/handler/event/`；`player_scene_handler.cpp` 在 `cpp/nodes/scene/handler/rpc/player/`；`player_lifecycle.cpp`、`player_scene.cpp`、`player_team.cpp`
>   在 `cpp/libs/services/scene/player/system/`；`config.cpp` 在 `cpp/libs/engine/config/`；`node.cpp` 在 `cpp/libs/engine/core/node/system/node/`；
>   `etcd_manager.cpp` 在 `cpp/libs/engine/core/node/system/etcd/`。
> - proto 用 mmorpg 行号：`proto/scene_manager/scene_node_service.proto`、`proto/scene/{scene,scene_info,player_scene}.proto`。
>   `message_id.txt` 指 `xm-proto/src/main/resources/contract/message_id.txt`（**号 N 在第 N+1 行**）。
> - 以 `go/`、`cpp/`、`proto/`、`robot/`、`generated/`、`deploy/`、`docs/design/world-channel-*.md`、`PROGRESS.md` 开头的路径在 `D:\work\mmorpg` 下；
>   以 `xm-`、`docs/`（上一条除外）、`tools/`、`config-data/`、`contract/`、`PARITY.md`、`AGENTS.md` 开头的路径在 `D:\work\xuanming-server-mmo` 下。
>
> **本稿的来历**：由两份分区稿合并而成——基线分区稿（算法、数据、客户端可见效果、robot 覆盖）与 Java 分区稿（现状、缺口、设计、测试）。
> 两份稿子之间、以及它们与代码、inventory、设计稿不一致的地方都回到代码重新核对过，更正集中在 §7.3。Java 设计在分区稿基础上做了几处简化与收紧
> （计划改为节点按版本号拉取、分配只读目录、规划写入加版本号 CAS、排空改派加默认大世界回落、组队跟随不进排空频道），理由写在对应小节与 §8。
> 本稿只读代码，除本文件外没有改任何文件。

---

## 0 概览与范围（含与 5.2 / 5.3 / 5.5 的边界）

### 0.1 结论速览

- **基线是中心编排**：scene_manager 的领导者在 Redis 里维护「每个 (zone, World 图) 的期望频道数 + 频道集合 + 场景 → 节点映射」，按 FNV 哈希把频道铺到
  主世界节点，命令式地对 C++ 节点发 `CreateScene` / `DestroyScene`；进场时用一段 Lua 在一张图的全部活频道里**原子预占**人数最少的那个；
  另有空频道再平衡、按人数自动扩缩容（默认关）、World 表删图后的孤儿清理（`world_init.go:129-338`、`:441-513`；`scene_atomic.go:69-101`；
  `world_rebalance.go:54-205`；`world_autoscale.go:164-257`；`orphan_cleanup.go:42-90`）。
- **Java 现状是节点自建**：每个 scene 节点启动时为 World 表每张图建 **1 个**场景，scene_id 用本节点（按 zone 租约）的雪花；运行期不再建、不再销毁；
  scene-manager 只读 Redis 节点目录、挑人数最少的场景，**不预占**（`SceneNode.java:280-283`；`SceneWorld.java:177-183`；`SceneAssigner.java:29-30`、`:114-129`）。
  没有频道计划、扩缩容、再平衡、选主、中心发号。
- **本稿建议**（§4）：
  1. 频道计划集中在 Redis（每个 zone 一张 HASH，值是 Java 自有的 `WorldChannel` pb），只由 scene-manager 的**分 zone 领导者**用一段「令牌 + 版本号 CAS」
     的 Lua 写；scene_id 由 scene-manager 的全服雪花租约发。
  2. scene 节点**每秒查一次计划版本号**，变了就读回属于自己的那份、在逻辑线程上建 / 排空 / 销毁场景（启动时先同步拉一次）；不需要 scene-manager → scene 的 RPC。
  3. 分配只在目录里「没在排空」的场景中挑，用带 TTL 的**软预占**防扎堆。
  4. 5.2 之前用「**每节点覆盖**」模式：每个活节点对每张 World 图至少有 1 个 ACTIVE 频道，63 按地图换场景永远能在本节点完成；
     缩容与死节点后的重铺都在**同节点**内完成，不需要 5.2 的跨节点交接或 5.5 的跨节点改派。
- **客户端可见的变化**（§0.5）：同一张图分成多个频道（不同 scene_id）；63 只带当前地图时可能换到同图更空的频道（与基线一致，Java 现在一律不动）；
  缩容时频道里的人收到一条非自己发起的 79（同图、新 scene_id、坐标保留；基线是落到默认大世界）；63 指定一个排空中的频道回 3023（基线放行）。
  契约里**没有**频道列表消息（§2.6）。

### 0.2 盘点 id 与落点

| 盘点 id | 基线落点 | Java 现状（盘点原文） | 5.1 交付 |
|---|---|---|---|
| scene-instance-registry（`docs/porting/inventory/scene-core.md:160-170`） | `scene_node_service.cpp:19-125`（gRPC，SM 用的入口）、`scene_handler.cpp:849-976`（muduo 旧入口，「两处必须同步」，`:896-898`）、`scene_event_handler.cpp:30-56` | partial：只在启动时建，无运行期 Create / Destroy、无 mirror / dungeon / creators | scene 节点按计划在逻辑线程上建（带号、按 scene_id 幂等、拒 0 与非世界图）/ 标排空 / 改派 / 销毁；`Scene` 接收完整 `SceneInfoComp`（§4.10） |
| world-channels-from-tables（`scene-core.md:172-182`） | `world_init.go:129-338`、`world_autoscale.go`、`world_rebalance.go`、`node_selection.go` | partial：每节点每图 1 个、无频道数配置 | 频道计划 + 期望数（配置播种、Redis 权威）+ 领导者铺设（§4.6） |
| sm-node-registry-load（`scene-manager-match.md:22-32`，本批只用子集） | `load_reporter.go`、`node_selection.go`、`scene_node_client.go` | partial：Redis 目录，无角色字段 | 不移植负载分 / 用途池；目录加 `draining` 与 `applied_plan_version`，预留 `scene_node_type`（§4.3） |
| sm-world-channel-provisioning（`:34-44`） | `world_init.go`、`createscenelogic.go:133-159`、`DesiredWorldChannelCount` | partial | §4.6（不做数据面按需铺设，D13） |
| sm-enter-scene-allocation（`:46-56`，只取「选频道」一段） | `enterscenelogic.go:1267-1298`、`world_init.go:441-513`、`scene_atomic.go:69-101`、`:235-278` | partial：不预占、按 scene_id 进指定场景不支持 | `SceneAssigner` 排除排空频道 + 软预占（§4.11）；63 同图跳频道（§4.12） |
| sm-world-channel-rebalance（`:166-176`） | `world_rebalance.go`、`world_rebalance_debug.go`、`orphan_cleanup.go` | **not_applicable**（`:173`）——前提「场景归节点所有」在 5.1 后不成立 | 死节点重铺（§4.9）、择机迁移（只在 hash 覆盖模式下，§4.8）、孤儿清理（§4.9） |
| sm-world-channel-autoscale（`:178-188`） | `world_autoscale.go`；配置 `config.go:297-331` | missing | §4.7（缺省关） |
| sm-cluster-ops 的「选主 / 发号围栏」部分（`:190-200`） | `scene_manager_service.go:94-132`、`:250-260`；`leader_gate.go` | not_applicable（`:197`） | 随 5.1 做（§4.4、§4.5）；热关停已有（`architecture.md:408-426`）；Agones 不移植 |

### 0.3 范围

**5.1 做**：上表「5.1 交付」一列；63「只带当前地图」改为在本节点同图频道间挑最空的（§4.12）；组队跟随不进排空频道（§4.13）；指标、健康组件、文档与 PARITY 登记。

**5.1 不做**：跨节点换场景 / 换频道（5.2）；副本、镜像与空闲回收（5.3）；跨 zone（5.4）；有人的频道跨节点改派、整节点疏散、再入屏障（5.5）；
节点用途分池与负载分（基线 `node_selection.go:37-77`、`load_reporter.go:264-274`，随 5.3 评估）；Agones（Java 无 K8s / Agones 部署形态，
`scene-manager-match.md:197`）；频道容量上限（基线也没有，§7.1 B16）。

### 0.4 与 5.2 / 5.3 / 5.5（及 4.3 / 4.5 / 5.4）的边界与 5.1 要留的钩子

| 基线段落 | 归属 | 5.1 留下的钩子 / 约束 |
|---|---|---|
| 频道集合、期望数、铺设、FNV 落点、补建锁（`world_init.go:25-338`；`world_autoscale.go:68-117`） | **5.1** | — |
| conf 选频道 + 原子预占 + 成对退还（`world_init.go:441-513`；`scene_atomic.go:235-278`；`instance_lifecycle.go:365-379`；退还出口见 §2.3） | **5.1** | `ChannelSelector.select(zone, conf, excludeSceneId, playerId)` 供 login 进场与 5.2 的跨节点换场景共用；预占以 player_id 为成员、带 TTL，5.2 的拒绝出口**不需要**成对退还（D7） |
| 显式 scene_id 的解析（`enterscenelogic.go:1177-1248`） | **5.1**（解析与排空校验），**5.2**（之后的换手） | 排空中的频道一律拒绝显式进入（D11）；同节点的显式 63 在 5.1 内完成 |
| 同落点重连判定、换手门 18、ReleasePlayer、epoch 铸造、location CAS、推路由与回滚（`enterscenelogic.go:492-767`） | **5.2** | `SceneWorld.resolveSwitchTarget` 改为返回 `SwitchTarget{Local(scene) / Remote(conf, sceneId, nodeId) / Reject(tip)}`；5.1 把 Remote 映射为 3023（与现在相同），5.2 接上 scene-manager 往返；位置记录的写入点不变（`SceneWorld.java:473`） |
| 再平衡：择机迁移空频道（`world_rebalance.go:195-201`） | **5.1** | 只在 `coverage=hash` 下执行；5.2 上线后把缺省覆盖模式切到 `hash`（D6） |
| 死节点上频道的改派（urgent，`world_rebalance.go:168-193`；懒改派 `world_init.go:471-506`；补建内改派 `:259-296`、`:307-329`） | 代码路径 **5.1**，判死证据与再入屏障 **5.5** | Java 把「节点死了」简化为「目录缺席满 `dead-node-grace`」，删记录、发新号重铺（D4、D12）；规划器留 `NodeAvailability` 判定口，5.5 的节点级排空标记接在这里（被标记的节点不再接新频道） |
| `BeginSceneDrain` 与改派链路（`player_lifecycle.cpp:2149-2363`） | **5.5**（scene-drain-relocate） | 5.1 的 scene 侧有自己的「同节点改派」实现（§4.10.3），抽成 `ChannelEvacuator` 接口；跨节点实现随 5.5 + 5.2 做，届时可放开缩容的「同节点兄弟」限制与排空超时回滚 |
| `drainOrDestroyChannel` / `finishDrainedChannel` 的 SM 侧状态机（`world_autoscale.go:368-476`） | **5.1** | 收尾时调用镜像级联钩子（5.3） |
| 世界频道之外的场景：`createInstance`、镜像、`instances:zone:{z}:active`、空闲回收、`destroyInstance*`、SM `DestroyScene` RPC | **5.3** | `WorldChannel.kind`（1 = 主世界）；`Scene` 构造接收完整 `SceneInfoComp`；`MirrorSources` 接口（缩容 / 迁移跳过镜像源，查询失败按「是」处理，同 `world_autoscale.go:281-293`）与收尾时的级联销毁钩子（同 `:437-457`、`world_rebalance.go:299-330`），5.1 实现为空；目录字段 `scene_node_type`（只占号） |
| 死节点判定、`death_at`、推迟摘除、死节点副本收尾、`playerLocationOwnerDead/Gone`、整节点疏散（`reentry_barrier.go`；`load_reporter.go:286-` `removeNodeFromRedis`；`player_lifecycle.cpp:2186-2205`） | **5.5** | 5.1 不做再入屏障（D12）；节点号被复用而旧进程仍活着的窗口（gate 按节点号复用链路，`GateNode.java:169-180`）归 scene-node-loss-handling |
| 组队跟随进队长所在频道（`player_team.cpp:475-482`） | 4.3 已做本节点版本（`TeamFollowService.java:17-34`） | 跟随不进排空频道（§4.13）；队长在别的节点仍不跟（同 4.3，跨节点随 5.2） |
| scene 的 Dubbo 提供方（4.5 在途：`SceneAssetOpService`、`rpc_host / rpc_port`） | **4.5** | 本稿的计划下发**不依赖**它（拉取，D3）；若改用推送（Q2）则与 4.5 共用同一个提供方端口与 `IsolatedDubboModule` |
| 跨 zone 预检 `rejectTravelToUnopenedMap`（`enterscenelogic.go:967-994`，读 `SCARD world_channels`） | **5.4** | 计划按 zone 分键，可直接回答「(zone, conf) 有没有 ACTIVE 频道」；scene_id 改为全服唯一（§4.5），跨 zone 引用不撞号 |

### 0.5 客户端可见效果速览

| 情形 | 基线 | Java 现在 | Java 5.1 之后 |
|---|---|---|---|
| 79 `EnterSceneS2C.scene_info` | `scene_config_id` = World 行的 `scene_id`，`scene_id` = 频道雪花号，mirror / dungeon 0，creators 空（`scene_info.proto:4-11`；`scene_node_client.go:84-86`） | 同形状，scene_id 由节点按 zone 的雪花发（`Scene.java:28-35`） | 同形状，scene_id 由 scene-manager 全服租约发（§4.5） |
| 首登 / 干净登出后再登 | 默认大世界（World 第一行）人数最少的频道（§2.1） | 默认大世界人数最少的场景（`SceneAssigner.java:77-79`） | 同左，候选排除排空频道、加软预占 |
| 断线重连 / 顶号 | 只用 location 定 zone，仍落默认大世界人数最少的频道（PARITY 有意差异 ⑥，`PARITY.md:91`） | 回原实例，不在了按原地图（`SceneAssigner.java:64-79`） | 同左；原实例在排空中 → 按原地图选 |
| 63 只带当前 `scene_config_id` | SM 在全 zone 该图活频道里预占最少者：挑回原频道 → 不发 79；挑到别的 → 51 / 79 / 21，坐标保留（§2.6） | 一律留在原场景、不发 79（`SceneWorld.java:238-240`） | 本节点该图 ACTIVE 频道里挑最少者（含自己），平局留原地；挑到别的 → 51 / 79 / 21 / 47，坐标保留 |
| 63 指定一个排空中的 scene_id | 放行（§7.1 B5） | 不存在排空 | 3023（D11） |
| 63 指定别的节点上的 scene_id | 放行（5.2 换手） | 3023 | 3023（直到 5.2） |
| 缩容 | 先存盘，旁人收到 51，本人收到**默认大世界**的 79 / 21，换图时落出生点；客户端经历一次加载；没有 tip（§7.1 B1） | 没有缩容 | 旁人收到 51，本人收到**同图**新频道的 79 / 21 / 47，坐标保留，不存盘、不换进程；没有 tip（D8） |
| World 表删图后的孤儿频道 | 被 DestroyScene → 同上落默认大世界 | 不适用（场景随节点重启消失） | 同节点默认大世界、落出生点（§4.10.3） |
| 再平衡 | 只迁空频道，不可见 | 无 | 同左（只在 hash 模式） |
| 31 `SceneInfoS2C` | `repeated` 字段，但只放当前场景一条（`player_scene_handler.cpp:203-205`；`player_scene.proto:28-31`） | 同左（`ClientRequestHandler.java:106-108`） | 不变（不借这个 repeated 发频道列表，N3） |

---

## 1 基线：场景实例与频道的数据和算法

### 1.1 Redis 键全集（本批相关）

scene_manager 是 zone-agnostic 的：一个进程管理 etcd 里出现过的所有 zone，自己按 `c.ZoneId` 在 etcd 注册供 C++ 发现（`scene_manager_service.go:197-210`）。
除注明 TTL 的外均无 TTL。键名都**不带 hash tag**；`scene_atomic.go:66-68` 声称多键 Lua「Cluster-safe」，实际候选的键分属不同槽，只在单机 Redis（yaml `:19-22` `Type: node`）上成立（§7.1 B17）。

| 键 | 类型 / 值 | 写者 | 读者 | TTL | 出处 |
|---|---|---|---|---|---|
| `world_channels:zone:{z}:{conf}` | SET，scene_id 十进制 | 补建 SADD、缩容 SREM、孤儿清理 DEL | 选频道、再平衡、扩缩容、跨 zone 预检（SCARD） | 无 | `world_init.go:25`、`:224`；`world_autoscale.go:327`；`orphan_cleanup.go:206`；`enterscenelogic.go:980` |
| `world_channels:desired:zone:{z}` | HASH conf → 期望频道数 | 首次访问 HSETNX 播种；伸缩时 HSET（钳到 `[max(1,Min), Max]`）；孤儿清理 HDEL | 补建、扩缩容 | 无 | `world_autoscale.go:38`、`:73-117`；`orphan_cleanup.go:203` |
| `world_channels:draining:zone:{z}:{conf}` | SET，排空中的 scene_id | 缩容 SADD、收尾 SREM、孤儿清理 DEL | 排空收敛、孤儿清理 | 无 | `world_autoscale.go:49`、`:353`、`:464`；`orphan_cleanup.go:127`、`:204` |
| `world_channels:cooldown:zone:{z}:{conf}` | STRING `"1"` | 每次伸缩后 SETEX | 扩缩容决策 | `CooldownSeconds`（≤0 → 120） | `world_autoscale.go:44`、`:491-504` |
| `scene:{id}:draining` | STRING `"1"` | 开始排空 SETEX | 只有扩缩容决策读（冗余，B4）；孤儿清理 DEL | `DrainTimeoutSeconds`（≤0 → 300） | `world_autoscale.go:41`、`:189`、`:345-351`；`orphan_cleanup.go:198` |
| `world_init:lock:zone:{z}` | STRING uuid 令牌 | `SETNX … EX 60` | 补建互斥；属主校验 Lua 释放 | 60 s | `world_init.go:32-35`、`:55-61`、`:67-102` |
| `scene:{id}:node` | STRING node_id | 补建、各种改派、迁移；收尾 / 清理 DEL | 全部路径；它就是「场景还在不在」的权威 | 无 | `world_init.go:216-217`；`createscenelogic.go:21` |
| `scene:{id}:zone` | STRING | 补建 SET | `GetSceneZone`（显式进入、跨 zone 判定） | 无 | `world_init.go:222`；`changesceneutil.go:210-217` |
| `instance:{id}:player_count` | STRING int（世界频道与副本共用） | 补建置 0；预占 INCR；离场 / 换场景 `INCRBY -1` 后负数改 0 | 选频道、扩缩容、再平衡 | 无 | `createscenelogic.go:20`；`world_init.go:230`；`instance_lifecycle.go:355-379` |
| `node:zone:{z}:{n}:scene_count` | STRING int | 补建 INCR、迁移 ±1、收尾 / 清理 −1 | 负载分 | 无 | `load_reporter.go:24`；`world_init.go:232-233`；`world_rebalance.go:273-280` |
| `node:zone:{z}:{n}:player_count` | STRING int | 预占 Lua INCR；`DecrInstancePlayerCount` 钳 0 | 负载分 | 无 | `load_reporter.go:25`；`scene_atomic.go:94` |
| `node:zone:{z}:{n}:scenes` | SET 反向索引 | 补建 SADD；改派两边各改一次 | 死节点收尾（5.5）、诊断 | 无 | `createscenelogic.go:37-40`；`world_init.go:239`、`:528-543` |
| `node:zone:{z}:{n}:scene_node_type` | STRING 0..3 | 只有领导者镜像 etcd 的值 | 用途过滤 | 无 | `load_reporter.go:30`、`:246-251` |
| `scene_nodes:zone:{z}:load` | ZSET，成员 node_id，分 = `int64(α·scene_count + β·player_count)` | 只有领导者，每 5 s；判死时 ZREM | `IsNodeAlive`、用途过滤（分数升序） | 无 | `load_reporter.go:23`、`:230-274` |
| `node:zone:{z}:{n}:death_at` | STRING Unix 毫秒 | 判死（5.5） | 再入屏障 | 600 s | `reentry_barrier.go:39`；`constants/reentry_barrier.go:80` |
| `scene:{id}:mirrors` / `:mirror` / `:source` | SET / `"1"` / id | 5.3 | 缩容排除镜像源、级联销毁 | 无 | `createscenelogic.go:23-36` |
| `scene:{id}:agones_gs` | STRING | 只在 Agones 模式下写 | 名额归还 | 无 | `agones_binding.go:25` |
| `scene_manager:leader:lock` | 选主锁 | shared/leader | 全部变更类后台循环 | 30 s | `scene_manager_service.go:94-101`；`config.go:65-78` |

不变量与惯例：
- **世界频道不在 `instances:zone:{z}:active` 里**，所以空闲回收与死节点收尾都不碰世界频道（`createscenelogic.go:19`；单测 `logic_test.go:2847` `TestReconcileDeadNode_PreservesWorldChannels`）。
- 节点号只在 zone 内唯一，凡节点维度的键都带 zone（`scene_node_client.go:24-25`；`load_reporter.go:105-107`）。
- 计数键「`INCRBY -1`，结果为负就 SET 0」不是原子的（`instance_lifecycle.go:362-379`）。

### 1.2 节点发现、负载分、用途过滤、存活判定（sm-node-registry-load 的本批子集）

- **etcd**：前缀 `SceneNodeService.rpc/`（`load_reporter.go:32`），C++ 注册键 `SceneNodeService.rpc/zone/{z}/node_type/{t}/node_id/{id}`（`etcd_manager.cpp:47-57`）；
  值是 `NodeInfo` 的 protobuf JSON，用到 `nodeId / nodeType / sceneNodeType / endpoint / grpcEndpoint / zoneId`（`load_reporter.go:67-85`）。
  `sceneNodeType` 来自 C++ 配置 `SceneNodeType`，可被环境变量 `SCENE_NODE_TYPE` 覆盖（`config.cpp:239-260`），`node.cpp:529` 写进 NodeInfo；
  0 = 主世界、1 = 副本、2 = 跨服主世界、3 = 跨服副本（`scene_type.go:9-19`）。超出 0..3 照样参与路由，但打 ERROR（`load_reporter.go:194-214`）。
- **list-watch**：`StartLoadReporter` 先 `fullSync`（失败 3 s 重试）→ 置 `knownNodesSynced` → 从 rev+1 watch；watch 中断或领导权变化（`loadReporterResync`）就回到 fullSync
  （`load_reporter.go:604-625`、`:805-852`）。内存镜像 `knownNodes` 以 etcd 键为键（`:93-103`）。
- **身份歧义**：同一 (zone, node_id) 两条及以上注册（`load_reporter.go:105-123`）——选点、改派、发 RPC 一律拒用（`node_selection.go:46-50`；`world_init.go:368-372`、`:459-463`；
  `world_rebalance.go:163-167`；`scene_node_client.go:167-171`）；`IsNodeAlive` 对歧义身份返回 false（`load_reporter.go:1056-1058`）。
- **活跃 zone** = knownNodes 里出现过的 zone，不论节点类型（`load_reporter.go:579-595`、`:675-682`）。扩缩容（`world_autoscale.go:149`）、周期再平衡（`load_reporter.go:880`）、
  调试端点（`world_rebalance_debug.go:53`）只遍历活跃 zone。
- **负载分** = `NodeLoadWeightSceneCount`(1.0) × scene_count + `NodeLoadWeightPlayerCount`(0.01) × player_count；α ≤ 0 取 1，β < 0 取 0；ZADD 前截断成 int64；
  **只有领导者写** ZADD 与类型镜像，指标每副本都发（`load_reporter.go:226-274`；`config.go:120-128`）。每 5 s 对全部 knownNodes 重算（`LoadReportInterval`，`:31`；`:998-1020`）。
- **`IsNodeAlive` 三态**（`load_reporter.go:1031-1071`）：歧义 → false；ZSCORE 命中 → true；`redis.Nil` → false（唯一的「已死」证据）；其它错误 → **按存活**并打 ERROR。
- **`isNodeGoneFromRegistry`**：完成过首次全量同步、且 knownNodes 里该身份 0 条才为真（`load_reporter.go:187-192`）。
- **用途过滤 `getNodesForPurpose`**（`node_selection.go:37-77`）：读负载 ZSET 全部成员（分数升序）→ 跳过歧义 → 有类型镜像且 `MatchesPurpose` 的、以及**没有类型镜像（未分类）**的
  都进 preferred（World = 0 或 2，Instance = 1 或 3，`scene_type.go:32-54`）→ preferred 非空就返回 → 否则严格模式（`StrictNodeTypeSeparation`，缺省 true，`config.go:109-118`、
  yaml `:159`）返回 nil，非严格回落全池。`GetBestNodeForPurpose` 取第一个（`node_selection.go:79-89`）。

### 1.3 选主与领导者闸门

- Redis 锁选主：键缺省 `scene_manager:leader:lock`（`scene_manager_service.go:94-97`），TTL `LeaderLockTTLSeconds` = 30 s、续期间隔 TTL/3（`config.go:65-74`；
  注释推导了「单次续期最坏 8 s，须 < TTL/3」）。状态变化时：置 `is_leader` gauge、降级时清掉只有领导者维护的 gauge、踢一次 fullSync（`scene_manager_service.go:103-117`；
  `metrics.go:431-435`；`leader_gate.go:50-61`）。`LeaderEligible=false` 不竞选（金丝雀，`scene_manager_service.go:122-132`；`config.go:80-86`）；SIGTERM 时属主校验放锁（`:124-127`）。
  没装判定函数一律视为领导者（`leader_gate.go:26-35`）。
- **只有领导者做**：fullSync 的变更段（陈旧清扫、推迟摘除、死节点收尾、补建 + 再平衡、孤儿清理，`load_reporter.go:684-721`）；watch PUT 的补建与再平衡（`:936-955`）；
  watch DELETE 的摘除与再平衡（`:978-994`）；周期再平衡（`:871-883`）；自动扩缩容（`world_autoscale.go:143-148`）。再平衡执行每条迁移前都重查领导权（`world_rebalance.go:72-78`）。
- **任何副本都会执行的变更**（数据面）：EnterScene 的懒改派（`world_init.go:483-505`）、SM `CreateScene(MAIN_WORLD)` 的按需补建（`createscenelogic.go:144-148`，也受 zone 锁约束）。

### 1.4 scene_id 发号与围栏

- `SceneIDGen.Generate()`：snowflake，worker id 由 etcd 租约持有（`world_init.go:187-194`；`scene_manager_service.go:231-249`）。
- 租约**确认丢失**（`Lost()` 关闭）：先 `Fence()` 发号器 → 让位 → flush Kafka → `os.Exit(1)`（`scene_manager_service.go:250-260`）。租约抖动不触发 Lost；
  距上次水位写成功超过 2 h 时 `Generate` 返回 `ErrWatermarkStale`，建场景整体失败但进程不退（`:239-242`）。
- 铺设路径遇发号失败立即停止本轮铺设（`world_init.go:190-194`）。撞号的后果被注释点明：`scene:{id}:node` 是裸 SET，两个场景共用一个 id 会互相覆盖路由（`scene_manager_service.go:248-249`）。

### 1.5 频道铺设 `initWorldScenesForZone(ctx, zone, confIds, waitForLock)`（`world_init.go:129-338`）

#### 1.5.1 触发点

| 触发 | waitForLock | 出处 |
|---|---|---|
| 领导者 fullSync：对每个活跃 zone 传全部 World conf，紧接着再平衡 | false | `load_reporter.go:708-713` |
| 领导者收到 watch PUT 且该 etcd 键**此前不在**（新节点，不论类型） | false（跑在 etcd 事件 goroutine 上，不能忙等） | `load_reporter.go:938-946` |
| 自动扩容（期望数 +1 之后） | true | `world_autoscale.go:243-247` |
| SM `CreateScene(MAIN_WORLD)` 找不到频道（任意副本） | true | `createscenelogic.go:144-148` |

World conf 列表 = World 表各行的 `scene_id`，按表序（`world_init.go:578-588`）。本地数据 16 行，`scene_id` 依次为 1..16（`generated/tables/world.json`）。

#### 1.5.2 zone 锁（`world_init.go:63-102`）

1. 每次调用生成新 uuid 令牌，`SETNX key token EX 60`。拿到 → 继续；Redis 出错 → **跳过本轮**（不无锁裸跑）。
2. 锁被占：`maxWait = 0` 立即放弃；否则每 200 ms 重试，直到 10 s 用完或 ctx 取消（`:43-44`、`:67-85`）。
3. 释放用属主校验 Lua（`:55-61`）；释放时发现属主不对 = 本轮超过 60 s、互斥已失效，打 ERROR（`:95-101`）；释放失败只记日志，等 TTL（`:90-93`）。

#### 1.5.3 算法（拿到锁之后）

1. `nodes = getNodesForPurpose(zone, World)`；为空 → ERROR 返回（`:144-149`）。`sort.Strings`——**字典序**，"10" 排在 "9" 前（`:150-153`）。复制为本轮 `liveNodes`，另建空的 `unreachableNodes`（`:158-165`）。
2. 对每个 conf：
   1. `channelCount = DesiredWorldChannelCount(zone, conf)`（`:171-174`，§1.5.4）；`existingCount = len(SMEMBERS)`（`:176-178`）。
   2. **补缺** `i = existingCount .. channelCount-1`（`:180-244`）：`liveNodes` 空 → 跳出；`sceneId = SceneIDGen.Generate()`，失败 → 跳出；
      `target = assignNodeByHash(conf*1000 + i, liveNodes)`（`:197`）；Agones 预占（非 Agones 模式原样返回 target，`agones_binding.go:280-289`）；
      依次 `SET scene:{id}:node`（失败 continue，**这个号作废**）→ `SET scene:{id}:zone` → `SADD world_channels`（失败 continue）→ `SET player_count 0` → `INCR scene_count` → `SADD node:…:scenes`。
      **无事务、不回滚**。
   3. **确保实体存在**：对集合里**每一个**成员都发 `CreateScene`，不论是不是本轮新建——这是 C++ 重启后恢复频道实体的唯一手段（`:246-333`）：
      - `GET scene:{id}:node` 为空 → 跳过（`:251-256`）。
      - 目标节点本轮已不可达：仍在 etcd 注册表 → 归属不动、跳过（`:258-269`）；注册表没了但仍在负载集 → 跳过（`:270-276`）；
        否则 `newNode = assignNodeByHash(conf*1000 + ensured, liveNodes)`，经 `reassignSceneNode`（内含再入屏障，挡住返回 false 即跳过）改派并转移 Agones 名额（`:277-296`）。
      - `CreateScene(config_id=conf, scene_id)`，**每次限时 5 s**（`:298-303`；`worldInitCreateRPCTimeout`，`:46-51`）。
      - 失败且错误串含 `Unavailable` / `connection refused` / `connectex` / `not found in etcd` / `DeadlineExceeded` / `context deadline exceeded`（`:609-626`）→
        `markNodeUnreachable`：记入本轮不可达、从 `liveNodes` 拿掉、关缓存连接，**不碰负载集**（`:628-656`）；若节点同时「已离开注册表且不在负载集」，按屏障规则改派并在新节点重试一次（同样 5 s，`:309-329`）。
      - 无论成败 `ensured++`（`:332`）。
3. 汇总日志 created / ensured / unreachable（`:336-337`）。

#### 1.5.4 期望频道数

- **`DesiredWorldChannelCount`**（`world_autoscale.go:68-99`）：`HGET`，是 ≥1 的合法整数就返回；否则 `seed = ChannelCountFor(conf)`（<1 当 1）→ `HSETNX`（失败返回 seed）→ 再 `HGET`，读不到合法值返回 seed。
  **种子不钳到 `MaxChannelsPerMap`**，只有 `setDesiredWorldChannelCount` 钳 `[max(1,Min), Max(>0 时)]`（`:101-117`）。
- **`ChannelCountFor`**（`config.go:333-351`）：遍历 `WorldChannelCountByConfId`（键是十进制字符串，值 ≤0 跳过）；没命中用 `WorldChannelCount`（<1 当 1）。
  开发配置 `WorldChannelCount: 1`、`WorldChannelCountByConfId: "1": 16`（yaml `:134`、`:142-143`）——**默认大世界（图 1）16 个频道，其余 15 张图各 1 个，每 zone 31 个频道**；
  实测 `SCARD world_channels:zone:1:1 = 16`（`PROGRESS.md:4844`）。
- **配置只是首次播种的种子，即使扩缩容关闭也一样**：铺设无条件走 `DesiredWorldChannelCount`（`world_init.go:174`），播过种之后改配置不生效（运维须 HDEL 字段，
  `world_autoscale.go:68-72` 注释）；调小也不会减频道——补建只在「已有 < 期望」时加（`world_init.go:181`），没有任何路径删多出来的频道（§7.1 B2）。

#### 1.5.5 FNV 落点 `assignNodeByHash(key, sortedNodes)`（`world_init.go:600-607`）

- 32 位 FNV-1a（`hash/fnv.New32a`），输入是 key 的**十进制 ASCII 串**；`idx = int(Sum32) % len`（64 位平台恒非负）。
- Java 等价：`h = 0x811C9DC5`；对每个字节 `h ^= b; h *= 16777619`（截 32 位）；**`Integer.toUnsignedLong(h) % n`**——Java `int` 有符号，`"2000"` 的哈希 3526271127 ≥ 2³¹，有符号取模会得到负下标。
- 黄金值（本稿用 bash 算术逐字节复算过；实现时再用 Go 单测复核）：

  | 键 | FNV-1a 32 | 十六进制 | mod 2 | mod 3 | mod 4 |
  |---|---|---|---|---|---|
  | 1000 | 580373188 | 0x2297cac4 | 0 | 1 | 0 |
  | 1001 | 597150807 | 0x2397cc57 | 1 | 0 | 3 |
  | 1002 | 613928426 | 0x2497cdea | 0 | 2 | 2 |
  | 1003 | 630706045 | 0x2597cf7d | 1 | 1 | 1 |
  | 1015 | 597297902 | 0x239a0aee | 0 | 2 | 2 |
  | 2000 | 3526271127 | 0xd22ea097 | 1 | 0 | 3 |
  | 16000 | 3950372026 | 0xeb75e4ba | 0 | 1 | 2 |
  | 16015 | 1417201820 | 0x5478c89c | 0 | 2 | 0 |

- **三处用了三种键**（§7.1 B3）：铺设 `conf*1000 + i`（`world_init.go:197`）；补建内改派 `conf*1000 + ensured`（`:281`、`:313`）；懒改派 `conf*1000 + 陈旧列表下标`（`:408`、`:484`）；
  再平衡的期望节点 `assignNodeByHash(sceneId, nodes)`（`world_rebalance.go:159`）。

#### 1.5.6 失败处理与 C++ 重启恢复时序

| 情形 | 处理 |
|---|---|
| zone 锁忙或 Redis 出错 | 后台路径跳过本轮；RPC 兜底最多等 10 s，等不到回「无频道」（`createscenelogic.go:150-158` → `ErrNoAvailableNode`） |
| 没有主世界节点（严格模式） | ERROR 后返回，不建（`world_init.go:144-149`）；设计稿承认扩容此时必败（`docs/design/world-channel-autoscale.md:182-183`） |
| 发号器被围栏 | 停止铺设（`world_init.go:190-194`） |
| CreateScene 超时 / 拒连 | 节点只在本轮剔除、不判死；归属只有在「已离开注册表、已出负载集、屏障已过」时才改派 |
| 持锁超过 60 s | 只打 ERROR，接受可能的重复建频道（`world_init.go:95-101`） |

C++ 重启（属 5.5 边界）：节点换代产生一次 DELETE + 一次新键 PUT，PUT 触发补建、对**所有**频道重发 CreateScene；新进程复用原 node_id 时实体被重建；
node_id 变了则改派要过再入屏障（20 s，`constants/reentry_barrier.go:51-69`），屏障内的 PUT 被挡，之后靠周期再平衡（≤300 s）、下一个 PUT / DELETE、fullSync 或全陈旧时的懒改派来补。

### 1.6 再平衡（`world_rebalance.go`）

#### 1.6.1 触发（都只在领导者上）

fullSync 补建后（`load_reporter.go:711`）；watch PUT 的节点是主世界类型且是新节点或角色变了（`:947-954`）；watch DELETE 的节点是主世界类型（`:986-994`）；
每 `RebalanceCheckIntervalSeconds`（缺省 300，0 关闭，`config.go:199-205`；`load_reporter.go:854-883`）。

#### 1.6.2 规划器 `PlanWorldChannelRebalance`（`world_rebalance.go:115-205`）

1. 预算 `MaxRebalanceMigrationsPerTick`：0 → 关闭（空计划）；<0 → 10；缺省 10（`:127-133`；`config.go:190-197`）。
2. 主世界节点排序成 liveSet；无节点 → 空计划（`:135-143`）。
3. 每个 conf 取 SMEMBERS 并**按字符串排序**（`:145-152`）；对每个成员：`cur = GET scene:{id}:node`，`target = assignNodeByHash(sceneId, nodes)`，相等跳过（`:158-162`）；`cur` 歧义 → 跳过（`:163-167`）。
4. **cur 不在 liveSet**（死了，或活着但角色已非主世界）：人数 > 0 且 `ZSCORE load cur` 命中 → 跳过（fail-closed，不做跨节点热迁移，`:168-178`）；再入屏障挡住 → 本轮不排（`:179-188`）；
   否则进 **urgent**，reason `node_gone`（`:189-193`）。
5. **cur 活着但不是哈希目标**：人数 > 0 跳过；否则进 **opportunistic**，reason `better_home`（`:195-201`）。
6. 读值语义：人数用 `readInt64`，读错当 0（`load_reporter.go:276-284`）；ZSCORE 返回非 Nil 错误时「仍注册」检查不成立（§7.1 B11）。

#### 1.6.3 执行器 `RebalanceWorldChannelsForZone`（`world_rebalance.go:54-106`）

发布积压 gauge → 预算 0 或计划空就返回 → 先 urgent 后 opportunistic，每条前重查领导权，`migrated >= budget` 停止（`:56-92`）。
**失败的迁移不消耗预算**（`:79-88`），一轮里失败的尝试次数没有上限。结束后用 `max0` 公式写回剩余积压（`:94-105`）。

#### 1.6.4 单条迁移 `migrateWorldChannel`（`world_rebalance.go:207-297`）

1. 新节点为空或等于旧节点 → false（`:231-233`）。
2. 新节点 `CreateScene`——ctx 是进程主 ctx，**没有 deadline**（`:235-239`；`scene_manager_service.go:76`），黑洞节点会挂约 20 s（`world_init.go:46-50` 的注释）。
3. 快照镜像子集合（`:241-245`）→ `reassignSceneNode`（再判一次屏障；返回 false 作废，新节点上的实体留给下一拍复用，`:247-258`）→ 回读校验映射（`:259-266`）→
   转移 Agones 名额（`:268-271`）→ 旧节点 scene_count −1、新节点 +1（`:273-280`）。
4. 旧节点仍存活 → `DestroyScene`（失败忽略；C++ 侧是「有人先排空」，`:282-290`）→ 级联强制销毁旧节点上的镜像、删 `scene:{id}:mirrors`（`:292`、`:299-330`，5.3 钩子）。
5. 步骤 1–3 成功即返回 true，4 的失败不影响返回值（`:226-227`）。
6. 竞态：规划看到人数 0 到执行之间可能有人被预占进旧节点，迁移不重查人数；之后的 DestroyScene 走 C++ 排空改派，不丢人，但那名玩家会被换一次图。
   迁移期间同一个 scene_id 在两个节点上各有一份实体（`world_init.go:260-265` 的注释承认这种双活副本的风险）。

#### 1.6.5 调试端点

`GET /debug/rebalance-plan[?zone=N]`，挂在指标端口（缺省 `:9150`，yaml `:210`）（`world_rebalance_debug.go:34-74`；`metrics.go:751-756`）。只输出计划不执行；
JSON `{zones:[{zone_id,budget,urgent[],opportunistic[]}]}`，每条 `{conf_id,scene_id,old_node,new_node,reason}`（`world_rebalance_debug.go:12-32`）。
zone 参数非法 → 400；指定一个未知 zone → 返回**该 zone 一条、两个列表为空数组**（规划器对它照跑，`toDebugMigrations` 用 `make(…, 0)`，`:45-54`、`:76-88`；注释 `:40-41` 说「empty response」不准确）。

### 1.7 自动扩缩容（`world_autoscale.go`，缺省关）

#### 1.7.1 循环

`WorldAutoscale.Enabled=false` 不启动（`:119-126`；开发 yaml 没有 `WorldAutoscale` 块）。间隔 `CheckIntervalSeconds`（≤0 → 30，`:127-130`），`safego.Loop` 驱动、每轮单独 recover
（`:136-152`；`safego.go` `Loop`）。每轮只有领导者执行，对每个活跃 zone 先 `sweepDrainingWorldChannels` 再逐图决策（`:146-175`）。

#### 1.7.2 单图决策 `autoscaleOneWorldMap`（`:177-257`）

1. `channels = SMEMBERS`，空 → 返回；有排空标记的跳过；其余取人数（`readScenePlayerCount`：出错 / 负数 / 不可解析当 0，`:478-489`）（`:180-200`）。
2. 按人数升序（`:202-203`）；**冷却中则返回**（`:205-207`；`inCooldown` 读失败当「不在冷却」，`:491-494`，§7.1 B15）；`minCh = max(1, MinChannelsPerMap)`（`:209-212`）。
3. **缩容**：`len(loads) > minCh` 且最空者 < `ScaleInPlayerThreshold`（100）（`:214-218`）→ `pickScaleInVictim` 升序遍历（`:295-322`）：人数 ≥ 缩容线即停；
   是镜像源跳过（`channelHasMirrors` 查询出错也当「有镜像」，`:281-293`）；`hasHeadroomFor` 不满足跳过——**余量** = 除牺牲者外每个频道 `max(0, 扩容线 − 人数)` 之和 ≥ 牺牲者人数（`:259-274`）。
   找到 → `beginDrainWorldChannel` 成功 → 打冷却、返回（`:219-223`）；找不到只打 INFO（`:224-228`）。
4. **扩容**：最空者 ≥ `ScaleOutPlayerThreshold`（2000），即**所有**频道到线（`:231-233`）→ 期望数 ≥ `MaxChannelsPerMap`（>0 时）→ ERROR + `max_reached`（`:234-241`）；
   否则期望 +1、等锁补建、打冷却、记 `scale_out/ok`（`:243-253`）。补建可能因无节点失败，但期望已 +1、冷却已打上。
5. 同一轮缩 / 扩互斥：缩容成功直接返回；进了缩容分支却没找到牺牲者时最空者 < 100 < 2000，不会扩。

#### 1.7.3 开始排空 `beginDrainWorldChannel`（`:276-366`）——**先摘路由再排空**

`SREM world_channels`（出错记 `scale_in/error`；返回 0 = 别人已摘，直接 false）→ 期望 −1（钳下限）→ `SETEX scene:{id}:draining 1 DrainTimeoutSeconds`（≤0 → 300）
→ `SADD world_channels:draining` → 记 `scale_in/ok` → 立刻推进一步 `drainOrDestroyChannel`（`:324-366`；设计稿 `world-channel-autoscale.md:43-55`）。

#### 1.7.4 收敛 `sweepDrainingWorldChannels` / `drainOrDestroyChannel`（`:368-428`）

对排空集合每个成员（不可解析的直接 SREM，`:378-383`）：`GET scene:{id}:node` 为空 → 收尾（`:391-396`）；歧义 → 本轮不动（`:397-401`）；`!IsNodeAlive` → 收尾、不发 RPC（`:403-410`）；
先读人数（`:412`），**再**发 `DestroyScene`（ctx 无 deadline，出错本轮返回，`:414-420`）；人数 > 0 → 等下一拍，否则收尾（`:422-427`）。
缝隙：Redis 人数已是 0、但 C++ 仍有带会话的住户时，C++ 保留实体而 Go 已收尾，那个实体就留在节点上、再也没人发 DestroyScene（§7.1 B10）。

#### 1.7.5 收尾 `finishDrainedChannel`（`:430-476`）

先读 `agones_gs` → 级联强制销毁排空窗口里新建的镜像、DEL `mirrors`（`:437-457`）→ DEL `node / zone / player_count / agones_gs / draining`、SREM 排空集合（`:459-464`）→
节点非空时 SREM 反向索引、`scene_count −1`（`:465-468`）→ 归还 Agones 名额 → 记 `scale_in/drained`（`:469-475`）。

#### 1.7.6 防抖

冷却（每次伸缩后 (zone, 图) 静默 120 s，`:496-504`）+ 2000 / 100 宽带 + 余量规则（`world-channel-autoscale.md:57-67`）。

### 1.8 孤儿频道清理（`orphan_cleanup.go`）

- **何时**：仅领导者，每次 fullSync（`load_reporter.go:715-720`）；开关 `CleanupOrphanChannelsOnStartup` 缺省 true（`config.go:232-240`；yaml `:183-186`）。
- **安全规则**：World conf 列表为空 → 拒绝运行（`:26-27`、`:43-47`）；只删 conf 已不在 World 表里的集合，conf 合法但 zone 已离开的保留（`:66-75`）。
- **扫描**：`SCAN … MATCH world_channels:zone:* COUNT 256`，键名解析为 (zone, conf)，格式不对的跳过（`:33-35`、`:53-84`、`:92-113`）。
- **单个集合 `deleteOrphanChannel`**（`:115-213`）：成员 = 集合 ∪ 排空集合（`:118-131`）→ **整组过屏障**：任一成员的节点「不存活或推迟摘除、且屏障未到」→ 整组推迟到下一次 fullSync（`:133-154`）→
  逐个：节点存活则 `DestroyScene`（无 deadline，失败忽略）并 `scene_count −1`；删反向索引、源场景的镜像集合成员、六个场景键、Agones 名额与 `agones_gs` / `draining`（`:156-199`）→
  HDEL 期望数字段、DEL 排空集合、DEL 集合键（`:201-212`）。节点上的残余人数不处理。

### 1.9 C++ scene 侧：场景实例登记（scene-instance-registry）

#### 1.9.1 CreateScene

| 入口 | 位置 | 前置 |
|---|---|---|
| gRPC `SceneNodeGrpc.CreateScene`（SM 只用它） | `scene_node_service.proto:13-14`；`scene_node_service.cpp:235-270` | Agones 模式在 gRPC 线程上**阻塞**等分配许可（有上限），拿不到回 `UNAVAILABLE`（`:240-258`）；然后 `runInLoop` 投到逻辑线程、用 future 同步等（`:260-269`）。**没有零值检查**（`:19-66`） |
| muduo 旧入口 `Scene.CreateScene`（消息号 36） | `scene_handler.cpp:849-924` | `config_id` 或 `scene_id` 为 0 → ERROR 并返回（`:854-859`）；Agones 用**非阻塞**许可（`:861-876`） |

逻辑在 `HandleCreateScene`（`scene_node_service.cpp:19-66`）：按 scene_id 线性扫描去重，已存在就回原 `SceneInfoComp`（`:23-37`）→ 建实体，挂
`SceneInfoComp{scene_config_id=config_id, scene_id, mirror_config_id, dungeon_config_id, creators}` 与空 `ScenePlayers`（`:39-52`）→ 触发 `OnSceneCreated`（Agones 单元计数 +1，
`:54-56`；`scene_event_handler.cpp:30-46`）→ 应答带回 `scene_info`（`:58`）。请求 / 应答形状：`CreateSceneRequest{config_id, scene_id, mirror_config_id, dungeon_config_id, creator_ids}`、
`CreateSceneResponse{SceneInfoComp scene_info}`（`proto/scene/scene.proto:60-72`）。世界频道经 `RequestNodeCreateScene` 发出，mirror 0、creators 空、dungeon 从不设置（`scene_node_client.go:80-119`）。
SM 连节点：gRPC 连接按 (zone, node) 缓存，歧义拒用；端点先查 knownNodes、再直读 etcd；**只用 `grpcEndpoint`**，绝不回落 TCP 端口（`scene_node_client.go:159-288`）。

#### 1.9.2 DestroyScene（先排空再销毁）

gRPC `scene_node_service.cpp:68-125`（旧入口 `scene_handler.cpp:926-976` 同义，另有 `scene_id == 0` 检查 `:932-936`）：找不到 → 幂等 OK（`:73-89`）→
`BeginSceneDrain(entity)` 返回 `relocating > 0` → WARN、**保留实体、返回 OK**（`:91-115`）→ 否则 `OnSceneDestroyed`（Agones −1，`scene_event_handler.cpp:47-56`）后销毁（`:117-121`）。
应答是 `Empty`，**调用方区分不了「已销毁」与「排空中、实体保留」**，Go 只能看自己的 Redis 人数（`world_autoscale.go:412-427`）。注释明写「改派到主世界」（`scene_node_service.cpp:93-97`）。

#### 1.9.3 `BeginSceneDrain` 与改派链路（5.5 的 scene-drain-relocate；5.1 的缩容依赖它）

1. 快照 `ScenePlayers`，对每个住户 `EnqueueRelocateTicket(…, kSceneDrain)`：有 gate 会话的先抄票据（player、session、gate node、gate 实例）再存盘并计入 `relocating`；无会话的只存盘
   （`player_lifecycle.cpp:2140-2184`、`:2207-2231`）。
2. 存盘落地后 `DispatchEmergencyRelocate`：epoch ≠ 0 且 Redis 可用 → 条件写 handoff 标记 `"{epoch}:{ms}"`，无论成败都发 EnterScene（`:2249-2329`）。
3. `SendEmergencyRelocateEnterScene`：`scene_id = 0`、`scene_conf_id = 0`、`zone_id` 与 `gate_zone_id` 都是本 zone，**刻意不带 request_id**（60 s 去重会吞掉第二次排空）（`:2331-2363`；注释 `:2342-2344`）。
4. SM 按 conf = 0 取 `defaultWorldConfID()`（World 第一行，`enterscenelogic.go:1273-1281`、`:1288-1298`）→ 默认大世界人数最少的频道；跨节点时凭标记过换手门（5.2）。
   新落点把被排空频道的人数减掉（`enterscenelogic.go:726-730`），排空收敛就靠它结束。

#### 1.9.4 节点角色闸与「频道」概念

副本节点（类型 1 / 3）上的客户端 63 一律回 3004（`player_scene_handler.cpp:45-52`）。节点不知道「频道」：频道只是「同一 `scene_config_id`、不同 `scene_id`」的若干实体
（`docs/design/world-channel-system.md:66-74` 的描述里「scene_id == 0 按 config_id 去重」已过时，§7.3）。

---

## 2 基线：进场分配如何在频道间选择

### 2.1 入口分流 `resolveSceneForEnter(sceneId, conf, zone)`（`enterscenelogic.go:1267-1286`）

此前 EnterScene 先定目标 zone（`:278-364`）并处理跨 zone（`:372-431`，5.4）。

| 情形 | 行为 | 预占 |
|---|---|---|
| `sceneId ≠ 0`（显式：组队跟随、客户端指定、跨 zone 第二条腿） | `resolveScene` case 1（§2.4） | 否；之后 `:648-670` 用 `AtomicIncrPlayerCountIfSceneExists` 补占并把节点人数 +1 |
| `sceneId = 0`、`conf ≠ 0` | `ReserveBestWorldChannelForEnter(conf, zone)` | 是 |
| 两者都是 0 | conf = `defaultWorldConfID()` = World 第一行（`:1288-1298`）；World 表为空报错（`:1273-1279`） | 是 |

走 conf = 0 的：login 进游戏**一律** `SceneId = 0`、不带 conf（`go/login/internal/logic/clientplayerlogin/entergamelogic.go:736-745`；`resolveEnterSceneRoute` 只返回 `sceneID: 0`，
同文件 `:661`、`:667`、`:678`、`:682`）；排空 / 疏散改派（§1.9.3）；跨 zone 不指定地图的传送。所以登录、重连、顶号都落默认大世界人数最少的频道，只有恰好选中原频道时才走「同落点重连」
（PARITY 有意差异 ⑥，`PARITY.md:91`）。跨 zone 第二条腿的「等待落点」会回读第一条腿记下的地图，解析失败时回落默认大世界（`enterscenelogic.go:433-459`）。
`resolveScene` 的 case 2（`:1250-1264`）对 EnterScene 是死代码（`:1268-1271` 只在 `sceneId ≠ 0` 时调用它）。

### 2.2 `ReserveBestWorldChannelForEnter`（`world_init.go:437-513`）

1. `SMEMBERS`，空或出错 → (0, "")（`:442-446`）。
2. 每个成员：映射到歧义节点 → 跳过；节点为空或 `!IsNodeAlive` → 陈旧；其余进候选 `{sceneID, nodeID, zone}`（`:451-469`）。
3. **只有候选为空（全部陈旧）时才懒改派**（`:471-506`）：取主世界活节点并排序，无 → 返回 0；对**每一个**陈旧频道：`target = assignNodeByHash(conf*1000 + 下标)`；
   老节点过再入屏障，被挡就换下一个；直接 `SET scene:{id}:node`（不经 `reassignSceneNode`）；修反向索引；**同步** `CreateScene`（请求 ctx，受 zrpc `Timeout 8000` 约束、不在预算内，yaml `:3-14`），失败跳过；
   成功进候选。此路径**不改** scene_count、**不转** Agones 名额、**不要求**节点已离开注册表（与补建、再平衡口径不同，§7.1 B7）。
4. **原子预占** `ReserveBestWorldChannel(candidates)`（`scene_atomic.go:220-278`，Lua `:46-101`）：KEYS = `[scene:{id}:node, instance:{id}:player_count] × N` + `node:…:player_count × N`，
   ARGV = N 个 nodeId；脚本只比较 `GET scene:{id}:node == ARGV[i]` 的候选，取人数最少者（nil 当 0，**严格小于**，并列取候选顺序第一个）；对选中者场景人数与节点人数各 INCR；
   返回 `{下标, nodeId, 新人数}`，全部失效返回 nil（Go 当 0）。候选顺序 = SMEMBERS 顺序：整数且 ≤512 个成员时是 intset、升序，所以**实际上并列取 scene_id 最小（最老）的**——
   这是 Redis 实现细节，不是契约（§7.1 B13）。
5. `GetBestWorldChannel`（`world_init.go:340-435`）是不预占版本，懒改派只返回第一个成功的；只被 `createMainWorldScene`（`createscenelogic.go:133-159`）使用，生产链路上没有
   调用方用 SM 的 `CreateScene(MAIN_WORLD)`（C++ 只在镜像时调，`player_scene.cpp:122-` `RequestEnterMirrorScene`）。

### 2.3 预占的成对释放

自动预占后每个拒绝出口都要 `DecrInstancePlayerCount(zone, sceneId)`（场景 −1、再按此刻的 `scene:{id}:node` 节点 −1，负数钳 0，`instance_lifecycle.go:362-379`）：
节点号非法（`enterscenelogic.go:469-476`）、换手门 18（`:505-512`）、归属 zone 查询拒绝（`:545-550`）、epoch 补种失败 / 冲突（`:596-609`）、**同落点重连**（退还后直接重发路由、不写 location，
`:626-646`）、location 写失败（`:704-708`）、推路由失败后的回滚（`:732-751`，5.2）。落点成功后旧场景人数在 `:726-730` 扣（`currentLoc.SceneId != sceneId`）；
死节点接管的玩家在落点成功后经 `releaseTakenOverSceneCount` 还回旧频道的人数（`:757-762`、`:828-838`）。单测 `owner_epoch_test.go:2331`
`TestEnterScene_HomeZoneUnavailableReleasesAutoReservedChannel`。

### 2.4 显式频道：`resolveScene` case 1（`enterscenelogic.go:1175-1248`）

场景所属 zone 与请求 zone 不一致 → 报错（`:1178-1180`）→ `GET scene:{id}:node`：出错报错、为空报「不存在」（`:1181-1200`）→ 歧义报错（`:1201-1206`）→
节点不存活：屏障未到 → `ErrReentryBarrierPending`（最终回 17，`:1207-1218`）；没带 conf → 报错（`:1222-1227`）；否则按 `IsWorldConf` 决定用途、`GetBestNodeForPurpose`、
**直接 SET** 映射（不修反向索引、不改计数）、发 CreateScene（失败只记日志）（`:1228-1246`）。之后的预占是 `luaAtomicIncrPlayerCount`：映射不存在返回 −1 → `scene_gone` →
`ErrNoAvailableNode`，记 `enter_scene_rejected_total{reason="scene_gone"}`（`scene_atomic.go:31-44`；`enterscenelogic.go:648-659`）。
**它只看映射在不在，不看排空**——排空中的频道直到收尾才删映射（`world_autoscale.go:459`），所以组队跟随或客户端指定 scene_id 都能进到正在排空的频道（§7.1 B5）。

### 2.5 选频道相关的错误码（scene_manager 私有码，`errors.go:4-70`）

| 码 | 含义 | 出处 |
|---|---|---|
| 1 `ErrNoAvailableNode` | 解析不出频道（含懒改派全部被屏障挡住）、scene_gone | `enterscenelogic.go:461-468`、`:655-659` |
| 12 `ErrNoNodeForPurpose` | 只在副本创建时 | `errors.go:16-20` |
| 17 `ErrSceneReentryBarrier` | 只来自显式 scene_id 路径；懒改派被挡回的是 1（`world_init.go:487-490` → `enterscenelogic.go:1282-1283`） | `errors.go:39-44`；`enterscenelogic.go:464-466` |

故障分类里 1 与 12 计 fault（`scene_manager_service.go:303-324`）。

### 2.6 客户端可见：79 / 31 / 63 / 排空 / 跟随

1. **79 NotifyEnterScene**（`message_id.txt:80`）：`EnterSceneS2C{SceneInfoComp scene_info}`（`player_scene.proto:23-26`）；字段见 §0.5。**协议里没有频道序号、频道名、在线人数**，
   客户端唯一能辨认频道的是 `scene_id`。每次真正进入新实体时在 `HandleEnterScene` 同步发出，随后发自身 21（`player_scene.cpp:104-116`）；已在目标实体里时提前返回、**不发 79**（`:51-58`）。
2. **没有频道列表消息**：43 `SceneInfoC2S`（`message_id.txt:44`）只推 31 `NotifySceneInfo`（`message_id.txt:32`），`SceneInfoS2C` 虽是 `repeated SceneInfoComp`，基线只放当前场景一条
   （`player_scene_handler.cpp:185-206`；`player_scene.proto:28-31`）。
3. **63 EnterScene**（`message_id.txt:64`；`EnterSceneC2SRequest{SceneInfoComp scene_info}`，`player_scene.proto:13-16`）的同步拒绝顺序：副本节点 3004 → 战斗在途 3023 →
   换场景在途 3014 → 三个 id 全 0 → 3005 → 镜像分支 → `scene_id` 等于当前 → 3008 → 缺会话 3005 → 无 SM 1003（`player_scene_handler.cpp:45-149`；tip 定义
   `xm-table/src/main/proto/tip/scene_error_tip.proto:20-58`、`common_error_tip.proto:18`）。通过后把 `scene_id` 与 `scene_config_id` 原样发给 SM（`:151-166`）。
   - **只带 `scene_config_id`**：SM 在全 zone 该图活频道里（包括当前这个，已算上自己一人）预占最少者——挑回原频道走「同落点重连」，退还预占、重新路由，节点幂等早退，**客户端收不到任何 79**，
     63 应答只是「已受理」（`enterscenelogic.go:626-646`；`docs/reference/mmorpg-client-contract-scene.md:248-250`）；挑到别的 → 旧场景旁人收到 51，本人收到新 79 与 21，跨节点先经 5.2 换手。
   - **带 `scene_id`**：等于当前 → 3008；否则按 §2.4，可进排空频道。
4. **坐标**：同图换频道保留坐标，换地图落目标出生点（`player_scene.cpp:60-66`、`:97-102`）。
5. **组队跟随**进队长所在的**那个**频道：`scene_id = 队长 scene_id`、`zone_id = 队长 zone`、不带 request_id（`player_team.cpp:475-484`）；不走最少人数规则，频道也不设上限。
6. **缩容排空**：住户存盘 → 旁人 51 → 本人收到**默认大世界**的 79 / 21，客户端经历一次加载（`docs/design/world-channel-autoscale.md:176-178`「明确不成立的说法」）；被缩的图不是默认图时落出生点；
   **没有任何 tip**；已断线的住户只存盘。
7. **再平衡**只迁空频道，不可见；urgent 只发生在死节点上，那些玩家走重连接管（5.5），接管后落默认图人数最少的频道（`enterscenelogic.go:318-333`、`:757-762`）。
8. **扩容**不可见，新频道只在下次选频道时开始分到人。

### 2.7 robot 覆盖（基线）

| 场景 | 做什么 | 断言 | 出处 |
|---|---|---|---|
| login_test `SceneSwitch` | 等第一次 79，发 63 只带当前 conf | 5 s 内 79 计数增加即通过，不检查 scene_id 变没变 | `robot/login_test_scenarios.go:165-166`、`:1170-1201` |
| AI `switch_scene`（stress / behavioral / fighter / explorer / chatter 画像） | 发 63 带当前 conf | 发出即记成功，不等 79 | `robot/logic/ai/robot_ai.go:195-218`；缺省画像 stress（`robot/etc/robot.yaml:29`） |
| team_smoke S6 `switchScene` | 63 带目标 conf | 等 conf 相符的 79 | `robot/team_smoke_scenario.go:804-828` |
| travel_smoke T2b | 不存在的 conf 必须被同步拒绝（5.4；第一条腿预检读 `world_channels`） | — | `robot/travel_smoke_scenario.go:250-266`；`enterscenelogic.go:967-994` |

`SceneSwitch` 能通过的前提是默认图至少 2 个频道、且最少的不是自己所在的那个——开发配置图 1 有 16 个频道（`PROGRESS.md:4844` 写明是真实换频道）。
**这条用例现在对 Java 会超时失败**（Java 同图 63 一律不动，`SceneWorld.java:238-240`）；5.1 之后（本节点 ≥2 个图 1 频道）可以通过。
**没有任何 robot 覆盖扩缩容、排空、再平衡、孤儿清理或懒改派**，只有 Go 单测 / 集成测试（§9.1），设计稿也承认 E2E 要人工验收（`world-channel-autoscale.md:176-195`）。

---

## 3 Java 现状与差距

### 3.1 场景创建与发号（xm-scene）

- **节点号与雪花**：节点号在 `[1, Snowflake.MAX_WORKER=1023]` 按 (`scene`, zone) 占用，TTL 15 s（`SceneNode.java:116-119`、`:217-220`；`NodeIdLease.java:81-102`）；
  `new Snowflake(nodeId)`（`SceneNode.java:221`）以 `snowflake::nextId` 交给 `SceneWorld` 当**场景号与实体号**的发号器（`:257-264`；`SceneWorld.java:105-106`）；
  `nextId` 返回 0 即抛（`:976-983`）。`architecture.md:667`：「场景内的临时 id（实体、场景实例）用场景节点自己的雪花」。按 zone 的 worker 意味着两个 zone 中号相同的节点可能发出同号场景 id
  （全服发号的先例理由见 `architecture.md:660-662`；`NodeTypes.java:16-20`）。
- **建场景**：逻辑线程上 `tables.worldSceneConfigIds().forEach(sceneWorld::createScene)`，最多等 5 s（`SceneNode.java:280-283`、`:124-125`、`:624-626`）。
  世界图列表 = World 表 `scene_id` 按表序去重、跳过 0，表空即启动失败（`ConfigSceneTables.java:33-55`）。`createScene(configId)`：发号 → 放进 `scenes`（LinkedHashMap）→ 推人数指标 → 日志
  （`SceneWorld.java:114`、`:177-183`）。`Scene` 的 `SceneInfoComp` 只填 conf 与 id（`Scene.java:28-35`）；每个场景有自己的 `ViewIndex`，频道之间互不可见（`:26`）。
- **之后不再新增**：运行期没有建 / 销毁入口（`docs/porting/inventory/scene-core.md:167`）。这个前提写在 `SceneMetrics.java:27-28`、`SceneWorld.java:185-188`、`architecture.md:705`、
  `location.proto:18`（「进程内创建时发，跨进程重启不重复」）——5.1 要改这些文案。

### 3.2 节点目录（Redis）

- 内容：`SceneNodeInfo{zone_id, node_id, instance_id, link_host, link_port, scenes[], rpc_host=7, rpc_port=8}`（后两者是 4.5 在途字段）；
  `SceneEntry{scene_id, scene_config_id, player_count}`（`node_directory.proto:22-41`）。身份启动时组好、场景列表每次发布时在逻辑线程上取快照（`SceneNode.java:332-344`）。
- 周期与 TTL：`scheduleWithFixedDelay` 每 5 s，条目 TTL 15 s（`SceneDirectoryPublisher.java:26-27`、`:46-71`）；没有「立即发布」入口。
- 存储：`RMapCache`，键 `xm:nodes:scene:{zone}`、字段节点号；`list` 读整份、单条解析失败只跳过（`NodeDirectory.java:45-47` `publish`、`:77-87` `list`、`:89-92`；`RedisKeys.java:28-31`）。
- 停服先摘条目（`SceneNode.java:409-424` `release`；`SceneDirectoryPublisher.java:73-92`）；租约丢失只停发布不删条目、停监听、拒绝新进场，在场玩家继续服务（`SceneNode.java:542-557`；`SceneWorld.java:890-893`）。
- 其他读者：gate 按节点号连 scene（`GateNode.java:169-172`；`NettyLinkConnector.java:49-60`）；gateway 区服健康探测（`ZoneHealthProbe.java:76`）。

### 3.3 进游戏的分配链

1. **xm-login**（`EnterGameHandler.java:205-241`）：`AssignSceneRequest{zone_id, player_id}`，有本 zone 位置记录时带 `preferred_scene_config_id / node_id / scene_id`，读位置失败只带存档地图；
   调用外再套 5 s 超时（`:243-255`；`xm-login/src/main/resources/application.yaml:92`）。
2. **xm-scene-manager**（`SceneDirectoryProvider.java:68-81`）：Dubbo 业务线程上同步算，提供方超时 3000 ms（`xm-scene-manager/src/main/resources/application.yaml:61-68`）；
   指标 `xm.scene_manager.assign{result=ok|no_scene|bad_request|rejected|error}`，8 个固定桶（`SceneDirectoryProvider.java:32-66`）。
3. **规则 `SceneAssigner.assign`**（`SceneAssigner.java:55-94`）：zone = 0 → 3005；只用可用节点（节点号非 0、zone 相符、链路地址非空且端口合法，`:131-145`）；
   原实例（节点号与场景号都对上）直接用、不看人数（`:64-69`、`:96-112`）；期望地图是世界图且有承载 → 用它，否则默认图（`:70-79`；默认 = World 第一行，可用
   `xm.scene-manager.default-world-config-id` 覆盖，`WorldSceneConfigs.java:44-57`；`SceneManagerProperties.java:12-13`）；人数最少胜，并列取节点号小、再并列取场景号小，全部无符号
   （`:114-129`、`:151-165`）；一个没有 → 3000（`:37-42`、`:80-84`）。**不预占**（`:29-30`）。
4. **login 处理结果**：Dubbo 失败 → 应答内 3023（`EnterGameHandler.java:260-266`）；业务拒绝 → scene-manager 的 tip 原样放进应答（`:267-271`），即「没有场景」时客户端收到应答内 3000。
   基线所有进场失败都是 EnterGame 已受理后经 gate 推 23 `{3023}`（`entergamelogic.go:425-436`、`:456-462`）。这是 5.1 之前就有的差异，PARITY 没有登记；5.1 会让它更常出现（Q6）。
5. **gate 与 scene**：gate 把 `scene_id` 原样带进 `PlayerEnter`，路由只看节点号（`ClientDispatcher.java:531`）；scene 收到时场景不在本节点 → 3023（`SceneWorld.java:264-267`），
   加载完成后再查一次（`:314-318`）；进场下行 79 → 21 → 47 → 给旁人 21（`:411-432`）。

### 3.4 63 与 43 → 31

- 43 不回包、推 31，内容是当前场景的 `SceneInfoComp`（`ClientRequestHandler.java:105-108`）。
- 63 的校验（`ClientRequestHandler.java:203-235`）：三个 id 全 0 → 3005；带 mirror 且 `scene_id = 0` → 3023；`scene_id` 等于当前 → 3008；否则 `resolveSwitchTarget`：没找到 → 3023；
  指定了 conf 但目标 conf 不符 → 3005；先回应答再同步换场景。javadoc 说明 3004 / 3014 / 战斗在途不出现、镜像与跨节点回 3023（`:203-207`）。
- `resolveSwitchTarget`（`SceneWorld.java:229-248`）：指定 scene_id 只查本节点；**conf 与当前相同 → 留在原场景**（注释写「基线挑回原频道即幂等不动」，只说对了一半）；
  其余取本节点该 conf 人数**严格更少**的第一个（并列 = LinkedHashMap 插入序最早的）。
- `switchScene`（`SceneWorld.java:454-476`）：旧视野的人收到 51 → 本人 79 / 21 / 47；换地图落出生点、同图保留坐标（`:468`）；写位置记录（`:473`）；调组队跟随（`:475`）。

### 3.5 现状隐含的不变量（5.1 要么保持、要么显式打破）

| # | 不变量 | 证据 | 5.1 |
|---|---|---|---|
| I1 | 每个 scene 节点承载全部世界图，63 按地图换场景永远在本节点完成 | `SceneNode.java:280-283`；`SceneWorld.java:234-248`；robot 依赖（`ReconnectScenario.java:179-194`、`TeamScenario.java:453-466`） | **保持**到 5.2（per-node 覆盖，§4.6.4） |
| I2 | scene_id 由节点按 zone 的 worker 发，跨 zone 可能同号 | `SceneNode.java:218-221` | **打破**：改由 scene-manager 全服租约发（§4.5） |
| I3 | 场景只在启动时建（`scene_config` 指标标签有界的前提） | `SceneMetrics.java:27-28`；`architecture.md:705` | 标签仍是 `scene_config`（受 World 表约束），仍有界；只改文案 |
| I4 | 分配无状态、无副作用 | `SceneAssigner.java:29` | 改为「多副本安全、有软预占副作用」（§4.11） |
| I5 | 单节点时同一张图只有一个场景 | robot `MovementScenario.java:170-177`、`SkillScenario.java:85-87` 断言 A、B 同场景 | **打破**：robot 要改（§9.7） |
| I6 | 组队跟随只在本节点、进队长所在实例 | `TeamFollowService.java:17-34`、`:167-191` | 保持；新增「队长所在实例在排空中不跟」（§4.13） |

### 3.6 差距逐项

| 主题 | 基线 | Java 现状 | 缺口 / 5.1 处理 |
|---|---|---|---|
| 频道集合 | 每 (zone, conf) 一个 SET + `scene:{id}:node` 等多键（§1.1） | 无；频道 = 各节点自建的场景 | 一张 HASH 频道计划（§4.2） |
| 期望频道数 | Redis HASH，配置只作种子（§1.5.4） | 无；每节点每图固定 1 个 | 同语义（§4.6.2 P5） |
| 发号 | 中心 snowflake + 围栏（§1.4） | 节点本地、按 zone worker | scene-manager 全服租约（§4.5） |
| 落点 | FNV(`conf*1000+i`) % 字典序节点（§1.5.5） | 无 | per-node 模式按「最少者」放；hash 模式统一键 `conf*1000+slot`（§4.6.3） |
| 节点用途 | 0..3 类型 + 严格分池（§1.2） | 目录无角色字段 | 只预留字段（D15） |
| 补建互斥 | zone 锁 60 s + 等锁 10 s；慢 RPC 可让互斥失效（§1.5.2） | 无 | 分 zone 领导锁 + 令牌 / 版本号 CAS 写（§4.4） |
| 节点侧建 / 销毁 | 按 scene_id 幂等；gRPC 入口缺零号检查；有人先排空（§1.9） | 只在启动时建 | 拉取计划后在逻辑线程上建 / 排空 / 销毁（§4.10） |
| 排空改派 | 存盘 → handoff 标记 → `EnterScene(0,0)` → 默认大世界（§1.9.3） | 无 | 同节点同图改派，回落同节点默认大世界（§4.10.3） |
| 自动扩缩容 | 缺省关；全线满扩、最空 < 100 且有余量缩；冷却 120 s；排空标记 300 s（§1.7） | 无 | 同判定 + 三处收紧（§4.7） |
| 再平衡 | urgent + opportunistic，预算 10，300 s 周期，调试端点（§1.6） | not_applicable | 死节点重铺归补建；择机迁移只在 hash 模式（§4.8、§4.9） |
| 孤儿清理 | fullSync 时删 World 表已没有的 conf（§1.8） | 无 | 计划里转 DRAINING（§4.9） |
| 选主 | 全局一把锁 30 s，当选触发 fullSync（§1.3） | 无 | 分 zone 锁（§4.4） |
| 进场预占 | Lua 原子比较 + INCR，成对 DECR（§2.2、§2.3） | 不预占 | 软预占（§4.11，D7） |
| 死节点上的频道 | 懒改派 / urgent 迁移，过再入屏障（§2.2、§1.6） | 节点 TTL 15 s 后从目录消失，别的节点本来就有同图场景 | 宽限期后删记录、重铺容量；玩家侧随 5.5（D12） |
| 63 只带当前地图 | 全 zone 挑最少，可能跳频道（§2.6） | 一律不动 | 本节点挑最少，可能跳频道（§4.12） |
| 指标 | `world_autoscale_total`、`rebalance_*`、`is_leader`…（§6.1） | 只有 `xm_scene_manager_assign_seconds`（`architecture.md:762`） | §6.2，不带 zone 标签 |

### 3.7 工作区里在途的 4.5 改动与 5.1 的关系

- `SceneNodeInfo` 已追加 `rpc_host = 7; rpc_port = 8`（`node_directory.proto:30-34`），**5.1 新字段从 9 起编号**。
- 4.5 给 xm-scene 加 Dubbo Triple 提供方（`SceneAssetOpService`，`register=false`、group `scene-asset`，`xm-api/.../SceneAssetOpService.java:7-27`；`DubboGroups.java` 工作区版本），
  调用方客户端缓存 `SceneAssetOpClients` 与独立模型 `IsolatedDubboModule` 在 `xm-api/.../asset/`；本机脚本已把 `XM_DUBBO_SECRET` 注入 xm-scene、端口 `xm.scene.asset-rpc-port` 21100
  （`tools/local/start-slice.sh:5-7`、`:76`）。本稿推荐的拉取模型不需要它；Q2 若选推送，就在同一个端口上再导出 `SceneChannelService`，并改 `DubboGroups.SCENE_ASSET` 注释「同一端口上不会有别的服务」。

---

## 4 Java 设计

### 4.1 总体与职责

```
            ┌──────────────── xm-scene-manager（N 副本）────────────────────────────┐
 login ─Dubbo─►│ 数据面（每个副本）：SceneDirectoryService.assign                        │
            │   读：节点目录（排除 draining）；写：软预占 Lua（xm:world:{z:N}:resv:*）   │
            │ 控制面（每个 zone 的领导者，线程 scene-manager-world）：                   │
            │   每 5 s：只读 Lua 取快照 + 读目录 → WorldChannelPlanner（纯函数）       │
            │   → 一段「令牌 + 版本号 CAS」Lua 原子写计划、INCR ver                    │
            └───────────────────────────┬────────────────────────────────────────┘
                                        │ Redis：xm:world:{z:N}:{ch,desired,cooldown,ver,leader}
                                        ▼ （scene 每秒 GET ver，变了才读整张计划）
            ┌──────────────── xm-scene（每节点）───────────────────────────────────┐
            │ scene-sched：ChannelPlanFollower 拉计划 → 投递逻辑线程                    │
            │ 逻辑线程：applyChannelPlan（建 / 标排空）→ drainStep（同节点改派 / 空了销毁）│
            │ 目录：SceneEntry.draining、applied_plan_version，应用后立即补发一次       │
            └────────────────────────────────────────────────────────────────────┘
```

- **权威**：频道计划（Redis）是「应当存在哪些频道、在哪个节点、什么状态」的唯一权威；节点内存是「实际建出来的」；目录是节点对实际状态的报告。
  三者由「领导者按目录推进计划、节点按计划调整内存」收敛到一致。这与 team 的「读 → 纯规则 → 按 ver CAS 写」同构（`architecture.md:437-440`）。
- **线程所有权**（`AGENTS.md` §3）：scene 的场景增删只在逻辑线程上做；Redis I/O 在 `scene-sched`（`SceneNode.java:103`、`:217`）；scene-manager 控制面用专用调度线程
  （Redis 阻塞 I/O 可在其上），领导锁续期另起一个调度线程——一拍再慢也不拖住续期，补上基线「慢 RPC 让锁过期」的风险（`world_init.go:95-101`）。
- **不需要 scene-manager → scene 的 RPC**（D3）：节点自己拉计划，旧领导者的「晚到推送」这类问题不存在；节点到 scene-manager 也不需要可达。

### 4.2 Redis 键（全部经 `RedisKeys` 生成，前缀 `xm:`）

一个 zone 的频道键共用 hash tag `{z:<zone>}`，多键 Lua 在 Cluster 下同槽（与 `xm:{team}:*`、`xm:guild:{g:<id>}:*` 同一惯例，`RedisKeys.java:138-221`）。

| 方法（新增） | 键 | 类型 / TTL | 写者 | 读者 |
|---|---|---|---|---|
| `worldChannels(zone)` | `xm:world:{z:<zone>}:ch` | HASH：字段 = scene_id 无符号十进制，值 = `xm.api.WorldChannel` pb；无 TTL | 领导者（围栏 Lua） | 领导者、scene（拉取） |
| `worldDesired(zone)` | `xm:world:{z:<zone>}:desired` | HASH：conf → 期望频道数；无 TTL；首次 HSETNX 播种（同 `world_autoscale.go:87-92`） | 领导者 | 领导者 |
| `worldCooldown(zone)` | `xm:world:{z:<zone>}:cooldown` | HASH：conf → 冷却到期毫秒（Redis TIME）；过期字段由领导者顺手 HDEL | 领导者 | 领导者 |
| `worldPlanVersion(zone)` | `xm:world:{z:<zone>}:ver` | STRING 整数，只增不减、不过期 | 围栏 Lua INCR | 领导者（CAS）、scene（变更检测） |
| `worldLeader(zone)` | `xm:world:{z:<zone>}:leader` | STRING 令牌 `<instanceId>:<uuid>`，`SET NX PX 30000` | 竞选者 | 围栏 Lua |
| `worldReservations(zone, sceneId)` | `xm:world:{z:<zone>}:resv:<scene_id>` | ZSET：成员 player_id 无符号十进制，分 = 到期毫秒；`PEXPIRE = 预占 TTL` | assign 的 Lua | assign 的 Lua、领导者（hash 模式择机迁移前） |
| `worldZones()` | `xm:world:zones` | SET：zone_id；无 TTL、只增 | scene 启动时 SADD | 领导者（决定竞选哪些 zone） |

新增节点类型 `NodeTypes.SCENE_MANAGER = "scene-manager"`，作用域 0（全服），理由同 `TEAM` / `GUILD`（`NodeTypes.java:21-24`）；租约键沿用 `xm:node-id:scene-manager:0:<id>`
与 `xm:node-id-epoch:…`（`RedisKeys.java:15-26`）。**时间源一律 Redis TIME**（同 team / gateway，`architecture.md:440`、`:528`）；Lua 里调 `TIME` 后再写已有先例（`RedisRateLimitStore.java:31`、
`RedissonGuildCacheRedis.java:51`）。

### 4.3 内部 proto（xm-api，Java 版私有，不是同步产物）

新文件 `xm-api/src/main/proto/xm/api/world_channel.proto`：

```proto
enum ChannelState { CHANNEL_STATE_UNSPECIFIED = 0; CHANNEL_ACTIVE = 1; CHANNEL_DRAINING = 2; }
enum ChannelKind  { CHANNEL_KIND_UNSPECIFIED = 0; CHANNEL_KIND_WORLD = 1; }          // 5.3 追加副本 / 镜像
enum DrainReason  { DRAIN_REASON_UNSPECIFIED = 0; DRAIN_SCALE_IN = 1; DRAIN_REBALANCE = 2; DRAIN_ORPHAN = 3; }

// xm:world:{z:<zone>}:ch 的一个值。(scene_id, node_id) 终生不变：频道换节点 = 新建一条 + 旧的排空（D4）。
message WorldChannel {
  uint64 scene_id = 1;          // scene-manager 全服雪花号
  uint32 scene_config_id = 2;
  uint32 node_id = 3;           // 按 zone 的 scene 节点号；节点号的当前租约持有者承载它
  uint32 slot = 4;              // 频道位 0..998；hash 模式的落点键 = conf*1000 + slot
  ChannelState state = 5;
  int64 created_ms = 6;         // Redis TIME
  int64 state_since_ms = 7;     // 进入当前状态的时刻（排空超时据此判断）
  uint64 plan_version = 8;      // 最后改写本条时写入后的 ver（节点据此判断「已看到这次改写」）
  ChannelKind kind = 9;
  DrainReason drain_reason = 10;
}
```

`node_directory.proto` 只扩不改：`SceneEntry` 加 `bool draining = 4;`；`SceneNodeInfo` 加 `uint64 applied_plan_version = 9;`、`uint32 scene_node_type = 10;`（5.3 预留，取值沿用基线 0..3，
`scene_type.go:9-19`；5.1 恒 0）。

### 4.4 选主与围栏

- **每个 zone 一把锁** `xm:world:{z:<zone>}:leader`（基线全局一把，`scene_manager_service.go:94-97`；D2）：竞选 `SET NX PX 30000`（值 = 本副本令牌）；续期每 10 s 一次，
  Lua「值仍是我的才 PEXPIRE」；放锁 Lua「值是我的才 DEL」。写法沿用帮会排行锁（`RedissonGuildRankRedis.java:41-46`、`:150-165`；`GuildRanks.java:57-58`）。
  **不用** Redisson `RLock`（`QueueDispatcherRunner.java:39-57` 那种）：它的值是「UUID:线程号」的哈希，进不了业务 Lua 做令牌校验。
- **有效期**：距最近一次**成功续期**（时刻取命令发出前的单调时钟）不足 2/3 TTL（20 s）才算领导者，判法与 `NodeIdLease.isValid` 相同（`NodeIdLease.java:27-33`、`:138-144`）；
  续期返回 0（锁已不是我的）→ 立即降级；续期出错 → 保持到 2/3 TTL 后自动失效。失效期间该 zone 的全部变更动作停止。
- **写入 Lua**（整拍的全部改动打成一批）：

  ```lua
  -- KEYS: leader, ch, desired, cooldown, ver
  -- ARGV[1]=token  ARGV[2]=期望的 ver（快照读到的值，nil 当 0）  之后若干 (op, field, value) 三元组（字节串）
  if redis.call('GET', KEYS[1]) ~= ARGV[1] then return -1 end                 -- 不是领导者
  if tonumber(redis.call('GET', KEYS[5]) or '0') ~= tonumber(ARGV[2]) then return -2 end  -- 版本冲突
  -- op: S=HSET ch | D=HDEL ch | Q=HSET desired | N=HSETNX desired | X=HDEL desired | C=HSET cooldown | Y=HDEL cooldown
  ...
  return redis.call('INCR', KEYS[5])
  ```

  返回 −1 → 放弃该 zone 的领导；−2 → 本拍作废、下拍重读重算（只在双领导窗口或运维手改时出现）。被改写记录的 `plan_version` 由 Java 预先填成「期望 ver + 1」。
  pb 是任意字节，脚本参数一律用 `ByteArrayCodec`（同 team，`architecture.md:438-439`）。
- **当选**立即跑一拍（对应基线当选即 fullSync，`scene_manager_service.go:113-115`）；**停服**用属主校验 Lua 放全部 zone 锁（同 `:122-127`）；开关
  `xm.scene-manager.world.leader-eligible`（缺省 true，false 时只做数据面，同 `config.go:80-86`）；降级时把只有领导者维护的 gauge 清零（同 `scene_manager_service.go:108-112`）。
- **竞选哪些 zone**：每拍 `SMEMBERS xm:world:zones`，对每个 zone 未持锁就试一次 `SET NX`。

### 4.5 scene_id 发号

- scene-manager 启动时占一个 `NodeTypes.SCENE_MANAGER` 全服租约（作用域 0，号段 `[1, 1023]`，`Snowflake.java:18-20`；TTL 15 s）；worker = 租约号。
  包成「租约有效才发号」：照 `SceneGuids`（`xm-scene/.../SceneGuids.java:9-38`）的写法，建议把它挪到 xm-common 共用（它只依赖 `Snowflake` 与 `BooleanSupplier`）。
  发号前 `isValid()` 为假 → **跳过本拍全部新建**（fail-closed，计 `world_ticks_total{result=no_lease}`）；租约确认丢失 → 放掉全部 zone 锁、停止竞选、ERROR，等人工重启
  （对应基线 `scene_manager_service.go:250-260`，Java 不自杀，同 scene 节点丢租约的口径 `SceneNode.java:542-557`）。
- **scene_id 与 (zone, node) 终生绑定**：频道换节点时发新号（D4）。节点用同一个节点号重启时会按计划把**同号**频道重新建出来（§4.10.5），等价于基线「节点重启后对全部频道重发 CreateScene」
  （`world_init.go:120`、`:246-249`）。
- 节点侧 `SceneWorld.nextId` 只再用于**实体号**。场景号与实体号数值即使相同也无害：客户端里是不同字段，服务端在不同的表里（`SceneWorld.java:114`、`:118`）。
- 改 `architecture.md:667` 与 `location.proto:18` 的说法：scene_id 现在是「频道的身份」，不再是「节点进程里的实例」。

### 4.6 领导者的一拍（每个领导的 zone，每 `tick` = 5 s；基线负载刷新周期也是 5 s，`load_reporter.go:31`）

#### 4.6.1 输入

- **计划快照**：一段只读 Lua 一次取回 `GET ver`、`HGETALL ch / desired / cooldown`、`TIME`，保证一致。
- **目录**：`NodeDirectory.list(zone)`，只留可用节点（判法同 `SceneAssigner.isUsable`，`SceneAssigner.java:131-145`）→ `nodeId → {instance_id, applied_plan_version, scenes(id → conf, players, draining)}`。
- **缺席表**（内存）：计划引用了、但目录里看不到的节点 → 记「首次缺席时刻」（单调时钟）。领导者换人后从零计时：偏晚，安全。
- **配置**、World 表（`WorldSceneConfigs`，表空时 scene-manager 起不来，`WorldSceneConfigs.java:25-36`）、发号租约是否有效。

#### 4.6.2 规划顺序（纯函数 `WorldChannelPlanner.plan(快照, 目录, 缺席表, 配置, now) → ops`）

- **P1 孤儿 conf**（`cleanup-orphans`，缺省 true，同 `config.go:232-240`）：conf 不在 World 表的 ACTIVE 记录 → DRAINING（`drain_reason=ORPHAN`）；该 conf 的 desired / cooldown 字段 HDEL
  （同 `orphan_cleanup.go:201-204`）。不需要「表为空就不跑」的防线——表空时进程起不来。
- **P2 死节点**：节点缺席满 `dead-node-grace`（缺省 20 s）→ HDEL 它名下全部记录（任何状态）。替补由 P5 补出（只重铺容量，不碰玩家，D12）。
- **P3 排空推进**：对 DRAINING 记录、节点在场：
  - `applied_plan_version ≥ 记录.plan_version` 且该节点目录里已没有这个 scene_id → HDEL（收尾；`DRAIN_SCALE_IN` 记 `world_autoscale_total{scale_in, drained}`）。
    版本条件保证节点**已经看到**这次排空，不会把「节点还没来得及建」误当成「已销毁」。
  - 排空满 `autoscale.drain-timeout`（缺省 300 s，按 Redis TIME 与 `state_since_ms`）仍在：`drain_reason=SCALE_IN` 且 conf 有效 → 回滚 ACTIVE、desired + 1（钳上限）、记 `reverted`（D10）；
    其余原因只打 WARN（限频）并计入 `world_channels{state=draining}`。
- **P4 自动扩缩容**（开启且到 `autoscale.check-interval`）：§4.7。放在补建之前，扩容新增的期望数在**同一次写入**里就被 P5 实现。
- **P5 补建**：对每个 World conf（表序）：
  1. `d = desired[conf]`（≥1 的合法值）否则 `seed = channel-count-by-config[conf] ?: channel-count`（<1 当 1），出 op `N`（HSETNX）——语义同基线 `DesiredWorldChannelCount`（种子不钳上限）。
  2. 活节点 = 目录在场、且不被 `NodeAvailability` 排除（5.5 钩子）的节点，**按数值升序**。没有活节点 → 跳过（计 `world_ticks_total{result=no_nodes}` 一次）。
  3. **覆盖**（per-node 模式）：每个活节点若没有这个 conf 的 ACTIVE 记录 → 在它上面加一个。
  4. **补足**：`ACTIVE 数 < d` 就继续加（ACTIVE 数包含宽限期内缺席节点上的记录——避免抖动）。
  5. 加一个频道 = 发号（租约无效 → 本拍停止全部新建）→ 取该 conf ACTIVE 记录未用的**最小 slot**（≥999 → ERROR 停止）→ 按 §4.6.3 选节点 → op `S`（`state=ACTIVE`、`created_ms = state_since_ms = now`）。
  6. 多出期望数的 ACTIVE 频道**不删**（同基线补建只增不减，`world_init.go:181`），交给缩容。
- **P6 再平衡**（只在 `coverage=hash`）：§4.8。
- **P7 写入**：ops 非空 → §4.4 的写入 Lua；结果按 §4.4 处理。

#### 4.6.3 放置策略

| 模式 | 新频道放在哪 | 理由 |
|---|---|---|
| `per-node`（5.1 缺省） | 该 conf ACTIVE 数最少的活节点；并列取节点号小的 | 覆盖先行（0 个的节点天然最少），其余均匀摊开；本节点 63 的可选频道更均衡 |
| `hash`（5.2 起缺省） | `assignNodeByHash(conf*1000 + slot, 活节点按数值升序)`，FNV-1a 32 + **无符号取模**（§1.5.5 的黄金值） | 补建与再平衡用同一个键（修 B3）；节点按数值排序（基线字典序没有意义，D5） |

#### 4.6.4 「每节点覆盖」模式（per-node）

- 保住 I1：每个活节点对每张 World 图至少 1 个 ACTIVE 频道，63 按地图换场景永远能在本节点完成（直到 5.2）。
- 实际频道数 ≥ `max(desired, 活节点数)`；新节点加入时，若已有频道集中在老节点上，覆盖会在新节点**再加**一个，使总数可能超过 `max(desired, 活节点数)`——不自动回收，扩缩容开着时由缩容收敛。
- 缩容不得删掉某节点上某图的**最后一个** ACTIVE 频道（§4.7）；不做择机再平衡（§4.8）。
- 结果：缩容牺牲者所在节点**一定还有同图的 ACTIVE 兄弟频道**，缩容永远能在同节点内完成，不依赖 5.2 / 5.5。

### 4.7 自动扩缩容（缺省关）

判定规则照搬基线 `world_autoscale.go:177-257`，Java 收紧三处（D9）。

- **负载集**：该 conf 的 ACTIVE 记录、节点在场、目录里已建出且没在排空；人数取目录上报的 `player_count`（节点权威计数，不含预占）。DRAINING 不参与（同 `:186-191`）。
- **顺序**：按人数升序 → `cooldown[conf] > now` 则跳过（同 `:205-207`；冷却读不到 = 快照失败，整拍不动，比基线「读错当不在冷却」保守，`:491-494`）→ `minCh = max(1, min-channels)`。
- **缩容**：`len > minCh` 且最空者 < `scale-in-players` 时，按升序找第一个同时满足以下条件的牺牲者（同 `pickScaleInVictim` 的骨架，`:295-322`）：
  1. 人数 < 缩容线（升序，遇到 ≥ 即停）；
  2. 不是镜像源：`MirrorSources.isSource(sceneId)`，5.1 恒「否」；查询出错按「是」（fail-closed，同 `:281-293`）；
  3. **同节点兄弟**：牺牲者所在节点上还有这个 conf 的另一个 ACTIVE、已建出的频道（per-node 模式强制；hash 模式下牺牲者人数为 0 时可免）；
  4. **同节点余量**：`Σ_{同节点同图其它 ACTIVE 频道} max(0, scale-out-players − n) ≥ 牺牲者人数`（基线算全图余量 `:259-274`；Java 改派只能落同节点）。

  找到 → ops：记录转 DRAINING（`drain_reason=SCALE_IN`、`state_since_ms=now`）、`desired = clamp(desired − 1)`（同 `:339-341`）、`cooldown[conf] = now + cooldown`；记 `scale_in/ok`。
  找不到 → 记 `scale_in/no_victim`（基线只打 INFO，`:224-228`）。
- **扩容**：最空者 ≥ `scale-out-players`（全部到线）→ `desired ≥ max-channels (>0)` → ERROR + `scale_out/max_reached`（同 `:234-241`）；否则 `desired + 1`、打冷却、记 `scale_out/ok`，
  同一拍 P5 把新频道补出来（同 `:243-248` 的「立刻建出来」）。
- **钳制**：`clamp(n) = min(max(n, max(1, min-channels)), max-channels>0 ? max-channels : ∞)`（同 `setDesiredWorldChannelCount`，`:101-117`）。
- **缺省值**全同 `config.go:297-331`；Java 额外在启动时校验：开启时 `scale-in-players < scale-out-players`（基线只在注释里要求宽带，`config.go:309-314`）、`max-channels ≤ 999`（slot 键位宽）、
  `min-channels ≤ max-channels`（max > 0 时）。

### 4.8 再平衡

- **urgent（节点已死）**：Java 由 P2 + P5 完成——删记录、发新号在活节点补建；归入补建，**不占预算**（基线 urgent 占预算，`world_rebalance.go:69-92`）。基线「节点活着但角色变了、频道里还有人就拒绝迁移」
  （`:168-178`）在 Java 5.1 不存在（没有角色）。
- **择机迁移**（只在 `coverage=hash`）：ACTIVE、已建出、人数 0、预占数 0（迁移前对候选显式 KEYS 读一次 `ZCOUNT resv now +inf`）、当前节点 ≠ `落点(conf, slot)` →
  在**同一次写入**里：目标节点上加一条同 slot 的新 ACTIVE 记录（新号）+ 旧记录转 DRAINING（`drain_reason=REBALANCE`）。旧节点下一秒拉到计划，空频道立即销毁。永远不迁有人的频道（同 `:43-49`、`:195-197`）。
- **预算** `rebalance.max-migrations-per-tick`：缺省 10，0 = 关闭，负数按 10（同 `:127-133`）；按**计划出的**迁移数计（Java 计划即写入，不存在「失败不占预算」，修 B9 后半）。
- **触发**：活节点集合与上一拍不同，或距上次满 `rebalance.interval`（缺省 300 s；0 = 只按集合变化，同 `config.go:199-205`）。
- **调试端点**（可选）：基线 `/debug/rebalance-plan`（§1.6.5）。Java 在管理端口加只读 actuator 端点 `worldchannels`（当前计划 + 待迁移列表）；管理端口缺省只绑本机、只暴露 health 与 prometheus
  （`architecture.md:696-699`；`xm-scene-manager/src/main/resources/application.yaml:29-33`），要显式加进暴露清单。

### 4.9 孤儿与死节点（小结）

| 情形 | 基线 | Java |
|---|---|---|
| conf 从 World 表删掉 | fullSync 时 SCAN 删整组，节点在就 DestroyScene（C++ 有人改派默认大世界）；整组过屏障（§1.8） | P1 转 DRAINING → 节点同节点改派到默认大世界 → 销毁 → P3 删记录；节点缺席满宽限期 → P2 删 |
| zone 不再有节点 | 保留集合（`orphan_cleanup.go:66-72`），节点回来后再平衡 | 宽限期后 P2 删光记录；节点回来后 P5 用新号重铺。desired 保留 |
| 节点死亡 | 懒改派 / urgent 迁移 + 再入屏障（同号改派） | 缺席满 20 s 删记录、新号重铺；玩家断线重连时按原地图选（`SceneAssigner.java:64-79`） |
| 节点用同一节点号快速重启（宽限期内） | 新进程注册 → PUT → 补建对全部频道重发 CreateScene | 新进程启动时拉计划、按同号重建 ACTIVE 频道（§4.10.5），无重铺抖动 |

### 4.10 scene 节点侧

#### 4.10.1 计划跟随 `ChannelPlanFollower`（新，`scene-sched` 线程）

- 每 `xm.scene.channel-plan-poll-interval`（缺省 1 s）：节点号租约 `isValid()` 为假 → 跳过（计数）；`GET ver`，与上次应用的相同 → 结束；不同 → 一段只读 Lua 一次取回 `ver` 与 `HGETALL ch`，
  只留 `node_id == 本节点号` 的记录 → 投递逻辑线程 `world.applyChannelPlan(ver, records)` → 完成后 `publisher.requestPublishNow()`。
- 读失败只 WARN 并计数，下一秒重试；**读失败时不应用任何东西**（尤其不把本地场景当孤儿）。
- 节点身份 = 节点号：当前持有节点号 N 的进程承载 N 名下的频道，与实例无关（所以不需要基线 SM 侧的 `WRONG_TARGET` 一类判定）。

#### 4.10.2 `SceneWorld.applyChannelPlan(version, records)`（逻辑线程）

- `version ≤ appliedVersion` → 忽略（单个拉取者不会乱序，守卫只防将来改动）。
- **ACTIVE 记录**：
  - 本地没有 → `createScene(sceneId, configId)`：两个号都不能为 0，conf 必须是本节点 World 表里的世界图（同时补上基线 gRPC 入口缺的零号检查，B12）；不满足 → 不建、ERROR、
    计 `xm_scene_channel_plan_applies_total{result=rejected}`（领导者从目录看到它缺席，计 `world_channels{state=missing}`）。已存在但 conf 不同 → 同样拒绝。按 scene_id 幂等。
  - 本地有且在排空 → 改回 ACTIVE（排空超时回滚）。
- **DRAINING 记录**：本地有 → 标排空；本地没有 → 什么也不做。
- **本地有、计划里没有** → 按排空处理（节点侧孤儿，例如滚动升级前本地自发号建的场景）。
- 记 `appliedVersion`，立即推进一次排空（§4.10.3）。
- `Scene` 增加只在逻辑线程读写的状态 `ACTIVE / DRAINING`；构造函数改为接收完整 `SceneInfoComp`（5.3 填 mirror / dungeon / creators）。`sceneEntries()` 带上 `draining`。
- 现有只用本地号的 `createScene(int configId)` 保留为包内测试入口（大量单测在用），生产路径只走带号的版本。

#### 4.10.3 排空与同节点改派（`ChannelEvacuator` 的本节点实现）

每次应用计划后推进一次，另外每秒一次（挂在已有的每秒任务上，`SceneNode.java:300-307`）：

- 对每个排空中的场景里的每个在场玩家，目标按顺序取：
  1. **本节点同图** ACTIVE 场景里人数最少的（并列取场景号小的，无符号）；
  2. 没有 → **本节点默认大世界**（World 第一行）ACTIVE 场景里人数最少的（换图，落出生点）——与基线排空改派的落点规则一致（§1.9.3），只在孤儿图或 hash 模式下出现；
  3. 都没有 → 原地不动，计 `xm_scene_channel_relocations_total{result=blocked}`（per-node 模式下不会出现）。
- 改派 = `switchScene(player, target)`（`SceneWorld.java:459-476`）：旧视野的人收到 51；本人收到 79（新 scene_id）/ 21 / 47；同图保留坐标；写位置记录；触发组队跟随钩子。
  **不存盘、不动归属**：玩家留在同一进程，owner_epoch 不变——这正是 Java 能省掉基线「存盘 → handoff 标记 → EnterScene(0,0)」（`player_lifecycle.cpp:2249-2363`）的原因。
- 人数为 0、且没有指向它的在途进场（`pendingEnters` 的 `sceneId`，`SceneWorld.java:120`、`:985`）→ 从 `scenes` 移除、重算人数指标（`:189-197`）、计 `xm_scene_channels`。
- **6.3 预留**：战斗冻结中的玩家跳过改派，等下一次推进。

#### 4.10.4 进场重定向

`onPlayerLoaded` 里取到的目标场景（`SceneWorld.java:314`）在排空中 → 按 §4.10.3 的同一顺序换成兄弟场景；都没有就进排空中的那个、随后被改派。gate 只按节点路由、不看场景号
（`ClientDispatcher.java:531`），79 给出的就是实际进入的 scene_id，位置记录也写实际场景（`SceneWorld.java:380`）。目标场景已销毁 → 维持现状回 3023（`:314-318`）。
`onPlayerEnter` 的「场景在不在本节点」检查（`:264-267`）对排空中的场景仍放行（在途进场挡住销毁）。

#### 4.10.5 启动与停服

- `doStart` 里，`SceneWorld` 建好之后、链路监听之前（替换 `SceneNode.java:280-283`）：`SADD xm:world:zones <zone>` → 同步拉一次计划、经 `callOnLogic` 应用（只建 ACTIVE）→ 再起 `ChannelPlanFollower`。
  拉取失败只 WARN、照常启动（不带频道），由拉取者每秒重试。新节点计划里没有它的记录 → 以零个场景启动、上报目录，领导者下一拍按覆盖规则给它铺频道。
- 停服：`release` 第一步停 `ChannelPlanFollower`，再按现有顺序摘目录（`SceneNode.java:409-424`）。租约丢失：停 `ChannelPlanFollower`（与 `onLeaseLost` 一起，`:542-557`）。

### 4.11 分配（`SceneAssigner`）的改动

1. zone 校验与可用节点过滤不变（`SceneAssigner.java:56-62`）。
2. **候选** = 目录里 `scene_config_id == conf`、`scene_id ≠ 0`、**`!draining`** 的场景条目。**不读计划**：节点在拉到计划后 ≤1 s 内标排空并立即补发目录，窗口由 §4.10.4 的重定向兜住（D13）。
3. **原实例**（`:96-112`）：仍然「还在就不看人数」，但要求条目没在排空；否则按地图选。选中原实例也写一条预占（单键 ZADD），让并发分配看得见。
4. **地图回落**不变（`:70-79`），「有承载」改为「有非排空的候选」。
5. **选择与预占**：候选按 (node_id, scene_id) 无符号升序排好，交给一段 Lua（键共用 `{z:<zone>}`）：

   ```lua
   -- KEYS[i] = xm:world:{z:<zone>}:resv:<scene_id_i>
   -- ARGV[1]=ttl_ms  ARGV[2]=player_id  ARGV[2+i]=目录人数_i
   local t = redis.call('TIME'); local now = t[1]*1000 + math.floor(t[2]/1000)
   local best, load
   for i = 1, #KEYS do
     redis.call('ZREMRANGEBYSCORE', KEYS[i], '-inf', now)
     local l = tonumber(ARGV[2+i]) + redis.call('ZCARD', KEYS[i])
     if best == nil or l < load then best, load = i, l end      -- 严格小于：并列取排序靠前的（节点号小、再场景号小）
   end
   redis.call('ZADD', KEYS[best], now + tonumber(ARGV[1]), ARGV[2])
   redis.call('PEXPIRE', KEYS[best], ARGV[1])
   return {best, load}
   ```

   - 成员是 player_id：同一玩家重复分配（login 重试）只刷新到期时间，不重复计数。
   - **TTL 缺省 10 s**：覆盖「login 夺权等待（缺省 3 s，`xm-login/src/main/resources/application.yaml:95`）+ 加载 + 节点下一次上报（5 s）」；节点报上新人数后、预占到期前会重复计一次，偏保守。
   - `reservation-ttl = 0` 关闭预占，退回只看目录人数。
   - 并列规则与现在 `Candidate.isBetterThan` 一致（`SceneAssigner.java:151-165`），并且是确定的（基线依赖 SMEMBERS 顺序，B13）。
   - Lua 出错按基础设施故障上抛（契约 `:25-28`）→ login 回 3023（`EnterGameHandler.java:260-266`）。
6. 第 2–5 步抽成 `ChannelSelector.select(zone, conf, excludeSceneId, playerId)`（`excludeSceneId` 给 5.2 的「换到同图别的频道」用，5.1 传 0），login 进场与 5.2 的跨节点换场景共用（§0.4）。
   应答形状、tip 码、提供方指标不变。
7. **不做**基线 `createMainWorldScene` 的「数据面按需铺设」慢路径（`createscenelogic.go:133-159`，D13）。就绪由健康组件保证（§5.2）。

### 4.12 63（场景内换场景）的改动

在 `SceneWorld.resolveSwitchTarget`（`SceneWorld.java:229-248`）：

- **指定 scene_id**：目标在本节点且 ACTIVE 才放行；目标在排空中 → 返回空 → 3023（D11）；不在本节点 → 3023（5.2 前）。
- **只带当前地图**（与基线对齐）：在本节点同图 ACTIVE 场景里选人数最少的，**当前场景的人数含自己**；并列时**优先留在原地**，再取场景号小的；当前场景在排空中就把它排除。
  选中原场景 → 回成功、不发 79（同基线「挑回原频道幂等早退」，`mmorpg-client-contract-scene.md:248-250`）；选中别的 → 51 / 79 / 21 / 47、坐标保留。
- **只带别的地图**：本节点该图 ACTIVE 场景里选人数最少的（并列取场景号小的）；per-node 覆盖保证一定有。
- 本节点选不到 → 3023（与现在相同）。改为返回 `SwitchTarget`（§0.4），同步更新 `ClientRequestHandler.java:203-207` 的 javadoc。

### 4.13 组队跟随的配合

`TeamFollowService.followLeader`（`TeamFollowService.java:167-191`）：队长所在场景在排空中 → 不跟随，计新结果 `LEADER_SCENE_DRAINING`。队长自己随后被改派时 `switchScene` 会以「自己进场」
触发钩子，把本节点队员扇出到他的新频道（`:28-34`），队伍自然收拢，不会把队员拉回排空频道。基线的跟随可以进排空频道（B5）。

### 4.14 失败处理

| 情形 | 行为 |
|---|---|
| Redis 不可达（领导者） | 本拍跳过（`world_ticks_total{result=error}`）；续期失败满 20 s → 自动失去领导；计划不变。数据面读目录 / 预占失败 → assign 抛异常 → login 回 3023 |
| Redis 不可达（scene） | 拉取失败计数、每秒重试；已有场景照常服务；不建、不删 |
| 写入返回 −1 / −2 | −1：放弃该 zone 领导；−2：本拍作废，下拍重读 |
| 发号租约无效 | 本拍不新建（fail-closed，`result=no_lease`）；其它步骤照做 |
| 没有活节点 | P5 跳过；计划里已有记录按宽限期规则处理 |
| 节点拒绝某个 conf（双方 World 表不一致，如滚动更新配表期间） | 节点 ERROR + `plan_applies{rejected}`；领导者 `world_channels{state=missing}`；运维收敛配表 |
| 双领导窗口 | 旧令牌写入被 Lua 拒（−1）；即使令牌恰好相同也过不了版本号 CAS（−2） |
| 一拍执行到一半进程死 | 写入是单段 Lua，要么全有要么全无；新领导者重新规划 |
| 节点排空到一半死 | 记录仍是 DRAINING；节点用同号重启时不建 DRAINING 频道 → `applied_plan_version` 追上、场景缺席 → P3 删记录；不重启 → P2 删 |
| 排空迟迟排不空 | SCALE_IN：超时回滚 ACTIVE（D10）；ORPHAN / REBALANCE：保持 DRAINING（不可分配）、WARN |
| 节点上有计划外的场景 | 按排空处理：空了就销毁；有人就同节点改派 |
| 节点号被新进程复用而旧进程仍活着 | 5.5（新旧进程会各有一份同号频道；gate 按租约代次只连新进程，`architecture.md:119-121`） |

### 4.15 需要同步修改的文档与登记

- **`architecture.md`**：§2 表格（scene-manager 职责加「主世界频道编排（分 zone 领导）」，`:50`）；新增「主世界频道」小节；§6 目录新字段、`xm:world:*` 与 scene-manager 发号租约（`:506-543`）；
  §9 `:667` 改为「场景号由 scene-manager 全服租约发」；§11 新指标、`:705` 的「场景只在启动时建」改写。
- **代码注释**：`location.proto:18`、`SceneMetrics.java:27-28`、`SceneWorld.java:185-188`、`SceneAssigner.java:29-30`、`ClientRequestHandler.java:203-207`、`SceneWorld.java:229-233`。
- **PARITY**：新增一行「主世界多频道（频道计划 / 铺设 / 扩缩容 / 再平衡 / 孤儿清理 / 进场软预占）」；更新 `PARITY.md:27`「场景分配」（预占、排空频道排除）、`:29`（63 同图跳频道、缩容 79）、
  `:91`（原实例排空中按地图选）；「mmorpg 待做（可选）」登记 §7.1 的 B1–B5、B7–B12。
- **盘点**：`scene-manager-match.md:173`（再平衡 not_applicable → 已做）、`:197`（选主 / 发号随 5.1）、`:44`、`:180`（勘误，§7.3）；`scene-core.md:165`、`:177`（勘误）及两项的 java 列。
- **路线图**：`roadmap.md:70` 状态列。

---

## 5 配置与常量

### 5.1 基线（本批相关）

| 键 | 缺省 | 开发 yaml | 作用 | 出处 |
|---|---|---|---|---|
| `WorldChannelCount` | 1 | 1 | 每图种子频道数（钳 ≥1） | `config.go:95-98`；yaml `:134` |
| `WorldChannelCountByConfId` | 空 | `"1": 16` | 按 conf 覆盖（键是字符串） | `config.go:100-107`；yaml `:142-143` |
| `StrictNodeTypeSeparation` | true | true | 用途池空时拒绝而非回落全池 | `config.go:109-118`；yaml `:159` |
| `NodeLoadWeightSceneCount` / `PlayerCount` | 1.0 / 0.01 | 同 | 负载分权重 | `config.go:120-128`；yaml `:168-169` |
| `MaxRebalanceMigrationsPerTick` | 10 | 10 | 每轮迁移上限；0 关；<0 当 10 | `config.go:190-197`；`world_rebalance.go:127-133` |
| `RebalanceCheckIntervalSeconds` | 300 | 300 | 周期再平衡；0 关 | `config.go:199-205`；yaml `:181` |
| `CleanupOrphanChannelsOnStartup` | true | true | fullSync 时清孤儿 | `config.go:232-240`；yaml `:186` |
| `WorldAutoscale.Enabled` | false | 未配置 | 总开关 | `config.go:298` |
| `.CheckIntervalSeconds` | 30 | — | ≤0 当 30 | `config.go:301`；`world_autoscale.go:127-130` |
| `.ScaleOutPlayerThreshold` | 2000 | — | 所有频道 ≥ 才扩 | `config.go:303-307` |
| `.ScaleInPlayerThreshold` | 100 | — | 最空 < 才缩 | `config.go:309-314` |
| `.MinChannelsPerMap` | 1 | — | 硬下限 1 | `config.go:316-318` |
| `.MaxChannelsPerMap` | 16 | — | 0 = 不限 | `config.go:320-322` |
| `.CooldownSeconds` | 120 | — | ≤0 当 120 | `config.go:324-326`；`world_autoscale.go:497-500` |
| `.DrainTimeoutSeconds` | 300 | — | 排空标记 TTL，**不限制排空时长**（B4） | `config.go:328-330`；`world_autoscale.go:345-348` |
| `SceneReentryBarrierSeconds` | 0 → 20 s | — | 只能调高（5.5） | `config.go:216-224`；`constants/reentry_barrier.go:65-117` |
| `LeaderLockTTLSeconds` / `LeaderEligible` | 30 / true | — | 选主 | `config.go:65-86` |
| zrpc `Timeout` | — | 8000 ms | 懒改派的同步 CreateScene 不在预算内 | yaml `:3-14` |
| 写死 | 锁 60 s / 等锁 10 s / 轮询 200 ms / RPC 5 s / 刷新 5 s / 孤儿 SCAN 256 | — | — | `world_init.go:35`、`:43-44`、`:51`；`load_reporter.go:31`；`orphan_cleanup.go:34` |

`MirrorSourceNodeLoadCap`、`MirrorDedupBySource`、`InstanceIdleTimeoutSeconds` 等属 5.3；`Agones.*` 不适用。

### 5.2 Java：xm-scene-manager（`xm.scene-manager.world.*`）

| 键 | 缺省 | 对应基线 / 说明 |
|---|---|---|
| `channel-count` | 1（<1 当 1） | `WorldChannelCount` |
| `channel-count-by-config` | 空（map<int,int>，值 ≤0 忽略、>999 拒启） | `WorldChannelCountByConfId`；本机切片见 §5.4 |
| `coverage` | `per-node`（5.2 改 `hash`） | Java 过渡约束（D6） |
| `tick` | 5s | `LoadReportInterval` |
| `dead-node-grace` | 20s | 数值同 `SceneReentryBarrier`（`constants/reentry_barrier.go:69`），但只为防快速重启抖动，不是屏障 |
| `leader-lock-ttl` | 30s（续期 = ttl/3，有效 = 2/3 ttl） | `LeaderLockTTLSeconds` |
| `leader-eligible` | true | `LeaderEligible` |
| `cleanup-orphans` | true | `CleanupOrphanChannelsOnStartup` |
| `reservation-ttl` | 10s（0 = 关闭预占） | 基线硬计数 |
| `rebalance.max-migrations-per-tick` | 10（0 关，<0 当 10） | `MaxRebalanceMigrationsPerTick` |
| `rebalance.interval` | 300s（0 = 只按节点集合变化） | `RebalanceCheckIntervalSeconds` |
| `autoscale.enabled` / `check-interval` / `scale-out-players` / `scale-in-players` / `min-channels` / `max-channels` / `cooldown` / `drain-timeout` | false / 30s / 2000 / 100 / 1 / 16 / 120s / 300s | `WorldAutoscaleConfig`；启动校验见 §4.7 |

- 原有 `xm.scene-manager.default-world-config-id` 不变（`SceneManagerProperties.java:12-13`）。
- 常量：发号租约 `NodeTypes.SCENE_MANAGER`、作用域 0、`[1, 1023]`、TTL 15 s；slot 上限 998（键 `conf*1000+slot`）。
- 新增健康组件 `worldChannels`（不进 liveness）：对 `xm:world:zones` 里每个有活节点的 zone，每张 World 图至少 1 个「ACTIVE、节点在场、目录里已建出且未排空」的频道才 UP；
  本机脚本在 scene 起来之后等它就绪（scene-manager 先于 scene 启动，`tools/local/start-slice.sh:69`、`:76`）。

### 5.3 Java：xm-scene

- `xm.scene.channel-plan-poll-interval`：缺省 1s。
- 排空推进挂在已有的每秒任务上，不新增配置。拉取模型下 5.1 不需要 Dubbo 提供方与 `rpc-port`（若 Q2 选推送，则与 4.5 的 `xm.scene.asset-rpc-port` 合并为 `xm.scene.rpc-port`）。

### 5.4 本机切片

`xm.scene-manager.world.channel-count-by-config.1=16`（与基线开发配置一致，Q4）。多节点用例见 §9.6。

---

## 6 指标

### 6.1 基线

指标名一律 `scene_manager_` 前缀（`Subsystem` 统一，`metrics.go:36` 起各处）。

| 指标 | 标签 | 写入点 | 出处 |
|---|---|---|---|
| `world_autoscale_total` | zone_id、action（scale_out / scale_in）、outcome（ok / drained / error / max_reached） | 扩缩容 | `metrics.go:240-244`、`:483-488` |
| `rebalance_migrations_total` | zone_id、reason（node_gone / better_home）、outcome（migrated / failed） | 再平衡执行 | `metrics.go:69-73`；`world_rebalance.go:84`、`:87` |
| `rebalance_pending` | zone_id、reason | 每次调用写前后两次；降级时 Reset | `metrics.go:75-79`、`:431-435`；`world_rebalance.go:59-60`、`:103-105` |
| `node_player_count` / `node_scene_count` / `node_load_score` | node_id、zone_id、role | 每 5 s；节点离开时删序列 | `metrics.go:35-51` |
| `nodes_by_role` | zone_id、role | 每 5 s；本进程 ZoneId 补 0 | `metrics.go:54-58`；`load_reporter.go:1016-1019` |
| `is_leader` | — | 选主 | `metrics.go:63-65` |
| `reentry_barrier_blocked_total` | zone_id、site（rebalance / reassign / world_channel_lazy / orphan_cleanup 等） | 屏障挡住时 | `metrics.go:296-300`；`reentry_barrier.go:40-54`、`:181-190` |
| `enter_scene_stage_seconds` | zone_id、stage | EnterScene 各阶段 | `metrics.go:199-205` |
| `enter_scene_rejected_total{reason="scene_gone"}` | zone_id | 显式场景预占失败 | `metrics.go:120-127`；`enterscenelogic.go:657` |
| `safego_panic_total{point="scene_manager.world_autoscale"}` | point | 后台循环 panic | `world_autoscale.go:136-141` |

告警（`deploy/k8s/scene-manager-alerts.yaml`）：`SceneManagerWorldPoolEmpty`（`:79`）、`SceneManagerWorldNodeSaturated`（`:103`）、`SceneManagerLoadScoreDispersionLow`（`:116`）、
`SceneManagerRebalanceStalled`（`:137`，`rebalance_pending{reason="node_gone"}` 持续 > 0）、`SceneManagerRebalanceFailureRate`（`:155`）。

### 6.2 Java（Micrometer；**不以 zone / scene_id / 节点号 / player_id 作标签**，`architecture.md:703-706`；`AGENTS.md` §5）

| 进程 | 指标 | 类型 | 标签 | 对应 / 说明 |
|---|---|---|---|---|
| scene-manager | `xm_scene_manager_world_leader_zones` | Gauge | — | 本副本领导的 zone 数，0 = 跟随者（基线 `is_leader`） |
| scene-manager | `xm_scene_manager_world_ticks_total` | Counter | `result` = ok / not_leader / fenced / conflict / no_lease / no_nodes / error | 每 zone 每拍一次 |
| scene-manager | `xm_scene_manager_world_tick_seconds` | Timer | — | 一拍耗时（快照 + 目录 + 规划 + 写入），固定 SLO 桶 |
| scene-manager | `xm_scene_manager_world_channels` | Gauge | `scene_config`（受 World 表约束）、`state` = active / draining / missing | 本副本领导的各 zone 合计；降级时清零 |
| scene-manager | `xm_scene_manager_world_autoscale_total` | Counter | `action` = scale_out / scale_in，`outcome` = ok / drained / reverted / max_reached / no_victim / error | 基线 `world_autoscale_total` |
| scene-manager | `xm_scene_manager_rebalance_pending` | Gauge | `reason` = node_gone / better_home | node_gone = 宽限期内缺席节点上的记录数；降级时清零 |
| scene-manager | `xm_scene_manager_rebalance_migrations_total` | Counter | `reason`、`outcome` = planned / done | done = 旧记录收尾时 |
| scene-manager | `xm_scene_manager_assign_seconds` | Timer | 不变 | 预占 Lua 计入耗时 |
| scene | `xm_scene_channels` | Gauge | `state` = active / draining | 基线 Agones 单元计数的替代（`scene_event_handler.cpp:30-56`） |
| scene | `xm_scene_channel_plan_applies_total` | Counter | `result` = applied / rejected / skipped_lease | |
| scene | `xm_scene_channel_plan_poll_failures_total` | Counter | — | 拉取读失败 |
| scene | `xm_scene_channel_relocations_total` | Counter | `result` = same_map / default_world / blocked / enter_redirect | |
| scene | `xm_scene_team_follow_total` | Counter | `result` 新增 `leader_scene_draining` | 现有组队跟随指标的新取值 |
| scene | `xm_scene_players` | Gauge | `scene_config`（不变） | `architecture.md:769` |

---

## 7 隐患与边界

### 7.1 基线自身的隐患（移植时照搬或显式偏离；建议进 PARITY「mmorpg 待做（可选）」）

1. **B1 缩容改派落默认大世界，不是同图**。C++ 发 `EnterScene(0, 0)`（`player_lifecycle.cpp:2340-2349`，注释 `:2342-2344`；`scene_node_service.cpp:93-97`「to the main world」），
   SM 取 World 第一行（`enterscenelogic.go:1273-1281`、`:1288-1298`；只有跨 zone「等待落点」才回读地图，`:437-444`）。而 Go 注释（`world_autoscale.go:25-26`）、配置注释（`config.go:309-310`）、
   设计稿（`world-channel-autoscale.md:25-26`）、inventory（`scene-manager-match.md:180`）都写「同图其它频道」。只有被缩的恰好是默认图时才是同图。Java D8。
2. **B2 期望频道数一经播种即锁定（扩缩容关闭时也一样），调小不减频道**（`world_init.go:174`、`:181`；`world_autoscale.go:68-99`）。改配置要 HDEL；设计稿 `world-channel-system.md:12`
   「自动伸缩启用后 Redis 才是权威」不准。Java 照搬语义（N5）。
3. **B3 铺设与再平衡用不同哈希键** → fullSync 先补建后再平衡（`load_reporter.go:710-711`），≥2 个节点时刚建的空频道立刻被当 `better_home` 搬一次（每轮至多 10 个），白做建 / 删 RPC；
   懒改派与补建内改派又各用一种键（§1.5.5）。Java D5。
4. **B4 `DrainTimeoutSeconds` 名不副实**：排空标记只在决策（`world_autoscale.go:189`）与孤儿清理（`orphan_cleanup.go:198`）里被读；频道在设标记前已被 SREM（`world_autoscale.go:327` 早于 `:349`），
   所以 `:189` 那道检查冗余；收敛只看 Redis 人数（`:412-427`），无超时强制收尾——人数漂移成永久 > 0 时频道永远留在排空集合、每 30 s 被 DestroyScene 一次。Java D10。
5. **B5 显式 scene_id 可进入排空中的频道**（`enterscenelogic.go:1177-1248`；`scene_atomic.go:39-44`；映射到收尾才删 `world_autoscale.go:459`），组队跟随（`player_team.cpp:475-482`）与客户端指定都会。Java D11。
6. **B6 关掉扩缩容会让排空停在半路**：`sweepDrainingWorldChannels` 只被扩缩容循环调用（`world_autoscale.go:121-126`、`:166`）；唯一兜底是那张图从 World 表删掉后孤儿清理连带清掉排空集合（`orphan_cleanup.go:124-131`）。
   Java 的排空推进在领导者每拍的 P3，与扩缩容开关无关。
7. **B7 懒改派口径不一**：不要求节点已离开注册表、不改 scene_count、不转 Agones、被屏障挡住回 1 而非 17、同步 CreateScene 挂在请求预算外（`world_init.go:471-506`；`enterscenelogic.go:1282-1283`、`:467`；
   yaml `:12-13`）。Java 无懒改派。
8. **B8 迁移改写无 CAS**，代码自认是降级窗口里最危险的写路径（`world_rebalance.go:72-74`）。Java D2。
9. **B9 Create / Destroy RPC 无 deadline**：再平衡（`world_rebalance.go:235`、`:286`）、排空（`world_autoscale.go:416`）、孤儿清理（`orphan_cleanup.go:168`）都用进程主 ctx
   （`scene_manager_service.go:76`；扩缩容循环的 ctx 同一个，`world_autoscale.go:141`）；`RequestNodeDestroyScene` / `getOrDialNode` 不另加时限（`scene_node_client.go:121-135`、`:165-202`），
   `grpc.NewClient` 惰性连接（`:39-41`），黑洞节点要等 gRPC 建连失败（约 20 s，`world_init.go:46-50` 的注释）。另外失败的迁移不占预算（`world_rebalance.go:79-88`）。Java 无 RPC。
10. **B10 DestroyScene 应答区分不了「已销毁」与「排空中」**（`scene_node_service.cpp:109-115`），且 Go 先读人数后发 RPC（`world_autoscale.go:412-427`）：Redis 人数为 0 而 C++ 仍有带会话住户时，
    实体被遗留在节点上、无人再销毁。Java 由目录报告实际存在与否。
11. **B11 规划器在 Redis 读错时把人数当 0**（`load_reporter.go:276-284`；`world_rebalance.go:172`、`:195`），ZSCORE 非 Nil 错误时「仍注册」检查失效（`:173`）——可能误迁有人的频道。
12. **B12 gRPC CreateScene / DestroyScene 缺零号检查**（`scene_node_service.cpp:19-125`），muduo 入口有（`scene_handler.cpp:854-859`、`:932-936`），注释要求两处同步（`:896-898`）。
13. **B13 选频道并列依赖 SMEMBERS 顺序**（`scene_atomic.go:74-85`）。Java 写死 (人数, 节点号, 场景号)。
14. **B14 SM `DestroyScene` 不 SREM `world_channels`**（`destroyscenelogic.go:40-120` 只改反向索引与镜像集合，`:98`、`:101`），对世界频道调用会留下陈旧成员；全仓没有 SM `DestroyScene` 的生产调用方。
15. **B15 冷却读失败当「不在冷却」**（`world_autoscale.go:491-494`），Redis 抖动时可能连续伸缩。
16. **B16 没有频道容量上限**：BaseScene 表无容量列；tip 有 `kEnterSceneMainFull=3002`、`kEnterSceneSceneFull=3006`、`kEnterSceneGsFull=3011`（`scene_error_tip.proto:16`、`:24`、`:34`），
    但基线 C++ / Go 都不发（只有 `go/shared/serverbase/tipcode_test.go:42-43` 引用）。组队跟随可以无限堆进一个频道。Java 也不加（N2）。
17. **B17 多键 Lua 自称 Cluster-safe 但键无 hash tag**（`scene_atomic.go:51-68`），多候选时在 Cluster 上会 CROSSSLOT；基线部署是单机 Redis（yaml `:19-22`），实际无害。Java 键统一 `{z:<zone>}`。
18. **B18 死代码 / 兜底路径**：`resolveScene` case 2（§2.1）；`createMainWorldScene` 与 SM `CreateScene(MAIN_WORLD)` 无生产调用方（§2.2）。

### 7.2 Java 移植时会踩的

1. **无符号**：scene_id（uint64）在 HASH 字段、ZSET 成员、日志里一律 `Long.toUnsignedString`；FNV 取模 `Integer.toUnsignedLong`；目录人数 `player_count` 是 uint32、比较用
   `Integer.compareUnsigned`（现有写法 `SceneAssigner.java:151-165`）。
2. **线程**：计划应用、排空推进、场景增删只在逻辑线程；拉取在 `scene-sched`；scene-manager 控制面不在 Dubbo 线程上做阻塞 I/O（assign 的一次 Lua 与现状的一次目录读同级，`SceneDirectoryProvider.java:20`）。
3. **Lua 与字节**：计划值是 pb 字节，必须 `ByteArrayCodec`；不用 Redisson 批处理（遇 NOSCRIPT 不重载，`architecture.md:138`）。
4. **目录 TTL 与宽限期叠加**：节点异常退出后约 15 s 才从目录消失（`SceneDirectoryPublisher.java:27`），再过 20 s 宽限期才重铺——期间该节点的频道不可分配（assign 看不到它），玩家重连按地图落别的节点。
5. **混版本不可用**：旧版 scene 节点不拉计划、仍自建场景（scene_id 来自本地雪花）；新 scene-manager 看不到它们的 `applied_plan_version`。**5.1 上线必须整体停服切换**（Java 版尚未对外，可接受）。
6. **计划大小**：16 图 × 16 频道 + 覆盖 ≈ 300 条 × ~40 B ≈ 12 KB；只在版本号变化时整读。
7. **`xm:world:zones` 只增**：退役 zone 的领导者照常竞选（成本可忽略）；要清除需运维手工 SREM。
8. **robot 的同场景假设**：`MovementScenario.java:170-177`、`SkillScenario.java:85-87` 要求两个号进同一场景；16 频道下必然失败（§9.7）。
9. **组队跟随与改派的相互作用**：改派走 `switchScene` 会触发跟随钩子；没有 §4.13 的排空检查时，队员会被拉回排空频道、反复改派。

### 7.3 勘误

**对两份分区稿**

- 基线稿 §5.5「zone 未知 → 返回空数组」：实际返回该 zone 一条、`urgent / opportunistic` 为空数组（§1.6.5）。
- Java 稿 M6「DestroyScene 调用点没有单独限时……需要再核对 `getOrDialNode` 有没有兜底」：已核对，**没有兜底**（B9）。
- Java 稿「`node_directory.proto` 追加 `rpc_host = 7; rpc_port = 8`（与 4.5 同号同名）」：工作区里 4.5 已加上，5.1 的新字段从 9 编号（§4.3）。
- Java 稿的 `NodeDirectory.java:43-45`、`:52-62`、`:64-67` 是提交版行号；工作区版本 `publish` 在 `:45-47`、`list` 在 `:77-87`、`map` 在 `:89-92`。
- Java 稿 §3.6.2 把补建放在扩缩容之前、§3.7 说「本拍立刻补建」：两者顺序矛盾；本稿改为扩缩容在补建之前（P4 → P5）。
- Java 稿 §3.6.4「实际频道数 = max(desired, 覆盖需要)」：覆盖可能使总数更多（§4.6.4）。
- Java 稿的推送模型（`SceneChannelService.reconcile`）与「assign 读计划、1 s 缓存」：本稿改为拉取（D3）与「assign 只读目录」（D13），原方案列为 Q2。
- 两份稿对 B1（缩容落默认大世界）、B3（哈希键不一致）、B5（显式进排空频道）的结论一致，已复核。

**对 inventory**

- `scene-manager-match.md:180`「缩容排空时 C++ 把玩家改派到同图其他频道」→ 落默认大世界（B1）。
- `scene-manager-match.md:44`「期望频道数一旦启用自动扩缩容就以 Redis `world_desired_channels` 为准」→ 键名是 `world_channels:desired:zone:{z}`，且**不论扩缩容开没开**都以 Redis 为准（B2）。
- `scene-manager-match.md:173` 再平衡 not_applicable → 5.1 后为 Java 待做 / 已做；`:197`「Redisson RLock 可承担」→ RLock 的值进不了业务 Lua，选主用令牌锁（§4.4）。
- `scene-core.md:165`「config_id / scene_id 任一为 0 拒绝」→ 只有 muduo 入口；SM 用的 gRPC 入口没有零号检查（B12）。
- `scene-core.md:177`「MinChannelsPerMap 默认 1，按负载扩缩容」→ 扩缩容缺省关闭（`config.go:298`）。

**对 mmorpg 设计稿与参考契约**

- `docs/design/world-channel-autoscale.md:25-26`「玩家强制改派到同图其它频道」→ B1。
- `docs/design/world-channel-system.md:12`「自动伸缩启用后 Redis desired 值是运行期权威」→ B2；`:69-74`「`scene_id == 0` 按 `config_id` 去重」→ 已无此分支（gRPC 入口只按 scene_id 去重，muduo 入口拒 0）。
- `docs/reference/mmorpg-client-contract-scene.md:55` 引用的 `enterscenelogic.go:1228-1256` 已漂移，现为 `:1267-1298`。

---

## 8 建议的有意差异

### 8.1 建议采纳

| 编号 | 差异 | 理由 | 客户端可见？ | 两版同改？ |
|---|---|---|---|---|
| D1 | 频道计划集中在一张 HASH（`WorldChannel` pb），取代 SET + `scene:{id}:node/zone` + 反向索引 + 计数键的多键非事务写 | 一次 Lua 原子写；节点不需要 SM 维护的计数（目录自报） | 否 | 否 |
| D2 | 每 zone 一把领导锁；写入 Lua 同时校验令牌与版本号（CAS） | 修 B8；围栏键与数据键同槽；同 team 的 ver CAS 先例（`architecture.md:437-439`） | 否 | 否 |
| D3 | 节点按版本号**拉取**计划（每秒 GET ver），取代 SM 命令式 CreateScene / DestroyScene RPC | 发现源只有 Redis（`architecture.md:506-510`）；没有晚到推送、黑洞节点、RPC 无时限（B9）这类问题；启动与稳态同一条路径 | 否 | 否 |
| D4 | 频道与 (zone, node) 终生绑定：换节点 = 新号新建 + 旧的排空 | 基线同号改派有双活副本窗口（`world_init.go:260-265`）；Java 定位本就要求 (节点, 场景) 同时匹配（`SceneAssigner.java:96-112`） | 节点死亡 / 空频道迁移后 79 的号不同 | 否 |
| D5 | hash 模式补建与再平衡同一键 `conf*1000+slot`；节点数值排序；无符号取模 | 修 B3；Java int 有符号 | 否 | 否 |
| D6 | 5.2 之前 `coverage=per-node`，按「最少者」放置 | 保住 I1（63 在本节点完成）；5.2 切 `hash` | 频道总数 ≥ 节点数 | 否 |
| D7 | 软预占（TTL ZSET，成员 player_id）取代 INCR / DECR 硬计数 | 基线漏退还一处即漂移，DECR 非原子（`instance_lifecycle.go:362-379`）；Java 人数以节点内存为准 | 分布略有不同 | 否 |
| D8 | 排空在同节点改派：同图优先、否则同节点默认大世界；不存盘、不交接 | 同图符合基线设计意图（B1）；同进程不需要归属交接 | 是：缩容落同图、坐标保留、无加载 | 否（mmorpg 可选：B1） |
| D9 | 缩容牺牲者要求同节点同图兄弟 + 同节点余量 | 5.1 不做跨节点改派 | 否 | 否 |
| D10 | 缩容排空满 `drain-timeout` 回滚 ACTIVE（desired + 1） | 修 B4；5.1 没有跨节点兜底，不留永远半死的频道 | 否 | 否（mmorpg 可选：B4） |
| D11 | 排空中的频道拒绝显式进入（63 → 3023）；进场重定向到兄弟；组队跟随不进排空频道 | 修 B5 | 是（只在缩容窗口） | 否（mmorpg 可选：B5） |
| D12 | 死节点只按「目录缺席满 20 s」重铺容量，不设再入屏障 | 玩家数据安全由 owner_epoch 围栏保证（`architecture.md:583-600`）；重铺只动容量 | 否 | 否 |
| D13 | 数据面不做按需铺设；assign 只读目录、不读计划 | assign 不应成为第二个写者（基线为此加了补建锁，`world_init.go:27-44`）；窗口由节点重定向兜住 | 部署初期可能看到 3000 | 否 |
| D14 | 不接 Agones | 无 K8s / Agones 部署形态（`scene-manager-match.md:197`） | 否 | 否 |
| D15 | 5.1 不按节点用途分池（字段预留） | 全部节点都承载主世界；5.3 引入 | 否 | 否 |
| D16 | 指标不带 zone 标签 | `architecture.md:703-706` | 否 | 否 |
| D17 | 63 只带当前地图：本节点同图选最少（含自己），平局留原地 | 对齐基线「同图重选」；并列规则确定化（B13） | 是：从「一律不动」变为「可能跳频道」（与基线一致） | 否 |
| D18 | scene_id 由 scene-manager 全服租约发 | 计划要先有号再让节点建；全服唯一（I2） | 否（只要求非 0 唯一） | 否 |
| D19 | 排空推进与扩缩容开关无关（领导者每拍 P3） | 修 B6 | 否 | 否 |
| D20 | 选频道并列取 (节点号, 场景号) 小者，原实例直接用且也写预占 | 修 B13；保持 PARITY 有意差异 ⑥ | 否 | 否 |

### 8.2 列出但不建议首批采纳

- **N1 跨节点 63 / 跨节点缩容改派**：属 5.2 / 5.5。
- **N2 频道容量上限（发 3002 / 3006）**：基线没有（B16），需要两版同改并先定容量来源（BaseScene 无列）。
- **N3 借 31 的 `repeated SceneInfoComp` 发本图频道列表**：协议形状允许，但基线只发当前一条（`player_scene_handler.cpp:203-205`），客户端没有对应 UI；客户端可见，需两版同改。
- **N4 per-node 模式下回收覆盖造成的多余频道**（把空频道从频道多的节点「搬」到新节点而不是新增）：省几十个空场景的内存，首批不做。
- **N5 扩缩容关闭时以配置为准**（不再「播种即锁定」，B2）：会改变运维语义、且调小仍需缩容才能生效；保持基线语义，运维用 HDEL。
- **N6 节点用途 / 负载分选点**：随 5.3。
- **N7 再入屏障**：随 5.5 评估（Java 的安全面在 owner_epoch）。

### 8.3 待用户拍板 / 开放问题

1. **Q1 覆盖模式**：5.1 缺省 `per-node`（推荐），还是与 5.2 同批上线、直接 `hash`？
2. **Q2 计划下发**：节点拉取（推荐，D3），还是 Java 分区稿的推送（scene 导出 `SceneChannelService.reconcile`、领导者按目录 `rpc_host:rpc_port` 直连，与 4.5 共用端口与 `IsolatedDubboModule`）？
   推送的唯一实质收益是亚秒级创建与同步拿到「被拒的 conf」；拉取 1 s 内也能建出来、拒绝经目录反映。
3. **Q3 选主粒度**：分 zone（推荐：多副本可分担 zone，围栏键与数据键同槽），还是与基线一样全局一把？
4. **Q4 本机切片图 1 的频道数**：16（推荐，与基线开发配置一致、能跑通基线 robot 的 `SceneSwitch`，但要改 Movement / Skill 两个 robot），还是 1（robot 不改，但多频道路径只有单测覆盖）？
5. **Q5 缩容排空超时**：回滚 ACTIVE（推荐），还是保持 DRAINING 等 5.5 的跨节点改派？
6. **Q6「没有场景」时客户端所见**：应答内 3000（Java 现状，`EnterGameHandler.java:267-271`），还是对齐基线经 gate 推 23 `{3023}`（`entergamelogic.go:456-462`）？先在 PARITY 登记，是否本批对齐待定。
7. **Q7 PARITY「mmorpg 待做（可选）」**：是否把 B1–B5、B7–B12 都登记，还是只登记客户端可见的 B1、B5？
8. **Q8 预占 TTL**：10 s（推荐）是否需要随 login 的 `owner-claim-wait` 联动配置？
9. **Q9 63 并列留原地**（推荐）还是照基线取场景号小者（可能无谓跳频道）？

---

## 9 测试计划

### 9.1 基线测试对照（移植时逐条对照）

- 铺设与选频道：`logic_test.go` `TestInitWorldScenes_{Idempotent_NoDuplicateRedisEntries,MultipleChannels,MultipleChannels_Idempotent,StrictMode_SkipsInstanceOnlyZone,PerConfIdOverride}`
  （`:1635`、`:1665`、`:1688`、`:2203`、`:2241`）；`TestGetBestWorldChannel_{NotFound,SelectsLowestPlayerCount,AllEmpty}`（`:450`、`:1707`、`:1731`）；
  `TestCreateScene_MainWorld_{Idempotent,MultipleChannels_ReturnsLeastLoaded,ChannelCountDefault1_BackwardCompat}`（`:496`、`:1747`、`:1782`）；
  `TestChannelCountFor_{PerConfIdOverride,ClampsToOne}`（`:2221`、`:2235`）；`TestAssignNodeByHash_Deterministic`（`:459`）。
- 不可达与改派：`world_init_unreachable_test.go:112`、`:136`；`node_detach_deferred_test.go:513`。
- 预占与竞态：`logic_test.go:2649` `TestEnterScene_AtomicIncr_NoOrphanPlayerCount`、`:3225` `TestStress_EnterDestroyRace_NoOrphanPlayerCount`；`owner_epoch_test.go:2331`。
- 再平衡：规划器 `logic_test.go:2400`、`:2420`、`:2438`、`:2473`、`:2490`、`:2537`；执行器 `integration_test.go:383`、`:437`、`:495`、`:542`、`:612`（bufconn 假节点）。
- 扩缩容：`world_autoscale_test.go:61`、`:76`、`:91`、`:108`、`:135`、`:152`、`:164`、`:180`、`:192`、`:210`、`:241`、`:256`、`:271`；镜像 `world_autoscale_mirror_test.go:21`、`:41`、`:62`、`:78`。
- 孤儿清理：`orphan_cleanup_autoscale_test.go:22`、`:53`、`:96`；`logic_test.go:2508`（表空拒绝）、`:2521`（键名解析）。
- 死节点收尾保留世界频道：`logic_test.go:2847`。Agones（`agones_world_channel_test.go`）不适用。

### 9.2 纯函数单测（不连 Redis）

- **`ChannelPlacementTest`**：§1.5.5 的 FNV 黄金值（含 ≥ 2³¹ 的 `"2000"`、`"16000"`）、无符号取模、数值排序、per-node「最少者」并列取节点号小、slot 取最小未用、slot 上限 998。
- **`WorldChannelPlannerTest`**（输入快照 + 目录 + 缺席表 + 配置 + now，输出 ops）：
  - 播种 HSETNX、Redis 值优先于配置（对应 `TestDesiredWorldChannelCount_RedisWinsOverConfig`）；按 conf 覆盖（`_PerConfIdOverride`）；多频道补建（`_MultipleChannels*`）；补建只增不减。
  - per-node 覆盖：新节点加入补齐、不删任何节点上的最后一个；hash 模式按落点放。
  - 死节点：宽限期内不动、满宽限期删记录并在下一步用新号重铺；宽限期内缺席节点的记录计入 ACTIVE 数。
  - 孤儿 conf：ACTIVE → DRAINING(ORPHAN)、HDEL desired / cooldown；有效 conf 不动（对应 `TestOrphanCleanup_LeavesValidConfUntouched`）。
  - 排空收尾：`applied_plan_version` 未追上时不删；追上且缺席才删；排空超时 SCALE_IN 回滚、其它原因保持。
  - 发号租约无效 → 零新建、其它步骤照做；无活节点 → 不补建。
  - 写入批次的 `plan_version` 都是「期望 ver + 1」。
- **`WorldAutoscalerTest`**：逐条移植 `world_autoscale_test.go` 的 13 个用例（`ScalesOutWhenEveryChannelIsFull`、`DoesNotScaleOutWhenOneChannelHasRoom`、`RespectsMaxChannels`、
  `DrainsUnderpopulatedChannel`、`NeverDrainsTheLastChannel`、`MinChannelsIsClampedToOne`、`RefusesDrainWithoutHeadroom`、`HasHeadroomFor_Boundaries`、`DrainingChannelWithResidentsStaysDraining`、
  `DrainedChannelIsCleanedUp`、`RedisWinsOverConfig`、`CooldownSuppressesDecisions`、`DisabledIsInert`）与镜像的 4 个（假 `MirrorSources`，「查询失败阻止缩容」必须保留）；
  Java 特有：无同节点兄弟不缩、同节点余量、per-node 下不缩到某节点的最后一个、启动参数校验、扩容与补建同一次写入。
- **`WorldRebalancePlannerTest`**：移植 `TestPlanRebalance_*`（`logic_test.go:2400-2537`）中适用的（urgent 改为「死节点重铺」断言；`LiveRoleFlipWithPlayersFailsClosed`、`NonWorldHostingNotConsidered`
  在 5.1 无角色、记为不适用）；外加预算 0 关闭、有人 / 有预占永不迁、`per-node` 不做择机迁移、迁移 = 新记录同 slot + 旧记录 DRAINING(REBALANCE)。

### 9.3 scene-manager 单测（假 Redis 门面 / 假时钟）

- **`SceneAssignerTest`**（在现有用例上补，`xm-scene-manager/src/test/.../SceneAssignerTest.java`）：排空条目被排除；原实例在排空中 → 按地图；预占改变选择；并列规则；同一玩家重复分配不重复计数；
  `reservation-ttl=0` 退回现行为；Lua 异常原样上抛。
- **`WorldLeaderLockTest`**：2/3 TTL 判有效、续期返回 0 立即降级、续期出错到期失效、停服放锁、`leader-eligible=false` 不竞选。
- **`WorldChannelCoordinatorTest`**：写入返回 −1 放弃领导、−2 下拍重算；降级清零 gauge；当选立即跑一拍。
- **`WorldChannelsHealthIndicatorTest`**。

### 9.4 scene 单测（`SceneWorld`，现有 `SceneWorldTest` 风格）

- **`ChannelPlanApplyTest`**：带号建场景幂等；拒 0 号、非世界图、同号异 conf；旧版本忽略；排空中改回 ACTIVE；计划外本地场景按排空处理；读失败不应用。
- **`ChannelDrainTest`**：改派下行顺序 51 → 79 → 21 → 47、坐标保留、位置记录与组队跟随被调用；同图优先、无同图回落默认大世界（落出生点）、都无 → blocked；有在途进场不销毁；人数 0 才销毁；指标。
- **`EnterRedirectTest`**：加载完成时目标在排空 → 进兄弟、79 是兄弟的号。
- **63**：同图并列留原地、能跳到更空的频道（发 79）；当前在排空时排除自己；指定排空中的频道 → 3023；指定别的节点上的号 → 3023。
- **`TeamFollowServiceTest`**：队长场景排空中不跟（`leader_scene_draining`）；队长被改派后扇出。
- **`ChannelPlanFollowerTest`**：版本不变不读整表；租约无效跳过；完成后请求立即发布目录（`SceneDirectoryPublisher.requestPublishNow` 仍受 `stopped` 约束，`SceneDirectoryPublisher.java:52-71`）。

### 9.5 真 Redis（`-Dxm.it.redis=redis://127.0.0.1:6379`）

- **`WorldPlanRedisIT`**：令牌被夺后旧令牌写返回 −1、数据不变；版本号 CAS 冲突返回 −2；ver 严格递增；两个写者同时 HSETNX 只有一个生效；只读快照与并发写的一致性；pb 字节往返无损。
- **`ChannelReservationIT`**：200 个并发分配摊到 4 个频道，差 ±1 以内；同 player_id 重复分配不重复计数；按 TIME 到期；`SCRIPT FLUSH` 后 NOSCRIPT 能重载（`architecture.md:138`）。
- **`WorldLeaderFailoverIT`**：两个协调者竞选，停掉一方续期后另一方在 TTL 内接管；旧领导者写入被拒；两边各跑一拍频道数不翻倍。
- **`ScenePlanPullIT`**：节点启动只建属于自己的 ACTIVE 记录；版本变化 1 s 内应用；目录随即带上 `applied_plan_version`。

### 9.6 本机多节点切片

扩展 `tools/local/start-slice.sh`（服务清单 `:69-76`）：`XM_SCENE_NODES=3` 时三个 scene 各用不同的 `xm.scene.link-port`（21000 / 21001 / 21002）、`SERVER_PORT`（18104 / 18114 / 18124）
（4.5 的 `asset-rpc-port` 同样错开）；可选第二个 scene-manager 副本（Dubbo 20892、`SERVER_PORT` 18112，login 仍直连第一个，第二个只练选主）；启动后等 `worldChannels` 就绪。

| 编号 | 用例 | 期望 |
|---|---|---|
| S1 | 铺设：`channel-count-by-config 1: 16` | 图 1 共 16 个 ACTIVE；per-node 下每个节点都有全部 16 张图；各节点图 1 频道数相差 ≤1 |
| S2 | kill -9 一个 scene | 宽限期内计划不变；满 20 s 后删记录、新号重铺、per-node 覆盖恢复；重连玩家按地图落点 |
| S3 | 节点快速重启拿到同一节点号 | 同号频道重建，无重铺 |
| S4 | kill 领导者副本 | 30 s 内跟随者接管；频道数不翻倍、ver 单调 |
| S5 | 扩容（阈值调低：扩 3 / 缩 1 / 冷却 10 s，robot 灌人） | desired +1、新频道 1 s 内建出 |
| S6 | 缩容 | 同节点改派：被改派者收到一条非自己发起的 79（同 conf、新号、坐标不变），旁人收到 51 / 21 |
| S7 | 换一份删掉图 16 的配表重启 scene-manager | 图 16 的频道排空（玩家落同节点默认大世界出生点）后删除，desired 字段消失 |
| S8 | Redis 断开 > 20 s | 领导者失效、节点照常服务、恢复后自愈 |

### 9.7 robot

- **新增 Java robot `ChannelScenario`**：N 个新号进图 1 → 不同 scene_id 个数 = min(N, 频道数)（验证预占分散）；63 指定同节点另一频道的 scene_id → 收到该号的 79；63 只带当前地图 →
  无 79 或跳到更空的频道；借 S5 / S6 的阈值验证缩容收到非自己发起的 79。
- **改造**：`MovementScenario.java:170-177`、`SkillScenario.java:85-87` 遇 A、B 不同场景时让 B 发 63 `{scene_id = A 的场景}`（单节点切片可行），不再直接失败。
- **兼容**：用 mmorpg Go robot 连 Java 切片——login_test `SceneSwitch`（`robot/login_test_scenarios.go:1170-1201`，5.1 之前对 Java 会超时，§2.7）与 stress AI 的 `switch_scene`
  （`robot/logic/ai/robot_ai.go:195-218`）验证跳频道路径与契约兼容。
