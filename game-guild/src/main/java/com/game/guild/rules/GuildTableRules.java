package com.game.guild.rules;

import com.game.table.ConfigTables;
import com.game.table.GuildLevelTable;
import com.game.table.GuildRuleTable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;

/**
 * 帮会配表（GuildRule[1]、GuildLevel）的启动校验与运行期现查（基线 guild_manage_logic.go:37-196；guild-spec §0.5「配表」、§2.6）。
 *
 * <p><b>启动校验</b>（{@link #validate}）：成员上限、长老上限、申请有效期全部来自这两张表，表错了会表现为「任免随机失败」「申请永不过期」
 * 这类静默错误，所以在对外服务之前 fail-closed 拒启（guild.go:95-97）。只校验 4.4 读的列：GuildRule 第 2–4 列、GuildLevel 第 1–4 列。
 * GuildRule 第 5–6 列归 4.5、第 7 列及以后归 4.6，<b>不在这里校验</b>——第 8–10 列在 Java 契约里是 {@code reserved}（数据未导出），
 * 校验它们会让当前数据拒启（§2.6）。4.5 的经济校验先调本校验再补自己的列（:125-126）。
 *
 * <p><b>运行期现查</b>：配表只存 id、用时现查（{@link ConfigTables} 是整体替换的不可变快照，长期持有行对象等于永远拿热更前的值）。
 * 启动校验已保证行存在且自洽，运行期查不到 = 配表被错误替换：除 {@link #levelDisplay}（纯展示，留 0 并记 ERROR）外，调用方一律按故障处理，
 * 不给默认值。
 *
 * <p>表里的 uint32 / uint64 在 Java 按位模式持有，这里全部按无符号比较与打印。错误文案照抄基线（带表名、行 id、字段名与实际值，
 * 策划看到日志能直接定位到格子）。全是纯函数，可在任意线程调用。
 */
public final class GuildTableRules {

    /** GuildRule 是单行全局规则表，id 固定 1（guild_manage_logic.go:45）。 */
    public static final int RULE_ROW_ID = 1;

    // 配表取值的合法区间（guild_manage_logic.go:102-110）。判据与文案用同一组数。
    public static final int MIN_APPLICATION_EXPIRE_HOURS = 1;
    /** 30 天：申请行按 expire_ms 惰性清理，再长就等于永不过期。 */
    public static final int MAX_APPLICATION_EXPIRE_HOURS = 720;
    public static final int MIN_PENDING_PER_PLAYER = 1;
    /** 也是 ListMyGuildApplications 的单次上限（{@link GuildLimits#MY_APPLICATIONS_LIMIT}）。 */
    public static final int MAX_PENDING_PER_PLAYER = 10;
    public static final int MIN_PENDING_PER_GUILD = 1;
    public static final int MAX_PENDING_PER_GUILD = 500;
    /** 至少容得下帮主 + 一个成员。 */
    public static final int MIN_GUILD_MAX_MEMBERS = 2;

    private static final long MILLIS_PER_HOUR = 3_600_000L;

    /**
     * 入帮申请的规则（guild_manage_logic.go:54-64 applicationRulesFromTable 的结果形态）。
     *
     * @param ttlMs        申请有效期（毫秒）= application_expire_hours × 3 600 000
     * @param maxPerPlayer 每人同时待审上限
     * @param maxPerGuild  每帮同时待审上限（也是待审名单的条数上限）
     */
    public record ApplicationRules(long ttlMs, int maxPerPlayer, int maxPerGuild) {
    }

    /**
     * 帮会等级的两个纯展示字段（guild_manage_logic.go:90-96 levelDisplay）。
     *
     * @param maxOfficers      GuildLevel[level].max_officers（GuildInfo.max_officers）
     * @param upgradeCostFunds GuildLevel[level].upgrade_cost_funds（GuildInfo.upgrade_cost_funds，uint64；0 = 满级）
     */
    public record LevelDisplay(int maxOfficers, long upgradeCostFunds) {
    }

    private GuildTableRules() {
    }

    // ================================================================ 启动校验

