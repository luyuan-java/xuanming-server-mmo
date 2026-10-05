package com.game.battle;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.battle.BattleTables.RowReport;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 七张战斗表的行数报告（基线 {@code main.cpp:180-221}）：四张关键表为空打 ERROR、Item 为空打 WARN，都不拒启。 */
class BattleTablesTest {

    @Test
    void 七张表齐全_无告警_摘要同基线写法() {
        RowReport report = RowReport.of(Map.of("Skill", 10, "Buff", 4, "Cooldown", 3, "SkillPermission", 2, "Dungeon", 1,
                "Monster", 5, "Item", 7, "World", 99));

        assertThat(report.emptyCritical()).isEmpty();
        assertThat(report.itemEmpty()).isFalse();
        assertThat(report.summary())
                .isEqualTo("skill=10 buff=4 cooldown=3 skill_permission=2 dungeon=1 monster=5 item=7");
    }

    @Test
    void 关键表为空_列出来_Item为空单独标记_缺表按0() {
        RowReport report = RowReport.of(Map.of("Skill", 0, "Buff", 4, "Cooldown", 0, "Dungeon", 0, "Monster", 5));

        assertThat(report.emptyCritical()).containsExactly("Skill", "Dungeon");
        assertThat(report.itemEmpty()).isTrue();
        assertThat(report.rows()).containsEntry("SkillPermission", 0).containsEntry("Item", 0);
    }
}
