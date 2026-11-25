package com.game.match.tournament;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.match.metrics.MatchMetrics;
import com.game.match.rating.RatingStore;
import com.game.match.rating.RatingTestDatabase;
import com.game.proto.BattlePetSettlementData;
import com.game.proto.BattleSettlementData;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.eBattleOutcome;
import com.game.proto.content_guard.CheckTextResponse;
import com.game.proto.content_guard.TextVerdict;
import com.game.proto.match.CheerTournamentSquadRequest;
import com.game.proto.match.ClaimTournamentRewardRequest;
import com.game.proto.match.CreateTournamentSquadRequest;
import com.game.proto.match.GetTournamentOverviewRequest;
import com.game.proto.match.GetTournamentOverviewResponse;
import com.game.proto.match.InviteTournamentMemberRequest;
import com.game.proto.match.LeaveTournamentSquadRequest;
import com.game.proto.match.ListTournamentStandingsRequest;
import com.game.proto.match.MatchMode;
import com.game.proto.match.RespondTournamentInviteRequest;
import com.game.proto.match.StartTournamentMatchRequest;
import com.game.proto.match.TournamentMatchState;
import com.game.proto.match.TournamentRewardTierKind;
import com.game.proto.match.TournamentSquadState;
import com.game.proto.match.TournamentStage;
import com.game.proto.match.UpdateTournamentLineupRequest;
import com.game.table.ConfigTables;
import com.game.table.MatchErrorTip;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 全服赛季战队锦标赛《群仙问道赛》V1 纯规则 + H2 全生命周期 + RatingStore 战果分流双版对账回归。 */
class TournamentParityTest {

    private static ConfigTables config;

    @BeforeAll
    static void loadTables() {
        Path dir = Files.isDirectory(Path.of("../config-data/tables"))
                ? Path.of("../config-data/tables") : Path.of("config-data/tables");
        config = ConfigTables.load(dir);
    }

    @Test
    void 纯规则核与Go版逐条一致() {
        assertThat(TournamentRules.normalizeSquadName("  青云 剑阁 ")).isPresent()
                .get().extracting(TournamentRules.NormalizedName::display, TournamentRules.NormalizedName::norm)
                .containsExactly("青云 剑阁", "青云剑阁");
        assertThat(TournamentRules.normalizeSquadName("A")).isEmpty();
        assertThat(TournamentRules.normalizeSquadName("一二三四五六七八九十甲乙丙")).isEmpty();
        assertThat(TournamentRules.normalizeSquadName("非法\n名")).isEmpty();

        assertThat(TournamentRules.knockoutBracketSize(0, 16)).isZero();
        assertThat(TournamentRules.knockoutBracketSize(1, 16)).isZero();
        assertThat(TournamentRules.knockoutBracketSize(2, 16)).isEqualTo(2);
        assertThat(TournamentRules.knockoutBracketSize(3, 16)).isEqualTo(4);
        assertThat(TournamentRules.knockoutBracketSize(4, 16)).isEqualTo(4);
        assertThat(TournamentRules.knockoutBracketSize(5, 16)).isEqualTo(8);
        assertThat(TournamentRules.knockoutBracketSize(9, 16)).isEqualTo(16);
        assertThat(TournamentRules.knockoutBracketSize(20, 16)).isEqualTo(16);

        assertThat(TournamentRules.seedPairs(4)).isDeepEqualTo(new int[][]{{1, 4}, {2, 3}});
        assertThat(TournamentRules.rankForEliminatedRound(4, 1)).isEqualTo(3);
        assertThat(TournamentRules.rankForEliminatedRound(4, 2)).isEqualTo(2);
        assertThat(TournamentRules.rankForEliminatedRound(16, 1)).isEqualTo(9);

        // 四级平局决胜：存活数 > 剩余血量和 > 海选种子 > 队ID
        var a1 = new TournamentRules.KnockoutTieMetrics(100L, 4, 3, 100L);
        var b1 = new TournamentRules.KnockoutTieMetrics(200L, 1, 2, 9999L);
        assertThat(TournamentRules.resolveKnockoutDrawWinner(a1, b1)).isZero();

        var a2 = new TournamentRules.KnockoutTieMetrics(100L, 4, 2, 500L);
        var b2 = new TournamentRules.KnockoutTieMetrics(200L, 1, 2, 800L);
        assertThat(TournamentRules.resolveKnockoutDrawWinner(a2, b2)).isEqualTo(1);

        var a3 = new TournamentRules.KnockoutTieMetrics(100L, 2, 2, 500L);
        var b3 = new TournamentRules.KnockoutTieMetrics(200L, 3, 2, 500L);
        assertThat(TournamentRules.resolveKnockoutDrawWinner(a3, b3)).isZero();

        List<Long> ids = List.of(101L, 202L, 303L);
        assertThat(TournamentRules.decodeLineup(TournamentRules.encodeLineup(ids))).containsExactlyElementsOf(ids);
    }

