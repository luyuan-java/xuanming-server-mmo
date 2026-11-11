package com.game.trade.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.trade.store.TradeStartupChecks.Session;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 启动期数据库检查（trade-spec §5.10；Java 增项）：版本下限（MySQL 8.0.0+ / TiDB，MariaDB 与解析不出拒绝）、会话 RC、STRICT_TRANS_TABLES、
 * 元数据锁等待（MySQL 档不是约定值告警）；真 MySQL 用例（{@code -Dgame.it.mysql}）验证生产同口径的连接串能通过检查。
 */
class TradeStartupChecksTest {

    private static Session session(String version, String isolation, String sqlMode) {
        return new Session(version, isolation, sqlMode, 2, 2);
    }

    @Test
    void 版本判定() {
        assertThat(TradeStartupChecks.versionRejection("8.4.11")).isNull();
        assertThat(TradeStartupChecks.versionRejection("8.0.0")).isNull();
        assertThat(TradeStartupChecks.versionRejection("8.0.36-0ubuntu0.22.04.1")).isNull();
        assertThat(TradeStartupChecks.versionRejection("9.1.0")).isNull();
        assertThat(TradeStartupChecks.versionRejection("8.0.11-TiDB-v7.5.1")).as("TiDB 放行").isNull();
        assertThat(TradeStartupChecks.versionRejection("5.7.44-log")).contains("低于 8.0.0");
        assertThat(TradeStartupChecks.versionRejection("10.11.6-MariaDB")).contains("MariaDB");
        assertThat(TradeStartupChecks.versionRejection("8.0")).contains("无法");
        assertThat(TradeStartupChecks.versionRejection("")).contains("无法");
        assertThat(TradeStartupChecks.versionRejection(null)).contains("无法");
    }

    @Test
    void 会话参数判定() {
        String strict = "ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE";
        assertThat(TradeStartupChecks.rejection(session("8.4.11", "READ-COMMITTED", strict))).isNull();
        assertThat(TradeStartupChecks.rejection(session("8.4.11", "READ-COMMITTED", "STRICT_TRANS_TABLES"))).isNull();
        assertThat(TradeStartupChecks.rejection(session("8.4.11", "REPEATABLE-READ", strict))).contains("READ-COMMITTED");
        assertThat(TradeStartupChecks.rejection(session("8.4.11", null, strict))).contains("READ-COMMITTED");
        assertThat(TradeStartupChecks.rejection(session("8.4.11", "READ-COMMITTED", "STRICT_ALL_TABLES"))).contains("STRICT_TRANS_TABLES");
        assertThat(TradeStartupChecks.rejection(session("8.4.11", "READ-COMMITTED", ""))).contains("STRICT_TRANS_TABLES");
        assertThat(TradeStartupChecks.rejection(session("5.7.1", "READ-COMMITTED", strict))).contains("低于");
    }

    @Test
    void 元数据锁等待_只在MySQL档判_不是约定值要告警() {
        String strict = "STRICT_TRANS_TABLES";
        assertThat(TradeStartupChecks.metadataLockWaitOffContract(
                new Session("8.4.11", "READ-COMMITTED", strict, 2, 2))).as("约定值").isFalse();
        assertThat(TradeStartupChecks.metadataLockWaitOffContract(
                new Session("8.4.11", "READ-COMMITTED", strict, 2, 31_536_000))).as("连接串漏了：MySQL 缺省一年").isTrue();
        assertThat(TradeStartupChecks.metadataLockWaitOffContract(
                new Session("8.4.11", "READ-COMMITTED", strict, 2, 1))).as("比约定值短也算偏离").isTrue();
        assertThat(TradeStartupChecks.metadataLockWaitOffContract(
                new Session("8.0.11-TiDB-v7.5.1", "READ-COMMITTED", strict, 2, 31_536_000))).as("TiDB 档只接受不生效，不判").isFalse();
        assertThat(TradeStartupChecks.rejection(new Session("8.4.11", "READ-COMMITTED", strict, 2, 31_536_000)))
                .as("只告警不拒启").isNull();
    }

    @Test
    @EnabledIfSystemProperty(named = "game.it.mysql", matches = ".+")
    void 真MySQL_生产同口径的连接通过检查() throws Exception {
        try (TradeMysqlFixture fixture = TradeMysqlFixture.create()) {
            Session session = TradeStartupChecks.check(fixture.dataSource, Duration.ofSeconds(5));

            assertThat(session.isolation()).isEqualTo("READ-COMMITTED");
            assertThat(session.sqlMode()).contains("STRICT_TRANS_TABLES");
            assertThat(session.lockWaitTimeoutSeconds()).isEqualTo(2);
            if (!TradeStartupChecks.isTidb(session.version())) {
                // TiDB 档（-Dgame.it.require-tidb）这一项只接受不生效，读得出来即可，不核对值
                assertThat(session.metadataLockWaitTimeoutSeconds()).as("连接串的 lock_wait_timeout 生效").isEqualTo(2);
            }
            assertThat(TradeStartupChecks.metadataLockWaitOffContract(session)).isFalse();
            assertThat(TradeStartupChecks.versionRejection(session.version())).isNull();
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "game.it.mysql", matches = ".+")
    void 真MySQL_会话不是RC就拒启() throws Exception {
        try (TradeMysqlFixture fixture = TradeMysqlFixture.create();
             com.alibaba.druid.pool.DruidDataSource rr = new com.alibaba.druid.pool.DruidDataSource()) {
            rr.setUrl(TradeMysqlFixture.BASE_URL + "/" + fixture.database + TradeMysqlFixture.PARAMS
                    .replace("transaction_isolation='READ-COMMITTED'", "transaction_isolation='REPEATABLE-READ'"));
            rr.setUsername(TradeMysqlFixture.USER);
            rr.setPassword(TradeMysqlFixture.PASSWORD);

            assertThatThrownBy(() -> TradeStartupChecks.check(rr, Duration.ofSeconds(5)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("READ-COMMITTED");
        }
    }
}
