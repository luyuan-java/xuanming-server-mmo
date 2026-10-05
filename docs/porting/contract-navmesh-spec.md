# 批次 7.5 规格：契约变更报告与兼容性闸；导航网格查询

> **版本依据**
> - 基线：mmorpg `26ceb70ca`（`D:\work\mmorpg`，浅稀疏克隆，`git rev-list --count HEAD` 为 1，没有历史），与 `contract/SOURCE.properties:4` 相同。
> - 客户端：mmorpg-client `a8577c7`（`D:\work\mmorpg-client`，只检出 `Assets/Scripts`、`Tests`、`Docs`）。
> - Java：HEAD `aa8b5b5` 加工作区。5.2–5.5、6.2–6.4、7.1a、7.2 正在工作区里实现（`git status` 有大量 `M` / `??`），它们的规格按权威设计看待。
>   本文引用的 `SceneWorld.java`、`.github/workflows/*.yml`、`tools/TestReport.java`、`xm-battle/**` 行号都按**工作区**计；5.2 / 7.1a 入库后要重新定位。
>
> **盘点 id**：contract-change-report（`docs/porting/inventory/tools.md:218-228`，Java 版新增）、navmesh-bake-and-query（`tools.md:302-312`）、
> navmesh-queries（`inventory/combat.md:149-158`）。连带：contract-sync（`tools.md:206-216`）、message-id-allocation（`:182-192`）、
> contract-tamper-check（`:230-240`，本批只顺带覆盖其中 proto / 表部分）。路线图 `docs/porting/roadmap.md:95`。
>
> **本稿由三份分区稿合并**（契约报告、导航基线调研、Java 映射）。分区稿之间有 11 处分歧，编辑逐条回到代码和数据复核，裁决见 §0.4。
> 编辑做的实测都是只读的：JDK 单文件探针放在会话 scratchpad，不进仓库。

---

## 0 概览与范围

### 0.1 结论速览

| 问题 | 结论 |
|---|---|
| 契约闸放在哪 | **构建期契约面锁定测试**。新模块 `xm-contract-check` 用 protoc 产出的描述符渲染「契约面」，与仓库里提交的 `contract/SURFACE.txt` 比较。<br>不在 ContractSync 里手写 proto 解析器。理由：`clean install` 本来就是同步后、提交前的强制步骤（AGENTS §4、§5），在这里拦已经是提交前的闸；描述符是精确的，手写解析器需要再做一层 protoc 交叉校验才可信 |
| 比什么 | 消息号（号 ↔ 服务.方法 ↔ 请求 / 应答 ↔ 可见性）、契约 proto 的类型 / 字段 / reserved / 枚举值 / 服务、帧根、事件号、tip 码与文案、operator 枚举、具名常量、表 schema（含 cfg 选项和 pending 字段）、表数据（manifest 加逐行指纹）、导航网格文件指纹 |
| 怎么判 | 三级：BREAK / WARN / INFO。可见性也分三级：C（客户端可见）/ J（Java 生产代码在用）/ I（内部），I 级降一级判。**任何差异都要重锁；BREAK 还必须在 `contract/ACCEPTED_BREAKS.txt` 里按「目标 mmorpg 提交 + 变更 id」显式放行** |
| 号位复用 | Java 侧墓碑账本 `contract/RETIRED.txt`，只增不减。上游删方法后号会被新方法捡走（H1），开发期删字段可以不写 reserved（H3），只比相邻两份契约面发现不了 |
| CI | build job 的 `mvnw verify` 跑锁定测试；随后逐个复核本次推送里改过契约面的提交（防手改锁文件、防删账本）；漂移 workflow 加分级报告，仍然只报告 |
| 导航实现 | **自研 `xm-navmesh`（只依赖 JDK）：读取同步来的 UE5 double 布局 MSET `.bin`，移植上游 Detour 查询子集（findNearestPoly、raycast 及其依赖）**。不做格子导航：编辑实测网格比客户端位图多出 48.05 m²、凹角最多偏 0.225 m，评审又测出整体平移 +1 cm（§2.5），而且三张节庆图的位图根本不在 mmorpg 里 |
| 导航数据 | ContractSync 新增受管目录 `config-data/navmesh/`：只同步被 `BaseScene.nav_bin_file` 引用的 6 个 `.bin`（4 份不同几何），加一份 ContractSync 生成的 `navmesh.properties`（sha256、tile 数）。同步时做结构校验，失败不写入 |
| 接入点 | 与基线逐个对应的 3 处运行时调用（进场落位、移动上行裁决、帧外推夹持）加启动探针。FindPath、dtCrowd 不移植（基线没有调用方） |
| 失败策略 | 导航数据缺失 / 损坏 / 出生点探针失败 → **scene 启动失败**；`xm.scene.nav-enabled=false` 整体关闭（回到现在的 MoveGuard 口径）。仓库里的数据由构建期测试保证 |
| 第三方 | 不新增依赖。buf 11,474 star、recastnavigation 7,943、recast4j 292，都不到 2 万（§5） |
| 分批 | 7.5a 契约闸（M–L）→ 7.5b 导航数据同步 + xm-navmesh（L）→ 7.5c xm-scene 接入、robot、部署与文档（M）。7.5a 依赖 7.1a 入库，7.5c 依赖 5.2 入库 |

### 0.2 范围

**做**

- 契约变更报告与兼容性闸：渲染、比较、判级、放行、账本、CI 复核、漂移分级报告（§1、§4.1）。
- 导航网格：ContractSync 同步网格文件（§4.2）；`xm-navmesh` 读取器与查询（§4.3）；xm-scene 的进场吸附、移动射线夹持、外推夹持（§4.4）。
- 文档、PARITY、部署清单（§4.6）。

**不做**

- 烘焙器：mmorpg 是唯一生产者，Java 只消费产物（N-D9）。
- FindPath / findStraightPath / dtCrowd：基线没有调用方（N-D2）。
- 任意两个 mmorpg 提交之间的离线比较、在 mmorpg CI 里跑 Java 的检查（§8 CQ11）。
- Java 自有 proto（`xm-api`、`xm-audit`、`xm-data` 的 `xm.*` 包）的兼容性检查（§8 CQ9）。
- contract-tamper-check 中 TableConstants 与 `.pb` 字节级防手改的部分：`.pb` 有 manifest sha 兜底，TableConstants 仍只靠 `--check`（C-D7）。

### 0.3 本机约束与证据来源

- 本机没有 Docker / Go / python / node。GitHub Actions 可用，结论按 deploy-ci-spec §11.5 用匿名 REST 读取 check-runs。
- `third_party/ue5navmesh` 是子模块，没有检出；GitHub 匿名 API 读不到 `luyuan-cpp/ue5navmesh`（分区稿实测 404，私有或已删除）。UE5 版 Detour 的结构体定义和查询实现都读不到：
  §2.3 的二进制布局是从字节反推、在全部 180 个 tile 上自洽验证的；查询语义按上游 recastnavigation 写，**边界语义必须用 C++ 金样钉死**（§9.2.4）。
- 客户端 `Assets/Resources/` 不在稀疏检出里，节庆三地（蓬莱 / 东海 / 揽仙）的 `walkmask.txt` 看不到（引用处 `FestivalRegionMap.cs:22`）。只有天墉城的位图能逐点核对（它内嵌在 `TianyongPaintedCity.cs:289` 起）。
- `tools/navmesh_baker/test_baker.py` 要 python 和 C++ 烘焙器，本机跑不了；跨语言金样只能由 mmorpg 侧产出。
- **编辑实测**（2026-10-05，只读）：
  1. 按 §2.3 布局解析 4 份不同的 `.bin`：61 / 34 / 40 / 45 个 tile 的分段之和全部等于 `dataSize`，文件尾全部等于文件长度；tile 头版本全是 7；detail 顶点全为 0；
     多边形面积 24860.250 / 9099.500 / 8889.750 / 9197.281 m²；外部邻接边 356 / 149 / 149 / 180。
  2. 天墉城位图：base64 3752 字符、解码 2813 字节、可走格 6203；按 5 cm 采样比较网格与位图（§2.5）。5 cm 采样看不见 1 cm 的平移，评审已逐格边界补测（§2.5）。
  3. tile 头的 x / z 包围盒与 32 m tile 格子完全重合（61/61）；y 范围 [-0.4, 3.6]；BV 叶节点量化后的 y 在 [0, 2]。
  4. GitHub API star（§5）。

### 0.4 分区稿分歧与编辑裁决

| # | 分歧 | 裁决 | 依据 |
|---|---|---|---|
| 1 | 契约闸位置：ContractSync 里手写解析器 + 同步前闸（分区 A）vs 构建期锁定测试（分区 C） | **构建期锁定测试**，保留 A 的规则表、可见性模型、账本、放行文件和 CI 逐提交复核 | 描述符精确，省掉约 400 行解析器及其交叉校验；同步后、提交前必须 `clean install`（AGENTS §4、§5），闸在这里生效。理由与代价见 §4.1.1、§6.1 |
| 2 | 放行方式：放行文件（A）vs `-Dxm.contract.accept=…` 加锁文件头（C） | **放行文件**，键 = 目标提交 + 变更 id；重锁时每条 BREAK 都必须有放行（「少一项」失败）；多余的放行行判 WARN（评审把「多一项」从失败改为 WARN，§1.10） | 文件留痕、CI 可复核；命令行参数不留痕 |
| 3 | 客户端可见的根：服务带 `OptionIsClientProtocolService` 或 `OptionIsPlayerService`（A）vs 只看前者（C） | **两者之一即可，另加帧根** | `ScenePlayerSync` 只标了 `OptionIsPlayerService`，却下发 66 等属性同步（mmorpg `proto/scene/player_state_attribute_sync.proto:67-69`）。会多算 `SceneScenePlayer`（`s2s_player_scene.proto:34-41`），但 Java gate 按这个 option 路由（`architecture.md:23`），本来就是 J 级，同级判，不影响结论 |
| 4 | 导航实现：用位图做格子导航（分区 B）vs 移植 Detour 子集读 `.bin`（C） | **移植 Detour 子集** | B 的前提「网格与位图格线重合到 ≤1 cm」不成立：编辑实测网格多 48.05 m²、最远偏 0.225 m（§2.5）。≤1 cm 只对体素化成立（`navmesh_baker.cpp:114-119`），轮廓简化（`:406`，1.3 体素）会填凹角；而且网格相对位图整体平移 +1 cm，低坐标一侧的墙比位图缩进 1 cm（评审复核，§2.5）。另外三张节庆图的位图不在 mmorpg 里，B 要先改 mmorpg 才能开工 |
| 5 | 导航数据坏了：启动失败（B）vs 逐行 fail-open（C，同基线） | **启动失败 + 构建期数据测试 + 整体开关** | Java 配表加载 fail-closed；基线逐行 fail-open 是为了挡旧的 UE 占位 bin（`navigation.cpp:62-65`），Java 在同步和构建两道就挡住了；静默放行等于在该场景关掉防穿墙 |
| 6 | 同步什么：位图 + 清单（B）vs 被引用的 `.bin`（C） | **被引用的 `.bin` + ContractSync 生成的 `navmesh.properties`** | 由裁决 4 决定 |
| 7 | MoveGuard 接导航：只移锚点、不退额度（B）vs 拆成 propose / commit、按实际位移扣额度（C） | **C** | 额度扣「锚点到最终接受点」的水平距离，记账准确；无导航场景的行为逐位不变（§4.4.4） |
| 8 | 调用点数：4 处加兜底（B）vs 3 处（C） | **3 处运行时调用 + 1 处启动探针** | `grep` 全仓：`player_scene.cpp:102`、`player_movement_handler.cpp:96-129`、`movement.cpp:68-82`，加 `navigation.cpp:59-88` 的探针；`FallbackLocationForPlayer` 是移动裁决内部的分支 |
| 9 | 撞墙裁决坐标：≈195.95（B）vs ≈195.96（C） | **195.96**（墙 196.01 − 0.05；评审更正，原稿写 195.961 / 196.011） | 评审复核：这条墙是无邻接边（`neis == 0`），坐标就是顶点坐标 orig.z + 0.25k = 40.01 + 156 = **196.01**（double 精确值）。Recast 的顶点都在 orig + 0.25k 的体素格上，小数部分只有 .01 / .26 / .51 / .76。编辑稿的 196.011 是 1 mm 步进采样多算了一步。注意：起点离墙不到 0.05 m 时回缩夹到 0，裁决点就是起点（`nav_query.cpp:131-132`），robot 期望必须避开这种情况（§9.3） |
| 10 | 文件指纹：git blob `ecab830f`（B）vs md5 `e4131573…`（C） | **两者都对**（不同的哈希） | `git ls-files -s data/scene_nav_bin`、`md5sum` 实测 |
| 11 | 「Java 无导航」的说法在哪 | 在 `SceneWorld.java:1313-1319` 的 javadoc、`MoveGuard.java:12`、`docs/reference/mmorpg-client-contract-{movement,scene,aoi}.md`，**不在** `architecture.md` §5（B 的说法有误） | `grep` |

---

## 1 契约变更报告与兼容性闸

### 1.1 基线：契约产物、生产者与消费者

| 产物 | 上游怎么产生 | Java 落点 | 消费者 |
|---|---|---|---|
| `proto/**`（113 个文件，etcd 不同步，`ContractSync.java:58-60`） | 人写。字段号规则只靠约定：**上线后**不复用、删字段写 `reserved`；**开发期已删字段可以复用**，只要重生成并完整编译所有模块（mmorpg `AGENTS.md:44`） | `xm-proto/src/main/proto/proto/**` | 客户端（C#）、Go robot、Java 全部进程 |
| `proto/message_id.txt`（244 行） | protogen 发号：已有的键保留原号（`service_register_info.go:174-187`）；新键先填 [0, 方法总数) 内的空洞，再往后追加（`:189-207`）；写回时只写当前还存在的方法（`:148-167`） | `xm-proto/src/main/resources/contract/message_id.txt` | 客户端把号编进代码（`mmorpg-client/Assets/Scripts/Net/MessageIds.cs`）；Java 运行时按「服务裸名 + 方法」解析（`MessageIdRegistry.java:28-39`） |
| `proto/event_id.txt`（49 行） | protogen 发号；删除的事件写墓碑 `N=reserved:<名>`（`internal/message_id.go:17-26`） | 同上目录 | Java 没有消费者（`tools.md:200`） |
| tip 枚举 proto + `tip_text.json` | 导表器按段发号：已发的号原样保留（`core/generators/enum_gen.py:414-426`） | `xm-table/src/main/proto/tip/`、`config-data/tables/tip_text.json` | 客户端按码查文案；Java 用生成的枚举 |
| operator 枚举 | 导表器的号池；state 文件坏了就从 1 重新发号（fail-open，`tools.md:120`） | `xm-table/src/main/proto/operator/` | Java 只参与编译 |
| 权威表 schema | 人写；字段号是 wire 契约，删字段写 `reserved`（mmorpg `data/AGENTS.md:82-85`、`:94`） | `xm-table/src/main/proto/*_table.proto`。非服务端列（`ContractSync.java:421-443`）和导表产物里还没有的字段（`:342-391`）被改写成 `reserved` | Java 编译期生成 `ConfigTables` |
| 表数据 `.pb` + `manifest.json` | 导表器产出；`version` 只在内容摘要变化时加 1（`core/manifest.py:50-56`、`:168-175`） | `config-data/tables/` | Java 加载时校验 sha256 和行数 |
| 导航网格 `data/scene_nav_bin/*.bin` | `tools/navmesh_baker`（§2） | 目前**不同步**（`tools.md:309`）；本批新增 `config-data/navmesh/` | C++ scene；本批起 Java scene |

### 1.2 基线已有的守卫（都只管「一次生成之内」）

- 事件号墓碑，一轮新增墓碑超过 4 个就中止（`tools.md:199`）。
- tip 轴自检：组墓碑、段不重叠、名字全局唯一（`enum_gen.py:414-426` 起）。
- 导表器解析 reserved，拒绝在同一版本里复用退休号（`tools/data_table_exporter/core/schema_proto.py:180-201`）。
- ContractSync：schema 与导表产物在同一提交内一致（`ContractSync.java:342-391`）；manifest 里的每张表都要有 schema（`:394-405`）。
- Java 运行时：`parseMessageIds` 遇到重复的号或键就拒绝（`MessageIdRegistry.java:262-290`）。

### 1.3 基线守不住的（跨版本才看得出来）

- **H1 消息号复用。** 删掉一个方法后，写回只写现存方法（`service_register_info.go:148-167`），它的号变成空洞，下一次生成被新方法填上（`:189-203`）。
  多个空洞配多个新方法时，填洞顺序取决于 Go map 的迭代顺序（`:198` `for uk := range unUseMessageId`），同一批 proto 两次生成可能得到不同的号。
  客户端把号编进了代码，旧客户端发的旧号会落到新方法上。事件号有墓碑（注释自称是「reserved 在事件号上的对应物」，`internal/message_id.go:21`），消息号没有。
- **H2 客户端号与内部号共用号空间**（`0=KVCompact`、`3=dbTest`）。内部号被客户端方法复用无害，客户端号被复用才有害：判级要看号的**旧含义**。
- **H3 字段号复用只靠约定。** 开发期允许删字段、甚至复用（`AGENTS.md:44`）。`proto/friend/friend.proto:78-90` 把危害写得很清楚：旧客户端发来的 `player_id` 会被当成新字段的值解出来，是静默的错数据。那一处是作者自觉写了 `reserved 1;`，没有工具检查。
- **H4 mmorpg CI 没有任何 proto 破坏性检查**（`.github/workflows/` 里没有 buf、breaking 或 message_id 比对）。
- **H5 类型收窄只有清单式约束**（`AGENTS.md:58-67`：坐标 double，GUID / 时间戳 / 货币 uint64），没有跨版本检测。
- **H6 表字段改号只靠 review**（`data/AGENTS.md:85`「review 时盯住它」）。
- **H7 operator 号池 fail-open**，state 坏了整池重发且不报错。
- **H8 manifest `version` 没人读**，表数据退回旧导出时两端都发现不了。

