package com.game.battle.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.combat.CombatDamageRules;
import com.game.proto.BaseAttributesComp;
import com.game.proto.BattleAction;
import com.game.proto.BattleActorState;
import com.game.proto.BattleBuffEntry;
import com.game.proto.BattlePetSnapshot;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleStateS2C;
import com.game.proto.CreateBattleRequest;
import com.game.proto.TurnResultS2C;
import com.game.proto.eBattleActionType;
import com.game.proto.eBattleActorType;
import com.game.proto.eBattleEventType;
import com.game.table.BuffTable;
import com.game.table.ConfigTables;
import com.game.table.SkillTable;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 率土式持续叠加至战斗结束永久成长战法战斗引擎单测：
 * 卷 3 原有（每回合 +5%、5 层封顶）：12020 愈战愈勇、12021 深谋远虑、12022 步步为营；
 * 卷 7 新增（交互触发每层 +1%、伤害类 300 层 / 机制类 40~50 层封顶）：12023 承锋蓄势、12024 循隙破甲、12025 蚀骨愈深、12026 磐石道心、12027 厚泽蓄灵、12028 兽威渐盛。
 */
class GrowingPassiveSkillTest {

    private static final ConfigTables CONFIG = ConfigTables.load(Path.of("../config-data/tables"));
    private static final TableBattleData TABLE_DATA = new TableBattleData(CONFIG);

    private static final long HERO = 101L;
    private static final long ALLY = 102L;
    private static final long ENEMY = 201L;

    private static final int SKILL_GROWING_PHYS = 12020;  // 愈战愈勇
    private static final int SKILL_GROWING_MAGIC = 12021; // 深谋远虑
    private static final int SKILL_GROWING_DEF = 12022;   // 步步为营
    private static final int SKILL_TAKE_MOMENTUM = 12023; // 承锋蓄势
    private static final int SKILL_PENETRATION = 12024;   // 循隙破甲
    private static final int SKILL_DOT_GROWTH = 12025;    // 蚀骨愈深
    private static final int SKILL_CTRL_RESIST = 12026;   // 磐石道心
    private static final int SKILL_SHIELD_GROW = 12027;   // 厚泽蓄灵
    private static final int SKILL_PET_GROWTH = 12028;    // 兽威渐盛
    private static final int SKILL_O01 = 14001;           // 后发制人（全队 OutgoingDamage +5%×3）
    private static final int SKILL_A34 = 10034;           // 驱散增益
    private static final int SKILL_A36 = 10036;           // 同袍共济
    private static final int SKILL_A40 = 10040;           // 太清灵盾
    private static final int SKILL_B09 = 11009;           // 游说退敌

    private static final int BUFF_GROWING_PHYS = 120200;
    private static final int BUFF_GROWING_MAGIC = 120210;
    private static final int BUFF_GROWING_DEF = 120220;
    private static final int BUFF_TAKE_MOMENTUM = 120230;
    private static final int BUFF_PENETRATION = 120240;
    private static final int BUFF_DOT_GROWTH = 120250;
    private static final int BUFF_CTRL_RESIST = 120260;
    private static final int BUFF_SHIELD_GROW = 120270;
    private static final int BUFF_PET_GROWTH = 120280;

    private static final int TEST_IDLE_SKILL = 88000;
    private static final int TEST_PHYS_STRIKE = 88001;
    private static final int TEST_MAGIC_STRIKE = 88002;
    private static final int TEST_KILL_STRIKE = 88003;
    private static final int TEST_REVIVE_SKILL = 88004;
    private static final int TEST_BURN_SKILL = 88005;
    private static final int TEST_SEAL_ACTIVE_SKILL = 88006;

    private MemoryBattleData data;

