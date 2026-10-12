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
import com.game.table.LoginErrorTip;
import com.google.protobuf.Message;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RefreshToken（127，同 mmorpg {@code refreshtokenlogic.go}）：refresh token 轮换。不要求本连接已登录。
 * refresh token 为空、无效、已被用过、令牌存储出错一律回 2000（基线同；客户端据此走完整重登）；成功回新的一对，旧 refresh 作废。
 * HTTP {@code /api/refresh-token} 走同一个 {@link #refresh}。
 */
public final class RefreshTokenHandler implements ClientMessageHandler<RefreshTokenRequest> {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenHandler.class);

    private final LoginTokens tokens;

    public RefreshTokenHandler(LoginTokens tokens) {
        this.tokens = tokens;
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

    @Override
    public CompletableFuture<HandlerReply> handle(SessionContext session, RefreshTokenRequest request) {
        return CompletableFuture.completedFuture(HandlerReply.of(refresh(request)));
    }

    /** 轮换（阻塞，login 工作线程上调用）。 */
    public RefreshTokenResponse refresh(RefreshTokenRequest request) {
        Optional<TokenPair> pair;
        try {
            pair = tokens.refresh(request.getRefreshToken());
        } catch (RuntimeException e) {
            log.error("刷新令牌时令牌存储出错", e);
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
