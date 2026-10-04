package com.game.login;

import com.game.api.AccountLoginService;
import com.game.api.DubboGroups;
import com.game.login.account.AccountLogin;
import com.game.login.dispatch.LoginWorkerPool;
import com.game.login.handler.RefreshTokenHandler;
import com.game.proto.login.LoginRequest;
import com.game.proto.login.LoginResponse;
import com.game.proto.login.RefreshTokenRequest;
import com.game.proto.login.RefreshTokenResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link AccountLoginService} 的 Dubbo 提供方（group {@code login}，Triple）：HTTP 登录 / 刷新令牌。
 * 流程与 TCP 48 / 127 共用（{@link AccountLogin} / {@link RefreshTokenHandler}），在 login 工作线程池上执行（不占 Dubbo 线程）；
 * 工作队列满时 future 异常完成（gateway 回 500）。
 */
@DubboService(group = DubboGroups.LOGIN)
public class LoginAccountService implements AccountLoginService {

    private final AccountLogin accountLogin;
    private final RefreshTokenHandler refreshTokens;
    private final Executor executor;

    @Autowired
    public LoginAccountService(AccountLogin accountLogin, RefreshTokenHandler refreshTokens, LoginWorkerPool workers) {
        this(accountLogin, refreshTokens, (Executor) workers);
    }

    /** 测试用：阻塞调用放到给定的执行器上。 */
    LoginAccountService(AccountLogin accountLogin, RefreshTokenHandler refreshTokens, Executor executor) {
        this.accountLogin = accountLogin;
        this.refreshTokens = refreshTokens;
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

    /** 投递到工作线程池；队列满（同步抛 RejectedExecutionException）也落成异常完成的 future。 */
    private <T> CompletableFuture<T> onWorkers(Supplier<T> task) {
        try {
            return CompletableFuture.supplyAsync(task, executor);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
