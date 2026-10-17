package com.game.trade.commerce;

import com.game.common.deadline.Deadline;
import com.game.trade.store.pb.TradeAssetOpRow;
import com.game.trade.store.pb.TradeCommerceRow;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import java.math.BigInteger;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/** 合同、seq及可靠待办的单SQL事务边界；表定义只来自本模块proto。 */
public final class CommerceStore {
    private final DataSource source;
    public CommerceStore(DataSource source) {
        this.source = Objects.requireNonNull(source);
    }
    @FunctionalInterface
    public interface Transaction<T> {
        T run(Connection connection) throws SQLException;
    }
    public <T> T transaction(Deadline deadline, Transaction<T> action) throws SQLException {
        active(deadline);
        try (var c = source.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(false);
            try {
                T result = action.run(c);
                active(deadline);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException | Error error) {
                try {
                    c.rollback();
                } catch (SQLException rollback) {
                    error.addSuppressed(rollback);
                }
                throw error;
            }
        }
    }
    public static void active(Deadline d) throws SQLTimeoutException {
        if (Thread.currentThread().isInterrupted() || d.expired())
            throw new SQLTimeoutException("交易请求预算已结束");
    }
    private static String columns(Message v) {
        return String.join(",", v.getDescriptorForType().getFields().stream().map(FieldDescriptor::getName).toList());
    }
    private static void bind(PreparedStatement s, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object v = args[i];
            if (v instanceof ByteString b)
                v = b.toByteArray();
            if (v instanceof Long n && n < 0)
                v = new BigInteger(Long.toUnsignedString(n));
            if (v instanceof Integer n && n < 0)
                v = Integer.toUnsignedLong(n);
            s.setObject(i + 1, v);
        }
    }
    public static int execute(Connection c, String sql, Object... args) throws SQLException {
        try (var s = c.prepareStatement(sql)) {
            s.setQueryTimeout(2);
            bind(s, args);
            return s.executeUpdate();
        }
    }
    @SuppressWarnings("unchecked")
    private static <T extends Message> T read(ResultSet rs, T prototype) throws SQLException {
        Message.Builder b = prototype.newBuilderForType();
        for (var f : prototype.getDescriptorForType().getFields()) {
            Object value = switch (f.getType()) {
                case UINT64, FIXED64 -> Long.parseUnsignedLong(rs.getString(f.getName()));
                case UINT32, FIXED32 -> Integer.parseUnsignedInt(rs.getString(f.getName()));
                case INT32, SINT32, SFIXED32 -> rs.getInt(f.getName());
                case INT64, SINT64, SFIXED64 -> rs.getLong(f.getName());
                case BOOL -> rs.getBoolean(f.getName());
                case STRING -> rs.getString(f.getName());
                case BYTES -> ByteString.copyFrom(rs.getBytes(f.getName()));
                default -> throw new SQLException("交易表出现不支持的列类型");
            };
            b.setField(f, value);
        }
        return (T) b.build();
    }
    public static <T extends Message> List<T> query(Connection c, T prototype, String sql, Object... args)
        throws SQLException {
        try (var s = c.prepareStatement(sql)) {
            s.setQueryTimeout(2);
            bind(s, args);
            try (var rs = s.executeQuery()) {
                List<T> result = new ArrayList<>();
                while (rs.next()) result.add(read(rs, prototype));
                return result;
            }
        }
    }
    public static void insert(Connection c, String table, Message row) throws SQLException {
        List<Object> values = new ArrayList<>();
        for (var f : row.getDescriptorForType().getFields()) values.add(row.getField(f));
        execute(c,
            "INSERT INTO " + table + " (" + columns(row) + ") VALUES ("
                + String.join(",", java.util.Collections.nCopies(values.size(), "?")) + ")",
            values.toArray());
    }
    public TradeCommerceRow get(long id) throws SQLException {
        try (var c = source.getConnection()) {
            return one(c, "commerce_id=?", id);
        }
    }
    public TradeCommerceRow byRequest(String key) throws SQLException {
        try (var c = source.getConnection()) {
            return one(c, "request_key=?", key);
        }
    }
    public TradeCommerceRow lock(Connection c, long id) throws SQLException {
        return one(c, "commerce_id=? FOR UPDATE", id);
    }
    private TradeCommerceRow one(Connection c, String where, Object arg) throws SQLException {
        var rows = query(c, TradeCommerceRow.getDefaultInstance(),
            "SELECT " + columns(TradeCommerceRow.getDefaultInstance()) + " FROM trade_commerce WHERE " + where, arg);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    public List<TradeCommerceRow> list(String where, Object... args) throws SQLException {
        try (var c = source.getConnection()) {
            return list(c, where, args);
        }
    }
    public List<TradeCommerceRow> list(Connection c, String where, Object... args) throws SQLException {
        return query(c, TradeCommerceRow.getDefaultInstance(),
            "SELECT " + columns(TradeCommerceRow.getDefaultInstance()) + " FROM trade_commerce WHERE " + where, args);
    }
    public TradeCommerceRow save(Connection c, TradeCommerceRow.Builder row) throws SQLException {
        long version = row.getVersion();
        row.setVersion(version + 1);
        List<String> sets = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (var f : row.getDescriptorForType().getFields()) {
            if (f.getName().equals("commerce_id"))
                continue;
            sets.add(f.getName() + "=?");
            values.add(row.getField(f));
        }
        values.add(row.getCommerceId());
        values.add(version);
        if (execute(c, "UPDATE trade_commerce SET " + String.join(",", sets) + " WHERE commerce_id=? AND version=?",
                values.toArray())
            != 1)
            throw new SQLException("交易合同版本变化");
        return row.build();
    }
    public static long sqlNow(Connection c) throws SQLException {
        try (var s = c.createStatement()) {
            s.setQueryTimeout(2);
            try (var rs = s.executeQuery("SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)")) {
                if (!rs.next())
                    throw new SQLException("交易时钟不可用");
                return rs.getLong(1);
            }
        }
    }
    public void ensureSeqs(Deadline deadline, long... players) throws SQLException {
        transaction(deadline, c -> {
            execute(c,
                "INSERT IGNORE INTO trade_player_op_seq(player_id,stream,next_seq,epoch,updated_ms) VALUES(0,0,1,1,1)");
            try (var s = c.prepareStatement(
                     "SELECT next_seq FROM trade_player_op_seq WHERE player_id=0 AND stream=0 FOR UPDATE")) {
                s.setQueryTimeout(2);
                try (var rs = s.executeQuery()) {
                    if (!rs.next())
                        throw new SQLException("交易seq守卫缺失");
                }
            }
            long now = sqlNow(c);
            for (long p : java.util.Arrays.stream(players)
                     .filter(v -> v != 0)
                     .distinct()
                     .boxed()
                     .sorted(Long::compareUnsigned)
                     .toList())
                for (int stream : new int[] {3, 4})
                    execute(c,
                        "INSERT IGNORE INTO trade_player_op_seq(player_id,stream,next_seq,epoch,updated_ms) "
                            + "VALUES(?,?,1,?,?)",
                        p, stream, now, now);
            return null;
        });
    }
    public void enqueue(Connection c, TradeAssetOpRow.Builder op) throws SQLException {
        long seq, epoch;
        try (var s = c.prepareStatement(
                 "SELECT next_seq,epoch FROM trade_player_op_seq WHERE player_id=? AND stream=? FOR UPDATE")) {
            s.setQueryTimeout(2);
            bind(s, op.getPlayerId(), op.getStream());
            try (var rs = s.executeQuery()) {
                if (!rs.next())
                    throw new SQLException("交易seq未预建");
                seq = Long.parseUnsignedLong(rs.getString(1));
                epoch = Long.parseUnsignedLong(rs.getString(2));
            }
        }
        if (seq == 0 || seq == -1 || epoch == 0)
            throw new SQLException("交易seq耗尽或损坏");
        try (var s = c.prepareStatement("SELECT COUNT(*),COALESCE(MIN(seq),0) FROM trade_asset_op WHERE player_id=? "
                 + "AND stream=? AND stream_epoch=? AND status=1")) {
            s.setQueryTimeout(2);
            bind(s, op.getPlayerId(), op.getStream(), epoch);
            try (var rs = s.executeQuery()) {
                rs.next();
                long first = Long.parseUnsignedLong(rs.getString(2));
                if (rs.getInt(1) >= 16 || first != 0 && Long.compareUnsigned(seq - first, 512) >= 0)
                    throw new SQLException("交易流未决背压");
            }
        }
        execute(c, "UPDATE trade_player_op_seq SET next_seq=?,updated_ms=? WHERE player_id=? AND stream=?", seq + 1,
            op.getCreatedMs(), op.getPlayerId(), op.getStream());
        op.setSeq(seq).setStreamEpoch(epoch);
        insert(c, "trade_asset_op", op.build());
    }
    public List<TradeAssetOpRow> ops(Connection c, String where, Object... args) throws SQLException {
        return query(c, TradeAssetOpRow.getDefaultInstance(),
            "SELECT " + columns(TradeAssetOpRow.getDefaultInstance()) + " FROM trade_asset_op WHERE " + where, args);
    }
    public List<TradeAssetOpRow> ops(String where, Object... args) throws SQLException {
        try (var c = source.getConnection()) {
            return ops(c, where, args);
        }
    }
}
