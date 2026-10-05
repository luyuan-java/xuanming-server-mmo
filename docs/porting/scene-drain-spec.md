# 场景排空 / 节点疏散、死节点判定与接管（批次 5.5）移植统一规格：从 mmorpg 基线到 Java 版

> **盘点 id**：scene-drain-relocate（`docs/porting/inventory/scene-core.md:256-266`）、sm-dead-node-recovery（`docs/porting/inventory/scene-manager-match.md:154-164`）、
> scene-node-loss-handling（`docs/porting/inventory/gate.md:337-347`）。路线图 `docs/porting/roadmap.md:75`。
>
> **基线**：mmorpg `26ceb70ca`（`D:\work\mmorpg`，sparse clone）。本稿用到的 `cpp/nodes`、`cpp/libs`、`cpp/tests`、`go/scene_manager`、`go/login`、`docs/design` 都在；
> **缺 `bin/`**，所以部署值 `NodeTTLSeconds`、`HealthCheckInterval` 只能引设计稿 `docs/design/scene-owner-reentry-barrier.md:26-27`（下称 barrier.md；
> 两个键名见 `cpp/libs/engine/config/config.cpp:38-40`、`:52-54`）。
>
> **Java**：HEAD `aa8b5b5`（5.1、6.1 已提交）加当前工作区（5.2 跨节点换图、6.2 battle 节点在途，未提交）。**Java 行号一律按当前工作区**，同时写出类名 / 方法名；
> 5.2 提交后行号会漂移，以名字为准。
>
> **路径怎么读**
> - 以 `cpp/`、`go/`、`docs/design/`、`robot/` 开头的在 `D:\work\mmorpg` 下；以 `xm-*`、`docs/porting/`、`docs/design/architecture.md`、`PARITY.md`、`tools/` 开头的在本仓库下
>   （两边都有 `docs/design/`：barrier.md 在 mmorpg，`architecture.md` / `tech-stack.md` 在本仓库）。
> - 不带目录的 C++：`player_lifecycle.{h,cpp}`、`player_exit_intent.h` 在 `cpp/libs/services/scene/player/system/`；`player_ownership_comp.h` 在 `.../scene/player/comp/`；
>   `node.{h,cpp}` 在 `cpp/libs/engine/core/node/system/node/`；`etcd_service.{h,cpp}` 在 `.../node/system/etcd/`；`main.cpp` = `cpp/nodes/scene/main.cpp`；
>   `scene_handler.cpp` 在 `cpp/nodes/scene/handler/rpc/`；`scene_node_service.cpp` 在 `cpp/nodes/scene/handler/grpc/`；`client_message_processor.cpp` 在 `cpp/nodes/gate/handler/rpc/`；
>   `scene_route_helper.h`、`scene_entry_dispatch.{h,cpp}` 在 `cpp/nodes/gate/handler/event/`；`pending_scene_entry_comp.h` 在 `cpp/libs/services/gate/session/comp/`。
> - 不带目录的 Go：`enterscenelogic.go`、`world_autoscale.go`、`world_init.go`、`world_rebalance.go`、`orphan_cleanup.go`、`instance_lifecycle.go`、`destroyscenelogic.go`、
>   `reentry_barrier.go`、`load_reporter.go` 在 `go/scene_manager/internal/logic/`；`constants/reentry_barrier.go`、`constants/errors.go` 在 `go/scene_manager/internal/`；
>   `entergamelogic.go` 在 `go/login/internal/logic/clientplayerlogin/`。
> - 消息号取自 `xm-proto/src/main/resources/contract/message_id.txt`（号 N 在第 N+1 行）：21 NotifyActorCreate、23 SendTipToClient、34 KickPlayer、47 NotifyActorListCreate、
>   51 NotifyActorDestroy、63 EnterScene、66 SyncBaseAttribute、79 NotifyEnterScene。tip 取自 `xm-table/src/main/proto/tip/`：1003 / 1005 / 1006（`common_error_tip.proto:18`、`:22`、`:24`）、
>   2005 / 2017（`login_error_tip.proto:22`、`:46`）、3000 / 3014 / 3023（`scene_error_tip.proto:12`、`:40`、`:58`）。
> - 带「推导」字样的结论没有测试或运行证据，是把几处代码路径串起来得出的。
>
> **本稿的来历**：由三份分区稿合并——① 基线的排空、疏散与改派（scene 侧 + SM 侧、触发、批量、客户端帧）；② 死节点判定、推迟摘除、再入屏障、死节点接管与
> gate 链路丢失；③ Java 映射（跨节点疏散、放开同节点缩容、节点号复用围栏、死节点判定、运维面）。分区稿之间的分歧逐条回到代码核对，裁决见 §0.6。
> 所有编号（B / G / H / E / N / Q / R / V / K / C）以本稿为准，与分区稿不对应。本稿只读代码，除本文件外没有改任何文件。

---

## 0 概览与范围

### 0.1 结论速览

**基线**

- **排空与疏散是同一条改派链路的两个入口**（`player_lifecycle.cpp:2149-2363`）：单场景排空 `BeginSceneDrain`（SM 对有人的场景发 `DestroyScene` 时）与整节点疏散
  `BeginEmergencyRelocateAll`（节点号被别的 uuid 占走时）。每个住户：抄票据 → 退出存盘 → 存盘落地后条件写 handoff 标记 → 向 SM 发**不带等待者**的
  `EnterScene(scene_id=0, conf=0)` → 摘会话、销毁实体。落点由 SM 定，**一律是默认大世界（World 第一行）人数最少的频道**（`enterscenelogic.go:1267-1298`）。
- **触发面很窄**：SM 对有人场景发 `DestroyScene`（缩容、孤儿图、再平衡、实例回收 / 级联），或节点号被抢。SIGTERM / GM 签名停机**只存盘不改派**（`main.cpp:124-164`）；
  **没有运维主动疏散某个 scene 节点的入口**；**没有批量与并发上限**，唯一上界是冲突疏散的 15 s 看门狗（`node.cpp:875-909`，注释自认「待压测」）。
  改派失败**没有任何收尾**：会话挂在已销毁的实体上，scene 请求被静默丢弃，直到玩家自己重登（B1）。
- **死节点判定**：etcd 注册键消失（租约 180 s，barrier.md:26）是唯一正面证据；SM 领导者先写 `death_at` 再摘负载集，写不进就推迟摘除（节点留在负载集按存活处理）；
  之后一切改写归属的路径都过再入屏障 20 s（= C++ drain 15 s + 钟差 5 s，`constants/reentry_barrier.go:36-69`）。屏障过后：死节点上的副本强制销毁，
  世界频道**沿用原 scene_id** 迁到活节点，位置指向死节点的玩家按「无持有者」接管、免 handoff 标记（`enterscenelogic.go:840-898`）。
- **屏障的前提已过时**：自 2026-09-08 起 C++ 失租只拿新租约重注册、不再疏散（`node.h:35-43`；`etcd_service.cpp:598-607`），只有「号被别的 uuid 占了」才疏散。
  和 etcd 断开但仍能写 Redis 的老节点可以无限期写下去，真正挡回档的是 owner_epoch CAS（B8）。
- **gate**：节点被摘除时只把会话的 scene 指向置无效、**连接保持**，之后每条 scene 请求回 23 `{1003}`；摘除之前（崩溃后最长约 180 s）转发被静默丢弃。
  没有服务端路径为崩溃节点上的会话主动改派（`world_rebalance.go:207-306` 只迁频道、不路由玩家），客户端要自己重登。

**Java 现状**

- 5.1（已提交）：同节点排空改派——同图优先 → 本节点默认大世界 → 原地 BLOCKED，不存盘、不交接（`SceneWorld.drainStep` / `relocateResidents` / `relocationTarget`，
  `SceneWorld.java:423-494`）；缩容牺牲者必须有同节点兄弟（5.1 D9）、排空超时回滚（D10）；死节点 = 目录缺席满 20 s，只重铺容量、不碰玩家（D12）。
- 5.2（在途）：63 的跨节点换图——选目标 → 冻结 → 一笔带围栏的 MySQL 交出 → `PlayerTransfer` 改绑 → 目标进场（`SceneWorld.beginRemoteSwitch` 起，`:881-1168`），
  只服务玩家请求，失败一律推 tip。
- 归属在 MySQL 单行 + `owner_epoch` 围栏 + 30 s 租约；死节点上的玩家等租约过期后由 login 强制夺权，期间回 2005（`PARITY.md:37`）。
- 停服 = 先断开全部 gate 链路、再在逻辑线程上写回剩余玩家（`SceneNode.release`，`SceneNode.java:484-593`，`server.closeLinks()` 在 `:523`、写回在 `:535-539`），
  gate 直接关会话、不推 tip（`ClientDispatcher.onSceneLinkDown`，`ClientDispatcher.java:882-893`）。
- 节点号租约丢失**不分原因**：一律停接客、在场玩家服务到离开、没有恢复路径（`SceneNode.onLeaseLost`，`SceneNode.java:654-673`）——Redis 中断超过 15 s
  会让全区 scene 永久停止接客（G1）；号被新进程复用时，gate 按节点号复用旧链路，把新进场送进老进程回 3023（G2、G4）。gate↔scene 链路没有应用层活性检测（G3）。

**本稿的 Java 设计（§5）**

1. **服务器发起的换图**：5.2 状态机加 `SwitchReason.EVACUATE`；交出前的失败不推 tip、按玩家退避；选目标一律排除源节点（§5.2）。
2. **改派去向**：本节点同图 → 别的节点同图 → 本节点默认大世界 → 别的节点默认大世界 → 原地退避；跨节点一律走 5.2 交出；hash 覆盖下放开 5.1 D9 的「同节点兄弟」约束、
   余量按「改派实际会落到的那组频道」算（per-node 覆盖下规则不变，§5.3、§5.6.6）。存储层出错时暂停发起新的疏散冻结，免得库抖动成批踢人（§5.3 存储闸）。
3. **节点级疏散 `NodeEvacuation`**，三个触发：运维（xm-data 标记）、停服前（缺省开）、节点号被别的实例占用（疏散后退出）；有界 15 s；目录自报 `evacuating`，
   scene-manager 把它名下的频道转排空、同一次写入在别处补建（P2b）（§5.4、§5.6.4）。
4. **节点号租约区分丢失原因**：Redis 失联 → 挂起、照常服务、恢复后续上或原号复占；号归别人 → 疏散后退出；目录发布受租约有效性围栏（§5.5）。
5. **gate**：链路心跳与读空闲（15 s）；握手核对对端实例与租约代次、5 s 换代扫描、按链路代次寻址、旧链路宽限 30 s 后断开；链路断开先推 23 `{1003}` 再关（§5.7）。
6. **scene**：gate 链路读空闲即关链路、写回其上玩家；自己背压暂停读期间不计读空闲（§5.8）。
7. **死节点**：不移植 `death_at` / 再入屏障 / 推迟摘除 / 免标记接管（§5.13）；容量重铺加「节点号租约键已无人持有」的佐证，卡住的节点最多宽限 120 s（§5.6.5）。
8. **运维面**：xm-data `POST / GET / DELETE /admin/scenes/evacuate…`，与 gate 排空同形（§5.9）。
9. **前置修复**：`IsolatedDubboModule` 关掉 Dubbo 自带的 JVM 停机钩子，否则 SIGTERM 时选目标客户端在疏散开始前 / 进行中就被 Dubbo 销毁（H12，§5.10）；
   停服的真正上界是外部强杀期限，不是 Spring 停机阶段时限（G16、C2）。

### 0.2 盘点 id 与落点

| 盘点 id | 基线落点 | 盘点原文（Java 列） | 5.5 交付 |
|---|---|---|---|
| scene-drain-relocate | `player_lifecycle.cpp` BeginSceneDrain / BeginEmergencyRelocateAll / EnqueueRelocateTicket / DispatchEmergencyRelocate / SendEmergencyRelocateEnterScene / IsEmergencyRelocateDrained；`main.cpp` SetOnConflictShutdown / SetConflictDrainComplete | missing（`scene-core.md:263`；「无运行期销毁场景，也无改派」已被 5.1 过时，§8.3） | 跨节点改派（§5.2、§5.3）、节点级疏散（§5.4）、停服前与冲突疏散（§5.10）、运维面（§5.9） |
| sm-dead-node-recovery | `reentry_barrier.go`、`constants/reentry_barrier.go`、`load_reporter.go`、`enterscenelogic.go` playerLocationOwnerDead / Gone、`world_init.go` 懒改派 | partial（`scene-manager-match.md:161`） | 租约丢失分因（§5.5）；分层判死与租约键佐证（§5.6.5）；P2b（§5.6.4）；不移植清单与理由（§5.13） |
| scene-node-loss-handling | `client_message_processor.cpp` OnNodeRemoveEventHandler / ResolveSessionTargetNode；`scene_route_helper.h` CompareSceneNode | partial，「有意不同，未登记 PARITY」（`gate.md:344`） | 心跳、实例 / 代次围栏、按代次寻址、断链先推 23 `{1003}`（§5.7）；scene 侧读空闲（§5.8）；补登 PARITY |

### 0.3 范围

**5.5 做**：上表交付列；`xm-api` / `xm-discovery` 的内部契约变更（§6.3）；对 5.1 已提交代码与 5.2 在途代码的改动（§5.2、§5.3、§5.6）；指标、配置、启动校验；
`architecture.md` 与 PARITY 登记；单测、真 Redis 集成测试、本机多节点切片故障注入、robot（§11）。

**5.5 不做**：
- gate 自身的节点号租约语义（`GateNode.onLeaseLost` 关全部会话，`GateNode.java:272-283`）与 login 等其它服务的租约语义（Q3）。
- 跨 zone 的疏散（5.4）；按队伍聚合疏散目标、批量选目标接口（N1、N2）；提前释放死节点玩家的归属（N5）；gate 进场结果超时（N6）。
- K8s `terminationGracePeriodSeconds`、告警规则落地、liveness / readiness 接入（7.6，本稿只给数值与规则，§6.4、§7.3）。
- 战斗冻结本身（6.3）：本批只留「跳过战斗冻结中的玩家」的钩子。
- 基线自身的修复：登记进 PARITY「mmorpg 待做（可选）」（§8.1）。

### 0.4 与其它批次的边界与钩子

| 批次 | 已有 / 在途 | 5.5 的接法 |
|---|---|---|
| 5.1（已提交） | 频道计划 P1–P7、`NodeAvailability` 钩子（恒 `ALL`，`WorldChannelControlPlane.java:73`）、同节点改派、D9 / D10 / D12、`DrainReason` 0–3（`world_channel.proto:31-39`） | `NodeAvailability` 实现（读目录 `evacuating`）；新增 P2b 与 P2 租约键佐证；`DRAIN_EVACUATE = 4`；放开 D9（可回滚开关）、保留 D10；`relocationTarget` 扩成三态；**不抽 `ChannelEvacuator` 接口**（只有一个实现，§8.3 勘误） |
| 5.2（在途） | `PlayerSwitch` / `SwitchPhase`、`beginRemoteSwitch`、`selectSwitchTarget`、`PlayerTransfer`、冻结闸、墓碑 | `PlayerSwitch.reason`；`beginRemoteSwitch(player, want, reason)` 推广（5.2 / 5.3 规格里的 `SceneTransfers.begin(…, Reason.EVACUATE, playerRequested=false)` 就是它，不另建类）；`SelectSwitchTargetRequest` 追加 3 个字段；结局分派按 reason 分流；`node_link.proto` **不**为疏散加 `PlayerTransfer` 字段（Q12） |
| 5.3（规格在途） | 实例节点自有；`DrainCause{NONE, PLAN, IDLE, CASCADE, ADMIN}`；`relocationTarget(kind)`；`createInstance` 留 `NodeAvailability` 钩子（`dungeon-mirror-spec.md:120`、`:599`、`:675`） | `DrainCause` 追加 `NODE`；实例居民按 kind 定目标图再套 §5.3；疏散中的节点本地拒绝建镜像、scene-manager `createInstance` 对 `evacuating` 节点回 3000；作废在途的镜像取号 |
| 5.4（规格在途，`zone-travel-spec.md`） | 跨 zone 传送 / 重定向；`SwitchReason {PLAYER, TRAVEL}`、`SwitchPhase.PRECHECK`、`travelFramesPending`（`zone-travel-spec.md:98`、`:123`、`:613-614`） | 疏散只在本 zone 内选目标；`SwitchReason` 追加 `EVACUATE`（不另起判别字段）；疏散跳过任何阶段的 TRAVEL（含 PRECHECK）；疏散占着 `switching` 槽时 226 回 13000 / 3014（5.4 §5.5）；收敛谓词并入 `travelFramesPending`（§5.4.4） |
| 6.3（规格在途，`scene-battle-spec.md`） | 战斗冻结；`beginRemoteSwitch` / 5.5 入口拒绝 `inBattle()`（`scene-battle-spec.md:881`、`:1090`） | 改派跳过战斗冻结中的玩家（入口拒绝是纵深防御，正常走不到）；冲突 / 停服疏散到期时照常写回，结算由 6.3 落到下一个持有者（Q16，与 6.3 的 Q17 / D26 一致） |
| 7.6 | K8s 编排、告警 | 宽限期 ≥ 60 s（C2）；§7.3 告警；readiness 在挂起 / 疏散时 DOWN |
| 6.2（在途） | battle 节点用 `NodeIdLease` 缺省模式、租约丢失「尽力删除目录条目」（`battle-node-spec.md:1163-1169`） | 5.5 改 `NodeIdLease` 时缺省模式行为不变（battle 回归）；目录删条目的围栏（§5.5.4）建议 6.2 同样采用（§8.3） |

### 0.5 客户端可见效果速览

| 情形 | 基线 | Java 现状 | Java 5.5 |
|---|---|---|---|
| 频道排空（缩容 / 孤儿 / 迁移），本节点有同图频道 | 退出存盘 → 默认大世界（79 / 21 / 47）；新实体沿用库里的旧坐标、只按导航网格校验，不合法才落出生点（推导，5.2 规格 §3.4） | 同节点同图，坐标保留 | 不变 |
| 频道排空，本节点没有同图频道（hash 覆盖） | 同上 | 原地不动，缩容超时回滚 | 跨节点同图：旁人 51 → 本人 79（新 scene_id）/ 21 / 47，坐标保留，无 tip |
| 运维疏散某个 scene 节点 | 无此功能 | 无 | 同上，不断线 |
| 停服 / GM 签名停机 | 只存盘；节点被摘后 scene 请求回 23 `{1003}` | 写回 + 断链，直接断线 | 先疏散（同上）；预算内没迁走的照旧写回，先推 23 `{1003}` 再断线 |
| 节点号被别的进程占用、旧进程活着 | 疏散到默认大世界，≤15 s 后退出 | 服务到离开；新进场经旧链路回 3023 | 跨节点疏散（同上），≤15 s 后退出；gate 换代后新进场直接进新进程 |
| scene 进程崩溃 | ≤180 s 静默丢弃，之后哑连接回 1003；屏障过后重登可进（约 200 s） | 立即断线；重登回 2005，租约过期后可进（≤ 约 33 s） | 先推 23 `{1003}` 再断线；其余同现状 |
| scene 宿主静默死亡 / 进程挂起 | 同崩溃（等 etcd TTL） | 空闲会话不断开（等 TCP 超时或 32 MB 水位） | ≤15 s 推 23 `{1003}` 后断线 |
| 改派失败 | 什么都不发，会话坏掉（B1） | — | 交出前失败：原地留下、不推 tip；失去归属 2017 / 结局不明 3023 / 交出后失败 3023，均推后断开 |

### 0.6 分区稿分歧与编者裁决

| # | 分歧 | 分区稿 | 回到代码核对 | 裁决 |
|---|---|---|---|---|
| A1 | 节点级排空的选择面过滤放在哪 | ① 在 `SceneAssigner.isUsable` 加 `!draining`，称「唯一过滤点」 | `DirectoryView.of` 也用 `isUsable`（`DirectoryView.java:67`）：规划器会把疏散中的节点当成缺席，P2 满 20 s 删光它的记录，P3 误判场景已销毁 | **不改 `isUsable`**。疏散节点在同一次发布里把全部场景标 `draining`，分配 / 选频道 / 显式选目标已按场景排除（5.1 D11、D13）；节点级 `evacuating` 只给规划器与 `createInstance` 用（§5.6.1） |
| A2 | 不可用节点名下的频道记录 | ① 不计入期望与覆盖（改 5.1 约定），记录原样；③ P2b 转 `DRAINING(EVACUATE)` + 同批补建 | `NodeAvailability` 注释写「已有记录照常计数」（`NodeAvailability.java:3-6`）；① 会留下「计划 ACTIVE、节点本地排空」的长期不一致，撤销后多出频道 | 取 P2b（§5.6.4） |
| A3 | 运维标记谁读 | ① scene 每秒读、SM 只看目录；③ SM 领导者与 scene 都读 | 两个读者 = 两份判定；领导者一拍 5 s，比 scene 拉取 1 s 慢 | scene 是唯一读者，SM 只看目录 `evacuating`（§5.9） |
| A4 | 节点号租约丢失原因 | ① TAKEN / 续期失败（后者维持现状）；② TAKEN / EXPIRED（重占原号）；③ TAKEN / 键空闲 / 不可达 | 续期 Lua 在「键不在」与「键归别人」时都回 0（`NodeIdLease.java:41-42`、`:155-159`）；现状失联超过 TTL 即永久丢失 | 续期 Lua 三值；出错 ≥ TTL 进**挂起**、不判丢；恢复后按续期结果续上 / 原号复占 / TAKEN（§5.5） |
| A5 | 停服前疏散的缺省 | ① 开、20 s；② 开放问题；③ 关，切片验证后再开 | `stop-slice.sh` 等 20 s 后 kill -9（`tools/local/stop-slice.sh:11-17`）；`timeout-per-shutdown-phase` 30 s（`xm-scene/.../application.yaml:22`）——但它对同步的 `SceneNode.stop()` 不起约束（评审核对，C2） | 实现并**缺省开**，预算与冲突疏散共用 15 s；目录里没有别的接客节点就跳过、中途变成没有就提前结束；配套改外部强杀期限（stop-slice 等待、K8s 宽限，Q1、C2） |
| A6 | 节点疏散中玩家自己发 63 | ① 一律 Remote 且排除本节点；② 一律 3014 | 全部本地场景都在排空时，现有 `resolveSwitchTarget` 自然给出 Remote（只带地图）或 3023（显式本地号，D11）（`SceneWorld.java:560-580`） | 取 ①：只多带 `exclude_from_node`；自己的换图在途的玩家，疏散跳过他（§5.4.3） |
| A7 | 去向顺序与 SM 回落 | ① 把「回落默认大世界」合并进第 2 步的 SM 调用；③ Refused 再落下一级 | ① 的写法会让「远端默认大世界」排到「本节点默认大世界」之前，与它自己写的顺序矛盾 | `fallback` 只在本节点没有可用默认大世界时才带；SM 没给出远端去向时先试本节点默认大世界，再退避（§5.3） |
| A8 | 选目标是否排除源节点 | ① 只在节点级疏散；②③ 疏散一律 | 计划驱动时本节点同图已先查过且没有可用的，带上无害、省一次无用往返 | `EVACUATE` 一律排除；`PLAYER` 只在节点疏散中排除（§5.2） |
| A9 | 并发上限 | ① 一个键 32；③ 计划驱动 32 + 自疏散 64 | 存储池 4 线程、队列 1 万（`xm-scene/.../application.yaml:85-86`），交出与在线存盘共用 | 一个键 `evacuation.max-in-flight` = 32，压测后定（Q11） |
| A10 | 失败退避 | 2 s→30 s / 下一秒 / 1 s→30 s | 排空推进每秒一拍（`SceneNode.java:337-343`） | 1 s 起翻倍、封顶 30 s |
| A11 | gate 侧的号复用围栏 | ② 目录实例不符持续 ≥20 s 才断旧链路（期间新进场仍进老进程 → 3023）；③ 握手比对 + 5 s 换代扫描 + 按代次寻址 + 30 s 宽限 | `forwardToScene` / `leaveScene` 按节点号发（`ClientDispatcher.java:601`、`:916`）；`SceneLinkManager` 每节点一条（`SceneLinkManager.java:15-22`） | 取 ③（§5.7.2–§5.7.4）；心跳取 ②（§5.7.1） |
| A12 | 链路心跳 | 只有 ② 提出 | gate 建链只设连接超时与写水位（`NettyLinkConnector.java:64-70`）；scene 只开 `SO_KEEPALIVE`（`NodeLinkServer.java:63`） | 采纳；滚动升级用握手字段协商（§5.7.1） |
| A13 | 死节点判定的佐证 | 只有 ③ 提出租约键佐证 | 目录发布要在逻辑线程取快照（`SceneNode.java:405-410`，`callOnLogic` 5 s 超时），节点号续期在调度线程，两路信号独立 | 采纳（§5.6.5） |
| A14 | 停服疏散预算 | ① 20 s；③ 15 s | — | 共用 15 s（`evacuation.budget`） |
| A15 | 标记键与接口名 | ① `xm:scene-draining:{z:<zone>}:<node>`、`/admin/scenes/drain`；③ `xm:scene-evacuating:{zone}:{node}`、`/admin/scenes/evacuate` | gate 排空键是 `xm:gate-draining:<zone>:<node>`（`RedisKeys.java:59-64`），标记类只支持单节点 Redis（`GateDrainMarks` 类注释） | `xm:scene-evacuating:<zone>:<node>`、`/admin/scenes/evacuate`（与 5.1 的「频道排空」区分） |
| A16 | `ChannelEvacuator` / `SceneTransfers` | 5.1 / 5.2 / 5.3 规格的名字 | 全仓没有这两个符号；入口是 `SceneWorld.relocationTarget` 与 `beginRemoteSwitch` | 不新建：推广现有方法；规格名视为同一入口的别名（§8.3） |
| A17 | 「5.1 规格 `enterscenelogic.go:726-730` 应为 `:728-730`」 | ① 提出的勘误 | `:726` 起注释、`:729` 扣人数、`:730` 收尾 | 5.1 原文无误，撤销这条勘误 |
| A18 | 基线行号 | 三稿各有偏差（如 `beginDrainWorldChannel` 记成 `:276-366`） | 逐条复核：`world_autoscale.go:324-366`；`reconcileDeadNodeScenes` 自 `load_reporter.go:413`；`ReserveBestWorldChannelForEnter` 自 `world_init.go:441` | 以本稿行号为准 |

