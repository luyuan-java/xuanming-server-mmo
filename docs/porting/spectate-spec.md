# 观战（match 侧）与跨区 1V1（批次 6.5）移植统一规格

> **基线**：mmorpg `D:\work\mmorpg` @ `26ceb70ca`（稀疏克隆）。本稿用到的 `go/match/**`、`go/client_rpc_router/**`、`proto/{match,battle}`、`cpp/nodes/battle/logic`、
> `robot/*.go`、`robot/etc/*.yaml`、`robot/logic/handler`、`docs/design/**`、`PROGRESS.md` 都已检出。
> **稀疏克隆里缺的目录**（凡依赖它们的结论都标了「推导」）：`robot/config/`（`BattleSmokeConfig.validate` 不可读，只能引 `bsc.go:63-64` 的注释「配置层把非 "1v1" 拒掉」）、
> `robot/generated/`、`bin/etc/`。Unity 客户端是另一份稀疏克隆 `D:\work\mmorpg-client` @ `a8577c7`：`Assets/Scripts/{Game/Battle,UI/Ugui/Battle,App,Net}` 已检出，
> **`tools/` 不在**（双播放器验收脚本 `tools/run_crosszone_pair.ps1` 不可读，只读了 `DevAutoPilot.cs`）。
> **Java（成稿时，2026-10-05）**：HEAD `9fde7d8`（5.2 `6b28e9d`、7.1a / 7.2a `37dd8dc` 已提交；本稿随 `9fde7d8` 入库）加工作区。成稿时 6.2 的 xm-battle 还在工作区（**房间侧观战已实现**，`bn-spec` Q1 已采纳），
> 6.3 / 6.4 / 5.4 只有规格、xm-match 还没有代码。正文里引的 Java 行号是成稿时的，回写时没有逐个更新，**以类名 / 方法名为准**。
> **状态（2026-10-08）**：6.2、6.3、6.4 已落地；**批次 6.5 已实现并集成**（先行件、六个工作包、集成、评审修正；提交见 git log「批次 6.5」）。163 / 164 是真实现，M22 已关闭；双 zone 本机切片（`XM_ZONES=2`）与 robot 的 `--visit-zone`
> 由 6.5 提前落地（lead 裁决 1），**5.4 仍未实施**（226 / 124、GO-5、X16 都不在）。正文 §0–§10 已按实现回写（标「落地」的是实现阶段定下来的口径）；类的落点、与原稿的出入、评审的发现与修正、证据与遗留见文末「实现记录（2026-10-08）」，与正文冲突时以它和代码为准。6.4 对本稿三条要求的落地情况在 §4.11 的表里。
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
  - 复用 6.4 的落点记录 `xm:{match}:battle:<id>`（同时充当观战记录）、票据的只读口 `TicketReader`、`GatherHooks.beforePrepare / onStarted`、按落点直拨的 `placement.PlacementDialer`（用它自己的客户端缓存 `PlacementClients`）；
    战斗锁（6.3）与在线目录 `xm:presence:<pid>` 经 xm-match 惯用的读口 `port.PlayerStatusReader`（`inBattle` / `presence`）读。
  - 新增两个键 `xm:{match}:watching:<pid>`（观战标记）、`xm:{match}:watchable`（可观战索引），与票据同 tag。基线靠「先查后写 + 事后复查」拼出来的几处改成单段 Lua（落地：11 段，§4.3）。
  - 观众 RPC 按落点记录里的地址**直拨**，判死规则与 6.4 的 179 共用（`placement.PlacementDialer`；6.5 给它加了带硬截止的重载，§4.8）。
  - 163 跑在虚拟线程上（在途上限 128、4.5 s 预算；处理器经 `MatchMethodHandler.executor()` 自带执行器），164 跑在 `match-worker` 上。
- **跨区 1V1 不需要新的生产代码路径**：6.4 的排队池全局、6.2 的 battle 池全局、6.3 / 6.4 的 scene 定位按 (zone, 节点号) 区分、6.2 的推送按带 zone 的在线目录寻址，
  组合起来天然支持跨区。6.5 交付的是不变量清单 Z1–Z12（§2.7；Z1–Z3、Z5–Z7、Z11、Z12 有组件测试钉住，Z8、Z10 靠双 zone 切片上的 robot，Z4、Z9 是既有架构的事实、没有单独的用例）、「同号节点跨 zone 碰撞」的单测 / 组件测试与寻址审计、`XM_ZONES=2` 的本机切片脚本、切片上的 robot 场景 `battle-cross-zone`、PARITY 登记。
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
| **6.2 battle**（工作区已实现房间侧） | `addObserver` 的判定顺序与码（`BRS.java:320-366`）、幂等分支（`:368-401`，签不出票时摘除 + 关直连 + 1003）、`removeObserver` 推 166 REMOVED（`:405-424`）、观众上限 20（`RoomConstants.java:33`）、177 有活直连时直写、否则按在线目录经 gate 回落（`BRS.java:796-800`；`PresenceLobbyAnnouncer.java`；`bn-spec` N2）、161 随直连握手下发、165 白名单 | **已做（先行件）**：`DevRoutingResolver` 的私有 `gateRouting` 挪成 xm-discovery 的 `BattleRoutings.gatePart`，观战与 dev 接口共用（§4.11）；dev `add-observer` 的接口注释写明不写 match 标记（Q14）。xm-battle 的业务代码没有改 |
| **6.3 scene** | `BattleLockReader.exists`（16015）；`BattleRouting.zone_id` 由 scene 填自己的 zone；确认事件按 `(zone_id, scene_node_id)` 寻址、实例不符回落定位器（`sb-spec` §7.16 :934-935）；结算首投 / 重投经 `SceneAssetLocator` 按位置记录的 zone 解析（`sb-spec` §7.15 :918）；scene 应用结算后经大厅再推一份 150（`sb-spec:333`） | 跨区 robot 的结算断言（§10.8 Z7）依赖它 |
| **6.4 match** | xm-match 进程、163 / 164 已路由到 group match（`m-spec` §9.2）；落点记录 `BattlePlacement`（`m-spec` §4.3 :468-509）；`GatherHooks`（第 2.5 步 / 第 5 步，`m-spec` §9.6）；`MatchBudgets`；`placement.PlacementDialer` / `PlacementClients`；179 的判死规则（M16：请求确定没送达 + 同号换实例 + 原地址探测明确连不上）；6.4 期间 163 回 in-band 1006、164 回空列表（M22） | **都已做**：163 / 164 的临时处理器换成真处理器、M22 关闭、163 / 164 不再在 Dubbo 线程上当场回（`m-spec` §8.1、§9.3、§9.8、§9.9 已回写）；`created_at_ms` 取 Redis `TIME`、`player_names` 按成员顺序、`PlacementDialer` 三条是 6.4 自己做的（§4.11 的现状表） |
| **5.4 跨 zone** | `XM_ZONES=2` 本机切片（`zt-spec` §5.13 :821-839）：xm-gate-z2（11010 / 18123，节点号也会是 1）、xm-scene-z2（21010 / 21110 / 18115）、gateway 双区播种；robot 选项 `--visit-zone`（`zt-spec` §11.11）。**落地：5.4 没有实施，这两样由 6.5 提前做了**（lead 裁决 1；只有脚本与 robot 选项，不含 5.4 的任何生产代码；与 §5.13 原设计的出入见 §10.6） | §5.13 的「共用」行（**已含 battle**）加 xm-match；`--visit-zone` 的帮助文本是「另一个区」（Q16）。**不依赖** 226 / 124、GO-5、X16。`zt-spec` §5.13 / §11.9 / §11.11 已回写 |
| 5.1 / 5.2 / 5.3 / 5.5 | 无（观众没有战斗锁、不冻结，换图 / 交接 / 镜像 / 排空都不受观战影响，同基线） | 无 |
| 7.1 CI | 整栈冒烟分期（`ci-spec` §4.5 :736-760） | 第三期之后用两 zone 覆盖文件 `two-zones.yaml` 跑 `battle-cross-zone`（Q22） |
| 7.2 数据运维 / 7.3 合服 / 7.6 发布 | 无（观众没有锁，GM 回档等不被观战挡住，同基线；合服对 `xm:{match}:*` 不动，`zone-merge-spec.md:562`，观战键全服共用、不带 zone） | 7.6：battle 通告地址对全部 zone 可达（§2.8 第 4 条，`ops-release-spec` 已引用）、match 多副本下清扫器幂等（§4.7）、`xm_match_watchable_battles` 取 max（§6） |

### 0.4 分区稿分歧与裁决（都回到代码或现状核对过）

| # | 分歧 | 分区稿说法 | 裁决与依据 |
|---|---|---|---|
| 1 | 163 遇到 ready 残留票（B-s1） | ① Java 自愈放行；③ 照搬 16014 | **照搬**（BW1），登记两版同改候选（§8.4 C1）。这是正常路径上客户端可见的码（打完一局 60 s 内观战），没有 Java 模型上的理由，单边改会让两版在正常路径上分叉；同类先例 `m-spec` Q5（回合打满阈值） |
| 2 | 已结束的战斗主动出索引（B-s2） | ① 结果消费者打 `e = 1` 并 ZREM；③ 不做 | **6.5 不做**（BW7），登记两版同改候选 C2，做法写进 §8.4 备忘。列表内容是客户端可见的；活动局与作废局本来就没有结果事件给 match，做了也只覆盖一部分 |
| 3 | AddObserver 结局不明（超时 / 已发出后断开） | ① 照基线删标记、不补发 Remove；③ 保留标记 | **保留标记、不补发 Remove**（W4）。标记只是「可能在观战」的提示：多留的代价最多是一次幂等的 RemoveObserver；删掉则下一次开局清退会漏掉这个可能已登记的「幽灵观众」。补发 Remove 不可靠（可能先于在途的 Add 到达） |
| 4 | 复查时读战斗锁出错 | ① 改回 16004；③「读失败只记日志」 | **照搬基线**：读票出错只记日志、按无票继续；读锁出错按「有锁」处理（`pc.go:113-121` 回 `(true, err)`，`wb.go:218-222` 直接用它），自我清退并回 16014（BW2）。③ 的写法与基线不符；① 是只在 Redis 故障时可见的改动，收益小，不做 |
| 5 | 163 的执行器 | ① `match-worker` 带 4500 ms 截止；③ 虚拟线程 + 在途上限 | **虚拟线程 + 在途上限 128**（W10、Q2）。163 最坏串两跳 3 s 的 RPC，放在 16 线程的 `match-worker` 上，一个慢 battle 节点就能把 157 / 148 / 153 挤成过载；与 6.4 gather 同一执行模式 |
| 6 | 复查命中后的自我清退 RemoveObserver | ① 同步，预算耗尽才异步；③ 一律异步 | **一律异步、不等**：应答不依赖它的结果（基线 `removeObserver` 失败也只记日志，`sp.go:374-390`），持票 / 持锁的玩家此后发不出能与之竞争的 163（入口第 3 / 5 步就拒）。**例外**（第三轮评审补）：并发的 gather 很快失败、票被删且没加锁时，玩家可以立刻再发 163(同一场)，迟到的异步 Remove 可能摘掉这次新登记——只在竞态下可见，残余风险与理由见 §7.1 第 4 条 |
| 7 | 可观战索引的键名 | ① `xm:{match}:spectate:active`；③ `xm:{match}:watchable` | **`xm:{match}:watchable`** |
| 8 | 随机选场 | ① Lua 只在未过期成员里随机；③ 全集随机下标、Java 侧重抽 | **按分数过滤**（W8）：Java 的兜底清扫是 10 s 一轮（基线 500 ms），读路径必须自己挡住过期成员；分布与基线在存活成员上相同 |
| 9 | 清扫间隔 | ① 5 s；③ 10 s | **10 s、可配**：读路径已按分数过滤，间隔只影响残留成员的数量 |
| 10 | 跨区 robot 的第二个区参数 | ② 新增 `--zone-b`；③ 复用 5.4 的 `--visit-zone` | **复用 `--visit-zone`**（Q16），不增加同义选项；5.4 没合入时由 6.5 按 `zt-spec` §11.11 的名字与语义加 |
| 11 | 跨区 robot 要不要带观众 | ② 不带（归观战部分）；③ 带（zone 2 的 C 观战） | **带**（Q18）：Java 观众路由取带 zone 的在线目录，zone 2 的 gate 节点号也是 1，这正是同号碰撞要验的路径；代价是一个「开自动之前」的屏障 |
| 12 | 跨区 robot 怎么证明结算回到了各自 zone 的 scene | ② 等大厅 150 + 再打第二局；③ 各发 157 PVE_SOLO 后发 148 取消 | **②**：PVE_SOLO 不入队、即时开局，148 取消不了（`m-spec` §2.3 matched 之后静默成功），③ 的步骤不成立；第二局同时覆盖基线教训「0 血带入、ready 残留、迟到的旧 150」（`czm.md:281`） |
| 13 | Go robot 跨版本验收 | ② 暂缓；③ 纳入验收 | **可选、不阻塞**（Q23）：本机没有 Go；在 GitHub Actions 上作为可选 job，7.1b 之后再加 |
| 14 | 引用更正 | — | ② 的「`PROGRESS.md:4985` 把跨区冒烟列为待人工回归」不对（那一行是组队收口）；实情见 §2.6。③ 勘误 1 的 `inventory/scene-manager-match.md:289` 应为 `contract-robot.md:289`。① 的「`tbs.md:276` 不变量 7」应为 `:274`（`:275` 是不变量 8）。③ 的 `match_service.proto:164-167`、`:39-40` 是 Java 同步副本的行号，基线是 `ms.proto:161-164`、`:36-37`。`BattleUiStyle.cs:19-27` 应为 `:28` |

### 0.5 实施顺序与前置

实际落地顺序：6.2 → 6.3（2026-10-06）→ 6.4（2026-10-08）→ **6.5**（2026-10-08）；原稿设想的是 6.4 先于 6.3。

- 纯函数与组件测试只依赖 6.4 的代码加替身。
- `battle-smoke` 观战段的端到端依赖 6.3：参战者要真实冻结，打完要能结算，才能再排队（§10.7 S11）。
- `battle-cross-zone` 需要 `XM_ZONES=2` 的切片：原计划等 5.4 §5.13，实际由 6.5 自己落地脚本那一小块（lead 裁决 1）。X16（建角取会话 zone）只影响一条可选断言（角色列表里的 zone_id），不是硬依赖（Q26）；那条断言没有实现，等 5.4。

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

**自我清退**（复查，`wb.go:206-231`）：收窄「入口检查 → 写标记」之间的 TOCTOU 窗口（**收窄而不是关死**：没有票据的切磋 gather 若在本次写标记之前读过标记、在复查之后才写锁，复查看不到，两版相同，见 §7.1 第 16 条）。并发的 gather（尤其 PVE_SOLO 即时开局）可能在本次 163 写标记之前就已做完清退、读不到标记。
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
- A：157 PVE_SOLO → 143 → **立刻建直连**（屏障期间回合超时照样结算，回合帧只走直连）→ 阻塞在「观战就绪屏障」上，暂不开自动（`bss.go:70-77`、`:304-351`）；屏障**兜底 30 s**，B 迟迟不到位 A 也照样开自动（`bss.go:345-351`）。
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
| Z1 | 队列键、凑单锁、注册集都不含 zone；zone 只作观测字段写进票据 | 6.4（`m-spec` §9.4） | `MatcherPortedScenarios`（6.4 已移植 `TestMatcherMixesZonesAndShortensMatchedTTL`，`cz_test.go:153`；内存与真 Redis 两个子类各跑一遍）；`QueueServiceTest`（两个 zone 的玩家进同一条队列） |
| Z2 | JoinQueue 的 zone 取位置记录（严格读，`o` 才算），请求体的 zone 与 `SessionContext.zone_id` 都不用 | 6.4（`m-spec` §2.2 第 7–8 步） | `QueueServiceTest`：会话 zone 1、位置 zone 2 → 票据 zone 2 |
| Z3 | gather 逐人按**位置记录的 (zone, 节点号)** 读 `xm:nodes:scene:<zone>`；Cancel 发回 Prepare 时记下的端点 | 6.4 `ScenePreparer`（`m-spec` §9.6 第 3 步、fail 行） | `ScenePreparerTest` / `CrossZoneGatherTest` 同号碰撞；真 Redis 上 `ScenePreparerRedisIntegrationTest` |
| Z4 | battle 池 `xm:nodes:battle:0`（作用域 0） | 6.2（`NodeTypes.java:32-36`；`bn-spec` §7.10） | — |
| Z5 | 177 / 143 / 156 / 154 / 大厅 150 都按在线目录寻址，发往带 zone 的频道 `xm:gate-push:{zone}:{gate}`，再按 gate 实例过滤 | 6.2 N2、`arch` §4.3（`RedisKeys.java:351`；`PlayerPushes.java:130`） | xm-discovery `PlayerPushesTest`、`GatePushCrossZoneIntegrationTest`（真 Redis pub/sub）；xm-battle `PresenceLobbyAnnouncerTest` 同号 |
| Z6 | scene 用自己的 zone 填 `BattleRouting.zone_id`；确认事件按 `(zone_id, scene_node_id)` + 实例，不符回落定位器 | 6.3（`sb-spec` §7.16 :934-935） | `DubboSceneBattleEventsTest` 同号 |
| Z7 | 结算首投 / 重投经 `SceneAssetLocator`，按位置记录的 zone 读目录 | 6.3（`sb-spec` §7.15 :918；`SceneAssetLocator.java:21-35`） | `SettlementOutboxTest`（接真的 `SceneAssetLocator`）、`SceneAssetLocatorTest` / `SceneAssetLocationIntegrationTest` 同号；robot Z7 |
| Z8 | 每个 zone 的 gate 把 `MatchService` 转给同一组 xm-match（local 同一个 `xm.dubbo.match-url`；nacos 下 group `match` 不带 zone） | 6.4（`m-spec` §9.2） | 切片配置；robot |
| Z9 | 所有 zone 共用一个 Redis | 现有架构（`RedisKeys.presence` / `playerLocation` 不带 zone，`RedisKeys.java:42-55`） | — |
| Z10 | 评分按 player_id，全局 | 6.4（`m-spec` §5） | robot Z8 |
| Z11 | `xm_match_gather_zone_mix_total{mode, mix}` 按快照的 `routing.zone_id` 计 | 6.4（`m-spec` §11） | `GatherPipelineTest`、`CrossZoneGatherTest`（没有单独的 `ZoneMixTest`）；robot Z10 |
| Z12 | 观战不分 zone：索引全局；观众路由的 zone 取在线目录（gate 所在 zone） | 6.5（§4.4） | `CrossZoneGatherTest`（经真的 `SpectateGatherHooks` 与 `WatchBattleService`）、`WatchBattleServiceTest`（路由四个字段取在线目录）、`BattleRoutingsTest`；robot Z5 |

**基线需要、Java 不需要的**：D11 的 gate 特判（Java gate 经 Dubbo 按服务名找 match，本来就不按 zone 过滤）；「match 每 zone 一份」（`bsc.yaml:4-5` 只是基线本地部署形态，Java 的 xm-match 全局部署）。

### 2.8 Java 特有的坑：同号节点跨 zone 碰撞

1. **scene / gate 的节点号按 zone 租约**：键 `xm:node-id:{type}:{zone}:{id}`（`RedisKeys.java:16-31`；`NodeTypes.java:11`、`:13`），zone 1 与 zone 2 的第一台 scene、第一台 gate 都会是 1 号
   （`zt-spec` §5.13 :831 明写 xm-gate-z2 节点号也是 1）。任何只按 `scene_node_id` / `gate_node_id` 查目录、拼频道、拼缓存键的代码，都会把 zone 2 玩家的确认、结算、公告送到 zone 1 的同号节点。
   实例过滤会把它**丢掉而不是送错**，结局是「锁停到 TTL」或「公告丢失」，症状不显眼；本机 `XM_ZONES=2` 天然制造这种碰撞，robot 能暴露它。
2. **`BattleRouting.gate_node_id` 在 Java 不能用来寻址**（跨 zone 有歧义）。6.2 已改走在线目录（N2）；6.5 的观众路由同样取在线目录（W11），不得用它或 `SessionContext` 拼推送目标。
3. **三种 zone 不要混用**：位置记录的 zone（所在 zone：JoinQueue、gather、结算）；在线目录的 zone（gate 的 zone：推送、观众路由）；归属区 `player.zone_id`（建角、存盘、组队）。
   稳态下前两者相等；跨 zone 传送途中不等（`zt-spec:427`）。排队期间玩家旅行到别的 zone 时，gather 重读位置：位置处于待落点就判 `no_location`、该玩家作为肇事者出局，两版结局相同。
4. **battle 的通告地址必须对所有 zone 的客户端可达**：本机 127.0.0.1:12000；多机部署归 7.6。

**落地（寻址审计）**：6.5 把 6.2 / 6.3 / 6.4 的寻址代码逐处回到代码核对了一遍（静态审计），没有发现只按节点号 / 会话号寻址的地方；清单与新增的测试钉见文末实现记录「跨区 1V1：测试钉与寻址审计」。
本机 `XM_ZONES=2` 的切片有意让区 2 的第一台 scene / gate 也是 1 号，robot `battle-cross-zone` 在这个形态上跑。

---

## 3 客户端可见行为

### 3.1 163 WatchBattle：判定顺序（顺序本身就是语义）

失败时 `battle_id = 0`、`error_message = TipInfoMessage{id, parameters = [中文串]}`（`tipErr`，`join.go:19-21`）；成功只填 `battle_id`（随机模式回填实际挑中的场）。

| # | 条件 | 应答（id + `parameters[0]`） | 指标 outcome | 基线 | Java 落点 |
|---|---|---|---|---|---|
| 1 | 会话没有玩家身份 | 16004 `缺少玩家身份` | internal | `wb.go:53-59` | 只认 `SessionContext.player_id`，请求体一律忽略（W12） |
| 2 | 读票据出错 | 16004 `服务器繁忙,请稍后再试` | internal | `:70-73` | 只读脚本 S_W_ENTRY 出错（同一往返也读了标记，见注 1） |
| 3 | **持票，任意状态（queued / matched / ready）** | 16014 `匹配中无法观战` | queued | `:74-79` | 同（BW1） |
| 4 | 读战斗锁出错 | 16004 `服务器繁忙,请稍后再试` | internal | `:80-83` | `PlayerStatusReader.inBattle` 抛依赖异常（它包着 `BattleLockReader.exists`） |
| 5 | 战斗锁存在 | 16015 `战斗尚未结束,无法观战` | in_battle | `:84-89` | 同 |
| 6 | 读观战标记出错 | 16004 `服务器繁忙,请稍后再试` | internal | `:90-93` | 已在第 2 行一并读出 |
| 7 | 已有标记：**不拒绝**。值非法 → 删；请求 battle_id ≠ 0 且等于旧场 → 只删标记，**不发** RemoveObserver；其余（换场、随机）→ 读旧场记录，在则 RemoveObserver(`rewatch`) 后删标记 | 继续往下判 | 基线计 already_watching；Java 计入清退指标（W14） | `:94-113`；`sp.go:282-308` | 按读到的值删（W3）；Remove 直拨旧场落点地址（W5），**同步完成**（或超时）后才往下走。落地：要发 Remove 而剩余预算不足 2.2 s → J 行（旧标记原样）；删旧标记失败 → 16004（W16；重看同一场时旧标记原样留着，W17） |
| 8 | 读会话出错 | 16004 `服务器繁忙,请稍后再试` | internal | `:118-121` | `PlayerStatusReader.presence`（在线目录严格读，包着 `PlayerPresenceDirectory.findStrictAsync`）抛依赖异常 |
| 9 | 不在线（基线：会话不是 ONLINE，含断线等重连的 DISCONNECTING） | 16019 `会话不在线,无法观战` | offline | `:122-127`；`pc.go:87-92` | 在线目录没有条目（W11；gate 在断线、离场时删条目，`arch` §4.3） |
| 10 | 基线：`session.GateId` 不是数字 | 16004 `服务器繁忙,请稍后再试` | internal | `:128-132` | Java：在线目录条目缺 gate 实例（数据损坏，正常不可达）→ 同一应答 |
| 11 | 选场循环里：随机选场 / 读记录（含损坏）/ 写标记出错 | 16004 `服务器繁忙,请稍后再试` | internal | `:154-170`、`:195-197` | S_W_PICK / S_W_READ / S_W_ACQUIRE 出错 |
| 12 | 指定场：记录不存在 | 16018 `该战斗不存在或已结束` | not_found | `:171-185` | 同（已公开时只摘索引成员，不删记录） |
| 13 | 抢标记失败（只可能是并发 163） | 16016 `已在观战另一场战斗` | already_watching | `:187-203` | S_W_ACQUIRE 回 `busy`；回 `queued`（检查之后才建出的票据）→ **16014**、不调 AddObserver（W2）。落地：第 7 行刚按「重看同一场」删过旧标记时，另发一条异步的自我清退（`concurrent_queue`，W17） |
| 14 | AddObserver 成功后复查命中票据或锁（含读锁出错） | 16014 `匹配中无法观战` | queued | `:206-231` | 同（BW2）；RemoveObserver 异步发出（W10） |
| 15 | 成功 | `{battle_id}`，无 `error_message` | ok | `:232-235` | 同 |
| 16 | AddObserver 回 1004：在建房窗口内 → 不剔除；窗口外 → 剔除，随机 → 下一轮；指定（或窗口内） → 回包 | 16018 `该战斗不存在或已结束` | not_found | `:237-254`、`:306-318` | 窗口输入取原子快照（W6）；剔除按 attempt 守护（W7）；「节点已死」视同 1004（W5） |
| 17 | 其它拒绝（1005 / 1008 / 1003）或传输失败 | 16018 `该战斗当前无法观战`（随机也不换场） | rejected | `:255-260` | 明确拒绝与「确定没送达」删标记；结局不明保留标记（W4） |
| 18 | 随机两轮都没成 | 16017 `当前没有可观战的战斗` | no_battle | `:263-266` | 同 |
| J | Java 独有，三种：① 在途已满，或轮到执行时预算已用完（没进处理流程）；② 换场要发 Remove 时剩余预算不足 2.2 s；③ 登记前剩余预算不足 1 s | 16004 `服务器繁忙,请稍后再试` | ① overloaded；②③ internal | — | W10。① 标记从未写入（`WatchBattleHandler.onOverload()`）；② 旧标记原样保留、不发 Remove；③ 回滚刚抢到的标记——重看同一场时**保留**它（W17） |
| J2 | 处理器内未预期异常（bug） | 信封 1003（无 in-band 应答） | internal | 基线 panic → gRPC 错误 → 信封 1003（`fwd.go:170-184`） | `m-spec` §9.9 第 5 条。落地：按值尽力释放的是「已抢到、Add 的结局还没出来」的标记；Add 已成功或结局不明之后再出异常，标记保留（同 W4） |

- 注 1：基线第 2 行与第 6 行之间隔着锁检查；Java 把票据与标记放进一段只读脚本，脚本要么整体成功要么整体失败，所以「票据读成功、标记读失败、锁存在 → 16015」这种组合在 Java 不会出现。
  只在 Redis 故障时有差别（都回 16004 或 16015 之一），不单列差异。
- 随机模式只有「记录缺失」和「房间不存在且不在建房窗口内」进入下一轮；其它拒绝当场回 16018（BW6）。落地：随机模式换下一轮之前回滚本轮标记失败 → 16004（W16）。
- 落地（计数口径）：进了处理流程的请求恰好记一个 outcome；请求体解析失败（信封 1003）不记 `xm_match_watch_battle_total`，只进 `xm_match_requests_seconds{result="bad_request"}`。

### 3.2 `parameters[0]` 全表（逐字节，逗号都是半角）

`缺少玩家身份`、`服务器繁忙,请稍后再试`、`匹配中无法观战`、`战斗尚未结束,无法观战`、`会话不在线,无法观战`、`已在观战另一场战斗`、`当前没有可观战的战斗`、`该战斗不存在或已结束`、`该战斗当前无法观战`。

