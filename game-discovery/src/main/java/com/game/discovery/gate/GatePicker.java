package com.game.discovery.gate;

import com.game.api.proto.GateNodeInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntUnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 从 gate 目录里挑一个接新玩家的 gate，并统一 90% 水位满载判定与区级承载封顶计算（对齐权威主仓
 * {@code shared/gatepick} 的 {@code Eligible} / {@code TwoChoices} / {@code WatermarkCapacity} / {@code AdmissionCapacity}）。
 *
 * <ol>
 *   <li>剔除连不上的条目：zone 不符、节点号为 0、无客户端地址；</li>
 *   <li>同一客户端地址只留 {@code started_at_ms}（无符号）最大的一条；</li>
 *   <li><b>一次分档</b>（不分层各自回落）：
 *       <ul>
 *         <li>第一档（完全合格）：未排空且未到 90% 水位（{@code player_count < ceil(max_connections * 90%)}；{@code max_connections == 0} 视为不限）；</li>
 *         <li>第二档（回落可连集合）：全部可连 gate 都在排空或已达 90% 水位时，保留全部可连 gate 并记告警（原因 {@code draining} / {@code full} / {@code mixed}）；</li>
 *       </ul>
 *   </li>
 *   <li><b>随机二选一</b>：比较负载 {@code unsigned(player_count) + inflight}，同负载取第一次抽中者。</li>
 * </ol>
 */
public final class GatePicker {

    private static final Logger log = LoggerFactory.getLogger(GatePicker.class);

    private static final int MAX_PORT = 65535;
    /** 满载水位百分比（90%）：留出 10% 缓冲吸收 5s 目录上报滞后。 */
    public static final int FULL_PERCENT = 90;

    /**
     * 一批 gate 到 90% 水位为止一共能接的人数；{@code bounded == false} 表示存在未声明上限的 gate，整体不封顶。
     */
    public record AdmissionCapacity(long capacity, boolean bounded) {
    }

    private GatePicker() {
    }

    /**
     * 返回自报上限为 {@code maxConnections} 的 gate 到达 90% 水位的人数（{@code ceil(max * 90 / 100)}）。
     * {@code maxConnections == 0} 表示未声明上限，返回 0。
     */
    public static long watermarkCapacity(int maxConnections) {
        if (maxConnections == 0) {
            return 0L;
        }
        return (Integer.toUnsignedLong(maxConnections) * FULL_PERCENT + 99L) / 100L;
    }

    /** 按上报人数判该 gate 是否已达到 90% 水位（未声明上限时恒 false；不计本进程在途票）。 */
    public static boolean isFull(GateNodeInfo gate) {
        int max = gate.getMaxConnections();
        return max != 0 && Integer.toUnsignedLong(gate.getPlayerCount()) >= watermarkCapacity(max);
    }

    /**
     * 计算本区在接客的 gate 到 90% 水位一共能接的人数（总量，不扣在线人数）。
     * 仅统计可连且同地址去重后的最高非空档：优先未排空 gate；若全在排空则统计全部可连 gate；无 gate 返回 {@code (0, true)}；
     * 计入集合中任一 gate 未声明上限（{@code max_connections == 0}）则返回 {@code (0, false)} 不封顶。
     */
    public static AdmissionCapacity admissionCapacity(int zoneId, List<GateNodeInfo> gates) {
        List<GateNodeInfo> connectable = connectableNewest(zoneId, gates);
        if (connectable.isEmpty()) {
            return new AdmissionCapacity(0L, true);
        }
        List<GateNodeInfo> serving = connectable.stream().filter(g -> !g.getDraining()).toList();
        List<GateNodeInfo> counted = serving.isEmpty() ? connectable : serving;
        long total = 0L;
        for (GateNodeInfo gate : counted) {
            int max = gate.getMaxConnections();
            if (max == 0) {
                return new AdmissionCapacity(0L, false);
            }
            total += watermarkCapacity(max);
        }
        return new AdmissionCapacity(total, true);
    }

    /** @return 选中的 gate；没有任何可连接的条目时为空。随机源是 {@link ThreadLocalRandom}（并发安全）。 */
    public static Optional<GateNodeInfo> pick(int zoneId, List<GateNodeInfo> gates) {
        return pick(zoneId, gates, null, bound -> ThreadLocalRandom.current().nextInt(bound));
    }

    /** 带本进程在途票记账的随机二选一选台。 */
    public static Optional<GateNodeInfo> pick(int zoneId, List<GateNodeInfo> gates, GateInflight inflight) {
        return pick(zoneId, gates, inflight, bound -> ThreadLocalRandom.current().nextInt(bound));
    }

    /** 同 {@link #pick(int, List)}，随机源显式传入（测试用）。 */
    static Optional<GateNodeInfo> pick(int zoneId, List<GateNodeInfo> gates, IntUnaryOperator intn) {
        return pick(zoneId, gates, null, intn);
    }

