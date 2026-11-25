package com.game.scene.season;

import static com.game.table.SeasonErrorTip.season_error.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.player.store.state.PlayerState;
import com.game.proto.BattleSettlementData;
import com.game.proto.CatchupSeasonSealRequest;
import com.game.proto.CatchupSeasonSealResponse;
import com.game.proto.EquipSeasonSealRequest;
import com.game.proto.EquipSeasonSealResponse;
import com.game.proto.GetSeasonStateRequest;
import com.game.proto.GetSeasonStateResponse;
import com.game.proto.ListSkillsRequest;
import com.game.proto.ListSkillsResponse;
import com.game.proto.MessageContent;
import com.game.proto.UpgradeSkillRequest;
import com.game.proto.UpgradeSkillResponse;
import com.game.proto.eBattleOutcome;
import com.game.proto.match.MatchMode;
import com.game.scene.audit.AssetAudit;
import com.game.scene.battle.BattleFixture;
import com.game.scene.battle.BattleSnapshots;
import com.game.scene.player.Wallet;
import com.game.scene.skill.ClassSkillCatalog;
import com.game.scene.skill.ClassSkillService;
import com.game.scene.testing.Contracts;
import com.game.scene.world.PlayerData;
import com.game.scene.world.PlayerRepository.ProgressResult;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.Vec3;
import com.game.table.SeasonRuleTable;
import com.game.table.SeasonSealTable;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 赛季技能与单槽赛季印（453–455）端到端测试。
 */
class SeasonFeatureAndServiceTest {

    private static final String SERVICE = "SceneSeasonClientPlayer";

    private static SeasonRuleTable firstEnabledRule() {
        return BattleFixture.CONFIG.seasonRule().all().stream()
                .filter(SeasonRuleTable::getEnabled)
                .findFirst()
                .orElseThrow();
    }

    private static void ensureActiveSeason(BattleFixture f) {
        SeasonRuleTable rule = firstEnabledRule();
        if (f.seasonTables.activeSeason(f.clock.epochMillis()) == null) {
            f.clock.setEpochMillis(rule.getStartsAtMs() + 1_000L);
        }
    }

    private static ScenePlayer enterLevelPlayer(BattleFixture f, int sessionId, long playerId, int level) {
        f.repo.put(new PlayerData(playerId, 1, 3, 1, "look-" + playerId, level, f.scene1.configId(),
                new Vec3(5, 5, 0), PlayerState.getDefaultInstance(), "赛季测试" + playerId));
        ScenePlayer p = f.reenter(sessionId, playerId, 1, f.scene1);
        f.drain();
        f.sink.clear();
        f.locks.clearCalls();
        return p;
    }

    private static MessageContent lastReply(BattleFixture f, ScenePlayer p, String service, String method, long reqId) {
        int msgId = Contracts.REGISTRY.requireId(service, method);
        return f.pushes(p, msgId).stream().filter(m -> m.getId() == reqId).findFirst().orElseThrow();
    }

