# 功能清单：数据层（mmorpg 26ceb70ca ↔ xuanming-server-mmo）

范围：`go/db/**`、`go/data_service/**`、`go/schemamigrate/**`、`tools/merge_zone/**`、`tools/data_consistency_check/**`、
`proto/common/database/**`、`proto/data_service/**`、`generated/data/mysql_database_table_list.json`；
为看清存盘 / 加载链路，另读了 C++ scene `player_lifecycle.cpp`（SavePlayerToRedisImpl）、`player_database_loader.cpp`、
`core/system/redis.cpp`（周期存盘）与 go/login `dataloader`。Java 侧对照 `xm-player-store`、`xm-scene/storage`、`xm-login`。

## 区域概述

mmorpg 的玩家权威数据是每个 zone 库（`zone_{Z}_db`）里的 `player_database` / `player_database_1` 两行（每个组件一个 proto 子消息 → MEDIUMBLOB 列，
表结构由 proto2mysql 从 `mysql_database_table.proto` 推导），热数据是 Redis 里整份 `PlayerAllData:{id}` blob。
存盘链路：C++ scene 脏比对 → 带 owner_epoch 的 Lua CAS 写 Redis → 每张子表一条 `DBTask` 发 Kafka `db_task_zone_{home}` →
go/db 按 key 有序消费（子分片、合并、applied 游标、跨实例排序锁、归属 epoch 守卫、按落点选库、大字段闸）→ MySQL + 回写 `{MsgType}:{id}` 缓存；
加载链路反过来由 login 发 read 型 DBTask、经 Redis 结果列表 + pub/sub 回收、拼出 `PlayerAllData` 后 scene 再读。
go/data_service 是全局服务：全局库（号段 `id_segment`、全服角色名 `player_name`、`transaction_log`、`player_snapshot`、回档审计）、
mapping Redis 里的 `player:zone` 归属映射 / 合服围栏，以及一组 GM 快照 / 回档 / 回收 RPC（回档实际未接线、回收只做 dry-run）。
tools/merge_zone 是维护窗口合服 + 在线搬库的大工具；data_consistency_check 是跨区引用巡检。
Java 版只有 `xm_java` 一个库两张表（`account`、结构化列的 `player`：等级 / 场景 / 坐标 / 外观），离场时同步写回 MySQL，
用 `owner_epoch + owner_released + owner_lease_until` 做归属围栏；没有 Kafka、Redis 数据缓存层、周期存盘、组件 blob、号段、快照 / 回档、合服。

## 功能

### player-data-record — 玩家主数据记录（组件化持久化容器）
- mmorpg: `proto/common/database/mysql_database_table.proto`（player_database 字段 1–17、player_database_1、player_centre_database）、`proto/common/database/player_cache.proto`（PlayerAllData）、`bag_quest_mail_data.proto`、`cpp/libs/services/scene/player/system/player_database_loader.cpp`（Marshal/Unmarshal）、`generated/data/mysql_database_table_list.json`
- client messages: none（间接：进场后各系统下发的状态都来自这条记录）
- tables: none（配表）；库表 player_database、player_database_1、player_centre_database
- depends on: player-load-path、player-save-pipeline
- behavior: 一行一个玩家，每个组件一列 proto blob：transform、uint64/uint32 通用组件、skill_list、BaseAttributes（health/mana 等）、level、currency（含补缴 debts）、stress_test_probe、merge_state、attribute（加点）、pet、bag、mission、profile（名字只读副本）、asset_op_ledger、settlement_ledger。加载时：health==0 按职业初值复活并回满到二级属性上限；等级超上限压回；技能表清洗；资产账本校验失败只关该玩家资产通道（挂 Invalid 组件）不阻断登录。
- internal: proto2mysql 按 message 建表（子消息存 base64 MEDIUMBLOB）；加列纪律=取下一个空闲字段号且声明在末尾（merge_zone 按列名 `INSERT…SELECT` 依赖列序）；资产与账本必须同记录同一次落盘（不变量 I3）。Java 需要：一张能承载各组件状态的玩家记录（结构化列或 proto blob 列），随各玩法逐步加列。
- java: partial — `xm-player-store/src/main/resources/db/xm-player-schema.sql` 的 `player` 只有 class/gender/appearance/level/scene_config_id/pos_xyz（朝向、血蓝、货币、技能、背包、任务、宠物、加点、账本都没有）；`PlayerRow.java`
- size: M（容器与编解码本身；各组件随各自功能落地）
- robot: robot_smoke（进场即读）、robot.data_stress.yaml
- hazards: player_database_1 只有压测探针、player_centre_database 全仓无读写者（只 debug_fetch 认识），是死表；`profile_component` 名字副本可能缺失，读侧必须回源 BatchGetPlayerName；CopyFrom(currency) 必须在账本 / 欠款写入顺序之外，否则抹掉补缴欠款（C++ 注释踩过）。

### account-character-list — 账号与角色列表存储
- mmorpg: `mysql_database_table.proto` user / user_oauth / user_phone / user_password / user_accounts、`go/login/internal/logic/clientplayerlogin/{loginlogic,createplayerlogic}.go`（`GetAccountDataKey` Redis blob）、`go/login/cmd/password_admin`
- client messages: 48 Login（C2S，回角色列表）、14 CreatePlayer（C2S）
- tables: none
- depends on: player-name-registry、id-segment-allocator
- behavior: 账号 → AccountSimplePlayerList（角色摘要）存在 login Redis 的账号 blob；user_accounts.password 存 Argon2id，供密码登录只读查询；每账号角色上限 5（2001）。
- internal: 账号 blob 用 Lua get-or-init；建角改 blob 后写回 Redis。user / user_oauth / user_phone / user_password 表在 zone 库建出但本次读到的代码里没有业务写者。
- java: done — `account` + `player` 两张结构化表，角色列表 = `SELECT … WHERE account=? ORDER BY created_at`（`PlayerStore.listPlayers`），上限在事务里锁账号行保证（`createPlayerWithinCap`）；口令认证仍是开发口令（login 区域）
- size: S
- robot: robot_smoke（Login / CreatePlayer）
- hazards: mmorpg 里角色列表只在 Redis 账号 blob 中，本区域未发现把 user_accounts.simple_players 写回 MySQL 的路径（Redis 丢数据 = 角色列表丢失，待核实）；Java 用 MySQL 事实表，没有这个风险。

### player-save-pipeline — 玩家存盘链路（写后台 / 异步落库）
- mmorpg: `cpp/libs/services/scene/player/system/player_lifecycle.cpp` SavePlayerToRedisImpl、`cpp/libs/engine/infra/storage/redis_client/redis_client.h`、`go/db/internal/kafka/key_ordered_consumer.go`（handleTask / runDBOp / publishDBWriteResult）、`proto/db/db_task.proto`
- client messages: none
- tables: none
- depends on: player-data-record、owner-epoch-write-fence、db-task-consumer-ordering、placement-routing
- behavior: 存盘 = 整份 PlayerAllData 序列化 → Redis 裸 SET（带 epoch 时 Lua CAS）→ player_database 与 player_database_1 各发一条 `DBTask{key=player_id, op=write, msg_type=全名, body, task_id, owner_epoch}` 到 `db_task_zone_{home_zone}`（世代号>1 时加 `_g{N}`；home_zone 未知回落进程 zone 并计数）。go/db 写 MySQL 成功后回写 `{MsgType}:{id}` 缓存（TTL DefaultTTLSeconds），缓存写失败则任务整体重试（不推进游标）。离场、周期、交接前都走这条。
- internal: Kafka 生产 key=player_id；login / db 两边分区数镜像且启动 fail-closed；落库是幂等整行覆盖（proto2mysql Save = upsert）。Java 需要：离场写回 + 周期写回 + 失败不丢的持久化重试（Java 惯用可选：存储线程池 + 本地持久化重试，或 Kafka/spring-kafka）。
- java: partial — `xm-scene/.../storage/StoragePlayerRepository.java` 离场时在有界存储线程池上同步 `saveStateAndRelease`（带 epoch 围栏），瞬时故障 5s 内退避重试 ≤3 次，之后只记 ERROR + 计数；没有周期存盘、没有缓存层、没有持久化的失败队列
- size: M
- robot: robot.data_stress.yaml（全链路收敛）、robot.currency-crash.yaml（崩溃窗口快照）
- hazards: Kafka send 失败只打日志（Redis 已写、MySQL 可能永远落后，直到下一次存盘）；`where_case` 是原样拼进 SQL 的 WHERE 串（生产者是内部服务才安全）；DBTask 不带版本号，MySQL 的新旧只靠分区内 offset + epoch 判断。

