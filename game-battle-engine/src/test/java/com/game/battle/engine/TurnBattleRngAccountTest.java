package com.game.battle.engine;

import static com.game.battle.engine.EngineTestSupport.describeAll;
import static com.game.battle.engine.TestBattles.action;
import static com.game.battle.engine.TestBattles.addPlayer;
import static com.game.battle.engine.TestBattles.request;
import static com.game.battle.engine.TestTables.DUNGEON_WITH_DROP;
import static com.game.battle.engine.TestTables.ITEM_MANA_POTION;
import static com.game.battle.engine.TestTables.ITEM_POTION;
import static com.game.battle.engine.TestTables.MONSTER_ID;
import static com.game.battle.engine.TestTables.MONSTER_WITH_DROP;
import static com.game.battle.engine.TestTables.PLAYER_A;
import static com.game.battle.engine.TestTables.PLAYER_B;
import static com.game.battle.engine.TestTables.SKILL_POISON;
import static com.game.battle.engine.TestTables.standard;
import static com.game.battle.engine.TestTables.withDrops;
import static com.game.proto.eBattleActionType.BATTLE_ACTION_ATTACK;
import static com.game.proto.eBattleActionType.BATTLE_ACTION_DEFEND;
import static com.game.proto.eBattleActionType.BATTLE_ACTION_SKILL;
import static com.game.proto.eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.BattleItemEntry;
import com.game.proto.CreateBattleRequest;
import com.game.proto.TurnResultS2C;
import com.game.table.Monsterdrop;
import org.junit.jupiter.api.Test;

/**
 * 随机数消耗账（规格 §8.4，§13.1「抽数计数」）：用 {@code rngDrawsForTest()} 逐类钉住各消耗点。期望抽数按 §8.4 的表手算
 * （Java 追加，本稿外手算）；R1 单候选、R4 暴击、R6 掉落的组合另见 {@link TurnBattleTraceTest} 各轨迹的抽数，R3 见
 * {@link TurnBattleSkillTest}，结束后不耗随机数见 {@link TurnBattleActionTest}。
 */
class TurnBattleRngAccountTest {

    private static final int PVE_SOLO = BattleConstants.MATCH_MODE_PVE_SOLO;
    private static final int PVE_TEAM = BattleConstants.MATCH_MODE_PVE_TEAM;

    private static TurnBattleEngine solo(long critChance, MemoryBattleData data, int... extraSkills) {
        CreateBattleRequest.Builder request = request(9660, PVE_SOLO, 1);
        var player = addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, critChance, 120);
        for (int skill : extraSkills) {
            player.addSkillTableIds(skill);
        }
        return TestBattles.start(request, data);
    }

    // R1：显式指定存活的敌方目标不耗随机数；默认目标 0 必然重选，只有 1 个候选也耗 1 次
    @Test
    void 普攻显式目标不抽_默认目标单候选也抽() {
        TurnBattleEngine explicit = solo(0, standard());
        explicit.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, MONSTER_ID));
        explicit.resolveCurrentRound();
        assertThat(explicit.rngDrawsForTest()).as("只有怪物选目标").isEqualTo(1);

        TurnBattleEngine fallback = solo(0, standard());
        fallback.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, 0));
        fallback.resolveCurrentRound();
        assertThat(fallback.rngDrawsForTest()).isEqualTo(2);
    }

    // R4：暴击率 > 0 时每次算伤害各掷一次；技能 baseDamage 为 0（纯 buff 技能）时不算伤害、不掷暴击
    @Test
    void 暴击率大于0时每次算伤害掷一次_纯buff技能不掷() {
        TurnBattleEngine attacker = solo(50, standard());
        attacker.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, MONSTER_ID));
        attacker.resolveCurrentRound();
        assertThat(attacker.rngDrawsForTest()).as("A 暴击 1 + 怪物选目标 1").isEqualTo(2);

        TurnBattleEngine poisoner = solo(50, standard());
        poisoner.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, SKILL_POISON));
        poisoner.resolveCurrentRound();
        assertThat(poisoner.rngDrawsForTest()).as("只有怪物选目标").isEqualTo(1);
    }

    // R2：单体技能的目标无效（不存在）时从存活敌方里 RandIndex 选一个；只对首个位号是无目标、认不出、或模式为空的单体技能可达
    @Test
    void 单体技能目标无效时重选耗一次() {
        MemoryBattleData data = standard();
        data.addSkill(118).addTargetingMode(0).addSkillType(1); // 无目标位号：只要 target ≠ 0
        data.addSkill(119).addSkillType(1);                     // 模式为空：目标 0 也放行
        TurnBattleEngine noTarget = solo(0, data, 118);
        noTarget.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, 999999, 118));
        TurnResultS2C first = noTarget.resolveCurrentRound();
        assertThat(describeAll(first).get(0)).isEqualTo("g1 SKILL 5001->M0 skill118");
        assertThat(noTarget.rngDrawsForTest()).isEqualTo(2);

        TurnBattleEngine empty = solo(0, data, 119);
        empty.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, 0, 119));
        TurnResultS2C second = empty.resolveCurrentRound();
        assertThat(describeAll(second).get(0)).isEqualTo("g1 SKILL 5001->M0 skill119");
        assertThat(empty.rngDrawsForTest()).isEqualTo(2);
    }

    // R6：刚判出 A 方胜时，按「合格玩家（player_id 升序）× 击杀簿 × 非空掉落槽（表序）」逐个掷；drop_rate ≥ 10000 也照样掷；
    // drop_item / drop_count / drop_rate 任一为 0 即空槽，不掷
    @Test
    void 掉落每人每只每个非空槽各掷一次() {
        MemoryBattleData data = withDrops();
        var monster = data.addMonster(MONSTER_WITH_DROP);
        monster.addDrop(Monsterdrop.newBuilder().setDropItem(ITEM_POTION).setDropCount(1).setDropRate(0));
        monster.addDrop(Monsterdrop.newBuilder().setDropItem(0).setDropCount(1).setDropRate(5000));
        monster.addDrop(Monsterdrop.newBuilder().setDropItem(ITEM_MANA_POTION).setDropCount(0).setDropRate(5000));
        monster.addDrop(Monsterdrop.newBuilder().setDropItem(ITEM_MANA_POTION).setDropCount(1).setDropRate(20000));

        CreateBattleRequest.Builder request = request(9661, PVE_TEAM, 1).setBattleConfigId(DUNGEON_WITH_DROP);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 500, 0, 0, 500);
        addPlayer(request, PLAYER_B, 0, 1000, 1000, 500, 0, 0, 400);
        TurnBattleEngine engine = TestBattles.start(request, data);
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, 0));
        engine.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
        engine.resolveCurrentRound();
        assertThat(engine.outcome()).isEqualTo(BATTLE_OUTCOME_SIDE_A_WIN);

        // A 选目标 1 抽；掉落 2 人 × 1 只 × 2 个非空槽 = 4 抽，两个非空槽的掉率都 ≥ 10000，必掉
        assertThat(engine.rngDrawsForTest()).isEqualTo(5);
        for (long player : new long[] {PLAYER_A, PLAYER_B}) {
            assertThat(engine.buildSettlement(player).getItemsGainedList()).containsExactly(
                    BattleItemEntry.newBuilder().setItemTableId(ITEM_POTION).setCount(2).build(),
                    BattleItemEntry.newBuilder().setItemTableId(ITEM_MANA_POTION).setCount(1).build());
        }
    }
}
