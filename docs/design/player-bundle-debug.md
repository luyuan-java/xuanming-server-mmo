# 单玩家排障导出与本地复现（player-bundle，Java 版）

2026-10-19 新增。工具在 game-data：`com.game.data.tools.playerbundle.PlayerBundleExport`（只读导出）与 `PlayerBundleImport`（导入本地），
与 mmorpg（C++/Go 版）`go/data_service/cmd/debug_fetch -mode player-bundle` / `debug_import` 能力对齐：**能拉某玩家的数据、能在本地复现、写本地前有安全闸门**。
两版的存储与归属契约不同（mmorpg 是 Redis 父快照 + 存盘屏障 + Kafka 落库；Java 直接带 `owner_epoch` 围栏写 MySQL / TiDB），所以包格式、判读规则与写入步骤都按 Java 自己的契约做，不照抄。

## 0.1 玩家目录与初始存档的原子存在性（2026-10-23）

正常建角必须在同一个 SQL 事务、同一玩家路由围栏内严格插入 `player` 与初始 `player_state`。初始数据是非 null 的空 protobuf 字节，`saved_epoch=0`；空字节是合法初始值，不等于缺行。初始行发生冲突或写入失败时，目录插入整体回滚，不能覆盖已有孤儿存档。

生产加载、运维快照及回档素材读取遇到 `player_state` 缺行、null 数据或缺失存盘元数据时均拒绝，读取绝不补写。玩家目录的纪元、创建时间或等级不能证明它从未存盘；历史资产和去重账本丢失不能伪装成新号。已保存的货币、背包、宠物及资产／战斗／收件箱账本仍与目录在同一围栏事务写入。

正式导入必须有明确存在且哈希匹配的状态原件，不能以“来源缺行”为理由删除本地存档或制造初始值；直接构造的导入计划同样在首个数据库动作前拒绝 null。缺行的历史库只允许依据独立可信原件恢复，或在已授权、确认本任务归属的上线前本地验收库重建；不能按目录字段猜测并批量补空。生产字段、proto 与生成物不增加 birth 标记。

## 0. 什么时候用

- 玩家反馈「东西丢了 / 进度回退 / 进不了游戏」：先 **导出**，读包里的 `analysis`（在不在线、最近一次存盘落库没有、哪些资产流水晚于最后一次落盘），再看 `mysql` 与 `redis` 原始数据。
- 要在本地断点复现：把包 **导入** 本地隔离环境，用本地 dev 账号登录、EnterGame 这个玩家号，走的是真实的 login 夺权 → scene 加载链路。
- 逐字段比较两份快照、按时刻回看状态，用 game-data 已有的运维接口（`/admin/player-snapshots/{id}?includeState=true`、`/admin/players/{player}/snapshot-diff`，architecture.md §4.5），本工具不重复实现。
- 按玩家拉数据，**不要整库拷贝**：库与 Redis 是全区共用的，整库拷贝会带走所有玩家的数据，Redis 里还有以令牌明文为键名的登录键。

## 1. Java 版玩家数据在哪

| 位置 | 内容 | 新鲜度 / 说明 |
|---|---|---|
| scene 进程内存 | 在线玩家的真实最新态 | 只能等下线（最终写回并释放）后再拉 |
| `player` 行 | 等级、场景、坐标、归属 `owner_epoch` / `owner_released` / `owner_lease_until`、所属账号与归属区 | 与 `player_state` 同事务、同围栏写 |
| `player_state` 行 | `game.storage.PlayerState` 原字节（货币、背包、宝宝、任务、两本账本……）+ `saved_epoch` + `updated_at` | 在线存盘周期 `game.scene.save-interval`（缺省 300 s），资产通道记账即时存盘；离场 / 停服最终写回 |
| `player_snapshot` | 登录 / 登出 / 运维快照（经 Kafka 落库，或 game-data 直写） | 历史对比用 |
| `transaction_log` | 资产流水（按 `from_player` / `to_player`） | 「东西丢了」的主要凭证；经 Kafka 异步落库，没被确认的写兜底日志 |
| `ops_job_player` + `ops_job`、`recall_source` | 回档等运维作业、GM 回收从这名玩家身上扣回的资产 | 运维改过玩家数据的证据 |
| Redis（DB 12，前缀 `<ns>:`） | 位置记录、在线目录、战斗锁、**待结算战斗奖励**、组队 / 帮会 / 匹配的玩家级键 | 见 §3.3 |

