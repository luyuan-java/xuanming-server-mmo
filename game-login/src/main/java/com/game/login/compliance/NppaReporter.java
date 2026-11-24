package com.game.login.compliance;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * NPPA 上下线行为后台上报器（对齐 Go {@code nppa/reporter.go}）：每秒清扫租约到期会话并分批上报，
 * 过滤超过 150s 的过期事件，按全服共享 {@link NppaQuota#LOGINOUT_QUOTA} 控速。
 */
public final class NppaReporter implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(NppaReporter.class);
    private static final Duration MAX_AGE = Duration.ofSeconds(150);

    @FunctionalInterface
    public interface Sender {
        List<Integer> send(List<NppaClient.Behavior> batch) throws NppaClient.NppaException;
    }

    private final NppaLedger ledger;
    private final NppaQuota quota;
    private final Sender sender;
    private final Clock clock;
    private final MeterRegistry meters;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile ScheduledExecutorService scheduler;

    public NppaReporter(NppaLedger ledger, NppaQuota quota, Sender sender, Clock clock, MeterRegistry meters) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.quota = quota;
        this.sender = Objects.requireNonNull(sender, "sender");
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.meters = meters;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("login-nppa-reporter").daemon(true).factory());
        scheduler = exec;
        exec.scheduleWithFixedDelay(this::tickSafely, 1, 1, TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        ScheduledExecutorService exec = scheduler;
        if (exec != null) {
            exec.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void tickSafely() {
        try {
            tick();
        } catch (Throwable t) {
            log.error("[nppa] reporter tick failed: {}", t.toString());
        }
    }

    public void tick() {
        try {
            int swept = ledger.sweep();
            if (swept > 0) {
                count("swept", swept);
                log.info("[nppa] filled in logouts for {} sessions whose lease ran out", swept);
            }
        } catch (RuntimeException e) {
            log.error("[nppa] ledger sweep failed: {}", e.toString());
        }
        while (sendBatch()) {
            // drain ready batches within quota
        }
    }

    boolean sendBatch() {
        List<NppaLedger.Claimed> claimed;
        try {
            claimed = ledger.claim(NppaClient.MAX_BATCH);
        } catch (RuntimeException e) {
            log.error("[nppa] ledger claim failed: {}", e.toString());
            return false;
        }
        if (claimed.isEmpty()) {
            return false;
        }
        long nowSec = clock.instant().getEpochSecond();
        long minOt = nowSec - MAX_AGE.toSeconds();
        List<NppaLedger.Claimed> discard = new ArrayList<>();
        List<NppaLedger.Claimed> members = new ArrayList<>();
        List<NppaClient.Behavior> batch = new ArrayList<>();
        int expired = 0;
        for (NppaLedger.Claimed c : claimed) {
            if (!c.valid()) {
                log.error("[nppa] unreadable ledger event dropped");
                discard.add(c);
            } else if (c.behavior().ot() < minOt) {
                expired++;
                discard.add(c);
            } else if (c.behavior().ot() <= nowSec) {
                members.add(c);
                batch.add(c.behavior());
            }
        }
        if (!discard.isEmpty()) {
            try {
                ledger.ack(discard);
            } catch (RuntimeException e) {
                log.error("[nppa] ledger ack of dropped events failed: {}", e.toString());
            }
        }
        if (expired > 0) {
            count("dropped_age", expired);
            log.error("[nppa] {} behaviors older than {}s dropped", expired, MAX_AGE.toSeconds());
        }
        if (batch.isEmpty()) {
            return !discard.isEmpty();
        }
        if (quota != null) {
            boolean allowed;
            try {
                allowed = quota.allow(NppaQuota.LOGINOUT_QUOTA);
            } catch (RuntimeException e) {
                log.error("[nppa] loginout quota unavailable: {}", e.toString());
                allowed = false;
            }
            if (!allowed) {
                try {
                    ledger.release(members);
                } catch (RuntimeException e) {
                    log.error("[nppa] ledger release failed: {}", e.toString());
                }
                return false;
            }
        }
        List<Integer> rejected;
        try {
            rejected = sender.send(batch);
        } catch (NppaClient.NppaException | RuntimeException e) {
            count("send_failed", 1);
            log.error("[nppa] loginout report failed batch={} err={}", batch.size(), e.getMessage());
            return false;
        }
        try {
            ledger.ack(members);
        } catch (RuntimeException e) {
            log.error("[nppa] ledger ack failed batch={} err={}", batch.size(), e.toString());
        }
        int rejCount = rejected == null ? 0 : rejected.size();
        if (rejCount > 0) {
            count("rejected", rejCount);
            log.error("[nppa] {} of {} behaviors rejected numbers={}", rejCount, batch.size(), rejected);
        }
        count("sent", batch.size() - rejCount);
        return true;
    }

    private void count(String result, int n) {
        if (meters != null && n > 0) {
            meters.counter("login.nppa.report", "result", result).increment(n);
        }
    }
}
