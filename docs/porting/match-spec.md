# 匹配（批次 6.4）移植统一规格：排队、凑单、开局、补签、评分、切磋、帮会活动开战、整队开战

> **基线**：mmorpg `26ceb70ca`（`D:\work\mmorpg`，稀疏克隆）。本稿用到的目录都已检出：`go/match/**`（含 `internal/team`、`internal/kafka`）、
> `proto/match`、`proto/contracts/kafka`、`cpp/libs/services/scene/battle`、`cpp/nodes/gate`、`go/client_rpc_router`、`robot/*.go`、`docs/design/**`。
> `bin/etc/*.yaml` 没有检出，本稿不依赖它。
> **Java**：HEAD `aa8b5b5` 加工作区。工作区里有 5.2 与 6.2 尚未提交的改动（`BattleNodeService`、`battle_control.proto`、`node_directory.proto` 的
> `BattleNodeInfo`、`DubboGroups.BATTLE_NODE`、`xm-battle` 的出站端口 `BattleResultSink`），本稿只依赖它们的接口形状。
> **来历**：本稿合并了三份分区稿（排队段；gather / 补签 / 评分 / 切磋 / 活动 / 整队；Java 落地映射）。分区稿之间的分歧都回到代码核对过，裁决列在 §0.5。
> 行号：基线相对 `D:\work\mmorpg`，Java 相对仓库根。本稿只读代码，没有改其它文件。

**路径缩写（基线）**

| 缩写 | 路径 |
|---|---|
| `join.go` / `cancel.go` / `status.go` | `go/match/internal/logic/` 下的 `joinqueuelogic.go` / `cancelqueuelogic.go` / `getqueuestatuslogic.go` |
| `queue.go` / `matcher.go` / `rating.go` / `gather.go` / `spectate.go` / `keys.go` | 同目录同名文件 |
| `chl.go` / `rbt.go` / `act.go` / `tb.go` | 同目录 `challengelogic.go` / `requestbattleticketlogic.go` / `activitybattlelogic.go` / `team_battle.go` |
| `tsvc.go` / `tstore.go` / `trules.go` | `go/match/internal/team/` 下的 `service.go` / `store.go` / `rules.go` |
| `consumer.go` / `cfg.go` / `errors.go` / `metrics.go` | `go/match/internal/kafka/result_consumer.go` / `internal/config/config.go` / `internal/constants/errors.go` / `internal/metrics/metrics.go` |
| `yaml` / `msvc.go` | `go/match/etc/match_service.yaml` / `go/match/match_service.go` |
| `pb.cpp` / `pb.h` | `cpp/libs/services/scene/battle/system/player_battle.{cpp,h}` |
| `ms.proto` / `mi.proto` / `me.proto` | `proto/match/match_service.proto` / `proto/match/match_internal.proto` / `proto/contracts/kafka/match_event.proto` |
| `bss.go` / `tss.go` | `robot/battle_smoke_scenario.go` / `robot/team_smoke_scenario.go` |

**路径缩写（Java）**：`bn-spec` = `docs/porting/battle-node-spec.md`；`ho-spec` = `docs/porting/scene-handoff-spec.md`；`ch-spec` = `docs/porting/scene-channels-spec.md`；
`dm-spec` = `docs/porting/dungeon-mirror-spec.md`；`engine-spec` = `docs/porting/battle-engine-spec.md`；`team-spec` = `docs/porting/team-spec.md`；`arch` = `docs/design/architecture.md`；
`inv` = `docs/porting/inventory/scene-manager-match.md`。

---

## 0 概览与范围（与 6.2 / 6.3 / 6.5 / 4.3 / 4.6 的边界）

### 0.1 结论速览

- **新进程 `xm-match`**：Spring Boot 非 Web 应用 + Dubbo Triple 提供方 + 只挂 actuator 与 dev 接口的管理 Tomcat。包 `com.game.match.*`；Dubbo 端口 20888，管理端口 18113，
  group `match`（§9.1）。gate 把 `MatchService` 的 10 个消息号转给它（148 / 151 / 152 / 153 / 154 / 156 / 157 / 163 / 164 / 179）。
- **客户端契约逐字节照搬**：消息号、应答形状、tip 码、`parameters[0]` 的中文串（半角逗号）、判定顺序、177 的 `expire_at_ms`、156 的 `expires_at_ms`、
  GetQueueStatus 的五态映射与恒为 0 的 `estimated_wait_seconds`、5V5 蛇形分队、站位顺序。客户端可见的有意差异都很窄（只在伪造请求、故障、过载或竞态下出现），
  逐条见 §13 的「客户端可见？」列。
- **排队与凑单**：语义逐条照搬（锚点 + 评分容差、256 前缀、32 个锚点、battle 池为空时暂停）。所有 match 键共用一个 hash tag `{match}`，票据和队列落在同一个 slot。
  基线因为跨 slot 才需要的三条自愈路径，在 Java 里变成单段 Lua 的原子操作；每段可变脚本都能被 Redisson 安全重发（§9.4）。
- **gather**：五个入口汇入同一条管线，步骤、补偿矩阵、各跳超时、matched 票据 TTL 公式（42 / 48 / 54 / 60 / 66 / 96 s）与基线逐值相同。
  每次 gather 跑在一个虚拟线程上，代码写成直线式，与基线逐段对照；另有在途上限。另修两个基线缺陷：PrepareBattle 结局不明的人也发 Cancel；battle 明确拒绝建房时不发 Destroy（§3.7）。
- **177 / 143 由 battle 发，match 不发**（6.2 已按在线目录寻址）。gather 对此的义务只有一条：建房**之前**写好落点记录，保证客户端收到 177 之后马上发 179 也能定位到房间。
- **179 补签**：契约逐字照搬。落点记录多存 battle 的实例号与 rpc 地址，补签先直拨记录里的地址。直拨建连失败（超时不算）、且目录里同号节点已换了实例时，才判房间已死、回 1005（§4.3）。
- **评分**：规则逐条照搬（K = 32、队伍取平均分、平局 0.5、回合打满按平局、只计 1V1 / 5V5、下限 0、两位小数、新号 1500）。
  存储从 Redis 两层幂等 Lua 改成 MySQL 一个事务（pbmysql 建表）。结果走 Java 自有 Kafka topic `xm-battle-result-g<代次>`（落实 6.2 Q12）。
  「回合打满」的阈值经核对**保持基线的 30 加按配置覆盖**，不按引擎回合上限推导（§5.5）。
- **切磋**：可见行为逐条照搬；发起与消费改成单 tag 下的原子 Lua，修掉「双击接受开两次 gather」的竞态（§6.3）。
- **帮会活动开战**：照基线实现提供方 `MatchInternalService`，另给 robot 加一个 dev 管理口。真正的调用方 xm-guild 随 4.6 接入；4.6 被 GuildActivity 表卡住（`PARITY.md:56`）。
- **整队开战**：xm-team 继续持有开战锁与编排。预检、建票、gather 经 xm-match 的类型化接口 `MatchTeamService` 完成；gather 结果靠一次长时间挂起的异步 RPC 回到 xm-team（§7.5）。
- **验收**：6.4 的组件测试可以在 6.3 之前完成（用 scene 的替身）；端到端 robot 依赖 6.3 提供的 `SceneBattleService`（§9.7.2）。

### 0.2 覆盖的盘点条目（`inv`）

| id | `inv` 行 | 本稿 | 留给 |
|---|---|---|---|
| match-queue | `:202-212` | 全部（§2.1–§2.4） | — |
| match-matcher | `:214-224` | 全部（§2.5–§2.9） | — |
| match-rating | `:226-236` | 全部（§5） | — |
| match-gather | `:238-248` | 全部（§3） | 开局前的观战清退归 6.5（本稿留钩子） |
| match-challenge | `:250-260` | 全部（§6） | — |
| match-battle-ticket | `:274-284` | 全部（§4） | — |
| match-activity-battle | `:286-296` | match 侧全部（§7.1–§7.2） | xm-guild 调用方与结果消费归 4.6 |
| team-match | `:334-344` | match 端口 + xm-team 接线（§7.3–§7.6） | — |
| match-spectate | `:262-272` | 只划边界：落点记录的形状供 6.5 复用、开局前清退的钩子、163 / 164 的临时应答（§8.6） | 6.5 |

### 0.3 与相邻批次的边界

| 批次 | 6.4 依赖它的 | 6.4 提供给它的 |
|---|---|---|
| **6.2 battle**（实现中） | `BattleNodeService` 与 `CreateBattleResult.admission`（`xm-api/src/main/java/com/game/api/BattleNodeService.java`、`xm-api/src/main/proto/xm/api/battle_control.proto:18-22`）；目录 `BattleNodeInfo{accepting, instance_id, rpc_host, rpc_port}`（`node_directory.proto:55-72`，键 `xm:nodes:battle:0`）；出站端口 `BattleResultSink`（`xm-battle/src/main/java/com/game/battle/port/BattleResultSink.java`） | `KafkaBattleResultSink`（§5.4）；`MatchBudgets.MAX_MATCHED_TTL_SECONDS = 96`，供 6.2 的确认补发窗口单测直接引用（`bn-spec` §10.4） |
| **6.3 scene**（规格 `docs/porting/scene-battle-spec.md`；2026-10-06 已落地，接口与键以那份的正文和实现记录为准） | `SceneBattleService.prepareBattle / cancelBattlePrepare`（`SceneBattleReply.status` 的映射见 §9.7.2）；scene 写战斗锁 `RedisKeys.battleLock(pid)`（6.3 定为一个 Hash），match 只经 `BattleLockReader` 做 EXISTS（咨询性读，权威在 scene，§9.7.2 第 4 条）；快照填好 `BattleRouting`；PREPARING 按 `prepare_deadline_ms` 由 reaper 解冻；确认之后拒绝取消（§3.5 的 R1–R6、§9.7.2） | `PrepareBattleRequest` 的 `deadline_ms` / `prepare_deadline_ms` 口径（§3.2） |
| **6.5 观战** | — | 落点记录 `xm:{match}:battle:<id>`（同时充当观战记录）；`GatherHooks.beforePrepare / onStarted`；163 / 164 的路由已接好，6.5 只替换处理器；`MatchBudgets.GATHER_CREATE_STAGE_WORST_MS`（22.2 s，WatchBattle 的「记录已写、房间可能还在建」窗口判定要用，`queue.go:340-342`）；`TicketStore` 的只读口（16014「匹配中无法观战」要读票据）。基线每轮凑单顺手清观战索引（`matcher.go:201-204`），Java 不挂在 matcher 上，由 6.5 自带定时任务 |
| **5.2 交接**（实现中，设计以 `ho-spec` §5 为准） | PrepareBattle 拒绝 `switchState ≠ NONE` 的玩家；交出流程拒绝战斗中的玩家（`ho-spec:106`）。match 不读 5.2 的任何新结构 | — |
| **5.1 频道** | scene 目录条目（`SceneNodeInfo` 的 rpc 地址）与位置记录的稳定字段 | — |
| **4.3 组队** | `TeamBattlePort`（`xm-team/src/main/java/com/game/team/match/TeamBattlePort.java:11-20`）；`TeamService.startTeamMatch`（`xm-team/src/main/java/com/game/team/service/TeamService.java:353-395`：第 385 行判人数，第 388-392 行按「端口未接线」fail-closed） | `MatchTeamService`（§7.5） |
| **4.6 帮会活动** | — | `MatchInternalService.startActivityBattle`（§7.2） |

### 0.4 兼容面

- **客户端可见，两版逐字节相同**：§8 列出的消息号、应答形状、tip 与 `parameters[0]`、判定顺序、推送与顺序、可见时限；5V5 分队与站位顺序。
- **服务端内部，按 Java 方式重做**：进程划分、传输（Dubbo Triple 代替 gRPC，Redis pub/sub 推送代替 Kafka gate-cmd）、键空间、Lua 脚本形态、评分存储（MySQL）、线程模型、指标。
- **不在客户端契约里、但 Java 必须守住的语义**（其它进程依赖）：matched TTL 公式与 scene / battle / team 的数值不等式（§10.4）；票据写一律带 ticket id 做 CAS；
  「建房之前写落点记录」；「CreateBattle 结局不明 → 先 Destroy 成功才解冻」；结果事件「只在真正打完时发」。

### 0.5 分区稿分歧与裁决（都回到代码或现状核对过）

| 分歧 | 分区稿说法 | 裁决与依据 |
|---|---|---|
| 票据键空间 | ① 票据并进 `{mq}`；② 整个 match 用一个 tag `{match}`；③ 票据保持按玩家分布 `xm:match:ticket:<pid>`、队列 `xm:match:{mq}:…` | **整个 match 一个 tag `{match}`，形如 `xm:{match}:ticket:<pid>`**。先例是组队的 `xm:{team}:…`（`RedisKeys.java:139-160`；`team-spec` D2）。票据与队列同 slot，回队首、入队、取消都能在一段 Lua 里完成；基线的跨 slot 自愈（`queue.go:563-622`、`join.go:234-269`）不再需要 |
| 凑单并发 | ① 全局领导者（照 §4.19）；③ 照基线按队列加锁 | **按队列加锁**（同 `matcher.go:250-267`）：失效间隙 10 s（领导者是 30 s），多实例可以并行处理不同队列。①担心 gauge 双写，改为「抢不到锁的实例把自己那份 gauge 置 0」解决（§11） |
| 补签判死 | ② 只直拨落点记录里的地址，不采纳 6.2 Q14；③ 采纳 Q14：目录里同号节点实例不符即回 1005 | **合并**：先直拨记录地址；只有直拨**建连失败**（超时不算）、且目录里同号节点已换实例才回 1005（§4.3）。理由：6.2 丢租约后进程仍活着、房间不作废（`bn-spec` §7.10、Q4），单看目录会把活着的房间判死；只直拨又拿不到「节点已被别的进程接手」的正面证据（`rbt.go:98-104`） |
| 整队开战的 ticket id | ② 由 xm-team 每人生成一个 UUID；③ 全队共用 lock_token | **xm-team 每人一个 UUID**。基线就是每人一个 UUID（`tb.go:106-109`）；而且 JoinQueue 回 16001 时会把现有 ticket id 回给客户端（`join.go:133-140`），用 lock_token 会把开战锁令牌外泄、全队同号，是客户端可见差异。③ 说「team 的票不回给客户端」与代码不符 |
| 整队的跨进程方法集 | ② 5 个方法（teamSize 单独一个）；③ 4 个方法（人数检查与预检合成一次） | **4 个方法**（§7.5）：人数检查与逐成员预检合成一次调用，顺序与基线 `tsvc.go:371-381` 一致，少一次往返 |
| runTeamGather 的超时 | ② 等于开战锁时长；③ 静态 120 s | **等于开战锁时长**（5 人 101 s）。比 gather 加补偿的最坏 91 s 长；EndMatch 的 110 s 单调截止从 gather 结束才起算（`tstore.go:454-461`），不受影响 |
| 回合打满的阈值 | ② ③ 都主张按引擎回合上限推导（Dungeon 行有 `time_limit` 时换算，否则 30） | **不采纳，保持基线**：基线 yaml 的覆盖表是注释掉的（`yaml:122-124`），有效阈值对所有配置恒为 30；而引擎对 Dungeon 1 的上限是 300 回合（`engine-spec:280-282`）。按引擎推导会让 Dungeon 1 上 30–299 回合分出的胜负从「平局」变成「胜负」，两版评分分叉。改法登记为两版同改的开放问题（§14 Q5） |
| 评分取整 | ② HALF_EVEN；③ HALF_UP | **HALF_EVEN，作用于 double 的精确十进制值**：基线用 Lua `string.format("%.2f", next)`（`rating.go:77`），即 C printf 对精确二进制值舍入，恰好打平（如 x.125）时取偶。`new BigDecimal(double).setScale(2, HALF_EVEN)` 与之逐位一致 |
| 结果消费失败 | ② 可恢复故障暂停重试、不跳过；③ 照基线重试 3 次后跳过 | **可恢复故障不跳过**（同 xm-data `ConsumerLoop`，`arch` §4.5）。基线跳过（`consumer.go:132-150`）是因为 Redis 两层写有「部分入账」状态；Java 一个事务恰好一次，没有理由丢一局 |
| challenger_name 的来源 | ② 给 `PlayerProfiles` 加 account 列的读法；③ 用 `SessionContext.account` | **`SessionContext.account`**：会话上下文本来就带已认证账号（`xm-api/src/main/proto/xm/api/client_call.proto:16-17`），就是发起者本人的账号，与基线读发起者会话的 `Account` 同值（`chl.go:157-161`） |
| 163 / 164 在 6.4 的临时应答 | ② 163 回 in-band 16017；③ 163 回 in-band 1006 | **163 回 in-band 1006「该功能当前不可用」，164 回空列表**：Java 对未做的号一律回 1006，先例是 4.6 的五个活动号（`arch` §4.17）与 136（`PARITY.md:48`）。16017 / 16018 是 6.5 真实语义的码，提前借用会混淆 |
| gather 在途上限 | ② 2048；③ 256 | **256**（可配）：与 battle 单节点的 `rpc-max-inflight` 同值（`bn-spec` §7.8）。正常 gather 是几十毫秒，256 只在下游卡住时才会碰到 |
| 落点记录写入 | ② 一次 Redisson 调用，外层 6.1 s 截止；③ 每次尝试 `get(3 s)`、共两次 | **一次 Redisson 调用，外层 6.1 s 截止**：Redisson 自带 1 次重试，最坏 4.2 s（`RedisProperties.java:14`；`arch:608`）；外层再自己重试会与 Redisson 的内部重发叠加 |
| 落点记录的键 | ② `xm:{match}:battle:<id>`；③ `xm:match:battle:<id>`；`bn-spec` §7.13 暂定 `xm:spectate:battle:<id>` | **`xm:{match}:battle:<id>`**：它首先服务补签，6.5 直接复用；随 tag 决定落在 `{match}` |
| topic 规格件放哪 | ② 放 xm-audit 的 topic 目录；③ 抽一个新库 xm-kafka | **放 xm-audit**（YAGNI）：`TopicSpec` 与 `AuditTopicInitializer.ensure` 本来就是通用的（`xm-audit/src/main/java/com/game/audit/TopicSpec.java:11`、`AuditTopicInitializer.java:34`）。第三类 topic 出现时再抽（§14 Q15） |
| 「battle 进程的 Kafka 发送需不需要执行器跳板」 | `bn-spec` §10.5 说不需要（生产者自带发送线程） | **需要**：`producer.send` 会因元数据或缓冲阻塞到 `max.block.ms`，与 scene 审计的结论相同（`arch` §4.5）。逻辑线程只投递，发送在专用线程上（§5.4）。本条更正 `bn-spec` §10.5 |
| rank 评分镜像与缺分补写 | ① 保留补写；③ 不移植补写 | **保留 rank ZSET，不移植补写**：snapshot 仍按基线只读 list 与 ZSET（脚本里不访问未声明的键）；缺分只可能来自人为改数据，按票据里的评分用、不回写（§2.7） |
| 无肇事者失败的热循环（B8） | ① 列为待定 | **采纳 2 s 退避**（§2.9、§14 Q12），只影响失败路径的时序 |

---

## 1 基线进程与数据

### 1.1 进程构成（`msvc.go:69-249`）

基线 `go/match` 是一个进程，承载：

| 组件 | 内容 | 出处 |
|---|---|---|
| gRPC `MatchService` | 客户端入口（经 gate 直连或路由服），10 个方法 | `msvc.go:148`；`ms.proto:21-46` |
| gRPC `MatchInternal` | 帮会活动开战；带会话 metadata 的调用回 PermissionDenied | `msvc.go:150-151`、`:355-382` |
| gRPC `ClientPlayerTeam` | 组队（与匹配同进程） | `msvc.go:142-152` |
| matcher 循环 | 每 500 ms 遍历队列注册集 | `msvc.go:97`；`matcher.go:101-115` |
| Kafka 结果消费者 | `match-results` → Elo；Kafka 不可达时每 30 s 后台重试，不拖垮排队 | `msvc.go:101-130`；`consumer.go` |
| etcd 节点镜像 | scene / battle 节点的 gRPC 地址 | `msvc.go:92-93` |
| 发号 | `BattleIDGen`（snowflake，带 Fence）：battle_id、challenge_id、team_id 同源 | `gather.go:199-210`；`chl.go:105-113` |

### 1.2 Redis 数据（基线）

| 键 | 类型 | 内容 | 出处 |
|---|---|---|---|
| `match:{mq}:index` | SET | 活跃队列 key 注册集（代替 SCAN） | `keys.go:57` |
| `match:{mq}:queue:<mode>:<config>` | LIST | player_id 十进制；RPUSH 入队尾，队首等得最久；回队首用 LPUSH | `keys.go:59-61` |
| `match:{mq}:rank:<mode>:<config>` | ZSET | 评分镜像：member = pid，score = 入队时的评分 | `keys.go:69-71` |
| `match:{mq}:lock:<mode>:<config>` | STRING | 凑单锁：值 = 实例 id，SET NX EX 10，按持有者释放 | `keys.go:63-65`；`matcher.go:26-30`、`:250-267` |
| `match:ticket:<pid>` | HASH | 票据（§1.3），不带 tag，按玩家分布 | `keys.go:131-133` |
| `match:rating:<pid>` | HASH | rating / games / updated_at_ms / recent_battles，无 TTL | `keys.go:87-89`；`rating.go:44-50` |
| `match:rating:applied:<battle_id>` | STRING | 入账标记（applying 加 Δ_A，或 done），TTL 7 天 | `keys.go:93-95`；`rating.go:276-312` |
| `challenge:<id>` / `challenge:target:<pid>` | HASH / STRING | 切磋记录 / 目标占坑，TTL 60 s | `keys.go:135-141` |
| `spectate:battle:<id>` | STRING | `SpectateBattleRecord{summary, battle_node_id}`，TTL 300 + 60 s；补签与观战共用 | `spectate.go:22-59` |
| 只读契约键 | — | `battle:lock:<pid>`（C++ scene 写）、`player:<pid>:location`（scene_manager 写）、`player:session:<pid>` | `keys.go:36-43` |

不变量：
- (I1) 非空队列一定在注册集里：入队、回队首都是一段 Lua 做 SADD + 写 list；剔除用 `pruneQueueScript`，只有 LLEN = 0 且 key 不存在才 SREM（`matcher.go:32-44`）。
- (I2) list 的成员集合等于 rank ZSET 的成员集合：每段改 list 的 Lua 同时改 ZSET。
- (I3) 每个玩家至多一张票：`ticketCreateScript` 是「EXISTS 不存在才写」（`queue.go:158-165`）。
- (I4) 票据写一律带 ticket id 做 CAS：matched、ready、回队首、删票、取消五处（`queue.go:120-150`）。
- (I5) 「取消太迟」由存储层保证：取消脚本要求 state 仍是 queued（`queue.go:144-150`）。

票据和队列**分属不同 slot**，没有跨 slot 原子性，所以基线有三条自愈路径：JoinQueue 用 LPOS 自愈孤儿票（`join.go:234-269`）；matcher 弹出后 CAS 失败的人出局（`matcher.go:303-330`）；回队首先 CAS 后 LPUSH，LPUSH 失败就删票（`queue.go:563-622`）。
另有旧格式队列迁移（每 60 s SCAN 搬家，`matcher.go:91-196`、`keys.go:97-110`）。

### 1.3 票据状态机与 TTL

```
(无) ──JoinQueue──► queued(6 h) ──弹组 + CAS──► matched(按组大小) ──gather 成功──► ready(60 s) ──TTL──► (无)
  ▲                    │ Cancel(CAS，要求 queued)      │ gather 失败 + requeueOnFail：续期 → CAS 回 queued(6 h) → LPUSH 回队首
  │                    ▼                             │ gather 失败且本人是肇事者，或 requeueOnFail = false → CAS 删
  └──────────────── (无) ◄────────────────────────────┘ 实例崩溃 → matched TTL 到期自灭
PVE_SOLO / 整队开战 / 活动开战：直接建 matched，不入队
```

- 字段（`queue.go:34-47`）：`ticket`、`mode`、`config`、`state`、`enqueued_at_ms`、`zone_id`、`queue_key`、`rating`（两位小数）、`team_id`（只有整队开战写）、`battle_id`（ready 时写）。
- matched TTL（`queue.go:367-380`）：`max(30, ⌈n × (RemoveObserver 3 s + Prepare 3 s) + gatherCreateStageWorst 22.2 s + rollback 3 s⌉ + 10)`，
  其中 `gatherCreateStageWorst = 2 × (落点记录写入最坏 6.1 s + CreateBattle 5 s)`（`queue.go:342`；`spectate.go:68-83`；`gather.go:26-30`）。
  - 1 / 2 / 3 / 4 / 5 / 10 人 → **42 / 48 / 54 / 60 / 66 / 96 s**。下限 30（`yaml:63-70` 的 `MatchedTicketTTLSeconds`）永远不起作用。
  - 同一个值作为 `prepare_deadline_ms` 下发给 scene（`gather.go:226`）。
- 补偿续期：进补偿之前，把幸存者的票续成 `已冻结人数 × 3 + 10` 秒（`queue.go:384-411`；`gather.go:169-175`）。
- 回队首（`queue.go:563-622`）：从末尾往前逐个「读票，id 一致才继续 → CAS 回 queued 并恢复 6 h → LPUSH + ZADD 原评分 + SADD」，LPUSH 失败就 CAS 删票。
  **顺序必须是先 CAS 再 LPUSH**（理由见 `queue.go:570-576`）。

### 1.4 消息号（`xm-proto/src/main/resources/contract/message_id.txt`，第 N+1 行是 N 号）

