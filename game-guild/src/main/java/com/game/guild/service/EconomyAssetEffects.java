package com.game.guild.service;

import com.game.common.deadline.Deadline;
import com.game.guild.asset.DeliveryOrigin;
import com.game.guild.asset.GuildAssetStore;
import com.game.guild.cache.GuildCacheInvalidator;
import com.game.guild.cache.InvalidationOp;
import com.game.guild.metrics.GuildMetrics;
import com.game.guild.push.GuildPushes;
import com.game.guild.store.Invalidation;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 资产账本 Store 提交之后的副作用（{@link GuildAssetStore.Listener} 的服务进程实现；基线 GuildAssetStore 提交后的 invalidateAfterCommit /
 * orphan 计数 / 清理计数与 {@code OnFinalized = guildLogic.OnAssetFinalized}，guild.go:355-357、economy_logic.go:667-691）：
 * <ul>
 *   <li>终结改过资金 / 帮贡 → 失效 guild(G) 与 p 的映射（op = asset_finalize；先同步失效一次，失败的键交后台有界重试，永不抛）；</li>
 *   <li>orphan / 清理 → 指标；</li>
 *   <li>本次终结 → <b>只有后台循环（{@link DeliveryOrigin#LOOP}）终结的才推</b> FUNDS_CHANGED / DELIVERY_DONE（只推本人，E9）：同步投递里终结的，
 *       调用方自己拿着回包；人工终结不推（基线 OnFinalized 为 nil）。</li>
 * </ul>
 * 回调发生在 Store 的调用线程上（循环 worker、落库执行器、清理线程），必须便宜、不抛（Store 仍会兜住异常只记日志）。
 * 必须在循环与 Dubbo 暴露之前装好（构造即绑定，无可变字段）。线程安全。
 */
public final class EconomyAssetEffects implements GuildAssetStore.Listener {

    private static final Logger log = LoggerFactory.getLogger(EconomyAssetEffects.class);

    /**
     * 提交后同步失效的预算（后台 worker / 落库线程上，没有请求预算可继承）。取得短：同步投递的回读排在它之后，基线这一步也只剩 settle 的余量；
     * 失败的键交后台有界重试（100 / 400 / 1600 ms），不影响终结。
     */
    static final long INVALIDATE_BUDGET_MS = 300L;

    private final GuildCacheInvalidator invalidator;
    private final GuildPushes pushes;
    private final GuildMetrics metrics;

    public EconomyAssetEffects(GuildCacheInvalidator invalidator, GuildPushes pushes, GuildMetrics metrics) {
        this.invalidator = Objects.requireNonNull(invalidator, "invalidator");
        this.pushes = pushes;
        this.metrics = metrics;
    }

    @Override
    public void invalidate(Invalidation invalidation) {
        if (invalidation.isEmpty()) {
            return;
        }
        InvalidationOp op;
        try {
            op = InvalidationOp.ofLabel(invalidation.op().label());
        } catch (IllegalArgumentException e) {
            log.error("[GuildAsset] 未登记的失效 op {}，改记为 asset_finalize", invalidation.op().label(), e);
            op = InvalidationOp.ASSET_FINALIZE;
        }
        invalidator.afterCommit(op, invalidation.guildId(), invalidation.playerIds(), Deadline.after(INVALIDATE_BUDGET_MS));
    }

    @Override
    public void orphan(GuildAssetStore.Orphan orphan) {
        if (metrics != null) {
            metrics.assetOrphan(orphan);
        }
    }

    @Override
    public void cleanupDeleted(GuildAssetStore.CleanupTable table, long n) {
        if (metrics != null) {
            metrics.assetCleanupDeleted(table, n);
        }
    }

    @Override
    public void finalized(GuildAssetStore.FinalizedOp op) {
        if (pushes == null || op.origin() != DeliveryOrigin.LOOP) {
            return;
        }
        pushes.assetFinalized(op.kind(), op.guildId(), op.playerId());
    }
}
