package com.game.battle.engine;

import com.game.table.BuffTable;
import com.game.table.DungeonTable;
import com.game.table.ItemTable;
import com.game.table.MonsterTable;
import com.game.table.SkillPermissionTable;
import com.game.table.SkillTable;
import java.util.List;
import java.util.Optional;

/**
 * 回合制战斗引擎读配置的唯一入口（基线 {@code battle_data_provider.h:20-53}，规格 §9.1）。
 *
 * <p>生产实现 {@link TableBattleData} 绑定一份 {@code ConfigTables} 快照；单测用内存实现直接喂行。引擎只经由本接口读表，
 * 不碰全局表、不碰时钟。表 id 都是 uint32（{@code int} 承载，按无符号解释）。
 *
 * <p>契约：
 * <ul>
 *   <li>行查询查不到返回 {@link Optional#empty()}，引擎视为配置缺失；</li>
 *   <li>同一实例整局内返回的数据不变（快照语义），实现必须可从引擎所在线程安全调用；</li>
 *   <li>两个表达式方法必须确定：同参同值，不得使用任何随机源（规格 D4）。</li>
 * </ul>
 * 基线的 {@code GetBuffBonusDamage} 没有调用方，不移植（规格 D8）。
 */
public interface BattleData {

    /** SkillTable 行（{@code FindSkill}）。 */
    Optional<SkillTable> skill(int skillTableId);

    /** BuffTable 行（{@code FindBuff}）。 */
    Optional<BuffTable> buff(int buffTableId);

    /** SkillPermission 行，行 id 是战斗状态号（沉默 = {@link BattleConstants#COMBAT_STATE_SILENCE}）（{@code FindSkillPermission}）。 */
    Optional<SkillPermissionTable> skillPermission(int combatStateId);

    /** DungeonTable 行（{@code FindDungeon}）。 */
    Optional<DungeonTable> dungeon(int dungeonTableId);

    /** MonsterTable 行（{@code FindMonster}）。 */
    Optional<MonsterTable> monster(int monsterTableId);

    /** ItemTable 行；引擎只读 battle_usable / battle_heal_hp / battle_heal_mp（{@code FindItem}）。 */
    Optional<ItemTable> item(int itemTableId);

    /** 冷却时长毫秒（uint64，按无符号解释）；查不到返回 0（{@code GetCooldownDurationMs}）。 */
    long cooldownDurationMs(int cooldownTableId);

    /**
     * 副本怪物组，按表内顺序（{@code GetDungeonMonsterIds}）。生产实现跳过 0、缺行返回空列表；
     * 单测内存实现原样返回设置的列表（不过滤 0）。返回的列表不可变。
     */
    List<Integer> dungeonMonsterIds(int dungeonTableId);

    /** 技能伤害公式 {@code Skill.damage}，参数 {@code level = casterLevel}（{@code GetSkillDamage}）。引擎只在查到行之后调用。 */
    double skillDamage(int skillTableId, double casterLevel);

    /**
     * buff 回血公式 {@code Buff.health_regeneration}，按位置传参 {@code (level, lostHealth)}（{@code GetBuffHealthRegeneration}）。
     * schema 里第二个参数名叫 {@code health}，但引擎传的是已损失气血（基线 {@code engine.cpp:1125-1128}）。
     */
    double buffHealthRegeneration(int buffTableId, double level, double lostHealth);
}
