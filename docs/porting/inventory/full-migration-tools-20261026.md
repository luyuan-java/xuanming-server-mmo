# 7.4 数据巡检与故障工具：当前库存及 Java 实施设计

本文是 **2026-10-26 的只读盘点和下一批实施设计**，不是已实现或已验收声明。Java 对象为 `port/cpp-continuation-20261026` 工作区；C++/Go 只读固定提交 `92952c84b00f5dd45bf3b05ed4d08b9d7b5bd1b5`，没有修改该仓库的源码、配置、生成物或文档。此次只新增本文，没有执行构建、数据库查询、压力或故障实验。

`data-tools-spec.md` 基于更早的 `26ceb70ca` / Java `aa8b5b5`；它定义的目标仍有价值，但不能把其中的“尚未实现”当作今天的源码事实。下文的文件路径均相对相应仓库，Java 源文件优先于旧规格中的行号。已有运维能力另见 [remaining-ops-java-20261026.md](../remaining-ops-java-20261026.md)，战斗链另见 [full-migration-battle-20261026.md](full-migration-battle-20261026.md)。

## 1. 当前库存与范围裁决

| 项目 | 固定 C++/Go 的真实入口 | 当前 Java | 本轮结论 |
| --- | --- | --- | --- |
| 一致性巡检 | `tools/data_consistency_check/main.go`、`route_source.go` 及三个 route/mail 测试文件族 | 未找到 game-data 巡检运行器、管理接口或 `ConsistencyCheck.java`；正常业务内的门禁不等于独立巡检 | **真实代码缺口**；先做本文的首批检查，再补旧规格全部目录 |
| 到期战斗无恢复出口巡检 | `go/match/internal/logic/global_expired_audit.go`、`authority/store.go` 的 `CountStrandedExpiredFormations`、`ready_recovery.go` | `GlobalMatchStore.terminalDeadlineCandidates` 有正常终局恢复扫描；它 JOIN completion 且要求 pending，不能发现所有缺原件或坏形状的历史成局 | **真实代码缺口**；纳入 SQL v1 巡检，不另造终局或调用恢复 RPC |
| 玩家导出/本地复现 | `go/data_service/cmd/debug_fetch`、`debug_import`、`debugutil` 已有 player-bundle、身份及 TiDB 测试；不能沿用旧稿“全部只有坏的旧模式”的判断 | `game-data/.../tools/playerbundle/PlayerBundleExport`、`PlayerBundleImport`、`BundleAnalyzer` 已落地；`docs/design/player-bundle-debug.md`、`PlayerBundleSqlTest`、`BundleMysqlSnapshotIntegrationTest` 为现有入口 | **已有能力**；不按旧稿再建第二套导入 CLI。全量在线数据完整性和本次真环境结果需另验，不以历史文档代替 |
| 存盘压力与收敛核对 | `go/db/cmd/data_stress`、`verifier`、`internal/stresstest`、`robot/data_stress.go` | 玩家围栏、final-save、handoff、Inbox 有单元/条件集成测试；未找到通用 `data-stress` 场景及 DS1–DS10 运行账本 | **真实工具缺口**；使用 Java SQL 权威和真实玩法载体，不搬 Kafka 存盘探针 |
| 通用混沌编排 | 固定树没有旧稿所称的 `tools/scripts/chaos_test.ps1`；有 `currency_crash_window.ps1` 和专项 ownership fault 脚本 | `tools/local/battle-crash-window.sh` 是已有专项脚本；不是完整 C1–C6 矩阵 | 通用脚本属于**原版也未按旧稿交付**的目标；Java 仍需实现可重复的故障验收 |
| 压测机器人/策略 | `robot/logic/ai/{robot_ai,action,llm}.go`、robot stress 分支存在 | 已有大量确定性业务场景；未找到对应的通用 stress/data-stress 档位与 LLM `ActionPolicy` | **真实代码缺口**；先确定性动作和结局账本，再选配 LLM，不能把仅发送请求算成功 |
| 7.3 合服及退休工具 | 原版合服/热清理工具存在；具体原件结构不同 | `ZoneMergeStore`、`MergeZone`、`RetireZoneHotState`、`RetiredZoneRedis` 及永久 retired 发布围栏已落盘 | 不重复实现；巡检只观察其原件和结果，**不能调用维护方法顺便清理** |
| 整栈 CI | 固定树已有各种专项编排，验收范围逐项看脚本 | 本工作区 `.github/workflows/integration.yml` 已有 stack job，`deploy/ci` / `deploy/compose/stack.yaml` 为 17 个 Java 服务及 robot 的本轮接线 | “完全没有整栈 CI”已过时；**源码存在不等于真整栈运行通过**，更不等于混沌验收 |

专项脚本的版本边界：`battle-crash-window.sh` 的 `battle-after-store` 通过 GM 金币封禁拖延旧结算投递。本轮 SQL v1 `BattleSettlementService.applyInbox` 已按契约改为封禁只跳金币、其它奖励继续。因此该断点不能直接证明 SQL v1 的提交后/入箱前窗口。下一批应使用受控 SQL/Inbox 应答屏障，保留同一 battle/operation/instance 原件；旧脚本仍可作为明确标注的 legacy 场景，不能混计成功。

