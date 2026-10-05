# 数据工具（批次 7.4）移植统一规格：一致性巡检、存盘压测与收敛校验、玩家数据导出 / 导入、压测机器人 AI

> **基线**：mmorpg `D:\work\mmorpg` @ `26ceb70ca`（浅克隆、稀疏检出）。引用写作 `路径:行号`，路径相对 mmorpg 根目录；Java 路径相对本仓库根目录。
> **Java 侧**：HEAD `aa8b5b5` 加工作区。5.1–5.5、6.1–6.4、7.1、7.2 有的已落地、有的正在实现，它们的规格（`docs/porting/*-spec.md`）按权威设计看待。
> **行号口径**（评审补）：Java 源码的行号按 2026-10-05 的工作区；`docs/design/architecture.md` 的行号按 HEAD `aa8b5b5`——工作区里它正被并行批次改写（第 42 行起陆续插入，2026-10-05 已漂移 16 行以上），落地时以小节标题为准重新核对。
> **本稿**由三份分区稿合并而成。合并时把互相矛盾的地方逐条回到代码复核，裁决与证据见 §0.4。
> **契约影响**：四个功能都不改客户端契约，不需要运行 ContractSync。客户端能感知到的只有已有行为：GM 持有归属期间进游戏回 2005，被踢下线收到 23 `{2017}`（与 data-ops-spec D1 / D7 同一条通路）。所以本批没有查阅 Unity 客户端（另一个稀疏克隆 `D:\work\mmorpg-client`）。
>
> **稀疏检出缺口**（已用 `git show HEAD:<path>` 补读，或确认基线里根本没有）：
> - `robot/config/config.go`、`go/test.ps1`：在 HEAD 树里，只是没检出，已用 `git show` 只读读取。引用的行号以该对象为准。
> - `tools/scripts/chaos_test.ps1`：**HEAD 树里不存在**（`git ls-tree -r HEAD` 查不到），却被多处引用：`tools/AGENTS.md:28`、`:65-68`、`:75`，`docs/design/data-consistency-stress-testing.md:4`、`:253`、`:365`。所以基线的 L4 混沌层无法复现。
> - C++ 金样对拍文件 `stress_test_probe_test.cpp`：HEAD 里不存在，只有 `.h` 和 `.cpp`。可是 `go/db/internal/stresstest/probe_test.go:27-30` 和 `stress_test_probe.h:12-15` 都说有这个文件。
> - `tools/scripts/debug_fetch_data.ps1`：`docs/ops/online-debug-data-fetch.md:7` 写了这个包装脚本，但它不在版本库里。

---

## 0 概览与范围

### 0.1 盘点 id 与本批产出

| 盘点 id（`docs/porting/roadmap.md:94`） | 基线 | 基线实际能用到什么程度 | Java 产出 | 子批 |
|---|---|---|---|---|
| data-consistency-check | `tools/data_consistency_check/main.go`（524 行，Go CLI） | 四项检查。帮会榜那一项查错了 Redis 库，恒为假绿（§1.2 C1）。**从没在 staging 或生产跑过**（`docs/design/server-merge-gap-fixes.md:73` B8） | xm-data 包 `com.game.data.consistency`：27 项检查、定时运行、手动触发、指标；薄客户端 `tools/ConsistencyCheck.java` | 7.4a、7.4b |
| data-stress-verifier | `go/db/cmd/{data_stress,verifier}`、`go/db/internal/stresstest`、C++ `stress_test_probe.{h,cpp}`、`robot/data_stress.go` | L2 能用。L3（robot）实际只验证「行存在且签名对」（§2.3 S7）。L4 的编排脚本不存在 | 存储层围栏 soak / IT；scene 存储故障注入；`xm-robot data-stress`（不变量 DS1–DS10）；混沌矩阵 C1–C6 | 7.4b |
| player-data-debug-tools | `go/data_service/cmd/{debug_fetch,debug_import,debugutil}` | player / Redis 模式读写的不是 scene 的权威数据（§3.2 H1）；player-db 导入会静默丢行，大整数会失真（H3 / H4） | xm-data 运维接口：导出包、按区列玩家（7.4b）；带围栏的导入作业，只在 dev / test 运行模式开放（7.4c）；薄客户端 `tools/PlayerData.java` | 7.4b、7.4c |
| robot-stress-ai | `robot/logic/ai/{robot_ai,action,llm}.go`、`robot/main.go` 的 stress 分支 | `move` 只发 43、`chat` 必被拒、所有动作发出即记成功、退出码恒为 0（§4.3） | `xm-robot stress`：同名同值的档位外加 `stress-aoi`、动作按真实语义发送、按应答计结局、闸门与退出码；LLM 策略作为可选项（7.4e） | 7.4d、7.4e |

### 0.2 结论（一页纸）

1. **全部落在 xm-data 运维面和 xm-robot，不新增进程**。xm-data 管理端口 18106，令牌 + 操作人 + 审计（`architecture.md:219-222`），这与 7.2 的决定一致（`data-ops-spec.md:56`）。直连库的 CLI 一律不移植：库口令不下发到运维机，夺权、审计、发号已经在 xm-data 进程里。`tools/*.java` 只做 HTTP 薄客户端。
2. **巡检读权威数据，并区分「没查」与「查了干净」**：
   - zone 集合取 `zone_config` 全部行，不从 Redis 推断。
   - 全部改用精确查询（keyset 分页 + 反连接），不用数量启发式，也不用 `ORDER BY RAND()` 抽样。
   - `NOT_CHECKED` 是一等状态，CLI 用退出码 3 表示。
   - 加入 Java 独有的不变量：归属围栏、租约时钟偏差、僵尸归属、好友对称与计数、帮会成员与帮主、交易挂单 zone、world 登记、资产通道 seq 水位（PITR 后纪元没抬高的哨兵）。
   - 补上基线一直待做的「各表 zone 与归属区一致」（`server-merge-gap-fixes.md:352`）：帮会成员的归属区 = 帮会 zone、挂单 `market_zone` = 卖家归属区。
   - 报告存 Redis（xm-data 可以多副本，`architecture.md:216`），**缺省每天 04:10（UTC+8）定时跑**。基线真正的失败模式就是「从没跑过」。
3. **存盘压测不移植探针、签名和 Kafka 驱动**：Java 没有 Kafka 存盘链和缓存层；在 `PlayerState` 里盖探针还会破坏脏比对跳过（`architecture.md:670`）。改用玩法上可观测的载体，它们分处同一个事务的两半：
   - **货币**写在 `player_state`：钻石作轮次计数器，金币作玩家指纹。
   - **位置**写在 `player` 行：同图保留坐标，`SceneWorld.java:769-773` 已复核。
   这样能查出「内容落后一轮」「行与 blob 撕裂」「串号」「崩溃损失超界」「资产多出」，基线 robot 模式一项都查不出。验证不了就判失败，修掉基线 verifier 的 fail-open（`go/db/cmd/verifier/main.go:248-252`）。
4. **导入 = 带围栏的运维作业，只在 dev / test 开放**：
   - 导入包先落成一份 `SNAPSHOT_GM_IMPORT` 快照，再走 7.2b 回档的单玩家写事务：安全快照 `PRE_GM_EDIT` + 带围栏覆盖写 + 流水 `TX_GM_IMPORT` + 明细，四件事一个事务。
   - 同号导入；跨环境复现用 `createIfAbsent` 建同号的行。v1 不支持改号克隆。
   - 生产环境的恢复只走 7.2 的「快照 + 回档」，外部字节永远不进生产库。
5. **压测 AI 改成真实语义**：
   - `move` 发 134 / 132 / 131（基线发的是空的 43）；`chat` 走 WORLD 频道并带 request_id（基线频道为 0，必被拒）。
   - 按应答计结局；有时长、有闸门，退出码即结论。
   - 统计行前缀与 Go 逐字相同，两版可以用同一套汇总解析。
   - LLM 决策作为可选的 `ActionPolicy` 移植（7.4e）：密钥只读环境变量，提示词不带玩家号，异步调用，不挡节拍。

### 0.3 客户端可见面

| 场景 | 客户端看到什么 | 是否新增 |
|---|---|---|
| 导入作业夺权期间该玩家登录 | 2005（同 7.2，`EnterGameHandler.java:275`、`:309-318`） | 否 |
| 导入 `ifOnline=kick` | 被踢的会话收 23 `{2017}` 后断开（顶号通路，PARITY:37） | 否 |
| 巡检、导出、压测 | 无。robot 只使用现成的客户端消息；GM 37 只在 dev / test 运行模式生效 | 否 |
| 机器人动作 | 契约不变，只是机器人发出的流量形状与 Go robot 不同（R1 / R2） | 否 |

### 0.4 分区稿分歧的裁决（逐条复核过代码）

| # | 分歧 | 裁决 | 证据 |
|---|---|---|---|
| 1 | 巡检报告：存 Redis，还是只在进程内存留 20 轮 | **Redis**：运行记录 TTL 8 天，加最近 20 个 runId 的列表 | xm-data 可以多实例（`architecture.md:216`；`data-ops-spec.md:1003`）。只存内存时，`GET /runs/{id}` 打到另一个副本会 404 |
| 2 | 单飞：Redis 锁，还是进程内互斥 | **Redis 锁** `xm:consistency:lock`，不复用 `ops_active` | 理由同上。只读巡检不该挡住回档，回档也不该挡住巡检 |
| 3 | 定时：缺省开（每日 04:10），还是缺省关（7.6 再开） | **缺省开**，非 deep，`-` 关闭 | 基线的真实失败是「从没跑过」（B8）。巡检只读、有预算，并安排在游戏日 05:00 切点之前（PARITY:54） |
| 4 | 复核延迟：35 s 还是 2 s | **按检查分**：纯库检查 2 s；与租约有关的 35 s（大于 30 s 租约）；`own.ghost_presence` 65 s（评审修订：大于在线目录 TTL 60 s） | `PlayerPresenceDirectory.java:39`（TTL 60 s）；gate 崩溃或撤销失败时条目最多再活一个 TTL（`GatePresence.java:20-21`） |
| 5 | 孤儿引用定 warn 还是 block | **warn**；好友孤儿占边数 ≥ 5% 升 block（沿用基线 `main.go:474-476`）。CI 用 `--fail-on warn`，任何 warn 都判红 | Java 没有删角色、删账号的路径，孤儿只会来自 bug 或局部恢复。开发库清表会制造大量孤儿，定 block 太吵；CI 的空库不会有噪声 |
| 6 | 帮会榜检查有没有基线缺陷 | **有（C1）**：基线用 mapping 客户端（DB 0）扫 `guild_rank:zone:*`，而帮会榜在 guild Redis 的 DB 2，结果恒报「no orphan」 | `tools/data_consistency_check/main.go:103`、`:149`、`:330`；`tools/merge_zone/main.go:12`、`:113`；键名 `go/guild/internal/data/guild_repo.go:185` |
| 7 | 巡检的命令行入口：`xm-robot consistency` 还是 `tools/ConsistencyCheck.java` | **`tools/ConsistencyCheck.java`** | robot 只是客户端探针（`xm-robot/README.md:8`）；先例 `tools/GmShutdown.java` |
| 8 | 退出码：0 / 1 / 2，还是 0 / 1 / 2 / 3 | **0 / 1 / 2 / 3**：3 = 没有失败级发现，但有 NOT_CHECKED | 让「没查成」在 CI 和 runbook 里可区分 |
| 9 | 压测载体：位置 + 货币，还是只用货币（理由是「坐标不跨轮保留」） | **两者都用**。坐标跨轮保留**成立**，条件是落地图与存档图相同 | `SceneWorld.java:769-773`（同图且不在原点才沿用存档坐标）；`MovementScenario.java:42`（重登后位置等于停止点）；干净登出落默认主世界（`architecture.md:678`）。所以 robot 留在默认主世界，或者只在同图频道间切换 |
| 10 | 压测读服务端：`/admin/consistency/players`，还是 `GET /admin/players/{id}/state` | **两个都要**：批量只读的归属元组（结算期轮询用）+ 导出包（终态内容核对用） | — |
| 11 | 导入接口与语义：`POST /admin/players/{id}/import` + `createIfAbsent` + 不支持跨号；还是 `POST /admin/player-imports` + `crossPlayer` + 重发 id | **`POST /admin/player-imports`**（与 `/admin/rollbacks` 同形）+ 同号 + `createIfAbsent`；导入包落成 GM_IMPORT 快照，复用 7.2b 的写事务；v1 不重发 id | 基线的 `-player` 覆盖本身就是坏的：只改了 DELETE 的条件，INSERT 的行仍带源 player_id（`debug_import/main.go:77-79`、`:302-343`）。同号导入在语义上等于「回到外部内容」，与回档一致，不需要改写实例号 |
| 12 | 导入跑不跑资产分歧检查 | **目标原先已存在**：照跑 data-ops-spec §4.6 三道检查。**由 `createIfAbsent` 新建**：跳过，因为没有可分歧的对象，在 ACCEPTED 事件里记一笔 | — |
| 13 | LLM 决策：不移植，还是作为可选项移植 | **作为可选项移植**（最后一个子批 7.4e），并做安全改造；用户可以裁掉 | AGENTS §1「所有功能两个版本都要做」。基线缺省关闭（`robot/etc/robot.yaml:42-46`），实现量约 150 行，不加依赖 |
| 14 | switch_scene 缺省发「当前图」还是「另一张图」；以及「另一张图在两个节点下会触发 5.2」这一说法 | **缺省 same-map**（与基线请求形状相同），另提供 `other-map` / `cross-node`。只带配置号的 63 **优先在本节点内解决**：per-node 覆盖下不跨节点；跨节点只发生在 63 指定了不在本节点的 scene_id 时。唯一例外是当前频道正在排空、本节点又没有别的活频道（`SceneWorld.java:570-571`），那时同图 63 会走远端 | `SceneWorld.java:560-584`；`CrossNodeScenario.java` 类注释 |
| 15 | move 参数：≤ 5 m/s、±15 m，还是 ≤ 10 m/s、±30 m | **≤ 5 m/s、出生点 ±15 m（水平面 x / y，z 是高度、不动）、每 250 ms 一条 132、单次行走 ≤ 2 s** | 视野半径 10 m（`MovementScenario.java:61`）；`MoveGuard` 以 12 m/s 累积额度、封顶 24 m，单次上报相对上次接受位置的水平位移超过额度就被截断（PARITY:45；`MoveGuard.java:29-31`，12 = `MovementRules.java:13` 的 10 m/s × 1.2）；世界 z-up（`docs/reference/mmorpg-client-contract-movement.md:26`） |
| 16 | 世界聊天的放大估算（N × 权重 ÷ 间隔 × N 条推送） | **估算不成立**：Java 世界聊天成功后不推送给任何人，各自拉取 | `ChatService.java:42`。压力在 Redis 的 LPUSH 和发言人限速（每秒 5 条，`xm-chat/.../application.yaml:46`） |
| 17 | 「限速」怎么计 | gate 按消息号超频回的是**信封** `error_message=1008`；聊天业务限速回的是**应答体内** tip 1008。两者分开计数 | `ClientDispatcher.java:237-239`；`ChatService.java:184` |
| 18 | 132 是否撞限频 | 不会：132 每秒 20 条、134 / 131 每秒 15 条、54 每秒 10 条，其余缺省每秒 3 条 | `D:\work\mmorpg\generated\tables\messagelimiter.json`（与 `config-data/tables/messagelimiter.pb` 同批数据） |
| 19 | 检查分页：2000 行还是 5000 行；整轮预算：5 min 还是 15 min | **2000 行、15 min**（单项仍是 60 s，同基线） | 检查从 4 项增加到 27 项 |
| 20 | `guild.zone_id = 0` 定 warn 还是 block | **block**：zone 0 永远不合法 | 建帮写创建者的归属区（恒非 0，建角 zone 为 0 回 2020，`zone-travel-spec.md:117`）；入帮审批时申请人缺行或 zone 为 0 回 14018（`guild-spec.md:1513`）；zone 0 只用于查询全服榜 |

### 0.5 勘误

**盘点稿**（`docs/porting/inventory/`）：

1. `data.md:352`：写的是「mapping 里的 player_id 在 simple_players 里找不到 → warn」，实际只是「mapping 数 > 账号数 × 5」的数量启发式（`main.go:405-415`）；写的是「输出 markdown 报告」，实际是 stderr 上带时间戳的日志行（`main.go:496-523`）。
2. `data.md:364`：写的是「缓存缺席软通过」，代码里缓存缺席判失败：成功条件要求 `cacheOK`（`verifier/main.go:293-297`）。
3. `tools.md:380`：提议的 `xm-ops check-consistency` 不采用，落点改为 xm-data。
4. `tools.md:508`：写的是 move 发「134 / 132 / 131」，实际发的是 43 SceneInfoC2S（`robot/logic/ai/robot_ai.go:184-185`）。
5. `tools.md:511`：写的是「另有 default 档」，实际是 fighter / explorer / chatter 三档；档名不认识时静默回落 stress。
6. `tools.md:548`：坐标编码轮号的思路**成立**，但要以「落地图 = 存档图」为前提。「经只读 JDBC 校验」改为经 xm-data 运维接口；「kill / restart 编排复用 `java tools/Dev.java`」不成立——`tools/` 下没有 `Dev.java`，编排改为 `tools/local/data-stress.sh`（§5.9）。
7. `tools.md:474`（currency-crash-window）与 `:476`：「Java 无周期存盘」已过时，周期存盘 2026-10-02 已做（PARITY:57）。
8. `contract-robot.md:380`、`:382`：把 data-stress 判为 `not_applicable`，理由是「Java 不走 Kafka 存盘链、无 verifier」与「写回只在离场 / 断线（无周期存盘）」。后者已过时；再加上 5.2 交出、5.5 疏散、7.2 GM 写都新增了写路径，所以**这项需要做**。
9. `contract-robot.md:392`（robot-currency-crash）：「54 / 37 在 Java 回 1006；且 Java 无周期存盘」两条都已过时——54 / 37 已由 `CurrencyFeature.java:42-44` 实现（37 只在 dev / test 运行模式放行），周期存盘见第 7 条。

**分区稿**：

- 「坐标不跨轮保留」：错（见 §0.4 第 9 条）。
- 「`other-map` 在两个节点下触发 5.2」：错（第 14 条）。
- 世界聊天推送放大：错（第 16 条）。
- 基线帮会榜检查「无缺陷」：错（第 6 条）。
- 友表的表名是 `friend`，不是 message 名 `friend_edge`（`xm-friend/src/main/proto/xm/friend/friend_tables.proto:19`）。

### 0.6 拆批与依赖

| 子批 | 内容 | 前置 |
|---|---|---|
| **7.4a** 一致性巡检 | 框架（运行器、单飞、报告、SchemaGuard、zone 集合）、a 组 11 项检查、接口、`tools/ConsistencyCheck.java`、定时、指标 | 无（只读）。可以先于 7.2b |
| **7.4b** 导出与存盘压测 | b 组 16 项检查；xm-scene 新增计数 `xm_scene_owner_leases_lost_total`（§2.8 C2 的判据）；`/admin/consistency/players`；导出包与按区列玩家；`tools/PlayerData.java export / zone`；存储层 soak / IT；scene 存储故障注入；`xm-robot data-stress`；本机混沌脚本；CI 混沌 job | 7.4a。交出变体依赖 5.2；按区列玩家最好有 7.2b 的 `idx_player_zone`，没有就按主键全扫，并只在 dev 使用 |
| **7.4c** 导入 | `POST /admin/player-imports`、`insertImportedPlayer`、GM_IMPORT 快照、`TX_GM_IMPORT`、`RestoreComposer`（与回档共用）、robot `player-data` | 7.2b（`AdminOwnership`、作业框架、`RollbackExecutor`）；`TX_GM_IMPORT` 的取值排在 6.3 的 `TX_BATTLE_REWARD = 1005` 之后（§3.7） |
| **7.4d** 压测机器人 | `xm-robot stress`：档位、动作、`StreamingInbox`、统计行、闸门 | 无，可以最先做 |
| **7.4e** LLM 策略（可选） | `LlmPolicy implements ActionPolicy` | 7.4d |

推荐顺序：7.4d → 7.4a → 7.4b → 7.4e → 7.4c（等 7.2b 落地）。每个子批各自提交、推送、登记 PARITY（AGENTS §5）。

---

## 1 一致性巡检（data-consistency-check）

### 1.1 基线行为（`tools/data_consistency_check/main.go`）

- **形态**：一个独立 Go module，目录里只有 `main.go`、`go.mod`、`go.sum`，**没有测试**。只报告、不修复，也不连玩家数据 Redis（`:24-29`）。设计意图是每周 cron，或每次合服后跑一次（`:21-22`）。
- **参数**：
  - `-mysql-dsn`：主库。
  - `-guild-schema`：缺省 `mmorpg_guild`，过一遍 `^[A-Za-z0-9_]{1,64}$` 正则后拼进 SQL（`:64-67`、`:114-116`）。
  - `-friend-mysql-dsn`：好友独占库，连不上不致命（`:96-100`、`:131-147`）。
  - mapping Redis 三个参数（`:101-103`），`-redis-db` 缺省 0。
  - `-live-zones`（`:105-108`）。
  - `-timeout`：总预算 5 min（`:110`）；每项检查 60 s（`:202-206`）。
  - 主库或 mapping Redis 连不上就 `log.Fatalf`（`:122-129`、`:149-153`）。
- **存活 zone**：缺省 SCAN `player:zone:*` 再 MGET，凡出现过的值都算存活（`:215-249`）。`-live-zones` 可以显式覆盖，用于合服失败、mapping 处在中间状态时（`:155-159`）。集合为空就 Fatal（`:180-182`）。
- **四项检查**（清单在 `:195-200`）：
  - `guild.zone_id`（`:277-320`）；
  - `guild_rank:zone:*`（`:325-364`）；
  - player → account（`:374-417`）；
  - `friend.friend_player_id`（`:428-485`）。
  - 邮件孤儿那项已于 2026-05-23 删除，理由是 mail 表不存在，表缺失时报 info，读起来像干净通过（`:187-194`、`:487-492`）。
- **输出**：`printReport` 用 Go 标准 `log` 写 stderr，每行带时间戳（`:496-523`），并不是文件头说的 markdown。有 block 时 `os.Exit(1)`（`:209-212`）。

### 1.2 基线缺陷（mmorpg 待做候选，Java 不照搬）

