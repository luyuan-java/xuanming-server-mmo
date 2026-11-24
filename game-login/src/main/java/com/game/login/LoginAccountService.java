package com.game.login;

import com.game.api.AccountLoginService;
import com.game.api.DubboGroups;
import com.game.login.account.AccountLogin;
import com.game.login.auth.AuthProviderUnavailableException;
import com.game.login.compliance.AccountDeletionService;
import com.game.login.compliance.AntiAddictionGate;
import com.game.login.compliance.RealNameService;
import com.game.login.dispatch.LoginWorkerPool;
import com.game.login.dispatch.Tips;
import com.game.login.handler.RefreshTokenHandler;
import com.game.login.token.LoginTokens;
import com.game.login.token.TokenData;
import com.game.player.store.AccountLifecycle;
import com.game.player.store.PlayerStore;
import com.game.proto.login.AccountDeletionRequest;
import com.game.proto.login.AccountDeletionResponse;
import com.game.proto.login.CancelAccountDeletionRequest;
import com.game.proto.login.LoginRequest;
import com.game.proto.login.LoginResponse;
import com.game.proto.login.RefreshTokenRequest;
import com.game.proto.login.RefreshTokenResponse;
import com.game.proto.login.SubmitRealNameRequest;
import com.game.proto.login.SubmitRealNameResponse;
import com.game.table.LoginErrorTip;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link AccountLoginService} 的 Dubbo 提供方（group {@code login}，Triple）：HTTP 登录、刷新令牌、实名认证与账号注销申请/撤销。
 * 全部在 login 工作线程池上执行（不占 Dubbo 线程）；工作队列满时 future 异常完成（gateway 回 500）。
 */
