-- docs/design/db-migrations.md M8 的迁移语句（与文档逐字相同；XmDataMysqlIntegrationTest 用它把 M8 之前的库迁到新结构再与新建库比对）。
ALTER TABLE transaction_log
    ADD KEY idx_txlog_item (kind, item_config_id, time_ms),
    ADD KEY idx_txlog_currency (kind, currency_type, time_ms),
    ADD KEY idx_txlog_uuid (item_uuid, time_ms);
ALTER TABLE player_snapshot
    ADD COLUMN operator VARCHAR(64)  NOT NULL DEFAULT '' COMMENT 'xm-data 直写的操作人；scene 经 Kafka 的为空',
    ADD COLUMN note     VARCHAR(256) NOT NULL DEFAULT '' COMMENT '直写的备注（原因 / 事件 / 作业号）';
