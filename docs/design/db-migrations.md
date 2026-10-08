# 库表迁移记录（Java 版，库 `xm_java`）

建表脚本 `xm-player-store/src/main/resources/db/xm-player-schema.sql` 由 xm-login 启动时执行，只用
`CREATE TABLE IF NOT EXISTS`：**新库**直接得到最新结构，**已存在的表不会被改动**。所以每次结构变更都要在这里登记一条
「存量库手工迁移」，按顺序执行。只追加，不删旧条目。

执行前先停掉 xm-login / xm-scene（它们会写 `player` 表），迁移完再起。

---

## M1（2026-09-29）玩家名唯一改为大小写不敏感：`name_key` + `uk_player_name_key`

**原因**：原唯一索引 `uk_player_name (name)` 建在展示名上，库的排序规则是 `utf8mb4_bin`（逐字节比较），
「Alice」与「alice」能同时建出来。改为在唯一键列 `name_key` 上建唯一索引；键 = NFKC → 去首尾空白 → `Locale.ROOT` 小写，
唯一出处是 `PlayerStore.nameKey`（Java 侧计算，调用方拿不到也填不了）。

**行为变化**：建角时与已有角色仅大小写 / 全半角不同的名字回 2033（名字已被占用）。`PlayerStore.createPlayer` 撞到
名字以外的唯一约束（主键 `player_id`）时抛异常（客户端收到 1003），不再报「重名」。

**新库**：无需操作。

**存量开发库**（在 `xm_java` 上执行）：

```sql
-- 1. 加列（先带空默认值，存量行才能加上 NOT NULL 列）
ALTER TABLE player
    ADD COLUMN name_key VARCHAR(64) NOT NULL DEFAULT ''
        COMMENT '名字唯一键：NFKC + 去首尾空白 + 小写，唯一出处 PlayerStore.nameKey；全服唯一'
        AFTER name;

-- 2. 回填。存量名字都经过 xm-login 的 PlayerNames.normalize（已做 NFKC 与去首尾空白，字符集只有
--    0-9 A-Z a-z 与 CJK），所以 SQL 里只需补「小写」这一步，结果与 PlayerStore.nameKey 一致。
UPDATE player SET name_key = LOWER(name);

-- 3. 查冲突：有输出说明存量里已有「只差大小写」的重名，必须先人工改名（改 name 后重算 name_key），否则第 4 步失败。
SELECT name_key, COUNT(*) AS n, GROUP_CONCAT(player_id) AS player_ids
  FROM player GROUP BY name_key HAVING n > 1;

-- 4. 建新唯一索引、删旧索引、去掉临时默认值（与建表脚本一致：name_key 无默认值）
ALTER TABLE player
    ADD UNIQUE KEY uk_player_name_key (name_key),
    DROP INDEX uk_player_name,
    ALTER COLUMN name_key DROP DEFAULT;
```

**核对**：

```sql
SHOW CREATE TABLE player;                       -- 有 uk_player_name_key (name_key)，没有 uk_player_name
SELECT COUNT(*) FROM player WHERE name_key = '' OR name_key <> LOWER(name);   -- 应为 0
```

**回滚**（只在 M1 之后还没建出「只差大小写」的角色时可行）：

```sql
ALTER TABLE player ADD UNIQUE KEY uk_player_name (name), DROP INDEX uk_player_name_key, DROP COLUMN name_key;
```

回滚后必须同时回退代码：新代码的 INSERT 带 `name_key` 列，跑在旧表上建角会失败（1003）。

---

## M2（2026-09-29）玩家数据归属的释放标记与租约：`owner_released` + `owner_lease_until`

**原因**：原协议进游戏时无条件把 `owner_epoch` 加一。上一个写者（scene 上的旧实例）的离场写回若晚于这次夺权落库，
会被 `WHERE owner_epoch = ?` 的围栏拒掉，玩家本次在线期间的进度静默丢失（同会话再次 EnterGame 时必现；
LeaveGame 后立即 EnterGame、断线快速重连、存储线程池积压时竞态出现）。新协议（architecture.md §7）：
夺权只在上一个写者已释放（最终写回已落库）或其租约已过期时成功；写者在线期间续约，离开时写回并释放。

**行为变化**：
- 归属仍被持有时 EnterGame 会先请持有者让出（旧连接收到 23 {2017} 后被关闭）并等待最多 `xm.login.owner-claim-wait`（3s），
  等不到回 2005；
- scene 进程被 kill（没释放）后，该节点上的玩家要等租约过期（最多 30s）才能再进游戏。

**新库**：无需操作。