    @Test
    void 查询与切换赛季印453和454_战外免费切换并校验异常码() throws Exception {
        BattleFixture f = new BattleFixture();
        ensureActiveSeason(f);
        SeasonRuleTable rule = f.seasonTables.activeSeason(f.clock.epochMillis());
        assertThat(rule).isNotNull();
        List<SeasonSealTable> seals = f.seasonTables.validSealsOf(rule.getId());
        assertThat(seals.size()).isGreaterThanOrEqualTo(2);
        int otherSeal = seals.stream().mapToInt(SeasonSealTable::getId)
                .filter(id -> id != rule.getDefaultSealId()).findFirst().orElseThrow();

        ScenePlayer player = enterLevelPlayer(f, 11, 1001L, 30);
        long reqId = f.request(player, SERVICE, "GetSeasonState", GetSeasonStateRequest.getDefaultInstance());
        GetSeasonStateResponse stateResp = GetSeasonStateResponse.parseFrom(
                lastReply(f, player, SERVICE, "GetSeasonState", reqId).getSerializedMessage());
        assertThat(stateResp.getErrorMessage().getId()).isZero();
        assertThat(stateResp.getState().getAvailable()).isTrue();
        assertThat(stateResp.getState().getSeasonId()).isEqualTo(rule.getId());
        assertThat(stateResp.getState().getEquippedSealId()).isEqualTo(rule.getDefaultSealId());
        assertThat(stateResp.getState().getSealsCount()).isEqualTo(seals.size());
        assertThat(stateResp.getState().getSeasonSkillsCount()).isGreaterThan(0);

        // 切到已装备印 -> kSeasonSealAlreadyEquipped
        long dupReq = f.request(player, SERVICE, "EquipSeasonSeal", EquipSeasonSealRequest.newBuilder()
                .setSeasonId(rule.getId()).setSealId(rule.getDefaultSealId()).build());
        EquipSeasonSealResponse dupResp = EquipSeasonSealResponse.parseFrom(
                lastReply(f, player, SERVICE, "EquipSeasonSeal", dupReq).getSerializedMessage());
        assertThat(dupResp.getErrorMessage().getId()).isEqualTo(kSeasonSealAlreadyEquipped_VALUE);

        // 非法赛季 -> kSeasonMismatch
        long misReq = f.request(player, SERVICE, "EquipSeasonSeal", EquipSeasonSealRequest.newBuilder()
                .setSeasonId(rule.getId() + 99).setSealId(otherSeal).build());
        EquipSeasonSealResponse misResp = EquipSeasonSealResponse.parseFrom(
                lastReply(f, player, SERVICE, "EquipSeasonSeal", misReq).getSerializedMessage());
        assertThat(misResp.getErrorMessage().getId()).isEqualTo(kSeasonMismatch_VALUE);

        // 合法切印 -> 0，立即变更装备效果中的赛季印并推属性面板
        f.sink.clear();
        long okReq = f.request(player, SERVICE, "EquipSeasonSeal", EquipSeasonSealRequest.newBuilder()
                .setSeasonId(rule.getId()).setSealId(otherSeal).build());
        EquipSeasonSealResponse okResp = EquipSeasonSealResponse.parseFrom(
                lastReply(f, player, SERVICE, "EquipSeasonSeal", okReq).getSerializedMessage());
        assertThat(okResp.getErrorMessage().getId()).isZero();
        assertThat(okResp.getState().getEquippedSealId()).isEqualTo(otherSeal);
        assertThat(player.attributes().equipment().effects().getSeasonSeal().getSealId()).isEqualTo(otherSeal);
        int notifyPanel = Contracts.REGISTRY.requireId("SceneAttributeClientPlayer", "NotifyAttributePanelChanged");
        assertThat(f.pushes(player, notifyPanel)).hasSize(1);
    }