## 2. 固定 929 的实际 check 逐项映射

固定 `main.go` 的 runner 是 **五项 check，加一项路由值形状检查**；不再是旧稿的四项。以下逐项保留原意并按 Java 权威源重写。

| 固定原版函数 | 实际检查及局限 | Java 对应检查与权威源 |
| --- | --- | --- |
| `shapeCheckResult` / `routeSource.LiveZones` | 路由 home_zone 形状；显式 live-zones 时没有扫描；支持全局 SQL，Redis 回退会说明来源；SCAN 后键消失与坏值分开 | `route.meta`、`route.fences`、`zone.player`：`player.zone_id` + `zone_config` + `route_meta/zone_merge_fence/zone_merged_into`。**Java 不存在原版的 player_route/account_role 表，不用 Redis 推断归属区** |
| `checkGuildZoneFK` | guild.zone_id 在 knownZones 中；部分 COUNT/Scan 错误处理仍可能给出 info 或不完整计数 | `zone.guild`：按 guild 主键有界扫描并 LEFT JOIN 区配置和 SQL 退休标记。表缺失、扫描失败均 UNKNOWN；zone=0 为确定异常 |
| `checkGuildRankZoneOrphans` | 扫 `guild_rank:zone:*`；该实现使用 mapping Redis 句柄，不能照抄其库/键空间 | `zone.guild_rank`：注入 `RedisKeys.guildRankZones/guildRankZone`，读索引并按专用前缀扫描物理榜键，避免“索引丢了所以榜也不存在”的漏检。只读，不修索引 |
| `checkPlayerHomeZoneVsAccount` | 全局 SQL player_route→account_role 精确反连接；缺句柄是 NOT CHECKED；查询失败是 fail | `account.player_account`：同 SQL 语句检查 player.account→account；账号名只在 SQL 内比较，不进报告。附加 owner_revision 合法性，但不更换原身份 |
| `checkFriendOrphans` | RAND 1000 条，只查 friend_player_id；现已区分路由读错误与真缺行，并检查 Scan/rows.Err | `friend.orphan`：friend、request、block **两端**按主键有界精确扫描，不用 RAND；全量完成才报告全量比例，部分结果不假称“孤儿率低于 5%” |
| `checkMailSystemStateOrphans` | mail_state 引用的 **不同 mail_id** 在 mail_message 中缺失；不是收件人是否存在。缺依赖/失败不是干净 | `mail.state_orphan`：沿 Java 的 mail_state/mail_message 和 mail_id 索引检查；计数单位仍是不同邮件号，不是状态行数。非现存玩家收件人不是本项不变量 |

另外固定 `CountStrandedExpiredFormations` 是 match 服务内的独立观察能力：原版检查 state 2/3 的过期 v1 成局不能满足恢复形状。Java 对应 `match.expired_unrecoverable`，需使用 Java 的 formation + completion 两表形状，不能复制 Go 单表 SQL 或照抄它的状态数字范围。

## 3. 当前 Java 的权威源和只读边界

| 领域 | 本轮核对的事实源 | 巡检必须遵守的语义 |
| --- | --- | --- |
| 玩家/账号 | `game-player-store/src/main/resources/db/game-player-schema.sql`；`PlayerStore`；`PlayerRouteAuthority` | player.account + owner_revision 是当前身份；owner_epoch 是在线写围栏，二者不可互换。player_state 缺行/NULL 是异常，**空 protobuf bytes 是合法初始状态**。历史终态原件不因玩家后来过户就“归属错误” |
| 区路由/维护 | `game-common/src/main/resources/db/game-route-authority-schema.sql`；`game-gateway-store/.../game-gateway-schema.sql`；`game-data/.../merge/ZoneMergeStore` | `zone_config` 全部配置区构成已知区，CLOSED/MAINTENANCE 不等于被退休。route_meta 正常 id=1、UUID 世代、64 分片；writable=0 可是合法维护。退休源看 zone_merged_into，不能从少人/无目录推断 |
| 社交/邮件 | friend/guild/mail 各模块 `src/main/proto/game/*/*_tables.proto` | 使用 Java 同库 SQL 表和现有索引；不拼接用户指定 schema。帮会 player_state 的哨兵行不作玩家孤儿；好友跨区合法；邮件收件人缺号不作孤儿 |
| 7.2d 资产恢复 | `GuildInternalQueries`、`JdbcGuildAssetStore`、`RedisKeys.guildTerminalCleanupWatermark` 及 data 资产分歧读取 | 缺/坏水位、未知 COMMIT、查询窗口覆盖不足是 UNKNOWN，不填 0、不按普通异常断言回滚。巡检不推进水位，不清理原件、不触发 assetopfix |
| 7.3 永久退休 | `game-data/.../merge/retire/RetiredZoneRedis`；`ZoneNodeDirectory`；`RedisKeys.worldRetirement` | retired **只要存在就有屏障效力**，值是原件，不依赖固定常量；不得 DEL/EXPIRE/PERSIST。ver/node-seen/格式/类型证据等永久键不是待清垃圾。SQL finish、退休清理、release 是三个有序步骤 |
| 战斗 v1 | `game-match/.../global/GlobalMatchStore`；`rating/BattleCompletionStore`；`battle_completion_tables.proto`；`game-inbox/.../InboxSql` | formation 不是终局；completion 的 terminal_decision=0/1/2 分别为未决定/COMPLETE/ABORT。个人原件和 Inbox 持久回执决定补投进度；Redis 空不能证明死亡、未提交或已奖励 |
| 存档终态 | player_final_save 表及 player-store final-save 恢复路径 | OPEN 仅准入，PENDING 永不按 TTL/次数丢弃，APPLIED 是原终态。积压是独立告警；不能用巡检把 PENDING 改 APPLIED 或释放玩家 |