| # | 缺陷 | 依据 |
|---|---|---|
| C1 | **帮会榜查错了 Redis 库，恒为假绿**：用 mapping 客户端（`-redis-db` 缺省 0）扫 `guild_rank:zone:*`，而帮会榜在 guild Redis 的 DB 2。同一个客户端也不可能同时对两边都正确，因为 `player:zone:*` 在 DB 0 | `main.go:103`、`:149`、`:330`；`tools/merge_zone/main.go:12`、`:113`；`guild_repo.go:185` |
| C2 | guild 表读不到时报 **info「not present」**（`:281-285`），正是好友那一项注释反对的降级（`:426-427`）。Scan 失败静默 continue，不查 `rows.Err()`；逐区 COUNT 的错误被丢弃（`:297-305`、`:314`） | 同左 |
| C3 | player → account 只比数量（`:405-415`），注释自己也承认查不出个别悬挂；`user_accounts` 不在时报 info（`:399-402`） | 同左 |
| C4 | `ORDER BY RAND() LIMIT 1000`（`:446`）本身就要读全表再排序，注释说「避免全表扫」不成立（`:441-446`） | 同左 |
| C5 | 好友只查 `friend_player_id` 一端（`:462`），注释却说两边都查（`:461`）。EXISTS 出错时 `.Val()` 返回 0，被算成孤儿，Redis 一抖孤儿率就会被抬高到 block。低于 5% 记 warn「自然衰减」（`:473-483`） | 同左 |
| C6 | 从 Redis 推断存活区，会把没人的区（新开区、合服中途已全部改映射的源区）当成下线（`inventory/tools.md:384`） | `:215-249` |
| C7 | 「发现不一致」与「根本没能运行」退出码都是 1（`log.Fatalf` 也退 1） | `:209-212` |
| C8 | 从没运行过：deploy 里唯一的 CronJob 是 MySQL 备份；`docs/ops`、`tools/scripts` 都没有引用它 | `deploy/k8s/manifests/infra/mysql-backup-cronjob.yaml:33`；`server-merge-gap-fixes.md:28`、`:73` |
| C9 | 待补项一直没补：trade 巡检（`deploy/k8s/README.md:591`）、邮件 `mail_system_state` 孤儿（`docs/design/mail-system.md:735`）、「各表 zone_id 与 home_zone 一致」（`server-merge-gap-fixes.md:348-354`） | 同左 |

### 1.3 Java 的对象与 zone 集合

- **单库**：账号、玩家、帮会、好友、交易、区服目录都在 `xm_java`。帮会、好友、交易表经 pbmysql 建（`*_tables.proto`）；区服目录在 `zone_config`（`xm-gateway-store/src/main/resources/db/xm-gateway-schema.sql:5-17`）。基线那种三个 DSN、库名拼进 SQL 的问题在 Java 里不存在。
- **没有删除路径**：全仓没有 `DELETE FROM player` / `DELETE FROM account`，只有测试夹具会删 `player_state`。所以指向不存在玩家的引用只能来自 bug、局部恢复或人工改库，不存在基线所说的「自然衰减」（`main.go:477-479`）。
- **zone 集合 Z**：
  - 取 `zone_config` 全部行，不论 `manual_status` 是 OPEN / MAINTENANCE / CLOSED / PREVIEW（`xm-gateway-schema.sql:8`）。
  - zone 0 永远不在 Z 里：建角时 zone 为 0 回 2020（`zone-travel-spec.md:117`）。
  - 读不到 `zone_config` 时整轮失败，对应基线主库挂掉就 Fatal 的口径（`main.go:127-129`）。
  - **区服目录的行可以被无条件删除**：`DELETE /admin/zones/{zoneId}`（`ZoneAdminController.java:114-119`）不检查该区还有没有玩家、帮会、挂单。误删仍有数据的区，正是 `zone.*` 各项要抓的情形（报 block）；给删除接口加「区内仍有数据则 409」属于 7.3 / 7.6 的运维面，不在本批。robot `zones` 场景只对临时区做 assign-gate、不建角（`ZonesScenario.java:80`、`:116`、`:176`），不会在 CI 里留下 zone 孤儿。
  - 请求可以带 `zones` 覆盖，对应基线 `-live-zones`；报告里写明 `zoneSource=override`。
- **合服接口点**：「已并入的区」怎么表示由 7.3 决定（推荐给 `zone_config` 加 `merged_into`，Q4）；在那之前全部行都算存活。任一 zone 立着合服围栏（7.3 的 `xm:merge-fence:{zone}`，`zone-travel-spec.md:449`）时，`zone.*` 各项报 `NOT_CHECKED(merge_in_progress)`，除非请求显式带了 `zones`。7.3 之前围栏的读侧只留检查点、恒放行（同 `zone-travel-spec.md:112` Q6）。

### 1.4 检查目录

check id 是固定枚举，同时用作指标标签。「deep」项只在请求带 `deep=true` 时跑；定时运行缺省不带。

| id | 对象与判定 | 级别 | 依据 / 基线对应 | 子批 |
|---|---|---|---|---|
| `zone.player` | `player.zone_id ∉ Z`（`GROUP BY zone_id`，有 7.2b 的 `idx_player_zone` 就走索引） | block | Java 的归属区就是这一列（`zone-travel-spec.md:52`），是基线「从 mapping 推断存活区」的反面 | a |
| `zone.guild` | `guild.zone_id ∉ Z`，包括 0 | block | `main.go:277-320`；Java 的 `guild.zone_id` 是合服闸门的权威来源（`guild_tables.proto:45`） | a |
| `zone.guild_rank` | `SMEMBERS xm:guild:{rank}:zones` 的成员 `∉ Z` | block | `main.go:325-364`。读 Java 的索引 SET（`RedisKeys.java:200-206`），读的是正确的键空间，修 C1；不做 SCAN | a |
| `zone.trade_listing` | 未终结挂单的 `market_zone ∉ Z`（终结状态取 `ListingStatuses`，实现时核对；P1 只写 LISTED=2） | block | 基线待做（`deploy/k8s/README.md:591`）；`trade_tables.proto:42` | b |
| `zone.world_registry` | `SMEMBERS xm:world:zones` 的成员 `∉ Z`。退役区要手工 SREM，否则 scene-manager 还会去竞选它的领导锁 | warn | `RedisKeys.java:274-281` | b |
| `zone.guild_member_home` | 帮会成员的 `player.zone_id ≠ guild.zone_id`（`guild_member ⋈ player ⋈ guild`，按 `guild_member` 主键分页） | block | 基线待做「各表 zone 与 home_zone 一致」（`server-merge-gap-fixes.md:352`）。Java 入帮审批要求申请人归属区 = 帮会 zone（`guild-spec.md:1049`），帮会操作按 zone 闸拒绝别区的人（`:972`）；不一致说明合服漏改了一边，这个人既用不了这个帮、又因 `uk_guild_member` 进不了别的帮 | b |
| `zone.trade_listing_home` | 未终结挂单的 `market_zone ≠` 卖家的 `player.zone_id` | warn | 同上；`market_zone` = 上架时卖家归属区、合服时改写（`trade_tables.proto:42`）。不一致 = 挂单出现在错误的区市场 | b |
| `account.player_account` | `player.account` 在 `account` 表里没有行 | warn | 修 C3（精确反连接）。登录时会「取 / 建账号」，影响有限 | a |
| `account.player_cap` | 某账号的角色数超过上限（`max-players-per-account`，须与 xm-login 一致，缺省 5） | warn | 上限由事务保证（`architecture.md:645-648`），超出说明绕过了建角路径（人工改库或导入） | a |
| `player.name_key` | `name_key ≠ PlayerStore.nameKey(name)` | block | 唯一键只由 nameKey 算（`architecture.md:621-622`）；不一致说明唯一性被绕过。**deep** | a |
| `player.state_orphan` | 有 `player_state` 行，却没有 `player` 行 | warn | — | a |
| `player.state_decode` | `player_state` 解不出来，或 `PersistedAssetLedger` 判为无效 | block | 解不出 = 进不了场；账本坏 = 资产通道对他关闭（`PersistedAssetLedger.java:15-21`）。**deep** | b |
| `assetop.seq_watermark` | 对每个有 `guild_player_op_seq` 行的 (玩家, 流)：账本该流 `stream_epoch` > 行的 `epoch` → block；两者相等且账本 `max_seq ≥ next_seq` → block（发号方要重发 scene 已见过的 seq）；相等且 `next_seq − 1 − max_seq > 511` → warn（流卡死，新 seq 会被判 JUMP_TOO_FAR） | block / warn | 「库恢复后由运维手册显式抬高纪元」（`guild-economy-spec.md:426`）是 PITR 的硬步骤，漏做时同一 seq 会被当成已应用、结局错配；字段见 `player_state.proto:43-55`、`guild_tables.proto:133-145`。§1.8 把 PITR 列为事件后触发，这一项就是它的哨兵。只读、按 `guild_player_op_seq` 主键分页再点查 `player_state`；交易流（3 / 4）随 4.8 加入。**deep** | b |
| `own.fence` | `player_state.saved_epoch > player.owner_epoch` | block | 按构造不可能出现，出现即围栏被破坏。交出后 `saved_epoch = E`、`owner_epoch = E+1` 是合法的（`scene-handoff-spec.md:717-718`、`:792`） | a |
| `own.lease_skew` | `owner_released = 0` 且 `owner_lease_until > dbNow + 30 s + lease-skew-tolerance` | block | 租约只会续到写者墙钟 + 30 s（`architecture.md:651`、`:658`）。超出说明写者墙钟超前，破坏 NTP 前提（`architecture.md:666`），会把玩家挡在 2005 外更久 | a |
| `own.abandoned` | `owner_released = 0` 且 `owner_lease_until < dbNow − abandoned-grace` | info（只计数） | scene 崩溃或失联留下的；下次登录可以正常夺权（`architecture.md:659-660`），这些增量已经丢了。只作取证 | a |
| `own.zombie` | 租约仍有效，却没有 `xm:presence:{p}`；隔 35 s 复核，**同一个 `owner_epoch`** 仍未释放、租约仍有效、仍无 presence（epoch 变了算 transient） | warn | 有进程在为没有会话的玩家续约。断线照常写回并释放（短线重连不复用内存实例，`architecture.md:681`），所以没有「重连宽限期内持有」的合法情形。7.2 作业运行期间（`ops_active` 有行）报 `NOT_CHECKED(ops_job_running)`，因为 xm-data 自己持有的归属看起来就像僵尸 | b |
| `own.ghost_presence` | 有 `xm:presence:{p}`，但 `owner_released = 1`；隔 **65 s** 复核仍如此 | warn | 推送会发给已离线的人。presence TTL 60 s、gate 每 TTL/3 续期（`PlayerPresenceDirectory.java:39`；`GatePresence.java:20-21`）：gate 被杀或撤销失败时条目最多再活 60 s，35 s 复核会误报，所以复核间隔必须大于 TTL。presence 里带 `owner_epoch`（`friend-spec.md:983`），样本里一并给出 | b |
| `friend.orphan` | `friend`、`friend_request`、`friend_block` 的**任一端**不在 `player` | warn；`friend` 表孤儿占边数 ≥ 5% 升 block | 修 C4 / C5（精确、两端都查）；5% 门槛沿用 `main.go:474-476` | a |
| `friend.symmetry` | 有 (a,b) 却没有 (b,a) | block | 「一对好友存成双向两行，同一事务里同增同减」（`friend_tables.proto:17`） | b |
| `friend.capacity` | `friend_capacity.friend_count` ≠ 实际边数；有边却没有容量行也算 | block | `friend_tables.proto:47` | b |
| `guild.member` | `guild_member` / `guild_application` 的帮不存在 → block；`player_id` 不在 `player` → warn | block / warn | `uk_guild_member` 规定一人至多一个帮（`guild_tables.proto:69`），悬空的成员行会让这个人永远进不了帮 | b |
| `guild.leader` | `guild.leader_id` 不是本帮 role=3 的成员，或者一个帮有多个 role=3 | block | `guild_tables.proto:40` | b |
| `guild.capacity` | 成员数 > `max_members` | warn | `guild_tables.proto:44` | b |
| `guild.rank_score` | 区榜成员不是本区的帮，或者分数 ≠ `guild.score`（ZSCAN 抽样，复核） | warn | 「ZSET 由它重建」（`guild_tables.proto:46`） | b |
| `trade.seller` | 挂单的卖家不在 `player`；`trade_favorite.player_id` 不在 `player` 也计入 | warn | dev 播种要先解析卖家的归属区，卖家不存在就拒绝（`SeedListingService.java:101-103`），所以任何一条都说明有人绕过了写路径（`trade_tables.proto:40`、`:70`） | b |
| `ops.job_residue` | ① `ops_active` 行指向的作业已到终态，或心跳早于 `dbNow − 2 × stale-after`（清扫器没在工作，后续作业全被 409 `ops_busy` 挡住）；② 终态为 SUCCEEDED 的作业明细里还有 PLANNED | warn | data-ops-spec §7.4。REJECTED / CANCELLED / FAILED / INTERRUPTED 作业留下的 PLANNED 是「计划了、没写」的正常记录（data-ops-spec 没有要求把它们改成终态），**不报**，否则每个被拒的作业都会让 CI 判红 | b |

**留给后续批次的钩子**：每个批次把自己的检查实现成 `ConsistencyCheck`，作为它自己的验收项登记进目录，做法同 data-ops-spec Q13。

- 6.3：战斗锁 / 待结算悬挂，即 `xm:battle:{<pid>}:lock` 与 `:settlement`（`scene-battle-spec.md:52-55`）。发件箱超时摘除的记录本来就「留给 4.6 的巡检器」（`scene-battle-spec.md:947`）。
- 6.4：`match_rating_applied`。
- 4.8：交易托管；交易流（3 / 4）并入 `assetop.seq_watermark`。
- 7.3：合服围栏残留 `xm:merge-fence:{zone}`；`zone.guild_member_home` / `zone.trade_listing_home` 是合服 verify 步骤的主力判据。

### 1.5 不纳入的项

| 项 | 不纳入的理由 |
|---|---|
| `player.scene_config_id` 不在场景表里 | 无害：进场只在「同图且不在原点」时沿用存档坐标，否则落出生点（`SceneWorld.java:769-773`）。而且 xm-data 不加载配置表 |
| 帮会资产指令积压 | 已有指标 `xm_guild_assetop_pending_oldest_age_seconds` |
| 组队、聊天、位置记录 | 都由 TTL 兜底 |
| `player_state` 内部的物品配置号 | 由各自的 loader 做 fail-closed 校验 |
| 邮件 | Java 未做，不登记空检查（基线 `main.go:487-492` 的教训） |
| 「不该跨区的好友」（`server-merge-gap-fixes.md:350`） | Java 的好友本来就可以跨区（`FriendEntry.zone_id`，`friend-spec.md:65`） |

### 1.6 结果模型、状态与退出码

- **单项结果** `CheckResult`：
  - `id`
  - `status`：`OK | INFO | WARN | BLOCK | NOT_CHECKED`
  - `reason`：只在 NOT_CHECKED 时出现，取值 `table_missing` / `column_missing` / `statement_failed` / `budget_exceeded` / `merge_in_progress` / `ops_job_running` / `redis_unavailable`
  - `bad`、`total`、`scanned`
  - `transient`：复核时消失的个数
  - `samples`：≤ `sample-limit` 条
  - `note`
- **整次运行** `RunReport`：
  - `runId`（雪花，取自 `SCENE_GUID` 号段，`OpsIds`）
  - `trigger`：`schedule | manual`
  - `operator`、`reason`
  - `startedMs`、`finishedMs`、`dbNowMs`
  - `zoneSource`、`zones`、`deep`
  - `worst`：`CLEAN | INFO | WARN | BLOCK | ERROR`
  - `checks[]`
  - **ERROR** = 有 NOT_CHECKED，或者运行本身失败；它与 BLOCK 分开，修 C7。
- **格式**：以 JSON 为准；`?format=md` 渲染成 markdown 表，列与基线报告相同（级别、检查、bad、total、说明）。玩家号只出现在样本、应答和审计日志里，不进指标标签（AGENTS §5）。
- **只报告、不修复**，同基线 `main.go:24-26`。修复走 7.2 的运维作业或排障工具。
- **`tools/ConsistencyCheck.java` 的退出码**：

  | 退出码 | 含义 |
  |---|---|
  | 0 | 没有达到 `--fail-on` 级别（缺省 block）的发现，也没有 NOT_CHECKED |
  | 1 | 有达到 `--fail-on` 级别的发现（优先于 3） |
  | 3 | 没有失败级发现，但有 NOT_CHECKED |
  | 2 | 用法错误、连不上、409 / 503，或运行本身失败 |

### 1.7 扫描方式

- **读一致性**：每项检查在一个 `@Transactional(readOnly = true, isolation = REPEATABLE_READ)` 事务里跑完自己的全部语句。InnoDB 在第一条读语句时建立一致性快照，所以同一项检查里的分页与跨表反连接看到的是同一时刻的已提交状态。
  - Connector/J 缺省 `readOnlyPropagatesToServer=true`：会话成为 READ ONLY，误写会在服务端报 1792。
  - 从不 `FOR UPDATE`，不阻塞游戏写。
  - 事务时长受 `check-budget`（缺省 60 s）约束，超出报 `NOT_CHECKED(budget_exceeded)` 并附已扫行数，避免长事务拖住 undo purge。**绝不报 OK**。
- **分页**：大表按主键 keyset 分页，缺省每页 2000 行；从不用 OFFSET，从不用 `ORDER BY RAND()`。
  - 无符号主键 ≥ 2^63 时，Java long 为负，参数沿用 xm-data 现成的 `UnsignedLongTypeHandler`，pbmysql 表用 `PbMysql.uint64(..)`。
  - 每条语句都设 MyBatis `timeout`（JDBC 语句超时，缺省 30 s），另加 `/*+ MAX_EXECUTION_TIME(n) */` 提示；H2 把它当注释。
- **复核**：
  - 报 block / warn 之前，对候选逐个按主键或键点查复核（至多 `recheck-limit` 个，缺省 1000）。仍然坏的才计入 `bad`，消失的计入 `transient`；超出复核上限的部分照原样计入，并在 note 里写 `recheck_truncated`。
  - 复核间隔：纯库检查 2 s；`own.zombie`、`zone.guild_rank`、`guild.rank_score` 这类跨 MySQL × Redis 或与租约相关的，35 s（大于 30 s 租约）；`own.ghost_presence` 65 s（大于在线目录 TTL 60 s，§1.4）。
- **不给 `owner_released` / `owner_lease_until` 建索引**：在线玩家每 10 s 续一次租约（`architecture.md:658-660`），索引的写放大会落在最热的列上。`own.*` 用分页全扫，安排在低峰。
- **时钟**：每轮开头取一次 `dbNow`（`SELECT UNIX_TIMESTAMP(NOW(3)) * 1000`），所有租约判定都相对它。要检测的正是写者时钟与库的偏差。取法封装成 `DbClock`：H2 单测注入固定值，不依赖 H2 对 `UNIX_TIMESTAMP` 的兼容。
- **失败口径**：表或列不存在（`SchemaGuard` 用 information_schema 核对）、语句失败、Redis 不可用、超预算，一律报 NOT_CHECKED 并写明原因，修 C2 / C3。
- **不拼外部输入**：表名、列名都是代码常量。读别的模块的 pbmysql 表属于按列名的只读耦合；pbmysql 只扩不缩、字段号不复用（`architecture.md` §7 第 2 条），列名是稳定的。万一漂移，会以 `NOT_CHECKED(column_missing)` 暴露，CI 判红（§11.6）。xm-data 不依赖 xm-friend / xm-guild / xm-trade：它们是 repackage 过的胖 jar。
- **Redis**：只用 `SMEMBERS`（索引 SET，元素数 = zone 数）、带 COUNT 的 `SCAN xm:presence:*`（Cluster 下逐主节点迭代，用 Redisson 的按模式键迭代，不用单节点 Lua SCAN）、`ZSCAN`，以及 `own.zombie` 对候选玩家的按键批量 GET（每批 ≤ 500，复用 `PlayerPresenceDirectory` 的批量读）；从不 `KEYS`，从不写业务键。

### 1.8 触发、调度与单飞

- **定时**：
  - `xm.data.consistency.cron` 缺省 `0 10 4 * * *`（Spring 六段式：秒 分 时 日 月 周），时区 `xm.data.consistency.cron-time-zone` 缺省 `Asia/Shanghai`，落在游戏日 05:00 切点之前（PARITY:54）；写 `-` 关闭。
  - 用 Spring 自带的 `CronTrigger` 加一个单线程 `ThreadPoolTaskScheduler`（`data-consistency-lock`：只做定时触发、续锁、刷新 gauge，都是毫秒级）；检查本身在另一个单线程执行器 `data-consistency-run` 上跑。巡检一跑十几分钟，不能占住续锁所在的线程，否则锁会在运行中过期。
  - 不用 `@EnableScheduling`，与 `DataNode` 显式管理执行器的写法一致（`DataNode.java:208`）。
  - 定时运行不带 deep。
- **手动**：`POST /admin/consistency/runs {checks?, zones?, deep?, reason}` → 202 `{runId}`。
- **事件后触发**：下列流程收尾时都调一次手动触发，以 worst 作门禁，写进各自的 runbook：
  - 7.2b 整区回档作业；
  - 7.3 合服的 verify 步骤；
  - PITR 恢复的最后一步；
  - data-stress 收尾（§2.7）。
- **单飞**：
  - Redis 锁 `xm:consistency:lock`：`SET NX PX 60000`，值是令牌，执行中每 20 s 按令牌续期。
  - 定时触发拿不到锁记 `skipped`；手动触发回 409 `consistency_busy`；Redis 不可用时手动回 503 `redis_unavailable`，定时记 `error`。
  - 锁丢失（续期失败）就在下一个分页边界停下，整轮记 ERROR。
- **报告存放**：
  - `xm:consistency:run:{runId}`：JSON，TTL 8 天。
  - `xm:consistency:runs`：LIST，`LPUSH` + `LTRIM 0 19`。
  - 两个键都经 `RedisKeys` 新增。
  - 任何副本都能读。**每个副本每 60 s 读一次 `xm:consistency:runs` 的头一个 runId**，有新报告就用它刷新本副本的 gauge（启动时同样读一次）。只在执行的副本上更新 gauge 会让别的副本永远停在旧值：前天 block、今天 clean，`max` 仍是 block，告警摘不掉。
- **与 7.2 作业不互斥**：不复用 `ops_active`；作业期间只有 `own.zombie` 报 NOT_CHECKED。

---

## 2 存盘压测与收敛校验（data-stress-verifier）

### 2.1 基线：不变量与实际覆盖

设计文档 `docs/design/data-consistency-stress-testing.md:25-33` 定义了五个不变量：

