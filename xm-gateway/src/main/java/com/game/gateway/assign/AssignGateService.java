package com.game.gateway.assign;

import com.game.api.proto.GateNodeInfo;
import com.game.common.token.GateTokenIssuer;
import com.game.common.token.GateTokenIssuer.IssuedGateToken;
import com.game.discovery.gate.GatePicker;
import com.game.discovery.gate.GateSource;
import com.game.gateway.queue.LoginQueue;
import com.game.gateway.queue.QueueCapacity;
import com.game.gateway.queue.QueueTokens;
import com.game.gateway.ratelimit.RateLimitDecision;
import com.game.gateway.ratelimit.RateLimiter;
import com.game.gateway.store.ZoneRow;
import com.game.gateway.zone.ZoneDirectory;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 分配 gate：区服准入 → （排队打开时带令牌）轮询 → 开服限流 → （排队打开时）快速通道 / 入队 → 读 gate 目录 → 挑人数最少的 gate → 签令牌。
 *
 * <p>全部失败路径 fail-closed：区服不存在 / 不开放、目录不可达、没有可连接的 gate、排队存储出错，都不签令牌，
 * 以 {@link AssignGateResponse} 的业务码返回（HTTP 恒 200）。区服准入在一切之前，维护中的区不会触达 Redis；
 * 排队中的轮询同样先过准入（运维把区切成维护时排队者被拦下）。
 *
 * <p>排队（同 mmorpg AssignGateLogic，{@link Queueing} 为空即关闭——关闭时 {@code queue_token} 照收不用）：
 * <ol>
 *   <li>带 {@code queue_token}：按令牌轮询（{@link #poll}）；</li>
 *   <li>队列为空且快速通道原子占位成功（占位数 &lt; 容量 − 在线）：直接签；</li>
 *   <li>否则入队回 100（{@code queue_source="login"}、令牌、名次从 0 起、队长、建议轮询间隔）。</li>
 * </ol>
 * 与基线不同：<b>不做「账号已有在线会话就绕过排队」</b>——assign-gate 的 account 未经认证，按它绕过等于谁都能报一个在线账号插队。
 *
 * <p>开服限流（{@link RateLimiter}，同基线 AssignGateRateLimiter）：排队中的轮询不过限流（排队本身就是节流）；其余请求在区服准入之后、
 * 碰排队存储之前判——与基线不同，基线先限流再准入：不存在的区号也会建一个区桶键（谁都能按任意区号造键），维护中的区也消耗令牌。
 * 排队关闭时 {@code queue_token} 照收不用，也就不能拿它绕过限流。
 *
 * <p>线程模型：无可变状态，线程安全；会阻塞读 Redis / MySQL（区服目录缓存过期时），只在 Servlet 请求线程上调用。
 */
public final class AssignGateService {

    private static final Logger log = LoggerFactory.getLogger(AssignGateService.class);

    /** 排队打开时的依赖。 */
    public record Queueing(LoginQueue queue, QueueCapacity capacity, QueueTokens tokens, long retryAfterMs) {
    }

    private final ZoneDirectory zones;
    private final GateSource gates;
    private final GateTokenIssuer issuer;
    private final Queueing queueing;
    private final RateLimiter limiter;
    private final Clock clock;

    /** 不排队。 */
    public AssignGateService(ZoneDirectory zones, GateSource gates, GateTokenIssuer issuer) {
        this(zones, gates, issuer, null, Clock.systemUTC());
    }

    /** @param queueing 排队依赖；为 null 表示排队关闭 */
    public AssignGateService(ZoneDirectory zones, GateSource gates, GateTokenIssuer issuer, Queueing queueing,
                             Clock clock) {
        this(zones, gates, issuer, queueing, null, clock);
    }

    /**
     * @param queueing 排队依赖；为 null 表示排队关闭
     * @param limiter  开服限流；为 null 表示不限流
     */
    public AssignGateService(ZoneDirectory zones, GateSource gates, GateTokenIssuer issuer, Queueing queueing,
                             RateLimiter limiter, Clock clock) {
        this.zones = zones;
        this.gates = gates;
        this.issuer = issuer;
        this.queueing = queueing;
        this.limiter = limiter;
        this.clock = clock;
    }

    public AssignGateResponse assign(int zoneId) {
        return assign(zoneId, null);
    }

    public AssignGateResponse assign(int zoneId, String queueToken) {
        return assign(zoneId, queueToken, null, null);
    }

    /**
     * @param queueToken 客户端带来的排队令牌（可为空）
     * @param clientIp   客户端 IP（限流用）
     * @param account    请求体里的账号（未经认证，只当限流冷却的身份）
     */
    public AssignGateResponse assign(int zoneId, String queueToken, String clientIp, String account) {
        AssignGateResponse rejected = admission(zoneId);
        if (rejected != null) {
            return rejected;
        }
        if (queueing != null && queueToken != null && !queueToken.isBlank()) {
            return poll(zoneId, queueToken);
        }
        if (limiter != null) {
            RateLimitDecision decision = limiter.check(zoneId, clientIp, account, RateLimiter.Scope.ASSIGN);
            if (decision.kind() == RateLimitDecision.Kind.QUEUE) {
                return AssignGateResponse.rateLimitQueueing(decision.retryAfterMs(), decision.queuePos());
            }
            if (decision.kind() == RateLimitDecision.Kind.DENY) {
                return AssignGateResponse.rejected(AssignGateResponse.CODE_RATE_LIMITED, decision.reason());
            }
        }
        List<GateNodeInfo> candidates;
        try {
            candidates = gates.listGates(zoneId);
        } catch (RuntimeException e) {
            log.error("读取 gate 目录失败，拒绝分配 zone={}", zoneId, e);
            return AssignGateResponse.rejected(AssignGateResponse.CODE_INTERNAL,
                    AssignGateResponse.ERR_GATE_DIRECTORY_UNAVAILABLE);
        }
        Optional<GateNodeInfo> picked = GatePicker.pick(zoneId, candidates);
        if (picked.isEmpty()) {
            log.warn("没有可分配的 gate zone={} 目录条目数={}", zoneId, candidates.size());
            return AssignGateResponse.rejected(AssignGateResponse.CODE_INTERNAL, AssignGateResponse.ERR_NO_GATE_AVAILABLE);
        }
        if (queueing == null) {
            return sign(zoneId, picked.get());
        }
        try {
            ZoneRow zone = zones.find(zoneId).orElseThrow();
            if (queueing.queue().queueLength(zoneId) == 0
                    && queueing.queue().tryReserveFastPath(zoneId, queueing.capacity().budget(zone, candidates))) {
                return sign(zoneId, picked.get());
            }
            LoginQueue.Enqueued enqueued = queueing.queue().enqueue(zoneId);
            long expire = clock.millis() / 1000 + queueing.queue().entryTtl().toSeconds();
            String token = queueing.tokens().sign(enqueued.queueId(), zoneId, expire);
            return AssignGateResponse.loginQueueing(token, enqueued.rank(), enqueued.total(), queueing.retryAfterMs());
        } catch (RuntimeException e) {
            log.error("登录排队存储出错，拒绝分配 zone={}", zoneId, e);
            return AssignGateResponse.rejected(AssignGateResponse.CODE_INTERNAL, AssignGateResponse.ERR_QUEUE_UNAVAILABLE);
        }
    }

    /**
     * {@code POST /api/queue-status}（同基线 queryQueueStatus）：缺令牌 410 {@code missing_queue_token}；排队关闭 410
     * {@code queue_disabled}；先过区服准入（请求没带区时取令牌里的区——基线不带区就跳过准入）；再轮询。
     */
    public AssignGateResponse queueStatus(int zoneId, String queueToken) {
        if (queueToken == null || queueToken.isBlank()) {
            return AssignGateResponse.rejected(AssignGateResponse.CODE_QUEUE_EXPIRED,
                    AssignGateResponse.ERR_MISSING_QUEUE_TOKEN);
        }
        if (queueing == null) {
            // 同基线顺序：带了区先过准入，再报排队关闭
            if (zoneId > 0) {
                AssignGateResponse rejected = admission(zoneId);
                if (rejected != null) {
                    return rejected;
                }
            }
            return AssignGateResponse.rejected(AssignGateResponse.CODE_QUEUE_EXPIRED, AssignGateResponse.ERR_QUEUE_DISABLED);
        }
        int zone = zoneId;
        if (zone <= 0) {
            Optional<QueueTokens.Claims> claims = queueing.tokens().verify(queueToken, clock.millis() / 1000);
            if (claims.isEmpty()) {
                return expired();
            }
            zone = claims.get().zoneId();
        }
        AssignGateResponse rejected = admission(zone);
        if (rejected != null) {
            return rejected;
        }
        return poll(zone, queueToken);
    }

    /**
     * 按令牌轮询：令牌验不过 / 不是这个区的 / 条目不在了 → 410 {@code queue_token_expired}；还在排 → 100（同一个令牌）；
     * 已放行 → 取走放行槽、为选好的 gate 签 gate 令牌（有效期从此刻算起）→ 0。
     */
    private AssignGateResponse poll(int zoneId, String queueToken) {
        Optional<QueueTokens.Claims> claims = queueing.tokens().verify(queueToken, clock.millis() / 1000);
        if (claims.isEmpty() || claims.get().zoneId() != zoneId) {
            return expired();
        }
        LoginQueue.Lookup lookup;
        try {
            lookup = queueing.queue().lookup(zoneId, claims.get().queueId());
        } catch (RuntimeException e) {
            log.error("登录排队轮询出错 zone={}", zoneId, e);
            return AssignGateResponse.rejected(AssignGateResponse.CODE_INTERNAL, AssignGateResponse.ERR_QUEUE_UNAVAILABLE);
        }
        return switch (lookup) {
            case LoginQueue.Admitted admitted -> {
                log.debug("排队放行取走 zone={} 等待 {} ms", zoneId, clock.millis() - admitted.enqueuedAtMs());
                yield sign(zoneId, admitted.gate());
            }
            case LoginQueue.Waiting waiting -> AssignGateResponse.loginQueueing(queueToken, waiting.rank(), waiting.total(),
                    queueing.retryAfterMs());
            case LoginQueue.Expired ignored -> expired();
        };
    }

    /** 区服准入：放行回 null，否则回拒绝应答。 */
    private AssignGateResponse admission(int zoneId) {
        Optional<ZoneRow> zone;
        try {
            zone = zones.find(zoneId);
        } catch (RuntimeException e) {
            // 区服表读不到：fail-closed（同基线 zone_admission_unavailable）
            log.error("读取区服目录失败，拒绝分配 zone={}", zoneId, e);
            return AssignGateResponse.rejected(AssignGateResponse.CODE_INTERNAL,
                    AssignGateResponse.ERR_ZONE_ADMISSION_UNAVAILABLE);
        }
        if (zone.isEmpty()) {
            return AssignGateResponse.rejected(AssignGateResponse.CODE_ZONE_NOT_FOUND, AssignGateResponse.ERR_ZONE_NOT_FOUND);
        }
        return switch (zone.get().status()) {
            case MAINTENANCE -> AssignGateResponse.rejected(AssignGateResponse.CODE_ZONE_UNAVAILABLE,
                    AssignGateResponse.ERR_ZONE_MAINTENANCE);
            case CLOSED -> AssignGateResponse.rejected(AssignGateResponse.CODE_ZONE_UNAVAILABLE,
                    AssignGateResponse.ERR_ZONE_CLOSED);
            // 同基线：白名单不参与准入（assign-gate 的 account 未经认证，按它放行等于谁都能进），一律未开放
            case PREVIEW -> AssignGateResponse.rejected(AssignGateResponse.CODE_ZONE_UNAVAILABLE,
                    AssignGateResponse.ERR_ZONE_NOT_OPEN);
            case OPEN -> null;
        };
    }

    private AssignGateResponse sign(int zoneId, GateNodeInfo gate) {
        IssuedGateToken token = issuer.issue(gate.getNodeId(), zoneId);
        log.debug("已分配 gate zone={} node={} 在线={}", zoneId, gate.getNodeId(), Integer.toUnsignedLong(gate.getPlayerCount()));
        return AssignGateResponse.admitted(gate.getClientHost(), gate.getClientPort(),
                token.payload().toByteArray(), token.signature().toByteArray(), token.deadlineEpochSec());
    }

    private static AssignGateResponse expired() {
        return AssignGateResponse.rejected(AssignGateResponse.CODE_QUEUE_EXPIRED, AssignGateResponse.ERR_QUEUE_TOKEN_EXPIRED);
    }
}
