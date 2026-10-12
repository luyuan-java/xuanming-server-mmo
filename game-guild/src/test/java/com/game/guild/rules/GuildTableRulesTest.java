package com.game.guild.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.table.ConfigTables;
import com.game.table.GuildLevelTable;
import com.game.table.GuildRuleTable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * 配表启动校验与运行期现查（基线 guild_manage_logic_test.go:620-830 TestValidateGuildTables；guild-spec §2.6、§11.1）。
 * 坏样例逐条在合法样例上改一处；真实配表（config-data/tables）必须通过，且不因 GuildRule 第 8–10 列是 reserved 而拒启。
 */
class GuildTableRulesTest {

    /** GuildRule.xlsx 第 6 行（与真实配表逐格一致；第 5–7 列由 4.5 / 4.6 读，这里填上只为与真表一致）。 */
    private static GuildRuleTable legalRule() {
        return GuildRuleTable.newBuilder()
                .setId(1)
                .setApplicationExpireHours(72)
                .setMaxPendingApplicationsPerPlayer(3)
                .setMaxPendingApplicationsPerGuild(50)
                .setAssetOpDeadlineSeconds(600)
                .setAssetOpRetryBaseMs(1000)
                .setReunionMinOnlineMembers(3)
                .build();
    }

    /** GuildLevel.xlsx 第 6–15 行（默认 10 级）。 */
    private static List<GuildLevelTable> legalLevels() {
        long[][] rows = {
                {1, 30, 2, 20000}, {2, 35, 2, 50000}, {3, 40, 3, 100000}, {4, 45, 3, 180000}, {5, 50, 4, 300000},
                {6, 60, 4, 460000}, {7, 70, 5, 680000}, {8, 80, 5, 960000}, {9, 90, 6, 1300000}, {10, 100, 6, 0}};
        List<GuildLevelTable> levels = new ArrayList<>();
        for (long[] r : rows) {
            levels.add(GuildLevelTable.newBuilder()
                    .setId((int) r[0]).setMaxMembers((int) r[1]).setMaxOfficers((int) r[2]).setUpgradeCostFunds(r[3])
                    .build());
        }
        return levels;
    }

    private static GuildRuleTable brokenRule(Consumer<GuildRuleTable.Builder> mutate) {
        GuildRuleTable.Builder b = legalRule().toBuilder();
        mutate.accept(b);
        return b.build();
    }

    private static List<GuildLevelTable> brokenLevels(UnaryOperator<List<GuildLevelTable>> mutate) {
        return mutate.apply(legalLevels());
    }

    /** 改第 index 行的一个字段（行对象不可变，换成改过的新行）。 */
    private static List<GuildLevelTable> withRow(int index, Consumer<GuildLevelTable.Builder> mutate) {
        List<GuildLevelTable> rows = legalLevels();
        GuildLevelTable.Builder b = rows.get(index).toBuilder();
        mutate.accept(b);
        rows.set(index, b.build());
        return rows;
    }

    private static void assertRejected(GuildRuleTable rule, List<GuildLevelTable> levels, String wantContains) {
        assertThatThrownBy(() -> GuildTableRules.validate(rule, levels))
                .as("文案必须带表名 / 字段名：%s", wantContains)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(wantContains);
    }

    private static Path tableDir() {
        return Files.isDirectory(Path.of("../config-data/tables"))
                ? Path.of("../config-data/tables")
                : Path.of("config-data/tables");
    }

    @Test
    void 默认配表必须通过() {
        assertThatCode(() -> GuildTableRules.validate(legalRule(), legalLevels())).doesNotThrowAnyException();
    }

