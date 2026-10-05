# 观战（match 侧）与跨区 1V1（批次 6.5）移植统一规格

> **基线**：mmorpg `D:\work\mmorpg` @ `26ceb70ca`（稀疏克隆）。本稿用到的 `go/match/**`、`go/client_rpc_router/**`、`proto/{match,battle}`、`cpp/nodes/battle/logic`、
> `robot/*.go`、`robot/etc/*.yaml`、`robot/logic/handler`、`docs/design/**`、`PROGRESS.md` 都已检出。
> **稀疏克隆里缺的目录**（凡依赖它们的结论都标了「推导」）：`robot/config/`（`BattleSmokeConfig.validate` 不可读，只能引 `bsc.go:63-64` 的注释「配置层把非 "1v1" 拒掉」）、
> `robot/generated/`、`bin/etc/`。Unity 客户端是另一份稀疏克隆 `D:\work\mmorpg-client` @ `a8577c7`：`Assets/Scripts/{Game/Battle,UI/Ugui/Battle,App}` 已检出，
> **`tools/` 不在**（双播放器验收脚本 `tools/run_crosszone_pair.ps1` 不可读，只读了 `DevAutoPilot.cs`）。
> **Java**：HEAD `aa8b5b5` 加工作区，行号按当前工作区。6.2 的 xm-battle 在工作区（未提交），**房间侧观战已实现**（`BRS.java:320-424`，`bn-spec` Q1 已采纳）；
> 6.3 / 6.4 / 5.4 只有规格，本稿以规格为准；xm-match 还没有代码。
> 本稿由三份分区稿合并（match 侧观战盘点、跨区 1V1 盘点、Java 落地映射），分歧都回到代码核对后在 §0.4 裁决。只读编写，只改本文件。

**路径缩写（基线）**

| 缩写 | 路径 |
|---|---|
| `wb.go` / `lw.go` / `sp.go` | `go/match/internal/logic/` 下的 `watchbattlelogic.go` / `listwatchablebattleslogic.go` / `spectate.go` |
| `gather.go` / `queue.go` / `join.go` / `matcher.go` | 同目录（`join.go` = `joinqueuelogic.go`） |
| `sp_test.go` / `wbcw_test.go` / `gsi_test.go` / `cz_test.go` | 同目录 `spectate_test.go` / `watchbattle_create_window_test.go` / `gather_spectate_index_test.go` / `crosszone_test.go` |
| `pc.go` / `errors.go` / `metrics.go` | `go/match/internal/playercontract/playercontract.go`、`internal/constants/errors.go`、`internal/metrics/metrics.go` |
| `yaml` | `go/match/etc/match_service.yaml` |
| `fwd.go` / `rcfg.go` | `go/client_rpc_router/internal/logic/forwardlogic.go`、`internal/config/config.go` |
| `ms.proto` / `bn.proto` / `pb.proto` / `bd.proto` | `proto/match/match_service.proto`、`proto/battle/battle_node.proto`、`proto/battle/player_battle.proto`、`proto/battle/battle_data.proto` |
| `room.cpp` | `cpp/nodes/battle/logic/battle_room_manager.cpp` |
| `bss.go` / `bsc.go` / `bsc.yaml` / `bdc.go` / `msr.go` | `robot/battle_smoke_scenario.go`、`robot/battle_smoke_cross_zone_scenario.go`、`robot/etc/battle_smoke_cross_zone.yaml`、`robot/battle_direct_conn.go`、`robot/logic/handler/match_service_responses.go` |
| `tbs.md` / `czm.md` | `docs/design/turn-based-battle-server.md`、`docs/design/cross-zone-matchmaking.md` |
| `SC.cs` / `SP.cs` / `BC.cs` / `DAP.cs` | 客户端 `Assets/Scripts/Game/Battle/SpectateClient.cs`、`Assets/Scripts/UI/Ugui/Battle/SpectatePanel.cs`、`Assets/Scripts/Game/Battle/BattleClient.cs`、`Assets/Scripts/App/DevAutoPilot.cs` |

**路径缩写（Java）**：`m-spec` / `bn-spec` / `sb-spec` / `zt-spec` / `ci-spec` = `docs/porting/` 下的 `match-spec.md`（6.4）/ `battle-node-spec.md`（6.2）/ `scene-battle-spec.md`（6.3）/
`zone-travel-spec.md`（5.4）/ `deploy-ci-spec.md`（7.1）；`arch` = `docs/design/architecture.md`；`BRS.java` = `xm-battle/src/main/java/com/game/battle/room/BattleRoomServiceImpl.java`。

---

## 0 概览与范围（与 6.2 / 6.4 / 5.4 的边界）

### 0.1 结论速览

- **match 侧观战只有两个客户端号**：163 WatchBattle、164 ListWatchableBattles。161 / 158 / 166 / 165 / 177(OBSERVER) 都由 battle 发出或接收，6.2 已实现。
  match 对 battle 只调 `addObserver` / `removeObserver` 两个内部方法（`xm-api/src/main/java/com/game/api/BattleNodeService.java:54`、`:57`）。
- **客户端契约逐字节照搬**：163 的判定顺序、16004 / 16014–16019 与 9 条 `parameters[0]` 中文串（半角逗号）、成功应答只带 `battle_id`、164 的条数收口 / 排序 / 懒剔除不回填、
  `BattleWatchSummary` 的字段来源、随机选场两轮、换场与「重看同一场」的区别、「先观战后排队允许、先排队后观战拒绝」的不对称互斥、开局前清退推 166 REMOVED。
- **Java 落地**：xm-match 新包 `com.game.match.spectate`，不新增进程、不新增第三方依赖（`tech-stack.md` 不改）。
  - 复用 6.4 的落点记录 `xm:{match}:battle:<id>`（同时充当观战记录）、`TicketStore`、`GatherHooks.beforePrepare / onStarted`、`NodeRpcClients<BattleNodeService>`；
    复用 6.3 的 `BattleLockReader` 与在线目录 `xm:presence:<pid>`。
  - 新增两个键 `xm:{match}:watching:<pid>`（观战标记）、`xm:{match}:watchable`（可观战索引），与票据同 tag。基线靠「先查后写 + 事后复查」拼出来的几处改成单段 Lua。
  - 观众 RPC 按落点记录里的地址**直拨**，判死规则与 6.4 的 179 共用（抽出 `placement.PlacementDialer`）。
  - 163 跑在虚拟线程上（在途上限 128、4.5 s 预算），164 跑在 `match-worker` 上。
- **跨区 1V1 不需要新的生产代码路径**：6.4 的排队池全局、6.2 的 battle 池全局、6.3 / 6.4 的 scene 定位按 (zone, 节点号) 区分、6.2 的推送按带 zone 的在线目录寻址，
  组合起来天然支持跨区。6.5 交付的是不变量清单 Z1–Z12（每条有测试钉住）、「同号节点跨 zone 碰撞」的单测 / 组件测试、`XM_ZONES=2` 切片上的 robot 场景 `battle-cross-zone`、PARITY 登记。
- **有意差异**（§8）：客户端可见的都只出现在故障、竞态、过载或很窄的时间窗里。两处基线的「正常路径」怪癖（ready 残留回 16014、已结束的战斗留在列表里）**本批照搬**，
  登记为「两版同改候选」（§8.4），不在 Java 单边改客户端可见行为。

### 0.2 覆盖的盘点条目

| id | 盘点行 | 本稿 | 说明 |
|---|---|---|---|
| match-spectate | `inventory/scene-manager-match.md:262-272` | 全部（§1、§3、§4） | 163 / 164、观战标记、可观战索引、开局前清退 |
| battle-spectate | `inventory/combat.md:257-267` | 只登记（房间侧已在 6.2，`bn-spec` Q1 :1500-1503） | 6.5 只调用 6.2 的两个方法；`combat.md:264` 的 java 列随 6.5 改为 done |
| robot-battle-smoke（B 侧） | `inventory/contract-robot.md:409-419` | §10.7 | 6.4 已做 A 侧（`m-spec` §15.5），6.5 追加观战段 |
| robot-battle-smoke-cross-zone | `inventory/contract-robot.md:421-431` | §2、§10.8 | Java 场景 `battle-cross-zone` |
| svc-match-service-spectate-ticket（观战段） | `inventory/contract-robot.md:285-294` | §3 | 补签段归 6.4 |

### 0.3 与相邻批次的边界

| 批次 | 6.5 依赖它的 | 6.5 交给它 / 要它改的 |
|---|---|---|
| **6.2 battle**（工作区已实现房间侧） | `addObserver` 的判定顺序与码（`BRS.java:320-366`）、幂等分支（`:368-401`，签不出票时摘除 + 关直连 + 1003）、`removeObserver` 推 166 REMOVED（`:405-424`）、观众上限 20（`RoomConstants.java:33`）、177 有活直连时直写、否则按在线目录经 gate 回落（`BRS.java:796-800`；`PresenceLobbyAnnouncer.java`；`bn-spec` N2）、161 随直连握手下发、165 白名单 | `DevRoutingResolver.gateRouting`（`xm-battle/.../admin/DevRoutingResolver.java:220-227`）挪到 xm-discovery 的 `BattleRoutings.gatePart`，观战与 dev 接口共用（§4.11）；dev `add-observer` 不写 match 标记（Q14） |
| **6.3 scene** | `BattleLockReader.exists`（16015）；`BattleRouting.zone_id` 由 scene 填自己的 zone；确认事件按 `(zone_id, scene_node_id)` 寻址、实例不符回落定位器（`sb-spec` §7.16 :934-935）；结算首投 / 重投经 `SceneAssetLocator` 按位置记录的 zone 解析（`sb-spec` §7.15 :918）；scene 应用结算后经大厅再推一份 150（`sb-spec:333`） | 跨区 robot 的结算断言（§10.8 Z7）依赖它 |
| **6.4 match** | xm-match 进程、163 / 164 已路由到 group match（`m-spec` §9.2）；落点记录 `BattlePlacement`（`m-spec` §4.3 :468-509）；`GatherHooks`（第 2.5 步 / 第 5 步，`m-spec` §9.6 :1048、:1057）；`MatchBudgets`；`NodeRpcClients<BattleNodeService>`；179 的「建连失败 + 同号换实例才判死」（M16）；6.4 期间 163 回 in-band 1006、164 回空列表（M22） | 换掉 163 / 164 的临时处理器、关闭 M22；`created_at_ms` 取 Redis `TIME`；`player_names` 按成员顺序；163 / 164 改为不在 Dubbo 线程上当场回（改 `m-spec` §8.1 :837-841、§9.3 :966、§9.9 :1119）；从 `BattleTicketReissue` 抽出 `PlacementDialer`（§4.11） |
| **5.4 跨 zone** | `XM_ZONES=2` 本机切片（`zt-spec` §5.13 :821-839）：xm-gate-z2（11010 / 18123，节点号也会是 1）、xm-scene-z2（21010 / 21110 / 18115）、gateway 双区播种；robot 选项 `--visit-zone`（`zt-spec` §11.11 :1201） | §5.13 的「共用」行（`zt-spec:834`，**已含 battle**）只需加 xm-match；`--visit-zone` 的帮助文本扩成「另一个区」（Q16）。**不依赖** 226 / 124、GO-5、X16 |
| 5.1 / 5.2 / 5.3 / 5.5 | 无（观众没有战斗锁、不冻结，换图 / 交接 / 镜像 / 排空都不受观战影响，同基线） | 无 |
| 7.1 CI | 整栈冒烟分期（`ci-spec` §4.5 :736-760） | 第三期之后加 `--profile two-zones` 跑 `battle-cross-zone`（Q22） |
| 7.2 数据运维 | 无（观众没有锁，GM 回档等不被观战挡住，同基线） | 无 |

### 0.4 分区稿分歧与裁决（都回到代码或现状核对过）

| # | 分歧 | 分区稿说法 | 裁决与依据 |
|---|---|---|---|
| 1 | 163 遇到 ready 残留票（B-s1） | ① Java 自愈放行；③ 照搬 16014 | **照搬**（BW1），登记两版同改候选（§8.4 C1）。这是正常路径上客户端可见的码（打完一局 60 s 内观战），没有 Java 模型上的理由，单边改会让两版在正常路径上分叉；同类先例 `m-spec` Q5（回合打满阈值） |
| 2 | 已结束的战斗主动出索引（B-s2） | ① 结果消费者打 `e = 1` 并 ZREM；③ 不做 | **6.5 不做**（BW7），登记两版同改候选 C2，做法写进 §8.4 备忘。列表内容是客户端可见的；活动局与作废局本来就没有结果事件给 match，做了也只覆盖一部分 |
| 3 | AddObserver 结局不明（超时 / 已发出后断开） | ① 照基线删标记、不补发 Remove；③ 保留标记 | **保留标记、不补发 Remove**（W4）。标记只是「可能在观战」的提示：多留的代价最多是一次幂等的 RemoveObserver；删掉则下一次开局清退会漏掉这个可能已登记的「幽灵观众」。补发 Remove 不可靠（可能先于在途的 Add 到达） |
| 4 | 复查时读战斗锁出错 | ① 改回 16004；③「读失败只记日志」 | **照搬基线**：读票出错只记日志、按无票继续；读锁出错按「有锁」处理（`pc.go:113-121` 回 `(true, err)`，`wb.go:218-222` 直接用它），自我清退并回 16014（BW2）。③ 的写法与基线不符；① 是只在 Redis 故障时可见的改动，收益小，不做 |
| 5 | 163 的执行器 | ① `match-worker` 带 4500 ms 截止；③ 虚拟线程 + 在途上限 | **虚拟线程 + 在途上限 128**（W10、Q2）。163 最坏串两跳 3 s 的 RPC，放在 16 线程的 `match-worker` 上，一个慢 battle 节点就能把 157 / 148 / 153 挤成过载；与 6.4 gather 同一执行模式 |
| 6 | 复查命中后的自我清退 RemoveObserver | ① 同步，预算耗尽才异步；③ 一律异步 | **一律异步、不等**：应答不依赖它的结果（基线 `removeObserver` 失败也只记日志，`sp.go:374-390`），持票 / 持锁的玩家此后发不出能与之竞争的 163（入口第 3 / 5 步就拒） |
| 7 | 可观战索引的键名 | ① `xm:{match}:spectate:active`；③ `xm:{match}:watchable` | **`xm:{match}:watchable`** |
| 8 | 随机选场 | ① Lua 只在未过期成员里随机；③ 全集随机下标、Java 侧重抽 | **按分数过滤**（W8）：Java 的兜底清扫是 10 s 一轮（基线 500 ms），读路径必须自己挡住过期成员；分布与基线在存活成员上相同 |
| 9 | 清扫间隔 | ① 5 s；③ 10 s | **10 s、可配**：读路径已按分数过滤，间隔只影响残留成员的数量 |
| 10 | 跨区 robot 的第二个区参数 | ② 新增 `--zone-b`；③ 复用 5.4 的 `--visit-zone` | **复用 `--visit-zone`**（Q16），不增加同义选项；5.4 没合入时由 6.5 按 `zt-spec` §11.11 的名字与语义加 |
| 11 | 跨区 robot 要不要带观众 | ② 不带（归观战部分）；③ 带（zone 2 的 C 观战） | **带**（Q18）：Java 观众路由取带 zone 的在线目录，zone 2 的 gate 节点号也是 1，这正是同号碰撞要验的路径；代价是一个「开自动之前」的屏障 |
| 12 | 跨区 robot 怎么证明结算回到了各自 zone 的 scene | ② 等大厅 150 + 再打第二局；③ 各发 157 PVE_SOLO 后发 148 取消 | **②**：PVE_SOLO 不入队、即时开局，148 取消不了（`m-spec` §2.3 matched 之后静默成功），③ 的步骤不成立；第二局同时覆盖基线教训「0 血带入、ready 残留、迟到的旧 150」（`czm.md:281`） |
| 13 | Go robot 跨版本验收 | ② 暂缓；③ 纳入验收 | **可选、不阻塞**（Q23）：本机没有 Go；在 GitHub Actions 上作为可选 job，7.1b 之后再加 |
| 14 | 引用更正 | — | ② 的「`PROGRESS.md:4985` 把跨区冒烟列为待人工回归」不对（那一行是组队收口）；实情见 §2.6。③ 勘误 1 的 `inventory/scene-manager-match.md:289` 应为 `contract-robot.md:289`。① 的「`tbs.md:276` 不变量 7」应为 `:274`（`:275` 是不变量 8）。③ 的 `match_service.proto:164-167`、`:39-40` 是 Java 同步副本的行号，基线是 `ms.proto:161-164`、`:36-37`。`BattleUiStyle.cs:19-27` 应为 `:28` |

### 0.5 实施顺序与前置

6.2（房间侧已在工作区）→ 6.4（xm-match）→ 6.3（scene 备战 / 锁 / 结算）→ **6.5**。

- 纯函数与组件测试只依赖 6.4 的代码加替身，可以与 6.3 并行。
- `battle-smoke` 观战段的端到端要等 6.3：参战者要真实冻结，打完要能结算，才能再排队（§10.7 S11）。
- `battle-cross-zone` 还要等 5.4 §5.13 的 `XM_ZONES=2` 切片。X16（建角取会话 zone）只影响一条可选断言（角色列表里的 zone_id），不是硬依赖（Q26）。

---

## 1 基线观战（匹配侧）

### 1.1 协议面与归属

消息号来自 `xm-proto/src/main/resources/contract/message_id.txt`（第 N+1 行是 N 号；基线 `proto/message_id.txt:159-167`、`:178`）。

| 号 | 名 | 方向 / 通道 | 形状 | 归属 |
|---|---|---|---|---|
| 163 | MatchServiceWatchBattle | C2S，经 gate → match | `WatchBattleRequest{player_id=1, battle_id=2}` → `WatchBattleResponse{battle_id=1, error_message=2}`（`ms.proto:156-164`；Java 副本 `:159-167`） | **6.5** |
| 164 | MatchServiceListWatchableBattles | C2S，经 gate → match | `ListWatchableBattlesRequest{player_id=1, limit=2}` → `{repeated BattleWatchSummary battles=1}`（`ms.proto:166-173`） | **6.5** |
| — | `BattleWatchSummary` | 列表条目 | `{battle_id=1, mode=2 (MatchMode), battle_config_id=3, repeated player_names=4, created_at_ms=5}`（`ms.proto:175-182`，注释「摘要在开局时刻生成，不含实时回合数」） | 6.5 |
| — | `SpectateBattleRecord` | 只在服务端内部，Redis 值 | `{summary=1, battle_node_id=2}`（`ms.proto:184-188`）；Java 由 6.4 的 `BattlePlacement` 代替（§4.2） | 6.4 / 6.5 |
| 177 | BattleClientPlayerNotifyBattleAssigned | S2C 大厅公告 | `role = 2 OBSERVER`，`expire_at_ms` = 房间作废期限（`pb.proto:161-171`） | 6.2 |
| 161 / 158 / 166 | 观战首帧 / 每回合 / 结束 | S2C，**只走直连** | `SpectateStateS2C{state, observer_count（含收信者）}`、`TurnResultS2C`、`SpectateEndS2C{battle_id, outcome, reason}`，reason 取 1 FINISHED、2 ABORTED、3 REMOVED（`pb.proto:66-83`） | 6.2 |
| 165 | BattleClientPlayerStopWatchBattle | C2S，只走直连 | 幂等成功，**不推 166，也不碰 match 的标记**（`room.cpp:931-967`） | 6.2 |
| 160 / 159 | BattleNodeAddObserver / RemoveObserver | match → battle 内部 | `AddObserverRequest{battle_id, observer_player_id, routing, observer_name}` → `{error_message}`；`RemoveObserverRequest{battle_id, observer_player_id, reason}` → `Empty`（`bn.proto:47-68`） | 契约 6.2，调用方 6.5 |

