package com.game.match.activity;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.deadline.Deadline;
import com.game.common.id.Snowflake;
import com.game.match.gather.GatherPlan;
import com.game.match.id.MatchIds;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.precheck.DefaultMemberPrecheck;
import com.game.match.precheck.MemberPrecheck;
import com.game.match.testing.FakeGatherLauncher;
import com.game.match.testing.FakePlayerStatus;
import com.game.match.testing.InMemoryTicketStore;
import com.game.match.testing.ManualRedisClock;
import com.game.proto.BattleActivityContext;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.GuildWarBattleContext;
import com.game.proto.eBattleActivityKind;
import com.game.proto.match.ActivityBattleReject;
import com.game.proto.match.MatchMode;
import com.game.proto.match.StartGuildWarBattleRequest;
import com.game.proto.match.StartGuildWarBattleResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class GuildWarMatchStartTest {

    private static final long ATK = 9801L;
    private static final long DEF = 9802L;

    private final ManualRedisClock clock = new ManualRedisClock();
    private final InMemoryTicketStore tickets = new InMemoryTicketStore(clock);
    private final FakePlayerStatus players = new FakePlayerStatus().online(ATK, 1, 7).online(DEF, 1, 7);
    private final MemberPrecheck precheck = new DefaultMemberPrecheck(players, new StoreBackedHealing(tickets));
    private final FakeGatherLauncher gather = new FakeGatherLauncher();
    private final AtomicBoolean leaseValid = new AtomicBoolean(true);
    private final MatchIds ids = new MatchIds(new Snowflake(6), leaseValid::get, () -> false);
    private final MatchMetrics metrics = new MatchMetrics(new SimpleMeterRegistry(), new MetricLabels(id -> false));
    private final ActivityBattleService service = new ActivityBattleService(precheck, tickets, gather, ids, metrics);

    private static BattleActivityContext warContext() {
        return BattleActivityContext.newBuilder()
                .setKind(eBattleActivityKind.BATTLE_ACTIVITY_KIND_GUILD_WAR)
                .setGuildId(1001L)
                .setGuildWar(GuildWarBattleContext.newBuilder()
                        .setWarBattleKind(3)
                        .setAttackerGuildId(1001L)
                        .setDefenderGuildId(1002L)
                        .setZoneId(1)
                        .build())
                .build();
    }

    @Test
    void startGuildWarPvpAssignsExplicitTeamsAndLaunchesGather() {
        StartGuildWarBattleResponse resp = service.startGuildWar(
                StartGuildWarBattleRequest.newBuilder()
                        .setMatchMode(MatchMode.MATCH_MODE_GUILD_WAR_PVP_VALUE)
                        .setBattleConfigId(2)
                        .addAttackerPlayerIds(ATK)
                        .addDefenderPlayerIds(DEF)
                        .setActivityContext(warContext())
                        .build(),
                Deadline.after(3_000));

        assertThat(resp.getReject()).isEqualTo(ActivityBattleReject.ACTIVITY_BATTLE_REJECT_NONE);
        assertThat(resp.getBattleId()).isNotZero();
        assertThat(gather.plans).hasSize(1);
        GatherPlan plan = gather.plans.get(0);
        assertThat(plan.mode()).isEqualTo(MatchMode.MATCH_MODE_GUILD_WAR_PVP);
        assertThat(plan.memberTeams()).containsEntry(ATK, 0).containsEntry(DEF, 1);
        assertThat(plan.aiDefenderSnapshots()).isEmpty();
    }

    @Test
    void startGuildWarSiegePassesAiDefenderSnapshotsToGather() {
        BattlePlayerSnapshot aiDef = BattlePlayerSnapshot.newBuilder()
                .setPlayerId(900_001L)
                .setPlayerName("青云守旗弟子")
                .setTeamIndex(1)
                .setAiControlled(true)
                .build();
        StartGuildWarBattleResponse resp = service.startGuildWar(
                StartGuildWarBattleRequest.newBuilder()
                        .setMatchMode(MatchMode.MATCH_MODE_GUILD_WAR_SIEGE_VALUE)
                        .setBattleConfigId(2)
                        .addAttackerPlayerIds(ATK)
                        .addAiDefenderSnapshots(aiDef)
                        .setActivityContext(warContext())
                        .build(),
                Deadline.after(3_000));

        assertThat(resp.getReject()).isEqualTo(ActivityBattleReject.ACTIVITY_BATTLE_REJECT_NONE);
        assertThat(resp.getBattleId()).isNotZero();
        GatherPlan plan = gather.plans.get(0);
        assertThat(plan.mode()).isEqualTo(MatchMode.MATCH_MODE_GUILD_WAR_SIEGE);
        assertThat(plan.memberTeams()).containsEntry(ATK, 0).hasSize(1);
        assertThat(plan.aiDefenderSnapshots()).hasSize(1);
        assertThat(plan.aiDefenderSnapshots().get(0).getAiControlled()).isTrue();
    }

    @Test
    void startGuildWarConflictReturnsOffenderBattleIdForSpectate() {
        players.inBattle(DEF, 777_888L);
        StartGuildWarBattleResponse resp = service.startGuildWar(
                StartGuildWarBattleRequest.newBuilder()
                        .setMatchMode(MatchMode.MATCH_MODE_GUILD_WAR_PVP_VALUE)
                        .setBattleConfigId(2)
                        .addAttackerPlayerIds(ATK)
                        .addDefenderPlayerIds(DEF)
                        .setActivityContext(warContext())
                        .build(),
                Deadline.after(3_000));

        assertThat(resp.getReject()).isEqualTo(ActivityBattleReject.ACTIVITY_BATTLE_REJECT_MEMBER_IN_BATTLE);
        assertThat(resp.getOffenderPlayerId()).isEqualTo(DEF);
        assertThat(resp.getOffenderBattleId()).isEqualTo(777_888L);
        assertThat(gather.plans).isEmpty();
    }
}
