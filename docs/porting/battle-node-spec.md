# battle 节点（批次 6.2）移植统一规格：房间生命周期、客户端直连、票据、推送、准入

> **基线**：mmorpg `26ceb70ca`（`D:\work\mmorpg`），与 `contract/SOURCE.properties:4` 的 `mmorpg.commit` 相同。
> Java 侧以 HEAD `5ef79a6` 为准。6.1 的 `xm-battle-engine` 已在工作区（未提交），本稿引用它的行号以工作区为准；
> 5.2 在 xm-scene / xm-gate / xm-scene-manager / xm-player-store 有未提交改动，本稿不依赖其中任何文件。
>
> **本稿的来历**：由三份分区稿合并：① 房间生命周期；② 直连面、票据、推送、准入；③ Java 落地映射。
> 分区稿之间不一致、或与代码不符的地方，都回到代码重新核对过。裁决记在 §10.5，对 inventory 的勘误记在 §10.6。
> 本稿只读代码，没有改任何源文件。
>
> **路径缩写**（mmorpg 侧，相对 `D:\work\mmorpg`）
>
> | 缩写 | 路径 |
> |---|---|
> | `room.cpp` / `room.h` | `cpp/nodes/battle/logic/battle_room_manager.{cpp,h}` |
> | `edge.cpp` / `edge.h` | `cpp/nodes/battle/client/battle_client_edge.{cpp,h}` |
> | `sec.h` / `adm.h` / `pol.h` / `tbl.h` | `cpp/nodes/battle/` 下的 `battle_security.h` / `battle_admission_gate.h` / `battle_push_policy.h` / `battle_room_table.h` |
> | `node.cpp` | `cpp/nodes/battle/handler/grpc/battle_node.cpp` |
> | `main.cpp` | `cpp/nodes/battle/main.cpp` |
> | `tok.h` | `cpp/libs/engine/core/security/token_security.h` |
> | `codec.cpp` | `cpp/libs/engine/core/network/codec/codec.cpp` |
> | `limiter.{h,cpp}` / `illegal.h` | `cpp/libs/engine/core/message_limiter/` 下的 `message_limiter.{h,cpp}` / `illegal_packet_counter.h` |
> | `consts.h` / `engine.cpp` | `cpp/libs/services/battle/constants/turn_battle_constants.h` / `cpp/libs/services/battle/system/turn_battle_engine.cpp` |
> | `activity.h` | `cpp/libs/services/battle/system/battle_result_activity.h` |
> | `pb.{h,cpp}` | `cpp/libs/services/scene/battle/system/player_battle.{h,cpp}`（scene 侧，6.3） |
> | `agones.h` | `cpp/libs/engine/infra/agones/agones_gameserver_lifecycle.h` |
> | `gate_cmp.cpp` | `cpp/nodes/gate/handler/rpc/client_message_processor.cpp` |
> | `gather.go` / `queue.go` / `rbt.go` / `spectate.go` | `go/match/internal/logic/` 下的 `gather.go` / `queue.go` / `requestbattleticketlogic.go` / `spectate.go` |
> | `config.go` / `nw.go` | `go/match/internal/config/config.go` / `go/match/internal/discovery/node_watcher.go` |
> | `bdc.go` / `bss.go` | `robot/battle_direct_conn.go` / `robot/battle_smoke_scenario.go` |
> | `tbs.md` | `docs/design/turn-based-battle-server.md` |
> | `deploy.yaml` | `bin/etc/base_deploy_config.yaml`（本机稀疏克隆没有检出，按 `git show 26ceb70ca:<路径>` 读） |
> | `pb_player` / `pb_node` / `pb_data` | `proto/battle/{player_battle,battle_node,battle_data}.proto` |
> | `battle_event.proto` / `match_event.proto` | `proto/common/event/battle_event.proto` / `proto/contracts/kafka/match_event.proto` |
>
> **proto 行号**一律用 mmorpg 的。Java 同步副本在 `xm-proto/src/main/proto/proto/...`，只多第 2–4 行 java option，
> 所以 **Java 行号 = mmorpg 行号 + 3**（`docs/porting/battle-engine-spec.md:17-18`）。
>
> **Java 侧**路径相对 `D:\work\xuanming-server-mmo`。缩写：`engine-spec` = `docs/porting/battle-engine-spec.md`；
> `arch` = `docs/design/architecture.md`；`handoff-spec` = `docs/porting/scene-handoff-spec.md`；`combat.md` = `docs/porting/inventory/combat.md`。

---

## 0 概览与范围（与 6.1 / 6.3 / 6.4 / 6.5 的边界）

### 0.1 结论速览

- **新进程模块 `xm-battle`**：Spring Boot + Netty 直连面 + Dubbo Triple 提供方 + 只挂 actuator 与 dev 接口的管理 Tomcat。
  - 包 `com.game.battle.*`。
  - 引擎用 6.1 的 `xm-battle-engine`（`com.game.battle.engine`，API 见 engine-spec §10.3）。
- **线程**：一条 `battle-logic` 线程，同时是直连面唯一的 Netty I/O EventLoop。
  - 它独占全部房间状态、直连会话状态和房间计时器，与基线的单 muduo loop 同构（§7.3）。
  - Dubbo 线程只做第一道准入检查，然后投递任务；future 在专用回复执行器上完成。
- **直连面**：独立端口，缺省 12000，复用 `xm-net` 的帧编解码。
  - 闸门顺序、tip、握手失败字符串、10 s、1024 B、限频、50、2 MiB 全部照基线（§3）。
- **票据**：`xm-common` 新增 `BattleTickets`，HMAC-SHA256 小写 hex，与 `GateTokens` 同形。
  - 密钥任何运行模式都必填，去掉基线 dev 空密钥跳过验签（§11 N5）。
- **控制面**：基线 gRPC `BattleNode` → Dubbo `BattleNodeService`。
  - group `battle-node`，`register = false`，按节点目录直连，端口缺省 21200。
  - 「不可分配」从 gRPC 状态串 `battle_not_allocatable` 改成类型化的 `CreateBattleResult.admission`（§7.8）。
- **大厅公告 177 / 143**：基线走 Kafka gate-cmd，按开局快照里的路由寻址。
  - Java 改用 `PlayerPushes`：按在线目录找当前会话，经 gate 玩家栅栏下发。
  - 两条放进同一条 `GatePush` 保序（§7.7）。
- **battle → scene（确认、结算）与 battle → match（对局结果）**：6.2 只定义出站端口，缺省实现只记日志、计数。
  - 传输由 6.3 / 6.4 接入，推荐方案见 §12 Q12 / Q13。
- **准入**：保留 `NOT_STARTED → OPEN → CLOSED`，以及「同一逻辑任务里先关闸、再作废全部房间」。Agones 不移植。
- **存储**：不用 MySQL。
  - Redis 只写节点号租约（`battle`，作用域 0）和节点目录。
  - 只读在线目录（大厅公告），dev 接口另读位置记录。
- **验收**：6.4 之前用 dev / test 专用的管理口建房（§7.12）；robot 新增 `battle`、`battle-edge` 两个场景（§13.8）。

### 0.2 覆盖的盘点条目

| inventory id | `combat.md` | 本稿 | 留给 |
|---|---|---|---|
| battle-room-lifecycle | `:209-219` | 全部（§4） | — |
| battle-client-actions-push | `:221-231` | 全部（§5） | — |
| battle-direct-connect-edge | `:233-243` | 全部（§3、§7.4） | — |
| battle-ticket-assignment | `:245-255` | 全部（§2、§7.5） | 179 的 match 侧逻辑归 6.4，本稿只写契约（§2.7） |
| battle-node-admission-ops | `:281-291` | 全部（§6、§7.10–§7.11） | — |
| battle-spectate（邻居） | `:257-267` | 客户端可见行为写在 §5.5（165 白名单、161 首帧、158、166、单槽互斥） | 房间侧实现归哪一批见 **Q1**（推荐 6.2）；match 侧 163 / 164 归 6.5 |
| battle-settlement-outbox（邻居） | `:269-279` | 只在收尾顺序里的固定位置调端口（§4.6） | 6.3 |
| scene-battle-freeze（邻居） | `:293-303` | 只负责经端口发确认事件及其补发节奏（§4.8） | 6.3 |
| gate-battle-uplink-reject | `:329-339` | 已对齐；6.2 补一条钉住测试（§7.2） | — |

### 0.3 与相邻批次的边界

| 批次 | 6.2 提供 / 依赖 | 对方负责 |
|---|---|---|
| **6.1 引擎** | 只调 `TurnBattleEngine` 的公开 API（`xm-battle-engine/src/main/java/com/game/battle/engine/TurnBattleEngine.java:104`、`:390`、`:409`、`:425`、`:444`、`:473`、`:862`、`:867`、`:881`、`:940`、`:966`）。`start` 返回 `Rejected` → 1002；`setActorAuto` 成功返回 **1000**（engine-spec D2）；指纹取 `TableBattleData.fingerprint()`（engine-spec D3） | — |
| **5.2 交接** | 不依赖。battle 不读位置记录（dev 接口除外，§7.12） | 战斗冻结与交出互斥（`handoff-spec:106`），由 6.3 接入 |
| **6.3 scene** | 端口 `SceneBattleEvents.confirm`（补发节奏在 6.2 实现并测试）；`SettlementSink` / `ActivityResultSink` 的调用点与顺序；CreateBattle 对路由字段的校验 | scene 出快照时填 `BattleRouting`；在 scene 上提供确认与结算的处理方（推荐见 Q13）；结算发件箱；144；结算后经大厅再推 150 |
| **6.4 match** | `BattleNodeService` 接口；`CreateBattleResult.admission`；目录字段 `accepting`；`BattleResultSink` 端口 | 选节点、「不可分配」时换节点重试一次、建房失败时 destroy 补偿、建房**之前**写落点记录；179 补签；gate 把 `MatchService` 路由到 xm-match；battle_id 用雪花号；`deadline_ms = gather 起点 + 300 s`；结果事件的传输与消费 |
| **6.5 观战** | 见 Q1：推荐 6.2 做完房间侧（观众名单、AddObserver / RemoveObserver、161 / 158 / 166、165、单槽互斥） | match 侧 163 / 164、观战索引、开局前清退观众（`gather.go:228-234`）、跨区 1V1 |

### 0.4 兼容面

- **客户端可见，两版逐字节相同**：
  - §3 的帧、握手、闸门与拒绝串；
  - §2.1–§2.4 的票据与分配包；
  - §5 的消息号、应答形状、推送类别、线上顺序、视角裁剪；
  - tip 码；§8.4 的全部时限。
- **服务端内部，按 Java 方式重做**：控制面与出站的传输、线程、节点身份、Agones、指标。
- **不在客户端契约里、但 Java 仍须保住的语义**（因为其它进程依赖它）：
  - CreateBattle 的判定顺序与「拒绝即无副作用」（match 依赖）；
  - 确认补发窗口（scene 锁依赖）；
  - 对局结果事件的字段与「只在真打完时发」（match / guild 依赖）。

---

## 1 基线进程与线程模型

### 1.1 进程构成

| 部件 | 作用 | 出处 |
|---|---|---|
| gRPC 服务 `BattleNode` | match 调用的五个 RPC：CreateBattle / DestroyBattle / AddObserver / RemoveObserver / IssueBattleTicket | `pb_node:84-90`；`main.cpp:262-266` |
| 客户端直连面 `BattleClientEdge` | 装在节点自己的 TCP 端口上。battle 按 gRPC-only 节点注册，这个端口不再承载节点间 RPC，整个让给客户端 | `main.cpp:283-290` |
| 房间管理器 `BattleRoomManager` | 单例，纯内存。节点崩溃 = 全部战斗作废，补偿全靠 scene 的 reaper | `room.h:24-32` |
| 出站 | 除直连外全走 Kafka producer：大厅公告 → gate、确认 / 结算 → scene、对局结果 → match（§1.4） | `room.h:34-49`；`main.cpp:236-248` |
| Agones 生命周期 | 房间即单元；分配许可、排空标签、loop 心跳 | `main.cpp:41-88`、`:273-281`、`:312-319` |
| 出站拨号 | 无。对端路由全部来自快照 | `main.cpp:257-259` |

### 1.2 线程清单

| 线程 | 跑什么 | 出处 |
|---|---|---|
| **muduo 主 loop**（进程唯一的 EventLoop） | `BattleRoomManager` 的全部方法；三类房间计时器与发件箱计时器（`TimerTaskComp`）；直连面 TCP I/O；hiredis 异步回调；Kafka `produce`；Agones 心跳计时器 | `room.h:26-28`（「所有方法只允许在 muduo loop 线程执行……内部完全无锁」）；`tbl.h:15-16`；`edge.h:15`；`main.cpp:318-319` |
| gRPC 池线程 | `BattleNodeImpl` 的五个 RPC，依次：① 查准入闸；② 取 Agones 分配许可（**在 gRPC 线程上有界阻塞**，上限 `allocateWaitTimeout` 3000 ms）；③ `runInLoop` 投递；④ `future.get()` 阻塞到 loop 执行完 | `node.cpp:56-125`；`agones.h:95` |
| Agones lifecycle worker | 与 sidecar 的 HTTP 交互；房间增删回调只拿一把短锁 | `main.cpp:66-88` |

- **TcpServer 没有 I/O 线程池**：muduo 缺省 0 个 I/O 线程，连接回调全部在主 loop 上（同 gate，`gate_cmp.cpp:542-544` 的注释）。
- **`BuildAssignment` 只能在 loop 线程上调**：它读的 `gNode->GetNodeInfo()` 是 thread_local，换到 gRPC 线程会拿到空值，整批静默拒签（`room.cpp:1449-1451`；`node.cpp:64`）。

### 1.3 由此得出的不变量（Java 一律保留语义）

1. **无锁串行**：同一房间上的请求、计时器到期、外部回调天然串行。
2. **计时器回调按 battle_id 重查房间**，不捕获裸指针（`room.cpp:1033-1036`、`:597-599`、`:630-632`）。
3. **`ResolveRound` 可能当场删房**：调用方必须在调用**之前**填完应答，之后不得再碰房间（`room.cpp:730-733`）。
4. **关直连推迟到本轮 loop 之后**（`queueInLoop`），保证「终局包 → 本次请求的应答 → FIN」（`room.cpp:1595-1616`）。
5. **整个节点只有一根线程**：一次慢结算会卡住全部房间和全部直连 I/O。Agones 每 1 s 心跳一次，超过 10 s 没更新就停发 /health（`main.cpp:47-49`；`agones.h:102`）。

### 1.4 基线出站一览

| 出站 | 信封 / topic | key | 目标过滤 | 出处 |
|---|---|---|---|---|
| 大厅公告 → gate（只有 177 / 143） | `GateCommand{event 39 PushToPlayerEvent{session_id, MessageContent{message_id, serialized_message}}}`，topic `gate-cmd_g<N>`，按 gate_node_id 选分区 | player_id | `target_gate_id` + `target_instance_id = routing.gate_instance_id`；实例为空就拒发并打 ERROR | `room.cpp:66-118` |
| 确认 → scene | `SceneCommand{DispatchEvent, event 45 BattleConfirmedEvent}`，topic `scene-cmd_g<N>` | player_id | `target_scene_id = routing.scene_node_id` + `target_instance_id = routing.scene_instance_id`；实例为空或节点为 0 就拒发 | `room.cpp:150-197`、`:270-281` |
| 结算 → scene（6.3） | 同上信封，event 42 `BattleSettlementEvent`；重投时实例留空、按位置重新解析节点 | player_id | 同上 | `room.cpp:138-149`、`:248-255` |
| 对局结果 → match | topic `match-results`（全局，无 zone 段），payload 就是 `BattleResultEvent`，不套信封 | battle_id | 无 | `room.cpp:283-304` |
| 活动结果（6.3） | 先 `SET battle:activity_result:{id}`，再发上一行 | battle_id | — | `room.h:46-49`；`activity.h:28-36` |

事件号见 `xm-proto/src/main/resources/contract/event_id.txt:40`（39）、`:43`（42）、`:46`（45）。

---

## 2 票据与落点分配

### 2.1 `BattleTicketPayload`（`pb_player:135-142`；由 `room.cpp:1467-1474` 填写）

| 字段 | 取值 |
|---|---|
| `battle_id` | 房间号 |
| `player_id` | 目标玩家 |
| `battle_node_id` | 签发节点的 node_id |
| `battle_instance_id` | 签发节点的实例 UUID（防节点重启后 node_id 复用） |
| `expire_at_ms` | 房间作废期限 `room.deadlineMs` |
| `role` | `PARTICIPANT = 1` / `OBSERVER = 2`（`pb_player:125-129`） |

### 2.2 签名

- 签名 = `HMAC-SHA256(secret, payload 序列化字节)` 的**小写 hex**，64 字节 ASCII（`sec.h:89-92`；`tok.h:34-62`，`std::hex` 输出小写）。
  - 与 gate 令牌同一口径（`pb_player:131-134`、`:145-148`；Java `arch` §3）。
  - 注意 proto 注释说「hex 文本」，**不是** 32 字节原值。
- 验签：
  - 用**收到的原始 payload 字节**重算后，与签名做常数时间比较；
  - 期望值为空（OpenSSL 失败）一律不通过（`sec.h:95-100`）；长度不同或为空也一律不通过（`tok.h:67-74`）。
- 签发方和验签方都是 battle 节点自己，所以 payload 的字节不需要与 C++ 一致。但**验签不能「解析后重新序列化」**，必须用原字节。
- 基线 dev / test 下密钥为空时签名为空串，验签跳过（`room.cpp:1476-1487`；`edge.cpp:302-313`）。Java 去掉这条分支（§11 N5）。

### 2.3 `BattleAssignedS2C`（`pb_player:161-171`；由 `room.cpp:1489-1496` 填写）

| 字段 | 取值 |
|---|---|
| `battle_id` | 房间号 |
| `host` / `port` | 客户端可达地址：`client_endpoint` 可用就用它；否则在 `required = false` 时回落到 `endpoint`；都拿不到就拒签（`room.cpp:1452-1465`） |
| `token_payload` | §2.1 的序列化字节（**签名用的就是这份字节**） |
| `token_signature` | §2.2 |
| `expire_at_ms` | `room.deadlineMs` |
| `role` | 同票据 |

### 2.4 期限与票据寿命

- `room.deadlineMs` = 请求里的 `deadline_ms`；但当 `deadline_ms ≤ now` 时（不只是「没填」），取 `now + (30 + 2) × 6000 = now + 192000`（`room.cpp:559-567`）。
- 生产里 match 填 `gather 起点 + BattleMaxDurationSeconds × 1000`，缺省 300 s（`gather.go:219`；`config.go:55-58`；`go/match/etc/match_service.yaml:57`）。
  - 这个值在逐人 PrepareBattle **之前**就算好了，所以房间实际剩余时长 = 300 s − gather 已耗时间。
- **票据寿命 = 房间寿命**：同一张票在期限内可以反复重连；房间销毁后，挂接一步（§3.4 第 5 步）必然失败（`pb_player:131-134`）。
- **补签出的票与原票逐字节相同**：所有字段相同，HMAC 是确定性的。这是推导，不是 C++ 断言。

### 2.5 签发时机与 fail-closed

| 路径 | 时机 | 签不出时 | 出处 |
|---|---|---|---|
| CreateBattle | 插表、装计时器、推送、发确认事件**之前**，按 player_id 升序给全体参战者预签 | 整局拒绝，回 1003，**零副作用** | `room.cpp:569-585`；`pb_node:35-36` |
| AddObserver（新观众） | 登记之前 | 回 1003，不登记 | `room.cpp:873-883` |
| AddObserver（幂等重推） | 重推之前先重签 | 回 1003，并摘除这名观众、关其直连 | `room.cpp:823-838` |
| IssueBattleTicket | 核对名单之后 | 回 1003，清空 assignment | `room.cpp:2073-2078` |

基线有三种「签不出」：
1. prod 下密钥为空（`room.cpp:1436-1444`）；
2. OpenSSL 失败（`:1477-1485`）；
3. 没有客户端可达地址（`:1452-1465`）。

### 2.6 IssueBattleTicket（battle 侧，`room.cpp:2033-2081`；这些 tip 都不带参数）

1. `player_id == 0` → 1005（不是 1012：0 只可能是 match 漏填）。
2. 房间不存在 → 1005。
3. 角色：在参战名单里 → PARTICIPANT；否则在观众名单里 → OBSERVER；都不在 → 1005。两个名单都有时参战者优先。
4. 签不出 → 1003，清空 assignment。
5. 成功：`error_message` 不填，`assignment` 是新签的票。

