package com.game.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.api.proto.AbandonedEnter;
import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.EnterScene;
import com.game.api.proto.SessionClosed;
import com.game.api.proto.SessionDirective;
import com.game.api.proto.Ack;
import com.game.api.proto.SessionContext;
import com.game.login.account.AccountLogin;
import com.game.login.auth.LoginAuthenticator;
import com.game.login.dispatch.ClientMessageDispatcher;
import com.game.login.metrics.LoginMetrics;
import com.game.login.testing.InMemoryLoginDevices;
import com.game.login.testing.InMemoryLoginTokens;
import com.game.player.store.PlayerStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LoginClientMessageServiceTest {

    private static final SessionContext SESSION = SessionContext.newBuilder().setGateNodeId(3).setSessionId(9).build();

    private final PlayerStore store = mock(PlayerStore.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final LoginMetrics metrics = new LoginMetrics(meters);
    private final InMemoryLoginTokens tokens = new InMemoryLoginTokens();
    private final InMemoryLoginDevices devices = new InMemoryLoginDevices(3);
    private final AccountLogin accountLogin = new AccountLogin(LoginAuthenticator.passwordDisabled(tokens), store, tokens,
            devices);
    private final ClientMessageDispatcher dispatcher = mock(ClientMessageDispatcher.class);
    private final LoginClientMessageService service =
            new LoginClientMessageService(dispatcher, store, accountLogin, Runnable::run, metrics);

    private static AbandonedEnter abandoned(long playerId, long epoch) {
        return AbandonedEnter.newBuilder().setSession(SESSION).setPlayerId(playerId).setOwnerEpoch(epoch).build();
    }

    private double abandonedCount(String result) {
        return meters.get("xm.login.abandoned.enters").tag("result", result).counter().count();
    }

    @Test
    void 未送达的进场_按epoch围栏释放归属() throws Exception {
        when(store.releaseOwnership(42, 7)).thenReturn(true);

        Ack ack = service.abandonEnter(abandoned(42, 7)).get(5, TimeUnit.SECONDS);

        assertThat(ack).isEqualTo(Ack.getDefaultInstance());
        verify(store).releaseOwnership(42, 7);
        assertThat(abandonedCount("released")).isEqualTo(1);
    }

    @Test
    void 围栏没过_什么也不做_计stale() throws Exception {
        when(store.releaseOwnership(42, 7)).thenReturn(false);

        service.abandonEnter(abandoned(42, 7)).get(5, TimeUnit.SECONDS);

        assertThat(abandonedCount("stale")).isEqualTo(1);
        assertThat(abandonedCount("released")).isZero();
    }

    @Test
    void 玩家号或epoch为0_忽略() throws Exception {
        service.abandonEnter(abandoned(0, 7)).get(5, TimeUnit.SECONDS);
        service.abandonEnter(abandoned(42, 0)).get(5, TimeUnit.SECONDS);
        verify(store, never()).releaseOwnership(anyLong(), anyLong());
        assertThat(abandonedCount("invalid")).isEqualTo(2);
    }

    @Test
    void 释放失败或工作队列满_仍正常应答() throws Exception {
        when(store.releaseOwnership(42, 7)).thenThrow(new IllegalStateException("db down"));
        assertThat(service.abandonEnter(abandoned(42, 7)).get(5, TimeUnit.SECONDS)).isEqualTo(Ack.getDefaultInstance());

        LoginClientMessageService full = new LoginClientMessageService(mock(ClientMessageDispatcher.class), store, accountLogin, task -> {
            throw new RejectedExecutionException("满");
        }, metrics);
        assertThat(full.abandonEnter(abandoned(42, 7)).get(5, TimeUnit.SECONDS)).isEqualTo(Ack.getDefaultInstance());

        assertThat(abandonedCount("failed")).isEqualTo(1);
        assertThat(abandonedCount("overloaded")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 设备数登记的注销

    private static final SessionContext LOGGED_IN = SessionContext.newBuilder().setGateNodeId(3).setGateInstanceId("g")
            .setSessionId(9).setAccount("robot_0001").build();

    @Test
    void 应答带进场指令即进游戏成功_注销设备_别的应答不动() {
        devices.admit("robot_0001", "g/9");
        ClientCall call = ClientCall.newBuilder().setSession(LOGGED_IN).setMessageId(48).build();
        when(dispatcher.dispatch(call)).thenReturn(CompletableFuture.completedFuture(ClientReply.getDefaultInstance()));
        service.handle(call).join();
        assertThat(devices.of("robot_0001")).containsExactly("g/9");

        ClientReply entered = ClientReply.newBuilder().addDirectives(SessionDirective.newBuilder()
                .setEnterScene(EnterScene.getDefaultInstance())).build();
        when(dispatcher.dispatch(call)).thenReturn(CompletableFuture.completedFuture(entered));
        assertThat(service.handle(call).join()).isSameAs(entered);
        assertThat(devices.of("robot_0001")).isEmpty();
    }

    @Test
    void 会话结束注销设备_没登录过的会话什么也不做_存储故障照常应答() throws Exception {
        devices.admit("robot_0001", "g/9");
        assertThat(service.sessionClosed(SessionClosed.newBuilder().setSession(LOGGED_IN).build()).get(5, TimeUnit.SECONDS))
                .isEqualTo(Ack.getDefaultInstance());
        assertThat(devices.of("robot_0001")).isEmpty();

        assertThat(service.sessionClosed(SessionClosed.newBuilder().setSession(SESSION).build()).get(5, TimeUnit.SECONDS))
                .isEqualTo(Ack.getDefaultInstance());
        devices.broken = true;
        assertThat(service.sessionClosed(SessionClosed.newBuilder().setSession(LOGGED_IN).build()).get(5, TimeUnit.SECONDS))
                .isEqualTo(Ack.getDefaultInstance());
    }

    @Test
    void 登录应答没送到gate_会话结束时gate记的账号为空_按记下的归属注销() throws Exception {
        devices.admit("robot_0001", "g/9");
        devices.bind("robot_0001", "g/9", "");
        SessionContext unbound = LOGGED_IN.toBuilder().setAccount("").build();

        service.sessionClosed(SessionClosed.newBuilder().setSession(unbound).build()).get(5, TimeUnit.SECONDS);

        assertThat(devices.of("robot_0001")).isEmpty();
        assertThat(devices.boundTo).isEmpty();
    }
}
