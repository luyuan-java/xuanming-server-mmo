-- xm-data 的表（库 xm_java，启动时 CREATE TABLE IF NOT EXISTS；结构变更按 docs/design/db-migrations.md 手工迁移）。
-- 只由 xm-data 读写；scene 经 Kafka 生产，不直接写这里。

-- 资产流水（xm.audit.TransactionLogRecord）。主键 tx_id 让重放幂等（INSERT ... ON DUPLICATE KEY UPDATE 不改已有行）。
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
    KEY idx_txlog_time (time_ms)
);
