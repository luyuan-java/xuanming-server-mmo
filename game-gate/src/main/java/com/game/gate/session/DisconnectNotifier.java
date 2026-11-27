package com.game.gate.session;

import com.game.api.proto.SessionClosed;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gate → login 断线通知至少送达一次重试器（对齐 2026-11-19 断线重连待确认第 1 条）：
 * <ul>
 *   <li>首次发送失败后保留原 {@link SessionClosed}（携带 {@code gate_node_id / gate_instance_id / session_id} 保证 login 侧幂等去重）；</li>
 *   <li>按 1s 起翻倍、30s 封顶加 ±25% 抖动退避重发，直到 login 确认成功或 gate 停机关闭；</li>
 *   <li>有界挂起上限防止极端 login 长时间失联打爆内存。</li>
 * </ul>
 */
public final class DisconnectNotifier implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DisconnectNotifier.class);

    public static final long INITIAL_BACKOFF_MS = 1_000L;
    public static final long MAX_BACKOFF_MS = 30_000L;
    public static final int DEFAULT_MAX_PENDING = 16_384;

    private final Function<SessionClosed, CompletableFuture<?>> sender;
    private final ScheduledExecutorService scheduler;
    private final DoubleSupplier randomUnit;
    private final int maxPending;
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();

    public DisconnectNotifier(Function<SessionClosed, CompletableFuture<?>> sender,
                              ScheduledExecutorService scheduler) {
        this(sender, scheduler, () -> ThreadLocalRandom.current().nextDouble(), DEFAULT_MAX_PENDING);
    }

    public DisconnectNotifier(Function<SessionClosed, CompletableFuture<?>> sender,
                              ScheduledExecutorService scheduler,
                              DoubleSupplier randomUnit,
                              int maxPending) {
        this.sender = Objects.requireNonNull(sender, "sender");
        this.scheduler = scheduler;
        this.randomUnit = Objects.requireNonNull(randomUnit, "randomUnit");
        this.maxPending = Math.max(1, maxPending);
    }

    /** 计算第 attempt 次重试（1 起）的退避毫秒：1s 翻倍、30s 封顶，叠加 ±25% 抖动。 */
    public static long backoffMs(int attempt, double randomUnit) {
        int shift = Math.min(5, Math.max(0, attempt - 1));
        long base = Math.min(MAX_BACKOFF_MS, INITIAL_BACKOFF_MS << shift);
        double clamped = Math.max(0.0, Math.min(1.0, randomUnit));
        return Math.max(1L, (long) (base * (0.75 + 0.50 * clamped)));
    }

    /** 投递一次断线通知；若首次失败且已配置调度器，则自动按退避重试直至确认。 */
    public void send(SessionClosed event, Runnable onSuccess, java.util.function.Consumer<Throwable> onAttemptFailure) {
        Objects.requireNonNull(event, "event");
        attempt(event, 0, false, onSuccess, onAttemptFailure);
    }

    private void attempt(SessionClosed event, int failureCount, boolean countedInPending,
                         Runnable onSuccess, java.util.function.Consumer<Throwable> onAttemptFailure) {
        if (closed.get()) {
            if (countedInPending) {
                pending.decrementAndGet();
            }
            return;
        }
        CompletableFuture<?> future;
        try {
            future = sender.apply(event);
        } catch (RuntimeException e) {
            handleFailure(event, failureCount + 1, countedInPending, e, onSuccess, onAttemptFailure);
            return;
        }
        if (future == null) {
            handleFailure(event, failureCount + 1, countedInPending,
                    new IllegalStateException("login.sessionClosed 返回空 future"), onSuccess, onAttemptFailure);
            return;
        }
        future.whenComplete((ack, error) -> {
            if (error == null) {
                if (countedInPending) {
                    pending.decrementAndGet();
                }
                if (onSuccess != null) {
                    onSuccess.run();
                }
            } else {
                handleFailure(event, failureCount + 1, countedInPending, error, onSuccess, onAttemptFailure);
            }
        });
    }

    private void handleFailure(SessionClosed event, int nextFailureCount, boolean countedInPending, Throwable error,
                               Runnable onSuccess, java.util.function.Consumer<Throwable> onAttemptFailure) {
        if (onAttemptFailure != null) {
            onAttemptFailure.accept(error);
        }
        if (closed.get() || scheduler == null || scheduler.isShutdown()) {
            if (countedInPending) {
                pending.decrementAndGet();
            }
            return;
        }
        boolean nowCounted = countedInPending;
        if (!nowCounted) {
            if (pending.incrementAndGet() > maxPending) {
                pending.decrementAndGet();
                log.error("断线通知重试队列已满 max={}，放弃重试 session={} player={}",
                        maxPending, event.getSession().getSessionId(), event.getSession().getPlayerId());
                return;
            }
            nowCounted = true;
        }
        long delayMs = backoffMs(nextFailureCount, randomUnit.getAsDouble());
        final boolean tracked = nowCounted;
        try {
            scheduler.schedule(() -> attempt(event, nextFailureCount, tracked, onSuccess, onAttemptFailure),
                    delayMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException rejected) {
            pending.decrementAndGet();
            log.warn("调度断线通知重试被拒（可能正在停机） session={}", event.getSession().getSessionId());
        }
    }

    public int pendingCount() {
        return pending.get();
    }

    @Override
    public void close() {
        closed.set(true);
    }
}
