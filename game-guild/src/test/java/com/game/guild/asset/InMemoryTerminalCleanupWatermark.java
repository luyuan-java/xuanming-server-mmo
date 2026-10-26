package com.game.guild.asset;

import com.game.common.deadline.Deadline;
import java.util.concurrent.atomic.AtomicLong;

/** SQL 夹具只替换 Redis 端口；清理推进与查询使用同一份只增状态。 */
public final class InMemoryTerminalCleanupWatermark implements TerminalCleanupWatermark {
    private final AtomicLong cutoff = new AtomicLong();

    @Override public void advance(long offered, Deadline deadline) { cutoff.accumulateAndGet(offered, Math::max); }
    @Override public long read(Deadline deadline) { return cutoff.get(); }
}