**「最近一次存盘落库没有」的判据**（基线的存盘屏障在 Java 里的等价物，`BundleAnalyzer`）：

| `analysis.ownership` | 条件 | 含义 |
|---|---|---|
| `RELEASED` | `owner_released = 1` | 离线。`saved_epoch = owner_epoch`：这一代写者最终写回并释放，库里就是最终态；`saved_epoch < owner_epoch`：这一代没写玩法数据就释放了（跨 zone 交出并释放、进场失败只释放、运维夺权后释放），库里是更早一代落的盘，仍是最终态；没有 `player_state` 行：存档缺失，不能判作新号或最终态 |
| `HELD` | 未释放、租约未过期 | 在线、交出在途或运维作业持有。库里是最近一次在线存盘，内存可能领先至多一个存盘周期——**要确切数据，等下线后重导** |
| `HELD_LEASE_EXPIRED` | 未释放、租约已过期 | 写者失联（进程死了或与库失联超过 30 s）：**最终写回没落库**，库里是最近一次在线存盘，之后的增量已丢失；下次登录强制夺权以库为准 |
| `IMPORT_HOLD` | 租约 = BIGINT 最大值 | 本地导入中断留下的占位（只会出现在本地环境，§4.3） |

另外几条警告：时刻晚于 `player_state.updated_at` 的资产流水条数（写者失联时是「东西丢了」的首要嫌疑）、`saved_epoch > owner_epoch`（正常写路径产生不了，库被部分恢复或人工改过）、战斗锁（进场会恢复冻结）、待结算战斗奖励（battle 已落、scene 还没应用，不在库里的玩法数据中）、位置记录在别的 zone。

## 2. 权限与数据保护

- 连线上（port-forward、取口令）由运维本人操作；AI 不登录 k8s / 云账号，只处理已经拉到本地的文件。
- 导出包含该玩家的游戏数据与账号名。**口令哈希不出库**：SQL 只取 `password_hash IS NULL` 的判断，包里只有 `hasPasswordHash`。登录令牌族键（键名里就是令牌明文）一律不读。
- 导出文件不覆盖已有文件、**不许写进 git 工作区**（工具检查上级目录有没有 `.git`），POSIX 系统上以 0600 创建（Windows 上继承目录 ACL，所以放到只有自己能读的受控目录）。按工单留存，结案删除，不要提交、不要上传。
- 只读保证：库只在一个**可重复读事务**里发固定 SELECT（行、玩法数据与流水计数出自同一个一致性快照），结束回滚。MySQL 额外启用 JDBC 只读；TiDB 按当前连接的实际 `SELECT VERSION()` 识别后不设置 JDBC 只读，因为 8.5 默认拒绝该语法（1235），开启 noop 也不会提供只读保护。TiDB 沿用仓库 `AuthorityJdbc.snapshot` 的固定 SELECT 模式，不设置 noop 开关，不捕获 SQL 异常降级；需要数据库强制的只读权限时使用只读账号。Redis 每把键一条只读 Lua（`TYPE` / `PTTL` / `GET` / `HGETALL` / `ZRANGE`），不 `SCAN`、不写。
  仓库还没有只读数据库账号，线上用的是业务账号——这是遗留风险，建议运维先建只读账号再用。
- 连接串（含账号口令）只从环境变量读；报错只打 SQLState / 错误码，不打 URL。导出与导入用**不同的环境变量名**，shell 里残留的线上连接串不会被导入工具拿去写。

## 3. 导出（只读）

前置：**检出线上部署对应的提交**并打包 game-data（`./mvnw -B -pl game-data -am -DskipTests package`）。`player_state` 的 `decoded` 段用本地检出的 proto 解码，字段不一致时会解错；原字节 `dataBase64` 照样导出，导入用的是原字节。把这个提交填进 `--server-revision`。

