package com.game.battle.testing;

import com.game.battle.outbox.ActivityResultOutbox;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 活动结果通道的假 Redis 端口（scene-battle-spec §7.17、§13.5）：内存模型是「battle_id → 持久副本」，{@code SET} 回 1、{@code EXISTS} 看在不在；
 * 消费方销账由测试调 {@link #consume} 推进。完成时机与方式由 {@link #stores} / {@link #probes} 编排；调用进 {@link CallJournal}
 * （{@code activity-store:<bid>}、{@code activity-store-done:<bid>=…}、{@code activity-exists:<bid>}）。线程安全。
 */
public final class FakeActivityStore implements ActivityResultOutbox.Store {

    public final Scripted<Long> stores = new Scripted<>();
    public final Scripted<Boolean> probes = new Scripted<>();

    private final CallJournal journal;
    private final Map<Long, byte[]> copies = new HashMap<>();
    private final Map<Long, byte[]> offered = new HashMap<>();

    public FakeActivityStore(CallJournal journal) {
        this.journal = journal;
    }

    @Override
    public CompletableFuture<Long> store(long battleId, byte[] payload) {
        String id = Long.toUnsignedString(battleId);
        byte[] copy = payload.clone();
        synchronized (this) {
            offered.put(battleId, copy);
        }
        journal.add("activity-store:" + id);
        CompletableFuture<Long> future = stores.invoke("activity-store:" + id, () -> {
            synchronized (this) {
                copies.put(battleId, copy);
            }
            return 1L;
        });
        return future == null ? null
                : future.whenComplete((ok, error) -> journal.add("activity-store-done:" + id + "=" + (error != null ? "error" : ok)));
    }

    @Override
    public CompletableFuture<Boolean> exists(long battleId) {
        String id = Long.toUnsignedString(battleId);
        journal.add("activity-exists:" + id);
        return probes.invoke("activity-exists:" + id, () -> present(battleId));
    }

    /** 消费方销账：删掉持久副本。 */
    public synchronized void consume(long battleId) {
        copies.remove(battleId);
    }

    public synchronized boolean present(long battleId) {
        return copies.containsKey(battleId);
    }

    /** 模型里的持久副本；没有为 null。 */
    public synchronized byte[] copy(long battleId) {
        byte[] bytes = copies.get(battleId);
        return bytes == null ? null : bytes.clone();
    }

    /** 最近一次落库调用传入的字节；没调过为 null。 */
    public synchronized byte[] offered(long battleId) {
        byte[] bytes = offered.get(battleId);
        return bytes == null ? null : bytes.clone();
    }
}
