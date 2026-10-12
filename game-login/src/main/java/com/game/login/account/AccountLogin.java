package com.game.login.account;

import com.game.api.proto.SessionContext;
import com.game.login.auth.LoginAuthenticator;
import com.game.login.auth.LoginAuthenticator.Authenticated;
import com.game.login.dispatch.InFlightKeys;
import com.game.login.dispatch.Tips;
import com.game.login.handler.PlayerViews;
import com.game.login.session.LoginDevices;
import com.game.login.token.LoginTokens;
import com.game.login.token.TokenPair;
import com.game.player.store.PlayerRow;
import com.game.player.store.PlayerStore;
import com.game.proto.login.LoginRequest;
import com.game.proto.login.LoginResponse;
import com.game.table.LoginErrorTip;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 登录主流程（同 mmorpg {@code loginlogic.go}），两条路径共用：
 * <ul>
 *   <li><b>TCP 48</b>（经 gate，带会话）：认证 → 账号锁 → 设备数上限 → 取 / 建账号 → 签令牌 → 角色列表；
 *       成功后由调用方让 gate 把账号绑到会话上；</li>
 *   <li><b>HTTP {@code /api/login}</b>（gateway 经 Dubbo，无会话）：认证 → 账号锁 → 取 / 建账号 → 签令牌 → 角色列表；
 *       不查设备数、不绑会话（之后客户端在 gate 上用 access token 再登录一次完成绑定）。</li>
 * </ul>
 * 失败码：认证失败 2000（不区分原因）、同一账号登录在途 2005、设备数超限 2024、设备登记出错 2023；取 / 建账号失败抛异常
 * （调用方按调用失败处理）。签令牌失败不致命（基线同：登录照常成功、只是没有令牌）。access token 登录不签新令牌。
 * 阻塞（MySQL / Redis），只在 login 工作线程上调用；线程安全。
 */
public final class AccountLogin {

    private static final Logger log = LoggerFactory.getLogger(AccountLogin.class);

    private final LoginAuthenticator authenticator;
    private final PlayerStore store;
    private final LoginTokens tokens;
    private final LoginDevices devices;
    private final InFlightKeys<String> accountsInFlight = new InFlightKeys<>();

    public AccountLogin(LoginAuthenticator authenticator, PlayerStore store, LoginTokens tokens, LoginDevices devices) {
        this.authenticator = authenticator;
        this.store = store;
        this.tokens = tokens;
        this.devices = devices;
    }

    /**
     * 一次登录的结局。
     *
     * @param response 给客户端的应答（失败时只带 {@code error_message}）
     * @param account  成功时是通过认证的账号；失败为 null
     */
    public record Outcome(LoginResponse response, String account) {

        public boolean succeeded() {
            return account != null;
        }

        static Outcome failed(int tipId) {
            return new Outcome(LoginResponse.newBuilder().setErrorMessage(Tips.of(tipId)).build(), null);
        }
    }

    /** TCP 48：带会话（查设备数）。 */
    public Outcome loginOnSession(SessionContext session, LoginRequest request) {
        return login(request, session);
    }

    /** HTTP：无会话（不查设备数）。 */
    public Outcome loginWithoutSession(LoginRequest request) {
        return login(request, null);
    }

    private Outcome login(LoginRequest request, SessionContext session) {
        Optional<Authenticated> authenticated = authenticator.authenticate(request);
        if (authenticated.isEmpty()) {
            // 不记口令 / 令牌；未认证的账号 / auth_type 是客户端任意输入，只记长度，防日志注入。
            log.info("登录认证失败 path={} auth_type_len={} account_len={}", path(session),
                    request.getAuthType().length(), request.getAccount().length());
            return Outcome.failed(LoginErrorTip.login_error.kLoginAccountNotFound_VALUE);
        }
        String account = authenticated.get().account();
        if (!accountsInFlight.tryAcquire(account)) {
            log.info("同一账号登录在途，拒绝 account={} path={}", account, path(session));
            return Outcome.failed(LoginErrorTip.login_error.kLoginInProgress_VALUE);
        }
        try {
            String deviceKey = null;
            if (session != null) {
                deviceKey = LoginDevices.sessionKey(session.getGateInstanceId(), session.getSessionId());
                Integer refused = admitDevice(account, deviceKey);
                if (refused != null) {
                    return Outcome.failed(refused);
                }
            }
            List<PlayerRow> players;
            try {
                store.ensureAccount(account);
                players = store.listPlayers(account);
            } catch (RuntimeException e) {
                // gate 不会绑这个账号：撤销这次新登记（同账号重登时登记原本就在、gate 仍绑着它，不能撤）
                if (deviceKey != null && !account.equals(session.getAccount())) {
                    revokeQuietly(account, deviceKey);
                }
                throw e;
            }
            LoginResponse.Builder response = LoginResponse.newBuilder().addAllPlayers(PlayerViews.wrapAll(players));
            if (authenticated.get().issuesTokens()) {
                issueTokens(account, authenticated.get().authType(), response);
            }
            if (deviceKey != null) {
                bindQuietly(account, deviceKey, session.getAccount());
            }
            log.info("登录成功 account={} path={} auth_type={} players={}", account, path(session),
                    authenticated.get().authType(), players.size());
            return new Outcome(response.build(), account);
        } finally {
            accountsInFlight.release(account);
        }
    }

