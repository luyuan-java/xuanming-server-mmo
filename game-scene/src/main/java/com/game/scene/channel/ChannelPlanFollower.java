package com.game.scene.channel;

import com.game.api.proto.WorldChannel;
import com.game.discovery.world.WorldChannelStore;
import com.game.discovery.world.WorldPlan;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.metrics.SceneMetrics.ChannelPlanApply;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 主世界频道计划的拉取者（批次 5.1，scene-channels-spec §4.10.1、§4.10.5，D3）：每个拉取周期（缺省 1 s，
 * {@code game.scene.channel-plan-poll-interval}）在 {@code scene-sched} 上
 * <ol>
 *   <li>节点号租约此刻不可用（{@code NodeIdLease.isValid} 为假：丢失或续期滞后）→ 跳过（{@code skipped_lease}）——号可能已归别的实例，
 *       不按它的名下记录建场景；</li>
 *   <li>{@code GET game:world:{z:<zone>}:ver}，与上次应用成功的相同 → 结束（稳态每秒只有这一次往返）；</li>
 *   <li>不同 → 一段只读 Lua 一次取回版本号与整张频道表，只留本节点号名下的记录（节点身份 = 节点号，与实例无关），
 *       交给逻辑线程应用并等它完成（生产 {@link FencedPlanApplier} 在兑现前核对本拍原租约代次），成功后记下版本号、
 *       请求立即补发节点目录（{@code SceneDirectoryPublisher.requestPublishNow}）。</li>
 * </ol>
 * 读失败、数据损坏或逻辑线程没及时应用：WARN + {@code game.scene.channel.plan.poll.failures}，<b>什么也不应用</b>（尤其不把本地场景当孤儿），
 * 下一周期重试——已有场景照常服务（§4.14）。对应基线由 scene_manager 命令式发 CreateScene / DestroyScene RPC
 * （mmorpg go/scene_manager/internal/logic/scene_node_client.go:80-135；cpp/nodes/scene/handler/grpc/scene_node_service.cpp:19-125），
 * Java 改为节点拉取：没有晚到推送、黑洞节点、RPC 无时限（B9）这类问题，启动与稳态同一条路径。
 *
 * <p>启动时（{@link #syncOnce}，在启动线程上）先把 zone 登记进 {@code game:world:zones}（领导者只竞选登记过的 zone）再同步拉一次；
 * 两步失败都只告警、照常启动，由周期拉取重试（登记成功前每周期都先试登记）。
 *
 * <p>线程：{@link #pollOnce} 在调度线程上跑；{@link #syncOnce} 用于启动和租约恢复。两者经同一监视器串行化。
 * 每拍在 Redis 读取前捕获代次，读后及应用后复核；版本不变的同步通知也带同一原件。
 * Redis I/O 只在这两处（不在逻辑线程、不在 Netty I/O 线程，AGENTS.md §3）。
 */
public final class ChannelPlanFollower {

    private static final Logger log = LoggerFactory.getLogger(ChannelPlanFollower.class);

    /** 连续失败时每隔这么多次才再打一条 WARN（每秒一次的拉取，约一分钟一条）。 */
    static final int FAILURE_LOG_EVERY = 60;

    /** 把本节点号名下的记录交给逻辑线程应用，并<b>等它应用完</b>（限时）；失败或超时抛出。 */
    @FunctionalInterface
    public interface PlanApplier {
        void apply(long version, List<WorldChannel> mine) throws Exception;
    }

    /** 原代次在 Redis 读取前捕获；实现必须在逻辑线程真正兑现前再次核验。 */
    @FunctionalInterface
    public interface FencedPlanApplier {
        void apply(long version, List<WorldChannel> mine, long leaseEpoch) throws Exception;
    }

    private final WorldChannelStore store;
    private final int zoneId;
    private final int nodeId;
    private final BooleanSupplier leaseValid;
    private final LongSupplier leaseEpoch;
    private final FencedPlanApplier applier;
    private final Runnable onApplied;
    private final LongConsumer onSynchronized;
    private final SceneMetrics metrics;
    /** 上次应用成功的版本；-1 = 还没应用过（Redis 里没有计划时版本是 0，也要整读一次）。只在拉取线程上写。 */
    private volatile long appliedVersion = -1;
    private volatile boolean zoneRegistered;
    private volatile boolean stopped;
    /** 连续失败次数（限频告警用），只在拉取线程上读写。 */
    private int consecutiveFailures;
    private ScheduledFuture<?> task;

    /**
     * @param nodeId     本节点号（按 zone 的 scene 节点号租约）
     * @param leaseValid 节点号租约此刻是否可用（生产 = {@code NodeIdLease::isValid}）
     * @param applier    交给逻辑线程应用并等完成
     * @param onApplied  应用成功之后（立即补发节点目录；不得阻塞）
     */
    public ChannelPlanFollower(WorldChannelStore store, int zoneId, int nodeId, BooleanSupplier leaseValid,
                               PlanApplier applier, Runnable onApplied, SceneMetrics metrics) {
        this(store, zoneId, nodeId, leaseValid, applier, onApplied, metrics, () -> { });
    }

    public ChannelPlanFollower(WorldChannelStore store, int zoneId, int nodeId, BooleanSupplier leaseValid,
                               PlanApplier applier, Runnable onApplied, SceneMetrics metrics, Runnable onSynchronized) {
        this(store, zoneId, nodeId, leaseValid, () -> 0,
                (version, mine, epoch) -> applier.apply(version, mine), onApplied, metrics,
                epoch -> onSynchronized.run());
    }

    public ChannelPlanFollower(WorldChannelStore store, int zoneId, int nodeId, BooleanSupplier leaseValid,
                               LongSupplier leaseEpoch, FencedPlanApplier applier, Runnable onApplied,
                               SceneMetrics metrics, LongConsumer onSynchronized) {
        if (nodeId <= 0) {
            throw new IllegalArgumentException("节点号必须为正: " + nodeId);
        }
        this.store = store;
        this.zoneId = zoneId;
        this.nodeId = nodeId;
        this.leaseValid = leaseValid;
        this.leaseEpoch = java.util.Objects.requireNonNull(leaseEpoch);
        this.applier = applier;
        this.onApplied = onApplied;
        this.metrics = metrics;
        this.onSynchronized = java.util.Objects.requireNonNull(onSynchronized);
    }

    /**
     * 启动时同步做一次：登记 zone、拉一次计划并应用（§4.10.5）。失败只告警、返回 false，照常启动——新节点计划里没有它的记录时
     * 以零个场景启动，领导者下一拍按覆盖规则给它铺频道。
     */
    public boolean syncOnce() {
        boolean ok = poll();
        if (!ok) {
            log.warn("启动时没能拉到频道计划，先不带频道启动，由周期拉取重试 zone={} node_id={}", zoneId, nodeId);
        }
        return ok;
    }

    public synchronized void start(ScheduledExecutorService scheduler, Duration interval) {
        if (task == null && !stopped) {
            long periodMs = interval.toMillis();
            task = scheduler.scheduleWithFixedDelay(this::pollOnce, periodMs, periodMs, TimeUnit.MILLISECONDS);
            log.info("频道计划拉取已启动 zone={} node_id={} 周期={} 已应用版本={}", zoneId, nodeId, interval,
                    appliedVersion < 0 ? "无" : Long.toUnsignedString(appliedVersion));
        }
    }

    /** 停止拉取（停服第一步、节点号租约丢失时）。之后正在跑的一次可能还会应用完，再不会有新的。幂等。 */
    public synchronized void stop() {
        stopped = true;
        if (task != null) {
            task.cancel(false);
        }
    }

    /** 已应用成功的版本；还没应用过为 -1。 */
    public long appliedVersion() {
        return appliedVersion;
    }

    /** 一个拉取周期（调度线程；包内可见供测试直接驱动）。 */
    void pollOnce() {
        try {
            poll();
        } catch (RuntimeException e) {
            // 不让定时任务因意外异常停掉
            log.error("频道计划拉取出错，下一周期照常", e);
        }
    }

    private synchronized boolean poll() {
        if (stopped) {
            return false;
        }
        long expectedEpoch = leaseEpoch.getAsLong();
        if (!sameLease(expectedEpoch)) {
            metrics.channelPlanApply(ChannelPlanApply.SKIPPED_LEASE);
            log.debug("节点号租约此刻不可用，跳过本次频道计划拉取 zone={} node_id={}", zoneId, nodeId);
            return false;
        }
        registerZoneIfNeeded();
        long version;
        WorldPlan plan;
        try {
            version = store.planVersion(zoneId);
            if (version == appliedVersion) {
                if (!sameLease(expectedEpoch)) return false;
                recovered();
                onSynchronized.accept(expectedEpoch);
                return true;
            }
            plan = store.readPlan(zoneId);
        } catch (RuntimeException e) {
            failed("读频道计划失败", e);
            return false;
        }
        if (!sameLease(expectedEpoch)) return false;
        List<WorldChannel> mine = plan.channelsOn(nodeId);
        try {
            applier.apply(plan.version(), mine, expectedEpoch);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failed("等逻辑线程应用频道计划时被中断", e);
            return false;
        } catch (Exception e) {
            failed("逻辑线程没能应用频道计划（超时或已停止）", e);
            return false;
        }
        if (!sameLease(expectedEpoch)) return false;
        appliedVersion = plan.version();
        recovered();
        try {
            onApplied.run();
            onSynchronized.accept(expectedEpoch);
        } catch (RuntimeException e) {
            log.warn("应用频道计划之后补发节点目录失败（等下一个周期发布）", e);
        }
        return true;
    }

    private boolean sameLease(long expectedEpoch) {
        return !stopped && leaseValid.getAsBoolean() && leaseEpoch.getAsLong() == expectedEpoch;
    }

    private void registerZoneIfNeeded() {
        if (zoneRegistered) {
            return;
        }
        try {
            store.registerZone(zoneId);
            zoneRegistered = true;
            log.info("已把 zone 登记进主世界频道的 zone 集合 zone={}", zoneId);
        } catch (RuntimeException e) {
            // 只影响「领导者竞选哪些 zone」：同 zone 别的节点登记过就不受影响；下一周期再试，照样往下拉计划
            metrics.channelPlanPollFailed();
            log.warn("登记 zone 失败，下一周期重试 zone={}: {}", zoneId, e.toString());
        }
    }

    private void failed(String what, Exception e) {
        metrics.channelPlanPollFailed();
        consecutiveFailures++;
        if (consecutiveFailures == 1) {
            log.warn("{}，什么也不应用、下一周期重试 zone={} node_id={}", what, zoneId, nodeId, e);
        } else if (consecutiveFailures % FAILURE_LOG_EVERY == 0) {
            log.warn("{}（已连续失败 {} 次），什么也不应用 zone={} node_id={}: {}", what, consecutiveFailures, zoneId,
                    nodeId, e.toString());
        }
    }

    private void recovered() {
        if (consecutiveFailures > 0) {
            log.info("频道计划拉取恢复（此前连续失败 {} 次） zone={} node_id={}", consecutiveFailures, zoneId, nodeId);
            consecutiveFailures = 0;
        }
    }
}
