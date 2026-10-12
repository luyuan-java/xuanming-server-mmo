package com.game.data.store;

/** 一名玩家的归属区与建角时刻（{@link PersistedPlayerMapper#findBrief} / {@link PersistedPlayerMapper#listInZone}）。 */
public class PlayerBrief {

    private long playerId;
    private int zoneId;
    private long createdAt;

    public PlayerBrief() {
    }

    public PlayerBrief(long playerId, int zoneId, long createdAt) {
        this.playerId = playerId;
        this.zoneId = zoneId;
        this.createdAt = createdAt;
    }

    public long getPlayerId() {
        return playerId;
    }

    public void setPlayerId(long playerId) {
        this.playerId = playerId;
    }

    public int getZoneId() {
        return zoneId;
    }

    public void setZoneId(int zoneId) {
        this.zoneId = zoneId;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }
}