### 1.4 Java 现状

- `--check` 只回答「一致 / 不一致」（`ContractSync.java:99-107`、`:626-647`），说不出变了什么、危不危险。
- 漂移 workflow 只列不一致的文件名，只报告不判红（`contract-drift.yml:48-71`；deploy-ci-spec `:1033` Q5）。
- 消息号被复用时 `MessageIdRegistry` 按新表解析，没有任何一步能发现（`tools.md:228`）。
- 方法被删的兜底是各模块用真注册表解析自己号的单测（gate、login、friend、guild、team、trade、battle、robot、scene 都有，`MessageIdRegistry.loadFromClasspath()` 出现在它们的测试里）；
  **xm-chat 没有**，只在启动时解析（`ChatConfiguration.java:22-23`、`ChatDispatcher.java:55-56`）。
- **Java 自己的线路和存储也用到 mmorpg 的「内部」消息**：6.2 起 `contracts.kafka.BattleResultEvent` 的字节进 Java 的 Kafka `match-results`（battle-node-spec `:150`、match-spec `:585`），
  也写进 Redis `xm:battle:activity-result:<id>` 保留 7 天（scene-battle-spec `:548`）；`xm-battle` 已经 import 它（`xm-battle/.../port/BattleResultSink.java:3`）。
  滚动升级时新旧进程互读这些字节，所以这类消息的线格式对 Java 同样是契约（J 级）。

### 1.5 比较对象：契约面

**契约面**是契约里「每个号的含义」的规范化文本：一行一个事实，节内按键排序，LF 行尾，与源文件顺序、注释、注入的 java option 无关。
它由构建期从描述符渲染（§4.1），提交在 `contract/SURFACE.txt`。用途：锁定测试的比较对象；`git log -p contract/SURFACE.txt` 就是人能读的契约历史；CI 逐提交复核的输入。

样例（数据取自 `26ceb70ca`，节选）：

```
# 由 xm-contract-check 重锁生成，不要手改（AGENTS §1）。两版共享契约里每个「号」的含义。
format=1

[msgid]          # 号 键 服务.方法 请求 应答 服务标记
14 ClientPlayerLoginCreatePlayer ClientPlayerLogin.CreatePlayer .loginpb.CreatePlayerRequest .loginpb.CreatePlayerResponse client
66 ScenePlayerSyncSyncBaseAttribute ScenePlayerSync.SyncBaseAttribute .ActorBaseAttributesS2C .Empty player
0 KVCompact - - - unresolved
[proto.file]     # 文件 syntax 包
proto/common/base/tip.proto proto3 -
[proto.type]     # 全名 种类 文件 Java 类（嵌套类用 $）
.TipInfoMessage message proto/common/base/tip.proto com.game.proto.TipInfoMessage
[proto.field]    # 消息 号 标签 类型 名
.friendpb.AddFriendRequest 2 - uint64 target_player_id
[proto.reserved]
.friendpb.AddFriendRequest 1
.friendpb.AddFriendRequest "player_id"
[proto.enum]
.eBattleOutcome 0 BATTLE_OUTCOME_ONGOING
[proto.service]
.ScenePlayerSync player
[proto.frame]    # 帧根（docs/reference/mmorpg-client-contract-login.md:32）
.MessageContent
[event]
0 AcceptMissionEvent
[tip]            # 码 枚举 名 文案（\n 转义）
1003 common_error kServiceUnavailable 服务不可用
[operator] …  [const] …
[table.sheet]
GuildRule .mmorpg.cfgtable.v1.GuildRuleTable pk=id
[table.field]    # 表 号 状态 标签 类型 名 选项
GuildRule 8 pending - uint32 activity_join_min_hours
Mission 1 live - uint32 id bit_index
[table.data]
manifest version=19 digest=f33c8003a3ab data_commit=1bca719e5557 dirty=true
GuildRule rows=1 sha256=…
[table.row]      # 表 主键 序号 行指纹
GuildRule 1 0 3f2a…
[navmesh]        # 文件 sha256 tile 数；bind = BaseScene 配置号 → 文件 + 出生点
main_scene.bin 0c1d… 61
bind 1 main_scene.bin spawn=180,200,0
```

渲染规则：

- **身份按全名认，不按文件路径认**。全名带前导点（protoc `type_name` 的口径）。mmorpg 以后整理目录（`architecture.md:17`）只表现为文件列变化，判 INFO。
- map 写成 `map<K,V>`，不展开 Entry；proto3 `optional` 写成标签 `optional`。
- 只保留影响线格式或 Java 代码生成的选项：表 schema 的 `cfg_key`、`cfg_multi`、`cfg_index`、`cfg_bit_index`、`cfg_tip_ref`、`cfg_fk`、`cfg_gfk`、`cfg_owner`，
  以及表达式列的 `cfg_expr_type` 和**按声明顺序**的 `cfg_expr_param`（`TableSchemaReader.java:164-165` 读它们，生成的求值方法按这个顺序收参数）；
  `cfg_sheet`、`cfg_primary_key` 已体现在 `[table.sheet]` 行里；服务的 `OptionIsClientProtocolService`、`OptionIsPlayerService`。
  只给导表器用的选项（`cfg_col`、`cfg_slots`、`cfg_shape`、`cfg_composite`、`cfg_source_file`）和 mmorpg 建表用的选项不进契约面。
- `[proto.type]` 带 Java 类名（按 `ContractSync.javaPackageFor` 与 `java_multiple_files` 算出）。CI 复核只用 JDK 跑 `ContractGate`，
  手里只有两份契约面文本、没有描述符，要靠这一列把 Java 源码里的 import 反查成 proto 全名（§1.6 的 J 级）；全名本身分不出「包」和「嵌套」。
- `google.protobuf.*` 是 protoc 自带的固定类型，不渲染；引用它的字段照常写类型名。
- `[proto.file]` 记每个文件的 `syntax`（目前 113 个全是 proto3，没有 oneof）：proto2 ↔ proto3 会改变字段存在性和 repeated 标量的默认 packed 编码。
- **ContractSync 改写出来的 reserved 不当普通 reserved**：未导出字段写 `pending`、非服务端列写 `owner=<值>`，签名取改写前的权威 schema。
  否则这些字段将来「落地」时会被误判为「复用了 reserved 号」。为此 ContractSync 要在 `SOURCE.properties` 里带上号和签名（§4.1.6）。
- `[table.row]`：只解 `.pb` 外层字段 1（每个长度前缀块是一行）和行内字段 1 的 varint（主键，缺省为 0），整行字节取 sha256。`cfg_multi` 表同一主键多行，用「主键 + 序号」。当前 34 张表合计 359 行。
- `[navmesh]` 的 `bind` 行由渲染器从 BaseScene 表数据算出（配置号、`nav_bin_file` 的文件名部分、`spawn_x/y/z`）。
  BaseScene 的行变化本来只是 `data-rows` INFO，但换绑网格或挪出生点会改变客户端可见的撞墙与落点，单独成一行才能按 WARN 报（§1.8 `navmesh-binding`）。
- `format=N`：渲染规则一变就加 1（§1.8 的 `surface-format` 规则）。
- 契约面**不记** mmorpg 提交号（它在 `SOURCE.properties` 里）：上游前进了而契约内容没变时，不需要重锁。
- 规模约 5 千行。

### 1.6 可见性：C / J / I

| 级 | 定义 |
|---|---|
| **C** 客户端可见 | 根：①服务带 `OptionIsClientProtocolService` **或** `OptionIsPlayerService` 时，其全部方法的请求 / 应答类型；②帧根（`MessageContent`、`ClientRequest`、`ClientTokenVerifyRequest/Response`、`GateTokenPayload`）。从根沿字段类型递归求闭包。tip 码一律 C |
| **J** Java 在用 | Java 生产代码（`xm-*/src/main/java/**`）引用的契约生成类，反查为 proto 全名后求闭包，去掉已属 C 的部分。反查表就是契约面 `[proto.type]` 的 Java 类名列（渲染时由描述符精确算出：java_package + multiple_files + 嵌套），所以 CI 复核不需要描述符；通配 import 按整包算。表 schema、operator、常量、导航网格一律 J |
| **I** 内部 | 其余 |

- J 与 C 同级判，**I 降一级**（BREAK → WARN → INFO）：Java 不与 C++/Go 混部（`architecture.md:27`），I 级的线格式只有 mmorpg 自己在意。
- 消息号的可见性按**旧含义**判（H2）。
- J 级不写进契约面（否则 Java 一开始用某个类型，锁就要变）；比较时按当前源码现算。CI 复核一律用 HEAD 的源码算 J，属近似（§6.1 第 7 条）。

### 1.7 号位墓碑账本 `contract/RETIRED.txt`

- 号位有七种：`msgid:<号>`、`field:<消息>#<号>`、`enum:<枚举>#<号>`、`tip:<码>`、`event:<号>`、`operator:<枚举>#<号>`、`tfield:<表>#<号>`。表数据的行主键不记账（增删行是策划改表的常态，§8 CQ8）。
- 记账：某号位旧侧有、新侧没有，就把它的旧身份记一行，例如 `field:.friendpb.AddFriendRequest#1 = - uint64 player_id @26ceb70ca577`。显式写的 `reserved N` 消失时也记成 `= reserved`。
- 查复用：新侧某号位在旧契约面里是空的、但账本里有：身份相同判 INFO「恢复」；身份不同判**复用**（§1.8）。
- 只增不减：重锁时 `新账本 = 旧账本 ∪ 本次退役`；CI 复核另查「旧账本 ⊆ 新账本」，不满足判 `ledger-tamper`（BREAK，**不可放行**）。
- 播种：引入时为空。Java 只做过三次契约同步（`723067f` → `e02b39d` → `7ab4d9b`），其间 `message_id.txt` 只追加了 239–243、没有复用；mmorpg 本地没有历史可挖（§8 CQ6）。

### 1.8 判级规则

表中是 C / J 级的判级，I 级降一级（另注明的除外）。每条有稳定的变更 id `<规则>:<主体>`，放行按 id 写。

| 类别 | 变更 id | 触发 | 级 | 依据 |
|---|---|---|---|---|
| 消息号 | `msgid-reuse:<号>` | 同一号换了键且不是改名；或账本里退役的号被另一个键拿走 | BREAK（旧含义 C/J）/ INFO（旧含义 I） | H1、H2 |
| | `msgid-renumber:<键>` | 同一键换了号 | BREAK | 旧客户端发旧号 |
| | `msgid-signature:<号>` | 号和键不变，请求或应答类型变了 | BREAK；结构等价时 WARN | |
| | `msgid-rename:<号>` | 同号，服务名或方法名变了，请求 / 应答相同或结构等价 | WARN | 线格式兼容，生成的处理器名会变 |
| | `msgid-visibility:<号>` | 两个服务 option 变了 | WARN | Java gate 按 `OptionIsPlayerService` 路由 |
| | `msgid-removed:<号>` | 键消失 | WARN，记账，并提示「这个号下次生成可能被分给新方法」 | `service_register_info.go:189-203` |
| | `msgid-unmapped` / `msgid-orphan` | C 级方法没有号 / 某号对不上任何方法 | WARN | 上游没重跑 protogen。etcd 方法（`0=KVCompact` 等，`proto/etcd` 不同步）在契约面里本来就是 `unresolved`：只报**新出现**的 unresolved 号，旧侧已是 unresolved 的不重复报，不需要维护 etcd 服务名单 |
| | `msgid-added:<号>` | 新键 | INFO；C 级新方法列入「Java 待做」 | |
| 字段 | `field-renumber:<消息>.<名>` | 同名换号 | BREAK | |
| | `field-retype:<消息>#<号>` | 同号换类型 | 按 §1.9 | |
| | `field-label:<消息>#<号>` | 单值 ↔ repeated | BREAK | |
| | `field-presence:<消息>#<号>` | 加 / 去 proto3 `optional`；map ↔ repeated Entry | WARN | 线格式兼容 |
| | `field-rename:<消息>#<号>` | 同号同类型只改名 | WARN | 重新生成后编译会断 |
| | `field-reuse:<消息>#<号>` | 旧侧是 `reserved N` 或账本里退役过，现在被别的身份占用 | BREAK | H3；`friend.proto:78-90` |
| | `field-removed:<消息>#<号>` | 号消失 | WARN；没写 reserved 的注明「已记账防复用」 | `AGENTS.md:44` |
| | `reserved-dropped:<消息>#<号>` | reserved 消失、号空着 | WARN | |
| | `field-added` | 新号 | INFO | |
| 枚举值 | `enum-renumber` / `enum-reuse` | 同名换值 / 退役值被别的名字占用 | BREAK | |
| | `enum-rename` / `enum-removed` / `enum-added` | | WARN / WARN / INFO | |
| 类型与帧 | `frame-root:<消息>` | 帧根消失 | BREAK | xm-net / gate 帧编解码 |
| | `type-moved:<旧>→<新>` | 一个类型消失、同时出现结构等价的新全名 | WARN | Java 包名随 package 变（`ContractSync.java:556-575`） |
| | `type-removed` / `type-added` / `file-moved` | | WARN / INFO / INFO | |
| | `oneof-membership:<消息>#<号>` | 已有字段移进 / 移出已有 oneof | BREAK | 目前全仓 0 个 oneof，规则先定 |
| | `file-syntax:<文件>` | proto2 ↔ proto3 | WARN | 存在性语义与 repeated 标量的 packed 缺省变了；解析器两种编码都收，线格式兼容 |
| tip（全 C） | `tip-reuse:<码>`、`tip-renumber:<名>` | 同码换名 / 同名换码 | BREAK | 上游保证已发号不变，出现即上游 state 坏了 |
| | `tip-removed` / `tip-text` / `tip-added` | | WARN / INFO / INFO | |
| 其它注册表 | `operator-renumber` / `operator-reuse` | | WARN | Java 不落库 operator 码；提示上游 fail-open（H7） |
| | `const-value` / `const-removed` | | WARN | |
| | `event-renumber` / `event-reuse` / `event-tombstone-dropped` | | WARN | Java 没有消费者（§8 CQ7） |
| 表 schema | `table-field-renumber` / `-retype` / `-label` / `-reuse` | | BREAK | `data/AGENTS.md:82-85`、`:94` |
| | `table-bit-index:<表>#<号>` | 加 / 去 `cfg_bit_index` | BREAK | 存档位图下标「改动即错位」 |
| | `table-pk:<表>` | 主键列或主键上的 `cfg_multi` 变了 | WARN | 生成的查找方法变，编译期断 |
| | `table-expr-params:<表>#<号>` | 表达式列已有参数的**顺序或名字**变了（参数个数不变） | BREAK | 生成的求值方法按声明顺序收参数、全是 double：调用方照旧能编译，实参却被静默错位 |
| | `table-expr:<表>#<号>` | 加 / 去表达式列、`cfg_expr_type` 变、参数个数变 | WARN | 生成的方法签名变，编译期断 |
| | `table-field-rename` / `table-key` / `table-index` / `table-removed` | | WARN | |
| | `table-owner:<表>#<号>` | server/common → 其它（反向 INFO） | WARN | |
| | `table-fk` / `table-pending-landed` / `table-pending-new` / `table-added` | | INFO | |
| 表数据 | `data-rows:<表>` | 行增加或修改 | INFO（列出前 20 个主键） | |
| | `data-rows-removed:<表>` | 行删除 | WARN；位序表（Mission / Reward）另标注 | |
| | `manifest-version-regress` / `manifest-dirty` | `version` 变小 / 新侧 `data_dirty=true` | WARN | `manifest.py:168-175` |
| 导航 | `navmesh-changed:<文件>` | sha256 变了 | WARN（客户端可见行为可能变：跑 `navwall`、金样） | §3 |
| | `navmesh-added` / `navmesh-removed` | | INFO / WARN | |
| | `navmesh-binding:<配置号>` | `bind` 行变了：换了网格文件或出生点，或配置号新增 / 消失 | WARN（新增 INFO） | 撞墙位置、进场落点对客户端可见；跑 `navwall` 与 G1 |
| 工具 | `ledger-tamper` | 旧账本行在新账本里不见了 | BREAK，**不可放行** | |
| | `accept-unused:<键>` | 放行文件里键为目标提交的一行对不上任何 BREAK | WARN | 多半是 id 打错；打错时真正的 BREAK 仍未放行，照样拦住（§1.10） |
| | `accept-removed:<键>` | 放行文件的旧行消失（只追加被破坏） | WARN（只在 CI 复核里能看到） | 已消费的放行行删了不影响结论，只是丢了留痕 |
| | `surface-format` | format 变了 | INFO，本次不逐项比较 | |
| | `surface-format-with-sync` | format 变了，且新旧 `SOURCE.properties` 的 `mmorpg.commit` 也不同 | BREAK，**不可放行**（格式升级必须单独提交） | |

### 1.9 字段类型变更（`field-retype`，C / J 级）

| 旧 → 新 | 线格式 | 级 |
|---|---|---|
| int32→int64、uint32→uint64 | 同为 varint；旧端截断大值 | WARN |
| 64 位收窄到 32 位 | varint，截断 | **BREAK**（`AGENTS.md:58-67` 禁止的正是这个） |
| 有符号 ↔ 无符号 | 负数变成巨大正数 | BREAK |
| sint32/sint64 ↔ 其它整型 | zigzag 不兼容 | BREAK |
| enum ↔ int32/uint32、bool ↔ 整型 | 同为 varint | WARN |
| fixed32 ↔ sfixed32、fixed64 ↔ sfixed64 | 线型同、符号变 | BREAK |
| double ↔ float、32 位定长 ↔ 64 位定长、定长 ↔ varint | 线型不同 | BREAK（另见 `AGENTS.md:60` 坐标 double） |
| string ↔ bytes、message ↔ bytes | 同为长度前缀 | WARN |
| message A → message B | 长度前缀 | 结构等价 INFO；否则 WARN 并递归列出差异 |
| 标量或字符串 ↔ message | | BREAK |

