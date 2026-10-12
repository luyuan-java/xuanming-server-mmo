package com.game.login.handler;

import com.game.api.proto.BindAccount;
import com.game.api.proto.SessionContext;
import com.game.api.proto.SessionDirective;
import com.game.login.account.AccountLogin;
import com.game.login.dispatch.ClientMessageHandler;
import com.game.login.dispatch.HandlerReply;
import com.game.proto.TipInfoMessage;
import com.game.proto.login.LoginRequest;
import com.game.proto.login.LoginResponse;
import com.google.protobuf.Message;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Login（48）：流程在 {@link AccountLogin}（TCP 路径：带会话、查设备数），成功后让 gate 把账号绑到会话上。
 *
 * <p>客户端可见契约（mmorpg 登录契约 §4）：
 * <ul>
 *   <li>任何认证失败 → 2000 kLoginAccountNotFound（不区分原因）；同一账号已有 Login 在途 → 2005；设备数超限 → 2024；</li>
 *   <li>成功：{@code players} 按建角先后排列，新账号为空列表不报错；不设置 {@code error_message}；
 *       口令登录签一对令牌（字段 3–6），access token 登录不签（字段 3–6 为空）；</li>
 *   <li>同一连接上允许再次 Login（机器人 access_token 失败后会回退口令登录），新的 BindAccount 覆盖旧账号。</li>
 * </ul>
 */
public final class LoginHandler implements ClientMessageHandler<LoginRequest> {

    private final AccountLogin accountLogin;

    public LoginHandler(AccountLogin accountLogin) {
        this.accountLogin = accountLogin;
    }

    @Override
    public String methodName() {
        return "Login";
    }

    @Override
    public Class<LoginRequest> requestType() {
        return LoginRequest.class;
    }

    @Override
    public Class<LoginResponse> responseType() {
        return LoginResponse.class;
    }

    @Override
    public CompletableFuture<HandlerReply> handle(SessionContext session, LoginRequest request) {
        AccountLogin.Outcome outcome = accountLogin.loginOnSession(session, request);
        if (!outcome.succeeded()) {
            return CompletableFuture.completedFuture(HandlerReply.of(outcome.response()));
        }
        return CompletableFuture.completedFuture(HandlerReply.of(outcome.response(), SessionDirective.newBuilder()
                .setBindAccount(BindAccount.newBuilder().setAccount(outcome.account()))
                .build()));
    }

    @Override
    public Optional<Message> failureBody(TipInfoMessage tip) {
        return Optional.of(LoginResponse.newBuilder().setErrorMessage(tip).build());
    }
}