**不能复用会产生写入的“读接口”**：NodeDirectory 列表路径可能清过期条目；RetiredZoneRedis 是维护执行器；PlayerRouteAuthority.lock、ZoneMergeStore.withRetirementProof 会加权威锁；InboxStore.battleSettlement 即使不 enqueue 也进入带锁事务。巡检用独立只读 JDBC/Redis adapter，不调用这些业务路径，不建表、不补行、不拉起恢复、不发业务 RPC。

生产 SQL 读需明确强读策略：当前 DataConfiguration 没有统一安装 `StrongTiDbConnectionPolicy`，不能因为其它服务装了就假定本进程也有。实现时为巡检配置有界专用连接池，在物理连接初始化前安装现有策略；每次借连接在同一预算核验 TiDB strict/leader、snapshot 空、staleness=0、lowres=OFF、pessimistic/RC 和 DATABASE。dev/test 可显式走 MySQL 方言；prod 不允许弱配置回退。巡检代码只使用固定 SELECT/参数绑定，业务账号权限不足或会话无法证明时该 run 为 UNKNOWN。

TiDB 不以 JDBC `setReadOnly(true)` 为保护前提：本仓库 player-bundle 已记录 TiDB 对该语法的支持边界。MySQL 可加 JDBC 只读提示；两者都需固定 SQL 白名单，推荐独立只读数据库账号。连接初始化的 SESSION 设置和专属报告/锁元数据允许存在；**业务数据零写入**是可测试的边界，不是宣称整个进程绝无任何元数据写入。

## 4. 首批可以直接落成 repository 的查询

以下是固定 PreparedStatement 模板，`?` 只绑定值；非零首游标页展示 `>`，**第一页另有不带下界的固定模板，必须覆盖主键为 0 的坏行**。每表先用主键尾部取得本轮 upper（空表结果为空，读失败不是空表），随后 `cursor < key <= upper`。uint64 使用 `PbMysql.uint64` / `AuthorityJdbc.u64` 等已有无符号绑定，以无符号十进制字符串保存游标，不能以负 long 排序或 JavaScript number 承载。

### 4.1 路由原件与分片（`route.meta` / `route.fences`）

```sql
SELECT id,generation,writable,fence_shards FROM route_meta ORDER BY id LIMIT 2;
SELECT zone_id,manual_status FROM zone_config ORDER BY zone_id LIMIT ?;
SELECT zone_id,shard,run_id,revision FROM zone_merge_fence
 WHERE zone_id=? ORDER BY shard LIMIT 65;
SELECT zone_id,target_zone,run_id,revision FROM zone_merged_into
 WHERE zone_id>? AND zone_id<=? ORDER BY zone_id LIMIT ?;
```

必须恰有有效 meta 原件；每个应存在的区分片恰为 0–63、revision>0。区配置读取达到上限需再探测一行，超限 UNKNOWN，不能截断 Z 后把余区误报已死。writable=0 时查对应 zone_merge_run / fence 的原 run，记录 maintenance；不能把维护直接判 BLOCK。合服链需检测环、自环和悬空目标，允许目标后来继续合入其它区。

### 4.2 玩家基本引用和围栏（`zone.player` / `account.player_account` / `own.fence`）

```sql
SELECT p.player_id,p.zone_id,p.owner_revision,p.owner_epoch,
       p.owner_released,p.owner_lease_until,
       a.account IS NULL AS account_missing,
       z.zone_id IS NULL AS zone_missing,
       m.zone_id IS NOT NULL AS source_retired,
       s.player_id IS NULL AS state_missing,
       s.data IS NULL AS state_null,s.saved_epoch
FROM (SELECT player_id,account,zone_id,owner_revision,owner_epoch,
             owner_released,owner_lease_until
      FROM player WHERE player_id>? AND player_id<=?
      ORDER BY player_id LIMIT ?) p
LEFT JOIN account a ON a.account=p.account
LEFT JOIN zone_config z ON z.zone_id=p.zone_id
LEFT JOIN zone_merged_into m ON m.zone_id=p.zone_id
LEFT JOIN player_state s ON s.player_id=p.player_id
ORDER BY p.player_id;

SELECT s.player_id,p.player_id IS NULL AS player_missing
FROM (SELECT player_id FROM player_state
      WHERE player_id>? AND player_id<=? ORDER BY player_id LIMIT ?) s
LEFT JOIN player p ON p.player_id=s.player_id ORDER BY s.player_id;
```