| 号 | 方法 | 请求 → 应答 | 契约行 |
|---|---|---|---|
| 157 | MatchServiceJoinQueue | `JoinQueueRequest` → `JoinQueueResponse{error_code=1, queue_ticket=2, error_message=3}` | `:158`；`ms.proto:64-79` |
| 148 | MatchServiceCancelQueue | `CancelQueueRequest{player_id=1, queue_ticket=2}` → **`Empty`** | `:149`；`ms.proto:24`、`:83-86` |
| 153 | MatchServiceGetQueueStatus | `GetQueueStatusRequest{player_id}` → `GetQueueStatusResponse{state=1, estimated_wait_seconds=2, queued_seconds=3}` | `:154`；`ms.proto:90-107` |
| 152 | MatchServiceChallengePlayer | → `ChallengePlayerResponse{challenge_id, error_message}` | `:153`；`ms.proto:30` |
| 151 | MatchServiceRespondChallenge | → `RespondChallengeResponse{error_message}` | `:152`；`ms.proto:31` |
| 156 | MatchServiceNotifyChallengeInvite | 推送 `ChallengeInviteS2C`（上行是空操作，回 Empty） | `:157`；`ms.proto:32`、`:136-143` |
| 154 | MatchServiceNotifyChallengeResult | 推送 `ChallengeResultS2C`（上行是空操作，回 Empty） | `:155`；`ms.proto:33`、`:145-149` |
| 163 | MatchServiceWatchBattle | → `WatchBattleResponse{battle_id, error_message}`（6.5） | `:164`；`ms.proto:38`、`:156-164` |
| 164 | MatchServiceListWatchableBattles | → `ListWatchableBattlesResponse`（6.5） | `:165`；`ms.proto:46` |
| 179 | MatchServiceRequestBattleTicket | `RequestBattleTicketRequest{battle_id=1}` → `RequestBattleTicketResponse{error_message=1, assignment=2}`；两条消息定义在 battle 包（`proto/battle/player_battle.proto:176-183`，Java 类在 `com.game.proto.battle`），不在 match 包 | `:180`；`ms.proto:40-45` |
| 177 / 143 | BattleClientPlayer 推送 | battle 发（`bn-spec` §4.3.4） | `:178` |

- `MatchMode`（`ms.proto:52-60`）：0 UNSPECIFIED、1 5V5、2 3V3、3 1V1、4 PVE_SOLO、5 PVE_TEAM、6 PVP_CHALLENGE。
- `QueueState`（`ms.proto:100-107`）：0 UNSPECIFIED、1 QUEUED、2 MATCHED、3 READY、4 ENTERING、5 NOT_QUEUED。**ENTERING 和 UNSPECIFIED 永远不会回给客户端**（`status.go:30-34`）。
- gate 限频：MessageLimiter 表里**没有任何一个** match 消息号（148 / 151 / 152 / 153 / 154 / 156 / 157 / 163 / 164 / 179 都不在，核对基线 `generated/tables/messagelimiter.json` 的 id 列），
  一律按缺省每会话每号每秒 3 条，超频回 1008 并计非法包（`PARITY.md:40`；`arch` §5）。客户端可见：179 的退避重试、robot 连发同号请求都要按这个节奏（§15.5）。
- 内部：`MatchInternal.StartActivityBattle`（`mi.proto:52-54`）没有标客户端服务，不在消息号表里。

---

## 2 排队与凑单

### 2.1 身份

- 基线取 session metadata 里的 player_id；**session 里为 0 时回落到请求体的 player_id**（`queue.go:199-208` `authoritativePlayerID`）。
  C++ gate 转发 gRPC 类消息时不要求会话已绑定玩家（`cpp/nodes/gate/handler/rpc/client_message_processor.cpp:740-741`、`:952-967`）。
  后果：会话还没进游戏时，客户端可以在请求体里填别人的 player_id，替他排队、取消、查状态、发起或应答切磋（缺陷 B1）。179 不受影响（只认 session，`rbt.go:71-78`）。
- 身份为 0 时：JoinQueue 回 16004「缺少玩家身份」（`join.go:47-55`）；CancelQueue 回成功（`cancel.go:40-42`）；GetQueueStatus 回 NOT_QUEUED（`status.go:36-41`）。
- **Java：身份只取 `SessionContext.player_id`**，请求体里的 player_id 一律忽略（M3）。

### 2.2 JoinQueue 157：判定顺序（顺序本身就是语义）

请求里只用 `mode` 和 `battle_config_id`；`map_config_id`、`zone_id` 被忽略，zone 取自位置记录；`party_member_ids` 已废弃，多于 1 人只打一条日志（`join.go:57-61`）。
拒绝时 `error_code` 与 `error_message.id` 同值，`parameters[0]` 是服务端写死的中文串，**逗号是半角**（`tipErr`，`join.go:18-21`）。

| # | 条件 | tip / error_code | parameters[0] | queue_ticket | 指标 outcome | 出处 |
|---|---|---|---|---|---|---|
| 1 | 身份为 0 | 16004 | `缺少玩家身份` | 空 | internal | `join.go:47-55` |
| 2a | PVE_TEAM 且人数配置为 0 | 16003 | `该副本未开放组队` | 空 | no_team_size | `:68-76` |
| 2b | 3V3 / PVP_CHALLENGE / UNSPECIFIED / 未知值 | 16002 | `该匹配模式未开放` | 空 | mode_not_open | `:86-92` |
| 3 | 读战斗锁出错（fail-closed） | 16004 | `服务器繁忙,请稍后再试` | 空 | internal | `:96-104` |
| 4 | 战斗锁存在 | 16000 | `战斗尚未结束,无法排队` | 空 | in_battle | `:105-111` |
| 5 | 读票出错 / 自愈出错 | 16004 | 同 3 | 空 | internal | `:114-132` |
| 6 | 已有票据，自愈后仍在途 | 16001 | `已在匹配队列中` | **现有 ticket id** | already_queued | `:133-140` |
| 7 | 读位置出错 | 16004 | 同 3 | 空 | internal | `:144-152` |
| 8 | 没有位置 | 16020 | `请先进入场景` | 空 | not_in_scene | `:153-159` |
| 9 | 建票出错 / 入队出错（入队失败先删票） | 16004 | 同 3 | 空 | internal | `:196-207`、`:273-282` |
| 10 | 并发重复建票，后到的一方 | 16001 | `已在匹配队列中` | 赢家的 id（读失败时为空） | already_queued | `:283-300` |
| ok | — | 0，不带 `error_message` | — | 新 id，UUIDv4 小写带连字符（`:162`） | ok | `:185-186`、`:211-212` |

- **人数**（`join.go:64-93`，与 matcher 的 `requiredPlayers` 同口径，`matcher.go:361-378`）：PVE_SOLO = 1；1V1 = 2；5V5 = 10（`gather.go:40`）；
  PVE_TEAM = `min(PveTeamSizeByConfigId[config], 5)`（`gather.go:36`）。基线配置只有 `"1": 5`（`yaml:91-92`）。
  DungeonTable 里 id 1 / 2 / 3 的 `max_team_size` 是 5 / 5 / 10（基线 `generated/tables/dungeon.json`，已核对；`dm-spec:358` 记作「该列无消费者，人数取 yaml」），所以**不能改成查表**，否则 config 2、3 会从 16003 变成可以排队。
- **battle_config_id 不做任何校验**：PVP 下任何值都能开局，每个不同的值是一条单独的队列（B2）。Java 照搬（客户端可见），只净化指标标签（§11）。
- **自愈**（`join.go:234-269`；整队开战预检也用，`tb.go:74-90`）：
  - 已有票据是 ready，且第 4 步已确认没有战斗锁 → 按 id CAS 删票，放行；
  - queued，但 `LPOS` 在它的 queue_key 里找不到这个人 → 按 id 删票（要求删时仍是 queued），放行；
  - 其它情况都算「在途」。
  - 注意：自愈发生在第 5 步，**先于**第 8 步读位置。即使随后回 16020，残留票也已经被清掉（GetQueueStatus 随之是 NOT_QUEUED）。Java 必须保持这个先后。
- **PVE_SOLO**：不入队，直接建 matched 票（TTL = 42 s），异步 gather，`requeueOnFail = false`（`join.go:171-187`）。gather 失败只删票，**不推送任何东西**，客户端只能看到 GetQueueStatus 变成 NOT_QUEUED。
- **其它模式**：先写票（queued，6 h），再执行「SADD 注册集 + ZADD 评分镜像 + RPUSH 队尾」这一段 Lua。评分在 Go 侧先读出，读失败按 1500，不拒绝（`join.go:193-207`；`rating.go:132-141`）。
- **Java 的第 7–8 步**：位置用严格读 `PlayerLocationDirectory.findHolderAsync`（§7.4 第 3 步同一读法）：`ERROR` → 第 7 行 16004；`MISSING` / `RECONNECT_LEASE` / `LOGGED_OUT` → 第 8 行 16020；
  `ONLINE` → 取记录里的 zone 写进票据。基线第 8 步只判「键不存在」（`join.go:153-159`），Java 的 `l` / `x` 对一个正在发请求的会话不会出现，归到 16020 不改变可见结果。
- **Java 的 PVE_SOLO 前置**（第 8 步之后、建票之前，不改变 1–8 步的先后）：发号租约无效（`MatchIds.leaseValid()` 为假）或 gather 在途许可已满 → 16004 `服务器繁忙,请稍后再试`，不建票。
  基线租约丢失时整个进程退出（`msvc.go:207-221`），客户端看到的是信封 1003；Java 进程不退出（M28），不加这一步就会「回 0、建票、gather 第 1 步秒败、静默删票」。过载见 M13。

### 2.3 CancelQueue 148（应答 Empty）

`cancel.go:38-89`：
1. 身份为 0 → 成功。
2. 读票出错 → gRPC 错误。路由服把它翻成带请求号的信封 `{1003}`（`go/client_rpc_router/internal/logic/forwardlogic.go:170-184`、`:226-231`）。
3. 没有票 → 成功。
4. `queue_ticket` 非空且不等于当前票 → 成功、不动。**空串表示「取消我当前那张票」**。
5. state ≠ queued（取消太迟）→ 成功、不动。
6. Lua `ticketCancelScript`：id 相等**且** state 仍是 queued 才 DEL（`queue.go:144-150`）。没删掉（读票之后被 matcher 弹走了）→ 成功；出错 → 信封 1003。
7. 出队：list 与 ZSET 用一段 Lua 摘掉（`queue.go:90-97`）。失败只记日志，残留由 matcher 的票据校验兜底。

**16005 kMatchTicketMismatch、16006 kMatchCancelTooLate 从来不会发出**：定义在 `errors.go:40-43`，全仓没有使用点；Empty 应答也放不下它们。Java 同样不发。

### 2.4 GetQueueStatus 153

`status.go:35-79`：
- 读票出错 → gRPC 错误 → 信封 1003。没有票 → `{state=5}`。
- 状态映射：queued → 1，matched → 2，ready → 3，不认识的值 → 5（`status.go:54-65`）。
- `queued_seconds = (now − enqueued_at_ms) / 1000`，三种状态都算，回队首时不重置（`status.go:67-72`；`requeueFront` 不改 enqueued_at）。
- **`estimated_wait_seconds` 恒为 0**（`status.go:74-79`；mmorpg backlog D-11 未决）。`inv:207` 写「估算」是错的。
- `inv` 说「客户端每 3 s 轮询一次」（`inv:212`）：本仓库与基线都没有客户端代码可核实；基线 robot 从不发 148 / 153。

### 2.5 凑单循环（`matcher.go:101-115`、`:201-217`）

- 每 500 ms 一轮（`yaml:49`）。ticker 驱动，轮与轮不重叠，单轮 panic 互相隔离（`go/shared/safego/safego.go:122-142`）。
  Go ticker 是固定频率（慢轮之后缓冲的那一拍立即触发下一轮）；Java 用 `scheduleWithFixedDelay`（慢轮之后再等 500 ms）。只在单轮超过 500 ms 时有差别，不可见。
- 每轮先清观战索引（6.5 的内容，`matcher.go:201-204`），再 `SMEMBERS index`，对每个 key 执行一次 `matchQueueOnce`。

### 2.6 单条队列（`matcher.go:220-337`）

1. 解析 key；人数为 0 → 告警并跳过，不动数据。
2. **battle 池为空 → 整条跳过**，排队保留，告警限频 10 s 一条（`:235-245`）。目的是避免「gather 秒败 → 回队首 → 500 ms 后再弹同一批人」的热循环。
3. SETNX 锁（10 s）。拿不到就跳过本轮，**连 `queue_depth` 也不上报**（D5c）。
4. `LLEN`，上报 `queue_depth{mode,config}`：深度 < 需要人数 → `starved_anchor_wait_seconds` 置 0；深度为 0 → 剔除空队列，并把深度 gauge 置 0（`:342-357`）。
5. 持锁期间循环调 `popGroup`，直到凑不出组：
   - 弹出后逐人 `setTicketMatched`（CAS，TTL = matched TTL）。CAS 返回 false 的人（弹出之后取消、或票已被替换）**出局，不带进 gather**。
   - 凑不满 → `requeueFront`（CAS 成功的人和 Redis 出错、状态未知的人）；这次如果出了 Redis 错误就结束本轮（`:303-330`）。
   - 凑满 → 起 goroutine 跑 `runGatherFn(…, requeueOnFail = true, tickets)`，**不占锁**（`:331-335`）。

### 2.7 popGroup（`matcher.go:508-622`；pickGroup `:628-689`）

```
snapshot = Lua(LRANGE 队列 0..255 + 逐个 ZSCORE)                         // queueScanLimit = 256（:49）
for 锚点 in snapshot 按 list 序（最多 32 个有效锚点，无效的不计次数）:      // maxAnchorAttempts = 32（:53）
    validate(锚点)：pid 非法 / 票缺失 / 非 queued → 从 list + ZSET 摘掉；
                    有 battle:lock → 删票并摘掉；镜像缺分 → 按票里的 rating 补写 ZADD；Redis 出错 → 结束本轮
    wait = floor((now − 锚点票.enqueued_at_ms) / 1000)，now ≤ enqueued 时取 0
    tol  = 非评分模式 ? ∞ : (wait ≥ 90 ? ∞ : min(1000, 100 + floor(wait / 5) × 100))
    cands = snapshot 中除锚点外的**全部**成员（锚点之前的也算）、未被丢弃、pid 合法、按 pid 去重；
            评分模式且有分时要求 |r − r锚| ≤ tol（r锚 取锚点票里的 rating，r 取 ZSET 分）；
            评分模式按 |分差| 升序，同分按 snapshot 下标升序（稳定排序）；非评分模式按 snapshot 序
    逐个 validate 候选，直到够 required 人（补写评分后再按 tol 复核一次）
    不够 → 本锚点继续等（队列不动），记 starvedWait；评分模式且 wait ≥ 45 s → 限频告警；换下一个锚点
    removed = Lua(逐个 LREM 0 + ZREM)，返回实际从 list 摘出的人
    removed < required（校验与摘出之间有人取消）→ 已摘出的按原相对顺序 LPUSH 回队首，本次结束
    观测 wait_seconds(锚点 wait)，评分模式另观测 group_rating_spread(max − min)
    返回 members = [锚点] + 按上面顺序选中的候选，以及各自的 ticket id
没有成组 → 上报 starved_anchor_wait_seconds = max(starvedWait, 0)
```

- 设计文档写的是候选取「锚点之后」（`docs/design/cross-zone-matchmaking.md:386`），`pickGroup` 的注释也这么写（`matcher.go:626`），**代码实际扫描全部成员**（`matcher.go:633-649`）。
  回队首会让 list 序 ≠ 入队序，两种写法的结果这时不同，**以代码为准**。
- 锚点不是只认队首：队首凑不到人时不阻塞后面的锚点（`TestHeadAnchorDoesNotBlockLaterAnchors`）。

### 2.8 容差曲线（`rating.go:154-233`；配置 `cfg.go:125-137`、`yaml:112-118`）

| 已等待 | 0–4 s | 5–9 s | 10–14 s | 20–24 s | 30–34 s | 40–44 s | ≥ 45 s | ≥ 90 s |
|---|---|---|---|---|---|---|---|---|
| tol | ±100 | ±200 | ±300 | ±500 | ±700 | ±900 | ±1000 | ∞（纯等待序） |

- 只有 1V1 / 5V5 是评分模式（`isRatedMode`，`rating.go:94-101`）；PVE_TEAM 的 tol 恒为 ∞。
- 新号评分缺省 1500（`rating.go:35`）。镜像分与票里的分都保留两位小数（`rating.go:104-118`）。配置值为 0 或漏配时按缺省值；终态兜底（90 s 后 ∞）不能关闭。

### 2.9 分队、成员顺序与交给 gather 之后的结局

- **分队**（`gather.go:627-661`）：
  - **5V5**：在 gather 时**重新读一次评分**（不用票里存的分；读失败按 1500），按评分降序稳定排序，蛇形分队，按名次是 `0,1,1,0,0,1,1,0,0,1`（`rating.go:241-263`）。
    proto 注释「前 5 后 5」（`ms.proto:54`）已经过时，只保留在 `teamIndexFor` 的无评分分支里（`gather.go:645-661`）。
  - **1V1 / 切磋**：成员下标就是队号，锚点（发起者）在 0 队。**PVE**：全员 0 队。
- **成员顺序**就是 PrepareBattle 与快照的顺序，而同队站位 = 同队内的插入顺序（`engine-spec:334-340` §1.8）；`actors` 的插入序还决定选目标、buff 结算的次序（`engine-spec:226`），
  即影响确定性战斗的结果。所以「锚点在前，候选按选中顺序」对客户端可见，Java 必须原样保留。
- **结局**（排队侧能看到的）：

| 结局 | 结果 | 出处 |
|---|---|---|
| 成功 | 逐人 `markTicketReady`（CAS，写 battle_id，60 s）；客户端收 177 → 143（battle 推） | `gather.go:381-385` |
| 失败且有肇事者（no_location / prepare_failed / fingerprint_mismatch） | 肇事者 CAS 删票，幸存者回队首 | `gather.go:177-188`、`:240-251` |
| 失败且无肇事者（internal / no_battle_node / index_failed / not_allocatable / create_failed） | **全员**回队首 | `gather.go:202-216`、`:302-306`、`:351-376` |
| CreateBattle 失败、DestroyBattle 也失败 | 不解冻、不回队，票留在 matched 等 TTL | `gather.go:363-374` |

- 无肇事者的失败会热循环（B8）：全员回队首，500 ms 后再弹出同一组，每次都白发一个 battle_id、把人冻结再解冻一遍。基线只对「battle 池为空」做了暂停（`matcher.go:235-245`）。
  **Java 采纳 2 s 退避**：无肇事者失败时，回队首的票带 `not_before_ms = Redis TIME + 2000`，选锚点与候选时跳过尚未到点的票（M11）。

---

## 3 开局管线（gather）

### 3.1 五个入口（`gather.go:102-158`）

| 入口 | 发起位置 | 模式 | 名单顺序（即站位顺序） | 票据 | 失败时的票据处理 | battle_id 来源 | 活动上下文 |
|---|---|---|---|---|---|---|---|
| 凑单弹组 | `matcher.go:331-335` | 1V1 / 5V5 / PVE_TEAM | 弹出顺序 | 有 | **requeueOnFail = true**：肇事者删票，其余按原序回队首 | gather 发号 | — |
| PVE_SOLO | `join.go:171-187` | PVE_SOLO | [本人] | 有（建票即 matched） | 删票 | gather 发号 | — |
| 切磋 | `chl.go:274-283` → `gather.go:110-113` | PVP_CHALLENGE | [发起者, 应战者] | **无**（withTickets = false） | 不涉及 | gather 发号 | — |
| 整队 | `tsvc.go:484-495` → `tb.go:162-164` → `gather.go:123-125` | PVE_TEAM | 队长在前，其余按 join_seq | 有（含 team_id） | 全员 CAS 删票 | gather 发号 | — |
| 活动 | `act.go:141-152` → `gather.go:136-141` | PVE_TEAM | 发起人在前 | 有（不含 team_id） | 全员 CAS 删票 | **预先发号**（同步回给 guild） | 深拷贝后原样透传 |

### 3.2 步骤（`gather.go:162-394`）

1. **battle_id**：`BattleIDGen.Generate()`；发号失败 → `internal`，不许用 0 顶替（`:199-210`）。活动入口用预发的号。
2. **选节点**：`BattleNodes.PickRandom()`，全局池、不分 zone、v1 纯随机；池为空 → `no_battle_node`，此时还没冻结任何人（`:212-217`）。
   - `deadline_ms = now + 300 × 1000`（`:219`），在任何 PrepareBattle **之前**算好，所以房间实际剩余时长 = 300 s − gather 已耗时间。它就是 177 的 `expire_at_ms`，客户端可见。
   - `prepare_deadline_ms = now + matchedTicketTTLFor(n) × 1000`（`:226`）。
3. **观战清退**（`:228-234`；`spectate.go:282-308`）：逐人做，尽力而为，失败不阻断开局。归 6.5。
4. **分队**（`:236-237`，§2.9）。
5. **逐人串行冻结**（`:240-260`；`preparePlayer` 在 `:399-435`）：读 `player:{id}:location` → 定位 scene 节点 → `PrepareBattle{player, battle, battle_node_id = 首选节点, deadline_ms, prepare_deadline_ms}`。
   - 位置缺失或节点查不到 → `no_location`；RPC 失败、tip ≠ 0、快照为空 → `prepare_failed`。两种情况**肇事者都是该玩家**。成功时把队号写进 `snapshot.team_index`。
6. 统计组内 zone 组成（`:262-264`、`:513-528`），只用于观测。
7. **配表指纹**（`:266-272`、`:462-507`）：缺省 warn；全员非空且两两一致才透传给 battle。enforce 下肇事者 = 与多数派不一致的人里弹出顺序最靠前者；多数派只在非空指纹里选，全员为空时取第一位。
8. **种子**：crypto/rand 生成 uint64（`:274-279`、`:665-671`）。`created_at_ms` 在此刻取（`:286`），battle 侧没人读它（`bn-spec` §4.3.1）。
9. **先写落点记录** `spectate:battle:{id}`（TTL 300 + 60 s），写不进去就不建房（`:308-321`；`spectate.go:106-145`）。
   - 原因：battle 在 CreateBattle 回包**之前**就已向 scene 发确认事件，scene 进入 FIGHTING 后拒绝取消（`pb.cpp:1297-1306`）。建房之后才发现写失败，解冻就基本无效了。
10. **CreateBattle**（`:323-377`），四种结局：
    - 节点级拒绝（`UNAVAILABLE` 且消息**精确等于** `battle_not_allocatable`，`:581-584`）→ 不发 DestroyBattle；先把记录改指向另一个**按 endpoint 排除**后选出的节点，再重试**一次**（`:324-350`）；
    - 最后一次仍是节点级拒绝 → `not_allocatable`；
    - 其它失败（含 battle 明确回 tip ≠ 0）→ 先 `DestroyBattle(reason = "gather_rollback")`：成功 → `create_failed`；**失败** → `create_failed_room_alive`：不解冻、不回队、保留记录，票据留在 matched 由 TTL 自愈（`:351-377`）。
11. **成功**：全员 `markTicketReady`（CAS，state = ready、写 battle_id，TTL 60 s，`queue.go:514-530`）→ `publishSpectateBattle`（同值补写记录、ZADD 活跃集合，尽力而为，`spectate.go:163-176`）→ 指标（`gather.go:381-393`）。

### 3.3 补偿矩阵（`fail` 闭包 `gather.go:168-197`；`failDropRecord` `:302-306`）

| outcome | 发生在 | 肇事者 | 解冻 | 票据 | 落点记录 | DestroyBattle |
|---|---|---|---|---|---|---|
| internal（发号） | 第 1 步 | 无 | 无人可解 | 凑单：全员回队首；其余：全删 | 未写 | — |
| no_battle_node | 第 2 步 | 无 | 同上 | 同上 | 未写 | — |
| no_location / prepare_failed | 第 5 步 | **该玩家** | 已冻结的人逐个 Cancel | 凑单：肇事者删票、其余回队首；其余：全删 | 未写 | — |
| fingerprint_mismatch（enforce） | 第 7 步 | 少数派 | 全员 Cancel | 同上 | 未写 | — |
| internal（种子） | 第 8 步 | 无 | 全员 Cancel | 回队首或全删 | 未写 | — |
| index_failed | 第 9 步、D82 改写 | 无 | 全员 Cancel | 回队首或全删 | 补偿之后删除 | 不发 |
| not_allocatable | 第 10 步 | 无 | 全员 Cancel | 回队首或全删 | 补偿之后删除 | 不发 |
| create_failed | 第 10 步 | 无 | 全员 Cancel | 回队首或全删 | 补偿之后删除 | 已发且成功 |
| create_failed_room_alive | 第 10 步 | 无 | **不解冻** | **不动** | **保留** | 已发但失败 |

补偿的固定顺序：① 凑单入口先给幸存者票据 CAS 续期 `已冻结人数 × 3 + 10` 秒（`queue.go:384-411`）；② 逐人 CancelBattlePrepare，每人 3 s，失败只记日志，由 scene 的 reaper 兜底（`gather.go:532-545`）；
③ 删票或回队首。

### 3.4 各跳超时与票据 TTL（Java 照搬为代码常量）

| 常量 | 值 | 出处 |
|---|---|---|
| prepareBattleTimeout / rollbackTimeout（Cancel、Destroy） | 3 s | `gather.go:26-30` |
| createBattleTimeout | 5 s | `gather.go:28` |
| removeObserverTimeout | 3 s | `spectate.go:33-35` |
| 落点记录写入最坏 spectateRecordWriteWorst | 2 × 3 s + 0.1 s = 6.1 s | `spectate.go:68-83` |
| gatherCreateStageWorst | 2 × (6.1 + 5) = 22.2 s | `queue.go:342` |
| matchedTicketTTLFor(n) | `max(30, ⌈n × 6 + 22.2 + 3⌉ + 10)` | `queue.go:367-380` |
| 补偿续期 | `已冻结人数 × 3 + 10` | `queue.go:384-386` |
| 开战锁 | matched + 补偿 + 10 | `tb.go:60-66` |
| ready 票 TTL | 60 s | `cfg.go:77-80`；`yaml:72` |
| 落点记录 TTL | 300 + 60 = 360 s | `spectate.go:53-59` |
| 战斗最长时限 | 300 s | `cfg.go:55-58`；`yaml:57` |

