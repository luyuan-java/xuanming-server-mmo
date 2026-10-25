# 库表迁移记录（Java 版，库 `<ns>` = 部署命名空间，缺省 `game`，见 M13）

建表脚本 `game-player-store/src/main/resources/db/game-player-schema.sql` 由 game-login 启动时执行，只用
`CREATE TABLE IF NOT EXISTS`：**新库**直接得到最新结构，**已存在的表不会被改动**。所以每次结构变更都要在这里登记一条
「存量库手工迁移」，按顺序执行。只追加，不删旧条目。

执行前先停掉 game-login / game-scene（它们会写 `player` 表），迁移完再起。

> **改名说明**：M1–M11 写于 2026-10-12 改名（`8cbba6c`）之前，当时库名是 `xm_java`，文中各条已按新名 `game` 改写。
> 改名前的存量库先在 `xm_java` 上补齐到 M11，再按 M12 搬到 `game`。

---

## M1（2026-09-29）玩家名唯一改为大小写不敏感：`name_key` + `uk_player_name_key`

**原因**：原唯一索引 `uk_player_name (name)` 建在展示名上，库的排序规则是 `utf8mb4_bin`（逐字节比较），
「Alice」与「alice」能同时建出来。改为在唯一键列 `name_key` 上建唯一索引；键 = NFKC → 去首尾空白 → `Locale.ROOT` 小写，
唯一出处是 `PlayerStore.nameKey`（Java 侧计算，调用方拿不到也填不了）。

**行为变化**：建角时与已有角色仅大小写 / 全半角不同的名字回 2033（名字已被占用）。`PlayerStore.createPlayer` 撞到
名字以外的唯一约束（主键 `player_id`）时抛异常（客户端收到 1003），不再报「重名」。

**新库**：无需操作。

**存量开发库**（在 `game` 上执行）：

```sql
-- 1. 加列（先带空默认值，存量行才能加上 NOT NULL 列）
ALTER TABLE player
    ADD COLUMN name_key VARCHAR(64) NOT NULL DEFAULT ''
        COMMENT '名字唯一键：NFKC + 去首尾空白 + 小写，唯一出处 PlayerStore.nameKey；全服唯一'
        AFTER name;

-- 2. 回填。存量名字都经过 game-login 的 PlayerNames.normalize（已做 NFKC 与去首尾空白，字符集只有
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
- 归属仍被持有时 EnterGame 会先请持有者让出（旧连接收到 23 {2017} 后被关闭）并等待最多 `game.login.owner-claim-wait`（3s），
  等不到回 2005；
- scene 进程被 kill（没释放）后，该节点上的玩家要等租约过期（最多 30s）才能再进游戏。

**新库**：无需操作。

**存量开发库**（在 `game` 上执行；先停 game-login / game-scene）：

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

**变更**：新增表 `player_state`（`player_id` 主键、`data` MEDIUMBLOB = `game.storage.PlayerState`、`saved_epoch`、`updated_at`），
存各玩法的持久化数据（首个玩法数据：朝向）。在线存盘与离场写回都在同一事务里先带围栏更新 `player` 行，通过才 upsert 本表。

**新库 / 存量库**：都无需手工操作——这是新表，建表脚本的 `CREATE TABLE IF NOT EXISTS` 启动时自动建出。
存量玩家没有这一行，读到默认实例（各玩法都取初始状态），第一次写回或在线存盘时补上。

**核对**：

```sql
SHOW CREATE TABLE player_state;
```

**回滚**：回退代码即可；表可留着（旧代码不读不写它），确需删除再 `DROP TABLE player_state;`（玩法数据随之丢失）。

---

## M4：资产流水表 `transaction_log`（2026-10-03，game-data 建）

**原因**：资产审计管线（architecture.md §4.5）的落库表。建表脚本是 `game-data/src/main/resources/db/game-data-schema.sql`，
由 **game-data** 启动时执行（`CREATE TABLE IF NOT EXISTS`），与玩家表的脚本分开、互不改动。

**新库 / 存量库**：都无需手工操作——这是新表，game-data 第一次启动时建出来。

**核对**：

```sql
SHOW CREATE TABLE transaction_log;
```

**回滚**：回退代码即可；表可留着（旧代码不读不写它）。审计数据有留存要求时不要删表。

## M5：玩家快照表 `player_snapshot`（2026-10-03，game-data 建）

**原因**：玩家快照（architecture.md §4.5）的落库表，与 M4 同一份建表脚本 `game-data/src/main/resources/db/game-data-schema.sql`，
由 **game-data** 启动时执行（`CREATE TABLE IF NOT EXISTS`）。

**新库 / 存量库**：都无需手工操作——新表，升级后的 game-data 第一次启动时建出来。

**核对**：

```sql
SHOW CREATE TABLE player_snapshot;
```

**回滚**：回退代码即可；表可留着（旧代码不读不写它）。快照是回档素材，有留存要求时不要删表。

## M6：账号口令哈希列 `account.password_hash`（2026-10-04）

**原因**：生产口令认证（批次 3.2，PARITY「生产口令认证」行）读账号的 Argon2id 哈希。哈希放在 `account` 表新加的一列，
登录只读不写；哈希由离线工具 `com.game.login.tools.PasswordAdmin` 给既有账号写入一次（不注册、不改密）。

**新库**：无需操作（建表脚本已带这一列）。

**存量库**（在 `game` 上执行；只加一列可空列，不必停服）：

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

回滚后必须同时回退代码，或关掉生产口令认证（`game.login.auth.password.enabled=false`）：新代码的口令查询引用这一列，
跑在旧表上口令登录一律失败（查询出错按认证失败处理，不影响 dev 口令与三方登录）。

## M7：区服目录 / 白名单 / 登录公告表 `zone_config`、`zone_whitelist`、`announcement`（2026-10-04，game-gateway 建）

**原因**：区服目录改为可运行时修改（批次 3.4a，PARITY「区服目录与运维接口」「登录公告」「区服白名单」行）。建表脚本是
`game-gateway-store/src/main/resources/db/game-gateway-schema.sql`，由 **game-gateway** 启动时执行（`CREATE TABLE IF NOT EXISTS`），
随后按 `game.gateway.seed-zones` 播种库里没有的区（已有的不动）。game-data 的运维接口只读写、不建表。
播种的区以首次插入的时刻为创建时刻（同基线 schema 播种用 CURRENT_TIMESTAMP），所以升级后第一次启动播种出的区 7 天内在区服列表里显示为新区（`is_new`）。

**新库 / 存量库**：都无需手工操作——新表，升级后的 game-gateway 第一次启动时建出来并播种。原来写在
`game.gateway.zones` 里的区服配置改名为 `game.gateway.seed-zones`（只在库里没有该区时生效）；之后改状态 / 文案走 `/admin/zones`。

**核对**：

```sql
SHOW CREATE TABLE zone_config;
SELECT zone_id, name, manual_status, recommended, sort_order FROM zone_config;
```

**回滚**：回退代码即可；表可留着（旧代码不读不写它，区服回到读静态配置）。

## M8：流水查询索引、快照操作人 / 备注列与运维作业表（2026-10-05，game-data，批次 7.2a）

**原因**：数据运维（data-ops-spec §7.3）。
- `transaction_log` 加三条二级索引：全服按物品 / 按币种的查询与回收（`idx_txlog_item (kind, item_config_id, time_ms)`、
  `idx_txlog_currency (kind, currency_type, time_ms)`）、物品追溯（`idx_txlog_uuid (item_uuid, time_ms)`）。不合成一条
  `(kind, item_config_id, currency_type, time_ms)`：那要依赖「物品行币种恒 0、货币行配置号恒 0」，被打破时回收会静默漏行。
- `player_snapshot` 加 `operator`、`note`：game-data 直写的快照（手工 / 维护前，以后的回档前安全快照）记操作人与备注；
  scene 经 Kafka 的行两列为空串（`DEFAULT ''`，Kafka 落库的 INSERT 不用改）。
- 运维作业表 `ops_job`、`ops_job_event`、`ops_job_player`、`ops_active`、`recall_source`、`audit_replay_line`：由 game-pbmysql 按
  `game-data/src/main/proto/game/data/ops_tables.proto` 在 game-data 启动时建表 / 只扩不缩地补列补索引（同 game-trade / game-guild），**不需要手工迁移**。

**行为变化**：无客户端可见变化。热表 `transaction_log` 多三条二级索引会放大落库写入（可接受）。

**新库**：无需操作（建表脚本 `game-data/src/main/resources/db/game-data-schema.sql` 已带索引与两列；运维表启动时自动建）。

**存量库**（在 `game` 上执行；只加索引与带缺省值的列，不必停服；大表加索引用 InnoDB 在线 DDL）：

```sql
ALTER TABLE transaction_log
    ADD KEY idx_txlog_item (kind, item_config_id, time_ms),
    ADD KEY idx_txlog_currency (kind, currency_type, time_ms),
    ADD KEY idx_txlog_uuid (item_uuid, time_ms);
