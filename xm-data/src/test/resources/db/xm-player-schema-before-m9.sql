-- docs/design/db-migrations.md M9 之前的 xm-player-store 建表脚本（git HEAD 945c590 原样；XmDataMysqlIntegrationTest 用它 + M9 迁移与新建库比对）。
-- Java 版账号 / 玩家表（库 xm_java，与 mmorpg 的库隔离）。启动时由 Spring SQL init 执行，必须幂等。
-- CREATE TABLE IF NOT EXISTS 不会改动已存在的表：结构变更对存量库要手工迁移，步骤见 docs/design/db-migrations.md。

CREATE TABLE IF NOT EXISTS account (
    account     VARCHAR(64)     NOT NULL,
    created_at  BIGINT          NOT NULL COMMENT 'Unix 毫秒',
    password_hash VARCHAR(255)  NULL COMMENT 'Argon2id PHC 串；NULL = 该账号不能口令登录（生产口令认证只读，登录不写）',
    PRIMARY KEY (account)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

CREATE TABLE IF NOT EXISTS player (
    player_id        BIGINT UNSIGNED NOT NULL,
    account          VARCHAR(64)     NOT NULL,
    zone_id          INT UNSIGNED    NOT NULL,
    name             VARCHAR(64)     NOT NULL COMMENT '展示名（原样返回给客户端）',
    name_key         VARCHAR(64)     NOT NULL COMMENT '名字唯一键：NFKC + 去首尾空白 + 小写，唯一出处 PlayerStore.nameKey；全服唯一',
    class_id         INT UNSIGNED    NOT NULL,
    gender           INT UNSIGNED    NOT NULL,
    appearance_id    VARCHAR(64)     NOT NULL DEFAULT '',
    level            INT UNSIGNED    NOT NULL DEFAULT 1,
    scene_config_id  INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '上次所在场景配置；0 = 从未进过场景',
    pos_x            DOUBLE          NOT NULL DEFAULT 0,
    pos_y            DOUBLE          NOT NULL DEFAULT 0,
    pos_z            DOUBLE          NOT NULL DEFAULT 0,
    owner_epoch      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '归属围栏：每次进场景自增，写回必须带上',
    owner_released   TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '1 = 当前 epoch 的写者已写回并释放（或从未进过场景）；0 = 有写者持有，新的夺权要等它释放或租约过期',
    owner_lease_until BIGINT         NOT NULL DEFAULT 0 COMMENT '当前写者的归属租约到期时刻（Unix 毫秒）；写者在线期间定期续约，过期后允许强制夺权',
    created_at       BIGINT          NOT NULL COMMENT 'Unix 毫秒',
    updated_at       BIGINT          NOT NULL COMMENT 'Unix 毫秒',
    PRIMARY KEY (player_id),
    UNIQUE KEY uk_player_name_key (name_key),
    KEY idx_player_account (account)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

-- 玩家在线状态（各玩法的数据），protobuf xm.storage.PlayerState。与 player 行在同一事务里、同一 owner_epoch 围栏下写入：
-- 先带围栏更新 player 行（行锁串行同一玩家的写者），成功才 upsert 本表。没有这一行 = 从未写过 = 各玩法都取初始状态。
CREATE TABLE IF NOT EXISTS player_state (
    player_id    BIGINT UNSIGNED NOT NULL,
    data         MEDIUMBLOB      NOT NULL COMMENT 'xm.storage.PlayerState 序列化字节',
    saved_epoch  BIGINT UNSIGNED NOT NULL COMMENT '写入这份数据的 owner_epoch（排障用；围栏判定在 player 行上）',
    updated_at   BIGINT          NOT NULL COMMENT 'Unix 毫秒',
    PRIMARY KEY (player_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;