---

## 1 基线排空与疏散

### 1.1 scene 侧：一条链路，两个入口

| 步骤 | 位置 | 要点 |
|---|---|---|
| 入口 A：单场景排空 | `BeginSceneDrain`，`player_lifecycle.cpp:2207-2231` | 先给 `ScenePlayers` 拍快照（退出流程会边遍历边摘）；每个住户 `EnqueueRelocateTicket(…, kSceneDrain)`；返回**有 gate 会话**的人数。不设 `tlsEmergencyRelocating`，节点不进疏散态 |
| 入口 B：整节点疏散 | `BeginEmergencyRelocateAll`，`:2186-2205` | 幂等闸 `tlsEmergencyRelocating`（`:2188-2192`）；遍历 `view<Player>` 全体，原因 `kIdentityConflict` |
| 抄票据 | `EnqueueRelocateTicket`，`:2149-2184` | 有 `gate_session_id` 才抄 `{player, session, gateNodeId, gateInstanceId}`（`:2162-2172`）；没有会话的只存盘（`:2173-2179`）；然后 `HandleExitGameNode`。已在退出中的只合并原因（`:2181-2183`） |
| 退出与存盘 | `HandleExitGameNode`，`:1900-` | 挂退出意图 → 停移动 → `DetachFromScene`（旁人 51）→ 拍 LOGOUT 快照（`:1988`）→ `SavePlayerToRedis` |
| 落地后收尾 | `FinishExitAfterPersist`，`:2055-2136` | 交接在途时「退出优先」：撤回交接标记、解冻（`:2066-2118`）→ `DispatchEmergencyRelocate`（`:2127`）→ A1′ 释放标记（消费了票据就不写）→ `RemovePlayerSession`（`:2133`）→ `DestroyPlayer`（`:2135`）。顺序理由在 `:2121-2126` |
| 写标记 | `DispatchEmergencyRelocate`，`:2249-2329` | 取票即删（幂等）；epoch 为 0 或 Redis 不可用直接发改派；否则 Lua 条件写 `player:{id}:handoff = "{E}:{ms}"`（owner_epoch 仍为 E 才写），写成写不成都发改派；在途计数 `tlsRelocateHandoffMarksInFlight` |
| 发改派 | `SendEmergencyRelocateEnterScene`，`:2331-2363` | 找不到 SM 只打 ERROR，玩家「保留 gate 会话，自己走登录流程」（`:2333-2339`）；请求 `scene_id = 0, conf = 0`，本 zone；**刻意不带 request_id**（否则 60 s 去重吞掉第二次排空，`:2351-2355`）；**不登记等待者**，应答到达时是空操作（`:2356-2358`） |

### 1.2 收敛谓词与预算

- `IsEmergencyRelocateDrained`（`player_lifecycle.cpp:2233-2247`）：票据空、写标记在途 0、A1′ 在途 0、本地 `Player` 实体 0。只在疏散态下有意义，单场景排空恒真。
- 冲突收尾 `OnNodeIdConflictShutdown`（`node.cpp:837-873`）：停健康检查与注册重试 → 跑业务钩子 `BeginEmergencyRelocateAll`（`main.cpp:246-251`）→ `StartConflictDrainWatchdog`：
  每 0.1 s 查谓词（`main.cpp:255-256`），`kDrainBudget = 15 s`（`node.cpp:883`），完成或到期都走普通 `Shutdown()`（`:875-909`）。普通停机再跑一次 `exitAllPlayers`
  （`main.cpp:124-164`）并每 100 ms 复扫在线实体（`:168-237`），疏散期间才建出来的玩家在这一步只存盘；普通停机预算 `kShutdownDrainBudget = 15 s`（`node.cpp:52`）。
- 疏散态下 `IsNodeIdentityConfirmed()` 恒假，A1′ 断线释放标记一律不写（`player_lifecycle.cpp:725-732`）。

### 1.3 `DestroyScene`：先排空，再销毁

`scene_node_service.cpp:68-125`（muduo 旧入口 `scene_handler.cpp:926-976` 同义）：找不到场景按幂等 OK（`:84-88`）；`BeginSceneDrain(entity) > 0` 时保留实体并返回 OK（`:108-115`），
注释写「SM 下一拍再调，自然收敛」（`:91-107`）；否则触发 `OnSceneDestroyed` 后销毁（`:117-121`）。**应答一律 Empty**，调用方分不清「已销毁」与「排空中」（5.1 规格 B10）。

### 1.4 SM 侧：改派请求的处理（`EnterScene`，`enterscenelogic.go:120-`）

1. 带 `GateId` 的请求校验 Kafka、gate id、`gate_instance_id`（`:136-160`）；改派请求三项都带（`player_lifecycle.cpp:2345-2347`）。
2. 读位置与 `owner_epoch`（本请求唯一一次观察）；陈旧 zone / 死节点上的位置按「无持有者」处理（`:306`、`:321`，见 §2.5）。
3. `resolveSceneForEnter(0, 0)`（`:1267-1286`）：`defaultWorldConfID()` = World 第一行（`:1288-1298`）→ `ReserveBestWorldChannelForEnter`（`world_init.go:441-513`）：
   跳过身份歧义与不存活节点上的频道，全部不可用时懒改派，原子预占人数最少的频道。被缩容的频道开始排空前已从 `world_channels` 摘掉（`world_autoscale.go:327`），不会被选回。
4. 判定：`samePlacement`（`:492`）/ `samePhysicalNode`（同 zone 同节点号，**不过换手门、不铸 epoch**，`:494`）/ `crossNodeHandoff`（`:496`，`requireHandoffCommitted`
   凭 §1.1 的标记放行、落点铸 E+1，`:505`、`:786-817`）。
5. 跨节点时向旧节点派 `ReleasePlayer`（`:694-699`，实体已销毁，那边是空操作）→ 写位置（`:705`）→ 成功后扣旧场景 Redis 人数（`:726-730`，**排空收敛靠它**）→
   Kafka `RoutePlayerEvent` 推给 gate（`:732-750`）；gate 改绑后以 enterType=0 向新节点转进场。转不出去（节点未发现 / 未连上 / 未握手）时记欠账、
   有上限地补发（§3.1），超限推 23 `{3023}` → 34 `KickPlayer{reason=3023}` → 关写端（`cpp/nodes/gate/handler/event/scene_entry_dispatch.cpp:156-200`）。
6. 失败（17 再入屏障、18 换手门、无节点、Kafka）只回错误应答；发起方没登记等待者，**没有人收尾**（B1）。

### 1.5 SM 侧：谁驱动排空（`DestroyScene` 的全部调用点）

| 驱动方 | 位置 | 频率与上限 | 对有人场景 |
|---|---|---|---|
| 自动缩容（缺省关） | `beginDrainWorldChannel` `world_autoscale.go:324-366`（先 SREM 摘路由 `:327`、期望数 −1、标排空、立即推进一步）；`sweepDrainingWorldChannels` `:368-388`；`drainOrDestroyChannel` `:389-428` | 每 `CheckIntervalSeconds`（缺省 30 s）一轮；每 (zone, 图) 每轮至多一个牺牲者（`pickScaleInVictim` `:296-322`，全图余量 `hasHeadroomFor` `:258-274`），冷却 120 s（`:491-504`） | **每轮重发 `DestroyScene`**（`:416`），直到 Redis 人数为 0 才收尾；排空无超时（5.1 B4） |
| 孤儿图清理 | `orphan_cleanup.go:156-199` | 每次 fullSync，整组处理 | 节点存活就发 `DestroyScene`（`:168`，失败忽略），残余人数不处理 |
| 再平衡迁移 | `world_rebalance.go:283-287` | 每拍至多 10 个，只迁空频道（`:195-197`） | 只在迁移瞬间恰好有人进来时才改派 |
| 实例回收 / 级联 / 强制销毁（5.3） | `instance_lifecycle.go:299-305` | — | 同上，落默认大世界 |
| SM `DestroyScene` RPC | `destroyscenelogic.go:77` | 无生产调用方（5.1 B14） | — |

### 1.6 基线的触发面

| 触发 | 入口 | 是否改派 | 预算 |
|---|---|---|---|
| SM 对有人场景发 `DestroyScene`（§1.5） | `BeginSceneDrain` | 是，落默认大世界 | 无超时 |
| 节点号被别的 uuid 占用：重注册 CAS 失败（`etcd_service.cpp:234-251`，冲突调用 `:250`）或 Watch 看到自己的键被别的 uuid PUT（`:287-336`，判定 `:329-334`） | `OnNodeIdConflictShutdown` → `BeginEmergencyRelocateAll` | 是 | 15 s，然后 `Shutdown()` |
| 租约过期（keepalive TTL=0 `:598-607`；本地 deadline `node.cpp:1531-1545`） | `RequestReRegistration`，**只重注册** | 否 | — |
| SIGTERM / Agones / GM 签名停机 | `exitAllPlayers`（`main.cpp:124-164`，原因 `kNodeShutdown`） | **否，只存盘** | `kShutdownDrainBudget` 15 s |
| 运维主动疏散某个 scene 节点 | **无**（SM 只有 `/debug/rebalance-plan`） | — | — |
| 进程崩溃 / 断网 | 无法疏散；由死节点接管（§2.5） | — | — |

`NodeIdConflictReason` 注释写明自 2026-09-08 起只剩 `kReRegistrationFailed` 一个触发源（`node.h:35-43`）。`main.cpp:239` 的注释「身份冲突(etcd 租约过期 / …)」与
`constants/reentry_barrier.go:13-27` 的时间线都已过时（§8.3）。

### 1.7 批量与并发

- **无批量**：两个入口都在一个调用里对全部住户发起退出存盘（`player_lifecycle.cpp:2196-2204`、`:2224-2229`）；存盘、标记 EVAL、`EnterScene` gRPC、Kafka 路由同时涌向 Redis、SM、gate。
- 唯一上界是冲突看门狗 15 s；注释自估「几百玩家百毫秒级」并标「TODO 待压测」（`node.cpp:877-881`）。
- SM 侧每个改派请求独立处理、各自 Lua 预占；缩容频率见 §1.5。
- **不重试**：每张票据至多发一次（`player_lifecycle.cpp:2351-2355`）。

### 1.8 客户端可见

**两段退出闸**：

| 时段 | 处理 | 位置 |
|---|---|---|
| 退出开始 → 摘会话（存盘在途 + 写标记） | 带 `UnregisterPlayer` 的实体，除 ExitGame 外的请求一律回**应答内** `error_message{1006}` | `scene_handler.cpp:493-506` |
| 摘会话 → gate 改绑 | `SessionMap` 里已没有这个会话，请求**静默丢弃、不应答** | `scene_handler.cpp:406-411`；`RemovePlayerSession` `player_lifecycle.cpp:1756-1777` |

| 结局 | 旧场景旁人 | 被改派者 |
|---|---|---|
| 成功（跨节点，或同节点重载） | 退出一开始就收到 51 | ① 应答内 `{1006}` 段；② 静默丢弃段；③ 新节点 79（默认大世界的新 scene_id）+ 21（自身），下一拍 47；**无 tip、无 34** |
| 落点坐标 | — | 默认大世界；被缩的不是默认图时换图，新实体只按导航网格校验坐标（5.2 规格 §3.4） |
| SM 已推路由、gate 转不出进场（新节点未发现 / 未连上 / 未握手，§3.1 欠账超限） | 51 | 23 `{3023}` → 34 `{reason=3023}` → 断开（`scene_entry_dispatch.cpp:156-200`；`scene_entry_dispatch.h:25-27`） |
| 改派失败（17 / 18 / 无节点 / Kafka / 无 SM） | 51 | **什么都不发**：会话仍绑旧节点、实体已销毁，scene 请求静默丢弃，直到重登；旧节点随后下线时 gate 回 23 `{1003}`（§3.1） |
| 已断线的玩家 | 51 | 只存盘 |
| SIGTERM / GM 停机 | 51（旁人也在退出） | 不改派；节点被摘后 scene 请求回 23 `{1003}` |

### 1.9 基线隐患（排空 / 疏散部分）

| # | 隐患 | 出处 |
|---|---|---|
| B1 | **改派失败没有收尾**：不登记等待者、找不到 SM 只打日志；SM 拒绝时会话仍绑旧节点、实体已销毁、请求静默丢弃。旧频道人数只在落点成功后才扣（`enterscenelogic.go:726-730`），缩容 sweep 一直看到 `players > 0`，叠加 5.1 B4，直到被困玩家自己重登 | `player_lifecycle.cpp:2333-2339`、`:2356-2358`；`scene_handler.cpp:406-411` |
| B2 | 疏散期间没有准入闸：`tlsEmergencyRelocating` 只被身份确认与收敛谓词读，进场路径不看；疏散开始后才建出的玩家没有票据，15 s 后只存盘（推导） | `player_lifecycle.cpp:727`、`:2235` |
| B3 | 断线中、存盘在途的玩家也会被抄票据（`HandleExitGameNode` 不解绑会话）：SM 照常铸 E+1、写位置到新节点、推路由，但没人会去加载他；标记用旧 epoch E，此后跨节点重登可能被换手门 18 挡到标记 TTL 300 s（推导） | `player_exit_intent.h:14-17`；`player_lifecycle.cpp:2162-2172`；`player_ownership_comp.h:74` |
| B4 | 无批量，15 s 是唯一上界且未压测 | `node.cpp:877-881` |
| B5 | 号被抢后 SM 看到的节点 N 是新进程、`IsNodeAlive(N)` 为真，被疏散的玩家仍可能被分到 N 名下的频道，而新进程未必建过这些场景（身份歧义检测只在 SM 同时看到两份注册时生效）（推导） | `world_init.go:452-457` |
| B6 | `DestroyScene` 注释与实际不符：首次调用后 `ScenePlayers` 已被 `DetachFromScene` 清空，第二次调用直接销毁实体，改派可能仍在途。无害，但「实体保留到排空完成」不准 | `scene_node_service.cpp:91-121` |
| B7 | 同节点落点的多余重载：SM 按全区挑选可能挑回本节点（`samePhysicalNode`），白做一次存盘 + 销毁 + 重载，客户端照样看到 79 / 47 | `enterscenelogic.go:494` |

---

## 2 基线死节点判定与接管

### 2.1 判死证据

- **注册**：C++ 节点用 etcd 租约注册，TTL 读 `Etcd.NodeTTLSeconds`；部署值 180 s、健康检查 1 s（barrier.md:26-27；`bin/` 缺失，无法直接核对部署文件）。
- **SM watch（每个副本都跑）**：DELETE → 删内存镜像、`noteNodeGoneFromRegistry`（调用 `load_reporter.go:969`，定义 `:147`）、清 gRPC 连接；**只有领导者**接着 `removeNodeFromRedis` 与一次再平衡
  （`:957-994`）。watch 重建时 fullSync 补记差集（`:662-673`）；领导者清扫「在负载集里、不在 etcd 快照里」的陈旧成员，走同一条摘除路径（`:688-694`、`:736-800`）。
  PUT（重新注册）→ `clearNodeDeath`、取消推迟摘除（`:917-924`）。
- **三级证据**：
  - `isNodeGoneFromRegistry`：已完成首次全量同步且注册表里该身份 0 条，是唯一的**正面**死亡证据（`load_reporter.go:175-192`）；
  - `IsNodeAlive` 三态（`:1031-1071`）：身份歧义 → false；ZSCORE 命中 → 活；`redis.Nil` → 死；其它错误 → **按活处理**；
  - 本副本观察时刻 `nodeGoneObservedAt`：`death_at` 只有领导者写，跟随者不能凭「没有 `death_at`」放行（`:124-173`）。

### 2.2 摘除顺序与推迟摘除（GO-6）

- **顺序固定**：先 `SETEX node:zone:{z}:{n}:death_at <ms> 600`（`markNodeDeath`，`reentry_barrier.go:87-99`），再 ZREM 负载集、删类型镜像、清连接、把收尾入队
  （`load_reporter.go:303-342`、`:345-378`）。反过来，读者会看到「不在负载集 + 没有 `death_at`」= 「屏障已过」（`:305-322`）。
- **写 `death_at` 或 ZREM 失败**：`deferNodeDetach`——节点**留在负载集按存活处理**，抄下判死那一刻的场景快照，每 5 s tick 与每次 fullSync 重试（`reentry_barrier.go:384-417`、`:474-543`）。
- 不变量 G6-I1 / G6-I2（`reentry_barrier.go:317-336`）：不带 `death_at` 的摘除只能在本进程单调钟量满一个屏障之后做；ZREM 一直失败 10 min 就放弃（`:361`、`:517-523`）；
  非领导者丢弃队列（`:492-500`）；重新注册时取消推迟（`:444-470`）。收尾与推迟队列只在进程内存（`:207-215`）。

### 2.3 再入屏障

- **不变量**：老节点最晚写入的时刻 < 新节点最早接管的时刻（`constants/reentry_barrier.go:8-34`）。
- **常数**：`CppNodeDrainBudget` 15 s（C++ `kDrainBudget` 的**手工镜像**，`:36-51`）、`ReentryBarrierClockSkewMargin` 5 s（`:53-63`）、`SceneReentryBarrier` 20 s（`:65-69`）、
  `NodeDeathMarkTTL` 10 min（`:71-80`）；启动校验 `:86-98`；配置只能调高（`:100-117`）。
- **判定 `CanReclaimDeadNode`**（`reentry_barrier.go:129-175`）：推迟态 → 拒；读 `death_at` 失败 → 拒；没有标记 → 放；标记非法或在未来 → 拒；已过屏障 → 放。
- **统一入口** `reentryBarrierBlocks`（`:181-190`），判定点位兼作指标标签：`resolve_scene`、`rebalance`、`reassign`、`world_channel_lazy`、`orphan_cleanup`、`dead_node_cleanup`、
  `stale_location`、`dead_owner_takeover`；指标 `reentry_barrier_blocked_total{zone,site}`。
- **被挡下的进场**：显式进刚判死节点的场景回私有码 17 `ErrSceneReentryBarrier`；位置指向死节点、屏障未到回换手门 18；login 把非 0 码当失败，经 gate 推 23 `{3023}`
  （`entergamelogic.go:751-765`）。

### 2.4 屏障之后：死节点上的场景

- **副本**：`drainPendingDeadNodeReconciles`（5 s tick + fullSync 驱动，`reentry_barrier.go:258-289`）对屏障已过的节点跑 `reconcileDeadNodeScenes`（`load_reporter.go:413-472`）：
  只处理快照里仍映射到死节点、且在活跃副本集合里的场景，`destroyInstanceForce`；世界频道跳过；逐条复查领导权；收尾后才删节点计数（`:380-395`）。
- **世界频道**：不销毁，**沿用原 scene_id** 迁到活节点。两条路：再平衡 urgent 迁移，原因 `node_gone`，计划阶段先过屏障（`world_rebalance.go:22`、`:168-193`）；进场懒改派
  （`world_init.go:471-506`，屏障判定 `:486-489`）。老节点仍注册且频道里还有人时拒绝迁移（`world_rebalance.go:168-178`）。
- 这些路径**都不路由任何玩家**：`migrateWorldChannel`（`world_rebalance.go:207-306`）只 CreateScene、改映射、DestroyScene 旧节点、级联镜像。

### 2.5 屏障之后：死节点上的玩家

- **整个 zone 已无活节点**：负载集为空、节点不活、屏障已过 → `playerLocationOwnerGone`，位置记录当不存在（`enterscenelogic.go:306`、`:920-943`）。
- **单节点死亡**：首次全量同步完成、注册表里已没有、身份无歧义、本副本看到它消失满一个屏障、`IsNodeAlive` 为假、`death_at` 屏障已过——全部成立才 `playerLocationOwnerDead`
  （`:321`、`:877-898`）：当作没有位置记录、**不要求 handoff 标记**、按首次落点铸 epoch；落点成功后才归还旧场景人数、计 `enter_scene_owner_dead_takeover_total`
  （`:759-762`、`:830`）。安全论证在 `:843-856`：屏障靠时间、epoch 靠 CAS，两层同时在位才放行；代价是死节点自上次存盘以来的进度丢失。
- 屏障之前（以及 etcd TTL 内节点仍注册时）重登回 18 / 17 → 23 `{3023}`，客户端自己重试。

### 2.6 失去身份的触发面：租约过期不再疏散

- 只有两种触发会疏散：重注册 CAS 失败（`etcd_service.cpp:234-251`）与 Watch 劫持检测（`:329-334`）。
- keepalive 返回 TTL=0 或本地 deadline 超时，都只拿新租约重注册；注释原话「身份由分配键 CAS 决定，不由租约决定」（`etcd_service.cpp:598-607`；`node.cpp:1531-1545`）。
- 因此 barrier.md:18（「四个触发点」）、`constants/reentry_barrier.go:13-27` 与 `main.cpp:239` 描述的「租约到期 → `BeginEmergencyRelocateAll` → 15 s drain」不再成立：
  一个和 etcd 分区、但仍能写 Redis 的老节点**不会**在 15 s 内停笔，屏障过后的接管与同号迁移只靠 epoch CAS 兜底（B8）。

