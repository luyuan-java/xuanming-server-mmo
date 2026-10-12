package com.game.guild.store;

import com.game.guild.rules.GuildLimits;

/**
 * 帮会写路径的 op 固定集合（基线 guild_manage_repo.go:394-426）：事务指标（死锁 / 子预算 / 锁等待）与提交后缓存失效指标的
 * {@code op} 标签都只取这里的值，绝不放 guild_id / player_id（AGENTS.md §5）。新批次只能往这里追加，不许在调用点写字面量。
 *
 * <p>4.5 / 4.6 的值（{@link #UPGRADE} … {@link #SHOP}）一次定全（基线 90-consistency Y-04），4.4 不用但照样预注册；
 * {@link #INSERT_GUARD} 只给启动期建全局插入守卫哨兵行（{@link GuildStartupChecks#ensureGlobalInsertGuard}）用。
 * {@link #VERIFY_MAPPING} 与 {@link #SCORE} 不开事务，只作缓存失效的标签（映射自愈；4.4 没有改分入口，D12）。
 */
public enum GuildTxOp {
    CREATE("create"),
    SET_ROLE("set_role"),
    KICK("kick"),
    TRANSFER("transfer"),
    LEAVE("leave"),
    APPLY("apply"),
    CANCEL("cancel"),
    REVIEW("review"),
    DISBAND("disband"),
    ANNOUNCEMENT("announcement"),
    VERIFY_MAPPING("verify_mapping"),
    SCORE("score"),
    // ---- 4.5 / 4.6 预留（guild_manage_repo.go:410-421）----
    UPGRADE("upgrade"),
    ASSET_FINALIZE("asset_finalize"),
    ACTIVITY("activity"),
    TRIAL_SETTLE("trial_settle"),
    DONATE("donate"),
    SHOP("shop"),
    // ---- 启动期（guild_manage_repo.go:423-425）----
    INSERT_GUARD("insert_guard");

    private final String label;

    GuildTxOp(String label) {
        this.label = label;
    }

    /** 指标标签值（与基线 op 字符串逐字相同）。 */
    public String label() {
        return label;
    }

    /**
     * 单次事务尝试的子预算（毫秒，基线 txBudgetFor，guild_manage_repo.go:125-131）：解散 2500，其余 1500。<b>固定映射</b>，
     * 新批次要改就改这里，不许在调用点传数字；实际取 {@code min(请求剩余, 它)}，每次重跑各给一份完整子预算。
     */
    public long budgetMillis() {
        return this == DISBAND ? GuildLimits.TX_BUDGET_DISBAND_MS : GuildLimits.TX_BUDGET_MS;
    }
}
