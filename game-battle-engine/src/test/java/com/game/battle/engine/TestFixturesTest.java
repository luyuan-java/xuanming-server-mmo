package com.game.battle.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.BattleAction;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.CreateBattleRequest;
import com.game.proto.eBattleActionType;
import com.game.table.SkillTable;
import org.junit.jupiter.api.Test;

/** 测试夹具 {@link MemoryBattleData}、{@link TestTables}、{@link TestBattles} 自身的语义（与基线 {@code memory_battle_data_provider.h}、{@code turn_battle_engine_test.cpp} 的工具函数一致）。 */
class TestFixturesTest {

    @Test
    void 重复add返回同一行_开局前后的修改都可见() {
        MemoryBattleData data = TestTables.standard();
        assertThat(data.addSkill(TestTables.SKILL_DAMAGE)).isSameAs(data.addSkill(TestTables.SKILL_DAMAGE));
        SkillTable before = data.skill(TestTables.SKILL_DAMAGE).orElseThrow();
        data.addSkill(TestTables.SKILL_DAMAGE).setCooldownId(42);
        assertThat(data.skill(TestTables.SKILL_DAMAGE).orElseThrow().getCooldownId()).isEqualTo(42);
        assertThat(before.getCooldownId()).as("已经取出的行是不可变快照").isEqualTo(TestTables.COOLDOWN_GROUP);
        assertThat(data.addMonster(7000).getId()).isEqualTo(7000);
    }

    @Test
    void 表达式定值忽略参数_没设过的为0() {
        MemoryBattleData data = TestTables.standard();
        assertThat(data.skillDamage(TestTables.SKILL_DAMAGE, 1)).isEqualTo(50.0);
        assertThat(data.skillDamage(TestTables.SKILL_DAMAGE, 85)).isEqualTo(50.0);
        assertThat(data.skillDamage(TestTables.SKILL_POISON, 10)).isZero();
        assertThat(data.buffHealthRegeneration(TestTables.BUFF_REGEN, 10, 999)).isEqualTo(25.0);
        assertThat(data.buffHealthRegeneration(TestTables.BUFF_POISON, 10, 999)).isZero();
    }

    @Test
    void 副本怪物组原样返回_不过滤0() {
        MemoryBattleData data = TestTables.standard();
        assertThat(data.dungeonMonsterIds(TestTables.DUNGEON_CONFIG)).isEmpty();
        data.setDungeonMonsters(8, 0, 7001, 0);
        assertThat(data.dungeonMonsterIds(8)).containsExactly(0, 7001, 0);
        assertThat(TestTables.withDrops().dungeonMonsterIds(TestTables.DUNGEON_WITH_DROP)).containsExactly(TestTables.MONSTER_WITH_DROP);
    }

    @Test
    void 标准表与基线MakeProvider一致() {
        MemoryBattleData data = TestTables.standard();
        assertThat(data.dungeon(TestTables.DUNGEON_CONFIG)).as("副本 7 没有行：回合上限缺省、兜底怪").isEmpty();
        assertThat(data.cooldownDurationMs(TestTables.COOLDOWN_GROUP)).isEqualTo(12000);
        assertThat(data.cooldownDurationMs(1)).isZero();
        assertThat(data.skillPermission(BattleConstants.COMBAT_STATE_SILENCE).orElseThrow().getSkillTypeList())
                .containsExactly(1000, 7005, 7005, 1000, 1000, 1000);
        assertThat(data.buff(TestTables.BUFF_POISON).orElseThrow().getTagMap()).containsOnlyKeys("poison_tag");
        assertThat(data.item(TestTables.ITEM_POTION).orElseThrow().getBattleHealHp()).isEqualTo(100);
        assertThat(data.monster(TestTables.MONSTER_WITH_DROP)).isEmpty();
        assertThat(TestTables.withDrops().monster(TestTables.MONSTER_WITH_DROP).orElseThrow().getDrop(0).getDropRate()).isEqualTo(10000);
    }

    @Test
    void 请求构造工具与基线一致_子builder的修改反映到请求里() {
        CreateBattleRequest.Builder request = TestBattles.request(BattleConstants.MATCH_MODE_PVE_SOLO, 42);
        BattlePlayerSnapshot.Builder player = TestBattles.addPlayer(request, TestTables.PLAYER_A, 0, 1000, 1000, 4, 100, 50, 120);
        TestBattles.addPet(player, TestTables.PET_A, 400, 400, 10, 360);
        player.setMaxMana(100); // 加完之后再改，同基线返回的可变指针
        CreateBattleRequest built = request.build();
        assertThat(built.getBattleId()).isEqualTo(TestBattles.DEFAULT_BATTLE_ID);
        assertThat(built.getBattleConfigId()).isEqualTo(TestTables.DUNGEON_CONFIG);
        assertThat(built.getSeed()).isEqualTo(42);
        BattlePlayerSnapshot snapshot = built.getPlayers(0);
        assertThat(snapshot.getPlayerName()).isEqualTo("测试玩家");
        assertThat(snapshot.getLevel()).isEqualTo(10);
        assertThat(snapshot.getMaxMana()).isEqualTo(100);
        assertThat(snapshot.getBaseAttributes().getCritchance()).isEqualTo(50);
        assertThat(snapshot.getSkillTableIdsList()).containsExactly(101, 102, 103, 104);
        assertThat(snapshot.getPets(0).getOwnerPlayerId()).isEqualTo(TestTables.PLAYER_A);
        assertThat(snapshot.getPets(0).getLevel()).isEqualTo(10);
        assertThat(snapshot.getPets(0).getBaseAttributes().getSpeed()).isEqualTo(360);
        BattleAction action = TestBattles.action(eBattleActionType.BATTLE_ACTION_SKILL, TestTables.MONSTER_ID, TestTables.SKILL_DAMAGE);
        assertThat(action.getTargetId()).isEqualTo(TestTables.MONSTER_ID);
        assertThat(action.getItemTableId()).isZero();
    }
}
