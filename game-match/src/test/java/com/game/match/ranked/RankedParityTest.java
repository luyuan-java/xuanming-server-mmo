package com.game.match.ranked;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.match.feature.pb.MatchRankedSeasonRow;
import com.game.match.matcher.Tolerance;
import com.game.match.metrics.MatchMetrics;
import com.game.match.rating.RatingStore;
import com.game.match.rating.RatingTestDatabase;
import com.game.match.support.MatchModes;
import com.game.proto.BattleParticipationFact;
import com.game.proto.RankedMatchContext;
import com.game.proto.RankedMemberLoadout;
import com.game.proto.RankedPresetSelection;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.contracts.kafka.BattleResultTeam;
import com.game.proto.eBattleOutcome;
import com.game.proto.match.GetRankedProfileRequest;
import com.game.proto.match.GetRankedProfileResponse;
import com.game.proto.match.RankedBattleSettlementView;
import com.game.proto.match.RankedMatchRatingStatus;
import com.game.proto.match.RankedRewardKind;
import com.game.proto.match.RankedSeasonState;
import com.game.proto.match.RankedTier;
import com.game.table.ConfigTables;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 论剑排位 V1 Java 侧双端对账回归（分差硬封顶、1540→1556 跨段、12 败局 3 件展示权益、临界分/同分排序/无榜首/跨季重放/崩溃恢复）。 */
class RankedParityTest {

    private static final ConfigTables CONFIG = ConfigTables.load(Path.of("../config-data/tables"));
    private static final RankedTables TABLES = RankedTables.from(CONFIG);

    private static BattleResultEvent rankedBattle(long battleId, int seasonId, int teamSize,
                                                  long playerA, long playerB, eBattleOutcome outcome,
                                                  boolean aParticipated, boolean bParticipated) {
        RankedPresetSelection defSel = RankedPresetSelection.newBuilder()
                .setCharacterPresetId(101).setPetPresetId(203).build();
        return BattleResultEvent.newBuilder()
                .setBattleId(battleId)
                .setMatchMode(MatchModes.PVP_TEAM)
                .setBattleConfigId(5)
                .setOutcome(outcome)
                .setTotalRounds(4)
                .addTeams(BattleResultTeam.newBuilder().setTeamIndex(0).addPlayerIds(playerA))
                .addTeams(BattleResultTeam.newBuilder().setTeamIndex(1).addPlayerIds(playerB))
                .setRankedContext(RankedMatchContext.newBuilder()
                        .setSeasonId(seasonId)
                        .setRuleVersion(1)
                        .setConfigFingerprint(TABLES.configFingerprint())
                        .setTeamSize(teamSize)
                        .addMembers(RankedMemberLoadout.newBuilder().setPlayerId(playerA).setPresets(defSel))
                        .addMembers(RankedMemberLoadout.newBuilder().setPlayerId(playerB).setPresets(defSel)))
                .addParticipationFacts(BattleParticipationFact.newBuilder()
                        .setPlayerId(playerA).setEnteredRoom(aParticipated).setManualActionAccepted(aParticipated))
                .addParticipationFacts(BattleParticipationFact.newBuilder()
                        .setPlayerId(playerB).setEnteredRoom(bParticipated).setManualActionAccepted(bParticipated))
                .addSettlements(com.game.proto.BattleSettlementData.newBuilder()
                        .setBattleId(battleId).setPlayerId(playerA).setParticipated(aParticipated)
                        .setManualActionRounds(aParticipated ? 4 : 0).setEffectiveCombatContribution(aParticipated))
                .addSettlements(com.game.proto.BattleSettlementData.newBuilder()
                        .setBattleId(battleId).setPlayerId(playerB).setParticipated(bParticipated)
                        .setManualActionRounds(bParticipated ? 4 : 0).setEffectiveCombatContribution(bParticipated))
                .build();
    }