    @BeforeEach
    void setUp() {
        data = new MemoryBattleData();
        data.addPet(1);
        for (SkillTable row : CONFIG.skill().all()) {
            data.addSkill(row.getId()).mergeFrom(row);
            data.setSkillDamage(row.getId(), TABLE_DATA.skillDamage(row.getId(), 30.0));
        }
        for (BuffTable row : CONFIG.buff().all()) {
            data.addBuff(row.getId()).mergeFrom(row);
        }
        addTestSkill(TEST_IDLE_SKILL, 3, 0, BattleEffect.MANA, 0.0, false);
        addTestSkill(TEST_PHYS_STRIKE, 1, CombatDamageRules.PHYSICAL_DAMAGE, BattleEffect.DAMAGE, 1000.0, false);
        addTestSkill(TEST_MAGIC_STRIKE, 1, CombatDamageRules.MAGIC_DAMAGE, BattleEffect.DAMAGE, 1000.0, false);
        addTestSkill(TEST_KILL_STRIKE, 1, CombatDamageRules.PHYSICAL_DAMAGE, BattleEffect.DAMAGE, 100000.0, false);
        addTestSkill(TEST_REVIVE_SKILL, 2, 0, BattleEffect.REVIVE, 0.0, true);
        data.addSkill(TEST_BURN_SKILL)
                .setBattleModel(1).setCasterMask(3).setAbilityKind(1)
                .setTargetTeam(1).setMaxTargets(1).setPetMaxTargets(1)
                .setUnlockLevel(1).setMaxRank(10).addSkillType(1).addEffect(TEST_BURN_SKILL);
        data.addBuff(TEST_BURN_SKILL)
                .setBattleOp(BattleEffect.PERIODIC)
                .setStateKind(BattleEffect.BURN)
                .setMaxLayer(1).setFlatValue(100.0).setTickCount(6).setDurationRounds(6)
                .setTickOnApply(1).setApplicationChance(0.0)
                .putTag("Debuff", true).putTag("Burn", true);
        data.addSkill(TEST_SEAL_ACTIVE_SKILL)
                .setBattleModel(1).setCasterMask(3).setAbilityKind(1)
                .setTargetTeam(1).setMaxTargets(1).setPetMaxTargets(1)
                .setUnlockLevel(1).setMaxRank(10).addSkillType(1).addEffect(TEST_SEAL_ACTIVE_SKILL);
        data.addBuff(TEST_SEAL_ACTIVE_SKILL)
                .setBattleOp(BattleEffect.CONTROL)
                .setStateKind(BattleEffect.SEAL_ACTIVE)
                .setMaxLayer(1).setDurationRounds(1).setApplicationChance(0.5)
                .putTag("Debuff", true);
    }

    private void addTestSkill(int id, int targetTeam, int dmgType, int op, double damageValue, boolean allowDead) {
        SkillTable.Builder s = data.addSkill(id)
                .setBattleModel(1)
                .setCasterMask(3)
                .setAbilityKind(1)
                .setTargetTeam(targetTeam)
                .setMaxTargets(1)
                .setPetMaxTargets(1)
                .setUnlockLevel(1)
                .setMaxRank(10)
                .addSkillType(1)
                .setDamageType(dmgType)
                .addEffect(id);
        if (allowDead) {
            s.setAllowDeadTarget(1);
        }
        BuffTable.Builder b = data.addBuff(id)
                .setBattleOp(op)
                .setMaxLayer(1);
        if (op == BattleEffect.DAMAGE) {
            data.setSkillDamage(id, damageValue);
        } else if (op == BattleEffect.REVIVE) {
            b.setKeepOnDeath(1)
                    .setTickCount(1)
                    .setDurationRounds(1)
                    .setTickOnApply(1)
                    .setSecondaryRatio(0.5);
        }
    }

    private static BattleAction cast(int skillId, long targetId) {
        return BattleAction.newBuilder()
                .setActionType(eBattleActionType.BATTLE_ACTION_SKILL)
                .setSkillTableId(skillId)
                .setTargetId(targetId)
                .build();
    }

    private static BattleAction basicAttack(long targetId) {
        return BattleAction.newBuilder()
                .setActionType(eBattleActionType.BATTLE_ACTION_ATTACK)
                .setTargetId(targetId)
                .build();
    }

    private static long shieldCapacity(BattleActorState actor, int skillTableId) {
        for (BattleBuffEntry buff : actor.getBuffsList()) {
            if (buff.getSourceSkillId() == skillTableId && buff.getRemainingCapacity() > 0) {
                return buff.getRemainingCapacity();
            }
        }
        return 0L;
    }

    private static long firstPeriodicDamageTo(TurnResultS2C result, long sourceId, long targetId) {
        for (var event : result.getEventsList()) {
            if (event.getEventType() == eBattleEventType.BATTLE_EVENT_DAMAGE
                    && event.getSourceId() == sourceId && event.getTargetId() == targetId
                    && event.getDamageOrigin() == BattleEffect.DOT) {
                return event.getValue();
            }
        }
        return 0L;
    }

    private static BattleActorState findActor(BattleStateS2C state, long id) {
        for (BattleActorState actor : state.getActorsList()) {
            if (actor.getActorId() == id) {
                return actor;
            }
        }
        throw new AssertionError("missing actor " + id);
    }

    private static int buffStacks(BattleActorState actor, int buffTableId) {
        for (BattleBuffEntry buff : actor.getBuffsList()) {
            if (buff.getBuffTableId() == buffTableId) {
                return buff.getStacks();
            }
        }
        return 0;
    }

    private static boolean hasBuff(BattleActorState actor, int buffTableId) {
        for (BattleBuffEntry buff : actor.getBuffsList()) {
            if (buff.getBuffTableId() == buffTableId) {
                return true;
            }
        }
        return false;
    }

    private static long firstDamageTo(TurnResultS2C result, long sourceId, long targetId) {
        for (var event : result.getEventsList()) {
            if (event.getEventType() == eBattleEventType.BATTLE_EVENT_DAMAGE
                    && event.getSourceId() == sourceId && event.getTargetId() == targetId) {
                return event.getValue();
            }
        }
        return 0L;
    }

