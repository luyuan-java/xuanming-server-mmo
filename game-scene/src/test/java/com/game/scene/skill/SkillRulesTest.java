package com.game.scene.skill;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.combatai.StateMutexMatrix;
import com.game.common.combatai.StateMutexMatrix.StateExitHookRegistry;
import com.game.common.combatai.StateMutexMatrix.StateExitReason;
import com.game.common.combatai.StateMutexMatrix.StateMode;
import com.game.scene.player.PlayerSkillState;
import com.game.scene.player.PlayerSkillState.Cast;
import com.game.scene.player.PlayerSkillState.Phase;
import com.game.scene.skill.SkillTables.SkillDef;
import com.game.table.ConfigTables;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 放技能的纯规则与配表视图：目标方式、表提示码、位图行为互斥与退出钩子、战斗状态、技能许可、阶段结算。 */
class SkillRulesTest {

    private static SkillDef skill(List<Integer> types, List<Integer> modes) {
        return new SkillDef(1, types, modes, false, 0, 0, 0, 0);
    }

    @Test
    void 正式表_技能行与冷却按秒和毫秒换成纳秒_规范化互斥矩阵预编译生效() {
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

        StateMutexMatrix actionMatrix = tables.actionStateMatrix();
        assertThat(actionMatrix.hasAction(SkillService.ACTION_USE_SKILL)).isTrue();
        long followAndMount = (1L << SkillService.STATE_TEAM_FOLLOW) | (1L << SkillService.STATE_MOUNTED);
        var evalUseSkill = actionMatrix.evaluate(followAndMount, SkillService.ACTION_USE_SKILL,
                SkillRules.OK, SkillRules.INVALID_TABLE_ID);
        assertThat(evalUseSkill.allowed()).isTrue();
        assertThat(evalUseSkill.interruptMask()).isEqualTo(followAndMount);
        assertThat(actionMatrix.canPerform(1L << SkillService.STATE_COMBAT, 1,
                SkillRules.OK, SkillRules.INVALID_TABLE_ID)).as("战斗中禁止跟随").isEqualTo(7004);
        assertThat(tables.combatStateMatrix().hasAction(SkillService.ACTION_USE_SKILL)).isTrue();
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
    void 行为互斥_行缺失1001_互斥格按提示码_打断格先摘态再触发退出钩子_最后加成功状态() {
        PlayerSkillState state = new PlayerSkillState();
        StateExitHookRegistry<String> hooks = new StateExitHookRegistry<>();
        assertThat(SkillRules.tryPerformAction(null, state, 0, 0, "p1", hooks)).isEqualTo(1001);
        assertThat(state.actionStates()).isEmpty();

        StateMutexMatrix matrix = new StateMutexMatrix();
        // BLOCK 配 1000 经 compileMatrixRow 归一化为放行，INTERRUPT 删状态并触发退出钩子
        SkillTables.compileMatrixRow("test", matrix, null, 0, 0, StateMode.ALLOW.code(), 1000);
        SkillTables.compileMatrixRow("test", matrix, null, 0, 1, StateMode.BLOCK.code(), 1000);
        SkillTables.compileMatrixRow("test", matrix, null, 0, 2, StateMode.INTERRUPT.code(), 0);

        state.addActionState(1);
        state.addActionState(2);
        state.addActionState(5);
        List<String> hookLog = new ArrayList<>();
        hooks.register(2, (owner, reason) -> {
            assertThat(state.hasActionState(2)).as("钩子触发前状态必须已移除").isFalse();
            hookLog.add(owner + ":" + reason);
        });

        assertThat(SkillRules.tryPerformAction(matrix, state, 0, 0, "p1", hooks)).isZero();
        assertThat(state.actionStates()).as("互斥但提示 1000 放行；打断格（2）删掉；越界的 5 不动；加上 0")
                .containsExactly(0, 1, 5);
        assertThat(state.actionStateMask()).isEqualTo((1L << 0) | (1L << 1) | (1L << 5));
        assertThat(hookLog).containsExactly("p1:" + StateExitReason.INTERRUPTED);

        StateMutexMatrix mutex = new StateMutexMatrix();
        SkillTables.compileMatrixRow("test", mutex, null, 0, 0, StateMode.ALLOW.code(), 1000);
        SkillTables.compileMatrixRow("test", mutex, null, 0, 1, StateMode.BLOCK.code(), 10000);
        assertThat(SkillRules.tryPerformAction(mutex, state, 0, 2, "p1", hooks)).isEqualTo(10000);
        assertThat(state.actionStates()).as("被拒不改状态").containsExactly(0, 1, 5);

        assertThat(SkillRules.tryPerformAction(matrix, state, 0, SkillRules.ACTOR_STATE_MAX, "p1", hooks))
                .as("非法成功入态拒绝且无副作用").isEqualTo(SkillRules.INVALID_PARAMETER);
    }

    @Test
    void 战斗状态互斥_没有状态放行_按最小状态号返回互斥提示码() {
        StateMutexMatrix matrix = new StateMutexMatrix();
        SkillTables.compileMatrixRow("combat", matrix, null, 0, 0, StateMode.BLOCK.code(), 1000);
        SkillTables.compileMatrixRow("combat", matrix, null, 0, 1, StateMode.BLOCK.code(), 7005);

        assertThat(SkillRules.validateCombatStates(null, 0L, 0)).isZero();
        assertThat(SkillRules.validateCombatStates(null, 1L << 1, 0)).isEqualTo(1001);
        assertThat(SkillRules.validateCombatStates(matrix, 1L << 1, 0)).isEqualTo(7005);
        assertThat(SkillRules.validateCombatStates(matrix, (1L << 0) | (1L << 1), 0))
                .as("状态 0 填 1000 归一化为放行，状态 1 仍正确触发 7005").isEqualTo(7005);
        assertThat(SkillRules.validateCombatStates(matrix, 1L << 9, 0)).as("未配置状态位放行").isZero();
    }

    @Test
    void 技能许可_行缺失1001_类型位越界1002_格子按提示码() {
        SkillTables tables = new SkillTables(Map.of(), Map.of(), new StateMutexMatrix(), new StateMutexMatrix(),
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

        Cast zero = new Cast(1, Phase.CASTING, 1_000, -1, 0);
        assertThat(SkillService.settle(zero, 1_000)).as("0 长度的后摇不算进行中").isNull();
        assertThat(SkillService.settle(null, 5)).isNull();
    }
}
