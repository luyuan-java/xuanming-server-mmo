package com.game.battle.outbox;

import com.game.battle.outbox.OutboxMetrics.ActivityEvent;
import com.game.battle.port.ActivityResultSink;
import com.game.battle.port.BattleResultSink;
import com.game.battle.room.BattleScheduler;
import com.game.battle.room.Cancellable;
import com.game.discovery.battle.BattleRedis;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis 兼容通道的活动结果发件箱（scene-battle-spec §3.6、§7.17）。写入成功或结果未知时均登记原 ID 和不可变原字节，
 * 首发走 {@link BattleResultSink.Channel#ACTIVITY}；{@link #dispatchCommitted} 已有 broker 提交证据，省略重复首发。
 * 每 10 秒探测，最多重发 30 次、登记最多保留 {@link BattleRedis#OUTBOX_MAX_AGE}。写入未知始终不把不存在当作销账，
 * 每次重发前必须读取并精确核对原字节，读失败、缺失或异值都跳过且不计重发次数；不补写、不销账。
 * 同进程在途或登记期间拒绝同 ID 不同字节，旧回包不能替换新登记；这不是 Redis 的跨写者围栏。
 *
 * <p>仍使用既有 7 天 TTL，不提供跨重启的未知记录恢复，也不代替 SQL v1 的永久原件与消费回执。
 * 年龄或次数耗尽仅停止内存重投，不能证明已消费或原件仍存在。线程与结算发件箱共用 {@code battle-outbox}。
 */
public final class ActivityResultOutbox implements ActivityResultSink {

    private static final Logger log = LoggerFactory.getLogger(ActivityResultOutbox.class);

    /** 活动结果持久副本的 Redis 操作（生产 = {@link BattleRedis}）。 */
    public interface Store {

        CompletableFuture<Long> store(long battleId, byte[] payload);

        CompletableFuture<Boolean> exists(long battleId);

        /** 读取持久原字节；空值不能作为活动已消费的证明。 */
        default CompletableFuture<byte[]> readOriginal(long battleId) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("未装配活动原件读取"));
        }

        static Store redis(BattleRedis redis) {
            Objects.requireNonNull(redis, "redis");
            return new Store() {
                @Override
                public CompletableFuture<Long> store(long battleId, byte[] payload) {
                    return redis.storeActivityResult(battleId, payload, BattleRedis.ACTIVITY_RESULT_TTL_SEC);
                }

                @Override
                public CompletableFuture<Boolean> exists(long battleId) {
                    return redis.activityResultExists(battleId);
                }

                @Override
                public CompletableFuture<byte[]> readOriginal(long battleId) {
                    return redis.readActivityResult(battleId);
                }
            };
        }
    }

    private static final class Entry {
        final BattleResultEvent event;
        final ByteString payload;
        final long enqueuedAtMs;
        final boolean persistenceUnconfirmed;
        int resent;
        boolean probing;

        Entry(BattleResultEvent event, ByteString payload, long enqueuedAtMs, boolean persistenceUnconfirmed) {
            this.event = event;
            this.payload = payload;
            this.enqueuedAtMs = enqueuedAtMs;
            this.persistenceUnconfirmed = persistenceUnconfirmed;
        }
    }

    /** 全部未完成写调用共享字节约束；latest 只决定回包是否有权登记。 */
    private static final class PendingStores {
        final ByteString payload;
        Object latest;
        int unfinished;

        PendingStores(ByteString payload) {
            this.payload = payload;
        }

        Object begin() {
            latest = new Object();
            unfinished++;
            return latest;
        }

        boolean finish(Object attempt) {
            unfinished--;
            return latest == attempt;
        }
    }

    private final BattleScheduler outbox;
    private final Store store;
    private final BattleResultSink publisher;
    private final OutboxMetrics metrics;
    private final LongSupplier clockMillis;
    private final Map<Long, Entry> entries = new LinkedHashMap<>();
    private final Map<Long, PendingStores> pendingStores = new LinkedHashMap<>();
    private Cancellable timer;

    public ActivityResultOutbox(BattleScheduler outbox, Store store, BattleResultSink publisher, OutboxMetrics metrics,
                                LongSupplier clockMillis) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.store = Objects.requireNonNull(store, "store");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
    }

    /** {@link ActivityResultSink}：在 {@code battle-logic} 上调用，只把任务交给发件箱线程。 */
    @Override
    public void dispatch(BattleResultEvent event) {
        dispatch(event, false);
    }

    @Override
    public void dispatchCommitted(BattleResultEvent event) {
        dispatch(event, true);
    }

    private void dispatch(BattleResultEvent event, boolean brokerCommitted) {
        try {
            outbox.execute(() -> onDispatch(event, brokerCommitted));
        } catch (RejectedExecutionException e) {
            log.error("发件箱线程已停，活动局结果没有落库也没有发布 battle_id={}", Long.toUnsignedString(event.getBattleId()));
        }
    }

    private void onDispatch(BattleResultEvent event, boolean brokerCommitted) {
        long battleId = event.getBattleId();
        byte[] payload;
        try {
            payload = event.toByteArray();
        } catch (RuntimeException e) {
            metrics.activity(ActivityEvent.SERIALIZE_FAILED);
            log.error("活动局结果序列化失败，不落库不发布 battle_id={}", Long.toUnsignedString(battleId), e);
            return;
        }
        ByteString bytes = ByteString.copyFrom(payload);
        Entry registered = entries.get(battleId);
        PendingStores pending = pendingStores.get(battleId);
        if ((registered != null && !registered.payload.equals(bytes))
                || (pending != null && !pending.payload.equals(bytes))) {
            metrics.activity(ActivityEvent.PAYLOAD_CONFLICT);
            log.error("同一活动结果原ID收到不同字节，拒绝覆盖或发布 battle_id={}", Long.toUnsignedString(battleId));
            return;
        }
        PendingStores calls = pendingStores.computeIfAbsent(battleId, ignored -> new PendingStores(bytes));
        Object attempt = calls.begin();
        CompletableFuture<Long> stored;
        try {
            stored = store.store(battleId, payload);
        } catch (RuntimeException e) {
            stored = CompletableFuture.failedFuture(e);
        }
        onOutbox(stored, (ok, error) -> {
            boolean latest = calls.finish(attempt);
            if (calls.unfinished == 0) {
                pendingStores.remove(battleId, calls);
            }
            if (!latest) {
                return;
            }
            boolean unconfirmed = error != null || ok == null || ok != 1;
            if (unconfirmed) {
                metrics.activity(ActivityEvent.NOT_DURABLE);
                log.error("活动结果持久性未确认，登记原件探测；不能将异常视为回滚 battle_id={}: {}", Long.toUnsignedString(battleId),
                        error != null ? error.toString() : "无效落库结果: " + ok);
            } else {
                metrics.activity(ActivityEvent.STORED);
            }
            if (!brokerCommitted) {
                publish(event);
            }
            entries.put(battleId, new Entry(event, bytes, clockMillis.getAsLong(), unconfirmed));
            if (timer == null) {
                timer = outbox.every(BattleRedis.ACTIVITY_RETRY_INTERVAL.toMillis(), this::tick);
            }
        });
    }

    /** 经发布端口按活动通道发一次（首发与每次重发都走这里，计 {@code game_battle_results_total{channel=activity}}，不混进普通局）。 */
    private void publish(BattleResultEvent event) {
        try {
            publisher.publish(event, BattleResultSink.Channel.ACTIVITY);
        } catch (RuntimeException e) {
            log.error("活动局结果发布端口抛出异常（已吞掉） battle_id={}", Long.toUnsignedString(event.getBattleId()), e);
        }
    }

    /** 一轮（每 10 s）。 */
    void tick() {
        long now = clockMillis.getAsLong();
        for (Map.Entry<Long, Entry> e : new ArrayList<>(entries.entrySet())) {
            long battleId = e.getKey();
            Entry entry = e.getValue();
            if (now - entry.enqueuedAtMs > BattleRedis.OUTBOX_MAX_AGE.toMillis()) {
                entries.remove(battleId, entry);
                metrics.activity(ActivityEvent.EXPIRED);
                log.error("活动结果超龄，停止内存重投；销账未确认，须核对持久原件与消费回执 persistence_unconfirmed={} battle_id={}",
                        entry.persistenceUnconfirmed, Long.toUnsignedString(battleId));
                continue;
            }
            if (entry.probing) {
                continue;
            }
            entry.probing = true;
            CompletableFuture<Boolean> probe;
            try {
                probe = store.exists(battleId);
            } catch (RuntimeException ex) {
                probe = CompletableFuture.failedFuture(ex);
            }
            onOutbox(probe, (present, error) -> {
                if (entries.get(battleId) != entry) {
                    return;
                }
                entry.probing = false;
                if (error != null || present == null) {
                    metrics.activity(ActivityEvent.PROBE_ERROR);
                    return;
                }
                if (entry.persistenceUnconfirmed) {
                    if (present) {
                        verifyOriginal(battleId, entry);
                    } else {
                        metrics.activity(ActivityEvent.PROBE_ERROR);
                    }
                    return;
                }
                onVerifiedPresence(battleId, entry, present);
            });
        }
        stopTimerIfIdle();
    }

    private void verifyOriginal(long battleId, Entry entry) {
        entry.probing = true;
        CompletableFuture<byte[]> original;
        try {
            original = store.readOriginal(battleId);
        } catch (RuntimeException failure) {
            original = CompletableFuture.failedFuture(failure);
        }
        onOutbox(original, (bytes, error) -> {
            if (entries.get(battleId) != entry) {
                return;
            }
            entry.probing = false;
            if (error != null || bytes == null || !entry.payload.equals(ByteString.copyFrom(bytes))) {
                metrics.activity(ActivityEvent.PROBE_ERROR);
                return;
            }
            onVerifiedPresence(battleId, entry, true);
        });
    }

    private void onVerifiedPresence(long battleId, Entry entry, boolean present) {
        switch (ActivityRetryRules.classify(present, entry.resent)) {
            case ACKED -> {
                entries.remove(battleId, entry);
                metrics.activity(ActivityEvent.ACKED);
            }
            case EXHAUSTED -> {
                entries.remove(battleId, entry);
                metrics.activity(ActivityEvent.EXHAUSTED);
                log.error("活动结果重发用尽，停止内存重投，未删除 Redis 原件 battle_id={}", Long.toUnsignedString(battleId));
            }
            case RESEND -> {
                entry.resent++;
                metrics.activity(ActivityEvent.RESEND);
                publish(entry.event);
            }
        }
        stopTimerIfIdle();
    }

    private void stopTimerIfIdle() {
        if (entries.isEmpty() && timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    /** 当前登记的条目数（发件箱线程上读；测试用）。 */
    int size() {
        return entries.size();
    }

    /** 异步结局投递回发件箱线程（已停时丢弃）。端口返回空 future 按出错处理。 */
    private <T> void onOutbox(CompletableFuture<T> future, BiConsumer<T, Throwable> callback) {
        if (future == null) {
            future = CompletableFuture.failedFuture(new IllegalStateException("端口返回了空的 future"));
        }
        future.whenComplete((value, error) -> {
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            try {
                outbox.execute(() -> callback.accept(value, cause));
            } catch (RejectedExecutionException e) {
                log.debug("发件箱线程已停，丢弃一个异步结局");
            }
        });
    }
}