### 2.7 补签 179 的客户端可见结果（match 侧，6.4 实现；`rbt.go:70-141`）

`tipErr(id, msg)` 把说明写进 `parameters[0]`（`go/match/internal/logic/joinqueuelogic.go:18-21`）。

| 条件 | tip | `parameters[0]` | assignment |
|---|---|---|---|
| 没有会话身份 | 16004 kMatchInternal（`go/match/internal/constants/errors.go:38-39`） | `缺少玩家身份` | 空 |
| 读观战索引出错 | 16004 | `服务器繁忙,请稍后再试` | 空 |
| 索引不存在（房间已结束或作废） | 1005 | `该战斗不存在或已结束` | 空 |
| battle 节点没注册或身份歧义 | 1003 | `战斗服务暂不可用` | 空 |
| 调 battle 的 RPC 失败（超时 3 s，`rbt.go:22`） | 1003 | `战斗服务暂不可用` | 空 |
| 调通了 | battle 的裁决原样透传（§2.6） | — | — |

- **客户端判据**：Unity **只把**补签回 1005 判为 BattleGone，1003 等其它失败都退避重试；每局最多补签 3 次（`tbs.md:952-962`）。
  - 所以 1005 只能用来表示「这局确实没了」，Java 不得在别的失败上回 1005。
- **已知缺口**：节点「不在」不判 BattleGone，房间节点崩溃后要等索引 TTL 过期才回 1005。
  - 代码里已经写明了正面证据的做法：比较实例 UUID，但还没做（`rbt.go:98-104`）。Java 6.4 是否采用见 Q14。

### 2.8 落点（match 侧，6.4；这里只列与 battle 的约定）

- **选节点**：全局池，不分 zone，v1 随机（`gather.go:212-217`）。
- **建房之前先写观战索引** `spectate:battle:{id}`，里面有 `battle_node_id`；写不进去就不建房（`gather.go:308-321`）。
  - 原因：battle 在回包之前就已向 scene 发出确认事件；建房之后才发现写不进去，解冻基本不生效。
- **「不可分配」**（`UNAVAILABLE "battle_not_allocatable"`，`node.cpp:11-20`）：
  - 不发 DestroyBattle；
  - 先把索引改指向新节点，再换一个没试过的节点（按 endpoint 排除，`gather.go:334`）重试**一次**（`gather.go:323-350`）。
- **其它失败**（tip ≠ 0 或 RPC 失败）：
  - 先发 `DestroyBattle(reason = "gather_rollback")`。它在节点上要么是幂等命中，要么真把一间房删掉；之后逐人 CancelBattlePrepare。
  - DestroyBattle 也失败时，不解冻、不回队，交给房间期限与 scene 的 reaper（`gather.go:351-377`）。
- **超时**：CreateBattle 5 s；DestroyBattle / CancelBattlePrepare / PrepareBattle 3 s（`gather.go:25-30`）。

---

## 3 直连面（握手、帧、安全闸）

本节全部是客户端可见契约。

### 3.1 两条连接

- **大厅连接（gate）**：承载登录、scene 全部消息、match 消息（含补签 179）。
  - 接收大厅公告 177 / 143 的回落，以及 scene 推的 144 / 150。
  - **不承载任何战斗上行**（`tbs.md:797`）。
- **battle 直连**：参战或观战期间，每局一条。承载握手应答、四条战斗 RPC 及其应答、握手后的全部战斗帧。
  - 直连是战斗帧的**唯一通路**：没有活直连就丢弃，不回落 gate（`tbs.md:798`；`pb_player:9-21`）。
- **单槽**：每个 player_id 在房间里只有一个直连槽，参战与观战共用（`room.h:214-220`）。

### 3.2 帧与信封

- **帧格式与 gate 完全相同**（`edge.h:11-13`；Java `arch` §3）：
  - `[int32 len][int32 nameLen][typeName][body][int32 adler32]`；
  - 帧长上限 64 KiB（`xm-net` `ClientFrames`）；
  - 下行类型名写全名加 `\0`，上行只剥掉类型名的最后 1 字节。
  - 这批 proto 没有 package，所以全名就是 `BattleTokenVerifyRequest` / `ClientRequest` / `MessageContent` / `BattleTokenVerifyResponse`。
- **上行只认两种类型**：`BattleTokenVerifyRequest`、`ClientRequest`（`edge.cpp:58-65`）。
  - 类型名不认识、长度或校验和不对、body 解析失败：编解码层清空缓冲、立即强关，不回包（`codec.cpp:118-143`、`:164-197`）。
  - 类型名合法但不是这两种（例如把大厅的 `ClientTokenVerifyRequest` 发错了连接）：不回包，`shutdown()` 后 0.1 s 强关（`edge.cpp:516-528`）。
  - 连接一旦不是 connected 状态（含 `shutdown()` 之后），不再分发任何帧（`codec.cpp:158-162`、`:180-185`）。
- **下行只有两种类型**：`BattleTokenVerifyResponse`、`MessageContent`。
- **`MessageContent` 的三种形状**（客户端靠形状区分）：

| 形状 | 字段 | 出处 |
|---|---|---|
| 应答 | `id = 请求 id`，`message_id = 请求号`，`serialized_message = 应答体字节`（可以是 0 字节），没有 `error_message` | `edge.cpp:440-442`、`:468`、`:513` |
| 信封错误 | `id` 与 `message_id` 回显，`error_message{id = tip}`，没有 `serialized_message` | `edge.cpp:382-390` |
| 推送 | `message_id = N`，`serialized_message`；`id` 不填（= 0） | 直连：`room.cpp:1381-1388`；经 gate：`room.cpp:107-118` |

四条上行 RPC 的应答类型都不是 `Empty`，所以**每条都回包**，成功也回（应答体 0 字节）。

### 3.3 握手之前的闸（都不回包）

| 闸 | 条件 | 处置 | 出处 |
|---|---|---|---|
| G1 并发上限 | 会话数（含未验证）≥ `battle_max_connections`；配 0 时取硬上限 65535 | 立即强关，不分配会话 | `edge.cpp:157-166`；`edge.h:55` |
| G2 空密钥 + prod | 纵深防御（启动门禁已挡过一次） | 立即强关 | `edge.cpp:168-176` |
| G3 握手期限 | 10 s 内没完成握手 | 强关 | `edge.h:51`；`edge.cpp:187-200` |
| G4 握手前发 `ClientRequest` | — | 立即强关，**不计非法包** | `edge.cpp:402-408` |
| G4′ 别的合法类型 | — | `shutdown` 后 0.1 s 强关 | `edge.cpp:516-528` |

- dev 配置的上限是 4096（`deploy.yaml:136`）。
- 代码里没有缺省值：缺键就是 0，prod 拒绝启动（`tbs.md:588`）。

### 3.4 握手判定（`edge.cpp:255-362`）

任一步失败：回 `BattleTokenVerifyResponse{success = false, error = 下表字符串}`，`battle_id` 不填；随后 `shutdown()`，0.1 s 后强关（`edge.cpp:267-274`）。

| 步 | 条件 | `error`（逐字照抄） | 采样日志 reason |
|---|---|---|---|
| 0 | 本连接**已经验证过** | 不是拒绝：回 `success = true, battle_id = 已绑定的 battle_id`。**不看新票、不重新绑定**，这一步排在所有校验之前（`edge.cpp:276-281`） | — |
| 1 | prod 下密钥为空 | `battle token secret not configured` | token_secret_not_configured |
| 2 | 配了密钥且签名不符 | `invalid ticket signature` | ticket_hmac_mismatch |
| 3 | payload 解析失败 | `malformed ticket payload` | ticket_payload_parse_failed |
| 4 | 字段判定，顺序固定为「最便宜的先拒」（`sec.h:153-184`；`cpp/nodes/battle/tests/battle_ticket_test.cpp:162-170` 断言了这个顺序）：<br>① `battle_id` 或 `player_id` 为 0；<br>② `battle_node_id ≠ 本节点`；<br>③ 实例 id 任一侧为空或不等；<br>④ `expire_at_ms ≤ now`（等于也算过期）；<br>⑤ role 不是 1 / 2（按整数值判断，未知枚举值同样拒绝） | `ticket rejected: empty_identity` / `node_mismatch` / `instance_mismatch` / `expired` / `role_invalid` | 同名 |
| 5 | 挂接：房间存在，且该玩家**按票上的角色**在对应名单上（参战票只认参战名单，观众票只认观众名单，`room.cpp:1520-1541`） | `battle not found or player not in this battle` | ticket_not_in_roster |

**成功**：
1. 置为已验证；非法包计数清零；取消握手定时器（`edge.cpp:343-349`）。
2. 回 `success = true, battle_id`，`error` 不填（`edge.cpp:351`、`:242-253`）。
3. **然后**，只对 OBSERVER 推 161 首帧（`edge.cpp:358-361`；`room.cpp:1576-1593`）。
   - 线上顺序固定为「握手应答 → 161」：客户端握手读到的第一个包必须是应答（`edge.cpp:358-359`；robot `robot/pkg/client.go:240-265`）。
   - 参战者握手后服务端什么也不推，由客户端自己发 140 补拉（`room.cpp:1579-1583`；`bdc.go:178-189`）。

**重连顶替**：
- 同一玩家已有活直连时，新连接挂接成功会**强关旧连接，不给旧连接发任何帧**（`room.cpp:1543-1553`）。
- 旧连接迟到的断开回调只摘「仍指向自己」的槽，不会误摘新连接（`room.cpp:1561-1574`；`edge.cpp:227-231`）。

### 3.5 已验证连接上的上行闸（顺序就是语义，`edge.cpp:410-511`）

1. **体积**：`ClientRequest` 整条序列化后 > 1024 B → 信封错误 **1010** kMessageSizeExceeded，非法包 +1（`edge.h:53`；`edge.cpp:410-416`）。
2. **限频**：按消息号，每条连接一份滑动窗口（白名单外的号也计）→ 信封错误 **1008** kRateLimitExceeded，非法包 +1（`edge.cpp:418-425`；`limiter.cpp:11-49`）。
   - 上限查 MessageLimiter 表，表里没有的号取缺省「1 s 窗口 3 条」（`limiter.h:15-16`）。
   - 表里**没有** 140 / 149 / 162 / 165（`git show 26ceb70ca:generated/tables/messagelimiter.json`），所以四条战斗 RPC 都是每秒 3 条。
   - 被拒的请求不占额度。
3. **白名单**：只放行 149 / 140 / 165 / 162；其它号（含 Notify 号与登录、场景、匹配的号）→ 信封错误 **1005**，非法包 +1（`edge.cpp:455-510`）。
4. **请求体解析失败** → 信封错误 **1005**，非法包 +1（`edge.cpp:444-453`）。

- **非法包阈值**：缺省 50，基线环境变量 `GATE_ILLEGAL_PACKET_THRESHOLD`，0 = 只计数不断开（`illegal.h:38-62`）。
  - 达到阈值即强关（`edge.cpp:366-380`）。这时刚写的信封错误可能来不及发出，不保证送达。
- **身份只来自票据**：处理函数拿到的 `player_id` 是票上的；请求体里没有 player_id，也不信任（`edge.cpp:434-438`）。房间按**请求体里的 battle_id** 查。

### 3.6 关闭语义与时序

| 场景 | 动作 | 出处 |
|---|---|---|
| 握手被拒 | 先回应答，`shutdown()`，0.1 s 后强关 | `edge.cpp:267-274` |
| 终局、退出观战、观众被清退 | 槽位**立刻**摘除；`shutdown()` 推迟到本轮 loop 之后执行（等输出排空后发 FIN），1.0 s 后强关兜底 | `room.cpp:1595-1653` |
| 重连顶替 | 旧连接立即强关，不发帧 | `room.cpp:1551` |
| 输出缓冲 > 2 MiB | 强关。客户端凭票重连后用 GetBattleState 补拉 | `edge.cpp:20-30` |
| 非法包到阈值 | 强关 | `edge.cpp:376` |

**为什么推迟**：`shutdown()` 会同步把连接切到 kDisconnecting，之后的 `send` 一律丢弃；而应答要等 Handle* 返回后才由直连面写出（`room.cpp:1597-1601`）。

### 3.7 gate 对战斗上行

- 大厅连接上发 `BattleClientPlayer` 服务的任何号（含 Notify 号），都回 23 `{1003}`，不计非法包、不断连（`gate_cmp.cpp:937-949`）。
- Java 已对齐（`combat.md:329-339`）：
  - `BattleClientPlayer` 不在 `SERVICE_BACKENDS` 里（`xm-gate/src/main/java/com/game/gate/session/MessageRoutes.java:35-41`）；
  - 走 `ClientDispatcher` 的「未接入的域」分支，回 23 `{1003}`（`xm-gate/src/main/java/com/game/gate/session/ClientDispatcher.java:320-325`）。

### 3.8 参考客户端依赖的判据

- **robot**（`bdc.go`）：
  - 建连加握手共 10 s 预算（`bdc.go:51`）；
  - `VerifyBattleToken` 只读一个包，类型必须是 `BattleTokenVerifyResponse`（`robot/pkg/client.go:240-265`）；
  - 握手成功后断言 `battle_id ==` 分配包里的值（`bdc.go:162-165`）；
  - 参战者握手后立刻发 140（`bdc.go:178-189`）；
  - battle-smoke 断言经直连到达的 state 应答、139、150 各 ≥ 1；观众要求 161 来自直连，158 / 166 各 ≥ 1（`bss.go:225`、`:257`、`:378`）；
  - 超时预算：开局 30 s、观战 15 s、终局 120 s（`bss.go:55-60`）。
- **Unity**（`tbs.md:946-973`）：
  - 关闭原因分 `Ended / HostClosed / BattleGone / Unreachable / Superseded`；
  - 建连 10 s、调用 15 s；同一张票最多重连 1 次；
  - 每局最多补签 3 次，只有补签回 1005 才判 BattleGone；
  - 149 的 1005 显示「行动无效」；只有 162 的 1005 显示「战斗不存在或已结束」；
  - 两条连接收到的 150 按 battle_id 幂等。

---

## 4 房间生命周期

### 4.1 房间数据（`room.h:184-221`）

| 字段 | 语义 |
|---|---|
| `battleId` | 房间键 |
| `matchMode`、`battleConfigId` | 开局参数副本，只回显进结果事件（`:187-189`） |
| `activityContext` | `CreateBattleRequest.activity_context` 原样副本；结束时回显，并据 kind 选结果通道（`:190-192`） |
| `engine` | 一房一个引擎 |
| `routingByPlayer` | player_id → `BattleRouting`（快照副本）。**有序 map**：广播与结算按 player_id 升序遍历（`:194-196`） |
| `routingByObserver`、`observerNames` | 观众名单；观众路由的 scene 字段恒为 0（`:197-202`） |
| `roundTimer` / `battleTimer` / `confirmResendTimer` | 回合截止 / 整场期限 / 确认补发（`:203-213`） |
| `actionDeadlineMs` | 当前回合截止（Unix ms），节点回填进各类 state |
| `deadlineMs` | 整场作废期限：与 `battleTimer` 同值，也是票据 `expire_at_ms` 和确认事件里的 `deadline_ms` |
| `confirmResendUntilMs` | 补发窗口终点 |
| `directConnByPlayer` | player_id → 已验证直连（weak_ptr），参战与观战共用，有序（`:214-220`） |

`BattleRouting`（`pb_data:12-19`）的字段：`session_id`、`gate_node_id`、`gate_instance_id`、`scene_node_id`、`scene_instance_id`、`zone_id`。

### 4.2 房间表（`tbl.h`）

- 底层容器藏起来，只能经 `Emplace` / `Erase` 增删，各**恰好**触发一次 `onCreated` / `onRemoved`。
  - 回调在表已经更新之后同步调用；回调里不许重入增删（`tbl.h:5-9`、`:56-92`）。
- 批量删除先拷一份 `Ids()` 再逐个 `Erase`（`tbl.h:94-104`；`room.cpp:1335-1337`）。
- 重复插表返回 nullptr，不触发回调（`room.cpp:467-478`）。
- 基线 hooks 驱动 Agones 单元计数（`main.cpp:273-281`）。

### 4.3 CreateBattle

#### 4.3.1 输入（`pb_node:19-33`）

| 字段 | 节点怎么用 |
|---|---|
| `battle_id` | 房间键，不能为 0；match 用雪花号发，是唯一生产者（`pb_node:15-16`） |
| `battle_config_id` | 交给引擎（Dungeon 行：怪物组、回合上限）；回显进结果事件 |
| `players[]` | 引擎快照；`routing` 抄进 `routingByPlayer` |
| `seed` / `match_mode` | 引擎 RNG / 引擎判 PVE；`match_mode` 回显 |
| `created_at_ms` | **节点和引擎都不读**（在 `cpp/nodes/battle`、`cpp/libs/services/battle` 里没有引用） |
| `deadline_ms` | 整场期限（§2.4） |
| `table_fingerprint` | 指纹闸 |
| `activity_context` | 原样保存，结束时回显 |

#### 4.3.2 节点级准入（详见 §6.2，这里只列位置）

准入闸 → Agones 许可 → 投递进 loop 后复核准入闸。任一不过 → `UNAVAILABLE "battle_not_allocatable"`，发生在任何副作用之前。

#### 4.3.3 业务判定顺序（`room.cpp:486-595`；前一步拒绝时后面都不执行）

`response.battle_id` 在第一行就填好，每条路径都带（`:490`）。

| # | 条件 | 结果 | 副作用 | 出处 |
|---|---|---|---|---|
| 0 | 房间已存在（幂等命中，**不比较请求内容**） | OK，不填 error_message | **无**：不重推 177 / 143，不重发确认，不重装计时器 | `:492-497` |
| 1 | `battle_id == 0` 或没有玩家 | 1005 | 无 | `:499-505` |
| 2 | 任一快照的 `routing.gate_instance_id` 或 `scene_instance_id` 为空 | 1005 | 无 | `:507-521` |
| 3 | 指纹不一致，且模式为 enforce | 1006 kFeatureUnavailable，`parameters[0] = "battle table fingerprint mismatch: node=<本节点指纹> request=<request.table_fingerprint>"` | 无 | `:523-534` |
| 4 | 引擎初始化失败 | 1002 kInvalidTableData | 无（房间是局部对象，随返回析构） | `:536-548` |
| 5 | — | 抄 `routingByPlayer` | 纯计算 | `:550-553` |
| 6 | `deadline_ms ≤ now` | `deadlineMs = now + 192000`，必须在签票之前落到房间上 | 纯计算 | `:555-567` |
| 7 | 按 player_id 升序为每个参战者预签；任一人签不出 | 1003 kServiceUnavailable | **无**（「没有建房、没有推送、没有确认事件」） | `:569-585` |
| 8 | 插表 | `onCreated` 恰好一次。返回 nullptr（不可达的程序缺陷）时按幂等命中处理：回 OK、不填错误 | — | `:587-595` |

**指纹闸细节**（`room.cpp:306-451`）：
- **模式来源**：取 `etc/game_config.yaml` 的 `battle_table_fingerprint_mode`，环境变量 `BATTLE_TABLE_FINGERPRINT_MODE` 可覆盖。缺键、取值非法时回落 warn。首次 CreateBattle 时读一次，之后缓存（`:350-404`）。
- **比较范围**：只比非空值，即 request 本身的一个，加上每个快照里的一个（`:417-430`）。
- **off**：不比。
- **warn**：打 `metric=battle_table_fingerprint_mismatch`，照常开局。
- **enforce**：打 `metric=battle_table_fingerprint_reject`，拒绝开局（`:436-450`）。
- `parameters[0]` 里 `request=` 后面永远是 `request.table_fingerprint()`：不一致来自快照时，它可能是空串（`:529-532`）。

#### 4.3.4 插表之后的副作用（顺序固定，`room.cpp:597-632`）

1. `battleTimer.RunAfter(deadlineMs − now)` → `OnBattleDeadline`。
2. `ArmRoundTimer`：装第一回合窗口（§4.4.1）。
3. 组一份公共的 `BattleStartS2C`：引擎全知快照 + `battle_id` + `action_deadline_ms = actionDeadlineMs`。
4. **按 player_id 升序**逐人执行：
   1. 推 **177** `NotifyBattleAssigned`（role = PARTICIPANT，`expire_at_ms = deadlineMs`）。
   2. 推本人那一份 **143** `NotifyBattleStart`：拷贝公共包，按本人视角裁剪，只填本人的 `self_items`。
   3. 向该玩家所在 scene 发 `BattleConfirmedEvent(battle_id, player_id, deadlineMs)`。
