package com.game.login.handler;

import com.game.api.proto.SessionContext;
import com.game.login.dispatch.ClientMessageHandler;
import com.game.login.dispatch.HandlerReply;
import com.game.login.dispatch.Tips;
import com.game.login.token.LoginTokens;
import com.game.login.token.TokenPair;
import com.game.proto.TipInfoMessage;
import com.game.proto.login.RefreshTokenRequest;
import com.game.proto.login.RefreshTokenResponse;
import com.game.table.CommonErrorTip;
import com.game.table.LoginErrorTip;
import com.google.protobuf.Message;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RefreshToken（127，同 mmorpg {@code refreshtokenlogic.go}）：refresh token 轮换。不要求本连接已登录。
 * refresh token 为空、无效、已被用过（且不在重放宽限窗口内）、令牌存储出错一律回 2000（基线同；客户端据此走完整重登）；
 * 成功回新的一对，旧 refresh 作废。宽限窗口内对同一旧 refresh 的重放回同一对（见 {@link LoginTokens#refresh}），
 * 所以回包丢失（1003 / 超时）后客户端应拿原 token 重试，而不是直接重登。
 * HTTP {@code /api/refresh-token} 走同一个 {@link #refresh}。
 *
 * <p><b>请求预算</b>（客户端路径，{@link #DEFAULT_REFRESH_BUDGET}，从分发器受理这条请求开始算，含工作队列排队）：
 * gate 调 login 的 Dubbo 超时（5 s）一到就放行同一会话的下一条请求，而本处理器可能还在跑。轮换唯一的写是那段原子脚本，
 * 所以脚本发出之前最后看一次截止（{@link LoginTokens#refresh(String, BooleanSupplier)}）：已过就什么都不写、回 1003——
 * 旧 refresh 仍然有效，按上面的约定客户端拿原 token 重试即可，不回 2000（那会逼客户端完整重登）。
 * mmorpg 截止后回 2000（{@code token.go} Refresh 提交前查 ctx），但它的截止就是路由服的转发截止：到那时路由服已放弃这次调用，
 * 客户端收到的是 gate 回的 1003，那份 2000 送不到。Java 的预算短于 gate 的超时，这份应答送得到，所以按客户端实际看到的对齐，回 1003。
 * HTTP 路径不经 gate 的会话 lane，没有要保序的下一条请求，不设截止。
 * 已知残留：脚本保留 Redisson 的超时重发（重放恢复靠它，见 {@code RedisLoginTokens}），截止前发出的脚本首发没到 Redis 时，
 * 重发仍可能在截止之后才轮换。
 */
public final class RefreshTokenHandler implements ClientMessageHandler<RefreshTokenRequest> {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenHandler.class);

    /**
     * 客户端路径一次轮换的请求预算（见类注释「请求预算」）：取 Java 各后端的整请求预算（基线 RequestBudget 3500 ms），
     * 比 gate 调 login 的 Dubbo 超时少 1.5 s，差值留给链路往返、那段脚本与回包。
     */
    public static final Duration DEFAULT_REFRESH_BUDGET = Duration.ofMillis(3500);

    private final LoginTokens tokens;
    private final LongSupplier nanoClock;
    private final Duration refreshBudget;

    public RefreshTokenHandler(LoginTokens tokens) {
        this(tokens, System::nanoTime, DEFAULT_REFRESH_BUDGET);
    }

    /**
     * 同上；请求预算与单调时钟可注入（测试用）。
     *
     * @param nanoClock     单调时钟（纳秒），只用于请求预算的截止判断
     * @param refreshBudget 客户端路径的请求预算，必须为正且小于 gate 调 login 的 Dubbo 超时
     */
    RefreshTokenHandler(LoginTokens tokens, LongSupplier nanoClock, Duration refreshBudget) {
        if (refreshBudget.isNegative() || refreshBudget.isZero()) {
            throw new IllegalArgumentException("轮换 refresh 的请求预算必须为正: " + refreshBudget);
        }
        this.tokens = tokens;
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.refreshBudget = refreshBudget;
    }

    @Override
    public String methodName() {
        return "RefreshToken";
    }

    @Override
    public Class<RefreshTokenRequest> requestType() {
        return RefreshTokenRequest.class;
    }

    @Override
    public Class<RefreshTokenResponse> responseType() {
        return RefreshTokenResponse.class;
    }

    /** 没有经过分发器的调用（测试）：按此刻受理、没有排队。 */
    @Override
    public CompletableFuture<HandlerReply> handle(SessionContext session, RefreshTokenRequest request) {
        return handle(session, request, 0);
    }

    /** 客户端路径：截止 = 受理时刻 + 请求预算（排队 {@code queuedNanos} 从预算里扣掉，见类注释「请求预算」）。 */
    @Override
    public CompletableFuture<HandlerReply> handle(SessionContext session, RefreshTokenRequest request, long queuedNanos) {
        long deadline = nanoClock.getAsLong() - queuedNanos + refreshBudget.toNanos();
        return CompletableFuture.completedFuture(HandlerReply.of(
                rotate(request, () -> deadline - nanoClock.getAsLong() <= 0)));
    }

    /** 轮换，不设截止（阻塞，login 工作线程上调用）：HTTP 路径用。 */
    public RefreshTokenResponse refresh(RefreshTokenRequest request) {
        return rotate(request, () -> false);
    }

    private RefreshTokenResponse rotate(RefreshTokenRequest request, BooleanSupplier pastDeadline) {
        Optional<TokenPair> pair;
        try {
            pair = tokens.refresh(request.getRefreshToken(), pastDeadline);
        } catch (LoginTokens.RotationAbandoned e) {
            log.warn("刷新令牌放弃：请求预算 {} 在轮换之前已用完，什么都没写，旧 refresh 仍有效 token_len={}", refreshBudget,
                    request.getRefreshToken().length());
            return RefreshTokenResponse.newBuilder()
                    .setErrorMessage(Tips.of(CommonErrorTip.common_error.kServiceUnavailable_VALUE))
                    .build();
        } catch (RuntimeException e) {
            // 存储异常可能携带 Redis 的令牌键，不能输出消息或嵌套异常。
            log.error("刷新令牌时令牌存储出错 reason={}", e.getClass().getName());
            pair = Optional.empty();
        }
        if (pair.isEmpty()) {
            log.info("刷新令牌失败（空 / 无效 / 已用过 / 存储出错） token_len={}", request.getRefreshToken().length());
            return RefreshTokenResponse.newBuilder()
                    .setErrorMessage(Tips.of(LoginErrorTip.login_error.kLoginAccountNotFound_VALUE))
                    .build();
        }
        return RefreshTokenResponse.newBuilder()
                .setAccessToken(pair.get().accessToken())
                .setRefreshToken(pair.get().refreshToken())
                .setAccessTokenExpire(pair.get().accessExpireSeconds())
                .setRefreshTokenExpire(pair.get().refreshExpireSeconds())
                .build();
    }

    @Override
    public Optional<Message> failureBody(TipInfoMessage tip) {
        return Optional.of(RefreshTokenResponse.newBuilder().setErrorMessage(tip).build());
    }
}
