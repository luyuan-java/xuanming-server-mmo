package com.game.login.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.api.proto.SessionContext;
import com.game.discovery.zone.ZoneMergeFence;
import com.game.login.character.CharacterRules;
import com.game.login.character.PlayerIdGenerator;
import com.game.login.character.RoleNameRules;
import com.game.login.dispatch.InFlightKeys;
import com.game.login.metrics.LoginMetrics;
import com.game.player.store.PlayerRow;
import com.game.player.store.PlayerStore;
import com.game.proto.content_guard.CheckTextResponse;
import com.game.proto.content_guard.TextVerdict;
import com.game.proto.login.CreatePlayerRequest;
import com.game.proto.login.CreatePlayerResponse;
import com.game.table.LoginErrorTip;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 建角角色名接入内容安全服务回归：PASS 放行、REJECT/MASKED 回 2034、不可用/异常 fail-closed 回 2050（不烧号）。 */
class CreatePlayerContentGuardTest {

    private static SessionContext session() {
        return SessionContext.newBuilder().setSessionId(11).setZoneId(1).setAccount("acc_cg").build();
    }

    private static CreatePlayerRequest req(String name) {
        return CreatePlayerRequest.newBuilder().setName(name).setClassId(1).setGender(1).setAppearanceId("").build();
    }

    @Test
    void 建角名经内容安全判定_放行与拒绝与不可用均不误烧号() {
        PlayerStore store = mock(PlayerStore.class);
        when(store.listPlayers("acc_cg")).thenReturn(List.of());
        when(store.createPlayerWithinCap(any(PlayerRow.class), anyInt(), anyList(), any(Duration.class)))
                .thenReturn(new PlayerStore.CreateOutcome(PlayerStore.CreateStatus.CREATED, List.of()));
        CharacterRules rules = new CharacterRules() {
            @Override public int defaultClassId() { return 1; }
            @Override public boolean classExists(int classId) { return classId == 1; }
            @Override public RoleNameRules roleNameRules() { return new RoleNameRules(2, 12, "侠客", 4, 3); }
        };
        PlayerIdGenerator ids = mock(PlayerIdGenerator.class);
        when(ids.nextId()).thenReturn(9001L);

        AtomicReference<CreatePlayerHandler.TextGuard> current = new AtomicReference<>();
        CreatePlayerHandler handler = new CreatePlayerHandler(store, rules, ids, () -> 1, ZoneMergeFence.OPEN, 5,
                new LoginMetrics(new SimpleMeterRegistry()), s -> null, new InFlightKeys<>(),
                (acc, name) -> current.get().checkRoleName(acc, name));

        // 1. REJECT -> 2034（不烧号）
        current.set((a, n) -> CheckTextResponse.newBuilder().setVerdict(TextVerdict.TEXT_VERDICT_REJECT).build());
        CreatePlayerResponse rejected = (CreatePlayerResponse) handler.handle(session(), req("青莲剑仙")).join().body().orElseThrow();
        assertThat(rejected.getErrorMessage().getId()).isEqualTo(LoginErrorTip.login_error.kRoleNameSensitive_VALUE);

        // 2. MASKED（名字类绝不打码，fail-closed）-> 2034
        current.set((a, n) -> CheckTextResponse.newBuilder().setVerdict(TextVerdict.TEXT_VERDICT_MASKED).setMaskedText("****").build());
        CreatePlayerResponse masked = (CreatePlayerResponse) handler.handle(session(), req("青莲剑仙")).join().body().orElseThrow();
        assertThat(masked.getErrorMessage().getId()).isEqualTo(LoginErrorTip.login_error.kRoleNameSensitive_VALUE);

        // 3. null 或抛异常 -> 2050（不可用）
        current.set((a, n) -> null);
        CreatePlayerResponse nullResp = (CreatePlayerResponse) handler.handle(session(), req("青莲剑仙")).join().body().orElseThrow();
        assertThat(nullResp.getErrorMessage().getId()).isEqualTo(LoginErrorTip.login_error.kRoleNameCheckUnavailable_VALUE);

        current.set((a, n) -> { throw new RuntimeException("rpc timeout"); });
        CreatePlayerResponse errResp = (CreatePlayerResponse) handler.handle(session(), req("青莲剑仙")).join().body().orElseThrow();
        assertThat(errResp.getErrorMessage().getId()).isEqualTo(LoginErrorTip.login_error.kRoleNameCheckUnavailable_VALUE);

        verify(ids, never()).nextId();

        // 4. PASS -> 正常发号建角成功
        current.set((a, n) -> CheckTextResponse.newBuilder().setVerdict(TextVerdict.TEXT_VERDICT_PASS).build());
        CreatePlayerResponse ok = (CreatePlayerResponse) handler.handle(session(), req("青莲剑仙")).join().body().orElseThrow();
        assertThat(ok.hasErrorMessage()).isFalse();
        assertThat(ok.getPlayersCount()).isEqualTo(1);
    }
}
