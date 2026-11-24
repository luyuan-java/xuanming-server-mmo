package com.game.scenemanager.world;

import com.game.discovery.world.WorldChannelDirectoryStore;
import com.game.discovery.world.WorldChannelStore;
import com.game.proto.SceneChannelDirectory;
import com.game.scenemanager.SceneNodeSource;
import com.game.scenemanager.world.WorldChannelMetrics.DirectoryPublishResult;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 独立于扩缩容/规划节拍的只读目录发布；每次只读已提交计划，发布脚本再核对令牌与版本。 */
public final class ChannelDirectoryPublisher {
    private static final Logger log = LoggerFactory.getLogger(ChannelDirectoryPublisher.class);
    private final WorldChannelStore plans;
    private final WorldChannelDirectoryStore directories;
    private final SceneNodeSource nodes;
    private final ChannelSwitchProperties props;
    private final WorldChannelProperties world;
    private final List<Integer> configs;
    private final Supplier<Set<Integer>> ledZones;
    private final String token;
    private final LongSupplier millis;
    private final WorldChannelMetrics metrics;
    private final MysteryMerchantScheduler mysteryMerchant;

    public ChannelDirectoryPublisher(WorldChannelStore plans, WorldChannelDirectoryStore directories,
                                     SceneNodeSource nodes, ChannelSwitchProperties props, WorldChannelProperties world,
                                     List<Integer> configs, Supplier<Set<Integer>> ledZones, String token, LongSupplier millis,
                                     WorldChannelMetrics metrics) {
        this(plans, directories, nodes, props, world, configs, ledZones, token, millis, metrics, null);
    }

    public ChannelDirectoryPublisher(WorldChannelStore plans, WorldChannelDirectoryStore directories,
                                     SceneNodeSource nodes, ChannelSwitchProperties props, WorldChannelProperties world,
                                     List<Integer> configs, Supplier<Set<Integer>> ledZones, String token, LongSupplier millis,
                                     WorldChannelMetrics metrics, MysteryMerchantScheduler mysteryMerchant) {
        this.plans = plans;
        this.directories = directories;
        this.nodes = nodes;
        this.props = props;
        this.world = world;
        this.configs = List.copyOf(configs);
        this.ledZones = ledZones;
        this.token = token;
        this.millis = millis;
        this.metrics = metrics;
        this.mysteryMerchant = mysteryMerchant;
    }

    public Duration interval() { return props.refreshInterval(); }

    public void publishOnce() {
        if (interval().isZero()) return;
        for (int zone : ledZones.get()) {
            try {
                var plan = plans.readPlan(zone);
                var directory = nodes.list(zone);
                long nowMs = millis.getAsLong();
                Duration ttl = props.directoryTtl(world.tick());
                boolean hasChannels = false;
                boolean fenced = false;
                Map<Integer, SceneChannelDirectory> byConfig = new LinkedHashMap<>();
                for (int config : configs) {
                    var model = ChannelDirectoryBuilder.build(zone, config, plan.channels(), directory, props, world, nowMs);
                    hasChannels |= model.getChannelsCount() > 0;
                    // 成功读到确实为空的计划会发布空目录，使旧线路及时消失；失败/缺号绝不伪装成空目录。
                    if (!directories.publish(zone, token, plan.version(), config, model.toByteArray(), ttl)) {
                        fenced = true;
                        break; // 任期/版本已变，本轮剩余地图也不能用这份快照发布。
                    }
                    byConfig.put(config, model);
                }
                if (!fenced) {
                    var overview = WorldMapOverviewBuilder.build(zone, world.mapPlay(), byConfig, nowMs);
                    if (overview.getMapsCount() > 0
                            && !directories.publishOverview(zone, token, plan.version(), overview.toByteArray(), ttl)) {
                        fenced = true;
                    }
                    if (!fenced && mysteryMerchant != null) {
                        mysteryMerchant.tick(zone, byConfig, nowMs);
                    }
                }
                metrics.directoryPublish(fenced ? DirectoryPublishResult.FENCED
                        : hasChannels ? DirectoryPublishResult.OK : DirectoryPublishResult.EMPTY);
            } catch (RuntimeException failure) {
                // 不把读取失败变成空目录，也不清持久线号；旧读模型自然过期。
                log.warn("分线目录发布失败，等待下个周期 zone={}: {}", Integer.toUnsignedString(zone), failure.toString());
                metrics.directoryPublish(DirectoryPublishResult.ERROR);
            }
        }
    }
}
