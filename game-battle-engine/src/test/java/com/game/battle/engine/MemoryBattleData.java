package com.game.battle.engine;

import com.game.table.BuffTable;
import com.game.table.DungeonTable;
import com.game.table.ItemTable;
import com.game.table.MonsterTable;
import com.game.table.SkillPermissionTable;
import com.game.table.SkillTable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 单测用的内存表数据（逐项移植基线 {@code cpp/tests/turn_battle_engine_test/memory_battle_data_provider.h}，规格 §9.2）。
 *
 * <ul>
 *   <li>行以 builder 存在内存里。{@code addXxx(id)} 遇到已存在的 id 返回已有的那个 builder（并重设 id），用例靠这一点「改一列」；
 *       查询时现场 {@code build()}，所以开局前后对 builder 的修改都会被引擎看到（同基线返回行指针的语义）。</li>
 *   <li>两个表达式方法返回预设的定值，<strong>忽略</strong>等级与损血参数；没设过的返回 0.0。</li>
 *   <li>{@link #dungeonMonsterIds(int)} 原样返回设置的列表，<strong>不过滤 0</strong>；没设过的返回空列表。</li>
 * </ul>
 * 内存手搭的行不属于任何 {@code ConfigTables} 快照，所以不走生成的 {@code evalXxx}；这与基线单测口径一致：引擎只关心求值结果。
 * 只做查找、不遍历，用 {@link HashMap} 不影响确定性。基线的 {@code SetBuffBonusDamage} 不移植（D8）。
 */
final class MemoryBattleData implements BattleData {

    private final Map<Integer, SkillTable.Builder> skillRows = new HashMap<>();
    private final Map<Integer, BuffTable.Builder> buffRows = new HashMap<>();
    private final Map<Integer, SkillPermissionTable.Builder> permissionRows = new HashMap<>();
    private final Map<Integer, DungeonTable.Builder> dungeonRows = new HashMap<>();
    private final Map<Integer, MonsterTable.Builder> monsterRows = new HashMap<>();
    private final Map<Integer, ItemTable.Builder> itemRows = new HashMap<>();
    private final Map<Integer, Long> cooldownDurations = new HashMap<>();
    private final Map<Integer, List<Integer>> dungeonMonsterIds = new HashMap<>();
    private final Map<Integer, Double> skillDamageValues = new HashMap<>();
    private final Map<Integer, Double> buffRegenValues = new HashMap<>();

    // ---- 数据装配（对应 AddXxx / SetXxx） ----

    SkillTable.Builder addSkill(int skillTableId) {
        return skillRows.computeIfAbsent(skillTableId, id -> SkillTable.newBuilder()).setId(skillTableId);
    }

    BuffTable.Builder addBuff(int buffTableId) {
        return buffRows.computeIfAbsent(buffTableId, id -> BuffTable.newBuilder()).setId(buffTableId);
    }

    SkillPermissionTable.Builder addSkillPermission(int combatStateId) {
        return permissionRows.computeIfAbsent(combatStateId, id -> SkillPermissionTable.newBuilder()).setId(combatStateId);
    }

    DungeonTable.Builder addDungeon(int dungeonTableId) {
        return dungeonRows.computeIfAbsent(dungeonTableId, id -> DungeonTable.newBuilder()).setId(dungeonTableId);
    }

    MonsterTable.Builder addMonster(int monsterTableId) {
        return monsterRows.computeIfAbsent(monsterTableId, id -> MonsterTable.newBuilder()).setId(monsterTableId);
    }

    /** 战斗道具行：battle_usable / battle_heal_hp / battle_heal_mp 由用例自己填。 */
    ItemTable.Builder addItem(int itemTableId) {
        return itemRows.computeIfAbsent(itemTableId, id -> ItemTable.newBuilder()).setId(itemTableId);
    }

    /** 冷却时长毫秒（uint64）。 */
    void setCooldownMs(int cooldownTableId, long durationMs) {
        cooldownDurations.put(cooldownTableId, durationMs);
    }

    /** 副本怪物组，原样保存（不过滤 0）。 */
    void setDungeonMonsters(int dungeonTableId, int... monsterIds) {
        dungeonMonsterIds.put(dungeonTableId, Arrays.stream(monsterIds).boxed().toList());
    }

    /** 同上，传列表。 */
    void setDungeonMonsters(int dungeonTableId, List<Integer> monsterIds) {
        dungeonMonsterIds.put(dungeonTableId, List.copyOf(monsterIds));
    }

    /** 技能伤害定值（忽略施法者等级）。 */
    void setSkillDamage(int skillTableId, double damage) {
        skillDamageValues.put(skillTableId, damage);
    }

    /** buff 回血定值（忽略等级与损血）。 */
    void setBuffRegen(int buffTableId, double regen) {
        buffRegenValues.put(buffTableId, regen);
    }

    // ---- BattleData ----

    @Override
    public Optional<SkillTable> skill(int skillTableId) {
        return Optional.ofNullable(skillRows.get(skillTableId)).map(SkillTable.Builder::build);
    }

    @Override
    public Optional<BuffTable> buff(int buffTableId) {
        return Optional.ofNullable(buffRows.get(buffTableId)).map(BuffTable.Builder::build);
    }

    @Override
    public Optional<SkillPermissionTable> skillPermission(int combatStateId) {
        return Optional.ofNullable(permissionRows.get(combatStateId)).map(SkillPermissionTable.Builder::build);
    }

    @Override
    public Optional<DungeonTable> dungeon(int dungeonTableId) {
        return Optional.ofNullable(dungeonRows.get(dungeonTableId)).map(DungeonTable.Builder::build);
    }

    @Override
    public Optional<MonsterTable> monster(int monsterTableId) {
        return Optional.ofNullable(monsterRows.get(monsterTableId)).map(MonsterTable.Builder::build);
    }

    @Override
    public Optional<ItemTable> item(int itemTableId) {
        return Optional.ofNullable(itemRows.get(itemTableId)).map(ItemTable.Builder::build);
    }

    @Override
    public long cooldownDurationMs(int cooldownTableId) {
        return cooldownDurations.getOrDefault(cooldownTableId, 0L);
    }

    @Override
    public List<Integer> dungeonMonsterIds(int dungeonTableId) {
        return dungeonMonsterIds.getOrDefault(dungeonTableId, List.of());
    }

    @Override
    public double skillDamage(int skillTableId, double casterLevel) {
        return skillDamageValues.getOrDefault(skillTableId, 0.0);
    }

    @Override
    public double buffHealthRegeneration(int buffTableId, double level, double lostHealth) {
        return buffRegenValues.getOrDefault(buffTableId, 0.0);
    }
}
