package com.game.scene.pet;

import java.util.List;

/**
 * 宝宝的纯规则（基线 {@code pet_rules.h}，零状态零表依赖）：宝宝等级、维度面板值、二级属性、成长率。
 * 点数总量与「目标已分配」校验复用角色那套（{@code AttributeRules}）。
 *
 * <p>资质是万分比（10000 = 100%），只放大该维度对二级属性的贡献，不改面板上的维度值。
 */
final class PetRules {

    /** 资质基准：缺配 / 老存档按它处理，等价于没有资质。 */
    static final long APTITUDE_BASE = 10_000;

    /** 一个维度的系数（AttributeDimension 的每级自然成长 + 六列系数）。 */
    record Coefficients(long basePerLevel, double maxHealth, double maxMana, double physicalAttack,
                        double magicAttack, double speed, double defense) {
    }

    /** 一个维度在这只宝宝身上的输入（已分配点、资质都按 uint32，long 承载）。 */
    record DimensionInput(int dimensionId, Coefficients coefficients, long allocated, long aptitude) {
    }

    /** Pet 表的三项初值（等价角色的 Class 初值）。 */
    record BaseValues(long maxHealth, long maxMana, long speed) {
    }

    /** 二级属性（向下取整后的整数，uint64 按 long 承载）。 */
    record Derived(long maxHealth, long maxMana, long physicalAttack, long magicAttack, long speed, long defense) {
    }

    private PetRules() {
    }

    /** 宝宝等级 = min(主人等级, 种类上限)；上限缺配（0）不夹；至少 1（等级按 uint32）。 */
    static long effectiveLevel(long ownerLevel, long levelCap) {
        long level = ownerLevel > 0 ? ownerLevel : 1;
        return levelCap == 0 ? level : Math.min(level, levelCap);
    }

    /** 面板维度值 = 每级自然成长 × 等级 + 已分配点（资质不参与）。 */
    static long dimensionValue(DimensionInput input, long level) {
        return input.coefficients().basePerLevel() * level + input.allocated();
    }

    /** 二级属性 = 初值 + Σ(维度值 × 系数 × 资质 / 10000)；向下取整（≤ 0 为 0），气血上限至少 1。 */
    static Derived computeDerived(BaseValues base, List<DimensionInput> dimensions, long level) {
        double maxHealth = unsignedToDouble(base.maxHealth());
        double maxMana = unsignedToDouble(base.maxMana());
        double physicalAttack = 0.0;
        double magicAttack = 0.0;
        double speed = unsignedToDouble(base.speed());
        double defense = 0.0;
        for (DimensionInput input : dimensions) {
            double value = (double) dimensionValue(input, level);
            if (value == 0.0) {
                continue;
            }
            double aptitude = (double) (input.aptitude() > 0 ? input.aptitude() : APTITUDE_BASE) / (double) APTITUDE_BASE;
            double weighted = value * aptitude;
            Coefficients c = input.coefficients();
            maxHealth += c.maxHealth() * weighted;
            maxMana += c.maxMana() * weighted;
            physicalAttack += c.physicalAttack() * weighted;
            magicAttack += c.magicAttack() * weighted;
            speed += c.speed() * weighted;
            defense += c.defense() * weighted;
        }
        return new Derived(Math.max(toLong(maxHealth), 1), toLong(maxMana), toLong(physicalAttack),
                toLong(magicAttack), toLong(speed), toLong(defense));
    }

    /** 展示用成长率 = 各维度资质的算术平均（万分比，整除）；没有维度时为基准。 */
    static long growthPermyriad(List<DimensionInput> dimensions) {
        if (dimensions.isEmpty()) {
            return APTITUDE_BASE;
        }
        long sum = 0;
        for (DimensionInput input : dimensions) {
            sum += input.aptitude() > 0 ? input.aptitude() : APTITUDE_BASE;
        }
        return sum / dimensions.size();
    }

    private static long toLong(double value) {
        if (value <= 0.0) {
            return 0;
        }
        return (long) Math.floor(value);
    }

    private static double unsignedToDouble(long value) {
        return value >= 0 ? value : ((value >>> 1) | (value & 1)) * 2.0;
    }
}