### periodic-autosave — 周期存盘 + 脏比对跳过
- mmorpg: `cpp/libs/services/scene/core/system/redis.cpp`（periodicSaveTimer，`SCENE_PLAYER_SAVE_INTERVAL_SECONDS` 默认 300）、`player_lifecycle.cpp`（dirty_save::IsEqual 与 LastPersistedSnapshotComp）、`player/system/dirty_save_stats.h`
- client messages: none
- tables: none
- depends on: player-save-pipeline
- behavior: 每秒一个槽位，只存 `player_id % interval == slot` 的玩家，整周期每人恰好一次（不在一个 tick 里全量扫，避免数百 ms 停顿）；与上次成功落盘的快照逐字段相等（剔除探针字段）就跳过（no-op，不发 DBTask）；0 = 关闭。进程崩溃时最多丢 interval 内的增量。
- internal: 上次落盘快照组件 + 跳过率计数（relaxed atomic，周期打日志）。Java 需要：场景逻辑线程上按槽位分摊地抓取不可变存档快照，投递存储线程池写回（续约时顺带也可），与离场写回共用围栏 SQL（不释放归属）。
- java: missing — `StoragePlayerRepository` javadoc 明写「只在离场时写回、没有周期存盘，进程被 kill 时本次在线期间的增量会丢」；`PlayerStore` 也没有「写回但不释放」的 SQL
- size: M
- robot: none（mmorpg robot 不断言周期存盘）
- hazards: 脏比对必须在打探针之前；周期存盘与交接 / 退出存盘并发时由 owner_epoch CAS 兜底；Java 加周期写回时要用「带 epoch 围栏、不改 owner_released」的 UPDATE，不能复用 `updateStateAndRelease`。

### owner-epoch-write-fence — 归属 epoch 写围栏
- mmorpg: `cpp/libs/services/scene/player/comp/player_ownership_comp.h`、`player_lifecycle.cpp`（带 guard 的 Save、HandlePlayerSaveRejected）、`go/db/internal/kafka/key_ordered_consumer.go`（checkOwnerEpoch / guardOwnerEpoch / luaMarkAppliedEpoch，键 `consumer:applied_epoch:{topic}:{key}:{msgType}`）、`proto/db/db_task.proto` owner_epoch
- client messages: 23 PlayerKicked/tip 2017（顶号，间接）
- tables: none
- depends on: player-save-pipeline
- behavior: scene_manager 每次归属变更 INCR `player:{id}:owner_epoch`；scene 存 Redis 用 Lua 比对 epoch，不等即拒并回调 HandlePlayerSaveRejected（被拒 epoch ≠ 当前缓存 epoch → 只是旧代在途写，用新 epoch 重存；相等 → 本节点被废黜）；go/db 对 write 任务比对「已落库的最大 epoch」：小于即丢弃并计 stale_owner_write_rejected，等于 / 大于放行并只升不降地推进；epoch=0 为兼容窗口放行。合并写时 epoch 不同的两条写互为段边界、不互相 supersede。
- internal: Redis Lua CAS + 消费端 applied-epoch 键（永久、无 TTL）。Java 的等价物是 MySQL 行级 `owner_epoch` + `owner_released` + `owner_lease_until`（夺权只在已释放或租约过期时成功）。
- java: done — `PlayerStore.claimOwnership / saveStateAndRelease / releaseOwnership / renewOwnerLeases`、`PlayerMapper` 各条带 `WHERE owner_epoch = ?` 的 SQL、`xm-scene/.../ownership/OwnerLeaseRenewer.java`（10s 续约，续不上即丢实例踢会话）；db-migrations.md M2；PARITY「玩家数据归属」行
- size: M
- robot: robot_smoke（老角色重登走围栏自增）
- hazards: mmorpg 比对的是「已落库最大 epoch」而非当前铸造值，否则交接前的最后一笔合法写会被误杀；markAppliedOwnerEpoch 失败只记日志（守卫落后一格）；Java 租约依赖墙钟、要求 NTP（偏差需远小于 30s）。

### player-load-path — 玩家数据加载链路
- mmorpg: `go/login/internal/logic/pkg/dataloader/{ensure_player_all_data_async,sync_loader,data_loader}.go`、`go/db/internal/kafka/key_ordered_consumer.go`（handleDBReadOp / publishDBReadResult / publishTaskResult）、`cpp/libs/engine/infra/storage/redis_client/redis_client.h`（NIL 重试）、`player_database_loader.cpp`
- client messages: 26 EnterGame（C2S）→ 79 NotifyEnterScene 等（间接）
- tables: ClassTable（复活初值）
- depends on: player-data-record、player-save-pipeline
- behavior: login 进游戏时：`PlayerAllData:{id}` 父键命中即跳过；否则逐子表查 `{MsgType}:{id}` 子缓存，缺的发 `op=read` DBTask（WHERE 串由 login 拼），db 读 MySQL（无行 = 空消息当成功，即新角色）、回写子缓存、`LPUSH task:result:{taskID}`（5 分钟过期）+ `PUBLISH task:result:notify`；login 收齐后拼父 blob 写 Redis 再放行进场。scene 读父键遇 NIL 按 2,4,…,60s 退避约 2 分钟后失败。失败统一推 tip kEnterSceneFailed（3023），保留登录会话。
- internal: TaskResultDispatcher（Redis pub/sub 驱动，无 BLPOP 常驻协程）；读任务在同 key 写之后排队，读作为合并的屏障（读后写一致）。Java 需要：从库加载完整玩家记录并投回场景逻辑线程。
- java: done（机制不同）— `StoragePlayerRepository` 在存储线程池 `store.findPlayer(playerId)` 后投回逻辑线程；进场失败 gate 推 23 {3023}（PARITY「进场异步失败」行）。只是加载的记录内容受 player-data-record 限制
- size: M
- robot: robot_smoke、robot.stress-*.yaml（EnterGame 吞吐）
- hazards: 读任务按「无行=空消息成功」发布，与「读失败」靠 TaskResult.success 区分；Kafka 分区若被 broker 自动建成 1 分区，其余分区的读任务无人消费，EnterGame 大面积超时（2026-05-31 压测实例，现在 EnsureTopics 启动校验）。

### db-task-consumer-ordering — 存盘消费者的按键有序与去重
- mmorpg: `go/db/internal/kafka/key_ordered_consumer.go`（worker / routeToSubShard / processTaskBatch / orderingForTask / acquireOrderingLock / processDBTask 扩容态）、`go/db/db.go`（EnsureTopics 分区契约）、`go/db/etc/db.yaml`（PartitionCnt=10、SubShardCount=8、TopicGeneration）、`go/shared/kafkautil`
- client messages: none
- tables: none
- depends on: player-save-pipeline
- behavior: 每分区一个 worker，按 key 哈希再分 8 个子分片（同 key 同协程）；批内同 (key,msgType) 连续写只保留最大 offset（读是屏障，epoch 不同是段边界，来源分区不同 / seq 未知不合并）；applied 游标 `consumer:applied:{topic}:{key}:{msgType}` = `v2:{partition}:{offset+1}`、永久无 TTL，seq ≤ 游标即丢弃，跨分区 / 无分区信息的写不可比 → 进死信隔离；写任务持跨实例排序锁 `kafka:ordering:{topic}:{key}:{msgType}`（TTL 2min，30s 续期，落库后 verify 一次才推进游标）；分区扩容期间（Redis expand status，30 分钟过期自动复位）再加 `kafka:consumer:lock:{key}` 5s 锁；offset 只提交连续完成的前缀（claimAcker）。topic 实际分区数超出配置时按需建 worker。
- internal: sarama 消费组 + go-redis + redsync 风格锁。Java 的同步写回在单一归属写者 + epoch 围栏下天然有序，不需要这层；若 Java 将来改为异步队列落库，必须重做同等语义（按 key 串行、去重游标、跨分区不可比即隔离）。
- java: not_applicable — Java 不经消息队列落库（每玩家同一时刻只有一个持有 epoch 的写者、写回同步执行）；tech-stack.md 把 spring-kafka 列为「后续批次用」
- size: L
- robot: robot.data_stress.yaml + go/db/cmd/data_stress + verifier
- hazards: 历史坑都写在注释里：按配置固定建 worker 会静默丢多出分区的消息；重试按 Key%N 回不了原分区导致旧 seq 盖新 seq（现在 retry payload v2 携带原分区）；游标若跟缓存 24h TTL 走，延迟重试会复活覆盖新快照；「先 ACK 后落库」被改成落库 + 游标都成功才 ACK。