    private CreateBattleRequest.Builder baseRequest(long heroDefense) {
        CreateBattleRequest.Builder req = CreateBattleRequest.newBuilder()
                .setBattleId(88001L)
                .setMatchMode(3)
                .setSeed(20261124L);
        addPlayer(req, HERO, 0, 300, heroDefense);
        addPlayer(req, ALLY, 0, 250, 0);
        addPlayer(req, ENEMY, 1, 100, 0);
        return req;
    }

    private static void addPlayer(CreateBattleRequest.Builder req, long pid, int team, int speed, long def) {
        BattlePlayerSnapshot.Builder p = req.addPlayersBuilder()
                .setPlayerId(pid)
                .setTeamIndex(team)
                .setLevel(30)
                .setClassId(1)
                .setMaxHealth(20000L)
                .setMaxMana(5000L)
                .setPhysicalAttack(1000L)
                .setMagicAttack(1000L)
                .setDefense(def)
                .setBaseAttributes(BaseAttributesComp.newBuilder()
                        .setHealth(20000L)
                        .setMana(5000L)
                        .setSpeed(speed));
        for (int sid : new int[]{TEST_IDLE_SKILL, TEST_PHYS_STRIKE, TEST_MAGIC_STRIKE, TEST_KILL_STRIKE, TEST_REVIVE_SKILL}) {
            p.putSkillRanks(sid, 1).addSkillTableIds(sid);
        }
    }

    private TurnBattleEngine start(CreateBattleRequest req) {
        BattleStart started = TurnBattleEngine.start(req, data);
        assertThat(started).isInstanceOf(BattleStart.Started.class);
        return ((BattleStart.Started) started).engine();
    }

    @Test
    void 愈战愈勇与深谋远虑按回合叠至5层封顶且物法严格隔离() {
        CreateBattleRequest.Builder req = baseRequest(0);
        req.getPlayersBuilder(0).putSkillRanks(SKILL_GROWING_PHYS, 1);
        req.getPlayersBuilder(1).putSkillRanks(SKILL_GROWING_MAGIC, 1);
        TurnBattleEngine engine = start(req.build());

        long[] expectedScaled = {630L, 660L, 690L, 720L, 750L, 750L};
        for (int round = 0; round < 6; round++) {
            engine.submitAction(HERO, cast(TEST_PHYS_STRIKE, ENEMY));
            engine.submitAction(ALLY, cast(TEST_MAGIC_STRIKE, ENEMY));
            engine.submitAction(ENEMY, cast(TEST_IDLE_SKILL, ENEMY));
            TurnResultS2C res = engine.resolveCurrentRound();
            int expectedStacks = Math.min(5, round + 1);
            assertThat(buffStacks(findActor(res.getState(), HERO), BUFF_GROWING_PHYS)).isEqualTo(expectedStacks);
            assertThat(buffStacks(findActor(res.getState(), ALLY), BUFF_GROWING_MAGIC)).isEqualTo(expectedStacks);
            assertThat(firstDamageTo(res, HERO, ENEMY)).as("round=%d phys", round + 1).isEqualTo(expectedScaled[round]);
            assertThat(firstDamageTo(res, ALLY, ENEMY)).as("round=%d magic", round + 1).isEqualTo(expectedScaled[round]);
        }

        // 第 7 回合互换伤害类型：愈战愈勇不加成法术，深谋远虑不加成物理（维持基础 600）。
        engine.submitAction(HERO, cast(TEST_MAGIC_STRIKE, ENEMY));
        engine.submitAction(ALLY, cast(TEST_PHYS_STRIKE, ENEMY));
        engine.submitAction(ENEMY, cast(TEST_IDLE_SKILL, ENEMY));
        TurnResultS2C crossRes = engine.resolveCurrentRound();
        assertThat(firstDamageTo(crossRes, HERO, ENEMY)).isEqualTo(600L);
        assertThat(firstDamageTo(crossRes, ALLY, ENEMY)).isEqualTo(600L);
    }

    @Test
    void 愈战愈勇与后发制人加算叠层不复利() {
        CreateBattleRequest.Builder req = baseRequest(0);
        req.getPlayersBuilder(0).putSkillRanks(SKILL_GROWING_PHYS, 1).putSkillRanks(SKILL_O01, 1);
        TurnBattleEngine engine = start(req.build());

        for (int round = 1; round <= 5; round++) {
            engine.submitAction(HERO, cast(TEST_PHYS_STRIKE, ENEMY));
            engine.submitAction(ALLY, cast(TEST_IDLE_SKILL, ALLY));
            engine.submitAction(ENEMY, cast(TEST_IDLE_SKILL, ENEMY));
            TurnResultS2C res = engine.resolveCurrentRound();
            if (round == 5) {
                assertThat(firstDamageTo(res, HERO, ENEMY)).isEqualTo(840L);
            }
        }
    }

