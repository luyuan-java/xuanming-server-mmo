package com.game.player.store.audit;

import com.game.common.workshard.WorkShard;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 审计发件箱 JDBC 访问层：支持与玩家存档在同一数据库事务连接中批量写入（同生共死，回滚/围栏拒绝零残留），
 * 以及按 64 片分片条件（置于 LIMIT 之前）轮询、按主键删除与积压最老年龄观测。
 */
public final class PlayerAuditOutboxStore {

    public static final String SHARD_KIND = "audit_outbox";

    private static final String INSERT_SQL =
            "INSERT INTO player_audit_outbox (shard, player_id, kind, payload, created_ms) VALUES (?, ?, ?, ?, ?)";

    private static final String OLDEST_CREATED_SQL =
            "SELECT MIN(created_ms) FROM player_audit_outbox WHERE ";

    private PlayerAuditOutboxStore() {
    }

    /** 在当前存档事务的同一个 {@link Connection} 上整批写入审计发件箱（不分笔，总大小不超过 8 MiB）。 */
    public static int insertInCurrentTx(Connection conn, List<AuditOutboxEntry> entries) throws SQLException {
        Objects.requireNonNull(conn, "conn");
        if (entries == null || entries.isEmpty()) {
            return 0;
        }
        long totalBytes = 0L;
        for (AuditOutboxEntry e : entries) {
            totalBytes += e.payload().length;
            if (totalBytes > AuditOutboxEntry.MAX_BATCH_BYTES) {
                throw new IllegalArgumentException("audit outbox batch exceeds 8MiB limit: " + totalBytes);
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
            for (AuditOutboxEntry e : entries) {
                ps.setInt(1, e.shard());
                ps.setLong(2, e.playerId());
                ps.setInt(3, e.kind());
                ps.setBytes(4, e.payload());
                ps.setLong(5, e.createdMs());
                ps.addBatch();
            }
            int[] counts = ps.executeBatch();
            return counts.length;
        }
    }

    /** 按分片条件（置于 LIMIT 之前）拉取一批待转发的审计发件箱记录。 */
    public static List<AuditOutboxEntry> pollDue(Connection conn, WorkShard.SqlFilter filter, int limit)
            throws SQLException {
        Objects.requireNonNull(conn, "conn");
        Objects.requireNonNull(filter, "filter");
        if (limit <= 0 || filter.isNone()) {
            return List.of();
        }
        String sql = "SELECT outbox_id, shard, player_id, kind, payload, created_ms FROM player_audit_outbox WHERE "
                + filter.sql() + " ORDER BY outbox_id ASC LIMIT ?";
        List<AuditOutboxEntry> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            for (Object arg : filter.args()) {
                ps.setObject(idx++, arg);
            }
            ps.setInt(idx, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong(1);
                    int shard = rs.getInt(2);
                    long playerId = rs.getLong(3);
                    int kind = rs.getInt(4);
                    byte[] payload = rs.getBytes(5);
                    long createdMs = rs.getLong(6);
                    if (payload == null || payload.length == 0 || payload.length > AuditOutboxEntry.MAX_SINGLE_ENTRY_BYTES
                            || (kind != AuditOutboxEntry.KIND_TRANSACTION && kind != AuditOutboxEntry.KIND_SNAPSHOT)
                            || playerId == 0L) {
                        out.add(new AuditOutboxEntry(id, shard, playerId == 0L ? 1L : playerId,
                                AuditOutboxEntry.KIND_TRANSACTION, new byte[] { 0 }, createdMs));
                    } else {
                        out.add(new AuditOutboxEntry(id, shard, playerId, kind, payload, createdMs));
                    }
                }
            }
        }
        return out;
    }

    /** Kafka acks=all 确认后按主键删除已转发记录。 */
    public static int deleteByIds(Connection conn, List<Long> outboxIds) throws SQLException {
        Objects.requireNonNull(conn, "conn");
        if (outboxIds == null || outboxIds.isEmpty()) {
            return 0;
        }
        StringBuilder sb = new StringBuilder("DELETE FROM player_audit_outbox WHERE outbox_id IN (");
        for (int i = 0; i < outboxIds.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('?');
        }
        sb.append(')');
        try (PreparedStatement ps = conn.prepareStatement(sb.toString())) {
            for (int i = 0; i < outboxIds.size(); i++) {
                ps.setLong(i + 1, outboxIds.get(i));
            }
            return ps.executeUpdate();
        }
    }

    /** 查询本副本负责分片中最老待转发记录的入箱时刻（无积压返回 0）。 */
    public static long oldestCreatedMs(Connection conn, WorkShard.SqlFilter filter) throws SQLException {
        Objects.requireNonNull(conn, "conn");
        Objects.requireNonNull(filter, "filter");
        if (filter.isNone()) {
            return 0L;
        }
        try (PreparedStatement ps = conn.prepareStatement(OLDEST_CREATED_SQL + filter.sql())) {
            int idx = 1;
            for (Object arg : filter.args()) {
                ps.setObject(idx++, arg);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long v = rs.getLong(1);
                    return rs.wasNull() ? 0L : v;
                }
                return 0L;
            }
        }
    }
}
