package com.game.match.matcher;

import com.game.common.deadline.Deadline;
import com.game.common.id.Snowflake;
import com.game.match.MatchInstance;
import com.game.match.MatchProperties;
import com.game.match.id.MatchIds;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.port.PlayerStatusReader;
import com.game.match.testing.FakeBattleNodes;
import com.game.match.testing.FakeGatherLauncher;
import com.game.match.testing.FakePlayerStatus;
import com.game.match.ticket.QueueRef;
import com.game.match.ticket.TicketStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntPredicate;

/**
 * 凑单测试的台架：除票据存储之外的全部协作件（玩家状态、开局管线、battle 目录、发号租约、指标）都用内存替身，票据存储由各测试给
 * （内存实现，或真 Redis 实现）。缺省状态是「可以凑单」：目录里有一个可分配的 battle 节点、租约有效、开局许可充足。
 */
final class MatcherRig {

    /** 被测凑单所属的实例标识（凑单锁的持有者）。 */
    static final String INSTANCE = "inst-self";
    /** queued 票据的 TTL（缺省配置 6 h）。 */
    static final long TICKET_TTL_MS = 21_600_000;

    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final MetricLabels labels;
    final MatchMetrics metrics;
    final FakePlayerStatus players = new FakePlayerStatus();
    final FakeGatherLauncher gather = new FakeGatherLauncher();
    final FakeBattleNodes nodes = new FakeBattleNodes().add(FakeBattleNodes.node(1, "battle-inst-1", 21200));
    final AtomicBoolean leaseValid = new AtomicBoolean(true);
    final AtomicBoolean leaseLost = new AtomicBoolean(false);
    final MatchIds ids = new MatchIds(new Snowflake(7), leaseValid::get, leaseLost::get);
    /** 告警限频用的单调时钟（纳秒），测试手拨。 */
    final AtomicLong nanos = new AtomicLong(1_000_000_000L);

    /** @param dungeonExists 指标的 config 标签认哪些副本号（其余记 other） */
    MatcherRig(IntPredicate dungeonExists) {
        this.labels = new MetricLabels(dungeonExists);
        this.metrics = new MatchMetrics(meters, labels);
    }

    /** 全缺省的配置（PVE 组队 {@code {1: 5}}、容差曲线缺省、凑单锁 10 s）。 */
    static MatchProperties defaults() {
        return props(null, null);
    }

    /** @param pveTeamSizes null = 缺省 {@code {1: 5}}；{@code tolerance} null = 缺省曲线 */
    static MatchProperties props(Map<Integer, Integer> pveTeamSizes, MatchProperties.Tolerance tolerance) {
        MatchProperties.Rating rating = tolerance == null ? null : new MatchProperties.Rating(null, tolerance, null, null, null);
        return new MatchProperties(null, null, null, null, null, null, rating, pveTeamSizes, null, null, null, null);
    }

    QueueMatcher matcher(TicketStore store, MatchProperties props) {
        return matcher(store, players, props);
    }

    QueueMatcher matcher(TicketStore store, PlayerStatusReader statusReader, MatchProperties props) {
        return new QueueMatcher(store, statusReader, gather, nodes, ids, metrics, labels, props, new MatchInstance(INSTANCE), nanos::get);
    }

    static Deadline d() {
        return Deadline.after(5_000);
    }

    // ---------------------------------------------------------------- 指标读数

    /** 这条队列的深度 gauge；本实例从没写过为 null。 */
    Double depth(QueueRef queue) {
        return gauge("xm.match.queue.depth", queue);
    }

    /** 这条队列的饥饿秒数 gauge；本实例从没写过为 null。 */
    Double starved(QueueRef queue) {
        return gauge("xm.match.starved.anchor.wait", queue);
    }

    private Double gauge(String name, QueueRef queue) {
        Gauge gauge = meters.find(name).tags("mode", MetricLabels.mode(queue.mode()), "config", labels.config(queue.configId())).gauge();
        return gauge == null ? null : gauge.value();
    }

    double count(String name, String... tags) {
        return meters.get(name).tags(tags).counter().count();
    }

    /** 成组等待分布的样本数（每成一组记一个）。 */
    long waitSamples(QueueRef queue) {
        var summary = meters.find("xm.match.wait").tags("mode", MetricLabels.mode(queue.mode())).summary();
        return summary == null ? 0 : summary.count();
    }
}