    /** 设备数登记；返回拒绝码或 null。被拒时什么都不改（会话原来绑的账号的登记留着：gate 不改绑定）。 */
    private Integer admitDevice(String account, String deviceKey) {
        try {
            if (!devices.admit(account, deviceKey)) {
                log.info("账号设备数超限，拒绝 account={} session={}", account, deviceKey);
                return LoginErrorTip.login_error.kTooManyDevices_VALUE;
            }
            return null;
        } catch (RuntimeException e) {
            log.error("登记登录设备失败 account={} session={}: {}", account, deviceKey, e.toString());
            return LoginErrorTip.login_error.kLoginRedisSetFailed_VALUE;
        }
    }

    /**
     * 会话上的建角 / 进游戏之前续期设备登记（会话离开窗口后——进游戏失败回到大厅、离开游戏、登记过期——再在大厅里行动就重新计入）。
     * 返回拒绝码（名单已满 2024、存储出错 2023）或 null。会话没绑账号时不做事（由调用方按 2028 处理）。
     */
    public Integer renewDevice(SessionContext session) {
        String account = session.getAccount();
        if (account.isEmpty()) {
            return null;
        }
        return admitDevice(account, LoginDevices.sessionKey(session.getGateInstanceId(), session.getSessionId()));
    }

    /**
     * 会话离开窗口（应答里带进场指令、会话结束）：注销设备。按记下的账号与 gate 给的账号都注销——gate 记的账号可能是空的
     * （登录应答没送到 gate）。尽力而为（兜底是登记的有效期）。
     */
    public void leaveDeviceWindow(SessionContext session) {
        String deviceKey = LoginDevices.sessionKey(session.getGateInstanceId(), session.getSessionId());
        try {
            devices.leave(deviceKey, session.getAccount());
        } catch (RuntimeException e) {
            log.warn("注销登录设备失败（按登记有效期自愈） account={} session={}: {}", session.getAccount(), deviceKey,
                    e.toString());
        }
    }

    private void bindQuietly(String account, String deviceKey, String previous) {
        try {
            devices.bind(account, deviceKey, previous);
        } catch (RuntimeException e) {
            log.warn("记录设备归属失败（按登记有效期自愈） account={} session={}: {}", account, deviceKey, e.toString());
        }
    }

    private void revokeQuietly(String account, String deviceKey) {
        try {
            devices.revoke(account, deviceKey);
        } catch (RuntimeException e) {
            log.warn("撤销设备登记失败（按登记有效期自愈） account={} session={}: {}", account, deviceKey, e.toString());
        }
    }

    private void issueTokens(String account, String authType, LoginResponse.Builder response) {
        try {
            TokenPair pair = tokens.issue(account, authType, "");
            response.setAccessToken(pair.accessToken())
                    .setRefreshToken(pair.refreshToken())
                    .setAccessTokenExpire(pair.accessExpireSeconds())
                    .setRefreshTokenExpire(pair.refreshExpireSeconds());
        } catch (RuntimeException e) {
            log.error("签发令牌失败（登录照常成功，只是没有令牌） account={}: {}", account, e.toString());
        }
    }

    private static String path(SessionContext session) {
        return session == null ? "http" : "gate/" + session.getGateNodeId() + "/" + session.getSessionId();
    }
}