    @Test
    void 步步为营叠至5层持续降伤且严守60减伤红线封顶() {
        CreateBattleRequest.Builder req = baseRequest(500);
        req.getPlayersBuilder(0).putSkillRanks(SKILL_GROWING_DEF, 1);
        TurnBattleEngine engine = start(req.build());

        long r1Damage = 0, r5Damage = 0, r6Damage = 0;
        for (int round = 1; round <= 6; round++) {
            engine.submitAction(HERO, cast(TEST_IDLE_SKILL, HERO));
            engine.submitAction(ALLY, cast(TEST_IDLE_SKILL, ALLY));
            engine.submitAction(ENEMY, cast(TEST_PHYS_STRIKE, HERO));
            TurnResultS2C res = engine.resolveCurrentRound();
            long dmg = firstDamageTo(res, ENEMY, HERO);
            if (round == 1) r1Damage = dmg;
            if (round == 5) r5Damage = dmg;
            if (round == 6) r6Damage = dmg;
        }
        assertThat(r5Damage).isLessThan(r1Damage);
        assertThat(r6Damage).isEqualTo(r5Damage);

        CreateBattleRequest.Builder capReq = baseRequest(20000);
        capReq.getPlayersBuilder(0).putSkillRanks(SKILL_GROWING_DEF, 1);
        TurnBattleEngine capEngine = start(capReq.build());
        for (int round = 1; round <= 5; round++) {
            capEngine.submitAction(HERO, cast(TEST_IDLE_SKILL, HERO));
            capEngine.submitAction(ALLY, cast(TEST_IDLE_SKILL, ALLY));
            capEngine.submitAction(ENEMY, cast(TEST_PHYS_STRIKE, HERO));
            TurnResultS2C res = capEngine.resolveCurrentRound();
            assertThat(firstDamageTo(res, ENEMY, HERO)).isGreaterThanOrEqualTo(240L);
        }
    }

    @Test
    void 成长战法不可被A34驱散且阵亡保留暂停复活后恢复叠层() {
        CreateBattleRequest.Builder req = baseRequest(0);
        req.getPlayersBuilder(0).putSkillRanks(SKILL_GROWING_PHYS, 1);
        req.getPlayersBuilder(2).putSkillRanks(SKILL_A34, 1).addSkillTableIds(SKILL_A34);
        TurnBattleEngine engine = start(req.build());

        // 第 1 回合：敌方施放 A34 驱散，愈战愈勇必须保留且 1 层生效。
        engine.submitAction(HERO, cast(TEST_IDLE_SKILL, HERO));
        engine.submitAction(ALLY, cast(TEST_IDLE_SKILL, ALLY));
        engine.submitAction(ENEMY, cast(SKILL_A34, HERO));
        TurnResultS2C res = engine.resolveCurrentRound();
        assertThat(hasBuff(findActor(res.getState(), HERO), BUFF_GROWING_PHYS)).isTrue();
        assertThat(buffStacks(findActor(res.getState(), HERO), BUFF_GROWING_PHYS)).isEqualTo(1);

        // 第 2 回合：Hero 叠到 2 层后被敌方秒杀（keep_on_death=1 保留 2 层）。
        engine.submitAction(HERO, cast(TEST_IDLE_SKILL, HERO));
        engine.submitAction(ALLY, cast(TEST_IDLE_SKILL, ALLY));
        engine.submitAction(ENEMY, cast(TEST_KILL_STRIKE, HERO));
        res = engine.resolveCurrentRound();
        assertThat(findActor(res.getState(), HERO).getIsDead()).isTrue();
        assertThat(hasBuff(findActor(res.getState(), HERO), BUFF_GROWING_PHYS)).isTrue();
        assertThat(buffStacks(findActor(res.getState(), HERO), BUFF_GROWING_PHYS)).isEqualTo(2);

        // 第 3 回合：Hero 阵亡期间暂停叠层（保持 2 层），友军将其复活。
        engine.submitAction(ALLY, cast(TEST_REVIVE_SKILL, HERO));
        engine.submitAction(ENEMY, cast(TEST_IDLE_SKILL, ENEMY));
        res = engine.resolveCurrentRound();
        assertThat(findActor(res.getState(), HERO).getIsDead()).isFalse();
        assertThat(buffStacks(findActor(res.getState(), HERO), BUFF_GROWING_PHYS)).isEqualTo(2);

        // 第 4 回合：复活存活后恢复叠层（2 -> 3 层 = 600*1.15=690 物理伤害）。
        engine.submitAction(HERO, cast(TEST_PHYS_STRIKE, ENEMY));
        engine.submitAction(ALLY, cast(TEST_IDLE_SKILL, ALLY));
        engine.submitAction(ENEMY, cast(TEST_IDLE_SKILL, ENEMY));
        res = engine.resolveCurrentRound();
        assertThat(buffStacks(findActor(res.getState(), HERO), BUFF_GROWING_PHYS)).isEqualTo(3);
        assertThat(firstDamageTo(res, HERO, ENEMY)).isEqualTo(690L);
    }