- 身份：基线优先取 session 里的 player_id，**session 里为 0 时回落到请求体**（`queue.go:199-208`）；164 根本不看身份（`lw.go:34-81`）。
- 限频：MessageLimiter 表里没有 163 / 164，按缺省每会话每号每秒 3 条，超频回 1008（`m-spec` §1.4 :190-191）。
- 应答：163 成功时**不带 `error_message`**，只带 `battle_id`（`wb.go:235`）；164 没有错误字段，读索引出错时回 gRPC 错误（`lw.go:47-51`）→ 路由服回信封 1003（`fwd.go:170-184`）。

### 1.2 存储与生命周期

三类键都在 `MatchRedis`，只由 match 读写（`tbs.md:274` 不变量 7）：

| 键 | 类型 / TTL | 写者与时机 | 出处 |
|---|---|---|---|
| `spectate:battle:{id}` | STRING = `SpectateBattleRecord` pb，TTL = 300 + 60 = **360 s** | gather **建房之前**预写（第 4.1 步，fail-closed）→ 节点准入拒绝（D82）换节点时改写 → 成功后同值补写（第 6 步）→ 确认没建成时在补偿之后 DEL；`create_failed_room_alive` 保留。与 179 补签共用 | `sp.go:51-59`、`:106-145`、`:163-176`、`:196-202`；`gather.go:299-321`、`:334-343`、`:387-389` |
| `spectate:battles:active` | ZSET，member = battle_id 十进制，score = `created_at_ms`；成员**没有 TTL** | 开局成功、补写记录**之后**才 ZADD（尽力而为，失败只是不进列表） | `sp.go:178-191`；顺序不变量 `sp.go:156-162` |
| `spectate:watching:{pid}` | STRING = battle_id 十进制，TTL 360 s | 163 用 SETNX+EX 抢占，抢到之后才发 AddObserver；AddObserver 失败就 DEL 回滚；清退时 DEL | `wb.go:187-203`、`:237`；`sp.go:310-315` |

- `created_at_ms` 取在全员 Prepare 之后、写记录之前（`gather.go:286`），比 177 的 `expire_at_ms`（`gather.go:219` 的 deadline）晚 Prepare 那段时间。时钟是写记录那个 match 实例的本机时钟（`wb.go:302-305`）。
- `player_names` = 已冻结成员的快照名，顺序 = gather 成员顺序（`gather.go:281-285`），不含怪物。
- **过期分界** `staleBefore = now − 360 s`（`sp.go:230-234`）：score 早于它的成员一定已经收尾。
- **活跃集合成员的三条清理路**（`sp.go:28-29`）：AddObserver 回 1004 时懒剔除（`wb.go:238-249`）；读到过期 score 或记录缺失时现场剔除（`sp.go:236-274`、`lw.go:55-77`）；
  matcher 每轮 `ZREMRANGEBYSCORE 0 staleBefore`（`matcher.go:201-204`、`sp.go:392-408`），每个实例每 500 ms 一次（`yaml:49`）。
- **battle 正常打完不碰 Redis**：只推 150 / 166 并发 BattleResultEvent（`room.cpp:1176-1210`）。所以活跃集合与记录会保留**已结束**的战斗，直到被懒剔除或满 360 s（B-s2）。

### 1.3 163 的内部流程

判定顺序与客户端看到的码见 §3.1；这里只写选场循环的内部（`wb.go:141-261`）。随机模式（battle_id = 0）最多 2 轮，指定模式 1 轮（`wb.go:143-147`）。每一轮：

1. **随机**：`pickRandomBattle`：ZCARD，取 `rand.Intn(n)` 作下标，`ZRANGE idx idx WITHSCORES`。非法成员 → ZREM 后重挑；过期成员 → DEL 记录 + ZREM 后重挑；每次调用**最多 3 挑**；
   出错 → 16004；挑不到 → `break` → 16017（`sp.go:236-274`、`wb.go:154-161`）。随机挑中的成员视为已公开（`published = true`）。
2. **指定**：读记录**之前**先取 `checkedAtMs = now` 与 `published = ZSCORE 命中`；ZSCORE 出错按「未公开」处理（`wb.go:162-165`、`:320-331`）。
3. 读记录出错（含反序列化失败）→ 16004（`wb.go:167-170`；B-s7）。
4. **记录不存在**：`published` → 只 ZREM 残留成员，**不 DEL** 记录键（GET 之后才预写的记录不能删）；随机 → 下一轮；指定 → 16018 `该战斗不存在或已结束`（`wb.go:171-185`；`wbcw_test.go:175`）。
5. **SETNX+EX 标记**（值 = battle_id，TTL 360 s）：出错 → 16004；没抢到（只可能是并发 163）→ 16016。抢占之前有测试钩子 `beforeAcquireWatchingHook`（`wb.go:187-203`、`queue.go:188-193`）。
6. **AddObserver**：按记录里的 `battle_node_id` 从 etcd 镜像找地址（`EndpointOfNode`），超时 3 s（`sp.go:349-372`、`:33-36`）。观众路由 `{session_id, gate_node_id, gate_instance_id, zone_id}`，
   zone 取自位置记录（读失败或没有记录时为 0），**scene 字段留 0**（`wb.go:133-139`、`:269-281`；`tbs.md:272` 不变量 6：观众收不到结算）；`observer_name = session.Account`（`wb.go:204-205`）。
   - **成功 → 复查**：再读票与锁。命中 → `RemoveObserver(reason = concurrent_queue)` + DEL 标记 → 16014。读票出错只记日志、按无票；读锁出错 → `IsBattleLocked` 回 true → 自我清退并回 16014（`wb.go:206-231`；B-s3）。
   - 否则回 `{battle_id}`（`wb.go:232-235`）。
   - **失败**：先 DEL 标记（`wb.go:237`）。回 1004：若 `roomMayBeCreating`（未公开，且 `checkedAtMs − created_at_ms ≤ 22.2 s`；`created_at_ms = 0` 按窗口外）→ 不剔除，回 16018 `该战斗不存在或已结束`；
     窗口外 → DEL 记录 + ZREM，随机 → 下一轮，指定 → 同一 16018（`wb.go:238-254`、`:306-318`）。其它失败（找不到节点、RPC 失败或超时、1005 / 1008 / 1003）→ 16018 `该战斗当前无法观战`，
     **随机模式也不换场**，索引不动（`wb.go:255-260`；B-s8）。

**建房窗口的由来**：活动开局（帮会历练）的 battle_id 在 gather 之前就已发给 guild、在历练大厅公开（`sp.go:149-153`）；D82 改写记录时，读到的可能是首选节点的旧记录（`wb.go:283-305`）。
`gatherCreateStageWorst = 2 × (6.1 s + 5 s) = 22.2 s`（`queue.go:332-342`）。

### 1.4 164 的内部流程（`lw.go:34-81`）

1. 不做身份检查和互斥检查，排队中也能浏览（`lw.go:34-37`）。
2. `limit`：0 → 20；大于 50 → 50（`lw.go:16-17`、`:39-45`）。
3. `ZREVRANGE active 0 limit−1 WITHSCORES`，出错回 gRPC 错误（`lw.go:47-51`；`sp_test.go:854`）。
4. 逐条：非法成员 → ZREM、跳过；score 早于 staleBefore → DEL 记录 + ZREM、跳过；读记录出错或损坏 → **跳过、不剔除**；记录不在或 summary 为空 → DEL + ZREM、跳过；其它 → 原样追加 `record.summary`（`lw.go:55-79`）。
5. **不回填**：剔除之后列表可以短于 limit。
6. 排序：score 降序；同分时按 member 字符串逆字典序（Redis 语义），两版都在 Redis 里排，天然一致。

### 1.5 互斥与清退（`tbs.md:303` D11；`tbs.md:275` 不变量 8）

**矩阵**：

| 玩家当前状态 → 请求 | 163 WatchBattle | 157 JoinQueue / 152、151 切磋 / 211 整队 / 活动开战 |
|---|---|---|
| 持票（queued / matched / ready） | **16014** | 按各自规则（6.4） |
| 持战斗锁、无票（切磋参战者、ready 已过期的长局） | **16015** | 16000 等（6.4） |
| 正在观战（有标记） | 不拒绝，先清退旧场（§3.4） | **请求时不检查、不清退**，继续看；**进入 gather 时**才清退 |

所以「先观战、后排队」允许（排队期间继续看），「先排队、后观战」拒绝。

**开局清退**（`gather.go:228-234` → `sp.go:276-308`）：

- 五个入口（凑单、PVE_SOLO、切磋、整队、活动）都经过同一步（`gather.go:102-158`），在第 2.5 步、任何 PrepareBattle 之前，按成员顺序**串行**清退，`reason = "enter_gather"`。
- 每人：GET 标记，出错 → 只记日志、**标记不删**、返回；值非法 → DEL；读记录出错 → 记日志、不发 RPC、DEL；记录不在 → DEL；否则 `RemoveObserver(节点, battle, pid, reason)`（3 s，失败只记日志）然后 DEL（`sp.go:374-390`）。
- **尽力而为**：任何失败都不阻断开局（`sp.go:276-281`）；gather 随后失败，观众也已被摘掉、不恢复（`sp_test.go:867`）。
- battle 侧：有活直连 → 推 166 `{battle_id, ONGOING, REMOVED}` → 摘除 → 关直连（先 flush）；**没有直连 → 166 丢弃，不回落大厅**（`room.cpp:897-929`）。
- 客户端兜底：RemoveObserver 丢了或 166 晚到时，客户端收到参战 177 会把直连改服务新的一局（Superseded），观战状态机收回 None（`SC.cs:339-368`）；
  参战侧对「迟到的 OBSERVER 分配包」也有兜底（`BC.cs:684-693`）。
- 时间预算：matched 票 TTL 公式里每人计 `removeObserverTimeout 3 s`（`queue.go:344-380`；1 / 2 / 5 / 10 人 → 42 / 48 / 66 / 96 s）。**这个 3 s 不能改大**。

**自我清退**（复查，`wb.go:206-231`）：关掉「入口检查 → 写标记」之间的 TOCTOU 窗口。并发的 gather（尤其 PVE_SOLO 即时开局）可能在本次 163 写标记之前就已做完清退、读不到标记。
RemoveObserver 按 battle_id 定位房间，不会误伤玩家在别处的参战直连。

### 1.6 battle 回给 match 的码与 match 的映射

| battle 码 | 含义 | 出处 | match 的处理 |
|---|---|---|---|
| 1004 | 房间不在（懒剔除信号，不能换码） | `room.cpp:780-788`；`BRS.java:325-330` | 「房间不存在」分支（`sp.go:368-370`） |
| 1005 | observer 为 0、`gate_instance_id` 为空，或观众是参战者 | `room.cpp:793-810` | 16018 `该战斗当前无法观战` |
| 1008 | 观众已满 20 人（排在幂等判定之后） | `room.cpp:865-871`；`room.cpp:64` | 同上 |
| 1003 | 签不出票（新观众不登记；幂等分支摘除 + 关直连） | `room.cpp:812-838`、`:875-883` | 同上 |
| 传输失败 / 超时 / 找不到节点 | — | `sp.go:355-367` | 同上 |

### 1.7 时限

| 项 | 值 | 出处 |
|---|---|---|
| 记录 / 标记 TTL、过期分界 | 360 s（300 + 60） | `sp.go:51-59`、`:230-234`；`yaml:57` |
| AddObserver / RemoveObserver 超时 | 3 s / 3 s | `sp.go:32-36` |
| 记录写入最坏 | 2 次 × 3 s + 0.1 s = 6.1 s | `sp.go:61-83` |
| 建房窗口 | 22.2 s | `queue.go:332-342` |
| 随机选场 | 每次 `pickRandomBattle` 最多 3 挑；外层最多 2 轮 | `sp.go:240-274`；`wb.go:143-147` |
| 列表条数 | 缺省 20，上限 50 | `lw.go:16-17` |
| 过期成员兜底清理 | 每个实例每 500 ms（matcher 轮） | `matcher.go:201-204`；`yaml:49` |
| 单房间观众上限 | 20 | `room.cpp:64` |
| match 服务端超时 / 路由转发超时 | 5000 ms / 5000 ms；163 同步链最坏 > 4 s（`yaml:138` 注释） | `yaml:3`；`rcfg.go:29` |
| ready 票据残留 | 60 s（影响 16014，B-s1） | `yaml:72` |
| 客户端首帧超时 | 15 s（之后自行收回 None；直连就绪时发 165） | `SC.cs:50`、`:118-138` |
| robot 观战各步超时 | 等 177 与等 161 各 15 s；等 166 120 s | `bss.go:54-60` |

### 1.8 基线 robot：`battle_smoke` 的观战段

- 账号 robot_9001 = A（参战），robot_9002 = B（观战）（`bss.go:47-52`）。
- A：157 PVE_SOLO → 143 → **立刻建直连**（屏障期间回合超时照样结算，回合帧只走直连）→ 阻塞在「观战就绪屏障」上，暂不开自动（`bss.go:70-77`、`:304-351`）。
- B：等 A 开战 → 163(battle_id = 0) → 等 177，断言 `role == OBSERVER`、battle_id 等于 A 的 → 直连 → 等 161，断言计数来自直连 → 放行屏障（`bss.go:180-233`）。
  「全服只有 A 这一场」是这条随机观战断言的前提（`bss.go:183-184`）。
- B 等 166：reason 不是 FINISHED 只打 warn → 断言观战回合 ≥ 1，且直连上 `spectate_turns ≥ 1`、`spectate_ends ≥ 1`（`bss.go:235-260`）。
- 输出 `BATTLE_SMOKE_OK battle_id=… a_turns=… b_spectate_turns=… a_direct_turns=… b_direct_spectate_turns=…`，失败 `BATTLE_SMOKE_FAIL step=… reason=…`，退出码 0 / 1（`bss.go:17-21`、`:277-280`）。
- 163 的应答只打日志、不断言（`msr.go:49-67`）。**没有任何场景发 164**，也没有注册 164 的 handler（`msr.go:22-26`）。

### 1.9 基线缺陷与怪癖

| # | 现象 | 出处 | 客户端可见？ | Java |
|---|---|---|---|---|
| B-s1 | 163 对**任何**票据都回 16014，包括已打完那局残留的 ready 票（60 s）。JoinQueue 遇到「ready 且无锁」会自愈（`join.go:234-246`），163 没有 | `wb.go:70-79` | **是** | 照搬 BW1；两版同改候选 C1 |
| B-s2 | 已结束的战斗留在活跃集合与列表里最长 360 s。随机两轮都挑中已结束的场时，即使有正在打的战斗也回 16017 | `room.cpp:1176-1210`；`wb.go:238-249` | **是** | 照搬 BW7；候选 C2 |
| B-s3 | 复查读锁出错 → 按有锁自我清退并回 16014（误导） | `wb.go:213-231`；`pc.go:113-121` | 只在 Redis 故障时 | 照搬 BW2（§0.4 #4） |
| B-s4 | AddObserver 超时等结局不明：match 已删标记，battle 却可能已登记这名观众；这个「幽灵观众」占名额，开局清退也找不到他 | `wb.go:237` | 只在故障时 | 保留标记（W4） |
| B-s5 | 163 同步链最坏 3 + 3 + 3 + 3 = 12 s，超过 5 s 客户端收到信封 1003；RPC 用 `context.Background()`，请求超时不取消它，之后仍可能观战成功、推来 177 | `sp.go:331-334`、`:342-345`；`yaml:3`；`SC.cs:192-198` | 只在 battle 慢时 | 预算夹紧（W10） |
| B-s6 | 指标 `already_watching` 在懒清退路径上也计数，换场成功会同时计 already_watching 与 ok | `wb.go:99`、`:234`；`metrics.go:86-90` | 否 | 拆开（W14） |
| B-s7 | 损坏的记录：163 回 16004 且不剔除，随机模式反复挑中就反复 16004；列表跳过也不剔除 | `wb.go:167-170`；`lw.go:68-73` | 只在数据损坏时 | 照搬 BW9，计数告警 |
| B-s8 | 随机模式里，1004 以外的拒绝（满员等）不换场，直接 16018 | `wb.go:255-260` | 是 | 照搬 BW6 |
| B-s9 | 身份回落到请求体 | `queue.go:199-208` | 只影响伪造请求 | W12（同 M3） |
| B-s10 | 换场时先摘旧场；新场失败时玩家什么都不在看 | `wb.go:107-111` | 是 | 照搬 BW10 |
| B-s11 | 「换场」与「重看同一场」只按请求里的 battle_id 区分；随机模式即使又挑中旧场也已经发过 Remove（再推一次 166 REMOVED + 177） | `wb.go:103-111` | 是 | 照搬 |

---

## 2 跨区 1V1

### 2.1 基线端到端链路（A 在 zone 1，B 在 zone 2）

基线跨区 1V1 没有专门的链路，用的就是普通 1V1（`czm.md:6`、`:27-53`）。能跨区靠四个前提：匹配池全局（D1，`czm.md:90`）；定位玩家用位置记录里的 `zone_id`（D6，`czm.md:97`）；
battle 是全局池；节点号全服唯一（etcd 全局分配），Kafka 按节点号寻址不带 zone（`czm.md:41-46`）。入口只多一处：gate 随机选 match 时全局池类型免 zone 过滤（D11，`czm.md:102`）。

| # | 步骤 | 基线事实 | 出处 |
|---|---|---|---|
| 1 | 选区、取 gate | `POST /api/assign-gate {zone_id}` 拿到该 zone 的 gate 地址与 gate 令牌 | `robot/main.go:513-538`；`robot/http_assign_gate.go:14-41` |
| 2 | 进场 | 按 gate 的 zone 落到本 zone 的 scene；scene_manager 写 `player:{id}:location{…, zone_id}` | `czm.md:50` |
| 3 | 157 到 match | 直连模式下 gate 的 `PickRandomNode` 对全局池类型不比对 zone；路由模式经 client_rpc_router 转发，只有 Login 按 zone 选实例 | `czm.md:68-78`、`:102`；`rcfg.go:42-45` |
| 4 | JoinQueue 取 zone | 判模式与人数、战斗锁、票据自愈之后读**位置记录**：读不到回 16020，读到就把 `loc.ZoneId` 写进票据。请求体的 `zone_id` 不读 | `join.go:143-167` |
| 5 | 入队 / 凑单 | 队列键 `(mode, battle_config_id)` 全局，不含 zone；锚点 + 评分容差，不看 zone；2 人 matched TTL 48 s | `czm.md:90-92`；`cz_test.go:153-195` |
| 6 | 选 battle | `BattleNodes.PickRandom()`，覆盖全部 zone | `gather.go:212-217` |
| 7 | 逐人冻结 | 再读位置 → `SceneNodes.EndpointOf(loc.ZoneId, loc.NodeId)` 按 (zone, 节点) 查 → 发往**该 zone 的 scene** | `gather.go:239-260`、`:399-435` |
| 8 | 快照路由 | scene 填 `BattleRouting{session, gate 节点与实例, scene 节点与实例, zone_id = 本 zone}` | `bd.proto:12-19` |
| 9 | zone 组成 | 按快照的 `routing.zone_id` 去重，记 `gather_zone_mix_total{mode, mix}`，只作观测 | `gather.go:262-264`、`:509-528` |
| 10 | 落点记录 → 建房 | 先写 `spectate:battle:{id}`，再 CreateBattle | `gather.go:308-350` |
| 11 | battle 预签票 | 插表之前为全部参战者签票，载荷 `{battle_id, player_id, battle_node_id, battle_instance_id, expire_at_ms, role}`，**没有 zone**；地址 = 本节点 `client_endpoint` | `pb.proto:135-142`、`:161-171`；`room.cpp:566-585` |
| 12 | 大厅公告 | 每人先 177 后 143，经 Kafka 发给**快照路由里的那台 gate**（分区按 `gate_node_id`） | `room.cpp:609-628` |
| 13 | 直连 | 两名客户端各凭自己的票连**同一个 battle 进程**的 `host:port` | `bsc.go:264-265` 注释 |
| 14 | 确认 / 结算回流 | 确认事件发往快照的 scene 节点 + 实例；结算重投时重新读位置记录、**只解析 node_id**（节点号全局唯一）；各自 scene 应用后经大厅再推一份 150 | `room.cpp:211-245`、`:1794-1846` |
| 15 | 评分 | 全局 topic `match-results`，key 为 battle_id | `czm.md:345-347` |

