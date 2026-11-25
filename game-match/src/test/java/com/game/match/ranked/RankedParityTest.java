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
        // 卷一普通/困难首领试炼 101..108
        for (int dungeonId = 101; dungeonId <= 108; dungeonId++) {
            var ctx = TABLES.trialContextFor(MatchModes.PVE_SOLO, dungeonId, null, null);
            assertThat(ctx).as("dungeon=%d", dungeonId).isPresent();
            assertThat(ctx.get().getJournalEntryId()).isPositive();
            assertThat(ctx.get().getTrialRuleVersion()).isPositive();
            assertThat(ctx.get().getSeasonId()).isZero();
            assertThat(ctx.get().getGuardMonsterTableId()).isPositive();
        }
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
}
