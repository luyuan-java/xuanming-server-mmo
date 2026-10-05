package com.game.battle.engine;

import static com.game.battle.engine.EngineTestSupport.describe;
import static com.game.battle.engine.EngineTestSupport.describeAll;
import static com.game.battle.engine.TestBattles.action;
import static com.game.battle.engine.TestBattles.addPlayer;
import static com.game.battle.engine.TestBattles.countEvents;
import static com.game.battle.engine.TestBattles.firstEvent;
import static com.game.battle.engine.TestBattles.request;
import static com.game.battle.engine.TestBattles.stateActor;
import static com.game.battle.engine.TestTables.BUFF_IMMUNE_POISON;
import static com.game.battle.engine.TestTables.BUFF_INSTANT_TABLE;
import static com.game.battle.engine.TestTables.BUFF_POISON;
import static com.game.battle.engine.TestTables.BUFF_REGEN;
import static com.game.battle.engine.TestTables.BUFF_SILENCE;
import static com.game.battle.engine.TestTables.BUFF_STUN_TABLE;
import static com.game.battle.engine.TestTables.MONSTER_ID;
import static com.game.battle.engine.TestTables.PLAYER_A;
import static com.game.battle.engine.TestTables.PLAYER_B;
import static com.game.battle.engine.TestTables.SKILL_DISPEL;
import static com.game.battle.engine.TestTables.SKILL_POISON;
import static com.game.battle.engine.TestTables.standard;
import static com.game.proto.eBattleActionType.BATTLE_ACTION_ATTACK;
import static com.game.proto.eBattleActionType.BATTLE_ACTION_DEFEND;
import static com.game.proto.eBattleActionType.BATTLE_ACTION_SKILL;
import static com.game.proto.eBattleEventType.BATTLE_EVENT_BUFF_ADD;
import static com.game.proto.eBattleEventType.BATTLE_EVENT_BUFF_REMOVE;
import static com.game.proto.eBattleEventType.BATTLE_EVENT_BUFF_TICK;
import static com.game.proto.eBattleEventType.BATTLE_EVENT_DAMAGE;
import static com.game.proto.eBattleEventType.BATTLE_EVENT_DEATH;
import static com.game.proto.eBattleOutcome.BATTLE_OUTCOME_DRAW;
import static com.game.proto.eBattleOutcome.BATTLE_OUTCOME_ONGOING;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.BattleActorState;
import com.game.proto.BattleBuffEntry;
import com.game.proto.BattleEventItem;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.CreateBattleRequest;
import com.game.proto.TurnResultS2C;
import com.game.table.BuffTable;
import org.junit.jupiter.api.Test;

/**
 * buff（规格 §5）：周期 tick 与到期、叠层、驱散、免疫、快照 buff、同回合毒死平局、快照 buff 清洗、子 buff、瞬时 buff。
 *
 * <p>前 7 个方法逐条移植基线 {@code turn_battle_engine_test.cpp}（【C++ 断言】）；标「Java 追加」的用例按规格 §5 的条文手算，
 * 涉及随机数的地方都让结果与抽签无关（怪物只有一个候选目标、玩家暴击率为 0）。
 */
class TurnBattleBuffTest {

    private static final int PVE_SOLO = BattleConstants.MATCH_MODE_PVE_SOLO;
    private static final int PVE_TEAM = BattleConstants.MATCH_MODE_PVE_TEAM;
    private static final int PVP_1V1 = 3;

    private static BattleBuffEntry entry(long buffId, int buffTableId, int layer, int remainRounds, long casterId) {
        return BattleBuffEntry.newBuilder()
                .setBuffId(buffId)
                .setBuffTableId(buffTableId)
                .setLayer(layer)
                .setRemainRounds(remainRounds)
                .setCasterId(casterId)
                .build();
    }

    // 基线 PoisonBuffTicksEachRoundAndExpiresAfterTwoRounds（test.cpp:354-383）【C++ 断言】
    @Test
    void 毒每回合tick_两回合后到期() {
        CreateBattleRequest.Builder request = request(9006, PVE_SOLO, 1);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120);
        TurnBattleEngine engine = TestBattles.start(request, standard());

