# 跨 zone 传送（226）与重定向（124）、归属区路由（批次 5.4）移植统一规格：从 mmorpg 基线到 Java 版

> **基线**：mmorpg `26ceb70ca`（与 `contract/SOURCE.properties` 的 `mmorpg.commit` 相同）。**Java 侧**：HEAD `aa8b5b5`；工作区里批次 5.2（跨节点换图与归属交接）
> 与 6.2（battle 节点）正在实现、尚未提交，恰好改着本批要动的文件（`ClientDispatcher`、`SceneWorld`、`ClientRequestHandler`、`PlayerStore`、`PlayerMapper`、
> `node_link.proto`、`scene_directory.proto`、`start-slice.sh` 等）。**本稿引用的 Java 行号一律按工作树**（标「工作树」的会随实现漂移），引用时同时写类名 / 方法名。
>
> **状态（2026-10-11）**：**批次 5.4 已实现并集成到 main**（先行件 `ffb6168` / `813dfd6` / `f7143b0` / `888d404`、X16 `0765a18`、下沉重构 `9a81db3`、合拢 `77b245b` / `14ea692`；
> 六个工作包 W1–W6 的实现与评审修正 `bca69b9` … `52d6875`；合并后跨模块评审的修正 `b24582d`、`e7ff9a2`），本机全量单测通过；**双 zone 本机切片（§11.9）、robot 端到端（§11.11 / §11.12）、
> Go robot 跨实现验收（§11.10）与 5.4 新写的真 Redis / MySQL 集成测试都还没有跑**，PARITY 登记为「Java 进行中」。正文已按实现回写（标「落地」的是实现阶段定下来的口径，
> 与原稿不同的地方在原句后面就地注明）；类的落点、与原稿的出入、评审的发现与处理、证据与遗留见文末「实现记录（2026-10-11）」，与正文冲突时以它和代码为准。
> 下面「Java 侧」一句与正文里引的 Java 行号是成稿时的工作树，没有逐个更新，**以类名 / 方法名为准**。
>
> **路径怎么读**（同 5.2 规格 `docs/porting/scene-handoff-spec.md:9-35`）：
> - 以 `go/`、`cpp/`、`proto/`、`robot/`、`tools/merge_zone/`、`generated/` 开头的路径，以及 `travel.md`（= `docs/design/cross-zone-scene-travel.md`）、
>   `runbook.md`（= `docs/ops/cross-zone-failure-test-runbook.md`）在 `D:\work\mmorpg` 下；
> - 以 `game-`、`docs/`、`tools/local/`、`config-data/`、`contract/`、`PARITY.md`、`AGENTS.md` 开头的路径在 `D:\work\xuanming-server-mmo` 下。
> - 不带目录的基线文件：`player_lifecycle.{h,cpp}`、`travel_freeze_cap.h` 在 `cpp/libs/services/scene/player/system/`；`player_scene_handler.cpp` 在
>   `cpp/nodes/scene/handler/rpc/player/`；`scene_handler.cpp` 在 `cpp/nodes/scene/handler/rpc/`；`client_message_processor.cpp` 在 `cpp/nodes/gate/handler/rpc/`；
>   `gate_event_handler.cpp`、`scene_entry_dispatch.h` 在 `cpp/nodes/gate/handler/event/`；`enterscenelogic.go`、`gate_redirect.go`、`home_zone.go`、`changesceneutil.go`
>   在 `go/scene_manager/internal/logic/`；`errors.go` 在 `go/scene_manager/internal/constants/`；`entergamelogic.go`、`createplayerlogic.go`、`loginlogic.go` 在
>   `go/login/internal/logic/clientplayerlogin/`；`homezone.go` 在 `go/login/internal/logic/pkg/homezone/`；`gatetoken.go` 在 `go/login/internal/logic/pkg/loginqueue/`；
>   `router.go` 在 `go/data_service/internal/routing/`；`redirect.go` 在 `robot/pkg/`。
> - Java 类：`SceneWorld` / `ClientRequestHandler` / `PlayerCall` / `ClientSink` / `PlayerRepository` 在 `game-scene/src/main/java/com/game/scene/world/`；
>   `ClientDispatcher` / `ClientSession` / `SceneEventRouter` 在 `game-gate/src/main/java/com/game/gate/session/`；`EnterGameHandler` / `CreatePlayerHandler` / `PlayerViews`
>   在 `game-login/src/main/java/com/game/login/handler/`；`LoginClientMessageService` / `LoginConfiguration` 在 `game-login/.../login/`；`PlayerStore` / `PlayerMapper` 在
>   `game-player-store/src/main/java/com/game/player/store/`，表结构 `game-player-store/src/main/resources/db/game-player-schema.sql`（下称 `schema.sql`）；
>   `PlayerLocationDirectory` 在 `game-discovery/.../discovery/location/`；`GateTokens` 在 `game-common/.../common/token/`；`GateTokenIssuer` / `GatePicker` / `RedisGateSource`
>   在 `game-gateway/.../gateway/gate/`；`SceneAssigner` / `WorldSceneConfigs` 在 `game-scene-manager/src/main/java/com/game/scenemanager/`；
>   `node_link.proto`、`client_call.proto`、`scene_directory.proto`、`node_directory.proto` 在 `game-api/src/main/proto/game/api/`。
> - tip 数值取自 `game-table/src/main/proto/tip/`：`scene_error_tip.proto`（3007 `:26`、3014 `:40`、3023 `:58`、3024 `:60`、3025 `:62`、3026 `:64`、3027 `:66`）、
>   `cross_server_error_tip.proto`（13000 `:12`）、`login_error_tip.proto`（2005 `:22`、2011 `:34`、2017 `:46`、2020 `:52`）、`common_error_tip.proto`（1003 `:18`、1006 `:24`）。
>   消息号取自 `game-proto/src/main/resources/contract/message_id.txt`（**号 N 在第 N+1 行**：124 `:125`、226 `:227`）。
> - 带「推导」字样的结论没有对应的测试或用例覆盖，是把多处代码路径串起来得出的。
>
> **本稿的来历**：由三份分区稿合并——基线协议稿（226 → 124 → 落地的端到端协议、客户端契约、时间常量）、归属区与重连稿（home zone、合服围栏、跨 zone 后的重连选 gate）、
> Java 设计稿（建立在 5.2 之上的映射、多 zone 切片、差异与测试）。分稿之间、以及与代码不一致的地方都回到代码重新核对，裁决集中在 §0.6，勘误集中在 §8.4。
> 本稿只读代码，除本文件外没有改任何文件。

---

## 0 概览与范围（与 5.2 / 5.3 / 5.5 / 7.3 的边界）

### 0.1 结论速览

- **基线怎么做**：客户端发 226 → 源 scene 同步校验（3024 / 3007 / 3025 / 3026 / 13000 / 3014）后冻结、存盘、写 handoff 标记，请 scene_manager（下称 SM）
  `EnterScene{zone_id = 目标, gate_zone_id = 本 zone}`（`player_lifecycle.cpp:2745-2875`）。SM 发现 gate zone ≠ 目标 zone 时**先于解析场景**判跨区
  （`enterscenelogic.go:372`），只读预检目标图有没有频道（`:967-994`）与归属映射 / 合服（`:413`），过换手门（`:419-425`），在目标 zone 挑 gate、签 300 s 票据
  （`gate_redirect.go:27`、`:62-104`），把 epoch 推到 E+1、location 写成「等待落点」（`node_id = ""`，记目标地图），经 Kafka 推 `RedirectToGateEvent`
  （`enterscenelogic.go:1018-1099`、`:1123-1168`）。gate 原样包成 124 推给客户端、**不断连**（`gate_event_handler.cpp:234-271`）；源 scene 收到带 redirect 的应答后
  不存盘销毁实体（`player_lifecycle.cpp:4014-4026`）。客户端连目标 gate 验票，在新连接上**完整重跑** 48 / 26；目标 gate 只认本 zone 的票据（`client_message_processor.cpp:1058-1066`），
  login 拒绝他人持票（2011，`entergamelogic.go:82-87`）、票据钉住本 zone 时不弹回（`:655-662`）；SM 第二条腿用等待落点里记的地图，失败回落默认大世界（`enterscenelogic.go:436-461`）。
- **基线从未端到端跑通**：设计文档自述「全部内容未经编译器与测试验证」（`travel.md:169`），travel-smoke 没人跑过（`PROGRESS.md:5247`），124 只在 09-05 跨区匹配时实机跑通过一次
  （`travel.md:18`）。所以 Java 对齐的判据是**基线代码 + 客户端契约**，不是一份绿色的基线运行记录。
- **Java 设计**（§5）：在 5.2 的「一笔带围栏的 MySQL 交出事务」上加一个 `RELEASE` 模式——写回冻结快照、`owner_epoch` E→E+1、**同时 `owner_released = 1`**。
  两条腿之间无人持有（与基线等待落点同义），第二条腿就是目标 zone 的一次普通 EnterGame（夺权得 E+2）。源 scene 提交后写「待落点」位置记录（`s = l`、节点号 0、
  记目标地图、TTL = 票据剩余），再经**同一条节点链路**发 `PlayerTransfer{redirect}`（5.2 预留的字段 7）；gate 解绑、推 124、会话转 REDIRECTED。
  scene-manager 只做地图预检、选 gate、签票据，不铸 epoch、不写位置。登录期 GO-5（位置在别的 zone）由 login 发会话指令 `RedirectToGate`，gate 推 124、不夺权。
- **归属区**（§3）：Java 的归属区就是 `game.player.zone_id`（`schema.sql:14`，建角同行写入、恒有值），没有基线「映射 vs 账号 blob」两份数据；
  本批唯一的代码缺口是建角把 login **进程**的 zone 写进去（`CreatePlayerHandler.java:262`），改为会话 zone。合服围栏本批只定检查点（恒放行），实现随 7.3。
- **客户端所见**（§4）：成功路径与基线相同（226 `{0}` → 旁人 51 → 本人 124 → 新 gate 上 48 / 26 → 79 / 21 / 47）；码与基线相同。差在时机、顺序、收口：
  226 应答恒先于 124；3026 是 ≤ 2 s 的延迟同步应答；受理到出结论约 25 s（基线最长约 71 s）；交出后失败 23 + 断开、不发 34；推出 124 的旧连接 60 s 后由 gate 关闭；
  跨 zone 重连回原实例。

### 0.2 来源、覆盖面与可信度

- **稀疏克隆覆盖**：本稿用到的 `cpp/nodes`、`cpp/libs`、`go/scene_manager`、`go/login`、`go/data_service`、`go/player_locator`、`go/shared`、`proto/`、`generated/code/proto/tip/`、
  `robot/`、`tools/merge_zone/`、`docs/` 都在。
- **缺**：Unity 客户端仓 `mmorpg-client`（独立仓库）。客户端行为（预算、熔断、文案、`CurrentZoneId`）一律转引 `travel.md` 的客户端核查节（§12.5.4–§12.5.6、§12.7、§13.6），
  标「转述」，没有在客户端代码上复核。`bin/etc/` 与 `cpp/generated/` 大部分不在：C++ 调 SM 的 deadline 10000 ms 只能转引 `travel.md:265` 与
  `go/scene_manager/etc/scene_manager_service.yaml:7` 的注释（实际配置文件 `bin/etc/base_deploy_config.yaml` 不在稀疏克隆里）。
- **124 只有一个生产者**：全 Go 仓只有 `enterscenelogic.go` 的 `sendRedirectToGate`（`:1123-1168`）构造 `RedirectToGateEvent`，三个触发源：本批的 226、login 的
  `RedirectOnEnterEnabled`（首登送回归属区，生产与 dev 都关，`go/login/etc/login.yaml:211`）、GO-5（重连 / 顶号跟随 location 所在 zone）。

### 0.3 盘点 id 与落点

| 盘点 id | 盘点出处 | 5.4 交付 |
|---|---|---|
| zone-travel | `docs/porting/inventory/scene-core.md:220-230` | 226 处理器 + TRAVEL 状态机 + 交出并释放 + 待落点 + `PlayerTransfer.redirect`（§5.2、§5.5、§5.9） |
| cross-zone-redirect | `inventory/gate.md:309-321` | gate 推 124 的两条入口（链路帧 / login 会话指令）、REDIRECTED 收口（§5.7） |
| sm-cross-zone-redirect | `inventory/scene-manager-match.md:106-116` | `selectTravelTarget` / `redirectToZone`：地图预检、选 gate（同地址去重、剔除排空）、签票据（§5.4）；GO-5 落在 login（§5.8） |
| sm-home-zone-routing | `scene-manager-match.md:118-128` | 归属区 = `player.zone_id`，存盘不按归属区路由；合服围栏留检查点（§3.5） |
| home-zone-mapping | `inventory/login.md:233-243` | `player.zone_id` 就是映射；`CreatePlayer` 改取会话 zone（§3.2） |
| 依赖：redirect-ticket-binding | `gate.md:323-335` | gate 存票据字段，经 `SessionContext` 交 login；login 拒他人持票 2011（§5.7、§5.8） |
| 依赖：enter-game-cross-zone | `login.md:221-231` | 票据钉 zone + GO-5（§5.8）；`RedirectOnEnter` 随 7.3（Q16） |
| 边界：scene-node-loss-handling | `gate.md:337-350` | **5.5**；本批只规定它与旅途交叉时的处理（§8.3） |

### 0.4 范围

**5.4 做**：226 全流程（含回家 = 同一条链反向，`travel.md:38` CZ-9）；124 的两个来源（传送、登录期 GO-5）；票据签发与校验（持票者、目标 zone）；交出并释放原语、待落点位置记录；
`CreatePlayer` 归属区来源；`GatePicker` / 签发器下沉共用，gate 目录同地址去重；合服围栏检查点；指标、配置、文档与 PARITY 登记；多 zone 本机切片；Java robot `travel`；
用 mmorpg Go robot `travel_smoke` 做跨实现契约验收。

**5.4 不做**：合服本身与围栏的读写实现（7.3）；`RedirectOnEnter` 首登按归属区弹回（基线缺省关，用途是合服后路由，随 7.3，Q16）；死节点 / 疏散 / 再入屏障 / 节点丢失时会话处理（5.5）；
回合制战斗冻结闸 3025 的真实来源（6.3，本批只留钩子）；跨区 1V1 与 `battle_smoke_cross_zone`（6.5，基线已改为 battle 直连、不经 124）；按区服目录状态拦截传送（Q5）。

### 0.5 与其它批次的边界

| 话题 | 归属 | 5.4 的处理 |
|---|---|---|
| 5.2 交出原语、`PlayerTransfer`、`SwitchState`、冻结闸、墓碑、探测（`scene-handoff-spec.md:712-939`；工作树 `SceneWorld.java:863-1190`） | 5.2 在途 | 交出加 `HandOffMode.RELEASE`（§5.2）；`PlayerTransfer` 字段 7 = `ZoneRedirect`（§5.3）；冻结闸、墓碑、`pendingAbort`、探测全部复用；RELEASE 模式下所有「释放 E+1」的调用跳过（已释放） |
| 5.2 留给 5.4 的钩子（`scene-handoff-spec.md:103`：「交出事务相同；字段 7 承载 redirect；gate 推 124 而不是改绑；3027 / 13000」） | 5.2 → 5.4 | 照此接；唯一不同是交出时同时释放（X1，§8.4 勘误 3） |
| 5.1 计划按 zone 分键，能回答「(zone, conf) 有没有频道」（`scene-channels-spec.md:91`；`RedisKeys.worldChannels`，`RedisKeys.java:229`） | 5.1 已做 | 地图预检读目标 zone 的计划快照（§5.4） |
| 5.3 镜像 / 副本里发 226（`dungeon-mirror-spec.md:119`「在实例里发 226 由 5.4 决定」） | 5.3 → 5.4 | 放行，同基线：`RequestZoneTravel` 不看场景类型（`player_lifecycle.cpp:2745-2816`；`travel.md:214`）；实例按 5.3 的空闲回收处理（Q13）。不能传送**进**副本 / 镜像（目标图必须是 World 表地图，3007） |
| 5.5 scene 链路断开时 gate 关会话（Java 有意不同、未登记 PARITY，`gate.md:337-350`） | 5.5 | 不改；旅途中源链路断开按 §5.5 的 `pendingAbort = LEAVE` / TF7 收口；第二条腿目标链路断开走普通进场失败。5.5 规格把「疏散中的 226」交给本批（`docs/porting/scene-drain-spec.md:104`）：疏散本身占用 `switching` 槽（5.2 原语）时 226 按 §5.5 第 4 步回 13000 / 3014；已在 TRAVEL 的玩家被疏散跳过（同 5.5 对「自己的换图在途」的处理，`scene-drain-spec.md:130`）；疏散只在本 zone 内选目标，不跨 zone。**5.5 的收敛谓词**（`playersById` 空 ∧ … ∧ `transferWritesPending == 0`，`scene-drain-spec.md:609-612`）必须把「已交出、实例已移除、待落点在写、`PlayerTransfer{redirect}` 还没写出」的旅客计入（本批提供计数 `travelFramesPending`，5.5 并进谓词），否则停服 / 冲突疏散会在帧发出之前退出（TF16） |
| 6.2 battle 节点直连票据 | 6.2 | 互不相干：battle 直连自有票据与端口，不经 124 |
| 6.3 战斗在途 | 6.3 | 226 同步段第 3 步留钩子回 3025；`PrepareBattle` 拒绝 `switchState ≠ NONE`（5.2 已定，`scene-handoff-spec.md:106`） |
| 4.3 / 4.4 / 4.7 的归属区读者（`PlayerHomeZones` 读 `player.zone_id`，`game-common/.../player/PlayerHomeZones.java:12`、`:51`） | 已做 | 访客的组队 / 帮会 / 聚宝斋天然按归属区（同 `travel.md:35` CZ-6），不改 |
| 7.3 合服（`merge-*`） | 7.3 | 本批定下围栏检查点与接口（§3.5），并把写侧约束登记进路线图 7.3；`RedirectOnEnter` 随 7.3 |

### 0.6 合稿裁决（分稿分歧与依据）

| # | 分歧 | 分稿说法 | 裁决 | 依据 |
|---|---|---|---|---|
| 1 | 普通 gate 令牌的 `target_zone_id` | 归属区稿：改回 0（同基线），gate 另验 `zone_id`；Java 设计稿：保持 `= zone_id`，票据判据改用 `player_id ≠ 0` | **保持 Java 现状**（X13） | Java 普通令牌钉 zone 是已登记的有意差异（`GateTokenIssuer.java:14-15`、`:47`），`GateTokens.verify` 已据此拒他区令牌（`GateTokens.java:55-57`）；基线 gate **不验** `zone_id`（`client_message_processor.cpp:1051-1074`），改回 0 再加 `zone_id` 校验等于换一个新差异；SM 签的票据恒填 `player_id`（`gate_redirect.go:85`），所以 `TP ≠ 0 ∧ TZ == G` 对重定向票据与基线 `TicketPinsZone`（`homezone.go:241-243`）等价 |
| 2 | 跨 zone 交出的归属终态 | 归属区稿：沿用「最终写回并释放」（epoch E 围栏）；Java 设计稿：E→E+1 且 `released = 1` | **E→E+1 + 释放**（X1） | `updateStateAndRelease` 只判 `owner_epoch = E`、不要求 `released = 0`（`PlayerMapper.java:64-70`），只在 E 上释放时 E 的迟到 / 重复写回在下一次夺权之前仍能落库；推进一代后全部被围栏拒。还能原样复用 5.2 的安全边际、租约值标识与加锁读探测（工作树 `PlayerStore.java:359-417`） |
| 3 | 等待落点的表示 | 归属区稿：新状态 `w`，`find` 带状态返回；Java 设计稿：复用 `s = l`、节点号 0、TTL = 票据剩余 | **复用 `l` + 节点号 0**（X14） | 三个读者都不用改：`find` 对 `l` 有值（`PlayerLocationDirectory.java:109-115`）、资产通道 `l` → NoHolder(LEASE)（`:317-319`）、组队 `l` → RECONNECT_LEASE（`:345-355`）；正常的 `l` 永远带节点号（断线写最后所在位置，`:143-147`），节点号 0 可无歧义地表示待落点；过期交给 Redis TTL |
| 4 | 待落点写入用哪个 epoch | 归属区稿：E、序号 +1；Java 设计稿：E+1、序号 1 | **E、序号 +1** | 两者都安全（E+2 的写一定更新）；用 E 保持 5.2 的 J5「只以自己写过的 epoch 写」（`scene-handoff-spec.md:686`、`:1001-1002`），且与 5.2 的补写函数 `writeLeaveLocation` 同一套序号。注意这是对 5.2「提交后只补写一次」（`:1001`）的**扩展**：旅途提交后源节点最多写两次（待落点 (E, q+1)，之后 TF7 / 交叉离开再写 (E, q+2)），见 §5.9 |
| 5 | 合服围栏读侧 | 归属区稿：5.4 接真实现（`game:merge-fence:{zone}`，读失败封锁）；Java 设计稿：只留检查点，随 7.3 | **只留检查点、恒放行**（Q6） | 帮会 4.4 已有同一先例（`game-guild/.../zone/MergeFence.java:18-31`、`GuildConfiguration.java:437` 用 `MergeFence.NONE`）；7.3 之前没有写者，提前接读侧只会给每次 EnterGame 加一条 fail-closed 的 Redis 依赖，客户端所见零差别 |
| 6 | `RedirectOnEnter` | 归属区稿：本批做（缺省关）；Java 设计稿：随 7.3 | **随 7.3**（Q16） | 基线缺省关（`login.yaml:211`；`config.go:198-215` 注明依赖客户端实现），唯一用途是合服后把源区登录的人送去目标区 |
| 7 | GO-5 的 SM 接口 | 归属区稿：`AssignSceneRequest` 加 `gate_zone_id`、应答加 `redirect`；Java 设计稿：新方法 `redirectToZone` | **新方法** | `assign` 保持「选一个场景」的单一职责（`SceneDirectoryService.java:17-18`）；重定向不解析场景、不预占，与 assign 没有共享步骤 |
| 8 | login 发起 124 之后旧连接的状态 | 归属区稿：保持「已登录、未进游戏」，可再发 EnterGame；Java 设计稿：REDIRECTED，丢弃一切请求 | **两种来源统一 REDIRECTED** | 基线重定向分支是 EnterGame 的成功出口（`entergamelogic.go:767-775`），成功即删登录会话（`PARITY.md:35`），旧连接本来就进不了游戏；统一后 gate 只有一种收口 |
| 9 | 2011 持票者校验的位置 | 两份分稿都放在 2018 / 2028 之后、在途闸门之前 | **放在 2018 之后、2028 之前** | 基线在取账号（2028）之前就判（`entergamelogic.go:82` 早于 `:89-95`） |
| 10 | 会话 zone 为 0 时建角 | 归属区稿：2020；Java 设计稿：回落配置 zone | **2020**（fail-closed） | 基线 zone 为 0 视为调用方 bug、拒绝登记（`homezone.go:184-186`）→ 建角回 2020（`createplayerlogic.go:257-268`）；AGENTS.md §3 归属路径默认 fail-closed |
| 11 | 本机切片 zone 2 端口 | 归属区稿：gate 11001、scene 21002 / 21102 / 18124；Java 设计稿：gate 11010 / 18113、scene 21010 / 21110 / 18115 | **gate 11010 / 18123、scene 21010 / 21110 / 18115** | 21002 / 18124 已被 5.1 规划给第三个 scene 节点（`scene-channels-spec.md:1169`）；18113 已被 6.4 的 game-match 规划为管理端口（`docs/porting/match-spec.md:35`、`:916`），gate-z2 改用 18123；6.2 的 game-battle 占 12000 / 21200 / 18112（`battle-node-spec.md:818-820`、`:1412`），不冲突 |
| 12 | gate 目录「同地址只留最新」 | 归属区稿：放进 5.4；Java 设计稿：另起小批 | **放进 5.4**（Q10） | `GatePicker` 本批就要下沉共用，一处改两处生效；基线 login 与 SM 两处都去重（`go/login/internal/svc/servicecontext.go:370`、`gate_redirect.go:155`），重定向选到陈旧条目的代价更高（旧连接已作废） |
| 13 | 目标 zone 不存在 | 基线稿 J5：可以用区服目录同步回 3024；另两份：受理后异步 23 {3027} | **异步 23 {3027}**（同基线） | Java scene 同样没有 zone 表；SM 地图预检 / 选 gate 失败即回落 3027；runbook D1 的期望就是异步 3027（`runbook.md:487-492`） |
| 14 | 交出结局不明后读到 epoch ≥ E+2 | Java 设计稿：按 Lost（23 {3027} 断开） | **按「已交出且已被接管」踢 2017** | RELEASE 模式提交后任何登录都能立即夺到 E+2；探测窗口（< M）内 E 的下一代只可能由本次提交产生（5.2 安全边际论证，`scene-handoff-spec.md:769-790`），所以 ≥ E+2 = 本次已提交 + 别的登录已接管 |
| 15 | 地图预检读 Redis 失败 | 基线：放行（`enterscenelogic.go:980-986`）；Java 设计稿：调用失败 | **调用失败（23 {3027}）**（X20） | Java 计划与 gate 目录在同一 Redis（DB 12），读不到计划时 gate 目录同样读不到；放行只会把失败推迟到交出之后 |
| 16 | 旅途在 `PlayerSwitch` 上的判别字段（评审补） | 本稿初稿：`kind = TRAVEL`；5.5 规格：`SwitchReason {PLAYER, EVACUATE}`、指标标签 `reason`（`scene-drain-spec.md:508`、`:962`） | **`SwitchReason.TRAVEL`**，指标标签统一为 `reason` | 同一个 `PlayerSwitch` 上不能有两个判别字段；5.4 先落地就由 5.4 引入 `SwitchReason {PLAYER, TRAVEL}`，5.5 追加 `EVACUATE`。TRAVEL 额外带 PRECHECK 阶段与旅途字段（§5.5），不另起类 |

---

## 1 基线协议

### 1.1 参与方与链路总图

```
客户端 ─226─▶ gate(A) ─▶ scene(A) RequestZoneTravel ─校验─▶ StartTravelHandoff
  ◀─226 应答{0}─┘          冻结(PlayerFrozenComp) → SavePlayerToRedis → 落地
                           → SET player:{id}:handoff "{E}:{ms}" EX 300
                           → gRPC SM.EnterScene{zone_id=B, gate_zone_id=A, scene_id=0, scene_conf_id, gate_id, gate_instance_id, session_id, correlation_id}
  SM(任一副本)：地图预检 → 归属查询 / 合服闸 → 换手门(标记 epoch==E) → 选 B 区 gate + 签票据
             → Lua：INCR owner_epoch(E→E+1) + location={zone=B, node="", epoch=E+1, pending_scene_conf_id}
             → Kafka gate-cmd(A) RedirectToGateEvent → 应答 scene(A){redirect}
  gate(A) ─124 RedirectToGateNotify─▶ 客户端               scene(A) 收到 redirect 应答 → 不存盘销毁实体（旁人收 51）
客户端：探测 → 先连 gate(B) 再关 gate(A) → ClientTokenVerify(票据原样) → 48 Login → 26 EnterGame
  login(B)：持票者 == 请求角色？target_zone_id == 本 zone → ZoneId=B（不弹回）
  SM(B)：等待落点 → 取 pending 地图 → 归属查询（未映射拒）→ 铸 E+2 → RoutePlayerEvent{home_zone_id=A, owner_epoch}
  gate(B) → scene(B) 从共享 Redis 读档 → 79 / 21 / 47 / 66 → 存盘 topic = db_task_zone_A
```

出处：链路总注释 `player_lifecycle.cpp:2702-2743`；数据流 `travel.md:41-58`；SM 第一条腿 `enterscenelogic.go:366-431`、`:1018-1099`。

### 1.2 客户端可见帧（共享契约，逐字节保持）

| 号 | 方向 | proto（无 package） | 字段 | 何时出现 | 出处 |
|---|---|---|---|---|---|
| 226 | C2S | `TravelToZoneRequest` | `target_zone_id = 1`（u32）、`scene_config_id = 2`（u32；0 = 目标 zone 挑默认大世界） | 玩家发起 | `proto/scene/player_scene.proto:82-91`、`:107`；Java `game-proto/src/main/proto/proto/scene/player_scene.proto:85-94`、`:110` |
| 226 | S2C 应答 | `TravelToZoneResponse` | `error_message = 1`（`TipInfoMessage`） | 同步 | 同上 |
| 23 | S2C 推送 | `TipInfoMessage{id = 1, parameters = 2}` | 本链只用 `id` | 受理后未成 / 第二条腿未成 | `client_player_common.proto:31` |
| 34 | S2C 推送 | `GameKickPlayerRequest{reason = 1, operator = 2}` | `reason.id` = 同一个 tip（3027 / 3023），`operator` 空 | 交出后无法原地恢复 | `client_player_common.proto:12-15`；`player_lifecycle.cpp:223-229` |
| 124 | S2C 推送 | `RedirectToGateNotify` | `target_ip = 1`、`target_port = 2`、`token_payload = 3`（序列化的 `GateTokenPayload`）、`token_signature = 4`、`token_deadline = 5`（i64，Unix 秒） | 第一条腿放行；GO-5 只送连接；RedirectOnEnter | `client_player_common.proto:18-24`、`:33`；Java `game-proto/.../scene/client_player_common.proto:21-27` |
| — | C2S（新连接首包） | `ClientTokenVerifyRequest{payload = 1, signature = 2}` | 原样转发 124 的字段 3、4 | 换到目标 gate 后 | `proto/common/base/message.proto:243-246` |
| — | S2C | `ClientTokenVerifyResponse{success = 1, error = 2}` | 文案见 §1.7 | 验票 | `message.proto:249-252` |
| 48 / 26 | C2S / S2C | `loginpb.LoginRequest/Response`、`EnterGameRequest/Response` | 26 可能回 2011 | 新连接上重登 | `message_id.txt:27`、`:49` |
| 79 / 21 / 47 / 66 | S2C | `EnterSceneS2C` 等 | 与普通进场相同 | 落地 | `docs/reference/mmorpg-client-contract-scene.md` §3 |
| 51 | S2C（给**旁人**） | `ActorDestroyS2C` | 源场景观察者看到传送者消失 | 源实体销毁时 | `message_id.txt:52`；5.2 规格 `scene-handoff-spec.md:67`、`:193` |

### 1.3 信封与字节形态

- **226 应答**：`MessageContent{message_id = 226, id = 请求 id, serialized_message}`。`error_message` **总是存在**，受理时是空子消息、编码为 `0a 00`：生成的 CallMethod 在 handler 之后
  用 TLS 里的 tip 整体覆盖（`cpp/libs/engine/core/macros/return_define.h:67-76`；`player_scene_handler.cpp:250-265` 把拒绝码 `SetTip`）。客户端与 robot 只能用 `error_message.id ≠ 0` 判拒绝
  （`robot/travel_smoke_wire.go:40-54`）。
- **124**：gate 直接组 `MessageContent{message_id = 124, serialized_message}`，**不设 `id`**，`GetGateCodec().send`（`gate_event_handler.cpp:234-271`）。gate **只推送**，
  不断开、不改会话绑定，搬迁全靠客户端（`redirect.go:14-28`）。
