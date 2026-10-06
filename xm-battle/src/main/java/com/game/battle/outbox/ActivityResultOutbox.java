package com.game.battle.outbox;

import com.game.battle.outbox.OutboxMetrics.ActivityEvent;
import com.game.battle.port.ActivityResultSink;
import com.game.battle.port.BattleResultSink;
import com.game.battle.room.BattleScheduler;
import com.game.battle.room.Cancellable;
import com.game.discovery.battle.BattleRedis;
import com.game.proto.contracts.kafka.BattleResultEvent;
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
 * 活动结果持久通道的 battle 侧（基线 {@code battle_result_activity.h} + {@code room.cpp:1861-2031}；scene-battle-spec §3.6、§7.17）：
 * {@code SET xm:battle:activity-result:<id> EX 7d} 成功 → 经 {@link BattleResultSink} 发布并登记；失败 → 发布一次 + {@code not_durable}。
 * 每 10 s 对每条 EXISTS：0 → 摘除（消费方已销账）；1 → 用尽（30 次重发）则 ERROR 摘除，否则重发；<b>出错一律跳过、不计次</b>（与 D15 统一）；
 * 登记超过 {@link BattleRedis#OUTBOX_MAX_AGE} 仍没有结论 → 按用尽摘除（记录仍在 Redis，留给 4.6 的巡检器）。
 *
 * <p>销账方是 4.6 的帮会同道历练消费方与巡检器，两版现在都不存在；6.4 接上 {@link BattleResultSink} 的真实传输之前发布端口只记日志——整条通道两版都不可达（Q11）。
 * 线程：与结算发件箱共用 {@code battle-outbox}。
 */
public final class ActivityResultOutbox implements ActivityResultSink {

    private static final Logger log = LoggerFactory.getLogger(ActivityResultOutbox.class);

    /** 活动结果持久副本的 Redis 操作（生产 = {@link BattleRedis}）。 */
    public interface Store {

        CompletableFuture<Long> store(long battleId, byte[] payload);

        CompletableFuture<Boolean> exists(long battleId);

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
            };
        }
    }

    private static final class Entry {
        final BattleResultEvent event;
        final long enqueuedAtMs;
        int resent;
        boolean probing;

        Entry(BattleResultEvent event, long enqueuedAtMs) {
            this.event = event;
            this.enqueuedAtMs = enqueuedAtMs;
        }
    }

    private final BattleScheduler outbox;
    private final Store store;
    private final BattleResultSink publisher;
    private final OutboxMetrics metrics;
    private final LongSupplier clockMillis;
    private final Map<Long, Entry> entries = new LinkedHashMap<>();
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
        try {
            outbox.execute(() -> onDispatch(event));
        } catch (RejectedExecutionException e) {
            log.error("发件箱线程已停，活动局结果没有落库也没有发布 battle_id={}", Long.toUnsignedString(event.getBattleId()));
        }
    }

    private void onDispatch(BattleResultEvent event) {
        long battleId = event.getBattleId();
        byte[] payload;
        try {
            payload = event.toByteArray();
        } catch (RuntimeException e) {
            metrics.activity(ActivityEvent.SERIALIZE_FAILED);
            log.error("活动局结果序列化失败，不落库不发布 battle_id={}", Long.toUnsignedString(battleId), e);
            return;
        }
        CompletableFuture<Long> stored;
        try {
            stored = store.store(battleId, payload);
        } catch (RuntimeException e) {
            stored = CompletableFuture.failedFuture(e);
        }
        onOutbox(stored, (ok, error) -> {
            if (error != null) {
                metrics.activity(ActivityEvent.NOT_DURABLE);
                log.error("活动局结果落库失败，只发布一次、不登记 battle_id={}: {}", Long.toUnsignedString(battleId), error.toString());
                publish(event);
                return;
            }
            metrics.activity(ActivityEvent.STORED);
            publish(event);
            entries.put(battleId, new Entry(event, clockMillis.getAsLong()));
            if (timer == null) {
                timer = outbox.every(BattleRedis.ACTIVITY_RETRY_INTERVAL.toMillis(), this::tick);
            }
        });
    }

    private void publish(BattleResultEvent event) {
        try {
            publisher.publish(event);
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
            if (entry.probing) {
                continue;
            }
            if (now - entry.enqueuedAtMs > BattleRedis.OUTBOX_MAX_AGE.toMillis()) {
                entries.remove(battleId, entry);
                metrics.activity(ActivityEvent.EXPIRED);
                log.error("活动局结果超龄仍无结论，摘除（副本仍在 Redis，留给巡检器） battle_id={}", Long.toUnsignedString(battleId));
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
                switch (ActivityRetryRules.classify(present, entry.resent)) {
                    case ACKED -> {
                        entries.remove(battleId, entry);
                        metrics.activity(ActivityEvent.ACKED);
                    }
                    case EXHAUSTED -> {
                        entries.remove(battleId, entry);
                        metrics.activity(ActivityEvent.EXHAUSTED);
                        log.error("活动局结果重发用尽，摘除（副本仍在 Redis） battle_id={}", Long.toUnsignedString(battleId));
                    }
                    case RESEND -> {
                        entry.resent++;
                        metrics.activity(ActivityEvent.RESEND);
                        publish(entry.event);
                    }
                }
                stopTimerIfIdle();
            });
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

    private <T> void onOutbox(CompletableFuture<T> future, BiConsumer<T, Throwable> callback) {
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
