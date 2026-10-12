package com.game.data.store;

/**
 * 一名玩家的已落盘数据（{@link PersistedPlayerMapper#find}）。没有 {@code player_state} 行时 {@link #hasState()} 为 false
 * （从未写过 = 各玩法取初始状态，读到空字节）。号与 epoch 都小于 2^63（Java 版发出的号），按有符号 long 存。
 */
public class PersistedPlayer {

    private long playerId;
    private int zoneId;
    private int level;
    private int sceneConfigId;
    private double posX;
    private double posY;
    private double posZ;
    private long ownerEpoch;
    private int ownerReleased;
    private long ownerLeaseUntil;
    private long createdAt;
    private long updatedAt;
    private byte[] stateData;
    private Long stateSavedEpoch;
    private Long stateUpdatedAt;

    public boolean hasState() {
        return stateUpdatedAt != null;
    }

    /** 玩法数据原字节；没有这一行为空数组（= 默认 PlayerState）。 */
    public byte[] stateBytes() {
        return stateData == null ? new byte[0] : stateData;
    }

    /** 已落盘内容的时刻：player_state.updated_at，没有这一行时取 player.updated_at（data-ops-spec §3.2）。 */
    public long persistedAtMs() {
        return stateUpdatedAt != null ? stateUpdatedAt : updatedAt;
    }

    /** 写入这份玩法数据的 epoch；没有这一行为 0（内容不来自任何一次写回）。 */
    public long savedEpoch() {
        return stateSavedEpoch != null ? stateSavedEpoch : 0;
    }

    /**
     * 此刻是否有写者持有归属（未释放且租约未过期）：有就说明 scene 内存里的状态可能比库里新（至多一个存盘周期）。
     * 比在线目录（gate 维护）更贴近「已落盘是否滞后」：断线重连保留期内玩家不在线，但 scene 仍持有、仍可能有未落盘的改动。
     */
    public boolean ownerHeld(long nowMs) {
        return ownerReleased == 0 && ownerLeaseUntil > nowMs;
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

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public int getSceneConfigId() {
        return sceneConfigId;
    }

    public void setSceneConfigId(int sceneConfigId) {
        this.sceneConfigId = sceneConfigId;
    }

    public double getPosX() {
        return posX;
    }

    public void setPosX(double posX) {
        this.posX = posX;
    }

    public double getPosY() {
        return posY;
    }

    public void setPosY(double posY) {
        this.posY = posY;
    }

    public double getPosZ() {
        return posZ;
    }

    public void setPosZ(double posZ) {
        this.posZ = posZ;
    }

    public long getOwnerEpoch() {
        return ownerEpoch;
    }

    public void setOwnerEpoch(long ownerEpoch) {
        this.ownerEpoch = ownerEpoch;
    }

    public int getOwnerReleased() {
        return ownerReleased;
    }

    public void setOwnerReleased(int ownerReleased) {
        this.ownerReleased = ownerReleased;
    }

    public long getOwnerLeaseUntil() {
        return ownerLeaseUntil;
    }

    public void setOwnerLeaseUntil(long ownerLeaseUntil) {
        this.ownerLeaseUntil = ownerLeaseUntil;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public byte[] getStateData() {
        return stateData;
    }

    public void setStateData(byte[] stateData) {
        this.stateData = stateData;
    }

    public Long getStateSavedEpoch() {
        return stateSavedEpoch;
    }

    public void setStateSavedEpoch(Long stateSavedEpoch) {
        this.stateSavedEpoch = stateSavedEpoch;
    }

    public Long getStateUpdatedAt() {
        return stateUpdatedAt;
    }

    public void setStateUpdatedAt(Long stateUpdatedAt) {
        this.stateUpdatedAt = stateUpdatedAt;
    }
}