ALTER TABLE player_snapshot
    ADD COLUMN operator VARCHAR(64)  NOT NULL DEFAULT '' COMMENT 'game-data 直写的操作人；scene 经 Kafka 的为空',
    ADD COLUMN note     VARCHAR(256) NOT NULL DEFAULT '' COMMENT '直写的备注（原因 / 事件 / 作业号）';
```

**核对**：迁移后 `SHOW CREATE TABLE transaction_log` / `player_snapshot` 与新库逐字相同（`DataMysqlIntegrationTest` 用迁移前的建表脚本
`game-data/src/test/resources/db/game-data-schema-before-m8.sql` + 上面的语句（`db/game-data-migration-m8.sql`）对拍）。

```sql
SHOW CREATE TABLE transaction_log;   -- 有 idx_txlog_item / idx_txlog_currency / idx_txlog_uuid
SHOW CREATE TABLE player_snapshot;   -- 有 operator / note
SHOW TABLES LIKE 'ops\_%';           -- game-data 启动后有 ops_active / ops_job / ops_job_event / ops_job_player
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
game-data 按 `zone_id = ? AND player_id > ? ORDER BY player_id LIMIT 500` 分页读；没有索引就是全表扫。二级索引自带主键，按玩家号续翻不用排序。
建表脚本仍只由 game-login 执行（`game-player-store/src/main/resources/db/game-player-schema.sql`）。

另：`ops_job` 新增 `cancel_requested`（`POST /admin/ops-jobs/{id}/cancel`，执行线程在阶段边界查它）——由 game-pbmysql 在 game-data 启动时
只扩不缩地补列，**不需要手工迁移**。

**行为变化**：无客户端可见变化。`player` 多一条二级索引，玩家写入（建角、换 zone）多维护一棵索引树，可忽略。

**新库**：无需操作（建表脚本已带索引）。

**存量库**（在 `game` 上执行；只加索引，不必停服；大表用 InnoDB 在线 DDL）：

```sql
ALTER TABLE player ADD KEY idx_player_zone (zone_id);
```

**核对**：迁移后 `SHOW CREATE TABLE player` 与新库逐字相同（`DataMysqlIntegrationTest` 用迁移前的建表脚本
`game-data/src/test/resources/db/game-player-schema-before-m9.sql` + 上面的语句（`db/game-player-migration-m9.sql`）对拍，并 EXPLAIN 按区分页走 `idx_player_zone`）。

