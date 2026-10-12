package com.game.discovery.gate;

import com.game.api.proto.GateNodeInfo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 从 gate 目录里挑一个接新玩家的 gate。纯函数（只打日志），规则与 mmorpg {@code loginqueue.PickGate} +
 * {@code clientendpoint.DedupeNewest} + {@code FilterDrainingGates} 一致：
 * <ol>
 *   <li>先剔除连不上的条目：zone 不符、节点号为 0、没有客户端地址（host 空或端口不在 1..65535）——
 *       把这种地址发给客户端只会让它连不上；</li>
 *   <li><b>同一个客户端地址（host:port）只留 {@code started_at_ms} 最大的一条</b>（批次 5.4，zone-travel-spec §5.4 第 4 步、Q10）：
 *       gate 崩溃后在同一地址重启、换了节点号，旧进程的条目要等目录 TTL 才消失；这段时间里旧条目的人数停在崩溃时（往往更少），
 *       按人数挑会挑中它，而签给旧节点号的令牌在新进程上验不过（{@code token not for this gate}）。缺这个字段的旧版本条目读出来是 0（最旧）。
 *       并列最大（含两条都缺字段）时都留下、交给第 4 条排序——没有依据判断哪条陈旧，宁可保持去重之前的行为；</li>
 *   <li>再剔除排空中的 gate；<b>全部都在排空时忽略排空标记</b>（与 mmorpg 同向：宁可放人进排空中的 gate，
 *       也不因误标整区而全服登不上），并告警；</li>
 *   <li>按 (在线人数, 节点号) 升序取最小（uint32 按无符号比较），平局结果确定。</li>
 * </ol>
 * gateway 的登录分配、放行循环与 scene-manager 的跨 zone 重定向共用这一份规则。
 */
public final class GatePicker {

    private static final Logger log = LoggerFactory.getLogger(GatePicker.class);

    private static final int MAX_PORT = 65535;

    /** 人数少者优先；人数相同取节点号小者，保证同一份目录总挑同一台，便于对日志。 */
    static final Comparator<GateNodeInfo> LEAST_LOADED = Comparator
            .comparingLong((GateNodeInfo g) -> Integer.toUnsignedLong(g.getPlayerCount()))
            .thenComparingLong(g -> Integer.toUnsignedLong(g.getNodeId()));

    private GatePicker() {
    }

    /** @return 选中的 gate；没有任何可连接的条目时为空 */
    public static Optional<GateNodeInfo> pick(int zoneId, List<GateNodeInfo> gates) {
        return eligible(zoneId, gates).stream().min(LEAST_LOADED);
    }

    /** 规则 1–3 之后剩下的 gate（放行循环要在一批里按自己记的已分配人数摊开，所以单独给出）；没有时为空表。 */
    public static List<GateNodeInfo> eligible(int zoneId, List<GateNodeInfo> gates) {
        List<GateNodeInfo> connectable = newestPerAddress(zoneId,
                gates.stream().filter(g -> isConnectable(zoneId, g)).toList());
        if (connectable.isEmpty()) {
            return List.of();
        }
        List<GateNodeInfo> accepting = connectable.stream().filter(g -> !g.getDraining()).toList();
        if (accepting.isEmpty()) {
            log.warn("zone={} 的 {} 台 gate 全部标记为排空，忽略排空标记继续分配；请检查是否误把整区标成排空",
                    zoneId, connectable.size());
            accepting = connectable;
        }
        return accepting;
    }

    /**
     * 规则 2：同一客户端地址只留 {@code started_at_ms}（uint64，按无符号比较）最大的条目，并列最大的都留下。
     * 地址按目录里的原串比较（host 与端口都不做规范化）；输出保持输入里各条目的相对顺序。
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
                // 每次分配都会走到（直到旧条目过期，最多一个目录 TTL），所以同其它跳过一样只记 debug。
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
