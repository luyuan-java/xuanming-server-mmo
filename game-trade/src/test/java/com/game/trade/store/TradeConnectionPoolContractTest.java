package com.game.trade.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.trade.rules.TradeLimits;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * 连接池会话参数的契约：等锁的语句（行锁 {@code innodb_lock_wait_timeout}、元数据锁 {@code lock_wait_timeout}）都由库端以 1205 中止，
 * 且早于客户端读超时 {@code socketTimeout}——读超时只断开连接、撤不回已发出的语句，库端拿到锁后照样执行，会成为 gate 放行同会话下一条
 * 之后的迟到写（mmorpg client-request-ordering.md §6.1 的 2026-10-16 订正注；PARITY「业务库连接超时与会话锁等待」）。MySQL 的
 * {@code lock_wait_timeout} 缺省一年，不设就等于没有这道保护。
 *
 * <p>第一条不连库、每次构建都跑：生产 {@code spring.datasource.url} 的会话变量与真库夹具逐字节相同，两种锁等待同值、都短于 socketTimeout。
 * 第二条连真库（{@code -Dgame.it.mysql}；TiDB 档另加 {@code -Dgame.it.require-tidb=true}），在与生产同口径的连接池借出的连接上读
 * {@code @@SESSION.lock_wait_timeout}。
 */
class TradeConnectionPoolContractTest {

    @Test
    void 生产连接串与夹具的会话变量相同_元数据锁与行锁等待同值且短于socketTimeout() throws IOException {
        String url = productionUrl();
        String sessionVariables = queryParameter(url, "sessionVariables");
        assertThat(sessionVariables).as("真库用例验的必须是线上的数据库会话").isEqualTo(TradeMysqlFixture.SESSION_VARIABLES);

        Map<String, String> variables = parseSessionVariables(sessionVariables);
        String expected = Integer.toString(TradeLimits.LOCK_WAIT_TIMEOUT_SECONDS);
        assertThat(variables).containsEntry("lock_wait_timeout", expected).containsEntry("innodb_lock_wait_timeout", expected);
        assertThat(Long.parseLong(queryParameter(url, "socketTimeout")))
                .as("库端先以 1205 中止，客户端读超时才到")
                .isGreaterThan(TradeLimits.LOCK_WAIT_TIMEOUT_SECONDS * 1000L);
    }

    @Test
    @EnabledIfSystemProperty(named = "game.it.mysql", matches = ".+")
    void 真库_连接池借出的连接上元数据锁等待是约定值() throws Exception {
        try (TradeMysqlFixture fixture = TradeMysqlFixture.create();
             Connection c = fixture.dataSource.getConnection();
             Statement sql = c.createStatement();
             ResultSet rs = sql.executeQuery("SELECT VERSION(), @@SESSION.lock_wait_timeout, @@SESSION.innodb_lock_wait_timeout")) {
            assertThat(rs.next()).isTrue();
            String version = rs.getString(1);
            assertThat(rs.getLong(3)).isEqualTo(TradeLimits.LOCK_WAIT_TIMEOUT_SECONDS);
            if (version != null && version.toLowerCase(Locale.ROOT).contains("tidb")) {
                // TiDB 把 lock_wait_timeout 当作只接受、不生效的兼容变量（pingcap/tidb pkg/sessionctx/variable/noop.go），
                // 它的 DML 也不像 MySQL 那样排在 DDL 的元数据锁后面。这一档的契约是「同一串连接串能建连」：走到这里说明建连时的 SET 已被接受。
                return;
            }
            assertThat(rs.getLong(2)).as("MySQL 会话的元数据锁等待").isEqualTo(TradeLimits.LOCK_WAIT_TIMEOUT_SECONDS);
        }
    }

    /** application.yaml 里的 {@code spring.datasource.url} 原文（库名占位符不解析，只看查询串）。 */
    private static String productionUrl() throws IOException {
        for (PropertySource<?> document : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))) {
            Object url = document.getProperty("spring.datasource.url");
            if (url != null) {
                return url.toString();
            }
        }
        throw new AssertionError("application.yaml 没有 spring.datasource.url");
    }

    private static String queryParameter(String url, String name) {
        int query = url.indexOf('?');
        assertThat(query).as("连接串带查询串").isPositive();
        for (String pair : url.substring(query + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return pair.substring(eq + 1);
            }
        }
        throw new AssertionError("连接串缺少 " + name + "：" + url);
    }

    /** Connector/J 的 sessionVariables：逗号分隔的 {@code 名=值}，单引号里的逗号不算分隔。 */
    private static Map<String, String> parseSessionVariables(String spec) {
        Map<String, String> out = new LinkedHashMap<>();
        boolean quoted = false;
        int start = 0;
        for (int i = 0; i <= spec.length(); i++) {
            if (i == spec.length() || (spec.charAt(i) == ',' && !quoted)) {
                String item = spec.substring(start, i);
                int eq = item.indexOf('=');
                assertThat(eq).as("会话变量 " + item).isPositive();
                out.put(item.substring(0, eq).strip(), item.substring(eq + 1).strip());
                start = i + 1;
            } else if (spec.charAt(i) == '\'') {
                quoted = !quoted;
            }
        }
        return out;
    }
}
