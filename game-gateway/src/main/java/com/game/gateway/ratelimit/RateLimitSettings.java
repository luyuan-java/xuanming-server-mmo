package com.game.gateway.ratelimit;

import com.game.common.zone.ZoneIds;

import java.util.List;
import java.util.Map;

/**
 * 开服限流配置（{@code game.gateway.rate-limit.*}，同基线 {@code gate.rate-limit.*}）。缺省关闭（同基线）。
 *
 * @param enabled               打开限流
 * @param zoneDefaultRps        区令牌桶每秒补充（缺省 500）
 * @param zoneDefaultBurst      区令牌桶容量（缺省 1000）
 * @param zoneOverrides         按区覆盖（区号 → rps / burst，非正的不覆盖）
 * @param ipRps                 单 IP 令牌桶每秒补充（缺省 5，同基线 application.yaml；基线代码缺省 2）
 * @param ipBurst               单 IP 令牌桶容量（缺省 20，同基线 application.yaml；基线代码缺省 10）
 * @param accountCooldownMs     同一身份同一 IP 两次请求的最短间隔（缺省 5000；按端点分开计；0 = 不判冷却）
 * @param trustedProxies        可信代理网段（CIDR）：只有对端在这些网段里才看 X-Forwarded-For
 * @param wave                  分波开放
 * @param queueRetryMinMs       排队提示 retry_after_ms 下限（缺省 2000ms，防止区桶高 RPS 时客户端 1ms 狂刷热键）
 * @param queueRetryJitterMs    排队提示 retry_after_ms 随机抖动上界（缺省 2000ms，[0, 上界) 均匀分散惊群）
 * @param queueRetryIpCostMilli 区桶空排队时从 IP 桶扣除的千分令牌数（缺省 100 = 0.1 个令牌，范围 [0, 1000]）
 */
public record RateLimitSettings(boolean enabled, Long zoneDefaultRps, Long zoneDefaultBurst,
                                Map<Long, ZoneLimit> zoneOverrides, Long ipRps, Long ipBurst, Long accountCooldownMs,
                                List<String> trustedProxies, Wave wave,
                                Long queueRetryMinMs, Long queueRetryJitterMs, Integer queueRetryIpCostMilli) {

    public static final long DEFAULT_QUEUE_RETRY_MIN_MS = 2000L;
    public static final long DEFAULT_QUEUE_RETRY_JITTER_MS = 2000L;
    public static final int DEFAULT_QUEUE_RETRY_IP_COST_MILLI = 100;

    public RateLimitSettings(boolean enabled, Long zoneDefaultRps, Long zoneDefaultBurst,
                             Map<Long, ZoneLimit> zoneOverrides, Long ipRps, Long ipBurst, Long accountCooldownMs,
                             List<String> trustedProxies, Wave wave) {
        this(enabled, zoneDefaultRps, zoneDefaultBurst, zoneOverrides, ipRps, ipBurst, accountCooldownMs,
                trustedProxies, wave, null, null, null);
    }

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public RateLimitSettings {
        zoneDefaultRps = positive(zoneDefaultRps, 500, "zone-default-rps");
        zoneDefaultBurst = positive(zoneDefaultBurst, 1000, "zone-default-burst");
        if (zoneOverrides != null) {
            for (Long zone : zoneOverrides.keySet()) {
                if (zone == null) {
                    throw new IllegalArgumentException("zone-overrides 的区号不能为 null");
                }
                ZoneIds.requiredBits(zone);
            }
        }
        zoneOverrides = zoneOverrides == null ? Map.of() : Map.copyOf(zoneOverrides);
        ipRps = positive(ipRps, 5, "ip-rps");
        ipBurst = positive(ipBurst, 20, "ip-burst");
        accountCooldownMs = accountCooldownMs == null ? 5000L : accountCooldownMs;
        if (accountCooldownMs < 0) {
            throw new IllegalArgumentException("game.gateway.rate-limit.account-cooldown-ms 不能为负");
        }
        trustedProxies = trustedProxies == null ? List.of() : List.copyOf(trustedProxies);
        wave = wave == null ? new Wave(false, null, null) : wave;
        queueRetryMinMs = queueRetryMinMs == null ? DEFAULT_QUEUE_RETRY_MIN_MS : queueRetryMinMs;
        if (queueRetryMinMs < 0) {
            throw new IllegalArgumentException("game.gateway.rate-limit.queue-retry-min-ms 不能为负");
        }
        queueRetryJitterMs = queueRetryJitterMs == null ? DEFAULT_QUEUE_RETRY_JITTER_MS : queueRetryJitterMs;
        if (queueRetryJitterMs < 0) {
            throw new IllegalArgumentException("game.gateway.rate-limit.queue-retry-jitter-ms 不能为负");
        }
        queueRetryIpCostMilli = queueRetryIpCostMilli == null ? DEFAULT_QUEUE_RETRY_IP_COST_MILLI : queueRetryIpCostMilli;
        if (queueRetryIpCostMilli < 0 || queueRetryIpCostMilli > 1000) {
            throw new IllegalArgumentException("game.gateway.rate-limit.queue-retry-ip-cost-milli 必须在 [0, 1000] 内");
        }
    }

    public static RateLimitSettings disabled() {
        return new RateLimitSettings(false, null, null, null, null, null, null, null, null);
    }

    /** 区的令牌桶参数（覆盖值非正时取缺省）。 */
    public long zoneRps(int zoneId) {
        ZoneLimit o = zoneOverrides.get(Integer.toUnsignedLong(zoneId));
        return o != null && o.rps() != null && o.rps() > 0 ? o.rps() : zoneDefaultRps;
    }

    public long zoneBurst(int zoneId) {
        ZoneLimit o = zoneOverrides.get(Integer.toUnsignedLong(zoneId));
        return o != null && o.burst() != null && o.burst() > 0 ? o.burst() : zoneDefaultBurst;
    }

    public record ZoneLimit(Long rps, Long burst) {
    }

    /**
     * 分波开放：从 {@code startEpochSec} 起，每一步在 {@code offsetSec} 之后放开 {@code allowZones}（含 -1 = 全部区）。
     *
     * @param startEpochSec 分波起点（Unix 秒）。<b>打开分波时必填</b>——基线缺省取网关进程启动时刻，多副本 / 重启后各副本波次不同步
     */
    public record Wave(boolean enabled, Long startEpochSec, List<WaveStep> schedule) {

        public Wave {
            schedule = schedule == null ? List.of() : List.copyOf(schedule);
            if (enabled && (startEpochSec == null || startEpochSec <= 0)) {
                throw new IllegalArgumentException("game.gateway.rate-limit.wave.start-epoch-sec 打开分波时必填"
                        + "（基线缺省取进程启动时刻，多副本 / 重启会让各副本的波次不同步）");
            }
        }
    }

    public record WaveStep(long offsetSec, List<Long> allowZones) {

        public WaveStep {
            if (allowZones != null) {
                for (Long zone : allowZones) {
                    if (zone == null || (zone != -1L && (zone <= 0 || zone > ZoneIds.MAX))) {
                        throw new IllegalArgumentException("allow-zones 仅允许 -1（全部）或 1–4294967295");
                    }
                }
            }
            allowZones = allowZones == null ? List.of() : List.copyOf(allowZones);
        }
    }

    private static long positive(Long value, long fallback, String name) {
        if (value == null) {
            return fallback;
        }
        if (value <= 0) {
            throw new IllegalArgumentException("game.gateway.rate-limit." + name + " 必须为正");
        }
        return value;
    }
}