```sql
SHOW CREATE TABLE player;            -- 有 KEY `idx_player_zone` (`zone_id`)
SHOW COLUMNS FROM ops_job LIKE 'cancel_requested';   -- game-data 启动后有
```

**回滚**：

```sql
ALTER TABLE player DROP INDEX idx_player_zone;
```

回滚后整区回档 / 维护前快照仍能执行（查询照样对），只是退化成全表扫；`ops_job.cancel_requested` 可留着（旧代码不读不写）。

## M10：`player_state` 载体新字段 `battle_ledger = 9`（2026-10-06，批次 6.3；只登记字段号，无需迁移）

**变更**：`player_state.data`（MEDIUMBLOB = `game.storage.PlayerState`，M3）里新增一段回合制战斗结算幂等账本
`BattleLedgerState battle_ledger = 9`（`BattleLedgerState{repeated BattleLedgerEntry applied = 1}`，`BattleLedgerEntry{battle_id = 1, applied_at_ms = 2}`；
按 battle_id 无符号升序写出，稳态 0～1 条、上限 64）。它与货币 / 背包 / 宝宝 / 任务在同一份记录里、随同一次带 `owner_epoch` 围栏的写落库
（architecture.md §4.23、§7；规格 docs/porting/scene-battle-spec.md §7.12）。**表结构没有变化**，blob 里多一个 protobuf 字段而已。

`PlayerState` 顶层字段号现状（字段号永不复用，删字段写 `reserved`）：1 facing、2 currency、3 attribute、4 bag、5 mission、6 vitals、7 pets、8 asset_ledger、9 battle_ledger；下一个空闲号是 10。

同批另有两处只增不改、同样不动表的枚举值：`game.audit.TransactionReason` 追加 `TX_ITEM_AWARD = 20`（战斗掉落入包，数值同 mmorpg）与 `TX_BATTLE_REWARD = 1005`
（战斗结算的金币，Java 独有）——`transaction_log.reason` 是数值列，game-data 按数值原样落库。

**新库 / 存量库**：都无需手工操作。存量玩家没有这一段，读到的是「没有待销账的局」（空账本），第一次结算应用之后的存盘才写出。

**核对**：无 DDL 可核对；改了 proto 之后按 AGENTS.md §4 `clean install`。

**回滚**：回退代码即可，不必清数据。旧版本的 scene 不认识字段 9，按未知字段原样带回、不会抹掉（不认识的玩法数据原样带回，PARITY「玩家持久化数据模型」行）。
回退期间没有人应用结算，Redis 里的待结算记录（`game:battle:{pid}:settlement`，TTL 7 天）留着，升回新版本后由进场恢复照常应用、按账本去重。
GM 回档（7.2b）把这一段归在资产组里随 `assets` / FULL 整段回退（data-ops-spec §4.5）。

## M11：评分表 `match_rating`、`match_rating_applied`（2026-10-08，game-match 建，批次 6.4；pbmysql 启动时自建，无需手工迁移）

**原因**：匹配评分（architecture.md §4.24；规格 docs/porting/match-spec.md §5.2）。基线把评分放在 Redis（每人一个 HASH + 每局一个入账标记，两层幂等 Lua）；Java 改成 MySQL 一局一笔事务，
所以多两张表。它们由 game-pbmysql 按 `game-match/src/main/proto/game/match/match_tables.proto` 在 game-match 启动时建表 / 只扩不缩地补列补索引（`MatchRatingTables` → `PbMysql.syncAll`，
同 game-trade / game-guild / game-data 的运维作业表），**没有手写的建表脚本，也不需要手工迁移**；本条只作登记。

| 表 | 主键 | 其余列 | 索引 | 说明 |
|---|---|---|---|---|
| `match_rating`（`MatchRatingRow`） | `player_id` | `rating_centi`（评分 × 100，1500.00 = 150000，下限 0）、`games`（已入账的计分局数）、`updated_at_ms` | — | 每个打过计分模式（1V1 / 5V5）的玩家一行；没有行 = 新号，按 1500.00 读。永不清理 |
| `match_rating_applied`（`MatchRatingAppliedRow`） | `battle_id` | `match_mode`、`delta_a_centi`（A 队每人的增量 × 100，只作审计）、`applied_at_ms` | `idx_match_rating_applied_0 (applied_at_ms)` | 入账标记：每一局计分对局一行，主键冲突 = 重复投递（恰好一次的依据）。保留 30 天，由 game-match 每小时分批清理（`RatingCleanup`） |

改表纪律同其它 pbmysql 表：字段只追加、字段号永不复用（删字段用 `reserved` 占住），索引只能追加到末尾（索引名按位置编号）；两张表的追加区都从字段号 5 起。结构漂移（线上列比 proto 窄、同名索引定义不同等）时
game-match 拒绝启动，由人工对齐。

**行为变化**：无客户端可见变化（评分不下发）。game-match 因此依赖 MySQL：建表失败拒绝启动；连接串是会话级 READ COMMITTED、`innodb_lock_wait_timeout = 1`（入账撞锁就整笔重跑）。
入账标记的保留期（30 天）必须长于对局结果消息在 Kafka topic 里的最长寿命（保留 7 天 + 滚段周期约 7 天），改 topic 的保留期时要一起重算（`MatchRatingTablesTest` 钉住这条不等式）。

**新库 / 存量库**：都无需手工操作，game-match 第一次启动时建表。库用户需要建表与建索引的权限（同其它 pbmysql 表）。排队、票据、切磋与战斗落点记录都只在 Redis，本批没有别的库表变化；
`player_state` 的字段号不变（下一个空闲号仍是 10）。

**核对**：

