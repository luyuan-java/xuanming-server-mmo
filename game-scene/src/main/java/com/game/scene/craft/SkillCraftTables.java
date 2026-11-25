package com.game.scene.craft;

import com.game.scene.equip.EquipCraftQualityRules;
import com.game.scene.equip.EquipmentTables;
import com.game.table.ConfigTables;
import com.game.table.ItemTable;
import com.game.table.SkillScrollCraftTable;
import com.game.table.SkillTable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.LoggerFactory;

/** 分卷宗永久技能制书配表的不可变视图（对齐 C++ {@code PlayerCraftSystem::ValidateTables}）。 */
public final class SkillCraftTables {

    private final Map<Integer, SkillScrollCraftTable> scrolls;
    private final List<SkillScrollCraftTable> enabledScrolls;
    private final Map<Integer, SkillTable> skills;
    private final Map<Integer, List<Integer>> skillsByScroll;
    private final Map<Integer, ItemTable> items;
    private final EquipmentTables equipmentTables;

    public SkillCraftTables(List<SkillScrollCraftTable> scrolls, List<SkillTable> skills,
                            List<ItemTable> items, EquipmentTables equipmentTables) {
        Map<Integer, SkillScrollCraftTable> scrollMap = new LinkedHashMap<>();
        List<SkillScrollCraftTable> enabled = new ArrayList<>();
        for (SkillScrollCraftTable row : scrolls) {
            scrollMap.putIfAbsent(row.getId(), row);
            if (row.getEnabled() != 0) {
                enabled.add(row);
            }
        }
        enabled.sort(Comparator.comparing(SkillScrollCraftTable::getId, Integer::compareUnsigned));
        this.scrolls = Collections.unmodifiableMap(scrollMap);
        this.enabledScrolls = List.copyOf(enabled);

        Map<Integer, SkillTable> skillMap = new LinkedHashMap<>();
        Map<Integer, List<Integer>> byScroll = new HashMap<>();
        for (SkillTable row : skills) {
            skillMap.putIfAbsent(row.getId(), row);
            if (row.getScrollId() != 0 && row.getSeasonId() == 0 && row.getBattleModel() == 1) {
                byScroll.computeIfAbsent(row.getScrollId(), k -> new ArrayList<>()).add(row.getId());
            }
        }
        Map<Integer, List<Integer>> frozenByScroll = new HashMap<>();
        byScroll.forEach((sid, list) -> {
            list.sort(Integer::compareUnsigned);
            frozenByScroll.put(sid, List.copyOf(list));
        });
        this.skills = Collections.unmodifiableMap(skillMap);
        this.skillsByScroll = Collections.unmodifiableMap(frozenByScroll);

        Map<Integer, ItemTable> itemMap = new LinkedHashMap<>();
        for (ItemTable row : items) {
            itemMap.putIfAbsent(row.getId(), row);
        }
        this.items = Collections.unmodifiableMap(itemMap);
        this.equipmentTables = equipmentTables;
    }

    public static SkillCraftTables from(ConfigTables tables) {
        EquipmentTables equipTables = EquipmentTables.from(tables);
        SkillCraftTables result = new SkillCraftTables(
                tables.skillScrollCraft().all(),
                tables.skill().all(),
                tables.item().all(),
                equipTables);
        if (!result.validateTables()) {
            LoggerFactory.getLogger(SkillCraftTables.class).warn("技能制书 / 装备品相配表校验未通过，相关写入口按无效表拒绝");
        }
        return result;
    }

    public SkillScrollCraftTable scroll(int id) {
        return scrolls.get(id);
    }

    public List<SkillScrollCraftTable> enabledScrolls() {
        return enabledScrolls;
    }

    public SkillTable skill(int id) {
        return skills.get(id);
    }

    public ItemTable item(int id) {
        return items.get(id);
    }

    public List<Integer> scrollSkillIds(int scrollId) {
        return skillsByScroll.getOrDefault(scrollId, List.of());
    }

    public boolean validStackableMaterial(int itemId, int count) {
        ItemTable row = items.get(itemId);
        return itemId != 0 && count != 0 && row != null
                && row.getMaxStackSize() != 0 && row.getEquipKind() == 0 && row.getBattleUsable() == 0;
    }

    /**
     * 校验 5 档品相表 + 技能卷宗配方 + 永久技能卷宗绑定 + 稀世绝学占比硬红线（<= 10%）。
     * 对齐 C++ {@code PlayerCraftSystem::ValidateTables}。
     */
    public boolean validateTables() {
        if (equipmentTables == null || !equipmentTables.validateQualityTable()) {
            return false;
        }
        if (scrolls.isEmpty()) {
            return false;
        }
        Map<Integer, Integer> scrollRarity = new TreeMap<>(Integer::compareUnsigned);
        for (SkillScrollCraftTable scroll : scrolls.values()) {
            if (scroll.getId() == 0 || Integer.compareUnsigned(scroll.getRarityTier(), 1) > 0) {
                return false;
            }
            if (scroll.getEnabled() == 0) {
                continue;
            }
            scrollRarity.put(scroll.getId(), scroll.getRarityTier());
            if (scroll.getRarityTier() == 0) {
                if (!validStackableMaterial(scroll.getFragmentItemId(), scroll.getRandomFragmentCount())
                        || Integer.compareUnsigned(scroll.getTargetedFragmentCount(), scroll.getRandomFragmentCount()) < 0
                        || scroll.getDismantleFragmentCount() == 0
                        || Integer.compareUnsigned(scroll.getDismantleFragmentCount(), scroll.getRandomFragmentCount()) > 0) {
                    return false;
                }
            } else {
                if (!validStackableMaterial(scroll.getRareScrollItemId(), scroll.getRareScrollCount())
                        || !validStackableMaterial(scroll.getRareInkItemId(), scroll.getRareInkCount())) {
                    return false;
                }
            }
        }

        int totalScrollSkills = 0;
        int rareScrollSkills = 0;
        Map<Integer, Integer> perScrollCount = new HashMap<>();
        for (SkillTable skill : skills.values()) {
            if (skill.getScrollId() == 0) {
                continue;
            }
            Integer expectedRarity = scrollRarity.get(skill.getScrollId());
            if (expectedRarity == null || skill.getSeasonId() != 0 || skill.getBattleModel() != 1
                    || skill.getRarityTier() != expectedRarity) {
                return false;
            }
            totalScrollSkills++;
            perScrollCount.merge(skill.getScrollId(), 1, Integer::sum);
            if (skill.getRarityTier() == 1) {
                rareScrollSkills++;
            }
        }
        for (int scrollId : scrollRarity.keySet()) {
            if (perScrollCount.getOrDefault(scrollId, 0) == 0) {
                return false;
            }
        }
        // 硬红线：永久技能中稀世绝学占比严锁 <= 10%。
        return totalScrollSkills > 0
                && Integer.toUnsignedLong(rareScrollSkills) * 10_000L
                <= Integer.toUnsignedLong(totalScrollSkills) * EquipCraftQualityRules.MAX_RARE_SKILL_RATIO_BPS;
    }
}
