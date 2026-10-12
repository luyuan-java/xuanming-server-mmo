package com.game.guild.zone;

import com.game.guild.store.GuildStore;

/**
 * 合服闸门（基线 MergeFence，merge_fence.go；guild-spec §1.13、D4）。基线只读 data_service mapping Redis 的
 * {@code merge:in_progress:{zone}}，只做 EXISTS，键存在即封锁。
 *
 * <p>两处使用，判据相同：
 * <ul>
 *   <li>事务外 {@code mergeFenceTip}（所有写 RPC 的前置，查请求者的归属区）：命中 → 14013「zone merging」，读失败 → 14013
 *       「merge fence unreadable」（fail-closed，guild_logic.go:306-320）；</li>
 *   <li>事务内（解散，锁住 guild 行后按行里的 zone_id 再判一次，{@link #inTransaction}）：命中或读失败 → {@code ZONE_MERGING}
 *       （economyFence，economy_logic.go:288-311）。</li>
 * </ul>
 * zone 为 0（内部调用）一律放行。
 *
 * <p>Java 首批不做合服（architecture.md:617-620），4.4 只有恒放行的 {@link #NONE}：14013 在 Java 暂不出现，检查点照样保留，
 * 将来接上真实现时不动调用方。
 */
@FunctionalInterface
public interface MergeFence {

    /**
     * @return 该 zone 是否正在合服
     * @throws Exception 闸门状态读不出来（调用方按合服中处理，fail-closed）
     */
    boolean mergeInProgress(int zoneId) throws Exception;

    /** 4.4：没有合服，恒放行（D4）。 */
    MergeFence NONE = zoneId -> false;

    /** 事务内闸门（存储在锁住 guild 行后按行里的 zone_id 调它）；{@link #NONE} 直接给存储恒放行的 {@code ZoneFence.OPEN}。 */
    default GuildStore.ZoneFence inTransaction() {
        if (this == NONE) {
            return GuildStore.ZoneFence.OPEN;
        }
        return zoneId -> zoneId != 0 && mergeInProgress(zoneId);
    }
}