「结构等价」：字段号集合相同，逐号比较标签、线型和嵌套消息结构（递归）都等价。递归深度上限 8，遇到环按等价处理。

### 1.10 放行文件 `contract/ACCEPTED_BREAKS.txt`（人写、只追加）

```
# 契约破坏性变更的显式放行。人写、只追加，不是同步产物，也不是重锁产物。
# 键 = <目标 mmorpg 提交前 12 位>/<变更 id>；值 = 理由（谁确认、客户端怎么处理）。值为空视为未放行。
3f1e0d2c9a11/msgid-reuse:212 = （示例）上游删 X 后 Y 复用 212；客户端同批重出包，已与 mmorpg 确认
```

- 键绑定目标提交（新侧 `SOURCE.properties` 的 `mmorpg.commit`）：同一种破坏下次同步再出现，必须重新确认。
- 重锁时每条 BREAK 都必须有放行，否则失败。放行文件里键为目标提交、却对不上本次任何 BREAK 的行判 `accept-unused` **WARN**，重锁和 CI 复核都一样（评审更正：原稿重锁时判失败）。
  理由：同一目标提交上再次重锁很常见，例如格式升级、ContractSync 改了 pending 签名、只追加了放行注释。这时上次已消费的放行行仍以当前目标提交为键，
  而本次差异里已经没有那条 BREAK，判失败就是误报。至于 id 打错，真正的 BREAK 仍然没有放行，照样失败，拦截效果不受影响。
- `ledger-tamper`、`surface-format-with-sync` 不接受放行。
- 不设命令行放行开关：不留痕，CI 也无从复核。

### 1.11 不比什么

- 不是同步产物的契约：帧字节由 xm-net 的 golden 单测管；HTTP JSON 形状来自手写 DTO。
- 与含义无关的：注释、import、注入的 java option、建表用 option、文件顺序。
- tip 段：Java 没有段元数据（`tools.md:98-108`）。mmorpg 出 `tip_meta.json` 后再加 `tip-segment` 规则。

---

## 2 导航网格：基线烘焙与数据

### 2.1 烘焙管线（`tools/navmesh_baker/navmesh_baker.cpp`，893 行）

| 项 | 内容 | 出处 |
|---|---|---|
| 输入（三选一） | `--painted-city`：解析客户端 `TianyongPaintedCity.cs` 里内嵌的 `WalkMaskBase64`；`--mask-base64`：同格式的独立位图文件，必须显式给 `--probe`；`--obj`：OBJ 三角网（给将来的真 3D 场景，目前没用） | `:3-12` |
| 位图格式 | 150×150 位、MSB 优先、`i = cy*150+cx`；cy=0 在 Unity z=300、向下递减；cx=0 在 Unity x=50；单格 2 m | `:100-109` |
| 位图编码 | 恰好 2813 字节；base64 恰好 3752 字符、只有末尾一个 `=`；不接受中间 padding 或截断 | `:236-251` |
| 几何 | 每行连续可走格合成一个 quad（法线 +Y），不可走格留洞；quad 内缩 1 cm（`kQuadInset`）；地面放在 y = −ch = −0.2，取整后可走面回到 y≈0 | `:111-119`、`:25-41` |
| 体素与 agent | cs=0.25、ch=0.2；agent 半径 0、高 1.8、爬升 0.35；坡度 50°；tile 128 体素（32 m） | `:366-378`、`:398-415` |
| 轮廓 | `maxSimplificationError = 1.3` 体素；`minRegionArea = 8×8`、`mergeRegionArea = 20×20` | `:406-408` |
| ledge 过滤 | painted-city / mask 模式**跳过** `rcFilterLedgeSpans`（否则边界内缩 0.25 m，引发 2026-09-08 每秒 4 次的回拉），OBJ 模式保持开启 | `:29-37`、`:478-481`、`:776-778` |
| 多边形标记 | 全部 `flags = 1` | `:558` |
| 网格参数 | `orig` = 几何 bmin（y 再放宽一个 ch）；`maxTiles = nextPow2(tx*ty)`、`maxPolys = 2^20`；UE5 把 walkableHeight / Radius / Climb、`bvQuantFactor` 挪进了 mesh params | `:825-847` |
| 出生点探针 | `--probe`（Unity 坐标）用 `findNearestPoly`、范围与运行时相同（水平 ±2、垂直 ±4）；任一探针不在网格上就**不写文件**、非零退出 | `:678-712` |
| 确定性 | 写文件前把头部结构体清零（`:612`），同一输入产出逐字节相同的 bin；`test_baker.py:31-40` 断言两种输入入口产物相同 | |
| 构建 | 与运行时同一套 ue5navmesh 源码，`dtReal=double` | `CMakeLists.txt:1-37` |

### 2.2 数据文件与表

- BaseScene schema：`id=1, nav_bin_file=2, spawn_x/y/z=3/4/5`，出生点用服务器 Z-up 坐标（`data/schema/basescene_table.proto:17-22`）。Java 已同步，`nav_bin_file` 是服务端可读列（`xm-table/src/main/proto/basescene_table.proto:21`）。
- 21 行（`generated/tables/basescene.json`）：

  | 场景配置 | `nav_bin_file` | 出生点（服务器） |
  |---|---|---|
  | 1、5–16 | `data/scene_nav_bin/main_scene.bin` | (180, 200, 0) |
  | 2 | `penglai_scene.bin` | (170, 220, 0) |
  | 3 | `donghai_scene.bin` | (180, 200, 0) |
  | 4 | `lanxian_scene.bin` | (180, 200, 0) |
  | 17–19 | `dungeon_scene.bin` | (180, 200, 0) |
  | 20–21 | `mirror_scene.bin` | (180, 200, 0) |

- `data/scene_nav_bin/` 有 7 个文件，被引用 6 个；`tianyong_scene.bin` 没有引用。main / dungeon / mirror / tianyong 四份逐字节相同（git blob `ecab830f`、md5 `e4131573…`），**实际只有 4 份不同几何**：

  | 几何 | 字节 | tile | maxTiles | orig（Unity） | 多边形面积 | 外部邻接边 |
  |---|---|---|---|---|---|---|
  | main（天墉） | 175024 | 61 | 128 | (70.01, −0.40, 40.01) | 24860.250 m² | 356 |
  | penglai | 86064 | 34 | 64 | (112.01, −0.40, 68.01) | 9099.500 m² | 149 |
  | donghai | 80520 | 40 | 64 | (98.01, −0.40, 32.01) | 8889.750 m² | 149 |
  | lanxian | 102088 | 45 | 64 | (102.01, −0.40, 36.01) | 9197.281 m² | 180 |

  四份都是 walkableHeight 1.8、walkableRadius 0、climb 0.35、bvQuantFactor 4、tile 32 m、maxPolys 1048576；tile 数与设计文档一致（`docs/design/scene-navmesh-pipeline.md:160-161`）。
- 路径：C++ 用 `GetDataRootDir() + nav_bin_file`（`navigation.cpp:105`）。
- **mmorpg 仓库里没有任何一份位图**：天墉那份在客户端源码里，节庆三地在客户端 `Resources/World/FestivalRegions/<key>/walkmask.txt`（`FestivalRegionMap.cs:22`）。现有 bin 不能只靠 mmorpg 仓库重新烘焙。

### 2.3 文件格式（MSET v1，UE5 double 布局，小端；编辑实测）

| 段 | 布局 |
|---|---|
| 文件头（96 B） | `int32 magic='MSET'`（字节 `54 45 53 4d`）、`int32 version=1`、`int32 numTiles`、4 B 填充、`dtNavMeshParams{double walkableHeight, walkableRadius, walkableClimb, bvQuantFactor, orig[3], tileWidth, tileHeight; int32 maxTiles, maxPolys}` |
| tile 记录头（16 B） | `uint64 tileRef`（main 第 0 个 tile 是 `1<<27`：salt=1、tileBits=7、polyBits=20）、`int32 dataSize`、4 B 填充 |
| `dtMeshHeader`（80 B，**没有 `DNAV` 魔数**） | `u16 version=7, layer=0, polyCount, vertCount; i32 x, y; u16 maxLinkCount, detailMeshCount, detailVertCount(全 0), detailTriCount, bvNodeCount(=2·polyCount), offMeshConCount(全 0), offMeshBase, pad; double bmin[3], bmax[3]` |
| verts | `double[3] × vertCount`，y 全为 0.000 |
| polys（32 B） | `u32 firstLink; u16 verts[6]; u16 neis[6]; u16 flags(=1); u8 vertCount; u8 areaAndType`；`neis`（上游语义）：`0` = 墙（无邻接）；`1..0x7fff` = 同 tile 多边形下标 + 1；`0x8000 \| dir` = 跨 tile 的 portal，`dir` 为 0–7 的方向。评审按 `neis == 0` 逐条取墙，与 §9.2.3 的轴向表一致 |
| links（16 B） | `maxLinkCount` 个槽位，加载时由 `addTile` 建立（文件里的内容无意义） |
| detail meshes（6 B） | `u16 vertBase, triBase; u8 vertCount, triCount` |
| detail tris（4 B） | |
| BV 树（16 B） | `u16 bmin[3], bmax[3]; i32 i`（i<0 为 escape 节点） |
| off-mesh 连接 | 全为 0 个 |

- 各段从 tile 数据起点按 **8 字节对齐**。4 份文件 180 个 tile 全部「分段之和 = `dataSize`」，文件尾 = 文件长度（评审用独立探针复算，结论相同）。
- 评审复核的其它数据事实：四份网格的**顶点 y 全部严格等于 0.0**（不是近似），所以吸附后的高度、裁决点的 z 都精确为 0，robot 的 1e-4 位置比较不受影响；
  多边形数 848 / 417 / 378 / 486，顶点数 3–6 都有；Recast 多边形顶点是整数体素坐标，水平坐标都在 `orig + 0.25k` 的格上（小数部分只有 .01 / .26 / .51 / .76），
  评审沿 §9.2.3 的 16 条轴向射线取到的墙全部符合。
- tile 头的 x / z 包围盒与 32 m tile 格子完全重合（61/61），y 范围 [−0.4, 3.6]；BV 叶节点量化后的 y 在 [0, 2]。这决定了吸附的垂直边界（§3.2）。
- 这是 UE5 改过的紧凑头，不是上游 Detour 格式：上游 `dtMeshHeader` 以 `DNAV` 开头、参数是 float。所以 recast4j 等上游移植**不能直接读**（recast4j 的读取器要求 `DT_NAVMESH_MAGIC`，本机未核实其源码，不影响结论：它的 star 已经否决了它，§5）。

### 2.4 基线加载器的缺陷（`cpp/libs/services/scene/spatial/system/recast.cpp:31-94`）

- 遇到 `tileRef == 0` 或 `dataSize == 0` **静默 break** 并按成功返回（`:77-78`）；分配失败也 break（`:81`）；`addTile` 的返回值不检查（`:90`）；不检查文件长度。
- 截断或损坏的文件可能「少几块 tile 也算加载成功」，只靠出生点探针兜底。

### 2.5 网格与客户端位图的关系（编辑实测，天墉城）

- 位图可走格 6203 个，按 5 cm 采样（3600 万点）比较：**只在位图里、不在网格里的样本为 0；只在网格里的 19220 个样本合 48.05 m²**；
  网格多出部分离最近可走格最远 **0.225 m**，在 Unity (87.475, 204.225)。网格面积折合 6215.06 格。
- 结论（评审更正）：凹角处被轮廓简化填了角（1.3 体素 = 0.325 m 以内）。基线注释里「边界与 mask 格线精确重合」（`navmesh_baker.cpp:28`、`constants/nav.h:14-16`）只对体素化成立。
  但原稿的「网格**完全包住**位图」不对：5 cm 采样看不见下面这条 1 cm 的带子。
- **网格相对位图整体平移 +1 cm**（评审实测，天墉城）：几何 bmin 是内缩后的 quad 角 `格线 + 0.01`（orig = 70.01 / 40.01），体素格从这里起算。
  于是低坐标一侧的 quad 边正好压在体素边界上，高坐标一侧的 quad 边落在体素内部、整格被覆盖。墙因此都在 `格线 + 0.01`：
  - 高坐标一侧（Unity +x / +z，即服务器 +y / +x）网格比位图多 1 cm。例：服务器 +x 墙在 196.01，位图可走到 196.000，196.005 已不可走；
  - 低坐标一侧（服务器 −x / −y）网格比位图少 1 cm。例：服务器 −x 墙在 52.01，位图在 (52.000, 52.010) 内可走、网格上没有。
  天墉位图里朝低坐标的可走格边有 1298 条，朝高坐标的也是 1298 条，所以两侧面积大致抵消，48.05 m² 的净增量基本都来自填角。
- 节庆三地没有位图可采样，但面积同样比文档记录的可走格多：2274.87 / 2222.44 / 2299.32 格，对 2265 / 2218 / 2295 格（`scene-navmesh-pipeline.md:160`）。
  三地烘焙方式相同，orig 也是 `格线 + 0.01`，同样有 +1 cm 平移；§9.2.3 轴向表里的墙坐标全是 `xx.01`，与此相符。
- 对客户端的含义：客户端只让脚底点走在可走格里（`TianyongPaintedCity.cs:87-95`）。合法客户端只可能落在低坐标一侧那条 1 cm 带子里，此时服务器的裁决点是「墙 + 0.05」，
  与客户端相差不到 0.06 m，远小于 0.5 m 的纠偏阈值，**不会回 137**，结论不变。网格多出的边角只影响异常输入的夹持位置（≤0.225 m）。

### 2.6 导航数据算不算共享契约

- AGENTS §1 列的共享面里没有 `.bin`：客户端不读它，它的格式依赖 C++ ABI 和私有的 UE5 Detour 布局。
- 但它决定了**客户端可见的行为**：137 的有无与落点、进场坐标；它被已同步的 BaseScene 表按路径引用。
- 本稿的定位：**`.bin` 作为「配置表引用的服务端数据」随配置表一起同步**（同步产物，不许手改），列入 AGENTS §1 受管路径；它不是客户端协议的一部分，Java 用自己的读取器消费它。格式变化由同步时的结构校验和构建期读取测试 fail-closed 地暴露。

---

## 3 导航网格：服务器侧查询与调用点

### 3.1 加载（`cpp/libs/services/scene/spatial/system/navigation.cpp`）

- 配置表加载成功后调用 `NavigationSystem::LoadNavBins()`（`core/config/config.cpp:5-8`）。
- 同一路径只加载一次，多行共享一个 `shared_ptr`（`:94-110`）；加载或 `navQuery.init` 失败返回 null、不注册（`:35-57`），`kMaxMeshQueryNodes=4096`（`constants/nav.h:5`）。
- **每一行用自己的出生点探针一次**：吸附不上就不注册这一行，打 ERROR（`:59-88`）。效果是**该场景 fail-open**：移动不校验，进场只修 (0,0,0)。
- `SceneNavManager` 是 `thread_local`（`manager/scene_nav.h:24-27`），只在启动线程加载过（设计文档自认缺陷，`scene-navmesh-pipeline.md:84-85`）；`NavComp` 不可拷贝、不可移动（`comp/nav_comp.h:13-23`）。

### 3.2 查询 API（`system/nav_query.h:11-42`、`nav_query.cpp`）

对外一律收发服务器坐标（Z-up），内部换轴 `nav = (s.y, s.z, s.x)`、`server = (n.z, n.x, n.y)`（`nav_query.cpp:35-45`；与客户端 `WorldCoordinateConverter.cs:28-32` 一致）。
常量：吸附范围 `{2, 4, 2}`（`:19`）、raycast 途经多边形上限 256（`:23`）、撞墙回缩 0.05 m（`:27`）；过滤器一律默认 `dtQueryFilter`。

| API | 语义 |
|---|---|
| `SnapToMesh`（`:74-84`） | `findNearestPoly`，`ref != 0` 判成功（`:47-54`）；输出网格上的点，高度取网格高度 |
| `ValidateMove(from, to)`（`:86-149`） | ① 吸附起点，失败返回 false 且**不写** clamped（`:89-94`）。② 从吸附后的起点向**未吸附**的终点做 2D `raycast`（`:96-103`）。③ 查询本身失败 → clamped = 起点（`:104-109`）。④ `t > 1`（没撞墙）→ 吸附终点，成功返回 true；吸附失败落到 ⑤（`:111-123`）。⑤ 撞墙点 = `start + dir · max(0, min(t,1) − 0.05/|dir|)`，dir 与 |dir| 是**三维**的（`:125-136`）；再吸附，吸不上取起点；返回 false（`:138-148`） |
| `FindPath`（`:151-197`） | 两端吸附 → `findPath` → `findStraightPath`；部分结果照常返回。**全仓没有调用方**（`scene-navmesh-pipeline.md:82`） |

由数据推出的边界语义（按上游 Detour 推算，待金样确认）：

- **垂直方向不对称**：上游 BV 查询先把查询框夹进 tile 包围盒再量化，叶节点之外不再精确比较。本批数据 tile y 范围 [−0.4, 3.6]、叶节点量化 y ∈ [0, 2]，
  于是远低于地面的点（如 z = −100）仍能吸附，而 z 高于约 4.1–4.6 m 的点吸附不上。
  评审按上游公式推算：叶节点的 y 上界只有 0 和 2 两种（评审复核四份文件都如此），
  所以 z < 4.1 一定能吸附；4.1 ≤ z < 4.6 只有落在 y 上界为 2 的叶节点上才行；z ≥ 4.6 一定吸附不上。