tip 数值来自 `xm-table/src/main/proto/tip/match_error_tip.proto:20`（16004）、`:40-50`（16014–16019），表内文案来自 `config-data/tables/tip_text.json:70`、`:80-85`（客户端按 id 查表内文案，`parameters[0]` 另带服务端说明）。

### 3.3 164 ListWatchableBattles

| 规则 | 基线 | Java |
|---|---|---|
| `limit = 0` → 20；`> 50` → 50 | `lw.go:15-18`、`:39-45` | `SpectateRules.clampLimit`（`MatchBudgets.WATCHABLE_LIST_DEFAULT / MAX`；`limit` 是 uint32，大于 2^31 的值同样收到 50） |
| 按 `created_at_ms` 降序；同分按成员字符串降序 | `lw.go:47` | 同一条 `ZREVRANGE … WITHSCORES` |
| 读索引失败 | gRPC 错误 → 信封 1003 | `ClientReply.tip_id = 1003` |
| 非法成员 → ZREM、跳过；过期 → 删记录 + ZREM、跳过；读记录出错 / 损坏 → 跳过、不剔除；记录不在 → 剔除、跳过 | `lw.go:55-77` | S_W_EVICT 的 `invalid` / `stale` / `missing` 三种模式（`missing` 只摘成员、永不删落点），过期分界取 Redis `TIME`；非法成员的判据比基线严（W18） |
| **不补齐条数** | `lw.go:34-36` | 同（BW5） |
| 不校验身份、不做互斥 | `lw.go:34-37` | 同（BW4） |

`BattleWatchSummary` 从 6.4 的 `BattlePlacement` 映射：`battle_id`、`battle_config_id`、`created_at_ms`、`player_names` 原样（顺序 = gather 成员顺序）；`mode` 用 `setModeValue(placement.mode)`，不认识的值也原样保留。
列表里会出现：已结束不足 360 s 的战斗（BW7）、满员的房间、请求者本人参战的局（同基线）。

- **基线「summary 为空 → 剔除」的对应物**：落点 HASH 在、`pb` 字段缺失（6.4 的写入是一段 Lua 同时写 `a` 与 `pb`，正常写者不会产生）或 `pb` 解析失败，一律按**损坏**处理：跳过、不剔除、计 `anomalies{corrupt_record}`。
  落地：观战读路径的损坏判据（`PlacementRecords.parse(battleId, a, pb)`）比 179 的多两条——`a` 字段缺失或与消息里的 attempt 不一致、落点键不是 HASH——也按损坏处理（W15）；这样读到的好记录的 attempt 一定等于 `a` 字段，可以直接用于 W7 的守护与公开条件。
  基线对「summary 为空」是 DEL + ZREM（`lw.go:74-77`），Java 不照搬：S_W_EVICT 的 `missing` 模式只认「落点不存在」，对一条在场但缺字段的落点 DEL 会连带毁掉 179 的定位；只在数据损坏时可见，登记为 W15。
- **空列表也回包**：`ListWatchableBattlesResponse{}` 序列化是 0 字节，gate 按应答类型（不是 `Empty`）照常回包（`xm-api/src/main/proto/xm/api/client_call.proto:33-36`）。客户端靠这个空包把面板从「刷新中」收回（`SP.cs:101-123`）。

### 3.4 换场与重看

| 情形 | 服务端 | 客户端可见 |
|---|---|---|
| 有标记，指定 Y ≠ 旧场 X | 记录 X 还在 → RemoveObserver(X, rewatch) → 删标记 → 走 Y 的流程 | X 的直连上收到 166 `{X, ONGOING, REMOVED}` 并关闭；**Y 失败时玩家什么都不在看**（BW10） |
| 有标记，随机 | 同上（随机一律先清退），随后可能又挑中 X → 重新登记 | 166 REMOVED(X)，再收到一次 177(X) |
| 有标记，指定 = 旧场 X | 只删标记 → 抢标记 → AddObserver 走幂等分支：同会话重推 177、有活直连再推 161；会话变了 → 关旧直连、推 177（`room.cpp:812-863`；`BRS.java:368-401`） | 不推 166。**重推的 177 有活直连时经直连直写**，没有才经大厅（大厅公告的统一规则，`room.cpp:1410-1430`；`BRS.java:796-800`）；客户端两条链路都挂了 177 的处理器，同局且直连活着时只换存票据、不重连（`mmorpg-client` `Assets/Scripts/Net/BattleDirectLink.cs:226-239`；`Assets/Scripts/Game/Battle/DirectRoutingBattleTransport.cs:17-19`） |
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

只在竞态 / 故障 / 过载 / 慢节点时可见：W2、W5、W6、W8、W10、W12、W13、W15（数据损坏时）；落地新增的 W16（Redis 故障时 16016 变成 16004）、W18（只在脏数据上）、W20（只在停机时）同属这一类，W17、W19 客户端不可见。正常路径上与基线逐字节相同；正常路径上的两处基线怪癖（BW1、BW7）照搬，登记两版同改候选（§8.4）。

---

## 4 Java 设计

### 4.1 包与类（领域对象 + `XxxService`，不用 ECS）

| 类 | 职责 | 基线来源 |
|---|---|---|
| `spectate.WatchBattleService` / `WatchBattleHandler` | 163 的流程（§4.4）/ 入口处理器（非 inline，`executor()` 返回 163 自己的执行器，过载回 in-band 16004） | `wb.go` |
| `spectate.SpectateExecutor`（`implements Executor, InflightWatches`） | 163 的执行器：每个请求一条虚拟线程 + 在途上限；同时是停机时「等在途 163」的口（§4.9） | — |
| `spectate.WatchableListService` / `ListWatchableHandler` | 164 的流程（§4.5）/ 入口处理器（非 inline，跑在 `match-worker`，过载与读索引失败回信封 1003） | `lw.go` |
| `spectate.SpectateStore` / `RedissonSpectateStore` / `SpectateScripts` | 观战标记、可观战索引、原子读快照（§4.3 的脚本） | `sp.go:22-30`、`:204-274`、`:310-315` |
| `spectate.SpectateRules`（纯函数） | `roomMayBeCreating`、`clampLimit`、`staleCutoff`、`summaryOf(BattlePlacement)`、标记值的编解码 | `wb.go:306-318`；`lw.go:39-45`；`sp.go:230-234` |
| `spectate.ObserverDialer` / `DefaultObserverDialer` | 按落点地址调 `addObserver / removeObserver`，结局分成 `Replied(tip)` / `Dead` / `NotDelivered` / `Unknown`（§4.8）；`add` / `remove` 同步、带硬截止，`removeAsync` 发出即返回 | `sp.go:349-390` |
| `spectate.SpectateGatherHooks implements GatherHooks` | `beforePrepare`：开局前清退；`onStarted`：公开这一场 | `gather.go:228-234`、`:387-389`；`sp.go:163-191`、`:282-308` |
| `spectate.SpectateSweeper`（`implements SweeperControl`） | 定时摘掉过期的索引成员，采样索引大小；自己不带生命周期，由 `MatchLifecycle` 启停（§4.7） | `matcher.go:201-204`；`sp.go:395-408` |
| `spectate.SpectateStoreConfiguration` / `WatchBattleConfiguration` / `WatchableConfiguration` | 观战包的三个装配类（存储；163 + 观众 RPC + 执行器；164 + 钩子 + 清扫）。不带条件装配 | — |
| `placement.PlacementDialer`（6.4 已有，从一开始就在 `placement` 包；6.5 加带硬截止的重载） | 「按记录地址直拨；请求没送达时查目录，同号换了实例且原地址探测明确连不上才判死」，179 与 `ObserverDialer` 共用 | `m-spec` §4.3 第 4–6 行 |
| `placement.PlacementRecords`（6.5 先行件抽出） | 落点 HASH 的字段名（`a` / `pb`）与损坏判据，`RedissonPlacementStore` 与观战存储共用 | — |
| `lifecycle.InflightWatches` / `SweeperControl` | `MatchLifecycle` 停机时等在途 163、启停清扫器的两个口（§4.9） | — |
| `dispatch.MatchMethodHandler.executor()` | 处理器可自带执行器（缺省 null = `match-worker`）；拒收走 `onOverload()`（lead 裁决 2） | — |
| `support.MatchTip` / `MatchTips`（扩充） | 16014–16019 与 §3.2 的全部 `parameters[0]`（七个 `WATCH_*` 发生点；两条 16004 复用 `NO_IDENTITY` / `BUSY`）；`watchRejected` / `watchAccepted`；`BATTLE_ROOM_NOT_FOUND`（1004，只用来解读 battle 的应答） | `errors.go:65-84` |
| `metrics.MatchMetrics`（扩充）、`MatchProperties.Spectate` | §6 的指标与枚举（全部标签组合启动即预建）；`xm.match.spectate.*`（§5.2） | `metrics.go:86-90` |
| xm-discovery `com.game.discovery.battle.BattleRoutings.gatePart(PlayerPresence)` | 在线目录 → `BattleRouting` 的 gate 部分（`session_id`、`gate_node_id`、`gate_instance_id`、`zone_id`），scene 字段恒为 0 / 空 | `wb.go:133-139`；原是 `DevRoutingResolver` 的私有方法，先行件挪出、两边共用 |

### 4.2 Redis 键（全部经 `RedisKeys` 生成，`xm:` 前缀，tag `{match}`）

| 键 | 类型 / TTL | 内容 | 基线 |
|---|---|---|---|
| `xm:{match}:watching:<pid>`（`RedisKeys.matchWatching(pid)`） | STRING，PX 360 000 | `"<battle_id 无符号十进制>:<nonce>"`，nonce 是 16 位小写十六进制，**每次抢标记**生成一个（随机模式两轮是两个值）。同值重放不续期。解析不了的值（含基线那种只有 battle_id 的旧值、battle_id 为 0）一律是脏值，按读到的原串删（W18） | `spectate:watching:{pid}`（值只有 battle_id） |
| `xm:{match}:watchable`（`RedisKeys.matchWatchable()`） | ZSET，无 TTL | member = `Long.toUnsignedString(battle_id)`，score = `created_at_ms`（毫秒 < 2^53，double 精确） | `spectate:battles:active` |
| `xm:{match}:battle:<id>` | 6.4 的落点 HASH（`a` = attempt，`pb` = `BattlePlacement`），360 s | 6.5 只读，加两种有条件的删除（S_W_EVICT） | `spectate:battle:{id}` |
| 只读 | `xm:{match}:ticket:<pid>`（6.4）、`xm:presence:<pid>`、`xm:nodes:battle:0`、`RedisKeys.battleLock(pid)`（6.3；后三样经 `port.PlayerStatusReader` 与 `gather.BattleNodes`） | — | — |

观战标记、可观战索引、票据、落点记录同在 `{match}` slot；战斗锁不在（6.3 拥有），所以锁检查不进脚本，靠复查兜底。

落地的几条存储口径（`RedissonSpectateStore`；都只在脏数据上才看得出来）：
- 索引成员只认**规范形**（非 0 的无符号十进制，没有符号、空白与前导零，`SpectateRules.parseMember`）；`007` 这类成员按非法成员、按**原串** ZREM（W18）。成员按字节一一对应读写，任何脏成员原样交回都摘得掉。
- 标记值按 UTF-8 文本读写。已知限制：不是合法 UTF-8 的脏标记按值删不掉，只能等 360 s 的 TTL（期间该玩家抢标记回 16016）；xm-match 自己不写这样的值。
- 过期分界 = Redis `TIME` − 360 000 ms，分数**严格小于**分界才算过期（恰在分界上的还算活着）；选场、列表、清扫同一口径。公开时的分数按 `created_at_ms` 的有符号十进制写。
- 落点键被占成别的类型读成「损坏」（163 回 16004、列表跳过、都不剔除）；索引键 / 标记键被占成别的类型是依赖异常。

### 4.3 脚本（每段只访问 KEYS 里声明的键；可变脚本都能被 Redisson 安全重发，同 `m-spec` §9.4）

落地是 **11 段 Lua**（`spectate.SpectateScripts`，由 `RedissonSpectateStore` 执行）：下表的九段 S_W_*，外加两段只读的批读 S_W_MARKS / S_W_RECORDS（原稿写的是两条普通命令 `MGET` / `RBatch`）。
KEYS 的次序：**票据在标记之前；索引在落点之前**（落点键是可选的那一把，放最后）。返回值是扁平数组或整数，不含 nil（缺失值用空串占位、另带 0 / 1 标志）。

| 脚本 | KEYS（按次序） | 语义 | 返回值 | 重发 |
|---|---|---|---|---|
| S_W_ENTRY（只读） | 票据、标记 | 一次往返读「有没有票据（`EXISTS`，任意状态、损坏的也算有）」与「标记的值」；判定仍按 §3.1 的 3 → 5 → 7 顺序 | `{有票 0/1, 有标记 0/1, 标记值}`（空串的脏标记靠「有标记」标志与「没有」区分） | — |
| S_W_ACQUIRE | 票据、标记 | 票据存在 → `queued`；标记值等于 ARGV → `ok`（重放，不续期）；标记存在 → `busy`；否则 `SET PX 360000` → `ok` | `0` = ok / `1` = queued / `2` = busy | 值里带 nonce，重发命中自己写的值回 `ok`，不会误报 16016。**唯一挡不住「迟到的重发」的一段**，见表下 |
| S_W_RELEASE | 标记 | 值等于 ARGV 才 DEL（回滚、复查清退、入口删旧标记、开局清退都用它） | `1` = 本次删掉 / `0` | 幂等 |
| S_W_MARKS（只读，落地新增） | 每名玩家的标记 | 一次读出一组玩家的标记，只回有标记的人（开局清退用） | `{KEYS 下标, 值, …}` | — |
| S_W_READ（只读） | 索引、落点 | 原子取「成员是否在索引里、落点的 `a` 与 `pb`、`TIME`」 | `{now, 已公开 0/1, 落点状态, a, pb}`；状态 `0` = 键不在 / `1` = 在 / `2` = 键不是 HASH | — |
| S_W_RECORDS（只读，落地新增） | 每场的落点 | 批读落点（164 用）：分得清「键不在」与「键在但缺字段」，一把被占成别的类型的键只坏它自己 | 每把键三项 `{状态, a, pb}` | — |
| S_W_PICK（只读） | 索引 | ARGV = Java 侧 `ThreadLocalRandom` 生成的 `r ∈ [0,1)`：`cutoff = TIME − 360000`；`n = ZCOUNT [cutoff, +inf)`；n = 0 → 空；否则 `ZRANGEBYSCORE cutoff +inf WITHSCORES LIMIT min(⌊r·n⌋, n−1) 1`。**过期成员不可能被挑中**（W8）；选场因此不剔过期成员、也不删它的落点 | `{now}` 或 `{now, 成员, 分数}`（成员原样，不保证是合法的 battle_id） | — |
| S_W_EVICT | 索引 [、落点] | 四种模式：`invalid`：ZREM 成员（只给索引一把键）；`missing`：落点不存在才 ZREM，**永不删落点**；`dead@a`：落点的 `a` 等于给定值才 DEL 落点 + ZREM，被改写过（或没有 `a` 字段）就不动（W7），落点已不在则只摘成员；`stale@cutoff`：成员的分数 < cutoff 才 DEL 落点 + ZREM（只看分数，不看落点好坏） | `1` = 真的摘掉了东西 / `0` | 幂等；每个模式都是读完再写 |
| S_W_PUBLISH | 索引、落点 | 落点的 `a` 等于这次开局的 attempt 才 `ZADD score = created_at_ms` | `1` = 现在在索引里 / `0` = 条件不成立 | 幂等 |
| S_W_SWEEP | 索引 | `ZREMRANGEBYSCORE -inf (TIME − 360000)`（开区间，恰在分界上的留着），只摘成员；落点靠自己的 TTL 过期（同 `sp.go:395-408`） | 摘掉的条数 | 幂等 |
| S_W_LIST（只读） | 索引 | `ZREVRANGE 0 limit−1 WITHSCORES` + TIME；含过期与非法成员，由调用方判 | `{now, 成员, 分数, …}` | — |

唯一的普通命令是清扫器采样用的 `ZCARD`（`watchableCount`）。落地的通用约定（`SpectateScripts` 的类注释，`SpectateScriptsTest` 与真 Redis 用例钉住）：
- **全部脚本（含只读的）以 `READ_WRITE` 执行 = 读主库**：主从部署下只读脚本缺省发到从库，那里的 `TIME` 与复制延迟下的标记 / 落点不能与主库上刚写下的放在一起判。不做 `EVALSHA` 缓存（同 6.4 的票据 / 落点存储）。
- 玩家号、战斗号、成员、标记值在 Lua 里只当字符串，从不 `tonumber`（uint64 超出 double 精度）；转成数字的只有分数、分界、`r`、条数上限、过期时长。
- 每段脚本都「先判定、后写」：键被占成别的类型时在第一条写之前报错，不留写了一半的状态。
- **S_W_ACQUIRE 与迟到的重发**（评审 S65-STORE-2）：其余可变脚本都有条件可比，ACQUIRE 的标记一旦被调用方回滚就没有东西可比——163 等到截止、放弃并释放之后，Redisson 重发的那一遍才到，看到「无票、无标记」又把标记写回。
  `RedissonSpectateStore.acquire` 在「放弃等待时命令还在路上」时，等在途的命令有了结局再按值释放一次；这只是收窄：最后一遍在客户端判它超时之后才被 Redis 执行的，标记留到 TTL（360 s，只是提示）。没有做按 nonce 的墓碑键。
- 164 的异步剔除（`evictAsync`）用一个 `RBatch` 管道一次发出整批 S_W_EVICT：某一条在 Redis 上报错时其余照常生效，只打一条 WARN。

**顺序不变量**（照搬 `sp.go:156-162`）：必须先有最终落点记录、再进索引。S_W_PUBLISH 的 attempt 条件保证「索引里出现的成员，其记录一定是最终那一次写的」；
6.4 第 5 步先补写、再调 `onStarted`（`m-spec` §9.6 :1057）。

### 4.4 163 的 Java 流程（`WatchBattleService`，虚拟线程，§4.9 的预算）

1. 第 1 行判身份（只认 `SessionContext.player_id`）。
2. S_W_ENTRY → 第 2、3 行。
3. `PlayerStatusReader.inBattle`（xm-match 惯用的读口，包着 6.3 的 `BattleLockReader.exists`）→ 第 4、5 行。
4. 第 7 行的旧标记（`clearPreviousMark`）：
   - 值非法 → S_W_RELEASE(读到的原串)，往下走。
   - 显式同场（请求的 battle_id ≠ 0 且等于旧场）→ 只 S_W_RELEASE、**不发** Remove；删成功后在本次请求上记一个「重看同一场」的记号（W17：从这里到 AddObserver 之间玩家仍登记在这一场，却没有标记指着它）。
   - 其余（换场、随机）→ 读旧场落点（`PlacementStore.read`，179 的口径；读失败 / 损坏只记日志、不发 RPC；不在 → 标记只是残留）；在则先看预算：
     **剩余预算不足 2.2 s（`WATCH_REWATCH_RESERVE_MS + WATCH_ADD_MIN_BUDGET_MS`）时什么都不做、直接回 16004**（J 行②：旧标记原样保留，玩家仍在看旧场）；够则
     `ObserverDialer.remove(旧落点, reason = rewatch)`，硬截止 = 请求截止 − 1.2 s，超时 = `min(3 s, 硬截止的剩余)`，**同步等它返回**；然后 S_W_RELEASE。
     落地：这个 2.2 s 的判定放在「读到旧场落点之后、发 Remove 之前」——只有确实要发 Remove 时才判，不需要这一跳的几种（脏标记、同场、旧场已没有落点、读落点失败）照常只删标记往下走。
   - **删旧标记失败**（Redis 故障 / 预算已尽）→ 16004（W16）：换场 / 脏标记再尽力异步删一次；重看同一场**不补发**那次异步删除，旧标记原样留着（W17 ③）。
5. 在线目录严格读（`PlayerStatusReader.presence`）→ 第 8、9 行；组观众路由 `BattleRoutings.gatePart(presence)`；条目缺 gate 实例 → 第 10 行。
6. 选场循环（随机 2 轮、指定 1 轮）：
   - **随机**：S_W_PICK，Java 侧至多 3 挑；挑到非法成员（不是规范的无符号十进制）→ S_W_EVICT(`invalid`，按原串) 后重挑；挑不到 → 跳出循环。随机模式 `published = true`。
   - 对选中的场 S_W_READ 得到 `{published（指定模式用）, 落点（好记录的 attempt = HASH 的 a 字段）, now}`；`checkedAtMs = now`（W6）。
   - 落点不在 → 已公开时 S_W_EVICT(`missing`)；随机 → 下一轮；指定 → 第 12 行。落点损坏 → 第 11 行（BW9，计 `xm_match_watchable_anomalies_total{reason="corrupt_record"}`）。
   - 每次抢标记生成一个 nonce，S_W_ACQUIRE(`battle_id:nonce`)：
     - 抛依赖异常（结局不明）→ 尽力异步 S_W_RELEASE(本次的值) → 16004（存储另在在途命令有了结局之后再按值释放一次，§4.3）；
     - `queued` → S_W_RELEASE(本次的值)（覆盖「Redisson 重发 EVAL、首轮已写入标记、两轮之间建出票据」的情形）→ 16014。**带着「重看同一场」记号时先发一条异步的自我清退** `removeAsync(该场落点, concurrent_queue)`（W17 ①）；
     - `busy` → 16016（占着标记的是另一条并发 163，它的值不归本次请求删）。
   - 剩余预算不足 1 s（`WATCH_ADD_MIN_BUDGET_MS`）→ S_W_RELEASE → 16004（J 行③）；**带着「重看同一场」记号时不回滚、保留刚抢到的标记**（W17 ②）。否则
     `ObserverDialer.add(placement, AddObserverRequest{battle_id, observer_player_id, routing, observer_name = SessionContext.account})`，硬截止 = 请求截止 − 0.2 s，超时 = `min(3 s, 硬截止的剩余)`。结局（§4.8）：
     - `Replied(0)` → **复查**：再读票据（`TicketReader.read`，读失败只记日志、按无票）与锁（`PlayerStatusReader.inBattle`，读失败按有锁），**两条虚拟线程并行**、各自只等到请求截止。命中 → 异步 `removeAsync(reason = concurrent_queue)`、不等 → S_W_RELEASE → 16014。否则 → 第 15 行。只查一次。
       复查的读因剩余预算耗尽而等超时，按「读失败」处理：锁 → 有锁 → 16014（与 BW2 同一 fail-closed 方向，宁可多清退一个观众也不放进「观战 + 参战」）。Add 前的 1 s 门槛与 0.2 s 预留保证正常 Redis（毫秒级）下不会走到这里，只在 Redis 慢时可见，归入 W10。
     - `Replied(1004)` 或 `Dead` → S_W_RELEASE；`roomMayBeCreating(published, placement.created_at_ms, checkedAtMs)` 为真 → 第 16 行（不剔除）；否则 S_W_EVICT(`dead@attempt`；落点 attempt 为 0 时不发，防御)，随机 → 下一轮，指定 → 第 16 行。
       随机模式换下一轮之前，本轮的标记没能确认删掉 → 16004（W16：否则下一轮的抢占会被它挡成一条误导的 16016）。
     - `Replied(其它)` 或 `NotDelivered` → S_W_RELEASE → 第 17 行。
     - `Unknown` → **保留标记**（W4）→ 第 17 行。
   - 索引剔除只在存储回报「真的摘了」时计数；剔除失败只记日志、不改应答。
7. 循环用完 → 第 18 行。

入口与抢占的「有没有票」在 `SpectateStore` 的脚本里判（`EXISTS` 票据键，原子性只能在脚本里做）；`TicketReader` 只用于复查——两者只在票据 HASH 损坏时结果不同（脚本算「有」，`TicketReader` 抛异常、复查按无票）。

`observer_name` 取 `SessionContext.account`（`xm-api/src/main/proto/xm/api/client_call.proto:16-17`），与基线 `session.Account` 同值；它只进 battle 日志（`bn.proto:54`；Java 同步副本 `:57`），不下发客户端。

### 4.5 164 的 Java 流程（`WatchableListService`，`match-worker`）

S_W_LIST → 第一遍只凭成员与分数判「非法成员 / 过期」→ 其余一次批读落点（S_W_RECORDS，一段只读脚本；原稿写的是 `RBatch`）→ 逐条按 §3.3 判定 → 按索引次序组摘要 → 应答组好之后一批异步剔除。
读索引失败 → `tip_id = 1003`。预算同 `match-worker` 的 4500 ms。一次请求至多三次 Redis 往返（第三次不等结果）；过期成员不读落点，没有候选时不发批读。

- **批读整批失败或超时**：每一条都按「读记录出错」处理（跳过、不剔除，同基线逐条 GET 出错，`lw.go:68-73`），回已组好的列表（通常为空），**不回 1003**；`xm_match_list_watchable_total` 仍计 `ok`，另计 `xm_match_watchable_anomalies_total{reason="record_read_failed"}`。
  基线没有「整批」概念，逐条失败时同样回一个变短的列表，所以这样对齐的是客户端结果而不是调用次数。批读结果里缺某一条同样按读失败跳过。
- **剔除不阻塞应答**：需要剔除的成员收集起来，在应答组好之后异步发出（`evictAsync`：一个管道里的一批 S_W_EVICT，不等结果）。基线是同步逐条执行，剔除结果不影响本次列表内容（`lw.go:55-77` 剔除后都是 `continue`），所以不可见。
  三种模式：非法成员 → `invalid`（按原串）；分数过期 → `stale`（连同落点一起删）；落点不在 → `missing`（只摘成员、永不删落点）。`xm_match_watchable_index_evictions_total{invalid_member / stale / missing_record}` 按**发出**的条数计（异步，等不到结果）。
- **出口计数** `xm_match_list_watchable_total{result}`：`ok` = 回了列表（含空列表、含批读失败后变短的列表）；`error` = 读索引失败，以及流程里未预期的异常（后者原样抛给派发器、同样回信封 1003）；`overloaded` 在 `ListWatchableHandler.onOverload()` 里记。请求体解析失败不计。

### 4.6 gather 钩子（6.4 的 `GatherHooks`，跑在 gather 的虚拟线程上）

**`beforePrepare(members)`**（第 2.5 步，对应 `gather.go:228-234`）：

- 一次批读（`SpectateStore.marksOf` = S_W_MARKS；原稿写的是一条 `MGET`）读出全部成员的标记，至多等 1 s；读失败 → 只记日志、**整次跳过**清退、标记不删，计 `anomalies{mark_read_failed}`（基线是逐人 GET、出错的那一人返回而其余照常，W19）。
- 对每个有标记的成员按名单顺序**逐人串行**：值非法 → S_W_RELEASE；否则读落点（`SpectateStore.read`，观战口径的损坏判据）：读失败 / 损坏 → 记日志、不发 RPC、S_W_RELEASE；不在 → S_W_RELEASE；
  在 → `ObserverDialer.remove(落点, reason = enter_gather)`（带硬截止的重载，失败只记日志）→ S_W_RELEASE。不论走到哪一步，标记都按**读到的原值**删。
- 每人至多 3 s：matched TTL 公式里每人 3 s 的 RemoveObserver 项就是它（`m-spec` §3.4），数值不变。落地（`SpectateGatherHooks`）：每人一个 3 s 的 `Deadline`（`PER_MEMBER_BUDGET_MS = MatchBudgets.REMOVE_OBSERVER_TIMEOUT_MS`），
  **读落点 + RemoveObserver + 同步删标记三步共用**（RPC 的硬截止就是它，超时取 `min(3 s, 剩余)`）；截止所剩不足 50 ms（`MIN_SYNC_RELEASE_MS`）或同步删失败 / 结局不明时改发异步 S_W_RELEASE，然后处理下一人——比原稿「删标记算在 10 s 余量里」更紧。
  开头的批读与开局后的公开各等至多 1 s（`SMALL_OP_BUDGET_MS`，同基线每条观战 Redis 命令的 1 s 截止），算在公式的 10 s 余量里（`queue.go:351-354`）。**`beforePrepare` 的总上限 = 1 s + 有标记的人数 × 3 s**（10 人时 31 s，小于公式里的「n × 3 s + 10 s 余量」）。
  不这样夹紧的话，Java 的 Redis 单命令最坏 4.2 s（§4.9）加 3 s RPC 会让单人超出公式的 3 s 项。