| n | matched TTL（= scene PREPARING 作废期限） | 补偿续期 | 开战锁（`tb.go:64-66`） | scene 备战锁最晚到期（期限剩余 + 60，`pb.h:111`） |
|---|---|---|---|---|
| 1 | 42 | 13 | 65 | gather 起点 + 102 |
| 2 | 48 | 16 | 74 | + 108 |
| 3 | 54 | 19 | 83 | + 114 |
| 4 | 60 | 22 | 92 | + 120 |
| 5 | 66 | 25 | **101** | + 126 |
| 10 | 96 | 40 | — | **+ 156** |

### 3.5 scene 侧 PrepareBattle / CancelBattlePrepare：match 依赖的语义（`pb.cpp:1105-1308`）

| # | 语义 | 出处 | 对 Java 6.3 的要求 |
|---|---|---|---|
| R1 | 参数非法 → 1005；玩家不在本节点 → **1004**；冻结中 / 已有在途战斗 / 0 血 → **1006**；快照构建失败 → 对应 tip；任何拒绝都**不留冻结痕迹** | `pb.cpp:1110-1164` | 加一条：`switchState ≠ NONE` 回 1006（`ho-spec:106`、`:559`；`dm-spec:121`） |
| R2 | 成功：挂上 PREPARING 与 deadline / prepare_deadline；`SET battle:lock = battle_id EX (prepare 剩余 + 60)`，外加伴生 ctx 键；回快照与 `table_fingerprint` | `pb.cpp:1166-1230` | 键与值形状归 6.3：`RedisKeys.battleLock(pid)`，锁与 ctx 合成一个 Hash（scene-battle-spec D2，取代 `bn-spec` §7.13 的两键预留），match 经 `BattleLockReader` 只做 EXISTS（§9.7.2 第 4 条）；快照填 `BattleRouting`，**gate 实例与 scene 实例都不能为空**，否则 battle 回 1005（`bn-spec` §4.3.3） |
| R3 | `prepare_deadline_ms = 0` 时才沿用 `deadline_ms` | `pb.cpp:1166-1169` | 照搬 |
| R4 | Cancel：玩家不在本节点时，按锁值条件清锁；ctx 已是 FIGHTING 则拒绝 | `pb.cpp:1236-1281` | Java 断线即移除实体（`ho-spec:105`），这条「不在本节点」分支是**常态**，必须实现 |
| R5 | Cancel：没有在途战斗 → 幂等；battle_id 不符 → 忽略；**已 FIGHTING → 拒绝**（防止「房间活着、玩家已解冻」） | `pb.cpp:1283-1306` | 照搬 |
| R6 | reaper：每 30 s 一次，PREPARING 按 prepare_deadline 解冻 | `pb.h:94-102` | 照搬（match 实例崩溃后靠它兜底） |

### 3.6 名单与票据之间没有原子性（基线就是这样，照搬）

- gather 不持凑单锁（`matcher.go:325-335`）。从弹组到推进 matched、从 CancelQueue 到 CAS，各个窗口都靠「带 ticket id 的 CAS」守住：迟到的 gather 写不脏玩家重排之后的新票。
- 切磋、整队、活动入口**不检查**参战者是否持有别的排队票据之外的东西。例：正在 1V1 排队的玩家可以接受切磋；切磋 gather 先冻结了他，之后他的排队组 PrepareBattle 被 scene 拒绝（已有在途战斗），他作为肇事者被删票。照搬。

### 3.7 基线怪癖与缺陷

**怪癖（照搬，PARITY 注明）**：
- **B-g1**：PrepareBattle 的传输失败也把该玩家判为肇事者并删票（`gather.go:242-250`）。scene 过载并不是玩家的错，但客户端看到的就是「被踢出队列」。
- **B-g2**：读位置失败（Redis 故障）同样按 `no_location` 处理、该玩家是肇事者（`gather.go:405-411`）。
- **B-g3**：PrepareBattle 里的 `battle_node_id` 是首选节点；D82 换节点之后它就过期了，只用于日志（`gather.go:331-333`）。
- **B-g4**：create_failed_room_alive 不解冻，玩家最长冻结到 prepare 期限；房间若真活着，冻结会被确认事件改成按正式期限计。

**缺陷（Java 修，PARITY 登记「mmorpg 待做（可选）」）**：
- **F-g1**：PrepareBattle **结局不明**（超时或断连）时，请求可能已在 scene 生效，但该玩家不在 `prepared` 列表里，补偿不给他发 Cancel（`gather.go:242-251`），他会冻结到 prepare 期限（最长 96 s）。
  Java 把「结局不明」的成员也放进 Cancel 列表；Cancel 幂等、按 battle_id 守护，总是安全的（M14）。
  残余：Cancel 先于迟到的 Prepare 在 scene 上执行时，Cancel 是空操作、随后的 Prepare 照样冻结，此人仍冻结到 prepare 期限——与基线同一上界，只是概率变小。
- **F-g2**：battle **明确拒绝**（tip ≠ 0，保证零副作用）时基线仍发 DestroyBattle；如果 Destroy 本身又失败，就走进 create_failed_room_alive，不解冻（`gather.go:358-375`）。
  Java 用 admission 枚举区分：`ADMITTED` 且 tip ≠ 0 时不发 Destroy，直接补偿（M15）。安全性：只有 NOT_ALLOCATABLE 才会换节点重试，它保证首选节点没建房，所以最后一次明确拒绝时任何节点上都没有这间房。

---

## 4 票据补签 179

### 4.1 基线判定（客户端可见，逐字照搬；`rbt.go:70-142`）

| 顺序 | 条件 | 应答 | 出处 |
|---|---|---|---|
| 1 | 会话没有绑定玩家（只认 session，不回落请求体） | `{16004, ["缺少玩家身份"]}` | `rbt.go:71-78` |
| 2 | 读记录出错（含反序列化失败） | `{16004, ["服务器繁忙,请稍后再试"]}` | `:81-89`；`spectate.go:205-218` |
| 3 | 记录不存在 | `{1005, ["该战斗不存在或已结束"]}` | `:90-96` |
| 4 | 节点未注册或身份歧义 | `{1003, ["战斗服务暂不可用"]}` | `:98-113` |
| 5 | IssueBattleTicket 的 RPC 失败（3 s） | `{1003, ["战斗服务暂不可用"]}` | `:115-126`、`:22` |
| 6 | battle 的裁决原样透传（成员：新签的 assignment；非成员或房间不在：1005；签不出：1003） | `{battle 的 error_message, assignment}` | `:128-141`；`bn-spec` §2.6 |

- 请求体只有 battle_id，player_id **只取会话身份**。
- 客户端**只把 1005 判为 BattleGone**，其它失败退避重试，每局最多补签 3 次（`bn-spec` §2.7）。所以 1005 只能用来表达「这局确实没了」。
- 补签出的票与原票**逐字节相同**（同一份 payload，HMAC 是确定性的，`bn-spec` §2.4）。
- 基线 robot 只注册了 179 的应答 handler（`robot/logic/handler/match_service_responses.go:25`），没有任何场景发送 179。

### 4.2 记录的生命周期（与 gather 绑定）

建房**之前**写入 → D82 换节点时改写 → 成功后同值补写 → 确认房间没建成时在补偿之后删除 → 只有 create_failed_room_alive 保留 → TTL 360 s。
「写不进去就不开局」的理由（`spectate.go:106-119`）：丢票之后（客户端重启 / 重登 / 握手被拒），补签是回到本局的唯一通路。

### 4.3 Java 设计

**落点记录**：Java 自有 proto `xm.match.BattlePlacement`（`xm-match/src/main/proto/xm/match/battle_placement.proto`），Redis 键 `xm:{match}:battle:<battle_id>`，
HASH 两个字段：`a` = attempt（十进制），`pb` = 消息字节（ByteArrayCodec，先例是组队记录的 `ver` + `pb`，`arch` §4.16）。TTL 360 s。

```proto
// Java 版内部（xm-match 拥有）：一场战斗的落点。gather 在建房之前写入；补签按它直拨 battle；6.5 观战复用同一条记录。
message BattlePlacement {
  uint64 battle_id = 1;
  uint32 battle_node_id = 2;
  string battle_instance_id = 3;   // BattleNodeInfo.instance_id（建房那一刻）
  string rpc_host = 4;             // BattleNodeService 直连地址（建房那一刻）
  uint32 rpc_port = 5;
  uint32 attempt = 6;              // 1 = 首选节点，2 = D82 改写；只收 attempt ≥ 已存值的写（单调）
  uint32 mode = 7;                 // MatchMode 数值
  uint32 battle_config_id = 8;
  repeated string player_names = 9;
  uint64 created_at_ms = 10;
  uint64 deadline_ms = 11;
}
```

- **写入**：一段 Lua，`attempt ≥ 已存值` 才写（单调）。基线要靠「成功后同值补写」纠正「超时后迟到落盘的第一次写」盖掉 D82 改写（`spectate.go:147-153`）；Java 的单调写直接排除这种乱序，成功后仍补写一次作纵深防御。
- **删除**：补偿之后无条件 DEL。超时那次迟到落盘把记录「复活」也无害：179 会直拨到 battle，battle 回 1005（房间不存在），语义正确，随 TTL 自清。
- **补签流程**（`ticket.BattleTicketReissue`，在 `match-worker` 上）：

| 顺序 | 条件 | 应答 |
|---|---|---|
| 1 | `SessionContext.player_id = 0` | `{16004, ["缺少玩家身份"]}` |
| 2 | 读记录出错或记录损坏 | `{16004, ["服务器繁忙,请稍后再试"]}` |
| 3 | 记录不存在 | `{1005, ["该战斗不存在或已结束"]}` |
| 4 | 按**记录里的地址与实例号**取 `NodeRpcClients<BattleNodeService>` 引用，`issueBattleTicket(battle_id, 会话 player_id)`，超时 3 s；调通了 | battle 的裁决原样透传 |
| 5 | **建连失败**（对端拒绝连接 / 地址不可达，请求确定没有送达），再读目录 `xm:nodes:battle:0`：同号节点存在且 `instance_id ≠ 记录的 battle_instance_id` | `{1005, ["该战斗不存在或已结束"]}`（正面证据：原进程的号已被别的进程接手，且原地址连不上） |
| 6 | 其余传输失败：**超时**（3 s）、连上之后断开、目录里没有该号、同实例、目录读失败 | `{1003, ["战斗服务暂不可用"]}` |

- **为什么先直拨**：battle 丢租约后关闸、停止发布目录，但不作废在打的房间（`bn-spec` §7.10、Q4）。按目录找必然找不到它（基线会回 1003），直拨仍能签出票。
  节点重启后占了同一个地址时，调用落到新进程，新进程回 1005，语义正确。
- **为什么不单看目录**：6.2 Q14 的「目录实例号 ≠ 记录实例号即判 1005」在「丢租约但仍活着、号被别的进程接手」时会误判。本稿的第 5 行只在直拨失败时才看目录，排除了这种误判（§14 Q6；6.2 规格的 Q14 应改写为本条）。
- **为什么超时不判死**：丢租约的 battle 恰好是「出了问题、可能很慢」的进程；3 s 超时加「号已被接手」并不能证明房间没了，而 1005 会让客户端永久放弃本局（`bn-spec` §2.7）。
  所以只有「请求确定没送达」的建连失败才参与判死，超时一律 1003，客户端退避后再补签。实现上按 Dubbo 异常的类别区分（建连失败 vs 调用超时），单测分别钉住。

---

## 5 评分与结果回流

### 5.1 基线规则（照搬）

1. **只计 1V1 / 5V5**（`rating.go:94-101`）；PVE、切磋、活动一律 `ignored`。
2. **outcome**：只认 SIDE_A_WIN = 1、SIDE_B_WIN = 0、DRAW = 0.5，其余忽略（`rating.go:401-412`）。
3. **必须恰好两支非空队伍**，队号只能是 0 / 1，否则忽略（`rating.go:508-528`）。
4. **回合打满按平局**：`total_rounds ≥ cap(config)` 的胜负结果按 0.5 结算，cap 缺省 30，可按 config 覆盖（`rating.go:418-427`；`cfg.go:139-149`、`:177-189`）。
   原因：引擎打满回合一律判 B 胜，这是 PVE 规则漏到了 PVP（`cfg.go:139-143`）。最后一回合真把人打死也按平局（与打满不可区分）。
5. **Elo**：读全员赛前评分，`E = 1 / (1 + 10^((B − A) / 400))`，`Δ_A = 32 × (S_A − E(avgA, avgB))`，`Δ_B = −Δ_A`，同队每人同一增量（`rating.go:327-341`、`:430-433`）。
6. **存储**：`max(0, 当前 + Δ)` 后按 `%.2f` 保留两位小数（`rating.go:56-89`）；新号 1500；读失败按 1500，不阻塞（`rating.go:132-150`）。
7. **幂等**：每局一个标记（applying 带 Δ_A → done，TTL 7 天）+ 每人最近 8 局 battle_id；逐人增量 Lua，写到一半失败由重投按标记里的 Δ_A 续写（`rating.go:276-312`、`:390-506`）。
8. **消费者**：topic `match-results`，key = battle_id，3 分区，保留 7 天；组 `match-rating`；从最早位点开始；每条处理完同步提交；handler 失败重试 3 次（间隔 1 s）后**跳过**；坏消息跳过并提交（`consumer.go:47-152`；`yaml:106-108`）。
9. **Kafka 不可达**：match 照常启动，每 30 s 后台重试（`msvc.go:101-130`）。
10. **事件来源**：battle 只在真正打完的局发（正常收尾、整场期限强制平局）；Destroy / Abort 不发（`me.proto:7-14`；`bn-spec` §4.9）。

评分不下发客户端，没有客户端契约。可见的只有间接效果：凑单时的评分容差与 5V5 分队。

### 5.2 Java 存储：MySQL 一个事务（替代两层 Lua）

pbmysql 建两张表（`xm-match/src/main/proto/xm/match/match_tables.proto`，Java 自有，不 import 同步来的 proto，先例 `xm-trade/src/main/proto/xm/trade/trade_tables.proto:22-68`）：

```proto
message MatchRatingRow {            // option table_name = "match_rating"，primary_key = "player_id"
  uint64 player_id = 1;
  int64  rating_centi = 2;          // 评分 × 100；1500.00 = 150000；下限 0
  uint32 games = 3;
  uint64 updated_at_ms = 4;
}
message MatchRatingAppliedRow {     // option table_name = "match_rating_applied"，primary_key = "battle_id"，index = "applied_at_ms"
  uint64 battle_id = 1;
  int32  match_mode = 2;
  int64  delta_a_centi = 3;         // 审计用
  uint64 applied_at_ms = 4;
}
```

**`RatingStore.apply(event)`**（只在消费线程上执行；连接会话级 RC、`innodb_lock_wait_timeout = 1`，同帮会，`arch` §4.17）：
1. 不计分的直接返回、不碰库：§5.1 第 1–3 条。第 4 条决定 S_A。
2. `BEGIN` → `INSERT match_rating_applied(...)`。撞 1062 → `duplicate`，回滚，不写任何东西。
3. 按无符号 player_id 升序 `INSERT … ON DUPLICATE KEY UPDATE player_id = player_id`，补出缺失的行（150000）。不用 `INSERT IGNORE`：MySQL 8.4 上它会与并发删除死锁（`arch` §4.20）。
4. 同样升序 `SELECT … FOR UPDATE` 读赛前评分。
5. Java 侧算 Δ：每人 `double cur = rating_centi / 100.0`，两队按名单顺序求平均，`Math.pow` 求期望，`next = max(0, cur + Δ)`，
   **`new BigDecimal(next).setScale(2, HALF_EVEN)`**（与 C printf `%.2f` 对精确二进制值的舍入逐位一致）→ `rating_centi`。
6. 逐人 `UPDATE rating_centi, games = games + 1, updated_at_ms`，提交。
7. 1213 死锁或锁等待超时 → 整笔重跑，最多 3 次；仍失败按「可恢复故障」交给消费者。

**同一 player_id 在事件里出现多次**（正常对局不会出现，防御坏数据）：基线的平均分按队伍名单原样求（重复的人算多次，`rating.go:430-432`），
写分时第二次出现会被 `recent_battles` 判为「本局已入账」跳过（`rating.go:66-71`、`:478-480`），所以每人只加一次、取**第一次出现**那一队的 Δ（A 队先于 B 队，`:487-488`）。
Java 照此：平均分按原名单求，第 3–6 步对 player_id 去重、保留首次出现的队别；不去重会对同一行 UPDATE 两次、`games` 加 2。

效果：一局要么全员落账，要么一个都不落账，没有基线的「部分入账 → 续写」状态（指标没有 `partial`）。同一玩家两局被并发入账时，行锁把它们串行化，第二局按更新后的分算 Δ；
基线靠「增量可交换」不丢分，但第二局用的是并发读到的旧分（M18，不可见）。`Math.pow` 与 Go `math.Pow` 在末位 ulp 上可能不同，极少数情况下会差 0.01 分（不可见）。

**清理**：每小时分批 `DELETE FROM match_rating_applied WHERE applied_at_ms < now − 7 d`（与 topic 保留期一致）。

**读接口**（给排队与 5V5 分队）：`RatingStore.loadOrDefault(pid)` / `loadAllOrDefault(pids)`：PK / IN 查询，语句超时 1 s，失败记 ERROR 回落 1500（同 `rating.go:132-150`）。
在有界的 `match-db` 平台线程池上执行；虚拟线程里不直接跑 JDBC（JDK 21 下驱动内部的 `synchronized` 会钉住载体线程），只在 future 上等结果。

### 5.3 消费者（xm-match 的 `BattleResultConsumer`）

- 一条专用线程、一个 KafkaConsumer，组 `xm-match-rating`，`auto.offset.reset = earliest`，关自动提交；每条处理成功才 `commitSync`（同 `consumer.go:82-88`）。
- 解不出的消息写毒丸日志后跳过并提交（同基线）；key 与 battle_id 不一致只打日志，以 payload 为准（同 `consumer.go:127-130`）。
- **可恢复的库故障不跳过**：暂停全部分区（继续 poll 保住组成员身份）、退避 1 s → 30 s 后重试原记录（同 xm-data 的 `ConsumerLoop`，`arch` §4.5；M19）。
  暂停期间 gauge `xm_match_rating_consumer_paused = 1`，持续暂停要告警（评分整体停更，排队照常）。
- 数据错误（SQLState 22 / 23，非 1062）写毒丸日志后跳过。
- Kafka 不可达时照常启动，每 30 s 后台重试（同基线）。开关 `xm.match.rating.enabled`，缺省 true。

### 5.4 结果 topic 与生产（落实 6.2 Q12）

| 项 | 取值 | 基线 |
|---|---|---|
| 名字 | `xm-battle-result-g<XM_BATTLE_RESULT_TOPIC_GENERATION>`（缺省 1，xm-battle 与 xm-match 必须一致） | `match-results`（`yaml:106`） |
| 分区 / 保留 | 3 / 7 天（显式声明，不继承 broker 默认）；分区数是契约，改分区数就升代次 | `yaml:107`；`consumer.go:51` |
| key / value | battle_id 的无符号十进制 / 契约 `contracts.kafka.BattleResultEvent` 的字节（`com.game.proto.contracts.kafka.BattleResultEvent`） | `me.proto:20-35` |
| 规格件 | `xm-audit` 新增 `BattleResultTopics`，复用 `TopicSpec` 与 `AuditTopicInitializer.ensure`；主人是 xm-match（校正保留期并读回），xm-battle 只核对分区数 | `EnsureTopics`（`consumer.go:70-76`） |
| 生产方 | xm-battle `KafkaBattleResultSink implements BattleResultSink`：逻辑线程只把不可变事件投进有界队列（1024），专用 `battle-result-out` 线程上 `producer.send`；生产者幂等，acks = all；队列满、未核对、发送失败、停服没发完，都把**完整字节**写进兜底日志 `xm.battle.result.fallback`，可回灌（模式同 `AuditPipeline`，`arch` §4.5）；dev 房间不调用 sink（`bn-spec` §7.9） | `room.cpp:1181-1211` |
| 活动局 | 走 `ActivityResultSink`（6.3 / 4.6：先落 `xm:battle:activity-result:<id>` 再发同一 topic，`bn-spec` §7.9）；评分侧因 PVE_TEAM 不计分自然忽略，xm-guild 用自己的消费组 `xm-guild-trial` | — |

### 5.5 回合打满的阈值（核对后的裁决）

- 基线有效值：`RatingDrawRoundCap = 30`，`RatingDrawRoundCapByConfigId` 在 yaml 里是注释掉的（`yaml:122-124`），所以对所有 config 都是 30（`cfg.go:177-189`）。
- 引擎的回合上限：Dungeon 行存在且 `time_limit > 0` 时按毫秒换算，否则 30（`xm-battle-engine/src/main/java/com/game/battle/engine/TurnBattleEngine.java:133-136`；`engine-spec:280-282`）；
  真表 Dungeon 1 / 3 是 300 回合，Dungeon 2 是 600 回合。
- 两者在 config = 0 或无 Dungeon 行时相同；在 config = 1 / 2 / 3 时不同。全自动的回合间隔 2 s，300 s 期限内最多约 150 回合，所以按引擎推导时这条规则在 Dungeon 1 上永远不触发，而基线在 30 回合起就判平。
- **Java 照搬基线**：`xm.match.rating.draw-round-cap: 30` + `xm.match.rating.draw-round-cap-by-config-id: {}`（缺省空，值为 0 视为未配置，同 `cfg.go:180-183`）。
  「按引擎推导」作为两版同改的开放问题（§14 Q5）。

---

## 6 切磋

### 6.1 客户端可见行为（照搬；所有 tip 的 `parameters[0]` 都是下表的中文串）

**152 ChallengePlayer**（`chl.go:53-185`）：

| 顺序 | 条件 | 结果 |
|---|---|---|
| 1 | 会话没有玩家 | 16004 `缺少玩家身份` |
| 2 | 目标为 0 或是自己 | 16007 `不能挑战自己` |
| 3 | 发起者有战斗锁（**读失败也算有**） | 16010 `战斗尚未结束,无法发起切磋` |
| 4 | 目标有战斗锁（读失败也算有） | 16009 `对方正在战斗中` |
| 5 | 读目标会话失败 | 16004 `服务器繁忙,请稍后再试` |
| 6 | 目标不在线 | 16008 `对方不在线` |
| 7 | 发号失败 | 16004 `服务器繁忙,请稍后再试` |
| 8 | 目标已有待应答的挑战（占坑失败；占坑出错回 16004） | 16011 `对方已有待处理的切磋邀请` |
| 9 | 写记录失败 | 清理后回 16004 `服务器繁忙,请稍后再试` |
| 10 | 推 156 失败（含目标刚好下线） | 清理后回 16004 `邀请发送失败,请稍后再试` |
| 11 | 成功 | `{challenge_id}`；目标收到 156 `{challenge_id, challenger_id, challenger_name, battle_config_id, expires_at_ms = now + 60 s}` |

**151 RespondChallenge**（`chl.go:190-289`）：

| 顺序 | 条件 | 结果 |
|---|---|---|
| 1 | 会话没有玩家 | 16004 `缺少玩家身份` |
| 2 | 读记录失败 | 16004 `服务器繁忙,请稍后再试` |
| 3 | 记录不存在 | 16012 `切磋邀请已过期` |
| 4 | 应答者不是目标 | 16013 `该邀请不是发给你的`（**不消费**记录） |
| 5 | 消费记录（一次性） | — |
| 6 | `now ≥ expires_at_ms` | 16012 `切磋邀请已过期` |
| 7 | 拒绝 | 只给发起者推 154 `{challenge_id, accepted = false, responder_id}`，应答为空 |
| 8 | 接受，但发起者有战斗锁（或读失败） | 给发起者推 154 false，应答 16012 `发起者已进入其它战斗` |
| 9 | 接受，但应答者有战斗锁（或读失败） | 给发起者推 154 false，应答 16010 `战斗尚未结束,无法应战` |
| 10 | 接受 | **先**给发起者、再给应答者各推 154 `{accepted = true}`，再异步 gather（mode = 6，名单 [发起者, 应战者]，不带票据）；gather 失败时再给双方各推一次 154 false；应答为空 |

其余细节：
- 154 推送失败：目标不在线只记 Info 并放弃，其它失败记 ERROR，都不影响应答（`chl.go:309-323`）。
- 156 / 154 作为上行是**空操作**，回 Empty（`chl.go:325-334`）。Java：回 Empty、tip 0，按 `arch:89-90` gate 不回包。
- `battle_config_id` 不校验，原样传给 CreateBattle。切磋**不检查排队票据**（§3.6），也不计分。
- `challenger_name` 是**账号名**，注释写的是「一期用账号名占位」（`chl.go:157-161`）。Java 取 `SessionContext.account`（同值，§0.5）。改成角色名属于客户端可见改动，要两版同改（§14 Q22）。

### 6.2 基线的竞态

`deleteChallengeRecord` 是两条无条件 DEL（`chl.go:291-302`），「读记录」与「消费」是分开的两步（`chl.go:199-228`）。两条接受请求都在删除之前读到记录时，会开两次 gather：
第二次 gather 的 PrepareBattle 被 scene 拒绝（已有在途战斗），补偿后给双方推 154 false，而第一局正在打。Java 修掉（M17）。

### 6.3 Java 设计

- **键**：`xm:{match}:challenge:<id>`（HASH：challenger、target、config、expires_at_ms）与 `xm:{match}:challenge-target:<pid>`（值 = challenge_id），同一 tag，TTL 60 s。
- **S_CH_INVITE**（KEYS 记录、占坑；ARGV id、字段、TTL）：占坑存在且值 = 本 id → 当作重放、成功；占坑存在 → pending；否则 `SET 占坑 = id PX ttl` + `HSET 记录` + `PEXPIRE 记录 ttl`，一次原子完成。
  基线是三条命令，中间失败要靠 cleanup（`chl.go:119-155`）。
