package com.game.battle.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.BaseAttributesComp;
import com.game.proto.BattleAction;
import com.game.proto.BattleActorState;
import com.game.proto.BattleEventItem;
import com.game.proto.BattlePetSnapshot;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleStateS2C;
import com.game.proto.CreateBattleRequest;
import com.game.proto.TurnResultS2C;
import com.game.proto.eBattleActionType;
import com.game.proto.eBattleActorType;
import com.game.proto.eBattleEventType;
import com.game.proto.match.MatchMode;
import com.game.table.ConfigTables;
import com.game.table.SkillTable;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 首领试炼「周常五行词缀」V1 Java 引擎对账测试（对齐 C++ SeasonBattleTest.BossTrialWeeklyAffix*）。 */
class BossTrialWeeklyAffixBattleEngineTest {

    private static ConfigTables tables;
    private static TableBattleData data;
    private static int dispelBuffSkillId;
    private static int chargeSkillId;

    @BeforeAll
    static void loadTables() {
        tables = ConfigTables.load(Path.of("../config-data/tables"));
        data = new TableBattleData(tables);
        for (SkillTable s : tables.skill().all()) {
            if (s.getBattleModel() != 1 || s.getAbilityKind() != 1 || (s.getCasterMask() & 1) == 0) {
                continue;
            }
            for (int effId : s.getEffectList()) {
                var b = tables.buff().find(effId);
                if (b.isEmpty()) continue;
                if (b.get().getBattleOp() == BattleEffect.DISPEL_BUFF && s.getTargetTeam() == 1 && dispelBuffSkillId == 0) {
                    dispelBuffSkillId = s.getId();
                }
                if (b.get().getBattleOp() == BattleEffect.PREPARE_CAST && s.getTargetTeam() == 1 && chargeSkillId == 0) {
                    chargeSkillId = s.getId();
                }
            }
        }
        assertThat(dispelBuffSkillId).isPositive();
        assertThat(chargeSkillId).isPositive();
    }

    private static BattlePlayerSnapshot player(long id, long hp, long maxMana, long mana, long speed,
                                               long physAtk, long magicAtk, int... skills) {
        BattlePlayerSnapshot.Builder b = BattlePlayerSnapshot.newBuilder()
                .setPlayerId(id)
                .setPlayerName("P" + id)
                .setTeamIndex(0)
                .setLevel(60)
                .setClassId(1)
                .setMaxHealth(hp)
                .setMaxMana(maxMana)
                .setPhysicalAttack(physAtk)
                .setMagicAttack(magicAtk)
                .setDefense(200L)
                .setBaseAttributes(BaseAttributesComp.newBuilder()
                        .setHealth(hp).setMana(mana).setSpeed(speed).setStrength(50).setArmor(20).build());
        for (int sid : skills) {
            b.addSkillTableIds(sid).putSkillRanks(sid, 1);
        }
        return b.build();
    }

    private static TurnBattleEngine startEngine(CreateBattleRequest req) {
        BattleStart res = TurnBattleEngine.start(req, data);
        assertThat(res).isInstanceOf(BattleStart.Started.class);
        return ((BattleStart.Started) res).engine();
    }

    private static BattleActorState findBoss(BattleStateS2C state) {
        return state.getActorsList().stream()
                .filter(a -> a.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_MONSTER && a.getIsBoss())
                .findFirst().orElseThrow();
    }

    private static BattleActorState findGuard(BattleStateS2C state) {
        return state.getActorsList().stream()
                .filter(a -> a.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_MONSTER && !a.getIsBoss())
                .findFirst().orElseThrow();
    }

