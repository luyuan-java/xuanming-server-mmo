package com.game.scenemanager.world;

import com.game.discovery.world.WorldChannelStore;
import com.game.discovery.world.WorldLeaderLock;
import com.game.scenemanager.SceneIdAllocator;
import com.game.scenemanager.SceneNodeSource;
import com.game.scenemanager.WorldSceneConfigs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 控制面的装配与生命周期（scene-channels-spec §4.4、§4.5）：{@code leader-eligible = false} 时什么也不起（本副本只做数据面分配与实例取号）；
 * 否则建规划器与协调者 → 起两条控制面线程。
 *
 * <p>scene_id 发号租约<b>不归控制面</b>（批次 5.3 R5，dungeon-mirror-spec §6.6）：由独立 bean {@link SceneIdAllocator} 在<b>每个</b>副本上申领
 * （不竞选的副本也要能给镜像 / 副本实例发号），控制面只借用它。租约无效（续期滞后）期间规划器跳过全部新建（fail-closed）；
 * 租约确认丢失 → 协调者放掉全部 zone 锁、停止竞选，等人工重启（对应基线 scene_manager_service.go:250-260 的「先 Fence 再让位」，Java 不自杀）。
 * {@link #close()}：停线程、放锁；租约由 {@link SceneIdAllocator} 自己的 bean 生命周期归还（它被本 bean 依赖，后销毁）。
 */
public final class WorldChannelControlPlane implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WorldChannelControlPlane.class);

    private final SceneIdAllocator ids;
    private final WorldChannelCoordinator coordinator;
    private final WorldChannelRunner runner;

    private WorldChannelControlPlane(SceneIdAllocator ids, WorldChannelCoordinator coordinator, WorldChannelRunner runner) {
        this.ids = ids;
        this.coordinator = coordinator;
        this.runner = runner;
    }

    /** 按配置起控制面；不参与竞选时返回一个空壳（{@link #coordinator()} 为 null）。 */
    public static WorldChannelControlPlane start(WorldChannelStore store, SceneNodeSource source, WorldSceneConfigs worldConfigs,
                                                 WorldChannelProperties props, WorldChannelMetrics metrics,
                                                 SceneIdAllocator ids) {
        if (!props.leaderEligible()) {
            log.info("xm.scene-manager.world.leader-eligible=false：本副本不竞选频道计划领导者，只做数据面分配与实例取号（发号 worker={}）",
                    ids.worker());
            return new WorldChannelControlPlane(ids, null, null);
        }
        WorldChannelPlanner planner = WorldChannelPlanner.fromDirectory(props, worldConfigs.orderedConfigIds(), ids);
        String token = WorldLeaderLock.newToken("scene-manager-" + ProcessHandle.current().pid());
        WorldChannelCoordinator coordinator = new WorldChannelCoordinator(store, source, planner, props, metrics,
                NodeAvailability.ALL, token, System::nanoTime);
        ids.onLost(coordinator::onIdLeaseLost);
        log.info("主世界频道控制面 scene_id 发号 worker={} 覆盖模式={} 自动扩缩容={} 种子={} 按图覆盖={}", ids.worker(), props.coverage(),
                props.autoscale().enabled(), props.channelCount(), props.channelCountByConfig());
        WorldChannelRunner runner = new WorldChannelRunner(coordinator, props.tick(), props.leaderLockTtl().dividedBy(3));
        return new WorldChannelControlPlane(ids, coordinator, runner);
    }

    /** 协调者；不参与竞选时为 null。 */
    public WorldChannelCoordinator coordinator() {
        return coordinator;
    }

    /** scene_id 雪花的 worker（发号租约号；不论是否竞选，每个副本都有）。 */
    public int idWorker() {
        return ids.worker();
    }

    @Override
    public void close() {
        if (runner != null) {
            runner.close();
        }
    }
}