| 不变量 | 设计口径 | 实际情况 |
|---|---|---|
| I1 MySQL seq == 期望 | strict 或 atleast | 校验器已实现 |
| I2 缓存 seq == 期望 | 缓存缺席算软通过（文档 `:30`；校验器文件头 `:9-12`） | **代码里缺席判失败**：成功条件要求 `cacheOK`（`verifier/main.go:293-297`） |
| I3 Kafka 无积压 | — | 没实现，要人工配合 `kafka-consumer-groups.sh`（`verifier:14-16`；文档 `:216-217`） |
| I4 同分区读后写 | — | 没有任何工具验证 |
| I5 崩溃恢复一致 | — | 只设计了「杀 db 消费者」这一格，编排脚本又不存在 |

### 2.2 基线：探针、四层、校验器

- **探针载体**：`PlayerStressTestProbe{test_seq, test_sig}`，挂在 `player_database` 第 9 字段、`player_database_1` 第 2 字段（`proto/common/database/mysql_database_table.proto:119`、`:170`）。
- **签名**：两路不同种子的 FNV-1a-64 拼成 16 字节，输入是 `player_id_be8 ‖ msg_type ‖ seq_be8`。它不是 MAC，只用来识别撕裂、错路由、拼接的载荷（`go/db/internal/stresstest/probe.go:7-13`、`:26-68`），金样在 `probe_test.go:31-36`。
- **C++ 镜像**（`stress_test_probe.cpp:116-155`）：只有 `STRESS_TEST_PROBE=1` 时才盖（`:99-102`）；seq = `max(prior+1, now_us)`（`:157-183`）；在脏比对之后、Save 之前盖（`player_lifecycle.cpp:2474-2519`），退出收敛比较时剥掉（`:664-674`）。
- **L1**：`key_ordered_consumer_test.go` 的 TC1–TC6 加签名金样（文档 `:98-118`）。
- **L2** `go/db/cmd/data_stress`：直接往 `db_task_zone_<z>` 生产 DBTask，生产者幂等、acks=all（`main.go:99-111`、`:228-232`）；跑完写 `verify:expected:player_database:<pid>=N`（TTL 24 h），并 `SADD verify:enrolled:*`（`:245-256`）。
- **L3** robot `mode: data-stress`（`robot/data_stress.go:161-300`）：每轮登录 → 等 scene ready → 按 stress 档 AI 玩 `play_seconds` → 发一次 EnterScene → LeaveGame → 睡 500 ms → 关连接；每轮成功后写 expected = 轮号（`:144-152`）。缺省 50 个号 × 10 轮 × 每轮 5 s（`robot/etc/robot.data_stress.yaml:16`、`:30-35`）。
- **L4**：文档说 `chaos_test.ps1` 会把 db 消费者杀掉并重启 3 次（`:250-270`），但这个脚本不存在。toxiproxy、Redis OOM、MySQL 切主、scene 硬杀仍是手工项（`:272-279`）。
- **校验器** `go/db/cmd/verifier`：
  - 单 zone（`:119-124`）。
  - 按 enrolled 集合，以并发 32 查 MySQL 行与 Redis 缓存；`-wait 60s -interval 2s` 轮询（`:107-110`、`:157-205`）。
  - 不一致分 11 种 kind（`:349-391`）；Prometheus 指标 `verify_*`（`:63-88`）。
  - 收敛退出 0；发散，或截止前没人登记，退出 2（`:204-205`、`:458-465`）。

### 2.3 基线缺陷（mmorpg 待做候选）

| # | 缺陷 | 依据 |
|---|---|---|
| S1 | 单个玩家检查出错（缺 expected 键、Redis 报错）时只打日志、**不计 mismatch**，收敛判定因此 fail-open | `verifier/main.go:248-252`、`:277-283` |
| S2 | MySQL 读错误与「行不存在」混为一谈 | `:318-319` |
| S3 | 只校验 `player_database`；`player_database_1` 同样盖了探针，却不校验 | `:155`、`:316` |
| S4 | JSON 摘要漏了两个签名标志 | `:417-418` |
| S5 | L2 造的 DBTask 不带 `owner_epoch`，消费端走 legacy-zero 分支，**永远测不到归属围栏** | `data_stress/main.go:209-215`；`key_ordered_consumer.go:609-611` |
| S6 | L2 驱动：发送失败也把 expected 记成 N（`:233-237`、`:245-250`）；文件头写 SHA-256（`:12-15`），实际是 FNV（`probe.go:7-13`）；注释写的是已废弃的 `registration_timestamp`（`:174-176`）；`break` 只跳出 select，没跳出循环（`:152-155`） | 同左 |
| S7 | **robot 模式实际只验「行在且签名对」**：C++ seq 是微秒时间戳（约 1.7e15），expected 是轮号（≤ 10），atleast 恒成立，robot 注释自己也承认（`robot/data_stress.go:52-58`）。yaml 里给的校验命令不带 `-mode`，缺省 strict，在 robot 模式下**恒失败**（`yaml:11-12`，对照文档 `:243-246`、`:362`） | 同左 |
| S8 | robot 一轮的「成功」不等 LeaveGame 应答、也不等存盘，固定睡 500 ms 就判 ok（`:281-299`）；登录失败分支不计失败统计（`:204-207`）；写 expected 到 Redis 的错误被吞掉（`:151-152`） | 同左 |
| S9 | `chaos_test.ps1` 与 C++ 金样对拍文件缺失 | 见本稿开头 |

### 2.4 Java 要证明什么

- **存盘链不同**：
  - 离场时同步写 MySQL，`player` 行与 `player_state` 在同一事务、同一 `owner_epoch` 围栏下写入（`architecture.md:613`、`:649-665`；`xm-player-schema.sql` 的 `player_state` 注释）。
  - 在线周期存盘缺省 300 s，可由 `XM_SCENE_SAVE_INTERVAL` 改（`xm-scene/src/main/resources/application.yaml:97`）。
  - 写路径有：最终写回并释放、在线存盘、5.2 原子交出（E → E+1）、5.5 疏散、顶号让出、7.2 GM 夺权后写入。
- **要证明的五件事**：
  - (a) 新归属者加载到的，一定是上一个写者写回后的状态（基线 I4）。
  - (b) 旧写者永远盖不过新写者。旧写者包括被杀后复活的、暂停后恢复的、交出后迟到的在线存盘。
  - (c) `player` 行与 blob 不撕裂。
  - (d) 没有永久悬挂或僵尸归属。
  - (e) 进程被杀时只丢最近一次在线存盘之后的增量（`architecture.md:672`），而且**绝不多出资产**。
- **已知缺口**：最终写回 5 s 内重试 3 次后放弃（PARITY:57「Java 待做」）。压测要把它暴露出来（C3），不能绕开。
- **已有确定性用例**：`PlayerStoreSqlTest` 共 26 个，含 5.2 交出与写回的并发、锁等待。缺的是随机化、长时间的混合竞争。

### 2.5 不移植的部分（都不是客户端可见）

- **探针字段与签名**：Java 的 `player_state` 是自有格式。盖探针会让周期存盘的「与上次落库快照逐值比较，相同跳过」永远判脏（`architecture.md:670`）；基线为此专门把盖探针挪到脏比对之后（`player_lifecycle.cpp:2474-2483`）。这还会在生产代码里留一条只给测试用的分支。
- **L2 Kafka 驱动、`verify:*` Redis 键、校验器的 Prometheus 端点**：Java 存盘不走 Kafka；期望值放在 robot 本机的运行目录文件里，不往共享 Redis 写测试键。
- **缓存类不变量（I2 / I3）**：Java 存盘没有缓存层和队列。I3 在 Java 的对应物是「审计管线不丢」（DS9）。

### 2.6 不变量 DS1–DS10

**载体**（每个账号在 robot 本机账本里记录基准值）：

- **钻石（币种 1）作轮次计数器**：每轮 GM 37 加 1，`round(bal) = bal − base_diamond`。
- **金币（币种 0）作玩家指纹**：第 1 轮 GM 37 加 `f_p = 1 + (player_id mod 1_000_003)`，之后不再动它。
- **位置作第二计数器**：第 r 轮停在 `spawn + (0.4 × (1 + r mod 8), 0, 0)` 米处，`spawn` 是第一次进场时自己 21 里的位置。只在本轮落地图与存档图相同时有效：robot 始终留在默认主世界，或者只在同图频道间切换，同图保留坐标（D10，`SceneWorld.java:769-773`）。

**不确定带**：GM 37 等应答超时、或者在中断轮里，这一笔可能生效也可能没有，robot 按区间 `[确认值, 确认值 + 不确定数]` 记录，由下一次观测收敛。

| # | 内容 | calm（无注入） | chaos（混沌） |
|---|---|---|---|
| DS1 读后写（基线 I4） | 第 r+1 轮进场看到的状态等于第 r 轮确认的状态：54 的钻石、金币，以及（有效时）自己 21 的位置，容差 0.05 m | 严格 | 中断轮之后改用 DS7 |
| DS2 最终收敛（I1） | 全部轮次结束、归属全部释放后：① 再登一次，客户端看到的等于最后确认值；② 导出包里已落盘的余额与位置等于它（服务端一侧）。只有超出不确定带才判 BEHIND / AHEAD | 严格 | 以恢复后的轮次为准 |
| DS3 不撕裂 | 位置解出的 `r mod 8` 等于钻石解出的 `r mod 8`；两者同一事务写入 | 必须成立（离场写回发生在本轮屏障之后，两个载体都已是本轮值） | 位置轮号 ∈ {钻石轮号, 钻石轮号 + 1}（mod 8）：被杀后读到的是最近一次在线存盘，它可能落在本轮「已移动、未加币」之间，这是一份自洽的中间态，不是撕裂；其余取值一律失败 |
| DS4 不串号（替代 test_sig） | 金币始终等于 `base_gold + f_p` | 必须成立 | 必须成立 |
| DS5 围栏指标 | 全部 scene 节点上 `xm_scene_storage_writes_seconds_count`，`op` ∈ {save, release, handoff} 且 `result` ∈ {fenced, failed, rejected}，以及 `op=progress` 且 `result` ∈ {failed, rejected} 的增量为 0，对应基线「`stale_owner_write_rejected` 压测期恒 0」（`scene-handoff-spec.md:1068`）。`{op=progress, result=fenced}` **不作判据、只打印**：离场不等在途的在线存盘，迟到的在线存盘盖不过已提交的最终写回（`SceneWorld.java:96-97`），存盘间隔 5 s 加频繁离场时它在 calm 下也会出现 | 严格；开了换节点另要求 `xm_scene_transfers_total{result=lost_unknown}` 为 0；全程没有非预期的 23 `{2017}`（被围栏拒绝的在线存盘若命中仍在场的实例会踢人，那才是事故） | C2 要求 `fenced` ≥ 1 或 `xm_scene_owner_leases_lost_total` ≥ 1（新增计数，§5.2）。注入下 fenced 不作失败判据（§8.2 第 9 条） |
| DS6 归属卫生 | settle（calm 缺省 45 s = 30 s 租约 + 15 s；`--chaos tolerate` 时缺省 65 s，大于在线目录 TTL 60 s——gate 被杀后 presence 条目最多再活一个 TTL）内全部登记玩家 `released = 1`，`saved_epoch ≤ owner_epoch`，无 presence，位置记录不存在或是墓碑。Δ`owner_epoch` 落在 `[确认进场数 + 交出数, 确认进场数 + 交出数 + 不确定进场数]` | 严格 | 允许「未释放且租约已过期」（被杀节点的遗留）；**绝不允许**「未释放且租约仍有效」（僵尸） |
| DS7 崩溃损失有界（I5） | 被杀后重登看到的钻石值 B 满足 `下界 ≤ B ≤ 上界`。下界 = max(最近一次「重登后观测到」的值, 确认时刻早于「断线时刻 − 2 × save-interval − 2 s」的最后一轮)；上界 = 最后一次已发出的加币（含不确定带）。**B > 上界一律失败**（资产多出）。下界取两个周期：周期存盘按槽到期，上一次在途时本周期跳过（`xm_scene_periodic_saves_total{result=in_flight}`），最坏晚一个周期落库；若期间 `{result=deferred}`（存储积压推迟）增量 > 0，下界判定记 UNVERIFIED 而不是放宽 | — | 严格；robot 运行时用 `--save-interval` 与服务端对齐，建议 5 s |
| DS8 不倒退 | 同一玩家后一次登录看到的轮号 ≥ 前一次 | 严格 | 严格（C3 除外，见 §2.8） |
| DS9 审计一致（基线 I3 的 Java 对应） | 消费积压归零后（抓 xm-data 的 `xm_data_kafka_consumer_lag` 全部为 0，且两次读数之间 scene 的 `xm_scene_audit_records_total{result=acked}` 不再增长；轮询至多 30 s）：TX_GM_GRANT(9) 流水条数落在确认加币次数的不确定带内，最后一行的 after 等于终值；最近一份 LOGOUT 快照的 `owner_epoch == saved_epoch`、内容与已落盘一致（先比 sha256，不等再比两份 `StateJson` 的 JSON 树，见 §8.2 第 10 条）；不存在 PRE_ROLLBACK / PRE_GM_EDIT 快照（排除运维写入的干扰） | `--audit off` 时显式打印「未检查（显式关闭）」；开着却验证不了 → UNVERIFIED，判失败 | **放宽为「不多出」**：条数 ≤ 上界、不存在同一笔加币的重复行；缺行只打印、不判失败。`kill -9` 会丢掉 scene 审计队列与生产者缓冲里还没被确认的记录（停服才会写兜底日志，`architecture.md:199-205`），Redis 停摆时发不出号的记录只进兜底日志（同上），都不在库里 |
| DS10 在线存盘生效（可选 `--online-save every:N`） | 第 N 轮在线轮询导出包，截止 `2 × save-interval + 2 s`（理由同 DS7 下界）：`released = 0` 且已落盘钻石等于本轮值。它是 DS7 下界的依据 | 严格 | — |

### 2.7 分层

**L1 / L2：存储层**（对应基线 L1 / L2 的用途：隔离游戏逻辑，只测存储链）

- **`OwnershipFenceSoakTest`**（xm-player-store test，H2，缺省就跑）：
  - 模型随机测试：K 个模拟 scene 写者 × P 个玩家（缺省 8 × 64），固定种子列表，失败可用 `-Dxm.soak.seed=` 复现。
  - 随机交错 `claimOwnership`、`saveStateHeld`、`saveStateAndRelease`、`renewOwnerLeases`、`handOffOwnership`（5.2）、`releaseOwnership`，外加拨快注入时钟（`PlayerStore` 构造参数 `LongSupplier clockMs`，`PlayerStore.java:73`、`:81`）制造租约过期。
  - **写探针**：每次写在 `PlayerState` 里放一个未知字段（字段号 49999），内容是 (writer, epoch, seq)。protobuf-java 会原样保留未知字段，所以**不改 proto、不进生产代码**。
  - 每一步之后把库与模型对比，断言：
    - S1：库里的探针 = 最高 epoch 写者最后一次被接受的写；
    - S2：返回 false 的写不改动该行（写前写后比对）；
    - S3：`saved_epoch ≤ owner_epoch` 恒成立；释放之后，同一 epoch 的 held 写一律失败；
    - S4：只有「已释放」或「租约过期」时夺权才成功，epoch 严格递增；任一时刻至多一个可写的（epoch，未释放）持有者。
  - 这是基线 TC1–TC6（文档 `:98-113`，其中 TC6 `TestConcurrentWorkers_FinalStateIsGlobalMax`）在 Java 写模型下的对应物。
- **`OwnershipFenceConcurrencyIT`**（`-Dxm.it.mysql`）：真线程跑 30 s，64 个玩家、每人 4 个写线程，各持一份 epoch 视图，靠真行锁交错；静止后与各线程的「已接受写」日志对账。
- **`StorageFaultInjectionTest`**（xm-scene test）：用 JDK 动态代理包住 `DataSource` / `Connection`，不引入新库。注入两类故障，对应基线 TC5a、TC5b：
  - 「已提交但应答丢失」：代理的 `commit()` 先调真实提交、再抛 `java.sql.SQLRecoverableException`（与 Connector/J 通信失败同类）。`StoragePlayerRepository.isTransient` 沿 cause 链认它（`StoragePlayerRepository.java:337-350`），所以会走重试；断言内容不丢、不重复，结局被记成 fenced（§8.2 第 9 条）。抛别的异常类型（如裸 `RuntimeException`）测不到重试路径；
  - 「接管后才重试」：断言旧 epoch 的重试一定被拒。

**L3：`xm-robot data-stress`**（客户端可见面，流程见 §5.5）。

**L4：混沌**（§2.8）。

### 2.8 混沌矩阵

robot 只是客户端，不起停任何进程（`xm-robot/README.md:8`）。注入由外部脚本或 CI 完成：robot 用 `--chaos tolerate` 把中途断开、23 `{1003}`、`{3023}` 记成「中断轮」，下一轮改用 DS7；或者用两阶段 `--phase drive` / `--phase verify`。

| # | 注入 | 在哪跑 | 期望 |
|---|---|---|---|
| C1 | `kill -9` scene，随后重启（两个 scene 节点时幸存节点继续接客） | 本机：`kill -9 $(cat run/pids/xm-scene-2.pid)`，与 `stop-slice.sh:8-17` 的强杀同法；CI：`docker compose kill -s KILL xm-scene` → `start` | DS3 / DS4 / DS6（无僵尸）/ DS7；最后一次续约后 ≤ 30 s 能重登（先 2005）；与 5.5 `node-loss` 的路由断言互补（`scene-drain-spec.md:1310`） |
| C2 | `docker compose pause xm-scene` 40 s，其间 robot 重登接管，然后 unpause | 仅 CI（Windows 原生进程没有 SIGSTOP）；需要 `--profile two-scenes`，否则接管后无处落地 | fenced ≥ 1，或者 `xm_scene_owner_leases_lost_total` ≥ 1（续约发现归属已被夺走而移除实例；现在只有 WARN 日志 `OwnerLeaseRenewer.java:116`，没有指标，本批在 xm-scene 加这个无标签计数）；DS2 以新持有者为准；旧进程不能盖掉任何东西 |
| C3 | MySQL 暂停 10 s（CI：`docker compose pause mysql`；本机变体：停 MySQL 10 s 再起） | CI，`continue-on-error` + annotation | **当前预期失败**：这期间离场的玩家会丢最终写回（PARITY:57 待做）。保留严格判定，标为已知缺口（Q13）；「持续重试直到成功」做完后转为必过 |
| C4 | Redis 停 20 s | CI；本机可选 | gate 节点号租约续期失败超过 15 s，会关闭全部会话（`scene-drain-spec.md` K9，`GateNode.java:272-283`），各会话经链路让 scene 写回；DS2 严格；scene 恢复后无需重启即可重新接客 |
| C5 | `kill -9` gate | 本机 + CI | 链路断开时 scene 写回；DS2 严格 |
| C6 | `kill -9` login，时机在 EnterGame 期间 | CI；本机可选 | `abandonEnter` 或租约过期后，30 s 内无僵尸（DS6） |

currency-crash-window（`inventory/tools.md:470-480`，基线 `tools/scripts/currency_crash_window.ps1` 的 A / B / C / D 四个用例）由 C1 加 calm 覆盖，在 PARITY 单列一行（Q15）。

---

## 3 玩家数据导出 / 导入（player-data-debug-tools）

### 3.1 基线入口与模式

| 工具 / 模式 | 位置 | 读写什么 | 备注 |
|---|---|---|---|
| `debug_fetch -mode player` | `go/data_service/cmd/debug_fetch/main.go:99-140` | 不给字段时 SCAN `player:{id}:*` 再 MGET（`data_logic.go:57-90`），带 `__version` | 对真实玩家无效（H1） |
| `-mode zone` / `server`（别名，`:369-378`） | `:142-172`、`:205-228` | SCAN 映射库，每个键再 GET 一次，O(N)（`routing/router.go:437-470`） | 排序后截取 `-limit` 条（缺省 20，`:56`） |
| `-mode snapshot` | `:174-203` | data_service 自己的 source=0 快照 | 对真实玩家无效（data-ops-spec §3.1） |
| `-mode sql` | `:230-254`、`:537-559` | 任意「只读」SQL，用读写口令（`debugutil.go:81-87`） | 只看第一个词（H8） |
| `-mode player-db` | `:256-328` | information_schema 找列名为 `player_id / playerid / role_id / roleid / uid` 的表（`:267-272`），每表 `SELECT * … LIMIT limit`（`:299`） | 查询出错静默跳过（`:300-303`） |
| 值渲染 | `:400-439`、`:441-525` | 总带 `raw_base64`（`:408-409`）；按列名 / 字段名猜 proto 类型，解出就给 `proto_json` | 文档说「解不出才给 base64」（`online-debug-data-fetch.md:75`），与代码不符 |
| `debug_import`（Redis） | `go/data_service/cmd/debug_import/main.go:98-181` | 逐字段裸 `SET player:{id}:<f>`（`:153-158`），`__version` 重置为 1（`:160-164`），再 SETNX 登记 home zone（`:166-177`） | 结束提示「to local Redis」（`:179`），设计意图是把线上数据搬到本机复现 |
| `debug_import`（player-db） | `:185-287`、`:302-349` | 每表一个 READ COMMITTED 事务：`DELETE … WHERE match_col = ?`，再逐行 INSERT（`:318-343`） | RC 的成环分析在 `:351-370` |
| 公共参数 | `:47-79` | 缺省 dry-run（`:52`）；`-zone` / `-player` 覆盖导出文件里的值 | `-player` 的覆盖是坏的（H11） |

### 3.2 基线缺陷（mmorpg 待做候选）