    @Test
    void 久等不越300分与1540跨段1556及12场跨规模败局拿齐3件展示权益() throws Exception {
        assertThat(Tolerance.rankedHardCapCenti(0)).isEqualTo(10_000L);
        assertThat(Tolerance.rankedHardCapCenti(30)).isEqualTo(20_000L);
        assertThat(Tolerance.rankedHardCapCenti(90)).isEqualTo(30_000L);
        assertThat(Tolerance.rankedHardCapCenti(600)).isEqualTo(30_000L);

        try (RatingTestDatabase db = RatingTestDatabase.h2()) {
            AtomicLong now = new AtomicLong(1_730_000_000_000L);
            RankedStore rankedStore = new RankedStore(db.dataSource, TABLES, now::get);
            RatingStore ratingStore = new RatingStore(
                    RatingStore.connections(db.dataSource), cfg -> 0,
                    new MatchMetrics(new SimpleMeterRegistry(), new com.game.match.metrics.MetricLabels(id -> true)),
                    now::get).withRankedStore(rankedStore);
            rankedStore.ensureActiveSeason();

            // 1. V2 1584 -> 1600 跨段（青锋 -> 云章）与权威分档暴露
            long p1 = 20001L, p2 = 20002L;
            db.putRating(p1, 158_400L, 7);
            db.putRating(p2, 158_400L, 7);
            // 先让 p1 在本季已亮段 7 场（其中 5 胜 2 负，下一场胜即解锁 6 胜锋芒称号）
            for (int i = 0; i < 7; i++) {
                rankedStore.recordBattleSettlement(new RankedStore.SettlementInput(
                        p1, 100L + i, 1, 3, RankedMatchRatingStatus.RANKED_MATCH_RATING_CONFIRMED,
                        i < 5 ? eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN : eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN,
                        0, 4, true, false, 0L, 101, 203));
            }
            BattleResultEvent winEvent = rankedBattle(200L, 1, 3, p1, p2, eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, true, true);
            assertThat(ratingStore.apply(winEvent).outcome()).isEqualTo(RatingStore.Outcome.APPLIED);
            // 重放只计一次
            assertThat(ratingStore.apply(winEvent).outcome()).isEqualTo(RatingStore.Outcome.DUPLICATE);

            RankedBattleSettlementView view = rankedStore.getBattleResult(p1, 200L).orElseThrow();
            assertThat(view.getRatingBeforeCenti()).isEqualTo(158_400L);
            assertThat(view.getRatingAfterCenti()).isEqualTo(160_000L);
            assertThat(view.getRatingDeltaCenti()).isEqualTo(1_600L);
            assertThat(view.getTierBefore()).isEqualTo(RankedTier.RANKED_TIER_QINGFENG);
            assertThat(view.getTierAfter()).isEqualTo(RankedTier.RANKED_TIER_YUNZHANG);
            assertThat(view.getSeasonValidMatchesAfter()).isEqualTo(8);
            assertThat(view.getNewlyUnlockedRewardsList())
                    .extracting(r -> r.getKind())
                    .containsExactlyInAnyOrder(
                            RankedRewardKind.RANKED_REWARD_CARD_BACKGROUND,
                            RankedRewardKind.RANKED_REWARD_WIN_MILESTONE);
            // 6 胜锋芒称号允许装备至称号栏
            var equipWinTitle = rankedStore.updateCosmetic(p1,
                    com.game.proto.match.RankedCosmeticLoadout.newBuilder()
                            .setEquippedTitleCode("s1_win_milestone_6").build());
            assertThat(equipWinTitle.status()).isEqualTo(RankedStore.WriteStatus.OK);
            assertThat(equipWinTitle.row().getEquippedTitleCode()).isEqualTo("s1_win_milestone_6");

            // 2. 同一角色跨五种人数完成 12 场有效败局仍拿齐 3 件参与展示权益，且不误发 6 胜称号
            long pLoss = 20010L;
            int[] sizes = {1, 2, 3, 4, 5, 1, 2, 3, 4, 5, 1, 2};
            for (int i = 0; i < 12; i++) {
                long opp = 21000L + i;
                BattleResultEvent loss = rankedBattle(300L + i, 1, sizes[i], pLoss, opp,
                        eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN, true, true);
                assertThat(ratingStore.apply(loss).outcome()).isEqualTo(RatingStore.Outcome.APPLIED);
            }
            RankedService service = new RankedService(rankedStore, TABLES, now::get);
            GetRankedProfileResponse profile = service.getProfile(pLoss, GetRankedProfileRequest.getDefaultInstance());
            assertThat(profile.getCurrentProgress().getValidMatches()).isEqualTo(12);
            assertThat(profile.getCurrentProgress().getLosses()).isEqualTo(12);
            assertThat(profile.getEntitlementsList())
                    .extracting(r -> r.getKind())
                    .containsExactlyInAnyOrder(
                            RankedRewardKind.RANKED_REWARD_AVATAR_PATTERN,
                            RankedRewardKind.RANKED_REWARD_CARD_BACKGROUND,
                            RankedRewardKind.RANKED_REWARD_TALISMAN);
            assertThat(profile.getCosmeticLoadout().getEquippedTalismanCode()).isEqualTo("s1_qingyun_talisman");
            assertThat(profile.getCurrentSeason().getUsesOwnProgression()).isTrue();
            assertThat(profile.getCurrentSeason().getAllowedTeamSizesList()).containsExactly(1, 3);
            assertThat(profile.getCurrentSeason().getPrimaryTeamSizesList()).containsExactly(1, 3, 5);
            assertThat(profile.getCurrentSeason().getSpecialTeamSizesList()).containsExactly(2, 4);
            assertThat(profile.getCurrentSeason().getTierQingfengCenti()).isEqualTo(152_000);
            assertThat(profile.getCurrentSeason().getTierYunzhangCenti()).isEqualTo(160_000);
            assertThat(profile.getCurrentSeason().getTierTianshuCenti()).isEqualTo(172_000);
            assertThat(profile.getCurrentSeason().getTierWushuangCenti()).isEqualTo(185_000);
            assertThat(profile.getCurrentSeason().getMilestoneWinMatches()).isEqualTo(6);
            assertThat(profile.getPresetsList()).allSatisfy(p -> assertThat(p.getSkillIdsCount()).isEqualTo(4));

            // 3. 正式排位上下文 use_free_preset=false，娱乐轮换(物法易势/护卫救援) use_free_preset=true 且 season_id=0
            RankedMatchContext formalCtx = rankedStore.buildRankedContext(1, List.of(p1, p2), Map.of()).orElseThrow();
            assertThat(formalCtx.getSeasonId()).isEqualTo(1);
            assertThat(formalCtx.getUseFreePreset()).isFalse();
            assertThat(formalCtx.getMembersList()).isEmpty();
            assertThat(TABLES.usesFreePreset(MatchModes.POLARITY_5V5, 110)).isTrue();
            assertThat(TABLES.usesFreePreset(MatchModes.PVE_SOLO, 109)).isTrue();
            assertThat(TABLES.usesFreePreset(MatchModes.PVP_TEAM, 5)).isFalse();
            RankedMatchContext freeCtx = rankedStore.buildFreePresetContext(5, List.of(p2, p1), Map.of());
            assertThat(freeCtx.getSeasonId()).isZero();
            assertThat(freeCtx.getUseFreePreset()).isTrue();
            assertThat(freeCtx.getMembersList()).extracting(RankedMemberLoadout::getPlayerId).containsExactly(p1, p2);
        }
    }

