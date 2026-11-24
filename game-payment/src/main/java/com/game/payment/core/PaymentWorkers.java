package com.game.payment.core;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * 支付服务 5 个后台租约推进循环（查单关单、推进商城、执行退款、Google consume 收尾、历史游标补漏）。
 * 所有推进幂等且带租约 CAS，多副本同时运行安全；收到回调或记账后经 {@link #kick()} 立即异步触发一轮商城推进。
 */
public final class PaymentWorkers implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PaymentWorkers.class);

    private final PaymentService service;
    private final Duration interval;
    private final Duration refundPollInterval;
    private final Duration historyInterval;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean kickQueued = new AtomicBoolean(false);
    private volatile ScheduledExecutorService scheduler;

    public PaymentWorkers(PaymentService service, Duration interval, Duration refundPollInterval,
                          Duration historyInterval, boolean enabled) {
        this.service = Objects.requireNonNull(service, "service");
        this.interval = interval == null ? Duration.ofSeconds(2) : interval;
        this.refundPollInterval = refundPollInterval == null ? Duration.ofSeconds(5) : refundPollInterval;
        this.historyInterval = historyInterval == null ? Duration.ofMinutes(5) : historyInterval;
        this.enabled = enabled;
        this.service.setKickCallback(this::kick);
    }

    /** 非阻塞叫醒商城推进器：合并并发信号，避免每次回调堆积多余任务。 */
    public void kick() {
        ScheduledExecutorService exec = this.scheduler;
        if (!running.get() || exec == null) {
            return;
        }
        if (kickQueued.compareAndSet(false, true)) {
            try {
                exec.execute(() -> {
                    kickQueued.set(false);
                    runSafely("pushMall(kick)", service::pushMall);
                });
            } catch (RejectedExecutionException stopping) {
                kickQueued.set(false);
                log.debug("[payment] ignore pushMall kick during worker shutdown");
            }
        }
    }

    @Override
    public synchronized void start() {
        if (!enabled || running.get()) {
            return;
        }
        ScheduledExecutorService exec = Executors.newScheduledThreadPool(5,
                Thread.ofPlatform().name("payment-worker-", 0).daemon(true).factory());
        this.scheduler = exec;
        running.set(true);
        long baseMs = Math.max(200L, interval.toMillis());
        long refundMs = Math.max(500L, refundPollInterval.toMillis());
        long histMs = Math.max(1000L, historyInterval.toMillis());
        exec.scheduleWithFixedDelay(() -> runSafely("sweepIntents", service::sweepIntents), baseMs, baseMs, TimeUnit.MILLISECONDS);
        exec.scheduleWithFixedDelay(() -> runSafely("pushMall", service::pushMall), baseMs, baseMs, TimeUnit.MILLISECONDS);
        exec.scheduleWithFixedDelay(() -> runSafely("processRefunds", service::processRefunds), refundMs, refundMs, TimeUnit.MILLISECONDS);
        exec.scheduleWithFixedDelay(() -> runSafely("finalizePending", service::finalizePending), baseMs, baseMs, TimeUnit.MILLISECONDS);
        exec.scheduleWithFixedDelay(() -> runSafely("pollHistory", service::pollHistory), histMs, histMs, TimeUnit.MILLISECONDS);
        log.info("[payment] background workers started interval={}ms refund={}ms history={}ms", baseMs, refundMs, histMs);
    }

    @Override
    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        ScheduledExecutorService exec = this.scheduler;
        this.scheduler = null;
        if (exec != null) {
            exec.shutdown();
            try {
                if (!exec.awaitTermination(15, TimeUnit.SECONDS)) {
                    exec.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exec.shutdownNow();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private static void runSafely(String name, Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            log.error("[payment] background task {} failed: {}", name, t.getMessage(), t);
        }
    }
}
