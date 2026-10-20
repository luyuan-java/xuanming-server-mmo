package com.game.data.tools.playerbundle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.audit.proto.AssetKind;
import com.game.audit.proto.SnapshotCause;
import com.game.audit.proto.TransactionReason;
import com.game.data.ops.pb.OpsJobKind;
import com.game.data.ops.pb.OpsJobStatus;
import com.game.data.snapshot.StateJson;
import com.google.protobuf.ProtocolMessageEnum;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * 导出包的库部分：只发固定的 SELECT，全部在<b>同一个可重复读事务</b>里（一个一致性快照：行、玩法数据与流水计数出自同一时刻），
 * 结束回滚。{@code account.password_hash} 不出库——SQL 里只取「是否为 NULL」。
 *
 * <p>各部分独立容错：某张表读不到（例如运维表还没建）只在 {@code errors} 里记一条，不影响其余部分；玩家行本身读失败则整体失败。
 */
final class BundleMysqlReader {

    private final Connection connection;
    private final ObjectMapper json;

    BundleMysqlReader(Connection connection, ObjectMapper json) {
        this.connection = connection;
        this.json = json;
    }

    /**
     * 读出该玩家的库部分。调用方负责事务边界（{@link #beginSnapshot} / {@link #endSnapshot}）。
     *
     * @param limit 快照 / 流水 / 运维作业 / 回收记录各取最近多少条
     */
    PlayerBundle.Mysql read(long playerId, int limit) throws SQLException {
        Map<String, String> errors = new LinkedHashMap<>();
        PlayerBundle.Player player = readPlayer(playerId);
        PlayerBundle.Account account = null;
        if (player != null) {
            try {
                account = readAccount(player.account());
            } catch (SQLException e) {
                errors.put("account", describe(e));
            }
        }
        PlayerBundle.State state;
        try {
            state = readState(playerId);
        } catch (SQLException e) {
            errors.put("playerState", describe(e));
            state = null;
        }
        List<Map<String, Object>> snapshots = part(errors, "snapshots", () -> readSnapshots(playerId, limit));
        List<Map<String, Object>> txlog = part(errors, "transactionLog", () -> readTransactionLog(playerId, limit));
        PlayerBundle.TxlogCounts counts = null;
        try {
            counts = countTransactionLog(playerId, state != null && state.found() ? state.updatedAt() : null);
        } catch (SQLException e) {
            errors.put("transactionLogCounts", describe(e));
        }
        List<Map<String, Object>> ops = part(errors, "opsJobs", () -> readOpsJobs(playerId, limit));
        List<Map<String, Object>> recall = part(errors, "recallSources", () -> readRecallSources(playerId, limit));
        return new PlayerBundle.Mysql(account, player, state, snapshots, txlog, counts, ops, recall,
                errors.isEmpty() ? null : errors, limit);
    }

    /** 开一个只发 SELECT 的可重复读快照；版本取自当前连接的实际 SELECT VERSION()。 */
    static void beginSnapshot(Connection connection, String databaseVersion) throws SQLException {
        connection.setAutoCommit(false);
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        // TiDB 8.5 的 READ ONLY 默认报 1235，开启 noop 也不提供只读保护；沿用 AuthorityJdbc 的 SELECT 快照模式。
        // 只对已识别的 TiDB 避开该语法；MySQL 及版本未知时保留 JDBC 只读，任何 SQL 异常原样交给调用者。
        if (databaseVersion == null || !databaseVersion.toLowerCase(Locale.ROOT).contains("tidb")) {
            connection.setReadOnly(true);
        }
    }

    /** 结束快照：回滚（事务内只有固定 SELECT）。 */
    static void endSnapshot(Connection connection) throws SQLException {
        connection.rollback();
    }