    @Test
    void 确定性五周轮换_自动冻结_非试炼隔离与非法覆盖拒绝() {
        assertThat(BossTrialWeeklyAffixRules.affixIdForTimestamp(0L)).isEqualTo(BossTrialWeeklyAffixRules.AFFIX_NONE);
        for (long w = 0; w < 6; w++) {
            long ts = BossTrialWeeklyAffixRules.FIRST_MONDAY_05_UTC8_MS + w * BossTrialWeeklyAffixRules.WEEK_MS + 12345L;
            assertThat(BossTrialWeeklyAffixRules.affixIdForTimestamp(ts)).isEqualTo((int) ((w % 5L) + 1L));
        }

        long waterWeekTs = BossTrialWeeklyAffixRules.FIRST_MONDAY_05_UTC8_MS + 2L * BossTrialWeeklyAffixRules.WEEK_MS + 1000L;
        CreateBattleRequest trialReq = CreateBattleRequest.newBuilder()
                .setBattleId(8801L)
                .setBattleConfigId(101)
                .setSeed(42L)
                .setMatchMode(MatchMode.MATCH_MODE_PVE_SOLO_VALUE)
                .setCreatedAtMs(waterWeekTs)
                .addPlayers(player(1001L, 12000L, 1000L, 1000L, 1000L, 1200L, 1200L))
                .build();
        TurnBattleEngine trialEng = startEngine(trialReq);
        assertThat(trialEng.bossWeeklyAffixId()).isEqualTo(BossTrialWeeklyAffixRules.AFFIX_WATER);
        assertThat(trialEng.buildStateSnapshot().getBossWeeklyAffixId()).isEqualTo(BossTrialWeeklyAffixRules.AFFIX_WATER);

        // 非首领试炼（护卫救援 109）恒为 0
        CreateBattleRequest rescueReq = trialReq.toBuilder().setBattleId(8802L).setBattleConfigId(109).build();
        TurnBattleEngine rescueEng = startEngine(rescueReq);
        assertThat(rescueEng.bossWeeklyAffixId()).isEqualTo(BossTrialWeeklyAffixRules.AFFIX_NONE);
        assertThat(rescueEng.buildStateSnapshot().getBossWeeklyAffixId()).isZero();

        // 非法覆盖值 (> 5) 建房直接拒绝
        CreateBattleRequest badReq = trialReq.toBuilder().setBattleId(8803L).setBossWeeklyAffixId(6).build();
        assertThat(TurnBattleEngine.start(badReq, data)).isInstanceOf(BattleStart.Rejected.class);
    }

