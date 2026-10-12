package com.game.scene.skill;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.scene.player.PlayerSkillState.Cast;
import com.game.scene.player.PlayerSkillState.Phase;
import com.game.scene.skill.SkillTables.SkillDef;
import com.game.scene.skill.SkillTables.StateCell;
import com.game.table.ConfigTables;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** 放技能的纯规则与配表视图：目标方式、表提示码、行为互斥、战斗状态、技能许可、阶段结算。 */
class SkillRulesTest {

    private static SkillDef skill(List<Integer> types, List<Integer> modes) {
        return new SkillDef(1, types, modes, false, 0, 0, 0, 0);
    }

    @Test
    void 正式表_技能行与冷却按秒和毫秒换成纳秒() {
        Path dir = Files.isDirectory(Path.of("../config-data/tables"))
                ? Path.of("../config-data/tables")
                : Path.of("config-data/tables");
        SkillTables tables = SkillTables.from(ConfigTables.load(dir));
        SkillDef one = tables.skill(1);
        assertThat(one.general()).isTrue();
        assertThat(one.immediate()).isTrue();
        assertThat(one.targetingModes()).containsExactly(1, 2);
        assertThat(one.castPointNanos()).isEqualTo(1_000_000_000L);
        assertThat(one.recoveryNanos()).isEqualTo(1_000_000_000L);
        assertThat(tables.cooldownNanos(one.cooldownId())).isEqualTo(500_000_000L);
        SkillDef thirteen = tables.skill(13);
        assertThat(thirteen.castPointNanos()).isEqualTo(300_000_000L);
        assertThat(tables.cooldownNanos(thirteen.cooldownId())).isEqualTo(2_000_000_000L);
        assertThat(tables.cooldownNanos(tables.skill(2).cooldownId())).as("0 号冷却组没有行，不冷却").isZero();
        SkillDef three = tables.skill(3);
        assertThat(three.channel()).isTrue();
        assertThat(three.channelFinishNanos()).isEqualTo(1_000_000_000L);
        assertThat(tables.skill(4).general() || tables.skill(4).channel()).as("开关技能没有施法阶段").isFalse();
        assertThat(tables.skill(0)).isNull();
        assertThat(tables.actionStateRow(0)).extracting(StateCell::mode).containsOnly(1);
        assertThat(tables.skillPermissionRow(1)).hasSize(6).containsOnly(1000);
    }

    @Test
    void 目标闸() {
        Set<Long> present = Set.of(77L);
        assertThat(SkillRules.checkTarget(skill(List.of(1), List.of()), 0, present::contains)).as("没有目标方式不查").isZero();
        assertThat(SkillRules.checkTarget(skill(List.of(1), List.of(2)), 0, present::contains)).isEqualTo(7001);
        assertThat(SkillRules.checkTarget(skill(List.of(1), List.of(3)), 0, present::contains)).isEqualTo(7001);
        assertThat(SkillRules.checkTarget(skill(List.of(1), List.of(3)), 5, present::contains)).isZero();
        assertThat(SkillRules.checkTarget(skill(List.of(1), List.of(1, 2)), 5, present::contains)).isEqualTo(7001);
        assertThat(SkillRules.checkTarget(skill(List.of(1), List.of(1, 2)), 77, present::contains)).isZero();
        assertThat(SkillRules.checkTarget(skill(List.of(1), List.of(2, 1)), 5, present::contains)).as("第一个认得出的方式说了算")
                .isZero();
        assertThat(SkillRules.checkTarget(skill(List.of(1), List.of(3, 1)), 5, present::contains)).as("认不出的方式跳过")
                .isEqualTo(7001);
        assertThat(SkillRules.checkTarget(skill(List.of(1), List.of(0)), -1, present::contains)).isZero();
    }

    @Test
    void 表提示码_1000放行_0按配置错() {
        assertThat(SkillRules.tableTip(1000)).isZero();
        assertThat(SkillRules.tableTip(0)).isEqualTo(1002);
        assertThat(SkillRules.tableTip(7005)).isEqualTo(7005);
    }