    @Test
    void 承锋蓄势仅敌方直击与援护分担叠层且每回合限1层每层加1() {
        assertThat(data.buff(BUFF_TAKE_MOMENTUM).orElseThrow().getStackLimit()).isEqualTo(300);
        CreateBattleRequest.Builder req = baseRequest(0);
        req.getPlayersBuilder(0).putSkillRanks(SKILL_TAKE_MOMENTUM, 1).putSkillRanks(SKILL_A36, 1).addSkillTableIds(SKILL_A36);
        addPlayer(req, 202L, 1, 90, 0);
        TurnBattleEngine engine = start(req.build());

        // 第 1 回合未受击不叠层（0 层 -> 600）。
        engine.submitAction(HERO, cast(TEST_PHYS_STRIKE, ENEMY));
        engine.submitAction(ALLY, cast(TEST_IDLE_SKILL, ALLY));
        engine.submitAction(ENEMY, cast(TEST_IDLE_SKILL, ENEMY));
        engine.submitAction(202L, cast(TEST_IDLE_SKILL, 202L));
        TurnResultS2C res = engine.resolveCurrentRound();
        assertThat(buffStacks(findActor(res.getState(), HERO), BUFF_TAKE_MOMENTUM)).isZero();
        assertThat(firstDamageTo(res, HERO, ENEMY)).isEqualTo(600L);

        // 第 2 回合同轮被两名敌人击中仍仅叠 1 层。
        engine.submitAction(HERO, cast(TEST_IDLE_SKILL, HERO));
        engine.submitAction(ALLY, cast(TEST_IDLE_SKILL, ALLY));
        engine.submitAction(ENEMY, cast(TEST_PHYS_STRIKE, HERO));
        engine.submitAction(202L, cast(TEST_PHYS_STRIKE, HERO));
        res = engine.resolveCurrentRound();
        assertThat(buffStacks(findActor(res.getState(), HERO), BUFF_TAKE_MOMENTUM)).isEqualTo(1);

        // 第 3 回合 A36 援护队友承受转伤正常叠至 2 层。
        engine.submitAction(HERO, cast(SKILL_A36, ALLY));
        engine.submitAction(ALLY, cast(TEST_IDLE_SKILL, ALLY));
        engine.submitAction(ENEMY, cast(TEST_PHYS_STRIKE, ALLY));
        engine.submitAction(202L, cast(TEST_IDLE_SKILL, 202L));
        res = engine.resolveCurrentRound();
        assertThat(buffStacks(findActor(res.getState(), HERO), BUFF_TAKE_MOMENTUM)).isEqualTo(2);

        for (int r = 4; r <= 8; r++) {
            engine.submitAction(HERO, cast(TEST_PHYS_STRIKE, ENEMY));
            engine.submitAction(ALLY, cast(TEST_IDLE_SKILL, ALLY));
            engine.submitAction(ENEMY, cast(TEST_PHYS_STRIKE, HERO));
            engine.submitAction(202L, cast(TEST_IDLE_SKILL, 202L));
            res = engine.resolveCurrentRound();
            if (r == 7) {
                assertThat(firstDamageTo(res, HERO, ENEMY)).isEqualTo(630L);
                assertThat(buffStacks(findActor(res.getState(), HERO), BUFF_TAKE_MOMENTUM)).isEqualTo(6);
            }
            if (r == 8) {
                assertThat(firstDamageTo(res, HERO, ENEMY)).isEqualTo(636L);
                assertThat(buffStacks(findActor(res.getState(), HERO), BUFF_TAKE_MOMENTUM)).isEqualTo(7);
            }
        }
    }

    @Test
    void 循隙破甲主动普攻与技能命中叠穿防且满50层封顶() {
        assertThat(data.buff(BUFF_PENETRATION).orElseThrow().getStackLimit()).isEqualTo(50);
        CreateBattleRequest.Builder req = baseRequest(0);
        req.getPlayersBuilder(0).putSkillRanks(SKILL_PENETRATION, 1);
        req.getPlayersBuilder(2).setDefense(2400L).setMaxHealth(100000L).getBaseAttributesBuilder().setHealth(100000L);
        TurnBattleEngine engine = start(req.build());

        engine.submitAction(HERO, cast(TEST_IDLE_SKILL, HERO));
        engine.submitAction(ALLY, cast(TEST_IDLE_SKILL, ALLY));
        engine.submitAction(ENEMY, cast(TEST_IDLE_SKILL, ENEMY));
        TurnResultS2C res = engine.resolveCurrentRound();
        assertThat(buffStacks(findActor(res.getState(), HERO), BUFF_PENETRATION)).isZero();

        engine.submitAction(HERO, basicAttack(ENEMY));
        engine.submitAction(ALLY, cast(TEST_IDLE_SKILL, ALLY));
        engine.submitAction(ENEMY, cast(TEST_IDLE_SKILL, ENEMY));
        res = engine.resolveCurrentRound();
        assertThat(buffStacks(findActor(res.getState(), HERO), BUFF_PENETRATION)).isEqualTo(1);

        long r3Dmg = 0, r52Dmg = 0, r53Dmg = 0;
        for (int r = 3; r <= 53; r++) {
            engine.submitAction(HERO, cast(TEST_PHYS_STRIKE, ENEMY));
            engine.submitAction(ALLY, cast(TEST_IDLE_SKILL, ALLY));
            engine.submitAction(ENEMY, cast(TEST_IDLE_SKILL, ENEMY));
            res = engine.resolveCurrentRound();
            long dmg = firstDamageTo(res, HERO, ENEMY);
            if (r == 3) r3Dmg = dmg;
            if (r == 52) r52Dmg = dmg;
            if (r == 53) r53Dmg = dmg;
        }
        assertThat(buffStacks(findActor(res.getState(), HERO), BUFF_PENETRATION)).isEqualTo(50);
        assertThat(r52Dmg).isGreaterThan(r3Dmg);
        assertThat(r53Dmg).isEqualTo(r52Dmg);
    }

