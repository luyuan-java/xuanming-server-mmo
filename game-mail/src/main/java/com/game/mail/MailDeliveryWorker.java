package com.game.mail;

import com.game.api.asset.AssetRpc;
import com.game.common.deadline.Deadline;
import com.game.discovery.asset.ReliableAssetDelivery;
import com.game.player.store.asset.AssetSeqState;
import com.game.player.store.asset.PersistedAssetLedgerReader;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private ScheduledExecutorService workers;
    public MailDeliveryWorker(MailStore store, ReliableAssetDelivery delivery, PersistedAssetLedgerReader ledger) {
        this.store = store;
        this.delivery = delivery;
        this.ledger = ledger;
    }
    @Override
    public synchronized void start() {
        if (!running.compareAndSet(false, true))
            return;
        workers = Executors.newScheduledThreadPool(4, Thread.ofPlatform().name("mail-delivery-", 0).daemon(true).factory());
        for (int i = 0; i < 4; i++) workers.scheduleWithFixedDelay(this::tick, i * 100L, 1000, TimeUnit.MILLISECONDS);
    }
    private void tick() {
        if (!running.get())
            return;
        try {
            for (long id : store.due(Deadline.after(2000), System.currentTimeMillis())) {
                if (!running.get())
                    break;
                process(id);
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
        var response = delivery.deliver(AssetRpc.CREDIT, op.request(), Deadline.after(2500)).get(3, TimeUnit.SECONDS);
        com.game.player.store.asset.PersistedAssetLedger committed = null;
        var read = ledger.read(op.player());
        if (read instanceof PersistedAssetLedgerReader.Read.Loaded loaded) {
            var proof = loaded.ledger().outcomeOf(5, op.epoch(), op.seq());
            if (proof.conclusive() && loaded.ledger().identityMatches(5, op.epoch(), op.seq(), op.id(),
                    com.game.api.asset.AssetOpSignatures.requestDigest(op.request()))) {
                if (response.answered() && (response.response().getOutcomeValue() == 1 || response.response().getOutcomeValue() == 2)
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