- **水平方向**：tile 包围盒等于 32 m 格子（§2.3），框夹取不会造成水平方向的「远距离吸附」。但吸附范围并不严格是 ±2 m 的方框。
  查询框量化时向外取整，上游写法是：
  - 下界 `(u16)(q·(clamp(min) − tbmin)) & 0xfffe`；
  - 上界 `(u16)(q·(clamp(max) − tbmin) + 1) | 1`（评审更正：原稿漏了「+1」）。
  其中 q = bvQuantFactor = 4，步长 0.25 m，两端各可外扩到约 0.5 m。叶节点用文件里存的量化包围盒，是多边形的包围盒、不是多边形本身；
  命中后不再与查询框精确比较，`findNearestPoly` 只在候选里取最近点。所以离网格超过 2 m 的点仍可能吸附成功，外扩量不是一个固定值。
  Java 必须逐位照搬这套量化，不能自己按距离判，测试也不能断言「≤2.5 m」之类的界。
- **候选顺序与并列**：tile 按 y 外层、x 内层遍历（`calcTileLoc` 求出的范围），tile 内按 BV 数组顺序；取最近点时严格小于才替换，并列时先到的胜出。
  点正好落在两个多边形的公共边上时，两者距离都是 0，选中哪个 polyRef 由这个顺序决定。落点相同，但它决定了 raycast 从哪个多边形出发。
- **回缩下限**：起点离墙不到 0.05 m 时 `finalT` 夹到 0，裁决点就是起点。
- **终点 z 很大**：raycast 是 2D 的，可能判通过；终点吸附失败后走 ⑤，3D 长度很大，回缩后的点 z 仍很大、吸附失败，裁决回到起点。

### 3.3 运行时调用点（全部三处）

| 调用点 | 查询 | 行为 | 客户端可见效果 |
|---|---|---|---|
| 进场落位：`player/system/player_scene.cpp:102` → `SceneSpawnSystem::EnsureValidEnterLocation`（`spatial/system/scene_spawn.cpp:91-149`） | SnapToMesh | `mapChanged`（只和内存里的旧场景比，`player_scene.cpp:60-66`）→ 落出生点并吸附（`scene_spawn.cpp:108-113`、`:65-84`）。有导航：能吸附就只吸附（修高度），否则落出生点（`:114-128`）。无导航：只修 (0,0,0)（`:129-135`）。出生点取表里有效值，否则 (180,200,0)（`:45-63`、`constants/nav.h:35-37`）。改写时打 INFO（`:140-147`）。在 79 和自身 21 之前执行 | 自身 21 的坐标 |
| 移动上行 134 / 132 / 131：`nodes/scene/handler/rpc/player/player_movement_handler.cpp:82-154`（`ApplyReportedLocation`） | ValidateMove + SnapToMesh | 通过 → 吸附后的终点；起点在网格上但撞墙 → 撞墙点（`blocked`）；起点不在网格上 → 上报点能吸附就用吸附点（`guided`），否则落出生点（`respawned`，`:124` `FallbackLocationForPlayer`）（`:96-129`）。水平偏差 > 0.5 m（`constants/nav.h:19`）给本人回 137（`:135-153`）。先裁决位置再写速度（`:174-176`），所以 137 的 `server_velocity` 是旧速度。战斗中 / 冻结时丢弃（`:170-173`） | 137 及其 `server_location`；66 的坐标与高度 |
| 帧外推：`spatial/system/movement.cpp:66-82` | ValidateMove | 不通过（撞墙或起点不在网格上）就清零速度、置 Velocity 脏位，位置取 clamped（起点不在网格上时 clamped 预置为当前位置，即原地不动）。战斗中 / 冻结 / 挂机不参与外推（`:51-52`）。**不发 137** | 66（速度变 0） |
| 启动探针：`navigation.cpp:59-88` | SnapToMesh | 决定这一行是否注册导航 | 间接：没注册的场景完全放行 |

### 3.4 不用导航的部分（Java 不移植）

- dtCrowd：`scene_crowd.cpp:23` 在 `sceneRegistry` 上拿**玩家实体**查 `DtCrowdPtr`，全仓没有地方 emplace 它，恒为空直接返回；离场是 TODO（`:62-64`）。盘点也要求不移植（`combat.md:147`）。
- FindPath：没有调用方。
- `view.cpp:5`、`:49-59`、`:139-141`：只借 `DetourCommon` 的向量函数。
- 技能、战斗：校验链里没有导航调用；实时怪物不存在（`combat.md:3`）。
- `TeleportRequest`：处理器是空桩（`player_movement_handler.cpp:216-222`）。

### 3.5 客户端对照

- 可走判定只看脚底点所在的位图格（`TianyongPaintedCity.cs:87-95`）；客户端 A* 不切角（`TianyongNavigationGrid.cs:163-167`）。
- 收到 137：距离 > 1.5 m 硬拉，静止且距离 > 0.05 m 时贴回（`GameClient.cs:1834-1863`，距离是三维的）。
- 进场兜底（评审补充）：客户端拿到服务器给的进场坐标后，如果脚底点在位图上不可走，就自己挪到最近的可走格（找不到就用本地出生点），再把位置报回服务器（`TianyongMapRuntime.cs:197-222`）。
  有导航后，服务器给的进场点都在网格上，但仍可能落在网格比位图多出的凹角里，或高坐标一侧那 1 cm 带子里（§2.5）。这时客户端会自己挪一步再上报，
  服务器按移动上行裁决：射线从网格点走到位图格内，一般不撞墙，也就不回 137。所以这是客户端已有的自愈路径，不需要服务器配合。

### 3.6 基线测试

与导航有关的只有 `cpp/tests/bag_test/scene_spawn_test.cpp` 的 6 个用例（`:51-127`），全部是吸附与落位；**没有 raycast、ValidateMove、FindPath 的测试或金样**。

---

## 4 Java 设计

### 4.1 契约闸：`xm-contract-check`

#### 4.1.1 为什么放在构建期

- ContractSync 是 JDK 单文件、零依赖程序，构建之前运行，classpath 上没有 protobuf-java，旧侧也没有描述符集。要在同步前判级，只能手写 proto 解析器，再用构建期的 protoc 交叉校验来证明它没解析错——两份实现做同一件事。
- 构建期能直接读 protoc 产出的描述符：xm-proto 已经输出带 imports 和自定义 option 的 `contract/contract.desc`（`xm-proto/pom.xml:41-44`），xm-table 输出 `config-tables.binpb`（`xm-table/pom.xml:23`、`:58`）。
- 「旧侧」就是仓库里提交的 `contract/SURFACE.txt`，不需要重建旧提交。
- 同步后必须 `clean install`，构建与测试通过才能提交（AGENTS §4、§5），锁定测试在这里失败就是提交前的闸。仓库工作区虽已被同步改写，但没提交，`git checkout` 即可撤回。
- 顺带收益：没有 mmorpg 的机器上手改同步来的 proto / 表 schema，渲染结果与锁不一致，构建会失败（覆盖 contract-tamper-check 的 proto / 表部分）。

#### 4.1.2 模块与文件

| 项 | 内容 |
|---|---|
| 模块 | `xm-contract-check`（包 `com.game.contract.check`），根 pom 里排在 `xm-table` 之后；依赖 xm-proto、xm-table、xm-table-codegen（读 cfg 选项的 `TableSchemaReader`）。**不被任何进程模块依赖**，不进镜像 |
| `SurfaceRenderer` | 描述符 → 契约面文本。proto 部分复用 `MessageIdRegistry` 取「生成类自带描述符」的做法（`MessageIdRegistry.java:166-195`，改为包内可见或抽出工具类）以拿到解析过的自定义 option；表部分用 `TableSchemaReader`；tip / operator 来自表描述符集；常量反射读 `com.game.table.TableConstants`；事件号、消息号读 classpath 上的 txt；表行与导航指纹读 `config-data/` |
| `SurfaceDiff` | 两份契约面 + 两份账本 → `List<Finding>`，实现 §1.6–§1.9。**只依赖 JDK**（CI 复核直接用 `target/classes` 跑） |
| `Acceptances` | 解析放行文件，§1.10 |
| `JavaRefs` | 扫描 `xm-*/src/main/java/**` 的 import 与全限定名，按渲染器给出的「Java 类名 → proto 全名」反查 J 级根 |
| `ContractGate` | 命令行入口（CI 复核）：`--old-surface --new-surface --old-retired --new-retired --old-source --new-source --old-accepts --accepts --java-src --annotate --out`；目标提交取 `--new-source` 的 `mmorpg.commit`；退出码 0 = 无未放行 BREAK，2 = 用法或输入错误，3 = 有未放行 BREAK。<br>**`ContractGate`、`SurfaceDiff`、`Acceptances`、`JavaRefs` 不得引用任何 protobuf 类型**（包括方法签名与字段）：CI 复核的 classpath 只有 `target/classes`，引用了就会 `NoClassDefFoundError`。由一个单测在不含 protobuf-java 的隔离类加载器里跑一遍 `ContractGate` 来守住（§9.1） |
| `ContractSurfaceTest` | 锁定测试，三种模式见下 |
| 提交的文件 | `contract/SURFACE.txt`、`contract/RETIRED.txt`（重锁产物，不许手改）、`contract/ACCEPTED_BREAKS.txt`（人写，只追加） |

`ContractSurfaceTest` 的三种模式：

| 模式 | 触发 | 行为 |
|---|---|---|
| 校验（缺省，`mvnw verify` 每次都跑） | — | 渲染 → 与 `SURFACE.txt` 逐行比较。一致就通过；不一致就失败，打印前 20 条分级结果、报告路径 `xm-contract-check/target/contract-report.md` 和重锁命令 |
| 重锁 | `-Dxm.contract.relock=true` | 判级 → 校验放行（§1.10：每条 BREAK 都有放行；多余放行行判 WARN）→ 通过才写 `SURFACE.txt` 与 `RETIRED.txt`，并在报告末尾给出 PARITY 版本基线行草稿。<br>本地重锁看不到旧的 `SOURCE.properties`（测试不依赖 git），所以 `surface-format-with-sync` 只能由 CI 复核判；本地只报 `surface-format` INFO |
| 只报告 | `-Dxm.contract.report=<绝对路径>` | 判级、写报告，差异本身不让测试失败（渲染出错仍失败）。漂移 workflow 用，路径给 `$GITHUB_WORKSPACE/drift-report.md`（surefire 的工作目录是模块目录，相对路径会落进 `xm-contract-check/`） |

#### 4.1.3 日常流程

```bash
java tools/ContractSync.java --mmorpg ../mmorpg            # 同步（不变）
./mvnw -B -pl xm-contract-check -am clean test -Dtest=ContractSurfaceTest \
       -Dsurefire.failIfNoSpecifiedTests=false -Dxm.contract.relock=true
#   必须带 clean（AGENTS §4）：渲染器按描述符去加载生成类，同步删掉或改名的类型留下的旧类不清掉，会让渲染与真实契约不一致
#   有未放行的 BREAK：失败并列出；在 contract/ACCEPTED_BREAKS.txt 写理由（或放弃这次同步：git checkout 受管路径）后重跑
./mvnw -B clean install                                    # 全量构建（AGENTS §4）
# 提交：同步产物 + SURFACE.txt + RETIRED.txt + 放行行 + PARITY 版本基线行，同一个提交
```

ContractSync 同步成功后打印上面第二条命令。报告里有 C 级 BREAK / WARN 时，提交前先跑 robot（§9.3）。

#### 4.1.4 CI

- **build job**（`ci.yml:31-61`）：`mvnw verify` 已包含锁定测试，失败由 `TestReport` 转成 annotation。
- **build job 新增一步「契约兼容性复核」**（在 verify 之后；checkout 改为 `fetch-depth: 0`）：

  ```yaml
      - name: 契约兼容性复核（逐个改过契约面的提交）
        if: ${{ !cancelled() }}
        env:
          BASE: ${{ github.event_name == 'pull_request' && github.event.pull_request.base.sha || github.event.before }}
        run: |
          if [ ! -f xm-contract-check/target/classes/com/game/contract/check/ContractGate.class ]; then
            echo "::error title=契约兼容性复核没跑::xm-contract-check 没编译出来（看上一步的构建错误）"; exit 1
          fi
          if [[ ! "$BASE" =~ ^[0-9a-f]{40}$ ]] || ! git cat-file -e "$BASE^{commit}" 2>/dev/null; then BASE=$(git rev-parse HEAD^); fi
          rc=0
          for c in $(git rev-list --reverse "$BASE..HEAD" -- contract/SURFACE.txt contract/RETIRED.txt contract/ACCEPTED_BREAKS.txt); do
            mkdir -p ".cb/$c"
            for f in SURFACE.txt RETIRED.txt; do
              git show "$c^:contract/$f" > ".cb/$c/old-$f" 2>/dev/null || : > ".cb/$c/old-$f"
              git show "$c:contract/$f"  > ".cb/$c/new-$f"
            done
            git show "$c:contract/ACCEPTED_BREAKS.txt" > ".cb/$c/accepts" 2>/dev/null || : > ".cb/$c/accepts"
            git show "$c^:contract/ACCEPTED_BREAKS.txt" > ".cb/$c/old-accepts" 2>/dev/null || : > ".cb/$c/old-accepts"
            git show "$c^:contract/SOURCE.properties"  > ".cb/$c/old-source"
            git show "$c:contract/SOURCE.properties"   > ".cb/$c/new-source"
            java -cp xm-contract-check/target/classes com.game.contract.check.ContractGate \
                 --old-surface ".cb/$c/old-SURFACE.txt" --new-surface ".cb/$c/new-SURFACE.txt" \
                 --old-retired ".cb/$c/old-RETIRED.txt" --new-retired ".cb/$c/new-RETIRED.txt" \
                 --old-source ".cb/$c/old-source" --new-source ".cb/$c/new-source" \
                 --old-accepts ".cb/$c/old-accepts" --accepts ".cb/$c/accepts" --java-src . --annotate --out "cb-$c.md" || rc=1
            { echo "### 契约变更 ${c::12}"; cat "cb-$c.md"; } >> "$GITHUB_STEP_SUMMARY"
          done
          exit $rc
  ```

  - 逐个提交比，不把 `BASE..HEAD` 当一次比：一次推送可能含两次同步，各自的放行绑定各自的目标提交。
  - 拦的是：手改锁文件绕过放行、手删账本行（`ledger-tamper`）、格式升级和同步混在一个提交里。
  - 旧侧没有契约面（引入 7.5a 的那个提交）时输出 INFO「旧侧没有契约面」，退出码 0。
  - annotation 约定同 deploy-ci-spec §4.2：每条未放行 BREAK 一行 `::error file=contract/SURFACE.txt,line=<新侧行号>,title=契约破坏性变更 <id>::<一句话>`（删除类取旧侧行号并注明），最多 9 条，其余合并为第 10 条；全部 WARN 合并成一条 `::warning`。
- **contract job**（`ci.yml:63-114`）：`--check` 不变；稀疏目录加 `data/scene_nav_bin`（§4.2）。
- **contract-drift.yml**：在 `:58-70` 的漂移分支里，把 mmorpg HEAD 同步进 runner 工作区（不提交），再跑「只报告」模式，把报告写进 Step Summary；报告里有 BREAK 时额外输出 `::warning title=上游破坏性契约变更::N 项`。
  setup-java 加 `cache: maven`，timeout 15 → 30 分钟。同步或构建本身失败（上游出现 codegen 不认识的写法）同样按漂移报告。**job 始终 `exit 0`**（与 deploy-ci-spec Q5 一致）。

#### 4.1.5 报告格式（虚构数据）

```markdown
## 契约变更：mmorpg 26ceb70ca577 → 3f1e0d2c9a11
表数据：导出自 1bca719e5557（脏）→ 77aa01c3e9d2；manifest version 19 → 21
结论：BREAK 2（未放行 1）· WARN 3 · INFO 17 —— 重锁被拒绝

### BREAK
| 变更 id | 可见 | 旧 | 新 | 为什么 | 放行 |
|---|---|---|---|---|---|
| msgid-reuse:212 | C | 212 Svc.MethodA | 212 Svc.MethodB | 已构建的客户端发 212 会落到新方法 | **未放行** |
| field-retype:.BattleStateS2C#4 | C | uint64 action_deadline_ms | uint32 … | 64→32 收窄 | 已放行：… |
### WARN …  ### INFO …（C 级新方法列入「Java 待做」）
### Java 引用粗查（仅提示，以 clean install 为准）
- MethodA 出现在 xm-guild/…/GuildDispatcher.java:112、xm-robot/…/GuildScenario.java:203
### PARITY 版本基线行草稿
| 0.1.0-SNAPSHOT | `3f1e0d2c9` | YYYY-MM-DD | 契约同步到 mmorpg HEAD：… |
```

引用粗查：对 BREAK / WARN 涉及的方法名和类型简单名，在 `xm-*/src/main/java` 与 `xm-robot/src` 里找出现位置，每项最多 5 处，用来提示该重点跑哪些模块和 robot 场景。

#### 4.1.6 ContractSync 的配套改动（小）

- `SOURCE.properties`：`table.pending.fields` 由 `Sheet.name` 改为带号和签名的 `Sheet#8:uint32 activity_join_min_hours`；新增 `table.owner.reserved`（`Sheet#号:owner:标签 类型 名`）。
  渲染器据此把描述符里的 `reserved` 区分成 `pending` / `owner=` / 普通 reserved。改动点：`syncTableSchemas`（`ContractSync.java:237-267`）、`alignWithProduct`（`:342-391`）、`stripNonServerFields`（`:421-443`）把签名带出来。
- 同步时新增 fail-closed 校验：`message_id.txt` 的行格式、号和键重复，口径同 `MessageIdRegistry.java:262-290`；表主键必须是 `uint32 id = 1`（行指纹依赖它，不满足就同步失败并说明）。
- 同步完成后打印 §4.1.3 的重锁命令。
- xm-table 把描述符集同时输出到 classpath（例如 `${project.build.outputDirectory}/table-descriptors/config-tables.binpb`），让 `xm-contract-check` 不依赖相邻模块的 `target/` 路径。

