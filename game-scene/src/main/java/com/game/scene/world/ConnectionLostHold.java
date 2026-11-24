package com.game.scene.world;

import java.time.Duration;

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

    /** 是否已正式进入离线托管（非普通 25s 保留且距离月卡到期还超过退场提前量）。 */
    public static boolean isHosting(boolean hostingStarted, AfkPassState pass, long passExpiresAtSec, long nowUnixSec) {
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

        public State(long lostAtNanos, SessionKey lostSession, long afkPassExpiresAtSec, long nowUnixSec) {
            this.lostAtNanos = lostAtNanos;
            this.lostSession = lostSession;
            this.afkPassExpiresAtSec = afkPassExpiresAtSec;
            this.afkPass = afkPassExpiresAtSec > nowUnixSec ? AfkPassState.ACTIVE : AfkPassState.INACTIVE;
            this.lastProjectionRefreshSec = nowUnixSec;
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
    }
}
