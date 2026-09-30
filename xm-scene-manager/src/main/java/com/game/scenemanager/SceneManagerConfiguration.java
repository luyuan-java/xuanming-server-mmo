package com.game.scenemanager;

import com.game.common.token.DubboCallAuth;
import com.game.table.AllTable;
import com.game.table.WorldTableManager;
import java.nio.file.Files;
import java.nio.file.Path;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配：配置表 → {@link WorldSceneConfigs}；Redis 节点目录 → {@link SceneNodeSource}；两者 → {@link SceneAssigner}。
 * {@link RedissonClient} 由 xm-discovery 的自动配置提供（{@code xm.redis.*}）。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SceneManagerProperties.class)
public class SceneManagerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SceneManagerConfiguration.class);

    /**
     * @param tableDir 配置表目录（导表器产物 .pb）。默认相对路径 {@code config-data/tables}：进程约定从仓库根目录启动；
     *                 从别处启动时显式设 {@code xm.table-dir}（环境变量 {@code XM_TABLE_DIR}）。
     */
    @Bean
    public WorldSceneConfigs worldSceneConfigs(@Value("${xm.table-dir:config-data/tables}") String tableDir,
                                               SceneManagerProperties props) {
        WorldSceneConfigs configs = loadWorldSceneConfigs(Path.of(tableDir), props.defaultWorldConfigId());
        log.info("世界地图已加载 default={} all={}", configs.defaultConfigId(), configs.worldConfigIds());
        return configs;
    }

    @Bean
    public SceneNodeSource sceneNodeSource(RedissonClient redis) {
        return new RedisSceneNodeSource(redis);
    }

    @Bean
    public SceneAssigner sceneAssigner(SceneNodeSource source, WorldSceneConfigs worldConfigs) {
        return new SceneAssigner(source, worldConfigs);
    }

    /**
     * Dubbo 调用鉴权密钥的启动检查（fail-fast，报错信息明确）。真正的校验在 xm-api 的 Dubbo 提供方过滤器里，
     * 它自己也从同一个环境变量读密钥、缺失即实例化失败。
     */
    @Bean
    public DubboCallAuth dubboCallAuth() {
        return DubboCallAuth.requireFromEnvValue(System.getenv(DubboCallAuth.SECRET_ENV));
    }

    /** 加载全部配置表（顺带校验表目录完整），再从 World 表构建世界地图集合。任何问题都在启动时失败。 */
    static WorldSceneConfigs loadWorldSceneConfigs(Path tableDir, Integer overrideDefault) {
        Path dir = tableDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("配置表目录不存在: " + dir + "（进程需从仓库根目录启动，或设置 xm.table-dir）");
        }
        try {
            AllTable.loadTables(dir.toString(), true);
        } catch (Exception e) {
            throw new IllegalStateException("加载配置表失败: " + dir, e);
        }
        return WorldSceneConfigs.fromWorldTable(WorldTableManager.getInstance().findAll(), overrideDefault);
    }
}