```sql
SHOW TABLES LIKE 'match\_rating%';          -- game-match 启动后有 match_rating / match_rating_applied
SHOW CREATE TABLE match_rating;             -- 主键 player_id；列注释带 pb:N（pbmysql 按它识别字段）
SHOW CREATE TABLE match_rating_applied;     -- 主键 battle_id；有 KEY idx_match_rating_applied_0 (applied_at_ms)
```

**回滚**：回退代码即可（不再起 game-match），两张表可留着，别的进程不读不写它们。要清空评分重来时删表或删行即可，game-match 下次启动重建；
入账标记是「同一局只入账一次」的唯一依据：清掉 `match_rating_applied` 之后不要再从更早的位点重放结果 topic（换消费组名、人工重置位点），否则 topic 里还留着的局会再入账一次。

---

## M12：库改名 `xm_java` → `game`，连带 Redis 键前缀、Kafka topic 与环境变量改名（2026-10-12，`8cbba6c`）

**原因**：用户要求这套服务端可复用于别的游戏，名字里不再带本游戏的缩写 `xm`。全仓的 `xm` 前缀统一改为 `game`：库 `xm_java` → `game`、库用户 `xm_app` → `game_app`；
Redis 键前缀 `xm:` → `game:`（`RedisKeys.PREFIX`——当时的静态写法；M13 起是注入的实例方法 `prefix()`，缺省命名空间下值仍是 `game:`）；Kafka topic `xm-*` → `game-*`（如 `game-transaction-log-g<N>`、`game-player-snapshot-g<N>`、`game-battle-result-g<代次>`）、消费组同改；
环境变量 `XM_*` → `GAME_*`；配置键 `xm.*` → `game.*`。表结构**没有任何变化**。

**行为变化**：无客户端可见变化。新旧进程**不能混跑**：Dubbo 鉴权附件键、排队令牌的派生域串、节点号租约的 Redis 键（`game:node-id-epoch:*`）都随前缀改了名，
旧进程与新进程会按 fail-closed 互相拒绝；节点号 epoch 从零起算，只有在全部进程停掉之后切换才安全。

**新库**：无需操作（`MYSQL_DATABASE: game`；game-login 首次启动建表）。

**存量库（本机开发环境）**：compose 项目名在同一轮改名里从 `xuanming-java` 改成了 `game-java`，卷名随之变成 `game-java_*`，新项目起来就是空库空 Redis；旧项目的卷用
`docker compose -p xuanming-java -f deploy/compose/infra.yaml down -v` 清掉（或留着搬数据）。推荐整体重建——`docker compose -f deploy/compose/infra.yaml down -v` 后重新 `up`，MySQL 与 Redis **一起**清（只清其中一边会造成
infra.yaml 头部明令禁止的「节点号 epoch 回退」与 Redis / MySQL 权威数据不一致）。一定要保留数据时，必须两边同时迁、不许只迁一边：

1. 停掉全部 Java 进程（用改名**之前**版本的 `tools/local/stop-slice.sh`，它认 `run/pids/xm-*.pid`；或 `for p in run/pids/xm-*.pid; do kill "$(cat "$p")"; done`），删掉 `run/xm-*` 与 `run/pids/xm-*.pid`；
2. MySQL：`CREATE DATABASE game CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;`，按 `information_schema.tables` 生成并执行 `RENAME TABLE xm_java.<t> TO game.<t>;`（同实例可跨库改名），
   `game_app` 按 ops-release-spec §7.5 授权；旧库 `xm_java` 留着或删掉都可以；
3. Redis：把全部 `xm:*` 键按新前缀改名（`RENAME xm:<rest> game:<rest>`，带 hash tag 的 `xm:{match}:…` / `xm:{team}:…` 同样只换前缀，tag 不变）；
   （2026-10-12 补记）**节点目录 `xm:nodes:*` 不要搬**：它是 Redisson RMapCache，条目的过期分数在派生键 `redisson__timeout__set:{xm:nodes:…}`（及
   `redisson__idle__set:{…}`、`redisson__map_cache__last_access__set:{…}`）里，这些键不以 `xm:` 开头、不会被按前缀改名，只搬主键的话条目被当成永不过期，
   停掉的节点会以幽灵条目留在 `game:nodes:*` 里。停服后删掉 `xm:nodes:*` 与 `redisson__*:{xm:nodes:*}`，进程启动后重新发布（同 M13 第 2 步）；
4. Kafka：旧 topic 里没消费完的流水与快照不迁移（开发环境可以接受）；新 topic 由 game-data / game-battle 启动时经 `TopicAdmin`（`KafkaTopicAdmin`）按新名创建（compose 关了自动建 topic）；
5. 本机 `export` 的 `XM_*` 全部换成 `GAME_*` 后再起切片。

**核对**：

```sql
SHOW DATABASES LIKE 'game';                  -- 存在
SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'game';   -- 与迁移前 xm_java 的表数相同
```

**回滚**：回退代码，并按上面的步骤反向改回 `xm_java` / `xm:` / `XM_*`（同样两边一起）。

---

## M13：部署命名空间 `game.namespace`（2026-10-12；缺省 `game`，表结构与现有名字都不变；新增库名核对，库名不叫 game 的存量部署见「行为变化」）

**原因**：同一套 MySQL / Redis / Kafka（以及 Nacos）上要跑多个游戏或多套部署（architecture.md §6.2）。新增配置项 `game.namespace`（环境变量 `GAME_NAMESPACE`，
缺省 `game`）：MySQL 库名 = `<ns>`（各 `application.yaml` 的 JDBC URL 写 `${game.namespace:game}`，部署只覆盖 host / port）、Redis 键前缀 `<ns>:`、
Kafka topic / 消费组 / client.id 前缀 `<ns>-`、帮会建哨兵行的命名锁 `game_guild_insert_guard_init:<ns>`。compose 的 infra 不读它：预建哪个库由独立变量
`GAME_MYSQL_INIT_DATABASE` 决定（`MYSQL_DATABASE: ${GAME_MYSQL_INIT_DATABASE-game}`，没设仍预建 game，与以前相同）。