### 2.7 基线时序

**崩溃**（SIGKILL / OOM / 掉电）：

| 时刻 | 事件 |
|---|---|
| t0 | 进程死；gate TCP 断，但节点实体仍在，转发静默丢弃（§3.1） |
| t0 + ≤180 s | etcd 租约到期 → DELETE：gate 解绑会话（之后回 1003）；SM 写 `death_at`、摘负载集 |
| t0 + ≤200 s | 屏障过：世界频道同号迁移、副本强制销毁、重登可以接管 |
| 期间重登 | 18 / 17 → 23 `{3023}`，登录会话保留，客户端重试 |

**失去身份**（节点活着）：t0 冲突 → 每人存盘往返后写标记 → `EnterScene(0,0)` → 默认大世界 → gate 改绑 → 新节点 79 / 21；≤ t0 + 15 s 完成或到期 → `Shutdown()` 释放租约 →
DELETE，gate 解绑仍挂着的会话。

### 2.8 基线隐患（死节点部分）

| # | 隐患 | 出处 |
|---|---|---|
| B8 | 屏障前提过时：改为重注册后，与 etcd 分区、仍能写 Redis 的老节点不会在 15 s 内停笔；同号世界频道可能两边同时活着，只靠 epoch CAS 兜底 | §2.6；`world_rebalance.go:168-193` |
| B9 | `CppNodeDrainBudget` 是 C++ 常数的手工镜像，C++ 调大而 Go 没跟会静默打开双写窗口 | `constants/reentry_barrier.go:36-51`；`node.cpp:883` |
| B10 | `death_at` 用墙钟打点，跨 Pod 钟差方向不定（基线自认唯一不安全项） | `constants/reentry_barrier.go:53-63` |
| B13 | 收尾队列与推迟队列只在进程内，SM 重启即丢（新领导者 fullSync 重扫，方向安全） | `reentry_barrier.go:207-215` |

（B11、B12、B14 是 gate / scene 链路侧，见 §3.3。）

### 2.9 基线测试

- `node_detach_deferred_test.go:223-600`：`death_at` 写失败时节点留在负载集、推迟重试、满屏障摘除、重注册取消、陈旧清扫推迟、推迟态挡改派与孤儿清理。
- `logic_test.go`：`:336`、`:351` `IsNodeAlive` 三态；`:802-910` 陈旧位置与屏障；`:2808-2900`、`:3167` 死节点收尾（只销副本、保留世界频道）。
- `owner_epoch_test.go:2449-2630`：屏障后免标记接管、屏障内仍拒、本地观察表、注册表未同步或身份歧义时 fail-closed。
- `world_autoscale_test.go:108`、`:164`、`:192`、`:210`；`orphan_cleanup_autoscale_test.go:53`；`cpp/tests/cross_zone_test/cross_zone_test.cpp:1113-1170`（`ExitReleaseDecision`）。
- **`BeginSceneDrain` / `BeginEmergencyRelocateAll` 没有单测，改派条件写只有 runbook 演练（`cross_zone_test.cpp:120-122`），没有 robot**（`scene-core.md:265`、`scene-manager-match.md:162`）。

---

## 3 gate 侧链路丢失处理

### 3.1 基线

- **节点摘除**：节点实体只在 etcd 删除该 uuid 时销毁并发 `OnNodeRemoveEvent`（`node.cpp:1463-1495`）。gate 把所有指向它的会话的 scene 指向置 `kInvalidEntityId`，
  **不断连接**（`client_message_processor.cpp:182`、`:1104-1113`）；之后 scene 类请求在 `ResolveSessionTargetNode` 失败（`:187-219`），回 23 `{1003}`（`:765-780`）。
- **DELETE 之前**（崩溃后 ≤ TTL）：节点实体仍有效；TCP 不在已连接态时「RpcClient::CallRemoteMethod 只打一条 ERROR 就静默丢弃」（`pending_scene_entry_comp.h:37-38`，针对进场转发；
  普通请求走同一路径是推导）。
- **重新绑定只有一条路**：之后某次 `RoutePlayer` 落到这个会话时，无效指向按 `kChanged` 处理、以 enterType=0 转进场（`scene_route_helper.h:41-59`；
  `cpp/tests/routing_identity_test/routing_identity_test.cpp:544-557`）。SM 不会为死节点上的会话主动发 `RoutePlayer`（§2.4 末条）；唯一主动发的是 §1.1 的疏散。
- **进场转发欠账**：发现预算 3 s、退避 250 ms → 2 s、至多 16 次、总 20 s（`scene_route_helper.h:28-41`）；每次按 node_id 重新解析节点，因为同 uuid 重注册是「先销毁再创建」
  且不发移除事件（`pending_scene_entry_comp.h:20-22`）。**超限的客户端可见收口**：23 `{3023}` → 34 `KickPlayer{reason=3023}` → `shutdown()`，1 s 后强关
  （`scene_entry_dispatch.cpp:156-200`；`scene_entry_dispatch.h:37`）。疏散改派（§1.4 第 5 步）与死节点之后的重新绑定都走这条路。Java 5.2 对应的出口是
  「23 `{3023}` 后断开、不发 34」（5.2 D5 / D11），5.5 沿用。
- **scene 对 gate 消失什么都不做**：`cpp/nodes/scene/handler/event/node_event_handler.cpp:26-30` 是空实现。

### 3.2 Java 现状

- **链路断开即断连接**：`SceneLink.fail` → `SceneEventRouter.onLinkDown`（`SceneEventRouter.java:87-93`）→ `ClientDispatcher.onSceneLinkDown`：绑定在 (节点, 链路代次) 上的会话解绑后
  `closeNow`，**不推 tip**（`ClientDispatcher.java:882-893`、`:1081-1084`）。旧代次断开不影响已在新代次上进场的会话（`boundTo` 判断；inventory `gate.md:345` 记了测试名）。
- **判链路断开只有三种**：TCP 关闭、gate 出站缓冲越过 32 MB 高水位（`SceneLink.offer`）、建链 / 握手失败。**两侧都没有应用层心跳或读空闲检测**：gate 只设连接超时、
  `TCP_NODELAY`、写水位（`NettyLinkConnector.java:64-70`）；scene 只开 `SO_KEEPALIVE`（`NodeLinkServer.java:63`，OS 缺省约 2 h）。宿主掉电、分区、进程挂起时，
  空闲会话永远等不到断开，进场帧写进缓冲后石沉大海、会话一直停在「进场中」。
- **握手不核对对端实例**：`SceneLink.onHelloAck` 只核对节点号与 zone（`SceneLink.java:196-232`），实例号只进日志（`:231`）；而目录条目里有 `instance_id`（`node_directory.proto:25`）。
- **按节点号发帧**：`SceneLinkManager.send(nodeId, frame)` 每节点至多一条活链路，「已就绪的旧链路照常发帧」（`SceneLinkManager.java:15-22`、`:72-96`）；会话的转发与离开都按节点号
  （`ClientDispatcher.java:601`、`:916`）——链路换代后、旧代次的 `onSceneLinkDown` 投递到会话线程之前，同一会话的帧会落到新代次链路上被对端丢弃（推导）。
- **scene 侧**：gate 链路关闭时取消这条链路上的全部进场、写回并释放其上玩家、位置转重连租约（`SceneWorld.onLinkClosed`，`SceneWorld.java:1422-1451`）——比基线 B14 好；
  但 gate 静默死掉时要等 TCP keepalive 才发现，期间这些玩家留在 AOI 里、归属照常续约（重登时经让出请求解决）。

### 3.3 对照与隐患

| 基线件 | 作用 | Java |
|---|---|---|
| 摘除即解绑、连接保持、回 1003 | 会话处置 | 链路一断即断连接、不推 tip（有意不同，未登记，G11） |
| 每次按 node_id 重新解析 | 防实体号悬空 | 链路代次（已覆盖） |
| etcd TTL 180 s | 活性 | 无应用层活性检测（G3） |
| 实体号区分新旧进程（`CompareSceneNode`） | 号复用时不串到旧进程 | 握手不核对实例、按节点号复用链路（G4） |

| # | 隐患 | 出处 |
|---|---|---|
| B11 | 崩溃后最长约 180 s 内 gate 静默丢弃 scene 请求 | §3.1 |
| B12 | DELETE 之后会话变成「哑连接」：每条 scene 请求回 1003，既不改派也不断开 | `client_message_processor.cpp:1104-1113` |
| B14 | scene 对 gate 消失不做处理，玩家一直留在场景里 | `node_event_handler.cpp:26-30` |

---

## 4 Java 现状与差距

### 4.1 钩子核对：规格承诺与代码事实

| # | 规格里的钩子 | 代码事实 | 对 5.5 的含义 |
|---|---|---|---|
| H1 | 5.1：同节点改派「抽成 `ChannelEvacuator` 接口」（`scene-channels-spec.md:85`、`:777`） | 没有这个接口；改派内联在 `SceneWorld.drainStep` / `relocateResidents` / `relocationTarget` / `leastLoadedActive`（`SceneWorld.java:423-509`） | 不新增接口；`relocationTarget` 扩成三态（§5.3） |
| H2 | 5.1：`NodeAvailability`（`scene-channels-spec.md:84`） | 存在（`NodeAvailability.java:3-15`），生产装 `ALL`（`WorldChannelControlPlane.java:73`）；`liveNodes` = 在场且放行（`WorldChannelCoordinator.java:229-243`） | 5.5 给实现（§5.6.3） |
| H3 | 5.1 D12「死节点 = 目录缺席满 20 s」（`scene-channels-spec.md:1075`） | `AbsenceTracker.java:14-40` + `WorldChannelPlanner.deadNodes`（`WorldChannelPlanner.java:123-136`），只看目录 | 加租约键佐证（§5.6.5） |
| H4 | 5.2 / 5.3：疏散 = `SceneTransfers.begin(…, Reason.EVACUATE, playerRequested=false)`（`scene-handoff-spec.md:104`；`dungeon-mirror-spec.md:120`） | 没有 `SceneTransfers`；入口 `SceneWorld.beginRemoteSwitch(player, wantSceneId, wantConfigId)`（`:881-894`），失败出口一律推 23 `{1003}` / `{3023}`（`:897-922`、`:1124`） | 推广为带 reason 的入口，失败出口按 reason 分流（§5.2） |
| H5 | 5.2 `selectSwitchTarget` | `SwitchTargetSelector.selectByMap` 只排除 `from_scene_id`（`SwitchTargetSelector.java:132-143`）；请求里已有 `from_scene_node_id`（`scene_directory.proto:34`） | 加 `exclude_from_node` 与 `fallback`（§5.6.2） |
| H6 | 5.2：「S 节点号租约丢失：只停接客，对外交出不受影响」（`scene-handoff-spec.md:973`） | `SceneNode.onLeaseLost`：停拉计划、停发目录、停监听、拒新进场，「请尽快重启」（`SceneNode.java:654-673`） | 按丢失原因分流（§5.5） |
| H7 | 5.1：「节点号被新进程复用而旧进程仍活着 → gate 按租约代次只连新进程（`architecture.md:119-121`）」（`scene-channels-spec.md:864`） | **不成立**：那段讲 scene 拒绝**低代次的 gate**（`GateLinks`）；gate 对 scene 既不比实例也不比代次（`SceneLink.java:196-232`），按节点号复用链路（`SceneLinkManager.java:15-22`） | 补 scene → gate 方向的围栏（§5.7）；5.1 规格勘误（§8.3） |
| H8 | — | 续期 Lua 在「键不在」与「键归别人」都回 0，一律 `markLost`（`NodeIdLease.java:41-42`、`:155-159`）；续期出错超过 TTL 同样判丢（`:160-164`） | 原因混在一起：全区 Redis 故障 = 全体「身份丢失」 |
| H9 | — | `SceneDirectoryPublisher.publishOnce` 发布前不看租约有效性（`SceneDirectoryPublisher.java:93-118`）；`stop(true)` 无条件删条目（`:124-139`），调用方只按 `isLost` 判（`SceneNode.java:504-508`） | 号复用窗口里旧进程可能盖掉新进程的目录条目（§5.5.4） |
| H10 | inventory：「链路断开直接断连，未登记 PARITY」（`gate.md:344`） | 同 §3.2；PARITY 没有对应行（只有 `PARITY.md:91` 提到 scene 侧写重连租约） | 补登（E14） |
| H11 | 5.3：被 5.5 标记的节点 `createInstance` 回 3000；`DrainCause` 枚举（`dungeon-mirror-spec.md:599`、`:675`） | 5.3 在途 | `DrainCause` 追加 `NODE`（§5.4） |
| H12 | 5.2 / 4.5：选目标客户端与资产通道提供方用 `IsolatedDubboModule`，「生命周期完全由持有者控制」（`IsolatedDubboModule.java:12-15`），`SceneNode.release()` 在写回之后才关（`SceneNode.java:549`、`:561`） | Dubbo 3.3.6 的 `DefaultApplicationDeployer.initialize()` 无条件调 `registerShutdownHook()`，`DubboShutdownHook.register()` 只在 `dubbo.shutdownHook.listenIgnore=true` 时跳过；钩子运行时先把提供方置只读，模块不归 Spring 管就直接 `ApplicationModel.destroy()`（评审对 `dubbo-3.3.6.jar` 反汇编核对，未运行验证）。`IsolatedDubboModule.create` 只设了 `dubbo.service.shutdown.wait`（`:52-53`） | SIGTERM 时 Dubbo 的 JVM 钩子与 Spring 的停机钩子并行：选目标客户端在 SHUTDOWN 疏散开始前 / 进行中就被销毁，每个选目标都 Failed，等于没疏散；对现有 4.5 / 5.2 也意味着资产通道反导出早于写回（与 `release()` 注释的顺序相反）。**5.5 前置**：`IsolatedDubboModule` 设 `dubbo.shutdownHook.listenIgnore=true`（§5.10） |

### 4.2 判死证据与频道容量（5.1）

- 节点目录每 5 s 发布、TTL 15 s（`SceneDirectoryPublisher.java:31-32`）；`SceneAssigner`、`ChannelSelector`、gate 只读目录。
- 节点号租约 TTL 15 s、每 TTL/3 续期、距上次成功续期不足 2/3 TTL 才有效（`NodeIdLease.java:27-33`、`:142-144`；`SceneNode.java:130`）。
- 5.1 P2：领导者每 5 s 一拍；缺席表记「计划引用了、目录里看不到」的节点的首次缺席时刻（单调钟，领导者换人从零计）；缺席满 `dead-node-grace`（20 s）删掉它名下全部记录，
  同一次写入 P5 用新号在活节点补建（D4、D12）；只动容量、不碰玩家。
- 缺席只按节点号判：号被新进程复用时缺席表看到它「在场」、记录保留，新进程按同号重建频道（5.1 §4.9 末行）。

### 4.3 归属安全：为什么不需要再入屏障

- **所有写都有围栏**：夺权只认「已释放」或「租约已过」（`PlayerMapper.java:53-56`）；最终写回、在线存盘、只释放、续约、交出都带 `owner_epoch = E` 条件。别人夺到 E+1 之后，
  老节点的任何写都影响 0 行。
- **持有即能写库**：老节点要继续持有归属必须续约成功（每 `OWNER_LEASE / 3` = 10 s，`OwnerLeaseRenewer.java:33`；租约 30 s，`PlayerStore.java:57`），续约本身就是写库。
  「看上去死了其实还在写」在 Java 里只有一种形态：能写库 → 续约成功 → 别人夺不走。基线要靠时间屏障挡的窗口在 Java 里不存在。
- **其它状态不依赖屏障**：位置记录按 (epoch, 序号) 只收更新的写（`PlayerLocationDirectory`）；资产结局的 durable 只看已落库快照（`architecture.md` §4.12）。
- 结论：`death_at`、屏障常数、推迟摘除、本副本观察表、死节点场景收尾、计数清理全部不移植（§5.13）。

### 4.4 崩溃后的时间线（现状）

| 时刻 | 事件 |
|---|---|
| t0 | 进程死，OS 关 socket → gate 关绑定在这条链路上的会话，不推 tip |
| t0 + ≤15 s | 目录条目过期（最后一次发布在 t0 之前 ≤5 s） |
| 期间重连 | login 读到位置记录仍是死节点的 `o`（TTL 60 s，`PlayerLocationDirectory.java:46`）→ assign（节点不在目录就按原地图选）→ 夺权得 Held → pub/sub 请让出、无人应 → 3 s 后 2005（`EnterGameHandler`；`xm-login/.../application.yaml:95`） |
| t0 + 20–30 s | 归属租约过期（最后一次续约在 t0 之前 ≤10 s）→ 下一次 EnterGame 夺权 → 原地图人数最少的频道（PARITY ⑥）→ 79 / 21 / 47；数据回到最后一次落库（周期存盘 300 s，资产记账后立即存一次） |
| t0 + 约 35–45 s | P2 删掉死节点的记录、新号重铺 |

**一个碰巧成立、没有守卫的关系**：login 先 assign 后夺权；能夺到权的那次 EnterGame 的 assign 时刻 ≥ 租约过期 − `owner-claim-wait` ≥ t0 + (30 − 10 − 3) = t0 + 17 s，
大于目录 TTL 15 s，所以 assign 不会把人送回死节点。只关可用性（违反时进场失败、客户端重试），不关数据（C1）。

### 4.5 节点号租约丢失：现状与暴露面

现状（`SceneNode.onLeaseLost`）：停计划跟随、停目录发布（不删条目）、关链路监听、逻辑线程 `stopAcceptingEnters`；在场玩家照常服务；**没有恢复路径**。

| # | 暴露面 | 后果 |
|---|---|---|
| X1 | Redis 中断（或本节点到 Redis 断开）超过 15 s | 每个 scene 都判丢、永久停接客与停接链路，Redis 恢复后仍要逐台重启（G1）；gate 同时关全部会话（`GateNode.java:272-283`）。基线同情形是重注册 |
| X2 | 号被新进程 P2 占用、老进程 P1 还活着（P1 曾停顿 >15 s：GC、`SIGSTOP`、到 Redis 的分区） | P1 不疏散、不断链；仍能续归属租约，别人夺不走它的玩家 |
| X3 | gate 按节点号复用 P1 的已就绪链路 | 发往 N 的新 `PlayerEnter`（登录、5.2 交出进场）落到 P1：判丢后回 3023（交出进场则 F10 断开），判丢前 P1 会接下来，玩家被一个僵尸进程持有 |
| X4 | P1 判丢前发目录不看租约（H9） | 可能把 P2 的目录条目盖回自己的 |
| X5 | P2 按计划重建同号频道（D4），P1 手里也有这些号 | 别的节点上的玩家显式 63 进这个 scene_id → 选目标落 N → gate 若还拿着 P1 的链路就交给了 P1 |
| X6 | P1 的玩家被资产通道按位置记录 → N → 目录 → P2 定位 | 一直 NOT_HERE，调用方重投 |

**数据安全不受影响**（epoch 围栏 + MySQL 单行归属）；受影响的是路由与可用性。

### 4.6 停服与运维面（现状）

- 停服顺序：停计划拉取与排空推进 → 摘目录 → 停监听 → 停封禁同步、接管订阅、续约 → 断开全部 gate 链路 → 逻辑线程写回全部玩家 → 关存储池 → … → 最后释放节点号
  （`SceneNode.release`，`SceneNode.java:478-593`）。GM 签名停机与 SIGTERM 都走这里（`SceneNodeConfiguration.java:147-154`）。玩家断线，gate 不推 tip。
- 运维面没有 scene 疏散入口（xm-data 只有 gate 排空，`architecture.md` §6 gate 排空段；`GateDrainAdminController`）。

### 4.7 可复用件

| 件 | 位置 | 说明 |
|---|---|---|
| 同节点排空改派 | `SceneWorld.drainStep` / `relocateResidents` / `relocationTarget`（`:423-494`） | 冻结中的跳过；找不到去向计 BLOCKED、原地不动，WARN 只打一次（`:441-480`） |
| 交出事务与探测 | `PlayerStore.handOffOwnership` / `probeOwnership`；`StoragePlayerRepository` 的 HANDOFF / PROBE | 5.2 §5.2 |
| 换图状态机 | `PlayerSwitch`（`pendingAbort`、`detached`）、`SwitchPhase`；`freeze` / `onHandOffDone` / `onProbeDone` / `onHandedOff` / `sendTransfer`（`:969-1102`） | 5.2 §5.5 |
| 不交给自己 | `onSwitchTargetChosen`：选中本节点号、本地却没有该场景时拒绝（`:952-960`） | 号复用时挡住「交给同号新进程」的一半；另一半靠排除源节点 |
| 改绑 | `PlayerTransfer`（`node_link.proto:85-94`）；gate `onPlayerTransfer` / `rebindForTransfer` / `abandonEnter`（`ClientDispatcher.java:721-800`、`:933-960`） | 经源节点的同一条链路发出，与疏散天然契合 |
| 冻结中的离开 / 接管 / 失去归属 / 停服 | `onLinkClosed` / `onTakeoverRequested` / `onOwnershipLost` / `shutdown`（`:1422-1509`、`:1693-1722`） | 都已处理 FREEZING |
| GM 停机的进程退出 | `GmShutdownController.ProcessExit`（`GmShutdownController.java:26-30`）；`SceneNodeConfiguration.java:147-154` | 冲突收尾复用 |
| gate 排空的标记模型 | `GateDrainMarks`、`GateDrainAdminController`、`RedisKeys.gateDraining` | scene 疏散标记照抄 |

### 4.8 缺口

| # | 缺口 | 证据 | 客户端可见 |
|---|---|---|---|
| G1 | 节点号租约不区分「被夺」与「失联」，失联后无恢复：Redis 中断 >15 s 全区 scene 永久停接客 | §4.5 X1 | 是（整区进不去） |
| G2 | 号被夺 / 被复用时老进程不疏散、不断链；gate 旧链路把新进场送进老进程 → 3023 | §4.5 X2–X5 | 是 |
| G3 | gate↔scene 链路无活性检测（宿主静默死亡 / 分区 / 挂起） | §3.2 | 是（会话卡住） |
| G4 | gate 握手不核对对端实例 / 代次；会话的帧按节点号发 | §3.2 | 间接（放大 G2） |
| G5 | 5.2 发起入口只服务 63：无 reason、失败推 tip、选目标不排除源节点 | H4、H5 | 否（内部，但疏散依赖） |
| G6 | 排空只能同节点改派：hash 覆盖 / 孤儿图下本节点没有同图与默认大世界时永远 BLOCKED | `SceneWorld.java:459-470` | 是（排空频道里的人不动） |
| G7 | 没有节点级疏散：目录无节点级标志、`NodeAvailability` 恒 ALL | H2 | — |
| G8 | 停服不疏散 | §4.6 | 是（滚动重启掉线） |
| G9 | 运维面没有 scene 疏散入口 | §4.6 | — |
| G10 | 缩容牺牲者受 5.1 D9 约束（同节点兄弟 + 同节点余量） | `WorldAutoscaler.java:17-25`、`:134-177` | 否 |
| G11 | 链路断开即断连接、不推 tip，未登记 PARITY | H10 | 是 |
| G12 | 常数之间的关系没有守卫（§4.4 的 17 s > 15 s、疏散预算与 gate 宽限、Spring 停机阶段） | — | 否 |
| G13 | 目录发布不受租约有效性围栏 | H9 | 否 |
| G14 | 死节点判定只看目录缺席：逻辑线程卡顿（目录取快照超时）会被当死亡、频道换号抖动 | H3；`SceneNode.java:405-410` | 是（卡顿恢复后玩家被改派） |
| G15 | Dubbo 自带的 JVM 停机钩子在 SIGTERM 时先于 / 并行于 `SceneNode.stop()` 销毁隔离 Dubbo 模型 | H12 | 是（停服疏散失效；资产通道早于写回反导出） |
| G16 | xm-scene 的 `spring.lifecycle.timeout-per-shutdown-phase` 注释称「停服写回要在这个上限之内完成」（`xm-scene/.../application.yaml:21-22`），但 `SceneNode` 只实现同步 `stop()`，`SmartLifecycle.stop(Runnable)` 的缺省实现先同步跑完 `stop()` 再回调，`DefaultLifecycleProcessor` 的等待在它返回之后才开始（spring-context 6.2.19，评审反汇编核对）——这个时限约束不了停服 | — | 否（真正的上界是外部强杀期限，C2） |

