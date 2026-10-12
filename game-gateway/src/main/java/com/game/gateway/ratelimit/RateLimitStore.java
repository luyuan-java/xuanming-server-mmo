package com.game.gateway.ratelimit;

import java.time.Duration;

/** 限流的共享存储（生产实现 {@link RedisRateLimitStore}；测试换内存实现）。时间由存储自己取（Redis 实现取服务器时间，各副本一个时钟）。 */
public interface RateLimitStore {

    /**
     * 先 IP 桶、再区桶，原子地各取一个：IP 桶空 → {@link Admission.Kind#IP_EMPTY}（区桶不碰）；区桶空 →
     * {@link Admission.Kind#ZONE_EMPTY}（IP 令牌退回——排队中的重试不吃 IP 桶）；都有 → 两边各扣一个。
     *
     * @param zone 区桶；为 null 只判 IP 桶
     */
    Admission admit(Bucket ip, Bucket zone);

    /** 冷却：键不存在就占上（TTL = 冷却时长）返回 true；还在冷却返回 false。 */
    boolean tryCooldown(String key, Duration cooldown);

    /** 一个令牌桶：容量 {@code burst}、每秒补 {@code rps}。 */
    record Bucket(String key, long rps, long burst) {
    }

    /**
     * @param waitMs    区桶空时补到一个令牌要等的毫秒（向下取整）
     * @param remaining 区桶取后余量（只判 IP 桶时是 IP 桶的）
     */
    record Admission(Kind kind, long waitMs, long remaining) {

        public enum Kind { OK, IP_EMPTY, ZONE_EMPTY }
    }
}