| # | 现象 | 依据 | 后果 |
|---|---|---|---|
| H1 | player 模式和 Redis 导入读写的字段图不是 scene 的权威数据 | `data_logic.go:18-21`；scene 存的是 `<PlayerAllData>:{player_id}` 整份 blob（`asset_op_ledger_logic.go:24-27`） | 导出为空或陈旧；导入「成功」但对游戏没有影响 |
| H2 | player-db 导入只改 MySQL，不删也不覆盖那份 blob | `debug_import/main.go:302-349`；scene 从 Redis 加载，NIL 时才等落库路径回填（`redis_client.h:100-106`） | 缓存在时导入不生效，之后的存盘还会把它覆盖掉。属于推断，没有逐步跑通加载链 |
| H3 | 导出每表上限 `-limit`（缺省 20），导入却先删光该玩家的旧行；导出里只有 `rows_returned`，没有截断标记 | `debug_fetch/main.go:299`、`:308-313`；`debug_import/main.go:324` | 一张表超过 20 行时，导入**静默丢行** |
| H4 | 导入的数值先解成 float64 | `debug_import/main.go:417-425` | 2^53 以上的 BIGINT 失真。基线建角的 player_id「号段优先、可回退 snowflake」（`createplayerlogic.go:219-224`），雪花号会被插成另一个 id |
| H5 | Redis 导入先写字段、后登记映射 | `:153-158` 在 `:172-177` 之前 | SETNX 被拒时字段已写进去，留下孤儿数据 |
| H6 | Redis 导入不拿玩家锁，并把 `__version` 重置为 1 | 正常存盘走 Lua「锁令牌 + 版本比较」（`data_logic.go:157-178`） | 与在途存盘交错；之前读到版本 1 的陈旧写者可以 CAS 通过（ABA） |
| H7 | 导入不看在线、不碰 owner_epoch | 全文件没有任何在线或围栏检查（`inventory/data.md:381` 已核） | 在线玩家的进度被覆盖，或者导入被下一次存盘覆盖 |
| H8 | 只读 SQL 只看第一个词 | `debug_fetch/main.go:537-559` | `SELECT … FOR UPDATE`、`SELECT SLEEP(…)`、`SELECT … INTO OUTFILE`、`EXPLAIN ANALYZE` 都能通过；用的是读写口令。单独一个 `;` 会让 `strings.Fields(q)[0]` 越界 panic（`:549-552`） |
| H9 | player-db 导出静默跳过出错的表；建快照库失败也静默丢弃 | `:300-303`；`debugutil.go:146-158` | 「没查到」与「查了是空的」长得一样 |
| H10 | 改了 `proto_json` 不会被导入 | 导入只认 `raw_base64` / `text`（`debug_import/main.go:34-39`、`:105-114`、`:397-409`） | 手改导出文件的人以为改了数据，其实没有生效 |
| H11 | `-player` 覆盖只改了 DELETE 的条件，INSERT 的行仍带源 player_id | `:77-79`、`:302-343`（行没有重映射） | 「导到另一个 id」实际不可用 |
| H12 | 每张表一个事务 | `:273-279` | 跨表可能只导入一半 |
| H13 | 时间列按 RFC3339 导出；文档腐坏 | `debug_fetch/main.go:573-574`；`online-debug-data-fetch.md:7`、`:75` | 往返丢掉亚秒精度 |

基线测试（`debug_fetch/main_test.go`、`debug_import/main_test.go`）覆盖了白名单 / 黑名单、渲染、导入事务为 RC（`:166`）、标识符安全（`:288`）。**没有**覆盖 H3 / H4 / H5 / H11 和在线冲突。

### 3.3 Java 形态与不移植的部分

- **全部是 xm-data 运维接口**，外加 HTTP 薄客户端 `tools/PlayerData.java`（§5.8）。不做直连库的 CLI，原因：
  - 夺权、续约、`SCENE_GUID` 发号、作业审计都已经在 xm-data 进程里（data-ops-spec §2.3、§4.2），CLI 要么复制这一套，要么绕过它；
  - 库口令不必下发到运维机（AGENTS §3）。
- **不移植**：
  - 只读 SQL 模式（H8）。临时查询用 MySQL 客户端加只读账号，写进 runbook。
  - Redis 字段图模式：Java 没有 Redis 玩家数据存储，对应信息由导出包的 `live` 段提供。
  - 基线 snapshot 模式：7.2a 已有 `GET /admin/player-snapshots[/{id}?includeState=true]`。

### 3.4 导出包 `xm-player-export/1`

`GET /admin/players/{id}/export?reason=&related=true&redact=false`：同步只读，任何运行模式都可用，**必须带 reason**（个人数据访问审计）。

```json
{
  "format": "xm-player-export/1",
  "exportedAtMs": 0, "operator": "…", "reason": "…",
  "source": { "zoneId": 1, "build": { "version": "…", "commit": "…" }, "contract": "26ceb70ca" },
  "account": { "account": "…", "createdAtMs": 0, "hasPassword": false },
  "player": { "playerId": "…", "account": "…", "zoneId": 1, "name": "…", "classId": 1, "gender": 0,
              "appearanceId": "", "level": 1, "sceneConfigId": 1, "pos": { "x": 0.0, "y": 0.0, "z": 0.0 },
              "createdAtMs": 0, "updatedAtMs": 0 },
  "ownership": { "ownerEpoch": "…", "ownerReleased": true, "ownerLeaseUntilMs": 0 },
  "state": { "present": true, "savedEpoch": "…", "updatedAtMs": 0, "bytes": 1234, "sha256": "hex",
             "base64": "…", "json": { }, "unknownFields": [ ], "parseError": null },
  "live": { "online": false, "presence": { } , "location": null },
  "related": [ { "table": "friend", "column": "player_id", "rows": [ ], "truncated": false, "error": null } ],
  "warnings": [ ],
  "payloadSha256": "hex"
}
```

- **数据源**：`PersistedPlayerMapper` 新增 `findFull`，在原有 `find`（`PersistedPlayerMapper.java:14-20`）的基础上多取 account / name / class / gender / appearance。仍是一条语句，同一个读视图。
- **64 位整数一律输出十进制字符串**（修 H4），包括 `JsonFormat` 的 uint64（`StateJson.java` 头注释）。毫秒时刻小于 2^53，按数字输出。
- **`account.password_hash` 永不导出**，只给出 `hasPassword`。`redact=true` 时把 account / name 换成占位串，用于跨环境搬运。
- **`state`** 复用 7.2a 的 `StateJson.render`：`base64` 是权威内容，`json` 与 `unknownFields` 供人阅读；解析失败时给 `parseError` 和原字节，不省略。
- **`payloadSha256`**：对 xm-data 自有 proto `xm.data.PlayerExportPayload`（player 的可导入列 + state 原字节）做 deterministic 序列化后取 SHA-256。导入时用它判断文件「被手改过」。
- **在线语义**（同 data-ops-spec §3.4）：内容是已落盘状态，最多落后一个存盘周期。
  - `live.online` 来自在线目录。Redis 出错时 `presence` / `location` 写 `"unavailable"`，不省略。
  - 要此刻的精确状态，就先走 7.2 的 kick，或等玩家下线；不新增 scene 接口。
- **related**（只作诊断，**永不导入**）：
  - 静态登记表 `RelatedTables`：`friend(player_id, friend_player_id)`、`friend_request(from_player_id, to_player_id)`、`friend_capacity`、`friend_block(player_id, blocked_player_id)`、`guild_member`、`guild_application`、`guild_player_state`、`guild_player_op_seq`、`guild_asset_op`（最近 20 条，诊断资产通道卡单最常用）、`trade_listing(seller_player_id)`、`trade_favorite`，以及 `player_snapshot`（只取元数据，最近 20 条）。列名见 `friend_tables.proto:18-72`、`guild_tables.proto:53-205`、`trade_tables.proto:30-73`。
  - 每张表 `LIMIT max+1`，用多出来的那一行判 `truncated`（修 H3）；出错写进 `error`，不影响其他表（修 H9）。
  - 再扫一遍 information_schema：`xm_java` 里有列名形如 `%player_id`（含 `from_player_id`、`seller_player_id`、`blocked_player_id` 这类）、却既没登记也不在**显式忽略表**里的表，进 `warnings`，防止新表漏登记。显式忽略表：`transaction_log`（另有 `/admin/transaction-log`）、`ops_job_player`、`recall_source`、`guild_daily_counter`，以及 pbmysql 测试表；忽略表同样是代码常量，单测钉住「生产建表脚本 + 各服务 proto 声明的全部表都被登记或忽略」。`guild_player_state` 里 `player_id = 0` 的全局插入守卫哨兵行（`guild_tables.proto:52`）按号查询天然取不到，任何按表扫的检查都要排除它。
  - 组队在 Redis，v1 不导出。

### 3.5 按区列玩家

`GET /admin/players?zone=&after=&limit=`：对应 debug_fetch 的 zone / server 模式。

- 按 `player_id` keyset 分页，`limit ≤ 1000`，回 `hasMore`，不做 `COUNT(*)`。
- 依赖 7.2b 的 `idx_player_zone`。没有这个索引时按主键全扫，并只在 dev 运行模式开放（否则回 409 `index_missing`）。

### 3.6 导入：受理闸门与校验

`POST /admin/player-imports`，必带 `Idempotency-Key` 头。请求体：

```json
{ "player": "…", "bundle": { }, "dryRun": true, "ifOnline": "reject|kick",
  "createIfAbsent": false, "targetAccount": null, "targetName": null, "targetZone": null,
  "sections": [], "acceptEdited": false, "acceptDivergence": false, "reason": "…" }
```

**受理闸门**（在 Tomcat 线程上同步执行；任何一道不过，零变更）：

1. `xm.run-mode`（xm-data 新增，复用 `com.game.common.RunMode`，同 xm-trade 播种口径 `TradeConfiguration.java:173-177`）必须是 DEV 或 TEST，否则 403 `import_forbidden`。未设、写错一律按 PROD（`RunMode.java:18-21`、`:31-40`），写错时启动打 WARN（同 xm-trade）。
2. `xm.data.ops.enabled=true`，否则 503 `ops_disabled`。
3. `reason`、操作人、幂等键齐全，否则 400。`dryRun=true` 也要带幂等键（与 `/admin/rollbacks` 同口径），但 dry-run 不写作业行，键只校验格式。
   - 请求体上限 `xm.data.import.max-request-bytes`（缺省 4 MiB：1 000 000 字节状态的 base64 + json 两份再加余量），按 `Content-Length` / 读取计数超出即 413 `request_too_large`，不把整个大包读进内存再判。
4. **包校验**：
   - `format` 必须认识。
   - `bundle.player.playerId == player`，否则 400 `player_mismatch`。v1 不支持改号克隆（Q19）。
   - 重算 `payloadSha256`，不符且 `acceptEdited=false` 时 400 `bundle_edited`。
   - **状态只取一处**（修 H10）：有 `state.base64` 就用它；只有 `state.json` 时用 `JsonFormat.parser()` 严格解析（缺省就拒绝不认识的 JSON 字段）；两者都在时**按消息比较**：`parseFrom(base64)` 去掉未知字段后与 `parse(json)` 做 `Message.equals`，不等才 400 `state_conflict`。不能比较重新编码后的字节：map 字段的序列化顺序没有保证（同 §8.2 第 10 条），逐字节比较会误报；JSON 本来表达不了未知字段，所以比较前要去掉。只有 json、而 `unknownFields` 非空时，400 `unknown_fields_would_be_lost`。
   - `PlayerState` 严格解析；`PersistedAssetLedger` 加载校验，坏账本拒绝（400 `ledger_invalid`）；包内 `item_uuid` / `pet_id` 不得重复（400 `duplicate_instance_id`）。
   - state 字节数 ≤ `xm.data.import.max-state-bytes`（缺省 1000000，与快照上限相同，`architecture.md:209-210`），否则 400 `bundle_too_large`。
5. **目标**：
   - 玩家不存在且 `createIfAbsent=false` → 404 `player_not_found`。
   - `createIfAbsent=true` 时：`targetZone`（缺省取包里的值）必须在 `zone_config` 里有行，否则 400 `zone_not_found`；`targetAccount`（缺省取包里的值；`redact` 过的包必须显式给）必须匹配开发账号前缀白名单 `xm.data.import.dev-account-prefixes`（缺省 `robot_`、`dev_`，须与 xm-login 的 `xm.login.dev-account-prefixes` 一致，`LoginProperties.java:45-47`），否则 400 `account_not_dev`；名字（`targetName` 或包内名字）只做列约束校验（非空、≤ 64 字符、不含控制字符），不合法 400 `name_invalid`；**不**套 xm-login 的表驱动名字规则（`PlayerNames.normalize` + `RoleNameRules`，`CreatePlayerHandler.java:162-172`）——它要配置表，xm-data 不加载配置表；导入只在 dev / test 开放，源名字在源环境已经校验过一次。
6. `dryRun=true`（缺省）：同步返回计划，不夺权、不写任何东西。计划包括：
   - 复用 `SnapshotDiffService` 的结构化差异（「快照一侧」换成导入包）；
   - 账本差集（`LedgerDiff`）；
   - `online` / `ownerReleased`；
   - 将要建的行与名字冲突。
7. 否则：一个事务内占单飞槽 + 插 `ops_job`（kind `OPS_JOB_IMPORT = 7`）+ STARTED 事件 → 202 `{jobId}`（同 data-ops-spec §4.7 的受理口径）。

### 3.7 导入：执行（7.2b 作业框架，`data-ops` 线程）

1. **建行（只在 `createIfAbsent` 且目标不存在时）**：xm-player-store 新增 `insertImportedPlayer`，它只是一个 `@Transactional` 组合，**不新增写 SQL**：`insertAccountIfAbsent`（`PlayerMapper.java:16-17`，`password_hash` 为 NULL，dev 口令按前缀白名单登录不受影响）→ 同一事务里走 `createPlayerWithinCap` 的锁序（`PlayerStore.java:191-210`：锁账号行 → 数角色 → 插入，`architecture.md:645-648`）。
   - 插入前同一事务里先 `selectById`：该 id 已存在且属于别的账号 → 409 `player_conflict`，不静默改绑；属于同一账号 → 按「目标已存在」处理（并发建同号只可能来自另一个导入，已被单飞槽挡住）。不能靠插入撞主键来判：`createPlayer` 把主键重复当成发号不变量被破坏、直接抛 `IllegalStateException`（`PlayerStore.java:132-147`）。
   - 账号已满 → 409 `account_full`；名字撞上 `uk_player_name_key` → 409 `name_conflict`。
   - 插入的行：`name = targetName ?: 包内名字`、`zone_id = targetZone`、职业 / 性别 / 外观取包内值，`owner_epoch = 0`、`owner_released = 1`（列缺省，`xm-player-schema.sql` 的 player 表）。
   - 记 PLANNED 事件（`created=true`）。
   - **建行与第 5 步的写事务分属两个事务**：之后任何一步失败，都会留下一个没有 `player_state` 的空号。用新幂等键重提时它按「目标已存在」处理，三道分歧检查面对的是空账本，自然干净；结果里写明 `created_in_previous_job`。
   - 这扩大了「谁能建 player 行」：以前只有 xm-login 的建角，现在 dev / test 下 xm-data 也能经同一个 `PlayerStore` 方法建同号行。要写进 `architecture.md` §7（§5.10）。
2. **素材落成快照**：导入包插成一行 `player_snapshot`：
   - cause `SNAPSHOT_GM_IMPORT = 1003`（Java 独有；当前最大值是 1002，`player_snapshot.proto:31-33`；其他并行批次的规格没有占用 1003，实现时再核对一次）；
   - `operator` 照写，`note = job:<id> import exported=<exportedAtMs>`，`time_ms = bundle.state.updatedAtMs`（内容时刻，同 data-ops-spec §3.2 的语义）；`zone_id` 取目标 `player.zone_id`；`owner_epoch` 写 0（外部内容不对应本环境的任何写者，不能冒用包里源环境的 `savedEpoch`）。
   - 快照号钉进 `ops_job_player`（PLANNED）。与 PRE_* 一样，**不进按时刻选源的白名单**，只能显式按号使用（data-ops-spec §3.3）。
   - 这一行在夺权之前写、单独提交：作业之后被拒（`player_online` / 分歧）时它留下，作为这次尝试的素材，按 `gm-snapshot` 保留期清理。data-ops-spec §3.7 的保留期是按原因清单分的（`player-snapshot` 只清 LOGIN / LOGOUT / PERIODIC），GM_IMPORT 必须显式加进 `gm-snapshot` 那一类的清单，并有单测钉住；否则它要么永不清理、要么将来被误归到短保留期里。
3. **夺权**：`AdminOwnership`。`reject` 时回 `player_online`；`kick` 时走顶号通路，被踢的人收 23 `{2017}`；`claim-wait` 内仍夺不到记 `player_busy`。
4. **资产分歧检查**：
   - 目标原先已存在：data-ops-spec §4.6 的三道检查照跑，`since = 快照 time_ms − 余量`，有分歧时缺省 REJECTED，`acceptDivergence` + reason 才放行。
   - 本作业新建的目标：跳过，在 ACCEPTED 事件里记 `created_by_import`。
