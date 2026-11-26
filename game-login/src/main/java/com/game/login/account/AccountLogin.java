package com.game.login.account;

import com.game.api.proto.SessionContext;
import com.game.login.auth.AuthProviderUnavailableException;
import com.game.login.auth.LoginAuthenticator;
import com.game.login.auth.LoginAuthenticator.Authenticated;
import com.game.login.compliance.AntiAddictionGate;
import com.game.login.dispatch.InFlightKeys;
import com.game.login.dispatch.Tips;
import com.game.login.handler.PlayerViews;
import com.game.login.session.LoginDevices;
import com.game.login.token.LoginTokens;
import com.game.login.token.TokenPair;
import com.game.player.store.AccountLifecycle;
import com.game.player.store.PlayerRow;
import com.game.player.store.PlayerStore;
import com.game.proto.login.AntiAddictionStatus;
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
 * 失败码：认证失败 2000（不区分原因）、三方渠道或本服务渠道配置故障 kLoginAuthProviderUnavailable（故障码）、
 * 同一账号登录在途 2005、设备数超限 2024、设备登记出错 2023、会话已结束 2018
 * （gate 注销会话之后才落到的迟到登录，回包送不到）；取 / 建账号失败抛异常（调用方按调用失败处理）。
 * 签令牌失败：HTTP 路径回故障码 kLoginTokenIssueFailed（令牌就是这条路径的全部产出，三方 code 也已被消费，同 mmorpg）；
 * gate 路径不致命（会话随后绑定，令牌只用于之后的静默重连）。access token 登录不签新令牌。
 * 阻塞（MySQL / Redis），只在 login 工作线程上调用；线程安全。
 */
public final class AccountLogin {

    private static final Logger log = LoggerFactory.getLogger(AccountLogin.class);

    private final LoginAuthenticator authenticator;
    private final PlayerStore store;
    private final LoginTokens tokens;
    private final LoginDevices devices;
    private final InFlightKeys<String> accountsInFlight;
    private final AntiAddictionGate antiAddiction;
    private final boolean strictAccessTokenAccount;

    public AccountLogin(LoginAuthenticator authenticator, PlayerStore store, LoginTokens tokens, LoginDevices devices) {
        this(authenticator, store, tokens, devices, new InFlightKeys<>());
    }

    public AccountLogin(LoginAuthenticator authenticator, PlayerStore store, LoginTokens tokens, LoginDevices devices,
                        InFlightKeys<String> accountsInFlight) {
        this(authenticator, store, tokens, devices, accountsInFlight, null, false);
    }

    public AccountLogin(LoginAuthenticator authenticator, PlayerStore store, LoginTokens tokens, LoginDevices devices,
                        InFlightKeys<String> accountsInFlight, AntiAddictionGate antiAddiction,
                        boolean strictAccessTokenAccount) {
        this.authenticator = authenticator;
        this.store = store;
        this.tokens = tokens;
        this.devices = devices;
        this.accountsInFlight = java.util.Objects.requireNonNull(accountsInFlight, "accountsInFlight");
        this.antiAddiction = antiAddiction;
        this.strictAccessTokenAccount = strictAccessTokenAccount;
    }

    /**
     * 一次登录的结局。
     *
     * @param response       给客户端的应答（失败时只带 {@code error_message}，防沉迷拒绝时也附带最新 {@code anti_addiction}）
     * @param account        成功时是通过认证的账号；失败为 null
     * @param playDeadlineMs 未成年人防沉迷截止时刻（Unix 毫秒，0 = 不限）
     */
    public record Outcome(LoginResponse response, String account, long playDeadlineMs) {

        public Outcome(LoginResponse response, String account) {
            this(response, account, 0L);
        }

        public boolean succeeded() {
            return account != null;
        }

        static Outcome failed(int tipId) {
            return new Outcome(LoginResponse.newBuilder().setErrorMessage(Tips.of(tipId)).build(), null, 0L);
        }

        static Outcome failedWithAntiAddiction(int tipId, AntiAddictionStatus status) {
            LoginResponse.Builder b = LoginResponse.newBuilder().setErrorMessage(Tips.of(tipId));
            if (status != null) {
                b.setAntiAddiction(status);
            }
            return new Outcome(b.build(), null, 0L);
        }
    }