- **23 / 34**：scene 经 gate 的 `GateSendMessageToPlayer` 推，`id = 0`；同一 RpcSession，实际按序到达，但 `player_message_utils.h:7-9` 声明不保证（`travel.md:459`）。
- **签名**：`token_signature` 是 HMAC-SHA256 的 **64 字节小写 hex ASCII**（`gate_redirect.go:206-210`），不是 proto 注释说的 32 字节原值（`message.proto:245`，以实现为准）。
  Java `GateTokens.sign` 已一致（`GateTokens.java:31-34`）。

### 1.4 `GateTokenPayload`：重定向票据与普通令牌

客户端把它当不透明字节原样转发，但会**只读解析**其中两个字段（`message.proto:219-240`）：

| 字段 | 重定向票据（SM 签，`gate_redirect.go:80-87`） | 基线普通令牌（login 签，`gatetoken.go:131-136`） | Java 普通令牌（`GateTokenIssuer.java:38-51`） |
|---|---|---|---|
| `gate_node_id = 1` | 目标 gate | 所选 gate | 所选 gate |
| `zone_id = 2` | 目标 gate 所在 zone | 同左 | 同左 |
| `expire_timestamp = 3` | now + 300 | now + 600（`assigngatelogic.go:27`） | now + 600 |
| `hmac_session_key = 4` | **不填** | 32 随机字节 | 32 随机字节 |
| `player_id = 5` | 持票者 | 0 | 0 |
| `target_zone_id = 6` | 目标 zone | **0** | **= zone_id**（Java 有意钉 zone） |

客户端怎么用：Unity 在 RedirectFlow 换连接成功后读 `target_zone_id`（为 0 时读 `zone_id`）作 `CurrentZoneId`（转述，`travel.md:382`）；robot 用它们断言落区与持票者
（`robot/travel_smoke_scenario.go:801-859`）。

### 1.5 226 的同步校验（应答体 `error_message.id`，不改任何状态）

检查顺序即代码顺序，命中即返回。

| 序 | 条件 | 码 | 出处 | Java |
|---|---|---|---|---|
| 0 | gate 层：消息号限频（缺省每秒 3 条） | 信封 1008，到不了 scene | `PARITY.md:40` | 已有 |
| 0′ | scene 入口：实体正在退出（`UnregisterPlayer`） | **1006** | `scene_handler.cpp:493-506` | 不适用（Java 离场即移除实例，没有「退出中」实体） |
| 1 | 实体无效 | 3027 | `player_lifecycle.cpp:2747-2750` | 不适用（只有在场实例才会被分派） |
| 2 | `target_zone_id == 0` 或等于本 zone | **3024** | `:2759-2763` | 同 |
| 3 | `scene_config_id ≠ 0`、World 表非空、该图不在 World 表 | **3007**（复用码） | `:2773-2779` | 同 |
| 4 | 战斗中或备战中 | **3025** | `:2784-2788` | 6.3 钩子，现恒否 |
| 5 | 实体缓存的 `TeamId ≠ 0`（Redis 投影，可能过时） | **3026** | `:2789-2799` | 延迟同步应答（X4） |
| 6 | `IsSceneChangeBusy`：已冻结 / 已有交接组件 → **13000**；普通 63 在途，或上一次作废交接的标记撤回还没确认（Redis 故障时最长约 305 s，`:2984-3006`）→ **3014** | 13000 / 3014 | `:2803-2812` | 同（映射见 §5.5）；Java 没有标记撤回，作废后立即可再试 |
| 7 | `StartTravelHandoff` 重查：退出中、gate 会话不活、**没有可达 SM** | 3027（跨 zone 兜底码 `genericFailTip`） | `:2826-2873` | SM 不可达改为受理后 23 {3027}（X6） |
| 8 | 同上重查：已冻结 / 战斗中 | 13000 / 3025 | `:2849-2859` | 同 6 / 4 |

- **目标 zone 存在与否 C++ 判不了**（没有 zone 表，`:2756-2758`）：请求被受理，之后 SM 回错，客户端看到**异步** 23 {3027}（`runbook.md:487-492`）。
- **副本 / 镜像实例里也能发起**：基线不校验节点类型（`travel.md:214`）。客户端入口只有地图窗（`scene_config_id` 1..4）与 DevAutoPilot（转述，`travel.md:342`、`:324`）。

### 1.6 受理与第一条腿

- **受理** = 已冻结、存盘已发起，**不代表到达**（`kTravelAccepted = 0`，`player_lifecycle.h:915`；契约原文 `player_scene.proto:72-81`）。
- **SM 第一条腿**（`scene(A)` 发起：`zone_id = B, gate_zone_id = A, gate_id ≠ ""`）：
  1. 读 location 与 owner_epoch，过滤陈旧位置（所在 zone 已下线、属主节点已确认死亡且过了屏障，`enterscenelogic.go:290-331`）。
  2. `awaitingPlacement = location 存在 ∧ node_id == "" ∧ owner_epoch ≠ 0`（`:337`）。
  3. **跨 zone 判定先于解析场景**：`crossZoneRedirect = gate_zone_id ≠ 0 ∧ 目标 ≠ 0 ∧ gate_zone_id ≠ 目标`（`:372`），防目标 zone 过渡窗口把人卡死。
  4. `leavingZone = location 存在 ∧ 当前 zone ≠ 目标`（`:384`）。只有 leavingZone 才：a) 地图只读预检 `SCARD world_channels:zone:{B}:{conf}`（conf 为 0 用 World 表第一行；
     0 个频道回 1；**Redis 读失败放行**，`:967-994`、`:980-986`）；b) 归属前置 `resolveHomeZone(rejectsTravel)`：未映射 / 为 0 / 查询失败回 **20**，合服回 **21**（`:413`；
     `home_zone.go:110-216`，超时 1500 ms `:46`）；c) 换手门：标记 epoch == 观察到的 epoch 才放行，否则 **18**（`:419-425`）。
  5. `handleCrossZoneRedirect`（`:1018-1099`）：**先签票据**（纯读 etcd，签不出回 1、一个字节不改）→ leavingZone 时 Lua 铸 E+1、写 location
     `{zone = B, node = "", scene = 0, owner_epoch = E+1, pending_scene_conf_id}`（`changesceneutil.go:128-145`；标记撤回 18、epoch 冲突 19、写失败 3）→ 扣旧场景人数 →
     写 Kafka（失败回 **7** 并单调回滚：epoch 再 INCR、写回滚回执）→ 成功回 `EnterSceneResponse{error_code = 0, redirect}`。
  6. 「只送连接」（`!leavingZone`：目标就是所在 zone，或没有 location）不过门、不写 location、不铸 epoch，日志 `Cross-zone redirect only (ownership unchanged)`（`:1082`）。
- **SM 私有码**（`errors.go:5-69`）1、3、7、8、18、19、20、21 只在服务间用；源 scene 收到任何一个都按 §2.2 收敛成 23 {3027} 或 23 + 34 {3027}。

### 1.7 票据：签发、下发、验证、持票者绑定

- **签发**（`gate_redirect.go`）：没有 `GateTokenSecret` 直接报错（`:44-46`）。从 etcd `GateNodeService.rpc/zone/{目标}/node_type/{gate}/` 读（超时 5 s，`:173`）；
  地址经 `clientendpoint.Select`（优先 `client_endpoint`，`RequireClientEndpoint = false` 时回落 `endpoint`）；按客户端地址 `DedupeNewest` 只留 `launch_time` 最大者（`:155`）；
  按 `player_count` 取最小（`:72-75`）。**不看排空**。字段见 §1.4，签名 hex。
- **下发**：`RedirectToGateEvent{player_id, session_id, target_gate_ip, target_gate_port, token_payload, token_signature, token_deadline}`（`proto/contracts/kafka/gate_event.proto:58-66`）
  套在 `GateCommand{target_gate_id, target_instance_id, event_id}`，写 `gate-cmd`，分区 = `gate_node_id % P`（`enterscenelogic.go:1123-1168`）。至多一次：实例号不符不消费、
  会话不在就丢弃（`gate_event_handler.cpp:236-251`）；gate **不核对** `event.player_id` 与会话玩家，只按 `session_id` 找连接。
- **验证**（目标 gate `DispatchTokenVerify`，失败回 `success = false, error = 文案` 后 shutdown、0.1 s 后强关，`client_message_processor.cpp:993-1001`）：

| 步 | 判据 | `error` 文案 | 出处 |
|---|---|---|---|
| 1 | 已验过 | success（幂等） | `:1003-1007` |
| 2 | 空密钥：dev 直通或拒绝 | `gate token secret not configured` | `:1013-1027` |
| 3 | 常数时间比较 hex 签名 | `invalid token signature` | `:1040` |
| 4 | 解析 payload | `malformed token payload` | `:1048` |
| 5 | `gate_node_id == 本 gate` | `token not for this gate` | `:1055` |
| 6 | `target_zone_id ≠ 0 ∧ ≠ 本 zone`（node_id 只在 zone 内唯一） | `token not for this zone` | `:1058-1066` |
| 7 | `expire_timestamp ≤ now`（服务端时钟） | `token expired` | `:1068-1074` |
| 8 | 通过：装 `hmac_session_key`（可空）；**只存不判** `ticketPlayerId` / `ticketTargetZoneId` | success | `:1092-1099` |

  Java `ClientDispatcher.clientError` 五条文案逐字一致（工作树 `ClientDispatcher.java:1097-1104`），`GateTokens.verify` 顺序一致（`GateTokens.java:36-62`）。
- **持票者绑定**：gate 把两个字段放进 `SessionDetails`（`proto/common/base/session.proto:16-17`），直连与路由两种转发模式走同一个 `BuildSessionDetails`（`client_message_processor.cpp:112-131`）。
  login EnterGame：持票者 ≠ 0 且 ≠ 请求的 `player_id` → 26 回 **2011**，在取账号、加锁、写任何会话状态之前（`entergamelogic.go:78-87`）；`ticket_target_zone_id == 本 zone`
  → `ZoneId = 本 zone`、不按 home_zone 弹回，**优先于** ShortReconnect 的 `ZoneId = 0`（`:646-662`；`homezone.go:238-243`）。普通令牌两个字段都是 0，一律放行。
- **客户端侧**（转述）：`token_deadline` Unity 只打日志不拦截（CL-2，`travel.md:354`），Go robot 本地拦截已过期票据（`redirect.go:89-95`）；重定向熔断每会话 3 跳
  （`redirect.go:64-68`；Unity 主动传送时归零，`travel.md:181`）；先探测（5 s，`redirect.go:73`），「先连新、后关旧」，票据原样转发，在新连接上**完整重跑** Login + EnterGame——
  票据只认证这一条 TCP，login 会话不跨区转移（`redirect.go:22-28`、`:104-186`）。Go robot 的 `SwapConn` 在**验票之前**就关掉旧连接（`redirect.go:108-110`、`:162-180`）。
  Unity 跨区在途只把 `IsTravelFailureTip` 的 5 个码当传送失败、其余 tip 不收场（转述，`travel.md:356`、`:382`）；收到 34 时 reason 命中同一判据才显示传送失败文案，
  否则显示通用断线（`travel.md:442`）——这决定了 Java 不发 34、以及发 23 {2017} 时客户端看到什么（X7、X19）。

### 1.8 第二条腿（login(B) 发起：`zone_id = B, gate_zone_id = B`）

- 不跨 zone，等待落点没有持有者、不过换手门（`enterscenelogic.go:496`）。
- **地图**：等待落点**未过期**（`now − update_time ≤ 300 s`，`:945-947`）、所在 zone 就是 B、请求不带场景与地图时用 `pending_scene_conf_id`（`:436-444`）；解析失败
  回落默认大世界，**人必须能落地**（`:445-460`，reason `pending_map_fallback`）。
- **归属**：`awaitingPlacement` 时未映射一律回 20（`:540-547`）；归属随 `RoutePlayerEvent.home_zone_id` 下发（`gate_event.proto:10-22`），scene(B) 写 `PlayerHomeZoneComp`、
  存盘 topic 按 home_zone 选（`player_lifecycle.cpp:1582-1594`、`:2520-2569`）。
- **失败**：26 已回成功，login 异步链被拒时推 23 {3023}（best-effort，`entergamelogic.go:427-470`），不踢线；gate(B) 转发补发 20 s 预算、至多 16 次不成推 23 {3023} + 34 {3023} 并关连接
  （收口动作 `scene_entry_dispatch.h:20-27`；预算与退避 `cpp/nodes/gate/handler/event/scene_route_helper.h:29-41`；`travel.md:278`）。
- 第二条腿建实体时照常拍 LOGIN 快照（`InitPlayerFromAllData`，`player_lifecycle.cpp:2365-2428`、`:2425`）。

### 1.9 GO-5：重连 / 顶号的去向（login 发 `ZoneId = 0`）

出处：`entergamelogic.go:664-668`；`enterscenelogic.go:342-364`。

| 情形 | 去向 | 结果 |
|---|---|---|
| location 落在具体节点（`node_id` 非空） | 跟随它所在的 zone | 不是 gate 所在 zone 就「只送连接」：回 124、**不动归属**；login 走成功出口、不推 3023（`entergamelogic.go:767-775`） |
| 等待落点（传送途中断线） | **不牵引**，落 gate 所在 zone 并铸造 | 这次传送作废，回到最后稳定位置（取代旧 R8） |
| 没有 location / 租约到期被 LeaveScene 删掉 | gate 所在 zone | 回家 |

- **窗口** = login 断线租约 30 s（`go/login/internal/logic/pkg/sessionmanager/session_manager.go:115`），挂机月卡顺延到 300 s（基线全仓无写入方，`PARITY.md:91` ⑤）。
- 一次访客重连只用 1 跳（第二条腿走票据分支），碰不到 3 跳熔断（`travel.md:818`）。

### 1.10 时间常量（基线）

| 常量 | 值 | 位置 | 客户端可见？ |
|---|---|---|---|
| 重定向票据 TTL（= 124 的 `token_deadline`） | now + 300 s | `gate_redirect.go:27`、`:80` | **是** |
| 普通 gate 令牌 TTL | 600 s | `assigngatelogic.go:27` | 是（HTTP） |
| 等待落点「采用 pending 地图」的有效期 | 300 s（同一常量） | `enterscenelogic.go:945-947` | 间接 |
| handoff 标记 TTL | 300 s | `player_ownership_comp.h:74` | 否 |
| 存盘 / 应答看门狗 | 30 s / 30 s | `travel_freeze_cap.h:52`、`:64` | **是**：受理后最迟约 30 s 收到 3027 |
| 核实余量 / 晚发闸 / 冻结上限 / 扫描周期 | 5 / 35 / 70 / 1 s | `:67`、`:75`、`:71`、`:78` | **是**：最迟约 71 s 出结论 |
| 镜像客户端「已受理交接」预算 | 75 s；`static_assert(70 + 1 < 75)` | `:90`、`:97` | **是** |
| SM zrpc 服务端 Timeout / Kafka 写超时 | 8000 ms / 5 s | `go/scene_manager/etc/scene_manager_service.yaml:3-14`、`:44` | 否 |
| C++ 调 SM 的 deadline | 10000 ms | 转引 `travel.md:265`、`scene_manager_service.yaml:7`（注释） | 否 |
| 归属查询 / etcd 取 gate | 1500 ms / 5 s | `home_zone.go:46`；`gate_redirect.go:173` | 否 |
| login 断线租约（GO-5 窗口） | 30 s | `session_manager.go:115` | **是**（落点） |
| gate 进场转发补发 | 250 ms → 2 s 退避，预算 20 s、至多 16 次 | `scene_route_helper.h:29-41`（扫描周期 `scene_entry_dispatch.h:35`） | 是（3023 + 34） |

**Unity**（转述 `travel.md:1418-1440`）：已受理交接预算 75 s；RedirectFlow 最坏 105 s = 探测 5 + 验票 10 + Login 15 + EnterGame 15 + 等 79 共 60；跨区总预算 195 s；
单个 RPC 超时 15 s；熔断 3 跳。**Go robot**：每跳 60 s、请求间隔 1.1 s、停留 35 s（`travel_smoke_scenario.go:72-101`）。

---

## 2 失败与回滚

### 2.1 基线：受理之后客户端只会看到三种结局

| 结局 | 客户端帧 | 服务端条件 | 出处 |
|---|---|---|---|
| **到达** | gate(A) 推 124 → 新连接 Login + EnterGame → 79 | SM 第一条腿放行，Kafka 已确认 | §1.6 |
| **未成，原地恢复** | 23 {3027}，实体解冻，连接保持 | 归属没动（B4），或 SM 推送失败后单调回滚到本节点（B5，采纳 E+2） | `player_lifecycle.cpp:3759-3818`、`:4311-4373`（tip `:4366-4370`） |
| **未成，无法原地恢复** | 23 {3027} 后紧跟 34 {reason.id = 3027}，不存盘销毁，客户端断线回选服 | 见 §2.2 | `:3876-3918`、`:4218-4309` |

同 zone 的同一条链用 3023 代替 3027（`:2826-2829`）；**踢线 34 只对跨 zone 生效**（同 zone 放行靠改绑同一个会话，`travel.md:427`）。
快路径上「受理后同步失败」的 23 可能**先于** 226 应答 `{0}` 到达，Unity 用代次计数吸收（转述，`travel.md:334`、`:344`）。

### 2.2 基线：证据与收口细表（谁发什么帧）

handoff 标记 SET 是「不可回头点」：SET 发出之前一律可以解冻（I0）；之后只有取证读到 epoch 没变（B4）或本次回滚回执（B5）才解冻，其余一律销毁（`travel.md:889-894`）。

| 阶段 / 事件 | 处置 | 客户端帧 | 出处 |
|---|---|---|---|
| 存盘 30 s 没落地 | Abort 解冻 | 23 {3027} | `player_lifecycle.cpp:2957-2982` |
| 晚发闸（set_mark 阶段，冻结 > 35 s） | Abort | 23 {3027} | `:3354-3368` |
| owner_epoch 为 0、zone Redis 未连接、发不出、SET 收到 ERROR | Abort | 23 {3027} | `:3374-3390`、`:3444-3479` |
| SET 回调为空（结果未知） | 保持冻结、取证（kMarkWriteUnknown，永不踢线） | 视取证 | `:3409-3428` |
| SET 已 OK，但晚发闸（enter_scene 阶段）/ 没有 gate 会话 / 没有 SM | `ConcludeHandoffAfterMarkSent` | 会话活着时 23 + 34 {3027}，然后销毁 | `:3504-3533`、`:4218-4309` |
| SM 应答 `error_code ≠ 0` | 取证（kFailed） | epoch 没变 23 {3027}；B5 回滚 23 {3027} 并采纳 E+2 | `:3982-3993` |
| 应答成功且带 redirect | **不取证**，直接不存盘销毁 | 无（124 经 Kafka） | `:4014-4026` |
| 应答成功、跨 zone 却没有 redirect | 取证（kAnomalous），已放行时只销毁不踢 | 无 | `:4006-4012` |
| 应答看门狗 30 s 到期 | 取证（kNoReply） | 同上 | `:3573-3600` |
| 已放行、没拿到放行应答，且 location 正是本次等待落点 | ClientReset | 23 + 34 {3027}，销毁 | `:3876-3918` |
| 回执对上但交叉校验不成立（B6） | Conclude（`travel_receipt_anomaly`） | 23 + 34 {3027} | `:3819-3836` |
| 冻结满 70 s、SET 已发出 / 没发出 | Conclude / Abort | 23 + 34 {3027} / 23 {3027} | `travel_freeze_cap.h:71-97` |
| 交接途中客户端断线 | 退出优先：作废交接、撤回标记 | 无 | `travel.md:195`、`:438`；runbook 场景 C |

### 2.3 Java：失败与回滚一览

| # | 失败点 | 归属与数据 | 客户端 | 指标（scene `reason = travel`） |
|---|---|---|---|---|
| TF1 | 同步拒绝（3024 / 3007 / 3025 / 13000 / 3014） | 不变 | 226 应答内码 | `travel_requests{result}` |
| TF2 | 在队 / 组队读失败或超时 | 不变 | 226 应答 3026 / 3027（≤ 2 s 延迟） | 同上 |
| TF3 | 选目标拒绝（没 gate / 没这张图 / 参数；7.3 起还有围栏） | 不变 | 23 {3027} | `travel_resolves{rejected}` |
| TF4 | 选目标调用失败 / 超时 / 应答残缺（**落地**：含 `redirect.target_zone_id` 与请求的目标 zone 对不上）；**落地**：RESOLVING 槽过期（结果回调丢了或迟到）由作废方推 23 {3027}、迟到的结果只记日志 | 不变 | 23 {3027} | `travel_resolves{error}` |
| TF5 | 交出 `LeaseTooShort`；探测 `NotCommitted` | 仍是 E，原地解冻 | 23 {3027} | `transfers{lease_too_short / aborted_in_place}` |
| TF6 | 交出 `Fenced` | 别人持有 | 23 {2017} 后断开 | `transfers{fenced}` |
| TF6′ | 交出或探测读到 epoch ≥ E+2（已交出且已被别的登录接管，§0.6 裁决 14） | 新持有者 | 23 {2017} 后断开 | `transfers{superseded}`（**落地**：原稿写 taken_over，那个取值已是 5.2 的「冻结中被请求让出」，先行件预注册了 superseded） |
| TF6″ | 探测 `Lost`（锁等待超时 / 读到无法解释的状态） | 不明，fail-closed | 23 {3027} 后断开（不发 34，X7） | `transfers{lost_unknown}` |
| TF7 | 提交后 `PlayerTransfer` 没写出（链路已断 / 异步写失败） | E+1 已释放；位置以 (E, 序号+2) 改回**源场景的重连租约** | gate 随链路断开关会话；重连回原场景原坐标（X15） | `transfers{link_gone}`（**落地**：在 `handed_off` 之外另计一次，不是 5.2 那样代替它的终结结局，`SceneMetrics.travelLinkGone()`） |
| TF8 | gate 收到过期的 redirect 帧 / 会话已不在 | E+1 已释放；位置由墓碑补写 | 无（会话已走） | gate `redirects{source=travel, stale / orphan}`（**落地**：原稿写 `scene_transfers`；重定向帧不计进 gate 的改绑指标 `game.gate.scene.transfers`）。墓碑在帧发出**之前**就被交叉的离开消费、帧不发的，由 scene 计 `travel_frames_withheld`（合拢评审 D1） |
| TF9 | redirect 字段非法 | E+1 已释放 | 23 {3027} 后断开 | gate `redirects{invalid}` |
| TF10 | 客户端没跟随 124 / 探测不通 / 验票被拒 | 无人持有；待落点 ≤ 300 s | 客户端自行回选服（转述）；从 B 进 → 落待落点地图，从别处进 → 入口 zone 首登 | — |
| TF11 | 第二条腿持票者不符 | 不变 | 26 应答 2011 | login `ticket_holder_rejected` |
| TF12 | 第二条腿进场失败（无场景 / 链路 / 加载） | 现有：scene 释放 / `abandonEnter` | 26 应答内 tip 或 23 {3023}（现有口径，`PARITY.md:27`、`:36`） | 现有 |
| TF13 | GO-5 签票失败（目标 zone 没有 gate / 没有 scene 节点） | 不变 | 落入口 zone（X11） | login `enter_routes{redirect_fallback}` |
| TF14 | 停服时正在交出 | 停服 `save(E)` 与交出谁先都安全：交出先 → save 被拒；save 先 → 交出 Fenced | 断开 | 现有 |
| TF15 | 写待落点失败 | 已释放；记录仍是源节点的 `o`(E)（≤ 60 s） | 第二条腿落目标 zone 默认主世界（同基线 pending 回落） | `travel_placements{failed}` |
| TF16（评审补） | 源节点停服 / 冲突疏散恰在「已交出、待落点在写、帧未写出」窗口里退出（逻辑线程停了，写完的回调投递被拒） | E+1 已释放；位置是待落点（若已写）或源节点 `o`(E)（≤ 60 s） | gate 随链路断开关会话、没收到 124；重连从 A 进：待落点不牵引 → A 首登（默认主世界），从 B 进：落待落点地图 | `transfers{reason=travel, link_gone}`（能计到的话）；5.5 收敛谓词须等 `travelFramesPending == 0`（§0.5） |
| TF17（评审补） | PRECHECK 槽过期作废（组队读回调丢失，正常不会） | 不变 | 由作废方经 `DeferredReply` 补回 3027，绝不让 226 无应答 | `travel_requests{team_read_error}` |

> **落地（2026-10-11）**：槽过期的作废方有三个——`SceneWorld.switchInFlight` 的任意调用方（下一条 63 / 226、备战闸、组队跟随）与每秒一次的维护（`refreshDueLocations`），
> 都经 `concludeExpiredTravel` 先给结论再清槽：PRECHECK 经 `DeferredReply` 回 3027（TF17），**RESOLVING 推 23 {3027}、计 `travel_resolves{error}`**（W2 评审意见 1：原稿「照 5.2 静默清槽」，
> 可客户端此时已收到 `{0}`、处在传送窗口里，§4.4 要求受理之后一定有 124 或失败 tip）。槽上的 `expired` 标记让迟到的结果只记日志、不再推、不再计 stale，每次受理在 `travel_resolves` 上恰好计一次。
> 实例已不在世界里的槽不推不计。63 的 RESOLVING 照 5.2 不变，每秒维护不碰它。

**断开后重连**：RELEASE 模式下 E+1 无人持有、`released = 1`，任何入口的夺权立即成功（不等 30 s 租约）；落点按 §5.8 的表。数据一定是冻结快照或更新的状态。

### 2.4 Java：竞态

- **双重持有**：不可能。任何时刻库里只有一个 epoch；E 在提交时失去写权；两条腿之间没有持有者；E+2 只由夺权铸出。
- **提交之后、124 送达之前别的设备登录**（RELEASE 带来的新窗口，推导）：
  - 从源 zone 登：location 若仍是 `o`@S(E)，login 请 S 回原实例 → 夺权立即成功（E+2）→ `PlayerEnter{E+2}` 到 S：S 上冻结实例还在则按「本节点有更旧 epoch 的实例」
    移除并踢旧会话 2017（工作树 `SceneWorld.java:697-720`），随后迟到的交出结局因实例已移出（`sw.detached()`）只计 `left`；实例已移除则正常进场。
  - 若 S 已发出 `PlayerTransfer{redirect}`：旅客随后持票到 B → 夺权撞 Held(E+2) → 让出请求 → 先登录的实例写回释放、踢 2017 → 旅客落 B。**后登录者赢**，与基线
    「等待落点没有持有者，顶号接管当前位置」同义（`travel.md:765-766`）。
- **226 与 63 / 226 交错**：从 PRECHECK 起 `switching` 槽挡住（§5.5）：并发 226 回 13000、63 回 3014。第一条 226 的应答是延迟的（≤ 2 s），所以第二条的同步 13000
  **可能先于**第一条的 `{0}` 到达——客户端与 robot 必须按信封 `id` 配对应答，不能按到达顺序（基线受理即同步回 `{0}`，没有这种乱序）。
- **组队读之后才入队**（推导）：PRECHECK 读到「不在队」之后、交出提交之前（≤ 约 19 s）玩家仍可经 game-team 入队，于是带队传送。与基线 B-8「TeamId 缓存过时拦不住」同一类窗口，
  Java 的窗口更窄（只有 RESOLVING + FREEZING）；后果同基线（队伍按归属区，跨 zone 跟随不生效），不影响归属与数据。登记为 R-T11，不加跨服务锁。
- **交出与 `PlayerLeave` 交叉**：5.2 墓碑（工作树 `SceneWorld.consumeTransferTombstone`，`:1186`）补写源场景位置；gate 侧把随后到的 redirect 帧当过期丢弃。
- **124 与 226 应答**：同一条链路、应答先发，恒先到（X3）。
- **同号 gate 跨 zone**：z1 与 z2 的第一台 gate 都占到节点号 1（节点号按 zone 租约，`RedisKeys.nodeId`，`RedisKeys.java:16`）；z2 的票据交给 z1 的 1 号 gate 被
  `WRONG_ZONE` 拒绝（`GateTokens.java:55-57`）。
- **GO-5 与票据循环**：重定向的落点只看位置记录；目标 zone 的 login 被票据钉住、不再重定向。每跳恰好一条 124。
- **gate 目录残留条目**（崩溃重启换了节点号、旧条目 15 s 内还在）：同地址只留最新的一条（§5.4）。

### 2.5 幂等

- 交出事务按 (player_id, E, `released = 0`, 租约 ≥ now + M) 只能成功一次；重试中「其实已提交」按租约值 L_i 改判（§5.2）。
- `PlayerTransfer{redirect}` 按 (节点, 代次, 玩家, from_epoch) 只生效一次；之后会话已解绑、重复帧按过期丢弃。
- 位置记录按 (epoch, 序号) 去乱序（`PlayerLocationDirectory.java:66-87`）。
- 票据**不是一次性**的（同基线）：300 s 内可重复验票，但只认证连接；进角色仍要 48 / 26，且持票者绑定（2011）与账号归属校验各挡一层。

---

## 3 归属区与合服围栏

### 3.1 基线

| 项 | 基线行为 | 出处 |
|---|---|---|
| 唯一写入点 | 建角：铸号 → 名字 → `RegisterPlayerZone(player, Node.ZoneId, storage_id)` → 账号 blob；登记失败整体拒绝建角，回 **2020**、释放名字 | `createplayerlogic.go:257-268`、`:696-717` |
| 写语义 | SETNX：不存在才写；同值幂等；异值冲突、绝不覆盖；zone 为 0 拒绝 | `router.go:129-164`；`homezone.go:184-186` |
| 合服闸（写侧） | 目标 zone 有 `merge:in_progress:{zone}` 就拒；EXISTS 预检（查失败当封锁）+ 提交时 Lua 原子复核 | `router.go:73-109`、`:152-245` |
| 角色列表 | `BatchGetPlayerHomeZone`（500 ms）覆盖各角色 `zone_id`，不回写 blob；失败保留建角 zone | `homezone.go:200-236`；`loginlogic.go:236-250` |
| EnterGame | 只在 `RedirectOnEnterEnabled`（缺省关）且首登、无在场 scene 时查，送回归属区 | `homezone.go:245-275`；`entergamelogic.go:590-605`、`:670-682` |
| SM EnterScene | 带 GateId 的请求查 home 与围栏，写进 `RoutePlayerEvent.home_zone_id`；合服 → 21；未映射：首落点 / 同 zone 回落 gate zone，跨 zone 任一条腿拒；超时 / 故障 → 20 | `home_zone.go:19-46`、`:110-216`；`enterscenelogic.go:520-553` |
| 围栏写者 | `tools/merge_zone/fence.go`：SETNX + TTL（= max(run 预算 + 30 min, 1 h)）+ 每 TTL/3 后台续期，结束显式删除 | `fence.go:21`、`:98-187`（TTL 取法 `:111-113`） |
| 拒绝面 | 建角 2020；进场 23 {3023}；传送第一条腿 → 源端解冻 23 {3027}；帮会 14013 | `player_lifecycle.cpp:4362-4372`；inventory `guild.md:66` |

已知缺口：「拒绝先于任何写」只对带 GateId 的请求成立（`enterscenelogic.go:533-540` 注释）。

### 3.2 Java：归属区的来源与写入

- **来源**：`game.player.zone_id`（`schema.sql:14`，`INT UNSIGNED NOT NULL`），与角色行**同一条 INSERT** 写入；INSERT 本身就是「不存在才写」，没有基线「重复登记覆盖」的冲突面；
  丢应答的重试命中已有行，返回原行（`CreatePlayerHandler.isLostResponseRetry`，`:245`）。之后只有 7.3 的合服工具在围栏下改，传送、登录都不改。
