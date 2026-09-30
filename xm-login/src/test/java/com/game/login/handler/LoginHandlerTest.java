package com.game.login.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.api.proto.SessionContext;
import com.game.api.proto.SessionDirective;
import com.game.login.auth.DevPasswordRule;
import com.game.login.auth.LoginAuthenticator;
import com.game.login.dispatch.HandlerReply;
import com.game.player.store.PlayerRow;
import com.game.player.store.PlayerStore;
import com.game.proto.AccountSimplePlayer;
import com.game.proto.login.LoginRequest;
import com.game.proto.login.LoginResponse;
import com.game.table.LoginErrorTip;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LoginHandlerTest {

    static final String SECRET = "unit-test-shared-secret";
    static final SessionContext SESSION = SessionContext.newBuilder()
            .setGateNodeId(1).setSessionId((1 << 17) | 1).setZoneId(1).build();

    private final PlayerStore store = mock(PlayerStore.class);
    private final LoginHandler handler = new LoginHandler(
            LoginAuthenticator.withDevPassword(new DevPasswordRule(SECRET, List.of("robot_", "dev_"))), store);

    static LoginRequest passwordLogin(String account) {
        return LoginRequest.newBuilder().setAccount(account).setPassword(SECRET).build();
    }

    static PlayerRow row(long playerId, String account, String name) {
        PlayerRow row = new PlayerRow();
        row.setPlayerId(playerId);
        row.setAccount(account);
        row.setZoneId(1);
        row.setName(name);
        row.setClassId(1);
        row.setGender(1);
        return row;
    }

    private LoginResponse response(HandlerReply reply) throws Exception {
        assertThat(reply.body()).isPresent();
        return LoginResponse.parseFrom(reply.body().get().toByteString());
    }

    @Test
    void 新账号登录成功_空角色列表_绑定账号() throws Exception {
        when(store.listPlayers("robot_0001")).thenReturn(List.of());

        HandlerReply reply = handler.handle(SESSION, passwordLogin("robot_0001")).join();

        LoginResponse response = response(reply);
        assertThat(response.hasErrorMessage()).isFalse();
        assertThat(response.getPlayersList()).isEmpty();
        // 本批不签发 token。
        assertThat(response.getAccessToken()).isEmpty();
        assertThat(response.getAccessTokenExpire()).isZero();
        assertThat(reply.directives()).containsExactly(SessionDirective.newBuilder()
                .setBindAccount(com.game.api.proto.BindAccount.newBuilder().setAccount("robot_0001")).build());
        verify(store).ensureAccount("robot_0001");
    }

    @Test
    void 已有角色按存储顺序返回全部字段() throws Exception {
        PlayerRow first = row(11, "robot_0001", "道友aaaaaa");
        first.setAppearanceId("01_ice_sword_girl");
        first.setGender(2);
        PlayerRow second = row(22, "robot_0001", "张三");
        second.setClassId(3);
        when(store.listPlayers("robot_0001")).thenReturn(List.of(first, second));

        LoginResponse response = response(handler.handle(SESSION, passwordLogin("robot_0001")).join());

        assertThat(response.hasErrorMessage()).isFalse();
        assertThat(response.getPlayersList()).extracting(w -> w.getPlayer().getPlayerId()).containsExactly(11L, 22L);
        assertThat(response.getPlayers(0).getPlayer()).isEqualTo(AccountSimplePlayer.newBuilder()
                .setPlayerId(11).setClassId(1).setGender(2).setZoneId(1)
                .setName("道友aaaaaa").setAppearanceId("01_ice_sword_girl").build());
        assertThat(response.getPlayers(1).getPlayer().getClassId()).isEqualTo(3);
    }

    @Test
    void 认证失败回2000_不碰存储_不绑定() throws Exception {
        HandlerReply reply = handler.handle(SESSION,
                LoginRequest.newBuilder().setAccount("robot_0001").setPassword("bad").build()).join();

        LoginResponse response = response(reply);
        assertThat(response.getErrorMessage().getId()).isEqualTo(LoginErrorTip.login_error.kLoginAccountNotFound_VALUE);
        assertThat(response.getPlayersList()).isEmpty();
        assertThat(reply.directives()).isEmpty();
        verify(store, never()).ensureAccount(anyString());
    }

    @Test
    void 不支持的认证类型回2000() throws Exception {
        LoginRequest request = passwordLogin("robot_0001").toBuilder()
                .setAuthType("access_token").setAuthToken("x").build();
        LoginResponse response = response(handler.handle(SESSION, request).join());
        assertThat(response.getErrorMessage().getId()).isEqualTo(LoginErrorTip.login_error.kLoginAccountNotFound_VALUE);
    }

    @Test
    void 同账号登录在途时后到者回2005_结束后释放() throws Exception {
        AtomicReference<HandlerReply> nested = new AtomicReference<>();
        doAnswer(invocation -> {
            nested.set(handler.handle(SESSION, passwordLogin("robot_0001")).join());
            return null;
        }).when(store).ensureAccount("robot_0001");
        when(store.listPlayers("robot_0001")).thenReturn(List.of());

        LoginResponse outer = response(handler.handle(SESSION, passwordLogin("robot_0001")).join());

        assertThat(outer.hasErrorMessage()).isFalse();
        assertThat(response(nested.get()).getErrorMessage().getId())
                .isEqualTo(LoginErrorTip.login_error.kLoginInProgress_VALUE);

        doNothing().when(store).ensureAccount("robot_0001");
        assertThat(response(handler.handle(SESSION, passwordLogin("robot_0001")).join()).hasErrorMessage()).isFalse();
    }

    @Test
    void 存储故障抛出由分发器处理_在途闸门仍释放() throws Exception {
        doThrow(new IllegalStateException("db down")).when(store).ensureAccount("robot_0001");
        assertThatThrownBy(() -> handler.handle(SESSION, passwordLogin("robot_0001")))
                .isInstanceOf(IllegalStateException.class);

        doNothing().when(store).ensureAccount("robot_0001");
        when(store.listPlayers("robot_0001")).thenReturn(List.of());
        assertThat(response(handler.handle(SESSION, passwordLogin("robot_0001")).join()).hasErrorMessage()).isFalse();
    }
}
