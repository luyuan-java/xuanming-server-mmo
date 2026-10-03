package com.game.scene.player;

import java.util.ArrayDeque;

/**
 * 一个获取滑动窗口（某玩家 × 某币种）：窗口内的获取次数与累计量（同基线 PlayerAnomalyBucket）。只在逻辑线程上读写。
 * 累计量饱和在 {@code Long.MAX_VALUE}（基线 uint64 会回绕，回绕后反而低于阈值）；饱和期间剪枝后按剩余事件重算。
 */
public final class GainWindow {

    private record Event(long atNanos, long amount) {
    }

    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private long total;
    private boolean saturated;
    private boolean alerting;

    /**
     * 剪枝再记这一次（{@link #prune} + {@link #append}）。
     *
     * @param amount 获取量（正数）
     */
    public void record(long nowNanos, long amount, long windowNanos) {
        prune(nowNanos, windowNanos);
        append(nowNanos, amount);
    }

    /** 剪掉窗口外的事件（早于 {@code nowNanos - windowNanos}，边界上的保留，同基线）。 */
    public void prune(long nowNanos, long windowNanos) {
        boolean pruned = false;
        while (!events.isEmpty() && nowNanos - events.peekFirst().atNanos() > windowNanos) {
            Event expired = events.pollFirst();
            if (!saturated) {
                total -= expired.amount();
            }
            pruned = true;
        }
        if (saturated && pruned) {
            total = 0;
            saturated = false;
            for (Event e : events) {
                add(e.amount());
            }
        }
    }

    /** 记一次获取（不剪枝）。 */
    public void append(long nowNanos, long amount) {
        events.addLast(new Event(nowNanos, amount));
        add(amount);
    }

    /** 窗口内的次数（含刚记的这次）。 */
    public int count() {
        return events.size();
    }

    /** 窗口内的累计量（饱和于 {@code Long.MAX_VALUE}）。 */
    public long total() {
        return total;
    }

    /** 当前是否处于已告警的越线状态（越线时告警一次，回到线内才重新武装）。 */
    public boolean alerting() {
        return alerting;
    }

    public void alerting(boolean value) {
        alerting = value;
    }

    private void add(long amount) {
        long sum = total + amount;
        if (saturated || ((total ^ sum) & (amount ^ sum)) < 0) {
            total = Long.MAX_VALUE;
            saturated = true;
        } else {
            total = sum;
        }
    }
}
