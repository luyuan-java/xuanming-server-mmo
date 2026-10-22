package com.game.chat;

import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.chat.store.JdbcChatStore;
import com.game.common.deadline.Deadline;
import com.game.discovery.RedisAutoConfiguration;
import com.game.discovery.killswitch.KillSwitchAutoConfiguration;
import com.game.discovery.namespace.GameNamespaceAutoConfiguration;
import com.game.discovery.sql.StrongTiDbConnectionPolicy;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.core.io.ClassPathResource;

class ChatSqlConfigurationTest {
    @Test void 默认强TiDB_生产不可关_逐物理连接采用既有强会话策略() {
        assertTrue(ChatSqlConfiguration.requireTiDb(new MockEnvironment()));
        assertThrows(IllegalStateException.class, () -> ChatSqlConfiguration.requireTiDb(new MockEnvironment()
                .withProperty("game.run-mode", "prod").withProperty("game.chat.require-tidb", "false")));
        try (var pool = new DruidDataSource()) {
            ChatSqlConfiguration.chatConnectionPolicy(new MockEnvironment()).postProcessBeforeInitialization(pool, "source");
            assertTrue(pool.getConnectionInitSqls().contains(StrongTiDbConnectionPolicy.SESSION_SQL));
        }
    }

    @Test void 缺表或可空原账号列拒绝启动_不自动安装聊天DDL() throws Exception {
        try (var f = new ChatSqlFixture(JdbcChatAuthorityTest.rules())) {
            f.sql.execute("ALTER TABLE chat_send_authority ALTER COLUMN owner_account DROP NOT NULL");
            try (var c = f.source.getConnection()) { assertThrows(SQLException.class, () -> ChatSqlConfiguration.verifySchema(c)); }
            f.sql.execute("DROP TABLE chat_send_authority");
            try (var c = f.source.getConnection()) { assertThrows(SQLException.class, () -> ChatSqlConfiguration.verifySchema(c)); }
        }
    }

    @Test void 敏感词及窗口策略不能被后启动副本覆盖() throws Exception {
        var rules = JdbcChatAuthorityTest.rules();
        try (var f = new ChatSqlFixture(rules)) {
            f.player(7, "A", 7);
            var changedWords = new ChatProperties(rules.maxContentBytes(), rules.rateLimitPerSecond(), rules.historyMaxEntries(),
                    rules.historyTtl(), rules.historyDefaultLimit(), rules.historyMaxLimit(), rules.requestIdTtl(),
                    rules.requestBudget(), List.of("不同词"));
            for (var changed : List.of(changedWords, JdbcChatAuthorityTest.rules(5, 199, 604800), JdbcChatAuthorityTest.rules(5, 200, 10))) {
                var other = new JdbcChatStore(f.source, f.authority, changed);
                assertThrows(SQLException.class, other::initializePolicy);
                assertThrows(Exception.class, () -> JdbcChatAuthorityTest.result(other.pull(JdbcChatAuthorityTest.actor("A", 7),
                        0, 20, Deadline.after(3500))));
            }
            assertEquals(0, JdbcChatAuthorityTest.count(f));
        }
    }

    @Test void 正式默认不装配Redis热关停_命名空间键构造不提前建立Redis连接() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        assertEquals("false", yaml.getObject().getProperty("game.killswitch.enabled"));
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class,
                KillSwitchAutoConfiguration.class, GameNamespaceAutoConfiguration.class))
                .withPropertyValues("game.killswitch.enabled=false").run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFalse(context.getBeanFactory().containsSingleton("redissonClient"));
                    assertFalse(context.containsBean("redisKillSwitchSync"));
                    assertNotNull(context.getBean(com.game.discovery.RedisKeys.class));
                });
    }
}
