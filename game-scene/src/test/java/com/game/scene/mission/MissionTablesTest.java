package com.game.scene.mission;

import static com.game.scene.mission.MissionFixtures.condition;
import static com.game.scene.mission.MissionFixtures.edit;
import static com.game.scene.mission.MissionFixtures.mission;
import static com.game.scene.mission.MissionFixtures.newCondition;
import static com.game.scene.mission.MissionFixtures.reward;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.scene.mission.MissionTables.MissionDef;
import com.game.table.DungeonTable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 任务配表视图：正式表的静态闸结论、可达怪物、奖励合并规则、加载不拒绝。 */
class MissionTablesTest {

    /** 静态闸（两道）合起来：正式表上新号接取时不看玩家状态的那部分结论。 */
    private static int staticTip(MissionDef def) {
        return def.earlyTip() != 0 ? def.earlyTip() : def.lateTip();
    }

    @Test
    void 正式表_可达怪物是副本里出现且怪物表里有的() {
        assertThat(MissionTables.reachableMonsters(MissionFixtures.SHIPPED.dungeon().all(),
                MissionFixtures.SHIPPED.monster()::contains)).containsExactlyInAnyOrder(1, 2, 6, 7, 11, 12, 16);
        DungeonTable withZero = DungeonTable.newBuilder().setId(9).addMonster(0).addMonster(99).addMonster(3).build();
        assertThat(MissionTables.reachableMonsters(List.of(withZero), id -> id != 99)).containsExactly(3);
    }