- **缺口（本批修）**：建角写的是 login **进程**的 `game.zone-id`（`CreatePlayerHandler.java:57`、`:65`、`:262`；`LoginConfiguration.java:250-253`），进游戏却用**会话** zone
  （`EnterGameHandler.java:205`）。Java 的 login 不分 zone（玩家号租约全服，`LoginConfiguration.java:67-69`），多 zone 部署里在 zone 2 的 gate 上建的角会被记成 zone 1。
- **改为**：归属区 = `session.zone_id`（gate 填自己的 zone，`ClientDispatcher.context`，工作树 `:1005-1015`）；为 0 回 **2020**、不发号、不插行（同基线，§0.6 裁决 10）。
  删掉 `CreatePlayerHandler` 的进程 zone 参数（X16）。结果等价基线「每 zone 一个 login、取 `Node.ZoneId`」（`createplayerlogic.go:707-710`）。
- **不做**：角色列表刷新（角色列表直接读这一列，`PlayerViews.java:21`，就是基线刷新之后的结果，`RefreshRoleListDisabled` 不适用）；落点记录 `storage_id`（单库，inventory `data.md:119-130`
  已判不适用）；回填工具（列 NOT NULL，没有缺映射的存量号）。

### 3.3 读者清单与「不是归属区的 zone」

| 读者 | 读什么 | 时机 | 用途 | 失败方向 |
|---|---|---|---|---|
| login Login(48) / CreatePlayer(14) 应答 | `player.zone_id` | 每次 | 角色列表 `zone_id`，客户端据此选区 | 读不到行即失败（现状） |
| login EnterGame | 同一次 `findPlayer` 的行（`EnterGameHandler.java:200`） | 每次 | 合服围栏检查点 F2 的 zone（§3.5）；`RedirectOnEnter`（7.3） | 现状 |
| 组队 / 帮会 / 交易（`PlayerHomeZones`）；好友（`PlayerProfiles`） | `player.zone_id` | 每请求 | 区内隔离 | 现状不变 |
| 帮会合服闸 | `MergeFence` | 写 RPC | 14013 | 7.3 前恒放行 |

**不是归属区的 zone**（不要混用）：`game:location` 的 zone（所在 zone，GO-5 用）；`game:presence` 的 zone（gate 的 zone，推送寻址，`architecture.md:134-148`）；
`SessionContext.zone_id`（入口 zone）；审计流水 / 快照的 zone（所在进程 zone，同基线 `transaction_log_system.cpp:68`；Java `KafkaAssetAudit.java:37`）。
**存盘不按归属区路由**：单库，基线 CZ-2 / CZ-3 / 不变量 2「回家不回档」（`travel.md:31`、`:79-85`）在 Java 按构造成立，scene 不需要为存盘持有归属区（`RoutePlayerEvent.home_zone_id` 无对应物）。

### 3.4 传送时什么变、什么不变

| 状态 | 去程（A → B） | 回程 | 依据 |
|---|---|---|---|
| `player.zone_id` | **不变**（A） | 不变 | CZ-2 / CZ-9 |
| `player` 行归属三列与存档 | 交出事务写：冻结快照、E→E+1、`released = 1`；B 的登录夺权得 E+2 | 反向 | §5.2 |
| `game:location` | `o`@S(E) → 待落点 `l`{zone = B, 节点 0, conf}(E, q+1)，TTL = 票据剩余 → B 进场写 `o`@T(E+2, 1) | 反向 | §5.9 |
| `game:presence` | A 的 gate 解绑时比较后删除 → B 的 gate 进场成功后登记 | 同 | `architecture.md:136-143` |
| 会话 / 设备窗口 | A 的会话在客户端关旧连接后结束（login 只注销设备，`LoginClientMessageService.java:79-86`）；B 上是新会话 | 同 | — |
| 帮会 / 交易 / 组队 / 好友的归属判定 | 不变（读 A） | 不变 | CZ-6（`travel.md:35`） |
| 世界聊天 | Java 世界频道全服一条（`RedisKeys.chatWorldLog`），不受影响 | — | 基线按物理 zone，聊天批次的已有差异，本批不动 |

基线 travel_smoke T4「停留 35 s 不被误伤」（`travel_smoke_scenario.go:293-340`）守的是 home 区 30 s 断线租约到期后的收口；Java 没有会话登记的租约收口（`PARITY.md:91` ②），
位置记录按 (epoch, 序号) 只收更新的写，按构造不会误伤，robot 仍保留这一步当回归护栏（§11.11）。

### 3.5 合服围栏：检查点（本批恒放行）与 7.3 写侧约束

- **接口**：game-discovery 新增 `com.game.discovery.zone.ZoneMergeFence`（`boolean inProgress(int zone) throws Exception` 与异步版），本批只有 `ZoneMergeFence.OPEN`（恒 false）。
  7.3 换成读 Redis 的实现（建议键 `game:merge-fence:{zone}`，经 `RedisKeys` 生成，只看键在不在、读失败封锁、zone 0 放行，契约照 `router.go:73-109`），帮会 `MergeFence` 改为适配它。
  先例：帮会 4.4 同样「检查点照样保留、恒放行」（`MergeFence.java:18-31`）。
- **检查点**（7.3 之前客户端所见零差别）：

| # | 位置 | 判哪个 zone | 命中 / 读不到（7.3 起） | 与基线 |
|---|---|---|---|---|
| F1 | CreatePlayer，铸号之前 | 会话 zone（新角色的归属区） | 14 `{2020}` | 同 `createplayerlogic.go:257-268` |
| F2 | EnterGame 的落地路径，`assign` / 夺权之前 | `row.zone_id` | 26 `{error_message 3023}` | 基线 26 成功再推 23 {3023}；沿用 Java 进场拒绝的同步口径（`PARITY.md:27`） |
| F3 | EnterGame 的重定向路径 | 不查 | — | 同基线「只送连接不查」（`enterscenelogic.go:400-417` 注释） |
| F4 | 226 第一条腿：scene-manager `selectTravelTarget` 第 3 步 | 归属区（7.3 时请求加 `home_zone_id`，取自加载时的 `player.zone_id`）。**落地**：7.3 之前请求里没有归属区，`TravelRouter.fenced` 按围栏契约判 zone 0（= 不封锁）；7.3 接 Redis 围栏时必须同批改判 `home_zone_id` 并把 0 当读不到（zone-merge-spec §2.6），否则静默放行 | scene 留原地 + 23 {3027}（**落地**：内部码 `TravelRouter.TIP_ZONE_FENCED` = 3027，计 `game_scene_manager_travel_seconds{result="fenced"}`） | 同 `enterscenelogic.go:413` → `player_lifecycle.cpp:4362-4372` |
| F5 | 帮会写 RPC（事务外、事务内） | 调用者 / 帮会 zone | 14013 | 已有 |

- **一致性（D-H4，7.3 定稿）**：Java 的归属在 MySQL、围栏在 Redis，做不到基线在一段 Lua 里原子读 home 与围栏（`router.go:283-330`）。残余窗口：读到旧 `zone_id` 后合服整段恰好完成再读围栏。
  后果只有「这次登录用旧归属区判了围栏 / RedirectOnEnter」：存盘不按归属区路由，帮会 / 交易每请求现读。可以接受。
- **对 7.3 写侧的约束**（登记进 `roadmap.md:93`）：① 先立 src、dst 两个围栏；② 等宽限（> login 建角最长在途时间）；③ 预检两区无 `o` / `l` 位置记录、该区玩家全部 `owner_released = 1`；
  ④ `UPDATE player SET zone_id = dst WHERE zone_id = src`；⑤ 复核 `COUNT(*) WHERE zone_id = src = 0`（堵「围栏前已过 F1、围栏后才提交」的建角，对应基线 `router.go:156-158`
  的「游标后面」）；⑥ 撤围栏。

### 3.6 `RedirectOnEnter`

基线缺省关（`login.yaml:211`），`config.go:198` 注明依赖客户端实现，用途是合服后把仍从源区进来的玩家送去目标区。本批不做，随 7.3（X17，Q16）。实现时就是 §5.8 去向表 d 行的一条分支，
复用 `redirectToZone`，不需要新机制。PARITY 登记「Java 待做（随 7.3）」。

---

## 4 客户端可见行为

### 4.1 成功路径（Java，帧序）

```
旧连接 A：226 → 226{error_message{}}（≤ 2 s，组队读回来之后）
          → [旁人] 66（冻结前 stopMotion：移动中的传送者在旁人视野里停下，同 5.2）
          → [旁人] 51（源实例移除）
          → 124{target_ip, target_port, token_payload, token_signature, token_deadline}，id = 0
新连接 B：ClientTokenVerify(票据原样) → success → 48 Login（角色列表 zone_id 仍是归属区）→ 26 EnterGame{player_id}
          → 79(scene_id ∈ B) → 21(自己) → 47 → 66 …；[B 场景旁人] 21
旧连接 A：客户端关闭；不关则 60 s 后由 gate 关闭（期间一切请求无回包）
```

### 4.2 结局对照：基线与 Java

| 情形 | 基线 | Java 5.4 | 差异 |
|---|---|---|---|
| 同步拒绝 | 226 应答 3024 / 3007 / 3025 / 3026 / 13000 / 3014 / 3027 | 同码；3026 / 3027(组队读失败) 晚 ≤ 2 s；1006 / 实体无效不出现 | X4 |
| 目标 zone 不存在 / 没开 gate / 没开这张图 | `{0}` 后 23 {3027} | 同 | 无 |
| SM 不可达 | 没注册 SM：同步 3027（`player_lifecycle.cpp:2870-2873`）；传输失败：看门狗后 23 {3027} | `{0}` 后 23 {3027}（≤ 4 s） | X6（码同、时机不同） |
| 交出前失败 | 解冻留原地 + 23 {3027} | 同 | 无 |
| 交出后无法确认 | 23 {3027} + 34 {3027} + 断开，回选服 | 23 {3027} + 断开（不发 34）；被接管 23 {2017} + 断开 | X7 |
| 成功 | 226 `{0}` 与 124 顺序不定（124 可能先到，`inventory/scene-core.md:230`） | 226 应答**恒先于** 124 | X3 |
| 受理后很快失败 | 快路径上 23 {3027} 可能先于 226 `{0}`（§2.1，Unity 用代次吸收） | 23 恒在 `{0}` 之后（`{0}` 在进 RESOLVING 之前发出，同一链路） | X3（收敛为基线允许的一种） |
| 连发两条 226 | 第二条 13000，应答按发送顺序 | 第二条 13000 **可能先于**第一条的 `{0}`（§2.4） | X4 的副作用：按 `id` 配对即可，码相同 |
| 失败后立即再试 | Redis 故障时标记撤回未确认，最长约 305 s 内回 3014（§1.5 第 6 行） | 立即可再试 | 否（只在基线故障路径上出现） |
| 运维热关停 226 | 基线 226 走 C++ scene，Go 侧 killswitch 管不到 | gate 按方法查 `game:killswitch`，命中回**信封** 1003（`architecture.md:428-430`），不进 scene | Java 独有的运维能力；只在运维打开时可见 |
| 受理到出结论上界 | 约 71 s | 约 25 s（§5.12） | X12 |
| 受理后到冻结 | 受理即冻结 | 受理后到选中目标（≤ 4 s）照常可玩 | X5 |
| 推出 124 的旧连接 | 保持（哑连接） | 丢弃一切请求，60 s 后 gate 关闭 | X8 |
| 第二条腿持票者不符 | 26 {2011} | 同 | 无 |
| 第二条腿进场失败 | 26 成功 + 23 {3023} | 26 应答内 tip（既有差异 `PARITY.md:27`）/ 链路层失败 23 {3023}（`PARITY.md:36`） | 既有 |
| 他区同号 gate 验票 | `token not for this zone` | 同 | 无 |
| 落点坐标 | 目标图坐标合法就保留旧坐标（推导，5.2 B-7） | 换图落出生点、同图保留（`PARITY.md:91` ⑦、5.2 D10） | 既有 |

### 4.3 跨 zone 之后的重连（归属 A、正在访问 B）

| # | 情形 | 入口 | 基线 | Java 5.4 | 客户端可见差异 |
|---|---|---|---|---|---|
| R1 | 在 B 在线，换设备登录（顶号） | A 的 gate | 124 → B → 落 B 默认主世界人数最少频道；旧连接 34 {2017} | 26 成功 + 124 → B → 落 B **原实例**；旧连接 23 {2017} 后断开 | 落点实例（X18）；34 → 23（既有，`PARITY.md:37`） |
| R2 | 断线 ≤ 30 s 重连 | A 的 gate | 124 → B，同 R1 | 26 成功 + 124 → B 原实例 | 落点实例 |
| R3 | 断线 ≤ 30 s 重连 | B 的 gate | 落 B 默认主世界 | 落 B 原实例，无 124 | 落点实例（`PARITY.md:91` ⑥） |
| R4 | 断线 > 30 s / 主动登出 | A | 首登 → A | 同 | 无 |
| R5 | 同 R4 | B | 首登 → B（RedirectOnEnter 关） | 同 | 无 |
| R6 | 124 已发、未落地（待落点） | A | 不牵引，落 A，这次传送作废 | 同 | 无 |
| R7 | 同 R6 | B（普通令牌） | 落 B，用等待落点记的目标图 | 同 | 无 |
| R8 | 归属区 A 正在合服 | 任意 | 23 {3023} | 7.3 起 26 {3023} | 既有同步 / 异步差异 |
| R9 | B 的 gate 全挂、B 的 scene 还在 | A | 重定向失败 → 23 {3023} | 回落在 A 落地（夺权会请 B 让出） | X11 |
| R10 | B 整区已无 scene 节点 | A | 位置按没有处理 → 落 A（`playerLocationOwnerGone`） | 同（`redirectToZone` 回 TIP_NO_SCENE，login 回落） | 无 |

gateway 不按玩家选 gate：assign-gate 的 account 未经认证、Java 刻意不信（`game-gateway/src/main/java/com/game/gateway/assign/AssignGateService.java:34`）；客户端按角色列表 zone（= A）或 `CurrentZoneId` 选区，由 login 按上表纠正。

### 4.4 时序上界（必须满足的客户端预算）

- 服务端从受理到 124 或失败 tip 必须早于客户端「已受理交接」预算 75 s（`travel_freeze_cap.h:90`）：Java ≈ 25 s（§5.12）。
- 226 应答必须早于客户端 RPC 超时 15 s：Java ≤ 组队读 2 s。
- 第二条腿服务端（assign + 夺权 + 建链 + 加载）必须早于客户端等 79 的 60 s：Java ≈ 26 s。
- 124 的 `token_deadline` 与票据 `expire_timestamp` 相同（now + 300 s），由目标 gate 用服务端时钟判过期。

### 4.5 必须逐字节保持的契约面

- 消息号 226、124、23、34、48、26、79；字段号与类型见 §1.2。
- 226 应答总是带 `error_message`，受理时编码为 `0a 00`（Java 走 `ClientRequestHandler` 的 `tip(0)`，与 63 同形）。
- 同步码 3024、3007、3025、3026、13000、3014；受理后跨 zone 未成 3027；交出后被接管 2017；第二条腿持票者不符 2011。
- 124：`id = 0`；地址是**目标 gate 的通告地址**（`GateNodeInfo.client_host / client_port`，`node_directory.proto:14-16`；`PARITY.md:50`）；`token_deadline` = 票据 `expire_timestamp`。
- 票据：`zone_id` = 目标 gate 的 zone；`target_zone_id` = 目标 zone；`player_id` = 持票者；`hmac_session_key` 不填；hex 签名；TTL 300 s。
- 目标 gate 验票顺序与五条文案（§1.7）。2011 持票者绑定；「票据钉 zone 不弹回」优先于重连跟随。

---

## 5 Java 设计

### 5.1 思路

基线要冻结、存盘、写标记、过换手门、写等待落点、Kafka 推送、回滚，是因为「源已落盘」与「归属推进」分在两个系统。Java 的数据与归属在同一行，5.2 已经把它们合成一笔交出事务
（`scene-handoff-spec.md:712-725`）。跨 zone 与同 zone 跨节点只差一点：**客户端必须换连接、在目标 zone 重新 Login + EnterGame**（gate 只连本 zone 的 scene，`LinkHello` 带 zone、不符即拒，
`node_link.proto:24-46`；`architecture.md:113`）。所以 E+1 不需要等某个 gate 帧来认领，反而应当**无人持有**，让第二条腿的 login 用现成的夺权拿到 E+2：

> `UPDATE player SET <冻结快照>, owner_epoch = E+1, owner_released = 1, owner_lease_until = L_i WHERE player_id = ? AND owner_epoch = E AND owner_released = 0 AND owner_lease_until >= now + M`，
> 同一事务 upsert `player_state`（`saved_epoch = E`）。

- 提交即证明：冻结快照已落库；E 的一切迟到写都被现有围栏拒（在线存盘 / 续约要求 `epoch = E ∧ released = 0`，最终写回要求 `epoch = E`，`PlayerMapper.java:64-80`、`:126-145`）。
- 两条腿之间**没有持有者**，与基线等待落点同义（`enterscenelogic.go:331-337`）；旅途失败或放弃时从任何入口重登都立即夺到 E+2，不用等 30 s 租约。
- **为什么推进一代而不是在 E 上释放**：`updateStateAndRelease` 不要求 `released = 0`（`PlayerMapper.java:64-70`），在 E 上释放后，停服写回或存储层重试仍可在下一次夺权前落库；推进一代后全部被围栏拒。
- **为什么不保持 E+1 未释放（5.2 形态）**：第二条腿与「回家重登」都会撞 Held(E+1)，没人回应让出请求，要等 30 s 租约；5.2 否决「先释放再夺权」（`scene-handoff-spec.md:724-725`）
  是因为同 zone 改绑本不需要登录，而跨 zone 的第二条腿**本来就是一次登录**，释放窗口就是基线的等待落点窗口，期间别的设备登录照「后登录者赢」处理（§2.4）。

### 5.2 存储原语（game-player-store，5.2 原语加一个模式）

```java
// PlayerStore（5.2 已有 handOffOwnership，工作树 PlayerStore.java:381-413；加一个参数，结果类型不变）
public enum HandOffMode { HOLD /* 5.2：E+1 保持未释放 */, RELEASE /* 5.4：E+1 同时释放 */ }
public HandOffResult handOffOwnership(PlayerRow frozen, PlayerState state, long leaseUntil, long requireLeaseAtLeast,
                                      Duration timeout, HandOffMode mode);
```

```sql
-- PlayerMapper.updateStateAndHandOff（工作树 :90-100）：只把 owner_released 的取值改成参数
UPDATE player
   SET level = #{row.level}, scene_config_id = #{row.sceneConfigId}, pos_x = #{row.posX}, pos_y = #{row.posY}, pos_z = #{row.posZ},
       owner_epoch = owner_epoch + 1, owner_released = #{released}, owner_lease_until = #{leaseUntil}, updated_at = #{row.updatedAt}
 WHERE player_id = #{row.playerId} AND owner_epoch = #{row.ownerEpoch} AND owner_released = 0
   AND owner_lease_until >= #{requireLeaseAtLeast}
```

- **租约值照样写 L_i**（`now_i + 30 s`）：`released = 1` 时租约对夺权没有语义（夺权先看 `released`，`PlayerMapper.java:53-56`），只当「本次尝试」的标识。
- **重试改判与探测**（`StoragePlayerRepository`，5.2 机制，`scene-handoff-spec.md:780-790`）按模式判：

| 读到的 (epoch, released, lease) | HOLD（5.2） | RELEASE（5.4） |
|---|---|---|
| (E, 0, *) | NotCommitted | NotCommitted |
| (E+1, 0, ∈ {L_i}) | HandedOff(E+1) | Lost（不可能出现，fail-closed） |
| (E+1, 1, ∈ {L_i}) | Lost | HandedOff(E+1) |
| (≥ E+2, *, *) | Lost | **Superseded**：已交出且已被别的登录接管（§0.6 裁决 14） |
| 其余 / 截止 | Lost | Lost |

- 新结局 `HandOffOutcome.Superseded(OwnerState)` / `ProbeOutcome.Superseded`，只在 RELEASE 模式出现。
- **结局投递被拒**（逻辑线程已停）：5.2 由存储线程 `releaseOwnership(E+1)`（`scene-handoff-spec.md:789-790`）；RELEASE 模式已释放，什么都不做。
- 不改表结构，H2 上同样能跑（`AGENTS.md` §4）。

### 5.3 内部契约变更（Java 自有，不是同步产物）

game-api 的 proto 不 import 同步来的契约目录（`AGENTS.md` §1），所以 124 的五个字段在 Java 内部消息里复刻一份，由 gate 组装成契约类 `RedirectToGateNotify`。

| 契约 | 变更 | 说明 |
|---|---|---|
| 新文件 `game/api/zone_redirect.proto` | `message ZoneRedirect { uint32 target_zone_id = 1; uint32 gate_node_id = 2; string gate_host = 3; uint32 gate_port = 4; bytes token_payload = 5; bytes token_signature = 6; int64 token_deadline = 7; }` | 后五项一一对应 `RedirectToGateNotify{target_ip, target_port, token_payload, token_signature, token_deadline}`；`target_zone_id` / `gate_node_id` 只进日志与校验 |
| `node_link.proto` | `PlayerTransfer` 的 `reserved 7`（工作树 `:92-93`）改为 `ZoneRedirect redirect = 7;` | 有 redirect 时 `target_scene_node_id = 0`、`target_scene_id = 0`、`to_epoch = E+1`（已释放）；gate 推 124、不改绑、不 `abandonEnter`。两者都有 / 都没有 = 非法帧 |
| `client_call.proto` | `SessionContext` 加 `uint64 ticket_player_id = 8; uint32 ticket_target_zone_id = 9;`（现有到 7，`:10-21`） | gate 验票时从 payload 抄（对应 `client_message_processor.cpp:1095-1096`、`:128-129`）；普通令牌 `ticket_player_id = 0` |
| 同上 | `SessionDirective` oneof 加 `RedirectToGate redirect_to_gate = 5;`（现有到 4，`:46-53`），`message RedirectToGate { ZoneRedirect redirect = 1; }` | login GO-5；gate 先回应答、再推 124 |
| `scene_directory.proto` | `SelectTravelTargetRequest{uint32 from_zone_id = 1; uint32 to_zone_id = 2; uint64 player_id = 3; uint32 want_scene_config_id = 4;}` / `SelectTravelTargetResponse{uint32 tip_id = 1; uint32 scene_config_id = 2; ZoneRedirect redirect = 3;}`；`RedirectToZoneRequest{uint32 from_zone_id = 1; uint32 to_zone_id = 2; uint64 player_id = 3;}` / `RedirectToZoneResponse{uint32 tip_id = 1; ZoneRedirect redirect = 2;}` | `SceneDirectoryService` 加 `selectTravelTarget`、`redirectToZone`（异步，同 `selectSwitchTarget`，`SceneDirectoryService.java:20-25`）；7.3 给 `SelectTravelTargetRequest` 加 `home_zone_id`（F4） |
| `node_directory.proto` | `GateNodeInfo` 加 `uint64 started_at_ms = 8;`（现有到 7，`:10-20`） | gate 进程启动时刻；`GatePicker` 按客户端地址去重只留最新（Q10）；缺字段的旧条目当 0 |
| `ClientSink`（scene） | `playerTransfer(...)` 加 `ZoneRedirect redirect`（null = 改绑） | 5.2 签名见工作树 `ClientSink.java:40-41` |
| `SceneEventRouter`（gate） | 不加方法；`onPlayerTransferWithoutSession` 遇 redirect 只计 `orphan`、不 `abandonEnter` | 工作树 `ClientDispatcher.java:762-770` |
| `PlayerRepository`（scene） | `handOff(PlayerSave, HandOffMode, Consumer<HandOffOutcome>)` | 5.2 的 `handOff`（`PlayerRepository.java:45`）加模式 |
| `PlayerLocationDirectory` | `awaitPlacementAsync(PlayerLocation, long seq, Duration ttl)` | 写 `s = l`、TTL 由调用方给（§5.9） |
| `PlayerCall`（scene） | `defer()` → 一次性的 `DeferredReply`（逻辑线程上 `reply(...)`）；`ClientRequestHandler` 对已延迟的调用不补 1006 | 现在「必须同步回，否则补 1006」（工作树 `ClientRequestHandler.java:227-232`；`PlayerCall.java:44-55`） |
| `PlayerLocations`（scene 写口，评审补） | 新增 `awaitingPlacement(ScenePlayer removed, int zone, int sceneConfigId, Duration ttl, Consumer<Boolean> onDone)`：用 `removed.nextLocationSeq()`、epoch = `removed.ownerEpoch()` 写 `s = l`、节点号 0；`onDone` 由实现**投递回逻辑线程**再调用 | 现有写口全部「发出即忘、失败只记日志」（`PlayerLocations.java:6-8`），没有完成回调；「写完再发帧」（Q14）需要它。Redisson 回调线程上不得碰 `ScenePlayer` / 墓碑表 |
| `PlayerSwitch`（scene，评审补） | `SwitchReason {PLAYER, TRAVEL}`（5.5 追加 `EVACUATE`，§0.6 裁决 16）；`SwitchPhase` 加 `PRECHECK`（只给 TRAVEL）；TRAVEL 专属不可变字段 `toZone`、`wantConf`、`DeferredReply`，选中后的 `ZoneRedirect` 与解析后的 conf 记在 FREEZING 上 | 5.2 的 `PlayerSwitch` 只有 `RESOLVING` / `FREEZING`（工作树 `SceneWorld.java:863-875`） |
| 世界计数（scene，评审补） | `travelFramesPending`：交出提交且实例移除时 +1，帧写出（或确定不发：墓碑被消费 / TF7）时 −1 | 给 5.5 收敛谓词用（§0.5、TF16） |
| game-common `com.game.common.token.GateTokenIssuer`（从 game-gateway 下沉） | `issue(gateNodeId, zoneId)` 行为不变；新增 `issueRedirect(gateNodeId, gateZoneId, playerId, targetZoneId)`，常量 `REDIRECT_TICKET_TTL = 300 s` | 下沉后注明两种票据的区别（§1.4） |
| game-discovery `com.game.discovery.gate.{GatePicker, GateSource, RedisGateSource}`（从 game-gateway 下沉） | 行为不变 + 同地址去重 | 排空标记 `GateDrainMarks` 已在 game-discovery |
| game-discovery `com.game.discovery.zone.ZoneMergeFence` | 新接口，本批只有 `OPEN` | §3.5 |

### 5.4 scene-manager：`selectTravelTarget` 与 `redirectToZone`

**`selectTravelTarget`**（源 scene 调；只读，唯一副作用是签名；Dubbo 业务线程上阻塞读 Redis，同 `SceneAssigner`，提供方超时 3 s，`game-scene-manager/src/main/resources/application.yaml:94`）：

1. `to_zone_id == 0`、等于 `from_zone_id`、`player_id == 0` → `TIP_BAD_REQUEST`（3005，`SceneAssigner.java:49`）。scene 已先挡过，到这里是编程错误。
2. **地图预检**：`conf = want ≠ 0 ? want : WorldSceneConfigs.defaultConfigId()`；`want` 不是世界地图 → `TIP_BAD_REQUEST`（纵深防御，scene 已回 3007）。读目标 zone 的频道计划快照
   （`WorldChannelStore.snapshot(to)`，5.1）：有任一 `config_id == conf` 的记录（ACTIVE 或 DRAINING）就放行，没有 → `TIP_NO_SCENE`（3000，`:46`）。只看「这张图在这个 zone 开没开」、
   不解析、不预占，同 `rejectTravelToUnopenedMap` 口径：过渡窗口里频道暂时不可用照常放行，由第二条腿的 `SceneAssigner` 回落默认主世界（`SceneAssigner.java:89-98`）。
   **计划读失败 → future 异常完成**（X20）。
3. **合服围栏检查点 F4**：`ZoneMergeFence.OPEN`，7.3 接实现。
4. **选 gate**：`GateSource.listGates(to)` → `GatePicker.pick(to, …)`：剔除 zone 不符、节点号 0、没有客户端地址的条目；**同一客户端地址只留 `started_at_ms` 最大的一条**（新增，
   对齐 `clientendpoint.DedupeNewest`）；剔除排空中的（全部排空时忽略标记，`GatePicker.java:41-53`）；按（人数, 节点号）取最小（2026-10-18 更正：改为按人数随机二选一、同人数取第一次抽中的，对齐 mmorpg `shared/gatepick.TwoChoices`）。没有 → `TIP_NO_GATE`（scene-manager 内部码，只进日志与指标）。
5. **签票据**：`issueRedirect(gate.node_id, gate.zone_id, player_id, to)`：`{gate_node_id, zone_id = gate 的 zone, expire = now + 300, player_id, target_zone_id = to}`，
   **不填** `hmac_session_key`，`GateTokens.sign` 出 hex 签名。
6. 返回 `{0, conf, ZoneRedirect{to, gate.node_id, gate.client_host, gate.client_port, payload, signature, expire}}`。目录读不到 → future 异常完成（scene 推 3027）。

**`redirectToZone`**（login 的 GO-5 调）：参数校验同 1；**目标 zone 在节点目录里没有任何可用 scene 节点 → `TIP_NO_SCENE`**（等价基线 `playerLocationOwnerGone`，`enterscenelogic.go:299-310`；
否则会把人送到一个无法落地的 zone）；然后做第 4、5 步，**不查地图、不查围栏**（只送连接，同基线 `!leavingZone`）。

**为什么签在 scene-manager**：与基线同构（SM 签票，`gate_redirect.go:43-54`）；一次调用把地图预检、选 gate、签名做完；代价是密钥多分发到一个进程（Q4，备选 N3）。
scene-manager 新增必填密钥 `GAME_GATE_TOKEN_SECRET`，缺失即拒绝启动（`AGENTS.md` §3）。只用到 `game:nodes:gate:{zone}`、`game:nodes:scene:{zone}`、`game:gate-draining:*`、`game:world:{z:*}:ch`，
都经 `RedisKeys`，没有新键。

> **落地（2026-10-11，W3 `bca69b9`）**：实现是 `com.game.scenemanager.travel.TravelRouter`（`TravelRouting` 的真实现，`TravelConfiguration` 装配 `RedisGateSource`、`ZoneMergeFence.OPEN`、`GateTokenIssuer`），
> 提供方 `SceneDirectoryProvider` 原样委托。与上文的出入与规格没写死处的取舍：
> - **内部码**：`TIP_NO_GATE` = 3003（`kEnterNodeUnavailable`）、`TIP_ZONE_FENCED` = 3027（`kZoneTravelTargetBusy`，正是 scene 随后推给客户端的码）；scene 与 login 对任何非 0 一律同样处理，具体取值不影响它们。
> - 第 1 步多挡 `from_zone_id == 0`（proto 注释要求非 0）；第 2 步只认主世界种类的频道记录（`ChannelKinds.isWorldChannel`），状态限 ACTIVE / DRAINING；目标 zone 不存在时计划为空 → 3000（scene 推 3027，§0.6 裁决 13）。
> - 第 4 步的同地址去重按 `host:port` 原串、`started_at_ms` 按无符号比、缺字段当 0；**并列最大都留下**，交给 (人数, 节点号) 排序（2026-10-18 起改为交给随机二选一；没有依据判断哪条陈旧，保持去重前的行为）。`pick` 与 `eligible` 都经过它，
>   gateway 的 assign-gate 与登录排队的放行循环同时生效（`AssignGateService`、`QueueDispatcher`）；`QueueCapacity.budget` 仍按原始目录求人数和（见实现记录「遗留」）。
> - 目录 / 计划读失败与签名意外失败都以异常完成，异常只带概要（不外发 Redis 地址）；同步阻塞读、返回已完成的 future，构造后只读、线程安全。
> - 指标名按 scene-manager 的习惯写 `game.scene_manager.travel` / `game.scene_manager.redirect`（Prometheus `game_scene_manager_travel_seconds` / `game_scene_manager_redirect_seconds`），travel 的 `result` 多一个 `fenced`（§7.2）。
> - 密钥：与 game-gate / game-gateway 同一个键、同一种取法（`Environment` 读，缺失或全空白拒启，原值按 UTF-8 作密钥、不去空白）。部署模板 `deploy/compose/env.example` 的必填清单随合拢评审 `e7ff9a2` 加上 scene-manager（§5.14）。