5. `confirmResendUntilMs = now + 180000`；`confirmResendTimer.RunEvery(10 s)`。
6. 返回 OK（error_message 不填）。

177 / 143 的通道：房间刚建，不可能已有直连，所以两条都回落到 Kafka → gate → 大厅连接。两条的 Kafka key 都是 player_id，同一分区，所以**客户端一定先收到 177、再收到 143**（`room.cpp:611-622`；`pb_player:157-160`）。

#### 4.3.5 客户端看到什么

- **177** `BattleAssignedS2C`，见 §2.3。
- **143** `BattleStartS2C{battle_id, state}`（`pb_player:37-40`），其中 `state`：
  - `round_index = 1`，`outcome = ONGOING`；
  - `actors`：全部单位。他人与他人宝宝的 `skill_cooldown_rounds` 被清掉（开局时本来就是空的）；
  - `pending_actor_ids` = 全部存活、未逃、非挂机的玩家（engine-spec §7.1）；
  - `action_deadline_ms = 建房时刻 + 6000`。开局时没有人挂机（引擎不从快照带 `is_auto`），所以**第一回合恒为 6 s**；
  - `self_items` 只有本人的。
- **线上帧**：`MessageContent{id = 0, message_id = 177 / 143, serialized_message}`。

### 4.4 回合计时

#### 4.4.1 装填 `ArmRoundTimer`（`room.cpp:1022-1037`）

```
windowMs = engine.AllPlayersReady() ? 2000 (kAutoRoundIntervalMs) : 6000 (kRoundDurationMs)
room.actionDeadlineMs = nowUtcMs + windowMs
roundTimer.RunAfter(windowMs) → ResolveRound(battleId)
```

- 窗口长度在**装填那一刻**就定死；之后切换挂机，不会改变本回合的窗口。
- `AllPlayersReady` 的判定：跳过非玩家、已死或已逃、挂机的单位；剩下的每个人都有 pending 才为真。
  - 没有剩下的人时也为真（空真）。例如 A 方玩家全灭、只剩宝宝（engine-spec §2.4）。
  - 所以「全自动节奏」的确切含义是：**装填时已经全员就绪** → 2 s。
- 常量：`consts.h:15`（6000）、`consts.h:35`（2000）；Java `BattleConstants.java:18`、`:34`。

#### 4.4.2 结算触发（三种，都进同一个 `ResolveRound`）

| 触发 | 条件 | 出处 |
|---|---|---|
| 窗口到期 | `roundTimer` 回调 | `room.cpp:1034-1036` |
| 149 Submit | `ValidateAction` 通过后，`SubmitAction` 返回 true（全员就绪）就**同步**结算。注意：全自动房间里，挂机玩家提交一条合法行动也会立即结算（§10.2 B2） | `room.cpp:709-734` |
| 162 SetAutoBattle | 只在「置位前未全员就绪 → 置位后全员就绪」翻转、且 `enabled = true` 时结算。对已经全员就绪的房间重复置位不结算，防止按包速率击穿 2 s 节奏 | `room.cpp:999-1019` |

#### 4.4.3 `ResolveRound(battleId)`（`room.cpp:1039-1080`）

1. 按 id 重查房间，查不到就返回。
2. `roundTimer.Cancel()`：提前结算时旧窗口还挂着，必须先取消，避免同一回合结算两次。
3. `result = engine.ResolveCurrentRound()`；`outcome = engine.Outcome()`。
4. 回填截止：
   - 未分胜负：**先** `ArmRoundTimer`，再 `result.state.action_deadline_ms = 新的截止`；
   - 已分胜负：`action_deadline_ms = 0`。
5. 回填 `result.battle_id`、`result.state.battle_id`；`action_order` 先清空，再从 `engine.LastActionOrder()` 抄进来。
6. `BroadcastTurnResult`（`:1223-1248`）：
   - 每个参战者一份拷贝：按视角裁剪 + 本人 `self_items`，发 **139**，只走直连；
   - 有观众时另做**一份**观众版（清掉全员冷却、不带道具），逐个观众发 **158**。
7. 已分胜负：`FinishBattle(outcome, SPECTATE_END_BATTLE_FINISHED)`，然后 `EraseRoom`。

**139 `TurnResultS2C`**（`pb_player:42-48`）：
- `round_index` 是本次结算的回合号；
- `state.round_index`：未结束时是下一回合号，已结束时与本次相同（engine-spec §3）；
- `state.action_deadline_ms`：新截止，结束时为 0；
- `events`、`action_order`。

#### 4.4.4 节奏速查

| 场景 | 每回合间隔 |
|---|---|
| 有手动玩家、他没提交 | 6.0 s |
| 全部手动玩家都提交了 | 最后一份提交到达时立即结算 |
| 开局后有人开挂机，导致翻转成全员就绪 | 立即结算；之后每回合 2.0 s |
| 全自动时有人关挂机 | 本回合仍 2 s（已装填）；下一回合 6 s |
| 被眩晕 / 冰冻的手动玩家 | 提交一律被 7006 拒绝，只能等满 6 s（engine-spec §2.5） |
| 玩家全部掉线 | 每 6 s 按默认普攻推进，直到分出胜负或到整场期限 |

### 4.5 整场期限（强制平局，`room.cpp:1082-1105`）

1. 按 id 重查房间。
2. `outcome = engine.Outcome()`；仍是 ONGOING 就改成 **DRAW**。这是节点盖章，引擎没有「强制终局」接口。
3. 打 WARN；`roundTimer.Cancel()`。
4. `FinishBattle(outcome, SPECTATE_END_BATTLE_ABORTED)`，然后 `EraseRoom`。

**客户端可见的差异**：
- **没有最后一帧 139**，本回合已排队的行动作废。
- 参战者收 **150**：`outcome = DRAW`，`settlement.outcome` 也被覆盖成 DRAW（`room.cpp:1146-1149`）。
  - 引擎侧胜负仍是 ONGOING，所以不满足发奖条件：经验、金币为 0，`defeated_monsters` 与 `items_gained` 为空。
  - `items_consumed`、`health`、`mana` 照常带出；`total_rounds` = 已完成的回合数（engine-spec §6.6）。
- 观众收 **166**：`reason = ABORTED(2)`，**`outcome = DRAW`**。`pb_player:81` 注释说「作废时为 ONGOING」，只对 Destroy / Abort 成立。
- **照常发结算与结果事件**（DRAW）。

**与回合上限的关系**：
- 引擎回合上限 = `RoundsFromMilliseconds(Dungeon.time_limit × 1000)`，缺省 30（engine-spec §1）。
- 现行 Dungeon 表的 `time_limit` 只有 1800 / 3600 两种，对应 300 / 600 回合。
- 300 s 期限内最多打 50 回合（手动）或约 150 回合（全自动），远够不到上限。所以对现有 PVE 副本，「打满回合判 B 胜」不可达，整场期限就是实际的上限。
- 没有 Dungeon 行时（例如 PVP 填 0，缺省 30 回合），回合上限可能先于期限触发。

### 4.6 正常收尾 `FinishBattle(room, outcome, spectateReason)`（`room.cpp:1130-1221`）

只组装与发送，不动房间表；调用方随后 `EraseRoom`。顺序固定：

1. 取消三个计时器（`:1133-1135`）。
2. **按 player_id 升序**逐个参战者（`:1144-1175`）：
   1. `settlement = engine.BuildSettlement(pid)`；把 `outcome` 覆盖成节点给的值，补上 `battle_id`；
   2. 按 `settlement.player_team_index` 归组；记下 `total_rounds`；`fled` / `is_dead` 分别进各自名单；
   3. 推 **150** `BattleEndS2C{battle_id, outcome, settlement = 本人那份}`，只走直连；
   4. 持久投递结算 `DispatchSettlementDurably(routing, pid, settlement)`（6.3）。
3. 观众：逐个推 166 `{battle_id, outcome, reason}` 并关闭其直连，然后清空观众表（`:1297-1325`）。
4. 关全部参战者直连（推迟到本轮 loop 之后，`:1177-1179`）。
5. 组装结果事件（§4.9）：带活动上下文的走持久通道（6.3），否则只发一次（`:1181-1211`）。
6. 打 INFO。

没有直连的参战者收不到这份 150。6.3 的 scene 应用结算之后，会经大厅再推一份 150；客户端两条连接都挂了 NotifyBattleEnd，按 battle_id 幂等（`room.cpp:1162-1165`；`pb_player:19-20`）。

### 4.7 作废

**DestroyBattle**（match 补偿路径，`room.cpp:644-670`）：
- 房间不存在 → 幂等成功，只打 INFO。
- 否则：
  1. 取消三个计时器；
  2. 观众收 166 `{ABORTED(2), ONGOING}` 后关其直连；
  3. 关全部直连：参战者**收不到 150，也没有终局 139**；
  4. `EraseRoom`。
- **不发结算、不发结果事件、不通知 scene**。应答是 `Empty`，gRPC status 恒为 OK（`node.cpp:127-141`）。
- **已知取舍**（`room.cpp:656-659`）：scene 收到过确认事件（已 FIGHTING）后，会拒绝 CancelBattlePrepare（`pb.cpp:1297-1306`）。这时玩家要冻结到 `deadline_ms`，再由 reaper（每 30 s 扫一次，`pb.h:102`）解除。

**AbortAllRooms(reason)**（停机，`room.cpp:1327-1353`）：
- 先拷房间 id，再逐间处理：取消计时器 → 观众收 166 `{ABORTED, ONGOING}` → 关直连 → `EraseRoom`（每间各触发一次 `onRemoved`）。
- 不结算、不发结果事件，玩家同样冻结到期限。
- 调用方必须在**同一个 loop 任务里**先关准入闸（`room.h:164-168`；`main.cpp:342-345`）。

**节点崩溃**（`room.h:30-32`）：
- 参战者和观众都收不到 150 / 166，只看到 TCP 断开；
- 补签拿到的是 1003（节点不在），或 1005（索引已过期）；
- scene 的 reaper 按 `deadline_ms` 解冻。

### 4.8 确认事件 `BattleConfirmedEvent` 与补发

- **消息**（`battle_event.proto:21-25`）：`{battle_id, player_id, deadline_ms = 房间期限}`。
  - scene 据此做 PREPARING → FIGHTING，把作废期限切到正式期限，并给锁续期；其余情况幂等（`pb.cpp:1312-1430`）。
- **投递**：发往快照里的 `(scene_node_id, scene_instance_id)`。实例为空或节点为 0 就拒发并打 ERROR；produce 失败只打日志，由下一次补发覆盖（`room.cpp:150-197`、`:270-281`）。
- **补发**（`room.cpp:1107-1128`）：
  - 每 10 s 触发一次；若 `now ≥ confirmResendUntilMs` 就停表，否则对全部参战者按 `room.deadlineMs` 重发。
  - **次数**：建房时首发 1 次；在 +10、+20 … +170 s 各补发 1 次，共 17 次；+180 s 那次触发时停表、不发。所以每个玩家最多 18 条。
  - 战斗先结束时，随 `FinishBattle` 一起停表。
- **窗口取 180 s 的依据**（`room.cpp:257-268`）：
  - scene 备战期锁的 EX = prepare 剩余时间 + `kLockExtraTtlSec`（60 s，`pb.h:111`）；
  - match 的 matched TTL 公式（`queue.go:367-380`）对 1 / 2 / 5 / 10 人组分别是 42 / 48 / 66 / 96 s（`room.cpp:260-261`）；
  - 所以锁最晚在 gather 起点 + 96 + 60 = 156 s 过期，取 180 s 再留 24 s 余量。
  - **依赖**：match 改公式、调大 `MatchedTicketTTLSeconds`、放大组上限，或者 scene 改 `kLockExtraTtlSec` 时，窗口必须仍 ≥ 最大 TTL + 60 s（§10.4）。
- **没有回执**：基线没有 scene → battle 的确认回执通道，靠有界的周期补发覆盖首发丢失、Kafka 积压、消费者 rebalance（`room.h:207-211`）。

### 4.9 对局结果 `BattleResultEvent`（`room.cpp:1181-1211`；`match_event.proto:20-35`）

| 字段 | 取值 |
|---|---|
| `battle_id` / `match_mode` / `battle_config_id` | 房间副本 |
| `outcome` | 节点的最终值（整场期限路径为 DRAW） |
| `winner_team_index` | 只有 `SIDE_B_WIN` 时为 1，其余（含 DRAW）为 0；平局时 match 忽略这个字段 |
| `teams[]` | 按 `team_index` 无符号升序；队内 player_id 升序 |
| `total_rounds` | 最后一名参战者结算里的值（大家都相同） |
| `finished_at_ms` | 组装时刻的 UTC 毫秒 |
| `activity_context` | 只在 `kind ≠ NONE` 时回显；**不认识的 kind 值也按活动局处理**（`activity.h:43-48`、`:62-70`） |
| `fled_player_ids` / `dead_player_ids` | 所有对局都填；无符号升序、去重（`activity.h:50-55`、`:71-80`） |

- 只在真正打完的局发（正常收尾、整场期限）；Destroy / Abort 不发（`match_event.proto:9-10`；`room.cpp:1181`）。
- 带活动上下文的局可能投递多次，消费方必须按 battle_id 幂等（6.4 / 4.6）。

### 4.10 收尾路径对照（客户端与下游可见）

| 路径 | 终局 139 | 150（参战者） | 结算 → scene | 166 给观众（reason, outcome） | 结果事件 | 直连 | 冻结何时解除 |
|---|---|---|---|---|---|---|---|
| 分出胜负 | 有（`action_deadline_ms = 0`） | 有（真实胜负） | 有 | FINISHED(1)，真实胜负 | 有 | 终局包 →（应答）→ FIN | 结算应用后（6.3） |
| 整场期限 | **无** | 有（DRAW，无奖励） | 有（DRAW） | ABORTED(2)，**DRAW** | 有（DRAW） | 同上 | 结算应用后 |
| DestroyBattle | 无 | **无** | 无 | ABORTED，ONGOING | 无 | FIN | 已 FIGHTING：等期限后的 reaper；仍 PREPARING：match 发 Cancel |
| 停机作废 | 无 | 无 | 无 | ABORTED，ONGOING | 无 | FIN（Java 先排空，§11 N16） | 期限后的 reaper |
| 崩溃 | 无 | 无 | 无 | 无（TCP 断开） | 无 | RST / 断开 | 期限后的 reaper |

---

## 5 客户端协议（提交 / 拉状态 / 自动 / 视角裁剪 / 推送）

### 5.1 消息号（`xm-proto/src/main/resources/contract/message_id.txt`，第 N+1 行是 N 号；`:140-180`）

| 号 | 契约名 | 方向 | 连接 | 体 |
|---|---|---|---|---|
| 140 | BattleClientPlayerGetBattleState | C2S → 应答 | 直连 | `GetBattleStateRequest{battle_id}` → `BattleStateS2C`（`pb_player:95-97`、`:24-35`） |
| 149 | BattleClientPlayerSubmitBattleAction | C2S → 应答 | 直连 | `SubmitBattleActionRequest{battle_id, action}` → `SubmitBattleActionResponse{error_message}`（`:86-93`） |
| 162 | BattleClientPlayerSetAutoBattle | C2S → 应答 | 直连 | `SetAutoBattleRequest{battle_id, enabled}` → `SetAutoBattleResponse{error_message}`（`:109-116`） |
| 165 | BattleClientPlayerStopWatchBattle | C2S → 应答 | 直连 | `StopWatchBattleRequest{battle_id}` → `StopWatchBattleResponse{error_message}`（`:100-106`） |
| 139 | NotifyTurnResult | S2C 推 | 直连（战斗帧） | `TurnResultS2C`（`:42-48`） |
| 150 | NotifyBattleEnd | S2C 推 | 直连（战斗帧）；scene 结算后另经大厅推一份（6.3） | `BattleEndS2C`（`:50-54`） |
| 143 | NotifyBattleStart | S2C 推 | 大厅公告 | `BattleStartS2C`（`:37-40`） |
| 177 | NotifyBattleAssigned | S2C 推 | 大厅公告 | `BattleAssignedS2C`（`:161-171`） |
| 158 / 161 / 166 | NotifySpectateTurnResult / NotifySpectateState / NotifySpectateEnd | S2C 推 | 直连（战斗帧） | `TurnResultS2C` / `SpectateStateS2C`（`:66-69`）/ `SpectateEndS2C`（`:79-83`） |
| 144 | NotifyBattleReconnect | S2C 推 | 大厅（scene 发，6.3） | `BattleReconnectS2C` |
| 179 | MatchServiceRequestBattleTicket | C2S → 应答 | 大厅 → match（6.4） | `RequestBattleTicketRequest` → `RequestBattleTicketResponse`（`:176-183`） |
| 146 / 147 / 159 / 160 / 178 | BattleNode* | 内部 | — | Java 走 Dubbo，不用这些号 |

握手的两种类型没有消息号，靠帧里的类型名区分（`pb_player:145-155`）。

### 5.2 149 SubmitBattleAction（`room.cpp:672-735`）

1. `player_id == 0` → 1012 kPlayerNotFoundInSession。票据保证非 0，实际走不到。
2. 房间不存在 → 1005（常见于战斗刚结束、行动包晚到）。
3. 不是参战者（观众也算不是）→ 1005（防串房）。
4. 引擎 `validateAction` 不是成功 → 原样回这个 tip；返回 0 时兜底改成 1005 并打 ERROR（`:709-724`）。可能的 tip 见 engine-spec §2.2、§2.5（1005 / 1009 / 7006 / 技能与道具链）。
5. `submitAction`，返回全员就绪就**当场结算一回合**（`:727-734`）。

- 成功时 `error_message` 不填，应答体 0 字节。
- 校验失败的提交回错误，但同一回合里此前已收下的合法行动仍然有效（engine-spec §2.1）。

### 5.3 140 GetBattleState（`room.cpp:737-772`）

- 房间不存在、player_id 为 0、或者请求者既不是参战者也不是观众 → 回 `BattleStateS2C` 默认值（0 字节，`battle_id = 0`）。客户端据此丢掉本地战斗界面。**不回错误码**。
- 否则：引擎全知快照 + `battle_id` + `action_deadline_ms` = 当前回合截止，再按视角裁剪：
  - 参战者：只保留本人和本人宝宝的冷却，并填 `self_items`；
  - 观众：清掉全部冷却，不填 `self_items`。

### 5.4 162 SetAutoBattle（`room.cpp:969-1020`）

1. 1012（同上）→ 房间不存在 1005 → 不是参战者 1005（观众不能切自动）。
2. 采样 `wasAllReady = engine.AllPlayersReady()`。
3. `setActorAuto` 失败 → 回它的 tip（1005，或已死 / 已逃时 1009）。
   - 基线成功码是 0（`:1002-1010`）；Java 引擎成功返回 **1000**，节点判 `!= 1000`。
4. **只有** `enabled && !wasAllReady && allPlayersReady()` 时当场结算（`:1012-1019`）：
   - 已经全员就绪的房间重复开启，不会加速；
   - 关闭自动永远不触发结算，当前窗口不变。
5. 成功时 `error_message` 不填。

### 5.5 观战部分（客户端可见行为；房间侧实现归哪一批见 Q1）

**165 StopWatchBattle**（`room.cpp:931-967`）：
- 1012 → 房间不存在：回成功 → 不是观众：回成功 → 否则移出观众名单，并在应答写出之后关闭直连。
- **不推 166**。

**AddObserver**（match → battle，`room.cpp:774-895`），按顺序判定：
1. 房间不存在 → **1004** kEntityIsNull（match 据此懒剔除观战索引，不能换 tip）。
2. observer 为 0，或 `routing.gate_instance_id` 为空 → 1005。
3. 是参战者 → 1005（单槽互斥）。
4. 已登记过（幂等路径）：先重签，签不出就摘除 + 关直连 + 1003。
   - 同一会话：重推 177，有活直连时再推 161；
   - 会话变了：更新路由，关旧直连，重推 177，不推 161。
5. 已有 20 名观众 → **1008** kRateLimitExceeded。这一步排在幂等判定**之后**（`:865-871`）。
6. 新观众：先签票（签不出回 1003，不登记）→ 登记 → 推 177（role = OBSERVER）。

