package com.game.gateway.zone;

import com.game.gateway.store.ZoneRow;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 区服目录的读面（{@code zone_config}，运维经 xm-data 改）：整张表一份快照缓存 {@link #TTL}，同基线准入缓存的 1 s——
 * 运维改状态约 1 s 内对分配 gate / HTTP 登录 / 区服列表生效。
 *
 * <p>与基线（按 zone 各缓存一项、最多 4096 项、同 zone 并发未命中合并、失败不缓存）不同：区服表只有几十行，整表一次读回，
 * 随便传的 zone_id 也不会撑大缓存或打穿到库；读失败同样缓存 1 s（{@link TtlCache}），数据库故障期间请求立即失败，
 * 调用方 fail-closed。
 *
 * <p>线程安全；会阻塞（MySQL），只在允许阻塞的线程上调用。
 */
public final class ZoneDirectory {

    public static final Duration TTL = Duration.ofSeconds(1);

    private final TtlCache<List<ZoneRow>> cache;

    /** @param loader 读整张区服表（{@code GatewayStore::zones}，按 sort_order 升序）；读失败抛异常 */
    public ZoneDirectory(Supplier<List<ZoneRow>> loader, LongSupplier nanoClock) {
        this.cache = new TtlCache<>(() -> List.copyOf(loader.get()), nanoClock, TTL);
    }

    /** 全部区服，按 sort_order、zone_id 升序（不可变）。读失败抛异常。 */
    public List<ZoneRow> zones() {
        return cache.get();
    }

    /** 区服；不在目录里为空。读失败抛异常。 */
    public Optional<ZoneRow> find(int zoneId) {
        for (ZoneRow zone : zones()) {
            if (zone.zoneId() == zoneId) {
                return Optional.of(zone);
            }
        }
        return Optional.empty();
    }
}
