package com.game.gateway.drain;

import com.game.api.proto.GateNodeInfo;
import com.game.discovery.drain.GateDrainMarks;
import com.game.gateway.store.ZoneRow;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gate 排空判定（同 mmorpg gatedrain_monitor）：周期地看每个区每台 gate——
 * <ul>
 *   <li>没在排空：顺手清掉残留的 drained；排空标记是旧实例留下的（节点号已被新实例复用）：比较并删除，新实例照常接客；</li>
 *   <li>在排空：按 {@link #evaluate} 判，可下线就写 drained（理由 {@code below_threshold} / {@code deadline}，TTL 跟着排空标记，
 *       只在标记仍是这一份时写）；不再满足（比如人又回来了）就撤掉之前写的 drained——drained 的意思是「现在删实例是安全的」。</li>
 * </ul>
 * 「已等多久」= Redis 服务器时间 − 标记起点（同一个时钟，不受各副本时钟偏差影响）。
 *
 * <p><b>只自动化判定，不踢人</b>（同基线）：那台 gate 上的玩家最终靠「下线 → 客户端重连 → assign-gate 已剔除排空中的 gate」
 * 完成改派；现成的踢人提示是「账号在别处登录」，给计划内维护的玩家看是误导。什么时候删实例由运维看 drained 决定。
 *
 * <p>每个 xm-gateway 都跑（写入幂等）。{@link #tick()} 在单个调度线程上调用；任何异常只记日志。
 */
public final class GateDrainMonitor {

    private static final Logger log = LoggerFactory.getLogger(GateDrainMonitor.class);

    /** 一台在排空的 gate 的判定结果。 */
    public record Verdict(boolean drained, String reason, long waitedSec) {
    }

    private final Supplier<List<ZoneRow>> zones;
    private final IntFunction<List<GateNodeInfo>> gates;
    private final GateDrainMarks marks;
    private final GateDrainSettings settings;
    private final LongSupplier serverTimeSec;

    /**
     * @param gates         区 → 该区 gate 目录（<b>不要</b>过滤排空中的——要看的就是它们）
     * @param serverTimeSec Redis 服务器时间（秒，与标记起点同一个时钟）
     */
    public GateDrainMonitor(Supplier<List<ZoneRow>> zones, IntFunction<List<GateNodeInfo>> gates, GateDrainMarks marks,
                            GateDrainSettings settings, LongSupplier serverTimeSec) {
        this.zones = zones;
        this.gates = gates;
        this.marks = marks;
        this.settings = settings;
        this.serverTimeSec = serverTimeSec;
    }

    /**
     * 纯判定（同基线 EvaluateGateDrain）：在线 ≤ 阈值 → {@code below_threshold}；deadline 为正且已等满 → {@code deadline}；
     * 否则还没排空。打标记时刻在将来（来自别的时钟）按刚打上算，宁可多等也不因一个负数把人断了。
     */
    public static Verdict evaluate(long online, long markedAtSec, long nowSec, GateDrainSettings settings) {
        long waited = Math.max(0, nowSec - markedAtSec);
        if (online <= settings.drainedBelowPlayers()) {
            return new Verdict(true, GateDrainMarks.REASON_BELOW_THRESHOLD, waited);
        }
        long deadline = settings.deadline().toSeconds();
        if (deadline > 0 && waited >= deadline) {
            return new Verdict(true, GateDrainMarks.REASON_DEADLINE, waited);
        }
        return new Verdict(false, null, waited);
    }

    /** 一轮。 */
    public void tick() {
        try {
            Long now = null;
            for (ZoneRow zone : zones.get()) {
                try {
                    List<GateNodeInfo> list = gates.apply(zone.zoneId());
                    if (list.isEmpty()) {
                        continue;
                    }
                    if (now == null) {
                        now = serverTimeSec.getAsLong();
                    }
                    tickZone(zone.zoneId(), list, now);
                } catch (RuntimeException e) {
                    log.warn("gate 排空判定出错 zone={}: {}", zone.zoneId(), e.toString());
                }
            }
        } catch (RuntimeException e) {
            log.warn("gate 排空判定这一轮出错，下一轮照常: {}", e.toString());
        } catch (Throwable t) {
            log.error("gate 排空判定这一轮出现严重错误，下一轮照常", t);
        }
    }

    private void tickZone(int zoneId, List<GateNodeInfo> list, long now) {
        List<Integer> nodes = list.stream().map(GateNodeInfo::getNodeId).toList();
        Map<Integer, GateDrainMarks.Mark> draining = marks.draining(zoneId, nodes);
        Map<Integer, String> drained = marks.drained(zoneId, nodes);
        for (GateNodeInfo gate : list) {
            int node = gate.getNodeId();
            GateDrainMarks.Mark mark = draining.get(node);
            if (mark != null && !mark.appliesTo(gate.getInstanceId())) {
                // 旧实例留下的标记：节点号已归新实例，比较并删除（不误删运维刚给新实例打的标记）
                if (marks.clearIf(zoneId, node, mark)) {
                    log.info("清掉旧实例留下的排空标记 zone={} node_id={} 标记实例={} 当前实例={}", zoneId, node,
                            mark.instanceId(), gate.getInstanceId());
                }
                continue;
            }
            if (mark == null) {
                if (drained.containsKey(node)) {
                    // 运维取消了排空：不清的话这台 gate 下次会被误判成「随时可以删」
                    marks.clearDrained(zoneId, node);
                }
                continue;
            }
            long online = Integer.toUnsignedLong(gate.getPlayerCount());
            Verdict verdict = evaluate(online, mark.markedAtSec(), now, settings);
            if (!verdict.drained()) {
                if (drained.containsKey(node)) {
                    marks.clearDrained(zoneId, node);
                    log.warn("gate 不再满足排空条件，撤掉 drained zone={} node_id={} 在线={}", zoneId, node, online);
                } else {
                    log.info("gate 排空中 zone={} node_id={} 在线={} 已等 {} s", zoneId, node, online, verdict.waitedSec());
                }
                continue;
            }
            if (verdict.reason().equals(drained.get(node))) {
                continue;
            }
            GateDrainMarks.DrainedWrite write = marks.markDrained(zoneId, node, mark, verdict.reason());
            if (write == GateDrainMarks.DrainedWrite.NO_TTL) {
                log.error("gate 排空标记没有 TTL（不会过期，这台 gate 会永远分不到玩家），必须撤销重打或补 EXPIRE zone={} node_id={}",
                        zoneId, node);
            } else if (write == GateDrainMarks.DrainedWrite.MARK_CHANGED) {
                log.info("没写 drained：排空标记在判定之后变了 zone={} node_id={}", zoneId, node);
            } else if (GateDrainMarks.REASON_DEADLINE.equals(verdict.reason())) {
                log.error("gate 排空到期仍有玩家在线，判定可下线（下线会让这批人断线重连） zone={} node_id={} 在线={} 已等 {} s",
                        zoneId, node, online, verdict.waitedSec());
            } else {
                log.info("gate 已排空 zone={} node_id={} 在线={} 已等 {} s", zoneId, node, online, verdict.waitedSec());
            }
        }
    }
}
