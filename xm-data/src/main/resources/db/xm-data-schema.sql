-- xm-data 的表（库 xm_java，启动时 CREATE TABLE IF NOT EXISTS；结构变更按 docs/design/db-migrations.md 手工迁移）。
-- 流水 / 快照由 xm-data 写：scene 经 Kafka 生产、xm-data 消费落库；运维面（批次 7.2）直写自己的快照 / 流水行（号取自同一个全服租约池）。
-- 运维作业表（ops_job 等）不在这里，由 xm-pbmysql 按 xm/data/ops_tables.proto 建。

-- 资产流水（xm.audit.TransactionLogRecord）。主键 tx_id 让重放幂等（INSERT ... ON DUPLICATE KEY UPDATE 不改已有行）。
-- idx_txlog_item / idx_txlog_currency / idx_txlog_uuid：全服按物品 / 按币种查询与回收、物品追溯（批次 7.2a，存量库见 db-migrations.md M8）。
-- 不合成一条 (kind, item_config_id, currency_type, time_ms)：那要依赖「物品行币种恒 0、货币行配置号恒 0」这条生产方不变量，被打破时会静默漏行。
CREATE TABLE IF NOT EXISTS transaction_log (
    tx_id          BIGINT UNSIGNED NOT NULL COMMENT '全服唯一流水号（场景节点的雪花号）',
    time_ms        BIGINT          NOT NULL COMMENT '变动发生的 Unix 毫秒',
    reason         INT UNSIGNED    NOT NULL COMMENT 'xm.audit.TransactionReason',
    kind           INT UNSIGNED    NOT NULL COMMENT 'xm.audit.AssetKind',
    from_player    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '扣减方，0 = 系统',
    to_player      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '获得方，0 = 系统',
    currency_type  INT UNSIGNED    NOT NULL DEFAULT 0,
    currency_delta BIGINT          NOT NULL DEFAULT 0 COMMENT '加为正、扣为负',
    balance_before BIGINT UNSIGNED NOT NULL DEFAULT 0,
    balance_after  BIGINT UNSIGNED NOT NULL DEFAULT 0,
    item_uuid      BIGINT UNSIGNED NOT NULL DEFAULT 0,
    item_config_id INT UNSIGNED    NOT NULL DEFAULT 0,
    item_quantity  INT UNSIGNED    NOT NULL DEFAULT 0,
    correlation_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    extra          VARCHAR(1024)   NOT NULL DEFAULT '' COMMENT 'JSON',
    zone_id        INT UNSIGNED    NOT NULL,
    ingested_at    BIGINT          NOT NULL COMMENT 'xm-data 落库的 Unix 毫秒',
    PRIMARY KEY (tx_id),
    KEY idx_txlog_from (from_player, time_ms),
    KEY idx_txlog_to (to_player, time_ms),
    KEY idx_txlog_time (time_ms),
    KEY idx_txlog_item (kind, item_config_id, time_ms),
    KEY idx_txlog_currency (kind, currency_type, time_ms),
    KEY idx_txlog_uuid (item_uuid, time_ms)
);

-- 玩家快照（xm.audit.PlayerSnapshotRecord）：player 行可变列 + 原样的 player_state 字节，回档与客服排查的素材。
-- 主键 snapshot_id 让重放幂等；玩法数据原样存字节（scene 侧上限约 1MB，MEDIUMBLOB 足够）。
-- 来源靠 operator 区分：scene 经 Kafka 的为空，xm-data 直写的（手工 / 维护前 / 安全快照）是运维操作人。
-- time_ms 是内容所代表的时刻：scene 拍的（LOGIN / LOGOUT）= 拍摄时刻；xm-data 直写的 = 所读已落盘状态的写入时刻（player_state.updated_at），
-- 拍摄时刻记在 ingested_at；owner_epoch 是写入这份内容的 epoch。operator / note 两列随批次 7.2a 加（存量库见 db-migrations.md M8）。
CREATE TABLE IF NOT EXISTS player_snapshot (
    snapshot_id     BIGINT UNSIGNED NOT NULL COMMENT '全服唯一快照号（场景节点的雪花号）',
    player_id       BIGINT UNSIGNED NOT NULL,
    time_ms         BIGINT          NOT NULL COMMENT '采集时刻的 Unix 毫秒',
    cause           INT UNSIGNED    NOT NULL COMMENT 'xm.audit.SnapshotCause',
    zone_id         INT UNSIGNED    NOT NULL,
    owner_epoch     BIGINT UNSIGNED NOT NULL COMMENT '采集时持有的归属 epoch',
    level           INT UNSIGNED    NOT NULL,
    scene_config_id INT UNSIGNED    NOT NULL,
    pos_x           DOUBLE          NOT NULL,
    pos_y           DOUBLE          NOT NULL,
    pos_z           DOUBLE          NOT NULL,
    player_state    MEDIUMBLOB      NOT NULL COMMENT 'xm.storage.PlayerState 序列化字节（原样）',
    ingested_at     BIGINT          NOT NULL COMMENT 'xm-data 落库的 Unix 毫秒',
    operator        VARCHAR(64)     NOT NULL DEFAULT '' COMMENT 'xm-data 直写的操作人；scene 经 Kafka 的为空',
    note            VARCHAR(256)    NOT NULL DEFAULT '' COMMENT '直写的备注（原因 / 事件 / 作业号）',
    PRIMARY KEY (snapshot_id),
    KEY idx_snapshot_player (player_id, time_ms),
    KEY idx_snapshot_time (time_ms)
);