### db-task-retry-dlq — 存盘失败的持久化重试 / 死信 / 毒消息隔离
- mmorpg: `go/db/internal/kafka/key_ordered_consumer.go`（deferFailedTask / saveToRetryQueue / consumeOneRetryTask / moveRetryReceipt / quarantineOrderingConflict / quarantinePoisonTask / recoverRetryProcessing）、`go/db/internal/kafka/retry_payload_test.go`
- client messages: none
- tables: none
- depends on: db-task-consumer-ordering
- behavior: 失败任务先 LPUSH 进 `kafka:retry:queue:{topic}`（payload `[0x02][seq 8B][partition 4B][DBTask]`，推送 0/100/500/1000ms 四次尝试）成功才 ACK Kafka，入队也失败就不 ACK（DATA-LOSS PREVENTED）；重试消费者每 tick 最多认领 200 条，认领原子搬到 `kafka:retry:processing:{topic}`，成功删收据、失败原子搬回；超过 retryMaxTimes 进 `kafka:dead:queue:{topic}`；落点冻结 / 落点库不可用 / 落点变化不耗重试次数；解不出的 Kafka 消息以 JSON（0x03 前缀，含 topic/partition/offset/原始字节）进死信；启动时把 processing 里的残留搬回 ready。单条消息 panic 也走同一条持久化路径。
- internal: Redis 列表 + Lua 原子搬移收据。Java 需要：写回失败不能只记日志——要有持久化的待重写队列（本地文件 / Redis / MySQL 表）与人工处理的死信，并保持同一玩家的写回顺序与 epoch 围栏。
- java: partial — `StoragePlayerRepository` 只做进程内退避重试（≤3 次、5s 预算），之后记 ERROR（带 player_id / epoch / 场景 / 坐标供人工修复）+ `writeFailures` 计数；进程退出即丢
- size: M
- robot: none
- hazards: 大字段闸拒绝的写也走重试 → 死信（payload 不丢，人工决定裁剪或调上限）；合服预检要求三个队列为空（merge_zone preflight）。

### placement-routing — 玩家存储落点选库（多库路由）
- mmorpg: `go/db/internal/kafka/placement_route.go`、`go/shared/placement/placement.go`、`go/db/internal/logic/pkg/proto_sql/store_registry.go`、`go/db/db.go`（MarkPlacementCapability / KeepPlacementCapability）、`docs/design/player-storage-placement.md`
- client messages: none
- tables: none
- depends on: home-zone-registry、db-task-consumer-ordering
- behavior: 每条读写任务一次 MGET `player:placement:{id}` 与 `player:zone:{id}`：有记录 → 记录的库；无记录 → home_zone 的 zone 库；都没有 → 本进程 zone 库。记录值 `{storage_id}:{version}` 或 `…:frozen:{run_id}`（搬库中，写延后不耗重试）；值畸形一律 fail-closed（不当缺席）；写的 home_zone ≠ 本 ZoneId → 死信（合服前滞留写）；SQL 之后再 MGET 复核，变了就作废重投（关 TOCTOU）。库名由编号派生：1..999999 → `zone_{id}_db`，≥1000000 → `player_store_{id}_db`；额外落点库按需打开（只 Ping + 只读 schema 闸，从不建库），连接池 8/2。go/db 往 mapping Redis 写 `db:capability:zone:{Z}`（TTL 90s、30s 心跳）证明本 zone 跑的是按落点选库的新版。
- internal: Redis 契约键 + 多数据源注册表。Java 只有一个库，不需要；若 Java 做多区多库，需要等价的「玩家 → 存储库」映射与搬库冻结语义。
- java: not_applicable — 单库 `xm_java`，`player.zone_id` 只是归属区字段
- size: L
- robot: none
- hazards: `Placement.Required=true` 必须在所有 login 已 PinOnCreate 且各 zone 已 pin-placement 之后才能开（顺序反了会全员 fail-closed）；落点 Redis 必须是主节点，读副本关不住 TOCTOU；mapping Redis 是 go-zero RedisConf，恒 DB 0（yaml 里写 DB 会被忽略）。

### home-zone-registry — 玩家归属区登记与查询
- mmorpg: `go/data_service/internal/routing/router.go`（RegisterPlayerZone / GetPlayerHomeZoneAndMergeFence / BatchGetPlayerHomeZone / DeletePlayerZone / ClientForPlayer）、`go/data_service/internal/server/dataserviceserver.go`、调用方 `go/login/.../homezone`、`go/scene_manager/internal/logic/home_zone.go`、guild / trade / friend / match 的 home_zone 适配
- client messages: none（间接：14 CreatePlayer 时登记；归属区合服中 scene_manager 拒绝进场 → 3023）
- tables: none
- depends on: placement-routing（可选钉落点）、merge-fence-and-remap
- behavior: 建角时 `player:zone:{id}` SETNX 登记，绝不覆盖：同值幂等成功、异值返回冲突（ErrCodeZoneMappingConflict）；目标 zone 有合服围栏 `merge:in_progress:{zone}` 时拒绝（预检 EXISTS + Lua 提交点复核，查询失败按封锁）；storage_id≠0 时同一段 Lua 只在 home 新写成时 SETNX 落点记录；查询无映射 → gRPC NotFound（login 回退账号 blob 里的建角 zone），值畸形 → 错误；GetPlayerHomeZone 同时报告 home_zone_merging；批量查询走 pipeline。
- internal: mapping Redis（DB 0）键 `player:zone:{id}`；data_service 按 home_zone 选 zone Redis Cluster（dev 单实例）。Java 需要：一个可被各服务（scene-manager、将来的 guild / trade / friend）查询的「玩家 → 归属区」真源与合服期间的拒绝语义。
- java: partial — `player.zone_id` 在建角时写入（`PlayerMapper.insertPlayer`），`PlayerViews` 下发；但没有对外查询接口、没有合服围栏；`EnterGameHandler` 选场景用的是会话 zone（`session.getZoneId()`，缺省 defaultZoneId），不是角色行的 zone_id
- size: M
- robot: robot.stress-3zone-*.yaml（多区）
- hazards: 旧实现无条件 SET，合服后一次建角重试就能把玩家送回已下线源区（现为 SETNX）；Java EnterGame 用会话 zone 而非角色 zone，跨区角色列表出现时会进错区（需 login 区域确认）。

### player-name-registry — 全服角色名唯一登记
- mmorpg: `go/data_service/internal/logic/player_name_logic.go`、`internal/store/player_name_store.go`、`internal/routing/player_name_cache.go`、`proto/common/database/rollback_database_table.proto` player_name、`go/shared/playername`、`go/login/internal/logic/pkg/playernamereg`
- client messages: 14 CreatePlayer（C2S；重名 tip 2033）、48 Login / 26 EnterGame 的角色名展示（间接）
- tables: RoleNameRule（长度 2–12，由 login 把关）
- depends on: account-character-list
- behavior: 名字归一 = NFKC + 去首尾空白（展示名保留大小写），唯一键 name_norm = 再转小写；Reserve 结果 OK / Taken（附占用者 id，只给 login 判「丢响应重试」，禁止下发客户端）/ Invalid（UTF-8、1–32 字、字符集、敏感词）；同 player 同名重试 = 成功（already_owned）；同 player 已有别名 = Conflict（当事故处理）；Release 无管理令牌只能删 created_ms 在 10 分钟窗口内的行（建角失败补偿），超窗需 `x-admin-token`；BatchGetPlayerName 读缓存 `player:name:{id}`（正 24h、负 60s，NUL 字节哨兵）未命中回源。
- internal: 全局库 player_name（主键 player_id、唯一 name_norm、VARCHAR 手写 bootstrap DDL）、ODKU 从聚簇索引起锁避免死锁；zone 库 profile_component 与账号 blob 只是副本。
- java: done — `player.name_key` + 唯一索引 `uk_player_name_key`，键只由 `PlayerStore.nameKey`（NFKC→strip→小写）计算，插入角色行与占名同一条 INSERT（无需释放补偿）；重名 → 2033，撞主键 → 异常（1003）；PARITY「角色名唯一大小写不敏感」行、db-migrations.md M1。差异：没有独立的批量查名接口（其他服务目前也不存在）
- size: M
- robot: robot_smoke（CreatePlayer）
- hazards: mmorpg 名字表与角色行不在一个事务（名字先登记、角色后建），中途崩溃留孤儿名，只能靠释放窗口 / 运维清；Java 同表同事务没有这个问题；Java 字符集校验在 login（PlayerNames.normalize）。

