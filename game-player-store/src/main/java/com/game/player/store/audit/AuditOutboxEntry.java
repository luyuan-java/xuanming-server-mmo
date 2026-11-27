package com.game.player.store.audit;

import java.util.Objects;

/**
 * 审计发件箱单条记录（R13，PARITY 2026-11-12）：货币/物品流水（{@link #KIND_TRANSACTION}）或玩家快照
 * （{@link #KIND_SNAPSHOT}）随存档在同一数据库事务中写入 {@code player_audit_outbox}。
 */
public record AuditOutboxEntry(
        long outboxId,
        int shard,
        long playerId,
        int kind,
        byte[] payload,
        long createdMs) {

    public static final int KIND_TRANSACTION = 1;
    public static final int KIND_SNAPSHOT = 2;

    /** 单条审计载荷上限（2 MiB）。 */
    public static final int MAX_SINGLE_ENTRY_BYTES = 2 * 1024 * 1024;
    /** 单个玩家一次存档随附的审计记录上限（8 MiB；与 scene 侧每人排队上限一致，一笔带完不分笔）。 */
    public static final int MAX_BATCH_BYTES = 8 * 1024 * 1024;
    /** 含玩家状态行与全部随附审计记录的单次存档请求总上限（16 MiB）。 */
    public static final int MAX_SAVE_REQUEST_BYTES = 16 * 1024 * 1024;

    public AuditOutboxEntry {
        if (playerId == 0L) {
            throw new IllegalArgumentException("audit outbox entry requires a non-zero single player_id");
        }
        if (kind != KIND_TRANSACTION && kind != KIND_SNAPSHOT) {
            throw new IllegalArgumentException("invalid audit outbox kind: " + kind);
        }
        Objects.requireNonNull(payload, "payload");
        if (payload.length == 0 || payload.length > MAX_SINGLE_ENTRY_BYTES) {
            throw new IllegalArgumentException("audit outbox payload size out of bounds: " + payload.length);
        }
        payload = payload.clone();
    }

    public static AuditOutboxEntry newEntry(long playerId, int kind, byte[] payload, long createdMs) {
        int shard = shardOf(playerId);
        return new AuditOutboxEntry(0L, shard, playerId, kind, payload, createdMs);
    }

    public static int shardOf(long playerId) {
        return (int) Long.remainderUnsigned(playerId, 64L);
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
