package com.game.scene.world;

import java.time.Duration;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.CompletionStage;

/**
 * 断线保留与挂机月卡离线托管判定（对齐 C++ {@code connection_lost_hold.h} 与
 * {@code docs/design/player-connection-lost-hold.md}）。
 *
 * <p>核心约束：普通断线保留窗口（25s）严格小于 login 的 30s 断线租约，避免跨节点重登产生双实例。
 * 持有有效挂机月卡（剩余 > 30s）自动转为离线托管，直到重连或月卡到期（含 15s 未确认宽限）。
 */
public final class ConnectionLostHold {

    /** 普通断线保留窗口：25 秒（严格小于 login 30 秒断线租约）。 */
    public static final long HOLD_WINDOW_SECONDS = 25L;
    public static final Duration HOLD_WINDOW = Duration.ofSeconds(HOLD_WINDOW_SECONDS);

    /** 托管到期前多少秒开始提前复查月卡是否续费：30 秒。 */
    public static final long HOSTING_EXIT_LEAD_SECONDS = 30L;

    /** 月卡到期后若仍无法确认续费，再宽限多久强制退场：15 秒。 */
    public static final long HOSTING_UNCONFIRMED_GRACE_SECONDS = 15L;

    /** 托管中未接上队长的队员，服务器错峰重查跟随的周期：15 秒。 */
    public static final long HOSTED_FOLLOW_RECHECK_PERIOD_SECONDS = 15L;

    /** 离线托管在场心跳刷新周期与存活 TTL（对齐主仓 20s / 60s）。 */
    public static final long HOSTED_PRESENCE_HEARTBEAT_SECONDS = 20L;
    public static final long HOSTED_PRESENCE_TTL_SECONDS = 60L;

    /** 异步读取 {@code player:afk_pass:{<player_id>}} 投影（返回空 = 无月卡；异常完成 = 读失败保留原态/宽限）。 */
    @FunctionalInterface
    public interface AfkPassProbe {
        CompletionStage<OptionalLong> readExpiresAtSec(long playerId);
    }

