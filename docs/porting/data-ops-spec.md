# 数据运维（批次 7.2）移植统一规格：流水 / 快照落库、GM 快照差异、回档、批量回收、scene 侧 GM 102–117

> **基线**：mmorpg `26ceb70ca`（`D:\work\mmorpg` 稀疏克隆，与 `contract/SOURCE.properties` 的 `mmorpg.commit` 相同）。本稿用到的目录全部已检出、没有缺失：
> `go/data_service/**`、`go/db/**`、`go/shared/kafkautil/**`、`cpp/libs/modules/{transaction_log,snapshot,audit,currency}/**`、`cpp/libs/services/scene/player/system/`、
> `cpp/nodes/{scene,gate}/`、`cpp/tests/`、`proto/**`、`docs/design/**`、`.github/workflows/**`。
>
> **Java 侧**：HEAD `aa8b5b5`，另有批次 5.2 的未提交改动（xm-player-store / xm-scene / xm-gate / xm-scene-manager / xm-discovery），按「即将落地」处理。
> `PlayerStore.java`、`PlayerMapper.java`、`PlayerStoreAutoConfiguration.java`、`SceneWorld.java`、`SceneNode.java`、`ClientRequestHandler.java`、`ClientDispatcher.java`、
> `NodeTypes.java` 都在未提交改动里，行号按**工作区**计、标「5.2 工作区」，提交后可能漂移，所以同时写出类名 / 方法名。
>
> **路径怎么读**
> - 不带目录的 Go 文件：`rollback_logic.go`、`recall_logic.go`、`snapshot_logic.go`、`data_logic.go`、`asset_op_ledger_logic.go`、`rollback_recall_test.go`
>   在 mmorpg `go/data_service/internal/logic/`；`snapshot_store.go`、`transaction_log_store.go`、`schema.go` 在 `internal/store/`；
>   `txlog_consumer.go`、`snapshot_consumer.go`、`consumer.go`、`start.go` 在 `internal/kafka/`；`guild_divergence.go` 在 `internal/guildcheck/`；
>   `servicecontext.go` 在 `internal/svc/`；`config.go` 在 `internal/config/`；`dataserviceserver.go` 在 `internal/server/`；`error_codes.go` 在 `internal/constants/`；
>   `metrics.go` 在 `internal/metrics/`；`data_service.yaml` 在 `go/data_service/etc/`。
> - C++：`transaction_log_system.{h,cpp}` 在 `cpp/libs/modules/transaction_log/`；`snapshot_system.{h,cpp}` 在 `cpp/libs/modules/snapshot/`；`audit_topic.h` 在
>   `cpp/libs/modules/audit/`；`player_lifecycle.cpp` 在 `cpp/libs/services/scene/player/system/`；`player_rollback_handler.cpp`、`player_gm_guard.h` 在
>   `cpp/nodes/scene/handler/rpc/player/`；`client_message_processor.cpp` 在 `cpp/nodes/gate/handler/rpc/`；`gate_gm_client_messages.h` 在 `cpp/nodes/gate/`；
>   `currency_system.{h,cpp}` 在 `cpp/libs/modules/currency/system/`。
> - proto：`rdt.proto` = mmorpg `proto/common/database/rollback_database_table.proto`；`txlog.proto(mm)` / `snap.proto(mm)` = `proto/common/rollback/{transaction_log,player_snapshot}.proto`；
>   `data_service.proto` = `proto/data_service/data_service.proto`；`player_rollback.proto` = `proto/scene/player_rollback.proto`（Java 同步副本
>   `xm-proto/src/main/proto/proto/scene/player_rollback.proto` 多第 2–4 行 java option，**Java 行号 = mmorpg 行号 + 3**）；`currency_comp.proto` = `proto/common/component/`。
> - Java 侧：`txlog.proto(xm)` / `snap.proto(xm)` = `xm-audit/src/main/proto/xm/audit/{transaction_log,player_snapshot}.proto`；`player_state.proto` =
>   `xm-player-store/src/main/proto/xm/storage/player_state.proto`；`xm-data-schema.sql` = `xm-data/src/main/resources/db/`；`xm-player-schema.sql` =
>   `xm-player-store/src/main/resources/db/`；`guild_internal.proto(xm)` = `xm-api/src/main/proto/xm/api/`。以 `xm-`、`docs/`、`tools/` 开头的路径与 `PARITY.md` 都在
>   `D:\work\xuanming-server-mmo` 下。`message_id.txt` 两版逐字节相同，**号 N 在第 N+1 行**。
> - 设计稿 `single_player_rollback.md`、`zone_data_rollback.md`（mmorpg `docs/design/`）只作背景，与代码冲突时以代码为准。
>
> **本稿的来历**：由三份分区稿合并而成。一份讲流水 / 快照的数据面，一份讲 GM 快照、回档、回收与 102–117 的客户端可见面，一份讲 Java 落地设计。
> 三稿互相矛盾、或与代码不符的地方都回到代码重新核对过，结论与勘误列在 §9.4。本稿只读代码，没有改任何源文件。
>
> **契约影响**：7.2 **不改任何客户端契约**，不需要跑 ContractSync。客户端能感知到的只有三件已有行为：GM 持有归属期间进游戏回 2005、被运维踢下线收到 23 `{2017}`、
> gate 丢弃 96–117（§6.6）。
>
> **与 pbmysql 的关系**：阶段 4 起新增的「形状由 proto 消息决定」的表一律接 xm-pbmysql（`docs/porting/roadmap.md:50-53`）。7.2 新增的运维作业表属于这一类，
> 对应基线 `rdt.proto` 里由 proto2mysql 建的 `rollback_audit_log`。已有的 `transaction_log` / `player_snapshot` 是 MyBatis 手写表，不迁移，只按 db-migrations 加列加索引（§7.3）。

---

## 0 概览与范围

### 0.1 盘点 id 与本批产出

| 盘点 id | 基线位置 | 基线在生产上能不能用 | Java 现状（7.2 前） | 7.2 产出 |
|---|---|---|---|---|
| txlog-ingest（`inventory/data.md:192-202`） | `txlog_consumer.go`、`transaction_log_store.go`、`recall_logic.go` QueryTransactionLog | 落库能用；查询无鉴权、`COUNT(*)` + OFFSET | 落库已对齐（2.3a，`PARITY.md:67`）；查询只能按玩家 | 查询扩展（类型 / 物品 / 币种 / uuid / 游标）、3 条索引、兜底日志回灌（7.2a） |
| player-snapshot-ingest（`:204-214`） | `snapshot_consumer.go`、`snapshot_store.go` | 能落库，但 **scene 快照没有任何读路径** | 落库已对齐（2.3b，`PARITY.md:68`）；只有元数据查询 | 读路径、新触发原因、按原因分的保留期（7.2a） |
| gm-snapshot-diff（`:216-226`） | `snapshot_logic.go`、`recall_logic.go` CreateEventSnapshot | **对真实玩家无效**（读的不是权威数据）；无鉴权 | 缺 | 手工快照、详情、结构化差异（7.2a）；整区维护前快照（7.2b） |
| gm-rollback（`:228-238`） | `rollback_logic.go`、`guild_divergence.go`、`servicecontext.go` | **一律回 16**（栅栏没接线） | 缺；且 4.5 承诺「帮会闸接入前不得提供回档入口」（`guild-economy-spec.md:1155`） | 单人 / 多人 / 整区 / 全服回档，三道资产分歧检查（7.2b） |
| gm-batch-recall（`:240-250`） | `recall_logic.go` BatchRecallItems | 只有 dry-run 能用；金币回收不了；无鉴权 | 缺 | dry-run（7.2a）、真正执行（7.2c） |
| gm-rollback-rpcs（`inventory/modules.md:194-204`） | `player_rollback_handler.cpp`（12 条 `TODO(P1-B)` 桩） | 设计上不可达 | 已对齐（等价）：gate 拒、scene 无桩 | 回归测试钉住（7.2a）；欠款 / 追溯 / 精确回收的语义放到运维面（追溯 7.2a，其余 7.2c） |

### 0.2 结论（一页纸）

1. **全部落在 xm-data 的运维面**（管理端口 18106，令牌 + 操作人，`architecture.md:219-222`），不新增进程，不在 scene 注册 102–117 的处理器，不改 gate。
   xm-data 本来就是基线 data_service 在 Java 版的对应物，已拥有 `transaction_log` / `player_snapshot` 两张表。
2. **基线的 GM 快照 / 回档 / 回收在生产上都不可用**（§3.1、§4.1、§5.1），所以 Java 对齐的是基线的**安全门与设计意图**，不是它眼下的行为：
   鉴权、先写意图审计再动手、离线栅栏、整份计划过闸才写第一个玩家、帮会资产闸、写后复查、结果审计不随调用方取消。
3. **离线栅栏 = 归属夺权**：xm-data 以普通写者的身份调 `PlayerStore.claimOwnership`（`owner_epoch` 加一、持有、每 10 s 续约），
   持有期间用同一套 epoch 围栏 SQL，在**一个 MySQL 事务**里写入恢复后的状态、回档前安全快照、资产流水和作业明细；写后复查与结果审计之后才释放。
   在线玩家缺省拒绝；运维显式要求时走现成的顶号通路把人踢下线再夺权（§4.2）。
4. **回档素材就是 2.3b 起已在落库的 scene 快照**（`player_state` 原样字节 + player 行可变列）。xm-data 自己拍的快照用同一张表、同一种格式、同一个枚举（§3.2），
   所以基线「source=0 / source=1 两种格式不兼容」的问题在 Java 里不存在。
5. **资产分歧检查三道**：帮会检查（逐条对齐基线，调 4.5 已交付的 `GuildInternalService.listAppliedAssetOpsSince`）；账本差集
   （Java 独有，不等待，自动覆盖交易流）；回收逆转（Java 独有）。都默认拒绝，帮会 / 账本的分歧可以带原因放行（§4.6）。
6. **两条硬约束**：
   - xm-data 发的流水号 / 快照号 / 作业号必须取自 `NodeTypes.SCENE_GUID` 全服租约池（§2.3）。另开租约类型会与 scene 撞号，被主键 ODKU 静默吞掉一行。
   - 回档必须把「资产组」（货币、背包、宝宝、资产通道账本）作为一个整体恢复（§4.5），拆开会破坏「资产与账本同记录、同一次落盘」的不变量（`player_state.proto:32-34`）。
7. **批量回收真正执行**（基线非 dry-run 一律失败）：只认获得方流水，金币也能回收，同一条源流水至多被回收一次，对不完整的集合不动手（§5）。
8. **总开关 `xm.data.ops.enabled` 缺省 false**：关闭时所有会改玩家数据的写接口回 503，只读接口照常。

### 0.3 拆批与依赖

| 子批 | 内容 | 前置 |
|---|---|---|
| **7.2a** 只读与无栅栏 | 流水查询扩展 + 3 条索引 + 物品追溯（104）；快照新列与新原因值；xm-data 占 `SCENE_GUID` 租约；手工快照（96 / 103 / 105）；快照详情 / 列表扩展（97 / 115）；结构化差异（98 / 116）；回收 dry-run（108 的基线可用面）；快照保留期按原因分；兜底日志回灌工具；运维作业表（pbmysql）；102–117 回归测试 | 无，可先于 5.2 落地 |
| **7.2b** 栅栏与回档 | 作业框架（异步、单飞、心跳、清扫、幂等）；`AdminOwnership`（夺权 / 踢线 / 续约 / 位置墓碑 / 释放）；回档（99 / 100 / 101 / 112）；帮会检查（xm-data 引入 Dubbo 消费端）；账本差集；整区维护前快照；`idx_player_zone` 迁移 | 5.2 落地（依赖 `selectOwnerForUpdate`、交出与夺权的互斥）；7.2a |
| **7.2c** 离线编辑 | `PersistedStateEditor`；回收执行（`recall_source` 去重、落库完整性闸）；回档的回收逆转检查；欠款（102 / 106 / 107 / 109 / 113）；精确回收（110） | 7.2b |

每个子批各自提交、推送、登记 PARITY（AGENTS.md §5）。

---

## 1 基线数据通路（流水 / 快照）

### 1.1 全景

```
C++ scene ──TransactionLogSystem──► transaction_log_topic_g<N>（6 分区）──┐
          ──SnapshotSystem────────► player_snapshot_topic_g<N>（3 分区）──┤
                                                                         ▼
                        go/data_service：两条 kafka-go 消费者（supervisor 守护）
                                                                         ▼
     全局库：transaction_log / player_snapshot / rollback_audit_log（表结构由 proto2mysql 从 rdt.proto 生成）
                                                                         ▼
        data_service.proto 运维 gRPC：QueryTransactionLog / *Snapshot* / Rollback* / BatchRecallItems
```

- **go/db 不参与流水和快照**，只留下一处残留：`player_snapshot` 的大字段闸按表覆盖（`go/db/internal/config/config.go:170`、`go/db/etc/db.yaml:104`），这张表早已迁到全局库（`rdt.proto:48-53`）。
- **scene 侧 GM 102–117 不在数据通路上**：全是空桩（§6.2），gate 也不放行。

### 1.2 topic 契约

| 项 | 资产流水 | 玩家快照 | 出处 |
|---|---|---|---|
| 基名 / 有效名 | `transaction_log_topic` → `_g<N>` | `player_snapshot_topic` → `_g<N>` | `transaction_log_system.h:20`；`snapshot_system.h:17`；`audit_topic.h:144-173`（0 归一为 1，第一代也带后缀）；`config.go:245-295` |
| 分区 | 6 | 3 | `data_service.yaml:207-216` |
| 保留 | 30 天；`retention.ms` 只在创建时设，已存在的 topic 不校正；不设 `retention.bytes` | 同左 | `data_service.yaml:217`；`go/shared/kafkautil/topic_init.go:66-83` |
| 分区数契约 | 消费方 `EnsureTopics` 核对，另建一个 compact 标记 topic；生产方不核对 | 同左 | `topic_init.go:93-120`；`start.go:111-116` |
| 键 | `from_player`，为 0 时用 `to_player` | `player_id` | `transaction_log_system.cpp:76-80`；`snapshot_system.cpp:99` |
| 消费组 / 起点 | `data_service-transaction-log`，FirstOffset，同步提交 | `data_service-player-snapshot`，同左 | `data_service.yaml:213,216`；`consumer.go:125-138` |

### 1.3 消息格式与单位

- **`TransactionLogEntry`**（`txlog.proto(mm):83-117`）：`tx_id`、`timestamp`（**Unix 秒**）、`tx_type`（`TransactionType` 1–26，`:20-79`；其中
  16 TX_ROLLBACK_RESTORE、17 TX_CLAWBACK、19 TX_BATCH_RECALL **有枚举值、没有写入方**）、from / to、item_uuid、config、quantity、currency_type、`currency_delta`、
  前后余额、`correlation_id`、`extra`（JSON）、`zone_id`（捕获时刻，消费者不许回查，`:112-116`）。`TransactionLogBatch`（`:120-123`）全仓没有使用方。
- **`PlayerSnapshotEntry`**（`snap.proto(mm):18-41`）：`snapshot_id`、`player_id`、`snapshot_time`（**秒**）、`trigger`、`player_database_blob`、
  `player_database_1_blob`、`schema_version="v1"`、`total_bytes`、`zone_id`。`trigger` 取 `SnapshotTrigger`（`:5-14`）：1 LOGIN、2 LOGOUT、3 PERIODIC、
  4 PRE_TRADE、5 PRE_MAINTENANCE、6 GM_MANUAL。
- **两套快照枚举共用一列**：data_service 自己的 `SnapshotType`（`data_service.proto:172-178`）是 0 MANUAL、1 PERIODIC、2 PRE_MAINTENANCE、3 PRE_ROLLBACK、
  4 LOGIN，`recall_logic.go:340` 还写入一个枚举外的 5（EVENT）。两套值同列，只能靠 `source` 列区分（`snapshot_store.go:16-31`）。

### 1.4 生产者

- **流水**：号取自 txlog 号段，没号就 fail-closed、整条不发（`transaction_log_system.cpp:26-34`、`:49-60`）；发送失败只记一行 ERROR（`:83-88`）。
  入口在 `bag_service.cpp` 8 处、`currency_system.cpp` 3 处。
- **快照**：号取自 snapshot 号段，没号就跳过（`snapshot_system.cpp:55-67`）；`total_bytes` 要序列化两次才算得出（`:84-97`）。调用点只有两个：
  登出**在存盘之前**拍（`player_lifecycle.cpp:1988`），登录拍一次（`:2425`）。PERIODIC / PRE_TRADE / PRE_MAINTENANCE / GM_MANUAL 都没有调用方。

### 1.5 消费者

| 项 | txlog（`txlog_consumer.go`） | snapshot（`snapshot_consumer.go`） |
|---|---|---|
| 批 | 攒 200 条或 200 ms，一条多行 `INSERT IGNORE`（`:216-232`；`transaction_log_store.go:94-131`） | 不攒批，逐条落、逐条提交（`:31-37`、`:73-114`） |
| 幂等 | 主键 `tx_id`，重复行被 IGNORE | 自增主键 + `snapshot_guid`：MySQL 上靠存储型生成列 `snapshot_guid_nz` 上的唯一键做 ODKU `id=LAST_INSERT_ID(id)`（`snapshot_store.go:119`；`schema.go:605-607,692`）；唯一键不在（含 TiDB）就退回 `INSERT…SELECT…WHERE NOT EXISTS`，靠 1213 有界重试吸收死锁（`:128-132`、`:148`、`:369`） |
| 坏消息 | 解不出 → `decode_error`；`tx_id=0` → `invalid`；只提交、不入库（`:327-341`） | 解不出、id=0、player=0 → 跳过；超过 16 MiB−1 → `oversize`（`:24`、`:119-139`） |
| 落库内容 | 逐字段；`extra` 是 MEDIUMTEXT，不截断（`:356`；`rdt.proto:41`） | 整条原始字节进 `data`，`source=1`、`operator="scene-node"`、`reason="cpp:"+trigger`（`:141-155`） |
| 库故障 | 重试 5 次、间隔 1 s，用尽就停、不提交位点（`:363-397`）；60 s 后 supervisor 重建 reader（`start.go:38`、`:156-175`） | 同左 |
| 关停 | 独立的 5 s ctx 尽力落掉手里这批（`:411-426`）；先等消费者退出再关连接池（`start.go:65-88`） | 直接退出 |
| Kafka 不可达 | 后台每 30 s 重试，不挡 gRPC 启动（`go/data_service/data_service.go:55,96`） | 同左 |
| 部署 | data_service 单副本（replicas=1 + Recreate，`schema.go:257`） | 同左 |

### 1.6 表（全局库，结构全部来自 `rdt.proto`）

- **`transaction_log`**（`:11-46`）：主键 `tx_id`；列 `timestamp_sec`、`tx_type`、`extra MEDIUMTEXT`、`zone_id`；四条 `(维度, timestamp_sec)` 联合索引：
  from、to、`item_config_id`、`tx_type`（`:19`）。**`item_uuid` 和 `currency_type` 上没有索引。**
- **`player_snapshot`**（`:54-89`）：自增 `id`；`snapshot_type`、`created_at`（秒）、`reason`、`operator`、`data`、`snapshot_guid`、`source`；
  索引 `player_id`、`snapshot_guid`、`(zone_id, created_at)`（`:68`），另加上面的生成列唯一键。
- **`rollback_audit_log`**（`:94-118`）：`rollback_type` 1 单人 / 2 整区 / 3 全服，回收另用 4（`recall_logic.go:144`）；`orphans_cleaned` 恒 0。
- **死表**：`player_debt`（`:124-140`）、`rollback_audit`（`:145-164`），全仓没有读写者（`inventory/data.md:237`）。

### 1.7 查询目录

| store 函数 | 语义 | 调用方 / 鉴权 |
|---|---|---|
| `QueryLog`（`transaction_log_store.go:134-215`） | 玩家条件 `from=? OR to=?`；时间是秒、**闭区间**；`tx_type IN`；`item_config_id` / `currency_type` **只在 > 0 时生效**（`:165-172`）；先 `COUNT(*)`，再 `ORDER BY timestamp_sec DESC LIMIT ? OFFSET ?`；缺省 100、上限 10000 | `QueryTransactionLog`、`BatchRecallItems`，**都不要求 x-admin-token**（`dataserviceserver.go:501`、`:537`） |
| `QueryByItemUUID`（`:217-246`） | 按 uuid 升序；**没有索引，也没有调用方** | — |
| `GetSnapshotByID` / `GetLatestSnapshotBefore`（`snapshot_store.go:533-560`） | **只看 source=0**；后者 `created_at <= ? ORDER BY created_at DESC LIMIT 1`（同秒并列不确定） | GM diff 与回档 |
| `ListSnapshotsMeta`（`:583-621`） | source=0，新的在前，缺省 20 条，**没有上限** | `ListPlayerSnapshots` |
| `GetSnapshotPlayerTimesByZone`（`:645-674`） | `(zone_id, created_at)`，source=0，每人取 `MAX(created_at)` | `RollbackZone` 计划（`rollback_logic.go:924-925`） |
| `ListSceneSnapshotsByPlayer`（`:686-711`） | source=1 元数据 | **未接 RPC**：scene 快照在落库，但没有任何读路径 |
| `InsertAuditLog`（`:714-725`） | 写 `rollback_audit_log` | 回档、回收 |
| `DeleteOldSnapshots`（`:727-736`） | 不分 source、不分类型，一律按时间删 | **没有生产调用方** |

### 1.8 保留期

topic 保留 30 天；库内**没有**清理。设计稿曾打算「保留 N 天登录快照 + 每日周期快照」（`single_player_rollback.md:107-109`），没有落地。

### 1.9 基线在数据面上的缺陷（7.2 设计必须知道）

- **B1 scene 快照落了库却用不上**：GM 的 Create / List / Diff / Rollback 只认 source=0，也就是 data_service 自己那套 `player:{id}:*` 字段图；
  scene 真正存盘写的是 `PlayerAllData:{id}` 整份 blob（`asset_op_ledger_logic.go:25-30`），对真实玩家要么 NotFound、要么拿到非权威数据。
- **B2 批量回收永远选不中金币**：金币是币种 0（`cpp/libs/modules/currency/constants/currency.h:8`），而入参校验要求物品和币种不能同时为 0（`recall_logic.go:75-77`），
  `QueryLog` 又只在币种 > 0 时才过滤；dry-run 的「数量」只算正的 delta，扣减行记 0（`:162-184`）。
- **B3 `INSERT IGNORE`** 在严格模式下把数据错误降成警告；`extra` 不限长。
- **B4 `QueryLog` 写法重**：OR 跨两列走不了同一条索引；热表上每次 `COUNT(*)`；OFFSET 深分页；按币种回收没有索引。
- **B5 快照种类混在一列**：两套枚举同列；PRE_ROLLBACK 安全快照也会被 `GetLatestSnapshotBefore` 选成「T 时刻之前最近的一份」回档源。
- **B6 回档与回收的执行路径都 fail-closed、实际不可用**：`RollbackFence` 在生产中从未赋值，三个回档 RPC 回 `ErrCodeNotImplemented`（`rollback_logic.go:60-94`；
  `servicecontext.go:72-82`）；回收非 dry-run 同样回 16（`recall_logic.go:186-247`）。

---

## 2 Java 现状与差距

### 2.1 现状（批次 2.3a / 2.3b 已完成，`architecture.md:185-222`）

| 项 | Java（已有） | 与基线的差异 |
|---|---|---|
| 流水格式 | `xm.audit.TransactionLogRecord`（`txlog.proto(xm):50-75`），时间**毫秒**；与基线同义的原因沿用数值，Java 独有的从 1001 起（`:14-41`）；多一个 `kind`（`:44-48`，币种 0 是金币，单看币种分不出货币 / 物品） | 不共享基线的 proto 与 topic |
| 流水 extra | 至多 1024 字符（`xm-data-schema.sql:20`；超出由 `KafkaAssetAudit` 换成截断标记） | 基线不限长 |
| 快照格式 | `PlayerSnapshotRecord`（`snap.proto(xm):23-41`）= player 行可变列（等级、场景、坐标）+ `owner_epoch` + `player_state` 原样字节；`cause` 只有 LOGIN=1、LOGOUT=2，数值同基线 trigger（`:15-21`） | 一种格式；没有 operator / note |
| topic | `xm-transaction-log-g<N>` 6 分区、`xm-player-snapshot-g<N>` 3 分区，保留 30 天且 `retention.bytes=-1`；两端都核对分区数，消费方还把配置校正到规格（`architecture.md:195-197`） | 基线生产方不核对、保留期只在创建时设 |
| 号 | 雪花，worker 取全服租约 `SCENE_GUID`（`architecture.md:726-729`；`SceneNode.acquireSceneGuids`） | 基线号段 |
| 生产失败 | 每条完整写兜底日志 `xm.audit.fallback`（`AuditFallbackLog.java:28-38`）；未核对 / 发不出号 / 队列满的行 `tx_id=0`（`AuditPipeline.java:174-191`） | 基线只记 ERROR |
| 快照拍摄点 | 进场后用内存状态拍 LOGIN（5.2 工作区 `SceneWorld.java:750`）；离场写回拍 LOGOUT（`:1672`、`:1710`）；失去归属不拍；5.2 交出不拍（`scene-handoff-spec.md` D8） | 同基线时机 |
| 快照上限 | 超过 `xm.scene.snapshot-max-bytes`（缺省 1000000）丢弃并计 `oversize`（`architecture.md:205-207`） | 基线消费端上限 16 MiB−1 |
| 消费 | 每 topic 一线程一 KafkaConsumer，`earliest`、关自动提交（`DataNode.java:183-184`）；一次拉取一个事务、成功才 `commitSync`；可恢复故障暂停分区、退避 1 s→30 s **不限次数**；数据错误逐行隔离写毒丸日志（`architecture.md:212-217`） | 基线重试 5 次后停、60 s 后恢复 |
| 幂等 | 主键即号，`ON DUPLICATE KEY UPDATE x = x`（`TransactionLogMapper.java:30-45`；`PlayerSnapshotMapper.java:20-31`）；流水无符号列按无符号绑定 | 不需要生成列唯一键 |
| 表 | `transaction_log`（`xm-data-schema.sql:5-27`），索引 from、to、time（`:24-26`）；`player_snapshot`（`:31-48`），索引 `(player_id, time_ms)`、`(time_ms)`（`:46-47`） | 没有物品 / 币种 / uuid 索引 |
| 查询 | `GET /admin/transaction-log`、`GET /admin/player-snapshots`：令牌 + 操作人；半开毫秒窗口；升序；上限 1000；快照只回元数据；流水的玩家条件分两条索引查询再归并（`AuditQueryController.java:27-28`、`:45-78`） | 只能按玩家查 |
| 保留期 | `xm.data.retention.{transaction-log,player-snapshot}`，缺省 0 = 永久；设了就每小时按批 DELETE（`DataProperties.java:80-92`；`DataNode.java:191-247`；`PlayerSnapshotMapper.java:44-45` 不分原因） | 基线没有清理 |
| 运维面 | 令牌 `XM_ADMIN_TOKEN`（常数时间比较，未配置 503）+ 操作人 `X-Xm-Operator`（1–64 字符）；每次调用写 `xm.audit.admin` 并计指标，op 标签有界（`AdminAuthFilter.java:25-95`） | 基线只有三个 Rollback* 要令牌 |
| 玩家数据 | `player` 行 + `player_state`（八段：facing / currency / attribute / bag / mission / vitals / pets / asset_ledger，`player_state.proto:16-35`），同事务、同 epoch 围栏写入 | — |
| 归属协议 | 夺权只在「已释放或租约过期」时成功（5.2 工作区 `PlayerMapper.java:53-58`、`PlayerStore.claimOwnership:232`）；在线写 `saveStateHeld:268`；最终写回 `saveStateAndRelease:252`；释放 `releaseOwnership:300`；批量续约 `renewOwnerLeases:311`；加锁读 `selectOwnerForUpdate`（`PlayerMapper.java:106-111`）；顶号让出走 `xm:owner-takeover`（`architecture.md:649-667`） | — |
| 欠款 | `CurrencyState.debts`（`player_state.proto:131-149`）与 scene `Wallet.Debt` 加币抵扣已实现，**没有写入口**（`PARITY.md:84`；`player_state.proto:131-133` 注明「GM 挂欠款随路线图 7.2」） | 两版都休眠 |
| 帮会闸数据面 | `GuildInternalService.listAppliedAssetOpsSince`（Dubbo group `guild`）；结果码在应答里，0 不是成功；`RETENTION_REJECTED` 带 `cutoff_ms`（`GuildInternalService.java:12-26`；`guild_internal.proto(xm):45-69`） | — |
| 已落盘账本只读视图 | `PersistedAssetLedger`：加载即校验，坏账本不当空账本（`xm-player-store/.../asset/PersistedAssetLedger.java:16-21`） | — |
| xm-data 依赖 | xm-audit、xm-discovery、xm-api、xm-gateway-store、MyBatis、Druid（`xm-data/pom.xml`）。**没有** xm-player-store、xm-pbmysql、Dubbo | — |