### id-segment-allocator — 永久身份号段发号
- mmorpg: `go/data_service/internal/logic/id_segment_logic.go`、`internal/store/id_segment_store.go`、`internal/store/schema.go`（idSegmentBootstrapDDL / raiseIdSegmentFloor）、`go/shared/idsegment/{client,minter,adapter}.go`、C++ `cpp/libs/modules/id_segment/guid_segment_client.h`、`cpp/nodes/scene/id_segment_bootstrap.h`
- client messages: none（产物 player_id / item_uuid / guild_id 对客户端可见，只要求唯一）
- tables: none
- depends on: schema-migration
- behavior: `AllocateIdSegment(biz_tag, step) → [lo,hi)`，半开区间、发出即作废不回收；tag 须匹配 `^[a-z0-9_]{1,64}$`，step 上限 1e7；值域 < 2^55（与存量雪花号 ≥6.7e16 不相交），见底 → Exhausted fail-closed；缺行生产环境 → UnknownTag（不自动种行），只有迁移按 BootstrapTags（player, guild, item, txlog, snapshot, trade_listing, guild_asset_op）`INSERT IGNORE` 从 1 预建；迁移对 player / guild 做地板校验（max_id ← 消费表 MAX(<2^55)+1）。客户端双 buffer 预取、动态 step（<15min 用完翻倍、>30min 减半）、范围单调性校验不过即拒用。
- internal: 全局库 id_segment，`SELECT … FOR UPDATE` + `WHERE version=?` CAS，死锁重试 4 次；data_service 往 etcd 写 C++ 约定的 NodeInfo 路径供 scene 发现（noderegistry）。
- java: partial — player_id 用雪花号 + 节点号租约（`xm-login/.../character/PlayerIdGenerator.java`，租约无效拒绝发号；PARITY「节点号租约有效期判定」行）；物品 guid、帮会 id、流水 / 快照 id 尚无发号源
- size: M
- robot: none
- hazards: 全局库从旧备份恢复 = ID 安全事件：从低水位重发会被 `INSERT … ON DUPLICATE KEY UPDATE` 静默覆盖他人角色；item / txlog / snapshot 做不了地板校验。Java 若继续用雪花号，需同样保证 worker 不重叠与时钟回拨拒发（已有）。

### schema-migration — 版本化库表迁移
- mmorpg: `go/db/internal/migrate/{plan,runner}.go`、`go/db/cmd/migrate/main.go`（status / plan / up、-create-database、-allow-modify、-storage-id）、`go/schemamigrate/{schemamigrate,plan,runner}.go`（guild / friend / trade 共用）、`go/data_service/internal/store/schema.go`（全局库 AutoMigrate / `-migrate`、ensureIndexes、extraIndexes）
- client messages: none
- tables: none
- depends on: none
- behavior: 台账 `schema_migrations(version, name, checksum, dirty)`：version=1 基线（按 proto 表清单 CREATE TABLE IF NOT EXISTS，checksum=表名集合），之后每次 up 按真实库结构算漂移（缺列 ADD COLUMN；schemamigrate 还补缺表）记成 `auto_proto_sync_<checksum12>`；执行前写 dirty=1、全部成功改 0，有 dirty 拒绝继续；`GET_LOCK("mmorpg_db_migrate:<库名>")` 跨实例互斥；会话 lock_wait_timeout / innodb_lock_wait_timeout + 每条 DDL 硬超时与旁路 KILL QUERY；列类型漂移默认只报告（-allow-modify 才 MODIFY）；多余列 / 表只告警、永不删。全局库在表已存在时 proto2mysql 不建索引，ensureIndexes 按「列序前缀覆盖」补索引。
- internal: proto2mysql 从 proto 推导 DDL（禁止手写与 proto 平行的 DDL）；业务进程启动默认不跑 DDL（DDLPolicy，env `DB_AUTO_CREATE_DATABASE` / `DB_AUTO_MIGRATE_SCHEMA` 双向覆盖）。Java 需要：有版本台账、可重复执行、跨实例互斥的迁移入口（候选：Flyway / Liquibase，均 <2 万 star，按 AGENTS §2 需另行裁决，或自写极简台账），替代「启动时 CREATE TABLE IF NOT EXISTS + 手工迁移文档」。
- java: partial — `xm-player-schema.sql` 由 Spring SQL init 在 xm-login 启动时执行，只能建新表；存量库变更靠 `docs/design/db-migrations.md` 手工 SQL（M1、M2），无台账、无漂移检测、无互斥
- size: M
- robot: none
- hazards: go/db 的基线跑过后再加表永远建不出来（只有 WARN），schemamigrate 修了、go/db 未切换；存量表无 `pb:N` 注释时首迁对每一列 MODIFY = 整表重写，启动路径只给 5 分钟；TiDB 上 GET_LOCK / KILL QUERY 语义未验证。

### db-startup-guards — 存储进程启动闸（库名白名单 / schema 闸 / 严格模式 / 大字段闸）
- mmorpg: `go/db/internal/dbguard/{allowlist,blob}.go`、`go/db/internal/logic/pkg/proto_sql/db.go`（assertConnectedDatabase / assertSchemaUpToDate / newMysqlConfig）、`go/db/etc/db.yaml`（AllowedDatabases、AllowlistEnforcement、BlobGuard）、`go/data_service/internal/store/mysql.go`
- client messages: none
- tables: none
- depends on: schema-migration
- behavior: 启动 `SELECT DATABASE()` 必须落在与 ZoneId 无关的外部白名单（env `DB_ALLOWED_DATABASES` > 文件 > yaml），不在即拒启；库不存在 fail-closed（不顺手建库）；缺 proto 声明的列 / 可建出的缺表 → 拒启并打印补救命令（2026-09-22 缺 profile_component 致全部登录失败的教训）；每条连接 `sql_mode='STRICT_TRANS_TABLES'`（防 MEDIUMBLOB 静默截断）；落库前大字段闸：单列 256KiB、单行 1MiB（入库字节，含 base64 膨胀），80% 告警，超限拒写进重试 / 死信，player_snapshot 放宽到 8MiB。
- internal: 指标 db_blob_bytes / db_blob_row_bytes / db_blob_guard_total。Java 需要：库名不能因配置错而静默新建；结构不符时启动即失败；严格模式。
- java: partial — 两个 JDBC URL（`xm-login`、`xm-scene` 的 application.yaml）都带 `createDatabaseIfNotExist=true`（正是 mmorpg 要堵的「配错库名静默建新库」），未显式设置 sql_mode（依赖 MySQL 8 默认严格模式）；没有启动期 schema 核对（列缺失要到第一次 SQL 才 1054）；目前无 blob 列，大字段闸不适用
- size: S
- robot: none
- hazards: AllowlistEnforcement=warn 只许 dev 用，生产缺省 strict；Java 引入组件 blob 时要同时引入大小上限，否则一个膨胀组件会拖垮每次写回。

