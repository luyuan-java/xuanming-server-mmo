package com.game.login.handler;

import com.game.api.proto.BindAccount;
import com.game.api.proto.SessionContext;
import com.game.api.proto.SessionDirective;
import com.game.login.auth.LoginAuthenticator;
import com.game.login.dispatch.ClientMessageHandler;
import com.game.login.dispatch.HandlerReply;
import com.game.login.dispatch.InFlightKeys;
import com.game.login.dispatch.Tips;
import com.game.player.store.PlayerRow;
import com.game.player.store.PlayerStore;
import com.game.proto.TipInfoMessage;
import com.game.proto.login.LoginRequest;
import com.game.proto.login.LoginResponse;
import com.game.table.LoginErrorTip;
import com.google.protobuf.Message;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Login（48）：认证 → 取 / 建账号 → 回账号下全部角色，并让 gate 把账号绑到会话上。
 *
 * <p>客户端可见契约（mmorpg 登录契约 §4）：
 * <ul>
 *   <li>任何认证失败 → 2000 kLoginAccountNotFound（不区分原因）；</li>
 *   <li>同一账号已有 Login 在途 → 2005 kLoginInProgress；</li>
 *   <li>成功：{@code players} 按建角先后排列，新账号为空列表不报错；不设置 {@code error_message}；
 *       access / refresh token 本批不签发（字段 3–6 留空，机器人据此只走口令登录）；</li>
 *   <li>同一连接上允许再次 Login（机器人 access_token 失败后会回退口令登录），新的 BindAccount 覆盖旧账号。</li>
 * </ul>
 */
public final class LoginHandler implements ClientMessageHandler<LoginRequest> {

    private static final Logger log = LoggerFactory.getLogger(LoginHandler.class);

    private final LoginAuthenticator authenticator;
    private final PlayerStore store;
    private final InFlightKeys<String> accountsInFlight = new InFlightKeys<>();

    public LoginHandler(LoginAuthenticator authenticator, PlayerStore store) {
        this.authenticator = authenticator;
        this.store = store;
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
        return CompletableFuture.completedFuture(login(session, request));
    }

    private HandlerReply login(SessionContext session, LoginRequest request) {
        Optional<String> authenticated = authenticator.authenticate(request);
        if (authenticated.isEmpty()) {
            // 不记口令；未认证的账号 / auth_type 是客户端任意输入，只记长度，防日志注入。
            log.info("登录认证失败 gate={} session={} auth_type_len={} account_len={}",
                    session.getGateNodeId(), session.getSessionId(),
                    request.getAuthType().length(), request.getAccount().length());
            return error(LoginErrorTip.login_error.kLoginAccountNotFound_VALUE);
        }
        String account = authenticated.get();
        if (!accountsInFlight.tryAcquire(account)) {
            log.info("同一账号登录在途，拒绝 account={} session={}", account, session.getSessionId());
            return error(LoginErrorTip.login_error.kLoginInProgress_VALUE);
        }
        try {
            store.ensureAccount(account);
            List<PlayerRow> players = store.listPlayers(account);
            LoginResponse response = LoginResponse.newBuilder()
                    .addAllPlayers(PlayerViews.wrapAll(players))
                    .build();
            log.info("登录成功 account={} gate={} session={} players={}",
                    account, session.getGateNodeId(), session.getSessionId(), players.size());
            return HandlerReply.of(response, SessionDirective.newBuilder()
                    .setBindAccount(BindAccount.newBuilder().setAccount(account))
                    .build());
        } finally {
            accountsInFlight.release(account);
        }
    }

    @Override
    public Optional<Message> failureBody(TipInfoMessage tip) {
        return Optional.of(LoginResponse.newBuilder().setErrorMessage(tip).build());
    }

    private static HandlerReply error(int tipId) {
        return HandlerReply.of(LoginResponse.newBuilder().setErrorMessage(Tips.of(tipId)).build());
    }
}