    /**
     * 校验一份配表快照（基线 ValidateGuildTables，guild_manage_logic.go:117-120）。
     *
     * @throws IllegalStateException 任一条规则不过（消息带表名 / 行 id / 字段名 / 实际值）
     */
    public static void validate(ConfigTables tables) {
        validate(tables.guildRule().find(RULE_ROW_ID).orElse(null), tables.guildLevel().all());
    }

    /**
     * 纯校验（基线 validateGuildTables，guild_manage_logic.go:128-196；单测 guild_manage_logic_test.go:677）。
     *
     * <ul>
     *   <li>GuildRule[1] 必须存在；application_expire_hours ∈ [1, 720]；每人上限 ∈ [1, 10]；每帮上限 ∈ [1, 500]；</li>
     *   <li>GuildLevel 非空、没有 null 行；按 id 排序后从 1 连续（重复 id 同样被抓到）；max_members ∈ [2, 100]；
     *       max_officers &lt; max_members；两个上限都不随等级递减；<b>只有最后一行</b> upgrade_cost_funds == 0。</li>
     * </ul>
     *
     * @param rule   GuildRule[1]；null = 表里没有这一行
     * @param levels GuildLevel 全部行，顺序不作假设（这里复制一份再排序，不动入参）
     * @throws IllegalStateException 任一条规则不过
     */
    public static void validate(GuildRuleTable rule, List<GuildLevelTable> levels) {
        if (rule == null) {
            throw fail("GuildRule 表缺少 id=%d 的规则行", RULE_ROW_ID);
        }
        checkRange("application_expire_hours", rule.getApplicationExpireHours(),
                MIN_APPLICATION_EXPIRE_HOURS, MAX_APPLICATION_EXPIRE_HOURS);
        checkRange("max_pending_applications_per_player", rule.getMaxPendingApplicationsPerPlayer(),
                MIN_PENDING_PER_PLAYER, MAX_PENDING_PER_PLAYER);
        checkRange("max_pending_applications_per_guild", rule.getMaxPendingApplicationsPerGuild(),
                MIN_PENDING_PER_GUILD, MAX_PENDING_PER_GUILD);

        if (levels == null || levels.isEmpty()) {
            throw fail("GuildLevel 表为空:建帮要读第 %d 级的 max_members", GuildLimits.DEFAULT_INIT_LEVEL);
        }
        List<GuildLevelTable> sorted = new ArrayList<>(levels.size());
        for (int i = 0; i < levels.size(); i++) {
            GuildLevelTable row = levels.get(i);
            if (row == null) {
                throw fail("GuildLevel 表第 %d 行为空", i + 1);
            }
            sorted.add(row);
        }
        sorted.sort(Comparator.comparingLong(row -> Integer.toUnsignedLong(row.getId())));

        long prevMaxMembers = 0;
        long prevMaxOfficers = 0;
        for (int i = 0; i < sorted.size(); i++) {
            GuildLevelTable row = sorted.get(i);
            long level = i + 1L;
            long id = Integer.toUnsignedLong(row.getId());
            // id 必须从 1 连续：guild.level 直接当 id 查表，缺一级就等于那一级的帮会全部读不到配表。
            if (id != level) {
                throw fail("GuildLevel.id 必须从 1 连续递增:第 %d 行 id=%d,期望 %d", i + 1, id, level);
            }
            long maxMembers = Integer.toUnsignedLong(row.getMaxMembers());
            long maxOfficers = Integer.toUnsignedLong(row.getMaxOfficers());
            if (maxMembers < MIN_GUILD_MAX_MEMBERS || maxMembers > GuildLimits.MAX_GUILD_MEMBERS_CAP) {
                throw fail("GuildLevel[%d].max_members=%d 越界,应在 [%d,%d]",
                        level, maxMembers, MIN_GUILD_MAX_MEMBERS, GuildLimits.MAX_GUILD_MEMBERS_CAP);
            }
            // 长老上限必须严格小于成员上限：相等就意味着可以把全帮任命成长老。
            if (maxOfficers >= maxMembers) {
                throw fail("GuildLevel[%d].max_officers=%d 必须小于 max_members=%d", level, maxOfficers, maxMembers);
            }
            // 升级只该让帮会变强：递减会让「升级后现有成员超编」成为常态，而本设计不做强制踢人。
            if (maxMembers < prevMaxMembers) {
                throw fail("GuildLevel[%d].max_members=%d 小于上一级的 %d,成员上限不得随等级递减",
                        level, maxMembers, prevMaxMembers);
            }
            if (maxOfficers < prevMaxOfficers) {
                throw fail("GuildLevel[%d].max_officers=%d 小于上一级的 %d,长老上限不得随等级递减",
                        level, maxOfficers, prevMaxOfficers);
            }
            // upgrade_cost_funds == 0 的语义是「满级」，所以只允许出现在最后一行；写在中间会让那一级永远升不上去且没有任何报错。
            boolean last = i == sorted.size() - 1;
            long cost = row.getUpgradeCostFunds();
            if ((cost == 0) != last) {
                if (last) {
                    throw fail("GuildLevel[%d].upgrade_cost_funds=%s:最后一级必须为 0(满级)", level, Long.toUnsignedString(cost));
                }
                throw fail("GuildLevel[%d].upgrade_cost_funds=0:只有最后一级允许为 0(满级)", level);
            }
            prevMaxMembers = maxMembers;
            prevMaxOfficers = maxOfficers;
        }
    }