    @Test
    void 规则行坏样例() {
        assertRejected(null, legalLevels(), "GuildRule");
        assertRejected(brokenRule(b -> b.setApplicationExpireHours(0)), legalLevels(), "application_expire_hours");
        assertRejected(brokenRule(b -> b.setApplicationExpireHours(721)), legalLevels(), "application_expire_hours");
        assertRejected(brokenRule(b -> b.setMaxPendingApplicationsPerPlayer(0)), legalLevels(),
                "max_pending_applications_per_player");
        assertRejected(brokenRule(b -> b.setMaxPendingApplicationsPerPlayer(11)), legalLevels(),
                "max_pending_applications_per_player");
        assertRejected(brokenRule(b -> b.setMaxPendingApplicationsPerGuild(0)), legalLevels(),
                "max_pending_applications_per_guild");
        assertRejected(brokenRule(b -> b.setMaxPendingApplicationsPerGuild(501)), legalLevels(),
                "max_pending_applications_per_guild");
    }

    @Test
    void 规则行边界值合法() {
        GuildRuleTable low = brokenRule(b -> b.setApplicationExpireHours(1)
                .setMaxPendingApplicationsPerPlayer(1).setMaxPendingApplicationsPerGuild(1));
        GuildRuleTable high = brokenRule(b -> b.setApplicationExpireHours(720)
                .setMaxPendingApplicationsPerPlayer(10).setMaxPendingApplicationsPerGuild(500));
        assertThatCode(() -> GuildTableRules.validate(low, legalLevels())).doesNotThrowAnyException();
        assertThatCode(() -> GuildTableRules.validate(high, legalLevels())).doesNotThrowAnyException();
    }

    /** uint32 按无符号：按位是负数的值是 42 亿多，越界，文案也按无符号打印。 */
    @Test
    void 规则行按无符号判越界() {
        assertRejected(brokenRule(b -> b.setApplicationExpireHours(-1)), legalLevels(),
                "application_expire_hours=4294967295 越界");
    }

    /** 4.4 只校验第 2–4 列：第 5–7 列归 4.5 / 4.6，填 0 也不拒启（第 8–10 列在 Java 契约里是 reserved）。 */
    @Test
    void 不校验4_5与4_6的列() {
        GuildRuleTable only44 = GuildRuleTable.newBuilder()
                .setId(1).setApplicationExpireHours(72).setMaxPendingApplicationsPerPlayer(3)
                .setMaxPendingApplicationsPerGuild(50).build();
        assertThatCode(() -> GuildTableRules.validate(only44, legalLevels())).doesNotThrowAnyException();
    }

    @Test
    void 等级表坏样例() {
        GuildRuleTable rule = legalRule();
        assertRejected(rule, List.of(), "GuildLevel");
        assertRejected(rule, null, "GuildLevel");
        assertRejected(rule, brokenLevels(rows -> {
            rows.add(null);
            return rows;
        }), "GuildLevel 表第 11 行为空");
        // guild.level 直接当 id 查表，不从 1 开始等于第 1 级的帮会全部读不到配表
        assertRejected(rule, brokenLevels(rows -> {
            List<GuildLevelTable> shifted = new ArrayList<>();
            for (GuildLevelTable r : rows) {
                shifted.add(r.toBuilder().setId(r.getId() + 1).build());
            }
            return shifted;
        }), "GuildLevel.id");
        assertRejected(rule, brokenLevels(rows -> {
            rows.remove(2);
            return rows;
        }), "GuildLevel.id");
        // 重复 id 在排序后必然出现 id != 期望值
        assertRejected(rule, withRow(1, b -> b.setId(1)), "GuildLevel.id");
        // 相等就意味着可以把全帮任命成长老
        assertRejected(rule, withRow(0, b -> b.setMaxOfficers(30)), "max_officers");
        // 超过 MaxGuildMembersCap 就打破了推送批量与 GuildInfo 包体的预算前提
        assertRejected(rule, withRow(9, b -> b.setMaxMembers(GuildLimits.MAX_GUILD_MEMBERS_CAP + 1)), "max_members");
        assertRejected(rule, withRow(0, b -> b.setMaxMembers(1).setMaxOfficers(0)), "max_members");
        assertRejected(rule, withRow(1, b -> b.setMaxMembers(20)), "max_members");
        assertRejected(rule, withRow(2, b -> b.setMaxOfficers(1)), "max_officers");
        // 花费 0 的语义是「满级」，出现在中间一行等于那一级永远升不上去
        assertRejected(rule, withRow(4, b -> b.setUpgradeCostFunds(0)), "upgrade_cost_funds");
        assertRejected(rule, withRow(9, b -> b.setUpgradeCostFunds(1)), "upgrade_cost_funds");
    }