### 4.2 导航数据同步（ContractSync）

- 新增受管路径 `config-data/navmesh`，加入 `MANAGED`（`ContractSync.java:67-75`），`--check` 自动覆盖。
- 引用关系：从已在稀疏目录里的 `generated/tables/basescene.json` 取每行的 `nav_bin_file`（ContractSync 已读 `generated/tables`，`:128-139`）。
  要求路径在 `data/scene_nav_bin/` 下；目标文件名取文件名部分；两个不同路径同名就同步失败。当前去重后 6 个文件，共 793,744 字节（约 790 KB）。`tianyong_scene.bin` 没被引用，不同步。
  ContractSync 是零依赖程序，读 JSON 只能用正则（与现有的 `manifestValue` 同法，`ContractSync.java:407-411`）：按 `"nav_bin_file"\s*:\s*"([^"]*)"` 取值，
  另数 `"id"` 行，取到的个数必须等于 manifest 里 BaseScene 的 `rows`（当前 21），不等就同步失败，防止导表器换了 JSON 排版后正则少取。
  Java 运行时读的是 `basescene.pb`，不是 JSON；两者出自同一次导出，一致性由构建期测试 G1 兜底：它按 `.pb` 解析出的 21 行逐行注册导航（§9.2.3）。
- 结构校验（fail-closed，任何一条失败都不写入）：文件存在、≥ 96 B；magic、文件版本 1；`numTiles ≥ 1`；逐个 tile 记录 `tileRef ≠ 0`、`dataSize > 0`，累加后恰好到文件尾；tile 头 `u16 version == 7`、`layer == 0`、`offMeshConCount == 0`。深度校验由 `xm-navmesh` 的读取器在构建期测试里做（§4.3）。
- 复制用 `Files.copy`（不走 `copyText`）；`normalized()` 把 `.bin` 当二进制（现在只认 `.pb`，`:661-668`）；`.gitattributes` 加 `*.bin binary`（现在只有 `*.pb`、`*.jar`）。
- ContractSync 生成 `config-data/navmesh/navmesh.properties`：

  ```
  # 由 tools/ContractSync.java 生成，不要手改。BaseScene.nav_bin_file 引用的导航网格（mmorpg data/scene_nav_bin）。
  format=1
  donghai_scene.bin.sha256=…
  donghai_scene.bin.tiles=40
  …
  ```

  运行时据此校验 sha256（与表数据的 manifest 校验同理）。`SOURCE.properties` 加 `navmesh.files=6`。
- CI 稀疏目录（`ci.yml:92-97`、`contract-drift.yml:37-42`）加 `data/scene_nav_bin`。deploy-ci-spec `:988` 第 12 条已预见这类改动：漏改时 contract job 会因「目录不存在」报红。
- 镜像：`.dockerignore` 加 `!config-data/navmesh`（现在只放行 `config-data/tables`，`.dockerignore:6`）；Dockerfile 在 `COPY config-data/tables/`（`deploy/docker/Dockerfile:39`）旁加 `COPY config-data/navmesh/ ./config-data/navmesh/`。
  这与 deploy-ci-spec §3.7「表数据烤进镜像」同理；`xm.nav-dir` 缺省值相对 WORKDIR `/app`，不用改配置；临时换数据时用 `XM_NAV_DIR` 覆盖（Spring 宽松绑定），口径同 `XM_TABLE_DIR`。
  Dockerfile 文件头和 `.dockerignore` 头注释里「只放行 … 与 config-data/tables」的说法要一并改。

### 4.3 `xm-navmesh`（新纯库模块，`com.game.navmesh`，只依赖 JDK）

| 类 | 职责 |
|---|---|
| `NavMeshReader` | 按 §2.3 布局用小端 `ByteBuffer` 逐字段读取，**整文件 fail-closed**，抛 `NavMeshFormatException`：magic / 文件版本 / tile 头版本不对；分段之和 ≠ `dataSize` 或文件尾 ≠ 文件长度；tile x/y 越界、layer ≠ 0、同一位置两个 tile；多边形顶点 / `neis` / BV 叶子下标越界；`vertCount` 不在 3..6；`offMeshConCount > 0`；`flags == 0`（过不了默认过滤器）。detail 顶点按通用方式读取（当前为 0），给将来的 OBJ 烘焙留口 |
| `NavMesh` | 不可变。按头部 x/y 放置 tile（`tileRef` 只校验 salt ≠ 0、tile 下标唯一且 < maxTiles）；构造时建立邻接：内部边用 `neis`，跨 tile 边按上游 `connectExtLinks` / `findConnectingPolys` 匹配并记录 portal 的量化区间（0..255）。构造完成后可在任意线程读 |
| `NavMeshQuery` | 无状态，只移植**基线用到的子集**：`queryPolygons`（`calcTileLoc` 选 tile；BV 量化重叠，含夹到 tile 包围盒、`& 0xfffe` / `\| 1` 规则）、`closestPointOnPoly`（多边形内取 detail 三角形高度，外取最近边）、`findNearestPoly`、`raycast`（沿多边形链走 portal，含部分 portal 判定；没撞墙时 t = 极大值；不设途经多边形上限，N-D6）。不移植 findPath、findStraightPath、crowd、off-mesh 连接 |
| `NavScratch` | 调用方持有的可复用缓冲（候选 polyRef 数组、raycast 途经表、多边形顶点暂存）：库内部的查询不分配。scene 只有一个逻辑线程，由 `SceneWorld` 持有一份。门面（§4.4.1）返回 `Optional` / `MoveCheck` 这类小对象照常分配，每条移动本来就新建 `Vec3`，不必为它们做对象池 |

- 坐标：库在 Detour 空间（Unity Y-up）工作，不知道服务器坐标；换轴在 xm-scene 的门面里做。
- `findNearestPoly` 的距离度量有两种写法：老版本用纯三维距离，新版本对「点在多边形正上方」做爬升修正。UE5 用的是哪种读不到源码。
  **本批数据是平地（y ≡ 0）**：两种写法选出的多边形可能不同，但落点相同。正上方的多边形，落点就是投影点；其它候选的落点距离只会更大，相等时落点重合。
  评审补充：UE4 公开源码里的 `findNearestPoly` 是老写法，即纯三维距离、多一个可选的 `referencePt` 参数、候选放在固定 128 个的数组里（`queryPolygons(..., 128)`）。
  这一点按记忆，本机无法核实。UE5 很可能沿用。因此**实现改取老写法**：纯三维距离，候选按遍历顺序最多取 128 个。
  在平地上它与新写法落点逐位相同，代码更少，也更可能与 UE 一致。本批数据在吸附框（约 5 m × 5 m）里的多边形远少于 128 个，上限不会触发。最终以金样为准（§8 NQ8）。
- 邻接与 portal 要逐位照搬上游的构造顺序，raycast 在同一条边上有多个 link 时，取第一个包含交点的：
  - tile 按文件顺序加入；
  - 每个 tile 先建内部 link，再按方向 0–7 与已加入的相邻 tile 互连（`connectExtLinks`，每条边最多 4 个邻居）；
  - 新 link 插在链表头（`link->next = poly->firstLink`）；
  - portal 区间压成 0–255 的字节（`(u8)(clamp(t, 0, 1) · 255)`）；
  - raycast 判断交点是否在 portal 内时，用 `bmin / 255`、`bmax / 255` 还原区间。
  量化误差最大是边长 / 255，12 m 的边（`maxEdgeLen`，`navmesh_baker.cpp:405`）约 4.7 cm。跨 tile 贴墙的射线可能因此早停或多走几厘米，必须用金样钉住。
- 来源纪律：按上游 recastnavigation（zlib 许可）的算法实现，配合 §2.3 的数据事实；**不抄 UE 修改过的源码**；由 Detour 派生的文件保留 zlib 声明（§8 NQ3）。
- 规模：主代码约 1.3k 行，测试约 0.8k 行（L）。

### 4.4 xm-scene 接入（新包 `com.game.scene.nav`，领域对象 + 服务，不用 ECS 命名）

#### 4.4.1 门面与注册表

- `SceneNavigation`：一份网格的服务器坐标门面，对应基线 `NavQuerySystem`。常量与基线逐个对齐（吸附 {2,4,2}、回缩 0.05）。
  - `Optional<Vec3> snap(Vec3 p)`；
  - `MoveCheck validateMove(Vec3 from, Vec3 to)`，`MoveCheck` 是密封类型：`Passed(end)`（吸附后的终点）/ `Blocked(hit)`（回缩后再吸附的撞墙点，吸附不上取吸附后的起点；查询失败也归此类）/ `StartOffMesh`。逐行照搬 `nav_query.cpp:86-149`，包括「raycast 通过但终点吸附失败按 t=1 撞墙」。
- `SceneNavigations`：启动时随 `ConfigTables` 构建（`SceneNodeConfiguration`，与 `ConfigTables.load` 同处）。
  - 读 `xm.nav-dir`（缺省 `config-data/navmesh`，与 `xm.table-dir` 同级、同样相对仓库根）下的 `navmesh.properties`，按 BaseScene 每行 `nav_bin_file` 的**文件名部分**解析，同名只加载一次，校验 sha256。
  - 每一行用 `SceneTables.spawnPoint(id)`（`ConfigSceneTables.java:62-70`）探针一次。
  - **任何失败都让启动失败**（文件缺失、sha 不符、读取器拒绝、探针吸不上），日志带配置号、文件、出生点。`nav_bin_file` 为空的行没有导航（数据层面的显式放行，同基线 `navigation.cpp:101-104`）。
  - `xm.scene.nav-enabled=false`：整体关闭，所有场景无导航，行为回到现在的 MoveGuard 口径。
- `Scene` 在创建时（`SceneWorld.java:245`，唯一构造点）按配置号取得 `SceneNavigation`（可空），移动时不再查表。副本和镜像（5.3）按配置号自然获得导航。
- 注入方式（评审补充）：导航挂在 `SceneTables` 上，加一个缺省方法 `default Optional<SceneNavigation> navigation(int sceneConfigId) { return Optional.empty(); }`，
  由 `ConfigSceneTables` 覆盖。不要给 `SceneWorld` 的构造器加参数：测试里有 29 处 `new SceneWorld(`，各种 `SceneTables` 替身也不用改。
  替身不覆盖这个方法就等于「无导航」，现有用例按原口径继续成立。理由：导航就是「按场景配置号查的配置派生数据」，与 `spawnPoint` 同类。
- 线程：网格不可变、跨线程共享；查询只在逻辑线程调用，用 `SceneWorld` 持有的 `NavScratch`。不需要 thread_local（基线那个缺陷不存在，N-D4）。

#### 4.4.2 进场落位（替换 `resolveEnterPosition`，`SceneWorld.java:764-774`）

统一成一个 `EnterPlacement`，所有进场路径都走它：登录、重连、顶号、交出进场（5.2）、跨 zone 进场（5.4）、排空改派（5.5）走 `:724`；同节点换图、组队跟随、同节点改派走 `switchScene`（`:846`）。

| 情形 | 无导航 | 有导航 |
|---|---|---|
| 存档可用（配置号与目标相同、在世界范围内、不是原点） | 沿用存档坐标（现状） | 能吸附 → 吸附点（只修高度，不算改写）；吸不上 → 落吸附后的出生点 |
| 存档不可用、换图（D10） | 出生点（现状） | 吸附后的出生点（启动探针保证吸得上；万一吸不上打 ERROR、用原出生点，同基线 `scene_spawn.cpp:77-83`） |
| 同节点换图到**同一配置**（换线） | 保留当前坐标（现状） | 当前坐标吸附一次；吸不上（只在位置被别处改坏时）落吸附后的出生点，同基线 `scene_spawn.cpp:122-127` |

- 发生改写时打 INFO（对应基线 `scene_spawn.cpp:140-147`）。
- Java 的 D10 口径不变：存档配置号 ≠ 目标就落出生点（5.2 scene-handoff-spec D10、`PARITY.md:91` ⑦）。
- `switchScene` 现在用 `tables.spawnPoint` 直接落点（`:846`），改为走同一个 `EnterPlacement`；之后的 `stopMotion` 与 `moveGuard().reset`（`:848-849`）不变。

#### 4.4.3 移动上行（`applyMove`，`SceneWorld.java:1242-1275`）

1. 输入检查（非有限值、超出世界范围）→ 丢弃（现状，N-D8）。
2. `MoveGuard.propose(reported, now)` 给出候选点（额度内就是上报点，超额沿上报方向截断）。
3. 场景有导航时，从**服务器当前位置**（含外推，同基线）到候选点做 `validateMove`：
   - `Passed(end)` / `Blocked(hit)`：接受该点；
   - `StartOffMesh`：候选点能吸附就用吸附点（`guided`），否则用吸附后的出生点（`respawned`）。
4. 记账：`Passed` / `Blocked` → `MoveGuard.commit(proposal, accepted)`：锚点移到接受点。
   - 额度扣多少：接受点与候选点水平坐标相同（无导航、或 `Passed`），扣 `proposal.spent()`；否则扣 `min(proposal.spent(), 旧锚点到接受点的水平距离)`。
   - `guided` / `respawned` 是服务器放置 → `MoveGuard.reset(accepted, now)`。
5. 写位置、朝向、速度、脏位；与上报点水平偏差 > 0.5 m 回 137，`server_velocity` 仍填处理后的速度（PARITY 移动行②）。

导航场景里位置的 z 一律取网格高度（与基线一致；现在 Java 取上报值）。

#### 4.4.4 `MoveGuard` 拆分

- `admit` 拆成 `propose` 与 `commit`：
  - `propose` 先补额度、算候选、确认有限，返回 `Proposal(candidate, spent)`，`spent` 就是 `admit` 里那个变量：额度内为 `wanted`，超额为当时的额度；不改锚点与额度。
  - `commit` 提交锚点与额度，按 §4.4.3 第 4 步扣。
- 无导航场景 `commit(p, p.candidate())` 扣的正是 `p.spent()`，与现在的 `admit(x)` 逐位等价。`MoveGuardTest` 现有用例原样保留作回归，包括截断后 `allowance()` 恰好为 0 的三处断言（`MoveGuardTest.java:52`、`:135`、`:142`）。
  评审更正：原稿让 `commit` 重算「锚点到候选点的水平距离」再取 `min`。超额截断时这个距离可能比额度小一个末位，额度就剩下 1e-15 量级而不是 0，
  `isZero()` 会失败，`min` 只吸收得了「大一个末位」的那一边。
- 类注释（`MoveGuard.java:5-25`，尤其 `:12`「Java 版没有导航网格」）改为「无导航场景高度不校验；有导航场景先按额度截断、再射线夹持，高度取网格」。

#### 4.4.5 帧外推（`integrate`，`SceneWorld.java:1320-1336`）

有导航时对 `当前 → 当前 + v × 0.05` 做 `validateMove`：

- `Passed(end)`：位置取 end（高度跟随网格）；
- `Blocked(hit)`：位置取 hit，速度清零，置 `DIRTY_VELOCITY`；
- `StartOffMesh`：原地不动，速度清零，置 `DIRTY_VELOCITY`。

不发 137（与基线 `movement.cpp:66-82` 相同）。挂机停推等现有逻辑不变；javadoc（`:1313-1319`）「没有导航网格，不做撞墙夹持」改掉。

#### 4.4.6 指标（不加任何玩家或场景维度的 label）

| 指标 | 类型 | label | 说明 |
|---|---|---|---|
| `xm_scene_moves_total` | Counter | `result`=accepted / clamped / corrected / invalid（不变） | 口径改为：accepted = 水平位置未变（高度可能被吸附）；clamped = 水平位置被改（额度或导航）但偏差 ≤ 0.5 m；corrected = 回了 137 |
| `xm_scene_nav_verdicts_total` | Counter | `verdict`=passed / blocked / guided / respawned | 导航场景的移动上行裁决，每条恰好一次 |
| `xm_scene_nav_extrapolation_stops_total` | Counter | — | 外推被夹住、速度清零的次数 |
| `xm_scene_nav_configs` | Gauge | — | 已注册导航的场景配置数（启动时设定，当前 21） |

纠偏日志保持 debug（现状）；启动时每份网格打一条 INFO（文件、tile 数、orig），每个配置一条「已注册」。

#### 4.4.7 性能

- 每次移动上行：两次吸附 + 一次射线；每帧外推：每个移动中的玩家一次 `validateMove`。每次查询只碰 1–4 个 tile、几十个 BV 节点、几个多边形，量级是微秒。
- 架构文档记录的出生点人群基准：2000 人整帧均值 22.4 ms、p99 48.9 ms（`architecture.md` §5「出生点人群基准」，该文件正被并行批次修改，按小节引用），已贴着 50 ms 预算。导航接入后要复测（§9.2.6）：
  均值增量 ≤ 5 ms 且 p99 仍在预算内才算通过。不达标时再做「起止点在同一凸多边形内就跳过射线」的快路径（在本批平地数据上与完整算法结果逐位相同，证明见测试），否则不做（YAGNI）。
- 复测要保证人群行为可比（评审补充）：基准让人在出生点 15 m 内随机走（`ViewCrowdBenchmarkTest` 类注释），而天墉城 +x 墙离出生点只有 16.01 m，四周凹角更近。
  接上真实网格后，一部分人会撞墙停下、收 137，外推人数和 66 扇出都会变少，均值反而可能「变好」。复测时：
  - 报告撞墙停下的次数与平均移动人数；
  - 必要时把半径降到 12 m，或撞墙后立即换方向，让移动人数与无导航那次相当后再比较。

### 4.5 robot

