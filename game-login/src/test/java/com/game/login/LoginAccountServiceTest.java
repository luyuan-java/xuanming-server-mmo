package com.game.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.login.account.AccountLogin;
import com.game.login.auth.DevPasswordRule;
import com.game.login.auth.LoginAuthenticator;
import com.game.login.handler.RefreshTokenHandler;
import com.game.login.testing.InMemoryLoginDevices;
import com.game.login.testing.InMemoryLoginTokens;
import com.game.player.store.PlayerStore;
import com.game.proto.login.LoginRequest;
import com.game.proto.login.LoginResponse;
import com.game.proto.login.RefreshTokenRequest;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

/** HTTP 登录 / 刷新的 Dubbo 提供方：签令牌、不碰设备数、与 TCP 共用流程；工作队列满时 future 异常完成。 */
class LoginAccountServiceTest {

    private static final String SECRET = "unit-test-shared-secret";

    private final PlayerStore store = mock(PlayerStore.class);
    private final InMemoryLoginTokens tokens = new InMemoryLoginTokens();
    private final InMemoryLoginDevices devices = new InMemoryLoginDevices(3);
    private final AccountLogin accountLogin = new AccountLogin(
            LoginAuthenticator.withDevPassword(new DevPasswordRule(SECRET, List.of("robot_")), tokens), store, tokens,
            devices);
    private final LoginAccountService service =
            new LoginAccountService(accountLogin, new RefreshTokenHandler(tokens), Runnable::run);

    @Test
    void HTTP口令登录_签令牌_不登记设备_不限设备数() {
        when(store.listPlayers("robot_0001")).thenReturn(List.of());
        for (int i = 0; i < 5; i++) {
            LoginResponse response = service.login(LoginRequest.newBuilder().setAccount("robot_0001").setPassword(SECRET)
                    .build()).join();
            assertThat(response.hasErrorMessage()).isFalse();
            assertThat(response.getAccessToken()).isNotEmpty();
        }
        assertThat(devices.sessions).isEmpty();
        assertThat(tokens.issued).isEqualTo(5);
    }

    @Test
    void 错口令回2000_刷新走同一个轮换() {
        assertThat(service.login(LoginRequest.newBuilder().setAccount("robot_0001").setPassword("bad").build()).join()
                .getErrorMessage().getId()).isEqualTo(2000);
        String refresh = tokens.issue("robot_0001", "password", "").refreshToken();
        assertThat(service.refreshToken(RefreshTokenRequest.newBuilder().setRefreshToken(refresh).build()).join()
                .hasErrorMessage()).isFalse();
        assertThat(service.refreshToken(RefreshTokenRequest.newBuilder().setRefreshToken(refresh).build()).join()
                .getErrorMessage().getId()).isEqualTo(2000);
    }

    @Test
    void 工作队列满时future异常完成() {
        LoginAccountService full = new LoginAccountService(accountLogin, new RefreshTokenHandler(tokens), task -> {
            throw new RejectedExecutionException("满");
        });
        assertThatThrownBy(() -> full.login(LoginRequest.getDefaultInstance()).join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(RejectedExecutionException.class);
    }

    @Test
    void 按角色号回查登录渠道与openid供内容安全平台审核路由() {
        when(store.findPlayerChannelIdentity(8801L))
                .thenReturn(java.util.Optional.of(new com.game.player.store.AccountChannelIdentity(
                        "wx_u1", "wechat", "openid_real_8801", 1000L)));
        LoginAccountService full = new LoginAccountService(accountLogin, new RefreshTokenHandler(tokens),
                tokens, store, null, null, Runnable::run);
        var resp = full.getPlayerChannelIdentity(
                com.game.proto.data_service.GetPlayerChannelIdentityRequest.newBuilder().setPlayerId(8801L).build()).join();
        assertThat(resp.getFound()).isTrue();
        assertThat(resp.getAccount()).isEqualTo("wx_u1");
        assertThat(resp.getChannel()).isEqualTo("wechat");
        assertThat(resp.getOpenId()).isEqualTo("openid_real_8801");

        // 建角阶段 player_id=0 按账号名查询最近登录渠道身份
        when(store.findAccountChannelIdentity("wx_u_create"))
                .thenReturn(java.util.Optional.of(new com.game.player.store.AccountChannelIdentity(
                        "wx_u_create", "wechat_minigame", "openid_mg_create", 2000L)));
        var byAccount = full.getPlayerChannelIdentity(
                com.game.proto.data_service.GetPlayerChannelIdentityRequest.newBuilder().setAccount("wx_u_create").build()).join();
        assertThat(byAccount.getFound()).isTrue();
        assertThat(byAccount.getChannel()).isEqualTo("wechat_minigame");
        assertThat(byAccount.getOpenId()).isEqualTo("openid_mg_create");
    }

    @Test
    void 校验access_token区分有效_拒绝空串与存储故障异常() {
        LoginAccountService full = new LoginAccountService(accountLogin, new RefreshTokenHandler(tokens),
                tokens, store, null, null, Runnable::run);
        String access = tokens.issue("robot_0001", "password", "").accessToken();
        assertThat(full.resolveAccessTokenAccount(access).join()).isEqualTo("robot_0001");
        assertThat(full.resolveAccessTokenAccount("bad-token").join()).isEmpty();
        assertThat(full.resolveAccessTokenAccount("").join()).isEmpty();

        com.game.login.token.LoginTokens broken = mock(com.game.login.token.LoginTokens.class);
        when(broken.validateAccess("any")).thenThrow(new IllegalStateException("redis timeout"));
        LoginAccountService brokenService = new LoginAccountService(accountLogin, new RefreshTokenHandler(tokens),
                broken, store, null, null, Runnable::run);
        assertThatThrownBy(() -> brokenService.resolveAccessTokenAccount("any").join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(IllegalStateException.class);
    }
}
