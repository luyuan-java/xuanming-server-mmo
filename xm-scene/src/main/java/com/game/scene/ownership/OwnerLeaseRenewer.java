package com.game.scene.ownership;

import com.game.player.store.OwnerLease;
import com.game.player.store.PlayerStore;
import com.game.scene.world.OwnedPlayer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 给本节点内存里持有的玩家数据归属续约（PlayerStore「归属协议」第 2 步）。每 {@link #PERIOD}（租约的 1/3）一轮：
 * 在逻辑线程上取快照 → 在存储线程池上批量续约（阻塞 MySQL，不占调度线程与逻辑线程）→ 续不上的交回逻辑线程移除并踢人。
 *
 * <p>续约连续失败（库不可达）超过一个租约时，别的会话可以强制夺权；本节点的实例在续约恢复后被识别为失去归属并移除。
 * 这是 fail-closed 的取舍：宁可踢掉一个已经无法写回的实例，也不让它继续被玩、再在离开时被围栏拒掉。
 */
public final class OwnerLeaseRenewer {

    private static final Logger log = LoggerFactory.getLogger(OwnerLeaseRenewer.class);

    /** 续约周期：租约的 1/3，留两次失败的余量。 */
    public static final Duration PERIOD = PlayerStore.OWNER_LEASE.dividedBy(3);

    private final Callable<List<OwnedPlayer>> snapshot;
    private final PlayerStore store;
    private final Executor storageExecutor;
    private final Consumer<List<OwnedPlayer>> onLost;
    private ScheduledFuture<?> task;

    /**
     * @param snapshot        取本节点持有的归属（实现负责切到逻辑线程并限时等待）
     * @param storageExecutor 执行阻塞续约的存储线程池
     * @param onLost          续不上的归属（实现负责投递回逻辑线程）
     */
    public OwnerLeaseRenewer(Callable<List<OwnedPlayer>> snapshot, PlayerStore store, Executor storageExecutor,
                             Consumer<List<OwnedPlayer>> onLost) {
        this.snapshot = snapshot;
        this.store = store;
        this.storageExecutor = storageExecutor;
        this.onLost = onLost;
    }

    public synchronized void start(ScheduledExecutorService scheduler) {
        if (task == null) {
            task = scheduler.scheduleWithFixedDelay(this::renewOnce, PERIOD.toMillis(), PERIOD.toMillis(),
                    TimeUnit.MILLISECONDS);
        }
    }

    public synchronized void stop() {
        if (task != null) {
            task.cancel(false);
        }
    }

    /** 一轮续约（调度线程上；包内可见供测试直接驱动）。 */
    void renewOnce() {
        List<OwnedPlayer> owned;
        try {
            owned = snapshot.call();
        } catch (Exception e) {
            log.warn("取归属快照失败，本轮不续约", e);
            return;
        }
        if (owned.isEmpty()) {
            return;
        }
        try {
            storageExecutor.execute(() -> renewNow(owned));
        } catch (RejectedExecutionException e) {
            log.warn("存储线程池繁忙或已关闭，本轮不续约 持有={}", owned.size());
        }
    }

    private void renewNow(List<OwnedPlayer> owned) {
        List<OwnerLease> lost;
        try {
            lost = store.renewOwnerLeases(owned.stream().map(o -> new OwnerLease(o.playerId(), o.ownerEpoch())).toList());
        } catch (RuntimeException e) {
            log.warn("归属续约失败，下一轮重试（连续失败超过 {} 归属可能被强制夺走） 持有={}", PlayerStore.OWNER_LEASE,
                    owned.size(), e);
            return;
        }
        if (lost.isEmpty()) {
            return;
        }
        log.warn("续约发现 {} 份归属已不在本节点手里，移除对应实例", lost.size());
        onLost.accept(lost.stream().map(l -> new OwnedPlayer(l.playerId(), l.ownerEpoch())).toList());
    }
}