- 任何异常都吞掉（两个方法都接 `Throwable`），**不影响开局**；单个成员身上的运行时异常就地归类、标记照删、继续处理后面的人。gather 随后失败，被清退的观众也不恢复（`sp_test.go:867`）。五个入口都经过这一步。
  管线在第 2.5 步之前就失败的（发号失败、没有可分配的 battle 节点）与拿不到在途许可的过载收尾不调钩子，观众不被清退、标记原样。
- 每条标记恰好记一个结局 `xm_match_spectate_evictions_total{reason="enter_gather", result}`：`Replied`（任何 tip）→ `removed`；`Dead` 或落点不在 → `no_record`；`NotDelivered` / `Unknown` / 直拨器违约 → `rpc_failed`；读落点失败或损坏 → `read_failed`；标记值解析不了 → `invalid_mark`。

**`onStarted(placement)`**（第 5 步，6.4 补写落点之后）：S_W_PUBLISH(attempt, created_at_ms)，至多等 1 s，尽力而为、不重试；抛异常与回「没登记上」（落点不在 / 已不是这一次的 attempt）都只计 `anomalies{publish_failed}`，这一场不进列表（`gsi_test.go:463`）。
6.4 的失败路径不调它：失败的场次永远不在索引里（`create_failed_room_alive` 保留落点但不公开）。

### 4.7 清扫（`SpectateSweeper`）

单线程 `scheduleWithFixedDelay`（线程 `match-spectate-sweeper`，守护线程），缺省 10 s，每轮 `try/catch Throwable`（JDK 调度器遇异常会永久停止，`m-spec` §12.1 第 14 条）；每轮 S_W_SWEEP（摘掉的条数计 `index_evictions{sweep}`），并用 ZCARD 采样 `xm_match_watchable_battles`。
多实例重复执行幂等，不抢锁。不挂在 matcher 上（`m-spec` §0.3）。

落地：
- 第一轮在一个间隔之后（`start` 不当场跑一轮，所以启动后头 10 s gauge 读数为 0）；一轮两条命令各等至多 3 s（`OP_BUDGET_MS`），互不依赖：摘失败了照样采样，采样失败 gauge 停在上一次的读数。
- **自己不带生命周期**（`SweeperControl`：`start()` / `stop()`）：构造时不起线程、不碰 Redis；由 `MatchLifecycle` 在启动第 8 步（凑单之后、评分消费之前，Dubbo 已导出）起，停机时在「等在途 163」之后停。
- **`stop()` 当场中断手上那一轮、不等它做完**（lead 在集成阶段的裁决）：停清扫是串在「排空工作池 ∥ 等在途 163」与「等在途 gather」之间的一步，它等多久最坏停机就多多久；清扫是幂等的只删操作，做一半被打断无害（已发出的命令 Redis 照样执行，没摘完的下一次启动 / 另一个实例接着摘）。
  中断之后只等清扫线程退出；`STOP_TIMEOUT`（1 s）只防「这一轮不理会中断」的违约情形，不计入停机的最坏时长。被停机打断的那一轮打 INFO。停机序列的预算见 §4.9。

### 4.8 观众 RPC 的寻址与结局分类（`ObserverDialer`，基于 `PlacementDialer`）

落地（`DefaultObserverDialer`）：包着 6.4 的 `placement.PlacementDialer`，用它**带硬截止的重载** `dial(placement, timeout, Deadline hardStop, call)` 按落点记录的地址直拨；引用来自直拨专用的客户端缓存 `PlacementClients`
（按地址缓存、实例段恒为空串），不是 gather 那份 `NodeRpcClients<BattleNodeService>`。不重试、永不抛；超时与硬截止由调用方给。每次调用记一个 `xm_match_observer_rpc_total{method, result}`。

| 结局 | 判据（直拨的结局 → 观众 RPC 的结局） | 163 | 清退路径（rewatch / enter_gather / concurrent_queue） |
|---|---|---|---|
| `Replied(tip)` | `Replied`：调通了（`addObserver` 的 tip 取应答的 `error_message.id`，`removeObserver` 恒为 0） | 按 tip（§4.4） | 计 `removed` |
| `Dead` | `RoomGone`：**三条证据都在硬截止之前拿齐**——请求确定没送达（`RpcFailures` 按 Dubbo 客户端侧的异常特征判）+ 目录 `xm:nodes:battle:0` 里同号节点的 `instance_id` ≠ 落点的实例 + 对原地址的 TCP 建连探测明确被拒绝 / 不可达（M16） | 视同 1004 | 只删标记，计 `no_record` |
| `NotDelivered` | `Unavailable(NOT_DELIVERED)`：请求确定没送达，但判不了死（目录里没这个号、同一实例、目录读失败、探测超时或连得上、硬截止已到）；另：调用方给的超时 ≤ 0（硬截止剩余不足 1 ms）时不发调用，也归这里 | 删标记 → 16018 `该战斗当前无法观战` | 只记日志，计 `rpc_failed`（标记照删） |
| `Unknown` | `Unavailable(TIMEOUT / OTHER)`：超时，或连上之后断开、对端回了传输层错误（鉴权失败、6.2 在途超限）；直拨器违约抛异常也归这里 | **保留标记** → 16018 `该战斗当前无法观战` | 只记日志，计 `rpc_failed`（标记照删） |

理由同 `m-spec` §4.3：battle 丢了租约但进程还活着时（`bn-spec` §7.10），它不在目录里，但直拨能到；只看目录会把活着的房间判死，只直拨又拿不到「号已被别的进程接手」的证据。
实现按 Dubbo 异常类别区分没送达与调用超时，单测与真 Triple 回环分别钉住（§10.5）。

**带硬截止的直拨**（lead 裁决 3；原稿的预算没有算上直拨器的本地余量 250 ms 与判死时至多 1 s 的目录读，按字面实现 163 最坏约 5.3 s、开局清退每人约 4 s）：
- 进来时硬截止已过 → 不发调用、`NotDelivered`；交给出站口的超时 = `min(timeout, 硬截止的剩余)`；本地等待不再另加余量越过它，等到点 → `Unknown`；
  请求没送达之后硬截止已到、或目录读（`BattleNodes.lookup(nodeId, instanceId, Deadline)`：至多等 `min(1 s, 剩余)`）/ 探测被截断 → `NotDelivered`。**到点一律按「没调通、不判死」返回**，时间不够永远不会被当成「这一局没了」。
- 163 的两跳传「请求截止 − 预留」（换场 Remove 1.2 s、Add 0.2 s），开局清退传每人的 3 s 截止。179 仍走不带截止的旧重载，行为不变。
- **夹不住的只有「发调用」这一步本身**（评审 R163-01）：`NodeCalls.call` 在调用线程上同步执行、没有截止，靠出站口「不阻塞」的契约。生产的出站口（`port.NodeClientCache`）取一把进程内的锁之后才发起调用，
  同一把锁在清扫销毁空闲引用（空闲 ≥ 360 s 的地址、60 s 一轮）时被短暂占着——按读 Dubbo 3.3.6 的实现是毫秒级，没有实测；「锁内摘表、锁外销毁」的结构性修复登记为遗留（实现记录）。到点不判死不受它影响。
- `removeAsync`（自我清退用）：另起一条虚拟线程 `match-spectate-evict-<n>`，固定 3 s 的超时与截止，发出即返回；返回的 future 一定完成、永不异常完成（执行器拒收时以 `NotDelivered` 完成）。不占 163 的在途许可。

### 4.9 线程与预算

| 号 / 任务 | 执行器 | 预算 | 过载时 |
|---|---|---|---|
| 163 | **虚拟线程** `match-spectate-<n>`（每个受理的请求一条），全局在途上限 `xm.match.spectate.max-inflight`（缺省 128）；`SpectateExecutor` | 受理时刻 + `xm.match.request-budget`（缺省 4500 ms）的 `Deadline`，由派发器给；每一次 Redis 读写只等到它，两跳 battle RPC 各有更早的硬截止（§4.4、§4.8） | in-band 16004 `服务器繁忙,请稍后再试`（outcome `overloaded`） |
| 163 复查的两次读 | 虚拟线程 `match-spectate-recheck-<n>`（每请求至多两条，只活到请求截止；不占在途许可） | 请求截止 | — |
| 163 的自我清退 | 虚拟线程 `match-spectate-evict-<n>`（`removeAsync`；不占在途许可） | 固定 3 s | — |
| 164 | `match-worker`（只有 Redis 操作） | 4500 ms | 信封 1003（164 没有 in-band 错误字段，同 M29） |
| 开局清退 / 公开 | gather 的虚拟线程 | 批读 1 s + 每人 3 s；公开 1 s（§4.6） | — |
| 清扫 | `match-spectate-sweeper`（单线程，守护） | 每条命令 3 s | — |
| 停机时等在途 163 | 平台线程 `match-stop-watches`（辅助线程，只在停机时存在） | `MatchBudgets.SPECTATE_DRAIN_TIMEOUT_MS` = 5 s | — |

- **163 怎么接到虚拟线程上**（lead 裁决 2；原稿只写了「Dubbo 线程上 `tryAcquire`、虚拟线程上执行」，6.4 的派发器只有 inline / `match-worker` 两条路）：`MatchMethodHandler` 多一个 `default Executor executor()`（null = 共用 `match-worker`）。
  `MatchDispatcher` 每次派发读一次，把任务投到它返回的执行器上；`execute` 抛 `RejectedExecutionException`（在途已满、已关闭）与「轮到执行时预算已用完」都走该处理器的 `onOverload()`，与工作池满同一个口径。
  截止、解析失败 / 未预期异常 → 信封 1003、请求计时都不用再写一遍；179 以后要挪出工作池也能用同一个钩子。`executor()` 自己抛异常按处理器异常回信封 1003。
- gate 调 match 的超时是 5 s（`m-spec` §9.2）；4.5 s 预算保证 match 先给出 in-band 应答。**`xm.match.request-budget` 调小时**（合法区间仍是 [500 ms, 4500 ms]，评审 R163-02）：163 的两道门槛是代码常量，
  预算 ≤ 2200 ms 时带旧标记的换场 / 随机观战恒回 16004（旧标记最长留 360 s），≤ 1000 ms 时凡是走到登记的 163 都回 16004。不拒启，启动时打一条 WARN（各留 100 ms 余量：低于 2300 ms / 1100 ms，`WatchBattleConfiguration.budgetWarning`）；缺省值不受影响。
- 虚拟线程上不在 `synchronized` 块内阻塞；Redisson / Dubbo 都用异步 API 加 `future.get(剩余预算)`（`m-spec` §12.1 第 13 条）。Redisson 单条命令最坏 4.2 s（`arch` §6「Redis 客户端超时」条：HEAD `9fde7d8` 与当前工作区都在 `:666-668`；`m-spec` §9.6 引的 `arch:607-609` 已过时，按节名找），
  所以 Redis 操作同样按剩余预算等；等超时而命令可能已生效时（S_W_ACQUIRE），尽力异步 S_W_RELEASE(本次的值)，再回 16004（这次释放可能被重发越过，§4.3）。
- 线程所有权（`AGENTS.md` §3）：全部阻塞 I/O 都不在 Netty I/O 线程或场景逻辑线程上；xm-match 没有场景状态。
- 在途许可（`SpectateExecutor`）：Dubbo 线程上当场试拿（不阻塞、不排队），拿不到即当场回 J 行①；拿到之后起一条虚拟线程，许可在同一个 `finally` 里归还（正常返回、跑到预算到点、`RuntimeException`、`Error` 都覆盖），
  `xm_match_spectate_inflight` 随之增减。自我清退的异步 Remove 与 164 的异步剔除不占这个许可（数量上界分别是「每次 163 至多一次」与「每次 164 至多 `limit` 条」）。`close()` 只是不再受理，不等也不打断在途请求。
- **停机序列**（lead 裁决 4；`MatchLifecycle`，日志编号「停机 n/6」）：停凑单 → 撤 Dubbo 导出 → **[排空 `match-worker`（≤ 10 s）∥ 等在途 163（≤ 5 s，辅助线程；实现不守约时再多等 1 s 后放弃）]** → 停观战清扫（当场中断，§4.7）→ 等在途 gather（≤ 10 s）→ 停评分消费 → 还租约。
  原稿要把「等在途 163」串在排空之后，那样 `SmartLifecycle.stop()` 里这几步最坏是 10 + 5 + 10 = 25 s；并行之后仍是 10 + 10 = 20 s，与 6.4 相同。
  **这 20 s 是设计预算**，取值与 `spring.lifecycle.timeout-per-shutdown-phase` 相同，但 Spring 不会拿它截断同步的 `stop()`（评审 LC-01；同 scene-drain-spec G16）；硬期限在进程外、从 SIGTERM 起算、还包含前两步：
  本机切片是 `stop-slice.sh` 的 20 s，容器部署是按这个值推出来的宽限期（ops-release-spec §0.6 #30：5 + 20 + 10 = 35 s）——这几步守住 20 s，那条公式才继续成立。等不完的 163：已抢到的标记留到 TTL，客户端按 gate 的超时收到信封 1003。
- 钉住检查：163 / 164 / 钩子在虚拟线程上等 Redis 的路径由一条常设的集成用例用 JFR 的 `jdk.VirtualThreadPinned` 事件核对（带对照）；`-Djdk.tracePinnedThreads` 在 surefire 下收不到输出（Temurin 21 只往普通的 `PrintStream` 打），没有加进 pom。
  真 Triple 直拨那一段只有手工验证；Dubbo 3.3.6 一个模型上的第一次调用会在调用线程上的 `synchronized` 里等扩展加载，生产路径的首调落在 `NodeRpcClients` 自己的建连线程上。

### 4.10 跨区 1V1：Java 侧要做的事

- **生产代码**：没有新路径（§2.7 每条都由 6.2 / 6.3 / 6.4 保证）。落地：6.5 没有为跨区改任何生产代码；按 §2.8 逐条核对了 6.2 / 6.3 / 6.4 的寻址代码，没有发现「只按节点号」的地方（静态审计，清单见实现记录）。
- **切片**：`XM_ZONES=2` 由 6.5 落地（lead 裁决 1；`tools/local/start-slice.sh` / `stop-slice.sh` / `battle-crash-window.sh`，§10.6）：多起 xm-scene-z2 与 xm-gate-z2，其余进程共用。xm-gate-z2 用缺省的 `xm.dubbo.match-url`（`tri://127.0.0.1:20888`），与 zone 1 的 gate 指向同一个 xm-match；
  区 2 在区服目录里的那一行由脚本经 xm-data 的运维接口管，**状态跟随 `XM_ZONES`**（=2 置 OPEN，=1 置维护；lead 裁决 5），不在 xm-gateway 的命令行上播种。
- **测试**：§10.3 的同号碰撞组件测试、§10.8 的 robot 场景。
- **CI**：7.1b 之后加两 zone 覆盖文件 `deploy/compose/two-zones.yaml`（Q22）；本批没有做。

### 4.11 对其它批次与文档的改动

| 对象 | 改动（原稿） | 落地（2026-10-08） |
|---|---|---|
| 6.4 xm-match | 从 `ticket.BattleTicketReissue` 抽出 `placement.PlacementDialer`；**`created_at_ms` 取 Redis `TIME`**（与 `deadline_ms` 同源，并入 M7；取值时刻仍在全员 Prepare 之后、写落点之前，同 `gather.go:286`；`m-spec` §9.6 第 4 步只写了「字段同 `gather.go:287-297`」，实现时核对）；落点的 `player_names` = 各成员**快照里的 `player_name`（角色名，不是账号）**，按成员顺序写（同 `gather.go:281-285`）；`GatherHooks` 的空实现换成 `SpectateGatherHooks`；`MatchClientMessageService` 把 163 派到虚拟线程、164 派到 `match-worker`；`m-spec` §8.1、§9.3、§9.9 随之改（§8.1 两行的新内容见本表下方）；停机序列（`m-spec` §9.8）在「撤 Dubbo 导出」之后加「有界等待在途 163（预算本身 ≤ 4.5 s）→ 停清扫器」；关闭 M22 | 前三条 6.4 已做（见下表）。其余 6.5 做了：钩子换成 `SpectateGatherHooks`（`MatchConfiguration.gatherHooks()` 已删）；163 经 `MatchMethodHandler.executor()` 跑在 `SpectateExecutor` 的虚拟线程上、164 跑在 `match-worker`；`InlineHandlers` 只剩 156 / 154，`MatchTips.watchUnavailable` 与 `MatchTip.FEATURE_UNAVAILABLE` 已删；停机序列按 §4.9（等 163 与排空**并行**，不是串在其后）；M22 关闭；`m-spec` 已回写。另给共享件加了：`PlacementDialer.dial` / `BattleNodes.lookup` 带 `Deadline` 的重载、`PlacementRecords`、`MatchProperties.Spectate`、`MatchLifecycle` 的两个口 |
| 6.2 xm-battle | 不改业务。`DevRoutingResolver.gateRouting` 挪到 xm-discovery `BattleRoutings.gatePart`（6.2 没合入时由 6.2 挪，否则 6.5 挪）；dev `add-observer` 接口注释写明「不写 match 的观战标记，开局不会清退 dev 观众」（Q14） | 已做（6.5 的先行件挪的；行为不变） |
| xm-discovery | `RedisKeys.matchWatching(pid)`、`matchWatchable()`；`BattleRoutings` | 已做 |
| 5.4 切片 | `zt-spec` §5.13 的「共用」行加 xm-match（battle 已在该行）；`start-slice.sh` / `stop-slice.sh` 同步 | 5.4 没有实施；`XM_ZONES=2` 的脚本由 6.5 落地，连同第三个脚本 `battle-crash-window.sh`（`SliceScriptsTest` 把它与 `start-slice.sh` 逐字钉着）。`zt-spec` 已回写 |
| xm-robot | `battle-smoke` 观战段（§10.7）；新场景 `battle-cross-zone`（§10.8）；`--visit-zone` 帮助文本 | 已做；`--visit-zone` 是 6.5 新增的选项（5.4 没合入） |
| `arch` | §4.24「匹配」（6.4 落地时的实际节号；本稿原写 §4.21）加「观战」小节（键、脚本、钩子、线程）；§5 线程模型加「163 虚拟线程 + 在途上限」；§11 加 §6 的指标 | 随本批的文档提交 |
| `PARITY.md` / `roadmap.md` / 盘点 | 见 §10.11 | 同上 |

**「6.4 xm-match」一行里 6.4 已做的三条要求——现状（2026-10-08，逐条回到 xm-match 的代码核对过）**

| 要求 | 6.4 的落地 | 6.5 接手时注意 |
|---|---|---|
| **`created_at_ms` 取 Redis `TIME`** | 已做。`gather.GatherPipeline` 在全员备战之后、写落点之前**再读一次** Redis 时间（`RedisClock.nowMs`）作 `created_at_ms`，同时填进 `CreateBattleRequest` 与落点记录；`deadline_ms` 用的是备战之前那一次读数。这一次读失败与种子生成失败同口径 → `internal`，全员取消 | 取值时刻同 `gather.go:286`，W13 的窗口判定可以直接用 |
| **`player_names` = 各成员快照里的角色名，按成员顺序** | 已做。落点记录的 `player_names` 逐个取 `BattlePlayerSnapshot.player_name`，顺序 = gather 的成员顺序（凑单：锚点在前 + 选中顺序；切磋：[发起者, 应战者]；整队：队长在前） | `summaryOf(BattlePlacement)` 原样映射即可 |
| **抽出 `placement.PlacementDialer`** | 已做，而且从一开始就在 `placement` 包（179 的类是 `reissue.BattleTicketReissue`，不在本稿写的 `ticket` 包）。接口 `PlacementDialer.dial(placement, timeout, call)`，结局三选一：`Replied(reply)` / `RoomGone` / `Unavailable(kind, detail)`，`kind ∈ {NOT_DELIVERED, TIMEOUT, OTHER}`；生产实现 `DirectPlacementDialer`，永不抛、不重试 | 本稿 §4.8 的 `ObserverDialer` 四种结局可以这样对：`Dead` = `RoomGone`、`NotDelivered` = `Unavailable(NOT_DELIVERED)`、`Unknown` = `Unavailable(TIMEOUT / OTHER)` |

与本稿设想不同、6.5 要按实现来的几处（正文在 match-spec §4.3、§9.7.1；这一段是 6.5 动工前记下的 6.4 现状，各条末尾的「→ 6.5」是 6.5 的落地）：

- **判死比本稿写的多一条证据**：本稿多处写「建连失败 + 同号换实例才判死」。实现里「建连失败」是「请求确定没有送达」（`RpcFailures` 按 Dubbo 客户端侧的异常特征判），它比「原地址连不上」宽，
  所以判 `RoomGone` 前对原地址再做一次 TCP 建连探测（`ConnectProbe`，上限 300 ms 且不超出这次直拨余下的预算），明确被拒绝 / 不可达才算；探测超时、连得上、没有实例号可比，都是 `Unavailable(NOT_DELIVERED)`。
  → 6.5：观众 RPC 原样沿用这三条证据（§4.8 已改写）。
- **直拨用单独的客户端缓存**：`placement.PlacementClients`（按地址缓存、实例段恒为空串、空闲 360 s 清扫），不是本稿 §0.1 写的与 gather 共用的 `NodeRpcClients<BattleNodeService>`。观众 RPC 经 `PlacementDialer` 发就自动用它，不要自己从容器里取 `NodeCalls<BattleNodeService>`（那一份只给 gather 的建房 / 销毁）。
  → 6.5：照做（`DefaultObserverDialer` 只依赖 `PlacementDialer`）。
- **`BattleNodes.lookup`**（判死时读目录条目）没有截止参数，实现里固定等 1 s；观众 RPC 若要按请求预算收短，再给接口加参数。
  → 6.5：先行件加了 `lookup(nodeId, instanceId, Deadline)`（至多等 `min(1 s, 剩余)`，已过不读、回 ERROR）与 `PlacementDialer.dial(…, Deadline hardStop, …)`（lead 裁决 3）；不带截止的旧签名行为不变。
- **票据的只读口**是 `ticket.TicketReader`（`read` / `readAll` / `status`；读失败或 HASH 损坏一律抛依赖异常，不折成「没有票」），16014 的判定用它。
  → 6.5：入口与抢占的「有没有票」在观战存储的脚本里判（`EXISTS`，原子性只能在脚本里做）；`TicketReader.read` 只用于登记成功后的复查（§4.4 末段）。
- **钩子**：`gather.GatherHooks`（`beforePrepare(members)` / `onStarted(placement)`）现在由 `MatchConfiguration.gatherHooks()` 给空实现，**不带条件装配**——6.5 提供 `SpectateGatherHooks` bean 时把那个 bean 方法删掉。
  调用位置与本稿一致：`beforePrepare` 在选好节点、定好期限之后、分队与任何备战之前；`onStarted` 在全员票据置 ready、同值补写落点之后。钩子抛异常只记日志、不影响开局；失败路径不调 `onStarted`。
  → 6.5：已删，`WatchableConfiguration.gatherHooks` 给 `SpectateGatherHooks`；仍不带条件装配。
- **163 / 164 的临时处理器**在 `dispatch.InlineHandlers`（inline：在 Dubbo 线程上当场回）。换成真处理器时删掉那两个 bean；非 inline 的处理器经 `MatchMethodHandler` 的 `handle / onOverload` 接进派发器，
  163 要走虚拟线程的话需要扩这个接口或另配执行器。派发器在十个方法里有任何一个没有处理器时拒启。
  → 6.5：已删；`MatchMethodHandler` 加了 `executor()`（lead 裁决 2）。过渡期间先行件把两个临时处理器挪进观战包的三个装配类、由各工作包整体替换，每个中间提交都保持十个方法有处理器。
- **停机序列**实际是：停凑单 → 撤 Dubbo 导出 → 排空 `match-worker` → 等在途 gather（至多 10 s）→ 停评分消费 → 还租约。本稿要加的「有界等待在途 163 → 停清扫器」接在「排空 `match-worker`」之后。
  → 6.5：等在途 163 与排空**并行**（lead 裁决 4），随后停清扫（当场中断）；见 §4.9。
- **发号租约**：本稿 §7.2 写的「163 / 164 不发号，照常服务」成立——租约丢失时派发层只拒 157 与 152。→ 6.5：不变（`LeaseLostTest` 钉住）。

**`m-spec` §8.1 的 163 / 164 两行改为**（列与该表相同；表下那条「156 / 154 / 163 / 164 不涉及 I/O，直接在 Dubbo 线程上回」改为只剩 156 / 154；**已回写进 `m-spec` §8.1**）：

| 号 | 正常 | 会话没绑定玩家 | 请求体解析失败 | 依赖故障 | 工作池满 / 在途满 / 排队超预算 |
|---|---|---|---|---|---|
| 163 | §3.1 | in-band 16004 `缺少玩家身份` | 信封 1003 | in-band 16004 `服务器繁忙,请稍后再试`（§3.1 第 2 / 4 / 6 / 8 / 10 / 11 行） | in-band 16004 `服务器繁忙,请稍后再试`（J 行①：在途已满，或轮到执行时预算已用完；outcome `overloaded`） |
| 164 | §3.3 | **照常回列表**（BW4，不看身份） | 信封 1003 | 读索引失败 → 信封 1003；读落点失败 → 逐条跳过（§4.5） | 信封 1003（M29） |

处理器内未预期异常（bug）两个号都回信封 1003，同 `m-spec` §9.9 第 5 条；163 已抢到的标记按值尽力释放（§3.1 J2 行）。

**配置校验**：`xm.match.spectate.*` 在 `MatchProperties` 里校验（§5.2 的范围），不合法拒绝启动，同 `m-spec` §9.8 第 4 步的口径。

---

## 5 配置与常量

### 5.1 `MatchBudgets` 增补（xm-api，纯函数，单测直接引用）

| 常量 | 值 | 出处 / 说明 |
|---|---|---|
| `ADD_OBSERVER_TIMEOUT_MS` / `REMOVE_OBSERVER_TIMEOUT_MS` | 3000 / 3000（落地：没有照原稿合成一个 `OBSERVER_RPC_TIMEOUT_MS`——`REMOVE_OBSERVER_TIMEOUT_MS` 是 6.4 已有的名字、已在 matched TTL 公式里，保留；只新增 Add 的） | `sp.go:32-36` |
| `WATCHING_TTL_SECONDS` | `= PLACEMENT_TTL_SECONDS` = 360 | `sp.go:51-59` |
| `SPECTATE_STALE_MS` | 360 000（过期分界 = 落点 TTL） | `sp.go:230-234` |
| `WATCHABLE_LIST_DEFAULT` / `WATCHABLE_LIST_MAX` | 20 / 50 | `lw.go:15-18` |
| `RANDOM_WATCH_ROUNDS` / `RANDOM_PICK_TRIES` | 2 / 3 | `wb.go:143-147`；`sp.go:242` |
| `GATHER_CREATE_STAGE_WORST_MS` | 22 200（6.4 已有） | `queue.go:342` |
| `WATCH_REWATCH_RESERVE_MS` / `WATCH_ADD_RESERVE_MS` / `WATCH_ADD_MIN_BUDGET_MS` | 1200 / 200 / 1000 | Java 独有（W10）。落地：有了硬截止，三个数值不必改——换场 Remove 的硬截止 = 请求截止 − 1.2 s，Add 的硬截止 = 请求截止 − 0.2 s，Add 之前剩余不足 1 s 不发 |
| `SPECTATE_DRAIN_TIMEOUT_MS`（落地新增） | `DEFAULT_REQUEST_BUDGET_MS + 500` = 5000 | 停机时等在途 163 的上限（§4.9） |

不进 `MatchBudgets` 的几个包内常量（不出现在跨进程不等式里）：`SpectateGatherHooks.PER_MEMBER_BUDGET_MS`（= `REMOVE_OBSERVER_TIMEOUT_MS`）/ `SMALL_OP_BUDGET_MS` = 1000 / `MIN_SYNC_RELEASE_MS` = 50；`SpectateSweeper.OP_BUDGET_MS` = 3000 / `STOP_TIMEOUT` = 1 s；
`MatchLifecycle.WATCH_DRAIN_GRACE` = 1 s；`WatchBattleConfiguration.REWATCH_BUDGET_FLOOR_MS` = 2300 / `ADD_BUDGET_FLOOR_MS` = 1100（启动告警的两道门槛）；`SpectateRules.NONCE_LENGTH` = 16。