### 2.2 链路上的三种「票」

| 票 | 谁签、绑什么 | 跨区时的作用 |
|---|---|---|
| gate 令牌 `GateTokenPayload` | gateway 按 zone 签；gate 只认本 zone 的令牌 | 决定玩家从哪个 zone 进来，从而决定位置记录里的 zone |
| 排队票 `match:ticket:{pid}` | match 写，含 `zone_id` | `zone_id` 只用于观测（D4，`czm.md:93`）；凑单不读，gather 重新读位置 |
| 战斗票 `BattleTicketPayload` | battle 自签，密钥与 gate 令牌分域 | 不含 zone，只绑节点与实例；两人各持一张，指向同一节点 |

### 2.3 归属区不参与 1V1

match logic 里没有任何归属区引用（只有 team 模块读，`go/match/internal/team/homezone.go:12-17`）；1V1 排队与 gather 的 zone 一律取位置记录。
归属区只用于建角登记、角色列表、首登回弹（`RedirectOnEnter`，生产与 dev 都关）与存盘路由。Java 同（Q25）。

### 2.4 基线依赖的不变量（Java 逐条有对应物，见 §2.7）

1. 共享 Redis 全服只有一份（D12，`czm.md:103`）。
2. **节点号全服唯一**，Kafka 按节点号分区不带 zone（`czm.md:41-46`）。**这一条在 Java 不成立**（§2.8）。
3. match / battle 是全局池；gate 对全局池类型豁免 zone 过滤（D11）。
4. match 能看到全部 zone 的 scene，按 (zone, 节点) 查，身份有歧义就拒绝。

### 2.5 基线 robot：`battle_smoke_cross_zone`

- **配置**（`bsc.yaml`）：`mode: battle-smoke`、`battle_smoke.cross_zone: true`、`zone_a: 1`、`zone_b: 2`（必须不同）、`mode: "1v1"`、password 认证（`:26-36`）；
  前置「两个 zone 都在跑、共用一个 Redis，match 每 zone 一份」（`:4-5`）；固定账号 robot_9003 / 9004，与 9001 / 9002 错开防互顶（`:12-14`）。
- **分流**：`RunBattleSmoke` 见 `CrossZone = true` 进 `runCrossZoneMatchSmoke`（`bss.go:97-101`）。
- **步骤**：A、B 并发登录各自的 zone（只改 cfg 的 `ZoneID`，`bsc.go:135-155`）→ 断言两人 gate 地址不同（`:157-166`）→ 各发 157 `{1V1, battle_config_id = 1, zone_id = 本侧}`，**不检查回包**（`:244-252`）
  → 等 143（30 s）→ 直连、比对握手 battle_id、补拉 140 → 直连上 162 开自动 → 等 150（120 s），直连计数要求 `state_replies ≥ 1 ∧ turn_results ≥ 1 ∧ battle_ends ≥ 1`（`:254-305`）
  → 主流程要求两侧 battle_id 非 0 且相同（`:174-200`）、回合数 ≥ 1（`:202-219`）。
- **输出**：`CROSS_ZONE_MATCH_OK battle_id=… zone_a=… zone_b=… a_turns=… b_turns=… a_direct_turns=… b_direct_turns=…`；失败 `CROSS_ZONE_MATCH_FAIL step=… reason=…`（`bsc.go:16-20`、`:221-227`）。
- **为什么 `battle_config_id = 1`**：1V1 不校验它（`bsc.go:50-61`），但它是队列键的一部分；Unity 客户端的 1V1 也固定 1（`BattleUiStyle.cs:28` `Pvp1V1BattleConfigId = 1`），
  `DAP.cs:543-546` 注明必须与 robot 一致才能凑到一起。**客户端可见约束**：Java 不得给 1V1 校验或改写 config。
- **基线 robot 的弱点**（Java 补强，§10.8）：「gate 地址不同」只证明入口 zone 不同；157 回包不检查；不断言两人连同一节点 / 实例；不断言两侧 outcome 一致
  （`eBattleOutcome` 是按阵营的绝对值，`bd.proto:172-177`）；不区分直连 150 与 scene 结算后的大厅 150；固定账号带来评分漂移与残留状态。
- **Unity 双播放器验收**对齐这个 robot，并要求 `direct_turns == turns`（`DAP.cs:37-50`）。

### 2.6 基线运行记录与教训

- 2026-09-02 单库形态 `CROSS_ZONE_MATCH_OK`（A 走 gate 10000、B 走 gate 10010，0.25 s 凑单、建局 71 ms、24 回合）；Redis Cluster 形态通过；杀掉一个 zone 的 match 后两个 zone 的 gate 都路由到剩下那台；
  连续复跑两次通过（`czm.md:273-285`；`PROGRESS.md:3474-3486`）。2026-09-03 新版节点复验 21 回合 / 49 s（`PROGRESS.md:3556`）。
- 复跑暴露的三处缺陷都来自「连续对局」：0 血带入下一局；上一局 ready 票（60 s）挡住「打完立刻再排」→ 无锁时按票 id CAS 删票（`join.go:234-246`）；robot 把登录时补推的上一局 150 当成本局结束（`czm.md:281`）。
- **当前基线没有绿色记录**：2026-09-29 的直连收缩（`tbs.md` §22 D66–D73）落码时自述「全部未编译、未测试」（`PROGRESS.md:6199`；`czm.md:137`）。
  `PROGRESS.md` 里最晚的 `BATTLE_SMOKE_OK` 在 `:6090`（09-28），最晚的 `CROSS_ZONE_MATCH_OK` 在 `:3556`（09-03），都早于收缩。跨版本验收失败时先分清是 Java 还是基线 robot 的问题。
- 固定账号的代价：评分随运行次数漂移，基线另写脚本删 `match:rating:*`（`docs/design/handoff-backlog-2026-09-05.md:1050-1056`，P2-07）。

### 2.7 Java 对照与不变量

| # | 不变量 | 谁保证 | 怎么钉住（§10） |
|---|---|---|---|
| Z1 | 队列键、凑单锁、注册集都不含 zone；zone 只作观测字段写进票据 | 6.4（`m-spec` §9.4） | IT：移植 `TestMatcherMixesZonesAndShortensMatchedTTL`（`cz_test.go:153`） |
| Z2 | JoinQueue 的 zone 取位置记录（严格读，`o` 才算），请求体的 zone 与 `SessionContext.zone_id` 都不用 | 6.4（`m-spec` §2.2 第 7–8 步） | `QueueServiceTest`：会话 zone 1、位置 zone 2 → 票据 zone 2 |
| Z3 | gather 逐人按**位置记录的 (zone, 节点号)** 读 `xm:nodes:scene:<zone>`；Cancel 发回 Prepare 时记下的端点 | 6.4 `ScenePreparer`（`m-spec` §9.6 第 3 步、fail 行） | `ScenePreparerTest` / `CrossZoneGatherTest` 同号碰撞 |
| Z4 | battle 池 `xm:nodes:battle:0`（作用域 0） | 6.2（`NodeTypes.java:32-36`；`bn-spec` §7.10） | — |
| Z5 | 177 / 143 / 156 / 154 / 大厅 150 都按在线目录寻址，发往带 zone 的频道 `xm:gate-push:{zone}:{gate}`，再按 gate 实例过滤 | 6.2 N2、`arch` §4.3（`RedisKeys.java:351`；`PlayerPushes.java:130`） | `PlayerPushesTest` / `PresenceLobbyAnnouncerTest` 同号 |
| Z6 | scene 用自己的 zone 填 `BattleRouting.zone_id`；确认事件按 `(zone_id, scene_node_id)` + 实例，不符回落定位器 | 6.3（`sb-spec` §7.16 :934-935） | `DubboSceneBattleEventsTest` 同号 |
| Z7 | 结算首投 / 重投经 `SceneAssetLocator`，按位置记录的 zone 读目录 | 6.3（`sb-spec` §7.15 :918；`SceneAssetLocator.java:21-35`） | `SettlementOutboxTest` 同号；robot Z7 |
| Z8 | 每个 zone 的 gate 把 `MatchService` 转给同一组 xm-match（local 同一个 `xm.dubbo.match-url`；nacos 下 group `match` 不带 zone） | 6.4（`m-spec` §9.2） | 切片配置；robot |
| Z9 | 所有 zone 共用一个 Redis | 现有架构（`RedisKeys.presence` / `playerLocation` 不带 zone，`RedisKeys.java:42-55`） | — |
| Z10 | 评分按 player_id，全局 | 6.4（`m-spec` §5） | robot Z8 |
| Z11 | `xm_match_gather_zone_mix_total{mode, mix}` 按快照的 `routing.zone_id` 计 | 6.4（`m-spec` §11） | `ZoneMixTest`；robot |
| Z12 | 观战不分 zone：索引全局；观众路由的 zone 取在线目录（gate 所在 zone） | 6.5（§4.4） | `CrossZoneGatherTest`；robot Z5 |

**基线需要、Java 不需要的**：D11 的 gate 特判（Java gate 经 Dubbo 按服务名找 match，本来就不按 zone 过滤）；「match 每 zone 一份」（`bsc.yaml:4-5` 只是基线本地部署形态，Java 的 xm-match 全局部署）。

### 2.8 Java 特有的坑：同号节点跨 zone 碰撞

1. **scene / gate 的节点号按 zone 租约**：键 `xm:node-id:{type}:{zone}:{id}`（`RedisKeys.java:16-31`；`NodeTypes.java:11`、`:13`），zone 1 与 zone 2 的第一台 scene、第一台 gate 都会是 1 号
   （`zt-spec` §5.13 :831 明写 xm-gate-z2 节点号也是 1）。任何只按 `scene_node_id` / `gate_node_id` 查目录、拼频道、拼缓存键的代码，都会把 zone 2 玩家的确认、结算、公告送到 zone 1 的同号节点。
   实例过滤会把它**丢掉而不是送错**，结局是「锁停到 TTL」或「公告丢失」，症状不显眼；本机 `XM_ZONES=2` 天然制造这种碰撞，robot 能暴露它。
2. **`BattleRouting.gate_node_id` 在 Java 不能用来寻址**（跨 zone 有歧义）。6.2 已改走在线目录（N2）；6.5 的观众路由同样取在线目录（W11），不得用它或 `SessionContext` 拼推送目标。
3. **三种 zone 不要混用**：位置记录的 zone（所在 zone：JoinQueue、gather、结算）；在线目录的 zone（gate 的 zone：推送、观众路由）；归属区 `player.zone_id`（建角、存盘、组队）。
   稳态下前两者相等；跨 zone 传送途中不等（`zt-spec:427`）。排队期间玩家旅行到别的 zone 时，gather 重读位置：位置处于待落点就判 `no_location`、该玩家作为肇事者出局，两版结局相同。
4. **battle 的通告地址必须对所有 zone 的客户端可达**：本机 127.0.0.1:12000；多机部署归 7.6。

---

## 3 客户端可见行为

### 3.1 163 WatchBattle：判定顺序（顺序本身就是语义）

失败时 `battle_id = 0`、`error_message = TipInfoMessage{id, parameters = [中文串]}`（`tipErr`，`join.go:19-21`）；成功只填 `battle_id`（随机模式回填实际挑中的场）。

| # | 条件 | 应答（id + `parameters[0]`） | 指标 outcome | 基线 | Java 落点 |
|---|---|---|---|---|---|
| 1 | 会话没有玩家身份 | 16004 `缺少玩家身份` | internal | `wb.go:53-59` | 只认 `SessionContext.player_id`，请求体一律忽略（W12） |
| 2 | 读票据出错 | 16004 `服务器繁忙,请稍后再试` | internal | `:70-73` | 只读脚本 S_W_ENTRY 出错（同一往返也读了标记，见注 1） |
| 3 | **持票，任意状态（queued / matched / ready）** | 16014 `匹配中无法观战` | queued | `:74-79` | 同（BW1） |
| 4 | 读战斗锁出错 | 16004 `服务器繁忙,请稍后再试` | internal | `:80-83` | `BattleLockReader.exists` 异常完成 |
| 5 | 战斗锁存在 | 16015 `战斗尚未结束,无法观战` | in_battle | `:84-89` | 同 |
| 6 | 读观战标记出错 | 16004 `服务器繁忙,请稍后再试` | internal | `:90-93` | 已在第 2 行一并读出 |
| 7 | 已有标记：**不拒绝**。值非法 → 删；请求 battle_id ≠ 0 且等于旧场 → 只删标记，**不发** RemoveObserver；其余（换场、随机）→ 读旧场记录，在则 RemoveObserver(`rewatch`) 后删标记 | 继续往下判 | 基线计 already_watching；Java 计入清退指标（W14） | `:94-113`；`sp.go:282-308` | 按读到的值删（W3）；Remove 直拨旧场落点地址（W5），**同步完成**（或超时）后才往下走 |
| 8 | 读会话出错 | 16004 `服务器繁忙,请稍后再试` | internal | `:118-121` | `PlayerPresenceDirectory.findStrictAsync` 异常完成（`PlayerPresenceDirectory.java:145`） |
| 9 | 不在线（基线：会话不是 ONLINE，含断线等重连的 DISCONNECTING） | 16019 `会话不在线,无法观战` | offline | `:122-127`；`pc.go:87-92` | 在线目录没有条目（W11；gate 在断线、离场时删条目，`arch` §4.3） |
| 10 | 基线：`session.GateId` 不是数字 | 16004 `服务器繁忙,请稍后再试` | internal | `:128-132` | Java：在线目录条目缺 gate 实例（数据损坏，正常不可达）→ 同一应答 |
| 11 | 选场循环里：随机选场 / 读记录（含损坏）/ 写标记出错 | 16004 `服务器繁忙,请稍后再试` | internal | `:154-170`、`:195-197` | S_W_PICK / S_W_READ / S_W_ACQUIRE 出错 |
| 12 | 指定场：记录不存在 | 16018 `该战斗不存在或已结束` | not_found | `:171-185` | 同（已公开时只摘索引成员，不删记录） |
| 13 | 抢标记失败（只可能是并发 163） | 16016 `已在观战另一场战斗` | already_watching | `:187-203` | S_W_ACQUIRE 回 `busy`；回 `queued`（检查之后才建出的票据）→ **16014**、不调 AddObserver（W2） |
| 14 | AddObserver 成功后复查命中票据或锁（含读锁出错） | 16014 `匹配中无法观战` | queued | `:206-231` | 同（BW2）；RemoveObserver 异步发出（W10） |
| 15 | 成功 | `{battle_id}`，无 `error_message` | ok | `:232-235` | 同 |
| 16 | AddObserver 回 1004：在建房窗口内 → 不剔除；窗口外 → 剔除，随机 → 下一轮；指定（或窗口内） → 回包 | 16018 `该战斗不存在或已结束` | not_found | `:237-254`、`:306-318` | 窗口输入取原子快照（W6）；剔除按 attempt 守护（W7）；「节点已死」视同 1004（W5） |
| 17 | 其它拒绝（1005 / 1008 / 1003）或传输失败 | 16018 `该战斗当前无法观战`（随机也不换场） | rejected | `:255-260` | 明确拒绝与「确定没送达」删标记；结局不明保留标记（W4） |
| 18 | 随机两轮都没成 | 16017 `当前没有可观战的战斗` | no_battle | `:263-266` | 同 |
| J | Java 独有：在途已满；或剩余预算不够发下一跳 | 16004 `服务器繁忙,请稍后再试` | overloaded / internal | — | W10（标记已回滚或从未写入） |
| J2 | 处理器内未预期异常（bug） | 信封 1003（无 in-band 应答） | internal | 基线 panic → gRPC 错误 → 信封 1003（`fwd.go:170-184`） | `m-spec` §9.9 第 5 条；已抢到的标记按值尽力释放 |

- 注 1：基线第 2 行与第 6 行之间隔着锁检查；Java 把票据与标记放进一段只读脚本，脚本要么整体成功要么整体失败，所以「票据读成功、标记读失败、锁存在 → 16015」这种组合在 Java 不会出现。
  只在 Redis 故障时有差别（都回 16004 或 16015 之一），不单列差异。
- 随机模式只有「记录缺失」和「房间不存在且不在建房窗口内」进入下一轮；其它拒绝当场回 16018（BW6）。

### 3.2 `parameters[0]` 全表（逐字节，逗号都是半角）

`缺少玩家身份`、`服务器繁忙,请稍后再试`、`匹配中无法观战`、`战斗尚未结束,无法观战`、`会话不在线,无法观战`、`已在观战另一场战斗`、`当前没有可观战的战斗`、`该战斗不存在或已结束`、`该战斗当前无法观战`。

tip 数值来自 `xm-table/src/main/proto/tip/match_error_tip.proto:20`（16004）、`:40-50`（16014–16019），表内文案来自 `config-data/tables/tip_text.json:70`、`:80-85`（客户端按 id 查表内文案，`parameters[0]` 另带服务端说明）。

### 3.3 164 ListWatchableBattles

| 规则 | 基线 | Java |
|---|---|---|
| `limit = 0` → 20；`> 50` → 50 | `lw.go:15-18`、`:39-45` | `MatchBudgets.WATCHABLE_LIST_DEFAULT / MAX` |
| 按 `created_at_ms` 降序；同分按成员字符串降序 | `lw.go:47` | 同一条 `ZREVRANGE … WITHSCORES` |
| 读索引失败 | gRPC 错误 → 信封 1003 | `ClientReply.tip_id = 1003` |
| 非法成员 → ZREM、跳过；过期 → 删记录 + ZREM、跳过；读记录出错 / 损坏 → 跳过、不剔除；记录不在 → 剔除、跳过 | `lw.go:55-77` | S_W_EVICT，过期分界取 Redis `TIME` |
| **不补齐条数** | `lw.go:34-36` | 同（BW5） |
| 不校验身份、不做互斥 | `lw.go:34-37` | 同（BW4） |

