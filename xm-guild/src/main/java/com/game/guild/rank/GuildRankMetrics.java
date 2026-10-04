package com.game.guild.rank;

/**
 * 排行维护向外报告结果的监听接口（服务装配时由 {@code GuildMetrics} 绑定到 Micrometer 的 {@code xm_guild_rank_ops_total{op, outcome}}，
 * guild-spec §8.2；基线只有日志）。标签全部来自固定枚举，绑定方应在启动时把 3 × 3 个组合预建为 0。
 *
 * <p>实现必须线程安全、不阻塞、不抛异常。
 */
public interface GuildRankMetrics {

    /** 什么都不记的实现（单测 / 未装配指标时用）。 */
    GuildRankMetrics NONE = (op, outcome) -> {
    };

    /** 排行维护动作（标签 {@code op}）。 */
    enum RankOp {
        /** 单帮入榜 / 改分（建帮以 0 分入榜）。 */
        ADD("add"),
        /** 从全服榜与全部区榜移除（解散）。 */
        REMOVE("remove"),
        /** 从 MySQL 全量重建（启动期）。 */
        REBUILD("rebuild");

        private final String label;

        RankOp(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 结局（标签 {@code outcome}）。 */
    enum RankOutcome {
        OK("ok"),
        /** 在等锁上限内没拿到维护锁（请求路径放弃、只记日志；重建则拒启）。 */
        LOCK_TIMEOUT("lock_timeout"),
        /** Redis 故障、锁中途失效、临时键不完整、回源失败。 */
        ERROR("error");

        private final String label;

        RankOutcome(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 记一次排行维护的结局。 */
    void rankOp(RankOp op, RankOutcome outcome);
}