    @Test
    void 蚀骨愈深随持续伤害跳伤每层加1逐跳放大() {
        assertThat(data.buff(BUFF_DOT_GROWTH).orElseThrow().getStackLimit()).isEqualTo(300);
        CreateBattleRequest.Builder req = baseRequest(0);
        req.getPlayersBuilder(0).putSkillRanks(SKILL_DOT_GROWTH, 1).putSkillRanks(TEST_BURN_SKILL, 1).addSkillTableIds(TEST_BURN_SKILL);
        TurnBattleEngine engine = start(req.build());

        engine.submitAction(HERO, cast(TEST_BURN_SKILL, ENEMY));
        engine.submitAction(ALLY, cast(TEST_IDLE_SKILL, ALLY));
        engine.submitAction(ENEMY, cast(TEST_IDLE_SKILL, ENEMY));
        TurnResultS2C res = engine.resolveCurrentRound();
        assertThat(firstPeriodicDamageTo(res, HERO, ENEMY)).isEqualTo(100L);
        assertThat(buffStacks(findActor(res.getState(), HERO), BUFF_DOT_GROWTH)).isEqualTo(1);

        long[] expected = {101L, 102L, 103L, 104L, 105L};
        for (int i = 0; i < expected.length; i++) {
            engine.submitAction(HERO, cast(TEST_IDLE_SKILL, HERO));
            engine.submitAction(ALLY, cast(TEST_IDLE_SKILL, ALLY));
            engine.submitAction(ENEMY, cast(TEST_IDLE_SKILL, ENEMY));
            res = engine.resolveCurrentRound();
            assertThat(firstPeriodicDamageTo(res, HERO, ENEMY)).as("tick=%d", i + 2).isEqualTo(expected[i]);
            assertThat(buffStacks(findActor(res.getState(), HERO), BUFF_DOT_GROWTH)).isEqualTo(i + 2);
        }
    }

    @Test
    void 磐石道心40层封顶与厚泽蓄灵50层封顶均由交互触发并正确生效() {
        assertThat(data.buff(BUFF_SHIELD_GROW).orElseThrow().getStackLimit()).isEqualTo(50);
        assertThat(data.buff(BUFF_CTRL_RESIST).orElseThrow().getStackLimit()).isEqualTo(40);
        CreateBattleRequest.Builder shieldReq = baseRequest(0);
        shieldReq.getPlayersBuilder(0).putSkillRanks(SKILL_SHIELD_GROW, 1).putSkillRanks(SKILL_A40, 1).addSkillTableIds(SKILL_A40);
        data.addSkill(SKILL_A40).setCooldownRounds(0).setManaPercent(0.0);
        TurnBattleEngine shieldEngine = start(shieldReq.build());
        // 第 1 回合待机不放辅助技：维持 0 层。
        shieldEngine.submitAction(HERO, cast(TEST_IDLE_SKILL, HERO));
        shieldEngine.submitAction(ALLY, cast(TEST_IDLE_SKILL, ALLY));
        shieldEngine.submitAction(ENEMY, cast(TEST_IDLE_SKILL, ENEMY));
        TurnResultS2C idleRes = shieldEngine.resolveCurrentRound();
        assertThat(buffStacks(findActor(idleRes.getState(), HERO), BUFF_SHIELD_GROW)).isZero();

        long r2Shield = 0, r52Shield = 0, r53Shield = 0;
        for (int r = 2; r <= 53; r++) {
            shieldEngine.submitAction(HERO, cast(SKILL_A40, HERO));
            shieldEngine.submitAction(ALLY, cast(TEST_IDLE_SKILL, ALLY));
            shieldEngine.submitAction(ENEMY, cast(TEST_IDLE_SKILL, ENEMY));
            TurnResultS2C res = shieldEngine.resolveCurrentRound();
            long cap = shieldCapacity(findActor(res.getState(), HERO), SKILL_A40);
            if (r == 2) r2Shield = cap;
            if (r == 52) r52Shield = cap;
            if (r == 53) r53Shield = cap;
            assertThat(buffStacks(findActor(res.getState(), HERO), BUFF_SHIELD_GROW)).isEqualTo(Math.min(50, r - 1));
        }
        assertThat(r52Shield).isGreaterThan(r2Shield);
        assertThat(r53Shield).isEqualTo(r52Shield);

        int baselineLanded = countSealLanded(false);
        int resistedLanded = countSealLanded(true);
        assertThat(resistedLanded).isLessThan(baselineLanded);
    }