    @Test
    void 正式表_17个任务的静态闸结论同基线() {
        MissionTables tables = MissionFixtures.shippedTables();
        Map<Integer, Integer> tips = new HashMap<>();
        for (MissionDef def : tables.missions()) {
            tips.put(def.id(), staticTip(def));
        }
        assertThat(tips).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry(1, 1003), Map.entry(2, 1003), Map.entry(3, 1002), Map.entry(4, 0), Map.entry(5, 1002),
                Map.entry(6, 1003), Map.entry(7, 0), Map.entry(8, 0), Map.entry(9, 0), Map.entry(10, 1003),
                Map.entry(11, 1003), Map.entry(12, 0), Map.entry(13, 0), Map.entry(14, 0), Map.entry(15, 0),
                Map.entry(16, 0), Map.entry(17, 0)));
        assertThat(tables.missions()).extracting(MissionDef::id)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17);
        assertThat(tables.reward(1).items()).containsExactly(Map.entry(1, 4L));
        MissionDef seven = tables.mission(7);
        assertThat(seven.nextMissionIds()).containsExactly(8);
        assertThat(seven.slots().get(0).targetOverride()).isEqualTo(8);
        assertThat(tables.mission(2).ordered()).isTrue();
        assertThat(tables.mission(15).schedule()).isNotNull();
    }

    @Test
    void 击杀条件可达性看列表里任一_空列表看是否有任何可达怪物_condition2非空一律1003() {
        assertThat(staticTip(edit().condition(condition(1).clearCondition1().addCondition1(3).addCondition1(1))
                .build().mission(4))).isZero();
        assertThat(staticTip(edit().condition(condition(1).clearCondition1().addCondition1(3).addCondition1(4))
                .build().mission(4))).isEqualTo(1003);
        assertThat(staticTip(edit().condition(condition(1).clearCondition1()).build().mission(4))).isZero();
        assertThat(staticTip(edit().condition(condition(1).clearCondition1().addCondition2(1)).build().mission(4)))
                .isEqualTo(1003);
    }

    @Test
    void 副本或怪物表缺失时击杀条件fail_closed_怪物行缺失不能用副本引用顶替() {
        assertThat(staticTip(edit().noDungeons().build().mission(4))).isEqualTo(1003);
        assertThat(staticTip(edit().noMonsters().build().mission(9))).isEqualTo(1003);
        MissionTables noMonster1 = edit().removeMonster(1).build();
        assertThat(staticTip(noMonster1.mission(4))).isEqualTo(1003);
        assertThat(staticTip(noMonster1.mission(9))).isZero();
    }

    @Test
    void 逐格静态闸按格子顺序_第一个不过的格子定错误码() {
        // 条件缺失 1002、比较符非法 1002、目标为 0 1002、来源不支持 1003、有时效 1003、计数方式 1003、大于 + 目标 uint32 上限 1002
        assertThat(lateTipWith(newCondition(1, 1, 1, 1).setComparisonOp(2))).isEqualTo(1002);
        assertThat(lateTipWith(newCondition(1, 1, 0, 1))).isEqualTo(1002);
        assertThat(lateTipWith(newCondition(1, 2, 1, 1))).isEqualTo(1003);
        assertThat(lateTipWith(newCondition(1, 1, 1, 1).setValidDuration(5))).isEqualTo(1003);
        assertThat(lateTipWith(newCondition(1, 1, 1, 1).setQuantityType(1))).isEqualTo(1003);
        assertThat(lateTipWith(newCondition(1, 6, 1, 10).setQuantityType(1))).isZero();
        assertThat(lateTipWith(newCondition(1, 6, 1, 10).setQuantityType(2))).isEqualTo(1003);
        assertThat(lateTipWith(newCondition(1, 1, -1, 1).setComparisonOp(1))).isEqualTo(1002);
        assertThat(lateTipWith(newCondition(1, 1, -1, 1))).as(">= 目标 uint32 上限可以").isZero();
        assertThat(lateTipWith(newCondition(1, 8, 1, 15))).isZero();
        assertThat(staticTip(edit().removeCondition(1).build().mission(4))).isEqualTo(1002);
        // 任务行的目标覆盖让条件目标 0 也能过
        assertThat(staticTip(edit().condition(newCondition(1, 1, 0, 1)).build().mission(7))).isZero();
        // 跨格：第一个不过的格子定错误码（格 0 来源不支持 1003、格 1 比较符非法 1002 → 1003；反过来 → 1002）
        MissionTables crossed = edit()
                .condition(newCondition(901, 2, 1, 1))
                .condition(newCondition(902, 1, 1, 1).setComparisonOp(2))
                .mission(MissionFixtures.newMission(70, 1, 1, 0, 901, 902))
                .mission(MissionFixtures.newMission(71, 1, 1, 0, 902, 901))
                .build();
        assertThat(staticTip(crossed.mission(70))).isEqualTo(1003);
        assertThat(staticTip(crossed.mission(71))).isEqualTo(1002);
        // 同一格多处不合格：比较符 / 目标（1002）先于来源（1003），计数方式（1003）先于「> + uint32 上限」（1002）
        assertThat(lateTipWith(newCondition(1, 2, 1, 1).setComparisonOp(2))).isEqualTo(1002);
        assertThat(lateTipWith(newCondition(1, 2, -1, 1).setComparisonOp(1))).isEqualTo(1003);
        assertThat(lateTipWith(newCondition(1, 1, -1, 1).setComparisonOp(1).setQuantityType(2))).isEqualTo(1003);
        // 顺序非法 1002 先于条件
        assertThat(edit().mission(mission(4).setConditionOrder(2)).build().mission(4).earlyTip()).isEqualTo(1002);
    }

    private static int lateTipWith(com.game.table.ConditionTable.Builder condition) {
        // 任务 16 没有目标覆盖（任务 4 覆盖成 1，测不到条件目标 0）
        return staticTip(edit().condition(condition).build().mission(16));
    }

    @Test
    void 奖励合并_0成功且为空_缺行物品0数量0未知物品溢出空都是1002() {
        MissionTables tables = edit()
                .reward(reward(1, 1, 2, 10, 3, 1, 2))
                .reward(reward(2, 0, 1))
                .reward(reward(3, 1, 0))
                .reward(reward(4, 999_999, 1))
                .reward(reward(5, 10, -1, 10, 1))
                .reward(reward(6))
                .reward(reward(7, 10, -1))
                .build();
        assertThat(tables.reward(0).tip()).isZero();
        assertThat(tables.reward(0).items()).isEmpty();
        assertThat(tables.reward(1).items()).containsExactly(Map.entry(1, 4L), Map.entry(10, 3L));
        for (int id : new int[] {2, 3, 4, 5, 6, 99}) {
            assertThat(tables.reward(id).tip()).as("reward %d", id).isEqualTo(1002);
        }
        assertThat(tables.reward(7).items()).containsExactly(Map.entry(10, 0xFFFF_FFFFL));
        assertThat(edit().removeItem(1).build().reward(1).tip()).isEqualTo(1002);
    }

    @Test
    void 类别集合只含表里有的条件() {
        MissionTables tables = MissionFixtures.shippedTables();
        assertThat(tables.mission(14).categories()).isEqualTo(Set.of(8));
        assertThat(edit().removeCondition(28).build().mission(14).categories()).isEqualTo(Set.of(8));
        assertThat(tables.mission(6).categories()).containsExactly(1, 2, 3, 4, 6, 5);
    }
}