- **S_CH_DEL**（清理）：DEL 记录；占坑**值等于本 id 时**才 DEL。
- **S_CH_CONSUME**（KEYS 记录、`challenge-target:<应答者>`、墓碑 `xm:{match}:challenge-done:<id>`；ARGV id、应答者、请求 nonce）：
  1. 墓碑存在且 nonce 相同 → 返回墓碑里的字段（Redisson 重发的重放）；
  2. 记录不存在 → expired；target ≠ 应答者 → not_target（不删）；
  3. 否则 DEL 记录；占坑值 = id 才 DEL；写墓碑（字段 + nonce，PX 60 s）；返回字段。
  - Java 侧先 HGETALL 读一次，按基线顺序回 16004 / 16012 / 16013（第 2–4 行），再执行 S_CH_CONSUME。并发的两条接受只有一条拿到字段，另一条回 16012 `切磋邀请已过期`（客户端只在竞态下可见）。
- **在线判定**：`xm:presence:<pid>` 存在 = 在线。基线只认会话状态 ONLINE；Java 的在线目录只在「在游戏里」时存在，断线租约期间不存在，语义一致（`arch` §4.3）。
- **推送**：`PlayerPushes.pushToPlayer(pid, MessageContent{156 或 154, 字节})`（`xm-discovery/src/main/java/com/game/discovery/presence/PlayerPushes.java:57`）。
  156 的结果 `OFFLINE` 或 `GATE_UNREACHABLE`，**或 stage 异常完成**（Redis 故障、在线目录条目损坏，`PlayerPushes.java:55-56`）→ S_CH_DEL 后回 16004 `邀请发送失败,请稍后再试`
  （基线读会话失败、不在线、Kafka 失败都走这一个出口，`chl.go:171-179`）；154 的推送结果只计数，异常完成同样只计数、不影响应答。推送在有界的 `match-push` 执行器上完成回调。
- **时钟**：`expires_at_ms` 由 S_CH_INVITE 用 Redis `TIME` 计算并写进记录与 156，151 的过期判定（第 6 行）用 S_CH_CONSUME 返回的 `TIME`；多实例之间不依赖本机时钟（同 §9.4「时间」）。
  基线两处都用 match 本机时钟（`chl.go:115-116`、`:229`），实例间偏差会让过期判定提前或推后；对客户端只在时钟偏斜时可见（并入 M7）。
- **发起者名字**：基线读发起者会话失败时 `challenger_name` 为空串、照常发起（`chl.go:157-161`）；Java 的 `SessionContext.account` 总有值，这条失败路径不存在。
- **gather 失败通知**：gather 在虚拟线程上跑，结束后按结果推 154 false。

---

## 7 帮会活动开战与整队开战

### 7.1 帮会活动开战：基线语义（`act.go`；`mi.proto`；业务拒绝一律放在 `reject` 字段）

1. **防御**：带会话 metadata 的调用 → gRPC PermissionDenied（`act.go:87-92`；`msvc.go:355-365`）。
2. **参数校验**，任一不过 → `INVALID_ARGUMENT`，offender = 0（`act.go:170-203`）：名单 1..5 人、不含 0、不重复；`battle_config_id ≠ 0`；上下文非空，kind 是已知值且不是 NONE（未知的 int 值同样拒绝）；
   guild_id / activity_id / period_key / guild_period_key 都不为 0；`initiator == members[0]`。
3. **逐人只读预检**，按名单顺序，第一个不满足的人即为结果（`act.go:209-263`）：

| 检查 | 失败结果 |
|---|---|
| 读会话出错 | INTERNAL（offender 0） |
| 会话不是 ONLINE | MEMBER_OFFLINE（pid） |
| 读战斗锁出错 | **INTERNAL**（offender 0） |
| 有战斗锁 | MEMBER_IN_BATTLE（pid） |
| 读位置出错 | INTERNAL |
| 没有位置 | MEMBER_NOT_READY（pid） |
| 调用方已放弃（ctx 结束） | INTERNAL |
| 读票据出错 | INTERNAL |
| 有票据：先按 JoinQueue 的规则自愈，自愈后仍在途 | MEMBER_NOT_READY（pid） |

4. **发号**：失败 → INTERNAL（`act.go:120-124`）。
5. **逐人建 matched 票**：PVE_TEAM，不入队，不写 team_id，每人一个 UUID，TTL = matchedTTL(n)。某人已有票 → MEMBER_NOT_READY（pid）；出错 → INTERNAL。
   两种失败都先回滚：回滚集合 = 已建成的人 ∪ 结果不明的人，用独立 3 s 预算逐个 CAS 删（`act.go:272-322`）。
6. **调用方已放弃** → 回滚票据，回 INTERNAL（`act.go:131-139`）。
7. **成功**：异步 gather（预发的号 + 深拷贝的上下文），同步回 `{battle_id}`（`act.go:141-158`）。gather 失败时全员删票、不回队列，这个 battle_id **不会产生结果事件**，由 guild 巡检判 EXPIRED（`mi.proto:46-49`）。
8. **指标** `match_activity_battle_total{kind, result}`，kind 只有 guild_trial / none / unknown 三种值（`act.go:340-348`）。
9. **调用方现状**：基线 go/guild 里没有任何 `StartActivityBattle` 的调用点（在 `go/` 下 grep，除 `go/match` 外为空）；对应批次 B6b-srv2 还没落地（`docs/design/guild-phase2/91-batches-and-codex.md:30`）。

### 7.2 帮会活动开战：Java 设计

```java
package com.game.api;

/** 帮会活动开战（基线 MatchInternal.StartActivityBattle，mi.proto:52-54）。group DubboGroups.MATCH，由 xm-match 提供。
 *  业务拒绝放在 reject 里；future 异常完成只表示传输失败（调用方按 INTERNAL 映射，并负责「战斗可能已开始」的补登记，归 4.6）。 */
public interface MatchInternalService {
    CompletableFuture<StartActivityBattleResponse> startActivityBattle(StartActivityBattleRequest request);
}
```

- 参数与返回值直接用同步来的契约类 `com.game.proto.match.StartActivityBattleRequest / Response`（先例 `AccountLoginService`，`arch` §4.1）。
- **隔离**：gate 只调 `ClientMessageService.handle`，`mi.proto` 没标客户端服务、不在消息号白名单，客户端够不到这个接口；加上调用方 MAC，不需要 PermissionDenied 分支（M2）。
- **kind 校验**：proto3 未知枚举读成 `UNRECOGNIZED`，必须用 `getKindValue()` 判断，NONE 与未知值都拒绝。
- **预检**：与整队开战共用 `MemberPrecheck`（§7.4），由各入口自己映射。Java 的「有位置」= 位置记录状态 `o` 且节点号 ≠ 0（与整队一致；基线活动入口不查节点号为空，`act.go:230-238`，整队入口查，`tsvc.go:441-451`；M27）。
- **建票**：一段 Lua `S_CREATE_GROUP` 原子建全员的 matched 票：任一人已有票（且不是同 id 的重放）→ 不写任何东西，返回第一个冲突者（名单顺序，与基线一致）。
  Redis 出错（结果不明）→ 用独立的 3 s 预算（不受请求截止约束，同 `act.go:45-46`）按本次的 ticket id 逐个 `S_DEL`，回 INTERNAL。原子建票让「部分建成再回滚」不复存在。
- **截止**：调用方把**剩余预算**（毫秒，发出时刻计）经 Dubbo 附件 `xm-budget-ms` 传入，提供方以收到时刻 + 预算作为本地截止（单调时钟）。
  不传绝对的 Unix 毫秒：跨主机比墙钟会把时钟偏差算进截止（提前判过期、或在调用方已放弃之后仍开局）。传输耗时让本地截止略晚于调用方的真实截止，这个窄窗口与基线
  「检查之后到响应送达之间」的窗口同性质，由 4.6 的结算补登记兜住（`act.go:132-134`）。读票据之前、建票之后各检查一次，已过期 → 回滚票据，回 INTERNAL（同 `act.go:131-139`、`:240-244`）。
  缺附件按请求预算 4500 ms 处理。`MatchTeamService.createTeamTickets` 同样带这个附件：请求到达时已过期就不写任何东西、回 INTERNAL，缩小「xm-team 判传输失败并回滚之后，迟到的建票才执行」的窗口。
- **dev 管理口**（Java 独有，供 robot 用）：`POST /admin/match/dev/activity-battle`，请求体是契约 `StartActivityBattleRequest` 的字节，应答是 Response 的字节；
  鉴权同 xm-trade 播种接口（`X-Xm-Admin-Token` + `X-Xm-Operator`，`arch` §4.20）；运行模式不是 dev / test 一律 403。
  注意它建的是**正常房间**（不是 6.2 的 dev 房间）：打完照常结算发奖、照常经 `ActivityResultSink` 发结果事件。所以 robot 填的 guild_id / activity_id 是不存在的值，
  4.6 接入后 xm-guild 的结果消费必须把「不认识的 (guild, activity)」当终态销账（删 `xm:battle:activity-result:<id>`），否则 battle 会按原字节重发到上限（`me.proto:11-14`）。

### 7.3 整队开战：基线分工（`tsvc.go:340-600`、`tb.go`）

- **team 包（编排）**：
  1. Refresh：惰性转让或过期清理有提交 → 整轮重来；
  2. `CheckMatchStart`：不是队长 → 4018；锁有效 → 4023；
  3. 人数（`trules.go:317-325`）：0 → 4027，成员数 > 上限 → 4028；
  4. roster：队长在前，其余按 join_seq（`trules.go:327-330`）；
  5. 预检：会话 → 战斗锁 → 位置 → 票据（`tsvc.go:417-463`）；
  6. 钉版本提交开战锁：提交报错 → 后台按 token 清锁、回 4030；未提交 → 按 token 同步确认一轮再整轮重来；最多 3 轮，耗尽回 4029；
  7. 用**锁内名单**建票；失败 → 回 4026[失败者]，后台 `finishMatch(false, tip = 4026[失败者])`（`tsvc.go:470-480`）。
     **「失败者」包括 Redis 出错的那个人**：逐人建票时出错（结果不明）也把该成员记为 failed、回滚后同样回 4026[该成员]（`tb.go:123-128`），不是 4030；
     Redis 整体不可用时落在名单第一个人（队长）身上；
  8. 回包：STARTING 视图；
  9. 后台 RunGather → `finishMatch(ok, tip = nil)`（gather 失败拿不到原因，`tsvc.go:484-494`）：清锁提交成功 → 全员（含发起人）收 MATCH_ENDED 或 MATCH_FAILED；没有提交 → `pushMatchView`。
- **端口**（`tsvc.go:40-60`）：`TeamSizeFor`、`MatchLockTTLSeconds`、`TicketBlocked`、`CreateMatchedTickets`、`RunGather`。
- **预检映射**：

| 情况 | 整队（`tsvc.go:417-463`） | 活动（`act.go:209-263`） |
|---|---|---|
| 读会话 / 位置 / 票据出错 | 4030 | INTERNAL |
| 不在线 | 4024[pid] | MEMBER_OFFLINE |
| **读战斗锁出错** | **4025[pid]**（按已锁处理） | **INTERNAL** |
| 有锁 | 4025[pid] | MEMBER_IN_BATTLE |
| 无位置 / 节点号为空 / 票据在途 | 4026[pid] | MEMBER_NOT_READY（节点号为空不判） |
| 建票：已有票据 | 4026[pid] | MEMBER_NOT_READY（pid） |
| 建票：Redis 出错（结果不明） | **4026[出错的那个人]**（`tb.go:123-128`） | **INTERNAL**（offender 0，`act.go:291-296`） |

- 两个入口的同步失败都附「同源视图」：预检的任何非 0 结果（含 4030）都带本轮 S_READ 的快照回包（`tsvc.go:375-378`）；建票失败经 `respond` 自由读（`tsvc.go:480`）。

### 7.4 预检的共享实现（xm-match `MemberPrecheck`）

按名单顺序逐人，每人固定顺序：
1. `xm:presence:<pid>` 存在 → 在线；读出错 → `PRESENCE_READ_FAILED`；不存在 → `OFFLINE`。
2. `BattleLockReader` 的 EXISTS（键 `RedisKeys.battleLock(pid)`，归 6.3）：读出错 → `LOCK_READ_FAILED`；存在 → `IN_BATTLE`。
3. `PlayerLocationDirectory.findHolderAsync`（严格读，`xm-discovery/src/main/java/com/game/discovery/location/PlayerLocationDirectory.java:289`）：
   出错 → `LOCATION_READ_FAILED`；状态不是 `o` 或节点号为 0 → `NO_LOCATION`；否则记下 zone。
4. 票据：读取并按 JoinQueue 的规则自愈（§2.2）；读或自愈出错 → `TICKET_READ_FAILED`；仍在途 → `TICKET_IN_FLIGHT`。

第 4 步的 ready 自愈依赖「已确认没有战斗锁」（`join.go:234-246`），顺序不可调换。返回 `OK(zones)` 或第一个失败者与原因，由入口按 §7.3 的映射表翻译。

### 7.5 跨进程端口：`MatchTeamService`（xm-api，group `match`，xm-match 提供、xm-team 调用）

```java
package com.game.api;

public interface MatchTeamService {
    /** 第 3 + 5 步：副本人数（未开放 / 超员）→ 逐成员预检（MemberPrecheck，首个失败即返回）；附开战锁时长 MatchBudgets.teamMatchLockSeconds(n)。 */
    CompletableFuture<TeamMatchCheckReply> checkTeamMatch(TeamMatchCheckRequest request);
    /** 第 7 步：按调用方给的每人 ticket id 原子建 matched 票（带 team_id，不入队）；冲突时不写任何东西，返回第一个冲突者。 */
    CompletableFuture<TeamTicketsReply> createTeamTickets(TeamTicketsRequest request);
    /** 建票回包丢失时的补偿：按 (pid, ticket id) 逐个 CAS 删票，幂等。 */
    CompletableFuture<Empty> releaseTeamTickets(TeamTicketsRelease request);
    /** 第 8 步：整队 gather（roster 原序、失败全员删票）；future 在 gather 结束时完成。调用方超时 = 开战锁时长。 */
    CompletableFuture<TeamGatherReply> runTeamGather(TeamGatherRequest request);
}
```

消息放在 Java 自有 proto `xm-api/src/main/proto/xm/api/match_control.proto`：
- `TeamMatchCheckRequest{battle_config_id, repeated roster}` → `TeamMatchCheckReply{result, offender, map<pid, zone>, lock_ttl_seconds}`，
  `result` 是中性枚举 `OK / DUNGEON_NOT_OPEN / SIZE_EXCEEDED / MEMBER_OFFLINE / MEMBER_IN_BATTLE / MEMBER_NOT_READY / INTERNAL`，由 xm-team 映射成 4027 / 4028 / 4024 / 4025 / 4026 / 4030（team 段 tip 只归 xm-team）。
  读战斗锁出错在这里映射成 `MEMBER_IN_BATTLE`（同 `tsvc.go:429-435`），活动入口则映射成 INTERNAL。
- `TeamTicketsRequest{battle_config_id, team_id, repeated roster, map<pid, zone>, map<pid, ticket_id>}` → `TeamTicketsReply{failed_player_id, expired}`。
  `failed_player_id`：0 = 全员建成；冲突时是名单序第一个已有别人票据的人；**xm-match 自己的 Redis 出错**（S_CREATE_GROUP 结果不明）时，match 先用独立 3 s 预算按本次 id 逐个 `S_DEL`，
  再回 `failed_player_id = roster[0]`。理由：基线逐人建票，Redis 出错的那个人就是 failed，回 4026[该成员]（`tb.go:123-128`；§7.3 映射表）；
  Redis 整体不可用时它就是第一个人。Java 原子建票没有「第几个人出错」，取 roster[0] 让最常见的情形逐字节相同（客户端可见：tip 与 MATCH_FAILED 的 tip 都是 4026[队长]）。
  请求到达时 `xm-budget-ms` 已过期 → 不写、回 `failed_player_id = roster[0]` 并带 `expired = true`，xm-team 按传输失败处理（4030）。
- `TeamGatherRequest{battle_config_id, team_id, repeated roster, map<pid, ticket_id>}` → `TeamGatherReply{ok, outcome}`。

设计要点：
- **预检整个放在 match**：基线按「每个成员依次查四项」交错进行、首个失败即返回。若在线检查留在 team、其余在 match，先后次序会变（例：成员 1 有在途票、成员 2 离线，基线回 4026[成员 1]，拆开做会回 4024[成员 2]）。
- **ticket id 由 xm-team 生成（每人一个 UUID）**：跨进程后 `createTeamTickets` 的回包可能丢失，xm-team 手里有 id 才能调 `releaseTeamTickets` 回滚；否则只能等 66 s 的 matched TTL。不用 lock_token（§0.5）。
- **开战锁时长由 match 回给 team**：实现就是 `MatchBudgets.teamMatchLockSeconds(n)`（xm-api 的纯函数），跨进程再传一次是为了防滚动升级时两边版本不一。
- **`runTeamGather` 是长时间挂起的异步 RPC**（Triple 异步，调用方超时 = `lock_ttl_seconds`，5 人 101 s，大于 gather + 补偿的最坏 91 s）。gather 不受调用方取消影响，提供方的 future 独立完成。
  - 超时按**每次调用**设置（Dubbo 调用级 `timeout` 附件，先例 `SceneAssetOpClients.java:128`），引用级的缺省超时（其余三个方法用的 3 s 级）不适用于它；
    Triple 连接的心跳 / 空闲断开周期必须容得下 101 s 无数据的挂起流（§15.3 有一条专门的回环测试）。
  - xm-team 进程退出：match 照样把 gather 跑完，锁自然过期，同基线「进程退出不等 EndMatch」（`team-spec` §5.2）。
  - xm-match 中途退出或网络分区：future 异常完成 → xm-team `finishMatch(false, tip = nil)`，推 MATCH_FAILED，计 `gather_unknown`。基线同进程一起死，什么都不推。
    残余风险：分区时 match 仍活着，可能先推 MATCH_FAILED、随后又到 177 / 143（M20）。
  - 这时 xm-team **不调** `releaseTeamTickets`：gather 可能仍在跑，票据由它自己收尾（成功置 ready、失败删票），对端已死时按 matched TTL（5 人 66 s）自愈；
    提前删票只会让在打的人 GetQueueStatus 掉成 NOT_QUEUED。`releaseTeamTickets` 只用于 `createTeamTickets` 结果不明、且还没发出 `runTeamGather` 的那一种情形。
- **为什么不让 match 回调 team**：会形成双向依赖，还要再做一个回调接口与重试。长挂 RPC 与基线「goroutine 同步跑 gather 再 finishMatch」语义一致（`tsvc.go:484-495`）。

### 7.6 xm-team 改动（接 `team-spec` §5.5 的清单）

- `TeamBattlePort` 扩成与 `MatchTeamService` 对应的四个方法：前三个阻塞、带 `Deadline`（xm-team 的请求本来就在 worker 上阻塞读 Redis）；`runTeamGather` 返回 `CompletionStage`、不阻塞 worker
  （第 5 步挂回调）。实现类 `MatchTeamBattle`（Dubbo 客户端，`retries = 0`，每次调用带 `xm-budget-ms`），删除 `NoTeamBattle`
  （`xm-team/src/main/java/com/game/team/TeamConfiguration.java:155`）。
- `TeamService.startTeamMatch` 用下面的流程替换第 385-392 行（人数检查与 fail-closed）：
  1. `checkTeamMatch(config, MatchRoster(rec))`：非 OK → 映射后的 tip + **同源视图**；传输失败或超出请求预算 → 4030 + **同源视图**
     （基线预检的所有非 0 结果、含读 Redis 出错的 4030，都带本轮快照回包，`tsvc.go:375-378`；4027 / 4028 带视图，同现状）；
  2. `CommitMatchLock`：`expire = snap.nowMs + lock_ttl_seconds × 1000`；提交报错 → 后台按 token 清锁、回 4030；未提交 → 按 token 同步确认一轮后整轮重来；
  3. `createTeamTickets`：`failed ≠ 0` 且 `expired = false` → 回 4026[failed]（`respond` 自由读），后台 `finishMatch(false, tip{4026, [failed]})`——含 match 侧 Redis 出错的情形（`failed = roster[0]`，§7.5），与基线可见结果相同；
     **传输失败或 `expired = true`**（基线没有这个故障面）→ 后台先 `releaseTeamTickets` 再 `finishMatch(false, tip = nil)`，回 4030；
  4. 回 STARTING 视图（加锁那次提交构建的）；
  5. 拿到 `runTeamGather` 的 future 后挂回调，在有界执行器 `team-match-end` 上记 `xm_team_matches_total{success | gather_failed | gather_unknown}` 并执行 `finishMatch(ok, tip = nil)`。
- EndMatch 的 110 s 单调截止与退避、`pushMatchView`、`releaseLockInBackground`、`settleUnconfirmedLock` 按基线移植（`tsvc.go:501-600`、`tstore.go:452-561`）。
- 视图里的 `in_battle`（`team-spec` D10）由 6.3 改为 `BattleLockReader` 批量 EXISTS（scene-battle-spec 的改动表已列），不在本批。（6.3 已做，`team-spec` D10 已收口；xm-team 的 `TeamConfiguration` 里已有 `BattleLockReader` bean，整队开战预检若要在 xm-team 一侧读锁可以直接用。）

---

## 8 客户端可见行为

### 8.1 Java 的应答规则（gate 侧按 `arch:89-90`）

| 号 | 正常 | 会话没绑定玩家 | 请求体解析失败 | 依赖故障 | 工作池满 / 排队超预算 |
|---|---|---|---|---|---|
| 157 | 应答体（§2.2） | in-band `error_code = 16004`，`{16004, ["缺少玩家身份"]}` | 信封 1003 | in-band 16004 `服务器繁忙,请稍后再试`（同基线） | in-band 16004 `服务器繁忙,请稍后再试` |
| 148 | **不回包**（Empty、tip 0；M4） | 不回包 | 信封 1003 | 信封 1003（同基线 gRPC 错误） | 信封 1003 |
| 153 | 应答体（§2.4） | `{state = 5}` | 信封 1003 | 信封 1003 | 信封 1003 |
| 152 / 151 | 应答体（§6.1） | in-band 16004 `缺少玩家身份` | 信封 1003 | in-band 16004 `服务器繁忙,请稍后再试` | in-band 16004 `服务器繁忙,请稍后再试` |
| 179 | 应答体（§4.3） | in-band 16004 `缺少玩家身份` | 信封 1003 | §4.3 表 | in-band 16004 `服务器繁忙,请稍后再试` |
| 156 / 154 上行 | Empty，不回包 | 同左 | 信封 1003 | — | — |
| 163（6.4 临时） | in-band `error_message{1006}`（M22） | 同左 | 信封 1003 | — | — |
| 164（6.4 临时） | 空列表 | 同左 | 信封 1003 | — | — |

- 信封 1003 = `MessageContent{message_id, id = 请求号, error_message{1003}}`；gate 调 match 失败或超时同样回它（`arch` §4.1；同基线路由服 `forwardlogic.go:170-184`）。
- 156 / 154 / 163 / 164 不涉及 I/O，直接在 Dubbo 线程上回，不进工作池。
- 「过载」列是 Java 独有的出口（基线没有工作池，过载表现为 zrpc 5 s 超时 → 信封 1003）：有 in-band 错误字段的 157 / 152 / 151 / 179 回 in-band 16004，
  只能用信封的 148 / 153 回信封 1003（M29）。先例：xm-guild 过载回 in-band 14021（`arch` §4.17）。
- 148 的「不回包」：基线缺省是 gate 直连模式（`cpp/nodes/gate/gate_router_mode.h:29`，环境变量不设即 direct），直连模式按应答类型反查消息号，`Empty` 会被映射到最后登记的那个以 Empty 应答的方法号、且不带请求号（`cpp/nodes/gate/main.cpp:270-313`），
  客户端在基线缺省模式下本来就收不到与 148 对得上的回包；路由模式回 148 空包（`forwardlogic.go:186-191`）。Java 的「不回包」与缺省模式在客户端看来一致（B6、M4）。

### 8.2 tip 全表

数值来自 `xm-table/src/main/proto/tip/match_error_tip.proto:10-52`，表内文案来自 `config-data/tables/tip_text.json:66-86`。客户端按 id 查表内文案，`parameters[0]` 另带服务端的中文说明。

| id | 名 | 表内文案 | 本批何时发 |
|---|---|---|---|
| 16000 | kMatchInBattle | 你正在战斗中 | JoinQueue 第 4 步 |
| 16001 | kMatchAlreadyQueued | 已在匹配队列中 | JoinQueue 第 6、10 步，带 queue_ticket |
| 16002 | kMatchModeNotOpen | 该匹配模式暂未开放 | JoinQueue 第 2b 步 |
| 16003 | kMatchTeamSizeNotConfigured | 队伍人数未配置 | JoinQueue 第 2a 步 |
| 16004 | kMatchInternal | 服务繁忙，请稍后重试 | 身份缺失、依赖故障、过载、邀请发送失败、179 读记录失败 |
| 16005 / 16006 | TicketMismatch / CancelTooLate | — | **永不发出** |
| 16007–16013 | 切磋七个码 | 见表 | §6.1 |
| 16014–16019 | 观战 | — | 6.5 |
| 16020 | kMatchNotInScene | 请先进入场景 | JoinQueue 第 8 步 |
| 1003 | kServiceUnavailable | 服务不可用 | 信封；179 的「战斗服务暂不可用」 |
| 1005 | kInvalidParameter | 参数无效 | 179 的「该战斗不存在或已结束」，以及 battle 透传 |
| 1006 | kFeatureUnavailable | 该功能当前不可用 | 163（6.4 临时） |
| 4018 / 4023–4030 | 组队段 | — | 211（xm-team 发，§7.3） |

### 8.3 推送与顺序

