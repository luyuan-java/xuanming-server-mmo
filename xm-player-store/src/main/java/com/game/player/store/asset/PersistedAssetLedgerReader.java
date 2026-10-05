package com.game.player.store.asset;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.player.store.state.PlayerState;
import com.google.protobuf.InvalidProtocolBufferException;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

/**
 * 已落盘账本只读查询（asset-op-ledger-read，guild-economy-spec §4.9；基线 data_service {@code GetPlayerAssetOpLedger}，
 * {@code asset_op_ledger_logic.go:68-124}）：直读 {@code xm_java.player_state} 里该玩家已提交的那份记录，解出 {@code asset_ledger}，
 * 交给 {@link PersistedAssetLedger} 分类。
 *
 * <p>为什么直读 MySQL：Java 的 durable 就是「player_state 带 owner_epoch 围栏写提交成功」（scene 判 durable 看的
 * {@code persistedState} 是它的镜像，E7），没有基线那层「Redis blob 才是 durable、MySQL 可能更旧」的区别，不需要 data_service 式中转。
 *
 * <p>契约（同基线 {@code ledger_dataservice.go:17-24} 的映射）：
 * <ul>
 *   <li>没有这一行（玩家从未写过状态）→ {@link Read.Absent}：调用方计 absent，照常退避；</li>
 *   <li>有行 → {@link Read.Loaded}；没有 {@code asset_ledger} 段 = 空账本（不是 Absent）；</li>
 *   <li>读失败 / 超时 / 解析失败 / player_id 为 0 → {@link Read.Failed}：调用方计 error、继续重投，<b>不终结</b>（I7）。</li>
 * </ul>
 * 不重试（下一次读本身就是重试，节律由调用方退避决定）。<b>阻塞 JDBC</b>：只在调用方的专用工作线程上调用，
 * 不得在 Netty I/O / 场景逻辑线程上调。单次上限缺省 300 ms（{@code svc/asset_op.go:53-56}）：Druid 连接池的取连接等待与
 * 连接的网络超时都按剩余预算设，另设 1 s 的服务端语句超时兜底（JDBC 语句超时只有秒级）。
 *
 * <p>本类不进 {@code PlayerStoreAutoConfiguration}：调用方（xm-guild）用自己的数据源构造它，并排除那个自动装配（Q3a）。
 * 线程安全（无可变状态）。
 */
public final class PersistedAssetLedgerReader {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofMillis(300);
    static final String SQL = "SELECT data FROM player_state WHERE player_id = ?";
    /** 服务端语句超时（JDBC 只有秒级；真正的上限是网络超时）。 */
    private static final int QUERY_TIMEOUT_SECONDS = 1;

    /** 一次读的结局（只有三种，调用方穷举）。 */
    public sealed interface Read permits Read.Absent, Read.Loaded, Read.Failed {

        /** 没有 {@code player_state} 行。 */
        record Absent() implements Read {
        }

        /** 读到了（可能是空账本，也可能是加载时判损坏的账本——看 {@link PersistedAssetLedger#invalidReason()}）。 */
        record Loaded(PersistedAssetLedger ledger) implements Read {
            public Loaded {
                Objects.requireNonNull(ledger, "ledger");
            }
        }

        /**
         * 读失败（结局未知，不得据此终结）。
         *
         * @param reason 可进日志的原因（不含库错误原文以外的敏感信息）
         * @param cause  原始异常（可能为 null）
         */
        record Failed(String reason, Exception cause) implements Read {
        }
    }

    private final DataSource dataSource;
    private final long timeoutMillis;

    public PersistedAssetLedgerReader(DataSource dataSource) {
        this(dataSource, DEFAULT_TIMEOUT);
    }

    /**
     * @param timeout 单次读的上限（取连接 + 查询），至少 1 ms
     */
    public PersistedAssetLedgerReader(DataSource dataSource, Duration timeout) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        if (timeout.isNegative() || timeout.toMillis() < 1) {
            throw new IllegalArgumentException("读已落盘账本的超时至少 1 ms: " + timeout);
        }
        this.timeoutMillis = timeout.toMillis();
    }

    /** 读一名玩家已落盘的账本（阻塞，至多约一个超时）。从不抛异常。 */
    public Read read(long playerId) {
        if (playerId == 0) {
            return new Read.Failed("player_id = 0", null);
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        byte[] data;
        boolean found;
        try (Connection connection = connect()) {
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining < 1) {
                return new Read.Failed("取连接用完了预算 " + timeoutMillis + " ms", null);
            }
            int previousNetworkTimeout = boundNetworkTimeout(connection, (int) Math.min(remaining, Integer.MAX_VALUE));
            try (PreparedStatement statement = connection.prepareStatement(SQL)) {
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                statement.setObject(1, unsigned(playerId));
                try (ResultSet rows = statement.executeQuery()) {
                    found = rows.next();
                    data = found ? rows.getBytes(1) : null;
                }
            } finally {
                restoreNetworkTimeout(connection, previousNetworkTimeout);
            }
        } catch (SQLException | RuntimeException e) {
            return new Read.Failed("读 player_state 失败 player_id=" + Long.toUnsignedString(playerId) + ": " + e, e);
        }
        if (!found) {
            return new Read.Absent();
        }
        if (data == null) {
            // data 列 NOT NULL；读到 NULL 只可能是库被改坏，按读失败处理，不当空账本
            return new Read.Failed("player_state.data 为 NULL player_id=" + Long.toUnsignedString(playerId), null);
        }
        try {
            return new Read.Loaded(PersistedAssetLedger.of(PlayerState.parseFrom(data)));
        } catch (InvalidProtocolBufferException e) {
            return new Read.Failed("player_state.data 解析失败 player_id=" + Long.toUnsignedString(playerId), e);
        }
    }

    /** Druid 池按预算限制取连接的等待；其它数据源（测试的 H2 等）走普通 getConnection。 */
    private Connection connect() throws SQLException {
        if (dataSource.isWrapperFor(DruidDataSource.class)) {
            return dataSource.unwrap(DruidDataSource.class).getConnection(timeoutMillis);
        }
        return dataSource.getConnection();
    }

    /** 把连接的网络超时收紧到剩余预算，返回原值（不支持时返回 -1，靠语句超时兜底）。 */
    private static int boundNetworkTimeout(Connection connection, int millis) throws SQLException {
        int previous;
        try {
            previous = connection.getNetworkTimeout();
            connection.setNetworkTimeout(Runnable::run, millis);
        } catch (SQLFeatureNotSupportedException | UnsupportedOperationException e) {
            return -1;
        }
        return previous;
    }

    /** 连接要还回池里：恢复原来的网络超时；连接已因超时损坏时恢复失败无妨（池会丢弃坏连接）。 */
    private static void restoreNetworkTimeout(Connection connection, int previous) {
        if (previous < 0) {
            return;
        }
        try {
            if (!connection.isClosed()) {
                connection.setNetworkTimeout(Runnable::run, previous);
            }
        } catch (SQLException | RuntimeException ignored) {
            // 连接已坏：池在归还时按异常分类丢弃它
        }
    }

    /** BIGINT UNSIGNED 不收负数：≥ 2^63 的位模式按 BigInteger 绑定。 */
    static Object unsigned(long bits) {
        return bits >= 0 ? (Object) bits : new BigInteger(Long.toUnsignedString(bits));
    }
}