@DubboService(group = DubboGroups.LOGIN)
public class LoginAccountService implements AccountLoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginAccountService.class);

    private final AccountLogin accountLogin;
    private final RefreshTokenHandler refreshTokens;
    private final LoginTokens tokens;
    private final PlayerStore store;
    private final AntiAddictionGate antiAddiction;
    private final AccountDeletionService accountDeletion;
    private final Executor executor;

    @Autowired
    public LoginAccountService(AccountLogin accountLogin, RefreshTokenHandler refreshTokens, LoginTokens tokens,
                               PlayerStore store, @Autowired(required = false) AntiAddictionGate antiAddiction,
                               @Autowired(required = false) AccountDeletionService accountDeletion,
                               LoginWorkerPool workers) {
        this(accountLogin, refreshTokens, tokens, store, antiAddiction, accountDeletion, (Executor) workers);
    }

    /** 兼容既有 3 参测试构造器。 */
    LoginAccountService(AccountLogin accountLogin, RefreshTokenHandler refreshTokens, Executor executor) {
        this(accountLogin, refreshTokens, null, null, null, null, executor);
    }

    public LoginAccountService(AccountLogin accountLogin, RefreshTokenHandler refreshTokens, LoginTokens tokens,
                               PlayerStore store, AntiAddictionGate antiAddiction, AccountDeletionService accountDeletion,
                               Executor executor) {
        this.accountLogin = accountLogin;
        this.refreshTokens = refreshTokens;
        this.tokens = tokens;
        this.store = store;
        this.antiAddiction = antiAddiction;
        this.accountDeletion = accountDeletion;
        this.executor = executor;
    }

    @Override
    public CompletableFuture<LoginResponse> login(LoginRequest request) {
        return onWorkers(() -> accountLogin.loginWithoutSession(request).response());
    }

    @Override
    public CompletableFuture<RefreshTokenResponse> refreshToken(RefreshTokenRequest request) {
        return onWorkers(() -> refreshTokens.refresh(request));
    }

    @Override
    public CompletableFuture<SubmitRealNameResponse> submitRealName(SubmitRealNameRequest request) {
        return onWorkers(() -> handleSubmitRealName(request));
    }

    @Override
    public CompletableFuture<AccountDeletionResponse> requestAccountDeletion(AccountDeletionRequest request) {
        return onWorkers(() -> handleRequestDeletion(request));
    }

    @Override
    public CompletableFuture<AccountDeletionResponse> cancelAccountDeletion(CancelAccountDeletionRequest request) {
        return onWorkers(() -> handleCancelDeletion(request));
    }

    @Override
    public CompletableFuture<Long> countZoneRoles(int zoneId) {
        if (store == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("player store not wired"));
        }
        return onWorkers(() -> store.countByZone(zoneId));
    }

    /** 实名提交（对齐 {@code SubmitRealNameLogic}）：未开防沉迷回空应答；令牌无效回 2000，其余按带内 tip。 */
    SubmitRealNameResponse handleSubmitRealName(SubmitRealNameRequest req) {
        SubmitRealNameResponse.Builder resp = SubmitRealNameResponse.newBuilder();
        if (antiAddiction == null) {
            return resp.build();
        }
        if (tokens == null || store == null || antiAddiction.realNameService() == null) {
            return resp.setErrorMessage(Tips.of(LoginErrorTip.login_error.kLoginRealNameServiceUnavailable_VALUE)).build();
        }
        Optional<TokenData> data;
        try {
            data = tokens.validateAccess(req.getAccessToken());
        } catch (RuntimeException e) {
            log.error("[real-name] access token lookup failed: {}", e.getClass().getSimpleName());
            return resp.setErrorMessage(Tips.of(LoginErrorTip.login_error.kLoginAuthProviderUnavailable_VALUE)).build();
        }
        if (data.isEmpty()) {
            return resp.setErrorMessage(Tips.of(LoginErrorTip.login_error.kLoginAccountNotFound_VALUE)).build();
        }
        String account = data.get().account();
        String channel = data.get().authType();
        Optional<AccountLifecycle> lifecycle;
        try {
            lifecycle = store.readAccountLifecycle(account, false);
        } catch (RuntimeException e) {
            log.error("[real-name] read account failed account={} err={}",
                    AccountDeletionService.maskAccount(account), e.getClass().getSimpleName());
            return resp.setErrorMessage(Tips.of(LoginErrorTip.login_error.kLoginRealNameServiceUnavailable_VALUE)).build();
        }
        String pi = lifecycle.map(AccountLifecycle::realNamePi).orElse("");
        if (pi.isEmpty() && antiAddiction.isSelfManaged(channel)) {
            RealNameService.SubmitOutcome sub = antiAddiction.realNameService()
                    .submit(account, req.getName(), req.getIdNumber());
            pi = sub.pi();
            if (!sub.ok()) {
                resp.setErrorMessage(Tips.of(sub.tipId()));
            }
        }
        var verdict = antiAddiction.evaluate(channel, pi, antiAddiction.now());
        if (verdict.status() != null) {
            resp.setAntiAddiction(verdict.status());
        }
        return resp.build();
    }

    /** 申请注销（对齐 {@code RequestAccountDeletionLogic}）：要求重新凭据认证，拒绝 {@code access_token}。 */
    AccountDeletionResponse handleRequestDeletion(AccountDeletionRequest req) {
        if (accountDeletion == null) {
            return AccountDeletionService.tipReply(LoginErrorTip.login_error.kLoginDataSerializeFailed_VALUE);
        }
        String authType = req.getAuthType().isEmpty() ? "password" : req.getAuthType();
        if ("access_token".equals(authType)) {
            return AccountDeletionService.tipReply(LoginErrorTip.login_error.kLoginAccountNotFound_VALUE);
        }
        LoginRequest loginReq = LoginRequest.newBuilder()
                .setAccount(req.getAccount())
                .setPassword(req.getPassword())
                .setAuthType(req.getAuthType())
                .setAuthToken(req.getAuthToken())
                .build();
        Optional<com.game.login.auth.LoginAuthenticator.Authenticated> auth;
        try {
            auth = accountLogin.authenticate(loginReq);
        } catch (AuthProviderUnavailableException e) {
            return AccountDeletionService.tipReply(LoginErrorTip.login_error.kLoginAuthProviderUnavailable_VALUE);
        }
        if (auth.isEmpty()) {
            return AccountDeletionService.tipReply(LoginErrorTip.login_error.kLoginAccountNotFound_VALUE);
        }
        return accountDeletion.request(auth.get().account(), AccountLifecycle.SOURCE_USER,
                "apple".equals(authType), req.getAuthorizationCode());
    }

    /** 撤销注销（对齐 {@code CancelAccountDeletionLogic}）：凭冷静期内的 {@code access_token} 鉴权。 */
    AccountDeletionResponse handleCancelDeletion(CancelAccountDeletionRequest req) {
        if (accountDeletion == null || tokens == null) {
            return AccountDeletionService.tipReply(LoginErrorTip.login_error.kLoginDataSerializeFailed_VALUE);
        }
        if (req.getAccessToken().isEmpty()) {
            return AccountDeletionService.tipReply(LoginErrorTip.login_error.kLoginAccountNotFound_VALUE);
        }
        Optional<TokenData> data;
        try {
            data = tokens.validateAccess(req.getAccessToken());
        } catch (RuntimeException e) {
            log.error("[account-deletion] access token lookup failed on cancel: {}", e.getClass().getSimpleName());
            return AccountDeletionService.tipReply(LoginErrorTip.login_error.kLoginRedisError_VALUE);
        }
        if (data.isEmpty()) {
            return AccountDeletionService.tipReply(LoginErrorTip.login_error.kLoginAccountNotFound_VALUE);
        }
        return accountDeletion.cancel(data.get().account());
    }

    /** 投递到工作线程池；队列满（同步抛 RejectedExecutionException）也落成异常完成的 future。 */
    private <T> CompletableFuture<T> onWorkers(Supplier<T> task) {
        try {
            return CompletableFuture.supplyAsync(task, executor);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
