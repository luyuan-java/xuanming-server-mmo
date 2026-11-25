-- Java 版账号 / 玩家表（库 game，与 mmorpg 的库隔离）。启动时由 Spring SQL init 执行，必须幂等。
-- CREATE TABLE IF NOT EXISTS 不会改动已存在的表：结构变更对存量库要手工迁移，步骤见 docs/design/db-migrations.md。

-- M16路由结构的唯一DDL源为classpath:db/game-route-authority-schema.sql；必须在本脚本前执行。

CREATE TABLE IF NOT EXISTS account (
    account                    VARCHAR(64)       NOT NULL,
    created_at                 BIGINT            NOT NULL COMMENT 'Unix 毫秒',
    password_hash              VARCHAR(255)      NULL COMMENT 'Argon2id PHC 串；NULL = 该账号不能口令登录（生产口令认证只读，登录不写）',
    real_name_pi               VARCHAR(64)       NOT NULL DEFAULT '' COMMENT 'NPPA 38 位实名标识；空串 = 未实名',
    deletion_state             TINYINT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '0=正常 1=冷静期 2=执行中',
    deletion_requested_ms      BIGINT            NOT NULL DEFAULT 0 COMMENT '注销申请时刻（Unix 毫秒）',
    deletion_execute_after_ms  BIGINT            NOT NULL DEFAULT 0 COMMENT '冷静期满可执行时刻（Unix 毫秒）',
    deletion_lease_until_ms    BIGINT            NOT NULL DEFAULT 0 COMMENT '执行者认领租约截止时刻（Unix 毫秒）',
    deletion_source            TINYINT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '1=本人申请 2=运维代办',
    stage1_tradable_material_used INT UNSIGNED   NOT NULL DEFAULT 0 COMMENT '阶段一装备材料自选跨角色终身可交易已用总额（0..60，删角不重置）',
    PRIMARY KEY (account),
    KEY idx_account_deletion_due (deletion_state, deletion_execute_after_ms, deletion_lease_until_ms)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

-- 账号级阶段一可交易装备材料按角色高水位确认表（防建小号/删角循环刷 60 份可交易料）。
CREATE TABLE IF NOT EXISTS account_role_tradable_quota (
    account          VARCHAR(64)       NOT NULL,
    player_id        BIGINT UNSIGNED   NOT NULL,
    confirmed_units  INT UNSIGNED      NOT NULL DEFAULT 0,
    updated_at       BIGINT            NOT NULL COMMENT 'Unix 毫秒',
    PRIMARY KEY (account, player_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

-- 账号注销完成回执：只存账号 SHA-256 摘要，不存原文，供同身份重注册展示上次注销完成时刻。
CREATE TABLE IF NOT EXISTS account_deletion_receipt (
    account_digest  VARBINARY(32)     NOT NULL COMMENT 'SHA-256(account)',
    requested_ms    BIGINT            NOT NULL COMMENT '申请注销时刻（Unix 毫秒）',
    deleted_ms      BIGINT            NOT NULL COMMENT '完成注销时刻（Unix 毫秒）',
    source          TINYINT UNSIGNED  NOT NULL COMMENT '1=本人申请 2=运维代办',
    PRIMARY KEY (account_digest)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

-- 账号渠道身份档案：登录解析后落库，供内容安全外部平台复审与聊天检索 (channel, open_id)。
CREATE TABLE IF NOT EXISTS account_channel_identity (
    account     VARCHAR(64)   NOT NULL,
    channel     VARCHAR(32)   NOT NULL DEFAULT '',
    open_id     VARCHAR(128)  NOT NULL DEFAULT '',
    updated_at  BIGINT        NOT NULL COMMENT 'Unix 毫秒',
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
    renamed_ms       BIGINT          NOT NULL DEFAULT 0 COMMENT '上次角色改名成功时刻（Unix 毫秒，用于冷却校验）',
    afk_pass_expires_at BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '挂机月卡到期时刻（Unix 秒；0 = 未开通/已清除）',
    afk_pass_last_req_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '最近一次挂机月卡开通幂等请求号',
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
-- 创建角色同P事务严格插入非NULL初始protobuf；读取缺行或NULL拒绝，合法空bytes仍为初始状态，禁止读时补行。
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
    admission_proto    MEDIUMBLOB NULL COMMENT '首次同P准入的完整RoleAdmissionIdentity原件；旧空记录不能补当前身份',
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


-- M19：旧OPEN维护退休的永久SQL原件，不是APPLIED证明，不按TTL清理。
CREATE TABLE IF NOT EXISTS player_trade_admission_recovery (
    recovery_id VARBINARY(36) NOT NULL,
    listing_id BIGINT NOT NULL,
    player_id BIGINT UNSIGNED NOT NULL,
    old_epoch BIGINT UNSIGNED NOT NULL,
    proof_mode VARCHAR(32) NOT NULL,
    request_digest BINARY(32) NOT NULL,
    plan_bytes MEDIUMBLOB NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_ms BIGINT NOT NULL,
    PRIMARY KEY (recovery_id),
    UNIQUE KEY uk_trade_admission_recovery_epoch (player_id, old_epoch)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

-- 角色改名旧名保护表：改名后旧名在保护期内仅原主可改回，他人建角或改名均视为已占用。
CREATE TABLE IF NOT EXISTS player_name_hold (
    name_key        VARCHAR(64)     NOT NULL COMMENT '被保护旧名的唯一键（PlayerStore.nameKey）',
    owner_player_id BIGINT UNSIGNED NOT NULL COMMENT '原主角色号',
    held_until_ms   BIGINT          NOT NULL COMMENT '保护截止时刻（Unix 毫秒）',
    updated_at      BIGINT          NOT NULL COMMENT 'Unix 毫秒',
    PRIMARY KEY (name_key),
    KEY idx_player_name_hold_until (held_until_ms)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;
