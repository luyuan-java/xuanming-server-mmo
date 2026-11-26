package com.game.scene.battle;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.player.store.state.BattleEconomyLedgerState;
import com.game.player.store.state.ClientRequestLedgerState;
import com.game.player.store.state.PlayerProgressionState;
import com.game.proto.AttributePanelInfo;
import com.game.proto.BattleCurrencyGain;
import com.game.proto.BattleSettlementData;
import com.game.proto.PlayerBattleResult;
import com.game.proto.eBattleOutcome;
import com.game.proto.match.MatchMode;
import com.game.scene.player.PlayerClientRequestLedger;
import com.game.scene.player.PlayerProgression;
import com.game.scene.player.Wallet;
import com.game.scene.progression.BattleProgressionRules;
import com.game.scene.progression.BattleProgressionTables;
import com.game.scene.testing.Contracts;
import com.game.scene.world.ScenePlayer;
import com.google.protobuf.UnknownFieldSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * G1 角色平滑升级曲线（1→85）与全玩法经验/银两周软上限 Java 双版对账单测。
 * 与 C++ {@code PlayerBattleProgressionTest} 逐场景对齐，并包含持久账本未知字段往返证据。
 */
class BattleProgressionParityTest {

    private static BattleFixture newProgressionFixture() {
        BattleFixture f = new BattleFixture();
        BattleProgressionTables progTables = BattleProgressionTables.from(BattleFixture.CONFIG);
        progTables.validate();
        f.attributes.withProgressionTables(progTables);
        f.settlements.withProgression(f.attributes, progTables);
        f.battle.withProgressionPush(
                f.attributes,
                Contracts.REGISTRY.requireId("SceneAttributeClientPlayer", "NotifyAttributePanelChanged"),
                f.petListChangedId);
        return f;
    }