`BattleWatchSummary` 从 6.4 的 `BattlePlacement` 映射：`battle_id`、`battle_config_id`、`created_at_ms`、`player_names` 原样（顺序 = gather 成员顺序）；`mode` 用 `setModeValue(placement.mode)`，不认识的值也原样保留。
列表里会出现：已结束不足 360 s 的战斗（BW7）、满员的房间、请求者本人参战的局（同基线）。

### 3.4 换场与重看

| 情形 | 服务端 | 客户端可见 |
|---|---|---|
| 有标记，指定 Y ≠ 旧场 X | 记录 X 还在 → RemoveObserver(X, rewatch) → 删标记 → 走 Y 的流程 | X 的直连上收到 166 `{X, ONGOING, REMOVED}` 并关闭；**Y 失败时玩家什么都不在看**（BW10） |
| 有标记，随机 | 同上（随机一律先清退），随后可能又挑中 X → 重新登记 | 166 REMOVED(X)，再收到一次 177(X) |
| 有标记，指定 = 旧场 X | 只删标记 → 抢标记 → AddObserver 走幂等分支：同会话重推 177、有活直连再推 161；会话变了 → 关旧直连、推 177（`room.cpp:812-863`；`BRS.java:368-401`） | 不推 166。**重推的 177 有活直连时经直连直写**，没有才经大厅（大厅公告的统一规则，`room.cpp:1410-1430`；`BRS.java:796-800`）；客户端两条链路都挂了 177 的处理器，同局且直连活着时只换存票据、不重连（`mmorpg-client` `Assets/Scripts/Net/BattleDirectLink.cs:226-239`；`DirectRoutingBattleTransport.cs:17-19`） |
| 标记残留但 X 已收尾 | 读不到记录 → 只删；记录在而房间不在 → RemoveObserver 在 battle 侧幂等无副作用 | 无 |

Unity 客户端只在 `Phase == None` 时发 163（`SC.cs:150-156`），观战中要先 165；服务端的换场分支主要清理「本地已退出但直连没就绪、165 发不出去」「客户端崩溃或重登」留下的标记（`SC.cs:201-206`；`wb.go:95-98`）。

### 3.5 推送、顺序与时限（由 6.2 发出，6.5 只负责触发）

| 项 | 值 / 顺序 | 出处 |
|---|---|---|
| 163 成功之后 | battle 经大厅推 177 `{role = OBSERVER(2), expire_at_ms = 房间期限}`（新观众此刻一定没有直连，所以一定走大厅；幂等重看见 §3.4）；观众直连握手应答之后**紧跟** 161 `{state（全员冷却清空、无 self_items）, observer_count}` | `bn-spec` §5.5 :683-707、§5.8 O2 |
| 随机模式 | 161 可能先于 163 的应答到达（直连与大厅两条链路无序） | `SC.cs:33-34` |
| 每回合 | 观众收 158（冷却全清） | `bn-spec` §5.6 |
| 收尾 | 166 `{FINISHED(1), outcome}`；期限到 `{ABORTED(2), DRAW}`；作废 `{ABORTED, ONGOING}`；被清退 `{REMOVED(3), ONGOING}`；然后 FIN | `bn-spec` §4.10、§5.8 O5 / O6 |
| 165 | 应答 → FIN，**不推 166**；match 的标记不清（BW8） | `room.cpp:931-967` |
| 177 丢失 | 至多一次；客户端首帧 15 s 超时收回 None（`SC.cs:118-138`）；观众也可经 179 补签（6.4） | `m-spec` §8.3 |
| 观众上限 | 20 | `room.cpp:64` |
| 观战标记 TTL | 360 s（只影响「标记残留多久」，客户端不直接可见） | `sp.go:51-59` |

### 3.6 客户端依赖（`mmorpg-client` @ `a8577c7`）

- 观战面板一次最多拉 6 条（`SP.cs:22`、`:92-97`），显示「模式名 · 已开局时长」+ 玩家名（`SP.cs:136`、`:160-184`）；时长 = 客户端墙钟 − `created_at_ms`，所以 `created_at_ms` 必须是 Unix 毫秒（Java 取 Redis `TIME`，同量纲）。
- 「收到首帧才算进入观战」；Requesting 期间也接受 166（AddObserver 与战斗收尾竞速，`SC.cs:302`）。
- Superseded（直连改服务另一局）收敛回 None（`SC.cs:339-368`）；参战侧把迟到的 OBSERVER 分配包当成 Superseded 处理（`BC.cs:684-693`）。Java 的 W2 让这个窗口变窄，但没有消除，客户端这条兜底仍然需要。

### 3.7 跨区 1V1 的客户端可见面

协议零改动、客户端不展示对手大区（D9，`czm.md:100`）。157 / 177 / 143 / 140 / 162 / 139 / 150 的形状、票据字节、「1V1 不校验 config」与基线逐字节相同；Java 没有跨区相关的客户端可见差异。

### 3.8 客户端可见差异一览（详见 §8）

只在竞态 / 故障 / 过载 / 慢节点时可见：W2、W5、W6、W8、W10、W12、W13。正常路径上与基线逐字节相同；正常路径上的两处基线怪癖（BW1、BW7）照搬，登记两版同改候选（§8.4）。

---

## 4 Java 设计

### 4.1 包与类（领域对象 + `XxxService`，不用 ECS）

| 类 | 职责 | 基线来源 |
|---|---|---|
| `spectate.WatchBattleService` | 163 的流程（§4.4） | `wb.go` |
| `spectate.WatchableListService` | 164 的流程（§4.5） | `lw.go` |
| `spectate.SpectateStore` / `RedissonSpectateStore` | 观战标记、可观战索引、原子读快照（§4.3 的脚本） | `sp.go:22-30`、`:204-274`、`:310-315` |
| `spectate.SpectateRules`（纯函数） | `roomMayBeCreating`、`clampLimit`、`staleCutoff`、`summaryOf(BattlePlacement)`、标记值的编解码 | `wb.go:306-318`；`lw.go:39-45`；`sp.go:230-234` |
| `spectate.ObserverDialer` | 按落点地址调 `addObserver / removeObserver`，结局分成 `Replied(tip)` / `Dead` / `NotDelivered` / `Unknown`（§4.8） | `sp.go:349-390` |
| `spectate.SpectateGatherHooks implements GatherHooks` | `beforePrepare`：开局前清退；`onStarted`：公开这一场 | `gather.go:228-234`、`:387-389`；`sp.go:163-191`、`:282-308` |
| `spectate.SpectateSweeper` | 定时摘掉过期的索引成员，采样索引大小 | `matcher.go:201-204`；`sp.go:395-408` |
| `placement.PlacementDialer`（**从 6.4 的 `BattleTicketReissue` 抽出**） | 「按记录地址直拨；建连失败时查目录，同号换了实例才判死」，179 与 `ObserverDialer` 共用 | `m-spec` §4.3 第 4–6 行 |
| `support.MatchTips`（扩充） | 16014–16019 与 §3.2 的全部 `parameters[0]` | `errors.go:65-84` |
| xm-discovery `com.game.discovery.battle.BattleRoutings.gatePart(PlayerPresence)` | 在线目录 → `BattleRouting` 的 gate 部分（`session_id`、`gate_node_id`、`gate_instance_id`、`zone_id`），scene 字段恒为 0 / 空 | `wb.go:133-139`；现为 `DevRoutingResolver.java:220-227` 的私有方法 |

### 4.2 Redis 键（全部经 `RedisKeys` 生成，`xm:` 前缀，tag `{match}`）

| 键 | 类型 / TTL | 内容 | 基线 |
|---|---|---|---|
| `xm:{match}:watching:<pid>`（`RedisKeys.matchWatching(pid)`） | STRING，PX 360 000 | `"<battle_id 无符号十进制>:<nonce>"`，nonce 每次 163 随机 16 位 hex | `spectate:watching:{pid}`（值只有 battle_id） |
| `xm:{match}:watchable`（`RedisKeys.matchWatchable()`） | ZSET，无 TTL | member = `Long.toUnsignedString(battle_id)`，score = `created_at_ms`（毫秒 < 2^53，double 精确） | `spectate:battles:active` |
| `xm:{match}:battle:<id>` | 6.4 的落点 HASH（`a` = attempt，`pb` = `BattlePlacement`），360 s | 6.5 只读，加两种有条件的删除（S_W_EVICT） | `spectate:battle:{id}` |
| 只读 | `xm:{match}:ticket:<pid>`（6.4）、`xm:presence:<pid>`、`xm:nodes:battle:0`、`RedisKeys.battleLock(pid)`（6.3，经 `BattleLockReader`） | — | — |

观战标记、可观战索引、票据、落点记录同在 `{match}` slot；战斗锁不在（6.3 拥有），所以锁检查不进脚本，靠复查兜底。

### 4.3 脚本（每段只访问 KEYS 里声明的键；可变脚本都能被 Redisson 安全重发，同 `m-spec` §9.4）

| 脚本 | KEYS | 语义 | 重发 |
|---|---|---|---|
| S_W_ENTRY（只读） | 票据、标记 | 一次往返返回 `{EXISTS 票据, GET 标记}`；判定仍按 §3.1 的 3 → 5 → 7 顺序 | — |
| S_W_ACQUIRE | 票据、标记 | 票据存在 → `queued`；标记值等于 ARGV → `ok`（重放）；标记存在 → `busy`；否则 `SET PX 360000` → `ok` | 值里带 nonce，重发命中自己写的值回 `ok`，不会误报 16016 |
| S_W_RELEASE | 标记 | 值等于 ARGV 才 DEL（回滚、复查清退、入口删旧标记、开局清退都用它） | 幂等 |
| S_W_READ（只读） | 落点、索引 | 原子返回 `{ZSCORE 成员, HGET a, HGET pb, TIME}` | — |
| S_W_PICK（只读） | 索引 | ARGV = Java 侧 `ThreadLocalRandom` 生成的 `r ∈ [0,1)`：`cutoff = TIME − 360000`；`n = ZCOUNT [cutoff, +inf)`；n = 0 → 空；否则 `ZRANGEBYSCORE cutoff +inf WITHSCORES LIMIT min(⌊r·n⌋, n−1) 1`，连同 TIME 返回。**过期成员不可能被挑中**（W8） | — |
| S_W_EVICT | 落点、索引 | 四种模式：`invalid`：ZREM 成员；`missing`：落点不存在才 ZREM；`dead@a`：落点的 `a` 等于给定值（或落点已不在）才 DEL 落点 + ZREM，被改写过就不动（W7）；`stale@cutoff`：ZSCORE < cutoff 才 DEL 落点 + ZREM | 幂等 |
| S_W_PUBLISH | 落点、索引 | 落点的 `a` 等于这次开局的 attempt 才 `ZADD score = created_at_ms` | 幂等 |
| S_W_SWEEP | 索引 | `ZREMRANGEBYSCORE -inf (TIME − 360000)`，只摘成员；落点靠自己的 TTL 过期（同 `sp.go:395-408`） | 幂等 |
| S_W_LIST（只读） | 索引 | `ZREVRANGE 0 limit−1 WITHSCORES` + TIME | — |

另有两处普通命令：164 用 Redisson `RBatch` 一次取回这批落点的 `pb`（只读、无需原子）；开局清退用一条 `MGET` 读出全部成员的标记（同 slot）。

**顺序不变量**（照搬 `sp.go:156-162`）：必须先有最终落点记录、再进索引。S_W_PUBLISH 的 attempt 条件保证「索引里出现的成员，其记录一定是最终那一次写的」；
6.4 第 5 步先补写、再调 `onStarted`（`m-spec` §9.6 :1057）。

### 4.4 163 的 Java 流程（`WatchBattleService`，虚拟线程，§4.9 的预算）

1. 第 1 行判身份（只认 `SessionContext.player_id`）。
2. S_W_ENTRY → 第 2、3 行。
3. `BattleLockReader.exists` → 第 4、5 行。
4. 第 7 行的旧标记：值非法 → S_W_RELEASE(读到的值)；显式同场 → 只 S_W_RELEASE；否则读旧场落点（`PlacementStore.read`；读失败只记日志、不发 RPC），在则
   `ObserverDialer.remove(旧落点, reason = rewatch)`，超时 `min(3 s, 剩余预算 − 1.2 s)`；**剩余预算不足 2.2 s 时什么都不做、直接回 16004**（标记原样保留，玩家仍在看旧场）；然后 S_W_RELEASE。
5. 在线目录严格读 → 第 8、9 行；组观众路由 `BattleRoutings.gatePart(presence)`；条目缺 gate 实例 → 第 10 行。
6. 选场循环（随机 2 轮、指定 1 轮）：
   - **随机**：S_W_PICK，Java 侧至多 3 挑；挑到非数字成员 → S_W_EVICT(`invalid`) 后重挑；挑不到 → 跳出循环。随机模式 `published = true`。
   - 对选中的场 S_W_READ 得到 `{published（指定模式用）, attempt, placement, now}`；`checkedAtMs = now`（W6）。
   - 落点不在 → 已公开时 S_W_EVICT(`missing`)；随机 → 下一轮；指定 → 第 12 行。落点损坏 → 第 11 行（BW9，计 `xm_match_watchable_anomalies_total`）。
   - nonce 生成，S_W_ACQUIRE(`battle_id:nonce`)：`queued` → 尽力 S_W_RELEASE(本次的值)（覆盖「Redisson 重发 EVAL、首轮已写入标记、两轮之间建出票据」的情形）→ 16014；`busy` → 16016。
   - 剩余预算不足 1 s → S_W_RELEASE → 16004（J 行）。否则 `ObserverDialer.add(placement, AddObserverRequest{battle_id, observer_player_id, routing, observer_name = SessionContext.account})`，
     超时 `min(3 s, 剩余预算 − 0.2 s)`。结局（§4.8）：
     - `Replied(0)` → **复查**：**并行**再读票据（`TicketStore` 只读口，读失败只记日志、按无票）与锁（读失败按有锁），按剩余预算等。命中 → 异步 `remove(reason = concurrent_queue)`、不等 → S_W_RELEASE → 16014。否则 → 第 15 行。
       复查的读因剩余预算耗尽而等超时，按「读失败」处理：锁 → 有锁 → 16014（与 BW2 同一 fail-closed 方向，宁可多清退一个观众也不放进「观战 + 参战」）。Add 前的 1 s 门槛与 0.2 s 预留保证正常 Redis（毫秒级）下不会走到这里，只在 Redis 慢时可见，归入 W10。
     - `Replied(1004)` 或 `Dead` → S_W_RELEASE；`roomMayBeCreating(published, placement.created_at_ms, checkedAtMs)` 为真 → 第 16 行（不剔除）；否则 S_W_EVICT(`dead@attempt`)，随机 → 下一轮，指定 → 第 16 行。
     - `Replied(其它)` 或 `NotDelivered` → S_W_RELEASE → 第 17 行。
     - `Unknown` → **保留标记**（W4）→ 第 17 行。
7. 循环用完 → 第 18 行。

`observer_name` 取 `SessionContext.account`（`xm-api/src/main/proto/xm/api/client_call.proto:16-17`），与基线 `session.Account` 同值；它只进 battle 日志（`bn.proto:54`；Java 同步副本 `:57`），不下发客户端。

### 4.5 164 的 Java 流程（`WatchableListService`，`match-worker`）

S_W_LIST → `RBatch` 取这批落点的 `pb` → 逐条按 §3.3 判定（剔除用 S_W_EVICT，尽力而为、失败只计数）→ 组摘要。读索引失败 → `tip_id = 1003`。预算同 `match-worker` 的 4500 ms。

- **`RBatch` 整批失败或超时**：每一条都按「读记录出错」处理（跳过、不剔除，同基线逐条 GET 出错，`lw.go:68-73`），回已组好的列表（通常为空），**不回 1003**；`xm_match_list_watchable_total` 仍计 `ok`，另计 `xm_match_watchable_anomalies_total{reason="record_read_failed"}`。
  基线没有「整批」概念，逐条失败时同样回一个变短的列表，所以这样对齐的是客户端结果而不是调用次数。
- **剔除不阻塞应答**：需要剔除的成员收集起来，在应答组好之后异步发出（一批 S_W_EVICT，不等结果）。基线是同步逐条执行，剔除结果不影响本次列表内容（`lw.go:55-77` 剔除后都是 `continue`），所以不可见。

### 4.6 gather 钩子（6.4 的 `GatherHooks`，跑在 gather 的虚拟线程上）

**`beforePrepare(members)`**（第 2.5 步，对应 `gather.go:228-234`）：

- 一条 `MGET` 读出全部成员的标记；读失败 → 只记日志、全部跳过（同基线「读标记出错 → 标记不删、返回」）。
- 对每个有标记的成员**逐人串行**：值非法 → S_W_RELEASE；否则读落点：读失败 → 记日志、不发 RPC、S_W_RELEASE；不在 → S_W_RELEASE；
  在 → `ObserverDialer.remove(落点, reason = enter_gather)`（3 s，失败只记日志）→ S_W_RELEASE。
- 每人至多 3 s：matched TTL 公式里每人 3 s 的 RemoveObserver 项就是它（`m-spec` §3.4 :393），数值不变。实现上每人一个 3 s 的 `Deadline`，**读落点 + RemoveObserver 都算在里面**（RPC 超时取 `min(3 s, 剩余)`）；
  到点只记日志、尽力异步 S_W_RELEASE，然后处理下一人。开头那条 `MGET` 与各次 S_W_RELEASE 属于「Redis 小操作」，同基线算在公式的 10 s 余量里（`queue.go:351-354`）。
  不这样夹紧的话，Java 的 Redis 单命令最坏 4.2 s（§4.9）加 3 s RPC 会让单人超出公式的 3 s 项。
- 任何异常都吞掉，**不影响开局**；gather 随后失败，被清退的观众也不恢复（`sp_test.go:867`）。五个入口都经过这一步。

**`onStarted(placement)`**（第 5 步，6.4 补写落点之后）：S_W_PUBLISH(attempt, created_at_ms)，尽力而为；失败只是这一场不进列表（`gsi_test.go:463`）。6.4 的失败路径不调它：失败的场次永远不在索引里。

### 4.7 清扫（`SpectateSweeper`）

单线程 `scheduleWithFixedDelay`，缺省 10 s，每轮 `try/catch Throwable`（JDK 调度器遇异常会永久停止，`m-spec` §12.1 第 14 条）；每轮 S_W_SWEEP，并用 ZCARD 采样 `xm_match_watchable_battles`。
多实例重复执行幂等，不抢锁。不挂在 matcher 上（`m-spec` §0.3 :74）。

### 4.8 观众 RPC 的寻址与结局分类（`ObserverDialer`，基于 `PlacementDialer`）

按落点记录的 `(rpc_host, rpc_port, battle_instance_id)` 从 `NodeRpcClients<BattleNodeService>` 取引用，`retries = 0`，超时由调用方给。

| 结局 | 判据 | 163 | 清退路径（rewatch / enter_gather / concurrent_queue） |
|---|---|---|---|
| `Replied(tip)` | 调通了 | 按 tip（§4.4） | 忽略结果 |
| `Dead` | **建连失败**（请求确定没送达），且目录 `xm:nodes:battle:0` 里同号节点的 `instance_id` ≠ 落点的实例 | 视同 1004 | 只删标记 |
| `NotDelivered` | 建连失败，但没有「换了实例」的正面证据（目录里没这个号、同一实例、或目录读失败） | 删标记 → 16018 `该战斗当前无法观战` | 只记日志 |
| `Unknown` | 超时，或连上之后断开；6.2 在途超限的异常完成（`BattleNodeServiceImpl.java:278-283`）也归这里 | **保留标记** → 16018 `该战斗当前无法观战` | 只记日志 |