**存量开发库**（在 `xm_java` 上执行；先停 xm-login / xm-scene）：

```sql
-- 默认 1 = 已释放：存量玩家当前都没有写者（进程已停），迁移后直接可以夺权。
ALTER TABLE player
    ADD COLUMN owner_released TINYINT UNSIGNED NOT NULL DEFAULT 1
        COMMENT '1 = 当前 epoch 的写者已写回并释放（或从未进过场景）；0 = 有写者持有，新的夺权要等它释放或租约过期'
        AFTER owner_epoch,
    ADD COLUMN owner_lease_until BIGINT NOT NULL DEFAULT 0
        COMMENT '当前写者的归属租约到期时刻（Unix 毫秒）；写者在线期间定期续约，过期后允许强制夺权'
        AFTER owner_released;
```

**核对**：

```sql
SHOW CREATE TABLE player;                                    -- 有 owner_released、owner_lease_until 两列
SELECT COUNT(*) FROM player WHERE owner_released <> 1;       -- 迁移后应为 0
```

**回滚**：

```sql
ALTER TABLE player DROP COLUMN owner_lease_until, DROP COLUMN owner_released;
```

回滚后必须同时回退代码：新代码的夺权 / 写回 / 续约 SQL 引用这两列，跑在旧表上进游戏会失败（1003）。

## M3：玩家玩法数据表 `player_state`（2026-10-02）

**变更**：新增表 `player_state`（`player_id` 主键、`data` MEDIUMBLOB = `xm.storage.PlayerState`、`saved_epoch`、`updated_at`），
存各玩法的持久化数据（首个玩法数据：朝向）。在线存盘与离场写回都在同一事务里先带围栏更新 `player` 行，通过才 upsert 本表。

**新库 / 存量库**：都无需手工操作——这是新表，建表脚本的 `CREATE TABLE IF NOT EXISTS` 启动时自动建出。
存量玩家没有这一行，读到默认实例（各玩法都取初始状态），第一次写回或在线存盘时补上。

**核对**：

```sql
SHOW CREATE TABLE player_state;
```

**回滚**：回退代码即可；表可留着（旧代码不读不写它），确需删除再 `DROP TABLE player_state;`（玩法数据随之丢失）。

---

## M4：资产流水表 `transaction_log`（2026-10-03，xm-data 建）

**原因**：资产审计管线（architecture.md §4.5）的落库表。建表脚本是 `xm-data/src/main/resources/db/xm-data-schema.sql`，
由 **xm-data** 启动时执行（`CREATE TABLE IF NOT EXISTS`），与玩家表的脚本分开、互不改动。

**新库 / 存量库**：都无需手工操作——这是新表，xm-data 第一次启动时建出来。

**核对**：

```sql
SHOW CREATE TABLE transaction_log;
```

**回滚**：回退代码即可；表可留着（旧代码不读不写它）。审计数据有留存要求时不要删表。

## M5：玩家快照表 `player_snapshot`（2026-10-03，xm-data 建）

**原因**：玩家快照（architecture.md §4.5）的落库表，与 M4 同一份建表脚本 `xm-data/src/main/resources/db/xm-data-schema.sql`，
由 **xm-data** 启动时执行（`CREATE TABLE IF NOT EXISTS`）。

**新库 / 存量库**：都无需手工操作——新表，升级后的 xm-data 第一次启动时建出来。

**核对**：

```sql
SHOW CREATE TABLE player_snapshot;
```

**回滚**：回退代码即可；表可留着（旧代码不读不写它）。快照是回档素材，有留存要求时不要删表。

## M6：账号口令哈希列 `account.password_hash`（2026-10-04）

**原因**：生产口令认证（批次 3.2，PARITY「生产口令认证」行）读账号的 Argon2id 哈希。哈希放在 `account` 表新加的一列，
登录只读不写；哈希由离线工具 `com.game.login.tools.PasswordAdmin` 给既有账号写入一次（不注册、不改密）。

**新库**：无需操作（建表脚本已带这一列）。

**存量库**（在 `xm_java` 上执行；只加一列可空列，不必停服）：

```sql
ALTER TABLE account
    ADD COLUMN password_hash VARCHAR(255) NULL
        COMMENT 'Argon2id PHC 串；NULL = 该账号不能口令登录（生产口令认证只读，登录不写）'
        AFTER created_at;
```

**核对**：

```sql
SHOW CREATE TABLE account;                                   -- 有 password_hash 列
SELECT COUNT(*) FROM account WHERE password_hash IS NOT NULL; -- 迁移后为 0，之后随 PasswordAdmin 增加
```

**回滚**：