### txlog-ingest — 交易流水落库与查询
- mmorpg: `go/data_service/internal/kafka/{txlog_consumer,start}.go`、`internal/store/transaction_log_store.go`、`internal/logic/recall_logic.go` QueryTransactionLog、生产者 `cpp/libs/modules/transaction_log/transaction_log_system.{h,cpp}`（含 anomaly_detector）、`proto/common/rollback/transaction_log.proto`
- client messages: none 直接（scene 的 GM 客户端 RPC GmQueryTransactionLog 是桩，且默认拒绝）
- tables: none
- depends on: id-segment-allocator（tx_id 段）、各资产玩法（货币 / 背包 / 交易）
- behavior: topic `transaction_log_topic_g1`（6 分区、保留 30 天、key=player_id）；攒批 200 条 / 200ms 一条多行 `INSERT IGNORE`，先插库后提交 offset；解不出 / tx_id=0 跳过并提交；库故障有界重试后停住不提交（宁积压不丢），supervisor 60s 后续消费；Kafka 不可达不拖死服务（后台每 30s 重试）。QueryTransactionLog 按玩家 / 时间 / 类型 / 物品 / 货币过滤分页（默认 100）。
- internal: 全局库 transaction_log（主键 tx_id，4 条 (维度, timestamp_sec) 联合索引，zone_id 列）。Java 需要：资产变动审计流水（异步、幂等、可按玩家时间查）。
- java: missing — 无流水表、无生产者（Java 尚无货币 / 背包）
- size: M
- robot: none
- hazards: data_service_role_and_scope.md「已知缺口」说 C++ 生产者仍用裸名，已过时：`cpp/libs/modules/audit/audit_topic.h` AuditTopicName 已拼 `_g<N>`（0 归一为第一代），两边须同代号；关停顺序必须先停消费者再关连接池，否则最后一批丢；分区数改动要换代号，绝不原地扩分区（打乱同玩家顺序）。

### player-snapshot-ingest — 玩家快照采集与落库（回档素材）
- mmorpg: 生产者 `cpp/libs/modules/snapshot/snapshot_system.{h,cpp}`（CaptureAndSend，`player_lifecycle.cpp` 登录 / 登出各一次）、消费者 `go/data_service/internal/kafka/snapshot_consumer.go`、`internal/store/snapshot_store.go`（InsertSnapshotIfGuidAbsent / ListSceneSnapshotsByPlayer / DeleteOldSnapshots）、`proto/common/rollback/player_snapshot.proto`
- client messages: none
- tables: none
- depends on: id-segment-allocator（snapshot 段）、player-data-record
- behavior: PlayerSnapshotEntry{snapshot_id, player_id, snapshot_time, trigger(LOGIN/LOGOUT/PERIODIC/PRE_TRADE/PRE_MAINTENANCE/GM_MANUAL), player_database_blob, player_database_1_blob, schema_version, zone_id=捕获时所在 zone} → topic `player_snapshot_topic_g1`（3 分区）；消费者逐条落 `player_snapshot`（source=1、snapshot_guid=snapshot_id、operator="scene-node"），MySQL 上按存储型生成列 `snapshot_guid_nz = NULLIF(snapshot_guid, 0)` 上的唯一键 `uk_snapshot_guid_nz` 用 `INSERT … ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)` 去重；建 store 时探测一次，唯一键不在（未迁移 / DDL 回滚 / TiDB）才退回单条 `INSERT … SELECT … WHERE NOT EXISTS`（间隙锁，并发会成环、靠有界 1213 重试吸收）；超 MEDIUMBLOB（2^24-1）/ id 为 0 / 解不出 → 跳过并计数；库故障停住不提交。
- internal: 全局库 player_snapshot（自增主键，索引 player_id、snapshot_guid、(zone_id, created_at)）。Java 需要：周期 / 登录登出快照（含全部组件）与保留策略，供回档使用。
- java: partial（2026-10-03，批次 2.3b）— xm-data 第二条 `ConsumerLoop` 落 `xm_java.player_snapshot`（主键即快照号、ODKU 幂等，不需要基线的生成列唯一键与启动探测）；保留期 `xm.data.retention.player-snapshot`（缺省永久）；运维查询 `GET /admin/player-snapshots` 只给元数据。尚缺：周期快照、回档读路径（随 GM 回档批次）
- size: M
- robot: robot.currency-crash.yaml（currency-crash-snapshot 模式采集崩溃窗口快照）
- hazards: 唯一键是独立迁移，未迁移 / TiDB 时去重退回 NOT EXISTS 形态（TiDB 上去重失效）；GM 读路径一律只看 source=0，source=1 的 proto blob 还没有任何回档读路径（`ListSceneSnapshotsByPlayer` 未接 RPC）——快照在落库但用不上。

### gm-snapshot-diff — GM 快照 / 差异查看 / 事件快照
- mmorpg: `go/data_service/internal/logic/snapshot_logic.go`（CreatePlayerSnapshot / ListPlayerSnapshots / GetPlayerSnapshotDiff / resolveSnapshot）、`recall_logic.go` CreateEventSnapshot、`internal/server/dataserviceserver.go`、`proto/data_service/data_service.proto`
- client messages: none（运维 gRPC）
- tables: none
- depends on: data-service-kv-proxy、player-snapshot-ingest
- behavior: Create：读 `player:{id}:*` 全部字段 → JSON 字段图 → 写 player_snapshot(source=0, snapshot_type=SnapshotType)，无字段 → NotFound；List：按 before_time、默认 20 条；Diff：指定 snapshot_id 或 target_time 之前最近一份，与当前字段逐个比较（only_in_snapshot / only_in_current）；CreateEventSnapshot：事件类型（大额交易、充值、维护前、升级里程碑、每日首登）映射成快照类型 + 描述性 reason。这几条都不要求 x-admin-token。
- internal: 全局库 player_snapshot；data_service 按 home_zone 选 zone Redis。Java 需要：运维面（Dubbo / HTTP 管理端，带鉴权与审计）对玩家存档做快照与差异查看。
- java: missing
- size: M
- robot: none
- hazards: 读的是 data_service 自己那套 `player:{id}:<field>` 键，而 scene 真正存盘写的是 `PlayerAllData:{id}` 整份 blob（asset_op_ledger_logic.go 注释已点明）——对真实玩家快照为空（NotFound）或抓到的不是权威数据；建 / 查快照无鉴权。

### gm-rollback — GM 回档（单人 / 整区 / 全服）
- mmorpg: `go/data_service/internal/logic/rollback_logic.go`、`internal/guildcheck/guild_divergence.go`、`internal/svc/servicecontext.go`（RollbackFence 接口）、`internal/server/dataserviceserver.go`（authorizeAdmin，`x-admin-token`）、表 rollback_audit_log
- client messages: none（scene 侧 GmPreviewRollback / GmExecuteRollback 客户端 RPC 是桩且默认拒绝，属 GM 区域）
- tables: none
- depends on: gm-snapshot-diff、player-snapshot-ingest、guild 资产通道（帮会闸）
- behavior: 必须带 x-admin-token（未配置 = 停用）；先写 STARTED 审计（审计库不可用则零变更），再持「跨服务离线 epoch 栅栏」（目标在线 / 登录中 → ErrCodePlayerOnline），计划阶段解析实际快照（不能用 target_time 本身），帮会闸检查快照时刻以来已终结的帮会资产操作（有分歧拒绝，除非 accept_guild_divergence + reason + operator），执行：再选快照（不得早于计划用的那份）→ 打 PRE_ROLLBACK 安全快照 → 全量 / 部分字段覆盖写 Redis（跳过版本校验）→ 写后复查帮会分歧；整区按 (zone_id, created_at) 扫快照，报告孤儿候选（不再自动删，orphans_cleaned 恒 0）；全服先为全部 zone 建计划、合并过一次帮会闸再逐区执行；最后写 RESULT 审计（不随调用方取消的 ctx）。
- internal: 全局库 player_snapshot / rollback_audit_log；mapping Redis；guild 只读 RPC。Java 需要：离线前提下按快照恢复玩家记录的运维操作（带归属栅栏——Java 可直接复用 owner_epoch 夺权作栅栏）、审计与帮会一致性检查。
- java: missing
- size: L
- robot: none
- hazards: 生产代码从不给 `svcCtx.RollbackFence` 赋值 → 三个回档 RPC 一律回 ErrCodeNotImplemented（fail-closed，实际不可用）；即便接上，写的也是 `player:{id}:*` 字段图而不是 scene 读的 `PlayerAllData:{id}`，回档对在线数据无效；source=1 的 scene 快照（proto blob）还不能当回档源。表 player_debt、rollback_audit 在 proto 里定义但全仓无读写者。

