package com.game.scene.equip;

import com.game.player.store.state.EquipmentAffixState;
import com.game.player.store.state.EquipmentState;
import com.game.scene.player.BagItem;
import com.game.table.EquipAttributeTable;
import java.util.*;
import java.util.function.LongUnaryOperator;

/** 新装备掷值与现算有效值。随机源构造注入，任何读档、搬运或展示都不掷骰。 */
public final class EquipmentRules {
    public record AttributeValue(int attributeId, long value, long cap, int tier, int sequence) { }
    private record Candidate(int id, long weight, long min, long cap) { }
    private final EquipmentTables tables;
    private final LongUnaryOperator random;
    public EquipmentRules(EquipmentTables tables, LongUnaryOperator random) {
        this.tables = Objects.requireNonNull(tables); this.random = Objects.requireNonNull(random);
    }
    public EquipmentTables tables() { return tables; }
    private long below(long bound) {
        long value = random.applyAsLong(bound);
        return value < 0 || value >= bound ? bound - 1 : value;
    }
    public boolean enhanceSuccess(int rate) { return rate != 0 && below(10_000) < Integer.toUnsignedLong(rate); }

    /** 带硬保底的强化掷骰结果（对齐 C++ {@code equiprules::RollEnhanceWithPity}）。 */
    public record EnhanceRoll(boolean success, int nextPityCount, boolean triggeredByPity) { }

    /**
     * 含保底的强化判定：{@code pityLimit > 0} 且 {@code currentPity + 1 >= pityLimit} 时 100% 必成且不消耗随机源；
     * 否则按万分比 {@code successRate} 掷骰，成功归零，失败且有保底时计数 +1。
     */
    public EnhanceRoll rollEnhanceWithPity(int successRate, int currentPity, int pityLimit) {
        if (pityLimit > 0 && Integer.compareUnsigned(currentPity + 1, pityLimit) >= 0) {
            return new EnhanceRoll(true, 0, true);
        }
        if (enhanceSuccess(successRate)) {
            return new EnhanceRoll(true, 0, false);
        }
        int nextPity = pityLimit > 0 ? currentPity + 1 : 0;
        return new EnhanceRoll(false, nextPity, false);
    }

    public EquipmentState newInstance(int configId) {
        var item = tables.item(configId);
        if (item == null || item.getEquipKind() == 0 || item.getMaxStackSize() != 1) return null;
        var result = EquipmentState.newBuilder();
        var rule = tables.rule(item.getAffixRule());
        if (rule == null || item.getAffixRule() == 0 || item.getAffixPool() == 0) return result.build();
        Map<Integer, Candidate> byId = new LinkedHashMap<>();
        for (var entry : tables.pool(item.getAffixPool())) {
            var cap = tables.cap(entry.getAttrId(), item.getEquipLevel());
            if (tables.attribute(entry.getAttrId()) == null || entry.getWeight() == 0 || cap == null || cap.getCap() == 0) continue;
            Candidate old = byId.get(entry.getAttrId());
            long weight = Integer.toUnsignedLong(entry.getWeight()) + (old == null ? 0 : old.weight());
            long upper = Integer.toUnsignedLong(cap.getCap());
            byId.put(entry.getAttrId(), new Candidate(entry.getAttrId(), weight,
                    Math.max(1, Math.min(Integer.toUnsignedLong(cap.getMinValue()), upper)), upper));
        }
        List<Candidate> all = List.copyOf(byId.values());
        if (all.isEmpty()) return result.build();
        List<Candidate> remaining = new ArrayList<>(all);
        long min = Integer.toUnsignedLong(rule.getBlueMin()), max = Integer.toUnsignedLong(rule.getBlueMax());
        int count = (int) Math.min(all.size(), min + below(max > min ? max - min + 1 : 1));
        for (int i = 0; i < count; i++) {
            Candidate chosen = pick(remaining); remaining.remove(chosen); append(result, chosen, 1, i);
        }
        if (rule.getPinkRate() != 0 && below(10_000) < Integer.toUnsignedLong(rule.getPinkRate())) append(result, pick(all), 2, 0);
        if (rule.getYellowRate() != 0 && below(10_000) < Integer.toUnsignedLong(rule.getYellowRate())) append(result, pick(all), 3, 0);
        return result.build();
    }
    private Candidate pick(List<Candidate> rows) {
        long ticket = below(rows.stream().mapToLong(Candidate::weight).sum());
        for (Candidate row : rows) { if (ticket < row.weight()) return row; ticket -= row.weight(); }
        throw new IllegalStateException("装备权重选择失败");
    }
    private void append(EquipmentState.Builder out, Candidate row, int tier, int sequence) {
        out.addAffixes(EquipmentAffixState.newBuilder().setAttrId(row.id()).setTier(tier).setSeq(sequence)
                .setValue((int) (row.min() + below(row.cap() - row.min() + 1))));
    }
    public static final int V1_GUARANTEED_MAX_ENHANCE_LEVEL = 10;
    public static final int V1_MAX_ENHANCE_LEVEL = 11;
    public static final int V1_ENHANCE_BONUS_PER_LEVEL = 200;
    public static final int V1_TOP_ENHANCE_BONUS_PERMYRIAD = 2400;

