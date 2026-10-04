package com.game.gateway.ratelimit;

import com.game.discovery.RedisKeys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 开服限流（同 mmorpg AssignGateRateLimiter），依次判：
 * <ol>
 *   <li>分波：区不在当前波次 → 排队（retry_after = 距开放的秒数 × 1000，以后都不放它给 3600 s；queue_pos = -1）；</li>
 *   <li>IP 令牌桶空 → 429 {@code IP_RATE_LIMIT}；</li>
 *   <li>区令牌桶（全部 gateway 共享）空 → 排队（retry_after = 补到一个令牌要等的毫秒）；这时 IP 令牌退回；</li>
 *   <li>同一身份（账号 / 三方令牌）同一 IP 的冷却（按端点分开：login / assign）→ 429 {@code ACCOUNT_COOLDOWN}。</li>
 * </ol>
 * 两个桶在存储里一次原子地判（{@link RateLimitStore#admit}）。Redis 出错放行（fail-open，同基线：限流是保护手段，不能因为它不可用
 * 把全服挡在门外），之后 {@link #FAIL_OPEN_HOLD} 内直接放行、不再碰 Redis，到期只放一个请求去探（半开）。
 *
 * <p>与基线不同：① 冷却放在 Redis（SET NX PX），多副本下仍是一个窗口——基线是进程内的，N 个副本等于 N 倍窗口；
 * ② 冷却键是「身份的 SHA-256 前缀 + 客户端 IP」——身份在这一步还没认证，基线只按账号冷却，谁都能每 5 s 报一次别人的账号把他挡在门外；
 * 防的是同一客户端的重试风暴，那本来就来自同一个 IP；键长也不随请求里的账号 / 令牌长度变；③ 先 IP 桶后区桶、区桶空时退回 IP 令牌——
 * 基线先区桶：被 IP 桶拒掉的每个请求都已经吃掉一个区令牌，单 IP 狂发就能把全区的桶掏空、让所有人排队；退回 IP 令牌让排队中的重试
 * 不吃 IP 桶（同一 NAT 后面的一群人等区桶时不会被 429 踢出排队，与基线一样一直回 100）。线程安全。
 */
public final class RateLimiter {

    /** 冷却按端点分开（基线 cooldownScope）：login 成功紧接着 assign-gate 是正常顺序，不能互相撞冷却。 */
    public enum Scope {
        LOGIN("login"), ASSIGN("assign");

        private final String key;

        Scope(String key) {
            this.key = key;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    /** 存储出错后直接放行多久。 */
    static final Duration FAIL_OPEN_HOLD = Duration.ofSeconds(5);

    private final RateLimitSettings settings;
    private final RateLimitStore store;
    private final WaveSchedule wave;
    private final LongSupplier nowMs;
    /** 0 = 正常；否则存储出错后直接放行到这个时刻（毫秒），到期由抢到的那一个请求去探。 */
    private final AtomicLong bypassUntilMs = new AtomicLong();

    public RateLimiter(RateLimitSettings settings, RateLimitStore store, LongSupplier nowMs) {
        this.settings = settings;
        this.store = store;
        this.wave = new WaveSchedule(settings.wave());
        this.nowMs = nowMs;
    }

    public boolean enabled() {
        return settings.enabled();
    }

    /**
     * @param clientIp 客户端 IP（{@link ClientIpResolver}，IPv6 已归到 /64）
     * @param identity 冷却用的身份：账号，三方认证时是令牌（可为空：不判冷却）；只进哈希，不进键、不进日志
     */
    public RateLimitDecision check(int zoneId, String clientIp, String identity, Scope scope) {
        return decide(zoneId, true, clientIp, identity, scope);
    }

    /**
     * 区号没法核对时（区服目录读不到）：分波照判（只看配置、不碰 Redis），不判区桶——不按请求里的任意区号建区桶键。
     */
    public RateLimitDecision checkUnverifiedZone(int zoneId, String clientIp, String identity, Scope scope) {
        return decide(zoneId, false, clientIp, identity, scope);
    }

    private RateLimitDecision decide(int zoneId, boolean zoneBucket, String clientIp, String identity, Scope scope) {
        if (!settings.enabled()) {
            return RateLimitDecision.pass();
        }
        long now = nowMs.getAsLong();
        long nowSec = now / 1000;
        if (!wave.isOpen(zoneId, nowSec)) {
            return RateLimitDecision.queue(wave.secondsUntilOpen(zoneId, nowSec) * 1000L, -1);
        }
        long until = bypassUntilMs.get();
        if (until != 0 && (now < until || !bypassUntilMs.compareAndSet(until, now + FAIL_OPEN_HOLD.toMillis()))) {
            return RateLimitDecision.pass();
        }
        String ip = clientIp == null || clientIp.isBlank() ? "?" : clientIp;
        try {
            RateLimitStore.Admission admission = store.admit(
                    new RateLimitStore.Bucket(RedisKeys.rateLimitIp(ip), settings.ipRps(), settings.ipBurst()),
                    zoneBucket && zoneId > 0 ? new RateLimitStore.Bucket(RedisKeys.rateLimitZone(zoneId),
                            settings.zoneRps(zoneId), settings.zoneBurst(zoneId)) : null);
            if (until != 0) {
                bypassUntilMs.set(0);
                log.info("限流存储恢复，重新开始限流");
            }
            switch (admission.kind()) {
                case IP_EMPTY -> {
                    return RateLimitDecision.deny(RateLimitDecision.IP_RATE_LIMIT);
                }
                case ZONE_EMPTY -> {
                    return RateLimitDecision.queue(admission.waitMs(), admission.remaining());
                }
                case OK -> {
                }
            }
            if (identity != null && !identity.isBlank() && !store.tryCooldown(
                    RedisKeys.rateLimitCooldown(scope.key, digest(identity) + ":" + ip),
                    Duration.ofMillis(settings.accountCooldownMs()))) {
                return RateLimitDecision.deny(RateLimitDecision.ACCOUNT_COOLDOWN);
            }
        } catch (RuntimeException e) {
            // 存储不可用时每个请求都要等满 Redis 超时（约 4 s）才放行：之后一段时间直接放行，不再碰 Redis
            bypassUntilMs.set(now + FAIL_OPEN_HOLD.toMillis());
            log.warn("限流存储出错，{} s 内放行（fail-open）: {}", FAIL_OPEN_HOLD.toSeconds(), e.toString());
            return RateLimitDecision.pass();
        }
        return RateLimitDecision.pass();
    }

    /** 身份的 SHA-256 前 16 字节（十六进制）。 */
    static String digest(String identity) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