### gm-batch-recall — GM 批量回收（按流水筛选）
- mmorpg: `go/data_service/internal/logic/recall_logic.go` BatchRecallItems、`internal/server/dataserviceserver.go`
- client messages: none
- tables: none
- depends on: txlog-ingest
- behavior: 按玩家列表（空 = 全部）、物品 config_id（0 = 货币，取 currency_type）、时间窗、tx_types 查 transaction_log；结果超过 10000 条分页上限 → ErrCodeResultTruncated、零变更（不许对不完整集合动手）；dry_run 只报告匹配；非 dry_run 一律把每条记为失败并回 ErrCodeNotImplemented，但照写审计（rollback_type=4，players_affected=0）。不要求 x-admin-token。
- internal: 真正回收需要：货币走扣减、道具落持久化的 pending-recall 意图并由 scene 在 load/save 时消费——两者都不存在。Java 需要：资产玩法落地后再设计「离线待执行扣回」的持久化意图。
- java: missing
- size: M
- robot: none
- hazards: 旧实现只置 success=true 就累加 total_recalled（对运营「说谎」），已改为 fail-closed；非 dry-run 调用无鉴权也能写审计行。

### data-service-kv-proxy — 跨区玩家数据 KV 代理（Load / Save / Field，乐观锁）
- mmorpg: `go/data_service/internal/logic/data_logic.go`（LoadPlayerData / SavePlayerData / GetPlayerField / SetPlayerField / DeletePlayerData，saveFieldsScript）、`internal/routing/router.go`（ClientForPlayer / AcquirePlayerLock）、`docs/design/data_service_role_and_scope.md`
- client messages: none
- tables: none
- depends on: home-zone-registry
- behavior: 键 `player:{id}:<field>`（hash-tag 同 slot）+ `player:{id}:__version`；Load 不给字段时 SCAN 全部、跳过内部字段，读不到版本即失败（不回 version=0 冒充成功）；Save / SetField 先拿玩家锁（mapping 与 data 两份副本），Lua 原子「比版本 → 写字段 → 版本自增」，区分版本冲突与锁已失效（TTL 后恢复的旧持锁者不许写）；expected_version=0 跳过校验；DeletePlayerData 可连带删归属映射。均无鉴权。
- internal: 设计初衷是让非 home zone 的 scene 透明读写 home zone Redis；当前全仓（Go / C++ / Java）没有任何调用方，scene 实际走的是 PlayerAllData blob 与 Kafka 存盘。
- java: not_applicable — mmorpg 内无调用方；Java 单库直接按 player_id 读写，跨区访问时可直接用 PlayerStore
- size: M
- robot: none
- hazards: 与 scene 的权威 blob 是两套互不相干的存储，GM 快照 / 回档又建在它上面（见 gm-snapshot-diff、gm-rollback）；DeletePlayerData / SavePlayerData 无管理令牌，任何能连 data_service 的进程都能删改。

### asset-op-ledger-read — 已落盘资产账本只读查询
- mmorpg: `go/data_service/internal/logic/asset_op_ledger_logic.go`、`go/shared/assetop/{ledger_dataservice,reconcile}.go`、调用方 `go/guild/internal/svc/asset_op.go`、`proto/common/component/asset_op_ledger_comp.proto`
- client messages: none
- tables: none
- depends on: player-data-record（asset_op_ledger 组件）、player-save-pipeline、guild 资产通道
- behavior: GET zone Redis `PlayerAllData:{id}`（键前缀取 message 全名，不手写），解出 asset_op_ledger 返回；语义表：键不存在 → found=false；blob 解不开或 player_id 不符 → Internal；home_zone 查不到 → Unavailable；player_id=0 → InvalidArgument；调用方取消 → Canceled/DeadlineExceeded；绝不回落读 MySQL（异步落库可能更旧）。guild 的重投循环据此提前终结「scene 已记账」的长期离线指令（不变量 I7）。
- internal: 只读、不加锁、不鉴权；指标 result=found/absent/error。Java 需要：帮会资产通道落地时，让帮会服务能查询玩家已落盘的账本（Java 落库是同步 MySQL，可直接读 MySQL）。
- java: missing — 无账本组件、无帮会
- size: S
- robot: guild_smoke（间接）
- hazards: 任何错误路径都不能回 found=false，否则把「Redis 坏了」藏进看似正常的 absent 计数。

### merge-fence-and-remap — 合服围栏与归属区改写
- mmorpg: `tools/merge_zone/{fence,merged_into,manifest,merge_run,main}.go`、`go/data_service/internal/routing/router.go`（RemapHomeZoneForMerge、IsMergeInProgress）、`internal/server/dataserviceserver.go`（RemapHomeZoneForMerge 需 x-admin-token）、读围栏者 `go/guild/internal/logic/merge_fence.go`、`go/scene_manager/internal/logic/home_zone.go`
- client messages: none 直接（合服窗口内 EnterGame 被 scene_manager 以 ErrHomeZoneMerging 拒绝 → 客户端收 3023；建角 RegisterPlayerZone 被拒）
- tables: none
- depends on: home-zone-registry
- behavior: 停服维护窗口：先对 src 与 dst 两个 zone 立 `merge:in_progress:{zone}`（JSON 值仅供排障、读者只 EXISTS；有 TTL + keepAlive；按 run_id Lua 校验后删），之后才扫 mapping 收集源区玩家清单并在任何写之前落盘 manifest（续跑读清单、不重扫，dry-run 只写 `.dryrun.json`）；`merge:merged_into:{src}` 先于映射改写写入，源区已被合走 / 目标区自己已被合走即拒绝（A10）；映射按清单逐键 CAS `player:zone` src→dst（必须在玩家数据迁移之后）；data_service 的 RemapHomeZoneForMerge 是 SCAN 全量版本，要求源区围栏存在（dry-run 也要），否则零变更。围栏期间 guild 拒绝建帮 / 经济操作。F 之后 M 之前的拒绝正常释放围栏；M 之后失败保留围栏、按指引人工核对后续跑。
- internal: mapping Redis DB 0；guild Redis DB 2 的 `guild_rank:maintenance_lock`。Java 需要：zone 合并 = 把一批玩家的归属区改成目标区，期间拒绝两区新建角色 / 帮会，且可续跑、可审计。Java 单库下可退化为事务内 `UPDATE player SET zone_id=dst WHERE zone_id=src` + 围栏标记。
- java: missing
- size: M
- robot: none
- hazards: mapping Redis 恒 DB 0（go-zero RedisConf 无 DB 字段）——把围栏写进别的 DB 会让 fence 与 remap 静默无效；映射改写一旦先于数据迁移，玩家会被路由到还没有他行的库。

### merge-player-data — 合服玩家数据迁移（pin / copy 两种模式）
- mmorpg: `tools/merge_zone/{player_rows,pin_placement,player_blob_migrate,capability_check,placement_ops,placement_codec}.go`、`go/db/db.go`（能力标记）
- client messages: none
- tables: none
- depends on: merge-fence-and-remap、placement-routing
- behavior: pin（默认）：清单玩家无落点记录者钉 `player:placement={src}:1`，有效落点保持源库，不拷行、不拷 blob、不删缓存；动手前对 `-db-capability-zones`（必填、无缺省，可为 none）每个 zone 核对 go/db 能力标记。copy（旧语义）：撞号预检（全部玩家表）→ 无落点记录者按列名对齐 `INSERT…SELECT` 从 `zone_src_db` 拷到 `zone_dst_db`（目标已有行按策略：完全相同视为已拷）→ 失效共享缓存 `{MsgType}:{id}` / `PlayerAllData:{id}`；多集群时另拷 data Redis 的 `player:{id}:*`。两种模式都拒绝有冻结记录（搬库中）或畸形记录的玩家，清单记下模式，续跑必须同模式。
- internal: MySQL 跨库拷贝 + Redis 缓存失效。Java 单库：玩家行不需要搬，只改 zone_id；若将来分库需要等价的搬迁。
- java: not_applicable — 单库 `xm_java`，玩家行不随归属区移动
- size: L
- robot: none
- hazards: 列序依赖「加列纪律」（新库 CREATE 按声明序、老库 ADD COLUMN 追加到末尾，`INSERT … SELECT *` 列序错位会静默串档，所以按列名对位）；能力标记只证明最近 90s 有新版 go/db 在跑，T-0 时 src/dst 已停机，只能列仍在跑的 zone。