    private static BattleSettlementData.Builder baseSettlement(ScenePlayer player, long battleId) {
        return BattleSettlementData.newBuilder()
                .setPlayerId(player.playerId())
                .setBattleId(battleId)
                .setPlayerTeamIndex(0)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN)
                .setTotalRounds(4)
                .setManualActionRounds(3)
                .setEffectiveCombatContribution(true)
                .setHealth(100)
                .setMana(50);
    }

    @Test
    void exportedTablesPassValidationAndMatchMilestones() {
        BattleProgressionTables tables = BattleProgressionTables.from(BattleFixture.CONFIG);
        tables.validate();
        assertThat(tables.levelExpRows()).hasSize(85);
        assertThat(tables.levelExpRows().get(0).requiredTotalExp()).isEqualTo(0L);        // Lv1
        assertThat(tables.levelExpRows().get(3).requiredTotalExp()).isEqualTo(900L);      // Lv4
        assertThat(tables.levelExpRows().get(9).requiredTotalExp()).isEqualTo(4500L);     // Lv10
        assertThat(tables.levelExpRows().get(29).requiredTotalExp()).isEqualTo(20000L);   // Lv30
        assertThat(tables.levelExpRows().get(59).requiredTotalExp()).isEqualTo(70000L);   // Lv60
        assertThat(tables.levelExpRows().get(79).requiredTotalExp()).isEqualTo(210000L);  // Lv80
        assertThat(tables.levelExpRows().get(84).requiredTotalExp()).isEqualTo(300000L);  // Lv85
        assertThat(tables.weeklySilverRule().softCap()).isEqualTo(30000L);
        assertThat(tables.weeklySilverRule().diminishedRatePermyriad()).isEqualTo(2000);
        assertThat(tables.weeklySilverRule().hardCap()).isEqualTo(45000L);
    }

    private static ScenePlayer enterAtLevel(BattleFixture f, int sessionId, long playerId, int level) {
        ScenePlayer player = f.enter(sessionId, playerId);
        player.setLevel(level);
        f.attributes.initializeOnLoad(player);
        return player;
    }

    @Test
    void firstPveSoloBossWinJumpsLv1ToLv4UpdatesPanelAndResults() {
        BattleFixture f = newProgressionFixture();
        ScenePlayer player = enterAtLevel(f, 1, 1001L, 1);

        BattleSettlementData settlement = baseSettlement(player, 7101L)
                .setMatchMode(MatchMode.MATCH_MODE_PVE_SOLO_VALUE)
                .setBattleDifficulty(1)
                .build();
        var res = f.settlements.apply(f.world, player, settlement);
        assertThat(res.outcome()).isEqualTo(BattleSettlementService.Outcome.APPLIED);

        assertThat(player.level()).isEqualTo(4);
        assertThat(player.progression().totalExp()).isEqualTo(1000L);
        assertThat(player.wallet().balance(Wallet.GOLD)).isEqualTo(600L);

        AttributePanelInfo panel = f.attributes.buildPanel(player);
        assertThat(panel.getLevel()).isEqualTo(4);
        assertThat(panel.getTotalExp()).isEqualTo(1000L);
        assertThat(panel.getCurrentLevelExp()).isEqualTo(100L);
        assertThat(panel.getNextLevelExp()).isEqualTo(500L);
        assertThat(panel.getIsMaxLevel()).isFalse();

        var results = player.battleResults().visibleNewestFirst(Set.of());
        assertThat(results).hasSize(1);
        PlayerBattleResult r = results.get(0);
        assertThat(r.getLevelBefore()).isEqualTo(1);
        assertThat(r.getLevelAfter()).isEqualTo(4);
        assertThat(r.getExpGain()).isEqualTo(1000L);
        assertThat(r.getTotalExpAfter()).isEqualTo(1000L);
        assertThat(r.getNextLevelTotalExp()).isEqualTo(1400L);
        assertThat(r.getBattleSilverWeeklyUsed()).isEqualTo(600L);
        assertThat(r.getBattleSilverWeeklySoftCap()).isEqualTo(30000L);
        assertThat(r.getBattleSilverDiminished()).isFalse();
        assertThat(r.getCurrenciesList()).hasSize(1);
        assertThat(r.getCurrencies(0).getAmount()).isEqualTo(600L);
    }

    @Test
    void isolatedPvpTeamWinGrantsProgressionWithoutBattleDamage() {
        BattleFixture f = newProgressionFixture();
        ScenePlayer player = enterAtLevel(f, 2, 1002L, 1);
        long hpBefore = player.attributes().health();
        long mpBefore = player.attributes().mana();

        BattleSettlementData settlement = baseSettlement(player, 7102L)
                .setMatchMode(MatchMode.MATCH_MODE_PVP_TEAM_VALUE)
                .setUseFreePreset(true)
                .setHealth(1)
                .setMana(0)
                .build();
        var res = f.settlements.apply(f.world, player, settlement);
        assertThat(res.outcome()).isEqualTo(BattleSettlementService.Outcome.APPLIED);

        assertThat(player.level()).isEqualTo(4);
        assertThat(player.progression().totalExp()).isEqualTo(1300L);
        assertThat(player.wallet().balance(Wallet.GOLD)).isEqualTo(1000L);
        assertThat(player.attributes().health()).isGreaterThanOrEqualTo(hpBefore);
        assertThat(player.attributes().mana()).isGreaterThanOrEqualTo(mpBefore);
    }

    @Test
    void unqualifiedPvpLossChallengeFestivalBeastGrantZeroAndQualifiedLossGrantsReward() {
        BattleFixture f = newProgressionFixture();
        ScenePlayer player = enterAtLevel(f, 3, 1003L, 1);

        // 1. 回合不足 3 的 PVP 负局：0 经验 0 银两
        var r1 = f.settlements.apply(f.world, player, baseSettlement(player, 7201L)
                .setMatchMode(MatchMode.MATCH_MODE_5V5_VALUE)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN)
                .setTotalRounds(2)
                .build());
        assertThat(r1.outcome()).isEqualTo(BattleSettlementService.Outcome.APPLIED);
        assertThat(player.progression().totalExp()).isZero();
        assertThat(player.wallet().balance(Wallet.GOLD)).isZero();

        // 2. 切磋胜局：0 经验 0 银两
        var r2 = f.settlements.apply(f.world, player, baseSettlement(player, 7202L)
                .setMatchMode(MatchMode.MATCH_MODE_PVP_CHALLENGE_VALUE)
                .build());
        assertThat(r2.outcome()).isEqualTo(BattleSettlementService.Outcome.APPLIED);
        assertThat(player.progression().totalExp()).isZero();
        assertThat(player.wallet().balance(Wallet.GOLD)).isZero();

        // 3. 瑞兽迎春：0 经验 0 银两
        BattleSettlementData.Builder fbBuilder = baseSettlement(player, 7203L)
                .setMatchMode(MatchMode.MATCH_MODE_PVE_TEAM_VALUE);
        fbBuilder.getFestivalBeastBuilder().setActivityInstanceId(99);
        var r3 = f.settlements.apply(f.world, player, fbBuilder.build());
        assertThat(r3.outcome()).isEqualTo(BattleSettlementService.Outcome.APPLIED);
        assertThat(player.progression().totalExp()).isZero();
        assertThat(player.wallet().balance(Wallet.GOLD)).isZero();

        // 4. 达标 5V5 负局：+800 经验 +500 银两
        var r4 = f.settlements.apply(f.world, player, baseSettlement(player, 7204L)
                .setMatchMode(MatchMode.MATCH_MODE_5V5_VALUE)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN)
                .setTotalRounds(4)
                .setManualActionRounds(2)
                .setEffectiveCombatContribution(true)
                .build());
        assertThat(r4.outcome()).isEqualTo(BattleSettlementService.Outcome.APPLIED);
        assertThat(player.progression().totalExp()).isEqualTo(800L);
        assertThat(player.wallet().balance(Wallet.GOLD)).isEqualTo(500L);
    }

    @Test
    void guildWarGrantsExpOnlyWithZeroSilver() {
        BattleFixture f = newProgressionFixture();
        ScenePlayer player = enterAtLevel(f, 4, 1004L, 1);

        var r1 = f.settlements.apply(f.world, player, baseSettlement(player, 7301L)
                .setMatchMode(MatchMode.MATCH_MODE_GUILD_WAR_PVP_VALUE)
                .build());
        assertThat(r1.outcome()).isEqualTo(BattleSettlementService.Outcome.APPLIED);
        assertThat(player.progression().totalExp()).isEqualTo(800L);
        assertThat(player.wallet().balance(Wallet.GOLD)).isZero();

        var r2 = f.settlements.apply(f.world, player, baseSettlement(player, 7302L)
                .setMatchMode(MatchMode.MATCH_MODE_GUILD_WAR_SIEGE_VALUE)
                .build());
        assertThat(r2.outcome()).isEqualTo(BattleSettlementService.Outcome.APPLIED);
        assertThat(player.progression().totalExp()).isEqualTo(1300L);
        assertThat(player.wallet().balance(Wallet.GOLD)).isZero();
    }

    @Test
    void weeklySilverCapSplitsAcrossSoftCapAndClampsAtHardCapWhileExpFull() {
        BattleFixture f = newProgressionFixture();
        BattleProgressionTables tables = BattleProgressionTables.from(BattleFixture.CONFIG);
        ScenePlayer player = enterAtLevel(f, 5, 1005L, 1);

        // 预设本周已用 29,500（置极大周起点防测试时钟触发跨周重置）
        player.clientRequestLedger().setBattleEconomy(BattleEconomyLedgerState.newBuilder()
                .setWeekStartsAtMs(Long.MAX_VALUE)
                .setBattleSilverThisWeek(29500L)
                .build());

        // 困难组队胜（1300 EXP, 1000 原始银两）：500 满额 + 500*20% = 600，账本到 30,100
        BattleSettlementData s1 = baseSettlement(player, 7401L)
                .setMatchMode(MatchMode.MATCH_MODE_PVE_TEAM_VALUE)
                .setBattleDifficulty(2)
                .build();
        assertThat(f.settlements.apply(f.world, player, s1).outcome()).isEqualTo(BattleSettlementService.Outcome.APPLIED);
        assertThat(player.progression().totalExp()).isEqualTo(1300L);
        assertThat(player.wallet().balance(Wallet.GOLD)).isEqualTo(600L);
        assertThat(player.clientRequestLedger().battleEconomy().getBattleSilverThisWeek()).isEqualTo(30100L);
        assertThat(player.battleResults().findByBattleId(7401L).getBattleSilverDiminished()).isTrue();

        // 逼近 45,000 硬封顶：已有 44,900 -> 实得 100 -> 45,000；再胜实得 0 但经验仍满额
        player.clientRequestLedger().setBattleEconomy(player.clientRequestLedger().battleEconomy().toBuilder()
                .setBattleSilverThisWeek(44900L)
                .build());
        assertThat(f.settlements.apply(f.world, player, s1.toBuilder().setBattleId(7402L).build()).outcome())
                .isEqualTo(BattleSettlementService.Outcome.APPLIED);
        assertThat(player.wallet().balance(Wallet.GOLD)).isEqualTo(700L);
        assertThat(player.clientRequestLedger().battleEconomy().getBattleSilverThisWeek()).isEqualTo(45000L);

        assertThat(f.settlements.apply(f.world, player, s1.toBuilder().setBattleId(7403L).build()).outcome())
                .isEqualTo(BattleSettlementService.Outcome.APPLIED);
        assertThat(player.progression().totalExp()).isEqualTo(3900L);
        assertThat(player.wallet().balance(Wallet.GOLD)).isEqualTo(700L);

        // 纯规则验证跨周清零与抗时钟回拨
        BattleEconomyLedgerState cur = BattleEconomyLedgerState.newBuilder()
                .setWeekStartsAtMs(2000L)
                .setBattleSilverThisWeek(45000L)
                .build();
        var rollback = BattleProgressionRules.prepareEconomyLedger(cur, 1000L, s1, 0L, 0L,
                tables.rewardRows(), tables.weeklySilverRule());
        assertThat(rollback.candidateLedger().getWeekStartsAtMs()).isEqualTo(2000L);
        assertThat(rollback.delta().silverGain()).isZero();

        var newWeek = BattleProgressionRules.prepareEconomyLedger(cur, 3000L, s1, 0L, 0L,
                tables.rewardRows(), tables.weeklySilverRule());
        assertThat(newWeek.candidateLedger().getWeekStartsAtMs()).isEqualTo(3000L);
        assertThat(newWeek.delta().silverGain()).isEqualTo(1000L);
        assertThat(newWeek.delta().silverDiminished()).isFalse();
    }

    @Test
    void currencyGainsFixedAndBlockedGoldDoesNotConsumeWeeklyCap() {
        BattleFixture f = newProgressionFixture();
        ScenePlayer player = enterAtLevel(f, 6, 1006L, 1);

        // currency_gains 携带显式银两不再被 Java 丢弃
        BattleSettlementData explicit = baseSettlement(player, 7501L)
                .setMatchMode(MatchMode.MATCH_MODE_UNSPECIFIED_VALUE)
                .addCurrencyGains(BattleCurrencyGain.newBuilder().setCurrencyType(Wallet.GOLD).setAmount(250L).build())
                .build();
        assertThat(f.settlements.apply(f.world, player, explicit).outcome()).isEqualTo(BattleSettlementService.Outcome.APPLIED);
        assertThat(player.wallet().balance(Wallet.GOLD)).isEqualTo(250L);

        // Inbox 路径金币封禁时跳过银两且不消耗本周软上限，经验与升级照常落地（独立玩家避免受上一次 apply 的在途存盘阻塞）
        ScenePlayer blockedPlayer = enterAtLevel(f, 8, 1008L, 1);
        blockedPlayer.wallet().block(Wallet.GOLD);
        BattleSettlementData blockedFight = baseSettlement(blockedPlayer, 7502L)
                .setMatchMode(MatchMode.MATCH_MODE_PVE_SOLO_VALUE)
                .setBattleDifficulty(1)
                .build();
        assertThat(f.settlements.applyInbox(f.world, blockedPlayer, blockedFight).outcome())
                .isEqualTo(BattleSettlementService.Outcome.APPLIED);
        assertThat(blockedPlayer.level()).isEqualTo(4);
        assertThat(blockedPlayer.progression().totalExp()).isEqualTo(1000L);
        assertThat(blockedPlayer.wallet().balance(Wallet.GOLD)).isZero();
        assertThat(blockedPlayer.clientRequestLedger().battleEconomy().getBattleSilverThisWeek()).isZero();
    }

    @Test
    void mixedVersionAndGmHighLevelNeverDowngradeOnExpGain() {
        BattleFixture f = newProgressionFixture();
        BattleProgressionTables tables = BattleProgressionTables.from(BattleFixture.CONFIG);

        // 1. 混版本超上限携带档 (stored=90 > 85) 不降级
        var mixed = BattleProgressionRules.resolveLevelForExp(90, 0L, 1000L, tables.levelExpRows());
        assertThat(mixed).isNotNull();
        assertThat(mixed.storedLevelAfter()).isEqualTo(90);
        assertThat(mixed.levelAfter()).isEqualTo(85);
        assertThat(mixed.leveledUp()).isFalse();

        // 2. GM 调 60 级但 total_exp=0 的号，打赢一局得 1000 EXP 绝不回退到 Lv4
        ScenePlayer player = enterAtLevel(f, 7, 1007L, 60);
        assertThat(f.settlements.apply(f.world, player, baseSettlement(player, 7601L)
                .setMatchMode(MatchMode.MATCH_MODE_PVE_SOLO_VALUE)
                .setBattleDifficulty(1)
                .build()).outcome()).isEqualTo(BattleSettlementService.Outcome.APPLIED);
        assertThat(player.level()).isEqualTo(60);
        assertThat(player.progression().totalExp()).isEqualTo(1000L);
        assertThat(f.attributes.buildPanel(player).getCurrentLevelExp()).isZero();
    }

    @Test
    void ledgerAndProgressionPreserveUnknownFieldsAcrossRestoreAndToState() {
        UnknownFieldSet ledgerUnknown = UnknownFieldSet.newBuilder()
                .addField(991, UnknownFieldSet.Field.newBuilder().addVarint(77L).build())
                .build();
        ClientRequestLedgerState rawLedger = ClientRequestLedgerState.newBuilder()
                .setNextSeq(5L)
                .setBattleEconomy(BattleEconomyLedgerState.newBuilder()
                        .setWeekStartsAtMs(1900000000000L)
                        .setBattleSilverThisWeek(1200L)
                        .setBattleExpThisWeek(2000L)
                        .build())
                .setUnknownFields(ledgerUnknown)
                .build();
        PlayerClientRequestLedger restoredLedger = PlayerClientRequestLedger.restore(rawLedger);
        restoredLedger.setForgeRevision(9L);
        ClientRequestLedgerState savedLedger = restoredLedger.toState();
        assertThat(savedLedger.getUnknownFields().getField(991).getVarintList()).containsExactly(77L);
        assertThat(savedLedger.getBattleEconomy().getBattleSilverThisWeek()).isEqualTo(1200L);

        UnknownFieldSet progUnknown = UnknownFieldSet.newBuilder()
                .addField(992, UnknownFieldSet.Field.newBuilder().addVarint(88L).build())
                .build();
        PlayerProgression restoredProg = PlayerProgression.restore(PlayerProgressionState.newBuilder()
                .setTotalExp(500L)
                .setUnknownFields(progUnknown)
                .build());
        restoredProg.setTotalExp(1500L);
        PlayerProgressionState savedProg = restoredProg.toState();
        assertThat(savedProg.getTotalExp()).isEqualTo(1500L);
        assertThat(savedProg.getUnknownFields().getField(992).getVarintList()).containsExactly(88L);
    }
}
