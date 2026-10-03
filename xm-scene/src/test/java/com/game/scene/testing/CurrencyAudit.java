package com.game.scene.testing;

import com.game.scene.audit.AssetAudit;

/** 只关心货币流水的测试用审计出口（物品流水忽略），可以写成 lambda：{@code (CurrencyAudit) (playerId, ...) -> ...}。 */
@FunctionalInterface
public interface CurrencyAudit extends AssetAudit {

    @Override
    default void itemGained(long playerId, long itemUuid, int configId, long quantity, Reason reason, long correlationId,
                            String extra) {
    }

    @Override
    default void itemDestroyed(long playerId, long itemUuid, int configId, long quantity, Reason reason,
                               long correlationId, String extra) {
    }
}
