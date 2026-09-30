package com.game.gateway.gate;

import com.game.api.proto.GateNodeInfo;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 从 gate 目录里挑一个接新玩家的 gate。纯函数（只打日志），规则与 mmorpg {@code loginqueue.PickGate} +
 * {@code FilterDrainingGates} 一致：
 * <ol>
 *   <li>先剔除连不上的条目：zone 不符、节点号为 0、没有客户端地址（host 空或端口不在 1..65535）——
 *       把这种地址发给客户端只会让它连不上；</li>
 *   <li>再剔除排空中的 gate；<b>全部都在排空时忽略排空标记</b>（与 mmorpg 同向：宁可放人进排空中的 gate，
 *       也不因误标整区而全服登不上），并告警；</li>
 *   <li>按 (在线人数, 节点号) 升序取最小（uint32 按无符号比较），平局结果确定。</li>
 * </ol>
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
        List<GateNodeInfo> connectable = gates.stream().filter(g -> isConnectable(zoneId, g)).toList();
        if (connectable.isEmpty()) {
            return Optional.empty();
        }
        List<GateNodeInfo> accepting = connectable.stream().filter(g -> !g.getDraining()).toList();
        if (accepting.isEmpty()) {
            log.warn("zone={} 的 {} 台 gate 全部标记为排空，忽略排空标记继续分配；请检查是否误把整区标成排空",
                    zoneId, connectable.size());
            accepting = connectable;
        }
        return accepting.stream().min(LEAST_LOADED);
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