### 5.2 `xm.match.spectate.*`（`xm-match/src/main/resources/application.yaml`）

| 配置 | 缺省 | 说明 |
|---|---|---|
| `sweep-interval` | 10 s | 清扫间隔（W9），> 0 且是整毫秒（≥ 1 ms），否则拒启 |
| `max-inflight` | 128 | 163 在途上限（W10），≥ 1，否则拒启 |

163 的预算沿用 6.4 的 `xm.match.request-budget`（4500 ms）。其余都是代码常量（理由同 `m-spec` §10.1：出现在跨进程不等式里）。
落地：`request-budget` 的合法区间 [500 ms, 4500 ms] 是 6.4 冻结的，没有为 163 提高下限；低于 2300 ms / 1100 ms 时启动打一条 WARN（§4.9），不拒启。

### 5.3 数值不等式（实现与评审核对）

| 不等式 | 数值 | 说明 |
|---|---|---|
| 观战标记 TTL ≥ 战斗最长时限 | 360 ≥ 300 | 标记到期时该场必已收尾 |
| 过期分界 = 落点 TTL | 360 s | 读路径与清扫同一口径 |
| matched TTL 每人项 ≥ 开局清退每人最坏 | 3 s ≥ 3 s | `REMOVE_OBSERVER_TIMEOUT_MS` 不能改大（`queue.go:344-380`）；每人的读落点 + RPC + 删标记共用这一个截止（§4.6） |
| 163 预算 < gate → match 超时 | 4500 < 5000 ms | match 先回 in-band |
| 换场 Remove 预留 + Add 最低预算 ≤ 预算 | 1.2 + 1.0 ≤ 4.5 s | §4.4 第 4 步。**只对缺省预算成立**：`request-budget` 可以合法地配到 500 ms，配到 2.2 s 及以下时带旧标记的换场恒回 16004、1.0 s 及以下时登记恒回 16004（启动告警，§4.9） |
| 两跳的硬截止都早于请求截止 | 截止 − 1.2 s、截止 − 0.2 s | 直拨的本地等待、目录读、探测都夹在硬截止之内（§4.8），163 的最坏耗时不越过预算 |
| 停机时等在途 163 的上限 ≥ 163 的预算 | 5000 ≥ 4500 ms | 守约的 163 一定在这段时间里结束 |
| 停机第 3–5 步的设计预算不变 | max(10, 5) + 10 = 20 s | 等 163 与排空并行、清扫当场中断（§4.9） |
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

- `xm_match_watchable_battles` 是**每个实例各自采样同一个全局 ZSET**：多实例部署（`ops-release-spec` Q22 生产起点 match 2 副本）时看板与告警取 `max`，不能 `sum`（同 6.4 `queue_depth` 翻倍的教训，`m-spec` §11）。
  `xm_match_spectate_inflight` 是每实例的真实在途数，求和才有意义。

**落地的取值与计数口径**（表里的八个新指标都已实现，Micrometer 名是点分的 `xm.match.watch.battle` / `xm.match.list.watchable` / `xm.match.spectate.evictions` / `xm.match.watchable.index.evictions` /
`xm.match.watchable.anomalies` / `xm.match.watchable.battles` / `xm.match.observer.rpc` / `xm.match.spectate.inflight`；标签取值与上表逐字相同，枚举在 `MatchMetrics`，全部标签组合启动即预建）：

- `watch_battle_total`：进了处理流程的请求恰好记一个。`internal` = 16004（身份缺失、依赖故障、**流程内剩余预算不够发下一跳**）与未预期异常（信封 1003）；`overloaded` = 在途已满或轮到执行时预算已用完（在 `onOverload()` 里记，没进流程）；
  `queued` = 16014 的三个出口（入口持票、抢标记时有票、复查命中票据或锁）；`already_watching` 只计 16016。请求体解析失败不计。
- `spectate_evictions_total`：每处理一条旧标记记一个结局。`reason=rewatch` 是 163 入口处收拾旧标记（换场 / 随机 / 脏标记），**显式重看同一场只删标记、不计**；`reason=enter_gather` 见 §4.6；
  `reason=concurrent_queue` 是 163 的自我清退，在异步 Remove 的结局回来时才记——口径在评审修正后**扩大**：除「登记成功后的复查命中」外，还包括「重看同一场时抢标记发现已有票据」补发的那一条（W17）。
  `result`：`removed` = RemoveObserver 调通（battle 幂等，房间里没有这名观众也算）；`no_record` = 没有落点或直拨判死；`invalid_mark`；`read_failed` = 读落点失败或损坏（不发 RPC）；`rpc_failed` = 没送达 / 结局不明。
- `watchable_index_evictions_total` 计的是**摘掉的成员数**：163 的同步剔除（`room_missing` / `dead_node` / `missing_record` / `invalid_member`）只在存储回报「真的摘了」时计；164 的异步剔除（`invalid_member` / `stale` / `missing_record`）按发出计；`sweep` 按脚本返回的条数计。
- `watchable_anomalies_total`：`corrupt_record` 只由 163 / 164 计（开局清退读到损坏记录计 `evictions{enter_gather, read_failed}`）；`publish_failed` 含「抛异常」与「落点不在 / 已不是这一次的 attempt」两种。
- `observer_rpc_total` 由 `DefaultObserverDialer` 记，每次调用一个，调用方不重复记；`removeAsync` 的那一次记在 `method=remove`。
- `watchable_battles` 在清扫器的每一轮采样；第一轮在一个间隔之后，所以启动后头 10 s 读数为 0，采样失败时停在上一次的读数。

---

## 7 隐患与边界

### 7.1 实现必须守住的坑

1. **1004 是唯一的「房间不在」信号**（`room.cpp:783`），其它码一律 16018 `该战斗当前无法观战`；「节点已死」只在**建连失败 + 同号换实例**时视同 1004，超时永远不算（§4.8；落地是三条证据：请求确定没送达 + 同号换实例 + 原地址探测明确连不上，且都在硬截止之前拿齐）。
2. **先有最终落点、再进索引**（S_W_PUBLISH 的 attempt 条件）；**读到落点缺失时只 ZREM、永不 DEL 落点**（GET 之后才预写的记录不能删，`wbcw_test.go:175`）。
3. **建房窗口的输入不能晚于读落点**：`published` 与 `checkedAtMs` 和落点在同一段只读脚本里取（W6）；剔除时再按 attempt 守护（W7）。删落点会让 179 失去定位，客户端判 BattleGone。
4. **换场的 Remove 必须先于 Add 完成**（或超时）：随机模式可能重挑同一场，迟到的 Remove 会把刚登记的观众摘掉。只有复查命中后的自我清退可以异步。
   残余风险（同基线 `wb.go:107-111` 的同步 Remove）：Remove **超时**时请求可能仍在 battle 的投递路上，随后重挑到同一场的 Add 理论上可能先被执行，观众随即被摘、收到 166 REMOVED，客户端按 Ended 收场后可再发 163。
   不为这个窗口加「超时后本轮排除旧场」之类的逻辑：它只在 battle 慢于 3 s 时出现，排除旧场反而改变随机选场的客户端可见分布。
   **自我清退异步的残余风险**（Java 独有，基线是同步的，`wb.go:223`）：复查命中 → 异步 `remove(X, concurrent_queue)` 发出 → 按值释放标记 → 回 16014；若并发的 gather 随即失败
   （例如 PVE_SOLO 的 `no_battle_node` / `prepare_failed`：DELETE_ALL 删票、锁没写或已被 Cancel 删掉），玩家立刻再发 163(X) 能过入口，新的 AddObserver(X) 若赶在那条迟到的 Remove 之前被 battle 执行，
   新登记随即被摘、收到 166 REMOVED（客户端 Ended，可再发 163）。Remove 比 16014 应答先发出，新请求还要走完一次客户端往返和入口的几次 Redis 读才能追上，实际几乎不可达。
   不改成同步：复查命中时剩余预算可能只有约 0.2 s（§4.4），同步等反而把 16014 变成 16004。归入 W10 的客户端可见面（只在竞态下）。
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
16. **复查收窄而不关死 TOCTOU**（两版相同）：没有票据的入口只有切磋（151 接受）。它的 gather 在第 2.5 步读标记时本次 163 还没写标记（读不到、不清退），
    Prepare 写锁又落在本次复查之后——复查读票、读锁都落空，玩家同时是 X 的观众与新局的参战者。后果：参战 177 到达后客户端直连改服务新局，观战侧 Superseded 收回 None（`SC.cs:339-368`）；
    X 的名单里留一名幽灵观众（占一个名额，直连已被客户端关掉，帧丢弃）；match 标记还在，下一次 163 或开局清退会摘掉它。有票据的入口（凑单、PVE_SOLO、整队、活动）票据先于 gather 存在，
    由入口检查、W2 与复查覆盖（**「重看同一场」是例外**，见第 17 条）。不为切磋再加锁或二次复查：战斗锁不在 `{match}` slot，做不成原子；收益只是少一个幽灵观众。单测钉住「复查时锁尚不存在 → 成功」这一既定结局（§10.3）。
17. **重看同一场留下的「在名单、无标记」**（评审 S65-STORE-1，落地 W17）：显式重看同一场在入口只删旧标记、不发 Remove（第 5 条），所以从删掉旧标记到 AddObserver 之间，玩家仍登记在这一场、却没有标记指着它——
    而开局清退只认标记。**不调 AddObserver 就回包**的出口都要有交代：① 抢标记回有票（16014）→ 补一条异步自我清退（基线在同一交错下 SETNX 成功 → 幂等的 AddObserver → 复查命中 → RemoveObserver，结局一致）；
    ② 登记前剩余预算不足 1 s（16004）→ 保留刚抢到的标记；③ 入口删旧标记**失败**（16004）→ 旧标记原样留着，不补发那次异步删除。其余出口与基线相同（不在线、读落点失败等同样留下「在名单、无标记」，随那一场结束清理）。
    残余：③ 里「等到截止、而那条删除其实已在路上并随后成功」（Redis 卡顿时可见，随那一场结束清理）。客户端可见的码与文案都不变。
18. **S_W_ACQUIRE 挡不住迟到的重发**（评审 S65-STORE-2）：见 §4.3 表下。标记只是提示，漏掉的最长留 360 s；极窄窗口里下一条 163 可能被它挡成一次误导的 16016。只在 Redis 卡顿 ≥ 2 s 且预算所剩不多时出现。
19. **带硬截止的直拨管不到「发调用」这一步**（评审 R163-01）：见 §4.8。

### 7.2 与其它系统的交互边界

- **观众没有战斗锁、不冻结**：观战中可以移动、换图（含 5.2 跨节点）、传送（5.4 的 226 不检查观战）、组队、聊天；scene 的战斗闸（`sb-spec` §2.3）不作用于观众。同基线。
- **5.4 传送 / GO-5 重定向**：观众换到另一个 zone 的 gate 时大厅会话变了；客户端在大厅断线时把观战作废回 None（`SC.cs:42`），直连随之关闭；
  match 的标记留到 TTL 或下一次 163 / 开局清退。再次 163 同一场走 battle 的「会话变了」幂等分支（关旧直连、按新会话推 177）。
- **5.5 / 6.2 排空**：battle 关闸只挡 createBattle，`addObserver` 不看准入闸（`BattleNodeServiceImpl.java:146-157`），排空中的节点上的房间照样能观战，直到房间结束。
- **6.2 租约丢失**：房间不作废、进程仍活，直拨照样能登记观众（W5 的收益）；目录里同号被别的进程接手后，直拨建连失败才判死。
- **7.2 GM 回档**：只看战斗锁，观众不受影响。
- **6.4 发号租约丢失（M28）**：163 / 164 不发号，照常服务；只有开局（gather 第 1 步）受影响，所以这期间不会产生新的可观战场次，开局清退钩子也不会被调用。
- **xm-match 停机 / 崩溃**：在途 163 已抢到的标记留到 TTL（只是「可能在观战」的提示，W4 的同一口径）；停机时有界等待在途 163（至多 5 s，与排空工作池并行，§4.9），随后停清扫（当场中断）。
- **限频**：163 / 164 按缺省每秒 3 条；Unity 面板「刷新」按钮与 robot 都要按这个节奏。

---

## 8 建议的有意差异

> **落地状态（2026-10-08）**：W1–W15、X1–X7 全部按本节落地，BW1–BW10 照搬；其中 W2、W5、W8、W9、W10、W15 的措辞按实现更新过（行内标「落地」的部分）。
> **本批新增 W16–W20、X8**（接在各表表尾）：W16、W18、W19、W20 是实现中形成的差异，W17 是评审（S65-STORE-1）之后补的。C1、C2 没有做（两版同改候选）。涉及改 mmorpg 的都只登记、未动 mmorpg，需用户同意。

### 8.1 观战（W）

| # | 项 | 基线 | Java | 客户端可见？ | 两版同改？ |
|---|---|---|---|---|---|
| W1 | 键空间 | `spectate:*` 三类键，不带 tag，与票据分属不同 slot | 全部落在 `{match}` tag；复用 6.4 落点记录 | 否 | 否 |
| W2 | 抢标记 | 先查票据、后 SETNX，靠事后复查收窗（`wb.go:187-231`） | S_W_ACQUIRE 原子完成「无票据 ∧ SET NX」，事后复查保留（锁不在同一 tag，gather 也可能在抢占之后才开始）。落地：回 `queued` 时仍按本次的值释放一次（重发时首轮可能已写入）；它与「重看同一场」的组合另见 W17 | 只在竞态下：「检查之后、抢标记之前」建出的票据不再先推一条 177(OBSERVER) 再清退，结果码同为 16014 | 可选 |
| W3 | 删标记 | 无条件 DEL（`wb.go:237`；`sp.go:311-315`），同一玩家两个并发请求会删掉对方刚抢到的标记 | 值带 nonce，按值删 | 否 | 可选 |
| W4 | AddObserver 结局不明 | 删标记；之后开局清退找不到可能已登记的观众（B-s4） | 只有明确拒绝或确定没送达才删；超时 / 断开时保留，不补发 Remove | 否（应答同为 16018 `该战斗当前无法观战`） | 可选 |
| W5 | 观众 RPC 寻址与判死 | 按 etcd 镜像找节点（`sp.go:355-358`），找不到回 16018 `该战斗当前无法观战`、不剔除，死节点上的场次留在列表里干扰随机观战直到 360 s | 直拨落点地址；判死 → 视同 1004 → 剔除，随机换下一场（与 179 共用，M16）。落地：判死要**三条证据**都在硬截止之前拿齐——请求确定没送达 + 同号换了实例 + 对原地址的 TCP 建连探测明确被拒绝 / 不可达；超时、到点、探测没有结论都不判死（§4.8） | **是，很窄**：battle 丢租约但还活着时照样能观战；**号已被别的进程接手且原地址明确连不上**的死节点上的场次，指定观战的 `parameters[0]` 从「当前无法观战」变成「不存在或已结束」（码都是 16018），随机观战跳过它并剔除。号没有被接手的死节点（目录缺席 / 读失败）、主机被隔离（探测超时）仍是 `NotDelivered` → 「当前无法观战」、不剔除，与基线相同 | 建议与 M16 一并 |
| W6 | 建房窗口的输入 | 先 ZSCORE、后 GET，`checkedAtMs` 用本实例墙钟（`wb.go:150-165`）；ZSCORE 失败按未公开继续 | 一段只读脚本原子取 `{已公开, attempt, 落点, TIME}`；脚本失败整体回 16004 | 只在时钟偏斜或 Redis 故障时 | 否 |
| W7 | 剔除守护 | 靠窗口判定避免删掉 D82 改写之后的记录 | 剔除时比较落点的 attempt，被改写过就不动 | 否（纵深防御） | 否 |
| W8 | 随机选场 | 全集随机下标，挑中过期成员现场剔除，每次最多 3 挑（`sp.go:240-274`） | 一段 Lua 只在未过期区间里随机。落地：过期成员根本挑不到，所以选场不剔过期成员、也不删它的落点（基线是现场 DEL 记录 + ZREM）；过期成员只由 164 的懒剔除与清扫器摘，落点靠自己的 TTL | 只在过期成员堆积时（基线这时可能误回 16017） | 否 |
| W9 | 兜底清扫 | 每实例每 500 ms，挂在 matcher 上 | 独立定时任务（`match-spectate-sweeper`），10 s 一轮、可配；第一轮在一个间隔之后；由 `MatchLifecycle` 启停 | 否（读路径已按分数过滤） | 否 |
| W10 | 执行与预算 | gRPC 线程同步执行；超过 5 s → 信封 1003，后台 RPC 不取消（B-s5）；自我清退同步等 | 虚拟线程 + 在途上限 128；4.5 s 预算、每跳按剩余预算夹紧；预算不够 / 在途满回 in-band 16004；自我清退的 Remove 异步。落地：两跳 battle RPC 各有早于请求截止的硬截止（−1.2 s / −0.2 s），直拨的本地等待、目录读、探测都夹在其内（§4.8）；预算可配（`xm.match.request-budget`，[500 ms, 4500 ms]），调到 2300 ms 以下时启动告警（≤ 2200 ms 时带旧标记的换场恒回 16004，≤ 1000 ms 时凡走到登记的都回 16004，§4.9） | 只在过载 / 慢节点时：应答改为 in-band 16004 / 16018，不再是 5 s 后的信封 1003。**177 仍可能晚到**：AddObserver 超时（`Unknown`）时 battle 可能已登记并推 177，Dubbo 不取消服务端执行（同基线 B-s5）；客户端 `BattleDirectLink` 对任何完整的 177 都会建连（`mmorpg-client` `Assets/Scripts/Net/BattleDirectLink.cs:226-260`），观战相位已回 None 时帧被丢弃，参战中则走 Superseded 兜底（`BC.cs:684-693`）。W4 保留标记，让开局清退能摘掉这类观众。另有自我清退异步带来的极窄竞态（§7.1 第 4 条：并发 gather 秒败后立刻重看同一场，新登记可能被迟到的 Remove 摘掉、收到 166 REMOVED） | 否 |
| W11 | 观众路由与在线判定 | 读 `player:session`，要求 ONLINE；zone 读位置记录，读失败按 0（`wb.go:118-139`、`:269-281`） | 读在线目录，有条目才算在线；路由四个字段都取在线目录（177 也按在线目录推，路由与推送目标一致） | 否 | 否 |
| W12 | 身份 | 会话为 0 时回落请求体（B-s9） | 只认会话（同 M3） | 只影响伪造请求 | 同 M3 |
| W13 | 时钟 | match 本机时钟 | Redis `TIME`（并入 M7） | 只在时钟偏斜时 | 同 M7 |
| W14 | 指标 | `already_watching` 把懒清退也算进去（B-s6） | 只计 16016；清退另计（§6） | 否 | 否 |
| W15 | 落点在但缺 `pb`（基线：记录在但 summary 为空） | 164 里 DEL 记录 + ZREM（`lw.go:74-77`） | 按损坏处理：跳过、不剔除、计 anomalies（§3.3）。落地：观战读路径的损坏判据另含「`a` 字段缺失或与消息里的 attempt 不一致」「落点键不是 HASH」（`PlacementRecords.parse(battleId, a, pb)`；179 仍只看 `pb`） | 只在数据损坏时：该条在列表里一直缺席而不是被剔除一次；163 回 16004 | 否 |
| W16 | 删标记失败之后 | 忽略删除失败、继续往下走（`wb.go:99-113`、`:237`）；随后的抢占被自己没删掉的标记挡成 16016「已在观战另一场战斗」 | 入口删旧标记失败、随机模式换下一轮之前回滚本轮标记失败 → 回 16004「服务器繁忙,请稍后再试」（outcome `internal`），换场 / 脏标记再尽力异步删一次。终局回包前的回滚失败不改应答，只改走异步释放。原因：带着一个没删掉的旧标记往下走，得到的 16016 是一条误导的应答（玩家并没有在别处并发观战） | 只在 Redis 故障时：16016 变成 16004 | 否 |
| W17 | 重看同一场时「在名单、无标记」的出口 | 同场只删标记 → SETNX → AddObserver（幂等）→ 复查命中票据 / 锁 → RemoveObserver(`concurrent_queue`) + 删标记（`wb.go:103-106`、`:193-224`）；删旧标记失败时标记还在 | W2 与 W10 让 Java 多出两个「不调 AddObserver 就回包」的出口，各补一条交代：① 抢标记回有票（16014）→ 另发一条异步自我清退，结局同基线；② 登记前剩余预算不足 1 s（16004）→ 保留刚抢到的标记（同 W4 的口径）；③ 入口删旧标记失败（16004，W16）→ 旧标记原样留着，不补发异步删除（评审 S65-STORE-1；§7.1 第 17 条） | 否（码与文案不变；不补的话后果是那一场多一名幽灵观众、开局清退摘不到他） | 否（Java 独有的出口） |
| W18 | 索引成员与标记值的规范形 | 成员用 `strconv.ParseUint` 解析（接受前导零）；标记值只有 battle_id | 成员只认规范的无符号十进制（非 0、无符号与前导零），其余按非法成员、按**原串** ZREM——`007` 若当成 7 号，之后按战斗号做的剔除摘的是 `7`，这个成员永远留在索引里；标记值必须是 `<battle_id>:<16 位小写 hex>`，解析不了的（含基线那种旧值）一律脏值、按原串删 | 只在脏数据上 | 否 |
| W19 | 开局清退读标记与每人的时间 | 逐人 GET 标记，出错的那一人返回、其余照常；RemoveObserver 3 s，其余 Redis 小操作算在公式的余量里（`sp.go:276-315`；`queue.go:344-380`） | 开头一次批读全员标记（S_W_MARKS，至多 1 s），失败则**整次跳过**清退、标记不删；每名有标记的成员一个 3 s 的截止，读落点 + RemoveObserver + 同步删标记共用（§4.6）。原因：批读一次往返、读主库；Java 的 Redis 单命令最坏约 4.2 s，各等各的会让单人超出 matched TTL 公式里的 3 s 项 | 否（都只在 Redis 故障 / 慢时有别；清退本来就是尽力而为，客户端靠参战 177 改连兜底） | 否 |
| W20 | 停机时的在途 163 与清扫 | 进程退出不等；清扫随 matcher 的轮次 | 撤 Dubbo 导出之后有界等在途 163（至多 5 s，与排空工作池并行），随后当场中断清扫；等不完的 163 由 gate 的超时回信封 1003，已抢到的标记留到 TTL（§4.9）。原因：Java 的 163 在自己的执行器上，工作池的排空管不到它；并行与当场中断是为了加了观战之后停机不比 6.4 长（lead 裁决 4） | 只在停机时：多数在途 163 能拿到 in-band 应答 | 否 |

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
| X8（落地新增） | 本机双 zone 的形态与 robot 的几处做法：基线是「每个 zone 一套部署、match 每 zone 一份」（`bsc.yaml:4-5`），robot 的 A / B 并发登录、只比两人的 gate 地址（`bsc.go:135-166`）。Java：`XM_ZONES=2` 只多起区 2 的 gate 与 scene，其余进程共用，**两个区的第一台 scene / gate 都是 1 号**；区 2 在区服目录里的状态由切片脚本跟随 `XM_ZONES` 管；robot 三个号顺序登录，「两个区的 gate 端点不同」用场景另发的 assign-gate 核对，失败行的步骤号是小写 | 同号节点是有意的（§2.8 的碰撞靠这个形态暴露）；顺序登录更保守；`StepTrack` 只认小写 | 否 |

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

落地（2026-10-08）：C1、C2 都没有做，行为照搬 BW1 / BW7。robot 原计划用结果行字段 `s12_ready_residue` 观察 C1 修好之后的变化，评审修正后它的含义变成「切磋开局之前的 153 见到过 READY」（§10.7 S12），
C1 落地后这个字段观察不到变化（153 不受它影响）；要保留观察点得另加一条 163 探针，没有做。

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

落地（2026-10-08）：E3–E6 已随本批改进盘点的 `java:` 列（基线描述原样保留，勘误写在后面）；E8 已改进 `bn-spec` §7.10。E1、E2、E7 与 E3 的 `bss.go` 那一半在 mmorpg 一侧，只登记、未动。
robot 的实测补充 E3：Java 切片上新号单人 PVE（Dungeon 1）是 7–10 回合，不是基线记录的 13–21 回合（§10.7）。

---

## 9 开放问题（每条附推荐答案）

> **落地结论（2026-10-08）**：28 个问题全部采纳推荐答案（lead 裁决），其中 Q2、Q3、Q9、Q16 的推荐方向不变、但规格没写清的接入方式与数值由开工前的六条裁决补上；Q22、Q23 本批不做。逐条如下；问题原文保留在后面。
> 涉及改 mmorpg 的（Q5、Q6 的 C1 / C2）只登记、未动 mmorpg，需用户同意。