    @Test
    void 封榜临界分_同分排序_无榜首_旧季迟到_崩溃恢复与未参赛回归不上新季榜() throws Exception {
        try (RatingTestDatabase db = RatingTestDatabase.h2()) {
            AtomicLong now = new AtomicLong(1_730_000_000_000L);
            RankedStore rankedStore = new RankedStore(db.dataSource, TABLES, now::get);
            RatingStore ratingStore = new RatingStore(
                    RatingStore.connections(db.dataSource), cfg -> 0,
                    new MatchMetrics(new SimpleMeterRegistry(), new com.game.match.metrics.MetricLabels(id -> true)),
                    now::get).withRankedStore(rankedStore);
            rankedStore.ensureActiveSeason();

            // 同分 184999（临界无双线之下 => 无榜首）：A 亮段更早应排第 1，B 排第 2；另加一位云章 160000 校验 V2 云章起发永久徽章+称号
            long pA = 30001L, pB = 30002L, pLate = 30003L, pOpp = 30004L, pYunzhang = 30005L;
            db.putRating(pA, 184_999L, 5);
            db.putRating(pB, 184_999L, 5);
            db.putRating(pLate, 150_000L, 0);
            db.putRating(pOpp, 150_000L, 0);
            db.putRating(pYunzhang, 160_000L, 4);
            now.set(1_730_000_100_000L);
            rankedStore.recordBattleSettlement(new RankedStore.SettlementInput(
                    pA, 401L, 1, 1, RankedMatchRatingStatus.RANKED_MATCH_RATING_CONFIRMED,
                    eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, 0, 3, true, false, 0L, 101, 203));
            now.set(1_730_000_200_000L);
            rankedStore.recordBattleSettlement(new RankedStore.SettlementInput(
                    pB, 402L, 1, 1, RankedMatchRatingStatus.RANKED_MATCH_RATING_CONFIRMED,
                    eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, 0, 3, true, false, 0L, 101, 203));
            now.set(1_730_000_250_000L);
            rankedStore.recordBattleSettlement(new RankedStore.SettlementInput(
                    pYunzhang, 404L, 1, 1, RankedMatchRatingStatus.RANKED_MATCH_RATING_CONFIRMED,
                    eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, 0, 3, true, false, 0L, 101, 203));

            // 进入 SETTLING：阻止新季前的 S1 新局，但截止前已成局（season_id=1）的迟到结算仍算旧季
            MatchRankedSeasonRow settling = rankedStore.beginSeasonRollover(1);
            assertThat(settling.getState()).isEqualTo(RankedSeasonState.RANKED_SEASON_STATE_SETTLING_VALUE);
            assertThat(rankedStore.buildRankedContext(1, List.of(pA, pB), Map.of())).isEmpty();

            BattleResultEvent lateS1 = rankedBattle(403L, 1, 1, pLate, pOpp, eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, true, true);
            assertThat(ratingStore.apply(lateS1).outcome()).isEqualTo(RatingStore.Outcome.APPLIED);
            assertThat(rankedStore.getBattleResult(pLate, 403L).orElseThrow().getSeasonId()).isEqualTo(1);

            // 崩溃恢复封榜并开启 S2（freezeHook 恰好执行一次）
            AtomicInteger freezeCalls = new AtomicInteger();
            MatchRankedSeasonRow s2 = rankedStore.recoverSeasonRollover(
                    Map.of(pA, "甲", pB, "乙"),
                    season -> freezeCalls.incrementAndGet());
            assertThat(freezeCalls.get()).isEqualTo(1);
            assertThat(s2.getSeasonId()).isEqualTo(2);
            assertThat(s2.getSeasonKey()).isEqualTo("s2");
            assertThat(s2.getState()).isEqualTo(RankedSeasonState.RANKED_SEASON_STATE_ACTIVE_VALUE);

            // 重复调用恢复幂等：不再建 S3、不再调封榜
            MatchRankedSeasonRow s2Again = rankedStore.recoverSeasonRollover(
                    Map.of(pA, "甲", pB, "乙"),
                    season -> freezeCalls.incrementAndGet());
            assertThat(s2Again.getSeasonId()).isEqualTo(2);
            assertThat(freezeCalls.get()).isEqualTo(1);

            // 核验 S1 历史：无榜首（184999 < 185000）、同分 A 第 1 / B 第 2；pA 在 S2 沿用 184999 分但 qualified=false（未参赛不上新季榜）
            RankedService service = new RankedService(rankedStore, TABLES, now::get);
            GetRankedProfileResponse profA = service.getProfile(pA, GetRankedProfileRequest.newBuilder().setIncludeHistory(true).build());
            assertThat(profA.getCurrentSeason().getSeasonId()).isEqualTo(2);
            assertThat(profA.getCurrentProgress().getRatingCenti()).isEqualTo(184_999L);
            assertThat(profA.getCurrentProgress().getQualified()).isFalse();
            assertThat(profA.getPastSeasonsList()).hasSize(1);
            assertThat(profA.getPastSeasons(0).getChampionPlayerId()).isZero();
            assertThat(profA.getPastSeasons(0).getRankBoardSealed()).isTrue();
            assertThat(profA.getHistoryList()).hasSize(1);
            assertThat(profA.getHistory(0).getFinalRank()).isEqualTo(1L);
            assertThat(profA.getHistory(0).getChampionAwarded()).isFalse();

            GetRankedProfileResponse profB = service.getProfile(pB, GetRankedProfileRequest.getDefaultInstance());
            assertThat(profB.getHistoryList()).isEmpty();
            GetRankedProfileResponse profBHist = service.getProfile(pB, GetRankedProfileRequest.newBuilder().setIncludeHistory(true).build());
            assertThat(profBHist.getHistory(0).getFinalRank()).isEqualTo(2L);

            GetRankedProfileResponse profYun = service.getProfile(pYunzhang, GetRankedProfileRequest.getDefaultInstance());
            assertThat(profYun.getEntitlementsList())
                    .extracting(r -> r.getItemCode())
                    .containsExactlyInAnyOrder("s1_tier_badge_yunzhang", "s1_tier_title_yunzhang");
        }
    }