    @Test
    void 完整四季阶段生命周期_组队报名海选助威淘汰平局决胜发奖与下季开启() throws Exception {
        TournamentTables tables = TournamentTables.from(config);
        assertThat(tables.rule().getEnabled()).isTrue();
        assertThat(tables.rule().getBattleTeamSize()).isEqualTo(3);
        assertThat(tables.rule().getRosterMaxSize()).isEqualTo(4);
        assertThat(tables.rewardTierViews()).hasSize(7);

        try (RatingTestDatabase db = RatingTestDatabase.h2()) {
            AtomicLong now = new AtomicLong(1_800_000_000_000L);
            AtomicLong idGen = new AtomicLong(900_000L);
            AtomicLong battleIdGen = new AtomicLong(700_000L);
            TournamentStore store = new TournamentStore(db.dataSource, tables, now::get);
            TournamentService service = new TournamentService(store, idGen::incrementAndGet, spec -> battleIdGen.incrementAndGet());
            MatchMetrics metrics = new MatchMetrics(new SimpleMeterRegistry(), new com.game.match.metrics.MetricLabels(id -> true));
            RatingStore ratingStore = new RatingStore(RatingStore.connections(db.dataSource), cfg -> 20, metrics, now::get)
                    .withTournamentStore(store);

            // 1. 建 4 支战队（每队 3 人，第 1 队额外收 1 名替补），校验重名/满员/替补换首发/锁定后不可踢人
            long[] squadIds = new long[4];
            long[][] captainsAndMembers = {
                    {1001L, 1002L, 1003L, 1004L},
                    {2001L, 2002L, 2003L},
                    {3001L, 3002L, 3003L},
                    {4001L, 4002L, 4003L}
            };
            String[] names = {"太玄剑阁", "青云仙宗", "昆仑道盟", "天衍古派"};
            for (int i = 0; i < 4; i++) {
                var created = service.createSquad(captainsAndMembers[i][0], 1,
                        CreateTournamentSquadRequest.newBuilder().setSquadName(names[i]).build());
                assertThat(created.getErrorMessage().getId()).isZero();
                squadIds[i] = created.getSquad().getSquadId();
                for (int j = 1; j < captainsAndMembers[i].length; j++) {
                    long memberId = captainsAndMembers[i][j];
                    assertThat(service.inviteMember(captainsAndMembers[i][0],
                            InviteTournamentMemberRequest.newBuilder().setTargetPlayerId(memberId).build())
                            .getErrorMessage().getId()).isZero();
                    assertThat(service.respondInvite(memberId, 1,
                            RespondTournamentInviteRequest.newBuilder().setSquadId(squadIds[i]).setAccept(true).build())
                            .getErrorMessage().getId()).isZero();
                }
            }
            // 同名归一化冲突拒绝
            assertThat(service.createSquad(5001L, 1,
                    CreateTournamentSquadRequest.newBuilder().setSquadName("  太玄 剑阁 ").build())
                    .getErrorMessage().getId())
                    .isEqualTo(MatchErrorTip.match_error.kMatchTournamentSquadNameInvalid_VALUE);
            // 第 1 队已 4 人（rosterMaxSize=4），再邀第 5 人拒满员
            assertThat(service.inviteMember(1001L,
                    InviteTournamentMemberRequest.newBuilder().setTargetPlayerId(5001L).build())
                    .getErrorMessage().getId())
                    .isEqualTo(MatchErrorTip.match_error.kMatchTournamentSquadFull_VALUE);
            // 第 1 队把替补 1004 换入首发，换下 1003
            var lineupResp = service.updateLineup(1001L,
                    UpdateTournamentLineupRequest.newBuilder().addAllStarterPlayerIds(List.of(1001L, 1002L, 1004L)).build());
            assertThat(lineupResp.getErrorMessage().getId()).isZero();
            // 锁定报名
            for (int i = 0; i < 4; i++) {
                var reg = service.registerSquad(captainsAndMembers[i][0]);
                assertThat(reg.getErrorMessage().getId()).isZero();
                assertThat(reg.getSquad().getState()).isEqualTo(TournamentSquadState.TOURNAMENT_SQUAD_STATE_REGISTERED);
            }
            // 锁定后禁止踢人/退队
            assertThat(service.leaveSquad(1001L,
                    LeaveTournamentSquadRequest.newBuilder().setTargetPlayerId(1004L).build())
                    .getErrorMessage().getId())
                    .isEqualTo(MatchErrorTip.match_error.kMatchTournamentSquadLocked_VALUE);

            // 观众 9001 免费助威第 1 队，二次助威拒绝
            var cheer1 = service.cheerSquad(9001L, CheerTournamentSquadRequest.newBuilder().setSquadId(squadIds[0]).build());
            assertThat(cheer1.getErrorMessage().getId()).isZero();
            assertThat(cheer1.getSquadCheerCount()).isEqualTo(1);
            assertThat(service.cheerSquad(9001L, CheerTournamentSquadRequest.newBuilder().setSquadId(squadIds[1]).build())
                    .getErrorMessage().getId())
                    .isEqualTo(MatchErrorTip.match_error.kMatchTournamentAlreadyCheered_VALUE);

            // 2. 推进至海选（QUALIFIER），通过 RatingStore.apply 结算 2 场海选赛（校验重复投递幂等）
            GetTournamentOverviewResponse regView = service.getOverview(1001L, GetTournamentOverviewRequest.getDefaultInstance());
            now.set(regView.getCurrentSeason().getQualifierStartMs() + 1000L);
            store.advanceSeason(now.get(), idGen::incrementAndGet);
            assertThat(service.getOverview(1001L, GetTournamentOverviewRequest.getDefaultInstance())
                    .getCurrentSeason().getStage()).isEqualTo(TournamentStage.TOURNAMENT_STAGE_QUALIFIER);

            // Squad 0 vs Squad 1 -> Squad 0 胜；Squad 2 vs Squad 3 -> Squad 2 胜
            var q1a = service.startMatch(1001L, StartTournamentMatchRequest.getDefaultInstance());
            assertThat(q1a.getErrorMessage().getId()).isZero();
            assertThat(q1a.getBattleId()).isZero();
            var q1b = service.startMatch(2001L, StartTournamentMatchRequest.getDefaultInstance());
            assertThat(q1b.getErrorMessage().getId()).isZero();
            assertThat(q1b.getBattleId()).isPositive();
            BattleResultEvent q1Event = BattleResultEvent.newBuilder()
                    .setBattleId(q1b.getBattleId())
                    .setMatchMode(MatchMode.MATCH_MODE_SEASON_TOURNAMENT_VALUE)
                    .setBattleConfigId(tables.rule().getBattleConfigId())
                    .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN)
                    .setTotalRounds(6)
                    .build();
            assertThat(ratingStore.apply(q1Event).outcome()).isEqualTo(RatingStore.Outcome.APPLIED);
            assertThat(ratingStore.apply(q1Event).outcome()).isEqualTo(RatingStore.Outcome.DUPLICATE);

            service.startMatch(3001L, StartTournamentMatchRequest.getDefaultInstance());
            var q2b = service.startMatch(4001L, StartTournamentMatchRequest.getDefaultInstance());
            assertThat(ratingStore.apply(BattleResultEvent.newBuilder()
                    .setBattleId(q2b.getBattleId())
                    .setMatchMode(MatchMode.MATCH_MODE_SEASON_TOURNAMENT_VALUE)
                    .setBattleConfigId(tables.rule().getBattleConfigId())
                    .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN)
                    .setTotalRounds(7)
                    .build()).outcome()).isEqualTo(RatingStore.Outcome.APPLIED);

