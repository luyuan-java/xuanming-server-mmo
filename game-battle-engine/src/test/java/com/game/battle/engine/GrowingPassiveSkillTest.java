package com.game.battle.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.combat.CombatDamageRules;
import com.game.proto.BaseAttributesComp;
import com.game.proto.BattleAction;
import com.game.proto.BattleActorState;
import com.game.proto.BattleBuffEntry;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleStateS2C;
import com.game.proto.CreateBattleRequest;
import com.game.proto.TurnResultS2C;
import com.game.proto.eBattleActionType;
import com.game.proto.eBattleEventType;
import com.game.table.BuffTable;
import com.game.table.ConfigTables;
import com.game.table.SkillTable;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 率土式「每回合叠加至战斗结束（5 层封顶）」3 个永久成长战法战斗引擎单测：
 * 12020 愈战愈勇（物理直伤）、12021 深谋远虑（法术直伤）、12022 步步为营（自身防御）。
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
    private static final int SKILL_O01 = 14001;           // 后发制人（全队 OutgoingDamage +5%×3）
    private static final int SKILL_A34 = 10034;           // 驱散增益

    private static final int BUFF_GROWING_PHYS = 120200;
    private static final int BUFF_GROWING_MAGIC = 120210;
    private static final int BUFF_GROWING_DEF = 120220;

    private static final int TEST_IDLE_SKILL = 88000;
    private static final int TEST_PHYS_STRIKE = 88001;
    private static final int TEST_MAGIC_STRIKE = 88002;
    private static final int TEST_KILL_STRIKE = 88003;
    private static final int TEST_REVIVE_SKILL = 88004;

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
}