先驱动一页**所有行**再判断好坏，避免 `WHERE 坏条件 LIMIT 256` 为找不到坏行而无界全扫。account_missing 的结果只输出布尔值；SQL 内额外拒空账号。复核发现时重读相同玩家原件；`saved_epoch > owner_epoch` 才破坏围栏，反向不等式正常。SQL 退休源仍有玩家是确定数据矛盾；普通关服区仍有玩家合法。第一次发现 missing/null 时不能在巡检中补空存档。

### 4.3 帮会区（`zone.guild`）

```sql
SELECT g.guild_id,g.zone_id,z.zone_id IS NULL AS zone_missing,
       m.zone_id IS NOT NULL AS source_retired
FROM (SELECT guild_id,zone_id FROM guild
      WHERE guild_id>? AND guild_id<=? ORDER BY guild_id LIMIT ?) g
LEFT JOIN zone_config z ON z.zone_id=g.zone_id
LEFT JOIN zone_merged_into m ON m.zone_id=g.zone_id
ORDER BY g.guild_id;
```

zone=0、未知区或已退休源区为 BLOCK 候选；合服执行期间需连同 run/generation 复核，原件变化不能混成一个静态结论。成员归属和 leader 检查见 §7，不从榜缓存反推 guild.zone_id。

### 4.4 好友两端和对称（`friend.orphan` / `friend.symmetry`）

```sql
SELECT f.player_id,f.friend_player_id,
       p.player_id IS NULL AS left_missing,
       q.player_id IS NULL AS right_missing,
       r.player_id IS NULL AS reverse_missing
FROM (SELECT player_id,friend_player_id FROM friend
      WHERE (player_id>? OR (player_id=? AND friend_player_id>?))
        AND (player_id<? OR (player_id=? AND friend_player_id<=?))
      ORDER BY player_id,friend_player_id LIMIT ?) f
LEFT JOIN player p ON p.player_id=f.player_id
LEFT JOIN player q ON q.player_id=f.friend_player_id
LEFT JOIN friend r ON r.player_id=f.friend_player_id AND r.friend_player_id=f.player_id
ORDER BY f.player_id,f.friend_player_id;
```

绑定 lowerPlayer 两次、lowerFriend、upperPlayer 两次、upperFriend、pageSize。request/block 另建各自列名和主键顺序的固定模板，不能用请求拼表名。孤儿计数按一条物理边一次，两个端点都缺不加两次；对称缺失复核同页 SQL，已删除的整对边属于并发变化。5% 升级仅作用于 friend 完整覆盖的分母；部分页不外推全表。

### 4.5 邮件状态孤儿（`mail.state_orphan`）

```sql
SELECT d.mail_id,m.mail_id IS NULL AS message_missing
FROM (SELECT DISTINCT mail_id FROM mail_state
      WHERE mail_id>? AND mail_id<=? ORDER BY mail_id LIMIT ?) d
LEFT JOIN mail_message m ON m.mail_id=d.mail_id ORDER BY d.mail_id;
```

走 mail_state 的 `(mail_id,claim_state)` 前缀索引、mail_message 主键；游标/分母都是不同 mail_id。LIMIT 限输出，不自动保证底层读取量：MySQL/TiDB 的真实计划需验证 DISTINCT 索引访问；大热点或不能跳过重复值时以请求时限截断为 UNKNOWN，不为了“跑完”改变为采样通过。确认候选用 `EXISTS(mail_state WHERE mail_id=?) AND NOT EXISTS(mail_message WHERE mail_id=?)` 同语句复核。消息标题、正文、附件、收件人名单均不读取或输出。

### 4.6 7.3 退休 SQL 原件（`retire.original` / `retire.references`）

```sql
SELECT m.zone_id,m.target_zone,m.run_id,m.revision,
       r.run_id IS NULL AS run_missing,r.mode,r.source_zone,r.target_zone AS run_target,
       r.generation,r.state,r.plan_hash,z.manual_status
FROM (SELECT zone_id,target_zone,run_id,revision FROM zone_merged_into
      WHERE zone_id>? AND zone_id<=? ORDER BY zone_id LIMIT ?) m
LEFT JOIN zone_merge_run r ON r.run_id=m.run_id
LEFT JOIN zone_config z ON z.zone_id=m.zone_id ORDER BY m.zone_id;

SELECT kind,entity_id,state FROM zone_merge_item
 WHERE run_id=? AND (kind>? OR (kind=? AND entity_id>?))
 ORDER BY kind,entity_id LIMIT ?;
```