理由同 `m-spec` §4.3：battle 丢了租约但进程还活着时（`bn-spec` §7.10），它不在目录里，但直拨能到；只看目录会把活着的房间判死，只直拨又拿不到「号已被别的进程接手」的证据。
实现按 Dubbo 异常类别区分建连失败与调用超时，单测分别钉住（§10.5）。

### 4.9 线程与预算

| 号 / 任务 | 执行器 | 预算 | 过载时 |
|---|---|---|---|
| 163 | **虚拟线程**，全局信号量 `xm.match.spectate.max-inflight`（缺省 128） | 受理时刻 + 4500 ms 的 `Deadline`；每一跳 `future.get(min(基线超时, 剩余预算 − 预留))` | in-band 16004 `服务器繁忙,请稍后再试`（outcome `overloaded`） |
| 164 | `match-worker`（只有 Redis 操作） | 4500 ms | 信封 1003（164 没有 in-band 错误字段，同 M29） |
| 开局清退 / 公开 | gather 的虚拟线程 | 每人 3 s | — |
| 清扫 | `match-spectate-sweeper`（单线程） | — | — |

- gate 调 match 的超时是 5 s（`m-spec` §9.2）；4.5 s 预算保证 match 先给出 in-band 应答。
- 虚拟线程上不在 `synchronized` 块内阻塞；Redisson / Dubbo 都用异步 API 加 `future.get(剩余预算)`（`m-spec` §12.1 第 13 条）。Redisson 单条命令最坏 4.2 s（`arch` §6「Redis 客户端超时」条：HEAD `:631-633`，当前工作区 `:666-668`；`m-spec` §9.6 引的 `arch:607-609` 已过时，按节名找），
  所以 Redis 操作同样按剩余预算等；等超时而命令可能已生效时（S_W_ACQUIRE），尽力异步 S_W_RELEASE(本次的值)，再回 16004。
- 线程所有权（`AGENTS.md` §3）：全部阻塞 I/O 都不在 Netty I/O 线程或场景逻辑线程上；xm-match 没有场景状态。

### 4.10 跨区 1V1：Java 侧要做的事

- **生产代码**：没有新路径（§2.7 每条都由 6.2 / 6.3 / 6.4 保证）。实现评审时按 §2.8 逐条核对 6.2 / 6.3 / 6.4 的寻址代码没有「只按节点号」的地方。
- **切片**：5.4 §5.13 的 `XM_ZONES=2` 共用进程里 xm-battle 已列（`zt-spec:834`），只需再加 xm-match 一份；xm-gate-z2 用缺省的 `xm.dubbo.match-url`（`tri://127.0.0.1:20888`），与 zone 1 的 gate 指向同一个 xm-match。
- **测试**：§10.3 的同号碰撞组件测试、§10.8 的 robot 场景。
- **CI**：7.1b 之后 `stack.yaml` 加 `two-zones` profile（Q22）。

### 4.11 对其它批次与文档的改动

| 对象 | 改动 |
|---|---|
| 6.4 xm-match | 从 `ticket.BattleTicketReissue` 抽出 `placement.PlacementDialer`；**`created_at_ms` 取 Redis `TIME`**（与 `deadline_ms` 同源，并入 M7；取值时刻仍在全员 Prepare 之后、写落点之前，同 `gather.go:286`；`m-spec` §9.6 第 4 步只写了「字段同 `gather.go:287-297`」，实现时核对）；落点的 `player_names` = 各成员**快照里的 `player_name`（角色名，不是账号）**，按成员顺序写（同 `gather.go:281-285`）；`GatherHooks` 的空实现换成 `SpectateGatherHooks`；`MatchClientMessageService` 把 163 派到虚拟线程、164 派到 `match-worker`；`m-spec` §8.1 :837-841、§9.3 :966、§9.9 :1119 随之改（§8.1 两行的新内容见本表下方）；停机序列（`m-spec` §9.8）在「撤 Dubbo 导出」之后加「有界等待在途 163（预算本身 ≤ 4.5 s）→ 停清扫器」；关闭 M22 |
| 6.2 xm-battle | 不改业务。`DevRoutingResolver.gateRouting` 挪到 xm-discovery `BattleRoutings.gatePart`（6.2 没合入时由 6.2 挪，否则 6.5 挪）；dev `add-observer` 接口注释写明「不写 match 的观战标记，开局不会清退 dev 观众」（Q14） |
| xm-discovery | `RedisKeys.matchWatching(pid)`、`matchWatchable()`；`BattleRoutings` |
| 5.4 切片 | `zt-spec` §5.13 :834 的「共用」行加 xm-match（battle 已在该行）；`start-slice.sh` / `stop-slice.sh` 同步 |
| xm-robot | `battle-smoke` 观战段（§10.7）；新场景 `battle-cross-zone`（§10.8）；`--visit-zone` 帮助文本 |
| `arch` | §4.21「匹配」加「观战」小节（键、脚本、钩子、线程）；§5 线程模型加「163 虚拟线程 + 在途上限」；§11 加 §6 的指标 |
| `PARITY.md` / `roadmap.md` / 盘点 | 见 §10.11 |

**`m-spec` §8.1 的 163 / 164 两行（:837-838）改为**（列与该表相同；表下那条「156 / 154 / 163 / 164 不涉及 I/O，直接在 Dubbo 线程上回」（:841）改为只剩 156 / 154）：

| 号 | 正常 | 会话没绑定玩家 | 请求体解析失败 | 依赖故障 | 工作池满 / 在途满 / 排队超预算 |
|---|---|---|---|---|---|
| 163 | §3.1 | in-band 16004 `缺少玩家身份` | 信封 1003 | in-band 16004 `服务器繁忙,请稍后再试`（§3.1 第 2 / 4 / 6 / 8 / 10 / 11 行） | in-band 16004 `服务器繁忙,请稍后再试`（J 行，outcome `overloaded`） |
| 164 | §3.3 | **照常回列表**（BW4，不看身份） | 信封 1003 | 读索引失败 → 信封 1003；读落点失败 → 逐条跳过（§4.5） | 信封 1003（M29） |

处理器内未预期异常（bug）两个号都回信封 1003，同 `m-spec` §9.9 第 5 条；163 已抢到的标记按值尽力释放（§3.1 J2 行）。

**配置校验**：`xm.match.spectate.*` 在 `MatchProperties` 里校验（§5.2 的范围），不合法拒绝启动，同 `m-spec` §9.8 第 4 步的口径。

---

## 5 配置与常量

### 5.1 `MatchBudgets` 增补（xm-api，纯函数，单测直接引用）

| 常量 | 值 | 出处 / 说明 |
|---|---|---|
| `OBSERVER_RPC_TIMEOUT_MS` | 3000（Add 与 Remove 相同；Remove 项已在 matched TTL 公式里） | `sp.go:32-36` |
| `WATCHING_TTL_SECONDS` | `= PLACEMENT_TTL_SECONDS` = 360 | `sp.go:51-59` |
| `SPECTATE_STALE_MS` | 360 000（过期分界 = 落点 TTL） | `sp.go:230-234` |
| `WATCHABLE_LIST_DEFAULT` / `WATCHABLE_LIST_MAX` | 20 / 50 | `lw.go:15-18` |
| `RANDOM_WATCH_ROUNDS` / `RANDOM_PICK_TRIES` | 2 / 3 | `wb.go:143-147`；`sp.go:242` |
| `GATHER_CREATE_STAGE_WORST_MS` | 22 200（6.4 已有） | `queue.go:342` |
| `WATCH_REWATCH_RESERVE_MS` / `WATCH_ADD_RESERVE_MS` / `WATCH_ADD_MIN_BUDGET_MS` | 1200 / 200 / 1000 | Java 独有（W10） |

### 5.2 `xm.match.spectate.*`（`xm-match/src/main/resources/application.yaml`）

| 配置 | 缺省 | 说明 |
|---|---|---|
| `sweep-interval` | 10 s | 清扫间隔（W9），> 0 |
| `max-inflight` | 128 | 163 在途上限（W10），≥ 1 |

163 的预算沿用 6.4 的 `xm.match.request-budget`（4500 ms）。其余都是代码常量（理由同 `m-spec` §10.1：出现在跨进程不等式里）。

### 5.3 数值不等式（实现与评审核对）

| 不等式 | 数值 | 说明 |
|---|---|---|
| 观战标记 TTL ≥ 战斗最长时限 | 360 ≥ 300 | 标记到期时该场必已收尾 |
| 过期分界 = 落点 TTL | 360 s | 读路径与清扫同一口径 |
| matched TTL 每人项 ≥ 开局清退每人最坏 | 3 s ≥ 3 s | `OBSERVER_RPC_TIMEOUT_MS` 不能改大（`queue.go:344-380`） |
| 163 预算 < gate → match 超时 | 4500 < 5000 ms | match 先回 in-band |
| 换场 Remove 预留 + Add 最低预算 ≤ 预算 | 1.2 + 1.0 ≤ 4.5 s | §4.4 第 4 步 |
| 建房窗口 = gather 写记录到最后一次建房的最坏耗时 | 22.2 s | 改 6.4 的超时要连带改窗口 |

### 5.4 基线常量对照

| 名称 | 基线值 | Java | 客户端可见 |
|---|---|---|---|
| 记录 / 标记 TTL | 360 s（`yaml:57` + 60） | 同 | 间接 |
| Add / Remove 超时 | 3 / 3 s | 同（按剩余预算夹紧） | 只在慢节点时 |
| 列表缺省 / 上限 | 20 / 50 | 同 | 是 |
| 随机轮数 / 每轮挑数 | 2 / 3 | 同（只在未过期成员里挑） | 是 |
| 建房窗口 | 22.2 s | 同 | 间接 |
| 兜底清理 | 500 ms / 实例 | 10 s | 否 |
| 观众上限 | 20（6.2） | 同 | 是 |
| match 请求超时 | 5000 ms | 预算 4500 ms | 只在慢 / 过载时 |

---

## 6 指标（`MatchMetrics`，Micrometer；不用 player / battle / zone 的 id 作标签，`AGENTS.md` §5）

| 指标 | 类型 | 标签 | 基线 |
|---|---|---|---|
| `xm_match_watch_battle_total` | Counter | `outcome` = ok / internal / queued / in_battle / already_watching / offline / no_battle / not_found / rejected / overloaded | `watch_battle_total`（`metrics.go:86-90`）。`overloaded` 是新增；`already_watching` **只计 16016**（基线把入口的懒清退也算进去，`wb.go:99`；W14） |
| `xm_match_list_watchable_total` | Counter | `result` = ok / error / overloaded | 新增 |
| `xm_match_spectate_evictions_total` | Counter | `reason` = enter_gather / rewatch / concurrent_queue；`result` = removed / no_record / invalid_mark / read_failed / rpc_failed | 新增（基线只有日志，`sp.go:307`） |
| `xm_match_watchable_index_evictions_total` | Counter | `reason` = room_missing / dead_node / stale / missing_record / invalid_member / sweep | 新增 |
| `xm_match_watchable_anomalies_total` | Counter | `reason` = corrupt_record / record_read_failed / publish_failed / mark_read_failed | 新增（BW9、164 读落点失败（§4.5）、`onStarted` 失败、开局清退读标记失败） |
| `xm_match_watchable_battles` | Gauge | — | 新增（清扫时采样 ZCARD） |
| `xm_match_observer_rpc_total` | Counter | `method` = add / remove；`result` = replied / dead / not_delivered / unknown | 新增 |
| `xm_match_spectate_inflight` | Gauge | — | 新增（163 在途数） |
| `xm_match_requests_seconds{method=WatchBattle / ListWatchableBattles}` | Timer | 6.4 已有 | `grpcstats` |
| `xm_match_gather_zone_mix_total{mode, mix}` | Counter | 6.4 已有；跨区 robot 断言它 | 同名 |

---

## 7 隐患与边界

### 7.1 实现必须守住的坑

1. **1004 是唯一的「房间不在」信号**（`room.cpp:783`），其它码一律 16018 `该战斗当前无法观战`；「节点已死」只在**建连失败 + 同号换实例**时视同 1004，超时永远不算（§4.8）。
2. **先有最终落点、再进索引**（S_W_PUBLISH 的 attempt 条件）；**读到落点缺失时只 ZREM、永不 DEL 落点**（GET 之后才预写的记录不能删，`wbcw_test.go:175`）。
3. **建房窗口的输入不能晚于读落点**：`published` 与 `checkedAtMs` 和落点在同一段只读脚本里取（W6）；剔除时再按 attempt 守护（W7）。删落点会让 179 失去定位，客户端判 BattleGone。
4. **换场的 Remove 必须先于 Add 完成**（或超时）：随机模式可能重挑同一场，迟到的 Remove 会把刚登记的观众摘掉。只有复查命中后的自我清退可以异步。
   残余风险（同基线 `wb.go:107-111` 的同步 Remove）：Remove **超时**时请求可能仍在 battle 的投递路上，随后重挑到同一场的 Add 理论上可能先被执行，观众随即被摘、收到 166 REMOVED，客户端按 Ended 收场后可再发 163。
   不为这个窗口加「超时后本轮排除旧场」之类的逻辑：它只在 battle 慢于 3 s 时出现，排除旧场反而改变随机选场的客户端可见分布。
5. **重看同一场不发 RemoveObserver**：否则给仍活着的旧会话推假 166（`wb.go:103-106`）。
6. **标记先于 AddObserver 写入**；删除一律按值（W3）。结局不明时保留（W4）。
7. **复查**：读票失败按无票，读锁失败按有锁（BW2），不能写反。
8. **开局清退永不阻断开局**，每人至多 3 s；gather 失败后不恢复观战。
9. **163 必须在 gate 的 5 s 之前给出 in-band 应答**；Redisson 单命令最坏 4.2 s，所以每一跳都按剩余预算等。
10. **虚拟线程**：不在 `synchronized` 里阻塞；定时任务每轮 `try/catch Throwable`。
11. **battle_id 是无符号 64 位**：成员一律 `Long.toUnsignedString`、解析用 `Long.parseUnsignedLong`；标记值同理。
12. **时钟**：`created_at_ms`、过期分界、建房窗口都用 Redis `TIME`（W13），依赖 6.4 按 §4.11 取值；混用本机时钟会让窗口平移。
13. **同号节点跨 zone 碰撞**（§2.8）：观众路由与推送不得按 `gate_node_id` 单独寻址。
14. **dev `add-observer` 登记的观众没有 match 标记**，开局清退不到他（Q14）；只在 dev / test 出现。
15. **幽灵观众**（B-s4）：W4 让开局清退能覆盖它，但不补发 Remove；名单残留随该场结束清理，客户端靠 Superseded 收场。

### 7.2 与其它系统的交互边界

- **观众没有战斗锁、不冻结**：观战中可以移动、换图（含 5.2 跨节点）、传送（5.4 的 226 不检查观战）、组队、聊天；scene 的战斗闸（`sb-spec` §2.3）不作用于观众。同基线。
- **5.4 传送 / GO-5 重定向**：观众换到另一个 zone 的 gate 时大厅会话变了；客户端在大厅断线时把观战作废回 None（`SC.cs:42`），直连随之关闭；
  match 的标记留到 TTL 或下一次 163 / 开局清退。再次 163 同一场走 battle 的「会话变了」幂等分支（关旧直连、按新会话推 177）。
- **5.5 / 6.2 排空**：battle 关闸只挡 createBattle，`addObserver` 不看准入闸（`BattleNodeServiceImpl.java:146-157`），排空中的节点上的房间照样能观战，直到房间结束。
- **6.2 租约丢失**：房间不作废、进程仍活，直拨照样能登记观众（W5 的收益）；目录里同号被别的进程接手后，直拨建连失败才判死。
- **7.2 GM 回档**：只看战斗锁，观众不受影响。
- **6.4 发号租约丢失（M28）**：163 / 164 不发号，照常服务；只有开局（gather 第 1 步）受影响，所以这期间不会产生新的可观战场次，开局清退钩子也不会被调用。
- **xm-match 停机 / 崩溃**：在途 163 已抢到的标记留到 TTL（只是「可能在观战」的提示，W4 的同一口径）；停机按 §4.11 有界等待在途 163。
- **限频**：163 / 164 按缺省每秒 3 条；Unity 面板「刷新」按钮与 robot 都要按这个节奏。

---

## 8 建议的有意差异

### 8.1 观战（W）

| # | 项 | 基线 | Java | 客户端可见？ | 两版同改？ |
|---|---|---|---|---|---|
| W1 | 键空间 | `spectate:*` 三类键，不带 tag，与票据分属不同 slot | 全部落在 `{match}` tag；复用 6.4 落点记录 | 否 | 否 |
| W2 | 抢标记 | 先查票据、后 SETNX，靠事后复查收窗（`wb.go:187-231`） | S_W_ACQUIRE 原子完成「无票据 ∧ SET NX」，事后复查保留（锁不在同一 tag，gather 也可能在抢占之后才开始） | 只在竞态下：「检查之后、抢标记之前」建出的票据不再先推一条 177(OBSERVER) 再清退，结果码同为 16014 | 可选 |
| W3 | 删标记 | 无条件 DEL（`wb.go:237`；`sp.go:311-315`），同一玩家两个并发请求会删掉对方刚抢到的标记 | 值带 nonce，按值删 | 否 | 可选 |
| W4 | AddObserver 结局不明 | 删标记；之后开局清退找不到可能已登记的观众（B-s4） | 只有明确拒绝或确定没送达才删；超时 / 断开时保留，不补发 Remove | 否（应答同为 16018 `该战斗当前无法观战`） | 可选 |
| W5 | 观众 RPC 寻址与判死 | 按 etcd 镜像找节点（`sp.go:355-358`），找不到回 16018 `该战斗当前无法观战`、不剔除，死节点上的场次留在列表里干扰随机观战直到 360 s | 直拨落点地址；建连失败且同号换了实例 → 视同 1004 → 剔除，随机换下一场（与 179 共用，M16） | **是，很窄**：battle 丢租约但还活着时照样能观战；**号已被别的进程接手**的死节点上的场次，指定观战的 `parameters[0]` 从「当前无法观战」变成「不存在或已结束」（码都是 16018），随机观战跳过它并剔除。号没有被接手的死节点（目录缺席 / 读失败）仍是 `NotDelivered` → 「当前无法观战」、不剔除，与基线相同 | 建议与 M16 一并 |
| W6 | 建房窗口的输入 | 先 ZSCORE、后 GET，`checkedAtMs` 用本实例墙钟（`wb.go:150-165`）；ZSCORE 失败按未公开继续 | 一段只读脚本原子取 `{已公开, attempt, 落点, TIME}`；脚本失败整体回 16004 | 只在时钟偏斜或 Redis 故障时 | 否 |
| W7 | 剔除守护 | 靠窗口判定避免删掉 D82 改写之后的记录 | 剔除时比较落点的 attempt，被改写过就不动 | 否（纵深防御） | 否 |
| W8 | 随机选场 | 全集随机下标，挑中过期成员现场剔除，每次最多 3 挑（`sp.go:240-274`） | 一段 Lua 只在未过期区间里随机 | 只在过期成员堆积时（基线这时可能误回 16017） | 否 |
| W9 | 兜底清扫 | 每实例每 500 ms，挂在 matcher 上 | 独立定时任务，10 s 一轮 | 否（读路径已按分数过滤） | 否 |
| W10 | 执行与预算 | gRPC 线程同步执行；超过 5 s → 信封 1003，后台 RPC 不取消（B-s5）；自我清退同步等 | 虚拟线程 + 在途上限 128；4.5 s 预算、每跳按剩余预算夹紧；预算不够 / 在途满回 in-band 16004；自我清退的 Remove 异步 | 只在过载 / 慢节点时：应答改为 in-band 16004 / 16018，不再是 5 s 后的信封 1003。**177 仍可能晚到**：AddObserver 超时（`Unknown`）时 battle 可能已登记并推 177，Dubbo 不取消服务端执行（同基线 B-s5）；客户端 `BattleDirectLink` 对任何完整的 177 都会建连（`mmorpg-client` `Assets/Scripts/Net/BattleDirectLink.cs:226-260`），观战相位已回 None 时帧被丢弃，参战中则走 Superseded 兜底（`BC.cs:684-693`）。W4 保留标记，让开局清退能摘掉这类观众 | 否 |
| W11 | 观众路由与在线判定 | 读 `player:session`，要求 ONLINE；zone 读位置记录，读失败按 0（`wb.go:118-139`、`:269-281`） | 读在线目录，有条目才算在线；路由四个字段都取在线目录（177 也按在线目录推，路由与推送目标一致） | 否 | 否 |
| W12 | 身份 | 会话为 0 时回落请求体（B-s9） | 只认会话（同 M3） | 只影响伪造请求 | 同 M3 |
| W13 | 时钟 | match 本机时钟 | Redis `TIME`（并入 M7） | 只在时钟偏斜时 | 同 M7 |
| W14 | 指标 | `already_watching` 把懒清退也算进去（B-s6） | 只计 16016；清退另计（§6） | 否 | 否 |