### 5.5 scene：226 处理器与 TRAVEL 状态机（全部在场景逻辑线程）

**注册**：`register(ids.travelToZone(), TravelToZoneRequest.class, FreezePolicy.ALLOW, this::travelToZone)`（工作树 `ClientRequestHandler.java:120-131` 的注册段）。与 63 一样自己判在途、冻结闸放行。
注册后 226 不再走 `replyUnavailable` 的 1006（`:209-215`）。

**同步段**（顺序同 §1.5）：

| # | 判定 | 应答 |
|---|---|---|
| 1 | `target_zone_id == 0` 或等于本节点 zone | 3024 |
| 2 | `scene_config_id ≠ 0` 且不是 World 表地图（表空不拦） | 3007 |
| 3 | 战斗在途（6.3 钩子，现恒否） | 3025 |
| 4 | `switching ≠ null`：TRAVEL 任一阶段或 5.2 FREEZING → **13000**；5.2 RESOLVING（63 / 5.3 镜像取号在途）→ **3014**（5.2 `switchInFlight` 的过期作废照旧，工作树 `SceneWorld.java:863-875`） | 13000 / 3014 |
| 5 | 置 `PlayerSwitch{reason = TRAVEL, phase = PRECHECK, toZone, wantConf, deferred = call.defer(), deadline = now + team-check-timeout + 1 s 宽限}`，`TeamMembershipReader.readAsync(pid)`（`game-discovery/.../team/TeamMembershipReader.java:69`，其 javadoc `:14` 已写明「跨 zone 传送的在队即拒也用它」）**套 `orTimeout(game.scene.travel.team-check-timeout)`**（缺省 2 s；读者本身没有超时，只靠 Redisson 单命令最坏约 4.2 s，`architecture.md:607-609`），完成后投递回逻辑线程 | （延迟） |

**组队读回到逻辑线程**：先核对 `playersById.get(id) == player ∧ switching == 这次`，不符就丢弃（会话已走，不必回应答）。
- `teamId ≠ 0` → 清槽、回 **3026**；读失败 / 数据损坏 / 超时 → 清槽、回 **3027**（fail-closed，X4）。
- 不在队 → 回 `{0}`（**已受理**）→ `phase = RESOLVING` → `selectTravelTarget`（本地兜底复用 5.2 的 `switch-resolve-timeout` 4 s）。
- 第 4 步放在组队读之前，让 PRECHECK 期间的并发 226 回 13000、63 回 3014，等价基线「受理即冻结」挡住后来的请求。代价：已在途又在队时 Java 回 13000 / 3014、基线回 3026
  （同时满足两个条件的极窄窗口，只影响码、不影响状态）。
- **PRECHECK 槽的寿命与「一定回应答」**（评审补）：`switchInFlight` 现在只对 RESOLVING 做过期作废（工作树 `SceneWorld.java:863-875`），必须同样覆盖 PRECHECK。
  作废 PRECHECK 槽的一方（`switchInFlight` / 下一次 226）**先经槽上的 `DeferredReply` 回 3027 再清槽**（TF17）；之后迟到的组队结果因 `switching ≠ 这次` 丢弃，不会二次应答。
  `DeferredReply` 只在两种情况下作废、不回：实例已移除（会话已走），或已回过。这样 226 的应答在 `team-check-timeout + 1 s` 内必到，远小于客户端 RPC 15 s（R-T7）。
  **落地订正（2026-10-11 核对修正）**：「+ 1 s 内必到」写小了。正常路径由 `orTimeout` 保证约 2 s 内回；槽在 `team-check-timeout + 1 s` 时只是过期，回调丢失时要等下一次 226 / 63
  或每秒一次的 `refreshDueLocations` 经 `switchInFlight` 作废它才回 3027，所以兜底的上界是 2 + 1 + 至多 1 ≈ 4 s（与 §5.12 的落地行一致），仍远小于 15 s。

**选目标结果**（逻辑线程，先核对实例与令牌，同 5.2 `onSwitchTargetSelected`，工作树 `SceneWorld.java:897-924`）：

| 结果 | 动作 | 客户端 |
|---|---|---|
| 调用失败 / 超时 | 清槽 | 23 {3027}（基线把 SM 不可达也归 3027，`player_lifecycle.cpp:4360-4372`） |
| `tip ≠ 0` | 清槽 | 23 {3027} |
| 选中（`scene_config_id ≠ 0`、redirect 齐全） | **冻结**：`stopMotion` → `snapshot = toSave()` → FREEZING → `repository.handOff(snapshot, RELEASE, …)`（复用 5.2 `freeze`，工作树 `:969-982`） | 无 |
| 应答残缺 | 清槽、记 ERROR | 23 {3027} |

**FREEZING 的事件**：全部复用 5.2 的表（`scene-handoff-spec.md:844-861`），差别只有：RELEASE 模式下一律不调 `repository.release(E+1)`；未提交 / Lost 的 tip 换成 3027（2017 不变）。

| 结局 | pendingAbort | 动作 | 客户端 |
|---|---|---|---|
| `HandedOff(E+1)` | NONE | ① 快照比对，不同计 `post_freeze_mutation` → ② `removePlayer(player, false)`：旁人 51，不写回，不拍 LOGOUT（同基线 DestroyDeposedPlayer）→ ③ 留墓碑、`travelFramesPending++` → ④ `locations.awaitingPlacement(…)` 写待落点 (E, q+1)（§5.9）→ ⑤ `onDone` 回到逻辑线程后墓碑仍在（没被交叉的 `PlayerLeave` 消费）才 `sink.playerTransfer(…, redirect)`；返回 false / 异步写失败 → TF7；无论哪条出口都 `travelFramesPending--` | 本人随后收到 124 |
| `HandedOff(E+1)` | LEAVE(voluntary) | `removePlayer(false)`；`writeLeaveLocation`（E, q+1）写源场景墓碑或重连租约；不发帧 | 无（会话已走） |
| `HandedOff(E+1)` | TAKEOVER | `removePlayer(false)`；`kick(2017)` | 旧会话 23 {2017} |
| `Superseded` | 任意 | `removePlayer(false)`；会话还在则 `kick(2017)` | 23 {2017} 后断开 |
| `LeaseTooShort`；探测 `NotCommitted` | — | 原地解冻；有 pendingAbort 按现有离开 / 接管写回并释放 E | 23 {3027} |
| `Fenced` | — | `removePlayer(false)` + `kick(2017)` | 23 {2017} 后断开 |
| 探测 `Lost` | — | `removePlayer(false)` + `kick(3027)` | 23 {3027} 后断开（X7） |
| 实例已移出（`sw.detached()`：停服 / 被同节点新进场接替） | — | 只计结局（`left`），RELEASE 下不释放 | 无 |

- **TF7 收口**：位置以 (E, q+2) 写回**源场景的重连租约**（`zone = 本 zone`、源节点、源 scene_id、源地图，TTL 30 s，即 5.2 的 `locations.disconnected`），计 `link_gone`。链路已断，gate 会关会话
  （`onSceneLinkDown` → `SCENE_LINK_DOWN`，`inventory/gate.md:344-345`）；客户端重连回原场景原坐标，库里就是冻结快照（X15）。
- **墓碑**：只服务「`PlayerLeave` 与 `PlayerTransfer{redirect}` 在链路上交叉」（5.2 `consumeTransferTombstone`）；命中时按 voluntary 用 (E, q+2) 写源场景墓碑或重连租约，并且第 ⑤ 步不再发帧。
  **落地**：帧不发的这一支计 `game.scene.travel.frames.withheld`（合拢评审 D1，`b24582d`；不带标签、启动即注册）——只计帧发出**之前**的交叉；帧发出之后墓碑才被消费的，
  那一帧到 gate 时会话已离开，gate 已计 `redirects{travel, stale / orphan}`，scene 不另计（§7.3 第二条）。跨 zone 的墓碑（`TransferTombstone.forRedirect`）在帧还没定之前
  不过期、不随所在链路断开或停服清掉，帧的去留由待落点写完的回调裁决（链路已断就走 TF7）。
- **RESOLVING / PRECHECK 不冻结**（同 5.2，X5）：离场、断链、接管、失去归属照现有逻辑处理，迟到结果丢弃；组队跟随按 `switching ≠ null` 跳过；5.1 排空改派只跳过 FREEZING。
- **5.3 实例里发 226**：放行（Q13）。

> **落地（2026-10-11，W2 `83e884d` / `52d6875`，W1 `49d5086` / `f2c4264`）**：注册是 `register(ids.travelToZone(), TravelToZoneRequest.class, FreezePolicy.ALLOW, BattlePolicy.GATED, …)`，处理器 `SceneWorld.requestTravel`；
> 同步段第 3 步是 6.3 的真钩子 `inBattle()`（计 `game.scene.battle.gate.rejects{gate=zone_travel}`），之后多一步「没装配跨 zone 传送（`ZoneTravel.DISABLED`）→ 同步 3027、计 `travel.requests{unavailable}`」。
> 在队检查 `TeamChecks` 的生产实现是 `team.TravelTeamChecks`（读口 future 的副本套 `orTimeout`，在队 / 投影缺失 → IN_TEAM，无队 / 键缺失 → NOT_IN_TEAM，读失败 / 超时 / 数据损坏 / 同步抛出 → UNKNOWN，
> 经逻辑执行器恰好投递一次）；选目标 `TravelTargets` 的生产实现是 `transfer.SceneManagerSwitchTargets.selectTravel`。与上文的出入：
> - **RESOLVING 槽过期推 23 {3027}**（W2 评审意见 1，见 §2.3 TF17 后的落地注）；槽期限：PRECHECK = `team-check-timeout + 1 s`，RESOLVING = `switch-resolve-timeout + 1 s`，每秒维护再给至多 1 s。
> - 在队检查 / 选目标调用**同步抛异常**按失败收口（3027 / 23 {3027}），不靠分发处的 1006 兜底。
> - 选中结果在逻辑线程上再核对一遍完整性（纵深防御），并多查 `redirect.target_zone_id` 等于请求的目标 zone；`SceneManagerSwitchTargets` 在适配层已按同样的判据把残缺应答转成 Failed（§5.4 第 6 步）。
> - 选中时在战斗中 → 23 {3025}、计 `travel_resolves{in_battle}`（6.3 的复查点，原稿 §7.2 没列这个取值）。
> - 交出结局：`Superseded` 在交出与探测两处都踢 2017、计 `transfers{superseded}`；W1 的重试改判只在**有过结局不明的更早尝试**时才把 ≥ E+2 判成 Superseded，第一次尝试就读到仍是 Fenced（同样踢 2017）。
>   §5.2 表里「不可能出现」的两格（RELEASE 的 (E+1, 未释放, ∈ {L_i})、HOLD 的 (E+1, 已释放, …)）在交出改判里落成 Fenced，在探测里是 Lost。
> - `PlayerSwitch` 的判别字段是 `reason`（`SwitchReason {PLAYER, TRAVEL}`，5.5 追加 EVACUATE）；「怎么交出」仍由 5.2 的 `Purpose` 表达（TRAVEL 用 REMOTE_SWITCH）。
>   读口 `SceneWorld.travelFramesPending()` 与 gauge `game.scene.travel.frames.pending` 给 5.5 的收敛谓词。
> - 待落点写完的回调（`onPlacementWritten`）里墓碑已被消费 → 计 withheld、不发帧；写失败照发帧并计 `placements{failed}`；帧写不出 / 异步写失败（`onRedirectWriteFailed`）→ (E, q+2) 源场景重连租约、计 `link_gone`。

### 5.6 正常时序（Java）

```
client    gate G1(z1)                源 scene S(z1)                 scene-manager             MySQL            Redis            gate G2(z2) / login / 目标 scene T(z2)
226{2,c} ▶ forward ──CF──▶ 3024/3007/3025/在途 → PRECHECK；defer
                            组队读 ──────────────────────────────────────────────────────────────▶ game:{team}:player
                            ◀── 不在队 → 回 {0}；RESOLVING
 ◀ 226{0} ◀──── ToClient
                            selectTravelTarget ─Dubbo─▶ 计划 (z2,c)？挑 z2 gate（去重 / 剔排空）；签票据
                            ◀── {conf=c, redirect}
                            停下→快照→FREEZING→HANDOFF(RELEASE) ───────────────────▶ E→E+1, released=1, lease=L_i
                            ◀── HandedOff(E+1)
                            removePlayer(不写回) → 旁人 51；墓碑
                            待落点 (E,q+1) l{z2, node 0, scene 0, c} TTL≈300 s ──────────────────────▶ game:location
                            ◀── 写完
          ◀ PlayerTransfer{E→E+1, redirect}（同一链路，排在 226 应答之后）
 G1：核对 (S, gen, pid, E) → unbindScene（不发 PlayerLeave，撤在线登记）→ REDIRECTED → 推 124 → 挂 60 s 收口
 ◀ 124
  探测 G2 → 新连接 ClientTokenVerify(票据) ──────────────────────────────────────────────────────▶ G2：gate 号 / target_zone=z2 / 未过期；存 ticket_player / ticket_zone
  关旧连接 → G1 sessionClosed → login 注销设备窗口
  48 Login ──────────────────────────────────────────────────────────────────────────────────────▶ login（角色列表 zone_id = z1）
  26 EnterGame{pid} ─────────────────────────────────────────────────────────────────────────────▶ login：持票者 = pid？钉 z2；位置 = z2 待落点 c
                                                ◀── assign(z2, preferred_conf = c)
                                                                         claim（released=1 → E+2，一次成功）
 ◀ 26{player_id} ◀──────────────────────────────────────────────────────────────────────────────── G2 ── PlayerEnter{E+2} ──▶ T：加载 → 79/21/47；位置 (E+2,1) o；LOGIN 快照
```

第二条腿是一次真实的 EnterGame，T 照常拍 LOGIN 快照，同基线 B 侧建实体时拍（`player_lifecycle.cpp:2425`）。

### 5.7 gate（会话 EventLoop 上）

- **握手**（`ClientDispatcher.onTokenVerify`，工作树 `:179-205`）：校验通过后把 `payload.player_id`、`payload.target_zone_id` 存进 `ClientSession.ticketPlayerId / ticketTargetZoneId`；
  已验证的会话再次 verify 不覆盖。`context(s)`（`:1005-1015`）填 `SessionContext` 8 / 9 号字段——所有转 login / 后端域的调用都走它，同基线「两种转发模式同一个 BuildSessionDetails」
  （`gate.md:330`）；`sessionlessContext`（`:1018-1025`）不带票据（只用于 `abandonEnter` 打日志）。gate 只存不判：gate 要到绑定会话才知道 player_id，在 gate 拒只会在 login 留残留（`gate.md:335`）。
- **`onPlayerTransfer` 的 redirect 分支**（在 5.2 工作树 `:721-760` 的现有判定之前按 `hasRedirect()` 分流）：
  1. 过期判定照 5.2：会话已关 / 正在关，或 (节点, 代次, player_id, from_epoch) 对不上 → **只计 `stale`、不 `abandonEnter`**（E+1 已释放）。
  2. 校验：`gate_host` 非空、`1 ≤ gate_port ≤ 65535`、`token_payload` / `token_signature` 非空、`target_scene_node_id == 0`、`to_epoch > from_epoch` → 不过：计 `redirects{invalid}`，
     `unbindScene` 后推 23 {3027} 并断开（对应基线隐患「空 payload 兜底会推出空地址的 124」，`gate.md:321`）。
  3. `unbindScene(s)`：不发 `PlayerLeave`（源已移除实例）、撤在线目录（工作树 `:921-932`）。
  4. `s.redirected = true`；丢弃排队中的请求；组装 `RedirectToGateNotify` → `MessageContent{message_id = 124}`（**不设 id**）下发。消息号经 `MessageIdRegistry` 取
     `SceneClientPlayerCommon.RedirectToGate`，同 `GateNode.java:182` 取 23 的做法。
  5. 挂 `game.gate.redirect-linger`（缺省 60 s）定时器：到点会话还在就直接关闭（`DisconnectReason.REDIRECT_LINGER`，**不推任何 tip**：客户端此刻要么已在新连接上，要么根本没实现 RedirectFlow）；
     客户端先关则定时器作废。定时器任务只捕获会话号，到点先查会话仍在、仍是 REDIRECTED 再关（AGENTS.md §3 回调生命周期）。
- **`RedirectToGate` 会话指令**（login 的 GO-5，`applyDirectives`，工作树 `:483-496`）：先回 EnterGame 应答（`replyToClient` 先于 `applyDirectives`，`:454-455`）→ 执行 2、4、5 步
  （会话本就没有场景绑定）。迟到的应答（会话已断，`recordIdentity`）忽略该指令。
- **REDIRECTED 会话**：之后的一切客户端请求静默丢弃（计 `RequestResult.REDIRECTED`），不走「未进场景推 23 {1003}」（`:588-592`；2026-10-15 起这一支改为回带请求 id 的信封 1003，mmorpg client-request-ordering.md O6，REDIRECTED 会话同样不走）。断线照常：`sessionClosed` 通知 login 注销设备窗口；
  没有场景绑定，不发 `PlayerLeave`。
- **REDIRECTED 之前已转出、之后才回来的调用**（评审补；例：传送提交时客户端恰好发了 17 LeaveGame）：login / 后端的应答**照常下发**（`replyToClient`，合规客户端已在换连接，无害），
  应答里的会话指令**一律忽略**（`applyDirectives` 遇 REDIRECTED 直接返回：会话已没有场景绑定，`UnbindPlayer` / `EnterScene` 都不能再生效；`CloseSession` 由收口定时器覆盖）。
  发往源 scene、提交后才到的 `ClientForward` 在 scene 侧按「玩家不在」丢弃（§5.10），不会有应答。交叉的 17 在 scene 侧由 FREEZING 的 `pendingAbort = LEAVE(voluntary)` 或墓碑接住（§5.5）。
- **路由层**：`SceneEventRouter` 找不到会话时 redirect 帧只计 `orphan`。

> **落地（2026-10-11，W4 `de19d45`；先行件 c `f7143b0`）**：`ClientDispatcher.onPlayerTransfer` 先按 `hasRedirect()` 分流到 `onTravelRedirect`，login 的指令走 `redirectByLogin`，两者共用 `invalidRedirect` 与 `enterRedirected`；
> 收口定时器 `onRedirectLingerExpired` 只捕获会话号、到点重新查表；路由层是 `onPlayerTransferWithoutSession`。124 的消息号由新记录 `PushMessageIds`（23 与 124，按名字从契约解析）带进会话层；
> `game.gate.redirect-linger` 在 `GateLimits` / `GateProperties` 里校验 ≥ 15 s。规格没写死处的取舍：非法重定向（两种来源）的断开原因计 `disconnects{transfer_failed}`，来源由 `redirects{source, invalid}` 区分；
> 已重定向的会话再次 `ClientTokenVerify` 也静默丢弃（未重定向的已验证会话仍按基线回成功、不覆盖票据字段）；转入 REDIRECTED 时排队中的请求计请求去向 `redirected`（不是 dropped）；
> 已重定向会话上又来的、或关闭途中遇到的 `RedirectToGate` 指令计 `redirects{login, stale}`，保持「每条重定向指令恰好计一次」；没装配 124 消息号（只收 tip 号的旧构造器）时一律按内容非法收口，绝不发消息号为 0 的包；
> 防御：login 若对仍挂着场景的会话下发重定向（不会发生，EnterGame 对这种会话回 2028），先按断线离场再重定向；`gate_host` 用 `isBlank` 判空。login 来源的指令内容非法时，EnterGame 应答已先发出，再推 23 {3027} 并断开。

### 5.8 login

`EnterGameHandler.handle`（工作树 `:172-197`）的新顺序：

1. `session_id == 0` → 2018（现状）。
2. **[新] 持票者校验**：`ticket_player_id ≠ 0 ∧ ≠ request.player_id` → **2011**，在 2028 判定、设备续期、在途闸门之前（同基线，§0.6 裁决 9）。
3. 没登录 / 已在游戏里 → 2028；设备续期；在途闸门 2005；角色归属校验 2011（现状，`:176-204`）。
4. **去向**（替换 `applyLocation`，`:221-241`）——纯函数 `resolveEnterRoute(G, TP, TZ, L)`，可表驱动测试：`G = session.zone_id`；`pinned = TP ≠ 0 ∧ TZ == G`（X13）；
   `L = locations.find(pid)`（`s ∈ {o, l}` 才有值；节点号 0 = 待落点；Redis 出错 = ERROR）。

| 位置记录 L | pinned | 未钉 |
|---|---|---|
| 没有 / 墓碑 | 本 zone 首登（默认主世界） | 同左（RedirectOnEnter 随 7.3） |
| 本 zone、节点号 ≠ 0（在线 / 断线租约） | 回原实例（`PARITY.md:91` ⑥） | 同左 |
| 本 zone、节点号 = 0（待落点） | `preferred_scene_config_id = L.conf`，不带实例 | 同左（同基线 zone 对上、请求没指定地图即采用，`enterscenelogic.go:439-444`） |
| 别的 zone、节点号 ≠ 0 | 本 zone 首登（票据优先、不再弹走） | **GO-5 重定向**：`redirectToZone(L.zone, pid)` → 回 `EnterGameResponse{player_id}`（不设 `error_message`）+ `RedirectToGate` 指令；**不夺权、不分配**。调用失败 / 超时 / `tip ≠ 0` → 退化为本 zone 首登（X11） |
| 别的 zone、节点号 = 0（别处的待落点） | 本 zone 首登 | 本 zone 首登（等待落点不牵引，`enterscenelogic.go:343-356`） |
| 读失败 | 按存档地图（现有退化，`:225-228`）；注意第二条腿上「存档地图」是**源 zone** 的冻结地图，B 没开这张图时 `SceneAssigner` 回落默认主世界——Redis 故障时目标地图丢失，只影响落点 | 同左 |

5. 落地路径：合服围栏检查点 F2（`OPEN`）→ 分配 → 夺权 → `rerouteIfStale` → `accepted`（现状 `:244-403`）。第二条腿上 `owner_released = 1`，夺权一次即成（E+2）。
   `rerouteIfStale` 不改：只在路由用了原实例时复查，且只认 `zone == G` 的记录（`:336-350`）；夺权后出现的别 zone 记录一律忽略（已持有归属，不再重定向）。
6. `LoginClientMessageService.handle`：回复里带 `RedirectToGate` 指令时与 `EnterScene` 一样注销设备窗口（`:64-71`）。

- **一次动作最多 1 跳**：GO-5 / 传送的票据都钉了目标 zone，到达后必然命中 pinned 列；pinned 永不重定向。比客户端的 3 跳熔断更强。
- **基线依据**：基线 ShortReconnect / ReplaceLogin 来自会话登记（`entergamelogic.go:664-668`）；Java 没有会话登记（`PARITY.md:91` ①），等价判定来自位置记录的 `l` / `o`，客户端所见相同。
- **`CreatePlayer`**：归属区 = `session.zone_id`，为 0 回 2020；合服围栏检查点 F1（`OPEN`）在铸号与事务之前（§3.2）。

> **落地（2026-10-11，W5 `bdbb7bd` / `89d7f3b`；X16 `0765a18`）**：去向表是纯函数 `EnterRoutes.resolveEnterRoute(G, TP, TZ, L)`（替换原来的 `applyLocation`），输出 `Land`（带 zone 恒为 G 的分配请求）或 `Redirect`（GO-5，同时带一个本 zone 首登的回落请求）；
> GO-5 经 `callSceneManager` 异步调 `redirectToZone`，完成后 `handleAsync` 回工作线程。与上文的出入与规格没写死处的取舍：
> - **时间预算 `game.login.enter-game-budget`**（缺省 4 s，`EnterGameHandler.DEFAULT_ENTER_BUDGET`，必须为正；W5 评审意见 1）：从 `EnterGameHandler.handle` 起算，整条链按剩余预算收紧——签票、分配、夺权后重新分配的本地兜底取
>   `scene-assign-timeout` 与剩余预算中较小的，预算用完就不再发起调用（按调用超时，回 3023）；夺权截止取 `owner-claim-wait` 与预算截止中较早的，分配回来时预算已用完就不夺权、不请持有者让出（回 2005）；
>   夺到归属时预算已用完就不再复查位置。原因：GO-5 回落 = 签票 + 分配 + 夺权可能超过 gate 调 login 的 5 s，超时后迟到的应答被 Dubbo 消费端丢弃（到不了 gate），那时夺到的归属无人接、玩家要等 30 s 租约；
>   普通路径「分配 3 s + 夺权 3 s」的同类残余一并收掉。预算不含派发层工作队列的排队时间（残余，见实现记录「遗留」）。
> - 指标口径：`route=pinned` 只在「钉住且记录在别的 zone 的节点上」（本该 GO-5、被票据挡住）时计，钉住时其余格按记录计 first / resume / awaiting / location_error；签票耗时计进现有 `game_login_backend_calls_seconds{backend=scene-manager, method=redirectToZone, result}`；
>   签票回来时工作队列已满（链以异常结束）的那一次不计 `enter_routes`，计在 `game_login_requests{result=internal_error}`。
> - GO-5 成功前的完整性校验多一条 `redirect.target_zone_id` 等于请求的 zone，端口按 uint32 判越界；位置记录的 zone 为 0（不该出现）按本 zone 首登，不往 zone 0 签票。
> - 第 5 步「`rerouteIfStale` 不改」照做：它只认 `zone == G` 的记录，别 zone 的记录当作没有，于是夺权后读到别 zone 的记录时**按首登在本 zone 重新分配一次**（与登出墓碑同一条路）——不重定向、不签票、只夺一次权。
>   §11.7 原写「不触发重新分配」与此字面冲突，已改为「不触发重定向」（W5 偏离 1，评审认为现实现与本节一致）。
> - F2 在落地路径（含 GO-5 回落）上、分配之前，判角色行的 `player.zone_id`，命中 / 读不到（含中断，保留中断标记）→ 26 `{3023}`；`LoginConfiguration` 把 X16 注册的 `zoneMergeFence` bean 注入进游戏 bean。

### 5.9 位置记录：待落点（sm-player-location 的 5.4 部分）

- **形状**：`game:location:{pid}` = `{e = E, q = q+1, s = l, v = PlayerLocation{player_id, zone = 目标, scene_node_id = 0, scene_id = 0, scene_config_id = SM 解析后的地图, owner_epoch = E}}`，
  TTL = `token_deadline − now`（截到 [1 s, 300 s]），取代基线 `awaitingPlacementExpired`（`enterscenelogic.go:945-947`）。
- **写者**：源 scene，在交出提交之后、发 `PlayerTransfer` 之前写，**等写完（或失败）再发帧**（Q14）。用 E 与续接的序号写，5.2 不变量 J5「只由持有者以自己写过的 epoch 写」保持
  （`scene-handoff-spec.md:686`）。之后 T 以 E+2 写 `o` 一定更新（`WRITE_IF_NEWER`，`PlayerLocationDirectory.java:66-87`）。
- **对 5.2「提交后只补写一次」的扩展**（评审补；5.2 原文 `scene-handoff-spec.md:1001-1002`）：旅途提交后源节点至多写两次，都用 E：先 (E, q+1) 待落点，之后只在 TF7 或交叉离开时再写 (E, q+2)。
  两次都经同一个 `ScenePlayer.nextLocationSeq()`（实例虽已移除，对象由墓碑持有，序号在逻辑线程上单调），所以乱序到达也按 (E, 序号) 收敛到后一次；E+2 的任何写都覆盖它们。
  「写失败」可能是超时后服务端仍执行了的写：它要么被后来的 (E, q+2) / (E+2, 1) 覆盖，要么就是本该存在的待落点，都无害。
- **读者不改**：login `find` 对 `l` 有值，按节点号 0 判待落点（§5.8）；资产通道 `findHolderAsync` 对 `l` 回 RECONNECT_LEASE → 调用方合成 NOT_HERE、稍后重投（`:317-319`；
  `SceneAssetLocator`）；组队 `statusesAsync` 回 RECONNECT_LEASE（传送者按规则不在队，只影响别人看他的在线态）。
- **状态迁移**（每行按 (epoch, 序号) 只收更新的写）：

| 时刻 | 记录 |
|---|---|
| 旅途前 | `(E, q) o @S` |
| 交出提交 + 写待落点 | `(E, q+1) l @z2 节点 0`（TTL ≈ 300 s） |
| 改绑帧没写出 / 交叉的离开 | `(E, q+2) l @S`（30 s）或 `(E, q+2) x` |
| 第二条腿进场 | `(E+2, 1) o @T` |
| 旅途放弃，TTL 到期 | 无记录 → 任意 zone 首登 |

> **落地（2026-10-11，先行件 a `ffb6168`，W1 `49d5086`）**：底层是 `PlayerLocationDirectory.awaitPlacementAsync`（同一段 `WRITE_IF_NEWER` 脚本，TTL 上限 `AWAIT_PLACEMENT_MAX_TTL` = 300 s，与 `GateTokenIssuer.REDIRECT_TICKET_TTL` 同值；
> 不合法的参数在发命令之前拒）；scene 写口 `RedisPlayerLocations.awaitingPlacement` 取 `(removed.ownerEpoch(), removed.nextLocationSeq())`，TTL = `token_deadline` 减本地墙钟、截到 [1 s, 300 s]，
> Redisson 回调线程上只做投递（只捕获玩家号），`onDone` 经逻辑执行器恰好一次；没生效 / Redis 失败 / 目录当场拒参数都回 false，投递被拒（逻辑线程已停）丢弃、回调在哪条线程上都不执行。
> 指标 `travel_placements{written / failed}` 由 world 在 `onDone` 里计（适配层不计，免得重复）。`removed` / `ttl` / `onDone` 为 null 是编程错误，当场抛 NPE。

### 5.10 冻结闸与客户端请求

- 复用 5.2 §5.9（`scene-handoff-spec.md:920-938`）：只在 FREEZING 生效，PRECHECK / RESOLVING 不冻结。冻结中再发 226 回 13000、63 回 3014；资产通道 RETRY 27003。
- 交出提交后实例已移除：`PlayerTransfer` 到 gate 之前发到源节点的请求按「玩家不在」静默丢弃；gate 进入 REDIRECTED 后在 gate 侧丢弃。

### 5.11 线程所有权

- **scene**：226 处理器、PRECHECK / RESOLVING / FREEZING 状态机、墓碑、`DeferredReply`、`travelFramesPending`、发帧都在逻辑线程；组队读（Redisson 线程完成）、选目标（Dubbo 线程完成）、
  写待落点（Redisson 线程完成）都是异步，回调**只做投递**、回到逻辑线程再读写 `ScenePlayer` / 墓碑表；投递被拒（逻辑线程已停）时什么都不做（归属已在库里定案，TF16）。
  交出 / 探测在存储线程池（`architecture.md:548`）。