```bash
# 运维在另外的终端里保持端口转发（示例端口；TiDB 4000、Redis 6379）
export GAME_NAMESPACE=game
export GAME_BUNDLE_EXPORT_JDBC_URL='jdbc:mysql://127.0.0.1:14000/game?user=<只读账号>&password=<口令>'
export GAME_BUNDLE_EXPORT_REDIS_PASSWORD='<口令>'      # 没有口令可不设
java -cp game-data/target/game-data-0.1.0-SNAPSHOT.jar \
     -Dloader.main=com.game.data.tools.playerbundle.PlayerBundleExport \
     org.springframework.boot.loader.launch.PropertiesLauncher \
     --player <玩家号> --operator <操作人> --reason <工单或反馈编号> --server-revision <线上提交> \
     --redis redis://127.0.0.1:16379 --redis-database 12 \
     --out /secure/tickets/<工单>/player-<玩家号>.json
```

| 参数 | 说明 |
|---|---|
| `--player` / `--operator` / `--reason` / `--out` | 必填。操作人与原因写进包里留档 |
| `--server-revision` | 线上部署的提交；不填会提示 |
| `--limit` | 快照 / 流水 / 运维作业 / 回收记录各取最近多少条，缺省 20，上限 500 |
| `--redis` / `--redis-database` | 缺省 `redis://127.0.0.1:6379`、DB 12 |
| `--skip-redis` | 只导库（判读里的在线状态只依据归属列）；这样的包没有 Redis 来源身份，**不能导入** |
| `--expect-database` | 库名偏离「库名 = 命名空间」规格的存量部署的应急开关（同其他命令行工具） |

部署命名空间与服务进程同口径（`-Dgame.namespace` 优先于 `GAME_NAMESPACE`，缺省 `game`），连上库后先核对 `DATABASE()`。
退出码 0 成功、1 失败（连不上、玩家不存在、写文件失败）、2 参数 / 命名空间 / 库名 / 输出位置不合规。玩家不存在时不写文件。

### 3.1 读包的顺序

`analysis`（`ownership` → `online` → `persistence` → `warnings`）→ `mysql.player` / `mysql.playerState`（`decoded` 是可读形式，`sha256` 与快照的 `stateSha256` 比对就知道当前状态和哪份快照相同）→ `mysql.transactionLog`（按时刻倒序，带原因名）与 `transactionLogCounts` → `mysql.snapshots` / `opsJobs` / `recallSources` → `redis`。
读失败的部分记在 `mysql.errors` 并进 `analysis.warnings`，不影响其余部分。

### 3.2 包格式

`format = xuanming-java-player-bundle`，`formatVersion = 1`；格式只增不改，导入端拒绝看不懂的版本。64 位无符号数（玩家号、epoch、快照号、流水号）一律写成十进制字符串。
`sources` 记录来源主机（不含账号口令）、库名、库版本与**实例身份**（MySQL `server_uuid:<@@server_uuid>`；TiDB `tidb-pd:<PD 实例地址>`，取自 `INFORMATION_SCHEMA.CLUSTER_INFO`；Redis `run_id:<INFO server 的 run_id>`），导入端据此拒绝写回导出来源。

### 3.3 导出哪些 Redis 键

全部经 `RedisKeys` 生成（`PlayerRedisKeys.specs`）：位置记录 `location`（hash，`v` 解成 `PlayerLocation`）、在线目录 `presence`（`PlayerPresence`）、战斗锁、待结算战斗奖励（每个字段解成 `BattleSettlementEvent`）、组队归属与邀请、所属帮会缓存、匹配票据、切磋邀请、观战标记。
每把键一条原子读（类型、TTL、值同一时刻）；**键与键之间不是同一时刻**——这些键的 hash tag 各不相同，集群部署下跨槽的多键命令会被拒绝。

不导出的：登录令牌族（键名即令牌明文）、按账号的设备表、要 `SCAN` 才找得到的战斗已销账墓碑 `battle:{pid}:settled:<battle_id>`、共享集合里的成员（世界频道预约、匹配队列与评分镜像）、好友列表缓存（MySQL 的缓存，看库即可）。

