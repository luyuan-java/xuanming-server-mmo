package com.game.battle.testing;

import com.game.battle.engine.BattleConstants;
import com.game.battle.engine.BattleData;
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
 * battle 节点测试用的内存表数据（引擎的 {@code MemoryBattleData} 在 xm-battle-engine 的测试源里，不共享；这里按同样的口径自建一份最小表）。
 *
 * <p>{@link #standard()} 的内容：
 * <ul>
 *   <li>技能 {@link #SKILL_DAMAGE}（101）：指向性、普通施放、冷却组 9 = 12000 ms（2 回合）、伤害定值 50；</li>
 *   <li>技能 {@link #SKILL_NUKE}（103）：指向性、普通施放、伤害 1000（一击打死兜底怪）；</li>
 *   <li>道具 {@link #ITEM_POTION}（301）：战斗可用，回血 100；</li>
 *   <li>副本 {@link #DUNGEON_NONE}（7）没有行：PVE 每名玩家对一只兜底怪（300 hp、速度 60，actor_id = {@link #MONSTER_ID}）。</li>
 * </ul>
 * 行以 builder 存着，查询时现场 build；只在测试线程上用。
 */
public final class FakeBattleData implements BattleData {

    public static final int SKILL_DAMAGE = 101;
    public static final int SKILL_NUKE = 103;
    public static final int ITEM_POTION = 301;
    public static final int COOLDOWN_GROUP = 9;
    /** 没有行的副本：兜底怪、回合上限 30。 */
    public static final int DUNGEON_NONE = 7;
    /** 第 0 只怪的 actor_id。 */
    public static final long MONSTER_ID = BattleConstants.MONSTER_ACTOR_ID_BASE;

    private final Map<Integer, SkillTable.Builder> skills = new HashMap<>();
    private final Map<Integer, ItemTable.Builder> items = new HashMap<>();
    private final Map<Integer, MonsterTable.Builder> monsters = new HashMap<>();
    private final Map<Integer, DungeonTable.Builder> dungeons = new HashMap<>();
    private final Map<Integer, List<Integer>> dungeonMonsters = new HashMap<>();
    private final Map<Integer, Long> cooldowns = new HashMap<>();
    private final Map<Integer, Double> skillDamage = new HashMap<>();

    /** 标准表（见类注释），每次返回新实例。 */
    public static FakeBattleData standard() {
        FakeBattleData data = new FakeBattleData();
        SkillTable.Builder damage = data.addSkill(SKILL_DAMAGE);
        damage.addTargetingMode(1);
        damage.addSkillType(BattleConstants.SKILL_TYPE_BIT_GENERAL);
        damage.setCooldownId(COOLDOWN_GROUP);
        data.cooldowns.put(COOLDOWN_GROUP, 12000L);
        data.skillDamage.put(SKILL_DAMAGE, 50.0);

        SkillTable.Builder nuke = data.addSkill(SKILL_NUKE);
        nuke.addTargetingMode(1);
        nuke.addSkillType(BattleConstants.SKILL_TYPE_BIT_GENERAL);
        data.skillDamage.put(SKILL_NUKE, 1000.0);

        ItemTable.Builder potion = data.addItem(ITEM_POTION);
        potion.setBattleUsable(1);
        potion.setBattleHealHp(100);
        return data;
    }

    public SkillTable.Builder addSkill(int id) {
        return skills.computeIfAbsent(id, k -> SkillTable.newBuilder()).setId(id);
    }

    public ItemTable.Builder addItem(int id) {
        return items.computeIfAbsent(id, k -> ItemTable.newBuilder()).setId(id);
    }

    public MonsterTable.Builder addMonster(int id) {
        return monsters.computeIfAbsent(id, k -> MonsterTable.newBuilder()).setId(id);
    }

    public DungeonTable.Builder addDungeon(int id) {
        return dungeons.computeIfAbsent(id, k -> DungeonTable.newBuilder()).setId(id);
    }

    public void setDungeonMonsters(int dungeonId, int... monsterIds) {
        dungeonMonsters.put(dungeonId, Arrays.stream(monsterIds).boxed().toList());
    }

    @Override
    public Optional<SkillTable> skill(int skillTableId) {
        return Optional.ofNullable(skills.get(skillTableId)).map(SkillTable.Builder::build);
    }

    @Override
    public Optional<BuffTable> buff(int buffTableId) {
        return Optional.empty();
    }

    @Override
    public Optional<SkillPermissionTable> skillPermission(int combatStateId) {
        return Optional.empty();
    }

    @Override
    public Optional<DungeonTable> dungeon(int dungeonTableId) {
        return Optional.ofNullable(dungeons.get(dungeonTableId)).map(DungeonTable.Builder::build);
    }

    @Override
    public Optional<MonsterTable> monster(int monsterTableId) {
        return Optional.ofNullable(monsters.get(monsterTableId)).map(MonsterTable.Builder::build);
    }

    @Override
    public Optional<ItemTable> item(int itemTableId) {
        return Optional.ofNullable(items.get(itemTableId)).map(ItemTable.Builder::build);
    }

    @Override
    public long cooldownDurationMs(int cooldownTableId) {
        return cooldowns.getOrDefault(cooldownTableId, 0L);
    }

    @Override
    public List<Integer> dungeonMonsterIds(int dungeonTableId) {
        return dungeonMonsters.getOrDefault(dungeonTableId, List.of());
    }

    @Override
    public double skillDamage(int skillTableId, double casterLevel) {
        return skillDamage.getOrDefault(skillTableId, 0.0);
    }

    @Override
    public double buffHealthRegeneration(int buffTableId, double level, double lostHealth) {
        return 0.0;
    }
}
