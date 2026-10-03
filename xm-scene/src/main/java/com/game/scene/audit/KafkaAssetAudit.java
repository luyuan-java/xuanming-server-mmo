package com.game.scene.audit;

import com.game.audit.proto.AssetKind;
import com.game.audit.proto.TransactionLogRecord;
import com.game.audit.proto.TransactionReason;
import com.game.scene.world.SceneClock;

/**
 * 资产流水发往 Kafka（经 {@link AuditPipeline}）。在场景逻辑线程上调用：只组装不可变的草稿（时刻、zone 在这里盖上），立刻返回。
 * 货币增加 / 物品入包记获得方（to_player），货币扣减 / 物品销毁记扣减方（from_player），同 mmorpg TransactionLogSystem。
 */
public final class KafkaAssetAudit implements AssetAudit {

    private final AuditPipeline pipeline;
    private final SceneClock clock;
    private final int zoneId;

    public KafkaAssetAudit(AuditPipeline pipeline, SceneClock clock, int zoneId) {
        this.pipeline = pipeline;
        this.clock = clock;
        this.zoneId = zoneId;
    }

    @Override
    public void currencyChanged(long playerId, int currencyType, long delta, long before, long after, Reason reason) {
        TransactionLogRecord.Builder record = TransactionLogRecord.newBuilder()
                .setTimeMs(clock.epochMillis())
                .setReason(reasonOf(reason))
                .setKind(AssetKind.ASSET_CURRENCY)
                .setCurrencyType(currencyType)
                .setCurrencyDelta(delta)
                .setBalanceBefore(before)
                .setBalanceAfter(after)
                .setZoneId(zoneId);
        if (delta >= 0) {
            record.setToPlayer(playerId);
        } else {
            record.setFromPlayer(playerId);
        }
        pipeline.submitTransaction(record.build());
    }

    @Override
    public void itemGained(long playerId, long itemUuid, int configId, long quantity, Reason reason, long correlationId,
                           String extra) {
        pipeline.submitTransaction(item(itemUuid, configId, quantity, reason, correlationId, extra)
                .setToPlayer(playerId).build());
    }

    @Override
    public void itemDestroyed(long playerId, long itemUuid, int configId, long quantity, Reason reason,
                              long correlationId, String extra) {
        pipeline.submitTransaction(item(itemUuid, configId, quantity, reason, correlationId, extra)
                .setFromPlayer(playerId).build());
    }

    private TransactionLogRecord.Builder item(long itemUuid, int configId, long quantity, Reason reason,
                                              long correlationId, String extra) {
        return TransactionLogRecord.newBuilder()
                .setTimeMs(clock.epochMillis())
                .setReason(reasonOf(reason))
                .setKind(AssetKind.ASSET_ITEM)
                .setItemUuid(itemUuid)
                .setItemConfigId(configId)
                // 数量是 uint32（基线同）：一批的量本来就不超过 2^32−1
                .setItemQuantity((int) Math.min(quantity, 0xFFFF_FFFFL))
                .setCorrelationId(correlationId)
                .setExtra(boundedExtra(extra))
                .setZoneId(zoneId);
    }

    /** extra 超过列宽时整段换成截断标记（截半个 JSON 没有意义）。 */
    static String boundedExtra(String extra) {
        if (extra == null) {
            return "";
        }
        return extra.length() > MAX_EXTRA ? EXTRA_TRUNCATED : extra;
    }

    /** 新增 Reason 不加映射编译不过（穷举 switch），流水原因不会悄悄变成未指定。 */
    static TransactionReason reasonOf(Reason reason) {
        return switch (reason) {
            case GM_GRANT -> TransactionReason.TX_GM_GRANT;
            case GM_DEDUCT -> TransactionReason.TX_GM_DEDUCT;
            case ATTRIBUTE_RESET -> TransactionReason.TX_ATTRIBUTE_RESET;
            case ATTRIBUTE_SCHEME_CREATE -> TransactionReason.TX_ATTRIBUTE_SCHEME_CREATE;
            case SYSTEM_GRANT -> TransactionReason.TX_SYSTEM_GRANT;
            case ITEM_DESTROY -> TransactionReason.TX_ITEM_DESTROY;
        };
    }
}
