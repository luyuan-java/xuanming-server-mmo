package com.game.match.testing;

import com.game.common.deadline.Deadline;
import com.game.match.placement.PlacementStore;
import com.game.match.proto.BattlePlacement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link PlacementStore} 的内存实现：单调写（只收 attempt ≥ 已存值）、无条件删、三态读；不模拟 TTL（360 s 的到期由真实现的集成测试钉）。
 *
 * <pre>
 * InMemoryPlacementStore placements = new InMemoryPlacementStore(events);    // events：与别的替身共用的事件序列（可省）
 * placements.failWrites = 1;                         // 接下来 1 次 write 返回 false（不写）
 * placements.failWriteOnAttempt = 2;                 // 只让 attempt = 2 的写失败（换节点改写失败）
 * placements.corrupt(77);                            // 77 的记录读出来是损坏的 → Read.Failed
 * placements.readFailed = true;                      // 一切读都失败
 * assertThat(placements.writes).extracting(BattlePlacement::getAttempt).containsExactly(1, 2, 2);   // 首写、改写、成功后的补写
 * assertThat(placements.deletes).containsExactly(77L);
 * assertThat(events).containsSubsequence("placement.write:77#1", "battle.create:77");                // 落点先于建房
 * </pre>
 * 线程安全。
 */
public final class InMemoryPlacementStore implements PlacementStore {

    /** 每次 write 的入参（含失败的），按调用顺序。 */
    public final List<BattlePlacement> writes = new CopyOnWriteArrayList<>();
    /** 每次 delete 的 battle_id，按调用顺序。 */
    public final List<Long> deletes = new CopyOnWriteArrayList<>();
    /** 事件序列：{@code "placement.write:<battle_id>#<attempt>"}（失败的写是 {@code "placement.write-failed:…"}）/ {@code "placement.delete:<battle_id>"}。 */
    public final List<String> events;
    /** 接下来这么多次 write 返回 false（每次减一）。 */
    public volatile int failWrites;
    /** 非 0：attempt 等于它的 write 一律返回 false。 */
    public volatile int failWriteOnAttempt;
    /** 一切 read 都回 {@code Failed}。 */
    public volatile boolean readFailed;
    private final Map<Long, BattlePlacement> records = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> corrupted = new ConcurrentHashMap<>();

    public InMemoryPlacementStore() {
        this(new CopyOnWriteArrayList<>());
    }

    public InMemoryPlacementStore(List<String> events) {
        this.events = events;
    }

    /** 直接放一条记录（不记进 {@link #writes}）：给补签 / 观战的测试摆前置状态。 */
    public InMemoryPlacementStore put(BattlePlacement placement) {
        records.put(placement.getBattleId(), placement);
        return this;
    }

    /** 把这条记录标成损坏（读它回 {@code Failed}）。 */
    public InMemoryPlacementStore corrupt(long battleId) {
        corrupted.put(battleId, true);
        return this;
    }

    /** 此刻存着的记录。 */
    public Optional<BattlePlacement> stored(long battleId) {
        return Optional.ofNullable(records.get(battleId));
    }

    @Override
    public synchronized boolean write(BattlePlacement placement) {
        if (placement.getBattleId() == 0 || placement.getAttempt() < 1) {
            throw new IllegalArgumentException("落点记录必须带 battle_id 与 attempt ≥ 1");
        }
        writes.add(placement);
        String tag = Long.toUnsignedString(placement.getBattleId()) + "#" + placement.getAttempt();
        if (failWriteOnAttempt != 0 && placement.getAttempt() == failWriteOnAttempt) {
            events.add("placement.write-failed:" + tag);
            return false;
        }
        if (failWrites > 0) {
            failWrites--;
            events.add("placement.write-failed:" + tag);
            return false;
        }
        BattlePlacement existing = records.get(placement.getBattleId());
        if (existing == null || Integer.compareUnsigned(placement.getAttempt(), existing.getAttempt()) >= 0) {
            records.put(placement.getBattleId(), placement);
        }
        events.add("placement.write:" + tag);
        return true;
    }

    @Override
    public synchronized void delete(long battleId) {
        deletes.add(battleId);
        records.remove(battleId);
        corrupted.remove(battleId);
        events.add("placement.delete:" + Long.toUnsignedString(battleId));
    }

    @Override
    public Read read(long battleId, Deadline d) {
        if (readFailed) {
            return new Read.Failed("注入的故障: 读落点记录");
        }
        if (corrupted.containsKey(battleId)) {
            return new Read.Failed("落点记录损坏 battle_id=" + Long.toUnsignedString(battleId));
        }
        BattlePlacement placement = records.get(battleId);
        return placement == null ? new Read.Absent() : new Read.Found(placement);
    }
}
