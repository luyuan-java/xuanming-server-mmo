package com.game.gateway.login;

import com.game.api.AccountLoginService;
import com.game.gateway.ratelimit.RateLimitDecision;
import com.game.gateway.ratelimit.RateLimiter;
import com.game.gateway.zone.ZoneDirectory;
import com.game.proto.AccountSimplePlayer;
import com.game.proto.login.AccountSimplePlayerWrapper;
import com.game.proto.login.LoginRequest;
import com.game.proto.login.LoginResponse;
import com.game.proto.login.RefreshTokenRequest;
import com.game.proto.login.RefreshTokenResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import org.apache.dubbo.rpc.RpcException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP 登录 / 刷新令牌 → xm-login {@link AccountLoginService}（Dubbo）的适配（同 mmorpg Java Gateway 的 LoginService /
 * RefreshTokenService）：
 * <ul>
 *   <li>login 回业务错误（error_message）一律 401，message 带 {@code upstream_err=<tip>}（客户端重新认证）；</li>
 *   <li>调用失败：超时 / 网络 / 没有可用的 login → 500 {@code login_unavailable}；其余异常 → 500 {@code internal_error}；</li>
 *   <li>区不在区服目录里 → 500 {@code unknown_zone}（基线按区路由 login，找不到该区的 login 同样是 5xx 一类）；
 *       区服目录读不到时跳过这项核对（基线不查区服表；登录不放人进游戏，进场仍经 assign-gate 的 fail-closed 准入）；</li>
 *   <li>开服限流（{@link RateLimiter}，区号核对之后、调 login 之前）：排队 → 100 {@code QUEUEING}；拒绝 → 429；
 *       冷却身份 = 账号，三方认证没有账号时用 auth_token（只进哈希）。refresh 不限流（同基线：游戏内正常续期不能被挡）；</li>
 *   <li>refresh 为空 → 401 {@code empty_refresh_token}，不调 login；不重试（重试会拿已作废的 refresh 再打一次）。</li>
 * </ul>
 * 不记口令 / 令牌。线程安全；查区服目录（1 s 缓存，过期时读一次 MySQL）与限流（Redis，限流打开时）在调用线程上阻塞，其余返回 future。
 */
public final class LoginHttpService {

    private static final Logger log = LoggerFactory.getLogger(LoginHttpService.class);

    static final String LOGIN_UNAVAILABLE = "login_unavailable";
    static final String INTERNAL_ERROR = "internal_error";
    static final String UNKNOWN_ZONE = "unknown_zone";
    static final String EMPTY_REFRESH_TOKEN = "empty_refresh_token";

    private final AccountLoginService login;
    private final ZoneDirectory zones;
    private final LoginHttpMetrics metrics;
    private final RateLimiter limiter;

    public LoginHttpService(AccountLoginService login, ZoneDirectory zones, LoginHttpMetrics metrics) {
        this(login, zones, metrics, null);
    }

    /** @param limiter 开服限流；为 null 表示不限流 */
    public LoginHttpService(AccountLoginService login, ZoneDirectory zones, LoginHttpMetrics metrics, RateLimiter limiter) {
        this.login = login;
        this.zones = zones;
        this.metrics = metrics;
        this.limiter = limiter;
    }

    public CompletableFuture<HttpLoginResponse> login(HttpLoginRequest request) {
        return login(request, null);
    }

    /** @param clientIp 客户端 IP（限流用） */
    public CompletableFuture<HttpLoginResponse> login(HttpLoginRequest request, String clientIp) {
        boolean known;
        boolean verified = true;
        if (request.zoneId() <= 0 || request.zoneId() > Integer.MAX_VALUE) {
            known = false;
        } else {
            try {
                known = zones.find((int) request.zoneId()).isPresent();
            } catch (RuntimeException e) {
                verified = false;
                // 读不到区服目录时不挡登录（基线 /api/login 根本不查区服表）：登录本身不放人进游戏，进场仍经 assign-gate 的 fail-closed 准入
                log.warn("读取区服目录失败，HTTP 登录跳过区号核对 zone={}: {}", request.zoneId(), e.toString());
                known = true;
            }
        }
        if (!known) {
            return done(recordLogin(HttpLoginResponse.error(HttpLoginResponse.CODE_INTERNAL, UNKNOWN_ZONE)));
        }
        if (limiter != null) {
            // 区号没核对上（目录读不到）时不判区桶：不按请求里的任意区号建桶键（分波照判）
            RateLimitDecision decision = verified
                    ? limiter.check((int) request.zoneId(), clientIp, identity(request), RateLimiter.Scope.LOGIN)
                    : limiter.checkUnverifiedZone((int) request.zoneId(), clientIp, identity(request),
                            RateLimiter.Scope.LOGIN);
            if (decision.kind() == RateLimitDecision.Kind.QUEUE) {
                return done(recordLogin(HttpLoginResponse.queueing(decision.retryAfterMs(), decision.queuePos())));
            }
            if (decision.kind() == RateLimitDecision.Kind.DENY) {
                return done(recordLogin(HttpLoginResponse.error(HttpLoginResponse.CODE_RATE_LIMITED, decision.reason())));
            }
        }
        LoginRequest rpc = LoginRequest.newBuilder()
                .setAccount(nullToEmpty(request.account()))
                .setPassword(nullToEmpty(request.password()))
                .setAuthType(nullToEmpty(request.authType()))
                .setAuthToken(nullToEmpty(request.authToken()))
                .build();
        return call(() -> login.login(rpc))
                .handle((response, failure) -> recordLogin(failure != null
                        ? HttpLoginResponse.error(HttpLoginResponse.CODE_INTERNAL, failureMessage("login", failure))
                        : toHttp(response)));
    }