**机制**（注入式，architecture.md §6.2）：`game.namespace` 由 game-discovery 的 `GameNamespaceProperties`（`@ConfigurationProperties` + `@Validated`）绑定校验，
自动配置注册 `GameNamespace` / `RedisKeys` / `KafkaNames` 三个 bean 注入给使用方；没有进程级全局值。命令行工具 AuditFallbackReplay、PasswordAdmin、assetopfix
经 `GameNamespaceBinder` 与服务进程同口径绑定同一个属性类型（`-Dgame.namespace` 优先于环境变量 `GAME_NAMESPACE`，assetopfix 再到它的 `-f` yaml）。

**行为变化**：
- **名字**：缺省值下库名、每一个键名、频道、topic、消费组、client.id、锁名都与 M12 之后逐字节相同（金样测试钉住）。
- **取值校验**：`[a-z][a-z0-9]{0,27}`（1–28 个字符）且不是 `mysql` / `sys` / `public`；非法值（大写、空串、首尾空白、超长……）时进程启动即失败（Spring Boot 的
  标准绑定校验报告 `APPLICATION FAILED TO START`，列出 Property / Value / Origin / Reason），命令行工具退出 2。不设就是 `game`，不受影响。
- **缺省命名空间下也新增的拒绝条件——库名核对**（规格：库名 = 命名空间）：
  - 9 个连库的服务进程（data、friend、gateway、guild、login、match、scene、team、trade）启动期、建表之前核对 `SELECT DATABASE()` 等于命名空间，不一致或连不上库即拒启；
  - 命令行工具 AuditFallbackReplay、assetopfix、PasswordAdmin 连上库后、建表 / 写库 / 读口令之前做同一个核对，不一致退出 2。

  按规格部署（URL 的库名段就是 `game`）的环境照常通过，什么都不用做。**库名不叫 `game` 的存量部署**（整串覆盖了 `spring.datasource.url`，或回灌 URL /
  `-f` yaml / `GAME_PASSWORD_ADMIN_JDBC_URL` 指向别的库）升级后会拒启 / 退出 2：要么把库迁成命名空间同名（推荐，下面「给一个已有部署改命名空间」的第 1 步），
  要么应急给出期望库名——服务进程设 `GAME_MYSQL_EXPECT_DATABASE=<库名>`（`game.mysql.expect-database`；规范写法 `GAME_MYSQL_EXPECTDATABASE` 同样认；放行时打 WARN，
  给了空值按标准的绑定校验报告拒启），工具加 `--expect-database <库名>`（assetopfix 是 `-expect-database`）。应急开关意味着部署偏离规格，应安排迁移。
- **消费组缺省值的出处**：game-match 的 `consumer-group` 从 yaml 挪到代码（`KafkaNames.matchRatingGroup()`，值不变）；显式配置的值照旧生效。

**新库 / 存量库**：都无需改表。新命名空间的库由进程的 `createDatabaseIfNotExist` 建（game-team 的 URL 不带它，先起 game-login），表由各进程照常建；
compose 的 `MYSQL_DATABASE`（取 `GAME_MYSQL_INIT_DATABASE`，没设为 game）只在第一次初始化数据目录时生效（设成空值即不预建）。按部署分账号时：``CREATE USER '<ns>_app'…; GRANT … ON `<ns>`.* TO '<ns>_app'…``
（命名空间最长 28 个字符，账号名不超过 MySQL 的 32 字符上限）。nacos profile 下多个部署共用一个 Nacos 时，各部署在 `GAME_NACOS_ADDR` 里带 `?namespace=<ID>`（或配 `dubbo.registry.parameters.namespace`；环境变量 `DUBBO_REGISTRY_PARAMETERS_NAMESPACE` Dubbo 不认），
并先在 Nacos 建好 **ID 恰好等于该值** 的命名空间（不自动推导，不设则与以前一样落在 public）。

**给一个已有部署改命名空间**（不提供工具；等同于搬到一个新部署，必须停掉全部进程、各中间件一起搬）：
1. MySQL：建库 `<new>`（`CHARACTER SET utf8mb4 COLLATE utf8mb4_bin`），按 `information_schema.tables` 生成 `RENAME TABLE <old>.<t> TO <new>.<t>;`；
2. Redis：把 `<old>:*` 键改名为 `<new>:*`（hash tag 不变），**节点目录 `<old>:nodes:*` 除外**——它是 Redisson 的 RMapCache，每个条目的过期分数存在派生键
   `redisson__timeout__set:{<old>:nodes:…}`（以及用到时的 `redisson__idle__set:{…}`、`redisson__map_cache__last_access__set:{…}`）里，这些键不以 `<old>:` 开头、不会被
   按前缀改名；只搬主键的话条目查不到过期分数、被当成永不过期，停掉的 gate / scene / battle 节点会以幽灵条目一直留在 `<new>` 的目录里，路由与健康探测都会读到。
   节点目录是短命数据：停服后直接删掉 `<old>:nodes:*` 与 `redisson__*:{<old>:nodes:*}`，进程启动后重新发布（一定要搬就把这三类派生键按 `{<new>:…}` 一起改名）。
   **另外**重写值里嵌着完整键名的数据——匹配队列注册集 `<ns>:{match}:index` 的成员与票据 `<ns>:{match}:ticket:<pid>` 的 `queue_key` 字段
   （都是 `<ns>:{match}:queue:<mode>:<config>`）；不想重写就在停服前让排队清空；
