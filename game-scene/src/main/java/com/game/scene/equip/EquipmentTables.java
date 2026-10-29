package com.game.scene.equip;

import com.game.table.*;
import java.util.*;
import java.util.function.ToIntFunction;

/** 装备配置的不可变视图；只含配表和索引，不持有玩家或进程级随机源。 */
public final class EquipmentTables {
    private final Map<Integer, ItemTable> items;
    private final Map<Integer, EquipAttributeTable> attributes;
    private final Map<Integer, EquipAffixRuleTable> rules;
    private final Map<Integer, EquipEnhanceTable> enhancements;
    private final Map<Integer, EquipGemTable> gems;
    private final List<EquipAffixPoolTable> pools;
    private final List<EquipAttributeCapTable> caps;
    private final List<EquipGemSocketTable> sockets;
    private final List<EquipSlotTable> slots;
    private final int maxEnhance;

    public EquipmentTables(List<ItemTable> items, List<EquipAttributeTable> attributes,
            List<EquipAffixRuleTable> rules, List<EquipAffixPoolTable> pools, List<EquipAttributeCapTable> caps,
            List<EquipEnhanceTable> enhancements, List<EquipGemTable> gems,
            List<EquipGemSocketTable> sockets, List<EquipSlotTable> slots) {
        this.items = index(items, ItemTable::getId); this.attributes = index(attributes, EquipAttributeTable::getId);
        this.rules = index(rules, EquipAffixRuleTable::getId); this.enhancements = index(enhancements, EquipEnhanceTable::getId);
        this.gems = index(gems, EquipGemTable::getId); this.pools = List.copyOf(pools); this.caps = List.copyOf(caps);
        this.sockets = List.copyOf(sockets); this.slots = List.copyOf(slots);
        int max = 0;
        while (this.enhancements.containsKey(max + 1)) max++;
        maxEnhance = max;
    }

    public static EquipmentTables from(ConfigTables t) {
        EquipmentTables result = new EquipmentTables(t.item().all(), t.equipAttribute().all(), t.equipAffixRule().all(),
                t.equipAffixPool().all(), t.equipAttributeCap().all(), t.equipEnhance().all(),
                t.equipGem().all(), t.equipGemSocket().all(), t.equipSlot().all());
        var log = org.slf4j.LoggerFactory.getLogger(EquipmentTables.class);
        result.validationIssues().forEach(issue -> log.warn("装备表校验：{}", issue));
        return result;
    }

