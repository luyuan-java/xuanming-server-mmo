package com.game.match.tournament;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * 全服赛季战队锦标赛《群仙问道赛》阶段自动推进后台驱动器（与 Go 版 {@code StartTournamentLoop} 5 秒周期对齐）。
 *
 * <p>SQL 提交点自带幂等行锁保护，多副本并行调用同一赛季推进依然安全。
 */
public final class TournamentSeasonDriver implements SmartLifecycle, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TournamentSeasonDriver.class);
    private static final long DEFAULT_INTERVAL_MS = 5_000L;

    @FunctionalInterface
    public interface IntermissionLauncher {
        void launch(TournamentStore.LaunchSpec spec) throws Exception;
    }

    private final TournamentStore store;
    private final LongSupplier idGenerator;
    private final long intervalMs;
    private final IntermissionLauncher intermissionLauncher;
    private final AtomicBoolean running = new AtomicBoolean();
    private ScheduledExecutorService scheduler;

    public TournamentSeasonDriver(TournamentStore store, LongSupplier idGenerator) {
        this(store, idGenerator, DEFAULT_INTERVAL_MS, null);
    }

    public TournamentSeasonDriver(TournamentStore store, LongSupplier idGenerator, long intervalMs) {
        this(store, idGenerator, intervalMs, null);
    }

    public TournamentSeasonDriver(TournamentStore store, LongSupplier idGenerator, long intervalMs,
                                  IntermissionLauncher intermissionLauncher) {
        this.store = Objects.requireNonNull(store, "store");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.intervalMs = Math.max(50L, intervalMs);
        this.intermissionLauncher = intermissionLauncher;
    }

    @Override
    public synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("tournament-season-driver").daemon(true).factory());
        scheduler.scheduleWithFixedDelay(this::tick, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("[tournament] season driver started intervalMs={}", intervalMs);
    }

    void tick() {
        if (!running.get()) {
            return;
        }
        try {
            store.advanceSeason(0L, idGenerator);
        } catch (Exception e) {
            log.warn("[tournament] advanceSeason tick failed: {}", e.getMessage());
        }
        if (intermissionLauncher != null) {
            try {
                for (TournamentStore.LaunchSpec spec : store.takeDueIntermissionLaunches(0L)) {
                    try {
                        intermissionLauncher.launch(spec);
                    } catch (Exception e) {
                        log.warn("[tournament] intermission auto-launch failed match={} game={}: {}",
                                Long.toUnsignedString(spec.matchId()), spec.gameIndex(), e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("[tournament] takeDueIntermissionLaunches tick failed: {}", e.getMessage());
            }
        }
    }

    @Override
    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            scheduler = null;
        }
        log.info("[tournament] season driver stopped");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    @Override
    public void close() {
        stop();
    }
}
