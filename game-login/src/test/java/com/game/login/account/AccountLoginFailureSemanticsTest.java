package com.game.login.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.api.proto.SessionContext;
import com.game.login.auth.AuthProviderUnavailableException;
import com.game.login.auth.LoginAuthenticator;
import com.game.login.testing.InMemoryLoginDevices;
import com.game.login.token.LoginTokens;
import com.game.player.store.PlayerStore;
import com.game.proto.login.LoginRequest;
import com.game.proto.login.LoginResponse;
import com.game.table.LoginErrorTip;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 登录失败语义（同 mmorpg {@code login_error_semantics_test.go}）：
 * <ul>
 *   <li>三方渠道 / 本服务渠道配置故障回故障码 kLoginAuthProviderUnavailable，不并进凭证无效的 2000；</li>
 *   <li>HTTP 路径签令牌失败回 kLoginTokenIssueFailed，不再「成功但没有令牌」（三方 code 已被消费）；</li>
 *   <li>gate 路径签令牌失败仍不致命（会话随后绑定，令牌只用于静默重连）。</li>
 * </ul>
 */
class AccountLoginFailureSemanticsTest {

    private final PlayerStore store = mock(PlayerStore.class);
    private final LoginTokens tokens = mock(LoginTokens.class);
    private final InMemoryLoginDevices devices = new InMemoryLoginDevices(3);

    private AccountLogin login(Map<String, com.game.login.auth.ExternalAuthProvider> external) {
        return new AccountLogin(new LoginAuthenticator(null, tokens, external), store, tokens, devices);
    }

    private static LoginRequest wechat(String code) {
        return LoginRequest.newBuilder().setAuthType("wechat").setAuthToken(code).build();
    }

    @Test
    void 渠道故障回故障码_不建账号不签令牌() {
        AccountLogin accountLogin = login(Map.of("wechat", code -> {
            throw new AuthProviderUnavailableException("微信认证渠道侧错误 errcode=40125");
        }));
        AccountLogin.Outcome outcome = accountLogin.loginWithoutSession(wechat("code"));
        assertThat(outcome.succeeded()).isFalse();
        assertThat(outcome.response().getErrorMessage().getId())
                .isEqualTo(LoginErrorTip.login_error.kLoginAuthProviderUnavailable_VALUE);
        verify(store, never()).ensureAccount(anyString());
        verify(tokens, never()).issue(anyString(), anyString(), anyString());
    }

    @Test
    void 凭证无效仍回2000() {
        AccountLogin accountLogin = login(Map.of("wechat", code -> Optional.empty()));
        assertThat(accountLogin.loginWithoutSession(wechat("expired")).response().getErrorMessage().getId())
                .isEqualTo(LoginErrorTip.login_error.kLoginAccountNotFound_VALUE);
    }

    @Test
    void HTTP路径签令牌失败回故障码_不冒充成功() {
        when(store.listPlayers("wx_u1")).thenReturn(List.of());
        when(tokens.issue(any(), any(), any())).thenThrow(new IllegalStateException("redis down"));
        AccountLogin accountLogin = login(Map.of("wechat", code -> Optional.of("wx_u1")));
        AccountLogin.Outcome outcome = accountLogin.loginWithoutSession(wechat("code"));
        assertThat(outcome.succeeded()).isFalse();
        LoginResponse response = outcome.response();
        assertThat(response.getErrorMessage().getId()).isEqualTo(LoginErrorTip.login_error.kLoginTokenIssueFailed_VALUE);
        assertThat(response.getAccessToken()).isEmpty();
        assertThat(response.getPlayersList()).isEmpty();
    }

    @Test
    void gate路径签令牌失败仍不致命() {
        when(store.listPlayers("wx_u1")).thenReturn(List.of());
        when(tokens.issue(any(), any(), any())).thenThrow(new IllegalStateException("redis down"));
        AccountLogin accountLogin = login(Map.of("wechat", code -> Optional.of("wx_u1")));
        SessionContext session = SessionContext.newBuilder().setGateInstanceId("gate-1").setSessionId(7).build();
        AccountLogin.Outcome outcome = accountLogin.loginOnSession(session, wechat("code"));
        assertThat(outcome.succeeded()).isTrue();
        assertThat(outcome.response().hasErrorMessage()).isFalse();
        assertThat(outcome.response().getAccessToken()).isEmpty();
    }
}