---

## 5 Java 设计

### 5.1 总览

```text
触发                                              scene 逻辑线程（唯一写者）                           5.2 原语
──────────────────────────────────────────        ──────────────────────────────────────────          ───────────────────────────────────
计划驱动：SCALE_IN / ORPHAN / REBALANCE /          relocate pass（drainStep 每秒、应用计划后、           Local  → switchScene（5.1：不存盘、不交接）
          EVACUATE（P2b，§5.6.4）                   每个 EVACUATE 结局回来后）：                         Remote → PlayerSwitch(reason = EVACUATE)
节点级：  ADMIN（运维标记）/ SHUTDOWN（停服前）/    排空场景里每个可动玩家：                               → selectSwitchTarget(exclude_from_node)
          CONFLICT（号归了别的实例）                  本节点同图 → 别的节点同图                          → 冻结 → handOff(E → E+1)
5.3：     实例回收 / 级联 / NODE                      → 本节点默认大世界 → 别的节点默认大世界              → PlayerTransfer → gate 改绑 → 目标进场
玩家 63： 5.2 原样（节点疏散中多带排除本节点）        → 原地退避
```

- **线程所有权不变**：疏散状态、`PlayerSwitch`、排空标志只在场景逻辑线程上读写；选目标（Dubbo）、交出（存储线程池）、读运维标记（`scene-sched`）的结果都投递回逻辑线程；
  gate 的换代扫描在 `gate-link-resolve` 线程，会话状态只在会话 EventLoop 上改（§5.14）。
- **安全面只有一个：MySQL 的 `owner_epoch` 围栏。** 本批引入的预算、宽限、租约状态、心跳都只影响路由、体验与容量，不影响数据正确性。

### 5.2 服务器发起的换图：`PlayerSwitch.reason = EVACUATE`

**形状**（替代规格里的 `SceneTransfers.begin`，不另建类）：

```text
enum SwitchReason { PLAYER, TRAVEL, EVACUATE }               // TRAVEL 由 5.4 引入（zone-travel-spec §0.6 裁决 16），5.5 只追加 EVACUATE；
                                                             // 5.3 以后可加 MIRROR / DUNGEON；playerRequested ≡ (reason != EVACUATE)
record RemoteWant(long sceneId, int configId, int fallbackConfigId, boolean excludeFromNode)
SceneWorld.beginRemoteSwitch(ScenePlayer player, RemoteWant want, SwitchReason reason)   // 63 传 PLAYER，excludeFromNode = 节点在疏散
RemoteSwitchTargets.select(playerId, fromSceneId, want, reason, onDone)                  // SceneManagerSwitchTargets 填 §6.3 的三个新字段
PlayerSwitch 新增 final 字段 reason；RESOLVING 槽寿命、令牌、冻结、交出、墓碑一律不变
```

**结局分派**（`onSwitchTargetSelected` / `onSwitchTargetChosen` / `unfreezeInPlace` 等按 `sw.reason()` 分流）：

| 结局 | PLAYER（5.2 原样） | EVACUATE |
|---|---|---|
| 入口 | 63 先回 `{0}` 再发起 | relocate pass 发起，**不回应答** |
| 期望 | 显式 scene_id 或只带地图 | 只按地图（`want.sceneId = 0`），`excludeFromNode = true`，`fallbackConfigId` 见 §5.3 |
| 结果回来时玩家所在场景已不在排空（缩容回滚、运维撤销） | — | 取消：回 NONE、不冻结，计 `cancelled` |
| `Selection.Failed`（调用失败 / 超时） | 23 `{1003}` | **不推 tip**；试本节点默认大世界（§5.3），没有则退避；计 `error` |
| `Selection.Refused` | 23 `{3023}` | 同上；计 `refused` |
| `Chosen` 在本节点 / 就是当前场景 | 本地 `switchScene`，排空中的则 3023 | 不应出现（已排除源节点）：ERROR 日志，按 Refused 处理 |
| `LeaseTooShort` / 探测 `NotCommitted` | 原地解冻 + 23 `{3023}` | 原地解冻，**不推 tip**，退避 |
| `Fenced` | 移除并踢 2017 | 同左（失去归属一律 fail-closed） |
| `Lost`（结局不明） | 移除并踢 3023 | 同左（Q8） |
| 交出提交之后：`link_gone`、`pendingAbort`（LEAVE / TAKEOVER）、gate / 目标侧失败（5.2 F7–F11） | 5.2 原样 | 同左 |
| 冻结闸、3014、墓碑、gap 期间的位置记录（5.2 §5.5、§5.9、§5.14） | — | 全部复用；冻结时 `stopMotion` 照常（只有移动中的玩家才让旁人收到「停了」的 66，`SceneWorld.freeze`，`SceneWorld.java:969-983`） |
| 玩家在 EVACUATE 的 RESOLVING / FREEZING 期间自己发 63 | — | `switchInFlight` 为真 → 63 回 3014（与 5.2 同一闸，客户端可见：玩家没发起过换图也会看到「正在切换场景」，最长约 4 s + 15 s） |

**不变**：`PlayerTransfer`、gate 改绑、目标节点进场一律不知道原因（`node_link.proto` 不加字段，Q12）；交出不拍 LOGIN / LOGOUT 快照（5.2 D8，E8）。

### 5.3 改派去向、顺序、并发与退避（`drainStep` 推广）

**目标图 T**：世界频道且图仍在 World 表 → 本图；孤儿图、副本（5.3 DUNGEON）→ 默认大世界（World 第一行）；镜像（5.3 MIRROR）→ 源场景的图（`dungeon-mirror-spec.md` §6.9 `relocationTarget`、§6.11）。
5.3 把 `drainStep` 扩为 `maintainScenes()`（回收判定 → 级联兜底 → 排空推进 → 销毁，`dungeon-mirror-spec.md` §6.9），本节的 relocate pass 就是其中「排空推进」一步。

**relocate pass**（逻辑线程；`drainStep` 每秒一次、每次应用计划后一次、每个 EVACUATE 结局回到逻辑线程后一次——最后这一次只发起、不销毁场景）：

```text
for scene in 排空中的场景（scene_id 无符号升序）:
  for player in scene 的在场玩家（player_id 升序）:
    跳过（计 switching / skipped_battle / deferred）：player.switching() ≠ null（任何 reason、任何阶段）/ 战斗冻结（6.3）/ retryAt > now
    if 节点不在疏散 && (L = 本节点 T 的 ACTIVE 频道里人数最少者，排除 scene) 存在:
        switchScene(player, L)                                                            // same_map 或 default_world（5.1 原样）
    elif crossNode.enabled():
        if 在途 EVACUATE（RESOLVING + FREEZING）≥ max-in-flight: 本拍到此为止（计 deferred_cap）
        if 存储闸关着（见下）: 本拍到此为止（计 deferred_storage）
        fallback = (T ≠ 默认大世界 && (节点在疏散 || 本节点没有默认大世界 ACTIVE 频道)) ? 默认大世界 : 0
        beginRemoteSwitch(player, RemoteWant(0, T, fallback, true), EVACUATE)
    elif 节点不在疏散 && T ≠ 默认大世界 && (D = 本节点默认大世界 ACTIVE 最少者) 存在:
        switchScene(player, D)                                                            // 跨节点没装配时退回 5.1 顺序
    else:
        计 blocked，retryAt = now + backoff
EVACUATE 选目标 Refused / Failed 回到逻辑线程：
    节点不在疏散 && T ≠ 默认大世界 && D 存在 → switchScene(player, D)（计 default_world）
    否则 → retryAt = now + backoff
人数为 0 且没有指向它的在途进场 → 销毁（5.1 原样；实例按 5.3）
```

于是去向顺序是：**本节点同图 → 别的节点同图 → 本节点默认大世界 → 别的节点默认大世界 → 原地退避**。理由：换图比换进程打扰更大（重新加载地图、落出生点、离开队友所在的图）；
5.1 D8 已确立「同图优先」，这里把它推广到跨节点。per-node 覆盖下缩容牺牲者总有本节点同图兄弟（§5.6.6），不会走到远端。

**与 5.1 / 5.2 的行为差异**：

- 5.1 只跳过 FREEZING、照常改派 RESOLVING 的玩家（5.2 §5.5「5.1 的排空改派只跳过 FREEZING」）；5.5 跳过**任何**在途 `PlayerSwitch`——一个玩家同一时刻只有一个换图在途，
  他自己的换图失败后下一拍再看。
- `relocateResidents` 的「一个找不到，剩下的也找不到」提前返回（`SceneWorld.java:460-470`）只在纯本地分支成立；有远端分支时逐人判断。

**并发与退避**：

| 项 | 值 | 理由 |
|---|---|---|
| 每节点在途上限 | `xm.scene.evacuation.max-in-flight` 缺省 32，范围 [1, 256]；计数 = reason = EVACUATE 且处于 RESOLVING / FREEZING 的 `PlayerSwitch` | 冻结窗口与存储池排队可预测（存储池 4 线程，交出与在线存盘共用） |
| 补位 | 每个 EVACUATE 结局回来立即补一次，另外每秒一次 | 只靠每秒补位时吞吐 ≈ 上限 / s |
| 顺序 | (scene_id 无符号, player_id) 升序 | 确定、好测；一个场景先清空、更早销毁 |
| 退避 | 1 s 起翻倍、封顶 30 s（`retry-backoff-initial` / `-max`）；成功改派、离开排空场景即清零 | 不对 SM 或库形成重试风暴 |
| SM 侧 | 逐人调用，不做批量 RPC（N1） | 32 × 少数排空节点的 QPS 很低；软预占（5.1 D7，TTL 10 s）把并发选择摊开 |
| 目标侧 | 不另设上限 | 源端上限 × 排空节点数即总上界；频道容量上限两版都没有（5.1 N2） |
| 吞吐 | 未实测；预计受目标加载与存储池限制 | §11.5 压测后定缺省值（Q11） |
| 存储闸 | 关闸条件（逻辑线程上判，不读外部状态）：存储池队列 ≥ 容量一半（经 `PlayerRepository` 暴露只读水位；`LinkedBlockingQueue.size()` 线程安全、不阻塞）；或最近 `retry-backoff-max`（30 s）内有一次 EVACUATE 交出以 `Lost` 结束、或交出 / 探测连续 `Failed` ≥ 3 次。关闸期间不发起**新的** EVACUATE（已在途的照常走完），计 `deferred_storage` | 5.2 的 `Lost` 一律踢 3023（fail-closed，Q8）；库抖动时若照常按上限持续冻结新玩家，会把整台节点的人一批批踢下线。闸只限制服务器发起的疏散，不限制玩家自己的 63 |

### 5.4 节点级疏散 `NodeEvacuation`

#### 5.4.1 状态（逻辑线程）

```text
SceneWorld.nodeEvacuation : null | NodeEvacuation{cause ∈ {ADMIN, SHUTDOWN, CONFLICT}, sinceNanos, deadlineNanos (ADMIN 为 0 = 无)}
cause 只升级不降级：ADMIN → SHUTDOWN / CONFLICT；升级时 deadline 取较早者
```

#### 5.4.2 进入 `beginNodeEvacuation(cause, budget)`（幂等）

1. `acceptingEnters = false`；加载中的进场立即 `releaseClaim` + `failEnter(3023)`（普通进场回大厅；交出进场由 gate 推 23 `{3023}` 后断开，5.2 F10）。
2. 本地全部场景标排空：世界频道、计划外场景、5.3 实例（`DrainCause.NODE`）。疏散期间 `applyChannelPlan` **不新建、不把排空改回 ACTIVE**，只记版本、照常应用 DRAINING
   （否则计划里的 ACTIVE 会把本地排空改回去，`SceneWorld.java:353-358`）。
3. 目录：租约有效（ADMIN / SHUTDOWN）→ `SceneNodeInfo.evacuating = true` 并 `requestPublishNow()`（同一次发布里全部场景 `draining = true`）；CONFLICT 时发布者已停（§5.5）。
   `evacuating` 与场景列表在**同一次**逻辑线程快照里取（`SceneDirectoryPublisher.Snapshot` 增加该字段；现在的 `SceneNodeInfo` 是启动时建好的静态模板，`SceneNode.java:396-404`），
   保证读方看不到「节点在疏散、场景却还 ACTIVE」的半截状态；`lease_epoch` 由发布线程在发布前从租约读（volatile）。
4. 立即跑一次 relocate pass。

#### 5.4.3 疏散期间

| 事件 | 处理 |
|---|---|
| 玩家发 226（5.4） | 疏散占着 `switching` 槽的玩家回 13000 / 3014（5.4 §5.5 第 4 步）；没被疏散占槽的照 5.4 发起 TRAVEL，之后疏散跳过他；跨 zone 交出不受 `exclude_from_node` 影响 |
| 玩家发 63 | `resolveSwitchTarget` 自然得出：显式本地号 → 3023（排空中，5.1 D11）；只带地图或显式远端号 → Remote，请求带 `exclude_from_node = true`；自己的换图在途 → 3014 |
| 63 建镜像（5.3） | 按 5.3 的「节点停止接客」出口：不建、推 23 `{3023}`（`dungeon-mirror-spec.md:498`），不发 `createInstance` |
| 组队跟随 | 不跟进排空场景（5.1 §4.13），自然跳过 |
| 断线 / LeaveGame / 被接管 / 失去归属 | 5.2 原样（RESOLVING 照常处理，FREEZING 记 pendingAbort） |
| 在线存盘、位置续期、续约、接管订阅、资产通道 | 照常（冻结中的按 5.2 §5.9） |
| 新的 gate 链路 | ADMIN / SHUTDOWN 照常接受（已有 gate 换代重连要用）；CONFLICT 已停监听 |

#### 5.4.4 收敛谓词与结束

`nodeEvacuated()` = `playersById` 空 ∧ `pendingEnters` 空 ∧ `transfersInFlight == 0` ∧ `transferWritesPending == 0` ∧ `travelFramesPending == 0`
（`transferWritesPending` 是新计数：`sendTransfer` 写出成功时 +1，写 future 完成时 −1，保证进程退出前 `PlayerTransfer` 都已冲刷，§5.12 V7；`travelFramesPending` 由 5.4 提供：
跨 zone 旅客「已交出、实例已移除、待落点在写、`PlayerTransfer{redirect}` 还没写出」期间计数，`zone-travel-spec.md` TF16 要求 5.5 并进谓词，否则停服 / 冲突疏散会在 124 发出之前退出）。
对应基线 `IsEmergencyRelocateDrained`（`player_lifecycle.cpp:2233-2247`）。

| cause | 来源 | 预算 | 结束 |
|---|---|---|---|
| ADMIN | 运维标记（§5.9），scene 每秒读、只认本实例 | 无；找不到去向的玩家原地退避 | 标记被删 / 过期 / 实例不符，且当前 cause **仍是 ADMIN**（已升级为 SHUTDOWN / CONFLICT 的不因撤标记而结束）→ `endNodeEvacuation()`：清标志；租约有效且没在停服时恢复接客；目录 `evacuating = false` 补发；强制按当前计划重应用（ACTIVE 改回承载、DRAINING 照旧）；5.3 实例的 `NODE` 排空撤回 NONE；在途 EVACUATE 照常结束（结果回来时已不在排空 → 取消）。人走空后**不自动停节点**（同 gate 排空「只判定、不踢人」，Q7） |
| SHUTDOWN | `SceneNode.stop()` 在 `release()` 之前（SIGTERM 与 GM 签名停机都走这里，§5.10） | `xm.scene.evacuation.budget` 15 s；目录里没有别的接客 scene 节点就跳过、中途变成没有就提前结束 | 谓词成立 / 到期 / 无去处 → 原样执行 `release()`（剩下的写回、先推 23 `{1003}` 再断线） |
| CONFLICT | 节点号租约 TAKEN（§5.5） | 15 s（对齐基线 `kDrainBudget`，`node.cpp:883`） | 谓词成立 / 到期 → `ProcessExit.exitSoon()` → `stop()` → `release()`，进程退出；gate 的旧链路随之断开 |

**触发矩阵（基线 → Java 现状 → 5.5）**：

| 触发 | 基线 | Java 现状 | 5.5 |
|---|---|---|---|
| 缩容 | `DestroyScene` → 改派默认大世界，无超时 | 同节点同图改派；牺牲者须有同节点兄弟；超时回滚 | 去向加跨节点；牺牲者按全图余量（E10）；保留超时回滚 |
| 孤儿图 / 再平衡 / 实例回收 | 同上 | 同节点（孤儿落默认大世界） | 加跨节点默认大世界 |
| 运维疏散 | 无 | 无 | ADMIN（E4） |
| SIGTERM / GM 停机 | 只存盘 | 写回 + 断链 | SHUTDOWN 先疏散，再写回（E4） |
| 号被他人占用 | 疏散 15 s 后 `Shutdown()` | 只停接客、服务到离开 | CONFLICT 疏散 15 s 后退出（E7） |
| 租约过期 / Redis 失联 | 只重注册 | 同上一行 | 挂起，恢复后续上或原号复占，**不疏散**（E7） |
| 进程崩溃 / 断网 | 屏障后接管 | 租约过期后 login 夺权 | 不变（E11） |

### 5.5 节点号租约：区分丢失原因、原号复占、挂起

**续期 Lua 改为三值**：`get == 本实例 → pexpire，回 1`；`键不存在 → 回 0`；`键归别的实例 → 回 −1`。`NodeIdLease` 增加 `reacquireSameId` 模式（scene 的 `SCENE` 与 `SCENE_GUID`
两份租约打开；gate / login 等保持现有语义，Q3），回调接口改为带原因的监听器：

| 续期结果 | 现状 | 5.5（`reacquireSameId`） |
|---|---|---|
| 1 | 续上 | 同 |
| 0（键过期、没人拿） | `markLost` | `SET key instance NX PX ttl`：成功 → INCR 新防护代次、`onRegained(newEpoch)`（WARN）；失败（这一瞬被人拿走）→ TAKEN |
| −1（键归别的实例） | `markLost`（与 0 不分） | TAKEN → `onTaken()`，停续期（终态） |
| 出错，距上次成功 < TTL | WARN、下一轮 | 同（期间 `isValid` 可能已为假） |
| 出错，≥ TTL | `markLost` | **挂起** → `onSuspended()` 一次；继续每 TTL/3 尝试，恢复后按上面三行走（1 → `onResumed()`） |

`leaseEpoch` 改为可变（volatile）；`isValid()` 定义不变（未 TAKEN、距上次成功续期 / 占号 < 2/3 TTL），所以挂起期间与 TAKEN 后都为假，发号与计划拉取照旧 fail-closed。

- **原号复占的 INCR 失败**：同 `acquire` 的 `nextLeaseEpoch`（`NodeIdLease.java:104-121`）——领不到新代次就用 `RELEASE_LUA` 放掉刚占的键、保持挂起、下一轮再试；不许带着旧代次复占
  （否则 gate 的握手比对与换代扫描分不出「原号复占」和「旧进程」）。
- **缺省模式的使用者**：gate、login、guild、team、trade、scene-manager 的发号租约、xm-data `OpsIds`、6.2 battle（`BattleInfrastructure.java:95`）都用
  `Runnable onLost` 构造；三值 Lua 下缺省模式把 0 与 −1 都映射到原来的 `markLost`，行为逐字不变，回归测试覆盖这些调用方的构造路径。
- **复占与雪花**：P1 自己的 `Snowflake` 保留 `lastMs`，复占后不会与自己早先发的号撞；与「键空闲期间别的进程用过这个 worker」之间的关系，与新进程启动占到一个刚被释放的号
  完全相同（同一个 NTP 前提，`architecture.md:666`、`:731`），不新增风险。

**scene 的反应**：

| 回调 | 动作 |
|---|---|
| `onSuspended` | 停目录发布（**不删**条目）；逻辑线程拒新进场（3023）；readiness DOWN；`xm.scene.node.identity = 1`。在场玩家照常（归属在 MySQL，不依赖 Redis）；链路监听保持 |
| `onResumed` / `onRegained` | 恢复目录发布（带新 `lease_epoch`）；同步拉一次计划——挂起期间被 P2 删掉的频道在本地成了「计划外」，按 5.1 §4.10.2 排空（玩家经 §5.3 改派）；恢复接客；readiness UP |
| `onTaken` | CONFLICT：停计划拉取、停目录发布（不删条目）、停接新链路 → `beginNodeEvacuation(CONFLICT, budget)` → 看门狗 → 退出（§5.10） |

这对应基线 2026-09-08 起的口径：「lease 过期不等于身份被抢」，只是重新挂上，CAS 失败才冲突关停（`etcd_service.cpp:598-607`、`:234-251`）。不把失联当冲突的理由：
Redis 不可达时 SM 同样读不到目录、选不出目标；按冲突处理并退出，一次 Redis 抖动就会让全区 scene 停掉。

**`SCENE_GUID` 租约**（全服发号，`SceneNode.java:599-606`）：挂起时 `LeaseGatedSnowflake` 已经 fail-closed（物品入包回 6004）；键空闲时复占原号；TAKEN 时申领任意空号、给
`LeaseGatedSnowflake` 换 worker（换之前发号暂停）。不触发节点疏散。

**5.5.4 目录发布围栏**：`SceneDirectoryPublisher.publishOnce` 在 `directory.publish` 之前**立刻**检查 `lease.isValid()`（2/3 TTL 的界保证写入时键仍归本实例），
`stop(removeEntry)` 也只在 `isValid()` 时删条目。`SceneNodeInfo` 带上 `lease_epoch`；`LinkIdentity` 改为持有 supplier，握手应答带 `scene_lease_epoch`（§6.3）。

这道围栏**收窄**而不是**关死** X4：2/3 TTL 的界假设「判有效之后立即落地」，而一次 Redisson 写在客户端内部可能超时重试（命令超时 × 重试次数，可达十余秒，
推导），迟到的写仍可能在新进程 P2 占号之后盖掉 P2 的条目，直到 P2 下一次发布（≤ 5 s）把它盖回来。所以读方不能只信「最新一次读到的条目」：
gate 的换代扫描对每个节点号记住**见过的最大** `lease_epoch`，代次更低的条目一律当作过时、不触发任何换代或回退（§5.7.3）；scene-manager 的规划器只用
目录判在场与否，短暂的旧条目最多让 P2 名下的频道晚一拍被识别，不影响数据。要真正关死需要「校验租约键 + 写目录」同一段 Lua，而目录是 Redisson
`RMapCache` 的内部结构，不值得为一个 ≤ 5 s 的自愈窗口去手写它的编码（R9）。

**实体号雪花（worker = 节点号 N）**：P1 判 TAKEN 后仍可能发实体号，与 P2 撞号无害——实体号只在本进程本场景有意义，疏散目标又排除了 N。写进文档，不处理。