## 4. 导入本地复现

前置：本地全套服务可用（`tools/local/start-slice.sh`），本地检出与包里的 `serverRevision` 一致；本地区服目录里有该玩家归属区且处于开放状态（或用 `--zone` 改成本地已开放的区）；本地同号玩家不在线。

```bash
export GAME_NAMESPACE=game
export GAME_BUNDLE_IMPORT_JDBC_URL='jdbc:mysql://127.0.0.1:3306/game?user=root&password='
# 先预览（缺省，不连任何外部系统、不写）
java -cp game-data/target/game-data-0.1.0-SNAPSHOT.jar \
     -Dloader.main=com.game.data.tools.playerbundle.PlayerBundleImport \
     org.springframework.boot.loader.launch.PropertiesLauncher \
     --file /secure/tickets/<工单>/player-<玩家号>.json --local-account dev_tester
# 核对预览里的写目标都是本地后执行；本地已有同号存档时加 --replace
java ...（同上）... --file <包> --local-account dev_tester --execute [--replace] [--zone <本地区>] [--name <新名字>]
```

然后本地登录：账号 `dev_tester`、口令为本地 login 的 `GAME_LOGIN_DEV_PASSWORD`（开发口令模式只认 `dev_` / `robot_` 前缀），Login（48）后 EnterGame（26）这个玩家号。
导入把 `player.account` 改写成 `--local-account`，所以角色列表里就能看到它。

### 4.1 写前闸门（任一不过都不写，退出 2）

1. **本机地址**：JDBC URL 与 Redis 地址的主机必须是 `localhost` 或回环 IP 字面量（不做 DNS 解析）；只接受单主机的 `jdbc:mysql://`。本地 docker 服务名用 `--allow-target-host <名字>` 显式放行（精确匹配）。这一步在建任何连接之前做。
2. **库名**：`DATABASE()` 等于部署命名空间（`--expect-database` 应急开关同导出）。
3. **实例身份**：目标库与目标 Redis 的身份都要取得到，且分别不等于包里记录的同类来源身份（库比库、Redis 比 Redis）——port-forward 之后线上实例也在 127.0.0.1，只看地址拦不住。任一侧取不到身份（含 `--skip-redis` 导出的包），一律拒绝（无法证明「不是导出来源」就不写）。
4. **本地状态**：本地同号玩家被未过期的租约持有（在线）一律拒绝，`--replace` 也不行；已有同号存档要 `--replace`；名字与本地别的玩家冲突（名字全服唯一、大小写与全半角不敏感）时拒绝，用 `--name` 换一个。这一条在第一段事务里加锁复核（提交点复核，不只靠预检）。
5. **包完整性**：格式与版本认得、玩家号自洽、`player_state` 原字节与 `sha256` 相符；导出时 `player_state` 部分读失败的包拒绝（不知道线上有没有玩法数据）。

### 4.2 写什么

- `player` 行：包里的全部列，但 `account` = `--local-account`、`name` / `zone_id` 可被 `--name` / `--zone` 改写、`name_key` 按 `PlayerStore.nameKey` 重算；**归属三列不照搬线上**——本地重铸 epoch E = max(线上 epoch, 本地 epoch) + 1，大于本地任何旧写者，它们的迟到写回、续约、释放全被围栏挡掉。
- `player_state`：必须有存在性明确、哈希匹配的原字节，`saved_epoch = E`；来源缺行或 null 时拒绝导入，本地原存档完全不变。
- `account` 行：补建（`INSERT IGNORE`，同登录时的 `ensureAccount`），口令不导入。
- 本地 Redis：只删三把会干扰加载链路的本玩家键——位置记录（别的 zone 的记录会把登录重定向走、本 zone 的会试图回到不存在的实例）、战斗锁（会把玩家冻在不存在的战斗里）、待结算战斗奖励（会被应用到导入的数据上、污染复现）。**线上的 Redis 键不导入**。

### 4.3 三段写入与中途失败（fail-closed、可重跑）

