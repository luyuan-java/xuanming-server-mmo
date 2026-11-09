package com.game.battle.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.game.battle.engine.ClassSkillRules.PassiveStat;
import com.game.battle.engine.ClassSkillRules.UpgradePlan;
import com.game.battle.engine.ClassSkillRules.UpgradeStop;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 固定源规则的行为断言与无符号边界；不启动引擎，不读外部表。 */
class ClassSkillRulesTest {
    @Test
    void 解锁后每两级开放一重并受表上限约束() {
        assertThat(ClassSkillRules.rankLimit(9, 10, 10)).isZero();
        assertThat(ClassSkillRules.rankLimit(10, 10, 10)).isEqualTo(1);
        assertThat(ClassSkillRules.rankLimit(11, 10, 10)).isEqualTo(1);
        assertThat(ClassSkillRules.rankLimit(12, 10, 10)).isEqualTo(2);
        assertThat(ClassSkillRules.rankLimit(13, 10, 10)).isEqualTo(2);
        assertThat(ClassSkillRules.rankLimit(19, 1, 10)).isEqualTo(10);
        assertThat(ClassSkillRules.rankLimit(85, 1, 10)).isEqualTo(10);
        assertThat(ClassSkillRules.rankLimit(50, 1, 0)).isZero();
        assertThat(ClassSkillRules.rankLimit(0, 0, 10)).isEqualTo(1);
    }

    @Test
    void 生效值不替代原存储值_未学与超上限均有确定结果() {
        assertThat(ClassSkillRules.effectiveRank(7, 10, 1, 10)).isEqualTo(5);
        assertThat(ClassSkillRules.effectiveRank(15, 30, 1, 10)).isEqualTo(10);
        assertThat(ClassSkillRules.effectiveRank(15, 5, 1, 10)).isEqualTo(3);
        assertThat(ClassSkillRules.effectiveRank(0, 30, 1, 10)).isZero();
        assertThat(ClassSkillRules.effectiveRank(5, 9, 10, 10)).isZero();
    }

