package com.game.scene.battle;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.player.store.state.PetEntry;
import com.game.player.store.state.PetState;
import com.game.player.store.state.PlayerState;
import com.game.proto.BattlePetSnapshot;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleRouting;
import com.game.proto.BattleSettlementData;
import com.game.proto.RankedMatchContext;
import com.game.proto.RankedPresetSelection;
import com.game.proto.eBattleOutcome;
import com.game.proto.match.MatchMode;
import com.game.scene.equip.EquipmentCraftService;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.WorldTestAccess;
import com.game.table.ConfigTables;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 论剑排位 V1 场景侧规则测试（新人 vs 高付费同预设一致、零宠物临时宝宝、每日材料额度上限与参与门禁）。 */
class RankedPresetRulesTest {

    private static ConfigTables configTables;
    private static SceneBattleTables sceneBattleTables;

    private static final BattleRouting ROUTING = BattleRouting.newBuilder()
            .setSessionId(1).setGateNodeId(1).setGateInstanceId("g1")
            .setSceneNodeId(1).setSceneInstanceId("s1").setZoneId(1).build();

    @BeforeAll
    static void loadTables() {
        configTables = ConfigTables.load(Path.of("../config-data/tables"));
        sceneBattleTables = SceneBattleTables.from(configTables);
    }

    @Test
    void 排位配表校验通过且规范指纹为64位小写Hex() {
        assertThat(sceneBattleTables.ranked()).isNotNull();
        assertThat(sceneBattleTables.ranked().isValid()).isTrue();
        String fp = sceneBattleTables.ranked().configFingerprint();
        assertThat(fp).matches("[0-9a-f]{64}");
        assertThat(RankedPresetRules.computeConfigFingerprint(configTables)).isEqualTo(fp);
    }

    @Test
    void 新人零宠残血账号与高付费满级有宠账号选同预设数值与技能完全一致() {
        // 新人：1 级、零宠物、场外残血 1
        ScenePlayer newbie = WorldTestAccess.player(10001L, 1, 1, "新人", 1, List.of(), "acc1",
                PlayerState.getDefaultInstance());
        newbie.attributes().setHealth(1);
        newbie.attributes().setMana(0);

        // 老号：85 级、跨职业、满血、有出战宝宝
        PlayerState vetState = PlayerState.newBuilder()
                .setPets(PetState.newBuilder()
                        .setActivePetId(88001L)
                        .addPets(PetEntry.newBuilder()
                                .setPetId(88001L)
                                .setPetTableId(1)
                                .setLevel(85)
                                .setName("高配灵宠")
                                .setHealth(9999)
                                .setMana(5000)))
                .build();
        ScenePlayer veteran = WorldTestAccess.player(10002L, 3, 85, "老号", 2, List.of(), "acc2", vetState);

        RankedMatchContext ctx = RankedMatchContext.newBuilder()
                .setSeasonId(1)
                .setRuleVersion(1)
                .setConfigFingerprint(sceneBattleTables.ranked().configFingerprint())
                .setTeamSize(1)
                .build();
        RankedPresetSelection sel = RankedPresetSelection.newBuilder()
                .setCharacterPresetId(101)
                .setPetPresetId(201)
                .build();

        Optional<BattlePlayerSnapshot> nOpt = RankedPresetRules.buildCompetitiveSnapshot(
                newbie, sceneBattleTables, ctx, sel, ROUTING, 1_700_000_000L);
        Optional<BattlePlayerSnapshot> vOpt = RankedPresetRules.buildCompetitiveSnapshot(
                veteran, sceneBattleTables, ctx, sel, ROUTING, 1_700_000_000L);
        assertThat(nOpt).isPresent();
        assertThat(vOpt).isPresent();

        BattlePlayerSnapshot ns = nOpt.get();
        BattlePlayerSnapshot vs = vOpt.get();

        // 六项派生、参考职业、等级、技能等级、动作候选、装备/道具零泄露完全一致
        assertThat(ns.getClassId()).isEqualTo(1).isEqualTo(vs.getClassId());
        assertThat(ns.getLevel()).isEqualTo(30).isEqualTo(vs.getLevel());
        assertThat(ns.getMaxHealth()).isEqualTo(vs.getMaxHealth()).isPositive();
        assertThat(ns.getMaxMana()).isEqualTo(vs.getMaxMana());
        assertThat(ns.getPhysicalAttack()).isEqualTo(vs.getPhysicalAttack()).isPositive();
        assertThat(ns.getMagicAttack()).isEqualTo(vs.getMagicAttack()).isPositive();
        assertThat(ns.getDefense()).isEqualTo(vs.getDefense()).isPositive();
        assertThat(ns.getBaseAttributes()).isEqualTo(vs.getBaseAttributes());
        assertThat(ns.getSkillRanksMap()).isEqualTo(vs.getSkillRanksMap());
        assertThat(ns.getSkillTableIdsList()).isEqualTo(vs.getSkillTableIdsList());
        assertThat(ns.hasEquipmentEffects()).isFalse();
        assertThat(vs.hasEquipmentEffects()).isFalse();
        assertThat(ns.getItemsList()).isEmpty();
        assertThat(vs.getItemsList()).isEmpty();
        assertThat(ns.getPetCaptureSlots()).isZero();
        assertThat(vs.getPetCaptureSlots()).isZero();

        // 宝宝数值、词条、技能完全一致；零宠物用默认外观，有出战宝宝保留外观名但仍是临时竞技宝宝
        assertThat(ns.getPetsCount()).isEqualTo(1);
        assertThat(vs.getPetsCount()).isEqualTo(1);
        BattlePetSnapshot np = ns.getPets(0);
        BattlePetSnapshot vp = vs.getPets(0);
        assertThat(np.getPetId()).isZero();
        assertThat(vp.getPetId()).isZero();
        assertThat(np.getCompetitiveTemporary()).isTrue();
        assertThat(vp.getCompetitiveTemporary()).isTrue();
        assertThat(np.getCombatPetTableId()).isEqualTo(vp.getCombatPetTableId());
        assertThat(np.getMaxHealth()).isEqualTo(vp.getMaxHealth());
        assertThat(np.getMaxMana()).isEqualTo(vp.getMaxMana());
        assertThat(np.getPhysicalAttack()).isEqualTo(vp.getPhysicalAttack());
        assertThat(np.getMagicAttack()).isEqualTo(vp.getMagicAttack());
        assertThat(np.getDefense()).isEqualTo(vp.getDefense());
        assertThat(np.getBaseAttributes()).isEqualTo(vp.getBaseAttributes());
        assertThat(np.getPetCultivation()).isEqualTo(vp.getPetCultivation());
        assertThat(np.getSkillRanksMap()).isEqualTo(vp.getSkillRanksMap());
        assertThat(np.getSkillTableIdsList()).isEqualTo(vp.getSkillTableIdsList());
        assertThat(np.getPetTableId()).isEqualTo(np.getCombatPetTableId());
        assertThat(vp.getPetName()).isEqualTo("高配灵宠");

        // 指纹不匹配 fail-closed 拒绝
        RankedMatchContext badCtx = ctx.toBuilder().setConfigFingerprint("0000").build();
        assertThat(RankedPresetRules.buildCompetitiveSnapshot(newbie, sceneBattleTables, badCtx, sel, ROUTING, 1_700_000_000L))
                .isEmpty();
    }

