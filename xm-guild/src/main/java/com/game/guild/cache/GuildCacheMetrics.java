package com.game.guild.cache;

/**
 * 缓存层向外报告结果的监听接口（服务装配时由 {@code GuildMetrics} 绑定到 Micrometer；单测可换成记录器）。
 *
 * <p>对应 guild-spec §8.2：{@code xm_guild_cache_total{cache, result}}（Java 增项，同 friend 的 {@code xm_friend_cache_total}）与
 * {@code xm_guild_cache_invalidation_failures_total{op}}（基线 {@code guild_cache_invalidate_failed_total{op}}）。
 * 标签全部来自下面的固定枚举（低基数，绝不带 guild_id / player_id），绑定方应在启动时把全部组合预建为 0。
 *
 * <p>实现必须线程安全、不阻塞、不抛异常（会在工作线程、Redisson 回调线程与后台重试线程上被调用）。
 */
public interface GuildCacheMetrics {

    /** 什么都不记的实现（单测 / 未装配指标时用）。 */
    GuildCacheMetrics NONE = new GuildCacheMetrics() {
        @Override
        public void cache(CacheKind kind, CacheResult result) {
        }

        @Override
        public void invalidationGaveUp(InvalidationOp op) {
        }
    };

    /** 哪一类缓存（标签 {@code cache}）。 */
    enum CacheKind {
        /** 帮会快照 {@code xm:guild:{g:<gid>}:snap}。 */
        SNAPSHOT("snapshot"),
        /** 玩家 → 帮会映射 {@code xm:guild:{p:<pid>}:gid}。 */
        MAPPING("mapping");

        private final String label;

        CacheKind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 一次缓存读 / 回填的结局（标签 {@code result}）。 */
    enum CacheResult {
        /** 数据键命中。 */
        HIT("hit"),
        /** 未命中，由单飞领头者回源。 */
        MISS("miss"),
        /** 回填被拒：读库期间代次变了或代次缺失（写者已失效，旧快照不落地）。 */
        FILL_SKIPPED("fill_skipped"),
        /** 回填的 Redis 调用失败（只记日志与指标，读请求照常返回 MySQL 结果）。 */
        FILL_FAILED("fill_failed"),
        /** 读数据键 / 代次键失败、缓存值坏（读请求回依赖故障）。 */
        ERROR("error");

        private final String label;

        CacheResult(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 记一次缓存结局。 */
    void cache(CacheKind kind, CacheResult result);

    /** 提交后失效在同步尝试与后台有界重试之后仍然失败（每次放弃计一次，不按键数计；同基线 invalidateGaveUp）。 */
    void invalidationGaveUp(InvalidationOp op);
}