### 5.6 scene-manager

#### 5.6.1 选择面：不改 `isUsable`

疏散节点在同一次目录发布里把全部场景标 `draining`（§5.4.2），`SceneAssigner.assign`（原实例在排空中 → 按地图重选，`SceneAssigner.java:78-86`）、`ChannelSelector`、
`SwitchTargetSelector.selectExplicit`（排空中拒绝，`SwitchTargetSelector.java:122-126`）已经按场景排除它。**不**在 `SceneAssigner.isUsable` 上加节点级过滤：
`DirectoryView.of` 同样用 `isUsable`（`DirectoryView.java:67`），加了会让规划器把疏散中的节点当缺席——P2 满 20 s 删光记录、P3 把「场景已不在目录」误判为已销毁（裁决 A1）。
`DirectoryView.Node` 增加 `evacuating`、`leaseEpoch` 两个字段（已有 `instanceId`）。

#### 5.6.2 `selectSwitchTarget` 的新规则（`SelectSwitchTargetRequest` 追加字段见 §6.3）

- `exclude_from_node = true`：先剔除 `from_scene_node_id` 名下的全部条目；显式 `want_scene_id` 落在源节点上 → `NOT_FOUND`。
- `reason = EVACUATE`：`want_scene_id` 必须为 0（否则 `BAD_REQUEST`）；按 `want_scene_config_id` 用 `ChannelSelector` 选（世界图校验同现状）；没有候选且 `fallback_scene_config_id ≠ 0`
  → 按 fallback 再选一次（同样世界图校验、同样写软预占）；都没有 → `NOT_FOUND`。
- 「同一 scene_id 出现在多个节点」继续拒绝（`selectExplicit`，`:109-114`）。
- 指标 `xm.scene_manager.switch{result, reason}`（reason 只有 player / evacuate 两个取值：5.4 的 TRAVEL 跨 zone、不调 `selectSwitchTarget`，所以 proto 枚举 `SwitchReason`
  与 scene 进程内的 `SwitchReason` 不是同一个类型，TRAVEL 不出现在请求里）。

#### 5.6.3 `NodeAvailability` 的实现

节点可接新频道 ⇔ 目录在场 ∧ `evacuating = false`。控制面 tick 用本拍的 `DirectoryView`（不多读）；5.3 `createInstance`（Dubbo 线程）读请求方节点的单条目录条目
（`NodeDirectory.findAsync`），`evacuating` → 回 3000。

#### 5.6.4 P2b：疏散节点的频道转排空、同批补建

规划器在 P2 之后、P3 之前新增一步：目录在场且 `evacuating = true` 的节点，其名下 ACTIVE 记录转 `DRAINING(DRAIN_EVACUATE = 4)`；P5 在**同一次写入**里按覆盖 / 期望数在活节点
补建（`liveNodes` 已不含疏散节点）。P3：`DRAIN_EVACUATE` 排空超时**不回滚**，只告警（同 ORPHAN / REBALANCE）；D10 只对 SCALE_IN 生效，**且不对疏散节点上的 SCALE_IN 记录生效**
（回滚出来的 ACTIVE 下一拍又会被 P2b 转走，期望数却已 +1，等于白白取消一次缩容；这类记录超时后改转 `DRAIN_EVACUATE`，期望数不动）。撤销标记后节点重新成为活节点，
下一拍 P5 按覆盖规则给它铺新号频道（per-node），或由择机迁移平衡（hash）。

为什么不用分区稿 ① 的「不计入期望与覆盖、记录原样」（裁决 A2）：那会留下「计划 ACTIVE、节点本地排空」的长期不一致，`NodeAvailability` 现有约定「已有记录照常计数」也要改；
撤销后这些记录重新计数，频道数会多出一截。

CONFLICT 时老进程 P1 不发布目录，新进程 P2 在目录里正常在场：N 名下的记录保持 ACTIVE，由 P2 按同号重建（5.1 D4）；P1 的疏散靠 `exclude_from_node` 避开 N。

#### 5.6.5 P2：死节点判定加租约键佐证

| 状况 | 判定 |
|---|---|
| 缺席 < `dead-node-grace`（20 s） | 不动（现状） |
| 缺席 ≥ 20 s，`xm:node-id:scene:<zone>:<N>` 不存在 | 删除该节点名下全部记录（现状） |
| 缺席 ≥ 20 s，键仍有持有者（同一实例：活着但发不出目录，典型是逻辑线程卡住、取快照超时；别的实例：正在换代重启） | 标 `world_nodes{state=stuck}`、保留记录；缺席满 `stuck-node-grace`（120 s）仍缺席才删，并打 ERROR |
| 读键失败 | 按「有持有者」处理（偏晚、安全） |

读法：只在快照阶段对**缺席节点**逐键 GET（租约键不在 `{z:}` hash tag 下，不进写入 Lua）；结果以 `PlanInput.leaseHeld` 交给规划器，规划器保持纯函数。理由同基线「死亡要正面证据，
缺席只说明这一刻没看到它」（`enterscenelogic.go:858-866`；`load_reporter.go:175-192`）：目录发布依赖逻辑线程快照，续租只在调度线程，两路信号独立，逻辑线程卡顿不会被误判成死亡、
进而引发频道换号的抖动（G14）。崩溃时两个键几乎同时过期，不增加重铺延迟。

#### 5.6.6 放开 5.1 D9 的缩容约束

`WorldAutoscaler.pickScaleInVictim`（`WorldAutoscaler.java:134-159`）在 `xm.scene-manager.world.cross-node-relocation = true`（缺省；false 退回 5.1 口径，用于回滚）时：

- **余量要按「改派实际会落到的那组频道」算**，而不是一律全图：relocate pass 先去本节点同图（§5.3），本节点有同图兄弟时牺牲者的人**全部**落到这些同节点兄弟上，
  这时按全图余量放行会把某个同节点兄弟推过扩容线，重新引入基线 `hasHeadroomFor` 要防的「缩完立刻扩」振荡（`world_autoscale.go:259-262` 注释）。所以：
  牺牲者在本节点有同图 ACTIVE 兄弟 → 余量只算这些兄弟（= 5.1 现状）；没有 → 余量算别的节点上该图的 ACTIVE 频道（scene-manager 按人数最少挑，与基线
  `ReserveBestWorldChannelForEnter` 的全图挑法一致，回到 `world_autoscale.go:263-274` 的口径）；
- **per-node 覆盖**下仍跳过「牺牲者是其节点该图最后一个 ACTIVE」的候选，保住 5.1 的 I1（63 按地图换场景永远在本节点完成）——于是 per-node 下牺牲者总有同节点兄弟，
  **规则与 5.1 完全相同**，本节只在 hash 覆盖下改变行为；
- **hash 覆盖**下不再要求同节点兄弟（5.1 的「0 人可免」随之并入）；
- 镜像源仍不缩（`isMirrorSource`，`:179-187`，不变）；
- 类注释的「收紧三处」改为「余量按改派落点算」一处；D9 的「必须有同节点兄弟」撤销，**D10（缩容排空超时回滚）保留**：有跨节点改派之后它只在 scene-manager 不可达、
  或别处都没有容量时才会触发（回答 5.1 规格 Q5）；
- 择机迁移仍只迁空频道（基线 `world_rebalance.go:168-178`、`:195-197`），不变。

scene 的跨节点能力取决于 `crossNode.enabled()`（5.2 装配）；两边开关不一致时（SM 放开、scene 没装配）牺牲者的居民 BLOCKED，最终由 D10 回滚兜住。

#### 5.6.7 观测

领导者记下每个节点最近一次看到的 `(instance_id, lease_epoch)`：实例变了计 `xm.scene_manager.world.node.restarts`，只有代次变了（原号复占）计
`xm.scene_manager.world.node.regained`（两者分开，免得 Redis 抖动后的复占被当成重启告警；指标名前缀沿用现有 `xm.scene_manager.*`，`WorldChannelMetrics.java:33-39`）；只读 actuator 端点 `worldnodes`（显式加进暴露清单，
`xm-scene-manager/.../application.yaml`）：按 zone、按节点列出在场、实例、`lease_epoch`、`evacuating`、缺席时长、租约键持有者、`stuck`、各状态记录数。

### 5.7 gate

#### 5.7.1 链路心跳与读空闲（修 G3）

- `NodeLinkFrame` 追加 `LinkPing ping = 10`、`LinkPong pong = 11`（都只有 `uint64 seq`）；`LinkHello` 追加 `uint32 ping_interval_ms = 7`（gate 声明会发心跳）；
  `LinkHelloAck` 追加 `uint32 ping_interval_ms = 7`（scene 回显 = 支持）、`uint64 scene_lease_epoch = 6`。
- gate：握手成功且 ack 回显非 0 时，在链路的 EventLoop 上**固定**每 `xm.gate.link-ping-interval`（5 s）发一次 Ping（不按写空闲：有上行流量、对端却没有下行时仍要探活）；
  Netty 自带 `IdleStateHandler` 读空闲超过 `xm.gate.link-idle-timeout`（15 s）→ `SceneLink.fail("心跳超时")`，走现有 `onLinkDown`。不加依赖。
  读空闲按**任何**入站帧计（`ToClient`、进场结果、`PlayerTransfer`、Pong 都算），不是只认 Pong：scene 背压暂停读时（§5.8）Ping 会滞留在它的 TCP 缓冲里，
  只要它的逻辑线程还在往外写，gate 就不会误判；逻辑线程积压超过 1 万帧且 15 s 没有任何下行，按死链路处理是可以接受的。
- 滚动升级：旧 scene 不回显 → gate 不做读空闲判定；旧 gate 不声明 → scene 不做读空闲判定（§5.8）。
- 「gate 等进场结果没有超时」（分区稿 ②）：链路死掉的情形由心跳覆盖（链路判死 → `onEnterUndeliverable` / `onLinkDown` 收尾）；链路活着而 scene 逻辑线程不回结果的情形不在本批（N6）。

#### 5.7.2 握手核对对端实例与代次（修 G4 的一半）

`NettyLinkConnector.resolveAndConnect` 已经查过目录（`NettyLinkConnector.java:49-60`），把解析到的 `(instance_id, lease_epoch)` 记进 `SceneLink`。`onHelloAck` 增加：
`scene_instance_id == 解析到的实例`，且 `scene_lease_epoch ≥ 解析到的代次`（任一侧为 0 = 旧版本，跳过代次比对）；不符 → `fail("对端实例与目录不符")`。

#### 5.7.3 换代扫描与按代次寻址（修 G4 的另一半、X3、X5）

| 步骤 | 做法 |
|---|---|
| 扫描 | `gate-link-resolve` 线程每 `xm.gate.link-supersede-check-interval`（5 s）读一次 `NodeDirectory.list(zone)`。对每条已就绪链路：目录里同号条目缺席 / 读失败 → 不动（死活由心跳判）；实例相同、代次更高（原号复占）→ 只更新记录的代次；**实例不同且代次更高**（或任一侧代次为 0 时，连续两次扫描都不同）→ `supersede(link)`。每个节点号记住**见过的最大代次**，代次低于它的条目当作迟到的旧写（§5.5.4）直接忽略，绝不因此换代或撤销已做的取代 |
| 取代 | 链路从「当前」表移到「被取代」表（键 = (节点号, 代次)，带截止时刻 = 现在 + `xm.gate.link-superseded-grace` 30 s），计 `xm.gate.scene.links.superseded`；之后发往该节点的**新绑定**帧（`PlayerEnter`、5.2 改绑后的 `PlayerEnter`）建新代次链路，按目录连到新进程 |
| 按代次寻址 | `SceneLinks` 增加 `send(nodeId, gen, frame)`：当前链路代次相符 → 它；被取代表里有 (nodeId, gen) → 它；都没有 → 0（`LINK_UNAVAILABLE`）。已有绑定的帧——`ClientForward`（`ClientDispatcher.java:601`）、`PlayerLeave`（`:916`）——带 `s.sceneLinkGen` |
| 宽限到期 | 被取代链路 `fail("被新实例取代")` → `onLinkDown(node, oldGen)` 只关仍绑在旧代次上的会话（断开原因 `scene_link_superseded`，先推 23 `{1003}`）；老进程在链路关闭时写回这些人（`onLinkClosed`） |
| 疏散中的改绑 | 老进程发出的 `PlayerTransfer` 走被取代的旧链路，照 5.2 的 (节点, 代次, 玩家, from_epoch) 校验改绑；改绑后的 `PlayerEnter` 走目标 T 的当前链路（`exclude_from_node` 保证 T ≠ N） |

按代次寻址顺手修掉 §3.2 的潜伏问题：链路换代后旧绑定的帧不再落到新代次上。宽限给僵尸进程的寿命设了上界，即使它处于挂起态看不见冲突。
基线的对应做法是比较节点**实体号**（同号重新注册就是新实体，`scene_route_helper.h:41-59`）、摘除时作废旧绑定（`client_message_processor.cpp:1104-1113`）。

#### 5.7.4 链路断开时的会话处置（修 G11）

维持「断开连接」，不采用基线的「解绑 + 哑连接」；但断开前先推 23 `{1003}`：`onSceneLinkDown` 里 `closeNow(s)` 改为 `s.sendThenClose(tip(1003))`（写法同 `kickByServer`，
`ClientDispatcher.java:866-876`）。理由：Java 里链路断开就意味着 scene 已经移除了这些玩家或已经死了，没有改绑的来源，保留连接只会让每条请求都回 1003（B12）；
1003 与基线首个 scene 请求得到的码相同，客户端知道原因。处于 `transferEntering` 的会话照 5.2 F11 处理。登记 PARITY（E14）。

### 5.8 scene：gate 链路读空闲（B14 的 Java 版）

`NodeLinkHandler` 收到 Ping 在**链路 I/O 线程**上直接回 Pong（不进逻辑线程队列、不占背压计数）；握手里 `ping_interval_ms ≠ 0` 时给子 channel 装读空闲 = 3 × 该间隔，超时关链路，
走现有 `onLinkClosed`：取消进场、写回并释放、位置转重连租约。效果：静默死掉的 gate 上的玩家在约 15 s 内离场，而不是等 TCP keepalive（约 2 h）。scene 不另设配置，由 gate 决定间隔。

两处必须写明的边界（评审补）：

- **背压暂停读期间不计读空闲**。现有背压在「已投递未执行的帧 ≥ `link-max-pending-frames`（1 万）」时 `setAutoRead(false)`，降到一半才恢复（`NodeLinkHandler.java:195-198`、`:233`）。
  暂停期间 scene 一个字节也不读，`IdleStateHandler` 的读空闲照样会到点——不处理的话，逻辑线程积压超过 15 s 就会被 scene **自己**判成「gate 死了」，关链路并写回整条链路上的玩家
  （推导）。做法：暂停读时记下标志，读空闲事件在标志为真时忽略；恢复读时由一次 `channelReadComplete` 自然重置计时。同理，「Ping 不受背压影响」只在没暂停读时成立。
- **间隔做边界校验**：`ping_interval_ms` 不在 [1000, 30000] 视为 0（不启用读空闲）并 WARN 一次，防止异常的 gate 声明 1 ms 让 scene 3 ms 就关链路。

### 5.9 运维面

**xm-data**（管理端口只绑本机、共享令牌 + 操作人 + 审计日志，同 `GateDrainAdminController`；Redis 懒加载，不可达时这几个接口回 503、不挡启动）：

| 接口 | 行为 |
|---|---|
| `POST /admin/scenes/evacuate {zone_id, node_id, ttl_sec?=3600, force?}` | 目录里没有该节点 → 404；TTL 不在 [`xm.data.scene-evacuate.min-ttl` 30 min, 86400 s] → 400；一段 Lua 同时做「写 `xm:scene-evacuating:<zone>:<node>` = `<Redis TIME 秒>:<目录里的实例>` EX ttl」与「打上之后本区是否还剩没在疏散的 scene 节点」：没有 → 409 `last_node`（`force` 才打）；同实例已有标记 → 起点不变、回 `marked=false`；已有同实例标记没有 TTL → 409 `existing_mark_invalid` |
| `GET /admin/scenes/{zone}` | 目录叠加：实例、`lease_epoch`、`evacuating`（节点自报）、标记（起点、`stale_mark`）、在线人数、频道（ACTIVE / 排空）、`evacuated = 标记适用 ∧ evacuating ∧ 在线 0` |
| `DELETE /admin/scenes/evacuate/{zone}/{node}` | 删标记，恒 204 |

键经 `RedisKeys.sceneEvacuating(zone, node)` 生成，格式与 gate 排空键同形（只支持单节点 Redis，同 `GateDrainMarks`）；标记类 `SceneEvacuationMarks` 放在 xm-discovery。

**scene**：`ChannelPlanFollower` 每秒那一拍（租约有效时）多 GET 一次本节点的标记；实例与自己相同 → `beginNodeEvacuation(ADMIN)`；ADMIN 进行中而标记不在 / 实例不符 →
`endNodeEvacuation()`；读失败保持现状（不开始、不结束）。scene 是标记的唯一读者，scene-manager 只看目录 `evacuating`（裁决 A3）。

**scene 管理端口**：不新增端点；`xm.scene.shutdown.evacuate-first` 同时决定 SIGTERM 与 `POST /gm/graceful-shutdown` 是否先疏散。

### 5.10 停服与冲突收尾的顺序

```text
SceneNode.stop()（SIGTERM 时是 Spring 的 JVM 停机钩子线程，GM 签名停机时是 gm-shutdown 线程）:
  running = false
  if shutdown.evacuate-first && evacuation.budget > 0 && 节点号租约 isValid && 目录里有别的未疏散 scene 节点:
      done = callOnLogic(beginNodeEvacuation(SHUTDOWN, budget))     // 返回 CompletableFuture，由逻辑线程上的收敛检查完成
      停机线程上 done.get(≤ 1 s) 循环；每 1 s 复查一次目录「还有没有别的未疏散节点」（Redis 读，不在 I/O / 逻辑线程上）
      直到：done 完成（疏散完 / 到期）/ 没有别的接客节点 → INFO 一行（用时、迁走人数、剩余人数）
  release()          // 原样：停计划拉取 → 摘目录（租约有效才删）→ 停监听 → … → 断链 → 写回剩余玩家 → 释放节点号

onTaken（scene-sched 线程，§5.5）:
  停计划拉取；publisher.stop(false)；linkServer.stopAccepting()
  if !running:                     // 停机已在进行（SHUTDOWN 疏散中判出 TAKEN）
      postToLogic(cause 升级为 CONFLICT、deadline 取较早者)；不再调 ProcessExit（停机线程会照常走完 release()）
  else:
      postToLogic(beginNodeEvacuation(CONFLICT, budget))
      收敛检查完成时 → 把 ProcessExit.exitSoon() 交给独立的 "scene-conflict-exit" 线程 → stop()（租约已丢，跳过 SHUTDOWN 疏散）→ release()

收敛检查（逻辑线程；beginNodeEvacuation 时排上，logicLoop.scheduleAtFixedRate 100 ms）:
  nodeEvacuated() || now ≥ deadline → 取消自身、完成 future（结果 drained / budget_exceeded / no_target）
```

- **收敛检查放在逻辑线程上，不放 `scene-sched`**（评审改）：谓词读的全是逻辑线程自有状态，在逻辑线程上直接判、不阻塞。`scene-sched` 只有 3 条线程，计划拉取与目录发布
  已可能各自同步等逻辑线程至多 5 s（`SceneNode.java:136`、`:240-241`），归属续约器与全服发号租约的续期也在上面；再加一个每 100 ms `callOnLogic` 的看门狗，逻辑线程一慢
  就可能把续约挤到有效期之外。停机线程也只等一个 future，不反复 `callOnLogic`。
- **疏散期间全部组件保持运行**：链路（`PlayerTransfer` 要走它）、归属续约器（冻结中的玩家要靠它续租，交出要求剩余租约 ≥ M）、接管订阅（疏散期间来的接管请求要有人处理）、
  存储池、选目标客户端、目录发布器（ADMIN / SHUTDOWN，带 `evacuating`）。`release()` 之后的顺序不变。
- **前置修复（H12 / G15）**：`IsolatedDubboModule.create` 在 `updateAppConfigMap` 里加 `dubbo.shutdownHook.listenIgnore=true`，Dubbo 就不注册自己的 JVM 停机钩子，
  选目标客户端与资产通道提供方只由 `release()` 关。不改的话 SIGTERM 一到 Dubbo 钩子与 Spring 钩子并行，选目标客户端在 SHUTDOWN 疏散期间已被销毁，疏散全部 Failed；
  这也顺带修正 4.5 / 5.2 现有的「资产通道反导出可能早于写回」。GM 签名停机先 `SpringApplication.exit` 关上下文、再 `System.exit`，不受影响，所以只在 SIGTERM 路径上暴露。
- **全部节点同时停**（K8s 删除整个工作负载、本机同时 `kill` 全部 scene 进程）：各节点在同一秒内发布 `evacuating`，彼此在 ≤ 1–2 s 内发现「没有别的接客节点」而提前结束，不白等预算。
  `tools/local/stop-slice.sh` **不是**这种情形：它按 gateway → gate → xm-scene-2 → xm-scene 的顺序**逐个**停（`stop-slice.sh:7`），gate 先停时会话已全部关掉，scene 停时没有人可疏散。
- **部署（外部强杀期限，C2）**：`SceneNode` 只实现同步 `stop()`，Spring 的 `timeout-per-shutdown-phase` 约束不了它（G16），真正的上界是进程管理器的强杀期限。
  `release()` 本身最坏约 `shutdown-save-timeout`（15 s）+ 逻辑线程停止（≤ 5 s，`SceneNode.java:556`）+ 回写池（≤ 2 s）+ 审计发完（`audit-flush-timeout`，缺省 5 s）≈ 27 s，
  加上疏散预算 15 s 与其余 Bean / Dubbo 反导出余量约 3 s，合计约 45 s：`tools/local/stop-slice.sh` 的等待从 20 s 改为 50 s（现在超时即 kill -9，会打断写回）；
  K8s `terminationGracePeriodSeconds` ≥ 60 s（7.6）。`timeout-per-shutdown-phase` 不必改，顺手更正 `application.yaml:21` 那句不成立的注释（§5.15）。
- GM 签名停机的 `affected_count`、签名、nonce 一律不变；单次「先疏散再停」的独立 GM 方法（`Scene.GmEvacuateShutdown`）不做（N4）。

### 5.11 客户端可见（逐结局）

| 结局 | 旧场景旁人 | 被改派者 | 对照基线 |
|---|---|---|---|
| 同节点去向 | 51 | 79 / 21 / 47（同 5.1），同图保留坐标、换图落出生点 | 落点不同（E1） |
| 跨节点成功 | 冻结时 66（停下），交出提交后 51 | RESOLVING 期间照常游玩；FREEZING（一笔库事务，最坏约 15 s）按 5.2 `FreezePolicy`：63 回 3014、移动静默丢、只读照常、写请求回 1005 / 27003；之后目标节点 79（新 scene_id）/ 21（新实体号）/ 47（非空才发）；同图保留坐标；**无 tip** | 基线窗口内一律应答内 1006、随后静默丢弃（E2） |
| 选目标被拒 / 调用失败 / LeaseTooShort / 探测 NotCommitted | 无（冻结前在移动的，旁人收到 66） | **什么都不发**，留在原地，退避后重试 | 基线是哑会话（E3） |
| 疏散在途（RESOLVING ≤ 约 5 s / FREEZING ≤ 约 15 s）时玩家自己发 63 | — | 63 回 3014（玩家没发起过换图也会看到「正在切换场景」） | 基线退出段回应答内 1006、之后静默丢弃 |
| 存储闸关着（库抖动） | — | 不发起新的疏散冻结，玩家原地、无 tip | 基线照发（B1 / B4） |
| Fenced | 51 | 23 `{2017}` 后断开（同 5.2 F4） | 基线：被当废黜销毁 |
| Lost | 51 | 23 `{3023}` 后断开（同 5.2 F6） | — |
| 目标拒绝进场 / 到目标链路不可用（含目标刚进疏散） | — | gate 推 23 `{3023}` 后断开（5.2 F9 / F10） | — |
| 停服 / 冲突预算用尽、无处可去的剩余玩家 | 51（写回移除） | 23 `{1003}` 后断开 | 基线：1003 哑会话 |
| 疏散中加载中的进场 | — | 普通进场：应答 3023、回大厅；交出进场：23 `{3023}` 后断开 | 基线疏散期间无准入闸（B2） |
| 链路断开 / 心跳超时 / 被取代宽限到期 | — | 23 `{1003}` 后断开 | 基线：保持连接、每条请求 1003（E14） |