    @Test
    void 追赶455_幂等重放_报价校验_一键补满与轻属性只加气血防御() throws Exception {
        BattleFixture f = new BattleFixture();
        ensureActiveSeason(f);
        SeasonRuleTable rule = f.seasonTables.activeSeason(f.clock.epochMillis());
        ScenePlayer player = enterLevelPlayer(f, 11, 1001L, 40);

        long baseHp = player.attributes().derived().maxHealth();
        long baseDef = player.attributes().derived().defense();
        long baseSpd = player.attributes().derived().speed();
        long baseCrit = player.attributes().combat().getPhysicalCritRate();

        // 灵石不足 -> kSeasonCatchupCurrencyNotEnough
        long poorReq = f.request(player, SERVICE, "CatchupSeasonSeal", CatchupSeasonSealRequest.newBuilder()
                .setClientRequestId(101L)
                .setSeasonId(rule.getId())
                .setPointsToBuy(2)
                .setExpectedCurrencyType(rule.getCatchupCurrencyType())
                .setExpectedTotalPrice(rule.getCatchupPricePerPoint() * 2L)
                .build());
        CatchupSeasonSealResponse poorResp = CatchupSeasonSealResponse.parseFrom(
                lastReply(f, player, SERVICE, "CatchupSeasonSeal", poorReq).getSerializedMessage());
        assertThat(poorResp.getErrorMessage().getId()).isEqualTo(kSeasonCatchupCurrencyNotEnough_VALUE);

        // 发放足够货币
        long grant = rule.getCatchupPricePerPoint() * (long) rule.getSealMaxPoints() * 2L;
        assertThat(f.currency.add(player, rule.getCatchupCurrencyType(), grant, AssetAudit.Reason.GM_GRANT).ok()).isTrue();

        // 报价不符 -> kSeasonCatchupPriceChanged
        long badPriceReq = f.request(player, SERVICE, "CatchupSeasonSeal", CatchupSeasonSealRequest.newBuilder()
                .setClientRequestId(102L)
                .setSeasonId(rule.getId())
                .setPointsToBuy(2)
                .setExpectedCurrencyType(rule.getCatchupCurrencyType())
                .setExpectedTotalPrice(rule.getCatchupPricePerPoint() * 2L + 1)
                .build());
        CatchupSeasonSealResponse badPriceResp = CatchupSeasonSealResponse.parseFrom(
                lastReply(f, player, SERVICE, "CatchupSeasonSeal", badPriceReq).getSerializedMessage());
        assertThat(badPriceResp.getErrorMessage().getId()).isEqualTo(kSeasonCatchupPriceChanged_VALUE);

        // 精确买 3 点成功
        CatchupSeasonSealRequest buy3 = CatchupSeasonSealRequest.newBuilder()
                .setClientRequestId(103L)
                .setSeasonId(rule.getId())
                .setPointsToBuy(3)
                .setExpectedCurrencyType(rule.getCatchupCurrencyType())
                .setExpectedTotalPrice(rule.getCatchupPricePerPoint() * 3L)
                .build();
        long okReq = f.request(player, SERVICE, "CatchupSeasonSeal", buy3);
        CatchupSeasonSealResponse okResp = CatchupSeasonSealResponse.parseFrom(
                lastReply(f, player, SERVICE, "CatchupSeasonSeal", okReq).getSerializedMessage());
        assertThat(okResp.getErrorMessage().getId()).isZero();
        assertThat(okResp.getReplayed()).isFalse();
        assertThat(okResp.getAddedPoints()).isEqualTo(3);
        assertThat(okResp.getSpentCurrency()).isEqualTo(rule.getCatchupPricePerPoint() * 3L);
        assertThat(okResp.getState().getProgressPoints()).isEqualTo(3);

        // 战斗中同 client_request_id 同内容重放 -> 仍命中账本回成功且 replayed=true，不重复扣费
        f.fighting(player.playerId(), 8001L);
        long balanceBeforeReplay = player.wallet().balance(rule.getCatchupCurrencyType());
        long replayReq = f.request(player, SERVICE, "CatchupSeasonSeal", buy3);
        CatchupSeasonSealResponse replayResp = CatchupSeasonSealResponse.parseFrom(
                lastReply(f, player, SERVICE, "CatchupSeasonSeal", replayReq).getSerializedMessage());
        assertThat(replayResp.getErrorMessage().getId()).isZero();
        assertThat(replayResp.getReplayed()).isTrue();
        assertThat(replayResp.getAddedPoints()).isEqualTo(3);
        assertThat(player.wallet().balance(rule.getCatchupCurrencyType())).isEqualTo(balanceBeforeReplay);

        // 同 client_request_id 不同内容 -> kInvalidParameter (1005)
        long mismatchReq = f.request(player, SERVICE, "CatchupSeasonSeal", buy3.toBuilder().setPointsToBuy(4).build());
        CatchupSeasonSealResponse mismatchResp = CatchupSeasonSealResponse.parseFrom(
                lastReply(f, player, SERVICE, "CatchupSeasonSeal", mismatchReq).getSerializedMessage());
        assertThat(mismatchResp.getErrorMessage().getId()).isEqualTo(1005);

        // 解冻后一键补满（points_to_buy=0）
        f.deliver(BattleFixture.settlement(player.playerId(), 8001L, 0).build());
        f.drain();
        f.world.saveDuePlayers(1);
        f.completeSaves(ProgressResult.SAVED);

        int remaining = rule.getSealMaxPoints() - 3;
        long fillReq = f.request(player, SERVICE, "CatchupSeasonSeal", CatchupSeasonSealRequest.newBuilder()
                .setClientRequestId(104L)
                .setSeasonId(rule.getId())
                .setPointsToBuy(0)
                .setExpectedCurrencyType(rule.getCatchupCurrencyType())
                .setExpectedTotalPrice(rule.getCatchupPricePerPoint() * (long) remaining)
                .build());
        CatchupSeasonSealResponse fillResp = CatchupSeasonSealResponse.parseFrom(
                lastReply(f, player, SERVICE, "CatchupSeasonSeal", fillReq).getSerializedMessage());
        assertThat(fillResp.getErrorMessage().getId()).isZero();
        assertThat(fillResp.getAddedPoints()).isEqualTo(remaining);
        assertThat(fillResp.getState().getProgressPoints()).isEqualTo(rule.getSealMaxPoints());
        assertThat(fillResp.getState().getCurrentMaxHealthBps()).isEqualTo(rule.getMaxHealthBpsCap());
        assertThat(fillResp.getState().getCurrentDefenseBps()).isEqualTo(rule.getDefenseBpsCap());

        // 轻属性只加成气血与防御，速度与暴击严格零变化
        assertThat(player.attributes().derived().maxHealth()).isGreaterThan(baseHp);
        assertThat(player.attributes().derived().defense()).isGreaterThan(baseDef);
        assertThat(player.attributes().derived().speed()).isEqualTo(baseSpd);
        assertThat(player.attributes().combat().getPhysicalCritRate()).isEqualTo(baseCrit);

        // 满级再买 -> kSeasonSealAlreadyMax
        long maxReq = f.request(player, SERVICE, "CatchupSeasonSeal", CatchupSeasonSealRequest.newBuilder()
                .setClientRequestId(105L)
                .setSeasonId(rule.getId())
                .setPointsToBuy(1)
                .setExpectedCurrencyType(rule.getCatchupCurrencyType())
                .setExpectedTotalPrice(rule.getCatchupPricePerPoint())
                .build());
        CatchupSeasonSealResponse maxResp = CatchupSeasonSealResponse.parseFrom(
                lastReply(f, player, SERVICE, "CatchupSeasonSeal", maxReq).getSerializedMessage());
        assertThat(maxResp.getErrorMessage().getId()).isEqualTo(kSeasonSealAlreadyMax_VALUE);
    }