    /**
     * 解析挂机月卡 Redis 字符串值（对齐 C++ {@code connection_lost_hold.h ParseAfkPass}）：
     * 仅接受正无符号十进制 Unix 秒；空串、负号、前导垃圾或解析失败均视为无月卡（返回 0）。
     */
    public static long parseAfkPass(String raw) {
        if (raw == null || raw.isEmpty()) {
            return 0L;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.charAt(0) == '+' || trimmed.charAt(0) == '-') {
            return 0L;
        }
        try {
            long sec = Long.parseUnsignedLong(trimmed);
            return sec > 0L ? sec : 0L;
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    /**
     * 新会话接回判据（对齐主仓 {@code player-connection-lost-hold.md} §5 第 0.1、3 步）：
     * 会话号不同，或会话号相同且登录代次严格更大，才算新的一次登录；同一次登录的重复进场不离场、不重发入场通知、也不摘保留。
     */
    public static boolean isNewLoginIdentity(SessionKey lostSession, long lastLoginGeneration,
                                             SessionKey incomingSession, long incomingLoginGeneration) {
        if (lostSession == null || incomingSession == null) {
            return true;
        }
        if (!Objects.equals(lostSession, incomingSession)) {
            return true;
        }
        if (incomingLoginGeneration != 0L || lastLoginGeneration != 0L) {
            return Long.compareUnsigned(incomingLoginGeneration, lastLoginGeneration) > 0;
        }
        return false;
    }

    public enum AfkPassState {
        UNKNOWN,
        INACTIVE,
        ACTIVE
    }

    public enum HoldVerdict {
        KEEP,
        PROBE,
        EXIT
    }

    private ConnectionLostHold() {
    }

    public static boolean isExpired(long heldSeconds) {
        return heldSeconds >= HOLD_WINDOW_SECONDS;
    }

    /** 是否已正式进入离线托管（非普通 25s 保留且距离月卡到期还超过退场提前量，且未越过未成年人可玩截止时刻）。 */
    public static boolean isHosting(boolean hostingStarted, AfkPassState pass, long passExpiresAtSec, long nowUnixSec) {
        return isHosting(hostingStarted, pass, passExpiresAtSec, nowUnixSec, 0L, nowUnixSec * 1000L);
    }

    public static boolean isHosting(boolean hostingStarted, AfkPassState pass, long passExpiresAtSec,
                                    long nowUnixSec, long minorPlayDeadlineMs, long nowUnixMs) {
        if (minorPlayDeadlineMs > 0L && nowUnixMs >= minorPlayDeadlineMs) {
            return false;
        }
        return hostingStarted && pass == AfkPassState.ACTIVE
                && nowUnixSec + HOSTING_EXIT_LEAD_SECONDS < passExpiresAtSec;
    }

    /** 托管中错峰跟随重查槽位判定（每名玩家每 15 秒恰好命中 1 秒）。 */
    public static boolean isHostedFollowRecheckDue(long playerId, long nowUnixSec) {
        return Long.remainderUnsigned(playerId + nowUnixSec, HOSTED_FOLLOW_RECHECK_PERIOD_SECONDS) == 0;
    }

    /** 纯判定：根据已保留时长、月卡状态、当前 Unix 秒及是否有查询在途决定保留 / 复查 / 退场。 */
    public static HoldVerdict judgeHold(long heldSeconds, AfkPassState pass, long passExpiresAtSec,
                                        long nowUnixSec, boolean probeInFlight) {
        return judgeHold(heldSeconds, pass, passExpiresAtSec, nowUnixSec, probeInFlight, 0L, nowUnixSec * 1000L);
    }

    /** 纯判定（含未成年人可玩截止时刻封顶）：一到截止时刻立即退场，不保留也不托管。 */
    public static HoldVerdict judgeHold(long heldSeconds, AfkPassState pass, long passExpiresAtSec,
                                        long nowUnixSec, boolean probeInFlight,
                                        long minorPlayDeadlineMs, long nowUnixMs) {
        if (minorPlayDeadlineMs > 0L && nowUnixMs >= minorPlayDeadlineMs) {
            return HoldVerdict.EXIT;
        }
        if (pass == AfkPassState.ACTIVE) {
            if (nowUnixSec + HOSTING_EXIT_LEAD_SECONDS < passExpiresAtSec) {
                return HoldVerdict.KEEP;
            }
            if (nowUnixSec >= passExpiresAtSec + HOSTING_UNCONFIRMED_GRACE_SECONDS) {
                return HoldVerdict.EXIT;
            }
            return probeInFlight ? HoldVerdict.KEEP : HoldVerdict.PROBE;
        }
        if (isExpired(heldSeconds)) {
            return HoldVerdict.EXIT;
        }
        return pass == AfkPassState.UNKNOWN && !probeInFlight ? HoldVerdict.PROBE : HoldVerdict.KEEP;
    }

    /** 挂在断线保留/离线托管玩家身上的纯内存运行态（不持久化，重连或退出即摘除）。 */
    public static final class State {
        private final long lostAtNanos;
        private final SessionKey lostSession;
        private AfkPassState afkPass;
        private long afkPassExpiresAtSec;
        private boolean probeInFlight;
        private boolean hostingStarted;
        private long lastProjectionRefreshSec;
        private long lastPresenceHeartbeatSec;

        public State(long lostAtNanos, SessionKey lostSession, long afkPassExpiresAtSec, long nowUnixSec) {
            this(lostAtNanos, lostSession, afkPassExpiresAtSec > nowUnixSec ? AfkPassState.ACTIVE : AfkPassState.INACTIVE,
                    afkPassExpiresAtSec, nowUnixSec);
        }

        public State(long lostAtNanos, SessionKey lostSession, AfkPassState initialState,
                     long afkPassExpiresAtSec, long nowUnixSec) {
            this.lostAtNanos = lostAtNanos;
            this.lostSession = lostSession;
            this.afkPass = Objects.requireNonNull(initialState, "initialState");
            this.afkPassExpiresAtSec = afkPassExpiresAtSec;
            this.lastProjectionRefreshSec = nowUnixSec;
            this.lastPresenceHeartbeatSec = Long.MIN_VALUE;
        }

        public static State unknown(long lostAtNanos, SessionKey lostSession, long nowUnixSec) {
            return new State(lostAtNanos, lostSession, AfkPassState.UNKNOWN, 0L, nowUnixSec);
        }

        public long lostAtNanos() {
            return lostAtNanos;
        }

        public SessionKey lostSession() {
            return lostSession;
        }

        public AfkPassState afkPass() {
            return afkPass;
        }

        public long afkPassExpiresAtSec() {
            return afkPassExpiresAtSec;
        }

        public void refreshPass(long expiresAtSec, long nowUnixSec) {
            this.afkPassExpiresAtSec = expiresAtSec;
            this.afkPass = expiresAtSec > nowUnixSec ? AfkPassState.ACTIVE : AfkPassState.INACTIVE;
            this.probeInFlight = false;
        }

        public boolean probeInFlight() {
            return probeInFlight;
        }

        public void setProbeInFlight(boolean probeInFlight) {
            this.probeInFlight = probeInFlight;
        }

        public boolean hostingStarted() {
            return hostingStarted;
        }

        public void markHostingStarted() {
            this.hostingStarted = true;
        }

        public long lastProjectionRefreshSec() {
            return lastProjectionRefreshSec;
        }

        public void setLastProjectionRefreshSec(long nowUnixSec) {
            this.lastProjectionRefreshSec = nowUnixSec;
        }

        public long lastPresenceHeartbeatSec() {
            return lastPresenceHeartbeatSec;
        }

        public void setLastPresenceHeartbeatSec(long nowUnixSec) {
            this.lastPresenceHeartbeatSec = nowUnixSec;
        }
    }
}