| 推送 | 发送方 | 顺序与条件 |
|---|---|---|
| 177 `BattleAssignedS2C` → 143 | battle | 建房成功后按 player_id 升序逐人：177 `{role = PARTICIPANT, expire_at_ms = deadline}` → 本人视角 143（一条 `GatePush.MessageBatch`，`bn-spec` §7.7）。幂等命中不重推（`bn-spec` §10.2 B7） |
| 156 | match | 发起成功时推给目标 |
| 154 | match | 拒绝：只推发起者；接受：先发起者后应答者各一条，**在 gather 开始之前**，实际几乎总早于 177；gather 失败再各推一次 false |
| 213 MATCH_STARTED / ENDED / FAILED | xm-team | 除发起人外的成员先收 MATCH_STARTED；gather 成功后全员收 MATCH_ENDED（通常早于战斗结束，`tss.go:15-17`）；失败收 MATCH_FAILED（建票失败带 tip 4026[pid]，gather 失败不带 tip） |

- match 推进 ready 在 CreateBattle 回包之后，所以客户端可能先收到 177 / 143、后看到 GetQueueStatus = READY。照搬。
- 推送都是至多一次。177 / 143 丢了，靠 6.3 的 144 加上 179 补签恢复。

### 8.4 客户端可见的时限

| 时限 | 值 | 出处 |
|---|---|---|
| 177 `expire_at_ms` | gather 起点 + 300 s | `gather.go:219` |
| 156 `expires_at_ms` | 发起时刻 + 60 s | `chl.go:115-116`；`yaml:60` |
| QUEUED 最长保持 | 6 h（之后票据过期，Status 回 NOT_QUEUED） | `yaml:63` |
| MATCHED 最长保持（match 实例崩溃时） | 42 / 48 / 54 / 60 / 66 / 96 s | `queue.go:367-380` |
| READY 窗口 | 60 s | `yaml:72` |
| 开战锁（故障时 4023 持续多久） | 5 人 101 s | `tb.go:64-66` |
| 凑单节奏 | 500 ms 一轮；容差曲线 §2.8 | `yaml:49`、`:112-118` |
| match 请求超时 | 5000 ms（超时 → 信封 1003） | `yaml:3` |

### 8.5 失败时客户端看到什么

| 入口 | 可见结果 |
|---|---|
| 凑单 | GetQueueStatus 回到 QUEUED（幸存者）或 NOT_QUEUED（肇事者） |
| PVE_SOLO | NOT_QUEUED，无推送 |
| 切磋 | 双方各再收一次 154 accepted = false |
| 整队 | 213 MATCH_FAILED，不带 tip |
| 活动 | 只在 guild 侧可见（巡检判 EXPIRED） |

### 8.6 6.4 期间的临时行为

- 163 WatchBattle → in-band `error_message{1006}`；164 ListWatchableBattles → 空列表（合法的「当前没有可观战的战斗」）。6.5 替换处理器（M22）。
- 开局前的观战清退是空钩子（6.4 还没有观众）。matched TTL 公式里每人 3 s 的 RemoveObserver 项**保留**，数值不变。

---

## 9 Java 设计

### 9.1 进程、模块、包

| 项 | 内容 |
|---|---|
| 新进程 | **xm-match**：Spring Boot 非 Web 应用 + Dubbo Triple 提供方 + 管理 Tomcat（同 xm-team / xm-trade，`arch` §11） |
| 端口 | Dubbo **20888**（`XM_MATCH_RPC_PORT`；20881–20887 已占用，`tools/local/start-slice.sh:84-90`）；管理 **18113**（`SERVER_PORT`；18101–18112 已占用，18114 是 xm-scene-2，`start-slice.sh:15`） |
| Dubbo group | `DubboGroups.MATCH = "match"`：`ClientMessageService` 的 group 等于 proto 一级目录（`xm-api/src/main/java/com/game/api/DubboGroups.java:3-6`）；`MatchTeamService`、`MatchInternalService` 同 group（先例 `GUILD` 下的 `GuildInternalService`） |
| 发号 | `NodeTypes.MATCH = "match"`，作用域 0，作雪花 worker：battle_id 与 challenge_id 同源（基线还包括 team_id，Java 的 team_id 归 xm-team 的 `NodeTypes.TEAM`；M23）。**battle_id 必须用时间在高位的雪花号（随时间递增）**：scene 的待结算记录每局一个字段、进场恢复按 battle_id 无符号升序应用并以此当作时间顺序（气血是终值，顺序就是语义；scene-battle-spec D13、§10.4），基线同样是 match 节点的 snowflake（`gather.go:83`、`:202-209`）。换成随机号或把时间放低位都会破坏这条局序。ticket id 用 `UUID.randomUUID().toString()`，格式同基线 |
| 依赖 | xm-api、xm-discovery、xm-common、xm-proto、xm-table、xm-pbmysql、xm-audit（TopicSpec）、kafka-clients、Redisson、Dubbo。**没有新的第三方依赖**，`tech-stack.md` 不改 |
| 共享常量 | `com.game.api.match.MatchBudgets`（xm-api，纯函数）：各跳超时、`GATHER_CREATE_STAGE_WORST_MS = 22 200`、`matchedTicketTtlSeconds(n)`、`compensationTtlSeconds(n)`、`teamMatchLockSeconds(n)`、`MAX_MATCHED_TTL_SECONDS = 96`、`BATTLE_MAX_DURATION_SECONDS = 300`、`PLACEMENT_TTL_SECONDS = 360`。xm-match、xm-team、xm-battle（以及 6.5）的单测都引用它 |

**包结构**（领域对象 + `XxxService`，不用 ECS）：

| 包 / 类 | 职责 | 基线来源 |
|---|---|---|
| `com.game.match.MatchApplication / MatchConfiguration / MatchProperties` | 装配；启动门禁（§9.8） | `msvc.go`；`cfg.go` |
| `dispatch.MatchClientMessageService / MatchDispatcher / MatchWorkerPool` | 按消息号派发；身份只取 `SessionContext.player_id`；有界工作池；应答规则 §8.1 | `msvc.go:355-382`；`queue.go:199-208` |
| `queue.QueueService` | 157 / 148 / 153 | `join.go`、`cancel.go`、`status.go` |
| `ticket.TicketStore`（`RedissonTicketStore`，Lua） | 票据状态机与队列原语（§9.4），排队、gather、整队、活动共用 | `queue.go:70-181` |
| `matcher.MatcherRunner / QueueMatcher / GroupPicker` | 500 ms 一轮、按队列加锁、锚点 + 容差选人 | `matcher.go` |
| `gather.GatherLauncher / GatherPipeline / GatherPlan / Compensation` | 开局管线与补偿矩阵 | `gather.go` |
| `gather.BattleNodePicker` | 读 `xm:nodes:battle:0`，只从 `accepting = true` 的条目里随机挑，支持按 (节点号, 实例) 排除 | `gather.go:212-217`、`:334` |
| `gather.ScenePreparer` | 定位持有者 → `SceneBattleService.prepare / cancel` | `gather.go:399-449`、`:532-557` |
| `placement.PlacementStore` | 落点记录（§4.3） | `spectate.go:89-201` 中与补签相关的一半 |
| `rating.RatingRules`（纯函数） | 容差、锚点容差、Elo、蛇形分队、回合打满按平局 | `rating.go:152-263`、`:326-341` |
| `rating.RatingStore`（JDBC，表经 pbmysql） | 读评分、整局一笔事务入账 | 替代 `rating.go:56-89`、`:374-506` |
| `rating.BattleResultConsumer` | 消费 `xm-battle-result-g<N>` | `consumer.go` |
| `challenge.ChallengeService` | 152 / 151，推 156 / 154 | `chl.go` |
| `ticket.BattleTicketReissue` | 179 | `rbt.go` |
| `precheck.MemberPrecheck` | 整队与活动共用的逐成员预检 | `tsvc.go:417-463`；`act.go:209-263` |
| `team.MatchTeamServiceImpl` | §7.5 | `tb.go` |
| `activity.MatchInternalServiceImpl` | §7.2 | `act.go` |
| `support.MatchTips` | 全部 tip 码与 `parameters[0]` 中文串（客户端可见，单测逐字节钉住） | `join.go:18-21` 等 |
| `id.MatchIds` | `LeaseGatedSnowflake`（`NodeTypes.MATCH`） | `svcCtx.BattleIDGen` |
| `admin.DevMatchController` | dev / test 专用管理口（§9.10） | Java 独有 |
| `metrics.MatchMetrics` | §11 | `metrics.go` |

### 9.2 对已有模块的改动

| 模块 | 改动 |
|---|---|
| xm-api | `DubboGroups.MATCH`；`MatchTeamService`、`MatchInternalService`；`xm/api/match_control.proto`；`com.game.api.match.MatchBudgets`；把 `SceneAssetOpClients`（`xm-api/src/main/java/com/game/api/asset/SceneAssetOpClients.java:42`）泛化成 `com.game.api.rpc.NodeRpcClients<S>`：按 (host, port, 实例号) 缓存编程式引用、`retries = 0`、建引用在自己的守护线程上（这是 6.2 Q9 留给 6.4 的决定；6.3 规格稿也要用它、计划先做，哪批先合入就由哪批做，另一批只引用，形状以本条为准）；`SceneAssetOpClients` 改为它的薄包装 |
| xm-discovery | `NodeTypes.MATCH`；`RedisKeys` 新增 §9.4 的 match 键；战斗锁键 `RedisKeys.battleLock` 与 `BattleLockReader` 由 6.3 加（scene-battle-spec 的 xm-discovery 改动），6.4 只引用；`SceneAssetLocator`（`xm-discovery/src/main/java/com/game/discovery/location/SceneAssetLocator.java:40`）泛化为「持有者定位」，scene 战斗通道与资产通道共用（同一端口，`bn-spec` Q13） |
| xm-gate | `MessageRoutes.SERVICE_BACKENDS` 加 `"MatchService" → DubboGroups.MATCH`（`xm-gate/src/main/java/com/game/gate/session/MessageRoutes.java:35-41`）；`GateConfiguration` 加 match 引用（`retries = 0`，`xm.dubbo.match-url: tri://127.0.0.1:20888`，nacos profile 置空，超时 5 s；先例 trade，`GateConfiguration.java:125-131`）；`GateMetrics` 的 backend 标签加 `match`。`BattleClientPlayer` 继续不进路由表 |
| xm-team | `NoTeamBattle` 换成 `MatchTeamBattle`；补齐 `team-spec` §5.5 清单（§7.6） |
| xm-battle（6.2） | `KafkaBattleResultSink` + `battle-result-out` 线程 + topic 核对（§5.4）；dev 房间仍不调用 sink |
| xm-audit | `BattleResultTopics`（topic 规格常量）；`AuditTopicInitializer` 不改 |
| xm-scene（6.3） | `SceneBattleService` 提供方与战斗锁（§9.7.2，由 6.3 交付） |
| xm-robot | 场景 `battle-smoke`、`match-activity`、`match-5v5`；`team` 场景打开 S7 / S8（§15.5） |
| tools/local | `start-slice.sh` / `stop-slice.sh`：服务清单在 xm-battle 之后加 `xm-match 20888`；导出 `XM_BATTLE_RESULT_TOPIC_GENERATION`（缺省 1） |

### 9.3 线程所有权

| 线程 | 做什么 | 不得做什么 |
|---|---|---|
| Dubbo 提供方线程 | 解析调用、身份检查、投递到 `match-worker`；不涉及 I/O 的 156 / 154 / 163 / 164 当场回；`runTeamGather` 只登记 future 就返回 | 阻塞 I/O |
| `match-worker`（固定 16 线程，队列 1024，AbortPolicy） | 客户端请求、`MatchTeamService` 的前三个方法、`MatchInternalService`。可以同步等 Redisson / JDBC（它既不是 Netty I/O 线程，也不是场景逻辑线程，`AGENTS.md` §3）。整请求预算 4500 ms（基线 zrpc 超时 5000 − 500，同 trade 口径） | — |
| `match-matcher`（单线程，`scheduleWithFixedDelay(500 ms)`） | 读注册集 → 逐个队列抢锁 → 弹组 → 把 gather 交给 `GatherLauncher`。每轮 `try/catch Throwable`：JDK 调度器遇到一次异常就会永久停止后续执行，这一点与 safego 不同 | 跑 gather 本身 |
| `match-gather`（**虚拟线程**，每次 gather 一个；全局信号量 `gather-max-inflight`，缺省 256） | 照基线顺序写的阻塞式 gather，每跳 `future.get(剩余预算)`（Dubbo 异步调用、Redisson 异步命令），与 `gather.go` 逐段对照 | 在 `synchronized` 块内阻塞（JDK 21 会钉住载体线程）；直接跑 JDBC（经 `match-db`） |
| `match-db`（有界平台线程池，8 线程） | 评分读取（JoinQueue、5V5 分队） | — |
| `match-rating-consumer`（单线程） | Kafka poll → 解码 → MySQL 事务 → `commitSync` | — |
| `match-push`（有界） | `PlayerPushes` 回调与计数 | — |

- **为什么 gather 用虚拟线程**：一次 gather 是最长约 90 s 的串行 RPC 链（`queue.go:344-366`）。用平台线程池，scene 卡住时会把池子堵满，而且排队等线程的时间会吃掉 matched TTL 的预算（TTL 从建票时就开始算）；
  改写成 `CompletableFuture` 链又会失去与基线逐段对照的可读性。
- **背压**：拿不到 gather 许可时，matcher 不弹组（队列原样保留）；PVE_SOLO 在建票之前先看一眼余量，不足回 16004 `服务器繁忙,请稍后再试`；
  真正拿许可发生在 `launch` 时，拿不到 → outcome `overloaded`，按入口的失败策略处理（PVE_SOLO 删票；切磋推 154 false；整队 MATCH_FAILED；活动删票；凑单回队首），没有其它副作用（M13）。
- 不涉及任何场景状态：scene 的状态改动全部在 scene 逻辑线程上，由 6.3 的提供方投递执行。

### 9.4 Redis 键与脚本（全部经 `RedisKeys` 生成，`xm:` 前缀，tag `{match}`）

| 键 | 类型 / TTL | 写者 | 基线 |
|---|---|---|---|
| `xm:{match}:index` | SET | match | `match:{mq}:index` |
| `xm:{match}:queue:<mode>:<config>` | LIST（等待序；mode 为数值，config 为无符号十进制） | match | `match:{mq}:queue:…` |
| `xm:{match}:rank:<mode>:<config>` | ZSET（member = pid，score = 入队时评分 × 100） | match | `match:{mq}:rank:…` |
| `xm:{match}:lock:<mode>:<config>` | STRING（实例 id，PX 10 s） | match | `match:{mq}:lock:…` |
| `xm:{match}:ticket:<pid>` | HASH：`ticket` / `mode` / `config` / `state` / `enqueued_at_ms` / `zone_id` / `queue_key` / `rating_centi` / `team_id` / `battle_id` / `not_before_ms`。TTL：queued 6 h，matched 按公式，ready 60 s | match | `match:ticket:<pid>`（换 tag；`not_before_ms` 是 Java 新增，M11） |
| `xm:{match}:pop:<token>` | STRING，PX 60 s | match | Java 独有（弹组重放标记） |
| `xm:{match}:challenge:<id>` / `xm:{match}:challenge-target:<pid>` / `xm:{match}:challenge-done:<id>` | HASH / STRING / HASH，60 s | match | `challenge:<id>` / `challenge:target:<pid>`；墓碑是 Java 独有 |
| `xm:{match}:battle:<battle_id>` | HASH（`a`、`pb` = `BattlePlacement`），360 s | match | `spectate:battle:<id>` |
| `xm:presence:<pid>` / `xm:location:<pid>` / `xm:nodes:battle:0` / `xm:nodes:scene:<zone>` / `RedisKeys.battleLock(pid)`（Hash，键名与形状归 6.3，经 `BattleLockReader`） | 只读 | gate / scene / battle / scene（6.3） | `keys.go:36-43` |

**为什么一个 tag**：票据与队列同 slot 后，回队首、入队、取消、弹组都能在一段 Lua 里完成，基线的跨 slot 孤儿票与假 16001（B11）都不再出现。Java 的 Redis 是单实例，一个 tag 没有热点问题；
将来上 Cluster，这些多键脚本仍同槽（同 `team-spec` D2）。match 的写 QPS 很低（基线已论证，`keys.go:25-29`）。

**脚本**（每段可变脚本都能被 Redisson 重发：`retryAttempts = 1` 会重发 EVAL，`RedisProperties.java:14`；`team-spec` §5.5）。脚本里只访问 KEYS 里声明的键：

| 脚本 | KEYS | 作用与重放语义 |
|---|---|---|
| S_HEAL | 票、该票的 queue | JoinQueue 第 5 步与预检的自愈：模式 ready → id 一致且 state = ready 才 DEL；模式 orphan → id 一致、state = queued、queue_key 等于 KEYS[2] 且 `LPOS` 找不到才 DEL。重发时第二次找不到票 → 0，调用方按「已自愈」继续 |
| S_JOIN | 票、index、queue、rank | 票已存在且 id = 本次 id → 重放成功；已存在 → 回现有 id（16001）；否则用 `TIME` 写 `enqueued_at_ms`，建票并 PEXPIRE；queue_key 非空时 SADD + ZADD + RPUSH。PVE_SOLO、整队、活动只建 matched 票，不入队 |
| S_CREATE_GROUP | 各成员的票 | 整队 / 活动原子建全员 matched 票：任一人已有票且 id 不是本次的 → 不写，返回第一个冲突者；全部是本次 id → 重放成功 |
| S_CANCEL | 票、queue、rank | Java 先 HGETALL 读票并处理第 1–5 步；脚本：id = 当前 id 且 state = queued 且 queue_key = KEYS[2] → DEL 票 + LREM + ZREM；重发时找不到票 → 0（仍回成功） |
| S_SNAPSHOT | queue、rank | LRANGE 0..255 + 逐个 ZSCORE + `TIME`（只读，同 `queue.go:103-111`） |
| S_DROP | queue、rank、票 | 有条件地摘掉无效成员：票不存在 / 非 queued / queue_key 不符 → LREM + ZREM；原因是「有战斗锁」或「已离线」时，还要求 id = 快照时读到的 id 才 DEL 票。期间重新入队的同名玩家不受影响 |
| S_POP | queue、rank、pop 标记、各成员的票 | 标记存在 → 重放成功；逐个核对「在 list 里、state = queued、id 相等、queue_key 相符、`not_before_ms` 已过」，全部满足才一次性 LREM + ZREM + 置 matched + PEXPIRE(matched TTL) 并写标记；否则不写任何东西，返回无效名单 |
| S_READY / S_EXTEND / S_DEL / S_DEL_GROUP | 票 | 带 ticket id 的 CAS：ready 写 state 与 battle_id、60 s；补偿续期只续 matched；删票 |
| S_REQUEUE | index、queue、rank、各幸存者的票 | **同一段**脚本里从末尾往前：id 一致且 state = matched 才「回 queued + 6 h + LPUSH + ZADD 原评分」（可带 `not_before_ms`），最后 SADD。重发时 state 已是 queued → 跳过，结果不变 |
| S_PRUNE | index、queue、rank | 同 `matcher.go:32-44` |
| S_STATUS | 票 | HMGET + `TIME`（只读） |
| S_PLACE | 落点 | `attempt ≥ 已存值` 才写 `a` 与 `pb`，PEXPIRE 360 s |
| S_CH_INVITE / S_CH_DEL / S_CH_CONSUME | 记录、占坑、墓碑 | §6.3 |
| S_LOCK_RELEASE | 锁 | 按持有者释放（同 `matcher.go:24-30`） |

- **时间**：`enqueued_at_ms`、`not_before_ms` 与所有等待时长都取 Redis `TIME`，等待时长夹到 ≥ 0；GetQueueStatus 的 `queued_seconds` 也用 S_STATUS 返回的 `TIME` 计算并夹到 ≥ 0（修 B5，M7）。
- **不移植**：旧格式队列搬迁、旧票补 queue_key（`matcher.go:91-196`；`queue.go:178-188`）；评分镜像缺分时的补写（Java 的入队是原子的，缺分只可能来自人为改数据；遇到时按票里的评分用、不回写，并计数；M9）。

### 9.5 matcher

- **并发**：照基线按队列加锁（SET NX PX 10 s，值 = 实例 id，按持有者释放）。抢不到锁的实例跳过该队列，并把**自己**那份 `queue_depth` / `starved_anchor_wait_seconds` 置 0，看板按实例求和不再翻倍（§11）。
- **暂停条件**（在抢锁之前判，与基线「battle 池为空」同位置）：目录里没有 `accepting = true` 的 battle 条目或目录读失败；发号租约无效（`MatchIds.leaseValid()` 为假，即 `LeaseGatedSnowflake.leaseValid()`）；gather 许可已满。
  暂停期间每 10 s 告警一次，计 `xm_match_matcher_rounds_total{result=paused_*}`。后两条是 Java 新增：否则会出现「弹组 → gather 发号失败或拿不到许可 → 回队首」的热循环（M10）。
- **算法**：与 §2.6–§2.9 逐条一致，包括：候选扫描全部成员、稳定排序的键、按 pid 去重、32 个锚点、256 前缀、成员顺序、5V5 在 gather 时重读评分做蛇形分队。差异只有：
  - 弹组用 S_POP 一次原子完成（核对 + 摘出 + 置 matched）。S_POP 返回无效名单时，对其中每人执行 S_DROP，然后在本轮内重新挑选；基线这种情况会把剩下的人 LPUSH 回队首（`matcher.go:588-599`），Java 他们根本没离开原位（不可见）。
    S_DROP 不会剔除「只是 `not_before_ms` 未到」的人，所以本轮内的重挑**有上限**（同一队列 3 次），用完就结束这条队列的本轮，防止同一批人反复被挑中又被 S_POP 拒绝的空转。
  - 队列锁只是效率手段：锁在单轮超过 10 s（Redis 慢）时过期、另一实例同时处理同一队列也不会双弹，因为 S_POP 在脚本里核对每个人仍在 list 且是 queued。
  - **凑单校验多一项位置检查**（M12）：候选与锚点通过战斗锁检查后，再读一次位置记录：状态 `l`（重连租约中）→ 本轮跳过、保留票据与位置；`x` 或不存在 → S_DROP 删票出局。
    理由：Java 断线即移除实体（`ho-spec:105`），不能像基线那样把掉线玩家冻进战斗（B9），而这样的人在 PrepareBattle 上必然失败、还会让排在他前面的人白冻结一次。
  - `not_before_ms` 未到的票不作锚点也不作候选（M11）。

### 9.6 gather 实现（`GatherPipeline.run(GatherPlan)`，与 §3.2 逐步对号）

```java
record GatherPlan(MatchMode mode, int battleConfigId, List<Long> members,
                  Map<Long, String> tickets,          // 切磋为空
                  FailPolicy onFail,                  // REQUEUE_SURVIVORS（凑单）/ DELETE_ALL（PVE_SOLO、整队、活动）/ NO_TICKETS（切磋）
                  long presetBattleId,                // 活动：非 0
                  BattleActivityContext activityContext) { }
// GatherLauncher.launch(plan) -> CompletableFuture<GatherResult(boolean ok, GatherOutcome outcome, long battleId)>
```

| 步 | Java |
|---|---|
| 0 | 拿在途许可，失败 → `overloaded`（无副作用、无肇事者） |
| 1 | 用预发的号或 `MatchIds` 发号；失败 → `internal` |
| 2 | `BattleNodePicker.pickRandom(exclude = ∅)`：只从 `accepting = true` 的条目里等概率选；没有 → `no_battle_node`。`deadlineMs = TIME + 300 000`，`prepareDeadlineMs = TIME + matchedTtl(n) × 1000`（两者取同一次时钟读数，在任何 Prepare 之前） |
| 2.5 | `GatherHooks.beforePrepare(members)`：6.4 是空操作，6.5 在这里接观战清退 |
| 2.6 | `TeamAssignment`：5V5 经 `match-db` 读评分后蛇形分配；其余按下标 |
| 3 | 逐人：`ScenePreparer.resolve(pid)`（位置 `o` → scene 目录 → rpc 地址，判定顺序同 `SceneAssetLocator`）。找不到持有者或读出错 → `no_location`（该玩家是肇事者，同 B-g2）。`prepareBattle`（3 s）：传输失败或超时 → `prepare_failed`，并把该玩家记进「结局不明」集合（F-g1）；tip ≠ 0 或快照为空 → `prepare_failed` |
| 3.5 | zone 组成指标 |
| 3.6 | `FingerprintCheck`：模式 `xm.match.table-fingerprint-mode`（off / warn / enforce，缺省 warn），**枚举绑定，值非法拒绝启动**（同 `bn-spec` N18） |
| 4 | `SecureRandom.nextLong()` 作种子；组 `CreateBattleRequest`，字段同 `gather.go:287-297`（含 `table_fingerprint`、活动上下文） |
| 4.1 | `PlacementStore.write(attempt = 1)`：一次 Redisson 调用，外层截止 6.1 s；失败 → `index_failed`，补偿之后删记录 |
| 4.2 | `createBattle`（5 s）：`ADMITTED` 且 tip = 0 → 成功；`NOT_ALLOCATABLE` → 按 (节点号, 实例) 排除后再挑一个，`write(attempt = 2)`（失败 → `index_failed`）→ 再建一次；没有可换的节点 → 直接进 4.3 |
| 4.3 | 最后一次的结局：`NOT_ALLOCATABLE` → `not_allocatable`（不发 destroy，删记录）；`ADMITTED` 且 tip ≠ 0 → **`create_rejected`**（不发 destroy，删记录，F-g2）；`UNSPECIFIED`、异常或超时 → 对**最后尝试的节点** `destroyBattle(reason = "gather_rollback")`（3 s）：成功 → `create_failed` 并删记录；失败 → `create_failed_room_alive`（不解冻、不动票据、保留记录） |
| 5 | 全员 `S_READY`（CAS）→ `PlacementStore.write(同一 attempt)` 补写 → `GatherHooks.onStarted(placement)`（6.5 在这里加入活跃集合）→ 指标 |
| fail | `REQUEUE_SURVIVORS` 时先 `S_EXTEND`（TTL = (已冻结人数 + 结局不明人数) × 3 + 10）→ 逐人 `cancelBattlePrepare`（已冻结 ∪ 结局不明，每人 3 s，失败只记日志）→ 按 `onFail` 处理票据：肇事者 `S_DEL`、幸存者 `S_REQUEUE`（无肇事者时带 2 s 的 `not_before_ms`）；或 `S_DEL_GROUP` → 删预写的记录 → 指标。Cancel 发往**该成员 Prepare 时用的那个 scene 节点**（记下的 (节点号, 实例, rpc 地址)，同基线 `p.sceneEndpoint`，`gather.go:532-537`），不按位置记录重新解析：Java 断线即移除实体、位置随之变 `l`，重新解析会找不到持有者而漏发 Cancel（R4 正是给这种「玩家已不在本节点」的 Cancel 用的）；对端已重启时回实例不符或传输失败，都只记日志，冻结与锁由 R6 / 锁 TTL 收尾 |

