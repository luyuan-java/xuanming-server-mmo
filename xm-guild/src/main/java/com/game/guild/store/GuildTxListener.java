package com.game.guild.store;

/**
 * 事务基座的指标钩子（基线 guild_tx_*_total{op}，guild_manage_repo.go:361-383、:268-274）。服务装配时绑到 Micrometer
 * （{@code xm_guild_tx_deadlocks_total} / {@code xm_guild_tx_lock_wait_timeouts_total} / {@code xm_guild_tx_budget_exceeded_total}，
 * 标签只有 {@code op}，取 {@link GuildTxOp} 的固定集合）；锁序并发回归把它换成记录器，断言「被重跑吸收掉的死锁」为零次。
 *
 * <p>实现必须便宜、不抛异常（在工作线程上、事务回滚之后同步调用）。
 */
public interface GuildTxListener {

    /** 判定一次「事务已被整体回滚、可重跑」（1213 / TiDB 9007）：每判一次调一次，含用尽重试的最后一次。 */
    void deadlockObserved(GuildTxOp op);

    /** 1205 锁等待超时（不重试，回 WRITE_CONFLICT）。 */
    void lockWaitTimeout(GuildTxOp op);

    /** 子预算到期而请求还活着（不内部重试，回 WRITE_CONFLICT）。 */
    void budgetExceeded(GuildTxOp op);

    GuildTxListener NONE = new GuildTxListener() {
        @Override
        public void deadlockObserved(GuildTxOp op) {
        }

        @Override
        public void lockWaitTimeout(GuildTxOp op) {
        }

        @Override
        public void budgetExceeded(GuildTxOp op) {
        }
    };
}
