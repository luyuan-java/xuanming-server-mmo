package com.game.gateway.login;

import com.game.common.zone.ZoneIds;

import com.game.api.AccountLoginService;
import com.game.gateway.ratelimit.RateLimitDecision;
import com.game.gateway.ratelimit.RateLimiter;
import com.game.gateway.zone.ZoneDirectory;
import com.game.proto.AccountSimplePlayer;
import com.game.proto.login.AccountDeletionBlocker;
import com.game.proto.login.AccountDeletionRequest;
import com.game.proto.login.AccountDeletionResponse;
import com.game.proto.login.AccountSimplePlayerWrapper;
import com.game.proto.login.CancelAccountDeletionRequest;
import com.game.proto.login.LoginRequest;
import com.game.proto.login.LoginResponse;
import com.game.proto.login.RefreshTokenRequest;
import com.game.proto.login.RefreshTokenResponse;
import com.game.proto.login.SubmitRealNameRequest;
import com.game.proto.login.SubmitRealNameResponse;
import com.game.table.LoginErrorTip;
import com.game.table.tip.TipFaults;
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
 * HTTP 登录 / 刷新令牌 → game-login {@link AccountLoginService}（Dubbo）的适配（同 mmorpg Java Gateway 的 LoginService /
 * RefreshTokenService）：
 * <ul>
 *   <li>login 回业务错误（error_message）：message 带 {@code upstream_err=<tip>}；Tip.xlsx 标了 fault 的码（{@link TipFaults}，
 *       如渠道故障、令牌签发失败、读 Redis 失败）→ 500（服务端出错，稍后重试），其余 → 401（客户端重新认证）；</li>
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
    private static final int MAX_ACCOUNT_CODE_POINTS = 191;
    private static final int MAX_PASSWORD_CHARS = 1024;
    private static final int MAX_TOKEN_CHARS = 16 << 10;

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
        if (request.zoneId() <= 0 || request.zoneId() > ZoneIds.MAX) {
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
        return refresh(request, null);
    }

    /** 刷新令牌：仅过共享 IP 令牌桶（跳过波次、区桶与账号冷却），超限回 429 IP_RATE_LIMIT。 */
    public CompletableFuture<HttpRefreshTokenResponse> refresh(HttpRefreshTokenRequest request, String clientIp) {
        if (request.refreshToken() == null || request.refreshToken().isBlank()) {
            return done(recordRefresh(HttpRefreshTokenResponse.error(HttpLoginResponse.CODE_AUTH_REJECTED,
                    EMPTY_REFRESH_TOKEN)));
        }
        if (limiter != null) {
            RateLimitDecision decision = limiter.checkIpOnly(clientIp);
            if (decision.kind() == RateLimitDecision.Kind.DENY) {
                return done(recordRefresh(HttpRefreshTokenResponse.error(HttpLoginResponse.CODE_RATE_LIMITED,
                        decision.reason())));
            }
        }
        RefreshTokenRequest rpc = RefreshTokenRequest.newBuilder().setRefreshToken(request.refreshToken()).build();
        return call(() -> login.refreshToken(rpc))
                .handle((response, failure) -> recordRefresh(failure != null
                        ? HttpRefreshTokenResponse.error(HttpLoginResponse.CODE_INTERNAL,
                                failureMessage("refresh", failure))
                        : toHttp(response)));
    }

    /** {@code POST /api/real-name}：不记姓名与身份证号，映射 0 / 401 / 422 / 429 / 500。 */
    public CompletableFuture<HttpRealNameResponse> submitRealName(HttpRealNameRequest request, String clientIp) {
        if (request.zoneId() <= 0 || request.zoneId() > ZoneIds.MAX) {
            return done(recordRealName(HttpRealNameResponse.of(HttpLoginResponse.CODE_INTERNAL, "invalid_zone", null, null)));
        }
        if (limiter != null) {
            RateLimitDecision decision = limiter.check((int) request.zoneId(), clientIp,
                    request.accessToken() == null || request.accessToken().isBlank() ? null : "token:" + request.accessToken(),
                    RateLimiter.Scope.REAL_NAME);
            if (decision.kind() != RateLimitDecision.Kind.PASS) {
                String reason = decision.kind() == RateLimitDecision.Kind.DENY ? decision.reason() : "rate_limited";
                return done(recordRealName(HttpRealNameResponse.of(HttpLoginResponse.CODE_RATE_LIMITED, reason, null, null)));
            }
        }
        SubmitRealNameRequest rpc = SubmitRealNameRequest.newBuilder()
                .setAccessToken(nullToEmpty(request.accessToken()))
                .setName(nullToEmpty(request.name()))
                .setIdNumber(nullToEmpty(request.idNumber()))
                .build();
        return call(() -> login.submitRealName(rpc))
                .handle((response, failure) -> recordRealName(failure != null
                        ? HttpRealNameResponse.of(HttpLoginResponse.CODE_INTERNAL,
                                failureMessage("real_name", failure), null, null)
                        : toHttpRealName(response)));
    }

    /** {@code POST /api/account/delete}：边界校验 → 共享登录限流（排队也回 429）→ 转发 login。 */
    public CompletableFuture<HttpAccountDeletionResponse> requestAccountDeletion(
            HttpAccountDeletionRequest request, String clientIp) {
        String invalid = validateDeletionRequest(request);
        if (invalid != null) {
            return done(recordDeletion(HttpAccountDeletionResponse.error(HttpLoginResponse.CODE_AUTH_REJECTED, invalid)));
        }
        if (limiter != null) {
            String identity = (request.account() != null && !request.account().isBlank())
                    ? request.account()
                    : (request.authToken() != null && !request.authToken().isBlank() ? "token:" + request.authToken() : null);
            RateLimitDecision decision = limiter.check((int) request.zoneId(), clientIp, identity, RateLimiter.Scope.LOGIN);
            if (decision.kind() != RateLimitDecision.Kind.PASS) {
                String reason = decision.kind() == RateLimitDecision.Kind.QUEUE ? "QUEUEING" : decision.reason();
                return done(recordDeletion(HttpAccountDeletionResponse.error(HttpLoginResponse.CODE_RATE_LIMITED, reason)));
            }
        }
        AccountDeletionRequest rpc = AccountDeletionRequest.newBuilder()
                .setAccount(nullToEmpty(request.account()))
                .setPassword(nullToEmpty(request.password()))
                .setAuthType(nullToEmpty(request.authType()))
                .setAuthToken(nullToEmpty(request.authToken()))
                .setAuthorizationCode(nullToEmpty(request.authorizationCode()))
                .build();
        return call(() -> login.requestAccountDeletion(rpc))
                .handle((response, failure) -> recordDeletion(failure != null
                        ? HttpAccountDeletionResponse.error(HttpLoginResponse.CODE_INTERNAL,
                                failureMessage("account_deletion", failure))
                        : toHttpDeletion(response)));
    }

    /** {@code POST /api/account/delete/cancel}：凭冷静期内 access_token 撤销注销。 */
    public CompletableFuture<HttpAccountDeletionResponse> cancelAccountDeletion(
            HttpCancelAccountDeletionRequest request, String clientIp) {
        String invalid = validateCancelDeletionRequest(request);
        if (invalid != null) {
            return done(recordDeletion(HttpAccountDeletionResponse.error(HttpLoginResponse.CODE_AUTH_REJECTED, invalid)));
        }
        if (limiter != null) {
            RateLimitDecision decision = limiter.check((int) request.zoneId(), clientIp,
                    "token:" + request.accessToken(), RateLimiter.Scope.LOGIN);
            if (decision.kind() != RateLimitDecision.Kind.PASS) {
                String reason = decision.kind() == RateLimitDecision.Kind.QUEUE ? "QUEUEING" : decision.reason();
                return done(recordDeletion(HttpAccountDeletionResponse.error(HttpLoginResponse.CODE_RATE_LIMITED, reason)));
            }
        }
        CancelAccountDeletionRequest rpc = CancelAccountDeletionRequest.newBuilder()
                .setAccessToken(nullToEmpty(request.accessToken()))
                .build();
        return call(() -> login.cancelAccountDeletion(rpc))
                .handle((response, failure) -> recordDeletion(failure != null
                        ? HttpAccountDeletionResponse.error(HttpLoginResponse.CODE_INTERNAL,
                                failureMessage("cancel_account_deletion", failure))
                        : toHttpDeletion(response)));
    }

    static HttpLoginResponse toHttp(LoginResponse response) {
        if (response.hasErrorMessage()) {
            int tip = response.getErrorMessage().getId();
            return HttpLoginResponse.error(codeForUpstreamTip("login", tip),
                    "upstream_err=" + Integer.toUnsignedString(tip));
        }
        List<HttpLoginResponse.PlayerInfo> players = new ArrayList<>(response.getPlayersCount());
        for (AccountSimplePlayerWrapper wrapper : response.getPlayersList()) {
            AccountSimplePlayer player = wrapper.getPlayer();
            players.add(new HttpLoginResponse.PlayerInfo(player.getPlayerId(), emptyToNull(player.getName()), 0));
        }
        HttpAntiAddictionStatus aa = response.hasAntiAddiction()
                ? HttpAntiAddictionStatus.from(response.getAntiAddiction())
                : null;
        return HttpLoginResponse.ok(players, emptyToNull(response.getAccessToken()),
                emptyToNull(response.getRefreshToken()),
                response.getAccessTokenExpire(), response.getRefreshTokenExpire(),
                response.getAccountDeletionState(), response.getAccountDeletionExecuteAfter(),
                response.getPreviousAccountDeletedAt(), aa);
    }

    static HttpRealNameResponse toHttpRealName(SubmitRealNameResponse response) {
        HttpAntiAddictionStatus status = response.hasAntiAddiction()
                ? HttpAntiAddictionStatus.from(response.getAntiAddiction())
                : null;
        if (!response.hasErrorMessage() || response.getErrorMessage().getId() == 0) {
            return HttpRealNameResponse.of(HttpRealNameResponse.CODE_OK, null, null, status);
        }
        int tip = response.getErrorMessage().getId();
        if (TipFaults.isFault(tip)) {
            log.warn("real_name 回 in-band 故障码 tip={}", Integer.toUnsignedString(tip));
            return HttpRealNameResponse.of(HttpLoginResponse.CODE_INTERNAL,
                    "upstream_err=" + Integer.toUnsignedString(tip), tip, status);
        }
        if (tip == LoginErrorTip.login_error.kLoginAccountNotFound_VALUE) {
            return HttpRealNameResponse.of(HttpLoginResponse.CODE_AUTH_REJECTED,
                    "upstream_err=" + Integer.toUnsignedString(tip), tip, status);
        }
        return HttpRealNameResponse.of(HttpRealNameResponse.CODE_REJECTED,
                "upstream_err=" + Integer.toUnsignedString(tip), tip, status);
    }

    static HttpAccountDeletionResponse toHttpDeletion(AccountDeletionResponse response) {
        int code = HttpLoginResponse.CODE_OK;
        String message = null;
        int tip = 0;
        if (response.hasErrorMessage() && response.getErrorMessage().getId() != 0) {
            tip = response.getErrorMessage().getId();
            message = "upstream_err=" + Integer.toUnsignedString(tip);
            code = codeForUpstreamTip("account_deletion", tip);
        }
        List<HttpAccountDeletionResponse.Blocker> blockers = new ArrayList<>(response.getBlockersCount());
        for (AccountDeletionBlocker b : response.getBlockersList()) {
            blockers.add(new HttpAccountDeletionResponse.Blocker(b.getPlayerId(), b.getReason()));
        }
        return HttpAccountDeletionResponse.answered(code, message, tip,
                response.getState(), response.getExecuteAfter(), blockers);
    }

    private static String validateDeletionRequest(HttpAccountDeletionRequest req) {
        if (req.zoneId() <= 0 || req.zoneId() > ZoneIds.MAX) {
            return "invalid_zone_id";
        }
        String account = req.account();
        if ((account != null && account.codePointCount(0, account.length()) > MAX_ACCOUNT_CODE_POINTS)
                || longer(req.password(), MAX_PASSWORD_CHARS)
                || longer(req.authType(), MAX_TOKEN_CHARS)
                || longer(req.authToken(), MAX_TOKEN_CHARS)
                || longer(req.authorizationCode(), MAX_TOKEN_CHARS)) {
            return "field_too_long";
        }
        return null;
    }

    private static String validateCancelDeletionRequest(HttpCancelAccountDeletionRequest req) {
        if (req.zoneId() <= 0 || req.zoneId() > ZoneIds.MAX) {
            return "invalid_zone_id";
        }
        if (req.accessToken() == null || req.accessToken().isBlank()) {
            return "empty_access_token";
        }
        return longer(req.accessToken(), MAX_TOKEN_CHARS) ? "field_too_long" : null;
    }

    private static boolean longer(String s, int max) {
        return s != null && s.length() > max;
    }

    static HttpRefreshTokenResponse toHttp(RefreshTokenResponse response) {
        if (response.hasErrorMessage()) {
            int tip = response.getErrorMessage().getId();
            return HttpRefreshTokenResponse.error(codeForUpstreamTip("refresh", tip),
                    "upstream_err=" + Integer.toUnsignedString(tip));
        }
        return HttpRefreshTokenResponse.ok(emptyToNull(response.getAccessToken()),
                emptyToNull(response.getRefreshToken()),
                response.getAccessTokenExpire(), response.getRefreshTokenExpire());
    }

    /**
     * login 回业务错误码时的 HTTP 业务码（同 mmorpg LoginService#httpCodeForUpstreamTip）。分类只认 Tip.xlsx 的 fault 列
     * （ContractSync 同步来的 {@link TipFaults}），这里不另写故障码集合：故障码（渠道 / 存储故障、令牌签发失败……）→ 500，
     * 服务端出错、客户端稍后重试；其余码与本版本不认识的新码 → 401，客户端重新认证。message 都带上游 tip。
     */
    static int codeForUpstreamTip(String what, int tip) {
        if (TipFaults.isFault(tip)) {
            log.warn("{} 回 in-band 故障码 tip={}", what, Integer.toUnsignedString(tip));
            return HttpLoginResponse.CODE_INTERNAL;
        }
        return HttpLoginResponse.CODE_AUTH_REJECTED;
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
            // Dubbo 超时或网络异常可能包含原请求（口令、令牌），只记异常类型。
            log.warn("{} 调 game-login 不可用 reason={}", what, cause.getClass().getName());
            return LOGIN_UNAVAILABLE;
        }
        log.error("{} 调 game-login 出错 reason={}", what, cause.getClass().getName());
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

    private HttpRealNameResponse recordRealName(HttpRealNameResponse response) {
        metrics.realName(response.code());
        return response;
    }

    private HttpAccountDeletionResponse recordDeletion(HttpAccountDeletionResponse response) {
        metrics.accountDeletion(response.code());
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
