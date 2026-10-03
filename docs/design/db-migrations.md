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