    @Test
    void 试炼志上下文解析覆盖全三卷并隔离活动战与跨季排位() {
        // 卷一普通/困难首领试炼 101..108 与困难双卫同阵 112
        for (int dungeonId = 101; dungeonId <= 108; dungeonId++) {
            var ctx = TABLES.trialContextFor(MatchModes.PVE_SOLO, dungeonId, null, null);
            assertThat(ctx).as("dungeon=%d", dungeonId).isPresent();
            assertThat(ctx.get().getJournalEntryId()).isPositive();
            assertThat(ctx.get().getTrialRuleVersion()).isPositive();
            assertThat(ctx.get().getSeasonId()).isZero();
            assertThat(ctx.get().getGuardMonsterTableId()).isPositive();
        }
        var dualGuard = TABLES.trialContextFor(MatchModes.PVE_SOLO, 112, null, null);
        assertThat(dualGuard).isPresent();
        assertThat(dualGuard.get().getJournalEntryId()).isEqualTo(105);
        assertThat(dualGuard.get().getSeasonId()).isZero();
        assertThat(TABLES.bossTrialKind(112)).isEqualTo(8);
        // 卷三护卫救援(109) 与 物法易势 5V5(110) 搭配免费预设上下文（season_id=0, use_free_preset=true）保留试炼志上下文
        RankedMatchContext freePreset = RankedMatchContext.newBuilder().setUseFreePreset(true).build();
        var rescue = TABLES.trialContextFor(MatchModes.PVE_TEAM, 109, null, freePreset);
        assertThat(rescue).isPresent();
        assertThat(rescue.get().getJournalEntryId()).isEqualTo(301);
        assertThat(rescue.get().getSeasonId()).isPositive();
        assertThat(rescue.get().getGuardMonsterTableId()).isPositive();

        var polarity = TABLES.trialContextFor(MatchModes.POLARITY_5V5, 110, null, freePreset);
        assertThat(polarity).isPresent();
        assertThat(polarity.get().getJournalEntryId()).isEqualTo(302);
        assertThat(polarity.get().getSeasonId()).isPositive();

        // 卷三人宠会武(PVP_TEAM) 同季保留，跨季过滤
        int rankedSeasonId = TABLES.trialContextFor(MatchModes.PVP_TEAM, 5, null, null).orElseThrow().getSeasonId();
        var rankedSame = TABLES.trialContextFor(MatchModes.PVP_TEAM, 5, null,
                RankedMatchContext.newBuilder().setSeasonId(rankedSeasonId).build());
        assertThat(rankedSame).isPresent();
        assertThat(rankedSame.get().getJournalEntryId()).isEqualTo(303);
        assertThat(TABLES.trialContextFor(MatchModes.PVP_TEAM, 5, null,
                RankedMatchContext.newBuilder().setSeasonId(rankedSeasonId + 1).build())).isEmpty();

        // 活动战、切磋与常规副本一律不附试炼志上下文
        var activity = com.game.proto.BattleActivityContext.newBuilder().setActivityId(1).build();
        assertThat(TABLES.trialContextFor(MatchModes.PVE_SOLO, 101, activity, null)).isEmpty();
        assertThat(TABLES.trialContextFor(MatchModes.PVP_CHALLENGE, 101, null, null)).isEmpty();
        assertThat(TABLES.trialContextFor(MatchModes.PVE_SOLO, 1, null, null)).isEmpty();
    }