    public static EquipmentTables empty() {
        return new EquipmentTables(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static <T> Map<Integer, T> index(List<T> rows, ToIntFunction<T> key) {
        Map<Integer, T> result = new LinkedHashMap<>();
        for (T row : rows) result.putIfAbsent(key.applyAsInt(row), row);
        return Collections.unmodifiableMap(result);
    }
    public ItemTable item(int id) { return items.get(id); }
    public EquipAttributeTable attribute(int id) { return attributes.get(id); }
    public List<EquipAttributeTable> attributes() { return List.copyOf(attributes.values()); }
    public EquipAffixRuleTable rule(int id) { return rules.get(id); }
    public EquipEnhanceTable enhance(int level) { return enhancements.get(level); }
    public EquipGemTable gem(int id) { return gems.get(id); }
    public int maxEnhance() { return maxEnhance; }
    public List<EquipSlotTable> slots() { return slots; }
    public List<EquipAffixPoolTable> pool(int id) { return pools.stream().filter(r -> r.getPoolId() == id).toList(); }
    public List<EquipGemSocketTable> sockets(int kind) {
        return sockets.stream().filter(r -> r.getEquipKind() == kind && Integer.compareUnsigned(r.getSocket(), 8) < 0)
                .sorted(Comparator.comparingInt(EquipGemSocketTable::getSocket)).toList();
    }
    public EquipAttributeCapTable cap(int id, int level) {
        EquipAttributeCapTable result = null;
        for (var row : caps) if (row.getAttrId() == id && Integer.compareUnsigned(row.getLevel(), level) <= 0
                && (result == null || Integer.compareUnsigned(row.getLevel(), result.getLevel()) > 0)) result = row;
        return result;
    }

    /** 配表问题只告警；写入口继续按同一份不可变表逐项拒绝，读取不洗掉玩家实例。 */
    public List<String> validationIssues() {
        List<String> issues = new ArrayList<>();
        for (var row : attributes.values()) if (!EquipmentRules.accepted(row)) issues.add("EquipAttribute 无效效果 id=" + row.getId());
        for (var row : items.values()) if (row.getEquipKind() != 0) {
            if (row.getMaxStackSize() != 1) issues.add("Item 装备必须不可堆叠 id=" + row.getId());
            if (row.getAffixRule() != 0 && rule(row.getAffixRule()) == null) issues.add("Item 缺随机规则 id=" + row.getId());
            if (row.getAffixPool() != 0 && pool(row.getAffixPool()).isEmpty()) issues.add("Item 缺随机池 id=" + row.getId());
            for (var base : row.getBaseAttrList()) if (attribute(base.getBaseAttrId()) == null)
                issues.add("Item 缺基础属性 id=" + row.getId() + " attr=" + base.getBaseAttrId());
        }
        for (var row : pools) if (attribute(row.getAttrId()) == null) issues.add("EquipAffixPool 缺属性 id=" + row.getId());
        for (var row : caps) if (Integer.compareUnsigned(row.getMinValue(), row.getCap()) > 0)
            issues.add("EquipAttributeCap 下界超过上界 id=" + row.getId());
        for (var row : rules.values()) if (Integer.compareUnsigned(row.getBlueMin(), row.getBlueMax()) > 0
                || Integer.compareUnsigned(row.getPinkRate(), 10_000) > 0 || Integer.compareUnsigned(row.getYellowRate(), 10_000) > 0)
            issues.add("EquipAffixRule 区间或概率越界 id=" + row.getId());
        for (var row : enhancements.values()) {
            if (row.getId() == 0 || Integer.compareUnsigned(row.getId(), maxEnhance) > 0)
                issues.add("EquipEnhance 等级必须从 1 连续 id=" + row.getId());
            if (Integer.compareUnsigned(row.getSuccessRate(), 10_000) > 0 || row.getCostGold() < 0)
                issues.add("EquipEnhance 概率或金币越界 id=" + row.getId());
            var material = item(row.getMaterialId());
            if (row.getMaterialId() != 0 && row.getMaterialCount() != 0
                    && (material == null || material.getEquipKind() != 0 || material.getBattleUsable() != 0))
                issues.add("EquipEnhance 材料配置异常 id=" + row.getId());
        }
        for (var row : gems.values()) {
            var item = item(row.getId());
            if (item == null || item.getEquipKind() != 0 || item.getBattleUsable() != 0 || row.getGemType() == 0
                    || !EquipmentRules.accepted(attribute(row.getAttrId()))) issues.add("EquipGem 配置异常 id=" + row.getId());
        }
        Set<String> seen = new HashSet<>();
        Map<Integer, SortedSet<Integer>> byKind = new HashMap<>();
        for (var row : sockets) {
            if (row.getEquipKind() == 0 || Integer.compareUnsigned(row.getSocket(), 8) >= 0
                    || !seen.add(row.getEquipKind() + ":" + row.getSocket())) issues.add("EquipGemSocket 部位、孔位或重复行异常 id=" + row.getId());
            if (Integer.compareUnsigned(row.getSocket(), 8) < 0) byKind.computeIfAbsent(row.getEquipKind(), key -> new TreeSet<>()).add(row.getSocket());
        }
        byKind.forEach((kind, configured) -> {
            if (configured.last() + 1 != configured.size()) issues.add("EquipGemSocket 孔位不连续 equip_kind=" + kind);
        });
        return List.copyOf(issues);
    }
}
