package com.game.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.api.proto.SessionContext;
import com.game.common.id.Snowflake;
import com.game.discovery.zone.ZoneMergeFence;
import com.game.login.account.AccountLogin;
import com.game.login.auth.LoginAuthenticator;
import com.game.login.character.CharacterRules;
import com.game.login.character.PlayerIdGenerator;
import com.game.login.character.RoleNameRules;
import com.game.login.handler.CreatePlayerHandler;
import com.game.login.metrics.LoginMetrics;
import com.game.login.testing.InMemoryLoginDevices;
import com.game.login.testing.InMemoryLoginTokens;
import com.game.player.store.PlayerRow;
import com.game.player.store.PlayerStore;
import com.game.player.store.PlayerStore.CreateOutcome;
import com.game.player.store.PlayerStore.CreateStatus;
import com.game.proto.login.CreatePlayerRequest;
import com.game.proto.login.CreatePlayerResponse;
import com.game.table.LoginErrorTip;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 建角 bean 的装配（X16）：归属区来自会话、不来自 login 进程的 {@code xm.zone-id}；合服围栏是注入进来的那个 bean
 * （7.3 换实现时只换 bean，不动处理器），批次 7.3 之前这个 bean 是恒放行的 {@link ZoneMergeFence#OPEN}。
 * 只调装配方法、不起容器。
 */
class LoginHomeZoneWiringTest {

    private static final String ACCOUNT = "robot_0001";

    private final LoginConfiguration configuration = new LoginConfiguration();
    private final PlayerStore store = mock(PlayerStore.class);
    private final CharacterRules rules = mock(CharacterRules.class);
    private final InMemoryLoginTokens tokens = new InMemoryLoginTokens();
    private final InMemoryLoginDevices devices = new InMemoryLoginDevices(3);
    private final AccountLogin accountLogin =
            new AccountLogin(LoginAuthenticator.passwordDisabled(tokens), store, tokens, devices);
    private final PlayerIdGenerator ids = new PlayerIdGenerator(new Snowflake(3), () -> true);
    private final LoginProperties props = new LoginProperties(null, null, null, null, null, null, null, null, null, null, null);

    private static SessionContext sessionIn(int zoneId) {
        return SessionContext.newBuilder().setGateNodeId(7).setGateInstanceId("gate-z" + zoneId)
                .setSessionId((7 << 17) | 5).setZoneId(zoneId).setAccount(ACCOUNT).build();
    }

    private CreatePlayerHandler handlerWith(ZoneMergeFence fence) {
        when(rules.defaultClassId()).thenReturn(1);
        when(rules.roleNameRules()).thenReturn(new RoleNameRules(2, 12, "道友", 6, 5));
        when(store.listPlayers(ACCOUNT)).thenReturn(List.of());
        when(store.createPlayerWithinCap(any(), anyInt(), anyList())).thenAnswer(inv -> {
            PlayerRow row = inv.getArgument(0);
            row.setName(inv.<List<String>>getArgument(2).get(0));
            return new CreateOutcome(CreateStatus.CREATED, List.of());
        });
        return configuration.createPlayerHandler(store, rules, ids, props, new LoginMetrics(new SimpleMeterRegistry()),
                accountLogin, fence);
    }

    private static CreatePlayerResponse create(CreatePlayerHandler handler, SessionContext session) throws Exception {
        var reply = handler.handle(session, CreatePlayerRequest.getDefaultInstance()).get(5, TimeUnit.SECONDS);
        assertThat(reply.body()).isPresent();
        return CreatePlayerResponse.parseFrom(reply.body().get().toByteString());
    }

    @Test
    void 合服围栏的bean在批次7点3之前是恒放行的OPEN() {
        assertThat(configuration.zoneMergeFence()).isSameAs(ZoneMergeFence.OPEN);
    }

    @Test
    void 建角bean的归属区取会话zone_装配里没有进程zone() throws Exception {
        CreatePlayerHandler handler = handlerWith(configuration.zoneMergeFence());

        CreatePlayerResponse fromZone2 = create(handler, sessionIn(2));
        CreatePlayerResponse fromZone3 = create(handler, sessionIn(3));

        assertThat(fromZone2.hasErrorMessage()).isFalse();
        assertThat(fromZone3.hasErrorMessage()).isFalse();
        ArgumentCaptor<PlayerRow> inserted = ArgumentCaptor.forClass(PlayerRow.class);
        verify(store, org.mockito.Mockito.times(2)).createPlayerWithinCap(inserted.capture(), anyInt(), anyList());
        assertThat(inserted.getAllValues()).extracting(PlayerRow::getZoneId).containsExactly(2, 3);
        assertThat(fromZone2.getPlayers(0).getPlayer().getZoneId()).isEqualTo(2);
        assertThat(fromZone3.getPlayers(0).getPlayer().getZoneId()).isEqualTo(3);
        // 建角前的设备数续期照旧接着（accountLogin::renewDevice）：两个区的 gate 各登记了一个设备。
        assertThat(devices.of(ACCOUNT)).hasSize(2);
    }

    @Test
    void 建角bean用的是注入的围栏_换成封锁zone2的实现后只有zone2被拒() throws Exception {
        List<Integer> asked = new ArrayList<>();
        ZoneMergeFence zone2Merging = new ZoneMergeFence() {
            @Override
            public boolean inProgress(int zoneId) {
                asked.add(zoneId);
                return zoneId == 2;
            }

            @Override
            public CompletionStage<Boolean> inProgressAsync(int zoneId) {
                return CompletableFuture.failedStage(new AssertionError("建角应同步判围栏"));
            }
        };
        CreatePlayerHandler handler = handlerWith(zone2Merging);

        CreatePlayerResponse refused = create(handler, sessionIn(2));
        assertThat(refused.getErrorMessage().getId()).isEqualTo(LoginErrorTip.login_error.kLoginDataSerializeFailed_VALUE);
        assertThat(refused.getPlayersList()).isEmpty();
        verify(store, never()).createPlayerWithinCap(any(), anyInt(), anyList());

        CreatePlayerResponse allowed = create(handler, sessionIn(3));
        assertThat(allowed.hasErrorMessage()).isFalse();
        assertThat(allowed.getPlayers(0).getPlayer().getZoneId()).isEqualTo(3);
        assertThat(asked).containsExactly(2, 3);
    }
}