    @Test
    void 论剑V4连胜累计透传与破势终结纯荣誉口径() throws Exception {
        try (RatingTestDatabase db = RatingTestDatabase.h2()) {
            AtomicLong now = new AtomicLong(1_730_000_000_000L);
            RankedStore rankedStore = new RankedStore(db.dataSource, TABLES, now::get);
            RatingStore ratingStore = new RatingStore(
                    RatingStore.connections(db.dataSource), cfg -> 0,
                    new MatchMetrics(new SimpleMeterRegistry(), new com.game.match.metrics.MetricLabels(id -> true)),
                    now::get).withRankedStore(rankedStore);
            rankedStore.ensureActiveSeason();

            long p1 = 41001L, p2 = 41002L;
            // p1 连胜 3 场：每场后 current_win_streak / best_win_streak 递增
            for (int i = 1; i <= 3; i++) {
                now.addAndGet(5_000L);
                BattleResultEvent ev = rankedBattle(5000L + i, 1, 1, p1, p2,
                        eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, true, true);
                assertThat(ratingStore.apply(ev).outcome()).isEqualTo(RatingStore.Outcome.APPLIED);
            }
            RankedService service = new RankedService(rankedStore, TABLES, now::get);
            var prof1 = service.getProfile(p1, GetRankedProfileRequest.getDefaultInstance());
            assertThat(prof1.getCurrentProgress().getCurrentWinStreak()).isEqualTo(3);
            assertThat(prof1.getCurrentProgress().getBestWinStreak()).isEqualTo(3);

            // 达到 3 连胜后，buildRankedContext 透传 member_win_streaks 且保持 members 为空
            RankedMatchContext ctx = rankedStore.buildRankedContext(1, List.of(p1, p2), Map.of()).orElseThrow();
            assertThat(ctx.getMembersList()).isEmpty();
            assertThat(ctx.getMemberWinStreaksMap()).containsEntry(p1, 3).doesNotContainKey(p2);

            // p2 击败 3 连胜的 p1 -> p1 连胜归零(best 保留 3)，p2 触发破势终结(ended_opponent_streak=3)
            now.addAndGet(5_000L);
            BattleResultEvent upset = rankedBattle(5010L, 1, 1, p1, p2,
                    eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN, true, true);
            assertThat(ratingStore.apply(upset).outcome()).isEqualTo(RatingStore.Outcome.APPLIED);

            var viewP2 = rankedStore.getBattleResult(p2, 5010L).orElseThrow();
            assertThat(viewP2.getWinStreakAfter()).isEqualTo(1);
            assertThat(viewP2.getEndedOpponentStreak()).isEqualTo(3);

            var viewP1 = rankedStore.getBattleResult(p1, 5010L).orElseThrow();
            assertThat(viewP1.getWinStreakAfter()).isZero();
            assertThat(viewP1.getEndedOpponentStreak()).isZero();

            var prof1After = service.getProfile(p1, GetRankedProfileRequest.getDefaultInstance());
            assertThat(prof1After.getCurrentProgress().getCurrentWinStreak()).isZero();
            assertThat(prof1After.getCurrentProgress().getBestWinStreak()).isEqualTo(3);
        }
    }

