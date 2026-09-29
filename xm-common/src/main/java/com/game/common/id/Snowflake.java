package com.game.common.id;

import java.util.function.LongSupplier;

/**
 * 雪花 ID：{@code [符号 1][毫秒时间 41][worker 10][序号 12]}。
 *
 * <p>Java 版只在一种节点类型里产生同一种 ID（例如 player_id 只由 login 产生），worker 由节点号租约给出，
 * 这样 worker 不会在不同节点类型间撞号（mmorpg AGENTS.md §7.1 的同一条不变量）。
 *
 * <p>时钟回拨：回拨不超过 {@link #MAX_BACKWARD_MS} 时自旋等待追平；超过即抛异常，宁可失败也不发重复号。
 * 线程安全（{@code synchronized}，发号不在热路径上）。
 */
public final class Snowflake {

    /** 2026-01-01T00:00:00Z，毫秒。 */
    public static final long DEFAULT_EPOCH_MS = 1767225600000L;
    public static final int WORKER_BITS = 10;
    public static final int SEQUENCE_BITS = 12;
    public static final int MAX_WORKER = (1 << WORKER_BITS) - 1;
    static final long MAX_BACKWARD_MS = 5;

    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1;

    private final long epochMs;
    private final long workerId;
    private final LongSupplier clockMs;
    private long lastMs = -1;
    private long sequence;

    public Snowflake(int workerId, long epochMs, LongSupplier clockMs) {
        if (workerId < 0 || workerId > MAX_WORKER) {
            throw new IllegalArgumentException("worker 超出 [0, " + MAX_WORKER + "]: " + workerId);
        }
        this.workerId = workerId;
        this.epochMs = epochMs;
        this.clockMs = clockMs;
    }

    public Snowflake(int workerId) {
        this(workerId, DEFAULT_EPOCH_MS, System::currentTimeMillis);
    }

    public synchronized long nextId() {
        long now = clockMs.getAsLong();
        if (now < lastMs) {
            long behind = lastMs - now;
            if (behind > MAX_BACKWARD_MS) {
                throw new IllegalStateException("时钟回拨 " + behind + "ms，拒绝发号");
            }
            while (now < lastMs) {
                Thread.onSpinWait();
                now = clockMs.getAsLong();
            }
        }
        if (now == lastMs) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                while (now <= lastMs) {
                    Thread.onSpinWait();
                    now = clockMs.getAsLong();
                }
            }
        } else {
            sequence = 0;
        }
        lastMs = now;
        long elapsed = now - epochMs;
        if (elapsed < 0) {
            throw new IllegalStateException("当前时间早于雪花纪元");
        }
        return (elapsed << (WORKER_BITS + SEQUENCE_BITS)) | (workerId << SEQUENCE_BITS) | sequence;
    }

    public static int workerOf(long id) {
        return (int) ((id >>> SEQUENCE_BITS) & MAX_WORKER);
    }
}
