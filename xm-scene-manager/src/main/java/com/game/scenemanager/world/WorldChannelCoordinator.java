package com.game.scenemanager.world;

import com.game.api.proto.WorldChannel;
import com.game.discovery.world.WorldChannelStore;
import com.game.discovery.world.WorldLeaderLock;
import com.game.discovery.world.WorldPlanSnapshot;
import com.game.discovery.world.WorldPlanWriteResult;
import com.game.scenemanager.SceneNodeSource;
import com.game.scenemanager.world.WorldChannelMetrics.TickResult;
import com.game.scenemanager.world.WorldChannelProperties.Coverage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 主世界频道的控制面（scene-channels-spec §4.1、§4.4、§4.6）：每个 zone 一把领导锁，领导者每拍「只读快照 + 读目录 → 纯函数规划 →
 * 令牌 + 版本号 CAS 写入」。对应基线 scene_manager 领导者上散落的 fullSync 补建 / 再平衡 / 孤儿清理 / 扩缩容循环
 * （mmorpg load_reporter.go:684-721、world_autoscale.go:119-175、world_rebalance.go:54-106）。
 *
 * <p><b>线程</b>（AGENTS.md §3）：{@link #tick()} 只在控制面专用线程（{@code scene-manager-world}）上调，Redis 阻塞 I/O 可在其上；
 * {@link #renewLeaders()} 在<b>另一个</b>续期线程上调——一拍再慢也不拖住续期（补上基线「慢 RPC 让锁过期」，world_init.go:95-101）。
 * {@link #releaseAll()} 可从任意线程调（停服、发号租约丢失）。每个 zone 的规划状态（缺席表、上次扩缩容 / 再平衡时刻）只在 tick 线程读写；
 * 锁对象两边共享（{@link WorldLeaderLock} 自身线程安全）。
 *
 * <p><b>每拍每 zone</b>：
 * <ol>
 *   <li>没持锁就竞选一次（{@code SET NX PX}）；竞选不上 = 跟随者（{@code not_leader}，清掉该 zone 的 gauge）。新当选立即跑一拍
 *       （对应基线当选即 fullSync，scene_manager_service.go:113-115），缺席表从零计时。</li>
 *   <li>锁的有效期已过（续期滞后超过 2/3 TTL）→ 不做任何变更（{@code not_leader}）。</li>
 *   <li>快照 + 目录 → {@link WorldChannelPlanner#plan}；批次为空就不写（不推进 ver，节点不必重读）。</li>
 *   <li>写入：成功 → 计事件、更新 gauge；−1（围栏）→ 立即降级；−2（版本冲突）→ 本拍作废、下拍重读重算。</li>
 * </ol>
 * Redis 故障 / 坏数据 → 本拍什么也不应用（{@code error}），计划不变。
 */
public final class WorldChannelCoordinator {

    private static final Logger log = LoggerFactory.getLogger(WorldChannelCoordinator.class);

    private final WorldChannelStore store;
    private final SceneNodeSource directory;
    private final WorldChannelPlanner planner;
    private final WorldChannelProperties props;
    private final WorldChannelMetrics metrics;
    private final NodeAvailability availability;
    private final String token;
    private final LongSupplier nanoClock;
    private final Map<Integer, ZoneState> zones = new ConcurrentHashMap<>();
    /** 停服或发号租约确认丢失：不再竞选、不再规划（等人工重启，同 scene 节点丢租约的口径）。 */
    private volatile boolean stopped;

    /**
     * @param token     本副本的领导者令牌（{@link WorldLeaderLock#newToken}，全部 zone 共用）
     * @param nanoClock 单调时钟（缺席表、扩缩容 / 再平衡间隔）；记录上的时刻一律用快照里的 Redis TIME
     */
    public WorldChannelCoordinator(WorldChannelStore store, SceneNodeSource directory, WorldChannelPlanner planner,
                                   WorldChannelProperties props, WorldChannelMetrics metrics, NodeAvailability availability,
                                   String token, LongSupplier nanoClock) {
        this.store = store;
        this.directory = directory;
        this.planner = planner;
        this.props = props;
        this.metrics = metrics;
        this.availability = availability;
        this.token = token;
        this.nanoClock = nanoClock;
    }

    /** 一个 zone 的领导状态。除 {@link #lock} 外只在 tick 线程上读写。 */
    private static final class ZoneState {
        final WorldLeaderLock lock;
        final AbsenceTracker absence = new AbsenceTracker();
        /** 本任期是否已初始化（竞选成功后第一拍置真；看到锁已不持有即置假）。 */
        boolean inTerm;
        boolean autoscaleRan;
        long lastAutoscaleNanos;
        /** 上次跑择机迁移时的活节点集合；null = 本任期还没跑过。 */
        List<Integer> lastRebalanceLive;
        long lastRebalanceNanos;
        List<String> lastAnomalies = List.of();

        ZoneState(WorldLeaderLock lock) {
            this.lock = lock;
        }

        void newTerm() {
            inTerm = true;
            absence.clear();
            autoscaleRan = false;
            lastRebalanceLive = null;
            lastAnomalies = List.of();
        }
    }

    public String token() {
        return token;
    }

    /** 本副本当前持有锁的 zone（无符号升序；不看有效期）。 */
    public Set<Integer> ledZones() {
        Set<Integer> out = new TreeSet<>(Integer::compareUnsigned);
        zones.forEach((zone, state) -> {
            if (state.lock.isHeld()) {
                out.add(zone);
            }
        });
        return out;
    }

    public boolean isStopped() {
        return stopped;
    }

    /** 控制面一拍：对 {@code xm:world:zones} 里的每个 zone 竞选 / 规划 / 写入（tick 线程）。 */
    public void tick() {
        if (stopped) {
            return;
        }
        List<Integer> zoneIds;
        try {
            zoneIds = store.zones();
        } catch (RuntimeException e) {
            log.warn("读 zone 集合失败，本拍跳过", e);
            metrics.tick(TickResult.ERROR);
            return;
        }
        for (int zoneId : zoneIds) {
            if (stopped) {
                break;
            }
            ZoneState state = zones.computeIfAbsent(zoneId,
                    z -> new ZoneState(new WorldLeaderLock(store, z, token, props.leaderLockTtl())));
            tickZone(zoneId, state);
        }
        metrics.leaderZones(ledZones().size());
    }

    /** 续期全部持有中的锁（续期线程，每 TTL/3）。续期发现锁已不是本令牌 → 立即降级并清掉该 zone 的 gauge。 */
    public void renewLeaders() {
        if (stopped) {
            releaseAll(); // 停止之后在途的一拍又竞选上的锁：不续，放掉
            return;
        }
        zones.forEach((zoneId, state) -> {
            if (state.lock.isHeld() && state.lock.renew() == WorldLeaderLock.RenewOutcome.LOST) {
                metrics.clearZone(zoneId);
            }
        });
        metrics.leaderZones(ledZones().size());
    }

    /** 发号租约确认丢失（租约线程回调）：放掉全部 zone 锁、停止竞选，等人工重启（对应基线 scene_manager_service.go:250-260，Java 不自杀）。 */
    public void onIdLeaseLost() {
        log.error("scene_id 发号租约丢失：放掉全部频道计划领导锁、停止竞选（数据面分配照常），需要重启本进程");
        releaseAll();
    }

    /** 停止竞选并放掉全部持有中的锁（属主校验，同基线 SIGTERM 放锁 scene_manager_service.go:122-127）。 */
    public void releaseAll() {
        stopped = true;
        zones.forEach((zoneId, state) -> {
            if (state.lock.isHeld()) {
                state.lock.release();
            }
            metrics.clearZone(zoneId);
        });
        metrics.leaderZones(0);
    }

    private void tickZone(int zoneId, ZoneState state) {
        WorldLeaderLock lock = state.lock;
        if (!lock.isHeld()) {
            state.inTerm = false;
            boolean acquired;
            try {
                acquired = lock.tryAcquire();
            } catch (RuntimeException e) {
                log.warn("竞选频道计划领导者失败 zone={}", Integer.toUnsignedString(zoneId), e);
                metrics.clearZone(zoneId);
                metrics.tick(TickResult.ERROR);
                return;
            }
            if (!acquired) {
                metrics.clearZone(zoneId);
                metrics.tick(TickResult.NOT_LEADER);
                return;
            }
            if (stopped) {
                lock.release(); // 竞选在途时进程开始停止 / 发号租约丢失：刚拿到的锁立即放掉
                return;
            }
        }
        if (!state.inTerm) {
            state.newTerm();
        }
        if (!lock.isValid()) {
            // 续期滞后超过 2/3 TTL：别的副本可能马上（或已经）拿到锁，停止一切变更
            metrics.tick(TickResult.NOT_LEADER);
            return;
        }
        long start = nanoClock.getAsLong();
        try {
            metrics.tick(runPlan(zoneId, state));
        } catch (RuntimeException e) {
            log.warn("频道计划一拍失败，本拍什么也没应用 zone={}", Integer.toUnsignedString(zoneId), e);
            metrics.tick(TickResult.ERROR);
        } finally {
            metrics.tickTimer().record(Math.max(0, nanoClock.getAsLong() - start), TimeUnit.NANOSECONDS);
        }
    }

    private TickResult runPlan(int zoneId, ZoneState state) {
        WorldPlanSnapshot snapshot = store.snapshot(zoneId);
        DirectoryView view = DirectoryView.of(zoneId, directory.list(zoneId));
        long now = nanoClock.getAsLong();

        Set<Integer> referenced = new HashSet<>();
        for (WorldChannel channel : snapshot.channels().values()) {
            referenced.add(channel.getNodeId());
        }
        Map<Integer, Long> absent = state.absence.update(referenced, view.nodes().keySet(), now);
        List<Integer> live = new ArrayList<>();
        for (int nodeId : view.nodes().keySet()) {
            if (availability.acceptsNewChannels(zoneId, nodeId)) {
                live.add(nodeId);
            }
        }
        live = ChannelPlacement.sortNodes(live);

        boolean autoscaleDue = props.autoscale().enabled()
                && (!state.autoscaleRan || now - state.lastAutoscaleNanos >= props.autoscale().checkInterval().toNanos());
        boolean rebalanceDue = rebalanceDue(state, live, now);
        Map<Long, Long> reservations = rebalanceDue ? reservationCounts(zoneId, snapshot, view, live) : Map.of();

        PlanResult result = planner.plan(new PlanInput(zoneId, snapshot, view, absent, live, autoscaleDue, rebalanceDue,
                reservations));
        reportAnomalies(zoneId, state, result.anomalies());

        if (!result.batch().isEmpty()) {
            if (!state.lock.isValid()) {
                return TickResult.NOT_LEADER; // 规划期间续期滞后：不写
            }
            WorldPlanWriteResult written;
            try {
                written = store.write(zoneId, state.lock.token(), result.batch());
            } catch (RuntimeException e) {
                metrics.recordFailed(result.events());
                throw e;
            }
            switch (written.status()) {
                case FENCED -> {
                    state.lock.markLost("计划写入被围栏（锁已不是本令牌）");
                    metrics.clearZone(zoneId);
                    metrics.recordFailed(result.events());
                    return TickResult.FENCED;
                }
                case CONFLICT -> {
                    log.info("频道计划版本冲突，本拍作废、下拍重读 zone={} expected={}", Integer.toUnsignedString(zoneId),
                            Long.toUnsignedString(result.batch().expectedVersion()));
                    metrics.recordFailed(result.events());
                    return TickResult.CONFLICT;
                }
                case WRITTEN -> log.info("频道计划已写入 zone={} ver={} ops={} removed={}", Integer.toUnsignedString(zoneId),
                        Long.toUnsignedString(written.newVersion()), result.batch().size(), result.removed().size());
            }
        }
        commit(zoneId, state, result, now, autoscaleDue, rebalanceDue, live);
        if (result.noLease()) {
            return TickResult.NO_LEASE;
        }
        if (result.noNodes()) {
            return TickResult.NO_NODES;
        }
        return TickResult.OK;
    }

    /** 写入成功（或无需写入）之后：计事件、更新 gauge、推进扩缩容 / 再平衡的节拍。 */
    private void commit(int zoneId, ZoneState state, PlanResult result, long now, boolean autoscaleDue,
                        boolean rebalanceDue, List<Integer> live) {
        metrics.record(result.events());
        metrics.zone(zoneId, result.counts(), result.nodeGonePending(), result.betterHomePending());
        if (autoscaleDue) {
            state.autoscaleRan = true;
            state.lastAutoscaleNanos = now;
        }
        if (rebalanceDue) {
            state.lastRebalanceLive = live;
            state.lastRebalanceNanos = now;
        }
    }

    /** 择机迁移到期：hash 模式、预算 &gt; 0，且活节点集合与上次不同（含本任期第一拍）或距上次满 {@code rebalance.interval}（0 = 只按集合变化）。 */
    private boolean rebalanceDue(ZoneState state, List<Integer> live, long now) {
        if (props.coverage() != Coverage.HASH || props.rebalance().maxMigrationsPerTick() <= 0) {
            return false;
        }
        if (state.lastRebalanceLive == null || !state.lastRebalanceLive.equals(live)) {
            return true;
        }
        long interval = props.rebalance().interval().toNanos();
        return interval > 0 && now - state.lastRebalanceNanos >= interval;
    }

    /** 择机迁移候选的未到期预占数；读失败只告警、返回空表（候选一个也不迁，fail-closed）。 */
    private Map<Long, Long> reservationCounts(int zoneId, WorldPlanSnapshot snapshot, DirectoryView view, List<Integer> live) {
        List<Long> probe = planner.rebalanceProbe(snapshot, view, live);
        if (probe.isEmpty()) {
            return Map.of();
        }
        try {
            List<Long> counts = store.countReservations(zoneId, probe);
            Map<Long, Long> out = new HashMap<>();
            for (int i = 0; i < probe.size() && i < counts.size(); i++) {
                out.put(probe.get(i), counts.get(i));
            }
            return out;
        } catch (RuntimeException e) {
            log.warn("读择机迁移候选的预占数失败，本拍不迁 zone={}", Integer.toUnsignedString(zoneId), e);
            return Map.of();
        }
    }

    /** 异常情况按 zone 去重告警：和上一拍相同的不再重复打（限频）。 */
    private static void reportAnomalies(int zoneId, ZoneState state, List<String> anomalies) {
        if (anomalies.equals(state.lastAnomalies)) {
            return;
        }
        Set<String> previous = new HashSet<>(state.lastAnomalies);
        for (String anomaly : anomalies) {
            if (!previous.contains(anomaly)) {
                log.warn("频道计划 zone={}: {}", Integer.toUnsignedString(zoneId), anomaly);
            }
        }
        state.lastAnomalies = List.copyOf(anomalies);
    }
}