    @Test
    void 正规战斗结算累计赛季进度点_逃跑不加分且达上限夹取() {
        BattleFixture f = new BattleFixture();
        ensureActiveSeason(f);
        SeasonRuleTable rule = f.seasonTables.activeSeason(f.clock.epochMillis());
        ScenePlayer player = enterLevelPlayer(f, 11, 1001L, 30);

        // 1. PVE 胜利 -> +pveWinPoints
        BattleSettlementData pveWin = BattleFixture.settlement(player.playerId(), 9001L, 0)
                .setMatchMode(MatchMode.MATCH_MODE_PVE_SOLO_VALUE)
                .setPlayerTeamIndex(0)
                .setTotalRounds(3)
                .build();
        assertThat(f.settlements.apply(f.world, player, pveWin).outcome())
                .isEqualTo(com.game.scene.battle.BattleSettlementService.Outcome.APPLIED);
        assertThat(player.season().record(rule.getId()).getProgressPoints()).isEqualTo(rule.getPveWinPoints());
        assertThat(player.battleResults().entries().getFirst().getSeasonId()).isEqualTo(rule.getId());

        // 2. PVP 胜利 -> +pvpWinPoints
        BattleSettlementData pvpWin = BattleFixture.settlement(player.playerId(), 9002L, 0)
                .setMatchMode(MatchMode.MATCH_MODE_1V1_VALUE)
                .setPlayerTeamIndex(0)
                .setTotalRounds(2)
                .build();
        assertThat(f.settlements.apply(f.world, player, pvpWin).outcome())
                .isEqualTo(com.game.scene.battle.BattleSettlementService.Outcome.APPLIED);
        assertThat(player.season().record(rule.getId()).getProgressPoints())
                .isEqualTo(rule.getPveWinPoints() + rule.getPvpWinPoints());

        // 3. PVP 失败 -> +pvpOtherPoints
        BattleSettlementData pvpLoss = BattleFixture.settlement(player.playerId(), 9003L, 0)
                .setMatchMode(MatchMode.MATCH_MODE_1V1_VALUE)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN)
                .setPlayerTeamIndex(0)
                .setTotalRounds(4)
                .build();
        assertThat(f.settlements.apply(f.world, player, pvpLoss).outcome())
                .isEqualTo(com.game.scene.battle.BattleSettlementService.Outcome.APPLIED);
        int expectedAfterLoss = rule.getPveWinPoints() + rule.getPvpWinPoints() + rule.getPvpOtherPoints();
        assertThat(player.season().record(rule.getId()).getProgressPoints()).isEqualTo(expectedAfterLoss);