    /** 提供认证器给注销申请等重新认证路径复用。 */
    public Optional<Authenticated> authenticate(LoginRequest request) {
        return authenticator.authenticate(request);
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
        Optional<Authenticated> authenticated;
        try {
            authenticated = authenticator.authenticate(request);
        } catch (AuthProviderUnavailableException e) {
            log.warn("三方认证渠道故障，登录回故障码 path={} reason={}", path(session), e.getMessage());
            return Outcome.failed(LoginErrorTip.login_error.kLoginAuthProviderUnavailable_VALUE);
        }
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
            boolean isAccessToken = "access_token".equals(request.getAuthType());
            String authType = authenticated.get().authType();
            String channelAuthType = authenticated.get().channelAuthType();
            String deviceKey = session != null
                    ? LoginDevices.sessionKey(session.getGateInstanceId(), session.getSessionId())
                    : null;

            // 先读账号生命周期（access_token 路径在严格模式下不自动建行，防止已注销账号被旧令牌复活）
            Optional<AccountLifecycle> before = readLifecycleQuietly(account, false);
            if (strictAccessTokenAccount && isAccessToken && before.isEmpty()) {
                return Outcome.failed(LoginErrorTip.login_error.kLoginAccountNotFound_VALUE);
            }
            AccountLifecycle lifecycle = before.orElse(null);

            // TCP 路径先判防沉迷（按令牌保留的原始渠道判定自管/平台托管），被拒时不占设备名额、不写会话账本
            AntiAddictionGate.Verdict aaVerdict = AntiAddictionGate.Verdict.DISABLED;
            if (session != null && antiAddiction != null) {
                String pi = lifecycle == null ? "" : lifecycle.realNamePi();
                aaVerdict = antiAddiction.evaluate(channelAuthType, pi, antiAddiction.now());
                if (aaVerdict.rejectTip() != 0) {
                    log.info("防沉迷拒绝绑定会话 account={} channel={} tip={}", account, channelAuthType, aaVerdict.rejectTip());
                    return Outcome.failedWithAntiAddiction(aaVerdict.rejectTip(), aaVerdict.status());
                }
            }

            if (deviceKey != null) {
                Integer refused = admitDevice(account, deviceKey);
                if (refused != null) {
                    return Outcome.failed(refused);
                }
            }
            List<PlayerRow> players;
            try {
                if (!strictAccessTokenAccount || !isAccessToken) {
                    if (before.isEmpty()) {
                        // 首次出现该账号：走 ensureAccount 兼容既有单测，再补查生命周期与历史注销回执
                        store.ensureAccount(account);
                        lifecycle = readLifecycleQuietly(account, false).orElse(null);
                        if (lifecycle != null) {
                            long prevDeletedMs = store.findDeletionReceiptMs(account);
                            if (prevDeletedMs > 0) {
                                lifecycle = lifecycle.withPreviousDeletedMs(prevDeletedMs);
                            }
                        }
                    } else {
                        store.ensureAccount(account);
                    }
                }
                players = store.listPlayers(account);
            } catch (RuntimeException e) {
                // gate 不会绑这个账号：撤销这次新登记（同账号重登时登记原本就在、gate 仍绑着它，不能撤）
                if (deviceKey != null && !account.equals(session.getAccount())) {
                    revokeQuietly(account, deviceKey);
                }
                throw e;
            }
            recordChannelIdentityQuietly(account, channelAuthType, authenticated.get().openId());

            if (deviceKey != null && antiAddiction != null) {
                try {
                    antiAddiction.beginWatchedSession(deviceKey, aaVerdict);
                } catch (RuntimeException e) {
                    log.error("登记防沉迷会话账本失败 account={} session={}: {}", account, deviceKey, e.toString());
                    if (!account.equals(session.getAccount())) {
                        revokeQuietly(account, deviceKey);
                    }
                    return Outcome.failed(LoginErrorTip.login_error.kLoginRedisSetFailed_VALUE);
                }
            }

            LoginResponse.Builder response = LoginResponse.newBuilder().addAllPlayers(PlayerViews.wrapAll(players));
            if (lifecycle != null) {
                response.setAccountDeletionState(lifecycle.deletionState())
                        .setAccountDeletionExecuteAfter(lifecycle.deletionExecuteAfterMs() / 1000L)
                        .setPreviousAccountDeletedAt(lifecycle.previousDeletedMs() / 1000L);
            }
            if (antiAddiction != null) {
                if (session == null) {
                    AntiAddictionStatus status = antiAddiction.evaluateForHttpLogin(
                            account, channelAuthType, lifecycle == null ? "" : lifecycle.realNamePi());
                    if (status != null) {
                        response.setAntiAddiction(status);
                    }
                } else if (aaVerdict.status() != null) {
                    response.setAntiAddiction(aaVerdict.status());
                }
            }
            if (authenticated.get().issuesTokens()
                    && !issueTokens(account, authType, response) && session == null) {
                return Outcome.failed(LoginErrorTip.login_error.kLoginTokenIssueFailed_VALUE);
            }
            if (deviceKey != null) {
                bindQuietly(account, deviceKey, session.getAccount());
            }
            log.info("登录成功 account={} path={} auth_type={} players={}", account, path(session),
                    authType, players.size());
            return new Outcome(response.build(), account, aaVerdict.playDeadlineMs());
        } finally {
            accountsInFlight.release(account);
        }
    }

    /**
     * 设备数登记；返回拒绝码或 null。被拒时什么都不改（会话原来绑的账号的登记留着：gate 不改绑定）。
     * 会话已结束（关闭墓碑，见 {@link LoginDevices#close}）回 2018 kLoginSessionIdNotFound：这是 gate 注销会话之后才落到的迟到调用，
     * 回包送不到客户端，码只进日志与指标，复用「会话不存在」。
     */
    private Integer admitDevice(String account, String deviceKey) {
        try {
            return switch (devices.admit(account, deviceKey)) {
                case ADMITTED -> null;
                case FULL -> {
                    log.info("账号设备数超限，拒绝 account={} session={}", account, deviceKey);
                    yield LoginErrorTip.login_error.kTooManyDevices_VALUE;
                }
                case CLOSED -> {
                    log.info("会话已结束（gate 已注销），迟到的调用不再登记设备 account={} session={}", account, deviceKey);
                    yield LoginErrorTip.login_error.kLoginSessionIdNotFound_VALUE;
                }
            };
        } catch (RuntimeException e) {
            log.error("登记登录设备失败 account={} session={}: {}", account, deviceKey, e.toString());
            return LoginErrorTip.login_error.kLoginRedisSetFailed_VALUE;
        }
    }

    /** 周期续租（{@code RenewSession}）的结论：是否继续续租、下次间隔（秒）、是否需兜底踢掉超时的未成年人。 */
    public record RenewOutcome(boolean renewed, int renewAfterSeconds, boolean kickMinor) {
    }

    /**
     * gate 为活跃连接定期代发的续租（对齐 Go {@code RenewSessionLogic} + {@code renewWatchedSession}）：
     * <ul>
     *   <li>大厅未进场会话同步续期设备登记；会话已留关闭墓碑时立即收尾防沉迷账本并停止续租；</li>
     *   <li>防沉迷会话账本顺延租约；若未成年人已过可玩截止时刻（gate 漏踢），由 login 兜底返回踢线指令；</li>
     *   <li>续租间隔取 {@code sessionTtl / 3}（下限 1 秒），连续失败两次仍不早于 TTL 过期。</li>
     * </ul>
     */
    public RenewOutcome renewSession(SessionContext session, java.time.Duration sessionTtl) {
        if (session == null || session.getAccount().isEmpty() || session.getGateInstanceId().isEmpty()) {
            return new RenewOutcome(false, 0, false);
        }
        String deviceKey = LoginDevices.sessionKey(session.getGateInstanceId(), session.getSessionId());
        if (session.getPlayerId() == 0) {
            LoginDevices.Admission admission = devices.admit(session.getAccount(), deviceKey);
            if (admission != LoginDevices.Admission.ADMITTED) {
                if (antiAddiction != null) {
                    antiAddiction.endWatchedSessionQuietly(deviceKey);
                }
                return new RenewOutcome(false, 0, false);
            }
        }
        if (antiAddiction != null) {
            try {
                long deadlineMs = antiAddiction.renewWatchedSession(deviceKey);
                if (antiAddiction.isPastPlayDeadline(deadlineMs)) {
                    log.error("未成年人超过防沉迷可玩截止时刻仍在线，login 兜底踢线 account={} session={} deadline_ms={}",
                            session.getAccount(), deviceKey, deadlineMs);
                    antiAddiction.endWatchedSessionQuietly(deviceKey);
                    return new RenewOutcome(false, 0, true);
                }
            } catch (RuntimeException e) {
                // 账本暂不可用时不踢线，留待下一次续租重试（同 Go anti_addiction.go）
                log.error("防沉迷会话续租失败，下周期重试 session={}: {}", deviceKey, e.toString());
            }
        }
        long seconds = (sessionTtl == null ? java.time.Duration.ofMinutes(30) : sessionTtl).toSeconds() / 3L;
        int renewAfter = (int) Math.max(1L, Math.min(Integer.MAX_VALUE, seconds));
        return new RenewOutcome(true, renewAfter, false);
    }

    /**
     * 会话上的建角 / 进游戏之前续期设备登记（会话离开窗口后——进游戏失败回到大厅、离开游戏、登记过期——再在大厅里行动就重新计入）。
     * 返回拒绝码（名单已满 2024、存储出错 2023、会话已结束 2018）或 null。会话没绑账号时不做事（由调用方按 2028 处理）。
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

    /**
     * 会话结束（gate 的 sessionClosed）：同 {@link #leaveDeviceWindow} 注销，并留关闭墓碑，挡住 gate 注销之后才落到的迟到登记
     * （见 {@link LoginDevices#close}）。尽力而为（兜底是登记的有效期）。
     */
    public void closeDeviceSession(SessionContext session) {
        String deviceKey = LoginDevices.sessionKey(session.getGateInstanceId(), session.getSessionId());
        if (antiAddiction != null) {
            antiAddiction.endWatchedSessionQuietly(deviceKey);
        }
        try {
            devices.close(deviceKey, session.getAccount());
        } catch (RuntimeException e) {
            log.warn("会话结束时注销登录设备失败（按登记有效期自愈） account={} session={}: {}", session.getAccount(), deviceKey,
                    e.toString());
        }
    }

    private Optional<AccountLifecycle> readLifecycleQuietly(String account, boolean createIfAbsent) {
        Optional<AccountLifecycle> res = store.readAccountLifecycle(account, createIfAbsent);
        return res == null ? Optional.empty() : res;
    }

    private void recordChannelIdentityQuietly(String account, String channel, String openId) {
        if (channel == null || channel.isEmpty() || "access_token".equals(channel)) {
            return;
        }
        try {
            store.upsertAccountChannelIdentity(account, channel, openId == null ? "" : openId);
        } catch (RuntimeException e) {
            log.debug("记录账号渠道身份失败（不影响登录） account={} channel={}: {}", account, channel, e.getClass().getSimpleName());
        }
    }

    private void bindQuietly(String account, String deviceKey, String previous) {
        try {
            if (!devices.bind(account, deviceKey, previous)) {
                log.info("会话已结束（gate 已注销），不记设备归属、撤销本次登记；登录应答送不到 gate account={} session={}", account,
                        deviceKey);
            }
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

    /** 签发令牌写进应答；失败返回 false（是否致命由调用方按路径决定）。 */
    private boolean issueTokens(String account, String authType, LoginResponse.Builder response) {
        try {
            TokenPair pair = tokens.issue(account, authType, "");
            response.setAccessToken(pair.accessToken())
                    .setRefreshToken(pair.refreshToken())
                    .setAccessTokenExpire(pair.accessExpireSeconds())
                    .setRefreshTokenExpire(pair.refreshExpireSeconds());
            return true;
        } catch (RuntimeException e) {
            // 新令牌可能已经生成并进入命令参数，异常消息同样视作凭据。
            log.error("签发令牌失败 account={} reason={}", account, e.getClass().getName());
            return false;
        }
    }

    private static String path(SessionContext session) {
        return session == null ? "http" : "gate/" + session.getGateNodeId() + "/" + session.getSessionId();
    }
}
