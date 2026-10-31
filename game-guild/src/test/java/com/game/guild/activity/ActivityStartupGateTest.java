package com.game.guild.activity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.audit.AuditBrokerUnavailableException;
import com.game.audit.BattleResultTopics;
import com.game.audit.TopicAdmin;
import com.game.audit.TopicSpec;
import com.game.common.GameNamespace;
import com.game.common.KafkaNames;
import com.game.common.sql.AuthorityDataSource;
import com.game.discovery.RedisKeys;
import com.game.discovery.presence.PlayerPresenceDirectory;
import com.game.discovery.sql.StrongTiDbConnectionPolicy;
import com.game.guild.store.GuildActivityStore;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

/** 沿正式Spring装配验证：SQL策略和结果topic未过门禁时，活动处理器不能创建。 */
class ActivityStartupGateTest {
    private static final GameNamespace NS = GameNamespace.of("guildgate");
    private static final KafkaNames NAMES = new KafkaNames(NS);

    private static ApplicationContextRunner configuration(Set<String> keep) {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(ActivityConfiguration.class).withInitializer(context -> {
            context.addBeanFactoryPostProcessor(factory -> {
                var registry = (BeanDefinitionRegistry) factory;
                for (var method : ActivityConfiguration.class.getDeclaredMethods()) {
                    if (method.isAnnotationPresent(Bean.class) && !keep.contains(method.getName())
                            && registry.containsBeanDefinition(method.getName())) {
                        registry.removeBeanDefinition(method.getName());
                    }
                }
            });
        });
    }

    private static ApplicationContextRunner sqlPolicy() {
        return configuration(Set.of("guildActivityConnectionPolicy"))
                .withBean(DataSource.class, DruidDataSource::new);
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "production", "pre", "pro", "unknown", ""})
    void 生产及未知模式不能关闭TiDB强读(String mode) {
        sqlPolicy().withPropertyValues("game.run-mode=" + mode, "game.guild.activity.require-tidb=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasStackTraceContaining("生产帮会");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test"})
    void 明确本地模式允许旧MySQL(String mode) {
        sqlPolicy().withPropertyValues("game.run-mode=" + mode, "game.guild.activity.require-tidb=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(((DruidDataSource) context.getBean(DataSource.class)).getConnectionInitSqls())
                            .containsExactly(AuthorityDataSource.GUILD_FAIR_LOCKING_OFF_SQL);
                });
    }

    @Test
    void 缺少配置默认安装TiDB强读而非关闭() {
        sqlPolicy().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(((DruidDataSource) context.getBean(DataSource.class)).getConnectionInitSqls())
                    .containsExactlyInAnyOrder(StrongTiDbConnectionPolicy.SESSION_SQL,
                            AuthorityDataSource.GUILD_FAIR_LOCKING_OFF_SQL);
        });
    }

    private static final class FakeAdmin implements TopicAdmin {
        boolean exists = true, unavailable;
        int replicas = 3, partitions = 3;
        String minIsr = "2", unclean = "false";
        final AtomicInteger opened = new AtomicInteger(), closed = new AtomicInteger(), mutations = new AtomicInteger();
        TopicAdmin open() {
            opened.incrementAndGet();
            return this;
        }
        @Override public Map<String, Integer> partitionCounts(Collection<String> topics, Duration timeout) {
            if (unavailable) throw new AuditBrokerUnavailableException("测试broker不可用", null);
            return exists ? Map.of(new BattleResultTopics(NAMES).name(1), partitions) : Map.of();
        }
        @Override public Map<String, Integer> minimumReplicaCounts(Collection<String> topics, Duration timeout) {
            return replicas < 0 ? Map.of() : Map.of(new BattleResultTopics(NAMES).name(1), replicas);
        }
        @Override public Map<String, String> configs(String topic, Collection<String> keys, Duration timeout) {
            return Map.of("min.insync.replicas", minIsr, "unclean.leader.election.enable", unclean);
        }
        @Override public void create(Collection<TopicSpec> specs, short replicas, Duration timeout) {
            mutations.incrementAndGet();
        }
        @Override public void alterConfigs(String topic, Map<String, String> configs, Duration timeout) {
            mutations.incrementAndGet();
        }
        @Override public void close() { closed.incrementAndGet(); }
    }

    @SuppressWarnings("rawtypes")
    private static ApplicationContextRunner activities(FakeAdmin admin) {
        return configuration(Set.of("guildActivityConnectionPolicy", "guildActivityService",
                                     "guildActivityResultAdmin", "guildActivityResultReady"))
                .withPropertyValues("game.guild.activity.enabled=true", "game.guild.activity.require-tidb=true",
                                    "game.run-mode=prod", "GLOBAL_MATCH_ENABLED=true")
                .withBean("guildActivityResultAdmin", Supplier.class, () -> (Supplier<TopicAdmin>) admin::open)
                .withBean(KafkaNames.class, () -> NAMES)
                .withBean(RedisKeys.class, () -> new RedisKeys(NS))
                .withBean("fixtureGuildStore", GuildActivityStore.class, () -> mock(GuildActivityStore.class))
                .withBean(PlayerPresenceDirectory.class, () -> mock(PlayerPresenceDirectory.class))
                .withBean(RedissonClient.class, () -> mock(RedissonClient.class))
                .withBean(GuildActivityService.Effects.class, () -> (guild, actor, players) -> {});
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "rf1", "unknown_replicas", "isr1", "unclean", "partitions", "unavailable"})
    void 结果topic未满足持久契约前不得创建活动处理器(String failure) {
        var admin = new FakeAdmin();
        switch (failure) {
            case "missing" -> admin.exists = false;
            case "rf1" -> admin.replicas = 1;
            case "unknown_replicas" -> admin.replicas = -1;
            case "isr1" -> admin.minIsr = "1";
            case "unclean" -> admin.unclean = "true";
            case "partitions" -> admin.partitions = 1;
            case "unavailable" -> admin.unavailable = true;
        }
        activities(admin).run(context -> assertThat(context).hasFailed());
        assertThat(admin.opened).hasValue(1);
        assertThat(admin.closed).hasValue(1);
        assertThat(admin.mutations).hasValue(0);
    }

    @Test
    void 严格结果topic同步通过后才开放活动且不抢建topic() {
        var admin = new FakeAdmin();
        admin.minIsr = "3";
        activities(admin).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(GuildActivityService.class);
            assertThat(admin.opened).hasValue(1);
            assertThat(admin.closed).hasValue(1);
        });
        assertThat(admin.mutations).hasValue(0);
    }

    @Test
    void 生产不能通过关闭全球匹配标记绕开持久结果门禁() {
        var admin = new FakeAdmin();
        activities(admin).withPropertyValues("GLOBAL_MATCH_ENABLED=false")
                .run(context -> assertThat(context).hasFailed());
        assertThat(admin.opened).hasValue(0);
    }

    @Test
    void 明确开发兼容模式仍同步验证旧结果主题但允许RF1() {
        var admin = new FakeAdmin();
        admin.replicas = 1;
        admin.minIsr = "1";
        activities(admin).withPropertyValues("game.run-mode=dev", "GLOBAL_MATCH_ENABLED=false")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(GuildActivityService.class));
        assertThat(admin.opened).hasValue(1);
        assertThat(admin.closed).hasValue(1);
        assertThat(admin.mutations).hasValue(0);
    }

    @Test
    void 关闭活动后仍可启动处理器但不访问Kafka() {
        var admin = new FakeAdmin();
        activities(admin).withPropertyValues("game.guild.activity.enabled=false").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(GuildActivityService.class);
            assertThat(admin.opened).hasValue(0);
        });
    }
}
