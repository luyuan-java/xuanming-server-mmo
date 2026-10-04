-- xm-gateway 的库表（库 xm_java）：区服目录、区服白名单、登录公告。由 xm-gateway 启动时执行（只 CREATE TABLE IF NOT EXISTS），
-- xm-data 的运维接口写。时刻一律存整数：客户端看到的开放 / 公告时刻是 Unix 秒，创建 / 更新时刻是 Unix 毫秒
-- （不用 DATETIME：基线 JVM 本地时间与 UTC 混用会差整数小时）。存量库的结构变更登记在 docs/design/db-migrations.md。

CREATE TABLE IF NOT EXISTS zone_config (
    zone_id         INT UNSIGNED     NOT NULL,
    name            VARCHAR(64)      NOT NULL,
    manual_status   TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '运维手工状态：0 OPEN / 1 MAINTENANCE / 2 CLOSED / 3 PREVIEW',
    capacity        INT UNSIGNED     NOT NULL DEFAULT 5000 COMMENT '负载档的分母（全区 gate 在线人数 / capacity）',
    maintenance_msg VARCHAR(256)     NOT NULL DEFAULT '' COMMENT '维护 / 关闭时随区服列表下发的文案',
    open_time       BIGINT           NULL COMMENT 'PREVIEW 区的开放时刻（Unix 秒）',
    recommended     TINYINT(1)       NOT NULL DEFAULT 0,
    sort_order      INT              NOT NULL DEFAULT 0 COMMENT '区服列表按它升序',
    created_at      BIGINT           NOT NULL COMMENT 'Unix 毫秒；7 天内为新区（is_new）',
    updated_at      BIGINT           NOT NULL COMMENT 'Unix 毫秒',
    PRIMARY KEY (zone_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

CREATE TABLE IF NOT EXISTS zone_whitelist (
    zone_id INT UNSIGNED NOT NULL,
    account VARCHAR(64)  NOT NULL COMMENT '账号（与 account.account 同一口径）',
    note    VARCHAR(128) NOT NULL DEFAULT '',
    PRIMARY KEY (zone_id, account)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

CREATE TABLE IF NOT EXISTS announcement (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    title      VARCHAR(128) NOT NULL,
    content    TEXT         NULL,
    type       VARCHAR(32)  NOT NULL DEFAULT 'notice' COMMENT 'notice / maintenance / update',
    start_time BIGINT       NULL COMMENT '生效起点（Unix 秒）；NULL = 立即',
    end_time   BIGINT       NULL COMMENT '生效终点（Unix 秒，含）；NULL = 不过期',
    created_at BIGINT       NOT NULL COMMENT 'Unix 毫秒；客户端按它倒序展示',
    PRIMARY KEY (id),
    KEY idx_announcement_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;