| 问题 | 落地 |
|---|---|
| Q1 观战代码放哪 | 采纳：xm-match 的 `com.game.match.spectate`（16 个类，三个装配类）；共享件的改动在 `dispatch` / `lifecycle` / `placement` / `gather` / `metrics` / `support`（§4.1、实现记录） |
| Q2 163 的执行器 | 采纳：虚拟线程 + 在途上限 128（`SpectateExecutor`），164 留在 `match-worker`。接入方式是裁决 2：`MatchMethodHandler.executor()`，拒收走 `onOverload()`（§4.9） |
| Q3 观众 RPC 直拨还是按目录 | 采纳：直拨，判死与 179 共用 `PlacementDialer`（三条证据）。预算口径是裁决 3：给直拨与目录读加带 `Deadline` 的重载，到点一律「没调通、不判死」（§4.8） |
| Q4 结局不明保不保留标记 | 采纳：保留、不补发 Remove（W4）；未预期异常时的口径同此（§3.1 J2） |
| Q5 ready 残留自愈 | 采纳：照搬（BW1），C1 登记为两版同改候选，没有做 |
| Q6 已结束的战斗主动出索引 | 采纳：不做（BW7），C2 登记。连带后果：切片上残留的已结束场次会让随机观战扑空，robot 在 S1 之前加了预清理（§10.7） |
| Q7 请求时对称清退 | 采纳：不做（BW3）；robot S6 钉住「观战中可以排队、直连上没有 166」 |
| Q8 随机模式非 1004 拒绝换不换场 | 采纳：不换（BW6） |
| Q9 163 的预算 | 采纳做法；数值不变（1.2 s / 0.2 s / 1.0 s），靠硬截止把直拨的本地余量与目录读夹进去（裁决 3，§4.4、§5.3）。评审后补：预算调小时的启动告警 |
| Q10 建房窗口判定 | 采纳：做了（`SpectateRules.roomMayBeCreating`；`Dead` 与 1004 同走窗口判定） |
| Q11 按 zone 过滤 | 采纳：不过滤 |
| Q12 清扫间隔 | 采纳：10 s、可配（`xm.match.spectate.sweep-interval`） |
| Q13 复查读锁出错 | 采纳：照搬，按有锁自我清退、回 16014（BW2）；等到请求截止同此 |
| Q14 dev `add-observer` 不写标记 | 采纳：`DevBattleController` 的接口注释写明；`bn-spec` §7.12 已补一句 |
| Q15 dev 登记接口 | 采纳：不加（6.3 先于 6.5 落地，前提成立） |
| Q16 跨区 robot 的参数名 | 采纳名字 `--visit-zone` / `XM_ROBOT_VISIT_ZONE`（缺省 2，≥ 1）。5.4 没有合入，这个选项由 6.5 新增（裁决 1）；只有 `battle-cross-zone` 要求它与 `--zone` 不同，帮助文本现在只写了这一种用途，5.4 的 `travel` 落地时再补 |
| Q17 独立场景 | 采纳：`battle-cross-zone` |
| Q18 跨区带观众 | 采纳：区 B 的 C 观战（Z5 / Z6） |
| Q19 跨区用哪个 config | 采纳：`battle_config_id = 1`（`battle-smoke` 的 1V1 用 0） |
| Q20 同 zone 优先 | 采纳：不做 |
| Q21 两台 xm-match | 采纳：不做，随 7.6 |
| Q22 CI 加双 zone | **本批不做**：7.1b（整栈 compose）没有落地，`deploy/compose/` 里还没有 `stack.yaml`。前置清单①–⑤原样留给 7.1b；`ci-spec` 的端口表与 `zt-spec` §11.9 各登记了一句 |
| Q23 Go robot 跨版本 | **没有跑**：本机没有 Go 工具链（§10.9）。验收证据是 Java robot 的 OK 行 |
| Q24 Z7 依赖 6.3 | 6.3 已落地 → Z7 必选、没有降级 |
| Q25 1V1 读归属区 | 采纳：不读。xm-match 主代码里用到 zone 的地方——`QueueService`、`DefaultMemberPrecheck`、`ScenePreparer` 的端点、`GatherPipeline` 的 zone 组成（都取位置记录或快照），票据的编解码，163 的观众路由（取在线目录）——没有一处是归属区 |
| Q26 等不等 X16 | 采纳：不等。Z1 的「角色列表 zone_id」断言没有实现（场景只记一条观察）；X16 未合入时从区 2 进来的新号归属区会记成 1，1V1 不读它 |
| Q27 终局 120 s | 采纳：保持 120 s |
| Q28 指纹跨 zone 不一致 | 采纳：跟 6.4（缺省 warn）；`CrossZoneGatherTest` 补了「两侧指纹不同」的用例（warn 照常开局、enforce 各在自己的 zone 解冻） |

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
- **Q22 CI 要不要加双 zone？** 推荐**要**：7.1b 加两 zone 的整栈形态（xm-gate-z2、xm-scene-z2，端口照 `zt-spec` §5.13；思路同 `ci-spec:758` 的 `two-scenes`，但 gateway 要换参数，所以用覆盖文件而不是 profile，见前置③），在第三期之后跑 `battle-cross-zone`；本机连续绿了再加。
  **前置**：① `ci-spec` 风险 15 / Q9（:992、:1037）——xm-battle 的通告地址一址两用。6.2 工作区已按 Q9 拆开：`xm.battle.client-advertise-host`（环境变量 `XM_BATTLE_CLIENT_ADVERTISE_HOST`，空 = 取 `xm.advertise-host`）只进票据与目录的 `client_host`，
  `rpc_host` 仍用 `xm.advertise-host`（`xm-battle/src/main/resources/application.yaml:47-48`、`:63-65`；`BattleIdentity.java:13`）。整栈剩下的事是在 compose 里给 xm-battle 配上这个变量（宿主可达的地址），否则宿主上的 robot 连不上直连，单 zone 的 `battle-smoke` 也一样卡在这里；
  ② xm-gate-z2 在 compose 里的 `xm.dubbo.match-url` 要指向 `tri://xm-match:20888`（本机切片的缺省 `127.0.0.1` 在容器网络里不成立），与 zone 1 的 gate 同一个值；
  ③ **gateway 要同时播种两个区**：`seed-zones` 必须 [0] 与 [1] 一起给（Spring 列表跨来源不合并，`zt-spec:829`），而 gateway 是共用服务、不能按 profile 换参数。
  推荐做成覆盖文件 `deploy/compose/two-zones.yaml`（`-f stack.yaml -f two-zones.yaml`，覆盖 gateway 的 seed 参数并加 xm-gate-z2 / xm-scene-z2，二者复用现有镜像、不写 `build`），
  不要在单 zone 的 stack 里常驻一个 OPEN 的 2 区（没有 gate 的 OPEN 区会让按区选 gate 的场景失败）；
  ④ **模块清单漂移守卫**（`ci-spec` §4.5 第 4 步）只比对不带 profile 的 `stack.yaml` 的服务集合，z2 服务放在覆盖文件或 profile 里才不会判红；
  ⑤ `zt-spec` §11.9 把多 zone 用例（MZ1–MZ11，含故障注入）定为「手工 / IT，不进 CI」。本条只把 `battle-cross-zone`（以及 5.4 的 `travel` 场景，若 5.4 同意）放进 CI，
  故障注入类仍手工；7.1b 采纳时在 `ci-spec` 与 `zt-spec` 各登记一句，避免两份规格对「多 zone 进不进 CI」说法相反。
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

> **落地（2026-10-08）**：§10.2–§10.5 是开工前的计划，已按实际的类名订正；**测试类的实际分布、各自钉了什么、运行证据以文末实现记录「测试类清单与模块级证据」为准**。本批没有动 proto。
> 与计划的主要出入：钩子的调用位置用例放在新类 `GatherHooksCallSiteTest`（没有改 6.4 的 `GatherPipelineTest`）；观众 RPC 的用例在 `DefaultObserverDialerTest` 与 `DirectPlacementDialerTest`；
> 没有 `ZoneMixTest`；`PlayerPushesTest` 是本批在 xm-discovery 新建的；另有计划外的 `SpectateFlowRedisIntegrationTest`（真流程接真存储，含 JFR 钉住探测）、`ObserverRpcLoopbackTest`、`SpectateExecutorTest`、
> `WatchBattleHandlerTest` / `ListWatchableHandlerTest`、三个装配类各自的测试，以及存储的抽象契约 `SpectateStoreContract`（内存替身与真 Redis 各跑一遍）。

### 10.2 纯函数（缺省执行）

| 测试 | 覆盖 | 对照基线 |
|---|---|---|
| `SpectateRulesTest` | `roomMayBeCreating` 五个分支：已公开；`created_at = 0`；窗口内；窗口外；检查时刻早于创建时刻（时钟偏斜）。`clampLimit`：0 → 20、1 → 1、50 → 50、51 → 50。`staleCutoff = now − 360 000`。`summaryOf` 保留成员顺序、不认识的 mode 原样。标记值编解码（无符号 battle_id、非法值） | `wbcw_test.go:55-174`；`sp_test.go:582`、`:809` |
| `MatchTipsTest`（扩充） | §3.2 的 9 条 `parameters[0]` 逐字节（半角逗号） | — |
| `BattleRoutingsTest` | 在线目录 → 路由：session、gate 节点与实例、zone；scene 字段为 0 / 空 | `sp_test.go:294` |
| `MatchBudgetsTest`（扩充） | §5.3 的不等式；`REMOVE_OBSERVER_TIMEOUT_MS` 在 matched TTL 公式里，1 / 2 / 5 / 10 人仍是 42 / 48 / 66 / 96 | `gsi_test.go:232` |
| zone 组成（落地：没有单独的 `ZoneMixTest`，在 `GatherPipelineTest` 与 `CrossZoneGatherTest` 里） | 快照 zone {1, 2} → `cross`；{1, 1} → `single`（按快照记，不按票据）；标签只有 mode / mix、不含 zone 值 | `gather.go:509-528` |

### 10.3 组件（替身：内存版 `SpectateStore` 与票据存储、`ObserverDialer`、`BattleLockReader`、在线目录、落点、手动时钟）

**`WatchBattleServiceTest`**（逐条对照基线；§3.1 每一行都要有：tip、`parameters[0]`、outcome、调没调 battle、标记状态）：

| 基线用例（`sp_test.go` 行号） | Java |
|---|---|
| RejectsQueuedPlayer `:236`、RejectsPlayerInBattle `:253`、RejectsOfflineObserver `:268`、RandomModeWithoutBattles `:280`、WithoutIdentity `:512`、GarbageWatchingMarkIsHealed `:527` | 照移 |
| BindsObserverWithGateOnlyRouting `:294` | 路由取在线目录、zone = 在线目录的 zone（W11）；`observer_name = account`；标记 TTL 360 s |
| ExplicitMissingRoomEvictsIndex `:326`、RandomModeEvictsFinishedOnlyBattle `:347`、RandomModeSwitchesToAnotherBattleAfterMissingRoom `:605`、RandomModeSkipsRecordlessMember `:640`、ExplicitRecordExpiredEvictsIndexWithoutRpc `:565`、NonMissingTipKeepsIndexAndDoesNotRetry `:661` | 照移 |
| RpcFailureKeepsIndexRollsBackMark `:367` | **拆成四例**：建连失败无换实例证据 → 删标记；超时 → 保留标记（W4）；建连失败且同号换实例 → 剔除、随机换场（W5）；同上但指定场未公开且在 22.2 s 窗口内 → **不剔除**、16018 `该战斗不存在或已结束`（`Dead` 与 1004 同走窗口判定） |
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
第三轮评审补：S_W_ACQUIRE 回 `queued` → 先按本次值 S_W_RELEASE 再回 16014（替身模拟「重发前首轮已写入」）；处理器内未预期异常 → 信封 1003、已抢到的标记按值释放（J2）；
在途许可在正常 / 超时 / 异常 / 未预期异常四条出口都归还（`xm_match_spectate_inflight` 回到 0）；`Unknown` 之后同一玩家再发 163(同一场) → 只删标记、不发 Remove、Add 走幂等；
复查时锁尚不存在（切磋的 Prepare 晚于复查）→ 照常成功（§7.1 第 16 条的既定结局，防止有人「顺手」加二次复查而改变行为）。

**`WatchableListServiceTest`**：最新在前；剔除过期、缺记录、非法成员；读记录出错 / 损坏只跳过；读索引失败 → `tip_id = 1003`；不回填；摘要字段与名字顺序；`limit` 收口。
对照 `sp_test.go:206`、`:582`、`:782`、`:854`。第三轮评审补：批读落点（落地是 S_W_RECORDS）整批失败 → 回变短的列表（通常为空）、不回 1003、计 `anomalies{record_read_failed}`；
落点在但 `pb` 缺失或解析失败 → 跳过、不剔除、计 `anomalies{corrupt_record}`（§3.3）；剔除异步发出（替身让 S_W_EVICT 挂起，应答照常返回）；空列表的应答体是 0 字节且照常回包。

**`SpectateGatherHooksTest`**：有标记有落点 → 发往**落点地址**的 `remove(reason = enter_gather)` → 按值删标记；无落点 → 只删；值非法 → 删；读落点出错 → 不发 RPC、删；读全员标记（落地是 S_W_MARKS）出错 → 全部跳过、标记保留；
RPC 失败 / 超时开局照常；每人至多 3 s；`onStarted` 只在 attempt 一致时 ZADD，失败只计数。对照 `sp_test.go:448`、`:481`、`:824`，`gsi_test.go:244`、`:463`。

**`GatherHooksCallSiteTest`**（落地：新类，真钩子接在 `GatherFixture` 的管线上；原计划是往 6.4 的 `GatherPipelineTest` 里追加，那个文件没有改）：五个入口都在第一次 prepare 之前调 `beforePrepare`；失败路径不调 `onStarted`；落点在建房期间被剔除后，补写 + 公开能把它恢复（`gsi_test.go:440`）；gather 失败后观众仍被清退（`sp_test.go:867`）。

**`SpectateSweeperTest`**：只摘过期成员（`sp_test.go:192`）；某一轮抛异常，下一轮照常；gauge 采样。

**`DefaultObserverDialerTest` / `DirectPlacementDialerTest`**（落地的类名；后者另钉带硬截止的重载，`RedisBattleNodesTest` 钉带截止的 `lookup`）：四种结局的判据（建连失败 + 换实例 / 同实例 / 目录缺席 / 目录读失败；超时；连上后断开；6.2 在途超限异常）；179 与观战共用后 `BattleTicketReissueTest` 不回归。

**`CrossZoneGatherTest`**（钉住 Z1–Z3、Z11、Z12）：
- A 在 zone 1、B 在 zone 2 的位置记录，`xm:nodes:scene:1` 与 `:2` **都有 1 号节点**、实例与地址不同 → prepare 分别打到两个端点；Cancel 发回 Prepare 时的端点；
- CreateBattle 的两个快照 `routing.zone_id` 是 1 和 2；`gather_zone_mix_total{mix="cross"}` 加 1；
- B 排队之后旅行到 zone 3（位置 `o`@z3）→ prepare 打到 zone 3；gather 时位置不是 `o`（`l` / `x` / 缺失）→ `no_location`、B 作为肇事者出局（`m-spec` §9.6 第 3 步）。
  「位置 `l` → 本轮跳过、保留排队」是 matcher 凑单校验的规则（M12，`m-spec:1028`），归 6.4 的 matcher 单测，不在这个 gather 用例里；
- 观众在 zone 2、参战者在 zone 1：163 成功，观众路由的 zone = 2、gate 节点号 1。

**同号碰撞（其它模块，钉住 Z5–Z7）**：`PlayerPushesTest`（xm-discovery，本批新建）/ `PresenceLobbyAnnouncerTest`：presence (zone 2, gate 1) → 发布到 `xm:gate-push:2:1` 而不是 `:1:1`；
`DubboSceneBattleEventsTest`、`SettlementOutboxTest`（6.3）：路由 (zone 2, 节点 1) 不命中 zone 1 的 1 号节点，实例不符回落定位器；`BattleTicketsTest`（xm-common）：载荷无 zone 字段，跨实例拒绝。
落地另补：xm-battle `ObserverTest`（观众换到另一个 zone 的同号 gate：会话号、gate 节点号都不变，只有实例与 zone 变 → 按会话变了处理）、`SceneClientSweeperTest`（按节点自己的 zone 读目录）；
xm-discovery `SceneAssetLocatorTest`；xm-common `GateTokensTest`（zone 1 的 1 号 gate 的令牌拿到 zone 2 的 1 号 gate → `WRONG_ZONE`）。

**`QueueServiceTest`（6.4，追加，Z2）**：`SessionContext.zone_id = 1`、位置记录 zone = 2 → 票据 `zone_id = 2`；位置 `l` / `x` / 缺失 → 16020。对照 `cz_test.go:108`、`:121`。

**`MatchClientMessageServiceTest`（6.4，扩充）**：163 走虚拟线程、164 走 `match-worker`；过载应答（163 in-band 16004，164 信封 1003）；M22 的临时应答已移除。
落地：这个类里 163 / 164 用的是派发层替身（钉过载口径）；真处理器经真 `MatchDispatcher` 的用例在 `WatchBattleHandlerTest` / `ListWatchableHandlerTest`，执行器钩子在 `MatchDispatcherTest`，
「真装配 + 真 Triple」这一跳在 `MatchSkeletonContextTest`（评审 T-01 补），装起来的是不是真实现由 `MatchApplicationContextTest` 钉。

**生命周期与配置（第三轮评审补）**：停机时撤 Dubbo 导出之后有界等待在途 163（替身让一次 163 挂在 AddObserver 上，停机不早于它的预算到点、也不无限等）、随后停清扫器；
`xm.match.spectate.sweep-interval ≤ 0`、`max-inflight < 1` → 拒绝启动（§4.11 配置校验，`MatchPropertiesTest`）。

### 10.4 真 Redis（`-Dxm.it.redis`，缺省跳过）

- 用例类带 `@EnabledIfSystemProperty(named = "xm.it.redis", …)`，用 DB 13、开头清自己的键（同其它模块：CI 里各模块串行、共用 DB 13，`ci-spec` §4.4 第 5 步）；它会进 `TestReport --require-it-executed` 的计数（§4.4 第 6 步），整类被跳过即判红。
- 每段脚本单独验证；**每段可变脚本执行两次，结果不变**（模拟 Redisson 重发）。
- S_W_ACQUIRE：有票 → queued；同值重放 → ok；别的值 → busy。S_W_RELEASE 只删自己的值。
- S_W_PICK：不返回过期成员；全部过期返回空；1 万次挑选分布大致均匀；`r` 取 0 与 0.999… 的边界。
- S_W_EVICT 四种模式；S_W_PUBLISH 的 attempt 条件；S_W_SWEEP 只摘成员不动落点。
- 观战键、票据、落点同 slot。落地：用 Redisson 的 CRC16 在本地算槽号相等——单机 Redis 对 `CLUSTER KEYSLOT` 回「cluster support disabled」。
- 落地（`RedissonSpectateStoreIntegrationTest` + 夹具 `SpectateRedisFixture`）：**契约用例不用生产的索引键**，每个夹具一把自己的 `xm:{match}:watchable:it:<随机>`（同槽）——索引是全局的一把键，共用 DB 13 的别的用例会公开战斗、
  同库里若有活着的清扫器会摘掉过期成员，「索引此刻恰好这几个成员」只在自己的键上成立。真 Redis 拨不动 `TIME`：「恰在分界上」用把脚本里唯一的 `redis.call('TIME')` 换成固定值的副本来钉，「重放不续期」用 `PEXPIRE` 把 TTL 改短后重放来钉。
  每段可变脚本原样重发一遍后用 `DUMP` 逐字节比对存储不变。另有 `SpectateFlowRedisIntegrationTest`（真的 163 / 164 / 钩子 / 清扫器接在真的观战、落点、票据存储上）。
- 移植 `TestSpectateRegisterLoadRemoveRoundTrip :127`、`TestPickRandomBattleNeverReturnsStaleOrGarbage :161`、`…EvictsAllBadMembersAndReportsNone :177`、`…GivesUpAfterThreeAttempts :839`。
- 同号碰撞：`ScenePreparer` 与 `SceneAssetLocator` 在真 Redis 上重复 §10.3 的用例；移植 `TestMatcherMixesZonesAndShortensMatchedTTL`（`cz_test.go:153`）。
  落地：`ScenePreparerRedisIntegrationTest`（xm-match）、`SceneAssetLocationIntegrationTest`（xm-discovery，多 1 例）、`GatePushCrossZoneIntegrationTest`（xm-discovery，真 Redis pub/sub：zone 2 玩家的消息 zone 1 的同号 gate 一条也收不到）；`cz_test.go:153` 6.4 已移植（`MatcherPortedScenarios`）。

### 10.5 Dubbo

xm-match → xm-battle 的一条真 Triple 回环：带 MAC 调 `addObserver` / `removeObserver`；缺 MAC 被拒；`PlacementDialer` 区分建连失败与超时（对一个没人监听的端口、一个故意挂起的提供方各测一次）。
落地：`ObserverRpcLoopbackTest`（缺省执行）——缺 MAC 被拒 → `Unknown`（业务代码没执行）；没人监听的端口 → `NotDelivered`（配给定的探测结论时 → `Dead`）；挂起的提供方 → `Unknown`，硬截止早于超时时到硬截止就返回。
Dubbo 模型上的首次调用在 `@BeforeAll` 的平台线程上预热（§4.9 的钉住点）。

### 10.6 本机切片

- 单 zone：`start-slice.sh`（6.2 起 xm-battle、6.4 起 xm-match，6.3 的 scene）→ `battle-smoke`。13 个进程，缺省 `XM_ZONES=1` 的命令行与以前相同。
- 双 zone：`XM_ZONES=2 tools/local/start-slice.sh` → `java -jar xm-robot/target/xm-robot-*.jar battle-cross-zone --zone 1 --visit-zone 2`。
- 回归：`XM_ZONES=1` 下现有场景与 6.4 `battle-smoke` 不受影响。

**落地的 `XM_ZONES`**（lead 裁决 1、5；`tools/local/start-slice.sh` / `stop-slice.sh` / `battle-crash-window.sh`，文本由 xm-robot 的 `SliceScriptsTest` 与 xm-gate 的 `LocalSliceOrderTest` 钉住）：

| 项 | 落地 |
|---|---|
| 开关 | `XM_ZONES`（1 / 2，缺省 1；只有 `start-slice.sh` 读，不导出；其它值退出码 1、不起进程）。可与 `XM_SCENE_NODES=2` 同用 |
| 新增进程（=2） | `xm-scene-z2`：`--xm.zone-id=2`，链路 21010、资产通道 21110、管理端口 18115，排在区 1 的场景节点之后，同样等主世界频道（`xm_scene_channels{state="active"} ≥ 1`）；`xm-gate-z2`：`--xm.zone-id=2`，客户端 11010、管理端口 18123，排在 xm-gate 之后。其余进程共用（含 xm-match、xm-battle）。单 zone 13 个进程，双 zone 15 个（再加 `XM_SCENE_NODES=2` 是 16 个）。区 2 的 scene / gate 节点号按 zone 租约也是 1 号——与区 1 同号是有意的（§2.8） |
| 启动 / 停止次序 | 启动：… xm-data → xm-scene [→ xm-scene-2] → xm-scene-z2 → xm-gate → xm-gate-z2 → xm-gateway → xm-battle → 区 2 状态同步。停止是它的逆序：`stop-slice.sh` 在停 xm-gate 之前先停 xm-gate-z2、停 xm-scene-2 之前先停 xm-scene-z2；它不读 `XM_ZONES`，只看 PID 文件 |
| 区 2 在区服目录里的那一行 | **由脚本经 xm-data 的运维接口管，状态跟随 `XM_ZONES`**（`sync_zone2_status`；令牌 `XM_ADMIN_TOKEN`，操作人头 `X-Xm-Operator: start-slice`）：=2 → `POST /admin/zones/2/open`，回 404（库里还没有区 2）就 `POST /admin/zones` 建区（名字「二区」、`sort_order` 2）；=1 → `POST /admin/zones/2/maintenance`，回 404 就什么都不做。**xm-gateway 的命令行两种形态下都不变**——没有按 `zt-spec` §5.13 原设计在命令行上给 `seed-zones[0] / [1]`：本机 JDK 启动器按 ANSI 代码页取命令行，中文区名到进程时已是「??」，而播种只在库里没有这个区时写、写了不再改 |
| 「全部就绪」 | =2 时等 `GET /api/server-list` 里区 2 是 `OPEN` 且带 `load_level`（负载档只在 gateway 的健康探测看到该区有 gate 之后才下发，所以同时说明 xm-gate-z2 已进节点目录）；=1 且库里有区 2 时等它显示 `MAINTENANCE`；各 30 s，失败退出码 1（这时进程都已起来）。末行是「全部就绪（场景节点 N 个、区 M 个）。…」。缺省路径因此多一次 HTTP 调用，回的不是 200 / 404 时脚本以退出码 1 结束 |
| 故障变体 `battle-crash-window.sh` | 只在区 1 上做：robot 要登录的区（`--zone N` / `--zone=N` 或 `XM_ROBOT_ZONE`）不是 1 时退出码 2。区 2 的两个实例它不杀、不重启、不检查 |

区 2 的五个端口（21010 / 21110 / 18115 / 11010 / 18123）只由脚本经命令行参数传入；`SliceScriptsTest` 从脚本的 `ZONE2_*` 两行解析它们，并扫描每个模块的 `application*.yaml`——哪个模块的缺省端口与它们相撞，xm-robot 的测试就红（评审 XZ-1：这种冲突只在 `XM_ZONES=2` 时才暴露）。

没有落地的：`team` X1 / X2 与 `guild` 第 4 步的跨区步骤（`zt-spec` §5.13 说「顺带解锁」，归 5.4，照旧跳过）；双 zone 进 CI（Q22）。

### 10.7 robot：`battle-smoke` 观战段（替换 `m-spec` §15.5 第 10 步）

- **前置**：切片带 xm-battle、xm-match、6.3 的 scene；`XM_RUN_MODE=dev`；Kafka 就绪。
- **账号**：观战段用自己的四个新号 SA（参战）、SB（指定观战）、SC（列表 / 随机观战）、SD（第二局参战），run-tag 新建，与 6.4 的 A / B / C 互不牵连。观战段放在 6.4 各段**之前**跑。
  落地：账号是 `<prefix>bm<runTag>_w / _x / _y / _z`（SA / SB / SC / SD）；这四个号在观战段结束时就 LeaveGame 下线（SB 没来得及取消的排队票会补一条 148）。
- **节奏**：同一会话同号请求间隔 ≥ 350 ms（每号每秒 3 条）；等 177 与 161 各 15 s、等 166 120 s（同 `bss.go:54-60`）；过渡态每 1 s 重试、上限 20 s。
- **客户端件**：复用 6.2 的 `BattleDirectConnection`（按消息号计数、能检测 FIN）与大厅 177 handler（`bn-spec` §13.8）；新增 163 / 164 的请求与应答解析；开头抓一次 18113 作为指标基数。
- **执行顺序**：S0–S11 整段在 6.4 第 1 步**之前**跑；S12 插在 6.4 第 9 步里（它要用切磋局）；S13 在整个场景末尾（6.4 第 11 步之后）。6.4 第 10 步（M22 的临时应答）删除。
  落地的次序（`BattleSmokeScenario`；lead 裁决：两套编号不合并，观战段用 S0–S13，匹配段沿用 6.4 的第 1–12 步）：第 1 步前半（运维令牌 + 抓指标基数，`1-login`）→ 观战段 S0–S11 → 第 1 步后半（A / B / C 登录）→ 第 2–9 步
  （S12 的两半都在第 9 步里）→ 第 11 步 → S13。失败行里的步骤号一律**小写**（`StepTrack` 只认 `[a-z0-9-]`，同 6.4 的 `4-pve-solo`）：`s0-login`、`s0-list`、`s0-preclean`、`s1-queued-watch`、`s2-list`、`s3-watch`、`s4-rewatch`、`s5-reissue`、
  `s6-queue-watching`、`s7-ghost`、`s8-random`、`s9-finish`、`s10-ended`、`s11-evict`、`s12-ready-residue`、`s12-in-battle`、`s13-metrics`，以及 `s<n>-budget`、`s<n>-x-ended-early`。
- **预清理**（落地新增，`s0-preclean`，S0 之后、S1 之前；只记观察、不参与判定）：BW7 让前几遍 robot 留下的已结束战斗在索引里最长留 360 s，而每条 163(0) 至多懒剔除两场，S8 的 10 s 重试上限在连跑时不够。
  所以每轮发一条 164 `{limit = 50}` 加一条 163(0)，直到**列表空了**为止（上限 30 轮）——停止判据不是「163(0) 回了 16017」：随机观战连着扑空两场就回 16017，那时索引里多半还有残留。
  163(0) 成功（切片上有别人的活战斗）→ 等观众票、直连、165 退出并停止清理；列表不到 50 条且没有比上一轮变短、或回了别的码 → 停止。