### merge-cross-system-steps — 合服的帮会 / 交易 / 排行榜 / 场景热状态步骤
- mmorpg: `tools/merge_zone/{guild_step,trade_step,scene_hot_state,merge_run}.go`、`audit_resources.go`（公会重名断言）
- client messages: none（合服后帮会 / 寄售 / 排行榜所在区变化对玩家可见）
- tables: none
- depends on: merge-fence-and-remap、帮会、聚宝斋交易、scene_manager 场景热状态
- behavior: 公会重名断言（冲突时一个字节都不写）；`guild.zone_id` 按清单逐条改写 + 失效 `guild:v2:{id}` 缓存 + 复查源区已空；`mmorpg_trade.trade_listing.market_zone` 按清单改写（`seller_zone_at_listing` 不改）+ 复查；`guild_rank:zone:{z}` ZSET 在 maintenance_lock 内重读源榜后 MULTI/EXEC 合并；可选清理 scene_manager 源区热状态（玩家位置键按 zone 判定、zone=0 残留另扫，要求源区已 zone-down）。
- internal: 各服务自己的库与 Redis（guild DB 2、trade 独占库）。Java 需要：随帮会 / 交易 / 排行榜功能落地后，各自提供「按清单迁区」的步骤。
- java: missing — 依赖的帮会 / 交易 / 排行榜在 Java 尚不存在
- size: L
- robot: none
- hazards: 公会名在 mmorpg 是区内唯一，合服前必须断言；trade 的 market_zone 与 seller_zone_at_listing 语义不同，只能改前者。

### merge-preflight-audit-unmerge — 合服预检 / 审计 / 合服后验证 / 撤销
- mmorpg: `tools/merge_zone/{preflight,audit_checks,audit_resources,unmerge,backfill_home_zone,merge_run}.go`
- client messages: none
- tables: none
- depends on: merge-fence-and-remap、db-task-retry-dlq
- behavior: 预检（围栏之下）：源 / 目标库存在且发现玩家表；源区无活节点；`db_task_zone_{src}` 消费组无积压（kafka-consumer-groups 解析或运维显式担保）；重试 / processing / 死信三个队列为空；无玩家锁、无在线会话 `player:session:{pid}`、不在活队伍里；空清单或「源库有行却没有映射」拒绝（除非 -allow-empty-source）。`-mode audit` 只读资源审计（在线、锁、队列、节点、名字冲突、好友 / 好友申请、帮会成员），`-verify-merged` 合服后核对（映射已排空、清单映射 / 行到位、帮会区已排空、排行榜 ZSET、源区热状态已清、围栏已释放），block / warn 分级、非零退出。`-mode unmerge` 按清单把映射改回、删合服提示标记、清 merged_into（pin 模式只改回 home 不动落点）。`-backfill-home-zone` 给缺映射的存量玩家补 `player:zone`（源区已被合走则拒绝）。
- internal: 只读审计 + 清单驱动的反向操作。Java 需要：合服前的「全员离线、存盘队列已排空」检查与合服后验证报告。
- java: missing
- size: L
- robot: none
- hazards: 预检的 Kafka 积压检查依赖外部 CLI 输出解析；撤销只能撤到清单为止，合服后新产生的数据（新帮会、新交易）不在清单里。

### post-merge-notice — 合服后一次性提示 / 强制改名标记
- mmorpg: `tools/merge_zone/post_merge_stamp.go`（写 `player_merge_notice:{pid}`、`player_force_rename:{pid}` 到 login Redis DB 0）、`go/login/internal/logic/clientplayerlogin/entergamelogic.go` consumePostMergeFlags、`proto/login/login.proto` EnterGameResponse 字段 3 / 4、`proto/common/component/player_comp.proto` PlayerMergeStateComp
- client messages: 26 EnterGame（C2S）的应答 EnterGameResponse.post_merge_notice_ts（int64，=合服时刻毫秒）/ force_rename_required（bool）
- tables: none
- depends on: merge-fence-and-remap
- behavior: 合服最后一步给清单玩家打两把键；合服后第一次成功 EnterGame 时 login 读出并填进应答：notice 读到即删（只出现一次），force_rename 不删（要等改名 RPC 成功才删，否则关掉改名 UI 就绕过）；读失败不阻断进游戏。名字已全服唯一，force_rename 正常运营不会置位；旧客户端忽略两个字段照常进游戏。
- internal: login Redis（DB 0）。Java 需要：EnterGame 应答在合服后首登时带上这两个字段（Java 若做合服，用 MySQL 列或 Redis `xm:` 键均可）。
- java: missing — Java 的 EnterGame 应答从不设置这两个字段（只有生成的 proto 类里出现）
- size: S
- robot: none
- hazards: 2026-09-08 之前标记写进 mapping Redis 而 login 读 DB 0，提示从未触发且完全静默；GET 与 DEL 不原子、应答丢失则提示丢失（可接受）；当前 Unity 客户端（`../mmorpg-client/Assets/Scripts`）未读取这两个字段。

### storage-relocate — 冻结式在线搬库（玩家存储落点迁移）
- mmorpg: `tools/merge_zone/{relocate,relocate_run,relocate_manifest,storage_audit,pin_placement,capability_check}.go`、`go/db/internal/kafka/placement_route.go`（冻结延后 / 落库后复核）
- client messages: none
- tables: none
- depends on: placement-routing、db-task-consumer-ordering
- behavior: 不要求玩家离线：R0 校验 S≠T、两库存在、列名对齐、能力标记，清单先落盘；R1 逐人 Lua 原子确认记录与 home 未变且 home zone 无合服围栏后写 `{S}:{v}:frozen:{run}`；R2 等每人每张表的 go/db 排序锁至少一次不存在（超时本批解冻）；R3 每人一个事务删 T 旧冷副本、`INSERT…SELECT` 按列名对位；R4 逐人逐表逐字节比对，不一致解冻；R5 CAS 切换到 `{T}:{v+1}`。`relocate-abort` 解冻回 S；`storage-audit` 只读统计有效落点为 N 的玩家与冷副本；`pin-placement` 在线给 home==N 且无记录者钉 `{N}:1`；`capability-check` 逐 zone 报告能力标记 present / missing / unreadable（exit 0/1/2）。与合服互斥（各自先写自己的标记再查对方）。
- internal: 状态机 relocateEngine 注入 Redis / MySQL 接缝。为 Phase 2「玩家搬进全局库」准备。
- java: not_applicable — Java 单库，无落点概念
- size: L
- robot: none
- hazards: 正确性不依赖 R2 等待——锁过期后才落进 S 的在途写由 go/db 落库后复核看到冻结 / 切换而重投到 T；缓存不删（内容未变、键不带落点）。

### data-consistency-check — 跨区引用一致性巡检
- mmorpg: `tools/data_consistency_check/main.go`
- client messages: none
- tables: none
- depends on: home-zone-registry、帮会、好友、merge-fence-and-remap
- behavior: 周期（每周 cron 或每次合服后）跑的只读巡检，输出 markdown 报告，有 block 级即非零退出：活 zone 集合由 mapping Redis 推断；guild.zone_id 指向已不存在的 zone → block；`guild_rank:zone:{id}` ZSET 指向死 zone → 合服中途崩溃的残留；mapping 里的 player_id 在 user_accounts.simple_players 里找不到 → warn；好友表 friend_player_id 不在 mapping（抽样）→ 「好友不存在」。friend 走独立 DSN（mmorpg_friend），句柄缺失 / 表不在报 warn「NOT CHECKED」（没查与查了干净必须长得不一样）。只报告不修复，不连玩家数据 Redis。
- internal: 只读 MySQL + Redis 扫描。Java 需要：随帮会 / 好友 / 合服落地后的同类巡检（可做成 Spring Boot 命令行任务）。
- java: missing
- size: M
- robot: none
- hazards: 库名会拼进 SQL（不是绑定参数），只能靠形状校验防注入；邮件孤儿检查已删（mail 表不存在）。