核 marker→正向 run 的 source/target/generation 原件、合法完成状态和计划摘要；run=5/6 不能仍有未提交条目。源区 player/guild/有效 trade_listing 引用用各领域分页检查。**不要直接调用 withRetirementProof**：其持锁及清理回调是写维护协议。Redis retired 原件与 SQL 的跨库确认采用 SQL A→Redis→SQL B；A/B 原件改变则重试一次，仍变化记 UNKNOWN(concurrent_change)。无跨库原子快照承诺。

### 4.7 SQL v1 无恢复出口和终局完整性（`match.expired_unrecoverable` / `battle.terminal_original`）

```sql
SELECT f.battle_id,f.operation_id,f.instance_id,f.state,f.deadline_ms,
       OCTET_LENGTH(f.create_request) AS create_bytes,
       t.battle_id IS NULL AS completion_missing,t.terminal_decision,
       t.placement_operation_id,t.target_instance_id,t.outcome_protocol_version,
       t.preflight_supported_version,t.preflight_instance_id,
       t.adopted_outcome_protocol_version,t.adopted_outcome_observed,t.source_kind
FROM (SELECT battle_id,operation_id,instance_id,state,deadline_ms,create_request
      FROM match_global_formation WHERE battle_id>? AND battle_id<=?
      ORDER BY battle_id LIMIT ?) f
LEFT JOIN match_battle_completion t ON t.battle_id=f.battle_id
ORDER BY f.battle_id;

SELECT battle_id,terminal_decision,source_kind,format_version,player_count,
       OCTET_LENGTH(original) AS original_bytes,OCTET_LENGTH(original_digest) AS digest_bytes,
       result_published,retry_ms
FROM match_battle_completion WHERE battle_id>? AND battle_id<=?
ORDER BY battle_id LIMIT ?;
```

第一查询不可照生产恢复器用 INNER JOIN，否则恰好漏掉 completion 缺失的坏成局。过期参照本页 SQL 时间和生产恢复宽限；带 Create 原件的 state 2/3/4，如果缺前置原件、UUID 不匹配、协议/能力证据坏、adopted 观察值不合法，报告不能恢复的候选；确认前还须读有界 Create bytes 按现有解析规则核对。缺 Create 的排队/准备态可能合法；本轮 `finishUncreated` 已有同事务安全出口，不能仅据空 Create 报“丢结果”。state=5 且无 completion 也可能是该正常出口。

COMPLETE 在有限深读中核 canonical original/digest、player_count 与个人原件成员集合一致；个人表反向扫才能发现孤儿。source_kind=1 的 legacy Kafka 历史归档有不同的原身份覆盖，不能套 v1 强制规则制造误报。RPC v1 原件不伪造 Kafka 位点。原文尺寸以当前生产常量为上限（整局 960 KiB、个人 16 KiB），超限是坏原件而不是尝试无限反序列化；默认页先读长度，只对有界候选拉正文。

Inbox 扩展采用 `InboxSql` 当前 schema：`(shard,player_id,domain,seq)`，Match producer 的 request_id=battle_id，内容摘要还包含 issued_at_ms；不得直接把两边 digest 列相等当作协议一致。以 `InboxShards.rowShard` 定位，核 seq/producer/type/request/原 account+revision/issuedAt/payload 的协议摘要，按固定枚举而非魔数判断 APPLIED。未入箱、未发布或 pending 是积压，需单独 SLA；不能据 Redis ACK 缺失判丢失，也不能因为当前玩家已过户而修改永久旧原件。

## 5. Redis 检查与 7.3/5.6 边界

独立 `ConsistencyRedisReader` 仅开放 TYPE、GET、PTTL、HGET/有界 HSCAN、SSCAN、ZSCAN、ZSCORE、ZCOUNT、SCAN、TIME。需要原子读一键值/类型/TTL 时可用仅含这些读命令的 Lua；不调用会 prune 的目录 service，不用 KEYS、无界 HGETALL 或 GET 成功前默认空集合。不同槽分别读，不能声称全 Redis 同时快照。