### 5.12 失败、竞态与幂等

| # | 情形 | 处理 | 客户端 |
|---|---|---|---|
| V1 | 目标节点也刚进疏散（发布之前被选中） | 交出进场被拒（`acceptingEnters = false`）→ 5.2 F10 | 23 `{3023}` 后断开（罕见，E6） |
| V2 | 源节点疏散中崩溃 | 已提交的 E+1 无人持有，≤30 s 后可夺；未提交的回到最近落库 | 断线、重登回 2005 后成功 |
| V3 | 目标进场后、续约前崩溃 | 5.2 F11 | 同上 |
| V4 | scene-manager 不可达 | 计划驱动：Failed → 本节点默认大世界或退避；SHUTDOWN / CONFLICT：到期写回 | 原地 / 到期 23 `{1003}` 后断开 |
| V5 | gate 崩溃 | 5.2 R-J1：E+1 至多 30 s 后才能再进 | 断线 |
| V6 | 两台都在疏散（带 `force`） | 选目标 NOT_FOUND → 原地退避，计 `refused` | 无 |
| V7 | 进程退出时链路上还有未冲刷的 `PlayerTransfer` | 收敛谓词等 `transferWritesPending == 0`；预算到期仍未确认 → `release()` 关链路时写失败，回调投递被拒，E+1 等 30 s（R3） | 断线后 ≤30 s 可进 |
| V8 | 运维撤销 / 缩容回滚与在途选目标交叉 | 结果回来时场景已不在排空 → 取消、不冻结 | 无 |
| V9 | 疏散中断线 / LeaveGame / 被接管 / 失去归属 | 5.2 原样 | 同 5.2 |
| V10 | 号复用：P1、P2 同号同场景号 | 目录每节点号一条（P1 停发布）；资产通道 NOT_HERE 重投直到 P1 迁走或写回（X6） | 无 |
| V11 | 停服与运维疏散叠加、冲突与停服叠加 | cause 只升级不降级，deadline 取较早者 | — |
| V12 | 选中的远端频道在目标上已排空 / 销毁 | 目标按 5.1 §4.10.4 重定向到兄弟，或 3023（F10） | 79 是兄弟的号 / 23 `{3023}` 后断开 |
| V13 | 疏散期间又来了 63 / 组队跟随 / 建镜像 | §5.4.3 | — |
| V14 | P1 挂起（看不见冲突）且没有新进程占号 | 心跳 15 s 后 gate 断链 → P1 恢复后 `onLinkClosed` 写回（被夺则围栏拒）；租约键过期 → P2 判死重铺；P1 续期回 0 → 原号复占 | 23 `{1003}` 后断开 |
| V15 | 疏散中 MySQL 抖动：交出 / 探测失败、`Lost` | 已在途的按 5.2 收尾（`Lost` 踢 3023）；存储闸关上，不再冻结新的玩家，恢复后继续（§5.3） | 在途的至多 `max-in-flight` 人 23 `{3023}` 后断开；其余原地无感 |
| V16 | 选中的目标节点刚进入挂起（目录条目还没过期） | 目标拒新进场（含交出进场）→ 5.2 F10 | 23 `{3023}` 后断开（窗口 ≤ 目录 TTL 15 s） |
| V17 | SIGTERM 而 `IsolatedDubboModule` 仍注册了 Dubbo 停机钩子（H12 前置没做） | 选目标客户端被 Dubbo 钩子先销毁 → 疏散全部 Failed → 到期写回 | 与今天相同：23 `{1003}` 后断开；K10 能测出来 |
| V18 | scene 背压暂停读超过 15 s | scene 侧不计读空闲（§5.8）；gate 侧只要有下行就不判死 | 无 |
| V19 | SHUTDOWN 疏散进行中判出 TAKEN | cause 升级为 CONFLICT、deadline 取较早者；不再调 `ProcessExit`（停机已在进行） | 同 SHUTDOWN |

**幂等**：`beginNodeEvacuation` / `endNodeEvacuation` 幂等；标记写入同实例不改起点；交出、`PlayerTransfer`、`abandonEnter`、`release` 的幂等沿用 5.2 §5.12；P2b 对已是 DRAINING 的记录不动。

### 5.13 不移植的基线件（含理由）

| 基线件 | 理由 |
|---|---|
| `death_at`、`SceneReentryBarrier` 及其配置项、`ValidateSceneReentryBarrier`、`NodeDeathMarkTTL` | §4.3：MySQL 单行围栏 + 租约已经保证安全；Java 没有「跨语言镜像常数」（B9）与墙钟打点（B10）这两个隐患 |
| 推迟摘除 GO-6、本副本观察表、`IsNodeAlive` 三态 | Java 没有「摘出负载集」这个动作：选点只读带 TTL 的目录；计划写入是一段 CAS Lua，失败下一拍重来 |
| `reconcileDeadNodeScenes`、`deleteNodeCounters`、`releaseTakenOverSceneCount` | 实例随进程消失（5.3 D13）；人数由节点自报，没有账要清 |
| 世界频道同号迁移、懒改派 | 5.1 D4：换节点一律新号（E12） |
| `playerLocationOwnerDead / Gone` | login 夺权 + 位置回落已覆盖；位置记录只是建议，归属在 MySQL |
| 疏散写 handoff 标记 + `EnterScene(0,0)` + SM 铸 epoch | 5.2 D1：一笔交出事务闭合（E2） |
| 疏散拍 LOGOUT 快照 | 交出不是登出（5.2 D8，E8） |
| SM 每轮重发 `DestroyScene` 推进排空 | 5.1 D3：节点本地推进（E9） |
| 不等 30 s 提前接管死节点上的玩家 | 必须先证明老进程已不能写库（`scene-handoff-spec.md:104`），目录缺席不构成证据；收益只有十来秒（N5） |
| 冲突收尾里的 `agones::GameServerLifecycle::Instance().Stop()`（`main.cpp:250`）与 SIGTERM 停机里的号段客户端 / Agones 收尾（`main.cpp:128`、`:136`） | 不接 Agones（5.1 D14）；Java 没有号段客户端（`architecture.md` §9） |

### 5.14 线程所有权

| 线程 | 读写 |
|---|---|
| scene 逻辑线程 | `nodeEvacuation`、`acceptingEnters`、`PlayerSwitch`、场景排空标志、relocate pass、在途计数、`transferWritesPending`（写 future 在 I/O 线程完成，减计数投递回逻辑线程；投递被拒时由到期兜底）、收敛检查（100 ms 定时，完成 future）、存储闸 |
| `scene-sched` | 节点号续期与回调、目录发布（取快照经逻辑线程）、计划拉取与标记读取；**不**跑收敛看门狗 |
| 停机线程（Spring 停机钩子 / `gm-shutdown`） | SHUTDOWN 的等待：等收敛 future，每秒读一次目录 |
| `scene-conflict-exit`（一次性） | CONFLICT 收敛后调 `ProcessExit.exitSoon()` |
| scene 链路 I/O | Ping → Pong、读空闲关链路（事件投递逻辑线程；背压暂停读期间不计读空闲） |
| gate 链路 EventLoop | 心跳发送、读空闲判死 |
| `gate-link-resolve` | 建链解析、换代扫描（只改 `SceneLinkManager` 的并发表；会话状态由 `onLinkDown` 投递到会话 EventLoop） |
| scene-manager 控制面 tick | P2b、P2 佐证（快照阶段读租约键） |

阻塞 I/O（Redis / MySQL）都不在 Netty I/O 线程或场景逻辑线程上执行（AGENTS.md §3）。

### 5.15 需要同步修改的文档与登记

- **`docs/design/architecture.md`**：§4.2（`LinkHelloAck` 带实例与代次、心跳、按代次寻址、换代宽限、断链推 1003）；§4.19（跨节点改派、P2b、放开 D9、租约键佐证）；
  §6（scene 节点号租约三种结局、原号复占、挂起、目录发布围栏、scene 疏散标记）；§7（死节点不影响归属的说明）；§11（新指标）。
- **`PARITY.md`**：新增「场景排空 / 节点疏散、死节点判定与接管」一行（E1–E17）；「场景实例与主世界频道」行撤销 D9；补登 gate 链路断开即关会话（E14）；
  「mmorpg 待做（可选）」登记 §8.1 推荐项。
- **盘点**：`scene-core.md:256-266`、`scene-manager-match.md:154-164`、`gate.md:337-347` 的 java 列；勘误见 §8.3。
- **规格**：`scene-channels-spec.md:85`、`:777`、`:864`；`scene-handoff-spec.md:104`、`:973`，以及 5.2 F7 / F11 的客户端列（「关会话」→「推 23 `{1003}` 后关会话」，E14）；
  `battle-node-spec.md` §7.10 的「尽力删除目录条目」（§8.3）。
- **路线图**：`docs/porting/roadmap.md:75` 状态列。
- **工具**：`tools/local/stop-slice.sh` 等待 50 s（C2）；`tools/local/start-slice.sh` 支持 `XM_SCENE_NODES=3`（§11.4）。
- **配置注释**：`xm-scene/src/main/resources/application.yaml:21` 改为「`SceneNode` 是同步停机，本时限约束不了停服写回；上界是外部强杀期限」（G16）。
- **代码前置**：`xm-api` 的 `IsolatedDubboModule` 关 Dubbo 停机钩子（H12），单独提交、两版各自登记（mmorpg 无对应物）。

---

## 6 配置与常量

### 6.1 基线

| 常量 | 值 | 出处 |
|---|---|---|
| etcd `NodeTTLSeconds` / `HealthCheckInterval` | 180 s / 1 s | barrier.md:26-27（`bin/` 缺失） |
| `kDrainBudget`（冲突疏散） | 15 s，0.1 s 轮询 | `node.cpp:882-883` |
| `kShutdownDrainBudget` | 15 s | `node.cpp:52` |
| 再入屏障 / 钟差 / `death_at` TTL | 20 s / 5 s / 10 min | `constants/reentry_barrier.go:51-80` |
| handoff 标记 TTL | 300 s | `player_ownership_comp.h:74` |
| gate 进场转发 | 发现 3 s、退避 250 ms–2 s、≤16 次、总 20 s | `scene_route_helper.h:28-41` |
| 缩容检查 / 冷却 | 30 s / 120 s | `world_autoscale.go`（§1.5） |
| 普通停机等事件循环退出 | 20 s（`kShutdownCompletionWaitTimeout`） | `node.cpp:53`、`:1079-1084` |
| gate 进场转发放弃后强关 | 1 s（`kGiveUpForceCloseDelaySeconds`） | `scene_entry_dispatch.h:37` |
| SM `EnterScene` 的 request_id 去重 | 60 s SETNX（改派刻意不带 request_id） | `player_lifecycle.cpp:2351-2355` |

### 6.2 Java 新增 / 改动

| 键 | 缺省 | 校验 | 模块 |
|---|---|---|---|
| `xm.scene.evacuation.max-in-flight` | 32 | [1, 256] | xm-scene |
| `xm.scene.evacuation.retry-backoff-initial` / `-max` | 1 s / 30 s | initial ∈ [100 ms, max] | xm-scene |
| `xm.scene.evacuation.budget` | 15 s | (0, 60 s]；SHUTDOWN 与 CONFLICT 共用 | xm-scene |
| `xm.scene.shutdown.evacuate-first` | true | — | xm-scene |
| `spring.lifecycle.timeout-per-shutdown-phase`（scene） | 不改（30 s）；只更正注释 | 对同步 `stop()` 不起约束（G16、C2） | xm-scene |
| `dubbo.shutdownHook.listenIgnore`（`IsolatedDubboModule` 的应用环境，代码里写死） | true | — | xm-api（H12） |
| `xm.gate.link-ping-interval` | 5 s | [1 s, 30 s] | xm-gate |
| `xm.gate.link-idle-timeout` | 15 s | ≥ 3 × ping-interval（C3） | xm-gate |
| `xm.gate.link-supersede-check-interval` | 5 s | [1 s, 60 s] | xm-gate |
| `xm.gate.link-superseded-grace` | 30 s | ≥ 20 s（C4） | xm-gate |
| `xm.scene-manager.world.cross-node-relocation` | true | — | xm-scene-manager |
| `xm.scene-manager.world.stuck-node-grace` | 120 s | > `dead-node-grace`（C5） | xm-scene-manager |
| `xm.data.scene-evacuate.min-ttl` | 30 min | — | xm-data |

`NodeIdLease` 的 TTL（15 s）、目录发布周期 / TTL（5 s / 15 s）、`dead-node-grace`（20 s）、归属租约（30 s）、续约周期（10 s）、`transfer-lease-margin`（15 s）、
`switch-resolve-timeout`（4 s）、`shutdown-save-timeout`（15 s）都不变。

### 6.3 内部契约变更（Java 自有 proto，不是同步产物）

| 文件 | 变更 |
|---|---|
| `xm-api/.../node_directory.proto` `SceneNodeInfo` | `uint64 lease_epoch = 11`（本节点号租约的当前防护代次；0 = 旧版本）、`bool evacuating = 12`（节点级疏散中：不接新频道、不接新实例；全部场景同时带 `draining`） |
| `xm-api/.../scene_directory.proto` `SelectSwitchTargetRequest` | `SwitchReason reason = 7`（`SWITCH_REASON_PLAYER = 0`、`SWITCH_REASON_EVACUATE = 1`）、`bool exclude_from_node = 8`、`uint32 fallback_scene_config_id = 9` |
| `xm-api/.../world_channel.proto` `DrainReason` | `DRAIN_EVACUATE = 4` |
| `xm-api/.../node_link.proto` | `NodeLinkFrame`：`LinkPing ping = 10`、`LinkPong pong = 11`；`LinkHello.ping_interval_ms = 7`；`LinkHelloAck.scene_lease_epoch = 6`、`ping_interval_ms = 7`。`PlayerTransfer` 不变 |
| `xm-discovery` `RedisKeys` | `sceneEvacuating(zone, node)` = `xm:scene-evacuating:<zone>:<node>` |
| `xm-discovery` `NodeIdLease` | 三值续期 Lua；`reacquireSameId` 模式；带原因的监听器（`onSuspended` / `onResumed` / `onRegained(epoch)` / `onTaken`）；旧的 `Runnable onLost` 构造保留给 gate / login |

兼容：所有新字段缺省值都等于旧行为；滚动升级先升 scene-manager（认 `reason` / `exclude_from_node` / `fallback`），再升 scene，再升 gate（5.3 已要求先升 scene-manager）。

### 6.4 常数关系与启动校验（修 G12）

| # | 关系 | 守卫 |
|---|---|---|
| C1 | `OWNER_LEASE − OwnerLeaseRenewer.PERIOD − owner-claim-wait`（30 − 10 − 3 = 17 s）> `SceneDirectoryPublisher.TTL`（15 s）：能夺到权的那次 assign 时死节点已不在目录 | xm-scene 单测（login 的 3 s 作常量入参）；只关可用性 |
| C2 | 外部强杀期限（stop-slice 等待、K8s `terminationGracePeriodSeconds`）≥ `evacuation.budget + shutdown-save-timeout + audit-flush-timeout + 10 s`（逻辑线程停止 ≤ 5 s、回写池 ≤ 2 s、其余 Bean 与 Dubbo 反导出约 3 s）= 45 s；本稿取 stop-slice 50 s、K8s ≥ 60 s | 跨进程，不能机械校验：xm-scene 启动时把算出的下限打一行 INFO（「外部强杀期限须 ≥ N s」），7.6 的部署清单引用它；**不**用 `timeout-per-shutdown-phase` 做守卫（G16） |
| C3 | `link-idle-timeout ≥ 3 × link-ping-interval` | xm-gate 启动校验 |
| C4 | `link-superseded-grace ≥ 20 s`；文档要求 ≥ scene 的 `evacuation.budget + shutdown-save-timeout`（跨进程，不能机械校验） | xm-gate 启动校验 + 注释 |
| C5 | `stuck-node-grace > dead-node-grace ≥ SceneDirectoryPublisher.PERIOD` | xm-scene-manager 启动校验 |
| C6 | 5.2 已有：`transfer-lease-margin` ∈ (续约周期, 租约 − 续约周期) | 现有 |
| C7 | `retry-backoff-initial ≤ retry-backoff-max` | xm-scene 启动校验 |

预算不是安全常数：基线的 Go 屏障必须镜像 C++ 的 15 s，因为老节点停笔之前新节点不能接管（`constants/reentry_barrier.go:36-51`）；Java 里老进程迟到的写都被 epoch 围栏拒掉，
单人一次疏散的最坏用时（4 s 选目标 + 15 s 交出与探测）超过预算也只影响体验，到期的人照常写回。

---

## 7 指标

### 7.1 基线

`reentry_barrier_blocked_total{zone,site}`、`enter_scene_owner_dead_takeover_total`、`world_autoscale{action,result}`；改派本身只有日志（`[EmergencyRelocate]`、`[SceneDrain]`）
与 `exit_persist_stats`（`scene_handler.cpp:503`）。

### 7.2 Java（Micrometer；不带 zone、节点号、场景实例号、player_id 标签，AGENTS.md §5）

| 进程 | 指标 | 标签 / 说明 |
|---|---|---|
| scene | `xm.scene.channel.relocations` | `result` 在 5.1 的 same_map / default_world / blocked / switching 之上新增 remote_same_map、remote_default_world、deferred、deferred_cap、deferred_storage、skipped_battle、cancelled |
| scene | `xm.scene.switch.resolves`、`xm.scene.transfers`、`xm.scene.transfer.freeze`（5.2） | 加 `reason = player / travel / evacuate`（travel 由 5.4 加，`zone-travel-spec.md:898`） |
| scene | `xm.scene.evacuations.in.flight`（gauge） | 在途 EVACUATE |
| scene | `xm.scene.node.evacuations` | `trigger = admin / shutdown / conflict`，`result = drained / budget_exceeded / no_target / revoked` |
| scene | `xm.scene.node.evacuation.seconds`（timer） | 从开始到结束 |
| scene | `xm.scene.node.evacuating`（gauge 0 / 1） | — |
| scene | `xm.scene.node.identity`（gauge） | 0 VALID / 1 SUSPENDED / 2 TAKEN |
| scene | `xm.scene.link.idle.closes` | scene 侧读空闲关链路次数（背压暂停读期间被忽略的读空闲事件另计 `xm.scene.link.idle.ignored`，用来核对 §5.8 的豁免没有被滥用） |
| scene-manager | `xm.scene_manager.switch` | 加 `reason` |
| scene-manager | `xm.scene_manager.world.nodes`（gauge，只由领导者维护） | `state = present / absent / stuck / evacuating` |
| scene-manager | `xm.scene_manager.world.node.restarts` / `xm.scene_manager.world.node.regained` | 实例变化 / 只有代次变化（§5.6.7） |
| scene-manager | `xm.scene_manager.world.autoscale` | 已有；跨节点缩容同样计 `scale_in/ok` |
| gate | `xm.gate.scene.links.superseded` | — |
| gate | `xm.gate.link.events` | `event` 加 `idle_timeout`、`superseded`、`peer_mismatch` |
| gate | `xm.gate.disconnects` | `reason` 加 `scene_link_superseded` |
| gate | `xm.gate.link.dropped` | `reason` 加 `gen_gone`（按代次找不到链路） |

停服 / 冲突收尾各打一行 INFO：疏散用时、迁走人数、剩余人数、原因。

### 7.3 告警（随 7.6）

`SceneNodeIdentityTaken`（identity = 2）、`SceneNodeSuspended`（identity = 1 持续 > 30 s）、`SceneEvacuationBudgetExceeded`、`WorldNodeStuck`、
`RelocationDeferredRate` 偏高、`RelocationStorageGateClosed`（`relocations{deferred_storage}` 持续增长）、`SceneLinkIdleTimeouts` 突增、`TransferPostFreezeMutations > 0`（5.2 已有）。

---

## 8 隐患与边界

### 8.1 基线隐患汇总（建议进 PARITY「mmorpg 待做（可选）」）

| # | 隐患 | 推荐 |
|---|---|---|
| B1 | 改派失败没有收尾、会话变哑 | **登记**（客户端可见） |
| B2 | 疏散期间没有准入闸（推导） | 登记 |
| B3 | 断线中玩家被抄票据（推导） | 登记 |
| B4 | 无批量、预算未压测 | 不登记（同类性能项，随压测） |
| B5 | 号被抢后被疏散的玩家可能被分到同号新进程（推导） | 登记 |
| B6 | `DestroyScene` 注释不准 | 不登记（无害） |
| B7 | 同节点落点的多余重载 | 不登记 |
| B8 | 再入屏障前提过时（注释与设计稿也过时） | **登记**：更新注释与设计稿，重新评估屏障在分区情形下的意义 |
| B9 | `CppNodeDrainBudget` 跨语言手工镜像 | 随 B8 |
| B10 | `death_at` 墙钟 | 随 B8 |
| B11 | 崩溃后 ≤180 s 静默丢弃 | 登记 |
| B12 | 摘除后哑连接（1003） | **登记**（客户端可见） |
| B13 | 收尾 / 推迟队列只在进程内 | 不登记（方向安全） |
| B14 | scene 不处理 gate 消失 | **登记** |

### 8.2 Java 残余风险

- **R1**：P2 占号之后、gate 下一次换代扫描之前（≤ 5 s），经旧链路发往 N 的新进场仍落到 P1：P1 判 TAKEN 之后回 3023，判之前会接下来、随后被疏散走。数据安全，客户端重试即可。
- **R2**：P1 只与 Redis 断开且一直没有新进程占 N：P1 挂起、继续服务已有玩家（归属有效；拒新进场；readiness DOWN；告警）。期间它名下的频道会被 P2 判死重铺，恢复后本地成孤儿再改派。
- **R3**：冲突 / 停服预算到期时仍有未冲刷的 `PlayerTransfer`：E+1 无人持有，至多 30 s 后才能再进；数据完整（冻结快照）。与 5.2 R-J1 同级。
- **R4**：目录（Redisson `RMapCache`）条目的 TTL 按写入方 / 读取方墙钟计算，节点间钟差影响「缺席」判定；与现有「部署要求 NTP」同一前提（`architecture.md:666`）。
- **R5**：疏散中的玩家冻结期间（≤ 约 15 s）写请求被拒（1005 / 27003）；与 5.2 玩家主动换图同口径。
- **R6**：hash 覆盖下运维疏散一台节点时，P2b 补建的新频道要等领导者一拍（5 s）+ 拉取（1 s）才建出；这期间同图远端可能没有频道，玩家先落默认大世界（`fallback`）。
  per-node 覆盖下别的节点本来就有同图频道，不受影响。
