package com.game.scenemanager;

import com.game.common.token.DubboCallAuth;
import com.game.discovery.RedisKeys;
import com.game.discovery.world.WorldChannelStore;
import com.game.discovery.world.ChannelSwitchCooldown;
import com.game.scenemanager.world.ChannelSwitchProperties;
import com.game.scenemanager.world.WorldChannelProperties;
import com.game.table.ConfigTables;
import java.nio.file.Path;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配：配置表 → {@link WorldSceneConfigs}；Redis 节点目录 → {@link SceneNodeSource}；目录 + 软预占（{@code <ns>:world:*}）→
 * {@link ChannelSelector}；三者 → {@link SceneAssigner}（进游戏）与 {@link SwitchTargetSelector}（在线换图选跨节点目标，批次 5.2）；
 * 全服 scene_id 发号租约 → {@link SceneIdAllocator}（每个副本都申领）→ {@link InstanceIdIssuer}（镜像 / 副本实例取号，批次 5.3）。
 * 主世界频道的控制面在 {@code com.game.scenemanager.world.WorldChannelConfiguration}。
 * {@link RedissonClient}（{@code game.redis.*}）与 {@link RedisKeys}（部署命名空间 {@code game.namespace} 的键名）由 game-discovery 的自动配置提供。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SceneManagerProperties.class)
public class SceneManagerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SceneManagerConfiguration.class);

    /**
     * @param tableDir 配置表目录（导表器产物 .pb）。默认相对路径 {@code config-data/tables}：进程约定从仓库根目录启动；
     *                 从别处启动时显式设 {@code game.table-dir}（环境变量 {@code GAME_TABLE_DIR}）。
     */
    @Bean
    public WorldSceneConfigs worldSceneConfigs(@Value("${game.table-dir:config-data/tables}") String tableDir,
                                               SceneManagerProperties props) {
        WorldSceneConfigs configs = loadWorldSceneConfigs(Path.of(tableDir), props.defaultWorldConfigId());
        log.info("世界地图已加载 default={} all={}", configs.defaultConfigId(), configs.worldConfigIds());
        return configs;
    }

    @Bean
    public SceneNodeSource sceneNodeSource(RedissonClient redis, RedisKeys redisKeys) {
        return new RedisSceneNodeSource(redis, redisKeys);
    }

    /**
     * 选频道 + 软预占（批次 5.1，scene-channels-spec §4.11）。预占 TTL 已在 {@link WorldChannelProperties} 绑定时按 login 的归属夺取等待校验过（Q8）。
     */
    @Bean
    public ChannelSelector channelSelector(SceneNodeSource source, WorldChannelStore worldChannelStore,
                                           WorldChannelProperties worldProps) {
        log.info("进场软预占 TTL={}（0 = 关闭）", worldProps.reservationTtl());
        return new ChannelSelector(source, worldChannelStore, worldProps.reservationTtl());
    }

    @Bean
    public SceneAssigner sceneAssigner(SceneNodeSource source, WorldSceneConfigs worldConfigs, ChannelSelector channelSelector) {
        return new SceneAssigner(source, worldConfigs, channelSelector);
    }

    /** 在线换图选跨节点目标（批次 5.2，scene-handoff-spec §5.4）：与进游戏分配共用目录、世界地图集合与选频道 + 软预占。 */
    @Bean
    public SwitchTargetSelector switchTargetSelector(SceneNodeSource source, WorldSceneConfigs worldConfigs,
                                                     ChannelSelector channelSelector, WorldChannelStore plans,
                                                     ChannelSwitchCooldown cooldown, ChannelSwitchProperties switchProps,
                                                     WorldChannelProperties worldProps) {
        return new SwitchTargetSelector(source, worldConfigs, channelSelector,
                new ClientChannelPick(plans, cooldown, switchProps, worldProps));
    }

    /**
     * 全服 scene_id 发号器（批次 5.3 R5）：<b>每个副本都申领</b>发号租约，不论 {@code leader-eligible}——主世界频道（控制面，只在领导者上用）
     * 与镜像 / 副本实例取号（{@link InstanceIdIssuer}，任一副本都可能被调到）共用。启动时占不到号即启动失败；停服时最后还租约
     * （控制面与提供方都依赖它，先于它销毁）。
     */
    @Bean(destroyMethod = "close")
    public SceneIdAllocator sceneIdAllocator(RedissonClient redis, RedisKeys redisKeys) {
        return SceneIdAllocator.acquire(redis, redisKeys);
    }

    /**
     * 镜像 / 副本实例取号：放置恒为发起节点；有界读取单条目录，疏散或不存在时不发新号。
     */
    @Bean
    public InstanceIdIssuer instanceIdIssuer(SceneIdAllocator sceneIdAllocator, SceneNodeSource source) {
        return new InstanceIdIssuer(sceneIdAllocator, new com.game.scenemanager.world.DirectoryNodeAvailability(source));
    }

    /**
     * Dubbo 调用鉴权密钥的启动检查（fail-fast，报错信息明确）。真正的校验在 game-api 的 Dubbo 提供方过滤器里，
     * 它自己也从同一个环境变量读密钥、缺失即实例化失败。
     */
    @Bean
    public DubboCallAuth dubboCallAuth() {
        return DubboCallAuth.requireFromEnvValue(System.getenv(DubboCallAuth.SECRET_ENV));
    }

    /**
     * 加载并整体校验全部配置表（manifest、sha256、外键，见 {@link ConfigTables#load}），再从 World 表构建世界地图集合。
     * 任何问题都在启动时失败。
     */
    static WorldSceneConfigs loadWorldSceneConfigs(Path tableDir, Integer overrideDefault) {
        return WorldSceneConfigs.fromWorldTable(ConfigTables.load(tableDir).world().all(), overrideDefault);
    }
}