- **gate**：票据字段、REDIRECTED、124、收口定时器都在会话 EventLoop；链路 I/O 线程只做投递（`SceneEventRouter`）。
- **login**：工作线程；`redirectToZone` 异步 Dubbo，完成后回工作线程（同 `callAssign`，`EnterGameHandler.java:244-255`）。
- **scene-manager**：Dubbo 业务线程上阻塞读 Redis（同 `SceneAssigner`）。

### 5.12 超时预算

| 段 | 上限 | 出处 / 理由 |
|---|---|---|
| 226 应答 | 同步段 + 组队读 ≤ 2 s | 客户端 RPC 15 s（转述，`travel.md:1440`） |
| 选目标 | Dubbo 3 s，本地兜底 4 s | 同 5.2（`scene-handoff-spec.md:987`） |
| 交出 + 探测 | ≤ M（15 s） | 同 5.2 §5.13 |
| 写待落点 | Redis 单命令最坏约 4.2 s | `architecture.md:607-609` |
| **受理 → 124 或失败 tip** | ≈ 4 + 15 + 4.2 ≈ 23 s（加 226 应答前的 2 s 约 25 s） | < 75 s（`travel_freeze_cap.h:90`）；基线最长 71 s（`:71`、`:78`） |
| 票据 / 待落点 | 300 s | `gate_redirect.go:27` |
| 第二条腿（服务端） | assign 5 s + 夺权 ≤ 3 s + 建链 3 + 握手 5 s + 加载 ≤ 10 s ≈ 26 s（**落地**：login 一侧的 assign + 夺权受 `game.login.enter-game-budget` 4 s 约束，见下一行） | `game-login/src/main/resources/application.yaml:88-95`；`scene-handoff-spec.md:992-993`；客户端等 79 60 s（转述） |
| **落地**：一次 EnterGame 在 login 里的总时长 | `game.login.enter-game-budget` 4 s（签票 / 分配 / 夺权都按剩余预算收紧，GO-5 回落 = 签票 + 分配 + 夺权也不超过它） | 必须小于 gate 调 login 的 Dubbo 超时 5 s（`game-gate` 的 `dubbo.consumer.timeout`）：gate 到点推 23 {1003} 之后，迟到的应答被 Dubbo 消费端丢弃、到不了 gate（W5 评审意见 1） |
| **落地**：226 应答 / 受理后到结论的槽期限 | PRECHECK 槽 `team-check-timeout + 1 s`、RESOLVING 槽 `switch-resolve-timeout + 1 s`，每秒维护再给至多 1 s | 槽过期由作废方给 3027（§2.3 TF17 后的落地注）；合并后评审按真实配置算出 226 应答最坏约 4 s、受理到 124 或 23 约 25 s |
| REDIRECTED 收口 | 60 s | > 探测 5 s + 验票 10 s（`redirect.go:73-75`；`travel.md:1438`） |

### 5.13 多 zone 本机切片

`tools/local/start-slice.sh` 加 `GAME_ZONES`（1 或 2，缺省 1，行为与现在相同）。等于 2 时多起 zone 2 的 gate 与 scene；其余进程共用（都不分 zone，或按会话 / 归属区取 zone）。

> **落地状态（2026-10-08）**：本小节的**切片脚本部分已由批次 6.5（观战与跨区 1V1）提前落地**——6.5 的 robot 场景 `battle-cross-zone` 需要两个区，而 5.4 还没有实施；落地的只有脚本与 robot 的 `--visit-zone` 选项，**不含 5.4 的任何生产代码**
> （226 / 124、GO-5、X16 都还没有）。实际形态与下表有三处出入，以脚本与 spectate-spec §10.6 为准：
> 1. **game-gateway 的命令行不带播种参数**（下表 game-gateway 一行的 `seed-zones[0] / [1]` 没有照做）：本机 JDK 启动器按 ANSI 代码页取命令行，中文区名到进程时已是「??」，而播种只在库里没有这个区时写、写了不再改。
>    区 2 在区服目录里的那一行改由脚本经 game-data 的运维接口管，**状态跟随 `GAME_ZONES`**：=2 → `POST /admin/zones/2/open`（回 404 就 `POST /admin/zones` 建区，名字「二区」、`sort_order` 2）；
>    =1 → `POST /admin/zones/2/maintenance`（回 404 就什么都不做）——免得单 zone 切片上留下一个没有 gate 的 OPEN 区。脚本等 `GET /api/server-list` 反映出来（=2 时要区 2 是 OPEN 且带 `load_level`）才报「全部就绪」。
> 2. `stop-slice.sh` 的清单行没有加名字（game-gate 的 `LocalSliceOrderTest` 逐项钉着），区 2 的两个实例在循环体里紧挨同类先停：game-gate-z2 在 game-gate 之前、game-scene-z2 在 game-scene-2 之前；它不读 `GAME_ZONES`，只看 PID 文件。
> 3. 第三个脚本 `battle-crash-window.sh` 也同步了（它与 `start-slice.sh` 的函数、端口表逐字钉着）：故障变体只在区 1 上做，robot 要登录的区不是 1 时拒绝。
>
> 端口与关键参数同下表：game-scene-z2 链路 21010 / 资产通道 21110 / 管理 18115（另带 `--game.scene.scene-manager-url`，等区 2 的主世界频道铺好），game-gate-z2 客户端 11010 / 管理 18123；两者的节点号按 zone 租约也是 1 号。
> `GAME_ZONES` 只有 `start-slice.sh` 读，其它值退出码 1；可与 `GAME_SCENE_NODES=2` 同用。单 zone 13 个进程，双 zone 15 个。
> 「顺带解锁」的组队 X1 / X2 与帮会第 4 步的跨区步骤**没有**随 6.5 打开，仍归 5.4。X16 合入之前，双 zone 切片上从区 2 进来的新号归属区会记成 1（下表 game-login 一行）。
>
> **落地状态（2026-10-11）**：5.4 的生产代码已全部在 main（X16 `0765a18`，其余见文首状态）；切片脚本没有为 5.4 再改（`start-slice.sh` 早已要求并导出 `GAME_GATE_TOKEN_SECRET`，所有进程继承同一环境，scene-manager 拿得到）。
> robot 的跨区步骤由 `--cross-zone auto / require / skip` 管（team X1 / X2、guild 第 4 步、reconnect 的跨区重连；trade 的跨区步骤**没有接入**，`trade --cross-zone require` 是参数错误）。
> **双 zone 切片上 5.4 的任何场景都还没有跑**（§11.9、§11.11）。X16 之前在双 zone 切片上从区 2 建出来的「归属区 = 1」的本机数据不清理，验证一律用新的账号前缀。

| 进程 | zone | 端口（客户端 / 链路 / 资产 / 管理） | 关键参数 |
|---|---|---|---|
| game-scene-manager | 共用 | Dubbo 20882 / 管理 18102 | 继承 `GAME_GATE_TOKEN_SECRET`（脚本已要求，`:23`）；领导者按 `game:world:zones` 自动竞选 zone 2（`RedisKeys.java:279`） |
| game-login | 共用 | 20881 / 18101 | 依赖 X16（否则 z2 建的角色记成 1） |
| game-gateway | 共用 | 18081 | `seed-zones` 必须 **[0] 与 [1] 一起**在命令行给（Spring 列表跨来源不合并）：`--game.gateway.seed-zones[0].zone-id=1 …[0].name=一区 …[0].status=OPEN … --game.gateway.seed-zones[1].zone-id=2 --game.gateway.seed-zones[1].name=二区 --game.gateway.seed-zones[1].status=OPEN --game.gateway.seed-zones[1].sort-order=2` |
| game-gate | 1 | 11000 / — / — / 18103 | 不变 |
| game-gate-z2 | 2 | 11010 / — / — / 18123 | `--game.zone-id=2 --game.gate.client-port=11010 --server.port=18123`；节点号也会是 1（按 zone 租约），用来测 MZ7 |
| game-scene（+ 可选 game-scene-2） | 1 | 21000 / 21100 / 18104（21001 / 21101 / 18114） | 不变（5.2 的 `GAME_SCENE_NODES`，`:72-103`） |
| game-scene-z2 | 2 | 21010 / 21110 / 18115 | `--game.zone-id=2 --game.scene.link-port=21010 --game.scene.asset-rpc-port=21110 --server.port=18115 --game.scene.scene-manager-url=…`；等 zone 2 的 `game_scene_channels{state="active"} ≥ 1` |
| friend / chat / team / guild / trade / data / battle / **match** | 共用 | 不变 | 不分 zone。game-match（6.4；Dubbo 20888 / 管理 18113）全服一份：两个区的 gate 都用缺省的 `game.dubbo.match-url` 指向同一个 game-match（spectate-spec §4.10） |

- 已占用 / 已规划的管理端口：18081、18101–18112（18105 是 game-gateway 管理口、18112 是 6.2 的 game-battle）、18113（6.4 game-match，`match-spec.md:35`、`:916`）、18114（game-scene-2）；
  5.1 规划了 18124 / 21002。zone 2 用 18123 / 18115、21010 / 21110、11010。
- **启动顺序**：scene-manager → login → 其余 Dubbo 服务 → data → zone 1 的 scene → zone 2 的 scene（各自等频道）→ 两个 gate → gateway。`stop-slice.sh` 同步加 `game-gate-z2` / `game-scene-z2`。
- 顺带解锁：组队 X1 / X2（`TeamScenario.java:66`）与帮会第 4 步（`GuildScenario.java:72`、`:297`）的跨区步骤——只在有第二个区时执行，单 zone 照旧跳过。

### 5.14 需要同步修改的文档与登记

- **`architecture.md`**：§2 scene-manager 加「跨 zone 选 gate、签票据」；§4.1 `RedirectToGate` 指令与 `SessionContext` 票据字段；§4.2 `PlayerTransfer.redirect`；§4.3 注明 124 不经 `PlayerPushes`；
  §7 归属协议加「交出并释放」、位置记录加待落点、归属区来源；§8 登录链加票据、GO-5、跨 zone 第二条腿；§10 删掉「跨 zone」（`:740`）；§11 新指标。
- **`PARITY.md`**：新增「跨 zone 传送（226）与重定向（124）」行（写明 §9 的 X1–X22）；新增「归属区映射」行（`player.zone_id`，`CreatePlayer` 取会话 zone）；新增「合服围栏」行
  （Java 待做，随 7.3；含 `RedirectOnEnter`）；更新 `:25`（gate 目录同地址去重）、`:27`（不再「不跨 zone」）、`:28`（重定向已做）、`:50`（重定向通告地址已做）、`:59`（124 不经推送通道）、
  `:91` ④（改为 GO-5 重定向）、⑥（延伸到跨 zone）；「mmorpg 待做（可选）」按 Q15 登记。
- **盘点 java 列**：`scene-core.md:227`、`gate.md:318`、`:332`、`:344`（5.5 登记时一并改）、`scene-manager-match.md:113`、`:125`、`login.md:228`、`:240`、`data.md:138`、`:403` 第 7 条、`contract-robot.md:612` 第 10 条；勘误见 §8.4。
- **代码注释**：`SceneAssetLocator.java:33`（AwaitingPlacement → LEASE、节点号 0）；`GateTokenIssuer`（下沉后注明票据与普通令牌的区别）；`node_link.proto:92`（字段 7 已用）；
  `ClientRequestHandler` 关于 1006 补应答的注释（延迟应答例外）；`game-scene/src/main/resources/application.yaml` 里 `switch-resolve-timeout` 的注释（「到时推 23 {1003}」只对 63，226 推 {3027}）。
- **5.2 / 5.5 规格**：5.2 J5 的「提交后只补写一次」改为引用本稿 §5.9；5.5 的 `SwitchReason`、指标 `reason` 标签与收敛谓词按 §0.6 裁决 16 与 §0.5 对齐（由 5.5 规格的作者改，本批只登记）。
- **`docs/reference/mmorpg-client-contract-login.md:400`**：注明 Java 在 GO-5 / 传送时发 124，GO-5 下 26 先到。
- **路线图**：`roadmap.md:74` 状态；`:93`（7.3）加 §3.5 的写侧约束与 `RedirectOnEnter`。
- **（合拢评审补，本清单原先漏列）部署侧的按进程密钥表**：`deploy/compose/env.example`（整栈 stack.yaml 的必填清单）、`docs/porting/deploy-ci-spec.md` §6.1 秘密矩阵、`docs/porting/ops-release-spec.md` §7.2 的
  Secret 注入表——`GAME_GATE_TOKEN_SECRET` 的使用者都要加上 game-scene-manager（缺了它 scene-manager 拒启，`assign` 也在它上面，连单 zone 的普通进游戏都会失败）；README 快速开始里这个变量的注释同样要提到它。
- **（实现阶段补）其它规格**：`scene-battle-spec.md` §7.13 的闸门表加 226（GATED，在途 3025，计 `zone_travel`）；5.5 规格（`scene-drain-spec.md`）登记 `SwitchReason` / `PRECHECK` / `travelFramesPending` 已由本批落地。

> **落地（2026-10-11，文档阶段）**：上面各条都已改：`architecture.md`（§2、§4.1–§4.4、§5、§6、§7 第 4 / 6 步与「玩家位置」「归属区」两段、§8 第 5 / 12 步、§8.1 的冻结闸与新 §8.2、§10、§11）；`PARITY.md`（新增「跨 zone 传送（226）与重定向（124）」「合服围栏与合服」两行，
> 更新「归属区映射」与分配 gate、场景分配、gate 接入与路由、节点客户端可达地址、服务端推送、短线重连与落点几行；原写的 `:25` / `:27` / `:28` / `:50` / `:59` / `:91` 在本批改动时仍是这几行，新增的三行追加在第 133–135 行，没有让它们漂移）；
> 盘点的 java 列（`scene-core.md` zone-travel 与 ownership-handoff、`gate.md` token-handshake / cross-zone-redirect / redirect-ticket-binding、`scene-manager-match.md` sm-cross-zone-redirect / sm-home-zone-routing、`login.md` enter-game-cross-zone / home-zone-mapping、
> `data.md` home-zone-registry 与开放问题第 7 条、`contract-robot.md` robot-travel-smoke 与开放问题第 10 条；`gate.md` scene-node-loss-handling 仍归 5.5，未改）；5.2 J5（`scene-handoff-spec.md`）；5.5 的登记（`scene-drain-spec.md` §0.4）；
> `scene-battle-spec.md` §7.13；`deploy-ci-spec.md` §6.1；`ops-release-spec.md` §7.2；`docs/reference/mmorpg-client-contract-login.md` §8；路线图 5.4 / 7.3 两行；README。
> **代码注释**都已随各工作包改好（`SceneAssetLocator`、`GateTokenIssuer`、`node_link.proto` 字段 7、`ClientRequestHandler` 的 1006 补应答注释、`game-scene` 的 `switch-resolve-timeout` 注释）；`deploy/compose/env.example` 随合拢评审 `e7ff9a2` 改。

---

## 6 配置与常量

### 6.1 基线

见 §1.10。对 Java 有意义的只有：票据 TTL 300 s（客户端可见）、等待落点有效期 300 s、断线租约 30 s、客户端已受理预算 75 s。看门狗、晚发闸、冻结上限、handoff 标记 TTL、
归属查询超时都随标记链与映射一起不移植（X1、X10）。

### 6.2 Java 新增 / 复用

| 配置 / 常量 | 缺省 | 进程 | 说明 |
|---|---|---|---|
| `GAME_GATE_TOKEN_SECRET` | 必填 | game-scene-manager（新增） | 与 game-gateway / game-gate 同值；缺失拒启（`AGENTS.md` §3）。**落地**：三个进程取法相同（`Environment` 读，缺失或全空白拒启，原值按 UTF-8 作密钥、不去空白，原值逐字节相同）；三方缺密钥的报错互相点名；部署侧的按进程密钥表见 §5.14 |
| `GateTokenIssuer.REDIRECT_TICKET_TTL` | 300 s（常量） | game-common | 客户端可见（`token_deadline`），同 `gate_redirect.go:27`，不做成配置 |
| `GateTokenIssuer.TOKEN_TTL` | 600 s（常量） | game-common | 不变（`GateTokenIssuer.java:22`） |
| `game.scene.travel.team-check-timeout` | 2 s | game-scene | 启动校验 ≤ 5 s（226 应答要远小于客户端 15 s） |
| `game.scene.switch-resolve-timeout` / `transfer-lease-margin` / `transfer-probe-statement-timeout` / `transfer-tombstone-ttl` | 4 s / 15 s / 3 s / 30 s | game-scene | 复用 5.2（`game-scene/src/main/resources/application.yaml:104-113`） |
| 启动校验（新增） | — | game-scene | `team-check-timeout + switch-resolve-timeout + transfer-lease-margin + 5 s < 60 s`（给客户端 75 s 预算留 15 s 余量） |
| `game.gate.redirect-linger` | 60 s | game-gate | 启动校验 ≥ 15 s（**落地**：`GateProperties` 与 `GateLimits.MIN_REDIRECT_LINGER` 都校验） |
| `game.gate.handshake-timeout` | 30 s | game-gate | 复用（`game-gate/src/main/resources/application.yaml` `gate.handshake-timeout`）：跟随 124 的新连接须在 30 s 内发 `ClientTokenVerify`，远大于客户端探测 5 s + 验票 10 s |
| `game.login.scene-assign-timeout` / `owner-claim-wait` | 5 s / 3 s | game-login | 复用（`game-login/src/main/resources/application.yaml:91-95`）：第二条腿与 GO-5 后的落地；`redirectToZone` 的本地兜底也取 `scene-assign-timeout` |
| gate → login Dubbo 调用超时 | 5 s | game-gate | 复用（`game-gate` 的 `dubbo.consumer.timeout`）：GO-5 时 EnterGame 的 `redirectToZone`（提供方 3 s）+ 查库须在其内；超时则 gate 推 23 {1003}（现有 `onLoginCompleted` 口径）。~~login 侧迟到的 `RedirectToGate` 指令按「会话已断 / 已应答」忽略~~ **订正（W5 评审）**：超时之后迟到的应答被 Dubbo 消费端直接丢弃，**根本到不了 gate**（`recordIdentity` / `abandonEnter` 的迟到处理只管「会话已断 / 正在断」时回来的应答）。对 `RedirectToGate` 无害（票据无人使用、到期作废）；对落地路径有害——login 那时夺到的归属无人持有、无人代为释放，玩家要等 30 s 租约，期间重试撞 Held 回 2005。由下一行的 login 预算挡住 |
| `game.login.enter-game-budget`（**落地新增**） | 4 s | game-login | 一次 EnterGame 从 login 处理器开始算的时间预算（`EnterGameHandler.DEFAULT_ENTER_BUDGET`，必须为正）：签票 / 分配的本地兜底取 `scene-assign-timeout` 与剩余预算中较小的，夺权截止取 `owner-claim-wait` 与预算截止中较早的，用完就不再调用（回 3023）、不再夺权（回 2005）、不再复查位置；必须小于上一行的 5 s，差值留给链路往返、派发排队、最后一次夺权的 SQL 与回包 |
| `PlayerLocationDirectory.RECONNECT_LEASE` / `ONLINE_TTL` | 30 s / 60 s | game-discovery | 复用（`PlayerLocationDirectory.java:45-49`）；待落点 TTL 由调用方给 |
| 方法热关停 | — | 全部 | 运维可经现有 `game:killswitch` 关掉 `SceneSceneClientPlayer/TravelToZone`（`architecture.md:416-435`），不另加开关 |

---

## 7 指标

### 7.1 基线

- C++ scene：`[TravelHandoff]` / `[ZoneTravel]` 日志计数（`travel_handoff_stats`，granted / grantedClientReset 等，`player_lifecycle.cpp:4025`、`:3906`），无 Prometheus 端点（`PARITY.md:43`）。
- SM：`enter_scene_rejected_total{zone_id, reason}`（含 `travel_map_unavailable`、`pending_map_fallback`、`handoff_withdrawn`、`epoch_conflict`）、
  `enter_scene_stage_seconds{zone, stage=cross_zone}`、`home_zone_lookup_total{outcome}`（`home_zone.go:53-63`；`enterscenelogic.go:63-66`、`:429`）。
- shared：`mmorpg_client_endpoint_select_total`（由 `go/shared/clientendpoint` 统一计数，`gate_redirect.go:151`）。

### 7.2 Java（Micrometer；不以 player / session / zone / 场景号 / 节点号作标签，`architecture.md:773`；`AGENTS.md` §5）

| 进程 | 指标 | 类型 | 标签 / 说明 |
|---|---|---|---|
| scene | `game_scene_travel_requests_total` | Counter | `result` = accepted / zone_invalid / map_invalid / in_battle / in_team / busy_transfer / busy_switch / team_read_error / **unavailable**（落地：没装配跨 zone 传送、同步回 3027）；每条 226 恰好计一次。team_read_error 含 PRECHECK 槽过期由作废方回 3027 |
| scene | `game_scene_travel_resolves_total` | Counter | `result` = chosen / rejected / error / stale / **in_battle**（落地：选中时在战斗中，推 23 {3025}）；每次受理恰好计一次。error 含调用失败、本地兜底超时、应答残缺与 RESOLVING 槽过期（落地：作废方推 23 {3027}，迟到的结果不再计） |
| scene | `game_scene_transfers_total`（5.2） | Counter | 加 `reason` = player / travel（5.5 再加 evacuate，§0.6 裁决 16、Q12），reason × result 全部预注册；`result` 加 **superseded**（落地：原写 taken_over，见 §2.3 TF6′）。travel 下 `link_gone` 在 `handed_off` 之外另计（不是终结结局） |
| scene | `game_scene_travel_placements_total` | Counter | `result` = written / failed（待落点写入） |
| scene | `game_scene_travel_frames_pending`（落地） | Gauge | — 已交出并移出世界、重定向帧还没定的旅客数（`SceneWorld.travelFramesPending()`，给 5.5 的收敛谓词），应很快回到 0 |
| scene | `game_scene_travel_frames_withheld_total`（落地，合拢评审 D1） | Counter | — 帧发出**之前**墓碑已被交叉的离开消费、重定向帧不发的次数（帧发出之后才交叉的由 gate 计 stale / orphan） |
| scene | `game_scene_battle_gate_rejects_total`（6.3） | Counter | `gate` 加 zone_travel（226 被战斗在途拒，3025；**落地**：只在同步段计，在队检查 / 选目标回来时复查到战斗的分别计 `travel_requests{in_battle}` / `travel_resolves{in_battle}`，不计本指标） |
| gate | `game_gate_redirects_total` | Counter | `source` = travel / login；`result` = sent / invalid / stale / orphan（2 × 4 全量预建；orphan 只出现在 travel；login 的 stale 含迟到的应答与已重定向会话上又来的指令） |
| gate | `game_gate_disconnects_total` | Counter | `reason` 加 redirect_linger；**落地**：非法重定向（两种来源）推 23 {3027} 后断开计 transfer_failed |
| gate | `game_gate_client_requests_total` | Counter | `result` 加 redirected（REDIRECTED 会话丢弃，含转入 REDIRECTED 时还在排队的请求）。**落地**：原写 `game_gate_requests_total`，实际沿用既有的 `game_gate_client_requests_total` |
| gate | `game_gate_handshakes_total` | Counter | 复用（`WRONG_ZONE` 覆盖他区同号 gate 的票据，`GateMetrics.java:57-67`） |
| scene-manager | `game_scene_manager_travel_seconds` | Timer | `result` = ok / no_gate / no_map / **fenced**（落地：F4 命中或读不到，7.3 之前恒为 0）/ bad_request / error。Micrometer 名 `game.scene_manager.travel` |
| scene-manager | `game_scene_manager_redirect_seconds` | Timer | `result` = ok / no_gate / no_scene / bad_request / error。Micrometer 名 `game.scene_manager.redirect` |
| login | `game_login_enter_routes_total` | Counter | `route` = first / resume / awaiting / pinned / redirect / redirect_fallback / location_error（全量预建；pinned 的口径见 §5.8 落地注） |
| login | `game_login_ticket_holder_rejected_total` | Counter | — |
| login | `game_login_backend_calls_seconds`（既有，落地） | Timer | `method` 加 redirectToZone（GO-5 签票的耗时，`backend` = scene-manager，`result` = ok / rejected / error） |
| login、scene-manager | `game_merge_fence_checks_total` | Counter | 7.3 起：`site` = create / enter / travel；`result` = open / fenced / unreadable |

### 7.3 勾稽

- `travel_requests{accepted}` ≈ Σ `travel_resolves{*}` + 在途。
- `transfers{reason=travel, handed_off}` ≈ `gate_redirects{source=travel, sent + invalid + stale + orphan}` + `transfers{reason=travel, link_gone}` + `travel_frames_withheld` + `travel_frames_pending`。
  **落地（合拢评审 D1，`b24582d`）**：原式的「被墓碑消费（交叉离开）的次数」改成 `travel_frames_withheld`——它只计帧发出**之前**的交叉；帧发出之后墓碑才被消费的，gate 已计 stale / orphan，
  原式按字面会把这类旅途算两遍，而帧发出之前的那类在任何指标里都看不到（与真正丢了一帧 124 分不开）。剩下的差额才是真正丢在路上的帧（已交给链路、gate 没收到、也没报写失败）。
  同一个式子写进了 `SceneMetrics.TransferResult` 与 `GateMetrics.RedirectResult` 的 javadoc。没有用 `transfers{travel, left}`：left 是「冻结中会话离开」的终结取值，那时不计 handed_off。
  scene 一侧可单独核对：交给链路的帧 + link_gone + withheld + pending = handed_off（`ZoneTravelHandOffTest`）。
- `login_enter_routes{redirect}` ≈ `gate_redirects{source=login}`（迟到应答除外）。

---

## 8 隐患与边界

### 8.1 基线自身的隐患（Java 对应处置）

| # | 隐患 | 出处 | Java |
|---|---|---|---|
| B-1 | 整条链从未端到端跑通；Go / C++ 跨 zone 单测没跑过、`scene_manager` / `login` 无编译证据 | `docs/design/handoff-crosszone-20260920.md:6-14`；`PROGRESS.md:5247` | 本批要求切片 + Java robot + Go robot 跨实现验收；Go robot 失败时先分清是 Java 还是基线 robot 的问题 |
| B-2 | 放行后 124 丢失或迟到（gate-cmd 滞后、实例号不符）：源已销毁、不触发 ClientReset，客户端停在哑连接直到 75 s（推导） | §2.2；`travel.md:453` | 不适用：124 经同一条链路帧；链路断则关会话（TF7） |
| B-3 | gate-cmd 滞后 > 30 s 时 34 比 124 先到，成功的传送被踢回选服 | `travel.md:453` | 不适用 |
| B-4 | 冻结期间旁人看到不动的分身，最长到放行或 30 s 看门狗 | `runbook.md:441` | 分身至多留交出 + 探测时长（≤ 15 s） |
| B-5 | 传送窗口内全局服务推送丢失、不补发 | `travel.md:92-103` | 同（推送至多一次，在线目录在两条腿之间撤销），客户端落地后自己拉 |
| B-6 | tip 与 34 到达顺序不保证；34 先到则 tip 无连接可收 | `travel.md:459` | Java 不发 34（X7），23 先于断开 |
| B-7 | 只发 23 再断开时 Unity 通用断线文案盖住传送失败文案 | `travel.md:442` | **Java 受此影响**（X7）：失败后选服界面显示通用断线文案。登记客户端侧改进候选 |
| B-8 | TeamId 是缓存，刚入队、缓存没刷新时拦不住传送 | `travel.md:214` | 不适用：每次现读成员关系（X4） |
| B-9 | 重定向选 gate 不看排空 | `gate_redirect.go:56-104` | X9：剔除排空 |
| B-10 | 「拒绝先于任何写」只对带 GateId 的请求成立 | `enterscenelogic.go:533-540` | 不适用（Java 的检查点都在写之前，§3.5） |

### 8.2 Java 移植时会踩的 / 残余风险

- **R-T1**：票据里的 gate 在交出之前选定，提交后它可能已死：客户端跟随失败回选服；数据安全（已释放），从 B 进落待落点地图、从 A 进落 A。与基线「先签后铸」同型。
- **R-T2**：提交到 124 送达之间别的设备登录会抢到 E+2（§2.4），结局确定、单写者成立；被抢的一方收 2017。基线的同一窗口从 SM 铸 E+1 开始。
- **R-T3**：待落点记录最长存活 300 s：期间组队看他是「断线中」、资产通道多一次 NOT_HERE 重投。
- **R-T4**：`PlayerTransfer` 已写进 socket 但 gate 随即崩溃 / 链路在 gate 处理前断开：客户端断线、没收到 124；待落点仍在。从 A 重登 → 不牵引 → A 首登（默认主世界，不是源场景原位）；
  从 B 重登 → 落待落点地图。数据完整（冻结快照）。与基线等待落点不牵引同义。
- **R-T5**：票据 300 s 内可重复验票（同基线）；只认证连接，进角色另有 2011 与账号校验。
- **R-T6**：旅客绕过目标 zone 的登录排队与开服限流（同基线直签），目标区软上限可能被一批传送冲过（Q11）。
- **R-T7**：延迟应答是 `PlayerCall` 的新能力：处理器永不回应答会让客户端等到 15 s 超时。`DeferredReply` 必须带截止时刻，组队读超时保证一定回；实例移除时作废、不回。
- **R-T8**：5.2 在工作树上与本批改同一组文件（`SceneWorld` / `ClientRequestHandler` / `ClientDispatcher` / `PlayerStore` / `node_link.proto`），本批须在 5.2 提交之后开工、重新取行号。
- **R-T9**：GO-5 跟随的 `o` 记录所在节点刚死、目录条目还在（≤ 15 s）：重定向到 B 后 B 的 login 夺权撞 Held、等不到回 2005，客户端重试；至多 30 s（同 zone 现有残余口径）。
- **R-T10**：墙钟偏差接近 M 时安全边际失效（同 5.2 R-J5，`architecture.md:666`）。待落点 TTL 用 SM 签的 `token_deadline` 减 scene 本地时钟，偏差只让 TTL 偏长 / 偏短（截在 [1 s, 300 s]），不影响归属。
- **R-T11**（评审补）：组队读之后、交出提交之前入队，带队传送（§2.4）。与基线 B-8 同类、窗口更窄；不加跨服务锁。
- **R-T12**（评审补）：源节点在「已交出、帧未写出」窗口里停服 / 冲突退出（TF16）：客户端没收到 124、断线重登；数据完整（冻结快照、E+1 已释放）。5.5 收敛谓词计入 `travelFramesPending` 后只剩
  「预算到期仍未写出」的残余，与 5.5 R3（`scene-drain-spec.md:1012`）同级，但 RELEASE 模式下没有 E+1 悬空、重登不用等 30 s。
- **R-T13**（评审补）：Unity 跨区在途只认 `IsTravelFailureTip` 的 5 个码（§1.7）。Java 交出后被接管 / 被围栏的 23 {2017} 不在其中，客户端的传送窗口要等随后的断线才收场，
  断线文案是通用文案（与 B-7 同一个客户端改进项，Q21）。

### 8.3 scene-node-loss-handling 边界（5.5）

