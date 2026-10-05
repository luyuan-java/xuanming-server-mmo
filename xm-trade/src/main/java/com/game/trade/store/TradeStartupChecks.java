package com.game.trade.store;

import com.game.trade.rules.TradeLimits;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 启动期的数据库检查（trade-spec §5.10 第 2–3 步之间；Java 增项——基线 trade.go 没有版本检查，但 DSN 强制了 RC 与严格模式，
 * servicecontext.go:225-243）：建表之后、申领租约与暴露 Dubbo 之前，在一条池化连接上读一次会话参数，不满足即拒启。
 *
 * <ol>
 *   <li><b>版本</b>：TiDB 放行；MariaDB 拒绝（不认 {@code MAX_EXECUTION_TIME} 优化器提示，每条 SELECT 的毫秒级预算会静默失效）；MySQL 低于
 *       {@code 8.0.0} 或解析不出一律拒绝（fail-closed：收藏写入的 ODKU 取锁推演与 {@code utf8mb4_unicode_ci} 下的 LIKE 语义都是在 8.x 上核对的）；</li>
 *   <li><b>会话隔离级别</b>必须是 {@code READ-COMMITTED}（连接池漏了 {@code transaction_isolation} 时，取消收藏的自动提交 DELETE 会落回 RR、拿间隙锁，
 *       §1.9）；</li>
 *   <li><b>sql_mode</b> 必须含 {@code STRICT_TRANS_TABLES}（在连接层兜底「超长文本被静默截断」，§1.1）；</li>
 *   <li>{@code innodb_lock_wait_timeout} 不等于 {@value TradeLimits#LOCK_WAIT_TIMEOUT_SECONDS}（Q7）只告警：单条 SQL 另有 2000 ms 的查询超时兜底。</li>
 * </ol>
 * 阻塞 JDBC；只在启动线程上调用。
 */
public final class TradeStartupChecks {

    private static final Logger log = LoggerFactory.getLogger(TradeStartupChecks.class);

    /** MySQL 的最低版本（主, 次, 修订）。 */
    static final int[] MIN_MYSQL = {8, 0, 0};

    static final String SELECT_SESSION = "SELECT VERSION(), @@session.transaction_isolation, @@session.sql_mode,"
            + " @@session.innodb_lock_wait_timeout";

    /** 读到的会话参数。 */
    public record Session(String version, String isolation, String sqlMode, long lockWaitTimeoutSeconds) {
    }

    private TradeStartupChecks() {
    }

    /**
     * 读会话参数并判定。
     *
     * @return 读到的会话参数（供启动日志）
     * @throws IllegalStateException 不满足（拒启）
     * @throws SQLException          读不出来（拒启）
     */
    public static Session check(DataSource dataSource, Duration timeout) throws SQLException {
        Session session = read(dataSource, timeout);
        String rejection = rejection(session);
        if (rejection != null) {
            throw new IllegalStateException(rejection);
        }
        if (session.lockWaitTimeoutSeconds() != TradeLimits.LOCK_WAIT_TIMEOUT_SECONDS) {
            log.warn("数据库会话 innodb_lock_wait_timeout={} s，不是约定的 {} s（spring.datasource.url 的 sessionVariables）；单条 SQL 仍有 {} ms 上限",
                    session.lockWaitTimeoutSeconds(), TradeLimits.LOCK_WAIT_TIMEOUT_SECONDS, TradeLimits.STORE_OP_TIMEOUT_MS);
        }
        return session;
    }

    static Session read(DataSource dataSource, Duration timeout) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SELECT_SESSION)) {
            ps.setQueryTimeout((int) Math.max(1, timeout.toSeconds()));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("读会话参数没有返回行");
                }
                return new Session(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4));
            }
        }
    }

    /** @return null = 通过；否则是拒绝原因 */
    static String rejection(Session session) {
        String version = versionRejection(session.version());
        if (version != null) {
            return version;
        }
        String isolation = session.isolation() == null ? "" : session.isolation().strip().toUpperCase(Locale.ROOT);
        if (!isolation.equals("READ-COMMITTED")) {
            return "数据库会话隔离级别是 \"" + session.isolation() + "\"，聚宝斋要求 READ-COMMITTED（spring.datasource.url 的 sessionVariables"
                    + " transaction_isolation='READ-COMMITTED' 与 druid default-transaction-isolation=2），拒绝启动";
        }
        boolean strict = session.sqlMode() != null && Arrays.stream(session.sqlMode().split(","))
                .anyMatch(mode -> mode.strip().equalsIgnoreCase("STRICT_TRANS_TABLES"));
        if (!strict) {
            return "数据库会话 sql_mode=\"" + session.sqlMode() + "\" 不含 STRICT_TRANS_TABLES（超长文本会被静默截断），拒绝启动";
        }
        return null;
    }

    /** 版本判定（同 xm-guild ServerVersion 的写法，下限不同）：null = 通过。 */
    static String versionRejection(String version) {
        String raw = version == null ? "" : version;
        String lower = raw.strip().toLowerCase(Locale.ROOT);
        if (lower.contains("tidb")) {
            return null;
        }
        String min = MIN_MYSQL[0] + "." + MIN_MYSQL[1] + "." + MIN_MYSQL[2];
        if (lower.contains("mariadb")) {
            return "数据库版本 \"" + raw + "\" 是 MariaDB：不认 MAX_EXECUTION_TIME 优化器提示（每条 SELECT 的预算会静默失效），聚宝斋只支持 MySQL "
                    + min + "+ 与 TiDB，拒绝启动";
        }
        int[] got = parseMySqlVersion(raw.strip());
        if (got == null) {
            return "无法从数据库版本 \"" + raw + "\" 解析出 MySQL 的主.次.修订号，按低于 " + min + " 处理并拒绝启动（fail-closed）";
        }
        if (Arrays.compare(got, MIN_MYSQL) < 0) {
            return "MySQL 版本 \"" + raw + "\" 低于 " + min + "，拒绝启动";
        }
        return null;
    }

    /** 取版本串开头连续的「数字与点」，至少三段、前三段都是十进制数；其后的后缀（-0ubuntu…、-log）忽略。解析不出返回 null。 */
    static int[] parseMySqlVersion(String version) {
        int end = 0;
        while (end < version.length()) {
            char ch = version.charAt(end);
            if (ch != '.' && (ch < '0' || ch > '9')) {
                break;
            }
            end++;
        }
        String[] parts = version.substring(0, end).split("\\.", -1);
        if (parts.length < 3) {
            return null;
        }
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 9) {
                return null;
            }
            out[i] = Integer.parseInt(parts[i]);
        }
        return out;
    }
}
