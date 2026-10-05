package com.game.discovery.world;

import java.time.Duration;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一个 zone 的频道计划领导锁（scene-channels-spec §4.4，D2）：键 {@code xm:world:{z:<zone>}:leader}，值 = 本副本令牌。
 * 基线全局一把 {@code scene_manager:leader:lock}、TTL 30 s、续期 TTL/3（scene_manager_service.go:94-132；config.go:65-86）。
 *
 * <p><b>有效期</b>（判法同 {@code NodeIdLease.isValid}）：持有中，且距最近一次<b>成功</b>竞选 / 续期不足 TTL 的 2/3（单调时钟；时刻取命令
 * <b>发出前</b>的读数——Redis 端键至少活到「该时刻 + TTL」，本地提前 1/3 TTL 判无效，吸收判定到写入之间的停顿与时钟速率偏差）。
 * 失效期间调用方必须停止该 zone 的一切变更动作；写入 Lua 另外再校验一次令牌（双保险，旧领导者的晚到写入被拒）。
 * <ul>
 *   <li>续期返回 0（值已不是本令牌）→ 立即降级（{@link RenewOutcome#LOST}）；</li>
 *   <li>续期出错 → 保持到 2/3 TTL 后自动失效（{@link #isValid()} 变假），连续出错超过一个 TTL 视为丢失（键必已过期）；</li>
 *   <li>写入 Lua 回 −1 → 调用方 {@link #markLost}；</li>
 *   <li>{@link #release()} 只删仍属于本令牌的键（停服时对每个 zone 调一次）。</li>
 * </ul>
 *
 * <p>本类不自己调度：竞选由控制面一拍里调 {@link #tryAcquire()}，续期由调用方的<b>独立</b>续期线程每 {@link #renewInterval()} 调 {@link #renew()}
 * （一拍再慢也不拖住续期，补上基线「慢 RPC 让锁过期」的风险，world_init.go:95-101）。各方法可从不同线程调用；
 * {@link #isValid()}、{@link #isHeld()} 无锁读。竞选 / 续期 / 放锁是阻塞 Redis I/O，不在 I/O 线程上调用。
 */
public final class WorldLeaderLock {

    private static final Logger log = LoggerFactory.getLogger(WorldLeaderLock.class);

    /** 一次续期的结局。 */
    public enum RenewOutcome {
        /** 续上了。 */
        RENEWED,
        /** 锁已不是本令牌（或连续出错超过一个 TTL）：已降级。 */
        LOST,
        /** 本次出错，仍持有（有效期按上次成功续期计，可能已失效）。 */
        FAILED,
        /** 本来就没持有，什么也没做。 */
        NOT_HELD
    }

    private final WorldChannelStore store;
    private final int zoneId;
    private final String token;
    private final Duration ttl;
    private final long ttlNanos;
    private final long validityNanos;
    private final LongSupplier nanoClock;
    private volatile boolean held;
    /** 最近一次成功竞选 / 续期的命令发出前的单调时钟读数（只在 {@link #held} 为真时有意义）。 */
    private volatile long lastRenewedNanos;

    public WorldLeaderLock(WorldChannelStore store, int zoneId, String token, Duration ttl) {
        this(store, zoneId, token, ttl, System::nanoTime);
    }

    /** 同上，单调时钟可注入（测试用）。 */
    WorldLeaderLock(WorldChannelStore store, int zoneId, String token, Duration ttl, LongSupplier nanoClock) {
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("领导者令牌不能为空");
        }
        if (ttl.toMillis() < 3) {
            throw new IllegalArgumentException("领导锁 TTL 太短: " + ttl);
        }
        this.store = store;
        this.zoneId = zoneId;
        this.token = token;
        this.ttl = ttl;
        this.ttlNanos = ttl.toNanos();
        this.validityNanos = ttlNanos / 3 * 2;
        this.nanoClock = nanoClock;
    }

    /** 本副本的令牌 {@code <instanceId>:<uuid>}（§4.2）：同一副本的全部 zone 用同一个。 */
    public static String newToken(String instanceId) {
        return instanceId + ":" + UUID.randomUUID();
    }

    public int zoneId() {
        return zoneId;
    }

    public String token() {
        return token;
    }

    public Duration ttl() {
        return ttl;
    }

    /** 续期间隔 = TTL/3（同 NodeIdLease 与基线 config.go:65-74）。 */
    public Duration renewInterval() {
        return ttl.dividedBy(3);
    }

    /**
     * 竞选一次（{@code SET NX PX}；已是本令牌时续期）。成功即持有并以本次发出前的时刻起算有效期；失败（别人持有）即不持有。
     * Redis 出错原样抛出，持有状态不变。
     */
    public boolean tryAcquire() {
        long sentAt = nanoClock.getAsLong();
        boolean ok = store.tryAcquireLeader(zoneId, token, ttl);
        synchronized (this) {
            if (ok) {
                boolean was = held;
                advance(sentAt, was);
                held = true;
                if (!was) {
                    log.info("当选频道计划领导者 zone={} token={}", Integer.toUnsignedString(zoneId), token);
                }
            } else if (held) {
                held = false;
                log.warn("频道计划领导锁已被别人持有，降级 zone={}", Integer.toUnsignedString(zoneId));
            }
        }
        return ok;
    }

    /** 续期一次（调用方的续期线程每 {@link #renewInterval()} 调用）。 */
    public RenewOutcome renew() {
        if (!held) {
            return RenewOutcome.NOT_HELD;
        }
        long sentAt = nanoClock.getAsLong();
        boolean renewed;
        try {
            renewed = store.renewLeader(zoneId, token, ttl);
        } catch (RuntimeException e) {
            synchronized (this) {
                if (!held) {
                    return RenewOutcome.NOT_HELD;
                }
                if (nanoClock.getAsLong() - lastRenewedNanos > ttlNanos) {
                    held = false;
                    log.error("频道计划领导锁续期失败超过 TTL，降级 zone={}", Integer.toUnsignedString(zoneId), e);
                    return RenewOutcome.LOST;
                }
            }
            log.warn("频道计划领导锁续期失败，下一轮重试 zone={}", Integer.toUnsignedString(zoneId), e);
            return RenewOutcome.FAILED;
        }
        synchronized (this) {
            if (!held) {
                // 续期在途时已被降级（写入回 −1 / 放锁）：不复活，下一拍重新竞选
                return RenewOutcome.NOT_HELD;
            }
            if (renewed) {
                advance(sentAt, true);
                return RenewOutcome.RENEWED;
            }
            held = false;
        }
        log.warn("频道计划领导锁已不属于本副本，降级 zone={}", Integer.toUnsignedString(zoneId));
        return RenewOutcome.LOST;
    }

    /** 写入 Lua 回 −1（令牌不符）等外部证据表明锁已丢：立即降级（不访问 Redis）。 */
    public void markLost(String reason) {
        boolean was;
        synchronized (this) {
            was = held;
            held = false;
        }
        if (was) {
            log.warn("频道计划领导者降级 zone={} 原因={}", Integer.toUnsignedString(zoneId), reason);
        }
    }

    /** 放锁（停服 / 租约丢失时）：先降级，再删仍属于本令牌的键；Redis 出错只告警（TTL 到期自动回收）。 */
    public void release() {
        synchronized (this) {
            held = false;
        }
        try {
            store.releaseLeader(zoneId, token);
        } catch (RuntimeException e) {
            log.warn("释放频道计划领导锁失败（TTL 到期后自动回收） zone={}", Integer.toUnsignedString(zoneId), e);
        }
    }

    /** 本地认为持有（不看有效期）。 */
    public boolean isHeld() {
        return held;
    }

    /** 现在能否以领导者身份做变更：持有中，且距最近一次成功竞选 / 续期不足 2/3 TTL。 */
    public boolean isValid() {
        return held && nanoClock.getAsLong() - lastRenewedNanos < validityNanos;
    }

    /** 有效期起点只前进不后退（并发的竞选与续期谁先发出以谁为准不重要，取较晚的那次）。调用方持有监视器。 */
    private void advance(long sentAt, boolean wasHeld) {
        if (!wasHeld || sentAt - lastRenewedNanos > 0) {
            lastRenewedNanos = sentAt;
        }
    }
}