- 现有 `movement --expect-jump correct` 不改，应仍通过：行走段（服务器 x 180 → 184.375，含超速段外推的余量 5 m 也到不了 190）离 +x 墙 196.01 还远；跳跃先被 MoveGuard 截到约 +24 m，再被导航夹在墙前，137 落在 195.96；robot 只断言自洽（`MoveAssertions.java:214-251`）。
  `MovementScenario` 里「66 的 z 不应变化」与「重登位置 = 停止点（1e-4）」两类断言不受吸附影响：顶点高度严格为 0（§2.3），吸附只把 z 写成 0.0，x / y 原样保留。
- 现有 `reconnect`、`cross-node` 从各图出生点沿 +x 走 2 m：四份几何 +x 方向到墙的距离分别是 16 / 60 / 30 / 6 m 左右（§9.2.3 的轴向表），都在网格内。
- 新增 `navwall` 场景，细节见 §9.3。

### 4.6 落地清单、文档、PARITY

**分批**

| 批 | 内容 | 规模 | 前置 |
|---|---|---|---|
| 7.5a | `xm-contract-check`、`SURFACE.txt` / `RETIRED.txt` 引导重锁、`ACCEPTED_BREAKS.txt`（只有文件头）、ContractSync 的 §4.1.6 改动、ci.yml 复核步骤、contract-drift.yml 分级报告、xm-chat 补真注册表单测 | M–L（约 1.6k 行 + 0.9k 行测试） | 7.1a 入库（改同一批 workflow） |
| 7.5b | ContractSync 同步导航（§4.2）、`xm-navmesh`、`.gitattributes`、CI 稀疏目录；重锁出 `navmesh-added` INFO | L | 7.5a |
| 7.5c | xm-scene 接入（§4.4）、`navwall`、`.dockerignore` / Dockerfile、文档与 PARITY | M | 7.5b；`SceneWorld` / `MoveGuard` 的在途改动全部入库：5.2（交出进场）、5.5（排空改派）、6.3（备战停步、战斗在途丢弃移动，scene-battle-spec D5） |

**文档**

- `AGENTS.md` §1：受管清单加 `config-data/navmesh/**`；写明「`contract/SURFACE.txt`、`contract/RETIRED.txt` 是重锁产物，不许手改；破坏性契约变更在 `contract/ACCEPTED_BREAKS.txt` 登记理由（人写、只追加）」。
  §4：同步后先跑重锁命令再 `clean install`；报告里有 C 级变更时跑引用粗查列出的 robot 场景。
- `architecture.md`：§1 边界清单加「导航网格数据（同步产物）」与「同步后过契约闸」；§2 模块表加 `xm-navmesh`、`xm-contract-check`；§5 线程模型加「网格不可变、跨线程共享，查询只在逻辑线程」；§11 加 §4.4.6 的指标并改 `xm_scene_moves_total` 的口径说明。
- `tech-stack.md`：两行（§5）。
- `docs/reference/mmorpg-client-contract-scene.md:69`、`-movement.md:366`、`-aoi.md:211`、`:377-379`：「Java 没有导航网格」改为现状。
- `docs/porting/roadmap.md:95` 更新状态；`docs/design/config-tables.md` 补一句导航数据同步。
- `xm-robot/README.md`：`navwall` 用法。
- 配置与测试装配（评审补充）：`SceneNodeProperties` 加 `navDir`（`xm.nav-dir`，缺省 `config-data/navmesh`），`SceneSettings` 加 `navEnabled`。
  凡是用真实 `config-data` 装配 scene 的测试与工具，都要同时给 `xm.nav-dir=../config-data/navmesh`，与现有的 `xm.table-dir=../config-data/tables` 成对出现，否则启动 fail-closed。
  `tools/local/start-slice.sh` 从仓库根启动，缺省相对路径即可，不用改。

**PARITY**

| 功能 | mmorpg 位置 | Java 模块 | 状态 | 备注 |
|---|---|---|---|---|
| 契约变更报告与兼容性闸（新增） | 无对应（mmorpg 只有事件号墓碑与导表器 tip 轴自检） | xm-contract-check、tools/ContractSync.java、.github/workflows | 不适用（Java 版专有工具） | 有意差异 C-D1–C-D8；**mmorpg 待做**：message_id 墓碑与确定性发号（§8 CQ4） |
| 导航网格查询：进场吸附 / 移动射线夹持 / 外推夹持（新增） | `cpp/libs/services/scene/spatial/system/{navigation,nav_query,recast,scene_spawn,movement}.cpp`、`cpp/nodes/scene/handler/rpc/player/player_movement_handler.cpp` | xm-navmesh、xm-scene、tools/ContractSync.java | 已对齐（行为有意不同，协议不变） | N-D1–N-D12；**跨语言金样待 mmorpg**（§9.2.4） |
| 导航网格烘焙（新增） | `tools/navmesh_baker` | — | 不适用（mmorpg 单一生产者，Java 同步产物） | |
| 移动位移校验（更新，`PARITY.md:45`） | | | | 「高度取上报值」改为「无导航场景如此；有导航场景先按额度截断、再射线夹持，高度取网格」 |

提交说明写明：「兼容性闸 / 导航的 CI 结论以 run `<id>` 为准」，以及 mmorpg 侧的待做项需用户同意后再动 mmorpg。

---

## 5 选型（含 star 核对）

star 为 2026-10-05 GitHub API 实测（编辑复核）。

| 需求 | 选择 | 候选与 star | 理由 |
|---|---|---|---|
| 契约破坏性检查 | **自写**（JDK + 已有的 protobuf-java） | bufbuild/buf **11,474** | 不到 2 万；只懂 proto，不懂 message_id、tip、表数据、导航；是 Go 二进制，CI 要另装 |
| 描述符读取 | protobuf-java（已是依赖，与 protoc 同版本，`tech-stack.md` 版本约束） | protocolbuffers/protobuf **72,096** | 已在用 |
| 测试 | JUnit 5 / AssertJ（Spring Boot 管理） | — | 已在用 |
| 导航查询 | **自研 `xm-navmesh`**（只依赖 JDK） | recastnavigation/recastnavigation **7,943**（zlib，C++）；recast4j/recast4j **292**（Java 移植）；ikpil/DotRecast **948**（C#）；libgdx/gdx-ai **1,304**（只有通用图寻路）；jMonkeyEngine **4,321** | 全部不到 2 万。recast4j 另有硬伤：按上游 float 布局、要 `DNAV` 魔数，读不了 UE5 double MSET；JNI 调 C++ 要私有的 ue5navmesh、每平台编原生库、原生崩溃带走 JVM，JDK 21 的 FFM 还是预览特性 |
| 导航数据 | 同步 mmorpg 烘焙产物 | 位图格子导航（自研）| 见 §0.4 裁决 4、§2.5 |

`tech-stack.md` 新增两行：

| 用途 | 选用 | star | 落选（star） | 说明 |
|---|---|---|---|---|
| 契约破坏性检查 | 自写（xm-contract-check，protobuf-java 读描述符） | — | buf (11.5K) | 要同时覆盖消息号、tip、表、导航，buf 不到 2 万也不覆盖 |
| 导航网格查询 | 自研 xm-navmesh（只依赖 JDK，算法按上游 recastnavigation 的 zlib 实现） | — | recastnavigation (7.9K)、recast4j (0.3K)、gdx-ai (1.3K) | 读 mmorpg 烘焙的 UE5 double MSET；只移植基线用到的查询子集 |

---

## 6 隐患与边界

### 6.1 契约闸

1. **工作区先被改写再判级**：同步直接改了受管路径，闸在构建时才拦。缓解：重锁命令在 ContractSync 输出里给出；放弃同步用 `git checkout -- <受管路径>` 或 `--commit <旧 sha>` 重新同步。
2. **锁文件被手改**：本地构建会通过（渲染 = 锁），但 CI 逐提交复核会把没有放行的 BREAK 报出来；账本被删行判 `ledger-tamper`。
3. **一次推送含多次同步**：逐个提交复核，各自对各自的放行。
4. **放行 id 打错**：真正的 BREAK 仍未放行，重锁失败；打错的那一行另报 `accept-unused` WARN，提示去对 id（评审更正：多余放行不再判失败，§1.10）。
5. **契约面格式升级掩盖一次比较**：`surface-format-with-sync` 禁止格式升级和同步落在同一提交里。本地重锁看不到旧的 `SOURCE.properties`，这条只由 CI 复核判：本地会放过，推送后变红。
   做法：先单独提交格式升级（目标提交不变），再做同步。
6. **上游以后给消息号加墓碑**（§8 CQ4）：`parseMessageIds` 会把 `reserved:Xxx` 当普通键（`MessageIdRegistry.java:275-286`），`MessageIdRegistryTest` 的 etcd 断言会变红。这个失败信号是对的：同批改 MessageIdRegistry 跳过墓碑行、契约面写 `msgid N reserved <键>`、账本当退役处理。
7. **J 级靠源码文本扫描**：反射或拼字符串引用的生成类扫不到（漏报一级）；CI 复核用 HEAD 源码近似历史提交。Java 代码按惯例都 import 生成类，影响小。
8. **`.pb` 里 map 字段的序列化顺序不稳定**：行「修改」可能误报，只是 INFO。
9. **annotation 每步最多显示约 10 条**：先汇总再输出。
10. **漂移报告需要 Maven 构建**：比原来慢几分钟；上游出现 codegen 不认识的写法时构建失败，按漂移报告处理，不判红。
11. **CRLF**：两个锁文件写 LF，读取时先归一；`.gitattributes` 的 `* text=auto eol=lf` 已覆盖。
12. **CI 复核与构建失败叠加**：`-fae verify` 下 xm-contract-check 本身编译失败时，复核步骤先检查 `ContractGate.class` 是否存在，不存在就输出一条明确的 `::error` 退出，
    不让 `NoClassDefFoundError` 挤占 annotation 名额（§4.1.4）。
13. **表达式列参数错位**：上游调换 `cfg_expr_param` 的顺序后，Java 照常编译、结果静默错误。由 `table-expr-params` BREAK 拦住（§1.8）。

### 6.2 导航

1. **UE5 Detour 的私有改动**：布局已在 180 个 tile 上自洽验证；查询边界（BV 夹取、部分 portal、`findNearestPoly` 的距离度量、raycast 缓冲溢出）只能参照上游，**金样到来之前「已对齐」是有条件的**（PARITY 注明）。
2. **mmorpg 升级 ue5navmesh 或改烘焙参数**：tile 头版本、段布局一变，ContractSync 同步时就失败（fail-closed），不会把读不懂的文件带进来；参数变化（如 agent 半径）由 `navmesh-changed` WARN 和 `navwall` / 金样暴露。
3. **离墙 0.05 m 以内回缩夹到 0**：裁决点就是起点（`nav_query.cpp:131-132`），连续贴墙推进时 137 落点会在 [墙 − 0.05, 墙) 之间变化。robot 期望必须按这个设计（§9.3）。
4. **旧存档一次性改落点**：7.5c 之前 MoveGuard 接受过墙里的坐标。首次进场时，离网格约 2 m 以内的会被吸附到最近的网格点（只修位置，不算改写）；更远的才落回出生点（§8 NQ9）。
   固定 `--run-tag` 的 robot 账号会受影响。
5. **启动 fail-closed 的代价**：mmorpg 发出一行出生点不在网格上的数据时，Java 的同步提交会被构建期测试拦住，要先修 mmorpg 数据（同一个数据在 C++ 里是该场景静默失去导航）。
6. **帧预算**：§4.4.7。
7. **高度很大的上报**：2D 射线判通过、终点吸附失败、回缩点 z 仍大，裁决回到起点并回 137（基线同）。世界范围检查（±1e7）在它之前丢弃更极端的值。
8. **位图与网格的差**：凹角最多差 0.225 m，此外整体平移 +1 cm（§2.5）。凹角只影响异常输入的夹持位置。
   合法客户端可能站进低坐标一侧那条 1 cm 带子，服务器裁决点与它相差 < 0.06 m，不回 137。客户端在高坐标一侧和凹角里拿到不可走的进场点时会自己挪开（§3.5）。
9. **文件名部分冲突**：两个不同路径同名时同步失败（§4.2）。
10. **导航关闭开关**：`xm.scene.nav-enabled=false` 让所有场景回到无导航口径（无撞墙夹持）。只用于止血，打 WARN。

---

## 7 建议的有意差异（相对基线）

### 7.1 契约闸

| # | 项 | 基线 | Java | 理由 | 客户端可见 |
|---|---|---|---|---|---|
| C-D1 | 跨版本契约闸 | 没有（H4） | 构建期锁定测试 + CI 逐提交复核 + 漂移分级报告 | 消息号复用、字段号复用的机制确实存在，只有跨版本比较才看得出来 | 否 |
| C-D2 | 号位墓碑 | 只有事件号有 | 七类号位都记账 | H1、H3 | 否 |
| C-D3 | 开发期删字段 / 复用字段号 | 允许删、开发期允许复用（`AGENTS.md:44`） | 照常同步；删除记账、复用判 BREAK，需要显式放行 | 已构建的客户端包不会随上游重生成；放行只要一行字 | 否 |
| C-D4 | 类型变更 | 只有清单式约束（`AGENTS.md:58-67`） | 按线格式分级（§1.9） | 覆盖清单以外的字段 | 否 |
| C-D5 | 上游出现 BREAK | — | 未放行就不能重锁，构建不过、不能提交 | 宁可晚一步跟上，不默默跟错 | **间接**：放行之前 Java 对客户端仍是旧契约 |
| C-D6 | 变更记录 | — | 不另建 CHANGES.md；契约历史 = `git log contract/SURFACE.txt` + PARITY 版本基线行 | PARITY 是唯一对账处（`PARITY.md:4`）；`**/*.md` 还会被 ci.yml 的 `paths-ignore` 跳过（`ci.yml:16`） | 否 |
| C-D7 | 防手改范围 | 没有 | 覆盖 proto、消息号、表 schema、表行、导航；TableConstants 仍只靠 `--check` | 控制本批范围 | 否 |
| C-D8 | 闸的位置与盘点设想不同 | — | 构建期测试，不在 ContractSync 里（盘点 `tools.md:224` 设想 ContractSync `--report`） | §4.1.1 | 否 |

### 7.2 导航

| # | 项 | 基线 | Java | 理由 | 客户端可见 |
|---|---|---|---|---|---|
| N-D1 | 有导航的场景仍先过 MoveGuard（已登记，`PARITY.md:45`） | 只做射线夹持（`player_movement_handler.cpp:96-129`） | 先按令牌桶截断，再从服务器位置做射线 | 安全 > 严格对齐（2026-09-30 定） | **是**，只在异常输入：超过 12 m/s 或一次跳超过 24 m 时，137 落在额度点与墙点中较近的一个 |
| N-D2 | 不移植 FindPath、dtCrowd | 有 API 没调用方；crowd 是坏桩（`scene_crowd.cpp:23`、`:62-64`） | 不实现 | YAGNI；实时怪物要先在 mmorpg 设计（`combat.md:147`） | 否 |
| N-D3 | 导航数据 fail-closed | 逐行 fail-open（`navigation.cpp:44-46`、`:62-65`、`:73`）；加载器把截断文件当成功（`recast.cpp:77-78`、`:90`） | 同步、构建、启动三道校验，任何失败启动不了；有整体开关 | 与配表加载一致；静默放行等于关掉防穿墙 | 部署正确时否 |
| N-D4 | 网格不可变、跨线程共享 | `thread_local` 管理器，只在启动线程加载（`scene_nav.h:24-27`） | 一份不可变 `NavMesh` | Java 惯用；消除多线程下看不到导航的隐患 | 否 |
| N-D5 | 文件解析路径 | `GetDataRootDir() + nav_bin_file`（`navigation.cpp:105`） | 文件名部分，在 `xm.nav-dir` 下解析 | Java 自己的目录组织（AGENTS §1） | 否 |
| N-D6 | raycast 不设途经多边形上限 | 256，注释说溢出按「从起点被挡」（`nav_query.cpp:21-23`、`:104-109`） | 不设上限 | 受 24 m 额度和每帧 ≤0.5 m 外推约束，不可能接近 256；上游的溢出本来也不算失败 | 否 |
| N-D7 | 换图落点沿用 D10（已登记） | 跨节点 / 跨登录时坐标在目标网格上就保留（`scene_spawn.cpp:114-128`） | 存档配置号 ≠ 目标就落出生点 | 5.2 D10、`PARITY.md:91` ⑦ | 是（已登记） |
| N-D8 | 非有限值和越界输入在导航之前丢弃（已登记） | 照收，NaN 会进 Detour | 丢弃 | PARITY 移动行 ①⑥ | 是（已登记） |
| N-D9 | 不移植烘焙器 | `tools/navmesh_baker` | 同步烘焙产物 | 单一生产者 | 否 |
| N-D10 | guided 分支吸附 MoveGuard 候选点 | 吸附原始上报点（`player_movement_handler.cpp:116-121`） | 吸附候选点 | 起点不在网格上时也不让人绕过位移校验 | 是（罕见：只在服务器位置本身不在网格上时） |
| N-D11 | 读取方式 | Detour `addTile` 整块装载、依赖 ABI | 逐字段解析、整文件结构校验 | 不依赖 C++ ABI；坏文件不进内存 | 否 |
| N-D12 | 指标 | 只有日志 | §4.4.6 | 可观测性 | 否 |

**不是差异**（与基线一致）：导航场景高度取网格（Java 由「取上报值」**向基线收敛**）；外推夹持不发 137；出生点探针；吸附范围 ±2/4/2；回缩 0.05 m；纠偏阈值 0.5 m；进场时有导航只吸附、无导航只修 (0,0,0)；同配置换线保留坐标。

---

## 8 开放问题（各带推荐答案）

### 8.1 契约闸

