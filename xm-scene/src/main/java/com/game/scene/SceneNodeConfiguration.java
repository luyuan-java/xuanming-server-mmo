package com.game.scene;

import com.game.common.token.NodeLinkAuth;
import com.game.contract.MessageIdRegistry;
import com.game.player.store.PlayerStore;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.world.GeneratedSceneTables;
import com.game.scene.world.SceneTables;
import com.game.table.AllTable;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Files;
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
@EnableConfigurationProperties(SceneNodeProperties.class)
public class SceneNodeConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SceneNodeConfiguration.class);

    @Bean
    public MessageIdRegistry messageIdRegistry() {
        return MessageIdRegistry.loadFromClasspath();
    }

    @Bean
    public SceneTables sceneTables(SceneNodeProperties props) throws Exception {
        Path dir = Path.of(props.tableDir()).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("配置表目录不存在: " + dir
                    + "（进程需从仓库根目录启动，或把 xm.table-dir 配成绝对路径）");
        }
        AllTable.loadTables(dir.toString(), true);
        log.info("配置表已加载 dir={}", dir);
        return GeneratedSceneTables.fromLoadedTables();
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
                               MessageIdRegistry registry, SceneTables tables, NodeLinkAuth nodeLinkAuth,
                               SceneMetrics sceneMetrics) {
        return new SceneNode(props, redis, playerStore, registry, tables, nodeLinkAuth, sceneMetrics);
    }
}
