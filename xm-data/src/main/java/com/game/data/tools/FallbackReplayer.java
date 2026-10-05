package com.game.data.tools;

import com.game.data.ops.pb.AuditReplayLineRow;
import com.game.data.tools.FallbackLines.Malformed;
import com.game.data.tools.FallbackLines.Parsed;
import com.game.data.tools.FallbackLines.Snapshot;
import com.game.data.tools.FallbackLines.Transaction;
import com.game.data.txlog.TransactionLogRow;
import com.game.pbmysql.PbMysql;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.LongSupplier;

/**
 * 兜底日志回灌的核心（data-ops-spec §2.4，G9；CLI 外壳是 {@link AuditFallbackReplay}）。逐行、每行一个事务：
 *
 * <ol>
 *   <li>{@code audit_replay_line} 里已登记 (文件 SHA-256, 行号) → 跳过（同一文件重复回灌不重复入库）；</li>
 *   <li>定流水号：行里带号（{@code tx_id ≠ 0}）就用原号——与 Kafka 落库路径按主键天然幂等；{@code tx_id = 0}（未核对 / 发不出号 /
 *       队列满）发新号，号取自 {@code NodeTypes.SCENE_GUID} 全服池（与 scene 同池，不会撞号，§2.3）；</li>
 *   <li>库里还没有这个号就插 {@code transaction_log}；</li>
 *   <li>登记这一行（入库用的号、结局），提交。</li>
 * </ol>
 * 登记与入库同一事务：中途崩溃要么都在、要么都不在，重跑从断点继续。发不出新号时立即停（之前的行已各自提交），返回失败。
 *
 * <p>文件按<b>整份内容</b>的 SHA-256 识别：只回灌已轮转、不再写入的文件（还在追加的文件内容变了就是另一个文件，tx_id = 0 的行会被当新行再入一次）。
 * 不是线程安全的（一个实例回灌一个文件）。
 */
public final class FallbackReplayer {

    /** 回灌结果计数。 */
    public record Stats(int lines, int transactions, int snapshotsSkipped, int malformed, int inserted, int duplicate,
                        int alreadyReplayed, int newIds, List<String> problems, boolean aborted) {

        /** 全部成功：没有解析失败、没有中止。 */
        public boolean ok() {
            return malformed == 0 && !aborted;
        }
    }

    /** 新流水号的来源（租约无效时为空）。 */
    @FunctionalInterface
    public interface Ids {
        OptionalLong next();
    }

    static final String INSERT_SQL = "INSERT INTO transaction_log (tx_id, time_ms, reason, kind, from_player, to_player, "
            + "currency_type, currency_delta, balance_before, balance_after, item_uuid, item_config_id, item_quantity, "
            + "correlation_id, extra, zone_id, ingested_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    static final String EXISTS_SQL = "SELECT COUNT(*) FROM transaction_log WHERE tx_id = ?";
    /** 问题清单至多列这么多条（其余只计数）。 */
    static final int MAX_PROBLEMS = 100;

    private final Connection conn;
    private final PbMysql db;
    private final Ids ids;
    private final LongSupplier clockMs;

