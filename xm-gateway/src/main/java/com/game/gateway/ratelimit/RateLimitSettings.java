package com.game.gateway.ratelimit;

import java.util.List;
import java.util.Map;

/**
 * 开服限流配置（{@code xm.gateway.rate-limit.*}，同基线 {@code gate.rate-limit.*}）。缺省关闭（同基线）。
 *
 * @param enabled           打开限流
 * @param zoneDefaultRps    区令牌桶每秒补充（缺省 500）
 * @param zoneDefaultBurst  区令牌桶容量（缺省 1000）
 * @param zoneOverrides     按区覆盖（区号 → rps / burst，非正的不覆盖）
 * @param ipRps             单 IP 令牌桶每秒补充（缺省 5，同基线 application.yaml；基线代码缺省 2）
 * @param ipBurst           单 IP 令牌桶容量（缺省 20，同基线 application.yaml；基线代码缺省 10）
 * @param accountCooldownMs 同一身份同一 IP 两次请求的最短间隔（缺省 5000；按端点分开计；0 = 不判冷却）
 * @param trustedProxies    可信代理网段（CIDR）：只有对端在这些网段里才看 X-Forwarded-For
 * @param wave              分波开放
 */
public record RateLimitSettings(boolean enabled, Long zoneDefaultRps, Long zoneDefaultBurst,
                                Map<Integer, ZoneLimit> zoneOverrides, Long ipRps, Long ipBurst, Long accountCooldownMs,
                                List<String> trustedProxies, Wave wave) {

    public RateLimitSettings {
        zoneDefaultRps = positive(zoneDefaultRps, 500, "zone-default-rps");
        zoneDefaultBurst = positive(zoneDefaultBurst, 1000, "zone-default-burst");
        zoneOverrides = zoneOverrides == null ? Map.of() : Map.copyOf(zoneOverrides);
        ipRps = positive(ipRps, 5, "ip-rps");
        ipBurst = positive(ipBurst, 20, "ip-burst");
        accountCooldownMs = accountCooldownMs == null ? 5000L : accountCooldownMs;
        if (accountCooldownMs < 0) {
            throw new IllegalArgumentException("xm.gateway.rate-limit.account-cooldown-ms 不能为负");
        }
        trustedProxies = trustedProxies == null ? List.of() : List.copyOf(trustedProxies);
        wave = wave == null ? new Wave(false, null, null) : wave;
    }

    public static RateLimitSettings disabled() {
        return new RateLimitSettings(false, null, null, null, null, null, null, null, null);
    }

    /** 区的令牌桶参数（覆盖值非正时取缺省）。 */
    public long zoneRps(int zoneId) {
        ZoneLimit o = zoneOverrides.get(zoneId);
        return o != null && o.rps() != null && o.rps() > 0 ? o.rps() : zoneDefaultRps;
    }

    public long zoneBurst(int zoneId) {
        ZoneLimit o = zoneOverrides.get(zoneId);
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
                throw new IllegalArgumentException("xm.gateway.rate-limit.wave.start-epoch-sec 打开分波时必填"
                        + "（基线缺省取进程启动时刻，多副本 / 重启会让各副本的波次不同步）");
            }
        }
    }

    public record WaveStep(long offsetSec, List<Long> allowZones) {

        public WaveStep {
            allowZones = allowZones == null ? List.of() : List.copyOf(allowZones);
        }
    }

    private static long positive(Long value, long fallback, String name) {
        if (value == null) {
            return fallback;
        }
        if (value <= 0) {
            throw new IllegalArgumentException("xm.gateway.rate-limit." + name + " 必须为正");
        }
        return value;
    }
}
