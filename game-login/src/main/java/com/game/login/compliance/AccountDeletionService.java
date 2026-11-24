package com.game.login.compliance;

import com.game.login.dispatch.Tips;
import com.game.login.ownership.OwnerTakeovers;
import com.game.login.token.LoginTokens;
import com.game.player.store.AccountLifecycle;
import com.game.player.store.PlayerRow;
import com.game.player.store.PlayerStore;
import com.game.player.store.PlayerStore.AccountDeletionOutcome;
import com.game.player.store.PlayerStore.AccountDeletionStatus;
import com.game.player.store.PlayerStore.DeletionBlocker;
import com.game.player.store.PlayerStore.FinishDeletionOutcome;
import com.game.player.store.PlayerStore.RoleRemovalStatus;
import com.game.player.store.RoleDeletionPrecheck;
import com.game.proto.login.AccountDeletionBlocker;
import com.game.proto.login.AccountDeletionResponse;
import com.game.table.LoginErrorTip;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * 账号注销编排服务与后台执行器（对齐 Go {@code go/login/internal/logic/pkg/accountdeletion}）：
 * <ul>
 *   <li>{@link #request}：可选 Apple 授权码换票 → 目录预检与进入冷静期 → 撤销 Apple 授权、吊销全量令牌家族、踢掉在线角色；</li>
 *   <li>{@link #cancel}：冷静期内撤销回正常态，执行中回 2040（{@code kLoginAccountDeletionInProgress}）；</li>
 *   <li>{@link #runExecutorOnce}：每 30s 按租约认领到期账号 → 吊销令牌 → 逐角踢线并经角色栅栏删除 → 写入匿名回执并删账号行。</li>
 * </ul>
 */
public final class AccountDeletionService implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);
    static final int CLAIM_BATCH = 8;
    static final Duration EXECUTION_LEASE = Duration.ofMinutes(2);
    static final Duration EXECUTOR_INTERVAL = Duration.ofSeconds(30);

    /** Sign in with Apple 授权撤销适配（未配置时为 null，注销仍照常推进）。 */
    public interface AppleRevoker {
        String exchangeCode(String authorizationCode) throws InvalidAppleCodeException;
        void revoke(String token);

        final class InvalidAppleCodeException extends Exception {
            public InvalidAppleCodeException(String message) {
                super(message);
            }
        }
    }

    public record RoundResult(int claimed, int finished, int waiting, int failed) {
    }

    private final PlayerStore store;
    private final LoginTokens tokens;
    private final OwnerTakeovers takeovers;
    private final RoleDeletionPrecheck precheck;
    private final Duration coolingOff;
    private final AppleRevoker appleRevoker;
    private final boolean executorEnabled;
    private final MeterRegistry meters;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile ScheduledExecutorService scheduler;

    public AccountDeletionService(PlayerStore store, LoginTokens tokens, OwnerTakeovers takeovers,
                                  RoleDeletionPrecheck precheck, Duration coolingOff,
                                  AppleRevoker appleRevoker, boolean executorEnabled, MeterRegistry meters) {
        this.store = Objects.requireNonNull(store, "store");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.takeovers = takeovers;
        this.precheck = precheck == null ? RoleDeletionPrecheck.SQL_FULL : precheck;
        this.coolingOff = coolingOff == null ? Duration.ofDays(7) : coolingOff;
        this.appleRevoker = appleRevoker;
        this.executorEnabled = executorEnabled;
        this.meters = meters;
    }

    /** 申请注销（本人 {@code SOURCE_USER} 或运维代办 {@code SOURCE_ADMIN}）。 */
    public AccountDeletionResponse request(String account, int source, boolean isApple, String authorizationCode) {
        String appleToken = null;
        if (isApple && appleRevoker != null) {
            try {
                appleToken = appleRevoker.exchangeCode(authorizationCode);
            } catch (AppleRevoker.InvalidAppleCodeException e) {
                countRequest("reauth");
                return tipReply(LoginErrorTip.login_error.kLoginAccountDeletionReauthRequired_VALUE);
            } catch (RuntimeException e) {
                log.error("[account-deletion] apple code exchange failed: {}", e.getClass().getSimpleName());
                countRequest("error");
                return tipReply(LoginErrorTip.login_error.kLoginAuthProviderUnavailable_VALUE);
            }
        }
        AccountDeletionOutcome outcome;
        try {
            outcome = store.requestAccountDeletion(account, source, coolingOff, precheck);
        } catch (RuntimeException e) {
            log.error("[account-deletion] request failed account={} err={}", maskAccount(account), e.toString());
            countRequest("error");
            return tipReply(LoginErrorTip.login_error.kLoginDataSerializeFailed_VALUE);
        }
        AccountDeletionResponse reply = toProtoReply(outcome);
        countRequest(outcomeLabel(outcome.status()));
        if (outcome.status() != AccountDeletionStatus.OK
                || outcome.lifecycle() == null
                || outcome.lifecycle().deletionState() == AccountLifecycle.DELETION_NONE) {
            return reply;
        }
        if (appleToken != null) {
            try {
                appleRevoker.revoke(appleToken);
            } catch (RuntimeException e) {
                log.error("[account-deletion] apple revoke failed after schedule account={} err={}",
                        maskAccount(account), e.getClass().getSimpleName());
            }
        }
        signOutQuietly(account);
        log.info("[account-deletion] scheduled account={} source={} execute_after_ms={}",
                maskAccount(account), source, outcome.lifecycle().deletionExecuteAfterMs());
        return reply;
    }

    /** 冷静期内撤销注销。 */
    public AccountDeletionResponse cancel(String account) {
        AccountDeletionOutcome outcome;
        try {
            outcome = store.cancelAccountDeletion(account);
        } catch (RuntimeException e) {
            log.error("[account-deletion] cancel failed account={} err={}", maskAccount(account), e.toString());
            return tipReply(LoginErrorTip.login_error.kLoginDataSerializeFailed_VALUE);
        }
        if (outcome.status() == AccountDeletionStatus.OK) {
            log.info("[account-deletion] cancelled or idle account={}", maskAccount(account));
        }
        return toProtoReply(outcome);
    }

    /** 后台执行器单轮推进（同步方法，供定时线程与测试直接调用）。 */
    public RoundResult runExecutorOnce() {
        List<String> claimed = store.claimDueAccountDeletions(CLAIM_BATCH, EXECUTION_LEASE);
        int finished = 0;
        int waiting = 0;
        int failed = 0;
        for (String account : claimed) {
            switch (executeOne(account)) {
                case FINISHED -> { finished++; countExecutor("finished"); }
                case WAITING -> { waiting++; countExecutor("waiting"); }
                case FAILED -> { failed++; countExecutor("error"); }
            }
        }
        return new RoundResult(claimed.size(), finished, waiting, failed);
    }

    private enum StepResult { FINISHED, WAITING, FAILED }

    private StepResult executeOne(String account) {
        String masked = maskAccount(account);
        try {
            tokens.revokeAll(account);
        } catch (RuntimeException e) {
            log.error("[account-deletion] executor revoke tokens failed account={} err={}", masked, e.getClass().getSimpleName());
        }
        var opt = store.readAccountLifecycle(account, false);
        if (opt.isEmpty()) {
            return StepResult.FINISHED;
        }
        if (opt.get().deletionState() != AccountLifecycle.DELETION_DELETING) {
            log.error("[account-deletion] claimed account not in DELETING account={} state={}",
                    masked, opt.get().deletionState());
            return StepResult.FAILED;
        }
        List<PlayerRow> roles = store.listPlayers(account);
        boolean pending = false;
        for (PlayerRow role : roles) {
            kickRoleIfHeld(role);
            RoleRemovalStatus status = store.removeRoleForDeletion(account, role.getPlayerId(), precheck);
            if (status == RoleRemovalStatus.BUSY || status == RoleRemovalStatus.BLOCKED) {
                log.info("[account-deletion] role not ready for removal player={} status={}",
                        Long.toUnsignedString(role.getPlayerId()), status);
                pending = true;
            }
        }
        if (pending) {
            return StepResult.WAITING;
        }
        try {
            FinishDeletionOutcome fin = store.finishAccountDeletion(account);
            if (!fin.done()) {
                log.info("[account-deletion] waiting for {} remaining roles account={}", fin.remainingRoles(), masked);
                return StepResult.WAITING;
            }
            log.info("[account-deletion] account deleted account={}", masked);
            return StepResult.FINISHED;
        } catch (RuntimeException e) {
            log.error("[account-deletion] finish failed account={} err={}", masked, e.toString());
            return StepResult.FAILED;
        }
    }

    private void signOutQuietly(String account) {
        try {
            tokens.revokeAll(account);
        } catch (RuntimeException e) {
            log.error("[account-deletion] revoke tokens failed account={} err={}",
                    maskAccount(account), e.getClass().getSimpleName());
        }
        try {
            for (PlayerRow role : store.listPlayers(account)) {
                kickRoleIfHeld(role);
            }
        } catch (RuntimeException e) {
            log.info("[account-deletion] list roles for kick failed account={} err={}",
                    maskAccount(account), e.toString());
        }
    }

    private void kickRoleIfHeld(PlayerRow role) {
        if (takeovers == null || role.getOwnerEpoch() <= 0) {
            return;
        }
        takeovers.request(role.getPlayerId(), role.getOwnerEpoch());
    }

    static AccountDeletionResponse toProtoReply(AccountDeletionOutcome outcome) {
        AccountDeletionResponse.Builder b = AccountDeletionResponse.newBuilder();
        if (outcome.lifecycle() != null) {
            b.setState(outcome.lifecycle().deletionState());
            b.setExecuteAfter(outcome.lifecycle().deletionExecuteAfterMs() / 1000L);
        }
        switch (outcome.status()) {
            case OK -> {}
            case NOT_FOUND -> b.setErrorMessage(Tips.of(LoginErrorTip.login_error.kLoginAccountNotFound_VALUE));
            case BLOCKED -> {
                b.setErrorMessage(Tips.of(LoginErrorTip.login_error.kLoginAccountDeletionBlocked_VALUE));
                for (DeletionBlocker blk : outcome.blockers()) {
                    b.addBlockers(AccountDeletionBlocker.newBuilder()
                            .setPlayerId(blk.playerId())
                            .setReason(blk.reason())
                            .build());
                }
            }
            case TOO_LATE -> b.setErrorMessage(Tips.of(LoginErrorTip.login_error.kLoginAccountDeletionInProgress_VALUE));
            case RETRY -> b.setErrorMessage(Tips.of(LoginErrorTip.login_error.kLoginDataSerializeFailed_VALUE));
        }
        return b.build();
    }

    public static AccountDeletionResponse tipReply(int tipId) {
        return AccountDeletionResponse.newBuilder().setErrorMessage(Tips.of(tipId)).build();
    }

    /** 脱敏账号日志（仅保留前 4 后 2 字符）。 */
    public static String maskAccount(String account) {
        if (account == null || account.length() <= 8) {
            return "***";
        }
        return account.substring(0, 4) + "***" + account.substring(account.length() - 2);
    }

    private static String outcomeLabel(AccountDeletionStatus status) {
        return switch (status) {
            case OK -> "ok";
            case BLOCKED -> "blocked";
            case NOT_FOUND -> "not_found";
            case TOO_LATE, RETRY -> "error";
        };
    }

    private void countRequest(String outcome) {
        if (meters != null) {
            meters.counter("login.account.deletion.request", "outcome", outcome).increment();
        }
    }

    private void countExecutor(String result) {
        if (meters != null) {
            meters.counter("login.account.deletion.executor", "result", result).increment();
        }
    }

    @Override
    public void start() {
        if (!executorEnabled || !running.compareAndSet(false, true)) {
            return;
        }
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("login-account-deletion").daemon(true).factory());
        scheduler = exec;
        exec.scheduleWithFixedDelay(() -> {
            try {
                RoundResult r = runExecutorOnce();
                if (r.claimed() > 0) {
                    log.info("[account-deletion] executor round claimed={} finished={} waiting={} failed={}",
                            r.claimed(), r.finished(), r.waiting(), r.failed());
                }
            } catch (Throwable t) {
                log.error("[account-deletion] executor tick failed: {}", t.toString());
            }
        }, EXECUTOR_INTERVAL.toSeconds(), EXECUTOR_INTERVAL.toSeconds(), TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        ScheduledExecutorService exec = scheduler;
        if (exec != null) {
            exec.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }
}
