package com.game.scene.world;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 固定步长驱动器（基线 {@code World::Update}）：由场景逻辑线程上的定时任务每 {@link #STEP_NANOS} 调一次 {@link #run()}，
 * 按单调时钟的实际流逝补足应跑的帧数，每帧调一次 {@code step}。
 *
 * <p>规则与基线一致：累加器夹在 ±1 秒；每次调用最多跑 {@link #MAX_STEPS_PER_RUN} 帧，跑满后剩下的整帧时间直接扣掉、
 * 不补模拟（逻辑线程长时间卡住后不会连跑几十帧追赶，也不会越积越多）。
 *
 * <p>{@code step} 抛出的异常在这里记错误日志并吞掉：定时任务一旦抛异常，{@code scheduleAtFixedRate} 就不再调度，
 * 场景会整体停摆。只在场景逻辑线程上调用。
 */
public final class SceneTicker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(SceneTicker.class);

    /** 一帧的时长：20 FPS。 */
    public static final long STEP_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    static final int MAX_STEPS_PER_RUN = 5;
    static final long ACCUMULATOR_LIMIT_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final Runnable step;
    private final LongSupplier nanoClock;
    private long last;
    private long accumulated;

    public SceneTicker(Runnable step, LongSupplier nanoClock) {
        this.step = step;
        this.nanoClock = nanoClock;
        this.last = nanoClock.getAsLong();
    }

    @Override
    public void run() {
        long now = nanoClock.getAsLong();
        accumulated += now - last;
        last = now;
        accumulated = Math.max(-ACCUMULATOR_LIMIT_NANOS, Math.min(ACCUMULATOR_LIMIT_NANOS, accumulated));
        int steps = 0;
        while (accumulated >= STEP_NANOS && steps < MAX_STEPS_PER_RUN) {
            accumulated -= STEP_NANOS;
            steps++;
            try {
                step.run();
            } catch (RuntimeException e) {
                log.error("场景帧执行异常", e);
            }
        }
        if (accumulated >= STEP_NANOS) {
            // 跑满上限后剩下的整帧时间只扣掉、不补模拟（基线同义），保留不足一帧的零头。
            accumulated %= STEP_NANOS;
        }
    }
}
