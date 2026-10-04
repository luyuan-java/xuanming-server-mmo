package com.game.guild.store;

import com.game.common.deadline.Deadline;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 一条连接上的语句助手：每条语句都挂在一个截止时刻上（事务里是本次尝试的子预算，事务外是请求预算）。
 *
 * <p><b>语句纪律</b>（guild-spec §7.4、§7.6）：发语句之前先查截止时刻，过了就不发（抛 {@link BudgetExpired}，由调用方定性）；
 * 查询超时取 {@code min(上限, 剩余向上取整到秒)}——JDBC 只到秒级，子预算靠「语句前检查 + 查询超时 + innodb_lock_wait_timeout=1」
 * 三样合起来实现。
 *
 * <p><b>参数绑定约定</b>（帮会表的整数列全是无符号）：{@link Long} 一律按 uint64 绑定（≥ 2^63 的位模式转成 {@link BigInteger}，
 * BIGINT UNSIGNED 不收负数）、{@link Integer} 一律按 uint32 绑定（{@link Integer#toUnsignedLong}）、{@link String} 原样；
 * {@link BigInteger} / {@link Boolean} 透传。别的类型直接拒绝（多半是忘了拆箱的枚举）。要绑有符号 64 位值时传 {@link BigInteger}。
 * 读回用 {@link #u64} / {@link #u32}。
 *
 * <p>不是线程安全的：一个实例只属于一次事务尝试（或一次事务外读），用完即弃。
 */
public final class GuildJdbc {

    /** 截止时刻已过，语句没有发出。事务里定性为子预算到期（WRITE_CONFLICT 或请求预算用完），事务外定性为依赖故障。 */
    public static final class BudgetExpired extends SQLException {
        BudgetExpired(String sql) {
            super("超过预算，不再执行: " + abbreviate(sql));
        }
    }

    /** 一行映射成一个值。 */
    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private final Connection connection;
    private final Deadline deadline;
    private final int queryTimeoutCapSeconds;

    GuildJdbc(Connection connection, Deadline deadline, int queryTimeoutCapSeconds) {
        this.connection = connection;
        this.deadline = deadline;
        this.queryTimeoutCapSeconds = queryTimeoutCapSeconds;
    }

    /** 本连接上语句共用的截止时刻（事务里是子预算）。 */
    public Deadline deadline() {
        return deadline;
    }

    /** 单行查询；没有行返回 null（映射结果本身不许是 null）。 */
    public <T> T one(String sql, RowMapper<T> mapper, Object... args) throws SQLException {
        try (PreparedStatement ps = prepare(sql, args); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? mapper.map(rs) : null;
        }
    }

    /** 多行查询，读完才返回（游标随之关闭：同一连接上游标没关就发下一条语句会被驱动拒绝）。 */
    public <T> List<T> list(String sql, RowMapper<T> mapper, Object... args) throws SQLException {
        try (PreparedStatement ps = prepare(sql, args); ResultSet rs = ps.executeQuery()) {
            List<T> out = new ArrayList<>();
            while (rs.next()) {
                out.add(mapper.map(rs));
            }
            return out;
        }
    }

    /** 逐行交给 {@code sink}（不在内存里攒整张结果；sink 抛的异常原样上抛）。 */
    public <T> void forEach(String sql, RowMapper<T> mapper, Consumer<? super T> sink, Object... args) throws SQLException {
        try (PreparedStatement ps = prepare(sql, args); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                sink.accept(mapper.map(rs));
            }
        }
    }

    /** 只返回一列 BIGINT UNSIGNED 的查询（readIDColumn，guild_manage_repo.go:1031-1052）。 */
    public List<Long> ids(String sql, Object... args) throws SQLException {
        return list(sql, rs -> u64(rs, 1), args);
    }

    /** {@code SELECT COUNT(*) ...}。 */
    public long count(String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = prepare(sql, args); ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                throw new SQLException("计数查询没有返回行: " + abbreviate(sql));
            }
            return rs.getLong(1);
        }
    }

    /** 有没有行（锁定读时就是 lockRowExists，asset_store.go:822-833）。 */
    public boolean exists(String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = prepare(sql, args); ResultSet rs = ps.executeQuery()) {
            return rs.next();
        }
    }

    /** 写语句，返回实际改动行数（连接串必须带 {@code useAffectedRows=true}，否则 UPDATE 回的是匹配行数）。 */
    public int update(String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = prepare(sql, args)) {
            return ps.executeUpdate();
        }
    }

    /**
     * 写入自检（execExactlyOneRow，guild_manage_repo.go:1154-1170）：改动行数不是 1 → {@link GuildStoreException}
     * （{@link GuildStoreException.Kind#ROW_COUNT_MISMATCH}，事务随之回滚）。0 行意味着刚在锁内读到的行写的时候不见了，
     * 继续提交就是一份「响应说成功、库里没变」的假成功。
     */
    public void updateExactlyOne(String what, String sql, Object... args) throws SQLException {
        int affected = update(sql, args);
        if (affected != 1) {
            throw new GuildStoreException(GuildStoreException.Kind.ROW_COUNT_MISMATCH,
                    what + ": 期望恰好改动 1 行，实际 " + affected);
        }
    }

    // ================================================================ 无符号读写

    /** 无符号 64 位值的绑定形式：大于 Long.MAX_VALUE 的位模式用 {@link BigInteger}。 */
    public static Object u64(long bits) {
        return bits >= 0 ? (Object) bits : new BigInteger(Long.toUnsignedString(bits));
    }

    /** 读 BIGINT UNSIGNED 列为无符号 64 位位模式（驱动对超过 Long.MAX_VALUE 的值给 {@link BigInteger}）。 */
    public static long u64(ResultSet rs, int column) throws SQLException {
        Object value = rs.getObject(column);
        if (value instanceof BigInteger big) {
            return big.longValue();
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new SQLException("列 " + column + " 不是数字: " + value);
    }

    /** 读 INT UNSIGNED 列为 uint32 位模式。 */
    public static int u32(ResultSet rs, int column) throws SQLException {
        return (int) rs.getLong(column);
    }

    // ================================================================ 内部

    private PreparedStatement prepare(String sql, Object... args) throws SQLException {
        long remaining = deadline.remainingMillis();
        if (remaining <= 0 || deadline.expired()) {
            throw new BudgetExpired(sql);
        }
        int timeout = (int) Math.max(1, (remaining + 999) / 1000);
        if (queryTimeoutCapSeconds > 0) {
            timeout = Math.min(timeout, queryTimeoutCapSeconds);
        }
        PreparedStatement ps = connection.prepareStatement(sql);
        try {
            ps.setQueryTimeout(timeout);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, bindValue(args[i]));
            }
            return ps;
        } catch (SQLException | RuntimeException e) {
            ps.close();
            throw e;
        }
    }

    static Object bindValue(Object arg) {
        if (arg instanceof Long l) {
            return u64(l);
        }
        if (arg instanceof Integer i) {
            return Integer.toUnsignedLong(i);
        }
        if (arg instanceof String || arg instanceof BigInteger || arg instanceof Boolean) {
            return arg;
        }
        throw new IllegalArgumentException("帮会 SQL 参数只收 Long(uint64) / Integer(uint32) / String / BigInteger / Boolean，收到 "
                + (arg == null ? "null" : arg.getClass().getName()));
    }

    static String abbreviate(String sql) {
        return sql.length() <= 60 ? sql : sql.substring(0, 60) + "…";
    }
}