    public CompletableFuture<HttpRefreshTokenResponse> refresh(HttpRefreshTokenRequest request) {
        if (request.refreshToken() == null || request.refreshToken().isBlank()) {
            return done(recordRefresh(HttpRefreshTokenResponse.error(HttpLoginResponse.CODE_AUTH_REJECTED,
                    EMPTY_REFRESH_TOKEN)));
        }
        RefreshTokenRequest rpc = RefreshTokenRequest.newBuilder().setRefreshToken(request.refreshToken()).build();
        return call(() -> login.refreshToken(rpc))
                .handle((response, failure) -> recordRefresh(failure != null
                        ? HttpRefreshTokenResponse.error(HttpLoginResponse.CODE_INTERNAL,
                                failureMessage("refresh", failure))
                        : toHttp(response)));
    }

    static HttpLoginResponse toHttp(LoginResponse response) {
        if (response.hasErrorMessage()) {
            return HttpLoginResponse.error(HttpLoginResponse.CODE_AUTH_REJECTED,
                    "upstream_err=" + Integer.toUnsignedString(response.getErrorMessage().getId()));
        }
        List<HttpLoginResponse.PlayerInfo> players = new ArrayList<>(response.getPlayersCount());
        for (AccountSimplePlayerWrapper wrapper : response.getPlayersList()) {
            AccountSimplePlayer player = wrapper.getPlayer();
            players.add(new HttpLoginResponse.PlayerInfo(player.getPlayerId(), emptyToNull(player.getName()), 0));
        }
        return HttpLoginResponse.ok(players, emptyToNull(response.getAccessToken()),
                emptyToNull(response.getRefreshToken()),
                response.getAccessTokenExpire(), response.getRefreshTokenExpire());
    }

    static HttpRefreshTokenResponse toHttp(RefreshTokenResponse response) {
        if (response.hasErrorMessage()) {
            return HttpRefreshTokenResponse.error(HttpLoginResponse.CODE_AUTH_REJECTED,
                    "upstream_err=" + Integer.toUnsignedString(response.getErrorMessage().getId()));
        }
        return HttpRefreshTokenResponse.ok(emptyToNull(response.getAccessToken()),
                emptyToNull(response.getRefreshToken()),
                response.getAccessTokenExpire(), response.getRefreshTokenExpire());
    }

    /** 同步抛出、返回 null 都归成失败的 future。 */
    private static <T> CompletableFuture<T> call(Supplier<CompletableFuture<T>> invocation) {
        try {
            CompletableFuture<T> future = invocation.get();
            return future == null
                    ? CompletableFuture.failedFuture(new IllegalStateException("login 返回了 null future"))
                    : future;
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    static String failureMessage(String what, Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
        // 超时 / 网络 / 没有提供方（直连是 NO_INVOKER_AVAILABLE_AFTER_FILTER，注册中心里一个都没有是 FORBIDDEN）/ login 工作队列满
        if (cause instanceof RpcException rpc && (rpc.isTimeout() || rpc.isNetwork()
                || rpc.isNoInvokerAvailableAfterFilter() || rpc.isForbidden())
                || causedBy(cause, RejectedExecutionException.class)) {
            log.warn("{} 调 xm-login 不可用: {}", what, cause.toString());
            return LOGIN_UNAVAILABLE;
        }
        log.error("{} 调 xm-login 出错", what, cause);
        return INTERNAL_ERROR;
    }

    private static boolean causedBy(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    /** proto3 没有「未设置」的字符串：空串在 JSON 里不出现（同基线：没签令牌时没有 access_token / refresh_token 键，名字为 null）。 */
    private static String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }

    private HttpLoginResponse recordLogin(HttpLoginResponse response) {
        metrics.login(response.code());
        return response;
    }

    private HttpRefreshTokenResponse recordRefresh(HttpRefreshTokenResponse response) {
        metrics.refresh(response.code());
        return response;
    }

    private static <T> CompletableFuture<T> done(T value) {
        return CompletableFuture.completedFuture(value);
    }

    /** 限流冷却的身份（同基线 effectiveAccount）：账号；三方认证没有账号时用 auth_token；都没有为 null（不判冷却）。 */
    static String identity(HttpLoginRequest request) {
        if (request.account() != null && !request.account().isBlank()) {
            return request.account();
        }
        if (request.authToken() != null && !request.authToken().isBlank()) {
            return "token:" + request.authToken();
        }
        return null;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