    private int countSealLanded(boolean withCtrlResist) {
        int landed = 0;
        for (int seed = 1; seed <= 40; seed++) {
            CreateBattleRequest.Builder req = baseRequest(0).setSeed(9000L + seed);
            req.getPlayersBuilder(2).setMaxHealth(200000L).getBaseAttributesBuilder().setHealth(200000L);
            if (withCtrlResist) req.getPlayersBuilder(0).putSkillRanks(SKILL_CTRL_RESIST, 1);
            req.getPlayersBuilder(2).putSkillRanks(TEST_SEAL_ACTIVE_SKILL, 1).addSkillTableIds(TEST_SEAL_ACTIVE_SKILL);
            TurnBattleEngine eng = start(req.build());
            // 第 1 回合敌方不控场：0 层。
            eng.submitAction(HERO, cast(TEST_IDLE_SKILL, HERO));
            eng.submitAction(ALLY, cast(TEST_IDLE_SKILL, ALLY));
            eng.submitAction(ENEMY, cast(TEST_IDLE_SKILL, ENEMY));
            TurnResultS2C r1 = eng.resolveCurrentRound();
            if (withCtrlResist) {
                assertThat(buffStacks(findActor(r1.getState(), HERO), BUFF_CTRL_RESIST)).isZero();
            }
            for (int r = 2; r <= 41; r++) {
                eng.submitAction(HERO, basicAttack(ENEMY));
                eng.submitAction(ALLY, cast(TEST_IDLE_SKILL, ALLY));
                eng.submitAction(ENEMY, cast(TEST_SEAL_ACTIVE_SKILL, HERO));
                eng.resolveCurrentRound();
            }
            eng.submitAction(HERO, basicAttack(ENEMY));
            eng.submitAction(ALLY, cast(TEST_IDLE_SKILL, ALLY));
            eng.submitAction(ENEMY, cast(TEST_SEAL_ACTIVE_SKILL, HERO));
            TurnResultS2C res = eng.resolveCurrentRound();
            if (withCtrlResist) {
                assertThat(buffStacks(findActor(res.getState(), HERO), BUFF_CTRL_RESIST)).isEqualTo(40);
            }
            if (hasBuff(findActor(res.getState(), HERO), TEST_SEAL_ACTIVE_SKILL)) landed++;
        }
        return landed;
    }

