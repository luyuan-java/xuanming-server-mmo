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
import com.game.login.account.AccountLogin;
import com.game.login.dispatch.HandlerReply;
import com.game.login.testing.InMemoryLoginDevices;
import com.game.login.testing.InMemoryLoginTokens;
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
    static final SessionContext SESSION = session(1);

    private final PlayerStore store = mock(PlayerStore.class);
    private final InMemoryLoginTokens tokens = new InMemoryLoginTokens();
    private final InMemoryLoginDevices devices = new InMemoryLoginDevices(3);
    private final LoginHandler handler = new LoginHandler(new AccountLogin(
            LoginAuthenticator.withDevPassword(new DevPasswordRule(SECRET, List.of("robot_", "dev_")), tokens), store,
            tokens, devices));

    static SessionContext session(int sessionId) {
        return SessionContext.newBuilder().setGateNodeId(1).setGateInstanceId("gate-a").setSessionId((1 << 17) | sessionId)
                .setZoneId(1).build();
    }

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
        // 口令登录签一对令牌（字段 3–6）
        assertThat(response.getAccessToken()).isEqualTo("access-1");
        assertThat(response.getRefreshToken()).isEqualTo("refresh-1");
        assertThat(response.getAccessTokenExpire()).isEqualTo(1000 + 7200);
        assertThat(response.getRefreshTokenExpire()).isEqualTo(1000 + 720 * 3600);
        assertThat(tokens.access.get("access-1").authType()).isEqualTo("password");
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
                .setAuthType("satoken").setAuthToken("x").build();
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

    // ------------------------------------------------------------------ 令牌

    @Test
    void access_token登录_账号取令牌里的_不签新令牌_照常绑定() throws Exception {
        when(store.listPlayers("robot_0001")).thenReturn(List.of());
        String access = tokens.issue("robot_0001", "password", "").accessToken();
        LoginRequest request = LoginRequest.newBuilder().setAuthType("access_token").setAuthToken(access)
                .setAccount("robot_9999").build();

        HandlerReply reply = handler.handle(SESSION, request).join();

        LoginResponse response = response(reply);
        assertThat(response.hasErrorMessage()).isFalse();
        assertThat(response.getAccessToken()).isEmpty();
        assertThat(response.getRefreshTokenExpire()).isZero();
        assertThat(tokens.issued).isEqualTo(1);
        assertThat(reply.directives().get(0).getBindAccount().getAccount()).isEqualTo("robot_0001");
    }

    @Test
    void 签令牌失败不致命_登录照常成功只是没有令牌() throws Exception {
        when(store.listPlayers("robot_0001")).thenReturn(List.of());
        tokens.broken = true;
        HandlerReply reply = handler.handle(SESSION, passwordLogin("robot_0001")).join();
        LoginResponse response = response(reply);
        assertThat(response.hasErrorMessage()).isFalse();
        assertThat(response.getAccessToken()).isEmpty();
        assertThat(reply.directives()).hasSize(1);
    }

    // ------------------------------------------------------------------ 设备数上限

    @Test
    void 设备数_窗口内第4个连接回2024且不登记_同一会话重登不占名额() throws Exception {
        when(store.listPlayers("robot_0001")).thenReturn(List.of());
        for (int s = 1; s <= 3; s++) {
            assertThat(response(handler.handle(session(s), passwordLogin("robot_0001")).join()).hasErrorMessage()).isFalse();
        }
        assertThat(response(handler.handle(session(1), passwordLogin("robot_0001")).join()).hasErrorMessage())
                .as("同一会话再登录").isFalse();

        HandlerReply fourth = handler.handle(session(4), passwordLogin("robot_0001")).join();

        assertThat(response(fourth).getErrorMessage().getId()).isEqualTo(LoginErrorTip.login_error.kTooManyDevices_VALUE);
        assertThat(fourth.directives()).isEmpty();
        assertThat(devices.of("robot_0001")).hasSize(3);
        assertThat(tokens.issued).as("被拒的不签令牌").isEqualTo(4);
    }

    @Test
    void 同一会话换账号登录成功_才从旧账号名单里注销_记下新归属() throws Exception {
        when(store.listPlayers(anyString())).thenReturn(List.of());
        handler.handle(session(1), passwordLogin("robot_0001")).join();
        SessionContext bound = session(1).toBuilder().setAccount("robot_0001").build();

        assertThat(response(handler.handle(bound, passwordLogin("robot_0002")).join()).hasErrorMessage()).isFalse();

        String key = "gate-a/" + ((1 << 17) | 1);
        assertThat(devices.of("robot_0001")).isEmpty();
        assertThat(devices.of("robot_0002")).containsExactly(key);
        assertThat(devices.boundTo).containsEntry(key, "robot_0002");
    }

    @Test
    void 换号登录被拒_旧账号的登记留着_gate仍绑旧账号() throws Exception {
        when(store.listPlayers(anyString())).thenReturn(List.of());
        handler.handle(session(1), passwordLogin("robot_0001")).join();
        for (int s = 2; s <= 4; s++) {
            handler.handle(session(s), passwordLogin("robot_0002")).join();
        }
        SessionContext bound = session(1).toBuilder().setAccount("robot_0001").build();

        HandlerReply refused = handler.handle(bound, passwordLogin("robot_0002")).join();

        assertThat(response(refused).getErrorMessage().getId()).isEqualTo(LoginErrorTip.login_error.kTooManyDevices_VALUE);
        assertThat(devices.of("robot_0001")).as("被拒的换号不改 gate 的绑定，旧账号的登记必须留着")
                .containsExactly("gate-a/" + ((1 << 17) | 1));
    }

    @Test
    void 同账号重登时取建账号失败_不撤销原有登记() {
        when(store.listPlayers("robot_0001")).thenReturn(List.of());
        handler.handle(session(1), passwordLogin("robot_0001")).join();
        doThrow(new IllegalStateException("db down")).when(store).ensureAccount("robot_0001");
        SessionContext bound = session(1).toBuilder().setAccount("robot_0001").build();

        assertThatThrownBy(() -> handler.handle(bound, passwordLogin("robot_0001")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(devices.of("robot_0001")).hasSize(1);
    }

    @Test
    void 续期_离开窗口后再行动重新计入_满了回2024_存储出错回2023_没绑账号不做事() {
        AccountLogin flow = new AccountLogin(LoginAuthenticator.passwordDisabled(tokens), store, tokens, devices);
        SessionContext bound = session(1).toBuilder().setAccount("robot_0001").build();
        assertThat(flow.renewDevice(bound)).isNull();
        assertThat(devices.of("robot_0001")).hasSize(1);
        flow.leaveDeviceWindow(bound);
        assertThat(devices.of("robot_0001")).isEmpty();
        assertThat(flow.renewDevice(bound)).as("进游戏失败回到大厅后再进游戏：重新计入").isNull();
        for (int s = 2; s <= 3; s++) {
            assertThat(flow.renewDevice(session(s).toBuilder().setAccount("robot_0001").build())).isNull();
        }
        assertThat(flow.renewDevice(session(4).toBuilder().setAccount("robot_0001").build()))
                .isEqualTo(LoginErrorTip.login_error.kTooManyDevices_VALUE);
        assertThat(flow.renewDevice(session(5))).isNull();
        devices.broken = true;
        assertThat(flow.renewDevice(bound)).isEqualTo(LoginErrorTip.login_error.kLoginRedisSetFailed_VALUE);
    }

    @Test
    void 设备登记出错回2023_不绑定() throws Exception {
        devices.broken = true;
        HandlerReply reply = handler.handle(SESSION, passwordLogin("robot_0001")).join();
        assertThat(response(reply).getErrorMessage().getId())
                .isEqualTo(LoginErrorTip.login_error.kLoginRedisSetFailed_VALUE);
        assertThat(reply.directives()).isEmpty();
        verify(store, never()).ensureAccount(anyString());
    }

    @Test
    void 取建账号失败时注销刚登记的设备() {
        doThrow(new IllegalStateException("db down")).when(store).ensureAccount("robot_0001");
        assertThatThrownBy(() -> handler.handle(SESSION, passwordLogin("robot_0001")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(devices.of("robot_0001")).isEmpty();
    }
}
