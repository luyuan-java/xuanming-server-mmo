package com.game.scenemanager;

import com.game.common.id.LeaseGatedSnowflake;
import com.game.common.id.Snowflake;
import com.game.discovery.NodeIdLease;
import com.game.discovery.world.WorldChannels;
import com.game.scenemanager.world.SceneIdSource;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * scene-manager 的全服 scene_id 发号器（批次 5.3 R5，dungeon-mirror-spec §6.6）：占一个 {@code NodeTypes.SCENE_MANAGER} 节点号租约
 * （作用域 0，号段 [1, 1023]，TTL 15 s，{@link WorldChannels}）作雪花 worker，租约有效才发号（{@link LeaseGatedSnowflake}）。
 * 主世界频道（规划器，控制面 tick 线程）与镜像 / 副本实例（{@code createInstance}，Dubbo 业务线程）共用这一个——「scene_id 只由一种发号器发」
 * （{@code Snowflake}「一种 ID 只在一种节点类型里产生」，Q8）。
 *
 * <p><b>每个副本都申领</b>：5.1 时租约只在可竞选领导的副本上申领（{@code WorldChannelControlPlane.start}），{@code leader-eligible = false} 的
 * 副本（金丝雀）就发不出实例号；挪成独立 bean 后不论是否竞选都占一个号（worker 号段 1..1023 全服共享，副本数远小于它）。启动时占不到号
 * （Redis 不可达 / 号段占满）即启动失败。
 *
 * <p>租约无效（续期滞后，{@code isValid} 为假）期间 {@link #tryNext()} 为空：规划器本拍停止全部新建，{@code createInstance} 以调用失败返回
 * （scene 推 23 {1003}，Q12）；续期恢复后自动恢复。租约<b>确认丢失</b>是终态（本进程不再发号，等人工重启）：回调全部
 * {@link #onLost 登记的监听}（控制面借此放掉全部 zone 锁、停止竞选，同基线 scene_manager_service.go:250-260 的「先 Fence 再让位」）。
 *
 * <p>线程安全：{@code Snowflake.nextId} 是 synchronized 的，规划器线程与 Dubbo 线程可以共用；丢失回调在租约续期线程上执行。
 */
public final class SceneIdAllocator implements SceneIdSource, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SceneIdAllocator.class);

    private final int worker;
    private final LeaseGatedSnowflake ids;
    /** 生产的租约与它的续期线程；{@link #forTesting} 造的为 null。 */
    private final NodeIdLease lease;
    private final ScheduledExecutorService leaseScheduler;
    /** 确认丢失（终态）。发号前读，所以单独 volatile。 */
    private volatile boolean lost;
    /** 持 {@code this} 读写。 */
    private final List<Runnable> lostListeners = new ArrayList<>();

    private SceneIdAllocator(int worker, BooleanSupplier leaseValid, NodeIdLease lease,
                             ScheduledExecutorService leaseScheduler) {
        this.worker = worker;
        this.lease = lease;
        this.leaseScheduler = leaseScheduler;
        this.ids = new LeaseGatedSnowflake(new Snowflake(worker), () -> !lost && leaseValid.getAsBoolean());
    }

    /** 生产：在 Redis 上占发号租约（阻塞，启动时调用一次）。占不到号抛异常（启动失败）。 */
    public static SceneIdAllocator acquire(RedissonClient redis) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("scene-manager-id-lease").daemon(true).factory());
        AtomicReference<SceneIdAllocator> self = new AtomicReference<>();
        NodeIdLease lease;
        try {
            lease = NodeIdLease.acquire(redis, scheduler, WorldChannels.ID_LEASE_NODE_TYPE, WorldChannels.ID_LEASE_ZONE,
                    WorldChannels.ID_LEASE_MIN_ID, WorldChannels.ID_LEASE_MAX_ID, UUID.randomUUID().toString(),
                    WorldChannels.ID_LEASE_TTL, () -> {
                        SceneIdAllocator allocator = self.get();
                        if (allocator != null) {
                            allocator.leaseLost();
                        }
                    });
        } catch (RuntimeException e) {
            scheduler.shutdownNow();
            throw e;
        }
        SceneIdAllocator allocator = new SceneIdAllocator(lease.nodeId(), lease::isValid, lease, scheduler);
        self.set(allocator);
        if (lease.isLost()) {
            // 赋值之前就丢了（续期是 TTL/3 之后的事，实际不会发生）：回调当时找不到对象，这里补上
            allocator.leaseLost();
        }
        log.info("scene_id 发号租约已占 worker={}（主世界频道与镜像 / 副本实例共用；每个副本都申领，R5）", lease.nodeId());
        return allocator;
    }

    /** 不占租约的发号器：worker 固定、租约是否有效由调用方决定。<b>只给单测与装配测试用</b>。 */
    public static SceneIdAllocator forTesting(int worker, BooleanSupplier leaseValid) {
        return new SceneIdAllocator(worker, leaseValid, null, null);
    }

    /** 发一个号；租约无效 / 已丢失、时钟回拨超出容忍或发出 0 时为空（fail-closed，绝不自造号）。 */
    @Override
    public OptionalLong tryNext() {
        return ids.tryNext();
    }

    /** 租约此刻能否用来发号（未丢失且续期不滞后）。 */
    public boolean leaseValid() {
        return ids.leaseValid();
    }

    /** 雪花 worker（= 发号租约的节点号）。 */
    public int worker() {
        return worker;
    }

    public boolean isLost() {
        return lost;
    }

    /** 登记租约确认丢失时的回调（在续期线程上调用，只调一次）；已经丢失则在调用方线程上立即调用。 */
    public void onLost(Runnable listener) {
        synchronized (this) {
            if (!lost) {
                lostListeners.add(listener);
                return;
            }
        }
        listener.run();
    }

    /** 租约确认丢失（续期线程；包内可见供测试直接驱动）。只生效一次。 */
    void leaseLost() {
        List<Runnable> listeners;
        synchronized (this) {
            if (lost) {
                return;
            }
            lost = true;
            listeners = List.copyOf(lostListeners);
            lostListeners.clear();
        }
        log.error("scene_id 发号租约已丢失 worker={}：本进程不再发号（频道新建停止、建实例调用失败），需要重启", worker);
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                log.error("发号租约丢失回调出错", e);
            }
        }
    }

    /** 停续期并还租约（只删仍属于本实例的键）。幂等。 */
    @Override
    public void close() {
        if (lease != null) {
            lease.close();
        }
        if (leaseScheduler != null) {
            leaseScheduler.shutdownNow();
        }
    }
}