**RemoveObserver**（`room.cpp:897-929`）：
- 房间或观众不在 → 幂等，什么都不做；
- 否则推 166 `{REMOVED(3), ONGOING}`，摘除，关直连。

**观战帧**：
- **161**：观众握手成功后紧跟应答推出，内容为 `SpectateStateS2C{state（全员冷却清空、无 self_items、action_deadline_ms = 房间值）, observer_count = 当前观众数}`（`room.cpp:1283-1295`）。
- **158**：每回合一份观众版。
- **166**：收尾、作废时推出（§4.10）。

### 5.6 视角裁剪（`room.cpp:1250-1281`）

- **`RedactStateForViewer(state, viewer)`**：
  - 先清 `self_items`；
  - 再处理每个单位：「自己的」= `viewer ≠ 0` 且（`actor_id == viewer` 或 `owner_player_id == viewer`），不是自己的单位清 `skill_cooldown_rounds`；
  - **buff 不裁剪**。
- **`FillSelfItems`**：只给 `viewer ≠ 0` 的参战者填，用引擎 `SelfItems`（按 id 升序）。
- **任何路径都必须裁剪**：开局 143、每回合 139、140、观战 161 / 158。漏掉任何一处，都会泄露对手的冷却和道具（`combat.md:231`）。

### 5.7 推送类别与出口（`pol.h:19-49`；`room.cpp:1391-1430`）

| 类别 | 消息 | 有活直连 | 没有活直连 |
|---|---|---|---|
| 大厅公告 | 177、143 | 走直连 | 经 gate 回落到大厅会话 |
| 战斗帧 | 139、150、158、161、166 | 走直连 | **丢弃**，按消息号采样打 `metric=battle_frame_dropped_no_direct`（每个号第一次必打，之后每 1024 次一条，`room.cpp:124-136`） |

- 回落是白名单：新加的类别缺省丢弃（`pol.h:42-49`）。
- 「活直连」= 槽里有这条连接，且它仍是 connected（`room.cpp:1357-1376`）。
- 大厅公告的判定理论上不会得出「丢弃」；万一得出就打 ERROR（`room.cpp:1424-1428`）。

### 5.8 线上顺序（实现与评审逐条核对）

| # | 场景 | 顺序 | 出处 |
|---|---|---|---|
| O1 | 开局 | 按 player_id 升序；每人先 177 后 143（都经大厅） | `room.cpp:611-622` |
| O2 | 握手 | 握手应答 →（只对观众）161 | `edge.cpp:351-361` |
| O3 | 上行触发结算（149 使全员就绪，或 162 翻转） | 同一条直连上：<br>① 各参战者 139，各观众 158；<br>② 若打完：各参战者 150、观众 166；<br>③ **本次请求的应答**；<br>④ FIN（排空后），1 s 后强关兜底 | `room.cpp:1073`、`:1166-1179`、`:1602-1616`；`edge.cpp:466-468`、`:513` |
| O4 | 定时器触发结算 | 同 O3，只是没有应答那一步 | 同上 |
| O5 | 整场期限 | 没有 139；参战者 150（DRAW）；观众 166 ABORTED + DRAW；FIN | `room.cpp:1082-1105` |
| O6 | Destroy / 停机作废 | 参战者**什么帧都收不到**，只看到 FIN；观众 166 ABORTED + ONGOING | `room.cpp:660-667`、`:1344-1351` |
| O7 | 重连顶替 | 旧连接被强关，不发任何帧 | `room.cpp:1551` |
| O8 | 165 | 应答 → FIN | `room.cpp:961-963` |

---

## 6 准入与部署生命周期

### 6.1 准入闸（`adm.h:31-70`）

- 三态：`NotStarted → Open → Closed`，Closed 是终态。
- `Open()` 只在 NotStarted 时成功；`Close()` 在任何状态下都进入 Closed，幂等。
- 有 7 条单测（`cpp/nodes/battle/tests/battle_admission_gate_test.cpp:33-88`）。

### 6.2 CreateBattle 的五道关（`node.cpp:56-125`）

1. gRPC 线程查准入闸，不是 Open → 拒绝（`:66-74`）。
2. 取 Agones 分配许可，拿不到 → 拒绝（`:76-91`）。
3. `runInLoop` 后在 loop 内复核准入闸，不是 Open → 拒绝（`:102-123`）。
   - 关闸与作废全部房间在同一个 loop 任务里，排在它之后的建房必然看到 Closed。
4. 预签票据（§4.3.3 第 7 步），失败 → gRPC OK + tip 1003。
5. 插表。

前三道任一不过 → gRPC `UNAVAILABLE` + `battle_not_allocatable`。这是跨语言的字符串契约，match 精确匹配（`node.cpp:11-20`）。语义：**保证无副作用，match 不发 destroy、换节点重试一次**（§2.8）。

### 6.3 启动门禁 `ValidateBattleClientEdgeConfigOrDie`（`main.cpp:103-178`）

| 情形 | prod | dev / test |
|---|---|---|
| 密钥为空 | 拒绝启动 | 打醒目 WARN，跳过验签 |
| 密钥去首尾空白后 < 32 字节 | 拒绝启动 | WARN |
| 密钥与 gate 密钥（同样去空白）相同；gate 密钥为空时不比 | 拒绝启动 | WARN |
| `battle_max_connections = 0` | 拒绝启动 | 允许（硬上限 65535） |
| 运行模式取值不认识 | 按 prod 处理并 WARN | — |

- 运行模式读 `BATTLE_RUN_MODE`，与 gate 分开（`sec.h:40-42`）。
- 别名同 `tok.h:138-173`；纯空白的密钥视同未配（`tok.h:217-224`）。
- 强度判定见 `sec.h:56-84`。

### 6.4 表加载（`main.cpp:180-221`）

- 打出七张表的行数：Skill、Buff、Cooldown、SkillPermission、Dungeon、Monster、Item。
- Skill / Buff / Dungeon / Monster 任一为空打 ERROR；Item 为空只打 WARN；**都不拒绝启动**。
- 算出配表指纹并打日志。

### 6.5 启动顺序（`main.cpp:260-327`）

1. 注册 gRPC 服务。
2. 过门禁，构造直连面，挂上房间表 hooks。
3. `SetAfterStart`：装直连面 → 打出地址日志（没有客户端可达地址时打 ERROR）→ 启动 Agones → 起 loop 心跳 → **最后开准入闸**。

etcd 发布早于开闸，所以开闸之前的 CreateBattle 都由闸拒绝（`main.cpp:321-327`；`adm.h:5-16`）。

### 6.6 停机顺序（`main.cpp:329-350`，同一个 loop 任务里）

关准入闸 → `AbortAllRooms("node_shutdown")` → `DisconnectAll` → 停 Agones。之后框架关 gRPC，flush Kafka。

**已知缺陷**（Java 修，§11 N16）：
- `AbortAllRooms` 把直连的 `shutdown` 推迟到本轮 loop 之后，而同一任务里紧接着的 `DisconnectAll` 看到这些连接仍是 connected，于是直接 `forceClose`（`edge.cpp:91-110`；`room.cpp:1609-1615`）。
- `edge.cpp:101-103` 的注释以为它们已经 shutdown，实际上没有。
- 结果：观众 166 只要还留在用户态输出缓冲里没写进内核，就会丢失。
- 设计文档也记了这条风险（`tbs.md:1051-1052`）。

### 6.7 Agones（不移植）

`agones.h`、`main.cpp:41-88` 的分配许可、排空标签、loop 心跳、单元计数，都是部署相关的，Java 不移植（§11 N4）。

---

## 7 Java 落地映射

### 7.1 模块、包、依赖、端口

- **模块**：`xm-battle`，进程。Spring Boot 非 Web 应用 + 管理 Tomcat（同 `arch` §11 的「非 Web 进程的管理端口」）。
- **依赖**：`xm-battle-engine`、`xm-net`、`xm-common`、`xm-api`、`xm-discovery`、`xm-proto`、`xm-table`。
  - 无新第三方依赖（Netty、Dubbo、Redisson、Micrometer、Spring Boot 都是现成的，`AGENTS.md` §2）。
- **根 pom**：排在 `xm-battle-engine`、`xm-discovery` 之后。
- **端口**：已占用的是 11000 gate、21000 / 21100 scene、20881–20887、18101–18111、18081、18105。

| 用途 | 配置 | 缺省 | 依据 |
|---|---|---|---|
| 直连面 | `xm.battle.client-port`（`XM_BATTLE_CLIENT_PORT`） | **12000** | gate 是 11000 |
| Dubbo Triple（按节点直连） | `xm.battle.rpc-port`（`XM_BATTLE_RPC_PORT`） | **21200** | 与按节点直连、`register=false` 的 scene 资产通道 21100 同系列（`arch` §4.12） |
| 管理端口 | `server.port`（`SERVER_PORT`） | **18112** | 18101–18111 已用 |

- **新常量**：
  - `NodeTypes.BATTLE = "battle"`：租约与目录，**作用域 0**。基线 battle 是全局池，不分 zone（`deploy.yaml:46`；`gather.go:212`）。
  - `DubboGroups.BATTLE_NODE = "battle-node"`。

**包结构**（领域对象 + `XxxService`，不用 ECS）：

| 包 / 类 | 职责 | 基线来源 |
|---|---|---|
| `com.game.battle.BattleApplication` / `BattleConfiguration` / `BattleProperties`（`xm.battle.*`） | 装配；启动门禁 | `main.cpp:103-178` |
| `com.game.battle.BattleNode`（`SmartLifecycle`） | 启停顺序、目录发布、租约丢失处置 | `main.cpp:288-350` |
| `admission.AdmissionGate` | 三态，`AtomicReference` + CAS | `adm.h` |
| `room.BattleRoom` | 一间房（§7.6） | `room.h:184-221` |
| `room.RoomTable` / `RoomHooks` | 唯一增删口，回调各恰好一次 | `tbl.h` |
| `room.BattleRoomService` | 全部 Handle*、计时器回调、收尾、作废、挂接与摘除；**只在逻辑线程上调用** | `room.cpp:486-1653`、`:2033-2081` |
| `room.BattleViews` / `ViewerState` | 视角裁剪；用类型强制「必经裁剪」 | `room.cpp:1250-1281` |
| `room.FingerprintGuard` | off / warn / enforce | `room.cpp:306-451` |
| `room.BattleResultAssembler` | 结果事件组装（纯函数） | `room.cpp:1137-1201`；`activity.h:62-81` |
| `room.BattleScheduler` / `BattleClock` | 定时与时钟接口；测试用手动调度器 + 假时钟 | `TimerTaskComp` / `TimeSystem` |
| `ticket.BattleTicketIssuer` | `BuildAssignment` 的 Java 版 | `room.cpp:1432-1498` |
| `push.PushPolicy` / `BattleOutbound` / `LobbyAnnouncer` / `PresenceLobbyAnnouncer` | 出口判定、直写、大厅回落 | `pol.h`；`room.cpp:1391-1430` |
| `edge.BattleEdgeServer` / `BattleEdgeHandler` / `DirectSession` / `DirectClose` / `BattleMessageIds` / `EdgeRejectReason` | 直连面 | `edge.cpp` / `edge.h` |
| `rpc.BattleNodeServiceImpl` / `BattleRpcServer` | Dubbo 提供方与编程式导出 | `node.cpp`；先例 `xm-scene/src/main/java/com/game/scene/asset/SceneAssetRpcServer.java` |
| `port.SceneBattleEvents` / `SettlementSink` / `ActivityResultSink` / `BattleResultSink` 及 `Logging*` 缺省实现 | 出站端口（§7.9） | `room.cpp:138-304`、`:1657-` |
| `directory.BattleDirectoryPublisher` | 每 5 s 写一次 `BattleNodeInfo` | — |
| `admin.DevBattleController` / `BattleAdminAuthFilter` | dev / test 专用建房等接口（§7.12） | 先例 xm-trade 播种接口（`arch` §4.20） |
| `metrics.BattleMetrics` | §9 | — |

### 7.2 对已有模块的改动

| 模块 | 改动 | 理由 |
|---|---|---|
| `xm-common` `com.game.common.token` | 新增 `BattleTickets`（签名、常数时间验签、字段判定、`Verdict` 与日志名）、`BattleSecretPolicy`（强度判定） | `HmacSha256Hex` 是包私有的；与 `GateTokens` 并列最自然；纯函数，可逐条移植 `battle_ticket_test.cpp` |
| `xm-net` | 把 `MessageLimit` / `MessageLimits` / `MessageRateLimiter` / `TableMessageLimits` 从 xm-gate 挪到 `com.game.net.limit`（public）；xm-net 新增依赖 xm-table | 现在 `MessageRateLimiter` 是 xm-gate 包私有的（`xm-gate/src/main/java/com/game/gate/session/MessageRateLimiter.java:13`）；基线两个客户端面用同一张表（`edge.h:22-24`）；见 Q9 |
| `xm-api` | `BattleNodeService` 接口；新文件 `xm/api/battle_control.proto`（`CreateBattleResult`）；`node_directory.proto` 加 `BattleNodeInfo`；`DubboGroups.BATTLE_NODE` | §7.8、§7.10 |
| `xm-discovery` | `NodeTypes.BATTLE`；`presence.proto` 的 `GatePush` oneof 加 `MessageBatch`；`PlayerPushes.pushAllToPlayer` | §7.7 |
| `xm-gate` | `GatePushSubscriber` 支持 `MessageBatch`；路由**不改**，只加钉住测试与注释：`BattleClientPlayer` 永不进 `SERVICE_BACKENDS` | `combat.md:339` 的隐患 |
| `xm-robot` | 战斗直连客户端、dev 管理接口客户端、`battle` / `battle-edge` 场景 | §13.8 |
| `tools/local/start-slice.sh` / `stop-slice.sh` | 服务清单加 `xm-battle`（等 21200）；`XM_BATTLE_TOKEN_SECRET` 没设时生成 32 字节随机值写进 `run/xm-battle-token-secret`（umask 077），并校验 ≥ 32 字节且 ≠ `XM_GATE_TOKEN_SECRET` | 同 `start-slice.sh:51-65` 的资产密钥写法 |

### 7.3 线程所有权

**选定模型**：`battle-logic` = 直连面 `ServerBootstrap` 的 child group 里**唯一**的 EventLoop（单线程 `EventLoopGroup`，实现类与 xm-gate 一致），boss group 另起 1 个线程。

| 线程 | 做什么 | 不得做什么 |
|---|---|---|
| `battle-logic` | 直连 I/O 与编解码；直连会话状态（是否已验证、限频器、非法包计数、握手定时器）；`RoomTable` 与全部 `BattleRoom`；引擎；三类房间计时器（`loop.schedule`）；全部业务判定与帧构造 | 阻塞 I/O（Redis / MySQL / 同步 Dubbo / Kafka `send`）；`join()` / `get()` 任何 future |
| boss（1 条） | accept | — |
| Dubbo 提供方线程 | 读准入闸（原子量）→ 投递 `logic.execute(...)` → 立即返回 `CompletableFuture` | 碰房间 |
| `battle-rpc-reply`（小执行器） | 完成 Dubbo future（Triple 序列化与写出不占逻辑线程；同 scene 的 `scene-asset-reply`，`arch` §4.12） | 碰房间 |
| Redisson 回调线程 | `PlayerPushes` 的结局：只计数、打日志；要改状态时投递回逻辑线程 | 碰房间 |
| 管理 Tomcat（4 线程） | actuator；dev 接口做 Redis 查询后，经 `BattleNodeServiceImpl` 走同一条投递路径 | 碰房间 |

**理由**：
- 与基线的单 loop 完全同构（`room.h:26-28`；`edge.h:15`）。
- 帧顺序、「应答之后才关」、「处理中被关的连接不再分发」都天然成立，不需要跨线程排队协议；输入背压由 TCP 自然传导。
- 负载有上界：连接上限缺省 4096，每回合每人一条几 KB 的帧，每连接每秒最多 3 条 140。
- 满足 `AGENTS.md:36-37` 的线程纪律。
- 以后要扩容按节点水平扩（YAGNI，Q16）。

**否决的备选**：逻辑线程用独立的 `DefaultEventLoop`（同 scene），I/O 用多线程 worker 组。这样做：
- 限频与非法包计数都在逻辑线程上，洪水包会在限频之前就堆进逻辑线程的任务队列，需要另加每连接积压上限 + `autoRead` 背压；
- 握手的「挂接 + 写应答」必须在同一个逻辑任务里，信封错误也要绕到逻辑线程写出才能保序。
- 复杂度更高，收益只是编解码挪出逻辑线程。

**写出顺序规则**（实现与评审逐条核对）：

| # | 规则 | 基线 | Java 做法 |
|---|---|---|---|
| R1 | 握手应答是直连上的第一帧；观众的 161 紧随其后；参战者握手时不推任何东西 | `edge.cpp:343-361` | 挂接、写应答、推 161 在同一个 handler 调用里按序 `writeAndFlush` |
| R2 | 一次请求里引发的推送，先于这次请求的应答 | `edge.cpp:466-468`、`:513` | 处理器只返回应答对象，由 edge 在处理器返回**之后**写出；处理器内部的推送已经先写 |
| R3 | 收尾、清退：终局包 →（应答）→ FIN；1 s 后强关兜底 | `room.cpp:1595-1653` | `DirectClose.afterCurrentTask(ch)`，见 §7.4 |
| R4 | 握手被拒：应答 → FIN → 0.1 s 后强关 | `edge.cpp:267-274` | 同 R3，延迟 100 ms |
| R5 | 177 先于 143 | `room.cpp:611-622` | 走直连时同一任务里先后写；走大厅时用一条 `GatePush` 带两条（§7.7） |
| R6 | 同一回合：参战者按 player_id 升序收 139，然后观众收 158；收尾时逐个参战者「150 → 结算端口」，然后观众 166 + 关闭，最后关参战者直连 | `room.cpp:1229-1247`、`:1144-1179` | 名单用 `TreeMap<Long,…>(Long::compareUnsigned)`；跨连接的顺序客户端看不见，但测试按这个顺序断言，便于对照基线 |
| R7 | 重连：先关旧直连再挂新的；旧连接迟到的断开不得摘掉新连接 | `room.cpp:1543-1574` | `slot.put(pid, newCh)`，旧的 `close()`；断开事件 `detach(battleId, pid, ch)` 只在 `slot.get(pid) == ch`（引用相等）时摘除 |
| R8 | `resolveRound` 可能把房间删掉 | `room.cpp:730-733`、`:1075-1079` | `resolveRound` 返回 `RoundOutcome{ONGOING, FINISHED}`，不返回房间引用；`BattleRoom.closed` 在 erase 之前置位，迟到的访问能识别出来 |

### 7.4 直连面（`com.game.battle.edge`）

**pipeline**：

```
SocketChannel（TCP_NODELAY，WriteBufferWaterMark(1 MiB, 2 MiB)）
  → EdgeInboundGuard（关闭中丢弃入站字节；一次读处理完时让房间发起的优雅关闭生效）
  → ClientFrameDecoder(accepted = {BattleTokenVerifyRequest, ClientRequest}, maxLen = 64 KiB,      // xm-net，与 gate 同一个类
                       keepDecoding = 会话还收帧, onError = 计数 + 按原因采样日志)
  → ClientFrameEncoder.INSTANCE
  → BattleEdgeHandler（每连接一个，持有 DirectSession）
```

- 解码失败与白名单外的类型名一律立即关闭、不回包（`ClientFrameDecoder.fail`）。
- **逐帧分发**（照 `codec.cpp:164-191`）：每次 `decode` 至多解一帧，交给 handler 处理完（应答已写出）才解下一帧；解下一帧之前先问
  `keepDecoding`，handler 已同步关闭连接就丢弃剩余字节、不解析、不计非法帧（`codec.cpp:180-185`）。所以同一次读里先到的合法帧
  （握手、149 等）照常处理、回包，后面的坏帧 / 合法但不收的类型才导致断开；被拒的握手后面跟着坏帧，客户端照样先收到拒绝应答再 FIN。
  阶段 A 先在 xm-battle 里放了一份逐帧的 `EdgeFrameDecoder`；阶段 B 已把这个行为并回 xm-net 的 `ClientFrameDecoder`（gate 的
  `keepDecoding` = 会话没决定关闭），删掉了那份副本。
  - 基线对「合法但不收」的类型是 0.1 s 后关，这里有 ≤ 0.1 s 的时序差（§11 N9）。

**`DirectSession`**：
- 状态 `PENDING → VERIFIED → CLOSING`；
- 字段：battleId、playerId、role、gateSessionId（只进日志）、illegalPackets、`MessageRateLimiter`、握手定时器。