    @Test
    void 归元城木桩禁免费预设_残局奇谈支持可选免费预设() {
        // 演武试招木桩（301/302, kind=9）强制本人真实养成，严禁走免费预设
        assertThat(TABLES.bossTrialKind(301)).isEqualTo(RankedTables.BOSS_TRIAL_KIND_PRACTICE_DUMMY);
        assertThat(TABLES.bossTrialKind(302)).isEqualTo(RankedTables.BOSS_TRIAL_KIND_PRACTICE_DUMMY);
        assertThat(TABLES.usesFreePreset(MatchModes.PVE_SOLO, 301)).isFalse();
        assertThat(TABLES.allowsOptionalFreePreset(301)).isFalse();
        assertThat(TABLES.allowsOptionalFreePreset(302)).isFalse();

        // 回合残局（311..313, kind=10）与归元人物奇谈（321..323, kind=11）允许单人按票据选配免费 3×3 预设
        for (int id : List.of(311, 312, 313, 321, 322, 323)) {
            assertThat(TABLES.usesFreePreset(MatchModes.PVE_SOLO, id)).isFalse();
            assertThat(TABLES.allowsOptionalFreePreset(id)).isTrue();
            assertThat(TABLES.trialContextFor(MatchModes.PVE_SOLO, id, null, null)).isEmpty();
        }
    }

    @Test
    void 论剑V6宿敌恩怨复仇与问道名局公开回放汇总() throws Exception {
        try (RatingTestDatabase db = RatingTestDatabase.h2()) {
            AtomicLong now = new AtomicLong(1_732_000_000_000L);
            RankedStore rankedStore = new RankedStore(db.dataSource, TABLES, now::get);
            RatingStore ratingStore = new RatingStore(
                    RatingStore.connections(db.dataSource), cfg -> 0,
                    new MatchMetrics(new SimpleMeterRegistry(), new com.game.match.metrics.MetricLabels(id -> true)),
                    now::get).withRankedStore(rankedStore);
            rankedStore.ensureActiveSeason();

            long p1 = 60001L, p2 = 60002L;
            db.putRating(p1, 165_000L, 10);
            db.putRating(p2, 166_000L, 10);
            // 预埋 66001 / 66002 的战报正文（模拟终局落库战报），验证 featured_battle_id 仅在有录像时写入且 ref_count 精准护航/替换释放。
            for (long bid : List.of(66001L, 66002L, 66003L)) {
                db.update("INSERT INTO match_battle_report(battle_id,created_ms,match_mode,battle_config_id,envelope,envelope_digest,ref_count) VALUES(?,?,?,?,?,?,?)",
                        com.game.pbmysql.PbMysql.uint64(bid), com.game.pbmysql.PbMysql.uint64(now.get()),
                        MatchModes.PVP_TEAM, 5, new byte[]{1, 2, 3}, new byte[]{4, 5, 6}, 2);
            }

            // 第 1 局：p2 胜 p1，携带 V6 自养配招快照
            BattleResultEvent b1 = BattleResultEvent.newBuilder()
                    .setBattleId(66001L)
                    .setMatchMode(MatchModes.PVP_TEAM)
                    .setBattleConfigId(5)
                    .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN)
                    .setTotalRounds(6)
                    .addTeams(BattleResultTeam.newBuilder().setTeamIndex(0).addPlayerIds(p1))
                    .addTeams(BattleResultTeam.newBuilder().setTeamIndex(1).addPlayerIds(p2))
                    .setRankedContext(RankedMatchContext.newBuilder()
                            .setSeasonId(1).setRuleVersion(2).setConfigFingerprint(TABLES.configFingerprint())
                            .setTeamSize(1).setUseFreePreset(false))
                    .addParticipationFacts(BattleParticipationFact.newBuilder()
                            .setPlayerId(p1).setEnteredRoom(true).setManualActionAccepted(true))
                    .addParticipationFacts(BattleParticipationFact.newBuilder()
                            .setPlayerId(p2).setEnteredRoom(true).setManualActionAccepted(true))
                    .addSettlements(com.game.proto.BattleSettlementData.newBuilder()
                            .setBattleId(66001L).setPlayerId(p1).setParticipated(true)
                            .setManualActionRounds(6).setEffectiveCombatContribution(true)
                            .setPlayerDisplayName("青云").setFinalSeasonSealId(101).setStarterPetTableId(1)
                            .addCoreSkillIds(10002).addCoreSkillIds(10018))
                    .addSettlements(com.game.proto.BattleSettlementData.newBuilder()
                            .setBattleId(66001L).setPlayerId(p2).setParticipated(true)
                            .setManualActionRounds(6).setEffectiveCombatContribution(true)
                            .setPlayerDisplayName("玄烈").setFinalSeasonSealId(102).setStarterPetTableId(2)
                            .addCoreSkillIds(10021).addCoreSkillIds(10002))
                    .build();
            assertThat(ratingStore.apply(b1).outcome()).isEqualTo(RatingStore.Outcome.APPLIED);

            // 再匹配时：buildRankedContext 透传宿敌记录，p1 对 p2 标记可雪耻；高段排位同步开启 V7 公开实时观战与最高分冻结
            RankedMatchContext ctx = rankedStore.buildRankedContext(1, List.of(p1, p2), Map.of()).orElseThrow();
            assertThat(ctx.getAllowPublicSpectate()).isTrue();
            assertThat(ctx.getPeakRatingCenti()).isGreaterThanOrEqualTo(165_000L);
            assertThat(com.game.match.spectate.SpectateVisibilityPolicy.decide(
                    com.game.proto.match.MatchMode.MATCH_MODE_PVP_TEAM, null, null, ctx))
                    .isEqualTo(com.game.proto.eSpectateVisibility.SPECTATE_VISIBILITY_PUBLIC);
            assertThat(com.game.match.spectate.SpectateVisibilityPolicy.decide(
                    com.game.proto.match.MatchMode.MATCH_MODE_PVP_TEAM, null, null,
                    ctx.toBuilder().setAllowPublicSpectate(false).build()))
                    .isEqualTo(com.game.proto.eSpectateVisibility.SPECTATE_VISIBILITY_PRIVATE);
            assertThat(com.game.match.spectate.SpectateVisibilityPolicy.decide(
                    com.game.proto.match.MatchMode.MATCH_MODE_PVP_TEAM, null, null,
                    ctx.toBuilder().setUseFreePreset(true).build()))
                    .isEqualTo(com.game.proto.eSpectateVisibility.SPECTATE_VISIBILITY_PRIVATE);
            var summary = com.game.match.spectate.SpectateRules.summaryOf(
                    com.game.match.proto.BattlePlacement.newBuilder()
                            .setBattleId(66002L).setMode(MatchModes.PVP_TEAM).setBattleConfigId(5)
                            .setRankedTeamSize(ctx.getTeamSize()).setRankedPeakRatingCenti(ctx.getPeakRatingCenti())
                            .build());
            assertThat(summary.getRankedTeamSize()).isEqualTo(1);
            assertThat(summary.getRankedPeakRatingCenti()).isEqualTo(ctx.getPeakRatingCenti());
            assertThat(ctx.getRivalryEncountersList())
                    .anySatisfy(r -> {
                        assertThat(r.getPlayerId()).isEqualTo(p1);
                        assertThat(r.getOpponentId()).isEqualTo(p2);
                        assertThat(r.getOpponentName()).isEqualTo("玄烈");
                        assertThat(r.getWins()).isZero();
                        assertThat(r.getLosses()).isEqualTo(1);
                        assertThat(r.getRevengeTarget()).isTrue();
                    });

            // 第 2 局：p1 复仇成功，结算落盘 revenged_rival=true，且 66001 / 66002 进入名局公开回放白名单
            now.addAndGet(10_000L);
            BattleResultEvent b2 = b1.toBuilder()
                    .setBattleId(66002L)
                    .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN)
                    .setSettlements(0, b1.getSettlements(0).toBuilder().setBattleId(66002L))
                    .setSettlements(1, b1.getSettlements(1).toBuilder().setBattleId(66002L))
                    .build();
            assertThat(ratingStore.apply(b2).outcome()).isEqualTo(RatingStore.Outcome.APPLIED);