- **R7**：5.5 与 5.2、5.3 改同一组文件（`SceneWorld`、`SceneNode`、`ClientRequestHandler`、`SwitchTargetSelector`、`SceneMetrics`），须在 5.2 提交之后开工、重新取行号；
  `NodeIdLease`（xm-discovery）同时被 6.2 battle 节点使用，改它的那次提交要连 `xm-battle` 一起编译、跑测试（缺省模式回归）。
- **R8**：gate 关会话前推的 23 `{1003}` 在写缓冲已满的连接上可能发不出去（`sendThenClose` 尽力而为），客户端只看到断线；与现有踢人同口径。
- **R9**：目录发布的租约围栏只收窄 X4（§5.5.4）：Redisson 内部重试的迟到写可能把新进程的条目盖回旧的，至多到新进程下一次发布（≤ 5 s）；gate 按「见过的最大代次」忽略它，
  scene-manager 只受一拍延迟影响。数据安全不受影响。
- **R10**：滚动重启 N 台 scene 且 `evacuate-first` 开时，一个玩家最坏被连续迁 N − 1 次（每次一次冻结 + 一次加载）。缓解靠运维顺序（先对整批打 ADMIN 标记、等 `evacuated`
  再逐台停），或 7.6 的编排先起新实例再停旧实例；本批不做「疏散目标避开即将停机的节点」。

### 8.3 勘误

| 位置 | 原文 | 实际 |
|---|---|---|
| `scene-channels-spec.md:85`、`:777` | 同节点改派「抽成 `ChannelEvacuator` 接口」 | 全仓没有这个符号，改派在 `SceneWorld.drainStep` / `relocateResidents` / `relocationTarget`；5.5 也不抽（只有一个实现），删掉这句 |
| `scene-channels-spec.md:864` | 「gate 按租约代次只连新进程（`architecture.md:119-121`）」 | 那段讲 scene 拒绝**低代次的 gate**；gate 不核对 scene 的身份（`SceneLink.java:196-232`）。5.5 §5.7 补上 |
| `scene-handoff-spec.md:104`；`dungeon-mirror-spec.md:118`、`:120`、`:810` | `SceneTransfers.begin(…, Reason.EVACUATE, playerRequested = false)` | 只是规格里的名字；实现是推广后的 `SceneWorld.beginRemoteSwitch(player, want, reason)`，`playerRequested ≡ reason != EVACUATE`（5.4 的 TRAVEL 也是玩家发起） |
| `scene-handoff-spec.md:973` | 「S 节点号租约丢失：只停接客，对外交出不受影响」 | 5.5 起按原因分流：挂起 / 复占 / TAKEN 疏散后退出（§5.5） |
| `scene-core.md:263`（scene-drain-relocate java 列） | 「无运行期销毁场景，也无改派」 | 5.1 已有运行期建 / 排空 / 销毁与同节点改派；「丢节点号租约时只 `stopAcceptingEnters`」在 5.5 之前仍准确 |
| `scene-core.md:261`（behavior） | 「节点身份冲突（etcd 租约丢失）」 | 自 2026-09-08 起只有「号被别的 uuid 占用」触发疏散，租约过期只重注册（§2.6） |
| `gate.md:342`（scene-node-loss-handling behavior） | 「直到 scene_manager 的 node_gone 改派经 RoutePlayer 把会话重绑」 | `node_gone` 只迁频道映射并 CreateScene，不路由任何玩家（`world_rebalance.go:168-193`、`:207-306`）；重绑只发生在之后的某次 RoutePlayer 上，实际是客户端重登或失去身份时的疏散。另漏了 DELETE 之前约 180 s 的静默丢弃（`pending_scene_entry_comp.h:33-35`） |
| `scene-manager-match.md:159`（sm-dead-node-recovery behavior） | 屏障 = 「C++ drain 15s + 余量」，暗含「C++ 丢租约后 15 s drain」 | 前提已过时（§2.6）；另漏了推迟摘除的细节：期间节点留在负载集按存活处理、满一个屏障才不带标记摘除、10 min 后放弃，以及本副本观察表 |
| 基线 `main.cpp:239`、`node.cpp:839-840`、`constants/reentry_barrier.go:13-27`、barrier.md:18 | 「租约到期 → `BeginEmergencyRelocateAll` → 15 s drain」/「四个触发点」 | 与 `node.h:35-43`、`etcd_service.cpp:598-607`、`node.cpp:1531-1545` 不符（§8.1 B8） |
| `scene-handoff-spec.md` §5.10 F7、F11 的客户端列 | 「gate 同时收到链路断开、关会话」/「链路断开即关会话」 | 5.5 起先推 23 `{1003}` 再关（E14） |
| `battle-node-spec.md:1165`（§7.10 租约丢失第 2 条） | 「停止发布并尽力删除目录条目」 | 丢号之后号可能已归新持有者，条目键相同，删了会误删对方刚发布的条目（同 `GateNode.java:270` 的方法注释、本稿 H9 / X4）；建议 6.2 改为只停发布、不删，或只在租约仍 `isValid()` 时删 |
| `xm-scene/src/main/resources/application.yaml:21` | 「停服写回（xm.scene.shutdown-save-timeout）要在这个上限之内完成」 | `SceneNode` 只实现同步 `stop()`，该时限约束不了它（G16） |
| 分区稿 ① 对 `scene-channels-spec.md:375` 的勘误 | 「`:726-730` 应为 `:728-730`」 | 撤销：`:726` 起注释、`:729` 扣人数、`:730` 收尾，5.1 原文无误 |

---

## 9 建议的有意差异

### 9.1 建议采纳

| 编号 | 差异 | 理由 | 客户端可见 | 两版同改 |
|---|---|---|---|---|
| E1 | 改派去向同图优先：本节点同图 → 别的节点同图 → 本节点默认大世界 → 别的节点默认大世界；基线一律默认大世界 `EnterScene(0,0)` | 同图打扰最小、保留坐标；延续 5.1 D8；基线自己的设计意图也是同图（5.1 B1） | **是**：落点、坐标保留、不换图 | mmorpg 可选（同 5.1 B1） |
| E2 | 跨节点改派走 5.2 原子交出（不写 handoff 标记、不发 `EnterScene(0,0)`、SM 不铸 epoch）；同节点不存盘 | 数据与归属同行，一笔事务闭合（5.2 D1） | **是**：窗口内的拒绝按 `FreezePolicy`，不再一律应答内 1006；没有「静默丢弃」段 | 否 |
| E3 | 每个失败出口都确定：交出前失败原地留下、不推 tip、按玩家退避；失去归属 2017 / 结局不明 3023 / 交出后失败 3023 均推后断开；基线改派失败变哑会话（B1） | 玩家不能丢；玩家没有发起操作，原地留下即可 | **是**（只在失败路径） | mmorpg 可选（B1） |
| E4 | 新增节点级疏散的两个触发：运维主动疏散、停服前先疏散（缺省开）；基线只有冲突疏散、停服只存盘 | 计划内缩容 / 滚动重启不断线（同 gate 排空的动机） | **是**：收到非自己发起的 79 而不是断线 | 否 |
| E5 | 每节点在途上限 + 按玩家退避 + 存储闸（库抖动时暂停发起新的疏散冻结）；基线一次性全部、不重试 | 冻结窗口与存储池排队有界；不形成重试风暴（B4）；不因库抖动成批踢人 | 否 | 否 |
| E6 | 疏散中的节点拒绝一切新进场（含交出进场），目录立即带 `evacuating` 与全部 `draining`；基线疏散期间无准入闸（B2） | 防来回弹、防疏散后又进新人 | 罕见：刚进疏散瞬间被选中的交出进场 → 23 `{3023}` 后断开 | mmorpg 可选（B2） |
| E7 | 节点号丢失区分原因：失联 → 挂起、恢复后续上或原号复占，不疏散；号归别人 → 疏散 15 s 后退出 | 对齐基线 2026-09-08 起「失租重注册、CAS 失败才冲突」；Redis 抖动不能让全区停摆；同号新进程必须让位 | 否（挂起期间新进场回 3023） | 否 |
| E8 | 疏散不拍 LOGOUT / LOGIN 快照（同 5.2 D8）；基线疏散走 `HandleExitGameNode`，拍 LOGOUT（`player_lifecycle.cpp:1988`） | 交出不是登出 | 否（运维可见：快照少一对） | 否 |
| E9 | 排空在节点本地推进，SM 不重发 `DestroyScene`（沿用 5.1 D3） | 没有「应答分不清排空中」（5.1 B10）、没有每 30 s 一发的 RPC | 否 | 否 |
| E10 | hash 覆盖下撤销 5.1 D9 的「必须有同节点兄弟」；余量按改派实际落点算（有同节点兄弟 → 只算它们；没有 → 别的节点该图的频道）；per-node 覆盖下仍不缩某节点某图的最后一个频道，规则与 5.1 相同 | 有了跨节点改派，D9 的前提在 hash 下不再成立；余量口径跟着落点走，才不会把同节点兄弟推过扩容线、重现基线 `hasHeadroomFor` 要防的振荡 | 否 | 否（没有同节点兄弟时回到基线的全图口径） |
| E11 | 不移植 `death_at`、再入屏障、推迟摘除、本副本观察、`playerLocationOwnerDead / Gone`、死节点实例收尾；死节点上的玩家等 MySQL 租约（30 s）过期后由 login 夺权 | 归属与数据同行、所有写带 epoch 围栏（§4.3） | **是**：最早可重进从约 200 s 变为 ≤ 约 33 s；等待期间回 2005 而不是 23 `{3023}`（`PARITY.md:37` 已登记） | 否 |
| E12 | 死节点上的世界频道用新号重铺，不同号迁移（5.1 D4、D12） | 同号迁移有双活窗口 | **是**：重进后 79 里的 scene_id 不同 | 否 |
| E13 | 死节点分层判定：分配看目录与自报状态；容量重铺还要求节点号租约键无人持有（卡住的节点最多宽限 120 s） | 双信号，不把卡顿当死亡 | 否 | 否 |
| E14 | scene 链路断开时 gate 推 23 `{1003}` 后断开连接；基线解绑、连接保持、此后每条 scene 请求回 1003。**补登 PARITY** | Java 没有改绑来源，哑连接没有用（B12） | **是** | mmorpg 可选（B12） |
| E15 | gate↔scene 链路心跳 + 两侧读空闲（15 s） | 基线要等 etcd TTL 180 s（B11）；scene 侧补 B14 | **是**：断开更快、幽灵角色更早从视野消失 | mmorpg 可选（B14） |
| E16 | gate 按 scene 实例与租约代次做链路围栏：握手比对、5 s 换代扫描、按代次寻址、30 s 宽限后断开旧链路 | 基线靠实体号区分新旧进程；Java 链路按节点号复用（H7） | 否（只有宽限到期仍留在旧进程上的玩家会被断开） | 否 |
| E17 | 改派选目标时排除整个源节点号 | 号复用时同号新进程也在目录里，gate 发往该号的帧可能还走旧链路（B5） | 否 | mmorpg 可选（B5） |

### 9.2 列出但首批不做

- **N1** SM 批量选目标 RPC（一次给多名疏散玩家选目标）：逐人调用的 QPS 很低，压测有需要再做。
- **N2** 按队伍聚合疏散目标：两版跨节点跟随都不做（4.3；基线 DV-6，`player_lifecycle.cpp:3925-3937`），疏散后队伍可能分散到不同节点 / 频道，基线每人各自 `EnterScene(0,0)` 同样分散。
- **N3** 疏散中的节点在短宽限内接收已在途的交出进场、再二次疏散（用来取代 E6 的 3023）：窗口 ≤ 1 s，收益小。
- **N4** 「这一次停机先疏散」的独立 GM 方法 `Scene.GmEvacuateShutdown`（签名 canonical 带方法名，旧签名不能重放）：`evacuate-first` 缺省开后不需要。
- **N5** 不等 30 s 提前接管死节点上的玩家：要在 MySQL 侧加一层节点围栏证明老进程已不能写库，代价与改动面都不值。
- **N6** gate 的进场结果超时（链路活着、scene 不回结果）：与本批的死节点 / 链路丢失无关。
- **N7** scene 归属续约连续失败超过租约时自我围栏（移除实例并踢人）：现状是续约恢复后才识别失去归属并移除（`OwnerLeaseRenewer` 类注释），见 Q15。

---

## 10 开放问题（各带推荐答案）

| # | 问题 | 推荐 |
|---|---|---|
| Q1 | 停服 / GM 停机前是否先疏散？缺省开还是关？ | **做，缺省开**；预算 15 s；目录里没有别的接客节点就跳过、中途变成没有就提前结束；同批做 H12 前置（关 Dubbo 停机钩子）、`stop-slice.sh` 等待改 50 s，K8s 宽限 ≥ 60 s 记入 7.6（Spring 停机阶段时限约束不了同步 `stop()`，不改，G16）。切片 K10 不过就把缺省翻成关 |
| Q2 | 号被别的实例占用后：疏散并退出（推荐），还是保持现状「服务到离开」？ | 疏散 15 s 后退出：对齐基线，解决同号双进程与 gate 旧链路；进程管理器重启后拿到新号 |
| Q3 | 「失联挂起 + 原号复占」是否同批用于 gate / login 的 `NodeIdLease`？ | 不同批：gate 的会话号高位与节点号绑定、`onLeaseLost` 要关全部会话，语义需要单独评审，另起一项 |
| Q4 | gate 的号复用围栏：按代次寻址 + 30 s 宽限（推荐），还是一发现换代就立刻断旧链路？ | 按代次寻址 + 宽限：立刻断开会让旧进程上的玩家马上断线，自疏散失去意义 |
| Q5 | 链路断开时先推 23 `{1003}` 再关（推荐），还是维持直接关？ | 先推 1003：与基线首个请求得到的码相同，又不挂一条哑连接 |
| Q6 | 心跳 5 s、读空闲 15 s、换代扫描 5 s、宽限 30 s 是否合适？宽限要不要与 `dead-node-grace` 合并成一个配置？ | 保持这组值；不合并（一个是 gate 侧链路寿命，一个是规划器容量判定，含义不同） |
| Q7 | 运维疏散清空后是否自动停节点？ | 不停，只在 `GET` 里报 `evacuated`（同 gate 排空「只判定、不踢人」） |
| Q8 | EVACUATE 结局不明（Lost）时：推 3023 后断开，还是静默断开？ | 推 3023（同 5.2 F6，客户端知道原因） |
| Q9 | 疏散节点名下的频道：P2b 转 `DRAINING(EVACUATE)` + 同批补建（推荐），还是不计入期望与覆盖、记录原样（改 `NodeAvailability` 的 5.1 约定）？ | P2b（裁决 A2） |
| Q10 | 5.5 是否放宽 5.1 D9、保留 D10？ | hash 覆盖下放宽 D9 的「同节点兄弟」（带回滚开关），余量按改派落点算；per-node 不变；保留 D10，但疏散节点上的 SCALE_IN 记录超时转 `DRAIN_EVACUATE`、不回滚 |
| Q11 | `evacuation.max-in-flight` 缺省值 | 先用 32，§11.5 压测后定 |
| Q12 | `PlayerTransfer` 要不要带原因？ | 不带：gate 处理完全相同（YAGNI） |
| Q13 | 同队、同场景的队员是否尽量疏散到同一目标？是否加批量选目标接口？ | 首批不做（N1、N2） |
| Q14 | `stuck-node-grace` 120 s 是否合适？ | 合适：远大于目录 TTL 与 GC 停顿，又能在真正卡死的节点上 2 分钟内恢复容量 |
| Q15 | scene 归属续约连续失败超过租约时是否自我围栏？ | 维持现状（N7），另起评审 |
| Q16 | 战斗冻结中的玩家阻塞疏散：冲突 / 停服到期就写回、结算由 6.3 的 outbox 落到下一个持有者，可以接受吗？ | 接受；6.3 定细节。运维疏散没有预算，等战斗结束再迁 |
| Q17 | 本机切片扩到 `XM_SCENE_NODES=3` 并加备用实例开关（号复用测试用）？ | 做：kill -9 一台之后还要留两台才能测「疏散目标也在排空」与交出 |
| Q18 | PARITY「mmorpg 待做（可选）」登记哪些基线隐患？ | §8.1 标「登记」的：B1、B2、B3、B5、B8（含 B9、B10）、B11、B12、B14 |

---

## 11 测试计划

### 11.1 基线测试对照

| 基线用例 | 验证的性质 | Java 对应 |
|---|---|---|
| `world_autoscale_test.go:108` DrainsUnderpopulatedChannel、`:164` RefusesDrainWithoutHeadroom、`:192` DrainingChannelWithResidentsStaysDraining、`:210` DrainedChannelIsCleanedUp | 缩容牺牲者与余量 | `WorldAutoscalerTest` 在新余量规则下（E10）：hash 下无同节点兄弟、别的节点该图有余量 → 可缩；有同节点兄弟时只按同节点兄弟算余量（全图有余量但同节点兄弟会被推过扩容线 → 拒）；余量不足 → 拒；per-node 不缩最后一个、行为与 5.1 逐条相同；开关关闭时现有 `WorldAutoscalerTest` 全部用例（当前 23 个 `@Test`）回归 |
| `orphan_cleanup_autoscale_test.go:53` | 孤儿图 | relocate pass：孤儿图跳过同图步骤，本节点 → 远端默认大世界 |
| `owner_epoch_test.go:2537` DeadOwnerWithinBarrierIsStillRejected、`:2449` AfterBarrierIsTakenOverWithoutMarker | 屏障内不接管、屏障后接管 | 归属租约：持有者停止续约后 20–30 s 才能夺权（`PlayerStoreSqlTest` 形状，补「续约停止 → 夺权时刻」用例） |
| `owner_epoch_test.go:2599`、`:2617`、`:2511`（RequiresSyncedRegistry / FailsClosedOnAmbiguousIdentity / HonoursLocallyObservedDisappearance） | 死亡要正面证据 | 规划器 P2 租约键佐证矩阵（§11.2） |
| `node_detach_deferred_test.go:223-600` | `death_at` 先于摘除 | 不适用（E11）；对应租约键佐证 |
| `logic_test.go:2847` 等死节点收尾 | 只销副本 | 不适用（5.3 D13）；规划器只删记录、重铺容量 |
| `logic_test.go:878` StaleLocationCheckWaitsForReentryBarrier | 陈旧位置 + 屏障 | 不适用 |
| `cross_zone_test.cpp:1161` ExitReleaseDecision.ConsumedRelocateTicketDoesNotWrite | 疏散票据与 A1′ 互斥 | 不适用：Java 没有 A1′（断线即写回释放），记 N/A |
| `routing_identity_test.cpp:544-557` | 节点摘除后重新绑定算「换了节点」 | gate 按代次寻址（§11.2 gate） |
| `BeginSceneDrain` / `BeginEmergencyRelocateAll` | **无单测、无 robot** | §11.2–§11.6 全部新增 |

### 11.2 单测（缺省跑，无外部依赖）

**xm-discovery**
- `NodeIdLeaseTest`（假 Redis 门面 + 可控时钟）：续期 1 / 0 / −1 → VALID / 原号复占（代次 +1、`onRegained`）/ `onTaken`；复占失败 → `onTaken`；出错 ≥ TTL 在 `reacquireSameId`
  模式下 `onSuspended` 一次、恢复后 `onResumed`；缺省模式行为不变（gate / login / battle 等用 `Runnable onLost` 构造的调用方回归）；`isValid` 在挂起与 TAKEN 后为假；
  复占时 INCR 失败 → 放掉刚占的键、保持挂起、代次不变（不带旧代次复占）。

**xm-api**
- `IsolatedDubboModuleTest`：建出的应用模型环境里 `dubbo.shutdownHook.listenIgnore = true`；起一个直连引用后 JVM 里没有新增 `DubboShutdownHook`
  （`Runtime.removeShutdownHook` 探测或反射读 `DubboShutdownHook.registered`），H12 回归。
- `SceneEvacuationMarksTest`：标记值格式、实例比对、TTL 校验。

**xm-scene**（`SceneWorldTest` / `HandOffTransferTest` / `RemoteSwitchTest` 写法；`FakeSwitchTargets`、`FakePlayerRepository`、`RecordingSink`）
- `RelocationOrderTest`：去向顺序 本节点同图 → 远端同图（请求带 `exclude_from_node`、`fallback` 只在本节点没有默认大世界时带）→ 本节点默认大世界（远端 Refused / Failed 后）→
  远端默认大世界 → blocked；孤儿图跳过同图；跨节点没装配时退回 5.1 顺序；5.3 镜像 → 同图、副本 → 默认大世界。
- `ServerRelocationTest`：不回应答；Failed / Refused / LeaseTooShort / NotCommitted 都不推 tip（sink 里没有 23）、写下退避、退避期内不重试、过期后重试；Fenced → 2017；Lost → 3023；
  结果回来时场景已不在排空 → 取消、不冻结；`Chosen` 在本节点 → 按 Refused；`link_gone` 与 `pendingAbort`（LEAVE、TAKEOVER）与 5.2 相同；指标带 `reason=evacuate`；成功帧：旧旁人 51、
  sink 收到 `PlayerTransfer`、没有 23。
- `RelocationConcurrencyTest`：N 人、上限 k → 恰好 k 个进 RESOLVING；一个结局回来立刻补位；顺序按 (scene_id, player_id)；有自己的 `PlayerSwitch`（任何 reason、任何阶段，含 5.4 TRAVEL 的 PRECHECK）和战斗冻结桩的玩家跳过；
  存储闸：一次 `Lost` 后 30 s 内不再发起新的 EVACUATE（计 `deferred_storage`）、在途的照常走完；`FakePlayerRepository` 报告队列过半时同样关闸；关闸不影响玩家自己的 63；
  EVACUATE 在途时玩家自己发 63 → 3014。
- `NodeEvacuationTest`：进入后 `sceneEntries` 全部 `draining`、`evacuating` 为真；登录 / 重连 / 交出进场都 3023；加载中的进场被拒并释放；63 显式本地号 → 3023、只带地图 → Remote 且排除本节点、
  自己的换图在途 → 3014；组队跟随跳过；建镜像被拒；疏散期间 `applyChannelPlan` 不新建、不改回；收敛谓词含 `transferWritesPending` 与 5.4 的 `travelFramesPending`；疏散占槽时 226 回 13000 / 3014；ADMIN 撤销恢复接客与目录标志、重应用计划、
  在途的照常结束；cause 只升级不降级；随后 `shutdown()` 写回剩余、FREEZING 的标 `detach`。
- `SceneNodeLifecycleTest`：SHUTDOWN 在预算内疏散完才 `release()`；疏散期间续约器、接管订阅、链路、目录发布都在运行；预算用尽后剩余照现有流程写回；目录里没有别的接客节点时跳过、
  中途变成没有时提前结束；CONFLICT：`onTaken` → 停计划与发布（不删条目）→ 疏散 → 逻辑线程上的收敛检查完成 future → `ProcessExit` 在独立线程上被调、`scene-sched`
  上没有任何 `callOnLogic` 轮询；SHUTDOWN 中判 TAKEN → cause 升级、不调 `ProcessExit`；挂起 → 停发布、拒新进场、在场照常 → 恢复后发布与拉计划、恢复接客。
- `SceneDirectoryPublisherTest`：`isValid()` 为假时不发布、`stop(true)` 不删条目；发布带 `lease_epoch`；`evacuating` 与场景列表来自同一份快照。
- `NodeLinkHandlerTest`：Ping 在 I/O 线程直接回 Pong（逻辑线程阻塞但未触发背压时照样回）；`ping_interval_ms ≠ 0` 时读空闲关链路 → `onLinkClosed`；= 0 或超出 [1000, 30000] 时不判；
  **背压暂停读（pending ≥ 上限）期间读空闲到点不关链路**（计 `idle.ignored`），恢复读后重新计时。