```sql
ALTER TABLE account DROP COLUMN password_hash;
```

回滚后必须同时回退代码，或关掉生产口令认证（`xm.login.auth.password.enabled=false`）：新代码的口令查询引用这一列，
跑在旧表上口令登录一律失败（查询出错按认证失败处理，不影响 dev 口令与三方登录）。

## M7：区服目录 / 白名单 / 登录公告表 `zone_config`、`zone_whitelist`、`announcement`（2026-10-04，xm-gateway 建）

**原因**：区服目录改为可运行时修改（批次 3.4a，PARITY「区服目录与运维接口」「登录公告」「区服白名单」行）。建表脚本是
`xm-gateway-store/src/main/resources/db/xm-gateway-schema.sql`，由 **xm-gateway** 启动时执行（`CREATE TABLE IF NOT EXISTS`），
随后按 `xm.gateway.seed-zones` 播种库里没有的区（已有的不动）。xm-data 的运维接口只读写、不建表。
播种的区以首次插入的时刻为创建时刻（同基线 schema 播种用 CURRENT_TIMESTAMP），所以升级后第一次启动播种出的区 7 天内在区服列表里显示为新区（`is_new`）。

**新库 / 存量库**：都无需手工操作——新表，升级后的 xm-gateway 第一次启动时建出来并播种。原来写在
`xm.gateway.zones` 里的区服配置改名为 `xm.gateway.seed-zones`（只在库里没有该区时生效）；之后改状态 / 文案走 `/admin/zones`。

**核对**：

```sql
SHOW CREATE TABLE zone_config;
SELECT zone_id, name, manual_status, recommended, sort_order FROM zone_config;
```

**回滚**：回退代码即可；表可留着（旧代码不读不写它，区服回到读静态配置）。

## M8：流水查询索引、快照操作人 / 备注列与运维作业表（2026-10-05，xm-data，批次 7.2a）

**原因**：数据运维（data-ops-spec §7.3）。
- `transaction_log` 加三条二级索引：全服按物品 / 按币种的查询与回收（`idx_txlog_item (kind, item_config_id, time_ms)`、
  `idx_txlog_currency (kind, currency_type, time_ms)`）、物品追溯（`idx_txlog_uuid (item_uuid, time_ms)`）。不合成一条
  `(kind, item_config_id, currency_type, time_ms)`：那要依赖「物品行币种恒 0、货币行配置号恒 0」，被打破时回收会静默漏行。
- `player_snapshot` 加 `operator`、`note`：xm-data 直写的快照（手工 / 维护前，以后的回档前安全快照）记操作人与备注；
  scene 经 Kafka 的行两列为空串（`DEFAULT ''`，Kafka 落库的 INSERT 不用改）。
- 运维作业表 `ops_job`、`ops_job_event`、`ops_job_player`、`ops_active`、`recall_source`、`audit_replay_line`：由 xm-pbmysql 按
  `xm-data/src/main/proto/xm/data/ops_tables.proto` 在 xm-data 启动时建表 / 只扩不缩地补列补索引（同 xm-trade / xm-guild），**不需要手工迁移**。

**行为变化**：无客户端可见变化。热表 `transaction_log` 多三条二级索引会放大落库写入（可接受）。

**新库**：无需操作（建表脚本 `xm-data/src/main/resources/db/xm-data-schema.sql` 已带索引与两列；运维表启动时自动建）。

**存量库**（在 `xm_java` 上执行；只加索引与带缺省值的列，不必停服；大表加索引用 InnoDB 在线 DDL）：

```sql
ALTER TABLE transaction_log
    ADD KEY idx_txlog_item (kind, item_config_id, time_ms),
    ADD KEY idx_txlog_currency (kind, currency_type, time_ms),
    ADD KEY idx_txlog_uuid (item_uuid, time_ms);
ALTER TABLE player_snapshot
    ADD COLUMN operator VARCHAR(64)  NOT NULL DEFAULT '' COMMENT 'xm-data 直写的操作人；scene 经 Kafka 的为空',
    ADD COLUMN note     VARCHAR(256) NOT NULL DEFAULT '' COMMENT '直写的备注（原因 / 事件 / 作业号）';
```

**核对**：迁移后 `SHOW CREATE TABLE transaction_log` / `player_snapshot` 与新库逐字相同（`XmDataMysqlIntegrationTest` 用迁移前的建表脚本
`xm-data/src/test/resources/db/xm-data-schema-before-m8.sql` + 上面的语句（`db/xm-data-migration-m8.sql`）对拍）。