1. **占位并写入**（一个事务）：加锁复核 → 补账号 → 写玩家行为 (E, 未释放, 租约 = BIGINT 最大值) → 写或删玩法数据。这个租约永不过期，从此 login 的夺权（「已释放或租约过期」才成功）拿不到归属、回 2005：玩家进不来，不会以半成品被加载。失败整笔回滚，可直接重跑。
2. **清本地 Redis 残留键**。失败时玩家停在占位，修好 Redis 后带 `--replace` 重跑。
3. **发布**：`UPDATE … SET owner_released = 1 WHERE epoch = E AND 未释放 AND 租约 = 哨兵值`，只有占位仍是自己的才生效；之后玩家可以登录。没生效（占位被别的导入或人工改动）时报错，查明后重跑。

重跑认得上一次中断留下的占位（`analysis.ownership = IMPORT_HOLD` 也是它），要求 `--replace`，按同样三段收敛。

### 4.4 限制

- 只导入登录加载链路读的 `player` 与 `player_state`。好友、帮会、邮件、交易、匹配评分等其他服务的数据，Redis 里的待结算奖励等都不导入（包里可以看）；配置表不导入，本地配表版本须与线上一致。
- 不支持换玩家号（玩法数据里的实例号、账本里的身份不会跟着改）；撞号用 `--replace`。
- 导入的是导出时刻库里的状态：导出时玩家在线（`HELD`）就不是最终态。
- 要「问题发生那一刻」的状态：先用包里的快照与流水对比；不够再走库的备份恢复到影子库，再对影子库导出（目标库仍须是本机地址，影子库的实例身份与线上不同）。

## 5. 残余风险

- **Redis `run_id` 随重启变化**：导出之后线上 Redis 重启过，「目标就是导出来源」就认不出来。挡在前面的是闸门一（本机地址）；即使漏过，导入对 Redis 只删这名玩家的三把键。
- **TiDB 身份**取的是 PD 实例地址：两套集群恰好用同一组内部地址时会被误判为同一实例——那是拒绝方向的误判（fail-closed）。读 `CLUSTER_INFO` 可能需要 `PROCESS` 权限；取不到时导出会提示、包里没有库身份，导入一律拒绝。
- 线上业务账号直接用于导出（没有只读账号），只靠工具只发 SELECT 保证不写。

## 6. 设计取舍

- **放在 game-data**：它已经依赖 game-player-store（`PlayerState`）、game-audit（流水 / 快照的原因枚举）、game-discovery（`RedisKeys`、`PlayerLocation` / `PlayerPresence`），并有同类离线工具 `AuditFallbackReplay` 的打包与启动方式；不新建模块。
- **直接用 JDBC 而不是 `PlayerStore`**：导出要的是「库里原样是什么」，包括正常写路径不会产生的异常值；导入要写的是归属三列的特定组合（占位哨兵），`PlayerStore` 的方法都是业务语义（夺权、写回、释放），不该为排障工具开口子。SQL 只有这两个类里的几条固定语句。
- **占位哨兵而不是「先写 Redis 屏障」**：Java 的加载链路只认 `player` 行的归属列，所以 fail-closed 的开关就放在这里——永不过期的租约同时起到「挡住登录」和「标记这是导入中断」两个作用，不需要额外的表或键。
- **epoch 重铸而不是照搬**：照搬线上 epoch 可能小于本地旧写者的 epoch，旧写者的迟到写会盖掉导入的数据。
- **导出与导入是两个命令**：读线上、写本地的权限与闸门完全不同，分开后导入工具从不需要线上连接串。

## 7. 测试与验证状态

