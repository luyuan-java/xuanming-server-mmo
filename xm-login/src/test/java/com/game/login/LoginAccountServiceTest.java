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
}
