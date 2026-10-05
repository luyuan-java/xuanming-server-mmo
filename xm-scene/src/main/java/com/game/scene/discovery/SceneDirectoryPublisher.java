package com.game.scene.discovery;

import com.game.api.proto.SceneEntry;
import com.game.api.proto.SceneNodeInfo;
import com.game.discovery.NodeDirectory;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 定期把本节点写进 Redis 节点目录（{@code SceneNodeInfo}，TTL 15s，每 5s 刷新），scene-manager 按它分配场景、
 * gate 按它连本节点。
 *
 * <p>场景列表与已应用的频道计划版本（批次 5.1，scene-channels-spec §4.10.1）由调用方在场景逻辑线程上一并取快照（{@code snapshot}），
 * 发布（Redis I/O）在调度线程上做，不占逻辑线程。应用了新的频道计划之后、实例建立 / 回收 / 复活 / 级联 / 销毁之后（批次 5.3）调
 * {@link #requestPublishNow()} 立即补发一次，不等下一个 5s：分配据目录排除排空频道（D13），领导者据 {@code applied_plan_version} 收尾排空（§4.6.2 P3），
 * 目录是实例的唯一登记（dungeon-mirror-spec D1）。
 *
 * <p><b>单飞（single-flight）</b>：周期发布与立即补发都只是「标脏 + 至多一个在跑的发布任务」，任务里循环「清脏 → 取快照 → 发布」直到不再脏。
 * 所以任意多次请求（一次排空推进销毁上百个实例、每个都请求一次）合并成至多两次发布，<b>至多占一条调度线程</b>——调度池与节点号续期、
 * 归属续约共用（{@code SceneNode} 只给了 3 条），发布不能把续期挤到有效期之外。发布天然串行：先取的旧快照不会盖掉后取的新快照。
 *
 * <p>{@link #requestPublishNow()} 不加锁、不碰 Redis（逻辑线程上调用，不得等 I/O，AGENTS.md §3）。真正的 Redis 写在 {@code ioLock} 下，
 * {@link #stop} 也在 {@code ioLock} 下置停并删条目：{@link #stop} 返回后不会再有发布落地，所以「先停再删条目」不会被迟到的发布复活，
 * 节点号租约丢失时（号可能已归别的实例）也不会再写别人的条目。
 */
public final class SceneDirectoryPublisher {

    private static final Logger log = LoggerFactory.getLogger(SceneDirectoryPublisher.class);

    public static final Duration PERIOD = Duration.ofSeconds(5);
    public static final Duration TTL = Duration.ofSeconds(15);

    /**
     * 一次发布的场景部分（逻辑线程上取）。
     *
     * @param scenes             场景列表（{@code SceneWorld.sceneEntries}）
     * @param appliedPlanVersion 已应用的频道计划版本（{@code SceneWorld.appliedPlanVersion}）
     */
    public record Snapshot(List<SceneEntry> scenes, long appliedPlanVersion) {

        public Snapshot {
            scenes = List.copyOf(scenes);
        }
    }

    private final NodeDirectory<SceneNodeInfo> directory;
    private final SceneNodeInfo identity;
    private final Callable<Snapshot> snapshot;
    /** 有没发出去的变化（请求立即补发或周期到点时置位，发布任务取快照前清掉）。 */
    private final AtomicBoolean dirty = new AtomicBoolean();
    /** 有发布任务在跑或已排队（单飞：同一时刻至多一个）。 */
    private final AtomicBoolean draining = new AtomicBoolean();
    /** Redis 写与 {@link #stop} 互斥（只包 I/O 本身，取快照不在锁内：{@link #stop} 不必等一次可能卡在逻辑线程上的取快照）。 */
    private final Object ioLock = new Object();
    /** 启动后非 null（{@link #requestPublishNow()} 不加锁读）。 */
    private volatile ScheduledExecutorService scheduler;
    private volatile boolean stopped;
    /** 周期任务（只在 {@code this} 的监视器下读写）。 */
    private ScheduledFuture<?> task;

    /**
     * @param identity 节点身份部分（zone / node_id / instance_id / link_host / link_port / rpc_host / rpc_port），
     *                 scenes 与 applied_plan_version 每次发布时填
     * @param snapshot 取场景快照（实现负责切到场景逻辑线程并限时等待）
     */
    public SceneDirectoryPublisher(NodeDirectory<SceneNodeInfo> directory, SceneNodeInfo identity,
                                   Callable<Snapshot> snapshot) {
        this.directory = directory;
        this.identity = identity;
        this.snapshot = snapshot;
    }

    public synchronized void start(ScheduledExecutorService scheduler) {
        if (task == null && !stopped) {
            this.scheduler = scheduler;
            task = scheduler.scheduleWithFixedDelay(this::periodic, 0, PERIOD.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 立即补发一次（在调度线程上做）。<b>不加锁、不阻塞、不碰 Redis</b>，任意线程可调（含场景逻辑线程）。已有发布任务在跑或排队时只标脏，
     * 由那个任务发完这一轮后再补一轮（合并）。没启动或已停止时什么也不做；调度线程已关闭时丢弃（停服中）。
     */
    public void requestPublishNow() {
        ScheduledExecutorService s = scheduler;
        if (stopped || s == null) {
            return;
        }
        dirty.set(true);
        if (!draining.compareAndSet(false, true)) {
            return;
        }
        try {
            s.execute(this::drain);
        } catch (RejectedExecutionException e) {
            draining.set(false);
            log.debug("调度线程已关闭，丢弃立即发布");
        }
    }

    /** 周期到点（调度线程）：标脏；没有发布任务在跑就就地发（不再另占一条线程），在跑就交给它补一轮。 */
    private void periodic() {
        if (stopped) {
            return;
        }
        dirty.set(true);
        if (draining.compareAndSet(false, true)) {
            drain();
        }
    }

    /**
     * 单飞发布循环（调度线程；调用方已把 {@code draining} 置位）：清脏 → 发布，直到不再脏或已停止。退出前复核一次，防止
     * 「最后一次清脏之后、复位 {@code draining} 之前」到达的请求丢失（它看到 {@code draining} 仍置位，只标了脏）。
     */
    private void drain() {
        while (true) {
            try {
                while (!stopped && dirty.getAndSet(false)) {
                    publishOnce();
                }
            } catch (RuntimeException e) {
                // publishOnce 已兜住快照与 Redis 的异常；这里兜住其余意外，免得周期任务因异常被取消
                log.warn("发布场景节点目录出错，下一轮重试", e);
            } finally {
                draining.set(false);
            }
            if (stopped || !dirty.get() || !draining.compareAndSet(false, true)) {
                return;
            }
        }
    }

    /** 取一次快照并发布（调度线程；单飞保证同一时刻至多一个在跑）。已停止则不发布。 */
    void publishOnce() {
        Snapshot s;
        try {
            s = snapshot.call();
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("取场景快照失败，本轮不发布节点目录", e);
            return;
        }
        SceneNodeInfo info = identity.toBuilder()
                .clearScenes()
                .addAllScenes(s.scenes())
                .setAppliedPlanVersion(s.appliedPlanVersion())
                .build();
        synchronized (ioLock) {
            if (stopped) {
                return;
            }
            try {
                directory.publish(identity.getZoneId(), identity.getNodeId(), info, TTL);
            } catch (RuntimeException e) {
                log.warn("发布场景节点目录失败，下一轮重试", e);
            }
        }
    }

    /**
     * 停止发布。{@code removeEntry=true}（正常停服）同时删除目录条目；节点号租约丢失时传 false：
     * 这个节点号可能已被别的实例占用，删条目会删掉别人的。返回前等在途的一次 Redis 写结束，返回后不会再有发布落地。
     * 停服线程 / 租约续期线程上调用（会等 Redis I/O，不得在场景逻辑线程上调）。
     */
    public synchronized void stop(boolean removeEntry) {
        if (stopped) {
            return;
        }
        stopped = true;
        if (task != null) {
            task.cancel(false);
        }
        // 锁序 this → ioLock（publishOnce 只拿 ioLock，requestPublishNow 什么锁都不拿）：等在途的一次写结束，之后的发布都会看到 stopped
        synchronized (ioLock) {
            if (removeEntry) {
                try {
                    directory.remove(identity.getZoneId(), identity.getNodeId());
                } catch (RuntimeException e) {
                    log.warn("删除场景节点目录条目失败（TTL 到期后自动消失）", e);
                }
            }
        }
    }
}