- 单测（`game-data/src/test/java/com/game/data/tools/playerbundle/`）：`BundleAnalyzerTest`（判读的每种归属状态与警告）、`LocalTargetsTest`（本机地址与实例身份闸门）、`PlayerBundleSqlTest`（H2；`-Dgame.it.mysql` 时连真 MySQL）：导出只读且口令哈希不出库、输出位置检查；导入后用生产 `PlayerStore` 夺权并加载到逐字节相同的玩法数据；非本机、实例身份相同、本地在线、名字冲突、包被改过一律拒绝且不写；第二段中断后夺权拿不到归属、带 `--replace` 重跑收敛。Redis 用内存替身。
- 2026-10-19 本机 JDK 21 离线 Maven（`./mvnw -o -B -T1 -pl game-data,game-robot -am test -Dtest='com.game.data.**.*Test,NamespaceLiteralGuardTest'`）：上述三个类 9 + 7 + 13 = 29 项通过（H2）；game-data 全部 354 项 0 失败 / 0 错误（26 项是既有的真库 / 真 Redis 门控用例，默认跳过）；`NamespaceLiteralGuardTest` 9 项通过（登记了新的构造入口）。
- **未验证**：真 MySQL / TiDB 与真 Redis 上的运行（本机当时没有可用实例）；`RedissonPlayerRedis` 的只读 Lua 与 `INFO server` 读取、`InstanceIdentity` 对 MySQL `@@server_uuid` 与 TiDB `CLUSTER_INFO` 的读取都只经编译，没有连真实例跑过；本地全套服务起来后用导入的玩家走一遍登录进场景也没有做。
- **2026-10-19 Codex 复验**：Temurin JDK 21.0.12.1+1-LTS、Maven 3.9.9，在 Java 仓执行交接命令 `./mvnw -B -T1 -pl game-data,game-robot -am test -Dtest='com.game.data.**.*Test,NamespaceLiteralGuardTest' -Dsurefire.failIfNoSpecifiedTests=false`（Windows 为 `mvnw.cmd`），14 项 reactor SUCCESS、退出 0；game-data 328 通过 / 26 项既有环境条件跳过 / 0 失败 / 0 错误，玩家包三类 29 项及命名空间守卫 9 项均通过。执行前后源码与构建输入 SHA256 一致；只覆盖所选测试，不代表全仓回归。证据在同级 C++/Go 仓 `build/verification/playerbundle-20261019-082657714/summary.json` 与 `regression.log`。
- **本轮真实冒烟受阻**：已尝试启动本机 Docker Desktop 一次，backend 因初始化 Secrets Engine 时无法访问 / 改名 `docker-secrets-engine/engine.sock` 而崩溃，Linux engine 管道不可用；未起依赖容器与本地切片，未重置数据或修系统。真实 MySQL / TiDB 的实例身份与只读事务设置、Redis Lua / INFO、导入后的 Login(48) / EnterGame(26) 仍未验证。恢复后须使用两个不同数据库实例身份和两个不同 Redis run_id（仅换库或 DB 编号不行），先打包当前 jar；真实导出除退出 0 外，还要检查 `mysql.errors` 和每条 `redis[].error` 为空，并覆盖非空值读取。`PlayerBundleSqlTest` 即使接真库仍替换实例身份与 Redis，不能替代上述适配器实测。

- **2026-10-20 Codex 续验**：当前来源 2,541 文件独立快照，JDK 21 下 `clean install` 的31项reactor全部成功；所选368项=342通过+26既有条件跳过，0失败/错误。玩家包9+7+16=32项及命名空间守卫9项全过。另在独立原生MySQL8.4.11上运行 `PlayerBundleSqlTest`，16项全过、0跳过，临时库清理为0；测试仍替换实例身份和Redis。新产物与回归只绑定该快照，原工作区结束时并行改了契约来源元数据 `contract/SOURCE.properties`。证据在同级C++/Go仓 `build/verification/playerbundle-resume-20261020-073353275/summary.json`。
- **本轮首停**：两套私有Redis8.10.2及私有TiDB8.5.2已能启动；真实适配器探针却因Codex生成的javac参数文件将引号拆成独立行而退出2，尚未运行测试体。修正参数文件已准备，按AGENTS §10.2未自动重跑、等待用户决定。本轮5个自有容器与自有MySQL均已正常停止并保留数据，未操作其他会话实例。Lua/INFO、生产身份适配器、TiDB只读事务实际查询和导入后的登录进场景仍待验证，不能用上述MySQL测试或容器就绪替代。