    PlayerBundle.Player readPlayer(long playerId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT player_id, account, zone_id, name, class_id, gender, appearance_id, level, scene_config_id,
                       pos_x, pos_y, pos_z, owner_epoch, owner_released, owner_lease_until, created_at, updated_at
                  FROM player WHERE player_id = ?""")) {
            bindU64(ps, 1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new PlayerBundle.Player(rs.getString("player_id"), rs.getString("account"), u32(rs, "zone_id"),
                        rs.getString("name"), u32(rs, "class_id"), u32(rs, "gender"), rs.getString("appearance_id"),
                        u32(rs, "level"), u32(rs, "scene_config_id"), rs.getDouble("pos_x"), rs.getDouble("pos_y"),
                        rs.getDouble("pos_z"), rs.getString("owner_epoch"), rs.getInt("owner_released"),
                        rs.getLong("owner_lease_until"), rs.getLong("created_at"), rs.getLong("updated_at"));
            }
        }
    }

    /** 账号行；口令哈希只判是否为 NULL，原值不出库。 */
    PlayerBundle.Account readAccount(String account) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT account, created_at, CASE WHEN password_hash IS NULL THEN 0 ELSE 1 END AS has_password FROM account WHERE account = ?")) {
            ps.setString(1, account);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new PlayerBundle.Account(rs.getString("account"), rs.getLong("created_at"), rs.getInt("has_password") == 1)
                        : null;
            }
        }
    }

    PlayerBundle.State readState(long playerId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT data, saved_epoch, updated_at FROM player_state WHERE player_id = ?")) {
            bindU64(ps, 1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return PlayerBundle.State.absent();
                }
                byte[] data = rs.getBytes("data");
                return new PlayerBundle.State(true, rs.getString("saved_epoch"), rs.getLong("updated_at"), data.length,
                        sha256(data), Base64.getEncoder().encodeToString(data), StateJson.render(data, json));
            }
        }
    }

    /** 最近的快照：元数据 + 玩法数据的字节数与 sha256（与当前 {@code player_state} 比 sha 就知道哪份不同；逐字段差异用 game-data 的差异接口）。 */
    List<Map<String, Object>> readSnapshots(long playerId, int limit) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT snapshot_id, time_ms, cause, zone_id, owner_epoch, level, scene_config_id, pos_x, pos_y, pos_z,
                       player_state, ingested_at, operator, note
                  FROM player_snapshot WHERE player_id = ? ORDER BY time_ms DESC, snapshot_id DESC LIMIT ?""")) {
            bindU64(ps, 1, playerId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("snapshotId", rs.getString("snapshot_id"));
                    row.put("timeMs", rs.getLong("time_ms"));
                    int cause = (int) u32(rs, "cause");
                    row.put("cause", cause);
                    row.put("causeName", enumName(cause, SnapshotCause::forNumber));
                    row.put("zoneId", u32(rs, "zone_id"));
                    row.put("ownerEpoch", rs.getString("owner_epoch"));
                    row.put("level", u32(rs, "level"));
                    row.put("sceneConfigId", u32(rs, "scene_config_id"));
                    row.put("pos", List.of(rs.getDouble("pos_x"), rs.getDouble("pos_y"), rs.getDouble("pos_z")));
                    byte[] state = rs.getBytes("player_state");
                    row.put("stateBytes", state.length);
                    row.put("stateSha256", sha256(state));
                    row.put("ingestedAt", rs.getLong("ingested_at"));
                    row.put("operator", rs.getString("operator"));
                    row.put("note", rs.getString("note"));
                    out.add(row);
                }
            }
        }
        return out;
    }

    /**
     * 最近的资产流水（该玩家是扣减方或获得方）：两个方向各走自己的索引（{@code idx_txlog_from} / {@code idx_txlog_to}）取最近 {@code limit} 条，
     * 按 (时刻, 流水号) 倒序合并去重后取前 {@code limit} 条。
     */
    List<Map<String, Object>> readTransactionLog(long playerId, int limit) throws SQLException {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (String column : List.of("from_player", "to_player")) {
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT tx_id, time_ms, reason, kind, from_player, to_player, currency_type, currency_delta, balance_before,
                           balance_after, item_uuid, item_config_id, item_quantity, correlation_id, extra, zone_id, ingested_at
                      FROM transaction_log WHERE %s = ? ORDER BY time_ms DESC, tx_id DESC LIMIT ?""".formatted(column))) {
                bindU64(ps, 1, playerId);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("txId", rs.getString("tx_id"));
                        row.put("timeMs", rs.getLong("time_ms"));
                        int reason = (int) u32(rs, "reason");
                        row.put("reason", reason);
                        row.put("reasonName", enumName(reason, TransactionReason::forNumber));
                        int kind = (int) u32(rs, "kind");
                        row.put("kind", kind);
                        row.put("kindName", enumName(kind, AssetKind::forNumber));
                        row.put("fromPlayer", rs.getString("from_player"));
                        row.put("toPlayer", rs.getString("to_player"));
                        row.put("currencyType", u32(rs, "currency_type"));
                        row.put("currencyDelta", rs.getLong("currency_delta"));
                        row.put("balanceBefore", rs.getString("balance_before"));
                        row.put("balanceAfter", rs.getString("balance_after"));
                        row.put("itemUuid", rs.getString("item_uuid"));
                        row.put("itemConfigId", u32(rs, "item_config_id"));
                        row.put("itemQuantity", u32(rs, "item_quantity"));
                        row.put("correlationId", rs.getString("correlation_id"));
                        row.put("extra", rs.getString("extra"));
                        row.put("zoneId", u32(rs, "zone_id"));
                        row.put("ingestedAt", rs.getLong("ingested_at"));
                        byId.putIfAbsent((String) row.get("txId"), row);
                    }
                }
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>(byId.values());
        rows.sort(Comparator.<Map<String, Object>>comparingLong(r -> (Long) r.get("timeMs"))
                .thenComparing((a, b) -> Long.compareUnsigned(Long.parseUnsignedLong((String) a.get("txId")),
                        Long.parseUnsignedLong((String) b.get("txId"))))
                .reversed());
        return rows.size() > limit ? new ArrayList<>(rows.subList(0, limit)) : rows;
    }

    /** 流水计数（两个方向，自转只计一次）；{@code stateUpdatedAt} 非 null 时另数晚于它的条数。 */
    PlayerBundle.TxlogCounts countTransactionLog(long playerId, Long stateUpdatedAt) throws SQLException {
        long total = countByDirection(playerId, Long.MIN_VALUE);
        Long after = stateUpdatedAt == null ? null : countByDirection(playerId, stateUpdatedAt);
        return new PlayerBundle.TxlogCounts(total, after);
    }

    /** 该玩家作为扣减方的条数 + 作为获得方（且不是自己转自己）的条数，只数 {@code time_ms > afterMs} 的。 */
    private long countByDirection(long playerId, long afterMs) throws SQLException {
        long n = 0;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM transaction_log WHERE from_player = ? AND time_ms > ?")) {
            bindU64(ps, 1, playerId);
            ps.setLong(2, afterMs);
            n += single(ps);
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM transaction_log WHERE to_player = ? AND time_ms > ? AND from_player <> ?")) {
            bindU64(ps, 1, playerId);
            ps.setLong(2, afterMs);
            bindU64(ps, 3, playerId);
            n += single(ps);
        }
        return n;
    }

    private static long single(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * 涉及这名玩家的运维作业（回档等，{@code ops_job_player} 联 {@code ops_job}）。{@code ops_job_player} 的主键是 (job_id, player_id)，
     * 按玩家查是全表扫描；运维作业量很小，可以接受。
     */
    List<Map<String, Object>> readOpsJobs(long playerId, int limit) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT p.job_id, j.kind, j.status, j.operator, j.reason, j.result_code, j.created_ms, j.finished_ms,
                       p.planned_snapshot_id, p.planned_snapshot_ms, p.claimed_epoch, p.pre_snapshot_id, p.outcome, p.detail_json,
                       p.written_ms
                  FROM ops_job_player p LEFT JOIN ops_job j ON j.job_id = p.job_id
                 WHERE p.player_id = ? ORDER BY p.job_id DESC LIMIT ?""")) {
            bindU64(ps, 1, playerId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("jobId", rs.getString("job_id"));
                    int kind = rs.getInt("kind");
                    row.put("kind", enumName(kind, OpsJobKind::forNumber));
                    int status = rs.getInt("status");
                    row.put("status", enumName(status, OpsJobStatus::forNumber));
                    row.put("operator", rs.getString("operator"));
                    row.put("reason", rs.getString("reason"));
                    row.put("resultCode", rs.getString("result_code"));
                    row.put("createdMs", rs.getLong("created_ms"));
                    row.put("finishedMs", rs.getLong("finished_ms"));
                    row.put("plannedSnapshotId", rs.getString("planned_snapshot_id"));
                    row.put("plannedSnapshotMs", rs.getLong("planned_snapshot_ms"));
                    row.put("claimedEpoch", rs.getString("claimed_epoch"));
                    row.put("preSnapshotId", rs.getString("pre_snapshot_id"));
                    row.put("outcome", rs.getString("outcome"));
                    row.put("detailJson", rs.getString("detail_json"));
                    row.put("writtenMs", rs.getLong("written_ms"));
                    out.add(row);
                }
            }
        }
        return out;
    }

    /** GM 回收记录（{@code recall_source}，按玩家走 {@code player_id} 索引）：回收从这名玩家身上扣回过什么。 */
    List<Map<String, Object>> readRecallSources(long playerId, int limit) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT tx_id, job_id, kind, asset_id, matched_qty, recovered_qty, at_ms
                  FROM recall_source WHERE player_id = ? ORDER BY at_ms DESC LIMIT ?""")) {
            bindU64(ps, 1, playerId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("txId", rs.getString("tx_id"));
                    row.put("jobId", rs.getString("job_id"));
                    int kind = (int) u32(rs, "kind");
                    row.put("kind", kind);
                    row.put("kindName", enumName(kind, AssetKind::forNumber));
                    row.put("assetId", rs.getString("asset_id"));
                    row.put("matchedQty", rs.getString("matched_qty"));
                    row.put("recoveredQty", rs.getString("recovered_qty"));
                    row.put("atMs", rs.getLong("at_ms"));
                    out.add(row);
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 工具

    private interface SqlPart {
        List<Map<String, Object>> read() throws SQLException;
    }

    private static List<Map<String, Object>> part(Map<String, String> errors, String name, SqlPart part) {
        try {
            return part.read();
        } catch (SQLException e) {
            errors.put(name, describe(e));
            return null;
        }
    }

    /** 只给 SQLState / 错误码 / 消息首行（驱动消息不含 URL；这里的语句也不含口令）。 */
    static String describe(SQLException e) {
        String message = e.getMessage() == null ? "" : e.getMessage().lines().findFirst().orElse("");
        return "SQLState=" + e.getSQLState() + " code=" + e.getErrorCode() + " " + message;
    }

    /** uint64 绑定：≥ 2^63 的玩家号在 Java long 里是负数，按无符号十进制绑定。 */
    static void bindU64(PreparedStatement ps, int index, long value) throws SQLException {
        if (value >= 0) {
            ps.setLong(index, value);
        } else {
            ps.setBigDecimal(index, new BigDecimal(Long.toUnsignedString(value)));
        }
    }

    /** INT UNSIGNED 列按无符号读成 long（经字符串，不经 getInt 的有符号截断）。 */
    static long u32(ResultSet rs, String column) throws SQLException {
        String s = rs.getString(column);
        return s == null ? 0 : Long.parseLong(s);
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String enumName(int number, IntFunction<? extends ProtocolMessageEnum> lookup) {
        ProtocolMessageEnum value = lookup.apply(number);
        return value == null ? "UNKNOWN_" + number : value.getValueDescriptor().getName();
    }
}