- **战斗 X 的寿命（S1–S8 的时间预算）**：SA 不开自动时，X 每 6 s 按回合超时结算一次，未出手的一方执行默认普攻（`TurnBattleEngine` 的默认行动路径同时覆盖「未提交的玩家」「挂机玩家」与全部怪物；第一回合恒 6 s）——
  **开不开自动，出手内容都一样**，区别只在回合节奏（不开自动等满 6 s，开自动全员就绪即结算）。
  **落地（评审 R-3）：预算是 30 s，不是原稿的 60 s**。原稿拿基线「同一场 PVE 开自动要打 13–21 回合」（E3）外推出 X 能活 78 s 以上；Java 切片上新号单人 PVE 实测是 **7–10 回合**，不开自动的 X 只活 42–60 s，
  60 s 的预算永远排在「X 提前结束」之后、起不到提前报警的作用。现在：S1 收到 177 到 S8 结束**合计预算 30 s**（正常约 6 s；各步的等待上限照旧，第一次超出记一条 `step=s<n>-budget` 的失败、之后照常往下跑）；
  预算必须比「最少 7 回合 × 6 s」短一个回合以上（`SpectateSteps.SOLO_PVE_MIN_ROUNDS` / `ROUND_TIMEOUT_MS`，有用例钉住，回合时长对着 xm-battle 的常量）。
  S2–S9 每步开始前看 SA 的直连，一旦出现 150 或连接已关（X 提前结束）→ `step=s<n>-x-ended-early` 并中止观战段（匹配段照常跑），报告当时的回合数——明显少于 7 回合才是表数值变了，否则预算应当已先报。

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
| S11 | 开局清退：SD 发 157 PVE_SOLO（战斗 Z）→ 直连、不开自动；SB 发 163(Z) → 直连收到 161；**SB 发 157 PVE_SOLO** | SB 在 Z 的直连上 10 s 内收到 166 `{battle_id = Z, outcome = 0 ONGOING, reason = 3 REMOVED}`，然后 FIN；SB 大厅收到新战斗 W 的 177(role 1) / 143。**两条连接之间不断言先后**。之后 SD、SB 都开自动打完各自的局（否则 SB 带着锁）。落地（评审 R-4）：这两局（SD 的 Z、SB 的 W）的直连 150 到了之后，观战段下线之前**再等各自的大厅 150**（结算落到 scene 才推），两局共用截止「最后一条直连 150 + 25 s」；等不到只记观察、不判失败——否则参战者在结算应用之前离场，battle 的结算发件箱要对着离线玩家重投约两分钟才放弃。SA 的 X 不等（它在 S9 就结束了） | `gather.go:228-234`；`room.cpp:897-929` |
| S12 | 16015：在 6.4 第 9 步的切磋战斗里（切磋不建票），A、B 都开自动之前，A 发 163(0)。**注意 A 在 6.4 第 8 步打过 1V1**：那一局的 ready 票（60 s，BW1）在切磋开局时可能还没过期，而票据检查排在锁检查之前，此时回 16014。**落地（评审 R-2）分成两半**：① `s12-ready-residue`——**切磋开局之前**（第二次发起切磋之前）每 1 s 发一条 153，把 A 上一局 1V1 的 ready 票等掉，直到 NOT_QUEUED；过了「第 8 步收到 177 的时刻 + 62 s」仍是 READY，或是 QUEUED / MATCHED，记一条失败后继续；② `s12-in-battle`——切磋局里（A、B 开自动之前）A **只发一次** 163(0)，必须是 16015，再见 16014 直接失败。原稿是「在切磋局里遇 16014 每 1 s 重试到 177 + 62 s」，并论证「新号 1V1 最短 8 回合约 48 s，等得过来」——**这个论证不成立**：切磋双方带着上一局 1V1 的结算血量进场（败者复活回满、胜者残血），不开自动的切磋局可以只有 1 回合（6 s）；`step=s12-battle-ended` 已不存在 | ① 最终 NOT_QUEUED；② `{16015, 战斗尚未结束,无法观战}`。报告字段 `s12_ready_residue=0/1` 的含义随之改变：现在是「切磋开局之前的 153 见到过 READY」，不再是「163 见到过 16014」；robot 不再直接观察「ready 残留 → 16014」（BW1 由 §10.3 的组件用例钉），16014 仍由 S1（持票且有锁）与 S6（排队票）断言 | `wb.go:74-89`；`join.go:234-246` |
| S13 | 场景末尾再抓 18113，与开头的基数相减 | `xm_match_watch_battle_total{outcome="ok"}` 增量 ≥ 4（S3、S4、S8、S11）、`not_found` ≥ 2（S7、S10）、`queued` ≥ 2（S1、S6）、`in_battle` ≥ 1（S12）；`xm_match_spectate_evictions_total{reason="enter_gather",result="removed"}` 增量 ≥ 1 | — |

- **输出**：沿 6.4 的 `BATTLE_SMOKE_OK battle_id=… a_turns=… a_direct_turns=… pvp_battle_id=… challenge_battle_id=…`，追加 `spectate_battle_id=… b_spectate_turns=… b_direct_spectate_turns=… removed_ok=1`
  （`b_*` 两个字段名同 Go，`bss.go:277-278`）以及 `s12_ready_residue=0|1`；失败 `BATTLE_SMOKE_FAIL step=s<n>-… reason=…`（步骤号小写）。
  落地的完整结果行：`BATTLE_SMOKE_OK battle_id=… a_turns=… a_direct_turns=… pvp_battle_id=… challenge_battle_id=… spectate_battle_id=… b_spectate_turns=… b_direct_spectate_turns=… removed_ok=1 s12_ready_residue=0|1`
  （`removed_ok` 按 S11 的清退检查输出 0 / 1；OK 行只在全部检查通过时才写，所以 OK 行上它恒为 1）。
  观战段让 `battle-smoke` 的耗时多出约 2–4 分钟（X 靠回合超时活过 S1–S8、放行后挂机打完、S11 两局单人 PVE、S12 前半可能等 ready 残留至多几十秒）；外层脚本对单个场景设了时限的要放宽。
- **比表里多核对的几处**（纯判据在 `SpectateSteps`）：164 每条的 battle_id 非 0 且不重复；观众收尾时 166 恰好一条、之后紧跟 FIN，观众的直连上没有 139 / 150，每条 158 都是观众版；165 的应答之前允许夹着 158、之后必须紧跟 FIN。
  S2 核对 `player_names` 用的「SA 的角色名」取自 SA 自己那条 143 里本人 actor 的 name（与落点的 `player_names` 同源：备战快照的 `player_name`）。S6 的 1V1 配置号 = 900000 + run-tag（按 36 进制读）的低 16 位。
- **`m-spec` §15.5 第 7 步的订正**（2026-10-08 首次带观战段的切片上暴露，不是两版差异）：同一个号的第二局 PVE 不再断言 SIDE_A_WIN，只要求「打完」——见实现记录「robot 场景与切片脚本的现状」。
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
| Z2 | 抓一次 18113 | 记下 `xm_match_gather_zone_mix_total{mix="cross"}`（**对 `mode` 标签求和**：`m-spec` §11 没有钉死 mode 标签的取值写法，robot 不与它耦合）与 `xm_match_watch_battle_total{outcome="ok"}` 的基数 | — |
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

**落地（`BattleCrossZoneScenario` / `BattleCrossZoneChecks`；与上表的出入）**：

- **选项**：`--visit-zone` / `XM_ROBOT_VISIT_ZONE`（缺省 2，必须 ≥ 1）是 6.5 新增的（5.4 没有合入）；只有 `battle-cross-zone` 要求它与 `--zone` 不同（否则参数错误、退出码 2），别的子命令不看它（`smoke --zone 2` 是正常用法）。
- **步骤号小写**：`preflight`、`z8-admin-token`、`z1-login`、`z2-baseline`、`z3-queue`、`z4-announce`、`z5-direct-watch`、`z6-fight`、`z7-lobby-end`、`z8-rating`、`z9-second-{queue, announce, direct, fight, lobby-end, rating, battle}`、`z10-metrics`、`z11-leave`。
- **Z0 / 运维令牌**：令牌在 Z0 之后、登录之前就检查（赛前评分基数也要读），缺令牌记 `step=z8-admin-token` 失败，不白打一局、不静默跳过。
- **Z1**：三个号**顺序**登录（上表写「并发」，那是动作不是断言；顺序更保守）。「两个区的 gate 端点不同」用的是场景自己另发的一次 assign-gate（两个客户端各一次）——`PlayerFlow` 不暴露实际连接的端点；同一个区只有自己的 gate，与「实际连接的端点不同」等价，
  真正的跨区证据还有 Z10 的 `mix="cross"`。**「角色列表 zone_id = 1 / 2」的断言没有实现**（等 5.4 的 X16），场景只记一条观察。
- **Z3**：157 的 `zone_id` 如实填本侧登录的区（服务端不读）；`battle_config_id = 1`。
- **Z6 多核对**：两侧 150 的 battle_id 都是本局、`settlement.total_rounds` 相同。
- **Z7**：等大厅 150 的期限 = 直连 150 到达时刻 + 25 s。
- **Z9**：第二局各步的步骤号是 `z9-second-*`；排队对 16000 每 1 s 重试、上限 20 s，出现 16001 即失败并给排查提示。
- **收尾**（成功与失败路径）：给 A、B 各发一条 148("")（免得把排队票留在全服共享的 1 号配置队列里凑走下一遍的 A），三人 LeaveGame 后断开。
- **结果行**：`CROSS_ZONE_MATCH_OK battle_id=… zone_a=… zone_b=… a_turns=… b_turns=… a_direct_turns=… b_direct_turns=… observer_zone=… c_spectate_turns=… second_battle_id=…`（前七个字段与基线同名同序）；失败 `CROSS_ZONE_MATCH_FAIL step=… reason=…`。
- **使用上的注意**：1V1 的 1 号配置队列全服共享，切片上同时有 Unity 客户端 / 别的 robot 在排它时 A 或 B 会被凑走，场景在 Z4 失败；Z8 的 `|Δ| = 16` 只对新号（1500）成立。
- 场景的单测跑在本机假服务端 `FakeMatchWorld`（补了观战语义与第二个区）上，它只钉 robot 这一侧的编排，不是服务端行为的证明；服务端行为的证据是双 zone 切片上的运行（实现记录、最终验证）。

### 10.9 跨版本验收（可选、不阻塞；不改 Go robot 代码）

- 本机没有 Go，只在 GitHub Actions 上做可选 job（7.1b 之后，Q23）：mmorpg 的检出要包含 `robot/`（含 `robot/config/`、`robot/generated/`，本机稀疏克隆里没有）。
- **Go `battle-smoke`**（单 zone、**全新**的整栈）：复制 `robot/etc/battle_smoke.yaml`，`gateway_addr: http://127.0.0.1:18081`；口令 = `XM_LOGIN_DEV_PASSWORD`（或把整栈口令设成配置里的 `123456`，同 `zt-spec` §11.10）；
  `robot_` 前缀在 Java 开发白名单里。期望 `BATTLE_SMOKE_OK`。B 的随机观战断言「挑中的就是 A 那一场」（`bss.go:181-184`），所以整栈上不能有别的战斗或已结束的残留场——全新整栈天然满足。
  B 收到 A 的 143 就发 163(0)、不重试（`bss.go:166-187`），而 143 在 createBattle 期间推出、公开在 gather 第 5 步之后：两版都有这个毫秒级竞态（基线同样是 `gather.go:381-389` 在建房回包之后才 ZADD），偶发 16017 → B 等 177 超时属于基线 robot 的已知脆弱点，重跑一次再判。
- **Go `battle_smoke_cross_zone`**（两 zone 覆盖文件，Q22）：复制 `bsc.yaml` 改 `gateway_addr`，期望 `CROSS_ZONE_MATCH_OK`。固定账号 robot_9003 / 9004 在全新整栈上没有评分漂移与位置残留；
  本机复跑时要等位置记录过期（在线 60 s / 租约 30 s），否则会被 GO-5 送走（`zt-spec` §11.12）。
- 失败时先按 §2.6 分清是 Java 还是基线 robot（基线收缩后没有绿色记录）的问题。
- **落地（2026-10-08）：两个 Go robot 场景都没有跑**。原因：本机没有 Go 工具链；可选的 GitHub Actions job 要等 7.1b 的整栈 compose（`deploy/compose/` 里现在只有 `infra.yaml`）。6.5 的验收证据是 Java robot 的 OK 行。

### 10.10 CI

- 单测与 `-Dxm.it.redis` 用例进现有 `ci.yml` / `integration.yml`（`ci-spec` §4）。落地：本批新增四个 `xm.it.redis` 门控的集成测试类（xm-match 的 `RedissonSpectateStoreIntegrationTest`、`SpectateFlowRedisIntegrationTest`、
  `ScenePreparerRedisIntegrationTest`，xm-discovery 的 `GatePushCrossZoneIntegrationTest`），都会进 `TestReport --require-it-executed` 的计数。写本稿时观战脚本只有本机（Redis 8.10.2 单机）的运行记录；CI 的 Integration 作业用 `deploy/compose/infra.yaml` 起同一个版本的 Redis（`redis:8.10.2`），以它自己的结果为准。
- 整栈：`battle-smoke` 随 6.4 的批次进第二期之后；`battle-cross-zone` 在两 zone 覆盖文件下、第三期之后加入（Q22）。两者都以 `ci-spec` Q9（battle 通告地址拆分）为前提：6.2 工作区已提供 `xm.battle.client-advertise-host`，compose 里配上即可（Q22）。

### 10.11 交付清单与登记

> **落地（2026-10-08）**：本节是开工前的清单。下面各条的文档登记都已做（「证据」一条的最终运行结论另见实现记录末尾的「最终验证」）；出入只有差异编号的范围——随落地变成 W1–W20、X1–X8、BW1–BW10（下面写的 W1–W15、X1–X7 是开工前的范围）。实际回写的文档：
> 本稿（正文与实现记录）；`m-spec`（§0.2 / §0.3、§8.1、§8.2、§8.6、§9.1、§9.3、§9.4、§9.6、§9.8、§9.9、§10.1、§11、§13 M22、§14 Q8、§15.5、实现记录里「给 6.5 与 4.6 的接口与义务」）；`zt-spec`（§5.13、§11.9、§11.11）；
> `bn-spec`（§7.10、§7.12）；`ci-spec`（§2.1 端口表）；三份盘点；`PARITY.md`（新增「观战」「跨区 1V1 匹配」两行，更新「匹配」「battle 节点」「战斗票据补签 179」「本地编排」四行）；
> `arch`（§2、§4.24、新小节 §4.24.1、§5、§6、§6.1、§7、§10、§11）；`roadmap.md`（6.5 行与 5.4 行，6.2 / 6.4 行里「留给 6.5」各补一句已落地）；仓库根 `README.md` 与 `xm-robot/README.md`。`tech-stack.md` 没有改（无新依赖）。`tools/local/*.sh` 的文件头与 `deploy/compose/env.example` 的说明随 L1 的代码提交已经写好。

- **`PARITY.md` 新增行**：
  - 「观战（match 侧：163 / 164、观战标记、可观战索引、开局清退）」：Java 模块 xm-match、xm-discovery；状态「已对齐」；附 W1–W15、BW1–BW10；mmorpg 侧状态「已有」；
    「mmorpg 待做（可选）」：W2–W5（W5 与 M16 一并）、E1–E3 注释勘误；两版同改候选 C1、C2（改 mmorpg 需用户同意）。
  - 「跨区 1V1 匹配」：状态「已对齐」；附 Z1–Z12、X1–X7、robot 场景名与 OK 行；注明 xm-match 不分 zone 部署；「mmorpg 待做（可选）」：基线 robot 补强（§2.5 的弱点）、收缩之后的跨区冒烟复验。
- **`PARITY.md` 更新行**：6.4 的匹配行（M22 关闭）；6.2 的 battle 行（「观战 match 侧 6.5」改为已接入）。
- **`roadmap.md:85`**：6.5 打勾、写提交号；注明 battle-spectate 的房间侧已在 6.2（Q1）。
- **盘点 java 列**：`inventory/combat.md:264`、`scene-manager-match.md:269`、`contract-robot.md:416`、`:428` 改为 done（模块、场景名、提交号）；勘误 E3–E6 同批改。
  `contract-robot.md:292`（svc-match-service-spectate-ticket，一行同时覆盖 163 / 164 / 179）也要改：179 段随 6.4 记 done，163 / 164 段随 6.5 记 done，两批各写各的提交号（第三轮评审补，原清单漏了这一行）。
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

### A.3 评审修订记录（第三轮完整性评审，2026-10-05，只改本文件）

通读全文后回到基线（`D:\work\mmorpg` @ `26ceb70ca`）、客户端（`D:\work\mmorpg-client` @ `a8577c7`，`Assets/Scripts/Net` 也已检出）、Java HEAD `9fde7d8` 加工作区与相关规格逐条核对。
本轮抽查的引用（都对得上，不再逐条列出）：`wb.go` 第 1–18 行判定的全部行号区间；`sp.go:32-36`、`:51-83`、`:106-191`、`:230-274`、`:276-315`、`:349-408`；`lw.go:15-18`、`:34-81`；
`gather.go:102-158`、`:212-234`、`:239-264`、`:281-350`、`:379-393`、`:649-661`；`queue.go:188-208`、`:332-380`（重算 1 / 2 / 5 / 10 人 = 42 / 48 / 66 / 96 s）；`join.go:18-21`、`:143-167`、`:234-246`；
`pc.go:87-92`、`:112-121`；`errors.go:65-84`；`metrics.go:86-90`；`yaml:3`、`:49`、`:57`、`:72`、`:138`；`rcfg.go:29`；`fwd.go:170-184`；`ms.proto:36-37`、`:152-188`（Java 副本 `:39-41`、`:155-167`）；
`bn.proto:47-68`（Java 副本 `:57`）；`pb.proto:66-83`、`:131-171`；`bd.proto:12-19`、`:172-177`；`room.cpp:62-64`、`:778-790`、`:862-884`、`:1406-1432`、`:211-245`、`:1794-1846`；
`bss.go:17-21`、`:47-77`、`:160-283`、`:304-351`；`bsc.go:16-20`、`:43-72`、`:110-311`；`bsc.yaml:1-36`；`msr.go:22-26`、`:49-67`；`bdc.go:51`；`robot/main.go:513`；`http_assign_gate.go:14-20`；
`sp_test.go` 全部 37 个用例行号、`wbcw_test.go` 5 个、`gsi_test.go`、`cz_test.go`；`tbs.md:270-275`、`:303`、`:305-323`、`:338`；`czm.md:90-103`、`:137`、`:278-284`、`:345`；
`PROGRESS.md:3474`、`:3486`、`:3556`、`:4191-4192`、`:5631`、`:6090`、`:6199`；基线 `generated/tables/{dungeon,monster}.json`（E3 的 370 / 360 与 time_limit 1800）；`messagelimiter.json`（68 行，没有 163 / 164）；
客户端 `SC.cs:25-60`、`:110-215`、`:290-370`，`SP.cs:15-30`、`:85-187`，`BC.cs:685-695`，`BattleDirectLink.cs:220-260`，`DAP.cs:37-50`、`:540-547`，`BattleUiStyle.cs:28`；
Java `BRS.java:300-426`、`:704-800`、`RoomConstants.java:33`、`BattleNodeServiceImpl.java:146-158`、`:271-300`、`BattleNodeService.java:32-57`、`BattleTicketIssuer.java:50-79`、`BattleTickets.java:45-46`（hex ASCII 签名）、
`DevRoutingResolver.java:210-227`、`PlayerPresenceDirectory.java:145`、`presence.proto`、`RedisKeys.java:14-55`、`:351`、`NodeTypes.java:8-36`、`PlayerPushes.java:125-133`、`client_call.proto:10-40`、
`match_error_tip.proto:20`、`:40-52`、`tip_text.json:70`、`:80-85`、`message_id.txt`、`xm-battle application.yaml:43-69`、`BattleIdentity.java:13`、`TurnBattleEngine.java:59-60`、`:473-490`、`:520-530`；
`m-spec` :73-74、:225-240、:393、:455-510、:837-841、:960-970、:1028、:1035-1062、:1112-1122、:1142、:1224、:1451-1498，`bn-spec` :470、:683-707、:1174、:1500-1503，`sb-spec` :333、:918、:934-935，
`zt-spec` :427、:819-842、:1166、:1201、:1212，`ci-spec` :145、:726-727、:736-760、:992、:1037，`zone-merge-spec` :562，`ops-release-spec` :794、:1231。

**更正与补遗**

| # | 位置 | 问题 | 修订 |
|---|---|---|---|
| T1 | 文首 | Java HEAD 写成 `aa8b5b5`；5.2（`6b28e9d`）、7.1a / 7.2a（`37dd8dc`）与本稿（`9fde7d8`）都已提交；客户端检出目录漏了 `Net` | 改为 HEAD `9fde7d8`，补 `Net` |
| T2 | §0.4 #6、§7.1 第 4 条、W10 | 「自我清退的 Remove 一律异步」的理由写成「持票 / 持锁的玩家此后发不出能与之竞争的 163」——不成立：并发 gather 秒败（DELETE_ALL 删票、锁没写或已被 Cancel 删）后玩家可立刻再发 163(同一场)，新登记可能被迟到的 Remove 摘掉（基线是同步 Remove，`wb.go:223`，没有这个窗口） | 写明这条 Java 独有的残余竞态、为什么几乎不可达（Remove 先于 16014 发出，新请求要多走一次客户端往返）、为什么仍不改同步（复查时只剩约 0.2 s 预算）；归入 W10 的客户端可见面 |
| T3 | §1.5、§7.1 新增第 16 条、§10.3 | 「复查关掉 TOCTOU 窗口」说过头了：切磋没有票据，gather 在本次写标记之前读标记、Prepare 在复查之后写锁时复查两样都落空，玩家同时是观众与参战者（两版相同） | 改为「收窄而不是关死」，写明后果（幽灵观众 + 客户端 Superseded 收场）与不加二次复查的理由；单测钉住「复查时锁尚不存在 → 成功」这一既定结局 |
| T4 | §1.8 | 漏了基线屏障的 30 s 兜底（`bss.go:345-351`）：B 不到位 A 也会开自动。影响 §10.9 跨版本验收的时序判断 | 补上 |
| T5 | §3.3、§10.3 | 基线「记录在但 summary 为空 → DEL + ZREM」（`lw.go:74-77`）在 Java 的对应物没定义；空列表 0 字节是否回包没写 | 落点在但 `pb` 缺失 / 解析失败一律按损坏：跳过、不剔除、计 anomalies（DEL 会毁掉 179 的定位；只在数据损坏时可见）；空列表照常回 0 字节应答体（`client_call.proto:33-36`），客户端靠它收回「刷新中」 |
| T6 | §3.4 | `DirectRoutingBattleTransport.cs` 不在 `Assets/Scripts/Net/` | 改为 `Assets/Scripts/Game/Battle/DirectRoutingBattleTransport.cs:17-19` |
| T7 | §4.9 | 在途许可的取得 / 归还没写；`arch` 行号注记按旧 HEAD | 写明 Dubbo 线程 `tryAcquire`、虚拟线程 `finally` 归还（四条出口），异步 Remove / 异步剔除不占许可及其上界；`arch` 行号改为 HEAD `9fde7d8` 与工作区同为 `:666-668` |
| T8 | §6 | `xm_match_watchable_battles` 是每个实例采样同一个全局 ZSET，多副本（`ops-release-spec` Q22 生产 match 2 副本）下求和会翻倍 | 写明看板取 `max`；`xm_match_spectate_inflight` 才求和 |
| T9 | Q22、§0.3、§4.10、§10.9、§10.10 | 双 zone 进 CI 的前置不全：① gateway 必须同时播种两个区（`zt-spec:829`：`seed-zones` [0]、[1] 一起给，Spring 列表跨来源不合并），共用的 gateway 不能按 profile 换参数；② 单 zone stack 里常驻一个 OPEN 的 2 区会让按区选 gate 的场景失败；③ 模块清单漂移守卫（`ci-spec` §4.5 第 4 步）；④ `zt-spec` §11.9 把多 zone 用例定为「不进 CI」，与本稿 Q22 说法相反；另 `application.yaml` 行号偏了（`:47`、`:61-65` → `:47-48`、`:63-65`） | 推荐改为覆盖文件 `deploy/compose/two-zones.yaml`（`-f stack.yaml -f two-zones.yaml`），列出前置③④⑤；写明只把 `battle-cross-zone`（及 5.4 同意时的 `travel`）放进 CI、故障注入仍手工，7.1b 采纳时在 `ci-spec` 与 `zt-spec` 各登记一句；全文 `--profile two-zones` 的说法同步改掉 |
| T10 | §10.3 `CrossZoneGatherTest` | 「位置是 `l` → 本轮跳过（M12）」把 matcher 凑单校验（`m-spec:1028`）写进了 gather 用例；gather 时位置不是 `o` 是 `no_location`、该玩家作为肇事者出局（`m-spec` §9.6 第 3 步） | 拆开：gather 用例断言 `no_location`；`l` 跳过归 6.4 matcher 单测 |
| T11 | §10.3 | 用例缺口：`Dead` 在建房窗口内不剔除；S_W_ACQUIRE 回 `queued` 时按本次值释放；J2（未预期异常 → 信封 1003 + 释放标记）；在途许可四条出口都归还；`Unknown` 后重看同一场；164 的 `RBatch` 整批失败、`pb` 缺失、剔除异步、空列表回包；停机有界等待在途 163；配置校验 | 逐条补进 `WatchBattleServiceTest` / `WatchableListServiceTest`，新增「生命周期与配置」一段 |
| T12 | §10.7 战斗 X 的寿命 | 「不开自动能活 13 × 6 s」是拿开自动的基线回合数外推，依据没写 | 补依据：默认行动路径同时覆盖未提交玩家与挂机玩家（`TurnBattleEngine.java:520-530`），开不开自动出手内容相同，只差回合节奏，所以回合数分布可直接沿用 |
| T13 | §10.8 Z2 | 断言 `mode="MATCH_MODE_1V1"` 与 6.4 指标标签的取值写法耦合，`m-spec` §11 并没有钉死它 | 改为按 `mix="cross"` 对 `mode` 求和 |
| T14 | §10.11 | 漏了 `inventory/contract-robot.md:292`（svc-match-service-spectate-ticket 一行同时覆盖 163 / 164 / 179，java 列仍是 missing） | 补上：179 段随 6.4、163 / 164 段随 6.5 各记提交号 |
| T15 | §0.3 | 没列 7.3 合服 / 7.6 发布的边界 | 并入 7.2 那一行（不加行，理由见表下「行号稳定性」一段）：合服对 `xm:{match}:*` 不动（`zone-merge-spec.md:562`）；交给 7.6 的三件事（通告地址跨 zone 可达、清扫器多副本幂等、gauge 取 max） |
| T16 | §8.1、§3.8、§10.11 | T5 的「`pb` 缺失按损坏、不剔除」是一处有意差异，按 `AGENTS.md` 的要求必须登记 | 新增 W15（只在数据损坏时客户端可见，不建议两版同改）；§3.8 与 PARITY 附表范围改为 W1–W15 |

**行号稳定性与其它规格对本稿的引用**：`ops-release-spec.md:794` 引 `spectate-spec.md:347`（§2.8 第 4 条「battle 通告地址对所有 zone 可达」）。本轮在第 347 行之前的修改都是行内替换，
**没有增删行**，这条引用仍然有效。`ops-release-spec.md:1231` 引的 `spectate-spec.md:776` 本来就不对（在被引用时的版本里是 §9 标题前的空行），所指内容是 Q21（本轮修订后在 `:817`，行号还会随修订漂移），建议它改为按条目号「`spectate-spec` Q21」引用；
同处 `:794` 说「`xm-battle application.yaml:47-48` 现在一址两用」已过时——6.2 工作区已按 `ci-spec` Q9 加了 `xm.battle.client-advertise-host`（`:63-65`）。两处都不在本文件，只在此登记，留给 ops-release-spec 的下一轮修订。

**核对过、维持原结论的点**：§3.1 的判定顺序、码与 9 条 `parameters[0]`；BW1–BW10 照搬与 C1 / C2 两版同改候选；W1–W14 的客户端可见性标注（W10 只补了一条竞态；新增 W15，见 T16）；
S_W_* 九段脚本都只访问 `{match}` slot 的键、可变脚本可安全重发；Redis 7.2（`ci-spec:145`）下脚本内先 `TIME` 后写按效果复制，没有确定性问题
（2026-10-08 订正：`ci-spec:145` 那一格的 7.2 是基线 K8s 清单的版本，Java 的本机与 CI 用的都是 `deploy/compose/infra.yaml` 钉的 Redis 8.10.2；「Redis ≥ 7 按效果复制」的结论不变）；
跨区 1V1 不需要新的生产代码路径；没有新增第三方依赖（`tech-stack.md` 不改）；线程所有权符合 `AGENTS.md` §3。

---

## 实现记录（2026-10-08）

范围：163 WatchBattle / 164 ListWatchableBattles 的真实现、观战标记与可观战索引（11 段 Lua）、观众 RPC 的直拨、开局前清退与开局后公开、清扫与停机（都在 xm-match 的 `spectate` 包）；跨区 1V1 的测试钉与寻址审计（只动测试）；
双 zone 本机切片 `XM_ZONES=2`（tools/local）；robot `battle-smoke` 的观战段与新场景 `battle-cross-zone`。没有改 proto、没有新增第三方依赖、没有为跨区改任何生产代码。

过程：开工前 lead 对规格里六处没写清的地方做了裁决（双 zone 切片由 6.5 落地、163 的执行器钩子、带硬截止的直拨、停机并行、区 2 状态跟随 `XM_ZONES`、双 zone 联调暴露别的批次缺陷时谁修）→ 先行件（共享文件一次改完、冻结 `spectate` 包的接口与替身；提交 `517c99d`）→
六个工作包各在自己的 worktree 里并行实现（W1 存储与脚本 `2213274`、W2 163 与观众 RPC `78117d6`、W3 164 / 钩子 / 清扫 `377aa8a`、X1 跨区测试钉 `37d8998`、L1 切片脚本 `3cc04de`、R robot `1f24238`）→
集成（装配核对、真流程接真存储的集成测试、全量构建；改动分在 `ad96065` 与 `8f39377` 两个提交里——前者带着改了停机语义的 `SpectateSweeper` 与当时还没跟着改的 `SpectateSweeperTest`，单独看有一条必然失败的用例，后者才恢复全绿）→
PARITY 登记「进行中」（`976d7ff`）→ 切片验证中修 robot 的一条断言（`a2b4a0d`）→ 六路只读评审与逐条复核（17 条发现）→ 评审修正（`0100ab3`）→ 文档（其后的提交，git log「批次 6.5」）。
正文 §0–§10 已按本节的事实回写；本节只记正文里放不下的东西：类的落点、与原稿的出入、评审清单、证据与遗留。工作包代号 W1–W3 / X1 / L1 / R 是开工时的分工名，与 §8 的有意差异编号 W1–W20 / X1–X8 无关。