    /**
     * @param db 登记了 {@link AuditReplayLineRow} 的 pbmysql 实例（表由调用方事先建好）
     */
    public FallbackReplayer(Connection conn, PbMysql db, Ids ids, LongSupplier clockMs) {
        this.conn = conn;
        this.db = db;
        this.ids = ids;
        this.clockMs = clockMs;
    }

    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺 SHA-256", e);
        }
    }

    /** 只解析不写库（{@code --dry-run}）。 */
    public static Stats parseOnly(byte[] content) {
        Counter c = new Counter();
        int lineNo = 0;
        for (String line : lines(content)) {
            lineNo++;
            c.lines++;
            classify(FallbackLines.parse(line), c, lineNo);
        }
        return c.stats(false);
    }

    /** 回灌一整个文件的内容。数据库错误照常抛出（之前的行已各自提交）。 */
    public Stats replay(byte[] content) throws SQLException {
        String sha = sha256(content);
        Counter c = new Counter();
        boolean autoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            int lineNo = 0;
            for (String line : lines(content)) {
                lineNo++;
                c.lines++;
                Parsed parsed = FallbackLines.parse(line);
                if (!(parsed instanceof Transaction tx)) {
                    classify(parsed, c, lineNo);
                    continue;
                }
                c.transactions++;
                if (!replayLine(sha, lineNo, tx.row(), c)) {
                    c.problem("第 " + lineNo + " 行：全服发号租约无效，发不出新流水号，回灌中止（已完成的行不受影响，修复后重跑即可续上）");
                    return c.stats(true);
                }
            }
            return c.stats(false);
        } finally {
            conn.setAutoCommit(autoCommit);
        }
    }

    /** 回灌一行；发不出新号时返回 false（什么也没写）。 */
    private boolean replayLine(String sha, int lineNo, TransactionLogRow row, Counter c) throws SQLException {
        AuditReplayLineRow key = AuditReplayLineRow.newBuilder().setFileSha256(sha).setLineNo(lineNo).build();
        try {
            if (db.findOneByPk(conn, key).isPresent()) {
                conn.rollback();
                c.alreadyReplayed++;
                return true;
            }
            long txId = row.txId();
            boolean fresh = false;
            if (txId == 0) {
                OptionalLong id = ids.next();
                if (id.isEmpty()) {
                    conn.rollback();
                    return false;
                }
                txId = id.getAsLong();
                fresh = true;
            }
            boolean exists = exists(txId);
            long now = clockMs.getAsLong();
            if (!exists) {
                insert(txId, row, now);
            }
            db.insert(conn, key.toBuilder().setTxId(txId).setAtMs(now).setOutcome(exists ? "duplicate" : "inserted").build());
            conn.commit();
            if (exists) {
                c.duplicate++;
            } else {
                c.inserted++;
            }
            if (fresh) {
                c.newIds++;
            }
            return true;
        } catch (SQLException e) {
            conn.rollback();
            if (isDuplicate(e)) {
                // 另一个回灌进程同时登记了这一行：以它为准
                c.alreadyReplayed++;
                return true;
            }
            throw e;
        }
    }

    private boolean exists(long txId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(EXISTS_SQL)) {
            setU64(ps, 1, txId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getLong(1) > 0;
            }
        }
    }

    private void insert(long txId, TransactionLogRow r, long ingestedAt) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
            setU64(ps, 1, txId);
            ps.setLong(2, r.timeMs());
            ps.setLong(3, Integer.toUnsignedLong(r.reason()));
            ps.setLong(4, Integer.toUnsignedLong(r.kind()));
            setU64(ps, 5, r.fromPlayer());
            setU64(ps, 6, r.toPlayer());
            ps.setLong(7, Integer.toUnsignedLong(r.currencyType()));
            ps.setLong(8, r.currencyDelta());
            setU64(ps, 9, r.balanceBefore());
            setU64(ps, 10, r.balanceAfter());
            setU64(ps, 11, r.itemUuid());
            ps.setLong(12, Integer.toUnsignedLong(r.itemConfigId()));
            ps.setLong(13, Integer.toUnsignedLong(r.itemQuantity()));
            setU64(ps, 14, r.correlationId());
            ps.setString(15, r.extra());
            ps.setLong(16, Integer.toUnsignedLong(r.zoneId()));
            ps.setLong(17, ingestedAt);
            ps.executeUpdate();
        }
    }

    /** uint64 按位放在 long 里：≥ 2^63 的值按无符号十进制绑定（同 UnsignedLongTypeHandler）。 */
    private static void setU64(PreparedStatement ps, int index, long bits) throws SQLException {
        if (bits >= 0) {
            ps.setLong(index, bits);
        } else {
            ps.setBigDecimal(index, new BigDecimal(Long.toUnsignedString(bits)));
        }
    }

    /** 主键 / 唯一键冲突：MySQL 1062，或标准 SQLState 23505（H2 等）。 */
    static boolean isDuplicate(SQLException e) {
        for (SQLException x = e; x != null; x = x.getNextException()) {
            if (x.getErrorCode() == 1062 || "23505".equals(x.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    static List<String> lines(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>(List.of(text.split("\n", -1)));
        if (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1); // 文件末尾的换行不算一行
        }
        return out;
    }

    private static void classify(Parsed parsed, Counter c, int lineNo) {
        switch (parsed) {
            case Transaction t -> c.transactions++;
            case Snapshot s -> c.snapshotsSkipped++;
            case Malformed m -> {
                c.malformed++;
                c.problem("第 " + lineNo + " 行：" + m.why());
            }
            default -> {
            }
        }
    }

    /** 可变计数器（只在回灌线程上用）。 */
    private static final class Counter {
        int lines;
        int transactions;
        int snapshotsSkipped;
        int malformed;
        int inserted;
        int duplicate;
        int alreadyReplayed;
        int newIds;
        final List<String> problems = new ArrayList<>();

        void problem(String p) {
            if (problems.size() < MAX_PROBLEMS) {
                problems.add(p);
            }
        }

        Stats stats(boolean aborted) {
            return new Stats(lines, transactions, snapshotsSkipped, malformed, inserted, duplicate, alreadyReplayed, newIds,
                    List.copyOf(problems), aborted);
        }
    }
}
