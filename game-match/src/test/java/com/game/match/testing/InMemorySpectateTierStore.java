package com.game.match.testing;

import com.game.common.deadline.Deadline;
import com.game.match.spectate.SpectateTierStore;
import com.game.proto.eSpectateTier;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** {@link SpectateTierStore} 的内存替身（只升不降同 Lua；后标记的赛事局在前，不模拟到期）；{@link #failing} 打开后每个方法都抛依赖异常。线程安全。 */
public final class InMemorySpectateTierStore implements SpectateTierStore {

    private final Map<Long, eSpectateTier> tiers = new ConcurrentHashMap<>();
    private final List<Long> featured = new CopyOnWriteArrayList<>();
    /** 模拟 Redis 故障。 */
    public volatile boolean failing;

    private void check() {
        if (failing) {
            throw new Deadline.DependencyException("注入的 Redis 故障");
        }
    }

    @Override
    public eSpectateTier tier(long battleId, Deadline d) {
        check();
        return tiers.getOrDefault(battleId, eSpectateTier.SPECTATE_TIER_DIRECT);
    }

    @Override
    public eSpectateTier raise(long battleId, eSpectateTier target, Deadline d) {
        check();
        return tiers.merge(battleId, target, (current, next) -> next.getNumber() > current.getNumber() ? next : current);
    }

    @Override
    public void setFeatured(long battleId, boolean mark, Deadline d) {
        check();
        featured.remove(Long.valueOf(battleId));
        if (mark) {
            featured.add(0, battleId);
        }
    }

    @Override
    public List<Long> activeFeatured(int limit, Deadline d) {
        check();
        return List.copyOf(featured.subList(0, Math.min(limit, featured.size())));
    }

    /** 直接看档位（断言用，不受 {@link #failing} 影响）。 */
    public eSpectateTier peek(long battleId) {
        return tiers.getOrDefault(battleId, eSpectateTier.SPECTATE_TIER_DIRECT);
    }

    public boolean featured(long battleId) {
        return featured.contains(battleId);
    }
}
