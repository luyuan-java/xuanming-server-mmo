package com.game.scene.pet;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.scene.pet.PetRules.BaseValues;
import com.game.scene.pet.PetRules.Coefficients;
import com.game.scene.pet.PetRules.Derived;
import com.game.scene.pet.PetRules.DimensionInput;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 宝宝纯规则（基线 pet_rules.h）：等级跟随主人并受种类上限夹、维度面板值、二级属性按资质放大、成长率。 */
class PetRulesTest {

    private static DimensionInput dim(int id, long basePerLevel, double maxHealth, double physicalAttack, long allocated,
                                      long aptitude) {
        return new DimensionInput(id, new Coefficients(basePerLevel, maxHealth, 0, physicalAttack, 0, 0, 0), allocated,
                aptitude);
    }

    @Test
    void 宝宝等级等于主人等级_受种类上限夹_上限0不夹_至少1() {
        assertThat(PetRules.effectiveLevel(30, 120)).isEqualTo(30);
        assertThat(PetRules.effectiveLevel(130, 120)).isEqualTo(120);
        assertThat(PetRules.effectiveLevel(130, 0)).isEqualTo(130);
        assertThat(PetRules.effectiveLevel(0, 120)).isEqualTo(1);
    }

    @Test
    void 维度面板值等于自然成长乘等级加已分配_资质不参与() {
        assertThat(PetRules.dimensionValue(dim(401, 1, 42, 0, 50, 8000), 30)).isEqualTo(80);
    }

    @Test
    void 二级属性等于初值加维度值乘系数乘资质_向下取整_气血上限至少1() {
        BaseValues base = new BaseValues(400, 800, 144);
        List<DimensionInput> inputs = List.of(dim(401, 1, 42, 0, 0, 8000), dim(403, 1, 0, 40, 0, 7000));
        Derived derived = PetRules.computeDerived(base, inputs, 1);
        assertThat(derived.maxHealth()).as("400 + 42 × 1 × 0.8 = 433.6").isEqualTo(433);
        assertThat(derived.physicalAttack()).as("40 × 1 × 0.7").isEqualTo(28);
        assertThat(derived.maxMana()).isEqualTo(800);
        assertThat(derived.speed()).isEqualTo(144);
        assertThat(PetRules.computeDerived(new BaseValues(0, 0, 0), List.of(), 1).maxHealth()).isEqualTo(1);
        assertThat(PetRules.computeDerived(base, List.of(dim(401, 1, 42, 0, 0, 0)), 1).maxHealth())
                .as("资质 0 按基准 10000").isEqualTo(442);
        assertThat(PetRules.computeDerived(base, List.of(dim(401, 0, 42, 0, 0, 8000)), 1).maxHealth())
                .as("维度值 0 跳过").isEqualTo(400);
    }

    @Test
    void 成长率是资质算术平均_整除_缺省按基准() {
        assertThat(PetRules.growthPermyriad(List.of(dim(401, 1, 0, 0, 0, 8000), dim(402, 1, 0, 0, 0, 10000),
                dim(403, 1, 0, 0, 0, 7000), dim(404, 1, 0, 0, 0, 11001)))).isEqualTo(9000);
        assertThat(PetRules.growthPermyriad(List.of(dim(401, 1, 0, 0, 0, 0)))).isEqualTo(10000);
        assertThat(PetRules.growthPermyriad(List.of())).isEqualTo(10000);
    }
}
