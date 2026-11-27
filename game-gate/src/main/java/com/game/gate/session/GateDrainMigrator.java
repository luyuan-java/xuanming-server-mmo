package com.game.gate.session;

import com.game.proto.login.GateMigrationResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * gate 排空在线连接迁移控制器（对齐 {@code docs/design/gate-drain-session-migration.md} §7/§12/§17–§20 与无感重启收尾批）：
 * <ul>
 *   <li><b>默认开启</b>，保留应急关闭开关；缺省速率每台每秒 {@value #DEFAULT_RATE_PER_SECOND} 人，在途上限 {@code 2 × rate}；</li>
 *   <li><b>许可租期</b>：收到许可开启/续期；速率或租期为 0 立即停；过期未续自动停；login 回 {@code NOT_DRAINING}/{@code DISABLED} 立即停；</li>
 *   <li><b>合格条件</b>：已验票、已登录、未推过 124、场景 lane 为空；游戏中须有场景路由且不在进场/交出途中；
 *       选角界面须最近 5 分钟内未发过 EnterGame；</li>
 *   <li><b>失败退避</b>：没要到票、调用失败、未发出均按会话退避 {@value #SESSION_BACKOFF_MS} ms；</li>
 *   <li><b>复核与身份栅栏</b>：票据到达时复核会话仍合格、角色未变（票据 player_id 非 0 时必须等于当前 player_id）方推 124；</li>
 *   <li><b>低基数指标</b>：结果、许可事件、许可在效、在途要票数、124 后收尾关闭数。</li>
 * </ul>
 */
public final class GateDrainMigrator {

    public static final int DEFAULT_RATE_PER_SECOND = 20;
    public static final long SESSION_BACKOFF_MS = 10_000L;
    public static final long LOBBY_ENTER_QUIET_MS = 5L * 60_000L;

    /** 迁移候选会话只读快照（便于在 EventLoop 派发与单元测试）。 */
    public record SessionCandidate(int sessionId,
                                   boolean verified,
                                   boolean loggedIn,
                                   boolean redirected,
                                   boolean closingOrClosed,
                                   long playerId,
                                   int sceneNodeId,
                                   boolean enteringOrTransferring,
                                   boolean sceneLaneNonEmpty,
                                   long lastEnterGameAtMs) {
    }

    /** 发起一次要票调用的动作（生产经 Dubbo 调 login.RequestGateMigration）。 */
    @FunctionalInterface
    public interface TicketRequester {
        void requestTicket(SessionCandidate candidate);
    }

    private final boolean enabledByConfig;
    private final MeterRegistry meters;
    private final Map<Integer, Long> sessionBackoffUntilMs = new HashMap<>();
    private final AtomicInteger permitActiveGauge = new AtomicInteger();
    private final AtomicInteger inFlightGauge = new AtomicInteger();
    private final Counter lingerClosedCounter;

    private int ratePerSecond;
    private long permitExpiresAtMs;

    public GateDrainMigrator(MeterRegistry meters) {
        this(true, meters);
    }

    public GateDrainMigrator(boolean enabledByConfig, MeterRegistry meters) {
        this.enabledByConfig = enabledByConfig;
        this.meters = Objects.requireNonNull(meters, "meters");
        Gauge.builder("gate_drain_migration_permit_active", permitActiveGauge, AtomicInteger::get).register(meters);
        Gauge.builder("gate_drain_migration_inflight", inFlightGauge, AtomicInteger::get).register(meters);
        this.lingerClosedCounter = Counter.builder("gate_drain_migration_linger_closed_total").register(meters);
    }

    public boolean enabledByConfig() {
        return enabledByConfig;
    }

    public boolean isPermitActive(long nowMs) {
        if (!enabledByConfig || ratePerSecond <= 0 || nowMs >= permitExpiresAtMs) {
            permitActiveGauge.set(0);
            return false;
        }
        permitActiveGauge.set(1);
        return true;
    }

    public int ratePerSecond() {
        return ratePerSecond;
    }

    public int inFlightCount() {
        return inFlightGauge.get();
    }

    /** 收到排空许可（rate <= 0 或 leaseMs <= 0 立即停）。 */
    public void applyPermit(int ratePerSec, long leaseMs, long nowMs) {
        if (!enabledByConfig) {
            stop("config_disabled");
            return;
        }
        if (ratePerSec <= 0 || leaseMs <= 0) {
            stop("zero_permit");
            return;
        }
        this.ratePerSecond = ratePerSec;
        this.permitExpiresAtMs = nowMs + leaseMs;
        permitActiveGauge.set(1);
        permitEvent("granted");
    }

    public void stop(String reason) {
        this.ratePerSecond = 0;
        this.permitExpiresAtMs = 0L;
        permitActiveGauge.set(0);
        permitEvent(reason);
    }

    /** 判定一条会话当前是否合格参与排空迁移（发前与回包后共用同一判据）。 */
    public static boolean isEligible(SessionCandidate s, long nowMs) {
        if (s == null || !s.verified() || !s.loggedIn() || s.redirected() || s.closingOrClosed()) {
            return false;
        }
        if (s.sceneLaneNonEmpty()) {
            return false;
        }
        if (s.playerId() != 0L) {
            return s.sceneNodeId() != 0 && !s.enteringOrTransferring();
        }
        return s.lastEnterGameAtMs() <= 0L || nowMs - s.lastEnterGameAtMs() >= LOBBY_ENTER_QUIET_MS;
    }

    /** 身份栅栏复核：票据回包到达时检查会话仍合格且未换角。 */
    public static boolean canDeliverTicket(SessionCandidate current, long ticketPlayerId, long nowMs) {
        if (!isEligible(current, nowMs)) {
            return false;
        }
        return ticketPlayerId == 0L ? current.playerId() == 0L : current.playerId() == ticketPlayerId;
    }

    /** 每秒节拍：按速率挑选合格且未退避的会话发起要票（在途不超过 2 × rate）。 */
    public int tickOneSecond(List<SessionCandidate> candidates, long nowMs, TicketRequester requester) {
        if (!isPermitActive(nowMs)) {
            return 0;
        }
        int maxInFlight = ratePerSecond * 2;
        int dispatched = 0;
        for (SessionCandidate c : candidates) {
            if (dispatched >= ratePerSecond || inFlightGauge.get() >= maxInFlight) {
                break;
            }
            if (!isEligible(c, nowMs)) {
                continue;
            }
            Long backoffUntil = sessionBackoffUntilMs.get(c.sessionId());
            if (backoffUntil != null && nowMs < backoffUntil) {
                continue;
            }
            inFlightGauge.incrementAndGet();
            dispatched++;
            requester.requestTicket(c);
        }
        return dispatched;
    }

    /** 要票回包收口：根据登录服结果决定推 124、退避 10s 或立即停本轮迁移。 */
    public boolean onTicketReply(int sessionId, GateMigrationResult result, SessionCandidate current,
                                 long ticketPlayerId, long nowMs) {
        inFlightGauge.updateAndGet(v -> Math.max(0, v - 1));
        if (result == GateMigrationResult.GATE_MIGRATION_RESULT_NOT_DRAINING) {
            stop("login_not_draining");
            recordResult("not_draining");
            return false;
        }
        if (result == GateMigrationResult.GATE_MIGRATION_RESULT_DISABLED) {
            stop("login_disabled");
            recordResult("disabled");
            return false;
        }
        if (result != GateMigrationResult.GATE_MIGRATION_RESULT_OK) {
            backoff(sessionId, nowMs);
            recordResult("no_ticket");
            return false;
        }
        if (!canDeliverTicket(current, ticketPlayerId, nowMs)) {
            backoff(sessionId, nowMs);
            recordResult("stale_session");
            return false;
        }
        sessionBackoffUntilMs.remove(sessionId);
        recordResult("redirected");
        return true;
    }

    /** 传输失败或本地写出 124 失败时记录退避。 */
    public void onTicketFailure(int sessionId, long nowMs) {
        inFlightGauge.updateAndGet(v -> Math.max(0, v - 1));
        backoff(sessionId, nowMs);
        recordResult("error");
    }

    /** 推过 124 后因收口时限或顶号静默关闭时计指标。 */
    public void recordLingerClosed() {
        lingerClosedCounter.increment();
    }

    public void forgetSession(int sessionId) {
        sessionBackoffUntilMs.remove(sessionId);
    }

    private void backoff(int sessionId, long nowMs) {
        sessionBackoffUntilMs.put(sessionId, nowMs + SESSION_BACKOFF_MS);
    }

    private void recordResult(String result) {
        Counter.builder("gate_drain_migration_results_total").tag("result", result).register(meters).increment();
    }

    private void permitEvent(String event) {
        Counter.builder("gate_drain_migration_permit_events_total").tag("event", event).register(meters).increment();
    }
}