M22（6.4 期间 163 回 1006、164 回空列表）随 6.5 关闭，不是差异。

### 8.2 跨区 1V1（X）

| # | 差异 | 理由 | 客户端可见？ |
|---|---|---|---|
| X1 | gate → match 用一个配置好的 Dubbo 地址，不做「本 zone 的 match 挂了就落到别的 zone」（基线 D11 的入口层容错，`czm.md:280`） | Java 的 match 是全局服务；多实例与负载均衡随 7.6 | 否（只影响可用性） |
| X2 | scene / gate 一律按 (zone, 节点号) 寻址；基线结算重投只按 node_id（`room.cpp:211-245`） | Java 节点号按 zone 租约，没有全局唯一性 | 否 |
| X3 | 177 / 143 按在线目录的当前会话投递（6.2 N2） | 修掉基线「备战期间换会话，公告投到旧会话」 | 只在这个竞态里（改进） |
| X4 | 一个 Redis（`{match}` tag），没有 MatchRedis / SharedRedis 双存储 | 6.4 已定 | 否 |
| X5 | xm-match 全局部署一份（或多份全局实例），不按 zone 起 | 基线「每 zone 一份」只是本地部署形态（`bsc.yaml:4-5`） | 否 |
| X6 | robot 每次 run-tag 新建账号，不用固定的 robot_9003 / 9004 | 避免评分漂移与残留状态（P2-07）；「连续对局」的教训改由同一次运行打两局覆盖 | 否 |
| X7 | robot 断言更严，并加跨区观众与第二局；做成独立场景 `battle-cross-zone`，报告一行与基线 `CROSS_ZONE_MATCH_OK` 字段相同的 note | 补 §2.5 的弱点；Java `RobotOptions` 是扁平的场景枚举 | 否 |

### 8.3 照搬的基线怪癖（PARITY 注明）

| # | 怪癖 | 出处 |
|---|---|---|
| BW1 | 163 遇到 **ready 票据**（开局成功后 60 s）也回 16014，不像 JoinQueue 那样自愈 | `wb.go:70-79` 对比 `join.go:234-246` |
| BW2 | 复查命中战斗锁（含读锁出错）也回 16014，不是 16015 / 16004 | `wb.go:213-231` |
| BW3 | 观战中可以排队、被挑战、整队开战，开局时才清退；排队期间照样在观战 | `gather.go:228-234` |
| BW4 | 164 不校验身份，不做互斥 | `lw.go:34-37` |
| BW5 | 剔除后不补齐条数；列表里可能有满员的房间、本人参战的局 | `lw.go:34-36` |
| BW6 | 随机模式遇到满员 / 是参战者 / 签不出票 / 传输失败当场回 16018，不换场 | `wb.go:255-260` |
| BW7 | 已结束的战斗留在列表里，直到被点中或 `created_at + 360 s` | `sp.go:28-29`；`room.cpp:1176-1210` |
| BW8 | 165 不清 match 的标记、不推 166 | `wb.go:95-98`；`room.cpp:931-967` |
| BW9 | 损坏的记录：163 回 16004 不剔除；列表跳过不剔除（Java 另计 anomalies） | `wb.go:167-170`；`lw.go:68-73` |
| BW10 | 换场先摘旧场，新场失败时什么都不在看 | `wb.go:107-111` |

### 8.4 两版同改候选（6.5 不做；改 mmorpg 需用户同意）

- **C1 ready 残留自愈**（BW1）：163 第 3 步「ready 且读锁确认无锁 → CAS 删票、继续；ready 且有锁 → 仍 16014」，与 `join.go:234-246` 同理（ready 只在开局成功后出现、锁在 Prepare 时已写，「ready 且无锁」即战斗已结束）。
  推荐做，mmorpg 先改，Java 用 6.4 的 S_HEAL（ready 模式）同批跟进。
- **C2 已结束的战斗及时出索引**（BW7）：结果消费者处理每条 BattleResultEvent 时执行一段 Lua：落点存在就 `HSET e 1`、ZREM；`onStarted` 见 `e` 已置位就不 ZADD（极短局结果先到）；
  随机选场、列表、163 遇到 `e = 1` 视同房间不在。落点保留，179 照旧能定位。活动局与作废局没有结果事件给 match，仍靠懒剔除与 TTL。推荐做，两版同批。
- **C3** 文档与注释勘误（§8.5 E1–E3）。

### 8.5 勘误

| # | 位置 | 问题 |
|---|---|---|
| E1 | `tbs.md:305-323`（§10.2 数据流）、`:338`（§10.4 键表） | 数据流仍是收缩之前的形态（BindBattleEvent、161 经 Kafka）；键表写「gather 成功后写」；实际是**建房之前**写（`gather.go:308-321`），首帧随直连握手下发（`tbs.md` §22 D66–D69） |
| E2 | `ms.proto:36-37`、`:152-154`（Java 副本 `:39-40`、`:155-157`） | 「经 Kafka 推 161 首帧」「gather 开局成功后写」已过时，同 E1。契约文件不能在 Java 侧手改，建议 mmorpg 顺手改注释 |
| E3 | `bss.go:70-72` | ①「B 扑空回 tip_id=5」已过时：随机扑空回 16017，指定扑空回 16018；②「PVE solo 秒杀、一回合即结束、实测 ~100 ms」也已过时：现行表 Dungeon 1 的怪物 1 / 2 气血 370 / 360（基线 `generated/tables/monster.json`、`dungeon.json`），基线运行记录是 13 / 14 / 21 回合（`PROGRESS.md:4191-4192`、`:5631`）。`inventory/contract-robot.md:414` 照抄了这两条。§10.7 的时间预算按②的实情定 |
| E4 | `inventory/contract-robot.md:289-290` | 「161 经 Kafka 推」过时；把 AlreadyWatching 列成一般拒绝原因，实际「已在观战」不拒绝，16016 只有并发抢占一个出口 |
| E5 | `inventory/scene-manager-match.md:267` | 漏了 BW1、BW2 |
| E6 | `inventory/contract-robot.md:426` | 「直连计数 ≥ 1」的准确判据是 `state_replies ≥ 1 ∧ turn_results ≥ 1 ∧ battle_ends ≥ 1`（`bsc.go:300`） |
| E7 | `bsc.yaml:4-5` | 「match 每 zone 一份」是基线本地部署形态，不是需求 |
| E8 | `bn-spec` §7.10 :1174「match 按 node_id 补签会找不到节点 / 打到同号新持有者」 | 已被 M16 与本稿 W5 取代（直拨落点地址） |

---

## 9 开放问题（每条附推荐答案）

- **Q1 观战代码放哪？** 推荐 **xm-match 的 `com.game.match.spectate`**：与票据、落点同 tag，原子脚本只能在 match 里写；另起进程会让 W2 / W6 做不成。
- **Q2 163 用哪个执行器？** 推荐**虚拟线程 + 在途上限 128**，164 留在 `match-worker`。不采纳时的备选：163 进 `match-worker`，但加 4 并发的舱壁，超出直接回 16004。
- **Q3 观众 RPC 直拨还是按目录？** 推荐**直拨，判死规则与 179 共用 `PlacementDialer`**（W5）。备选是按目录，代价是丢租约但还活着的节点上的战斗没法观战。
- **Q4 AddObserver 结局不明时保不保留标记、要不要补发 Remove？** 推荐**保留、不补发**（W4）。
- **Q5 ready 残留要不要自愈（BW1）？** 推荐 **6.5 照搬**，登记两版同改候选 C1（推荐做，mmorpg 先改、Java 同批跟进）。
- **Q6 已结束的战斗要不要主动出索引（BW7）？** 推荐 **6.5 不做**，登记 C2（推荐两版同批做）。
- **Q7 JoinQueue / 切磋 / 整队要不要在请求时对称清退观战？** 推荐**不要**（BW3）：基线语义是「排队期间可以继续看」，客户端按这个设计（`BC.cs:684-693`）。
- **Q8 随机模式遇到满员等非 1004 拒绝要不要换场？** 推荐**不换**（BW6），否则改变客户端看到的码。
- **Q9 163 的预算怎么控制？** 推荐 §4.4 / §4.9：换场 Remove 同步且先于 Add，每跳按剩余预算夹紧，不够就回 16004；自我清退的 Remove 异步。
- **Q10 活动局还没接入（4.6 被表卡住），建房窗口判定还做不做？** 推荐**做**：代价很小，同时覆盖 D82 改写；4.6 接入后不必回头改。
- **Q11 列表或随机选场要不要按 zone 过滤？** 推荐**不过滤**：基线需求是「所有大区互相排队、切磋、观战」（`czm.md:4`），按 zone 过滤是客户端可见改动。
- **Q12 清扫间隔？** 推荐 **10 s**、可配（W9）。
- **Q13 复查读锁出错怎么回？** 推荐**照搬**：按有锁自我清退、回 16014（BW2）。改成 16004 只在 Redis 故障时可见、收益小。
- **Q14 6.2 dev `add-observer` 不写 match 标记，可以接受吗？** 推荐**接受**，只在 dev / test 出现，接口注释写明。
- **Q15 要不要加 dev 接口登记可观战场次，让 6.5 在 6.3 之前端到端？** 推荐**不加**（YAGNI）：组件测试不依赖 6.3，实施顺序是 6.3 → 6.5。
- **Q16 跨区 robot 的参数名？** 推荐复用 5.4 的 `--visit-zone`（帮助文本改成「另一个区：travel 的目的区 / battle-cross-zone 的 B 区」），与 `--zone` 相同时 `UsageException`。
- **Q17 跨区 1V1 做成独立场景还是 `battle-smoke` 的开关？** 推荐**独立场景 `battle-cross-zone`**（X7）。
- **Q18 跨区场景要不要带观众？** 推荐**带**（zone 2 的 C）：Java 的观众路由与 177 推送走带 zone 的在线目录、两个 zone 的 gate 同号，这是同号碰撞在观战上的唯一端到端验证点。
- **Q19 跨区 robot 用哪个 battle_config_id？** 推荐 **1**：同基线 robot 与 Unity 客户端，并与 6.4 `battle-smoke` 第 8 步的 0 分开，两个场景并行也不会互相凑走对手。
- **Q20 要不要同 zone 优先或按延迟加权？** 推荐**不要**：基线 D1 是用户的明确要求（`czm.md:90`）。
- **Q21 切片要不要起两台 xm-match 复现基线的故障切换？** 推荐 **6.5 不做**（X1）；按队列加锁的多实例安全由 6.4 单测覆盖，随 7.6 再议。
- **Q22 CI 要不要加双 zone？** 推荐**要**：7.1b 的 `stack.yaml` 加 `--profile two-zones`（xm-gate-z2、xm-scene-z2，端口照 `zt-spec` §5.13，做法同 `ci-spec:758` 的 `two-scenes`），在第三期之后跑 `battle-cross-zone`；本机连续绿了再加。
  **前置**：① `ci-spec` 风险 15 / Q9（:992、:1037）——xm-battle 的通告地址一址两用。6.2 工作区已按 Q9 拆开：`xm.battle.client-advertise-host`（环境变量 `XM_BATTLE_CLIENT_ADVERTISE_HOST`，空 = 取 `xm.advertise-host`）只进票据与目录的 `client_host`，
  `rpc_host` 仍用 `xm.advertise-host`（`xm-battle/src/main/resources/application.yaml:47`、`:61-65`；`BattleIdentity.java:13`）。整栈剩下的事是在 compose 里给 xm-battle 配上这个变量（宿主可达的地址），否则宿主上的 robot 连不上直连，单 zone 的 `battle-smoke` 也一样卡在这里；
  ② xm-gate-z2 在 compose 里的 `xm.dubbo.match-url` 要指向 `tri://xm-match:20888`（本机切片的缺省 `127.0.0.1` 在容器网络里不成立），与 zone 1 的 gate 同一个值。
- **Q23 要不要用基线 Go robot 跑 Java（跨版本验收）？** 推荐**可选、不阻塞**：本机没有 Go；7.1b 之后在 GitHub Actions 上加可选 job（§10.9）。Java robot 的 OK 行是 6.5 的验收证据。
- **Q24 跨区 robot 的「大厅 150」（Z7）依赖 6.3，6.3 推迟怎么办？** 推荐 6.5 排在 6.3 之后，Z7 必选；6.3 的结算传输若推迟，Z7 改记观察、不判失败，PARITY 注明。
- **Q25 1V1 要不要读归属区？** 推荐**不读**：两版都只用位置记录（§2.3），加了反而会在访客 zone 里错拒。
- **Q26 要不要等 5.4 的 X16？** 推荐**不等**：只影响 robot Z1 的一条可选断言，X16 合入后再打开。
- **Q27 终局预算 120 s 比房间期限 300 s 短，会不会误判？** 推荐**保持 120 s**（基线实测 8–24 回合、≤ 49 s），失败时报告回合数；真出现抖动再放宽到 310 s。
- **Q28 跨 zone 配表指纹不一致怎么办？** 推荐跟 6.4：缺省 warn；切片共用 `config-data` 不会不一致，只补一条「两侧指纹不同」的组件用例。

---

## 10 测试计划与 robot

### 10.1 命令（`AGENTS.md` §4）

```bash
./mvnw -B -pl xm-match -am test
./mvnw -B -pl xm-match -am test -Dxm.it.redis=redis://127.0.0.1:6379   # Lua；本机用 D:/work/.tools 的 Redis 进程（with-backends.sh 包住），CI 用 ci-spec §4.4 的 compose infra（不用 services:）
./mvnw -B -pl xm-battle,xm-discovery -am test                          # 同号碰撞用例
```

动了 proto 之后先 `clean install`。测试方法名用中文。**没有运行证据时不得声称通过**：交付说明附 `-pl xm-match -am test` 与 `-Dxm.it.redis` 的结果、两个 robot 场景的 OK 行。

### 10.2 纯函数（缺省执行）

| 测试 | 覆盖 | 对照基线 |
|---|---|---|
| `SpectateRulesTest` | `roomMayBeCreating` 五个分支：已公开；`created_at = 0`；窗口内；窗口外；检查时刻早于创建时刻（时钟偏斜）。`clampLimit`：0 → 20、1 → 1、50 → 50、51 → 50。`staleCutoff = now − 360 000`。`summaryOf` 保留成员顺序、不认识的 mode 原样。标记值编解码（无符号 battle_id、非法值） | `wbcw_test.go:55-174`；`sp_test.go:582`、`:809` |
| `MatchTipsTest`（扩充） | §3.2 的 9 条 `parameters[0]` 逐字节（半角逗号） | — |
| `BattleRoutingsTest` | 在线目录 → 路由：session、gate 节点与实例、zone；scene 字段为 0 / 空 | `sp_test.go:294` |
| `MatchBudgetsTest`（扩充） | §5.3 的不等式；`OBSERVER_RPC_TIMEOUT_MS` 进 matched TTL 公式后 1 / 2 / 5 / 10 人仍是 42 / 48 / 66 / 96 | `gsi_test.go:232` |
| `ZoneMixTest` | 快照 zone {1, 2} → `cross`；{1, 1} → `single`；标签不含 zone 值 | `gather.go:509-528` |

### 10.3 组件（替身：内存版 `SpectateStore` 与票据存储、`ObserverDialer`、`BattleLockReader`、在线目录、落点、手动时钟）

**`WatchBattleServiceTest`**（逐条对照基线；§3.1 每一行都要有：tip、`parameters[0]`、outcome、调没调 battle、标记状态）：

| 基线用例（`sp_test.go` 行号） | Java |
|---|---|
| RejectsQueuedPlayer `:236`、RejectsPlayerInBattle `:253`、RejectsOfflineObserver `:268`、RandomModeWithoutBattles `:280`、WithoutIdentity `:512`、GarbageWatchingMarkIsHealed `:527` | 照移 |
| BindsObserverWithGateOnlyRouting `:294` | 路由取在线目录、zone = 在线目录的 zone（W11）；`observer_name = account`；标记 TTL 360 s |
| ExplicitMissingRoomEvictsIndex `:326`、RandomModeEvictsFinishedOnlyBattle `:347`、RandomModeSwitchesToAnotherBattleAfterMissingRoom `:605`、RandomModeSkipsRecordlessMember `:640`、ExplicitRecordExpiredEvictsIndexWithoutRpc `:565`、NonMissingTipKeepsIndexAndDoesNotRetry `:661` | 照移 |
| RpcFailureKeepsIndexRollsBackMark `:367` | **拆成三例**：建连失败无换实例证据 → 删标记；超时 → 保留标记（W4）；建连失败且同号换实例 → 剔除、随机换场（W5） |
| ServiceUnavailableRollsBackMark `:898` | 照移（1003 是明确拒绝） |
| DoubleCheckSelfEvictsOnConcurrentQueue `:386`、…OnConcurrentBattleLock `:763` | 照移，都回 16014（BW2）；Remove 异步发出；另加「复查读锁出错 → 自我清退、16014」「复查读票出错 → 成功」 |
| RewatchEvictsPreviousBattle `:410`、SameBattleRewatchDoesNotRemoveObserver `:431` | 照移；换场 Remove 在 Add 之前完成（替身记录调用顺序） |
| AlreadyWatchingWhenMarkStolenBeforeAcquire `:694` | 照移；测试缝 `beforeAcquireWatching`（同 `queue.go:188-193`） |
| RejectsNonOnlineSessionState `:716` | 改为「在线目录没有条目」 |
| UsesSessionPlayerIdOverRequestBody `:739` | 加强：会话为 0 时请求体也被忽略（W12） |
| RejectsNonNumericGateId `:543` | 改为「在线目录条目缺 gate 实例 → 16004」 |
| `wbcw_test.go`：CreateWindow `:55`、PublishedDuringRPCKeepsRecord `:96`、RecordRewrittenToRetryNodeKeepsRecord `:142`、ExplicitMissingRecordNeverDeletesRecordKey `:175` | 照移；`:142` 另断言 attempt 守护（W7） |
| `wbcw_test.go` ActiveLookupFailureKeepsRecord `:112` | 改为「原子读失败 → 16004，落点不动」（W6） |