**事件处理**：

| 事件 | 处理 |
|---|---|
| `channelActive` | 连接数 ≥ 有效上限（配置值；0 时取 65535）→ 立即 `close()`，计 `at_capacity`；否则建会话，挂握手定时器（缺省 10 s） |
| 握手定时器到点 | 仍是 PENDING → `close()`，计 `handshake_timeout` |
| 收到 `BattleTokenVerifyRequest` | VERIFIED：回 `{success, 已绑定的 battle_id}`（§3.4 第 0 步）。PENDING：走 §3.4 第 2–5 步（Java 没有第 1 步，密钥必填），拒绝串逐字照抄；拒绝时置 CLOSING 并按 R4 关闭 |
| PENDING 时收到 `ClientRequest` | `close()`，不回包，不计非法包，计 `request_before_verify` |
| VERIFIED 时收到 `ClientRequest` | 按 §3.5 的顺序过闸，再分发给 `BattleRoomService`，处理器返回后写应答 |
| CLOSING 时收到任何帧 | 忽略（对应 `codec.cpp:158-162`、`:180-185`）。何时进 CLOSING 看谁发起关闭：直连面自己关（握手被拒、握手前发请求、非法帧 / 非法包达阈值、写缓冲满、强关）当场进，同一次读里后面的帧也作废（基线同步 `shutdown()` / `forceClose()`）；房间发起的优雅关闭（终局、销毁 / 作废、165、清退观众）当场只脱离房间（`isLive()` / `send` 失效），本次读处理完（`EdgeInboundGuard`）或排在当前任务之后（`DirectClose`）才进——同一次读里后面的帧照常分发、各自回包后才 FIN（基线 `ShutdownDirectConnAfterThisLoop` 只 `queueInLoop`，`battle_room_manager.cpp:1602-1617`） |
| `channelWritabilityChanged` 且不可写 | `close()`，计 `write_buffer_full`（对应 `edge.cpp:20-30`） |
| `channelInactive` | 连接数减一；取消握手定时器；VERIFIED 的调 `rooms.detachDirect(battleId, playerId, ch)` |

**关闭（`DirectClose`）**：
- `afterCurrentTask(ch, forceAfter, onStart)`：
  ```java
  logic.execute(() -> {
      onStart.run();   // 房间发起的：置 CLOSING（若本次读处理完时已置则无事）；握手被拒在调用前已同步置 CLOSING
      ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(f -> ((SocketChannel) ch).shutdownOutput());
      logic.schedule(ch::close, forceAfter);
  });
  ```
  - `execute` 排在当前 handler / 任务之后，所以当前处理器写的应答先进缓冲。
  - 空缓冲写的 promise 在它之前的全部数据写进内核之后才完成，再 `shutdownOutput` 发 FIN。
    不能直接 `shutdownOutput`：Netty 会丢弃 outbound buffer 里没写出的数据。
  - 对端不读时，空写永远不完成，由 `forceAfter`（1 s / 0.1 s）兜底。
- 房间槽位在调用方**立刻**摘除（`room.cpp:1632-1634`）。
- 重连顶替旧连接用 `ch.close()`：立即关，不发帧。

**限频与非法包**：
- 用挪到 xm-net 的 `MessageRateLimiter`，每条连接一份。语义同 PARITY「gate 按消息号限频」行：单调时钟纳秒窗口，被拒不占额度（`PARITY.md:40`）。
- 非法包阈值 `xm.battle.illegal-packet-threshold`，缺省 50，0 = 只计不断，同 gate 的 `xm.gate.illegal-packet-threshold`。握手成功时清零。

**消息号**：启动时按「服务裸名 `BattleClientPlayer` + 方法名」解析 4 个上行号与 7 个下行号（177 / 143 / 139 / 150 / 161 / 158 / 166），缺一个就拒绝启动（写法同 `xm-scene/src/main/java/com/game/scene/world/SceneMessageIds.java:38-58`）。

**采样日志**：按原因分别计数，每种第一次必打，之后每 1024 次一行（`edge.cpp:32-49`）。原因是有界枚举 `EdgeRejectReason`：
- 握手前：`at_capacity`、`handshake_timeout`、`request_before_verify`、`unknown_frame`；
- 握手：`ticket_hmac_mismatch`、`ticket_payload_parse_failed`、`empty_identity`、`node_mismatch`、`instance_mismatch`、`expired`、`role_invalid`、`ticket_not_in_roster`；
- 已验证后：`oversized`、`rate_limited`、`message_id_not_allowed`、`body_parse_failed`、`illegal_threshold`、`write_buffer_full`。

### 7.5 票据（`BattleTickets` + `BattleTicketIssuer`）

```java
package com.game.common.token;

public final class BattleTickets {
    public BattleTickets(byte[] secret);                                    // 空密钥抛异常
    public ByteString sign(ByteString payloadBytes);                        // 64 字节小写 hex ASCII
    public boolean signatureMatches(ByteString payloadBytes, ByteString signature); // MessageDigest.isEqual，大小写敏感
    public static Verdict classify(BattleTicketPayload p, int selfNodeId, String selfInstanceId, long nowMs);
    public enum Verdict { OK, EMPTY_IDENTITY, NODE_MISMATCH, INSTANCE_MISMATCH, EXPIRED, ROLE_INVALID; public String wireName(); }
}
```

- `classify` 的顺序与边界同 `sec.h:155-184`：`expire == now` 算过期；role 用 `getRoleValue()` 的整数值判断。
- 验签用 `BattleTokenVerifyRequest.payload` 的原字节；验签通过之后才解析。
- **`BattleSecretPolicy.check(battleSecret, gateSecretOrNull, runMode)`**：
  - 缺失或纯空白 → **任何模式**都拒绝启动（Java 不放行空密钥，§11 N5）；
  - 去首尾空白后 < 32 字节，或与 `XM_GATE_TOKEN_SECRET`（进程能看到时，去空白后比较）相同 → prod 拒绝启动，dev / test WARN；
  - 签名用原始字节，不去空白（同 `tok.h:46-56`）。
- **`BattleTicketIssuer.build(room, playerId, role) → Optional<BattleAssignedS2C>`**（逻辑线程）：
  1. payload 填 `{battle_id, player_id, battle_node_id = 租约节点号, battle_instance_id = 实例 UUID, expire_at_ms = room.deadlineMs, role}`；
  2. 只调一次 `toByteString()`，**同一份字节**既拿去签名，又放进 `token_payload`；
  3. 地址：`host = xm.advertise-host`，`port = xm.battle.advertise-port`（0 = client-port）。地址的做法同 PARITY 第 50 行的 gate。
  - Java 的通告地址与密钥总有值，所以「签不出」只剩 JCA 抛异常（实际走不到）。仍保留 fail-closed 结构：异常 → `empty`、计 `tickets{result=failed}`、打 ERROR，调用方按 1003 处理。

### 7.6 房间（`com.game.battle.room`）

**`BattleRoom`**（领域对象，只在逻辑线程上用）：

| C++ | Java |
|---|---|
| `battleId`、`matchMode`、`battleConfigId`、`activityContext` | 同名 final 字段，外加 `RoomOrigin origin`（MATCH / DEV，§7.12） |
| `engine` | `TurnBattleEngine`（`start` 返回 `Started` 时持有） |
| `routingByPlayer` / `routingByObserver`（`std::map`） | `TreeMap<Long, BattleRouting>(Long::compareUnsigned)` |
| `observerNames` | `Map<Long, String>` |
| 三个 `TimerTaskComp` | 三个 `Cancellable` |
| `actionDeadlineMs` / `deadlineMs` | long |
| `confirmResendUntilMs` | `int confirmResendsLeft`（初值 17，§11 N19） |
| `directConnByPlayer`（weak_ptr） | `TreeMap<Long, Channel>(Long::compareUnsigned)`；关闭时立刻移除（`AGENTS.md:38`），不长期钉住死连接 |
| — | `boolean closed`：erase 之前置位 |

**`RoomTable`**：照搬 `tbl.h`。
- `emplace` / `erase` 各恰好调一次 `RoomHooks.onCreated / onRemoved`；`ids()` 返回快照。
- Java 里 hooks 驱动房间数 gauge 和目录的 `room_count`；Java 没有 Agones。

**`BattleRoomService`**（签名示意；每个 public 方法开头断言 `loop.inEventLoop()`）：

```java
CreateBattleResponse createBattle(CreateBattleRequest req, RoomOrigin origin);
void destroyBattle(DestroyBattleRequest req);
void abortAll(String reason);
IssueBattleTicketResponse issueBattleTicket(IssueBattleTicketRequest req);
AddObserverResponse addObserver(AddObserverRequest req);      // Q1
void removeObserver(RemoveObserverRequest req);                // Q1
SubmitBattleActionResponse submit(long playerId, SubmitBattleActionRequest req);
BattleStateS2C getState(long playerId, GetBattleStateRequest req);
SetAutoBattleResponse setAuto(long playerId, SetAutoBattleRequest req);
StopWatchBattleResponse stopWatch(long playerId, StopWatchBattleRequest req);
OptionalInt attachDirect(long battleId, long playerId, int role, Channel ch);   // 返回大厅会话号（只进日志）
void detachDirect(long battleId, long playerId, Channel ch);
void onDirectVerified(long battleId, long playerId, int role);                   // 只对观众推 161
// 包内：RoundOutcome resolveRound(long battleId)；onBattleDeadline(long)；resendConfirmed(long)
```

**createBattle**：
- 判定与副作用顺序逐条照 §4.3.3–§4.3.4。
- 引擎拒绝一律回 1002；`InitRejection` 与 detail 只进日志（engine-spec D1）。
- 判定部分抽成纯函数，便于单测。

**计时器**：
- `BattleScheduler.after(delayMs, task)` / `every(periodMs, task)` 返回 `Cancellable`。生产实现是 `battle-logic` 上的 `schedule` / `scheduleAtFixedRate`；测试实现是 `ManualBattleScheduler`（虚拟时间，`advance(ms)` 按到期顺序执行）。
- 回调都捕获房间引用，执行时检查 `rooms.get(id) == room && !room.closed`。基线只按 id 重查；Java 多比一层身份，防止 Destroy 之后同 id 重建时，旧回调打到新房间（§11 N14）。
- 收尾、销毁、作废时一律先取消三个句柄。
- `BattleClock.epochMillis()` 用于 `action_deadline_ms`、`deadlineMs`、票据过期、`finished_at_ms`（同 `xm-scene/src/main/java/com/game/scene/world/SceneClock.java`）；间隔一律交给 scheduler。

**整场期限**：`after(max(0, deadlineMs − now))`。

**确认补发**：用次数而不是墙钟比较实现（§11 N19）：
- `every(10 s)`：剩余次数 > 0 就补发并减一；为 0 就取消。
- 首发之后正好再补发 17 次，与基线的标称行为相同；结束时随收尾取消。

**视角裁剪的类型约束**：
- `BattleViews.forParticipant(BattleStateS2C full, long viewer, List<BattleItemEntry> selfItems)` 与 `forObserver(BattleStateS2C full)` 返回 `ViewerState`，构造器包私有。
- 组装 143 / 139 / 140 / 161 / 158 的代码只接受 `ViewerState`，引擎的 `BattleStateS2C` 无法直接塞进推送或应答，「忘了裁剪」就编译不过。
- 裁剪对拷贝进行，不改引擎产出的对象。
- 观众版每回合只构造、序列化**一次**，同一份 `MessageContent` 写给全部观众（§11 N15）。

**排序**：结果事件的 teams、fled / dead 名单都用无符号比较（基线是 `std::map<uint32_t>` 与对 `uint64` 的 `std::sort`，`room.cpp:1138`；`activity.h:50-55`）。

### 7.7 推送出口（`com.game.battle.push`）

- **`PushPolicy.decide(category, hasLiveDirect)`**：纯函数，逐条移植 `pol.h:42-49` 与它的单测。
- **`BattleOutbound`**（逻辑线程）：
  - `pushBattleFrame(room, pid, messageId, ViewerFrame msg)`：槽里的 channel 仍 `isActive()` 就直写 `MessageContent{message_id, serialized_message}`；否则丢弃，计 `pushes{route=dropped}`，按消息号采样日志。
  - `pushLobby(room, pid, List<MessageContent>)`：有活直连就按序直写；否则交给 `LobbyAnnouncer`。
- **`PresenceLobbyAnnouncer`**：调 `PlayerPushes.pushAllToPlayer(pid, contents)`。
  - 查一次在线目录，取玩家**当前**会话；一次发布。
  - gate 侧玩家栅栏照常生效（`arch` §4.3）。
  - 语义是至多一次，结局只计数，不回传成业务失败。丢失时，靠 6.3 scene 推的 144 → 客户端 179 补签兜底。
- **为什么要新的批量动作**：现有 `pushToPlayer` 每次调用都是独立的「异步查目录 + 发布」，两次调用之间没有顺序保证（`xm-discovery/src/main/java/com/game/discovery/presence/PlayerPushes.java:56-61`、`:100-108`）。
- **`GatePush` 的改动**（`xm-discovery/src/main/proto/xm/discovery/presence.proto:27-37`）：

```proto
oneof action {
  bytes message_content = 3;
  uint32 kick_tip_id = 4;
  // 同一会话按序下发的多条 MessageContent（battle 大厅公告 177 → 143）。gate 先全部解析，任一损坏整条丢弃；
  // 再对每个目标在会话 EventLoop 的同一个任务里逐条过玩家栅栏后下发。
  MessageBatch message_batch = 5;
}
message MessageBatch { repeated bytes message_contents = 1; }
```

  - `GatePushSubscriber`（`xm-gate/src/main/java/com/game/gate/presence/GatePushSubscriber.java:61-104`）加一个 case：对每个目标调一次 `session.execute`，在里面依次 `deliverPush`。
  - 备选（Q3）：先发 177，等它的 stage 完成再发 143。不推荐：要多查一次目录，还依赖 Redisson 订阅回调的顺序。
- **回落为什么不用快照里的路由**：Java 的推送通道按在线目录寻址；而且按当前会话投递，修掉了基线「备战期间换会话，公告投到旧会话」的问题（`tbs.md:1011-1025`；§11 N2）。
  - CreateBattle 对 `gate_instance_id` 非空的校验仍然保留：它是 match 侧的契约，也是 6.3 的数据质量闸。

### 7.8 Dubbo 控制面

```java
package com.game.api;

/**
 * battle 节点控制面（基线 gRPC BattleNode，pb_node:84-90）。按节点直连、register = false、group DubboGroups.BATTLE_NODE；
 * 调用方从 Redis 节点目录（BattleNodeInfo.rpc_host / rpc_port）拿地址，引用必须 retries = 0。
 * future 异常完成 = 传输失败，结局未知：createBattle 时调用方按「可能已建」处理（发一次幂等的 destroyBattle 再补偿，gather.go:357-377）。
 */
public interface BattleNodeService {
    CompletableFuture<CreateBattleResult> createBattle(CreateBattleRequest request);                  // match 侧超时 5 s
    CompletableFuture<Empty> destroyBattle(DestroyBattleRequest request);                             // 3 s
    CompletableFuture<IssueBattleTicketResponse> issueBattleTicket(IssueBattleTicketRequest request); // 3 s（rbt.go:22）
    CompletableFuture<AddObserverResponse> addObserver(AddObserverRequest request);                   // 3 s（Q1）
    CompletableFuture<Empty> removeObserver(RemoveObserverRequest request);                           // 3 s（Q1）
}
```

- 参数与返回值直接用契约生成类（`com.game.proto.*`）：xm-api 已依赖 xm-proto，先例是 `AccountLoginService`（`arch` §4.1）。
- `CreateBattleResult` 放在 `xm-api/src/main/proto/xm/api/battle_control.proto`。xm-api 的 proto 不 import 依赖里的 proto（`xm-api/pom.xml:70`），所以契约应答按字节嵌入，先例是 `GatePush.message_content`：

```proto
enum BattleAdmission {
  BATTLE_ADMISSION_UNSPECIFIED = 0;     // 读方按传输失败处理（结局未知 → destroy 补偿）：字段缺失不能被读成「已受理」
  BATTLE_ADMISSION_ADMITTED = 1;        // 已进入业务判定；response 是契约 CreateBattleResponse 的字节（error_message 非 0 = 没建房）
  BATTLE_ADMISSION_NOT_ALLOCATABLE = 2; // 节点级拒绝，保证没建房、没推送、没发确认；调用方不发 destroy，换一个没试过的节点重试一次
}
message CreateBattleResult {
  BattleAdmission admission = 1;
  string reason = 2;   // not_started / closed / closed_in_loop / overloaded（只进日志与指标）
  bytes response = 3;
}
```

**提供方 `createBattle`**：
1. Dubbo 线程：准入闸不是 OPEN → `NOT_ALLOCATABLE(not_started | closed)`。
2. 在途数 ≥ `xm.battle.rpc-max-inflight`（缺省 256）→ `NOT_ALLOCATABLE(overloaded)`。Java 独有，同样无副作用。
3. `logic.execute(...)`；被拒（loop 正在关闭）→ `NOT_ALLOCATABLE(closed)`。
4. 逻辑线程上复核准入闸：不是 OPEN → `NOT_ALLOCATABLE(closed_in_loop)`；否则交给 `BattleRoomService.createBattle` → `ADMITTED` + 应答字节。
5. 在 `battle-rpc-reply` 上完成 future。

- 基线第 ② 道 Agones 许可不移植。
- **其余四个方法**：业务错误都在应答的 `error_message` 里；Dubbo 层恒成功（同 `node.cpp:127-209`）。loop 拒绝投递或在途超限时 future 异常完成（传输失败）。
- **鉴权**：调用方 MAC（`XM_DUBBO_SECRET`，必填）与 killswitch 提供方过滤器自动生效（`arch` §4.1、§4.15）。
  - `IssueBattleTicket` 信任调用方填的 player_id，与基线相同（`pb_node:70-76`）；安全性来自调用方鉴权 + 内网隔离。
- **导出**：编程式 `IsolatedDubboModule`，`register = false`；导出成功之后才把 rpc 地址写进目录（先例 `SceneAssetRpcServer`，`arch` §4.12）。

### 7.9 出站端口（逻辑线程只调接口；实现必须异步、不阻塞）

| 端口 | 6.2 的调用点 | 6.2 缺省实现 | 真实传输 |
|---|---|---|---|
| `SceneBattleEvents.confirm(routing, playerId, battleId, deadlineMs)` | 建房首发 + 补发（§4.8） | `LoggingSceneBattleEvents`：DEBUG 日志 + `scene_events{kind=confirm, result=logged}` | 6.3（Q13） |
| `SettlementSink.dispatch(routing, playerId, settlement)` | `FinishBattle` 逐人，排在本人 150 之后（R6） | 打 INFO + `scene_events{kind=settlement, result=logged}` | 6.3：先落 Redis、后投递、未销账就重投（`combat.md:269-279`） |
| `ActivityResultSink.dispatch(event)` | 活动局的结果事件 | 打 INFO + 计数 | 6.3 / 4.6：先落 `xm:battle:activity-result:{battle_id}` 再发 |
| `BattleResultSink.publish(event)` | 普通局的结果事件 | 打 INFO + 计数 | 6.4（Q12） |

- **地址解析**（6.3 实现时遵守）：
  - 确认事件按快照路由的 `(zone_id, scene_node_id)` 查 scene 目录，**目录里的 `instance_id` 必须等于 `routing.scene_instance_id`**，不等就不发、只计数（`skipped{reason=stale_instance}`）。这等价于基线 Kafka 的 `target_instance_id` 过滤（`xm-api/src/main/proto/xm/api/node_directory.proto:22-34`）。
  - 结算重投按玩家位置重新解析，同基线 `room.cpp:138-149`。
- **dev 房间**（`origin = DEV`）：`SettlementSink`、`ActivityResultSink`、`BattleResultSink` 一律跳过，只记日志。这样 6.3 / 6.4 落地之后，dev 接口也不会变成发奖口子（§7.12）。

### 7.10 节点身份、目录与租约丢失

- **节点号**：`NodeIdLease.acquire(redis, scheduler, NodeTypes.BATTLE, 0, …)`（`xm-discovery/src/main/java/com/game/discovery/NodeIdLease.java:81`）。
  - 键为 `xm:node-id:battle:0:{id}`、`xm:node-id-epoch:battle:0:{id}`（`xm-discovery/src/main/java/com/game/discovery/RedisKeys.java:15-31`）。
  - 实例 id 是每次进程启动生成的 `UUID.randomUUID()`。
