package com.game.match.testing;

import com.game.common.deadline.Deadline;
import com.game.match.port.RedisClock;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 手拨的「Redis 时间」：{@link RedisClock} 的测试替身，也是 {@link InMemoryTicketStore} 的时间来源（票据的 {@code enqueued_at_ms}、
 * {@code not_before_ms}、TTL 到期都按它算）。把同一个实例交给票据存储与被测对象，拨一次两边一起走——等价于真 Redis 上的 {@code TIME}。
 *
 * <pre>
 * ManualRedisClock clock = new ManualRedisClock();          // 起点 1_800_000_000_000
 * InMemoryTicketStore store = new InMemoryTicketStore(clock);
 * clock.advanceSeconds(45);                                  // 锚点「已等 45 秒」
 * clock.faults.failNext("nowMs");                            // 下一次读时间失败
 * </pre>
 * 线程安全。
 */
public final class ManualRedisClock implements RedisClock {

    /** 缺省起点：一个明显不是「现在」的时刻，免得测试误用本机时钟也碰巧通过。 */
    public static final long DEFAULT_START_MS = 1_800_000_000_000L;

    /** 故障注入：操作名 {@code "nowMs"}。 */
    public final Faults faults = new Faults();
    private final AtomicLong nowMs;
    private final AtomicInteger reads = new AtomicInteger();

    public ManualRedisClock() {
        this(DEFAULT_START_MS);
    }

    public ManualRedisClock(long startMs) {
        this.nowMs = new AtomicLong(startMs);
    }

    @Override
    public long nowMs(Deadline d) {
        reads.incrementAndGet();
        faults.check("nowMs");
        return nowMs.get();
    }

    /** 当前时刻（不计入 {@link #reads()}，不触发故障注入）：替身内部与测试断言用。 */
    public long peekMs() {
        return nowMs.get();
    }

    public ManualRedisClock set(long ms) {
        nowMs.set(ms);
        return this;
    }

    public ManualRedisClock advanceMs(long ms) {
        nowMs.addAndGet(ms);
        return this;
    }

    public ManualRedisClock advanceSeconds(long seconds) {
        return advanceMs(seconds * 1000);
    }

    /** 被测对象经 {@link #nowMs(Deadline)} 读了几次（断言「两个期限取同一次读数」用）。 */
    public int reads() {
        return reads.get();
    }
}
