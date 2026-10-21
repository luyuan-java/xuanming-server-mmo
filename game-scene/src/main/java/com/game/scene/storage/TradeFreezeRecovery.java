package com.game.scene.storage;

import com.game.player.store.PlayerTradeStore;
import com.game.player.store.PlayerTradeStore.DrainTask;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 存储线程有界续页→原scene逻辑线程排空→存储线程验真ACK；不在逻辑线程阻塞SQL。 */
public final class TradeFreezeRecovery implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(TradeFreezeRecovery.class);
    private final PlayerTradeStore store;
    private final String source;
    private final Executor storage;
    private final Executor logic;
    private final BiConsumer<DrainTask, Runnable> drain;
    private final AtomicBoolean scanning = new AtomicBoolean();
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    /** 游标仅由单个扫描任务写；在途业务不改变游标所有权。 */
    private long afterListing;
    private volatile boolean closed;
    private ScheduledFuture<?> task;

    public TradeFreezeRecovery(PlayerTradeStore store, String source, Executor storage, Executor logic,
                               BiConsumer<DrainTask, Runnable> drain) {
        this.store = java.util.Objects.requireNonNull(store);
        this.source = java.util.Objects.requireNonNull(source);
        this.storage = java.util.Objects.requireNonNull(storage);
        this.logic = java.util.Objects.requireNonNull(logic);
        this.drain = java.util.Objects.requireNonNull(drain);
    }
    public synchronized void start(ScheduledExecutorService scheduler) {
        if (task != null || closed) return;
        task = scheduler.scheduleWithFixedDelay(this::requestScan, 0, 1, TimeUnit.SECONDS);
    }
    public void requestScan() {
        if (closed || !scanning.compareAndSet(false, true)) return;
        try { storage.execute(this::scan); }
        catch (RejectedExecutionException rejected) {
            scanning.set(false);
            log.warn("角色冻结扫描队列已满，保留SQL交易锁");
        }
    }
    private void scan() {
        try {
            if (closed) return;
            var page = store.pendingSceneDrains(source, afterListing, 20);
            if (page.isEmpty()) { afterListing = 0; return; }
            for (var entry : page) {
                if (closed) break;
                long listing = entry.request().listingId();
                try {
                    if (inFlight.add(listing)) {
                        try { logic.execute(() -> drain(entry)); }
                        catch (RuntimeException rejected) { inFlight.remove(listing); throw rejected; }
                    }
                } catch (RuntimeException failure) {
                    log.warn("角色冻结逻辑投递失败，后轮重试", failure);
                }
                afterListing = listing;
            }
        } catch (RuntimeException failure) {
            log.warn("角色冻结SQL扫描失败，游标保持原位置", failure);
        } finally { scanning.set(false); }
    }
    private void drain(DrainTask entry) {
        if (closed) { inFlight.remove(entry.request().listingId()); return; }
        try { drain.accept(entry, () -> persist(entry)); }
        catch (RuntimeException failure) {
            inFlight.remove(entry.request().listingId());
            log.error("角色冻结逻辑排空失败，禁止清锁", failure);
        }
    }
    private void persist(DrainTask entry) {
        if (closed) { inFlight.remove(entry.request().listingId()); return; }
        try {
            storage.execute(() -> {
                try { if (!closed) store.recordSceneDrain(entry, Duration.ofSeconds(5)); }
                catch (RuntimeException failure) { log.warn("角色排空ACK尚未持久化或未完成，保留交易锁", failure); }
                finally { inFlight.remove(entry.request().listingId()); }
            });
        } catch (RejectedExecutionException rejected) {
            inFlight.remove(entry.request().listingId());
            log.warn("角色排空ACK存储队列已满，后轮重试");
        }
    }
    @Override public synchronized void close() {
        closed = true;
        if (task != null) task.cancel(false);
    }
}