| # | 问题 | 推荐答案 | 理由 |
|---|---|---|---|
| CQ1 | 闸放在 ContractSync 同步前，还是构建期 | **构建期锁定测试 + CI 逐提交复核** | §4.1.1。同步前闸需要手写解析器再交叉校验，收益只是「工作区不被改写」，而工作区本来就在 git 里 |
| CQ2 | 还没上线，现在就判 BREAK 吗 | **判，配合放行文件** | 放行只要一行字；客户端把号编进代码，任何已构建的客户端包都受影响。账本把「删」和「复用」分开后，加字段、加方法、删字段（写不写 reserved）都不触发 BREAK |
| CQ3 | 放行用文件还是命令行参数 | **文件**，键绑定目标提交；每条 BREAK 必须有放行，多余放行行只报 WARN | 留痕、CI 可复核；同一目标提交上二次重锁不误报（§1.10） |
| CQ4 | 是否请 mmorpg 给 `message_id.txt` 加墓碑、改成确定性发号（`tools.md:593`） | **是**，登记「mmorpg 待做」，动 mmorpg 前需用户同意 | 墓碑落地之前由 Java 账本兜底；落地时同批改 MessageIdRegistry（§6.1 第 6 条） |
| CQ5 | I 级（Java 不用的内部消息）的破坏要不要拦 | **不拦，降一级报告** | J 级由源码扫描自动提升 |
| CQ6 | 账本要不要用 mmorpg 全部历史播种 | **不要** | Java 从没对着更早的编号发布过；本地 mmorpg 是浅克隆 |
| CQ7 | 事件号要不要拦 | **现在 WARN** | Java 没有消费者；等 Java 引入事件号消费者时升到 BREAK |
| CQ8 | 表数据删行要不要拦 | **不拦，WARN** | 策划改表的常态；存量引用由各功能的加载期校验处理 |
| CQ9 | Java 自有 proto（`xm.*` 包）要不要纳入 | **7.5 不纳入** | 它们不是两版共享契约；Java 进程间滚动升级的兼容性另行设计 |
| CQ10 | 要不要按盘点建 `contract/CHANGES.md` | **不建** | C-D6 |
| CQ11 | 是否支持「任意两个 mmorpg 提交」离线比较、在 mmorpg CI 里跑 Java 的检查 | **不做** | 需要两份 mmorpg 加 Java 构建；漂移报告已覆盖「上游 HEAD vs 已记录」 |
| CQ12 | 漂移报告是否分级 | **分级，仍然只报告** | 提前看到「上游复用了号 N」 |
| CQ13 | 和 7.6 发布门禁的关系 | **7.6 的 `tools/Release.java` 复用 `ContractGate`**，读「上一个发布标签 → 当前」的契约面差异写进发布说明 | 7.5 不做 |

### 8.2 导航

| # | 问题 | 推荐答案 | 理由 |
|---|---|---|---|
| NQ1 | 用哪种实现：① 移植 Detour 子集读 `.bin`；② 位图格子导航；③ 只保留 MoveGuard | **①** | `.bin` 是 C++ 服务器的真相；② 实测偏差 0.225 m 且要先改 mmorpg（节庆位图不在 mmorpg）；③ 挡不住穿墙、修不了墙里的存档坐标 |
| NQ2 | 同步哪些文件 | **被引用的 6 个 `.bin` + 生成的 `navmesh.properties`**；引用的文件缺失就同步失败；`tianyong_scene.bin` 不同步 | YAGNI；fail-closed |
| NQ3 | 许可与来源 | **按上游 recastnavigation（zlib）的算法实现，只读数据格式，不复制 UE 修改过的源码；派生文件保留 zlib 声明。请用户确认 `luyuan-cpp/ue5navmesh` 的许可**（本仓库公开且挂在简历上） | 数据格式是事实，算法来自 zlib 上游 |
| NQ4 | 金样从哪来 | **请 mmorpg 加一个 gtest（或 `navmesh_baker --dump-queries`）产出 `data/scene_nav_bin/query_golden.json`**（向量集 §9.2.4）；Java 用 ContractSync 的「测试夹具」类别同步，与 battle-engine-spec Q2 方案 A 一致（`battle-engine-spec.md:1567-1568`）。之前 PARITY 注明「跨语言金样待 mmorpg」 | 唯一能钉死 UE5 私有语义的办法 |
| NQ5 | 导航数据坏了怎么办 | **启动失败**，有整体开关 | N-D3 |
| NQ6 | MoveGuard 与导航的顺序和记账 | **先额度、再射线；射线起点用服务器当前位置；propose / commit，guided / respawned 重置额度** | §4.4.3–§4.4.4 |
| NQ7 | 现在做 FindPath 吗 | **不做**。等第一个服务端寻路调用方（实时怪物要先在 mmorpg 设计）；到时移植上游 findPath + findStraightPath | YAGNI |
| NQ8 | 垂直方向、BV 夹取、`findNearestPoly` 度量与 128 候选上限、候选与 link 的顺序、portal 字节量化、缓冲溢出这些边界 | **照搬上游逐位实现，由金样钉死**，不另加规则。度量取老写法（纯三维、≤128 候选，评审改，§4.3） | §3.2、§4.3 |
| NQ9 | 旧存档一次性改落点 | **接受** | 与基线「由进场逻辑自然修复」一致；robot 用新账号 |
| NQ10 | 要不要热更导航 | **不做**，只在启动时加载 | 基线同；`.bin` 只随契约同步和重启变化 |
| NQ11 | 不支持的网格特性（off-mesh 连接、layer ≠ 0、多层 tile） | **同步和加载时都拒绝** | 当前数据没有；出现时先设计再放开 |
| NQ12 | 指标要不要按场景加 label | **不加** | 计数和计量就够 |
| NQ13 | 是否请 mmorpg 把四份位图放进 `data/` | **可选，低优先级**。有了位图可以在 Java 加「网格与位图一致」的回归测试，并让 mmorpg 不依赖客户端仓库重烘。断言写成「平移 +1 cm 后一致，凹角最多多出 0.325 m」，不能写「完全包住」（§2.5） | Java 实现不需要它 |
| NQ14 | 导航场景的高度要不要取网格 | **要**，与基线一致 | §7.2 |

---

## 9 测试计划

### 9.1 契约闸

**单元测试（`xm-contract-check`）**

- 渲染器：用 `DescriptorProtos` 在代码里构造文件描述符，覆盖 message（含嵌套）、enum、service、map、optional、reserved（号、区间、名字）、自定义 option，断言渲染行；名字解析（同包、外层作用域、全名、嵌套遮蔽）；`pending` / `owner=` 行按 `SOURCE.properties` 区分。
- 比较器：§1.8 每条规则至少一正一反（约 50 条），夹具就是最小的契约面文本和账本；§1.9 类型矩阵逐格。
- 放行：键对得上 → 通过；目标提交不同、值为空 → 拒绝；多余放行 → 重锁与 CI 复核都只出 `accept-unused` WARN。
  同一目标提交上二次重锁（上次的放行行已被消费）→ 通过；放行文件删行 → `accept-removed` WARN；`ledger-tamper` 配放行仍拒绝；`surface-format-with-sync` 不可放行。
- 账本：删除记账；隔一次锁复用 → BREAK；原样恢复 → INFO；旧账本行消失 → `ledger-tamper`。
- 可见性：`ScenePlayerSync` 判 C；夹具里放一个 import `com.game.proto.contracts.kafka.BattleResultEvent` 的 .java，断言该类型判 J；I 级降级。
- 表行：增、删、改，`cfg_multi` 重复主键；行指纹用 `CodedOutputStream` 现写 `.pb`。
- 表达式列：调换两个 `cfg_expr_param` → `table-expr-params` BREAK；加一个参数 → `table-expr` WARN。
- 导航绑定：改一行 BaseScene 的 `nav_bin_file` 或出生点 → `navmesh-binding` WARN；只改别的表 → 无此项。
- msgid：旧侧已是 `unresolved` 的号不重复报；新出现的 `unresolved` → `msgid-orphan` WARN。
- `ContractGate`：退出码 0 / 2 / 3；BREAK ≥ 10 条时恰好 10 行 `::error`。
- `ContractGate` 不依赖 protobuf：在只含 `target/classes`、不含 protobuf-java 的隔离 `URLClassLoader` 里，用两份夹具契约面跑一遍 `main`，退出码符合预期、没有 `NoClassDefFoundError`。
- J 级反查只用契约面：夹具契约面里 `[proto.type]` 的 Java 类名列配一个 import 了它的 `.java`，不加载任何描述符也能判出 J。

**在真实契约上**

- 引导：在 7.5a 上首次重锁，`SURFACE.txt` 生成，`RETIRED.txt` 只有文件头；再跑一次校验模式通过；重锁两次结果逐字节相同。
- 反例（都在 scratch 副本或 worktree 上做，不碰工作区）：

  | # | 操作 | 期望 |
  |---|---|---|
  | a | 对调 `message_id.txt` 里两个客户端方法的号 | 两条 `msgid-renumber` BREAK；重锁被拒；加两行放行后通过 |
  | b | 把一个客户端字段从 uint64 改成 uint32 | `field-retype` BREAK |
  | c | 删一个字段不写 reserved → 重锁（WARN、记账）→ 再让新字段占这个号 | 第二次 `field-reuse` BREAK |
  | d | 对调两个 tip 码 | 两条 `tip-reuse` BREAK |
  | e | 手删 `RETIRED.txt` 一行后提交 | CI 复核 `ledger-tamper`，放行无效 |
  | f | 手改 `SURFACE.txt` 让它与描述符一致却不放行（模拟绕过） | 本地构建通过、CI 复核报未放行 BREAK |
  | g | 手改同步来的一个 proto 字段名 | 锁定测试失败（防手改） |

- 历史回放（本机一次，只读，worktree 在 scratch）：对改过 `contract/SOURCE.properties` 的四个提交 `723067f`、`e02b39d`、`7ab4d9b`、`3a52935` 各拷入 `xm-contract-check` 渲染契约面（`3a52935` 之前没有权威表 schema 与 `TableSchemaReader`，只比 proto / 消息号 / 事件号），相邻两两比较。期望：
  `723067f` → `e02b39d` 只有 INFO（msgid-added 239–243、guild_internal / match_internal 两个文件的类型，`PARITY.md:15`）；`e02b39d` → `7ab4d9b` 只有 `NodeInfo` 字段 11 `client_endpoint` 新增（I 级 INFO，`PARITY.md:16`）。出现任何 BREAK / WARN 逐条解释。

**CI**

- 推送后按 deploy-ci-spec §11.5 匿名读取 check-runs：build job 绿；复核步骤对引导提交输出「旧侧没有契约面」；contract job 绿。
- 手动触发 contract-drift：mmorpg 无漂移时只有一行；人为让 `--commit` 指向旧值时 Summary 出现分级报告且 job 绿。

**robot**：本功能不改客户端可见行为，不加场景。流程规则：报告里有 C 级 BREAK / WARN 时，同步提交之前跑引用粗查列出的 robot 场景，至少 smoke、token、movement。

### 9.2 导航

#### 9.2.1 ContractSync

- `--check` 覆盖 `config-data/navmesh/`：scratch 副本里改 `.bin` 一个字节 → 「不同 config-data/navmesh/main_scene.bin」；把 `.bin` 按文本做 CRLF 转换也必须报不同。
- 结构校验反例（scratch 里的假 mmorpg 目录）：截断、magic 错、版本错、`dataSize` 之和不到文件尾、引用的文件缺失、两个路径同名 → 同步失败、仓库不变。
- 用新稀疏目录做一次干净检出（deploy-ci-spec V5 的办法）后 `--check` 退出码 0，证明 6 个稀疏目录刚好够用。

#### 9.2.2 `xm-navmesh` 单元测试

- 读取器正反例：真实 4 份几何读取成功；截断、magic / 文件版本 / tile 版本错、`dataSize` 不符、下标越界、off-mesh 连接、`flags == 0`、layer ≠ 0 一律拒绝。
- 数据自证（Java 自算，只证明自洽）：tile 数 61 / 34 / 40 / 45；头部参数（orig、32 m、maxTiles 128 / 64、bvQuant 4、walkable 1.8 / 0 / 0.35）；多边形面积 24860.250 / 9099.500 / 8889.750 / 9197.281 m²；
  内部邻接对称；外部边 356 / 149 / 149 / 180 条且每条在相邻 tile 都有匹配。
- 查询（断言值按上游语义推算，金样到来后以金样为准）：BV 量化与夹取（含 §3.2 的垂直不对称：z = −100 能吸附、z = 5 吸不上；水平方向略超 2 m 仍可能吸附）；`closestPointOnPoly` 在多边形内 / 外；raycast：不撞墙 t 极大、撞墙 t ∈ [0,1)、跨 tile、部分 portal、恰好过顶点、沿边；回缩下限（离墙 < 0.05 m 时裁决点 = 起点）。
- 裁判：用 1 mm 步长沿线段做点在多边形内的暴力判定，比对第一次离开网格的位置（容差 1 mm）；用 1 cm 网格暴力求最近点，比对吸附结果。
  判定必须用**闭多边形**（边上算在内）或「到任一多边形的距离 ≤ 1e-9」。否则采样点正好落在 tile 接缝（`orig + 32k`）或多边形公共边上时，
  会被两侧都判成「不在内」，被当成撞墙。评审复核时就踩过：蓬莱 +x 方向在 196.01（tile 接缝）被误报成墙，真正的墙在 230.01。
  另一个办法是直接取 `neis == 0` 的边和射线求交，评审用的就是这个，与本节轴向表一致。

#### 9.2.3 出生点与落位（`SceneSpawnParityTest`，移植 `scene_spawn_test.cpp:51-127`，真实 `config-data`）

- G1：21 行全部注册导航；每行出生点吸附后与原值差 ≤ 0.02。
- G2：配置 2、3、4 用三份不同几何（文件 sha 两两不同、`NavMesh` 实例两两不同）。
- G3：配置 2、3、4 存档 (0,0,0) 与 (9999,9999,9999) 都改写为出生点（±0.02），报告「发生了改写」。
- G4：出生点 +(2,0,0) 吸附得到 nearby，`|dx|+|dy| > 0.5`；同图重入保留 nearby、不算改写；换图落出生点。
- G5：无导航时 (10,20,0) 原样保留；换图落出生点。
- G6：配置号不存在时出生点为 (180,200,0)。
- 轴向回归（编辑实测，几何近似、不含部分 portal 量化，跨 tile 的以金样为准）：出生点沿四个方向到第一条无邻接边的服务器坐标

  | 网格 | +x | −x | +y | −y |
  |---|---|---|---|---|
  | main（180,200） | 196.01 | 52.01 | 250.01 | 148.01 |
  | penglai（170,220） | 230.01 | 148.01 | 256.01 | 168.01 |
  | donghai（180,200） | 210.01 | 172.01 | 240.01 | 186.01 |
  | lanxian（180,200） | 186.01 | 64.01 | 222.01 | 174.01 |

  评审用独立探针对四份文件复测了全部 16 个值：先做 1 mm 步进的点在多边形内判定；蓬莱 +x 在接缝处误报过一次，再用「无邻接边与射线求交」排除。结果与上表一致。
  原稿 main 一行写成 196.011 / 52.010 / 250.011 / 148.009，那是 1 mm 步进的采样边界，不是墙坐标。
  墙坐标都是 `orig + 0.25k` 的精确 double，断言容差用 1e-9 即可。
  例：main 从 (182,200,0) 到 (199,200,0) → `Blocked`，裁决点 x = **195.96**（±1e-9；原稿 195.961 ± 0.001 会挂）。
  蓬莱 +x 方向 196.01 是 tile 接缝（orig 68.01 + 4 × 32），不是墙：射线必须经 portal 穿过去，这条正好当作跨 tile 的回归用例。

#### 9.2.4 跨语言金样（mmorpg 待做，NQ4）

对 4 份几何用固定种子生成，输出服务器坐标、布尔结果与类别，坐标 `%.17g`：

- snap：出生点；出生点 ±(2,0,0)、±(0,2,0)；包围盒内随机 200 点；z ∈ {−100, −4.5, −4.4, −4, 0, 3.9, 4, 4.09, 4.1, 4.5, 4.59, 4.6, 5, 100}；墙外沿轴向、对角 1.9 / 2.1 / 2.5 / 2.9 m；
  格线上、格线 ±0.01 / ±0.011；tile 边界（`orig + 32k`）上；两个多边形公共边上（检查选中的 polyRef 与 C++ 相同）。
- validateMove：出生点出发 16 个方向、长度 {0.04, 0.5, 2.5, 24, 500}；网格内随机点对；过顶点、沿 tile 边界、过部分 portal（含贴墙穿过 tile 接缝）；
  起点在网格外；终点吸附失败的分支；终点 z 很大；起点离墙 0.03 m 朝墙走（回缩夹到 0）。

Java 参数化测试：布尔结果与类别完全相同，坐标误差 ≤ 1e-9（两边都是 double、运算顺序相同）。金样到来之前只出报告（PARITY 注明），到来之后超出容差判红。

#### 9.2.5 场景整合（扩展 `MovementSyncTest`、`SceneWorldTest`、`ChannelDrainTest`、换图与交出进场的现有测试；导航替身一套 + 真实网格一套）

- 合法移动被接受、z = 0；撞墙回 137 且 `server_location` 是撞墙点；额度截断与撞墙叠加时取较近的点，锚点与额度按 §4.4.4 记账。
- guided、respawned 两个分支，且重置额度。
- 外推撞墙：速度清零、置脏位、66 带速度 0、不发 137；起点在网格外时原地不动并清零速度。
- 进场：存档在网格上保留（z 吸附）、不在网格上落出生点、换图落出生点；七种进场路径都经 `EnterPlacement`。
- 无导航场景（替身返回空、或 `nav-enabled=false`）：现有用例全部保持绿；`MoveGuardTest` 原样通过，包括截断后额度恰好为 0 的断言（§4.4.4）。
- 有导航时额度记账：`Passed` 扣 `proposal.spent()`（与无导航逐位相同）；`Blocked` 扣 `min(spent, 锚点到撞墙点)`；`guided` / `respawned` 之后额度满格。
- 帧外推不碰冻结 / 备战中的玩家：这两种状态由 `stopMotion` 清零速度（5.2 冻结、6.3 D5），外推按速度为零跳过，导航不会被调用。用例断言它们的位置在导航场景里同样不变。
- `SceneNavigations` 反例：sha 不符、文件缺失、读取器拒绝、出生点吸不上 → 启动失败；`nav_bin_file` 为空 → 该配置无导航。

