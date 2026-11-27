package com.game.discovery.gate;

import com.game.api.proto.GateNodeInfo;
import com.game.discovery.NodeDirectory;
import com.game.discovery.NodeTypes;
import com.game.discovery.RedisKeys;
import com.game.discovery.drain.GateDrainMarks;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link GateSource} 的 Redis 实现：读 game-gate 发布到 {@link NodeDirectory} 的 {@link NodeTypes#GATE} 目录
 * （类型名与 game-gate 共用同一常量），再一次 MGET 叠加运维打的排空标记（{@link GateDrainMarks}）——标记在的条目
 * {@code draining=true}（标记的实例须与条目一致），由 {@link GatePicker} 剔除。
 *
 * <p>按 zone 单飞短缓存 1 秒（对齐主仓缺口清单第 31 条 {@code DrainMarkCache}）：同 zone 并发过期只读一次 Redis，
 * 空结果同样缓存一个周期；排空标记读不到按没人在排空放行；目录本身读不到照旧抛出（调用方 fail-closed），不缓存失败。
 * 纵深防御类的排空校验直接走 {@link GateDrainMarks}，不经本缓存。
 */
public final class RedisGateSource implements GateSource {

    private static final Logger log = LoggerFactory.getLogger(RedisGateSource.class);

    /** 缺省按 zone 短缓存时长（1 秒）。 */
    public static final Duration DEFAULT_CACHE_TTL = Duration.ofSeconds(1);

    private final IntFunction<List<GateNodeInfo>> directoryLoader;
    private final BiFunction<Integer, List<Integer>, Map<Integer, GateDrainMarks.Mark>> drainLoader;
    private final long cacheTtlNanos;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<Integer, ZoneSlot> cache = new ConcurrentHashMap<>();

    public RedisGateSource(RedissonClient redis, RedisKeys keys) {
        this(redis, keys, DEFAULT_CACHE_TTL);
    }

    public RedisGateSource(RedissonClient redis, RedisKeys keys, Duration cacheTtl) {
        NodeDirectory<GateNodeInfo> directory = new NodeDirectory<>(redis, keys, NodeTypes.GATE, GateNodeInfo.parser());
        GateDrainMarks drainMarks = new GateDrainMarks(redis, keys);
        this.directoryLoader = directory::list;
        this.drainLoader = drainMarks::draining;
        this.cacheTtlNanos = validateTtl(cacheTtl);
        this.nanoClock = System::nanoTime;
    }

    RedisGateSource(IntFunction<List<GateNodeInfo>> directoryLoader,
                    BiFunction<Integer, List<Integer>, Map<Integer, GateDrainMarks.Mark>> drainLoader,
                    Duration cacheTtl, LongSupplier nanoClock) {
        this.directoryLoader = Objects.requireNonNull(directoryLoader, "directoryLoader");
        this.drainLoader = Objects.requireNonNull(drainLoader, "drainLoader");
        this.cacheTtlNanos = validateTtl(cacheTtl);
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    private static long validateTtl(Duration ttl) {
        Objects.requireNonNull(ttl, "cacheTtl");
        if (ttl.isNegative()) {
            throw new IllegalArgumentException("cacheTtl 不能为负: " + ttl);
        }
        return ttl.toNanos();
    }

    @Override
    public List<GateNodeInfo> listGates(int zoneId) {
        if (cacheTtlNanos == 0L) {
            return fetchFresh(zoneId);
        }
        ZoneSlot slot = cache.computeIfAbsent(zoneId, ignored -> new ZoneSlot());
        long now = nanoClock.getAsLong();
        Snapshot snap = slot.snapshot;
        if (snap != null && now - snap.loadedAtNanos < cacheTtlNanos) {
            return snap.gates;
        }
        // 同 zone 单飞：只锁该 zone 槽位，避免并发过期把全服登录洪峰打透到 Redis，且不同 zone 互不阻塞。
        synchronized (slot) {
            now = nanoClock.getAsLong();
            snap = slot.snapshot;
            if (snap != null && now - snap.loadedAtNanos < cacheTtlNanos) {
                return snap.gates;
            }
            List<GateNodeInfo> loaded = fetchFresh(zoneId);
            slot.snapshot = new Snapshot(loaded, nanoClock.getAsLong());
            return loaded;
        }
    }

    private List<GateNodeInfo> fetchFresh(int zoneId) {
        List<GateNodeInfo> gates = directoryLoader.apply(zoneId);
        if (gates.isEmpty()) {
            return List.of();
        }
        Map<Integer, GateDrainMarks.Mark> draining;
        try {
            draining = drainLoader.apply(zoneId, gates.stream().map(GateNodeInfo::getNodeId).toList());
        } catch (RuntimeException e) {
            log.error("读取 gate 排空标记失败，按没人在排空处理 zone={}: {}", zoneId, e.toString());
            return List.copyOf(gates);
        }
        if (draining.isEmpty()) {
            return List.copyOf(gates);
        }
        List<GateNodeInfo> out = new ArrayList<>(gates.size());
        for (GateNodeInfo gate : gates) {
            GateDrainMarks.Mark mark = draining.get(gate.getNodeId());
            // 标记绑实例：节点号被新实例复用时，旧实例的标记不算数
            out.add(mark != null && mark.appliesTo(gate.getInstanceId()) ? gate.toBuilder().setDraining(true).build() : gate);
        }
        return List.copyOf(out);
    }

    private record Snapshot(List<GateNodeInfo> gates, long loadedAtNanos) {
    }

    private static final class ZoneSlot {
        volatile Snapshot snapshot;
    }
}