- **Redis 单条命令的最坏耗时**：Java 缺省配置是 4.2 s（`arch:607-609`），基线按 3 s 算。落点记录写入在外层 6.1 s 截止之内，公式里的 `spectateRecordWriteWorst = 6.1 s` 不变。
  其余 Redis 小操作（读位置、推进 ready）仍算在公式的 10 s 余量里，同 `queue.go:351-354` 的口径：超支的后果只是 matched 票先于 gather 过期，玩家可以重排，不会串局。
- **启动断言**：`RedisProperties.worstCaseCommandMillis() ≤ 6100`，各跳 Dubbo 超时 ≤ 3 / 5 / 3 / 3 s，否则拒绝启动。这样 42 / 48 / 66 / 96 与基线逐字相同，单测钉住这组数。
- **备战 / 取消调用的超时保持 3 s（裁决，2026-10-06，与 6.3 对齐时定）**：scene 侧一次备战要等一条写锁脚本的结局，Java 缺省配置下最坏 4.2 s，大于这里的 3 s——
  scene-battle-spec 的评审稿曾据此要求「match 的备战调用同样 ≥ 5 s」，审计也按那句话报过本稿「只对齐了一半」。裁决是**本稿不改**：数值表、matched TTL 公式与 §10.3 的跨进程不等式整表不动；
  ≥ 5 s 的要求只针对 battle → scene 的确认 / 结算与 dev gather（scene-battle-spec §7.3、§10.4 已改写）。match 这边超时后的处理就是第 3 步与 fail 一行已有的：
  **超时按结局不明**，该玩家记进「结局不明」集合，补偿时对他补发 Cancel（F-g1 / M14）。scene 保证这样是安全的：写锁在途时收到的取消被延后到写锁完成之后再删锁
  （删的是「只删备战锁」——`b == X` 且还没确认才删）；超时之后 scene 才给出的那份成功应答没人收，冻结与锁由这条取消、scene 的 reaper（备战期限）与锁 TTL 收尾。
  `SceneBattleService` 接口注释里「调用方超时必须大于它」那一句随 6.4 改。

### 9.7 出站

#### 9.7.1 battle

| 方法 | 超时 | 寻址 |
|---|---|---|
| `BattleNodeService.createBattle` / `destroyBattle` / `issueBattleTicket` | 5 s / 3 s / 3 s（`gather.go:25-30`；`rbt.go:22`） | gather：目录 `xm:nodes:battle:0` 的 `rpc_host:rpc_port`；补签：落点记录里的地址。`NodeRpcClients<BattleNodeService>` 按 (host, port, 实例) 缓存，实例变了就重建引用；`retries = 0` |

只按 `admission` 判断「不可分配」；字段缺失（UNSPECIFIED）一律按「可能已建房」处理（`battle_control.proto` 注释；`bn-spec` §7.8）。

#### 9.7.2 scene：对 6.3 `SceneBattleService` 的最低要求

推荐形状（`bn-spec` Q13）：scene 在资产 RPC 端口（`xm.scene.asset-rpc-port`，21100）上另导出 `SceneBattleService`，group `scene-battle`，`register = false`，按目录直连，`retries = 0`。
6.3 规格（`docs/porting/scene-battle-spec.md`，2026-10-06 已落地）§7.3–§7.6 按这个形状定义并实现了接口：`SceneBattleCall{target_instance_id, player_id, body}` →
`SceneBattleReply{status, body}`，`status ∈ {HANDLED, NOT_HERE, DEFERRED, OVERLOADED}`（首值 UNSPECIFIED）。**接口、判定次序、键与脚本一律以那份的 §7.3（接口与提供方）、§7.4（状态模型）、
§7.5（备战）、§7.6（取消）为准**；下面只列 match 依赖的最低语义与 match 侧的映射，两处不一致时以 scene-battle-spec 为准。调用方用 xm-api 现成的 `com.game.api.rpc.NodeRpcClients<SceneBattleService>`（6.3 已建，battle 与 dev gather 在用）。

1. 调用带目标 scene 实例（调用方从目录读到的 `instance_id`）。match 对 prepare 的映射：
   - `HANDLED` 且 body 的 tip = 0、快照非空 → 成功；`HANDLED` 且 tip ≠ 0（含玩家不在本节点的 1004、写锁出错的 1003）→ `prepare_failed`，该玩家是肇事者，**不发 Cancel**（6.3 保证 tip ≠ 0 零冻结痕迹）；
   - `NOT_HERE`（实例不符，目录过时或对端重启）、`OVERLOADED`（在途超限，保证没进逻辑线程）→ `prepare_failed`，该玩家是肇事者（同 B-g1 的可见结果），**不发 Cancel**（零副作用）；
   - `UNSPECIFIED`、future 异常完成或超时 → `prepare_failed`，该玩家是肇事者，并记入「结局不明」集合、补偿时发 Cancel（F-g1 / M14）。
2. prepare 的应答 body 是契约 `PrepareBattleResponse`：tip、快照、六表指纹。快照的 `routing.gate_instance_id / scene_instance_id` 必须非空（`bn-spec` §4.3.3）。
3. prepare 在 `switchState ≠ NONE` 时拒绝（`ho-spec:106`），已有战斗 / 0 血 / 冻结时回 1006（R1）。
4. 战斗锁的键与值形状归 6.3：`RedisKeys.battleLock(pid)`，一个 Hash（锁与 ctx 合一，取代 `bn-spec` §7.13 的两键预留，scene-battle-spec D2）；TTL = prepare 剩余 + 60 s，所有删除和续期都按 battle_id 条件执行。
   match 不碰这个键的值，只经 6.3 提供的 `BattleLockReader`（xm-discovery `com.game.discovery.battle`）做 EXISTS——JoinQueue 第 3–4 步、切磋、凑单校验、`MemberPrecheck` 都用它，语义同基线的 `EXISTS battle:lock`。
   - **这是咨询性的读，权威在 scene**：`BattleLockReader.exists / existsAll` 走 Redisson 的普通读路由（主从部署下可能读到从库的旧值），只用来早拒；真正保证「一个人不会同时在两局里」的是 scene 的备战写锁——
     锁独占（被别的局占着就回 1006）、写成功才回 0（scene-battle-spec D3）。所以 match 的预检读到「没有锁」而实际有锁时，后果只是这名玩家在 gather 的 Prepare 一步被判 `prepare_failed`，不会串局。
     读失败怎么处理仍按本稿各入口自己的口径（JoinQueue 回内部错误、切磋按「忙」、`MemberPrecheck` 回 `LOCK_READ_FAILED`）。上主从 / 集群前的部署约束见 scene-battle-spec §10.5。
   - 锁存在的时段比「在打」长：从备战起，到结算在 scene 落盘并销账（或锁过期 / 判废）为止；结算应用后锁至少再保持 180 s 或到销账。这期间 JoinQueue 回 16000 是预期行为（同基线「锁留到落盘」）。
   - `existsAll`：按入参顺序去重，任何一个读失败整体异常完成；`battleId(pid)` 读锁指向的 battle_id（经脚本、读主库），6.5 的观战等需要时用。
5. cancel 只在 battle_id 一致且状态是 PREPARING 时生效；FIGHTING 拒绝（R5）；玩家不在本节点时按锁值条件清锁（R4，Java 的常态）。cancel 的 `NOT_HERE` / `OVERLOADED` / 传输失败都只记日志，靠 R6 与锁 TTL 收尾。
   取消的应答等删锁脚本的结局才回（最坏约 4.2 s），3 s 超时只记日志即可——取消的效果在 scene 侧已生效或由 reaper / 锁 TTL 兜底；写锁在途时到达的取消被延后到写锁完成再删。
6. 处理在 scene 逻辑线程上完成，结果经回复执行器回给 Dubbo（同资产通道的 `scene-asset-reply`）。
7. **调用超时保持 3 s**（§9.6 末条的裁决）：prepare 超时 = 结局不明，按第 1 条最后一支处理并补发 Cancel。
8. **备战请求的期限有上界**：`deadline_ms` / `prepare_deadline_ms` 比 scene 的当前时间晚超过 7 天会被拒（1005，scene-battle-spec D36）；本稿的期限是 TIME + 300 s / + matched TTL，远在界内。
9. **battle_id 必须随时间递增**（时间在高位的雪花号，§9.1 发号一行）：scene 按 battle_id 升序当作局序。

6.3 不交付时，6.4 只能用替身做组件测试，端到端无法验收（§14 Q21）。（6.3 已于 2026-10-06 落地；在 6.4 之前 6.3 自己用 xm-battle 的 dev gather 管理口验收，scene-battle-spec §7.18。）

### 9.8 启动、停机、租约丢失

**启动**（任一步失败即拒绝启动）：
1. 运行模式（`XM_RUN_MODE`）。
2. `XM_DUBBO_SECRET`、`XM_MYSQL_PASSWORD`。
3. 加载 `ConfigTables`；校验 `pve-team-size-by-config-id` 的每个 id 都在 Dungeon 表里、值 ≥ 1（M21）。
4. `table-fingerprint-mode` 枚举绑定；§9.6 的预算断言。
5. 占 `NodeTypes.MATCH` 租约。
6. pbmysql `syncAll` 建两张评分表。
7. 导出 Dubbo。
8. 起 matcher。
9. 评分消费者与 topic 核对：Kafka 不可达时告警并照常启动，每 30 s 后台重试（同 `msvc.go:114-134`；`AuditTopicInitializer` 的模式）。

**停机**：停 matcher（等当前一轮结束）→ 撤 Dubbo 导出 → 有界等待在途 gather（缺省 10 s），超时直接放弃（票据按 matched TTL 自愈，scene 按 prepare 期限解冻，同基线「进程退出不等」）→ 停消费者 → 交还租约。

**租约丢失**（有意差异 M28）：基线是「先 Fence 发号器，再注销发现 key、flush Kafka、`os.Exit(1)` 重启换 worker」（`msvc.go:207-221`）；
Java 沿用 `LeaseGatedSnowflake` 的约定（`xm-common/src/main/java/com/game/common/id/LeaseGatedSnowflake.java:8-17`，同 scene-manager 5.1）：进程不退出、只停发号，续期恢复后自动恢复。期间：
- gather 第 1 步、切磋发号、活动开战发号失败（与基线 Fence 之后、退出之前的行为相同：16004 / INTERNAL / 推 154 false）；matcher 暂停（§9.5），队列原样保留；
- JoinQueue 的 PVE_SOLO 在建票之前就回 16004（§2.2），`checkTeamMatch` 回 INTERNAL（→ 4030 + 同源视图），不让必败的请求先建票、加锁；
- 其余入口（排队、取消、查询、补签、应答切磋的前半段）照常服务。基线这段时间整个进程不在，客户端看到的是信封 1003。

### 9.9 客户端入口的派发

`MatchClientMessageService.handle(ClientCall)`：
1. 按消息号取方法，未知号 → `tip_id = 1003`；
2. 解析请求体，失败 → `tip_id = 1003`；
3. 156 / 154 / 163 / 164 当场回（§8.1）；
4. 其余投递到 `match-worker`，带受理时刻 + 4500 ms 的 `Deadline`；投递被拒或排队超预算按 §8.1 的「过载」列回；
5. 业务方法内部异常 → `tip_id = 1003`（信封）；
6. 回 `ClientReply{body}`。不产生会话指令。

### 9.10 dev / test 管理接口（Java 独有）

| 接口（管理端口 18113） | 请求 | 应答 | 说明 |
|---|---|---|---|
| `GET /admin/match/dev/rating/{pid}` | — | JSON `{"player_id":"…","rating":"1516.00","games":1}`（uint64 输出为十进制字符串） | robot 断言评分；不存在回默认 1500 / 0 |
| `POST /admin/match/dev/activity-battle` | 契约 `StartActivityBattleRequest` 字节 | Response 字节 | 走与 Dubbo 同一个实现 |

鉴权同 xm-trade 播种接口（`arch` §4.20）：`X-Xm-Admin-Token`（`XM_ADMIN_TOKEN`，常数时间比较，未配置 503）+ 必填 `X-Xm-Operator`，每次调用写审计日志；运行模式不是 dev / test 一律 403。

---

## 10 配置与常量

### 10.1 `xm.match.*`（`xm-match/src/main/resources/application.yaml`）

| 配置 | 缺省 | 说明 / 校验 |
|---|---|---|
| `worker.threads` / `worker.queue` | 16 / 1024 | 同 trade |
| `request-budget` | 4500 ms | 基线 zrpc 5000 − 500 |
| `matcher.interval` | 500 ms | `yaml:49` |
| `matcher.lock-ttl` | 10 s | `yaml:53` |
| `ticket-ttl` | 6 h | `yaml:63`（QUEUED 最长保持，客户端可见） |
| `ready-ticket-ttl` | 60 s | `yaml:72`（READY 窗口，客户端可见） |
| `challenge-ttl` | 60 s | `yaml:60`（156 的 `expires_at_ms`，客户端可见） |
| `rating.tolerance.base / step-seconds / step-delta / max / max-wait-seconds` | 100 / 5 / 100 / 1000 / 90 | `yaml:112-118`；0 视为缺省 |
| `rating.enabled` | true | `yaml:103` |
| `rating.draw-round-cap` / `rating.draw-round-cap-by-config-id` | 30 / `{}` | `yaml:122-124`（§5.5） |
| `pve-team-size-by-config-id` | `{1: 5}` | `yaml:91-92`；键必须在 Dungeon 表里，值 ≥ 1，按 5 收口 |
| `table-fingerprint-mode` | warn | 枚举绑定，非法拒启 |
| `gather-max-inflight` | 256 | 同 battle `rpc-max-inflight` |
| `requeue-backoff` | 2 s | 无肇事者失败后的 `not_before_ms`（M11）；0 关闭 |
| `rating.consumer-group` | `xm-match-rating` | — |

不开放配置的（代码常量，`MatchBudgets`）：各跳超时、落点记录写入最坏 6.1 s、matched TTL 公式（含已失效的下限 30）、补偿续期、开战锁余量 10 s、战斗最长时限 300 s、落点记录 TTL 360 s。
它们出现在跨进程不等式里（§10.4），改一处要连带核对，不适合做成运行期配置（M21；基线这些是 yaml 可调的，`cfg.go:55-80`）。

### 10.2 环境变量

| 变量 | 说明 |
|---|---|
| `XM_DUBBO_SECRET` | 必填，缺失拒启 |
| `XM_MYSQL_PASSWORD` | 必填 |
| `XM_BATTLE_RESULT_TOPIC_GENERATION` | 缺省 1，与 xm-battle 必须一致 |
| `XM_ADMIN_TOKEN` | dev 管理口 |
| `XM_RUN_MODE` | 运行模式 |
| `XM_MATCH_RPC_PORT` / `SERVER_PORT` | 20888 / 18113 |

### 10.3 跨进程数值不等式（实现与评审核对；单测直接引用 `MatchBudgets`）

| 不等式 | 数值 | 出处 |
|---|---|---|
| battle 确认补发窗口 ≥ 最长 matched TTL + scene 锁余量 | 180 ≥ 96 + 60 | `bn-spec` §4.8、§10.4 |
| xm-team EndMatch 单调截止 ≥ 最长开战锁 | 110 ≥ 101 | `tstore.go:454-461` |
| 开战锁 ≥ matched TTL + 补偿窗口 | 101 ≥ 66 + 25 | `tb.go:60-66` |
| `runTeamGather` 调用方超时 ≥ gather + 补偿最坏 | 101 ≥ 91 | §7.5 |
| 落点记录 TTL ≥ 战斗最长时限 | 360 ≥ 300 | `spectate.go:53-59` |
| 票据寿命 = 房间期限 = 确认事件的 `deadline_ms` = scene 冻结的正式期限 | 同值 | `bn-spec` §10.4 |

与 6.3 落地后的两点对照（表本身不改）：

- 第一行的 180 s 是 battle 确认补发**停表**的时刻，最后一次实际补发在 170 s；相对锁最晚过期时刻（96 + 60 = 156 s）的实际余量是 14 s，不是 24 s（`bn-spec` §4.8、§10.4；xm-battle 的
  `ConfirmWindowConstraintTest` 两条都钉，其中 match 的 96 s 现在是字面值，6.4 落地时换成 `MatchBudgets` 的常量）。改 matched TTL 公式或放大组上限时按 14 s 核。
- 「调用 scene 的超时 > scene 侧一条 Redis 脚本最坏 4.2 s」**不在**本表里，对 match 也不成立——备战 / 取消保持 3 s，超时按结局不明（§9.6 末条的裁决）。

### 10.4 基线常量全表（对照用）

| 名称 | 值 | 出处 | 客户端可见 |
|---|---|---|---|
| 凑单间隔 | 500 ms | `cfg.go:48`；`yaml:49` | 间接 |
| 凑单锁 TTL | 10 s | `cfg.go:53`；`matcher.go:251-254` | 否 |
| queued 票 TTL | 21600 s | `cfg.go:65`；`queue.go:325-330` | 是 |
| matched 票 TTL | 42 / 48 / 54 / 60 / 66 / 96 s | `queue.go:367-380` | 是（崩溃时 MATCHED 维持多久） |
| 补偿续期 | 已冻结 × 3 + 10 s | `queue.go:384-386` | 间接 |
| ready 票 TTL | 60 s | `cfg.go:80` | 是 |
| 容差 base / step / delta / max / 兜底 | 100 / 5 s / 100 / 1000 / 90 s | `rating.go:162-197` | 玩法可见 |
| 饱和告警秒数 | 45 s | `rating.go:202-223` | 否 |
| 快照前缀 / 锚点上限 | 256 / 32 | `matcher.go:49`、`:53` | 间接 |
| 告警限频 | 10 s | `matcher.go:71`、`:76` | 否 |
| 默认评分 / K / 下限 | 1500 / 32 / 0 | `rating.go:35-38`、`:74-76` | 否 |
| 入账标记 TTL / 最近局数 | 7 d / 8 | `rating.go:42`、`:54` | 否（Java 不用） |
| 回合打满阈值 | 30 | `yaml:122` | 否 |
| 战斗最长时限 | 300 s | `yaml:57` | 是 |
| 切磋 TTL | 60 s | `yaml:60` | 是 |
| gather 各跳超时 | 3 / 5 / 3 / 3 s，记录写入 6.1 s | `gather.go:26-30`；`spectate.go:33-35`、`:68-83` | 经 TTL 可见 |
| match 服务端超时 | 5000 ms | `yaml:3` | 超时 → 信封 1003 |
| 每队上限 / 5V5 人数 | 5 / 10 | `gather.go:36-40` | 是 |

---

## 11 指标（`MatchMetrics`，Micrometer；不用 player / battle / challenge / team 的 id、zone 作标签，`AGENTS.md` §5）

| 指标 | 类型 | 标签 | 基线 |
|---|---|---|---|
| `xm_match_requests_seconds` | Timer | `method`（MatchService 方法名 / unknown）、`result` | `grpcstats` |
| `xm_match_join_queue_total` | Counter | `mode`、`outcome`（ok / in_battle / already_queued / mode_not_open / no_team_size / not_in_scene / internal / overloaded） | `match_join_queue_total`（`metrics.go:26-30`） |
| `xm_match_queue_depth` | Gauge | `mode`、`config` | 同名（`metrics.go:68-72`）；只有持锁实例写实值，抢不到锁的实例把自己那份置 0，剔除时置 0 |
| `xm_match_starved_anchor_wait_seconds` | Gauge | `mode`、`config` | 同名（`metrics.go:132-136`），同上 |
| `xm_match_wait_seconds` | 分布，固定桶 1,2,5,10,20,30,45,60,120,300 | `mode` | 同名（`metrics.go:122-127`） |
| `xm_match_group_rating_spread` | 分布，固定桶 0,25,50,100,200,300,500,800,1000,1600 | `mode` | 同名（`metrics.go:114-119`） |
| `xm_match_matcher_rounds_total` | Counter | `result` = ok / paused_no_battle / paused_no_lease / paused_saturated / error | Java 新增 |
| `xm_match_requeued_total` | Counter | `reason` = gather_offender（有肇事者时的幸存者）/ gather_no_offender（S_POP 全有全无，没有基线「摘出不足回队首」那一类） | Java 新增 |
| `xm_match_queue_dropped_total` | Counter | `reason` = invalid / in_battle / offline | Java 新增 |
| `xm_match_queue_anomalies_total` | Counter | `reason` = missing_score（镜像缺分，按票里的评分用、不剔除、不回写）/ repick_exhausted（§9.5 重挑上限用完） | Java 新增 |
| `xm_match_rating_consumer_paused` | Gauge | — | Java 新增（§5.3） |
| `xm_match_gathers_total` / `xm_match_gather_seconds` | Counter / Timer | `mode`、`outcome` = success / internal / overloaded / no_battle_node / no_location / prepare_failed / fingerprint_mismatch / index_failed / not_allocatable / create_rejected / create_failed / create_failed_room_alive | `match_gather_total`、`_duration_seconds`；`overloaded`、`create_rejected` 是 Java 新增 |
| `xm_match_gathers_inflight` | Gauge | — | Java 新增 |
| `xm_match_gather_zone_mix_total` | Counter | `mode`、`mix` | 同名 |
| `xm_match_table_fingerprint_mismatches_total` | Counter | `fp_mode` | 同名 |
| `xm_match_battle_ticket_reissues_total` | Counter | `result` = ok / rejected / not_found / no_session / internal / rpc_error / rpc_timeout / instance_changed | 同名（`rpc_timeout`、`instance_changed` 新增；基线的 `no_node` 在 Java 不出现，先直拨） |
| `xm_match_challenges_total` | Counter | `stage` = invite / respond，`result`（同 `chl.go` 各出口） | 同名 |
| `xm_match_activity_battles_total` | Counter | `kind`（guild_trial / none / unknown）、`result` | 同名 |
| `xm_match_team_calls_total` | Counter | `method`、`result` | Java 新增 |
| `xm_match_rating_updates_total` / `xm_match_rating_round_cap_draws_total` | Counter | `mode`、`outcome` = applied / duplicate / ignored / error / decode_error | 同名（没有 `partial`） |
| `xm_match_battle_nodes` | Gauge | `state` = accepting / not_accepting | `match_discovered_nodes` |
| `xm_match_pushes_total` | Counter | `kind` = 156 / 154，`outcome` = sent / offline / gate_unreachable | `kafka_push_total` |
| `executor_*{name=match-worker / match-db / match-push}` | 标准 | — | — |
| `xm_battle_result_events_total`（xm-battle） | Counter | `result` = sent / fallback / not_verified | Java 新增 |
| `xm_team_matches_total`（xm-team，已有） | Counter | `outcome` 增加 success / gather_failed / ticket_failed / gather_unknown | `team_match_total` |

**标签净化**（修 B2）：`mode` 只取已知枚举名，其余记 `unknown`（基线 `in.Mode.String()` 对未知值会变成数字串，`join.go:48`）；
`config` 只取 0 或 Dungeon 表里存在的 id，其余记 `other`（基线不校验，`matcher.go:270-276`，而 `metrics.go:71` 的注释声称这是有界的表 id）。

---

## 12 隐患与边界

### 12.1 实现必须守住的坑

1. **CreateBattle 超时 ≠ 建房失败**：必须先 Destroy 成功才能解冻；Destroy 失败时一律不解冻、不回队（`gather.go:357-375`）。
2. **落点记录必须在建房之前写好**，写不进去就不开局（补签与 6.5 都依赖它）。
3. **prepare 期限在 PrepareBattle 时就下发了，事后改不了**。D82 的重试路径必须算进 TTL 公式（`queue.go:358-361`）。改任何一跳的超时都要重算 §3.4 整张表并核对 §10.3。
4. **票据的所有写都带 ticket id 做 CAS**；回队首在一段脚本里完成，不能拆成「先 LPUSH 后 CAS」。
5. **判断「不可分配」只看 admission 枚举**；UNSPECIFIED 按「可能已建房」处理。
6. **换节点只重试一次**，否则整池都不可分配时 gather 会被拖出 TTL 预算（`gather.go:324-328`）。
7. **活动的 battle_id 在 gather 之前就交给了 guild**：gather 失败不会有结果事件，guild 必须有过期兜底（4.6）。
8. **切磋发起时只做咨询性检查，不冻结任何人**；应答时对双方战斗锁「权威复查」，读失败一律按「在战斗中」（`chl.go:247-266`）。
9. **整队开战锁的提交「报错」或「未提交」都可能已经落锁**（Redisson 同样会重发 EVAL），必须按 token 确认（`team-spec` §5.5）。
10. **结果事件只在真正打完时发**，dev 房间永不发，否则 dev 接口会变成刷分的口子（`bn-spec` §7.9）。
11. **1005 只用于「这局确实没了」**，补签的其它失败都不能回 1005（`bn-spec` §2.7）；直拨**超时**哪怕目录里同号已换实例也只回 1003（§4.3）。
12. **Java 断线即移除实体**：CancelBattlePrepare 的离线分支（按锁值条件清锁、FIGHTING 时拒绝）是常态路径，6.3 必须覆盖（R4）；凑单校验的位置检查（§9.5）也基于这一点。
13. **虚拟线程**：gather 代码里不得在 `synchronized` 块内阻塞；JDBC 只经 `match-db`；Redisson / Dubbo 都用异步 API 加 `future.get(剩余预算)`。
14. **JDK 调度器遇到异常会停止**：matcher 与所有定时任务每轮 `try/catch Throwable`。
15. **5.2 交接与 gather 交错**：交接途中位置记录还指向旧节点，PrepareBattle 会在旧节点得 1004（实体已交出）或在任一节点得 1006（`switchState ≠ NONE`），该玩家作为肇事者删票。与基线（冻结中回 1006）可见结果相同。
16. **单 tag**：所有 match 键在一个 slot。Java 是单实例 Redis，没有问题；将来上 Cluster 时这是单分片，match 的 QPS 低（`keys.go:25-29`），可以接受。
17. **battle_config_id 不校验**：客户端可以造出任意多条队列（每条一个 LIST + ZSET + 注册集成员），空队列由 matcher 懒剔除。照搬（客户端可见），只净化指标标签。
18. **Cancel 发回 Prepare 时的那个节点**，不按位置记录重新解析（§9.6 fail 行）；Java 断线即移除实体，重新解析会漏发。
19. **整队开战的 `runTeamGather` 传输失败时不删票**：gather 可能仍在跑，票据由它收尾或按 matched TTL 自愈（§7.5）。
20. **结果事件里的重复 player_id 要去重入账**（§5.2），否则 MySQL 版会对同一行加两次分、两次局数。

