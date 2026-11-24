package com.game.login.compliance;

import com.game.common.compliance.AntiAddictionRules;
import com.game.proto.login.AntiAddictionStatus;
import com.game.table.LoginErrorTip;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 登录期防沉迷裁决器（对齐 Go {@code go/login/internal/svc/anti_addiction.go}）：
 * 未启用时直接放行；启用后对非平台托管渠道要求实名，对已实名未成年人校验周五/六/日及法定节假日 20:00–21:00 可玩时段。
 */
public final class AntiAddictionGate {

    public record Verdict(AntiAddictionStatus status, int rejectTip, long playDeadlineMs, String reportPi) {
        public static final Verdict DISABLED = new Verdict(null, 0, 0L, "");

        public boolean enabled() {
            return status != null;
        }
    }

    private final AntiAddictionRules.Calendar calendar;
    private final Set<String> platformManagedAuthTypes;
    private final RealNameService realNameService;
    private final NppaLedger ledger;
    private final Clock clock;

    public AntiAddictionGate(AntiAddictionRules.Calendar calendar, Set<String> platformManagedAuthTypes,
                             RealNameService realNameService, NppaLedger ledger, Clock clock) {
        this.calendar = Objects.requireNonNull(calendar, "calendar");
        this.platformManagedAuthTypes = platformManagedAuthTypes == null ? Set.of() : Set.copyOf(platformManagedAuthTypes);
        this.realNameService = realNameService;
        this.ledger = ledger;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public boolean isSelfManaged(String channel) {
        return channel == null || !platformManagedAuthTypes.contains(channel);
    }

    public RealNameService realNameService() {
        return realNameService;
    }

    public NppaLedger ledger() {
        return ledger;
    }

    public Instant now() {
        return clock.instant();
    }

    /** 按渠道与账号 {@code pi} 做防沉迷裁决；已实名时即使来自平台托管渠道仍按规则限玩。 */
    public Verdict evaluate(String channel, String pi, Instant now) {
        AntiAddictionStatus.Builder status = AntiAddictionStatus.newBuilder();
        boolean selfManaged = isSelfManaged(channel);
        if (pi == null || pi.isEmpty()) {
            if (selfManaged) {
                status.setRealNameRequired(true);
                return new Verdict(status.build(), LoginErrorTip.login_error.kLoginRealNameRequired_VALUE, 0L, "");
            }
            return new Verdict(status.build(), 0, 0L, "");
        }
        LocalDate birth = AntiAddictionRules.birthDate(pi);
        if (birth == null) {
            return new Verdict(status.build(), LoginErrorTip.login_error.kLoginRealNameServiceUnavailable_VALUE, 0L, "");
        }
        String reportPi = selfManaged ? pi : "";
        if (AntiAddictionRules.ageAt(birth, now) >= AntiAddictionRules.ADULT_AGE) {
            return new Verdict(status.build(), 0, 0L, reportPi);
        }
        status.setMinor(true);
        Instant deadline = calendar.playDeadline(now);
        if (deadline == null) {
            return new Verdict(status.build(), LoginErrorTip.login_error.kLoginMinorNotInPlayTime_VALUE, 0L, reportPi);
        }
        long deadlineMs = deadline.toEpochMilli();
        status.setPlayDeadlineMs(deadlineMs);
        return new Verdict(status.build(), 0, deadlineMs, reportPi);
    }

    /** HTTP 登录时若账号尚无 {@code pi} 且为自有渠道，先回查 NPPA 待查提交，再输出最新状态。 */
    public AntiAddictionStatus evaluateForHttpLogin(String account, String channel, String existingPi) {
        String pi = existingPi == null ? "" : existingPi;
        if (pi.isEmpty() && isSelfManaged(channel) && realNameService != null) {
            pi = realNameService.resolvePending(account);
        }
        return evaluate(channel, pi, now()).status();
    }

    public void beginWatchedSession(String connId, Verdict verdict) {
        if (ledger == null || verdict == null) {
            return;
        }
        if (verdict.reportPi().isEmpty() && verdict.playDeadlineMs() <= 0) {
            return;
        }
        String si = verdict.reportPi().isEmpty() ? "" : NppaClient.newId();
        ledger.begin(connId, si, verdict.reportPi(), verdict.playDeadlineMs());
    }

    public void endWatchedSessionQuietly(String connId) {
        if (ledger == null) {
            return;
        }
        try {
            ledger.end(connId);
        } catch (RuntimeException ignored) {
            // 会话租约到期后由 NppaReporter.sweep 自动补报下线
        }
    }
}