### 2.2 差距速览

| # | 缺口 | 给谁用 | 子批 |
|---|---|---|---|
| G1 | 流水筛选查询（kind / 物品 / 币种 / uuid / 原因 / 窗口 / 游标），截断用 limit+1 判 | 查询、回收、追溯 | 7.2a |
| G2 | `transaction_log` 加 3 条索引 | G1 | 7.2a |
| G3 | 快照读路径：按号取本体、按时刻取最近一份（原因白名单）、按玩家列出 | 差异、回档 | 7.2a |
| G4 | xm-data 同步直写快照：号源、operator / note 列、新原因值、内容时刻语义 | 手工快照、回档前安全快照 | 7.2a |
| G5 | xm-data 解码 `PlayerState`（依赖 xm-player-store；xm-player-store 不依赖 xm-data，没有环） | 差异、回档 | 7.2a |
| G6 | 保留期按原因区分，约束流水保留 ≥ 快照保留 | 回档、回收 | 7.2a |
| G7 | 落库的运维审计（STARTED 写不进就零变更） | 所有写操作 | 7.2a 建表，7.2b 用满 |
| G8 | 落库完整性闸：查整个消费组的位点 | 回收执行 | 7.2c |
| G9 | 兜底日志回灌工具（`PARITY.md:67` 的「Java 待做」） | txlog-ingest | 7.2a |
| G10 | 离线栅栏（归属夺权）与作业框架 | 回档、回收、欠款 | 7.2b |
| G11 | 帮会检查的 Dubbo 消费端 | 回档 | 7.2b |
| G12 | 周期快照 | 回档的时间精度 | 待决（Q8） |

### 2.3 号源（硬约束）

雪花的位布局是 `[符号 1][毫秒 41][worker 10][序号 12]`，**不含节点类型位**（`xm-common/.../id/Snowflake.java:6`）。所以：

- xm-data 写入的 `snapshot_id`（手工 / 安全快照）、`tx_id`（回档 / 回收流水）、`job_id`，**必须**从与 scene 同一个全服租约池 `NodeTypes.SCENE_GUID`
  （作用域 0，`NodeTypes.java:16-20`）占 worker，用 `LeaseGatedSnowflake` 发号；停服时最后交还。
- 另开一个租约类型（例如一稿提议的 `NodeTypes.DATA`）时，它的 worker 号会与 scene 重叠：同一毫秒同一序号发出相同的号，`transaction_log` / `player_snapshot`
  按主键 ODKU 幂等，后到的一行被**静默吞掉**。这与 `architecture.md:726-729` 不允许各 zone 自己发号是同一个道理。team / guild 能各开租约，是因为它们的号域不与 scene 共用。
- 租约无效时不发号，写接口回 503 `id_unavailable`，不自造号、不用自增。`snapshot_id` 按有符号绑定（`PlayerSnapshotMapper.java:26`），雪花号 < 2^63，这个范围必须守住。
- `SCENE_GUID` 这个名字从此不只给 scene 用，但改名等于换键空间、只能停服切换（`NodeTypes.java:5-6`），所以**不改名**，只改注释（Q12）。

### 2.4 兜底日志回灌（G9）

- 行格式见 `AuditFallbackLog.java:28-38`（键值对，`extra=` 在行尾、取到行末）。`tx_id≠0` 的行原号回灌，按主键 ODKU 天然幂等。
- `tx_id=0` 的行（unverified / no_id / queue_full）回灌时必须发新号：工具以 CLI 形式运行（同 `PasswordAdmin` 的 PropertiesLauncher 写法），自己占一个 `SCENE_GUID` worker。
  **同一文件重复回灌会重复入库**，所以工具在表 `audit_replay_line`（主键 `file_sha256, line_no`，pbmysql）里登记每一行，已登记的跳过。
- 快照的兜底行只有元数据（`AuditFallbackLog.java:18-26`），**不能回灌**，这是有意的。

---

## 3 GM 快照与差异

### 3.1 基线

- **Create**（`snapshot_logic.go:37-102`）：player 为 0 回 14；`LoadPlayerData` 读 `player:{id}:*` 字段图（`data_logic.go:57-58`），为空回 4 NotFound；
  序列化成 JSON `{fields: map}`，zone 用**当前** home_zone，`created_at` 取秒，落 `source=0`。对真实玩家恒 NotFound（B1）。
- **List**（`:128-158`）：只列 source=0，倒序；`before_time=0` 不过滤；limit 0 取 20，**没有上限**；`SnapshotInfo.fields`（字段 9）从未填写（`dataserviceserver.go:316-325`）。
- **Diff**（`:183-268`）：`resolveSnapshot`（`:271-289`）按 id 查，属于别的玩家当作找不到；没给 id 时取 `target_time` 之前（含）最近一份；与当前字段图逐个比字节，
  产出 `only_in_snapshot` / `only_in_current`。
- **CreateEventSnapshot**（`recall_logic.go:339-405`）：事件类型映射成描述性 reason，`snapshot_type=5`，**不在** `SnapshotType` 枚举里；全仓无调用方。
- **scene 侧 116 的 `SnapshotDiff`** 是语义差异：逐币种 delta、逐物品 uuid 的 `quantity_diff`，带 `has_transfer_record` / `restorable`（`player_rollback.proto:58-82`），
  对应设计「回档 = 补偿异常损失，有正常转移记录的不恢复」（`single_player_rollback.md:138-172`）。基线没有实现。
- **鉴权**：Create / List / Diff / EventSnapshot 都**不要**令牌（`dataserviceserver.go:281-362`、`:578-596`）。

### 3.2 Java 快照模型：一张表、一种格式、一个枚举

**内容**：所有来源都是「player 行可变列 + `player_state` 原样字节」。xm-data 直写与 scene 经 Kafka 的行完全同格式，读路径不分来源。

**新增列**（与 §7.3 同一条迁移；`DEFAULT ''` 让 Kafka 落库路径的 INSERT 不用改）：

```sql
ALTER TABLE player_snapshot
    ADD COLUMN operator VARCHAR(64)  NOT NULL DEFAULT '' COMMENT 'xm-data 直写的操作人；scene 经 Kafka 的为空',
    ADD COLUMN note     VARCHAR(256) NOT NULL DEFAULT '' COMMENT '直写的备注（原因 / 事件 / 作业号）';
```

来源靠 `operator` 是否为空即可区分，不加基线那样的 `source` 列（不需要按来源分读路径）。

**`SnapshotCause` 追加的值**（`snap.proto(xm)`，只增不改）：

| 值 | 数值 | 来源 | 说明 |
|---|---|---|---|
| `SNAPSHOT_PERIODIC` | 3 | 同基线 `SnapshotTrigger` | 预留，Q8 |
| `SNAPSHOT_PRE_TRADE` | 4 | 同上 | 预留给 4.8 |
| `SNAPSHOT_PRE_MAINTENANCE` | 5 | 同上 | 整区维护前快照（§3.4） |
| `SNAPSHOT_GM_MANUAL` | 6 | 同上 | 手工快照；基线 105 的事件快照并入这里，事件写进 `note`（不另设 EVENT 值） |
| `SNAPSHOT_PRE_ROLLBACK` | 1001 | Java 独有（沿用「Java 独有从 1001 起」） | 回档前安全快照；基线把它放在另一枚举的 3，与 PERIODIC 撞值 |
| `SNAPSHOT_PRE_GM_EDIT` | 1002 | Java 独有 | 回收 / 精确回收 / 欠款等离线编辑前的安全快照 |

消费端不用改：不认识的原因按数值原样落库（`PlayerSnapshotDecoder.java:11-13`）。

**`time_ms` 的含义 = 内容所代表的时刻**（Java 独有的明确定义，修正一稿「拍摄时刻」的说法）：

- scene 的 LOGIN / LOGOUT：内容就是拍摄那一刻的内存 / 写回状态，`time_ms` = 拍摄时刻（现状不变）。
- xm-data 直写：内容取**已落盘**的状态，`time_ms` = `player_state.updated_at`（没有这一行时取 `player.updated_at`），拍摄时刻记在 `ingested_at`。
  理由：在线玩家已落盘状态最多落后一个存盘周期（缺省 300 s，`architecture.md:670`）。若按拍摄时刻记，这份快照被选作回档源时，帮会检查的起点
  `since = time_ms − 余量`（余量缺省也是 300 s）会晚于内容的真实时刻，漏掉这段时间里终结的帮会操作，即回档复制资产（fail-open）。
- `owner_epoch` 取 `player_state.saved_epoch`（写入这份内容的 epoch）；`zone_id` 取 `player.zone_id`（直写没有 scene，用归属区）。

### 3.3 读路径（`PlayerSnapshotMapper` 新方法）

| 方法 | SQL 形状 | 索引 |
|---|---|---|
| `findById(id)` | 含 `player_state` 本体 | 主键 |
| `findLatestAtOrBefore(player, t, causes)` | `WHERE player_id=? AND time_ms<=? AND cause IN (…) ORDER BY time_ms DESC, snapshot_id DESC LIMIT 1` | `idx_snapshot_player`（InnoDB 二级索引自带主键，可反向扫） |
| `listByPlayer(player, since, until, causes, order, limit)` | 元数据 + `operator` / `note`，可正序可倒序 | 同上 |

**按时刻选源的原因白名单**：缺省 LOGIN / LOGOUT / PERIODIC / PRE_MAINTENANCE / GM_MANUAL，**不含 PRE_ROLLBACK / PRE_GM_EDIT**。
理由：安全快照记录的是「被覆盖之前」的状态，覆盖之后那一刻的真实状态已经变了，拿它当「T 时刻之前最近的状态」是错的（修正 B5；也修正一稿「任何来源都可以」的说法）。
安全快照只能**显式按 `snapshotId`** 选，用来撤销上一次编辑（§4.11）。

**时间可比性**：`time_ms` 来自各 scene 节点 / xm-data 的墙钟，跨节点比较依赖 NTP（同 `architecture.md:666` 的租约前提）。同毫秒并列时取 `snapshot_id` 大的（雪花时间序）。

### 3.4 手工快照（对应 96 / 103 / 105）与整区维护前快照

- **`POST /admin/player-snapshots`** `{player, cause: GM_MANUAL | PRE_MAINTENANCE, note}`，必带 `Idempotency-Key` 与 reason（§7.4）。同步执行，**不夺权、不踢人**：
  快照不改玩家数据，读已提交的状态就够了（与基线「冻结中也允许拍快照」同义，`player_rollback_handler.cpp:150-159`）。
  - 一个事务：读 player 行 + `player_state` 原字节 → 插 `player_snapshot`（§3.2 语义）→ 写作业行（kind `SNAPSHOT`，终态）。
  - 应答带 `snapshotId`、`timeMs`（= 内容时刻）、`ingestedAt`、`savedEpoch`、`ownerReleased`、`online`（查在线目录）。
    `online=true` 时明示「可能落后内存至多一个存盘周期」；需要此刻精确状态的，先 kick 让 scene 写回（§4.2）或等玩家下线。
  - 玩家不存在 404；号源无效 503；插入失败 503，什么也没写。
- **`POST /admin/zone-snapshots`** `{zones | allZones, note, reason}`（7.2b，作业）：按 `player.zone_id` 分批（每批 500）读、批量插入 PRE_MAINTENANCE 快照，不需要栅栏。
  不挂在 `/admin/zones/...` 下，免得与区服目录的路由（`ZoneAdminController.java:42`、`:79-114`）和指标归类混在一起。
  实现（`ZoneSnapshotService`，作业 kind `ZONE_SNAPSHOT`）：必带 `Idempotency-Key` 与 reason，`zones` 非空与 `allZones=true` 二选一，**不需要写开关**（不改玩家数据），回 202；
  它是作业只为统一审计与单飞（与回档共用 `ops_active`，不并发）。每批一个事务；快照语义同手工快照（`time_ms` = 内容时刻、`owner_epoch` = `saved_epoch`、操作人、
  `note` 末尾带 `job:<id>`）；在线玩家照拍已落盘状态，不夺权、不踢人。号源中途失效 → 当批回滚、之前的批保留，作业 FAILED `id_unavailable`；批与批之间查取消与时限；
  一个人也没有 → SUCCEEDED `zone_empty`。摘要给逐区计数 `byZone` 与 `total`。

### 3.5 详情与列表（对应 97 / 115）

- **`GET /admin/player-snapshots`**（扩展现有接口）：新增 `cause=`（可多值）、`order=asc|desc`（缺省 asc，保持现有行为；基线倒序）；输出加 `operator`、`note`。
  limit 仍 ≤ 1000（基线无上限，H14）。所有来源一起列。
- **`GET /admin/player-snapshots/{snapshotId}?includeState=true`**：带本体时用 protobuf-java-util 的 `JsonFormat` 把 `player_state` 转成 JSON
  （根 BOM 已管理该依赖，`pom.xml:86`；它是 protobuf 本身的一部分，不新增第三方库）。uint64 一律十进制字符串。不认识的字段原样以 base64 给出字节数与内容。

### 3.6 结构化差异（对应 98 / 116；也是回档 dry-run 的主体）

`GET /admin/players/{player}/snapshot-diff?snapshot=<id>` 或 `?atMs=<ms>`：

- **选快照**：按 id 时快照必须属于该玩家，否则 404（同基线 `snapshot_logic.go:271-289`）；按时刻用 §3.3 白名单。
- **「当前」= 已落盘状态**，应答带 `online`、`persistedAtMs`、`ownerReleased`（基线的「当前」是 Redis，接近实时；D19）。
- **输出**：
  1. **行级**：等级、场景、坐标。
  2. **货币**：逐币种快照值、当前值、delta；欠款逐笔对照。
  3. **物品**：按配置聚合数量；逐实例列出「只在快照里」「只在当前」「堆叠数变了」（uuid、包、格子、数量差）。
  4. **宝宝**：按 `pet_id` 列出增减。
  5. **其余段**（facing / attribute / mission / vitals）：先给相同 / 不同；不同的段用 protobuf 反射逐字段给路径级差异，至多 500 条，超出带截断标记；
     不认识的字段按字节比较。Java 的 protobuf 没有 C++ 的 `MessageDifferencer`，这个反射比较器自己写（几十行，放 `com.game.data.snapshot.StateDiff`）。
  6. **转移证据**（对应基线 `has_transfer_record` / `restorable` 的设计意图）：对「只在快照里」的物品实例按 `item_uuid` 查快照之后的流水（`idx_txlog_uuid`），
     标注被销毁 / 被转移 / 无记录；对货币 delta 查快照之后该玩家的转移类流水（原因 1 / 2 / 3 / 4 / 24 / 25 / 26）。给出 `restorable = !transferAfterSnapshot`，
     **只作提示，不自动裁剪回档内容**（D9）。局限：入包流水的 `item_uuid` 是「写到的第一个实例」（`AssetAudit.java:84`，同基线 PrimaryWrittenGuid），
     堆叠物品的证据只是近似。
     **证据完整性**：GM / 安全快照缺省永久保留（§3.7 不约束 `gm-snapshot`），会比流水活得久。应答顶层给 `evidenceComplete` 与
     `evidenceFloorMs`（= now − `retention.transaction-log`；保留期 0 时为 0）；快照时刻早于下界时 `evidenceComplete=false`，只认正面证据——
     仍看得到的转移照样 `transferAfterSnapshot=true` / `TRANSFERRED`、`restorable=false`，其余货币行两者都给 `null`、物品证据记 `INCOMPLETE`、
     `restorable=null`（未知，不当「没转移、可恢复」），`caveat` 追加说明。
  7. **账本差集**（§4.6.2 的结果）：快照之后在玩家身上已应用的资产通道操作，或「不可证明」。

### 3.7 保留期按原因区分

- `xm.data.retention.player-snapshot` 只清 LOGIN / LOGOUT / PERIODIC；GM_MANUAL / PRE_MAINTENANCE / PRE_ROLLBACK / PRE_GM_EDIT 另设
  `xm.data.retention.gm-snapshot`，缺省 0（永久）。基线若真去调 `DeleteOldSnapshots`，会把安全快照一起删掉。
- **启动校验，不满足就拒绝启动**：流水保留期为 0，或者「快照保留期 ≠ 0 且流水保留期 ≥ 快照保留期」。理由：回档窗口内要能用流水解释差异（转移证据），
  回收窗口也受流水保留期限制。
- 被保留期删掉的快照如果正被某个作业钉住（§4.4），该玩家结果记 `snapshot_gone`、不写。
- **容量**：每次上线产生两份快照，每份 ≈ `player_state` 字节数 + 约 100 B 元数据；缺省永久保留时库会无限增长，生产必须显式设保留期（Q11）。

---

## 4 回档（单人 / 整区 / 全服）

### 4.1 基线

**入口与顺序**（`RollbackPlayer`，`rollback_logic.go:518-603`）：入参（player 为 0，或 snapshot_id 与 target_time 都为 0 → 14；放行时 reason / operator
去空白后非空，`:189-194`）→ 依赖（`:32-43`）→ [令牌，server 层 `dataserviceserver.go:377`、`:413`、`:448`] → **先持栅栏**（`:532-536`）→ **再写 STARTED**
（`:538-550`，写不进零变更回 11）→ 帮会闸装配预检（checker 为 nil 就拒绝，回 28，`:292-304`）→ 计划（以实际选中快照的 created_at 作帮会检查起点，`:622-635`）
→ 沉降 → 检查 → [放行：ACCEPTED 审计 + 逐行 ERROR] → 执行 → 写后复查 → RESULT（脱钩 ctx，30 s 上限，`:106-111`）。栅栏持到 RESULT 之后（`:137-139`）。

**执行**（`rollbackSinglePlayer`，`:668-777`）：再选一次快照，早于计划值（R5）就不写、回 28（`:682-690`）；打 PRE_ROLLBACK 安全快照，失败或 id 为 0 就停（`:699-726`）；
`scope==1 && len(fields)>0` 才过滤字段，**PARTIAL 不带字段 = 全量**（`:728-739`）；`SavePlayerData(ExpectedVersion=0)` 跳过版本校验，**只 SET 快照里有的字段，
快照之后新增的字段不删**（`:749-763`；`data_logic.go:171-185`）。

**整区**（`:806-1065`）：先持 zone 栅栏再 STARTED；计划 = 快照所在 zone 里每人 `MAX(created_at) ≤ T`（`snapshot_store.go:645-674`）；一次帮会闸；按 player_id 升序执行，
执行期重选**不带 zone 条件**（`:949-955`），单人失败计入名单继续（`:959-966`）；孤儿（当前映射在本 zone 却没有快照）**只报告不删**，`orphans_cleaned` 恒 0（`:1020-1065`）。
**全服**（`:1086-1312`）：zone 清单 `Router.AllZoneIDs()` 升序（`:1097-1099`）；为全部 zone 写 STARTED、建计划；同一玩家多 zone 取最早时刻合并检查一次（`:1199-1234`）；
任一被拒就给每个 zone 补「not executed」RESULT（`:1186-1197`）；RESULT 响应码优先级：审计失败 > 写后分歧 > 执行失败（`:1279-1311`）。

**帮会资产闸**（`:128-436`；`guild_divergence.go:28-52`；`config.go:97-129`）：since = 快照秒×1000 − 余量，钳到 ≥ 1（`:240-249`）；余量缺省 300000 ms、合法区间
[5000, 3600000]；沉降 30 s（按 Go 帮会的重投参数推出约 27 s 上界，`:140-150`）；复查前等 10 s、复查预算 120 s；检查预算缺省 120 s、上限 1 h；每块 ≤ 100 人、每页 500 行；
过滤后超过 10000 行算检查失败；样本 20 条。结局：干净放行；有分歧或不可证明 → 27（可放行）；问不到（未配置 / 不可达 / 超时 / 超上限 / 预算耗尽）→ 28（放行无效）；
放行先写 ACCEPTED（写不进零写入）再逐行 ERROR；复查发现新 op_id → 29，不自动撤销。**只检查帮会**，交易流没有闸。

**三重失效**：栅栏没接线（B6）；读写的不是权威数据（B1）；scene 快照读不到（B1）。所以基线回档在生产上一律回 16。

### 4.2 Java 栅栏 = 归属夺权（`AdminOwnership`）

不发明新协议：xm-data 只是归属协议里的又一个写者（`architecture.md:649-667`）。