    @Test
    void 排位材料额度校验参与门禁与PVE共享每日20份上限() {
        ScenePlayer player = WorldTestAccess.player(10003L, 1, 30, "试玩", 1, List.of(), "acc3",
                PlayerState.getDefaultInstance());
        long nowMs = 1_730_000_000_000L;

        // 1. 排位未参与(participated=false)不给材料额度
        BattleSettlementData absent = BattleSettlementData.newBuilder()
                .setBattleId(1L).setPlayerId(10003L).setMatchMode(MatchMode.MATCH_MODE_PVP_TEAM_VALUE)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN).setPlayerTeamIndex(0).setTotalRounds(2)
                .setCompetitiveRanked(true).setSeasonId(1).setParticipated(false).build();
        assertThat(EquipmentCraftService.creditBattleMaterialReward(player, absent, nowMs)).isZero();

        // 2. 两场合格 PVE 胜局占满每日 20 份上限
        BattleSettlementData pveWin = BattleSettlementData.newBuilder()
                .setBattleId(2L).setPlayerId(10003L).setMatchMode(MatchMode.MATCH_MODE_PVE_SOLO_VALUE)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN).setPlayerTeamIndex(0).setTotalRounds(3).build();
        assertThat(EquipmentCraftService.creditBattleMaterialReward(player, pveWin, nowMs)).isEqualTo(10);
        assertThat(EquipmentCraftService.creditBattleMaterialReward(player, pveWin.toBuilder().setBattleId(3L).build(), nowMs)).isEqualTo(10);

        // 3. 日满后再打有效排位局新增额度为 0
        BattleSettlementData rankedValid = absent.toBuilder().setBattleId(4L).setParticipated(true).build();
        assertThat(EquipmentCraftService.creditBattleMaterialReward(player, rankedValid, nowMs)).isZero();
        assertThat(player.clientRequestLedger().materialReward().getAvailableUnits()).isEqualTo(20L);
    }
}