3. Kafka：旧 topic 里没消费完的流水 / 快照 / 对局结果先消费完；新 topic 由进程启动时按新名创建，消费组位点从头开始（审计落库按主键幂等，评分入账按 battle_id 幂等）；
4. Nacos（nacos profile，且这个部署要与别的部署共用 Nacos 时）：命名空间不自动跟着变，在 `GAME_NACOS_ADDR` 里带 `?namespace=<值>`（建议取 `<new>`；或配 `dubbo.registry.parameters.namespace`），
   并先在 Nacos 控制台或 OpenAPI 建好**命名空间 ID = 该值**的 namespace（显示名随意；不填 ID 会生成 UUID，对不上）。环境变量 `DUBBO_REGISTRY_PARAMETERS_NAMESPACE` 与 `parameters[namespace]` 方括号写法 Dubbo 都不认；
5. 全部进程与命令行工具换成同一个 `GAME_NAMESPACE` 再起（推荐经环境变量给；工具与服务进程同口径绑定，给服务进程的值原样给工具）。

**核对**：

```sql
SELECT DATABASE();                           -- 进程用的连接：等于命名空间（服务进程启动期自己会核对，不一致起不来）
```

启动日志里有「部署命名空间 game.namespace=<ns>（Redis 前缀 <ns>:，Kafka 前缀 <ns>-，MySQL 库 <ns>）」与「库名核对通过：DataSource … 的 DATABASE()=<ns>」，
Redisson 客户端创建时的日志带 `key_prefix=<ns>:`。

**回滚**：回退代码即可；缺省命名空间下名字与回退前完全相同（回退也去掉了库名核对）。用过非缺省命名空间的部署回退后会回到 `game` 的库与键空间（看不见 `<ns>` 下的数据），
要么先按上面的步骤搬回、要么回退前不改命名空间。

---

## M14：最终 SAVE 的 SQL 意图与同代准入（2026-10-19 实现候选，待验证/切换）

唯一 schema 入口是 `game-player-store/src/main/resources/db/game-player-schema.sql` 中的 `player_final_save` CREATE。新库随 game-login 的既有初始化建立；存量库在排空窗口按同一 CREATE 扩表，不维护第二份手写 DDL，也不在请求路径临时建表。新启动的玩家权威进程通过独立 PlayerSqlAuthorityAutoConfiguration 验证表、18列类型/宽度/OPEN可空列、`(player_id, owner_epoch)` 主键、due 与 retired 两索引；缺表/旧结构拒启，game-data 自建 PlayerStore 也不豁免。

表的 OPEN 只绑定一次加载的原 scene instance，允许 payload/digest/标量为空；PENDING 与 APPLIED 必须有版本1、序号1、原 protobuf bytes（MEDIUMBLOB，上限16,777,215字节）及 BINARY(32) SHA。应用代码校验完整数据与来源，不通过就保留原行。next_attempt_at/attempt_count/低基数错误分类仅决定重试，绝不改变数据义务。APPLIED 在 player_state 及释放同一事务提交；PENDING 不设清理过期值。

升级前：停止接新登录/迁移/回档，排空当前 scene 并保存未完成最终写证据；停止全部旧 scene、login、data 和任何其他 PlayerStore writer/claim 入口，确认旧进程不会复活；备份玩家表、player_state、player_final_save 与原 epoch 上界；执行唯一正式 schema 扩表并核对元数据；统一启新版本后先补偿待办再开放玩家准入。不支持忽略新门禁的旧进程滚动混跑。该步骤需要运维窗口，本候选没有操作生产或本地持久库。

恢复：PENDING 存在时所有新 claim/历史恢复不能从旧 player_state 继续；同锁帮助完成或等待，损坏数据先隔离人工处理。PITR 必须同点恢复 player/player_state/player_final_save，随后按既有已审计旧 epoch 上界恢复流程前进新 E；不能单删待办或只回一张表。未登记 RAM 不在 SQL 可恢复承诺内。回滚二进制前必须停止新入口、完成所有 PENDING、排空新 writer并保留现 E 的终态凭据；不能让旧二进制越过残留 PENDING。清理每轮有总上限，只删除已有更大 SQL E 且超过七天的旧 OPEN/APPLIED；当前 E 和全部 PENDING 保留。


### 本批实现与验收边界（准备于 2026-10-19T09:04:38.793141+00:00）

本批最终SAVE、运维导入围栏与InboxHooks组合接缝由root在已授权范围统一接手；准备时为工作树实现候选，待root逐文件核SHA后正规apply。源码落地与运行验收分别记录，不以当前资源不足冒完成或回退实现。

- 已有真实证据：Store两夹具类26/26、0skip（C682622E…）；旧续建前13模块成功产物已封存，整体该次仍保留scene夹具首停原件。
- 最近剩余6模块在1809448KiB可用内存门禁停止，native_started=false，未执行Java/Maven/clean（1119636B…）；不是业务测试再次失败，旧8份失败XML不得算本次结果。
- 后续待验：剩余6模块正常clean install、新CLI3方法/原13方法共16条、Hook组合5条、当前155条严格真TiDB，以及实际宿主旧实例复活/延迟ACK故障链。默认H2/条件skip、编译/枚举与历史通过都不能替代它们。
- M14仅完成正式schema/迁移入口源码候选；本批未执行部署或实际库迁移，未启动基础设施。TX1耐久接管凭据仍不表示逐项资产durable=true；资产确认只按player_state实际SQL回执。
- InboxHooks原有接口/外部实现与CLI新增代码保留；生产SceneNode尚未装配InboxService的既有边界独立登记，World/Hook组合测试不能冒真实Inbox端到端验收。

### 2026-10-19 root 正式工作树落地补记（未完成运行验收）