    @Test
    void 重数和角色等级的高位不是负数() {
        assertThat(ClassSkillRules.rankLimit(Integer.MAX_VALUE, Integer.MIN_VALUE, -1)).isZero();
        assertThat(ClassSkillRules.rankLimit(Integer.MIN_VALUE, Integer.MIN_VALUE, -1)).isEqualTo(1);
        assertThat(ClassSkillRules.rankLimit(-1, 0, -1)).isEqualTo(Integer.MIN_VALUE);
        assertThat(ClassSkillRules.rankLimit(-1, -2, -1)).isEqualTo(1);
        assertThat(ClassSkillRules.effectiveRank(-1, -1, 0, -1)).isEqualTo(Integer.MIN_VALUE);
        assertThat(ClassSkillRules.effectiveRank(Integer.MAX_VALUE, -1, 0, -1)).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void 主动与辅助每重百分之二_被动每重百分之五() {
        assertThat(ClassSkillRules.damageRankFactor(1)).isEqualTo(1);
        assertThat(ClassSkillRules.damageRankFactor(10)).isEqualTo(1.18);
        assertThat(ClassSkillRules.supportRankFactor(6)).isEqualTo(1.10);
        assertThat(ClassSkillRules.passiveRankFactor(10)).isEqualTo(1.45);
        assertThat(ClassSkillRules.damageRankFactor(0)).isEqualTo(1);
        assertThat(ClassSkillRules.supportRankFactor(0)).isEqualTo(1);
        assertThat(ClassSkillRules.passiveRankFactor(0)).isEqualTo(1);
        // uint32 最大重数有 4_294_967_294 个额外重数：1 + 0.02 × 4_294_967_294。
        assertThat(ClassSkillRules.damageRankFactor(-1)).isCloseTo(85_899_346.88, within(0.0000001));
        assertThat(ClassSkillRules.supportRankFactor(-1)).isCloseTo(85_899_346.88, within(0.0000001));
        assertThat(ClassSkillRules.passiveRankFactor(-1)).isCloseTo(214_748_365.7, within(0.0000001));
    }

    @Test
    void 被动名字逐项匹配_不从展示文字或大小写推断() {
        Map.of("defense", PassiveStat.DEFENSE, "physicalAttack", PassiveStat.PHYSICAL_ATTACK,
                "magicAttack", PassiveStat.MAGIC_ATTACK, "maxHp", PassiveStat.MAX_HEALTH,
                "maxMp", PassiveStat.MAX_MANA, "speed", PassiveStat.SPEED,
                "crit", PassiveStat.CRIT, "outgoingHeal", PassiveStat.OUTGOING_HEAL)
                .forEach((name, stat) -> assertThat(ClassSkillRules.parsePassiveStat(name)).isEqualTo(stat));
        for (String value : new String[] {null, "", "bogus", "maxhp", " defense", "Defense", "治疗加成"}) {
            assertThat(ClassSkillRules.parsePassiveStat(value)).isEqualTo(PassiveStat.NONE);
        }
    }

    @Test
    void 百万分比量化避免二进制小数多进一位() {
        assertThat(ClassSkillRules.ceilRatio(100, 0.07)).isEqualTo(7);
        assertThat(ClassSkillRules.ceilRatio(250, 0.06)).isEqualTo(15);
        assertThat(ClassSkillRules.floorRatio(220, 0.8)).isEqualTo(176);
        assertThat(ClassSkillRules.floorRatio(3000, 1.25)).isEqualTo(3750);
        assertThat(ClassSkillRules.ceilRatio(3, 0.5)).isEqualTo(2);
        assertThat(ClassSkillRules.floorRatio(3, 0.5)).isEqualTo(1);
        assertThat(ClassSkillRules.ceilRatio(0, 1.25)).isZero();
    }

    @Test
    void 百万分比半数向上进位_低于半数不凭空增加() {
        assertThat(ClassSkillRules.ceilRatio(1_000_000, 0.0000005)).isEqualTo(1);
        assertThat(ClassSkillRules.floorRatio(1_000_000, 0.0000005)).isEqualTo(1);
        assertThat(ClassSkillRules.ceilRatio(1_000_000, Math.nextDown(0.0000005))).isZero();
        assertThat(ClassSkillRules.ceilRatio(1_000_000, 0.0000015)).isEqualTo(2);
        assertThat(ClassSkillRules.ceilRatio(-1L, Double.MIN_VALUE)).isZero();
    }

    @Test
    void 非正和非有限比例不产生倍率结果() {
        for (double ratio : new double[] {0, -0.0, -0.1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThat(ClassSkillRules.ceilRatio(-1L, ratio)).isZero();
            assertThat(ClassSkillRules.floorRatio(-1L, ratio)).isZero();
        }
    }

    @Test
    void 高位值按uint64精确取整_不经double损失整数() {
        assertThat(ClassSkillRules.floorRatio(-1L, 1)).isEqualTo(-1L);
        assertThat(ClassSkillRules.ceilRatio(-1L, 1)).isEqualTo(-1L);
        assertThat(ClassSkillRules.floorRatio(-1L, 0.5)).isEqualTo(Long.MAX_VALUE);
        assertThat(ClassSkillRules.ceilRatio(-1L, 0.5)).isEqualTo(Long.MIN_VALUE);
        assertThat(ClassSkillRules.floorRatio(Long.MIN_VALUE, 0.5)).isEqualTo(1L << 62);
        assertThat(ClassSkillRules.ceilRatio(Long.MIN_VALUE, 0.5)).isEqualTo(1L << 62);
        assertThat(ClassSkillRules.floorRatio(-1L, 0.000001)).isEqualTo(18_446_744_073_709L);
        assertThat(ClassSkillRules.ceilRatio(-1L, 0.000001)).isEqualTo(18_446_744_073_710L);
    }

    @Test
    void 结果在uint64上限饱和_极大有限比例先按源上限量化() {
        assertThat(ClassSkillRules.ceilRatio(-1L, 2)).isEqualTo(-1L);
        assertThat(ClassSkillRules.floorRatio(Long.MIN_VALUE, 2)).isEqualTo(-1L);
        assertThat(ClassSkillRules.floorRatio(1, Double.MAX_VALUE)).isEqualTo(18_446_744L);
        assertThat(ClassSkillRules.ceilRatio(1, Double.MAX_VALUE)).isEqualTo(18_446_745L);
        assertThat(ClassSkillRules.ceilRatio(-1L, Double.MAX_VALUE)).isEqualTo(-1L);
        assertThat(ClassSkillRules.floorRatio(0, Double.MAX_VALUE)).isZero();
    }

    @Test
    void 正耗蓝至少一点_非有限边界按源规则区分() {
        assertThat(ClassSkillRules.manaCost(100, 0.03)).isEqualTo(3);
        assertThat(ClassSkillRules.manaCost(10, 0.03)).isEqualTo(1);
        assertThat(ClassSkillRules.manaCost(0, 0.03)).isEqualTo(1);
        assertThat(ClassSkillRules.manaCost(-1L, Double.MIN_VALUE)).isEqualTo(1);
        assertThat(ClassSkillRules.manaCost(100, Double.POSITIVE_INFINITY)).isEqualTo(1);
        assertThat(ClassSkillRules.manaCost(-1L, 0.5)).isEqualTo(Long.MIN_VALUE);
        for (double ratio : new double[] {0, -0.0, -0.03, Double.NaN, Double.NEGATIVE_INFINITY}) {
            assertThat(ClassSkillRules.manaCost(-1L, ratio)).isZero();
        }
    }

    @Test
    void 修习单价与固定内容公式一致_极端输入保持源回绕() {
        assertThat(ClassSkillRules.upgradeCost(1, 1)).isEqualTo(110);
        assertThat(ClassSkillRules.upgradeCost(2, 10)).isEqualTo(500);
        assertThat(ClassSkillRules.upgradeCost(10, 40)).isEqualTo(10_400);
        assertThat(ClassSkillRules.upgradeCost(0, -1)).isEqualTo(42_949_672_950L);
        // 100×(2^32−1)² mod 2^64 = 2^64−200×2^32+100。
        assertThat(ClassSkillRules.upgradeCost(-1, 0)).isEqualTo(-858_993_459_100L);
    }

    @Test
    void 修习按剩余余额计算部分成功_满重等级余额按顺序停() {
        assertThat(ClassSkillRules.planUpgrade(0, 10, 3, 1, -1L, 10))
                .isEqualTo(new UpgradePlan(3, 3, 1430, UpgradeStop.LEVEL_LIMIT));
        assertThat(ClassSkillRules.planUpgrade(0, 10, 10, 1, 520, 10))
                .isEqualTo(new UpgradePlan(2, 2, 520, UpgradeStop.GOLD));
        assertThat(ClassSkillRules.planUpgrade(10, 10, 0, 1, 0, 10))
                .isEqualTo(new UpgradePlan(0, 10, 0, UpgradeStop.MAX_RANK));
        assertThat(ClassSkillRules.planUpgrade(0, 10, 0, 1, 0, 10))
                .isEqualTo(new UpgradePlan(0, 0, 0, UpgradeStop.LEVEL_LIMIT));
        assertThat(ClassSkillRules.planUpgrade(0, 10, 10, 1, 109, 1))
                .isEqualTo(new UpgradePlan(0, 0, 0, UpgradeStop.GOLD));
    }

    @Test
    void 刚好修够次数不提前报告下一步停因_零存储重数可学习() {
        assertThat(ClassSkillRules.planUpgrade(0, 10, 10, 1, 110, 1))
                .isEqualTo(new UpgradePlan(1, 1, 110, UpgradeStop.NONE));
        assertThat(ClassSkillRules.planUpgrade(9, 10, 10, 1, -1L, 1))
                .isEqualTo(new UpgradePlan(1, 10, 10_010, UpgradeStop.NONE));
        assertThat(ClassSkillRules.planUpgrade(4, 10, 10, 1, Long.MIN_VALUE, 1))
                .isEqualTo(new UpgradePlan(1, 5, 2510, UpgradeStop.NONE));
    }

    @Test
    void 十次修习按逐重总价收费_少一银两只完成前九重() {
        // 100×(1²+…+10²)+10×1×10 = 38,600；前九重为 28,590。
        assertThat(ClassSkillRules.planUpgrade(0, 10, 10, 1, 38_600, 10))
                .isEqualTo(new UpgradePlan(10, 10, 38_600, UpgradeStop.NONE));
        assertThat(ClassSkillRules.planUpgrade(0, 10, 10, 1, 38_599, 10))
                .isEqualTo(new UpgradePlan(9, 9, 28_590, UpgradeStop.GOLD));
    }

    @Test
    void 超当前版本的重数保留原值并拒绝收费_不先做加一() {
        for (int rank : new int[] {15, Integer.MIN_VALUE, -1}) {
            for (int count : new int[] {1, 10}) {
                assertThat(ClassSkillRules.planUpgrade(rank, 10, 10, 1, -1L, count))
                        .isEqualTo(new UpgradePlan(0, rank, 0, UpgradeStop.MAX_RANK));
            }
        }
        assertThat(ClassSkillRules.planUpgrade(-1, -1, -1, 0, -1L, 1))
                .isEqualTo(new UpgradePlan(0, -1, 0, UpgradeStop.MAX_RANK));
    }

    @Test
    void 计划比较余额和重数均按无符号位模式() {
        // 下一重为 2^32−1，价格是高位为 1 的 uint64；不能按有符号认为是负价。
        long cost = -858_993_459_100L;
        assertThat(ClassSkillRules.planUpgrade(-2, -1, -1, 0, Long.MAX_VALUE, 1))
                .isEqualTo(new UpgradePlan(0, -2, 0, UpgradeStop.GOLD));
        assertThat(ClassSkillRules.planUpgrade(-2, -1, -1, 0, -1L, 1))
                .isEqualTo(new UpgradePlan(1, -1, cost, UpgradeStop.NONE));
        assertThat(ClassSkillRules.planUpgrade(-2, -1, -1, 0, -1L, 10))
                .isEqualTo(new UpgradePlan(1, -1, cost, UpgradeStop.MAX_RANK));
    }

    @Test
    void 非法修习次数不进入计算循环() {
        assertThat(ClassSkillRules.isValidUpgradeCount(1)).isTrue();
        assertThat(ClassSkillRules.isValidUpgradeCount(10)).isTrue();
        for (int count : new int[] {0, 2, 11, Integer.MIN_VALUE, -1}) {
            assertThat(ClassSkillRules.isValidUpgradeCount(count)).isFalse();
            assertThatThrownBy(() -> ClassSkillRules.planUpgrade(0, -1, -1, 0, -1L, count))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