### 12.2 照搬的基线怪癖（PARITY 注明）

- Cancel 在 matched 之后静默成功，客户端只能靠 GetQueueStatus 收敛；16005 / 16006 永不发出。
- `estimated_wait_seconds = 0`（B4）。
- 切磋的 `challenger_name` 是账号名；切磋与 PVE_SOLO 失败都没有专门的提示。
- 回合打满按平局，最后一回合真把人打死也按平局（`rating.go:418-427`）；阈值对所有 config 恒为 30（§5.5）。
- B-g1 – B-g4（§3.7）。
- 切磋、整队、活动入口不检查对方的排队票据（§3.6）。

### 12.3 基线缺陷汇总

| # | 缺陷 | Java | 出处 |
|---|---|---|---|
| B1 | 身份回落到请求体：没进游戏的会话可以冒用他人 player_id | 只认会话（M3）；建议同时报给 mmorpg | `queue.go:199-208`；gate `client_message_processor.cpp:740-741` |
| B2 | 指标标签由客户端控制 | 标签净化 | `join.go:48`；`matcher.go:270-276`；`metrics.go:71` |
| B3 | 16005 / 16006 是死码 | 同样不发 | `errors.go:40-43` |
| B4 | `estimated_wait_seconds` 恒为 0 | 照搬（§14 Q19） | `status.go:74-79` |
| B5 | `queued_seconds` 用 uint64 相减，时钟偏斜时下溢 | Redis TIME + 夹到 ≥ 0（M7）；建议报 mmorpg | `status.go:67-72` |
| B6 | CancelQueue 成功时两种 gate 模式回包不一致 | 不回包（M4） | `cpp/nodes/gate/main.cpp:270-313`；`forwardlogic.go:186-191` |
| B7 | `MatchedTicketTTLSeconds` 缺省 30 低于公式最小值 42，永不起作用 | 去掉这项配置 | `cfg.go:67-75` |
| B8 | 无肇事者的持续失败会热循环 | 2 s 退避（M11） | `gather.go:202-216` |
| B9 | 离线的人留在队列里，弹出后在 PrepareBattle 失败，排在前面的人白冻结一轮 | 凑单校验位置（M12） | `gather.go:240-251` |
| B10 | 文档漂移：候选「锚点之后」、5V5「前 5 后 5」、inv 的「估算」 | 以代码为准 | §2.7、§2.9、§2.4 |
| B11 | Redis 客户端重发建票脚本时，第二次执行看到自己写的票，被当成并发后到者回 16001，之后票是 queued 却不在队列里 | 可重放脚本（M6） | `queue.go:158-165`；`join.go:283-300` |
| F-g1 | PrepareBattle 结局不明不发 Cancel | 发（M14） | `gather.go:242-251` |
| F-g2 | 明确拒绝建房仍发 Destroy，Destroy 失败就不解冻 | 不发（M15） | `gather.go:358-375` |
| G8 | 切磋双击接受开两次 gather | 原子消费（M17） | `chl.go:199-228`、`:291-302` |

### 12.4 对 inventory 与设计文档的勘误

1. **`inv:207`（match-queue）**：「estimated_wait_seconds 估算」不对，实际恒为 0（`status.go:74-79`）；另外漏写了 Cancel / Status 在 Redis 故障时回 gRPC 错误、客户端看到信封 1003（`cancel.go:44-47`、`:67`；`status.go:43-46`）。
2. **`inv:212`（match-queue hazards）**：「客户端每 3s 一次」轮询无法核实（本仓库与基线都没有客户端代码）。
3. **`inv:244`（match-gather internal）**：「PrepareBattle / RemoveObserver / CreateBattle 5s 级」不对，是 3 / 3 / 5 s（`gather.go:26-30`；`spectate.go:33-35`）。
4. **`inv:243`（match-gather behavior）**：漏写基线对「明确拒绝（tip ≠ 0）」也发 Destroy，Destroy 失败就不解冻（`gather.go:358-375`）。
5. **`inv:283`（match-battle-ticket robot）**：「battle_smoke（直连重建路径）」不对，基线 robot 只注册了 179 的应答 handler（`robot/logic/handler/match_service_responses.go:25`），没有任何场景发送 179。
6. **`inv:339`（team-match behavior）**：「MATCH_FAILED（带 tip）」只对建票失败成立（4026[pid]）；gather 失败的 MATCH_FAILED 不带 tip（`tsvc.go:484-494`）。
7. **`inv:339`（team-match behavior）**：漏写预检里「位置 node_id 为空 → 4026[pid]」这一条（`tsvc.go:444-451`）。它专指跨 zone 交接已放行、目标 zone 还没落点，
   Java 在 5.4 之前不会出现这种状态（Java 位置的节点号是 uint32，按「≠ 0」判）；另外漏写「建票时 Redis 出错也回 4026[该成员]」（`tb.go:123-128`）。
8. **`docs/design/cross-zone-matchmaking.md:386`** 与 `matcher.go:626` 的注释「候选取锚点之后」与代码（`matcher.go:633-649`）不符；**`ms.proto:54`**「前 5 后 5」已被蛇形分队取代。两处建议 mmorpg 顺手改注释。
9. **`inv:243`（match-gather behavior）**：指纹比对模式写作「warn / reject」，实际取值是 off / warn / enforce（`gather.go:42-47`；`cfg.go:90`）。
10. **`inv:204` / `inv:209`**：没提 match 的 10 个号都不在 MessageLimiter 表里、按缺省每秒 3 条限频（§1.4）。

---

## 13 建议的有意差异（PARITY 候选）

| # | 差异 | 基线 | Java | 客户端可见？ | 两版同改？ |
|---|---|---|---|---|---|
| M1 | 进程 | match 与 team 同进程（`msvc.go:137-152`） | xm-match 与 xm-team 分开，经 `MatchTeamService` 交互 | 否 | 否 |
| M2 | 传输与隔离 | gRPC + 会话 metadata；MatchInternal 遇带会话的调用回 PermissionDenied | Dubbo Triple；会话取 `SessionContext`；内部接口类型化、gate 不可达，加调用方 MAC | 否 | 否 |
| M3 | 身份 | session 为 0 时回落到请求体（B1） | 只认会话 | 只影响伪造请求 | 建议 mmorpg 同修 |
| M4 | 148 成功的回包 | 直连模式（缺省）回错号且无请求号；路由模式回 148 空包（B6） | 不回包（Java gate 的 Empty 规则，`arch:89-90`） | **是**，很窄；基线 robot 不发 148 | 否 |
| M5 | 键空间 | `{mq}` 只管队列，票据按玩家分布；跨 slot 三条自愈路径 | 一个 tag `{match}`；所有迁移单段 Lua；弹组 S_POP 原子 | 否（去掉孤儿票 / 假 16001 窗口；弹组失败时其余人不再被挪到队首） | 否 |
| M6 | 脚本可重放 | go-redis 重发会产生假 16001（B11） | 按 ticket id / 弹组 token / 请求 nonce 识别重放 | 否 | 否 |
| M7 | 时钟 | Go 本机时钟；`queued_seconds` 可能下溢（B5）；切磋 `expires_at_ms` 与 151 的过期判定用各自实例的本机时钟 | 一律 Redis TIME，夹到 ≥ 0（含切磋，§6.3） | 只在时钟偏斜时 | 建议 mmorpg 同修 |
| M8 | 指标 | 标签由客户端控制（B2）；gauge 只由持锁实例写，旧持有者的值滞留 | 标签净化；抢不到锁的实例把自己那份 gauge 置 0 | 否 | 否 |
| M9 | 旧数据 | 搬旧格式队列、补 queue_key、缺分补写 | 不移植 | 否 | 不适用 |
| M10 | 凑单暂停 | battle 池为空（etcd 镜像无节点） | 目录无 `accepting = true` 条目或读失败；另加「发号租约无效」「gather 许可已满」 | 否 | 否 |
| M11 | 无肇事者失败后 | 全员立即回队首，500 ms 后再弹（B8） | 回队首的票 2 s 内不参与凑单 | 只影响失败路径的时序 | 可选 |
| M12 | 凑单时的离线成员 | 不检查，弹出后 PrepareBattle 失败才删票（B9） | 位置 `l` 跳过保留、`x` / 无删票出局 | 是（很小：掉线重连中的人保留排队；登出的人更早出局，其他人不被白冻结） | 否（Java 断线即移除实体，模型不同） |
| M13 | gather 并发 | goroutine，不设上限 | 虚拟线程 + 在途上限 256；超限 `overloaded`、无副作用 | 只在过载时 | 否 |
| M14 | PrepareBattle 结局不明 | 不发 Cancel，冻结到 prepare 期限（F-g1） | 也发 Cancel | 失败时冻结更短 | 建议 mmorpg 同修（可选） |
| M15 | battle 明确拒绝建房 | 照发 Destroy，Destroy 失败就不解冻（F-g2） | 不发 Destroy，直接补偿 | 双重故障时冻结更短 | 建议 mmorpg 同修（可选） |
| M16 | 落点记录与补签判死 | `SpectateBattleRecord{summary, node_id}`，靠成功后补写纠正乱序；节点缺席只回 1003 | `BattlePlacement` 多存实例号与地址，单调写；补签直拨记录地址，直拨**建连失败**且同号换实例才回 1005（超时一律 1003） | **是**：battle 丢租约仍活着时补签成功（基线 1003）；节点被别的进程接手时更早 BattleGone | 建议 mmorpg 同做后一半（`rbt.go:98-104` 已写明做法） |
| M17 | 切磋记录 | 多条命令、读与消费分开（G8） | 一个 tag 下的 Lua，发起与消费原子，可重放 | 只在竞态下：不再出现多余的 154 false | 建议 mmorpg 同修（可选） |
| M18 | 评分存储 | Redis hash 无 TTL，两层幂等，增量可交换 | MySQL（pbmysql）一个事务，行锁下按顺序算 Elo | 否（评分不下发；并发入账时第二局用更新后的分） | 否 |
| M19 | 结果 topic 与消费 | `match-results`；失败重试 3 次后跳过 | `xm-battle-result-g<N>`，启动核对；可恢复故障不跳过 | 否 | 否 |
| M20 | 整队开战跨进程 | 同进程端口；ticket id 由端口生成 | `MatchTeamService`；预检合成一次调用（顺序不变）；ticket id 由 xm-team 每人生成；gather 结果靠长挂 RPC；match 崩溃或分区时推 MATCH_FAILED；match 侧建票 Redis 出错照基线回 4026[roster[0]] | 只在 xm-match 崩溃 / 分区 / 调不通时：211 回 4030（基线没有这个故障面），多一条 MATCH_FAILED，分区时可能随后又到 177 / 143 | 否 |
| M21 | 时限与配置 | yaml 可调；matched TTL 下限配置无效；指纹模式由 go-zero `options=off\|warn\|enforce` 在加载时校验（`cfg.go:90`，写错即起不来；只有空串回落 warn，`gather.go:465-468`）；PVE 人数不校验、值 0 视为未配置（`cfg.go:198-201`） | `MatchBudgets` 代码常量（数值相同）；去掉下限配置；指纹模式枚举绑定、非法拒启（与基线同效）；PVE 人数 id 必须在 Dungeon 表里、值 ≥ 1，否则拒启 | 否 | 否 |
| M22 | 163 / 164 | 已实现 | 6.4 期间 163 回 in-band 1006、164 回空列表，6.5 补齐 | **是**（只在本批期间） | 按批次登记 |
| M23 | 发号 | team_id、battle_id、challenge_id 同一个发号器 | battle_id 与 challenge_id 用 `NodeTypes.MATCH`；team_id 用 `NodeTypes.TEAM` | 否 | 否 |
| M24 | 156 / 154 推送 | Kafka gate-cmd | `PlayerPushes`（Redis pub/sub + gate 玩家栅栏） | 否（同为至多一次；不会推错人） | 否 |
| M25 | 整队 / 活动建票 | 逐人建、失败回滚 | 一段 Lua 原子建全员；只在结果不明时按 id 回滚 | 否 | 否 |
| M26 | 选 battle 节点 | etcd 镜像随机，按 endpoint 排除 | Redis 目录只选 `accepting = true`，随机，按 (节点号, 实例) 排除；「不可分配」是类型化枚举（`bn-spec` N1） | 否 | 否 |
| M27 | 活动预检的「有位置」 | 只判位置存在 | 状态 `o` 且节点号 ≠ 0（与整队一致） | 间接（窄窗口里 guild 更早看到 NOT_READY） | 否 |
| M28 | 发号租约丢失 | Fence 后注销、flush、`os.Exit(1)` 重启换 worker（`msvc.go:207-221`） | 进程不退出、只停发号（`LeaseGatedSnowflake`），matcher 暂停，PVE_SOLO / 整队在建票、加锁之前就拒（§9.8） | 是，只在租约丢失期间：基线整个服务不在（信封 1003），Java 排队 / 查询 / 补签照常，开局类请求回 16004 / 4030 | 否（Java 全仓统一约定） |
| M29 | 过载应答 | 没有工作池，过载 = zrpc 5 s 超时 → 信封 1003 | 工作池满或排队超预算：157 / 152 / 151 / 179 回 in-band 16004 `服务器繁忙,请稍后再试`，148 / 153 回信封 1003（§8.1；先例 guild 14021） | 是，只在过载时 | 否 |

引用 6.2 的差异：177 / 143 按在线目录寻址（`bn-spec` N2）——备战期间换了会话也能收到。

---

## 14 开放问题（每条附推荐答案）

- **Q1 进程、端口、group**：推荐**采纳** xm-match，Dubbo 20888，管理 18113，group `match`。组队已是独立进程（`team-spec` D1），不合并。
- **Q2 键空间**：推荐整个 match 只用一个 hash tag `{match}`（§9.4）。
- **Q3 凑单并发**：推荐**按队列加锁**（同基线）。备选是全局领导者（照 §4.19）：失效间隙 30 s、不能多实例并行，收益只有 gauge 单写，而 gauge 已用「非持有者置 0」解决。
- **Q4 评分存储**：推荐 **MySQL**（pbmysql，一个事务）。评分是永久的玩家数据，基线自己承认无 TTL 的 Redis 遇淘汰策略会回落 1500（`keys.go:31-34`）。代价：读评分走库，在 `match-db` 上执行，量很小。
- **Q5 回合打满的阈值**：推荐 6.4 **照搬基线**（30 + 覆盖表，缺省空）。基线的本意是「与引擎回合上限一致」（`cfg.go:146-148`），但覆盖表没配，Dungeon 1 上 30–299 回合分出的胜负都按平局。
  建议登记「mmorpg 待做：阈值改为按 Dungeon.time_limit 推导（或在 yaml 补 `"1": 300` 等），两版同批改」，改 mmorpg 需用户同意。
- **Q6 补签判死**：推荐**先直拨落点地址，直拨建连失败（请求确定没送达）且目录里同号换实例才回 1005；超时一律 1003**（§4.3）。6.2 规格的 Q14 改写为本条。
- **Q7 148 成功是否回包**：推荐**不回包**（Java gate 的统一规则；基线缺省直连模式在客户端看来也没有对得上的回包，§8.1）。备选是 gate 对 148 特判回空包以对齐路由模式。
- **Q8 163 / 164 在 6.4 怎么回**：推荐 163 回 in-band 1006、164 回空列表（M22）。备选是 163 按 battle_id 是否为 0 回 16017 / 16018，不推荐：提前借用 6.5 的真实语义码。
- **Q9 整队开战的结果怎么回到 team**：推荐长挂异步 RPC（§7.5）。备选是 match 回调 team：双向依赖、要再做回调接口与重试。
- **Q10 整队的 ticket id**：推荐 xm-team 每人生成一个 UUID（§0.5）。
- **Q11 PVE 组队人数的来源**：推荐 xm-match 配置 `{1: 5}`，与基线 yaml 同源同值；xm-team 经 `checkTeamMatch` 取。改读 `Dungeon.max_team_size` 会额外开放 id 2 和 3，客户端可见，须先改 mmorpg、两版同批。
- **Q12 无肇事者失败的退避**：推荐**做**，2 s、可配、0 关闭（M11）。
- **Q13 凑单时的离线成员**：推荐**做**：`l` 跳过保留、`x` / 无删票出局（M12）。
- **Q14 F-g1 / F-g2 / G8 三处修正是否 6.4 就做**：推荐**做**。都不改契约、更安全，PARITY 登记「mmorpg 待做（可选）」。
- **Q15 topic 规格件放哪**：推荐放 xm-audit（`BattleResultTopics`）。第三类 topic 出现时再抽 `xm-kafka`。
- **Q16 gather 执行器**：推荐虚拟线程 + 信号量 256。不采纳时用有界平台线程池（按 64 线程估算），但排队时间会吃掉 matched TTL 的预算。
- **Q17 PrepareBattle 是否并行**：推荐**保持串行**。TTL 公式、scene 锁、开战锁与确认窗口都按串行标定，改并行要连带重算，收益只在 5V5 上明显。
- **Q18 选节点时是否参考 `BattleNodeInfo.table_fingerprint`**：推荐**不做**：选节点在 PrepareBattle 之前，拿不到快照指纹；battle 侧已有指纹闸。
- **Q19 `estimated_wait_seconds`**：推荐保持 0，等 mmorpg backlog D-11 定了两版同批改。
- **Q20 B1 / B5 报给 mmorpg**：推荐报（安全缺陷与下溢）。
- **Q21 6.3 的 `SceneBattleService` 契约**：6.3 规格稿（`scene-battle-spec.md` §7.3–§7.6）已按 `bn-spec` Q13 的形状定义，§9.7.2 的六条与状态映射都能对上；两稿定稿时再互相核对一次。6.3 推迟时，6.4 先交付组件测试与 dev 验收，robot 端到端随 6.3。
- **Q22 challenger_name**：推荐照搬账号名（`SessionContext.account`）。改角色名要两版同改，登记「mmorpg 待做（可选）」。
- **Q23 dev 管理口（读评分、活动开战）**：推荐接受，只在 dev / test 开放（§9.10）。
- **Q24 battle_config_id 是否校验**：推荐照搬「不校验」（客户端可见），只净化指标标签。
- **Q25 结果消费遇到可恢复故障**：推荐不跳过、暂停重试（M19）。
- **Q26 发号租约丢失时进程退不退出**：推荐**不退出**（M28，Java 全仓 `LeaseGatedSnowflake` 约定），开局类入口在建票 / 加锁之前就拒。备选是照基线退出重启，代价是排队、查询、补签一起中断。
- **Q27 match 侧建票 Redis 出错时整队开战回什么**：推荐照基线回 4026[roster[0]]（§7.5）。备选回 4030（语义更准，但与基线 Redis 故障时的可见结果不同，要两版同改）。

---

## 15 测试计划与 robot

`./mvnw -B -pl xm-match -am test`；改了 proto 之后按 `AGENTS.md` §4 做 `clean install`；测试方法名用中文。

### 15.1 纯函数（缺省执行）

| Java 测试 | 覆盖 | 对照的基线测试 |
|---|---|---|
| `MatchBudgetsTest` | 1 / 2 / 3 / 4 / 5 / 10 人 → 42 / 48 / 54 / 60 / 66 / 96；补偿续期；开战锁 101；`MAX_MATCHED_TTL_SECONDS + 60 ≤ 180`；`teamMatchLockSeconds(5) ≤ 110` | `ticket_cas_test.go:77-171`；`gather_spectate_index_test.go:232`；`team_battle_test.go:39` |
| `RatingRulesTest` | 容差曲线逐行（§2.8 表）、饱和秒数、锚点容差；Elo 数值；5V5 按平均分；平局；回合打满按平局（含覆盖表）；下限 0；HALF_EVEN（含 x.125 这类恰好打平的值）；只认两队、队号 0 / 1 | `rating_match_test.go`（12 条）；`rating_review_fix_test.go:160` |
| `TeamAssignmentTest` | 蛇形 `0,1,1,0,0,1,1,0,0,1`；同分按弹出序；1V1 / 切磋 0、1；PVE 全 0 | `rating_match_test.go:452`、`:487` |
| `FingerprintCheckTest` | 一致则透传；warn 下不一致或部分为空照常开局、不透传；enforce 肇事者 = 少数派里最靠前者；全员为空；off | `gather_fingerprint_test.go:122-237` |
| `GroupPickerTest` | 候选来自锚点之前（回队首后 list 序 ≠ 入队序）；去重；32 个锚点；256 前缀；`not_before_ms` 跳过；缺分按票里的评分 | `TestHeadAnchorDoesNotBlockLaterAnchors`、`TestPopGroupDedupesDuplicateEntries` |
| `MatchTipsTest` | 全部 `parameters[0]` 逐字节（半角逗号） | — |
| `QueueKeysTest` | 键形状与解析；所有 match 键同 slot | `keys_test.go`（去掉 legacy） |
| `MetricLabelsTest` | mode / config 净化 | — |

### 15.2 组件（替身：票据存储内存实现、scene / battle / 落点 / 目录 / 推送 / 时钟）

- **`QueueServiceTest`**：§2.2 的每一行（`error_code`、`error_message.id`、`parameters[0]` 字节、`queue_ticket`、outcome 指标）；判定顺序（在战且模式未开放 → 16002；PVE_TEAM 未配置且在战 → 16003；在途票且无位置 → 16001；ready 残留 + 无位置 → 先自愈再 16020）；
  身份为 0 的三条分支；请求体里填了别人的 player_id 被忽略；148：成功不回包、存储故障信封 1003、空串取消当前票、票号不符 / matched 静默；153：五态映射，不回 4 / 0，estimated 恒为 0，时钟偏斜夹到 0。
- **`MatcherTest`**：battle 池为空 / 租约无效 / 许可已满时暂停且不动队列；抢不到锁不上报实值并置 0；单轮抛异常后下一轮照常；弹出后有人取消 → S_POP 返回无效名单 → 本轮重挑；成员顺序原样传到 gather 替身；离线成员 `l` 跳过、`x` 删票。
  对照 `nobattle_guard_test.go`、`review_fix_test.go`（`TestMatcherDropsMemberCancelledAfterPop` 等）、`ticket_cas_test.go:279-354`（深度只由持锁实例上报）。
- **`GatherPipelineTest`**：成功路径（请求字段、`prepare_deadline_ms`、ready CAS、补写、钩子）；§3.3 每个出口各一例（肇事者、解冻集合、回队首或删票、记录删或留、destroy 的目标节点）；
  不可分配 → 换节点成功；连续两次不可分配；重试节点出别的错 → 只 destroy 重试节点；没有可换节点；记录改写失败；**M14**：结局不明的人也收 Cancel；`SceneBattleReply` 的 `NOT_HERE` / `OVERLOADED` / tip ≠ 0 都判肇事者且**不**收 Cancel，`UNSPECIFIED` 按结局不明处理（§9.7.2）；Cancel 发回 Prepare 时的节点（位置已变 `l` 也照发）；**M15**：明确拒绝不发 destroy；
  create 超时且 destroy 失败 → 保留记录、不解冻、不动票；`overloaded` 无副作用；预设 battle_id 与活动上下文透传；切磋不碰票据；整队与活动失败全删票；无肇事者失败带 `not_before_ms`。
  对照 `gather_not_allocatable_test.go:131-309`、`gather_create_reject_test.go:43-94`（Java 改为断言**不发** destroy）、`gather_spectate_index_test.go:264-463`、`activitybattlelogic_test.go:533-587`、`team_battle_test.go:231-263`。
- **`BattleTicketReissueTest`**：§4.3 表的 6 行（含 parameters 文案）；按记录地址拨号；建连失败 + 同号换实例 → 1005；**超时 + 同号换实例 → 1003**；建连失败 + 同实例 / 目录缺席 / 目录读失败 → 1003；battle 裁决原样透传。对照 `requestbattleticket_test.go:85-165`。
- **`ChallengeServiceTest`**：§6.1 两张表每一行；拒绝时只推发起者；接受时先推双方 154 再 gather；gather 失败再推一次 false；156 的字段（`expires_at_ms`、`challenger_name = account`）；156 / 154 上行空操作；
  并发两条接受只有一条成功、另一条 16012；S_CH_CONSUME 重放返回同一结果；156 推送结果为 OFFLINE / GATE_UNREACHABLE / 异常完成 → 都清理记录与占坑并回 16004 `邀请发送失败,请稍后再试`；
  `expires_at_ms` 与过期判定取替身时钟（Redis TIME），本机时钟偏移不影响结果（基线没有切磋单测，新增）。
