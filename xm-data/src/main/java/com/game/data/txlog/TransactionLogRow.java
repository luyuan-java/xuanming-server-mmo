package com.game.data.txlog;

import com.game.audit.proto.TransactionLogRecord;

/**
 * 一行资产流水（{@code transaction_log}）。无符号整数按位存进 long / int（≥ 2^63 / 2^31 的值是负数）：余额的中间值（补缴抵扣链）、
 * 调用方给的关联号都可能越过 2^63，绑定与读取经 {@code TransactionLogMapper} 的无符号类型处理器。
 */
public record TransactionLogRow(long txId, long timeMs, int reason, int kind, long fromPlayer, long toPlayer,
                                int currencyType, long currencyDelta, long balanceBefore, long balanceAfter,
                                long itemUuid, int itemConfigId, int itemQuantity, long correlationId, String extra,
                                int zoneId) {

    static TransactionLogRow of(TransactionLogRecord r) {
        return new TransactionLogRow(r.getTxId(), r.getTimeMs(), r.getReasonValue(), r.getKindValue(), r.getFromPlayer(),
                r.getToPlayer(), r.getCurrencyType(), r.getCurrencyDelta(), r.getBalanceBefore(), r.getBalanceAfter(),
                r.getItemUuid(), r.getItemConfigId(), r.getItemQuantity(), r.getCorrelationId(), r.getExtra(),
                r.getZoneId());
    }
}