- **目录**：`NodeDirectory<BattleNodeInfo>`，键 `xm:nodes:battle:0`，每 5 s 写一次，TTL 15 s（`arch` §6）。
  - `node_directory.proto` 新增：

```proto
message BattleNodeInfo {
  uint32 node_id = 1;
  string instance_id = 2;
  string rpc_host = 3;          // BattleNodeService 直连地址；导出成功后才写
  uint32 rpc_port = 4;
  string client_host = 5;       // 通告给客户端的直连地址（排障用；票据里的地址以 battle 自签的为准）
  uint32 client_port = 6;
  bool accepting = 7;           // 准入闸 OPEN 且租约有效
  uint32 room_count = 8;
  uint32 connection_count = 9;
  string table_fingerprint = 10;
}
```

  - 读方（6.4）只从 `accepting = true` 的条目里随机挑。目录最多滞后 5 s，所以「NOT_ALLOCATABLE 后换节点重试一次」仍要保留（同 `gather.go:334` 按 endpoint 排除）。
- **租约丢失**（Java 独有；推荐见 Q4）：
  1. 在逻辑线程上 `admission.close()`；
  2. 停止发布并尽力删除目录条目；打 ERROR，计 `lease_lost`；
  3. **不作废**在打的房间：battle 不持有权威数据；票据带实例 id，同号的新进程不会接受旧票；结算与确认按玩家和 battle_id 寻址，与节点号无关；
  4. 房间按期限或胜负自然结束；房间数归零时打一行「可安全重启」；进程保持存活但不可分配。
  - **代价**：match 按 node_id 补签时，会找不到节点（回 1003），或者打到同号的新持有者（回 1005，客户端判 BattleGone）。
  - 与 gate 不同：gate 丢租约必须关会话，因为会话号高位就是节点号（`arch` §6）；battle 没有这种复用冲突。

### 7.11 启动与停机（`BattleNode`）

**启动**（任一步失败都拒绝启动）：
1. 解析 `XM_RUN_MODE`（`xm-common/src/main/java/com/game/common/RunMode.java`），不认识的值 WARN 并按 prod 处理。
2. 门禁：
   - 票据密钥策略（§7.5）；
   - prod 下有效 `max-connections` 为 0 → 拒启；
   - 缺 `XM_DUBBO_SECRET` → 拒启；
   - `table-fingerprint-mode` 非法 → 拒启（枚举绑定，§11 N18）；
   - `handshake-timeout` 超出 (0, 60 s] → 拒启。
3. 加载 `ConfigTables`，构造 `TableBattleData`（构造失败即拒启，engine-spec D4）与 `TableMessageLimits`。
   - 打出七张表的行数与指纹；四张关键表为空打 ERROR，Item 为空 WARN，**不拒启**（同 `main.cpp:205-214`）。
   - 解析 `BattleMessageIds`，缺号拒启。
4. 占节点号租约，生成实例 UUID。
5. 起 boss / logic 线程与回复执行器。
6. 导出 Dubbo（端口被占用即拒启）。
7. 绑定直连端口（被占用即拒启）。
8. 在逻辑线程上 `admission.open()`。
9. 首次发布目录（`accepting = true`），之后每 5 s 一次。
10. 打一行就绪日志：client 地址 / 通告地址 / rpc 地址 / max_connections / run_mode / 指纹。

先开闸、后进目录：match 在开闸之前找不到这个节点，基线那段「已发布、未开闸」的窗口在 Java 里不存在。准入闸保留是为了停机（§11 N4）。

**停机**（`SmartLifecycle.stop`）：
1. 停止发布目录并删除条目（读方最多滞后 5 s）。
2. 在逻辑线程的**同一个任务**里：`admission.close()` → `abortAll("node_shutdown")`。
   - 观众收 166 ABORTED + ONGOING；参战者不收帧；全部房间直连走 `DirectClose`。
3. 关监听 channel；有界等待正在优雅关闭的连接排空，上限 `xm.battle.shutdown-flush-timeout`，缺省 1 s（与基线强关延迟同值）；然后强关剩余的未验证、空闲连接。**修基线缺陷**（§6.6，§11 N16）。
4. 撤销 Dubbo 导出（等待在途调用至多 1.5 s，`IsolatedDubboModule.DEFAULT_SHUTDOWN_WAIT`，`xm-api/src/main/java/com/game/api/asset/IsolatedDubboModule.java:40`）。
   - 期间进来的 createBattle 在逻辑线程上复核看到 CLOSED → NOT_ALLOCATABLE；issueBattleTicket → 1005（房间已作废）。
5. 停逻辑线程与回复执行器。
6. 交还租约。

停机只由 Spring 的关停钩子驱动：`BattleApplication.main` 在起 Spring 之前设系统属性 `dubbo.shutdownHook.listenIgnore=true`（同 xm-scene / xm-guild）。
否则 Dubbo 3.3.6 导出时无条件往 JVM 注册的 `DubboShutdownHook` 会在 SIGTERM 时与 Spring 的钩子并行，抢在第 2 步之前把控制面置只读并销毁，
第 4 步的顺序被倒过来，期间的 createBattle 拿到传输失败而不是 NOT_ALLOCATABLE。这个开关在 `FrameworkModel.newApplication()` 构造部署器时就读，
只能是事先设好的系统属性（或 `DUBBO_SHUTDOWNHOOK_LISTENIGNORE` 环境变量），`IsolatedDubboModule.create` 之后再写应用配置已经晚了。

**可选**：管理端口挂 GM 签名停机（复用 `GmShutdownHandler`，`arch` §6），留给 7.6。

### 7.12 dev / test 管理接口（Java 独有）

| 接口（管理端口 18112） | 请求体 | 应答 | 说明 |
|---|---|---|---|
| `POST /admin/battle/dev/create` | 契约 `CreateBattleRequest` 字节 | `CreateBattleResult` 字节 | 快照里 `routing.gate_instance_id` 为空的，由接口补全路由，补不全回 422 并写原因、不建房：gate 部分（session / gate 节点与实例 / zone）取在线目录；scene 部分取位置记录（只认状态 `o`，同 `SceneAssetLocator`）+ scene 目录的 `instance_id`。房间标 `origin = DEV` |
| `POST /admin/battle/dev/destroy` | `DestroyBattleRequest` | 204 | |
| `POST /admin/battle/dev/issue-ticket` | `IssueBattleTicketRequest` | `IssueBattleTicketResponse` | 模拟 179 的 battle 侧 |
| `POST /admin/battle/dev/add-observer` / `remove-observer` | `AddObserverRequest` / `RemoveObserverRequest` | 契约应答 / 204 | Q1 采纳时开放；add 同样从在线目录补 gate 路由 |

- **鉴权**同 xm-trade 播种接口（`arch` §4.20）：
  - 头 `X-Xm-Admin-Token`（环境变量 `XM_ADMIN_TOKEN`，常数时间比较），不设则 503；
  - 头 `X-Xm-Operator` 必填，每次写一行审计日志；
  - 运行模式不是 dev / test 一律 403。
- **线程**：Redis 查询在 Tomcat 线程上做，然后调用进程内的 `BattleNodeServiceImpl`（同一条准入与投递路径），带 5 s 超时等待。
- **dev 房间**：照常推 177 / 143 / 139 / 150、照常补发确认（6.2 只记日志）；**永不**投递结算与结果事件（§7.9）。

### 7.13 存储

| 存储 | 6.2 | 之后 |
|---|---|---|
| MySQL | 不用。房间是纯内存的（`room.h:30-32`） | — |
| Redis：节点号租约、节点目录 | 写（§7.10） | — |
| Redis：在线目录 `xm:presence:{pid}`、推送频道 `xm:gate-push:{zone}:{gate}` | 读 / 发布（大厅公告） | — |
| Redis：位置 `xm:location:{pid}`、scene 目录 | 只有 dev 接口读 | 6.3 用位置解析结算目标 |
| 6.3 / 6.4 预留（全部经 `RedisKeys`，`xm:` 前缀） | — | `xm:battle:settlement:pending:{pid}` / `…:pending-id:{pid}`（TTL 7 天，一段 Lua 写两键）；`xm:battle:activity-result:{battle_id}`；scene 拥有的 `xm:battle:lock:{pid}` / `xm:battle:ctx:{pid}`；match 拥有的落点 / 观战索引 `xm:spectate:battle:{battle_id}`（要带 `battle_instance_id`，Q14） |

### 7.14 与并行批次的钩子

- **6.1**：API 见 §0.3。
  - `validateAction` 只返回 1000 或真实 tip；节点仍保留「0 → 1005」的兜底（`room.cpp:717-722`）。
  - 测试用 6.1 测试源里的 `MemoryBattleData`，需要以 test-jar 形式共享（`xm-battle-engine/src/test/java/com/game/battle/engine/MemoryBattleData.java`）。
- **5.2**：
  - 战斗中不能换图、不能交接（`handoff-spec:106`），所以房间存续期间，快照路由里的 scene 节点与实例一般保持有效；例外是 scene 节点重启，或玩家离线后在别的节点重登（由确认事件的实例过滤与 6.3 的重投兜住）。
  - battle 不读 5.2 改动的任何结构；dev 接口只读位置记录的稳定字段。
- **6.3**：
  - scene 出快照时填 `BattleRouting`（Java 的 scene 能从链路握手拿到 gate 实例 id）与指纹；
  - 实现 `SceneBattleEvents` / `SettlementSink` / `ActivityResultSink` 的真实传输；
  - 在接口文档里写清「DestroyBattle 发生在确认之后 → 冻结要等到期限」的取舍（Q5）。
- **6.4**：
  - 调用 `BattleNodeService`；按 `admission` 处理；
  - 建房**之前**写落点记录（至少 `battle_node_id` 与 `battle_instance_id`）；
  - 179 的处置同 §2.7；
  - 实现 `BattleResultSink` 的传输与消费（按 battle_id 幂等）；
  - 改 matched TTL 公式时回看 §10.4。
- **6.5**：见 Q1。直连面已放行 165，观众首帧钩子 `onDirectVerified` 已留好。

---

## 8 配置与常量

### 8.1 `xm.battle.*`（`xm-battle/src/main/resources/application.yaml`）

| 键 | 缺省 | 说明 |
|---|---|---|
| `client-port` | `${XM_BATTLE_CLIENT_PORT:12000}` | 直连监听端口（同机多实例各不相同） |
| `client-bind-host` | `0.0.0.0` | 直连面对客户端开放 |
| `advertise-port` | `${XM_BATTLE_ADVERTISE_PORT:0}` | 票据里的端口；0 = client-port。host 用已有的 `xm.advertise-host`（PARITY 第 50 行） |
| `rpc-port` | `${XM_BATTLE_RPC_PORT:21200}` | BattleNodeService |
| `max-connections` | `${XM_BATTLE_MAX_CONNECTIONS:4096}` | 同基线 dev 配置（`deploy.yaml:136`）；0 只允许在 dev / test，此时硬上限 65535 |
| `handshake-timeout` | `10s` | **客户端可见**，不建议改；只为测试可注入；校验范围 (0, 60 s] |
| `illegal-packet-threshold` | `50` | 0 = 只计不断（同 gate） |
| `table-fingerprint-mode` | `${XM_BATTLE_TABLE_FINGERPRINT_MODE:warn}` | off / warn / enforce；非法值拒启 |
| `rpc-max-inflight` | `256` | Java 独有（§7.8） |
| `shutdown-flush-timeout` | `1s` | §7.11 |

`server.port: 18112`；actuator 只暴露 `health`、`prometheus`；管理端口缺省只绑 `127.0.0.1`（`XM_MANAGEMENT_ADDRESS`）。

### 8.2 环境变量

| 变量 | 必填 | 用途 |
|---|---|---|
| `XM_BATTLE_TOKEN_SECRET` | 是 | 票据签名密钥（只从环境变量读，`AGENTS.md:40`） |
| `XM_DUBBO_SECRET` | 是 | Dubbo 调用方鉴权 |
| `XM_GATE_TOKEN_SECRET` | 否 | 只用于「不得与 gate 相同」检查（Q11） |
| `XM_ADMIN_TOKEN` | 否 | dev 接口 |
| `XM_RUN_MODE` | 否 | 运行模式 |
| `XM_MANAGEMENT_ADDRESS` / `SERVER_PORT` | 否 | 管理端口 |

### 8.3 代码常量（不开放配置；注释写清与谁联动）

| 常量 | 值 | 来源 / 联动 |
|---|---|---|
| 回合窗口 | 6000 ms；装填时全员就绪为 2000 ms | `BattleConstants.ROUND_DURATION_MS` / `AUTO_ROUND_INTERVAL_MS`（`BattleConstants.java:18`、`:34`） |
| 缺省整场期限 | (30 + 2) × 6000 = 192000 ms | `BattleConstants.DEFAULT_MAX_ROUNDS`（`:20`）；`room.cpp:563-564` |
| 确认补发 | 每 10 s 一次，共 17 次（窗口 180 s） | 必须 ≥ match 最长 matched TTL（96 s）+ scene 锁余量（60 s）；配单测（§10.4） |
| 每房观众上限 | 20 | `room.cpp:64` |
| 单条 `ClientRequest` 上限 | 1024 B | `edge.h:53` |
| 帧长上限 | 64 KiB | `ClientFrames.DEFAULT_MAX_LEN` |
| 写水位 | 1 / 2 MiB | `edge.cpp:23` |
| 握手被拒后强关 | 100 ms | `edge.cpp:273` |
| 优雅关闭强关兜底 | 1 s | `room.cpp:1614` |
| 采样日志 | 首次 + 每 1024 次 | `edge.cpp:43`；`room.cpp:129` |

### 8.4 基线常量全表（对照用）

| 名称 | 值 | 出处 | 作用 / 归属 |
|---|---|---|---|
| kRoundDurationMs / kAutoRoundIntervalMs | 6000 / 2000 ms | `consts.h:15`、`:35` | 回合窗口 |
| kDefaultMaxRounds | 30 | `consts.h:18` | 引擎缺省回合上限；节点期限兜底 |
| kMaxBattleTeamSize | 5 | `consts.h:30` | 每房 ≤ 10 名参战者 |
| BattleMaxDurationSeconds | 300 s | `config.go:55-58`；`match_service.yaml:57` | match 填的期限 |
| kConfirmResendWindowMs / IntervalSec | 180000 ms / 10 s | `room.cpp:267-268` | 确认补发 |
| kMaxObserversPerRoom | 20 | `room.cpp:64` | 观众上限 |
| kHandshakeTimeoutSec | 10 s | `edge.h:51` | 握手期限 |
| kMaxClientRequestBytes | 1024 | `edge.h:53` | 体积闸 |
| kHardMaxConnections | 65535 | `edge.h:55` | 配 0 时的硬上限 |
| BattleMaxConnections（dev） | 4096 | `deploy.yaml:136` | 并发上限 |
| kDirectConnHighWaterMark | 2 MiB | `edge.cpp:23` | 写缓冲 |
| 非法包阈值 | 50 | `illegal.h:61` | 断开 |
| 限频缺省 | 3 条 / 1 s | `limiter.h:15-16` | 未配表的号 |
| 握手被拒 / 不收类型的强关延迟 | 0.1 s | `edge.cpp:273`、`:527` | 关闭 |
| 优雅关闭强关 | 1.0 s | `room.cpp:1614` | 关闭 |
| kSettlementRetryIntervalSec × MaxAttempts | 10 s × 12 | `room.h:175-176` | 6.3 |
| kPendingSettlementTtlSec | 7 天 | `room.h:179` | 6.3 |
| 活动结果重发 | 10 s × 30，TTL 7 天 | `activity.h:32-36` | 6.3 / 4.6 |
| Agones 心跳 / 判卡死 / 许可等待 | 1 s / 10 s / 3000 ms | `main.cpp:49`；`agones.h:95`、`:102` | 不移植 |
| match createBattle / rollback / prepare 超时 | 5 / 3 / 3 s | `gather.go:25-30` | 6.4 |
| matched TTL（1 / 2 / 5 / 10 人） | 42 / 48 / 66 / 96 s | `queue.go:367-380`；`room.cpp:260-261` | 确认窗口推导的输入 |
| scene kLockExtraTtlSec / kReaperIntervalSec | 60 s / 30 s | `pb.h:111`、`:102` | 6.3 |

---

## 9 指标（`BattleMetrics`；不用 player_id / battle_id / session / IP 作 label，`AGENTS.md:66`）

| 指标 | 类型 | 标签 | 含义 / 基线对应 |
|---|---|---|---|
| `xm_battle_rooms` | Gauge | — | 房间数（RoomTable hooks 维护） |
| `xm_battle_room_creates_total` | Counter | `result` = ok / idempotent / invalid / fingerprint_reject / engine_reject / ticket_failed / not_allocatable | 建房结局；对应 `battle_table_fingerprint_reject`、`battle_ticket_issue_failed`（`room.cpp:440`、`:579`） |
| `xm_battle_room_ends_total` | Counter | `reason` = finished / deadline / destroyed / aborted | 房间结局 |
| `xm_battle_fingerprint_mismatch_total` | Counter | `mode` = warn / enforce | `room.cpp:440`、`:446` |
| `xm_battle_rounds_total` | Counter | `trigger` = timer / all_ready / auto_flip | 结算触发方式 |
| `xm_battle_round_resolve_seconds` | Timer（0.1 ms–1 s） | — | 逻辑线程上「结算 + 广播」的耗时 |
| `xm_battle_direct_connections` | Gauge | — | 直连数（含未握手） |
| `xm_battle_handshakes_total` | Counter | `result` = ok / repeat / §7.4 握手类原因 | 对应基线采样日志的 reason |
| `xm_battle_client_requests_total` | Counter | `method` = SubmitBattleAction / GetBattleState / SetAutoBattle / StopWatchBattle / other；`result` = ok / business_error / oversized / rate_limited / not_allowed / bad_body | 每条请求恰好计一次 |
| `xm_battle_invalid_frames_total` | Counter | `reason` = 解码器的原因 | 同 gate |
| `xm_battle_disconnects_total` | Counter | `reason` = handshake_timeout / handshake_rejected / request_before_verify / illegal_packets / write_buffer_full / invalid_frame / at_capacity / replaced / battle_closed / shutdown | 服务端主动断开 |
| `xm_battle_pushes_total` | Counter | `category` = battle_frame / lobby；`route` = direct / via_gate / dropped；`message` = 7 个 Notify 名 | 对应 `battle_frame_dropped_no_direct`（`room.cpp:131`） |
| `xm_battle_lobby_push_outcomes_total` | Counter | `outcome` = sent / offline / gate_unreachable / error | `PlayerPushes` 的结局 |
| `xm_battle_tickets_total` | Counter | `path` = create / observer / reissue；`result` = ok / failed | 签票 |
| `xm_battle_scene_events_total` | Counter | `kind` = confirm / settlement；`result` = logged / sent / skipped / error | 6.2 只有 logged |
| `xm_battle_results_total` | Counter | `channel` = plain / activity；`result` = logged / sent / error | 6.2 只有 logged |
| `xm_battle_rpc_seconds` | Timer（1 ms–1 s） | `method`（5 个）；`result` = ok / business_error / not_allocatable / error | 提供方耗时，含逻辑线程排队 |
| `xm_battle_logic_pending_tasks` | Gauge | — | 逻辑线程队列长度（同 scene，`arch` §11） |
| `xm_battle_admission_phase` | Gauge | — | 0 / 1 / 2 |
| `xm_battle_lease_lost_total` | Counter | — | §7.10 |

抓取地址 `http://127.0.0.1:18112/actuator/prometheus`，登记进 `arch` §11 的表。

---

## 10 隐患与边界

### 10.1 实现必须守住的坑