```sql
SHOW CREATE TABLE transaction_log;   -- 有 idx_txlog_item / idx_txlog_currency / idx_txlog_uuid
SHOW CREATE TABLE player_snapshot;   -- 有 operator / note
SHOW TABLES LIKE 'ops\_%';           -- xm-data 启动后有 ops_active / ops_job / ops_job_event / ops_job_player
SHOW TABLES LIKE 'recall_source';    -- 同上（pbmysql 建）
SHOW TABLES LIKE 'audit_replay_line';
```

**回滚**：

```sql
ALTER TABLE transaction_log DROP INDEX idx_txlog_item, DROP INDEX idx_txlog_currency, DROP INDEX idx_txlog_uuid;
ALTER TABLE player_snapshot DROP COLUMN operator, DROP COLUMN note;
```

回滚后必须同时回退代码：新代码的快照直写 / 列表查询引用这两列。运维作业表可留着（旧代码不读不写）；作业行是运维审计，有留存要求时不要删。

## M9：`player` 按归属区的索引 `idx_player_zone`；运维作业表加列 `ops_job.cancel_requested`（2026-10-05，批次 7.2b）

**原因**：GM 回档（data-ops-spec §4.9、Q9）。整区 / 多区回档与整区维护前快照的目标 = `player.zone_id ∈ zones` 的玩家（归属区，权威列），
xm-data 按 `zone_id = ? AND player_id > ? ORDER BY player_id LIMIT 500` 分页读；没有索引就是全表扫。二级索引自带主键，按玩家号续翻不用排序。
建表脚本仍只由 xm-login 执行（`xm-player-store/src/main/resources/db/xm-player-schema.sql`）。

另：`ops_job` 新增 `cancel_requested`（`POST /admin/ops-jobs/{id}/cancel`，执行线程在阶段边界查它）——由 xm-pbmysql 在 xm-data 启动时
只扩不缩地补列，**不需要手工迁移**。

**行为变化**：无客户端可见变化。`player` 多一条二级索引，玩家写入（建角、换 zone）多维护一棵索引树，可忽略。

**新库**：无需操作（建表脚本已带索引）。

**存量库**（在 `xm_java` 上执行；只加索引，不必停服；大表用 InnoDB 在线 DDL）：

```sql
ALTER TABLE player ADD KEY idx_player_zone (zone_id);
```

**核对**：迁移后 `SHOW CREATE TABLE player` 与新库逐字相同（`XmDataMysqlIntegrationTest` 用迁移前的建表脚本
`xm-data/src/test/resources/db/xm-player-schema-before-m9.sql` + 上面的语句（`db/xm-player-migration-m9.sql`）对拍，并 EXPLAIN 按区分页走 `idx_player_zone`）。

```sql
SHOW CREATE TABLE player;            -- 有 KEY `idx_player_zone` (`zone_id`)
SHOW COLUMNS FROM ops_job LIKE 'cancel_requested';   -- xm-data 启动后有
```

**回滚**：

```sql
ALTER TABLE player DROP INDEX idx_player_zone;
```

回滚后整区回档 / 维护前快照仍能执行（查询照样对），只是退化成全表扫；`ops_job.cancel_requested` 可留着（旧代码不读不写）。

## M10：`player_state` 载体新字段 `battle_ledger = 9`（2026-10-06，批次 6.3；只登记字段号，无需迁移）

**变更**：`player_state.data`（MEDIUMBLOB = `xm.storage.PlayerState`，M3）里新增一段回合制战斗结算幂等账本
`BattleLedgerState battle_ledger = 9`（`BattleLedgerState{repeated BattleLedgerEntry applied = 1}`，`BattleLedgerEntry{battle_id = 1, applied_at_ms = 2}`；
按 battle_id 无符号升序写出，稳态 0～1 条、上限 64）。它与货币 / 背包 / 宝宝 / 任务在同一份记录里、随同一次带 `owner_epoch` 围栏的写落库
（architecture.md §4.23、§7；规格 docs/porting/scene-battle-spec.md §7.12）。**表结构没有变化**，blob 里多一个 protobuf 字段而已。

`PlayerState` 顶层字段号现状（字段号永不复用，删字段写 `reserved`）：1 facing、2 currency、3 attribute、4 bag、5 mission、6 vitals、7 pets、8 asset_ledger、9 battle_ledger；下一个空闲号是 10。

同批另有两处只增不改、同样不动表的枚举值：`xm.audit.TransactionReason` 追加 `TX_ITEM_AWARD = 20`（战斗掉落入包，数值同 mmorpg）与 `TX_BATTLE_REWARD = 1005`
（战斗结算的金币，Java 独有）——`transaction_log.reason` 是数值列，xm-data 按数值原样落库。

