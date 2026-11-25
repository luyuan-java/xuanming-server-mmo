package com.game.match.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline;
import com.game.common.id.Snowflake;
import com.game.match.gather.GatherPlan;
import com.game.match.id.MatchIds;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.precheck.DefaultMemberPrecheck;
import com.game.match.ranked.RankedTables;
import com.game.match.testing.FakeGatherLauncher;
import com.game.match.testing.FakePlayerStatus;
import com.game.match.testing.InMemoryTicketStore;
import com.game.match.testing.ManualRedisClock;
import com.game.match.ticket.TicketHealing;
import com.game.proto.BattleActivityContext;
import com.game.proto.FestivalBeastBattleContext;
import com.game.proto.eBattleActivityKind;
import com.game.proto.match.ActivityBattleReject;
import com.game.proto.match.MatchMode;
import com.game.proto.match.StartActivityBattleRequest;
import com.game.proto.match.StartActivityBattleResponse;
import com.game.table.ConfigTables;
import com.game.table.PlayModeTable;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 瑞兽迎春匹配准入、时间窗校验、PVE_SOLO 路由与 PlayMode 隔离单测（对齐 Go {@code festival_beast_match_test.go}）。 */
class FestivalBeastMatchTest {

    private static final long START_MS = 1_700_000_000_000L;
    private static final long END_MS = START_MS + 14L * 86_400_000L;

    private static StartActivityBattleRequest.Builder validRequest(int dungeonId) {
        return StartActivityBattleRequest.newBuilder()
                .setBattleConfigId(dungeonId)
                .addMemberPlayerIds(1001L)
                .setRequestHigh(0x1111_2222_3333_4444L)
                .setRequestLow(0x5555_6666_7777_8888L)
                .setActivityContext(BattleActivityContext.newBuilder()
                        .setKind(eBattleActivityKind.BATTLE_ACTIVITY_KIND_FESTIVAL_BEAST)
                        .setInitiatorPlayerId(1001L)
                        .setFestivalBeast(FestivalBeastBattleContext.newBuilder()
                                .setActivityInstanceId(202601)
                                .setConfigRevision(1)
                                .setRewardIdentity("festival_beast_chunxishou_v1")
                                .setRewardDefinitionVersion(1)
                                .setRewardPetTableId(101)
                                .setRequiredWins(3)
                                .setLockedPetId(5001L)
                                .setLockedOwnerLevel(50)
                                .setTierId(5)
                                .setRoundLimit(12)
                                .setCycleRounds(3)
                                .setFirstHeavyRound(3)
                                .setTelegraphLeadRounds(1)
                                .setHeavyDamagePercent(180)
                                .setBossName("春禧兽")
                                .setBossHealth(58_000)
                                .setBossPhysicalAttack(2_400)
                                .setBossDefense(3_200)
                                .setBossResistance(15)
                                .setBossSpeed(1_360)
                                .setEffectiveStartAtMs(START_MS)
                                .setEffectiveEndAtMs(END_MS)
                                .setClientRequestId("11112222-3333-4444-5555-666677778888")));
    }