    @Test
    void 金周开局金甲护盾_木周驱散回蓝与首领反哺_水周蓄力省蓝增伤与偶回蓄势() {
        // 1. 金周（1）：敌方全体开局获 8% 最大气血金甲护盾
        CreateBattleRequest metalReq = CreateBattleRequest.newBuilder()
                .setBattleId(8811L).setBattleConfigId(103).setSeed(101L)
                .setMatchMode(MatchMode.MATCH_MODE_PVE_SOLO_VALUE)
                .setBossWeeklyAffixId(BossTrialWeeklyAffixRules.AFFIX_METAL)
                .addPlayers(player(1001L, 20000L, 1000L, 500L, 1000L, 800L, 800L, dispelBuffSkillId, chargeSkillId))
                .build();
        TurnBattleEngine metalEng = startEngine(metalReq);
        BattleActorState metalBoss = findBoss(metalEng.buildStateSnapshot());
        long expectedShield = Math.max(1L, Long.divideUnsigned(metalBoss.getMaxHealth() * 8L, 100L));
        assertThat(metalEng.tournamentEarthShieldOf(metalBoss.getActorId())).isEqualTo(expectedShield);

        // 2. 木周（2）：驱散护势触发施法者 +12% 法力回复与机制播报，且 R2 末未受控首领回 4% 血
        CreateBattleRequest woodReq = metalReq.toBuilder()
                .setBattleId(8812L)
                .setBossWeeklyAffixId(BossTrialWeeklyAffixRules.AFFIX_WOOD)
                .build();
        TurnBattleEngine woodEng = startEngine(woodReq);
        BattleActorState woodBoss = findBoss(woodEng.buildStateSnapshot());
        BattleActorState woodGuard = findGuard(woodEng.buildStateSnapshot());
        woodEng.submitAction(1001L, BattleAction.newBuilder()
                .setActionType(eBattleActionType.BATTLE_ACTION_ATTACK)
                .setTargetId(woodBoss.getActorId()).build());
        TurnResultS2C woodR1 = woodEng.resolveCurrentRound();
        assertThat(woodR1.getState().getBossStanceType()).isEqualTo(EncounterMechanics.POLARITY_PHYSICAL);

        woodEng.submitAction(1001L, BattleAction.newBuilder()
                .setActionType(eBattleActionType.BATTLE_ACTION_SKILL)
                .setSkillTableId(dispelBuffSkillId)
                .setTargetId(woodGuard.getActorId()).build());
        TurnResultS2C woodR2 = woodEng.resolveCurrentRound();
        boolean sawManaTrigger = woodR2.getEventsList().stream().anyMatch(e ->
                e.getEventType() == eBattleEventType.BATTLE_EVENT_BOSS_MECHANIC
                        && e.getMechanicKind() == BattleConstants.BOSS_MECHANIC_WEEKLY_AFFIX_TRIGGER
                        && e.getSourceId() == 1001L);
        boolean sawBossRegen = woodR2.getEventsList().stream().anyMatch(e ->
                e.getEventType() == eBattleEventType.BATTLE_EVENT_BOSS_MECHANIC
                        && e.getMechanicKind() == BattleConstants.BOSS_MECHANIC_WEEKLY_AFFIX_TRIGGER
                        && e.getSourceId() == woodBoss.getActorId());
        assertThat(sawManaTrigger).isTrue();
        assertThat(sawBossRegen).isTrue();

        // 3. 水周（3）：蓄力技耗蓝 -20%、蓄力兑现伤害 +20%、R2 末首领获得额外蓄势
        long[] manaSpent = new long[2];
        long[] dmgDealt = new long[2];
        int[] r2Momentum = new int[2];
        int[] affixes = {BossTrialWeeklyAffixRules.AFFIX_NONE, BossTrialWeeklyAffixRules.AFFIX_WATER};
        for (int i = 0; i < 2; i++) {
            CreateBattleRequest req = metalReq.toBuilder()
                    .setBattleId(8813L + i)
                    .setBossWeeklyAffixId(affixes[i])
                    .build();
            TurnBattleEngine eng = startEngine(req);
            long bossId = findBoss(eng.buildStateSnapshot()).getActorId();
            eng.submitAction(1001L, BattleAction.newBuilder()
                    .setActionType(eBattleActionType.BATTLE_ACTION_SKILL)
                    .setSkillTableId(chargeSkillId)
                    .setTargetId(bossId).build());
            TurnResultS2C r1 = eng.resolveCurrentRound();
            long manaAfterR1 = r1.getState().getActorsList().stream()
                    .filter(a -> a.getActorId() == 1001L).findFirst().orElseThrow()
                    .getAttributes().getMana();
            manaSpent[i] = 500L - manaAfterR1;
            TurnResultS2C r2 = eng.resolveCurrentRound();
            dmgDealt[i] = r2.getEventsList().stream()
                    .filter(e -> e.getEventType() == eBattleEventType.BATTLE_EVENT_DAMAGE
                            && e.getSourceId() == 1001L && e.getTargetId() == bossId)
                    .mapToLong(BattleEventItem::getValue).sum();
            r2Momentum[i] = r2.getState().getBossMomentumStacks();
        }
        assertThat(manaSpent[1]).isLessThan(manaSpent[0]);
        assertThat(dmgDealt[1]).isGreaterThan(dmgDealt[0]);
        assertThat(r2Momentum[1]).isGreaterThan(r2Momentum[0]);
    }