统一37路径已由root正规git apply，check/apply均0；逐37最终字节SHA与已审候选相同，未修改InboxHooks接口，保留并行CLI/收件箱源。实际应用原件为C++/Go仓 `build/verification/java-canonical-37-apply-20261019T090438788234Z/root-canonical-application.json`，SHA `F03743014BE227D961CAD7F37DCA29470A2BDA42B5A238614ECFB7CEAB1930D7`。当前是工作树已实现、验收未完成；没有提交、部署、启动服务或执行M14真实库迁移。

后续直接为当前Java完整源建立新快照（含并行新增代码），先生成含新Mapper公开准入规则的当前Store Jar，再按实际依赖编译受影响模块并运行CLI全16/Hook5和当前155真TiDB。旧JG2464、D136 Store Jar及其26通过/13模块成功仅绑定旧输入，不能冒当前World合并与新CLI已运行；不强制先重复旧快照六模块再重测新代码。最后验收真实宿主故障，任何编译/枚举/H2结果均不替代它。

### M16：唯一player归属与SQL路由围栏（2026-10-21，源码批）

`player.zone_id` 仍是Java唯一home/route事实，账号、账号修订与写者E仍在同一player行；不新增player_route副本。正式私有消息为 `game-player-store/src/main/proto/game/storage/route_authority.proto`，唯一DDL为 `game-common/src/main/resources/db/game-route-authority-schema.sql`。login SQL init先执行该DDL再执行玩家DDL；建结构不播种可写meta。存量库须先显式执行同一DDL，再在停写维护窗口运行 `com.game.data.tools.InitializeRouteAuthority --initialize-from-sql-player --generation <独立记录的非零规范UUID> --zones <显式配置区号列表> --expect-database <业务库名>`；JDBC取GAME_ROUTE_INIT_JDBC，账号/密码只取GAME_MYSQL_USER/GAME_MYSQL_PASSWORD环境，不输出连接信息。此命令要求TiDB强读并核实际DATABASE，不是生产合区或全库恢复工具。

初始化仅从真实SQL player.zone_id与操作者显式区号生成64片空run原件，不读Redis。已有可写世代只允许相同原件重查，缺片、换世代、非空run或已有被冻结meta均拒绝，不能借重跑初始化解除维护围栏。新库没有角色时也须显式指定可建角区号。生产启动只读核meta/列/主键，缺失拒启或拒流，不再默认OPEN。

提交顺序为meta共享锁、相关zone全部64片共享锁、完整角色集合按uint64升序P锁；P后重核预读home，发生变化须回滚重开。创建和导入同时锁显式目标home；普通存盘、归属交出/恢复、Native交易、Team/Trial及原会话服务共用前缀。维护写者须独占同meta/围栏后按同序P；run未明确终结就一直封锁，没有TTL自动放行。H2夹具用更强FOR UPDATE替代共享锁语法，仅真TiDB运行才证明TiDB并发行为。

历史权威恢复必须同时提供独立E上界和账号修订上界，在同一P事务推进两者。全权威库整体PITR还需独立不可回退审计/恢复门禁；当前SQL或旧备份都不能充当独立上界。仅本地开发player-bundle导入仍受原工具本机/命名空间限制，不是生产权威域恢复流程。

### M17：单库合区原件与永久退役标记（2026-10-21，未运行源码批）

同一正式route DDL追加zone_merged_into(zone_id,target_zone,run_id,revision)，只表示源区退役，不复制player home。game-data的zone_merge.proto经ZoneMergeTables登记run/item；game-team的正规TeamPolicyRow追加typed allow_cross_zone与contract_digest，旧试验策略没有digest必须停写审核迁移，不自动补权。所有改变玩家/玩法原件的事务，包括无actor后台写和原Guild单语句claim，都在领域锁之前持meta共享锁。

显式MergeZone工具持metaX证明全局owner_released=1和最终写回已终局后才置meta0并持源目标各64片；过期租约不充当排空。全服短维护期间所有zone业务写暂停。计划、审批hash、原身份/行指纹、批次CAS永久保留，完成仍保持meta0；独立显式Release携原hash、路由世代和操作者才恢复。失败/UNKNOWN不自动清冻；未写实体可按原hash显式Abort后Release，已写必须续跑原run再按原件撤销。源退役marker阻旧入口与新建/导入回源，目录写共用前缀。完整CLI顺序与恢复边界见zone-merge-spec末节；本批未执行DDL、初始化、维护、生成或任何测试。

### M18：原场景准入与永久Inbox原归属收据（2026-10-21，未运行源码批）

正式game-player-schema.sql在既有player_final_save增加admission_proto MEDIUMBLOB NULL，由完整loadForScene(RoleAdmissionIdentity,expectedAccount,timeout)首次同P登记确定性PB；源实例、原account/revision/E与全部入场字段固定。不存在才插入；旧OPEN缺raw、原件不同或非OPEN均拒绝，不能UPDATE补当前身份。旧四参load仅存量测试兼容，不作为生产Inbox消费准入。PlayerSqlAuthorityAutoConfiguration只读核新列类型/容量/可空性，缺列拒启。已有数据库需停新入口、正常排空原scene、审核备份与独立权威水位，再由正式schema迁移流程加列；CREATE IF NOT EXISTS不能代替已有表扩列，也未在本批执行实际迁移。

共享player_inbox_item原owner_account/revision列24/25经正规PB登记同步。已有PENDING无原owner或状态未知的开发/试验行必须人工审核终结/迁移，Inbox启动与运行均拒绝静默补权。终态2/3/4/5永久保留原unique receipt，终态清理不再调度，旧保留期配置不授权物删。Fetch/Ack整请求scene签名与同P完整SQL准入原件双重核验，空流/无流也不能跳过。Native/导入/恢复与合区消费同shard/pid完整终态集合；真正权威SQL全域PITR仍须外部不可回退审计上界与恢复门禁，不能用一并回退的admission/收据自证真相。