            // 3. 推进至淘汰赛（KNOCKOUT）：生成 4 强单败签表（2 场半决赛 + 1 场决赛 = 3 场）
            now.set(regView.getCurrentSeason().getKnockoutStartMs() + 1000L);
            store.advanceSeason(now.get(), idGen::incrementAndGet);
            var standings = service.listStandings(1001L, ListTournamentStandingsRequest.newBuilder().setPage(1).setPageSize(20).build());
            assertThat(standings.getSeason().getStage()).isEqualTo(TournamentStage.TOURNAMENT_STAGE_KNOCKOUT);
            assertThat(standings.getSeason().getKnockoutTeamCount()).isEqualTo(4);
            assertThat(standings.getKnockoutBracketCount()).isEqualTo(3);

            // 半决赛 1（round 1, slot 0）：测试打满回合判平时按存活数+血量四级决胜让 A 晋级
            var sf1 = standings.getKnockoutBracket(0);
            long sf1CapA = captainOf(sf1.getSquadAId(), squadIds, captainsAndMembers);
            long sf1CapB = captainOf(sf1.getSquadBId(), squadIds, captainsAndMembers);
            service.startMatch(sf1CapA, StartTournamentMatchRequest.newBuilder().setMatchId(sf1.getMatchId()).build());
            var sf1Launch = service.startMatch(sf1CapB, StartTournamentMatchRequest.newBuilder().setMatchId(sf1.getMatchId()).build());
            assertThat(sf1Launch.getBattleId()).isPositive();
            assertThat(sf1Launch.getMatch().getFeaturedSpectate()).isTrue();
            ratingStore.apply(BattleResultEvent.newBuilder()
                    .setBattleId(sf1Launch.getBattleId())
                    .setMatchMode(MatchMode.MATCH_MODE_SEASON_TOURNAMENT_VALUE)
                    .setBattleConfigId(tables.rule().getBattleConfigId())
                    .setOutcome(eBattleOutcome.BATTLE_OUTCOME_DRAW)
                    .setTotalRounds(20)
                    .addSettlements(BattleSettlementData.newBuilder().setPlayerId(sf1CapA).setPlayerTeamIndex(0).setHealth(500)
                            .addPets(BattlePetSettlementData.newBuilder().setHealth(300)))
                    .addSettlements(BattleSettlementData.newBuilder().setPlayerId(sf1CapB).setPlayerTeamIndex(1).setHealth(400).setIsDead(true))
                    .build());