Java 独有：W2（入口检查之后才建出票据 → 16014、不调 AddObserver）；W3（两个并发请求，后者的标记不被前者的回滚删掉）；S_W_ACQUIRE 重放回 ok；
W10（换场前剩余预算 < 2.2 s → 16004 且旧标记保留；Add 前剩余 < 1 s → 16004 且标记已回滚；在途已满 → 16004 / overloaded）；损坏落点 → 16004、计 anomalies、不剔除；
`already_watching` 只在 16016 时计数（W14）。

**`WatchableListServiceTest`**：最新在前；剔除过期、缺记录、非法成员；读记录出错 / 损坏只跳过；读索引失败 → `tip_id = 1003`；不回填；摘要字段与名字顺序；`limit` 收口。
对照 `sp_test.go:206`、`:582`、`:782`、`:854`。

**`SpectateGatherHooksTest`**：有标记有落点 → 发往**落点地址**的 `remove(reason = enter_gather)` → 按值删标记；无落点 → 只删；值非法 → 删；读落点出错 → 不发 RPC、删；`MGET` 出错 → 全部跳过、标记保留；
RPC 失败 / 超时开局照常；每人至多 3 s；`onStarted` 只在 attempt 一致时 ZADD，失败只计数。对照 `sp_test.go:448`、`:481`、`:824`，`gsi_test.go:244`、`:463`。

**`GatherPipelineTest`（6.4，追加）**：五个入口都在第一次 prepare 之前调 `beforePrepare`；失败路径不调 `onStarted`；落点在建房期间被剔除后，补写 + 公开能把它恢复（`gsi_test.go:440`）；gather 失败后观众仍被清退（`sp_test.go:867`）。

**`SpectateSweeperTest`**：只摘过期成员（`sp_test.go:192`）；某一轮抛异常，下一轮照常；gauge 采样。

**`ObserverDialerTest` / `PlacementDialerTest`**：四种结局的判据（建连失败 + 换实例 / 同实例 / 目录缺席 / 目录读失败；超时；连上后断开；6.2 在途超限异常）；179 与观战共用后 `BattleTicketReissueTest` 不回归。

**`CrossZoneGatherTest`**（钉住 Z1–Z3、Z11、Z12）：
- A 在 zone 1、B 在 zone 2 的位置记录，`xm:nodes:scene:1` 与 `:2` **都有 1 号节点**、实例与地址不同 → prepare 分别打到两个端点；Cancel 发回 Prepare 时的端点；
- CreateBattle 的两个快照 `routing.zone_id` 是 1 和 2；`gather_zone_mix_total{mix="cross"}` 加 1；
- B 排队之后旅行到 zone 3（位置 `o`@z3）→ prepare 打到 zone 3；位置是 `l` → 本轮跳过（M12）；
- 观众在 zone 2、参战者在 zone 1：163 成功，观众路由的 zone = 2、gate 节点号 1。

**同号碰撞（其它模块，钉住 Z5–Z7）**：`PlayerPushesTest` / `PresenceLobbyAnnouncerTest`：presence (zone 2, gate 1) → 发布到 `xm:gate-push:2:1` 而不是 `:1:1`；
`DubboSceneBattleEventsTest`、`SettlementOutboxTest`（6.3）：路由 (zone 2, 节点 1) 不命中 zone 1 的 1 号节点，实例不符回落定位器；`BattleTicketsTest`（xm-common）：载荷无 zone 字段，跨实例拒绝。

**`QueueServiceTest`（6.4，追加，Z2）**：`SessionContext.zone_id = 1`、位置记录 zone = 2 → 票据 `zone_id = 2`；位置 `l` / `x` / 缺失 → 16020。对照 `cz_test.go:108`、`:121`。

**`MatchClientMessageServiceTest`（6.4，扩充）**：163 走虚拟线程、164 走 `match-worker`；过载应答（163 in-band 16004，164 信封 1003）；M22 的临时应答已移除。

### 10.4 真 Redis（`-Dxm.it.redis`，缺省跳过）

- 用例类带 `@EnabledIfSystemProperty(named = "xm.it.redis", …)`，用 DB 13、开头清自己的键（同其它模块：CI 里各模块串行、共用 DB 13，`ci-spec` §4.4 第 5 步）；它会进 `TestReport --require-it-executed` 的计数（§4.4 第 6 步），整类被跳过即判红。
- 每段脚本单独验证；**每段可变脚本执行两次，结果不变**（模拟 Redisson 重发）。
- S_W_ACQUIRE：有票 → queued；同值重放 → ok；别的值 → busy。S_W_RELEASE 只删自己的值。
- S_W_PICK：不返回过期成员；全部过期返回空；1 万次挑选分布大致均匀；`r` 取 0 与 0.999… 的边界。
- S_W_EVICT 四种模式；S_W_PUBLISH 的 attempt 条件；S_W_SWEEP 只摘成员不动落点。
- 观战键、票据、落点同 slot（`CLUSTER KEYSLOT` 相等）。
- 移植 `TestSpectateRegisterLoadRemoveRoundTrip :127`、`TestPickRandomBattleNeverReturnsStaleOrGarbage :161`、`…EvictsAllBadMembersAndReportsNone :177`、`…GivesUpAfterThreeAttempts :839`。
- 同号碰撞：`ScenePreparer` 与 `SceneAssetLocator` 在真 Redis 上重复 §10.3 的用例；移植 `TestMatcherMixesZonesAndShortensMatchedTTL`（`cz_test.go:153`）。

### 10.5 Dubbo

xm-match → xm-battle 的一条真 Triple 回环：带 MAC 调 `addObserver` / `removeObserver`；缺 MAC 被拒；`PlacementDialer` 区分建连失败与超时（对一个没人监听的端口、一个故意挂起的提供方各测一次）。

### 10.6 本机切片

- 单 zone：`start-slice.sh`（6.2 起 xm-battle、6.4 起 xm-match，6.3 的 scene）→ `battle-smoke`。
- 双 zone：`source D:/work/.tools/env-local.sh; XM_ZONES=2 tools/local/start-slice.sh`（gateway 两个区一起播种，`zt-spec` §5.13）→ 等 zone 2 的 `xm_scene_channels{state="active"} ≥ 1`
  → `java -jar xm-robot/target/xm-robot-*.jar battle-cross-zone --zone 1 --visit-zone 2`；整次运行用 `bash D:/work/.tools/with-backends.sh …` 包住（Redis / MySQL / Kafka 有界启停）。
- 回归：`XM_ZONES=1` 下现有场景与 6.4 `battle-smoke` 不受影响。

### 10.7 robot：`battle-smoke` 观战段（替换 `m-spec` §15.5 第 10 步）

- **前置**：切片带 xm-battle、xm-match、6.3 的 scene；`XM_RUN_MODE=dev`；Kafka 就绪。
- **账号**：观战段用自己的四个新号 SA（参战）、SB（指定观战）、SC（列表 / 随机观战）、SD（第二局参战），run-tag 新建，与 6.4 的 A / B / C 互不牵连。观战段放在 6.4 各段**之前**跑。
- **节奏**：同一会话同号请求间隔 ≥ 350 ms（每号每秒 3 条）；等 177 与 161 各 15 s、等 166 120 s（同 `bss.go:54-60`）；过渡态每 1 s 重试、上限 20 s。
- **客户端件**：复用 6.2 的 `BattleDirectConnection`（按消息号计数、能检测 FIN）与大厅 177 handler（`bn-spec` §13.8）；新增 163 / 164 的请求与应答解析；开头抓一次 18113 作为指标基数。
- **执行顺序**：S0–S11 整段在 6.4 第 1 步**之前**跑；S12 插在 6.4 第 9 步里（它要用切磋局）；S13 在整个场景末尾（6.4 第 11 步之后）。6.4 第 10 步（M22 的临时应答）删除。
- **战斗 X 的寿命（S1–S8 的时间预算）**：SA 不开自动时，X 每 6 s 按回合超时结算一次，未出手的一方执行默认普攻（`xm-battle-engine/.../TurnBattleEngine.java:59-60`、`:527`；第一回合恒 6 s，`bn-spec:470`）。
  基线同一场 PVE 开自动要打 13–21 回合（E3），所以 X 不开自动大约能活 13 × 6 ≈ 78 s 以上。S1 收到 177 到 S8 结束**合计预算 60 s**（各步的等待上限照旧，但总和超出即 FAIL `step=S<n>-budget`）；
  S9 之前 SA 的直连上一旦出现 150（X 提前结束）→ FAIL `step=S<n>-x-ended-early`，报告当时的回合数，便于判断是表数值变了还是脚本太慢。

| 步 | 动作 | 断言 | 对照 |
|---|---|---|---|
| S0 | SC 发 164 `{limit = 0}` | 条数 ≤ 20；`created_at_ms` 不增；全部在 [now − 365 s, now + 5 s]（过期分界在服务端按 Redis `TIME − 360 s` 判，robot 墙钟与之有毫秒到秒级的差，两头各留 5 s） | `lw.go:39-80` |
| S1 | SA 发 157 `{mode = 4 PVE_SOLO, config = 1}` → 177 / 143（战斗 X）→ 直连，**不开自动**（屏障，同 `bss.go:70-77`）；随即 SA 发 163(X) | SA 的 163 → `{16014, 匹配中无法观战}`（持票，票检查先于锁检查） | `wb.go:74-79` |
| S2 | SC 发 164 `{limit = 50}`（X 不在列表里时每 1 s 重试、上限 5 s：177 / 143 在 createBattle 期间就已推出，而公开在 gather 第 5 步的补写之后，两者有先后），再发 `{limit = 1}` | X 在列表里：`mode = 4`、`battle_config_id = 1`、`player_names = [SA 的角色名]`、`created_at_ms ∈ [发 157 时刻 − 5 s, 收到 177 时刻 + 5 s]`；`{limit = 1}` 时条数 ≤ 1 | `gather.go:281-286` |
| S3 | SB 发 163(X) | 应答 `battle_id = X`、**没有** `error_message`；大厅收到 177 `{role = 2, battle_id = X}`，`expire_at_ms` 等于 SA 的 177，`token_signature` 匹配 `^[0-9a-f]{64}$`；直连上第一帧是握手应答，**然后**才是 161 `{observer_count = 1}`，所有 actor 的冷却为空、`self_items` 为空 | `bss.go:181-233`；`bn-spec` §5.5 |
| S4 | SB 再发 163(X)（重看同一场） | 成功；SB 的**直连**上再收到一条 177（有活直连时大厅公告直写，`room.cpp:1410-1430`；`BRS.java:796-800`），与第一条**逐字节相同**（payload 确定、HMAC 确定，`BattleTicketIssuer.java:50-79`），随后再收到一条 161；SB 的大厅连接上 1 s 内**没有**新的 177；直连上 1 s 内**没有** 166 | `wb.go:103-106`；`room.cpp:840-848`；`BRS.java:386-393` |
| S5 | SB 发 179(X) | `assignment` 与 177 逐字节相同（role 2） | `m-spec` §4.3 |
| S6 | SB 发 157 `{mode = 3 1V1, config = 本轮独有的值（900000 + runTag 低 16 位）}`；再发 163(0)；再发 148(该票) | 157 受理（观战中可以排队，BW3）；1 s 内 SB 的直连上**没有** 166；163 → `{16014, 匹配中无法观战}`；148 之后 153 → NOT_QUEUED。用独有 config 保证不会成局 | `gather.go:228-234` |
| S7 | SC 发 163(随机且不存在的 id) | `{16018, 该战斗不存在或已结束}` | `wb.go:171-185` |
| S8 | SC 发 163(0)（随机；遇 16017 每 1 s 重试、上限 10 s——每次失败都会懒剔除已结束的残留场） | 成功，`battle_id = Y ≠ 0`；SC 收到 Y 的 177(role 2)，直连后收到 161（Y = X 时 `observer_count = 2`）；SC 在直连上发 165 → 应答成功，然后 FIN，**没有** 166 | `bss.go:181-190`；`room.cpp:931-967` |
| S9 | 放行屏障，SA 开自动 | SA 收到 150；SB 的直连上收到 ≥ 1 条 158（冷却为空），然后 166 `{FINISHED(1), outcome = SA 那条 150 的 outcome}`，然后 FIN；SB 大厅连接上 139 / 158 / 161 / 166 的条数都是 0 | `bss.go:235-260` |
| S10 | SB 再发 163(X)；SC 发 164 | SB → `{16018, 该战斗不存在或已结束}`；列表里没有 X | `wb.go:238-254` |
| S11 | 开局清退：SD 发 157 PVE_SOLO（战斗 Z）→ 直连、不开自动；SB 发 163(Z) → 直连收到 161；**SB 发 157 PVE_SOLO** | SB 在 Z 的直连上 10 s 内收到 166 `{battle_id = Z, outcome = 0 ONGOING, reason = 3 REMOVED}`，然后 FIN；SB 大厅收到新战斗 W 的 177(role 1) / 143。**两条连接之间不断言先后**。之后 SD、SB 都开自动打完各自的局（否则 SB 带着锁） | `gather.go:228-234`；`room.cpp:897-929` |
| S12 | 16015：在 6.4 第 9 步的切磋战斗里（切磋不建票），A、B 都开自动之前，A 发 163(0)。**注意 A 在 6.4 第 8 步打过 1V1**：那一局的 ready 票（60 s，BW1）在切磋开局时可能还没过期，而票据检查排在锁检查之前，此时回 16014。所以：回 16014 时每 1 s 重试，直到「第 8 步收到 177 的时刻 + 62 s」；之后必须是 16015。切磋局不开自动时按 6 s 回合超时推进（默认普攻），新号 1V1 基线记录是 8–24 回合（`czm.md:273-285`；`PROGRESS.md:3486`、`:3556`），最短也约 48 s；而切磋开局时 ready 残留最多还剩 60 s 减去「第 8 步整局（开自动 2 s / 回合）+ 评分等待 + 第 9 步前半段」，通常不到 40 s，等得过来；等待期间 A 或 B 收到 150 → FAIL `step=S12-battle-ended` | 最终 `{16015, 战斗尚未结束,无法观战}`；记录是否先见到过 16014（报告字段 `s12_ready_residue=0/1`，用来观察 C1 修好之后的变化） | `wb.go:74-89`；`join.go:234-246` |
| S13 | 场景末尾再抓 18113，与开头的基数相减 | `xm_match_watch_battle_total{outcome="ok"}` 增量 ≥ 4（S3、S4、S8、S11）、`not_found` ≥ 2（S7、S10）、`queued` ≥ 2（S1、S6）、`in_battle` ≥ 1（S12）；`xm_match_spectate_evictions_total{reason="enter_gather",result="removed"}` 增量 ≥ 1 | — |

- **输出**：沿 6.4 的 `BATTLE_SMOKE_OK battle_id=… a_turns=… a_direct_turns=… pvp_battle_id=… challenge_battle_id=…`，追加 `spectate_battle_id=… b_spectate_turns=… b_direct_spectate_turns=… removed_ok=1`
  （`b_*` 两个字段名同 Go，`bss.go:277-278`）以及 `s12_ready_residue=0|1`；失败 `BATTLE_SMOKE_FAIL step=S<n>-… reason=…`。
- **不在 robot 里验**：16016（只在真并发下出现）、16017（需要全服没有可观战战斗）、16019（Java 里需要「会话绑定了玩家但在线目录没有条目」），由 §10.3 覆盖。

### 10.8 robot：新场景 `battle-cross-zone`（`RobotOptions.Scenario.BATTLE_CROSS_ZONE`）

- **入口**：`java -jar xm-robot.jar battle-cross-zone --zone 1 --visit-zone 2 [--match-admin-url http://127.0.0.1:18113]`；`--visit-zone` 缺省 2，与 `--zone` 相同 → `UsageException`（对齐 `bsc.yaml:32`）。
  Z8 查评分要 6.4 的 `MatchAdminClient`（`--match-admin-url`，环境变量带 `XM_ADMIN_TOKEN`；`m-spec` §9.10 / §15.5），缺 token 时 FAIL `step=Z8-admin-token`，不静默跳过。
- **账号**：`<prefix>xz<runTag>_{a,b,c}`，每轮新号（命名惯例同 `CrossNodeScenario.accountName`）。A 首登 zone 1，B、C 首登 zone 2：评分都从 1500 开始，没有 GO-5 重定向残留。
- **时限**：开战 30 s、直连 10 s、观战 15 s、终局 120 s（同 `bss.go:54-60`、`bdc.go:51`）；节奏同 §10.7。

| 步 | 动作 | 断言 | 对照 |
|---|---|---|---|
| Z0 | `GET /api/server-list` | 区 1、2 都 OPEN；缺区 → FAIL `step=preflight`，原因写「需要 XM_ZONES=2 切片」。不跳过：本场景就是验这件事 | `zt-spec` §11.11 T0 |
| Z1 | 并发：A assign-gate(zone) 登录进场；B、C assign-gate(visit-zone) 登录进场 | A 与 B 的 gate 端点不同（切片里是 11000 / 11010）；X16 已合入时另断言角色列表的 `zone_id` 分别是 1 / 2 | `bsc.go:120-166` |
| Z2 | 抓一次 18113 | 记下 `xm_match_gather_zone_mix_total{mode="MATCH_MODE_1V1",mix="cross"}` 与 `xm_match_watch_battle_total{outcome="ok"}` 的基数（mode 标签取 6.4 的枚举名） | — |
| Z3 | A 发 157 `{mode = 3, config = 1, zone_id = A 的区}`；**收到 A 的回包之后** B 再发同样的 157 | 两个回包 `error_code = 0`、ticket 匹配 UUID 格式；不得出现 16000 / 16001 / 16020。这样 A 一定是锚点 | `bsc.go:244-252` |
| Z4 | 两侧等大厅公告（30 s） | 收件箱里 177 的下标在 143 之前；两侧 battle_id 相同且非 0；两侧 177 的 `host:port` 相同；解析两张 `BattleTicketPayload`：`battle_node_id`、`battle_instance_id` 相同，`player_id` 各是自己，`role = PARTICIPANT`；143 的阵营里 A 在 0 队、B 在 1 队 | `pb.proto:135-142`；`room.cpp:609-628` |
| Z5 | A、B 各自直连（握手 battle_id 一致、140 补拉成功），**都不开自动**；C（zone 2）发 163(该 battle_id) | C：应答 `battle_id` 正确、无 `error_message`；177(role 2) 经 zone 2 的 gate 到达 → 直连 → 161 `{observer_count = 1}` | `czm.md:4`；§2.8 |
| Z6 | A、B 在直连上发 162 开自动 → 等直连 150（120 s） | 两侧 `outcome` 相同且 ∈ {1, 2, 3}；直连回合数 ≥ 1，且大厅连接上的 139 条数 = 0；C：≥ 1 条 158，最后 166 `{FINISHED, outcome 同上}` | `bsc.go:264-305`；`DAP.cs:41-43` |
| Z7 | 两侧各等大厅 150（scene 应用结算后推，`sb-spec:333`），以直连 150 为起点 25 s | battle_id 相同。证明结算回到了**各自 zone** 的 scene（Z6 / Z7 不变量）。6.3 推迟时按 Q24 降级 | — |
| Z8 | 读 `GET /admin/match/dev/rating/{A,B}`（收到 150 后最多等 10 s） | `games` 各 +1；胜负局且 `total_rounds < 30` 时 Δ 互为相反数、Δ 的绝对值 = 16；平局或 ≥ 30 回合时 Δ 都是 0 | `m-spec` §5、§9.10 |
| Z9 | 第二局：A、B 按 Z3 再排一次（遇 16000 每 1 s 重试、上限 20 s；**不得** 16001）→ 重复 Z4、Z6、Z7（不带观众） | 第二局 battle_id ≠ 第一局；两侧回合数 ≥ 1；`games` 再各 +1。覆盖「0 血带入、ready 残留、迟到的旧 150」三条基线教训 | `czm.md:281` |
| Z10 | 再抓 18113 | `mix="cross"` 增量 ≥ 2；`watch_battle_total{outcome="ok"}` 增量 ≥ 1（指标是全局的，只取下界） | `czm.md:267` |
| Z11 | 三人 LeaveGame | 输出 `CROSS_ZONE_MATCH_OK battle_id=… zone_a=1 zone_b=2 a_turns=… b_turns=… a_direct_turns=… b_direct_turns=…`（字段同基线）后接 ` observer_zone=2 c_spectate_turns=… second_battle_id=…`；失败 `CROSS_ZONE_MATCH_FAIL step=Z<n>-… reason=…` | `bsc.go:16-20` |