- **基线**：节点被摘除时只把会话对该节点的绑定置无效，**连接保持**（`client_message_processor.cpp:1104-1113`）；之后的 scene 类消息回 23 {1003}，直到 SM 改派后以 `enterType = 0` 重新进场（2026-10-15 起基线 `HandleTcpNodeMessage` 这一支改为回带请求 id 的信封 1003，Java「未进场景」同批改，mmorpg client-request-ordering.md O6；5.5 若改为「连接保持」，照信封形状对齐）。
- **Java 现状**：链路断开直接关闭会话（`SCENE_LINK_DOWN`），属既有差异，盘点记为「有意不同、未登记 PARITY」（`gate.md:337-350`），归 5.5 处理与登记。
- **与 5.4 的交叉**（本批规定）：
  - PRECHECK / RESOLVING 中源链路断开 → gate 关会话 → scene 按现有离场写回并释放，迟到结果丢弃。
  - FREEZING 中源链路断开 → `pendingAbort = LEAVE(断线)`：提交了就写源场景重连租约（E, q+1）、不发帧；没提交就写回释放 E。客户端重连回原场景。
  - `PlayerTransfer` 写出后、gate 处理前链路断开 → R-T4。
  - 第二条腿目标链路断开 → 现有进场失败口径（`PARITY.md:36`）。
  - 源节点停服 / 冲突疏散（5.5 SHUTDOWN / CONFLICT）：PRECHECK / RESOLVING 的旅客按停服的现有流程写回、断开（迟到结果丢弃）；FREEZING 的按 5.2「停服照常提交 save(E)」，RELEASE 模式下
    交出先提交则 save 被拒、什么都不用释放；「已交出、帧未写出」窗口见 TF16 / R-T12，收敛谓词要等 `travelFramesPending == 0`。
  - 5.5 规格里与本批相关、需要它按本稿对齐的三处：`SwitchReason`（§0.6 裁决 16）、指标 `reason` 标签（Q12）、收敛谓词（§0.5）。

### 8.4 勘误

**对分区稿**

1. 归属区稿 §3.6 / D-H8「普通令牌 `target_zone_id` 改回 0、gate 另验 `zone_id`」：改为保持 Java 现状（§0.6 裁决 1）。基线 gate 根本不验 `zone_id`，加这道校验本身是新差异。
2. 归属区稿 §3.9 第 1 条「跨 zone 用既有的最终写回并释放（epoch E 围栏）」：`updateStateAndRelease` 不要求 `released = 0`（`PlayerMapper.java:64-70`），改为 E→E+1 + 释放（§0.6 裁决 2）。
3. 归属区稿 §3.4 新状态 `w` 与 `find` 带状态返回：改为复用 `l` + 节点号 0（§0.6 裁决 3）。
4. 两份分稿把 2011 放在 2018 / 2028 之后：基线在取账号之前（`entergamelogic.go:82` 早于 `:89-95`），改为 2028 之前（§0.6 裁决 9）。
5. 归属区稿 §6.1 的 zone 2 端口 21002 / 21102 / 18124 与 5.1 规划的第三个 scene 节点冲突（`scene-channels-spec.md:1169`），改用 21010 / 21110 / 18115（§0.6 裁决 11）。
6. Java 设计稿 §3.12 TF6「探测读到 E+2 按 Lost（3027）」：RELEASE 模式下 ≥ E+2 只能是「已提交且被接管」，改踢 2017（§0.6 裁决 14）。
7. Java 设计稿 §3.9 待落点用 (E+1, 1) 写：改为 (E, q+1)，保持 5.2 J5 不扩展（§0.6 裁决 4）。
8. 基线稿 §5.3 引 `ClientDispatcher.java:1099-1103`：工作树现为 `:1097-1104`。5.2 规格 §5.4 引 scene-manager 提供方超时在 `application.yaml:68`：现为 `:94`（同 `dungeon-mirror-spec.md` E12）。

**对 inventory / PARITY / 5.2 规格 / 代码注释**

1. `inventory/scene-manager-match.md:125`（sm-home-zone-routing「Java 单 zone、单库，玩家没有 home zone 概念」）已过时：组队、帮会、聚宝斋自 4.3 / 4.4 / 4.7 起经 `PlayerHomeZones` 读 `player.zone_id`。
2. `inventory/login.md:240`（home-zone-mapping「java: missing」）说重了：`player.zone_id` 就是映射，角色列表直接用它（`PlayerViews.java:21`）；真实缺口是建角取 login 进程 zone（X16）。
3. `scene-handoff-spec.md:103`（5.2 留给 5.4 的钩子）写「交出事务相同」：5.4 用同一原语但多一个 RELEASE 模式（X1）；同一行「字段 7 承载 redirect、gate 推 124 而不是改绑、3027 / 13000」成立。
4. `inventory/scene-core.md:215` 的 Java 建议「源节点写回并释放 → 目标节点夺权加载」被 5.2 否决（`scene-handoff-spec.md:724-725`），该否决只对同 zone 改绑成立；跨 zone 正是本稿采用的形态（加 epoch 推进）。
5. `inventory/scene-core.md:230` 隐患「124 可能先于 226 应答到达」在 Java 不成立（X3）；「客户端预算须大于服务端最坏结论时长（约 71 s）」在 Java 约 25 s。
6. `SceneAssetLocator.java:33` 注释「AwaitingPlacement（Java 跨区随 5.4）」：5.4 起待落点表现为 `s = l`、节点号 0，归 LEASE（X14）。
7. `PARITY.md:59`「RedirectToGate 随跨区重定向批次」默认走推送通道：5.4 的 124 经链路帧或 login 会话指令下发，不经 `PlayerPushes`（X3、N4）。
8. `inventory/gate.md:337-350`（scene-node-loss-handling「有意不同，未登记 PARITY」）至今未登记，归 5.5。
9. 5.2 D8（`scene-handoff-spec.md:1175`「交出不拍 LOGIN / LOGOUT」）：基线 B 侧建实体时在 `InitPlayerFromAllData` 里拍 LOGIN（`player_lifecycle.cpp:2425`），5.2 D8 对基线确属差异（推导）；
   5.4 第二条腿是真实登录，照常拍，与基线一致。
10. `inventory/data.md:403` 第 7 条「Java EnterGame 用会话 zone 而非角色行 zone_id 选场景（需确认是否有意）」：有意且同基线——会话 zone 是**入口**，不是归属；基线 login 同样按 `Node.ZoneId`
    （= gate zone）发 `GateZoneId`（`entergamelogic.go:727`、`:743`）。
11. `inventory/contract-robot.md:612` 第 10 条「Java 无 HomeZone 概念，移植 travel 前要先定」：已定——归属区 = `player.zone_id`、建角取会话 zone；Java robot 每轮新号，Go robot 的 robot_9601 首次在 zone 1 建角即可。
12. `architecture.md:740` §10「首批不做：跨 zone」随本批删除。

> **落地（2026-10-11）**：第二组 1–7、10–12 都已在文档阶段（或随各工作包的代码注释）改掉，改在哪里见 §5.14 末尾的落地注；第 8 条（`gate.md` scene-node-loss-handling）仍归 5.5，没有改；
> 第 9 条是对 5.2 D8 的说明（5.4 第二条腿照常拍 LOGIN 快照，与基线一致），不需要改文件。

---

## 9 建议的有意差异

### 9.1 建议采纳

| 编号 | 差异 | 理由 | 客户端可见？ | 两版同改？ |
|---|---|---|---|---|
| X1 | 一笔「交出并释放」MySQL 事务（写回 + E→E+1 + `released = 1`）替代 handoff 标记、换手门、SM 铸造、等待落点 CAS、Kafka 回滚；第二条腿由目标 zone 的 login 正常夺权（E+2） | 数据与归属同行；第二条腿本来就是一次登录；两条腿之间无持有者，与基线等待落点同义 | 否 | 否 |
| X2 | scene-manager 只做地图预检、选 gate、签票据，不铸 epoch、不写位置；待落点由源 scene 在提交后写 | 与 5.2 D2「SM 只选」一致（`scene-handoff-spec.md:1169`） | 否 | 否 |
| X3 | 124 经源 scene 的同一条链路帧（`PlayerTransfer.redirect`）或 login 会话指令交给 gate，不经 Kafka；226 应答恒先于 124，GO-5 的 26 恒先于 124 | 同链路 FIFO / 先回包后执行指令（`ClientDispatcher.java:454-455`）；Java 无 Kafka 命令通道（`architecture.md:132-151`） | 是：顺序收敛为基线允许的一种 | 否 |
| X4 | 3026 用延迟应答（异步读组队索引 ≤ 2 s）判，不缓存 TeamId；读失败回 3027 | Java scene 不缓存队伍号（`architecture.md:459`；`TeamFollowService.java:37-40`）；保持「同步拒绝」契约（`travel.md:1431-1434`） | 是：仅 Redis 故障时 3027；应答晚 ≤ 2 s | 否 |
| X5 | 受理后、选到目标前不冻结（同 5.2 RESOLVING）；基线受理即冻结 | 冻结只覆盖库事务；客户端此时显示「传送中」遮罩（转述） | 是：最多约 4 s 内输入照常生效 | 否 |
| X6 | SM 不可达是「应答 `{0}` 后 23 {3027}」；基线没注册 SM 时同步 3027 | 直连 URL 无法同步得知（同 5.2 D12） | 是：码同、时机不同 | 否 |
| X7 | 交出后失败（Lost）23 {3027} 后断开，不发 34；被接管 23 {2017} 后断开；基线 23 + 34 {3027} | 与 Java「不发 34」口径一致（5.2 D5，`architecture.md:126`） | 是：少一条 34，Unity 按 34 的 reason 选文案（`travel.md:442`），没有 34 时显示通用断线文案（B-7、R-T13） | 否 |
| X8 | 推出 124 后旧会话转 REDIRECTED：丢弃之后一切请求，60 s 内客户端没关就由 gate 关；基线不关 | 不留半开空闲连接；合规客户端几秒内自己关（`redirect.go:160-163`） | 是：仅不合规客户端 | mmorpg 可选 |
| X9 | 重定向选 gate 剔除排空中的 gate；基线不看排空 | 与 gateway 分配同规则（`GatePicker.java:10-19`），不把旅客送进要下线的 gate | 否：只影响落到哪台 | mmorpg 可选 |
| X10 | 不查归属区映射：没有「未映射拒传送」、没有「归属区不可用」20；合服 21 随 7.3 | `player.zone_id` 建角同行写入、恒有值；单库无需按归属区路由存盘 | 否（今天） | 否 |
| X11 | GO-5 签票失败（目标 zone 没 gate / 没 scene 节点）时落入口 zone；基线 SM 回错、login 推 3023，租约到期前每次都失败 | 归属全服一行，跨 zone 落地只是一次接管，不会串档；可用性优先 | 是：仅故障时 | mmorpg 可选 |
| X12 | 受理到出结论约 25 s；基线最长约 71 s | 原子提交、结局可探测（同 5.2 D4） | 是：更快 | 否 |
| X13 | 票据判据 `ticket_player_id ≠ 0`；Java 普通令牌保持 `target_zone_id = zone_id` | Java 普通令牌钉 zone 比基线的 0 更安全且已登记（`GateTokenIssuer.java:14-15`），不改 | 否（客户端不解析普通令牌） | 否 |
| X14 | 待落点用「重连租约」形状（`s = l`、节点号 0、TTL = 票据剩余），不新增状态；基线是不过期的 location + `update_time` 判 300 s | 三个读者不用改；过期自动消失 | 否 | 否 |
| X15 | 提交后改绑帧没写出 / 与 `PlayerLeave` 交叉：位置改回源场景（(E, q+2) 租约或墓碑），重连回原场景原坐标；基线 Conclude（3027 + 34），重登时等待落点不牵引、落 gate zone 默认主世界 | 库里就是冻结快照，回原处最自然 | 是：失败后的落点 | mmorpg 可选 |
| X16 | `CreatePlayer` 的归属区取会话 zone（为 0 回 2020） | 一个 login 服务多个 zone；结果等价基线「每 zone 一个 login」 | 否 | 否 |
| X17 | 本批不做 `RedirectOnEnter`（随 7.3） | 基线缺省关（`login.yaml:211`），用途是合服后路由 | 否 | —（随 7.3） |
| X18 | 跨 zone 重连 / 顶号回原实例（`PARITY.md:91` ⑥ 延伸到跨 zone） | 同 ⑥ 的理由：按基线设计意图「去向按 location 决定」 | 是：落点实例 | mmorpg 可选（已登记 ⑥） |
| X19 | 读到 epoch ≥ E+2（已交出且被别的登录接管）踢 2017 | RELEASE 模式下的确定结论（§0.6 裁决 14） | 是：极窄竞态里的 tip；2017 不在 Unity 的传送失败码里，窗口随断线收场（R-T13） | 否 |
| X21（评审补） | 226 的应答延迟 ≤ 2 s（**落地**：正常约 2 s；在队检查的回调丢失时由槽过期兜底，最坏约 4 s，见 §5.5 的落地订正），连发时第二条的 13000 可能先于第一条的 `{0}`；受理后的 23 恒在 `{0}` 之后 | X4 的直接后果；同一链路先回 `{0}` 再进 RESOLVING | 是：只影响按到达顺序配对应答的客户端。Unity 是否按 `id` 配对没有在客户端代码上复核（客户端仓缺）；Go robot `travel_smoke` 不连发 226（请求间隔 1.1 s，T2 / T2b 都是同步拒绝），不受影响；Java robot 按 `id` 配对（§11.11 T9） | 否 |
| X22（评审补） | 226 可经 gate 的 `game:killswitch` 热关停（信封 1003） | Java gate 对全部客户端方法查热关停（`architecture.md:428-430`）；基线 226 走 C++ scene，Go 侧 killswitch 管不到 | 是：仅运维打开时 | 否 |
| X20 | 地图预检读计划失败按调用失败（23 {3027}）；基线 Redis 读失败放行 | 计划与 gate 目录同一 Redis，读不到计划时 gate 目录同样读不到；放行只把失败推迟到交出之后 | 是：仅 Redis 故障时 | 否 |

### 9.2 列出但不建议采纳

- **N1**：E+1 保持持有（5.2 形态），目标 login 凭服务端票据记录「接手」：要多一个原语和一类 Redis 状态；旅途失败时从别处登录要等 30 s 租约（5.2 R-J1 同型）。
- **N2**：位置记录新状态 `w` / `p`：要改 `READ_VALUE`、`statusOf`、`holderOf` 与组队映射（`PlayerLocationDirectory.java:109-127`、`:306-355`），收益只是语义清晰（Q2）。
- **N3**：game-gateway 当 Dubbo 提供方签票据：密钥不外扩、能顺带查区服目录状态；代价是地图预检仍在 scene-manager，一次传送调两个服务（Q4）。
- **N4**：经 `PlayerPushes`（Redis pub/sub）推 124：与链路帧无序，失去「应答先于 124」与绑定核对。
- **N5**：交出时把目标地图写进 `player` 行：改变冻结快照语义，旅途失败后回不到原处。
- **N6**：普通令牌改回 `target_zone_id = 0` + gate 验 `zone_id`（归属区稿 D-H8）：见 §0.6 裁决 1。
- **N7**：226 受理后才查组队、在队推 23 {3026}：改变「3026 是同步码」的契约。

### 9.3 照搬基线、不算差异

票据 300 s、不带会话密钥、绑 `player_id` 与 `target_zone_id`、hex 签名；目标 gate 五条文案与顺序；2011；钉 zone 不弹回；等待落点不牵引；GO-5 窗口内回原处；目标 zone 不存在异步 3027；
旅途窗口内推送会丢；旅客绕过目标 zone 的排队与限流；第二条腿拍 LOGIN 快照、源不拍 LOGOUT；同步码与顺序（§1.5，除 X4 的时机）；可以从副本 / 镜像里发起传送。

---

## 10 开放问题（各带推荐答案）

| # | 问题 | 推荐答案 |
|---|---|---|
| Q1 | 跨 zone 交出语义：「交出并释放」（X1）还是 E+1 保持持有（N1）？ | **交出并释放**。第二条腿与回家重登都不用等 30 s 租约；与基线「等待落点无持有者」同义 |
| Q2 | 待落点表示：复用 `s = l` + 节点号 0（X14）还是新状态（N2）？ | **复用 `l`**。读者零改动，过期交给 TTL |
| Q3 | 3026 判法：延迟应答（X4）、受理后推 23 {3026}（N7），还是在 scene 缓存队伍号？ | **延迟应答**。不改同步契约、不会过时；`PlayerCall.defer()` 作为通用能力保留但本批只给 226 用 |
| Q4 | 谁签重定向票据：scene-manager 还是 game-gateway（N3）？ | **scene-manager**，同基线；新增必填 `GAME_GATE_TOKEN_SECRET` |
| Q5 | 目标 zone 处于 MAINTENANCE / PREVIEW 时要不要拒传送？ | **首批不查**（基线不查），登记风险；要查时 scene-manager 读区服目录、回落 3027 |
| Q6 | 合服围栏读侧：本批接真实现，还是只定检查点？ | **只定检查点、恒放行**，随 7.3 换实现（帮会 4.4 先例） |
| Q7 | REDIRECTED 收口：60 s（X8）还是同基线不收口？ | **60 s** |
| Q8 | 切片拓扑：共用一个 login 还是每 zone 一个？ | **共用**（依赖 X16），与生产的 Java 部署形态一致 |
| Q9 | GO-5 签票失败：落入口 zone（X11）还是同基线回 3023？ | **落入口 zone** |
| Q10 | gate 目录同地址去重（`GateNodeInfo.started_at_ms` + `GatePicker` 只留最新）放进 5.4 吗？ | **放进 5.4**：`GatePicker` 本批下沉，一处改两处生效（gateway 现有缺口同时补上，PARITY `:25` 登记） |
| Q11 | 要不要按目标 zone 容量拒传送？ | **首批不做**（同基线），登记 R-T6 |
| Q12 | `game_scene_transfers_total` 加区分旅途的标签（要与 5.2 实现者对齐）？ | **加，名字用 `reason`**（与 5.5 规格 `scene-drain-spec.md:962` 同名，取值 player / travel，5.5 加 evacuate）：5.2 未提交，现在加不产生旧序列 |
| Q13 | 副本 / 镜像实例里发 226：放行还是要求先回主世界？ | **放行**（同基线），实例按 5.3 空闲回收 |
| Q14 | 写待落点与发帧的先后：写完再发，还是并发？ | **写完再发**（多一次 Redis 往返，换第二条腿一定读到目标地图） |
| Q15 | PARITY「mmorpg 待做（可选）」登记哪些？ | **X8、X9、X11、X15、X18 全部登记**，每条注明是否客户端可见 |
| Q16 | `RedirectOnEnter`：本批做（缺省关）还是随 7.3？ | **随 7.3**，PARITY 登记「Java 待做」 |
| Q17 | 2011 放在哪：2028 之前（同基线）还是之后？ | **2028 之前、2018 之后**（同基线顺序） |
| Q18 | 会话 zone 为 0 时建角：2020 还是回落配置 zone？ | **2020**（fail-closed，同基线） |
| Q19 | 普通令牌 `target_zone_id`：保持钉 zone（X13）还是改回 0（N6）？ | **保持** |
| Q20 | 交出 / 探测读到 epoch ≥ E+2：踢 2017（X19）还是按 Lost 踢 3027？ | **2017**（结论确定） |
| Q21 | B-7（只发 23 时 Unity 文案被通用断线文案盖住）怎么办：Java 补发 34，还是登记客户端改进？ | **登记客户端改进**：保持 Java「不发 34」统一口径（5.2 D5、`PARITY.md:37`），把「23 {3027} 后紧跟断开时保留 3027 文案」提给客户端 |

---

## 11 测试计划与 robot

### 11.1 基线测试对照

| 基线用例 / 断言 | 性质 | Java 对应 |
|---|---|---|
| robot T2 / T2b（`travel_smoke_scenario.go:250-276`）：target 0、不存在的地图同步拒、无 124 | 同步拒绝 | §11.5、§11.11 T2 |
| GO-5 / 等待落点系（`go/scene_manager/internal/logic/owner_epoch_test.go:1666` `CrossZoneRedirectIntoOwnZoneLeavesOwnershipUntouched`、`:1721` `FreshAwaitingPlacementWithoutZoneLandsInGateZone`、`:1767` `…RejectsUnmappedHomeZone`、`:1813` `ZeroZoneOverLocationInGateZoneBehavesLikeExplicitGateZone`、`:1899` `ExpiredAwaitingPlacementNoLongerRedirects`；`travel.md:806` 写的 `:1133 / :1188 / :1234 / :1366` 已漂移） | 只送连接不动归属；等待落点不牵引；过期不牵引；未映射拒 | §11.7 去向表；未映射不适用（X10） |
| 第一条腿 / 第二条腿系（`owner_epoch_test.go:454` `CrossZoneWithCurrentMarkerReleasesToAwaitingPlacement`、`:1274` `TravelTargetMapIsCarriedByAwaitingPlacement`、`:1319` `TravelToUnopenedMapIsRejectedBeforeReleasingOwnership`、`:1359` / `:1417` 不指定地图时按默认大世界预检、`:1458` `TravelSecondLegFallsBackToDefaultWorldWhenPendingMapIsUnavailable`） | 放行写等待落点；目标地图随等待落点带到第二条腿；没开的图在不可回头点前拒；pending 地图不可用回落默认 | §11.4（地图预检）、§11.5（待落点 conf）、§11.7（pinned + 待落点）、MZ2 |
| `logic_test.go:912` `RedirectWithoutTargetWorldChannelStillEmitsRedirectEvent`、`:1564` `FirstLandingCrossZoneReachesRedirectPath` | 只送连接的重定向不查地图 | §11.4 `redirectToZone` 不查地图 |
| `TestResolveEnterSceneRoute_Priority` 等（`go/login/.../entergame_route_test.go:60`、`:187`、`:200`） | 票据优先于 GO-5 | §11.7 |
| `signRedirectToGate` / `selectGateTargets` 测试（`gate_redirect_client_endpoint_test.go:142-212`，含 `:182` `NeverPicksStaleRecordOfCrashedGate`） | 选址、地址、去重、签名 | §11.3、§11.4（同地址去重用例照 `:182` 写） |
| `home_zone_merging_test.go:33-246` | 合服围栏 | 随 7.3（Q6）；本批只测检查点被调用 |
| C++ TravelFreezeCap、TravelOutcome 系 | 冻结有界、去留裁决 | 5.2 交出结局表加 RELEASE 模式与 `reason = travel`（§11.2、§11.5） |

### 11.2 存储（`PlayerStoreSqlTest` 风格，缺省 H2，`-Dgame.it.mysql` 连真库）

- **RELEASE 成功**：`epoch = E+1`、`released = 1`、`lease = L`、`player` 与 `player_state` 都是快照、`saved_epoch = E`；随后 `claimOwnership` 立即 `Claimed(E+2)`。
- **提交后旧 epoch 的写全部被拒**：`saveStateAndRelease(E)`、`saveStateHeld(E)`、`renewOwnerLeases(E)`、`releaseOwnership(E)`；`releaseOwnership(E+1)` 是空操作。
- **未提交的三种情况不改任何列**（两种模式都测）：epoch ≠ E、已释放、剩余租约 < M。
- **重试改判**（RELEASE）：第一次提交后让应答失败，第二次得到 `Fenced(E+1, 1, L₁)` → 改判 `HandedOff`；`(E+1, 1, 别的租约值)` → 不改判；`(E+2, …)` → `Superseded`。HOLD 模式的 5.2 用例原样通过。
- **探测**（RELEASE）：另一连接持有未提交的交出事务时阻塞；提交后读到 `(E+1, 1, L)` → HandedOff；回滚后 `(E, 0)` → NotCommitted；提交后又被另一连接夺权 → `Superseded`。
- **并发**（真 MySQL）：交出并释放与 `saveStateAndRelease(E)` 并发，恰好一个成功；交出并释放与 `claimOwnership` 并发，结论确定、库里只有一个持有者。
- **建角**：`zone_id` = 传入的会话 zone（H2 / 真 MySQL）。

### 11.3 game-common：票据

- `issueRedirect`：字段 `{gate_node_id, zone_id, expire = now + 300, player_id, target_zone_id}`，`hmac_session_key` 为空；`GateTokens.verify` 在本 gate、本 zone 通过；在 zone 不符的同号 gate 上
  `WRONG_ZONE`；过期 `EXPIRED`。
- `issue`（普通令牌）下沉后行为不变：game-gateway 现有单测原样通过（`target_zone_id = zone_id`、TTL 600、有会话密钥）。
- **跨语言金样**：同一组字段、固定时钟与密钥，Java 的 payload 字节与签名和 Go `signRedirectToGate` 逐字节相同（向量取自 Go 单测或补一条，存进 `game-common` 测试资源）。

### 11.4 game-scene-manager（`SceneAssignerTest` 风格）

- `selectTravelTarget`：zone 0 / 同 zone / player 0 → `TIP_BAD_REQUEST`；want 不是世界地图 → `TIP_BAD_REQUEST`；计划里没有这张图（含 conf 0 的默认主世界）→ `TIP_NO_SCENE`；只有 DRAINING 记录 → 放行；
  目标 zone 没有 gate、或都没有客户端地址 → `TIP_NO_GATE`；排空与不排空混合 → 只挑不排空的，全部排空 → 忽略标记；同一客户端地址两条 → 只留 `started_at_ms` 大者（人数更少的陈旧条目也不选）；
  人数相同按节点号（2026-10-18 更正：改为随机二选一，最忙的不选、同人数不按节点号）；返回地址 = `client_host / client_port`、票据字段正确、`scene_config_id` = 解析值；计划 / 目录读失败 → future 异常；指标各 `result` 恰好计一次。
- `redirectToZone`：不查地图；目标 zone 没有可用 scene 节点 → `TIP_NO_SCENE`；其余同上。
- 缺 `GAME_GATE_TOKEN_SECRET` → 拒启。
- `assign` 回归：与现在逐条一致。

### 11.5 game-scene（`SceneWorldTest` / `ClientRequestHandlerTest` 风格，假仓库、假 sink、假组队读、假选目标）

- **同步段**：3024（0 / 本 zone）、3007（非 World；表空不拦）、3025（钩子置位）；在途：TRAVEL 任一阶段 → 13000，5.2 FREEZING → 13000，5.2 RESOLVING → 3014；同时满足多条回第一条的码；
  每条都断言**没有冻结**、`switching` 不变、`repository` 零调用。
- **受理应答字节**：`error_message` 编码为 `0a 00`；信封 `message_id = 226`、`id` = 请求号。
- **延迟应答**：组队读回「在队」→ 应答 3026、槽清；「不在队」→ 应答 `{0}` 之后才发选目标；读失败 / 超时 → 3027；读回前玩家离开 → 不回、不选；PRECHECK 期间 63 → 3014、226 → 13000；
  `ClientRequestHandler` 不对已延迟的调用补 1006。
- **选目标**：失败 / 超时 / 拒绝 → 23 {3027}、留原地、可继续游玩、可再发 226 并成功；token 过期丢弃。
- **成功**：冻结前停下；快照与写入内容一致；`handOff(…, RELEASE)`；旁人 51；不写回、不拍 LOGOUT；待落点 `(E, q+1, l, zone = 目标, 节点 0, scene 0, conf = 解析值)`、TTL ≈ deadline − now；
  **写完才发** `playerTransfer(…, redirect)`，帧里五项与选目标结果一致；`ownedPlayers` 不再含该玩家；**不调** `release(E+1)`。
- **写待落点失败** → 仍发帧，计 `placements{failed}`。
- **结局**：`LeaseTooShort` / `NotCommitted` → 解冻、23 {3027}；`Fenced` → 2017；`Superseded` → 2017；`Lost` → `kick(3027)`；`detached` → 只计 `left`。
- **pendingAbort**：LEAVE → 不发帧、位置按 voluntary 写源场景 (E, q+1)；TAKEOVER → 2017；两种都不调 release。
- **帧没写出**（返回 false / 异步失败）：位置 (E, q+2) 源场景重连租约；计 `link_gone`。
- **墓碑**：写待落点期间到来的交叉 `PlayerLeave` → 写 (E, q+2) 且不再发帧；过期后不命中。
- 从 5.3 实例发 226 → 放行。冻结闸沿用 5.2 用例，加 `reason = travel`。
- **PRECHECK 过期**（评审补）：组队读回调永不完成（假读者挂起）→ 2 s 内应答 3027（`orTimeout`）；再把回调「丢掉」并推进时钟越过槽期限 → 下一条 63 回 3014 之前，作废方已补回 3027、槽已清；
  迟到的组队结果不二次应答（`DeferredReply` 回两次即测试失败）。
- **线程**（评审补）：假 `PlayerLocations.awaitingPlacement` 在另一个线程完成 `onDone` → 断言墓碑检查与 `playerTransfer` 在逻辑线程上执行（逻辑线程执行器计数）；投递被拒（执行器已关）→ 不抛、不发帧。
- **`travelFramesPending`**（评审补）：提交后 +1；帧写出 / TF7 / 墓碑被交叉离开消费各 −1；任何路径结束后回到 0。
- **两条 226 连发**：第一条延迟 `{0}`、第二条同步 13000，断言两条应答的 `id` 各自对上（顺序可以是 13000 在前）。

### 11.6 game-gate（`ClientDispatcherTest` 风格）

- **握手**：票据的 `player_id` / `target_zone_id` 进 `ClientSession`，并出现在之后**每一个** login 与后端域调用的 `SessionContext`；再次 verify 不覆盖；普通令牌 `ticket_player_id = 0`；
  他区同号 gate 的票据 → `WRONG_ZONE`、断开、文案 `token not for this zone`。
- **`onPlayerTransfer` 带 redirect**：绑定对上 → 解绑（不发 `PlayerLeave`、撤在线登记）、下发 124（`message_id = 124`、`id = 0`、五项逐字段等于帧）、会话 REDIRECTED；之后请求被丢弃
  （不推 1003）；60 s 后关闭、原因 `redirect_linger`；客户端先关 → 定时器作废。
- **过期 / 会话正在关 / 绑定不符** → 只计 `stale`、不调 `abandonEnter`；路由层找不到会话 → 只计 `orphan`（`SceneEventRouter` 单测）。
- **非法 redirect**（空 host / 端口 0 或越界 / 空 payload / 空签名 / 同时带目标节点）→ 23 {3027}、断开。
- **迟到帧**：REDIRECTED 后源节点迟到的 `ToClient` / `PlayerKicked` 因绑定已清被丢弃；`PlayerTransfer` 之前的 226 应答照常先到。
- **`RedirectToGate` 指令**：EnterGame 应答在前、124 在后；会话 REDIRECTED；会话已关时迟到的指令什么都不发。
- **REDIRECTED 之后回来的 login 应答**（评审补）：转出 17 后收到 redirect 帧 → 124 → 再收到 17 的应答 `{UnbindPlayer}`：应答体照常下发、指令被忽略（不发 `PlayerLeave`、不改会话）；
  收口定时器到点关闭、不推任何 tip；定时器到点时会话已被客户端关闭 → 什么都不做。

### 11.7 game-login（`EnterGameHandlerTest` / 新 `EnterRoutesTest` 风格，对照 `entergame_route_test.go:60/187/200`）

- **2011**：持票者 ≠ 请求的 player → 2011，在 2028 之前；不续设备、不占在途闸门（同角色并发不回 2005）、不读位置、不分配；持票者相同 → 继续。
- **`EnterRoutesTest` 表驱动 §5.8 去向表全部组合**：钉 / 未钉 × 无记录 / 本 zone 节点 / 本 zone 待落点 / 他区节点 / 他区待落点 / 读失败；`TZ ≠ G` 的票据按未钉；普通令牌（`TP = 0`、`TZ = G`）按未钉
  （X13 回归，防「所有普通登录都被钉在入口 zone」）。断言 `AssignSceneRequest` 的 zone 与 preferred 字段。