    /** 同 {@link #pick(int, List, GateInflight)}，随机源显式传入（测试用）。 */
    public static Optional<GateNodeInfo> pick(int zoneId, List<GateNodeInfo> gates, GateInflight inflight,
                                              IntUnaryOperator intn) {
        List<GateNodeInfo> candidates = eligible(zoneId, gates);
        int n = candidates.size();
        if (n == 0) {
            return Optional.empty();
        }
        if (n == 1) {
            return Optional.of(candidates.get(0));
        }
        Map<Integer, Integer> inflightCounts = inflight == null ? Map.of() : inflight.snapshot();
        int i = intn.applyAsInt(n);
        // 第二个从去掉 i 之后的 n-1 个里均匀抽，保证两个不同。
        int j = intn.applyAsInt(n - 1);
        if (j >= i) {
            j++;
        }
        long loadI = effectiveLoad(candidates.get(i), inflightCounts);
        long loadJ = effectiveLoad(candidates.get(j), inflightCounts);
        if (Long.compareUnsigned(loadJ, loadI) < 0) {
            i = j;
        }
        return Optional.of(candidates.get(i));
    }

    private static long effectiveLoad(GateNodeInfo gate, Map<Integer, Integer> inflightCounts) {
        return Integer.toUnsignedLong(gate.getPlayerCount()) + inflightCounts.getOrDefault(gate.getNodeId(), 0);
    }

    /**
     * 规则 1–3 之后剩下的 gate（一次分档：完全合格 &gt; 回落全部可连 gate）；没有时为空表。
     * 排队放行循环按它挑台并在批内叠加已分配人数与在途票。
     */
    public static List<GateNodeInfo> eligible(int zoneId, List<GateNodeInfo> gates) {
        List<GateNodeInfo> connectable = connectableNewest(zoneId, gates);
        if (connectable.isEmpty()) {
            return List.of();
        }
        int drainingCount = 0;
        int fullCount = 0;
        List<GateNodeInfo> healthy = new ArrayList<>(connectable.size());
        for (GateNodeInfo g : connectable) {
            if (g.getDraining()) {
                drainingCount++;
            } else if (isFull(g)) {
                fullCount++;
            } else {
                healthy.add(g);
            }
        }
        if (!healthy.isEmpty()) {
            return healthy;
        }
        String reason = fullCount == 0 ? "draining" : drainingCount == 0 ? "full" : "mixed";
        log.warn("zone={} 的 {} 台可连 gate 无完全合格候选（draining={} full={} reason={}），回落全部可连 gate 继续分配",
                zoneId, connectable.size(), drainingCount, fullCount, reason);
        return connectable;
    }

    /** 规则 1–2：过滤可连条目并按客户端地址去重只留最新启动者。 */
    public static List<GateNodeInfo> connectableNewest(int zoneId, List<GateNodeInfo> gates) {
        return newestPerAddress(zoneId, gates.stream().filter(g -> isConnectable(zoneId, g)).toList());
    }

    /**
     * 规则 2：同一客户端地址只留 {@code started_at_ms}（uint64，按无符号比较）最大的条目，并列最大的都留下。
     */
    private static List<GateNodeInfo> newestPerAddress(int zoneId, List<GateNodeInfo> gates) {
        Map<String, Long> newest = new LinkedHashMap<>();
        for (GateNodeInfo gate : gates) {
            newest.merge(address(gate), gate.getStartedAtMs(), (a, b) -> Long.compareUnsigned(a, b) >= 0 ? a : b);
        }
        if (newest.size() == gates.size()) {
            return gates;
        }
        List<GateNodeInfo> out = new ArrayList<>(newest.size());
        for (GateNodeInfo gate : gates) {
            if (gate.getStartedAtMs() == newest.get(address(gate))) {
                out.add(gate);
            } else {
                log.debug("跳过同一客户端地址上更早启动的 gate 条目（进程已重启、旧条目还没过期） zone={} node={} addr={} startedAt={} newest={}",
                        zoneId, gate.getNodeId(), address(gate), Long.toUnsignedString(gate.getStartedAtMs()),
                        Long.toUnsignedString(newest.get(address(gate))));
            }
        }
        return out;
    }

    private static String address(GateNodeInfo gate) {
        return gate.getClientHost() + ":" + Integer.toUnsignedString(gate.getClientPort());
    }

    private static boolean isConnectable(int zoneId, GateNodeInfo gate) {
        if (gate.getZoneId() != zoneId) {
            log.debug("跳过 zone 不符的 gate 条目 期望zone={} 条目zone={} node={}", zoneId, gate.getZoneId(), gate.getNodeId());
            return false;
        }
        if (gate.getNodeId() == 0) {
            log.debug("跳过节点号为 0 的 gate 条目 zone={}", zoneId);
            return false;
        }
        int port = gate.getClientPort();
        if (gate.getClientHost().isBlank() || port <= 0 || port > MAX_PORT) {
            log.debug("跳过没有客户端地址的 gate 条目 zone={} node={} host='{}' port={}",
                    zoneId, gate.getNodeId(), gate.getClientHost(), Integer.toUnsignedLong(port));
            return false;
        }
        return true;
    }
}
