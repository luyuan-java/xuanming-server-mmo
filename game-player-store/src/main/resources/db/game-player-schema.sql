-- Java 版账号 / 玩家表（库 game，与 mmorpg 的库隔离）。启动时由 Spring SQL init 执行，必须幂等。
-- CREATE TABLE IF NOT EXISTS 不会改动已存在的表：结构变更对存量库要手工迁移，步骤见 docs/design/db-migrations.md。

CREATE TABLE IF NOT EXISTS account (
    account     VARCHAR(64)     NOT NULL,
    created_at  BIGINT          NOT NULL COMMENT 'Unix 毫秒',
    password_hash VARCHAR(255)  NULL COMMENT 'Argon2id PHC 串；NULL = 该账号不能口令登录（生产口令认证只读，登录不写）',
    PRIMARY KEY (account)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

-- idx_player_zone：整区回档 / 维护前快照按归属区列玩家（game-data，批次 7.2b；存量库见 db-migrations.md M9）。
CREATE TABLE IF NOT EXISTS player (
    player_id        BIGINT UNSIGNED NOT NULL,
    account          VARCHAR(64)     NOT NULL,
    owner_revision   BIGINT NOT NULL DEFAULT 1 COMMENT '账号归属修订，独立于scene存盘围栏',
    trade_listing_id BIGINT NOT NULL DEFAULT 0 COMMENT 'M15角色寄售锁；0表示未冻结，不设TTL',
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
    KEY idx_player_account (account),
    KEY idx_player_zone (zone_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

-- M15：同listing解冻和过户终态永久保存，旧请求重放不能重新冻结。
CREATE TABLE IF NOT EXISTS player_trade_freeze (
    listing_id BIGINT NOT NULL,
    player_id BIGINT UNSIGNED NOT NULL,
    seller_account VARBINARY(256) NOT NULL,
    owner_revision BIGINT NOT NULL,
    freeze_epoch BIGINT UNSIGNED NOT NULL,
    request_digest BINARY(32) NOT NULL,
    creation_token VARBINARY(36) NOT NULL,
    status VARCHAR(16) NOT NULL,
    cancel_requested TINYINT NOT NULL DEFAULT 0,
    created_ms BIGINT NOT NULL,
    updated_ms BIGINT NOT NULL,
    PRIMARY KEY (listing_id),
    KEY idx_trade_freeze_due (status, listing_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

-- 只由同库验真与原scene逻辑排空回执建立；封闭旧E的准入在解锁后也不能撤销。
CREATE TABLE IF NOT EXISTS player_trade_drain (
    listing_id BIGINT NOT NULL,
    player_id BIGINT UNSIGNED NOT NULL,
    owner_epoch BIGINT UNSIGNED NOT NULL,
    proof_mode VARCHAR(32) NOT NULL,
    source_instance VARBINARY(64) NOT NULL,
    payload_digest BINARY(32) NULL,
    created_ms BIGINT NOT NULL,
    PRIMARY KEY (listing_id),
    KEY idx_trade_drain_epoch (player_id, owner_epoch)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

CREATE TABLE IF NOT EXISTS player_trade_transfer (
    tx_id BIGINT NOT NULL,
    listing_id BIGINT NOT NULL,
    player_id BIGINT UNSIGNED NOT NULL,
    seller_account VARBINARY(256) NOT NULL,
    buyer_account VARBINARY(256) NOT NULL,
    previous_revision BIGINT NOT NULL,
    new_revision BIGINT NOT NULL,
    request_digest BINARY(32) NOT NULL,
    creation_token VARBINARY(36) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_ms BIGINT NOT NULL,
    PRIMARY KEY (tx_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

-- 玩家在线状态（各玩法的数据），protobuf game.storage.PlayerState。与 player 行在同一事务里、同一 owner_epoch 围栏下写入：
-- 先带围栏更新 player 行（行锁串行同一玩家的写者），成功才 upsert 本表。没有这一行 = 从未写过 = 各玩法都取初始状态。
CREATE TABLE IF NOT EXISTS player_state (
    player_id    BIGINT UNSIGNED NOT NULL,
    data         MEDIUMBLOB      NOT NULL COMMENT 'game.storage.PlayerState 序列化字节',
    saved_epoch  BIGINT UNSIGNED NOT NULL COMMENT '写入这份数据的 owner_epoch（排障用；围栏判定在 player 行上）',
    updated_at   BIGINT          NOT NULL COMMENT 'Unix 毫秒',
    PRIMARY KEY (player_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

-- M14 草案：最终 SAVE 的 SQL 持久接管。只通过本正式 schema 入口建表；不是审计日志。
-- TX1 在锁住 player 后写入完整冻结快照并提交，TX2 才写 player/player_state 并置 APPLIED。
-- PENDING 永不按次数或 TTL 删除。启用前必须排空所有不认识该门禁的旧 Java writer/claim 方。
CREATE TABLE IF NOT EXISTS player_final_save (
    player_id          BIGINT UNSIGNED NOT NULL,
    owner_epoch        BIGINT UNSIGNED NOT NULL,
    save_seq           BIGINT UNSIGNED NOT NULL COMMENT '第一版每个 epoch 只登记一次冻结终态，固定为 1',
    protocol_version   INT UNSIGNED NOT NULL,
    source_instance    VARCHAR(64) NOT NULL COMMENT '原 scene 实例，只作原始来源；补偿不改绑新实例或 epoch',
    status             VARCHAR(16) NOT NULL COMMENT 'OPEN仅准入，不阻TTL；PENDING或APPLIED持久终态',
    level              INT UNSIGNED NULL,
    scene_config_id    INT UNSIGNED NULL,
    pos_x              DOUBLE NULL,
    pos_y              DOUBLE NULL,
    pos_z              DOUBLE NULL,
    data               MEDIUMBLOB NULL COMMENT '冻结 PlayerState 的原序列化字节，补偿直接原样写入',
    payload_digest     BINARY(32) NULL COMMENT '协议域、player/epoch/seq、标量与原 data 的 SHA-256',
    created_at         BIGINT NOT NULL,
    next_attempt_at    BIGINT NOT NULL,
    attempt_count      BIGINT UNSIGNED NOT NULL DEFAULT 0,
    last_error_code    VARCHAR(32) NOT NULL DEFAULT '',
    applied_at         BIGINT NULL,
    PRIMARY KEY (player_id, owner_epoch),
    KEY idx_final_save_due (status, next_attempt_at, player_id, owner_epoch),
    KEY idx_final_save_retired (status, created_at, player_id, owner_epoch)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;
