package com.game.match.global;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.api.proto.TeamTicketsRequest;
import com.game.common.deadline.Deadline;
import com.game.match.MatchProperties;
import com.game.match.global.pb.GlobalDirectStartOriginal;
import com.game.match.global.pb.GlobalFormationRow;
import com.game.match.global.pb.GlobalTicketRow;
import com.game.proto.team.StartTeamMatchRequest;
import com.game.proto.team.TeamLaunchMember;
import com.game.proto.team.TeamLaunchOriginal;
import com.game.proto.team.TeamOriginalRole;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 整队开战的 match 一侧（{@link GlobalMatchService#reserveTeam}）：每个事务都拿调用方（team 那一跳）的截止，不再各自新开 3 s 预算——
 * 否则 team 过了每跳超时回客户端、gate 放行同一会话的下一条之后，准入与开局仍会提交（mmorpg client-request-ordering.md §6.1）。
 * 存储换成替身，只钉住截止的传递；「截止已过不开事务、不提交」在真库上由 {@code GlobalMatchStoreIntegrationTest} 钉住。
 */
class GlobalTeamLaunchDeadlineTest {

    private static final long TEAM = 77;
    private static final long LEADER = 701;
    private static final long MATE = 702;
    private static final int CONFIG = 1;

    private final GlobalMatchStore store = mock(GlobalMatchStore.class);

    private static TeamOriginalRole role(long player) {
        return TeamOriginalRole.newBuilder().setPlayerId(player).setOwnerAccount("account-" + player).setOwnerRevision(3).build();
    }

    private static TeamLaunchOriginal launch() {
        StartTeamMatchRequest start = StartTeamMatchRequest.newBuilder().setExpectedTeamId(TEAM).setBattleConfigId(CONFIG)
                .setRequestId(UUID.randomUUID().toString()).build();
        return TeamLaunchOriginal.newBuilder().setTeamId(TEAM).setTeamVersion(1).setLockToken("lock-token").setRequest(start)
                .setLockTtlSeconds(30).setEnqueuedMs(1_000).setNotBeforeMs(1_000).setActor(role(LEADER))
                .addMembers(TeamLaunchMember.newBuilder().setOriginal(role(LEADER)).setRosterOrder(1).setTicket("ticket-leader"))
                .addMembers(TeamLaunchMember.newBuilder().setOriginal(role(MATE)).setRosterOrder(2).setTicket("ticket-mate"))
                .putZones(LEADER, 1).putZones(MATE, 1).build();
    }

    private static TeamTicketsRequest request(TeamLaunchOriginal launch) {
        return TeamTicketsRequest.newBuilder().setBattleConfigId(CONFIG).setTeamId(TEAM).addRoster(LEADER).addRoster(MATE)
                .putZones(LEADER, 1).putZones(MATE, 1).putTicketIds(LEADER, "ticket-leader").putTicketIds(MATE, "ticket-mate")
                .setRequestId(launch.getRequest().getRequestId()).setInitiatorPlayerId(LEADER).setClientRequest(launch.getRequest().toByteString())
                .setOwnerAccount(role(LEADER).getOwnerAccount()).setOwnerRevision(3).setLaunchOriginal(launch.toByteString()).build();
    }

    private GlobalMatchService service() {
        return new GlobalMatchService(store, GlobalRegionPolicyTest.CONFIG,
                new MatchProperties(null, null, null, null, null, null, null, null, null, null, null, null, null),
                null, null, null, null, null, null, null, null);
    }

    @Test
    void 已准备过的整队_重放_准备_收尾判定与开局都拿调用方截止_不用独立预算的旧重载() {
        TeamLaunchOriginal launch = launch();
        TeamTicketsRequest request = request(launch);
        var key = GlobalMatchStore.directUuidKey(1, LEADER, request.getRequestId()).toBuilder()
                .setOwnerAccount(request.getOwnerAccount()).setOwnerRevision(request.getOwnerRevision()).build();
        List<GlobalTicketRow> tickets = List.of(GlobalTicketRow.newBuilder().setPlayerId(LEADER).setTicket("ticket-leader").build(),
                GlobalTicketRow.newBuilder().setPlayerId(MATE).setTicket("ticket-mate").build());
        GlobalFormationRow formation = GlobalFormationRow.newBuilder().setBattleId(9).build();
        var prepared = key.toBuilder().setAdmissionPrepared(true).setStartOriginal(GlobalDirectStartOriginal.newBuilder()
                .setTeamLaunchOriginal(launch.toByteString()).setFormation(formation).addAllTickets(tickets)).build();
        when(store.direct(key)).thenReturn(prepared);
        when(store.prepareTeam(eq(key), eq(launch.toByteString()), eq(tickets), eq(formation), any())).thenReturn(prepared);
        when(store.reserveTeam(eq(key), eq(request.getClientRequest()), eq(launch.toByteString()), eq(tickets), eq(formation), any()))
                .thenReturn(formation);
        Deadline deadline = Deadline.after(2_000);

        try (GlobalMatchService service = service()) {
            assertEquals(9, service.reserveTeam(request, deadline).getBattleId());
        }

        verify(store).teamReplay(eq(key), eq(request.getClientRequest()), eq(launch.toByteString()), same(deadline));
        verify(store).prepareTeam(eq(key), eq(launch.toByteString()), eq(tickets), eq(formation), same(deadline));
        verify(store).closeObsoleteTeam(eq(prepared), same(deadline));
        verify(store).reserveTeam(eq(key), eq(request.getClientRequest()), eq(launch.toByteString()), eq(tickets), eq(formation), same(deadline));
        verify(store, never()).teamReplay(any(), any(), any());
        verify(store, never()).prepareTeam(any(), any(), any(), any());
        verify(store, never()).reserveTeam(any(), any(), any(), any(), any());
    }
}