| 步骤 | 做法 | 为什么成立 |
|---|---|---|
| 取得 | `PlayerStore.claimOwnership(p)`，在 xm-data 自己的事务模板里调（夺权与读回 epoch 同一事务，不依赖 `@Transactional` 代理）；SQL 条件 `owner_released = 1 OR owner_lease_until < now`（5.2 工作区 `PlayerMapper.java:53-58`） | 夺权本身是原子判定，没有「先查在线、再动手」的 TOCTOU。在线、交出在途（5.2 持有 E+1）都夺不到，基线的冻结闸（`player_rollback_handler.cpp:57-67`）自动满足。**基线的战斗闸（`:68-80`）不由夺权覆盖**：本稿初版以「回合制战斗必在线、所以夺不到」为前提，批次 6.3 落地后不成立——Java 断线即写回并释放、战斗在 xm-battle 继续，`ifOnline=reject` 也能夺到「战斗锁仍在」的离线玩家；`kick` 走的顶号通路不看是否在战斗，在线战斗中的玩家同样会被踢下线。裁决（2026-10-05，批次 6.3，已落地）：夺权成功之后另查战斗锁（`BattleLockGate`），锁在的玩家不写、明细 `in_battle`；读不到锁的同样不写（fail-closed），明细是单独的 `battle_lock_unknown`（§13.3） |
| 在线玩家 | `ifOnline=reject`（缺省）：`Held` → 结果 `player_online`。`ifOnline=kick`：向 `xm:owner-takeover` 发 `OwnerTakeover{p, E}`，持有 E 的 scene 带围栏写回、释放、推 23 `{2017}` 后断开（`SceneWorld.onTakeoverRequested`，5.2 工作区 `:1460`）；xm-data 退避重试夺权（50 ms 起翻倍、封顶 800 ms，每次重发让出请求），`claim-wait`（缺省 35 s）内夺到就持有，否则记 `player_busy`。**分两轮**（`RollbackJob`）：第一轮对全部目标不踢地夺一次（离线的直接夺到），把在线的一起发让出请求；第二轮逐个带等待夺，**全轮共用一个截止时刻**（让出请求发出时起算一个 `claim-wait`），截止之后每人只再试一次（照样重发让出请求）。两轮循环里逐人之间查作业时限与取消标志（取消标志在库里，节流为至多每 1 s 读一次） | 走现成顶号通路，不新增 scene 接口、不改链路协议。35 s > 租约 30 s：覆盖 5.2 交出提交后、目标节点进场前 E+1 无人持有、让出请求落空的最坏情况（`scene-handoff-spec.md:956-957`）。超过仍夺不到，说明有活着的写者在续约。两轮 + 共用截止：scene 并行写回释放，总等待约为一次写回而不是人数 × 写回；持有者不响应让出时也不会变成「在线人数 × claim-wait」，第一轮已夺到的离线玩家不被长时间扣着（期间登录回 2005） |
| 期间的登录 | login 撞上 Held → 发让出请求（xm-data 不订阅这个频道）→ 3 s 后回 **2005**（`EnterGameHandler.java:275`、`:309-318`） | 唯一的客户端可见面（白名单账号同样） |
| 持有 | 单线程 `data-ops-fence` 每 10 s 批量 `renewOwnerLeases`；续不上的玩家记 `fence_lost`，之后不再对他写，释放时也不写墓碑、不调释放（归属已不在我们手里） | 与 scene 续约同口径（`architecture.md:658-660`）；写入带围栏，迟到的一笔只会被拒 |
| 写入 | 每人一个事务（§4.8），开头 `selectOwnerForUpdate` 确认 (E', 未释放)，再 `saveStateHeld` | 旧写者碰不到 E'；我们也盖不过别人 |
| 位置记录 | 释放前写登出墓碑 `PlayerLocationDirectory.removeAsync(p, E', 1)`（`PlayerLocationDirectory.java:150`）；失败只告警，TTL 60 s 兜底 | 被接管的 scene 不改位置记录（`architecture.md:674-677`），会留下指向旧实例的 `o`。位置记录按 (epoch, 序号) 只收更新的写，E' 更大，一定生效。等于把「持有归属的 scene 是唯一写者」推广为「归属持有者是唯一写者」，要写进 `architecture.md` §7 |
| 释放 | 写后复查与 RESULT 之后：墓碑 → `releaseOwnership(p, E')`。释放失败就等租约过期（≤ 30 s）。作业收尾 `releaseAll` 先并发发出全部墓碑、一起等至多 2 s，再逐个释放（Redis 不可用时不至于人数 × 超时）；写之前就出局的玩家（快照没了、现档损坏、`unknown_sections`）当场单独释放 | 同基线「栅栏持到 RESULT 之后」（`rollback_logic.go:137-139`） |
| 崩溃 | 租约 30 s 后自然失效；每人的写是单事务（不会半写）；清扫器把作业标 INTERRUPTED（§7.4） | 基线没有这个兜底 |

**竞态**：scene 释放与 xm-data 夺权之间，玩家可能抢先重登夺走下一个 epoch。xm-data 再撞 Held → 再踢一次 → 重试，整体受 `claim-wait` 约束。
整区回档时区服不开放，没有新会话（白名单账号除外，同样会被再踢）。

**与 5.2 交出的互斥**：交出事务要求 `owner_epoch = E AND owner_released = 0 AND lease ≥ X`（5.2 工作区 `PlayerMapper.java:90-100`），GM 在交出期间夺不到权；
源 scene 已死、租约过期后 GM 夺到了权，源 scene 迟到的交出被围栏拒绝；冻结中的实例收到让出请求只记 `pendingAbort=TAKEOVER`、等交出结局（`SceneWorld.java:1460-1468`）。
两种情形都要回归测试（§12.1 T-F3）。

**不变量**（测试要钉住）：
- **I1** 任一时刻 player 行只有一个可写 epoch；xm-data 的写只在它持有 E' 且未释放时生效。
- **I2** 对每个被写的玩家，「安全快照 + 覆盖写 + 流水 + 明细」要么都在，要么都不在。
- **I3** STARTED 事件提交之前，不踢人、不夺权、不写任何数据。
- **I4** 资产分歧检查没有得出「通过」（干净或合法放行）之前，不写任何玩家；写后复查一定在释放之前。
- **I5** 释放之前已尝试写出位置墓碑。
- **I6** 同一条源流水至多被回收一次（§5.6）。

### 4.3 接口与校验

`POST /admin/rollbacks`（作业；`dryRun=true` 同步返回计划）：

```json
{ "scope": "players|zones", "players": ["..."], "zones": [1], "allZones": false,
  "snapshotId": "...", "targetTimeMs": 0,
  "sections": ["level","position","facing","attribute","vitals","mission","assets"],
  "ifOnline": "reject|kick", "acceptDivergence": false, "acceptRecallReversal": false,
  "reason": "...", "dryRun": false }
```

- `scope=players`：`players` 1–100 个、去重、不含 0；`snapshotId` 与 `targetTimeMs` **二选一**，`snapshotId` 只允许单个玩家，且快照必须属于该玩家（否则 404 `snapshot_not_found`）。
- `scope=zones`：`zones` 非空与 `allZones=true` 二选一（后者取区服目录全部行、升序，目录为空 → 404 `zone_not_found`；执行时点名的区不在目录里同样 404）；只能按 `targetTimeMs`；
  每个 zone 的 `zone_config.manual_status` 必须是 MAINTENANCE 或 CLOSED（`xm-gateway-schema.sql:8`），否则 409 `zone_open`；`ifOnline` 强制为 kick
  （Q7；显式传 `reject` 回 400）；玩家总数超过 `max-players-per-job` 回 422 `plan_too_large`（§4.9）。
- `targetTimeMs` ≤ 现在 − `min-target-age`（缺省 5 min）：快照经 Kafka 落库有延迟。
- **不给 `sections` = FULL；给了空列表 → 400**（防误回全量；与 D5、T-R2 同一口径）。非空时按 §4.5 的组合规则校验，不合法回 400（修 H4）。
- `reason` 一律必填（基线只在放行时要求，`rollback_logic.go:189-194`）；操作人一律取 `X-Xm-Operator`，不在请求体里自报。
- 必带 `Idempotency-Key`；`xm.data.ops.enabled=false` 回 503 `ops_disabled`。幂等指纹取规范化后的请求（玩家号 / 区号去重升序，`allZones` 记作 `all`、不含展开后的区号）。
- **dry-run 的范围**（同步、只读、不夺权，不要幂等键与写开关）：逐人给出选中的快照元数据；`scope=players` 另给是否在线、按**已落盘**现档预演的恢复内容
  与账本差集视图（`clean` / `rows` / `unprovable[{stream, reason}]`）。**不做帮会检查**（要沉降，只在执行时做）。`scope=zones` 的 dry-run 不校验维护态，只列出各区当前状态；
  先数人，超过 `max-players-per-job` 回 422；逐人只列快照元数据（至多 200 人），不预演恢复内容、不算账本差集。执行以夺权之后的现档为准（§9.2 第 11 条）。
  **战斗锁**（批次 6.3，§13.3）：dry-run 对「有快照可回」的全部目标查一次战斗锁（没有可回的目标就不读），在**请求线程**上限时等（`xm.data.ops.battle-lock-wait`）。
  应答顶层 `battleLock` = `{checked, inBattleCount, inBattlePlayers（前 100 个玩家号）, unknownCount}`，读失败时另带 `error`；逐人 `inBattle` = true / false，读不到为 `null`
  （`scope=zones` 同样逐人标出）。读失败不让 dry-run 失败；它只是此刻的值，执行时在夺权之后重查。

### 4.4 快照选取：计划时钉住，执行时不重选

- 单人按 id：快照必须属于该玩家，否则 404 `snapshot_not_found`。
- 按时刻：§3.3 的 `findLatestAtOrBefore`，原因白名单不含安全快照。
- **计划时把选中的 `snapshot_id` 写进 `ops_job_player`（PLANNED），执行时直接用它，不再重选**。这比基线的 R5「重选不得更早」更强：
  帮会检查用的起点与实际恢复的内容一定对应同一份快照。代价是计划之后才落库的、更新的快照不会被采用（`min-target-age` 已把这个窗口压到可以忽略）。
  被钉住的快照在执行前被删了 → 该玩家 `snapshot_gone`、不写。
- dry-run 与计划应答都带所选快照的 `timeMs`、`ingestedAt`、`cause`，由运维判断是不是想要的那份。

### 4.5 恢复范围

**FULL**：
- player 行的等级、`scene_config_id`、坐标取快照值。
- `player_state` **整份换成快照内容**，包括新版本写下、本版本不认识的字段。快照之后新增的段被清掉（修 H5：整份替换，不是逐字段 SET）。
- **例外**：`currency.blocked_types`（GM 封禁获取的币种）保留当前值。它是处罚状态，不是资产，回档不应解除处罚（D6）。

**SECTIONS**（以当前状态为底，只把选中的段换成快照里的，快照没有这段就清掉；当前状态里不认识的字段保留——但选了 `assets` 时见表后的 `unknown_sections` 规则）：

| 段名 | 内容 | 组合规则 |
|---|---|---|
| `level` | player.level | 自由；属性点按新等级在进场时收敛（`PARITY.md:64`） |
| `position` | player.scene_config_id + 坐标 | 自由（常用于卡位救援） |
| `facing` / `attribute` / `vitals` | 对应段 | 自由 |
| `assets` | **按描述符计算**：`PlayerState` 里除 facing / attribute / mission / vitals 以外的全部字段（`RollbackSection.ASSET_FIELDS`）。现在是 currency（不含 blocked_types，含 debts）+ bag + pets + asset_ledger + battle_ledger（= 9，批次 6.3 的战斗结算账本）；以后新加的段缺省归资产组，非资产的新段要显式排除并给自己的段名 | **不可拆分**：资产通道账本记录的是货币与物品的结局，必须与资产同记录、同一次写（`player_state.proto:32-34`）。只恢复货币、保留当前账本，已应用 seq 对应的扣款会消失；只恢复账本，帮会会重投、重复记账。所以不开放 `currency` / `bag` / `pets` 单独的段名，写了回 400 并提示用 `assets` |
| `mission` | mission 段 | **必须与 `assets` 同选**：单独恢复任务会让快照之后已领过的奖励（在资产里）可以再领一次，即复制资产；反过来「只回资产」最多让玩家少拿奖励，可补偿 |

欠款随 `assets` 恢复：已抵扣额与余额是一体的，只回其一会对不上账。

**`unknown_sections`**（13.2 第 9 条，已回写）：SECTIONS 选了 `assets`，而快照与现档在 `PlayerState` 顶层有本版本不认识、且两边不同的字段
（滚动升级时较新的 scene 写下的新段，可能是新账本）→ 这名玩家不写，明细 `unknown_sections`，dry-run 同样标出并列出字段号。资产组按本版本的描述符计算，
不认识的段既判断不了是否账本、也不会随资产一起恢复——资产回到快照而账本留在之后会重复记账或扣款消失，所以 fail-closed：先升级 xm-data，或改用 FULL
（整份替换，未知字段随快照）。两边未知字段相同、或不动资产组的 SECTIONS，照旧保留现档的未知字段。

### 4.6 资产分歧检查（三道，都要过）

**4.6.1 帮会检查**（逐条对齐基线，`com.game.data.rollback.GuildDivergenceGate`）：

| 项 | 基线 | Java |
|---|---|---|
| 接缝 | `GuildDivergenceChecker`，nil = 一律拒（`servicecontext.go:94-99`） | Dubbo `GuildInternalService`（group `guild`）。**编程式引用**（`DubboGuildInternalClient`，xm-api 的 `IsolatedDubboModule`，同 xm-scene / xm-guild 的资产通道客户端；`retries = 0`、`check = false`），不引入 dubbo-spring-boot-starter：只读与 dry-run 的进程不起 Dubbo，引用在第一次检查时才建（在作业线程上）。**只支持直连** `xm.dubbo.guild-url`，为空 = 没装配 = 一律 `check_failed`；nacos 注册中心发现未接（部署批次再定）。调用方 MAC `XM_DUBBO_SECRET` 由 xm-api 的 SPI 过滤器加上；`ops.enabled=true` 而缺 `XM_DUBBO_SECRET` 拒绝启动 |
| since | 快照秒×1000 − 余量，钳到 ≥ 1 | 快照 `time_ms`（§3.2 的内容时刻）− 余量，钳到 ≥ 1，不再取整到秒（D9）。每人各自的起点；一块（≤ 100 人）用块内最早的起点问，再按每人自己的起点过滤（`updated_ms > since_p`） |
| 余量 / 预算 | 300 s，[5 s, 1 h]；检查 120 s、上限 1 h | 相同，做成配置项；另有单次 Dubbo 调用上限 `call-timeout`（缺省 10 s，再受剩余预算约束） |
| 分块 / 分页 / 上限 / 样本 | 100 人 / 500 行 / 10000 行 / 20 条 | 相同（`guild_internal.proto(xm):32-43`） |
| 结果处理 | gRPC status + 解析 message 前缀拿 cutoff | 只认 `OK`；其它结果码、future 异常完成、超预算都算 `check_failed`，放行无效；`RETENTION_REJECTED` 用 `cutoff_ms` 钳位重查一次，钳不到的玩家记不可证明（`GuildInternalService.java:12-14`）；游标不前进或返回块外玩家算 `check_failed`（`guild-economy-spec.md:770`） |
| 沉降 / 复查 | 30 s 常量 / 10 s | 配置项，缺省 30 s / 10 s（Q6）；复查用同一组 since，持有归属期间做。复查问不到帮会时不改变作业状态（按写入结果定 SUCCEEDED / PARTIAL），结果码记 `post_write_unverified` 并打 ERROR、转人工核对（§7.7） |
**4.6.2 账本差集**（Java 独有，D8；`com.game.data.snapshot.LedgerDiff`——**在 xm-data**，7.2a 起就在这里，没有挪进 xm-player-store 的 `asset` 包；
判定规则全部复用 xm-player-store 的 `PersistedAssetLedger` / `AssetLedgerRules`，与 scene 在线账本、帮会离线读结局是同一份判定代码）。只在回档会动资产组时算
（FULL，或 SECTIONS 选了 `assets`）；只比 `asset_ledger`（资产通道账本），不比 `battle_ledger`（理由见 §4.12）：

对每个目标玩家比较「快照里的 `asset_ledger`」S 与「夺权之后已落盘的 `asset_ledger`」C（kick 时 scene 已写回最新状态）：
- 同一条流、同一纪元：C 窗口内「已应用」而 S 里「未应用」的 seq → **分歧行** `(stream, seq)`。
- 列不出具体 seq 的，记该玩家**不可证明**：C 的纪元比 S 新；C 的水位越过 S 的水位且区间内有 S 未见的 seq（已滑出窗口）；S 没有这条流而 C 的水位 > 0；任一份账本损坏（`invalidReason != null`）。
- 立即可得，不用等沉降；对交易流（3 / 4）同样有效，4.8 交易写侧落地后自动覆盖（基线没有交易闸，H13）。
- 它是帮会检查的**保守超集**：快照之后已应用、但帮会还没终结的指令其实是安全的（账本随资产回退后会被重投、相对快照恰好一次），也会被列出；带原因放行即可。
  反过来，帮会检查兜住 seq 已滑出窗口的部分与帮会侧的事实（对齐基线语义），所以两者都要过。
- 不可证明的玩家同时意味着回档后该流的下一个 seq 可能超出 1024 位窗口，scene 会回 UNKNOWN、帮会侧转人工（`architecture.md:337`）。dry-run 应答的 `ledger` 视图给 `clean`、分歧行数 `rows` 与逐流的不可证明原因
  `unprovable[{stream, reason}]`（原因文本里带水位差，例如「当前水位越过快照窗口上沿（差 N）」）；**结构化的逐流水位回退量字段没有做**，整区 dry-run 也不算账本差集
  （§4.3）——留给 7.2c。执行时的 CHECK 事件与作业摘要给分歧行数、不可证明人数与样本（至多 20 条 `(player, stream, epoch, seq)`）。

**4.6.3 回收逆转检查**（Java 独有，D8；7.2c 起生效）：目标玩家在快照时刻之后有 TX_BATCH_RECALL(19) / TX_CLAWBACK(17) 扣减流水（`idx_txlog_from` + 原因过滤），
说明回档会把已回收的资产还回去 → 缺省拒绝，记 `recall_reversal`；`acceptRecallReversal=true` 时放行，每一条写进 ACCEPTED 事件。
**7.2b 已接线**（`RollbackJob`：逐人查快照 `time_ms` 之后原因 17 / 19、扣减方是他的流水），但这两个原因的写入方随 7.2c，所以 7.2c 之前恒为干净。

**三道检查内联在 `RollbackJob.run` 里，不抽 `AssetDivergenceChecker` 接口**（lead 2026-10-05 裁决，§13.1 第 11 条）：三道的输入、时机与裁决各不相同
（账本差集立即、帮会要沉降与写后复查、回收逆转查流水），抽成同一个接口没有第二个使用方。4.8 交易托管落地时若需要新的检查，再按那时的形状决定。

**裁决**：帮会检查 `check_failed` → 作业 FAILED，放行无效；有帮会分歧 / 账本分歧 / 不可证明 → 缺省 REJECTED，`acceptDivergence=true`（reason 必填）放行；
有回收逆转 → 缺省 REJECTED，`acceptRecallReversal=true` 放行。放行先提交 ACCEPTED 事件（写不进零写入，作业 FAILED `snapshot_db_error`），再逐行写 ERROR 日志，两者都在第一笔写之前。
被拒时的结果码：帮会有分歧行或不可证明 → `rollback_guild_divergence`，否则（只有账本差集的分歧 / 不可证明）→ `ledger_divergence`。

### 4.7 流程（单人 / 多人）

```
运维 ─POST /admin/rollbacks─▶ 受理（Tomcat 线程）：校验 → 幂等键 → 占单飞槽 + 插 ops_job + STARTED 事件（一个事务；失败 503，零变更）→ 202 {jobId}
OpsJobRunner（data-ops 线程；开始执行先在一个事务里刷新心跳并 QUEUED → RUNNING，不成立就一步也不执行，§7.4）→ RollbackJob：
 1 计划（只读）：逐人选快照并钉进 ops_job_player（PLANNED）；没有快照的分类（§4.9）；超 max-players-per-job → REJECTED plan_too_large
 2 夺权（§4.2，两轮）：Claimed → 持有；Held + reject → player_online；Held + kick → 让出、等待；超时 → player_busy；NotFound → player_not_found；访问库出错 → failed
   （单人 / 多人：夺不到的玩家只记结果，其余继续，一个也没夺到 → REJECTED；整区：见 §4.9）
 2b 查战斗锁（批次 6.3，§13.3；BattleLockGate）：两轮都夺完之后，对全部已夺到的玩家批量查一次。锁在 → in_battle；读不到 → battle_lock_unknown
   （fail-closed）；都不写，不走 acceptDivergence。单人 / 多人：被挡的人当场释放（墓碑并发写），其余继续；一个可写的也不剩 → REJECTED，
   结果码取夺权阶段第一个没夺到的结局，都夺到了而全被锁挡下则是 in_battle（只有读不到的时为 battle_lock_unknown）。整区：见 §4.9
 3 续约（data-ops-fence 每 10 s，夺到即纳入）
 4 账本差集（立即；同时把钉住的快照已被删的记 snapshot_gone、快照损坏或 SECTIONS 时现档损坏的记 state_invalid、未知字段不同的记 unknown_sections，
     这些玩家当场释放）→ 沉降 settle（缺省 30 s）→ 帮会检查 → 回收逆转检查 → 裁决；需要时 ACCEPTED 事件 + ERROR 日志
 5 逐人写事务（§4.8），按 player_id 升序；连续 3 人写失败或号源失效即停，剩下的记 not_executed
 6 等 recheck-delay（10 s）→ 写后复查（只在至少一人写成功时做，同基线 writeAttempted；持有归属期间）
     发现新 op_id → 作业 DIVERGED_AFTER_WRITE（紧急告警，不自动撤销）；问不到帮会 → 结果码 post_write_unverified（作业状态按写入结果定）
 7 RESULT 事件 + 终态作业行（执行线程自己的一个事务，失败重试至多 30 s，不随任何请求取消；写不进就不让槽、停心跳，交给清扫器）
 8 逐人：位置墓碑 → releaseOwnership（RESULT 之后）；让出单飞槽
```

过程事件：PLANNED（计划摘要 + 前 100 个目标）→ CLAIMED → CHECK → [ACCEPTED] → WRITE → [RECHECK] → RESULT；除 ACCEPTED 与 RESULT 外都是尽力而为（写不进只告警）。
CLAIMED 事件与作业摘要都带战斗锁检查的视图 `battleLock`（`checked` / `inBattleCount` / `inBattlePlayers` 前 100 个 / `unknownCount`，读失败时带 `error`）。

**取消**：`POST /admin/ops-jobs/{id}/cancel` 只在步骤 5 之前有效：计划之后、夺权循环里（逐人之间，至多每 1 s 读一次库）、夺权之后、沉降之后、裁决之后各查一次标志，
已夺权的全部释放、零写入，作业 CANCELLED。已进入写阶段的作业照常做完。

**作业时限**（`job-timeout`）：夺权循环里、沉降之后、放行（ACCEPTED）之前各查一次，超时即全部释放、零写入、FAILED `job_timeout`
（沉降之后已超时就不再问帮会；检查做完才超时的不写 ACCEPTED）；
写阶段超时写完当前玩家后停，剩下的记 `not_executed`：一个也没写成 → FAILED `job_timeout`，写成了一部分 → PARTIAL `job_timeout`（摘要带 `timedOut`）。

**作业终态**（按写入结果定）：全部写成 SUCCEEDED；写成一部分 PARTIAL；一个没写而有人写失败 → FAILED `snapshot_db_error`；一个没写、也不是失败或超时 → REJECTED，
结果码取第一个没写成的明细结局（如 `player_online`）。整区里没有快照的玩家只报告、不算失败。

### 4.8 一个玩家的写事务

```sql
BEGIN;
-- 1 加锁读归属（5.2 工作区 PlayerMapper.selectOwnerForUpdate）；不是 (E', 未释放) → ROLLBACK，记 fence_lost
SELECT owner_epoch, owner_released, owner_lease_until FROM player WHERE player_id = ? FOR UPDATE;
-- 2 读现档原字节（作为安全快照的内容；不解析也能存）
SELECT * FROM player WHERE player_id = ?;   SELECT data, saved_epoch, updated_at FROM player_state WHERE player_id = ?;
-- 3 安全快照：cause = 1001 PRE_ROLLBACK，operator，note = 'job:<id>'，time_ms = player_state.updated_at
INSERT INTO player_snapshot (...) VALUES (...);
-- 4 带围栏覆盖写（PlayerStore.saveStateHeld：updateStateHeld + upsertState，REQUIRED 传播加入本事务）
UPDATE player SET level=?, scene_config_id=?, pos_x=?, pos_y=?, pos_z=?, updated_at=?
 WHERE player_id=? AND owner_epoch=E' AND owner_released=0;
INSERT INTO player_state ... ON DUPLICATE KEY UPDATE data=?, saved_epoch=E', updated_at=?;
-- 5 回档流水（§4.10），tx_id 来自 SCENE_GUID 雪花，correlation_id = job_id
INSERT INTO transaction_log (...) VALUES (...), (...);
-- 6 作业明细：必须恰好改到 1 行
UPDATE ops_job_player SET outcome='RESTORED', pre_snapshot_id=?, written_ms=? WHERE job_id=? AND player_id=? AND outcome='PLANNED';
COMMIT;
```

- **与基线的差别**：基线的安全快照与覆盖写是两次调用，中间失败会留下「有安全快照没改写」（`rollback_logic.go:699-763`）。Java 四件事同一事务（D4）。
- **xm-data 是 `transaction_log` 唯一的写者**（`xm-data-schema.sql:1-2`），直写自己的行与 Kafka 消费路径的 ODKU 幂等互不影响；号不同源就不会撞。
- **事务边界**：由 xm-data 的服务方法开启；`PlayerStore` 的 `@Transactional` 方法按 REQUIRED 加入；pbmysql 表（`ops_job_player`）用
  `DataSourceUtils.getConnection(dataSource)` 取 Spring 事务绑定的同一个连接（§7.5）。
- **现档损坏**（`player_state` 解析失败）：FULL 回档正是修复手段，所以允许，但账本差集记「不可证明」（要 `acceptDivergence`），`blocked_types` 无法保留时取快照值并写进明细
  （`currentStateInvalid`）；前后余额无从得知，这名玩家**不写回档流水**（明细 `txlogSkipped`）。SECTIONS 需要以现档为底，现档损坏时该玩家 `state_invalid`、不写。
  快照本身解析失败同样 `state_invalid`。
- **实现**（`RollbackWriter.write`，一个 `TransactionTemplate` 事务）：恢复内容由纯函数 `RestoreBuilder.build(快照, 现档, sections)` 算出；安全快照经
  `PlayerSnapshotMapper.insertDirect`、流水经 `TransactionLogMapper.insertDirectAll`（普通 INSERT，撞主键即回滚，不是消费路径的 ODKU）；安全快照号与流水号在事务里从
  `OpsIds` 取，号源失效 → 回滚，`id_unavailable`。返回 RESTORED 之外的结局（`fence_lost` / `snapshot_gone` / `state_invalid` / `unknown_sections` / `id_unavailable`）
  都没有写任何东西；`saveStateHeld` 影响 0 行、安全快照或明细不是恰好 1 行、流水行数不符都抛异常整体回滚。

### 4.9 整区与多区（对应 100 / 101）

- **目标** = `player.zone_id ∈ zones` 的玩家（归属区，权威列；需要 `idx_player_zone`，§7.3、Q9）。基线按「快照所在 zone」推目标、再用映射 SCAN 找孤儿
  （`rollback_logic.go:924-938`、`:1043-1065`）；Java 的 `player.zone_id` 就是归属，7.3 合服改写它之后照样成立（D10）。快照所在 zone 与归属区不同的玩家在计划里单列（仅提示）。
- **没有快照的玩家只报告、从不删除**（R-D，`zone_data_rollback.md:74-90`），按 Java 有权威的 `player.created_at`（`xm-player-schema.sql:28`）分两类：
  `created_after_target`（建于 T 之后）与 `no_snapshot`（建于 T 之前却没有快照）。基线拿不到创建时间，只能笼统报候选（`rollback_logic.go:1039-1041`）。
  目标与孤儿都为空 → `zone_empty`。
- **夺权全有或全无**：先给全部目标夺权（强制 kick，两轮，§4.2）；两轮都夺完才裁决，任何一人夺不到 → 全部释放、零写入，作业 REJECTED `zone_not_quiescent`
  （摘要带前 100 个玩家号 `unclaimedPlayers`、总数 `unclaimedCount` 与按结局的计数 `unclaimedByOutcome`）。夺权时访问库出错则立即按 `zone_not_quiescent` 停下。
  **夺到之后战斗锁仍在 / 读不到（批次 6.3，§13.3）同样算不静止**：整单 REJECTED `zone_not_quiescent`、零写入；被挡的人只记明细（`in_battle` / `battle_lock_unknown`），
  归属与其余人一起在收尾时释放。战斗中的人数在摘要里**单列**为 `battleLock`（`inBattleCount` / `inBattlePlayers` / `unknownCount`，不并入 `unclaimed*`）。
  即使已经有人夺不到也照查锁——与「两轮都夺完才裁决」同理，一次把挡路的人列全；只有夺权访问库出错、立即停下的那条路径没走到查锁，摘要里没有 `battleLock`。
  对应基线「整份计划做完才写第一个玩家」（R4）；部分玩家被排除在整区回档之外会让玩家之间的交互（交易、帮会）错位。
- **一次合并的资产检查**：帮会检查合并做一次（Java 的目标按 `player.zone_id` 取，一名玩家只属于一个区，不会重复出现；基线同一玩家出现在多个 zone 时取最早快照时刻，
  `rollback_logic.go:1205-1222`）；账本差集逐人算；任一项拒绝即谁也不写。
- **执行**：按 player_id 升序；单人失败计入名单继续；**连续 3 人写失败或号源失效**就停，剩下的记 `not_executed`。
- **规模上限** `max-players-per-job`（缺省 10000）：超出回 422 `plan_too_large`，不自动拆批（拆批会破坏 R4）。受理时先数人（`countInZones`，走 `idx_player_zone`），
  dry-run 同样先数人再计划；作业体里计划之后再核一次（并发增长的兜底，超了作业 REJECTED `plan_too_large`）。
- **dry-run**：不校验维护态，只列出各区当前状态与逐人选中的快照（§4.3）。
- RESULT 一个事件。摘要的 `plan` 给总目标数、已计划数、按结局的未计划计数（`no_snapshot` / `created_after_target` 等）与快照所在区和归属区不同的玩家；`outcomes` 给逐结局计数。
  **逐 zone 计数没有做**（基线逐 zone 写 RESULT 行，是它审计表的形状所致）——留给 7.2c。

### 4.10 回档流水（D11）

- 每个余额有变化的币种一行 TX_ROLLBACK_RESTORE(16)：before = 现档余额、after = 快照余额、delta = after − before；delta > 0 记获得方 `to_player`，否则记扣减方 `from_player`。
- 每个数量有变化的物品实例一行（只在快照里 = 获得、只在现档里 = 扣减、两边都有 = 数量差）。
- 宝宝没有流水种类（`AssetKind` 只有货币和物品），宝宝变化只进明细摘要。
- 基线回档不写流水；Java 写，是为了让流水的余额链首尾相接（`PARITY.md:84` ①），之后 scene 的流水从恢复后的余额接着记。

### 4.11 撤销

撤销一次回档 = 对同一玩家以 `snapshotId = preSnapshotId`（PRE_ROLLBACK）再回档一次，照样过全部闸。PRE_ROLLBACK 只能这样显式选用（§3.3）。

### 4.12 与其他系统的相互作用

- **资产通道账本**：账本随 `assets` 一起回退后：快照之后才应用、帮会还没终结的指令会被重投，在回退后的钱包上再应用一次，相对快照恰好一次，正确；
  已终结的由帮会检查拦下。
- **帮会离线读账本（E8，`guild-economy-spec.md:545-555`）**：栅栏期间帮会循环可能读到还没回档的旧账本、据此终结一条指令，这正是写后复查要抓的「检查之后才终结的行」；
  读到已回档的账本则 seq 未见，按 I7「读到未见绝不判中止」不会误终结。
- **6.3 战斗结算**（lead 2026-10-05 裁决，取代本稿初版「6.3 落地时必须实现一个 `AssetDivergenceChecker`」的要求；Q13）：
  - **不需要单独的分歧检查器，也不建 `AssetDivergenceChecker` 接口**。战斗结算账本 `battle_ledger = 9` 按描述符自动归入资产组（§4.5），随 `assets` / FULL 整段回退。
    依据 `scene-battle-spec.md` §4.6 的 S-11：账本退回旧值、待结算记录（pending，在 Redis，不参与回档）还在，未销账的局回档后由待结算记录重投、
    在回退后的状态上再应用一次，相对快照恰好一次；已销账的局，奖励随回档一起消失，这正是回档的语义。列出「现档有而快照没有的 battle_id」只会命中前一种安全情形，
    缺省拒绝只增加噪音、不增加保护。
  - **基线的「战斗中拒绝」不由夺权覆盖**（本稿初版的前提「战斗中玩家必在线」已不成立，§4.2）：改为夺权成功之后、账本差集之前，对已夺到的玩家批量查战斗锁
    （xm-discovery `BattleLockReader.existsAll`），锁在的玩家不写、明细 `in_battle`——对应基线回档对战斗中玩家回 1005
    （`player_rollback_handler.cpp:68-80`；`scene-battle-spec.md` §7.13 第 5 条的「7.2 钩子」）；读不到锁的同样不写（fail-closed），明细是单独的
    `battle_lock_unknown`（规则拒绝与 Redis 故障分开记）。已随批次 6.3 落地，实现见 §13.3。
    读的是咨询性的普通读路由：上主从 / 集群之前要给这道闸一个读主库的入口，或把 Redisson `readMode` 设成 MASTER（§13.3 末条、`architecture.md` §6）。
- **4.8 交易托管**：账本差集对交易流（3 / 4）同样有效，交易写侧落地后自动覆盖（§4.6.2）；是否还要额外的检查由 4.8 的规格另定，本稿不预留接口。
- **在线周期存盘 / 接管守卫**：被踢的实例已移除，迟到的在线存盘带旧 epoch 被拒（`PlayerMapper.java:76-82`）；scene 分区后残留的旧实例下次接管时发现库已变，以库为准
  （`architecture.md:355-356`），不会盖掉回档结果。
- **缓存**：Java 没有 Redis 玩家数据缓存层，不需要失效任何东西；在线状态由 gate 维护，踢线时已删除。

---

## 5 批量回收

### 5.1 基线（`BatchRecallItems`，`recall_logic.go:58-248`，无鉴权）

1. 校验：依赖（非 dry-run 还要审计 store，`:62-70`）→ 时间窗必填（`:72-74`）→ 物品配置号与币种不能同时为 0（`:75-77`，B2）。
2. 查询：指定玩家逐个 `QueryLog`，**任一失败整体回 11**、不许跳过（`:86-109`）；否则全服一次（`:110-128`）；每次上限 10000（`:48`）。
3. 截断（候选总数 ≠ 返回数）：回 17、`total_failed = 候选数`、零变更；审计 store 在场时连 dry-run 也写 `REJECTED (TRUNCATED)`（`:137-160`）。
4. dry-run：每行一条结果；玩家取 to_player，没有就取 from_player；数量取物品数量或**正**的 delta，扣减行记 0（`:162-184`）。
   `player_ids` 不去重；一笔交易两侧玩家都在名单里时同一行计两次（H12）。
5. 非 dry-run：每行记失败、回 16，照写审计 `rollback_type=4`、`players_affected=0`——修掉旧实现「只置 success 就累加 total_recalled」的「对运营说谎」（`:186-247`）。

### 5.2 Java 匹配规则与查询

`POST /admin/recalls`：

```json
{ "players": ["..."], "kind": "currency|item", "currencyType": 0, "itemConfigId": 0,
  "sinceMs": 0, "untilMs": 0, "reasons": [9], "ifOnline": "reject|kick",
  "shortfall": "report|debt", "reason": "...", "dryRun": true }
```

- **筛选**：`kind=currency` 时 `currencyType` 必填、**允许 0（金币）**；`kind=item` 时 `itemConfigId` 必填（≠ 0）；`reasons` 可选；时间窗必填、半开 `[since, until)`，
  `sinceMs` 不得早于「现在 − 流水保留期」（已清掉的流水会让集合不完整）。`players` 为空（全服）时窗口不超过 `max-window`（缺省 7 天）。`players` 至多 1000 个、去重。
- **只匹配获得行**：`to_player <> 0`，物品行，或货币行且 `currency_delta > 0`。基线把扣减行也选进来、数量记 0（H12）；Java 扣减行不算候选（D14）。
- **去重**：同一 `tx_id` 只计一次（指定玩家的逐人查询结果按 tx_id 合并）。
- **排除已回收的**：`recall_source` 里已有的 tx_id 不再计入（§5.6）。
- **查询形状**：指定玩家时逐人走 `idx_txlog_to (to_player, time_ms)`；全服时走新索引
  `idx_txlog_item (kind, item_config_id, time_ms)` / `idx_txlog_currency (kind, currency_type, time_ms)`；`reason` 在已缩小的范围内过滤，不建索引。
  排序 `(time_ms, tx_id)` 升序，游标扫描。
- **已知限制**：入包流水每个配置一条、`item_uuid` 记「写到的第一个实例」（可能是并入的既有堆），所以**按实例回收不精确**，回收按「配置 + 数量」做。

### 5.3 完整性：截断与落库完整性

- **截断**：游标扫到 `max-rows + 1`（缺省 10000）就判截断 → 422 `result_truncated`，零变更，作业事件照写（dry-run 也写，同基线 `:137-160`）。不做热表 `COUNT(*)`。
- **落库完整性闸**（Java 独有，D14；7.2c）：流水在生产端是尽力而为的（未确认的进兜底日志、不重发），「库里没有」不等于「没发生」。执行前用 kafka-clients 的 Admin API
  对流水 topic 每个分区比较「消费组 `xm-data-transaction-log` 已提交位点」与「`listOffsets(OffsetSpec.forTimestamp(untilMs + ingest-slack))` 给出的位点」
  （没有该时刻之后的记录就比 end offset）；全部 ≥ 才算完整，否则 409 `ingest_lagging`。dry-run 只报告 `ingestComplete`。
  - Kafka 记录时间戳是生产者发送时刻，必然 ≥ 资产变动的 `time_ms`，所以要加 `ingest-slack`（缺省 60 s）并要求 `now ≥ untilMs + ingest-slack`。
  - 查的是**整个消费组**；现有 `xm_data_consumer_lag` 只反映本实例分到的分区，多实例时不完整。
  - 兜底日志里没回灌的行仍不在库里，应答照实声明（`fallbackCaveat`）。

### 5.4 dry-run（7.2a，同步）

逐行返回玩家、tx_id、uuid、配置号或币种、数量、原因；按玩家汇总应回收量，并按现档估算可回收量与缺口；带 `truncated`、`ingestComplete`。只读，不需要 `ops.enabled`。

### 5.5 执行（7.2c，作业）

同一道栅栏（§4.2）。每个玩家一个事务：

1. `selectOwnerForUpdate` 确认 (E', 未释放)。
2. 安全快照 `PRE_GM_EDIT`（1002）。
3. `PersistedStateEditor` 扣减（只删不增）：
   - 货币：扣 `min(余额, 应收)`。
   - 物品：按配置号扣应收总量；先扣流水里记的 uuid，再按背包 **3 临时格 → 0 人物背包 → 1 仓库 → 2 装备栏**（`player_state.proto:23`）、同包内按入包序号新到旧扣同配置的其它实例（Q10）。
     只减 `stack_size` 或**删除实例**，从不写出 `stack_size = 0`（scene 加载时会丢弃并 WARN，`PlayerBags.java:109-113`）、不移动格子。
4. `saveStateAndRelease(E')`：回收不需要写后复查，写完即释放（缩短锁人时间）；提交前先写位置墓碑。
5. 写 TX_BATCH_RECALL(19) 流水：扣减方 `from_player`，前后余额自洽，`correlation_id = job_id`，`extra` 只放源条数（明细在 `recall_source`，`extra` 有 1024 字符上限）。
6. 每条源流水插一行 `recall_source`（主键源 tx_id），`recovered_qty` 按源流水先后分摊。
7. 明细 `PLANNED → RECALLED`。

在线玩家（`ifOnline=reject`）结果 `player_online`、不执行；应答计数照实给出，绝不虚报（对应基线修掉的「对运营说谎」）。回收只删不还，不需要帮会检查。

### 5.6 去重与回收逆转

- `recall_source` 以源 `tx_id` 为主键、与改档同事务插入：同一条源流水至多被回收一次（I6），跨作业、跨操作员都成立。用新幂等键重提，已回收的行记 `already_recalled`，零变更。
- 回档的回收逆转检查见 §4.6.3。

### 5.7 缺口

- `shortfall=report`（缺省）：只记录在明细与应答里。
- `shortfall=debt`：给货币缺口挂补缴欠款（`currency.debts`，规则同 §6.3 Attach）；scene 已会用之后的收入抵扣（`architecture.md:357-358`）。这会唤醒两版都休眠的补缴机制（Q2）。
  物品缺口不挂欠款，如实报告。

---

## 6 scene 侧 GM 指令 102–117

### 6.1 号表 96–117 → Java 对应

全部「客户端不可发」：所在服务都没标 `OptionIsClientProtocolService`。

| 号（`message_id.txt` 行） | 服务.方法（proto 行） | 基线处理方 | Java 对应 | 子批 |
|---|---|---|---|---|
| 96（:97） | DataService.CreatePlayerSnapshot（`data_service.proto:35`） | data_service，无鉴权 | `POST /admin/player-snapshots` | 7.2a |
| 97（:98） | DataService.ListPlayerSnapshots（`:36`） | 同上 | `GET /admin/player-snapshots`（扩展） | 7.2a |
| 98（:99） | DataService.GetPlayerSnapshotDiff（`:37`） | 同上 | `GET /admin/players/{id}/snapshot-diff` | 7.2a |
| 99 / 100 / 101（:100–:102） | DataService.RollbackPlayer / Zone / All（`:38-40`） | 要令牌；恒回 16 | `POST /admin/rollbacks`（players / zones / allZones） | 7.2b |
| 102（:103） | SceneRollbackClientPlayer.GmAttachDebt（`player_rollback.proto:211`） | scene 空桩 | `POST /admin/players/{id}/debts/{type}/attach` | 7.2c |
| 103（:104） | …GmCreateSnapshot（`:218`） | 空桩 | 同 96 | 7.2a |
| 104（:105） | …GmTraceItem（`:229`） | 空桩 | `GET /admin/items/{uuid}/trace` | 7.2a |
| 105（:106） | DataService.CreateEventSnapshot（`data_service.proto:45`） | 无鉴权、**无调用方** | 并入 96（事件写 `note`，D20） | 7.2a |
| 106（:107） | …GmWaiveDebt（`:212`） | 空桩 | `DELETE /admin/players/{id}/debts/{type}` | 7.2c |
| 107（:108） | …GmQueryDebt（`:215`） | 空桩 | `GET /admin/players/{id}/debts` | 7.2c |
| 108（:109） | DataService.BatchRecallItems（`data_service.proto:43`） | 只有 dry-run 可用 | `POST /admin/recalls` | 7.2a / 7.2c |
| 109（:110） | …GmFreezeDebt（`:214`） | 空桩 | `PUT /admin/players/{id}/debts/{type}/frozen` | 7.2c |
| 110（:111） | …GmClawbackItem（`:230`） | 空桩 | `POST /admin/items/{uuid}/clawback` | 7.2c |
| 111（:112） | LoginAdmin.RemovePlayersFromAccounts | 只服务于已删除的孤儿清理（`rollback_logic.go:1034-1035`），无调用方 | 不移植 | — |
| 112（:113） | …GmExecuteRollback（`:223`） | 空桩 | `POST /admin/rollbacks`（带 `sections`；逐 uuid 补偿式见 Q14） | 7.2b |
| 113（:114） | …GmAdjustDebt（`:213`） | 空桩 | `POST /admin/players/{id}/debts/{type}/adjust` | 7.2c |
| 114（:115） | DataService.QueryTransactionLog（`data_service.proto:44`） | 无鉴权 | `GET /admin/transaction-log`（扩展，§6.5） | 7.2a |
| 115（:116） | …GmListSnapshots（`:219`） | 空桩 | 同 97 | 7.2a |
| 116（:117） | …GmPreviewRollback（`:222`） | 空桩 | 差异接口，或回档 `dryRun=true` | 7.2a / 7.2b |
| 117（:118） | …GmQueryTransactionLog（`:226`） | 空桩 | 同 114 | 7.2a |

这些号的应答类型是契约 proto 里的消息（`PlayerSnapshotEntry`、`SnapshotDiff`、`TransactionLogEntry`、`CurrencyDebtEntry`、`TraceHop`）。Java 运维面是服务端内部接口，
按 Java 惯用方式出 JSON，**不复用这些契约消息**，也不需要从 Java 格式反向映射成秒 / `TransactionType`（Java 独有原因 1001–1004 在契约里本来就没有对应值）。

### 6.2 gate / scene：两版行为一致，Java 不注册

- **基线 scene**（`player_rollback_handler.cpp`）：12 条处理器第一句都是运行模式闸 `RejectGmRollbackRpc`（非 dev/test 设 tip 1006，`:45-55`；判据 `player_gm_guard.h:68-78`）；
  写类（102、106、109、110、112、113）再过 `RejectIfFrozen`（跨 zone 冻结或回合制战斗在途回 1005，`:57-82`）；`GmCreateSnapshot` 是写类但**刻意不过冻结闸**（`:150-159`）；
  读类（104、107、115、116、117）只有运行模式闸。之后全部 `TODO(P1-B)`（`:95`…`:233`），返回空应答、不设 `error_message`。
  它们只能经节点路由 `InvokePlayerService` 打进来，gate 永远不转（`:26-40` 注释）。
- **基线 gate**：号不在 `IsClientMessageId` 里就打 DEBUG、计非法包，到阈值 `forceClose`，不回包（`client_message_processor.cpp:874-886`）；GM 清单只有六条，
  并写明 102–117 本来就到不了客户端、不要加进清单（`gate_gm_client_messages.h:30-46`）。
- **Java gate**：`MessageRoutes.of` 只为 `clientService()` 的方法建路由（`MessageRoutes.java:47-58`）；`ClientDispatcher.onRequest` 查不到路由计 `UNKNOWN_MESSAGE`、
  `registerIllegal(s, "unknown_message_id", …)`，不回包（5.2 工作区 `ClientDispatcher.java:225-226`）。
- **Java scene**：`ClientRequestHandler.register` 拒绝注册「不是客户端玩家服务」的方法，启动即抛（5.2 工作区 `ClientRequestHandler.java:140`）；`onClientForward`（`:167`）
  对非客户端方法直接丢弃；Java 没有能调到玩家客户端方法的节点路由入口（`PARITY.md:60`）。所以不需要桩（`inventory/modules.md:201`）。
- **为什么这组指令不该放在 scene**：`GmExecuteRollback` 注释要求「玩家必须离线」（`player_rollback.proto:120-122`），服务却标了 `OptionIsPlayerService`、处理器挂在**在线**
  玩家实体上（`:206-208`）——自相矛盾。离线编辑本来就该由运维面持有归属后做。基线注释也写明线上改玩家数据走 data_service 那条运维面（`player_rollback_handler.cpp:42-44`）。
- **7.2 只补回归测试**（§12.1 T-G1、T-S1）：`SceneRollbackClientPlayer` / `DataService` / `LoginAdmin` 的每个方法都不可路由；伪造的 `ClientForward` 带 112 被丢弃；
  在 scene 注册 112 启动即抛。以后若有人在 mmorpg 给这些服务加客户端协议标记，变化会在两版同步时被看见。

### 6.3 欠款 102 / 106 / 107 / 109 / 113（7.2c）

- **路径**：`GET /admin/players/{id}/debts`（读已落盘，不夺权）；`POST …/debts/{type}/attach`、`POST …/adjust`、`PUT …/frozen`、`DELETE …/debts/{type}`（作业，离线编辑，
  可 `ifOnline=kick`）。写入走 §4.8 同形事务：安全快照 `PRE_GM_EDIT`、`saveStateAndRelease`；**不写流水**（余额没变）。
- **规则逐条照搬 C++ 原语**（`currency_system.h:72-97`；纯规则类放 xm-player-store `com.game.player.store.edit.DebtRules`，scene 的 `Wallet` 只做抵扣，不重复实现）：

  | 原语 | 出处 | 规则 |
  |---|---|---|
  | Attach | `currency_system.cpp:304-351` | 数额为 0 拒绝；owed + 数额溢出拒绝；累加 owed；覆盖 reason / operator / expires；`created_at` 只在首次设置 |
  | Waive | `:357-383` | 删整笔，返回剩余额 |
  | Adjust | `:389-474` | 欠款不存在且 delta > 0 时新建；增加时检查溢出；减少时 owed 不低于 paid；`INT64_MIN` 先在无符号域取绝对值，不减出环绕 |
  | Freeze | `:480-506` | 没有欠款就什么也不做 |

- 币种只认 0–2，未知回 400（同 `CurrencyService` 对未知币种回 1005 的口径）；Attach 数额按**无符号 64 位**解析（基线 102 的 `owe_amount` 是 uint64
  （`player_rollback.proto:20`），`AttachDebt` 参数却是 int64，≥ 2^63 会变负数被拒——H11，D21）；到期时刻用 Unix 秒（`player_state.proto:145`）；
  operator 取请求头，reason 必填。欠款不下发给客户端（`PARITY.md:84` ③），只体现为「之后的收入被抵扣」。

### 6.4 物品追溯 104（7.2a）与精确回收 110（7.2c）

- **追溯** `GET /admin/items/{uuid}/trace`：按 `(item_uuid, time_ms)` 升序列出所有跳（`idx_txlog_uuid`），语义同基线 `QueryByItemUUID`（`transaction_log_store.go:217-246`）。
  末跳给提示 `hint`：`HELD_BY`（末跳是获得）/ `DESTROYED` / `MERGED`（整理时合并掉的空实例记 TX_ITEM_DESTROY、数量 0，`txlog.proto(xm):23`）。
  **只是提示**：Java 没有全局物品索引，不做全表扫找当前持有者（D17）；入包流水只记第一个实例 uuid（`AssetAudit.java:84`），追溯是尽力而为。
- **精确回收** `POST /admin/items/{uuid}/clawback {player, reason, ifOnline}`：必须显式给出持有者 `player`，不依据追溯结果推断；离线编辑，在四个背包里找到该实例整个移除，
  记 TX_CLAWBACK(17)（扣减方）；找不到回 404 `item_not_held`（可能已被合并，提示改用按配置回收）。`refundBuyer` 不支持（Java 还没有交易写侧，4.8）。

### 6.5 流水查询扩展（114 / 117）

扩展现有 `GET /admin/transaction-log`：

- **参数**：`player`（变成可选）、`kind`、`currencyType`（0 有效）、`itemConfigId`、`itemUuid`、`reasons`（多值）、`since` / `until`（半开毫秒）、`limit ≤ 1000`、
  游标 `after=<timeMs>:<txId>`（键集分页，不用 OFFSET，修 H14）。
- **走索引的约束**：不给 `player` 时必须给 `itemUuid`、`kind+itemConfigId`、`kind+currencyType` 之一；只给 `reasons` / 时间窗时窗口不超过 `max-window`（走 `idx_txlog_time`）。
- **输出**：结构不变，仍按 `(time_ms, tx_id)` 升序（基线倒序）；多回 `nextCursor`；不回总数（基线每次 `COUNT(*)`，`transaction_log_store.go:180-185`）。

### 6.6 客户端间接可见面与 tip

| 场景 | 基线 | Java | 出处 |
|---|---|---|---|
| GM 持有归属期间 EnterGame | 设计上「阻止新登录」，未实现 | 2005 kLoginInProgress，客户端稍后重试 | `EnterGameHandler.java:309-318`；`architecture.md:653-656` |
| 运维 kick | — | 23 `{2017}` 后断开（文案是「被其他账号顶下线」，Q5） | `SceneWorld.onTakeoverRequested` |
| 回档 / 回收 / 欠款之后 | — | 下次进场从库加载，54 / 191 / 193 / 181 / 167 反映新状态，不推送（目标离线） | — |
| 客户端发 96–117 | gate 计非法包、不回包 | 同（`UNKNOWN_MESSAGE`） | §6.2 |
| 1006 / 1005 | scene 桩回（只经节点路由才可达） | 不存在 | `player_rollback_handler.cpp:45-82` |

---

## 7 Java 落地映射

### 7.1 模块、包、依赖

**xm-data 新包**（领域对象 + XxxService，不用 ECS 命名）：
- `com.game.data.query`：流水查询、物品追溯（`TransactionLogQueryService`）。
- `com.game.data.snapshot`：保留现有消费代码；新增 `SnapshotAdminService`（手工快照直写、详情）、`SnapshotDiffService` + `StateDiff`（结构化差异）、`StateJson`、
  `SnapshotCauses`、`LedgerDiff`（账本差集，§4.6.2）、`ZoneSnapshotService`（整区维护前快照，7.2b）。初稿设想的独立 `SnapshotWriter` 没有单列，直写在两个服务里。
- `com.game.data.ops`（类名按 7.2b 的实际实现；初稿列的 `OpsJobSweeper` / `IdempotencyKeys` 没有单列成类）：`OpsJobStore`（pbmysql）、`OpsJobService`
  （受理、幂等重放 `replay`、查询、取消）、`OpsJobRunner`（`data-ops` 执行、心跳 `beat`、清扫 `sweep`、RESULT；嵌套 `JobBody` / `JobResult` / `JobAbortedException`）、
  `JobContext`（事件、检查点、可中断等待、时限、取消标志）、`OpsRequests`（幂等键、reason、无符号号码、请求指纹）、`OpsException`、`OpsTables`、
  `OpsIds`（`SCENE_GUID` 租约 + 雪花）。
- `com.game.data.ops.fence`：`AdminOwnership`（夺权 / 让出 / 续约 / 墓碑 / 释放），只调 `PlayerStore`、位置墓碑与让出发布器两个函数式接缝；
  `RedisTakeoverRequests`（让出请求发布方）；`StrictClock`（严格递增毫秒，§7.5）。
- `com.game.data.rollback`（初稿列的 `RollbackExecutor` 实际拆成 `RollbackJob` + `RollbackWriter`）：`RollbackRequest`（§4.3 校验与规范化）、`RollbackSection`（段名规则）、
  `RollbackService`（受理与 dry-run）、`RollbackPlanner`（选源与分类）、`RollbackJob`（§4.7 作业体，三道资产分歧检查内联在这里）、`RestoreBuilder`（§4.5 恢复内容，纯函数）、
  `RollbackWriter`（§4.8 单人写事务）、`GuildDivergenceGate`（帮会检查，接缝是其嵌套接口 `Client`）、`DubboGuildInternalClient`。**没有 `AssetDivergenceChecker` 接口**
  （lead 2026-10-05 裁决不建，§4.6.3、§4.12）。
- `com.game.data.recall`：`RecallPlanner`、`RecallExecutor`、`IngestCompleteness`。
- `com.game.data.admin`：新控制器 `RollbackAdminController`、`RecallAdminController`、`OpsJobAdminController`、`PlayerOpsAdminController`（差异 / 欠款）、`ItemAdminController`。
- `com.game.data.tools.AuditFallbackReplay`：兜底日志回灌 CLI（§2.4）。

**xm-player-store**：
- `com.game.player.store.edit.PersistedStateEditor`（只删不增：扣货币、删实例 / 减堆叠）与 `DebtRules`（7.2c）。`LedgerDiff` **没有**放进这里的 `asset` 包，
  仍在 xm-data 的 `com.game.data.snapshot`（7.2a 起；只复用本模块的 `AssetLedgerRules` / `PersistedAssetLedger`）。
- **不新增写 SQL**：夺权、带围栏写、释放、续约、加锁读全用现成方法（`selectOwnerForUpdate` 随 5.2）。只读的「player 行 + player_state 原字节 + updated_at」读取放在 xm-data
  自己的 `PersistedPlayerMapper`（`find` / `findBrief` / `listInZone` / `countInZones`），不动 xm-player-store 的 Mapper。7.2b 对本模块只改了建表脚本（`idx_player_zone`）。

**xm-discovery**：初稿打算把 xm-login 的 `RedisOwnerTakeovers`（`xm-login/.../ownership/RedisOwnerTakeovers.java`）的发布部分挪成共用件。**7.2b 没有挪**：xm-data 内实现
`com.game.data.ops.fence.RedisTakeoverRequests`，频道（`RedisKeys.ownerTakeoverTopic()`）与消息（`xm.api.OwnerTakeover`）都取自共用定义，发布尽力而为、不阻塞、不抛异常；
挪进 xm-discovery 另行安排（只是搬家）。

**xm-data 新依赖**：xm-player-store、xm-pbmysql、`org.apache.dubbo:dubbo`（核心包，编程式引用，**不是** dubbo-spring-boot-starter；§4.6.1）、protobuf-java-util。都是仓库内模块或
tech-stack 已登记的选型（Dubbo 41.6K、protobuf；`tech-stack.md:13`、`:18`），**不新增第三方库**；tech-stack 只需登记「xm-data 是 `GuildInternalService` 的 Dubbo 调用方」与 pbmysql 的新用途。
**Redis 的连接时机**（按实现，取代初稿「启用写操作时立即连接」）：`RedissonClient` 是懒加载 bean，由发号器 `OpsIds`（`SmartLifecycle`）在启动后的 `data-ops-ids` 线程上后台申领
`SCENE_GUID` 租约时连上，连不上每 10 s 重试——与 `ops.enabled` 无关，不同步等待、不 fail-fast（Redis 不可用不挡启动与审计消费）。让出请求与位置墓碑经
`ObjectProvider::getObject` 取同一个客户端；Redis 不可用时发号无效 → 写接口回 503 `id_unavailable`，让出请求发不出 → 夺不到记 `player_busy`，墓碑失败只告警。

### 7.2 配置项

| 键 | 缺省 | 说明 |
|---|---|---|
| `xm.data.ops.enabled` | false | 改玩家数据的写操作总开关；开启时 `XM_DUBBO_SECRET` 必填，缺了拒绝启动。本机切片脚本设 true |
| `xm.data.ops.claim-wait` | 35 s | 夺权最长等待（> 租约 30 s，§4.2）；kick 第二轮全轮共用一个 |
| `xm.data.ops.max-players-per-job` | 10000 | 一个作业涉及的玩家上限 |
| `xm.data.ops.job-timeout` | 30 min | 从开始执行算起。写阶段之前超时（夺权循环里、沉降之后、放行之前各查一次）：全部释放、零写入、FAILED `job_timeout`；写阶段超时：写完当前玩家后停，剩下的 `not_executed`，一个没写成 FAILED、写成一部分 PARTIAL，结果码都是 `job_timeout`（§4.7） |
| `xm.data.ops.min-target-age` | 5 min | 回档目标时刻至少早于现在多久 |
| `xm.data.ops.max-window` | 7 d | 全服查询 / 全服回收的时间窗上限 |
| `xm.data.ops.heartbeat` / `stale-after` | 5 s / 60 s | 作业心跳与清扫判定（清扫器同样每个 `heartbeat` 跑一拍；启动校验 `stale-after ≥ 3 × heartbeat`） |
| `xm.data.ops.battle-lock-wait` | 5 s | 回档前查战斗锁（批次 6.3，§13.3）一次检查全程最多等多久：分块（每块 500 人）共用一个截止时刻，到点没读完的玩家按读不到处理（`battle_lock_unknown`，不写）。缺省略长于 Redis 单条命令的最坏阻塞（`xm.redis` 缺省 4.2 s），Redis 故障时先拿到它自己的报错；必须为正，否则拒绝启动。作业与 dry-run 共用。没有对应的环境变量 |
| `xm.data.rollback.guild.settle` | 30 s | 沉降等待（Q6） |
| `xm.data.rollback.guild.recheck-delay` / `recheck-budget` | 10 s / 120 s | 写后复查 |
| `xm.data.rollback.guild.clock-skew-margin` | 300 s，[5 s, 1 h] | 同基线 `config.go:104`、`:117-119` |
| `xm.data.rollback.guild.check-budget` | 120 s，≤ 1 h | 同基线 `config.go:110`、`:129` |
| `xm.data.rollback.guild.call-timeout` | 10 s | 单次 Dubbo 调用的上限（再受剩余预算约束） |
| `xm.data.recall.max-rows` | 10000 | 同基线 `recall_logic.go:48` |
| `xm.data.recall.ingest-slack` | 60 s | §5.3 |
| `xm.data.retention.gm-snapshot` | 0 | GM / 安全快照保留期（§3.7） |
| `xm.dubbo.guild-url` | `tri://127.0.0.1:20886`（环境变量 `XM_DUBBO_GUILD_URL`） | 帮会检查直连的 xm-guild。为空 = 没有装配 = 帮会检查一律 `check_failed`（回档执行不了）；只支持直连，nacos 发现未接。xm-data 只有一份 `application.yaml`，没有 nacos profile |

环境变量（`application.yaml`）：`XM_DATA_OPS_ENABLED`、`XM_DATA_OPS_CLAIM_WAIT`、`XM_DATA_OPS_MIN_TARGET_AGE`、`XM_DATA_ROLLBACK_SETTLE`、`XM_DATA_ROLLBACK_RECHECK_DELAY`、
`XM_DUBBO_GUILD_URL`、`XM_DATA_GM_SNAPSHOT_RETENTION`；其余键没有对应的环境变量，用 Spring 的常规方式覆盖。

### 7.3 表与迁移

**`transaction_log` / `player_snapshot`**（MyBatis 手写表；新库改 `xm-data-schema.sql`，存量库在 `docs/design/db-migrations.md` 登记下一个空闲迁移号——当前最后是 M7，
5.2 若先占 M8 就顺延）：

```sql
ALTER TABLE transaction_log
    ADD KEY idx_txlog_item (kind, item_config_id, time_ms),
    ADD KEY idx_txlog_currency (kind, currency_type, time_ms),
    ADD KEY idx_txlog_uuid (item_uuid, time_ms);
ALTER TABLE player_snapshot
    ADD COLUMN operator VARCHAR(64)  NOT NULL DEFAULT '',
    ADD COLUMN note     VARCHAR(256) NOT NULL DEFAULT '';
```

- 不合成一条 `(kind, item_config_id, currency_type, time_ms)`：那要依赖「物品行币种恒 0、货币行配置号恒 0」这条生产方不变量，一旦被打破回收会静默漏行（fail-open）。
- `reason` 不建索引：回收与全服查询必须先带物品 / 币种 / uuid，原因只在已缩小的范围里过滤。
- 不加 `idx_snapshot_zone`：整区目标改由 `player.zone_id` 给出（§4.9）。
- 热表多三条二级索引会放大落库写入，可接受；这些索引都是按 SQL 形状推断的，**还没在真库上跑 EXPLAIN**（§12.3 验证）。

**`player`**（`xm-player-schema.sql`，由 xm-login 执行；7.2b，5.2 落地之后）：`ADD KEY idx_player_zone (zone_id)`（Q9）。

**新表（xm-pbmysql，proto 在 `xm-data/src/main/proto/xm/data/ops_tables.proto`，启动时 `syncAll` 建表 / 只扩不缩）**：

| 表 | 主键 / 唯一键 | 主要列 | 何时写 |
|---|---|---|---|
| `ops_job` | `job_id`；唯一 `idem_key` | `kind`（SNAPSHOT / ZONE_SNAPSHOT / ROLLBACK / RECALL / DEBT / CLAWBACK）、`status`、`request_json`、`request_hash`、`operator`、`reason`、`result_code`、`players_planned / affected / failed`、`divergence_rows`、`unprovable_players`、`accepted_divergence`、`accepted_recall_reversal`、`runner`、`created_ms / started_ms / finished_ms`、`summary_json`、`cancel_requested`（7.2b 加，字段号 22，pbmysql 启动补列，db-migrations M9） | 受理时插入；之后一律**按列、带状态条件**更新（`QUEUED → RUNNING`、写结局列、置取消标志），不整行覆盖（§7.4） |
| `ops_active` | `slot`（恒 1） | `job_id`、`runner`、`heartbeat_ms` | 受理时插入（重键 = 有作业在跑 → 409 `ops_busy`）；终态时删除 |
| `ops_job_player` | (`job_id`, `player_id`) | `planned_snapshot_id`、`planned_snapshot_ms`、`claimed_epoch`、`pre_snapshot_id`、`outcome`、`detail_json`、`written_ms` | 计划时插 PLANNED；**在写玩家数据的同一事务里**改终态 |
| `ops_job_event` | (`job_id`, `seq`) | `type`（STARTED / PLANNED / CLAIMED / CHECK / ACCEPTED / WRITE / RECHECK / RESULT / INTERRUPTED）、`payload_json`（≤ 64 KB）、`at_ms` | 只追加；STARTED / ACCEPTED / RESULT 对应基线 `rollback_audit_log` 的同名行 |
| `recall_source` | `tx_id`（被回收的源流水） | `job_id`、`player_id`、`kind`、`asset_id`、`matched_qty`、`recovered_qty`、`at_ms` | 回收改档的同一事务 |
| `audit_replay_line` | (`file_sha256`, `line_no`) | `tx_id`、`at_ms` | 回灌工具（§2.4） |

单飞用独立的 `ops_active` 行，而不是一稿提议的 MySQL `GET_LOCK`（要在专用连接上持有整个作业、H2 没有、多副本下连接断开就丢）或「可空唯一列」（pbmysql 的列不存 NULL）。

**`xm.audit` 枚举**（只增不改）：`TransactionReason += TX_ROLLBACK_RESTORE=16, TX_CLAWBACK=17, TX_BATCH_RECALL=19`（沿用 mmorpg 数值）；`SnapshotCause` 见 §3.2。
改了枚举要 `clean install`（AGENTS.md §4：残留生成类会掩盖错误）。

### 7.4 作业框架

- **受理**（Tomcat 线程，同步；`OpsJobService.submit`）：参数校验 → 写开关（回档执行）→ `Idempotency-Key`（1–64 个可见 ASCII，必填）：同键、同种类、同 `request_hash`
  （SHA-256(方法 + 路径 + 规范化请求)）回原作业（`replayed=true`）；同键不同参数 409 `idempotency_conflict`；缺失 400 → 发作业号（号源无效 503 `id_unavailable`）→
  **一个事务**插 `ops_active`（重键 = 有作业在跑 → 409 `ops_busy`，带在跑的作业号）+ `ops_job`（QUEUED）+ STARTED 事件；任一失败 503，零变更
  （R-A，对应 `rollback_logic.go:538-550`）→ 202 `{jobId}`。交给执行器时它已关（停服中）→ 作业改 FAILED `not_submitted`、让出槽、回 503。
- **执行**（`OpsJobRunner`）：`data-ops` 单线程执行器（与单飞一致）；作业不绑定 HTTP 请求，调用方断开不影响执行（对应基线的脱钩 ctx）。
  **开始执行先核对**：一个事务里刷新心跳（槽必须仍属于它）并 `QUEUED → RUNNING`（带状态条件），任一不成立就回滚、作业体一步也不执行——排队期间已被清扫器中断的作业
  不会在别的作业占着槽时夺权 / 踢人。作业体抛异常按 FAILED `snapshot_db_error`，照样写 RESULT、调 `afterResult` 收尾。
- **状态**：QUEUED → RUNNING → {SUCCEEDED, PARTIAL, REJECTED, FAILED, DIVERGED_AFTER_WRITE, CANCELLED, INTERRUPTED}。作业行的状态迁移一律按列、带
  `status IN (QUEUED, RUNNING)` 条件更新：不抹掉受理线程（可能在别的副本上）写的 `cancel_requested`，也不把清扫器写的 INTERRUPTED 改回去。
  **谁把作业行改成终态谁记 `jobs_total`**，同一作业不重复计数。
- **心跳**：`data-ops-fence` 线程（同一线程还跑归属续约）每 `heartbeat`（5 s）更新 `ops_active.heartbeat_ms`，新值取 `GREATEST(heartbeat_ms + 1, now)`
  （`useAffectedRows=true` 下同一毫秒的第二次心跳否则数出 0 行、被误判为槽已被收走）。更新不到行 = 槽已不属于它 → `JobContext.aborted()` 为真，
  作业体在下一个检查点（各阶段边界、每次等待每 200 ms）抛 `JobAbortedException` 自停，之后不再写任何玩家；这时只追加一条 RESULT 事件留痕，
  作业行还没终结才由执行线程兜底改成 INTERRUPTED。
- **清扫**：每个副本都跑 `data-ops-sweeper`（每 `heartbeat` 一拍）。心跳超过 `stale-after`（60 s）的槽，在**一个事务**里：按心跳值 CAS 删槽 → 未终结的作业行改
  INTERRUPTED → 改到了才追加 INTERRUPTED 事件；任一步失败整体回滚（槽还在，下一拍重来）。作业已终结、只是槽没让出的，只收回槽、WARN 一行，不追加事件、不记中断。
  事件序号与仍活着的执行线程撞号时整个事务重来（至多 3 次）。已提交的玩家完整（每人一个事务）；没来得及写后复查的已写玩家转人工（INTERRUPTED 事件里注明）。
- **RESULT**：与终态作业行同一个事务，在执行线程上写，失败重试至多 30 s；仍写不进 → ERROR 日志、停心跳、**不让出单飞槽**，由清扫器改 INTERRUPTED。
  RESULT 之后才调作业体的 `afterResult`（释放归属），最后让出单飞槽。
- **取消**（`POST /admin/ops-jobs/{id}/cancel`）：标志落在 `ops_job.cancel_requested`（多副本时请求可能落在别的副本上，执行线程读库）；幂等——标志已置上、作业未终结时
  重复取消仍回 `cancelRequested=true`；只在第一笔写之前生效（§4.7）。写一行 `xm.audit.ops`（`[OpsJob] CANCEL`），操作人由 `xm.audit.admin` 记。
- **停服**（生命周期阶段：Web 服务器 > `OpsJobRunner` > `OpsIds`，大的先停）：Web 服务器先停（不再受理）→ 执行器停清扫、等在跑的作业至多 5 s，不打断执行线程
  （打断会让一笔写档事务半途失败）→ 最后交还发号租约。没做完的作业在进程退出、心跳停止后由清扫器（别的副本，或本实例重启后）改成 INTERRUPTED。
  这个次序 2026-10-06 回到代码核实过（`OpsJobRunner.getPhase` = `OpsIds.PHASE + 1`，Web 服务器的阶段更大）：正文是对的，原先 `OpsJobRunner` 里的注释写反了、已改，`OpsLifecycleOrderTest` 钉住。
- **不自动续跑**：重提会把已回档的玩家再写一遍同一份快照（结果相同，但会抹掉两次之间的新进度），所以必须由人看明细决定、用新键重提。
- **玩家粒度幂等**：明细 PLANNED → 终态是条件更新、与写档同事务、必须恰好 1 行；夺权 / 释放 / 墓碑都带 epoch，重复执行无害。
- **单人短操作**（欠款、精确回收）也是作业（统一审计、统一单飞），通常几秒完成；手工快照不夺权，同步执行，只写一条终态作业行。
- **查询**：`GET /admin/ops-jobs?status=&kind=&limit=`、`GET /admin/ops-jobs/{id}`（含事件）、`GET /admin/ops-jobs/{id}/players?after=&limit=`。

### 7.5 线程、事务与连接

- **线程**：Tomcat 请求线程（受理、同步只读查询、dry-run）；`data-ops`（作业，阻塞 JDBC / Redis 可以，它不是 Netty I/O 或场景逻辑线程，AGENTS.md §3）；
  `data-ops-fence`（续约 + 心跳）；`data-ops-sweeper`（清扫）；`data-ops-ids`（发号租约的申领、重试与续期，7.2a 起）。都是单线程、守护线程。
  回档前的战斗锁读（批次 6.3）由调用线程限时等：作业在 `data-ops` 上、dry-run 在 Tomcat 请求线程上（最多占一个请求线程 `battle-lock-wait`），Redisson 的回调线程只完成 future。
- **事务混用**：一个写事务里同时有 MyBatis（`PlayerStore`、快照 / 流水 Mapper）和 pbmysql（`ops_job_player`、`recall_source`）。pbmysql 的方法都接收调用方的 `Connection`
  （`architecture.md:635-637`），用 `DataSourceUtils.getConnection(dataSource)` 取 Spring 事务绑定的同一个连接，MyBatis-Spring 走的也是它。
- **`useAffectedRows=true`**：xm-data 连接串带它（`application.yaml:14-15`），ODKU 才能数出新插入行；而 `PlayerMapper.renewOwnerLeases` 的注释按「匹配行数」写
  （5.2 工作区 `PlayerMapper.java:132`）。逐条核对：夺权改 epoch、释放改 `owner_released`，影响行数恒等于匹配行数；续约返回偏小时走 `selectStillHeld` 慢路径，结果仍正确；
  **唯一的坑**是 `updateStateHeld` / `updateStateAndRelease` 在「所有列都没变、`updated_at` 与上次同一毫秒」时影响 0 行，会被误判为失去围栏。处理：xm-data 自己定义
  `PlayerStore` bean（`PlayerStoreAutoConfiguration` 的 `@ConditionalOnMissingBean` 允许，`:25-29`），时钟用严格递增的毫秒（`max(now, last+1)`）；写事务开头的
  `selectOwnerForUpdate` 已确认持有，所以这个防护只是兜底。不能为此另开数据源，否则做不成同事务（§12.3 用生产连接串回归）。
- **自动装配**（按实现）：`PlayerStoreAutoConfiguration` **仍被 `DataApplication` 排除**（`guild-economy-spec.md:1156` 的坑）；`DataConfiguration` 的 `@MapperScan` 除本服务的
  `com.game.data.store` 外补上 `PlayerMapper`，两者共用同一个 `SqlSessionFactory` 与数据源（同事务的前提），并自己定义 `PlayerStore` bean（`StrictClock`）。
  `mapUnderscoreToCamelCase` 打开（与 xm-player-store 同口径）。`xm-player-schema.sql` 仍只由 xm-login 执行。
- **连接池**：Druid `max-active` 现为 4（`application.yaml:19-22`），两条消费线程 + 保留期清理 + 运维查询 + 作业事务会挤，调到 8。

### 7.6 HTTP 接口总表（全部在 `/admin/**` 下，过滤器统一鉴权）

| 方法 | 路径 | 形态 | 需要 `ops.enabled` | 子批 |
|---|---|---|---|---|
| GET | `/admin/transaction-log` | 同步 | 否 | 7.2a |
| GET | `/admin/items/{uuid}/trace` | 同步 | 否 | 7.2a |
| GET | `/admin/player-snapshots`、`/admin/player-snapshots/{id}` | 同步 | 否 | 7.2a |
| POST | `/admin/player-snapshots` | 同步 + 作业行 | 否（不改玩家数据） | 7.2a |
| GET | `/admin/players/{id}/snapshot-diff` | 同步 | 否 | 7.2a |
| POST | `/admin/recalls`（`dryRun=true`） | 同步 | 否 | 7.2a |
| POST | `/admin/zone-snapshots` | 作业 | 否 | 7.2b |
| POST | `/admin/rollbacks` | 作业（dry-run 同步） | 是（dry-run 否） | 7.2b |
| GET / POST | `/admin/ops-jobs[/{id}[/players\|/cancel]]` | 同步 | cancel 是 | 7.2b |
| POST | `/admin/recalls`（执行） | 作业 | 是 | 7.2c |
| GET / POST / PUT / DELETE | `/admin/players/{id}/debts[...]` | 读同步、写作业 | 写是 | 7.2c |
| POST | `/admin/items/{uuid}/clawback` | 作业 | 是 | 7.2c |

`AdminAuthFilter.opOf`（`:77-95`）按前缀补固定值：`rollbacks`、`recalls`、`ops_jobs`、`players`、`items`、`zone_snapshots`；`/admin/player-snapshots/{id}` 归 `player_snapshots`。
任意路径不能变成标签值。所有写接口 `reason` 必填（≤ 256 字符，不含控制字符）；**例外**：`POST /admin/ops-jobs/{id}/cancel` 不带请求体、不要 `reason` 与幂等键
（操作人进 `xm.audit.admin`，取消本身进 `xm.audit.ops`），回 202。`/admin/zone-snapshots` 要 `reason` 与幂等键，但不要写开关。回档 `dryRun=true` 不要幂等键与写开关（`reason` 仍必填）。

### 7.7 结果码

HTTP 状态 + 应答体 `code` 字符串（取基线常量名去掉前缀，便于两版运维对照；不是客户端契约）：

| code | 基线数值（`error_codes.go`） | HTTP / 作业状态 |
|---|---|---|
| `invalid_request` | 14 | 400 |
| `admin_auth_required` | 23 | 未配置 503、令牌错 401、缺操作人 400（过滤器现状） |
| `ops_disabled` / `id_unavailable` | — | 503 |
| `ops_busy` / `idempotency_conflict` | — | 409 |
| `snapshot_not_found` | 10 | 单人 404；多人 / 整区记在明细 |
| `player_not_found` | — | 404 / 明细 |
| `player_online` / `player_busy` / `fence_lost` | 13 / — / — | 明细；单人且 reject 时作业 REJECTED |
| `in_battle` / `battle_lock_unknown` | —（基线 scene 侧对战斗中玩家回 tip 1005，`player_rollback_handler.cpp:68-80`） | 明细；单人 / 多人里目标都夺到了、却全被战斗锁挡下时也是作业 REJECTED 的结果码（有确认在战的报 `in_battle`，只有读不到的报 `battle_lock_unknown`）；整区里有人被挡时结果码是 `zone_not_quiescent`（批次 6.3，§13.3） |
| `zone_open` / `zone_not_quiescent` | — | 409 / 作业 REJECTED |
| `zone_empty` | 15 | 作业 SUCCEEDED（无事可做） |
| `result_truncated` / `plan_too_large` | 17 / — | 422 |
| `ingest_lagging` | — | 409 |
| `rollback_guild_divergence` / `ledger_divergence` / `recall_reversal` | 27 / — / — | 作业 REJECTED，附样本 |
| `rollback_guild_check_failed` | 28 | 作业 FAILED（放行无效） |
| `rollback_guild_diverged_after_write` | 29 | 作业 DIVERGED_AFTER_WRITE |
| `post_write_unverified` | — | 写后复查问不到帮会：作业状态按写入结果定（SUCCEEDED / PARTIAL），数据不撤销，ERROR 日志、转人工核对 |
| `snapshot_db_error` | 11 | 503 / 作业 FAILED（受理写不进、作业体异常、ACCEPTED 写不进、有人写失败而一个也没写成） |
| `job_timeout` | — | 作业 FAILED（写之前超时，或写阶段超时一个没写成）/ PARTIAL（写阶段超时写成了一部分） |
| `cancelled` | — | 作业 CANCELLED |
| `interrupted` | — | 作业 INTERRUPTED（心跳丢失，执行线程自停时写进 RESULT） |
| `not_submitted` | — | 作业 FAILED（受理后交给执行器时正在停服，没有执行） |
| `ok` / `partial` | — | 作业 SUCCEEDED / PARTIAL 的缺省结果码 |
| `zone_not_found` / `job_not_found` | — | 404 |
| `not_implemented` | 16 | 回档不使用（Java 真正执行）；批量回收执行在 7.2c 之前回 501 |

**逐玩家结局**（`ops_job_player.outcome`，也是 `xm_data_ops_players_total` 的 `outcome` 标签；指标里 `RESTORED` 记作小写 `restored`）：

| 结局 | 何时 |
|---|---|
| `PLANNED` → `RESTORED` | 计划时钉住快照；写事务提交 |
| `player_not_found` / `snapshot_not_found` | 计划时玩家不存在 / 按号选的快照不属于他；夺权时玩家不存在 |
| `no_snapshot` / `created_after_target` | 目标时刻之前没有可用快照：建于目标时刻之前 / 之后（整区只报告，不算失败） |
| `player_online` / `player_busy` | 在线且 reject / 踢了但等不到释放 |
| `in_battle` | 夺到之后战斗锁仍在（批次 6.3，§13.3）。无条件不写，不走 `acceptDivergence` / `acceptRecallReversal` |
| `battle_lock_unknown` | 夺到之后读不到战斗锁：读取方抛异常、future 异常完成、超时（`battle-lock-wait`）、应答里没有这名玩家。fail-closed，同样不写；与 `in_battle` 分开记（规则拒绝对 Redis 故障） |
| `snapshot_gone` | 钉住的快照在检查或写之前被删 |
| `state_invalid` | 快照解析失败；或 SECTIONS 而现档解析失败 |
| `unknown_sections` | SECTIONS 选了 `assets`，两边顶层未知字段不同（§4.5） |
| `fence_lost` | 续约发现归属已不在我们手里，或写事务开头加锁核对不是 (E', 未释放) |
| `id_unavailable` | 写事务里号源失效（之后的人不再写） |
| `failed` | 夺权或写事务访问库出错（事务已回滚） |
| `not_executed` | 写阶段停下之后没轮到的人（连续 3 人写失败、号源失效、作业超时） |
| `rejected` / `cancelled` | 作业在第一笔写之前被拒 / 被取消时，还没定结局的人 |

告警口径同基线（`go/data_service/data_service.go:184-199`）：`check_failed` 与 `diverged_after_write` 算故障；`divergence`、`player_online` 是规则拒绝，不算故障。
战斗锁的两个结局照此分：`in_battle` 是规则拒绝，`battle_lock_unknown` 算故障（Redis 不可达或超时）。

### 7.8 本机脚本与 robot 配套

- `tools/local/start-slice.sh`：xm-data 设 `XM_DATA_OPS_ENABLED=true`、`XM_DUBBO_SECRET`、`xm.dubbo.guild-url`、调小 `min-target-age`（例如 5 s）与 `settle`（例如 3 s，仅本机）。
- robot 沿用 `AuditScenario` 访问运维接口的写法（HTTP + 令牌 + 操作人），新增场景见 §12.6。

### 7.9 文档与 PARITY 登记清单

- `architecture.md`：新增「GM 运维面（批次 7.2）」小节；§7 写明「归属持有者是唯一写者」、GM 夺权与位置墓碑；§9 写明 xm-data 也从 `SCENE_GUID` 发号；§11 加 xm-data 新指标。
- `tech-stack.md`：xm-data 引入 Dubbo 消费端、pbmysql 用于运维作业表。
- `db-migrations.md`：§7.3 的迁移条目（两张审计表、`player` 索引）。
- `PARITY.md`：更新 67 / 68 行（查询扩展、新原因值、回灌工具）；新增「GM 快照与差异」「GM 回档（单人 / 整区 / 全服）」「GM 批量回收」「GM 欠款（102 / 106 / 107 / 109 / 113）」
  「物品追溯与精确回收（104 / 110）」「scene 侧 102–117（客户端不可达，两版一致）」行；更新 84 行「两版都休眠」的说明；有意差异逐条引用 §10 编号。
- 回档入口上线即兑现 `guild-economy-spec.md:1155` 的前置要求，在该文件 §9.2 第 9 条后注明。

---

## 8 指标与审计

### 8.1 三层审计

1. **请求层**：`xm.audit.admin` 每次调用一行（已有，`AdminAuthFilter.java:68-72`）。
2. **作业事件层**：`ops_job_event` 只追加。
   - STARTED 提交失败 → 503，零动作（同基线 `:538-550`）；顺序与基线不同：Java 先 STARTED 后夺权，所以连「因在线被拒」的尝试也留痕（D3）。
   - ACCEPTED 一定先于第一笔写；写不进就零写入（同基线 `:363-381`）。
   - RESULT 在作业线程自己的事务里写（与终态作业行同事务），HTTP 请求早已返回，不受请求取消影响；写失败重试（30 s 上限）仍失败 → ERROR 日志、停心跳、不让出单飞槽，
     作业留 RUNNING 由清扫器改 INTERRUPTED 并由它记 `jobs_total{outcome=interrupted}`（基线是把审计失败升级成 11，`:567-584`）。
   - 结果码、计数是独立列（基线只编码在 reason 字符串里，`rollback_logic.go:115-126`）。
3. **数据层**：每个被写的玩家都有一份 PRE_ROLLBACK / PRE_GM_EDIT 快照（可直接作撤销源）；回档 / 回收流水 `correlation_id = job_id`；明细在 `ops_job_player`。

### 8.2 指标（全部在 `DataMetrics` 一个类里定义；不以 player / job / zone 的 id 作标签，AGENTS.md §5）

标签取值都是代码里的固定集合。**注册时机按实现**：只有两个 Gauge（`jobs_running`、`fence_held`）在装配时预注册（空闲报 0），其余 Counter / Timer 是某个标签组合
**首次用到时才注册**（不是初稿写的「启动时预注册」），所以没发生过的结局在抓取结果里不存在，告警表达式要按「缺失 = 0」写。下表的取值按 7.2b 的代码列出。
**例外（批次 6.3 起）**：`xm_data_ops_players_total{kind="rollback"}` 的 18 个 `outcome` 在装配时全部预建为 0（`RollbackService` 构造时经
`DataMetrics.registerOpsPlayers` 登记 `RollbackJob.PLAYER_OUTCOMES`）——战斗锁的两个结局要能直接写 `rate(...) > 0` 的告警，「从没发生」与「指标不存在」得分得开；
`DataMetricsPrometheusTest` 钉住导出的取值恰好是这个固定集合，新加结局必须加进 `PLAYER_OUTCOMES`。

| 指标 | 类型 | 标签 | 基线对应（`metrics.go`） |
|---|---|---|---|
| `xm_data_ops_jobs_total` | Counter | `kind`=作业种类名小写（7.2b：rollback / zone_snapshot；清扫时读不到作业行记 unknown）、`outcome`=succeeded / partial / rejected / failed / diverged_after_write / cancelled / interrupted | `data_service_rollback_total{scope,outcome}`（`:71-76`） |
| `xm_data_ops_job_seconds` | Timer | `kind` | — |
| `xm_data_ops_jobs_running` | Gauge | — | — |
| `xm_data_ops_players_total` | Counter | `kind`=rollback（整区维护前快照不逐人记）、`outcome` = §7.7 的逐玩家结局：restored / player_online / player_busy / player_not_found / snapshot_not_found / no_snapshot / created_after_target / snapshot_gone / state_invalid / unknown_sections / fence_lost / id_unavailable / failed / not_executed / rejected / cancelled，加上批次 6.3 的 in_battle / battle_lock_unknown（§13.3），共 18 个，装配时预建；recalled / edited 随 7.2c。初稿写的 online / busy / created_after 实际带前后缀，如左 | `rollback_players_affected_total`（`:79-84`） |
| `xm_data_ops_claims_total` | Counter | `outcome`=claimed / kicked / online（在线、没有踢）/ timeout / not_found / error | — |
| `xm_data_ops_fence_held` | Gauge | — | 空闲时应为 0 |
| `xm_data_ops_fence_lost_total` | Counter | — | — |
| `xm_data_rollback_divergence_check_total` | Counter | `source`=guild / ledger / recall。`result`：guild = clean / divergence / unprovable / check_failed / post_write_clean / post_write_diverged / post_write_failed；ledger = clean / divergence / unprovable；recall = clean / divergence。初稿的 truncated / retention 没有单独取值（超 10000 行并入 check_failed，保留期钳位后的玩家记 unprovable） | `rollback_guild_check_total`（`:163-170`、`:191-256`） |
| `xm_data_rollback_divergence_rows_total` | Counter | `source`=guild / ledger，`accepted`（7.2b 只在放行时累加，所以只有 `accepted="true"`） | `rollback_guild_divergence_rows`（`:172-178`） |
| `xm_data_rollback_guild_check_seconds` | Timer | — | `rollback_guild_check_seconds`（`:180-186`） |
| `xm_data_recall_rows_total` | Counter（7.2c，尚未定义） | `kind`=currency / item，`outcome`=recovered / partial / shortfall / already_recalled | — |
| `xm_data_snapshot_admin_total` | Counter | `cause`（固定集合）、`result` | — |
| `xm_data_location_tombstones_total` | Counter | `result`=ok / stale / error | — |
| `xm_data_retention_deleted_total` | Counter（已有） | `table` 增加 `player_snapshot_gm` | — |

### 8.3 告警

- `xm_data_ops_fence_held > 0` 持续超过 `job-timeout`：作业可能卡住、玩家进不了游戏。
- `divergence_check_total{result=~"check_failed|truncated|post_write_.*"}` 增长。
- `jobs_total{outcome=~"interrupted|diverged_after_write"}` 增长。
- `claims_total{outcome="timeout"}` 突增。
- `players_total{kind="rollback",outcome="battle_lock_unknown"}` 增长：回档前读不到战斗锁（Redis 不可达 / 超时），这些玩家被 fail-closed 挡下；`in_battle` 是规则拒绝，不告警。

### 8.4 日志

- logger `xm.audit.ops`：作业事件镜像；放行时逐行 ERROR `[Rollback][Divergence] accepted job=… player=… source=guild|ledger op_id/seq=… …`（字段同基线 `rollback_logic.go:440-451`），不设上限。
- 操作人与原因按 `AdminAuthFilter.printable` 的规则转义控制字符（同基线 `%q`，`rollback_logic.go:209-217`）；从不写令牌、密钥。

---

## 9 隐患与边界

### 9.1 基线自身（mmorpg 待做候选，交 mmorpg 侧裁决）

| 编号 | 问题 | 出处 |
|---|---|---|
| H1 | 回档栅栏没有接线，三个 Rollback* 恒回 16 | `servicecontext.go:72-82`；`rollback_logic.go:60-94` |
| H2 | 快照 / 回档读写的不是权威数据 | B1 |
| H3 | `source=1` 的 scene 快照没有读路径 | `snapshot_store.go:686-711` |
| H4 | PARTIAL 不带字段 = 全量回档 | `rollback_logic.go:728-739` |
| H5 | 全量回档不删快照之后新增的字段 | `data_logic.go:171-185` |
| H6 | 金币回收不了、也筛不出金币流水 | B2 |
| H7 | Create / List / Diff / Recall / Query / EventSnapshot 无鉴权；非 dry-run 回收无鉴权也能写审计行 | `dataserviceserver.go:281-362`、`:501-596`；`inventory/data.md:249` |
| H8 | `SnapshotInfo.fields` 从未填写 | `dataserviceserver.go:316-325` |
| H9 | 事件快照 type 5 不在枚举里 | `recall_logic.go:340` |
| H10 | dev/test 模式下 102–117 桩回「空成功」（经节点路由会被当成成功） | `player_rollback_handler.cpp:86-233` |
| H11 | `owe_amount` uint64 → int64 截断 | `player_rollback.proto:20` |
| H12 | dry-run 扣减行记 0；`player_ids` 不去重；交易两侧都在名单里时重复计数 | `recall_logic.go:86-109`、`:162-184` |
| H13 | 帮会之外的资产流（交易）没有回档闸 | §4.1 |
| H14 | `ListSnapshotsMeta` 无 limit 上限；`QueryLog` OFFSET 深分页、每次 `COUNT(*)` | §1.7 |
| H15 | 时间选源会选中 PRE_ROLLBACK 安全快照 | B5 |

mmorpg 待做候选：**M1**（H1–H3、H15）回档改以 scene 权威数据为源，栅栏可参考 Java 的归属夺权（Redis epoch CAS + go/db applied-epoch 守卫）；**M2**（H4 / H5）；
**M3**（H6 / H12）；**M4**（H7）；**M5**（H8 / H9 / H11）；**M6**（H13）交易流回档闸，可参考账本差集；**M7**（H10）实现之前 dev 模式也回 1006。

### 9.2 Java 移植时会踩的

1. **号源另开租约类型**：静默吞行（§2.3）。
2. **只恢复货币或背包、不恢复账本**：已应用的扣款消失或帮会重复记账（§4.5）。
3. **单独恢复任务**：已领奖励可再领（§4.5）。
4. **GM 快照按拍摄时刻记 `time_ms`**：帮会检查起点偏晚、漏行（§3.2）。
5. **时间选源包含安全快照**：回到「被覆盖之前」的错误状态（§3.3）。
6. **`useAffectedRows=true` 下的同毫秒无变化写**：被误判为失去围栏（§7.5）。
7. **为同事务另开数据源 / 用 pbmysql 自取连接**：安全快照与改写不再原子（§7.5）。
8. **帮会检查把非 OK 当「没有分歧」**：回档复制资产；`GuildInternalService` 的 0 不是成功（`guild_internal.proto(xm):45-47`）。
9. **栅栏释放早于写后复查**：玩家登录后新发生的操作会被误报（基线 `rollback_logic.go:392-393` 同理）。
10. **回收写出 `stack_size=0`**：scene 加载时丢弃并 WARN，与流水对不上（§5.5）。
11. **dry-run 结果当执行依据**：计划与执行之间余额会变；执行以作业内的实时读为准，应答以执行结果为准。
12. **xm-data 多副本各自跑作业**：单飞在库里（`ops_active`），不在进程里。
13. **5.2 未落地就上 7.2b**：`selectOwnerForUpdate` 与交出互斥都依赖 5.2。
14. **Druid 连接池 4**：作业事务与消费者争抢（§7.5）。

### 9.3 边界速查

| 情形 | 结果 |
|---|---|
| `ops.enabled=false` | 写接口 503；读与 dry-run 照常 |
| 号源租约失效 | 503 `id_unavailable`，零变更 |
| STARTED 写不进 | 503，零变更、不夺权 |
| 已有作业在跑 | 409 `ops_busy`（带在跑的 job_id） |
| 目标在线、reject | 明细 `player_online`，不写 |
| 目标在线、kick | 23 `{2017}` 断开；35 s 内夺到就继续，否则 `player_busy` |
| 夺到之后战斗锁仍在（离线结算在途，或 kick 踢下来的战斗中玩家） | 明细 `in_battle`，不写，放行开关无效；单人 / 多人当场释放、其余继续，整区整单 `zone_not_quiescent`。批次 6.3，§13.3 |
| 夺到之后读不到战斗锁（Redis 故障 / 超过 `battle-lock-wait`） | 明细 `battle_lock_unknown`，同样不写（fail-closed）；一块读失败后面的块不再发，没读到的人全部按读不到 |
| SECTIONS 选了 `assets`，两边未知字段不同 | 明细 `unknown_sections`，不写；改用 FULL 或先升级 xm-data |
| 写后复查问不到帮会 | 结果码 `post_write_unverified`，作业状态按写入结果定，数据不撤销，转人工 |
| 作业超时 | 写之前：全部释放、FAILED `job_timeout`；写阶段：停在当前玩家之后，FAILED / PARTIAL `job_timeout` |
| GM 持有期间玩家登录 | 2005，客户端重试 |
| 帮会服务不可达 / 超时 / 未装配 | 作业 FAILED `check_failed`，放行无效，零写入 |
| 帮会保留期拒绝 | 钳位重查；钳不到的玩家「不可证明」，可放行 |
| 账本损坏（任一侧） | 「不可证明」，可放行；SECTIONS 且现档损坏 → `state_invalid` |
| 被钉住的快照执行前被删 | `snapshot_gone`，不写 |
| 写后复查发现新 op | DIVERGED_AFTER_WRITE，数据不撤销，用 PRE_ROLLBACK 撤销或人工补偿 |
| xm-data 写阶段崩溃 | 已提交的玩家完整；未提交的无痕；30 s 后栅栏失效；作业 INTERRUPTED |
| 整区里一人夺不到，或夺到之后有人在战 / 读不到锁 | 全部释放，REJECTED `zone_not_quiescent`，零写入（在战人数在摘要 `battleLock` 里单列） |
| 回收候选 > 10000 | 422，零变更，记事件 |
| 回收时落库未追平 | 409 `ingest_lagging`（dry-run 只报告） |
| 同一源流水二次回收 | `already_recalled`，零变更 |
| 回收余额不足 | 扣到 0；缺口按 `shortfall` 报告或挂欠款 |
| 客户端发 96–117 | gate 计非法包、不回包、不断开（阈值内） |

### 9.4 对分区稿与盘点的勘误

**对分区稿**（都已回到代码核对）：

1. 一稿建议给 xm-data 新开租约类型 `NodeTypes.DATA`：**不成立**。雪花不含类型位（`Snowflake.java:6`），worker 会与 scene 重叠、撞号（§2.3）。另两稿的结论正确。
2. 一稿说时间选源「任何来源都可以，包括安全快照」：**不成立**，安全快照代表被覆盖之前的状态（§3.3）。
3. 两稿的 GM 快照 `time_ms` 都按拍摄时刻：会让帮会检查起点偏晚（§3.2），改为内容时刻。
4. 一稿认为 xm-data 连接串的 `useAffectedRows` 与 PlayerStore「影响行数与匹配行数一致」：同毫秒无变化写是例外（§7.5）。
5. 一稿用 `GET_LOCK` 做单飞、另一稿用可空唯一列：前者要在专用连接上持有整个作业、H2 没有；后者 pbmysql 不存 NULL。改为 `ops_active` 行（§7.3）。
6. 一稿提议 `idx_snapshot_zone` 与「快照所在 zone」选目标：整区目标改按归属区 `player.zone_id`（§4.9），该索引不再需要。
7. 三稿的流水索引方案各不相同：取 `(kind, item_config_id, time_ms)`、`(kind, currency_type, time_ms)`、`(item_uuid, time_ms)`（§7.3）；不建 `reason` 索引。
8. 一稿「执行期重选快照并检查 R5」与另一稿「计划时钉住」：取钉住（§4.4）。
9. 一稿「7.2 不提供踢人」与另一稿「显式 kick」：取显式 kick，缺省拒绝（§4.2，Q4）。
10. 一稿把事件快照单列 `EVENT=1003`：并入 GM_MANUAL（§3.2），原因值收敛为 1001 PRE_ROLLBACK、1002 PRE_GM_EDIT。
11. 一稿引用 `dataserviceserver.go:364-379` 为三个 Rollback* 的令牌检查：实际是 `:375-460`，令牌检查在 `:377`、`:413`、`:448`。
12. 一稿把账本差集作为独立放行开关之外的闸、另一稿只有帮会闸：取「三道检查、帮会与账本共用一个 `acceptDivergence`、回收逆转单独放行」（§4.6）。
13. 写后复查只复查帮会，不再算账本差集：持有归属期间账本只会被我们自己改写，复查它没有信息量。

**对盘点**（`docs/porting/inventory/`）：

- `data.md:198` txlog-ingest「java: missing」**已过时**：2.3a 已完成（`PARITY.md:67`）。
- `data.md:232`「先写 STARTED 审计 … 再持跨服务离线 epoch 栅栏」**顺序写反**：代码先持栅栏（`rollback_logic.go:532-536`）、后写 STARTED（`:538-550`）；整区 `:817` 之后才到 `:907-921`；
  全服 `:1100` 之后才到 `:1106`。
- `data.md:220`「这几条都不要求 x-admin-token」同样适用于 `QueryTransactionLog` 与 `CreateEventSnapshot`（`dataserviceserver.go:537-596`）；只有三个 Rollback* 要求令牌。
- `modules.md:199`「写类的冻结 / 战斗中闸」需要更准确：写类里 `GmCreateSnapshot` 刻意不过冻结闸（`player_rollback_handler.cpp:150-159`）；dev 模式下各桩回空应答，不是拒绝（H10）。

---

## 10 建议的有意差异（落地后逐条登记 PARITY）

| 编号 | 差异 | 理由 | 客户端可见 | mmorpg 待做 |
|---|---|---|---|---|
| D1 | 离线栅栏 = owner_epoch 夺权（单库、行级），不是跨服务栅栏 | Java 的归属协议本身就是原子的离线判定（§4.2）；基线栅栏未实现 | 是：持有期间 2005 | 是（M1） |
| D2 | 回档源 = scene 快照（`player_state` 原字节）；一张表一种格式一个枚举；PRE_* 不当时间点源 | 修 H2 / H3 / H15 | 否 | 是（M1） |
| D3 | 先 STARTED 后夺权（在线被拒也留痕）；归属持到 RESULT 之后 | 审计更完整 | 否 | 否 |
| D4 | 安全快照 + 覆盖写 + 流水 + 明细同一事务 | 基线两步之间失败会半写 | 否 | 可选 |
| D5 | FULL 整份替换；SECTIONS 固定段名，`assets` 不可拆、`mission` 依附 `assets`、空 SECTIONS 回 400 | 不变量 I3；修 H4 / H5；防复制 | 否 | 是（M2） |
| D6 | GM 封禁币种不随回档恢复 | 处罚状态不是资产 | 否 | 可选 |
| D7 | 在线缺省拒绝，可显式 kick（顶号通路 23 `{2017}`）；整区要求区服非 OPEN、一律 kick、任一夺不到整单拒绝 | 不新增 scene 接口；整区全有或全无 | 是：被踢 | 否 |
| D8 | 资产分歧检查三道：帮会（对齐基线）+ 账本差集（不等待、覆盖交易流）+ 回收逆转 | 修 H13；回收与回档互不抵消 | 否 | 是（M6，可选） |
| D9 | since 用毫秒、不取整到秒；快照计划时钉住、执行不重选（替代 R5）；差异给转移证据但不自动裁剪 | 精度；计划与执行一致 | 否 | 否 |
| D10 | 整区目标按归属区 `player.zone_id`；孤儿按 `created_at` 分两类，只报告 | Java 有权威列 | 否 | 否 |
| D11 | 回档 / 回收 / 精确回收写流水 16 / 19 / 17，与改写同事务 | 余额链首尾相接 | 否 | 可选 |
| D12 | 运维面全部令牌 + 操作人；`reason` 一律必填；写操作另加 `ops.enabled` 开关与 `Idempotency-Key` | 修 H7 | 否 | 是（M4） |
| D13 | 长操作是异步作业（202 + jobId），全集群单飞 | 30 s 沉降 + 10 s 复查，HTTP 同步不现实 | 否 | 否 |
| D14 | 回收真正执行；只认获得方行；金币可回收；跨作业去重；截断用 limit+1；执行前查整个消费组的落库完整性 | 修 H6 / H12；R-E | 否 | 是（M3） |
| D15 | 流水查询用游标、不回总数、升序；快照列表 limit ≤ 1000 | 修 H14 | 否 | 可选 |
| D16 | 快照保留期按原因分，约束流水保留 ≥ 快照保留 | 防止撤销依据被清 | 否 | 否（基线无清理） |
| D17 | 102–117 不进 scene，走运维面；欠款 / 精确回收离线编辑；追溯不给权威持有者 | §6.2 | 否 | 否 |
| D18 | 时间一律毫秒 | 沿用 Java 流水 / 快照口径 | 否 | 否 |
| D19 | GM 快照与差异的「当前」= 已落盘状态，`time_ms` = 内容时刻；应答标注落库时刻 | 不新增 scene 接口；时刻语义安全 | 否 | 否 |
| D20 | 事件快照不单列，并入 GM_MANUAL + note | 基线无调用方、枚举外值（H9） | 否 | 否 |
| D21 | 欠款数额按 uint64（基线 int64 截断） | 修 H11 | 否 | 是（M5） |

---

## 11 开放问题（各带推荐答案）

| 编号 | 问题 | 推荐 |
|---|---|---|
| Q1 | 批量回收是否真正执行（基线只有 dry-run） | **执行**（7.2c），离线编辑、去重、完整性闸；PARITY 记「mmorpg 待做」。保守备选是与基线一致回 501 |
| Q2 | 回收的货币缺口是否挂欠款（会唤醒两版都休眠的补缴机制，`PARITY.md:84`） | 提供 `shortfall=debt` 选项，**缺省 report**；与 Q3 同批（7.2c） |
| Q3 | 欠款 GM 指令（102 / 106 / 107 / 109 / 113）是否放进 7.2 | **放 7.2c**，只支持离线（可 kick）。`PARITY.md:84` 与 `player_state.proto:131-133` 都写明「随 7.2」；mmorpg 记待做 |
| Q4 | 在线玩家怎么办 | **缺省拒绝，显式 `ifOnline=kick`** 走顶号通路；在线不踢的编辑（需要 scene 新增内部管理接口、按 `xm:location` 路由）另列一批 |
| Q5 | 踢线用哪个 tip | 7.2 **沿用 2017**（不改契约）；若要「被运维踢下线」的专用 tip，先在 mmorpg 加、两版同批同步，记为 mmorpg 待做候选 |
| Q6 | 有了账本差集，沉降 30 s / 复查 10 s 要不要缩短 | **缺省与基线一致、做成配置项**；7.2 落地后按 Java `AssetOpLoop` 参数（每 2 s 认领、10 s 租约、退避上限 60 s，`architecture.md:491`）重算上界再定 |
| Q7 | 整区 / 全服回档是否要求区服先进维护态 | **要求**（非 OPEN，否则 409）；一律 kick；任一目标夺不到整单拒绝 |
| Q8 | 周期快照（基线 PERIODIC 无调用方，Java「周期存盘不拍」） | **7.2 不做**，`PERIODIC=3` 预留；连续在线多日的玩家回档点只能落到上线时那份 LOGIN，在 PARITY 写明限制。5.2 落地后可加 scene 选项「每 N 小时在线存盘成功后拍一份」，缺省关 |
| Q9 | 整区目标需要 `idx_player_zone`（xm-player-store，5.2 正在改该模块） | **7.2b 随迁移加索引**（5.2 落地后），不做全表扫 |
| Q10 | 物品回收的扣减顺序、是否扣装备栏 | 先扣流水记的 uuid，再 **3 临时格 → 0 人物背包 → 1 仓库 → 2 装备栏**，包内新到旧；**允许扣装备栏**（非法所得不因穿上身而豁免） |
| Q11 | 生产环境保留期缺省值 | **代码缺省保持 0（同基线）**；部署批次（7.6）给生产值，建议流水 180 天、LOGIN / LOGOUT 快照 90 天、GM / 安全快照永久，并受 §3.7 的启动约束 |
| Q12 | `SCENE_GUID` 被 xm-data 共用后名字不准 | **不改名**（改名 = 换键空间、只能停服切换），只改注释 |
| Q13 | 6.3 战斗 / 4.8 交易落地前，回档要不要额外查「在途战斗 / 托管」 | 初版答「不需要：夺权天然排除在线战斗；4.8 / 6.3 落地时各自实现 `AssetDivergenceChecker`」，**已被 lead 2026-10-05 的裁决取代**（批次 6.3 落地后「战斗中必在线」不成立）：① **在途战斗要查**——夺权成功之后、账本差集之前批量查战斗锁（`BattleLockReader.existsAll`），锁在的玩家明细 `in_battle`，对应基线回档对战斗中玩家回 1005；读不到的 fail-closed、明细 `battle_lock_unknown`；已随批次 6.3 落地（§13.3）。② **不建 `AssetDivergenceChecker` 接口**，三道检查内联在 `RollbackJob`；战斗结算不需要单独的分歧检查器——`battle_ledger` 随资产组整段回退，未销账的局回档后由待结算记录重投、相对快照恰好一次（`scene-battle-spec.md` S-11；§4.12）。③ 4.8 交易托管由账本差集自动覆盖，额外检查由 4.8 的规格另定 |
| Q14 | 112 的逐 uuid / 逐币种「补偿式恢复」（只补 `restorable` 的缺失项）是否 7.2 做 | **不做**；7.2 交付整段恢复 + 差异里的转移证据；补偿式等交易写侧（有了转移记录才有意义） |
| Q15 | 帮会数据走 Dubbo 还是直读 `guild_asset_op`（同在 `xm_java`） | **走 Dubbo**（4.5 为此交付的契约，归属清晰；直读越过服务边界） |
| Q16 | 兜底日志回灌工具放不放 7.2 | **放 7.2a**（`PARITY.md:67` 的 Java 待做，txlog-ingest 的收尾） |
| Q17 | 要不要「请 scene 立即存盘」的内部接口，让快照 / 差异的「当前」不滞后 | **不做**；需要精确时用 kick（scene 写回后再读） |

---

## 12 测试计划

**本机约束**：没有 Docker、没有 Go。Redis 8.10 / MySQL 8.4 / Kafka 4.3.1 作为普通进程运行，用 `D:/work/.tools/with-backends.sh <命令>` 统一启停（MySQL IT 先 `source env-local.sh`）。
GitHub Actions 可用（`luyuan-java/xuanming-server-mmo`，公开），但仓库还没有 `.github/workflows`（随 7.1）。**没有运行证据时不得声称「编译通过」「测试通过」**；
改了 `xm.audit` 枚举要 `clean install`。

### 12.1 单测（缺省就跑；`./mvnw -B -pl xm-data -am test`，及各模块）

| 编号 | 内容 | 对应基线用例 |
|---|---|---|
| T-G1 | xm-gate `MessageRoutesTest`：`SceneRollbackClientPlayer` / `DataService` / `LoginAdmin` 的**每个**方法 `clientRoute == null`（现有用例只取第一条非客户端方法，`MessageRoutesTest.java:187-188`）；`ClientDispatcherTest`：发 112 → `UNKNOWN_MESSAGE`、计非法包、不回包、连接仍可用 | `client_message_processor.cpp:874-886` |
| T-S1 | xm-scene：伪造 `ClientForward` 带 112 / 117 被丢弃、处理器不被调用；注册 112 启动即抛 | 意图同 `cpp/tests/currency_test/client_gm_gate_test.cpp` |
| T-Q1 | 流水查询：参数组合与走索引约束、游标续翻、金币（currency=0）能选中、半开窗口、limit+1 截断 | — |
| T-N1 | 号源：只从 `SCENE_GUID` 池取 worker；租约无效时不发号、写接口 503 | — |
| T-P1 | 快照直写：`time_ms` = `player_state.updated_at`、`ingested_at` = 现在、`owner_epoch` = `saved_epoch`；插入失败零变更 | — |
| T-P2 | `PlayerSnapshotDecoder`：新原因 3 / 5 / 6 / 1001 / 1002 与未知值都按数值原样落库 | — |
| T-P3 | `DataProperties`：保留期约束不满足拒绝启动；`purgeExpired` 不删 GM / 安全快照（替身 Mapper） | — |
| T-D1 | `StateDiff`：段级、路径级（500 条截断）、未知字段按字节、物品按配置聚合与逐实例、转移证据 | — |
| T-L1 | `LedgerDiff`：同纪元逐 seq；纪元变化 / 越过窗口 / S 缺流 / 账本损坏 → 不可证明；空对空 → 干净 | 新 |
| T-F1 | 栅栏：持有中且租约有效 → `player_online`、零写入；已释放 → 夺到；租约过期 → 夺到且旧写者 `saveStateHeld` 被拒 | `TestRollbackPlayer_OnlineFenceFailureDoesNotMutate`（`rollback_recall_test.go:231`）、`…WithoutCrossServiceFenceDoesNotMutate`（`:211`） |
| T-F2 | kick：发让出请求、退避重试、35 s 超时 → `player_busy`；GM 持有期间 login 得 Held、`OwnerTakeover{epoch=GM}` 不让任何 scene 释放；续约丢失后不再写；墓碑先于释放 | — |
| T-F3 | 与 5.2 交出互斥：交出在途时 GM 夺不到；源 scene 已死、GM 夺权后迟到的交出被围栏拒 | — |
| T-A1 | STARTED 写失败 → 零变更、不夺权；ACCEPTED 写失败 → 零写入；RESULT 写失败 → 上报、清扫器接手 | `:283`、`:308`、`TestRollbackGuildGate_D10_AcceptedAuditFailureBlocksWrites`（`:756`） |
| T-A2 | 单人事务任一步失败（含 `saveStateHeld` 0 行、明细条件更新不是 1 行），安全快照 / 流水 / 明细一起回滚 | `TestRollbackPlayer_SafetySnapshotFailureStopsBeforeOverwrite`（`:253`） |
| T-R1 | 选源：按 id 属于别人 → 404；按时刻含等号、同毫秒取号大的、白名单排除安全快照；钉住的快照被删 → `snapshot_gone` | `TestRollbackGuildGate_D13_OlderExecutionSnapshotIsNotWritten`（`:866`，Java 以钉住替代） |
| T-R2 | FULL 整份替换（快照后新增段被清）、blocked_types 保留；`sections` 不给 = FULL、给空列表 → 400、写 `currency` → 400、`mission` 不带 `assets` → 400；`assets` 资产组同时恢复（按描述符计算；`battle_ledger` 随资产组回退的正反两向用例 2026-10-06 已补：`RestoreBuilderTest`「战斗结算账本随资产组整段回退…」「不动资产组的部分回档_战斗结算账本留现档」，以及 `RollbackJobBranchesSqlTest` 落库层面的三例）；不认识字段：两边相同照留、不动资产组保留现档、选了 `assets` 而两边不同 → `unknown_sections` 并列出字段号（`RestoreBuilderTest` 三例） | H4 / H5 |
| T-R3 | 帮会检查逐条移植：没装配就拒（D2）、有分歧拒且零写入（D7）、部分回档也过闸、放行要 reason（D8）、ACCEPTED 先于第一笔写（D9）、整区第二块失败谁也不写（D11）、全服被拒一个 zone 都不写（D12）、写后复查（D14）、不可证明（D17）、两段等待用可注入的 sleeper / 时钟断言「检查在沉降之后」（D18）、超上限 / 沉降取消（`:1066`） | `rollback_recall_test.go:599-1086` |
| T-R4 | since：钳到 1、余量边界 [5 s, 1 h]、溢出 | `TestGuildSinceMs`（`:1087`） |
| T-R5 | `GuildInternalService` 应答：0 / ERROR / UNAVAILABLE / future 异常 → `check_failed`；RETENTION_REJECTED 钳位重查；游标不前进、块外玩家 → `check_failed` | guildcheck 14 个用例 |
| T-Z1 | 整区：区服 OPEN → 409；任一目标夺不到 → 全部释放、零写入；单人失败继续；孤儿两类；没有快照也报出当前玩家；空区 → `zone_empty`；超上限 → 422 | `TestRollbackZone_NoSnapshotsStillReportsCurrentPlayers`（`:180`） |
| T-C1 | 回收：截断 → 零变更 + 事件；指定玩家时查询失败不可跳过；审计失败上报；金币可作目标；扣减行不算候选；去重；`recall_source` 二次回收 → `already_recalled`；完整性闸（替身 Admin：位点落后拒、追平放行、无后续记录比 end offset） | `:361-458` |
| T-C2 | `PersistedStateEditor`：余额夹到 0、包顺序、减堆叠 / 删实例、从不写 0 堆叠、未知字段原样保留；xm-scene 加一条跨模块用例：编辑后的 `BagState` 交 `PlayerBags.restore(...)` 加载无异常 | — |
| T-E1 | 欠款规则逐条移植 C++：溢出、钳到 paid、`Long.MIN_VALUE`、调整时不存在则新建、冻结不存在的欠款不做事、`created_at` 只设一次、uint64 数额 | `currency_system.cpp:304-506` |
| T-J1 | 作业框架：幂等键同参 / 异参 / 缺失；单飞 409；心跳过期 → INTERRUPTED 且原线程自停；取消只在第一笔写之前有效；`opOf` 新路径映射；MockMvc 鉴权（沿用 `AdminEndpointSecurityTest`） | — |

### 12.2 H2 SQL（扩展 `AuditStoreSqlTest`，缺省就跑）

用生产建表脚本 + 新 Mapper：流水筛选组合、游标、排序；`findLatestAtOrBefore` 同毫秒并列与原因白名单；带 operator / note 的直写；按原因清理；`PlayerStore` 写方法在外层事务中加入与回滚。
H2 管不到：≥ 2^63 的无符号值、索引是否命中、pbmysql 表（依赖 information_schema）、`SELECT … FOR UPDATE` 的真实锁语义——这些放 §12.3。

### 12.3 真 MySQL（`-Dxm.it.mysql=jdbc:mysql://127.0.0.1:3306`，口令取 `XM_MYSQL_PASSWORD`）

- **迁移等价**：用迁移前的旧建表脚本（放测试资源）建库 → 执行迁移 → `SHOW CREATE TABLE` 规整后与新库逐字比较。
- **EXPLAIN 断言**：全服物品 / 货币回收、uuid 追溯、玩家选源四条查询的 `key` 分别是 `idx_txlog_item`、`idx_txlog_currency`、`idx_txlog_uuid`、`idx_snapshot_player`。
- **pbmysql**：`ops_*`、`recall_source`、`audit_replay_line` 的 `syncAll`；`ops_active` 重键 = 409。
- **§4.8 整个事务**：中途注入失败全部回滚；明细条件更新恰好 1 行；MyBatis 与 pbmysql 同连接（断言 `CONNECTION_ID()` 相同）。
- **`useAffectedRows` 回归**：用 xm-data 生产连接串跑夺权、续约、`saveStateHeld`（含同毫秒无变化写）、`saveStateAndRelease`、释放。
- **并发**：两线程分别以 login 身份与 xm-data 身份抢同一玩家；持有期间 login 一直 Held；租约过期后旧持有者的写被拒。
- **无符号边界**：余额、关联号 ≥ 2^63 的流水直写与读回。

### 12.4 Redis IT（`-Dxm.it.redis=redis://127.0.0.1:6379`）

- 让出请求发布 → 订阅端（测试替身模拟 scene）收到正确的 (player, epoch)。
- 位置墓碑按 (epoch, seq) 覆盖旧实例的 `o` 记录；旧 epoch 的写被忽略。
- xm-data 与 scene 共池占 `SCENE_GUID`，两边 worker 号不重叠。

### 12.5 Kafka IT（`-Dxm.it.kafka=127.0.0.1:9092`；本机 broker 删 topic 会停机，IT 用唯一 topic 名）

- 扩展 `KafkaAuditPipelineTest`：新原因值经 Kafka 往返落库；scene 发出的 LOGOUT 快照落库后被回档计划选中，回档后 `player_state` 字节与快照一致（除 blocked_types）。
- 完整性闸：排空后按 `forTimestamp` 判定为完整；暂停消费者后判定为不完整。
- 重复投递同一快照号，库里只有一行。

### 12.6 端到端 robot（本机切片 + 三个后端进程；`with-backends.sh` + `tools/local/start-slice.sh`）

**`rollback` 场景**（7.2b）：
1. 新号进场，GM 37 加金币 1000，LeaveGame；轮询 `/admin/player-snapshots` 直到 LOGOUT 快照 S1 落库。
2. 再进场，GM 加 500，保持在线；`ifOnline=reject` 回档到 S1 → 作业明细 `player_online`，54 仍是 1500。
3. `ifOnline=kick` 回档 → 收到 23 `{2017}` 并断开；作业执行期间（沉降内）尝试 EnterGame → 2005；轮询作业到 SUCCEEDED，带 `preSnapshotId`。
4. 重登，54 显示 1000；差异接口无差异；PRE_ROLLBACK 快照余额 1500；流水有一条 TX_ROLLBACK_RESTORE（before 1500、after 1000）；同幂等键重提返回同一 job_id。
5. 以 `preSnapshotId` 撤销 → 1500。

（上面是计划。落地的 `RollbackScenario` 用钻石、先以手工快照按号回档，2026-10-06 再补上按号撤销、按时刻选 LOGOUT 快照、持有期间 2005 与差异接口核对，共 19 项；
与计划的出入和运行结论见 §12.9、§13.4。）

**`recall` 场景**（dry-run 7.2a，执行 7.2c）：
1. GM 37 加 300 钻石（TX_GM_GRANT=9）与金币；dry-run 能选中两种（含金币）；窗口过大时 `result_truncated`、零变更（小 `max-rows` 配置）。
2. kick 执行 → 余额少 300，流水 TX_BATCH_RECALL；新幂等键再执行 → `already_recalled`、零变更。
3. 先花掉一部分再回收 → `shortfall` 记录；`shortfall=debt` 时之后 GM 加币可见 TX_DEFERRED_CLAWBACK。

**帮会联动**（复用 `guild-economy` 的准备步骤，xm-guild 在跑）：快照之后捐献一次 → 回档 REJECTED、附 1 条样本、零变更；带 `acceptDivergence` + reason → 成功、有 ACCEPTED 事件。

**102–117**（7.2a）：客户端直接发 112 / 115 / 117 原始帧 → 2 s 内无应答、连接仍可用（54 正常回），在非法包阈值以内；`XM_RUN_MODE=prod` 下同样。

回归：`audit`、`currency`、`guild-economy`、`guard` 全过。

### 12.7 CI（GitHub Actions）

- 7.1 建立「构建 + 单测 + 契约 `--check`」工作流；若 7.2 先于 7.1 落地，7.2 先提交一个最小工作流：Temurin 21 跑 `./mvnw -B test`。
- 另一个 IT job 用 `services:` 起官方镜像 `mysql:8.4`、`redis`、`apache/kafka`（KRaft 单节点），带 `-Dxm.it.mysql` / `-Dxm.it.redis` / `-Dxm.it.kafka` 跑。
  CI 的 MySQL 口令是工作流 `env` 里的一次性值，不是秘密。GitHub 托管 runner 能跑容器，本机不需要 Docker。Kafka 服务容器的 advertised listener 能否从 runner 访问，第一次运行时实测确认。
- 不用 Testcontainers（`tech-stack.md:27` 已按星数否决）。

### 12.8 基线侧

本机没有 Go，跑不了基线 Go 测试；用例清单照 §12.1 逐条移植（28 个回档 / 回收用例 + 14 个 guildcheck 用例）。mmorpg 的 `go-modules-ci.yml` 跑 `go test -count=1 ./...`
（`.github/workflows/go-modules-ci.yml:201-208`），**不带 `integration` tag**（`:78`、`:206`），所以 data_service 连真库的用例在 CI 里也不跑。本批不要求 mmorpg 改动；
§9.1 的待做若在 mmorpg 落地，只能靠它自己的 CI 验证，没有运行证据不得声称「已验证」。基线回档在生产上一律拒绝，没有可比对的运行行为；两版共享的客户端面只有「96–117 不可路由」，
Java 用 T-G1 钉住，基线有 `client_message_processor.cpp:874-886` 的白名单。

### 12.9 验收清单（每个子批）

- [ ] `clean install` 与相关模块单测全过，有运行证据。
- [ ] 本机 `-Dxm.it.mysql` / `-Dxm.it.redis` / `-Dxm.it.kafka` 中本子批涉及的 IT 跑过并留记录。
- [ ] robot 本子批场景与 §12.6 回归全过。
- [ ] §7.9 的文档与 PARITY 更新完成；有意差异按 §10 编号登记。
- [ ] 7.2b：`guild-economy-spec.md:1155` 的前置要求注明已兑现。

上面五条是每个子批通用的模板，不在模板上打勾；各子批的核对结果单列。

**7.2b 的核对结果（2026-10-05 收尾登记；逐项回到 §13 的运行记录与仓库现状核实，没有记录的不勾）**

- [x] 构建与相关模块单测：§13.1 的 `-pl xm-player-store,xm-scene,xm-login,xm-data install`（带三个 IT 开关）BUILD SUCCESS、0 失败；§13.2 评审修复之后
      `-pl xm-data test`（带三个 IT 开关）187 个用例、0 失败。
- [x] 已有的 IT 跑过并留记录：上面两次运行都带 `-Dxm.it.mysql` / `-Dxm.it.redis` / `-Dxm.it.kafka`（SQL 用例连真 MySQL、`useAffectedRows=true` 的连接串；
      `XmDataMysqlIntegrationTest` 含 M9 迁移等价与 `idx_player_zone` 的 EXPLAIN）。
- [x] §12.3–§12.5 里点名给 7.2b 的几条 IT（2026-10-05 登记时还没有用例，**2026-10-06 已补**，逐条回到代码核对过，见 §13.4）：§12.3 的「§4.8 整个事务」失败注入
      （`RollbackWriterSqlTest`）与 MyBatis / pbmysql 同一条连接同一个事务（`XmDataMysqlIntegrationTest`、`RollbackWriterSqlTest`）、login 与运维同时抢同一名玩家
      （`XmDataMysqlIntegrationTest`）；§12.4 的让出请求发布与位置墓碑的真 Redis 语义（`AdminOwnershipRedisIntegrationTest`、`RedisTakeoverRequestsTest`）；
      §12.5 的「LOGOUT 快照经 Kafka 落库后被回档按时刻选中」往返（`RollbackKafkaRoundTripIntegrationTest`）。
- [x] robot `rollback` 场景 10 项与回归场景（`smoke` `reconnect` `currency` `bag` `pet` `guild-economy` `audit`）在本机单节点切片上全过（§13.1）。
- [ ] robot 相对 §12.6 的缺口（2026-10-05 登记：没有走按 `targetTimeMs` 选 LOGOUT 快照、没有核对持有期间登录回 2005、没有「回档后差异接口无差异」、没有按 `preSnapshotId` 撤销、
      没有帮会联动，整区回档与 `/admin/zone-snapshots` 不在场景里；§13.2 的评审修复之后没有重跑本机切片）。**2026-10-06**：场景的检查项由 10 项扩到 19 项，补上了前四条
      （差异接口无差异、按 `preSnapshotId` 撤销、scene 发的 LOGOUT 快照经 Kafka 落库后按时刻回档、持有期间进游戏回 2005）；帮会联动、整区回档与 `/admin/zone-snapshots` 仍不在场景里。
      扩充之后的场景已在活切片上跑过（单、双 scene 各一遍，19 项全部通过，见 §13.4 的「最终验证」）；帮会联动与整区两项仍是缺口，所以这一条保持不勾。
- [x] 单测缺口（2026-10-05 登记时没有用例，**2026-10-06 已补**）：`ZoneSnapshotService` 的受理校验与作业体（`ZoneSnapshotSqlTest`、`ZoneSnapshotNoteTest`）；I2「安全快照 + 覆盖写 +
      流水 + 明细同生共死」的失败注入（T-A2，`RollbackWriterSqlTest`）；T-A1 的 ACCEPTED 写失败（`RollbackJobSqlTest`）与 RESULT 写失败（`OpsJobFrameworkSqlTest`）；T-R1 的
      `snapshot_gone`（`RollbackJobSqlTest`、`RollbackWriterSqlTest`）；`post_write_unverified`、连续 3 人失败即停、写阶段超时、回收逆转与 `acceptRecallReversal`、写阶段 `fence_lost`、
      FULL + 现档损坏（都在 `RollbackJobBranchesSqlTest`）；整区场景下的帮会分歧 / `check_failed`（`ZoneRollbackSqlTest`）；指标导出名与预建（`DataMetricsPrometheusTest`）；
      停机次序（`OpsLifecycleOrderTest`）。仍登记在案的残余见 §13.4。
- [x] 文档与 PARITY（§7.9）：`PARITY.md` 新增「GM 回档」行并更新 55 / 67 / 68 / 106 / 117 / 120 行，有意差异按 §10 编号登记；`docs/porting/roadmap.md` 7.2 行；
      `docs/design/architecture.md` §2 / §4.5 / §5 / §7 / §9 / §10 / §11；`docs/design/db-migrations.md` M9（随代码已写）；本稿正文按 §13.1「与本稿的出入」逐条回写。
- [x] `docs/design/tech-stack.md` 已登记「xm-data 自批次 7.2b 起是 `GuildInternalService` 的 Dubbo 调用方（编程式引用、只直连）」与 pbmysql 的新用途
      （`ops_active` / `ops_job_player` 启用、`ops_job.cancel_requested`）——随 7.2b 的登记提交补上（「服务间 RPC」「proto → MySQL 表映射」两行）。
- [x] `guild-economy-spec.md` §9.2 第 9 条已注明前置要求由 7.2b 兑现。
- [x] 批次 6.3 追加的「回档前查战斗锁」：已随 6.3 落地，实现、用例与模块级证据见 §13.3；全量构建与切片的结论见 §13.4「最终验证」。

---

## 13 实现记录

### 13.1 批次 7.2b（2026-10-05）：作业框架、离线栅栏、回档、整区维护前快照、`idx_player_zone`

开放问题按 §11 的推荐答案落地：Q4（缺省拒绝在线玩家，显式 `ifOnline=kick` 走现成顶号通路 → 23 {2017}）、Q5（沿用 2017）、Q6（沉降 30 s / 复查 10 s，
做成配置）、Q7（整区要求区服非 OPEN）、Q9（`idx_player_zone` 随本批迁移）、Q13（按初版答案「不额外查在途战斗」落地；这个答案随后被 lead 的裁决取代，
见 §11 Q13 与 §13.3）、Q14（不做逐 uuid 补偿式恢复）、Q15（帮会走 Dubbo）、Q17（不做「请 scene 立即存盘」）。
7.2c（回收执行、欠款、精确回收）不在本批。代码与测试随提交 `7dff75c` 入库（与批次 6.3 的主代码同一提交）。

**xm-data（新增 / 修改）**
- 作业框架 `com.game.data.ops`：`OpsJobService`（受理：参数校验 → 写开关 → 幂等键 → 发号 → **一个事务**插 `ops_active` + `ops_job`（QUEUED）+ STARTED，
  任一失败 503 零变更；重键 → 409 `ops_busy` 带在跑的作业号；同键同指纹回原作业、异参 409）、`OpsJobRunner`（`data-ops` 单线程执行；`data-ops-fence`
  每 5 s 心跳，槽被收走即 `JobContext.aborted()`、下一个检查点抛 `JobAbortedException` 自停；`data-ops-sweeper` 每个副本都跑，心跳超过 60 s 的作业按心跳值
  CAS 删槽、改 INTERRUPTED、追加 INTERRUPTED 事件；RESULT 与终态作业行同事务、失败重试 30 s，写不进就不让槽、停心跳交给清扫器；RESULT 之后才
  `afterResult` 释放归属）、`JobContext`（事件、检查点、可中断等待、超时、读库的取消标志）。`OpsJobStore` 补单飞槽 / 心跳 / 清扫 / 明细条件更新
  （`PLANNED → 终态` 必须恰好 1 行）/ 作业列表 / 取消。
- 离线栅栏 `com.game.data.ops.fence.AdminOwnership`：`PlayerStore.claimOwnership` 在 xm-data 自己的事务模板里调（不依赖 `@Transactional` 代理）；
  在线 + reject → `Online`；kick → 每次重试都重发让出请求（50 ms 起翻倍、封顶 800 ms）、`claim-wait`（35 s）内夺不到 → `Busy`；栅栏线程每 10 s
  `renewOwnerLeases`，续不上记 `lost`、之后不写也不写墓碑；释放 = 位置墓碑（`PlayerLocationDirectory.removeAsync(p, E', 1)`）→ `releaseOwnership`。
  作业收尾 `releaseAll` 并发发出全部墓碑、一起等至多 2 s 再逐个释放（Redis 不可用时不至于人数 × 超时）。让出请求发布方 `RedisTakeoverRequests`
  （同 xm-login 的频道与 `xm.api.OwnerTakeover` 消息）；`StrictClock`（严格递增毫秒，§7.5 的 `useAffectedRows` 坑）。
- 回档 `com.game.data.rollback`：`RollbackRequest`（§4.3 校验与规范化，指纹不含展开后的区号）、`RollbackSection`（段名规则，D5）、`RollbackPlanner`
  （§4.4 / §4.9；按号或按时刻白名单选源，没有快照的按 `created_at` 分两类、快照所在区与归属区不同的单列）、`RestoreBuilder`（§4.5；FULL 整份替换、
  `blocked_types` 保留现档，现档损坏 FULL 允许、SECTIONS → `state_invalid`）、`RollbackWriter`（§4.8 一个事务：加锁读归属 → 读现档与钉住的快照 →
  PRE_ROLLBACK 安全快照 → `saveStateHeld` → TX_ROLLBACK_RESTORE(16) 流水 → 明细 RESTORED）、`GuildDivergenceGate`（§4.6.1：只认 OK、保留期钳位重查一次、
  游标 / 块外玩家 / 10000 行上限 / 预算都算 `check_failed`；每块用块内最早起点问、按每人自己的起点过滤）、`DubboGuildInternalClient`、`RollbackJob`
  （§4.7 流程）、`RollbackService`（受理与 dry-run）。
- 整区维护前快照 `snapshot.ZoneSnapshotService`（作业 kind ZONE_SNAPSHOT，每批 500 人一个事务，PRE_MAINTENANCE，不需要写开关）。
- 接口：`POST /admin/rollbacks`（202 / dry-run 200）、`POST /admin/zone-snapshots`（202）、`GET /admin/ops-jobs[?status=&kind=&limit=]`、
  `GET /admin/ops-jobs/{id}`（含事件）、`GET /admin/ops-jobs/{id}/players?after=&limit=`、`POST /admin/ops-jobs/{id}/cancel`（要写开关）。
  `AdminAuthFilter.opOf` 补 `rollbacks` / `zone_snapshots` / `ops_jobs`。
- 装配：`PlayerStore` 由 `DataConfiguration` 定义（自动装配仍排除）、`@MapperScan` 补 `PlayerMapper`；`xm.data.ops.enabled=true` 时缺 `XM_DUBBO_SECRET` 拒启。
  配置项见 §7.2（环境变量 `XM_DATA_OPS_ENABLED`、`XM_DATA_OPS_MIN_TARGET_AGE`、`XM_DATA_OPS_CLAIM_WAIT`、`XM_DATA_ROLLBACK_SETTLE`、
  `XM_DATA_ROLLBACK_RECHECK_DELAY`、`XM_DUBBO_GUILD_URL`）。指标见 §8.2（`xm_data_ops_*`、`xm_data_rollback_*`、`xm_data_location_tombstones_total`）。
- 表：`ops_job` 加 `cancel_requested`（pbmysql 启动补列）；`PersistedPlayerMapper` 加 `findBrief` / `listInZone` / `countInZones`；
  `TransactionLogMapper.insertDirectAll`（普通 INSERT，撞主键回滚）。

**xm-player-store**：只改建表脚本，`player` 加 `KEY idx_player_zone (zone_id)`（`db-migrations.md` M9）；没有改任何 Java 代码与 scene / login 用到的行为。

**本机脚本与 robot**：`tools/local/start-slice.sh` 设 `XM_DATA_OPS_ENABLED=true`、`XM_DATA_OPS_MIN_TARGET_AGE=5s`、`XM_DATA_ROLLBACK_SETTLE=3s`、
`XM_DATA_ROLLBACK_RECHECK_DELAY=2s`；xm-robot 新场景 `rollback`（`RollbackScenario`）。

**与本稿的出入（实现时的取舍）**

lead 2026-10-05 裁决：**一律以实现为准，回写正文**（规格是 7.2c / 7.3 / 7.4 的依据，正文不能与代码相反）。下面每条末尾注明回写到了哪里；第 12–17 条是收尾核对时补记的
（实现时没有声明），第 11 条按裁决改写。

1. §7.1「把 `RedisOwnerTakeovers` 的发布部分挪成 xm-discovery 共用件」没做：本批期间 xm-discovery 由别的批次在改；xm-data 内实现
   `RedisTakeoverRequests`，频道与消息都取自共用定义，以后挪动只是搬家。——已回写 §7.1。
2. §7.1 `LedgerDiff` 仍在 xm-data（`com.game.data.snapshot.LedgerDiff`，7.2a 起就在这里），没挪到 xm-player-store 的 asset 包。——已回写 §4.6.2、§7.1。
3. 帮会 Dubbo 调用方用编程式引用（xm-api 的 `IsolatedDubboModule`，同 xm-scene / xm-guild 的资产通道客户端），不引入 dubbo-spring-boot-starter：
   只读与 dry-run 的进程不起 Dubbo，引用在第一次检查时才建。只支持直连 `xm.dubbo.guild-url`，为空 = 没有装配（一律 `check_failed`）；
   nacos 注册中心发现没接（部署批次再定）。——已回写 §4.6.1、§7.1、§7.2。
4. `sections` 不给 = FULL；给了空列表 → 400（§4.3 与 D5 两处说法不一，取「显式空列表拒绝」防误回全量）。——已回写 §4.3、T-R2。
5. dry-run 不做帮会检查（要沉降），只给计划、按现档预演的恢复内容与账本差集；整区 dry-run 不校验维护态，只列出各区状态。——已回写 §4.3、§4.9。
6. 写后复查问不到帮会时作业状态照写入结果定（SUCCEEDED / PARTIAL），结果码记 `post_write_unverified` 并 ERROR 日志（§7.7 没有给这种情形）。——已回写 §4.6.1、§4.7、§7.7。
7. 写阶段：连续 3 人写失败或号源失效就停，剩下的记 `not_executed`；作业超时在写之前 → FAILED `job_timeout`、写阶段 → 停在当前玩家之后。——已回写 §4.7、§4.9、§7.2、§7.7。
8. kick 分两轮：第一轮全部不踢地夺（离线的直接夺到），在线的一起发让出请求，第二轮再逐个带等待夺——总等待约为一次写回而不是人数 × 写回；
   整区两轮都夺完才裁决，`zone_not_quiescent` 带前 100 个玩家号与总数。——已回写 §4.2、§4.7、§4.9（含 13.2 第 6 条的共用截止时刻）。
9. 取消标志放在 `ops_job.cancel_requested`（多副本时取消请求可能落在别的副本上）；取消接口不带请求体、不要求 reason（操作人进审计日志）。——已回写 §7.3、§7.4、§7.6。
10. 回收逆转检查（§4.6.3）已接好（查快照之后 17 / 19 的扣减流水），7.2c 之前恒为干净。——已回写 §4.6.3。
11. SECTIONS 的 `assets` 组按描述符计算：`PlayerState` 里除 facing / attribute / mission / vitals 以外的全部字段（含以后新加的段；`battle_ledger = 9` 已随 6.3 落地），
    新段缺省归资产组（账本类字段必须与资产同写）；FULL 本来就整份替换。——已回写 §4.5。
    **`AssetDivergenceChecker` 接口没有建**（初稿 §7.1 把它列为 `com.game.data.rollback` 的接口，§4.12 / Q13 要求 6.3 落地时实现一个战斗结算的 checker）：
    三道检查内联在 `RollbackJob`。lead 裁决**不建接口**，战斗结算也不需要单独的分歧检查器——账本随资产组整段回退，未销账的局回档后由待结算记录重投、
    相对快照恰好一次（`scene-battle-spec.md` S-11）。本条初版末句「战斗结算账本的分歧检查按 Q13 是 6.3 自己的验收项」作废。——已回写 §4.6.3、§4.12、§7.1、Q13。
12. §7.1 列的三个类没有按原名出现：`OpsJobSweeper` → `OpsJobRunner.sweep`（`data-ops-sweeper` 线程）；`IdempotencyKeys` → `OpsRequests`（键与指纹的校验）+
    `OpsJobService.replay`（同键重放 / 冲突）；`RollbackExecutor` → `RollbackJob`（流程）+ `RollbackWriter`（单人写事务），恢复内容另由纯函数 `RestoreBuilder` 计算。
    ——已回写 §7.1；`data-tools-spec.md` 里对 `RollbackExecutor` 的四处引用同步改了。
13. Redis 没有「在启用写操作时立即连接」：`RedissonClient` 是懒加载 bean，由 `OpsIds` 在启动后的后台线程上申领发号租约时连上（连不上每 10 s 重试），不区分写开关、
    不同步等待、不 fail-fast。——已回写 §7.1。（`DataConfiguration` 里 `adminOwnership` / `gainBlockStore` 的注释原先写着「Redis 第一次用到时才连」，与此不符；已随批次 6.3 的收尾订正，§13.3。）
14. 指标不是「标签取值启动时预注册」：只有 `jobs_running`、`fence_held` 两个 Gauge 预注册，其余首次用到才注册。——已回写 §8.2。
    （批次 6.3 起有一个例外：`xm_data_ops_players_total{kind="rollback"}` 的 18 个结局装配时全部预建，§8.2、§13.3。）
15. 指标 `outcome` 的取值与初稿不同：逐玩家结局原样作标签，是 `player_online` / `player_busy` / `created_after_target`（不是 online / busy / created_after），并多出
    `player_not_found` / `snapshot_not_found` / `unknown_sections` / `id_unavailable` / `rejected` / `cancelled`；`claims_total` 多一个 `online`；
    `divergence_check_total` 没有 truncated / retention；`divergence_rows_total` 只在放行时累加。——已回写 §7.7、§8.2。
16. 整区回档的 RESULT / 摘要**没有逐 zone 计数**（`plan` 只有总数、已计划数、按结局的未计划计数与区不一致名单，`outcomes` 是逐结局计数）。——§4.9 改为「未做，留给 7.2c」。
17. dry-run 的 `ledger` 视图**没有结构化的逐流水位回退量**（只有 `clean` / `rows` / `unprovable[{stream, reason}]`，部分原因文本里带水位差）；整区 dry-run 不算账本差集。
    ——§4.6.2 改为「未做，留给 7.2c」，§4.3 按实现描述。

**测试证据**（缺省 H2；`-Dxm.it.mysql` 时同一套 SQL 用例连真 MySQL）
- 新增 `RollbackRequestTest`、`RestoreBuilderTest`（T-R2）、`GuildDivergenceGateTest`（T-R4 / T-R5）、`OwnershipFenceSqlTest`（T-F1 / T-F2 / T-F3：
  在线零变化、已释放 / 租约过期夺到且旧写者被拒、kick 每次重发、与 5.2 交出互斥、续约丢失后不写不墓碑、收尾墓碑并发）、`OpsJobFrameworkSqlTest`
  （T-J1 / T-A1：受理一个事务、号源失效零变更、槽被占不留作业行、心跳丢失自停且不覆盖 INTERRUPTED、清扫器 CAS、作业体异常照样收尾）、
  `RollbackJobSqlTest`（离线 FULL 一个事务写齐 + 封禁保留 + 墓碑先于释放 + 事件顺序、在线 reject 零写入、kick 成功、kick 等不到 player_busy、
  帮会没装配 / 非 OK → check_failed 放行无效、帮会分歧缺省拒绝与放行先写 ACCEPTED、写后复查 DIVERGED_AFTER_WRITE、账本差集拒绝、SECTIONS、
  按时刻选源排除安全快照且同毫秒取号大的、快照属于别人 404、多人 PARTIAL、幂等 / 写开关 / dry-run、ops_busy、沉降期间取消、按 preSnapshotId 撤销）、
  `ZoneRollbackSqlTest`（T-Z1：开放区 409、一律 kick 与没有快照的两类、一人夺不到全部释放零写入、空区、规模 422）、`RollbackAdminControllerTest`、
  `OpsWriteGateTest`（写开关打开缺 `XM_DUBBO_SECRET` 拒启、缺省值、越界拒启）；
  `XmDataMysqlIntegrationTest` 加 M9 迁移等价与 `idx_player_zone` 的 EXPLAIN；`AdminAuthFilterTest` 补新路径。
- `-pl xm-player-store,xm-scene,xm-login,xm-data install -Dxm.it.mysql=... -Dxm.it.redis=... -Dxm.it.kafka=...`：BUILD SUCCESS（xm-player-store 55、
  xm-login 170、xm-scene 835（跳过 1）、xm-data 172，0 失败）；资产组改为按描述符计算之后 xm-data 单模块带同样的 IT 开关重跑 173 个用例、0 失败。
- 本机单节点切片（slice-run.sh）：`smoke` `reconnect` `currency` `bag` `pet` `guild-economy` `audit` `rollback` 全部通过；xm-data 0 条 ERROR。
  `rollback` 10 项：下线手工快照、离线回档 SUCCEEDED + RESTORED、同键重提回同一作业、重登 1000、在线 reject → REJECTED player_online 余额与连接不变、
  在线 kick → 23 {2017} 后 SUCCEEDED、重登 1000、PRE_ROLLBACK 快照里 1300、回档流水 1300 → 1000（关联号 = 作业号）。
  本机库此前没有执行 M8（7.2a 的迁移），第一轮手工快照因此 503；按 `db-migrations.md` 执行 M8 / M9 后通过。
- 没有在 robot 里显式核对「GM 持有期间登录回 2005」（login 的既有行为，PlayerFlow 会自动重试 2005）。

### 13.2 批次 7.2b 评审修复（2026-10-05）

评审确认的 6 条（作业框架 3 条、回档 3 条），只改 xm-data，表结构不变（不需要新迁移）。

**作业框架（`OpsJobRunner` / `OpsJobStore` / `OpsJobService` / `JobContext`）**
1. 清扫改成**一个事务**：按心跳值 CAS 删槽 → 未终结的作业行改 INTERRUPTED → 改到了才追加 INTERRUPTED 事件；任一步失败整体回滚（槽还在，下一拍重来），
   不再出现「槽已删、作业行永远停在 RUNNING、没有中断事件也没有指标」。作业已终结、只是槽没让出（让槽失败、或 `afterResult` 释放期间停机）的，
   只收回槽、WARN 一行，不追加事件、不记 `jobs_total{outcome=interrupted}`（原来会对成功的作业误报中断）。清扫的事件序号与仍活着的执行线程撞号
   （`DuplicateKeyException`）时整个事务重来（至多 3 次）；执行线程写事件撞号时按库里的最大序号重排再写。
2. 开始执行先核对：一个事务里刷新心跳（槽必须仍属于它）并 `QUEUED → RUNNING`（带状态条件），任一不成立就回滚、作业体一步也不执行——
   排队超过 `stale-after` 已被清扫器中断的作业不会再被改回 RUNNING、在别的作业占着槽时夺权 / 踢人。心跳刷新之后清扫器至少 `stale-after` 内不会收走它。
3. 作业行不再整行覆盖：`markRunning`（`QUEUED → RUNNING`）与 `finishJob`（只写结局列）都按列、带 `status IN (QUEUED, RUNNING)` 条件更新，
   不抹掉受理线程（可能在别的副本上）写的 `cancel_requested`，也不把清扫器写的 INTERRUPTED 改回去；`failUnsubmitted` 同样。心跳丢失时作业行
   还没终结（清扫器没改到，例如槽被人工删掉）由执行线程兜底改成 INTERRUPTED。**谁把作业行改成终态谁记 `jobs_total`**（原来心跳丢失时清扫器与
   执行线程各记一次）。审计 `[OpsJob] RESULT` 行加 `row=finalized / event_only / not_written`。
4. 心跳写 `GREATEST(heartbeat_ms + 1, now)`：§7.5 的 `useAffectedRows=true` 下，同一毫秒的第二次心跳（受理与开始执行落在同一毫秒）原来数出 0 行、
   被误判为槽已被收走。
5. 取消幂等：标志本来就是 1 时 UPDATE 数出 0 行（`useAffectedRows=true`），原来第二次取消会回「作业已终结，取消无效」；改为 0 行时回读，
   未终结且标志已置上仍回 `cancelRequested=true`。取消写一行 `xm.audit.ops`（`[OpsJob] CANCEL`；操作人仍由 `xm.audit.admin` 记）。

**回档（`RollbackJob` / `RollbackService` / `RestoreBuilder`）**
6. kick 第二轮**共用一个截止时刻**（让出请求发出时起算一个 `claim-wait`）：每人仍有完整的 claim-wait 等 scene 写回，截止之后每人只再试一次
   （照样重发让出请求）。原来每人各等一个 claim-wait，持有者不响应让出（发布失败、订阅断了、逻辑线程卡住而续约照常）时总等待 = 在线人数 × 35 s，
   第一轮已夺到的离线玩家被扣着（登录回 2005），整区还可能拖到 `job-timeout` 变成 FAILED 而不是 `zone_not_quiescent`。两轮夺权循环里每 1 s 读一次
   取消标志（原来夺权期间取消要等两轮都夺完才生效）。13.1 第 8 条「总等待约为一次写回」现在在退化情形下也成立。
7. 作业时限：沉降之后、放行（ACCEPTED）之前各查一次，超时即全部释放、零写入、FAILED `job_timeout`（不问帮会、不写 ACCEPTED）；写阶段超时一个也没写成
   → FAILED `job_timeout`、写成了一部分 → PARTIAL `job_timeout`（写后复查问不到帮会时仍是 `post_write_unverified`，摘要带 `timedOut`）。
   原来沉降 / 帮会检查期间超时会在写循环里把人全记 `not_executed`，作业报成 REJECTED + 第一个明细结局（如 `player_online`），看起来像规则拒绝。
8. 整区 dry-run 先数人（与执行同一个 `countInZones`），超过 `max-players-per-job` 回 422 `plan_too_large`；原来在 Tomcat 线程上对整个范围逐人查快照、
   建全量计划之后才报 `tooLarge`（`tooLarge` 字段保留，作并发增长的兜底）。
9. SECTIONS 选了 `assets`，而快照与现档在 `PlayerState` 顶层有本版本不认识、且两边不同的字段（滚动升级时较新的 scene 写下的新段，可能是新账本）
   → 这名玩家不写，明细 `unknown_sections`（新的逐玩家结局，`xm_data_ops_players_total` 的 outcome 固定集合加一个值），dry-run 同样标出并列出字段号。
   资产组按本版本的描述符计算（13.1 第 11 条），不认识的段既判断不了是否账本、也不会随资产一起恢复——资产回到快照而账本留在之后会重复记账或扣款消失，
   所以 fail-closed：先升级 xm-data，或改用 FULL（整份替换，未知字段随快照）。两边相同、或不动资产组的 SECTIONS，照旧保留现档的未知字段。
   **与 §4.5 正文的出入**：「SECTIONS 保留当前状态里不认识的字段」只对不动资产组、或两边未知字段相同的情形成立。lead 2026-10-05 裁决回写——已回写 §4.5
   （`unknown_sections` 一段）、§7.7（逐玩家结局表）、§9.3、T-R2。

**测试证据**
- `OpsJobFrameworkSqlTest` 新增 7 例：清扫中途失败整体回滚、槽还在、下一拍重来；已终结作业只收回槽、不追加事件、不记中断；排队期间被中断的作业
  开始执行时不跑作业体、只追加 RESULT、中断只计一次；槽不属于它而作业行仍是 QUEUED 时兜底改 INTERRUPTED；取消标志不被执行线程覆盖、排队时的
  取消保留、重复取消仍报已请求；作业行被清扫器终结后执行线程只追加 RESULT、不覆盖、不重复计数；同一毫秒连续心跳仍判定槽属于它。
- `RollbackJobSqlTest` 新增 3 例（5 名在线 kick 持有者都不放：每人 `player_busy`、耗时 < 3 s 而不是 5 × 1 s；沉降之后已超时 FAILED `job_timeout`、
  零写入、不问帮会、不写 ACCEPTED；SECTIONS + assets 两边未知字段不同 → `unknown_sections`、零写入、dry-run 标出、FULL 不受影响），
  沉降期间取消的用例补「重复取消仍报已请求、终态行保留取消标志」；`ZoneRollbackSqlTest` 新增 2 例（整区 4 名在线都夺不到 → `zone_not_quiescent`、
  耗时 < 3 s；整区 dry-run 超上限 422）；`RestoreBuilderTest` 按新规则拆成三例（两边相同照留 / 不同拒绝并列出字段号 / 不动资产组保留现档）。
- `-pl xm-data test -Dxm.it.mysql=jdbc:mysql://127.0.0.1:3306 -Dxm.it.redis=redis://127.0.0.1:6379 -Dxm.it.kafka=127.0.0.1:9092`：187 个用例、0 失败
  （SQL 用例连真 MySQL、`useAffectedRows=true` 的连接串）；改动涉及的 4 个测试类另在缺省 H2 下跑过，0 失败。没有重跑本机切片。

### 13.3 批次 6.3 追加：回档前查战斗锁（已随 6.3 落地）

本节先记裁决与设计，再记落地的实现（「落地」一段按工作区代码逐条核对过，2026-10-06）。7.2b 的提交（`7dff75c`）里没有这段逻辑，它随批次 6.3 的收尾入库（提交 `4f3f345`）。

**为什么要补**。本稿初版（§4.2、§4.12、Q13）以「回合制战斗中的玩家必在线，所以夺权夺不到，基线的战斗闸自动满足」为前提。批次 6.3 落地后这个前提不成立：

- Java 断线即写回、释放、移除实例，战斗在 xm-battle 继续，结算可以在玩家离线时到达（`scene-battle-spec.md` §0.1、§7.15）。所以 `ifOnline=reject` 的回档
  也能夺到「战斗锁仍在」的离线玩家。
- `ifOnline=kick` 走的顶号通路（`SceneWorld.onTakeoverRequested`）不看是否在战斗，在线战斗中的玩家同样被写回、释放、踢下线，随后被夺到；整区回档一律 kick。
- 6.3 的规格本来就把这件事留给 7.2：`scene-battle-spec.md` §0.3（6.3 给 7.2 只提供 `BattleLockReader`，对方负责「GM 回档 1005」）、§7.13 第 5 条
  （「7.2 钩子：GM 回档 / 回收判 → 1005」）。7.2b 落地时没有接。

**裁决**（lead，2026-10-05）：接钩子。

**设计**

1. **位置**：`RollbackJob` 两轮夺权都做完之后、账本差集之前，对**全部已夺到的玩家**批量查一次战斗锁——xm-discovery 的 `BattleLockReader.existsAll`
   （键 `RedisKeys.battleLock(pid)`，锁是 Hash，逐键 EXISTS 并发发出）。放在这里一次覆盖两种情形：reject 夺到的离线玩家，以及 kick 踢下来的战斗中玩家。
2. **命中**：锁在的玩家不写，明细结局 `in_battle`，对应基线回档对战斗中玩家回 1005（`player_rollback_handler.cpp:68-80`：战斗快照已经发出去，这时改背包与属性，
   结算按账本扣药会「不足按 0」，属性会被结算的 HP / MP 终值整体覆盖）。
3. **读失败 fail-closed**：`existsAll` 任何一个键读失败都整体以异常完成；读不出就判定不了谁在战斗，一律按不能写处理，不把读失败当成「不在战斗」
   （AGENTS.md §3：玩家资产与归属路径默认 fail-closed）。落地时读不到的玩家记单独的结局 `battle_lock_unknown`，没有并进 `in_battle`（本节初稿写的是并进）：
   一个是规则拒绝、一个是 Redis 故障，分开记告警才分得出。
4. **仍然先 STARTED、后夺权、再查锁**：查锁在 I3（STARTED 之前不踢人不夺权）与 I4（检查通过之前不写）之间，不改变任何既有不变量；被 kick 的玩家即使随后因
   `in_battle` 没被回档，也已经被踢下线——这是 kick 的既有语义，运维应当先 dry-run、在维护窗口里做整区回档。
5. **与 S-11 的关系**：不查锁也推不出重复发奖——`battle_ledger` 随资产组整段回退，未销账的局由待结算记录重投、相对快照恰好一次（§4.12）。查锁是为了与基线的安全门一致、
   不在一局战斗的中途换掉它开局时拍下的状态。它不是资产分歧检查，不走 `acceptDivergence`（基线对战斗中的玩家同样是无条件拒绝）。

**落地**（`com.game.data.rollback.BattleLockGate`，由 `RollbackJob` 的 2b 步与 `RollbackService.dryRun` 调用；正文 §4.2 / §4.3 / §4.7 / §4.9 / §4.12 / §7.2 / §7.5 / §7.7 / §8.2 / §9.3 已按下面回写）

- **闸本身**：无状态、线程安全。接缝是 `BattleLockGate.Reader`（`existsAll(玩家号集合) → future<玩家号 → 锁在不在>`，允许同步抛异常），生产实现是 xm-discovery 的
  `BattleLockReader.existsAll`。`check(玩家号, 检查点)` 在调用线程上限时等：去重、保持入参顺序、每块 500 人分块发出（免得整区上万条命令一次压给连接池）、
  **全程共用一个截止时刻**（`xm.data.ops.battle-lock-wait`，缺省 5 s）；任何一块读失败就停，之后的块不再发，已读到的结论保留，没读到的人全部按读不到。
  读不到的来路：读取方同步抛异常、返回 null future、future 异常完成、超时（并取消那次读）、被取消、线程被中断、应答为空、应答里没有这个人。检查点抛出的异常（心跳丢失）原样抛给作业。
- **三种结论**：锁在 → `in_battle`；读不到 → `battle_lock_unknown`；锁不在 → 可写。前两种都零写入，**无条件**：不读 `acceptDivergence` / `acceptRecallReversal`，
  也不看回哪些段（只回非资产段同样拒绝），不问帮会、不进分歧裁决，不计 `xm_data_rollback_divergence_check_total`。
- **在作业里的位置**：两轮夺权都做完之后、CLAIMED 事件与账本差集之前；只查已夺到的人（没夺到的、没有快照的不查）。夺到之后不会再开出新的一局：备战要有活实例，
  活实例要先拿到归属；备战写锁途中被踢走的，scene 在写锁回调里发现人已离场会自己删锁（`scene-battle-spec.md` §7.5 第 4 步）。
- **单人 / 多人**：被挡的人写明细后**当场释放**（新增 `AdminOwnership.releaseMany`：墓碑并发发出、一起等，再逐个带围栏释放——逐个释放在 Redis 不可用时是人数 × 超时，
  而读不到锁多半正是 Redis 不可用），不陪其余的人等沉降；其余继续（可能 PARTIAL，帮会只问要写的人）。一个可写的也不剩 → REJECTED：夺权阶段有人没夺到时结果码仍取那个结局
  （如 `player_online`），都夺到了而全被锁挡下才是 `in_battle` / `battle_lock_unknown`。读不到锁的作业是 REJECTED 不是 FAILED（同夺权访问库出错的先例）。
- **整区**：见 §4.9。被挡的人只记明细，其余已夺到的记 `rejected`，归属一起在收尾时释放；整单 `zone_not_quiescent`。这条路径在写 CLAIMED 事件之前返回，
  所以整区被战斗锁挡下时事件序列里**没有 CLAIMED**（与原来有人夺不到时一样），信息全在摘要的 `battleLock`；单人 / 多人被挡时有 CLAIMED 且带 `battleLock`。
- **事件与摘要**：没有新增事件类型。CLAIMED 事件的 `claimed` 是查锁之前夺到的人数（含被挡的），新增 `battleLock` 视图；走到写阶段的摘要、被锁全部挡下的 REJECTED 摘要、
  `zone_not_quiescent` 的摘要都带 `battleLock`。
- **dry-run**：见 §4.3。在请求线程上读（本节初稿写的「读锁不上 Tomcat 线程」只对作业成立）；在战的玩家照样给恢复预演。
- **装配**：`DataConfiguration.battleLockGate` 经 `ObjectProvider<RedissonClient>` 在每次读锁时才取客户端（同 `RedisTakeoverRequests` 的接法），装配期不连接；
  取不到按读不到处理。它读的是 xm-data 自己的 Redis 客户端，**前提是 xm-data 与 xm-scene 用同一个 Redis 实例与库**（都是 DB 12）：配成不同的库时闸读到的是空键空间、
  会把所有人当成不在战，没有自检。
- **指标与配置**：两个新结局进 `RollbackJob.PLAYER_OUTCOMES`（共 18 个值，装配时预建，§8.2）；新配置键 `xm.data.ops.battle-lock-wait`（§7.2，yaml 已写）。没有新增指标名。
- **顺带改的**：`AdminOwnership` 类注释里「回合制战斗（必在线）都夺不到」已改成「夺到不等于不在战斗」；`DataConfiguration` 里 `adminOwnership` / `gainBlockStore`
  两处「Redis 第一次用到时才连」的注释按事实改写（§13.1 第 13 条括号里的「代码侧另行订正」已办）。
- **运维提示**：被 `in_battle` 挡下后等锁放掉再重提。锁的 TTL = 战斗期限 + 60 s，结算应用之后至少保持 180 s、落盘销账时放掉；离线玩家的锁过期后可以回档，
  待结算记录在他下次进场时应用到回档后的状态上（S-11）。被 kick 的玩家即使随后因战斗锁没被回档，也已经被踢下线（kick 的既有语义，上面设计第 4 点）。
- **残余**：
  - `BattleLockReader.existsAll` 走 Redisson 的普通读路由，是咨询性的读；这道闸却是安全门。现在是单机 Redis，没有影响；**上主从 / 集群之前**必须给它一个读主库的批量入口，
    或把 Redisson 的 `readMode` 设成 MASTER，否则复制延迟下读到从库的「没有锁」会放行一次本该拒绝的回档（`scene-battle-spec.md` §10.5、`architecture.md` §6）。
  - 夺权结束到查锁之间没有再查作业时限与取消标志，查锁最多多占一个 `battle-lock-wait`；之后的检查点不变。
  - 预建只覆盖 `kind=rollback`；7.2c 新增作业种类时要各自登记自己的结局集合，回收 / 精确回收 / 欠款的写操作也要过同一道闸（`BattleLockGate` 与 `releaseMany` 可直接复用）。
  - `DataConfiguration` 整体在 Spring 容器里的装配没有用例（既有缺口，要库与 Kafka）；`battleLockGate` 这一颗有单独的装配用例。robot 没有「在战玩家回档被拒」的场景。

**用例**（类名与覆盖面；条数以最终验证为准）

- `BattleLockGateTest`：三态与入参顺序、读失败的各种来路、应答缺人、超时并取消、500 人分块、一块失败即停、全程共用截止时刻、检查点抛出、等待必须为正。
- `RollbackBattleLockSqlTest`（缺省 H2，`-Dxm.it.mysql` 时连真 MySQL）：reject 夺到的离线在战玩家与 kick 踢下来的在战玩家都零写入并释放；不在战的照常回档、锁恰好读一次；
  读失败四种来路都 fail-closed；多人里在战的当场释放、其余照写；只查夺到的人；**无条件拒绝的矩阵**（四种段选择 × 两个放行开关的四种组合，每个作业都预置了真实的帮会分歧与回收扣减，
  一律 `in_battle`、不问帮会、没有 CHECK / ACCEPTED / WRITE 事件）及其对照（同样的请求没有锁时由两个开关裁决）；整区在战 / 读失败 / 夺不到与在战并存；dry-run 四例；指标预建。
- `BattleLockWiringTest`：装配期不取 Redis 客户端、取不到按读不到处理、逐人 EXISTS 的键是 `xm:battle:{pid}:lock`、等待上限确实取自配置键。
- `BattleLockGateRedisIntegrationTest`（`-Dxm.it.redis`）：用 scene 的同一段脚本造锁，备战中与战斗中都判在战，锁删掉之后不再挡；一千多人分块读完。
- 另：`OwnershipFenceSqlTest`（`releaseMany`）、`OpsWriteGateTest`（`battle-lock-wait` 的缺省与校验）、`DataMetricsPrometheusTest`（18 个结局的预建）。

**模块级证据**（单模块、`-o` 离线，2026-10-05 / 06；命令前缀 `mvn-locked.sh xm-data … -pl xm-data test`，日志在 `D:/work/.tools/logs/`）：实现之后缺省 H2 的整模块
`Tests run: 223, Failures: 0, Errors: 0, Skipped: 16`（`data-6.log`）、加 `-Dxm.it.mysql` 的整模块 `Tests run: 223, Failures: 0, Errors: 0, Skipped: 10`（`data-7.log`）；
评审修正（补「无条件拒绝」矩阵与配置接线两组用例）之后缺省 H2 的整模块 `Tests run: 228, Failures: 0, Errors: 0, Skipped: 16`（`datafix-full-1.log`），
三个相关类加真 MySQL / Redis `Tests run: 26, Failures: 0, Errors: 0, Skipped: 0`（`datafix-it-1.log`）。变异核对：不查锁、读不到当成不在战、整区被挡不拒、被挡的人不当场释放、
每块各等一遍、dry-run 不标、`releaseMany` 放掉全部、装配期就取客户端、放行开关开着就不查、不回资产组就不查、等待上限接错——每一处都有用例判红（`data-mut-*.log`、`datafix-mut-*.log`），
主代码逐次按摘要值还原。带真依赖的全量构建与 robot 切片的结论见 §13.4 的「最终验证」。

### 13.4 7.2b 的后续（2026-10-06，随批次 6.3 的收尾）

7.2b 登记（提交 `70b4b4b`）之后的事实，逐条回到代码核对过；代码与用例随批次 6.3 第一轮的提交 `4f3f345` 入库。

**补齐的测试缺口**（§12.9 里 2026-10-05 登记为「另行安排」的各条）

- `RollbackWriterSqlTest`：§4.8 单人写事务的失败注入——归属被夺 / 已释放 / 玩家行不存在、快照被删或属于别人、快照或现档损坏、未知字段不同、号源在取安全快照号或取流水号的中途失效、
  安全快照 / 覆盖写 / 流水 / 明细任一步失败或行数不符，全部零残留；MyBatis 与 pbmysql 在同一条事务连接上、一起回滚（I2，T-A2）。
- `RollbackJobBranchesSqlTest`：`post_write_unverified`（含 PARTIAL）、连续 3 人写失败即停与「失败不连续不算」、号源失效立即停、写阶段超时（FAILED / PARTIAL `job_timeout`）、
  帮会检查做完才超时不写 ACCEPTED、回收逆转缺省拒绝与 `acceptRecallReversal` 放行（`acceptDivergence` 代替不了）、沉降期间归属被夺走（续约已发现 / 还没发现两种）、现档损坏的 FULL 与 SECTIONS、
  运维持有期间 login 的夺权一直得到 Held、`battle_ledger` 随 `assets` 回退的三例。
- `ZoneSnapshotSqlTest` / `ZoneSnapshotNoteTest`：整区维护前快照的受理校验、幂等键、写开关无关、只拍命中区且不夺权、分页（每批 500）、空区、号源中途失效、取消与超时在批与批之间生效、
  与回档共用单飞槽、拍出的快照能被回档按时刻选中；备注与作业号的拼接。
- `OpsJobFrameworkSqlTest` 补 RESULT 写不进的两例（预算用完放弃并交给清扫器 / 退避重试后写进）；`RollbackJobSqlTest` 补 ACCEPTED 写不进零写入、钉住的快照被删（T-A1 / T-R1）；
  `ZoneRollbackSqlTest` 补整区下的帮会分歧、`check_failed`、第二块检查失败谁也不写。
- 真依赖：`AdminOwnershipRedisIntegrationTest`（让出请求的字节与 login 发的相同、每次重试重发、位置墓碑按 (epoch, 序号) 盖过旧记录而不抹掉更新的进场，§12.4）、
  `RedisTakeoverRequestsTest`、`RollbackKafkaRoundTripIntegrationTest`（scene 的 LOGOUT 快照经 Kafka 落库后被回档按时刻选中、写回的字节与快照一致，§12.5）、
  `XmDataMysqlIntegrationTest`（同一事务里两套映射同一条连接、login 与运维同时抢同一名玩家每轮恰好一方夺到，§12.3）。
- `DataMetricsPrometheusTest`（两个 Timer 的导出名是 `xm_data_ops_job_seconds` / `xm_data_rollback_guild_check_seconds`、逐玩家结局的固定集合、两个 Gauge 装配时就在）、
  `OpsLifecycleOrderTest`（停机次序，§7.4）。

**修掉的缺陷**

- `ZoneSnapshotService`：备注过长时拼上的 `job:<作业号>` 被列宽截掉。现在截的是备注、保住作业号（`noteWithJob`，上限 `NOTE_MAX = 256`，不在代理对中间下刀）。
- `PersistedPlayerMapper`：区号（`INT UNSIGNED`）按无符号绑定与读取（`UnsignedIntTypeHandler`）。此前 ≥ 2^31 的区号被当成负数发给数据库，整区回档与维护前快照一行也查不到；
  `XmDataMysqlIntegrationTest` 加了一条真 MySQL 用例。

**新增的包内接缝**：`OpsJobRunner` 多一个包私有构造器，末参 `resultRetryBudget` 让「RESULT 一直写不进」的用例不必等满 30 s；公开构造器恒传 30 s，生产行为不变。

**robot `rollback`**：检查项由 10 项扩到 19 项——离线回档后差异接口无差异、按 `preSnapshotId` 撤销并重登核对、scene 发的 LOGOUT 快照经 Kafka 落库后按 `targetTimeMs` 预演与执行、
运维持有期间进游戏先回 2005。帮会联动、整区回档与 `/admin/zone-snapshots` 仍不在场景里（§12.9）。

**没修、只登记的**：RESULT 每重试一次多占一个事件序号（序号不连续，不影响顺序）；`RollbackJobBranchesSqlTest` 有 3 例依赖「2 s 作业时限 + 2.4 s 睡眠」的墙钟，机器极端繁忙时理论上可能误报；
回收逆转检查里 TX_BATCH_RECALL(19) 这一原因、获得方方向的行不算逆转、超过 100 条时的截断没有专门用例（17 / 19 的写入方随 7.2c）。

**模块级证据**（同上的命令前缀，2026-10-06）：补齐之后缺省 H2 的整模块 `Tests run: 304, Failures: 0, Errors: 0, Skipped: 24`（`d72b-8.log`），
加 `-Dxm.it.mysql` / `-Dxm.it.redis` / `-Dxm.it.kafka` 的整模块 `Tests run: 304, Failures: 0, Errors: 0, Skipped: 0`（`d72b-9.log`）。之后 `XmDataMysqlIntegrationTest`
又加了上面那条无符号区号的用例，整模块的条数以最终验证为准。

#### 最终验证（2026-10-06）

与批次 6.3 的收尾同一次执行（本机 Windows 11，JDK 21.0.12；Redis 8.10.2、MySQL 8.4.11、Kafka 4.3.1 单机），完整记录见 scene-battle-spec 末尾的「最终验证（2026-10-06）」。与本稿有关的部分：

- **全量构建**（`./mvnw -B clean install`，带 `-Dxm.it.redis` / `-Dxm.it.mysql` / `-Dxm.it.kafka`）：26 个模块 BUILD SUCCESS，7096 条用例 0 失败 0 错误；
  其中 xm-data 305 条、0 跳过（比 13.4 的模块级证据多 1 条：`XmDataMysqlIntegrationTest` 的无符号区号用例）。
- **robot `rollback`**（19 项，含 2026-10-06 追加的差异接口无差异、按 `preSnapshotId` 撤销、LOGOUT 快照经 Kafka 落库后按时刻回档、运维持有期间进游戏回 2005）：
  本机单 scene 切片与双 scene 切片（`XM_SCENE_NODES=2`）上各跑一遍，19 项全部通过。`audit`（3 项）、`guard`（5 项）同次通过。
- 帮会联动、整区回档与 `/admin/zone-snapshots` 仍不在 robot 场景里（由 `RollbackJobSqlTest` / `ZoneRollbackSqlTest` / `ZoneSnapshotSqlTest` 覆盖）。
