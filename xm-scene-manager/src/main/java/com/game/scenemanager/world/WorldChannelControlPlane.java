package com.game.scenemanager.world;

import com.game.common.id.LeaseGatedSnowflake;
import com.game.common.id.Snowflake;
import com.game.discovery.NodeIdLease;
import com.game.discovery.world.WorldChannelStore;
import com.game.discovery.world.WorldChannels;
import com.game.discovery.world.WorldLeaderLock;
import com.game.scenemanager.SceneNodeSource;
import com.game.scenemanager.WorldSceneConfigs;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 控制面的装配与生命周期（scene-channels-spec §4.4、§4.5）：{@code leader-eligible = false} 时什么也不起（本副本只做数据面分配）；
 * 否则占 scene_id 发号租约（{@code NodeTypes.SCENE_MANAGER}，作用域 0，号段 [1, 1023]，TTL 15 s）→ 建规划器与协调者 → 起两条控制面线程。
 *
 * <p>发号租约无效（续期滞后）期间规划器跳过全部新建（fail-closed）；租约确认丢失 → 协调者放掉全部 zone 锁、停止竞选，等人工重启
 * （对应基线 scene_manager_service.go:250-260 的「先 Fence 再让位」，Java 不自杀）。{@link #close()}：停线程、放锁、还租约。
 */
public final class WorldChannelControlPlane implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WorldChannelControlPlane.class);

    private final ScheduledExecutorService leaseScheduler;
    private final NodeIdLease idLease;
    private final WorldChannelCoordinator coordinator;
    private final WorldChannelRunner runner;

    private WorldChannelControlPlane(ScheduledExecutorService leaseScheduler, NodeIdLease idLease,
                                     WorldChannelCoordinator coordinator, WorldChannelRunner runner) {
        this.leaseScheduler = leaseScheduler;
        this.idLease = idLease;
        this.coordinator = coordinator;
        this.runner = runner;
    }

    /** 按配置起控制面；不参与竞选时返回一个空壳（{@link #coordinator()} 为 null）。 */
    public static WorldChannelControlPlane start(RedissonClient redis, WorldChannelStore store, SceneNodeSource source,
                                                 WorldSceneConfigs worldConfigs, WorldChannelProperties props,
                                                 WorldChannelMetrics metrics) {
        if (!props.leaderEligible()) {
            log.info("xm.scene-manager.world.leader-eligible=false：本副本不竞选频道计划领导者，只做数据面分配");
            return new WorldChannelControlPlane(null, null, null, null);
        }
        ScheduledExecutorService leaseScheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("scene-manager-id-lease").daemon(true).factory());
        AtomicReference<WorldChannelCoordinator> coordinatorRef = new AtomicReference<>();
        NodeIdLease idLease;
        try {
            idLease = NodeIdLease.acquire(redis, leaseScheduler, WorldChannels.ID_LEASE_NODE_TYPE, WorldChannels.ID_LEASE_ZONE,
                    WorldChannels.ID_LEASE_MIN_ID, WorldChannels.ID_LEASE_MAX_ID, UUID.randomUUID().toString(),
                    WorldChannels.ID_LEASE_TTL, () -> {
                        WorldChannelCoordinator c = coordinatorRef.get();
                        if (c != null) {
                            c.onIdLeaseLost();
                        }
                    });
        } catch (RuntimeException e) {
            leaseScheduler.shutdownNow();
            throw e;
        }
        LeaseGatedSnowflake ids = new LeaseGatedSnowflake(new Snowflake(idLease.nodeId()), idLease::isValid);
        WorldChannelPlanner planner = new WorldChannelPlanner(props, worldConfigs.orderedConfigIds(), MirrorSources.NONE,
                ids::tryNext);
        String token = WorldLeaderLock.newToken("scene-manager-" + ProcessHandle.current().pid());
        WorldChannelCoordinator coordinator = new WorldChannelCoordinator(store, source, planner, props, metrics,
                NodeAvailability.ALL, token, System::nanoTime);
        coordinatorRef.set(coordinator);
        log.info("scene_id 发号 worker={} 覆盖模式={} 自动扩缩容={} 种子={} 按图覆盖={}", idLease.nodeId(), props.coverage(),
                props.autoscale().enabled(), props.channelCount(), props.channelCountByConfig());
        WorldChannelRunner runner = new WorldChannelRunner(coordinator, props.tick(), props.leaderLockTtl().dividedBy(3));
        return new WorldChannelControlPlane(leaseScheduler, idLease, coordinator, runner);
    }

    /** 协调者；不参与竞选时为 null。 */
    public WorldChannelCoordinator coordinator() {
        return coordinator;
    }

    /** scene_id 雪花的 worker（发号租约号）；不参与竞选时为 -1。 */
    public int idWorker() {
        return idLease != null ? idLease.nodeId() : -1;
    }

    @Override
    public void close() {
        if (runner != null) {
            runner.close();
        }
        if (idLease != null) {
            idLease.close();
        }
        if (leaseScheduler != null) {
            leaseScheduler.shutdownNow();
        }
    }
}
