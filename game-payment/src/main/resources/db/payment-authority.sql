-- 支付服务权威库表结构（对应 proto/payment/payment_table.proto）
-- 全部主键由自然键确定性派生，不依赖外部号段；未成年人限额与意图状态在同一事务行锁下收敛。

CREATE TABLE IF NOT EXISTS payment_intent (
    order_hi BIGINT NOT NULL,
    order_lo BIGINT NOT NULL,
    order_id VARCHAR(64) NOT NULL DEFAULT '',
    player_id BIGINT NOT NULL DEFAULT 0,
    channel INT NOT NULL DEFAULT 0,
    amount_minor BIGINT NOT NULL DEFAULT 0,
    currency VARCHAR(16) NOT NULL DEFAULT '',
    state INT NOT NULL DEFAULT 0,
    expire_ms BIGINT NOT NULL DEFAULT 0,
    next_check_ms BIGINT NOT NULL DEFAULT 0,
    check_count INT NOT NULL DEFAULT 0,
    checked_ms BIGINT NOT NULL DEFAULT 0,
    created_ms BIGINT NOT NULL DEFAULT 0,
    updated_ms BIGINT NOT NULL DEFAULT 0,
    channel_ref VARCHAR(512) NOT NULL DEFAULT '',
    channel_ref_ms BIGINT NOT NULL DEFAULT 0,
    sku_id BIGINT NOT NULL DEFAULT 0,
    payer_id VARCHAR(128) NOT NULL DEFAULT '',
    spender_hi BIGINT NOT NULL DEFAULT 0,
    spender_lo BIGINT NOT NULL DEFAULT 0,
    paid_ms BIGINT NOT NULL DEFAULT 0,
    minor_age INT NOT NULL DEFAULT 0,
    PRIMARY KEY (order_hi, order_lo),
    INDEX idx_payment_intent_due (state, next_check_ms),
    INDEX idx_payment_intent_spender (spender_hi, spender_lo, state, paid_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS payment_spend (
    spender_hi BIGINT NOT NULL,
    spender_lo BIGINT NOT NULL,
    updated_ms BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (spender_hi, spender_lo)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS payment_txn (
    channel INT NOT NULL,
    txn_hi BIGINT NOT NULL,
    txn_lo BIGINT NOT NULL,
    transaction_id VARCHAR(191) NOT NULL DEFAULT '',
    order_hi BIGINT NOT NULL DEFAULT 0,
    order_lo BIGINT NOT NULL DEFAULT 0,
    out_trade_no VARCHAR(64) NOT NULL DEFAULT '',
    player_id BIGINT NOT NULL DEFAULT 0,
    paid_amount_minor BIGINT NOT NULL DEFAULT 0,
    paid_currency VARCHAR(16) NOT NULL DEFAULT '',
    paid_ms BIGINT NOT NULL DEFAULT 0,
    mall_state INT NOT NULL DEFAULT 0,
    mall_refund_reason VARCHAR(64) NOT NULL DEFAULT '',
    mall_attempts INT NOT NULL DEFAULT 0,
    next_attempt_ms BIGINT NOT NULL DEFAULT 0,
    mall_last_error VARCHAR(255) NOT NULL DEFAULT '',
    refund_state INT NOT NULL DEFAULT 0,
    out_refund_no VARCHAR(64) NOT NULL DEFAULT '',
    refund_id VARCHAR(191) NOT NULL DEFAULT '',
    refund_ms BIGINT NOT NULL DEFAULT 0,
    refund_attempts INT NOT NULL DEFAULT 0,
    refund_next_ms BIGINT NOT NULL DEFAULT 0,
    refund_last_error VARCHAR(255) NOT NULL DEFAULT '',
    created_ms BIGINT NOT NULL DEFAULT 0,
    updated_ms BIGINT NOT NULL DEFAULT 0,
    fact_revision INT NOT NULL DEFAULT 0,
    fact_observed_ms BIGINT NOT NULL DEFAULT 0,
    mall_revision INT NOT NULL DEFAULT 0,
    mall_due INT NOT NULL DEFAULT 0,
    channel_token VARCHAR(1024) NOT NULL DEFAULT '',
    channel_order_id VARCHAR(191) NOT NULL DEFAULT '',
    store_amount_minor BIGINT NOT NULL DEFAULT 0,
    store_currency VARCHAR(16) NOT NULL DEFAULT '',
    finalize_state INT NOT NULL DEFAULT 0,
    finalize_next_ms BIGINT NOT NULL DEFAULT 0,
    product_id VARCHAR(191) NOT NULL DEFAULT '',
    PRIMARY KEY (channel, txn_hi, txn_lo),
    INDEX idx_payment_txn_mall_due (mall_due, next_attempt_ms),
    INDEX idx_payment_txn_finalize (finalize_state, finalize_next_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS payment_channel_cursor (
    channel INT NOT NULL,
    cursor_ms BIGINT NOT NULL DEFAULT 0,
    lease_until_ms BIGINT NOT NULL DEFAULT 0,
    lease_owner BIGINT NOT NULL DEFAULT 0,
    updated_ms BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (channel)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
