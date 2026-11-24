package com.game.gateway.queue;

import com.game.api.proto.GateNodeInfo;
import com.game.discovery.gate.GatePicker;
import com.game.gateway.store.ZoneRow;
import java.util.List;

/**
 * 区的放行预算（同 mmorpg loginqueue.FreeSlots + gatepick.AdmissionCapacity）：
 * {@code effectiveCapacity = min(配置/软上限容量, 在接客 gate 到 90% 水位的总承载)}，
 * {@code budget = max(0, effectiveCapacity - 全区 gate 在线人数)}。
 *
 * <p>在线人数始终统计全部候选 gate（含排空中 gate），避免排空期间少算在线而多放人；任一计入的 gate
 * 未声明上限（{@code max_connections == 0}）时不封顶。纯函数，线程安全。
 */
public final class QueueCapacity {

    private final double softCapMultiplier;

    public QueueCapacity(double softCapMultiplier) {
        this.softCapMultiplier = Math.max(1.0, softCapMultiplier);
    }

    /** 全区 gate 在线人数（包含排空中的 gate）。 */
    public static long online(List<GateNodeInfo> gates) {
        long online = 0;
        for (GateNodeInfo gate : gates) {
            online += Integer.toUnsignedLong(gate.getPlayerCount());
        }
        return online;
    }

    /** 仅按区服目录配置或软上限算出的未封顶容量。 */
    public long capacity(ZoneRow zone, long online) {
        if (zone.capacity() > 0) {
            return zone.capacity();
        }
        return (long) Math.floor(Math.max(online, 1) * softCapMultiplier);
    }

    /** 叠加 gate 90% 水位承载封顶后的实际区容量。 */
    public long effectiveCapacity(ZoneRow zone, List<GateNodeInfo> gates) {
        long online = online(gates);
        long configured = capacity(zone, online);
        GatePicker.AdmissionCapacity gateCap = GatePicker.admissionCapacity(zone.zoneId(), gates);
        return gateCap.bounded() ? Math.min(configured, gateCap.capacity()) : configured;
    }

    /** 放行预算（有效容量 − 在线）：占位数在 Lua 里原子地和它比。 */
    public long budget(ZoneRow zone, List<GateNodeInfo> gates) {
        long online = online(gates);
        return Math.max(0, effectiveCapacity(zone, gates) - online);
    }
}
