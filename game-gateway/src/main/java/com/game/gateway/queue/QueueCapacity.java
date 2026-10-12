package com.game.gateway.queue;

import com.game.api.proto.GateNodeInfo;
import com.game.gateway.store.ZoneRow;
import java.util.List;

/**
 * 区的放行预算（同 mmorpg loginqueue.FreeSlots 的前半）：budget = max(0, 容量 − 全区 gate 在线人数)；
 * 空位 = 预算 − 未过期占位，在 Lua 里原子地算（快速通道与放行循环都一样，见 {@link LoginQueue}）。
 * 容量取区服目录的 {@code capacity}（与负载档同一个分母）；为 0 时退回软上限 floor(max(在线, 1) × {@code softCapMultiplier})。
 *
 * <p>与基线不同：基线容量取 login 配置的 ZoneCapacityOverride，没配就按软上限——新区（在线 0）每轮只放 1 人、按 1.5 倍几何增长；
 * Java 用运维可在线改的区服容量，缺省 5000。纯函数，线程安全。
 */
public final class QueueCapacity {

    private final double softCapMultiplier;

    public QueueCapacity(double softCapMultiplier) {
        this.softCapMultiplier = Math.max(1.0, softCapMultiplier);
    }

    /** 全区 gate 在线人数。 */
    public static long online(List<GateNodeInfo> gates) {
        long online = 0;
        for (GateNodeInfo gate : gates) {
            online += Integer.toUnsignedLong(gate.getPlayerCount());
        }
        return online;
    }

    public long capacity(ZoneRow zone, long online) {
        if (zone.capacity() > 0) {
            return zone.capacity();
        }
        return (long) Math.floor(Math.max(online, 1) * softCapMultiplier);
    }

    /** 放行预算（容量 − 在线）：占位数在 Lua 里原子地和它比。 */
    public long budget(ZoneRow zone, List<GateNodeInfo> gates) {
        long online = online(gates);
        return Math.max(0, capacity(zone, online) - online);
    }
}