`xm-robot/README.md` 写清前置：`XM_ZONES=2`、dev 模式、两个区的 gate 都在 gateway 区服列表里。

### 10.9 跨版本验收（可选、不阻塞；不改 Go robot 代码）

- 本机没有 Go，只在 GitHub Actions 上做可选 job（7.1b 之后，Q23）：mmorpg 的检出要包含 `robot/`（含 `robot/config/`、`robot/generated/`，本机稀疏克隆里没有）。
- **Go `battle-smoke`**（单 zone、**全新**的整栈）：复制 `robot/etc/battle_smoke.yaml`，`gateway_addr: http://127.0.0.1:18081`；口令 = `XM_LOGIN_DEV_PASSWORD`（或把整栈口令设成配置里的 `123456`，同 `zt-spec` §11.10）；
  `robot_` 前缀在 Java 开发白名单里。期望 `BATTLE_SMOKE_OK`。B 的随机观战断言「挑中的就是 A 那一场」（`bss.go:181-184`），所以整栈上不能有别的战斗或已结束的残留场——全新整栈天然满足。
  B 收到 A 的 143 就发 163(0)、不重试（`bss.go:166-187`），而 143 在 createBattle 期间推出、公开在 gather 第 5 步之后：两版都有这个毫秒级竞态（基线同样是 `gather.go:381-389` 在建房回包之后才 ZADD），偶发 16017 → B 等 177 超时属于基线 robot 的已知脆弱点，重跑一次再判。
- **Go `battle_smoke_cross_zone`**（`two-zones` profile）：复制 `bsc.yaml` 改 `gateway_addr`，期望 `CROSS_ZONE_MATCH_OK`。固定账号 robot_9003 / 9004 在全新整栈上没有评分漂移与位置残留；
  本机复跑时要等位置记录过期（在线 60 s / 租约 30 s），否则会被 GO-5 送走（`zt-spec` §11.12）。
- 失败时先按 §2.6 分清是 Java 还是基线 robot（基线收缩后没有绿色记录）的问题。

### 10.10 CI

- 单测与 `-Dxm.it.redis` 用例进现有 `ci.yml` / `integration.yml`（`ci-spec` §4）。
- 整栈：`battle-smoke` 随 6.4 的批次进第二期之后；`battle-cross-zone` 在 `--profile two-zones` 下、第三期之后加入（Q22）。两者都以 `ci-spec` Q9（battle 通告地址拆分）为前提：6.2 工作区已提供 `xm.battle.client-advertise-host`，compose 里配上即可（Q22）。

### 10.11 交付清单与登记

- **`PARITY.md` 新增行**：
  - 「观战（match 侧：163 / 164、观战标记、可观战索引、开局清退）」：Java 模块 xm-match、xm-discovery；状态「已对齐」；附 W1–W14、BW1–BW10；mmorpg 侧状态「已有」；
    「mmorpg 待做（可选）」：W2–W5（W5 与 M16 一并）、E1–E3 注释勘误；两版同改候选 C1、C2（改 mmorpg 需用户同意）。
  - 「跨区 1V1 匹配」：状态「已对齐」；附 Z1–Z12、X1–X7、robot 场景名与 OK 行；注明 xm-match 不分 zone 部署；「mmorpg 待做（可选）」：基线 robot 补强（§2.5 的弱点）、收缩之后的跨区冒烟复验。
- **`PARITY.md` 更新行**：6.4 的匹配行（M22 关闭）；6.2 的 battle 行（「观战 match 侧 6.5」改为已接入）。
- **`roadmap.md:85`**：6.5 打勾、写提交号；注明 battle-spectate 的房间侧已在 6.2（Q1）。
- **盘点 java 列**：`inventory/combat.md:264`、`scene-manager-match.md:269`、`contract-robot.md:416`、`:428` 改为 done（模块、场景名、提交号）；勘误 E3–E6 同批改。
- **`arch`**：见 §4.11。**`m-spec`**：§8.1 / §9.3 / §9.9 的 163 / 164、§9.6 第 4 步的 `created_at_ms` 来源、§15.5 第 10 步指向本稿 §10.7。**`zt-spec`**：§5.13 共用行。
- **`tech-stack.md`**：无新依赖，不改。
- **证据**：`-pl xm-match -am test`、`-Dxm.it.redis` 的结果；`battle-smoke`（含观战段）与 `battle-cross-zone` 的 OK 行；Go robot 跨版本验收的结果（没跑的写明原因）。没有运行证据，不写「通过」。

---

## 附录 修订记录

### A.1 完整性评审（第一轮，2026-10-05）

**完整性评审（2026-10-05，只改本文件）**。通读全文后回到基线（`D:\work\mmorpg` @ `26ceb70ca`）、客户端（`D:\work\mmorpg-client` @ `a8577c7`）、Java 工作区和相关规格逐条核对。
抽查了 60 多处引用，以下这些核对无误，不再逐条列出：`wb.go` 全文的判定顺序与行号、`sp.go` / `lw.go` / `pc.go:113-121` / `queue.go:188-208`、`:332-380` / `join.go:18-21`、`:143-167`、`:234-246`、
`gather.go:213`、`:219`、`:233`、`:264`、`:284-286`、`:316`、`:389`、`:399`、`:513`、`matcher.go:204`、`yaml:3`、`:49`、`:57`、`:72`、`:138`、`rcfg.go:29`、`:42-45`、`fwd.go:170-184`、
`room.cpp:64`、`:774-967` 各分支、`ms.proto:36-37`、`:151-188`、`pb.proto:66-83`、`:135-142`、`:161-171`、`bd.proto:12-19`、`:172-177`、`errors.go:65-84`、`metrics.go:86-90`、
`sp_test.go` / `wbcw_test.go` / `gsi_test.go` / `cz_test.go` 的全部用例行号、`bss.go` / `bsc.go` / `bsc.yaml` / `msr.go:22-26`、`:49-67`、`DAP.cs:37-50`、`:543-546`、`BattleUiStyle.cs:28`、
`SC.cs` / `SP.cs` / `BC.cs:684-693` 的引用、`tbs.md:272-275`、`:303`、`czm.md:90-103`、`PROGRESS.md:3474-3486`、`:3556`、`:6090`、`:6199`、P2-07；
Java 侧 `BRS.java:320-424`、`RoomConstants.java:33`、`BattleNodeServiceImpl.java:146-157`、`:278-283`、`DevRoutingResolver.java:220-227`、`PlayerPresenceDirectory.java:145`、`RedisKeys.java:16-55`、`:351`、
`NodeTypes.java:11-36`、`PlayerPushes.java:130`、`SceneAssetLocator.java:21-35`、`client_call.proto:16-17`、`match_error_tip.proto`、`tip_text.json:70`、`:80-85`、`message_id.txt`；
`m-spec` :74、:190-191、:393、:837-841、:966、:1048、:1057、:1119，`bn-spec` §5.5、Q1、§7.10，`sb-spec` :333、§7.15、§7.16，`zt-spec` :831、:1201、§11.11 T0，盘点各行号，`roadmap.md:85`（`bn-spec` / `sb-spec` 此后有改动，行号以 A.2 的 E-1 为准）。

**引用更正**

| # | 位置 | 原文 | 更正 |
|---|---|---|---|
| R1 | §4.4 末 | `bn.proto:57` | 基线是 `bn.proto:54`，`:57` 是 Java 同步副本的行号 |
| R2 | §4.9 | `arch:607-609` | 那是旧行号（`m-spec` 引的也是它）；第二轮已改为按节名引用，见 A.2 E-2 |
| R3 | §3.7 | D9 `czm.md:101` | D9 在 `:100`，`:101` 是 D10 |
| R4 | §3.2 | 16004 的出处缺失 | 补 `match_error_tip.proto:20` |
| R5 | §8.5 E1 | 只列了 `tbs.md:305-323` | 「gather 成功后写」实际在 `tbs.md:338`（§10.4 键表），数据流段里没有这句 |
| R6 | §0.3、§4.10、§4.11 | 「`zt-spec` §5.13 共用行加 xm-battle、xm-match」 | `zt-spec:834` 的共用行已经含 battle，只需加 xm-match |

**遗漏与错误的修正**

| # | 问题 | 修正 |
|---|---|---|
| F1 | **robot S12 会随机失败**：A 在 6.4 第 8 步刚打完 1V1，ready 票据（60 s，BW1）在第 9 步切磋开局时可能还在，而 163 先查票据后查锁，会回 16014 而不是 16015 | S12 遇 16014 每 1 s 重试到「第 8 步 177 + 62 s」，之后必须 16015；论证了不开自动的切磋局活得过这段等待（默认普攻、6 s 一回合、基线最短 8 回合）；新增报告字段 `s12_ready_residue` |
| F2 | **robot S1–S8 依赖战斗 X 一直活着，但没有时间预算**；基线 `bss.go:70-72` 的「PVE 秒杀约 100 ms」已经过时，不能拿来估计 | 写明 X 不开自动时的推进方式（6 s 回合超时 + 默认普攻，`TurnBattleEngine.java:59-60`、`:527`）和实际寿命（基线 13–21 回合）；S1–S8 合计预算 60 s；X 提前结束时 FAIL `S<n>-x-ended-early`。E3 补上这条勘误 |
| F3 | **robot S2 有竞态**：177 / 143 在 createBattle 期间就推出，公开（ZADD）在 gather 第 5 步之后，紧接着发的 164 可能还看不到 X | S2 加「不在列表就每 1 s 重试、上限 5 s」；§10.9 注明 Go `battle_smoke` 的 B 侧（收到 143 就随机观战、不重试）有同一个基线竞态 |
| F4 | S0 的时间窗卡在 360 s 边界上，robot 墙钟与 Redis `TIME` 的差会造成误判 | 改为 [now − 365 s, now + 5 s] |
| F5 | §10.1 写「CI 用 Redis service」，与 `ci-spec` §4.4（compose infra，明确不用 `services:`）矛盾；也没提本机已有 Redis 进程 | 改正；§10.4 补 DB 13 约定和 `TestReport --require-it-executed` 计数 |
| F6 | 6.5 改完之后，`m-spec` §8.1 的 163 / 164 两行应写成什么，没有说明（会话无玩家、解析失败、依赖故障、过载、未预期异常） | §4.11 给出两行全文；§3.1 加 J2 行（未预期异常回信封 1003，同 `m-spec` §9.9 第 5 条） |
| F7 | 164 的 `RBatch` 整批失败时怎么回，没有定义（照字面会变成 1003，与基线逐条 GET 失败只跳过不一致） | §4.5：整批失败等同逐条读失败，回变短的列表、不回 1003，计 `anomalies{record_read_failed}`；剔除改为组好应答之后异步发出（不可见） |
| F8 | S_W_ACQUIRE 被 Redisson 重发时，首轮可能已写入标记，第二轮却回 `queued`，标记残留 | `queued` 分支先尽力按值释放本次标记，再回 16014 |
| F9 | 复查（AddObserver 成功后）只剩约 0.2 s 预算，读锁等超时的结果没有定义 | 并行读；等超时按「读失败」处理，锁 → 有锁 → 16014（与 BW2 同方向），归入 W10 |
| F10 | 开局清退「每人至多 3 s」与 Java 的 Redis 最坏 4.2 s 合起来做不到 | 每人一个 3 s `Deadline`，读落点与 RPC 都算在内；`MGET` 与删标记算在公式的 10 s 余量里，同基线口径 |
| F11 | W5 写「死节点上的场次…变成不存在或已结束」，范围说大了 | 只适用于「号已被别的进程接手」的死节点；号没被接手时仍是「当前无法观战」、不剔除，与基线相同 |
| F12 | W10 写「不再先收信封 1003 再收 177」，不准确 | AddObserver 超时后 battle 仍可能推 177（同基线 B-s5）；补上客户端 `BattleDirectLink` 对任何完整 177 都建连、靠相位与 Superseded 收场的依据 |
| F13 | 落点 `player_names` 的来源没写清 | 写明取快照里的 `player_name`（角色名，不是账号），同 `gather.go:281-285`；`created_at_ms` 的取值时刻仍在 Prepare 之后 |
| F14 | 失败路径漏项：6.4 发号租约丢失、xm-match 停机 / 崩溃时的 163 | §7.2 补两条；§4.11 在停机序列里加「有界等待在途 163 → 停清扫器」，加配置校验 |
| F15 | 换场 Remove 超时之后，随机模式又挑中同一场的残余风险没有写 | §7.1 第 4 条写明这个风险（与基线相同），以及不加「本轮排除旧场」的理由（会改变客户端可见的选场分布） |
| F16 | Q22 / §10.10 没提整栈的前置条件：`ci-spec` 风险 15 / Q9（battle 通告地址一址两用），以及 xm-gate-z2 在容器网络里的 match 地址 | 补上 |
| F17 | 跨区 robot 的 Z8 要用 6.4 的管理接口，入口没有列出 `--match-admin-url` / `XM_ADMIN_TOKEN` | §10.8 入口补上；缺 token 时 FAIL，不静默跳过 |
| F18 | §10.7 的执行顺序有歧义：说观战段在 6.4 之前，但 S12 在 6.4 第 9 步里，S13 在最后 | 写明 S0–S11、S12、S13 各在什么位置，以及 6.4 第 10 步删除 |

**核对过、维持原结论的点**：W1–W14 的客户端可见性标注；BW1 / BW7 照搬（两版同改候选 C1 / C2）；跨区不需要新的生产代码路径（Z1–Z12 都能落到 6.2 / 6.3 / 6.4 的具体机制上）；没有新增第三方依赖；线程所有权符合 `AGENTS.md` §3。

**说明**：`docs/reference/` 里没有匹配或观战的客户端契约页（`grep` 163 / 164 / WatchBattle 为空），所以本稿 §3 就是 6.5 客户端可见行为的唯一依据。是否从 mmorpg 补一页 reference，留给用户决定，不阻塞本批。

### A.2 编辑复核（第二轮，2026-10-05，只改本文件）

三份分区稿（match 侧观战盘点、跨区 1V1 盘点、Java 落地映射）重新对照本稿合并了一遍，§0.4 的 14 条裁决逐条回到代码复核，**结论都不变**。
复核依据：`wb.go:52-267`（判定顺序、复查里读票失败只记日志、读锁失败经 `pc.go:113-121` 回 `(true, err)` → 自我清退回 16014）；
`join.go:63-140` 与 `m-spec` §2.2 :235（PVE_SOLO 直接建 matched 票、开局后 ready 60 s，所以 S1 的 16014 成立）；
`m-spec` §2.3（148 对 matched 票静默成功，所以分区稿③的「PVE_SOLO 后发 148 取消」不成立，§0.4 #12）；`m-spec` §6.1（切磋不查也不自愈票据，所以 S12 的 ready 残留重试必要）；
`sp.go:236-274`（过期判据是 `score < staleBefore`，S_W_PICK 的 `[cutoff, +inf]` 区间与之一致）；`BRS.java:319-424`、`:704-779`（收尾后立即 `eraseRoom`，所以 S10 的 1004 → 16018 成立）。

**本轮更正**

| # | 位置 | 问题 | 更正 |
|---|---|---|---|
| E-1 | §0.2、§0.3、§2.7 Z6 / Z7、§3.5、§8.5 E8 | 并行编辑中的规格行号漂移：`bn-spec` Q1 在 :1500-1503（原写 :1489-1492）、§5.5 在 :683-707、§7.10 的「代价」在 :1174（原写 :1163-1169）；`sb-spec` §7.15 寻址在 :918（原写 :917）、§7.16 回落在 :934-935 | 已改 |
| E-2 | §4.9 | `arch` 的 Redisson 单命令 4.2 s 引的是旧行号；该段在 HEAD `:631-633`、工作区 `:666-668` | 改为按节名引用（`arch` §6「Redis 客户端超时」条），并注明 `m-spec` §9.6 的 `arch:607-609` 已过时 |
| E-3 | §10.7 S4（源自分区稿③ W5） | **断言写错**：「大厅再收到一条 177」。大厅公告在有活直连时**经直连直写**（基线 `room.cpp:1410-1430`；Java `BRS.java:796-800`），S4 时 SB 的直连是活的，重推的 177 只会出现在直连上；按原断言 robot 会稳定失败 | S4 改为「直连上再收到一条 177、与第一条逐字节相同，大厅上没有新的 177」；§3.4 的「重看同一场」行与 §3.5 补上这条规则，并引客户端两条链路都挂 177 处理器、同局活直连时只换存票据（`BattleDirectLink.cs:226-239`）；§0.3 的 6.2 行同步 |
| E-4 | Q22、§10.10 | 前置①写成「battle 通告地址一址两用尚未拆分」；6.2 工作区已按 `ci-spec` Q9 加了 `xm.battle.client-advertise-host`（`application.yaml:61-65`；`BattleIdentity.java:13`） | 改为「compose 里给 xm-battle 配 `XM_BATTLE_CLIENT_ADVERTISE_HOST` 即可」 |
| E-5 | §4.11 给 `m-spec` §8.1 的替换行 | 多出一列「处理器内未预期异常」，与 `m-spec` §8.1 的列不一致 | 列与 `m-spec` §8.1 对齐，未预期异常改为表下一句；并写明 :841「163 / 164 在 Dubbo 线程上当场回」那条要随之删掉 163 / 164 |

**复核过、维持原样的分歧裁决**：#1 / #2（BW1、BW7 照搬，C1 / C2 两版同改候选——正常路径上客户端可见，按 `AGENTS.md` §1 不单边改）；#3（W4 结局不明保留标记）；
#4（复查读锁出错按有锁）；#5 / #6（163 虚拟线程、自我清退异步）；#7–#9（键名、按分数随机、10 s 清扫）；#10–#13（`--visit-zone`、跨区带观众、第二局证明结算、Go robot 可选）。