            // 半决赛 2（round 1, slot 1）：A 胜
            var sf2 = standings.getKnockoutBracket(1);
            long sf2CapA = captainOf(sf2.getSquadAId(), squadIds, captainsAndMembers);
            long sf2CapB = captainOf(sf2.getSquadBId(), squadIds, captainsAndMembers);
            service.startMatch(sf2CapA, StartTournamentMatchRequest.newBuilder().setMatchId(sf2.getMatchId()).build());
            var sf2Launch = service.startMatch(sf2CapB, StartTournamentMatchRequest.newBuilder().setMatchId(sf2.getMatchId()).build());
            ratingStore.apply(BattleResultEvent.newBuilder()
                    .setBattleId(sf2Launch.getBattleId())
                    .setMatchMode(MatchMode.MATCH_MODE_SEASON_TOURNAMENT_VALUE)
                    .setBattleConfigId(tables.rule().getBattleConfigId())
                    .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN)
                    .setTotalRounds(5)
                    .build());

            // 决赛（round 2, slot 0）：两位胜者对决，A 夺冠
            var bracketBeforeFinal = service.listStandings(1001L, ListTournamentStandingsRequest.getDefaultInstance());
            var fin = bracketBeforeFinal.getKnockoutBracket(2);
            assertThat(fin.getSquadAId()).isEqualTo(sf1.getSquadAId());
            assertThat(fin.getSquadBId()).isEqualTo(sf2.getSquadAId());
            long finCapA = captainOf(fin.getSquadAId(), squadIds, captainsAndMembers);
            long finCapB = captainOf(fin.getSquadBId(), squadIds, captainsAndMembers);
            service.startMatch(finCapA, StartTournamentMatchRequest.newBuilder().setMatchId(fin.getMatchId()).build());
            var finLaunch = service.startMatch(finCapB, StartTournamentMatchRequest.newBuilder().setMatchId(fin.getMatchId()).build());
            ratingStore.apply(BattleResultEvent.newBuilder()
                    .setBattleId(finLaunch.getBattleId())
                    .setMatchMode(MatchMode.MATCH_MODE_SEASON_TOURNAMENT_VALUE)
                    .setBattleConfigId(tables.rule().getBattleConfigId())
                    .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN)
                    .setTotalRounds(8)
                    .build());

            // 4. 推进结算（SETTLED）：验证冠军殿堂 + 替补 1004 同领冠军档奖励 + 助威 9001 领助威礼 + 二次领奖幂等拒绝
            store.advanceSeason(now.get(), idGen::incrementAndGet);
            var settledView = service.getOverview(1004L, GetTournamentOverviewRequest.newBuilder().setIncludeHistory(true).build());
            assertThat(settledView.getCurrentSeason().getStage()).isEqualTo(TournamentStage.TOURNAMENT_STAGE_SETTLED);
            assertThat(settledView.getCurrentSeason().getChampionSquadId()).isEqualTo(squadIds[0]);
            assertThat(settledView.getHallOfFameCount()).isEqualTo(1);
            assertThat(settledView.getMyRewardsList())
                    .extracting(r -> r.getTierKind())
                    .containsExactly(TournamentRewardTierKind.TOURNAMENT_REWARD_TIER_CHAMPION);

            var claimedSub = service.claimReward(1004L, ClaimTournamentRewardRequest.newBuilder().setSeasonId(1).build());
            assertThat(claimedSub.getErrorMessage().getId()).isZero();
            assertThat(claimedSub.getClaimedRewardsCount()).isEqualTo(1);
            assertThat(service.claimReward(1004L, ClaimTournamentRewardRequest.newBuilder().setSeasonId(1).build())
                    .getErrorMessage().getId())
                    .isEqualTo(MatchErrorTip.match_error.kMatchTournamentRewardUnavailable_VALUE);

            var claimedCheer = service.claimReward(9001L, ClaimTournamentRewardRequest.newBuilder().setSeasonId(1).build());
            assertThat(claimedCheer.getErrorMessage().getId()).isZero();
            assertThat(claimedCheer.getClaimedRewards(0).getTierKind())
                    .isEqualTo(TournamentRewardTierKind.TOURNAMENT_REWARD_TIER_CHEER);

            // 5. 推进越过 end_ms -> 自动开启第 2 赛季报名
            now.set(settledView.getCurrentSeason().getEndMs() + 1000L);
            store.advanceSeason(now.get(), idGen::incrementAndGet);
            var nextSeasonView = service.getOverview(1001L, GetTournamentOverviewRequest.newBuilder().setIncludeHistory(true).build());
            assertThat(nextSeasonView.getCurrentSeason().getSeasonId()).isEqualTo(2);
            assertThat(nextSeasonView.getCurrentSeason().getStage()).isEqualTo(TournamentStage.TOURNAMENT_STAGE_REGISTRATION);
            assertThat(nextSeasonView.getHallOfFameCount()).isEqualTo(1);
            assertThat(nextSeasonView.getHallOfFame(0).getChampionSquadName()).isEqualTo("太玄剑阁");
            assertThat(bracketBeforeFinal.getKnockoutBracket(0).getState()).isEqualTo(TournamentMatchState.TOURNAMENT_MATCH_STATE_FINISHED);
        }
    }

    @Test
    void 生产接线回归_内容安全拦截与发件失败重试与开战失败回滚与后台驱动器() throws Exception {
        TournamentTables tables = TournamentTables.from(config);
        try (RatingTestDatabase db = RatingTestDatabase.h2()) {
            AtomicLong now = new AtomicLong(1_850_000_000_000L);
            AtomicLong idGen = new AtomicLong(950_000L);
            TournamentStore store = new TournamentStore(db.dataSource, tables, now::get);

            // 1. 内容安全拦截：违禁队名在事务外即被拒，且不写入数据库
            TournamentService.TextGuard guard = (scene, pid, text) -> CheckTextResponse.newBuilder()
                    .setVerdict(text.contains("违禁") ? TextVerdict.TEXT_VERDICT_REJECT : TextVerdict.TEXT_VERDICT_PASS)
                    .build();
            java.util.concurrent.atomic.AtomicBoolean failLaunch = new java.util.concurrent.atomic.AtomicBoolean(true);
            java.util.concurrent.atomic.AtomicBoolean failMail = new java.util.concurrent.atomic.AtomicBoolean(true);
            TournamentService.MatchLauncher launcher = spec -> failLaunch.get() ? 0L : idGen.incrementAndGet();
            TournamentService.RewardMailSender mailSender = (pid, reward, title, body) -> {
                if (failMail.get()) {
                    throw new IllegalStateException("mail node temporary unavailable");
                }
                assertThat(title).isNotBlank();
                assertThat(reward.rewardId()).isPositive();
                return 880_000L + reward.tierId();
            };
            TournamentService service = new TournamentService(store, idGen::incrementAndGet, launcher, guard, mailSender);

            var badName = service.createSquad(101L, 1, CreateTournamentSquadRequest.newBuilder().setSquadName("违禁仙盟").build());
            assertThat(badName.getErrorMessage().getId())
                    .isEqualTo(MatchErrorTip.match_error.kMatchTournamentSquadNameInvalid_VALUE);

            // 2. 正常建 2 队并锁定报名 -> 后台驱动器 tick 推进至海选
            long[][] teams = {{101L, 102L, 103L}, {201L, 202L, 203L}};
            String[] names = {"灵霄剑派", "天机玄门"};
            for (int i = 0; i < 2; i++) {
                var c = service.createSquad(teams[i][0], 1, CreateTournamentSquadRequest.newBuilder().setSquadName(names[i]).build());
                assertThat(c.getErrorMessage().getId()).isZero();
                for (int j = 1; j < 3; j++) {
                    service.inviteMember(teams[i][0], InviteTournamentMemberRequest.newBuilder().setTargetPlayerId(teams[i][j]).build());
                    service.respondInvite(teams[i][j], 1, RespondTournamentInviteRequest.newBuilder()
                            .setSquadId(c.getSquad().getSquadId()).setAccept(true).build());
                }
                assertThat(service.registerSquad(teams[i][0]).getErrorMessage().getId()).isZero();
            }

            var ov = service.getOverview(101L, GetTournamentOverviewRequest.getDefaultInstance());
            now.set(ov.getCurrentSeason().getQualifierStartMs() + 1000L);
            try (TournamentSeasonDriver driver = new TournamentSeasonDriver(store, idGen::incrementAndGet, 60_000L)) {
                driver.start();
                assertThat(driver.isRunning()).isTrue();
                driver.tick();
            }
            assertThat(service.getOverview(101L, GetTournamentOverviewRequest.getDefaultInstance())
                    .getCurrentSeason().getStage()).isEqualTo(TournamentStage.TOURNAMENT_STAGE_QUALIFIER);

            // 3. 开战器返回 0（如有人离线/备战失败）时回滚 READY 到 WAITING，返回 kMatchTournamentMatchNotReady；恢复后再点直接成功开战
            assertThat(service.startMatch(101L, StartTournamentMatchRequest.getDefaultInstance()).getErrorMessage().getId()).isZero();
            var failedStart = service.startMatch(201L, StartTournamentMatchRequest.getDefaultInstance());
            assertThat(failedStart.getErrorMessage().getId())
                    .isEqualTo(MatchErrorTip.match_error.kMatchTournamentMatchNotReady_VALUE);
            assertThat(failedStart.getBattleId()).isZero();

            failLaunch.set(false);
            var okStart = service.startMatch(201L, StartTournamentMatchRequest.getDefaultInstance());
            assertThat(okStart.getErrorMessage().getId()).isZero();
            assertThat(okStart.getBattleId()).isPositive();

            // 4. 完赛并直接推进结算 -> 验证发件失败不落账 CLAIMED，重试发件成功后写入 mail_id 且二次领取拒收
            store.applyBattleResult(BattleResultEvent.newBuilder()
                    .setBattleId(okStart.getBattleId())
                    .setMatchMode(MatchMode.MATCH_MODE_SEASON_TOURNAMENT_VALUE)
                    .setBattleConfigId(tables.rule().getBattleConfigId())
                    .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN)
                    .setTotalRounds(5)
                    .build(), 20);
            now.set(ov.getCurrentSeason().getKnockoutStartMs() + 1000L);
            store.advanceSeason(now.get(), idGen::incrementAndGet);
            now.set(ov.getCurrentSeason().getSettleStartMs() + 1000L);
            store.advanceSeason(now.get(), idGen::incrementAndGet);

            var mailFailResp = service.claimReward(101L, ClaimTournamentRewardRequest.newBuilder().setSeasonId(1).build());
            assertThat(mailFailResp.getErrorMessage().getId()).isEqualTo(MatchErrorTip.match_error.kMatchInternal_VALUE);

            failMail.set(false);
            var mailOkResp = service.claimReward(101L, ClaimTournamentRewardRequest.newBuilder().setSeasonId(1).build());
            assertThat(mailOkResp.getErrorMessage().getId()).isZero();
            assertThat(mailOkResp.getClaimedRewardsCount()).isEqualTo(1);
            assertThat(mailOkResp.getClaimedRewards(0).getMailId()).isPositive();
            assertThat(service.claimReward(101L, ClaimTournamentRewardRequest.newBuilder().setSeasonId(1).build())
                    .getErrorMessage().getId())
                    .isEqualTo(MatchErrorTip.match_error.kMatchTournamentRewardUnavailable_VALUE);
        }
    }

    private static long captainOf(long squadId, long[] squadIds, long[][] members) {
        for (int i = 0; i < squadIds.length; i++) {
            if (squadIds[i] == squadId) {
                return members[i][0];
            }
        }
        throw new AssertionError("unknown squadId=" + squadId);
    }
}
