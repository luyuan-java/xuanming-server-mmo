package com.game.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.api.proto.SessionContext;
import com.game.common.token.GateTokenIssuer;
import com.game.discovery.proto.PlayerLocation;
import com.game.discovery.proto.PlayerPresence;
import com.game.login.account.AccountLogin;
import com.game.login.auth.LoginAuthenticator;
import com.game.login.handler.RefreshTokenHandler;
import com.game.login.session.LoginDevices;
import com.game.login.token.LoginTokens;
import com.game.player.store.PlayerRow;
import com.game.player.store.PlayerStore;
import com.game.proto.login.LoginRequest;
import com.game.table.LoginErrorTip;
import com.google.protobuf.ByteString;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 登录排队收口（2026-11-20 / 2026-11-26）：
 * <ul>
 *   <li>gate 票据账号绑定校验：摘要相符或未绑放行，换账号盗用票据拒绝 {@code kLoginUnknownError}；</li>
 *   <li>重连 / 顶号免排队跨全角色判定：在线目录 {@code zone_id}（gate_zone_id）权威优先于建角区号，
 *       闭环合服并入角色与本区角色跨区在线，且原 gate 掉线后按 {@link PlayerLocation} 重连租约兜底。</li>
 * </ul>
 */
class TicketAccountAndReconnectTest {

    @Test
    void 票据绑定账号校验_同账号与空摘要放行_跨账号盗用拒绝() {
        LoginAuthenticator auth = mock(LoginAuthenticator.class);
        when(auth.authenticate(any())).thenAnswer(inv -> {
            LoginRequest req = inv.getArgument(0);
            return Optional.of(new LoginAuthenticator.Authenticated(req.getAccount(), "password"));
        });
        PlayerStore store = mock(PlayerStore.class);
        when(store.listPlayers(any())).thenReturn(List.of());
        when(store.readAccountLifecycle(any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(Optional.empty());
        LoginTokens tokens = mock(LoginTokens.class);
        LoginDevices devices = mock(LoginDevices.class);
        when(devices.admit(any(), any())).thenReturn(LoginDevices.Admission.ADMITTED);
        when(devices.bind(any(), any(), any())).thenReturn(true);

        AccountLogin login = new AccountLogin(auth, store, tokens, devices);
        ByteString aliceDigest = GateTokenIssuer.ticketAccountDigest("alice");

        // 1. 持 alice 的票登 bob -> 在抢锁之前直接拒绝 kLoginUnknownError
        SessionContext aliceTicketSession = SessionContext.newBuilder()
                .setSessionId(101).setGateInstanceId("g-1").setZoneId(1)
                .setTicketAccountDigest(aliceDigest).build();
        AccountLogin.Outcome rejected = login.loginOnSession(aliceTicketSession,
                LoginRequest.newBuilder().setAccount("bob").build());
        assertThat(rejected.succeeded()).isFalse();
        assertThat(rejected.response().getErrorMessage().getId())
                .isEqualTo(LoginErrorTip.login_error.kLoginUnknownError_VALUE);

        // 2. 持 alice 的票登 alice -> 放行
        AccountLogin.Outcome ok = login.loginOnSession(aliceTicketSession,
                LoginRequest.newBuilder().setAccount("alice").build());
        assertThat(ok.succeeded()).isTrue();

        // 3. 空摘要（匿名/开发免验签）-> 放行
        SessionContext unbound = SessionContext.newBuilder().setSessionId(102).setGateInstanceId("g-1").setZoneId(1).build();
        assertThat(login.loginOnSession(unbound, LoginRequest.newBuilder().setAccount("bob").build()).succeeded()).isTrue();
    }

    @Test
    void 重连免排队查全部角色_gateZoneId权威优先_合服与跨区均不误判_掉线走位置租约() {
        PlayerStore store = mock(PlayerStore.class);
        PlayerRow r1 = role(1001L, 1); // 建角区 1，当前跨区到 zone 2 在线 -> 不得误免 zone 1 排队
        PlayerRow r2 = role(1002L, 3); // 建角区 3（合服并入 zone 1），当前在 zone 1 gate 在线 -> 应免 zone 1 排队
        PlayerRow r3 = role(1003L, 4); // 建角区 4，原 gate 掉线（无 presence），但 location 在 zone 5 重连租约内 -> 免 zone 5 排队
        when(store.listPlayers("multi")).thenReturn(List.of(r1, r2, r3));

        Map<Long, PlayerPresence> presences = Map.of(
                1001L, PlayerPresence.newBuilder().setPlayerId(1001L).setZoneId(2).setGateNodeId(20).build(),
                1002L, PlayerPresence.newBuilder().setPlayerId(1002L).setZoneId(1).setGateNodeId(10).build());
        Map<Long, PlayerLocation> locations = Map.of(
                1003L, PlayerLocation.newBuilder().setPlayerId(1003L).setZoneId(5).setSceneNodeId(50).build());

        LoginAccountService svc = new LoginAccountService(
                mock(AccountLogin.class), mock(RefreshTokenHandler.class), null, store, null, null,
                ids -> presences, pid -> Optional.ofNullable(locations.get(pid)), Runnable::run);

        // 1. zone 1：跳过跨区在 zone 2 的 r1，命中第 2 个合服并入角色 r2
        assertThat(svc.findReconnectingPlayer("multi", 1).join()).isEqualTo(1002L);
        // 2. zone 2：命中跨区到 zone 2 在线的 r1
        assertThat(svc.findReconnectingPlayer("multi", 2).join()).isEqualTo(1001L);
        // 3. zone 3：r2 虽然建角区是 3，但 presence.zone_id=1（权威），不得误免 zone 3 排队
        assertThat(svc.findReconnectingPlayer("multi", 3).join()).isZero();
        // 4. zone 5：r3 无在线目录条目但有 zone 5 位置重连租约 -> 免 zone 5 排队
        assertThat(svc.findReconnectingPlayer("multi", 5).join()).isEqualTo(1003L);
        // 5. zone 9：无角色命中 -> 返回 0
        assertThat(svc.findReconnectingPlayer("multi", 9).join()).isZero();
    }

    private static PlayerRow role(long playerId, int zoneId) {
        PlayerRow row = new PlayerRow();
        row.setPlayerId(playerId);
        row.setZoneId(zoneId);
        return row;
    }
}