#### 9.2.6 性能

`ViewCrowdBenchmarkTest`（`-Dxm.bench=true`）在真实网格下跑 1000 / 2000 人：均值增量 ≤ 5 ms、p99 仍在 50 ms 内（§4.4.7）。
同时输出撞墙停下次数与平均移动人数；移动人数比无导航那次少 10% 以上时，按 §4.4.7 调整人群后重跑，否则结论不可比。

### 9.3 robot

- **`movement --expect-jump correct`**：不改，应通过（§4.5）。
- **新场景 `navwall`**（客户端可见，依据本节与 §9.2.3 的数据；场景配置号不是 1 时明确跳过；每次新账号）：
  1. A、B 进场，A 自己的 21 位置为 (180,200,0)（±0.02）。A、B 必须在同一个 `scene_id`：主世界多频道（5.1）下可能分到不同频道，
     否则第 4 步 B 看不见 A。复用 `MovementScenario` 的「同场景且互相可见」检查，不满足就明确失败并打印两边的 `scene_id`，不静默跳过。
  2. **贴 tile 边界行走不回拉**：A 以 8 m/s、每 250 ms 一条沿 −x 走 20 m 后停下，途中越过 x = 168.01 的 tile 边界；**全程不收任何 137**（回归 2026-09-08 每秒 4 次回拉）。
  3. **撞墙夹持**：A 回到 (182,200,0) 停稳后发 MoveStart（速度 +9 m/s），250 ms 后发 MoveSync 上报 (199,200,0)（17 m，未超 24 m 额度，MoveGuard 不截断）。
     **期望**：收到 137，`server_location` = (195.96 ± 0.01, 200, 0)，`input_seq` 回显；在该点发 MoveStop，之后不再收到 137。
     （推算：MoveSync 到达时服务器外推到 ≈184.25，射线 184.25 → 199 在 196.01 撞墙，回缩 0.05 m。网络慢到外推已先撞墙时，起点就是 195.96，结果相同。）
     不用「连续 2 m 一步走到墙」的写法：起点离墙不到 0.05 m 时裁决点等于起点（§6.2 第 3 条），期望值会漂。
  4. **外推夹持**：B 合法走到 (190,200,0)；A 合法走到 (194,200,0) 后以 +9 m/s 发 MoveStart 并保持静默。期望 B 在约 1 s 内收到 A 的 66：速度 0、x = 195.96（±0.01）；A 收不到 137。
     （推算：外推步长 0.45 m，194 → 194.45 → … → 195.8，下一步越过 196.01 被夹到 195.96。195.8 离墙超过 0.05 m，回缩完整生效，所以结果是确定的。）
  5. **重登**：A 重新登录，自己的 21 位置等于最后的裁决位置。
  6. **远距离上报**：A 上报 (1e5, 1e5, 0)，3 s 内收到 137；位置有限、离上一个位置水平不超过 24 m、z = 0。
  - 开关 `--expect-nav on|off|auto`：
    - `off` 时第 3、4 步按 MoveGuard 口径：第 3 步不回 137，第 4 步 x 越过墙；第 6 步仍有 137（MoveGuard 截断），z 取上报值。
    - `auto` 按第 3 步有没有收到 137 决定口径，并在报告里写明判成了哪种。
- **对基线跑同一个 `navwall`**：基线没有 MoveGuard。第 2–5 步只取决于射线夹持，期望完全相同（第 3 步同样夹在 195.96）。
  第 6 步在基线上是射线直接撞墙，与额度无关。这里两边结果碰巧一样：A 此时离 +x 墙只有 5 cm，射线一出发就撞墙。
  所以「不超过 24 m」这条断言在基线上也成立，但成立的理由不同，报告里要注明。本机没有 Go、跑不起 mmorpg 全栈，由用户在能跑 mmorpg 的环境执行。
- **CI**：`navwall` 加入 integration.yml stack job（deploy-ci-spec §4.5，7.1b），放第二期，紧跟第一期的 `movement`。

### 9.4 本机切片验收

- 按 machine-setup 起 Redis / MySQL / Kafka 后跑切片：scene 日志有 6 条「网格已加载」（按文件名缓存，其中 4 份不同几何）、21 条「已注册」，**没有**启动失败。
- 依次跑 robot `smoke`、`movement --expect-jump correct`、`navwall`、`reconnect`、`cross-node`、`zones`。
- 结束后关掉后台进程（`with-backends.sh`）。

---

## 附录 A：对盘点稿的更正

| # | 盘点原说法 | 更正 |
|---|---|---|
| 1 | 契约变更报告做在 ContractSync `--report`，用新旧两份 FileDescriptorSet 做 diff（`tools.md:224`） | ContractSync 是零依赖单文件程序、旧侧没有描述符集。改为构建期渲染契约面并锁定（§4.1） |
| 2 | 报告追加写 `contract/CHANGES.md`（`:224`） | 不建（C-D6） |
| 3 | 用 `--accept-id-reuse <号>` 放行（`:223`） | 放行文件，键绑定目标提交（§1.10） |
| 4 | 表数据复用 TableDump 的逐行 diff（`:224`） | TableDump 还没做；契约面自带行指纹（§1.5） |
| 5 | Java 仓库没有 CI（`tools.md:216`） | 已过时：7.1a 已在工作区加入 `.github/workflows/` |
| 6 | 「painted-city 场景的网格与 2 m 位图精确重合」，据此推荐格子导航（`tools.md:308`、`:596` Q5） | 只对体素化成立；实测网格多 48.05 m²、凹角最多偏 0.225 m，并且整体平移 +1 cm（§2.5）。推荐改为移植 Detour 子集（§0.4 裁决 4） |
| 7 | 客户端契约按 `OptionIsClientProtocolService` 划分（`contract-robot.md:5`） | `ScenePlayerSync` 只标了 `OptionIsPlayerService` 仍下发给客户端，根必须包含它（§1.6） |
| 8 | navmesh-queries「6 distinct files」（`combat.md:154`） | 6 个被引用的文件，其中只有 4 份不同几何（§2.2） |

---

## 评审修订记录

> 完整性评审，2026-10-05。只读复核了 mmorpg `26ceb70ca`、mmorpg-client `a8577c7`、Java 工作区和 §0 列出的上游规格。
> 数据实测用 JDK 单文件探针，放在会话 scratchpad，不进仓库：MSET 逐段解析、无邻接边求交、客户端位图解码。
> 需要的目录都在稀疏检出里，没有缺失。`third_party/ue5navmesh` 本来就未检出，§0.3 已写明。

### R.1 抽查过的引用（全部属实，除 R.2 列出的）

- 基线：
  - `nav_query.cpp:19`、`:23`、`:27`、`:35-45`、`:47-54`、`:86-149`、`:131-132`；`nav_query.h:11-42`；
  - `navigation.cpp:35-57`、`:59-88`、`:92-118`、`:105`；`recast.cpp:31-94`、`:77-78`、`:81`、`:90`；
  - `scene_spawn.cpp:45-63`、`:65-84`、`:91-149`、`:151-164`；`constants/nav.h:5`、`:19`、`:35-37`；`scene_nav.h:24-27`；`nav_comp.h:13-23`；
  - `movement.cpp:51-52`、`:66-82`；`player_movement_handler.cpp:82-154`、`:124`、`:170-176`、`:216-222`；`player_scene.cpp:60-66`、`:102`；
  - `core/config/config.cpp:7`；`scene_crowd.cpp:23`、`:62-64`；`view.cpp:5`；`scene_spawn_test.cpp:51-127`；
  - `navmesh_baker.cpp:3-12`、`:28-41`、`:100-119`、`:236-251`、`:366-378`、`:405-408`、`:476-481`、`:558`、`:612`、`:676-712`、`:776-778`、`:825-847`；
  - `scene-navmesh-pipeline.md:82`、`:84-85`、`:160-161`；
  - `service_register_info.go:148-167`、`:174-187`、`:189-207`、`:198`；`internal/message_id.go:17-26`；
  - mmorpg `AGENTS.md:44`、`:58-67`；`data/AGENTS.md:82-85`、`:94`；`friend.proto:78-90`；`player_state_attribute_sync.proto:67-69`；`s2s_player_scene.proto:34-41`；
  - `manifest.py:50-56`、`:168-175`；`schema_proto.py:180-201`；`enum_gen.py:414-426`；`.github/workflows/` 里没有 proto 破坏性检查（H4）；`.gitmodules` 的 ue5navmesh 子模块。
- 客户端：`GameClient.cs:1834-1863`、`TianyongPaintedCity.cs:87-95`、`:289`、`FestivalRegionMap.cs:22`、`WorldCoordinateConverter.cs:28-32`、`TianyongNavigationGrid.cs:163-167`、`Net/MessageIds.cs`。
- Java：
  - `ContractSync.java:58-75`、`:99-107`、`:128-139`、`:237-267`、`:342-405`、`:421-443`、`:556-575`、`:626-668`；`contract/SOURCE.properties:4`；
  - `MessageIdRegistry.java:28-39`、`:166-195`、`:262-290`；`xm-proto/pom.xml:41-44`；`xm-table/pom.xml:23`、`:58`；
  - `SceneWorld.java:245`、`:724`、`:764-774`、`:846`、`:1242-1275`、`:1313-1336`；`MoveGuard.java:5-25`；`ConfigSceneTables.java:62-70`；`MovementRules` 的常量；
  - `ChatConfiguration.java:22-23`、`ChatDispatcher.java:55-56`（xm-chat 确实没有真注册表单测）；`BattleResultSink.java:3`；
  - `PARITY.md:15`、`:16`、`:45`、`:91`；`architecture.md:17`、`:23`、`:27`、`:581-582`；`.dockerignore:6`、`Dockerfile:39`；
  - `ci.yml:16`、`:31-61`、`:63-114`、`:92-97`；`contract-drift.yml:37-42`、`:48-71`；`MoveAssertions.java:214-251`。
- 数据：文件字节数、md5 与 git blob；main 头部参数（61 tile、orig、1.8 / 0 / 0.35 / 4、32 m、maxTiles 128、maxPolys 2^20、首个 tileRef `1<<27`）；
  四份文件分段之和 = `dataSize`；BV 叶节点 y 上界 ∈ {0, 2}；tile 头 y ∈ [−0.4, 3.6]；`basescene.json` 21 行的分布。
- 上游规格：deploy-ci-spec `:988` 第 12 条、`:1033` Q5、§3.7、§4.5；battle-engine-spec `:1567-1568`；battle-node-spec `:150`；match-spec `:585`；
  scene-battle-spec `:548`、D5；scene-handoff-spec D10（注意与 scene-channels-spec 的 D10 不是一回事，本稿引用的是 5.2 的）；dungeon-mirror-spec §2.6。

### R.2 更正（原稿错误）

| # | 位置 | 原稿 | 更正 | 依据 |
|---|---|---|---|---|
| 1 | §0.4 #9、§4.5、§9.2.3、§9.3 | 天墉 +x 墙 196.011，裁决点 195.961；main 轴向 196.011 / 52.010 / 250.011 / 148.009 | 墙 **196.01**，裁决点 **195.96**；轴向 196.01 / 52.01 / 250.01 / 148.01 | 墙是 `neis == 0` 的边，坐标为 orig + 0.25k 的精确值。原值是 1 mm 步进的采样边界；单测按原稿 ±0.001 断言 195.961 会失败 |
| 2 | §2.5、§0.4 #4、§6.2 #8、NQ13、附录 A #6 | 网格「完全包住」位图 | 网格相对位图整体平移 +1 cm：服务器 −x / −y 一侧的墙比位图缩进 1 cm（52.000–52.010 位图可走、网格没有） | 客户端位图解码逐点核对；orig = 格线 + 0.01 的体素对齐。5 cm 采样看不见。对客户端的结论不变：偏差 < 0.06 m，不回 137 |
| 3 | §3.2 | BV 上界量化 `\| 1` | `(u16)(q·x + 1) \| 1` | 上游 `queryPolygonsInTile`。原稿要求「逐位照搬」，漏了「+1」会让候选集变小 |
| 4 | §4.4.4 | `commit` 重算锚点到候选点的距离再取 `min`，称与 `admit` 逐位等价 | `propose` 带出 `spent`，候选点被接受时直接扣它 | 超额截断时重算的距离可能比额度小一个末位，额度剩 ~1e-15，`MoveGuardTest.java:52`、`:135`、`:142` 的 `isZero()` 会挂 |
| 5 | §1.10、§6.1 #4、§9.1 | 重锁时多余放行行判失败 | 判 `accept-unused` WARN | 同一目标提交上二次重锁时，上次已消费的放行行会被误判；打错 id 时真正的 BREAK 仍未放行，照样拦住 |
| 6 | §4.4.2 | `SceneWorld.java:847-848` | `:848-849` | 工作区行号 |
| 7 | §4.5 | 行走段终点 ≈184.4 | 184.375（另有外推余量 ≤5 m，到不了墙） | `MovementScenario.planWalk` |

### R.3 补充（原稿缺失）

1. **CI 复核拿不到 J 级反查表**：原稿说 `ContractGate` 只用 JDK 跑 `target/classes`，但「Java 类名 → proto 全名」原本要从描述符算。
   改为契约面 `[proto.type]` 带 Java 类名列，并要求复核用到的四个类不引用 protobuf 类型，加隔离类加载器单测（§1.5、§1.6、§4.1.2、§9.1）。
   复核步骤先检查 `ContractGate.class` 是否存在（§4.1.4、§6.1 #12）。
2. **表达式列选项漏了**：`cfg_expr_type` / `cfg_expr_param` 由 `TableSchemaReader.java:164-165` 读取并决定生成的求值方法签名。
   参数换序会让 Java 静默错位，新增 `table-expr-params` BREAK、`table-expr` WARN（§1.5、§1.8、§6.1 #13）。
3. **BaseScene 换绑网格 / 挪出生点只算 INFO**：新增 `[navmesh] bind` 行与 `navmesh-binding` WARN（§1.5、§1.8）。
4. **etcd 号的 orphan 判定没有落地办法**：改为只报新出现的 `unresolved`（§1.8）。
5. **`[proto.file]` syntax、`google.protobuf.*` 不渲染**（§1.5、§1.8 `file-syntax`）。
6. **重锁命令要带 `clean`**（AGENTS §4，§4.1.3）；只报告模式的报告路径用绝对路径（§4.1.2）；本地重锁看不到 `surface-format-with-sync`，只由 CI 判（§4.1.2、§6.1 #5）。
   放行文件只追加的破坏报 `accept-removed` WARN，复核为此多读一份旧放行文件（§4.1.4）。
7. **ContractSync 读 `basescene.json` 只能用正则**：加行数核对，并说明运行时读 `.pb`、由 G1 兜底一致性（§4.2）。镜像侧补 `XM_NAV_DIR` 与 Dockerfile / `.dockerignore` 注释（§4.2）。
8. **`findNearestPoly`**：UE4 公开源码是老写法，纯三维距离，最多 128 个候选（按记忆，未核实）。实现改取老写法，平地上与新写法落点逐位相同。
   另补候选遍历顺序与并列规则、link 构造顺序、portal 字节量化（误差约 4.7 cm）（§3.2、§4.3、NQ8）。
9. **垂直阈值精确化**：z < 4.1 必吸附、4.1–4.6 看叶节点、≥ 4.6 必不吸附。金样向量加 4.09 / 4.59、公共边、贴墙穿接缝、离墙 0.03 m（§3.2、§9.2.4）。
10. **暴力裁判在 tile 接缝上误报**：评审在蓬莱 +x 196.01 实际踩到，裁判要用闭多边形或无邻接边求交（§9.2.2、§9.2.3）。
11. **客户端进场兜底**（`TianyongMapRuntime.cs:197-222`）：服务器进场点在位图上不可走时，客户端自己挪开并上报（§3.5、§6.2 #8）。
12. **测试装配**：导航挂在 `SceneTables` 的缺省方法上，29 处 `new SceneWorld(` 与各替身零改动；凡用真实 `config-data` 装配 scene 的地方成对给 `xm.nav-dir`（§4.4.1、§4.6）。
13. **7.5c 的前置**补上 5.5 与 6.3，它们同样在改 `SceneWorld`（§4.6）。
14. **帧预算复测的人群可比性**：+x 墙离出生点 16 m，与基准半径 15 m 相当（§4.4.7、§9.2.6）。
15. **robot**：
    - A、B 同 `scene_id` 的前置检查（多频道）；
    - `--expect-nav auto` 的判定口径；
    - 第 4 步外推落点的推算；
    - 基线跑 `navwall` 时第 6 步「结果相同、理由不同」；
    - CI 放第二期（§9.3）。
16. **旧存档改落点**：2 m 以内吸附、更远才落出生点（§6.2 #4）。

### R.4 评审未改、留给实现时确认

- star 数（§5）沿用编辑实测，评审没有重查（本机 GitHub 访问不稳）。结论不依赖具体数值：全部候选都远低于 2 万。
- §2.5 的 48.05 m² / 0.225 m 与节庆三地面积沿用编辑实测，评审只补测了天墉城的边界平移。
- UE 系 Detour 的 `findNearestPoly` / `raycast` 细节仍以 C++ 金样为准（NQ4、NQ8）。在那之前，PARITY 的「已对齐」照原稿带条件。
