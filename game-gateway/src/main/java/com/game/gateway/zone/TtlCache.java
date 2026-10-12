package com.game.gateway.zone;

import java.time.Duration;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 一份整体缓存的读面（区服目录、生效中的公告）：读到的值缓存 {@code ttl}；读失败也缓存同样久——故障期间请求立即失败，
 * 不在锁上排队一个接一个地重试（每次重试都可能等满数据库的连接 / 取连接超时，把请求线程全堵住）。
 * 过期后第一个请求在锁里重读，其余请求等它，同一时刻至多一次读。
 *
 * <p>线程安全；{@link #get()} 会阻塞（读数据库），只在允许阻塞的线程上调用。
 */
public final class TtlCache<T> {

    private record Entry<T>(T value, RuntimeException failure, long atNanos) {
    }

    private final Supplier<T> loader;
    private final LongSupplier nanoClock;
    private final long ttlNanos;
    private final Object lock = new Object();
    private volatile Entry<T> entry;

    public TtlCache(Supplier<T> loader, LongSupplier nanoClock, Duration ttl) {
        this.loader = loader;
        this.nanoClock = nanoClock;
        this.ttlNanos = ttl.toNanos();
    }

    /** 缓存的值；最近一次读失败（{@code ttl} 内）抛 {@link IllegalStateException}（原因是那次的异常）。 */
    public T get() {
        Entry<T> current = entry;
        if (fresh(current)) {
            return valueOf(current);
        }
        synchronized (lock) {
            current = entry;
            if (fresh(current)) {
                return valueOf(current);
            }
            try {
                T loaded = loader.get();
                entry = new Entry<>(loaded, null, nanoClock.getAsLong());
                return loaded;
            } catch (RuntimeException e) {
                entry = new Entry<>(null, e, nanoClock.getAsLong());
                throw e;
            }
        }
    }

    private boolean fresh(Entry<T> current) {
        return current != null && nanoClock.getAsLong() - current.atNanos() < ttlNanos;
    }

    private static <T> T valueOf(Entry<T> current) {
        if (current.failure() != null) {
            throw new IllegalStateException("最近一次读取失败，稍后重试", current.failure());
        }
        return current.value();
    }
}
