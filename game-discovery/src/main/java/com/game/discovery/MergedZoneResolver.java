package com.game.discovery;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 不停服合服读时最终区解析器（对齐主仓 {@code go/shared/placement/merged_zone.go}）：
 * <ul>
 *   <li>合服只写一条 {@code source_zone -> target_zone} 映射，玩家/帮会/商品/榜单存原始区，读时统一解析至最终区；</li>
 *   <li>支持链式多跳合并（带防环上限 64 跳）、双向成员集展开（按最终区查全员原始区集合）与定时原子刷新（刷新失败保留旧快照）。</li>
 * </ul>
 */
public final class MergedZoneResolver {

    public static final int MAX_HOPS = 64;
    public static final MergedZoneResolver IDENTITY = new MergedZoneResolver(Map.of());

    private final Map<Integer, Integer> mergedInto;
    private final Map<Integer, Integer> canonicalByZone;
    private final Map<Integer, Set<Integer>> membersByCanonical;

    private MergedZoneResolver(Map<Integer, Integer> rawMappings) {
        Map<Integer, Integer> copy = new HashMap<>();
        if (rawMappings != null) {
            for (var e : rawMappings.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && e.getKey() != 0 && e.getValue() != 0
                        && !e.getKey().equals(e.getValue())) {
                    copy.put(e.getKey(), e.getValue());
                }
            }
        }
        this.mergedInto = Collections.unmodifiableMap(copy);
        Map<Integer, Integer> canon = new HashMap<>();
        Map<Integer, Set<Integer>> members = new HashMap<>();
        for (int source : copy.keySet()) {
            int target = followChain(source, copy);
            canon.put(source, target);
            members.computeIfAbsent(target, k -> {
                Set<Integer> s = new TreeSet<>(Integer::compareUnsigned);
                s.add(k);
                return s;
            }).add(source);
        }
        Map<Integer, Set<Integer>> frozenMembers = new HashMap<>();
        for (var e : members.entrySet()) {
            frozenMembers.put(e.getKey(), Collections.unmodifiableSet(e.getValue()));
        }
        this.canonicalByZone = Collections.unmodifiableMap(canon);
        this.membersByCanonical = Collections.unmodifiableMap(frozenMembers);
    }

    public static MergedZoneResolver of(Map<Integer, Integer> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            return IDENTITY;
        }
        return new MergedZoneResolver(mappings);
    }

    /** 解析任意区号对应的最终区号（未合服区返回自身）。 */
    public int resolveFinalZone(int zoneId) {
        if (zoneId == 0) {
            return 0;
        }
        return canonicalByZone.getOrDefault(zoneId, zoneId);
    }

    /** 两区是否在合服后归属同一最终区。 */
    public boolean sameFinalZone(int zoneA, int zoneB) {
        return resolveFinalZone(zoneA) == resolveFinalZone(zoneB);
    }

    /** 返回与 {@code zoneId} 同属一个最终区的全部原始区集合（含最终区自身，无符号升序）。 */
    public Set<Integer> memberZonesOf(int zoneId) {
        if (zoneId == 0) {
            return Set.of();
        }
        int canon = resolveFinalZone(zoneId);
        return membersByCanonical.getOrDefault(canon, Set.of(canon));
    }

    /** 当前原始单跳映射只读视图。 */
    public Map<Integer, Integer> rawMappings() {
        return mergedInto;
    }

    private static int followChain(int start, Map<Integer, Integer> map) {
        int cur = start;
        Set<Integer> visited = new HashSet<>();
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            if (!visited.add(cur)) {
                throw new IllegalStateException("合服映射链存在环路: start=" + Integer.toUnsignedString(start));
            }
            Integer next = map.get(cur);
            if (next == null || next == 0) {
                return cur;
            }
            cur = next;
        }
        throw new IllegalStateException("合服映射链存在环路或超过 " + MAX_HOPS + " 跳: start=" + Integer.toUnsignedString(start));
    }

    /** 可定时刷新的缓存容器：启动加载失败抛异常拒启；后续刷新失败保留上一份快照。 */
    public static final class Cache {
        private final Supplier<Map<Integer, Integer>> loader;
        private final AtomicReference<MergedZoneResolver> current;

        private Cache(Supplier<Map<Integer, Integer>> loader, MergedZoneResolver initial) {
            this.loader = Objects.requireNonNull(loader, "loader");
            this.current = new AtomicReference<>(initial);
        }

        public static Cache loadInitial(Supplier<Map<Integer, Integer>> loader) {
            Objects.requireNonNull(loader, "loader");
            Map<Integer, Integer> initial = Objects.requireNonNull(loader.get(), "initial merged zone mappings");
            return new Cache(loader, MergedZoneResolver.of(initial));
        }

        public MergedZoneResolver current() {
            return current.get();
        }

        /** 定时刷新映射；失败返回 false 并保留旧值。 */
        public boolean tryRefresh() {
            try {
                Map<Integer, Integer> loaded = loader.get();
                if (loaded == null) {
                    return false;
                }
                current.set(MergedZoneResolver.of(loaded));
                return true;
            } catch (RuntimeException ignored) {
                return false;
            }
        }
    }
}
