package com.game.scene.testing;

import com.game.scene.world.SceneClock;
import java.util.concurrent.TimeUnit;

/** 手动推进的场景时钟：单调纳秒与 UTC 毫秒一起走，测试完全控制时间。 */
public final class ManualClock implements SceneClock {

    /** 墙钟起点（2027-01-15 08:00:00 UTC），只用来检查 137 的 server_time_ms 取的是它。 */
    public static final long EPOCH_MILLIS_START = 1_800_000_000_000L;

    private long nanos = TimeUnit.SECONDS.toNanos(1_000);
    private long epochMillis = EPOCH_MILLIS_START;

    @Override
    public long nanoTime() {
        return nanos;
    }

    @Override
    public long epochMillis() {
        return epochMillis;
    }

    public void advanceMillis(long millis) {
        nanos += TimeUnit.MILLISECONDS.toNanos(millis);
        epochMillis += millis;
    }
}
