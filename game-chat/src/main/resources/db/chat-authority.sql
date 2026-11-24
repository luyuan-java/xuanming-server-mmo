-- 先安装此正式聊天DDL；player与route原件必须已由既有正规初始化/迁移建立，聊天服务不补建角色权威。
CREATE TABLE IF NOT EXISTS chat_send_authority (
    player_id BIGINT UNSIGNED NOT NULL,
    request_id VARBINARY(64) NOT NULL,
    owner_account VARBINARY(764) NOT NULL,
    owner_revision BIGINT UNSIGNED NOT NULL,
    request_digest BINARY(32) NOT NULL,
    request_payload MEDIUMBLOB NOT NULL,
    message_payload MEDIUMBLOB NOT NULL,
    stream_key VARBINARY(191) NOT NULL,
    state INT NOT NULL,
    accepted_ms BIGINT NOT NULL,
    result_tip INT NOT NULL,
    PRIMARY KEY(player_id, request_id),
    INDEX idx_chat_stream(stream_key, state, accepted_ms, player_id, request_id),
    INDEX idx_chat_rate(player_id, state, accepted_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS chat_policy (
    id INT NOT NULL PRIMARY KEY,
    original_contract MEDIUMBLOB NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS chat_world_retraction (
    sender_player_id BIGINT UNSIGNED NOT NULL,
    accepted_ms BIGINT NOT NULL,
    retracted_ms BIGINT NOT NULL,
    reason VARBINARY(128) NOT NULL,
    PRIMARY KEY(sender_player_id, accepted_ms),
    INDEX idx_chat_retract_ms(accepted_ms, sender_player_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS chat_report (
    reporter_player_id BIGINT UNSIGNED NOT NULL,
    channel INT NOT NULL,
    target_sender_player_id BIGINT UNSIGNED NOT NULL,
    send_time_ms BIGINT NOT NULL,
    reason INT NOT NULL,
    detail VARBINARY(255) NOT NULL,
    reported_ms BIGINT NOT NULL,
    PRIMARY KEY(reporter_player_id, channel, target_sender_player_id, send_time_ms),
    INDEX idx_chat_report_target(target_sender_player_id, send_time_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS chat_repeat_event (
    content_hash BINARY(32) NOT NULL,
    accepted_ms BIGINT NOT NULL,
    sender_player_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY(content_hash, accepted_ms, sender_player_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS chat_private_daily_target (
    sender_player_id BIGINT UNSIGNED NOT NULL,
    day_ymd INT NOT NULL,
    target_player_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY(sender_player_id, day_ymd, target_player_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