    @Test
    void 等级表边界值与乱序() {
        GuildRuleTable rule = legalRule();
        // 单级表：唯一一行就是最后一行，花费必须为 0
        List<GuildLevelTable> single = List.of(GuildLevelTable.newBuilder().setId(1).setMaxMembers(2).setMaxOfficers(1).build());
        assertThatCode(() -> GuildTableRules.validate(rule, single)).doesNotThrowAnyException();
        // 上限持平（不递减）合法
        assertThatCode(() -> GuildTableRules.validate(rule, withRow(1, b -> b.setMaxMembers(30))))
                .doesNotThrowAnyException();
        // FindAll 的顺序不作假设：乱序输入照样通过，且入参不被改动
        List<GuildLevelTable> shuffled = legalLevels();
        Collections.reverse(shuffled);
        List<GuildLevelTable> before = List.copyOf(shuffled);
        assertThatCode(() -> GuildTableRules.validate(rule, Collections.unmodifiableList(shuffled)))
                .doesNotThrowAnyException();
        assertThat(shuffled).containsExactlyElementsOf(before);
        // 末级花费是 uint64：按位为负的值非 0，照样拒绝，文案按无符号打印
        assertRejected(rule, withRow(9, b -> b.setUpgradeCostFunds(-1L)), "upgrade_cost_funds=18446744073709551615");
    }

    /** 当前真实配表必须通过——GuildRule 第 8–10 列在 Java 契约里是 reserved，不能因此拒启。 */
    @Test
    void 真实配表通过_运行期现查与表一致() {
        ConfigTables tables = ConfigTables.load(tableDir());
        assertThatCode(() -> GuildTableRules.validate(tables)).doesNotThrowAnyException();

        GuildRuleTable row = tables.guildRule().get(GuildTableRules.RULE_ROW_ID);
        GuildTableRules.ApplicationRules rules = GuildTableRules.applicationRules(tables);
        assertThat(rules).isNotNull();
        assertThat(rules.ttlMs()).isEqualTo(row.getApplicationExpireHours() * 3_600_000L);
        assertThat(rules.maxPerPlayer()).isEqualTo(row.getMaxPendingApplicationsPerPlayer());
        assertThat(rules.maxPerGuild()).isEqualTo(row.getMaxPendingApplicationsPerGuild());
        assertThat(rules.maxPerPlayer()).isLessThanOrEqualTo(GuildLimits.MY_APPLICATIONS_LIMIT);

        GuildLevelTable first = tables.guildLevel().get(GuildLimits.DEFAULT_INIT_LEVEL);
        assertThat(GuildTableRules.initialMaxMembers(tables)).hasValue(first.getMaxMembers());
        assertThat(GuildTableRules.officerCap(tables, 1)).hasValue(first.getMaxOfficers());
        int top = tables.guildLevel().size();
        GuildTableRules.LevelDisplay topDisplay = GuildTableRules.levelDisplay(tables, top);
        assertThat(topDisplay).isNotNull();
        assertThat(topDisplay.upgradeCostFunds()).as("满级").isZero();
        assertThat(topDisplay.maxOfficers()).isEqualTo(tables.guildLevel().get(top).getMaxOfficers());

        // 缺行：长老上限 fail-closed（仓储回 LEVEL_CONFIG_MISSING），展示留 null 由调用方填 0
        assertThat(GuildTableRules.officerCap(tables, top + 1)).isEmpty();
        assertThat(GuildTableRules.officerCap(tables, 0)).isEmpty();
        assertThat(GuildTableRules.levelDisplay(tables, top + 1)).isNull();
    }
}