    // ================================================================ 运行期现查

    /**
     * 现查 GuildRule[1] 并换算成申请规则（guild_manage_logic.go:54-64）。
     *
     * @return null = 配表缺行；调用方必须按故障处理（信封 1003，基线 Internal {@code "GuildRule row 1 missing"}），不得当成「无限制」
     */
    public static ApplicationRules applicationRules(ConfigTables tables) {
        GuildRuleTable row = tables.guildRule().find(RULE_ROW_ID).orElse(null);
        if (row == null) {
            return null;
        }
        return new ApplicationRules(Integer.toUnsignedLong(row.getApplicationExpireHours()) * MILLIS_PER_HOUR,
                row.getMaxPendingApplicationsPerPlayer(), row.getMaxPendingApplicationsPerGuild());
    }

    /**
     * 按帮会当前等级查长老上限（guild_manage_logic.go:69-75 officerCapFromTable）。
     *
     * @param level guild.level（uint32）
     * @return 空 = GuildLevel 缺该等级行；仓储据此回 {@link GuildReject#LEVEL_CONFIG_MISSING}（故障），绝不能默认成「不限」
     */
    public static OptionalInt officerCap(ConfigTables tables, int level) {
        return tables.guildLevel().find(level)
                .map(row -> OptionalInt.of(row.getMaxOfficers()))
                .orElse(OptionalInt.empty());
    }

    /**
     * 建帮用的成员上限：GuildLevel[1].max_members（guild_manage_logic.go:79-85 initialMaxMembers）。
     *
     * @return 空 = 缺第 1 级；建帮按故障处理（基线 Internal {@code "GuildLevel row 1 missing"}，guild_logic.go:217-223）
     */
    public static OptionalInt initialMaxMembers(ConfigTables tables) {
        return tables.guildLevel().find(GuildLimits.DEFAULT_INIT_LEVEL)
                .map(row -> OptionalInt.of(row.getMaxMembers()))
                .orElse(OptionalInt.empty());
    }

    /**
     * GuildInfo 装配用的两个纯展示字段（guild_manage_logic.go:90-96 levelDisplay）。
     *
     * @return null = 缺该等级行；调用方留 0 并记 ERROR，不让整次读失败（guild_logic.go:777-784）
     */
    public static LevelDisplay levelDisplay(ConfigTables tables, int level) {
        return tables.guildLevel().find(level)
                .map(row -> new LevelDisplay(row.getMaxOfficers(), row.getUpgradeCostFunds()))
                .orElse(null);
    }

    private static void checkRange(String field, int raw, int min, int max) {
        long v = Integer.toUnsignedLong(raw);
        if (v < min || v > max) {
            throw fail("GuildRule[%d].%s=%d 越界,应在 [%d,%d]", RULE_ROW_ID, field, v, min, max);
        }
    }

    private static IllegalStateException fail(String format, Object... args) {
        return new IllegalStateException(String.format(format, args));
    }
}
