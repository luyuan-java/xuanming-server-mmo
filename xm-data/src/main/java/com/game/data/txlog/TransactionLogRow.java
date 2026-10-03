package com.game.data.txlog;

import com.game.audit.proto.TransactionLogRecord;

/**
 * 一行资产流水（{@code transaction_log}）。无符号整数按位存进 long / int：Java 版发出的号与玩家号都小于 2^63，
 * 写进 BIGINT UNSIGNED 时是正数；uint32 字段（币种、zone）同样都小于 2^31。
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