- **REDIRECT**：回 `{player_id}` + `RedirectToGate` 指令；`assign`、`claimOwnership`、takeovers **零调用**；不复查位置；`redirectToZone` 失败 / 超时 / `tip ≠ 0` → 回落本 zone 首登并计数；
  回复带指令时设备窗口被注销。
- **第二条腿夺权**：库里 `released = 1` → 一次成功、不发让出请求。
- **夺权后**别 zone 的记录不触发重定向（`rerouteIfStale` 回归）。**订正（落地）**：原写「不触发重新分配」与 §5.8 第 5 步「`rerouteIfStale` 不改」字面冲突；实现照「不改」，别 zone 的记录当作没有，
  于是按首登在本 zone 重新分配一次（与登出墓碑同一条路）。回归用例钉的是「不重定向、不签票、所有分配请求都在 G、只夺一次权」（`EnterGameZoneTravelTest`）。两种口径都只影响落点、不影响归属与数据。
- **`CreatePlayer`**：构造时进程 zone 1、会话 zone 2 → 行上是 2；会话 zone 0 → 2020、`PlayerIdGenerator` 零调用、零插行；丢应答重试命中已有行时 zone 不变；围栏检查点被调用（`OPEN`）。

### 11.8 真 Redis 集成测试（`-Dgame.it.redis`）

- 待落点 (E, q+1) 之后：S 迟到的 (E, q) 写与续期被拒；T 的 (E+2, 1) `o` 覆盖它；TTL 到期后 `find` 为空；(E, q+2) 的回写覆盖 (E, q+1)、被 (E+2, 1) 覆盖。
- `findHolderAsync` 对待落点回 RECONNECT_LEASE；`statusesAsync` 回 RECONNECT_LEASE；`find` 返回节点号 0 的值。
- `RedisGateSource` / `GatePicker` 下沉后的排空叠加与 gateway 现有集成测试一致；同地址去重在真目录上生效。

### 11.9 多 zone 切片用例（`GAME_ZONES=2`，手工 / IT，不进 CI）

> **2026-10-08 登记**：`GAME_ZONES=2` 的切片脚本已由批次 6.5 落地（§5.13 的落地状态），但下表的 MZ1–MZ18 都依赖 5.4 的生产代码，**一条都还没有跑**。
> **2026-10-11：未运行。** 5.4 的生产代码已在 main，但本机（Windows）没有 Redis / MySQL / Kafka，双 zone 切片没有起过，下表**一条都没有跑**；PARITY 因此登记「Java 进行中」，跑完后补运行结论再改「已对齐」。
> 下表的期望按实现核对过一遍，没有改动；与单测的对应关系见文末实现记录「证据」。
> 关于「进不进 CI」：本节的用例（含故障注入）仍是手工 / IT、不进 CI。spectate-spec Q22 说的是另一件事——等 7.1b 的整栈 compose 就位后，把 robot 的 `battle-cross-zone`（以及 5.4 同意时的 `travel`）放进两 zone 覆盖文件里跑；
> 6.5 没有做这一项（`deploy/compose/` 里还没有整栈文件），前置清单留给 7.1b。两处说法不冲突：进 CI 的只是 robot 的正常路径场景，本表不进。

| 编号 | 用例 | 期望 |
|---|---|---|
| MZ1 | z1 玩家 226 `{2, 0}` | 应答 `{0}` → 旁人 51 → 124：票据 `zone_id = target_zone_id = 2`、`player_id` 本人、地址 = z2 gate → 新连接 48 / 26 → 79（z2 的 scene_id）；库里 epoch +2、`owner_released = 0`；`game:location` = (E+2, `o`, z2) |
| MZ2 | 指定地图 226 `{2, c}` | 落 z2 的 c；z2 的 c 没有承载中的频道 → 落 z2 默认主世界 |
| MZ3 | 回家 226 `{1}` | 同 MZ1 反向；金币 = 出发值 + 访客区所加（不回档） |
| MZ4 | 在 z2 断线，30 s 内经 gateway 选 z1 重登 | 26 成功 + 一条 124 → 跟随 → z2 原实例原坐标；全程一条 124 |
| MZ5 | 拿到 124 后不跟随，经 z1 重登 / 经 z2 重登 | z1：不牵引、z1 首登；z2：落待落点地图 |
| MZ6 | 拿 A 的票据在 z2 用 B 的账号进 B 角色 | 26 回 2011 |
| MZ7 | 把 z2 的票据交给 z1 的同号 gate | 握手拒绝（`token not for this zone`）、断开 |
| MZ8 | 停掉 z2 的 gate 后 226 `{2}` | 23 {3027}，留原地、可继续游玩 |
| MZ9 | 停掉 scene-manager 后 226 | 23 {3027}；同节点 63 不受影响 |
| MZ10 | 冻结窗口内另一个 mysql 会话 `SELECT … FOR UPDATE` 持行锁 8 s | 交出超时 → 探测在锁释放后读到 (E, 0) → 23 {3027}，留原地 |
| MZ11 | 两条腿之间（不跟随 124）另一设备从 z1 进同一角色，随后旅客持票进 z2 | 后登录者赢：z1 实例收 2017；库里只有一个持有者 |
| MZ12 | 连发两条 226 | 第二条 13000 |
| MZ13 | 拿到 124 后不关旧连接 | 60 s 后被 gate 关闭；期间旧连接上的请求无回包 |
| MZ14 | 在队时 226 | 应答 3026（同步、≤ 2 s） |
| MZ15 | `redis-cli` 暂停 Redis 2 s 期间发 226 | 3027（组队读超时），留原地 |
| MZ16 | 在 z2 的 gate 上建角 | `player.zone_id = 2`，角色列表 zone_id = 2 |
| MZ17（评审补） | 经 game-data 运维接口给 `SceneSceneClientPlayer/TravelToZone` 写 `deny` 后发 226 | 信封 1003（`MessageContent.error_message`，不是 226 应答体），scene 无日志；删规则 1 s 后恢复（X22） |
| MZ18（评审补） | 把 z2 唯一的 gate 标排空（game-data 运维接口）后 226 `{2}` | 仍放行（全部排空时忽略标记，`GatePicker.java:46-51`）并告警；「只挑未排空的」由 §11.4 单测覆盖（X9），切片不另起第二台 z2 gate |

### 11.10 robot：mmorpg Go robot `travel_smoke` 跨实现契约验收（不改 robot 代码）

> **2026-10-11：未运行。** 本机没有双 zone 切片，也没有 Go 工具链与基线代码；这一项与 §11.9 一起做。

- **配置**：复制 `robot/etc/travel_smoke.yaml`，`gateway_addr: http://127.0.0.1:18081`、`home_zone: 1`、`visit_zone: 2`；口令 = `GAME_LOGIN_DEV_PASSWORD`（或把切片口令设成配置里的 `123456`，
  `travel_smoke.yaml:53`）；账号前缀 `robot_` 在 Java 开发白名单里（`game-login/src/main/resources/application.yaml:84-86`）。
- **前置**：Java 切片 `GAME_ZONES=2`、`GAME_RUN_MODE=dev`（放行 37 GmAddCurrency）；`robot_9601` 首次在 zone 1 建角（X16 保证归属区正确）。
- **期望**：输出 `TRAVEL_SMOKE_OK`；T1–T6 全过：T2 / T2b 同步拒（3024 / 3007）、不发 124；T3 / T5 每跳票据三项、gate 地址变化；T4 停留 35 s 不被踢、不被二次重定向；
  T6 金币不回档；全程恰好 2 条 124（`travel_smoke_scenario.go:367-370`）。这一项直接验证 124 / 票据 / 严格重登的客户端契约逐字节兼容。
- **重跑卫生**：上一轮死在途中时，等位置记录过期（在线 60 s / 租约 30 s / 待落点 ≤ 300 s）再跑，否则 T1 会被 GO-5 的 124 送走（`travel_smoke.yaml:41-43` 同理）。
- 基线自身从没跑通过（§0.1），失败时先分清是 Java 的问题还是基线 robot 的问题。

### 11.11 robot：Java `travel` 场景（新增 `RobotOptions.Scenario.TRAVEL`，子命令 `travel`）

> **2026-10-08 登记**：选项 `--visit-zone`（环境变量 `GAME_ROBOT_VISIT_ZONE`，缺省 2，必须 ≥ 1）**已存在**——批次 6.5 为 `battle-cross-zone` 加的（`RobotOptions` 记录的 `visitZoneId`），帮助文本写的是「另一个区」：
> 目前只写了 `battle-cross-zone` 的用途（B 与观众 C 登录的区；只有这个子命令要求它与 `--zone` 不同，否则参数错误、退出码 2），`travel` 落地时补上「travel 的目的区」并让 `travel` 也做同样的校验。
> T0「缺区报『需要 GAME_ZONES=2』后失败」的写法 `battle-cross-zone` 的 Z0 已照做（`step=preflight`）。本小节的 `travel` 场景本身（`Scenario.TRAVEL`、`RedirectFollower`、226 / 124 的消息号）还没有实现。
>
> **落地（2026-10-11，R0 `888d404`，W6 `bc76c6d` / `e0a7664`）**：`travel`（T0–T11）、`travel-abandon`（R6 / R7）、`travel-long`（R4）都已实现（`scenario.TravelScenario`、留底 `TravelLedger`、纯判据 `TravelChecks`；
> 客户端件 `client.RedirectTarget`、`flow.RedirectFollower`、`PlayerFlow.reenter / reenterOrRedirect`；选项类 `TravelOptions`），用法与步骤以 `game-robot/README.md` 为准。与上表的出入：结果行在规格字段之外还带 `move_ack`、`ticket_binding`（与基线同名同序）与 `hops`；
> T4「坐标不变」用「没有 79、没有自己的 21、旁人没收到 51」判（客户端读不到自己的坐标）；移动探针放在 T7b 之后、只记观察；**T10 发 17 后先等 `leaveSettle`（3 s）再经访客区登录**（W6 评审 major：离场是异步的，墓碑落下之前访客区的 login 读到的仍是出发区的在线记录，按 §5.8 会走 GO-5，那是正确行为）；
> travel-abandon / travel-long 不再拿 gate 端点判「落在出发区」，证据是经出发区入口严格重登时直接进场、没有 124；travel-abandon 没给 `--travel-scene-config` 时取 World 表的第二张世界地图；travel 三个子命令等 79 至少按一跳 60 s。
> 子模式里的**故障注入**（停 scene-manager、停目标 gate，MZ8–MZ11 的 robot 化）没有做，只在 IT / 手工做。**这些场景只在本机假服务端 `FakeTravelWorld` 上跑过单测，没有对真服务端跑过**（未运行，同 §11.9）。

**入口**：`java -jar game-robot.jar travel --zone 1 --visit-zone 2 [--travel-scene-config 0] [--dwell 35s]`。账号按现有场景的命名惯例 `<prefix>tv<runTag>_a`（旅客 A）、`<prefix>tv<runTag>_b`
（旁观者 / 冒用者 B；对照 `CrossNodeScenario.accountName` = `prefix + "xn" + runTag + "_" + role`、`TeamScenario.accountName` 的 `tm`），每轮新号、首登即在 home 区建角，
所以归属区一定是 home，没有「上一轮残留」（不需要基线固定账号的前置，`travel_smoke.yaml:28`）。

**客户端件**：`MessageIds` 加 226 / 124；新 `RedirectFollower`（对照 `redirect.go:104-187`）：解 124、本地校验地址与截止时间 → 每个登录会话最多 3 跳 → TCP 探测 5 s → 连新 gate、
`ClientTokenVerify` 原样转发（10 s）→ **验票成功后才关旧连接**（可配置延迟，用于 T5a；与 Go robot 不同，它在验票之前就关旧连接，`redirect.go:162-180`，两种顺序服务端都要支持）→ 严格重登：48 的角色列表必须有本 player_id，**绝不 CreatePlayer**；26 遇 2005 按 `PlayerFlow` 的
4 次 × 1 s 重试 → 等新连接上的 79。场景按到达顺序保留「(连接序号, 消息号, 包体)」留底，断言「79 在 124 之后」「每个窗口恰好一个 124」（对照 `travel_smoke_scenario.go:435-479`、`:737-799`）。
tip 用生成的表枚举断言，不写数字；请求间隔 1.1 s（避开按消息号限频）；一跳 60 s，同步应答 10 s。

| 步 | 动作 | 断言 | 对照 |
|---|---|---|---|
| T0 | `GET /api/server-list` | 区 1、2 都 OPEN；缺区报「需要 GAME_ZONES=2」后失败 | 基线前提 1 |
| T1 | A、B 经 zone 1 登录（B 尽量与 A 同场景，重登有次数上限，同 `CrossNodeScenario` 做法） | A 角色列表 `zone_id == 1`（归属区登记）；登录窗口内 0 条 124；A GM +11 读回得出发值 G0（非 0、刚变过） | `:209-246` |
| T2 | 226 `{0}`、`{1}`、`{2, 4000000000}` | 依次 3024、3024、3007；无 124；金币 == G0 | `:250-276`（基线只断言 ≠ 0） |
| T3 | A 建一人队 → 226 `{2}` → 解散 | 应答 3026（同步）；无 124 | X4 |
| T4 | 226 `{U}`，U = 9000 + runTag 散列 % 1000（未部署的区） | 应答 `{0}`，10 s 内 23 {3027}；无 124；随后 77 正常应答、坐标不变 | runbook D1；基线 robot 未覆盖 |
| T5 | 226 `{2, conf}` | 应答 `{0}` **先于** 124；B 收到 A 的 51（同场景时）；恰好一条 124：`player_id == A`、`target_zone_id == 2`、`zone_id == 2`、`deadline ∈ (now, now + 305]`；跟随后角色列表里 A 的 `zone_id` **仍是 1**；26 `player_id == A`；79 在 124 之后、gate 地址 == 124 的目标；金币 == G0；GM +7 → G0 + 7 | `:278-291`、`:824-859` |
| T5a | T5 跟随时晚 3 s 关旧连接，期间在旧连接上发 77 | 无回包（REDIRECTED） | X8 |
| T6 | 停留 `--dwell`（缺省 35 s，可设 0）后读金币 | 无 23 / 34 / 124；金币 G0 + 7 | `:293-340` |
| T7 | **GO-5 窗口内经 home 入口重连**：记 z2 的 scene_id 与坐标 → 直接关 TCP（不发 17）→ assign-gate(1) → 握手 → 48 → 26 | 26 无错；随后恰好一条 124（目标 2、`player_id == A`）；跟随后 79 的 scene_id == 记下的值（原实例），坐标误差 < 1 m；金币 G0 + 7 | `travel.md:832-835`（基线 robot 未覆盖） |
| T7b | 再关 TCP → assign-gate(2) 直连 | 无 124；79 的 scene_id 同上 | R3 |
| T8 | 用 T7 那条票据（300 s 内）开新连接到 z2 gate 验票 → 48 以 B 登录 → 26 EnterGame(B 的角色) | 26 回 2011 | MZ6；基线 robot 无 |
| T8b | 同一票据交给 z1 的 gate（同节点号 1） | 握手 `success = false`、`token not for this zone`、连接被关 | MZ7 |
| T9 | 连发两条 226 `{1, 0}`（间隔 < 2 s，仍在限频 3 条 / 秒之内） | 按信封 `id` 配对：第一条 `{0}`、第二条 13000（13000 可能先到，X21）；一条 124 → 跟随 → 79 在 z1；金币 G0 + 7（不回档） | `:342-370` |
| T10 | 17 LeaveGame → 关闭 → assign-gate(2) → 48 → 26 | 无 124（79 之后再等 3 s）；79 在 z2（窗口外按入口落地）；最后 17 | R5 |
| T11 | 汇总 | 124 总数 == 3（T5、T7、T9），每个窗口恰好一条；打印与 Go 同形的 `TRAVEL_SMOKE_OK player_id=… home_zone=… visit_zone=… home_gate=… visit_gate=… back_gate=… gold_home=… gold_back=… reject_tip=… reject_map_tip=…`，外加 `reconnect_gate=…`；退出码即结论 | `:369`、`:376-381` |

**子模式**（各自独立账号，可单独跑）：
- `travel-abandon`（R6 / R7）：拿到 124 后不跟随、关旧连接 → 经 z1 重登 → 落 z1、无 124、金币 == 出发值；另一账号同样不跟随 → 经 z2 重登 → 落待落点地图。
- `travel-long`（R4）：在 z2 关 TCP，等 31 s，经 z1 重登 → 落 z1、无 124。
- **故障注入**（只在 IT / 手工，不进 CI）：停 scene-manager 后 226 → 23 {3027}；放行后停目标 gate → 跟随失败，重登经 z1 落 z1、金币 == 出发值（MZ8–MZ11 的 robot 化）。
- 不为「停在交接窗口」在服务端加后门（基线 runbook G8 也没有），交出窗口内的时序交给 §11.5 单测覆盖。

`game-robot/README.md` 写清前置：`GAME_ZONES=2`、dev 模式、两个区的 gate 都在 gateway 区服列表里。

### 11.12 回归

- 单 zone（`GAME_ZONES=1`）下现有全部场景照旧通过：`smoke`、`reconnect`、`team`、`guild`、`cross-node`；login-test（Go）在 Java 下保持全过，确认单 zone 下**从不发 124**
  （`docs/reference/mmorpg-client-contract-robot.md:514` 第 13 条仍成立）。
- `GAME_ZONES=2` 下 `team` X1 / X2 与 `guild` 第 4 步的跨区步骤开始执行（§5.13）。
- `reconnect` 在 `GAME_ZONES=2` 下加一条：从另一个 zone 的入口重连 → 走 GO-5 回原处。
  **落地（2026-10-11）**：team X1 / X2（新选项 `--team-cross-zone deny / allow`，须与 game-team 的 `game.team.allow-cross-zone` 一致，一次只能跑一支）、guild 第 4 步、reconnect 的跨区重连都已接上，由 `--cross-zone auto / require / skip` 管；
  中断（等不到应答、跟随时握手被拒）只记一条失败、不中止整个场景；trade 的跨区步骤没有接入。单 zone 与双 zone 下的这些回归**都没有在真切片上跑过**（只在假服务端上跑过单测）。
- Go robot 的 `features-smoke` 收到 124 直接关连接、按失败处理（`robot/features_smoke_scenario.go:287-291`：通用重登可能自动建角）：`GAME_ZONES=2` 下必须从角色位置所在的 zone 入口跑，
  或等位置记录过期后再跑；它不是 5.4 的回归失败。Go `login-test` / `battle_smoke` 同理只在单 zone 或正确入口下跑。

---

## 12 评审修订记录

完整性评审（只读核对基线 `26ceb70ca` 与 Java 工作树；本节之外的改动都已就地落进正文，标「评审补」）。抽查引用 40 余处，下面只列改了什么。

**引用勘误**
1. `owner_epoch_test.go:1133 / :1188 / :1234 / :1366`（照抄 `travel.md:806`，已漂移）→ `:1666`、`:1721`、`:1767`、`:1813`、`:1899`；§11.1 另补第一 / 第二条腿系（`:454`、`:1274`、`:1319`、`:1359`、`:1417`、`:1458`）、
   `logic_test.go:912`、`:1564` 与 `gate_redirect_client_endpoint_test.go:182`（陈旧条目去重）的 Java 对应。
2. 进场补发「250 ms → 2 s、20 s」不在 `scene_entry_dispatch.h:35-37`（那里只有扫描周期）→ `scene_route_helper.h:29-41`（另有至多 16 次）；收口动作 `scene_entry_dispatch.h:20-27`。
3. game-match 管理端口 18113 的出处 `match-spec.md:873` → `:35`、`:916`（§0.6 裁决 11、§5.13）；补 6.2 game-battle 12000 / 21200 / 18112 不冲突。
4. 合服围栏 TTL「≥ run 预算 + 30 min」→ `max(run 预算 + 30 min, 1 h)`、每 TTL/3 续期（`fence.go:111-113`）。
5. C++ 调 SM 的 deadline 除 `travel.md:265` 外可引 `scene_manager_service.yaml:7`。

**漏掉的基线行为 / 客户端可见面**
6. §1.5：`IsSceneChangeBusy` 还包括「上次作废交接的标记撤回未确认」（Redis 故障时最长约 305 s 回 3014，`player_lifecycle.cpp:2984-3006`），Java 不适用。
7. §1.7：Go robot 在验票**之前**就关旧连接；Unity 只把 `IsTravelFailureTip` 的 5 个码当传送失败、34 的 reason 决定断线文案（`travel.md:356`、`:382`、`:442`）——据此补 R-T13，并改写 X7 / X19 的客户端影响。
8. §4.1 补旁人在冻结前收到的 66（stopMotion）；§4.2 补四行：受理后的 23 恒在 `{0}` 之后、连发两条 226 时 13000 可能先于延迟的 `{0}`、作废后立即可再试、226 可经 gate 热关停（信封 1003）。
   对应新差异 X21、X22。

**Java 设计的缺口（线程所有权 / 应答保证 / 停服）**
9. 「写完待落点再发帧」没有落点：现有 `PlayerLocations` 写口全是发出即忘（`PlayerLocations.java:6-8`）。§5.3 新增 `awaitingPlacement(…, Consumer<Boolean> onDone)`，回调只投递、回逻辑线程再碰墓碑与发帧；§5.11 写明三个异步回调的投递规则与投递被拒时的处置。
10. 延迟应答可能永不回：`switchInFlight` 只对 RESOLVING 做过期作废、`TeamMembershipReader` 自身没有超时。§5.5 改为组队读套 `orTimeout`，PRECHECK 槽同样过期作废且**作废方先回 3027**（新 TF17），
    `DeferredReply` 只在实例移除或已回过时作废。
11. 停服 / 冲突疏散恰在「已交出、帧未写出」窗口退出（新 TF16、R-T12）：新增 `travelFramesPending` 计数，要求 5.5 的收敛谓词并入（§0.5、§8.3）。
12. REDIRECTED 之前转出、之后才回来的 login 应答（如交叉的 17）没有规定：§5.7 定为应答照常下发、会话指令一律忽略；收口定时器关闭时不推 tip、到点先查会话仍在。
13. 组队读之后、提交之前入队的 TOCTOU（R-T11）：与基线 B-8 同类、窗口更窄，登记不加锁。
14. 第二条腿上位置读失败时「按存档地图」取到的是源 zone 地图（§5.8 表注）。

**与在途规格的矛盾**
15. 本稿的 `PlayerSwitch.kind = TRAVEL` / 指标标签 `kind` 与 5.5 规格的 `SwitchReason` / 标签 `reason`（`scene-drain-spec.md:508`、`:962`）冲突：统一为 `SwitchReason.TRAVEL`、标签 `reason`（新 §0.6 裁决 16，改 §5.3、§5.5、§7、Q12、§11）。
16. 5.2 的「提交后源节点只补写一次 (E, 序号+1)」（`scene-handoff-spec.md:1001-1002`）被本批扩展为至多两次（待落点 (E, q+1)、TF7 / 交叉离开 (E, q+2)）：§0.6 裁决 4 与 §5.9 写明扩展与乱序收敛论证，§5.14 登记回改 5.2 / 5.5 规格。

**测试与 robot**
17. §11.5 / §11.6 补 PRECHECK 过期、回调线程、`travelFramesPending`、连发 226 按 `id` 配对、REDIRECTED 后迟到应答；§11.9 补 MZ17（热关停）、MZ18（全部排空）。
18. Java robot 账号改按现有惯例 `<prefix>tv<runTag>_a / _b`（`CrossNodeScenario.accountName`）；T9 按信封 `id` 配对；注明与 Go robot 关旧连接时机不同、服务端两种都要支持。
19. §11.12 补：Go `features-smoke` 收到 124 即按失败关连接（`features_smoke_scenario.go:287-291`），双 zone 下要从角色所在 zone 跑。
20. §6.2 补复用的 gate 握手超时 30 s、login 分配 / 夺权等待 5 s / 3 s、gate → login Dubbo 超时 5 s 及其超时出口。

核对后**未改**、确认成立的要点：226 / 124 / 23 / 34 帧与字段、tip 与消息号行号、`GateTokens` 验票顺序与五条文案、票据字段与 hex 签名、SM 第一条腿顺序（`enterscenelogic.go:337`、`:372`、`:384`、`:395`、`:413`、`:420`、`:1018-1099`、`:1123-1168`）、
登录 2011 位置（`entergamelogic.go:78-87` 早于 `:89-95`）、`TicketPinsZone`、GO-5 去向、`updateStateAndRelease` 不要求 `released = 0`（裁决 2 的前提）、RELEASE 模式改判表与 Superseded 论证、`PlayerLocationDirectory` 三个读者对 `l` 的处理、
本机切片端口无冲突（含 6.2 / 6.4 / 5.1 规划）。

---

## 实现记录（2026-10-11）

> **改名说明（2026-10-12，`8cbba6c`）**：本节写于 `xm` 前缀统一改为 `game` 之前。命令、模块名、环境变量、配置键、日志 / PID 文件名、指标名、测试类名已按新名改写；当时实际的输出、日志与 CI run 里是 `xm-*` / `XM_*` / `xm.*` / `xm_*` / `-Dxm.it.*`（库 `xm_java`），对照旧日志时按此换算。

范围：226 TravelToZone 全流程（同步段、延迟应答的在队检查、选目标与签票、交出并释放、待落点、重定向帧）、124 的两个来源（传送、登录期 GO-5）、票据的持票者绑定与钉 zone、
建角取会话 zone（X16）、合服围栏检查点 F1 / F2 / F4（恒放行）、gate 目录同地址去重、指标与配置、Java robot 的 `travel` 三个子命令与 team / guild / reconnect 的跨区步骤。
没有新进程、没有新 Redis 键（待落点复用 `game:location:{pid}`）、没有改表结构（`PlayerMapper` 只把 `owner_released` 的取值改成参数）、没有新增第三方依赖（`tech-stack.md` 不改）。

过程：X16 先行（`0765a18`，与 PARITY「归属区映射」行的登记同一提交）→ 下沉重构（`9a81db3`：`GateTokenIssuer` 到 game-common，`GatePicker` / `GateSource` / `RedisGateSource` 到 game-discovery）→
先行件 a（`ffb6168`：协议字段、票据签发、待落点写口、交出并释放的存储口）、b（`813dfd6`：game-scene 的冻结接口、装配、指标与测试替身，226 仍未注册）、c（`f7143b0`：game-gate 的共享文件与 scene-manager 的选路骨架）、
robot 先行件 R0（`888d404`）→ 先行件合拢（`77b245b`、`14ea692`）→ 六个工作包各在自己的 worktree 里并行实现、各自接受一次独立的对照规格评审、再修正
（W3 scene-manager `bca69b9`；W4 gate `de19d45`；W5 login `bdbb7bd` / `89d7f3b`；W6 robot `bc76c6d` / `e0a7664`；W1 scene 适配层 `49d5086` / `f2c4264`；W2 scene 226 状态机 `83e884d` / `52d6875`；
提交号是合进 main 之后的，与工作包分支上的不同）→ 合并后五个视角的跨模块评审，每条意见两个反驳者复核 → 合拢评审修正（`b24582d`、`e7ff9a2`）→ 文档与登记（`a6d1867`）→ 文档核对修正（两位核对者对登记的意见逐条回到代码复核后的订正，见该提交正文）。
工作包代号 W1–W6 是开工时的分工名，与 §9 的有意差异编号 X1–X22 无关。正文已按本节的事实回写（标「落地」）；本节只记正文里放不下的东西。

### 落地的类（按模块、按包）

| 模块 | 提交 | 类 / 文件 |
|---|---|---|
| game-api | 先行件 a | 新 `game/api/zone_redirect.proto`（`ZoneRedirect` 七个字段）；`node_link.proto` 的 `PlayerTransfer.redirect = 7`（原 `reserved 7`，注释写明滚动升级先 gate 后 scene）；`client_call.proto` 的 `SessionContext.ticket_player_id = 8` / `ticket_target_zone_id = 9` 与 `SessionDirective.redirect_to_gate = 5`（`RedirectToGate`）；`scene_directory.proto` 的 `SelectTravelTarget*` / `RedirectToZone*`（`SelectTravelTargetRequest` 的 5 号字段留给 7.3 的 `home_zone_id`）；`node_directory.proto` 的 `GateNodeInfo.started_at_ms = 8`；`SceneDirectoryService.selectTravelTarget / redirectToZone` |
| game-common | `9a81db3`、先行件 a | `com.game.common.token.GateTokenIssuer`（从 game-gateway 下沉；新 `issueRedirect(gateNodeId, gateZoneId, playerId, targetZoneId)`、常量 `REDIRECT_TICKET_TTL` = 300 s，不带会话密钥，任一参数为 0 拒签） |
| game-discovery | `0765a18`、`9a81db3`、先行件 a、W3 | `zone.ZoneMergeFence`（`inProgress` / `inProgressAsync`、`OPEN`）；`gate.{GatePicker, GateSource, RedisGateSource}`（下沉；`GatePicker` 的同地址去重是 W3）；`location.PlayerLocationDirectory.awaitPlacementAsync` 与 `AWAIT_PLACEMENT_MAX_TTL`；`SceneAssetLocator`、`RedisKeys.playerLocation`、`location.proto` 的注释 |
| game-player-store | 先行件 a | `PlayerStore.HandOffMode {HOLD, RELEASE}` 与六参数 `handOffOwnership`（五参数形式 = HOLD）；`PlayerMapper.updateStateAndHandOff` 多一个 `released` 参数（只改 SET） |
| game-scene | 先行件 b、W1、W2、`b24582d` | `world`：新 `SwitchReason {PLAYER, TRAVEL}`、`DeferredReply`、`ZoneTravel`（装配记录，`DISABLED` = 226 同步回 3027）、`TravelTargets`、`TeamChecks`；`SwitchPhase.PRECHECK`；`PlayerSwitch`（reason、目标 zone、延迟应答、`expired`、选中后的 `ZoneRedirect` 与解析后的地图）；`PlayerCall.defer()`；`TransferTombstone.forRedirect`；`ClientSink.playerRedirect`；`PlayerLocations.awaitingPlacement`（default 不写、回 false）；`PlayerRepository.handOff(…, HandOffMode, …)` 与结局 `Superseded`；`SceneWorld`（`requestTravel`、`onTravelTeamChecked`、`onTravelTargetSelected`、`freezeForTravel`、`sendRedirect`、`onPlacementWritten`、`onRedirectWriteFailed`、`concludeExpiredTravel`、`travelFramesPending()`）；`ClientRequestHandler`（注册 226、已延迟的调用不补 1006）；`SceneMessageIds.travelToZone`。`link.GateLinks`（重定向帧，与改绑指令共用写帧与两条失败口）；`location.RedisPlayerLocations.awaitingPlacement`；`storage.StoragePlayerRepository`（RELEASE、按模式改判与探测）；新 `team.TravelTeamChecks`；`transfer.SceneManagerSwitchTargets.selectTravel`；`metrics.SceneMetrics`（`TransferReason`、`TravelRequest` / `TravelResolve` / `TravelPlacement`、`travelLinkGone()`、`travelFrameWithheld()`、`BattleGate.ZONE_TRAVEL`）；`SceneNode` 装配 `ZoneTravel`、`SceneNodeProperties`（`game.scene.travel.team-check-timeout` 与跨键校验 `requireTravelBudget`） |
| game-scene-manager | 先行件 c、W3、`e7ff9a2` | `travel.TravelRouting`（接口）、`travel.TravelRouter`（真实现）、`travel.TravelConfiguration`（装配与必填密钥）；`SceneDirectoryProvider` 原样委托两个新方法；`application.yaml` 头部登记 `GAME_GATE_TOKEN_SECRET` |
| game-gate | 先行件 c、W4、`e7ff9a2` | `session.ClientDispatcher`（握手存票据字段、`context` 带 8 / 9 号字段、`onTravelRedirect`、`redirectByLogin`、`invalidRedirect`、`enterRedirected`、`onRedirectLingerExpired`、REDIRECTED 下的请求 / 指令收口）、`session.ClientSession`（`ticketPlayerId`、`ticketTargetZoneId`、`redirected`、收口定时器）、新 `session.PushMessageIds`、`session.GateLimits` / `GateProperties`（`redirect-linger`）、`session.SceneEventRouter`、`metrics.GateMetrics`（`redirects{source, result}`、`REDIRECT_LINGER`、`REDIRECTED`）、`GateNode`（解析 124、目录条目带 `started_at_ms`）、`GateConfiguration`（缺密钥报错点名 scene-manager） |
| game-gateway | `9a81db3`、`e7ff9a2` | 主代码只有下沉（`GatePicker` / `GateSource` / `RedisGateSource` / `GateTokenIssuer` 移出，`GatewayConfiguration` / `AssignGateService` / `QueueDispatcher` 改 import）与缺密钥报错的文案、`application.yaml` 头注释；去重随 game-discovery 的 `GatePicker` 生效；其余是测试（`GatewayHttpApiTest`、`QueueDispatcherTest` 等） |
| game-login | `0765a18`、W5 | `handler.CreatePlayerHandler`（会话 zone、F1）；`handler.EnterGameHandler`（持票者 2011、去向、GO-5、F2、预算）；新 `handler.EnterRoutes`；`handler.PlayerLocationLookup`；`LoginClientMessageService`（带 `RedirectToGate` 指令时注销设备窗口）；`LoginConfiguration`（围栏 bean、预算注入）；`LoginProperties`（`enter-game-budget`）；`metrics.LoginMetrics`（`EnterRoute`、`ticket.holder.rejected`） |
| game-robot | R0、W6、`77b245b` | `TravelOptions`、`RobotOptions`（`Scenario.TRAVEL / TRAVEL_ABANDON / TRAVEL_LONG`、`--cross-zone`、`--team-cross-zone`）、`RobotMain`；`client.{RedirectTarget, GateEndpoint, GameConnection, RobotClient}`；`flow.{RedirectFollower, PlayerFlow, EnterOutcome, EnteredPlayer, RoleZone}`；`scenario.{TravelScenario, TravelLedger, TravelChecks, CrossZoneGate}`，`TeamScenario`（X1 / X2）、`GuildScenario`（第 4 步）、`ReconnectScenario`（跨区重连）、`TradeScenario` / `BattleCrossZoneScenario`（注释）、`BattleSettleChecks`（认 zone_travel 闸）；`README.md` |
| deploy | `e7ff9a2` | `deploy/compose/env.example`：`GAME_GATE_TOKEN_SECRET` 的必填清单加 scene-manager |

