-- M16：这里只建结构，不自动播种可写原件。显式RouteAuthorityInitializer从真实player行和配置zone初始化，生产缺meta/缺片拒绝。
CREATE TABLE IF NOT EXISTS route_meta (
    id INT UNSIGNED NOT NULL,
    generation VARCHAR(36) NOT NULL,
    writable INT UNSIGNED NOT NULL,
    fence_shards INT UNSIGNED NOT NULL,
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

