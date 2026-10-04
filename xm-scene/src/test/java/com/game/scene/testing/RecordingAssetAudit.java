package com.game.scene.testing;

import com.game.scene.audit.AssetAudit;
import java.util.ArrayList;
import java.util.List;

/** 记下全部资产流水的测试用审计出口。 */
public final class RecordingAssetAudit implements AssetAudit {

    public record Currency(long playerId, int type, long delta, long before, long after, Reason reason,
                           long correlationId, String extra) {

        public Currency(long playerId, int type, long delta, long before, long after, Reason reason) {
            this(playerId, type, delta, before, after, reason, 0, "");
        }

        public Currency(long playerId, int type, long delta, long before, long after, Reason reason,
                        long correlationId) {
            this(playerId, type, delta, before, after, reason, correlationId, "");
        }
    }

    /** @param gained true = 入包（to_player），false = 销毁（from_player） */
    public record Item(boolean gained, long playerId, long itemUuid, int configId, long quantity, Reason reason,
                       long correlationId, String extra) {
    }

    public final List<Currency> currencies = new ArrayList<>();
    public final List<Item> items = new ArrayList<>();

    @Override
    public void currencyChanged(long playerId, int currencyType, long delta, long before, long after, Reason reason,
                                long correlationId, String extra) {
        currencies.add(new Currency(playerId, currencyType, delta, before, after, reason, correlationId, extra));
    }

    @Override
    public void itemGained(long playerId, long itemUuid, int configId, long quantity, Reason reason, long correlationId,
                           String extra) {
        items.add(new Item(true, playerId, itemUuid, configId, quantity, reason, correlationId, extra));
    }

    @Override
    public void itemDestroyed(long playerId, long itemUuid, int configId, long quantity, Reason reason,
                              long correlationId, String extra) {
        items.add(new Item(false, playerId, itemUuid, configId, quantity, reason, correlationId, extra));
    }
}
