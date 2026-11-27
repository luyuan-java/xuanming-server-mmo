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
    /** 未决窗口背压是可恢复等待，区别于损坏合同，不能转人工或假报失败。 */
    static final class PendingBackpressure extends SQLException {
        PendingBackpressure() {
            super("交易流未决背压");
        }
    }
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
                com.game.common.player.PlayerRouteAuthority.requireMeta(c,(int)Math.max(1,Math.min(3,(deadline.remainingMillis()+999)/1000)),true);
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
    /** 仅由插入/重存按 workflow 现算「挂在盘口上」：kind=4 的买卖单且处于 market_open 阶段。 */
    static boolean computeOnOrderBook(com.game.trade.store.pb.TradeCommerceRowOrBuilder row) {
        if (row.getKind() != 4 || row.getSide() == 0 || row.getWorkflowProto().isEmpty()) {
            return false;
        }
        try {
            var workflow = com.game.proto.trade.TradeCommerceWorkflow.parseFrom(row.getWorkflowProto());
            return "market_open".equals(workflow.getStage());
        } catch (com.google.protobuf.InvalidProtocolBufferException ignored) {
            return false;
        }
    }

    public static void insert(Connection c, String table, Message row) throws SQLException {
        if (row instanceof TradeCommerceRow commerce) {
            row = commerce.toBuilder().setOnOrderBook(computeOnOrderBook(commerce)).build();
        }
        List<Object> values = new ArrayList<>();
        for (var f : row.getDescriptorForType().getFields()) values.add(row.getField(f));
        execute(c,
            "INSERT INTO " + table + " (" + columns(row) + ") VALUES ("
                + String.join(",", java.util.Collections.nCopies(values.size(), "?")) + ")",
            values.toArray());
    }
    /** 新合同/绑定买家的提交互锁：按无符号ID顺序先锁玩家；角色冻结也先取同一锁。 */
    static void lockAssetOwners(Connection c, long... players) throws SQLException {
        var ids = new java.util.TreeSet<Long>(Long::compareUnsigned);
        for (long id : players) if (id != 0) ids.add(id);
        if(!ids.isEmpty())com.game.common.player.PlayerRouteAuthority.lock(c,ids,2);
        for (long id : ids) {
            try (var statement = c.prepareStatement("SELECT owner_revision,trade_listing_id FROM player WHERE player_id=? FOR UPDATE")) {
                statement.setQueryTimeout(2); bind(statement, id);
                try (var row = statement.executeQuery()) {
                    if (!row.next() || nativeRevision(row.getString(1)) <= 0 || !"0".equals(row.getString(2)))
                        throw new CommerceRules.Refusal(CommerceRules.CONFLICT);
                }
            }
        }
    }
    /**
     * 后台合同推进专用的参与者归属锁（对齐 Go role_authority.go:147-150）：
     * 角色已删、账号变更或归属修订号已变更属不可逆失配，抛 {@link CommerceEngine.CorruptContract} 隔离为 MANUAL；
     * 仅角色寄售挂牌冻结（trade_listing_id != 0）属暂态冲突，抛 {@link CommerceRules.Refusal} 延迟重试。
     */
    static void lockAdvanceOwners(Connection c, java.util.Map<Long, com.game.proto.trade.TradeRoleIdentity> owners)
            throws SQLException {
        var sorted = new java.util.TreeMap<Long, com.game.proto.trade.TradeRoleIdentity>(Long::compareUnsigned);
        for (var entry : owners.entrySet()) {
            if (entry.getKey() != 0 && entry.getValue() != null) {
                sorted.put(entry.getKey(), entry.getValue());
            }
        }
        if (sorted.isEmpty()) {
            return;
        }
        com.game.common.player.PlayerRouteAuthority.lock(c, sorted.keySet(), 2);
        for (var entry : sorted.entrySet()) {
            long id = entry.getKey();
            var expected = entry.getValue();
            if (expected.getAccount().isEmpty() || expected.getOwnerRevision() <= 0) {
                throw new CommerceEngine.CorruptContract("合同参与者原账号或归属修订缺失");
            }
            try (var statement = c.prepareStatement(
                    "SELECT account,owner_revision,trade_listing_id FROM player WHERE player_id=? FOR UPDATE")) {
                statement.setQueryTimeout(2);
                bind(statement, id);
                try (var row = statement.executeQuery()) {
                    if (!row.next()) {
                        throw new CommerceEngine.OwnerChangedContract("合同参与者角色已删除，隔离等待人工介入 player_id=" + Long.toUnsignedString(id));
                    }
                    long currentRevision = nativeRevision(row.getString(2));
                    if (!expected.getAccount().equals(row.getString(1)) || expected.getOwnerRevision() != currentRevision) {
                        throw new CommerceEngine.OwnerChangedContract("合同参与者归属已变更，隔离等待人工介入 player_id=" + Long.toUnsignedString(id));
                    }
                    if (!"0".equals(row.getString(3))) {
                        throw new CommerceRules.Refusal(CommerceRules.CONFLICT);
                    }
                }
            }
        }
    }
    static com.game.proto.trade.TradeRoleIdentity roleIdentity(Connection c, long player) throws SQLException {
        try (var s = c.prepareStatement("SELECT account,owner_revision FROM player WHERE player_id=? FOR UPDATE")) {
            s.setQueryTimeout(2); bind(s, player);
            try (var row = s.executeQuery()) {
                if (!row.next() || row.getString(1) == null || row.getString(1).isEmpty())
                    throw new CommerceRules.Refusal(CommerceRules.CONFLICT);
                return com.game.proto.trade.TradeRoleIdentity.newBuilder().setAccount(row.getString(1))
                    .setOwnerRevision(nativeRevision(row.getString(2))).build();
            }
        }
    }
    private static long nativeRevision(String value) {
        try {
            long revision = Long.parseUnsignedLong(value);
            if (revision > 0) return revision;
        } catch (NumberFormatException invalid) { /* 统一在业务边界拒绝未知或越界原件。 */ }
        throw new SecurityException("Java账号归属修订超出1..LongMax，拒绝继续");
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
    public List<TradeCommerceRow> due(long now, int limit) throws SQLException {
        return due(now, limit, com.game.common.workshard.WorkShard.SqlFilter.ALL);
    }

    /** 按分片过滤拉取到期合同；分片条件放在 LIMIT 之前，避免他片积压挤占本副本名额。 */
    public List<TradeCommerceRow> due(long now, int limit, com.game.common.workshard.WorkShard.SqlFilter filter)
            throws SQLException {
        if (filter == null || filter.isAll()) {
            return list("status IN (1,3,4,6,8) AND updated_ms<=? ORDER BY updated_ms,commerce_id LIMIT ?", now, limit);
        }
        if (filter.isNone()) {
            return List.of();
        }
        List<Object> args = new ArrayList<>(filter.args().size() + 2);
        args.addAll(filter.args());
        args.add(now);
        args.add(limit);
        return list("status IN (1,3,4,6,8) AND " + filter.sql() + " AND updated_ms<=? ORDER BY updated_ms,commerce_id LIMIT ?",
                args.toArray());
    }

    /** 按分片过滤拉取待投递资产指令（LIMIT 前置分片条件）。 */
    public List<TradeAssetOpRow> pendingOps(long now, int limit, com.game.common.workshard.WorkShard.SqlFilter filter)
            throws SQLException {
        if (filter == null || filter.isAll()) {
            return ops("status=1 AND next_attempt_ms<=? AND lease_until_ms<=? ORDER BY op_id LIMIT ?", now, now, limit);
        }
        if (filter.isNone()) {
            return List.of();
        }
        List<Object> args = new ArrayList<>(filter.args().size() + 3);
        args.addAll(filter.args());
        args.add(now);
        args.add(now);
        args.add(limit);
        return ops("status=1 AND " + filter.sql() + " AND next_attempt_ms<=? AND lease_until_ms<=? ORDER BY op_id LIMIT ?",
                args.toArray());
    }

    /** 合同推进一步无业务变化时仅更新下次复查调度键，不改业务版本、不重存全行，避免撞玩家并发乐观锁。 */
    public void rescheduleUnchanged(Connection c, long commerceId, long version, long nextCheckMs) throws SQLException {
        execute(c, "UPDATE trade_commerce SET updated_ms=? WHERE commerce_id=? AND version=?",
                nextCheckMs, commerceId, version);
    }

    /** 尽力批量唤醒多份合同（如子单收尾唤醒买卖两张母单），只提前 updated_ms，不改版本。 */
    public void wakeMany(java.util.Collection<Long> commerceIds, long nowMs, Deadline deadline) throws SQLException {
        if (commerceIds == null || commerceIds.isEmpty()) {
            return;
        }
        var unique = new java.util.TreeSet<Long>(Long::compareUnsigned);
        for (Long id : commerceIds) {
            if (id != null && id != 0L) {
                unique.add(id);
            }
        }
        if (unique.isEmpty()) {
            return;
        }
        transaction(deadline, c -> {
            for (long cid : unique) {
                execute(c, "UPDATE trade_commerce SET updated_ms=? WHERE commerce_id=? AND status IN (1,3,4,6,8) AND updated_ms>?",
                        nowMs, cid, nowMs);
            }
            return null;
        });
    }
    /** 调度不改业务版本；点锁后复核观察版本，不覆盖其它 worker 刚推进的状态。 */
    public boolean defer(TradeCommerceRow observed, long until, Deadline deadline) throws SQLException {
        return transaction(deadline, c -> {
            var current = lock(c, observed.getCommerceId());
            if (current == null || current.getVersion() != observed.getVersion())
                return false;
            return execute(c, "UPDATE trade_commerce SET updated_ms=? WHERE commerce_id=? AND version=?",
                       until, current.getCommerceId(), current.getVersion()) == 1;
        });
    }
    /** 尽力唤醒指定合同（如拍品结案后唤醒持有中的出价），只把晚于 nowMs 的调度键拉回现在，不改版本。 */
    public void wake(long commerceId, long nowMs, Deadline deadline) throws SQLException {
        transaction(deadline, c -> {
            execute(c, "UPDATE trade_commerce SET updated_ms=? WHERE commerce_id=? AND status IN (1,3,4,6,8) AND updated_ms>?",
                    nowMs, commerceId, nowMs);
            return null;
        });
    }
    /** 只隔离自身已损坏或归属变更的合同，保留资产、待办与坏原件；不发补偿。 */
    public boolean quarantine(TradeCommerceRow observed, Deadline deadline) throws SQLException {
        return quarantine(observed, CommerceRules.MANUAL_REASON_CORRUPT, "合同数据损坏", 0L, deadline);
    }

    public boolean quarantine(TradeCommerceRow observed, String reason, String detail, long nowMs, Deadline deadline)
            throws SQLException {
        return transaction(deadline, c -> {
            var current = lock(c, observed.getCommerceId());
            if (current == null || current.getVersion() != observed.getVersion()
                || current.getStatus() == CommerceRules.MANUAL)
                return false;
            try {
                var builder = current.toBuilder();
                var workflow = CommerceEngine.workflow(current);
                long at = nowMs > 0 ? nowMs : sqlNow(c);
                CommerceRules.markManual(builder, workflow, reason, detail, at);
                save(c, builder.setWorkflowProto(workflow.build().toByteString()).setUpdatedMs(at));
            } catch (CommerceEngine.CorruptContract corrupted) {
                execute(c, "UPDATE trade_commerce SET status=?,on_order_book=0,version=version+1 WHERE commerce_id=? AND version=?",
                    CommerceRules.MANUAL, current.getCommerceId(), current.getVersion());
            }
            return true;
        });
    }

    /** 锁拍品或市场互斥守卫行（与 acceptBid / match 统一锁序：先角色 → 再 guard → 再拍品 → 再出价）。 */
    public static void lockAuctionGuard(Connection c, long guardId) throws SQLException {
        execute(c, "INSERT IGNORE INTO trade_commerce_guard(guard_id) VALUES(?)", guardId);
        try (var s = c.prepareStatement("SELECT guard_id FROM trade_commerce_guard WHERE guard_id=? FOR UPDATE")) {
            s.setQueryTimeout(2);
            bind(s, guardId);
            try (var rs = s.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("交易守卫行锁定失败");
                }
            }
        }
    }

    /** 分页列所有处于人工隔离态（status=MANUAL）的合同，最早转人工的在前。 */
    public List<TradeCommerceRow> listManual(long afterUpdatedMs, long afterCommerceId, int limit) throws SQLException {
        int capped = Math.max(1, Math.min(500, limit));
        if (afterUpdatedMs == 0L && afterCommerceId == 0L) {
            return list("status=? ORDER BY updated_ms,commerce_id LIMIT ?", CommerceRules.MANUAL, capped);
        }
        return list("status=? AND (updated_ms>? OR (updated_ms=? AND commerce_id>?)) ORDER BY updated_ms,commerce_id LIMIT ?",
                CommerceRules.MANUAL, afterUpdatedMs, afterUpdatedMs, afterCommerceId, capped);
    }
    public List<TradeAssetOpRow> ackDue(long after, int limit) throws SQLException {
        return ackDue(after, limit, com.game.common.workshard.WorkShard.SqlFilter.ALL);
    }

    /** 按 op_id 分片拉取待释放收据 ACK（LIMIT 前置分片条件）。 */
    public List<TradeAssetOpRow> ackDue(long after, int limit, com.game.common.workshard.WorkShard.SqlFilter filter)
            throws SQLException {
        if (filter == null || filter.isAll()) {
            return ops("receipt_ack_pending=1 AND status=2 AND durable=1 AND op_id>? ORDER BY op_id LIMIT ?", after, limit);
        }
        if (filter.isNone()) {
            return List.of();
        }
        List<Object> args = new ArrayList<>(filter.args().size() + 2);
        args.addAll(filter.args());
        args.add(after);
        args.add(limit);
        return ops("receipt_ack_pending=1 AND status=2 AND durable=1 AND " + filter.sql() + " AND op_id>? ORDER BY op_id LIMIT ?",
                args.toArray());
    }
    // TiDB RC 的二级索引写不代替主键点锁；与投递/终结统一 op_id 升序，避免旧观察覆盖新状态。
    private List<TradeAssetOpRow> lockCommerceOps(Connection c, long commerceId) throws SQLException {
        List<TradeAssetOpRow> locked = new ArrayList<>();
        for (var candidate : ops(c, "ref_kind=2 AND ref_id=? ORDER BY op_id", commerceId)) {
            var rows = ops(c, "op_id=? FOR UPDATE", candidate.getOpId());
            if (!rows.isEmpty() && rows.getFirst().getRefKind() == 2 && rows.getFirst().getRefId() == commerceId)
                locked.add(rows.getFirst());
        }
        return locked;
    }
    public void markConsumed(Connection c, long commerceId) throws SQLException {
        for (var op : lockCommerceOps(c, commerceId))
            if (!op.getBusinessConsumed())
                execute(c, "UPDATE trade_asset_op SET business_consumed=1 WHERE op_id=? AND ref_kind=2 AND ref_id=?",
                    op.getOpId(), commerceId);
    }
    public void expireDebits(Connection c, long commerceId, long now) throws SQLException {
        for (var op : lockCommerceOps(c, commerceId))
            if (op.getStream() == 3 && op.getStatus() == 1)
                execute(c, "UPDATE trade_asset_op SET deadline_ms=? WHERE op_id=? AND ref_kind=2 AND ref_id=? AND stream=3 AND status=1",
                    now, op.getOpId(), commerceId);
    }
    public TradeCommerceRow save(Connection c, TradeCommerceRow.Builder row) throws SQLException {
        row.setOnOrderBook(computeOnOrderBook(row));
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
                    throw new PendingBackpressure();
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