    @Test
    void 火周灵宠增伤与提前二阶段_土周化劲赐全队护盾与下回合增伤() {
        // 1. 火周（4）：灵宠直攻伤害 +18%
        BattlePlayerSnapshot withPet = player(1001L, 20000L, 1000L, 1000L, 1000L, 800L, 800L).toBuilder()
                .addPets(BattlePetSnapshot.newBuilder()
                        .setPetId(7001L).setLevel(60).setMaxHealth(8000L).setMaxMana(500L)
                        .setPhysicalAttack(1200L).setMagicAttack(500L).setDefense(150L)
                        .setBaseAttributes(BaseAttributesComp.newBuilder()
                                .setHealth(8000L).setMana(500L).setSpeed(950L).setStrength(60).build()))
                .build();
        long[] petDmg = new long[2];
        int[] fireAffixes = {BossTrialWeeklyAffixRules.AFFIX_NONE, BossTrialWeeklyAffixRules.AFFIX_FIRE};
        for (int i = 0; i < 2; i++) {
            CreateBattleRequest req = CreateBattleRequest.newBuilder()
                    .setBattleId(8821L + i).setBattleConfigId(101).setSeed(303L)
                    .setMatchMode(MatchMode.MATCH_MODE_PVE_SOLO_VALUE)
                    .setBossWeeklyAffixId(fireAffixes[i])
                    .addPlayers(withPet)
                    .build();
            TurnBattleEngine eng = startEngine(req);
            BattleStateS2C snap = eng.buildStateSnapshot();
            long bossId = findBoss(snap).getActorId();
            long petActorId = snap.getActorsList().stream()
                    .filter(a -> a.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_PET)
                    .findFirst().orElseThrow().getActorId();
            eng.submitAction(1001L, BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build());
            eng.submitOwnedAction(1001L, petActorId, BattleAction.newBuilder()
                    .setActionType(eBattleActionType.BATTLE_ACTION_ATTACK).setTargetId(bossId).build());
            TurnResultS2C r1 = eng.resolveCurrentRound();
            petDmg[i] = r1.getEventsList().stream()
                    .filter(e -> e.getEventType() == eBattleEventType.BATTLE_EVENT_DAMAGE
                            && e.getSourceId() == petActorId && e.getTargetId() == bossId)
                    .mapToLong(BattleEventItem::getValue).sum();
        }
        assertThat(petDmg[1]).isGreaterThan(petDmg[0]);

        // 2. 土周（5）：化劲防御化解 R2 慢重劈后，全队立即获 8% 厚土护盾 + R3 全伤害 +12%
        long[] r2Shield = new long[2];
        long[] r3Dmg = new long[2];
        int[] earthAffixes = {BossTrialWeeklyAffixRules.AFFIX_NONE, BossTrialWeeklyAffixRules.AFFIX_EARTH};
        for (int i = 0; i < 2; i++) {
            CreateBattleRequest req = CreateBattleRequest.newBuilder()
                    .setBattleId(8831L + i).setBattleConfigId(101).setSeed(404L)
                    .setMatchMode(MatchMode.MATCH_MODE_PVE_SOLO_VALUE)
                    .setBossWeeklyAffixId(earthAffixes[i])
                    .addPlayers(player(1001L, 20000L, 1000L, 1000L, 1000L, 1200L, 800L))
                    .build();
            TurnBattleEngine eng = startEngine(req);
            long bossId = findBoss(eng.buildStateSnapshot()).getActorId();
            eng.submitAction(1001L, BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build());
            eng.resolveCurrentRound(); // R1
            eng.submitAction(1001L, BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build());
            eng.resolveCurrentRound(); // R2 化劲防御成功
            r2Shield[i] = eng.tournamentEarthShieldOf(1001L);
            eng.submitAction(1001L, BattleAction.newBuilder()
                    .setActionType(eBattleActionType.BATTLE_ACTION_ATTACK).setTargetId(bossId).build());
            TurnResultS2C r3 = eng.resolveCurrentRound();
            r3Dmg[i] = r3.getEventsList().stream()
                    .filter(e -> e.getEventType() == eBattleEventType.BATTLE_EVENT_DAMAGE
                            && e.getSourceId() == 1001L && e.getTargetId() == bossId)
                    .mapToLong(BattleEventItem::getValue).sum();
        }
        assertThat(r2Shield[0]).isZero();
        assertThat(r2Shield[1]).isEqualTo(1600L);
        assertThat(r3Dmg[1]).isGreaterThan(r3Dmg[0]);
    }
}