        // 4. 逃跑 -> 0
        BattleSettlementData fled = BattleFixture.settlement(player.playerId(), 9004L, 0)
                .setMatchMode(MatchMode.MATCH_MODE_1V1_VALUE)
                .setFled(true)
                .setTotalRounds(1)
                .build();
        assertThat(f.settlements.apply(f.world, player, fled).outcome())
                .isEqualTo(com.game.scene.battle.BattleSettlementService.Outcome.APPLIED);
        assertThat(player.season().record(rule.getId()).getProgressPoints()).isEqualTo(expectedAfterLoss);

        // 5. 正式排位败局防挂机（回合不足 / 手动不足 / 无有效贡献均不给点；达标败局 +pvpOtherPoints；1 回合速胜 +pvpWinPoints）
        assertThat(SeasonService.isEligibleBattle(BattleFixture.settlement(player.playerId(), 9005L, 0)
                .setMatchMode(MatchMode.MATCH_MODE_PVP_TEAM_VALUE)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN)
                .setCompetitiveRanked(true).setParticipated(true)
                .setTotalRounds(2).setManualActionRounds(2).setEffectiveCombatContribution(true).build())).isFalse();
        assertThat(SeasonService.isEligibleBattle(BattleFixture.settlement(player.playerId(), 9006L, 0)
                .setMatchMode(MatchMode.MATCH_MODE_PVP_TEAM_VALUE)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN)
                .setCompetitiveRanked(true).setParticipated(true)
                .setTotalRounds(3).setManualActionRounds(1).setEffectiveCombatContribution(true).build())).isFalse();
        assertThat(SeasonService.isEligibleBattle(BattleFixture.settlement(player.playerId(), 9007L, 0)
                .setMatchMode(MatchMode.MATCH_MODE_PVP_TEAM_VALUE)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN)
                .setCompetitiveRanked(true).setParticipated(true)
                .setTotalRounds(3).setManualActionRounds(2).setEffectiveCombatContribution(false).build())).isFalse();
        assertThat(SeasonService.isEligibleBattle(BattleFixture.settlement(player.playerId(), 9008L, 0)
                .setMatchMode(MatchMode.MATCH_MODE_PVP_TEAM_VALUE)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN)
                .setCompetitiveRanked(true).setParticipated(true)
                .setTotalRounds(3).setManualActionRounds(2).setEffectiveCombatContribution(true).build())).isTrue();
        assertThat(SeasonService.isEligibleBattle(BattleFixture.settlement(player.playerId(), 9009L, 0)
                .setMatchMode(MatchMode.MATCH_MODE_PVP_TEAM_VALUE)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN)
                .setCompetitiveRanked(true).setParticipated(true)
                .setTotalRounds(1).setManualActionRounds(1).setEffectiveCombatContribution(true).build())).isTrue();
    }

    @Test
    void 赛季技能在ListSkills与快照中免费生效_不可修习_换季退场清零() throws Exception {
        BattleFixture f = new BattleFixture();
        ensureActiveSeason(f);
        SeasonRuleTable rule = f.seasonTables.activeSeason(f.clock.epochMillis());
        var seasonalSkills = f.seasonTables.seasonSkillsOf(rule.getId());
        assertThat(seasonalSkills).isNotEmpty();
        int seasonalSkillId = seasonalSkills.getFirst().getId();

        // 赋予真实全量技能目录并让玩家达到解锁等级
        ScenePlayer player = enterLevelPlayer(f, 11, 1001L, 60);
        // 用真实配表构建技能目录（FakeSceneTables 的默认目录仅含通用旧技能，这里验证全量目录行为）
        ScenePlayer realCatalogPlayer = com.game.scene.world.WorldTestAccess.playerWithCatalog(
                player.playerId(), player.classId(), 60,
                BattleFixture.persistentState(player), ClassSkillCatalog.from(BattleFixture.CONFIG));
        f.attributes.initializeOnLoad(realCatalogPlayer);

        ListSkillsResponse book = ClassSkillService.book(realCatalogPlayer, rule.getId());
        assertThat(book.getSkillList().getSkillListList())
                .anySatisfy(s -> {
                    assertThat(s.getSkillTableId()).isEqualTo(seasonalSkillId);
                    assertThat(s.getRank()).isGreaterThan(0);
                });
        assertThat(book.getCatalogList())
                .anySatisfy(entry -> {
                    assertThat(entry.getSkillTableId()).isEqualTo(seasonalSkillId);
                    assertThat(entry.getSeasonId()).isEqualTo(rule.getId());
                    assertThat(entry.getNextRankCost()).isZero();
                    assertThat(entry.getUpgradeBlockTipId()).isEqualTo(kSeasonSkillNoUpgrade_VALUE);
                });

        // 修习赛季技能直接被拒 kSeasonSkillNoUpgrade，金币不扣
        assertThat(f.currency.add(realCatalogPlayer, Wallet.GOLD, 50_000, AssetAudit.Reason.GM_GRANT).ok()).isTrue();
        long goldBefore = f.gold(realCatalogPlayer);
        ClassSkillService skillSvc = new ClassSkillService(f.currency, f.attributes).withActiveSeason(() -> rule.getId());
        UpgradeSkillResponse upResp = skillSvc.upgrade(realCatalogPlayer, UpgradeSkillRequest.newBuilder()
                .setSkillTableId(seasonalSkillId).setExpectedRank(0).setCount(1).build());
        assertThat(upResp.getErrorMessage().getId()).isEqualTo(kSeasonSkillNoUpgrade_VALUE);
        assertThat(f.gold(realCatalogPlayer)).isEqualTo(goldBefore);

        // 战斗快照冻结 active_season_id、赛季技能与赛季印
        f.attributes.equipmentChanged(realCatalogPlayer);
        var snapshot = BattleSnapshots.build(realCatalogPlayer, f.tables, List.of(),
                com.game.proto.BattleRouting.getDefaultInstance(), rule.getId());
        assertThat(snapshot.getActiveSeasonId()).isEqualTo(rule.getId());
        assertThat(snapshot.getSkillRanksMap()).containsKey(seasonalSkillId);
        assertThat(snapshot.getEquipmentEffects().getSeasonSeal().getSeasonId()).isEqualTo(rule.getId());

        // 换季 / 休赛期（显式验证有界窗口结束后赛季技能与赛季印全部退出不残留）
        SeasonRuleTable boundedRule = rule.toBuilder().setStartsAtMs(1_000L).setEndsAtMs(5_000L).build();
        SeasonTables boundedTables = new SeasonTables(
                List.of(boundedRule), BattleFixture.CONFIG.seasonSeal().all(), BattleFixture.CONFIG.skill().all());
        SeasonService boundedSeason = new SeasonService(boundedTables, f.currency, f.attributes, f.clock, f.sceneMetrics);
        f.attributes.withSeason((p, out) -> boundedSeason.collectEffects(p, f.clock.epochMillis(), out),
                boundedSeason::activeSeasonId);
        f.clock.setEpochMillis(2_000L);
        assertThat(boundedSeason.activeSeasonId()).isEqualTo(rule.getId());
        f.clock.setEpochMillis(6_000L);
        assertThat(boundedSeason.activeSeasonId()).isZero();
        f.attributes.equipmentChanged(realCatalogPlayer);
        assertThat(realCatalogPlayer.attributes().equipment().effects().hasSeasonSeal()).isFalse();
        assertThat(realCatalogPlayer.attributes().equipment().effects().getSeasonMaxHealthBps()).isZero();
        ListSkillsResponse offBook = ClassSkillService.book(realCatalogPlayer, 0);
        assertThat(offBook.getSkillList().getSkillListList())
                .noneMatch(s -> s.getSkillTableId() == seasonalSkillId);
        assertThat(offBook.getCatalogList())
                .noneMatch(entry -> entry.getSkillTableId() == seasonalSkillId);
    }

    @Test
    void 赛季配置存在重叠启用窗口时failClosed() {
        SeasonRuleTable r1 = firstEnabledRule();
        SeasonRuleTable overlap = r1.toBuilder().setId(r1.getId() + 100).build();
        SeasonSealTable seal2 = BattleFixture.CONFIG.seasonSeal().all().getFirst().toBuilder()
                .setId(999).setSeasonId(overlap.getId()).build();
        overlap = overlap.toBuilder().setDefaultSealId(999).build();
        var seals = new java.util.ArrayList<>(BattleFixture.CONFIG.seasonSeal().all());
        seals.add(seal2);

        SeasonTables bad = new SeasonTables(List.of(r1, overlap), seals, BattleFixture.CONFIG.skill().all());
        assertThat(bad.isValid()).isFalse();
        assertThat(bad.activeSeason(r1.getStartsAtMs() + 1000L)).isNull();
    }
}
