package com.game.gateway.zone;

import com.game.api.proto.GateNodeInfo;
import com.game.api.proto.SceneNodeInfo;
import com.game.discovery.gate.GatePicker;
import com.game.gateway.store.ZoneRow;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 区服健康探测与负载档（同 mmorpg ZoneHealthProbeService），只影响区服列表的展示与自动推荐，不影响准入：
 * <ul>
 *   <li>某区一台 gate 都没有 → {@link Health#DOWN}（列表里手工 OPEN 的区显示 MAINTENANCE）；有 gate 没 scene →
 *       {@link Health#DEGRADED}（仍显示 OPEN）；都有 → {@link Health#HEALTHY}；</li>
 *   <li>负载档分母 = {@code min(zone.capacity, Σ 本区 gate 的 ceil(max_connections * 90%))}；目录容量 ≤ 0 时仅看 gate 水位和；
 *       任一 gate 未声明上限（{@code max_connections == 0}）时不封顶。&lt; 0.5 SMOOTH、&lt; 0.8 BUSY、否则 FULL；分母 ≤ 0 → SMOOTH；</li>
 *   <li>同时记录负载率 {@code online / effectiveCapacity} 供区服列表自动补推荐时按最空区排序。</li>
 * </ul>
 */
public final class ZoneHealthProbe {

    public static final Duration INTERVAL = Duration.ofSeconds(5);
    public static final Duration STATUS_TTL = Duration.ofSeconds(15);
    static final int ALERT_AFTER_FAILURES = 3;

    private static final Logger log = LoggerFactory.getLogger(ZoneHealthProbe.class);

    /** 自动探测出的健康状态。 */
    public enum Health {
        UNKNOWN, HEALTHY, DEGRADED, DOWN
    }

    /** 负载档（区服列表的 {@code load_level}，枚举名原样下发）。 */
    public enum LoadLevel {
        SMOOTH, BUSY, FULL
    }

    private record Snapshot(Map<Integer, Health> health, Map<Integer, LoadLevel> load,
                            Map<Integer, Double> loadRatio, long capturedAtMs) {
    }

    private final Supplier<List<ZoneRow>> zones;
    private final IntFunction<List<GateNodeInfo>> gates;
    private final IntFunction<List<SceneNodeInfo>> scenes;
    private final LongSupplier nowMs;
    private final Duration statusTtl;
    private volatile Snapshot snapshot;
    private int consecutiveFailures;

    public ZoneHealthProbe(Supplier<List<ZoneRow>> zones, IntFunction<List<GateNodeInfo>> gates,
                           IntFunction<List<SceneNodeInfo>> scenes, LongSupplier nowMs, Duration statusTtl) {
        this.zones = zones;
        this.gates = gates;
        this.scenes = scenes;
        this.nowMs = nowMs;
        this.statusTtl = statusTtl;
    }

    /** 探测一轮。任何失败都只记日志、保留上一份快照（不向调度器抛出）。 */
    public void probe() {
        try {
            Map<Integer, Health> health = new HashMap<>();
            Map<Integer, LoadLevel> load = new HashMap<>();
            Map<Integer, Double> ratios = new HashMap<>();
            for (ZoneRow zone : zones.get()) {
                List<GateNodeInfo> zoneGates = gates.apply(zone.zoneId());
                health.put(zone.zoneId(), zoneGates.isEmpty() ? Health.DOWN
                        : scenes.apply(zone.zoneId()).isEmpty() ? Health.DEGRADED : Health.HEALTHY);
                long online = 0;
                for (GateNodeInfo gate : zoneGates) {
                    online += Integer.toUnsignedLong(gate.getPlayerCount());
                }
                long cap = effectiveCapacity(zone, zoneGates);
                load.put(zone.zoneId(), loadLevel(online, cap));
                ratios.put(zone.zoneId(), cap <= 0 ? 0.0 : (double) online / cap);
            }
            snapshot = new Snapshot(Map.copyOf(health), Map.copyOf(load), Map.copyOf(ratios), nowMs.getAsLong());
            consecutiveFailures = 0;
        } catch (RuntimeException e) {
            consecutiveFailures++;
            String serving = fresh(snapshot) ? "上一份快照" : "UNKNOWN";
            if (consecutiveFailures >= ALERT_AFTER_FAILURES) {
                log.error("区服健康探测连续失败 {} 次，展示 {}", consecutiveFailures, serving, e);
            } else {
                log.warn("区服健康探测失败（第 {} 次），展示 {}: {}", consecutiveFailures, serving, e.toString());
            }
        }
    }

    /**
     * 计算列表负载档的分母：取 {@code zone.capacity} 与本区全部可连 gate 到 90% 水位总承载的较小者；
     * 目录容量 ≤ 0 时仅看 gate 水位和；任一 gate 未声明上限（0）时不封顶。
     */
    static long effectiveCapacity(ZoneRow zone, List<GateNodeInfo> zoneGates) {
        List<GateNodeInfo> connectable = GatePicker.connectableNewest(zone.zoneId(), zoneGates);
        long gateCap = 0L;
        boolean gateBounded = !connectable.isEmpty();
        for (GateNodeInfo gate : connectable) {
            int max = gate.getMaxConnections();
            if (max == 0) {
                gateBounded = false;
                break;
            }
            gateCap += GatePicker.watermarkCapacity(max);
        }
        long configured = zone.capacity();
        if (gateBounded) {
            return configured > 0 ? Math.min(configured, gateCap) : gateCap;
        }
        return configured;
    }

    static LoadLevel loadLevel(long online, long capacity) {
        if (capacity <= 0) {
            return LoadLevel.SMOOTH;
        }
        double ratio = (double) online / capacity;
        if (ratio < 0.5) {
            return LoadLevel.SMOOTH;
        }
        return ratio < 0.8 ? LoadLevel.BUSY : LoadLevel.FULL;
    }

    public Health health(int zoneId) {
        Snapshot current = snapshot;
        return fresh(current) ? current.health().getOrDefault(zoneId, Health.UNKNOWN) : Health.UNKNOWN;
    }

    /** 负载档；快照过期 / 没有这个区为空。 */
    public Optional<LoadLevel> loadLevel(int zoneId) {
        Snapshot current = snapshot;
        return fresh(current) ? Optional.ofNullable(current.load().get(zoneId)) : Optional.empty();
    }

    /** 负载率（online / effectiveCapacity）；快照过期 / 没有这个区为空。 */
    public OptionalDouble loadRatio(int zoneId) {
        Snapshot current = snapshot;
        if (!fresh(current)) {
            return OptionalDouble.empty();
        }
        Double r = current.loadRatio().get(zoneId);
        return r == null ? OptionalDouble.empty() : OptionalDouble.of(r);
    }

    private boolean fresh(Snapshot current) {
        if (current == null) {
            return false;
        }
        long age = nowMs.getAsLong() - current.capturedAtMs();
        return age >= 0 && age <= statusTtl.toMillis();
    }
}