1. **握手应答必须是第一帧**（R1）：挂接与写应答在同一次调用里完成；161 在应答之后。
2. **推送先于应答，FIN 在应答之后**（R2 / R3）：直接 `channel.close()` 会丢掉最后一击的应答（`room.cpp:1597-1601`）；直接 `shutdownOutput()` 会丢掉缓冲里的终局包（§7.4）。
3. **结算之后不得再碰房间**（R8）：应答必须在调用 `resolveRound` 之前填完。
4. **视角裁剪**：开局、回合、补拉、观战首帧与每回合，漏掉任何一条路径就会泄露对手的冷却和道具。用 §7.6 的类型约束兜底。
5. **签名格式**：64 字节小写 hex 的 ASCII，比较大小写敏感；签的字节就是下发的字节；验签用原字节。
6. **`expire_at_ms == now` 算过期**；`deadline_ms ≤ now` 一律改用缺省期限，不只是「没填」。
7. **SetAutoBattle 只在翻转时立即结算**；Java 引擎成功码是 1000，不能沿用 `!= 0`。
8. **建房幂等不比较请求内容**；预签失败必须零副作用：不插表、不触发回调、不装计时器、不推送、不发确认。
9. **RoomTable 回调每次插入 / 移除恰好一次**：Java 不许绕开它直接改 map。
10. **参战者不能观战自己这局**：同一 player_id 只有一个直连槽（`room.cpp:802-810`）。
11. **139 的 `action_deadline_ms` 是下一回合的截止**（先装填再广播）；分出胜负时为 0。
12. **GetBattleState 对非成员回空状态**（battle_id 0），不回错误码。
13. **Dubbo 线程不碰房间**；调用方引用 `retries = 0`。
14. **gate 永远不路由 `BattleClientPlayer`**：只回 23 `{1003}`，不计非法包、不断连。
15. **关闭后清掉 Channel 引用**（`AGENTS.md:38`）：槽位立刻摘除；detach 按引用相等比较。
16. **无符号**：player_id 名单、team 分组、fled / dead 排序都用无符号比较（怪物与宝宝的 actor_id 在 long 里是负数，engine-spec §1）。

### 10.2 基线怪癖（照搬，PARITY 注明）

| # | 现象 | 出处 | 处置 |
|---|---|---|---|
| B1 | 已验证连接再握手：回 success 和**旧** battle_id，新票一个字节都不看 | `edge.cpp:276-281` | 照搬（客户端可见，无害） |
| B2 | 全自动房间里，挂机参战者合法地 Submit，`submitAction` 返回就绪 → **立即结算**。一个客户端按每秒 3 条提交，就能把 2 s 节奏打快；162 有翻转判定，149 没有 | `room.cpp:727-734`；engine-spec §2.1、§2.4 | 缺省照搬；是否改成「只在翻转时立即结算」见 Q2 |
| B3 | 校验失败的提交回错误，但同回合先前已收下的合法行动仍会执行 | engine-spec §2.1 | 照搬 |
| B4 | 白名单外的号也先过限频，每个不同的号占一个窗口；内存靠非法包阈值兜住（阈值 0 时无界） | `edge.cpp:418-425` | 保留顺序（客户端可见：白名单外的号同样可能回 1008） |
| B5 | 非法包到阈值强关时，刚写的信封错误可能丢 | `edge.cpp:376` | 不保证送达（同基线） |
| B6 | 期限路径给观众的 166 是 `ABORTED + DRAW`，与 `pb_player:81` 注释不一致 | `room.cpp:1100-1103` | 照搬代码行为 |
| B7 | 幂等命中不重推 177 / 143：第一次的推送丢了，客户端只能靠 144 + 179 恢复 | `room.cpp:492-497` | 照搬 |
| B8 | 指纹 enforce 的 `parameters[0]` 里 `request=` 永远是 request 的指纹，不一致来自快照时可能是空串 | `room.cpp:529-532` | 照搬文本 |
| B9 | 被眩晕 / 冰冻的手动玩家无法就绪，只能等满 6 s；开挂机可以绕开 | engine-spec §2.5 | 照搬 |

### 10.3 基线缺陷（Java 修）

| # | 缺陷 | 出处 | Java |
|---|---|---|---|
| F1 | 停机时观众的 166 可能被 `DisconnectAll` 强关吞掉 | §6.6 | 先排空再关（§7.11 第 3 步；§11 N16） |
| F2 | 大厅公告按开局快照的会话投递，备战期间换了会话就投到旧会话 | `tbs.md:1011-1025`；`combat.md:255` | 按在线目录的当前会话投递（§7.7；§11 N2） |

### 10.4 跨批次的数值依赖

- **确认补发窗口 ≥ match 最长 matched TTL + scene 锁余量**（96 + 60 = 156 ≤ 180）。
  - 6.2 写一条单测钉住这条不等式；6.3 / 6.4 落地后改成直接引用对方的常量。
- **票据寿命 = 房间期限 = 确认事件里的 `deadline_ms` = scene 冻结的正式期限**：四者必须同值（`room.cpp:566-567`、`:625`）。
- **DestroyBattle 或停机发生在确认之后**：玩家冻结到期限，最长约 300 s（§4.7；Q5）。

### 10.5 分区稿之间的分歧与裁决（都回到代码或现状核对过）

| 分歧 | 分区稿说法 | 裁决与依据 |
|---|---|---|
| inventory 行号 | ② ③ 引 `combat.md:45`、`:57`、`:69`、`:81`、`:93`、`:117`、`:170` | 现行文件中这些条目在 `:209`–`:339`（§0.2），本稿全部改用实际行号 |
| 线程模型 | ① ③：独立 `DefaultEventLoop` 当逻辑线程，I/O 另起，① 还加了 `battle-out` 出站线程；② ：逻辑线程 = 直连面唯一 worker | 采纳 ②（§7.3）。出站端口一律异步，6.2 没有阻塞出站；6.4 的 Kafka 生产者自带发送线程，不需要 `battle-out` |
| Dubbo 端口 | ① ② 20888；③ 21200 | **21200**：20881–20887 是注册中心里按服务寻址的端口；按节点直连、`register=false` 的先例是 scene 的 21100 |
| Dubbo group | ② ③ `battle` | **`battle-node`**：`DubboGroups` 约定 `ClientMessageService` 的 group 等于 proto 一级目录（`xm-api/src/main/java/com/game/api/DubboGroups.java:3-6`），`battle` 留给这一层；服务对服务的接口另起名（先例 `scene-asset`） |
| 「不可分配」的表达 | ② `BattleCreateReply{admission, battle_id, tip_id, tip_parameters}`；③ `CreateBattleResult{bool not_allocatable, reason, bytes response}` | 取 ③ 的嵌入式结构，把 bool 换成首值 UNSPECIFIED 的枚举（缺字段不会被读成「已受理」，§7.8） |
| `max-connections` 缺省 | ② 4096；③ 10000 | **4096**：同基线 dev 配置（`deploy.yaml:136`） |
| 指纹模式写错 | ① ② 拒启；③ 回落 warn | **拒启**（Spring 枚举绑定，配置错就快速失败）；§11 N18 |
| 租约丢失 | ② 等同停机（作废全部房间）；③ 只关闸、不作废 | **只关闸、不作废**（§7.10）。理由：节点号不是任何玩家数据的围栏，票据另带实例 id；作废的代价（无结算、冻结到期限）大于补签误判的代价。Q4 |
| 启动顺序 | ① 先发布目录、最后开闸（照基线）；② ③ 先开闸、后发布 | **先开闸、后发布**（§7.11） |
| 177 → 143 保序 | ② `GatePush` 新增多条消息字段；③ 顺序发布 `pushInOrder` | 采纳 ②（`MessageBatch`）：一次查目录、一次发布、原子、不依赖 Redisson 回调顺序。Q3 |
| 观战房间侧 | ① ② 留给 6.5；③ 推荐放进 6.2 | 推荐放进 6.2（Q1）：观众名单、166 收尾、单槽都在房间代码里，拆开会让 6.5 重开房间代码 |
| dev 建房接口 | ① `POST /admin/battle/create`；② `dev-create` + 合成快照；③ `/admin/battle/dev/*`，请求体是契约字节 | 采纳 ③ 的路径与字节请求体，加上 ② 的「dev 房间永不结算」（§7.12） |
| 确认补发的实现 | 照基线比较墙钟 | 改为计次（17 次），可观察行为相同，测试确定（§11 N19） |
| matched TTL 公式出处 | ① `queue.go:342-367`；② `:344-367` | 实际是 `queue.go:367-380`（`matchedTicketTTLFor`） |
| 非法包阈值为 0 | ② 只许 dev / test | 与 Java gate 一致：任何模式都允许，配置即生效（`xm-gate/src/main/java/com/game/gate/GateProperties.java:18`） |
| 握手期限是否可配 | ② 不可配；③ 可配 (0, 60 s] | 可配，缺省 10 s，标「客户端可见，不建议改」（便于测试注入） |

### 10.6 对 inventory（`combat.md`）的勘误与补充

1. **`:214`（battle-room-lifecycle）**：
   - 漏了一类 1005：`battle_id == 0` 或没有玩家（`room.cpp:499-505`）；
   - 「Missing deadline_ms」应为 `deadline_ms ≤ now`（`room.cpp:561`）；
   - 幂等命中**不重推任何东西**；`created_at_ms` 没有任何代码读。
2. **`:214`「resent every 10 s for 180 s」**：准确说法是首发 1 次，+10 … +170 s 共补发 17 次，+180 s 停表；战斗结束时一起停（§4.8）。
3. **`:214`「2000 ms when every alive player is on auto」**：准确条件是「**装填那一刻** `AllPlayersReady()` 为真」，包括空真（只剩宝宝）；第一回合恒为 6000 ms。
4. **`:214`「when all players are ready the round resolves early」**：162 只在翻转时提前结算；149 只要 `submitAction` 返回就绪就结算，所以全自动房间里也会提前（B2）。
5. **`:214`「The battle deadline forces outcome DRAW, and observers get ABORTED」**：补充——没有终局 139；照常给参战者发 150 DRAW（无奖励，但 HP、MP、消耗照常带出）；照常发结算与结果事件；观众收到的 outcome 是 **DRAW**，不是 ONGOING。
6. **`:214` 漏写线上顺序**：提前结算时，提交者看到的是 139 →（150）→ 应答 → FIN（§5.8 O3）。DestroyBattle 与停机作废时，参战者一条帧都收不到（O6）。
7. **`:214` 补一句**：现行 Dungeon 表下，PVE 的回合上限（300 / 600 回合）不可达，整场期限才是实际上限（§4.5）。
8. **`:215` 补充**：基线没有 `BattleRoomManager` 的单测，`cpp/nodes/battle/tests/` 下只有房间表、准入闸、推送判定、票据四组；房间生命周期只靠 robot 覆盖。
9. **`:238`（battle-direct-connect-edge）**：
   - 重复握手**不看新票**，回旧 battle_id（B1）；
   - 第一帧是别的合法类型时 0.1 s 后关，不是立即关；
   - 闸门顺序是体积 → 限频 → 白名单 → 解析，所以白名单外的号也先占限频额度（B4）；
   - 「(2) empty secret in prod → refuse」是接入时的纵深防御，启动门禁已先拒过一次（`main.cpp:165-170`）；
   - 拒绝串逐字列在 §3.4。
10. **`:250`（battle-ticket-assignment）**：
    - 生产里 `expire_at_ms` = match 的 gather 起点 + 300 s（`gather.go:219`）；
    - 补签出的票与原票逐字节相同；
    - 「otherwise 1005」也包括 `player_id == 0`（不是 1012，`room.cpp:2039-2044`）；
    - 179 在 match 侧的 tip 与 `parameters` 文案见 §2.7（16004 两种、1005、1003）。
11. **`:226`（battle-client-actions-push）**：漏写——上行触发结算时，139 / 150 / 166 **先于**该上行的应答写出，然后才是 FIN；还漏了 B2。
12. **`:262`（battle-spectate）**：「20 observers already → 1008」在幂等判定**之后**（`room.cpp:865-871`）；幂等路径也会先重签，签不出就摘除并关直连（`:823-838`）。
13. **`:286`（battle-node-admission-ops）**：补充——停机路径会把观众的 166 强关丢掉（§6.6）；dev 配置的连接上限是 4096（`deploy.yaml:136`）。

---

## 11 建议的有意差异（PARITY 候选）

| # | 差异 | 基线 | Java | 客户端可见？ | 两版同改？ |
|---|---|---|---|---|---|
| N1 | 控制面传输与「不可分配」 | gRPC；`UNAVAILABLE "battle_not_allocatable"`，字符串精确匹配 | Dubbo Triple 按节点直连；`CreateBattleResult.admission` 枚举 | 否 | 否 |
| N2 | 大厅公告寻址 | Kafka gate-cmd，按开局快照的会话 | `PlayerPushes`：在线目录里的当前会话 + gate 玩家栅栏；177 → 143 放进一条 `GatePush` | 备战期间换会话时也能收到（修 F2） | 可选（mmorpg 也可按 player_locator 寻址） |
| N3 | battle → scene / match 的出站传输 | Kafka scene-cmd / match-results | 端口；6.2 只记日志；6.3 / 6.4 定传输（Q12、Q13） | 否 | 否 |
| N4 | Agones | 生命周期、分配许可、loop 心跳、排空标签、单元计数 | 不移植；准入闸保留；先开闸后进目录 | 否 | 否 |
| N5 | 空密钥 | dev / test 空密钥跳过验签，`token_signature` 为空 | 密钥任何模式必填，`token_signature` 恒为 64 字节 hex（同 Java gate） | 字段内容不同，形状相同；客户端原样回传 | 否 |
| N6 | 客户端可达地址开关 | `CLIENT_ENDPOINT_REQUIRED` | 不需要：通告地址总有值（PARITY 第 50 行） | 否 | 否 |
| N7 | 观测 | 日志 `metric=` 提取计数 | Micrometer 指标；采样日志保留 | 否 | 否 |
| N8 | 运行模式变量 | `BATTLE_RUN_MODE` | 每个进程自己的 `XM_RUN_MODE` | 否 | 否 |
| N9 | 合法但不收的帧类型 | `shutdown` + 0.1 s 强关 | 解码器立即关 | 断开时序差 ≤ 0.1 s | 否 |
| N10 | 非法包阈值来源 | 环境变量 `GATE_ILLEGAL_PACKET_THRESHOLD`（与 gate 共用） | 配置 `xm.battle.illegal-packet-threshold`，缺省同为 50 | 否 | 否 |
| N11 | 限频窗口 | 整秒 | 单调时钟纳秒窗口（同 gate 那一行） | 边界略宽松 | 否 |
| N12 | 线程 | 单 muduo loop 通管，gRPC 线程阻塞在 `future.get()` | 单逻辑 loop 兼直连 I/O；Dubbo 异步，回复执行器完成 future | 否 | 否 |
| N13 | 节点身份 | etcd node_id + node_uuid | Redis 租约（作用域 0）+ 进程 UUID；目录在 Redis | 否（票据里的值来源不同） | 否 |
| N14 | 计时器防御 | 按 id 重查房间 | 按 id 重查，再比较房间对象身份 | 否 | 否 |
| N15 | 观众版回合帧 | 逐观众序列化 | 每回合序列化一次，共享字节 | 否 | 否 |
| N16 | 停机排空 | 观众的 166 可能被强关吞掉（F1） | 先排空、有界等待后再关 | 停机时观众能收到 166 | 建议 mmorpg 同修（`edge.cpp:91-110`） |
| N17 | 租约丢失 | 无此概念 | 关闸、停发布、不作废房间（Q4） | 否 | 否 |
| N18 | 指纹模式非法值 | 回落 warn | 拒启 | 否 | 否 |
| N19 | 确认补发计数 | 比较墙钟 `now ≥ until` | 计次 17 次（标称行为相同） | 否 | 否 |
| N20 | 在途上限 | 无 | `rpc-max-inflight` 超限时 createBattle 回 NOT_ALLOCATABLE | 否 | 否 |
| N21 | dev 管理接口 | 无（靠 match 建房） | dev / test 专用建房 / 销毁 / 补签 / 观战接口；dev 房间永不结算 | prod 不可用 | 否 |

另外还有照搬并在 PARITY 注明的基线怪癖：B1–B9（§10.2）。

---

## 12 开放问题（每条附推荐答案）

- **Q1 观战的房间侧放 6.2 还是 6.5？**
  推荐 **6.2**：观众名单、AddObserver / RemoveObserver、165、161 / 158 / 166、单槽互斥、`viewer = 0` 裁剪都在房间代码里，与收尾、作废路径绕在一起；6.5 只剩 match 侧（163 / 164、观战索引、开局前清退、跨区 1V1）。
  代价：6.2 体量多一个 M，路线图要把 battle-spectate 的房间侧挪进 6.2。
  不采纳时：接口方法保留，6.2 先回 1003，收尾路径对空观众表是空操作。
- **Q2 B2（全自动房间里合法的 149 立即结算）要不要改？**
  推荐**照搬**并在 PARITY 注明。改成「只在翻转时立即结算」会改变客户端可见的节奏，必须先改 mmorpg、两版同批。
- **Q3 177 → 143 的保序机制？**
  推荐 `GatePush.MessageBatch`（§7.7）。备选是顺序发布，不推荐。
- **Q4 租约丢失后作废房间吗？**
  推荐**不作废**，只关闸、停发布（§7.10）。备选是 fail-closed 全部作废（与 gate 同形）。
- **Q5 确认之后的 Destroy / 停机，能不能提前给玩家解冻？**（向 scene 发一个「作废」事件）
  会把冻结从「≤ 期限」缩短到「立即」，改变可见时序，也要 scene 配合。
  推荐 **6.2 不做**，登记为「mmorpg 待做（可选）」，两版同改。
- **Q6 确认补发收到 scene 的确定性回执后提前停止？**
  Dubbo 有应答，做得到，但基线没有回执。推荐 6.2 照基线补满窗口，是否优化由 6.3 评估。
- **Q7 单节点房间上限 `xm.battle.max-rooms`（满了回 NOT_ALLOCATABLE）？**
  基线靠 Agones 管容量。推荐 **6.2 不加**（连接上限已兜住负载），7.6 部署批次再定。
- **Q8 dev 管理接口（§7.12）是否接受？**
  推荐**接受**：它让 6.2 在 6.4 之前就能端到端验收；只在 dev / test 开放，dev 房间永不结算。
- **Q9 共享件的落点？**
  推荐：限频器挪到 `xm-net` 的 `com.game.net.limit`（xm-net 新增 xm-table 依赖）；票据与密钥策略放 `xm-common` 的 `token` 包；`SceneAssetOpClients` 泛化成 `NodeRpcClients<S>` 留给 6.4 决定。
- **Q10 端口与 group？**
  推荐直连 12000、Dubbo 21200、管理 18112、group `battle-node`。需与 6.4 的 match（调用方）确认。
- **Q11 battle 是否读 `XM_GATE_TOKEN_SECRET` 做「不得与 gate 相同」检查？**
  推荐**进程能看到时就比**（本机切片会导出）；部署侧另由发布门禁（7.6）比对，不强制把 gate 密钥注入 battle。
- **Q12 对局结果的传输（6.4 定）？**
  推荐 Java 自有的 Kafka topic `xm-battle-result-g<代次>`：键 battle_id，规格照 `xm-audit` 的 TopicSpec 写，启动时核对（`arch` §4.5）。
  理由：有两个独立的消费方（6.4 的评分、4.6 的帮会活动），需要持久与重放。
- **Q13 确认与结算的传输（6.3 定）？**
  推荐在 scene 现有的资产 RPC 端口（`xm.scene.asset-rpc-port`，21100）上再导出一个 `SceneBattleService`：group `scene-battle`，`register = false`，按目录直连，`retries = 0`。
  - 请求带目标实例，不符回 NOT_HERE；
  - 确认是异步发出不等回执，靠补发兜底；结算走 6.3 发件箱。
  - 备选：Redis pub/sub `xm:scene-battle:{zone}:{node}`（同样至多一次）。
- **Q14 6.4 补签时，「目录里同号节点的实例 id ≠ 落点记录里的实例 id」是否判房间已死、回 1005？**
  推荐**采纳**：这是 `rbt.go:98-104` 写明的正面证据，客户端能更早拿到 BattleGone。需在 PARITY 登记，可选在 mmorpg 同做。
- **Q15 要不要提供与 Agones 排空标签对等的运维排空（关闸但不作废，打完再下线）？**
  推荐**不做**：用停机（作废）或 GM 签名停机代替；有需要时在 7.6 加。
- **Q16 逻辑线程数？**
  推荐固定为 1（§7.3）。分片放到压测数据出来之后，而且要重新论证 R1 / R3。

---

## 13 测试计划与 robot

构建与运行：`./mvnw -B -pl xm-battle -am test`；涉及 xm-common / xm-net / xm-discovery / xm-gate 的改动各自跑模块测试。真 Redis 的用例加 `-Dxm.it.redis=...`。改了 proto 之后按 `AGENTS.md` §4 做 `clean install`。测试方法名用中文。

### 13.1 纯函数（逐条移植基线 gtest）