### 落地的类（按模块、按包）

**xm-api**（先行件）：`com.game.api.match.MatchBudgets` 新增 `ADD_OBSERVER_TIMEOUT_MS`、`WATCHING_TTL_SECONDS`、`SPECTATE_STALE_MS`、`WATCHABLE_LIST_DEFAULT / MAX`、`RANDOM_WATCH_ROUNDS`、`RANDOM_PICK_TRIES`、
`WATCH_REWATCH_RESERVE_MS`、`WATCH_ADD_RESERVE_MS`、`WATCH_ADD_MIN_BUDGET_MS`、`SPECTATE_DRAIN_TIMEOUT_MS`（§5.1）。`BattleNodeService.addObserver / removeObserver` 是 6.2 已有的，没有动。

**xm-discovery**（先行件）：`RedisKeys.matchWatching(playerId)` / `matchWatchable()`；新类 `com.game.discovery.battle.BattleRoutings`（`gatePart(PlayerPresence)`）。

**xm-battle**（先行件；业务代码不变）：`admin.DevRoutingResolver` 改为调 `BattleRoutings.gatePart`；`admin.DevBattleController` 的 `add-observer` 注释写明不写 match 的观战标记。

**xm-match**

| 工作包 | 提交 | 包 | 内容 |
|---|---|---|---|
| 先行件 | `517c99d` | `spectate`（接口）、`dispatch`、`lifecycle`、`placement`、`gather`、`support`、`metrics`、顶层、`testing` | 冻结的接口 `SpectateStore`、`ObserverDialer`，纯函数 `SpectateRules`；`MatchMethodHandler.executor()` 与 `MatchDispatcher` 按它投递；`InlineHandlers` 删掉 163 / 164 的临时处理器；`lifecycle.InflightWatches` / `SweeperControl` 与 `MatchLifecycle` 的新次序；`PlacementDialer.dial` / `BattleNodes.lookup` 带 `Deadline` 的重载（`DirectPlacementDialer`、`RedisBattleNodes`）；`placement.PlacementRecords`；`MatchTip` / `MatchTips` 的观战码与文案（删 `FEATURE_UNAVAILABLE` / `watchUnavailable`）；`MatchMetrics` 的八个指标与枚举；`MatchProperties.Spectate` 与 yaml 的两个键；`MatchConfiguration.gatherHooks()` 删除；三个装配类的占位；替身 `InMemorySpectateStore`、`FakeObserverDialer` 与抽象契约 `SpectateStoreContract` |
| W1 | `2213274` | `spectate` | `RedissonSpectateStore`、`SpectateScripts`（11 段 Lua）、`SpectateStoreConfiguration`；测试夹具 `SpectateRedisFixture` |
| W2 | `78117d6` | `spectate` | `WatchBattleService`、`WatchBattleHandler`、`DefaultObserverDialer`、`SpectateExecutor`、`WatchBattleConfiguration`（bean：`spectateExecutor`——同时是唯一的 `InflightWatches`、`observerDialer`、`watchBattleService`、`watchBattleHandler`） |
| W3 | `377aa8a` | `spectate` | `WatchableListService`、`ListWatchableHandler`、`SpectateGatherHooks`、`SpectateSweeper`、`WatchableConfiguration`（bean：`watchableListService`、`listWatchableBattlesHandler`、`gatherHooks`、`sweeperControl`） |
| 集成 | `ad96065`、`8f39377` | `spectate`、`lifecycle`、`gather` | `SpectateSweeper.stop()` 改成当场中断；`SpectateStore` / `GatherHooks` / `GatherConfiguration` / `SweeperControl` 的注释订正；新增 `SpectateFlowRedisIntegrationTest` |
| 评审修正 | `0100ab3` | `spectate`、`placement`、`lifecycle`、`metrics` | `WatchBattleService`（重看同一场的三个出口）、`RedissonSpectateStore.acquire`（迟到重发的补释放）、`WatchBattleConfiguration.budgetWarning`；其余是注释与测试（见「评审的发现与修正」） |

xm-match 进程里没有任何观战占位：163 的处理器非 inline、`executor()` 就是那个 `SpectateExecutor`；164 非 inline、`executor()` 为 null；当场回的只剩 156 / 154；十个号都有处理器（`MatchApplicationContextTest` 钉住）。
三个装配类都不带条件装配；在 `spectate` 包里再新增 `@Configuration` / `@Component` 类会让上下文测试里「装配清单 = 组件扫描结果」那条用例变红，真实现的 bean 放进已有的装配类。

**tools/local 与 deploy**（L1，`3cc04de`）：`start-slice.sh`（`XM_ZONES`、`ZONE2_ID` / `ZONE2_SCENE_NODE` / `ZONE2_GATE`、`start_zone2_scene` / `start_zone2_gate`、`sync_zone2_status`）、`stop-slice.sh`（区 2 的两个实例紧挨同类先停）、
`battle-crash-window.sh`（故障变体只在区 1 上做的前置）；`deploy/compose/env.example` 的一段说明（`XM_SCENE_NODES` / `XM_ZONES` 只有 `start-slice.sh` 读，compose 不读）。

**xm-robot**（R，`1f24238`；评审修正 `a2b4a0d`、`0100ab3`）：新 `scenario.SpectateSteps`（163 / 164 的请求与判据、观众票、观众直连、165、各步时限 `Timing`）、`scenario.BattleCrossZoneScenario` / `BattleCrossZoneChecks`；
`BattleSmokeScenario` / `BattleSmokeChecks` 的观战段与新编排；`RobotOptions`（`Scenario.BATTLE_CROSS_ZONE`、`--visit-zone` / `XM_ROBOT_VISIT_ZONE`）、`RobotMain` 的 `battle-cross-zone` 分支；`MatchSupport` 两行注释。

xm-common、xm-gate、xm-scene、xm-team、xm-login、xm-gateway 的主代码都没有改（X1 只加测试；xm-gate 的 `LocalSliceOrderTest` 没有改，它继续钉着 `SERVICES` 十三项与 `stop-slice.sh` 的清单行）。

### 与规格原稿的出入

行为上的出入都已回写进正文相应小节，这里汇总，便于对照原稿：

- **依赖 5.4 的两样由 6.5 自己落地**（裁决 1）：`XM_ZONES=2` 的切片脚本与 robot 的 `--visit-zone`。连带与 `zt-spec` §5.13 原设计的出入：不在 xm-gateway 的命令行上播种两个区，区 2 的那一行由脚本经 xm-data 的运维接口建 / 改，状态跟随 `XM_ZONES`（裁决 5；§10.6）；
  `stop-slice.sh` 的清单行没有加名字（xm-gate 的 `LocalSliceOrderTest` 逐项钉着），区 2 的实例在循环体里带上；第三个脚本 `battle-crash-window.sh` 原稿没提，也要同步。
- **163 的接入**（裁决 2）：`MatchMethodHandler.executor()`，不是另配一条派发路径（§4.9）。
- **预算**（裁决 3）：数值没变，靠带硬截止的直拨与目录读把原稿漏算的本地余量与额外读夹进去（§4.8、§5.3）。「不足 2.2 s 不换场」的判定挪到「读到旧场落点之后、发 Remove 之前」（§4.4 第 4 步）。
- **停机**（裁决 4）：等在途 163 与排空工作池并行；清扫 `stop()` 当场中断（集成阶段的裁决；W3 原来是「先等当前一轮至多 1 s 再中断」，那样最坏 21 s）。`SweeperControl` 是 `start()` + `stop()`（原计划只有 `stop`）：清扫器不自己启停，由 `MatchLifecycle` 在 Dubbo 导出之后统一起。
- **脚本**：11 段而不是「九段 + 两条普通命令」；KEYS 次序与返回值编码按 §4.3；全部读主库。
- **读口**：战斗锁与在线目录走 `port.PlayerStatusReader`，不是规格点名的 `BattleLockReader.exists` / `PlayerPresenceDirectory.findStrictAsync`（行为不变）。入口与抢占的「有没有票」在脚本里判，`TicketReader` 只用于复查。
  换场读旧场落点用 `PlacementStore.read`（179 的口径），开局清退读落点用 `SpectateStore.read`（观战口径）。
- **常量名**：保留 `REMOVE_OBSERVER_TIMEOUT_MS`、新增 `ADD_OBSERVER_TIMEOUT_MS`，没有 `OBSERVER_RPC_TIMEOUT_MS`。
- **接口细节**：`ObserverDialer.removeAsync` 返回 `CompletableFuture<Outcome>`（原计划是 void），调用方挂非阻塞回调记清退指标；落点 HASH 的字段名与损坏判据抽成 `PlacementRecords`，两处共用。
- **有意差异**：W16–W20、X8 是落地时新增的；W2、W5、W8、W9、W10、W15 的措辞更新过（§8）。
- **robot**：步骤号小写；S0 之后加预清理；屏障期预算 60 s → 30 s；S12 拆成两半、`s12_ready_residue` 的含义改变；观战段下线前等 S11 两局的大厅 150；`battle-cross-zone` 顺序登录、Z1 的归属区断言没有实现（§10.7、§10.8）。
- **钉住检查**：没有按原计划给测试 JVM 加 `-Djdk.tracePinnedThreads=short`（surefire 下收不到输出，会给出虚假的「没有钉住」），改用一条 JFR 事件的集成用例；lead 裁决不把它推广成通用测试基建。
- **测试类**：见 §10 开头的「落地」与下面的清单。

### 评审的发现与修正

集成提交之后做了六路只读评审（STORE 存储与脚本、WATCH 163 与观众 RPC、HOOKS 164 / 钩子 / 清扫 / 停机、XZONE 跨区、ROBOT、TESTS），每条发现再由另一人回到代码做反驳式复核。
共 **17 条**：high 1 条、medium 3 条、low 13 条；复核没有驳回的，全部处理（提交 `0100ab3`；R-1 的主代码修正在此前的 `a2b4a0d`）。「复核」一列：成立 / 未单独复核（直接交给修正阶段判断）。

| 编号 | 级别 | 复核 | 发现（修之前） | 处理 |
|---|---|---|---|---|
| S65-STORE-1 | medium | 成立 | 「重看同一场」先按值删旧标记、再抢新标记，两步不是原子的：抢标记回有票（W2）时既不调 AddObserver 也不发 Remove，玩家仍登记在那一场、却已没有标记，之后的开局清退（只认标记）摘不到他。Java 独有——基线在同一交错下靠复查把他摘掉。登记前预算不足的 J 行是同一类出口 | 修（lead 裁决取做法 A，只动 `WatchBattleService`）：三个出口各有交代（W17；§7.1 第 17 条）。第三个出口「入口删旧标记失败」是修正阶段报出、lead 裁决一并改掉的（`releasePrevious(…, retryAsync = false)`）。没有取做法 B（给 S_W_ACQUIRE 加「替换旧值」的参数）：它在「重发 + 两轮之间建票」与「旧标记只剩几秒 TTL」两种情形下洞还在，且要同时改脚本、契约测试与内存替身。前两个出口新增 4 条用例（变异核对过），第三个出口由「重看同一场时删旧标记失败」「换场时删旧标记失败」两条用例钉住 |
| S65-STORE-2 | low | 成立（机制按代码成立，没有实跑出 Redisson 的重发次序） | S_W_ACQUIRE 是可变脚本里唯一对「迟到的重发」没有守护的：163 等到截止后立刻发出的尽力释放，可能先于 Redisson 重发的那一遍到达 Redis，留下一个没人释放的标记 | 收窄（lead 裁决）：`RedissonSpectateStore.acquire` 在放弃等待而命令还在路上时，等它有了结局再按值释放一次；注释写明。不做按 nonce 的墓碑键，残余登记（§4.3） |
| R163-01 | low | 成立 | 带硬截止的直拨没有把「发调用」这一步夹进硬截止：`NodeClientCache` 的全局锁在清扫销毁空闲引用时被占着，这段等待不受硬截止约束 | 只改注释（`PlacementDialer` / `DirectPlacementDialer` 不再写「每一步都夹在硬截止之内」）。结构性修复（锁内摘表、锁外销毁）要动 xm-api 的 `NodeRpcClients`（xm-battle 也在用），登记为遗留，归 7.6 的连接治理 |
| R163-02 | low | 成立 | `xm.match.request-budget` 的合法区间是 [500 ms, 4500 ms]，而 163 的两道门槛是代码常量：调小之后换场 / 登记恒回 16004，没有任何启动期提示 | 只告警不拒启（lead 裁决）：`WatchBattleConfiguration.budgetWarning`，yaml 注释同步；不提高 6.4 冻结的 `MIN_REQUEST_BUDGET` |
| LC-01 | low | 成立 | 「停清扫当场中断」「等 163 与排空并行」本身安全，但注释里引用的依据不成立：`timeout-per-shutdown-phase` 截断不了同步的 `stop()`，真正的硬期限在进程外 | 代码不改，只改 `MatchLifecycle` / `SpectateSweeper` 的注释；文档口径按 §4.9 |
| XZ-1 | low | 未单独复核 | `SliceScriptsTest` 只拿区 2 的五个端口去比脚本里出现过的端口，各模块 `application*.yaml` 的缺省端口不在比较范围内——下一个新模块自然会取 18115，正好撞 xm-scene-z2 的管理端口，而且只在 `XM_ZONES=2` 时暴露 | 补测试：扫描每个模块的 `application*.yaml` 的端口缺省值，区 2 的五个端口改为从脚本的 `ZONE2_*` 两行解析；脚本没有改 |
| R-1 | **high** | 成立 | `battle-smoke` 第 7 步断言 A 的第二局 PVE 必须 SIDE_A_WIN；血量随第一局的结算带进第二局，第二局阵亡是合法结果，整条场景随机失败（6.4 留下的断言，挡住本批「连跑 3 遍」的验收） | 主代码在 `a2b4a0d` 修（见「robot 场景与切片脚本的现状」）；那次提交只跑了 `BattleSmokeChecksTest`，留下 `BattleSmokeScenarioTest` 一条红用例，`0100ab3` 改正并补两条场景级用例 |
| R-2 | low | 成立 | S12「在切磋局里等上一局 1V1 的 ready 票过期」的论证前提不成立：切磋双方带着上一局的结算血量进场，切磋局可以只有 1 回合；当时能过靠的是那局 1V1 恰好打了 29 回合 | 改编排：S12 拆成 `s12-ready-residue`（开局之前用 153 等掉）与 `s12-in-battle`（局里只发一次、必须 16015）；`FakeMatchWorld` 的 ready 残留改为按 153 的次数过期（§10.7） |
| R-3 | low | 未单独复核 | 屏障期的 60 s 预算没有判别力：Java 切片上新号单人 PVE 实测 7–10 回合，X 不开自动只活 42–60 s，比预算短 | 预算改 30 s；新增常量与一条钉「预算 + 一个回合 ≤ 7 × 6 s」的用例；提示文案改正 |
| R-4 | low | 成立 | 观战段收尾时四个号在直连 150 之后立刻 LeaveGame，S11 里后打完的局的参战者在结算应用之前离场：battle 的结算发件箱对离线玩家重投约 2 分钟后才放弃，每遍留下 1–2 条 WARN | 下线之前等 S11 两局各自的大厅 150（上限直连 150 + 25 s，等不到只记观察）；SA 的 X 不等 |
| T-01 | medium | 未单独复核 | 163 / 164 经「真装配 + 真 Triple」这一跳没有任何用例（先行件把它们从当场回的名单里拿掉后，观战包里没有一条用例走 Triple） | 补：`MatchSkeletonContextTest` 新增一条——依赖故障时 163 回 in-band 16004、会话没绑定玩家回「缺少玩家身份」、164 回信封 1003，并核对两个出口指标 |
| T-02 | medium | 成立 | `ObserverRpcLoopbackTest` 在真 Triple 回环上用 500 / 600 ms 的窄预算，并在直拨返回后立刻断言「对端已收到请求」，两者之间没有同步；`addObserver` 还是冷路径 | 修：预热 `addObserver`；两处预算放宽到 3 s / 2 s 并同步下界；「对端已收到」改成先带上限轮询再断言 |
| T-03 | low | 成立 | `RedisBattleNodesTest` 靠「总用时 ≤ 899 ms」区分「150 ms 截止」与「固定 1 s」，余量只有 749 ms | 修：不动生产代码，测试里用一个记下等待时长后直接抛超时的 future，不再真等、没有墙钟上界 |
| T-04 | low | 成立 | `SpectateExecutorTest` 断言另一条线程的未捕获处理器的副作用却没有等它 | 修：记下执行器起的每条线程，断言之前逐一 join |
| T-05 | low | 成立（部分：清单里三处不会翻面） | 一组「120–400 ms 的截止 + 对结局 / 文案 / 次数的精确断言」：创建截止到第一次过期检查之间卡过这个时长，结局就翻面 | 改了六处（两处改成不等待、四处截止放宽到 1 s）。**没改两处**（已登记的遗留，暴露窗口微秒级）：`DirectPlacementDialerTest` 的 150 ms 那条、`SpectateGatherHooksTest` 的 `SHORT_BUDGET_MS = 200` |
| T-06 | low | 未单独复核 | `WatchBattleHandlerTest`「截止由派发器给」那条用的是缺省预算，一个自建 4.5 s 截止的处理器同样通过 | 修：改用 20 s 的派发预算，断言 Add 硬截止的剩余落在只有它才可能的区间；变异核对过 |
| T-07 | low | 未单独复核 | 没有任何断言能区分复查的两次读是并行还是串行 | 补：`WatchBattleServiceTest` 新增一条，两次读各等「对方已发出」的闸；变异核对过 |

评审里顺带确认、没有列为发现的：11 段 Lua 的 KEYS / ARGV 次序、真值表、单位与时间来源与规格和各包登记的决定一致；内存替身与真脚本的判定逐条等价（差别只有已登记的三项：`a` 对得上而 `pb` 损坏的记录、不模拟落点 TTL、异步方法当场生效）。

### 跨区 1V1：测试钉与寻址审计

X1 只动测试（主代码零改动），把 §2.7 的 Z1–Z3、Z5–Z7、Z11、Z12 与 §2.8 的同号碰撞钉在组件层（Z4、Z9 是既有架构的事实，§2.7 的表里没有单独的用例；Z8、Z10 靠切片上的 robot）。统一的碰撞拓扑照本机 `XM_ZONES=2` 的切片摆：两个 zone 各有一台 1 号 scene 与一台 1 号 gate，两台 gate 发出的第一个会话号也相同——
所以两个 zone 的玩家 `session_id`、`gate_node_id`、`scene_node_id` 三个号全部相等，能分开的只有 zone 与实例号。先清点 6.3 / 6.4 已有的用例，只补缺口（类名见「测试类清单」）。

**寻址审计的结论**（静态，逐处回到代码；结论是「没有发现缺陷」）：

1. `RedisKeys` 里带节点号的键全部带 zone（节点号租约与代次、`nodes:{type}:{zone}`、gate 排空的两个键、`gate-push:{zone}:{gate}`）。
2. `PlayerPushes` 的频道 = (在线目录的 zone, gate 节点号)，多人分组键含 zone 与 gate 实例，`GatePush` 带 gate 实例号；`PresenceLobbyAnnouncer` 只经 `PlayerPushes`，battle 房间不拿 `routing.gate_node_id` 寻址。xm-team / xm-guild / xm-friend 与 xm-match 切磋的推送同样只经它。
3. `DubboSceneBattleEvents` 按快照的 `(zone_id, scene_node_id)` 查目录、实例相符才发，否则回落定位器；`SettlementOutbox` 只经 `SceneAssetLocator`（位置记录的 zone + 节点号），快照路由的节点号只进日志。
4. `ScenePreparer` 的端点带 zone；`Compensation` 的取消发回备战时记下的端点，不重新解析。`SceneClientSweeper`（xm-guild 的 `SceneEndpointSweeper` 同形）按地址登记、按节点自己的 zone 读目录。
5. `NodeRpcClients` / `NodeClientCache` / `PlacementClients` 的缓存键都是地址（加实例），不是节点号；Kafka 的 key 是 battle_id / player_id，没有按节点号分区的 topic。
6. `session_id` 作键的地方：xm-login 的设备键含 gate 实例；xm-battle 的观众幂等判定 = 会话号 ∧ gate 节点号 ∧ gate 实例；其余是进程内的或只进日志。
7. zone 隔离靠的几道闸（都有既有用例）：scene 握手拒绝别的 zone 的 gate、gate 核对 `HelloAck` 的 zone 与节点号、gate 令牌的 `target_zone_id`、login 按会话 zone 选 scene、scene-manager 每个请求带 zone。

顺带确认的两处无害现象：两个 zone 的 1 号 scene 可能发出相同的实体号（只在本节点运行期内用，不进战斗快照与 Redis 键）；`GateTokens.verify` 对 `target_zone_id = 0` 的令牌不校验 zone（照搬基线；网关签发时恒填请求的 zone，只有持密钥伪造才会出现）。
没有单测入口的一处：scene 侧 `SceneNode` 里组快照路由的私有装配代码（Z6 的写端），只能靠切片上的 robot Z7 覆盖。

### 测试类清单与模块级证据

**测试类的实际分布**（§10 是开工前的计划；方法名都是中文）

- **xm-api**：`MatchBudgetsTest`（扩充：§5.3 的不等式，1 / 2 / 5 / 10 人仍是 42 / 48 / 66 / 96）。
- **xm-discovery**：`RedisKeysMatchTest`（两个新键与票据 / 落点同槽）、`BattleRoutingsTest`、`PlayerPushesTest`（新；单推 / 按序 / 多人分组 / 踢人的频道都带 zone）、`GatePushCrossZoneIntegrationTest`（新；真 Redis pub/sub）、
  `SceneAssetLocatorTest` / `SceneAssetLocationIntegrationTest`（补同号碰撞）。
- **xm-common**：`BattleTicketsTest`（战斗票没有 zone 字段、跨实例拒绝）、`GateTokensTest`（同号 gate 跨 zone 被拒）。
- **xm-battle**：`SettlementOutboxTest`（接真的 `SceneAssetLocator`）、`DubboSceneBattleEventsTest`、`SceneClientSweeperTest`、`PresenceLobbyAnnouncerTest`（经真的 `PlayerPushes`）、`ObserverTest`（观众换到另一个 zone 的同号 gate）；`DevRoutingResolver` 的既有测试不回归。
- **xm-match**
  - 共享件（先行件扩充）：`SpectateRulesTest`、`MatchTipsTest`（9 条 `parameters[0]` 逐字节）、`MatchMetricsTest`、`MatchPropertiesTest`、`MatchDispatcherTest`（执行器钩子、拒收走 `onOverload`）、`InlineHandlersTest`、`MatchClientMessageServiceTest`、
    `MatchLifecycleTest`（新次序；等 163 有界、不早不晚）、`DirectPlacementDialerTest` / `RedisBattleNodesTest`（硬截止）、`PlacementRecordsTest`、`PlacementStoreIntegrationTest`（不回归）、`MatchApplicationContextTest`（装起来的是真实现、清扫器在启动事件前后与上下文关闭后的运行状态）、
    `MatchSkeletonContextTest`（163 / 164 经真 Triple）、`MatchRpcLoopbackTest`、`LeaseLostTest`（163 / 164 在租约丢失时照常）、`TestDoublesTest`。
  - 存储：抽象契约 `SpectateStoreContract`（由 `InMemorySpectateStoreTest` 与真 Redis 的 `RedissonSpectateStoreIntegrationTest` 共跑）、`SpectateScriptsTest`（脚本的静态约束：不拼键名、只读的段没有写命令、字面量与常量一致）、
    `RedissonSpectateStoreTest`（替身客户端：KEYS / ARGV / 解应答 / 形状不对的应答、迟到重发的补释放）、`SpectateStoreConfigurationTest`。
  - 163：`WatchBattleServiceTest`（§3.1 逐行：tip、`parameters[0]`、outcome、调没调 battle、标记状态；基线用例照移 / 改写加 Java 独有的）、`DefaultObserverDialerTest`、`SpectateExecutorTest`、`WatchBattleHandlerTest`（经真 `MatchDispatcher`）、
    `ObserverRpcLoopbackTest`（真 Triple 回环）、`WatchBattleConfigurationTest`（真装配、预算告警）。
  - 164、钩子、清扫：`WatchableListServiceTest`、`ListWatchableHandlerTest`、`SpectateGatherHooksTest`、`GatherHooksCallSiteTest`（真钩子接在管线上：五个入口的清退都在第一次备战之前、失败出口不公开且观众不恢复、换节点后按 attempt 2 公开）、
    `SpectateSweeperTest`、`WatchableConfigurationTest`。
  - 真流程接真存储：`SpectateFlowRedisIntegrationTest`（真的 163 / 164 / 钩子 / 清扫器接在真的观战、落点、票据存储上；其中一条用 JFR 的 `jdk.VirtualThreadPinned` 事件核对这些流程在虚拟线程上等 Redis 时不钉住载体线程，带对照）。
  - 跨区：`CrossZoneGatherTest`（真 `GatherPipeline` + 真 `Compensation` + 真 `SceneAssetLocator`；观众那一条经真的 `SpectateGatherHooks` 与 `WatchBattleService`）、`ScenePreparerRedisIntegrationTest`、`ScenePreparerTest` / `QueueServiceTest`（各补几条）。
- **xm-robot**：`SpectateStepsTest`、`BattleSmokeChecksTest`、`BattleSmokeScenarioTest`、`BattleCrossZoneChecksTest`、`BattleCrossZoneScenarioTest`、`MatchUpstreamTest`（观战文案、码常量、指标名与标签、回合时长对着 xm-match / xm-api / xm-battle 的源码钉住）、
  `RobotOptionsTest`、`RobotMainTest`、`SliceScriptsTest`。场景用例跑在按本稿手写的假服务端 `FakeMatchWorld` 上（补了观战语义、第二个区与若干故意做错的行为）。
- **xm-gate**：`LocalSliceOrderTest`（未改，继续钉 `start-slice.sh` 的 `SERVICES` 与 `stop-slice.sh` 的清单行）。

带 `xm.it.redis` 开关的新类（缺省跳过；CI 的 Integration 作业要求真跑）：xm-match 的 `RedissonSpectateStoreIntegrationTest`、`SpectateFlowRedisIntegrationTest`、`ScenePreparerRedisIntegrationTest`，xm-discovery 的 `GatePushCrossZoneIntegrationTest`。
测试约定同 6.4：Redis 集成测试用 DB 13、随机 id、只删自己的键；观战的契约用例另用自己的索引键（§10.4）。

**模块级的运行证据**（都是**模块级**的运行：单个模块、`-o` 离线；本机 Windows 11、Temurin 21.0.12；Redis 8.10.2 单机；日期 2026-10-08，日志在 `D:/work/.tools/logs/`）。
命令形如 `./mvnw -s /d/work/.tools/settings.xml -B -o -pl <模块> test`（工作包在各自的 worktree 里直接跑；先行件、集成与评审修正经 `/d/work/.tools/mvn-locked.sh` 串行）。
表里是各份汇报里记下的汇总行，各行对应的是当时那个分支 / 那个时点的代码，**条数只供对照规模，以最终验证为准**。