            RankedBattleSettlementView v1 = rankedStore.getBattleResult(p1, 66002L).orElseThrow();
            assertThat(v1.getRevengedRival()).isTrue();
            assertThat(v1.getRevengedOpponentName()).isEqualTo("玄烈");
            assertThat(v1.getRevengedWinsAfter()).isEqualTo(1);
            assertThat(v1.getRevengedLossesAfter()).isEqualTo(1);

            RankedService service = new RankedService(rankedStore, TABLES, now::get);
            GetRankedProfileResponse prof1 = service.getProfile(p1, GetRankedProfileRequest.getDefaultInstance());
            assertThat(prof1.getTopRivalsList()).hasSize(1);
            assertThat(prof1.getTopRivals(0).getOpponentName()).isEqualTo("玄烈");
            assertThat(prof1.getFeaturedBuildsList()).hasSize(2);
            assertThat(rankedStore.isFeaturedRankedReplayAllowed(66001L)).isTrue();
            assertThat(rankedStore.isFeaturedRankedReplayAllowed(66002L)).isTrue();
            assertThat(rankedStore.isFeaturedRankedReplayAllowed(999999L)).isFalse();
            // 66001 / 66002 各自作为流派代表战报，ref_count 从初始 2 护航至 3。
            assertThat(refCount(db, 66001L)).isEqualTo(3);
            assertThat(refCount(db, 66002L)).isEqualTo(3);

