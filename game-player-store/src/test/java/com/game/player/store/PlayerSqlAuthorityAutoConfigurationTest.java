package com.game.player.store;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.common.deadline.Deadline;
import com.game.discovery.sql.StrongTiDbConnectionPolicy;
import java.sql.Connection;
import java.sql.SQLTimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.env.MockEnvironment;

public class PlayerSqlAuthorityAutoConfigurationTest {
    /** H2不提供MySQL的VERSION函数；仅供明确关闭TiDB要求的装配夹具确认库类型。 */
    public static String legacySqlVersion() { return "8.0-test-fixture"; }

    @Test
    void 生产与未知模式默认强读_不能用配置关闭() {
        assertThat(PlayerSqlAuthorityAutoConfiguration.requireTiDb(new MockEnvironment())).isTrue();
        for (String mode : new String[] {"prod", "production", "typo", ""}) {
            assertThatThrownBy(() -> PlayerSqlAuthorityAutoConfiguration.requireTiDb(new MockEnvironment()
                    .withProperty("game.run-mode", mode).withProperty("game.player-store.require-tidb", "false")))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void 只有明确开发测试才能使用旧库() {
        for (String mode : new String[] {"dev", "test"}) {
            assertThat(PlayerSqlAuthorityAutoConfiguration.requireTiDb(new MockEnvironment()
                    .withProperty("game.run-mode", mode).withProperty("game.player-store.require-tidb", "false"))).isFalse();
        }
    }

    @Test
    void 多领域策略只给物理连接安装一份同样的初始化语句() {
        try (var pool = new DruidDataSource()) {
            new StrongTiDbConnectionPolicy("mail", true).postProcessBeforeInitialization(pool, "pool");
            PlayerSqlAuthorityAutoConfiguration.playerStoreConnectionPolicy(new MockEnvironment())
                    .postProcessBeforeInitialization(pool, "pool");
            assertThat(pool.getConnectionInitSqls()).containsExactly(StrongTiDbConnectionPolicy.SESSION_SQL);
        }
    }

    @Test
    void 强读核对不得在共享预算已过期后再发SQL() throws Exception {
        var connection = mock(Connection.class);
        assertThatThrownBy(() -> StrongTiDbConnectionPolicy.verify(connection, true, Deadline.after(-1)))
                .isInstanceOf(SQLTimeoutException.class);
        verify(connection, never()).createStatement();
    }

    @Test
    void 手动数据源未装配玩家Mapper时仍安装权威库门禁() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PlayerSqlAuthorityAutoConfiguration.class))
                .withPropertyValues("game.run-mode=test", "game.player-store.require-tidb=false")
                .withBean(DataSource.class, () -> authorityDataSource(true))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasBean("playerSqlAuthority")
                            .hasSingleBean(StrongTiDbConnectionPolicy.class).doesNotHaveBean(PlayerStore.class);
                    assertThat(((DruidDataSource) context.getBean(DataSource.class)).isInited()).isTrue();
                });
    }

    @Test
    void 手动数据源缺少正式最终存盘表时必须拒绝启动() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PlayerSqlAuthorityAutoConfiguration.class))
                .withPropertyValues("game.run-mode=test", "game.player-store.require-tidb=false")
                .withBean(DataSource.class, () -> authorityDataSource(false))
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable cause = context.getStartupFailure();
                    while (cause.getCause() != null) cause = cause.getCause();
                    assertThat(cause).isInstanceOf(java.sql.SQLException.class);
                    assertThat(cause.getMessage()).containsIgnoringCase("player_final_save");
                });
    }

    private static DruidDataSource authorityDataSource(boolean initializeSchema) {
        var pool = new DruidDataSource();
        pool.setUrl("jdbc:h2:mem:player_sql_authority_" + java.util.UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        pool.setMaxActive(1);
        pool.setValidationQuery("SELECT 1");
        pool.setConnectionInitSqls(java.util.List.of("CREATE ALIAS IF NOT EXISTS VERSION FOR "
                + "\"com.game.player.store.PlayerSqlAuthorityAutoConfigurationTest.legacySqlVersion\""));
        try {
            // 正向装配使用唯一正式DDL；负向保留空库，验证独立于Mapper的拒启门禁。
            if (initializeSchema) {
                new ResourceDatabasePopulator(new ClassPathResource("db/game-player-schema.sql")).execute(pool);
            }
            return pool;
        } catch (RuntimeException | Error failure) {
            pool.close();
            throw failure;
        }
    }

    @Test
    void M15旧玩家结构或非二进制收据必须拒绝启动() throws Exception {
        for (String change : java.util.List.of("ALTER TABLE player DROP COLUMN owner_revision",
                "ALTER TABLE player ALTER COLUMN trade_listing_id INT",
                "ALTER TABLE player_trade_freeze ALTER COLUMN seller_account VARCHAR(64)",
                "ALTER TABLE player_trade_transfer ALTER COLUMN request_digest BINARY(16)",
                "ALTER TABLE player_trade_freeze ALTER COLUMN cancel_requested INT",
                "ALTER TABLE player_trade_drain ALTER COLUMN source_instance VARCHAR(64)",
                "ALTER TABLE player_trade_drain ALTER COLUMN payload_digest SET NOT NULL",
                "DROP INDEX idx_trade_drain_epoch",
                "DROP TABLE player_trade_drain",
                "DROP TABLE player_trade_transfer")) {
            try (var source = authorityDataSource(true)) {
                var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
                try (var connection = source.getConnection()) { PlayerSqlAuthorityAutoConfiguration.verifyTradeSchema(connection); }
                jdbc.execute(change);
                assertThatThrownBy(() -> {
                    try (var connection = source.getConnection()) { PlayerSqlAuthorityAutoConfiguration.verifyTradeSchema(connection); }
                }).isInstanceOfAny(java.sql.SQLException.class, IllegalStateException.class);
            }
        }
    }

    @Test
    void M15永久幂等收据不能缺主键() throws Exception {
        try (var source = authorityDataSource(true); var connection = source.getConnection()) {
            String constraint = null;
            try (var keys = connection.getMetaData().getPrimaryKeys(connection.getCatalog(), connection.getSchema(), "player_trade_transfer")) {
                if (keys.next()) constraint = keys.getString("PK_NAME");
            }
            assertThat(constraint).isNotNull().matches("[A-Za-z0-9_]+");
            try (var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE player_trade_transfer DROP CONSTRAINT " + constraint);
            }
            assertThatThrownBy(() -> PlayerSqlAuthorityAutoConfiguration.verifyTradeSchema(connection))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("主键");
        }
    }
}
