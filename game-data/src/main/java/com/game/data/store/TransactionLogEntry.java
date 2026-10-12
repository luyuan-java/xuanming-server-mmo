package com.game.data.store;

/** 查询结果的一行资产流水（MyBatis 按列名下划线转驼峰填充）。 */
public class TransactionLogEntry {

    private long txId;
    private long timeMs;
    private int reason;
    private int kind;
    private long fromPlayer;
    private long toPlayer;
    private int currencyType;
    private long currencyDelta;
    private long balanceBefore;
    private long balanceAfter;
    private long itemUuid;
    private int itemConfigId;
    private int itemQuantity;
    private long correlationId;
    private String extra = "";
    private int zoneId;
    private long ingestedAt;

    public long getTxId() {
        return txId;
    }

    public void setTxId(long txId) {
        this.txId = txId;
    }

    public long getTimeMs() {
        return timeMs;
    }

    public void setTimeMs(long timeMs) {
        this.timeMs = timeMs;
    }

    public int getReason() {
        return reason;
    }

    public void setReason(int reason) {
        this.reason = reason;
    }

    public int getKind() {
        return kind;
    }

    public void setKind(int kind) {
        this.kind = kind;
    }

    public long getFromPlayer() {
        return fromPlayer;
    }

    public void setFromPlayer(long fromPlayer) {
        this.fromPlayer = fromPlayer;
    }

    public long getToPlayer() {
        return toPlayer;
    }

    public void setToPlayer(long toPlayer) {
        this.toPlayer = toPlayer;
    }

    public int getCurrencyType() {
        return currencyType;
    }

    public void setCurrencyType(int currencyType) {
        this.currencyType = currencyType;
    }

    public long getCurrencyDelta() {
        return currencyDelta;
    }

    public void setCurrencyDelta(long currencyDelta) {
        this.currencyDelta = currencyDelta;
    }

    public long getBalanceBefore() {
        return balanceBefore;
    }

    public void setBalanceBefore(long balanceBefore) {
        this.balanceBefore = balanceBefore;
    }

    public long getBalanceAfter() {
        return balanceAfter;
    }

    public void setBalanceAfter(long balanceAfter) {
        this.balanceAfter = balanceAfter;
    }

    public long getItemUuid() {
        return itemUuid;
    }

    public void setItemUuid(long itemUuid) {
        this.itemUuid = itemUuid;
    }

    public int getItemConfigId() {
        return itemConfigId;
    }

    public void setItemConfigId(int itemConfigId) {
        this.itemConfigId = itemConfigId;
    }

    public int getItemQuantity() {
        return itemQuantity;
    }

    public void setItemQuantity(int itemQuantity) {
        this.itemQuantity = itemQuantity;
    }

    public long getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(long correlationId) {
        this.correlationId = correlationId;
    }

    public String getExtra() {
        return extra;
    }

    public void setExtra(String extra) {
        this.extra = extra == null ? "" : extra;
    }

    public int getZoneId() {
        return zoneId;
    }

    public void setZoneId(int zoneId) {
        this.zoneId = zoneId;
    }

    public long getIngestedAt() {
        return ingestedAt;
    }

    public void setIngestedAt(long ingestedAt) {
        this.ingestedAt = ingestedAt;
    }
}