### data-stress-verifier — 存盘一致性压测与收敛校验
- mmorpg: `go/db/cmd/data_stress/main.go`、`go/db/cmd/verifier/main.go`、`go/db/internal/stresstest/probe.go`、C++ `cpp/libs/services/scene/player/system/stress_test_probe.{h,cpp}`、`robot/data_stress.go`、`robot/etc/robot.data_stress.yaml`
- client messages: 48 Login、26 EnterGame、17 LeaveGame（robot 模式循环登录 → 进场 → 玩 → 登出）
- tables: none
- depends on: player-save-pipeline、player-data-record（stress_test_probe 字段）
- behavior: 每次写在 `player_database.stress_test_probe` 盖 test_seq（每玩家单调）与 test_sig（两路不同种子的 FNV-1a-64 拼成 128 位，输入 player_id_be8 ‖ msg_type ‖ seq_be8，用于识别撕裂 / 错路由 / 拼接的载荷，不是 MAC）；驱动端把每玩家期望最大 seq 写 `verify:expected:{msg_type}:{player_id}`（robot 模式另写 `verify:enrolled:*`，期望值=成功登出轮数）；verifier 轮询直到截止：I1 MySQL 行 seq == 期望、I2 Redis 缓存 seq == 期望（缓存缺席软通过）、I3 Kafka 无积压（配合消费组 CLI），不一致非零退出并暴露 Prometheus 指标。scene 在 `STRESS_TEST_PROBE=1` 时才盖探针（在脏比对之后）。
- internal: 绕过 scene 直接造 DBTask（隔离测消费者）与全链路（robot）两种。Java 需要：一个「登录 → 改状态 → 离场」循环的 robot 模式 + 对库校验最终状态的 verifier（xm-robot 已有场景框架）。
- java: missing — `xm-robot` 只有 SmokeScenario / MovementScenario，没有数据收敛校验
- size: M
- robot: robot.data_stress.yaml（mode: data-stress）
- hazards: 签名算法须与 C++ 一致（跨语言复刻）；探针字段必须在脏比对之后盖，否则每次都判脏、跳过率失真。

### player-data-debug-tools — 玩家数据导出 / 导入排障工具
- mmorpg: `go/data_service/cmd/debug_fetch/main.go`、`go/data_service/cmd/debug_import/main.go`、`go/data_service/cmd/debugutil/debugutil.go`
- client messages: none
- tables: none
- depends on: home-zone-registry、player-data-record、gm-snapshot-diff
- behavior: debug_fetch 模式 player（data_service 字段图）| zone | server | snapshot | sql（只读 SQL）| player-db（直接读 zone 库 player_database / player_database_1 / player_centre_database 并按 proto 解码成 JSON）；可指定 proto-type 解码二进制、输出到文件；debug_import 默认 dry-run，`--dry-run=false` 才写：Redis 模式逐字段裸 SET 并把 `__version` 重置为 1；player-db 模式每张表一个 READ COMMITTED 事务「DELETE 该玩家旧行 + INSERT 导出行」（RR 下会间隙锁成环 1213）。
- internal: 运维 CLI，直连 Redis / MySQL。Java 需要：按 player_id 导出 / 导入完整玩家记录的运维工具（可用 xm-tools 或 Spring Boot CLI profile）。
- java: missing
- size: M
- robot: none
- hazards: debug_import 不检查在线 / 不碰 owner_epoch（已核对源码），写回绕过归属围栏，玩家在线时导入会被下一次存盘覆盖或覆盖在线进度——Java 实现须先夺权（或要求离线）再写。

### storage-observability-killswitch — 存储层指标与热关停
- mmorpg: `go/db/internal/metrics/metrics.go`（`:9160`）、`go/data_service/internal/metrics/metrics.go`、`go/db/db.go`（killswitch 拦截器、grpcstats、serverbase）、`go/shared/killswitch`、C++ `dirty_save_stats.h` 周期日志
- client messages: none
- tables: none
- depends on: player-save-pipeline
- behavior: go/db：db_task_stage_seconds{stage=op_total|op_handler|cache_write|result_publish, op}、db_task_result_total{op,result}、blob_bytes / blob_row_bytes / blob_guard_total、owner_epoch_guard_total{outcome}、stale_owner_write_rejected_total（压测期应恒 0）、placement_guard_total / placement_store_open_total / placement_open_stores；data_service：kafka_consumer_up（启动前按 0 预注册，「从没起来」也能告警）、kafka_consumer_messages_total、id_segment_allocate_total{biz_tag,outcome}、player_name_*、rollback_*、asset_op_ledger_read_total；player_id 只进日志不进 label。killswitch：etcd 前缀 `/mmorpg/killswitch/` 下写键即可秒级短路某个 gRPC 方法，etcd 不可达 fail-open。
- internal: Prometheus + etcd watch。Java 需要：存储写回结局 / 耗时 / 线程池积压的低基数指标（已有大部分），以及（可选）运维止血开关。
- java: partial — `SceneMetrics` 已有存储线程池与写回结局（released / fenced / failed / rejected）指标（architecture.md §11、PARITY「scene 低基数运行指标」行）；无「陈旧写被拒」类计数（Java 围栏拒绝记为 fenced）、无热关停
- size: S
- robot: robot.stress-*.yaml（压测期抓取）
- hazards: go/db 的 gRPC 面只有 `db.Test`，真正的业务失败全在 Kafka 链路，只能看 db_task_result_total；fc9377336 之后一度只 MustNewServer 不 Start，K8s 探针永远失败（已修）。

## Open questions

1. **Java 是否需要异步落库 / 周期存盘**：Java 当前「离场同步写回 + 30s 租约」在 scene 进程被 kill 时丢失整段在线增量（mmorpg 最多丢 300s）。周期写回是 Java 内部机制，是否引入 Kafka（tech-stack 已列 spring-kafka）还是保持「存储线程池 + 带 epoch 的周期 UPDATE」需要定方案；后者无需 db-task-consumer-ordering / placement-routing 那一整套。
2. **玩家记录形状**：Java 是结构化列（现在只有等级 / 场景 / 坐标），mmorpg 是每组件一列 proto blob。货币 / 背包 / 任务 / 宠物 / 账本落地时，Java 用结构化子表还是 proto blob 列？若用 blob，需要同时引入大字段上限与「加列纪律」。资产与账本同记录同事务（不变量 I3）在 Java 里应由单事务保证。
3. **迁移工具选型**：Flyway / Liquibase 都低于 AGENTS §2 的 2 万 star 门槛，Spring Boot 对二者有自动配置；需要用户裁决是破例采用还是自写极简台账（version / checksum / dirty + `GET_LOCK`）。
4. **`createDatabaseIfNotExist=true`**：xm-login / xm-scene 的 JDBC URL 会在库名配错时静默建新库，正是 mmorpg go/db 用白名单堵的问题；建议改为 fail-closed，但会影响「本地一键起」体验，需确认。
5. **mmorpg 侧存疑（mmorpg 待做候选）**：(a) GM 快照 / 回档 / 回收建在 `player:{id}:*` 字段图上，而权威数据是 `PlayerAllData:{id}` blob，且 RollbackFence 未接线、回档恒 NotImplemented——Java 做回档时应直接以玩家记录 + scene 快照（source=1）为源，不要照搬；(b) player_database_1、player_centre_database、player_debt、rollback_audit、user / user_oauth / user_phone / user_password 在本区域代码里没有业务读写者（死表或仅工具用）；(c) 角色列表（user_accounts.simple_players）只看到 Redis 读写，未发现 MySQL 写回路径；(d) DeletePlayerData / SavePlayerData / BatchRecallItems / CreatePlayerSnapshot 无管理令牌。
6. **合服在 Java 的范围**：Java 名字全服唯一（name_key 唯一索引），合服不会产生重名，force_rename 可永不置位；单库下合服退化为「改 zone_id + 帮会 / 交易 / 排行榜迁区 + 一次性提示」，merge-player-data 与 storage-relocate 可判不适用。是否需要做、何时做，取决于运营是否会开多区。
7. **归属区来源**：Java EnterGame 用会话 zone 而非角色行 zone_id 选场景（login 区域需确认是否有意）；Java 没有对其他服务开放的「查玩家归属区」接口，帮会 / 交易 / 组队移植时需要。
8. **号段 vs 雪花**：mmorpg 已把 player_id 从雪花迁到号段（值域 < 2^55，与存量雪花号不相交）。Java 继续用雪花（≥ 2^55 的大号），两版 player_id 值域不同；客户端只要求 uint64 唯一，但若将来两版数据互导需注意。物品 guid / 帮会 id 在 Java 的发号源待定。
