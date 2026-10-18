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
                .withBean(DataSource.class, () -> {
                    var pool = new DruidDataSource();
                    pool.setUrl("jdbc:h2:mem:player_sql_authority_wiring;MODE=MySQL");
                    pool.setMaxActive(1);
                    pool.setValidationQuery("SELECT 1");
                    pool.setConnectionInitSqls(java.util.List.of("CREATE ALIAS IF NOT EXISTS VERSION FOR "
                            + "\"com.game.player.store.PlayerSqlAuthorityAutoConfigurationTest.legacySqlVersion\""));
                    return pool;
                })
                .run(context -> {
                    assertThat(context).hasNotFailed().hasBean("playerSqlAuthority")
                            .hasSingleBean(StrongTiDbConnectionPolicy.class).doesNotHaveBean(PlayerStore.class);
                    assertThat(((DruidDataSource) context.getBean(DataSource.class)).isInited()).isTrue();
                });
    }
}
