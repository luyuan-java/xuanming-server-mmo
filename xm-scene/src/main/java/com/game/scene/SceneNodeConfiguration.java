package com.game.scene;

import com.game.audit.AuditProperties;
import com.game.common.token.NodeLinkAuth;
import com.game.contract.MessageIdRegistry;
import com.game.player.store.PlayerStore;
import com.game.scene.attribute.AttributeTables;
import com.game.scene.bag.BagTables;
import com.game.scene.mission.MissionTables;
import com.game.scene.skill.SkillTables;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.world.ConfigSceneTables;
import com.game.scene.world.SceneTables;
import com.game.table.ConfigTables;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Spring 装配：只提供不可变的共享件（消息号注册表、配置表）与 {@link SceneNode}；
 * 线程与连接全部由 SceneNode 在生命周期里创建和释放。
 * {@link RedissonClient} 来自 xm-discovery，{@link PlayerStore} 来自 xm-player-store 的自动装配。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SceneNodeProperties.class, AuditProperties.class})
public class SceneNodeConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SceneNodeConfiguration.class);

    @Bean
    public MessageIdRegistry messageIdRegistry() {
        return MessageIdRegistry.loadFromClasspath();
    }

    /** 配置表快照（不可变）；各玩法的视图都从这一份构建，保证同一次加载。 */
    @Bean
    public ConfigTables configTables(SceneNodeProperties props) {
        ConfigTables tables = ConfigTables.load(Path.of(props.tableDir()));
        log.info("配置表已加载 dir={} 行数={}", Path.of(props.tableDir()).toAbsolutePath().normalize(), tables.rowCounts());
        return tables;
    }

    @Bean
    public SceneTables sceneTables(ConfigTables tables) {
        return ConfigSceneTables.from(tables);
    }

    @Bean
    public AttributeTables attributeTables(ConfigTables tables) {
        return AttributeTables.from(tables);
    }

    @Bean
    public BagTables bagTables(ConfigTables tables) {
        return BagTables.from(tables);
    }

    @Bean
    public MissionTables missionTables(ConfigTables tables) {
        return MissionTables.from(tables);
    }

    @Bean
    public SkillTables skillTables(ConfigTables tables) {
        return SkillTables.from(tables);
    }

    /** gate 链路握手鉴权。密钥只从环境变量 {@code XM_NODE_LINK_SECRET} 读，缺失即启动失败（不允许无鉴权的链路）。 */
    @Bean
    public NodeLinkAuth nodeLinkAuth(Environment environment) {
        return NodeLinkAuth.requireFromEnvValue(environment.getProperty(NodeLinkAuth.SECRET_ENV));
    }

    /** scene 指标，注册到 actuator 提供的注册表（Prometheus 导出，见 architecture.md §11）。 */
    @Bean
    public SceneMetrics sceneMetrics(MeterRegistry meterRegistry) {
        return new SceneMetrics(meterRegistry);
    }

    @Bean
    public SceneNode sceneNode(SceneNodeProperties props, RedissonClient redis, PlayerStore playerStore,
                               MessageIdRegistry registry, SceneTables tables, AttributeTables attributeTables,
                               BagTables bagTables, MissionTables missionTables, SkillTables skillTables,
                               NodeLinkAuth nodeLinkAuth,
                               SceneMetrics sceneMetrics, AuditProperties audit) {
        return new SceneNode(props, redis, playerStore, registry, tables, attributeTables, bagTables, missionTables,
                skillTables, nodeLinkAuth, sceneMetrics, audit);
    }
}