### 与规格原稿的出入

行为上的出入都已回写进正文相应小节（标「落地」），这里汇总，便于对照原稿：

- **scene（W1 / W2）**：RESOLVING 槽过期推 23 {3027}、计 `resolves{error}`（原稿「照 5.2 静默清槽」，W2 评审意见 1）；每秒维护也作废过期的 PRECHECK / RESOLVING 槽（TF17 的加强）；在队检查 / 选目标同步抛异常按失败收口；
  选中结果在 world 里再核对一遍并多查 `target_zone_id`；TF7 的 `link_gone` 是 `handed_off` 之外的附加计数；≥ E+2 计 `superseded`（原稿 taken_over）；交出改判只在有过结局不明的更早尝试时判 Superseded；
  §5.2 表里「不可能」的两格在交出改判里是 Fenced、在探测里是 Lost；跨 zone 的墓碑在帧还没定之前不过期、不随链路断开或停服清；`Purpose` 仍表达「怎么交出」，`SwitchReason` 是唯一的「为什么」判别字段；
  `awaitingPlacement` 的空参数当场抛 NPE。
- **scene-manager（W3）**：内部码 3003 / 3027，travel 多一个 `fenced`；多挡 `from_zone_id == 0`；只认主世界种类的频道；同地址 `started_at_ms` 并列都留下、地址按原串比较；F4 在 7.3 之前判 zone 0；指标名 `game.scene_manager.*`。
- **gate（W4）**：非法重定向计 `transfer_failed`；已重定向会话再握手静默丢弃；排队请求计 `redirected`；重复 / 迟到指令计 `redirects{login, stale}`；没装配 124 号按非法收口；挂着场景时先离场的防御；`isBlank`；login 来源非法时 26 已先发。
- **login（W5）**：新增 `game.login.enter-game-budget`；`route=pinned` 的口径；签票耗时计进 `backend_calls{method=redirectToZone}`；GO-5 多查 `target_zone_id` 与端口无符号越界；记录 zone 为 0 按首登；签票回来时队列满不计 `enter_routes`；
  `rerouteIfStale` 照「不改」（§11.7 措辞订正）；原稿 §6.2「迟到的 `RedirectToGate` 按会话已断忽略」的说法订正（迟到应答到不了 gate）。
- **robot（W6）**：结果行多 `move_ack`、`ticket_binding`、`hops`；T4 的判据；新选项 `--team-cross-zone`；trade 的跨区步骤不接（`require` 是参数错误）；travel-abandon 缺省目标图取第二张世界地图；等 79 至少 60 s；T10 先等 3 s；
  abandon / long 不比 gate 端点；故障注入不做。
- **合拢评审**：新指标 `game.scene.travel.frames.withheld` 与 §7.3 第二条的改式（D1）；部署模板的密钥分配（D2 / E1）。
- **规格本身的勘误**：§2.3 TF8 的指标列（应是 gate `redirects{travel, stale / orphan}`）；§7.2 漏了 `unavailable`、`in_battle`、`fenced`、`frames_pending`、`frames_withheld`，gate 请求计数的名字是既有的 `game_gate_client_requests_total`；§5.14 漏列部署侧的三处密钥表。

### 评审的发现与处理

**工作包评审**（每个工作包合并前一次独立的对照规格评审；评审者都复跑了工作包的模块单测）：

| 工作包 | 结论 | 发现 | 处理 |
|---|---|---|---|
| W1 scene 适配层 | 通过，2 条 minor | ① 「逻辑线程已停」三条用例里「不该执行 / 不抛」的断言是空的：投递跑在 `CompletableFuture.whenComplete` 的回调里，回调抛的 `AssertionError` 被吞成依赖 future 的异常完成 | 成立，修（`f2c4264`）：`onDone` 只记录、等完成线程跑完再断言、恰好投递一次；新测试工具 `testing.CapturedLogs` 数「丢弃」日志；同文件 5.2 起就有的 63 同类用例一并改写；两种变异（被拒分支补调 `onDone` / 改成重新抛出）下这 4 条都失败 |
| | | ② `ZoneTravelSeamsTest`「在队检查的占位_逻辑线程已停时结果丢弃不抛…」换上真实现后测不到投递被拒（读口 future 永不完成，结果要等 `orTimeout` 2 s，`pending()` 恒为 0），另有三处「占位」字样过时 | 成立，**暂缓**（W2 的文件；同一行为的有效覆盖在 `TravelTeamChecksTest`）。文档阶段只订正了注释，用例本身的弱点登记为遗留 |
| W2 scene 226 状态机 | 基本合格，2 条 minor | ① RESOLVING 槽过期静默清槽、迟到结果按 stale 丢弃：客户端已收 `{0}` 却可能等到 75 s 都没有结论 | 成立，修（`52d6875`）：`concludeExpiredTravel`、`expired` 标记、每秒维护扩到两个阶段；原用例把旧行为钉死了，改写并新增三条 |
| | | ② §11.5「冻结闸沿用 5.2 用例，加 reason = travel」只覆盖一部分 | 成立，修：`FreezeGateTest` 的冻结入口参数化（63 远端 / 226 选中），矩阵与两条原地解冻用例两种冻结各跑一遍，「冻结之前的在途不冻结」对三种在途各跑一遍（6 → 11 条） |
| W3 scene-manager | 通过，无缺陷 | 不阻塞的记录：F4 判 zone 0（7.3 必须同批改）；`fenced` 与内部码要进规格；`QueueCapacity.budget` 对陈旧条目重复计数；`env.example` 缺 scene-manager；拒启用例没覆盖「完全不设」（null，分支是对的） | 记录；env.example 由合拢评审 E1 修，其余登记为遗留 / 写进正文 |
| W4 gate | 通过，无缺陷 | 评审者独立复算了 124 金样的字节；「粗测」几处关键守卫的变异是读代码推断、没有实跑 | — |
| W5 login | 无 blocker / major，2 条 minor | ① GO-5 回落链（签票 + 分配 + 夺权）可能超过 gate 调 login 的 5 s，迟到应答被 Dubbo 丢弃，夺到的归属无人接、锁住约 30 s；实现者「迟到的 EnterScene 走 gate 现有的迟到处理」的说法不准确 | 成立，修（`89d7f3b`）：`game.login.enter-game-budget`，整条链按剩余预算收紧，普通路径的同类残余一并收掉；预算不含派发排队时间，登记为残余 |
| | | ② 测试执行器是 `Runnable::run`，没覆盖签票后回工作线程与回线程时队列满 | 成立，修：手动泵的工作线程池替身，补三条；把 `handleAsync` 改成 `handle` 的变异下三条都失败 |
| W6 robot | 修掉 major 后可合入 | ① **major**：T10 登出后立刻经访客区登录，与离场处理有竞态，按规格行事的服务端会回 GO-5 的 124 | 成立，修（`e0a7664`）：`leaveSettle` 3 s，仍收到 124 时失败详情点明 GO-5 / 竞态 |
| | | ② 跨区步骤的异常没接住、与注释不符（reconnect、team；guild 同类） | 成立，修：中断只记一条失败，reconnect 经本区入口重新进游戏交给第 4 步，team 确认 C 不在 A 的队里；新增 `ReconnectScenarioTest` |
| | | ③ `trade --cross-zone require` 被接受却从不执行（假绿） | 成立，修：改成参数错误（退出码 2） |
| | | ④ X2 的检查名不副实 | 成立，修：纯判据 `TeamScenario.crossZoneJoinProblems` |
| | | ⑤ abandon / long 用 gate 端点判「落在出发区」，多 gate 时误报 | 成立，修：只记观察 |
| | | （规格缺口）§11.11 子模式的故障注入没做 | 不在任务书的三个子命令里，**延后**（规格写明只在 IT / 手工做） |

**合并后的跨模块评审**（五个视角：正常路径、失败路径、GO-5 与票据、配置与装配、回归；每条意见两个反驳者复核）：共 3 条意见，复核**全部成立、驳回 0 条**，都在主仓库修正并全量构建：

| 编号 | 级别 | 发现 | 处理 |
|---|---|---|---|
| D1 | minor | §7.3 第二条勾稽在实现的计数口径下核对不了：帧发出前被交叉离开消费的旅途在任何指标里都看不到，帧发出后才消费的 gate 已计 stale / orphan、原式会算两遍；`GateMetrics.RedirectResult` 的 javadoc 式子漏了 link_gone / 墓碑 / pending | 修（`b24582d`）：`onPlacementWritten` 的「墓碑已被消费、不发帧」分支计 `game.scene.travel.frames.withheld`；式子改为 handed_off ≈ gate redirects{travel, sent+invalid+stale+orphan} + link_gone + withheld + pending，写进两处 javadoc；`ZoneTravelHandOffTest` 钉帧前 / 帧后 / 写不出三种，去掉计数调用后两条用例失败 |
| D2 / E1 | minor | scene-manager 缺 `GAME_GATE_TOKEN_SECRET` 即拒启，但 `deploy/compose/env.example`、deploy-ci-spec §6.1、ops-release-spec §7.2 的按进程密钥表仍只分给 gate、gateway（README 的注释同样不全）；本稿 §5.14 也漏列了这几处 | 修（`e7ff9a2`）：env.example 的必填清单加 scene-manager 并注明用途与「三个进程原值逐字节相同」；gateway / gate 缺密钥的报错同时点名 scene-manager；`TravelConfigurationTest` 加 env.example 的文本核对（换回旧模板时失败）。两份规格与 README 在文档阶段改（见下） |

五个视角逐项核对过、没有列为发现的要点（摘要）：226 同步段与在队检查的顺序与槽期限；`{0}` 的 `0a 00` 字节与「226 应答恒先于 124」（同一条链路、同一个会话 EventLoop、FIFO）；选目标请求字段与 3 s / 4 s 的时限关系；
`TravelRouter` 的地图预检、去重、签票字段与原字节透传；scene、gate、login、robot 四处对重定向载荷的校验一致（scene 一侧最严，凡 scene 发出的帧都过得了 gate）；RELEASE 交出的 SQL、改判与探测表；
待落点 (E, q+1) 与 TF7 / 交叉离开 (E, q+2) 的序号来源与 `WRITE_IF_NEWER` 收敛；交叉的 17 / `PlayerLeave` 与重定向帧的四种到达顺序；各结局的 tip 与断开方式；R-T2（旅途中另一设备登录，后登录者赢）；
待落点与 TTL 到期的去向；停服（TF16）只保留旅途墓碑；「`l` + 节点号 0」的全部读者（含规格读者清单之外的 game-match / game-battle，按重连租约跳过）；gate 不 `abandonEnter` 的前提在唯一的发帧路径上成立；
票据链路（普通令牌 TP = 0、重定向票据 TP = 持票者，他区同号 gate `WRONG_ZONE`）；一次动作至多一跳；设备窗口两侧都注销且幂等；时限链（226 应答最坏约 4 s、受理到结论约 25 s、login 预算 4 s < 5 s）；
装配无重复 bean、三个进程的密钥取法一致；全部新指标预注册、没有高基数标签；X22 热关停的规则键 `SceneSceneClientPlayer/TravelToZone`；5.2 / 5.3 / 6.3 / 4.3 / 5.1 / 资产通道 / 运维夺权的回归；
`transfer-tombstone-ttl` 调小不会吞掉 124（旅途墓碑不过期，用临时单测证实后删除）。评审期间各 worktree 只读或已还原。

### docsFollowups 的落实

合并后评审与各工作包留给文档阶段的事项，逐条：

1. §7.2 补 `game_scene_travel_frames_withheld_total`（scene，Counter，无标签）——已补，另补 `frames_pending` 的 gauge 行。
2. §7.3 第二条改式、删掉「被墓碑消费的次数」——已改。
3. §5.5 墓碑一条补「墓碑被消费、不发帧时计 withheld」；§2.3 TF8 的指标列改成 gate `redirects{source=travel, stale / orphan}`——已改。
4. §7.2 的取值：`travel_requests` 补 `unavailable`、`travel_resolves` 补 `in_battle`、`transfers` 与 TF6′ 的 taken_over 改 superseded；另补 travel 的 `fenced`、login 的 `backend_calls{method=redirectToZone}`、gate 请求计数的实际名字——已改。
5. deploy-ci-spec §6.1 秘密矩阵、ops-release-spec §7.2 Secret 注入表：`GAME_GATE_TOKEN_SECRET` 加 scene-manager——已改。
6. README 快速开始里 `GAME_GATE_TOKEN_SECRET` 的注释提到 scene-manager——已改。
7. §5.14 补漏列的部署侧文档——已补。
8. 新指标是否进 PARITY / architecture 的指标表、ops-release-spec 是否加告警：architecture.md §11 已登记 withheld 与全部 5.4 指标；PARITY「跨 zone 传送」行提到它；**告警没有加**（ops-release-spec 的告警清单不在本批改动范围，候选：「§7.3 第二条的残差持续大于 0」「`travel_frames_pending` 长时间不归零」），登记为遗留。
9. （W5）§6.2 / §5.12 登记 `game.login.enter-game-budget`，订正「迟到的 `RedirectToGate` 按会话已断忽略」；architecture.md 归属协议第 4 步「只能等租约过期」的清单补「gate 调 login 超时」——已改。
10. （W5）§11.7 `rerouteIfStale` 的措辞——已改为「不触发重定向」并写明实现口径。
11. （W3）内部码 `TIP_NO_GATE` / `TIP_ZONE_FENCED` 与 `fenced` 进 §5.4、§7.2——已改。
12. （W2）scene-battle-spec §7.13 的闸门表加 226；5.5 规格登记 `SwitchReason` / `PRECHECK` / `travelFramesPending` 已落地——已改。
13. （W4）盘点 `gate.md` cross-zone-redirect / redirect-ticket-binding 的 java 列、architecture.md §4.1 / §4.2——已改。
14. （W6）§11.11 / §11.12 的 robot 落地与 `--team-cross-zone`——已在正文落地注与 PARITY 登记。
15. （W1）`TravelTargets.TravelSelection.Failed` 的 javadoc 补「`target_zone_id` 对不上」；`ZoneTravelSeamsTest` 过时的「占位」字样——文档阶段只改了注释（不改行为，见「遗留」）。

### 证据

**全量构建**（合拢评审修正之后，代码 `e7ff9a2`）：本机 Windows，便携 JDK 21.0.12 + Maven 3.9.9，`mvn -o -B -fae clean install` → 27 个模块 BUILD SUCCESS，**9760 条用例，0 失败，0 错误，796 条跳过**。
本机**没有 Redis / MySQL / Kafka**，跳过的就是缺省关闭的 `-Dgame.it.*` 集成测试与基准——其中包括 5.4 新写的 `AwaitingPlacementRedisIntegrationTest`、`TravelRouterRedisIntegrationTest`、`RedisGateSourceIntegrationTest`；
`PlayerStoreSqlTest` 里 5.4 的新用例（含探测阻塞与两个并发用例）只在 H2 上跑过，`EnterGameSecondLegSqlTest` 也是 H2。
推送情况：`0765a18` … `14ea692`（X16、下沉重构、先行件 a / b / c、R0 与先行件合拢）已在 `origin/main` 上，push main 会触发 `ci.yml` 与 `integration.yml`，它们的运行结论本节没有查看（本机没有 `gh`）；
六个工作包与合拢评审修正（`bca69b9` … `e7ff9a2`）以及文档提交还**没有推送**，所以 5.4 的功能实现没有 GitHub Actions 的运行记录，上面三个真 Redis 集成测试类也都在这一段里（见下文「给 7.1b / 5.5 / 7.3 的接口与义务」）。

**模块级的运行**（各提交正文里记下的汇总行，离线 `-o`、`-Dtest` 限定本模块，上游模块只编译；各行对应的是当时那个分支 / 时点的代码，**条数只供对照规模，以上面的全量构建为准**）：

| 时点 | 模块与结果 |
|---|---|
| 先行件 a `ffb6168` | 全仓 `-DskipTests clean install` 通过；game-api / game-common / game-discovery / game-player-store / game-scene-manager / game-data 带真 Redis 与真 MySQL 共 1063 条通过；game-login 181、game-scene 1417、game-gate 214 整模块通过 |
| 先行件 b `813dfd6` / c `f7143b0` | game-scene 1463 条（带真 Redis 与 Kafka）；game-gate 230、game-scene-manager 242（带真 Redis）——那时 226 还没注册、两个新 RPC 以异常完成 |
| 先行件合拢 `14ea692` | `clean install` + 真 Redis / MySQL / Kafka，27 个模块，9452 条 0 失败 0 错误 2 跳过——**六个工作包都还没合入**，不是 5.4 功能的证据 |
| W1 `49d5086` / `f2c4264` | game-player-store 70 条（4 跳过）、game-scene 1495 条（8 跳过，含 4 条真 Redis 用例）；`PlayerStoreSqlTest` 另连跑 3 轮（H2） |
| W2 `83e884d` / `52d6875` | game-scene 1522 → 1530 条（4 跳过）；其中 `FreezeGateTest` 11、`ZoneTravelRequestTest` 31、`ZoneTravelHandOffTest` 27、`ZoneTravelSeamsTest` 8 |
| W3 `bca69b9` | game-discovery 253（116 跳过）、game-scene-manager 273（14 跳过）、game-gateway 81（11 跳过）；`TravelRouterTest` 26、`GatePickerTest` 15 |
| W4 `de19d45` | game-gate 255 条，0 跳过；`ClientRedirectTest` 21 |
| W5 `bdbb7bd` / `89d7f3b` | game-login 272 → 281 条（11 条 Redis 集成测试跳过）；`EnterRoutesTest` 46、`EnterGameZoneTravelTest` 45 |
| W6 `bc76c6d` / `e0a7664` | game-robot 597 → 606 条，0 跳过；`TravelScenarioTest` 21、`ReconnectScenarioTest` 3 另连跑 2 遍 |

**判别力核对（变异）**——都是「临时改错、跑对应用例确认失败、还原」：W1 被拒分支补调 `onDone` / 改成重新抛出（4 条失败）；W4 去掉断线流程里撤销收口定时器（对应用例失败）；W5 `handleAsync` 改 `handle`（三条线程用例失败）；
D1 去掉 `SceneWorld` 里 withheld 的计数调用（两条失败）；E1 换回旧的 env.example（文本核对失败）。W4 评审对另外几处守卫的「变异」只是读代码推断，没有实跑。

**没有运行的验证**（所以 PARITY 是「Java 进行中」）：
- 双 zone 本机切片（`GAME_ZONES=2`）一次都没有起过：§11.9 的 MZ1–MZ18 一条都没跑；
- Java robot 的 `travel` / `travel-abandon` / `travel-long` 与 team X1 / X2、guild 第 4 步、reconnect 跨区重连只在假服务端（`FakeTravelWorld` 等）上跑过单测，没有对真服务端跑过；单 zone 下的回归场景（§11.12）也没有在切片上重跑；
- §11.10 的 mmorpg Go robot `travel_smoke` 跨实现验收没有做；
- 5.4 新写的真 Redis 集成测试三类、`PlayerStoreSqlTest` 的真 MySQL 档没有运行；
- 故障注入（MZ8–MZ11 的 robot 化）没有做。

### 遗留与残余风险

设计层面的残余已写进正文（§2.4、§8.2 的 R-T1–R-T13）；这里列实现与测试层面仍然成立的：

- **login 的预算不含派发排队时间**：从 `EnterGameHandler.handle` 起算，派发层工作队列里排队超过约 1 s 时，迟到夺权（归属无人接、要等 30 s 租约）的窗口仍在；彻底消除要 gate 在 `ClientCall` 里带截止时间（或透传 Dubbo 的剩余超时），跨 game-api / game-gate，没有做。
- **`QueueCapacity.budget` 对陈旧条目重复计数**：登录排队的容量预算仍按原始 gate 目录求人数和，崩溃重启的 gate 的旧条目在 15 s 的 TTL 内被重复计入，只让预算偏保守。
- **trade 的跨区步骤没有接入**（`trade --cross-zone require` 是参数错误，`auto` 只记观察）。
- **故障注入的 robot 化没有做**（停 scene-manager、停目标 gate；规格只要求 IT / 手工）。
- **`ZoneTravelSeamsTest` 的一条用例测不到它名字里说的东西**（W1 评审意见 2）：文档阶段只订正了注释；有效覆盖在 `TravelTeamChecksTest`。改法：读口立即完成（或给很短的上限并等到投递），`onDone` 记录而不是抛，断言投递被拒且回调没执行。
- gate `onRedirectLingerExpired` 里「不在会话线程就改投递」的防御分支没有单测（按构造到不了，`EmbeddedChannel` 也模拟不出跨 EventLoop）。
- 拒启用例只覆盖「设了但为空 / 全空白」的密钥，没有覆盖完全不设（分支是对的）。
- robot：T8b 要求两个区的第一台 gate 节点号相同（切片上都是 1 号），不同时服务端回的是 `token not for this gate`；team X2 要 game-team 配 `game.team.allow-cross-zone=true` 再用 `--team-cross-zone allow` 跑，本机切片缺省只走 X1；T10 的 3 s 是否够要看切片上的离场日志；
  `FakeTravelWorld` 是按规格手写的替身，只证明探针一侧的判定。
- **7.3 的义务**：F4 改判请求里的 `home_zone_id` 并把 0 当读不到（同批接 Redis 围栏，否则静默放行）；`RedirectOnEnter`；合服作业的写侧约束（roadmap 7.3 行）。
- **5.5 的义务**：收敛谓词并入 `travelFramesPending`（TF16 / R-T12），`SwitchReason` 追加 EVACUATE；5.5 落地之前，停服 / 冲突疏散恰在「已交出、帧未写出」窗口退出时客户端收不到 124（数据完整，重登即可）。
- 没有加告警（§7.3 残差、`travel_frames_pending` 不归零），归 ops-release-spec 的告警清单。
- 客户端侧：23 {3027} / {2017} 后紧跟断线时 Unity 显示通用断线文案（B-7 / R-T13）；Unity 是否按信封 `id` 配对 226 的应答没有在客户端代码上复核（X21）。

### 给 7.1b / 5.5 / 7.3 的接口与义务

- **7.1b（整栈 compose 与 CI）**：scene-manager 的容器要注入 `GAME_GATE_TOKEN_SECRET`（三个进程原值逐字节相同）；两 zone 的覆盖文件里可以加 robot `travel`（spectate-spec Q22 的同一份前置清单）；5.4 的三个 `game.it.redis` 集成测试类都是类级的 `@EnabledIfSystemProperty(named = "game.it.redis")`，落在 `TestReport --require-it-executed` 的扫描范围里——推送之后 CI 的 Integration 作业会要求它们真的执行，那将是它们的第一次运行。
- **5.5**：读口 `SceneWorld.travelFramesPending()`；`SwitchReason` 在 `com.game.scene.world`；疏散占着 `switching` 槽时 226 回 13000 / 3014、已在 TRAVEL（含 PRECHECK）的玩家被疏散跳过，都按本稿 §0.5 已实现的口径；
  5.4 只给 `game.scene.transfers` 加了 `reason`，`game.scene.switch.resolves` 与 `game.scene.transfer.freeze` 没有加（226 的选目标计在 `travel.resolves`）。
- **7.3**：见上一节与 PARITY「合服围栏与合服」行。

### 最终验证

**还没有做。** 本节写成时（2026-10-11）唯一的运行证据是上面的本机离线全量构建；双 zone 切片、robot 端到端、Go robot 跨实现验收与真依赖的集成测试做完后，在这里补记命令、环境、各场景的检查项数与结论，
并把 PARITY「跨 zone 传送（226）与重定向（124）」「归属区映射」两行改为「已对齐」（先例：6.5 的 `976d7ff` → `775f991`）。

## 基线后续修正（2026-10-12 复核，mmorpg `73b852f3`）

批次 0.3 增量盘点时核对了 GO-2 与 CPP-3 在旧基线之后的改动（出处 `docs/porting/inventory/delta-20261010.md` §6 的 d10-go2-followups、d10-zone-travel-main-chain、d10-cpp3-*）。
**结论：5.4 的 Java 代码不需要跟改**；下面只记基线指针、行号与文档层面的更新，正文不改写，收尾（MZ1–MZ18 跑完、改 ✅）时按这里更新引用。

- **基线指针**：头部写的 `26ceb70ca` 在 mmorpg 整理后的历史里已不存在，代码等价点是 `e8e6ed1c2`（2026-09-30）；新基线 `73b852f3`。头部的 `D:\work\mmorpg` 读作 `D:\luyuan\xuanming-server-mmo-cpp`
  （`travel.md` = `docs/design/cross-zone-scene-travel.md`、`runbook.md` = `docs/ops/cross-zone-failure-test-runbook.md` 路径不变）。
- **GO-2**：根治的代码本体（`luaRollbackPlayerPlacement`、`planRouteRollback`、`classifyRollbackReply`、`kLuaJudgeTravelOutcome`、`JudgeTravelOutcomeReply`、`HandleTravelMarkWriteRejected`、`PlayerLocation.rollback_receipt = 7`、
  `EnterSceneResponse.owner_epoch_after_rollback = 5`）在 `e8e6ed1c2` 里已经有了，之后没改。增量只有：告警 `SceneManagerEnterSceneRollbackRedisError` 的表达式并入 `outcome=plan_error`（`1d831acb5`，
  `deploy/k8s/scene-manager-alerts.yaml`）；`owner_epoch.go` / `owner_epoch_test.go` / `player_lifecycle.{h,cpp}` 只改注释；`travel.md` §13.7 按合并后的 HEAD 复核、runbook v2.6 重写 B3a–B3d 与「GO-2 专属验收点」7 条。
  Java 5.2 / 5.4 用一笔「交出并释放」的 MySQL 事务（X1，`HandOffMode.RELEASE`，E → E+1 且 `owner_released = 1`），没有回滚、epoch 只进不退，不需要跟（scene-handoff-spec 已登记「回滚回执采纳导致双持在 Java 不存在」）。
  告警表达式的变化只影响 ops-release-spec §4.2 基线表第 16 条（7.6）。
- **CPP-3**（`05f91f4b2` / `d49739faa` / `dacec28b1`，未编译未测试）：改的是疏散 / 排空改派，226 / 124 链路只多了「实体已不在」分支里的认领钩子（`ClaimRelocateEnterSceneReply` / `ClaimRelocateTransportFailure`），
  交出、取证、判定表、`ConcludeHandoffAfterMarkSent` 一行没动；`travel_freeze_cap.h`、`handoff_mark_withdraw.h` 不变。归 5.5，见 scene-drain-spec 文首「基线复核」。
- **主链路零行为改动**：`RequestZoneTravel` / `StartTravelHandoff` / `HandleTravelMarkWriteRejected` / `ResolveTravelOutcome` / `JudgeTravelOutcomeReply` / `ConcludeHandoffAfterMarkSent` 内容不变，
  行号整体后移约 1140–1155（2745 → 3885、2818 → 3958、3454 → 4609、3610 → 4765、3688 → 4843、4218 → 5373）；EnterScene 3.2 步只改注释（`1d831acb5`）；gate（`gate_event_handler`、`client_message_processor`）、
  login（`entergamelogic.go`、`homezone.go`）、scene_manager 的 `gate_redirect.go` / `home_zone.go` / `changesceneutil.go`、`robot/travel_smoke_scenario.go`、`robot/pkg/redirect.go` 都不变；`enterscenelogic.go` 第 430 行之后整体 +22
  （插入了玩家主动切线的预检 2b 段，只对 `client_channel_pick` 置位的请求生效，226 / 124 不置位）；`travel.md` +421 / −150 行，行号按节名重取。
- **3028**（`d970a050f`）：普通换图的传输失败改推 3028，跨 zone 226 的失败码 3027 不变，gate 通用失败桥仍回 1003——5.4 不受影响（Java 的 5.2 D12 / 5.3 D9 随 5.6 改）。
- **跨实现验收**：Go robot `travel_smoke` 没改，§11.10 的口径不变；但基线自身的 travel-smoke 至今没有绿色记录，mmorpg 本机 10-08 还进不了游戏，§11.10 暂时没有对端。runbook v2.6 / v2.7 的 B3a–B3d「scene_manager / Redis / 源 scene / 客户端四行对照」写法可以给 §11.9 的 MZ 用例参考。
