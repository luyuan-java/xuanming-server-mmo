package com.game.battle.engine;

import com.game.proto.EquipmentEffects;
import com.game.proto.SupportEffectSource;
import com.game.proto.SupportEffectSummary;
import com.game.proto.SupportSkillPreview;
import com.game.table.BuffTable;
import com.game.table.SkillTable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

/** 场景中立预览与战斗结算共用的施放方规则。 */
public final class SupportEffectRules {
    private static final Set<Integer> RETIRED = loadRetiredSkills();

    private SupportEffectRules() {}

    private static Set<Integer> loadRetiredSkills() {
        try (var stream = SupportEffectRules.class.getResourceAsStream("/contract/retired_skill_ids.txt")) {
            if (stream == null) throw new IllegalStateException("missing retired skill contract");
            return Arrays.stream(new String(stream.readAllBytes(), StandardCharsets.US_ASCII).trim().split(","))
                    .map(String::trim).map(Integer::parseUnsignedInt).collect(Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static boolean retiredSkill(int id) { return RETIRED.contains(id); }

    public static double baseValue(double flat, double perLevel, int level, double basis, double ratio, int rank) {
        double value = Math.max(0, flat + perLevel * Integer.toUnsignedLong(level) + basis * ratio)
                * ClassSkillRules.supportRankFactor(rank);
        return Double.isFinite(value) ? value : 0;
    }

    public static double applyBonus(double base, double bonus) { return base * Math.max(0, 1 + bonus); }

    public static long discountMana(long original, double reductionBps) {
        long bps = Double.isFinite(reductionBps) ? (long) Math.clamp(reductionBps, 0, 3000) : 0;
        return original - Long.divideUnsigned(original, 10000) * bps - Long.remainderUnsigned(original, 10000) * bps / 10000;
    }

    public static int manaCategory(SkillTable skill, IntFunction<BuffTable> lookup) {
        if (skill.getBattleModel() != 1) return 0;
        for (int id : skill.getEffectList()) {
            var row = lookup.apply(id);
            if (row != null && row.getBattleOp() == 1) return skill.getDamageType() == 1 ? 1 : 2;
        }
        boolean restoreMana = false;
        for (int id : skill.getEffectList()) {
            var row = lookup.apply(id);
            if (row == null) continue;
            switch (row.getBattleOp()) {
                case 2, 8 -> { return 3; }
                case 9 -> { return 4; }
                case 13 -> { if (row.getStateKind() >= 1 && row.getStateKind() <= 7) return row.getStateKind() + 4; }
                case 7 -> { if (row.getStateKind() == 7) return 11; }
                case 14, 15 -> { return 11; }
                case 3 -> restoreMana = true;
                default -> { }
            }
        }
        return restoreMana ? 0 : 12;
    }

    public static SupportEffectSummary.Builder begin(EquipmentEffects equipment) {
        var summary = SupportEffectSummary.newBuilder();
        for (int kind = 1; kind <= 2; kind++) {
            var part = kind == 1 ? summary.getHealingBuilder() : summary.getShieldBuilder();
            part.setEquipmentCapBps(3200).setComplete(equipment.getSupportSourcesComplete())
                    .setTotalBonusBps(kind == 1 ? equipment.getOutgoingHealBps() : equipment.getShieldCapacityBps());
            for (var source : equipment.getSupportSourcesList()) {
                if (source.getEffectKind() != kind) continue;
                part.addSources(source);
                if (source.getSourceCapBps() > 0) part.setEquipmentCapBps(Math.min(part.getEquipmentCapBps(), source.getSourceCapBps()));
            }
            for (var artifact : equipment.getArtifactsList()) {
                if (artifact.getEffectId() != kind + 3) continue;
                String condition = kind == 1 ? "主目标首段即时治疗且气血≤" + artifact.getHealthThresholdBps() / 100.0 + "%" : "主目标首个独立容量盾";
                if (artifact.getPerRoundLimit() > 0) condition += "，每回合" + artifact.getPerRoundLimit() + "次";
                if (artifact.getBattleLimit() > 0) condition += "，每场" + artifact.getBattleLimit() + "次";
                part.addSources(SupportEffectSource.newBuilder().setKind(4).setItemId(artifact.getItemId())
                        .setEffectKind(kind).setConditional(true).setRawBonusBps(artifact.getBonusBps())
                        .setName(artifact.getName()).setReason(condition + "，不计常驻总计"));
            }
        }
        return summary;
    }

    public static Optional<SupportSkillPreview> preview(SkillTable skill, BuffTable effect, int level, int rank,
            long maxMana, double bonus, double manaReductionBps, boolean complete) {
        int kind = switch (effect.getBattleOp()) {
            case 2 -> 1;
            case 8 -> 3;
            case 9 -> effect.getHitLimit() == 0 ? 2 : 0;
            default -> 0;
        };
        if (skill.getBattleModel() != 1 || kind == 0 || effect.getValueBasis() != 0) return Optional.empty();
        int referenceRank = rank == 0 ? 1 : rank;
        double base = baseValue(effect.getFlatValue(), effect.getPerLevel(), level, 0, effect.getRatio(), referenceRank);
        long mana = ClassSkillRules.manaCost(maxMana, skill.getManaPercent());
        return Optional.of(SupportSkillPreview.newBuilder().setSkillTableId(skill.getId()).setEffectKind(kind)
                .setReferenceRank(referenceRank).setLearned(rank != 0).setComplete(complete)
                .setBaseValue(base).setOutgoingBonusBps(bonus * 10000).setReferenceValue(whole(applyBonus(base, bonus)))
                .setOriginalManaCost(mana).setManaCost(discountMana(mana, manaReductionBps))
                .setScope(switch (kind) {
                    case 2 -> "单目标初始容量，不含条件法宝，不读取缺血或减疗";
                    case 3 -> "每次恢复的中立值，不含缺血封顶、目标增减疗和复活血量";
                    default -> "单目标中立治疗，不含缺血封顶、目标增减疗、残血条件和法宝";
                }).build());
    }

    private static long whole(double value) {
        if (!(value > 0) || !Double.isFinite(value)) return 0;
        if (value >= 0x1p64) return -1L;
        return value < 0x1p63 ? (long) value : Long.MIN_VALUE + (long) (value - 0x1p63);
    }
}