        // 第 1 回合：挂毒 → BUFF_ADD；回合末第一跳（每层 10 点）
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, SKILL_POISON));
        TurnResultS2C result = engine.resolveCurrentRound();
        assertThat(countEvents(result, BATTLE_EVENT_BUFF_ADD)).isEqualTo(1);
        BattleEventItem tick = firstEvent(result, BATTLE_EVENT_BUFF_TICK);
        assertThat(tick).isNotNull();
        assertThat(tick.getBuffTableId()).isEqualTo(BUFF_POISON);
        assertThat(tick.getValue()).isEqualTo(10);
        assertThat(tick.getTargetId()).isEqualTo(MONSTER_ID);

        // 第 2 回合：第二跳后到期移除
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND));
        result = engine.resolveCurrentRound();
        assertThat(countEvents(result, BATTLE_EVENT_BUFF_TICK)).isEqualTo(1);
        assertThat(countEvents(result, BATTLE_EVENT_BUFF_REMOVE)).isEqualTo(1);

        // 第 3 回合：毒已不在
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND));
        result = engine.resolveCurrentRound();
        assertThat(countEvents(result, BATTLE_EVENT_BUFF_TICK)).isZero();
        BattleActorState monster = stateActor(result.getState(), MONSTER_ID);
        assertThat(monster).isNotNull();
        assertThat(monster.getBuffsCount()).isZero();
    }

    // 基线 BuffStacksUpToMaxLayer（test.cpp:385-405）【C++ 断言】
    @Test
    void 叠层到max_layer封顶() {
        CreateBattleRequest.Builder request = request(9007, PVE_SOLO, 1);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120);
        TurnBattleEngine engine = TestBattles.start(request, standard());

        // 连续 4 回合重复挂毒：层数 1 → 2 → 3 → 3（max_layer = 3 封顶）
        for (int expectedLayer : new int[] {1, 2, 3, 3}) {
            engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, SKILL_POISON));
            TurnResultS2C result = engine.resolveCurrentRound();
            BattleEventItem add = firstEvent(result, BATTLE_EVENT_BUFF_ADD);
            assertThat(add).isNotNull();
            assertThat(add.getValue()).as("value 携带当前叠层数").isEqualTo(expectedLayer);

            BattleActorState monster = stateActor(result.getState(), MONSTER_ID);
            assertThat(monster).isNotNull();
            assertThat(monster.getBuffsCount()).isEqualTo(1);
            assertThat(monster.getBuffs(0).getLayer()).isEqualTo(expectedLayer);
        }
    }

    // 基线 DispelSkillRemovesPoisonWithoutLeavingEntry（test.cpp:407-426）【C++ 断言】
    @Test
    void 驱散技能移除毒且自身不落地() {
        CreateBattleRequest.Builder request = request(9008, PVE_SOLO, 1);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120);
        TurnBattleEngine engine = TestBattles.start(request, standard());

        // 第 1 回合挂毒
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, SKILL_POISON));
        engine.resolveCurrentRound();

        // 第 2 回合驱散：毒被移除，纯驱散 buff 自身不落地，回合末无毒 tick
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, SKILL_DISPEL));
        TurnResultS2C result = engine.resolveCurrentRound();
        assertThat(countEvents(result, BATTLE_EVENT_BUFF_REMOVE)).isEqualTo(1);
        assertThat(countEvents(result, BATTLE_EVENT_BUFF_ADD)).isZero();
        assertThat(countEvents(result, BATTLE_EVENT_BUFF_TICK)).isZero();
        BattleActorState monster = stateActor(result.getState(), MONSTER_ID);
        assertThat(monster).isNotNull();
        assertThat(monster.getBuffsCount()).isZero();
    }

    // 基线 ImmuneTagBlocksPoison（test.cpp:428-450）【C++ 断言】
    @Test
    void 免疫tag挡下毒() {
        CreateBattleRequest.Builder request = request(9009, PVP_1V1, 1);
        addPlayer(request, PLAYER_A, 0, 500, 500, 0, 100, 0, 120);
        BattlePlayerSnapshot.Builder defender = addPlayer(request, PLAYER_B, 1, 500, 500, 0, 100, 0, 60);
        // B 携带免疫毒 tag 的参战 buff（无限持续，remain_rounds = 0 哨兵）
        defender.addBuffs(BattleBuffEntry.newBuilder().setBuffId(900).setBuffTableId(BUFF_IMMUNE_POISON).setLayer(1).setRemainRounds(0));
        TurnBattleEngine engine = TestBattles.start(request, standard());

        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, PLAYER_B, SKILL_POISON));
        engine.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
        TurnResultS2C result = engine.resolveCurrentRound();

        assertThat(countEvents(result, BATTLE_EVENT_BUFF_ADD)).as("免疫挡下").isZero();
        BattleActorState defenderState = stateActor(result.getState(), PLAYER_B);
        assertThat(defenderState).isNotNull();
        assertThat(defenderState.getBuffsCount()).as("只剩免疫 buff 本体").isEqualTo(1);
        assertThat(defenderState.getBuffs(0).getBuffTableId()).isEqualTo(BUFF_IMMUNE_POISON);
    }

    // 基线 SnapshotRegenBuffHealsAtRoundEnd（test.cpp:452-481）【C++ 断言】
    @Test
    void 快照带入的回血buff在回合末回血() {
        CreateBattleRequest.Builder request = request(9010, PVE_SOLO, 1);
        BattlePlayerSnapshot.Builder snapshot = addPlayer(request, PLAYER_A, 0, 100, 200, 0, 100, 0, 120);
        // 参战携带周期回血 buff（remain_rounds 已是回合口径：2 回合）
        snapshot.addBuffs(BattleBuffEntry.newBuilder().setBuffId(901).setBuffTableId(BUFF_REGEN).setLayer(1).setRemainRounds(2));
        TurnBattleEngine engine = TestBattles.start(request, standard());

        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND));
        TurnResultS2C result = engine.resolveCurrentRound();

        BattleEventItem tick = firstEvent(result, BATTLE_EVENT_BUFF_TICK);
        assertThat(tick).isNotNull();
        assertThat(tick.getBuffTableId()).isEqualTo(BUFF_REGEN);
        assertThat(tick.getValue()).isEqualTo(25);
        BattleActorState player = stateActor(result.getState(), PLAYER_A);
        assertThat(player).isNotNull();
        // 玩家本回合也会挨一下（防御中再减半）：终值 = 100 − 挨打 + 回血 25
        long damageTaken = result.getEventsList().stream()
                .filter(e -> e.getEventType() == BATTLE_EVENT_DAMAGE && e.getTargetId() == PLAYER_A)
                .mapToLong(BattleEventItem::getValue)
                .sum();
        assertThat(player.getAttributes().getHealth()).isEqualTo(100 - damageTaken + 25);
    }

    // 基线 SimultaneousPoisonDeathIsDrawAndDefendHalvesTick（test.cpp:548-573）【C++ 断言】
    @Test
    void 同回合双方毒死判平_防御让毒伤减半() {
        CreateBattleRequest.Builder request = request(9014, PVP_1V1, 1);
        addPlayer(request, PLAYER_A, 0, 15, 15, 0, 100, 0, 120);
        addPlayer(request, PLAYER_B, 1, 15, 15, 0, 100, 0, 60);
        TurnBattleEngine engine = TestBattles.start(request, standard());

        // 第 1 回合：互相挂毒；回合末各掉 10 → 双方剩 5
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, PLAYER_B, SKILL_POISON));
        engine.submitAction(PLAYER_B, action(BATTLE_ACTION_SKILL, PLAYER_A, SKILL_POISON));
        TurnResultS2C result = engine.resolveCurrentRound();
        BattleEventItem firstTick = firstEvent(result, BATTLE_EVENT_BUFF_TICK);
        assertThat(firstTick).isNotNull();
        assertThat(firstTick.getValue()).isEqualTo(10);
        assertThat(engine.outcome()).isEqualTo(BATTLE_OUTCOME_ONGOING);

        // 第 2 回合：双方防御；毒 tick 减半（10 → 5）仍致死 → 同回合双死判平
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND));
        engine.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
        result = engine.resolveCurrentRound();
        BattleEventItem halvedTick = firstEvent(result, BATTLE_EVENT_BUFF_TICK);
        assertThat(halvedTick).isNotNull();
        assertThat(halvedTick.getValue()).as("DEFEND 覆盖回合末周期伤害").isEqualTo(5);
        assertThat(countEvents(result, BATTLE_EVENT_DEATH)).isEqualTo(2);
        assertThat(engine.outcome()).isEqualTo(BATTLE_OUTCOME_DRAW);
    }

    // 基线 SnapshotBuffsDropControlInstantUnknownAndSanitizeCaster（test.cpp:2056-2096）【C++ 断言】
    @Test
    void 快照buff清洗_丢控制类瞬时类未知行_施法者认不出置0() {
        MemoryBattleData data = standard();
        BuffTable.Builder stun = data.addBuff(BUFF_STUN_TABLE);
        stun.setBuffType(BattleConstants.BUFF_TYPE_STUN);
        stun.setDuration(12.0);
        BuffTable.Builder instant = data.addBuff(BUFF_INSTANT_TABLE);
        instant.setBuffType(BattleConstants.BUFF_TYPE_POISON);
        instant.setDuration(0.0); // 非无限 + duration <= 0 = 瞬时

        CreateBattleRequest.Builder request = request(9308, PVE_SOLO, 7);
        BattlePlayerSnapshot.Builder snapshot = addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 500);
        // 控制类：必须丢掉
        snapshot.addBuffs(BattleBuffEntry.newBuilder().setBuffId(11).setBuffTableId(BUFF_STUN_TABLE).setRemainRounds(2));
        // 瞬时类：实时侧挂上即到期，不该在战斗里永驻
        snapshot.addBuffs(BattleBuffEntry.newBuilder().setBuffId(12).setBuffTableId(BUFF_INSTANT_TABLE).setRemainRounds(0));
        // 表里没有的 buff：丢掉
        snapshot.addBuffs(BattleBuffEntry.newBuilder().setBuffId(13).setBuffTableId(9999));
        // 合法 buff，但 caster 是本局不存在的 id
        snapshot.addBuffs(BattleBuffEntry.newBuilder().setBuffId(14).setBuffTableId(BUFF_POISON).setRemainRounds(2).setCasterId(777777));
        TurnBattleEngine engine = TestBattles.start(request, data);

        BattleActorState actor = stateActor(engine.buildStateSnapshot(), PLAYER_A);
        assertThat(actor).isNotNull();
        assertThat(actor.getBuffsCount()).isEqualTo(1);
        assertThat(actor.getBuffs(0).getBuffTableId()).isEqualTo(BUFF_POISON);
        assertThat(actor.getBuffs(0).getCasterId()).as("认不出的施法者置 0").isZero();
    }

    // ---- Java 追加（规格 §5） ----

    // 规格 §5.8 / §1.5：剩余回合夹到表全量时长（无限条目保留快照值），层数不夹，认得出的施法者保留（含怪物）；
    // 实例号计数器在清洗之前按全部快照 buff_id 推高，被丢掉的条目也算
    @Test
    void 快照buff清洗的剩余回合与施法者口径_实例号按全部快照推高() {
        CreateBattleRequest.Builder request = request(9630, PVE_SOLO, 1);
        BattlePlayerSnapshot.Builder snapshot = addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120);
        snapshot.addBuffs(entry(50, 9999, 1, 1, 0));                       // 未知行：丢弃，但把计数器推到 51
        snapshot.addBuffs(entry(7, BUFF_POISON, 0, 0, MONSTER_ID));        // 有限，remain 0 → 2；施法者是本局怪物
        snapshot.addBuffs(entry(8, BUFF_IMMUNE_POISON, 4, 3, 777));        // 无限：remain 保留 3；施法者认不出 → 0
        snapshot.addBuffs(entry(9, BUFF_REGEN, 1, 5, PLAYER_A));           // 有限，remain 5 > 2 → 2
        TurnBattleEngine engine = TestBattles.start(request, standard());

        assertThat(engine.addBuffForTest(PLAYER_A, BUFF_SILENCE, MONSTER_ID)).isTrue();
        assertThat(stateActor(engine.buildStateSnapshot(), PLAYER_A).getBuffsList()).containsExactly(
                entry(7, BUFF_POISON, 0, 2, MONSTER_ID),
                entry(8, BUFF_IMMUNE_POISON, 4, 3, 0),
                entry(9, BUFF_REGEN, 1, 2, PLAYER_A),
                entry(51, BUFF_SILENCE, 1, 2, MONSTER_ID));
    }

    // 规格 §5.2 第 7、11 步：瞬时 buff 先出 ADD 再立即出 REMOVE，照样消耗一个实例号
    @Test
    void 瞬时buff挂上即移除且消耗实例号() {
        MemoryBattleData data = standard();
        data.addBuff(260); // 类型 0、非无限、duration 0 → 瞬时
        data.addSkill(114).addTargetingMode(1).addSkillType(1).addEffect(260);
        CreateBattleRequest.Builder request = request(9631, PVE_SOLO, 1);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120).addSkillTableIds(114);
        TurnBattleEngine engine = TestBattles.start(request, data);

        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, 114));
        TurnResultS2C result = engine.resolveCurrentRound();
        assertThat(describeAll(result)).containsExactly(
                "g1 SKILL 5001->M0 skill114",
                "g1 BUFF_ADD 5001->M0 buff260 v1",
                "g1 BUFF_REMOVE 5001->M0 buff260",
                "g2 ATTACK M0->5001",
                "g2 DAMAGE M0->5001 v15 hp985");
        assertThat(engine.addBuffForTest(MONSTER_ID, BUFF_POISON, PLAYER_A)).isTrue();
        assertThat(stateActor(engine.buildStateSnapshot(), MONSTER_ID).getBuffs(0).getBuffId()).as("实例号 1 已被瞬时 buff 用掉").isEqualTo(2);
    }

    // 规格 §5.2 第 9、10 步：sub_buff 挂给持有者（施法者不变），target_sub_buff 挂回施法者（方向对调）
    @Test
    void 子buff挂给持有者_target_sub_buff挂回施法者() {
        MemoryBattleData data = standard();
        data.addBuff(261).setDuration(12.0).addSubBuff(262).addTargetSubBuff(263);
        data.addBuff(262).setDuration(12.0);
        data.addBuff(263).setDuration(12.0);
        data.addSkill(115).addTargetingMode(1).addSkillType(1).addEffect(261);
        CreateBattleRequest.Builder request = request(9632, PVE_SOLO, 1);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120).addSkillTableIds(115);
        TurnBattleEngine engine = TestBattles.start(request, data);

        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, 115));
        TurnResultS2C result = engine.resolveCurrentRound();
        // 回合末 A（g3）与 M0（g4）都有 buff，各占一组但不出事件（类型 0 没有周期效果，2 回合未到期）
        assertThat(describeAll(result)).containsExactly(
                "g1 SKILL 5001->M0 skill115",
                "g1 BUFF_ADD 5001->M0 buff261 v1",
                "g1 BUFF_ADD 5001->M0 buff262 v1",
                "g1 BUFF_ADD M0->5001 buff263 v1",
                "g2 ATTACK M0->5001",
                "g2 DAMAGE M0->5001 v15 hp985");
        assertThat(stateActor(result.getState(), PLAYER_A).getBuffsList()).containsExactly(entry(3, 263, 1, 1, MONSTER_ID));
        assertThat(stateActor(result.getState(), MONSTER_ID).getBuffsList()).containsExactly(
                entry(1, 261, 1, 1, PLAYER_A),
                entry(2, 262, 1, 1, PLAYER_A));
    }

    // 规格 §5.2 第 1 步：判定是 depth > 8，深度 0..8 共 9 层都生效
    @Test
    void 子buff递归深度0到8都生效_第9层截断() {
        MemoryBattleData data = standard();
        for (int id = 271; id <= 281; id++) {
            BuffTable.Builder buff = data.addBuff(id).setDuration(12.0);
            if (id < 281) {
                buff.addSubBuff(id + 1);
            }
        }
        data.addSkill(116).addTargetingMode(1).addSkillType(1).addEffect(271);
        CreateBattleRequest.Builder request = request(9633, PVE_SOLO, 1);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120).addSkillTableIds(116);
        TurnBattleEngine engine = TestBattles.start(request, data);

        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, 116));
        TurnResultS2C result = engine.resolveCurrentRound();
        assertThat(countEvents(result, BATTLE_EVENT_BUFF_ADD)).isEqualTo(9);
        assertThat(stateActor(result.getState(), MONSTER_ID).getBuffsList())
                .extracting(BattleBuffEntry::getBuffTableId)
                .containsExactly(271, 272, 273, 274, 275, 276, 277, 278, 279);
    }

    // 规格 §5.3：叠层按施法者隔离（no_caster 时不分施法者，条目保留原施法者）；max_layer 为 0 时永不加层
    @Test
    void 叠层按施法者隔离_no_caster不分施法者_max_layer为0不加层() {
        MemoryBattleData data = standard();
        data.addBuff(282).setDuration(12.0).setMaxLayer(3).setNoCaster(1);
        data.addBuff(283).setDuration(12.0).setMaxLayer(0);
        CreateBattleRequest.Builder request = request(9634, PVE_TEAM, 1);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120);
        addPlayer(request, PLAYER_B, 0, 1000, 1000, 0, 100, 0, 120);
        TurnBattleEngine engine = TestBattles.start(request, data);

        assertThat(engine.addBuffForTest(MONSTER_ID, BUFF_POISON, PLAYER_A)).isTrue();
        assertThat(engine.addBuffForTest(MONSTER_ID, BUFF_POISON, PLAYER_B)).isTrue();
        assertThat(engine.addBuffForTest(MONSTER_ID, 282, PLAYER_A)).isTrue();
        assertThat(engine.addBuffForTest(MONSTER_ID, 282, PLAYER_B)).isTrue();
        assertThat(engine.addBuffForTest(MONSTER_ID, 283, PLAYER_A)).isTrue();
        assertThat(engine.addBuffForTest(MONSTER_ID, 283, PLAYER_A)).isTrue();
        assertThat(stateActor(engine.buildStateSnapshot(), MONSTER_ID).getBuffsList()).containsExactly(
                entry(1, BUFF_POISON, 1, 2, PLAYER_A),
                entry(2, BUFF_POISON, 1, 2, PLAYER_B),
                entry(3, 282, 2, 2, PLAYER_A),
                entry(4, 283, 1, 2, PLAYER_A));
    }

    // 规格 §5.3：驱散先按下标升序收集，再按下标降序移除，BUFF_REMOVE 的 source 是条目自己的施法者
    @Test
    void 驱散按插入序的逆序移除() {
        MemoryBattleData data = standard();
        data.addBuff(284).setDuration(12.0).putTag("x", true);
        data.addBuff(285).setDuration(12.0).putTag("x", true).putTag("y", true);
        data.addBuff(286).setDuration(12.0).putTag("z", true);
        data.addBuff(287).setBuffType(BattleConstants.BUFF_TYPE_DISPEL).putDispelTag("x", true);
        data.addSkill(117).addTargetingMode(1).addSkillType(1).addEffect(287);
        CreateBattleRequest.Builder request = request(9635, PVE_SOLO, 1);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120).addSkillTableIds(117);
        TurnBattleEngine engine = TestBattles.start(request, data);
        assertThat(engine.addBuffForTest(MONSTER_ID, 284, 0)).isTrue();
        assertThat(engine.addBuffForTest(MONSTER_ID, 286, PLAYER_A)).isTrue();
        assertThat(engine.addBuffForTest(MONSTER_ID, 285, MONSTER_ID)).isTrue();

        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, 117));
        TurnResultS2C result = engine.resolveCurrentRound();
        assertThat(describeAll(result).subList(0, 3)).containsExactly(
                "g1 SKILL 5001->M0 skill117",
                "g1 BUFF_REMOVE M0->M0 buff285",
                "g1 BUFF_REMOVE 0->M0 buff284");
        assertThat(stateActor(result.getState(), MONSTER_ID).getBuffsList())
                .extracting(BattleBuffEntry::getBuffTableId).containsExactly(286);
    }

    // 规格 §5.5：无限 buff 的 interval_count 按全局回合序号计数（基线行为，§11.1 第 11 条）
    @Test
    void 无限buff的周期次数按全局回合序号计() {
        MemoryBattleData data = standard();
        data.addBuff(288).setBuffType(BattleConstants.BUFF_TYPE_POISON).setInfiniteDuration(1)
                .setInterval(6.0).setIntervalCount(2).addIntervalEffect(5.0);
        data.addBuff(289).setBuffType(BattleConstants.BUFF_TYPE_BURN).setInfiniteDuration(1)
                .setInterval(6.0).setIntervalCount(1).addIntervalEffect(7.0);
        CreateBattleRequest.Builder request = request(9636, PVE_SOLO, 1);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120);
        TurnBattleEngine engine = TestBattles.start(request, data);
        assertThat(engine.addBuffForTest(MONSTER_ID, 288, PLAYER_A)).isTrue();

        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND));
        TurnResultS2C first = engine.resolveCurrentRound();
        assertThat(describe(firstEvent(first, BATTLE_EVENT_BUFF_TICK))).isEqualTo("g3 BUFF_TICK 5001->M0 buff288 v5 hp295");

        // 第 2 回合才挂上的 289（interval_count 1）：全局回合序号 2 > 1，一次也不 tick
        assertThat(engine.addBuffForTest(MONSTER_ID, 289, PLAYER_A)).isTrue();
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND));
        TurnResultS2C second = engine.resolveCurrentRound();
        assertThat(describeAll(second)).filteredOn(line -> line.contains("BUFF_TICK"))
                .containsExactly("g3 BUFF_TICK 5001->M0 buff288 v5 hp290");

        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND));
        TurnResultS2C third = engine.resolveCurrentRound();
        assertThat(countEvents(third, BATTLE_EVENT_BUFF_TICK)).isZero();
        assertThat(countEvents(third, BATTLE_EVENT_BUFF_REMOVE)).as("无限条目不递减、不到期").isZero();
        assertThat(stateActor(third.getState(), MONSTER_ID).getBuffsCount()).isEqualTo(2);
    }

    // 规格 §3.4 / §3.5：被眩晕的单位行动作废、不出事件，但组号照样被占用（事件流里的 group 号可能不连续）
    @Test
    void 眩晕跳过出手但占用组号() {
        MemoryBattleData data = standard();
        data.addBuff(BUFF_STUN_TABLE).setBuffType(BattleConstants.BUFF_TYPE_STUN).setDuration(12.0);
        CreateBattleRequest.Builder request = request(9637, PVE_SOLO, 1);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120);
        TurnBattleEngine engine = TestBattles.start(request, data);
        assertThat(engine.addBuffForTest(MONSTER_ID, BUFF_STUN_TABLE, PLAYER_A)).isTrue();

        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, MONSTER_ID));
        TurnResultS2C first = engine.resolveCurrentRound();
        // 10 × 1560 / (24 + 1560) = 9.85 → 10
        assertThat(describeAll(first)).containsExactly(
                "g1 ATTACK 5001->M0",
                "g1 DAMAGE 5001->M0 v10 hp290");

        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, MONSTER_ID));
        TurnResultS2C second = engine.resolveCurrentRound();
        assertThat(describeAll(second)).containsExactly(
                "g1 ATTACK 5001->M0",
                "g1 DAMAGE 5001->M0 v10 hp280",
                "g3 BUFF_REMOVE 5001->M0 buff250");
        assertThat(engine.rngDrawsForTest()).as("被眩晕的默认普攻不选目标").isZero();
    }

    // 规格 §3.7 / §11.1 第 8 条：当前气血超过上限时，回血量按 uint64 回绕成巨大值，当前值被拉低到上限（基线行为，照搬）
    @Test
    void 气血超过上限时回血量回绕() {
        CreateBattleRequest.Builder request = request(9638, PVE_SOLO, 1);
        BattlePlayerSnapshot.Builder snapshot = addPlayer(request, PLAYER_A, 0, 300, 200, 0, 100, 0, 120);
        snapshot.addBuffs(entry(901, BUFF_REGEN, 1, 2, 0));
        TurnBattleEngine engine = TestBattles.start(request, standard());

        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND));
        TurnResultS2C result = engine.resolveCurrentRound();
        // 怪物 15 × 1560/1660 × 0.5 = 7.05 → 8，300 → 292；回血后 min(200, 317) = 200，回血量 200 − 292 回绕成 2^64 − 92
        assertThat(describeAll(result)).containsExactly(
                "g1 DEFEND 5001->5001",
                "g2 ATTACK M0->5001",
                "g2 DAMAGE M0->5001 v8 hp292",
                "g3 BUFF_TICK 0->5001 buff240 v18446744073709551524 hp200");
        assertThat(EngineTestSupport.health(result.getState(), PLAYER_A)).isEqualTo(200);
    }
}