| 检查 | 读法及判定 | 不可误报/不可修复的边界 |
| --- | --- | --- |
| `zone.guild_rank` | SSCAN guildRankZones，再有界 SCAN 精确 zone 榜前缀，TYPE 和区配置核对；扫描重复去重，key 消失记 transient | 只扫索引会漏掉未索引的孤儿榜。Redis 故障是 UNKNOWN；榜维护锁存在时延迟确认，不能删除榜或补索引 |
| `retire.redis_barrier` | SQL marker/run 快照与 `worldRetirement(zone)` 的 EXISTS/GET/PTTL；存在且 PTTL=-1 才满足永久性；解析元数据有界 | 任意值存在都应按退休屏障处理；坏值不能当“不存在”。SQL finish 后、显式退休前缺 Redis 标记是阶段状态，不一律 BLOCK。已知执行退休的证据与实际缺屏障矛盾才升级 |
| `retire.hot_state` | 读 RetiredZoneRedis 同一热键白名单、v2 nodes/expiry 与 Redis TIME；只计算数量/摘要 | 不清理、不重新打标。retired、ver、node-seen、目录格式/类型证据、node-id/epoch、玩家 location/owner/seq 墓碑允许保留；world:zones 保留索引是当前协议，不再要求 SREM |
| `world.directory` | 读 world plan/version/retired 与 `worldChannelDirectory(zone,config)` bytes/PTTL，解析受限大小；比较稳定线号唯一性、正号、计划版本及节点租约证据 | 新 5.6 目录 TTL 自然失效是预期兜底。计划暂空/不可用不表示应清稳定号；retired 区不得重新发布。目录存在但无 TTL、已退休区仍有活发布证据为候选；瞬时发布滞后先复核 |
| `assetop.cleanup_watermark` | 按已登记 key 读有效时间水位/TTL，与查询下界和 SQL/账本覆盖范围比较 | 缺值/读失败不能用 0 宣称检查完整。巡检不 PERSIST，不推进、不删除；水位本身需永久，带 TTL 是告警候选 |
| `own.zombie` / `own.ghost_presence` | 当前 SQL owner 原件与 presence、ops job/final-save 状态分开读取，跨实际租约/TTL 后复核同一 identity/epoch | 不把交出、最终存档、运维合法持有误判僵尸；epoch 变更为 transient。35/65 秒是旧默认的推导值，必须从当前 TTL/续租常量重新导出 |

跨 Redis Cluster 扫描必须明确拓扑能力：当前单节点/代理连接可做完整游标扫描；若接 Cluster，须覆盖所有 master 分片并报告拓扑变化。未实现完整拓扑扫描时 `scope=single_endpoint` 且相关全域检查 UNKNOWN(unsupported_topology)，不能以一个节点 SCAN 宣称全服通过。

## 6. Java 运行器、预算和报告模型

落在 game-data 的 `com.game.data.consistency`，普通 Java record、枚举、JDBC repository、只读 Redis port、Spring scheduler/controller。沿用现有 Jackson、Micrometer、Redisson、JDBC 和 `Deadline`，不新增依赖、不复制 Go CLI/配置目录、不新增进程。CLI 是 JDK HTTP 薄客户端，口令不下发到调用者；玩家导出仍复用已有 PlayerBundle。

拟定入口：`POST /admin/consistency/runs`（固定 check IDs、zones、deep、可收紧预算），`GET /admin/consistency/runs/{runId}` 和 latest。沿 AdminAuthFilter、操作人和审计；请求不能传 SQL、schema、任意 Redis 前缀、DSN。调度按旧规格默认 Asia/Shanghai 04:10，显式 `-` 关闭；缺权威依赖时发布 UNKNOWN，不暗中跳过。实现上线前需核对生产授权与读权限配置，不因巡检主动初始化业务表。

分布式单飞用独立 consistency 锁和随机 token，不复用 ops_active，不锁玩家。报告存 Redis，保留 8 天及最近 20 个 runId；新增键必须进 RedisKeys 及命名测试。锁续期/发布/释放都比本 token；失租立即停止发新请求，迟到完成不得覆盖新 run。运行报告必须带 finalized 状态；崩溃遗留 RUNNING 超过 lease 后读为 UNKNOWN(interrupted)，绝不推断 PASS。报告存储故障记录本地脱敏结构日志并让查询/CLI 返回 UNKNOWN，不能仅因为检查内存完成就答成功。

结果分开记录 `outcome=OK|FINDING|UNKNOWN|NOT_APPLICABLE` 与 `severity=info|warn|block`，另有 coverage、countExact、scannedCount、confirmedBad、transientCount、cursor/upper、开始/结束 SQL 时间、reasonCode。query_failed/row_decode_failed/schema_missing/strong_read_unproved/budget_exhausted/lease_lost/concurrent_change 都是 UNKNOWN。请求的未实现项必须 UNKNOWN(not_implemented)；只有显式未部署且确实不适用的能力可 NOT_APPLICABLE。未选 deep 不伪装为已查。CLI 0=完整覆盖且未达阈值，1=已证实达到 fail-on 阈值，2=参数错误，3=没有阈值级发现但存在 UNKNOWN；同时存在异常与未知时仍保留两者，不让退出码隐藏覆盖缺口。

预算统一为：整 run 单调 deadline 缺省 15 分钟（旧规格上限）；一般 check 60 秒；每次 SQL/Redis 往返至多 2 秒，连接取得、强读证明、元数据、取 upper、遍历 ResultSet、复核及写报告都计入上层剩余预算。页默认 256、上限 2000，正文深读默认 16 条且每批总字节有上限；每项行/键扫描上限另配置，触顶 UNKNOWN(partial_coverage)。这些是实施默认提案，需以实库计划校准，不能靠加大超时掩盖无界扫描。

