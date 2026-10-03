package com.game.scene.mission;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.scene.mission.MissionTables.ConditionDef;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 条件判定（基线 condition_util + UpdateProgressIfConditionMatches）：比较符、参数匹配、累计 / 持有、封顶。 */
class ConditionRulesTest {

    private static final long U32 = 0xFFFF_FFFFL;

    private static ConditionDef cond(int category, int op, int quantityType, int target, List<Integer> c1,
                                     List<Integer> c2) {
        return new ConditionDef(1, category, c1, c2, List.of(), List.of(), 0, quantityType, target, op);
    }

    private static ConditionDef kill(int op, int target, Integer... monsters) {
        return cond(1, op, 0, target, List.of(monsters), List.of());
    }

    private static MissionFact fact(int category, long amount, Integer... ids) {
        return new MissionFact(category, List.of(ids), amount, 0);
    }

    @Test
    void 比较符按序号_0大于等于_1大于_2小于等于_3小于_4等于_其余不达成() {
        assertThat(ConditionRules.isFulfilled(kill(0, 2), 2, 0)).isTrue();
        assertThat(ConditionRules.isFulfilled(kill(0, 2), 1, 0)).isFalse();
        assertThat(ConditionRules.isFulfilled(kill(1, 2), 2, 0)).isFalse();
        assertThat(ConditionRules.isFulfilled(kill(1, 2), 3, 0)).isTrue();
        assertThat(ConditionRules.isFulfilled(kill(2, 2), 2, 0)).isTrue();
        assertThat(ConditionRules.isFulfilled(kill(2, 2), 3, 0)).isFalse();
        assertThat(ConditionRules.isFulfilled(kill(3, 2), 1, 0)).isTrue();
        assertThat(ConditionRules.isFulfilled(kill(3, 2), 2, 0)).isFalse();
        assertThat(ConditionRules.isFulfilled(kill(4, 2), 2, 0)).isTrue();
        assertThat(ConditionRules.isFulfilled(kill(4, 2), 3, 0)).isFalse();
        assertThat(ConditionRules.isFulfilled(kill(5, 2), 2, 0)).isFalse();
        assertThat(ConditionRules.isFulfilled(kill(-1, 2), 2, 0)).as("uint32 比较符按无符号看").isFalse();
        assertThat(ConditionRules.isFulfilled(null, 100, 0)).isFalse();
    }

    @Test
    void 任务行的目标覆盖大于0时优先_按无符号() {
        assertThat(ConditionRules.effectiveTarget(kill(0, 1), 8)).isEqualTo(8);
        assertThat(ConditionRules.effectiveTarget(kill(0, 1), 0)).isEqualTo(1);
        assertThat(ConditionRules.effectiveTarget(kill(0, -1), 0)).isEqualTo(U32);
        assertThat(ConditionRules.isFulfilled(kill(0, 1), 7, 8)).isFalse();
        assertThat(ConditionRules.isFulfilled(kill(0, 1), 8, 8)).isTrue();
    }

    @Test
    void 参数匹配_非空列表包含对应位置的参数_列表内任一_列表间全部_全空命中任何事件() {
        ConditionDef c = cond(4, 0, 0, 1, List.of(1, 3), List.of(2));
        assertThat(ConditionRules.matchesEventSlots(c, List.of(1, 2))).isTrue();
        assertThat(ConditionRules.matchesEventSlots(c, List.of(3, 2))).isTrue();
        assertThat(ConditionRules.matchesEventSlots(c, List.of(2, 2))).isFalse();
        assertThat(ConditionRules.matchesEventSlots(c, List.of(1, 9))).isFalse();
        assertThat(ConditionRules.matchesEventSlots(c, List.of(1))).as("事件参数不够").isFalse();
        assertThat(ConditionRules.matchesEventSlots(kill(0, 1), List.of(77))).isTrue();
    }

    @Test
    void 累计型累加并封顶到目标_比较符大于时封顶到目标加1_不超uint32() {
        assertThat(ConditionRules.advance(kill(0, 8), 0, 0, fact(1, U32, 1))).isEqualTo(8);
        assertThat(ConditionRules.advance(kill(1, 8), 0, 0, fact(1, U32, 1))).isEqualTo(9);
        assertThat(ConditionRules.advance(kill(0, 2), 1, 0, fact(1, 1, 1))).isEqualTo(2);
        assertThat(ConditionRules.advance(kill(0, -1), U32 - 1, 0, fact(1, U32, 1))).isEqualTo(U32);
        assertThat(ConditionRules.advance(kill(0, 1), 1, 0, fact(1, 1, 1))).as("已达成不再推进").isEqualTo(-1);
        assertThat(ConditionRules.advance(kill(4, 3), 3, 0, fact(1, 1, 1))).as("等于达成后不再推进").isEqualTo(-1);
    }

    @Test
    void 类别不符或参数不命中不推进() {
        assertThat(ConditionRules.advance(kill(0, 2), 0, 0, fact(6, 1, 1))).isEqualTo(-1);
        assertThat(ConditionRules.advance(kill(0, 2, 1), 0, 0, fact(1, 1, 2))).isEqualTo(-1);
        assertThat(ConditionRules.advance(kill(0, 2), 0, 0, fact(1, 1, 99))).as("空列表命中任何怪").isEqualTo(1);
    }

    @Test
    void 等级类别是持有型_直接覆盖_可以变小_阈值任一达到即可_condition2非空不命中() {
        ConditionDef level = cond(6, 0, 0, 40, List.of(10, 30), List.of());
        assertThat(ConditionRules.advance(level, 0, 0, fact(6, 20, 20))).isEqualTo(20);
        assertThat(ConditionRules.advance(level, 25, 0, fact(6, 20, 20))).as("覆盖成更小的值").isEqualTo(20);
        assertThat(ConditionRules.advance(level, 20, 0, fact(6, 20, 20))).as("值没变不算推进").isEqualTo(-1);
        assertThat(ConditionRules.advance(level, 0, 0, fact(6, 9, 9))).as("没到任何阈值").isEqualTo(-1);
        assertThat(ConditionRules.advance(level, 0, 0, fact(6, 100, 100))).as("持有型不按目标封顶").isEqualTo(100);
        assertThat(ConditionRules.advance(cond(6, 0, 0, 1, List.of(), List.of()), 0, 0, fact(6, 3, 3))).isEqualTo(3);
        assertThat(ConditionRules.advance(cond(6, 0, 0, 1, List.of(), List.of(1)), 0, 0, fact(6, 3, 3))).isEqualTo(-1);
        assertThat(ConditionRules.advance(level, 0, 0, fact(6, 0x1_0000_0005L, 1))).isEqualTo(U32);
    }

    @Test
    void quantity_type为1的非等级条件也是覆盖() {
        ConditionDef owned = cond(1, 0, 1, 10, List.of(), List.of());
        assertThat(ConditionRules.advance(owned, 4, 0, fact(1, 2, 1))).isEqualTo(2);
    }
}
