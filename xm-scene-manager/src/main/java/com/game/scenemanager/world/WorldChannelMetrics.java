package com.game.scenemanager.world;

import com.game.scenemanager.world.PlanEvent.AutoscaleAction;
import com.game.scenemanager.world.PlanEvent.AutoscaleOutcome;
import com.game.scenemanager.world.PlanEvent.MigrationOutcome;
import com.game.scenemanager.world.PlanEvent.MigrationReason;
import com.game.scenemanager.world.PlanResult.ChannelCounts;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 主世界频道编排的指标（scene-channels-spec §6.2；Micrometer，经 actuator 以 Prometheus 格式导出，architecture.md §11）。
 *
 * <p><b>标签基数</b>（AGENTS.md §5、architecture.md:703-706，D16）：不以 zone / scene_id / 节点号 / player_id 作标签。
 * 场景维度只用 {@code scene_config}（受 World 表约束，World 表之外的图汇总成 {@code other}）；其余标签都是本类的枚举。
 * 全部序列在构造时预注册，热路径不拼标签。
 *
 * <p><b>按 zone 汇总</b>：gauge 是「本副本领导的各 zone 合计」。协调者每拍写入本 zone 的贡献，降级时 {@link #clearZone} 把该 zone 的贡献清掉
 * （同基线降级时 Reset 只有领导者维护的 gauge，scene_manager_service.go:108-112）。gauge 回调只读并发表、不阻塞。线程安全。
 */
public final class WorldChannelMetrics {

    static final String LEADER_ZONES = "xm.scene_manager.world.leader_zones";
    static final String TICKS = "xm.scene_manager.world.ticks";
    static final String TICK = "xm.scene_manager.world.tick";
    static final String CHANNELS = "xm.scene_manager.world.channels";
    static final String AUTOSCALE = "xm.scene_manager.world.autoscale";
    static final String REBALANCE_PENDING = "xm.scene_manager.rebalance.pending";
    static final String REBALANCE_MIGRATIONS = "xm.scene_manager.rebalance.migrations";

    /** World 表之外的图的 {@code scene_config} 标签值。 */
    static final String OTHER_CONFIG_TAG = "other";

    /** 一拍（快照 + 目录 + 规划 + 写入，几次 Redis 往返）的桶边界：固定 8 个，1ms～2.5s。 */
    private static final Duration[] TICK_BUCKETS = {
            Duration.ofMillis(1), Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofMillis(25),
            Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(500), Duration.ofMillis(2500)};

    /** 每 zone 每拍恰好计一次的结局（{@code xm_scene_manager_world_ticks_total{result}}）。 */
    public enum TickResult {
        /** 领导者跑完一拍（写入成功或无需写入）。 */
        OK,
        /** 本副本不是该 zone 的领导者（或锁的有效期已过）。 */
        NOT_LEADER,
        /** 写入 Lua 回 −1：锁已不是本令牌，放弃领导。 */
        FENCED,
        /** 写入 Lua 回 −2：版本号与快照不同，本拍作废、下拍重读。 */
        CONFLICT,
        /** 需要新建频道但发号租约无效，本拍没有新建（其它改动照写）。 */
        NO_LEASE,
        /** 没有活节点，补建整体跳过。 */
        NO_NODES,
        /** Redis 故障或坏数据：本拍什么也没应用。 */
        ERROR
    }

    /** {@code xm_scene_manager_world_channels{state}}。 */
    enum ChannelStateTag { ACTIVE, DRAINING, MISSING }

    private final AtomicInteger leaderZones = new AtomicInteger();
    private final Map<TickResult, Counter> ticks = new EnumMap<>(TickResult.class);
    private final Timer tickTimer;
    private final Map<AutoscaleAction, Map<AutoscaleOutcome, Counter>> autoscale = new EnumMap<>(AutoscaleAction.class);
    private final Map<MigrationReason, Map<MigrationOutcome, Counter>> migrations = new EnumMap<>(MigrationReason.class);
    /** zone → 该 zone 各图的频道数（键 = scene_config_id，{@link PlanResult#OTHER_CONFIG} = World 表之外）。 */
    private final Map<Integer, Map<Integer, ChannelCounts>> channelsByZone = new ConcurrentHashMap<>();
    /** zone → [node_gone, better_home] 积压。 */
    private final Map<Integer, int[]> pendingByZone = new ConcurrentHashMap<>();

    /** @param worldConfIds World 表的世界地图（{@code scene_config} 标签的全集，另加 {@code other}） */
    public WorldChannelMetrics(MeterRegistry registry, List<Integer> worldConfIds) {
        Gauge.builder(LEADER_ZONES, leaderZones, AtomicInteger::get)
                .description("本副本领导的 zone 数（0 = 跟随者）")
                .register(registry);
        for (TickResult result : TickResult.values()) {
            ticks.put(result, Counter.builder(TICKS)
                    .description("频道计划领导者的拍数（每 zone 每拍一次）")
                    .tag("result", tag(result))
                    .register(registry));
        }
        tickTimer = Timer.builder(TICK)
                .description("一拍耗时（计划快照 + 目录 + 规划 + 写入）")
                .serviceLevelObjectives(TICK_BUCKETS)
                .register(registry);
        for (int conf : worldConfIds) {
            registerChannelGauges(registry, conf, Integer.toUnsignedString(conf));
        }
        registerChannelGauges(registry, PlanResult.OTHER_CONFIG, OTHER_CONFIG_TAG);
        for (AutoscaleAction action : AutoscaleAction.values()) {
            Map<AutoscaleOutcome, Counter> byOutcome = new EnumMap<>(AutoscaleOutcome.class);
            for (AutoscaleOutcome outcome : AutoscaleOutcome.values()) {
                byOutcome.put(outcome, Counter.builder(AUTOSCALE)
                        .description("主世界频道自动扩缩容的决定与结局")
                        .tag("action", tag(action))
                        .tag("outcome", tag(outcome))
                        .register(registry));
            }
            autoscale.put(action, byOutcome);
        }
        for (MigrationReason reason : MigrationReason.values()) {
            Map<MigrationOutcome, Counter> byOutcome = new EnumMap<>(MigrationOutcome.class);
            for (MigrationOutcome outcome : MigrationOutcome.values()) {
                byOutcome.put(outcome, Counter.builder(REBALANCE_MIGRATIONS)
                        .description("频道迁移：planned = 写进计划，done = 旧记录收尾（node_gone 两者同时发生）")
                        .tag("reason", tag(reason))
                        .tag("outcome", tag(outcome))
                        .register(registry));
            }
            migrations.put(reason, byOutcome);
            int index = reason.ordinal();
            Gauge.builder(REBALANCE_PENDING, pendingByZone, m -> sumPending(m, index))
                    .description("迁移积压：node_gone = 宽限期内缺席节点上的记录数；better_home = 落点不对、本拍没迁的空频道数")
                    .tag("reason", tag(reason))
                    .register(registry);
        }
    }

    public void tick(TickResult result) {
        ticks.get(result).increment();
    }

    public Timer tickTimer() {
        return tickTimer;
    }

    public void leaderZones(int count) {
        leaderZones.set(count);
    }

    /** 计一批规划事件（只在写入成功或无需写入时调用）。 */
    public void record(List<PlanEvent> events) {
        for (PlanEvent event : events) {
            switch (event) {
                case PlanEvent.Autoscale a -> autoscale.get(a.action()).get(a.outcome()).increment();
                case PlanEvent.Migration m -> migrations.get(m.reason()).get(m.outcome()).increment();
            }
        }
    }

    /** 写入失败时：本拍里「开始执行」的扩缩容决定计为 error（基线 scale_in/error，world_autoscale.go:283-285）。 */
    public void recordFailed(List<PlanEvent> events) {
        for (PlanEvent event : events) {
            if (event instanceof PlanEvent.Autoscale a && a.outcome() == AutoscaleOutcome.OK) {
                autoscale.get(a.action()).get(AutoscaleOutcome.ERROR).increment();
            }
        }
    }

    /** 更新某 zone 的 gauge 贡献（领导者写入成功 / 无需写入之后）。 */
    public void zone(int zoneId, Map<Integer, ChannelCounts> counts, int nodeGonePending, int betterHomePending) {
        channelsByZone.put(zoneId, Map.copyOf(counts));
        int[] pending = new int[MigrationReason.values().length];
        pending[MigrationReason.NODE_GONE.ordinal()] = nodeGonePending;
        pending[MigrationReason.BETTER_HOME.ordinal()] = betterHomePending;
        pendingByZone.put(zoneId, pending);
    }

    /** 降级：清掉该 zone 的 gauge 贡献。 */
    public void clearZone(int zoneId) {
        channelsByZone.remove(zoneId);
        pendingByZone.remove(zoneId);
    }

    private void registerChannelGauges(MeterRegistry registry, int confKey, String confTag) {
        for (ChannelStateTag state : ChannelStateTag.values()) {
            Gauge.builder(CHANNELS, channelsByZone, m -> sumChannels(m, confKey, state))
                    .description("本副本领导的各 zone 的频道数（active / draining；missing = 节点已应用计划但没建出来）")
                    .tag("scene_config", confTag)
                    .tag("state", tag(state))
                    .register(registry);
        }
    }

    private static double sumChannels(Map<Integer, Map<Integer, ChannelCounts>> byZone, int confKey, ChannelStateTag state) {
        long sum = 0;
        for (Map<Integer, ChannelCounts> zone : byZone.values()) {
            ChannelCounts c = zone.get(confKey);
            if (c != null) {
                sum += switch (state) {
                    case ACTIVE -> c.active();
                    case DRAINING -> c.draining();
                    case MISSING -> c.missing();
                };
            }
        }
        return sum;
    }

    private static double sumPending(Map<Integer, int[]> byZone, int index) {
        long sum = 0;
        for (int[] pending : byZone.values()) {
            sum += pending[index];
        }
        return sum;
    }

    private static String tag(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    /** 测试用：当前各 zone 的频道数贡献的拷贝。 */
    Map<Integer, Map<Integer, ChannelCounts>> channelsByZoneSnapshot() {
        return new HashMap<>(channelsByZone);
    }
}