presence 复核可能超过旧单项 60 秒：这两项单独允许“实际 TTL + 安全余量”的墙钟阶段预算，仍受整 run 上限；SQL/Redis 活跃请求累计不放宽。使用调度器延迟复核，不占连接、不睡占工作线程。若整 run 已无足够复核时间，直接 UNKNOWN，不跳过第二读假装确认。借连接等待和网络超时都收紧到同一个剩余预算；不能每次重试重新给满 2 秒，更不能异步超时后后台 SQL 无限继续。

时间规则：每个读取阶段在同一 SQL 会话取 DB now，用于该页 lease/deadline 判断；run 保留开始 DB now 作历史范围参照，**不能用 15 分钟前的 now 检查刚续租的玩家**。持续耗时用单调时钟，展示 UTC；Redis TTL 用 Redis TIME/PTTL，不用本机墙钟猜。分页 RC 强读是一段时间内的有界观察，不是整库单时刻快照；并发新增低于游标的 ID 留给下一轮，报告明确观察区间及该限制。候选纯 SQL 延迟约 2 秒再读相同原件；复核读失败不得当作候选消失。

脱敏规则：不 SELECT 密码哈希、登录令牌、邮件正文、账号设备凭据；账号比较仅返回布尔或摘要。样例默认只保留每 run 随机盐产生的不可逆标识和错误类别，实体原 ID 不进入公开指标或普通日志；管理员若需精确定位，应使用单独受审的现有玩家排障流程，首批报告不新增明文导出开关。PB 只在受限内存验证，不落原 bytes；退休原件只存结构判定/摘要，不输出外部部署材料。异常只记固定 reasonCode、SQLState/厂商码，禁止 SQLException 原消息泄露 DSN/绑定值。指标只有固定 check/result/severity 标签，无 player/scene/zone/run ID 高基数标签。

## 7. 旧规格 27 项逐项落实状态

下表“首批/后续”均指 **尚待实现的检查器**，不是说领域业务缺失。首批同时包含 §2 原版新增 mail 和 §4–5 的 route/retire/v1 扩展，不能把“首批完成”宣传为 27 项全完成。

| 旧 check id | 当前判据/需修正处 | 批次 |
| --- | --- | --- |
| zone.player | SQL 区配置 + SQL 退休；见 §4.2 | 首批 |
| zone.guild | zone=0/缺区/退休；见 §4.3 | 首批 |
| zone.guild_rank | 索引 + 实体键只读扫描；见 §5 | 首批 |
| zone.trade_listing | 活跃状态由当前 ListingStatuses 定义；以 trade SQL 表为真，不把已终结历史全算坏 | 后续 |
| zone.world_registry | world:zones 是索引，永久 retired 过滤已生产接线；存在退役成员本身合法 | 首批随 retire，取消旧“必须 SREM”判据 |
| zone.guild_member_home | member→guild/player 同语句比较；维护原件改变时复核 | 后续 |
| zone.trade_listing_home | 活跃 listing.market_zone 与 seller 当前 home，历史终态不能套 | 后续 |
| account.player_account | 同 SQL 反连接，不读 Go account_role；见 §4.2 | 首批 |
| account.player_cap | 读取与 login 同一上限配置，不能另硬编码 5；按账号索引分页聚合 | 后续 |
| player.name_key | 深读后调用唯一标准化规则；不在报告输出名字 | 后续 deep |
| player.state_orphan | 双向扫描：旧 state→player，加 player→state missing/null；空 bytes 合法 | 首批 |
| player.state_decode | 受限 PB + PersistedAssetLedgerReader，未知字段保留；不尝试修复 | 后续 deep |
| assetop.seq_watermark | 帮会/交易/邮件各自产生流原件与账本；同时核 7.2d 永久清理水位与下界，不能混 seq 域 | 后续 deep；首批核可读性 |
| own.fence | saved_epoch > owner_epoch；uint64 无符号比较 | 首批 |
| own.lease_skew | 当前页 DB now + 当前 lease 时长/容差；旧 app wall clock 说明需按现写者核对 | 后续 |
| own.abandoned | 仅计过期持有的观察值，不从其推断实际丢了多少增量 | 后续 |
| own.zombie | 加 ops/final-save/handoff 合法持有排除与同 identity/epoch 复核；目录空不证明死亡 | 后续 |
| own.ghost_presence | 跨当前 presence TTL 复核；读失败 UNKNOWN | 后续 |
| friend.orphan | friend/request/block 两端，完整分母；见 §4.4 | 首批 |
| friend.symmetry | 同语句反向边，提交竞态复核；见 §4.4 | 首批 |
| friend.capacity | 容量行与实际边计数同语句核；有边无容量异常，无边无容量合法 | 后续 |
| guild.member | member/application 的 guild/player 两端，不能扫哨兵 state 表制造孤儿 | 后续 |
| guild.leader | leader_id 对应本帮 role=3；同时检多个 leader，不能只点查名义帮主 | 后续 |
| guild.capacity | 成员数与 max_members，按 guild/member 索引预算；不是无界 COUNT 全表 | 后续 |
| guild.rank_score | SQL→Redis 比较需采用生产 double 表示规则；超过 2^53 不能朴素 long 相等；维护窗口复核 | 后续 |
| trade.seller | 当前 trade 表及 favorite 的玩家引用；区分历史永久原件 | 后续 |
| ops.job_residue | OpsJobStore 当前状态机；SUCCEEDED 不应有 PLANNED，其它终态的未写计划合法；不清 ops_active | 后续 |