| Java 测试 | 移植自 | 要点 |
|---|---|---|
| `BattleTicketsTest` | `cpp/nodes/battle/tests/battle_ticket_test.cpp:44-217`（全部用例） | 签名往返得到 64 位小写 hex；同输入结果确定；改 payload、换密钥都失败；空签名、截短到 63 位、多 1 位都失败；用 gate 密钥签的过不了 battle 校验；参战者 / 观众字段 OK；`empty_identity`；`node_mismatch`；`instance_mismatch`（不同、任一侧为空）；期限 `expire − 1` OK、`expire` 与 `expire + 1` 过期；role 0 / 3 / 42 无效；多个字段都坏时最便宜的那个赢。**另加**：大写 hex 签名必须拒绝；payload 带一个未知字段仍能验过（验签用原字节） |
| `BattleSecretPolicyTest` | 同文件 `BattleTokenSecretStrength*` | 缺失、纯空白 → 任何模式都拒；31 字节、两侧空白夹 24 字节 → 太短；32 字节 OK；与 gate 相同（含两侧空白差异）prod 拒、dev 只 WARN；gate 密钥不可见时不比 |
| `AdmissionGateTest` | `battle_admission_gate_test.cpp:33-88`（7 条） | 初始 NOT_STARTED；open 成功；重复 open 返回 false；close；没 open 就 close 是终态；close 幂等；名字 not_started / open / closed |
| `PushPolicyTest` | `battle_push_policy_test.cpp:33-75` | 四种组合；穷举证明只有「大厅公告 + 无直连」会回落 gate |
| `RoomTableTest` | `battle_room_table_test.cpp`（8 条） | 插入恰好回调一次；重复插入被拒、不回调、保留原房间；null 被拒；移除恰好一次；移除未知 id 不回调；按快照批量移除每间一次；回调里看到的表已更新；没设回调就跳过 |
| `BattleViewsTest` | `room.cpp:1250-1281` | 本人与本人宝宝保留冷却；他人、他人宝宝、怪物清空；buff 原样保留；观众全清；`self_items` 先清空再只给本人；输入快照不被改动 |
| `FingerprintGuardTest` | `room.cpp:408-451` | off 不比；空值不比；request 不符；仅某个快照不符；warn 放行并计数；enforce 拒绝且 `parameters[0]` 逐字相同（含 `request=` 为空串的情形） |
| `BattleResultAssemblerTest` | `room.cpp:1181-1201`；`activity.h:62-81` | 队伍升序、队内升序（无符号）；`winner_team_index` 只在 B 胜时为 1；fled / dead 升序去重；kind = NONE 不回显；kind = 1 回显；**不认识的 kind（如 7）也回显**并走活动通道；`total_rounds`、`finished_at_ms` 取注入的时钟 |
| `ConfirmWindowConstraintTest` | `room.cpp:257-268` | 窗口 ≥ 96 + 60；6.3 / 6.4 落地后改成引用对方常量 |

### 13.2 房间服务（逻辑线程上的组件测试）

**装配**：
- 真引擎 + `MemoryBattleData`（或正式 `ConfigTables`）；
- `ManualBattleScheduler` + `FakeBattleClock`；
- 假直连：按玩家记录写出的帧与关闭事件，带全局序号；
- 记录型的 `LobbyAnnouncer` / `SceneBattleEvents` / `SettlementSink` / `ActivityResultSink` / `BattleResultSink`，带全局序号，用于断言跨端口的先后。

| 测试类 | 用例 |
|---|---|
| `CreateBattleTest` | 幂等：第二次内容不同也回 OK，`battle_id` 回填，**零新出站**、没有新计时器、hooks 不触发。`battle_id = 0` / 没有玩家 → 1005。缺 gate 或 scene 实例 → 1005，且排在指纹之前（同时指纹不符也回 1005）。enforce 指纹不符 → 1006。引擎 `Rejected`（队伍超编、只有一边）→ 1002。签票失败（注入会抛异常的签名器）→ 1003，表为空、`onCreated` 未触发、零出站、零计时器。`deadline_ms` 为 0 或已过去 → 房间期限 = now + 192000，票据 `expire_at_ms` 与确认 `deadline_ms` 都等于它；`deadline_ms > now` 时照用原值。成功：出站序列严格为 `[177(p1), 143(p1), confirm(p1), 177(p2), …]`（无符号升序）；177 与 143 进同一次 `LobbyAnnouncer` 调用；143 `round_index = 1`、`action_deadline_ms = now + 6000`、`self_items` 只有本人、他人冷却为空；177 的 host / port 是通告值，role = 1；`onCreated` 恰好一次 |
| `RoundTimerTest` | 6 s 到期结算：每个参战者一条 139（裁剪过）、观众一条 158；新截止 = 结算时刻 + 6000。全员提交 → 当场结算，**旧窗口原到期时刻不再结算一次**（回合号只加 1）。开挂机造成翻转 → 当场结算，下一窗口 2000 ms。全自动时重复开挂机不结算；关挂机不结算，当前 2 s 窗口不变、下一窗口 6 s。全自动房间里挂机玩家提交合法行动 → 当场结算（钉住 B2）。只剩宝宝（空真）→ 2 s 节奏。结束回合：139 `action_deadline_ms = 0`，`action_order = lastActionOrder()`；随后 150 与结算端口逐人交替（R6），观众 166 FINISHED，关闭，结果事件；`onRemoved` 恰好一次；之后推进时间，没有任何回调执行 |
| `BattleDeadlineTest` | 期限到：没有 139；150 的 outcome 与 `settlement.outcome` 都是 DRAW，经验、金币为 0，`items_consumed` 保留；结算端口收到 DRAW；观众 166 `{ABORTED, DRAW}`；结果事件 DRAW、`winner_team_index = 0`；房间删除。期限只剩 1 ms → 立即收尾。期限与回合窗口同时到期：先执行者生效，另一个查不到房间、什么也不做 |
| `ConfirmResendTest` | 每人 18 条：t = 0 首发，t = 10 … 170 s 补 17 次，t = 180 s 停表；t = 35 s 结束后不再补发；每条带房间期限 |
| `FinishAndVoidTest` | Destroy 不存在的房间 → 空操作。Destroy 已有房间：观众 166 `{ABORTED, ONGOING}`；**参战者没有 150**；没有结算、没有结果事件；关直连；`onRemoved` 一次；计时器全部失效。`abortAll` 删 3 间房，hooks 各一次。「关闸 + abortAll」之后才执行的 createBattle → NOT_ALLOCATABLE，表保持为空。Destroy 后同 id 重建，旧计时器回调不打到新房间（N14） |
| `ClientActionsTest` | 149：1012 / 1005（房间不存在）/ 1005（非参战者、观众）/ 引擎 tip 原样 / 0 改成 1005；成功时应答体为空。140：非成员、房间不存在回空状态；参战者裁剪 + 道具；观众全清。162：1012 / 1005 / 1009；翻转立即结算；全自动时重复开启不结算。165：房间不在、非观众都回成功；观众被移除并关闭；不推 166 |
| `DirectAttachTest` | 角色与名单不符被拒（参战票对观众名单、观众票对参战名单）；重连替换旧连接，旧的被关且没收到帧；旧连接迟到的 detach 不摘新连接；观众挂接后 `onDirectVerified` 推 161（`observer_count` 正确），参战者不推 |
| `IssueTicketTest` | player 为 0 → 1005；房间不在 → 1005；非成员 → 1005；两个名单都有 → 参战者优先；签票失败 → 1003 且无 assignment；成功票的字段正确，且与开局票逐字节相同 |
| `ObserverTest`（Q1 采纳时） | Add：房间不在 1004；observer 为 0 或 gate 实例为空 1005；是参战者 1005；满 20 回 1008（排在幂等之后）；新观众签票失败 1003 且不登记；幂等同会话重推 177（有直连时再推 161）；会话变了关旧直连并重推 177、不推 161；幂等路径签票失败 → 摘除 + 关闭 + 1003。Remove：166 REMOVED + 关闭。158 全员冷却清空 |
| `LobbyAnnouncementTest` | 没有直连时 177、143 作为同一批交给 `LobbyAnnouncer`；有活直连时按序直写、不走 announcer；战斗帧无直连时丢弃并计数 |
| `ThreadOwnershipTest` | 从别的线程调 `BattleRoomService` 的方法 → 断言失败；createBattle 经 Dubbo 实现时提供方线程不阻塞，future 在 `battle-rpc-reply` 上完成 |

### 13.3 直连面（`EmbeddedChannel` 一组 + 真 Netty 回环一组；客户端用测试内编码器或 robot 的连接类）

- **并发上限**：第 N+1 条连接被关，没有回包。
- **握手前**：
  - 发 `ClientRequest` → 被关，无回包，非法包计数不变；
  - 首帧是 `ClientTokenVerifyRequest` → 被关，无回包；
  - 坏校验和 → 被关；
  - 握手超时（注入短时长）→ 被关。
- **握手失败**：五类拒绝串逐字比对（`invalid ticket signature`、`malformed ticket payload`、`ticket rejected: <5 种>`、`battle not found or player not in this battle`）；应答里 `battle_id` 不在线上；之后连接关闭。
- **重复握手**：第二次带垃圾 payload → 成功，且是旧的 battle_id。
- **已验证连接上的闸**：
  - 1025 B 的 `ClientRequest` → 信封 1010；
  - 同号第 4 条 140 / 秒 → 信封 1008（被拒的不占额度）；
  - 号 157 → 信封 1005；149 体解析失败 → 信封 1005；
  - 非法包到 50 断开；阈值 0 不断；握手成功时计数清零；每条新连接有自己的限频器。
- **应答形状**：`id` / `message_id` 回显；成功时体为 0 字节，且没有 `error_message`。
- **顺序**：
  - 单人房里 149 让全员就绪 → 线上依次是 139 → 150 → 149 应答 → FIN；
  - 162 翻转 → 139 → 162 应答；
  - 观众握手 → 应答 → 161；
  - 165 → 应答 → FIN。
- **写缓冲**：对端停止读，越过高水位后连接被关。
- **优雅关闭**：终局包与应答都完整到达之后才收到 FIN；对端不读时 1 s 内被强关。

### 13.4 Dubbo 提供方（`BattleNodeServiceImplTest` + 一条真 Triple 回环）

- 准入 NOT_STARTED / CLOSED → `NOT_ALLOCATABLE`，原因串正确；投递之后、执行之前关闸 → `closed_in_loop`；在途超限 → `overloaded`。
- 业务错误放在 `response` 字节里；`UNSPECIFIED` 从不出现在提供方的输出里。
- 不带 `XM_DUBBO_SECRET` 的调用方被拒（复用现有 `DubboAuth*` 测试的夹具）。

### 13.5 启动与配置（`ApplicationContextRunner`）

- prod 下：缺密钥、密钥太短、与 gate 相同、`max-connections = 0` → 都拒启。
- dev 下：缺密钥仍拒启（N5）；太短、与 gate 相同只 WARN。
- 缺 `XM_DUBBO_SECRET` 拒启；指纹模式非法拒启；`BattleMessageIds` 缺号拒启。
- 四张关键表为空时只打 ERROR、照常启动。

### 13.6 真 Redis（`-Dxm.it.redis`，缺省跳过）

- 租约（作用域 0）与目录的写入、列出、TTL。
- `PlayerPushes.pushAllToPlayer` 的两条消息在订阅端按序到达，且只发布一次。
- dev 接口能按在线目录 / 位置 / scene 目录补全路由。

### 13.7 gate / discovery 回归

- `MessageRoutesTest`：`BattleClientPlayer` 的 12 个方法全部路由到 `unsupported`。
- `ClientDispatcherTest`：大厅上发 140 / 149 / 162 / 165 → 推 23 `{1003}`，不计非法包、不断连。
- `GatePushSubscriberTest`：`MessageBatch` 在同一个会话任务里按序下发；任一条损坏整条丢弃；玩家栅栏对每条都生效；实例不符整条丢弃。

### 13.8 robot

**新增客户端件（xm-robot）**：

| 组件 | 内容 |
|---|---|
| `GameConnection` 参数化 | 可接受的下行类型集合可配，加入 `BattleTokenVerifyResponse` |
| `BattleDirectConnection` | `verifyBattleTicket(payload, signature)` 要求读到的第一帧是应答（同 `robot/pkg/client.go:240-265`）；建连 + 握手预算 10 s（`bdc.go:51`）；参战者握手成功后自动发一条 140（`bdc.go:178-189`）；按消息号计数；能检测服务端 FIN |
| `BattleAdminClient` | 调 `/admin/battle/dev/*`，请求体是 protobuf 二进制，带 `X-Xm-Admin-Token` + `X-Xm-Operator`（同 `xm-robot/src/main/java/com/game/robot/client/TradeAdminClient.java`）；`battle_id` 由 robot 随机生成非 0 正数 |
| 大厅 handler | 收到 177 时记下分配包（参照基线 robot 的 `battle_client_player_notify_battle_assigned.go:14-29`） |
| `RobotOptions` | 新增场景 `BATTLE`、`BATTLE_EDGE`；`--battle-admin-url`（缺省 `http://127.0.0.1:18112`） |

**场景 `battle`**（6.2 版：基线 battle-smoke 去掉 match 的部分；账号 A、B、C；前置：切片带 xm-battle，dev 模式）：

1. **登录**：A、B、C 登录进场。
2. **gate 拒绝**：A 在大厅发 149 → 收到 23 `{1003}`；随后正常请求仍有应答（没断连）。
3. **PVE 建房**：dev create，A 单人，`match_mode = 4`，`battle_config_id = 1`（Dungeon 1，怪物 1、2），固定 seed，`deadline_ms = now + 300000`。
   - 快照数值给高，保证能打赢；routing 留空由接口补全。
   - 期望 `admission = ADMITTED`，`error_message = 0`。
4. **大厅公告**：A 的大厅上**先 177 后 143**（断言 inbox 下标）。
   - 177：role = 1，battle_id 一致，host / port 是 battle 通告地址，`expire_at_ms` = 期限，`token_signature` 匹配 `^[0-9a-f]{64}$`；
   - 143：`round_index = 1`，`action_deadline_ms ∈ (now, now + 6 s]`。
5. **直连**：握手应答是第一帧，`success` 且 battle_id 正确；补拉 140 → 应答 id 回显、message_id = 140，actors 含 A 与怪物，`outcome = ONGOING`。
6. **非法行动**：Submit `action_type = NONE` → 应答带 1005（engine-spec §2.5）。
7. **合法行动**：Submit ATTACK → 应答体为空。单人房全员就绪，立即结算：断言 139 的下标 < 这条应答的下标（R2）；139 的 `action_order` 非空，`state.action_deadline_ms` 是新截止。
8. **同票重连**：用同一张票再建一条连接 → 新连接握手成功，旧连接被服务端关闭且没收到帧。
9. **自动战斗**：新连接上 SetAutoBattle(true) → 当场结算（139 先于 162 应答）；之后相邻 139 的间隔落在 [1.5 s, 3.5 s]，直到 150（`settlement.player_id = A`，`outcome = SIDE_A_WIN`，`total_rounds ≥ 1`，同 `robot/features_battle_smoke.go:82-95` 的判据）；1.5 s 内被服务端 FIN。6.2 的大厅上**不应**收到 150（scene 结算是 6.3）。
10. **终局后重连**：旧票重新握手 → `battle not found or player not in this battle`，然后断开。
11. **PVP 1V1**（A 在 team 0、B 在 team 1，`match_mode = 3`，`battle_config_id = 0`，快照给很高的气血）：
    - A 先 Submit → 不结算；B 再 Submit → 两边同时收到 139；
    - 下一回合只有 A 出手 → 约 6 s 后超时结算（间隔 ≥ 5.5 s）；
    - A 的 140 里，B 的 `skill_cooldown_rounds` 为空，`self_items` 只有 A 的。
12. **强制平局**：建 1V1 房，`deadline_ms = now + 8000`，没人出手 → 约 6 s 时一条 139 → 约 8 s 时两边收到 150 DRAW（经验、金币为 0）→ 期限之后**再没有 139** → FIN。
13. **销毁**：建房，A 直连，dev destroy → A 的直连被 FIN，**没有** 150；原票重新握手失败。
14. **补签**：活着的房间给 A 补签 → role = 1，票与开局票逐字节相同；给 C（非成员）补签 → 1005，无 assignment。
15. **幂等**：同一个 battle_id 再 dev create 一次 → `ADMITTED` 且无错误；2 s 内 A **不会**再收到 177 / 143。
16. **观战**（Q1 采纳时）：A 打 PVE 不开自动，dev add-observer C →
    - C 在大厅收到 177（role = 2）；直连后先应答、再 161（`observer_count = 1`）；
    - A 开自动 → C 每回合收 158，所有 actor 的冷却都为空；
    - 结束时 C 收到 166 `{FINISHED}` 后 FIN；
    - 另起一局 C 发 165 → 成功、FIN、**没有** 166。
17. **指标**：抓 18112，断言 `room_creates{result=ok}`、`handshakes{result=ok}`、`pushes{route=direct}` 有增长。

**场景 `battle-edge`**（负面用例；每条都新建连接）：

| 用例 | 期望 |
|---|---|
| 连上不握手 | 10 ± 1 s 被关（`--slow` 才跑） |
| 握手前发 `ClientRequest` | 立即关，无回包 |
| 首帧发 `ClientTokenVerifyRequest` | 被关，无回包 |
| 签名翻转一个字节 | `invalid ticket signature` 后被关 |
| 用 `deadline_ms = now + 5 s` 的房间的票，等 6 s 再握手 | `ticket rejected: expired`（字段判定先于名单判定） |
| 已验证后发号 157 | 信封 `{message_id 157, id 回显, error 1005}` |
| 1025 B 的请求 | 信封 1010 |
| 1 秒内 4 条 140 | 第 4 条信封 1008 |
| 连续 50 个非法包 | 断开 |
| 已验证后再握手（垃圾 payload） | success + 原 battle_id |
| 坏校验和的帧 | 被关 |

**prod 模式**：`XM_RUN_MODE=prod tools/local/start-slice.sh` 之后，`battle --expect-dev deny`：dev 接口回 403，场景只跑第 2 步。

**后续批次的升级**：
- 6.4 按 `bss.go:307-389` 移植 battle-smoke 的 A 侧：排队 PVE_SOLO → 177 / 143 → 直连 → 补拉 → SetAutoBattle → 150。另移植 features-smoke 的战斗段与 179 补签。
- 6.3 补「打赢 → 任务进度 → 领奖 → 重登」以及大厅上的 150 / 144。
- 6.5 移植 B 侧观战与 `battle_smoke_cross_zone`。
- 输出行沿用 `BATTLE_SMOKE_OK battle_id=… a_turns=… …`。

### 13.9 交付清单（随 6.2 提交）

- **PARITY.md**：
  - 新增行「battle 节点：房间生命周期 / 客户端直连 / 票据 / 推送 / 准入」：Java 模块 xm-battle、xm-common、xm-net、xm-api、xm-discovery、xm-gate；状态「已对齐（部分：确认 / 结算传输 6.3、match 6.4、观战 match 侧 6.5）」；附 N1–N21 与 B1–B9。
  - 更新第 28 行（gate）：战斗直连由 xm-battle 承担，gate 永久拒绝战斗上行。
  - 更新第 50 行：battle 的通告地址 `XM_BATTLE_ADVERTISE_PORT` 已接入。
  - 交付说明写明 mmorpg 侧的状态：已有，基线没有房间生命周期单测。§13.2 的时序断言可登记为「mmorpg 待做（可选）」；N16 建议 mmorpg 同修。改 mmorpg 需用户同意。
- **`docs/design/architecture.md`**：
  - §2 模块表加 xm-battle（端口 12000 / 21200 / 18112）；
  - 新增「battle 节点」小节（线程、直连面、Dubbo、准入、出站端口）；
  - §4.3 写上 `MessageBatch`；§5 线程模型加一条；§11 的抓取表与指标类名加行。
- **`docs/porting/roadmap.md:82`**：完成后打勾，写上提交号；Q1 采纳时同步调整 6.2 / 6.5 的盘点 id。
- **`docs/reference/mmorpg-client-contract-battle.md`**（建议新增）：把 §3、§5、§2.1–§2.4、§8.4 的客户端可见部分整理成参考文档，供 robot 与后续批次引用。
- **`tools/local/start-slice.sh` / `stop-slice.sh`**：服务清单与密钥生成（§7.2）。
- **`docs/design/tech-stack.md`**：没有新依赖，不改。
