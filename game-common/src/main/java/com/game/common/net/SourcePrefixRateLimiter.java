package com.game.common.net;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 边缘连接按公网来源前缀限速器（对齐 C++ {@code SourceRateGate}，IPv4 {@code /24}、IPv6 {@code /64}）。
 *
 * <p>基于 JDK 标准并发容器 {@link ConcurrentHashMap} 实现有界 + 空闲过期驱逐令牌桶：
 * <ul>
 *   <li>仅对公网来源生效：自动跳过私网、回环、链路本地、CGNAT {@code 100.64/10}、{@code 0/8} 及 IPv6 {@code ::/64} / {@code fc00::/7} / {@code fe80::/10}；</li>
 *   <li>仅在承压（{@code underPressure = true}）时检查限速；未承压直接放行；</li>
 *   <li>空闲超过 {@code idleExpiry}（默认 10 分钟）的前缀桶自动淘汰，避免长跑满 65,536 条后永久 fail-open；</li>
 *   <li>瞬时活跃前缀达到 {@code maxPrefixes} 时先触发一次空闲驱逐，仍满时对新前缀 fail-open 放行并累计 {@link #untrackedTotal()}。</li>
 * </ul>
 */
public final class SourcePrefixRateLimiter {

    public static final int DEFAULT_MAX_PREFIXES = 65_536;
    public static final Duration DEFAULT_IDLE_EXPIRY = Duration.ofMinutes(10);

    private final double ratePerSecond;
    private final double burst;
    private final int maxPrefixes;
    private final long idleExpiryNanos;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<Long, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong untrackedTotal = new AtomicLong();
    private final AtomicBoolean sweeping = new AtomicBoolean();

    public SourcePrefixRateLimiter(double ratePerSecond, double burst) {
        this(ratePerSecond, burst, DEFAULT_MAX_PREFIXES, DEFAULT_IDLE_EXPIRY, System::nanoTime);
    }

    public SourcePrefixRateLimiter(double ratePerSecond, double burst, int maxPrefixes, LongSupplier nanoClock) {
        this(ratePerSecond, burst, maxPrefixes, DEFAULT_IDLE_EXPIRY, nanoClock);
    }

    public SourcePrefixRateLimiter(double ratePerSecond, double burst, int maxPrefixes,
                                   Duration idleExpiry, LongSupplier nanoClock) {
        if (ratePerSecond < 0 || burst < 0 || maxPrefixes <= 0) {
            throw new IllegalArgumentException("来源限速参数非法");
        }
        Objects.requireNonNull(idleExpiry, "idleExpiry");
        if (idleExpiry.isNegative() || idleExpiry.isZero()) {
            throw new IllegalArgumentException("idleExpiry 必须为正: " + idleExpiry);
        }
        this.ratePerSecond = ratePerSecond;
        this.burst = burst <= 0 && ratePerSecond > 0 ? ratePerSecond * 10.0 : burst;
        this.maxPrefixes = maxPrefixes;
        this.idleExpiryNanos = idleExpiry.toNanos();
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    public boolean enabled() {
        return ratePerSecond > 0;
    }

    /** 仅查询是否放行，不消费令牌；非公网、未开启或未承压一律放行。 */
    public boolean canAdmit(InetAddress address, boolean underPressure) {
        if (!enabled() || !underPressure || !isPublicAddress(address)) {
            return true;
        }
        long key = prefixKey(address);
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            return true;
        }
        long now = nanoClock.getAsLong();
        synchronized (bucket) {
            bucket.refill(now, ratePerSecond, burst);
            return bucket.tokens >= 1.0;
        }
    }

    /** 全部前序闸门通过后记账；满表前先驱逐空闲过期前缀，仍满时 fail-open 并计数。 */
    public void recordAdmitted(InetAddress address, boolean underPressure) {
        if (!enabled() || !underPressure || !isPublicAddress(address)) {
            return;
        }
        long key = prefixKey(address);
        Bucket bucket = buckets.get(key);
        long now = nanoClock.getAsLong();
        if (bucket == null) {
            if (buckets.size() >= maxPrefixes) {
                evictIdle(now);
                if (buckets.size() >= maxPrefixes) {
                    untrackedTotal.incrementAndGet();
                    return;
                }
            }
            bucket = buckets.computeIfAbsent(key, k -> new Bucket(burst, now));
        }
        synchronized (bucket) {
            bucket.refill(now, ratePerSecond, burst);
            bucket.tokens = Math.max(0.0, bucket.tokens - 1.0);
        }
    }

    /** 一步判定并记账（适合仅有一道来源闸门的场景）。 */
    public boolean tryAdmit(InetAddress address, boolean underPressure) {
        if (!canAdmit(address, underPressure)) {
            return false;
        }
        recordAdmitted(address, underPressure);
        return true;
    }

    /** 驱逐所有超过 {@code idleExpiry} 未活跃的前缀桶，同一时刻至多一个线程扫描。 */
    public void evictIdle(long nowNanos) {
        if (!sweeping.compareAndSet(false, true)) {
            return;
        }
        try {
            for (Map.Entry<Long, Bucket> entry : buckets.entrySet()) {
                Bucket b = entry.getValue();
                if (nowNanos - b.lastNanos >= idleExpiryNanos) {
                    buckets.remove(entry.getKey(), b);
                }
            }
        } finally {
            sweeping.set(false);
        }
    }

    public int trackedPrefixes() {
        evictIdle(nanoClock.getAsLong());
        return buckets.size();
    }

    public long untrackedTotal() {
        return untrackedTotal.get();
    }

    /** 仅公网地址返回 true；内网代理/LB/小游戏边缘地址一律返回 false，避免误把后方玩家归为同一来源。 */
    public static boolean isPublicAddress(InetAddress address) {
        if (address == null) {
            return false;
        }
        byte[] raw = address.getAddress();
        if (address instanceof Inet4Address || raw.length == 4) {
            int b0 = raw[0] & 0xFF;
            int b1 = raw[1] & 0xFF;
            if (b0 == 0 || b0 == 10 || b0 == 127) {
                return false;
            }
            if (b0 == 100 && (b1 & 0xC0) == 64) {
                return false; // 100.64.0.0/10 CGNAT
            }
            if (b0 == 169 && b1 == 254) {
                return false; // 169.254.0.0/16 link-local
            }
            if (b0 == 172 && b1 >= 16 && b1 <= 31) {
                return false; // 172.16.0.0/12
            }
            if (b0 == 192 && b1 == 168) {
                return false; // 192.168.0.0/16
            }
            return b0 < 224; // 跳过组播/保留段 224.0.0.0/4+
        }
        if (address instanceof Inet6Address && raw.length == 16) {
            boolean v4Mapped = true;
            for (int i = 0; i < 10; i++) {
                if (raw[i] != 0) {
                    v4Mapped = false;
                    break;
                }
            }
            if (v4Mapped && (raw[10] & 0xFF) == 0xFF && (raw[11] & 0xFF) == 0xFF) {
                try {
                    return isPublicAddress(InetAddress.getByAddress(new byte[] {raw[12], raw[13], raw[14], raw[15]}));
                } catch (Exception ignored) {
                    return false;
                }
            }
            boolean zeroHigh64 = true;
            for (int i = 0; i < 8; i++) {
                if (raw[i] != 0) {
                    zeroHigh64 = false;
                    break;
                }
            }
            if (zeroHigh64) {
                return false;
            }
            int b0 = raw[0] & 0xFF;
            int b1 = raw[1] & 0xFF;
            if ((b0 & 0xFE) == 0xFC) {
                return false; // fc00::/7 ULA
            }
            if (b0 == 0xFE && (b1 & 0xC0) == 0x80) {
                return false; // fe80::/10 link-local
            }
            return b0 != 0xFF; // 跳过组播 ff00::/8
        }
        return false;
    }

    /** IPv4 取高 24 位（带版本标志），IPv6 取高 64 位。 */
    public static long prefixKey(InetAddress address) {
        byte[] raw = address.getAddress();
        if (raw.length == 4) {
            return (1L << 63)
                    | ((raw[0] & 0xFFL) << 16)
                    | ((raw[1] & 0xFFL) << 8)
                    | (raw[2] & 0xFFL);
        }
        long key = 0L;
        for (int i = 0; i < 8; i++) {
            key = (key << 8) | (raw[i] & 0xFFL);
        }
        return key;
    }

    private static final class Bucket {
        double tokens;
        volatile long lastNanos;

        Bucket(double tokens, long lastNanos) {
            this.tokens = tokens;
            this.lastNanos = lastNanos;
        }

        void refill(long nowNanos, double ratePerSec, double capacity) {
            long elapsed = nowNanos - lastNanos;
            if (elapsed > 0) {
                tokens = Math.min(capacity, tokens + (elapsed / 1_000_000_000.0) * ratePerSec);
                lastNanos = nowNanos;
            }
        }
    }
}