额外后续项：final-save PENDING 年龄/原件形状、Inbox shard/open_count/seq/永久回执、SQL v1 个人原件补投积压、rating 原件与派生投影。先复用现有生产规则做只读验证，不能通过“清理重建”来令巡检变绿。

## 8. 实现和验收测试方案

1. **结果/预算/单飞**：受控时钟和 CompletableFuture，覆盖最后一行 ResultSet 失败、已读几页后断线、borrow/强读证明耗尽预算、Redis nil 与异常、报告写失败、锁失租/旧 token 迟到发布、进程中断。UNKNOWN 必须保留 coverage 和诊断下界，不把未完成数写成精确 0。分页没有坏行也必须有界终止；每轮只能在允许的专属 metadata 键写入。
2. **SQL 形状与误报**：H2 使用现有 DataSqlFixture/正式 schema 作规则测试，不作为 TiDB 强读证据。覆盖缺表/缺列/空库、player_state 空 bytes/缺行/NULL、0 与高半区 UINT64、跨页边界、mail 同一 ID 大量 state 行、好友双端和反边同时提交/删除、关服区有玩家、合服链与循环、历史过户终态不能错判当前身份。
3. **MySQL/TiDB 方言与计划**：条件集成测试连接真实环境，显式记录产品/版本及门禁变量；用 EXPLAIN（不 EXPLAIN ANALYZE）确认驱动页主键/索引范围、点查被引用表、邮件 DISTINCT 的实际访问形态，不钉易变 cost 字符串。验证只读账号运行完整首批；生产 TiDB 拒 snapshot/stale/follower/错误 DB。缺环境时标未运行/skip，不能称实库通过。
4. **Redis 实例与退休**：固定时钟/真实 Redis 条件用例覆盖索引丢而物理榜仍在、SCAN 重复及消失、TYPE 错误、retired 任意值存在、永久键 TTL、plan/version 改变、旧 source 残留活节点、目录自然过期、保留 node-seen/location 等墓碑；故障/拓扑不完整一律 UNKNOWN。记录所有命令，除专属锁/报告外出现写命令即失败。
5. **SQL v1 与 Inbox**：真实 completion 事务生成原件后破坏单一字段，测缺前置、错 instance/operation、adopted 不兼容、到期无 Create 正常出口、COMPLETE 缺个人行/多行/坏摘要、原 payload 与不同 Inbox 摘要域、原 ID 重放、已过户历史原件、legacy Kafka archive 豁免。注入两读间正常终局提交，不能误报丢失；查异常不得触发 commit/abort/enqueue。
6. **覆盖回执**：报告保存 requested/executed/omitted IDs，测试全部已登记检查都有固定指标标签和说明；首批不能默默省略旧 27 项。真实全量 scan 在既定预算跑不完必须呈现 UNKNOWN 并给游标，不自动改成 RAND 或放大请求时限。

实施顺序：先 runner/结果/预算/只读 adapter + route/player/guild/friend/mail；随后 Redis 退休/目录/资产水位 + SQL v1 原件；再旧 27 项剩余与 deep，最后压力/混沌工具及策略。当前源代码冻结期间只完成本文；恢复实施要与统一构建窗口协调。

压力工具下一批的最小完整闭环：drive 阶段持久化受控测试玩家、原操作 ID、成功应答和截止时刻；故障阶段明确具体进程/依赖、故障前原件和恢复步骤；verify 阶段读 SQL 最终存档、final-save/Inbox/终局原件，验证 DS1–DS10（跨玩家污染、纪元、单调/崩溃损失边界、行/blob 原子、资产恰好一次等），任何读不到或未知操作仍未收敛均失败。C1–C6 分别覆盖 scene 退出、旧 owner 恢复、SQL 失联、Redis 失联、gate 退出、login 重试；每次重试原 ID，不用新请求掩盖旧 UNKNOWN。专项故障数据和敏感导出放受控仓库外目录，不能提交进 git。

本文所有“拟定”“首批”“测试方案”均为待实现工作。本次仅核对本地固定源码并完成设计；没有真 MySQL/TiDB/Redis、整栈、HA 或混沌运行结果。

## 2026-10-27 接续实现记录

上文保留为 10-26 的静态库存。现已接入核心及扩展，共登记 20 项（作业残留明确部分覆盖）、严格预算和 UNKNOWN/覆盖回执、HTTP CLI，详见 `../consistency-core-20261027.md`、`../consistency-extra-20261027.md`。源码测试合计 84；好友对称缺边为 BLOCK，新增归属对照在合服写闸关闭时保留 UNKNOWN。新增测试尚未运行，不覆盖持久 runs、分布式单飞、调度和其余待实现项；原有库存中的真实依赖及压力/混沌验收要求继续适用。