- **`ActivityBattleServiceTest`**：校验（含 `UNRECOGNIZED` 的 kind）；离线 / 有锁 / 读锁失败 → INTERNAL；无位置；票据冲突（原子建票不留残票）；结果不明的回滚；截止已过；发号失败；成功后异步 gather；gather 失败的指标。对照 `activitybattlelogic_test.go:147-498`（15 条）。
- **`MatchTeamServiceImplTest`**：人数收口到 5、未配置 → DUNGEON_NOT_OPEN、超员 → SIZE_EXCEEDED；预检映射（读锁失败 → MEMBER_IN_BATTLE）；交错顺序（成员 1 在途票、成员 2 离线 → 成员 1）；按调用方的 id 原子建票；建票时 Redis 出错 → 已按本次 id 回滚、回 `failed_player_id = roster[0]`；`xm-budget-ms` 已过期 → 不写、`expired = true`；`releaseTeamTickets` 幂等；`runTeamGather` 保持 roster 顺序。对照 `logic/team_battle_test.go`（8 条）。
- **xm-team `TeamServiceTest` 扩充**（替身 `MatchTeamService`）：加锁之前就拒绝；建票失败释放锁并带 tip 4026[pid]；roster 顺序；锁过期后可重开；并发只锁一次；迟到请求；整轮重来；提交结果未知时的补偿；EndMatch 冲突；
  `checkTeamMatch` 非 OK 与传输失败都带同源视图（传输失败 → 4030）；`createTeamTickets` 回 `failed = 队长`（match 侧 Redis 出错）→ 4026[队长] 且 MATCH_FAILED 带同一 tip；`createTeamTickets` 回包丢失 → `releaseTeamTickets` + MATCH_FAILED（无 tip）+ 4030；
  `runTeamGather` 传输失败 → MATCH_FAILED、计 `gather_unknown`，且**不调** `releaseTeamTickets`。对照 `team/team_battle_test.go`（11 条）。
- **`RatingStoreTest`**（缺省 H2，风格同 `PlayerStoreSqlTest`）：同一局只入账一次、重复投递 duplicate；事务中途失败整笔回滚、重投后恰好一次（替代基线 `TestApplyBattleResultResumesAfterPartialWrite`）；缺行补 1500；下限 0；精度；PVE / 切磋 / 队伍异常忽略；
  事件里同一 player_id 出现两次（同队 / 跨队）→ 只加一次、取首次出现的队别、`games + 1`（§5.2）。对照 `rating_match_test.go:369`、`:435`（`TestApplyBattleResultEloAndIdempotent`、`…5v5UsesTeamAverage`）。
- **`BattleResultConsumerTest`**（`MockConsumer`）：坏消息跳过并提交；可恢复故障暂停、重试、不跳过、不提交；处理成功才提交；重平衡丢弃未提交批次；key 不一致只打日志。
- **`KafkaBattleResultSinkTest`**（xm-battle，`MockProducer`）：key = battle_id；队列满或发送失败进兜底日志；`send` 不在 `battle-logic` 线程上调用（断言线程名）。
- **`MatchClientMessageServiceTest`**：10 个号的派发与 §8.1 整张表；未知号与解析失败 → `tip_id = 1003`；过载应答（M29：157 / 152 / 151 / 179 in-band 16004，148 / 153 信封 1003）。
- **租约丢失**（M28）：`MatchIds` 租约置为无效 → PVE_SOLO 回 16004 且不建票；`checkTeamMatch` 回 INTERNAL；matcher 暂停、队列不动；152 回 16004；排队 / 取消 / 查询 / 补签照常。
- **`DevMatchControllerTest`**：运行模式不是 dev / test → 403；未配置令牌 → 503；缺 `X-Xm-Operator` 拒绝；读评分的 JSON 形状（uint64 输出为字符串）。
- **启动**（`ApplicationContextRunner`）：缺密钥、指纹模式非法、人数 id 不在表里、Redis 预算断言不过，都拒启；Kafka 不可达照常启动。

### 15.3 真依赖（按开关启用，缺省跳过）

- `-Dxm.it.redis`：逐条验证每段 Lua，并移植基线 miniredis 用例：
  - `review_fix_test.go`：`TestMatcherDropsMemberCancelledAfterPop`、`TestCancelTicketIfQueuedIsCasOnState`、`TestJoinQueueHealsOrphanQueuedTicket`、`TestJoinQueueStillRejectsLiveTickets`、
    `TestCreateTicketIfAbsentIsConditional`、`TestJoinQueueConcurrentDuplicateRejected`、`TestPopGroupDedupesDuplicateEntries`、`TestRequeueFront*`、`TestExtendMatchedTicketsRefreshesOnlyOwnedTickets`；
  - `ready_ticket_test.go`：`TestJoinQueueClearsStaleReadyTicket`、`TestJoinQueueStillRejectsMatchedTicket`；
  - `crosszone_test.go`：`TestEnqueueAtomicRegistersQueue`、`TestJoinQueueRejectsWithoutLocation`、`TestJoinQueueRecordsZoneAndQueueKey`、`TestMatcherMixesZonesAndShortensMatchedTTL`、`TestRequeueFrontRestoresQueuedAndLongTTL`、
    `TestMatcherPrunesEmptyQueueFromIndex`、`TestCancelQueueUsesTicketQueueKey`（`TestDeleteChallengeRecordSplitsDel` 不适用：Java 切磋键同 tag、原子删除）；
  - `rating_match_test.go`：`TestNewPlayersMatchImmediately`、`TestRatingGapWaitsUntilToleranceWidens`、`TestClosestCandidateWins`、`TestHeadAnchorDoesNotBlockLaterAnchors`、`TestPveTeamIgnoresRating`、`TestQueueMirrorStaysConsistentAcrossOperations`；
  - `rating_review_fix_test.go`：`TestStarvedAnchorsMatchAfterMaxWait`；
  - `ticket_cas_test.go`：`TestQueueDepth*`（3 个）、`TestTicketCas*`（2 个）；TTL 三条（`TestMatchedTicketTTLFormula`、`TestMatchedTicketTTLCoversD82RetryPath`、`TestMatcher5v5MatchedTTLCoversWorstCaseGather`）是纯函数，归 `MatchBudgetsTest`；
  - **Java 独有**：每段可变脚本执行两次（模拟 Redisson 重发）结果不变；S_POP 重放命中标记；S_REQUEUE 不留孤儿；S_DROP 不误伤期间重新入队的玩家；`PlacementStore` 单调写（attempt 小的迟到写不能覆盖）、TTL 360 s；
    切磋的两个并发接受只有一个成功；`MemberPrecheck` 的四项读。
  - 不移植：legacy 的 3 个用例。
- `-Dxm.it.mysql`：`RatingStoreSqlTest`：同一玩家两局用两个连接并发入账都落账（对应 `rating_review_fix_test.go:108`）；1213 重跑。
- `-Dxm.it.kafka`：`BattleResultTopicIntegrationTest`：topic 的创建与核对、分区数不符时拒绝启动、端到端发一条消费一条。
- **Dubbo**：一条真 Triple 回环（`MatchTeamService` 与 `MatchInternalService`，带鉴权过滤器；缺 MAC 时被拒；`runTeamGather` 长挂 3 s 后完成；`xm-budget-ms` 已过期时 `createTeamTickets` 不写）。
  另加一条：测试配置把 Triple 心跳 / 空闲断开周期调小（如 2 s），`runTeamGather` 挂起超过它（如 6 s）仍正常完成，证明 101 s 的挂起流不会被空闲探活掐断（§7.5）。
- **gate**：`MessageRoutesTest`：148 / 151 / 152 / 153 / 154 / 156 / 157 / 163 / 164 / 179 都路由到 group match；148 / 154 / 156（应答 Empty）`hasResponse = false`，其余 `true`；`BattleClientPlayer` 仍是 unsupported；`MatchInternal` 的方法不在白名单。

### 15.4 本机切片

`tools/local/start-slice.sh` 在 xm-battle 之后起 xm-match（等 20888）；导出 `XM_BATTLE_RESULT_TOPIC_GENERATION`（缺省 1）；xm-match 缺 `XM_DUBBO_SECRET` 即拒绝启动。前置：MySQL、Redis、Kafka 已就绪（Kafka 已是切片前置，`start-slice.sh:10`）。

### 15.5 robot

**新增客户端件**：复用 6.2 的 `BattleDirectConnection`、大厅 177 handler（`bn-spec` §13.8）；新增 `MatchAdminClient`（调 §9.10 的两个接口，头同 `TradeAdminClient`）；
`RobotOptions` 新增场景 `BATTLE_SMOKE`（`battle-smoke`）、`MATCH_ACTIVITY`（`match-activity`）、`MATCH_5V5`（`match-5v5`），参数 `--match-admin-url`（缺省 `http://127.0.0.1:18113`）。
前置：切片带 xm-battle、xm-match 与 6.3 的 scene，运行模式 dev，Kafka 就绪。

**节奏与时限（各场景通用）**：
- match 的 10 个号都按 gate 缺省限频（每会话每号每秒 3 条，§1.4），超频回 1008 并计非法包。同一会话连发同号请求间隔 ≥ 350 ms；过渡态重试每 1 s 一次、上限 20 s
  （同基线 `tss.go:94-96` 的 `teamSmokeSettleTimeout` / `teamSmokeSettleRetryInterval`）。
- 等开战（177 / 143）30 s、等终局（150）120 s（同基线 `bss.go:57-59`）。

**场景 `battle-smoke`**（6.4 版：基线 `bss.go:304-389` 的 A 侧，加上排队语义、补签、1V1 评分、切磋；账号 A、B、C 用 run-tag 新建）：

1. **登录**：A、B、C 进场。A 发 153 → `{state = 5}`，两个秒数都是 0。
2. **拒绝码**：A 发 157 `{mode = 2}` → 16002 + `该匹配模式未开放`；`{mode = 5, config = 2}` → 16003 + `该副本未开放组队`。断言 `error_code == error_message.id`、`parameters[0]` 逐字节、`queue_ticket` 为空。
3. **排队与取消**：A 发 157 `{mode = 3, config = 0}` → ticket T 匹配 `^[0-9a-f-]{36}$`；再发一次 → 16001 且 `queue_ticket = T`；153 → QUEUED。
   A 发 148(`"stale"`) → 1 s 内无回包，153 仍 QUEUED；A 发 148(T) → 无回包，153 → NOT_QUEUED。
4. **PVE_SOLO**：A 发 157 `{mode = 4, config = 1}` → `error_code = 0` 且 ticket 非空；大厅上**先 177 后 143**（断言收件箱下标），battle_id 一致；177：`role = 1`、`token_signature` 匹配 `^[0-9a-f]{64}$`、
   `expire_at_ms ∈ [t_send + 300 s − 5 s, t_177 + 300 s + 5 s]`；153 → MATCHED 或 READY。
5. **补签**：A 发 179(battle_id) → 无错误，`assignment` 与 177 **逐字节相同**；C 发 179(同一个 battle_id) → 1005，无 assignment；C 发 179(随机 id) → `{1005, ["该战斗不存在或已结束"]}`。
6. **直连**：A 用 179 拿到的票直连 → 140 补拉 → 开自动战斗之前 A 再发 157 PVE_SOLO → **16000** + `战斗尚未结束,无法排队` → SetAutoBattle → 直连上收到 150（SIDE_A_WIN，`total_rounds ≥ 1`，判据同 `robot/features_battle_smoke.go:82-95`）→ FIN。
7. **再排**：A 立即再发 157 PVE_SOLO：6.3 的结算落地之前允许遇到 16000（在设定时限内重试，同 `tss.go:850-880`）；**不得**出现 16001（ready 残留必须已自愈）。
   受理之后这是 A 的**第二局**：同样直连、开自动、等到 150，否则 A 带着战斗锁进第 8 步会一直 16000。第一局结束后 A 发旧 battle_id 的 179 → 1005
   （落点记录还在 TTL 内，这个 1005 是 battle「房间不存在」的透传，不带 `parameters`，只断言 id）。
8. **1V1 与评分**：A 发 157 `{mode = 3, config = 0}`（第二局的锁未释放时按过渡态重试 16000），**收到 A 的受理应答之后** B 再发同样的 157（保证 A 是锚点）
   → 两人收到同一个 battle_id 的 177 / 143，143 的 actors 里 A（锚点）在 0 队、B 在 1 队 → 都开自动 → 都收到 150。
   收到 150 后最多等 10 s，查 `GET /admin/match/dev/rating/{pid}`：两人 games 各 +1；胜负局且 `total_rounds < 30` 时 Δ 互为相反数、`|Δ| = 16`（两个新号都是 1500）；平局或 `total_rounds ≥ 30` 时 Δ 都是 0。
9. **切磋**：A 发 152(A) → 16007；A 发 152(B) → 拿到 challenge_id，B 收到 156（`challenger_id = A`、`challenger_name = A 的账号`、`expires_at_ms ≈ now + 60 s`）；C 发 152(B) → 16011；
   C 发 151(该挑战) → 16013（记录不被消费）；B 发 151(accept = false) → 只有 A 收到 154 `{false, responder = B}`；B 再发 151(同一个 id) → 16012；
   A 再发 152(B)，B 接受 → A、B 都收到 154 true → 都收到同一个 battle_id 的 177 / 143（mode 6）；**此时、开自动之前** C 发 152(A) → 16009（A 持战斗锁）
   → 都开自动 → 都收到 150；C 下线后 A 发 152(C)：A 的锁在结算落地前仍在，会先命中第 3 行 16010，按过渡态重试，直到断言 16008。
10. **163 / 164（6.4 临时）**：A 发 163 → `error_message{1006}`；A 发 164 → 空列表。
11. **指标**：抓 18113，断言 `xm_match_gathers_total{outcome="success"} ≥ 4`、`xm_match_battle_ticket_reissues_total{result="ok"} ≥ 1`、`xm_match_challenges_total` 有增长、`xm_match_rating_updates_total{outcome="applied"} ≥ 1`。
12. **输出**：`BATTLE_SMOKE_OK battle_id=… a_turns=… a_direct_turns=… pvp_battle_id=… challenge_battle_id=…`；失败输出 `BATTLE_SMOKE_FAIL step=… reason=…`。6.5 再追加 B 侧观战与 `battle_smoke_cross_zone`。

**场景 `team` 升级**（`xm-robot/src/main/java/com/game/robot/scenario/TeamScenario.java:65`、`:88`、`:377-383` 现在断言 4027 / 4018；照 `tss.go:441-470` 升级，`team-spec` D16）：
- **S7**：A（队长）与 B 组队，A 发 211(1) → 回包 STARTING；B 收到 213 MATCH_STARTED（A 不收）；两人收到同一个 battle_id 的 177 / 143 → 都开自动 → 都收到 150；两人都收到 213 MATCH_ENDED。
  遇到 4025 / 4026 的过渡态在设定时限内重试（同 `tss.go:1028-1053`）。
- **S8**：B 单人 PVE 开战且尚未出手（持有战斗锁）时，A 发 211 → 4025，`parameters[0] = B`。
- **新增**：B 持有 1V1 排队票时 A 发 211 → 4026[B]，随后 B 发 148 取消（再发 153 确认 NOT_QUEUED，免得后续步骤被这张票挡住）；211(2)（未配置的副本）→ 4027，视图 IDLE；B 发 211 → 4018。

**场景 `match-activity`**（只在 dev / test 下运行）：调 dev 接口，名单 [A, B]、上下文合法 → `battle_id ≠ 0`，A、B 收到 177 / 143；`initiator ≠ members[0]` → INVALID_ARGUMENT；
名单里有离线账号 → MEMBER_OFFLINE、offender = 该账号；战斗中的成员 → MEMBER_IN_BATTLE。guild_id / activity_id 用不存在的值（§7.2 的注意事项）。
「运行模式不是 dev / test 时回 403」不在 robot 里验（robot 不切运行模式），由 `DevMatchControllerTest` 覆盖（§15.2）。

**场景 `match-5v5`**（可选，10 个新号）：全员发 157 `{mode = 1, config = 0}` → 同一个 battle_id；评分相同，所以 143 的 actors 按弹出序蛇形分为 `0,1,1,0,0,1,1,0,0,1`；评分查询每人 games + 1。

**跨版本**：Go robot `features` 模式的战斗段（JoinQueue PVE_SOLO → `FEATURES_BATTLE_OK`）在 6.3 结算落地后对 Java 跑；`team_smoke` S7 / S8 对 Java 跑；`battle_smoke`（含 WatchBattle）与 `battle_smoke_cross_zone` 归 6.5。

### 15.6 交付清单（随 6.4 提交）

- **PARITY.md 新增行**：「匹配：排队 / 凑单 / 开局 / 评分」「切磋」「战斗票据补签 179」「帮会活动开战（内部接口，调用方随 4.6）」，附 M1–M29 与 §12.2 的怪癖；
  交付说明写明 mmorpg 侧状态：已有；M3、M7、M14、M15、M16（后一半）、M17 建议 mmorpg 同修（可选）；Q5（回合打满阈值）登记「mmorpg 待做」；基线 robot 不覆盖 179 与切磋。改 mmorpg 需用户同意。
- **PARITY.md 更新行**：「组队」行（`PARITY.md:104`，整队开战已接，`team-spec` D11 关闭）；6.2 的 battle 行（结果事件传输已接）；补签判死的 6.2 Q14 改写。
- **`arch`**：§2 模块表加 xm-match（20888 / 18113）；新增 §4.21「匹配（xm-match）」；§5 线程模型加虚拟线程 gather 一条；§9 补 battle_id、challenge_id 的来源；§11 加抓取表与指标；§4.5 写明结果 topic。
- **`bn-spec`**：§10.5「6.4 的 Kafka 生产者不需要跳板」一行按 §0.5 更正；Q14 按 §4.3 改写；§7.13 的落点记录键改为 `xm:{match}:battle:<id>`。
- **其它**：`roadmap.md:84` 打勾写提交号；`tech-stack.md` 无新依赖；`start-slice.sh` / `stop-slice.sh`。

---

## 评审修订记录

完整性评审（2026-10-05）回到基线 `26ceb70ca` 逐条核对后在本稿就地修改。抽查了约 120 处 `文件:行` 引用（`join.go` / `cancel.go` / `status.go` / `queue.go` / `matcher.go` / `rating.go` / `gather.go` / `spectate.go` / `rbt.go` / `chl.go` / `act.go` / `tb.go` / `tsvc.go` / `tstore.go` / `consumer.go` / `cfg.go` / `yaml` / `msvc.go` / `ms.proto` / `mi.proto` / `me.proto` / `pb.cpp` / `pb.h` / gate `main.cpp` / `forwardlogic.go` / robot 与 Java 侧文件），绝大多数准确；改动如下。

**引用更正**
- `engine-spec:286` → `engine-spec:334-340`（§1.8 站位）与 `:226`（`actors` 插入序决定选目标与 buff 次序）。
- `pb.h:98-102` → `:94-102`；`node_directory.proto:55-75` → `:55-72`；`shared/safego/...` → `go/shared/safego/safego.go`；`91-batches-and-codex.md:29`（B6b-srv1）→ `:30`（B6b-srv2）；
  `tsvc.go:440-447` → `:441-451`（节点号为空的判断在 `:444-451`）；`ms.proto:45` 补成 `:40-45`。
- §12.4 第 7 条原引 `inv:342`（那一行是 `size: M`），改为 `inv:339` 并重写内容；新增第 9、10 条勘误（inventory 的指纹模式名、漏写限频）。
- §15.3 的 `TestMatchedTicketTTL*（3 个）` 只有 2 个匹配前缀，第三条是 `TestMatcher5v5MatchedTTLCoversWorstCaseGather`，改为逐个列名并归入 `MatchBudgetsTest`。
- M21 原写「指纹模式写错回落 warn」不对：基线 go-zero `options=off|warn|enforce` 在加载时就拒绝非法值（`cfg.go:90`），只有空串回落 warn。

**漏掉的客户端可见行为**
- §1.4：match 的 10 个号**全部**不在 MessageLimiter 表里（不止 148 / 153 / 157），一律每秒 3 条、超频 1008；179 的请求 / 应答消息在 battle 包（`player_battle.proto:176-183`）。
- §7.3 / §7.5 / §7.6：基线整队建票时 **Redis 出错也回 4026[该成员]**（`tb.go:123-128`），原稿让 Java 一律回 4030。改为：match 侧 Redis 出错 → 回滚后 `failed = roster[0]` → 4026[队长]（与基线最常见情形逐字节相同，Q27）；
  只有基线不存在的跨进程传输失败才回 4030。补「预检的任何非 0 结果（含 4030）都带同源视图」（`tsvc.go:375-378`）。
- §6.3：156 推送的 stage **异常完成**也要清理并回 16004 `邀请发送失败`（基线所有推送失败走同一出口，`chl.go:171-179`）；`expires_at_ms` 与 151 的过期判定改用 Redis TIME（并入 M7）。
- §2.2：Java 的第 7–8 步用严格读，`l` / `x` / 缺失都归 16020；PVE_SOLO 在第 8 步之后、建票之前检查发号租约与 gather 许可。

**与已有规格 / 基线的矛盾、不安全的设计**
- §9.8 / M28：原稿说租约丢失「同基线 Fence、进程不退出」，基线实际是 Fence 后 `os.Exit(1)`（`msvc.go:207-221`）。登记为有意差异 M28（沿用 Java `LeaseGatedSnowflake` 约定），并让 PVE_SOLO 与 `checkTeamMatch` 在建票 / 加锁之前就拒，避免「回 0 再静默失败」。
- §8.1 / M29：工作池过载回 in-band 16004 是基线没有的出口（基线过载 = 超时 → 信封 1003），登记为有意差异 M29（先例 guild 14021）。
- §4.3 / M16 / Q6：补签直拨**超时**不再参与判死（只有建连失败 + 同号换实例才回 1005）。丢租约的 battle 正是「可能很慢」的进程，超时判 1005 会让客户端永久放弃一场还活着的战斗（`bn-spec` §2.7）。
- §9.6：补偿的 Cancel 必须发回 Prepare 时的那个节点，不能按位置重新解析（Java 断线即移除实体，位置变 `l`，会漏发 Cancel）；补 F-g1 的残余竞态说明。
- §7.5：`runTeamGather` 传输失败时 xm-team **不调** `releaseTeamTickets`（gather 可能仍在跑）；超时按调用设置（先例 `SceneAssetOpClients.java:128`），并要求测 Triple 空闲探活不掐断 101 s 的挂起流。
- §7.6：原稿「四个阻塞方法」与「`runTeamGather` 挂回调」自相矛盾，改为三个阻塞 + 一个异步。
- §7.2：调用方截止从绝对 Unix 毫秒（`xm-deadline-ms`，跨主机比墙钟）改为相对预算 `xm-budget-ms`；`createTeamTickets` 也带它，过期不写。
- §5.2：结果事件里重复的 player_id 要去重入账（基线靠 `recent_battles` 只加一次、取首次出现的队别），否则 MySQL 版会加两次分。
- §9.5：S_POP 拒绝后的本轮重挑加上限（`not_before_ms` 未到的人不会被 S_DROP 剔除，否则会空转）；写明队列锁过期时 S_POP 仍保证不双弹。
- §7.2 / §9.10：dev 活动开战接口建的是正常房间（照常结算、照常发活动结果事件），4.6 的消费方必须把不认识的 (guild, activity) 当终态销账。
- §0.3：补 6.5 的边界（`GATHER_CREATE_STAGE_WORST_MS`、票据只读口、观战索引清理不挂在 matcher 上）；§11 指标去掉 Java 不会出现的 `pop_partial`，`missing_score` 挪到不剔除的 anomalies 计数，新增消费暂停 gauge 与 `rpc_timeout`。

**测试与 robot**
- §15.2 / §15.3：补租约丢失、过载、`DevMatchControllerTest`、补签超时、整队建票 Redis 出错 / 预算过期 / 传输失败不删票、切磋推送异常、重复 player_id 入账、Triple 长挂空闲探活等用例；补漏列的基线用例（`TestEnqueueAtomicRegistersQueue`、`TestRequeueFrontRestoresQueuedAndLongTTL`、评分两条）。
- §15.5：加限频节奏与时限（`bss.go:57-59`、`tss.go:94-96`）；第 7 步的第二局必须打完再进第 8 步；第 8 步 B 在 A 受理之后再排（保证 A 是锚点）；
  第 9 步 C 挑 A 必须在开自动之前，A 挑下线的 C 要按过渡态越过 16010；组队新增步骤之后 B 取消排队；「prod 下 403」从 robot 移到组件测试。
- **与并行的 6.3 规格稿对齐**（`docs/porting/scene-battle-spec.md`，评审时已存在）：战斗锁改为 `RedisKeys.battleLock(pid)`（一个 Hash，锁与 ctx 合一，取代 `bn-spec` §7.13 的两键预留），
  match 只经 `BattleLockReader` 做 EXISTS（§0.3、§3.5 R2、§7.4、§7.6、§9.2、§9.4、§9.7.2）；§9.7.2 补 `SceneBattleReply.status` 的映射：`NOT_HERE` / `OVERLOADED` / tip ≠ 0 判肇事者、不发 Cancel，
  `UNSPECIFIED` / 异常 / 超时按结局不明发 Cancel；原稿「在途超限时 future 异常完成」与 6.3 的类型化 `OVERLOADED` 不符，已改。`NodeRpcClients<S>` 由先合入的一批做。

**与落地后的 6.3 对齐（2026-10-06，批次 6.3 的文档交付时改）**
- §0.3、§9.7.2：6.3 已落地，`SceneBattleService` 的最低要求改为明确指向 scene-battle-spec §7.3–§7.6；`NodeRpcClients<S>` 已由 6.3 建在 xm-api。
- §9.7.2 第 4 条：写明 `BattleLockReader` 是咨询性读、权威在 scene 的备战写锁；锁存在的时段覆盖到结算销账。
- §9.6 末条、§9.7.2 第 7 条、§10.3：**备战 / 取消调用超时保持 3 s 的裁决**。scene-battle-spec 评审稿里「6.4 的 match 备战调用同样 ≥ 5 s」作废，那份的 §7.3 / §10.4 / §13.9 已改写；本稿的数值表与不等式整表不动。
- §9.1 发号、§9.7.2 第 9 条：battle_id 用时间在高位的雪花号（scene 的 D13 局序依赖它）。
- §9.7.2 第 8 条：scene 对备战期限加了 7 天的上界（scene-battle-spec D36），对本稿的期限没有影响。
- §10.3：补确认补发实际余量 14 s 的说明。