- **2026-10-20 真实适配器续验**：用户允许修正探针参数后重跑，探针编译0；生产MySQL8.4.11实例身份、两个独立Redis8.10.2的只读Lua/string/hash/zset/TTL/none/INFO与清理、TiDB8.5.2的PD身份均实际通过。**TiDB快照仍有真实缺陷**：生产 `BundleMysqlReader.beginSnapshot`→SELECT1失败，SQLState42000/code1235；未重试、未启用noop。官方说明见[tidb_enable_noop_functions](https://docs.pingcap.com/tidb/stable/system-variables/#tidb_enable_noop_functions)，它不会提供真实只读事务保护。四文件最小候选已准备，按AGENTS §10.2等待应用与继续验证的决定；候选尚未落地主仓或运行。CLI整包和Login48→EnterGame26仍未验。私有5容器/MySQL均正常停止，保留数据；具体原件见同级C++/Go仓 `build/verification/playerbundle-resume-20261020-073353275/adapter-results.json`。

- **2026-10-20 当前验收结论（补充此前待决策/未验状态）**：用户批准后已应用TiDB最小修复。新增真实门控测试 `BundleMysqlSnapshotIntegrationTest`：以当前连接实际版本开启快照，执行SELECT并rollback；MySQL额外断言JDBC只读。修复后的14项reactor干净构建成功，所选369项＝342通过＋27条件跳过，0失败/错误；27项包含新增真实库测试的默认跳过。随后以 `-Dtest=PlayerBundleSqlTest,BundleMysqlSnapshotIntegrationTest -Dgame.it.mysql=jdbc:mysql://127.0.0.1:<端口>` 在独立MySQL8.4.11和TiDB8.5.2各跑17项，全部通过、无跳过；TiDB另带 `-Dgame.it.require-tidb=true`（测试端口24000）。这些是定向测试，不代表所有Java测试均已运行。
- **真实生产适配器与完整加载通过**：MySQL server_uuid、TiDB PD身份、两套Redis8.10.2 Lua/INFO run_id和非空二进制string/hash/zset/TTL/缺键读取均已通过。源/目标各用原start-slice启动16服务，实际SQL与Redis实例身份不同；来源货币场景7项、目标背包场景11项全过。正式导出错误均空；正式导入首次epoch3，显式replace重导epoch4并真正删除三个哨兵（3→0）；同玩家Login48→EnterGame26→重登后再次导出，精确钻石380、整个player_state原字节SHA与源相同，两包mysql.errors及redis[].error均空。最终目标包的归属epoch6，来源epoch2，符合重导及两次登录夺权递增。
- **TiDB本机全切片前置**：team的Druid默认只读连接同样不能向TiDB发送READ ONLY。本轮仅给team使用SELECT账号、连接参数 `readOnlyPropagatesToServer=false`，其余服务仍使用各自所需权限；未开启全局或会话noop，源/目标均读回OFF。驱动参数含义见[Connector/J配置](https://dev.mysql.com/doc/connector-j/en/connector-j-reference-configuration-properties.html)。结果topic按既有门禁准备3分区、实际RF3/ISR3、minISR2、`unclean.leader.election.enable=false`；保留7天，不通过关闭门禁绕过。Windows MSYS2版redis-cli会在本机此次调用中去掉参数里的花括号，哨兵校验须用保留字面键名的客户端；本轮最终使用隔离Redis容器内CLI。
- **原件、恢复与范围**：证据位于同级C++/Go仓 `build/verification/playerbundle-resume-20261020-073353275/`，汇总 `e2e-final-summary.json`，源运行 `e2e-pb1020z/`、停库前权威行复核 `e2e-pb1020s/`、最终目标 `e2e-pb1020u/`，适配器绿态 `fixed-probes-20261020-082548189-d27385/`。TiDB旧1235及所有脚本首错分目录保留；源包恢复前再次核对离线、epoch和SHA，未伪装一次全绿。Java进程、原生MySQL及11个自有容器已停止，无OOM，卷和受控TEMP导出包保留；未碰其他会话数据。C++/Go本轮仅静态核对RR/SELECT/回滚，无同类JDBC缺陷；未执行其编译/真实CLI/登录，其PD身份门禁差异未在本任务扩改。无proto或客户端契约改动，未提交、未推送。