5. **单玩家写事务**（与 data-ops-spec §4.8 同形，共用 `RollbackExecutor` 的写路径）：
   1. `selectOwnerForUpdate`，确认持有 (E', 未释放)。
   2. 安全快照：cause `SNAPSHOT_PRE_GM_EDIT = 1002`，note `job:<id> import`。
   3. 按 `RestoreComposer` 拼出新状态：FULL 或 SECTIONS，规则同 data-ops-spec §4.5——`assets` 不可拆，`mission` 必须与 `assets` 同选，`currency.blocked_types` 保留当前值。`RestoreComposer` 是从 `RollbackExecutor` 拆出的纯函数，回档与导入只此一份。
   4. 带围栏覆盖写 `saveStateHeld`：`player` 行只恢复 level / `scene_config_id` / 坐标。包里的 `owner_*`、`account`、`name`、`name_key`、`zone_id` 一律忽略。
   5. 流水，原因用新增的 `TX_GM_IMPORT = 1006`。Java 独有段（1001 起）在 `transaction_log.proto:26-31` 用到 1004，**1005 已由 6.3 规格占给 `TX_BATTLE_REWARD`**（`scene-battle-spec.md:531`、D23），所以取 1006；哪个批次先落地都不改对方的值，落地时再核对一次空号。按 data-ops-spec §4.10 的行形状，每个变化的币种、每个变化的物品实例各一行。
   6. 明细 `UPDATE ops_job_player SET outcome='IMPORTED' …`，必须恰好 1 行。
   7. 提交。
6. **写后复查**（只在第 4 步跑过帮会检查时）→ RESULT 事件 → 位置墓碑 → 释放。
- **撤销**：对同一玩家以 `snapshotId = PRE_GM_EDIT 快照号` 回档一次（data-ops-spec §4.11），照样过全部闸。
- **生产上怎么恢复**：先 `POST /admin/player-snapshots`（GM_MANUAL，7.2a）拍快照，需要时 `POST /admin/rollbacks {snapshotId}`（7.2b）。**外部字节永远不进生产库**（D3）。

### 3.8 跨环境导入的已知局限（写进接口文档）

- `asset_ledger` 的水位来自源环境。目标环境 `guild_player_op_seq` 发的 seq 可能落在窗口外，帮会 / 交易指令会被 scene 拒成 UNKNOWN（`architecture.md:337`）。这只影响导入号在目标环境里玩帮会经济；dry-run 会逐流列出水位。
- `item_uuid` / `pet_id` 是源环境的雪花号。同一环境同号导入时，导出之后被转给别人的物品会在两边同时存在；这正是账本差集要拦的情形（交易流 3 / 4 随 4.8 自动覆盖，data-ops-spec §4.6.2），目标已存在时三道检查照跑，所以不会静默复制。跨环境撞号的概率可以忽略。v1 不重发号（Q25）。
- 6.3 落地后 `player_state.battle_ledger`（字段 9，`scene-battle-spec.md:53`）必须并入 `assets` 段组，作为 6.3 自己的验收项。

---

## 4 压测机器人 AI（robot-stress-ai）

### 4.1 基线生命周期（`robot/main.go`）

- **stress 分支**：每 50 ms 起一个机器人（`:222-236`），之后阻塞到 SIGINT / SIGTERM（`:238-240`）；退出时导出 `behavior_test_results.{csv,jsonl}`（`:246-247`）。**没有时长参数，退出码恒为 0**（`docs/reference/mmorpg-client-contract-robot.md:433-436`）。
- **单个机器人**：失败重试 5 次，退避 3 s → 30 s（`main.go:253`、`:262`、`:285`）；gate 令牌过期另计，间隔 200 ms，最多 20 次（`:261`、`:311`）。HTTP assign-gate 另有一层重试：至多 30 次，间隔 2 s 起 ×1.5、封顶 10 s（`:522-533`）。
- **进场之后**：等 79 最长 60 s（`:428-433`）→ 发 77 ListSkills，等 5 s（`:436-443`）→ 套上档位与可选 LLM（`:446-461`）→ 阻塞到收包循环结束或停止 → 尽力发 17（`:469-470`）→ 延迟执行的尽力发 58 Disconnect（`:355`；`robot/login.go:278-287`，只在 `PlayerId != 0` 时发）→ 关连接。整个顺序是「17 → 58 → 立即关 TCP，都不等应答」（`docs/reference/mmorpg-client-contract-robot.md:26`）。
- **断线**：服务端断开后，muduo 1 s 后自动重连同一个 gate，而且**不重新握手**；收包循环感知不到断线，机器人在未验证的连接上空转（`mmorpg-client-contract-robot.md:464-469`）。

### 4.2 基线决策与档位（`robot/logic/ai/robot_ai.go`、`action.go`）

- **缺省**：档位 stress（`robot_ai.go:34`），间隔 3 s（`:39`；`robot/etc/robot.yaml:12`）。首拍前随机抖动 0–2 s（`:51-52`）。用 `time.Ticker`，动作慢时会丢拍（`:54-63`）。
- **抽样**：加权抽样（`action.go:49-66`）遍历 Go map，顺序随机，但分布无偏；总权重为 0 时回 idle。
- **档位解析**（`config.go:479-494`）：`custom_weights` 优先，**不认识的动作名静默丢弃**；档名不认识时返回 nil，回落到缺省 stress，**也是静默的**。

| 档位（`action.go:68-118`） | cast_skill | move | switch_scene | idle | chat | 用在哪 |
|---|---|---|---|---|---|---|
| fighter | 60 | 20 | 10 | 5 | 5 | 没有入库配置 |
| explorer | 15 | 40 | 25 | 15 | 5 | 没有 |
| chatter | 10 | 20 | 10 | 25 | 35 | 没有 |
| behavioral | 45 | 25 | 20 | 5 | 5 | `robot/etc/robot.behavioral.yaml`；`login_test_scenarios.go:1222` |
| **stress** | 85 | 0 | 15 | 0 | 0 | `robot.yaml:29`、`robot_smoke.yaml:15`、`robot.stress-*.yaml`、`robot.smoke-5k-z1.yaml`、`robot.e2e-http.yaml`、`robot.currency-crash.yaml`、`robot.data_stress.yaml:23`；档名缺省时也是它（`robot_ai.go:34`） |

### 4.3 基线动作实际发什么

| 动作 | 实际发送 | 依据 | 问题 |
|---|---|---|---|
| cast_skill | 84 `{skill_table_id ∈ 77 返回的已拥有技能, target_id = 随机已知实体（含自己）, position = (本地 x ±10, 0, 本地 z ±10)}` | `robot_ai.go:131-174` | 本地坐标从 0 开始，只被假 move 改动，与服务端位置无关；而且把 X / Z 当水平面、Y 填 0，可服务器是 z-up（水平面 x / y，`docs/reference/mmorpg-client-contract-movement.md:26`），等于在 y = 0 的竖直面上乱点；发出即记成功（`:171-173`） |
| move | **43 SceneInfoC2S（空请求）**，只改本地坐标 | `robot_ai.go:176-193`（注释自称「report new position (if the server supports it)」） | 从不发 134 / 132 / 131，压不到移动广播与视野；服务端只回推一条 31 |
| switch_scene | 63，只带**当前**的 `scene_config_id` | `robot_ai.go:195-218` | 基线会在同图的频道间跳（`scene-channels-spec.md` §2.6 第 3 条）；发出即记成功 |
| chat | 61 `{message{content="[robot] …"}}`，**不填频道，不填 request_id** | `robot_ai.go:220-240` | 频道 0 = UNSPECIFIED，基线一律回 channel_unavailable（`go/chat/internal/logic/chat_logic.go:323-327`），Java 也一样（`ChatService.java:102-104`）；日志仍记成功 |
| idle | 什么也不发 | `:80-81` | — |

### 4.4 基线 LLM 决策

- `LLMAdvisor` 调 OpenAI 兼容的 `/v1/chat/completions`（`robot/logic/ai/llm.go:16-37`）。缺省端点是本机 Ollama `http://localhost:11434/v1/chat/completions`，模型 `qwen2`（`robot/etc/robot.yaml:42-46`）。
- 密钥 `api_key` 写在 YAML 里，按 `Authorization: Bearer` 发出（`config.go:442-447`；`llm.go:93-95`）。
- 提示里带 `GameState{player_id, pos_x, pos_z, scene_id, scene_config_id, skill_ids, tick_count}`（`llm.go:40-59`），`max_tokens = 32`、`temperature = 0.7`（`:70-78`）。
- 同步调用，HTTP 超时 5 s（`:35`、`:85`），比 3 s 的节拍长，慢的时候会挡住该机器人的循环。出错回 idle（`:61-66`），在回复里找第一个能认出的动作词（`:122-129`）。
- 提示里**没有**可见实体、血量、冷却，决策价值近似随机。
- 所有入库配置里都是 `llm.enabled: false`；注释自述只用于 1–10 个机器人的行为建模（`llm.go:21-22`；`config.go:94-96`）。没有单测（`robot/logic/ai/` 下没有 `_test.go`）。
- **外部 API 风险**：开启后会把玩家号与坐标发往配置的端点（可以是公网），密钥明文落在配置文件里。

### 4.5 基线统计与产物

- 统计行（`robot/metrics/stats.go:188-206`）被 `tools/scripts/stress_summarize.ps1:161` 用正则解析：
  - `elapsed` 是 Go 的 `time.Duration` 文本，例如 `5m0s`；
  - 时延是 `Truncate(ms)` 之后的 Duration 文本，例如 `151ms`、`1.234s`；
  - `skill`、`scene_switch` 是「发出数」（契约 §6.2）。
- 行为记录无上限地追加进内存切片，并且共用一把全局锁（`stats.go:252-256`），退出时才落盘。`LatencyMs` 是「开始到发出」的耗时，不是服务端时延（`robot_ai.go:111-129`）。

### 4.6 Java：档位

- `fighter`、`explorer`、`chatter`、`behavioral`、`stress` 与 Go **同名同值**（`action.go:68-118`）。
- Java 专有 `stress-aoi`：cast_skill 50、move 40、switch_scene 10。用来压出生点视野，即 PARITY「视野」行与 `inventory/tools.md:516` 指出的 Java 帧预算瓶颈。
- `--weights cast_skill=50,move=30,…` 自定义。**严格解析**：不认识的档名或动作名、权重为负、总和为 0，都退出码 2（R5）。
- `ActionPolicy` 接口。v1 实现 `WeightedPolicy(profile, new SplittableRandom(seed ^ robotIndex))`，同一 seed 可复现（R7）；7.4e 加 `LlmPolicy`。

### 4.7 Java：动作语义与结局对账

| 动作 | 发送 | 结局对账 |
|---|---|---|
| cast_skill | 84：从已拥有技能（77）里取；目标从 `RobotView` 的已知实体加自己里随机取；`position` = 服务端位置（z-up，水平面 x / y） | 应答无错计 ok；有错按 tip 分类计数，允许的集合是 {7000, 7001, 7002, 7003, 7004}（`xm-table/src/main/proto/tip/skill_error_tip.proto:12-20`）；技能不存在 / 未拥有回的是通用 `kInvalidTableId`（`SkillService.java:69-72`），机器人只从 77 里取，出现即计 unexpected；超时计 timeout；收到 70 / 33 分别计数 |
| move | 134 Start → 132 Sync 1–3 条（每 250 ms）→ 131 Stop。速度 ≤ 5 m/s，单次行走 ≤ 2 s，目标点限制在出生点 ±15 m 内（只改 x / y，z 取服务端当前值），让人群互相可见（视野半径 10 m） | 不回包；收到 137 计 `move_corrected`。Java 有 `MoveGuard`，正常速度下不该触发；触发说明机器人的节奏有问题 |
| switch_scene | `--switch-target`：`same-map`（缺省，只带当前 `scene_config_id`，同基线）/ `other-map`（从 World 表另选一张图，读 `--table-dir`）/ `cross-node`（63 `{scene_id}`，取别的机器人在 79 里见过的同图另一个 scene_id；只在「每张世界图每个节点恰好一个频道」时才必在另一节点、触发 5.2 交出——`CrossNodeScenario` 同一推断，类注释；扩容出第二个本节点频道后这个推断不成立；客户端看不出跳转是否跨节点，是否真的走了交出由收尾时抓的 `xm_scene_transfers_total` 增量佐证。一个别的 scene_id 都没见过时本拍记 `switch_skipped_no_target`、不发） | 应答 tip，允许 {0, 3008, 3014, 3023}；之后到来的新 79 计 `scene_hop`，并重置视野。Java 的 same-map 在本节点内选人最少的频道，并列时留原地（`SceneWorld.java:568-574`），所以单频道时是空操作 |
| chat | 61 `{channel = WORLD, content = "[robot] " + 基线同一组 9 句, request_id = <tag>-<idx>-<seq>}`；`--chat off` 时当作 idle | 应答体 tip 1008 计 `chat_rate_limited`（发言人每秒 5 条），其余非 0 tip 计 unexpected。世界聊天不推送（`ChatService.java:42`），压力在 Redis |
| idle | 不发 | — |

- **gate 限频**：启动时读 `--table-dir` 下的 MessageLimiter，打印每个动作用到的消息号及其上限；按 `--action-interval-ms` 与每个动作一拍内发几条，算出会超限的组合就退出码 2（例如间隔 < 334 ms 时 84 / 63 超过缺省每秒 3 条），`--allow-rate-limit` 才放行（专门压限频时用）。运行中凡是收到**信封** `error_message=1008`（`ClientDispatcher.java:237-239`）都计 `rate_limited`，作为闸门失败项：机器人不该自己撞上限频。注意超频、超长、运行模式拒绝的 GM 指令都计 gate 的「非法包」，累计 50 个就断开连接（`GateProperties.java:18`、`:45`）——持续撞限频的机器人会表现成 `dropped`。
- **`RobotView`**：只在本机器人线程上维护。
  - 自身实体号与位置：来自 21 / 137；
  - 场景号与配置号：来自 79；
  - 已知实体：21 / 47 加入、64 / 51 移除。
- **待决表**：按 `(message_id, 请求号)` 对账，`GameConnection.send` 返回请求号（`GameConnection.java:137`）。

### 4.8 Java：生命周期、断线、收尾与闸门

- **每个机器人一条虚拟线程**（同 `SmokeScenario.java:57`），跑完整生命周期：
  - assign-gate → TCP → 握手 → 48 → 必要时 14 → 26（撞 2005 就退避）→ 等 79（60 s）→ 77（5 s）→ 决策循环。
  - assign-gate：开服限流（100 + `queue_source="ratelimit"`、429 `IP_RATE_LIMIT`）沿用 `AssignGateClient` 按 `retry_after_ms` 退避（`AssignGateClient.java:23`、`:96-101`）；**登录排队**（100 + `queue_source="login"` + `queue_token`，区服在线达到 `zone_config.capacity` 时出现，`AssignGateResponse.java:16-17`、`:79-80`）按 `retry_after_ms` 轮询 `/api/queue-status`，与 Go 一样计 `q_entered` / `q_admitted` / `q_expired` / `q_avg_wait` / `q_max_rank`。总截止 `--assign-deadline`（缺省 5 min，量级同 Go 的 30 次重试）。`--count` 超过区服容量（缺省 5000，`xm-gateway-schema.sql:9`）时要么先经 `/admin/zones` 调大 capacity，要么接受排队并把排队时间计进闸门的爬坡窗口。
  - 首拍抖动 0–2 s；节拍用 `sleep` 到下一个时刻，慢了不丢拍，而是记 `tick_late`。
- **`StreamingInbox`**（新增，`com.game.robot.client`）：
  - 现有 `Inbox` 全量保留每一条下行（`Inbox.java:22-31`），而且在 `synchronized` 里 `timedWait`（`:61-80`）。在 JDK 21 上这会把虚拟线程钉在载体线程上（JEP 491 要到 JDK 24 才解决；JDK 21 对 `Object.wait` 的补偿是临时扩充载体线程，上限 256，几千个等待者照样耗尽），长时压测里内存也会无限增长。
  - 新实现用 `ReentrantLock` + `Condition`：应答进单独的小队列，从不丢；推送进有界队列（缺省 4096），满了丢最旧的并计 `inbox_dropped`。
  - I/O 线程只入队，机器人线程每拍排空。现有断言类场景继续用旧 `Inbox`。
- **Netty I/O 组大小**：`--io-threads`，缺省等于 CPU 核数，替代写死的 2（`RobotClient.java:31`）。
- **断线**：一律完整重登（新 assign-gate、新 TCP、新握手），最多 5 次，退避 3 s → 30 s（同 Go 的数值）；gate 令牌过期最多重取 20 次，间隔 200 ms。计 `dropped` / `relogin`；收到 23 `{2017}` 计 `kicked`。不复刻 muduo「无握手自动重连后空转」（R6）。
- **收尾**：到 `--duration` 或收到 SIGINT（shutdown hook）→ 停止决策 → 尽力发 17、再尽力发 58（同 Go 的顺序，`main.go:355`、`:469-470`；Java 的 58 只记日志、不回包，`DisconnectHandler.java:16`）→ 关闭连接 → 最多等 10 s → 打印汇总。
- **闸门**（全部可配；退出码即结论，`--report-only` 时恒为 0）：
  - 爬坡结束后再给 `--settle-enter`（缺省 60 s，覆盖 79 的 60 s 等待与 2005 退避；有登录排队时另加排队耗时）时已进场比例 ≥ 99%；
  - `login_fail + enter_fail` ≤ 1% × count；
  - `dropped` ≤ 1%；
  - 各动作 timeout ≤ 1%；
  - `rate_limited` = 0；
  - 意外 tip = 0。

### 4.9 Java：LLM 策略（7.4e，可选）

- `LlmPolicy implements ActionPolicy`。只用 JDK `HttpClient` + Jackson（xm-robot 已经在用），**不新增依赖**。
- **端点**：`--llm-endpoint` 必须显式给出，**没有缺省值**；`--llm-model` 同理。密钥只读环境变量 `XM_ROBOT_LLM_API_KEY`，可以不设。
- **规模**：`--count > 10` 时拒绝启动（退出 2），同基线的定位（`llm.go:21-22`）。
- **异步**：在单独的虚拟线程上提前一拍请求决策，超时 5 s，不挡节拍。没有就绪或出错时回 idle（同基线 `llm.go:61-66`），计 `llm_fallback`。
- **提示词**：动作词表与基线相同。`GameState` **去掉 player_id**，加入 `RobotView` 摘要（可见实体数、最近一次 tip）。
- **应答处理**：只认 `http` / `https` 端点；应答体读到 64 KiB 为止，超出按出错回 idle；解析与基线相同（取 `choices[0].message.content` 里第一个认得的动作词，`llm.go:122-129`），不执行应答里的任何其他内容。端点不是回环地址时启动打印一行警告（提示词会发往外部）。

---

## 5 Java 设计（落地）

### 5.1 形态分工规则

| 形态 | 什么时候用 | 先例 | 本批用在 |
|---|---|---|---|
| `tools/*.java`（JDK 21 单文件） | 只用 JDK（HttpClient、MessageDigest），不碰库、Redis 和生成类；作为运维接口的薄客户端，负责退出码、轮询作业、落文件 | `tools/GmShutdown.java`、`tools/TestReport.java` | `ConsistencyCheck.java`、`PlayerData.java` |
| 模块内 CLI（PropertiesLauncher） | 必须直连库或读本机文件，而且目标服务可能没在跑 | `xm-data/.../tools/AuditFallbackReplay.java` | **本批不新增** |
| xm-data `/admin/**` | 读写生产数据：令牌 + 操作人 + `xm.audit.admin` + 低基数指标；写操作再加 `ops.enabled`、`Idempotency-Key`、作业框架 | `architecture.md:219-222`；data-ops-spec §7.4、§7.6 | 巡检、导出、按区列玩家、导入 |
| xm-robot 子命令 | 只做客户端协议加读运维接口，不起停进程，退出码即结论 | 现有 24 个场景（工作区 `RobotOptions.java:70-78`；5.5 的 `evacuate` 等还没加） | `stress`、`data-stress`、`player-data` |
| 模块单测 / IT | 不跑起整套进程就能验证的不变量 | `PlayerStoreSqlTest` | `OwnershipFenceSoakTest` 等 |

### 5.2 模块、包与依赖

| 模块 | 新增 | 说明 |
|---|---|---|
| xm-data | `com.game.data.consistency`：`ConsistencyCheck`（接口：`id()`、`subBatch()`、`deep()`、`run(CheckContext) → CheckResult`）、`CheckContext`（zone 集合、dbNow、预算、复核器、样本器）、`ConsistencyRunner`、`ConsistencyScheduler`、`ConsistencyLock`、`ConsistencyReportStore`、`LiveZones`、`SchemaGuard`、`DbClock`、`ReportMarkdown`；`checks/` 下每项一个类 | 依赖已有的 xm-gateway-store（`zone_config`）、xm-player-store（`nameKey`、`PersistedAssetLedger`、`PlayerState`）、xm-discovery（`RedisKeys`、presence），**不依赖** xm-friend / xm-guild / xm-trade（胖 jar）；`assetop.seq_watermark` 读 `guild_player_op_seq` 同样走按列名的只读 SQL |
| xm-data | `com.game.data.player`：`PlayerExportService`、`ExportBundle`、`RelatedTables`、`PlayerListService`、`PlayerImportService`（受理 / 校验 / 计划）；`com.game.data.rollback.RestoreComposer`（从 `RollbackExecutor` 拆出，回档与导入共用） | `PersistedPlayerMapper` 增加 `findFull`、`listByZone`；新增只读 `ConsistencyMapper` |
| xm-data | proto `xm.data.PlayerExportPayload`；`ops_tables.proto` 增加 `OPS_JOB_IMPORT = 7` | 只增不改 |
| xm-data admin | `ConsistencyAdminController`、`PlayerExportController`（挂在 `/admin/players` 前缀下）、`PlayerImportController`；`AdminAuthFilter.opOf`（`AdminAuthFilter.java:83`）增加前缀 `consistency`、`player_imports` | 路径里的 id 不进标签 |
| xm-audit | `SnapshotCause += SNAPSHOT_GM_IMPORT = 1003`；`TransactionReason += TX_GM_IMPORT = 1006`（1005 归 6.3 的 `TX_BATTLE_REWARD`） | 只增不改；改完 `clean install`（AGENTS §4） |
| xm-player-store | `PlayerStore.insertImportedPlayer`（现有 Mapper 方法的事务组合）；test `OwnershipFenceSoakTest`、`OwnershipFenceConcurrencyIT` | **不新增写 SQL**、不改表 |
| xm-scene | `SceneMetrics` 新增无标签计数 `xm.scene.owner.leases.lost`（`OwnerLeaseRenewer` 发现归属已不在本节点、移除实例时累加，`OwnerLeaseRenewer.java:113-117`）；test `StorageFaultInjectionTest` | 只加指标，不改行为 |
| xm-robot | `com.game.robot.stress`（`StressScenario`、`RobotBrain`、`Action`、`Profile`、`ActionPolicy`、`WeightedPolicy`、`LlmPolicy`、`RobotView`、`StressStats`、`GoStatsLine`、`Gates`）；`com.game.robot.datastress`（`DataStressScenario`、`StressLedger`、`Verdicts`（纯函数）、`ChaosWindow`）；`scenario/PlayerDataScenario`；`client/StreamingInbox` | `RobotOptions.Scenario` 增加 STRESS / DATA_STRESS / PLAYER_DATA |
| tools | `ConsistencyCheck.java`、`PlayerData.java`；`tools/local/data-stress.sh`（本机混沌编排） | — |

### 5.3 HTTP 接口总表（全部在 `/admin/**` 下，过滤器统一鉴权）

| 方法 | 路径 | 形态 | 门槛 | op 标签 | 子批 |
|---|---|---|---|---|---|
| POST | `/admin/consistency/runs` `{checks?, zones?, deep?, reason}` | 异步，202 `{runId}`；409 `consistency_busy`；503 `redis_unavailable` | 令牌 + 操作人 + reason；只读，不受 `ops.enabled` 约束，不要幂等键 | `consistency` | 7.4a |
| GET | `/admin/consistency/runs/{runId}`、`/admin/consistency/runs/last`（`?format=md`） | 同步 | 同上，不要 reason | `consistency` | 7.4a |
| GET | `/admin/consistency/checks` | 同步：检查目录 | 同上 | `consistency` | 7.4a |
| POST | `/admin/consistency/players` `{players ≤ 200}` | 同步只读批量查询。回 `dbNowMs` 和每人的 `{exists, zoneId, ownerEpoch, ownerReleased, ownerLeaseUntilMs, savedEpoch, stateUpdatedAtMs, stateBytes, stateSha256, presence \| "unavailable", location \| "unavailable", verdicts[own.*]}` | 同上 | `consistency` | 7.4b |
| GET | `/admin/players/{id}/export` | 同步只读 | reason 必填；任何运行模式 | `players` | 7.4b |
| GET | `/admin/players?zone=&after=&limit=` | 同步只读 | 无索引时只在 dev | `players` | 7.4b |
| POST | `/admin/player-imports` | 作业；`dryRun=true` 时同步 | run-mode ∈ {DEV, TEST} + `ops.enabled` + `Idempotency-Key` + reason | `player_imports` | 7.4c |
| GET / POST | `/admin/ops-jobs/{id}[/players\|/cancel]` | 7.2b 已有 | — | `ops_jobs` | — |

结果码沿用 data-ops-spec §7.7。新增：`consistency_busy`(409)、`redis_unavailable`(503)、`import_forbidden`(403)、`player_mismatch` / `bundle_edited` / `state_conflict` / `unknown_fields_would_be_lost` / `ledger_invalid` / `duplicate_instance_id` / `bundle_too_large` / `zone_not_found` / `account_not_dev` / `name_invalid`(400)、`request_too_large`(413)、`name_conflict` / `player_conflict` / `account_full` / `index_missing`(409)。`name_conflict` / `player_conflict` / `account_full` 在 dry-run 与受理预检时就能查出、回 409；预检之后、执行建行时才撞上的（竞态）记作业 REJECTED 同码。作业内的其他结果（`player_online`、`player_busy`、分歧）沿用 data-ops-spec 的码。

### 5.4 线程、事务与连接

- **巡检**：在 `data-consistency-run` 单线程执行器上执行，每项检查一个只读 RR 事务（§1.7）；定时触发、续锁、刷新 gauge 在 `data-consistency-lock` 单线程调度器上（§1.8）。受理在 Tomcat 线程上只做校验、拿锁（一次 Redis `SET NX`，受客户端超时约束）、投递；SQL 一律不在 Tomcat 线程上跑。
- **导出、按区列玩家、`/consistency/players`**：在 Tomcat 线程上同步执行，单条语句或短只读事务。Redis 读带客户端超时（`architecture.md` §6：最坏 4.2 s）。
- **导入**：受理在 Tomcat 线程上；执行在 7.2b 的 `data-ops` 线程上；续约在 `data-ops-fence` 线程上。写事务的连接规则同 data-ops-spec §7.5：pbmysql 表用 `DataSourceUtils.getConnection` 取 Spring 事务绑定的同一个连接。

### 5.5 xm-robot：`data-stress`

**前提**：`XM_RUN_MODE=dev|test`（GM 37）；`XM_ADMIN_TOKEN`；建议服务端 `XM_SCENE_SAVE_INTERVAL=5s`。本机起切片时设 `XM_GATEWAY_RATE_LIMIT_ENABLED=false`：切片缺省是开的（`start-slice.sh:70`），IP 桶每秒 5 个、账号冷却 5 s（`xm-gateway/src/main/resources/application.yaml:92-100`）。

**选项**：

| 选项 | 缺省 | 说明 |
|---|---|---|
| `--count` / `--rounds` / `--run-tag` | 10 / 10 / 按时间生成 | count ≤ 200（`RobotOptions.java:66`）；更大规模用多个进程、不同 run-tag |
| `--exit-mix` | `leave:6,disconnect:2,displace:2` | 退出方式的权重 |
| `--switch-scene` | `off` | `every:N`：每 N 轮发一次 63 `{scene_id = 同图另一已知频道}`，覆盖 5.2 交出，并保持位置载体有效。「已知频道」来自全部账号 79 里出现过的 scene_id（进程内共享）；开局若所有账号都落在同一个 scene_id，照 `CrossNodeScenario` 的落位办法让一部分账号离开、等 6 s（节点目录 5 s 刷新）再登，至多 4 次，仍不行就把本次运行的换节点判为 UNVERIFIED(`no_second_channel`)。交出与否以收尾时 `xm_scene_transfers_total` 的增量为准 |
| `--currency` | `on` | `off` 时只剩位置载体，DS3 / DS4 / DS9 显式「未检查」 |
| `--audit` | `on` | DS9 |
| `--online-save` | `off` | `every:N` 开 DS10 |
| `--save-interval` | `5s` | 必须与服务端一致；用于 DS7 / DS10，开头打印 |
| `--phase` | `all` | `drive` / `verify`；配 `--hold-last-round`，最后一轮不退出，供外部 kill |
| `--chaos` | `off` | `tolerate`：把中途断开、23 `{1003}` / `{3023}` 记成中断轮 |
| `--server-checks` | `on` | `off` 时只做客户端可见的 DS1–DS4、DS7、DS8（可以拿来跑 mmorpg，Q12） |
| `--settle` | `45s`（`--chaos tolerate` 时 `65s`） | DS6 的截止；chaos 下要盖过在线目录 TTL 60 s |
| `--scene-metrics-url` | `http://127.0.0.1:18104` | 可以逗号分隔多个，**必须覆盖全部 scene 节点**；两个节点时还有 18114（`start-slice.sh:102`） |
| `--data-url` / `--out` | `http://127.0.0.1:18106` / `run/robot` | — |
| `--consistency-fail-on` | `block` | 收尾巡检的门槛 |

**每个账号一条虚拟线程**。每一轮：

1. **进场**（还没在游戏里时）：assign-gate（遇到 429 / 100 按 `retry_after` 退避，不计失败，截止 30 s）→ TCP → 握手 → 48 →（第一次）14 → 26。撞 2005 退避重试，200 ms 起、封顶 1.6 s、截止 40 s（大于 30 s 租约）。不复用 `PlayerFlow` 的「4 次 × 1 s」（`PlayerFlow.java:45-47`），理由同 `scene-drain-spec.md:1310`。等 79 和自己的 21。
2. **DS1 / DS8**：位置载体有效时比对自己 21 的位置；发 54 比对钻石与金币。
3. **移动**：134 → 132（250 ms 后，1 条）→ 131，目标为本轮编码位置。收到 137 就以服务端位置为准，本轮位置载体记为无效（DS3 跳过本轮）。
4. **加币**：GM 37 钻石 + 1；第 1 轮再给金币加 `f_p`。等应答；超时记入不确定带。gate 推 23 `{1006}`（prod）→ 整次运行 UNVERIFIED(`currency_gm_denied`)、**立即停止再发 37**（每条被拒的 GM 指令都计 gate 非法包，累计 50 个断连，`GateProperties.java:18`），提示改用 `--currency off`。应答 tip 27003（冻结中，`CurrencyFeature.java:36-38`）或运维全服封禁获取的拒绝是「确定没生效」，不进不确定带、本轮重发一次；仍拒就记 UNVERIFIED。
5. **换节点**（`--switch-scene` 命中的轮次）：63 `{scene_id}`，等 79（另一节点）和自己的 21，坐标应不变。
6. **屏障**：发 54。同一会话的请求按序到达 scene 逻辑线程，而 131 本身不回包，所以 54 既当屏障，又核对内存中的余额。
7. **在线存盘**（`--online-save` 命中的轮次）：等 `save-interval + 2 s`，读导出包，核对 DS10。
8. **退出**，按混合比例：
   - `leave`：发 17、保留连接，下一轮在同一连接上发 26（PARITY:32），覆盖「LeaveGame 紧跟 EnterGame」的接管路径。
   - `disconnect`：关 TCP，下一轮走完整的分配 gate 加登录。
   - `displace`：第二条连接登录同一角色并进游戏；旧连接必须先收到 23 `{2017}` 再被断开（PARITY:37）。新连接即下一轮的进场。每个账号同时至多 2 条连接，不碰设备数上限（PARITY:87：「已绑定、没在游戏里」的连接 ≤ 3）。

**收尾**：

1. 用 `/admin/consistency/players` 轮询，直到全部登记玩家已释放（DS6），截止 `--settle`。
2. 每个账号再登一次，核对 DS2 / DS3 / DS4，然后发 17 离开。
3. 读导出包，核对服务端一侧的 DS2；DS9 先抓 xm-data（`--data-url` 的 `/actuator/prometheus`）的 `xm_data_kafka_consumer_lag` 等积压归零，再读 `/admin/transaction-log`、`/admin/player-snapshots`。
4. 抓取全部 scene 节点的指标，算增量（DS5；DS7 / DS10 用到的 `xm_scene_periodic_saves_total`），抓法复用 `AuditScenario.java:268-285`。
5. `POST /admin/consistency/runs {deep: true}`，等它完成；按 `--consistency-fail-on` 判定。

**输出**：

- 账本 `run/robot/data-stress-<runTag>.json`：每轮先写临时文件再原子改名。按玩家记录基准、`f_p`、每轮的退出方式、目标、确认值、不确定带、确认时刻、观测值，以及开局和收尾的归属元组。
- 判定 `run/robot/data-stress-<runTag>-result.json`。
- 汇总行 `DATA_STRESS_OK …` 或 `DATA_STRESS_FAIL kind=…`。kind 是有界枚举：`RAW_STALE`、`RAW_AHEAD`、`FINAL_BEHIND`、`FINAL_AHEAD`、`TORN`、`FOREIGN`、`REGRESSION`、`NOT_RELEASED`、`ZOMBIE`、`FENCE_VIOLATION`、`EPOCH_MISMATCH`、`LOSS_WINDOW_EXCEEDED`、`ASSET_DUPLICATED`、`METRIC_FENCED`、`METRIC_FAILED`、`AUDIT_MISSING`、`SNAPSHOT_MISMATCH`、`ONLINE_SAVE_MISSING`、`CONSISTENCY_BLOCK`、`UNVERIFIED`。
- 退出码：0 通过 / 1 失败 / 2 参数错（`RobotMain.java:48-50`）。**验证不了就判失败（UNVERIFIED），不跳过**（修 S1）。
- robot 不直连数据库，也不持有数据库凭据，只用现有的 `AdminClient` 和运维令牌。

### 5.6 xm-robot：`stress`

```
java -jar xm-robot.jar stress --count 200 --ramp-interval-ms 50 --duration 10m \
     --profile stress|behavioral|fighter|explorer|chatter|stress-aoi | --weights cast_skill=50,move=30,idle=15,chat=5 \
     --action-interval-ms 3000 --seed 42 --switch-target same-map|other-map|cross-node --chat world|off \
     --run-tag t1 --out run/robot/stress-t1 --report-interval 5s --io-threads 0 --behavior-log --report-only \
     [--gate-min-enter-ratio 0.99 --gate-max-fail-ratio 0.01 ...] [--llm-endpoint URL --llm-model M]
```

- **规模**：`--count` 上限 20000，与 smoke 的 200 分开设。Windows 开发机实际约 500（Hyper-V 端口保留，`inventory/tools.md:463`）；更大规模放到 Linux runner 或预发环境。
- **账号**：`<prefix>s<runTag>_<n>`，≤ 64 字符；前缀必须在 xm-login 的开发账号白名单里。固定 `--run-tag` 可以复用角色，避免每轮建几千个号。
- **统计行**：前缀与 Go 格式串逐字相同（`stats.go:188-206`）。`elapsed` 先截到整秒再按 Go `time.Duration.String()` 渲染（`stats.go:156`；`GoStatsLine` 有金样单测），速率按 `%.0f` 取整；Java 没有的项填 0（`refresh_*`、`recon_*`：Java 断线一律完整重登，没有 access_token 重连），排队项按上文如实填。每行同时写 `stats.log` 和 **stderr**：`stress_summarize.ps1` 读的是 stderr 文件（`:161` 之前的 `$stderrPath`），匹配不锚定行首，所以 Java 日志前缀无妨。行尾追加 ` | java: skill_ok= skill_rej= skill_to= move= move_corr= switch_ok= switch_rej= hop= chat_ok= chat_rl= rate_limited= unexpected_tip= dropped= relogin= kicked= inbox_dropped= tick_late= p50_skill= p99_skill=`。
- **产物**：`--out` 下写 `stats.log`；`summary.json`（逐动作计数、固定对数桶的时延直方图，不引新库）；`--behavior-log` 时流式写 `behavior.jsonl`（有界队列，满了丢弃并计数）。stress-orchestration（抓服务端指标与汇总）读 `summary.json`，它不在 7.4（Q36）。

### 5.7 xm-robot：`player-data`（7.4c，dev 运行模式）

见 §11.4。新号 A 加币、移动后离线 → 导出 → 改包导入 → 重登核对；在线时的 reject / kick；2005；幂等重提。

### 5.8 `tools/*.java`

| 工具 | 子命令与参数 | 退出码 |
|---|---|---|
| `ConsistencyCheck.java` | `--url http://127.0.0.1:18106 --operator X --reason R [--checks a,b] [--zones 1,2] [--deep] [--fail-on block\|warn] [--wait 20m] [--last]`：POST 触发后轮询，打印 markdown 与 `CONSISTENCY_RESULT worst=…`；`--last` 只读最近一次。单文件工具不带 JSON 库：服务端在 `?format=md` 的末行固定输出 `CONSISTENCY_RESULT worst=<级别> block=<n> warn=<n> not_checked=<n>`，CLI 只做行匹配；`runId` 等扁平字段用 `GmShutdown.java:120-122` 同款的正则取值 | §1.6 |
| `PlayerData.java` | `export --player N --reason R [--related] [--redact] --out f.json`（文件权限 0600，Windows 上靠目录 ACL）；`zone --zone N [--after] [--limit]`；`import --file f.json --player N --reason R [--apply] [--if-online reject\|kick] [--create-if-absent --target-account A --target-name X --target-zone Z] [--sections a,b] [--accept-edited] [--accept-divergence] [--idempotency-key K]`（缺省 dry-run；没给幂等键就生成 UUID 并打印，方便原样重试；轮询 `/admin/ops-jobs/{id}` 直到终态） | 0 成功 / 1 作业失败或被拒 / 2 用法错误或连不上 / 3 轮询超时（作业还在跑） |

两者的令牌都只从环境变量 `XM_ADMIN_TOKEN` 读，`--operator` 必填（限可见 ASCII 1–64 字符，同 `GmShutdown.java:39`；服务端虽收 UTF-8，HTTP 头里放非 ASCII 不可移植）；跨环境搬运时 `export` 建议加 `--redact`。`PlayerData.java import` 把包文件原样作为请求体的 `bundle` 字段拼进去，不解析包内容。

### 5.9 本机脚本与 CI

- **`tools/local/start-slice.sh`**：加 `XM_SLICE_ONLY=<实例名>`，复用 `launch()`（`start-slice.sh:105-116`）只起一个实例（Q14）。scene-drain K10 也需要它。
- **`tools/local/data-stress.sh`**（新）：
  1. `data-stress --phase drive --hold-last-round`；
  2. `kill -9 $(cat run/pids/xm-scene.pid)`（或 gate / login；Git Bash 下 `$!` 记的 PID 发 SIGKILL 即强杀原生进程，`stop-slice.sh` 的超时强杀同法）；
  3. `XM_SLICE_ONLY=xm-scene tools/local/start-slice.sh`，带上与第一次相同的环境（`XM_SCENE_SAVE_INTERVAL=5s` 等）——单起一个实例时脚本不会替你补；
  4. `data-stress --phase verify --chaos tolerate`；
  5. `java tools/ConsistencyCheck.java --deep`。
- **CI**（依赖 7.1b，口径同 `deploy-ci-spec.md` §4.2、§4.5）：
  - `integration.yml` / stack 第三期：在 cross-node 之后、ratelimit 之前（ratelimit 会耗光 IP 令牌桶），加跑：
    - `data-stress --count 10 --rounds 4`（calm，`--online-save off`：stack 的 scene 用缺省 300 s 存盘间隔，DS7 / DS10 不在这里跑）；
    - `stress --count 20 --duration 60s --profile behavioral`（带闸门）；
    - 7.4c 之后加 `player-data`；
    - 最后 `java tools/ConsistencyCheck.java --deep --fail-on warn`：要求 worst ≤ INFO 且没有 NOT_CHECKED。此时库里已有好友、帮会、交易的真实数据，也顺带守住 pbmysql 表的列名漂移。
    - stack 的 gateway 开着限流（`deploy-ci-spec.md` §2.5 表：`XM_GATEWAY_RATE_LIMIT_ENABLED=true`；IP 桶每秒 5 个、突发 20，账号冷却 5 s，`xm-gateway/src/main/resources/application.yaml:92-100`），所有请求经 bridge 网关同一个 IP：robot 必须按 429 / 100 的 `retry_after_ms` 退避，不计失败。
    - compose 环境要补：xm-data `XM_RUN_MODE=dev`、`XM_DATA_OPS_ENABLED=true`（player-data 的导入闸门，§3.6；`ops.enabled` 同时要求 `XM_DUBBO_SECRET`，data-ops-spec §7.2）；gate / scene 的 `XM_RUN_MODE=dev`（GM 37，第二期的 currency 已经依赖它）。
  - **新增 `integration.yml` / chaos job**：
    - 触发：每日（与 it / stack 同一个 cron）、`workflow_dispatch`，以及 push 的提交信息含 `[chaos]` 时。Claude 触发不了 `workflow_dispatch`（`deploy-ci-spec.md:1099`），所以要留后一个入口。
    - `--profile two-scenes`，两个 scene 服务都设 `XM_SCENE_SAVE_INTERVAL=5s`；依次跑 C1、C2、C4、C5、C6；C3 设 `continue-on-error`。C6 的「EnterGame 期间」不靠精确计时：在 data-stress 以 `disconnect` 为主的退出混合下持续重登时杀 login，总有进场在途。
    - 每个用例失败时，把最后 20 行编码进 `::error`；timeout 45 min。
  - 单测与 H2 进 `ci.yml`；`-Dxm.it.*` 的 IT 进 `integration.yml` / it。新 IT 类受 `TestReport --require-it-executed` 约束（`deploy-ci-spec.md:727`、`:799`）。

### 5.10 文档与 PARITY 登记清单

- **PARITY 新增行**：
  1. 「跨区引用一致性巡检」：基线 `tools/data_consistency_check/main.go`；Java xm-data `consistency`；状态「已对齐（行为有意不同）」，引用 X1–X7。
  2. 「存盘一致性压测与收敛校验」：基线 `go/db/cmd/{data_stress,verifier}`、`go/db/internal/stresstest`、`robot/data_stress.go`；Java 为 xm-player-store soak / IT + xm-scene 故障注入 + `xm-robot data-stress`；引用 X8–X11。
  3. 「货币变更崩溃窗口验证」：基线 `tools/scripts/currency_crash_window.ps1`；Java 由 data-stress C1 + calm 覆盖。
  4. 「玩家数据导出 / 导入排障」：基线 `go/data_service/cmd/debug_{fetch,import}`；引用 D1–D12。
  5. 「压测机器人 AI（权重档）」：xm-robot `stress`；引用 R1–R9。
  6. 「压测机器人 LLM 决策」：7.4e 之前「Java 待做」，之后「已对齐（行为有意不同）」，引用 R10。
  7. 更新第 30 行的说明：「skill / scene_switch 为 Go 口径的发出数」。
- **architecture.md**：
  - §2 的 xm-data 一行加巡检与导出 / 导入；
  - §7 注明「导入同样是归属持有者，唯一写者」，以及「dev / test 下 xm-data 导入可以经 `PlayerStore` 建同号 player 行」（建角此前只有 xm-login 一个入口）；
  - §11 加新指标（含 xm-scene 的 `xm_scene_owner_leases_lost_total`）。
- **db-migrations.md**：不改表、不新增写 SQL（`insertImportedPlayer` 只组合现有语句）；只登记 pbmysql 表在 `ops_tables.proto` 里新增的枚举值。
- **data-ops-spec / 7.2 实现**：快照保留期的原因清单加 `SNAPSHOT_GM_IMPORT`（归 `gm-snapshot`，§3.7）。
- **tech-stack.md**：不新增第三方库（JDK HttpClient、Jackson、protobuf-java-util、Spring `CronTrigger` 都已在用或属于 Spring 自带），只在说明里记一笔。
- **xm-robot/README.md**：加 `stress`、`data-stress`、`player-data` 三节。
- **roadmap**：7.4 行打勾；stress-orchestration 建议补登到 7.6（Q36）。
- **盘点勘误**：§0.5 各条。

---

## 6 配置

| 键 | 缺省 | 说明 |
|---|---|---|
| `xm.data.consistency.cron` / `cron-time-zone` | `0 10 4 * * *` / `Asia/Shanghai` | `-` 关闭；定时运行不带 deep。时区键不叫 `zone`，免得与游戏区混淆 |
| `xm.data.consistency.page-size` / `statement-timeout` | 2000 / 30 s | keyset 分页、JDBC 语句超时 |
| `xm.data.consistency.check-budget` / `run-budget` | 60 s / 15 min | 超出报 NOT_CHECKED(`budget_exceeded`) |
| `xm.data.consistency.sample-limit` / `recheck-limit` | 20 / 1000 | — |
| `xm.data.consistency.recheck-delay` / `recheck-delay-ownership` / `recheck-delay-presence` | 2 s / 35 s / 65 s | §1.7；presence 那一档必须大于在线目录 TTL 60 s |
| `xm.data.consistency.lease-skew-tolerance` / `abandoned-grace` | 5 s / 60 s | `own.*` |
| `xm.data.consistency.friend-orphan-block-ratio` | 0.05 | 同基线 |
| `xm.data.consistency.gauge-refresh` | 60 s | 每个副本按最近一份报告刷新 gauge（§1.8） |
| `xm.data.max-players-per-account` | 5 | `account.player_cap` 与导入建行共用；须与 xm-login 的 `max-players-per-account` 一致（`xm-login/src/main/resources/application.yaml:88`） |
| `xm.data.consistency.report-ttl` / `runs-kept` / `lock-ttl` | 8 d / 20 / 60 s（每 20 s 续） | Redis |
| `xm.run-mode`（xm-data，环境变量 `XM_RUN_MODE`） | prod | 导入闸门；`start-slice.sh:30` 缺省给 dev |
| `xm.data.import.max-state-bytes` | 1000000 | 与快照上限一致 |
| `xm.data.import.max-request-bytes` | 4 MiB | 超出 413 `request_too_large` |
| `xm.data.import.dev-account-prefixes` | `robot_`, `dev_` | `createIfAbsent` 的账号白名单；须与 xm-login 的 `dev-account-prefixes` 一致 |
| `xm.data.export.related-limit` | 200（每表） | `LIMIT max+1` 判截断 |
| 服务端环境（data-stress） | `XM_SCENE_SAVE_INTERVAL=5s`、`XM_GATEWAY_RATE_LIMIT_ENABLED=false`（本机） | §5.5 |
| robot | §5.5、§5.6 的选项；环境变量 `XM_ROBOT_*`（沿用 `RobotOptions` 的写法，命令行优先）、`XM_ADMIN_TOKEN`、`XM_ROBOT_LLM_API_KEY` | — |
| tools | `XM_ADMIN_TOKEN`；`--operator` 必填 | — |

---

## 7 指标（与告警、审计日志）

指标全部定义在 `DataMetrics`，标签取值启动时预注册；**不以 player、job、zone、run 的 id 作标签**（AGENTS §5）。

| 指标 | 类型 | 标签 |
|---|---|---|
| `xm_data_consistency_runs_total` | Counter | `trigger`=schedule / manual；`result`=clean / info / warn / block / error / skipped |
| `xm_data_consistency_check_status` | Gauge | `check`（27 个固定枚举）；值 0 ok / 1 info / 2 warn / 3 block / 4 not_checked |
| `xm_data_consistency_bad_rows` | Gauge | `check` |
| `xm_data_consistency_last_completed_timestamp_seconds` | Gauge | — |
| `xm_data_consistency_run_seconds` | Timer | — |
| `xm_data_player_export_total` | Counter | `result`=ok / not_found / error |
| `xm_data_ops_jobs_total` 等 7.2 指标 | — | `kind` 增加 `import`；`xm_data_ops_players_total.outcome` 增加 `imported`、`created`（data-ops-spec §8.2） |
| `xm_data_snapshot_admin_total` | Counter | `cause` 增加 `gm_import` |
| `xm_scene_owner_leases_lost_total`（xm-scene） | Counter | —（续约发现归属已被夺走、移除实例的份数；§2.8 C2） |

**告警**（多副本时一律按实例取 max；各副本每 60 s 从最近一份报告刷新 gauge，所以 max 反映的是最新一轮，§1.8）：

- `check_status == 3`（block）→ 页面告警。
- `check_status == 4` 连续两轮 → 警告。
- `time() − max(last_completed_timestamp_seconds) > 36h` → 警告。基线真实的失败模式就是「从没跑过」，这条专门防它。

**日志**：

- 每项检查一行结构化日志，logger `xm.data.consistency`；样本 id 只进日志和报告。
- 导出写 `xm.audit.admin`（带 reason）。
- 导入走 `ops_job` / `ops_job_event` / `ops_job_player`，以及 `xm.audit.ops`。

**robot**：不暴露 Prometheus。产物是运行目录里的 JSON 和统计行。

---

## 8 隐患与边界

### 8.1 基线自身（mmorpg 待做候选，交 mmorpg 侧裁决，不阻塞 Java）

| # | 内容 | 位置 |
|---|---|---|
| 1 | 巡检：帮会榜查错库（假绿）、两项表缺失报 info、EXISTS 出错被算成孤儿、好友只查一端、RAND 抽样、退出码混用、从没跑过、trade / 邮件检查待补 | §1.2 C1–C9 |
| 2 | 压测：verifier fail-open、读错误与缺行混同、只验 `player_database`、L2 不带 epoch、L3 恒通过、yaml 校验命令恒失败、`chaos_test.ps1` 缺失 | §2.3 S1–S9 |
| 3 | 排障：字段图非权威、导入不碰 blob、截断后删光、float64、写序、ABA、不看在线、SQL 白名单与 `;` panic、吞错、`proto_json` 不生效、`-player` 覆盖坏、逐表事务、文档腐坏 | §3.2 H1–H13 |
| 4 | 压测 AI：move 发空 43、chat 必被拒、发出即成功、静默回落档位 / 丢弃动作名、行为记录无界、退出码恒 0、无握手重连后空转 | §4.1–§4.5 |
| 5 | 盘点 `data.md:364` 对「缓存缺席软通过」的说法与代码相反 | §2.1 |

### 8.2 Java 实现会踩的

1. **长事务**：RR 快照会拖住 undo purge。单项限时 60 s，并且分页。生产库很大时，先调大预算或改走只读从库（Q7）；不要去掉预算。
2. **租约列建索引**：会把写放大落在最热的列上，不要建。
3. **在途状态造成误报**：进场、交出、7.2 作业、合服、gate 崩溃后残留的在线目录条目都可能被误判。靠两遍复核（复核间隔按检查分档，presence 那档大于其 TTL）、作业期间对 `own.zombie` 报 NOT_CHECKED、合服围栏期间对 `zone.*` 报 NOT_CHECKED 来防。
4. **时钟**：租约统一以库时钟为准；`own.lease_skew` 本身就是 NTP 前提的哨兵。快照的 `time_ms` 跨节点比较同样依赖 NTP。
5. **列名耦合**：巡检按列名读别的模块的 pbmysql 表。靠「只扩不缩」的纪律、`SchemaGuard`，以及 CI 全栈收尾巡检兜底。
6. **多副本**：执行那一轮的副本写报告；**每个副本**每 60 s 按 Redis 里最近一份报告刷新自己的 gauge，否则没执行的副本会一直挂着旧值、`max` 摘不掉旧告警。Redis 不可用时巡检不跑，由「36 h 未完成」告警兜住。
7. **压测被限流**：
   - gateway 的 IP 令牌桶和账号冷却（切片缺省开着）；
   - gate 按消息号限频（84 / 63 / 37 / 61 / 17 / 26 缺省每秒 3 条）；
   - 设备数上限 3；
   - 登录排队（`--count` 超过区服 `capacity` 时）；
   - gate 非法包阈值 50（超频、运行模式拒绝的 GM 指令都算），到阈值断连；
   - robot 必须按 `retry_after` 退避，必须关闭被取代的连接。
8. **GM 运行模式**：prod 下 37 被拒（23 `{1006}`），data-stress 必须显式 `--currency off`，否则判 UNVERIFIED。
9. **应答丢失后的重试**：已提交的最终写回在重试时会因为 `owner_released = 1` 而得到 0 行，被记成 fenced（`StoragePlayerRepository.java:51-53`、`:304-327`；`PlayerStore.saveStateAndRelease:252`）。内容其实已经落库，只是分类失真。所以 DS5 在 calm 下判 0，在注入下不作失败判据；故障注入单测要钉住「内容不丢、不重复」。另一类合法的 fenced 是 `op=progress`：离场不等在途的在线存盘，后者晚到就被围栏拒（`SceneWorld.java:96-97`），DS5 因此不把它算进判据。
10. **快照与落盘的字节比较**：LOGOUT 快照与最终写回是同一份 `PlayerSave`（`architecture.md:206-207`），但两路各自序列化，map 字段的顺序不能保证逐字节相同。DS9 先比 sha256，不等再比解码后的 JSON 树。
11. **多 scene 节点**：robot 必须抓全部节点的指标，否则 DS5 会漏算。
12. **6.3 `battle_ledger`**：落地时必须并入回档 / 导入的 `assets` 段组，否则战斗结算可以被重放。
13. **虚拟线程钉住**：旧 `Inbox` 的 `synchronized` + `wait` 在 JDK 21 上会钉住载体线程。stress / data-stress 一律用 `StreamingInbox`。
14. **世界聊天**：没有推送，但每条都要写 Redis 的 LPUSH + LTRIM（`ChatStore.java:27`），并按发言人限速。chatter 档在大规模下压的是 Redis，不是 gate 下行。
15. **Go 统计行格式**：`elapsed` 与时延必须按 Go Duration 渲染（`1.234s` 而不是 `1234ms`），否则 `stress_summarize.ps1:161` 的正则会对不上。基线自己在时延 ≥ 1 s 时也匹配不上 `(?<max_login>\d+)ms`，Java 照抄这个行为，不擅自修复。
16. **导出含个人数据**：账号与角色名属于敏感数据。CLI 写文件用 0600；接口只绑本机（`architecture.md:219`）；每次调用写审计日志。生产导出拿到开发环境使用，要按 runbook 的数据处理规定办（Q23）。
17. **presence 的 zone 是 gate 所在的区**，不是归属区（`friend-spec.md:988`），不能拿来做区过滤。
18. **新 IT 类**：凡带 `@EnabledIfSystemProperty(named = "xm.it.*")` 的类，在 CI 的 it job 里必须实际执行（`TestReport --require-it-executed`）。
19. **枚举号被并行批次抢占**：`TransactionReason` 的 Java 独有段已被 6.3 规格占到 1005（`TX_BATTLE_REWARD`），本批取 1006；`SnapshotCause` 1003 目前没人占。各批落地前都要再查一次 proto 与全部 `*-spec.md`，撞号会让 xm-data 把两种原因混成一种（消费端按数值原样落库）。
20. **区服目录行可被直接删除**（`ZoneAdminController.java:114-119` 不检查区内数据）：巡检会把残留数据报 block，但它只事后发现；删除前的保护属于 7.3 / 7.6。
21. **PITR 后纪元**：恢复 `guild_player_op_seq` 所在的库之后不抬高 `epoch`，scene 账本会把重发的 seq 当成已见过（`guild-economy-spec.md:426`）。`assetop.seq_watermark` 是事后哨兵，不能替代 runbook 里「恢复后抬高纪元」这一步。

---

## 9 建议的有意差异（落地后逐条登记 PARITY）

| 编号 | 差异 | 理由 | 客户端可见 | mmorpg 待做 |
|---|---|---|---|---|
| X1 | 存活 zone 取自 `zone_config`，不从 mapping Redis 推断；保留覆盖参数 | 推断会把没有映射的区当成已死（C6）；Java 有权威的区服目录 | 否 | 否 |
| X2 | 全部改成精确查询（keyset + 反连接），不用 5 倍启发式和 RAND 抽样；好友两端都查 | C3 / C4 / C5；单库、规模可控，抽样会藏住少量孤儿 | 否 | 可选 |
| X3 | NOT_CHECKED 对所有检查都是一等状态；worst 区分 ERROR 与 BLOCK；CLI 退出码 3 | 修 C2 / C7 | 否 | 是 |
| X4 | 在 xm-data 进程内运行：缺省每日定时、手动触发、事件后触发；报告存 Redis，配指标与告警 | C8：基线的 cron 从未落地 | 否 | 是 |
| X5 | 增加 Java 独有检查：归属、好友对称与计数、帮会成员与帮主、world 登记、交易 zone、名字键、状态解码、资产通道 seq 水位；并补上基线待做的「帮会成员 / 挂单的 zone 与归属区一致」 | 这些是 Java 自己的不变量；后者是基线设计意图（`server-merge-gap-fixes.md:352`），基线没做 | 否 | 可选（基线待做项） |
| X6 | 不拼 SQL 标识符 | 单库，表名都是常量 | 否 | 否 |
| X7 | 帮会榜经 `RedisKeys.guildRankZones()` 读正确的键空间 | 修 C1（假绿） | 否 | 是 |
| X8 | 不移植探针字段、签名、Kafka L2 驱动和 `verify:*` 键；改用货币（计数器 + 指纹）加位置作载体。基线每轮「按 stress 档 AI 玩 `play_seconds`」（`robot/data_stress.go:161-300`）也不移植：AI 动作不改动任何载体，只会让判定多出噪声；要背景负载就另起一个 `stress` 进程 | §2.5；还能查出「落后一轮」、撕裂、串号、资产多出 | 否（只用已有的 37 / 54 / 移动） | 是（S7：L3 恒通过） |
| X9 | 验证不了就判失败；缓存类不变量在 Java 存盘上不适用 | 修 S1；Java 存盘没有缓存层 | 否 | 是 |
| X10 | 混沌对象是 scene、gate、login、MySQL、Redis，不是 db 消费者 | Java 没有 Kafka 存盘链 | 否 | 否 |
| X11 | 期望账本放在 robot 本机文件，不写服务端 `verify:*` 键 | 测试状态不混进业务键空间 | 否 | 否 |
| D1 | 排障工具是 xm-data 运维接口，不是持库口令的 CLI | 复用鉴权、审计、栅栏、发号；不下发口令 | 否 | 否 |
| D2 | 导入先夺权（reject / kick），带 epoch 围栏写 | 修 H7；同 7.2 回档 | **是**：被踢的人收 23 `{2017}`，持有期间登录回 2005。与 7.2 同一通路，没有新消息 | 是 |
| D3 | 导入只在 DEV / TEST 运行模式开放；生产恢复只走快照 + 回档 | 防止文件注入资产 | 否 | 是 |
| D4 | 不支持改号克隆 | 基线的 `-player` 覆盖本身就是坏的（H11）；改号需要重发实例号并处理账本（Q19） | 否 | 是（H11） |
| D5 | 只读 SQL 模式不移植 | H8 | 否 | 可选 |
| D6 | Redis 字段图模式不适用，改给 `live` 段 | Java 没有 Redis 玩家数据 | 否 | 否 |
| D7 | 关联行只导出、不导入；标出截断、报告错误 | 修 H3 / H9；社交表有跨行不变量（好友对称、帮会人数），按行导入会破坏它们 | 否 | 是（H3） |
| D8 | 64 位整数用十进制字符串 | 修 H4 | 否 | 是 |
| D9 | 状态只认一处来源，冲突就拒绝；带包哈希 | 修 H10 | 否 | 可选 |
| D10 | 导出必须带原因，永不导出口令哈希，可以脱敏 | 个人数据访问审计 | 否 | 可选 |
| D11 | 导入包落成 GM_IMPORT 快照；安全快照、覆盖写、`TX_GM_IMPORT` 流水、明细在一个事务里 | 修 H12；可以撤销；余额链首尾相接（同 7.2 D4 / D11） | 否 | 否 |
| D12 | `createIfAbsent` 建同号的行，并绑定开发账号，用于跨环境复现 | 替代基线「导到本机 Redis」的用途（`debug_import/main.go:179`），不必改号 | 否 | 否 |
| R1 | `move` 发真实的 134 / 132 / 131（基线发 43） | 基线注释表明本意是移动；这样才压得到视野 | 否：契约不变，只是机器人发出的流量不同 | 是（Go robot） |
| R2 | `chat` 走 WORLD 并带 request_id（基线频道 0，必被拒） | 让动作真正生效 | 否：同上 | 是 |
| R3 | 按应答计结局（基线按发出计） | 发出数说明不了服务端的表现 | 否 | 可选 |
| R4 | 有时长、有闸门，退出码即结论（基线跑到 SIGINT，退出码恒 0） | 能进 CI | 否 | 可选 |
| R5 | 档名或动作名写错就报错退出（基线静默回落） | fail-closed | 否 | 可选 |
| R6 | 断线就完整重登（基线无握手重连后空转）；收尾顺序 17 → 58 → 关连接与基线相同 | 契约 §7.3 记录的空转会让计数失真 | 否 | 可选 |
| R7 | 可复现的种子；Java 专有档位 `stress-aoi` | 复现问题；压视野 | 否 | 否 |
| R8 | 统计行前缀与 Go 逐字相同，Java 项追加在后 | 两版对比 | 否 | 否 |
| R9 | `--switch-target` 增加 `other-map` / `cross-node`，缺省 same-map 与基线同形 | Java 的同图 63 只在本节点内选频道（`SceneWorld.java:568-574`），覆盖不到交出 | 否 | 否 |
| R10 | LLM：密钥只读环境变量；提示词不带玩家号；异步决策；没有缺省端点；≤ 10 个机器人 | 秘密规则（AGENTS §3）；不挡节拍 | 否 | 可选 |

---

## 10 开放问题（各带推荐答案）

| # | 问题 | 推荐 |
|---|---|---|
| Q1 | 巡检放在服务端，还是做成直连库的 CLI | **xm-data + `tools/ConsistencyCheck.java`**：同一个库，这样才有定时、指标和统一审计 |
| Q2 | xm-data 直接读 friend / guild / trade 的表，还是由各服务各自出自检接口 | **只读 SQL 直读 + SchemaGuard + CI 漂移守卫**：同库、只读、需要跨表反连接（`player` × `guild_member` × `guild` 这类，各服务的接口给不出）。data-ops-spec Q15 对**回档的帮会分歧检查**选了 Dubbo、理由是「直读越过服务边界」——那是要按帮会语义解读指令状态、并据此放行写入的门；巡检只报告、不放行任何写，读的是列级事实，列一漂移就以 NOT_CHECKED 暴露。两处取舍不同是有意的，不是矛盾 |
| Q3 | 定时缺省开不开 | **开**：每日 04:10（UTC+8），不带 deep；7.6 可以按环境调整。另在回档、合服、PITR 之后各跑一次 |
| Q4 | 已合服的区怎么表示 | 由 7.3 决定，**推荐 `zone_config.merged_into`**。在那之前全部行都算存活；合服围栏期间 `zone.*` 报 NOT_CHECKED |
| Q5 | 孤儿引用定什么级别 | **warn**；好友孤儿 ≥ 5% 升 block。CI 用 `--fail-on warn` |
| Q6 | 报告存表，还是存 Redis | **Redis**（运行记录 8 天 + 最近 20 个 id）加日志，**不建表**（YAGNI）；历史由 7.6 的日志采集承担 |
| Q7 | 走只读从库 / 只读账号吗 | 暂不。主库 + 只读事务 + 分页 + 低峰执行；7.6 有从库后再加 `xm.data.consistency.datasource`（只有 SELECT 权限的账号） |
| Q8 | 巡检要不要自动修复 | **不要**，同基线；修复走 7.2 作业或排障工具 |
| Q9 | 要不要服务端探针 | **不移植**。存储层 soak 用未知字段作探针；端到端用货币 + 位置作载体 |
| Q10 | robot 直连 JDBC 吗 | **否**，走 xm-data 的只读接口；robot 不持有数据库凭据 |
| Q11 | data-stress 依赖 GM 吗 | **缺省依赖**（dev / test）；prod 环境显式 `--currency off`，只剩位置载体 |
| Q12 | 用 Java robot 跑 mmorpg 吗 | 可选，不作门禁：`--server-checks off`（GM 开时再加 `--currency on`）。基线 LeaveGame 会删登录会话，所以 `leave` 之后要重新 Login（PARITY:32），robot 要按对端是哪一版切换。结果记进 PARITY 备注 |
| Q13 | C3 是已知会失败的用例，怎么处理 | **保留严格判定**，标为预期失败并加 annotation；「持续重试直到成功」落地后转为必过。不放宽不变量 |
| Q14 | 本机怎么单独重启一个进程 | 给 `start-slice.sh` 加 `XM_SLICE_ONLY=<实例名>`，复用 `launch()`；混沌编排写在 `tools/local/data-stress.sh` 里，robot 不动进程 |
| Q15 | currency-crash-window 由谁覆盖 | **由 data-stress 的 C1 + calm 覆盖**，PARITY 单列一行 |
| Q16 | 后续批次的巡检怎么接 | 各批把自己的检查实现成 `ConsistencyCheck`，作为验收项登记进目录（6.3、6.4、4.8、7.3） |
| Q17 | 排障工具做成什么形态 | **xm-data 接口 + `tools/PlayerData.java` 薄包装** |
| Q18 | 生产环境开放导入吗 | **不开放**（403）；生产用「快照 + 回档」。真有需求再另立「双人复核」设计 |
| Q19 | 支持导到另一个 player_id 吗（基线 `-player`） | **v1 不支持**。将来要做的话：所有 `item_uuid` / `pet_id` 用 `SCENE_GUID` 重发并重映射 `active_pet_id`；保留目标的 `asset_ledger`、`currency.debts`、`blocked_types` |
| Q20 | 跨环境复现怎么做 | **`createIfAbsent`**：建同号的行，绑定开发账号，名字冲突用 `targetName` 解决 |
| Q21 | 关联表用静态登记，还是 information_schema 发现 | **静态登记 + 发现告警**：显式、可测，又能防止新表漏登记 |
| Q22 | 在线玩家导出要不要强制刷盘 | **不要**。给已落盘内容加 `online` 标记；需要精确状态时先 kick 或等下线；不新增 scene 接口 |
| Q23 | 导出缺省带不带账号与名字 | **带**（排障要用）；跨环境搬运时 `PlayerData.java export --redact`；生产数据进开发环境的规则写进 runbook |
| Q24 | 导入流水的原因码 | **新增 Java 独有的 `TX_GM_IMPORT = 1006`**，与回档的 16 区分开；1005 已由 6.3 规格占给 `TX_BATTLE_REWARD`（`scene-battle-spec.md:531`），实现时再核对空号 |
| Q25 | 导入要不要重发实例号 | **v1 不重发**：同号导入等于「回到外部内容」，与回档一致；重发只在将来的改号克隆里需要（Q19） |
| Q26 | 导入跑不跑资产分歧检查 | 目标原先已存在：**照跑三道**；`createIfAbsent` 新建的：跳过，并记 ACCEPTED |
| Q27 | `move` 保持基线的 43，还是发真实移动 | **真实移动**（R1）；PARITY 记「mmorpg 待做：Go robot 的 move 改发 134 / 132 / 131」 |
| Q28 | `stress` 档要不要加 move | **不改**，保持 85 / 15，方便两版对比；另设 `stress-aoi`。压测编排两档都跑 |
| Q29 | 移植 LLM 决策吗 | **移植为可选项（7.4e）**，带 R10 的安全改造；用户可以裁成「不移植」，届时 PARITY 记「不适用（有意不移植）」 |
| Q30 | chat 缺省走哪个频道 | **WORLD 加 request_id**；`--chat off` 可以关 |
| Q31 | switch_scene 缺省发什么 | **same-map**，与基线同形；`other-map` / `cross-node` 可选 |
| Q32 | 机器人线程模型 | **每个机器人一条虚拟线程** + `StreamingInbox`；I/O 线程只入队 |
| Q33 | 闸门缺省阈值 | 99% / 1% / 1% / 1% / 0 / 0（§4.8）；第一次本机基线跑完后，用实测值回填 README |
| Q34 | 行为明细缺省写不写 | **不写**，只写 `summary.json`；`--behavior-log` 才写 JSONL（每条约 150 B，1000 个机器人 × 10 分钟约 20 万行） |
| Q35 | robot 要不要出镜像去集群内压测 | **不出**（同 `deploy-ci-spec.md:1034` Q6）；集群内大规模压测归 7.6 |
| Q36 | stress-orchestration（抓服务端指标、汇总表）放哪 | **不在 7.4**；建议在 roadmap 7.6 行补登，读本批产出的 `summary.json` |
| Q37 | 进不进 CI | **进**：stack 第三期加 data-stress（calm）、stress（20 × 60 s）、player-data、收尾巡检；混沌独立成 job（每日 + 手动 + `[chaos]` 提交） |
| Q38 | 巡检与 7.2 作业要不要互斥 | **不互斥**；作业期间只有 `own.zombie` 报 NOT_CHECKED |
| Q39 | 帮会成员归属区 ≠ 帮会 zone 定什么级别 | **block**：这个人用不了这个帮、又进不了别的帮（`uk_guild_member`），是玩家可见的故障；挂单 zone 不一致只影响展示的区市场，定 **warn** |
| Q40 | `assetop.seq_watermark` 管多宽 | **v1 只管帮会两条流**（`guild_player_op_seq` 是唯一的发号表）；交易流随 4.8 的发号表加入；6.3 的 `battle_ledger` 不走 seq 窗口，不在此列。「同纪元且 `max_seq ≥ next_seq`」与「账本纪元更新」判 block，「间隔 > 511」判 warn |
| Q41 | stress 撞上登录排队怎么办 | **照 Go 处理排队**（轮询 `/api/queue-status`、计 q_*），闸门的爬坡窗口把排队时间算进去；只想压吞吐时先经 `/admin/zones` 调大 `capacity`，写进 README |
| Q42 | `ops.job_residue` 要不要也报 REJECTED / CANCELLED 作业的 PLANNED 明细 | **不报**：那是「计划了、没写」的正常记录。若 7.2b 实现决定在终态时把它们统一改成 `not_executed`，再把②放宽到所有终态 |

---

## 11 测试计划

本机条件：没有 Docker、Go、python、node。MySQL 8.4 / Redis 8.10 / Kafka 4.3.1 作为普通进程运行，用 `bash D:/work/.tools/with-backends.sh <命令>` 起停（Kafka 另行常驻；`data-ops-spec.md:1113`）。**没有运行证据时，不得声称编译通过或测试通过。** 改了 `xm.audit` 枚举或 `ops_tables.proto`，要 `clean install`。

### 11.1 单测（缺省就跑）

| 编号 | 模块 | 内容 |
|---|---|---|
| T1 | xm-data | 每项检查在 H2 夹具上覆盖「干净」和「各种违例」两类；pbmysql 表在 H2 里手写对应 DDL |
| T2 | xm-data | 运行器：表或列缺失 → NOT_CHECKED；预算调到极小 → `budget_exceeded`；复核能滤掉在途状态（计入 transient）；`own.zombie` 复核时 epoch 变了算 transient；`own.ghost_presence` 用 65 s 档；`recheck_truncated`；样本上限；≥ 2^63 的无符号 keyset；`zones` 覆盖；合服围栏 / `ops_active` 下报 NOT_CHECKED；好友 5% 门槛；`ops.job_residue` 对 REJECTED 作业的 PLANNED 不报、对 SUCCEEDED 的报；`assetop.seq_watermark` 的三种判定与 `guild_player_state` 哨兵行（player_id = 0）被排除；worst 计算；JSON 渲染成 markdown 的金样（含末行 `CONSISTENCY_RESULT`） |
| T3 | xm-data | 单飞（替身锁：拿不到 → skipped / 409；续期失败 → ERROR）；长时间运行的检查不占调度线程、续锁照常（运行线程阻塞 90 s，锁不过期）；两个副本实例共用一份替身 Redis，执行方写报告后另一方 60 s 内刷新 gauge；用固定时钟算 `CronTrigger` 的下次触发；指标预注册且标签里没有任何 id；`opOf` 映射；MockMvc 鉴权（照 `AdminEndpointSecurityTest` 的写法） |
| T4 | xm-data | 只读断言：巡检代码路径里出现任何写语句即失败（替身 Mapper 记录 SQL 类型） |
| T5 | xm-data | 导出：缺 `player_state` 行；state 损坏 → `parseError`；uint64 字符串（2^63−1 量级的雪花号原样往返）；`password_hash` 不出现；`redact` 生效；presence 报错 → `"unavailable"`；`payloadSha256` 稳定（deterministic 序列化）；related 用 `LIMIT max+1` 判截断，单表出错进 `error` |
| T6 | xm-data | 导入受理矩阵：运行模式 PROD / 未设 / 写错 → 403；`ops.enabled=false` → 503；缺 reason / 幂等键 → 400；请求体超限 → 413；`player_mismatch`、`bundle_edited`（以及 `acceptEdited` 放行）、`state_conflict`（base64 与 json 语义不同才报：同一状态的 map 字段换序编码不报）、`unknown_fields_would_be_lost`、`ledger_invalid`、`duplicate_instance_id`、`bundle_too_large`、`zone_not_found`、`account_not_dev`、`name_invalid`；不存在且没开 `createIfAbsent` → 404；dry-run 零写入 |
| T7 | xm-data | `RestoreComposer` 纯函数：FULL / SECTIONS 组合规则、`blocked_types` 保留、未知字段保留；回档与导入两条路径共用同一套表驱动用例 |
| T8 | xm-player-store | `OwnershipFenceSoakTest`：S1–S4，固定种子；包含 5.2 交出与过期夺权的交错 |
| T9 | xm-scene | `StorageFaultInjectionTest`：应答丢失后重试（提交后抛 `SQLRecoverableException`；内容不丢不重、分类为 fenced 的行为钉住）、接管后重试被拒；`xm.scene.owner.leases.lost` 在续约发现归属丢失时按份数累加 |
| T10 | xm-robot | data-stress 判定器（纯函数，输入记录、输出 kind）：表驱动覆盖每一种 kind、不确定带、DS7 上下界（含 `deferred` > 0 → UNVERIFIED）、chaos 下 DS3 允许位置领先一轮、DS5 不把 `op=progress,result=fenced` 计入、chaos 下 DS9 只判不多出、位置载体失效轮、`--currency off` 下的显式未检查 |
| T11 | xm-robot | stress：内置档位与 Go 逐项相等；固定种子下抽 10 万次，各动作占比误差在 ±1% 内；严格解析退出 2；间隔与限频冲突退出 2（`--allow-rate-limit` 放行）；`GoStatsLine` 与 Go 格式串的金样比较（含 `5m0s`、`151ms`、`1.234s`、`(199/s)`），并用 `stress_summarize.ps1:161` 的正则（抄进测试资源）断言能匹配；统计行同时出现在 stderr；闸门判定；`StreamingInbox` 有界（压入 100 万条推送，峰值受限，应答不丢）且不钉住（1000 个虚拟线程等待者） |
| T12 | xm-robot | 假 gate（沿用现有「假 gate 上的握手与请求应答」写法）：应答对账（无错 / 带 tip / 超时）；服务端关闭后完整重登，计 `dropped` / `relogin`；信封 1008 计 `rate_limited`、闸门判红；应答体 tip 1008 计 `chat_rate_limited`；收尾依次发 17、58 再关连接；假 gateway 回登录排队 100 → 轮询 queue-status → 放行，q_* 计数正确；`RobotOptions` 新选项与互斥规则 |
| T13 | tools | `ConsistencyCheck.java` / `PlayerData.java` 的退出码映射，对 JDK `HttpServer` 起的假端点测试 |
| T14 | xm-robot（7.4e） | `LlmPolicy`：JDK `HttpServer` 假端点；超时与出错回 idle 并计 `llm_fallback`；请求体里没有 player_id；`--count > 10` 时退出 2；没给端点时退出 2 |

### 11.2 H2 SQL（缺省就跑，扩展 `DataSqlFixture` / `PlayerStoreSqlTest`）

- 导出读到 player 行加状态，导出前后库不变。
- `insertImportedPlayer`：建账号与玩家；名字冲突 409；账号满 409 `account_full`；同号属于别的账号 → `player_conflict`（不抛 `IllegalStateException`）；同号属于同一账号 → 按已存在处理；建行后写事务失败，留下的空号可被新键重提。
- 导入写事务：GM_IMPORT 快照、PRE_GM_EDIT 安全快照、覆盖写、`TX_GM_IMPORT` 流水、明细恰好 1 行，要么全部提交，要么全部不在（同 data-ops-spec 不变量 I2）；围栏丢失时零写入。
- a 组检查的 SQL 在生产建表脚本上运行。

### 11.3 真 MySQL / Redis 集成测试

| 开关 | 内容 |
|---|---|
| `-Dxm.it.mysql` | `OwnershipFenceConcurrencyIT`；巡检 SQL：RR 快照下跨分页一致、≥ 2^63 的无符号 keyset、语句超时生效、`EXPLAIN` 确认 `zone.player` 走 `idx_player_zone`（有索引时）、分页走主键；F / G / T 各项在服务真实建出的 pbmysql 表上逐项种一条违例，判定正确；导入写事务里 MyBatis 与 pbmysql 用同一连接（`CONNECTION_ID()` 相同，同 data-ops-spec §12.3）；1 000 000 字节（上限）状态的导出导入往返一致；4 MiB 状态（`MEDIUMBLOB` 存得下）能导出，导入回 400 `bundle_too_large`；`assetop.seq_watermark` 在真 `guild_player_op_seq` 表上种一条「账本 max_seq ≥ next_seq」判 block |
| `-Dxm.it.redis` | 区榜索引 SET、world zones、锁（令牌、续期、丢锁）、报告 TTL 与 LIST 截断、副本间 gauge 刷新、`own.zombie` / `own.ghost_presence` 的两遍复核（presence 条目带 60 s TTL、在 35 s 到 60 s 之间仍在时不得报 warn） |
| 两者都开 | 导入 kick 路径（复用 7.2b 的 T-F3 用例形状）；两个并发导入同一玩家，第二个回 409 `ops_busy` |

### 11.4 robot 计划（客户端可见）

| 场景 | 涉及消息 | 断言 |
|---|---|---|
| `data-stress`（新） | 48 / 14 / 26 / 79 / 21 / 47 / 134 / 132 / 131 / 137 / 37 / 54 / 63 / 17 / 23（`disconnect` 退出不发 17 / 58，模拟掉线） | calm：DS1–DS6、DS8、DS9 全过；`--switch-scene every:2`（两个节点）：DS5 的交出口径，Δepoch 计入交出；`--online-save every:3`：DS10；两阶段混沌：C1、C5 |
| `player-data`（新，7.4c，dev） | 48 / 14 / 26 / 79 / 21 / 54 / 37 / 134 / 131 / 17；被踢时 23 `{2017}`；夺权期间回 2005 | ① 新号 A 加币、走到某个坐标后离线。② 导出，核对余额与位置。③ 改写包里的 `player.pos` 与 `state.json` 的余额，**删掉 `state.base64`**（否则两处不一致回 400 `state_conflict`；新号没有未知字段，所以不会撞 `unknown_fields_would_be_lost`），`acceptEdited=true` 导入，`ifOnline=reject`，作业 SUCCEEDED；另发一次只改余额、不删 base64 的包，断言 400 `state_conflict`。④ A 重登：21 的位置等于导入值，54 的余额等于导入值；有 PRE_GM_EDIT 与 GM_IMPORT 快照，`TX_GM_IMPORT` 流水首尾相接。⑤ A 在线时导入，`reject` → 作业 REJECTED `player_online`。⑥ `kick` → A 先收 23 `{2017}` 再断开，持有期间另一连接登录回 2005，重登后看到导入状态。⑦ 同一幂等键重提，返回同一个 jobId |
| `stress`（新） | 48 / 14 / 26 / 79 / 77 / 84 / 70 / 33 / 134 / 132 / 131 / 137 / 66 / 63 / 21 / 47 / 64 / 51 / 61 / 17 / 58 / 23 | 三档各跑一次：`--count 50 --duration 120s --profile stress` / `behavioral` / `stress-aoi`；退出码 0；`rate_limited = 0`；scene 指标 `xm_scene_moves_total` 与 `xm_scene_aoi_changes_total` 有增长（behavioral / stress-aoi）；`--switch-target cross-node` 在两个节点下 `scene_hop > 0` 且 `xm_scene_transfers_total` 有增长；经 `/admin/zones` 把 `capacity` 临时调到 30、`--count 50 --gate-min-enter-ratio 0.6` 跑一次，确认登录排队路径（约 20 个机器人停在排队里、`q_entered` 非 0、轮询不报错、闸门按 0.6 通过；没人离场时排队的不会被放行），跑完改回（同 `queue` 场景的做法）；记录帧耗时、视野进出作为基线数字 |
| 回归 | — | smoke、movement、currency、audit、cross-node、reconnect、skill、chat 全过 |
| 跨版本（可选，不作门禁） | — | `stress --profile stress` 与 `data-stress --server-checks off` 打基线服务端，做同一机器人下的两版对比；先核对口令认证方式 |

### 11.5 本机端到端与混沌

1. 启动：`XM_GATEWAY_RATE_LIMIT_ENABLED=false XM_SCENE_SAVE_INTERVAL=5s XM_DATA_OPS_ENABLED=true bash D:/work/.tools/with-backends.sh tools/local/start-slice.sh`；双节点加 `XM_SCENE_NODES=2`（`start-slice.sh:15`）。
2. calm：`data-stress --count 50 --rounds 10`；双节点再跑一次 `--switch-scene every:2`。
3. 混沌：`tools/local/data-stress.sh` 跑 C1（kill scene）、C5（kill gate），可选 C4（停 Redis 20 s）、C6（kill login）。
4. `stress` 三档；`player-data`（7.4c）。
5. 最后 `java tools/ConsistencyCheck.java --deep --fail-on warn`。

### 11.6 CI（GitHub Actions）

见 §5.9。要求：

- stack 收尾巡检 worst ≤ INFO 且没有 NOT_CHECKED；
- chaos job 的 C1 / C2 / C4 / C5 / C6 必过，C3 以 annotation 呈现；
- 运行号写进提交说明。

### 11.7 基线侧

本机跑不了 Go，基线也没有可复现的混沌层。本稿不移植探针，所以不需要跨语言金样。§8.1 各条只登记为 mmorpg 待做候选，各附一个最小复现思路：

- C1：在 guild DB 2 造一个死区榜键；
- C5：让 Redis EXISTS 超时；
- H3：一张表放 21 行；
- H4：用 > 2^53 的 player_id；
- H8：单独一个 `;`；
- H11：用 `-player` 导入。

这些由 mmorpg 自己的 CI 验证。

### 11.8 每个子批的验收清单

- [ ] `./mvnw -B clean install` 与相关模块单测全过，有运行证据。
- [ ] 本子批涉及的 IT（`-Dxm.it.mysql` / `-Dxm.it.redis`）在本机跑过，有记录。
- [ ] 本子批的 robot 场景（§11.4）和回归在本机切片上全过；混沌子批附本机 C1 / C5 的输出。
- [ ] CI stack（以及 7.4b 起的 chaos）的运行号写进提交说明。
- [ ] PARITY 登记完成，有意差异按编号引用；roadmap 7.4 行更新；盘点勘误已改。

---

## 评审修订记录（2026-10-05，完整性评审）

评审方式：通读全文；回到基线 `26ceb70ca`（含 `git show` 读未检出文件）与 Java 工作区逐条核对引用；对照 5.1–5.5、6.1–6.4、7.1、7.2 规格找矛盾。抽查引用 40 余处：基线 `tools/data_consistency_check/main.go`（`:24-29`、`:96-153`、`:195-212`、`:277-364`、`:374-417`、`:428-485`、`:496-523`）、`tools/merge_zone/main.go:12`、`:113`、`guild_repo.go:185`、`go/db/cmd/verifier/main.go`（`:9-16`、`:107-124`、`:155-205`、`:248-252`、`:277-297`、`:315-319`、`:417-418`、`:458-465`）、`go/db/cmd/data_stress/main.go`（`:12-15`、`:152-155`、`:174-176`、`:209-215`、`:233-256`）、`key_ordered_consumer.go` 的 legacy-zero 分支、`robot/data_stress.go`（`:52-58`、`:144-152`、`:204-207`、`:281-299`）、`robot/logic/ai/{action,robot_ai,llm}.go`、`robot/config/config.go:442-447`、`:479-494`、`robot/metrics/stats.go:156`、`:188-206`、`robot/main.go`（`:222-247`、`:253-311`、`:428-470`）、`stress_summarize.ps1:161`、`debug_fetch/main.go`、`debug_import/main.go` 各处、`messagelimiter.json`、`tools/AGENTS.md:28`、`:65-75`（`chaos_test.ps1` 确实不在 HEAD 树）；Java 侧 `RedisKeys`、`xm-gateway-schema.sql`、`PlayerStore` / `PlayerMapper`、`SceneWorld`（`:560-584`、`:769-773`）、`MoveGuard`、`ChatService`、`ClientDispatcher`、`RunMode`、`TradeConfiguration`、`AdminAuthFilter`、`PersistedPlayerMapper`、`Inbox`、`RobotOptions`、`GateNode`、`StoragePlayerRepository`、`SceneMetrics` 与各 `*_tables.proto`。其余引用（含 `architecture.md` 按 HEAD 的行号）核对无误。

### 错误（已改）

1. **流水原因撞号**：`TX_GM_IMPORT = 1005` 与 6.3 规格的 `TX_BATTLE_REWARD = 1005`（`scene-battle-spec.md:531`、D23）冲突。改为 1006，§0.6、§3.7、§5.2、Q24、§8.2 第 19 条同步。
2. **DS5 判据会误报**：calm 下「fenced 增量为 0」不成立——离场不等在途的在线存盘，迟到的在线存盘被围栏拒是设计行为（`SceneWorld.java:96-97`），5 s 存盘间隔下必然出现。改为按 `op` 区分，`op=progress, result=fenced` 只打印；另加「没有非预期的 23 `{2017}`」。顺带把表格里未转义的 `|` 改掉（原行把表格拆成了 11 列）。
3. **C2 判据没有可观测量**：「续约失败被移除 ≥ 1」在 xm-scene 没有任何指标（`OwnerLeaseRenewer.java:116` 只打 WARN）。本批在 xm-scene 加无标签计数 `xm_scene_owner_leases_lost_total`（§0.6、§5.2、§7、T9），并注明 C2 需要 two-scenes。
4. **`own.ghost_presence` 复核间隔小于在线目录 TTL**：presence TTL 60 s（`PlayerPresenceDirectory.java:39`），gate 被杀后条目最多再活 60 s，35 s 复核会误报，CI 的 `--fail-on warn` 会因此判红。改为 65 s（§0.4 第 4 条、§1.4、§1.7、§6、§11.3）；DS6 在 chaos 下的 settle 同理改为 65 s。
5. **`ops.job_residue` 会对每个被拒的作业报 warn**：data-ops-spec 没要求 REJECTED / CANCELLED / FAILED 作业把 PLANNED 明细改成终态。改为只报「SUCCEEDED 仍有 PLANNED」与「`ops_active` 指向终态作业或心跳过期」，后者是真实的卡单（Q42）。
6. **`trade.seller` 的理由写错**：dev 播种先解析卖家归属区，卖家不存在就拒绝（`SeedListingService.java:101-103`），不会产生不存在的卖家。改正理由，并把 `trade_favorite.player_id` 的孤儿并入这一项。
7. **导入的 `state_conflict` 按重新编码的字节比较会误报**（map 字段序列化顺序无保证，§8.2 第 10 条自己就写了）。改为解析后按消息比较、比较前去掉 JSON 表达不了的未知字段。robot `player-data` 第 ③ 步相应改为删掉 `state.base64` 再改 json，并加一个 `state_conflict` 反例。
8. **`insertImportedPlayer`「新增写 SQL 只有一条 INSERT」不对**：它还要插账号；而这两条语句都已存在（`insertAccountIfAbsent`、`insertPlayer`）。改为纯组合、不新增写 SQL；同号判定改为事务内先 `selectById`——靠撞主键判会被 `createPlayer` 当成发号不变量破坏而抛 `IllegalStateException`（`PlayerStore.java:132-147`）。补 `account_full`、建行与写事务分属两个事务时的空号处理、「xm-data 也能建 player 行」这条边界扩张要写进 architecture §7。
9. **§11.3「4 MiB 状态的导出导入往返」与导入上限 1 000 000 字节矛盾**：改为上限内往返一致、4 MiB 能导出但导入回 `bundle_too_large`。
10. **DS3 在 chaos 下会误报**：被杀后读到的是最近一次在线存盘，可能落在本轮「已移动、未加币」之间。chaos 下允许位置领先钻石一轮。
11. **DS9 在 chaos 下不可能严格成立**：`kill -9` 丢掉 scene 审计队列与生产者缓冲，Redis 停摆时记录只进兜底日志（`architecture.md:199-205`）。chaos 下改为「不多出」；calm 下明确用 `xm_data_kafka_consumer_lag` 判积压归零。
12. **DS7 下界、DS10 等待只算了一个存盘周期**：在途跳过（`periodic_saves{result=in_flight}`）会再晚一个周期。改为 `2 × save-interval + 2 s`，存储积压推迟（`deferred`）时判 UNVERIFIED 而不是放宽。
13. **多副本 gauge 会卡在旧值**：只有执行的副本更新 gauge，别的副本永远停在上一次自己执行的结果，`max` 摘不掉旧的 block 告警。改为每个副本每 60 s 按 Redis 里最近一份报告刷新（§1.8、§6、§7、§8.2 第 6 条、T3）。
14. **调度线程布局会让锁过期**：「`ThreadPoolTaskScheduler` 2 个线程，一个跑检查一个续锁」——调度池并不按任务分线程。改为单线程调度器只做触发 / 续锁 / 刷新，检查在独立的单线程执行器上跑（§1.8、§5.4、T3）。
15. **盘点勘误的行号**：`data.md:353` → `:352`，`data.md:365` → `:364`（§0.5、§8.1），`tools.md:510` → `:511`；`contract-robot.md:380` 的不适用理由原文是「不走 Kafka 存盘链、无 verifier」，「无周期存盘」在 `:382`；补 `contract-robot.md:392`（54 / 37 早已实现）与 `tools.md:548` 提到的不存在的 `tools/Dev.java`。
16. **其他引用**：`MoveGuard` 的 12 m/s / 24 m 出自 `MoveGuard.java:29-31`（`MovementRules.java:13` 是 10 m/s 信任上限）；「连续 2 s 不上报会被截断」不准确，改为「单次位移超过额度被截断」；14018 是**入帮审批**时申请人缺行 / zone 0（`guild-spec.md:1513`），不是建帮；xm-robot 现有 24 个场景，不是 25；基线 stress 档的用处补上 `robot.e2e-http.yaml`、`robot.currency-crash.yaml`；`architecture.md` 的行号按 HEAD（工作区在漂移），开头加了口径说明。
17. **Q2 对 data-ops-spec Q15 的转述不对**：Q15 是回档帮会分歧检查选 Dubbo（读路径），不是「写路径走 Dubbo」。改写为两处取舍不同的理由。

### 缺失（已补）

1. **基线待做的「各表 zone 与归属区一致」**（`server-merge-gap-fixes.md:352`；C9 列了却没落进目录）：新增 `zone.guild_member_home`（block，入帮要求同区，`guild-spec.md:1049`）与 `zone.trade_listing_home`（warn）。
2. **PITR 的哨兵**：§1.8 把 PITR 列为事件后触发，却没有检查能发现「恢复后没抬高纪元」（`guild-economy-spec.md:426`）。新增 deep 检查 `assetop.seq_watermark`（Q40）。检查总数 24 → 27（a 组 11、b 组 16），§0.1、§0.4 第 19 条、§0.6、§7 同步。
3. **基线收尾的 58 Disconnect**（`main.go:355`、`login.go:278-287`）与 assign-gate 的 30 次重试（`main.go:522-533`）漏写；Java stress 收尾按 17 → 58 → 关连接（R6）。
4. **登录排队**：Java gateway 有登录排队、本机切片与 CI stack 都开着，`--count` 超过区服容量（缺省 5000）就会排队；原稿把 q_* 当成「Java 没有」填 0。补排队轮询、q_* 计数、爬坡闸门的 settle 窗口（Q41、T12、§11.4）。
5. **gate 非法包阈值**：超频与运行模式拒绝的 GM 指令都计非法包，50 个断连（`GateProperties.java:18`、`:45`）。补进 §4.7、§5.5 第 4 步（prod 下第一次 23 `{1006}` 后立即停发 37）、§8.2 第 7 条；stress 启动时按间隔与限频表预判、冲突退出 2。
6. **基线 cast_skill 的坐标轴错误**：Go 把 X / Z 当水平面、Y 填 0，服务器是 z-up（`mmorpg-client-contract-movement.md:26`）；Java 的 move / cast 一律在 x / y 平面上取值。
7. **cross-node 目标的来源**：客户端只能从 79 学到 scene_id；补落位办法（照 `CrossNodeScenario`）、找不到第二个频道时的 UNVERIFIED、用 `xm_scene_transfers_total` 佐证是否真的交出。
8. **CI 环境**：stack 的 gateway 限流开着（同一 bridge IP）、xm-data 导入要 `XM_RUN_MODE=dev` + `XM_DATA_OPS_ENABLED=true`（连带 `XM_DUBBO_SECRET`）、chaos job 两个 scene 都要设 5 s 存盘间隔；stack 的 calm 跑法不开 DS7 / DS10。
9. **导入受理的细节**：建行冲突（`name_conflict` / `player_conflict` / `account_full`）受理预检时回 409、执行中才撞上记作业 REJECTED（原稿把异步作业里的结果写成 HTTP 码）；请求体上限与 413、开发账号白名单的配置键（须与 xm-login 一致）、`zone_not_found` / `account_not_dev` / `name_invalid`、名字不套 xm-login 的表驱动规则的理由、GM_IMPORT 快照的 `zone_id` / `owner_epoch` 取值与它在 data-ops 保留期清单里的归类、导入前被转出的物品由账本差集拦下。
10. **related 导出的发现告警**需要显式忽略表（否则 `transaction_log`、`ops_job_player` 等会永远告警）；`guild_asset_op` 加进登记表；`guild_player_state` 的 player_id = 0 哨兵行要排除。
11. **区服目录行可被无条件删除**（`ZoneAdminController.java:114-119`）：写明这是 `zone.*` 要抓的情形、保护措施不在本批，并确认 robot `zones` 场景不会在 CI 留下 zone 孤儿。
12. **`StorageFaultInjectionTest` 的注入类型**：必须在真实提交后抛 `SQLRecoverableException`，否则走不到 `isTransient` 的重试路径（`StoragePlayerRepository.java:337-350`）。
13. 其他：`own.zombie` 复核要求同一 epoch；SCAN 在 Cluster 下逐主节点迭代、`own.zombie` 需要按键批量 GET；`DbClock` 让 H2 单测不依赖 `UNIX_TIMESTAMP`；配置键 `cron-time-zone`（不叫 `zone`）与共用的 `xm.data.max-players-per-account`；单文件 CLI 不带 JSON 库时的结果行约定；`--operator` 限 ASCII；统计行同时写 stderr（`stress_summarize.ps1` 读 stderr）；LLM 应答 64 KiB 上限与非回环端点警告；`data-stress` 不移植基线每轮的 AI 游玩窗口（X8 写明理由）；同图 63 在「当前频道排空且本节点无别的活频道」时会走远端（§0.4 第 14 条）。

### 核对过、未改动的判断

- 客户端契约不变的结论成立：本批只用已有消息号（`message_id.txt` 核对了 14 / 17 / 21 / 23 / 26 / 31 / 33 / 37 / 43 / 47 / 48 / 51 / 54 / 58 / 61 / 63 / 64 / 66 / 70 / 77 / 79 / 84 / 131 / 132 / 134 / 137），客户端可见面仍只有 2005 与 23 `{2017}`。
- 新增依赖为零：JDK HttpClient / `com.sun.net.httpserver`、Jackson、protobuf-java-util、Spring `CronTrigger` / `ThreadPoolTaskScheduler` 都已在用或属 Spring 自带，符合 AGENTS §2。
- `TX_GM_GRANT = 9`、`SNAPSHOT_GM_IMPORT = 1003`（无人占用）、`OPS_JOB_IMPORT = 7`（`ops_tables.proto` 当前到 6）、MessageLimiter 中 132 / 134 / 131 / 54 的阈值、Java 世界聊天不推送、聊天发言人每秒 5 条，均与代码一致。