### M19：旧OPEN维护退休的永久原件（2026-10-23，实现候选，未运行迁移）

唯一DDL为`game-player-store/src/main/resources/db/game-player-schema.sql`新增`player_trade_admission_recovery`：恢复UUID主键、原角色/oldE唯一、完整v2 typed计划bytes、明确proof_mode、SHA-256、固定RETIRED_BY_SQL_SUCCESSOR与创建时间，全部NOT NULL。不存在TTL、覆盖更新或终态物删。PlayerSqlAuthorityAutoConfiguration按真实元数据核类型、宽度、主键与唯一索引，缺表或旧形状拒启动；CREATE IF NOT EXISTS不能代替已有表结构审计。

升级须先排空并停止全部旧player writer/claim/维护工具，备份player/player_state/final_save/trade四类原件，按唯一DDL加表并核元数据，再启统一新版本。本功能不自动迁移、PITR补权或删除PENDING，不在SQL事务内调用Redis网络。工具默认计划、显式apply固定计划SHA及本地实例；route/P锁下重核更高E的真实SCENE_ACK和双SQL APPLIED，永久收据与精确旧OPEN删除在同一事务。仅提供高E、租约/7天到期、外部JSON或ABSENT均不得当恢复成功。

提交UNKNOWN保留同一计划，重查永久SQL原件；回滚代码前必须保留此表及全部恢复收据，旧工具不得绕过新准入门禁。本次尚未执行DDL、真实库恢复或迁移，编译/H2与真TiDB、迟到旧写并发证据分别登记，不能相互替代。

SQL_NO_ADMISSION维护分支另核当前E从未load（current final实际空）、非NULL真实player_state与全部待投守卫；计划固定剩余旧准入原件集/数量及最后一条标志。较早退休不释放当前E，最后一条复用sealUnadmitted在同事务形成真实无准入封闭，不能用它冒已准入Scene的ACK或APPLIED。该分支与原SCENE_ACK分支都需真实TiDB与并发回归，当前尚未运行。

SQL行证明的编码域现为`player-trade-recovery-sql/v2`：列名、JDBC类型、NULL与长度/行边界固定；binary保原bytes，整数按BigDecimal精确读取，有限double保IEEE位，未知类型拒绝。仅行摘要格式变化，`recoveryBytes`和永久恢复收据协议不变。升级前已生成但未提交的旧计划必须重新plan，实际SQL摘要不等时拒绝apply；已提交的原计划仍先核永久原件/原plan_bytes后只读幂等返回，不重算当前身份或丢弃历史收据。原Q47 H2暴露的DOUBLE转BINARY错误保留；修后H2与真TiDB证据仍须分别实际执行。

### M20：旧OPEN正式维护目标（2026-10-24，已授权，待构建与真实验收）

RecoverTradeAdmission 的维护权限来自有权运维者显式执行及只覆盖审定范围的受限凭据。SHA固定计划完整性，不认证操作人。此入口独立于开发导入：不再把loopback视为维护资格，也不改变LocalTargets、InstanceIdentity或其他debug_import工具的限制。原命名空间与 --expect-database 检查、--expect-instance 真实MySQL/TiDB身份、强会话、默认只计划和显式 --apply --plan --plan-sha256 保留。URL只接受单主机、显式规范端口及单一库名的纯 jdbc:mysql://host:port/schema，拒user-info、query、fragment、多目标及凭据；账号密码仍只取环境，诊断不输出连接串或异常正文。

本工具只验证运维预置的永久表，不建表、不插入、不覆盖、不续期。Java同一权威库保存路由、角色和最终存盘，只核这一实际库；与Go正式维护合同使用相同DDL，部署时由权限独立的运维账号预置：

    CREATE TABLE maintenance_recovery_target (
      purpose VARBINARY(16) NOT NULL,
      zone_id INT UNSIGNED NOT NULL,
      schema_name VARBINARY(64) NOT NULL,
      target_nonce VARBINARY(32) NOT NULL,
      physical_addr VARBINARY(255) NOT NULL,
      PRIMARY KEY (purpose, zone_id, schema_name)
    );

表必须是无TTL的永久目标表；维护账号只读并持行锁，不拥有其DDL或写权限。purpose本入口固定为admission，zone_id必须等于原完整入场身份及计划时真实角色的区号，schema_name逐字等实际DATABASE。--maintenance-target-nonce 为独立非全零32位小写hex，--zone-id 显式固定原区号；physical_addr逐字等本次纯JDBC配置的无凭据host:port，不解析DNS、不据端口/名字/debug标记推导资格。nonce无TTL，时间推移不撤销或放宽维护目标；撤销须由运维改变或移除原标记。此合同不新造TiDB跨版本TTL元数据探针，也不涉及Redis/PTTL。

外层计划升为 player-trade-open-recovery-plan-v3，固定命名空间、实例、库、nonce、zone和物理地址；旧外层v2计划须重新生成。领域RecoveryPlan、player-trade-open-retirement/v2编码、原请求摘要、永久恢复表和原plan_bytes不变。每次plan、apply及永久收据重放，都先在实际SQL事务的同一连接重核强会话/库/实例，按purpose,zone_id,schema_name用FOR UPDATE锁住专用标记，随后保持原route→P→账号→listing/最终写锁序；提交或回滚前不能替换目标。只有debug标记、缺行、错scope/nonce/地址/实例均在领域副作用前拒绝。普通游戏Store构造和交易API不新增维护前提，只有恢复方法默认拒未配置独立目标；永久收据重放仍不查新玩家身份来改绑原请求。

本次不自动建库/迁移、不授予任意PITR能力、不制造SCENE_ACK/APPLIED、不删除pending或交易终局。H2/真实TiDB、正式main身份与A/B流程分别记实际证据；源码与夹具修改不是运行通过。