            // 第 3 局：p1 同流派以更高分再次获胜（66003L），替换代表局时 66003 ref_count +1(=3)、旧代表局 66002 ref_count -1(=2)。
            now.addAndGet(10_000L);
            BattleResultEvent b3 = b2.toBuilder()
                    .setBattleId(66003L)
                    .setSettlements(0, b2.getSettlements(0).toBuilder().setBattleId(66003L))
                    .setSettlements(1, b2.getSettlements(1).toBuilder().setBattleId(66003L))
                    .build();
            assertThat(ratingStore.apply(b3).outcome()).isEqualTo(RatingStore.Outcome.APPLIED);
            assertThat(rankedStore.isFeaturedRankedReplayAllowed(66003L)).isTrue();
            assertThat(rankedStore.isFeaturedRankedReplayAllowed(66002L)).isFalse();
            assertThat(refCount(db, 66003L)).isEqualTo(3);
            assertThat(refCount(db, 66002L)).isEqualTo(2);

            // 无战报正文的胜局 66004 不得篡改 featured_battle_id，避免名局回放 404。
            now.addAndGet(10_000L);
            BattleResultEvent b4NoReplay = b2.toBuilder()
                    .setBattleId(66004L)
                    .setSettlements(0, b2.getSettlements(0).toBuilder().setBattleId(66004L))
                    .setSettlements(1, b2.getSettlements(1).toBuilder().setBattleId(66004L))
                    .build();
            assertThat(ratingStore.apply(b4NoReplay).outcome()).isEqualTo(RatingStore.Outcome.APPLIED);
            assertThat(rankedStore.isFeaturedRankedReplayAllowed(66004L)).isFalse();
            assertThat(rankedStore.isFeaturedRankedReplayAllowed(66003L)).isTrue();

            // 论剑 V7「不服再战」权威防伪：10 分钟内真实 1V1 对手通过；陌生人、超时、未知局均拒绝。
            assertThat(rankedStore.validateRankedRematchSource(66003L, p1, p2, 600_000L)).isTrue();
            assertThat(rankedStore.validateRankedRematchSource(66003L, p2, p1, 600_000L)).isTrue();
            assertThat(rankedStore.validateRankedRematchSource(66003L, p1, 60099L, 600_000L)).isFalse();
            assertThat(rankedStore.validateRankedRematchSource(999999L, p1, p2, 600_000L)).isFalse();
            now.addAndGet(600_001L);
            assertThat(rankedStore.validateRankedRematchSource(66003L, p1, p2, 600_000L)).isFalse();

            // 删角收尾：清空注销角色在 featured_build 与对手宿敌行的展示名，并整删四张论剑个人表及本人宿敌行。
            rankedStore.savePreset(p2, 101, 201, TABLES.ruleVersion());
            try (java.sql.Connection c = db.dataSource.getConnection()) {
                c.setAutoCommit(false);
                RankedStore.purgePlayerRankedData(c, p2);
                c.commit();
            }
            GetRankedProfileResponse prof1AfterPurge = service.getProfile(p1, GetRankedProfileRequest.getDefaultInstance());
            assertThat(prof1AfterPurge.getTopRivalsList()).hasSize(1);
            assertThat(prof1AfterPurge.getTopRivals(0).getOpponentName()).isEmpty();
            GetRankedProfileResponse prof2AfterPurge = service.getProfile(p2, GetRankedProfileRequest.getDefaultInstance());
            assertThat(prof2AfterPurge.getTopRivalsList()).isEmpty();
            assertThat(countRows(db, "match_ranked_player_prefs", p2)).isZero();
            assertThat(countRows(db, "match_ranked_player_season", p2)).isZero();
            assertThat(countRows(db, "match_ranked_reward", p2)).isZero();
            assertThat(countRows(db, "match_ranked_battle", p2)).isZero();
            assertThat(countRows(db, "match_ranked_battle", p1)).isPositive();

            // 换季封榜释放旧季全部代表局 ref_count（66001 / 66003 均从 3 回到 2）。
            rankedStore.sealSeasonAndOpenNext(1, Map.of(), null);
            assertThat(refCount(db, 66001L)).isEqualTo(2);
            assertThat(refCount(db, 66003L)).isEqualTo(2);
            assertThat(rankedStore.isFeaturedRankedReplayAllowed(66003L)).isFalse();
        }
    }

    private static int countRows(RatingTestDatabase db, String table, long playerId) throws Exception {
        try (java.sql.Connection c = db.dataSource.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE player_id=?")) {
            ps.setObject(1, com.game.pbmysql.PbMysql.uint64(playerId));
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    private static int refCount(RatingTestDatabase db, long battleId) throws Exception {
        try (java.sql.Connection c = db.dataSource.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement("SELECT ref_count FROM match_battle_report WHERE battle_id=?")) {
            ps.setObject(1, com.game.pbmysql.PbMysql.uint64(battleId));
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }
}
