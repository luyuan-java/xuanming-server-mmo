-- M16：这里只建结构，不自动播种可写原件。显式RouteAuthorityInitializer从真实player行和配置zone初始化，生产缺meta/缺片拒绝。
CREATE TABLE IF NOT EXISTS route_meta (
    id INT UNSIGNED NOT NULL,
    generation VARCHAR(36) NOT NULL,
    writable INT UNSIGNED NOT NULL,
    fence_shards INT UNSIGNED NOT NULL,
    writer_epoch BIGINT UNSIGNED NOT NULL,
    catalog_revision BIGINT UNSIGNED NOT NULL,
    freeze_run_id VARBINARY(16) NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

-- 仅回答源区是否退役，不是玩家home副本；正常服务不得创建、删除或自动补marker。
CREATE TABLE IF NOT EXISTS zone_merged_into (
    zone_id INT UNSIGNED NOT NULL,
    target_zone INT UNSIGNED NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    revision BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (zone_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;
CREATE TABLE IF NOT EXISTS zone_merge_fence (
    zone_id INT UNSIGNED NOT NULL,
    shard INT UNSIGNED NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    revision BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (zone_id,shard)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

-- 以下六表仅由独立schema主体在fresh-prestart创建；normal绝不注册、补片、退休或迁移。
CREATE TABLE writer_registry (
    writer_key BINARY(16) NOT NULL,
    generation VARCHAR(36) NOT NULL,
    instance_id BINARY(16) NOT NULL,
    sql_principal VARBINARY(288) NOT NULL,
    registration_digest BINARY(32) NOT NULL,
    state TINYINT UNSIGNED NOT NULL,
    registered_ms BIGINT UNSIGNED NOT NULL,
    retirement_request_id VARBINARY(16) NOT NULL,
    retirement_digest VARBINARY(32) NOT NULL,
    retired_ms BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY(writer_key), UNIQUE KEY uq_writer_principal(sql_principal), UNIQUE KEY uq_writer_instance(instance_id),
    CHECK(state IN(1,2)), CHECK(registered_ms>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE writer_slot (
    writer_key BINARY(16) NOT NULL,
    slot TINYINT UNSIGNED NOT NULL,
    generation VARCHAR(36) NOT NULL,
    writer_epoch BIGINT UNSIGNED NOT NULL,
    state TINYINT UNSIGNED NOT NULL,
    lock_touch TINYINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY(writer_key,slot), CHECK(slot<64), CHECK(writer_epoch>0), CHECK(state IN(1,2)), CHECK(lock_touch IN(0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE writer_lifecycle_receipt (
    request_id BINARY(16) NOT NULL,
    kind TINYINT UNSIGNED NOT NULL,
    generation VARCHAR(36) NOT NULL,
    request_digest BINARY(32) NOT NULL,
    request_proto MEDIUMBLOB NOT NULL,
    previous_epoch BIGINT UNSIGNED NOT NULL,
    resulting_epoch BIGINT UNSIGNED NOT NULL,
    catalog_revision BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY(request_id), KEY idx_kind_request_digest(kind,request_digest), KEY idx_catalog_revision(catalog_revision,request_id), CHECK(kind IN(1,2))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE sql_writer_drain_run (
    operation_id BINARY(16) NOT NULL,
    request_digest BINARY(32) NOT NULL,
    generation VARCHAR(36) NOT NULL,
    attempt_revision BIGINT UNSIGNED NOT NULL,
    manifest_proto MEDIUMBLOB NOT NULL,
    manifest_digest BINARY(32) NOT NULL,
    phase TINYINT UNSIGNED NOT NULL,
    latest_receipt_digest VARBINARY(32) NOT NULL,
    frozen_request_id VARBINARY(16) NOT NULL,
    updated_ms BIGINT UNSIGNED NOT NULL,
    latest_receipt_id VARBINARY(16) NOT NULL,
    PRIMARY KEY(operation_id), CHECK(attempt_revision>0), CHECK(phase IN(1,3,4,5,6,7,8,9,10,11,12))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE sql_writer_freeze_ticket (
    ticket_id BINARY(16) NOT NULL,
    operation_id BINARY(16) NOT NULL,
    attempt_revision BIGINT UNSIGNED NOT NULL,
    generation VARCHAR(36) NOT NULL,
    manifest_digest BINARY(32) NOT NULL,
    non_sql_receipt_digest VARBINARY(32) NOT NULL,
    freeze_request_id BINARY(16) NOT NULL,
    freeze_request_digest BINARY(32) NOT NULL,
    not_before_ms BIGINT UNSIGNED NOT NULL,
    expires_ms BIGINT UNSIGNED NOT NULL,
    state TINYINT UNSIGNED NOT NULL,
    consumed_receipt_digest VARBINARY(32) NOT NULL,
    revocation_digest VARBINARY(32) NOT NULL,
    updated_ms BIGINT UNSIGNED NOT NULL,
    authorization_kind TINYINT UNSIGNED NOT NULL,
    grant_receipt_id BINARY(16) NOT NULL,
    grant_receipt_digest BINARY(32) NOT NULL,
    PRIMARY KEY(ticket_id), UNIQUE KEY uq_operation_attempt(operation_id,attempt_revision),
    UNIQUE KEY uq_freeze_request(freeze_request_id), CHECK(state IN(1,2,3)), CHECK(authorization_kind IN(1,2))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE sql_writer_maintenance_receipt (
    request_id BINARY(16) NOT NULL,
    request_digest BINARY(32) NOT NULL,
    run_id BINARY(16) NOT NULL,
    generation VARCHAR(36) NOT NULL,
    before_epoch BIGINT UNSIGNED NOT NULL,
    after_epoch BIGINT UNSIGNED NOT NULL,
    catalog_revision BIGINT UNSIGNED NOT NULL,
    phase TINYINT UNSIGNED NOT NULL,
    plan_proto MEDIUMBLOB NOT NULL,
    committed_ms BIGINT UNSIGNED NOT NULL,
    kind TINYINT UNSIGNED NOT NULL,
    run_phase TINYINT UNSIGNED NOT NULL,
    attempt_revision BIGINT UNSIGNED NOT NULL,
    prior_receipt_id VARBINARY(16) NOT NULL,
    prior_receipt_digest VARBINARY(32) NOT NULL,
    evidence MEDIUMBLOB NOT NULL,
    receipt_digest BINARY(32) NOT NULL,
    PRIMARY KEY(request_id), KEY idx_run_attempt(run_id,attempt_revision), CHECK(kind BETWEEN 1 AND 8)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
