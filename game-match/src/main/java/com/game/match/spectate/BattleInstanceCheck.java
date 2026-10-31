package com.game.match.spectate;

import com.game.common.deadline.Deadline;
import com.game.match.gather.BattleNodes;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 「这个 battle 实例是不是已经被换掉了」（mmorpg battle-spectate-tiered-fanout.md §15.9：不给死实例签凭证）。只认一条正面证据：目录里同节点号已是别的实例，房间随原进程没了。
 * 目录里没有、读失败、条目损坏都不判：丢了租约、条目刚过期、Redis 抖动都不代表实例已死。
 *
 * <p>大厅档每名观众都要走这里，量大：按 (节点号, 实例) 留 {@link #CACHE_TTL_NANOS} 的本地缓存，不放大目录读。缓存的代价只是实例换掉之后最多再签几秒凭证（gate 等不到帧，按源站失联收尾）；
 * 「已换」一旦成立就不会再变回去（实例号不复用）。条目数 = 节点数 × 重启次数，到 {@link #MAX_ENTRIES} 整表清掉重来。
 *
 * <p>线程安全：163 的虚拟线程上调，阻塞至多一次目录读（受 {@code d} 约束），不持锁；并发未命中时各读一次，无害。
 */
public final class BattleInstanceCheck {

    static final long CACHE_TTL_NANOS = TimeUnit.SECONDS.toNanos(3);
    static final int MAX_ENTRIES = 1024;

    private record Cached(boolean replaced, long readAtNanos) {
    }

    private final BattleNodes nodes;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    public BattleInstanceCheck(BattleNodes nodes) {
        this(nodes, System::nanoTime);
    }

    /** @param nanoClock 单调时钟（测试可推进） */
    BattleInstanceCheck(BattleNodes nodes, LongSupplier nanoClock) {
        this.nodes = Objects.requireNonNull(nodes, "nodes");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    /**
     * @param instanceId 落点记录里的实例；空串（正常写者不会产生）无从比对，不判
     * @return true = 目录里同节点号已是别的实例；其余（还是它、查不到、读失败）false
     */
    public boolean replaced(int nodeId, String instanceId, Deadline d) {
        if (instanceId.isEmpty()) {
            return false;
        }
        String key = BattleNodes.key(nodeId, instanceId);
        long now = nanoClock.getAsLong();
        Cached cached = cache.get(key);
        if (cached != null && now - cached.readAtNanos() < CACHE_TTL_NANOS) {
            return cached.replaced();
        }
        boolean replaced = nodes.lookup(nodeId, instanceId, d) == BattleNodes.Lookup.OTHER_INSTANCE;
        if (cache.size() >= MAX_ENTRIES) {
            cache.clear();
        }
        cache.put(key, new Cached(replaced, now));
        return replaced;
    }
}