- 常数守卫：C1、C7 各一条断言，违反时启动失败（`SceneNodePropertiesTest` 写法）；C2 只断言启动日志里打出的「外部强杀期限下限」等于公式结果。

**xm-scene-manager**
- `SwitchTargetSelectorTest`：`exclude_from_node` 排除源节点上的全部频道（含显式场景号 → NOT_FOUND）；EVACUATE 带显式场景号 → BAD_REQUEST；want 选不到时回落 fallback，回落也写软预占；
  都没有 → NOT_FOUND；疏散节点的场景（全部 `draining`）不被选中；`reason` 标签只有两个取值。
- `WorldChannelPlannerTest`：P2 佐证矩阵（缺席 < 宽限；≥ 宽限且键不在 → 删；键归同实例 / 别的实例 → 保留到 `stuck-node-grace` 后删；读键失败按有持有者）；P2b（`evacuating` 节点的
  ACTIVE → `DRAINING(EVACUATE)`、不当覆盖目标、同批在别处补建；`DRAIN_EVACUATE` 超时不回滚；疏散节点上的 SCALE_IN 记录超时转 `DRAIN_EVACUATE`、期望数不动；撤销后 per-node 补回）。
- `DirectoryViewTest`：疏散节点**仍在** `DirectoryView` 里（防 A1 回归），`evacuating` 字段读出。
- `WorldAutoscalerTest`：见 §11.1。
- `NodeAvailabilityTest`：从目录推导；`createInstance` 对疏散节点回 3000（5.3 接入后）。
- 常数守卫：C5。

**xm-gate**（`ClientDispatcherTest` / `SceneLinkTest` / `SceneEventRouterTest` 写法）
- `SceneLinkTest`：ack 实例与解析不符 → fail；代次低于目录 → fail；任一侧代次 0 → 跳过比对；ack 回显 `ping_interval_ms` 才启用读空闲；读空闲 → fail("心跳超时")；Ping / Pong 维持链路。
- `SceneLinkManagerTest`：被取代后 `PlayerEnter` 新建一代链路；按代次的 `ClientForward` / `PlayerLeave` 仍去旧链路、那一代不在了回 0；宽限到期只关旧代次上的会话。
- `SupersessionSweepTest`：读目录失败 / 条目缺席不动；同实例代次更高只更新；实例不同且代次更高 → 取代；代次为 0 时要连续两次不同；
  取代之后又读到代次更低的旧条目（迟到写，R9）→ 忽略、不撤销取代。
- `SceneLinkTest` 补：读空闲按任何入站帧计（只有 `ToClient`、没有 Pong 时不判死）。
- `ClientDispatcherTest`：转发 / 离开带代次；链路断开先推 23 `{1003}` 再关（断开原因 `scene_link_down` / `scene_link_superseded`）；被取代链路上到达的 `PlayerTransfer` 照常改绑；
  旧代次断开不影响新代次上的会话（保留现有用例）。
- 常数守卫：C3、C4。

**xm-data**
- `SceneEvacuationAdminControllerTest`：鉴权、操作人、审计；404；TTL 边界 400；409 `last_node` 与 `force`；同实例重复打不改起点；无 TTL 的已有标记 409 `existing_mark_invalid`；
  `GET` 叠加与 `stale_mark`；`DELETE` 恒 204；没有 Redis 也能启动（同 `AdminEndpointSecurityTest`）。

### 11.3 真 Redis / 真 MySQL

- `-Dxm.it.redis`：
  - `NodeIdLeaseIT`：键被他人覆写 → 一个续期周期内 TAKEN；键过期 → 原号复占、代次 +1；Redis 停 20 s（测试里用假门面注入错误）→ 挂起不判丢。
  - `SceneEvacuationMarksIT`：「最后一台」检查与写入原子（两个并发请求至多一个成功）；实例不符的标记被 scene 忽略。
  - `EvacuationPlanIT`：节点发布 `evacuating` → 领导者同一次版本推进里把记录转 `DRAINING(EVACUATE)` 并在别处补建；节点排空后 P3 删记录。
  - `DeadNodeCorroborationIT`：目录条目消失但租约键还在 → 不删；删掉键 → 宽限后删除。
- `-Dxm.it.mysql`：交出复用 5.2 的 `PlayerStoreSqlTest` 用例，不新增；补「续约停止 → 租约过期 → 夺权成功」的时刻断言。

### 11.4 本机多节点切片与故障注入

切片：`XM_SCENE_NODES=3`（第三个节点链路 21002、资产通道 21102、管理端口 18124；`tools/local/start-slice.sh:14-17` 现支持到 2，Q17），可选备用实例开关（号复用测试用）。
每个用例都核对：MySQL 每个玩家的 `owner_epoch` 单调、同一时刻只有一个持有者；scene 日志里没有被围栏拒绝的写回（`FENCED`）；`xm.scene.transfer.post.freeze.mutations` 为 0。
`kill -9` 用 `kill -9 $(cat run/pids/xm-scene-2.pid)`（与 `stop-slice.sh` 的强杀同法）。

| 编号 | 操作 | 期望 |
|---|---|---|
| K1 | 节点 2 上有 A、B，节点 1 上有 C；`kill -9` 节点 2 | gate 立即对 A、B 推 23 `{1003}` 后断开，C 不受影响；A 重登先回 2005，最后一次续期后 ≤30 s 进到节点 1 / 3 的同图，坐标是最近一次落库的；约 35–45 s 后规划器删除节点 2 的记录、`world.nodes{state}` 回到正常 |
| K2 | 运维疏散节点 2 | A、B 收到非自己发起的 79（同图、节点 1 / 3 上的新 scene_id、坐标不变），**不断线、无 23**；疏散期间新登录不落节点 2；`GET` 显示 `evacuated`；撤销后下一拍节点 2 拿回频道 |
| K3 | 运维疏散节点 2 的过程中 `kill -9` 节点 2 | 已提交交出的人：E+1 暂无持有者，重登 ≤30 s，数据是冻结快照；没提交的：最近一次落库；不会双持有 |
| K4 | 疏散中，源节点刚提交交出、目标还没进场时 `kill -9` 目标节点 | 同 5.2 M5：23 `{3023}` 后断开，≤30 s 后重登成功 |
| K5 | 疏散中 `kill -9` gate | 5.2 R-J1：E+1 至多 30 s 后才能再进；数据完整 |
| K6 | 疏散中 `kill -9` scene-manager（单副本） | 运维疏散：改派无 tip 地退避，scene-manager 恢复后完成；停服疏散：到期写回、推 1003 后断开 |
| K7 | `redis-cli SET xm:node-id:scene:1:2 intruder PX 60000`（号被占） | 节点 2 ≤5 s 判 TAKEN → A、B 收到非自己发起的 79、**不断线**；节点 2 在预算内退出；目录条目 2 过期 |
| K8 | 节点 2 挂起（`kill -STOP`）> 15 s，起备用实例占到 2 号，再 `kill -CONT` 节点 2 | ≤15 s 心跳超时，A、B 推 1003 后断开；≤5 s 内 gate 发现换代，分到 2 号的新登录进新进程、不再 3023；旧进程恢复后续期回 −1 → TAKEN，写回被围栏拒或成功释放（看日志），无玩家后退出 |
| K9 | 停 Redis 20 s | 全部 scene 挂起：**不**疏散、**不退出**、不停链路监听；新登录失败；恢复后续上或原号复占（代次 +1）、恢复接客、目录与计划恢复。**玩家会断线**：gate 的节点号租约语义本批不改（Q3），续期失败超过 15 s 即 `onLeaseLost` 关全部会话（`GateNode.java:272-283`），各会话照常经链路让 scene 写回。所以本用例要核对的是 scene 侧：断线全部来自 gate（gate 断开原因计数），scene 没有 `TAKEN` / 退出、恢复后无需重启即可重新接客、重登成功；对照现状（每台 scene 永久停接客，G1）。想单独看 scene 挂起时「在场玩家照常」，用 K9′ |
| K9′ | 只断开节点 2 到 Redis 的连接 20 s（本机用防火墙规则或 Redis `CLIENT KILL` 循环踢掉节点 2 的连接；gate 与别的节点照常） | 节点 2 挂起：A、B 照常移动与广播、不断线；目录条目过期后新登录不落节点 2；节点 2 名下频道约 35 s 后被规划器删除、在别处重铺；恢复后节点 2 原号复占、拉到计划，本地旧频道成「计划外」→ A、B 经 §5.3 改派（per-node 下领导者通常已给节点 2 铺了新号频道，于是同节点换到新 scene_id；否则跨节点；都是非自己发起的 79、无 23） |
| K10 | `evacuate-first` 开时 `kill $(cat run/pids/xm-scene-2.pid)`（只对节点 2 发 SIGTERM；`stop-slice.sh` 不支持单停、且先停 gate） | A、B 不断线地迁到别的节点后进程才退出（≤ 预算）；scene 日志里没有 Dubbo 的「Run shutdown hook now」、选目标调用全程成功（H12）；关掉 `evacuate-first` 时与今天相同（断线） |
| K11 | hash 覆盖、低阈值缩容 | 节点 2 上的牺牲者没有同节点兄弟 → 居民跨节点改派（同图、坐标保留）、期望数 −1、P3 收尾 |
| K12 | 两台都运维疏散（第二台带 `force`），第三台停掉 | 玩家原地、没有 23、不断线，`relocations{blocked}` / `refused` 增长；撤销后恢复 |
| K13 | 全部 scene 同时 SIGTERM（`for p in run/pids/xm-scene*.pid; do kill $(cat $p) & done`；**不**用 `stop-slice.sh`，它逐个停且先停 gate） | 各节点 ≤2 s 内发现无处可去、提前结束疏散，正常写回退出，没有被 kill -9；玩家 23 `{1003}` 后断开 |
| K14 | 节点 2 逻辑线程卡顿（测试开关注入 30 s 卡顿） | 目录发布超时、节点缺席，但租约键仍在 → `world.nodes{state=stuck}`、记录保留，卡顿结束后无频道换号；卡顿期间链路背压暂停读，scene 不因读空闲自关链路（`link.idle.ignored` 增长、`idle.closes` 不变），gate 只要还有下行就不判死 |
| K15 | 运维疏散节点 2（30 人）的过程中让 MySQL 不可达 10 s（停 mysqld 或防火墙） | 在途的至多 `max-in-flight` 人按 5.2 收尾（可能 23 `{3023}` 后断开），其余原地无感；`relocations{deferred_storage}` 增长；MySQL 恢复后疏散继续完成；没有双持有者 |

### 11.5 压测（7.4 前置）

1000 / 2000 人在节点 A 时运维疏散：记录疏散总时长、冻结时长 p99、存储池队列峰值、scene-manager 选目标 QPS；据此定 `max-in-flight` 与 `evacuation.budget` 的缺省值（Q11），
并复核基线「百毫秒级」的估计（B4）。

### 11.6 robot

基线没有对应 robot（`scene-core.md:265`、`scene-manager-match.md:162`；`robot/main.go` 里的 drain 字样与本功能无关）。

| 子命令 | 前置 | 步骤与断言 |
|---|---|---|
| `evacuate`（新，`RobotOptions.Scenario.EVACUATE`） | ≥ 2 个 scene 节点、运维令牌、per-node 覆盖 | ① 照 `CrossNodeScenario` 的做法让 A、B 落在节点 2（同图不同 scene_id 即不同节点），观察者 C 在节点 1 同图；② `POST /admin/scenes/evacuate` 节点 2；③ A、B 在 ≤10 s 内收到一条**非请求触发的 79**：`scene_config_id` 不变、scene_id 属于节点 1、坐标在容差内，并收到自己的 21（新实体号），C 收到 A、B 的 21；④ 全程没有 23、没有断线（A、B 在收到 79 之前不发 63，否则疏散在途时会合法地得到 3014，§5.11）；⑤ A 移动后 C 收到 66，再发 63 / 43 / 54 正常应答；⑥ 新号 D 不落节点 2；⑦ `GET` 显示 `evacuated=true`；⑧ `DELETE` 后节点 2 再次被分到人 |
| `evacuate-shutdown`（新） | 同上 | 用 `tools/GmShutdown.java` 签名停节点 2：A、B 在预算内落到别的节点、不断线；随后切片脚本重启节点 2。注意 GM 停机先关 Spring 上下文再 `System.exit`，**覆盖不到** H12（Dubbo 停机钩子只在 SIGTERM 路径上抢跑），SIGTERM 路径由 K10 覆盖 |
| `evacuate-nowhere`（新） | 同上 | 不带 `force` 疏散最后一台 → 409 `last_node`；带 `force` 后全部疏散 → 玩家原地、没有 23、不断线；撤销后恢复 |
| `node-loss`（新，两阶段 `--phase prepare / verify`，中间由脚本 `kill -9`） | ≥ 2 个 scene 节点 | verify：连接已被关闭且关前收到 23 `{1003}`；重登先回 2005，按退避重试（本场景用 40 s 截止，现有 `PlayerFlow` 只试 4 次、间隔 1 s，`PlayerFlow.java:45-47`，覆盖不了 30 s 租约）；最终落在幸存节点同图（PARITY ⑥） |
| 回归 | — | `cross-node`（5.2）、`drain`（gate 排空）、`reconnect`、`movement`、`skill` 照常通过；`movement` / `skill` 的「两个号同场景」假设不受影响（本场景用独立的号） |

robot 要补的能力：被动等待非请求触发的 79（复用 `CrossNodeScenario` 的 79 处理）；调 xm-data 管理接口（复用 `DrainScenario` 的 HTTP 客户端）；感知连接被关闭前的 23。

**兼容**：用 mmorpg 的 Go robot 连 Java 切片做疏散——非自己发起的 79 无害（`SignalSceneReady` 只生效一次，`docs/reference/mmorpg-client-contract-scene.md:305`）；
stress AI 的 `switch_scene` 在疏散期间仍可用；Go robot 感知不到连接被关（`docs/reference/mmorpg-client-contract-robot.md:204`），`node-loss` 只用 Java robot。

**检查项引用**：PARITY 新增的「场景排空 / 节点疏散、死节点判定与接管」行；盘点 `scene-drain-relocate`、`sm-dead-node-recovery`、`scene-node-loss-handling`。

---

## 评审修订记录

完整性评审（只读基线 `D:\work\mmorpg@26ceb70ca` 与本仓库工作区；除本文件外未改任何文件）。抽查引用 60 余处（基线 C++ / Go 约 40、Java 约 25），绝大多数准确；下表只列改动。

| # | 类别 | 改了什么 | 依据 |
|---|---|---|---|
| 1 | 漏掉的客户端可见帧（基线） | §1.4 第 5 步、§1.8、§3.1 补上 gate 进场转发欠账超限的收口：23 `{3023}` → 34 `KickPlayer{reason=3023}` → 关写端，1 s 后强关；注明 Java 沿用 5.2 的「23 `{3023}` 后断开、不发 34」 | `scene_entry_dispatch.cpp:156-200`、`scene_entry_dispatch.h:25-27`、`:37` |
| 2 | 基线描述错误 | §0.5 第一行：基线改派到默认大世界不是「出生点」，新实体沿用旧坐标、只按导航网格校验（与本稿 §1.8 和 5.2 §3.4 对齐） | 5.2 规格 §3.4（`player_scene.cpp:60-66`，推导） |
| 3 | 不安全设计（停服） | 新增 H12 / G15 / V17：Dubbo 3.3.6 对 `IsolatedDubboModule` 建的应用模型无条件注册 JVM 停机钩子，SIGTERM 时与 Spring 钩子并行销毁选目标客户端，停服疏散会全部 Failed；5.5 前置为设 `dubbo.shutdownHook.listenIgnore=true`，补单测与 K10 断言 | 对 `dubbo-3.3.6.jar` 的 `DefaultApplicationDeployer.initialize/registerShutdownHook`、`DubboShutdownHook.register/doDestroy` 反汇编；`IsolatedDubboModule.java:52-53` |
| 4 | 常数关系错误 | G16 / C2 重写：`SceneNode` 只实现同步 `stop()`，`SmartLifecycle.stop(Runnable)` 缺省先同步跑完再回调，`timeout-per-shutdown-phase` 约束不了停服；守卫改为外部强杀期限（stop-slice 50 s、K8s ≥ 60 s），公式补上审计发完 5 s 与逻辑线程停止 5 s；`timeout-per-shutdown-phase` 不改、只更正注释 | spring-context 6.2.19 反汇编；`SceneNode.java:426-435`、`:556`；`SceneNodeProperties.java:89` |
| 5 | 线程所有权 | §5.10 / §5.14：冲突收尾的看门狗从 `scene-sched`（每 100 ms `callOnLogic`）移到逻辑线程上的定时检查 + future，`ProcessExit` 交给独立线程；停机线程只等 future。理由：`scene-sched` 只有 3 条线程，还承担续约 | `SceneNode.java:136`、`:240-241` |
| 6 | 竞态 | V19 / §5.10：SHUTDOWN 疏散中判出 TAKEN 时只升级 cause，不再调 `ProcessExit`；§5.4.4：撤运维标记只结束 cause 仍为 ADMIN 的疏散 | §5.4.1「只升级不降级」的推论 |
| 7 | 心跳误判 | §5.8 / §5.7.1 / V18 / K14：现有背压 `setAutoRead(false)` 期间 scene 不读 Ping，读空闲会让 scene 自己关链路——改为暂停读期间不计读空闲；gate 读空闲按任何入站帧计；`ping_interval_ms` 加 [1000, 30000] 边界 | `NodeLinkHandler.java:195-198`、`:233` |
| 8 | 缩容振荡 | §5.6.6 / E10 / Q10：「放开 D9 = 全图余量」会让居民全部落到同节点兄弟上、把它推过扩容线；改为余量按改派实际落点算（有同节点兄弟只算它们），per-node 下规则与 5.1 相同，只在 hash 下改变 | 基线 `world_autoscale.go:259-262` 注释；本稿 §5.3 去向顺序 |
| 9 | 计划规则缺口 | §5.6.4：疏散节点上的 SCALE_IN 记录超时不走 D10 回滚（否则下一拍又被 P2b 转走、期望数白加 1），改转 `DRAIN_EVACUATE` | P2 → P2b → P3 的执行顺序 |
| 10 | 失败路径缺口 | §5.3 新增「存储闸」、V15、K15、E5：MySQL 抖动时 5.2 的 `Lost` 一律踢 3023，疏散若照常按上限冻结会成批踢人；关闸期间不发起新的疏散冻结 | `SceneWorld.java:1147-1160`（`onTransferLost`）；5.2 F6 / Q8 |
| 11 | 失败路径缺口 | V16：目标节点刚进挂起时交出进场被拒（F10）；§5.11 补「疏散在途时玩家自己发 63 → 3014」与「66 只在移动中才发」 | `SceneWorld.switchInFlight`（`:863-876`）、`freeze`（`:969-983`） |
| 12 | 租约细节 | §5.5：原号复占时 INCR 失败的处理（放键、保持挂起）；缺省模式的全部调用方（含 6.2 battle、xm-data `OpsIds`）回归；复占与雪花的关系 | `NodeIdLease.java:104-121`；`BattleInfrastructure.java:95`、`OpsIds.java:84` |
| 13 | 目录围栏的残余 | §5.5.4 / R9 / §5.7.3：发布前查 `isValid()` 只收窄 X4（Redisson 内部重试的迟到写仍可能盖掉新条目 ≤ 5 s）；gate 换代扫描按「见过的最大代次」忽略旧条目 | 推导 |
| 14 | 原子性 | §5.4.2 第 3 步：`evacuating` 与场景 `draining` 在同一份逻辑线程快照里取（现 `SceneNodeInfo` 是启动时的静态模板） | `SceneNode.java:396-404` |
| 15 | 指标命名 | scene-manager 指标前缀改为现有的 `xm.scene_manager.*`（原稿写成 `xm.scene-manager.*`，那是配置键前缀）；`node.restarts` 与原号复占的 `node.regained` 分开；新增 `deferred_storage`、`link.idle.ignored` 与告警 | `WorldChannelMetrics.java:33-39`、`SceneDirectoryProvider.java:36-37` |
| 16 | 测试计划错误 | K9：停 Redis 20 s 时 gate 的租约（本批不改，Q3）会关全部会话，「没有玩家断线」不成立——改写期望并新增只断节点 2 的 K9′；K10：`stop-slice.sh` 不支持单停，改用 `kill` 单个 pid；K13：`stop-slice.sh` 是逐个停且先停 gate，改为并发 `kill`；§5.10 同步更正；`WorldAutoscalerTest` 现有 23 个用例（原稿写 13）；robot `evacuate-shutdown` 注明 GM 路径覆盖不到 H12 | `GateNode.java:272-283`；`stop-slice.sh:7-17`；`WorldAutoscalerTest` |
| 17 | 引用勘误 | `enterscenelogic.go:704` → `:705`（写位置）、`:731-750` → `:732-750`；`pending_scene_entry_comp.h:33-35` → `:37-38`、`:19-21` → `:20-22`；`noteNodeGoneFromRegistry` 补调用点 `load_reporter.go:969`；§8.3 里 `dungeon-mirror-spec.md:102`（空行）→ `:118`、`:810`；§0.1 停服顺序是「先断链、再写回」（`SceneNode.java:523`、`:535-539`），原稿写反 | 逐条复核 |
| 18 | 新增勘误 / 同步项 | §8.3 / §5.15：基线 `node.cpp:839-840` 注释「四个触发点」同样过时；5.2 F7 / F11 客户端列随 E14 改为「推 23 `{1003}` 后关」；6.2 `battle-node-spec.md:1165`「尽力删除目录条目」与 H9 / X4 冲突；`application.yaml:21` 注释不成立；§0.4 增 6.2 行；§5.13 增 Agones / 号段收尾不移植行；§6.1 补 `kShutdownCompletionWaitTimeout`、gate 放弃后强关 1 s、request_id 去重 60 s；R7 补 `NodeIdLease` 与 6.2 共用；新增 R10（滚动重启的多次迁移） | `node.cpp:53`、`:839-840`；`main.cpp:128`、`:136`、`:250` |
| 19 | 与并行规格的矛盾（5.4 / 6.3） | 工作区里已有 5.4 `zone-travel-spec.md` 与 6.3 `scene-battle-spec.md`：`SwitchReason` 改为 `{PLAYER, TRAVEL, EVACUATE}`（TRAVEL 由 5.4 引入，裁决 16），`playerRequested ≡ reason != EVACUATE`（原稿 `== PLAYER` 会把 226 判成非玩家发起）；收敛谓词并入 5.4 的 `travelFramesPending`（5.4 TF16 的硬要求）；§5.4.3 补疏散中 226 的处理；指标 `reason` 取值加 travel；proto `SelectSwitchTargetRequest.reason` 不含 TRAVEL（跨 zone 不选目标）；§0.4 的 5.4 / 6.3 行引用两稿的钩子（6.3 入口拒绝 `inBattle()` 与本稿 Q16 一致） | `zone-travel-spec.md:98`、`:123`、`:357`、`:613-614`、`:898`；`scene-battle-spec.md:881`、`:1090`、`:1244` |

本稿行号在评审后整体后移；5.4 稿引用的 `scene-drain-spec.md:104`、`:130`、`:508`、`:609-612`、`:962`、`:1012` 已不再对应原段落，以章节名（§0.4、§0.6 A6、§5.2、§5.4.4、§7.2、§8.2 R3）为准。
