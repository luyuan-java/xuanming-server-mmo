package com.game.guild.service;

import com.game.guild.rules.GuildTableRules;
import com.game.guild.rules.GuildTableRules.ApplicationRules;
import com.game.guild.rules.GuildTableRules.LevelDisplay;
import com.game.table.ConfigTables;
import java.util.OptionalInt;
import java.util.function.Supplier;

/**
 * 服务用到的配表现查（基线 guild_manage_logic.go:37-96；guild-spec §2.6）：配表只存 id、用时现查。生产实现 {@link #of} 每次都从当前快照读
 * （{@link GuildTableRules} 的运行期函数），单测可换成假实现（{@code ConfigTables} 只能从磁盘加载，造不出「缺行」的快照）。
 *
 * <p>查不到时的处置在调用方（与基线一致）：申请规则 / 建帮成员上限缺行 → 故障（信封 1003）；长老上限缺行 → 存储回
 * {@code LEVEL_CONFIG_MISSING}（故障）；展示字段缺行 → 留 0 并记 ERROR。
 */
public interface GuildTableLookup {

    /** GuildRule[1] 换算成的申请规则；null = 缺行。 */
    ApplicationRules applicationRules();

    /** GuildLevel[level].max_officers；空 = 缺该等级行。 */
    OptionalInt officerCap(int level);

    /** GuildLevel[1].max_members（建帮的成员上限）；空 = 缺第 1 级。 */
    OptionalInt initialMaxMembers();

    /** GuildLevel[level] 的两个展示字段；null = 缺该等级行。 */
    LevelDisplay levelDisplay(int level);

    /** 以配表快照为数据源（{@code tables} 每次调用现取，便于将来整体替换快照做热更）。 */
    static GuildTableLookup of(Supplier<ConfigTables> tables) {
        return new GuildTableLookup() {
            @Override
            public ApplicationRules applicationRules() {
                return GuildTableRules.applicationRules(tables.get());
            }

            @Override
            public OptionalInt officerCap(int level) {
                return GuildTableRules.officerCap(tables.get(), level);
            }

            @Override
            public OptionalInt initialMaxMembers() {
                return GuildTableRules.initialMaxMembers(tables.get());
            }

            @Override
            public LevelDisplay levelDisplay(int level) {
                return GuildTableRules.levelDisplay(tables.get(), level);
            }
        };
    }
}
