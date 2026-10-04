package com.game.guild.cache;

/**
 * 触发提交后缓存失效的写操作（指标 {@code xm_guild_cache_invalidation_failures_total{op}} 的标签 + 失效日志）。
 *
 * <p><b>固定集合</b>，与基线 guild_manage_repo.go:394-426 的 op 常量逐个对应（标签串相同），含 4.5 / 4.6 预留值与 {@code insert_guard}：
 * 新增批次只能往这里加，调用点不写字面量——否则标签基数就不再可证（AGENTS.md §5）。apply / cancel / insert_guard 按失效矩阵
 * （guild-spec §1.11）不会失效任何键，保留它们只为与基线的标签集合一致（指标启动即全部预建）。
 */
public enum InvalidationOp {
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
    // 4.5 / 4.6 预留（guild_manage_repo.go:410-421）
    UPGRADE("upgrade"),
    ASSET_FINALIZE("asset_finalize"),
    ACTIVITY("activity"),
    TRIAL_SETTLE("trial_settle"),
    DONATE("donate"),
    SHOP("shop"),
    // 启动期建全局插入守卫哨兵行（guild_manage_repo.go:423-425）
    INSERT_GUARD("insert_guard");

    private final String label;

    InvalidationOp(String label) {
        this.label = label;
    }

    /** 指标标签 / 日志里的串（与基线 op 常量相同）。 */
    public String label() {
        return label;
    }

    /**
     * 按基线标签串取值（给自带 op 枚举的调用方做映射，例如事务基座的 op）。
     *
     * @throws IllegalArgumentException 不在固定集合里
     */
    public static InvalidationOp ofLabel(String label) {
        for (InvalidationOp op : values()) {
            if (op.label.equals(label)) {
                return op;
            }
        }
        throw new IllegalArgumentException("未知的帮会写操作标签: " + label);
    }
}