    public int level(BagItem item) {
        if (item.equipment() == null) return 0;
        var row = tables.item(item.configId());
        int profile = row == null ? 0 : row.getEquipProfile();
        return (int) Math.min(Integer.toUnsignedLong(item.equipment().getEnhanceLevel()), tables.maxEnhance(profile));
    }
    /** V1 强化行契约：+1..+10 100% 必成零金币无保底；+11 概率提前成+硬保底必成（+24%，不掉级，零金币）。 */
    public boolean isEnhanceRowAvailable(com.game.table.EquipEnhanceTable row) {
        if (row == null) return false;
        int profile = row.getProfileId();
        if (profile == 0) return row.getSuccessRate() != 0;
        if (profile != 1) return false;
        int level = row.getLevel();
        var material = tables.item(row.getMaterialId());
        boolean baseMaterialOk = row.getFailLevelLoss() == 0 && row.getCostGold() == 0
                && row.getMaterialId() != 0 && row.getMaterialCount() > 0
                && material != null && material.getEquipKind() == 0 && material.getBattleUsable() == 0;
        if (!baseMaterialOk || level <= 0 || level > V1_MAX_ENHANCE_LEVEL) return false;
        if (level <= V1_GUARANTEED_MAX_ENHANCE_LEVEL) {
            return row.getBonusPermyriad() == V1_ENHANCE_BONUS_PER_LEVEL * level
                    && row.getSuccessRate() == 10_000 && row.getPityLimit() == 0;
        }
        return level == V1_MAX_ENHANCE_LEVEL
                && row.getBonusPermyriad() == V1_TOP_ENHANCE_BONUS_PERMYRIAD
                && row.getSuccessRate() > 0 && row.getSuccessRate() < 10_000
                && row.getPityLimit() > 0;
    }
    public long bonus(int level) { return bonus(0, level); }
    public long bonus(int profileId, int level) {
        var row = tables.enhance(profileId, level);
        return row != null && (profileId == 0 || isEnhanceRowAvailable(row))
                ? Integer.toUnsignedLong(row.getBonusPermyriad()) : 0;
    }
    public static long add(long a, long b) { return b > Long.MAX_VALUE - a ? Long.MAX_VALUE : a + b; }
    public static long scale(long base, long permyriad) {
        long whole = base / 10_000;
        long inc = whole != 0 && permyriad > Long.MAX_VALUE / whole ? Long.MAX_VALUE : whole * permyriad;
        return add(base, add(inc, base % 10_000 * permyriad / 10_000));
    }
    public static boolean accepted(EquipAttributeTable row) {
        if (row == null) return false;
        return switch (row.getEffect()) {
            case 1 -> row.getEffectParam() != 0;
            case 2, 4 -> true;
            case 3 -> row.getEffectParam() >= 1 && row.getEffectParam() <= 6;
            case 5 -> row.getEffectParam() >= 1 && row.getEffectParam() <= 15;
            default -> false;
        };
    }
    public List<AttributeValue> values(BagItem item) {
        var row = tables.item(item.configId());
        if (row == null || row.getEquipKind() == 0) return List.of();
        List<AttributeValue> out = new ArrayList<>();
        int profile = row.getEquipProfile();
        for (var base : row.getBaseAttrList()) if (accepted(tables.attribute(base.getBaseAttrId())))
            out.add(new AttributeValue(base.getBaseAttrId(), scale(Integer.toUnsignedLong(base.getBaseAttrValue()), bonus(profile, level(item))), 0, 0, 0));
        if (item.equipment() == null) return List.copyOf(out);
        for (var affix : item.equipment().getAffixesList()) {
            if (affix.getTier() < 1 || affix.getTier() > 4 || !accepted(tables.attribute(affix.getAttrId()))) continue;
            var capRow = tables.cap(affix.getAttrId(), row.getEquipLevel());
            long cap = capRow == null ? 0 : Integer.toUnsignedLong(capRow.getCap());
            out.add(new AttributeValue(affix.getAttrId(), Math.min(Integer.toUnsignedLong(affix.getValue()), cap), cap, affix.getTier(), affix.getSeq()));
        }
        out.addAll(gemValues(item).values());
        return List.copyOf(out);
    }
    public Map<Integer, AttributeValue> gemValues(BagItem item) {
        var row = tables.item(item.configId());
        if (row == null || item.equipment() == null) return Map.of();
        Map<Integer, AttributeValue> out = new TreeMap<>(); Set<Integer> seen = new HashSet<>();
        var records = item.equipment().getGemsList();
        for (int i = 0; i < Math.min(8, records.size()); i++) {
            var record = records.get(i);
            if (record.getGemConfigId() == 0 || !seen.add(record.getSocket())) continue;
            var gem = tables.gem(record.getGemConfigId());
            if (gem == null || !accepted(tables.attribute(gem.getAttrId()))
                    || tables.sockets(row.getEquipKind()).stream().noneMatch(s -> s.getSocket() == record.getSocket())) continue;
            out.put(record.getSocket(), new AttributeValue(gem.getAttrId(), Integer.toUnsignedLong(gem.getValue()), 0, 0, 0));
        }
        return Collections.unmodifiableMap(out);
    }
}