    @Test
    void 兽威渐盛仅宝宝生效且阵亡与劝退清零复活后重叠() {
        assertThat(data.buff(BUFF_PET_GROWTH).orElseThrow().getStackLimit()).isEqualTo(300);
        CreateBattleRequest.Builder heroReq = baseRequest(0);
        heroReq.getPlayersBuilder(0).putSkillRanks(SKILL_PET_GROWTH, 1);
        TurnBattleEngine heroEngine = start(heroReq.build());
        assertThat(hasBuff(findActor(heroEngine.buildStateSnapshot(), HERO), BUFF_PET_GROWTH)).isFalse();

        CreateBattleRequest.Builder req = makePetRequest(20261126L);
        TurnBattleEngine engine = start(req.build());
        long petActorId = findPetActorId(engine.buildStateSnapshot(), HERO);
        assertThat(petActorId).isNotZero();

        for (int r = 1; r <= 2; r++) {
            engine.submitAction(HERO, cast(TEST_IDLE_SKILL, HERO));
            engine.submitOwnedAction(HERO, petActorId, cast(TEST_PHYS_STRIKE, ENEMY));
            engine.submitAction(ALLY, cast(TEST_IDLE_SKILL, ALLY));
            engine.submitAction(ENEMY, cast(TEST_IDLE_SKILL, ENEMY));
            TurnResultS2C res = engine.resolveCurrentRound();
            assertThat(buffStacks(findActor(res.getState(), petActorId), BUFF_PET_GROWTH)).isEqualTo(r);
            if (r == 2) assertThat(firstDamageTo(res, petActorId, ENEMY)).isEqualTo(606L);
        }

        // 阵亡清零但保留被动本体。
        engine.submitAction(HERO, cast(TEST_IDLE_SKILL, HERO));
        engine.submitOwnedAction(HERO, petActorId, cast(TEST_IDLE_SKILL, petActorId));
        engine.submitAction(ALLY, cast(TEST_IDLE_SKILL, ALLY));
        engine.submitAction(ENEMY, cast(TEST_KILL_STRIKE, petActorId));
        TurnResultS2C res = engine.resolveCurrentRound();
        assertThat(findActor(res.getState(), petActorId).getIsDead()).isTrue();
        assertThat(hasBuff(findActor(res.getState(), petActorId), BUFF_PET_GROWTH)).isTrue();
        assertThat(buffStacks(findActor(res.getState(), petActorId), BUFF_PET_GROWTH)).isZero();

        // 复活后回到 0 层（600 伤），再次攻击重新叠至 1 层。
        engine.submitAction(HERO, cast(TEST_IDLE_SKILL, HERO));
        engine.submitAction(ALLY, cast(TEST_REVIVE_SKILL, petActorId));
        engine.submitAction(ENEMY, cast(TEST_IDLE_SKILL, ENEMY));
        res = engine.resolveCurrentRound();
        assertThat(findActor(res.getState(), petActorId).getIsDead()).isFalse();

        engine.submitAction(HERO, cast(TEST_IDLE_SKILL, HERO));
        engine.submitOwnedAction(HERO, petActorId, cast(TEST_PHYS_STRIKE, ENEMY));
        engine.submitAction(ALLY, cast(TEST_IDLE_SKILL, ALLY));
        engine.submitAction(ENEMY, cast(TEST_IDLE_SKILL, ENEMY));
        res = engine.resolveCurrentRound();
        assertThat(firstDamageTo(res, petActorId, ENEMY)).isEqualTo(600L);
        assertThat(buffStacks(findActor(res.getState(), petActorId), BUFF_PET_GROWTH)).isEqualTo(1);

        // 劝退同样清零层数。
        data.addBuff(110090).setApplicationChance(1.0);
        CreateBattleRequest.Builder retreatReq = makePetRequest(20261127L);
        retreatReq.getPlayersBuilder(2).addPetsBuilder()
                .setPetId(9002L).setPetTableId(1).setLevel(30)
                .setMaxHealth(20000L).setMaxMana(5000L).setPhysicalAttack(1000L).setMagicAttack(1000L)
                .setBaseAttributes(BaseAttributesComp.newBuilder().setHealth(20000L).setMana(5000L).setSpeed(120))
                .putSkillRanks(SKILL_B09, 1).addSkillTableIds(SKILL_B09)
                .putSkillRanks(TEST_IDLE_SKILL, 1).addSkillTableIds(TEST_IDLE_SKILL);
        TurnBattleEngine retreatEngine = start(retreatReq.build());
        long heroPet2 = findPetActorId(retreatEngine.buildStateSnapshot(), HERO);
        long enemyPetId = findPetActorId(retreatEngine.buildStateSnapshot(), ENEMY);
        retreatEngine.submitAction(HERO, cast(TEST_IDLE_SKILL, HERO));
        retreatEngine.submitOwnedAction(HERO, heroPet2, cast(TEST_PHYS_STRIKE, ENEMY));
        retreatEngine.submitAction(ALLY, cast(TEST_IDLE_SKILL, ALLY));
        retreatEngine.submitAction(ENEMY, cast(TEST_IDLE_SKILL, ENEMY));
        retreatEngine.submitOwnedAction(ENEMY, enemyPetId, cast(SKILL_B09, heroPet2));
        res = retreatEngine.resolveCurrentRound();
        assertThat(findActor(res.getState(), heroPet2).getFled()).isTrue();
        assertThat(hasBuff(findActor(res.getState(), heroPet2), BUFF_PET_GROWTH)).isTrue();
        assertThat(buffStacks(findActor(res.getState(), heroPet2), BUFF_PET_GROWTH)).isZero();
    }

    private CreateBattleRequest.Builder makePetRequest(long seed) {
        CreateBattleRequest.Builder req = baseRequest(0).setSeed(seed);
        req.getPlayersBuilder(0).addPetsBuilder()
                .setPetId(9001L).setPetTableId(1).setLevel(30)
                .setMaxHealth(20000L).setMaxMana(5000L).setPhysicalAttack(1000L).setMagicAttack(1000L)
                .setDefense(0L)
                .setBaseAttributes(BaseAttributesComp.newBuilder().setHealth(20000L).setMana(5000L).setSpeed(280))
                .putSkillRanks(SKILL_PET_GROWTH, 1)
                .putSkillRanks(TEST_IDLE_SKILL, 1).addSkillTableIds(TEST_IDLE_SKILL)
                .putSkillRanks(TEST_PHYS_STRIKE, 1).addSkillTableIds(TEST_PHYS_STRIKE);
        return req;
    }

    private static long findPetActorId(BattleStateS2C state, long ownerId) {
        for (BattleActorState actor : state.getActorsList()) {
            if (actor.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_PET && actor.getOwnerPlayerId() == ownerId) {
                return actor.getActorId();
            }
        }
        return 0L;
    }
}
