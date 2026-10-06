package com.game.data.ops.fence;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 严格递增的毫秒时钟：{@code max(墙钟, 上次 + 1)}。给 xm-data 自己的 {@code PlayerStore} 用（data-ops-spec §7.5、§9.2 第 6 条）：
 * xm-data 的连接串带 {@code useAffectedRows=true}，{@code updateStateHeld} 在「所有列都没变、{@code updated_at} 与上次同一毫秒」时影响 0 行，
 * 会被误判为失去围栏；每次取到的毫秒都不同就不会出现「没变」。写事务开头的加锁读已确认持有，这只是兜底。线程安全。
 */
public final class StrictClock implements LongSupplier {

    private final LongSupplier wall;
    private final AtomicLong last = new AtomicLong(Long.MIN_VALUE);

    public StrictClock(LongSupplier wall) {
        this.wall = wall;
    }

    @Override
    public long getAsLong() {
        long now = wall.getAsLong();
        return last.updateAndGet(prev -> Math.max(now, prev + 1));
    }
}