**新库 / 存量库**：都无需手工操作。存量玩家没有这一段，读到的是「没有待销账的局」（空账本），第一次结算应用之后的存盘才写出。

**核对**：无 DDL 可核对；改了 proto 之后按 AGENTS.md §4 `clean install`。

**回滚**：回退代码即可，不必清数据。旧版本的 scene 不认识字段 9，按未知字段原样带回、不会抹掉（不认识的玩法数据原样带回，PARITY「玩家持久化数据模型」行）。
回退期间没有人应用结算，Redis 里的待结算记录（`xm:battle:{pid}:settlement`，TTL 7 天）留着，升回新版本后由进场恢复照常应用、按账本去重。
GM 回档（7.2b）把这一段归在资产组里随 `assets` / FULL 整段回退（data-ops-spec §4.5）。

## M11：评分表 `match_rating`、`match_rating_applied`（2026-10-08，xm-match 建，批次 6.4；pbmysql 启动时自建，无需手工迁移）

**原因**：匹配评分（architecture.md §4.24；规格 docs/porting/match-spec.md §5.2）。基线把评分放在 Redis（每人一个 HASH + 每局一个入账标记，两层幂等 Lua）；Java 改成 MySQL 一局一笔事务，
所以多两张表。它们由 xm-pbmysql 按 `xm-match/src/main/proto/xm/match/match_tables.proto` 在 xm-match 启动时建表 / 只扩不缩地补列补索引（`MatchRatingTables` → `PbMysql.syncAll`，
同 xm-trade / xm-guild / xm-data 的运维作业表），**没有手写的建表脚本，也不需要手工迁移**；本条只作登记。

| 表 | 主键 | 其余列 | 索引 | 说明 |
|---|---|---|---|---|
| `match_rating`（`MatchRatingRow`） | `player_id` | `rating_centi`（评分 × 100，1500.00 = 150000，下限 0）、`games`（已入账的计分局数）、`updated_at_ms` | — | 每个打过计分模式（1V1 / 5V5）的玩家一行；没有行 = 新号，按 1500.00 读。永不清理 |
| `match_rating_applied`（`MatchRatingAppliedRow`） | `battle_id` | `match_mode`、`delta_a_centi`（A 队每人的增量 × 100，只作审计）、`applied_at_ms` | `idx_match_rating_applied_0 (applied_at_ms)` | 入账标记：每一局计分对局一行，主键冲突 = 重复投递（恰好一次的依据）。保留 30 天，由 xm-match 每小时分批清理（`RatingCleanup`） |

改表纪律同其它 pbmysql 表：字段只追加、字段号永不复用（删字段用 `reserved` 占住），索引只能追加到末尾（索引名按位置编号）；两张表的追加区都从字段号 5 起。结构漂移（线上列比 proto 窄、同名索引定义不同等）时
xm-match 拒绝启动，由人工对齐。

**行为变化**：无客户端可见变化（评分不下发）。xm-match 因此依赖 MySQL：建表失败拒绝启动；连接串是会话级 READ COMMITTED、`innodb_lock_wait_timeout = 1`（入账撞锁就整笔重跑）。
入账标记的保留期（30 天）必须长于对局结果消息在 Kafka topic 里的最长寿命（保留 7 天 + 滚段周期约 7 天），改 topic 的保留期时要一起重算（`MatchRatingTablesTest` 钉住这条不等式）。

**新库 / 存量库**：都无需手工操作，xm-match 第一次启动时建表。库用户需要建表与建索引的权限（同其它 pbmysql 表）。排队、票据、切磋与战斗落点记录都只在 Redis，本批没有别的库表变化；
`player_state` 的字段号不变（下一个空闲号仍是 10）。

**核对**：

```sql
SHOW TABLES LIKE 'match\_rating%';          -- xm-match 启动后有 match_rating / match_rating_applied
SHOW CREATE TABLE match_rating;             -- 主键 player_id；列注释带 pb:N（pbmysql 按它识别字段）
SHOW CREATE TABLE match_rating_applied;     -- 主键 battle_id；有 KEY idx_match_rating_applied_0 (applied_at_ms)
```

**回滚**：回退代码即可（不再起 xm-match），两张表可留着，别的进程不读不写它们。要清空评分重来时删表或删行即可，xm-match 下次启动重建；
入账标记是「同一局只入账一次」的唯一依据：清掉 `match_rating_applied` 之后不要再从更早的位点重放结果 topic（换消费组名、人工重置位点），否则 topic 里还留着的局会再入账一次。
