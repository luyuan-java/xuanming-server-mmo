package com.game.mail;

import com.game.api.asset.AssetRpc;
import com.game.common.deadline.Deadline;
import com.game.discovery.asset.ReliableAssetDelivery;
import com.game.player.store.asset.AssetSeqState;
import com.game.player.store.asset.PersistedAssetLedgerReader;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/** 多副本SQL行租约重投；在线应答与Redis镜像均不能代替独立player_state SQL提交证据。 */
public final class MailDeliveryWorker implements SmartLifecycle, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(MailDeliveryWorker.class);
    private final MailStore store;
    private final ReliableAssetDelivery delivery;
    private final PersistedAssetLedgerReader ledger;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile com.game.common.workshard.WorkShard.Membership workShard;
    private volatile com.game.common.workshard.LoopLiveness deliveryLiveness;
    private volatile com.game.common.workshard.LoopLiveness cleanupLiveness;
    private ScheduledExecutorService workers;
    public MailDeliveryWorker(MailStore store, ReliableAssetDelivery delivery, PersistedAssetLedgerReader ledger) {
        this.store = store;
        this.delivery = delivery;
        this.ledger = ledger;
    }
    /** 挂载 64 片 HRW 分工成员表（附件重投按 player_id、过期清理按 mail_id，类别键分开）。 */
    public MailDeliveryWorker withWorkShard(com.game.common.workshard.WorkShard.Membership membership) {
        this.workShard = membership;
        return this;
    }
    /** 挂载重投与过期清理循环的活性跟踪器（每轮兜底 Throwable 防调度器静默停摆，有意关闭时摘除序列）。 */
    public MailDeliveryWorker withLiveness(com.game.common.workshard.LoopLiveness deliveryLiveness,
                                           com.game.common.workshard.LoopLiveness cleanupLiveness) {
        this.deliveryLiveness = deliveryLiveness;
        this.cleanupLiveness = cleanupLiveness;
        return this;
    }
    @Override
    public synchronized void start() {
        if (!running.compareAndSet(false, true))
            return;
        workers = Executors.newScheduledThreadPool(5, Thread.ofPlatform().name("mail-delivery-", 0).daemon(true).factory());
        for (int i = 0; i < 4; i++) workers.scheduleWithFixedDelay(this::tick, i * 100L, 1000, TimeUnit.MILLISECONDS);
        workers.scheduleWithFixedDelay(this::cleanupTick, 30_000L, 60_000L, TimeUnit.MILLISECONDS);
    }
    private void tick() {
        if (!running.get())
            return;
        var live = this.deliveryLiveness;
        if (live != null) {
            live.runGuarded(() -> pollDue(running::get));
            return;
        }
        try {
            pollDue(running::get);
        } catch (Throwable panic) {
            LOG.error("邮件资产重投循环单轮异常（循环继续）", panic);
        }
    }
    void cleanupTick() {
        if (!running.get())
            return;
        var live = this.cleanupLiveness;
        if (live != null) {
            live.runGuarded(this::cleanupExpiredOnce);
            return;
        }
        try {
            cleanupExpiredOnce();
        } catch (Throwable panic) {
            LOG.error("邮件过期清理循环单轮异常（循环继续）", panic);
        }
    }
    int cleanupExpiredOnce() {
        var membership = this.workShard;
        var filter = membership == null ? com.game.common.workshard.WorkShard.SqlFilter.ALL
                : membership.sqlFilter("mail_expiry_cleanup", "mail_id");
        if (filter.isNone()) {
            return 0;
        }
        try {
            return store.cleanupExpired(Deadline.after(10_000), System.currentTimeMillis(), 100, filter);
        } catch (Exception failure) {
            LOG.warn("邮件过期清理单轮失败，下轮保留重试，类型={}", failure.getClass().getSimpleName());
            return 0;
        }
    }
    void pollDue() {
        pollDue(() -> true);
    }
    private void pollDue(BooleanSupplier active) {
        try {
            var membership = this.workShard;
            var filter = membership == null ? com.game.common.workshard.WorkShard.SqlFilter.ALL
                    : membership.sqlFilter("mail_asset_op", "player_id");
            var dueIds = filter.isAll()
                    ? store.due(Deadline.after(2000), System.currentTimeMillis())
                    : store.due(Deadline.after(2000), System.currentTimeMillis(), filter);
            for (long id : dueIds) {
                if (!active.getAsBoolean())
                    break;
                try {
                    process(id);
                } catch (Exception failure) {
                    LOG.warn("邮件资产单条投递失败，下轮保留原操作恢复，opId={}，类型={}", id, failure.getClass().getSimpleName());
                }
            }
        } catch (Exception failure) {
            LOG.warn("邮件资产扫描失败，下轮保留原操作恢复，类型={}", failure.getClass().getSimpleName());
        }
    }
    void process(long id) throws Exception {
        long token = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        var op = store.lease(Deadline.after(2000), id, System.currentTimeMillis(), token);
        if (op == null)
            return;
        ReliableAssetDelivery.Delivery response = null;
        try {
            response = delivery.deliver(AssetRpc.CREDIT, op.request(), Deadline.after(2500)).get(3, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOG.warn("邮件资产投递被中断，将保持原操作重试，opId={}", id);
        } catch (Exception unknown) {
            LOG.warn("邮件资产投递未确认，将保持原操作重试，opId={}，类型={}", id, unknown.getClass().getSimpleName());
        }
        com.game.player.store.asset.PersistedAssetLedger committed = null;
        PersistedAssetLedgerReader.Read read = null;
        try {
            read = ledger.read(op.player());
        } catch (Exception unknown) {
            LOG.warn("邮件资产账本读取失败，将保持原操作重试，opId={}，类型={}", id, unknown.getClass().getSimpleName());
        }
        if (read instanceof PersistedAssetLedgerReader.Read.Loaded loaded) {
            var proof = loaded.ledger().outcomeOf(5, op.epoch(), op.seq());
            if (proof.conclusive() && loaded.ledger().identityMatches(5, op.epoch(), op.seq(), op.id(),
                    com.game.api.asset.AssetOpSignatures.requestDigest(op.request()))) {
                if (response != null && response.answered()
                        && (response.response().getOutcomeValue() == 1 || response.response().getOutcomeValue() == 2)
                        && ((proof.state() == AssetSeqState.APPLIED) != (response.response().getOutcomeValue() == 1)))
                    throw new IllegalStateException("资产SQL结局与在线结局翻转");
                committed = loaded.ledger();
            }
        }
        store.settle(Deadline.after(1500), op, committed);
    }
    @Override
    public synchronized void stop() {
        running.set(false);
        if (deliveryLiveness != null) {
            deliveryLiveness.close();
        }
        if (cleanupLiveness != null) {
            cleanupLiveness.close();
        }
        if (workers != null) {
            workers.shutdown();
            try {
                if (!workers.awaitTermination(10, TimeUnit.SECONDS))
                    workers.shutdownNow();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                workers.shutdownNow();
            }
            workers = null;
        }
    }
    @Override
    public boolean isRunning() {
        return running.get();
    }
    @Override
    public void close() {
        stop();
    }
}
