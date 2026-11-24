package com.game.gateway.zone;

import com.game.gateway.store.GatewayStore;
import com.game.gateway.store.ZoneRow;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntToLongFunction;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 按区注册角色软上限自动暂停创角守卫（对齐权威主仓 {@code RoleCapacityGuard}）。
 *
 * <p>每 10 秒轮询一次区服目录，仅对「{@code role_capacity > 0 && !create_role_closed}」的区统计历史已建角色数
 * （删角不减）；达到或超过上限时通过 {@link GatewayStore#closeCreateRoleIfCapacityReached} 比较并写原子关上创角开关
 * （运维中途调高上限或置 0 时本次关闭不生效）。只关不开，查询异常 fail-open 不关，并导出低基数观测指标。
 */
public final class RoleCapacityGuard {

    public static final Duration INTERVAL = Duration.ofSeconds(10);

    private static final Logger log = LoggerFactory.getLogger(RoleCapacityGuard.class);

    private final Supplier<List<ZoneRow>> zones;
    private final IntToLongFunction roleCounter;
    private final GatewayStore store;
    private final MeterRegistry meters;
    private final Counter checkOk;
    private final Counter checkClosed;
    private final Counter checkError;
    private final ConcurrentHashMap<Integer, AtomicLong> observedRoleCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, AtomicInteger> observedRoleCaps = new ConcurrentHashMap<>();

    public RoleCapacityGuard(Supplier<List<ZoneRow>> zones, IntToLongFunction roleCounter,
                             GatewayStore store, MeterRegistry meters) {
        this.zones = Objects.requireNonNull(zones, "zones");
        this.roleCounter = Objects.requireNonNull(roleCounter, "roleCounter");
        this.store = Objects.requireNonNull(store, "store");
        this.meters = Objects.requireNonNull(meters, "meters");
        this.checkOk = Counter.builder("game.gateway.role.capacity.check")
                .tag("outcome", "ok").description("角色上限巡检次数").register(meters);
        this.checkClosed = Counter.builder("game.gateway.role.capacity.check")
                .tag("outcome", "closed").description("角色上限巡检自动关停创角次数").register(meters);
        this.checkError = Counter.builder("game.gateway.role.capacity.check")
                .tag("outcome", "error").description("角色上限巡检查询失败次数").register(meters);
    }

    /** 巡检一轮；任何异常只记日志并累加 error 计数，不抛出中断调度线程。 */
    public void tick() {
        List<ZoneRow> list;
        try {
            list = zones.get();
        } catch (RuntimeException e) {
            checkError.increment();
            log.warn("角色上限守卫读取区服目录失败: {}", e.toString());
            return;
        }
        for (ZoneRow zone : list) {
            int zoneId = zone.zoneId();
            int cap = zone.roleCapacity();
            AtomicInteger capGauge = observedRoleCaps.get(zoneId);
            if (capGauge != null) {
                capGauge.set(Math.max(0, cap));
            }
            if (cap <= 0 || zone.createRoleClosed()) {
                continue;
            }
            registerCapacityGauge(zoneId, cap);
            long count;
            try {
                count = roleCounter.applyAsLong(zoneId);
            } catch (RuntimeException e) {
                checkError.increment();
                log.warn("角色上限守卫查询区角色数失败（不关停创角） zone={}: {}",
                        Integer.toUnsignedLong(zoneId), e.toString());
                continue;
            }
            registerCountGauge(zoneId, count);
            if (count >= cap) {
                boolean closed = store.closeCreateRoleIfCapacityReached(zoneId, cap);
                if (closed) {
                    checkClosed.increment();
                    log.warn("区服注册角色数达到软上限，自动暂停创建新角色 zone={} role_count={} role_capacity={}",
                            Integer.toUnsignedLong(zoneId), count, cap);
                } else {
                    checkOk.increment();
                }
            } else {
                checkOk.increment();
            }
        }
    }

    private void registerCountGauge(int zoneId, long value) {
        observedRoleCounts.computeIfAbsent(zoneId, id -> {
            AtomicLong holder = new AtomicLong(value);
            Gauge.builder("game.gateway.zone.role.count", holder, AtomicLong::get)
                    .tag("zone", Long.toUnsignedString(Integer.toUnsignedLong(id)))
                    .description("本区历史已建角色总数")
                    .register(meters);
            return holder;
        }).set(value);
    }

    private void registerCapacityGauge(int zoneId, int cap) {
        observedRoleCaps.computeIfAbsent(zoneId, id -> {
            AtomicInteger holder = new AtomicInteger(cap);
            Gauge.builder("game.gateway.zone.role.capacity", holder, AtomicInteger::get)
                    .tag("zone", Long.toUnsignedString(Integer.toUnsignedLong(id)))
                    .description("本区注册角色软上限")
                    .register(meters);
            return holder;
        }).set(cap);
    }
}