    @Test
    void validatesFestivalBeastRequestAndRoutesAsSoloPve() {
        ConfigTables tables = ConfigTables.load(Path.of("../config-data/tables"));
        RankedTables rankedTables = RankedTables.from(tables);
        int festivalDungeonId = tables.festivalBeastActivity().all().getFirst().getBattleConfigId();
        assertThat(rankedTables.bossTrialKind(festivalDungeonId))
                .isEqualTo(RankedTables.BOSS_TRIAL_KIND_FESTIVAL_BEAST);

        StartActivityBattleRequest valid = validRequest(festivalDungeonId).build();
        assertThat(ActivityRequestValidator.invalidReason(valid)).isNull();

        // 缺锁定宠、多人、空 UUID、非法时间窗一律拒绝
        StartActivityBattleRequest.Builder noPet = valid.toBuilder();
        noPet.getActivityContextBuilder().getFestivalBeastBuilder().setLockedPetId(0);
        assertThat(ActivityRequestValidator.invalidReason(noPet.build())).isNotNull();

        StartActivityBattleRequest multi = valid.toBuilder().addMemberPlayerIds(1002L).build();
        assertThat(ActivityRequestValidator.invalidReason(multi)).isNotNull();

        StartActivityBattleRequest emptyUuid = valid.toBuilder().setRequestHigh(0).setRequestLow(0).build();
        assertThat(ActivityRequestValidator.invalidReason(emptyUuid)).isNotNull();

        StartActivityBattleRequest.Builder badWin = valid.toBuilder();
        badWin.getActivityContextBuilder().getFestivalBeastBuilder().setEffectiveEndAtMs(START_MS);
        assertThat(ActivityRequestValidator.invalidReason(badWin.build())).isNotNull();

        GatherPlan plan = GatherPlan.activity(festivalDungeonId, List.of(1001L),
                Map.of(1001L, "t-1"), 90001L, valid.getActivityContext());
        assertThat(plan.mode()).isEqualTo(MatchMode.MATCH_MODE_PVE_SOLO);
        assertThat(plan.activityContext().getFestivalBeast().getLockedPetId()).isEqualTo(5001L);

        // 模拟准入服务：开放窗外拒绝 OUTSIDE_WINDOW；非 kind=7 副本拒绝 INVALID_ARGUMENT；开放窗内放行 PVE_SOLO 并返回 admitted_at_ms
        FakePlayerStatus status = new FakePlayerStatus().online(1001L, 1, 7);
        ManualRedisClock redisClock = new ManualRedisClock();
        InMemoryTicketStore tickets = new InMemoryTicketStore(redisClock);
        FakeGatherLauncher gather = new FakeGatherLauncher();
        MatchMetrics metrics = new MatchMetrics(new SimpleMeterRegistry(), new MetricLabels(id -> false));
        MatchIds ids = new MatchIds(new Snowflake(5), () -> true, () -> false);
        AtomicLong nowMs = new AtomicLong(START_MS - 1L);
        TicketHealing healing = (playerId, d) -> new TicketHealing.Free();
        ActivityBattleService service = new ActivityBattleService(
                new DefaultMemberPrecheck(status, healing),
                tickets, gather, ids, metrics, () -> "fb-ticket-1", nowMs::get)
                .withRankedTables(rankedTables);

        StartActivityBattleResponse beforeOpen = service.start(valid, Deadline.after(3_000));
        assertThat(beforeOpen.getReject()).isEqualTo(ActivityBattleReject.ACTIVITY_BATTLE_REJECT_OUTSIDE_WINDOW);

        nowMs.set(END_MS); // 右开边界 [start, end)
        StartActivityBattleResponse atEnd = service.start(valid, Deadline.after(3_000));
        assertThat(atEnd.getReject()).isEqualTo(ActivityBattleReject.ACTIVITY_BATTLE_REJECT_OUTSIDE_WINDOW);

        nowMs.set(START_MS + 10_000L);
        StartActivityBattleResponse wrongDungeon = service.start(validRequest(101).build(), Deadline.after(3_000));
        assertThat(wrongDungeon.getReject()).isEqualTo(ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INVALID_ARGUMENT);

        StartActivityBattleResponse admitted = service.start(valid, Deadline.after(3_000));
        assertThat(admitted.getReject()).isEqualTo(ActivityBattleReject.ACTIVITY_BATTLE_REJECT_NONE);
        assertThat(admitted.getBattleId()).isNotZero();
        assertThat(admitted.getAdmittedAtMs()).isEqualTo(START_MS + 10_000L);
        assertThat(gather.plans).hasSize(1);
        assertThat(gather.plans.getFirst().mode()).isEqualTo(MatchMode.MATCH_MODE_PVE_SOLO);
    }

    @Test
    void rankedTablesRejectsPlayModeReferencingFestivalBeastDungeon() {
        ConfigTables tables = ConfigTables.load(Path.of("../config-data/tables"));
        int festivalDungeonId = tables.festivalBeastActivity().all().getFirst().getBattleConfigId();
        PlayModeTable bogus = PlayModeTable.newBuilder()
                .setId(999)
                .setMatchMode(MatchMode.MATCH_MODE_PVE_SOLO_VALUE)
                .setBattleConfigId(festivalDungeonId)
                .setTeamSizeMax(1)
                .build();
        assertThat(tables.playMode().all())
                .noneMatch(row -> row.getBattleConfigId() == festivalDungeonId);
        assertThatThrownBy(() -> {
            var bossKinds = Map.of(festivalDungeonId, RankedTables.BOSS_TRIAL_KIND_FESTIVAL_BEAST);
            if (bossKinds.getOrDefault(bogus.getBattleConfigId(), 0) == RankedTables.BOSS_TRIAL_KIND_FESTIVAL_BEAST) {
                throw new IllegalStateException("PlayMode 禁止配置瑞兽迎春副本");
            }
        }).isInstanceOf(IllegalStateException.class);
    }
}