| 模块 | 命令的其余部分（日志） | 汇总行 | 时点 |
|---|---|---|---|
| xm-api / xm-discovery / xm-battle / xm-match | `-pl xm-api,xm-discovery,xm-battle,xm-match test -Dxm.it.redis=redis://127.0.0.1:6379`（`m65-c0-test.log`） | xm-api `Tests run: 82, Failures: 0, Errors: 0, Skipped: 0`；xm-discovery 210 / 0 / 0 / 0；xm-match 1360 / 0 / 0 / 跳过 42；xm-battle 519 / 0 / 0 / 跳过 4（跳过的都是 MySQL / Kafka 开关的既有用例） | 先行件 |
| xm-team / xm-gate / xm-robot | 缺省档（`m65-c0-test-dependents.log`） | xm-team 341 / 0 / 0 / 跳过 80；xm-gate 214 / 0 / 0 / 0；xm-robot 408 / 0 / 0 / 0 | 先行件（受影响的下游） |
| xm-match | `-Dxm.it.redis=…`（`m65-w1-9.log`） | `Tests run: 1468, Failures: 0, Errors: 0, Skipped: 42` | W1 的分支 |
| xm-match | `-Dxm.it.redis=… -Dxm.it.kafka=127.0.0.1:9092`（`m65-W2-14.log`） | `Tests run: 1496, Failures: 0, Errors: 0, Skipped: 39`（跳过的是要真 MySQL 的 `RatingStoreSqlTest`） | W2 的分支 |
| xm-match | `-Dxm.it.redis=…`（`m65-W3-7.log`） | `Tests run: 1442, Failures: 0, Errors: 0, Skipped: 42` | W3 的分支 |
| xm-common / xm-discovery / xm-battle / xm-match | 各自 `-Dxm.it.redis=…`（`m65-X1-6` … `m65-X1-9`） | xm-common 120 / 0 / 0 / 0；xm-discovery 224 / 0 / 0 / 0；xm-battle 527 / 0 / 0 / 跳过 4；xm-match 1380 / 0 / 0 / 跳过 42 | X1 的分支 |
| xm-robot / xm-gate | 缺省档（`m65-L1-4.log` / `m65-L1-5.log`） | xm-robot 408 / 0 / 0 / 0；xm-gate 214 / 0 / 0 / 0 | L1 的分支（脚本另在假进程沙箱里跑过 7 个用例，不是 Maven 测试） |
| xm-robot | 缺省档（`m65-R-11.log`） | `Tests run: 488, Failures: 0, Errors: 0, Skipped: 0` | R 的分支 |
| xm-match | `-Dxm.it.redis=… -Dxm.it.kafka=… -Dxm.it.mysql=jdbc:mysql://127.0.0.1:3306`（`m65fix-match-full-2.log`） | `Tests run: 1727, Failures: 0, Errors: 0, Skipped: 0` | 评审修正（xm-match 那一段）之后、提交之前的工作区 |
| xm-robot | 缺省档（`m65fix-robot-7-full.log`） | `Tests run: 502, Failures: 0, Errors: 0, Skipped: 0` | 评审修正（xm-robot 那一段）之后 |
| xm-match / xm-robot | `-pl xm-match,xm-robot install`，带真依赖（lead 的验证记录） | xm-match 1728 条、xm-robot 502 条，0 失败 | 提交 `0100ab3`（含 lead 裁决追加的那一处） |

**判别力核对（变异）**——都是「临时改错、跑对应用例确认失败、还原并核对」：

- W2：6 处变异（结局不明改成删标记、复查两个方向写反、重看同一场也发 Remove、建房窗口改用回包后再查的「已公开」、换场的 Remove 改成异步、回滚改成「读到什么删什么」）→ `WatchBattleServiceTest` 分两遍共 17 条失败，每个变异都有对应用例。
- W3：3 处（清退的 RPC 不再与读落点共用每人的截止；164「落点不在」改用会删落点的剔除模式；清扫每轮只接 `RuntimeException`）→ 58 条里 7 条失败，与预期一一对应。
- X1：`Compensation` 把每个人的取消都发给第一个人的端点 → `CrossZoneGatherTest` 3 条失败；`DubboSceneBattleEvents` 查目录写死 zone 1 → 3 条失败；`BattleRoomServiceImpl` 的同会话判定去掉 gate 实例比较 → 只有新增的 `ObserverTest` 用例变红（6.2 原有的「会话变了」用例改的是会话号，抓不到这一处）。
- 评审修正：xm-match 4 处（重看同一场的记号恒置 false、复查的读锁改为读票完成后才发、关掉 acquire 的补释放、处理器自建 4.5 s 截止）→ 129 条里 7 条失败，恰好是为 S65-STORE-1 / S65-STORE-2 / T-06 / T-07 新增或加强的用例；
  xm-robot 去掉 `awaitReadyResidue` / `awaitSettled`、临时放一个占 18115 的 yaml，对应用例分别失败。

**集成提交 `8f39377` 上的全量构建**（集成阶段的记录，2026-10-08；评审修正之前的树）：
`clean install -Dxm.it.redis=… -Dxm.it.mysql=… -Dxm.it.kafka=…` → 27 个模块 BUILD SUCCESS，9249 条用例 0 失败 0 错误，跳过 2 条
（xm-team `TeamMatchRedisIntegrationTest` 里 1 条要拨 Redis 时钟的由内存后端那一遍覆盖；xm-scene `ViewCrowdBenchmarkTest` 缺省关闭的基准）。其中 xm-match 1715、xm-robot 494、xm-battle 527、xm-discovery 224、xm-common 120、xm-api 82。
`java tools/TestReport.java --require-it-executed` 退出码 0：82 个 `xm.it.*` 门控的集成测试类全部真的执行了。评审修正之后的全量构建见「最终验证」。

### robot 场景与切片脚本的现状

**`battle-smoke` 第 7 步（同一个号的第二局 PVE）不再断言胜负**（2026-10-08；首次带观战段的单 zone 切片上，这是 102 个检查项里唯一的失败项）。

- 现象：A 第一局 SIDE_A_WIN（10 回合，结算时 `hp = 261`），第二局 SIDE_B_WIN（9 回合，阵亡）。
- 原因：血量随结算带进下一局（基线同此：scene 把结算的血量写回属性，备战快照取的是当前血量），种子每局随机取，所以第二局阵亡是合法结果。基线的 robot 只对第一局断言胜利（`robot/features_battle_smoke.go:82-95`），
  `m-spec` §15.5 第 7 步的原文也只要求「同样直连、开自动、等到 150」——是 Java 版 robot 多写了一条「必须 SIDE_A_WIN」，服务端没有问题。**这不是两版差异，是 robot 的断言修正**。
- 处理（`a2b4a0d`）：第 7 步改用 `BattleSmokeChecks.pveFinishedProblem`（外层与 settlement 一致、是胜 / 负 / 平之一、指向本局本人、真打过回合）；第 6 步（新号的第一局）仍用 `pveVictoryProblem` 断言胜利。
  阵亡后 scene 在结算时原地复活，后续步骤不受影响。那次提交留下的一条红用例（`BattleSmokeScenarioTest` 里「settlement 是别的局的」——第 7 步改判据之后也会咬住它）在 `0100ab3` 改正。

**切片上的运行**（lead 的验证记录，2026-10-08；**评审修正之前**的构建——`976d7ff` 加上面那条 robot 判据修正；评审修正之后的复验见「最终验证」）：

- 单 zone 切片：`battle-smoke` × 3 各 102 项、`team` 52 项、`match-activity` 6 项、`match-5v5` 5 项，全部通过。
- 双 zone 切片（`XM_ZONES=2`，同一批包；区服列表两个区都是 OPEN）：`smoke`（区 1、区 2）、`battle-cross-zone` × 3（含一遍 `--zone 2 --visit-zone 1`）各 33 项、区 1 上的 `battle-smoke` 102 项、`team`、`match-activity`、`match-5v5`、
  `battle-settle` 72 项、`battle` 60 项、`rollback`、`reconnect`、`zones`，全部通过。这是 Java 版第一次真跑两个 zone；寻址审计（静态）之后没有暴露出要改别的批次生产代码的问题，没有触发 6.5a / 6.5b 的拆分。

**评审修正对 robot 的改动**（S12 的两半、30 s 预算、下线前等大厅 150）在修正阶段只对着 `FakeMatchWorld` 跑通，它们在活切片上的结果以「最终验证」为准。切片上值得看的三处：S12 前半的等待（1V1 很短时约等 55 s）、
S12 后半的 163 是否在切磋局第一回合的 6 s 内发出、xm-battle 日志里观战段不再出现「结算重投用尽（玩家离线）」。

**切片脚本**：L1 的分支上只在假进程沙箱里跑过（约定不许起切片）；真切片上的首跑是上面 lead 的双 zone 记录。已知边角见「遗留」。

**没有做的验证**（写本记录时）：Go robot 的跨版本验收（**本机没有 Go 工具链**）；手工项「观战中 kill xm-battle 再发 163」（号没被接手时 16018「当前无法观战」、重启换实例后 16018「不存在或已结束」并剔除，W5）；
回到 `XM_ZONES=1` 重起后确认区 2 显示 MAINTENANCE 的记录不在上面的清单里（「最终验证」里补上了）；两台 xm-match、双 zone 进 CI。

### 遗留与残余风险

设计层面的取舍已写进正文（§7、§8）；这里列实现与测试层面仍然成立的遗留（各份汇报的 leftovers 去重归并）。

**163 / 观众 RPC**

- S65-STORE-2 只是收窄：Redisson 最后一遍在客户端判它超时之后才被 Redis 执行时，标记留到 TTL（360 s）；「重发越过释放」的实际次序没有在真 Redis 上复现过，只有替身层面的用例。
- W17 ③ 的残余：入口删旧标记「等到截止、而那条删除其实已在路上并随后成功」时，玩家仍在名单里而标记已无（Redis 卡顿时可见，随那一场结束清理）。
- R163-01 的结构性修复没做：`NodeClientCache` 清扫销毁空闲引用时短暂占锁，带硬截止的直拨管不到这段等待；占锁时长没有实测，也没有「出站口的 call 自己阻塞时带硬截止重载的返回时刻」的用例。归 7.6 的连接治理。
- R163-02 没有拒启：`request-budget` 配到 [500 ms, 2300 ms) 的进程照常启动，只有一条 WARN。
- 规格已登记、照做的残余行为：ready 残留 60 s 内 163 回 16014（BW1 / C1）；已结束的战斗不主动出索引，最长留 360 s（BW7 / C2）；自我清退异步、并发 gather 秒败后立刻重看同一场时新登记可能被迟到的 Remove 摘掉；
  换场 Remove 超时后随机又挑中同一场；切磋的备战晚于复查（§7.1 第 16 条）；165 不清观战标记（BW8）；未预期异常若发生在 AddObserver 已成功之后，标记留到 TTL / 下一次 163 / 开局清退。
- 不是合法 UTF-8 的脏观战标记按值删不掉，只能等 360 s 的 TTL（期间该玩家 163 回 16016）；有人手工 SET 了这样的值且不带 TTL 时要人工 DEL。把标记也改成按字节一一对应可以消掉它，没有做。
- 真 Triple 直拨那一段（163 的 AddObserver、钩子的 RemoveObserver 在虚拟线程上经真 Dubbo）的钉住检查只有 W2 的手工验证，JFR 用例不覆盖它；`NodeRpcClients` 既有的极窄首调钉住情形（6.4 的 gather 同样有）没有处理。
- 日志量没有限频：battle 节点整体不可达时 163 每请求一条 ERROR；164 对每条损坏落点每次请求一条 WARN（最长 360 s）；Redis 故障期间清扫每 10 s 两条 WARN；派发器对每次过载打 WARN，163 在途打满时可能刷屏（6.4 就有的行为）。
- `RedissonSpectateStore` 不做 `EVALSHA` 缓存：`evictAsync` 一批最多 50 条、每条带约 1 KB 的脚本文本，只在整页都要剔除时出现。

**164 / 钩子 / 清扫 / 停机**

- 开头读全员标记超过 1 s 就整次跳过清退（标记保留，靠客户端收到参战 177 后改连兜底）；RemoveObserver 超时 / 没送达后标记照删，battle 名单里的残留随那一场结束清理。
- 清扫器的第一轮在一个间隔之后，启动后头 10 s `xm_match_watchable_battles` 读数为 0。
- 停机的 20 s 是设计预算、不是 Spring 的强制截断；`stop-slice.sh` 的 20 s 从 SIGTERM 起算、还包含停凑单与撤 Dubbo 导出，xm-match 最坏停机可能超过而被强杀（6.4 已登记，后果自愈）。
- 内存替身与 Redis 实现只在脏数据上的差别：`a` 字段对得上、只是 `pb` 坏了的落点，Redis 上 `dead` 剔除照删、公开照登记，替身不动；替身不模拟落点的 TTL。

**测试**

- T-05 没动的两处窄窗口用例：`DirectPlacementDialerTest`「battle 永不应答、等到硬截止就返回」（`Deadline.after(150)`）与 `SpectateGatherHooksTest`（`SHORT_BUDGET_MS = 200` 的几条）——创建截止到第一次过期检查之间卡住 ≥ 150–200 ms 时结局会翻面，窗口微秒级。
  `RedisBattleNodesTest`「读条目迟迟不回、一秒后按 ERROR」（6.4 的用例，真等 1 s、上界 3 s）没动，T-03 的做法可以原样套用。
- 含真实等待、机器极忙时理论上可能抖的用例：`WatchBattleServiceTest`「复查的读等到请求预算耗尽」、`WatchBattleHandlerTest`「四条出口」、`ObserverRpcLoopbackTest` 整类、`SpectateSweeperTest` 的调度与启停几条、`BattleSmokeScenarioTest`（每条把场景整条跑一遍）、
  `RedissonSpectateStoreIntegrationTest` 的一万次选场与两处 PTTL 下界断言、`GatePushCrossZoneIntegrationTest` 里「不该收到」的 300 ms 空轮询（方向安全）。
- 观战的真 Redis 用例写本记录时只有本机的运行记录（Redis 8.10.2 单机；CI 的 Integration 作业用 `infra.yaml` 起的也是这个版本，但跑在 Linux 上）；JFR 探测那一条只在本机 Temurin 21.0.12 / Windows 上跑过，JFR 不可用时会失败而不是跳过。以 Integration 作业自己的结果为准。
- `SliceScriptsTest` 现在读各模块的 `application*.yaml`：新模块的缺省端口若与区 2 的五个端口（21010 / 21110 / 18115 / 11010 / 18123）相撞，xm-robot 的测试会红，按提示换端口或改 `start-slice.sh` 的 `ZONE2_*`。
  xm-robot 的 `MatchUpstreamTest` 按文本钉着 `MatchTip` 的文案、`MatchMetrics` 的指标名与枚举，改这些名字要两边一起改。
- `WatchBattleServiceTest` 偏大（按规格行号分段）；`CrossZoneGatherTest` 自带一个类内的 `ZoneScene`（既有替身 `FakeSceneBattle` 表达不了「两个 zone 各一台同号 scene」），以后别的跨区用例可以把它提到 `testing` 包。都没有做。

**切片脚本**

- `sync_zone2_status` 依赖 gateway 的健康探测在 30 s 内看到 xm-gate-z2；超时会明确报错、退出码 1，进程都还在。
- 区 2 的手工状态归脚本管：每次 `XM_ZONES=1` 启动且库里有区 2 时都会把它重置为 MAINTENANCE 并覆盖文案，每次 `XM_ZONES=2` 启动都会置 OPEN；手工把区 2 设成别的状态会被覆盖。
- `XM_ZONES=2` 且启动切片的 shell 里设了 `XM_GATE_ADVERTISE_PORT` 时，两个 gate 会通告同一个端口（脚本没有给 xm-gate-z2 单独清掉它；本机切片一般不设）。
- `battle-crash-window.sh` 的区号按字符串比较（`--zone 01` 也被拒）；`battle-after-store` 在区 2 上本来可以做，现在一律拒绝。
- 中文经 java 命令行被弄坏是本机（ANSI 代码页 1252）的实测，中文代码页的 Windows 与 Linux / macOS 上没有测；现在的做法在哪种环境都不依赖命令行编码。`start-slice.sh` 的 `SERVICES` 十三项里没有区 2 的实例（它们各自紧跟区 1 的同类启动）；仓库根 `README.md` 与 `xm-robot/README.md` 里「进程清单以 `SERVICES` 为准」那一句已注明 `XM_ZONES=2` 时另有这两个。

**robot**

- 不在 robot 里验的（规格如此）：16016（并发抢占）、16017 的「全服没有可观战战斗」、16019；`battle-cross-zone` 的 Z1 是顺序登录、X16 的归属区断言没有实现；第二局排队对 16000 的重试路径在假服务端上走不到。
- 1V1 的 1 号配置队列全服共享：切片上有别的客户端在排它时 `battle-cross-zone` 会在 Z4 失败。
- 预清理的边界：列表恰好满 50 条时无法用「没变短」提前停，最多跑满 30 轮；挑中别人的活战斗后 SC 的观战标记会留到 360 s 过期（165 不清标记），之后 SC 的 163 会先对那一场发一次 rewatch 的 RemoveObserver（那台 battle 不可达时最多吃掉 3 s 预算）。
- 失败路径上 SA 的 X 与场景末尾的 B 不等结算就下线（四遍切片日志里没有出现过，没有处理）。规格 C1（163 对 ready 残留自愈）落地后 robot 观察不到变化，要保留观察点得另加一条 163 探针，没有做。
- `FakeMatchWorld` 是按规格写的客户端可见行为的简化模型，只钉 robot 这一侧，不能当作服务端行为的证明。

**文档**

- 正文回写之后本稿的行号整体后移：`ops-release-spec` 引的 `spectate-spec.md:347`（指 §2.8 第 4 条「battle 通告地址对所有 zone 可达」）与 `:776`（指 Q21）都已不在那两行，附录 A.3「行号稳定性」那一段的结论随之作废；
  它们不在本批改动的文件里，只登记，建议改成按条目号引用。正文里引的其它规格与 Java 源码的行号是成稿时的，没有逐个更新，以节名、类名 / 方法名为准。
- 本批没有回写的：`ops-release-spec` 里给 6.5 预留的告警条目（A84）；`deploy-ci-spec` 的模块 / 端口 / 秘密表里仍没有 xm-match（6.4 就登记过）。

### 给 5.4 / 7.1b / 7.6 的接口与义务

**给 5.4（跨 zone 传送与重定向）**

- 现成可用的：`XM_ZONES=2` 的切片（xm-scene-z2 / xm-gate-z2 的实例名、端口与启停次序见 §10.6；区 2 的入口是 gateway `assign-gate {zone_id: 2}` → 127.0.0.1:11010）；脚本报「全部就绪」时区服列表里区 2 已是 OPEN；
  robot 的 `--visit-zone` / `XM_ROBOT_VISIT_ZONE`（`RobotOptions` 记录的 `visitZoneId`）；`FakeMatchWorld` 的第二个区；同号碰撞的测试钉与寻址审计结论（上文）；`SliceScriptsTest` 的端口冲突检查。
- 5.4 要接着做的：226 / 124、GO-5、X16、归属区路由等全部生产代码（6.5 一行都没有碰）；`--visit-zone` 的帮助文本现在只写了 `battle-cross-zone` 的用途，`travel` 落地时补「travel 的目的区」；
  `battle-cross-zone` Z1 的归属区断言要 `PlayerFlow` 把角色列表里的 `zone_id`（与实际连接的 gate 地址）暴露出来；X16 合入之前，双 zone 切片上从区 2 进来的新号归属区会记成 1（`CreatePlayerHandler` 取 login 配置的 zone）；
  `team` X1 / X2 与 `guild` 第 4 步的跨区步骤照旧跳过；`zt-spec` §11.9 的 MZ 用例都没有跑。
- 要知道的约定：区 2 在区服目录里的状态归切片脚本管（上文「切片脚本」）；故障变体脚本只认区 1；若 5.4 仍想按 §5.13 原设计在 gateway 上播种区名，名字只能走环境变量或 ASCII（Spring 的列表环境变量绑定没有验证过）。

**给 7.1b（整栈 compose 与 CI）**

- Q22 的前置清单①–⑤原样成立、一条都没有做：compose 里给 xm-battle 配 `XM_BATTLE_CLIENT_ADVERTISE_HOST`；xm-gate-z2 的 `xm.dubbo.match-url` 指向 `tri://xm-match:20888`；两 zone 用覆盖文件 `deploy/compose/two-zones.yaml`（gateway 两个区一起播种，不在单 zone 的 stack 里常驻一个 OPEN 的 2 区）；
  z2 服务放在覆盖文件里以免模块清单漂移守卫判红；`ci-spec` 与 `zt-spec` §11.9 对「多 zone 进不进 CI」各登记一句（本批已各补一句现状）。本机切片上区 2 的端口见 `ci-spec` §2.1。
- 可选 job：Go robot 的 `battle-smoke` 与 `battle_smoke_cross_zone` 跨版本验收（§10.9）；整栈上跑 Java 的 `battle-smoke`（观战段让它多出约 2–4 分钟）与 `battle-cross-zone`。
- 新增的四个 `xm.it.redis` 集成测试类已在 `TestReport --require-it-executed` 的计数里；JFR 那一条要求 CI 的 JDK 带 JFR。

**给 7.6（发布与连接治理）**：`NodeClientCache` 的「锁内摘表、锁外销毁」（R163-01）；`xm_match_watchable_battles` 多副本取 max、`xm_match_spectate_inflight` 求和（§6）；xm-match 多副本下清扫器各跑各的、幂等；battle 的通告地址要对所有 zone 的客户端可达（§2.8 第 4 条）；
容器宽限期公式里的 20 s 由 xm-match 停机第 3–5 步守住（§4.9）。

### 最终验证（2026-10-08）

在含全部评审修正的最终代码（提交 `0100ab3` 的代码；其后的收尾提交只有文档与几处注释 / 回显措辞的订正）上执行；本机 Windows 11，JDK 21.0.12；Redis 8.10.2、MySQL 8.4.11、Kafka 4.3.1 单机，与 CI 的依赖镜像同版本。

**全量构建**：`./mvnw -B clean install -Dxm.it.redis=redis://127.0.0.1:6379 -Dxm.it.mysql=jdbc:mysql://127.0.0.1:3306 -Dxm.it.kafka=127.0.0.1:9092`
→ 27 个模块 BUILD SUCCESS，9270 条用例、0 失败、0 错误，跳过 2 条（xm-team `TeamMatchRedisIntegrationTest` 里要拨 Redis 时钟的 1 条，由内存后端那一遍覆盖；xm-scene `ViewCrowdBenchmarkTest`，缺省关闭的基准）。各模块条数：
xm-proto 7、xm-table-codegen 18、xm-table 22、xm-net 21、xm-common 120、xm-battle-engine 2236、xm-pbmysql 127、xm-api 82、xm-discovery 224、xm-player-store 59、xm-gateway-store 10、
xm-audit 18、xm-scene-manager 232、xm-login 170、xm-friend 84、xm-chat 10、xm-team 341、xm-guild 531、xm-trade 175、**xm-match 1728**、xm-scene 1417、xm-battle 527、xm-gate 214、
xm-gateway 90、xm-data 305、**xm-robot 502**。`java tools/TestReport.java --require-it-executed` 退出码 0：82 个 `xm.it.*` 门控的集成测试类全部真的执行了。
收尾时订正的注释与切片脚本回显之后，另跑了 `MatchLifecycleTest`、`RedissonSpectateStoreTest`、`MatchUpstreamTest`、`SliceScriptsTest`（82 条，0 失败）。

**GitHub Actions**：`976d7ff`（集成之后）与 `0100ab3`（评审修正）的 CI（构建与单测）与 Integration（真依赖）都通过。中间两个提交是红的，都不是功能缺陷：`ad96065` 见上面「过程」（`8f39377` 恢复）；
`a2b4a0d`（robot 第 7 步的判据修正）那次提交只跑了 `BattleSmokeChecksTest`，漏了 `BattleSmokeScenarioTest` 里一条随判据变化要同步的用例，`0100ab3` 改正。本次收尾提交的结果以它自己的 run 为准。

**本机切片**（最终代码的 jar；dev 运行模式；三种形态先后各起一遍，共 62 次场景运行，全部退出码 0；括号里是检查项数）：

- **单 zone 单 scene**（`tools/local/start-slice.sh`，13 个服务进程；区服列表：区 1 OPEN、区 2 MAINTENANCE）——32 个场景全部通过：
  - 匹配与观战：smoke(3)、**battle-smoke(103)**（观战段 S0–S13 加原匹配段；`BATTLE_SMOKE_OK`）、team(52)、match-activity(6)、match-5v5(5)；battle-smoke 与 team 换 run-tag 另各多跑 2 遍，全部通过。
  - 回归：battle(60)、battle-edge(20)、battle-settle(72)、movement(20)、currency(7)、attribute(11)、bag(11)、features(20)、skill(15)、pet(23)、token(12)、reconnect(9)、friend(26)、chat(8)、
    guild(75)、guild-economy(78)、trade(79)、audit(3)、guard(5)、rollback(19)、mirror(47)、dungeon(31)、zones(15)、killswitch(6)、queue(7)、drain(7)、ratelimit(5)。
- **双 zone**（`XM_ZONES=2 tools/local/start-slice.sh`，多起 xm-scene-z2 与 xm-gate-z2；区服列表：区 1、区 2 都是 OPEN）：smoke(3)、`smoke --zone 2`(3)、
  **battle-cross-zone(33)** × 3（`--zone 1 --visit-zone 2` 两遍、`--zone 2 --visit-zone 1` 一遍；`CROSS_ZONE_MATCH_OK`）；区 1 上的回归 battle-smoke(103)、team(52)、match-activity(6)、match-5v5(5)、
  battle-settle(72)、battle(60)、rollback(19)、reconnect(9)、zones(15)，全部通过。
- **单 zone 双 scene**（`XM_SCENE_NODES=2`；紧接在双 zone 之后起，区服列表里区 2 回到 MAINTENANCE——区 2 的状态跟随 `XM_ZONES`，裁决 5）：smoke(3)、cross-node(41)、battle-smoke(103)、team(52)、
  match-activity(6)、match-5v5(5)、battle-settle(77，两个指标地址)、rollback(19)、reconnect(9)、mirror(47)、dungeon(31)、battle(60)，全部通过。
- **评审修正对 robot 的改动在活切片上的结果**：六遍 battle-smoke 里 S12 的两半都通过（前半用 153 等到 NOT_QUEUED，后半切磋局里的 163(0) 一次就回 16015）；屏障期 30 s 的预算没有被触到。
  「下线前等大厅 150」只保证结算**已应用**，不保证已销账：xm-battle 的日志里仍有「结算重投用尽（玩家离线），摘除；记录留给进场恢复」（WARN），三遍切片共 11 条——
  6 条是 match-activity 的活动局（两个号打完即下线，6.4 就有），5 条是 battle-smoke 观战段 S11 的局（六遍里出现在四遍）。后者的次序是：scene 应用结算（推大厅 150）→ robot 约 3 ms 后下线 →
  销账要等落盘，发起销账的实例已经不在，记录留给下次进场恢复（进场时账本挡住重复应用，再销账）。这是 6.3 的既定路径（scene-battle-spec 的销账次序），不是缺陷；
  上面「切片上值得看的三处」里的第三条（「不再出现」）是修正阶段的预期，按这里的实测订正：等大厅 150 收窄的是「结算还没应用就下线」，不是这条 WARN。
- **服务端日志里的 ERROR**：单 zone 那一遍四条，与 6.4 相同、都在预期内——xm-match 1 条（match-activity 的负面用例：发起人不在名单首位）、xm-battle 1 条（活动局结果重发用尽后摘除——消费方随 4.6，副本仍在 Redis）、
  xm-guild / xm-login 各 1 条（guild、killswitch 场景的负面用例）；双 zone 与双 scene 两遍各只有 xm-match 的那 1 条。

**没有做的验证**（登记为遗留）：Go robot 的跨版本验收（本机没有 Go 工具链）；手工项「观战中 kill xm-battle 再发 163」（W5 判死的三条证据只有组件层的用例）；两台 xm-match；双 zone 进 CI（留给 7.1b）；
`XM_ZONES=2` 与 `XM_SCENE_NODES=2` 同时开的形态；`battle-settle --slow` 与两个 kill -9 故障变体没有在这一批的切片上重跑（6.5 没有改那条链路的服务端代码）。