    @Test
    void 行为互斥_行缺失1001_互斥格按提示码_越界放行_打断格删状态_最后加成功状态() {
        TreeSet<Integer> states = new TreeSet<>();
        assertThat(SkillRules.tryPerformAction(null, states, 0)).isEqualTo(1001);
        assertThat(states).isEmpty();

        List<StateCell> row = List.of(new StateCell(1, 1000), new StateCell(0, 1000), new StateCell(2, 0));
        states.addAll(Set.of(1, 2, 5));
        assertThat(SkillRules.tryPerformAction(row, states, 0)).isZero();
        assertThat(states).as("互斥但提示 1000 放行；打断格（2）删掉；越界的 5 不动；加上 0").containsExactly(0, 1, 5);

        List<StateCell> mutex = List.of(new StateCell(1, 1000), new StateCell(0, 10000));
        assertThat(SkillRules.tryPerformAction(mutex, states, 2)).isEqualTo(10000);
        assertThat(states).as("被拒不改状态").containsExactly(0, 1, 5);

        TreeSet<Integer> huge = new TreeSet<>(Set.of(-1));
        assertThat(SkillRules.tryPerformAction(row, huge, 0)).as("uint32 极大状态号越界放行，不抛").isZero();
        assertThat(huge).containsExactly(-1, 0);
    }

    @Test
    void 战斗状态互斥_没有状态放行_第一个互斥格就返回() {
        List<StateCell> row = List.of(new StateCell(1, 1000), new StateCell(1, 7005));
        assertThat(SkillRules.validateCombatStates(null, Set.of())).isZero();
        assertThat(SkillRules.validateCombatStates(null, Set.of(1))).isEqualTo(1001);
        assertThat(SkillRules.validateCombatStates(row, new TreeSet<>(Set.of(1)))).isEqualTo(7005);
        assertThat(SkillRules.validateCombatStates(row, new TreeSet<>(Set.of(0, 1)))).as("状态 0 的互斥格提示 1000：放行并结束")
                .isZero();
        assertThat(SkillRules.validateCombatStates(row, Set.of(9))).as("越界跳过").isZero();
        assertThat(SkillRules.validateCombatStates(row, Set.of(-1))).as("uint32 极大状态号越界跳过，不抛").isZero();
    }

    @Test
    void 技能许可_行缺失1001_类型位越界1002_格子按提示码() {
        SkillTables tables = new SkillTables(Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(1, List.of(1000, 1000, 7005)));
        assertThat(SkillRules.checkSkillPermission(tables, skill(List.of(1), List.of()), Set.of())).isZero();
        assertThat(SkillRules.checkSkillPermission(tables, skill(List.of(1), List.of()), Set.of(1))).isZero();
        assertThat(SkillRules.checkSkillPermission(tables, skill(List.of(1, 2), List.of()), Set.of(1))).isEqualTo(7005);
        assertThat(SkillRules.checkSkillPermission(tables, skill(List.of(6), List.of()), Set.of(1))).isEqualTo(1002);
        assertThat(SkillRules.checkSkillPermission(tables, skill(List.of(1), List.of()), Set.of(2))).isEqualTo(1001);
    }

    @Test
    void 阶段按截止时刻顺次结算_每段从上一段截止起算() {
        Cast general = new Cast(1, Phase.CASTING, 1_000, -1, 500);
        assertThat(SkillService.settle(general, 999)).isEqualTo(general);
        assertThat(SkillService.settle(general, 1_000)).isEqualTo(new Cast(1, Phase.RECOVERY, 1_500, -1, 500));
        assertThat(SkillService.settle(general, 1_499).phase()).isEqualTo(Phase.RECOVERY);
        assertThat(SkillService.settle(general, 1_500)).isNull();

        Cast channel = new Cast(3, Phase.CASTING, 1_000, 2_000, 500);
        assertThat(SkillService.settle(channel, 2_999)).isEqualTo(new Cast(3, Phase.CHANNELING, 3_000, 2_000, 500));
        assertThat(SkillService.settle(channel, 3_000)).isEqualTo(new Cast(3, Phase.RECOVERY, 3_500, 2_000, 500));
        assertThat(SkillService.settle(channel, 9_999)).as("很久之后一次结算到底").isNull();

        Cast zero = new Cast(2, Phase.CASTING, 1_000, -1, 0);
        assertThat(SkillService.settle(zero, 1_000)).as("0 长度的后摇不算进行中").isNull();
        assertThat(SkillService.settle(null, 5)).isNull();
    }
}
